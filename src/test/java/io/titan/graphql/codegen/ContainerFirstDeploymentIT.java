package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import io.titan.graphql.management.TitanGraphqlInMemoryManagementStore;
import io.titan.graphql.management.TitanGraphqlManagementSchemaInstaller;
import io.titan.graphql.management.TitanGraphqlObservedOperation;
import io.titan.graphql.management.TitanGraphqlOperationRegistry;
import io.titan.management.JdbcTransactionalMutationStore;
import io.titan.management.ManagementSchemaInstaller;
import io.titan.runtime.jdbc.SingleConnectionDataSource;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("docker")
@Tag("database-engine-container-deployment")
final class ContainerFirstDeploymentIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IMPORT_JOB_ID = "00000000-0000-0000-0000-00000000d001";
    private static final String VALIDATION_JOB_ID = "00000000-0000-0000-0000-00000000d002";
    private static final String MANAGEMENT_IMPORT_JOB_ID = "00000000-0000-0000-0000-00000000d003";
    private static final String ARTIFACT_JOB_ID = "00000000-0000-0000-0000-00000000d004";
    private static final String REVIEW_JOB_ID = "00000000-0000-0000-0000-00000000d005";
    private static final String ARTIFACT_VALIDATION_JOB_ID = "00000000-0000-0000-0000-00000000d006";

    @Test
    void postgresqlAdminAndWorkerRecoverAfterWorkerProcessExit(@TempDir Path deploymentDirectory)
            throws Exception {
        Path applicationDescriptor = Path.of(System.getProperty("titan.graphql.database-frontend.descriptor"));
        Path managementDescriptor = Path.of(System.getProperty("titan.graphql.management-frontend.descriptor"));
        Path applicationMigrations = Path.of(System.getProperty("titan.graphql.database-engine.migrations.dir"));
        Path managementMigrations = Path.of(System.getProperty("titan.graphql.management.migrations.dir"));
        try (PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16")) {
            database.start();
            try (Connection connection = DriverManager.getConnection(
                    database.getJdbcUrl(), database.getUsername(), database.getPassword())) {
                TitanGraphqlManagementSchemaInstaller.install(connection, ManagementSchemaInstaller.Dialect.POSTGRESQL);
                for (String migration : new String[]{
                        "R__titan_004_graphql_mutation_state.sql",
                        "R__titan_006_graphql_outbox.sql",
                        "R__titan_007_graphql_control_jobs.sql"}) {
                    CommerceDatabaseEngineDeployment.executePostgreSqlScript(connection,
                            Path.of("src/database-engine-migrations/postgresql").resolve(migration));
                }
                CommerceDatabaseEngineDeployment.executePostgreSqlScript(connection,
                        Path.of("ddl/postgres/titan_graphql_postgres.sql"));
                for (String migration : new String[]{"R__titan_010_runtime.sql", "R__titan_020_routines.sql"}) {
                    CommerceDatabaseEngineDeployment.executePostgreSqlScript(connection,
                            applicationMigrations.resolve(migration));
                }
                for (String migration : new String[]{"R__titan_010_runtime.sql", "R__titan_020_routines.sql"}) {
                    CommerceDatabaseEngineDeployment.executePostgreSqlScript(connection,
                            managementMigrations.resolve(migration));
                }
            }
            verify(deploymentDirectory, applicationDescriptor, managementDescriptor, database,
                    applicationMigrations.getParent(), "postgresql", "jdbc:postgresql://m4-database:5432/test");
        }
    }

    @Test
    void mysqlAdminAndWorkerRecoverAfterWorkerProcessExit(@TempDir Path deploymentDirectory)
            throws Exception {
        Path applicationDescriptor = Path.of(System.getProperty("titan.graphql.database-frontend.mysql.descriptor"));
        Path managementDescriptor = Path.of(System.getProperty("titan.graphql.management-frontend.mysql.descriptor"));
        Path applicationMigrations = Path.of(System.getProperty("titan.graphql.database-engine.mysql.migrations.dir"));
        Path managementMigrations = Path.of(System.getProperty("titan.graphql.management.mysql.migrations.dir"));
        try (MySQLContainer<?> database = new MySQLContainer<>("mysql:8.4")
                .withCommand("--log_bin_trust_function_creators=1", "--max_allowed_packet=64M")
                .withUsername("root")
                .withPassword("test")) {
            database.start();
            try (Connection connection = DriverManager.getConnection(
                    database.getJdbcUrl(), database.getUsername(), database.getPassword())) {
                CommerceDatabaseEngineDeployment.executeMySqlScript(connection,
                        Path.of("ddl/mysql/titan_graphql_mysql.sql"));
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE DATABASE IF NOT EXISTS titan_runtime");
                    statement.execute("USE public");
                }
                TitanGraphqlManagementSchemaInstaller.install(connection, ManagementSchemaInstaller.Dialect.MYSQL);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("USE public");
                }
                for (String migration : new String[]{
                        "R__titan_004_graphql_mutation_state.sql",
                        "R__titan_006_graphql_outbox.sql",
                        "R__titan_007_graphql_control_jobs.sql"}) {
                    CommerceDatabaseEngineDeployment.executeMySqlScript(connection,
                            Path.of("src/database-engine-migrations/mysql").resolve(migration));
                }
                for (String migration : new String[]{"R__titan_010_runtime.sql", "R__titan_020_routines.sql"}) {
                    CommerceDatabaseEngineDeployment.executeMySqlScript(connection,
                            applicationMigrations.resolve(migration));
                }
                for (String migration : new String[]{"R__titan_010_runtime.sql", "R__titan_020_routines.sql"}) {
                    CommerceDatabaseEngineDeployment.executeMySqlScript(connection,
                            managementMigrations.resolve(migration));
                }
            }
            verify(deploymentDirectory, applicationDescriptor, managementDescriptor, database,
                    applicationMigrations.getParent(), "mysql", "jdbc:mysql://m4-database:3306/test");
        }
    }

    private static void verify(
            Path deploymentDirectory,
            Path applicationDescriptor,
            Path managementDescriptor,
            JdbcDatabaseContainer<?> database,
            Path artifactPackage,
            String dialect,
            String containerJdbcUrl
    ) throws Exception {
        Files.copy(applicationDescriptor, deploymentDirectory.resolve("application.properties"));
        Files.copy(managementDescriptor, deploymentDirectory.resolve("management.properties"));
        Files.writeString(deploymentDirectory.resolve("artifacts.properties"), "", StandardCharsets.UTF_8);
        Path previewDirectory = Files.createDirectory(deploymentDirectory.resolve("previews"));
        Files.setPosixFilePermissions(previewDirectory, PosixFilePermissions.fromString("rwxrwxrwx"));
        Files.writeString(previewDirectory.resolve("registry.properties"), "", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(previewDirectory.resolve("registry.properties"),
                PosixFilePermissions.fromString("rw-r--r--"));
        Files.setPosixFilePermissions(deploymentDirectory, PosixFilePermissions.fromString("rwxr-xr-x"));
        for (String file : new String[]{"application.properties", "management.properties", "artifacts.properties"}) {
            Files.setPosixFilePermissions(deploymentDirectory.resolve(file),
                    PosixFilePermissions.fromString("rw-r--r--"));
        }

        String project = "titanm4" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        int port = freePort();
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("TITAN_GRAPHQL_ADMIN_ACCESS_TOKEN", "container-deployment-token");
        environment.put("TITAN_GRAPHQL_JDBC_URL", containerJdbcUrl);
        environment.put("TITAN_GRAPHQL_JDBC_USERNAME", database.getUsername());
        environment.put("TITAN_GRAPHQL_JDBC_PASSWORD", database.getPassword());
        environment.put("TITAN_GRAPHQL_DEPLOY_DIR", deploymentDirectory.toAbsolutePath().toString());
        environment.put("TITAN_GRAPHQL_DIALECT", dialect);
        environment.put("TITAN_GRAPHQL_HOST_PORT", Integer.toString(port));
        environment.put("TITAN_GRAPHQL_PREVIEW_FRONTEND_REGISTRY", "/deploy/previews/registry.properties");
        environment.put("TITAN_GRAPHQL_CONTROL_DB_USER", database.getUsername());
        environment.put("TITAN_GRAPHQL_CONTROL_DB_PASSWORD", database.getPassword());
        String composeFile = "deployment/compose.yaml";
        String network = project + "_default";
        String worker = project + "-worker-1";
        boolean networkConnected = false;
        try {
            run(environment, "docker", "compose", "-p", project, "-f", composeFile, "create", "--build");
            run(environment, "docker", "network", "connect", "--alias", "m4-database",
                    network, database.getContainerId());
            networkConnected = true;
            run(environment, "docker", "compose", "-p", project, "-f", composeFile, "start");

            URI admin = URI.create("http://127.0.0.1:" + port + "/admin/graphql");
            awaitQuery(admin, "{ modelDraft(id: \"missing-draft\") { id } }", "data");
            String source = Files.readString(
                    Path.of("src/test/resources/graphql/renamed-vault.titan.graphql.yaml"));
            String importDocument = "mutation Import($yaml: String!) { requestModelImport(id: \""
                    + IMPORT_JOB_ID + "\", workspaceId: \"workspace-container\", yaml: $yaml) "
                    + "{ id workspaceId } }";
            JsonNode importResponse = post(admin, importDocument, Map.of("yaml", source), "container-import");
            assertEquals(IMPORT_JOB_ID, importResponse.path("data")
                    .path("requestModelImport").path("id").asText(), importResponse.toString());
            JsonNode imported = awaitJob(admin, IMPORT_JOB_ID);
            String draftId = JSON.readTree(imported.path("resultJson").asText()).path("draftId").asText();
            assertTrue(!draftId.isBlank(), imported.toString());

            int restartCount = Integer.parseInt(run(environment, "docker", "inspect", "--format",
                    "{{.RestartCount}}", worker).trim());
            run(environment, "docker", "exec", worker, "pkill", "-9", "java");
            awaitWorkerRestart(environment, worker, restartCount);

            String validateDocument = "mutation { requestModelValidation(id: \""
                    + VALIDATION_JOB_ID + "\", draftId: \"" + draftId + "\") { id draftId } }";
            JsonNode validationResponse = post(admin, validateDocument, Map.of(), "container-validation");
            assertEquals(VALIDATION_JOB_ID, validationResponse.path("data")
                    .path("requestModelValidation").path("id").asText(), validationResponse.toString());
            JsonNode validated = awaitJob(admin, VALIDATION_JOB_ID);
            assertEquals("VALIDATED", JSON.readTree(validated.path("resultJson").asText())
                    .path("status").asText(), validated.toString());
            JsonNode draft = post(admin, "{ modelDraft(id: \"" + draftId + "\") { id status } }",
                    Map.of(), null);
            assertEquals("validated", draft.path("data").path("modelDraft")
                    .path("status").asText(), draft.toString());

            String managementSource = Files.readString(
                    Path.of("src/test/resources/graphql/demo-blog.titan.graphql.yaml"));
            String managementImportDocument = "mutation Import($yaml: String!) { requestModelImport(id: \""
                    + MANAGEMENT_IMPORT_JOB_ID + "\", workspaceId: \"workspace-management\", yaml: $yaml) "
                    + "{ id workspaceId } }";
            JsonNode managementImport = post(admin, managementImportDocument,
                    Map.of("yaml", managementSource), "container-management-import");
            assertEquals(MANAGEMENT_IMPORT_JOB_ID, managementImport.path("data")
                    .path("requestModelImport").path("id").asText(), managementImport.toString());
            JsonNode managementResult = JSON.readTree(awaitJob(admin, MANAGEMENT_IMPORT_JOB_ID)
                    .path("resultJson").asText());
            String managementDraftId = managementResult.path("draftId").asText();
            String managementModelId = managementResult.path("modelId").asText();
            assertTrue(!managementDraftId.isBlank() && !managementModelId.isBlank(), managementResult.toString());
            String artifactValidationDocument = "mutation { requestModelValidation(id: \""
                    + ARTIFACT_VALIDATION_JOB_ID + "\", draftId: \"" + managementDraftId
                    + "\") { id draftId } }";
            JsonNode artifactValidation = post(admin, artifactValidationDocument,
                    Map.of(), "container-artifact-validation");
            assertEquals(ARTIFACT_VALIDATION_JOB_ID, artifactValidation.path("data")
                    .path("requestModelValidation").path("id").asText(), artifactValidation.toString());
            assertEquals("VALIDATED", JSON.readTree(awaitJob(admin, ARTIFACT_VALIDATION_JOB_ID)
                    .path("resultJson").asText()).path("status").asText());

            Path packageCopy = deploymentDirectory.resolve("artifact-package");
            try (Stream<Path> paths = Files.walk(artifactPackage)) {
                for (Path sourcePath : paths.toList()) {
                    Path targetPath = packageCopy.resolve(artifactPackage.relativize(sourcePath));
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                        Files.setPosixFilePermissions(targetPath, PosixFilePermissions.fromString("rwxr-xr-x"));
                    } else {
                        Files.copy(sourcePath, targetPath);
                        Files.setPosixFilePermissions(targetPath, PosixFilePermissions.fromString("rw-r--r--"));
                    }
                }
            }
            Files.writeString(deploymentDirectory.resolve("artifacts.properties"),
                    managementDraftId + "=/deploy/artifact-package\n", StandardCharsets.UTF_8);

            String artifactDocument = "mutation { requestArtifactGeneration(id: \"" + ARTIFACT_JOB_ID
                    + "\", draftId: \"" + managementDraftId + "\", generationProfile: \"preview\", "
                    + "enableIntrospection: true) { id draftId } }";
            JsonNode artifactRequest = post(admin, artifactDocument, Map.of(), "container-artifact");
            assertEquals(ARTIFACT_JOB_ID, artifactRequest.path("data")
                    .path("requestArtifactGeneration").path("id").asText(), artifactRequest.toString());
            JsonNode artifactResult = JSON.readTree(awaitJob(admin, ARTIFACT_JOB_ID)
                    .path("resultJson").asText());
            assertEquals("artifact-" + managementDraftId,
                    artifactResult.path("artifactSetId").asText(), artifactResult.toString());

            String operationDocument = "query Probe { __typename }";
            String operationHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(operationDocument.getBytes(StandardCharsets.UTF_8)));
            TitanGraphqlObservedOperation observed = TitanGraphqlObservedOperation.observed(
                    managementModelId, "preview", "operator", "container-client", operationHash,
                    "Probe", operationDocument, 1, 1, List.of("Query.__typename"), Instant.now().toString());
            try (Connection connection = DriverManager.getConnection(
                    database.getJdbcUrl(), database.getUsername(), database.getPassword())) {
                SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection);
                new TitanGraphqlDurableManagementStore(
                        new JdbcTransactionalMutationStore(dataSource), dataSource).saveObservedOperation(observed);
            }
            String reviewDocument = "mutation { requestObservedOperationReview(id: \"" + REVIEW_JOB_ID
                    + "\", observedOperationId: \"" + observed.id()
                    + "\", decision: \"approve\") { id observedOperationId } }";
            JsonNode reviewRequest = post(admin, reviewDocument, Map.of(), "container-review");
            assertEquals(REVIEW_JOB_ID, reviewRequest.path("data")
                    .path("requestObservedOperationReview").path("id").asText(), reviewRequest.toString());
            awaitJob(admin, REVIEW_JOB_ID);
            JsonNode reviewed = post(admin, "{ observedOperation(id: \"" + observed.id()
                    + "\") { id status } }", Map.of(), null);
            assertEquals("APPROVED", reviewed.path("data").path("observedOperation")
                    .path("status").asText(), reviewed.toString());
            String registryId = TitanGraphqlInMemoryManagementStore.operationRegistryId(
                    managementModelId, "preview");
            try (Connection connection = DriverManager.getConnection(
                    database.getJdbcUrl(), database.getUsername(), database.getPassword())) {
                SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection);
                TitanGraphqlDurableManagementStore store = new TitanGraphqlDurableManagementStore(
                        new JdbcTransactionalMutationStore(dataSource), dataSource);
                TitanGraphqlOperationRegistry registry = store.operationRegistry(registryId);
                assertNotNull(registry);
                assertEquals(1, registry.operations().size());
                assertEquals(operationHash, registry.operations().getFirst().operationHash());
                store.saveOperationRegistry(new TitanGraphqlOperationRegistry(
                        registry.id(), registry.modelId(), registry.environment(),
                        TitanGraphqlOperationRegistry.RegistryMode.ENFORCE, registry.operations(),
                        Instant.now().toString()));
            }
            String workerImage = run(environment, "docker", "compose", "-p", project, "-f", composeFile,
                    "images", "-q", "worker").trim();
            assertTrue(!workerImage.isBlank());
            String previewId = "container-preview";
            String publication = run(environment, "docker", "run", "--rm", "--network", network,
                    "--volume", deploymentDirectory.toAbsolutePath() + ":/deploy",
                    "--env", "TITAN_GRAPHQL_CONTROL_DB_USER",
                    "--env", "TITAN_GRAPHQL_CONTROL_DB_PASSWORD",
                    "--entrypoint", "/opt/titan/bin/titan-graphql-control", workerImage,
                    "publish-preview", dialect, containerJdbcUrl, "/deploy/artifact-package",
                    "/deploy/previews/registry.properties", previewId, managementDraftId,
                    "preview", registryId, Instant.now().plusSeconds(3600).toString(), "operator");
            String publicationJson = publication.lines().filter(line -> line.startsWith("{"))
                    .reduce((first, last) -> last).orElseThrow(() -> new AssertionError(publication));
            assertEquals(previewId, JSON.readTree(publicationJson).path("manifest")
                    .path("id").asText(), publication);
            environment.put("TITAN_GRAPHQL_HTTP_TRUST_REQUEST_CONTEXT_HEADERS", "true");
            run(environment, "docker", "compose", "-p", project, "-f", composeFile,
                    "up", "-d", "--no-deps", "--force-recreate", "frontend");
            URI preview = URI.create("http://127.0.0.1:" + port + "/preview/" + previewId + "/graphql");
            JsonNode previewResponse = awaitPreview(preview, operationDocument);
            assertEquals("Query", previewResponse.path("data").path("__typename").asText(),
                    previewResponse.toString());
        } catch (Exception | AssertionError failure) {
            System.out.println(run(environment, "docker", "compose", "-p", project, "-f", composeFile,
                    "logs", "--tail", "80"));
            throw failure;
        } finally {
            if (networkConnected) {
                run(environment, "docker", "network", "disconnect", network, database.getContainerId());
            }
            run(environment, "docker", "compose", "-p", project, "-f", composeFile, "down", "--remove-orphans");
        }
    }

    private static JsonNode post(URI endpoint, String query, Map<String, ?> variables, String requestId)
            throws Exception {
        return post(endpoint, query, variables, requestId, Map.of());
    }

    private static JsonNode post(URI endpoint, String query, Map<String, ?> variables, String requestId,
            Map<String, ?> extensions) throws Exception {
        Map<String, Object> body = Map.of("query", query, "variables", variables, "extensions", extensions);
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer container-deployment-token");
        if (requestId != null) {
            request.header("X-Titan-Management-Request-Id", requestId);
            request.header("X-Titan-Management-Idempotency-Key", requestId);
        }
        if (endpoint.getPath().startsWith("/preview/")) {
            request.header("X-Titan-Actor-Id", "1");
            request.header("X-Titan-Actor-Role", "operator");
        }
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode parsed = JSON.readTree(response.body());
        assertTrue(!parsed.has("errors"), response.body());
        return parsed;
    }

    private static void awaitQuery(URI endpoint, String query, String expectedMember) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                JsonNode result = post(endpoint, query, Map.of(), null);
                assertTrue(result.has(expectedMember), result.toString());
                return;
            } catch (Exception failure) {
                last = failure;
                Thread.sleep(200);
            }
        }
        assertNotNull(last);
        throw last;
    }

    private static JsonNode awaitPreview(URI endpoint, String query) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                return post(endpoint, query, Map.of(), null, Map.of("client", "container-client"));
            } catch (java.io.IOException unavailable) {
                last = unavailable;
                Thread.sleep(200);
            }
        }
        assertNotNull(last);
        throw last;
    }

    private static JsonNode awaitJob(URI endpoint, String jobId) throws Exception {
        JsonNode last = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            JsonNode response = post(endpoint, "{ controlJob(id: \"" + jobId
                    + "\") { status resultJson failureCode } }", Map.of(), null);
            last = response;
            JsonNode job = response.path("data").path("controlJob");
            if (job.path("status").asText().equals("succeeded")) {
                return job;
            }
            assertTrue(!job.path("status").asText().equals("failed"), response.toString());
            Thread.sleep(200);
        }
        throw new AssertionError("control job did not finish: " + jobId + " " + last);
    }

    private static void awaitWorkerRestart(Map<String, String> environment, String worker, int previous)
            throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            String state = run(environment, "docker", "inspect", "--format",
                    "{{.RestartCount}} {{.State.Status}}", worker).trim();
            String[] parts = state.split(" ");
            if (Integer.parseInt(parts[0]) > previous && parts[1].equals("running")) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Docker did not restart the worker container");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String run(Map<String, String> environment, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.directory(Path.of(".").toAbsolutePath().normalize().toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException failure) {
                throw new CompletionException(failure);
            }
        });
        boolean finished = process.waitFor(180, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("timed out: " + String.join(" ", command));
        }
        String commandOutput = output.get(10, TimeUnit.SECONDS);
        assertEquals(0, process.exitValue(), commandOutput);
        return commandOutput;
    }
}
