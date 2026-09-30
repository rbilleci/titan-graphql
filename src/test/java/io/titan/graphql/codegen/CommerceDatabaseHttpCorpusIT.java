package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.titan.graphql.artifact.TitanGraphqlDatabaseFrontendDescriptorCli;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("database-engine-commerce-http-corpus")
final class CommerceDatabaseHttpCorpusIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void postgresqlDistributionMatchesCommerceCorpus(@TempDir Path workspace) throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
            }
            assertCorpus(workspace, "postgresql", postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
        }
    }

    @Test
    void mysqlDistributionMatchesCommerceCorpus(@TempDir Path workspace) throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
                .withCommand("--log_bin_trust_function_creators=1", "--max_allowed_packet=64M")
                .withUsername("root")
                .withPassword("test")) {
            mysql.start();
            try (Connection connection = DriverManager.getConnection(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE DATABASE IF NOT EXISTS titan_runtime");
                }
                CommerceDatabaseEngineDeployment.deployMySql(connection);
            }
            assertCorpus(workspace, "mysql", mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        }
    }

    private static void assertCorpus(
            Path workspace,
            String dialect,
            String jdbcUrl,
            String username,
            String password
    ) throws Exception {
        Path packageDirectory = Path.of(System.getProperty(dialect.equals("mysql")
                ? "titan.graphql.database-engine.commerce.package.dir.mysql"
                : "titan.graphql.database-engine.commerce.package.dir"));
        Path descriptor = workspace.resolve("frontend.properties");
        TitanGraphqlDatabaseFrontendDescriptorCli.main(new String[] {
                packageDirectory.toString(), dialect, descriptor.toString()
        });
        assertInstalledPackageIdentity(packageDirectory, descriptor, jdbcUrl, username, password);
        Path distribution = Path.of(System.getProperty("titan.graphql.database-frontend.distribution"));
        try (Frontend frontend = startFrontend(workspace.resolve("frontend"), distribution, descriptor,
                jdbcUrl, username, password)) {
            waitForFrontend(frontend);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            DatabaseEngineExpectedResultCorpus.assertCommerceV1(
                    (query, operationName, variablesJson, actorRole, allowMutations,
                            allowIntrospection, tenantIsolation, tenantKey) ->
                            execute(client, frontend.endpoint(), query, operationName, variablesJson,
                                    actorRole, allowIntrospection, tenantIsolation, tenantKey));
            measureRequestLatency(client, frontend.endpoint(), dialect);
            assertGetMutationDenied(client, frontend.endpoint(), jdbcUrl, username, password);
            assertFailedMutationRollsBack(client, frontend.endpoint(), jdbcUrl, username, password);
        }
    }

    private static void measureRequestLatency(HttpClient client, URI endpoint, String dialect) throws Exception {
        String query = "{ customer(id: 7) { id name } }";
        for (int warmup = 0; warmup < 2; warmup++) {
            execute(client, endpoint, query, "", "{}", "reader", false);
        }
        long[] milliseconds = new long[10];
        for (int index = 0; index < milliseconds.length; index++) {
            long started = System.nanoTime();
            JsonNode response = execute(client, endpoint, query, "", "{}", "reader", false);
            milliseconds[index] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(7, response.at("/data/customer/id").asInt(), response::toString);
        }
        Arrays.sort(milliseconds);
        System.out.println("M5_HTTP_REQUEST_LATENCY dialect=" + dialect
                + " samples=10 p50Millis=" + milliseconds[5]
                + " p95Millis=" + milliseconds[9]
                + " maxMillis=" + milliseconds[9]);
    }

    private static JsonNode execute(
            HttpClient client,
            URI endpoint,
            String query,
            String operationName,
            String variablesJson,
            String actorRole,
            boolean allowIntrospection
    ) throws Exception {
        return execute(client, endpoint, query, operationName, variablesJson,
                actorRole, allowIntrospection, false, "");
    }

    private static JsonNode execute(
            HttpClient client,
            URI endpoint,
            String query,
            String operationName,
            String variablesJson,
            String actorRole,
            boolean allowIntrospection,
            boolean tenantIsolation,
            String tenantKey
    ) throws Exception {
        ObjectNode body = JSON.createObjectNode().put("query", query);
        if (operationName.isEmpty() == false) {
            body.put("operationName", operationName);
        }
        body.set("variables", JSON.readTree(variablesJson));
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/graphql-response+json")
                .header("Content-Type", "application/json")
                .header("X-Titan-Actor-Role", actorRole)
                .header("X-Titan-Introspection", Boolean.toString(allowIntrospection))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (tenantIsolation) {
            request.header("X-Titan-Context-Filters", "tenantIsolation");
            request.header("X-Titan-Context-Values", tenantKey.isBlank()
                    ? "{}" : JSON.createObjectNode().put("tenantKey", tenantKey).toString());
        }
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static void assertFailedMutationRollsBack(
            HttpClient client,
            URI endpoint,
            String jdbcUrl,
            String username,
            String password
    ) throws Exception {
        String originalName;
        long originalAuditCount;
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            originalName = customerName(connection, 8);
            originalAuditCount = auditCount(connection);
        }
        JsonNode failed = execute(client, endpoint,
                "mutation { first: renameCustomer(id: 8, name: \"Must roll back\") { id } "
                        + "second: renameCustomer(id: 999, name: \"Missing\") { id } }",
                "", "{}", "editor", false);
        assertTrue(failed.at("/data").isNull(), failed::toString);
        assertEquals("EXECUTION_ERROR", failed.at("/errors/0/extensions/code").asText(), failed::toString);
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            assertEquals(originalName, customerName(connection, 8));
            assertEquals(originalAuditCount, auditCount(connection));
        }
    }

    private static void assertGetMutationDenied(
            HttpClient client,
            URI endpoint,
            String jdbcUrl,
            String username,
            String password
    ) throws Exception {
        String originalName;
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            originalName = customerName(connection, 8);
        }
        String query = "mutation { renameCustomer(id: 8, name: \"GET must not write\") { id } }";
        URI mutationUri = URI.create(endpoint + "?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20"));
        HttpRequest request = HttpRequest.newBuilder(mutationUri)
                .timeout(Duration.ofSeconds(30))
                .header("X-Titan-Actor-Role", "editor")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode denied = JSON.readTree(response.body());
        assertEquals("UNSUPPORTED_OPERATION", denied.at("/errors/0/extensions/code").asText(),
                denied::toString);
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            assertEquals(originalName, customerName(connection, 8));
        }
    }

    private static String customerName(Connection connection, long id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name FROM commerce.customers WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private static long auditCount(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM public.titan_graphql_mutation_audit")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static void assertInstalledPackageIdentity(
            Path packageDirectory,
            Path descriptor,
            String jdbcUrl,
            String username,
            String password
    ) throws Exception {
        Properties selection = new Properties();
        try (var input = Files.newInputStream(descriptor)) {
            selection.load(input);
        }
        String identity = Files.readString(packageDirectory.resolve(
                "titan-graphql-database-package-identity.sha256")).trim();
        assertEquals(identity, selection.getProperty("package-identity-sha256"));
        String entryPoint = selection.getProperty("entry-point-schema") + "."
                + selection.getProperty("entry-point-routine");
        String dialect = selection.getProperty("database-dialect");
        Path routines = packageDirectory.resolve(dialect.equals("mysql") ? "mysql" : "postgresql")
                .resolve("R__titan_020_routines.sql");
        String sql = Files.readString(routines);
        assertTrue(sql.contains("titan:database-package-identity:v1"),
                "packaged SQL lacks its generated identity migration");
        assertTrue(sql.contains(identity), "packaged SQL lacks its bound identity");
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT package_identity FROM public.titan_graphql_package_identity WHERE entry_point = ?")) {
            statement.setString(1, entryPoint);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "installed database lacks bound package identity");
                assertEquals(identity, rows.getString(1).trim());
                assertFalse(rows.next(), "installed database has duplicate package identities");
            }
        }
    }

    private static Frontend startFrontend(
            Path home,
            Path distribution,
            Path descriptor,
            String jdbcUrl,
            String username,
            String password
    ) throws Exception {
        Files.createDirectories(home);
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(distribution))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path target = home.resolve(entry.getName()).normalize();
                if (target.startsWith(home) == false) {
                    throw new IllegalStateException("distribution contains an unsafe archive entry " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(input, target);
                }
            }
        }
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        ProcessBuilder launcher = new ProcessBuilder(
                "sh", home.resolve("bin/titan-graphql-database-http").toString());
        launcher.directory(home.toFile());
        launcher.redirectErrorStream(true);
        Path output = home.resolve("frontend.log");
        launcher.redirectOutput(output.toFile());
        launcher.environment().put("TITAN_GRAPHQL_FRONTEND_DESCRIPTOR", descriptor.toAbsolutePath().toString());
        launcher.environment().put("TITAN_GRAPHQL_JDBC_URL", jdbcUrl);
        launcher.environment().put("TITAN_GRAPHQL_JDBC_USERNAME", username);
        launcher.environment().put("TITAN_GRAPHQL_JDBC_PASSWORD", password);
        launcher.environment().put("TITAN_GRAPHQL_HTTP_PORT", String.valueOf(port));
        launcher.environment().put("TITAN_GRAPHQL_HTTP_TRUST_REQUEST_CONTEXT_HEADERS", "true");
        return new Frontend(launcher.start(), URI.create("http://127.0.0.1:" + port + "/graphql"), output);
    }

    private static void waitForFrontend(Frontend frontend) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        URI probeUri = URI.create(frontend.endpoint() + "?query=%7Bcustomer%28id%3A7%29%7Bid%7D%7D");
        HttpRequest probe = HttpRequest.newBuilder(probeUri)
                .timeout(Duration.ofSeconds(2))
                .header("X-Titan-Actor-Role", "reader")
                .GET()
                .build();
        for (int attempt = 0; attempt < 150; attempt++) {
            if (frontend.process().isAlive() == false) {
                fail("frontend exited before serving: " + frontend.output());
            }
            try {
                HttpResponse<String> response = client.send(probe, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"id\":7")) {
                    return;
                }
            } catch (ConnectException notReady) {
            }
            Thread.sleep(100L);
        }
        fail("frontend did not serve the bound Commerce model: " + frontend.output());
    }

    private record Frontend(Process process, URI endpoint, Path outputFile) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            process.destroy();
            if (process.waitFor(5, TimeUnit.SECONDS) == false) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            assertFalse(process.isAlive(), "frontend process did not exit: " + output());
        }

        private String output() throws Exception {
            return Files.exists(outputFile) ? Files.readString(outputFile) : "no frontend log";
        }
    }
}
