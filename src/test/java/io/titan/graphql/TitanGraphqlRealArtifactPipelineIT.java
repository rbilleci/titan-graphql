package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.titan.graphql.artifact.TitanGraphqlArtifactsDirectory;
import io.titan.graphql.artifact.TitanGraphqlEntryPointRef;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.graphql.artifact.TitanGraphqlRollbackScriptRef;
import io.titan.graphql.artifact.TitanGraphqlSqlRoutineRef;
import io.titan.graphql.management.TitanGraphqlArtifactSetRef;
import io.titan.graphql.management.TitanGraphqlDeployment;
import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import io.titan.graphql.management.TitanGraphqlManagedModel;
import io.titan.graphql.management.TitanGraphqlManagedWorkspace;
import io.titan.management.ManagementRecords.DeploymentStatus;
import io.titan.management.ManagementTransactions.DeploymentActivationExecution;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W3 end-to-end proof over the REAL core artifacts — no fixtures on this path:
 *
 * <ol>
 *   <li>{@code titanPackage} emits the package metadata to
 *       {@code build/generated/migrations/titan} (the {@code integrationTest} task wires
 *       {@code titan.graphql.artifacts.dir} there and depends on the task);</li>
 *   <li>the GAP-005 adapter reads the real manifest/inventory/plan/verification, including
 *       inventory-joined entry points and the real {@code titan-rollback.postgresql.sql}
 *       summary;</li>
 *   <li>{@code titanVerifyInstall} (scratch container — hence {@code @Tag("docker")}) has
 *       rewritten the install verification to {@code passed}, so durable deployment
 *       activation succeeds against that evidence;</li>
 *   <li>negative leg: the same real package with a not-yet-verified ({@code planned})
 *       verification status is refused with core's real status in the error.</li>
 * </ol>
 */
@Tag("docker")
final class TitanGraphqlRealArtifactPipelineIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void adapterReadsRealTitanPackageOutputIncludingEntryPointsAndRollback() {
        TitanGraphqlGap005ArtifactMetadata metadata = TitanGraphqlArtifactsDirectory.readGap005Metadata();

        assertEquals("migration", metadata.packageMode());
        // Dual transpilation targets since W5.2 (manifest dialect order is alphabetical).
        assertEquals(List.of("mysql", "postgresql"), metadata.dialects());
        assertTrue(metadata.artifactId().startsWith("titan.generated-sql."));

        // titanVerifyInstall ran before this task (Gradle dependency) and rewrote the
        // verification JSON with the real scratch-database result.
        assertEquals("passed", metadata.verificationStatus());

        // Entry points come from the packaged manifest joined with the object inventory —
        // the runtime-reflection scan is gone.
        assertFalse(metadata.entryPoints().isEmpty());
        TitanGraphqlEntryPointRef executeGraphql = metadata.entryPoints().stream()
                .filter(entryPoint -> entryPoint.methodName().equals("executeGraphql"))
                .findFirst()
                .orElseThrow();
        assertEquals("io.titan.graphql.demo.blog.DemoBlogTitanGraphqlFunctions", executeGraphql.className());
        assertEquals(List.of("java.lang.String", "long", "java.lang.String"), executeGraphql.parameterTypes());
        TitanGraphqlSqlRoutineRef routine = executeGraphql.routines().stream()
                .filter(candidate -> candidate.dialect().equals("postgresql"))
                .findFirst()
                .orElseThrow();
        assertEquals("function", routine.objectKind());
        assertEquals("public.execute_graphql", routine.qualifiedName());
        assertTrue(routine.signature().contains("p_query"));
        // The MySQL target (W5.2) packages the same entry point.
        assertTrue(executeGraphql.routines().stream()
                        .anyMatch(candidate -> candidate.dialect().equals("mysql")
                                && candidate.qualifiedName().equals("public.execute_graphql")),
                "expected a mysql routine for executeGraphql in the packaged inventory");

