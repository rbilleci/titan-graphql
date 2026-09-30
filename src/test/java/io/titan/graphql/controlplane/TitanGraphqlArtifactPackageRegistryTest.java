package io.titan.graphql.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TitanGraphqlArtifactPackageRegistryTest {
    @Test
    void selectsOnlyThePackageBoundToTheDraft(@TempDir Path directory) throws Exception {
        Path first = Files.createDirectory(directory.resolve("first-package"));
        Files.createDirectory(directory.resolve("second-package"));
        Path registryFile = directory.resolve("artifact-packages.properties");
        Files.writeString(registryFile,
                "first-draft=first-package\nsecond-draft=second-package\n");
        TitanGraphqlArtifactPackageRegistry registry = new TitanGraphqlArtifactPackageRegistry(registryFile);

        assertEquals(first, registry.packageDirectory("first-draft"));
        assertThrows(IllegalStateException.class, () -> registry.packageDirectory("unknown-draft"));
        assertThrows(IllegalArgumentException.class, () -> registry.packageDirectory(""));

        Files.writeString(registryFile, "first-draft=second-package\n");
        assertEquals(directory.resolve("second-package"), registry.packageDirectory("first-draft"));
    }

    @Test
    void refusesMissingDuplicateAndAbsentPackageEntries(@TempDir Path directory) throws Exception {
        Path registryFile = directory.resolve("artifact-packages.properties");
        assertThrows(IllegalStateException.class,
                () -> new TitanGraphqlArtifactPackageRegistry(registryFile));

        Files.writeString(registryFile, "draft=missing-package\n");
        TitanGraphqlArtifactPackageRegistry registry = new TitanGraphqlArtifactPackageRegistry(registryFile);
        assertThrows(IllegalStateException.class, () -> registry.packageDirectory("draft"));

        Files.writeString(registryFile, "draft=first-package\ndraft=second-package\n");
        assertThrows(IllegalStateException.class, () -> registry.packageDirectory("draft"));
    }
}
