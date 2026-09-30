package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.titan.graphql.GraphqlAdminHttpResource;
import io.titan.graphql.GraphqlPreviewHttpResource;
import io.titan.graphql.TitanGraphqlModelImportService;
import io.titan.graphql.artifact.TitanGraphqlPackageBinding;
import io.titan.graphql.controlplane.TitanGraphqlArtifactGenerationService;
import io.titan.graphql.controlplane.TitanGraphqlArtifactGenerationJobRunner;
import io.titan.graphql.controlplane.TitanGraphqlArtifactPackageRegistry;
import io.titan.graphql.controlplane.TitanGraphqlControlJobQueue;
import io.titan.graphql.controlplane.TitanGraphqlModelImportJobRunner;
import io.titan.graphql.controlplane.TitanGraphqlModelValidationJobRunner;
import io.titan.graphql.controlplane.TitanGraphqlModelValidationService;
import io.titan.graphql.controlplane.TitanGraphqlOperationReviewJobRunner;
import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import io.titan.graphql.management.TitanGraphqlInMemoryManagementStore;
import io.titan.graphql.management.TitanGraphqlManagedWorkspace;
import io.titan.graphql.management.TitanGraphqlManagementSchemaInstaller;
import io.titan.graphql.management.TitanGraphqlModelDraft;
import io.titan.graphql.management.TitanGraphqlObservedOperation;
import io.titan.graphql.management.TitanGraphqlOperationRegistry;
import io.titan.graphql.management.TitanGraphqlPreviewDeploymentPublisher;
import io.titan.graphql.management.TitanGraphqlPreviewDraftExporter;
import io.titan.graphql.management.TitanGraphqlPreviewPackageStager;
import io.titan.graphql.management.TitanGraphqlPreviewPublicationService;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import io.titan.management.JdbcTransactionalMutationStore;
import io.titan.management.ManagementSchemaInstaller;
import io.titan.runtime.jdbc.SingleConnectionDataSource;
import io.titan.runtime.testing.DatabaseTarget;
import io.titan.runtime.testing.TitanTest;
import io.titan.runtime.testing.TitanTestContext;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("docker")
@Tag("database-engine-preview-candidate")
@TitanTest(targets = {DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL})
final class PreviewCandidatePublicationIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OPERATION = "query Probe { __typename }";

    @TempDir
    Path temporaryDirectory;

    @Test
    void validatedDraftServesThroughPublishedCandidate(TitanTestContext context) throws Exception {
        String source = Files.readString(Path.of(System.getProperty("titan.graphql.preview.candidate.model")));
        String semanticHash = TitanGraphqlModelDocumentJson.semanticHash(
                TitanGraphqlModelDocumentYaml.parse(source));
        for (DatabaseTarget target : List.of(DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL)) {
            String dialect = target == DatabaseTarget.POSTGRESQL ? "postgresql" : "mysql";
            Path packageDirectory = Path.of(System.getProperty(
                    "titan.graphql.preview.candidate.package." + dialect));
            assertEquals(semanticHash, TitanGraphqlPackageBinding.read(packageDirectory).modelSemanticSha256());
            Connection connection = context.connection(target);
            TitanGraphqlManagementSchemaInstaller.install(connection,
                    target == DatabaseTarget.POSTGRESQL
                            ? ManagementSchemaInstaller.Dialect.POSTGRESQL
                            : ManagementSchemaInstaller.Dialect.MYSQL);
            installCandidate(connection, target, packageDirectory);
            installManagementPackage(connection, target);
            SingleConnectionDataSource servingDatabase = new SingleConnectionDataSource(connection);
            GraphqlAdminHttpResource adminRoute = new GraphqlAdminHttpResource(
                    "candidate-management-token", "operator", "candidate-operator",
                    Path.of("build/generated/proofs/database-engine-management-" + dialect
                            + "/frontend-deployment.properties").toString(),
                    () -> servingDatabase);
            TitanGraphqlDurableManagementStore store = new TitanGraphqlDurableManagementStore(
                    new JdbcTransactionalMutationStore(servingDatabase), servingDatabase);
            store.saveWorkspace(new TitanGraphqlManagedWorkspace(
                    "workspace-candidate", "candidate", "", "preview", "", ""));
            TitanGraphqlControlJobQueue queue = new TitanGraphqlControlJobQueue(
                    servingDatabase,
                    target == DatabaseTarget.POSTGRESQL
                            ? TitanGraphqlControlJobQueue.Dialect.POSTGRESQL
                            : TitanGraphqlControlJobQueue.Dialect.MYSQL,
                    Clock.systemUTC());
            String importJobId = "00000000-0000-0000-0000-000000000022";
            Response importRequest = adminRoute.postResponse(
                    Map.of("query", "mutation Import($yaml: String!) { requestModelImport(id: \""
                            + importJobId + "\", workspaceId: \"workspace-candidate\", yaml: $yaml) { id } }",
                            "operationName", "Import", "variables", Map.of("yaml", source)),
                    "application/graphql-response+json", "Bearer candidate-management-token",
                    "candidate-import-" + dialect, "candidate-import-key-" + dialect, null);
            assertEquals(200, importRequest.getStatus(), String.valueOf(importRequest.getEntity()));
            JsonNode importRequested = JSON.readTree((String) importRequest.getEntity());
            assertFalse(importRequested.has("errors"), importRequested::toString);
            assertEquals(importJobId, importRequested.at("/data/requestModelImport/id").asText());
            assertTrue(new TitanGraphqlModelImportJobRunner(queue,
                    () -> new TitanGraphqlModelImportService(store)).runOne(Duration.ofMinutes(1)));
            JsonNode imported = controlJobResult(adminRoute, importJobId, dialect);
            assertEquals("succeeded", imported.at("/data/controlJob/status").asText());
            JsonNode importResult = JSON.readTree(imported.at("/data/controlJob/resultJson").asText());
            assertTrue(importResult.path("accepted").asBoolean(), importResult::toString);
            assertEquals(semanticHash, importResult.path("semanticHash").asText());
            String draftId = importResult.path("draftId").asText();
            String modelId = importResult.path("modelId").asText();
            assertEquals(TitanGraphqlModelDraft.ModelDraftStatus.IMPORTED, store.draft(draftId).status());
            String validationJobId = "00000000-0000-0000-0000-000000000023";
            Response validationRequest = adminRoute.postResponse(
                    Map.of("query", "mutation { requestModelValidation(id: \"" + validationJobId
                            + "\", draftId: \"" + draftId + "\") { id } }"),
                    "application/graphql-response+json", "Bearer candidate-management-token",
                    "candidate-validation-" + dialect, "candidate-validation-key-" + dialect, null);
            assertEquals(200, validationRequest.getStatus(), String.valueOf(validationRequest.getEntity()));
            JsonNode validationRequested = JSON.readTree((String) validationRequest.getEntity());
            assertFalse(validationRequested.has("errors"), validationRequested::toString);
            assertEquals(validationJobId, validationRequested.at("/data/requestModelValidation/id").asText());
            assertTrue(new TitanGraphqlModelValidationJobRunner(
                    queue, new TitanGraphqlModelValidationService(store)).runOne(Duration.ofMinutes(1)));
            JsonNode validated = controlJobResult(adminRoute, validationJobId, dialect);
            assertEquals("succeeded", validated.at("/data/controlJob/status").asText());
            assertTrue(JSON.readTree(validated.at("/data/controlJob/resultJson").asText())
                    .path("accepted").asBoolean());
            assertEquals(TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED, store.draft(draftId).status());
            Path directory = temporaryDirectory.resolve(dialect);
            Path exported = directory.resolve("model.yaml");
            assertEquals(source, Files.readString(TitanGraphqlPreviewDraftExporter.export(
                    store, draftId, exported).source()));

            Path artifactPackages = directory.resolve("artifact-packages.properties");
            Files.writeString(artifactPackages, "");
            TitanGraphqlArtifactPackageRegistry packageRegistry =
                    new TitanGraphqlArtifactPackageRegistry(artifactPackages);
            TitanGraphqlArtifactGenerationJobRunner runner = new TitanGraphqlArtifactGenerationJobRunner(
                    queue, new TitanGraphqlArtifactGenerationService(
                            store, packageRegistry::packageDirectory));
            String jobId = "00000000-0000-0000-0000-000000000021";
            Response request = adminRoute.postResponse(
                    Map.of("query", "mutation { requestArtifactGeneration(id: \"" + jobId
                            + "\", draftId: \"" + draftId + "\", generationProfile: \"preview\", "
                            + "enableIntrospection: true) { id } }"),
                    "application/graphql-response+json", "Bearer candidate-management-token",
                    "candidate-request-" + dialect, "candidate-request-key-" + dialect, null);
            assertEquals(200, request.getStatus(), String.valueOf(request.getEntity()));
            JsonNode requested = JSON.readTree((String) request.getEntity());
            assertFalse(requested.has("errors"), requested::toString);
            assertEquals(jobId, requested.at("/data/requestArtifactGeneration/id").asText());
            assertThrows(IllegalStateException.class, () -> runner.runOne(Duration.ofMinutes(1)));
            assertEquals(TitanGraphqlControlJobQueue.Status.PENDING,
                    queue.find(jobId).orElseThrow().status());
            Path managementPackage = Path.of("build/generated/proofs/database-engine-management-"
                    + dialect + "/package");
            assertThrows(IllegalStateException.class, () -> TitanGraphqlPreviewPackageStager.stage(
                    store, draftId, managementPackage, dialect, artifactPackages));
            TitanGraphqlPreviewPackageStager.Stage staged = TitanGraphqlPreviewPackageStager.stage(
                    store, draftId, packageDirectory, dialect, artifactPackages);
            assertEquals(packageDirectory.toAbsolutePath().normalize(), staged.packageDirectory());
            assertEquals(staged, TitanGraphqlPreviewPackageStager.stage(
                    store, draftId, packageDirectory, dialect, artifactPackages));
            String otherDialect = target == DatabaseTarget.POSTGRESQL ? "mysql" : "postgresql";
            Path otherPackage = Path.of(System.getProperty("titan.graphql.preview.candidate.package."
                    + otherDialect));
            assertThrows(IllegalStateException.class, () -> TitanGraphqlPreviewPackageStager.stage(
                    store, draftId, otherPackage, otherDialect, artifactPackages));
            assertTrue(runner.runOne(Duration.ofMinutes(1)));
            assertEquals(TitanGraphqlControlJobQueue.Status.SUCCEEDED,
                    queue.find(jobId).orElseThrow().status());
            JsonNode jobResultBody = controlJobResult(adminRoute, jobId, dialect);
            assertEquals("succeeded", jobResultBody.at("/data/controlJob/status").asText());
            assertEquals(TitanGraphqlModelDraft.ModelDraftStatus.READY_FOR_REVIEW,
                    store.draft(draftId).status());
            assertNotNull(store.artifactEvidence("artifact-" + draftId));
            TitanGraphqlObservedOperation observed = TitanGraphqlObservedOperation.observed(
                    modelId, "preview", "operator", "candidate-client", sha256(OPERATION),
                    "Probe", OPERATION, 1, 1, List.of("Query.__typename"), Instant.now().toString());
            store.saveObservedOperation(observed);
            String reviewJobId = "00000000-0000-0000-0000-000000000024";
            Response reviewRequest = adminRoute.postResponse(
                    Map.of("query", "mutation { requestObservedOperationReview(id: \"" + reviewJobId
                            + "\", observedOperationId: \"" + observed.id()
                            + "\", decision: \"approve\") { id } }"),
                    "application/graphql-response+json", "Bearer candidate-management-token",
                    "candidate-review-" + dialect, "candidate-review-key-" + dialect, null);
            assertEquals(200, reviewRequest.getStatus(), String.valueOf(reviewRequest.getEntity()));
            JsonNode reviewRequested = JSON.readTree((String) reviewRequest.getEntity());
            assertFalse(reviewRequested.has("errors"), reviewRequested::toString);
            assertEquals(reviewJobId, reviewRequested.at("/data/requestObservedOperationReview/id").asText());
            assertTrue(new TitanGraphqlOperationReviewJobRunner(
                    queue, () -> store, Clock.systemUTC()).runOne(Duration.ofMinutes(1)));
            JsonNode reviewedJob = controlJobResult(adminRoute, reviewJobId, dialect);
            assertEquals("succeeded", reviewedJob.at("/data/controlJob/status").asText());
            assertEquals("APPROVED", JSON.readTree(reviewedJob.at("/data/controlJob/resultJson").asText())
                    .path("status").asText());
            String registryId = TitanGraphqlInMemoryManagementStore.operationRegistryId(modelId, "preview");
            TitanGraphqlOperationRegistry reviewed = store.operationRegistry(registryId);
            assertNotNull(reviewed);
            assertEquals(TitanGraphqlOperationRegistry.RegistryMode.OBSERVE, reviewed.mode());
            store.saveOperationRegistry(new TitanGraphqlOperationRegistry(
                    reviewed.id(), reviewed.modelId(), reviewed.environment(),
                    TitanGraphqlOperationRegistry.RegistryMode.ENFORCE, reviewed.operations(),
                    Instant.now().toString()));
            Path registry = directory.resolve("registry.properties");
            TitanGraphqlPreviewDeploymentPublisher.Publication published =
                    new TitanGraphqlPreviewPublicationService(store, servingDatabase).publish(
                            "candidate-preview", draftId, "preview", registryId,
                            Instant.now().plusSeconds(3600), "operator", packageDirectory, dialect, registry);
            assertNotNull(published.manifest());
            GraphqlPreviewHttpResource route = new GraphqlPreviewHttpResource(
                    registry.toString(), () -> servingDatabase, true);
            Response response = route.postResponse(
                    "candidate-preview", Map.of("query", OPERATION,
                            "extensions", Map.of("client", "candidate-client")),
                    "application/graphql-response+json", null, "operator", null, null, null,
                    null, null, null, null, null);
            assertEquals(200, response.getStatus(), String.valueOf(response.getEntity()));
            assertEquals("Query", JSON.readTree((String) response.getEntity())
                    .at("/data/__typename").asText());
        }
    }

    private static JsonNode controlJobResult(GraphqlAdminHttpResource adminRoute, String jobId, String dialect)
            throws Exception {
        Response response = adminRoute.postResponse(
                Map.of("query", "{ controlJob(id: \"" + jobId + "\") { id status resultJson } }"),
                "application/graphql-response+json", "Bearer candidate-management-token",
                "candidate-job-result-" + dialect + "-" + jobId, null, null);
        assertEquals(200, response.getStatus(), String.valueOf(response.getEntity()));
        JsonNode body = JSON.readTree((String) response.getEntity());
        assertFalse(body.has("errors"), body::toString);
        assertEquals(jobId, body.at("/data/controlJob/id").asText());
        return body;
    }

    private static void installCandidate(Connection connection, DatabaseTarget target, Path packageDirectory)
            throws Exception {
        String dialect = target == DatabaseTarget.POSTGRESQL ? "postgresql" : "mysql";
        if (target == DatabaseTarget.MYSQL) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE IF NOT EXISTS public");
                statement.execute("USE public");
            }
        }
        for (String name : List.of("R__titan_010_runtime.sql", "R__titan_020_routines.sql")) {
            execute(connection, target, packageDirectory.resolve(dialect).resolve(name));
        }
    }

    private static void installManagementPackage(Connection connection, DatabaseTarget target) throws Exception {
        String dialect = target == DatabaseTarget.POSTGRESQL ? "postgresql" : "mysql";
        Path packageDirectory = Path.of("build/generated/proofs/database-engine-management-" + dialect
                + "/package/" + dialect);
        for (String name : List.of("R__titan_010_runtime.sql", "R__titan_020_routines.sql")) {
            execute(connection, target, packageDirectory.resolve(name));
        }
    }

    private static void execute(Connection connection, DatabaseTarget target, Path migration) throws Exception {
        if (target == DatabaseTarget.POSTGRESQL) {
            CommerceDatabaseEngineDeployment.executePostgreSqlScript(connection, migration);
        } else {
            CommerceDatabaseEngineDeployment.executeMySqlScript(connection, migration);
        }
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
