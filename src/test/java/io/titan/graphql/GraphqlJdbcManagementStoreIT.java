package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.management.TitanGraphqlDeployment;
import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import io.titan.graphql.management.TitanGraphqlManagementStore;
import io.titan.graphql.management.TitanGraphqlModelDraft;
import io.titan.management.ManagementAudit.AuditStatus;
import io.titan.management.ManagementSchemaInstaller.Dialect;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Dogfood campaign Phase C ({@code @Tag("docker")}) — the consumer's management plane running ON the
 * dogfooded core JDBC store ({@code titan.graphql.management.store=jdbc}), against LIVE PostgreSQL 16
 * + MySQL 8.4, parameterized over both dialects.
 *
 * <p>It drives the SAME {@code importModelDocument} mutation path the file-store tests use —
 * {@link GraphqlManagementMutationSupport#executeMutation} over the management schema — but the store
 * is built by {@link TitanGraphqlManagementStoreFactory} in jdbc mode: the management schema +
 * Titan-transpiled routines are bootstrapped against the container, and the mutation runs through
 * core's {@code JdbcTransactionalMutationStore} (mutations are {@code CALL}ed transpiled routines).
 *
 * <p>It proves the three things the file store cannot show for the consumer:
 * <ol>
 *   <li><b>DURABILITY</b> — the imported model document persists THROUGH the JDBC store: a brand-new
 *       store instance over the same datasource (nothing in memory) reads the draft + idempotency +
 *       audit rows back from the live database.</li>
 *   <li><b>IDEMPOTENCY</b> — the same request/idempotency key replays without a second mutation (one
 *       idempotency row), and a conflicting input on the same key is refused.</li>
 *   <li><b>DEPLOYMENT-ACTIVATION GATING</b> — the GAP-006 activation guards still hold over the JDBC
 *       store (an ACTIVE deployment seed and an activation without verified GAP-005 evidence are
 *       refused), so the durable swap did not weaken the activation contract.</li>
 * </ol>
 *
 * <p>The schema-bootstrap honesty leg additionally asserts that an UNREACHABLE datasource fails fast
 * and descriptively (never a silent fallback to the file store).
 */
@Tag("docker")
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
        // The MySQL schema IS a database: provision it AS `management` so the scoped `titan` user has
        // ALL PRIVILEGES on it (it can DROP/CREATE its own database between tests, and the factory's
        // CREATE-DATABASE-IF-NOT-EXISTS / USE both succeed). log_bin_trust_function_creators lets a
        // non-super user CREATE PROCEDURE without SUPER.
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
    // DURABILITY + IDEMPOTENCY through the management plane (importModelDocument).
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Target.class)
    void importModelDocumentPersistsThroughTheJdbcStoreAndIsIdempotent(Target target) throws Exception {
        DataSource dataSource = freshDataSource(target);

        // The store is built EXACTLY as the runtime builds it in jdbc mode: the factory bootstraps
        // the schema/routines against the datasource and wraps core's JdbcTransactionalMutationStore.
        TitanGraphqlDurableManagementStore store = TitanGraphqlManagementStoreFactory.jdbcStore(
                dataSource, target.dialect);
        GraphqlManagementMutationSupport support = new GraphqlManagementMutationSupport(store);
        GraphqlSchema schema = managementSchema(support);
        String yaml = readDemoBlogFixture();

        GraphqlExecution first = support.executeMutation(
                schema, importOperation(yaml), context("request-import", "idem-import"));
        GraphqlExecution replay = support.executeMutation(
                schema, importOperation(yaml), context("request-import", "idem-import"));

        assertTrue(first.json().contains("\"accepted\":true"), target + " import accepted: " + first.json());
        assertEquals(first.json(), replay.json(), target + " replay returns the same payload");

        // The mutation ran THROUGH the JDBC store: one durable draft, one idempotency row, and the
        // attempt+outcome audit pair per CALL (faithful to the file store's [ATTEMPT, SUCCESS] x2).
        assertEquals(1, store.titanDrafts().size(), target + " one durable draft");
        assertEquals(1, store.idempotencyRecords().size(), target + " one durable idempotency row");
        assertEquals(
                List.of(AuditStatus.ATTEMPT, AuditStatus.SUCCESS, AuditStatus.ATTEMPT, AuditStatus.SUCCESS),
                store.auditRecords().stream().map(io.titan.management.ManagementAudit.AuditRecord::status).toList(),
                target + " attempt+outcome audited per call");

        String draftId = draftId(yaml);

        // DURABILITY: a BRAND-NEW jdbc-mode store over the same datasource — nothing in memory —
        // reads the draft + idempotency + audit back from the live database. (Re-bootstrap is a
        // no-op: the factory's already-installed guard skips re-applying the bundle.)
        TitanGraphqlDurableManagementStore reloaded = TitanGraphqlManagementStoreFactory.jdbcStore(
                dataSource, target.dialect);
        assertEquals(1, reloaded.titanDrafts().size(), target + " durable draft survives a new store instance");
        assertEquals(draftId, reloaded.titanDrafts().getFirst().id(), target + " durable draft id");
        assertNotNull(reloaded.draft(draftId), target + " draft read back through the JDBC store");
        assertEquals(TitanGraphqlModelDraft.ModelDraftStatus.IMPORTED,
                reloaded.draft(draftId).status(), target + " durable draft status");
        assertEquals(1, reloaded.idempotencyRecords().size(), target + " durable idempotency row survives");

        // IDEMPOTENCY conflict: same key, changed input -> refused, still one draft + one idem row.
        GraphqlExecution conflict = support.executeMutation(
                schema,
                importOperation(yaml.replace("name: demo-blog", "name: demo-blog-v2")),
                context("request-import-2", "idem-import"));
        assertTrue(conflict.json().contains("idempotency input mismatch"),
                target + " conflicting input on the same key is refused: " + conflict.json());
        TitanGraphqlDurableManagementStore afterConflict = TitanGraphqlManagementStoreFactory.jdbcStore(
                dataSource, target.dialect);
        assertEquals(1, afterConflict.titanDrafts().size(), target + " conflict performed no second mutation");
        assertEquals(1, afterConflict.idempotencyRecords().size(), target + " still one durable idempotency row");
    }

    // ------------------------------------------------------------------
    // DEPLOYMENT-ACTIVATION GATING still holds over the JDBC store.
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Target.class)
    void deploymentActivationGatingHoldsOverTheJdbcStore(Target target) throws Exception {
        DataSource dataSource = freshDataSource(target);
        TitanGraphqlDurableManagementStore store = TitanGraphqlManagementStoreFactory.jdbcStore(
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
        TitanGraphqlDurableManagementStore reloaded = TitanGraphqlManagementStoreFactory.jdbcStore(
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
                () -> TitanGraphqlManagementStoreFactory.jdbcStore(unreachable, target.dialect));
        assertTrue(failure.getMessage().contains("could not bootstrap")
                        || failure.getMessage().contains("could not be initialised"),
                target + " honest bootstrap failure: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("quarkus.datasource"),
                target + " failure names the remedy: " + failure.getMessage());
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    /**
     * A fresh, isolated {@code management} schema per test, so each parameterized leg starts empty.
     * Returns a plain DataSource at the database/admin level — the factory itself ensures the
     * {@code management} schema and sets the per-connection search path, so the test only needs to
     * reset the schema between runs.
     */
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
            // The scoped user owns the `management` database, so it can reset it between tests.
            statement.execute("DROP DATABASE IF EXISTS management");
            statement.execute("CREATE DATABASE management");
        }
        // Point the URL at the server (no default DB) so the factory's USE selects `management`;
        // the host/port come from the container's mapped binding.
        String url = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        return new SimpleDataSource(url, mysql.getUsername(), mysql.getPassword());
    }

    private static GraphqlSchema managementSchema(GraphqlManagementMutationSupport support) {
        return support.withManagementMutations(ProjectionGraphqlAdapter.adapt(
                io.titan.graphql.management.TitanGraphqlManagementProjection.projectionModel().toProjectionModel()
        ));
    }

    private static GraphqlAst.AstOperation importOperation(String yaml) {
        return GraphqlParser.parseSelectedOperation(new GraphqlRequest(
                """
                mutation Import($yaml: String!) {
                  importModelDocument(input: { workspaceId: "workspace-001", yaml: $yaml }) {
                    accepted
                    draftId
                    modelId
                    errors
                    warnings
                  }
                }
                """,
                "Import",
                Map.of("yaml", yaml),
                Map.of()
        ));
    }

    private static String draftId(String yaml) {
        var document = io.titan.graphql.model.TitanGraphqlModelDocumentYaml.parse(yaml);
        return "draft-" + io.titan.graphql.model.TitanGraphqlModelDocumentJson.semanticHash(document).substring(0, 12);
    }

    private static GraphqlRequestContext context(String requestId, String idempotencyKey) {
        return new GraphqlRequestContext(
                0L,
                "operator",
                "actor-operator",
                "",
                requestId,
                idempotencyKey,
                List.of("management"),
                List.of(),
                true,
                false,
                false,
                0L);
    }

    private static String readDemoBlogFixture() {
        try (InputStream stream = TitanGraphqlManagementStore.class.getResourceAsStream(
                "/graphql/demo-blog.titan.graphql.yaml"
        )) {
            if (stream == null) {
                throw new IllegalStateException("missing demo-blog model fixture");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read demo-blog model fixture", ex);
        }
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
