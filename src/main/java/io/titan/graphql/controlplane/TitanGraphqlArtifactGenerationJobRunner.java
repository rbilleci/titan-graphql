package io.titan.graphql.controlplane;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.titan.graphql.GraphqlException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

public final class TitanGraphqlArtifactGenerationJobRunner {
    public static final String JOB_TYPE = "artifact.generate";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TitanGraphqlControlJobQueue queue;
    private final Supplier<TitanGraphqlArtifactGenerationService> generation;

    public TitanGraphqlArtifactGenerationJobRunner(
            TitanGraphqlControlJobQueue queue,
            TitanGraphqlArtifactGenerationService generation
    ) {
        this(queue, constantGeneration(generation));
    }

    public TitanGraphqlArtifactGenerationJobRunner(
            TitanGraphqlControlJobQueue queue,
            Supplier<TitanGraphqlArtifactGenerationService> generation
    ) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.generation = Objects.requireNonNull(generation, "generation");
    }

    private static Supplier<TitanGraphqlArtifactGenerationService> constantGeneration(
            TitanGraphqlArtifactGenerationService generation
    ) {
        Objects.requireNonNull(generation, "generation");
        return () -> generation;
    }

    public TitanGraphqlControlJobQueue.JobReference request(
            Connection callerTransaction,
            String requestKey,
            String draftId,
            String generationProfile,
            boolean enableIntrospection
    ) throws SQLException {
        return request(queue, callerTransaction, requestKey, draftId, generationProfile, enableIntrospection);
    }

    public static TitanGraphqlControlJobQueue.JobReference request(
            TitanGraphqlControlJobQueue queue,
            Connection callerTransaction,
            String requestKey,
            String draftId,
            String generationProfile,
            boolean enableIntrospection
    ) throws SQLException {
        Objects.requireNonNull(queue, "queue");
        if (draftId == null || draftId.isBlank()) {
            throw new IllegalArgumentException("artifact generation draft id is required");
        }
        String profile = generationProfile == null || generationProfile.isBlank()
                ? "development" : generationProfile;
        ObjectNode payload = JSON.createObjectNode();
        payload.put("draftId", draftId);
        payload.put("generationProfile", profile);
        payload.put("enableIntrospection", enableIntrospection);
        return queue.enqueue(callerTransaction, JOB_TYPE, requestKey, payload.toString());
    }

    public boolean runOne(Duration lease) throws SQLException {
        return runOne(lease, false);
    }

    public boolean runOneWithHeartbeat(Duration lease) throws SQLException {
        if (lease == null || lease.toMillis() < 1_000L) {
            throw new IllegalArgumentException("artifact worker heartbeat requires a lease of at least one second");
        }
        return runOne(lease, true);
    }

    private boolean runOne(Duration lease, boolean heartbeatEnabled) throws SQLException {
        var claimed = queue.claimNext(JOB_TYPE, lease);
        if (claimed.isEmpty()) {
            return false;
        }
        TitanGraphqlControlJobQueue.ClaimedJob job = claimed.orElseThrow();
        TitanGraphqlArtifactGenerationService generationService;
        TitanGraphqlArtifactGenerationService.Prepared prepared;
        TitanGraphqlControlJobLeaseHeartbeat heartbeat = heartbeatEnabled
                ? TitanGraphqlControlJobLeaseHeartbeat.start(queue, job, lease)
                : TitanGraphqlControlJobLeaseHeartbeat.disabled();
        try (heartbeat) {
            JsonNode payload = JSON.readTree(job.payloadJson());
            JsonNode draftId = payload.path("draftId");
            JsonNode profile = payload.path("generationProfile");
            JsonNode introspection = payload.path("enableIntrospection");
            if (!draftId.isTextual() || !profile.isTextual() || !introspection.isBoolean()) {
                throw new IllegalArgumentException("artifact generation job payload is invalid");
            }
            generationService = generation.get();
            prepared = generationService.prepare(draftId.asText(), profile.asText(), introspection.asBoolean());
        } catch (GraphqlException | IllegalArgumentException | JsonProcessingException failure) {
            if (!queue.fail(job, "INVALID_ARTIFACT_REQUEST")) {
                throw new SQLException("artifact generation job lease expired before failure recording", failure);
            }
            return true;
        } catch (RuntimeException failure) {
            if (!queue.retry(job, "ARTIFACT_WORKER_FAILURE")) {
                failure.addSuppressed(new SQLException(
                        "artifact generation job lease expired before retry recording"));
            }
            throw failure;
        }
        heartbeat.verify();
        String resultJson;
        try {
            resultJson = JSON.writeValueAsString(prepared.result().payload());
        } catch (JsonProcessingException failure) {
            if (!queue.retry(job, "ARTIFACT_RESULT_FAILURE")) {
                throw new SQLException("artifact generation job lease expired before retry recording", failure);
            }
            throw new SQLException("artifact generation job result could not be serialized", failure);
        }
        if (!queue.renew(job, lease)) {
            throw new SQLException("artifact generation job lease expired before publication");
        }
        boolean completed;
        try {
            if (generationService.supportsAtomicJobCompletion()) {
                completed = generationService.publishAndComplete(prepared, queue, job, resultJson);
            } else {
                generationService.publish(prepared);
                completed = queue.complete(job, resultJson);
            }
        } catch (RuntimeException failure) {
            if (!queue.retry(job, "ARTIFACT_WORKER_FAILURE")) {
                failure.addSuppressed(new SQLException(
                        "artifact generation job lease expired before retry recording"));
            }
            throw failure;
        }
        if (!completed) {
            throw new SQLException("artifact generation job lease expired before completion");
        }
        return true;
    }

}
