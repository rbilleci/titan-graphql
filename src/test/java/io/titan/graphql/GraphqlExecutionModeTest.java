package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.artifact.TitanGraphqlArtifactsDirectory;
import io.titan.graphql.demo.blog.DemoBlogGraphqlRuntime;
import io.titan.graphql.sqlmode.GraphqlSqlModeRuntime;
import io.titan.graphql.sqlmode.GraphqlSqlEntryPointDispatch;
import io.titan.graphql.sqlmode.GraphqlSqlModeUnavailableException;
import io.titan.runtime.jdbc.TitanExecutionListener;
import jakarta.ws.rs.core.Response;
import java.io.PrintWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Docker-free coverage for the execution-mode plumbing: mode selection (default database),
 * the descriptive 503 failure path of SQL mode with an absent/unreachable datasource (never a
 * silent fallback to Java mode), the telemetry listener contract, and the mode surface
 * headers. The live SQL-mode serving path itself is proven by {@code GraphqlSqlModeHttpIT}.
 */
class GraphqlExecutionModeTest {

    private static final String SIMPLE_QUERY = "{ article(id: 1) { id title } }";
    private static final String DEMO_MODEL_PATH =
            "src/test/resources/graphql/demo-blog.titan.graphql.yaml";

    @TempDir
    Path temporaryDirectory;

    // --- mode selection -------------------------------------------------------------------

    @Test
    void emptyModeValueSelectsDatabaseProductionDefault() {
        assertEquals(GraphqlExecutionEngine.Mode.DATABASE, engine("").mode());
    }

    @Test
    void modeValuesParseLenientlyOnCaseAndWhitespaceOnly() {
        assertEquals(GraphqlExecutionEngine.Mode.DATABASE, engine("").mode());
        assertEquals(GraphqlExecutionEngine.Mode.JAVA, engine(" Java ").mode());
        assertEquals(GraphqlExecutionEngine.Mode.JDBC, engine("JDBC").mode());
        assertEquals(GraphqlExecutionEngine.Mode.COMPILED, engine("COMPILED").mode());
        assertEquals(GraphqlExecutionEngine.Mode.SQL, engine("SQL").mode());
        assertEquals(GraphqlExecutionEngine.Mode.DATABASE, engine(" Database ").mode());
    }

