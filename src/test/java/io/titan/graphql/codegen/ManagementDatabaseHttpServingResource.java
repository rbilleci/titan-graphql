package io.titan.graphql.codegen;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import io.titan.graphql.management.TitanGraphqlManagedModel;
import io.titan.graphql.management.TitanGraphqlManagedWorkspace;
import io.titan.graphql.management.TitanGraphqlManagementSchemaInstaller;
import io.titan.graphql.management.TitanGraphqlModelDraft;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import io.titan.management.JdbcTransactionalMutationStore;
import io.titan.management.ManagementSchemaInstaller;
import io.titan.runtime.jdbc.SingleConnectionDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

public final class ManagementDatabaseHttpServingResource implements QuarkusTestResourceLifecycleManager {
    private static final Path PACKAGE = Path.of(
            "build/generated/proofs/database-engine-management-postgresql/package");
    private static final Path DESCRIPTOR = Path.of(
            "build/generated/proofs/database-engine-management-postgresql/frontend-deployment.properties");

    private PostgreSQLContainer<?> container;

    @Override
    public Map<String, String> start() {
        container = new PostgreSQLContainer<>("postgres:16");
        container.start();
        try (Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword())) {
            TitanGraphqlManagementSchemaInstaller.install(connection, ManagementSchemaInstaller.Dialect.POSTGRESQL);
            for (String script : List.of(
                    "R__titan_004_graphql_mutation_state.sql",
                    "R__titan_006_graphql_outbox.sql",
                    "R__titan_007_graphql_control_jobs.sql")) {
                CommerceDatabaseEngineDeployment.executePostgreSqlScript(connection,
                        Path.of("src/database-engine-migrations/postgresql").resolve(script));
            }
            for (String script : List.of("R__titan_010_runtime.sql", "R__titan_020_routines.sql")) {
                CommerceDatabaseEngineDeployment.executePostgreSqlScript(
                        connection, PACKAGE.resolve("postgresql").resolve(script));
            }
            SingleConnectionDataSource fixtureDataSource = new SingleConnectionDataSource(connection);
            TitanGraphqlDurableManagementStore store = new TitanGraphqlDurableManagementStore(
                    new JdbcTransactionalMutationStore(fixtureDataSource), fixtureDataSource);
            String source = Files.readString(Path.of("src/test/resources/graphql/management.titan.graphql.yaml"));
            TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(source);
            store.saveWorkspace(new TitanGraphqlManagedWorkspace(
                    "http-workspace", "platform", "", "development", "", ""));
            store.saveModel(new TitanGraphqlManagedModel(
                    "http-model", "http-workspace", document.metadata().name(),
                    "Management", "", "http-draft", "", "", "", "", ""));
            store.saveDraft(new TitanGraphqlModelDraft(
                    "http-draft", "http-model", TitanGraphqlModelDraft.ModelDraftStatus.IMPORTED,
                    TitanGraphqlModelDraft.SourceFormat.YAML, source,
                    TitanGraphqlModelDocumentJson.canonicalJson(document),
                    TitanGraphqlModelDocumentJson.semanticHash(document),
                    "", "", "", "operator", "", ""));
        } catch (Exception failure) {
            container.stop();
            throw new IllegalStateException("management database HTTP fixture could not be installed", failure);
        }
        return Map.of(
                "quarkus.datasource.jdbc.url", container.getJdbcUrl(),
                "quarkus.datasource.username", container.getUsername(),
                "quarkus.datasource.password", container.getPassword(),
                "titan.graphql.admin.access-token", "database-admin-token",
                "titan.graphql.admin.role", "operator",
                "titan.graphql.admin.actor-key", "database-admin-test",
                "titan.graphql.admin.database-descriptor", DESCRIPTOR.toAbsolutePath().toString());
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
    }
}
