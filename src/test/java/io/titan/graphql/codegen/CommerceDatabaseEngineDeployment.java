package io.titan.graphql.codegen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Deploys only the physical commerce schema and an already-packaged database engine proof. */
final class CommerceDatabaseEngineDeployment {

    private CommerceDatabaseEngineDeployment() {
    }

    static void deployPostgreSql(Connection connection) throws IOException, SQLException {
        createPhysicalSchema(connection, false);
        Path migrations = migrationDirectory(false);
        executePostgreSqlScript(connection, migrations.resolve("R__titan_010_runtime.sql"));
        executePostgreSqlScript(connection, migrations.resolve("R__titan_020_routines.sql"));
        seed(connection);
    }

    static void deployMySql(Connection connection) throws IOException, SQLException {
        createPhysicalSchema(connection, true);
        // Runtime helpers in the packaged MySQL migration are intentionally unqualified: they
        // must be installed in the same database as the generated `public` routines that call
        // them. The Testcontainers connection itself defaults to its harness database (`test`),
        // so switch explicitly before applying either package migration. Application tables use
        // their fully-qualified `commerce` database and remain independent of this choice.
        try (Statement statement = connection.createStatement()) {
            statement.execute("USE public");
        }
        Path migrations = migrationDirectory(true);
        executeMySqlScript(connection, migrations.resolve("R__titan_010_runtime.sql"));
        executeMySqlScript(connection, migrations.resolve("R__titan_020_routines.sql"));
        seed(connection);
    }

