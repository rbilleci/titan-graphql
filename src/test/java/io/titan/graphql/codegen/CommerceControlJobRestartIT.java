package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.titan.graphql.artifact.TitanGraphqlArtifactKind;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.graphql.artifact.TitanGraphqlGeneratedArtifact;
import io.titan.graphql.controlplane.TitanGraphqlArtifactGenerationService;
import io.titan.graphql.controlplane.TitanGraphqlArtifactGenerationJobRunner;
import io.titan.graphql.controlplane.TitanGraphqlArtifactJobCli;
import io.titan.graphql.controlplane.TitanGraphqlControlJobQueue;
import io.titan.graphql.controlplane.TitanGraphqlModelImportJobRunner;
import io.titan.graphql.management.TitanGraphqlArtifactSetRef;
import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import io.titan.graphql.management.TitanGraphqlManagedModel;
import io.titan.graphql.management.TitanGraphqlManagedWorkspace;
import io.titan.graphql.management.TitanGraphqlModelDraft;
import io.titan.graphql.management.TitanGraphqlValidationReportRef;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import io.titan.management.JdbcTransactionalMutationStore;
import io.titan.runtime.jdbc.SingleConnectionDataSource;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("docker")
@Tag("database-engine-control-job-restart")
final class CommerceControlJobRestartIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DRAFT_ID = "draft-control-job-restart";
    private static final String REQUEST_KEY = "control-job-restart";

    @Test
    void postgresqlJobSurvivesCommandProcessRestart(@TempDir Path workspace) throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
                verify(workspace, "postgresql", connection,
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            }
        }
    }

    @Test
    void mysqlJobSurvivesCommandProcessRestart(@TempDir Path workspace) throws Exception {
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
                verify(workspace, "mysql", connection,
                        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            }
        }
    }

    private static void verify(
            Path workspace,
            String dialect,
            Connection connection,
            String jdbcUrl,
            String username,
            String password
    ) throws Exception {
        Path log = workspace.resolve("management.log");
        seedManagementStore(log);
        JsonNode requested = JSON.readTree(command(workspace.resolve("request.log"), username, password,
                "request", dialect, jdbcUrl, REQUEST_KEY, DRAFT_ID, "development", "true"));
        String jobId = requested.path("id").asText();
        assertFalse(jobId.isBlank());
        assertTrue(requested.path("created").asBoolean());
        assertEquals("PENDING", requested.path("status").asText());

        JsonNode pending = JSON.readTree(command(workspace.resolve("pending.log"), username, password,
                "status", dialect, jdbcUrl, jobId));
        assertEquals("PENDING", pending.path("status").asText());

        assertEquals("true", command(workspace.resolve("run.log"), username, password,
                "run-one", dialect, jdbcUrl, log.toString(), "60"));
        JsonNode succeeded = JSON.readTree(command(workspace.resolve("succeeded.log"), username, password,
                "status", dialect, jdbcUrl, jobId));
        assertEquals("SUCCEEDED", succeeded.path("status").asText());
        assertEquals(1, succeeded.path("attempt").asInt());
        assertEquals("artifact-" + DRAFT_ID,
                JSON.readTree(succeeded.path("resultJson").asText()).path("artifactSetId").asText());

        TitanGraphqlDurableManagementStore reloaded = new TitanGraphqlDurableManagementStore(log);
        assertNotNull(reloaded.artifactSet("artifact-" + DRAFT_ID));
        assertEquals("artifact-" + DRAFT_ID, reloaded.draft(DRAFT_ID).artifactSetId());

        JsonNode replay = JSON.readTree(command(workspace.resolve("replay.log"), username, password,
                "request", dialect, jdbcUrl, REQUEST_KEY, DRAFT_ID, "development", "true"));
        assertEquals(jobId, replay.path("id").asText());
        assertFalse(replay.path("created").asBoolean());
        assertEquals("SUCCEEDED", replay.path("status").asText());
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT COUNT(*) FROM public.titan_graphql_control_jobs")) {
            assertTrue(rows.next());
            assertEquals(1L, rows.getLong(1));
        }

        JsonNode interrupted = JSON.readTree(command(workspace.resolve("interrupted-request.log"),
                username, password, "request", dialect, jdbcUrl,
                "control-job-after-write", DRAFT_ID, "development", "true"));
        String interruptedId = interrupted.path("id").asText();
        TitanGraphqlControlJobQueue queue = new TitanGraphqlControlJobQueue(
                new SingleConnectionDataSource(connection),
                TitanGraphqlControlJobQueue.Dialect.valueOf(dialect.toUpperCase(java.util.Locale.ROOT)),
                Clock.systemUTC());
        TitanGraphqlControlJobQueue.ClaimedJob abandoned = queue.claimNext(
                "artifact.generate", Duration.ofSeconds(1)).orElseThrow();
        assertEquals(interruptedId, abandoned.id());
        new TitanGraphqlArtifactGenerationService(new TitanGraphqlDurableManagementStore(log))
                .generate(DRAFT_ID, "development", true);
        Thread.sleep(1_200L);
        assertEquals("true", command(workspace.resolve("recovered-run.log"), username, password,
                "run-one", dialect, jdbcUrl, log.toString(), "60"));
        JsonNode recovered = JSON.readTree(command(workspace.resolve("recovered-status.log"),
                username, password, "status", dialect, jdbcUrl, interruptedId));
        assertEquals("SUCCEEDED", recovered.path("status").asText());
        assertEquals(2, recovered.path("attempt").asInt());
        assertEquals("artifact-" + DRAFT_ID,
                new TitanGraphqlDurableManagementStore(log).draft(DRAFT_ID).artifactSetId());
        verifyFileJournalPublicationFailure(workspace, connection, queue);

        Path workerHome = workspace.resolve("worker");
        extractWorker(Path.of(System.getProperty("titan.graphql.control-job.worker.distribution")), workerHome);
        packagedCommandRejects(workspace.resolve("file-worker-rejected.log"), workerHome,
                username, password, "requires JDBC management state",
                "check-worker-ready", dialect, jdbcUrl, log.toString());
        packagedCommandRejects(workspace.resolve("file-worker-command-rejected.log"), workerHome,
                username, password, "requires JDBC management state",
                "run-worker", dialect, jdbcUrl, log.toString(), "60", "100", "1");
        JsonNode late = JSON.readTree(command(workspace.resolve("late-request.log"),
                username, password, "request", dialect, jdbcUrl,
                "control-job-late", DRAFT_ID, "development", "true"));
        assertEquals("true", packagedCommand(workspace.resolve("file-packaged-run-one.log"), workerHome,
                username, password, "run-one", dialect, jdbcUrl, log.toString(), "60"));
        JsonNode completedLate = JSON.readTree(command(workspace.resolve("late-status.log"),
                username, password, "status", dialect, jdbcUrl, late.path("id").asText()));
        assertEquals("SUCCEEDED", completedLate.path("status").asText());
        verifyJdbcManagementWorker(workspace, workerHome, dialect, connection, jdbcUrl, username, password);
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE public.titan_graphql_control_jobs DROP COLUMN failure_code");
        }
        packagedCommandRejects(workspace.resolve("partial-worker-ready.log"), workerHome,
                username, password, "failure_code", "check-worker-ready", dialect, jdbcUrl, "jdbc");
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE public.titan_graphql_control_jobs "
                    + "ADD COLUMN failure_code VARCHAR(64)");
            statement.execute(dialect.equals("postgresql")
                    ? "DROP INDEX public.titan_graphql_control_jobs_ready"
                    : "ALTER TABLE public.titan_graphql_control_jobs "
                            + "DROP INDEX titan_graphql_control_jobs_ready");
        }
        packagedCommandRejects(workspace.resolve("missing-ready-index-worker-ready.log"), workerHome,
                username, password, "status, lease_until, created_at, job_id ready index",
                "check-worker-ready", dialect, jdbcUrl, "jdbc");
        try (Statement statement = connection.createStatement()) {
            statement.execute(dialect.equals("postgresql")
                    ? "CREATE INDEX titan_graphql_control_jobs_ready "
                            + "ON public.titan_graphql_control_jobs "
                            + "(status, lease_until, created_at, job_id)"
                    : "ALTER TABLE public.titan_graphql_control_jobs "
                            + "ADD INDEX titan_graphql_control_jobs_ready "
                            + "(status, lease_until, created_at, job_id)");
            statement.execute(dialect.equals("postgresql")
                    ? "ALTER TABLE public.titan_graphql_control_jobs "
                            + "DROP CONSTRAINT titan_graphql_control_jobs_status"
                    : "ALTER TABLE public.titan_graphql_control_jobs "
                            + "DROP CHECK titan_graphql_control_jobs_status");
        }
        packagedCommandRejects(workspace.resolve("missing-status-check-worker-ready.log"), workerHome,
                username, password, "titan_graphql_control_jobs_status check constraint",
                "check-worker-ready", dialect, jdbcUrl, "jdbc");
        try (Statement statement = connection.createStatement()) {
            statement.execute(dialect.equals("postgresql")
                    ? "ALTER TABLE public.titan_graphql_control_jobs "
                            + "DROP CONSTRAINT titan_graphql_control_jobs_request"
                    : "ALTER TABLE public.titan_graphql_control_jobs "
                            + "DROP INDEX titan_graphql_control_jobs_request");
        }
        packagedCommandRejects(workspace.resolve("missing-request-unique-worker-ready.log"), workerHome,
                username, password, "unique job_type and request_key",
                "check-worker-ready", dialect, jdbcUrl, "jdbc");
        packagedCommandRejects(workspace.resolve("missing-request-unique-enqueue.log"), workerHome,
                username, password, "unique job_type and request_key",
                "request", dialect, jdbcUrl, "missing-unique", DRAFT_ID, "development", "true");
        try (Statement statement = connection.createStatement()) {
            statement.execute(dialect.equals("postgresql")
                    ? "ALTER TABLE public.titan_graphql_control_jobs "
                            + "DROP CONSTRAINT titan_graphql_control_jobs_pkey"
                    : "ALTER TABLE public.titan_graphql_control_jobs DROP PRIMARY KEY");
        }
        packagedCommandRejects(workspace.resolve("missing-primary-key-worker-ready.log"), workerHome,
                username, password, "job_id primary key", "check-worker-ready", dialect, jdbcUrl, "jdbc");
    }

    private static void verifyFileJournalPublicationFailure(
            Path workspace,
            Connection connection,
            TitanGraphqlControlJobQueue queue
    ) throws Exception {
        Path log = workspace.resolve("cross-file-management.log");
        seedManagementStore(log);
        TitanGraphqlDurableManagementStore store = new TitanGraphqlDurableManagementStore(log);
        TitanGraphqlArtifactGenerationJobRunner runner = new TitanGraphqlArtifactGenerationJobRunner(
                queue, new TitanGraphqlArtifactGenerationService(store));
        TitanGraphqlControlJobQueue.JobReference requested;
        connection.setAutoCommit(false);
        try {
            requested = runner.request(connection, "cross-file-publication", DRAFT_ID, "development", true);
            connection.commit();
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }

        long coreDraftEntriesBefore = Files.readAllLines(log).stream()
                .filter(line -> line.startsWith("DRAFT\t")).count();
        Path productJournal = log.resolveSibling("cross-file-management.log.graphql-state.jsonl");
        Path retained = workspace.resolve("cross-file-retained-product-state.jsonl");
        Files.move(productJournal, retained);
        Files.createDirectory(productJournal);
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> runner.runOne(Duration.ofMinutes(1)));
            assertTrue(failure.getMessage().contains("product state journal"), failure::getMessage);
        } finally {
            Files.delete(productJournal);
            Files.move(retained, productJournal);
        }
        assertEquals(TitanGraphqlControlJobQueue.Status.PENDING,
                queue.find(requested.id()).orElseThrow().status());
        TitanGraphqlDurableManagementStore afterFailure = new TitanGraphqlDurableManagementStore(log);
        assertTrue(afterFailure.artifactSet("artifact-" + DRAFT_ID) == null);
        assertEquals(coreDraftEntriesBefore + 1L, Files.readAllLines(log).stream()
                .filter(line -> line.startsWith("DRAFT\t")).count());

        TitanGraphqlArtifactGenerationJobRunner restarted = new TitanGraphqlArtifactGenerationJobRunner(
                queue, () -> new TitanGraphqlArtifactGenerationService(
                        new TitanGraphqlDurableManagementStore(log)));
        assertTrue(restarted.runOne(Duration.ofMinutes(1)));
        TitanGraphqlControlJobQueue.JobState completed = queue.find(requested.id()).orElseThrow();
        assertEquals(TitanGraphqlControlJobQueue.Status.SUCCEEDED, completed.status());
        assertEquals(2, completed.attempt());
        TitanGraphqlDurableManagementStore recovered = new TitanGraphqlDurableManagementStore(log);
        assertEquals("artifact-" + DRAFT_ID, recovered.draft(DRAFT_ID).artifactSetId());
        assertEquals("artifact-" + DRAFT_ID, recovered.artifactSet("artifact-" + DRAFT_ID).id());
        assertEquals(1L, Files.readAllLines(productJournal).stream()
                .filter(line -> line.contains("\"type\":\"artifactGeneration\"")).count());
    }

    private static void verifyJdbcManagementWorker(
            Path workspace,
            Path workerHome,
            String dialect,
            Connection connection,
            String jdbcUrl,
            String username,
            String password
    ) throws Exception {
        assertEquals("READY", packagedCommand(workspace.resolve("install-management.log"), workerHome,
                username, password, "install-management", dialect, jdbcUrl));
        assertEquals("READY", packagedCommand(workspace.resolve("reinstall-management.log"), workerHome,
                username, password, "install-management", dialect, jdbcUrl));
        try (Statement statement = connection.createStatement()) {
            statement.execute(dialect.equals("postgresql")
                    ? "SET search_path TO management" : "USE management");
            statement.execute("DROP PROCEDURE management.seed_draft");
        }
        assertEquals("READY", packagedCommand(workspace.resolve("repair-management.log"), workerHome,
                username, password, "repair-management", dialect, jdbcUrl));
        assertEquals("READY", packagedCommand(workspace.resolve("jdbc-worker-ready.log"), workerHome,
                username, password, "check-worker-ready", dialect, jdbcUrl, "jdbc"));
        var managementDataSource = new SingleConnectionDataSource(connection);
        seedManagementStore(new TitanGraphqlDurableManagementStore(
                new JdbcTransactionalMutationStore(managementDataSource), managementDataSource));

        JsonNode requested = JSON.readTree(command(workspace.resolve("jdbc-request.log"), username, password,
                "request", dialect, jdbcUrl, "control-job-jdbc", DRAFT_ID, "development", "true"));
        String jobId = requested.path("id").asText();
        assertEquals("true", command(workspace.resolve("jdbc-run.log"), username, password,
                "run-one", dialect, jdbcUrl, "jdbc", "60"));
        JsonNode completed = JSON.readTree(command(workspace.resolve("jdbc-status.log"), username, password,
                "status", dialect, jdbcUrl, jobId));
        assertEquals("SUCCEEDED", completed.path("status").asText());
        TitanGraphqlDurableManagementStore reloaded = new TitanGraphqlDurableManagementStore(
                new JdbcTransactionalMutationStore(managementDataSource), managementDataSource);
        assertEquals("artifact-" + DRAFT_ID, reloaded.draft(DRAFT_ID).artifactSetId());
        assertNotNull(reloaded.artifactSet("artifact-" + DRAFT_ID));
        verifyPackagedArtifactJobApi(workspace, workerHome, dialect, jdbcUrl, username, password);

        Path workerOutput = workspace.resolve("jdbc-worker.log");
        ProcessBuilder workerLauncher = new ProcessBuilder(
                "sh", workerHome.resolve("bin/titan-graphql-control-worker").toString(),
                dialect, jdbcUrl, "jdbc", "60", "100", "3");
        workerLauncher.redirectErrorStream(true);
        workerLauncher.redirectOutput(workerOutput.toFile());
        workerLauncher.environment().put("TITAN_GRAPHQL_CONTROL_DB_USER", username);
        workerLauncher.environment().put("TITAN_GRAPHQL_CONTROL_DB_PASSWORD", password);
        Process worker = workerLauncher.start();
        try {
            Thread.sleep(500L);
            assertTrue(worker.isAlive(), Files.readString(workerOutput));
            JsonNode late = JSON.readTree(command(workspace.resolve("jdbc-late-request.log"), username, password,
                    "request", dialect, jdbcUrl, "control-job-jdbc-late", DRAFT_ID, "development", "true"));
            TitanGraphqlControlJobQueue queue = new TitanGraphqlControlJobQueue(managementDataSource,
                    TitanGraphqlControlJobQueue.Dialect.valueOf(dialect.toUpperCase(java.util.Locale.ROOT)),
                    Clock.systemUTC());
            TitanGraphqlControlJobQueue.JobReference validation;
            TitanGraphqlControlJobQueue.JobReference imported;
            String importSource = Files.readString(Path.of(
                    "src/test/resources/graphql/renamed-vault.titan.graphql.yaml"));
            connection.setAutoCommit(false);
            try {
                validation = queue.enqueue(connection, "model.validate", "control-job-jdbc-validation",
                        "{\"draftId\":\"" + DRAFT_ID + "\"}");
                imported = queue.enqueue(connection, TitanGraphqlModelImportJobRunner.JOB_TYPE,
                        "control-job-jdbc-import", JSON.writeValueAsString(Map.of(
                                "workspaceId", "workspace-control-job",
                                "yaml", importSource,
                                "actorRole", "operator",
                                "actorKey", "worker-operator",
                                "requestId", "control-job-import-request",
                                "idempotencyKey", "control-job-import-key")));
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            assertTrue(worker.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS),
                    Files.readString(workerOutput));
            assertEquals(0, worker.exitValue(), Files.readString(workerOutput));
            JsonNode completedLate = JSON.readTree(command(workspace.resolve("jdbc-late-status.log"), username,
                    password, "status", dialect, jdbcUrl, late.path("id").asText()));
            assertEquals("SUCCEEDED", completedLate.path("status").asText());
            JsonNode completedValidation = JSON.readTree(command(workspace.resolve("jdbc-validation-status.log"),
                    username, password, "status", dialect, jdbcUrl, validation.id()));
            assertEquals("SUCCEEDED", completedValidation.path("status").asText());
            assertEquals("true", JSON.readTree(completedValidation.path("resultJson").asText())
                    .path("accepted").asText());
            JsonNode completedImport = JSON.readTree(command(workspace.resolve("jdbc-import-status.log"),
                    username, password, "status", dialect, jdbcUrl, imported.id()));
            assertEquals("SUCCEEDED", completedImport.path("status").asText());
            String importedDraftId = JSON.readTree(completedImport.path("resultJson").asText())
                    .path("draftId").asText();
            TitanGraphqlDurableManagementStore afterWorker = new TitanGraphqlDurableManagementStore(
                    new JdbcTransactionalMutationStore(managementDataSource), managementDataSource);
            assertEquals("artifact-" + DRAFT_ID, afterWorker.draft(DRAFT_ID).artifactSetId());
            assertEquals(importSource, afterWorker.draft(importedDraftId).sourceText());
        } finally {
            worker.destroyForcibly();
            worker.waitFor(5, TimeUnit.SECONDS);
        }
        verifyAtomicJdbcJobCompletion(connection, dialect, managementDataSource);
    }

    private static void verifyPackagedArtifactJobApi(
            Path workspace,
            Path workerHome,
            String dialect,
            String jdbcUrl,
            String username,
            String password
    ) throws Exception {
        String token = "artifact-job-api-test-token";
        Path output = workspace.resolve("artifact-job-api.log");
        ProcessBuilder launcher = new ProcessBuilder("sh",
                workerHome.resolve("bin/titan-graphql-control").toString(),
                "serve-api", dialect, jdbcUrl, "0");
        launcher.redirectErrorStream(true);
        launcher.redirectOutput(output.toFile());
        launcher.environment().put("TITAN_GRAPHQL_CONTROL_DB_USER", username);
        launcher.environment().put("TITAN_GRAPHQL_CONTROL_DB_PASSWORD", password);
        launcher.environment().put("TITAN_GRAPHQL_CONTROL_API_TOKEN", token);
        launcher.environment().put("TITAN_GRAPHQL_CONTROL_API_HOST", "127.0.0.1");
        Process api = launcher.start();
        try {
            String ready = "";
            for (int attempt = 0; attempt < 300; attempt++) {
                if (Files.isRegularFile(output)) {
                    ready = Files.readString(output).lines()
                            .filter(line -> line.startsWith("READY ")).findFirst().orElse("");
                }
                if (!ready.isEmpty() || !api.isAlive()) {
                    break;
                }
                Thread.sleep(100L);
            }
            assertTrue(ready.startsWith("READY "), Files.readString(output));
            URI endpoint = URI.create(ready.substring("READY ".length()));
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> unauthorized = client.send(HttpRequest.newBuilder(endpoint).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode());

            HttpResponse<String> duplicate = client.send(HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"draftId\":\"first\",\"draftId\":\"second\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, duplicate.statusCode(), duplicate.body());

            String body = JSON.writeValueAsString(java.util.Map.of(
                    "requestKey", "control-job-http-api", "draftId", DRAFT_ID,
                    "generationProfile", "development", "enableIntrospection", true));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> created = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(202, created.statusCode(), created.body());
            String jobId = JSON.readTree(created.body()).path("id").asText();
            assertEquals("true", JSON.readTree(created.body()).path("created").asText());
            HttpResponse<String> replay = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, replay.statusCode(), replay.body());
            assertEquals(jobId, JSON.readTree(replay.body()).path("id").asText());
            assertEquals("false", JSON.readTree(replay.body()).path("created").asText());

            String conflicting = body.replace("development", "production");
            HttpResponse<String> conflict = client.send(HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(conflicting)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(409, conflict.statusCode(), conflict.body());

            URI statusUri = URI.create(endpoint + "/" + jobId);
            HttpRequest status = HttpRequest.newBuilder(statusUri)
                    .header("Authorization", "Bearer " + token).GET().build();
            HttpResponse<String> pending = client.send(status, HttpResponse.BodyHandlers.ofString());
            assertEquals("PENDING", JSON.readTree(pending.body()).path("status").asText());
            assertEquals("true", command(workspace.resolve("http-api-run.log"), username, password,
                    "run-one", dialect, jdbcUrl, "jdbc", "60"));
            HttpResponse<String> completed = client.send(status, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, completed.statusCode(), completed.body());
            assertEquals("SUCCEEDED", JSON.readTree(completed.body()).path("status").asText());
            assertEquals("artifact-" + DRAFT_ID,
                    JSON.readTree(completed.body()).at("/result/artifactSetId").asText());
        } finally {
            api.destroyForcibly();
            api.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static void verifyAtomicJdbcJobCompletion(
            Connection connection,
            String dialect,
            SingleConnectionDataSource managementDataSource
    ) throws Exception {
        TitanGraphqlControlJobQueue queue = new TitanGraphqlControlJobQueue(
                managementDataSource,
                dialect.equals("postgresql")
                        ? TitanGraphqlControlJobQueue.Dialect.POSTGRESQL
                        : TitanGraphqlControlJobQueue.Dialect.MYSQL,
                Clock.systemUTC());
        connection.setAutoCommit(false);
        TitanGraphqlControlJobQueue.JobReference requested;
        try {
            requested = queue.enqueue(connection, "artifact.generate", "atomic-control-job",
                    "{\"draftId\":\"draft-atomic-control-job\"}");
            connection.commit();
        } finally {
            connection.setAutoCommit(true);
        }
        TitanGraphqlControlJobQueue.ClaimedJob claimed = queue.claimNext(Duration.ofMinutes(5)).orElseThrow();
        String hash = "0".repeat(64);
        String draftId = "draft-atomic-control-job";
        String artifactId = "artifact-atomic-control-job";
        TitanGraphqlArtifactSetRef artifact = new TitanGraphqlArtifactSetRef(
                artifactId, draftId, hash, "", "", "", "", "", hash,
                "development", "2026-06-09T00:02:00Z");
        TitanGraphqlModelDraft draft = new TitanGraphqlModelDraft(
                draftId, "model-control-job", TitanGraphqlModelDraft.ModelDraftStatus.READY_FOR_REVIEW,
                TitanGraphqlModelDraft.SourceFormat.YAML, "source", "{}", hash,
                "", artifactId, "", "operator", "2026-06-09T00:00:00Z", "2026-06-09T00:02:00Z");
        TitanGraphqlGap005ArtifactMetadata metadata = new TitanGraphqlGap005ArtifactMetadata(
                "build/atomic-job", artifactId, "migration", "0.1.0", List.of(dialect),
                hash, hash, hash, hash, "passed", List.of(), List.of(), List.of(),
                List.of(
                        TitanGraphqlArtifactKind.TITAN_ARTIFACT_METADATA,
                        TitanGraphqlArtifactKind.TITAN_OBJECT_INVENTORY,
                        TitanGraphqlArtifactKind.TITAN_INSTALL_PLAN,
                        TitanGraphqlArtifactKind.TITAN_INSTALL_VERIFICATION
                ).stream().map(kind -> new TitanGraphqlGeneratedArtifact(
                        kind, kind.defaultFileName(), "{}", hash)).toList());
        TitanGraphqlDurableManagementStore store = new TitanGraphqlDurableManagementStore(
                new JdbcTransactionalMutationStore(managementDataSource), managementDataSource);
        String result = "{\"artifactSetId\":\"" + artifactId + "\"}";
        renameProductStateTable(connection, dialect, true);
        try {
            assertThrows(IllegalStateException.class, () -> store.saveGeneratedArtifactsAndCompleteJob(
                    artifact, draft, metadata, queue, claimed, result));
        } finally {
            renameProductStateTable(connection, dialect, false);
        }
        assertEquals(TitanGraphqlControlJobQueue.Status.RUNNING,
                queue.find(requested.id()).orElseThrow().status());
        TitanGraphqlDurableManagementStore afterFailure = new TitanGraphqlDurableManagementStore(
                new JdbcTransactionalMutationStore(managementDataSource), managementDataSource);
        assertFalse(afterFailure.titanDrafts().stream().anyMatch(entry -> entry.id().equals(draftId)));
        assertFalse(afterFailure.titanArtifactRefs().stream().anyMatch(entry -> entry.id().equals(artifactId)));
        assertTrue(afterFailure.artifactSet(artifactId) == null);

        assertTrue(afterFailure.saveGeneratedArtifactsAndCompleteJob(
                artifact, draft, metadata, queue, claimed, result));
        assertEquals(TitanGraphqlControlJobQueue.Status.SUCCEEDED,
                queue.find(requested.id()).orElseThrow().status());
        TitanGraphqlDurableManagementStore recovered = new TitanGraphqlDurableManagementStore(
                new JdbcTransactionalMutationStore(managementDataSource), managementDataSource);
        assertEquals(artifactId, recovered.draft(draftId).artifactSetId());
        assertEquals(artifactId, recovered.artifactSet(artifactId).id());
        assertFalse(recovered.saveGeneratedArtifactsAndCompleteJob(
                artifact, draft, metadata, queue, claimed, result));
    }

    private static void renameProductStateTable(Connection connection, String dialect, boolean away)
            throws SQLException {
        String original = "management.graphql_product_state";
        String held = "management.graphql_product_state_held";
        String sql = dialect.equals("postgresql")
                ? "ALTER TABLE " + (away ? original : held)
                        + " RENAME TO " + (away ? "graphql_product_state_held" : "graphql_product_state")
                : "RENAME TABLE " + (away ? original : held) + " TO " + (away ? held : original);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void extractWorker(Path archive, Path directory) throws Exception {
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path target = directory.resolve(entry.getName()).normalize();
                if (!target.startsWith(directory)) {
                    throw new IllegalStateException("worker distribution contains an unsafe entry");
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

    private static void seedManagementStore(Path log) throws Exception {
        seedManagementStore(new TitanGraphqlDurableManagementStore(log));
    }

    private static void seedManagementStore(TitanGraphqlDurableManagementStore store) throws Exception {
        String yaml;
        try (InputStream source = CommerceControlJobRestartIT.class
                .getResourceAsStream("/graphql/management.titan.graphql.yaml")) {
            yaml = new String(java.util.Objects.requireNonNull(source).readAllBytes(), StandardCharsets.UTF_8);
        }
        TitanGraphqlModelDocument model = TitanGraphqlModelDocumentYaml.parse(yaml);
        String validationId = "validation-" + DRAFT_ID;
        store.saveWorkspace(new TitanGraphqlManagedWorkspace(
                "workspace-control-job", "platform", "", "development", "", ""));
        store.saveModel(new TitanGraphqlManagedModel(
                "model-control-job", "workspace-control-job", model.metadata().name(),
                "Management", "", DRAFT_ID, "", validationId, "", "", ""));
        store.saveDraft(new TitanGraphqlModelDraft(
                DRAFT_ID, "model-control-job", TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED,
                TitanGraphqlModelDraft.SourceFormat.YAML, yaml,
                TitanGraphqlModelDocumentJson.canonicalJson(model),
                TitanGraphqlModelDocumentJson.semanticHash(model),
                validationId, "", "", "operator", "", ""));
        store.saveValidationReport(new TitanGraphqlValidationReportRef(
                validationId, DRAFT_ID, TitanGraphqlValidationReportRef.ValidationReportStatus.PASS,
                "validation passed", 0, 0, 0, false, ""));
    }

    private static String command(
            Path output,
            String username,
            String password,
            String... args
    ) throws Exception {
        String classpath = System.getProperty("titan.graphql.control-job.classpath");
        assertNotNull(classpath);
        String[] command = new String[5 + args.length];
        command[0] = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        command[1] = "-Djava.util.logging.manager=java.util.logging.LogManager";
        command[2] = "-cp";
        command[3] = classpath;
        command[4] = TitanGraphqlArtifactJobCli.class.getName();
        System.arraycopy(args, 0, command, 5, args.length);
        ProcessBuilder launcher = new ProcessBuilder(command);
        launcher.directory(Path.of("").toAbsolutePath().toFile());
        launcher.redirectErrorStream(true);
        launcher.redirectOutput(output.toFile());
        launcher.environment().put("TITAN_GRAPHQL_CONTROL_DB_USER", username);
        launcher.environment().put("TITAN_GRAPHQL_CONTROL_DB_PASSWORD", password);
        Process process = launcher.start();
        if (!process.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("control-plane command timed out: " + Files.readString(output));
        }
        String result = Files.readString(output);
        assertEquals(0, process.exitValue(), result);
        return result.lines().filter(line -> !line.isBlank())
                .reduce((previous, current) -> current)
                .orElse("");
    }

    private static String packagedCommand(
            Path output,
            Path workerHome,
            String username,
            String password,
            String... args
    ) throws Exception {
        String[] command = new String[2 + args.length];
        command[0] = "sh";
        command[1] = workerHome.resolve("bin/titan-graphql-control").toString();
        System.arraycopy(args, 0, command, 2, args.length);
        ProcessBuilder launcher = new ProcessBuilder(command);
        launcher.redirectErrorStream(true);
        launcher.redirectOutput(output.toFile());
        launcher.environment().put("TITAN_GRAPHQL_CONTROL_DB_USER", username);
        launcher.environment().put("TITAN_GRAPHQL_CONTROL_DB_PASSWORD", password);
        Process process = launcher.start();
        if (!process.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("management install command timed out: " + Files.readString(output));
        }
        String result = Files.readString(output);
        assertEquals(0, process.exitValue(), result);
        return result.lines().filter(line -> !line.isBlank())
                .reduce((previous, current) -> current)
                .orElse("");
    }

    private static void packagedCommandRejects(
            Path output,
            Path workerHome,
            String username,
            String password,
            String expectedMessage,
            String... args
    ) throws Exception {
        String[] command = new String[2 + args.length];
        command[0] = "sh";
        command[1] = workerHome.resolve("bin/titan-graphql-control").toString();
        System.arraycopy(args, 0, command, 2, args.length);
        ProcessBuilder launcher = new ProcessBuilder(command);
        launcher.redirectErrorStream(true);
        launcher.redirectOutput(output.toFile());
        launcher.environment().put("TITAN_GRAPHQL_CONTROL_DB_USER", username);
        launcher.environment().put("TITAN_GRAPHQL_CONTROL_DB_PASSWORD", password);
        Process process = launcher.start();
        if (!process.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("worker readiness command timed out: " + Files.readString(output));
        }
        String result = Files.readString(output);
        assertNotEquals(0, process.exitValue(), result);
        assertTrue(result.contains(expectedMessage), result);
    }
}
