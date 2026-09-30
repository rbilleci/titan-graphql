package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.titan.graphql.GraphqlAdminHttpResource;
import io.titan.graphql.GraphqlPreviewHttpResource;
import io.titan.graphql.GraphqlRequestContext;
import io.titan.graphql.GraphqlRuntimeRequest;
import io.titan.graphql.TitanGraphqlModelImportService;
import io.titan.graphql.artifact.TitanGraphqlDatabasePackageIdentity;
import io.titan.graphql.artifact.TitanGraphqlDatabaseRuntimeIdentity;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.graphql.artifact.TitanGraphqlPackageBinding;
import io.titan.graphql.database.DatabaseGraphqlWholeRequestRuntime;
import io.titan.graphql.frontend.DatabasePreviewDeploymentAttestation;
import io.titan.graphql.frontend.DatabaseWholeRequestClient;
import io.titan.graphql.controlplane.TitanGraphqlArtifactGenerationJobRunner;
import io.titan.graphql.controlplane.TitanGraphqlArtifactGenerationService;
import io.titan.graphql.controlplane.TitanGraphqlControlJobQueue;
import io.titan.graphql.controlplane.TitanGraphqlModelValidationJobRunner;
import io.titan.graphql.controlplane.TitanGraphqlModelValidationService;
import io.titan.graphql.controlplane.TitanGraphqlModelImportJobRunner;
import io.titan.graphql.controlplane.TitanGraphqlOperationReviewJobRunner;
import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import io.titan.graphql.management.TitanGraphqlArtifactSetRef;
import io.titan.graphql.management.TitanGraphqlManagedModel;
import io.titan.graphql.management.TitanGraphqlManagedWorkspace;
import io.titan.graphql.management.TitanGraphqlManagementSchemaInstaller;
import io.titan.graphql.management.TitanGraphqlModelDraft;
import io.titan.graphql.management.TitanGraphqlObservedOperation;
import io.titan.graphql.management.TitanGraphqlOperationRegistry;
import io.titan.graphql.management.TitanGraphqlPreviewBuild;
import io.titan.graphql.management.TitanGraphqlPreviewDeploymentPublisher;
import io.titan.graphql.management.TitanGraphqlPreviewPublicationService;
import io.titan.graphql.management.TitanGraphqlValidationReportRef;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import io.titan.management.JdbcTransactionalMutationStore;
import io.titan.management.ManagementSchemaInstaller;
import io.titan.runtime.jdbc.SingleConnectionDataSource;
import io.titan.runtime.testing.DatabaseTarget;
import io.titan.runtime.testing.TitanTest;
import io.titan.runtime.testing.TitanTestContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("docker")
@Tag("database-engine-management")
@TitanTest(targets = {DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL})
final class ManagementDatabaseGraphqlEngineIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DatabaseGraphqlWholeRequestRuntime.EntryPoint MANAGEMENT_ENTRY_POINT =
            new DatabaseGraphqlWholeRequestRuntime.EntryPoint("management_graphql", "execute_graphql_request");

    @TempDir
    Path temporaryDirectory;

    @Test
    void verifiedPreviewPublicationBindsPackageAndReplacesRegistryMapping(TitanTestContext context) throws Exception {
        for (DatabaseTarget target : List.of(DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL)) {
            String dialect = target == DatabaseTarget.POSTGRESQL ? "postgresql" : "mysql";
            Path packageDirectory = packageDirectory("database-engine-management", target);
            TitanGraphqlGap005ArtifactMetadata metadata = TitanGraphqlGap005ArtifactMetadata.read(packageDirectory);
            TitanGraphqlPackageBinding binding = TitanGraphqlPackageBinding.read(packageDirectory);
            Connection connection = context.connection(target);
            TitanGraphqlManagementSchemaInstaller.install(connection,
                    target == DatabaseTarget.POSTGRESQL
                            ? ManagementSchemaInstaller.Dialect.POSTGRESQL
                            : ManagementSchemaInstaller.Dialect.MYSQL);
            installManagementPackage(connection, target);
            SingleConnectionDataSource servingDatabase = new SingleConnectionDataSource(connection);
            Path directory = temporaryDirectory.resolve(dialect);
            TitanGraphqlDurableManagementStore store = new TitanGraphqlDurableManagementStore(
                    new JdbcTransactionalMutationStore(servingDatabase), servingDatabase);
            store.saveModel(new TitanGraphqlManagedModel(
                    "model-001", "workspace-001", "titan-graphql-management-database", "", "",
                    "draft-001", "", "validation-001", "artifact-001", Instant.now().toString(), ""));
            store.saveArtifactSet(new TitanGraphqlArtifactSetRef(
                    "artifact-001", "draft-001", binding.modelSemanticSha256(), "", "", "", "", "",
                    sha256("generated preview SQL"), "preview", Instant.now().toString()), metadata);
            String source = Files.readString(Path.of(
                    "src/main/resources/graphql/management-database.titan.graphql.yaml"));
            TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(source);
            assertEquals(binding.modelSemanticSha256(), TitanGraphqlModelDocumentJson.semanticHash(document));
            store.saveDraft(new TitanGraphqlModelDraft(
                    "draft-001", "model-001", TitanGraphqlModelDraft.ModelDraftStatus.READY_FOR_REVIEW,
                    TitanGraphqlModelDraft.SourceFormat.YAML, source,
                    TitanGraphqlModelDocumentJson.canonicalJson(document), binding.modelSemanticSha256(),
                    "validation-001", "artifact-001", "", "operator", Instant.now().toString(),
                    Instant.now().toString()));
            String operationDocument = "query Probe { __typename }";
            TitanGraphqlOperationRegistry registryRecord = new TitanGraphqlOperationRegistry(
                    "registry-preview", "model-001", "preview",
                    TitanGraphqlOperationRegistry.RegistryMode.ENFORCE,
                    List.of(new TitanGraphqlOperationRegistry.RegisteredOperation(
                            "registered-probe", "Probe", sha256(operationDocument), operationDocument,
                            TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED,
                            List.of("operator"), List.of(), 1, 1, List.of(), Instant.now().toString(),
                            "operator", Instant.now().toString())), Instant.now().toString());
            store.saveOperationRegistry(registryRecord);
            Path registry = directory.resolve("preview-registry.properties");
            Files.createDirectories(directory);
            Path existingDescriptor = directory.resolve("other-preview.properties").toAbsolutePath();
            Files.writeString(existingDescriptor, Files.readString(
                    packageDirectory.getParent().resolve("frontend-deployment.properties"))
                    + "\npreview-build-id=other-preview\npreview-expires-at="
                    + Instant.now().plusSeconds(3600) + "\n");
            Files.writeString(registry, "other-preview=" + existingDescriptor + "\n");
            Instant firstExpiry = Instant.now().plusSeconds(3600);
            TitanGraphqlPreviewDeploymentPublisher.Publication published =
                    new TitanGraphqlPreviewPublicationService(store, servingDatabase).publish(
                            "preview-package", "draft-001", "preview", "registry-preview", firstExpiry,
                            "operator", packageDirectory, dialect, registry);
            TitanGraphqlPreviewBuild first = store.previewBuild("preview-package");
            java.util.Properties entries = new java.util.Properties();
            try (java.io.Reader reader = Files.newBufferedReader(registry)) {
                entries.load(reader);
            }
            assertEquals(published.descriptor().toString(), entries.getProperty(first.id()));
            assertEquals(existingDescriptor.toString(), entries.getProperty("other-preview"));
            String descriptor = Files.readString(published.descriptor());
            assertTrue(descriptor.contains("preview-build-id=preview-package"));
            assertTrue(descriptor.contains("operation-registry-id=registry-preview"));
            assertTrue(descriptor.contains("preview-expires-at="));
            assertTrue(descriptor.contains("preview-deployment-sha256="));
            java.util.Properties descriptorProperties = new java.util.Properties();
            descriptorProperties.load(new java.io.StringReader(descriptor));
            assertEquals(binding.deploymentFingerprint(),
                    descriptorProperties.getProperty("deployment-fingerprint"));
            Files.writeString(existingDescriptor, Files.readString(existingDescriptor)
                    + "operation-registry-id=registry-preview\npreview-deployment-sha256="
                    + descriptorProperties.getProperty("preview-deployment-sha256") + "\n");
            GraphqlPreviewHttpResource previewRoute = new GraphqlPreviewHttpResource(
                    registry.toString(), () -> servingDatabase, true);
            assertEquals(200, previewRequest(previewRoute).getStatus());
            assertEquals("Query", JSON.readTree((String) previewRequest(previewRoute).getEntity())
                    .at("/data/__typename").asText());

            String staleSource = source.replace(
                    "Database projection of installed management drafts and control jobs.",
                    "Changed management projection.");
            assertFalse(staleSource.equals(source));
            TitanGraphqlDurableManagementStore staleDraftStore = new TitanGraphqlDurableManagementStore(
                    directory.resolve("stale-draft.log"));
            staleDraftStore.saveModel(store.model("model-001"));
            staleDraftStore.saveArtifactSet(store.artifactSet("artifact-001"));
            staleDraftStore.saveDraft(new TitanGraphqlModelDraft(
                    "draft-001", "model-001", TitanGraphqlModelDraft.ModelDraftStatus.READY_FOR_REVIEW,
                    TitanGraphqlModelDraft.SourceFormat.YAML, staleSource,
                    TitanGraphqlModelDocumentJson.canonicalJson(document), binding.modelSemanticSha256(),
                    "validation-001", "artifact-001", "", "operator", Instant.now().toString(),
                    Instant.now().toString()));
            assertThrows(IllegalArgumentException.class,
                    () -> new TitanGraphqlPreviewPublicationService(staleDraftStore, servingDatabase).publish(
                            "preview-stale-source", "draft-001", "preview", "registry-preview",
                            Instant.now().plusSeconds(3600), "operator", packageDirectory, dialect, registry));

            TitanGraphqlPreviewBuild stale = previewCandidate(
                    "preview-package", sha256("wrong manifest"), Instant.now().plusSeconds(7200));
            assertThrows(IllegalArgumentException.class, () -> TitanGraphqlPreviewDeploymentPublisher.publish(
                    store, stale, packageDirectory, dialect, registry, servingDatabase));
            try (java.io.Reader reader = Files.newBufferedReader(registry)) {
                entries.load(reader);
            }
            assertEquals(published.descriptor().toString(), entries.getProperty(first.id()));
            assertEquals(first.expiresAt(), store.previewBuild(first.id()).expiresAt());

            TitanGraphqlPreviewBuild replacement = previewCandidate(
                    "preview-package", metadata.manifestContentHash(), Instant.now().plusSeconds(7200));
            try (java.sql.PreparedStatement change = connection.prepareStatement(
                    "UPDATE management.graphql_operation_registries SET mode = 'WARN' WHERE id = ?")) {
                change.setString(1, first.operationRegistryId());
                assertEquals(1, change.executeUpdate());
            }
            assertEquals(503, previewRequest(previewRoute).getStatus());
            assertThrows(IllegalStateException.class, () -> TitanGraphqlPreviewDeploymentPublisher.publish(
                    store, replacement, packageDirectory, dialect, registry, servingDatabase));
            assertEquals(first.expiresAt(), store.previewBuild(first.id()).expiresAt());
            try (java.sql.PreparedStatement restore = connection.prepareStatement(
                    "UPDATE management.graphql_operation_registries SET mode = 'ENFORCE' WHERE id = ?")) {
                restore.setString(1, first.operationRegistryId());
                assertEquals(1, restore.executeUpdate());
            }
            try (java.sql.PreparedStatement remove = connection.prepareStatement(
                    "DELETE FROM management.graphql_registry_operations WHERE registry_id = ?")) {
                remove.setString(1, first.operationRegistryId());
                assertEquals(1, remove.executeUpdate());
            }
            assertThrows(IllegalStateException.class, () -> TitanGraphqlPreviewDeploymentPublisher.publish(
                    store, replacement, packageDirectory, dialect, registry, servingDatabase));
            store.saveOperationRegistry(registryRecord);
            try (java.sql.PreparedStatement change = connection.prepareStatement(
                    "UPDATE management.graphql_registry_operations SET operation_name = 'Changed' "
                            + "WHERE registry_id = ?")) {
                change.setString(1, first.operationRegistryId());
                assertEquals(1, change.executeUpdate());
            }
            assertEquals(503, previewRequest(previewRoute).getStatus());
            assertThrows(IllegalStateException.class, () -> TitanGraphqlPreviewDeploymentPublisher.publish(
                    store, replacement, packageDirectory, dialect, registry, servingDatabase));
            store.saveOperationRegistry(registryRecord);
            String identityTable = target == DatabaseTarget.POSTGRESQL
                    ? "public.titan_graphql_package_identity"
                    : "management_graphql.titan_graphql_package_identity";
            try (java.sql.PreparedStatement change = connection.prepareStatement(
                    "UPDATE " + identityTable + " "
                            + "SET package_identity = ? WHERE entry_point = ?")) {
                change.setString(1, sha256("wrong installed package"));
                change.setString(2, "management_graphql.execute_graphql_request");
                assertEquals(1, change.executeUpdate());
            }
            assertEquals(503, previewRequest(previewRoute).getStatus());
            assertThrows(IllegalStateException.class, () -> TitanGraphqlPreviewDeploymentPublisher.publish(
                    store, replacement, packageDirectory, dialect, registry, servingDatabase));
            try (java.sql.PreparedStatement restore = connection.prepareStatement(
                    "UPDATE " + identityTable + " "
                            + "SET package_identity = ? WHERE entry_point = ?")) {
                restore.setString(1, TitanGraphqlDatabasePackageIdentity.read(packageDirectory));
                restore.setString(2, "management_graphql.execute_graphql_request");
                assertEquals(1, restore.executeUpdate());
            }
            java.util.Properties unchangedEntries = new java.util.Properties();
            try (java.io.Reader reader = Files.newBufferedReader(registry)) {
                unchangedEntries.load(reader);
            }
            assertEquals(published.descriptor().toString(), unchangedEntries.getProperty(first.id()));
            assertEquals(first.expiresAt(), store.previewBuild(first.id()).expiresAt());
            TitanGraphqlPreviewDeploymentPublisher.Publication replaced =
                    TitanGraphqlPreviewDeploymentPublisher.publish(
                            store, replacement, packageDirectory, dialect, registry, servingDatabase);
            assertFalse(published.descriptor().equals(replaced.descriptor()));
            assertTrue(Files.isRegularFile(published.descriptor()));
            try (java.io.Reader reader = Files.newBufferedReader(registry)) {
                java.util.Properties replacedEntries = new java.util.Properties();
                replacedEntries.load(reader);
                assertEquals(replaced.descriptor().toString(), replacedEntries.getProperty(first.id()));
            }
        }
    }

    private static TitanGraphqlPreviewBuild previewCandidate(String id, String manifestHash, Instant expiresAt) {
        return new TitanGraphqlPreviewBuild(
                id, "model-001", "draft-001", "artifact-001", "preview",
                TitanGraphqlPreviewBuild.PreviewBuildStatus.READY, "/preview/" + id + "/graphql", "", "",
                "", manifestHash, "registry-preview", expiresAt.toString(), "operator", Instant.now().toString());
    }

    private static Response previewRequest(GraphqlPreviewHttpResource route) {
        return route.postResponse(
                "preview-package", Map.of("query", "query Probe { __typename }"),
                "application/graphql-response+json", null, "operator", null, null, null,
                null, null, null, null, null);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void installedRegistryEnforcesOrderedDocumentAndScopeDecisions(TitanTestContext context) throws Exception {
        String document = "query Probe { __typename }";
        String registryId = "registry-management-serving-proof";
        for (DatabaseTarget target : List.of(DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL)) {
            Connection connection = context.connection(target);
            if (target == DatabaseTarget.POSTGRESQL) {
                CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
            } else {
                CommerceDatabaseEngineDeployment.deployMySql(connection);
            }
            TitanGraphqlManagementSchemaInstaller.install(connection,
                    target == DatabaseTarget.POSTGRESQL
                            ? ManagementSchemaInstaller.Dialect.POSTGRESQL
                            : ManagementSchemaInstaller.Dialect.MYSQL);
            installManagementPackage(connection, target);
            SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection);
            TitanGraphqlDurableManagementStore store = new TitanGraphqlDurableManagementStore(
                    new JdbcTransactionalMutationStore(dataSource), dataSource);
            TitanGraphqlOperationRegistry.RegisteredOperation rejected =
                    new TitanGraphqlOperationRegistry.RegisteredOperation(
                            "registered-probe", "Probe", "legacy-operation-hash", document,
                            TitanGraphqlOperationRegistry.RegisteredOperationStatus.REJECTED,
                            List.of("operator"), List.of("portal"), 1, 1, List.of(),
                            "2026-09-26T00:00:00Z", "reviewer", "2026-09-26T00:00:00Z");
            TitanGraphqlOperationRegistry warningRegistry = new TitanGraphqlOperationRegistry(
                    registryId, "model-management-serving", "development",
                    TitanGraphqlOperationRegistry.RegistryMode.WARN, List.of(rejected),
                    "2026-09-26T00:00:00Z");
            store.saveOperationRegistry(warningRegistry);
            try (java.sql.PreparedStatement projected = connection.prepareStatement(
                    "SELECT mode FROM management.graphql_operation_registries WHERE id = ?")) {
                projected.setString(1, registryId);
                try (ResultSet rows = projected.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("WARN", rows.getString(1));
                }
            }
            Path directory = packageDirectory("database-engine-management", target);
            TitanGraphqlPackageBinding binding = TitanGraphqlPackageBinding.read(directory);
            DatabaseGraphqlWholeRequestRuntime serving = runtime(context, target, directory, binding,
                    MANAGEMENT_ENTRY_POINT, registryId);
            GraphqlRequestContext operator = GraphqlRequestContext.legacy(1L, "operator");
            GraphqlRuntimeRequest portalRequest = new GraphqlRuntimeRequest(
                    document, "Probe", "{}", "{\"client\":\"portal\"}", false);

            JsonNode warning = JSON.readTree(serving.execute(portalRequest, operator));
            assertEquals("Query", warning.at("/data/__typename").asText(), warning::toString);
            assertEquals("REJECTED", warning.at(
                    "/extensions/warnings/0/extensions/operationRegistry/status").asText());

            store.saveOperationRegistry(new TitanGraphqlOperationRegistry(
                    registryId, warningRegistry.modelId(), warningRegistry.environment(),
                    TitanGraphqlOperationRegistry.RegistryMode.ENFORCE, List.of(rejected),
                    "2026-09-26T00:01:00Z"));
            JsonNode rejectedResponse = JSON.readTree(serving.execute(portalRequest, operator));
            assertEquals("OPERATION_REGISTRY_REJECTED", rejectedResponse.at(
                    "/errors/0/extensions/code").asText(), rejectedResponse::toString);
            assertEquals("REJECTED", rejectedResponse.at(
                    "/errors/0/extensions/operationRegistry/status").asText());

            String dialectName = target == DatabaseTarget.POSTGRESQL ? "postgresql" : "mysql";
            Path packagedDescriptor = Path.of("build/generated/proofs/database-engine-management-"
                    + dialectName + "/frontend-deployment.properties");
            Path boundDescriptor = Files.createTempFile("management-registry-serving-", ".properties");
            try {
                Files.writeString(boundDescriptor, Files.readString(packagedDescriptor)
                        + "\noperation-registry-id=" + registryId + "\n");
                GraphqlAdminHttpResource adminRoute = new GraphqlAdminHttpResource(
                        "registry-proof-token", "operator", "registry-proof-actor",
                        boundDescriptor.toString(), () -> dataSource);
                Response routed = adminRoute.postResponse(
                        Map.of("query", document, "operationName", "Probe",
                                "extensions", Map.of("client", "portal")),
                        "application/graphql-response+json", "Bearer registry-proof-token",
                        "registry-route-request", "", null);
                assertEquals(200, routed.getStatus());
                assertEquals("REJECTED", JSON.readTree((String) routed.getEntity()).at(
                        "/errors/0/extensions/operationRegistry/status").asText());
            } finally {
                Files.deleteIfExists(boundDescriptor);
            }

            JsonNode unknownClient = JSON.readTree(serving.execute(new GraphqlRuntimeRequest(
                    document, "Probe", "{}", "{\"client\":\"other\"}", false), operator));
            assertEquals("UNKNOWN", unknownClient.at(
                    "/errors/0/extensions/operationRegistry/status").asText(), unknownClient::toString);

            String deniedJobId = "00000000-0000-0000-0000-000000000099";
            String deniedMutation = "mutation { requestModelValidation(id: \"" + deniedJobId
                    + "\", draftId: \"missing\") { id } }";
            JsonNode deniedWrite = JSON.readTree(serving.execute(new GraphqlRuntimeRequest(
                    deniedMutation, "", "{}", "{}", true), operator));
            assertEquals("UNKNOWN", deniedWrite.at(
                    "/errors/0/extensions/operationRegistry/status").asText(), deniedWrite::toString);
            try (java.sql.PreparedStatement lookup = connection.prepareStatement(
                    "SELECT job_id FROM public.titan_graphql_control_jobs WHERE job_id = ?")) {
                lookup.setString(1, deniedJobId);
                try (ResultSet rows = lookup.executeQuery()) {
                    assertFalse(rows.next());
                }
            }

            TitanGraphqlOperationRegistry.RegisteredOperation approved =
                    new TitanGraphqlOperationRegistry.RegisteredOperation(
                            "registered-probe-approved", rejected.operationName(), rejected.operationHash(),
                            rejected.document(),
                            TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED,
                            rejected.roles(), rejected.clients(), rejected.depth(), rejected.estimatedCost(),
                            rejected.fieldUsage(), rejected.lastSeenAt(), rejected.approvedBy(), rejected.approvedAt());
            store.saveOperationRegistry(new TitanGraphqlOperationRegistry(
                    registryId, warningRegistry.modelId(), warningRegistry.environment(),
                    TitanGraphqlOperationRegistry.RegistryMode.ENFORCE, List.of(rejected, approved),
                    "2026-09-26T00:02:00Z"));
            JsonNode firstRejected = JSON.readTree(serving.execute(portalRequest, operator));
            assertEquals("REJECTED", firstRejected.at(
                    "/errors/0/extensions/operationRegistry/status").asText(), firstRejected::toString);
            store.saveOperationRegistry(new TitanGraphqlOperationRegistry(
                    registryId, warningRegistry.modelId(), warningRegistry.environment(),
                    TitanGraphqlOperationRegistry.RegistryMode.ENFORCE, List.of(approved, rejected),
                    "2026-09-26T00:03:00Z"));
            JsonNode allowed = JSON.readTree(serving.execute(portalRequest, operator));
            assertEquals("Query", allowed.at("/data/__typename").asText(), allowed::toString);
            assertFalse(allowed.has("errors"), allowed::toString);
            JsonNode unknownDocument = JSON.readTree(serving.execute(new GraphqlRuntimeRequest(
                    "query Else { __typename }", "Else", "{}", "{\"client\":\"portal\"}", false), operator));
            assertEquals("UNKNOWN", unknownDocument.at(
                    "/errors/0/extensions/operationRegistry/status").asText(), unknownDocument::toString);
        }
    }

    @Test
    void boundManagementReadsAndArtifactRequestCoexistWithCommerce(TitanTestContext context) throws Exception {
        TitanGraphqlModelDocument managementModel = TitanGraphqlModelDocumentYaml.parse(Files.readString(Path.of(
                "src/main/resources/graphql/management-database.titan.graphql.yaml")));
        Set<String> declaredMutations = managementModel.mutations().stream()
                .map(mutation -> mutation.name()).collect(java.util.stream.Collectors.toSet());
        for (DatabaseTarget target : List.of(DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL)) {
            Set<String> exercisedMutations = new HashSet<>();
            Connection connection = context.connection(target);
            if (target == DatabaseTarget.POSTGRESQL) {
                CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
            } else {
                CommerceDatabaseEngineDeployment.deployMySql(connection);
            }
            TitanGraphqlManagementSchemaInstaller.install(connection,
                    target == DatabaseTarget.POSTGRESQL
                            ? ManagementSchemaInstaller.Dialect.POSTGRESQL
                            : ManagementSchemaInstaller.Dialect.MYSQL);
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO management.management_drafts "
                        + "(id, workspace_id, model_id, version, status, document, document_hash, "
                        + "created_at, updated_at, metadata) VALUES "
                        + "('draft-management-proof', 'workspace-proof', 'model-proof', 1, 'validated', '{}', "
                        + "'document-hash', '2026-09-24T00:00:00Z', '2026-09-24T00:00:00Z', '{}')");
                statement.execute("INSERT INTO public.titan_graphql_control_jobs "
                        + "(job_id, job_type, request_key, payload_json, payload_sha256, status, attempt_count) "
                        + "VALUES ('00000000-0000-0000-0000-000000000001', 'artifact.generate', "
                        + "'management-job-proof', '{}', '" + "0".repeat(64) + "', 'pending', 0)");
                if (target == DatabaseTarget.POSTGRESQL) {
                    statement.execute("SET search_path TO public");
                } else {
                    statement.execute("USE public");
                }
            }
            installManagementPackage(connection, target);
            String expectedManagementRegistry = TitanGraphqlModelDocumentJson.mutationRegistryHash(managementModel);
            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery(
                            "SELECT management_graphql.mutation_registry_identity()")) {
                assertTrue(result.next());
                assertEquals(expectedManagementRegistry, result.getString(1));
            }
            assertEquals(CommerceMutationRegistryContract.expectedIdentity(),
                    CommerceMutationRegistryContract.installedIdentity(connection));
            assertFalse(expectedManagementRegistry.equals(CommerceMutationRegistryContract.expectedIdentity()));

            Path managementPackage = packageDirectory("database-engine-management", target);
            Path commercePackage = packageDirectory("database-engine-commerce", target);
            TitanGraphqlPackageBinding managementBinding = TitanGraphqlPackageBinding.read(managementPackage);
            TitanGraphqlPackageBinding commerceBinding = TitanGraphqlPackageBinding.read(commercePackage);
            assertFalse(managementBinding.modelSemanticSha256().equals(commerceBinding.modelSemanticSha256()));
            DatabaseGraphqlWholeRequestRuntime management = runtime(context, target, managementPackage,
                    managementBinding, MANAGEMENT_ENTRY_POINT);
            JsonNode introspection = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    "{ __schema { mutationType { fields { name } } } }", "", "{}", "{}", false),
                    new GraphqlRequestContext(1L, "operator", "operator-1", "", "", "",
                            List.of("management"), List.of(), true, false, false, 0L)));
            assertFalse(introspection.has("errors"), introspection::toString);
            Set<String> introspectedMutations = new HashSet<>();
            for (JsonNode field : introspection.at("/data/__schema/mutationType/fields")) {
                introspectedMutations.add(field.path("name").asText());
            }
            assertEquals(declaredMutations, introspectedMutations, target + " management introspection inventory");
            JsonNode authorized = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    "{ modelDraft(id: \"draft-management-proof\") { id workspaceId modelId version status documentHash } }",
                    "", "{}", "{}", false), GraphqlRequestContext.legacy(1L, "operator")));
            assertFalse(authorized.has("errors"), authorized::toString);
            assertEquals("draft-management-proof", authorized.at("/data/modelDraft/id").asText());
            assertEquals("workspace-proof", authorized.at("/data/modelDraft/workspaceId").asText());
            assertEquals("validated", authorized.at("/data/modelDraft/status").asText());

            String targetName = target == DatabaseTarget.POSTGRESQL ? "postgresql" : "mysql";
            GraphqlAdminHttpResource adminRoute = new GraphqlAdminHttpResource(
                    "management-proof-token", "operator", "management-proof-actor",
                    Path.of("build/generated/proofs/database-engine-management-" + targetName
                            + "/frontend-deployment.properties").toString(),
                    () -> new SingleConnectionDataSource(connection));
            Response routed = adminRoute.postResponse(
                    Map.of("query", "{ modelDraft(id: \"draft-management-proof\") { id status } }"),
                    "application/graphql-response+json", "Bearer management-proof-token",
                    "management-route-request", "management-route-key", null);
            assertEquals(200, routed.getStatus());
            JsonNode routedBody = JSON.readTree((String) routed.getEntity());
            assertFalse(routedBody.has("errors"), routedBody::toString);
            assertEquals("draft-management-proof", routedBody.at("/data/modelDraft/id").asText());
            Path previewRegistry = Files.createTempFile("management-preview-descriptors-", ".properties");
            try {
                Path previewDescriptor = temporaryDirectory.resolve(
                        "management-preview-deployment-" + targetName + ".properties");
                String previewDocument = "{ modelDraft(id: \"draft-management-proof\") { id status } }";
                String previewRegistryId = "registry-management-preview-proof";
                TitanGraphqlDurableManagementStore previewStore = new TitanGraphqlDurableManagementStore(
                        new JdbcTransactionalMutationStore(new SingleConnectionDataSource(connection)),
                        new SingleConnectionDataSource(connection));
                previewStore.saveOperationRegistry(new TitanGraphqlOperationRegistry(
                        previewRegistryId, "model-proof", "preview",
                        TitanGraphqlOperationRegistry.RegistryMode.ENFORCE,
                        List.of(new TitanGraphqlOperationRegistry.RegisteredOperation(
                                "registered-management-preview-read", "", sha256(previewDocument),
                                previewDocument, TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED,
                                List.of("operator"), List.of(), 2, 2, List.of(), Instant.now().toString(),
                                "operator", Instant.now().toString())), Instant.now().toString()));
                String previewSnapshot = DatabasePreviewDeploymentAttestation.capture(
                        connection,
                        target == DatabaseTarget.POSTGRESQL
                                ? DatabaseWholeRequestClient.Dialect.POSTGRESQL
                                : DatabaseWholeRequestClient.Dialect.MYSQL,
                        new DatabaseWholeRequestClient.EntryPoint(
                                MANAGEMENT_ENTRY_POINT.schemaName(), MANAGEMENT_ENTRY_POINT.routineName()),
                        previewRegistryId, TitanGraphqlDatabasePackageIdentity.read(managementPackage),
                        DatabaseWholeRequestClient.DEFAULT_STATEMENT_TIMEOUT_SECONDS);
                Files.writeString(previewDescriptor, Files.readString(Path.of(
                        "build/generated/proofs/database-engine-management-" + targetName
                                + "/frontend-deployment.properties"))
                        + "\npreview-build-id=management-preview-proof\npreview-expires-at="
                        + Instant.now().plusSeconds(3600) + "\noperation-registry-id="
                        + previewRegistryId + "\npreview-deployment-sha256=" + previewSnapshot + "\n");
                Files.writeString(previewRegistry, "management-preview-proof="
                        + previewDescriptor.toAbsolutePath() + "\n");
                GraphqlPreviewHttpResource previewRoute = new GraphqlPreviewHttpResource(
                        previewRegistry.toString(), () -> new SingleConnectionDataSource(connection), true);
                Response previewRead = previewRoute.postResponse(
                        "management-preview-proof",
                        Map.of("query", "{ modelDraft(id: \"draft-management-proof\") { id status } }"),
                        "application/graphql-response+json", null, "operator", null, null, null,
                        null, null, null, null, null);
                assertEquals(200, previewRead.getStatus());
                JsonNode previewBody = JSON.readTree((String) previewRead.getEntity());
                assertFalse(previewBody.has("errors"), previewBody::toString);
                assertEquals("draft-management-proof", previewBody.at("/data/modelDraft/id").asText());
                GraphqlPreviewHttpResource untrustedPreviewRoute = new GraphqlPreviewHttpResource(
                        previewRegistry.toString(), () -> new SingleConnectionDataSource(connection), false);
                Response spoofedRole = untrustedPreviewRoute.postResponse(
                        "management-preview-proof",
                        Map.of("query", "{ modelDraft(id: \"draft-management-proof\") { id } }"),
                        "application/graphql-response+json", null, "operator", null, null, null,
                        null, null, null, null, null);
                assertEquals(200, spoofedRole.getStatus());
                assertTrue(JSON.readTree((String) spoofedRole.getEntity()).has("errors"));
                Response unknownPreview = previewRoute.postResponse(
                        "unregistered-preview", Map.of("query", "{ __typename }"),
                        "application/graphql-response+json", null, "operator", null, null, null,
                        null, null, null, null, null);
                assertEquals(404, unknownPreview.getStatus());
            } finally {
                Files.deleteIfExists(previewRegistry);
            }
            String routedJobId = "00000000-0000-0000-0000-000000000010";
            String routedMutation = "mutation { requestModelValidation(id: \"" + routedJobId
                    + "\", draftId: \"draft-management-proof\") { id draftId } }";
            Response routedWrite = adminRoute.postResponse(
                    Map.of("query", routedMutation), "application/graphql-response+json",
                    "Bearer management-proof-token", "management-route-write",
                    "management-route-write-key", null);
            assertEquals(200, routedWrite.getStatus());
            JsonNode routedWriteBody = JSON.readTree((String) routedWrite.getEntity());
            assertFalse(routedWriteBody.has("errors"), routedWriteBody::toString);
            assertEquals(routedJobId, routedWriteBody.at("/data/requestModelValidation/id").asText());
            try (java.sql.PreparedStatement lookup = connection.prepareStatement(
                    "SELECT status FROM public.titan_graphql_control_jobs WHERE job_id = ?")) {
                lookup.setString(1, routedJobId);
                try (ResultSet rows = lookup.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("pending", rows.getString(1));
                }
            }
            Response forbiddenGetMutation = adminRoute.getResponse(
                    routedMutation, null, null, null, "application/graphql-response+json",
                    "Bearer management-proof-token", "management-route-get",
                    "management-route-get-key", null);
            assertEquals(200, forbiddenGetMutation.getStatus());
            assertTrue(JSON.readTree((String) forbiddenGetMutation.getEntity()).has("errors"));
            Response unauthenticated = adminRoute.postResponse(
                    Map.of("query", "{ modelDraft(id: \"draft-management-proof\") { id } }"),
                    "application/graphql-response+json", null, null, null, null);
            assertEquals(401, unauthenticated.getStatus());
            try (java.sql.PreparedStatement removeJob = connection.prepareStatement(
                    "DELETE FROM public.titan_graphql_control_jobs WHERE job_id = ?");
                    java.sql.PreparedStatement removeRequest = connection.prepareStatement(
                            "DELETE FROM management.graphql_validation_requests WHERE job_id = ?")) {
                removeJob.setString(1, routedJobId);
                assertEquals(1, removeJob.executeUpdate());
                removeRequest.setString(1, routedJobId);
                assertEquals(1, removeRequest.executeUpdate());
            }

            JsonNode denied = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    "{ modelDraft(id: \"draft-management-proof\") { id status } }", "", "{}", "{}", false),
                    GraphqlRequestContext.legacy(2L, "reader")));
            assertTrue(denied.has("errors"), denied::toString);
            assertFalse(denied.at("/data/modelDraft/id").isTextual(), denied::toString);

            JsonNode controlJob = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    "{ controlJob(id: \"00000000-0000-0000-0000-000000000001\") "
                            + "{ id type status attemptCount resultJson failureCode } }",
                    "", "{}", "{}", false), GraphqlRequestContext.legacy(4L, "operator")));
            assertFalse(controlJob.has("errors"), controlJob::toString);
            assertEquals("artifact.generate", controlJob.at("/data/controlJob/type").asText());
            assertEquals("pending", controlJob.at("/data/controlJob/status").asText());
            assertEquals(0, controlJob.at("/data/controlJob/attemptCount").asInt());
            assertTrue(controlJob.at("/data/controlJob/resultJson").isNull());

            JsonNode deniedJob = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    "{ controlJob(id: \"00000000-0000-0000-0000-000000000001\") { id status } }",
                    "", "{}", "{}", false), GraphqlRequestContext.legacy(5L, "reader")));
            assertTrue(deniedJob.has("errors"), deniedJob::toString);
            assertFalse(deniedJob.at("/data/controlJob/id").isTextual(), deniedJob::toString);

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE public.titan_graphql_control_jobs "
                        + "SET status = 'succeeded', attempt_count = 1, "
                        + "result_json = '{\"artifactSetId\":\"artifact-proof\"}' "
                        + "WHERE job_id = '00000000-0000-0000-0000-000000000001'");
            }
            JsonNode completedJob = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    "{ controlJob(id: \"00000000-0000-0000-0000-000000000001\") "
                            + "{ status attemptCount resultJson } }",
                    "", "{}", "{}", false), GraphqlRequestContext.legacy(6L, "operator")));
            assertFalse(completedJob.has("errors"), completedJob::toString);
            assertEquals("succeeded", completedJob.at("/data/controlJob/status").asText());
            assertEquals(1, completedJob.at("/data/controlJob/attemptCount").asInt());
            assertEquals("{\"artifactSetId\":\"artifact-proof\"}",
                    completedJob.at("/data/controlJob/resultJson").asText());

            SingleConnectionDataSource managementDataSource = new SingleConnectionDataSource(connection);
            TitanGraphqlDurableManagementStore store = new TitanGraphqlDurableManagementStore(
                    new JdbcTransactionalMutationStore(managementDataSource), managementDataSource);
            TitanGraphqlObservedOperation observed = TitanGraphqlObservedOperation.observed(
                    "model-management-generated", "development", "operator", "portal", "operation-proof",
                    "ReadDraft", "query ReadDraft { modelDraft(id: \"draft-management-proof\") { id } }",
                    2, 3, List.of("ManagedDraft.id"), "2026-09-26T00:00:00Z");
            store.saveObservedOperation(observed);
            JsonNode observedRead = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    "{ observedOperation(id: \"" + observed.id() + "\") "
                            + "{ id modelId environment role client operationHash status depth "
                            + "estimatedCost observedCount fieldUsageJson } }",
                    "", "{}", "{}", false), GraphqlRequestContext.legacy(9L, "operator")));
            assertFalse(observedRead.has("errors"), observedRead::toString);
            assertEquals(observed.id(), observedRead.at("/data/observedOperation/id").asText());
            assertEquals("OBSERVED", observedRead.at("/data/observedOperation/status").asText());
            assertEquals("[\"ManagedDraft.id\"]",
                    observedRead.at("/data/observedOperation/fieldUsageJson").asText());
            JsonNode deniedObserved = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    "{ observedOperation(id: \"" + observed.id() + "\") { id status } }",
                    "", "{}", "{}", false), GraphqlRequestContext.legacy(10L, "reader")));
            assertTrue(deniedObserved.has("errors"), deniedObserved::toString);
            String reviewJobId = "00000000-0000-0000-0000-000000000011";
            String reviewDocument = "mutation { requestObservedOperationReview(id: \"" + reviewJobId
                    + "\", observedOperationId: \"" + observed.id()
                    + "\", decision: \"approve\") { id observedOperationId decision } }";
            GraphqlRuntimeRequest reviewMutation = new GraphqlRuntimeRequest(
                    reviewDocument, "", "{}", "{}", true);
            GraphqlRequestContext reviewContext = new GraphqlRequestContext(
                    9L, "operator", "reviewer-9", "", "review-request", "review-key",
                    List.of("management"), List.of(), false, false, false, 0L);
            JsonNode reviewRequest = JSON.readTree(management.execute(reviewMutation, reviewContext));
            assertFalse(reviewRequest.has("errors"), reviewRequest::toString);
            assertEquals(reviewJobId, reviewRequest.at("/data/requestObservedOperationReview/id").asText());
            exercisedMutations.add("requestObservedOperationReview");
            assertEquals(reviewRequest, JSON.readTree(management.execute(reviewMutation, reviewContext)));
            String deniedReviewId = "00000000-0000-0000-0000-000000000012";
            JsonNode deniedReview = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    reviewDocument.replace(reviewJobId, deniedReviewId), "", "{}", "{}", true),
                    GraphqlRequestContext.legacy(10L, "reader")));
            assertTrue(deniedReview.has("errors"), deniedReview::toString);
            String invalidReviewId = "00000000-0000-0000-0000-000000000013";
            JsonNode invalidReview = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    reviewDocument.replace(reviewJobId, invalidReviewId)
                            .replace("decision: \"approve\"", "decision: \"other\""),
                    "", "{}", "{}", true), reviewContext));
            assertTrue(invalidReview.has("errors"), invalidReview::toString);
            TitanGraphqlControlJobQueue reviewQueue = new TitanGraphqlControlJobQueue(
                    managementDataSource,
                    target == DatabaseTarget.POSTGRESQL
                            ? TitanGraphqlControlJobQueue.Dialect.POSTGRESQL
                            : TitanGraphqlControlJobQueue.Dialect.MYSQL,
                    Clock.systemUTC());
            assertTrue(reviewQueue.find(deniedReviewId).isEmpty());
            assertTrue(reviewQueue.find(invalidReviewId).isEmpty());
            assertTrue(new TitanGraphqlOperationReviewJobRunner(
                    reviewQueue,
                    () -> new TitanGraphqlDurableManagementStore(
                            new JdbcTransactionalMutationStore(managementDataSource), managementDataSource),
                    Clock.systemUTC()).runOne(Duration.ofMinutes(1)));
            TitanGraphqlControlJobQueue.JobState reviewJob = reviewQueue.find(reviewJobId).orElseThrow();
            assertEquals(TitanGraphqlControlJobQueue.Status.SUCCEEDED, reviewJob.status());
            assertEquals("APPROVED", JSON.readTree(reviewJob.resultJson()).path("status").asText());
            JsonNode approvedRead = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    "{ observedOperation(id: \"" + observed.id() + "\") { id status } }",
                    "", "{}", "{}", false), GraphqlRequestContext.legacy(11L, "operator")));
            assertFalse(approvedRead.has("errors"), approvedRead::toString);
            assertEquals("APPROVED", approvedRead.at("/data/observedOperation/status").asText());
            String operationRegistryId = "registry-model-management-generated-development";
            String registryDocument = "{ operationRegistry(id: \"" + operationRegistryId
                    + "\") { id modelId environment mode operationsJson } }";
            JsonNode approvedRegistry = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    registryDocument, "", "{}", "{}", false), GraphqlRequestContext.legacy(9L, "operator")));
            assertFalse(approvedRegistry.has("errors"), approvedRegistry::toString);
            assertEquals(operationRegistryId, approvedRegistry.at("/data/operationRegistry/id").asText());
            assertEquals("OBSERVE", approvedRegistry.at("/data/operationRegistry/mode").asText());
            assertEquals("APPROVED", JSON.readTree(
                    approvedRegistry.at("/data/operationRegistry/operationsJson").asText()).get(0)
                    .path("status").asText());
            JsonNode deniedRegistry = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    registryDocument, "", "{}", "{}", false), GraphqlRequestContext.legacy(10L, "reader")));
            assertTrue(deniedRegistry.has("errors"), deniedRegistry::toString);
            TitanGraphqlObservedOperation rejectedCandidate = TitanGraphqlObservedOperation.observed(
                    "model-management-generated", "development", "operator", "portal", "operation-reject-proof",
                    "ReadAnotherDraft", "query ReadAnotherDraft { modelDraft(id: \"other\") { id } }",
                    2, 3, List.of("ManagedDraft.id"), "2026-09-26T00:02:00Z");
            store.saveObservedOperation(rejectedCandidate);
            String rejectJobId = "00000000-0000-0000-0000-000000000014";
            GraphqlRequestContext rejectContext = new GraphqlRequestContext(
                    9L, "operator", "reviewer-9", "", "reject-request", "reject-key",
                    List.of("management"), List.of(), false, false, false, 0L);
            JsonNode rejectRequest = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    reviewDocument.replace(reviewJobId, rejectJobId)
                            .replace(observed.id(), rejectedCandidate.id())
                            .replace("decision: \"approve\"", "decision: \"reject\""),
                    "", "{}", "{}", true), rejectContext));
            assertFalse(rejectRequest.has("errors"), rejectRequest::toString);
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE management.graphql_product_state "
                        + "ADD CONSTRAINT block_review_journal CHECK ("
                        + "entry_json NOT LIKE '%\"type\":\"operationReview\"%' "
                        + "OR entry_json NOT LIKE '%" + rejectedCandidate.id() + "%')");
            }
            try {
                assertThrows(IllegalStateException.class, () -> new TitanGraphqlOperationReviewJobRunner(
                        reviewQueue,
                        () -> new TitanGraphqlDurableManagementStore(
                                new JdbcTransactionalMutationStore(managementDataSource), managementDataSource),
                        Clock.systemUTC()).runOne(Duration.ofMinutes(1)));
                assertEquals(TitanGraphqlControlJobQueue.Status.PENDING,
                        reviewQueue.find(rejectJobId).orElseThrow().status());
                TitanGraphqlDurableManagementStore afterFailedReview = new TitanGraphqlDurableManagementStore(
                        new JdbcTransactionalMutationStore(managementDataSource), managementDataSource);
                assertEquals(TitanGraphqlObservedOperation.ObservedOperationStatus.OBSERVED,
                        afterFailedReview.observedOperation(rejectedCandidate.id()).status());
                JsonNode unchangedRegistry = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                        registryDocument, "", "{}", "{}", false), GraphqlRequestContext.legacy(9L, "operator")));
                assertFalse(unchangedRegistry.has("errors"), unchangedRegistry::toString);
                assertEquals(1, JSON.readTree(
                        unchangedRegistry.at("/data/operationRegistry/operationsJson").asText()).size());
                try (Statement lookup = connection.createStatement();
                        ResultSet rows = lookup.executeQuery(
                                "SELECT COUNT(*) FROM management.graphql_registry_operations "
                                        + "WHERE registry_id = '" + operationRegistryId + "'")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                }
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE management.graphql_product_state "
                            + "DROP CONSTRAINT block_review_journal");
                }
            }
            assertTrue(new TitanGraphqlOperationReviewJobRunner(
                    reviewQueue,
                    () -> new TitanGraphqlDurableManagementStore(
                            new JdbcTransactionalMutationStore(managementDataSource), managementDataSource),
                    Clock.systemUTC()).runOne(Duration.ofMinutes(1)));
            assertEquals(TitanGraphqlControlJobQueue.Status.SUCCEEDED,
                    reviewQueue.find(rejectJobId).orElseThrow().status());
            assertEquals(TitanGraphqlObservedOperation.ObservedOperationStatus.REJECTED,
                    new TitanGraphqlDurableManagementStore(
                            new JdbcTransactionalMutationStore(managementDataSource), managementDataSource)
                            .observedOperation(rejectedCandidate.id()).status());
            JsonNode reviewedRegistry = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    registryDocument, "", "{}", "{}", false), GraphqlRequestContext.legacy(9L, "operator")));
            assertFalse(reviewedRegistry.has("errors"), reviewedRegistry::toString);
            JsonNode registryOperations = JSON.readTree(
                    reviewedRegistry.at("/data/operationRegistry/operationsJson").asText());
            assertEquals(2, registryOperations.size());
            assertTrue(registryOperations.findValuesAsText("status").containsAll(List.of("APPROVED", "REJECTED")));
            try (Statement lookup = connection.createStatement();
                    ResultSet rows = lookup.executeQuery(
                            "SELECT COUNT(*) FROM management.graphql_registry_operations "
                                    + "WHERE registry_id = '" + operationRegistryId + "'")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
            TitanGraphqlDurableManagementStore reviewedStore = new TitanGraphqlDurableManagementStore(
                    new JdbcTransactionalMutationStore(managementDataSource), managementDataSource);
            TitanGraphqlOperationRegistry persistedRegistry = reviewedStore.operationRegistry(operationRegistryId);
            reviewedStore.saveOperationRegistry(new TitanGraphqlOperationRegistry(
                    persistedRegistry.id(), persistedRegistry.modelId(), persistedRegistry.environment(),
                    TitanGraphqlOperationRegistry.RegistryMode.ENFORCE, persistedRegistry.operations(),
                    "2026-09-26T00:04:00Z"));
            DatabaseGraphqlWholeRequestRuntime reviewedServing = new DatabaseGraphqlWholeRequestRuntime(
                    target == DatabaseTarget.POSTGRESQL
                            ? DatabaseGraphqlWholeRequestRuntime.Dialect.POSTGRESQL
                            : DatabaseGraphqlWholeRequestRuntime.Dialect.MYSQL,
                    () -> context.openAdditionalConnection(target),
                    managementBinding.modelSemanticSha256(),
                    TitanGraphqlDatabaseRuntimeIdentity.read(managementPackage),
                    TitanGraphqlDatabasePackageIdentity.read(managementPackage),
                    "reviewed management registry on a fresh connection", MANAGEMENT_ENTRY_POINT,
                    operationRegistryId);
            JsonNode approvedServing = JSON.readTree(reviewedServing.execute(new GraphqlRuntimeRequest(
                    observed.document(), observed.operationName(), "{}", "{\"client\":\"portal\"}", false),
                    GraphqlRequestContext.legacy(12L, "operator")));
            assertEquals("draft-management-proof", approvedServing.at("/data/modelDraft/id").asText(),
                    approvedServing::toString);
            JsonNode rejectedServing = JSON.readTree(reviewedServing.execute(new GraphqlRuntimeRequest(
                    rejectedCandidate.document(), rejectedCandidate.operationName(), "{}",
                    "{\"client\":\"portal\"}", false), GraphqlRequestContext.legacy(12L, "operator")));
            assertEquals("REJECTED", rejectedServing.at(
                    "/errors/0/extensions/operationRegistry/status").asText(), rejectedServing::toString);
            String generatedDraftId = "draft-management-generated";
            String source = Files.readString(Path.of("src/test/resources/graphql/management.titan.graphql.yaml"));
            TitanGraphqlModelDocument sourceModel = TitanGraphqlModelDocumentYaml.parse(source);
            store.saveWorkspace(new TitanGraphqlManagedWorkspace(
                    "workspace-management-generated", "platform", "", "development", "", ""));
            store.saveModel(new TitanGraphqlManagedModel(
                    "model-management-generated", "workspace-management-generated", sourceModel.metadata().name(),
                    "Management", "", generatedDraftId, "", "validation-" + generatedDraftId, "", "", ""));
            store.saveDraft(new TitanGraphqlModelDraft(
                    generatedDraftId, "model-management-generated", TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED,
                    TitanGraphqlModelDraft.SourceFormat.YAML, source,
                    TitanGraphqlModelDocumentJson.canonicalJson(sourceModel),
                    TitanGraphqlModelDocumentJson.semanticHash(sourceModel),
                    "validation-" + generatedDraftId, "", "", "operator", "", ""));
            store.saveValidationReport(new TitanGraphqlValidationReportRef(
                    "validation-" + generatedDraftId, generatedDraftId,
                    TitanGraphqlValidationReportRef.ValidationReportStatus.PASS,
                    "validation passed", 0, 0, 0, false, ""));
            String generatedJobId = "00000000-0000-0000-0000-000000000002";
            JsonNode generatedRequest = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    "mutation { requestArtifactGeneration(id: \"" + generatedJobId
                            + "\", draftId: \"" + generatedDraftId
                            + "\", generationProfile: \"development\", enableIntrospection: true) { id } }",
                    "", "{}", "{}", true), GraphqlRequestContext.legacy(7L, "operator")));
            assertFalse(generatedRequest.has("errors"), generatedRequest::toString);
            assertEquals(generatedJobId, generatedRequest.at("/data/requestArtifactGeneration/id").asText());
            exercisedMutations.add("requestArtifactGeneration");
            TitanGraphqlControlJobQueue queue = new TitanGraphqlControlJobQueue(
                    managementDataSource,
                    target == DatabaseTarget.POSTGRESQL
                            ? TitanGraphqlControlJobQueue.Dialect.POSTGRESQL
                            : TitanGraphqlControlJobQueue.Dialect.MYSQL,
                    Clock.systemUTC());
            assertTrue(new TitanGraphqlArtifactGenerationJobRunner(queue,
                    new TitanGraphqlArtifactGenerationService(store)).runOne(Duration.ofMinutes(1)));
            JsonNode generatedJob = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    "{ controlJob(id: \"" + generatedJobId + "\") { id status attemptCount resultJson } }",
                    "", "{}", "{}", false), GraphqlRequestContext.legacy(7L, "operator")));
            assertFalse(generatedJob.has("errors"), generatedJob::toString);
            assertEquals("succeeded", generatedJob.at("/data/controlJob/status").asText());
            assertEquals(1, generatedJob.at("/data/controlJob/attemptCount").asInt());
            assertEquals("artifact-" + generatedDraftId,
                    JSON.readTree(generatedJob.at("/data/controlJob/resultJson").asText())
                            .path("artifactSetId").asText());
            assertEquals("artifact-" + generatedDraftId, store.draft(generatedDraftId).artifactSetId());

            String validationJobId = "00000000-0000-0000-0000-00000000000a";
            String validationDocument = "mutation { requestModelValidation(id: \"" + validationJobId
                    + "\", draftId: \"" + generatedDraftId + "\") { id draftId } }";
            GraphqlRequestContext validationContext = new GraphqlRequestContext(7L, "operator", "operator-7", "",
                    "validation-request", "validation-request-key", List.of("management"), List.of(),
                    false, false, false, 0L);
            GraphqlRuntimeRequest validationMutation = new GraphqlRuntimeRequest(
                    validationDocument, "", "{}", "{}", true);
            JsonNode validationRequest = JSON.readTree(management.execute(validationMutation, validationContext));
            assertFalse(validationRequest.has("errors"), validationRequest::toString);
            assertEquals(validationJobId, validationRequest.at("/data/requestModelValidation/id").asText());
            exercisedMutations.add("requestModelValidation");
            assertEquals(validationRequest, JSON.readTree(management.execute(validationMutation, validationContext)));
            TitanGraphqlModelValidationJobRunner validationRunner = new TitanGraphqlModelValidationJobRunner(
                    queue, new TitanGraphqlModelValidationService(store));
            String hideProductState = target == DatabaseTarget.POSTGRESQL
                    ? "ALTER TABLE management.graphql_product_state RENAME TO graphql_product_state_held"
                    : "RENAME TABLE management.graphql_product_state "
                            + "TO management.graphql_product_state_held";
            String restoreProductState = target == DatabaseTarget.POSTGRESQL
                    ? "ALTER TABLE management.graphql_product_state_held RENAME TO graphql_product_state"
                    : "RENAME TABLE management.graphql_product_state_held "
                            + "TO management.graphql_product_state";
            try (Statement statement = connection.createStatement()) {
                statement.execute(hideProductState);
            }
            try {
                assertThrows(RuntimeException.class, () -> validationRunner.runOne(Duration.ofMinutes(1)));
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(restoreProductState);
                }
            }
            assertEquals(TitanGraphqlControlJobQueue.Status.PENDING,
                    queue.find(validationJobId).orElseThrow().status());
            assertEquals(TitanGraphqlModelDraft.ModelDraftStatus.READY_FOR_REVIEW,
                    store.draft(generatedDraftId).status());
            assertTrue(validationRunner.runOne(Duration.ofMinutes(1)));
            JsonNode validationJob = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    "{ controlJob(id: \"" + validationJobId + "\") { id type status resultJson } }",
                    "", "{}", "{}", false), GraphqlRequestContext.legacy(7L, "operator")));
            assertFalse(validationJob.has("errors"), validationJob::toString);
            assertEquals("model.validate", validationJob.at("/data/controlJob/type").asText());
            assertEquals("succeeded", validationJob.at("/data/controlJob/status").asText());
            JsonNode validationResult = JSON.readTree(validationJob.at("/data/controlJob/resultJson").asText());
            assertTrue(validationResult.path("accepted").asBoolean());
            assertEquals("validation-" + generatedDraftId,
                    validationResult.path("validationReportId").asText());
            assertEquals(TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED,
                    store.draft(generatedDraftId).status());
            String missingValidationId = "00000000-0000-0000-0000-00000000000b";
            JsonNode missingValidation = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    validationDocument.replace(validationJobId, missingValidationId)
                            .replace(generatedDraftId, "draft-does-not-exist"),
                    "", "{}", "{}", true), GraphqlRequestContext.legacy(7L, "operator")));
            assertTrue(missingValidation.has("errors"), missingValidation::toString);
            String deniedValidationId = "00000000-0000-0000-0000-00000000000c";
            JsonNode deniedValidation = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    validationDocument.replace(validationJobId, deniedValidationId),
                    "", "{}", "{}", true), GraphqlRequestContext.legacy(8L, "reader")));
            assertTrue(deniedValidation.has("errors"), deniedValidation::toString);
            try (Statement statement = connection.createStatement();
                    ResultSet requests = statement.executeQuery("SELECT COUNT(*) "
                            + "FROM management.graphql_validation_requests WHERE job_id IN ('"
                            + missingValidationId + "', '" + deniedValidationId + "')")) {
                assertTrue(requests.next());
                assertEquals(0L, requests.getLong(1));
            }

            String transitioningDraftId = "draft-management-validation-transition";
            store.saveDraft(new TitanGraphqlModelDraft(
                    transitioningDraftId, "model-management-generated",
                    TitanGraphqlModelDraft.ModelDraftStatus.IMPORTED,
                    TitanGraphqlModelDraft.SourceFormat.YAML, source,
                    TitanGraphqlModelDocumentJson.canonicalJson(sourceModel),
                    TitanGraphqlModelDocumentJson.semanticHash(sourceModel),
                    "", "", "", "operator", "", ""));
            String transitionQuery = "{ modelDraft(id: \"" + transitioningDraftId + "\") { status } }";
            JsonNode beforeTransition = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    transitionQuery, "", "{}", "{}", false), GraphqlRequestContext.legacy(7L, "operator")));
            assertEquals("imported", beforeTransition.at("/data/modelDraft/status").asText(),
                    beforeTransition::toString);
            String transitionJobId = "00000000-0000-0000-0000-00000000001c";
            JsonNode transitionRequest = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    "mutation { requestModelValidation(id: \"" + transitionJobId
                            + "\", draftId: \"" + transitioningDraftId + "\") { id } }",
                    "", "{}", "{}", true), GraphqlRequestContext.legacy(7L, "operator")));
            assertEquals(transitionJobId, transitionRequest.at("/data/requestModelValidation/id").asText(),
                    transitionRequest::toString);
            try (Statement statement = connection.createStatement()) {
                statement.execute(hideProductState);
            }
            try {
                assertThrows(RuntimeException.class, () -> validationRunner.runOne(Duration.ofMinutes(1)));
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(restoreProductState);
                }
            }
            JsonNode rolledBackTransition = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    transitionQuery, "", "{}", "{}", false), GraphqlRequestContext.legacy(7L, "operator")));
            assertEquals("imported", rolledBackTransition.at("/data/modelDraft/status").asText(),
                    rolledBackTransition::toString);
            assertEquals(TitanGraphqlControlJobQueue.Status.PENDING,
                    queue.find(transitionJobId).orElseThrow().status());
            assertTrue(validationRunner.runOne(Duration.ofMinutes(1)));
            JsonNode afterTransition = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    transitionQuery, "", "{}", "{}", false), GraphqlRequestContext.legacy(7L, "operator")));
            assertEquals("validated", afterTransition.at("/data/modelDraft/status").asText(),
                    afterTransition::toString);
            assertEquals(TitanGraphqlControlJobQueue.Status.SUCCEEDED,
                    queue.find(transitionJobId).orElseThrow().status());

            String importSource = source.replace("name: titan-graphql-management",
                    "name: management-import-proof");
            String importJobId = "00000000-0000-0000-0000-00000000000d";
            String importDocument = "mutation Import($yaml: String!) { requestModelImport(id: \""
                    + importJobId + "\", workspaceId: \"workspace-management-generated\", yaml: $yaml) "
                    + "{ id workspaceId } }";
            GraphqlRuntimeRequest importMutation = new GraphqlRuntimeRequest(
                    importDocument, "Import", JSON.writeValueAsString(java.util.Map.of("yaml", importSource)),
                    "{}", true);
            GraphqlRequestContext importContext = new GraphqlRequestContext(
                    7L, "operator", "operator-7", "", "model-import-request", "model-import-key",
                    List.of("management"), List.of(), false, false, false, 0L);
            JsonNode importedRequest = JSON.readTree(management.execute(importMutation, importContext));
            assertFalse(importedRequest.has("errors"), importedRequest::toString);
            assertEquals(importJobId, importedRequest.at("/data/requestModelImport/id").asText());
            exercisedMutations.add("requestModelImport");
            assertEquals("workspace-management-generated",
                    importedRequest.at("/data/requestModelImport/workspaceId").asText());
            assertEquals(importedRequest, JSON.readTree(management.execute(importMutation, importContext)));
            assertEquals(TitanGraphqlControlJobQueue.Status.PENDING,
                    queue.find(importJobId).orElseThrow().status());
            JsonNode queuedImport;
            try (java.sql.PreparedStatement lookup = connection.prepareStatement(
                    "SELECT payload_json FROM public.titan_graphql_control_jobs WHERE job_id = ?")) {
                lookup.setString(1, importJobId);
                try (ResultSet rows = lookup.executeQuery()) {
                    assertTrue(rows.next());
                    queuedImport = JSON.readTree(rows.getString(1));
                }
            }
            assertEquals("operator-7", queuedImport.path("actorKey").asText());
            assertEquals("model-import-request", queuedImport.path("requestId").asText());
            assertEquals(importSource, queuedImport.path("yaml").asText());
            String deniedImportId = "00000000-0000-0000-0000-00000000000e";
            JsonNode deniedImport = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    importDocument.replace(importJobId, deniedImportId), "Import",
                    importMutation.variablesJson(), "{}", true), GraphqlRequestContext.legacy(8L, "reader")));
            assertTrue(deniedImport.has("errors"), deniedImport::toString);
            assertTrue(queue.find(deniedImportId).isEmpty());
            String missingContextImportId = "00000000-0000-0000-0000-00000000000f";
            JsonNode missingContextImport = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    importDocument.replace(importJobId, missingContextImportId), "Import",
                    importMutation.variablesJson(), "{}", true), GraphqlRequestContext.legacy(8L, "operator")));
            assertTrue(missingContextImport.has("errors"), missingContextImport::toString);
            assertTrue(queue.find(missingContextImportId).isEmpty());
            TitanGraphqlModelImportJobRunner importRunner = new TitanGraphqlModelImportJobRunner(
                    queue, () -> new TitanGraphqlModelImportService(store));
            String importedDraftId = "draft-" + TitanGraphqlModelDocumentJson.semanticHash(
                    TitanGraphqlModelDocumentYaml.parse(importSource))
                    .substring(0, 12);
            try (Statement statement = connection.createStatement()) {
                statement.execute(hideProductState);
            }
            try {
                assertThrows(RuntimeException.class, () -> importRunner.runOne(Duration.ofMinutes(1)));
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(restoreProductState);
                }
            }
            assertEquals(TitanGraphqlControlJobQueue.Status.PENDING,
                    queue.find(importJobId).orElseThrow().status());
            assertTrue(store.draft(importedDraftId) == null);
            assertEquals(0L, coreDraftCount(connection, importedDraftId));
            assertTrue(importRunner.runOne(Duration.ofMinutes(1)));
            assertEquals(TitanGraphqlControlJobQueue.Status.SUCCEEDED,
                    queue.find(importJobId).orElseThrow().status());
            assertEquals(importedDraftId,
                    JSON.readTree(queue.find(importJobId).orElseThrow().resultJson())
                            .path("draftId").asText());
            assertEquals(importSource, store.draft(importedDraftId).sourceText());
            assertEquals(1L, coreDraftCount(connection, importedDraftId));

            String jobId = "00000000-0000-0000-0000-000000000003";
            String requestDocument = "mutation { requestArtifactGeneration(id: \"" + jobId
                    + "\", draftId: \"draft-management-proof\", generationProfile: \"development\", "
                    + "enableIntrospection: false) { id draftId generationProfile enableIntrospection } }";
            GraphqlRequestContext operator = new GraphqlRequestContext(7L, "operator", "operator-7", "",
                    "artifact-request", "artifact-request-key", List.of("management"), List.of(),
                    false, false, false, 0L);
            GraphqlRuntimeRequest request = new GraphqlRuntimeRequest(requestDocument, "", "{}", "{}", true);
            JsonNode requested = JSON.readTree(management.execute(request, operator));
            assertFalse(requested.has("errors"), requested::toString);
            assertEquals(jobId, requested.at("/data/requestArtifactGeneration/id").asText());
            assertEquals("draft-management-proof",
                    requested.at("/data/requestArtifactGeneration/draftId").asText());
            assertFalse(requested.at("/data/requestArtifactGeneration/enableIntrospection").asBoolean());
            assertEquals(requested, JSON.readTree(management.execute(request, operator)));
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT job_type, request_key, payload_json, "
                            + "payload_sha256, status FROM public.titan_graphql_control_jobs WHERE job_id = '"
                            + jobId + "'")) {
                assertTrue(rows.next());
                assertEquals("artifact.generate", rows.getString("job_type"));
                assertEquals(jobId, rows.getString("request_key"));
                assertEquals("pending", rows.getString("status"));
                String payload = rows.getString("payload_json");
                assertEquals("draft-management-proof", JSON.readTree(payload).path("draftId").asText());
                assertEquals("development", JSON.readTree(payload).path("generationProfile").asText());
                assertFalse(JSON.readTree(payload).path("enableIntrospection").asBoolean());
                assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(payload.getBytes(StandardCharsets.UTF_8))), rows.getString("payload_sha256"));
                assertFalse(rows.next());
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT draft_id, generation_profile, "
                            + "enable_introspection FROM management.graphql_artifact_requests WHERE job_id = '"
                            + jobId + "'")) {
                assertTrue(rows.next());
                assertEquals("draft-management-proof", rows.getString("draft_id"));
                assertEquals("development", rows.getString("generation_profile"));
                assertFalse(rows.getBoolean("enable_introspection"));
                assertFalse(rows.next());
            }
            JsonNode conflicting = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    requestDocument.replace("development", "release"), "", "{}", "{}", true), operator));
            assertTrue(conflicting.has("errors"), conflicting::toString);
            String deniedId = "00000000-0000-0000-0000-000000000004";
            JsonNode deniedRequest = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    requestDocument.replace(jobId, deniedId), "", "{}", "{}", true),
                    GraphqlRequestContext.legacy(8L, "reader")));
            assertTrue(deniedRequest.has("errors"), deniedRequest::toString);
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM public.titan_graphql_control_jobs "
                            + "WHERE job_id = '" + deniedId + "'")) {
                assertTrue(rows.next());
                assertEquals(0L, rows.getLong(1));
            }
            String missingId = "00000000-0000-0000-0000-000000000007";
            JsonNode missingDraft = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    requestDocument.replace(jobId, missingId)
                            .replace("draft-management-proof", "draft-does-not-exist"),
                    "", "{}", "{}", true), GraphqlRequestContext.legacy(7L, "operator")));
            assertTrue(missingDraft.has("errors"), missingDraft::toString);
            String rolledBackId = "00000000-0000-0000-0000-000000000008";
            String laterFailureId = "00000000-0000-0000-0000-000000000009";
            String laterFailure = "mutation { first: requestArtifactGeneration(id: \"" + rolledBackId
                    + "\", draftId: \"draft-management-proof\", generationProfile: \"development\", "
                    + "enableIntrospection: false) { id } second: requestArtifactGeneration(id: \""
                    + laterFailureId + "\", draftId: \"draft-does-not-exist\", "
                    + "generationProfile: \"development\", enableIntrospection: false) { id } }";
            JsonNode rolledBack = JSON.readTree(management.execute(new GraphqlRuntimeRequest(
                    laterFailure, "", "{}", "{}", true), GraphqlRequestContext.legacy(7L, "operator")));
            assertTrue(rolledBack.has("errors"), rolledBack::toString);
            try (Statement statement = connection.createStatement();
                    ResultSet jobs = statement.executeQuery("SELECT COUNT(*) FROM public.titan_graphql_control_jobs "
                            + "WHERE job_id IN ('" + missingId + "', '" + rolledBackId + "', '"
                            + laterFailureId + "')")) {
                assertTrue(jobs.next());
                assertEquals(0L, jobs.getLong(1));
            }
            try (Statement statement = connection.createStatement();
                    ResultSet requests = statement.executeQuery("SELECT COUNT(*) "
                            + "FROM management.graphql_artifact_requests WHERE job_id IN ('" + missingId
                            + "', '" + rolledBackId + "', '" + laterFailureId + "')")) {
                assertTrue(requests.next());
                assertEquals(0L, requests.getLong(1));
            }

            DatabaseGraphqlWholeRequestRuntime commerce = runtime(context, target, commercePackage,
                    commerceBinding, DatabaseGraphqlWholeRequestRuntime.DEFAULT_ENTRY_POINT);
            JsonNode commerceRead = JSON.readTree(commerce.execute(new GraphqlRuntimeRequest(
                    "{ customer(id: 7) { id name } }", "", "{}", "{}", false),
                    GraphqlRequestContext.legacy(3L, "reader")));
            assertEquals("Northwind", commerceRead.at("/data/customer/name").asText(), commerceRead::toString);
            assertEquals(declaredMutations, exercisedMutations, target + " management mutation inventory");
        }
    }

    private static long coreDraftCount(Connection connection, String draftId) throws Exception {
        try (java.sql.PreparedStatement lookup = connection.prepareStatement(
                "SELECT COUNT(*) FROM management.management_drafts WHERE id = ?")) {
            lookup.setString(1, draftId);
            try (ResultSet rows = lookup.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static DatabaseGraphqlWholeRequestRuntime runtime(
            TitanTestContext context,
            DatabaseTarget target,
            Path directory,
            TitanGraphqlPackageBinding binding,
            DatabaseGraphqlWholeRequestRuntime.EntryPoint entryPoint
    ) {
        return runtime(context, target, directory, binding, entryPoint, "");
    }

    private static DatabaseGraphqlWholeRequestRuntime runtime(
            TitanTestContext context,
            DatabaseTarget target,
            Path directory,
            TitanGraphqlPackageBinding binding,
            DatabaseGraphqlWholeRequestRuntime.EntryPoint entryPoint,
            String operationRegistryId
    ) {
        return new DatabaseGraphqlWholeRequestRuntime(
                target == DatabaseTarget.POSTGRESQL
                        ? DatabaseGraphqlWholeRequestRuntime.Dialect.POSTGRESQL
                        : DatabaseGraphqlWholeRequestRuntime.Dialect.MYSQL,
                () -> context.connection(target),
                binding.modelSemanticSha256(),
                TitanGraphqlDatabaseRuntimeIdentity.read(directory),
                TitanGraphqlDatabasePackageIdentity.read(directory),
                "management package coexistence test", entryPoint, operationRegistryId);
    }

    private static void installManagementPackage(Connection connection, DatabaseTarget target) throws Exception {
        String dialect = target == DatabaseTarget.POSTGRESQL ? "postgresql" : "mysql";
        Path migrations = packageDirectory("database-engine-management", target).resolve(dialect);
        for (String name : List.of("R__titan_010_runtime.sql", "R__titan_020_routines.sql")) {
            Path migration = migrations.resolve(name);
            if (target == DatabaseTarget.POSTGRESQL) {
                CommerceDatabaseEngineDeployment.executePostgreSqlScript(connection, migration);
            } else {
                CommerceDatabaseEngineDeployment.executeMySqlScript(connection, migration);
            }
        }
    }

    private static Path packageDirectory(String prefix, DatabaseTarget target) {
        String dialect = target == DatabaseTarget.POSTGRESQL ? "postgresql" : "mysql";
        String packageRoot = prefix.equals("database-engine-commerce") && target == DatabaseTarget.POSTGRESQL
                ? prefix : prefix + "-" + dialect;
        return Path.of("build/generated/proofs/" + packageRoot + "/package");
    }
}
