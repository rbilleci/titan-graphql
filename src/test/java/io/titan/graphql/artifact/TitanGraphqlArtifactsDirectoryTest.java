package io.titan.graphql.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Config plumbing for the real-artifact pipeline: the
 * {@code titan.graphql.artifacts.dir} property selects the {@code titanPackage} output
 * directory the management plane reads, and missing state is an explicit, descriptive
 * error (never a placeholder). Uses the checked-in schema-faithful fixture package under
 * {@code src/test/resources/titan-artifacts} so plain {@code test} stays Docker-free; the
 * real {@code titanPackage}/{@code titanVerifyInstall} output is exercised by
 * {@code TitanGraphqlRealArtifactPipelineIT}.
 */
final class TitanGraphqlArtifactsDirectoryTest {

    @TempDir
    Path tempDir;

    private String previousProperty;

    @BeforeEach
    void rememberProperty() {
        previousProperty = System.getProperty(TitanGraphqlArtifactsDirectory.SYSTEM_PROPERTY);
    }

    @AfterEach
    void restoreProperty() {
        if (previousProperty == null) {
            System.clearProperty(TitanGraphqlArtifactsDirectory.SYSTEM_PROPERTY);
        } else {
            System.setProperty(TitanGraphqlArtifactsDirectory.SYSTEM_PROPERTY, previousProperty);
        }
    }

    @Test
    void defaultsToTheTitanPackageOutputDirectory() {
        System.clearProperty(TitanGraphqlArtifactsDirectory.SYSTEM_PROPERTY);

        assertEquals(
                Path.of("build/generated/migrations/titan"),
                TitanGraphqlArtifactsDirectory.configuredDirectory());
    }

    @Test
    void readsGap005MetadataFromTheConfiguredPackageDirectory() {
        System.setProperty(
                TitanGraphqlArtifactsDirectory.SYSTEM_PROPERTY,
                Path.of("src/test/resources/titan-artifacts").toAbsolutePath().toString());

        TitanGraphqlGap005ArtifactMetadata metadata = TitanGraphqlArtifactsDirectory.readGap005Metadata();

        assertEquals("titan.generated-sql.fixture", metadata.artifactId());
        assertEquals("migration", metadata.packageMode());
        assertEquals("pending", metadata.verificationStatus());
        assertEquals("TITAN-GAP005-VERIFY-PENDING", metadata.verificationDiagnostics().getFirst().code());
        assertEquals("src/test/resources/titan-artifacts", metadata.artifactRoot());

        TitanGraphqlEntryPointRef entryPoint = metadata.entryPoints().getFirst();
        assertEquals("executeGraphqlRequestWithCompactContext", entryPoint.methodName());
        assertEquals("public.execute_graphql_request_with_compact_context",
                entryPoint.routines().getFirst().qualifiedName());
        assertEquals("(p_query TEXT)", entryPoint.routines().getFirst().signature());

        // The fixture manifest carries rollbackScripts[] (core B-5, titan 0933913): the ref reads
        // the manifest's authoritative statement count + raw-byte sha256 and integrity-verifies
        // them against the on-disk script.
        TitanGraphqlRollbackScriptRef rollback = metadata.rollbackScripts().getFirst();
        assertTrue(rollback.present());
        assertEquals("src/test/resources/titan-artifacts/titan-rollback.postgresql.sql", rollback.path());
        assertEquals(3, rollback.statementCount());
        assertEquals("de0f2d2a824a0f9292b7c031d896f2361e9142f9fcfa5d1f58072e5cb5da0a18", rollback.contentSha256());
        assertEquals(TitanGraphqlRollbackScriptRef.IntegrityStatus.VERIFIED, rollback.status());
        assertTrue(rollback.integrityVerified());
    }

