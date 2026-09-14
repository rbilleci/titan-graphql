package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** Proves a second, unrelated schema can be served without a schema-specific executor. */
@Tag("docker")
class GenericJdbcGraphqlDataModelIT {

    private static PostgreSQLContainer<?> postgres;
    private static MySQLContainer<?> mysql;

    enum Target { POSTGRESQL, MYSQL }

    @BeforeAll
    static void startDatabases() {
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("commerce")
                .withUsername("titan")
                .withPassword("titan");
        mysql = new MySQLContainer<>("mysql:8.4")
                .withDatabaseName("commerce")
                .withUsername("titan")
                .withPassword("titan");
        postgres.start();
        mysql.start();
    }

    @AfterAll
    static void stopDatabases() {
        if (postgres != null) postgres.stop();
        if (mysql != null) mysql.stop();
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void servesMutableCommerceSchemaWithoutHandwrittenQueries(Target target) throws Exception {
        DataSource dataSource = dataSource(target);
        initialize(dataSource, target);
        Path modelPath = Path.of("src/test/resources/graphql/commerce.titan.graphql.yaml");
        GraphqlExecutionEngine engine = new GraphqlExecutionEngine(
                "jdbc", () -> dataSource, target.name().toLowerCase(), modelPath.toString());
        GraphqlModelRuntime runtime = engine.runtime();
        assertEquals(GenericJdbcGraphqlRuntime.NAME, runtime.name());
        assertTrue(runtime == engine.runtime(), "the reviewed model/runtime should be loaded only once");

        GraphqlExecution first = execute(runtime);
        assertEquals(
                "{\"data\":{\"customer\":{\"id\":7,\"name\":\"Northwind\",\"active\":true,\"orders\":["
                        + "{\"id\":70,\"reference\":\"NW-001\"},"
                        + "{\"id\":71,\"reference\":\"NW-002\"}]}}}",
                first.json());
        assertEquals(2, first.plan().readStepCount());
        assertTrue(first.plan().readSteps().stream().allMatch(step -> step.sql().contains("?")),
                "Titan DSL must retain bind placeholders in the observable plan");

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE commerce.customers SET name = 'Contoso' WHERE id = 7");
            statement.executeUpdate("INSERT INTO commerce.orders (id, customer_id, reference) "
                    + "VALUES (72, 7, 'CT-003')");
        }

        GraphqlExecution changed = execute(runtime);
        assertTrue(changed.json().contains("\"name\":\"Contoso\""));
        assertTrue(changed.json().contains("\"reference\":\"CT-003\""));
        assertFalse(changed.json().contains("Northwind"));

        GraphqlExecution collection = runtime.executeWithPlan(
                GraphqlRequest.query("{ customers(first: 1) { edges { cursor node { id name } } "
                        + "totalCount pageInfo { hasNextPage hasPreviousPage endCursor } } }"),
                GraphqlRequestContext.legacy(1L, "reader"));
        assertTrue(collection.json().contains("\"totalCount\":2"), collection.json());
        assertTrue(collection.json().contains("\"hasNextPage\":true"), collection.json());
        assertTrue(collection.json().contains("\"cursor\":\"tgqlc1."), collection.json());

        GraphqlRequestContext filteredContext = new GraphqlRequestContext(
                1L, "reader", "actor-1", "", "request-1", "", List.of(),
                List.of("activeCustomers"), false, false, false, 0L,
                Map.of("activeCustomers", true));
        GraphqlExecution filtered = runtime.executeWithPlan(
                GraphqlRequest.query("{ customers(first: 10) { edges { node { id name } } totalCount } }"),
                filteredContext);
        assertTrue(filtered.json().contains("\"totalCount\":1"), filtered.json());
        assertTrue(filtered.json().contains("\"name\":\"Contoso\""), filtered.json());
        assertFalse(filtered.json().contains("Adventure Works"), filtered.json());

        GraphqlRequestContext missingFilterValue = new GraphqlRequestContext(
                1L, "reader", "actor-1", "", "request-2", "", List.of(),
                List.of("activeCustomers"), false, false, false, 0L);
        GraphqlExecution rejected = runtime.executeWithPlan(
                GraphqlRequest.query("{ customers(first: 10) { totalCount } }"), missingFilterValue);
        assertTrue(rejected.json().contains("required request context value 'activeCustomers' is missing"),
                rejected.json());
        assertFalse(rejected.json().contains("\"data\""), rejected.json());
    }

    private static GraphqlExecution execute(GraphqlModelRuntime runtime) {
        return runtime.executeWithPlan(
                GraphqlRequest.query("{ customer(id: 7) { id name active orders { id reference } } }"),
                GraphqlRequestContext.legacy(1L, "reader"));
    }

    private static void initialize(DataSource dataSource, Target target) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            if (target == Target.POSTGRESQL) statement.execute("CREATE SCHEMA IF NOT EXISTS commerce");
            statement.execute("DROP TABLE IF EXISTS commerce.orders");
            statement.execute("DROP TABLE IF EXISTS commerce.customers");
            statement.execute("CREATE TABLE commerce.customers (id BIGINT PRIMARY KEY, name VARCHAR(120) NOT NULL, "
                    + "active BOOLEAN NOT NULL)");
            statement.execute("CREATE TABLE commerce.orders (id BIGINT PRIMARY KEY, customer_id BIGINT NOT NULL, "
                    + "reference VARCHAR(120) NOT NULL, FOREIGN KEY (customer_id) REFERENCES commerce.customers(id))");
            statement.executeUpdate("INSERT INTO commerce.customers (id, name, active) "
                    + "VALUES (7, 'Northwind', true), (8, 'Adventure Works', false)");
            statement.executeUpdate("INSERT INTO commerce.orders (id, customer_id, reference) VALUES "
                    + "(70, 7, 'NW-001'), (71, 7, 'NW-002')");
        }
    }

    private static DataSource dataSource(Target target) {
        return target == Target.POSTGRESQL
                ? new SimpleDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                : new SimpleDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private record SimpleDataSource(String url, String user, String password) implements DataSource {
        @Override public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getLogger("titan-graphql-generic-it");
        }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) return iface.cast(this);
            throw new SQLException("not a wrapper for " + iface);
        }
        @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
    }
}
