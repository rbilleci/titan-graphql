package io.titan.graphql.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.ServerSocket;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Launches the packaged standalone HTTP distribution. Its Gradle task does not put the application
 * main output on the test classpath, and the child process receives only the distribution's lib
 * directory; a passing request therefore proves the physical serving host does not need a JVM
 * GraphQL implementation.
 */
class DatabaseGraphqlHttpFrontendIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REVIEW_REGISTRY_ID = "registry-model-proof-development";
    private static final String APPROVED_DOCUMENT =
            "query Approved { modelDraft(id: \"admin-http-proof\") { id } }";
    private static final String REJECTED_DOCUMENT =
            "query Rejected { modelDraft(id: \"admin-http-proof\") { status } }";
    private static final String PREVIEW_REGISTRY_ID = "registry-preview-http-proof";
    private static final String PREVIEW_DOCUMENT =
            "{ modelDraft(id: \"admin-http-proof\") { id workspaceId status } }";

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

    @Test
    @Tag("database-engine-management-http")
    void standalonePostgreSqlDistributionServesAuthenticatedManagementPackage() throws Exception {
        Path applicationDescriptor = Path.of(System.getProperty("titan.graphql.database-frontend.descriptor"));
        Path managementDescriptor = Path.of(System.getProperty("titan.graphql.management-frontend.descriptor"));
        Path managementMigrations = Path.of(System.getProperty("titan.graphql.management.migrations.dir"));
        Path distribution = Path.of(System.getProperty("titan.graphql.database-frontend.distribution"));
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                deployEngine(connection);
                try (Statement statement = connection.createStatement()) {
                    statement.execute(managementDraftTableSql(false));
                }
                execute(connection, managementMigrations.resolve("R__titan_010_runtime.sql"));
                execute(connection, managementMigrations.resolve("R__titan_020_routines.sql"));
                seedObservedOperation(connection);
            }
            try (StandaloneFrontend frontend = startFrontend(distribution, applicationDescriptor,
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), managementDescriptor)) {
                assertManagementFrontend(frontend, managementDescriptor);
                assertSealedPreviewRejectsRegistryChange(
                        frontend, postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            }
        }
    }

    @Test
    @Tag("database-engine-management-http")
    void standaloneMySqlDistributionServesAuthenticatedManagementPackage() throws Exception {
        Path applicationDescriptor = Path.of(System.getProperty("titan.graphql.database-frontend.mysql.descriptor"));
        Path managementDescriptor = Path.of(System.getProperty("titan.graphql.management-frontend.mysql.descriptor"));
        Path managementMigrations = Path.of(System.getProperty("titan.graphql.management.mysql.migrations.dir"));
        Path distribution = Path.of(System.getProperty("titan.graphql.database-frontend.distribution"));
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
                .withCommand("--max_allowed_packet=16M")
                .withUsername("root")
                .withPassword("test")) {
            mysql.start();
            try (Connection connection = DriverManager.getConnection(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                deployMySqlEngine(connection);
                for (String sql : splitMySqlStatements(managementDraftTableSql(true))) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                }
                try (Statement statement = connection.createStatement()) {
                    statement.execute("USE public");
                }
                executeMySqlScript(connection, managementMigrations.resolve("R__titan_010_runtime.sql"));
                executeMySqlScript(connection, managementMigrations.resolve("R__titan_020_routines.sql"));
                seedObservedOperation(connection);
            }
            try (StandaloneFrontend frontend = startFrontend(distribution, applicationDescriptor,
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(), managementDescriptor)) {
                assertManagementFrontend(frontend, managementDescriptor);
                assertSealedPreviewRejectsRegistryChange(
                        frontend, mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            }
        }
    }

    @Test
    @Tag("database-engine-management-http")
    void postgresqlRegistryDecisionSurvivesFrontendRestart(@TempDir Path workspace) throws Exception {
        Path applicationDescriptor = Path.of(System.getProperty("titan.graphql.database-frontend.descriptor"));
        Path managementDescriptor = Path.of(System.getProperty("titan.graphql.management-frontend.descriptor"));
        Path managementMigrations = Path.of(System.getProperty("titan.graphql.management.migrations.dir"));
        Path distribution = Path.of(System.getProperty("titan.graphql.database-frontend.distribution"));
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                deployEngine(connection);
                try (Statement statement = connection.createStatement()) {
                    statement.execute(managementDraftTableSql(false));
                }
                execute(connection, managementMigrations.resolve("R__titan_010_runtime.sql"));
                execute(connection, managementMigrations.resolve("R__titan_020_routines.sql"));
                seedServingRegistry(connection);
            }
            verifyRegistryAcrossFrontendRestart(workspace, distribution, applicationDescriptor,
                    managementDescriptor, postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
    }

    @Test
    @Tag("database-engine-management-http")
    void mysqlRegistryDecisionSurvivesFrontendRestart(@TempDir Path workspace) throws Exception {
        Path applicationDescriptor = Path.of(System.getProperty("titan.graphql.database-frontend.mysql.descriptor"));
        Path managementDescriptor = Path.of(System.getProperty("titan.graphql.management-frontend.mysql.descriptor"));
        Path managementMigrations = Path.of(System.getProperty("titan.graphql.management.mysql.migrations.dir"));
        Path distribution = Path.of(System.getProperty("titan.graphql.database-frontend.distribution"));
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
                .withCommand("--max_allowed_packet=16M")
                .withUsername("root")
                .withPassword("test")) {
            mysql.start();
            try (Connection connection = DriverManager.getConnection(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                deployMySqlEngine(connection);
                for (String sql : splitMySqlStatements(managementDraftTableSql(true))) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                }
                try (Statement statement = connection.createStatement()) {
                    statement.execute("USE public");
                }
                executeMySqlScript(connection, managementMigrations.resolve("R__titan_010_runtime.sql"));
                executeMySqlScript(connection, managementMigrations.resolve("R__titan_020_routines.sql"));
                seedServingRegistry(connection);
            }
            verifyRegistryAcrossFrontendRestart(workspace, distribution, applicationDescriptor,
                    managementDescriptor, mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        }
    }

    @Test
    @Tag("database-engine-management-http")
    void postgresqlReviewedDecisionSurvivesWorkerAndFrontendRestarts(@TempDir Path workspace) throws Exception {
        Path applicationDescriptor = Path.of(System.getProperty("titan.graphql.database-frontend.descriptor"));
        Path managementDescriptor = Path.of(System.getProperty("titan.graphql.management-frontend.descriptor"));
        Path managementMigrations = Path.of(System.getProperty("titan.graphql.management.migrations.dir"));
        Path distribution = Path.of(System.getProperty("titan.graphql.database-frontend.distribution"));
        Path workerHome = workspace.resolve("review-worker");
        extract(Path.of(System.getProperty("titan.graphql.control-job.worker.distribution")), workerHome);
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                deployEngine(connection);
            }
            assertEquals("READY", runWorkerCommand(workspace.resolve("install-management.log"), workerHome,
                    postgres.getUsername(), postgres.getPassword(), "install-management", "postgresql",
                    postgres.getJdbcUrl()));
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                seedFullManagementDraft(connection);
                execute(connection, managementMigrations.resolve("R__titan_010_runtime.sql"));
                execute(connection, managementMigrations.resolve("R__titan_020_routines.sql"));
                seedReviewObservation(connection, "observed-approved-proof", "Approved", APPROVED_DOCUMENT);
                seedReviewObservation(connection, "observed-rejected-proof", "Rejected", REJECTED_DOCUMENT);
            }
            verifyReviewedDecisionAcrossRestarts(workspace, distribution, applicationDescriptor,
                    managementDescriptor, "postgresql", postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
        }
    }

    @Test
    @Tag("database-engine-management-http")
    void mysqlReviewedDecisionSurvivesWorkerAndFrontendRestarts(@TempDir Path workspace) throws Exception {
        Path applicationDescriptor = Path.of(System.getProperty("titan.graphql.database-frontend.mysql.descriptor"));
        Path managementDescriptor = Path.of(System.getProperty("titan.graphql.management-frontend.mysql.descriptor"));
        Path managementMigrations = Path.of(System.getProperty("titan.graphql.management.mysql.migrations.dir"));
        Path distribution = Path.of(System.getProperty("titan.graphql.database-frontend.distribution"));
        Path workerHome = workspace.resolve("review-worker");
        extract(Path.of(System.getProperty("titan.graphql.control-job.worker.distribution")), workerHome);
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
                .withCommand("--max_allowed_packet=16M")
                .withUsername("root")
                .withPassword("test")) {
            mysql.start();
            try (Connection connection = DriverManager.getConnection(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                deployMySqlEngine(connection);
            }
            assertEquals("READY", runWorkerCommand(workspace.resolve("install-management.log"), workerHome,
                    mysql.getUsername(), mysql.getPassword(), "install-management", "mysql", mysql.getJdbcUrl()));
            try (Connection connection = DriverManager.getConnection(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                seedFullManagementDraft(connection);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("USE public");
                }
                executeMySqlScript(connection, managementMigrations.resolve("R__titan_010_runtime.sql"));
                executeMySqlScript(connection, managementMigrations.resolve("R__titan_020_routines.sql"));
                seedReviewObservation(connection, "observed-approved-proof", "Approved", APPROVED_DOCUMENT);
                seedReviewObservation(connection, "observed-rejected-proof", "Rejected", REJECTED_DOCUMENT);
            }
            verifyReviewedDecisionAcrossRestarts(workspace, distribution, applicationDescriptor,
                    managementDescriptor, "mysql", mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        }
    }

    private static void verifyReviewedDecisionAcrossRestarts(
            Path workspace,
            Path distribution,
            Path applicationDescriptor,
            Path managementDescriptor,
            String dialect,
            String jdbcUrl,
            String username,
            String password
    ) throws Exception {
        Path boundDescriptor = workspace.resolve("reviewed-management.properties");
        Files.writeString(boundDescriptor, Files.readString(managementDescriptor)
                + "\noperation-registry-id=" + REVIEW_REGISTRY_ID + "\n");
        String approvedJobId = "00000000-0000-0000-0000-000000000021";
        String rejectedJobId = "00000000-0000-0000-0000-000000000022";
        try (StandaloneFrontend frontend = startFrontend(distribution, applicationDescriptor,
                jdbcUrl, username, password, managementDescriptor)) {
            JsonNode approvedRequest = postAdmin(frontend, reviewMutation(
                    approvedJobId, "observed-approved-proof", "approve"), "review-approved-request");
            assertEquals(approvedJobId, approvedRequest.at("/data/requestObservedOperationReview/id").asText(),
                    approvedRequest::toString);
            JsonNode rejectedRequest = postAdmin(frontend, reviewMutation(
                    rejectedJobId, "observed-rejected-proof", "reject"), "review-rejected-request");
            assertEquals(rejectedJobId, rejectedRequest.at("/data/requestObservedOperationReview/id").asText(),
                    rejectedRequest::toString);
            assertEquals("pending", postAdmin(frontend, controlJobQuery(approvedJobId), "read-pending-review")
                    .at("/data/controlJob/status").asText());
        }

        Path workerHome = workspace.resolve("review-worker");
        runReviewWorker(workspace.resolve("approved-worker.log"), workerHome, dialect,
                jdbcUrl, username, password);
        runReviewWorker(workspace.resolve("rejected-worker.log"), workerHome, dialect,
                jdbcUrl, username, password);

        try (StandaloneFrontend frontend = startFrontend(distribution, applicationDescriptor,
                jdbcUrl, username, password, managementDescriptor)) {
            JsonNode approvedJob = postAdmin(frontend, controlJobQuery(approvedJobId), "read-approved-review");
            assertEquals("succeeded", approvedJob.at("/data/controlJob/status").asText(), approvedJob::toString);
            JsonNode rejectedJob = postAdmin(frontend, controlJobQuery(rejectedJobId), "read-rejected-review");
            assertEquals("succeeded", rejectedJob.at("/data/controlJob/status").asText(), rejectedJob::toString);
            JsonNode registry = postAdmin(frontend, "{ operationRegistry(id: \"" + REVIEW_REGISTRY_ID
                    + "\") { id mode operationsJson } }", "read-reviewed-registry");
            assertEquals(REVIEW_REGISTRY_ID, registry.at("/data/operationRegistry/id").asText(), registry::toString);
            String operations = registry.at("/data/operationRegistry/operationsJson").asText();
            assertTrue(operations.contains("APPROVED"), registry::toString);
            assertTrue(operations.contains("REJECTED"), registry::toString);
        }

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                PreparedStatement update = connection.prepareStatement(
                        "UPDATE management.graphql_operation_registries SET mode = ? WHERE id = ?")) {
            update.setString(1, "ENFORCE");
            update.setString(2, REVIEW_REGISTRY_ID);
            assertEquals(1, update.executeUpdate());
        }
        try (StandaloneFrontend frontend = startFrontend(distribution, applicationDescriptor,
                jdbcUrl, username, password, boundDescriptor)) {
            JsonNode approved = postAdmin(frontend, APPROVED_DOCUMENT, "Approved", "serve-approved-review");
            assertEquals("admin-http-proof", approved.at("/data/modelDraft/id").asText(), approved::toString);
            JsonNode rejected = postAdmin(frontend, REJECTED_DOCUMENT, "Rejected", "serve-rejected-review");
            assertEquals("OPERATION_REGISTRY_REJECTED", rejected.at("/errors/0/extensions/code").asText(),
                    rejected::toString);
            assertEquals("REJECTED", rejected.at("/errors/0/extensions/operationRegistry/status").asText(),
                    rejected::toString);
        }
    }

    private static String reviewMutation(String jobId, String observedId, String decision) {
        return "mutation { requestObservedOperationReview(id: \"" + jobId
                + "\", observedOperationId: \"" + observedId + "\", decision: \""
                + decision + "\") { id observedOperationId decision } }";
    }

    private static String controlJobQuery(String jobId) {
        return "{ controlJob(id: \"" + jobId + "\") { id status resultJson failureCode } }";
    }

    private static JsonNode postAdmin(StandaloneFrontend frontend, String query, String requestId) throws Exception {
        return postAdmin(frontend, query, "", requestId);
    }

    private static JsonNode postAdmin(
            StandaloneFrontend frontend,
            String query,
            String operationName,
            String requestId
    ) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        waitForServer(client, frontend);
        URI admin = URI.create(frontend.endpointUri().toString().replace("/graphql", "/admin/graphql"));
        String body = JSON.writeValueAsString(Map.of(
                "query", query, "operationName", operationName, "extensions", Map.of("client", "portal")));
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(admin)
                .header("Accept", DatabaseGraphqlHttpServer.GRAPHQL_RESPONSE_JSON)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer management-http-proof-token")
                .header("X-Titan-Management-Request-Id", requestId)
                .header("X-Titan-Management-Idempotency-Key", requestId)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static void runReviewWorker(
            Path output,
            Path workerHome,
            String dialect,
            String jdbcUrl,
            String username,
            String password
    ) throws Exception {
        assertEquals("true", runWorkerCommand(output, workerHome, username, password,
                "run-one-review", dialect, jdbcUrl, "jdbc", "60"));
    }

    private static String runWorkerCommand(
            Path output,
            Path workerHome,
            String username,
            String password,
            String... arguments
    ) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("sh");
        command.add(workerHome.resolve("bin/titan-graphql-control").toString());
        command.addAll(List.of(arguments));
        ProcessBuilder launcher = new ProcessBuilder(command);
        launcher.redirectErrorStream(true);
        launcher.redirectOutput(output.toFile());
        launcher.environment().put("TITAN_GRAPHQL_CONTROL_DB_USER", username);
        launcher.environment().put("TITAN_GRAPHQL_CONTROL_DB_PASSWORD", password);
        Process worker = launcher.start();
        if (!worker.waitFor(45, TimeUnit.SECONDS)) {
            worker.destroyForcibly();
            throw new AssertionError("review worker timed out: " + Files.readString(output));
        }
        assertEquals(0, worker.exitValue(), Files.readString(output));
        return Files.readString(output).trim().lines().reduce((first, last) -> last).orElse("");
    }

    private static void seedFullManagementDraft(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO management.management_drafts "
                    + "(id, workspace_id, model_id, version, status, document, document_hash, "
                    + "created_at, updated_at, metadata) VALUES "
                    + "('admin-http-proof', 'workspace-proof', 'model-proof', 1, 'validated', '{}', "
                    + "'document-hash', '2026-09-26T00:00:00Z', '2026-09-26T00:00:00Z', '{}')");
        }
    }

    private static void seedReviewObservation(
            Connection connection,
            String id,
            String operationName,
            String document
    ) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(document.getBytes(StandardCharsets.UTF_8)));
        String observedAt = "2026-09-26T00:00:00Z";
        String operationJson = JSON.writeValueAsString(Map.ofEntries(
                Map.entry("id", id), Map.entry("modelId", "model-proof"),
                Map.entry("environment", "development"), Map.entry("role", "operator"),
                Map.entry("client", "portal"), Map.entry("operationHash", hash),
                Map.entry("operationName", operationName), Map.entry("document", document),
                Map.entry("status", "OBSERVED"), Map.entry("depth", 2),
                Map.entry("estimatedCost", 3), Map.entry("fieldUsage", List.of("ManagedDraft.id")),
                Map.entry("firstSeenAt", observedAt), Map.entry("lastSeenAt", observedAt),
                Map.entry("observedCount", 1)));
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO management.graphql_observed_operations "
                        + "(id, model_id, environment, role, client, operation_hash, operation_name, "
                        + "document, status, depth, estimated_cost, field_usage_json, first_seen_at, "
                        + "last_seen_at, observed_count, operation_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, id);
            insert.setString(2, "model-proof");
            insert.setString(3, "development");
            insert.setString(4, "operator");
            insert.setString(5, "portal");
            insert.setString(6, hash);
            insert.setString(7, operationName);
            insert.setString(8, document);
            insert.setString(9, "OBSERVED");
            insert.setInt(10, 2);
            insert.setInt(11, 3);
            insert.setString(12, "[\"ManagedDraft.id\"]");
            insert.setString(13, observedAt);
            insert.setString(14, observedAt);
            insert.setInt(15, 1);
            insert.setString(16, operationJson);
            assertEquals(1, insert.executeUpdate());
        }
        try (PreparedStatement journal = connection.prepareStatement(
                "INSERT INTO management.graphql_product_state (entry_json) VALUES (?)")) {
            journal.setString(1, JSON.writeValueAsString(Map.of(
                    "type", "observedOperation", "value", JSON.readTree(operationJson))));
            assertEquals(1, journal.executeUpdate());
        }
    }

    private static void verifyRegistryAcrossFrontendRestart(
            Path workspace,
            Path distribution,
            Path applicationDescriptor,
            Path managementDescriptor,
            String jdbcUrl,
            String username,
            String password
    ) throws Exception {
        Path boundDescriptor = workspace.resolve("management-registry.properties");
        Files.writeString(boundDescriptor, Files.readString(managementDescriptor)
                + "\noperation-registry-id=registry-http-restart\n");
        try (StandaloneFrontend frontend = startFrontend(distribution, applicationDescriptor,
                jdbcUrl, username, password, boundDescriptor)) {
            assertServingRegistryResponse(frontend, "OPERATION_REGISTRY_WARNING");
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                PreparedStatement update = connection.prepareStatement(
                        "UPDATE management.graphql_operation_registries SET mode = ? WHERE id = ?")) {
            update.setString(1, "ENFORCE");
            update.setString(2, "registry-http-restart");
            assertEquals(1, update.executeUpdate());
        }
        try (StandaloneFrontend frontend = startFrontend(distribution, applicationDescriptor,
                jdbcUrl, username, password, boundDescriptor)) {
            assertServingRegistryResponse(frontend, "OPERATION_REGISTRY_REJECTED");
        }
    }

    private static void seedServingRegistry(Connection connection) throws Exception {
        String document = "query Probe { __typename }";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(document.getBytes(StandardCharsets.UTF_8)));
        try (PreparedStatement header = connection.prepareStatement(
                "INSERT INTO management.graphql_operation_registries "
                        + "(id, model_id, environment, mode, operations_json, updated_at, registry_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)");
                PreparedStatement operation = connection.prepareStatement(
                        "INSERT INTO management.graphql_registry_operations "
                                + "(registry_id, operation_id, operation_position, operation_hash, document_hash, "
                                + "operation_name, document, status, roles_json, clients_json) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            header.setString(1, "registry-http-restart");
            header.setString(2, "model-proof");
            header.setString(3, "development");
            header.setString(4, "WARN");
            header.setString(5, "[]");
            header.setString(6, "2026-09-26T00:00:00Z");
            header.setString(7, "{}");
            assertEquals(1, header.executeUpdate());
            operation.setString(1, "registry-http-restart");
            operation.setString(2, "registered-probe");
            operation.setInt(3, 0);
            operation.setString(4, hash);
            operation.setString(5, hash);
            operation.setString(6, "Probe");
            operation.setString(7, document);
            operation.setString(8, "REJECTED");
            operation.setString(9, "[\"operator\"]");
            operation.setString(10, "[]");
            assertEquals(1, operation.executeUpdate());
        }
    }

    private static void assertServingRegistryResponse(StandaloneFrontend frontend, String code) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        waitForServer(client, frontend);
        URI admin = URI.create(frontend.endpointUri().toString().replace("/graphql", "/admin/graphql"));
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(admin)
                .header("Accept", DatabaseGraphqlHttpServer.GRAPHQL_RESPONSE_JSON)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer management-http-proof-token")
                .header("X-Titan-Actor-Role", "reader")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"query\":\"query Probe { __typename }\",\"operationName\":\"Probe\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"code\":\"" + code + "\""), response.body());
    }

    private static String managementDraftTableSql(boolean mysql) {
        String schema = mysql ? "CREATE DATABASE IF NOT EXISTS management;" : "CREATE SCHEMA IF NOT EXISTS management;";
        return schema + "\n"
                + "CREATE TABLE management.management_drafts (id VARCHAR(128) PRIMARY KEY, "
                + "workspace_id VARCHAR(128) NOT NULL, model_id VARCHAR(128) NOT NULL, "
                + "version INTEGER NOT NULL, status VARCHAR(32) NOT NULL, "
                + "document_hash VARCHAR(64) NOT NULL, updated_at VARCHAR(40) NOT NULL);\n"
                + "INSERT INTO management.management_drafts "
                + "(id, workspace_id, model_id, version, status, document_hash, updated_at) "
                + "VALUES ('admin-http-proof', 'workspace-proof', 'model-proof', 1, 'validated', "
                + "'document-hash', '2026-09-24T00:00:00Z');\n"
                + "INSERT INTO public.titan_graphql_control_jobs "
                + "(job_id, job_type, request_key, payload_json, payload_sha256, status, attempt_count) "
                + "VALUES ('00000000-0000-0000-0000-000000000002', 'artifact.generate', "
                + "'management-http-job-proof', '{}', '" + "0".repeat(64) + "', 'pending', 0);";
    }

    private static void seedObservedOperation(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO management.graphql_observed_operations "
                    + "(id, model_id, environment, role, client, operation_hash, operation_name, "
                    + "document, status, depth, estimated_cost, field_usage_json, first_seen_at, "
                    + "last_seen_at, observed_count, operation_json) VALUES "
                    + "('observed-http-proof', 'model-proof', 'development', 'reader', 'portal', "
                    + "'hash-proof', 'ReadDraft', 'query ReadDraft { modelDraft(id: \"admin-http-proof\") "
                    + "{ id } }', 'OBSERVED', 2, 3, '[]', '2026-09-26T00:00:00Z', "
                    + "'2026-09-26T00:00:00Z', 1, '{}')");
        }
    }

    private static void seedPreviewRegistry(Connection connection) throws Exception {
        try (PreparedStatement lookup = connection.prepareStatement(
                "SELECT id FROM management.graphql_operation_registries WHERE id = ?")) {
            lookup.setString(1, PREVIEW_REGISTRY_ID);
            try (java.sql.ResultSet rows = lookup.executeQuery()) {
                if (rows.next()) {
                    return;
                }
            }
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(PREVIEW_DOCUMENT.getBytes(StandardCharsets.UTF_8)));
        try (PreparedStatement header = connection.prepareStatement(
                "INSERT INTO management.graphql_operation_registries "
                        + "(id, model_id, environment, mode, operations_json, updated_at, registry_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)");
                PreparedStatement operation = connection.prepareStatement(
                        "INSERT INTO management.graphql_registry_operations "
                                + "(registry_id, operation_id, operation_position, operation_hash, document_hash, "
                                + "operation_name, document, status, roles_json, clients_json) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            header.setString(1, PREVIEW_REGISTRY_ID);
            header.setString(2, "model-proof");
            header.setString(3, "preview");
            header.setString(4, "ENFORCE");
            header.setString(5, "[]");
            header.setString(6, Instant.now().toString());
            header.setString(7, "{}");
            assertEquals(1, header.executeUpdate());
            operation.setString(1, PREVIEW_REGISTRY_ID);
            operation.setString(2, "registered-preview-read");
            operation.setInt(3, 0);
            operation.setString(4, hash);
            operation.setString(5, hash);
            operation.setString(6, "");
            operation.setString(7, PREVIEW_DOCUMENT);
            operation.setString(8, "APPROVED");
            operation.setString(9, "[\"operator\"]");
            operation.setString(10, "[]");
            assertEquals(1, operation.executeUpdate());
        }
    }

    private static void assertSealedPreviewRejectsRegistryChange(
            StandaloneFrontend frontend, String jdbcUrl, String username, String password
    ) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                PreparedStatement change = connection.prepareStatement(
                        "UPDATE management.graphql_operation_registries SET mode = 'WARN' WHERE id = ?")) {
            change.setString(1, PREVIEW_REGISTRY_ID);
            assertEquals(1, change.executeUpdate());
        }
        URI preview = URI.create(frontend.endpointUri().toString()
                .replace("/graphql", "/preview/preview-proof/graphql"));
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(preview)
                        .header("Content-Type", "application/json")
                        .header("X-Titan-Actor-Role", "operator")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                JSON.writeValueAsString(Map.of("query", PREVIEW_DOCUMENT))))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(503, response.statusCode(), response.body());
    }

    private static void assertManagementFrontend(StandaloneFrontend frontend, Path managementDescriptor) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        waitForServer(client, frontend);
        URI admin = URI.create(frontend.endpointUri().toString().replace("/graphql", "/admin/graphql"));
        String request = JSON.writeValueAsString(Map.of("query", PREVIEW_DOCUMENT));
        HttpRequest.Builder base = HttpRequest.newBuilder(admin)
                .header("Accept", DatabaseGraphqlHttpServer.GRAPHQL_RESPONSE_JSON)
                .header("Content-Type", "application/json");
        HttpResponse<String> unauthenticated = client.send(base.copy()
                        .POST(HttpRequest.BodyPublishers.ofString(request)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauthenticated.statusCode(), unauthenticated.body());
        HttpResponse<String> wrongToken = client.send(base.copy()
                        .header("Authorization", "Bearer wrong-token")
                        .POST(HttpRequest.BodyPublishers.ofString(request)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, wrongToken.statusCode(), wrongToken.body());
        HttpResponse<String> authorized = client.send(base.copy()
                        .header("Authorization", "Bearer management-http-proof-token")
                        .header("X-Titan-Actor-Role", "reader")
                        .POST(HttpRequest.BodyPublishers.ofString(request)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, authorized.statusCode(), authorized.body());
        assertTrue(authorized.body().contains("\"modelDraft\":{\"id\":\"admin-http-proof\""),
                authorized.body());
        assertTrue(authorized.body().contains("\"workspaceId\":\"workspace-proof\""),
                authorized.body());
        String getQuery = URLEncoder.encode("{ modelDraft(id: \"admin-http-proof\") { id } }",
                StandardCharsets.UTF_8).replace("+", "%20");
        HttpResponse<String> authorizedGet = client.send(HttpRequest.newBuilder(
                        URI.create(admin + "?query=" + getQuery))
                        .header("Authorization", "Bearer management-http-proof-token")
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, authorizedGet.statusCode(), authorizedGet.body());
        assertTrue(authorizedGet.body().contains("\"id\":\"admin-http-proof\""),
                authorizedGet.body());
        HttpResponse<String> controlJob = client.send(base.copy()
                        .header("Authorization", "Bearer management-http-proof-token")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"{ controlJob(id: "
                                + "\\\"00000000-0000-0000-0000-000000000002\\\") { id type status } }\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, controlJob.statusCode(), controlJob.body());
        assertTrue(controlJob.body().contains("\"type\":\"artifact.generate\""), controlJob.body());
        assertTrue(controlJob.body().contains("\"status\":\"pending\""), controlJob.body());
        String requestedJobId = "00000000-0000-0000-0000-000000000006";
        String artifactRequest = "{\"query\":\"mutation { requestArtifactGeneration(id: "
                + "\\\"" + requestedJobId + "\\\", draftId: \\\"admin-http-proof\\\", "
                + "generationProfile: \\\"development\\\", enableIntrospection: false) { id draftId } }\"}";
        HttpRequest artifactMutation = base.copy()
                .header("Authorization", "Bearer management-http-proof-token")
                .header("X-Titan-Management-Idempotency-Key", "management-http-artifact-request")
                .POST(HttpRequest.BodyPublishers.ofString(artifactRequest)).build();
        HttpResponse<String> artifactResponse = client.send(artifactMutation, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, artifactResponse.statusCode(), artifactResponse.body());
        assertTrue(artifactResponse.body().contains("\"id\":\"" + requestedJobId + "\""),
                artifactResponse.body());
        assertEquals(artifactResponse.body(), client.send(artifactMutation,
                HttpResponse.BodyHandlers.ofString()).body());
        HttpResponse<String> queuedJob = client.send(base.copy()
                        .header("Authorization", "Bearer management-http-proof-token")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"{ controlJob(id: "
                                + "\\\"" + requestedJobId + "\\\") { id status } }\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, queuedJob.statusCode(), queuedJob.body());
        assertTrue(queuedJob.body().contains("\"status\":\"pending\""), queuedJob.body());
        String validationJobId = "00000000-0000-0000-0000-00000000000a";
        HttpResponse<String> validationResponse = client.send(base.copy()
                        .header("Authorization", "Bearer management-http-proof-token")
                        .header("X-Titan-Management-Idempotency-Key", "management-http-validation-request")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"mutation { requestModelValidation(id: "
                                + "\\\"" + validationJobId
                                + "\\\", draftId: \\\"admin-http-proof\\\") { id draftId } }\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, validationResponse.statusCode(), validationResponse.body());
        assertTrue(validationResponse.body().contains("\"id\":\"" + validationJobId + "\""),
                validationResponse.body());
        String importJobId = "00000000-0000-0000-0000-00000000000b";
        String importDocument = "mutation Import($yaml: String!) { requestModelImport(id: \""
                + importJobId + "\", workspaceId: \"workspace-proof\", yaml: $yaml) { id workspaceId } }";
        String importBody = new ObjectMapper().writeValueAsString(Map.of(
                "query", importDocument,
                "operationName", "Import",
                "variables", Map.of("yaml", Files.readString(Path.of(
                        "src/test/resources/graphql/renamed-vault.titan.graphql.yaml")))));
        HttpRequest importMutation = base.copy()
                .header("Authorization", "Bearer management-http-proof-token")
                .header("X-Titan-Actor-Role", "reader")
                .header("X-Titan-Management-Request-Id", "management-http-import-request")
                .header("X-Titan-Management-Idempotency-Key", "management-http-import-key")
                .POST(HttpRequest.BodyPublishers.ofString(importBody)).build();
        HttpResponse<String> importResponse = client.send(importMutation, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, importResponse.statusCode(), importResponse.body());
        assertTrue(importResponse.body().contains("\"id\":\"" + importJobId + "\""),
                importResponse.body());
        assertEquals(importResponse.body(), client.send(importMutation, HttpResponse.BodyHandlers.ofString()).body());
        HttpResponse<String> importJob = client.send(base.copy()
                        .header("Authorization", "Bearer management-http-proof-token")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"{ controlJob(id: "
                                + "\\\"" + importJobId + "\\\") { type status } }\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, importJob.statusCode(), importJob.body());
        assertTrue(importJob.body().contains("\"type\":\"model.import\""), importJob.body());
        assertTrue(importJob.body().contains("\"status\":\"pending\""), importJob.body());
        String reviewJobId = "00000000-0000-0000-0000-00000000000c";
        HttpRequest reviewMutation = base.copy()
                .header("Authorization", "Bearer management-http-proof-token")
                .header("X-Titan-Actor-Role", "reader")
                .header("X-Titan-Management-Request-Id", "management-http-review-request")
                .header("X-Titan-Management-Idempotency-Key", "management-http-review-key")
                .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"mutation { "
                        + "requestObservedOperationReview(id: \\\"" + reviewJobId
                        + "\\\", observedOperationId: \\\"observed-http-proof\\\", "
                        + "decision: \\\"approve\\\") { id observedOperationId decision } }\"}"))
                .build();
        HttpResponse<String> reviewResponse = client.send(reviewMutation, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, reviewResponse.statusCode(), reviewResponse.body());
        assertTrue(reviewResponse.body().contains("\"id\":\"" + reviewJobId + "\""), reviewResponse.body());
        assertEquals(reviewResponse.body(), client.send(reviewMutation, HttpResponse.BodyHandlers.ofString()).body());
        HttpResponse<String> reviewJob = client.send(base.copy()
                        .header("Authorization", "Bearer management-http-proof-token")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"{ controlJob(id: "
                                + "\\\"" + reviewJobId + "\\\") { type status } }\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, reviewJob.statusCode(), reviewJob.body());
        assertTrue(reviewJob.body().contains("\"type\":\"operation.review\""), reviewJob.body());
        assertTrue(reviewJob.body().contains("\"status\":\"pending\""), reviewJob.body());
        URI preview = URI.create(frontend.endpointUri().toString()
                .replace("/graphql", "/preview/preview-proof/graphql"));
        HttpResponse<String> previewAuthorized = client.send(HttpRequest.newBuilder(preview)
                        .header("Content-Type", "application/json")
                        .header("X-Titan-Actor-Role", "operator")
                        .POST(HttpRequest.BodyPublishers.ofString(request)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, previewAuthorized.statusCode(), previewAuthorized.body());
        assertEquals(deploymentFingerprint(managementDescriptor), previewAuthorized.headers()
                .firstValue(DatabaseGraphqlHttpServer.DEPLOYMENT_FINGERPRINT_HEADER).orElse(""));
        assertTrue(previewAuthorized.body().contains("\"modelDraft\":{\"id\":\"admin-http-proof\""),
                previewAuthorized.body());
        HttpResponse<String> previewDenied = client.send(HttpRequest.newBuilder(preview)
                        .header("Content-Type", "application/json")
                        .header("X-Titan-Actor-Role", "reader")
                        .POST(HttpRequest.BodyPublishers.ofString(request)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, previewDenied.statusCode(), previewDenied.body());
        assertTrue(previewDenied.body().contains("\"errors\""), previewDenied.body());
        HttpResponse<String> unregisteredPreview = client.send(HttpRequest.newBuilder(URI.create(
                        frontend.endpointUri().toString().replace("/graphql", "/preview/unknown/graphql")))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(request)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, unregisteredPreview.statusCode(), unregisteredPreview.body());
        HttpResponse<String> previewSuffix = client.send(HttpRequest.newBuilder(URI.create(preview + "/extra"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(request)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, previewSuffix.statusCode(), previewSuffix.body());
        HttpResponse<String> application = client.send(HttpRequest.newBuilder(frontend.endpointUri())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"query\":\"{ article(id: 1) { id title } }\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, application.statusCode(), application.body());
        assertTrue(application.body().contains("Titan GraphQL proof"), application.body());
    }

    private static void assertFrontendServesRequest(StandaloneFrontend frontend, String expectedFingerprint)
            throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        waitForServer(client, frontend);

        HttpResponse<String> disabledAdmin = client.send(HttpRequest.newBuilder(
                        URI.create(frontend.endpointUri().toString().replace("/graphql", "/admin/graphql")))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, disabledAdmin.statusCode(), disabledAdmin.body());

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
        assertTrue(unknownRoot.body().contains("field 'Query.doesNotExist' is not defined"),
                unknownRoot.body());
        assertTrue(unknownRoot.body().contains("\"code\":\"VALIDATION_ERROR\""),
                unknownRoot.body());

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
        return startFrontend(distribution, descriptor, jdbcUrl, username, password, null);
    }

    private static StandaloneFrontend startFrontend(
            Path distribution,
            Path descriptor,
            String jdbcUrl,
            String username,
            String password,
            Path managementDescriptor
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
        if (managementDescriptor != null) {
            launcher.environment().put("TITAN_GRAPHQL_ADMIN_FRONTEND_DESCRIPTOR",
                    managementDescriptor.toAbsolutePath().toString());
            launcher.environment().put("TITAN_GRAPHQL_ADMIN_ACCESS_TOKEN", "management-http-proof-token");
            launcher.environment().put("TITAN_GRAPHQL_ADMIN_ROLE", "operator");
            launcher.environment().put("TITAN_GRAPHQL_ADMIN_ACTOR_KEY", "management-http-proof-actor");
            Path previewRegistry = home.resolve("preview-packages.properties");
            Path previewDescriptor = home.resolve("preview-proof.properties");
            Properties previewProperties = new Properties();
            try (java.io.Reader reader = Files.newBufferedReader(managementDescriptor, StandardCharsets.UTF_8)) {
                previewProperties.load(reader);
            }
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
                seedPreviewRegistry(connection);
                DatabaseWholeRequestClient.Dialect previewDialect =
                        DatabaseWholeRequestClient.Dialect.valueOf(
                                previewProperties.getProperty("database-dialect").toUpperCase(Locale.ROOT));
                DatabaseWholeRequestClient.EntryPoint previewEntryPoint =
                        new DatabaseWholeRequestClient.EntryPoint(
                                previewProperties.getProperty("entry-point-schema"),
                                previewProperties.getProperty("entry-point-routine"));
                String snapshot = DatabasePreviewDeploymentAttestation.capture(
                        connection, previewDialect, previewEntryPoint, PREVIEW_REGISTRY_ID,
                        previewProperties.getProperty("package-identity-sha256"),
                        DatabaseWholeRequestClient.DEFAULT_STATEMENT_TIMEOUT_SECONDS);
                previewProperties.setProperty("preview-build-id", "preview-proof");
                previewProperties.setProperty("preview-expires-at", Instant.now().plusSeconds(3600).toString());
                previewProperties.setProperty("operation-registry-id", PREVIEW_REGISTRY_ID);
                previewProperties.setProperty("preview-deployment-sha256", snapshot);
            }
            try (java.io.Writer writer = Files.newBufferedWriter(previewDescriptor, StandardCharsets.UTF_8)) {
                previewProperties.store(writer, null);
            }
            Properties previews = new Properties();
            previews.setProperty("preview-proof", previewDescriptor.toAbsolutePath().toString());
            try (java.io.Writer writer = Files.newBufferedWriter(previewRegistry, StandardCharsets.UTF_8)) {
                previews.store(writer, null);
            }
            launcher.environment().put("TITAN_GRAPHQL_PREVIEW_FRONTEND_REGISTRY",
                    previewRegistry.toAbsolutePath().toString());
        }
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
