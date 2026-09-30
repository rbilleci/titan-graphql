package io.titan.graphql.management;

import io.titan.management.ManagementSchemaInstaller;
import io.titan.management.ManagementSchemaInstaller.Dialect;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TitanGraphqlManagementSchemaInstaller {
    private static final String SCHEMA = "management";
    private static final String[] CORE_TABLES = {
        "management_drafts", "management_artifact_refs", "management_deployments",
        "management_idempotency", "management_audit_outcomes"
    };
    private static final String[] CORE_ROUTINES = {
        "activate_deployment", "import_model_document", "seed_artifact_ref",
        "seed_deployment", "seed_draft", "transition_deployment_status", "transition_draft_status"
    };
    private static final Pattern TABLE_DEFINITION = Pattern.compile(
            "(?is)^CREATE\\s+TABLE\\s+([a-z_]+)\\s*\\(");
    private static final Pattern INDEX_DEFINITION = Pattern.compile(
            "(?is)^CREATE\\s+INDEX\\s+([a-z_]+)\\s+ON\\s+([a-z_]+)\\s*\\(([^)]+)\\)$");

    private record Column(String name, String type, boolean nullable) {
    }

    private TitanGraphqlManagementSchemaInstaller() {
    }

    public static void install(Connection connection, Dialect dialect) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new SQLException("management schema installation requires an auto-commit connection");
        }
        ensureSchema(connection, dialect);
        if (tableExists(connection, CORE_TABLES[0])) {
            verifyCore(connection);
        } else {
            ManagementSchemaInstaller.install(connection, dialect);
            verifyCore(connection);
        }
        ensureProductStateTable(connection, dialect);
        ensureArtifactRequestsTable(connection, dialect);
        ensureValidationRequestsTable(connection, dialect);
        ensureImportRequestsTable(connection, dialect);
        ensureObservedOperationsTable(connection, dialect);
        ensureOperationRegistriesTable(connection, dialect);
        ensureRegistryOperationsTable(connection, dialect);
        ensureReviewMutexTable(connection, dialect);
        ensureReviewRequestsTable(connection, dialect);
    }

    public static void repair(Connection connection, Dialect dialect) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new SQLException("management schema repair requires an auto-commit connection");
        }
        ensureSchema(connection, dialect);
        List<String> missing = new ArrayList<>();
        for (String sql : ManagementSchemaInstaller.schemaStatements(dialect)) {
            Matcher table = TABLE_DEFINITION.matcher(sql);
            Matcher index = INDEX_DEFINITION.matcher(sql);
            if (table.find()) {
                String name = table.group(1);
                if (tableExists(connection, name)) {
                    verifyTableShape(connection, name, sql, dialect);
                } else {
                    missing.add(sql);
                }
            } else if (index.matches()) {
                String name = index.group(1);
                String tableName = index.group(2);
                List<String> columns = List.of(index.group(3).toLowerCase(Locale.ROOT).split("\\s*,\\s*"));
                if (!tableExists(connection, tableName) || !indexExists(connection, name, tableName,
                        columns, dialect)) {
                    missing.add(sql);
                }
            } else {
                throw new SQLException("management schema repair does not recognize DDL statement: " + sql);
            }
        }
        for (String sql : missing) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
        if (firstMissingCoreRoutine(connection) != null) {
            ManagementSchemaInstaller.installRoutines(connection, dialect);
        }
        verifyCore(connection);
        ensureProductStateTable(connection, dialect);
        ensureArtifactRequestsTable(connection, dialect);
        ensureValidationRequestsTable(connection, dialect);
        ensureImportRequestsTable(connection, dialect);
        ensureObservedOperationsTable(connection, dialect);
        ensureOperationRegistriesTable(connection, dialect);
        ensureRegistryOperationsTable(connection, dialect);
        ensureReviewMutexTable(connection, dialect);
        ensureReviewRequestsTable(connection, dialect);
        verifyInstalled(connection);
    }

    public static void verifyInstalled(Connection connection) throws SQLException {
        verifyCore(connection);
        try (Statement statement = connection.createStatement()) {
            statement.executeQuery(
                    "SELECT entry_id, entry_json FROM management.graphql_product_state WHERE 1 = 0").close();
        }
        verifyRequestTable(connection, "graphql_artifact_requests", "artifact requests",
                List.of("job_id", "draft_id", "generation_profile", "enable_introspection", "created_at"));
        verifyRequestTable(connection, "graphql_validation_requests", "validation requests",
                List.of("job_id", "draft_id", "created_at"));
        verifyRequestTable(connection, "graphql_import_requests", "import requests",
                List.of("job_id", "workspace_id", "yaml_source", "created_at"));
        verifyRequestTable(connection, "graphql_observed_operations", "observed operations",
                List.of("id", "model_id", "environment", "role", "client", "operation_hash",
                        "operation_name", "document", "status", "depth", "estimated_cost",
                        "field_usage_json", "first_seen_at", "last_seen_at", "observed_count",
                        "operation_json"), "id");
        verifyRequestTable(connection, "graphql_operation_registries", "operation registries",
                List.of("id", "model_id", "environment", "mode", "operations_json", "updated_at",
                        "registry_json"), "id");
        verifyRequestTable(connection, "graphql_registry_operations", "registry operations",
                List.of("registry_id", "operation_id", "operation_position", "operation_hash", "document_hash",
                        "operation_name", "document", "status", "roles_json", "clients_json"),
                List.of("registry_id", "operation_id"));
        verifyRequestTable(connection, "graphql_review_mutex", "review mutex", List.of("id"), "id");
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT id FROM management.graphql_review_mutex WHERE id = 1")) {
            if (!rows.next()) {
                throw new SQLException("management review mutex requires its lock row");
            }
        }
        verifyRequestTable(connection, "graphql_review_requests", "review requests",
                List.of("job_id", "observed_operation_id", "decision", "created_at"));
    }

    private static void ensureSchema(Connection connection, Dialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (dialect == Dialect.POSTGRESQL) {
                statement.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
                statement.execute("SET search_path TO " + SCHEMA);
            } else {
                try {
                    statement.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA);
                } catch (SQLException createDenied) {
                    statement.execute("USE " + SCHEMA);
                    return;
                }
                statement.execute("USE " + SCHEMA);
            }
        }
    }

    private static void verifyCore(Connection connection) throws SQLException {
        for (String table : CORE_TABLES) {
            if (!tableExists(connection, table)) {
                throw new SQLException("management schema is incomplete: missing table " + table);
            }
        }
        String missingRoutine = firstMissingCoreRoutine(connection);
        if (missingRoutine != null) {
            throw new SQLException("management schema is incomplete: missing routine " + missingRoutine);
        }
    }

    private static String firstMissingCoreRoutine(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT routine_name FROM information_schema.routines WHERE routine_schema = ?")) {
            statement.setString(1, SCHEMA);
            try (ResultSet rows = statement.executeQuery()) {
                java.util.Set<String> installed = new java.util.HashSet<>();
                while (rows.next()) {
                    installed.add(rows.getString(1).toLowerCase(java.util.Locale.ROOT));
                }
                for (String routine : CORE_ROUTINES) {
                    if (!installed.contains(routine)) {
                        return routine;
                    }
                }
            }
        }
        return null;
    }

    private static void verifyTableShape(Connection connection, String name, String sql, Dialect dialect)
            throws SQLException {
        int open = sql.indexOf('(');
        int close = sql.lastIndexOf(')');
        if (open < 0 || close <= open) {
            throw new SQLException("management schema repair cannot parse table " + name);
        }
        List<Column> expected = new ArrayList<>();
        List<String> expectedKey = new ArrayList<>();
        for (String rawLine : sql.substring(open + 1, close).split("\\R")) {
            String line = rawLine.trim().replaceFirst(",\\s*$", "");
            if (line.isEmpty()) {
                continue;
            }
            if (line.toUpperCase(Locale.ROOT).startsWith("PRIMARY KEY")) {
                expectedKey.addAll(List.of(line.substring(line.indexOf('(') + 1, line.lastIndexOf(')'))
                        .toLowerCase(Locale.ROOT).split("\\s*,\\s*")));
                continue;
            }
            String[] parts = line.split("\\s+", 3);
            if (parts.length < 2) {
                throw new SQLException("management schema repair cannot parse column in " + name);
            }
            String column = parts[0].toLowerCase(Locale.ROOT);
            boolean primary = line.toUpperCase(Locale.ROOT).contains("PRIMARY KEY");
            expected.add(new Column(column, sqlType(parts[1], dialect),
                    !primary && !line.toUpperCase(Locale.ROOT).contains("NOT NULL")));
            if (primary) {
                expectedKey.add(column);
            }
        }
        List<Column> actual = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT column_name, data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position")) {
            statement.setString(1, SCHEMA);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    actual.add(new Column(rows.getString(1).toLowerCase(Locale.ROOT),
                            rows.getString(2).toLowerCase(Locale.ROOT), "YES".equalsIgnoreCase(rows.getString(3))));
                }
            }
        }
        List<String> actualKey = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT k.column_name FROM information_schema.key_column_usage k "
                        + "JOIN information_schema.table_constraints t ON "
                        + "t.table_schema = k.table_schema AND t.table_name = k.table_name "
                        + "AND t.constraint_name = k.constraint_name "
                        + "WHERE t.table_schema = ? AND t.table_name = ? AND t.constraint_type = 'PRIMARY KEY' "
                        + "ORDER BY k.ordinal_position")) {
            statement.setString(1, SCHEMA);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    actualKey.add(rows.getString(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        if (!expected.equals(actual) || !expectedKey.equals(actualKey)) {
            throw new SQLException("management schema repair refuses incompatible table " + name);
        }
    }

    private static String sqlType(String declaration, Dialect dialect) throws SQLException {
        String type = declaration.replaceFirst("\\(.*", "").toUpperCase(Locale.ROOT);
        return switch (type) {
            case "TEXT", "JSON", "JSONB" -> type.toLowerCase(Locale.ROOT);
            case "VARCHAR" -> dialect == Dialect.POSTGRESQL ? "character varying" : "varchar";
            case "INTEGER" -> "integer";
            case "INT" -> dialect == Dialect.POSTGRESQL ? "integer" : "int";
            default -> throw new SQLException("management schema repair does not recognize SQL type " + type);
        };
    }

    private static boolean indexExists(Connection connection, String name, String table,
            List<String> expectedColumns, Dialect dialect) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = dialect == Dialect.MYSQL ? SCHEMA : connection.getCatalog();
        String schema = dialect == Dialect.MYSQL ? null : SCHEMA;
        Map<Short, String> actualColumns = new TreeMap<>();
        try (ResultSet indexes = metadata.getIndexInfo(catalog, schema, table, false, false)) {
            while (indexes.next()) {
                if (name.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    if (!indexes.getBoolean("NON_UNIQUE")) {
                        throw new SQLException("management schema repair refuses unique index " + name);
                    }
                    actualColumns.put(indexes.getShort("ORDINAL_POSITION"),
                            indexes.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        if (actualColumns.isEmpty()) {
            return false;
        }
        if (!expectedColumns.equals(List.copyOf(actualColumns.values()))) {
            throw new SQLException("management schema repair refuses incompatible index " + name);
        }
        return true;
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT 1 FROM " + SCHEMA + "." + table + " WHERE 1 = 0").close();
            return true;
        } catch (SQLException notFound) {
            String state = notFound.getSQLState();
            if ("42P01".equals(state) || "42S02".equals(state)) {
                return false;
            }
            throw notFound;
        }
    }

    private static void ensureProductStateTable(Connection connection, Dialect dialect) throws SQLException {
        String definition = dialect == Dialect.POSTGRESQL
                ? "entry_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, entry_json TEXT NOT NULL"
                : "entry_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, entry_json LONGTEXT NOT NULL";
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS management.graphql_product_state (" + definition + ")");
        }
    }

    private static void ensureArtifactRequestsTable(Connection connection, Dialect dialect) throws SQLException {
        String definition = dialect == Dialect.POSTGRESQL
                ? "job_id CHAR(36) PRIMARY KEY, draft_id VARCHAR(191) NOT NULL, "
                        + "generation_profile VARCHAR(128) NOT NULL, enable_introspection BOOLEAN NOT NULL, "
                        + "created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP"
                : "job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, "
                        + "draft_id VARCHAR(191) NOT NULL, generation_profile VARCHAR(128) NOT NULL, "
                        + "enable_introspection BOOLEAN NOT NULL, "
                        + "created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)";
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS management.graphql_artifact_requests ("
                    + definition + ")");
        }
        verifyRequestTable(connection, "graphql_artifact_requests", "artifact requests",
                List.of("job_id", "draft_id", "generation_profile", "enable_introspection", "created_at"));
    }

    private static void ensureValidationRequestsTable(Connection connection, Dialect dialect) throws SQLException {
        String definition = dialect == Dialect.POSTGRESQL
                ? "job_id CHAR(36) PRIMARY KEY, draft_id VARCHAR(191) NOT NULL, "
                        + "created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP"
                : "job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, "
                        + "draft_id VARCHAR(191) NOT NULL, "
                        + "created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)";
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS management.graphql_validation_requests ("
                    + definition + ")");
        }
        verifyRequestTable(connection, "graphql_validation_requests", "validation requests",
                List.of("job_id", "draft_id", "created_at"));
    }

    private static void ensureImportRequestsTable(Connection connection, Dialect dialect) throws SQLException {
        String definition = dialect == Dialect.POSTGRESQL
                ? "job_id CHAR(36) PRIMARY KEY, workspace_id VARCHAR(191) NOT NULL, "
                        + "yaml_source TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP"
                : "job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, "
                        + "workspace_id VARCHAR(191) NOT NULL, yaml_source LONGTEXT NOT NULL, "
                        + "created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)";
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS management.graphql_import_requests ("
                    + definition + ")");
        }
        verifyRequestTable(connection, "graphql_import_requests", "import requests",
                List.of("job_id", "workspace_id", "yaml_source", "created_at"));
    }

    private static void ensureObservedOperationsTable(Connection connection, Dialect dialect) throws SQLException {
        String definition = dialect == Dialect.POSTGRESQL
                ? "id VARCHAR(64) PRIMARY KEY, model_id VARCHAR(191) NOT NULL, "
                        + "environment VARCHAR(128) NOT NULL, role VARCHAR(128) NOT NULL, "
                        + "client VARCHAR(191) NOT NULL, operation_hash VARCHAR(128) NOT NULL, "
                        + "operation_name VARCHAR(191) NOT NULL, document TEXT NOT NULL, "
                        + "status VARCHAR(32) NOT NULL, depth INTEGER NOT NULL, "
                        + "estimated_cost INTEGER NOT NULL, field_usage_json TEXT NOT NULL, "
                        + "first_seen_at VARCHAR(64) NOT NULL, last_seen_at VARCHAR(64) NOT NULL, "
                        + "observed_count INTEGER NOT NULL, operation_json TEXT NOT NULL"
                : "id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, "
                        + "model_id VARCHAR(191) NOT NULL, environment VARCHAR(128) NOT NULL, "
                        + "role VARCHAR(128) NOT NULL, client VARCHAR(191) NOT NULL, "
                        + "operation_hash VARCHAR(128) NOT NULL, operation_name VARCHAR(191) NOT NULL, "
                        + "document LONGTEXT NOT NULL, status VARCHAR(32) NOT NULL, "
                        + "depth INTEGER NOT NULL, estimated_cost INTEGER NOT NULL, "
                        + "field_usage_json LONGTEXT NOT NULL, first_seen_at VARCHAR(64) NOT NULL, "
                        + "last_seen_at VARCHAR(64) NOT NULL, observed_count INTEGER NOT NULL, "
                        + "operation_json LONGTEXT NOT NULL";
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS management.graphql_observed_operations ("
                    + definition + ")");
        }
        verifyRequestTable(connection, "graphql_observed_operations", "observed operations",
                List.of("id", "model_id", "environment", "role", "client", "operation_hash",
                        "operation_name", "document", "status", "depth", "estimated_cost",
                        "field_usage_json", "first_seen_at", "last_seen_at", "observed_count",
                        "operation_json"), "id");
    }

    private static void ensureReviewMutexTable(Connection connection, Dialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS management.graphql_review_mutex "
                    + "(id INTEGER PRIMARY KEY)");
            if (dialect == Dialect.POSTGRESQL) {
                statement.execute("INSERT INTO management.graphql_review_mutex (id) VALUES (1) "
                        + "ON CONFLICT (id) DO NOTHING");
            } else {
                statement.execute("INSERT IGNORE INTO management.graphql_review_mutex (id) VALUES (1)");
            }
        }
        verifyRequestTable(connection, "graphql_review_mutex", "review mutex", List.of("id"), "id");
    }

    private static void ensureOperationRegistriesTable(Connection connection, Dialect dialect) throws SQLException {
        String definition = dialect == Dialect.POSTGRESQL
                ? "id VARCHAR(512) PRIMARY KEY, model_id VARCHAR(191) NOT NULL, "
                        + "environment VARCHAR(128) NOT NULL, mode VARCHAR(16) NOT NULL, "
                        + "operations_json TEXT NOT NULL, updated_at VARCHAR(64) NOT NULL, "
                        + "registry_json TEXT NOT NULL"
                : "id VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, "
                        + "model_id VARCHAR(191) NOT NULL, environment VARCHAR(128) NOT NULL, "
                        + "mode VARCHAR(16) NOT NULL, operations_json LONGTEXT NOT NULL, "
                        + "updated_at VARCHAR(64) NOT NULL, registry_json LONGTEXT NOT NULL";
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS management.graphql_operation_registries ("
                    + definition + ")");
        }
        verifyRequestTable(connection, "graphql_operation_registries", "operation registries",
                List.of("id", "model_id", "environment", "mode", "operations_json", "updated_at",
                        "registry_json"), "id");
    }

    private static void ensureRegistryOperationsTable(Connection connection, Dialect dialect) throws SQLException {
        String definition = dialect == Dialect.POSTGRESQL
                ? "registry_id VARCHAR(512) NOT NULL, operation_id VARCHAR(512) NOT NULL, "
                        + "operation_position INTEGER NOT NULL, "
                        + "operation_hash VARCHAR(512) NOT NULL, document_hash VARCHAR(64) NOT NULL, "
                        + "operation_name TEXT NOT NULL, "
                        + "document TEXT NOT NULL, status VARCHAR(16) NOT NULL, "
                        + "roles_json TEXT NOT NULL, clients_json TEXT NOT NULL, "
                        + "PRIMARY KEY (registry_id, operation_id)"
                : "registry_id VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, "
                        + "operation_id VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, "
                        + "operation_position INTEGER NOT NULL, "
                        + "operation_hash VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, "
                        + "document_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, "
                        + "operation_name LONGTEXT NOT NULL, document LONGTEXT NOT NULL, "
                        + "status VARCHAR(16) NOT NULL, roles_json LONGTEXT NOT NULL, "
                        + "clients_json LONGTEXT NOT NULL, PRIMARY KEY (registry_id, operation_id), "
                        + "INDEX graphql_registry_operations_match (registry_id, operation_hash), "
                        + "INDEX graphql_registry_operations_document_match (registry_id, document_hash)";
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS management.graphql_registry_operations ("
                    + definition + ")");
            if (dialect == Dialect.POSTGRESQL) {
                statement.execute("CREATE INDEX IF NOT EXISTS graphql_registry_operations_match "
                        + "ON management.graphql_registry_operations (registry_id, operation_hash)");
                statement.execute("CREATE INDEX IF NOT EXISTS graphql_registry_operations_document_match "
                        + "ON management.graphql_registry_operations (registry_id, document_hash)");
            }
        }
        verifyRequestTable(connection, "graphql_registry_operations", "registry operations",
                List.of("registry_id", "operation_id", "operation_position", "operation_hash", "document_hash",
                        "operation_name", "document", "status", "roles_json", "clients_json"),
                List.of("registry_id", "operation_id"));
    }

    private static void ensureReviewRequestsTable(Connection connection, Dialect dialect) throws SQLException {
        String definition = dialect == Dialect.POSTGRESQL
                ? "job_id CHAR(36) PRIMARY KEY, observed_operation_id VARCHAR(64) NOT NULL, "
                        + "decision VARCHAR(16) NOT NULL, "
                        + "created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP"
                : "job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, "
                        + "observed_operation_id VARCHAR(64) NOT NULL, decision VARCHAR(16) NOT NULL, "
                        + "created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)";
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS management.graphql_review_requests ("
                    + definition + ")");
        }
        verifyRequestTable(connection, "graphql_review_requests", "review requests",
                List.of("job_id", "observed_operation_id", "decision", "created_at"));
    }

    private static void verifyRequestTable(
            Connection connection,
            String table,
            String label,
            List<String> required
    ) throws SQLException {
        verifyRequestTable(connection, table, label, required, "job_id");
    }

    private static void verifyRequestTable(
            Connection connection,
            String table,
            String label,
            List<String> required,
            String primaryKeyColumn
    ) throws SQLException {
        verifyRequestTable(connection, table, label, required, List.of(primaryKeyColumn));
    }

    private static void verifyRequestTable(
            Connection connection,
            String table,
            String label,
            List<String> required,
            List<String> primaryKeyColumns
    ) throws SQLException {
        Map<String, Boolean> nullable = new TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT column_name, is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ?")) {
            statement.setString(1, SCHEMA);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    nullable.put(rows.getString(1).toLowerCase(Locale.ROOT),
                            "YES".equalsIgnoreCase(rows.getString(2)));
                }
            }
        }
        for (String column : required) {
            if (!Boolean.FALSE.equals(nullable.get(column))) {
                throw new SQLException("management " + label + " table requires non-null column " + column);
            }
        }
        List<String> primaryKey = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT k.column_name FROM information_schema.key_column_usage k "
                        + "JOIN information_schema.table_constraints t ON "
                        + "t.table_schema = k.table_schema AND t.table_name = k.table_name "
                        + "AND t.constraint_name = k.constraint_name "
                        + "WHERE t.table_schema = ? AND t.table_name = ? AND t.constraint_type = 'PRIMARY KEY' "
                        + "ORDER BY k.ordinal_position")) {
            statement.setString(1, SCHEMA);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    primaryKey.add(rows.getString(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        if (!primaryKey.equals(primaryKeyColumns)) {
            throw new SQLException("management " + label + " table requires "
                    + String.join(", ", primaryKeyColumns) + " primary key");
        }
    }
}
