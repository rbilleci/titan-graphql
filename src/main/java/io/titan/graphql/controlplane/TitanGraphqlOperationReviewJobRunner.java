package io.titan.graphql.controlplane;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class TitanGraphqlOperationReviewJobRunner {
    public static final String JOB_TYPE = "operation.review";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> REVIEW_ROLES = Set.of("operator", "admin", "platform");

    private final TitanGraphqlControlJobQueue queue;
    private final Supplier<TitanGraphqlDurableManagementStore> stores;
    private final Clock clock;

    public TitanGraphqlOperationReviewJobRunner(
            TitanGraphqlControlJobQueue queue,
            Supplier<TitanGraphqlDurableManagementStore> stores,
            Clock clock
    ) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.stores = Objects.requireNonNull(stores, "management store supplier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean runOne(Duration lease) throws SQLException {
        return runOne(lease, false);
    }

    public boolean runOneWithHeartbeat(Duration lease) throws SQLException {
        if (lease == null || lease.toMillis() < 1_000L) {
            throw new IllegalArgumentException("operation review heartbeat requires a lease of at least one second");
        }
        return runOne(lease, true);
    }

    private boolean runOne(Duration lease, boolean heartbeatEnabled) throws SQLException {
        var claimed = queue.claimNext(JOB_TYPE, lease);
        if (claimed.isEmpty()) {
            return false;
        }
        TitanGraphqlControlJobQueue.ClaimedJob job = claimed.orElseThrow();
        String observedOperationId;
        String reviewedBy;
        boolean approve;
        TitanGraphqlControlJobLeaseHeartbeat heartbeat = heartbeatEnabled
                ? TitanGraphqlControlJobLeaseHeartbeat.start(queue, job, lease)
                : TitanGraphqlControlJobLeaseHeartbeat.disabled();
        try (heartbeat) {
            try {
                JsonNode payload = JSON.readTree(job.payloadJson());
                observedOperationId = text(payload, "observedOperationId");
                reviewedBy = text(payload, "reviewedBy");
                String decision = text(payload, "decision");
                String actorRole = text(payload, "actorRole");
                text(payload, "requestId");
                text(payload, "idempotencyKey");
                if (observedOperationId.length() > 64 || reviewedBy.length() > 191
                        || !REVIEW_ROLES.contains(actorRole)
                        || (!"approve".equals(decision) && !"reject".equals(decision))) {
                    throw new IllegalArgumentException("operation review job payload is invalid");
                }
                approve = "approve".equals(decision);
            } catch (IllegalArgumentException | JsonProcessingException failure) {
                if (!queue.fail(job, "INVALID_OPERATION_REVIEW_REQUEST")) {
                    throw new SQLException("operation review job lease expired before failure recording", failure);
                }
                return true;
            }
            heartbeat.verify();
            if (!queue.renew(job, lease)) {
                throw new SQLException("operation review job lease expired before completion");
            }
            try {
                boolean completed = stores.get().reviewObservedOperationAndCompleteJob(
                        observedOperationId, reviewedBy, clock.instant().toString(), approve, queue, job);
                if (!completed) {
                    throw new SQLException("operation review job lease expired before completion");
                }
                heartbeat.verify();
                return true;
            } catch (IllegalArgumentException invalidObservation) {
                if (!queue.fail(job, "UNKNOWN_OBSERVED_OPERATION")) {
                    throw new SQLException("operation review job lease expired before failure recording",
                            invalidObservation);
                }
                return true;
            } catch (RuntimeException failure) {
                if (!queue.retry(job, "OPERATION_REVIEW_WORKER_FAILURE")) {
                    failure.addSuppressed(new SQLException("operation review job lease expired before retry recording"));
                }
                throw failure;
            }
        }
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("operation review job field '" + field + "' is required");
        }
        return value.asText();
    }
}
