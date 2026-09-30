package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.titan.graphql.artifact.TitanGraphqlDatabaseFrontendDescriptorCli;
import io.titan.graphql.frontend.DatabaseWholeRequestClient;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("database-engine-package-replacement")
final class CommerceDatabasePackageReplacementIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONTEXT = "{\"contextVersion\":\"titan.graphql.request-context/v1\","
            + "\"actorRole\":\"reader\"}";

    @Test
    void postgresqlReplacementRejectsStaleBindingOnTheSameConnection() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (Connection physical = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                CommerceDatabaseEngineDeployment.deployPostgreSql(physical);
                assertReplacement(physical, "postgresql", DatabaseWholeRequestClient.Dialect.POSTGRESQL);
            }
        }
    }

    @Test
    void mysqlReplacementRejectsStaleBindingOnTheSameConnection() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
                .withCommand("--log_bin_trust_function_creators=1", "--max_allowed_packet=64M")
                .withUsername("root")
                .withPassword("test")) {
            mysql.start();
            try (Connection physical = DriverManager.getConnection(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                try (Statement statement = physical.createStatement()) {
                    statement.execute("CREATE DATABASE IF NOT EXISTS titan_runtime");
                }
                CommerceDatabaseEngineDeployment.deployMySql(physical);
                assertReplacement(physical, "mysql", DatabaseWholeRequestClient.Dialect.MYSQL);
            }
        }
    }

    private static void assertReplacement(
            Connection physical,
            String dialect,
            DatabaseWholeRequestClient.Dialect clientDialect
    ) throws Exception {
        Path commerce = packageDirectory("titan.graphql.database-engine.commerce.package.dir", dialect);
        Path replacement = packageDirectory("titan.graphql.database-engine.commerce.replacement.package.dir", dialect);
        Properties oldSelection = descriptor(commerce, dialect);
        Properties newSelection = descriptor(replacement, dialect);
        assertEquals(oldSelection.getProperty("model-semantic-sha256"),
                newSelection.getProperty("model-semantic-sha256"));
        assertEquals(oldSelection.getProperty("runtime-identity-sha256"),
                newSelection.getProperty("runtime-identity-sha256"));
        assertNotEquals(oldSelection.getProperty("package-identity-sha256"),
                newSelection.getProperty("package-identity-sha256"));
        Connection pooled = nonClosingConnection(physical);
        AtomicInteger opens = new AtomicInteger();
        DatabaseWholeRequestClient.ConnectionProvider provider = () -> {
            opens.incrementAndGet();
            return pooled;
        };
        DatabaseWholeRequestClient oldClient = client(clientDialect, provider, oldSelection);
        JsonNode before = response(oldClient, oldSelection, "{ customer(id: 7) { name } }");
        assertEquals("Northwind", before.at("/data/customer/name").asText(), before::toString);

        installReplacement(physical, replacement, dialect);
        assertEquals(newSelection.getProperty("package-identity-sha256"),
                installedIdentity(physical, "public.execute_graphql_request"));

        JsonNode stale = response(oldClient, oldSelection, "{ customer(id: 7) { name } }");
        assertTrue(stale.has("errors"), stale::toString);
        assertTrue(stale.at("/errors/0/message").asText().contains("package identity"), stale::toString);
        assertTrue(stale.path("data").isMissingNode() || stale.path("data").isNull(), stale::toString);

        DatabaseWholeRequestClient newClient = client(clientDialect, provider, newSelection);
        JsonNode current = response(newClient, newSelection, "{ customer(id: 7) { name } }");
        assertEquals("Northwind", current.at("/data/customer/name").asText(), current::toString);
        assertEquals(3, opens.get());
    }

    private static Path packageDirectory(String property, String dialect) {
        return Path.of(System.getProperty(property + "." + dialect));
    }

    private static Properties descriptor(Path packageDirectory, String dialect) throws Exception {
        Properties selected = new Properties();
        selected.load(new StringReader(
                TitanGraphqlDatabaseFrontendDescriptorCli.descriptorContents(packageDirectory, dialect)));
        return selected;
    }

    private static DatabaseWholeRequestClient client(
            DatabaseWholeRequestClient.Dialect dialect,
            DatabaseWholeRequestClient.ConnectionProvider provider,
            Properties selected
    ) {
        return new DatabaseWholeRequestClient(dialect, provider,
                new DatabaseWholeRequestClient.EntryPoint(
                        selected.getProperty("entry-point-schema"),
                        selected.getProperty("entry-point-routine")));
    }

    private static JsonNode response(
            DatabaseWholeRequestClient client, Properties selected, String query
    ) throws Exception {
        DatabaseWholeRequestClient.Request request = new DatabaseWholeRequestClient.Request(
                query, "", "{}", "{}", CONTEXT, false,
                selected.getProperty("model-semantic-sha256"),
                selected.getProperty("runtime-identity-sha256"),
                selected.getProperty("package-identity-sha256"));
        return JSON.readTree(client.execute(request).responseJson());
    }

    private static String installedIdentity(Connection connection, String entryPoint) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT package_identity FROM public.titan_graphql_package_identity WHERE entry_point = ?")) {
            statement.setString(1, entryPoint);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1).trim();
            }
        }
    }

    private static void installReplacement(Connection connection, Path packageDirectory, String dialect)
            throws Exception {
        Path migrations = packageDirectory.resolve(dialect);
        if (dialect.equals("mysql")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("USE public");
            }
            CommerceDatabaseEngineDeployment.executeMySqlScript(
                    connection, migrations.resolve("R__titan_010_runtime.sql"));
            CommerceDatabaseEngineDeployment.executeMySqlScript(
                    connection, migrations.resolve("R__titan_020_routines.sql"));
        } else {
            CommerceDatabaseEngineDeployment.executePostgreSqlScript(
                    connection, migrations.resolve("R__titan_010_runtime.sql"));
            CommerceDatabaseEngineDeployment.executePostgreSqlScript(
                    connection, migrations.resolve("R__titan_020_routines.sql"));
        }
    }

    private static Connection nonClosingConnection(Connection physical) {
        return (Connection) Proxy.newProxyInstance(
                CommerceDatabasePackageReplacementIT.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    try {
                        return method.invoke(physical, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
