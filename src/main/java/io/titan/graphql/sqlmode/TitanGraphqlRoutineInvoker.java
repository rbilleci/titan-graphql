package io.titan.graphql.sqlmode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Executes inventory-resolved model-generated Titan carriers on either supported dialect. */
public final class TitanGraphqlRoutineInvoker {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() { };

    private final GraphqlSqlPackageEntryPoints entryPoints;

    public TitanGraphqlRoutineInvoker(TitanGraphqlGap005ArtifactMetadata metadata) {
        this.entryPoints = new GraphqlSqlPackageEntryPoints(metadata);
    }

    public List<Map<String, Object>> read(
            Connection connection,
            String methodName,
            List<?> parameters
    ) throws SQLException {
        List<?> values = parameters == null ? List.of() : List.copyOf(parameters);
        boolean mysql = dialect(connection).equals("mysql");
        String routine = entryPoints.resolve(connection, methodName);
        String sql = GraphqlSqlEntryPointDispatch.carrierSql(routine, values.size(), mysql);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.size(); index++) {
                statement.setObject(index + 1, values.get(index));
            }
            return mysql ? mysqlRows(statement, methodName) : postgresqlRows(statement, methodName);
        }
    }

    public void attest(Connection connection, String expectedSemanticHash) throws SQLException {
        if (expectedSemanticHash == null || !expectedSemanticHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expected model semantic hash must be a lowercase SHA-256 value");
        }
        String routine = entryPoints.resolve(connection, "modelSemanticHash");
        String sql = GraphqlSqlEntryPointDispatch.noArgumentFunctionSql(routine);
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("model attestation routine returned no row: " + sql);
            }
            String actual = resultSet.getString(1);
            if (!expectedSemanticHash.equals(actual)) {
                throw new SQLException("deployed Titan GraphQL model semantic hash mismatch: expected '"
                        + expectedSemanticHash + "' but database returned '" + actual + "'");
            }
        }
    }

    private static List<Map<String, Object>> postgresqlRows(
            PreparedStatement statement,
            String methodName
    ) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("PostgreSQL generated carrier '" + methodName + "' returned no row");
            }
            try {
                List<Map<String, Object>> rows = JSON.readValue(resultSet.getString(1), ROWS);
                return rows == null ? List.of() : List.copyOf(rows);
            } catch (JsonProcessingException ex) {
                throw new SQLException("PostgreSQL generated carrier '" + methodName
                        + "' returned invalid JSON", ex);
            }
        }
    }

    private static List<Map<String, Object>> mysqlRows(
            PreparedStatement statement,
            String methodName
    ) throws SQLException {
        boolean hasResult = statement.execute();
        while (true) {
            if (hasResult) {
                try (ResultSet resultSet = statement.getResultSet()) {
                    return resultSetRows(resultSet);
                }
            }
            if (statement.getUpdateCount() == -1) {
                throw new SQLException("MySQL generated carrier '" + methodName + "' returned no result set");
            }
            hasResult = statement.getMoreResults();
        }
    }

    private static List<Map<String, Object>> resultSetRows(ResultSet resultSet) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData metadata = resultSet.getMetaData();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                row.put(metadata.getColumnLabel(column).toLowerCase(Locale.ROOT), resultSet.getObject(column));
            }
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private static String dialect(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        if (product.contains("postgresql")) return "postgresql";
        if (product.contains("mysql")) return "mysql";
        throw new SQLException("unsupported database product '"
                + connection.getMetaData().getDatabaseProductName() + "'");
    }
}
