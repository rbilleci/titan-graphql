package io.titan.graphql.frontend;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Framework-free, one-call JDBC transport for an installed database-resident GraphQL engine.
 *
 * <p>This class deliberately knows nothing about GraphQL grammar, schema, fields, policies, or
 * response data. A web framework supplies an authenticated context already serialized as JSON and
 * returns the database-completed response unchanged. This source set is the basis for the final
 * frontend-only serving artifact.</p>
 */
public final class DatabaseWholeRequestClient {

    /**
     * A serving request must never wait indefinitely for a database routine. Deployments can
     * choose a lower or higher ceiling, but the default keeps the frontend safe when no explicit
     * deployment value has been supplied.
     */
    public static final int DEFAULT_STATEMENT_TIMEOUT_SECONDS = 30;

    public enum Dialect {
        POSTGRESQL,
        MYSQL
    }

    @FunctionalInterface
    public interface ConnectionProvider {
        Connection open() throws SQLException;
    }

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

    public record Request(
            String query,
            String operationName,
            String variablesJson,
            String extensionsJson,
            String trustedContextJson,
            boolean allowMutations,
            String expectedModelSemanticHash,
            String expectedRuntimeIdentity,
            String expectedPackageIdentity,
            int statementTimeoutSeconds
    ) {
        public Request {
            query = query == null ? "" : query;
            operationName = operationName == null ? "" : operationName;
            variablesJson = variablesJson == null ? "" : variablesJson;
            extensionsJson = extensionsJson == null ? "" : extensionsJson;
            trustedContextJson = trustedContextJson == null ? "" : trustedContextJson;
            if (expectedModelSemanticHash == null || !expectedModelSemanticHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("expected model semantic hash must be a lowercase SHA-256 value");
            }
            if (expectedRuntimeIdentity == null || !expectedRuntimeIdentity.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("expected runtime identity must be a lowercase SHA-256 value");
            }
            if (expectedPackageIdentity == null || !expectedPackageIdentity.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("expected package identity must be a lowercase SHA-256 value");
            }
            if (statementTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("database statement timeout must be positive");
            }
        }

        /** Compatibility constructor using the frontend's safe default deadline. */
        public Request(
                String query,
                String operationName,
                String variablesJson,
                String extensionsJson,
                String trustedContextJson,
                boolean allowMutations,
                String expectedModelSemanticHash,
                String expectedRuntimeIdentity,
                String expectedPackageIdentity
        ) {
            this(query, operationName, variablesJson, extensionsJson, trustedContextJson, allowMutations,
                    expectedModelSemanticHash, expectedRuntimeIdentity, expectedPackageIdentity,
                    DEFAULT_STATEMENT_TIMEOUT_SECONDS);
        }
    }

    private static final String TRANSPORT_PREFIX = "\u001eTITAN-GRAPHQL-TRANSPORT/1 ";
    private static final String TRANSPORT_SEPARATOR = "\n";

    /** A completed database call, before its GraphQL payload is written to HTTP. */
    public record Response(String responseJson, TransactionOutcome transactionOutcome) {
        public Response {
            if (responseJson == null || responseJson.isEmpty()) {
                throw new IllegalArgumentException("database whole-request response must not be empty");
            }
            Objects.requireNonNull(transactionOutcome, "transactionOutcome");
        }
    }

    /** The transport lifecycle instruction issued separately from the GraphQL response. */
    public enum TransactionOutcome {
        COMMIT,
        ROLLBACK
    }

    private final Dialect dialect;
    private final ConnectionProvider connectionProvider;
    private final EntryPoint entryPoint;
    private final int statementTimeoutSeconds;

    public DatabaseWholeRequestClient(Dialect dialect, ConnectionProvider connectionProvider, EntryPoint entryPoint) {
        this(dialect, connectionProvider, entryPoint, DEFAULT_STATEMENT_TIMEOUT_SECONDS);
    }

