package io.titan.graphql.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Unit evidence that lifecycle control uses the fixed database protocol, never GraphQL JSON. */
class DatabaseWholeRequestClientTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void mysqlCommitComesFromTheDedicatedOutcomeColumnNotTheGraphqlPayload() throws Exception {
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger rollbacks = new AtomicInteger();
        AtomicInteger statementTimeout = new AtomicInteger();
        AtomicInteger getMoreResults = new AtomicInteger();
        AtomicInteger sessionResets = new AtomicInteger();
        AtomicInteger requestTransactions = new AtomicInteger();
        AtomicBoolean rowRead = new AtomicBoolean();
        String[] call = {""};
        String payload = "{\"data\":{\"ok\":true},\"extensions\":{"
                + "\"titanTransactionOutcome\":\"ROLLBACK\"}}";

        // The transport resets a real Connector/J session after a MySQL envelope. Keep this
        // proxy at the JDBC boundary rather than weakening production cleanup for unit tests.
        Class<?> mysqlConnectionType = Class.forName("com.mysql.cj.jdbc.JdbcConnection");
        Object mysqlConnection = Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{mysqlConnectionType}, (proxy, method, args) -> switch (method.getName()) {
                    case "resetServerState" -> {
                        sessionResets.incrementAndGet();
                        yield null;
                    }
                    case "isWrapperFor" -> false;
                    case "unwrap" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Statement transactionStatement = (Statement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Statement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> {
                        assertEquals("START TRANSACTION", args[0]);
                        requestTransactions.incrementAndGet();
                        yield false;
                    }
                    case "close" -> null;
                    case "isWrapperFor" -> false;
                    case "unwrap" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> rowRead.getAndSet(true) == false;
                    case "getString" -> "response_json".equals(args[0]) ? payload : "COMMIT";
                    case "close" -> null;
                    case "isWrapperFor" -> false;
                    case "unwrap" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        CallableStatement statement = (CallableStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{CallableStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setString", "setBoolean", "close" -> null;
                    case "setQueryTimeout" -> {
                        statementTimeout.set((Integer) args[0]);
                        yield null;
                    }
                    case "execute" -> true;
                    case "getResultSet" -> resultSet;
                    case "getMoreResults" -> {
                        getMoreResults.incrementAndGet();
                        yield false;
                    }
                    case "getUpdateCount" -> -1;
                    case "isWrapperFor" -> false;
                    case "unwrap" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Connection connection = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAutoCommit" -> true;
                    case "setAutoCommit", "close" -> null;
                    case "prepareCall" -> {
                        call[0] = (String) args[0];
                        yield statement;
                    }
                    case "createStatement" -> transactionStatement;
                    case "commit" -> {
                        commits.incrementAndGet();
                        yield null;
                    }
                    case "rollback" -> {
                        rollbacks.incrementAndGet();
                        yield null;
                    }
                    case "isWrapperFor" -> false;
                    case "unwrap" -> {
                        if (args[0] == mysqlConnectionType) yield mysqlConnection;
                        throw new UnsupportedOperationException("unwrap " + args[0]);
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        DatabaseWholeRequestClient client = new DatabaseWholeRequestClient(
                DatabaseWholeRequestClient.Dialect.MYSQL,
                () -> connection,
                new DatabaseWholeRequestClient.EntryPoint("bound_api", "run_graphql"));
        DatabaseWholeRequestClient.Response response = client.execute(new DatabaseWholeRequestClient.Request(
                "{ deliberately: unparsed }", "", "{}", "{}", "{}", true, HASH, HASH, HASH, 2));

        assertEquals(payload, response.responseJson());
        assertEquals(DatabaseWholeRequestClient.TransactionOutcome.COMMIT, response.transactionOutcome());
        assertEquals("CALL bound_api.run_graphql(?, ?, ?, ?, ?, ?, ?, ?, ?)", call[0]);
        assertEquals(1, commits.get(), "the outcome column, rather than GraphQL JSON, controls commit");
        assertEquals(0, rollbacks.get());
        assertEquals(1, requestTransactions.get(),
                "a MySQL request has an explicit transaction before its one GraphQL routine call");
        assertEquals(1, sessionResets.get(),
                "a completed MySQL envelope must reset stored-program state before reuse");
        assertEquals(1, getMoreResults.get(),
                "the adapter drains the generated procedure's post-response protocol tail");
        assertEquals(2, statementTimeout.get(),
                "the one database call honors the shorter trusted statement deadline");
        assertEquals(DatabaseWholeRequestClient.DEFAULT_STATEMENT_TIMEOUT_SECONDS,
                new DatabaseWholeRequestClient.Request("{ deliberately: unparsed }", "", "{}", "{}", "{}",
                        true, HASH, HASH, HASH).statementTimeoutSeconds(),
                "callers without an authenticated deadline still receive a bounded default");
        assertTrue(response.responseJson().contains("\"titanTransactionOutcome\":\"ROLLBACK\""));
    }
}