    @Test
    void surfacesDriftWhenTheOnDiskRollbackScriptDivergesFromTheManifestHash() throws IOException {
        // The manifest still pins the original raw-byte sha256, but the on-disk script is edited:
        // a present file whose bytes no longer match the manifest authority must read as DRIFTED,
        // never a clean "present".
        Path packageDir = copyFixturePackage();
        Path script = packageDir.resolve("titan-rollback.postgresql.sql");
        Files.writeString(
                script,
                Files.readString(script, StandardCharsets.UTF_8) + "\nDROP TABLE IF EXISTS \"public\".\"tampered\";\n",
                StandardCharsets.UTF_8);

        TitanGraphqlGap005ArtifactMetadata metadata =
                TitanGraphqlGap005ArtifactMetadata.read(packageDir, "build/generated/migrations/titan");
        TitanGraphqlRollbackScriptRef rollback = metadata.rollbackScripts().getFirst();

        assertTrue(rollback.present(), "the tampered file still exists on disk");
        assertEquals(TitanGraphqlRollbackScriptRef.IntegrityStatus.DRIFTED, rollback.status());
        assertFalse(rollback.integrityVerified(), "a drifted script must not read as integrity-verified");
        assertTrue(rollback.drifted());
        // Manifest stays authoritative: the count/hash are the manifest's, not the tampered file's.
        assertEquals(3, rollback.statementCount());
        assertEquals("de0f2d2a824a0f9292b7c031d896f2361e9142f9fcfa5d1f58072e5cb5da0a18", rollback.contentSha256());
    }

    @Test
    void fallsBackToFilenameDiscoveryWhenTheManifestLacksRollbackScripts() throws IOException {
        // Older packages / fixtures whose manifest predates rollbackScripts[]: the script is
        // discovered by the titan-rollback.<dialect>.sql convention and summarized locally
        // (LEGACY_UNLINKED, recomputed), and nothing crashes on the missing field.
        Path packageDir = copyFixturePackage();
        Path manifest = packageDir.resolve("titan-artifact.json");
        String stripped = Files.readString(manifest, StandardCharsets.UTF_8)
                .replaceAll("(?s)\"rollbackScripts\"\\s*:\\s*\\[.*?\\],\\s*", "");
        Files.writeString(manifest, stripped, StandardCharsets.UTF_8);
        assertFalse(stripped.contains("rollbackScripts"), "manifest must no longer carry the field");

        TitanGraphqlGap005ArtifactMetadata metadata =
                TitanGraphqlGap005ArtifactMetadata.read(packageDir, "build/generated/migrations/titan");
        TitanGraphqlRollbackScriptRef rollback = metadata.rollbackScripts().getFirst();

        assertTrue(rollback.present());
        assertEquals(TitanGraphqlRollbackScriptRef.IntegrityStatus.LEGACY_UNLINKED, rollback.status());
        assertFalse(rollback.integrityVerified(), "an unlinked legacy script is not manifest-verified");
        // Recomputed from the real bytes (raw-byte hash matches the fixture's actual sha256).
        assertEquals(3, rollback.statementCount());
        assertEquals("de0f2d2a824a0f9292b7c031d896f2361e9142f9fcfa5d1f58072e5cb5da0a18", rollback.contentSha256());
    }

    private Path copyFixturePackage() throws IOException {
        Path source = Path.of("src/test/resources/titan-artifacts");
        Path copy = tempDir.resolve("package");
        Files.createDirectories(copy);
        for (String fileName : List.of(
                "titan-artifact.json",
                "titan-object-inventory.json",
                "titan-install-plan.json",
                "titan-install-verification.json",
                "titan-rollback.postgresql.sql")) {
            Files.copy(source.resolve(fileName), copy.resolve(fileName));
        }
        return copy;
    }

    @Test
    void reportsMissingArtifactsDirectoryDescriptively() {
        System.setProperty(
                TitanGraphqlArtifactsDirectory.SYSTEM_PROPERTY,
                tempDir.resolve("never-packaged").toString());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                TitanGraphqlArtifactsDirectory::readGap005Metadata);

        assertTrue(error.getMessage().contains("does not exist"));
        assertTrue(error.getMessage().contains("titanPackage"));
        assertTrue(error.getMessage().contains(TitanGraphqlArtifactsDirectory.SYSTEM_PROPERTY));
    }

    @Test
    void reportsIncompletePackageDirectoryWithTheMissingFileNames() {
        System.setProperty(TitanGraphqlArtifactsDirectory.SYSTEM_PROPERTY, tempDir.toString());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                TitanGraphqlArtifactsDirectory::readGap005Metadata);

        assertTrue(error.getMessage().contains("titan-artifact.json"));
        assertTrue(error.getMessage().contains("titan-object-inventory.json"));
        assertTrue(error.getMessage().contains("titan-install-plan.json"));
        assertTrue(error.getMessage().contains("titan-install-verification.json"));
        assertTrue(error.getMessage().contains("titanPackage"));
    }
}
