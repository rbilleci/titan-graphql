package io.titan.graphql.controlplane;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.titan.graphql.GraphqlException;
import io.titan.graphql.GraphqlRequestContext;
import io.titan.graphql.TitanGraphqlModelImportService;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class TitanGraphqlModelImportJobRunner {
    public static final String JOB_TYPE = "model.import";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TitanGraphqlControlJobQueue queue;
    private final Supplier<TitanGraphqlModelImportService> importer;

    public TitanGraphqlModelImportJobRunner(
            TitanGraphqlControlJobQueue queue,
            Supplier<TitanGraphqlModelImportService> importer
    ) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.importer = Objects.requireNonNull(importer, "model import service supplier");
    }

    public boolean runOne(Duration lease) throws SQLException {
        return runOne(lease, false);
    }

    public boolean runOneWithHeartbeat(Duration lease) throws SQLException {
        if (lease == null || lease.toMillis() < 1_000L) {
            throw new IllegalArgumentException("model import heartbeat requires a lease of at least one second");
        }
        return runOne(lease, true);
    }

    private boolean runOne(Duration lease, boolean heartbeatEnabled) throws SQLException {
        var claimed = queue.claimNext(JOB_TYPE, lease);
        if (claimed.isEmpty()) {
            return false;
        }
        TitanGraphqlControlJobQueue.ClaimedJob job = claimed.orElseThrow();
        TitanGraphqlModelImportService service;
        TitanGraphqlModelImportService.Prepared prepared;
        TitanGraphqlControlJobLeaseHeartbeat heartbeat = heartbeatEnabled
                ? TitanGraphqlControlJobLeaseHeartbeat.start(queue, job, lease)
                : TitanGraphqlControlJobLeaseHeartbeat.disabled();
        try (heartbeat) {
            JsonNode payload = JSON.readTree(job.payloadJson());
            String workspaceId = required(payload, "workspaceId");
            String yaml = required(payload, "yaml");
            String actorRole = required(payload, "actorRole");
            String actorKey = required(payload, "actorKey");
            String requestId = required(payload, "requestId");
            String idempotencyKey = required(payload, "idempotencyKey");
            GraphqlRequestContext context = new GraphqlRequestContext(
                    0L, actorRole, actorKey, "", requestId, idempotencyKey,
                    List.of(), List.of(), false, false, false, 0L);
            service = importer.get();
            prepared = service.prepare(Map.of("workspaceId", workspaceId, "yaml", yaml), context);
            if (!prepared.invocation().validate().valid()) {
                throw new IllegalArgumentException("model import command context is invalid");
            }
        } catch (GraphqlException | IllegalArgumentException | JsonProcessingException failure) {
            if (!queue.fail(job, "INVALID_MODEL_IMPORT_REQUEST")) {
                throw new SQLException("model import job lease expired before failure recording", failure);
            }
            return true;
        } catch (RuntimeException failure) {
            if (!queue.retry(job, "MODEL_IMPORT_WORKER_FAILURE")) {
                failure.addSuppressed(new SQLException("model import job lease expired before retry recording"));
            }
            throw failure;
        }
        heartbeat.verify();
        String resultJson;
        try {
            resultJson = JSON.writeValueAsString(prepared.result());
        } catch (JsonProcessingException failure) {
            if (!queue.retry(job, "MODEL_IMPORT_RESULT_FAILURE")) {
                throw new SQLException("model import job lease expired before retry recording", failure);
            }
            throw new SQLException("model import job result could not be serialized", failure);
        }
        if (!queue.renew(job, lease)) {
            throw new SQLException("model import job lease expired before completion");
        }
        boolean completed;
        try {
            completed = service.publishAndComplete(prepared, queue, job, resultJson);
        } catch (RuntimeException failure) {
            if (!queue.retry(job, "MODEL_IMPORT_WORKER_FAILURE")) {
                failure.addSuppressed(new SQLException("model import job lease expired before retry recording"));
            }
            throw failure;
        }
        if (!completed) {
            throw new SQLException("model import job lease expired before completion");
        }
        return true;
    }

    private static String required(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("model import job field '" + field + "' is required");
        }
        return value.asText();
    }
}
