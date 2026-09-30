package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.titan.graphql.artifact.TitanGraphqlDatabaseFrontendDescriptorCli;
import io.titan.graphql.frontend.DatabaseWholeRequestClient;
import java.io.StringReader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("database-engine-snapshot")
final class CommerceDatabaseSnapshotIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String QUERY = "{ first: customer(id: 7) { name } "
            + "middle: country(code: \"NL\") { name } "
            + "last: customerByIdAndStatus(id: 7, status: ACTIVE) { name } }";
    private static final String CONTEXT = "{\"contextVersion\":\"titan.graphql.request-context/v1\","
            + "\"actorRole\":\"reader\"}";

    @Test
    void postgresqlRequestKeepsOneSnapshotAcrossSeparateRoots() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (Connection setup = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                CommerceDatabaseEngineDeployment.deployPostgreSql(setup);
            }
            assertSnapshot("postgresql", DatabaseWholeRequestClient.Dialect.POSTGRESQL,
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
    }

    @Test
    void mysqlRequestKeepsOneSnapshotAcrossSeparateRoots() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
                .withCommand("--log_bin_trust_function_creators=1", "--max_allowed_packet=64M")
                .withUsername("root")
                .withPassword("test")) {
            mysql.start();
            try (Connection setup = DriverManager.getConnection(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                try (Statement statement = setup.createStatement()) {
                    statement.execute("CREATE DATABASE IF NOT EXISTS titan_runtime");
                }
                CommerceDatabaseEngineDeployment.deployMySql(setup);
            }
            assertSnapshot("mysql", DatabaseWholeRequestClient.Dialect.MYSQL,
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        }
    }

    private static void assertSnapshot(
            String dialect,
            DatabaseWholeRequestClient.Dialect clientDialect,
            String jdbcUrl,
            String username,
            String password
    ) throws Exception {
        Path packageDirectory = Path.of(System.getProperty(
                "titan.graphql.database-engine.commerce.package.dir." + dialect));
        Properties selected = new Properties();
        selected.load(new StringReader(
                TitanGraphqlDatabaseFrontendDescriptorCli.descriptorContents(packageDirectory, dialect)));
        AtomicLong requestSession = new AtomicLong();
        DatabaseWholeRequestClient client = new DatabaseWholeRequestClient(clientDialect, () -> {
            Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
            requestSession.set(sessionIdentifier(connection, dialect));
            return connection;
        }, new DatabaseWholeRequestClient.EntryPoint(
                selected.getProperty("entry-point-schema"), selected.getProperty("entry-point-routine")));
        DatabaseWholeRequestClient.Request request = new DatabaseWholeRequestClient.Request(
                QUERY, "", "{}", "{}", CONTEXT, false,
                selected.getProperty("model-semantic-sha256"),
                selected.getProperty("runtime-identity-sha256"),
                selected.getProperty("package-identity-sha256"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection locker = DriverManager.getConnection(jdbcUrl, username, password);
             Connection observer = DriverManager.getConnection(jdbcUrl, username, password);
             Connection writer = DriverManager.getConnection(jdbcUrl, username, password)) {
            String original = customerName(writer);
            String updated = "Changed after first root";
            boolean lockHeld = false;
            try {
                if (dialect.equals("postgresql")) {
                    locker.setAutoCommit(false);
                }
                try (Statement statement = locker.createStatement()) {
                    statement.execute(dialect.equals("postgresql")
                            ? "LOCK TABLE commerce.countries IN ACCESS EXCLUSIVE MODE"
                            : "LOCK TABLES commerce.countries WRITE");
                }
                lockHeld = true;
                Future<JsonNode> pending = executor.submit(
                        () -> JSON.readTree(client.execute(request).responseJson()));
                waitForCountryLock(observer, requestSession, pending, dialect);
                updateCustomerName(writer, updated);
                releaseLock(locker, dialect);
                lockHeld = false;

                JsonNode response = pending.get(30, TimeUnit.SECONDS);
                assertFalse(response.has("errors"), response::toString);
                assertEquals(original, response.at("/data/first/name").asText(), response::toString);
                assertEquals("Netherlands", response.at("/data/middle/name").asText(), response::toString);
                assertEquals(original, response.at("/data/last/name").asText(), response::toString);
                assertEquals(updated, customerName(writer));
            } finally {
                if (lockHeld) {
                    releaseLock(locker, dialect);
                }
                updateCustomerName(writer, original);
            }
            assertDeadlineCancellation(client, selected, locker, dialect, original);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void assertDeadlineCancellation(
            DatabaseWholeRequestClient client,
            Properties selected,
            Connection locker,
            String dialect,
            String originalName
    ) throws Exception {
        try (Statement statement = locker.createStatement()) {
            statement.execute(dialect.equals("postgresql")
                    ? "LOCK TABLE commerce.customers IN ACCESS EXCLUSIVE MODE"
                    : "LOCK TABLES commerce.customers WRITE");
        }
        try {
            DatabaseWholeRequestClient.Request bounded = new DatabaseWholeRequestClient.Request(
                    "{ customer(id: 7) { name } }", "", "{}", "{}", CONTEXT, false,
                    selected.getProperty("model-semantic-sha256"),
                    selected.getProperty("runtime-identity-sha256"),
                    selected.getProperty("package-identity-sha256"), 1);
            long started = System.nanoTime();
            assertThrows(SQLException.class, () -> client.execute(bounded));
            assertTrue(System.nanoTime() - started < Duration.ofSeconds(15).toNanos(),
                    "locked database request exceeded the cancellation bound");
        } finally {
            releaseLock(locker, dialect);
        }
        DatabaseWholeRequestClient.Request recovered = new DatabaseWholeRequestClient.Request(
                "{ customer(id: 7) { name } }", "", "{}", "{}", CONTEXT, false,
                selected.getProperty("model-semantic-sha256"),
                selected.getProperty("runtime-identity-sha256"),
                selected.getProperty("package-identity-sha256"));
        JsonNode response = JSON.readTree(client.execute(recovered).responseJson());
        assertEquals(originalName, response.at("/data/customer/name").asText(), response::toString);
    }

    private static void waitForCountryLock(
            Connection observer, AtomicLong requestSession, Future<JsonNode> pending, String dialect
    ) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (pending.isDone()) {
                fail("GraphQL request finished before reaching the blocked country root: " + pending.get());
            }
            long session = requestSession.get();
            if (session != 0L && waitingForCountryLock(observer, session, dialect)) {
                return;
            }
            Thread.sleep(50L);
        }
        fail("GraphQL request did not reach the blocked country root");
    }

    private static boolean waitingForCountryLock(Connection observer, long session, String dialect)
            throws Exception {
        String sql = dialect.equals("postgresql")
                ? "SELECT COUNT(*) FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock'"
                : "SELECT COUNT(*) FROM performance_schema.metadata_locks locks "
                        + "JOIN performance_schema.threads threads ON threads.THREAD_ID = locks.OWNER_THREAD_ID "
                        + "WHERE threads.PROCESSLIST_ID = ? AND locks.OBJECT_SCHEMA = 'commerce' "
                        + "AND locks.OBJECT_NAME = 'countries' AND locks.LOCK_STATUS = 'PENDING'";
        try (PreparedStatement statement = observer.prepareStatement(sql)) {
            statement.setLong(1, session);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1) > 0L;
            }
        }
    }

    private static long sessionIdentifier(Connection connection, String dialect) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(dialect.equals("postgresql")
                     ? "SELECT pg_backend_pid()" : "SELECT CONNECTION_ID()")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static String customerName(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT name FROM commerce.customers WHERE id = 7")) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static void updateCustomerName(Connection connection, String name) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE commerce.customers SET name = ? WHERE id = 7")) {
            statement.setString(1, name);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void releaseLock(Connection locker, String dialect) throws Exception {
        if (dialect.equals("postgresql")) {
            locker.rollback();
        } else {
            try (Statement statement = locker.createStatement()) {
                statement.execute("UNLOCK TABLES");
            }
        }
    }
}
