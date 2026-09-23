package io.titan.graphql.database;

import io.titan.graphql.GraphqlExecution;
import io.titan.graphql.GraphqlExecutionModeUnavailableException;
import io.titan.graphql.GraphqlModelRuntime;
import io.titan.graphql.GraphqlRequest;
import io.titan.graphql.GraphqlRequestContext;
import io.titan.graphql.GraphqlRuntimeRequest;
import io.titan.graphql.artifact.TitanGraphqlDatabasePackageIdentity;
import io.titan.graphql.frontend.DatabaseWholeRequestClient;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Thin JDBC adapter for the generated whole-request database contract.
 *
 * <p>It deliberately does not parse, select, validate, plan, or assemble GraphQL. Every client
 * envelope and the trusted transport context are bound once to the installed entry point. The
 * transport receives a separately framed transaction instruction; it never inspects GraphQL data
 * or errors to decide whether to commit.</p>
 *
 * <p>This is the package-bound Phase 5 cutover adapter. It is selectable only through the
 * explicit database execution mode, after the reviewed model, package binding, dialect, and
 * manifest-published whole-request entry point have all been verified. It is not the final
 * production architecture yet: the legacy runtimes remain until database-engine feature parity,
 * pooled package-replacement evidence, and legacy deletion are complete.</p>
 */
public final class DatabaseGraphqlWholeRequestRuntime implements GraphqlModelRuntime {

    public static final String NAME = "database-whole-request";

    public enum Dialect {
        POSTGRESQL,
        MYSQL
    }

    /**
     * Public routine identity selected from the verified Titan package manifest. The JDBC adapter
     * never accepts this from an HTTP request or a configuration string.
     */
    public record EntryPoint(String schemaName, String routineName) {
        public EntryPoint {
            if (schemaName == null || !schemaName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("database entry-point schema name is invalid");
            }
            if (routineName == null || !routineName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("database entry-point routine name is invalid");
            }
        }

        public String qualifiedName() {
            return schemaName + "." + routineName;
        }
    }

    public static final EntryPoint DEFAULT_ENTRY_POINT = new EntryPoint("public", "execute_graphql_request");

    @FunctionalInterface
    public interface ConnectionProvider {
        Connection open() throws SQLException;
    }

    private static final String CONTEXT_VERSION = "titan.graphql.request-context/v1";
    private final Dialect dialect;
    private final String expectedModelSemanticHash;
    private final String expectedRuntimeIdentity;
    private final String expectedPackageIdentity;
    private final String connectionDescription;
    private final DatabaseWholeRequestClient client;

    public DatabaseGraphqlWholeRequestRuntime(
            Dialect dialect,
            ConnectionProvider connectionProvider,
            String expectedModelSemanticHash,
            String expectedRuntimeIdentity,
            String expectedPackageIdentity,
            String connectionDescription
    ) {
        this(dialect, connectionProvider, expectedModelSemanticHash, expectedRuntimeIdentity, expectedPackageIdentity,
                connectionDescription, DEFAULT_ENTRY_POINT);
    }

    public DatabaseGraphqlWholeRequestRuntime(
            Dialect dialect,
            ConnectionProvider connectionProvider,
            String expectedModelSemanticHash,
            String expectedRuntimeIdentity,
            String expectedPackageIdentity,
            String connectionDescription,
            EntryPoint entryPoint
    ) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(connectionProvider, "connectionProvider");
        if (expectedModelSemanticHash == null || !expectedModelSemanticHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expected model semantic hash must be a lowercase SHA-256 value");
        }
        if (expectedRuntimeIdentity == null || !expectedRuntimeIdentity.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expected runtime identity must be a lowercase SHA-256 value");
        }
        this.expectedModelSemanticHash = expectedModelSemanticHash;
        this.expectedRuntimeIdentity = expectedRuntimeIdentity;
        this.expectedPackageIdentity = TitanGraphqlDatabasePackageIdentity.require(expectedPackageIdentity);
        this.connectionDescription = connectionDescription == null ? "configured database" : connectionDescription;
        EntryPoint verifiedEntryPoint = Objects.requireNonNull(entryPoint, "entryPoint");
        this.client = new DatabaseWholeRequestClient(
                dialect == Dialect.POSTGRESQL
                        ? DatabaseWholeRequestClient.Dialect.POSTGRESQL
                        : DatabaseWholeRequestClient.Dialect.MYSQL,
                connectionProvider::open,
                new DatabaseWholeRequestClient.EntryPoint(
                        verifiedEntryPoint.schemaName(), verifiedEntryPoint.routineName()));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String execute(GraphqlRuntimeRequest request, GraphqlRequestContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        try {
            return client.execute(new DatabaseWholeRequestClient.Request(
                    request.query(), request.operationName(), request.variablesJson(), request.extensionsJson(),
                    trustedContextJson(context), request.allowMutations(), expectedModelSemanticHash,
                    expectedRuntimeIdentity, expectedPackageIdentity, statementTimeoutSeconds(context))).responseJson();
        } catch (SQLException failure) {
            throw unavailable("the installed whole-request entry point could not be invoked", failure);
        } catch (RuntimeException failure) {
            if (failure instanceof GraphqlExecutionModeUnavailableException) {
                throw failure;
            }
            throw unavailable("the database connection could not serve the whole request", failure);
        }
    }

