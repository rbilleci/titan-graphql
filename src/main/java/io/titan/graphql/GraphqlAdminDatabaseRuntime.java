package io.titan.graphql;

import io.titan.graphql.database.DatabaseGraphqlWholeRequestRuntime;
import io.titan.graphql.frontend.DatabasePreviewDeploymentAttestation;
import io.titan.graphql.frontend.DatabaseWholeRequestClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;
import javax.sql.DataSource;

final class GraphqlAdminDatabaseRuntime {

    private GraphqlAdminDatabaseRuntime() {
    }

    static DatabaseGraphqlWholeRequestRuntime fromDescriptor(
            Path descriptor,
            Supplier<DataSource> dataSources
    ) {
        Objects.requireNonNull(descriptor, "admin database descriptor");
        Objects.requireNonNull(dataSources, "admin data source supplier");
        Properties properties = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(descriptor, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException failure) {
            throw new IllegalStateException("admin database descriptor could not be read: " + descriptor, failure);
        }
        String version = required(properties, "schema-version");
        if (!version.equals("titan.graphql.database-frontend-deployment.v1")) {
            throw new IllegalArgumentException("unsupported admin database descriptor schema " + version);
        }
        DatabaseGraphqlWholeRequestRuntime.Dialect dialect = switch (required(properties, "database-dialect")) {
            case "postgresql" -> DatabaseGraphqlWholeRequestRuntime.Dialect.POSTGRESQL;
            case "mysql" -> DatabaseGraphqlWholeRequestRuntime.Dialect.MYSQL;
            default -> throw new IllegalArgumentException("admin database descriptor dialect is invalid");
        };
        String packageIdentity = required(properties, "package-identity-sha256");
        String registryId = properties.getProperty("operation-registry-id", "");
        DatabaseGraphqlWholeRequestRuntime.EntryPoint entryPoint =
                new DatabaseGraphqlWholeRequestRuntime.EntryPoint(
                        required(properties, "entry-point-schema"),
                        required(properties, "entry-point-routine"));
        String snapshot = properties.getProperty("preview-deployment-sha256", "").trim();
        DatabaseWholeRequestClient.ConnectionVerifier verifier = (connection, timeout) -> {};
        if (!snapshot.isEmpty()) {
            DatabasePreviewDeploymentAttestation attestation = new DatabasePreviewDeploymentAttestation(
                    registryId, packageIdentity, snapshot);
            verifier = (connection, timeout) -> attestation.verify(connection,
                    dialect == DatabaseGraphqlWholeRequestRuntime.Dialect.POSTGRESQL
                            ? DatabaseWholeRequestClient.Dialect.POSTGRESQL
                            : DatabaseWholeRequestClient.Dialect.MYSQL,
                    new DatabaseWholeRequestClient.EntryPoint(
                            entryPoint.schemaName(), entryPoint.routineName()), timeout);
        }
        return new DatabaseGraphqlWholeRequestRuntime(
                dialect,
                () -> dataSources.get().getConnection(),
                required(properties, "model-semantic-sha256"),
                required(properties, "runtime-identity-sha256"),
                packageIdentity,
                "Quarkus default datasource (quarkus.datasource.jdbc.url)",
                entryPoint, registryId, verifier);
    }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("admin database descriptor requires " + name);
        }
        return value.trim();
    }
}