    @Test
    void unknownModeFailsFastAndDescriptively() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> engine("yaml"));

        assertTrue(failure.getMessage().contains(GraphqlExecutionEngine.MODE_PROPERTY), failure.getMessage());
        assertTrue(failure.getMessage().contains("'java', 'jdbc', 'compiled', 'sql', or 'database'"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("yaml"), failure.getMessage());
    }

    @Test
    void systemConfigEngineReadsTheModeProperty() {
        assertEquals(GraphqlExecutionEngine.Mode.JAVA, GraphqlExecutionEngine.fromSystemConfig().mode());
        String previous = System.getProperty(GraphqlExecutionEngine.MODE_PROPERTY);
        System.setProperty(GraphqlExecutionEngine.MODE_PROPERTY, "sql");
        try {
            assertEquals(GraphqlExecutionEngine.Mode.SQL, GraphqlExecutionEngine.fromSystemConfig().mode());
        } finally {
            if (previous == null) System.clearProperty(GraphqlExecutionEngine.MODE_PROPERTY);
            else System.setProperty(GraphqlExecutionEngine.MODE_PROPERTY, previous);
        }
    }

    @Test
    void javaModeNeverTouchesTheDataSource() {
        GraphqlExecutionEngine engine = new GraphqlExecutionEngine(
                "java",
                () -> {
                    throw new AssertionError("java mode must not resolve the datasource");
                },
                "must-not-be-used");

        assertEquals(DemoBlogGraphqlRuntime.NAME, engine.runtime().name());
        GraphqlHttpResource.GraphqlHttpResult result = new GraphqlHttpResource(engine)
                .negotiatePost(Map.<String, Object>of("query", SIMPLE_QUERY), GraphqlHttpResource.GRAPHQL_RESPONSE_JSON);
        assertEquals(200, result.status());
        assertTrue(result.body().contains("\"data\""), result.body());
    }

    @Test
    void servingConfigurationRejectsLegacyModesBeforeTheHttpGetParserRuns() {
        GraphqlExecutionEngine engine = new GraphqlExecutionEngine(
                "java",
                () -> {
                    throw new AssertionError("legacy runtime must not be reached");
                },
                "must-not-be-used",
                "",
                GraphqlApplicationMutationProvider.none(),
                "",
                false);

        GraphqlHttpResource.GraphqlHttpResult result = new GraphqlHttpResource(engine).negotiateGet(
                "this is deliberately not a valid GraphQL document", "", "", "",
                GraphqlHttpResource.GRAPHQL_RESPONSE_JSON);

        assertEquals(503, result.status());
        assertTrue(result.body().contains(GraphqlExecutionEngine.ALLOW_LEGACY_EXECUTION_MODES_PROPERTY), result.body());
        assertTrue(result.body().contains("legacy execution mode 'java' is disabled"), result.body());
    }

    @Test
    void jdbcModeRequiresAReviewedModelBeforeResolvingTheDataSource() {
        GraphqlExecutionEngine engine = new GraphqlExecutionEngine(
                "jdbc",
                () -> {
                    throw new AssertionError("missing model configuration must fail before datasource resolution");
                },
                "must-not-be-used",
                "");

        GraphqlExecutionModeUnavailableException failure = assertThrows(
                GraphqlExecutionModeUnavailableException.class, engine::runtime);

        assertTrue(failure.getMessage().contains(GraphqlExecutionEngine.MODEL_PATH_PROPERTY), failure.getMessage());
        assertTrue(failure.getMessage().contains(GraphqlExecutionEngine.MODEL_PATH_ENVIRONMENT_VARIABLE),
                failure.getMessage());
    }

    @Test
    void jdbcConfigurationFailureIsADescriptive503WithoutJavaFallback() {
        GraphqlExecutionEngine engine = new GraphqlExecutionEngine(
                "jdbc", () -> { throw new AssertionError("must not resolve"); }, "unused", "");

        GraphqlHttpResource.GraphqlHttpResult result = new GraphqlHttpResource(engine)
                .negotiatePost(Map.<String, Object>of("query", SIMPLE_QUERY),
                        GraphqlHttpResource.GRAPHQL_RESPONSE_JSON);

        assertEquals(503, result.status());
        assertTrue(result.body().contains("titan.graphql.execution.mode=jdbc"), result.body());
        assertTrue(result.body().contains(GraphqlExecutionEngine.MODEL_PATH_PROPERTY), result.body());
        assertTrue(result.body().contains(GraphqlHttpResource.EXECUTION_MODE_UNAVAILABLE), result.body());
        assertFalse(result.body().contains("\"data\""), result.body());
    }

    // --- failure honesty (SQL mode, absent/unreachable datasource) -------------------------

    @Test
    void sqlModeRequiresAnExactlyBoundReviewedModelBeforeResolvingTheDataSource() {
        GraphqlExecutionEngine engine = new GraphqlExecutionEngine(
                "sql",
                () -> {
                    throw new AssertionError("missing model must fail before datasource resolution");
                },
                "must-not-be-used",
                "");

        GraphqlExecutionModeUnavailableException failure = assertThrows(
                GraphqlExecutionModeUnavailableException.class, engine::runtime);

        assertTrue(failure.getMessage().contains(GraphqlExecutionEngine.MODEL_PATH_PROPERTY), failure.getMessage());
        assertTrue(failure.getMessage().contains("titanGraphqlBindPackage"), failure.getMessage());
    }

    @Test
    void compiledModeRequiresAnExactlyBoundReviewedModelBeforeResolvingTheDataSource() {
        GraphqlExecutionEngine engine = new GraphqlExecutionEngine(
                "compiled",
                () -> {
                    throw new AssertionError("missing model must fail before datasource resolution");
                },
                "must-not-be-used",
                "");

        GraphqlExecutionModeUnavailableException failure = assertThrows(
                GraphqlExecutionModeUnavailableException.class, engine::runtime);

        assertTrue(failure.getMessage().contains("execution.mode=compiled"), failure.getMessage());
        assertTrue(failure.getMessage().contains(GraphqlExecutionEngine.MODEL_PATH_PROPERTY), failure.getMessage());
        assertTrue(failure.getMessage().contains("titanGraphqlBindPackage"), failure.getMessage());
    }

    @Test
    void sqlModeRejectsSemanticModelDriftBeforeResolvingTheDataSource() throws IOException {
        Path fixturePackage = Path.of("src/test/resources/titan-artifacts");
        for (String file : List.of(
                "titan-artifact.json",
                "titan-object-inventory.json",
                "titan-install-plan.json",
                "titan-install-verification.json",
                "titan-rollback.postgresql.sql",
                "titan-graphql-package.json")) {
            Files.copy(fixturePackage.resolve(file), temporaryDirectory.resolve(file));
        }
        Path changedModel = temporaryDirectory.resolve("changed-model.yaml");
        Files.writeString(changedModel, Files.readString(Path.of(DEMO_MODEL_PATH)).replace(
                "description: Bounded demo blog model used to prove Titan GraphQL.",
                "description: Changed after package generation."));
        String previousArtifactsDirectory = System.getProperty(TitanGraphqlArtifactsDirectory.SYSTEM_PROPERTY);
        System.setProperty(TitanGraphqlArtifactsDirectory.SYSTEM_PROPERTY, temporaryDirectory.toString());
        try {
            GraphqlExecutionEngine engine = new GraphqlExecutionEngine(
                    "sql",
                    () -> {
                        throw new AssertionError("model drift must fail before datasource resolution");
                    },
                    "must-not-be-used",
                    changedModel.toString());

            GraphqlExecutionModeUnavailableException failure = assertThrows(
                    GraphqlExecutionModeUnavailableException.class, engine::runtime);

            assertTrue(failure.getMessage().contains("model semantic hash mismatch"), failure.getMessage());
        } finally {
            if (previousArtifactsDirectory == null) {
                System.clearProperty(TitanGraphqlArtifactsDirectory.SYSTEM_PROPERTY);
            } else {
                System.setProperty(TitanGraphqlArtifactsDirectory.SYSTEM_PROPERTY, previousArtifactsDirectory);
            }
        }
    }

    @Test
    void packageRoutineIdentityIsStrictlyValidatedBeforeSqlConstruction() {
        GraphqlSqlEntryPointDispatch.Invocation invocation =
                new GraphqlSqlEntryPointDispatch.Invocation.Execute("{ article(id: 1) { id } }", 1L, "reader");

        assertEquals("SELECT tenant_api.graphql_execute(?, ?, ?)",
                GraphqlSqlEntryPointDispatch.placeholderSql(invocation, "tenant_api.graphql_execute"));
        assertEquals("SELECT tenant_api.model_semantic_hash()",
                GraphqlSqlEntryPointDispatch.noArgumentFunctionSql("tenant_api.model_semantic_hash"));
        assertEquals("SELECT tenant_api.read_root(?, ?)",
                GraphqlSqlEntryPointDispatch.carrierSql("tenant_api.read_root", 2, false));
        assertEquals("CALL tenant_api.read_root(?, ?)",
                GraphqlSqlEntryPointDispatch.carrierSql("tenant_api.read_root", 2, true));
        String wideCarrierCall = GraphqlSqlEntryPointDispatch.carrierSql("tenant_api.read_root", 1024, false);
        assertTrue(wideCarrierCall.startsWith("SELECT tenant_api.read_root(?, ?, ?"), wideCarrierCall);
        assertEquals(1024, wideCarrierCall.chars().filter(character -> character == '?').count(), wideCarrierCall);
        assertThrows(IllegalArgumentException.class,
                () -> GraphqlSqlEntryPointDispatch.placeholderSql(invocation, "public.fn; DROP TABLE users"));
        assertThrows(IllegalArgumentException.class,
                () -> GraphqlSqlEntryPointDispatch.noArgumentFunctionSql("public.fn; DROP TABLE users"));
        assertThrows(IllegalArgumentException.class,
                () -> GraphqlSqlEntryPointDispatch.carrierSql("public.fn; DROP TABLE users", 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> GraphqlSqlEntryPointDispatch.carrierSql("tenant_api.read_root", -1, false));
    }

    @Test
    void sqlModeWithoutAnyDataSourceAnswersDescriptive503InsteadOfFallingBack() {
        GraphqlExecutionEngine engine = new GraphqlExecutionEngine(
                "sql",
                () -> {
                    throw new IllegalStateException("no datasource is configured");
                },
                "test datasource (jdbc url unset)",
                DEMO_MODEL_PATH);
        GraphqlHttpResource resource = new GraphqlHttpResource(engine);

        GraphqlHttpResource.GraphqlHttpResult result =
                resource.negotiatePost(Map.<String, Object>of("query", SIMPLE_QUERY), GraphqlHttpResource.GRAPHQL_RESPONSE_JSON);

        assertEquals(503, result.status());
        String body = result.body();
        assertTrue(body.contains("titan.graphql.execution.mode=sql"), body);
        assertTrue(body.contains("test datasource (jdbc url unset)"), body);
        assertTrue(body.contains("no datasource is configured"), body);
        assertTrue(body.contains("R__titan_010_runtime.sql"), body);
        assertTrue(body.contains("switch titan.graphql.execution.mode back to java"), body);
        assertTrue(body.contains(GraphqlHttpResource.EXECUTION_MODE_UNAVAILABLE), body);
        // Failure honesty: the Java kernel could have answered this query — it must not.
        assertFalse(body.contains("\"data\""), body);
    }

    @Test
    void sqlModeWithUnreachableDatabaseAnswersDescriptive503OnPostAndGet() {
        GraphqlExecutionEngine engine = new GraphqlExecutionEngine(
                "sql",
                () -> new FailingDataSource("connection refused: db.example.test:5432"),
                GraphqlExecutionEngine.QUARKUS_DATASOURCE_DESCRIPTION,
                DEMO_MODEL_PATH);
        GraphqlHttpResource resource = new GraphqlHttpResource(engine);

        GraphqlHttpResource.GraphqlHttpResult post =
                resource.negotiatePost(Map.<String, Object>of("query", SIMPLE_QUERY), GraphqlHttpResource.GRAPHQL_RESPONSE_JSON);
        GraphqlHttpResource.GraphqlHttpResult get = resource.negotiateGet(
                SIMPLE_QUERY, null, null, null, GraphqlHttpResource.GRAPHQL_RESPONSE_JSON);

        for (GraphqlHttpResource.GraphqlHttpResult result : List.of(post, get)) {
            assertEquals(503, result.status());
            assertTrue(result.body().contains("connection refused: db.example.test:5432"), result.body());
            assertTrue(result.body().contains(GraphqlExecutionEngine.QUARKUS_DATASOURCE_DESCRIPTION), result.body());
            assertFalse(result.body().contains("\"data\""), result.body());
        }
    }

    @Test
    void databaseModeForwardsGetWithoutParsingAndRejectsANonEnginePackage() {
        GraphqlExecutionEngine engine = new GraphqlExecutionEngine(
                "database",
                () -> new FailingDataSource("connection refused: database-engine.example.test:5432"),
                "database-engine test datasource",
                DEMO_MODEL_PATH,
                GraphqlApplicationMutationProvider.none(),
                "postgresql");
        GraphqlHttpResource resource = new GraphqlHttpResource(engine);

        // A legacy runtime would parse this invalid document at the HTTP boundary. Database mode
        // must forward it untouched; it reaches package resolution, which rejects the fixture's
        // historical demo entry point instead of producing a JVM GraphQL syntax error.
        GraphqlHttpResource.GraphqlHttpResult result = resource.negotiateGet(
                "{", null, null, null, GraphqlHttpResource.GRAPHQL_RESPONSE_JSON);

        assertEquals(503, result.status());
        assertTrue(result.body().contains("does not publish the generated nine-input"), result.body());
        assertFalse(result.body().contains("GraphQL document"), result.body());
    }

    @Test
    void databaseModeRequiresAnExplicitSupportedDialectBeforePackageOrDatasourceAccess() {
        GraphqlExecutionEngine engine = new GraphqlExecutionEngine(
                "database",
                () -> {
                    throw new AssertionError("dialect validation must run before datasource access");
                },
                "must-not-be-used",
                "",
                GraphqlApplicationMutationProvider.none(),
                "oracle");

        GraphqlExecutionModeUnavailableException failure = assertThrows(
                GraphqlExecutionModeUnavailableException.class, engine::runtime);

        assertTrue(failure.getMessage().contains(GraphqlExecutionEngine.DATABASE_ENGINE_DIALECT_PROPERTY),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("postgresql"), failure.getMessage());
        assertTrue(failure.getMessage().contains("mysql"), failure.getMessage());
    }

    @Test
    void transportValidationStillAnswersBeforeTheSqlEngineIsConsulted() {
        GraphqlExecutionEngine engine = new GraphqlExecutionEngine(
                "sql",
                () -> {
                    throw new AssertionError("transport-invalid requests must not reach the engine");
                },
                "unused");

        GraphqlHttpResource.GraphqlHttpResult result = new GraphqlHttpResource(engine)
                .negotiatePost(Map.<String, Object>of("notQuery", 1), GraphqlHttpResource.GRAPHQL_RESPONSE_JSON);

        assertEquals(200, result.status());
        assertTrue(result.body().contains("unknown GraphQL request field"), result.body());
    }

    // --- telemetry listener contract --------------------------------------------------------

    @Test
    void sqlDispatchFailureEmitsFailureOutcomeToTheExecutionListener() {
        RecordingListener listener = new RecordingListener();
        GraphqlSqlModeRuntime runtime = new GraphqlSqlModeRuntime(
                () -> new FailingDataSource("connection refused"), "stub datasource", listener);

        GraphqlSqlModeUnavailableException failure = assertThrows(
                GraphqlSqlModeUnavailableException.class,
                () -> runtime.execute(
                        new GraphqlRuntimeRequest(SIMPLE_QUERY, "", "", ""),
                        GraphqlRequestContext.legacy(10L, "reader")));

        assertTrue(failure.getMessage().contains("stub datasource"), failure.getMessage());
        assertEquals(1, listener.events.size());
        RecordingListener.Event event = listener.events.get(0);
        assertEquals(TitanExecutionListener.Outcome.FAILURE, event.outcome());
        assertTrue(event.sql().startsWith("SELECT public.execute_graphql_request_with_compact_context("),
                event.sql());
        assertFalse(event.sql().contains("article"), "bound values must never reach the listener: " + event.sql());
    }

    // --- mode surface -----------------------------------------------------------------------

    @Test
    void javaModeResponsesNameTheEngineAndCarryNoFingerprint() {
        Response response = new GraphqlHttpResource(new GraphqlExecutionEngine(
                "java", () -> new FailingDataSource("unused"), "unused")).postResponse(
                Map.<String, Object>of("query", SIMPLE_QUERY), GraphqlHttpResource.GRAPHQL_RESPONSE_JSON,
                null, null, null, null, null, null, null, null, null, null);

        assertEquals("java", response.getHeaderString(GraphqlHttpResource.EXECUTION_MODE_HEADER));
        assertNull(response.getHeaderString(GraphqlHttpResource.DEPLOYMENT_FINGERPRINT_HEADER));
    }

    @Test
    void databasePackageResponsesNameTheEngineAndTheDeployedArtifact() {
        for (String mode : List.of("compiled", "sql", "database")) {
            GraphqlExecutionEngine engine = new GraphqlExecutionEngine(
                    mode, () -> new FailingDataSource("unreachable"), "stub datasource", DEMO_MODEL_PATH);

            Response response = new GraphqlHttpResource(engine).postResponse(
                    Map.<String, Object>of("query", SIMPLE_QUERY), GraphqlHttpResource.GRAPHQL_RESPONSE_JSON,
                    null, null, null, null, null, null, null, null, null, null);

            assertEquals(503, response.getStatus());
            assertEquals(mode, response.getHeaderString(GraphqlHttpResource.EXECUTION_MODE_HEADER));
            // The fingerprint binds the exact reviewed model to the checked-in fixture package.
            assertEquals(
                    io.titan.graphql.artifact.TitanGraphqlPackageBinding
                            .read(TitanGraphqlArtifactsDirectory.configuredDirectory())
                            .deploymentFingerprint(),
                    response.getHeaderString(GraphqlHttpResource.DEPLOYMENT_FINGERPRINT_HEADER));
        }
    }

    private static GraphqlExecutionEngine engine(String mode) {
        return new GraphqlExecutionEngine(mode, () -> new FailingDataSource("unused"), "test datasource");
    }

    /** Listener spy recording every observed execution. */
    private static final class RecordingListener implements TitanExecutionListener {
        record Event(String sql, Duration duration, Outcome outcome) {
        }

        private final List<Event> events = new ArrayList<>();

        @Override
        public void onExecute(String sqlWithPlaceholders, Duration duration, Outcome outcome) {
            events.add(new Event(sqlWithPlaceholders, duration, outcome));
        }
    }

    /** A datasource whose connections always fail — the unreachable-database stand-in. */
    private static final class FailingDataSource implements DataSource {
        private final String message;

        private FailingDataSource(String message) {
            this.message = message;
        }

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException(message);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException(message);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
