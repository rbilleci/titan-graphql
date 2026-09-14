package io.titan.graphql.sqlmode;

import io.titan.graphql.GraphqlExecution;
import io.titan.graphql.GraphqlModelRuntime;
import io.titan.graphql.GraphqlRequest;
import io.titan.graphql.GraphqlRequestContext;
import io.titan.graphql.GraphqlRuntimeRequest;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.runtime.jdbc.JdbcTelemetrySink;
import io.titan.runtime.jdbc.TitanExecutionListener;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Live SQL execution mode (completion plan W5.1): answers GraphQL runtime requests by calling
 * the DEPLOYED stored functions over a JDBC {@link DataSource} instead of the in-JVM kernel.
 *
 * <p>Every request becomes one prepared
 * {@code SELECT public.execute_graphql_request_with_compact_context(?, ... )} — the exact
 * entry point the Java-mode HTTP path delegates to
 * ({@code DemoBlogGraphqlRuntime}), with the identical argument mapping — dispatched through
 * {@link GraphqlSqlEntryPointDispatch} (the W2 corpus dispatch shapes, extracted).</p>
 *
 * <p><b>Telemetry.</b> Each dispatch is reported to core's {@link TitanExecutionListener}
 * with the placeholder SQL (never bound values), the wall-clock duration, and the outcome,
 * mirroring {@code JdbcExecutor}'s observed-execution contract (listener failures propagate
 * on success and ride along as suppressed exceptions on failure). The production listener is
 * core's {@link JdbcTelemetrySink} constructed on the same serving {@link DataSource}, so
 * telemetry rows land in {@code titan_runtime.telemetry} of the serving database — the table
 * the deployed {@code R__titan_010_runtime.sql} migration creates.</p>
 *
 * <p><b>Failure honesty.</b> Any failure — no datasource configured, connection refused,
 * routines not deployed, telemetry write failure — surfaces as a descriptive
 * {@link GraphqlSqlModeUnavailableException} naming the mode, the datasource, and the
 * remedy. There is never a silent fallback to Java mode.</p>
 */
public final class GraphqlSqlModeRuntime implements GraphqlModelRuntime {

    public static final String NAME = "demo-blog-sql";

    private final Supplier<DataSource> dataSourceSupplier;
    private final String dataSourceDescription;
    private final Function<DataSource, TitanExecutionListener> listenerFactory;
    private final GraphqlSqlPackageEntryPoints packageEntryPoints;
    private final TitanGraphqlRoutineInvoker routineInvoker;
    private final String expectedModelSemanticHash;

    private volatile DataSource resolvedDataSource;
    private volatile TitanExecutionListener resolvedListener;
    private volatile boolean databasePackageAttested;

    /** Production wiring with entry-point identities sourced from the verified Titan package. */
    public GraphqlSqlModeRuntime(
            Supplier<DataSource> dataSourceSupplier,
            String dataSourceDescription,
            TitanGraphqlGap005ArtifactMetadata packageMetadata,
            String expectedModelSemanticHash
    ) {
        this(dataSourceSupplier, dataSourceDescription, JdbcTelemetrySink::new, packageMetadata,
                expectedModelSemanticHash);
    }

    /** Test seam: a fixed listener instead of the datasource-bound telemetry sink. */
    public GraphqlSqlModeRuntime(
            Supplier<DataSource> dataSourceSupplier,
            String dataSourceDescription,
            TitanExecutionListener listener
    ) {
        this(dataSourceSupplier, dataSourceDescription, ignored -> listener, null, "");
        Objects.requireNonNull(listener, "listener");
    }