    @Override
    public GraphqlExecution executeWithPlan(GraphqlRequest request, GraphqlRequestContext context) {
        throw new UnsupportedOperationException(
                "the database whole-request runtime owns planning inside the installed engine");
    }

    static String trustedContextJson(GraphqlRequestContext context) {
        return "{\"contextVersion\":\"" + CONTEXT_VERSION + "\",\"actorId\":" + context.actorId()
                + ",\"actorRole\":\"" + jsonEscape(context.actorRole()) + "\",\"actorKey\":\""
                + jsonEscape(context.actorKey()) + "\",\"tenantId\":\"" + jsonEscape(context.tenantId())
                + "\",\"requestId\":\"" + jsonEscape(context.requestId()) + "\",\"idempotencyKey\":\""
                + jsonEscape(context.idempotencyKey()) + "\",\"policyFlags\":" + jsonStringArray(context.policyFlags())
                + ",\"enabledContextFilters\":" + jsonStringArray(context.enabledContextFilters())
                + ",\"introspectionEnabled\":" + context.introspectionEnabled()
                + ",\"contextValues\":" + contextValuesJson(context)
                + ",\"deadlineEpochMillis\":" + context.deadlineEpochMillis() + "}";
    }

    /**
     * Converts an already-authenticated deadline into JDBC's second-granularity statement
     * timeout. This is transport control, not GraphQL interpretation: the entire operation still
     * crosses the boundary once and is evaluated by the generated database routine.
     */
    static int statementTimeoutSeconds(GraphqlRequestContext context) {
        long deadline = context.deadlineEpochMillis();
        if (deadline <= 0L) {
            return DatabaseWholeRequestClient.DEFAULT_STATEMENT_TIMEOUT_SECONDS;
        }
        long remainingMillis = deadline - System.currentTimeMillis();
        if (remainingMillis <= 0L) {
            return 1;
        }
        long roundedSeconds = (remainingMillis + 999L) / 1_000L;
        return (int) Math.min(DatabaseWholeRequestClient.DEFAULT_STATEMENT_TIMEOUT_SECONDS,
                Math.max(1L, roundedSeconds));
    }

    /**
     * Serializes named, authenticated context values without making the GraphQL adapter interpret
     * them. Values are intentionally restricted to JSON scalars at this boundary; model-generated
     * database predicates decide which keys and types they accept.
     */
    private static String contextValuesJson(GraphqlRequestContext context) {
        String json = "";
        for (Map.Entry<String, Object> entry : context.values().entrySet()) {
            String prefix = json.isEmpty() ? "" : ",";
            json = json + prefix + "\"" + jsonEscape(entry.getKey()) + "\":" + contextValueJson(entry.getValue());
        }
        return "{" + json + "}";
    }

    private static String contextValueJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return String.valueOf(value);
        }
        if (value instanceof Float floating) {
            return Float.isFinite(floating) ? String.valueOf(floating) : "null";
        }
        if (value instanceof Double floating) {
            return Double.isFinite(floating) ? String.valueOf(floating) : "null";
        }
        if (value instanceof String text) {
            return "\"" + jsonEscape(text) + "\"";
        }
        // A context value with no stable JSON scalar representation must not be converted into a
        // string that could accidentally satisfy a database policy. It is deliberately absent to
        // typed engine accessors (which treat null as missing/non-matching).
        return "null";
    }

    private static String jsonStringArray(List<String> values) {
        String json = "[";
        for (int index = 0; index < values.size(); index++) {
            if (index != 0) {
                json = json + ",";
            }
            json = json + "\"" + jsonEscape(values.get(index)) + "\"";
        }
        return json + "]";
    }

    private static String jsonEscape(String value) {
        String escaped = "";
        String input = value == null ? "" : value;
        for (int index = 0; index < input.length(); index++) {
            char current = input.charAt(index);
            if (current == '\\' || current == '"') escaped = escaped + "\\" + current;
            else if (current == '\b') escaped = escaped + "\\b";
            else if (current == '\f') escaped = escaped + "\\f";
            else if (current == '\n') escaped = escaped + "\\n";
            else if (current == '\r') escaped = escaped + "\\r";
            else if (current == '\t') escaped = escaped + "\\t";
            else escaped = escaped + current;
        }
        return escaped;
    }

    private GraphqlExecutionModeUnavailableException unavailable(String cause, Throwable failure) {
        return new GraphqlExecutionModeUnavailableException(
                "database whole-request runtime (" + dialect.name().toLowerCase() + ") could not answer this request: "
                        + cause + " [database: " + connectionDescription + "]",
                failure);
    }
}
