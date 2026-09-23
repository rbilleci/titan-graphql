package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.titan.graphql.GraphqlApplicationMutationProvider;
import io.titan.graphql.GraphqlExecutionEngine;
import io.titan.graphql.GraphqlHttpResource;
import io.titan.graphql.conformance.DemoBlogSqlDeployment;
import io.titan.runtime.jdbc.SingleConnectionDataSource;
import io.titan.runtime.testing.DatabaseTarget;
import io.titan.runtime.testing.TitanTest;
import io.titan.runtime.testing.TitanTestContext;
import jakarta.ws.rs.core.Response;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Exercises MySQL's procedure/result-set adapter without any JVM GraphQL execution. */
@Tag("docker")
@Tag("database-engine-mysql")
@TitanTest(targets = {DatabaseTarget.MYSQL})
class DatabaseGraphqlMySqlEngineIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void installedProcedureReturnsOneCompletedGraphqlResponseResultSet(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.MYSQL);
        DemoBlogSqlDeployment.deployPackagedKernelMySql(connection);

        JsonNode literal = execute(connection, "{ article(id: 1) { id title titleLength } }", "", "{}");
        assertEquals(1, literal.at("/data/article/id").asInt());
        assertEquals("Titan GraphQL proof", literal.at("/data/article/title").asText());
        assertEquals(19, literal.at("/data/article/titleLength").asInt());

        JsonNode variableAlias = execute(connection,
                "query FindArticle($articleId: Int!) { chosen: article(id: $articleId) { id title } }",
                "FindArticle", "{\"articleId\":1}");
        assertEquals(1, variableAlias.at("/data/chosen/id").asInt(), variableAlias::toString);
        assertEquals("Titan GraphQL proof", variableAlias.at("/data/chosen/title").asText(),
                variableAlias::toString);

        JsonNode connectionArgument = execute(connection, """
                { articles(authorId: 10, first: 1) {
                    edges { node { id title } }
                    totalCount
                  } }
                """, "", "{}");
        assertEquals(1, connectionArgument.at("/data/articles/edges/0/node/id").asInt(),
                connectionArgument::toString);
        assertEquals(1, connectionArgument.at("/data/articles/totalCount").asInt(),
                connectionArgument::toString);

        JsonNode generatedFilterPlan = execute(connection, """
                { articles(
                    filter: { or: [
                      { titleLength: { gt: 20 } }
                      { authorName: { eq: "Ada Lovelace" } }
                    ] }
                    orderBy: [{ authorName: DESC }]
                    first: 2
                  ) {
                    edges { cursor node { id titleLength author { name } } }
                    totalCount
                  } }
                """, "", "{}");
        assertEquals(2, generatedFilterPlan.at("/data/articles/totalCount").asInt(),
                generatedFilterPlan::toString);
        assertEquals(2, generatedFilterPlan.at("/data/articles/edges/0/node/id").asInt(),
                generatedFilterPlan::toString);
        assertEquals("Grace Hopper", generatedFilterPlan.at("/data/articles/edges/0/node/author/name").asText(),
                generatedFilterPlan::toString);

        String relationCursor = generatedFilterPlan.at("/data/articles/edges/0/cursor").asText();
        assertFalse(relationCursor.isEmpty(), generatedFilterPlan::toString);
        JsonNode relationBoundary = execute(connection, """
                query Continue($after: String!) {
                  articles(
                    filter: { or: [
                      { titleLength: { gt: 20 } }
                      { authorName: { eq: "Ada Lovelace" } }
                    ] }
                    orderBy: [{ authorName: DESC }]
                    after: $after
                    first: 1
                  ) {
                    edges { node { id author { name } } }
                    totalCount
                    pageInfo { hasPreviousPage }
                  }
                }
                """, "Continue", JSON.createObjectNode().put("after", relationCursor).toString());
        assertEquals(1, relationBoundary.at("/data/articles/edges/0/node/id").asInt(),
                relationBoundary::toString);
        assertEquals(2, relationBoundary.at("/data/articles/totalCount").asInt(),
                relationBoundary::toString);
        assertEquals(true, relationBoundary.at("/data/articles/pageInfo/hasPreviousPage").asBoolean(),
                relationBoundary::toString);

        JsonNode computedOrderAndNegation = execute(connection, """
                { articles(
                    filter: { not: { title: { contains: "Titan" } } }
                    orderBy: [{ titleLength: DESC }]
                    first: 2
                  ) {
                    edges { node { id titleLength } }
                    totalCount
                  } }
                """, "", "{}");
        assertEquals(1, computedOrderAndNegation.at("/data/articles/totalCount").asInt(),
                computedOrderAndNegation::toString);
        assertEquals(2, computedOrderAndNegation.at("/data/articles/edges/0/node/id").asInt(),
                computedOrderAndNegation::toString);

        JsonNode computedFirst = execute(connection, """
                { articles(orderBy: [{ titleLength: DESC }], first: 1) {
                    edges { cursor node { id titleLength } }
                    totalCount
                  } }
                """, "", "{}");
        assertEquals(2, computedFirst.at("/data/articles/edges/0/node/id").asInt(), computedFirst::toString);
        String computedCursor = computedFirst.at("/data/articles/edges/0/cursor").asText();
        JsonNode computedBoundary = execute(connection, """
                query Continue($after: String!) {
                  articles(orderBy: [{ titleLength: DESC }], after: $after, first: 1) {
                    edges { node { id titleLength } }
                    totalCount
                    pageInfo { hasPreviousPage }
                  }
                }
                """, "Continue", JSON.createObjectNode().put("after", computedCursor).toString());
        assertEquals(1, computedBoundary.at("/data/articles/edges/0/node/id").asInt(),
                computedBoundary::toString);
        assertEquals(2, computedBoundary.at("/data/articles/totalCount").asInt(),
                computedBoundary::toString);
        assertEquals(true, computedBoundary.at("/data/articles/pageInfo/hasPreviousPage").asBoolean(),
                computedBoundary::toString);

        String unpublishedOnly = """
                {"contextVersion":"titan.graphql.request-context/v1","actorRole":"reader",
                 "enabledContextFilters":["publishedVisibility"],
                 "contextValues":{"articleVisibility":false}}
                """;
        JsonNode contextFiltered = execute(connection,
                "{ articles(first: 5) { edges { node { id } } totalCount } }", "", "{}", unpublishedOnly);
        assertEquals(2, contextFiltered.at("/data/articles/edges/0/node/id").asInt(), contextFiltered::toString);
        assertEquals(1, contextFiltered.at("/data/articles/totalCount").asInt(), contextFiltered::toString);

        String missingVisibility = """
                {"contextVersion":"titan.graphql.request-context/v1","actorRole":"reader",
                 "enabledContextFilters":["publishedVisibility"],"contextValues":{}}
                """;
        JsonNode failClosedContext = execute(connection,
                "{ articles(first: 5) { edges { node { id } } totalCount } }", "", "{}", missingVisibility);
        assertEquals(0, failClosedContext.at("/data/articles/edges").size(), failClosedContext::toString);
        assertEquals(0, failClosedContext.at("/data/articles/totalCount").asInt(), failClosedContext::toString);

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE public.articles SET title = 'Changed inside MySQL' WHERE id = 1");
        }
        JsonNode changed = execute(connection, "{ article(id: 1) { title } }", "", "{}");
        assertEquals("Changed inside MySQL", changed.at("/data/article/title").asText());
    }

    @Test
    void jaxRsAdapterUsesTheManifestBoundDemoEngine(TitanTestContext context) throws Exception {
        Connection connection = context.connection(DatabaseTarget.MYSQL);
        DemoBlogSqlDeployment.deployPackagedKernelMySql(connection);
        GraphqlExecutionEngine engine = new GraphqlExecutionEngine(
                "database",
                () -> new SingleConnectionDataSource(connection),
                "Titan MySQL demo HTTP test connection",
                "src/test/resources/graphql/demo-blog.titan.graphql.yaml",
                GraphqlApplicationMutationProvider.none(),
                "mysql");

        Response response = new GraphqlHttpResource(engine, true).getResponse(
                "{ article(id: 1) { title } }", null, null, null, "application/graphql-response+json",
                "10", "reader", null, null, null, null, null, null, null, null);

        assertEquals(200, response.getStatus());
        assertEquals("database", response.getHeaderString("X-Titan-Execution-Mode"));
        assertEquals("Titan GraphQL proof", JSON.readTree(String.valueOf(response.getEntity()))
                .at("/data/article/title").asText());
    }

    private static JsonNode execute(Connection connection, String query, String operationName, String variablesJson)
            throws Exception {
        return execute(connection, query, operationName, variablesJson,
                DatabaseEngineTestRequestContract.trustedContext("reader"));
    }

    private static JsonNode execute(
            Connection connection,
            String query,
            String operationName,
            String variablesJson,
            String trustedContextJson
    ) throws Exception {
        boolean originalAutoCommit = connection.getAutoCommit();
        if (originalAutoCommit) connection.setAutoCommit(false);
        try {
            try (Statement transaction = connection.createStatement()) {
                transaction.execute("START TRANSACTION");
            }
            String responseJson;
            String transactionOutcome;
            try (CallableStatement statement = connection.prepareCall(
                    "CALL public.execute_graphql_request(?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, query);
                statement.setString(2, operationName);
                statement.setString(3, variablesJson);
                statement.setString(4, "{}");
                statement.setString(5, trustedContextJson);
                statement.setBoolean(6, false);
                statement.setString(7,
                        DatabaseEngineTestRequestContract.modelHash("/graphql/demo-blog.titan.graphql.yaml"));
                statement.setString(8, DatabaseEngineTestRequestContract.runtimeIdentity());
                statement.setString(9, DatabaseEngineTestRequestContract.packageIdentity());

                boolean hasResultSet = statement.execute();
                while (hasResultSet == false && statement.getUpdateCount() != -1) {
                    hasResultSet = statement.getMoreResults();
                }
                assertTrue(hasResultSet, "the procedure must expose its final response result set");
                try (ResultSet resultSet = statement.getResultSet()) {
                    assertTrue(resultSet.next(), "the procedure must return one response row");
                    responseJson = resultSet.getString("response_json");
                    transactionOutcome = resultSet.getString("transaction_outcome");
                }
                boolean moreResults = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
                while (moreResults || statement.getUpdateCount() != -1) {
                    moreResults = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
                }
            }
            if ("COMMIT".equals(transactionOutcome)) connection.commit();
            else connection.rollback();
            resetMySqlSession(connection);
            return JSON.readTree(responseJson);
        } catch (Exception | AssertionError failure) {
            try {
                connection.rollback();
            } catch (Exception rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            if (originalAutoCommit) connection.setAutoCommit(true);
        }
    }

    /** Mirrors the Connector/J pool-boundary reset used by the production whole-request client. */
    private static void resetMySqlSession(Connection connection) throws Exception {
        Class<?> mysqlConnection = Class.forName("com.mysql.cj.jdbc.JdbcConnection");
        Object unwrapped = connection.unwrap(mysqlConnection);
        mysqlConnection.getMethod("resetServerState").invoke(unwrapped);
    }
}
