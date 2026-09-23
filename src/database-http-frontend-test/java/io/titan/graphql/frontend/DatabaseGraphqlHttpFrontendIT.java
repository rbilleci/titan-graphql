package io.titan.graphql.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Launches the packaged standalone HTTP distribution. Its Gradle task does not put the application
 * main output on the test classpath, and the child process receives only the distribution's lib
 * directory; a passing request therefore proves the physical serving host does not need a JVM
 * GraphQL implementation.
 */
class DatabaseGraphqlHttpFrontendIT {

    private static final String FIXTURE_SEED_SQL = """
            INSERT INTO users (id, name, email, role) VALUES
              (10, 'Ada Lovelace', 'ada@example.test', 'author'),
              (11, 'Grace Hopper', 'grace@example.test', 'author');
            INSERT INTO articles (id, author_id, title, body, published) VALUES
              (1, 10, 'Titan GraphQL proof', 'First proof body', true),
              (2, 11, 'Stored functions as APIs', 'Second proof body', false);
            INSERT INTO comments (id, article_id, author_id, body) VALUES
              (100, 1, 11, 'First comment'),
              (101, 1, 10, 'Second comment'),
              (102, 2, 10, 'API comment');
            """;

    @Test
    void standalonePostgreSqlDistributionForwardsCompleteRequestsWithoutApplicationClasses()
            throws Exception {
        Path descriptor = Path.of(System.getProperty("titan.graphql.database-frontend.descriptor"));
        Path distribution = Path.of(System.getProperty("titan.graphql.database-frontend.distribution"));

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                deployEngine(connection);
            }
            try (StandaloneFrontend frontend = startFrontend(
                    distribution, descriptor, postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                assertFrontendServesRequest(frontend, deploymentFingerprint(descriptor));
            }
        }
    }

    @Test
    void standaloneMySqlDistributionForwardsCompleteRequestsWithoutApplicationClasses()
            throws Exception {
        Path descriptor = Path.of(System.getProperty("titan.graphql.database-frontend.mysql.descriptor"));
        Path distribution = Path.of(System.getProperty("titan.graphql.database-frontend.distribution"));

        // The generated package creates and serves from the configured public database, so this
        // deployment leg needs the container's administrative account rather than its default
        // per-test-database user.
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
                // The generated whole-request closure is larger than MySQL's legacy 1 MiB
                // packet default. Match the deployment preflight used by the shared Titan
                // integration containers so this test reaches the installed frontend contract.
                .withCommand("--max_allowed_packet=16M")
                .withUsername("root")
                .withPassword("test")) {
            mysql.start();
            try (Connection connection = DriverManager.getConnection(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                deployMySqlEngine(connection);
            }
            try (StandaloneFrontend frontend = startFrontend(
                    distribution, descriptor, mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                assertFrontendServesRequest(frontend, deploymentFingerprint(descriptor));
            }
        }
    }

    private static void assertFrontendServesRequest(StandaloneFrontend frontend, String expectedFingerprint)
            throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        waitForServer(client, frontend);

        HttpResponse<String> post = client.send(HttpRequest.newBuilder(frontend.endpointUri())
                        .header("Accept", DatabaseGraphqlHttpServer.GRAPHQL_RESPONSE_JSON)
                        .header("Content-Type", "application/json")
                        .header("X-Titan-Actor-Id", "10")
                        .header("X-Titan-Actor-Role", "reader")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "query":"query FindArticle($articleId: Int!) { selected: article(id: $articleId) { id title } }",
                                  "operationName":"FindArticle",
                                  "variables":{"articleId":1}
                                }
                                """))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, post.statusCode(), post.body());
        assertEquals("database", post.headers()
                .firstValue(DatabaseGraphqlHttpServer.EXECUTION_MODE_HEADER).orElse(""));
        assertEquals(expectedFingerprint, post.headers()
                .firstValue(DatabaseGraphqlHttpServer.DEPLOYMENT_FINGERPRINT_HEADER).orElse(""));
        assertTrue(post.body().contains("\"selected\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}"),
                post.body());

        // Nullable HTTP envelope members are transport absence, not a reason for the frontend to
        // make a GraphQL input decision. The generated engine receives the original query once
        // with omitted variables/extensions and applies its own default/coercion semantics.
        HttpResponse<String> nullEnvelopeMembers = client.send(HttpRequest.newBuilder(frontend.endpointUri())
                        .header("Accept", DatabaseGraphqlHttpServer.GRAPHQL_RESPONSE_JSON)
                        .header("Content-Type", "application/json")
                        .header("X-Titan-Actor-Id", "10")
                        .header("X-Titan-Actor-Role", "reader")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "query":"{ article(id: 1) { id } }",
                                  "variables":null,
                                  "extensions":null
                                }
                                """))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, nullEnvelopeMembers.statusCode(), nullEnvelopeMembers.body());
        assertTrue(nullEnvelopeMembers.body().contains("\"article\":{\"id\":1}"),
                nullEnvelopeMembers.body());

        // Model-specific policy values travel through the generic trusted context-value channel;
        // the HTTP host neither recognizes the value name nor evaluates the policy. This fixture's
        // published-visibility policy makes article 2 visible only for `false`.
        HttpResponse<String> genericContextValue = client.send(HttpRequest.newBuilder(frontend.endpointUri())
                        .header("Accept", DatabaseGraphqlHttpServer.GRAPHQL_RESPONSE_JSON)
                        .header("Content-Type", "application/json")
                        .header("X-Titan-Actor-Id", "10")
                        .header("X-Titan-Actor-Role", "reader")
                        .header("X-Titan-Context-Filters", "publishedVisibility")
                        .header("X-Titan-Context-Values", "{\"articleVisibility\":false}")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"{ articles(first: 5) { edges { node { id } } } }\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, genericContextValue.statusCode(), genericContextValue.body());
        assertTrue(genericContextValue.body().contains("\"id\":2"), genericContextValue.body());

        // A JSON tree normally overwrites an earlier duplicate object member. The frontend must
        // reject that ambiguous transport before serializing variables for the database routine;
        // choosing either value would be an HTTP-side GraphQL coercion decision.
        HttpResponse<String> duplicateVariables = client.send(HttpRequest.newBuilder(frontend.endpointUri())
                        .header("Accept", DatabaseGraphqlHttpServer.GRAPHQL_RESPONSE_JSON)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "query":"query FindArticle($articleId: Int!) { article(id: $articleId) { id } }",
                                  "operationName":"FindArticle",
                                  "variables":{"articleId":1,"articleId":2}
                                }
                                """))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, duplicateVariables.statusCode(), duplicateVariables.body());
        assertTrue(duplicateVariables.body().contains("request body must be valid JSON"),
                duplicateVariables.body());

        // A complete GraphQL operation stays one request/envelope and one database invocation.
        // The thin host may not split its root fields, merge partial JSON, or accidentally route
        // either field through a local runtime. The generated engine owns both selections and the
        // completed response, including aliases and variables.
        HttpResponse<String> multipleRoots = client.send(HttpRequest.newBuilder(frontend.endpointUri())
                        .header("Accept", DatabaseGraphqlHttpServer.GRAPHQL_RESPONSE_JSON)
                        .header("Content-Type", "application/json")
                        .header("X-Titan-Actor-Id", "10")
                        .header("X-Titan-Actor-Role", "reader")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "query":"query TwoArticles($firstId: Int!, $secondId: Int!) { first: article(id: $firstId) { id title } second: article(id: $secondId) { id title } }",
                                  "operationName":"TwoArticles",
                                  "variables":{"firstId":1,"secondId":2}
                                }
                                """))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, multipleRoots.statusCode(), multipleRoots.body());
        assertTrue(multipleRoots.body().contains("\"first\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}"),
                multipleRoots.body());
        assertTrue(multipleRoots.body().contains("\"second\":{\"id\":2,\"title\":\"Stored functions as APIs\"}"),
                multipleRoots.body());

        // GraphQL semantic errors also come from the generated database engine. The HTTP host
        // merely forwards the completed JSON and never attempts local root validation.
        HttpResponse<String> unknownRoot = client.send(HttpRequest.newBuilder(frontend.endpointUri())
                        .header("Accept", DatabaseGraphqlHttpServer.GRAPHQL_RESPONSE_JSON)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"{ doesNotExist { id } }\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, unknownRoot.statusCode(), unknownRoot.body());
        assertTrue(unknownRoot.body().contains("unknown or unsupported root field"), unknownRoot.body());

        URI getUri = URI.create(frontend.endpointUri()
                + "?query=mutation%20%7B%20unsupportedMutation%20%7B%20id%20%7D%20%7D");
        HttpResponse<String> get = client.send(HttpRequest.newBuilder(getUri)
                        .header("Accept", DatabaseGraphqlHttpServer.GRAPHQL_RESPONSE_JSON)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, get.statusCode(), get.body());
        assertTrue(get.body().contains("not allowed"), get.body());
    }

    private static String deploymentFingerprint(Path descriptor) throws Exception {
        Properties properties = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(descriptor, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties.getProperty("deployment-fingerprint");
    }

    private static StandaloneFrontend startFrontend(
            Path distribution,
            Path descriptor,
            String jdbcUrl,
            String username,
            String password
    ) throws Exception {
        Path home = Files.createTempDirectory("titan-graphql-database-http-");
        extract(distribution, home);
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        ProcessBuilder launcher = new ProcessBuilder(
                "sh", home.resolve("bin/titan-graphql-database-http").toString());
        launcher.directory(home.toFile());
        launcher.redirectErrorStream(true);
        launcher.environment().put("TITAN_GRAPHQL_FRONTEND_DESCRIPTOR", descriptor.toAbsolutePath().toString());
        launcher.environment().put("TITAN_GRAPHQL_JDBC_URL", jdbcUrl);
        launcher.environment().put("TITAN_GRAPHQL_JDBC_USERNAME", username);
        launcher.environment().put("TITAN_GRAPHQL_JDBC_PASSWORD", password);
        launcher.environment().put("TITAN_GRAPHQL_HTTP_PORT", String.valueOf(port));
        launcher.environment().put("TITAN_GRAPHQL_HTTP_TRUST_REQUEST_CONTEXT_HEADERS", "true");
        return new StandaloneFrontend(home, launcher.start(), URI.create("http://127.0.0.1:" + port + "/graphql"));
    }

    private static void waitForServer(HttpClient client, StandaloneFrontend frontend) throws Exception {
        HttpRequest probe = HttpRequest.newBuilder(URI.create(
                        frontend.endpointUri() + "?query=%7Barticle%28id%3A1%29%7Bid%7D%7D"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        String lastResponse = "no HTTP response received";
        for (int attempt = 0; attempt < 50; attempt++) {
            if (frontend.process().isAlive() == false) {
                throw new IllegalStateException("standalone frontend exited before serving HTTP:\n"
                        + frontend.output());
            }
            try {
                HttpResponse<String> response = client.send(probe, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
                lastResponse = "HTTP " + response.statusCode() + ": " + response.body();
            } catch (java.net.ConnectException notReady) {
                // The child process has not bound its HTTP listener yet.
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException("standalone frontend did not become ready: " + lastResponse
                + "\n" + frontend.output());
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

    private record StandaloneFrontend(Path home, Process process, URI endpointUri) implements AutoCloseable {

        @Override
        public void close() throws Exception {
            process.destroy();
            if (process.waitFor(5, TimeUnit.SECONDS) == false) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            try (java.util.stream.Stream<Path> paths = Files.walk(home)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException ignored) {
                        // Test cleanup should not mask the more useful frontend assertion.
                    }
                });
            }
        }

        private String output() throws java.io.IOException {
            if (process.isAlive()) {
                return "frontend process is still running without accepting HTTP";
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void deployEngine(Connection connection) throws Exception {
        execute(connection, Path.of("ddl", "postgres", "titan_graphql_postgres.sql"));
        Path migrationsDirectory = Path.of(System.getProperty("titan.graphql.database-engine.migrations.dir"));
        execute(connection, migrationsDirectory.resolve("R__titan_010_runtime.sql"));
        execute(connection, migrationsDirectory.resolve("R__titan_020_routines.sql"));
        try (Statement statement = connection.createStatement()) {
            statement.execute(FIXTURE_SEED_SQL);
        }
    }

    private static void deployMySqlEngine(Connection connection) throws Exception {
        executeMySqlScript(connection, Path.of("ddl", "mysql", "titan_graphql_mysql.sql"));
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS titan_runtime");
            statement.execute("SET GLOBAL log_bin_trust_function_creators = 1");
            // The package's MySQL runtime helpers are unqualified. Install them in `public`,
            // the generated routine database, rather than the container's default `test`
            // database so the isolated frontend exercises the same closure a real package
            // installation uses.
            statement.execute("USE public");
        }
        Path migrationsDirectory = Path.of(
                System.getProperty("titan.graphql.database-engine.mysql.migrations.dir"));
        executeMySqlScript(connection, migrationsDirectory.resolve("R__titan_010_runtime.sql"));
        executeMySqlScript(connection, migrationsDirectory.resolve("R__titan_020_routines.sql"));
        for (String statementSql : splitMySqlStatements(
                FIXTURE_SEED_SQL.replace("INSERT INTO ", "INSERT INTO public."))) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementSql);
            }
        }
    }

    private static void execute(Connection connection, Path script) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(script, StandardCharsets.UTF_8));
        }
    }

    private static void executeMySqlScript(Connection connection, Path script) throws Exception {
        for (String statementSql : splitMySqlStatements(Files.readString(script, StandardCharsets.UTF_8))) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementSql);
            }
        }
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
                String statementSql = current.substring(0, current.length() - delimiter.length()).trim();
                if (statementSql.isEmpty() == false && commentOnly(statementSql) == false) {
                    statements.add(statementSql);
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

    private static boolean commentOnly(String statementSql) {
        for (String line : statementSql.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() == false && trimmed.startsWith("--") == false) {
                return false;
            }
        }
        return true;
    }
}
