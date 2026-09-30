package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.titan.graphql.artifact.TitanGraphqlDatabaseFrontendDescriptorCli;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
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
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("database-engine-commerce-http-restart")
final class CommerceDatabaseHttpRestartIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String QUERY = "mutation RestartMutation { renameCustomer(id: 7, "
            + "name: \"Recovered after restart\") { id name } }";
    private static final String IDEMPOTENCY_KEY = "commerce-http-restart";

    @Test
    void postgresqlReplaysCommittedMutationAfterLostHttpResponseAndFrontendRestart(@TempDir Path workspace)
            throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
                assertRecovery(workspace, "postgresql", connection,
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            }
        }
    }

    @Test
    void mysqlReplaysCommittedMutationAfterLostHttpResponseAndFrontendRestart(@TempDir Path workspace)
            throws Exception {
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
                assertRecovery(workspace, "mysql", connection,
                        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            }
        }
    }

    private static void assertRecovery(
            Path workspace,
            String dialect,
            Connection connection,
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
        Path distribution = Path.of(System.getProperty("titan.graphql.database-frontend.distribution"));
        String body = JSON.createObjectNode()
                .put("query", QUERY)
                .put("operationName", "RestartMutation")
                .toString();

        try (Frontend first = startFrontend(workspace.resolve("first"), distribution, descriptor,
                jdbcUrl, username, password)) {
            waitForFrontend(first);
            try (Socket ignoredResponse = sendWithoutReadingResponse(first.endpoint(), body)) {
                waitForCommittedMutation(connection, first);
            }
        }

        try (Frontend restarted = startFrontend(workspace.resolve("restarted"), distribution, descriptor,
                jdbcUrl, username, password)) {
            waitForFrontend(restarted);
            HttpResponse<String> replay = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build()
                    .send(mutationRequest(restarted.endpoint(), body), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, replay.statusCode(), replay.body());
            JsonNode response = JSON.readTree(replay.body());
            assertEquals("Recovered after restart", response.at("/data/renameCustomer/name").asText(),
                    response::toString);
            assertFalse(response.has("errors"), response::toString);
        }

        assertEquals("Recovered after restart", customerName(connection));
        assertEquals(1, mutationStateCount(connection, "titan_graphql_mutation_receipts"));
        assertEquals(1, mutationStateCount(connection, "titan_graphql_mutation_audit"));
    }

    private static HttpRequest mutationRequest(URI endpoint, String body) {
        return HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/graphql-response+json")
                .header("Content-Type", "application/json")
                .header("X-Titan-Actor-Id", "42")
                .header("X-Titan-Actor-Role", "editor")
                .header("X-Titan-Tenant-Id", "tenant-a")
                .header("X-Titan-Request-Id", "restart-request")
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static Socket sendWithoutReadingResponse(URI endpoint, String body) throws IOException {
        Socket socket = new Socket(endpoint.getHost(), endpoint.getPort());
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String headers = "POST /graphql HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\n"
                    + "Accept: application/graphql-response+json\r\n"
                    + "Content-Type: application/json\r\n"
                    + "X-Titan-Actor-Id: 42\r\n"
                    + "X-Titan-Actor-Role: editor\r\n"
                    + "X-Titan-Tenant-Id: tenant-a\r\n"
                    + "X-Titan-Request-Id: restart-request\r\n"
                    + "Idempotency-Key: " + IDEMPOTENCY_KEY + "\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
            socket.shutdownOutput();
            return socket;
        } catch (IOException failure) {
            try {
                socket.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static void waitForCommittedMutation(Connection connection, Frontend frontend) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if ("Recovered after restart".equals(customerName(connection))
                    && mutationStateCount(connection, "titan_graphql_mutation_receipts") == 1
                    && mutationStateCount(connection, "titan_graphql_mutation_audit") == 1) {
                return;
            }
            if (frontend.process().isAlive() == false) {
                fail("frontend exited before committing the mutation: " + frontend.output());
            }
            Thread.sleep(100L);
        }
        fail("mutation did not commit after the client discarded its HTTP response: " + frontend.output());
    }

    private static String customerName(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name FROM commerce.customers WHERE id = 7");
             ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static long mutationStateCount(Connection connection, String table) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM public." + table + " WHERE idempotency_key = ?")) {
            statement.setString(1, IDEMPOTENCY_KEY);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
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
        extract(distribution, home);
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

    private static void extract(Path zip, Path targetDirectory) throws Exception {
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path target = targetDirectory.resolve(entry.getName()).normalize();
                if (target.startsWith(targetDirectory) == false) {
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

        private String output() throws IOException {
            return Files.exists(outputFile) ? Files.readString(outputFile) : "no frontend log";
        }
    }
}
