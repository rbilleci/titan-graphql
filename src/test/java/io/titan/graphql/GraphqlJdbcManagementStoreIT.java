package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.artifact.TitanGraphqlArtifactKind;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.graphql.artifact.TitanGraphqlGeneratedArtifact;
import io.titan.graphql.management.TitanGraphqlDeployment;
import io.titan.graphql.management.TitanGraphqlArtifactSetRef;
import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import io.titan.graphql.management.TitanGraphqlManagementSchemaInstaller;
import io.titan.graphql.management.TitanGraphqlManagedModel;
import io.titan.graphql.management.TitanGraphqlModelDraft;
import io.titan.graphql.management.TitanGraphqlObservedOperation;
import io.titan.graphql.management.TitanGraphqlOperationRegistry;
import io.titan.management.ManagementAudit.AuditStatus;
import io.titan.management.ManagementSchemaInstaller;
import io.titan.management.ManagementSchemaInstaller.Dialect;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("docker")
@Tag("database-management-store")
class GraphqlJdbcManagementStoreIT {

    private static PostgreSQLContainer<?> postgres;
    private static MySQLContainer<?> mysql;

    enum Target {
        POSTGRESQL(Dialect.POSTGRESQL),
        MYSQL(Dialect.MYSQL);

        private final Dialect dialect;

        Target(Dialect dialect) {
            this.dialect = dialect;
        }
    }

    @BeforeAll
    static void startContainers() {
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("titan")
                .withUsername("titan")
                .withPassword("titan");
        postgres.start();
        mysql = new MySQLContainer<>("mysql:8.4")
                .withDatabaseName("management")
                .withUsername("titan")
                .withPassword("titan")
                .withCommand("--log_bin_trust_function_creators=1", "--innodb-use-native-aio=0");
        mysql.start();
    }

    @AfterAll
    static void stopContainers() {
        if (postgres != null) {
            postgres.stop();
        }
        if (mysql != null) {
            mysql.stop();
        }
    }

