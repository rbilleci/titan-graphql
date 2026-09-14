package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.graphql.management.TitanGraphqlArtifactSetRef;
import io.titan.graphql.management.TitanGraphqlDeployment;
import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import io.titan.graphql.management.TitanGraphqlManagedModel;
import io.titan.graphql.management.TitanGraphqlManagedWorkspace;
import io.titan.graphql.management.TitanGraphqlModelDraft;
import io.titan.graphql.management.TitanGraphqlPreviewBuild;
import io.titan.graphql.management.TitanGraphqlPreviewBuildManifest;
import io.titan.management.ManagementTransactions.DeploymentActivationExecution;
import io.titan.management.ManagementTransactions.TransactionalCommandExecution;
import io.titan.management.ManagementRecords.DeploymentStatus;
import io.titan.management.ManagementRecords.DraftStatus;
import io.titan.management.ManagementRecords.VerificationStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TitanGraphqlDurableManagementStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsCoreDraftReadsThroughTitanGap006Store() {
        Path logPath = tempDir.resolve("management.log");
        String semanticHash = sha256("demo draft");
        TitanGraphqlDurableManagementStore store = newStore(logPath)
                .saveDraft(new TitanGraphqlModelDraft(
                        "draft-001",
                        "model-001",
                        TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED,
                        TitanGraphqlModelDraft.SourceFormat.YAML,
                        "source",
                        "{}",
                        semanticHash,
                        "validation-001",
                        "",
                        "",
                        "operator",
                        "2026-06-09T00:00:00Z",
                        "2026-06-09T00:01:00Z"));

        assertEquals(DraftStatus.VALIDATED, store.titanDrafts().getFirst().status());
        assertEquals("sha256:" + semanticHash, store.titanDrafts().getFirst().documentHash());

        TitanGraphqlDurableManagementStore reloaded = newStore(logPath);
        assertEquals(TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED, reloaded.draft("draft-001").status());
        assertEquals(semanticHash, reloaded.draft("draft-001").semanticHash());
        assertEquals(List.of("draft-001"), reloaded.drafts().stream()
                .map(TitanGraphqlModelDraft::id)
                .toList());
    }

    @Test
    void importsDraftsThroughTitanTransactionAuditAndIdempotency() {
        Path logPath = tempDir.resolve("transactional-import.log");
        String semanticHash = sha256("transactional draft");
        TitanGraphqlDurableManagementStore store = newStore(logPath);
        TitanGraphqlModelDraft draft = new TitanGraphqlModelDraft(
                "draft-transactional",
                "model-001",
                TitanGraphqlModelDraft.ModelDraftStatus.IMPORTED,
                TitanGraphqlModelDraft.SourceFormat.YAML,
                "source",
                "{}",
                semanticHash,
                "validation-transactional",
                "",
                "",
                "operator",
                "2026-06-09T00:05:00Z",
                "2026-06-09T00:05:00Z");

        TransactionalCommandExecution first = store.importModelDocument(
                importInvocation("request-import", "idem-import"),
                draft,
                java.time.Instant.parse("2026-06-09T00:05:00Z"),
                java.time.Instant.parse("2026-06-09T00:05:01Z"));
        TransactionalCommandExecution retry = store.importModelDocument(
                importInvocation("request-import", "idem-import"),
                draft,
                java.time.Instant.parse("2026-06-09T00:05:02Z"),
                java.time.Instant.parse("2026-06-09T00:05:03Z"));

        assertTrue(first.success(), first.validation().errors().toString());
        assertTrue(retry.replayed());
        assertEquals(1, store.idempotencyRecords().size());
        assertEquals(4, store.auditRecords().size());
        assertEquals(List.of("draft-transactional"), store.titanDrafts().stream()
                .map(io.titan.management.ManagementRecords.Draft::id)
                .toList());
    }

    @Test
    void storesGap005ArtifactEvidenceInTitanArtifactRef() {
        Path logPath = tempDir.resolve("artifact.log");
        TitanGraphqlGap005ArtifactMetadata metadata = gap005Metadata("passed");
        TitanGraphqlDurableManagementStore store = newStore(logPath)
                .saveArtifactSet(artifactSetRef(), metadata);

        assertEquals("artifact-001", store.titanArtifactRefs().getFirst().id());
        assertEquals(VerificationStatus.PASSED, store.titanArtifactRefs().getFirst().verificationStatus());
        assertEquals("build/titan/titan-artifact.json", store.titanArtifactRefs().getFirst().manifestPath());
        assertEquals("sha256:" + metadata.manifestContentHash(), store.titanArtifactRefs().getFirst().manifestContentHash());
        assertEquals("postgresql", store.titanArtifactRefs().getFirst().dialect());

        // The raw package evidence (verification status text, entry points, rollback summary)
        // is recorded alongside the titan artifact ref.
        assertEquals("passed", store.artifactEvidence("artifact-001").verificationStatus());
        assertEquals("execute_graphql", store.artifactEvidence("artifact-001")
                .entryPoints().getFirst().routines().getFirst().routineName());
        assertTrue(store.artifactEvidence("artifact-001").rollbackScripts().getFirst().present());
    }

    @Test
    void exposesRollbackScriptAndEntryPointSurfaceForDeployments() {
        Path logPath = tempDir.resolve("rollback-surface.log");
        TitanGraphqlDurableManagementStore store = newStore(logPath)
                .saveArtifactSet(artifactSetRef(), gap005Metadata("passed"))
                .saveDeployment(new TitanGraphqlDeployment(
                        "deployment-001",
                        "model-001",
                        "draft-001",
                        "artifact-001",
                        "prod",
                        TitanGraphqlDeployment.DeploymentStatus.PENDING,
                        "operator",
                        "2026-06-09T00:03:00Z",
                        "",
                        "health-001"));

        List<io.titan.graphql.artifact.TitanGraphqlRollbackScriptRef> rollbackScripts =
                store.deploymentRollbackScripts("deployment-001");
        assertEquals(1, rollbackScripts.size());
        assertEquals("postgresql", rollbackScripts.getFirst().dialect());
        assertTrue(rollbackScripts.getFirst().present());
        assertEquals("build/titan/titan-rollback.postgresql.sql", rollbackScripts.getFirst().path());
        assertEquals(2, rollbackScripts.getFirst().statementCount());

        List<io.titan.graphql.artifact.TitanGraphqlEntryPointRef> entryPoints =
                store.deploymentEntryPoints("deployment-001");
        assertEquals("public.execute_graphql", entryPoints.getFirst().routines().getFirst().qualifiedName());
        assertEquals("(p_query TEXT, p_actor_id BIGINT, p_actor_role TEXT)",
                entryPoints.getFirst().routines().getFirst().signature());

        // The evidence (and with it the rollback surface, including its integrity status) survives
        // a durable reload through the JSONL journal.
        TitanGraphqlDurableManagementStore reloaded = newStore(logPath);
        assertEquals(2, reloaded.deploymentRollbackScripts("deployment-001").getFirst().statementCount());
        assertEquals(io.titan.graphql.artifact.TitanGraphqlRollbackScriptRef.IntegrityStatus.LEGACY_UNLINKED,
                reloaded.deploymentRollbackScripts("deployment-001").getFirst().status());
        assertEquals("io.titan.graphql.demo.blog.DemoBlogTitanGraphqlFunctions",
                reloaded.deploymentEntryPoints("deployment-001").getFirst().className());
    }

    @Test
    void activatesDeploymentsThroughTitanVerificationConsistency() {
        Path logPath = tempDir.resolve("deployment.log");
        TitanGraphqlDurableManagementStore store = newStore(logPath)
                .saveArtifactSet(artifactSetRef(), gap005Metadata("passed"));
        DeploymentActivationExecution activation = store.activateDeployment(new TitanGraphqlDeployment(
                        "deployment-001",
                        "model-001",
                        "draft-001",
                        "artifact-001",
                        "prod",
                        TitanGraphqlDeployment.DeploymentStatus.PENDING,
                        "operator",
                        "2026-06-09T00:03:00Z",
                        "",
                        "health-001"),
                "actor-platform-001",
                "platform",
                "request-deploy-001",
                "deploy:prod:artifact-001",
                java.time.Instant.parse("2026-06-09T00:03:00Z"),
                java.time.Instant.parse("2026-06-09T00:03:01Z"));

        assertTrue(activation.success());
        assertEquals(DeploymentStatus.ACTIVE, store.titanDeployments().getFirst().status());

        TitanGraphqlDurableManagementStore reloaded = newStore(logPath);
        assertEquals(List.of("deployment-001"), reloaded.titanDeployments().stream()
                .map(io.titan.management.ManagementRecords.Deployment::id)
                .toList());
        assertEquals(TitanGraphqlDeployment.DeploymentStatus.ACTIVE, reloaded.deployment("deployment-001").status());
    }

    @Test
    void rejectsActiveDeploymentSeedWithoutTitanActivation() {
        TitanGraphqlDurableManagementStore store = newStore(tempDir.resolve("active-seed.log"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> store.saveDeployment(
                new TitanGraphqlDeployment(
                        "deployment-001",
                        "model-001",
                        "draft-001",
                        "artifact-001",
                        "prod",
                        TitanGraphqlDeployment.DeploymentStatus.ACTIVE,
                        "operator",
                        "2026-06-09T00:03:00Z",
                        "",
                        "health-001")));

        assertTrue(error.getMessage().contains("GAP-006 activation"));
    }

    @Test
    void rejectsDeploymentActivationWithRealVerificationStatusWhenInstallVerificationFails() {
        TitanGraphqlDurableManagementStore store = newStore(tempDir.resolve("deployment-failed-verification.log"))
                .saveArtifactSet(artifactSetRef(), gap005Metadata("failed"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> store.activateDeployment(
                new TitanGraphqlDeployment(
                        "deployment-001",
                        "model-001",
                        "draft-001",
                        "artifact-001",
                        "prod",
                        TitanGraphqlDeployment.DeploymentStatus.PENDING,
                        "operator",
                        "2026-06-09T00:03:00Z",
                        "",
                        "health-001"),
                "actor-platform-001",
                "platform",
                "request-deploy-001",
                "deploy:prod:artifact-001",
                java.time.Instant.parse("2026-06-09T00:03:00Z"),
                java.time.Instant.parse("2026-06-09T00:03:01Z")));

        // The refusal carries the REAL status and diagnostic from titan-install-verification.json.
        assertTrue(error.getMessage().contains("titan-install-verification.json status is 'failed'"));
        assertTrue(error.getMessage().contains("TITAN-GAP005-VERIFY-FAILED"));
        assertTrue(store.deployments().isEmpty());
        assertTrue(store.titanDeployments().isEmpty());
    }

    @Test
    void rejectsDeploymentActivationWithoutTitanArtifactEvidence() {
        TitanGraphqlDurableManagementStore store = newStore(tempDir.resolve("deployment-missing-artifact.log"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> store.activateDeployment(
                new TitanGraphqlDeployment(
                        "deployment-001",
                        "model-001",
                        "draft-001",
                        "artifact-001",
                        "prod",
                        TitanGraphqlDeployment.DeploymentStatus.PENDING,
                        "operator",
                        "2026-06-09T00:03:00Z",
                        "",
                        "health-001"),
                "actor-platform-001",
                "platform",
                "request-deploy-001",
                "deploy:prod:artifact-001",
                java.time.Instant.parse("2026-06-09T00:03:00Z"),
                java.time.Instant.parse("2026-06-09T00:03:01Z")));

        assertTrue(error.getMessage().contains("GAP-005 artifact verification"));
    }

    @Test
    void savesVerifiedPreviewBuildManifestFromTitanArtifactEvidence() {
        TitanGraphqlDurableManagementStore store = newStore(tempDir.resolve("preview.log"))
                .saveArtifactSet(artifactSetRef(), gap005Metadata("passed"));

        TitanGraphqlPreviewBuildManifest manifest = store.saveVerifiedPreviewBuild(new TitanGraphqlPreviewBuild(
                "preview-001",
                "model-001",
                "draft-001",
                "artifact-001",
                "prod",
                TitanGraphqlPreviewBuild.PreviewBuildStatus.READY,
                "/preview/demo-blog/graphql",
                "/preview/demo-blog/console",
                sha256("sdl"),
                "build/titan/titan-artifact.json",
                sha256("manifest-content"),
                "registry-001",
                "2026-06-10T00:03:00Z",
                "operator",
                "2026-06-09T00:03:00Z"));

        assertEquals("build/titan/titan-artifact.json", manifest.artifactManifestPath());
        assertEquals(sha256("manifest-content"), manifest.artifactManifestHash());
        assertEquals("preview-001", store.previewBuild("preview-001").id());
    }

    @Test
    void rejectsPreviewBuildWhenSchemaHashIsStale() {
        TitanGraphqlDurableManagementStore store = newStore(tempDir.resolve("preview-stale.log"))
                .saveArtifactSet(artifactSetRef(), gap005Metadata("passed"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> store.saveVerifiedPreviewBuild(
                new TitanGraphqlPreviewBuild(
                        "preview-001",
                        "model-001",
                        "draft-001",
                        "artifact-001",
                        "prod",
                        TitanGraphqlPreviewBuild.PreviewBuildStatus.READY,
                        "/preview/demo-blog/graphql",
                        "/preview/demo-blog/console",
                        sha256("stale sdl"),
                        "build/titan/titan-artifact.json",
                        sha256("manifest-content"),
                        "registry-001",
                        "2026-06-10T00:03:00Z",
                        "operator",
                        "2026-06-09T00:03:00Z")));

        assertTrue(error.getMessage().contains("schema hash"));
    }

    @Test
    void rejectsPreviewBuildWhenManifestHashDiffersFromTitanEvidence() {
        TitanGraphqlDurableManagementStore store = newStore(tempDir.resolve("preview-manifest-mismatch.log"))
                .saveArtifactSet(artifactSetRef(), gap005Metadata("passed"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> store.saveVerifiedPreviewBuild(
                new TitanGraphqlPreviewBuild(
                        "preview-001",
                        "model-001",
                        "draft-001",
                        "artifact-001",
                        "prod",
                        TitanGraphqlPreviewBuild.PreviewBuildStatus.READY,
                        "/preview/demo-blog/graphql",
                        "/preview/demo-blog/console",
                        sha256("sdl"),
                        "build/titan/titan-artifact.json",
                        sha256("stale manifest"),
                        "registry-001",
                        "2026-06-10T00:03:00Z",
                        "operator",
                        "2026-06-09T00:03:00Z")));

        assertTrue(error.getMessage().contains("artifact manifest hash"));
    }

    @Test
    void keepsGraphqlOnlyRecordsOutsideTitanStore() {
        TitanGraphqlDurableManagementStore store = newStore(tempDir.resolve("boundary.log"));

        store.saveObservedOperation(io.titan.graphql.management.TitanGraphqlObservedOperation.observed(
                "model-001",
                "prod",
                "viewer",
                "portal",
                "operation-sha",
                "ArticleById",
                "query ArticleById { article(id: 1) { id } }",
                2,
                3,
                List.of("Article.id"),
                "2026-06-09T00:04:00Z"));

        assertEquals(1, store.observedOperations().size());
        assertTrue(store.titanDrafts().isEmpty());
        assertTrue(store.titanArtifactRefs().isEmpty());
        assertTrue(store.titanDeployments().isEmpty());
    }

    private TitanGraphqlDurableManagementStore newStore(Path logPath) {
        return new TitanGraphqlDurableManagementStore(logPath)
                .saveWorkspace(new TitanGraphqlManagedWorkspace(
                        "workspace-001",
                        "platform",
                        "",
                        "prod",
                        "2026-06-09T00:00:00Z",
                        ""))
                .saveModel(new TitanGraphqlManagedModel(
                        "model-001",
                        "workspace-001",
                        "demo-blog",
                        "Demo Blog",
                        "",
                        "draft-001",
                        "",
                        "",
                        "",
                        "2026-06-09T00:00:00Z",
                        ""));
    }

    private static io.titan.management.ManagementCommands.CommandInvocation importInvocation(
            String requestId,
            String idempotencyKey
    ) {
        return TitanGraphqlGap006CommandContext.importModelDocument(
                Map.of(
                        "workspaceId", "workspace-001",
                        "yaml", "apiVersion: titan.graphql/v1alpha1\nkind: ProjectionModel\n"),
                new GraphqlRequestContext(
                        0L,
                        "operator",
                        "actor-operator",
                        "",
                        requestId,
                        idempotencyKey,
                        List.of("management"),
                        List.of(),
                        true,
                        false,
                        false,
                        0L));
    }

    private static TitanGraphqlArtifactSetRef artifactSetRef() {
        return new TitanGraphqlArtifactSetRef(
                "artifact-001",
                "draft-001",
                sha256("semantic"),
                sha256("validation"),
                "",
                sha256("sdl"),
                "",
                sha256("conformance"),
                sha256("generated sql"),
                "migration",
                "2026-06-09T00:02:00Z");
    }

    private static TitanGraphqlGap005ArtifactMetadata gap005Metadata(String status) {
        String manifestHash = sha256("manifest-content");
        String inventoryHash = sha256("inventory-content");
        String planHash = sha256("plan-content");
        String sourceInputsHash = sha256("source-inputs");
        String manifest = """
                {
                  "schemaVersion": "titan.artifact.v1",
                  "artifactId": "artifact-001",
                  "packageMode": "migration",
                  "titanVersion": "0.1.0",
                  "dialects": ["postgresql"],
                  "entryPoints": [
                    {
                      "id": "io.titan.graphql.demo.blog.DemoBlogTitanGraphqlFunctions.executeGraphql(java.lang.String,long,java.lang.String)",
                      "java": {
                        "className": "io.titan.graphql.demo.blog.DemoBlogTitanGraphqlFunctions",
                        "methodName": "executeGraphql",
                        "parameterTypes": ["java.lang.String", "long", "java.lang.String"]
                      },
                      "sql": [
                        {
                          "dialect": "postgresql",
                          "objectId": "postgresql.public.execute_graphql.function",
                          "routineName": "execute_graphql",
                          "routineKind": "function",
                          "returnType": "TEXT"
                        }
                      ],
                      "securityMode": "invoker"
                    }
                  ],
                  "hashes": {
                    "manifestContentSha256": "%s",
                    "sourceInputsSha256": "%s"
                  }
                }
                """.formatted(manifestHash, sourceInputsHash);
        String inventory = """
                {
                  "schemaVersion": "titan.object-inventory.v1",
                  "artifactId": "artifact-001",
                  "objects": [
                    {
                      "id": "postgresql.public.execute_graphql.function",
                      "dialect": "postgresql",
                      "kind": "function",
                      "schema": "public",
                      "name": "execute_graphql",
                      "signature": "(p_query TEXT, p_actor_id BIGINT, p_actor_role TEXT)"
                    }
                  ],
                  "hashes": {
                    "inventoryContentSha256": "%s"
                  }
                }
                """.formatted(inventoryHash);
        String installPlan = """
                {
                  "schemaVersion": "titan.install-plan.v1",
                  "artifactId": "artifact-001",
                  "manifestContentSha256": "%s",
                  "inventoryContentSha256": "%s",
                  "hashes": {
                    "planContentSha256": "%s"
                  }
                }
                """.formatted(manifestHash, inventoryHash, planHash);
        String verification = """
                {
                  "schemaVersion": "titan.install-verification.v1",
                  "artifactId": "artifact-001",
                  "manifestContentSha256": "%s",
                  "installPlanContentSha256": "%s",
                  "status": "%s",
                  "diagnostics": [
                    {
                      "dialect": "all",
                      "code": "TITAN-GAP005-VERIFY-%s",
                      "message": "install verification reported status %s"
                    }
                  ]
                }
                """.formatted(manifestHash, planHash, status, status.toUpperCase(java.util.Locale.ROOT), status);
        return TitanGraphqlGap005ArtifactMetadata.fromContents(
                "build/titan",
                manifest,
                inventory,
                installPlan,
                verification,
                Map.of("postgresql", """
                        -- titan-rollback for artifact-001 (postgresql)
                        DROP FUNCTION IF EXISTS "public"."execute_graphql"(TEXT, BIGINT, TEXT);
                        DROP TABLE IF EXISTS "titan_runtime"."telemetry";
                        """));
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
