package io.titan.graphql.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.titan.graphql.GraphqlRequestContext;
import io.titan.graphql.GraphqlRuntimeRequest;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DatabaseGraphqlWholeRequestRuntimeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void forwardsNamedTrustedContextValuesAsTypedJson() throws Exception {
        GraphqlRequestContext context = GraphqlRequestContext.legacy(7L, "reader")
                .withValues(Map.of(
                        "articleVisibility", true,
                        "activeCustomers", false,
                        "tenantLimit", 25L,
                        "tenantLabel", "A\"B"));

        JsonNode json = JSON.readTree(DatabaseGraphqlWholeRequestRuntime.trustedContextJson(context));
        assertEquals("titan.graphql.request-context/v1", json.path("contextVersion").asText());
        assertTrue(json.at("/contextValues/articleVisibility").asBoolean());
        assertFalse(json.at("/contextValues/activeCustomers").asBoolean());
        assertEquals(25L, json.at("/contextValues/tenantLimit").asLong());
        assertEquals("A\"B", json.at("/contextValues/tenantLabel").asText());
    }

    @Test
    void doesNotInferModelSpecificValuesFromTheLegacyContextShape() throws Exception {
        GraphqlRequestContext legacy = GraphqlRequestContext.articleVisibility(7L, "reader", true);

        JsonNode json = JSON.readTree(DatabaseGraphqlWholeRequestRuntime.trustedContextJson(legacy));

        assertTrue(json.path("contextValues").isEmpty());
    }

    @Test
    void invokesExactlyOneManifestSelectedWholeRequestRoutineWithTheUntouchedEnvelope() {
        AtomicInteger prepareCount = new AtomicInteger();
        AtomicInteger commitCount = new AtomicInteger();
        AtomicInteger rollbackCount = new AtomicInteger();
        AtomicInteger statementTimeout = new AtomicInteger();
        AtomicBoolean resultRead = new AtomicBoolean();
        Map<Integer, Object> boundValues = new LinkedHashMap<>();
        ArrayList<Boolean> autoCommitTransitions = new ArrayList<>();
        ArrayList<Integer> isolationTransitions = new ArrayList<>();
        String[] preparedSql = {""};

        ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "next" -> !resultRead.getAndSet(true);
                        // The frontend must obey this fixed transport frame, not scan the
                        // GraphQL JSON. Deliberately place the opposite legacy extension in
                        // the payload to make a regression to JSON inspection observable.
                        case "getString" -> "\u001eTITAN-GRAPHQL-TRANSPORT/1 COMMIT\n"
                                + "{\"data\":{\"ok\":true},\"extensions\":{"
                                + "\"titanTransactionOutcome\":\"ROLLBACK\"}}";
                        case "close" -> null;
                        case "isWrapperFor" -> false;
                        case "unwrap" -> null;
                        default -> unsupported(method.getName());
                    };
                });
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "setString", "setBoolean" -> {
                            boundValues.put((Integer) args[0], args[1]);
                            yield null;
                        }
                        case "setQueryTimeout" -> {
                            statementTimeout.set((Integer) args[0]);
                            yield null;
                        }
                        case "executeQuery" -> resultSet;
                        case "close" -> null;
                        case "isWrapperFor" -> false;
                        case "unwrap" -> null;
                        default -> unsupported(method.getName());
                    };
                });
        Connection connection = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getAutoCommit" -> true;
                        case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                        case "setTransactionIsolation" -> {
                            isolationTransitions.add((Integer) args[0]);
                            yield null;
                        }
                        case "setAutoCommit" -> {
                            autoCommitTransitions.add((Boolean) args[0]);
                            yield null;
                        }
                        case "prepareStatement" -> {
                            prepareCount.incrementAndGet();
                            preparedSql[0] = (String) args[0];
                            yield statement;
                        }
                        case "commit" -> {
                            commitCount.incrementAndGet();
                            yield null;
                        }
                        case "rollback" -> {
                            rollbackCount.incrementAndGet();
                            yield null;
                        }
                        case "close" -> null;
                        case "isWrapperFor" -> false;
                        case "unwrap" -> null;
                        default -> unsupported(method.getName());
                    };
                });

        String expectedHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String expectedRuntimeIdentity = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
        String expectedPackageIdentity = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
        DatabaseGraphqlWholeRequestRuntime runtime = new DatabaseGraphqlWholeRequestRuntime(
                DatabaseGraphqlWholeRequestRuntime.Dialect.POSTGRESQL,
                () -> connection,
                expectedHash,
                expectedRuntimeIdentity,
                expectedPackageIdentity,
                "boundary-test database",
                new DatabaseGraphqlWholeRequestRuntime.EntryPoint("bound_api", "run_graphql"));
        String response = runtime.execute(
                new GraphqlRuntimeRequest("{ deliberately: unparsed }",
                        "SelectedOperation", "{\"amount\":9007199254740993}", "{\"trace\":true}", false),
                GraphqlRequestContext.legacy(41L, "reader"));

        assertTrue(response.contains("\"ok\":true"));
        assertTrue(response.contains("\"titanTransactionOutcome\":\"ROLLBACK\""), response);
        assertEquals(1, prepareCount.get(), "the HTTP adapter may make only one request entry-point call");
        assertEquals("SELECT bound_api.run_graphql(?, ?, ?, ?, ?, ?, ?, ?, ?)", preparedSql[0]);
        assertEquals("{ deliberately: unparsed }", boundValues.get(1));
        assertEquals("SelectedOperation", boundValues.get(2));
        assertEquals("{\"amount\":9007199254740993}", boundValues.get(3));
        assertEquals("{\"trace\":true}", boundValues.get(4));
        assertTrue(String.valueOf(boundValues.get(5)).contains("\"actorId\":41"));
        assertEquals(false, boundValues.get(6));
        assertEquals(expectedHash, boundValues.get(7));
        assertEquals(expectedRuntimeIdentity, boundValues.get(8));
        assertEquals(expectedPackageIdentity, boundValues.get(9));
        assertEquals(io.titan.graphql.frontend.DatabaseWholeRequestClient.DEFAULT_STATEMENT_TIMEOUT_SECONDS,
                statementTimeout.get());
        assertEquals(1, commitCount.get());
        assertEquals(0, rollbackCount.get());
        assertEquals(java.util.List.of(false, true), autoCommitTransitions);
        assertEquals(java.util.List.of(
                Connection.TRANSACTION_REPEATABLE_READ,
                Connection.TRANSACTION_READ_COMMITTED), isolationTransitions);
    }

    @Test
    void constrainsJdbcDeadlineToTheAuthenticatedContextBudget() {
        assertEquals(io.titan.graphql.frontend.DatabaseWholeRequestClient.DEFAULT_STATEMENT_TIMEOUT_SECONDS,
                DatabaseGraphqlWholeRequestRuntime.statementTimeoutSeconds(GraphqlRequestContext.legacy(1L, "reader")));
        assertEquals(1, DatabaseGraphqlWholeRequestRuntime.statementTimeoutSeconds(
                new GraphqlRequestContext(1L, "reader", "actor-1", "", "", "", java.util.List.of(),
                        java.util.List.of(), false, false, false, System.currentTimeMillis() - 1L)));
    }

    private static Object unsupported(String method) {
        throw new UnsupportedOperationException("unexpected JDBC call at whole-request boundary: " + method);
    }
}