    // ------------------------------------------------------------------
    // DEPLOYMENT-ACTIVATION GATING still holds over the JDBC store.
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Target.class)
    void deploymentActivationGatingHoldsOverTheJdbcStore(Target target) throws Exception {
        DataSource dataSource = freshDataSource(target);
        TitanGraphqlDurableManagementStore store = JdbcManagementStoreFixture.create(
                dataSource, target.dialect);

        // Seed a known model wrapper so the activation/seed paths can resolve the workspace.
        store.saveModel(new io.titan.graphql.management.TitanGraphqlManagedModel(
                "model-001", "workspace-001", "demo-blog", "Demo Blog", "",
                "draft-001", "", "", "", "2026-06-09T00:00:00Z", ""));

        // GATE 1: an ACTIVE deployment seed is refused — active deployments demand GAP-006 activation.
        TitanGraphqlDeployment activeSeed = new TitanGraphqlDeployment(
                "deployment-001", "model-001", "draft-001", "artifact-001", "prod",
                TitanGraphqlDeployment.DeploymentStatus.ACTIVE, "operator", "2026-06-09T00:03:00Z", "", "health-001");
        IllegalArgumentException activeError =
                assertThrows(IllegalArgumentException.class, () -> store.saveDeployment(activeSeed));
        assertTrue(activeError.getMessage().contains("GAP-006 activation"),
                target + " active seed refused: " + activeError.getMessage());

        // GATE 2: activation without verified GAP-005 artifact evidence is refused (no mutation).
        TitanGraphqlDeployment pending = new TitanGraphqlDeployment(
                "deployment-001", "model-001", "draft-001", "artifact-001", "prod",
                TitanGraphqlDeployment.DeploymentStatus.PENDING, "operator", "2026-06-09T00:03:00Z", "", "health-001");
        IllegalArgumentException missingEvidence = assertThrows(IllegalArgumentException.class,
                () -> store.activateDeployment(pending, "actor-platform-001", "platform",
                        "request-deploy-001", "deploy:prod:artifact-001",
                        java.time.Instant.parse("2026-06-09T00:03:00Z"),
                        java.time.Instant.parse("2026-06-09T00:03:01Z")));
        assertTrue(missingEvidence.getMessage().contains("GAP-005 artifact verification"),
                target + " activation without evidence refused: " + missingEvidence.getMessage());

        // The gates left the durable store untouched: no deployments persisted through the JDBC store.
        TitanGraphqlDurableManagementStore reloaded = JdbcManagementStoreFixture.create(
                dataSource, target.dialect);
        assertTrue(reloaded.titanDeployments().isEmpty(), target + " no deployment persisted past the gates");
    }

    // ------------------------------------------------------------------
    // SCHEMA-BOOTSTRAP HONESTY: an unreachable datasource fails fast, never silently.
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Target.class)
    void unreachableDatasourceFailsFastWithoutSilentFallback(Target target) {
        DataSource unreachable = new SimpleDataSource(
                "jdbc:" + (target == Target.MYSQL ? "mysql" : "postgresql")
                        + "://127.0.0.1:1/titan_does_not_exist", "nobody", "nobody");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> JdbcManagementStoreFixture.create(unreachable, target.dialect));
        assertTrue(failure.getMessage().contains("could not bootstrap"),
                target + " honest bootstrap failure: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("JDBC URL and credentials"),
                target + " failure names the remedy: " + failure.getMessage());
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void partialManagementSchemaCannotPassInstallation(Target target) throws Exception {
        DataSource dataSource = freshDataSource(target);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            if (target == Target.POSTGRESQL) {
                statement.execute("CREATE SCHEMA management");
            }
            statement.execute("CREATE TABLE management.management_drafts (id VARCHAR(191) PRIMARY KEY)");
            SQLException failure = assertThrows(SQLException.class,
                    () -> TitanGraphqlManagementSchemaInstaller.install(connection, target.dialect));
            assertTrue(failure.getMessage().contains("management schema is incomplete"),
                    target + " partial schema must not be accepted");
            SQLException repairFailure = assertThrows(SQLException.class,
                    () -> TitanGraphqlManagementSchemaInstaller.repair(connection, target.dialect));
            assertTrue(repairFailure.getMessage().contains("incompatible table"),
                    target + " incompatible schema must not be repaired");
        }
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void interruptedManagementInstallationCanBeRepairedWithoutLosingRows(Target target) throws Exception {
        DataSource dataSource = freshDataSource(target);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            if (target == Target.POSTGRESQL) {
                statement.execute("CREATE SCHEMA management");
                statement.execute("SET search_path TO management");
            } else {
                statement.execute("USE management");
            }
            statement.execute(ManagementSchemaInstaller.schemaStatements(target.dialect).get(0));
            statement.execute("INSERT INTO management.management_drafts "
                    + "(id, workspace_id, model_id, version, status, document, document_hash, "
                    + "created_at, updated_at, metadata) VALUES "
                    + "('preserved', 'workspace', 'model', 1, 'imported', '{}', 'hash', "
                    + "'2026-09-24T00:00:00Z', '2026-09-24T00:00:00Z', '{}')");

            TitanGraphqlManagementSchemaInstaller.repair(connection, target.dialect);
            TitanGraphqlManagementSchemaInstaller.verifyInstalled(connection);
            statement.execute("DROP PROCEDURE management.seed_draft");
            TitanGraphqlManagementSchemaInstaller.repair(connection, target.dialect);
            TitanGraphqlManagementSchemaInstaller.repair(connection, target.dialect);

            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM management.management_drafts WHERE id = 'preserved'")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void artifactRequestTableWithoutPrimaryKeyFailsManagementPreflight(Target target) throws Exception {
        DataSource dataSource = freshDataSource(target);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            TitanGraphqlManagementSchemaInstaller.install(connection, target.dialect);
            statement.execute(target == Target.POSTGRESQL
                    ? "ALTER TABLE management.graphql_artifact_requests "
                            + "DROP CONSTRAINT graphql_artifact_requests_pkey"
                    : "ALTER TABLE management.graphql_artifact_requests DROP PRIMARY KEY");
            SQLException preflight = assertThrows(SQLException.class,
                    () -> TitanGraphqlManagementSchemaInstaller.verifyInstalled(connection));
            assertTrue(preflight.getMessage().contains("job_id primary key"));
            SQLException reinstall = assertThrows(SQLException.class,
                    () -> TitanGraphqlManagementSchemaInstaller.install(connection, target.dialect));
            assertTrue(reinstall.getMessage().contains("job_id primary key"));
        }
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void validationRequestTableWithoutPrimaryKeyFailsManagementPreflight(Target target) throws Exception {
        DataSource dataSource = freshDataSource(target);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            TitanGraphqlManagementSchemaInstaller.install(connection, target.dialect);
            statement.execute(target == Target.POSTGRESQL
                    ? "ALTER TABLE management.graphql_validation_requests "
                            + "DROP CONSTRAINT graphql_validation_requests_pkey"
                    : "ALTER TABLE management.graphql_validation_requests DROP PRIMARY KEY");
            SQLException preflight = assertThrows(SQLException.class,
                    () -> TitanGraphqlManagementSchemaInstaller.verifyInstalled(connection));
            assertTrue(preflight.getMessage().contains("validation requests table requires job_id primary key"));
            SQLException reinstall = assertThrows(SQLException.class,
                    () -> TitanGraphqlManagementSchemaInstaller.install(connection, target.dialect));
            assertTrue(reinstall.getMessage().contains("validation requests table requires job_id primary key"));
        }
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void importRequestTableWithoutPrimaryKeyFailsManagementPreflight(Target target) throws Exception {
        DataSource dataSource = freshDataSource(target);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            TitanGraphqlManagementSchemaInstaller.install(connection, target.dialect);
            statement.execute(target == Target.POSTGRESQL
                    ? "ALTER TABLE management.graphql_import_requests "
                            + "DROP CONSTRAINT graphql_import_requests_pkey"
                    : "ALTER TABLE management.graphql_import_requests DROP PRIMARY KEY");
            SQLException preflight = assertThrows(SQLException.class,
                    () -> TitanGraphqlManagementSchemaInstaller.verifyInstalled(connection));
            assertTrue(preflight.getMessage().contains("import requests table requires job_id primary key"));
            SQLException reinstall = assertThrows(SQLException.class,
                    () -> TitanGraphqlManagementSchemaInstaller.install(connection, target.dialect));
            assertTrue(reinstall.getMessage().contains("import requests table requires job_id primary key"));
        }
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void operationRegistryTableWithoutPrimaryKeyFailsManagementPreflight(Target target) throws Exception {
        DataSource dataSource = freshDataSource(target);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            TitanGraphqlManagementSchemaInstaller.install(connection, target.dialect);
            statement.execute(target == Target.POSTGRESQL
                    ? "ALTER TABLE management.graphql_operation_registries "
                            + "DROP CONSTRAINT graphql_operation_registries_pkey"
                    : "ALTER TABLE management.graphql_operation_registries DROP PRIMARY KEY");
            SQLException preflight = assertThrows(SQLException.class,
                    () -> TitanGraphqlManagementSchemaInstaller.verifyInstalled(connection));
            assertTrue(preflight.getMessage().contains("operation registries table requires id primary key"));
            SQLException reinstall = assertThrows(SQLException.class,
                    () -> TitanGraphqlManagementSchemaInstaller.install(connection, target.dialect));
            assertTrue(reinstall.getMessage().contains("operation registries table requires id primary key"));
        }
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void registryOperationTableWithoutCompositeKeyFailsManagementPreflight(Target target) throws Exception {
        DataSource dataSource = freshDataSource(target);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            TitanGraphqlManagementSchemaInstaller.install(connection, target.dialect);
            statement.execute(target == Target.POSTGRESQL
                    ? "ALTER TABLE management.graphql_registry_operations "
                            + "DROP CONSTRAINT graphql_registry_operations_pkey"
                    : "ALTER TABLE management.graphql_registry_operations DROP PRIMARY KEY");
            SQLException preflight = assertThrows(SQLException.class,
                    () -> TitanGraphqlManagementSchemaInstaller.verifyInstalled(connection));
            assertTrue(preflight.getMessage().contains(
                    "registry operations table requires registry_id, operation_id primary key"));
        }
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void runtimeJdbcStoreRequiresPriorInstallation(Target target) throws Exception {
        DataSource dataSource = freshDataSource(target);
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> JdbcManagementStoreFixture.createInstalled(dataSource, target.dialect));
        assertTrue(missing.getMessage().contains("install-management"));

        try (Connection connection = dataSource.getConnection()) {
            TitanGraphqlManagementSchemaInstaller.install(connection, target.dialect);
        }
        TitanGraphqlDurableManagementStore installed =
                JdbcManagementStoreFixture.createInstalled(dataSource, target.dialect);
        assertTrue(installed.titanDrafts().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void generatedArtifactCoreAndProductWritesRollBackTogether(Target target) throws Exception {
        DataSource dataSource = freshDataSource(target);
        TitanGraphqlDurableManagementStore store = JdbcManagementStoreFixture.create(
                dataSource, target.dialect);
        store.saveModel(new TitanGraphqlManagedModel(
                "model-atomic", "workspace-atomic", "atomic", "Atomic", "",
                "draft-atomic", "", "", "", "", ""));
        String semanticHash = "0".repeat(64);
        TitanGraphqlArtifactSetRef artifact = new TitanGraphqlArtifactSetRef(
                "artifact-atomic", "draft-atomic", semanticHash, "", "", "", "", "", semanticHash,
                "development", "2026-06-09T00:02:00Z");
        TitanGraphqlModelDraft draft = new TitanGraphqlModelDraft(
                "draft-atomic", "model-atomic", TitanGraphqlModelDraft.ModelDraftStatus.READY_FOR_REVIEW,
                TitanGraphqlModelDraft.SourceFormat.YAML, "source", "{}", semanticHash,
                "", artifact.id(), "", "operator", "2026-06-09T00:00:00Z", "2026-06-09T00:02:00Z");
        TitanGraphqlGap005ArtifactMetadata metadata = new TitanGraphqlGap005ArtifactMetadata(
                "build/atomic", artifact.id(), "migration", "0.1.0",
                List.of(target.name().toLowerCase(java.util.Locale.ROOT)),
                semanticHash, semanticHash, semanticHash, semanticHash, "passed",
                List.of(), List.of(), List.of(),
                List.of(
                        TitanGraphqlArtifactKind.TITAN_ARTIFACT_METADATA,
                        TitanGraphqlArtifactKind.TITAN_OBJECT_INVENTORY,
                        TitanGraphqlArtifactKind.TITAN_INSTALL_PLAN,
                        TitanGraphqlArtifactKind.TITAN_INSTALL_VERIFICATION
                ).stream().map(kind -> new TitanGraphqlGeneratedArtifact(
                        kind, kind.defaultFileName(), "{}", semanticHash)).toList());

        renameProductStateTable(dataSource, target, true);
        try {
            assertThrows(IllegalStateException.class,
                    () -> store.saveGeneratedArtifacts(artifact, draft, metadata));
        } finally {
            renameProductStateTable(dataSource, target, false);
        }
        TitanGraphqlDurableManagementStore afterFailure = JdbcManagementStoreFixture.createInstalled(
                dataSource, target.dialect);
        assertTrue(afterFailure.titanDrafts().isEmpty(), target + " core seed rolled back");
        assertTrue(afterFailure.titanArtifactRefs().isEmpty(), target + " core artifact rolled back");
        assertTrue(afterFailure.artifactSet(artifact.id()) == null, target + " product entry did not commit");

        afterFailure.saveGeneratedArtifacts(artifact, draft, metadata);
        TitanGraphqlDurableManagementStore recovered = JdbcManagementStoreFixture.createInstalled(
                dataSource, target.dialect);
        assertEquals(draft.id(), recovered.titanDrafts().getFirst().id());
        assertEquals(artifact.id(), recovered.titanArtifactRefs().getFirst().id());
        assertEquals(artifact.id(), recovered.artifactSet(artifact.id()).id());
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void observedOperationReviewDoesNotPublishHalfARegistryDecision(Target target) throws Exception {
        DataSource dataSource = freshDataSource(target);
        TitanGraphqlDurableManagementStore store = JdbcManagementStoreFixture.create(
                dataSource, target.dialect);
        TitanGraphqlObservedOperation observed = TitanGraphqlObservedOperation.observed(
                "model-review", "prod", "reader", "portal", "operation-sha", "ArticleById",
                "query ArticleById { article(id: 1) { id } }", 2, 3, List.of("Article.id"),
                "2026-06-09T00:00:00Z");
        store.saveObservedOperation(observed);

        renameProductStateTable(dataSource, target, true);
        try {
            assertThrows(IllegalStateException.class, () -> store.approveObservedOperation(
                    observed.id(), "operator", "2026-06-09T00:01:00Z"));
            assertEquals(TitanGraphqlObservedOperation.ObservedOperationStatus.OBSERVED,
                    store.observedOperation(observed.id()).status());
            assertNull(store.operationRegistry("registry-model-review-prod"));
        } finally {
            renameProductStateTable(dataSource, target, false);
        }
        TitanGraphqlDurableManagementStore afterFailure = JdbcManagementStoreFixture.createInstalled(
                dataSource, target.dialect);
        assertEquals(TitanGraphqlObservedOperation.ObservedOperationStatus.OBSERVED,
                afterFailure.observedOperation(observed.id()).status());
        assertNull(afterFailure.operationRegistry("registry-model-review-prod"));
        try (Connection connection = dataSource.getConnection();
                PreparedStatement projection = connection.prepareStatement(
                        "SELECT id FROM management.graphql_operation_registries WHERE id = ?")) {
            projection.setString(1, "registry-model-review-prod");
            try (ResultSet rows = projection.executeQuery()) {
                assertFalse(rows.next());
            }
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement operations = connection.prepareStatement(
                        "SELECT operation_id FROM management.graphql_registry_operations "
                                + "WHERE registry_id = ?")) {
            operations.setString(1, "registry-model-review-prod");
            try (ResultSet rows = operations.executeQuery()) {
                assertFalse(rows.next());
            }
        }

        afterFailure.approveObservedOperation(observed.id(), "operator", "2026-06-09T00:01:00Z");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement removeProjection = connection.prepareStatement(
                        "DELETE FROM management.graphql_observed_operations WHERE id = ?");
                PreparedStatement removeOperations = connection.prepareStatement(
                        "DELETE FROM management.graphql_registry_operations WHERE registry_id = ?");
                PreparedStatement removeRegistry = connection.prepareStatement(
                        "DELETE FROM management.graphql_operation_registries WHERE id = ?")) {
            removeProjection.setString(1, observed.id());
            assertEquals(1, removeProjection.executeUpdate());
            removeOperations.setString(1, "registry-model-review-prod");
            assertEquals(1, removeOperations.executeUpdate());
            removeRegistry.setString(1, "registry-model-review-prod");
            assertEquals(1, removeRegistry.executeUpdate());
        }
        TitanGraphqlDurableManagementStore recovered = JdbcManagementStoreFixture.createInstalled(
                dataSource, target.dialect);
        assertEquals(TitanGraphqlObservedOperation.ObservedOperationStatus.APPROVED,
                recovered.observedOperation(observed.id()).status());
        assertEquals(TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED,
                recovered.operationRegistry("registry-model-review-prod").operations().getFirst().status());
        try (Connection connection = dataSource.getConnection();
                PreparedStatement projection = connection.prepareStatement(
                        "SELECT mode, operations_json, registry_json "
                                + "FROM management.graphql_operation_registries WHERE id = ?")) {
            projection.setString(1, "registry-model-review-prod");
            try (ResultSet rows = projection.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("OBSERVE", rows.getString(1));
                assertTrue(rows.getString(2).contains("\"status\":\"APPROVED\""));
                assertTrue(rows.getString(3).contains("\"id\":\"registry-model-review-prod\""));
                assertFalse(rows.next());
            }
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement operation = connection.prepareStatement(
                        "SELECT status, roles_json, clients_json FROM management.graphql_registry_operations "
                                + "WHERE registry_id = ? AND operation_id = ?")) {
            operation.setString(1, "registry-model-review-prod");
            operation.setString(2, "registry-operation-" + observed.id());
            try (ResultSet rows = operation.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("APPROVED", rows.getString(1));
                assertEquals("[\"reader\"]", rows.getString(2));
                assertEquals("[\"portal\"]", rows.getString(3));
                assertFalse(rows.next());
            }
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement projection = connection.prepareStatement(
                        "SELECT status, observed_count FROM management.graphql_observed_operations WHERE id = ?")) {
            projection.setString(1, observed.id());
            try (ResultSet rows = projection.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("APPROVED", rows.getString(1));
                assertEquals(1, rows.getInt(2));
            }
        }
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT entry_json FROM management.graphql_product_state ORDER BY entry_id")) {
            long reviews = 0;
            while (rows.next()) {
                if (rows.getString(1).contains("\"type\":\"operationReview\"")) {
                    reviews++;
                }
            }
            assertEquals(1, reviews);
        }
        TitanGraphqlOperationRegistry currentRegistry = recovered.operationRegistry("registry-model-review-prod");
        recovered.saveOperationRegistry(new TitanGraphqlOperationRegistry(
                currentRegistry.id(), currentRegistry.modelId(), currentRegistry.environment(),
                TitanGraphqlOperationRegistry.RegistryMode.ENFORCE, currentRegistry.operations(),
                "2026-06-09T00:02:00Z"));
        TitanGraphqlDurableManagementStore enforced = JdbcManagementStoreFixture.createInstalled(
                dataSource, target.dialect);
        assertEquals(TitanGraphqlOperationRegistry.RegistryMode.ENFORCE,
                enforced.operationRegistry(currentRegistry.id()).mode());
        try (Connection connection = dataSource.getConnection();
                PreparedStatement projection = connection.prepareStatement(
                        "SELECT mode FROM management.graphql_operation_registries WHERE id = ?")) {
            projection.setString(1, currentRegistry.id());
            try (ResultSet rows = projection.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("ENFORCE", rows.getString(1));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void concurrentObservationsMergeThroughLockedDatabaseRow(Target target) throws Exception {
        DataSource dataSource = freshDataSource(target);
        TitanGraphqlDurableManagementStore firstStore = JdbcManagementStoreFixture.create(
                dataSource, target.dialect);
        TitanGraphqlObservedOperation initial = TitanGraphqlObservedOperation.observed(
                "model-concurrent", "prod", "reader", "portal", "operation-sha", "ReadArticle",
                "query ReadArticle { article(id: 1) { id } }", 2, 3, List.of("Article.id"),
                "2026-06-09T00:00:00Z");
        firstStore.saveObservedOperation(initial);
        TitanGraphqlDurableManagementStore secondStore = JdbcManagementStoreFixture.createInstalled(
                dataSource, target.dialect);
        TitanGraphqlObservedOperation second = TitanGraphqlObservedOperation.observed(
                "model-concurrent", "prod", "reader", "portal", "operation-sha", "ReadArticle",
                "query ReadArticle { article(id: 2) { id } }", 2, 3, List.of("Article.id"),
                "2026-06-09T00:01:00Z");
        TitanGraphqlObservedOperation third = TitanGraphqlObservedOperation.observed(
                "model-concurrent", "prod", "reader", "portal", "operation-sha", "ReadArticle",
                "query ReadArticle { article(id: 3) { id } }", 2, 3, List.of("Article.id"),
                "2026-06-09T00:02:00Z");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Void> first = workers.submit((Callable<Void>) () -> {
                start.await();
                firstStore.observeOperation(second);
                return null;
            });
            Future<Void> next = workers.submit((Callable<Void>) () -> {
                start.await();
                secondStore.observeOperation(third);
                return null;
            });
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            next.get(30, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }

        TitanGraphqlDurableManagementStore recovered = JdbcManagementStoreFixture.createInstalled(
                dataSource, target.dialect);
        assertEquals(3, recovered.observedOperation(initial.id()).observedCount());
        try (Connection connection = dataSource.getConnection();
                PreparedStatement projection = connection.prepareStatement(
                        "SELECT observed_count FROM management.graphql_observed_operations WHERE id = ?")) {
            projection.setString(1, initial.id());
            try (ResultSet rows = projection.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(3, rows.getInt(1));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void concurrentReviewsKeepBothRegistryDecisions(Target target) throws Exception {
        DataSource dataSource = freshDataSource(target);
        TitanGraphqlDurableManagementStore firstStore = JdbcManagementStoreFixture.create(
                dataSource, target.dialect);
        TitanGraphqlObservedOperation viewer = TitanGraphqlObservedOperation.observed(
                "model-review-lock", "prod", "viewer", "portal", "operation-sha", "ReadArticle",
                "query ReadArticle { article(id: 1) { id } }", 2, 3, List.of("Article.id"),
                "2026-06-09T00:00:00Z");
        TitanGraphqlObservedOperation admin = TitanGraphqlObservedOperation.observed(
                "model-review-lock", "prod", "admin", "admin-tool", "operation-sha", "ReadArticle",
                "query ReadArticle { article(id: 1) { id } }", 2, 3, List.of("Article.id"),
                "2026-06-09T00:00:00Z");
        firstStore.saveObservedOperation(viewer).saveObservedOperation(admin);
        TitanGraphqlDurableManagementStore secondStore = JdbcManagementStoreFixture.createInstalled(
                dataSource, target.dialect);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Void> approve = workers.submit((Callable<Void>) () -> {
                start.await();
                firstStore.approveObservedOperation(viewer.id(), "operator", "2026-06-09T00:01:00Z");
                return null;
            });
            Future<Void> reject = workers.submit((Callable<Void>) () -> {
                start.await();
                secondStore.rejectObservedOperation(admin.id(), "operator", "2026-06-09T00:02:00Z");
                return null;
            });
            start.countDown();
            approve.get(30, TimeUnit.SECONDS);
            reject.get(30, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }

        TitanGraphqlDurableManagementStore recovered = JdbcManagementStoreFixture.createInstalled(
                dataSource, target.dialect);
        TitanGraphqlOperationRegistry registry = recovered.operationRegistry("registry-model-review-lock-prod");
        assertEquals(2, registry.operations().size());
        assertEquals(TitanGraphqlObservedOperation.ObservedOperationStatus.APPROVED,
                recovered.observedOperation(viewer.id()).status());
        assertEquals(TitanGraphqlObservedOperation.ObservedOperationStatus.REJECTED,
                recovered.observedOperation(admin.id()).status());
        assertTrue(registry.operations().stream().anyMatch(operation ->
                operation.roles().equals(List.of("viewer"))
                        && operation.status() == TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED));
        assertTrue(registry.operations().stream().anyMatch(operation ->
                operation.roles().equals(List.of("admin"))
                        && operation.status() == TitanGraphqlOperationRegistry.RegisteredOperationStatus.REJECTED));
        try (Connection connection = dataSource.getConnection();
                PreparedStatement operations = connection.prepareStatement(
                        "SELECT operation_id, operation_position, status "
                                + "FROM management.graphql_registry_operations "
                                + "WHERE registry_id = ? ORDER BY operation_position")) {
            operations.setString(1, registry.id());
            try (ResultSet rows = operations.executeQuery()) {
                List<String> statuses = new java.util.ArrayList<>();
                int position = 0;
                while (rows.next()) {
                    assertEquals(registry.operations().get(position).id(), rows.getString(1));
                    assertEquals(position, rows.getInt(2));
                    statuses.add(rows.getString(3));
                    position++;
                }
                assertEquals(2, statuses.size());
                assertTrue(statuses.containsAll(List.of("APPROVED", "REJECTED")));
            }
        }
    }

    private static void renameProductStateTable(DataSource dataSource, Target target, boolean away)
            throws SQLException {
        String original = "management.graphql_product_state";
        String held = "management.graphql_product_state_held";
        String sql = target == Target.POSTGRESQL
                ? "ALTER TABLE " + (away ? original : held)
                        + " RENAME TO " + (away ? "graphql_product_state_held" : "graphql_product_state")
                : "RENAME TABLE " + (away ? original : held) + " TO " + (away ? held : original);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private DataSource freshDataSource(Target target) throws SQLException {
        if (target == Target.POSTGRESQL) {
            try (Connection admin = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS management CASCADE");
            }
            return new SimpleDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        try (Connection admin = DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS management");
            statement.execute("CREATE DATABASE management");
        }
        String url = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        return new SimpleDataSource(url, mysql.getUsername(), mysql.getPassword());
    }

    /** Minimal {@link DataSource} returning a fresh DriverManager connection per call. */
    private static final class SimpleDataSource implements DataSource {
        private final String url;
        private final String user;
        private final String password;

        SimpleDataSource(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getLogger("titan-graphql-management-it");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("not a wrapper for " + iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}
