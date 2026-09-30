package io.titan.graphql.controlplane;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

public final class TitanGraphqlArtifactPackageRegistry {
    public static final String ENVIRONMENT_VARIABLE = "TITAN_GRAPHQL_ARTIFACT_PACKAGE_REGISTRY";

    private final Path registry;

    public TitanGraphqlArtifactPackageRegistry(Path registry) {
        this.registry = Objects.requireNonNull(registry, "artifact package registry")
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(this.registry)) {
            throw new IllegalStateException("artifact package registry does not exist: " + this.registry);
        }
    }

    public Path packageDirectory(String draftId) {
        if (draftId == null || draftId.isBlank()) {
            throw new IllegalArgumentException("artifact package registry requires a draft ID");
        }
        Properties entries = new Properties() {
            @Override
            public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) {
                    throw new IllegalStateException("duplicate draft ID in artifact package registry: " + key);
                }
                return super.put(key, value);
            }
        };
        try (Reader reader = Files.newBufferedReader(registry, StandardCharsets.UTF_8)) {
            entries.load(reader);
        } catch (IOException failure) {
            throw new IllegalStateException("artifact package registry could not be read: " + registry, failure);
        }
        String configured = entries.getProperty(draftId);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("artifact package registry has no package for draft: " + draftId);
        }
        Path configuredPath = Path.of(configured);
        Path directory = (configuredPath.isAbsolute()
                ? configuredPath : registry.getParent().resolve(configuredPath)).normalize();
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("artifact package directory does not exist for draft: " + draftId);
        }
        return directory;
    }
}
