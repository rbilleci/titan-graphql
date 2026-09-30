package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.controlplane.TitanGraphqlArtifactGenerationJobRunner;
import io.titan.graphql.controlplane.TitanGraphqlArtifactGenerationService;
import io.titan.graphql.controlplane.TitanGraphqlControlJobQueue;
import io.titan.graphql.management.TitanGraphqlInMemoryManagementStore;
import io.titan.graphql.management.TitanGraphqlModelDraft;
import io.titan.graphql.management.TitanGraphqlValidationReportRef;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import io.titan.runtime.jdbc.SingleConnectionDataSource;
import io.titan.runtime.testing.DatabaseTarget;
import io.titan.runtime.testing.TitanTestContext;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

final class TitanGraphqlControlJobQueueContract {
    private TitanGraphqlControlJobQueueContract() {
    }

    static void verify(
            TitanTestContext context,
            DatabaseTarget target,
            TitanGraphqlControlJobQueue.Dialect dialect
    ) throws Exception {
        Connection caller = context.connection(target);
        try (Connection workerConnection = context.openAdditionalConnection(target)) {
            Clock clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
            TitanGraphqlControlJobQueue queue = new TitanGraphqlControlJobQueue(
                    new SingleConnectionDataSource(workerConnection), dialect, clock);

            caller.setAutoCommit(false);
            TitanGraphqlControlJobQueue.JobReference rolledBack = queue.enqueue(
                    caller, "artifact.generate", "rolled-back", "{\"draftId\":\"draft-1\"}");
            assertTrue(rolledBack.created());
            assertTrue(queue.find(rolledBack.id()).isEmpty());
            caller.rollback();
            assertTrue(queue.find(rolledBack.id()).isEmpty());

            TitanGraphqlControlJobQueue.JobReference created = queue.enqueue(
                    caller, "artifact.generate", "build-1", "{\"draftId\":\"draft-1\"}");
            caller.commit();
            assertEquals(TitanGraphqlControlJobQueue.Status.PENDING, queue.find(created.id()).orElseThrow().status());

            TitanGraphqlControlJobQueue.JobReference duplicate = queue.enqueue(
                    caller, "artifact.generate", "build-1", "{\"draftId\":\"draft-1\"}");
            assertEquals(created.id(), duplicate.id());
            assertFalse(duplicate.created());
            assertThrows(IllegalArgumentException.class, () -> queue.enqueue(
                    caller, "artifact.generate", "build-1", "{\"draftId\":\"draft-2\"}"));
            caller.rollback();

            try (PreparedStatement lock = caller.prepareStatement(
                    "SELECT job_id FROM public.titan_graphql_control_jobs "
                            + "WHERE job_id = ? FOR UPDATE")) {
                lock.setString(1, created.id());
                try (ResultSet rows = lock.executeQuery()) {
                    assertTrue(rows.next());
                }
                assertTrue(queue.claimNext(Duration.ofMinutes(1)).isEmpty());
            } finally {
                caller.rollback();
            }

            TitanGraphqlControlJobQueue.ClaimedJob first = queue.claimNext(Duration.ofMinutes(1)).orElseThrow();
            assertEquals(created.id(), first.id());
            assertEquals(1, first.attempt());
            assertEquals(TitanGraphqlControlJobQueue.Status.RUNNING, queue.find(first.id()).orElseThrow().status());

            TitanGraphqlControlJobQueue renewing = new TitanGraphqlControlJobQueue(
                    new SingleConnectionDataSource(workerConnection), dialect,
                    Clock.offset(clock, Duration.ofSeconds(30)));
            assertTrue(renewing.renew(first, Duration.ofMinutes(2)));
            TitanGraphqlControlJobQueue beforeRenewedExpiry = new TitanGraphqlControlJobQueue(
                    new SingleConnectionDataSource(workerConnection), dialect,
                    Clock.offset(clock, Duration.ofMinutes(2)));
            assertTrue(beforeRenewedExpiry.claimNext(Duration.ofMinutes(1)).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> renewing.renew(first, Duration.ZERO));

            TitanGraphqlControlJobQueue afterLease = new TitanGraphqlControlJobQueue(
                    new SingleConnectionDataSource(workerConnection), dialect,
                    Clock.offset(clock, Duration.ofMinutes(3)));
            TitanGraphqlControlJobQueue.ClaimedJob second = afterLease.claimNext(Duration.ofMinutes(1)).orElseThrow();
            assertEquals(first.id(), second.id());
            assertEquals(2, second.attempt());
            assertFalse(renewing.renew(first, Duration.ofMinutes(1)));
            assertFalse(queue.complete(first, "{\"accepted\":true}"));
            assertTrue(afterLease.retry(second, "TEMPORARY_FAILURE"));
            TitanGraphqlControlJobQueue.JobState pending = queue.find(created.id()).orElseThrow();
            assertEquals(TitanGraphqlControlJobQueue.Status.PENDING, pending.status());
            assertEquals("TEMPORARY_FAILURE", pending.failureCode());

            TitanGraphqlControlJobQueue.ClaimedJob third = queue.claimNext(Duration.ofMinutes(1)).orElseThrow();
            assertEquals(3, third.attempt());
            assertTrue(queue.fail(third, "REJECTED"));
            assertEquals(TitanGraphqlControlJobQueue.Status.FAILED,
                    queue.find(created.id()).orElseThrow().status());
            assertTrue(queue.claimNext(Duration.ofMinutes(1)).isEmpty());

            TitanGraphqlControlJobQueue.JobReference successful = queue.enqueue(
                    caller, "artifact.generate", "build-2", "{\"draftId\":\"draft-2\"}");
            caller.commit();
            TitanGraphqlControlJobQueue.ClaimedJob claim = queue.claimNext(Duration.ofMinutes(1)).orElseThrow();
            assertEquals(successful.id(), claim.id());
            assertTrue(queue.complete(claim, "{\"artifactSetId\":\"artifact-2\"}"));
            TitanGraphqlControlJobQueue.JobState finished = queue.find(successful.id()).orElseThrow();
            assertEquals(TitanGraphqlControlJobQueue.Status.SUCCEEDED, finished.status());
            assertEquals("{\"artifactSetId\":\"artifact-2\"}", finished.resultJson());
            assertFalse(queue.fail(claim, "TOO_LATE"));
            assertTrue(queue.claimNext(Duration.ofMinutes(1)).isEmpty());
            caller.setAutoCommit(false);
            TitanGraphqlControlJobQueue.JobReference replay = queue.enqueue(
                    caller, "artifact.generate", "build-2", "{\"draftId\":\"draft-2\"}");
            assertEquals(successful.id(), replay.id());
            assertEquals(TitanGraphqlControlJobQueue.Status.SUCCEEDED, replay.status());
            caller.rollback();
            caller.setAutoCommit(true);

            String source;
            try (InputStream fixture = TitanGraphqlControlJobQueueContract.class
                    .getResourceAsStream("/graphql/management.titan.graphql.yaml")) {
                source = new String(java.util.Objects.requireNonNull(fixture).readAllBytes(), StandardCharsets.UTF_8);
            }
            TitanGraphqlModelDocument model = TitanGraphqlModelDocumentYaml.parse(source);
            TitanGraphqlInMemoryManagementStore store = new TitanGraphqlInMemoryManagementStore();
            String draftId = "draft-management-job";
            String validationId = "validation-management-job";
            store.saveDraft(new TitanGraphqlModelDraft(
                    draftId, "model-management-job", TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED,
                    TitanGraphqlModelDraft.SourceFormat.YAML, source,
                    TitanGraphqlModelDocumentJson.canonicalJson(model),
                    TitanGraphqlModelDocumentJson.semanticHash(model),
                    validationId, "", "", "operator", "", ""));
            store.saveValidationReport(new TitanGraphqlValidationReportRef(
                    validationId, draftId, TitanGraphqlValidationReportRef.ValidationReportStatus.PASS,
                    "validation passed", 0, 0, 0, false, ""));
            TitanGraphqlArtifactGenerationJobRunner runner = new TitanGraphqlArtifactGenerationJobRunner(
                    queue, new TitanGraphqlArtifactGenerationService(store));

            caller.setAutoCommit(false);
            TitanGraphqlControlJobQueue.JobReference generation = runner.request(
                    caller, "management-artifact-job", draftId, "development", true);
            caller.commit();
            assertTrue(runner.runOne(Duration.ofMinutes(1)));
            TitanGraphqlControlJobQueue.JobState generated = queue.find(generation.id()).orElseThrow();
            assertEquals(TitanGraphqlControlJobQueue.Status.SUCCEEDED, generated.status());
            assertTrue(generated.resultJson().contains("\"artifactSetId\":\"artifact-" + draftId + "\""));
            assertEquals("artifact-" + draftId, store.draft(draftId).artifactSetId());
            assertEquals("artifact-" + draftId, store.artifactSet("artifact-" + draftId).id());
            assertFalse(runner.runOne(Duration.ofMinutes(1)));

            TitanGraphqlControlJobQueue.JobReference invalid = runner.request(
                    caller, "missing-draft-job", "draft-missing", "development", false);
            caller.commit();
            assertTrue(runner.runOne(Duration.ofMinutes(1)));
            TitanGraphqlControlJobQueue.JobState failed = queue.find(invalid.id()).orElseThrow();
            assertEquals(TitanGraphqlControlJobQueue.Status.FAILED, failed.status());
            assertEquals("INVALID_ARTIFACT_REQUEST", failed.failureCode());

            String expiringDraftId = "draft-expiring-job";
            String expiringValidationId = "validation-expiring-job";
            store.saveDraft(new TitanGraphqlModelDraft(
                    expiringDraftId, "model-expiring-job", TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED,
                    TitanGraphqlModelDraft.SourceFormat.YAML, source,
                    TitanGraphqlModelDocumentJson.canonicalJson(model),
                    TitanGraphqlModelDocumentJson.semanticHash(model),
                    expiringValidationId, "", "", "operator", "", ""));
            store.saveValidationReport(new TitanGraphqlValidationReportRef(
                    expiringValidationId, expiringDraftId, TitanGraphqlValidationReportRef.ValidationReportStatus.PASS,
                    "validation passed", 0, 0, 0, false, ""));
            TitanGraphqlControlJobQueue.JobReference expiring = runner.request(
                    caller, "expiring-artifact-job", expiringDraftId, "development", true);
            caller.commit();
            AtomicInteger clockReads = new AtomicInteger();
            Clock expiringClock = new Clock() {
                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Instant instant() {
                    return clock.instant().plus(clockReads.getAndIncrement() == 0
                            ? Duration.ZERO : Duration.ofMinutes(2));
                }
            };
            TitanGraphqlControlJobQueue expiringQueue = new TitanGraphqlControlJobQueue(
                    new SingleConnectionDataSource(workerConnection), dialect, expiringClock);
            TitanGraphqlArtifactGenerationJobRunner expiringRunner = new TitanGraphqlArtifactGenerationJobRunner(
                    expiringQueue, new TitanGraphqlArtifactGenerationService(store));
            assertThrows(SQLException.class, () -> expiringRunner.runOne(Duration.ofMinutes(1)));
            assertEquals(TitanGraphqlControlJobQueue.Status.RUNNING,
                    queue.find(expiring.id()).orElseThrow().status());
            assertEquals("", store.draft(expiringDraftId).artifactSetId());

            String extendedDraftId = "draft-extended-job";
            String extendedValidationId = "validation-extended-job";
            store.saveDraft(new TitanGraphqlModelDraft(
                    extendedDraftId, "model-extended-job", TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED,
                    TitanGraphqlModelDraft.SourceFormat.YAML, source,
                    TitanGraphqlModelDocumentJson.canonicalJson(model),
                    TitanGraphqlModelDocumentJson.semanticHash(model),
                    extendedValidationId, "", "", "operator", "", ""));
            store.saveValidationReport(new TitanGraphqlValidationReportRef(
                    extendedValidationId, extendedDraftId, TitanGraphqlValidationReportRef.ValidationReportStatus.PASS,
                    "validation passed", 0, 0, 0, false, ""));
            TitanGraphqlControlJobQueue liveQueue = new TitanGraphqlControlJobQueue(
                    new SingleConnectionDataSource(workerConnection), dialect, Clock.systemUTC());
            TitanGraphqlArtifactGenerationJobRunner extendedRunner = new TitanGraphqlArtifactGenerationJobRunner(
                    liveQueue, () -> {
                        try {
                            Thread.sleep(2_200L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        return new TitanGraphqlArtifactGenerationService(store);
                    });
            TitanGraphqlControlJobQueue.JobReference extended = extendedRunner.request(
                    caller, "extended-artifact-job", extendedDraftId, "development", true);
            caller.commit();
            assertTrue(extendedRunner.runOneWithHeartbeat(Duration.ofSeconds(1)));
            TitanGraphqlControlJobQueue.JobState extendedState = liveQueue.find(extended.id()).orElseThrow();
            assertEquals(TitanGraphqlControlJobQueue.Status.SUCCEEDED, extendedState.status());
            assertEquals(1, extendedState.attempt());
            assertEquals("artifact-" + extendedDraftId, store.draft(extendedDraftId).artifactSetId());
        } finally {
            if (!caller.getAutoCommit()) {
                caller.rollback();
                caller.setAutoCommit(true);
            }
        }
    }
}
