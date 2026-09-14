package io.titan.graphql.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TitanGraphqlPackageBindingTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsDeterministicallyAndVerifiesBothAuthorities() throws IOException {
        TitanGraphqlModelDocument document = demoDocument();
        TitanGraphqlGap005ArtifactMetadata metadata = fixtureMetadata();
        TitanGraphqlPackageBinding binding = TitanGraphqlPackageBinding.create(document, metadata);

        binding.write(temporaryDirectory);
        String firstBytes = Files.readString(temporaryDirectory.resolve(TitanGraphqlPackageBinding.FILE_NAME));
        binding.write(temporaryDirectory);
        String secondBytes = Files.readString(temporaryDirectory.resolve(TitanGraphqlPackageBinding.FILE_NAME));

        assertEquals(firstBytes, secondBytes);
        assertEquals(binding, TitanGraphqlPackageBinding.read(temporaryDirectory));
        assertTrue(binding.deploymentFingerprint().matches("[0-9a-f]{64}"));
        binding.verify(document, metadata);
    }

    @Test
    void rejectsAChangedReviewedModelEvenWhenNameAndVersionAreUnchanged() {
        TitanGraphqlModelDocument document = demoDocument();
        TitanGraphqlGap005ArtifactMetadata metadata = fixtureMetadata();
        TitanGraphqlPackageBinding binding = TitanGraphqlPackageBinding.create(document, metadata);
        String changedYaml = readDemoYaml().replace(
                "description: Bounded demo blog model used to prove Titan GraphQL.",
                "description: Semantically changed reviewed model.");
        TitanGraphqlModelDocument changed = TitanGraphqlModelDocumentYaml.parse(changedYaml);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> binding.verify(changed, metadata));

        assertTrue(failure.getMessage().contains("model semantic hash mismatch"), failure.getMessage());
        assertNotEquals(binding.modelSemanticSha256(),
                io.titan.graphql.model.TitanGraphqlModelDocumentJson.semanticHash(changed));
    }

    @Test
    void rejectsAChangedCompiledPackage() {
        TitanGraphqlModelDocument document = demoDocument();
        TitanGraphqlGap005ArtifactMetadata metadata = fixtureMetadata();
        TitanGraphqlPackageBinding binding = TitanGraphqlPackageBinding.create(document, metadata);
        TitanGraphqlGap005ArtifactMetadata otherPackage = new TitanGraphqlGap005ArtifactMetadata(
                metadata.artifactRoot(),
                "titan.generated-sql.changed",
                metadata.packageMode(),
                metadata.titanVersion(),
                metadata.dialects(),
                metadata.manifestContentHash(),
                metadata.sourceInputsHash(),
                metadata.inventoryContentHash(),
                metadata.installPlanContentHash(),
                metadata.verificationStatus(),
                metadata.verificationDiagnostics(),
                metadata.entryPoints(),
                metadata.rollbackScripts(),
                metadata.artifacts());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> binding.verify(document, otherPackage));

        assertTrue(failure.getMessage().contains("Titan artifact id mismatch"), failure.getMessage());
    }

    @Test
    void missingBindingFailsWithThePipelineRemedy() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> TitanGraphqlPackageBinding.read(temporaryDirectory));

        assertTrue(failure.getMessage().contains("titanGraphqlBindPackage"), failure.getMessage());
    }

    private static TitanGraphqlModelDocument demoDocument() {
        return TitanGraphqlModelDocumentYaml.parse(readDemoYaml());
    }

    private static String readDemoYaml() {
        try {
            return Files.readString(Path.of("src/test/resources/graphql/demo-blog.titan.graphql.yaml"));
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static TitanGraphqlGap005ArtifactMetadata fixtureMetadata() {
        return TitanGraphqlGap005ArtifactMetadata.read(Path.of("src/test/resources/titan-artifacts"));
    }
}