    /** Adds deterministic fixture rows for one relation-budget probe after the package is installed. */
    static void addOrdersForCustomer(Connection connection, long customerId, long firstId, int count)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO commerce.orders (id, customer_id, reference, status) VALUES (?, ?, ?, ?)")) {
            for (int index = 0; index < count; index++) {
                statement.setLong(1, firstId + index);
                statement.setLong(2, customerId);
                statement.setString(3, "relation-budget-" + index);
                statement.setString(4, "OPEN");
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** Adds deterministic customer rows for aggregate request-budget probes. */
    static void addCustomers(Connection connection, long firstId, int count) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO commerce.customers "
                        + "(id, node_id, external_id, sort_rank, tenant_key, name, nickname, active, status, rating, credit_limit, verified) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (int index = 0; index < count; index++) {
                long id = firstId + index;
                statement.setLong(1, id);
                statement.setLong(2, 10_000L + id);
                statement.setString(3, "row-budget-" + id);
                statement.setLong(4, id);
                statement.setString(5, "tenant-a");
                statement.setString(6, "Row Budget " + id);
                statement.setString(7, null);
                statement.setBoolean(8, true);
                statement.setString(9, "ACTIVE");
                statement.setInt(10, index);
                statement.setDouble(11, 100.0);
                statement.setBoolean(12, true);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** Adds one deterministic order for each customer in a contiguous fixture range. */
    static void addOneOrderPerCustomer(Connection connection, long firstCustomerId, int count)
            throws SQLException {
        addOneOrderPerCustomer(connection, firstCustomerId, count, 1_000_000L);
    }

    /** Adds a second deterministic relation page with a caller-selected noncolliding id base. */
    static void addOneOrderPerCustomer(
            Connection connection,
            long firstCustomerId,
            int count,
            long orderIdBase
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO commerce.orders (id, customer_id, reference, status) VALUES (?, ?, ?, ?)")) {
            for (int index = 0; index < count; index++) {
                long customerId = firstCustomerId + index;
                statement.setLong(1, orderIdBase + customerId);
                statement.setLong(2, customerId);
                statement.setString(3, "relation-batch-" + customerId);
                statement.setString(4, "OPEN");
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** Removes one deterministic fixture order so relation batches prove zero-child parents. */
    static void removeOrder(Connection connection, long orderId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM commerce.orders WHERE id = ?")) {
            statement.setLong(1, orderId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("expected exactly one fixture order to be removed: " + orderId);
            }
        }
    }

    private static Path migrationDirectory(boolean mysql) {
        String property = mysql
                ? "titan.graphql.database-engine.commerce.migrations.dir.mysql"
                : "titan.graphql.database-engine.commerce.migrations.dir";
        String fallback = mysql
                ? "build/generated/proofs/database-engine-commerce-mysql/package/mysql"
                : "build/generated/proofs/database-engine-commerce/package/postgresql";
        return Path.of(System.getProperty(property, fallback));
    }

    private static void createPhysicalSchema(Connection connection, boolean mysql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (mysql) {
                statement.execute("CREATE DATABASE IF NOT EXISTS public");
                statement.execute("CREATE DATABASE IF NOT EXISTS commerce");
            } else {
                statement.execute("CREATE SCHEMA IF NOT EXISTS commerce");
            }
            statement.execute("DROP TABLE IF EXISTS commerce.orders");
            statement.execute("DROP TABLE IF EXISTS commerce.customers");
            statement.execute("DROP TABLE IF EXISTS commerce.inventory_items");
            statement.execute("DROP TABLE IF EXISTS commerce.api_clients");
            statement.execute("DROP TABLE IF EXISTS commerce.countries");
            statement.execute("CREATE TABLE commerce.customers (id BIGINT PRIMARY KEY, node_id BIGINT NOT NULL, "
                    + "external_id VARCHAR(120) NOT NULL, sort_rank BIGINT NOT NULL, "
                    + "tenant_key VARCHAR(80) NOT NULL, "
                    + "name VARCHAR(120) NOT NULL, nickname VARCHAR(120), active BOOLEAN NOT NULL, "
                    + "status VARCHAR(24) NOT NULL, "
                    + "rating INTEGER, credit_limit DOUBLE PRECISION NOT NULL, verified BOOLEAN)");
            statement.execute("CREATE TABLE commerce.orders (id BIGINT PRIMARY KEY, "
                    + "customer_id BIGINT NOT NULL, reference VARCHAR(120) NOT NULL, "
                    + "status VARCHAR(24) NOT NULL, "
                    + "FOREIGN KEY (customer_id) REFERENCES commerce.customers(id))");
            statement.execute("CREATE TABLE commerce.countries (code VARCHAR(8) PRIMARY KEY, "
                    + "name VARCHAR(120) NOT NULL)");
            statement.execute("CREATE TABLE commerce.api_clients (id "
                    + (mysql ? "CHAR(36)" : "UUID") + " PRIMARY KEY, sort_rank BIGINT NOT NULL, "
                    + "label VARCHAR(120) NOT NULL)");
            statement.execute("CREATE TABLE commerce.inventory_items (warehouse_code VARCHAR(16) NOT NULL, "
                    + "sku VARCHAR(40) NOT NULL, quantity INTEGER NOT NULL, "
                    + "PRIMARY KEY (warehouse_code, sku))");
        }
    }

    private static void seed(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO commerce.customers "
                    + "(id, node_id, external_id, sort_rank, tenant_key, name, nickname, active, status, rating, credit_limit, verified) VALUES "
                    + "(7, 7001, '7001', 10, 'tenant-a', 'Northwind', NULL, true, 'ACTIVE', 42, 1250.5, NULL), "
                    + "(8, 7002, 'customer:adventure', 10, 'tenant-b', 'Adventure Works', 'Adventure', false, 'INACTIVE', NULL, 500.0, true)");
            statement.executeUpdate("INSERT INTO commerce.orders (id, customer_id, reference, status) VALUES "
                    + "(70, 7, 'NW-001', 'OPEN')");
            statement.executeUpdate("INSERT INTO commerce.countries (code, name) VALUES "
                    + "('NL', 'Netherlands')");
            statement.executeUpdate("INSERT INTO commerce.api_clients (id, sort_rank, label) VALUES "
                    + "('11111111-2222-3333-4444-555555555555', 7, 'public-client'), "
                    + "('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', 8, 'private-client')");
            statement.executeUpdate("INSERT INTO commerce.inventory_items "
                    + "(warehouse_code, sku, quantity) VALUES ('AMS', 'TG-42', 17)");
        }
    }

    private static void executePostgreSqlScript(Connection connection, Path script) throws IOException, SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(readScript(script));
        }
    }

    private static void executeMySqlScript(Connection connection, Path script) throws IOException, SQLException {
        for (String statementSql : splitMySqlStatements(readScript(script))) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementSql);
            }
        }
    }

    private static String readScript(Path script) throws IOException {
        if (Files.exists(script) == false) {
            throw new IllegalStateException("missing packaged database-engine migration: "
                    + script.toAbsolutePath());
        }
        return Files.readString(script);
    }

    private static List<String> splitMySqlStatements(String script) {
        List<String> statements = new ArrayList<>();
        String delimiter = ";";
        StringBuilder buffer = new StringBuilder();
        for (String line : script.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("DELIMITER ")) {
                delimiter = trimmed.substring("DELIMITER ".length()).trim();
                continue;
            }
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(line);
            String current = buffer.toString().trim();
            if (current.endsWith(delimiter)) {
                String statement = current.substring(0, current.length() - delimiter.length()).trim();
                if (statement.isEmpty() == false && commentOnly(statement) == false) {
                    statements.add(statement);
                }
                buffer.setLength(0);
            }
        }
        String tail = buffer.toString().trim();
        if (tail.isEmpty() == false && commentOnly(tail) == false) {
            statements.add(tail);
        }
        return statements;
    }

    private static boolean commentOnly(String sql) {
        for (String line : sql.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() == false && trimmed.startsWith("--") == false) {
                return false;
            }
        }
        return true;
    }
}
