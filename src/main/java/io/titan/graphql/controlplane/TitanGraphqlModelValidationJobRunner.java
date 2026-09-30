package io.titan.graphql.controlplane;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.titan.graphql.GraphqlException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

public final class TitanGraphqlModelValidationJobRunner {
    public static final String JOB_TYPE = "model.validate";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TitanGraphqlControlJobQueue queue;
    private final Supplier<TitanGraphqlModelValidationService> validation;

    public TitanGraphqlModelValidationJobRunner(
            TitanGraphqlControlJobQueue queue,
            TitanGraphqlModelValidationService validation
    ) {
        this(queue, () -> Objects.requireNonNull(validation, "validation service"));
    }

    public TitanGraphqlModelValidationJobRunner(
            TitanGraphqlControlJobQueue queue,
            Supplier<TitanGraphqlModelValidationService> validation
    ) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.validation = Objects.requireNonNull(validation, "validation service supplier");
    }

    public boolean runOne(Duration lease) throws SQLException {
        return runOne(lease, false);
    }

    public boolean runOneWithHeartbeat(Duration lease) throws SQLException {
        if (lease == null || lease.toMillis() < 1_000L) {
            throw new IllegalArgumentException("model validation heartbeat requires a lease of at least one second");
        }
        return runOne(lease, true);
    }

    private boolean runOne(Duration lease, boolean heartbeatEnabled) throws SQLException {
        var claimed = queue.claimNext(JOB_TYPE, lease);
        if (claimed.isEmpty()) {
            return false;
        }
        TitanGraphqlControlJobQueue.ClaimedJob job = claimed.orElseThrow();
        TitanGraphqlModelValidationService validationService;
        TitanGraphqlModelValidationService.Prepared prepared;
        TitanGraphqlControlJobLeaseHeartbeat heartbeat = heartbeatEnabled
                ? TitanGraphqlControlJobLeaseHeartbeat.start(queue, job, lease)
                : TitanGraphqlControlJobLeaseHeartbeat.disabled();
        try (heartbeat) {
            JsonNode payload = JSON.readTree(job.payloadJson());
            JsonNode draftId = payload.path("draftId");
            if (!draftId.isTextual() || draftId.asText().isBlank()) {
                throw new IllegalArgumentException("model validation job payload is invalid");
            }
            validationService = validation.get();
            prepared = validationService.prepare(draftId.asText());
        } catch (GraphqlException | IllegalArgumentException | JsonProcessingException failure) {
            if (!queue.fail(job, "INVALID_MODEL_VALIDATION_REQUEST")) {
                throw new SQLException("model validation job lease expired before failure recording", failure);
            }
            return true;
        } catch (RuntimeException failure) {
            if (!queue.retry(job, "MODEL_VALIDATION_WORKER_FAILURE")) {
                failure.addSuppressed(new SQLException(
                        "model validation job lease expired before retry recording"));
            }
            throw failure;
        }
        heartbeat.verify();
        String resultJson;
        try {
            resultJson = JSON.writeValueAsString(prepared.result());
        } catch (JsonProcessingException failure) {
            if (!queue.retry(job, "MODEL_VALIDATION_RESULT_FAILURE")) {
                throw new SQLException("model validation job lease expired before retry recording", failure);
            }
            throw new SQLException("model validation job result could not be serialized", failure);
        }
        if (!queue.renew(job, lease)) {
            throw new SQLException("model validation job lease expired before completion");
        }
        boolean completed;
        try {
            completed = validationService.publishAndComplete(prepared, queue, job, resultJson);
        } catch (RuntimeException failure) {
            if (!queue.retry(job, "MODEL_VALIDATION_WORKER_FAILURE")) {
                failure.addSuppressed(new SQLException(
                        "model validation job lease expired before retry recording"));
            }
            throw failure;
        }
        if (!completed) {
            throw new SQLException("model validation job lease expired before completion");
        }
        return true;
    }
}
