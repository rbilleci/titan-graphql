package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.titan.graphql.GraphqlEngine;
import io.titan.graphql.GraphqlExecution;
import io.titan.graphql.GraphqlExecutionEngine;
import io.titan.graphql.GraphqlJsonWriter;
import io.titan.graphql.GraphqlRequest;
import io.titan.graphql.GraphqlRequestContext;
import io.titan.graphql.GraphqlRuntimeRequest;
import io.titan.graphql.TitanCompiledGraphqlDataModel;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.graphql.conformance.DemoBlogSqlDeployment;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import io.titan.runtime.jdbc.SingleConnectionDataSource;
import io.titan.runtime.testing.DatabaseTarget;
import io.titan.runtime.testing.TitanTest;
import io.titan.runtime.testing.TitanTestContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Live proof for model-generated Titan carrier routines on both supported dialects. */
@Tag("docker")
@TitanTest(targets = {DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL})
class GeneratedTitanGraphqlReadsIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void generatedRoutinesAttestReadRelationsPageAndReflectMutations(TitanTestContext context) throws Exception {
        TitanGraphqlGap005ArtifactMetadata packageMetadata = TitanGraphqlGap005ArtifactMetadata.read(
                Path.of("build/generated/migrations/titan"));
        assertTrue(packageMetadata.entryPoints().stream().allMatch(entry ->
                        entry.className().equals("io.titan.graphql.generated.GeneratedTitanGraphqlReads")),
                "production proof package must contain generated carriers only");
        assertFalse(packageMetadata.entryPoints().stream().anyMatch(entry ->
                entry.className().contains("DemoBlog") || entry.methodName().equals("executeGraphql")));
        for (DatabaseTarget target : List.of(DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL)) {
            Connection connection = context.connection(target);
            if (target == DatabaseTarget.POSTGRESQL) {
                DemoBlogSqlDeployment.deployPackagedKernel(connection);
            } else {
                DemoBlogSqlDeployment.deployPackagedKernelMySql(connection);
            }

            String expectedHash = TitanGraphqlModelDocumentJson.semanticHash(TitanGraphqlModelDocumentYaml.parse(
                    Files.readString(Path.of("src/test/resources/graphql/demo-blog.titan.graphql.yaml"))));
            assertEquals(expectedHash, scalar(connection, "SELECT public.model_semantic_hash()"), target.name());

            JsonNode point = rows(connection, target, "read_root_article", 1);
            assertEquals(1, point.size(), target.name());
            assertEquals("Titan GraphQL proof", point.get(0).get("title").asText(), target.name());
            assertEquals(19, point.get(0).get("title_length").asInt(), target.name());

            JsonNode author = rows(connection, target, "read_relation_article_author", false, 10);
            assertEquals("Ada Lovelace", author.get(0).get("name").asText(), target.name());
            assertTrue(author.get(0).get("email").isNull(),
                    "protected email leaked from carrier on " + target);
            JsonNode authorizedAuthor = rows(connection, target, "read_relation_article_author", true, 10);
            assertEquals("ada@example.test", authorizedAuthor.get(0).get("email").asText(), target.name());

            JsonNode publishedPage = rows(connection, target, "read_root_articles_forward",
                    false, 0, false, 0, false, 0, true, true, true, 10);
            assertEquals(1, publishedPage.size(), target.name());
            assertEquals(1, publishedPage.get(0).get("id").asInt(), target.name());
            assertEquals(2L, count(connection, target, false, 0, false, false, false));
            assertEquals(1L, count(connection, target, false, 0, true, true, true));

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE public.articles SET title = 'Changed in database' WHERE id = 1");
            }
            JsonNode changed = rows(connection, target, "read_root_article", 1);
            assertEquals("Changed in database", changed.get(0).get("title").asText(), target.name());
            assertTrue(changed.get(0).get("title_length").asInt() > 0, target.name());
        }
    }

    @Test
    void genericGraphqlPlannerExecutesGeneratedPackageWithoutDemoDispatch(TitanTestContext context) throws Exception {
        TitanGraphqlModelDocument model = TitanGraphqlModelDocumentYaml.parse(
                Files.readString(Path.of("src/test/resources/graphql/demo-blog.titan.graphql.yaml")));
        TitanGraphqlGap005ArtifactMetadata metadata = TitanGraphqlGap005ArtifactMetadata.read(
                Path.of("build/generated/migrations/titan"));

        for (DatabaseTarget target : List.of(DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL)) {
            Connection connection = context.connection(target);
            if (target == DatabaseTarget.POSTGRESQL) {
                DemoBlogSqlDeployment.deployPackagedKernel(connection);
            } else {
                DemoBlogSqlDeployment.deployPackagedKernelMySql(connection);
            }
            TitanCompiledGraphqlDataModel dataModel = new TitanCompiledGraphqlDataModel(
                    model, new SingleConnectionDataSource(connection), metadata);

            GraphqlExecutionEngine engine = new GraphqlExecutionEngine(
                    "compiled", () -> new SingleConnectionDataSource(connection), target.name(),
                    "src/test/resources/graphql/demo-blog.titan.graphql.yaml");
            assertEquals("titan-compiled", engine.runtime().name(), target.name());
            JsonNode enginePoint = JSON.readTree(engine.runtime().execute(
                    new GraphqlRuntimeRequest("{ article(id: 1) { id titleLength } }", "", "", ""),
                    GraphqlRequestContext.legacy(10L, "reader")));
            assertEquals(19, enginePoint.at("/data/article/titleLength").asInt(), target.name());
            assertTrue(engine.fingerprint().matches("[0-9a-f]{64}"), target.name());

            JsonNode point = graphql(dataModel, """
                    { aliased: article(id: 1) {
                        id title titleLength author { id name }
                    } }
                    """, GraphqlRequestContext.legacy(10L, "reader"));
            assertEquals("Titan GraphQL proof", point.at("/data/aliased/title").asText(), target.name());
            assertEquals(19, point.at("/data/aliased/titleLength").asInt(), target.name());
            assertEquals("Ada Lovelace", point.at("/data/aliased/author/name").asText(), target.name());

            JsonNode firstPage = graphql(dataModel, """
                    { articles(first: 1) {
                        edges { cursor node { id titleLength } }
                        totalCount
                        pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                    } }
                    """, GraphqlRequestContext.legacy(10L, "reader"));
            assertEquals(2, firstPage.at("/data/articles/totalCount").asInt(), target.name());
            assertTrue(firstPage.at("/data/articles/pageInfo/hasNextPage").asBoolean(), target.name());
            String cursor = firstPage.at("/data/articles/pageInfo/endCursor").asText();

            JsonNode filtered = graphql(dataModel, """
                    { articles(first: 10, filter: { title: { contains: "GraphQL" } }) {
                        edges { node { id title } }
                        totalCount
                    } }
                    """, GraphqlRequestContext.legacy(10L, "reader"));
            assertEquals(1, filtered.at("/data/articles/edges").size(), target.name());
            assertEquals(1, filtered.at("/data/articles/edges/0/node/id").asInt(), target.name());
            assertEquals(1, filtered.at("/data/articles/totalCount").asInt(), target.name());

            JsonNode computedFilter = graphql(dataModel,
                    "{ articles(first: 10, filter: { titleLength: { gt: 20 } }) { "
                            + "edges { node { id titleLength } } totalCount } }",
                    GraphqlRequestContext.legacy(10L, "reader"));
            assertEquals(1, computedFilter.at("/data/articles/edges").size(), target.name());
            assertEquals(2, computedFilter.at("/data/articles/edges/0/node/id").asInt(), target.name());
            assertEquals(1, computedFilter.at("/data/articles/totalCount").asInt(), target.name());

            JsonNode nullFilter = graphql(dataModel,
                    "{ articles(first: 10, filter: { title: { eq: null } }) { totalCount } }",
                    GraphqlRequestContext.legacy(10L, "reader"));
            assertEquals(0, nullFilter.at("/data/articles/totalCount").asInt(), target.name());

            JsonNode emptyIn = graphql(dataModel,
                    "{ articles(first: 10, filter: { id: { in: [] } }) { totalCount } }",
                    GraphqlRequestContext.legacy(10L, "reader"));
            assertEquals(0, emptyIn.at("/data/articles/totalCount").asInt(), target.name());

            JsonNode filteredFirst = graphql(dataModel,
                    "{ articles(first: 1, filter: { id: { in: [1, 2] } }) { "
                            + "edges { cursor node { id } } pageInfo { hasNextPage } totalCount } }",
                    GraphqlRequestContext.legacy(10L, "reader"));
            assertEquals(2, filteredFirst.at("/data/articles/totalCount").asInt(), target.name());
            assertTrue(filteredFirst.at("/data/articles/pageInfo/hasNextPage").asBoolean(), target.name());
            String filteredCursor = filteredFirst.at("/data/articles/edges/0/cursor").asText();
            JsonNode filteredContinuation = graphql(dataModel,
                    "{ articles(first: 1, after: \"" + filteredCursor
                            + "\", filter: { id: { in: [1, 2] } }) { edges { node { id } } } }",
                    GraphqlRequestContext.legacy(10L, "reader"));
            assertEquals(2, filteredContinuation.at(
                    "/data/articles/edges/0/node/id").asInt(), target.name());

            JsonNode oversizedIn = graphql(dataModel,
                    "{ articles(first: 10, filter: { id: { in: [1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17] } }) "
                            + "{ totalCount } }",
                    GraphqlRequestContext.legacy(10L, "reader"));
            assertTrue(oversizedIn.at("/errors/0/message").asText()
                    .contains("more than 16 values"), target.name());

            JsonNode escapedWildcard = graphql(dataModel,
                    "{ articles(first: 10, filter: { title: { contains: \"%\" } }) { totalCount } }",
                    GraphqlRequestContext.legacy(10L, "reader"));
            assertEquals(0, escapedWildcard.at("/data/articles/totalCount").asInt(), target.name());

            JsonNode composed = graphql(dataModel,
                    "{ articles(first: 10, filter: { id: { gt: 0, lt: 3 } }) { totalCount } }",
                    GraphqlRequestContext.legacy(10L, "reader"));
            assertEquals(2, composed.at("/data/articles/totalCount").asInt(), target.name());

            JsonNode relationFiltered = graphql(dataModel,
                    "{ articles(first: 10, filter: { authorName: { startsWith: \"Ada\" } }) { "
                            + "edges { node { id } } totalCount } }",
                    GraphqlRequestContext.legacy(10L, "reader"));
            assertEquals(1, relationFiltered.at("/data/articles/totalCount").asInt(), target.name());
            assertEquals(1, relationFiltered.at("/data/articles/edges/0/node/id").asInt(), target.name());

            JsonNode filteredOrder = graphql(dataModel,
                    "{ articles(first: 1, filter: { id: { gt: 0 } }, "
                            + "orderBy: [{ title: DESC }]) { edges { node { id } } totalCount } }",
                    GraphqlRequestContext.legacy(10L, "reader"));
            assertEquals(2, filteredOrder.at("/data/articles/totalCount").asInt(), target.name());
            assertEquals(1, filteredOrder.at("/data/articles/edges").size(), target.name());

            JsonNode continuation = graphql(dataModel,
                    "{ articles(first: 1, after: \"" + cursor
                            + "\") { edges { node { id } } pageInfo { hasPreviousPage } } }",
                    GraphqlRequestContext.legacy(10L, "reader"));
            assertEquals(2, continuation.at("/data/articles/edges/0/node/id").asInt(), target.name());
            assertTrue(continuation.at("/data/articles/pageInfo/hasPreviousPage").asBoolean(), target.name());

            JsonNode twoRows = graphql(dataModel,
                    "{ articles(first: 2) { edges { cursor node { id } } } }",
                    GraphqlRequestContext.legacy(10L, "reader"));
            String secondCursor = twoRows.at("/data/articles/edges/1/cursor").asText();
            JsonNode backward = graphql(dataModel,
                    "{ articles(last: 1, before: \"" + secondCursor
                            + "\") { edges { node { id } } pageInfo { hasNextPage hasPreviousPage } } }",
                    GraphqlRequestContext.legacy(10L, "reader"));
            assertEquals(1, backward.at("/data/articles/edges/0/node/id").asInt(), target.name());
            assertTrue(backward.at("/data/articles/pageInfo/hasNextPage").asBoolean(), target.name());

            JsonNode relatedOrder = graphql(dataModel,
                    "{ articles(first: 1, orderBy: [{ authorName: ASC }]) { "
                            + "edges { cursor node { id } } pageInfo { hasNextPage } } }",
                    GraphqlRequestContext.legacy(10L, "reader"));
            assertEquals(1, relatedOrder.at("/data/articles/edges/0/node/id").asInt(), target.name());
            assertTrue(relatedOrder.at("/data/articles/pageInfo/hasNextPage").asBoolean(), target.name());
            String relatedOrderCursor = relatedOrder.at("/data/articles/edges/0/cursor").asText();
            JsonNode relatedOrderContinuation = graphql(dataModel,
                    "{ articles(first: 1, after: \"" + relatedOrderCursor
                            + "\", orderBy: [{ authorName: ASC }]) { edges { node { id } } } }",
                    GraphqlRequestContext.legacy(10L, "reader"));
            assertEquals(2, relatedOrderContinuation.at(
                    "/data/articles/edges/0/node/id").asInt(), target.name());

            JsonNode relatedOrderDescending = graphql(dataModel,
                    "{ articles(first: 1, orderBy: [{ authorName: DESC }]) { edges { node { id } } } }",
                    GraphqlRequestContext.legacy(10L, "reader"));
            assertEquals(2, relatedOrderDescending.at(
                    "/data/articles/edges/0/node/id").asInt(), target.name());

            JsonNode visible = graphql(dataModel,
                    "{ articles(first: 10) { edges { node { id } } totalCount } }",
                    GraphqlRequestContext.articleVisibility(10L, "reader", true));
            assertEquals(1, visible.at("/data/articles/edges").size(), target.name());
            assertEquals(1, visible.at("/data/articles/totalCount").asInt(), target.name());

            GraphqlExecution batchedExecution = GraphqlEngine.execute(
                    dataModel, new GraphqlJsonWriter(), GraphqlRequest.query("""
                            { articles(first: 2) {
                                edges { node { id author { id name } } }
                            } }
                            """), GraphqlRequestContext.legacy(10L, "reader"));
            JsonNode batched = JSON.readTree(batchedExecution.json());
            assertEquals("Ada Lovelace", batched.at("/data/articles/edges/0/node/author/name").asText(),
                    target.name());
            assertEquals("Grace Hopper", batched.at("/data/articles/edges/1/node/author/name").asText(),
                    target.name());
            assertEquals(2, batchedExecution.plan().readStepCount(),
                    "two parents must use one root read plus one relation batch on " + target);

            JsonNode protectedField = graphql(dataModel,
                    "{ article(id: 1) { author { email } } }",
                    GraphqlRequestContext.legacy(10L, "admin"));
            assertEquals("ada@example.test", protectedField.at("/data/article/author/email").asText(),
                    protectedField.toString());
            JsonNode rejectedProtectedField = graphql(dataModel,
                    "{ article(id: 1) { author { protectedAlias: email } } }",
                    GraphqlRequestContext.legacy(10L, "reader"));
            assertTrue(rejectedProtectedField.at("/errors/0/message").asText().contains("not authorized"),
                    rejectedProtectedField.toString());

            JsonNode missingContext = graphql(dataModel,
                    "{ articles(first: 1) { edges { node { id } } } }",
                    GraphqlRequestContext.missingArticleVisibility(10L, "reader"));
            assertTrue(missingContext.at("/errors/0/message").asText().contains("required request context"),
                    missingContext.toString());

            GraphqlExecution relationPageExecution = GraphqlEngine.execute(
                    dataModel, new GraphqlJsonWriter(), GraphqlRequest.query("""
                            { articles(first: 2) {
                                edges { node { id comments(first: 1) {
                                    edges { cursor node { id body } }
                                    totalCount
                                    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                                } } }
                            } }
                            """), GraphqlRequestContext.legacy(10L, "reader"));
            JsonNode relationPage = JSON.readTree(relationPageExecution.json());
            assertEquals(100, relationPage.at("/data/articles/edges/0/node/comments/edges/0/node/id").asInt(),
                    target.name());
            assertEquals(2, relationPage.at("/data/articles/edges/0/node/comments/totalCount").asInt(),
                    target.name());
            assertTrue(relationPage.at(
                    "/data/articles/edges/0/node/comments/pageInfo/hasNextPage").asBoolean(), target.name());
            assertEquals(1, relationPage.at("/data/articles/edges/1/node/comments/totalCount").asInt(),
                    target.name());
            assertEquals(2, relationPageExecution.plan().readStepCount(),
                    "relation connections must retain one root plus one batched child read on " + target);

            GraphqlExecution nestedBatchExecution = GraphqlEngine.execute(
                    dataModel, new GraphqlJsonWriter(), GraphqlRequest.query("""
                            { articles(first: 2) {
                                edges { node { comments(first: 2) {
                                    edges { node { id author { id name } } }
                                } } }
                            } }
                            """), GraphqlRequestContext.legacy(10L, "reader"));
            JsonNode nestedBatch = JSON.readTree(nestedBatchExecution.json());
            assertEquals("Grace Hopper", nestedBatch.at(
                    "/data/articles/edges/0/node/comments/edges/0/node/author/name").asText(), target.name());
            assertEquals(3, nestedBatchExecution.plan().readStepCount(),
                    "two relation levels must use one root plus one batch per level on " + target);

            String firstCommentCursor = relationPage.at(
                    "/data/articles/edges/0/node/comments/pageInfo/endCursor").asText();
            JsonNode relationContinuation = graphql(dataModel,
                    "{ article(id: 1) { comments(first: 1, after: \"" + firstCommentCursor
                            + "\") { edges { cursor node { id } } pageInfo { hasPreviousPage } } } }",
                    GraphqlRequestContext.legacy(10L, "reader"));
            assertEquals(101, relationContinuation.at(
                    "/data/article/comments/edges/0/node/id").asInt(), target.name());
            assertTrue(relationContinuation.at(
                    "/data/article/comments/pageInfo/hasPreviousPage").asBoolean(), target.name());

            String secondCommentCursor = relationContinuation.at(
                    "/data/article/comments/edges/0/cursor").asText();
            JsonNode relationBackward = graphql(dataModel,
                    "{ article(id: 1) { comments(last: 1, before: \"" + secondCommentCursor
                            + "\") { edges { node { id } } pageInfo { hasNextPage } } } }",
                    GraphqlRequestContext.legacy(10L, "reader"));
            assertEquals(100, relationBackward.at(
                    "/data/article/comments/edges/0/node/id").asInt(), target.name());
            assertTrue(relationBackward.at(
                    "/data/article/comments/pageInfo/hasNextPage").asBoolean(), target.name());

            JsonNode aliasedRelationWindows = graphql(dataModel,
                    "{ article(id: 1) { first: comments(first: 1) { edges { node { id } } } "
                            + "last: comments(last: 1) { edges { node { id } } } } }",
                    GraphqlRequestContext.legacy(10L, "reader"));
            assertEquals(100, aliasedRelationWindows.at(
                    "/data/article/first/edges/0/node/id").asInt(), target.name());
            assertEquals(101, aliasedRelationWindows.at(
                    "/data/article/last/edges/0/node/id").asInt(), target.name());
        }
    }

    private static JsonNode graphql(
            TitanCompiledGraphqlDataModel dataModel,
            String query,
            GraphqlRequestContext context
    ) throws Exception {
        return JSON.readTree(GraphqlEngine.execute(
                dataModel, new GraphqlJsonWriter(), GraphqlRequest.query(query), context).json());
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next(), "scalar routine returned no row");
            return resultSet.getString(1);
        }
    }

    private static long count(Connection connection, DatabaseTarget target, Object... parameters) throws Exception {
        JsonNode rows = rows(connection, target, "count_root_articles", parameters);
        assertEquals(1, rows.size(), "count carrier row count on " + target);
        return rows.get(0).get("total_count").asLong();
    }

    private static JsonNode rows(
            Connection connection,
            DatabaseTarget target,
            String routine,
            Object... parameters
    ) throws Exception {
        if (target == DatabaseTarget.POSTGRESQL) {
            String sql = "SELECT public." + routine + "(" + placeholders(parameters.length) + ")";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, parameters);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next(), "PostgreSQL carrier returned no row");
                    return JSON.readTree(resultSet.getString(1));
                }
            }
        }

        String sql = "CALL public." + routine + "(" + placeholders(parameters.length) + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            boolean hasResult = statement.execute();
            while (true) {
                if (hasResult) {
                    try (ResultSet resultSet = statement.getResultSet()) {
                        List<Map<String, Object>> values = new ArrayList<>();
                        ResultSetMetaData metadata = resultSet.getMetaData();
                        while (resultSet.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                                row.put(metadata.getColumnLabel(column), resultSet.getObject(column));
                            }
                            values.add(row);
                        }
                        return JSON.valueToTree(values);
                    }
                }
                if (statement.getUpdateCount() == -1) {
                    throw new IllegalStateException("MySQL carrier returned no result set: " + routine);
                }
                hasResult = statement.getMoreResults();
            }
        }
    }

    private static void bind(PreparedStatement statement, Object[] parameters) throws Exception {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }
}
