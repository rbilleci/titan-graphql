package io.titan.graphql.sqlmode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The single source of truth for invoking the deployed kernel entry points
 * ({@code SELECT public.execute_graphql*(...)}) over JDBC.
 *
 * <p>Extracted from the W2 conformance corpus
 * ({@code io.titan.graphql.conformance.GraphqlSqlModeConformanceCorpus}) so the live SQL
 * execution mode (completion plan W5.1) and the equivalence corpus dispatch the exact same
 * prepared-statement shapes — the corpus now delegates here instead of carrying its own
 * copy.</p>
 *
 * <p>Each {@link Invocation} record mirrors one public {@code @StoredFunction} of
 * {@code DemoBlogTitanGraphqlFunctions}; the SQL leg is a plain prepared
 * {@code SELECT public.&lt;function&gt;(?, ...)} returning the GraphQL response document as
 * a single {@code TEXT} column.</p>
 *
 * <p>Plain prepared statements are deliberate: core's {@code JdbcExecutor} typed paths
 * require DSL builders and its raw-SQL convenience paths take no bind parameters, so
 * deployed {@code @StoredFunction} routines cannot be called through it without splicing
 * values into SQL text (TG-BLK-010). The execution-listener contract is mirrored by
 * {@link GraphqlSqlModeRuntime} instead.</p>
 */
public final class GraphqlSqlEntryPointDispatch {

    private GraphqlSqlEntryPointDispatch() {
    }

    /** One kernel entry-point invocation, executable on both legs with identical arguments. */
    public sealed interface Invocation {

        /** {@code executeGraphql(query, actorId, actorRole)}. */
        record Execute(String query, long actorId, String actorRole) implements Invocation {
        }

        /** {@code executeGraphqlRequest(query, operationName, actorId, actorRole)}. */
        record Request(String query, String operationName, long actorId, String actorRole)
                implements Invocation {
        }

        /** {@code executeGraphqlRequestWithVariables(...)}. */
        record RequestWithVariables(
                String query,
                String operationName,
                String variablesJson,
                String extensionsJson,
                long actorId,
                String actorRole
        ) implements Invocation {
        }

        /** {@code executeGraphqlWithContext(...)}. */
        record WithContext(
                String query,
                long actorId,
                String actorRole,
                boolean enablePublishedVisibility,
                boolean hasArticleVisibility,
                boolean articleVisibility
        ) implements Invocation {
        }

        /** {@code executeGraphqlWithIntrospection(query, actorId, actorRole, enabled)}. */
        record WithIntrospection(String query, long actorId, String actorRole, boolean enableIntrospection)
                implements Invocation {
        }

        /** {@code executeGraphqlRequestWithCompactContext(...)} — the full public envelope. */
        record CompactContext(
                String query,
                String operationName,
                String variablesJson,
                String extensionsJson,
                long actorId,
                String actorRole,
                boolean enablePublishedVisibility,
                boolean hasArticleVisibility,
                boolean articleVisibility,
                boolean enableIntrospection,
                String tenantId,
                String requestId,
                String policyFlags,
                String enabledContextFilters,
                long deadlineBudgetMillis
        ) implements Invocation {
        }
    }

    /** Returns the placeholder SQL ({@code ?} parameters, never bound values) for an invocation. */
    public static String placeholderSql(Invocation invocation) {
        return switch (invocation) {
            case Invocation.Execute ignored -> "SELECT public.execute_graphql(?, ?, ?)";
            case Invocation.Request ignored -> "SELECT public.execute_graphql_request(?, ?, ?, ?)";
            case Invocation.RequestWithVariables ignored ->
                    "SELECT public.execute_graphql_request_with_variables(?, ?, ?, ?, ?, ?)";
            case Invocation.WithContext ignored -> "SELECT public.execute_graphql_with_context(?, ?, ?, ?, ?, ?)";
            case Invocation.WithIntrospection ignored ->
                    "SELECT public.execute_graphql_with_introspection(?, ?, ?, ?)";
            case Invocation.CompactContext ignored ->
                    "SELECT public.execute_graphql_request_with_compact_context("
                            + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        };
    }

    /** Dispatches one invocation as {@code SELECT public.<function>(...)} against the deployed routines. */
    public static String execute(Connection connection, Invocation invocation) throws SQLException {
        String sql = placeholderSql(invocation);
        return switch (invocation) {
            case Invocation.Execute c -> selectText(connection, sql,
                    c.query(), c.actorId(), c.actorRole());
            case Invocation.Request c -> selectText(connection, sql,
                    c.query(), c.operationName(), c.actorId(), c.actorRole());
            case Invocation.RequestWithVariables c -> selectText(connection, sql,
                    c.query(), c.operationName(), c.variablesJson(), c.extensionsJson(), c.actorId(), c.actorRole());
            case Invocation.WithContext c -> selectText(connection, sql,
                    c.query(), c.actorId(), c.actorRole(),
                    c.enablePublishedVisibility(), c.hasArticleVisibility(), c.articleVisibility());
            case Invocation.WithIntrospection c -> selectText(connection, sql,
                    c.query(), c.actorId(), c.actorRole(), c.enableIntrospection());
            case Invocation.CompactContext c -> selectText(connection, sql,
                    c.query(), c.operationName(), c.variablesJson(), c.extensionsJson(), c.actorId(), c.actorRole(),
                    c.enablePublishedVisibility(), c.hasArticleVisibility(), c.articleVisibility(),
                    c.enableIntrospection(), c.tenantId(), c.requestId(), c.policyFlags(),
                    c.enabledContextFilters(), c.deadlineBudgetMillis());
        };
    }

    private static String selectText(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next() == false) {
                    throw new SQLException("entry point returned no row: " + sql);
                }
                return resultSet.getString(1);
            }
        }
    }
}
