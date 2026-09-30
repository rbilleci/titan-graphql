package io.titan.graphql.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.titan.graphql.GraphqlRequestContext;
import io.titan.graphql.GraphqlRuntimeRequest;
import io.titan.graphql.database.DatabaseGraphqlWholeRequestRuntime;
import io.titan.graphql.frontend.DatabasePreviewDeploymentAttestation;
import io.titan.graphql.frontend.DatabaseWholeRequestClient;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import javax.sql.DataSource;

final class TitanGraphqlPreviewDeploymentAttestor {
    private static final JsonMapper JSON = new JsonMapper();

    private TitanGraphqlPreviewDeploymentAttestor() {
    }

    static String attest(
            DataSource servingDatabase,
            String descriptorText,
            TitanGraphqlPreviewBuild candidate,
            TitanGraphqlOperationRegistry expectedRegistry
    ) {
        Properties descriptor = new Properties();
        try {
            descriptor.load(new StringReader(descriptorText));
        } catch (IOException malformed) {
            throw new IllegalArgumentException("preview package descriptor is malformed", malformed);
        }
        DatabaseGraphqlWholeRequestRuntime.EntryPoint entryPoint =
                new DatabaseGraphqlWholeRequestRuntime.EntryPoint(
                        required(descriptor, "entry-point-schema"), required(descriptor, "entry-point-routine"));
        if (expectedRegistry == null || !expectedRegistry.id().equals(candidate.operationRegistryId())
                || !expectedRegistry.modelId().equals(candidate.modelId())
                || !expectedRegistry.environment().equals(candidate.environment())
                || expectedRegistry.mode() != TitanGraphqlOperationRegistry.RegistryMode.ENFORCE) {
            throw new IllegalArgumentException("preview requires a matching ENFORCE operation registry");
        }
        DatabaseGraphqlWholeRequestRuntime.Dialect dialect = switch (required(descriptor, "database-dialect")) {
            case "postgresql" -> DatabaseGraphqlWholeRequestRuntime.Dialect.POSTGRESQL;
            case "mysql" -> DatabaseGraphqlWholeRequestRuntime.Dialect.MYSQL;
            default -> throw new IllegalArgumentException("preview descriptor database dialect is invalid");
        };
        String snapshotSha256;
        try (Connection connection = servingDatabase.getConnection()) {
            attestPackageIdentity(connection, dialect, entryPoint,
                    required(descriptor, "package-identity-sha256"));
            attestRegistry(connection, expectedRegistry);
            snapshotSha256 = DatabasePreviewDeploymentAttestation.capture(
                    connection,
                    dialect == DatabaseGraphqlWholeRequestRuntime.Dialect.POSTGRESQL
                            ? DatabaseWholeRequestClient.Dialect.POSTGRESQL
                            : DatabaseWholeRequestClient.Dialect.MYSQL,
                    new DatabaseWholeRequestClient.EntryPoint(
                            entryPoint.schemaName(), entryPoint.routineName()),
                    expectedRegistry.id(), required(descriptor, "package-identity-sha256"),
                    DatabaseWholeRequestClient.DEFAULT_STATEMENT_TIMEOUT_SECONDS);
        } catch (SQLException failure) {
            throw new IllegalStateException("serving database could not attest the preview deployment", failure);
        }
        DatabaseGraphqlWholeRequestRuntime runtime = new DatabaseGraphqlWholeRequestRuntime(
                dialect, () -> servingDatabase.getConnection(), required(descriptor, "model-semantic-sha256"),
                required(descriptor, "runtime-identity-sha256"), required(descriptor, "package-identity-sha256"),
                "preview publication attestation", entryPoint);
        String response = runtime.execute(new GraphqlRuntimeRequest(
                "query PreviewAttestation { __typename }", "PreviewAttestation", "{}", "{}", false),
                GraphqlRequestContext.forActor(1L, "operator"));
        try {
            JsonNode result = JSON.readTree(response);
            if (result.has("errors") || !"Query".equals(result.at("/data/__typename").asText())) {
                throw new IllegalStateException("installed preview routine did not attest the package identity: "
                        + response);
            }
        } catch (IOException malformed) {
            throw new IllegalStateException("installed preview routine returned malformed attestation JSON", malformed);
        }
        return snapshotSha256;
    }

