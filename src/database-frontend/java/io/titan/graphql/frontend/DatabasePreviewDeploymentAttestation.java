package io.titan.graphql.frontend;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;

public record DatabasePreviewDeploymentAttestation(
        String registryId,
        String expectedPackageIdentity,
        String expectedSnapshotSha256
) {
    public DatabasePreviewDeploymentAttestation {
        if (registryId == null || !registryId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("preview operation registry ID is invalid");
        }
        if (expectedPackageIdentity == null || !expectedPackageIdentity.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("preview package identity is invalid");
        }
        if (expectedSnapshotSha256 == null || !expectedSnapshotSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("preview deployment snapshot is invalid");
        }
    }

    public void verify(
            Connection connection,
            DatabaseWholeRequestClient.Dialect dialect,
            DatabaseWholeRequestClient.EntryPoint entryPoint,
            int timeoutSeconds
    ) throws SQLException {
        if (!expectedSnapshotSha256.equals(capture(
                connection, dialect, entryPoint, registryId, expectedPackageIdentity, timeoutSeconds))) {
            throw new SQLException("installed preview deployment changed after publication");
        }
    }

    public static String capture(
            Connection connection,
            DatabaseWholeRequestClient.Dialect dialect,
            DatabaseWholeRequestClient.EntryPoint entryPoint,
            String registryId,
            String expectedPackageIdentity,
            int timeoutSeconds
    ) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(entryPoint, "entryPoint");
        if (registryId == null || !registryId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("preview operation registry ID is invalid");
        }
        if (expectedPackageIdentity == null || !expectedPackageIdentity.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("preview package identity is invalid");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("preview attestation timeout must be positive");
        }
        MessageDigest digest = sha256();
        field(digest, entryPoint.qualifiedName());
        field(digest, expectedPackageIdentity);
        field(digest, registryId);
        String packageSchema = dialect == DatabaseWholeRequestClient.Dialect.POSTGRESQL
                ? "public" : entryPoint.schemaName();
        try (PreparedStatement lookup = connection.prepareStatement(
                "SELECT package_identity FROM " + packageSchema
                        + ".titan_graphql_package_identity WHERE entry_point = ?")) {
            lookup.setQueryTimeout(timeoutSeconds);
            lookup.setString(1, entryPoint.qualifiedName());
            try (ResultSet rows = lookup.executeQuery()) {
                if (!rows.next() || !expectedPackageIdentity.equals(rows.getString(1)) || rows.next()) {
                    throw new SQLException("installed preview package identity does not match descriptor");
                }
            }
        }
        try (PreparedStatement lookup = connection.prepareStatement(
                "SELECT model_id, environment, mode, registry_json "
                        + "FROM management.graphql_operation_registries WHERE id = ?")) {
            lookup.setQueryTimeout(timeoutSeconds);
            lookup.setString(1, registryId);
            try (ResultSet rows = lookup.executeQuery()) {
                if (!rows.next() || !"ENFORCE".equals(rows.getString(3))) {
                    throw new SQLException("installed preview operation registry is missing or not ENFORCE");
                }
                for (int column = 1; column <= 4; column++) {
                    field(digest, rows.getString(column));
                }
                if (rows.next()) {
                    throw new SQLException("installed preview operation registry ID is not unique");
                }
            }
        }
        try (PreparedStatement lookup = connection.prepareStatement(
                "SELECT operation_id, operation_position, operation_hash, document_hash, operation_name, "
                        + "document, status, roles_json, clients_json FROM management.graphql_registry_operations "
                        + "WHERE registry_id = ? ORDER BY operation_position")) {
            lookup.setQueryTimeout(timeoutSeconds);
            lookup.setString(1, registryId);
            try (ResultSet rows = lookup.executeQuery()) {
                while (rows.next()) {
                    for (int column = 1; column <= 9; column++) {
                        field(digest, rows.getString(column));
                    }
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void field(MessageDigest digest, String value) {
        if (value == null) {
            length(digest, -1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        length(digest, bytes.length);
        digest.update(bytes);
    }

    private static void length(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 digest is not available", unavailable);
        }
    }
}
