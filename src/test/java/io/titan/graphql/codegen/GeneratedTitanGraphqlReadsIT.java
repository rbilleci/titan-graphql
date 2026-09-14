package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.titan.graphql.conformance.DemoBlogSqlDeployment;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
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

            JsonNode author = rows(connection, target, "read_relation_article_author", 10);
            assertEquals("Ada Lovelace", author.get(0).get("name").asText(), target.name());
            assertFalse(author.get(0).has("email"), "protected email leaked from carrier on " + target);

            JsonNode publishedPage = rows(connection, target, "read_root_articles_forward",
                    false, 0, false, 0, false, 0, true, true, 10);
            assertEquals(1, publishedPage.size(), target.name());
            assertEquals(1, publishedPage.get(0).get("id").asInt(), target.name());

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE public.articles SET title = 'Changed in database' WHERE id = 1");
            }
            JsonNode changed = rows(connection, target, "read_root_article", 1);
            assertEquals("Changed in database", changed.get(0).get("title").asText(), target.name());
            assertTrue(changed.get(0).get("title_length").asInt() > 0, target.name());
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next(), "scalar routine returned no row");
            return resultSet.getString(1);
        }
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
