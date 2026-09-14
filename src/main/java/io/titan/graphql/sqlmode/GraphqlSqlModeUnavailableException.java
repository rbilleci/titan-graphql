package io.titan.graphql.sqlmode;

/**
 * SQL execution mode could not answer a request: the configured datasource is unreachable,
 * the Titan migrations are not deployed on it, or no datasource is configured at all.
 *
 * <p>Failure honesty (completion plan W5.1): this is surfaced to the client as a descriptive
 * 503-style GraphQL error naming the mode, the datasource, and the remedy. There is never a
 * silent fallback to Java mode.</p>
 */
public final class GraphqlSqlModeUnavailableException extends RuntimeException {

    public GraphqlSqlModeUnavailableException(String message) {
        super(message);
    }

    public GraphqlSqlModeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Builds the canonical descriptive message: mode + datasource + concrete cause + remedy. */
    public static GraphqlSqlModeUnavailableException describe(
            String dataSourceDescription, String cause, Throwable failure) {
        String message = "SQL execution mode (titan.graphql.execution.mode=sql) could not answer this request: "
                + cause
                + " [datasource: " + dataSourceDescription + "]."
                + " Remedy: deploy the packaged Titan migrations (titanPackage output"
                + " R__titan_010_runtime.sql + R__titan_020_routines.sql, after ddl/postgres/titan_graphql_postgres.sql)"
                + " onto a reachable PostgreSQL and point the Quarkus datasource at it,"
                + " or switch titan.graphql.execution.mode back to java.";
        return failure == null
                ? new GraphqlSqlModeUnavailableException(message)
                : new GraphqlSqlModeUnavailableException(message, failure);
    }
}