    /**
     * Creates a whole-request transport with a deployment-owned maximum database statement
     * duration. The request can ask for less time (for example from an authenticated deadline),
     * but can never extend this ceiling.
     */
    public DatabaseWholeRequestClient(
            Dialect dialect,
            ConnectionProvider connectionProvider,
            EntryPoint entryPoint,
            int statementTimeoutSeconds
    ) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
        this.entryPoint = Objects.requireNonNull(entryPoint, "entryPoint");
        if (statementTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("database statement timeout must be positive");
        }
        this.statementTimeoutSeconds = statementTimeoutSeconds;
    }

    /** Invokes exactly one installed entry point and owns only the surrounding JDBC transaction. */
    public Response execute(Request request) throws SQLException {
        Objects.requireNonNull(request, "request");
        try (Connection connection = connectionProvider.open()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            int originalIsolation = connection.getTransactionIsolation();
            boolean isolationChanged = originalIsolation != Connection.TRANSACTION_REPEATABLE_READ;
            if (isolationChanged) {
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            }
            if (originalAutoCommit) {
                connection.setAutoCommit(false);
            }
            try {
                beginRequestTransaction(connection);
                Response response = invoke(connection, request);
                if (response.transactionOutcome() == TransactionOutcome.COMMIT) connection.commit();
                else connection.rollback();
                resetMySqlSession(connection);
                return response;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                try {
                    resetMySqlSession(connection);
                } catch (SQLException resetFailure) {
                    failure.addSuppressed(resetFailure);
                }
                throw failure;
            } finally {
                if (originalAutoCommit) {
                    connection.setAutoCommit(true);
                }
                if (isolationChanged) {
                    connection.setTransactionIsolation(originalIsolation);
                }
            }
        }
    }

    /**
     * Establishes the database-owned request boundary before the single GraphQL entry-point call.
     *
     * <p>With MySQL, merely disabling JDBC autocommit does not reliably reset session state left
     * by a preceding stored-procedure request on a pooled connection. An explicit transaction
     * gives each envelope the required {@code BEGIN -> one routine call -> commit/rollback}
     * lifecycle. PostgreSQL begins its transaction on the entry-point statement once autocommit
     * has been disabled.</p>
     */
    private void beginRequestTransaction(Connection connection) throws SQLException {
        if (dialect != Dialect.MYSQL) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute("START TRANSACTION");
        }
    }

    /**
     * Resets MySQL's server-side connection state after an envelope has reached its explicit
     * commit/rollback outcome. Stored-program execution can retain session-local state that JDBC
     * transaction completion does not clear; Connector/J maps this call to COM_RESET_CONNECTION.
     * The GraphQL execution remains one database entry-point call—the reset is transport cleanup
     * before a pooled connection becomes eligible for another request.
     */
    private void resetMySqlSession(Connection connection) throws SQLException {
        if (dialect != Dialect.MYSQL) return;
        try {
            Class<?> mysqlConnection = Class.forName("com.mysql.cj.jdbc.JdbcConnection");
            Object unwrapped = connection.unwrap(mysqlConnection);
            if (unwrapped == null || mysqlConnection.isInstance(unwrapped) == false) {
                throw new SQLException("MySQL whole-request transport could not unwrap a Connector/J connection");
            }
            mysqlConnection.getMethod("resetServerState").invoke(unwrapped);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException failure) {
            throw new SQLException("MySQL whole-request transport requires Connector/J session reset support", failure);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof SQLException sqlFailure) throw sqlFailure;
            throw new SQLException("MySQL whole-request session reset failed", cause);
        }
    }

    private Response invoke(Connection connection, Request request) throws SQLException {
        if (dialect == Dialect.POSTGRESQL) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + entryPoint.qualifiedName() + "(?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setQueryTimeout(effectiveStatementTimeoutSeconds(request));
                bind(statement, request);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) throw new SQLException("whole-request function returned no response row");
                    return decodePostgresqlTransport(resultSet.getString(1));
                }
            }
        }
        try (CallableStatement statement = connection.prepareCall(
                "CALL " + entryPoint.qualifiedName() + "(?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setQueryTimeout(effectiveStatementTimeoutSeconds(request));
            bind(statement, request);
            boolean hasResultSet = statement.execute();
            while (!hasResultSet && statement.getUpdateCount() != -1) {
                hasResultSet = statement.getMoreResults();
            }
            if (!hasResultSet) throw new SQLException("whole-request procedure returned no response result set");
            String responseJson;
            String transactionOutcome;
            try (ResultSet resultSet = statement.getResultSet()) {
                if (!resultSet.next()) throw new SQLException("whole-request procedure returned an empty response result set");
                responseJson = resultSet.getString("response_json");
                transactionOutcome = resultSet.getString("transaction_outcome");
            }
            drainMySqlProcedureResults(statement);
            return decodeMySqlTransport(responseJson, transactionOutcome);
        }
    }

    /**
     * Drains update counts emitted after MySQL's single response result set before a pooled
     * connection returns to service. Generated routines finish their telemetry update after
     * publishing the response; leaving that protocol tail unread can corrupt a later request on
     * the same JDBC session even though this adapter makes exactly one GraphQL call.
     */
    private static void drainMySqlProcedureResults(CallableStatement statement) throws SQLException {
        boolean hasResultSet = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
        while (hasResultSet || statement.getUpdateCount() != -1) {
            hasResultSet = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
        }
    }

    private int effectiveStatementTimeoutSeconds(Request request) {
        return Math.min(statementTimeoutSeconds, request.statementTimeoutSeconds());
    }

    private static void bind(PreparedStatement statement, Request request) throws SQLException {
        statement.setString(1, request.query());
        statement.setString(2, request.operationName());
        statement.setString(3, request.variablesJson());
        statement.setString(4, request.extensionsJson());
        statement.setString(5, request.trustedContextJson());
        statement.setBoolean(6, request.allowMutations());
        statement.setString(7, request.expectedModelSemanticHash());
        statement.setString(8, request.expectedRuntimeIdentity());
        statement.setString(9, request.expectedPackageIdentity());
    }

    private static Response decodePostgresqlTransport(String framedResponse) throws SQLException {
        if (framedResponse == null || framedResponse.startsWith(TRANSPORT_PREFIX) == false) {
            throw new SQLException("whole-request function returned no Titan transport frame");
        }
        int separator = framedResponse.indexOf(TRANSPORT_SEPARATOR, TRANSPORT_PREFIX.length());
        if (separator < 0) {
            throw new SQLException("whole-request function returned an incomplete Titan transport frame");
        }
        return new Response(
                framedResponse.substring(separator + TRANSPORT_SEPARATOR.length()),
                decodeOutcome(framedResponse.substring(TRANSPORT_PREFIX.length(), separator)));
    }

    private static Response decodeMySqlTransport(String responseJson, String outcome) throws SQLException {
        return new Response(responseJson, decodeOutcome(outcome));
    }

    private static TransactionOutcome decodeOutcome(String value) throws SQLException {
        try {
            return TransactionOutcome.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("whole-request routine returned an invalid Titan transaction outcome", invalid);
        }
    }
}
