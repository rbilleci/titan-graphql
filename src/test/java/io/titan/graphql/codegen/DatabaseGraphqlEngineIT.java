package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A semantic proof for the separately packaged whole-request engine. It deliberately invokes the
 * public stored function directly: no GraphQL parser, planner, response writer, or resolver from
 * the JVM runtime participates in the assertion.
 */
@Tag("docker")
@Tag("database-engine")
@TitanTest(targets = {DatabaseTarget.POSTGRESQL})
class DatabaseGraphqlEngineIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void installedEntryPointExecutesPointReadVariablesAliasesAndLiveData(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        DemoBlogSqlDeployment.deployPackagedKernel(connection);

        JsonNode literal = execute(connection, "{ article(id: 1) { id title titleLength } }", "", "{}");
        assertEquals(1, literal.at("/data/article/id").asInt());
        assertEquals("Titan GraphQL proof", literal.at("/data/article/title").asText());
        assertEquals(19, literal.at("/data/article/titleLength").asInt());

        JsonNode variableAlias = execute(connection,
                "query FindArticle($articleId: Int!) { chosen: article(id: $articleId) { id title } }",
                "FindArticle", "{\"articleId\":1}");
        assertEquals(1, variableAlias.at("/data/chosen/id").asInt());
        assertEquals("Titan GraphQL proof", variableAlias.at("/data/chosen/title").asText());

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
            statement.executeUpdate("UPDATE public.articles SET title = 'Changed inside PostgreSQL' WHERE id = 1");
        }
        JsonNode changed = execute(connection, "{ article(id: 1) { title } }", "", "{}");
        assertEquals("Changed inside PostgreSQL", changed.at("/data/article/title").asText());
    }

    @Test
    void jaxRsAdapterUsesTheManifestBoundDemoEngine(TitanTestContext context) throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        DemoBlogSqlDeployment.deployPackagedKernel(connection);
        GraphqlExecutionEngine engine = new GraphqlExecutionEngine(
                "database",
                () -> new SingleConnectionDataSource(connection),
                "Titan PostgreSQL demo HTTP test connection",
                "src/test/resources/graphql/demo-blog.titan.graphql.yaml",
                GraphqlApplicationMutationProvider.none(),
                "postgresql");

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
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT public.execute_graphql_request(?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, query);
            statement.setString(2, operationName);
            statement.setString(3, variablesJson);
            statement.setString(4, "{}");
            statement.setString(5, trustedContextJson);
            statement.setBoolean(6, false);
            statement.setString(7, DatabaseEngineTestRequestContract.modelHash("/graphql/demo-blog.titan.graphql.yaml"));
            statement.setString(8, DatabaseEngineTestRequestContract.runtimeIdentity());
            statement.setString(9, DatabaseEngineTestRequestContract.packageIdentity());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return JSON.readTree(DatabaseEngineTestRequestContract.postgresqlResponseJson(resultSet.getString(1)));
            }
        }
    }
}
