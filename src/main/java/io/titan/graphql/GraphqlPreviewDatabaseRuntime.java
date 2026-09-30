package io.titan.graphql;

import io.titan.graphql.database.DatabaseGraphqlWholeRequestRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;
import javax.sql.DataSource;

final class GraphqlPreviewDatabaseRuntime {

    record PreviewSelection(DatabaseGraphqlWholeRequestRuntime runtime, boolean expired) {
    }

    private final Path registry;
    private final Supplier<DataSource> dataSources;
    private final Clock clock;

    private GraphqlPreviewDatabaseRuntime(Path registry, Supplier<DataSource> dataSources, Clock clock) {
        this.registry = registry;
        this.dataSources = dataSources;
        this.clock = clock;
        load();
    }

    static GraphqlPreviewDatabaseRuntime fromRegistry(Path registry, Supplier<DataSource> dataSources) {
        return fromRegistry(registry, dataSources, Clock.systemUTC());
    }

    static GraphqlPreviewDatabaseRuntime fromRegistry(
            Path registry,
            Supplier<DataSource> dataSources,
            Clock clock
    ) {
        Objects.requireNonNull(registry, "preview database descriptor registry");
        Objects.requireNonNull(dataSources, "preview data source supplier");
        Objects.requireNonNull(clock, "preview clock");
        return new GraphqlPreviewDatabaseRuntime(registry.toAbsolutePath(), dataSources, clock);
    }

    PreviewSelection find(String previewBuildId) {
        PreviewSelection candidate = load().get(previewBuildId);
        if (candidate == null) {
            return null;
        }
        return candidate;
    }

    private Map<String, PreviewSelection> load() {
        Properties entries = new Properties() {
            @Override
            public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) {
                    throw new IllegalArgumentException("duplicate preview build ID in registry: " + key);
                }
                return super.put(key, value);
            }
        };
        try (java.io.Reader reader = Files.newBufferedReader(registry, StandardCharsets.UTF_8)) {
            entries.load(reader);
        } catch (IOException failure) {
            throw new IllegalStateException("preview database descriptor registry could not be read: " + registry,
                    failure);
        }
        Map<String, PreviewSelection> previews = new LinkedHashMap<>();
        for (String previewBuildId : entries.stringPropertyNames()) {
            if (!previewBuildId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                throw new IllegalArgumentException("preview build ID in descriptor registry is invalid: "
                        + previewBuildId);
            }
            String configuredPath = entries.getProperty(previewBuildId);
            if (configuredPath == null || configuredPath.isBlank()) {
                throw new IllegalArgumentException("preview database descriptor path is required: " + previewBuildId);
            }
            Path descriptor = Path.of(configuredPath.trim());
            if (!descriptor.isAbsolute()) {
                descriptor = registry.toAbsolutePath().getParent().resolve(descriptor).normalize();
            }
            Properties deployment = new Properties();
            try (java.io.Reader reader = Files.newBufferedReader(descriptor, StandardCharsets.UTF_8)) {
                deployment.load(reader);
            } catch (IOException failure) {
                throw new IllegalStateException("preview database descriptor could not be read: " + descriptor,
                        failure);
            }
            String boundBuildId = deployment.getProperty("preview-build-id", "").trim();
            if (!boundBuildId.equals(previewBuildId)) {
                throw new IllegalArgumentException("preview database descriptor build ID does not match registry: "
                        + previewBuildId);
            }
            String expiresAtText = deployment.getProperty("preview-expires-at", "").trim();
            if (expiresAtText.isEmpty()) {
                throw new IllegalArgumentException("preview database descriptor expiration is required: "
                        + previewBuildId);
            }
            Instant expiresAt;
            try {
                expiresAt = Instant.parse(expiresAtText);
            } catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("preview database descriptor expiration is invalid: "
                        + previewBuildId, invalid);
            }
            if (deployment.getProperty("operation-registry-id", "").isBlank()
                    || deployment.getProperty("preview-deployment-sha256", "").isBlank()) {
                throw new IllegalArgumentException("preview database descriptor requires a sealed operation registry: "
                        + previewBuildId);
            }
            previews.put(previewBuildId, new PreviewSelection(
                    GraphqlAdminDatabaseRuntime.fromDescriptor(descriptor, dataSources),
                    !clock.instant().isBefore(expiresAt)));
        }
        return Map.copyOf(previews);
    }
}
