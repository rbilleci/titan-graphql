package io.titan.graphql.codegen;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.titan.graphql.artifact.TitanGraphqlArtifactsDirectory;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.graphql.artifact.TitanGraphqlPackageBinding;
import io.titan.graphql.management.TitanGraphqlArtifactSetRef;
import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import io.titan.graphql.management.TitanGraphqlManagedModel;
import io.titan.graphql.management.TitanGraphqlManagedWorkspace;
import io.titan.graphql.management.TitanGraphqlManagementSchemaInstaller;
import io.titan.graphql.management.TitanGraphqlModelDraft;
import io.titan.graphql.management.TitanGraphqlOperationRegistry;
import io.titan.graphql.management.TitanGraphqlPreviewPublicationService;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import io.titan.management.JdbcTransactionalMutationStore;
import io.titan.management.ManagementSchemaInstaller;
import io.titan.runtime.jdbc.SingleConnectionDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

public final class PreviewCandidateHttpServingResource implements QuarkusTestResourceLifecycleManager {
    private static final String OPERATION = "query Probe { __typename }";
    private static final String RESTRICTED_OPERATION = "query PrivateProbe { __typename }";

    private PostgreSQLContainer<?> container;
    private Path deploymentDirectory;

    @Override
    public Map<String, String> start() {
        container = new PostgreSQLContainer<>("postgres:16");
        container.start();
        try (Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword())) {
            Path packageDirectory = Path.of(System.getProperty(
                    "titan.graphql.preview.candidate.package.postgresql"));
            String source = Files.readString(Path.of(System.getProperty(
                    "titan.graphql.preview.candidate.model")));
            TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(source);
            TitanGraphqlPackageBinding binding = TitanGraphqlPackageBinding.read(packageDirectory);
            TitanGraphqlGap005ArtifactMetadata metadata = TitanGraphqlArtifactsDirectory.readGap005Metadata(
                    packageDirectory, TitanGraphqlArtifactsDirectory.PORTABLE_DISPLAY_ROOT);
            TitanGraphqlManagementSchemaInstaller.install(connection, ManagementSchemaInstaller.Dialect.POSTGRESQL);
            for (String script : List.of(
                    "R__titan_004_graphql_mutation_state.sql",
                    "R__titan_006_graphql_outbox.sql",
                    "R__titan_007_graphql_control_jobs.sql")) {
                CommerceDatabaseEngineDeployment.executePostgreSqlScript(connection,
                        Path.of("src/database-engine-migrations/postgresql").resolve(script));
            }
            for (String script : List.of("R__titan_010_runtime.sql", "R__titan_020_routines.sql")) {
                CommerceDatabaseEngineDeployment.executePostgreSqlScript(connection,
                        packageDirectory.resolve("postgresql").resolve(script));
            }
            SingleConnectionDataSource servingDatabase = new SingleConnectionDataSource(connection);
            TitanGraphqlDurableManagementStore store = new TitanGraphqlDurableManagementStore(
                    new JdbcTransactionalMutationStore(servingDatabase), servingDatabase);
            String timestamp = Instant.now().toString();
            store.saveWorkspace(new TitanGraphqlManagedWorkspace(
                    "workspace-http-candidate", "preview", "", "preview", timestamp, timestamp));
            store.saveModel(new TitanGraphqlManagedModel(
                    "model-http-candidate", "workspace-http-candidate", document.metadata().name(),
                    "Preview", "", "draft-http-candidate", "", "validation-http-candidate",
                    "artifact-http-candidate", timestamp, timestamp));
            store.saveArtifactSet(new TitanGraphqlArtifactSetRef(
                    "artifact-http-candidate", "draft-http-candidate", binding.modelSemanticSha256(),
                    "", "", "", "", "", sha256("preview HTTP candidate"), "preview", timestamp), metadata);
            store.saveDraft(new TitanGraphqlModelDraft(
                    "draft-http-candidate", "model-http-candidate",
                    TitanGraphqlModelDraft.ModelDraftStatus.READY_FOR_REVIEW,
                    TitanGraphqlModelDraft.SourceFormat.YAML, source,
                    TitanGraphqlModelDocumentJson.canonicalJson(document), binding.modelSemanticSha256(),
                    "validation-http-candidate", "artifact-http-candidate", "", "operator", timestamp, timestamp));
            store.saveOperationRegistry(new TitanGraphqlOperationRegistry(
                    "registry-http-candidate", "model-http-candidate", "preview",
                    TitanGraphqlOperationRegistry.RegistryMode.ENFORCE,
                    List.of(
                            new TitanGraphqlOperationRegistry.RegisteredOperation(
                                    "preview-http-operation", "Probe", sha256(OPERATION), OPERATION,
                                    TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED,
                                    List.of(), List.of(), 1, 1, List.of(), timestamp, "operator", timestamp),
                            new TitanGraphqlOperationRegistry.RegisteredOperation(
                                    "preview-http-restricted", "PrivateProbe", sha256(RESTRICTED_OPERATION),
                                    RESTRICTED_OPERATION,
                                    TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED,
                                    List.of("operator"), List.of(), 1, 1, List.of(), timestamp, "operator", timestamp)),
                    timestamp));
            deploymentDirectory = Files.createTempDirectory("titan-graphql-preview-http-");
            Path registry = deploymentDirectory.resolve("registry.properties");
            new TitanGraphqlPreviewPublicationService(store, servingDatabase).publish(
                    "preview-http-candidate", "draft-http-candidate", "preview",
                    "registry-http-candidate", Instant.now().plusSeconds(3600), "operator",
                    packageDirectory, "postgresql", registry);
            return Map.of(
                    "quarkus.datasource.jdbc.url", container.getJdbcUrl(),
                    "quarkus.datasource.username", container.getUsername(),
                    "quarkus.datasource.password", container.getPassword(),
                    "titan.graphql.preview.database-descriptor-registry", registry.toString());
        } catch (Exception failure) {
            container.stop();
            throw new IllegalStateException("preview candidate HTTP fixture could not be published", failure);
        }
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
        if (deploymentDirectory != null) {
            try (var entries = Files.list(deploymentDirectory)) {
                for (Path entry : entries.toList()) {
                    Files.deleteIfExists(entry);
                }
                Files.deleteIfExists(deploymentDirectory);
            } catch (Exception failure) {
                throw new IllegalStateException("preview candidate HTTP fixture could not be removed", failure);
            }
        }
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
