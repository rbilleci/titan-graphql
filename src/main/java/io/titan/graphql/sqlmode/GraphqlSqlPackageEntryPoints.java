package io.titan.graphql.sqlmode;

import io.titan.graphql.artifact.TitanGraphqlEntryPointRef;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.graphql.artifact.TitanGraphqlSqlRoutineRef;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/** Resolves logical GraphQL entry points from Titan's integrity-checked package inventory. */
final class GraphqlSqlPackageEntryPoints {

    private final TitanGraphqlGap005ArtifactMetadata metadata;

    GraphqlSqlPackageEntryPoints(TitanGraphqlGap005ArtifactMetadata metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("Titan package metadata is required");
        }
        this.metadata = metadata;
    }

    String resolve(Connection connection, GraphqlSqlEntryPointDispatch.Invocation invocation) throws SQLException {
        String dialect = dialect(connection);
        String methodName = GraphqlSqlEntryPointDispatch.methodName(invocation);
        TitanGraphqlSqlRoutineRef match = null;
        for (TitanGraphqlEntryPointRef entryPoint : metadata.entryPoints()) {
            if (!methodName.equals(entryPoint.methodName())) {
                continue;
            }
            for (TitanGraphqlSqlRoutineRef routine : entryPoint.routines()) {
                if (dialect.equals(routine.dialect())) {
                    if (match != null) {
                        throw new SQLException("Titan package contains multiple '" + methodName
                                + "' routines for dialect '" + dialect + "'");
                    }
                    match = routine;
                }
            }
        }
        if (match == null) {
            throw new SQLException("Titan package does not contain GraphQL entry point '" + methodName
                    + "' for database dialect '" + dialect + "'");
        }
        // The dispatch layer performs a second strict identifier check before building SQL.
        return match.qualifiedName();
    }

    private static String dialect(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        if (product.contains("postgresql")) {
            return "postgresql";
        }
        if (product.contains("mysql")) {
            return "mysql";
        }
        throw new SQLException("unsupported database product '"
                + connection.getMetaData().getDatabaseProductName() + "'");
    }
}