    private GraphqlSqlModeRuntime(
            Supplier<DataSource> dataSourceSupplier,
            String dataSourceDescription,
            Function<DataSource, TitanExecutionListener> listenerFactory,
            TitanGraphqlGap005ArtifactMetadata packageMetadata,
            String expectedModelSemanticHash
    ) {
        this.dataSourceSupplier = Objects.requireNonNull(dataSourceSupplier, "dataSourceSupplier");
        this.dataSourceDescription = Objects.requireNonNull(dataSourceDescription, "dataSourceDescription");
        this.listenerFactory = Objects.requireNonNull(listenerFactory, "listenerFactory");
        this.packageEntryPoints = packageMetadata == null ? null : new GraphqlSqlPackageEntryPoints(packageMetadata);
        this.routineInvoker = packageMetadata == null ? null : new TitanGraphqlRoutineInvoker(packageMetadata);
        this.expectedModelSemanticHash = expectedModelSemanticHash == null ? "" : expectedModelSemanticHash;
        if (packageMetadata != null && !this.expectedModelSemanticHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expected model semantic hash must be a lowercase SHA-256 value");
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String execute(GraphqlRuntimeRequest request, GraphqlRequestContext context) {
        return dispatch(compactContextInvocation(request, context));
    }

    /**
     * Plan-level execution is a Java-engine surface; in SQL mode the lowered kernel inside the
     * database owns planning and execution end to end.
     */
    @Override
    public GraphqlExecution executeWithPlan(GraphqlRequest request, GraphqlRequestContext context) {
        throw new UnsupportedOperationException(
                "SQL execution mode serves lowered kernel responses; plan-level execution is Java-mode only");
    }

    /**
     * The identical argument mapping {@code DemoBlogGraphqlRuntime} applies before invoking the
     * in-JVM compact-context entry point — both modes feed the kernel the same 15-tuple.
     */
    private static GraphqlSqlEntryPointDispatch.Invocation.CompactContext compactContextInvocation(
            GraphqlRuntimeRequest request, GraphqlRequestContext context) {
        return new GraphqlSqlEntryPointDispatch.Invocation.CompactContext(
                request.query(),
                request.operationName(),
                request.variablesJson(),
                request.extensionsJson(),
                context.actorId(),
                context.actorRole(),
                context.contextFilterEnabled("publishedVisibility"),
                context.hasArticleVisibility(),
                context.articleVisibility(),
                context.introspectionEnabled(),
                context.tenantId(),
                context.requestId(),
                String.join(",", context.policyFlags()),
                String.join(",", context.enabledContextFilters()),
                context.deadlineEpochMillis()
        );
    }

    private String dispatch(GraphqlSqlEntryPointDispatch.Invocation invocation) {
        DataSource dataSource = resolveDataSource();
        TitanExecutionListener listener = resolveListener(dataSource);
        String placeholderSql = GraphqlSqlEntryPointDispatch.placeholderSql(invocation);

        long startNanos = System.nanoTime();
        String responseJson;
        try (Connection connection = dataSource.getConnection()) {
            if (packageEntryPoints == null) {
                responseJson = GraphqlSqlEntryPointDispatch.execute(connection, invocation);
            } else {
                attestDatabasePackage(connection);
                String qualifiedRoutine = packageEntryPoints.resolve(connection, invocation);
                placeholderSql = GraphqlSqlEntryPointDispatch.placeholderSql(invocation, qualifiedRoutine);
                responseJson = GraphqlSqlEntryPointDispatch.execute(connection, invocation, qualifiedRoutine);
            }
        } catch (SQLException ex) {
            GraphqlSqlModeUnavailableException failure = GraphqlSqlModeUnavailableException.describe(
                    dataSourceDescription,
                    "the deployed stored functions could not be executed (" + ex.getMessage() + ")",
                    ex);
            notify(listener, placeholderSql, startNanos, TitanExecutionListener.Outcome.FAILURE, failure);
            throw failure;
        } catch (RuntimeException ex) {
            GraphqlSqlModeUnavailableException failure = GraphqlSqlModeUnavailableException.describe(
                    dataSourceDescription,
                    "the datasource refused a connection (" + ex.getMessage() + ")",
                    ex);
            notify(listener, placeholderSql, startNanos, TitanExecutionListener.Outcome.FAILURE, failure);
            throw failure;
        }
        // Success path: a telemetry failure must surface (never swallowed), as a descriptive
        // unavailability instead of an opaque 500 — same honesty contract as the dispatch itself.
        try {
            notify(listener, placeholderSql, startNanos, TitanExecutionListener.Outcome.SUCCESS, null);
        } catch (RuntimeException listenerFailure) {
            throw GraphqlSqlModeUnavailableException.describe(
                    dataSourceDescription,
                    "the request was answered but execution telemetry could not be recorded ("
                            + listenerFailure.getMessage() + ")",
                    listenerFailure);
        }
        return responseJson;
    }

    /**
     * Proves the connected database carries routines generated from the bound model, rather than
     * trusting that a matching package sidecar was deployed to this particular datasource.
     */
    private void attestDatabasePackage(Connection connection) throws SQLException {
        if (databasePackageAttested) {
            return;
        }
        synchronized (this) {
            if (databasePackageAttested) {
                return;
            }
            routineInvoker.attest(connection, expectedModelSemanticHash);
            databasePackageAttested = true;
        }
    }

    private DataSource resolveDataSource() {
        DataSource dataSource = resolvedDataSource;
        if (dataSource != null) {
            return dataSource;
        }
        synchronized (this) {
            if (resolvedDataSource == null) {
                try {
                    resolvedDataSource = Objects.requireNonNull(dataSourceSupplier.get(), "dataSource");
                } catch (GraphqlSqlModeUnavailableException ex) {
                    throw ex;
                } catch (RuntimeException ex) {
                    throw GraphqlSqlModeUnavailableException.describe(
                            dataSourceDescription,
                            "no usable datasource is configured (" + ex.getMessage() + ")",
                            ex);
                }
            }
            return resolvedDataSource;
        }
    }

    private TitanExecutionListener resolveListener(DataSource dataSource) {
        TitanExecutionListener listener = resolvedListener;
        if (listener != null) {
            return listener;
        }
        synchronized (this) {
            if (resolvedListener == null) {
                resolvedListener = Objects.requireNonNull(listenerFactory.apply(dataSource), "listener");
            }
            return resolvedListener;
        }
    }

    // TG-BLK-010: JdbcExecutor offers no parameterized raw-SQL path, so routine dispatch is
    // hand-rolled prepared statements and this method mirrors the executor's listener contract.
    /** Mirrors {@code JdbcExecutor.notify}: listener failures propagate on success, suppress on failure. */
    private static void notify(
            TitanExecutionListener listener,
            String sqlWithPlaceholders,
            long startNanos,
            TitanExecutionListener.Outcome outcome,
            Throwable primaryFailure
    ) {
        Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
        try {
            listener.onExecute(sqlWithPlaceholders, duration, outcome);
        } catch (RuntimeException listenerFailure) {
            if (primaryFailure == null) {
                throw listenerFailure;
            }
            primaryFailure.addSuppressed(listenerFailure);
        }
    }
}