        // The real titanPackage manifest now integrity-links each rollback script (core B-5,
        // titan 0933913: rollbackScripts[]). Per dialect the ref reads the manifest's authoritative
        // statement count + raw-byte sha256 and integrity-verifies them against the on-disk script.
        for (String dialect : List.of("postgresql", "mysql")) {
            TitanGraphqlRollbackScriptRef rollback = metadata.rollbackScripts().stream()
                    .filter(candidate -> candidate.dialect().equals(dialect))
                    .findFirst()
                    .orElseThrow();
            assertTrue(rollback.present());
            assertTrue(rollback.path().endsWith("titan-rollback." + dialect + ".sql"));
            assertTrue(rollback.statementCount() > 100,
                    "expected the real " + dialect + " rollback script to drop the full object "
                            + "inventory but counted " + rollback.statementCount() + " statements");
            assertFalse(rollback.contentSha256().isBlank());
            // The manifest links the real script and its on-disk bytes hash to the manifest's
            // sha256 — the ref reads the manifest value and verifies it, not a recompute.
            assertEquals(TitanGraphqlRollbackScriptRef.IntegrityStatus.VERIFIED, rollback.status(),
                    "expected the real " + dialect + " rollback script to integrity-verify against "
                            + "the manifest's rollbackScripts[] hash");
            assertTrue(rollback.integrityVerified());
        }
    }

    @Test
    void deploymentActivationSucceedsAgainstVerifyInstallPassedEvidence() {
        TitanGraphqlGap005ArtifactMetadata metadata = TitanGraphqlArtifactsDirectory.readGap005Metadata();
        TitanGraphqlDurableManagementStore store = newStore(tempDir.resolve("real-artifact-management.log"))
                .saveArtifactSet(artifactSetRef(metadata), metadata);

        DeploymentActivationExecution activation = store.activateDeployment(
                pendingDeployment(),
                "actor-platform-001",
                "platform",
                "request-deploy-real-001",
                "deploy:prod:artifact-real-001",
                Instant.parse("2026-06-12T00:03:00Z"),
                Instant.parse("2026-06-12T00:03:01Z"));

        assertTrue(activation.success(), () -> "activation against titanVerifyInstall-passed evidence failed: "
                + activation.outcomeRecord().errorMessage());
        assertEquals(DeploymentStatus.ACTIVE, store.titanDeployments().getFirst().status());
        assertEquals(TitanGraphqlDeployment.DeploymentStatus.ACTIVE, store.deployment("deployment-real-001").status());

        // Rollback surface of the now-active deployment traces to the real packaged script and
        // carries the integrity-verified manifest link across the durable store.
        List<TitanGraphqlRollbackScriptRef> rollbackScripts = store.deploymentRollbackScripts("deployment-real-001");
        assertTrue(rollbackScripts.getFirst().present());
        assertTrue(rollbackScripts.getFirst().statementCount() > 100);
        assertTrue(rollbackScripts.getFirst().integrityVerified());
        assertEquals("public.execute_graphql", store.deploymentEntryPoints("deployment-real-001").stream()
                .filter(entryPoint -> entryPoint.methodName().equals("executeGraphql"))
                .findFirst()
                .orElseThrow()
                .routines()
                .getFirst()
                .qualifiedName());
    }

    @Test
    void deploymentActivationIsRefusedWithTheRealStatusWhenVerificationIsOnlyPlanned() throws IOException {
        Path plannedPackage = copyRealPackageWithVerificationStatus("planned");
        TitanGraphqlGap005ArtifactMetadata metadata = TitanGraphqlGap005ArtifactMetadata.read(
                plannedPackage,
                "build/generated/migrations/titan");
        assertEquals("planned", metadata.verificationStatus());

        TitanGraphqlDurableManagementStore store = newStore(tempDir.resolve("planned-artifact-management.log"))
                .saveArtifactSet(artifactSetRef(metadata), metadata);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> store.activateDeployment(
                pendingDeployment(),
                "actor-platform-001",
                "platform",
                "request-deploy-planned-001",
                "deploy:prod:artifact-planned-001",
                Instant.parse("2026-06-12T00:04:00Z"),
                Instant.parse("2026-06-12T00:04:01Z")));

        assertTrue(error.getMessage().contains("titan-install-verification.json status is 'planned'"),
                () -> "refusal must carry the real verification status, was: " + error.getMessage());
        assertTrue(store.deployments().isEmpty());
        assertTrue(store.titanDeployments().isEmpty());
    }

    /**
     * Copies the real titanPackage output and resets only the top-level verification status —
     * artifact ids and hash links stay the genuine package values.
     */
    private Path copyRealPackageWithVerificationStatus(String status) throws IOException {
        Path realPackage = TitanGraphqlArtifactsDirectory.configuredDirectory();
        Path copy = tempDir.resolve("package-" + status);
        Files.createDirectories(copy);
        for (String fileName : List.of(
                "titan-artifact.json",
                "titan-object-inventory.json",
                "titan-install-plan.json",
                "titan-install-verification.json",
                "titan-rollback.postgresql.sql",
                "titan-rollback.mysql.sql")) {
            Files.copy(realPackage.resolve(fileName), copy.resolve(fileName));
        }
        Path verification = copy.resolve("titan-install-verification.json");
        ObjectNode root = (ObjectNode) MAPPER.readTree(Files.readString(verification, StandardCharsets.UTF_8));
        root.put("status", status);
        Files.writeString(verification, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
        return copy;
    }

    private static TitanGraphqlDeployment pendingDeployment() {
        return new TitanGraphqlDeployment(
                "deployment-real-001",
                "model-001",
                "draft-001",
                "artifact-real-001",
                "prod",
                TitanGraphqlDeployment.DeploymentStatus.PENDING,
                "operator",
                "2026-06-12T00:03:00Z",
                "",
                "health-001");
    }

    private static TitanGraphqlArtifactSetRef artifactSetRef(TitanGraphqlGap005ArtifactMetadata metadata) {
        return new TitanGraphqlArtifactSetRef(
                "artifact-real-001",
                "draft-001",
                metadata.sourceInputsHash(),
                "",
                "",
                "",
                "",
                "",
                metadata.manifestContentHash(),
                metadata.packageMode(),
                "2026-06-12T00:02:00Z");
    }

    private static TitanGraphqlDurableManagementStore newStore(Path logPath) {
        return new TitanGraphqlDurableManagementStore(logPath)
                .saveWorkspace(new TitanGraphqlManagedWorkspace(
                        "workspace-001",
                        "platform",
                        "",
                        "prod",
                        "2026-06-12T00:00:00Z",
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
                        "2026-06-12T00:00:00Z",
                        ""));
    }
}