    private static void attestPackageIdentity(
            Connection connection,
            DatabaseGraphqlWholeRequestRuntime.Dialect dialect,
            DatabaseGraphqlWholeRequestRuntime.EntryPoint entryPoint,
            String expectedIdentity
    ) throws SQLException {
        String packageIdentitySchema = dialect == DatabaseGraphqlWholeRequestRuntime.Dialect.POSTGRESQL
                ? "public" : entryPoint.schemaName();
        String sql = "SELECT package_identity FROM " + packageIdentitySchema
                + ".titan_graphql_package_identity WHERE entry_point = ?";
        try (PreparedStatement lookup = connection.prepareStatement(sql)) {
            lookup.setString(1, entryPoint.qualifiedName());
            try (ResultSet rows = lookup.executeQuery()) {
                if (!rows.next() || !expectedIdentity.equals(rows.getString(1)) || rows.next()) {
                    throw new IllegalStateException("installed preview package identity does not match descriptor");
                }
            }
        }
    }

    private static void attestRegistry(Connection connection, TitanGraphqlOperationRegistry expected)
            throws SQLException {
        try (PreparedStatement lookup = connection.prepareStatement(
                "SELECT model_id, environment, mode, registry_json "
                        + "FROM management.graphql_operation_registries WHERE id = ?")) {
            lookup.setString(1, expected.id());
            try (ResultSet rows = lookup.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("serving database operation registry is missing: " + expected.id());
                }
                if (!expected.modelId().equals(rows.getString(1))
                        || !expected.environment().equals(rows.getString(2))
                        || !expected.mode().name().equals(rows.getString(3))) {
                    throw new IllegalStateException("serving database operation registry header does not match");
                }
                try {
                    if (!JSON.valueToTree(expected).equals(JSON.readTree(rows.getString(4)))) {
                        throw new IllegalStateException("serving database operation registry record does not match");
                    }
                } catch (IOException malformed) {
                    throw new IllegalStateException("serving database operation registry record is malformed",
                            malformed);
                }
                if (rows.next()) {
                    throw new IllegalStateException("serving database operation registry ID is not unique");
                }
            }
        }
        try (PreparedStatement lookup = connection.prepareStatement(
                "SELECT operation_id, operation_position, operation_hash, document_hash, operation_name, "
                        + "document, status, roles_json, clients_json FROM management.graphql_registry_operations "
                        + "WHERE registry_id = ? ORDER BY operation_position")) {
            lookup.setString(1, expected.id());
            try (ResultSet rows = lookup.executeQuery()) {
                List<TitanGraphqlOperationRegistry.RegisteredOperation> operations = expected.operations();
                for (int index = 0; index < operations.size(); index++) {
                    TitanGraphqlOperationRegistry.RegisteredOperation operation = operations.get(index);
                    if (!rows.next() || !operation.id().equals(rows.getString(1))
                            || index != rows.getInt(2)
                            || !operation.operationHash().equals(rows.getString(3))
                            || !sha256(operation.document()).equals(rows.getString(4))
                            || !operation.operationName().equals(rows.getString(5))
                            || !operation.document().equals(rows.getString(6))
                            || !operation.status().name().equals(rows.getString(7))) {
                        throw new IllegalStateException("serving database operation registry projection does not match");
                    }
                    try {
                        if (!JSON.valueToTree(operation.roles()).equals(JSON.readTree(rows.getString(8)))
                                || !JSON.valueToTree(operation.clients()).equals(JSON.readTree(rows.getString(9)))) {
                            throw new IllegalStateException(
                                    "serving database operation registry scope projection does not match");
                        }
                    } catch (IOException malformed) {
                        throw new IllegalStateException("serving database operation registry scope is malformed",
                                malformed);
                    }
                }
                if (rows.next()) {
                    throw new IllegalStateException("serving database operation registry has extra operations");
                }
            }
        }
    }

    private static String required(Properties descriptor, String key) {
        String value = descriptor.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("preview package descriptor requires " + key);
        }
        return value.trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 digest is not available", unavailable);
        }
    }
}
