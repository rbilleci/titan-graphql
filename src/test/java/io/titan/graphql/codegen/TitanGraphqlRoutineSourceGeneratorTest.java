package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TitanGraphqlRoutineSourceGeneratorTest {

    @Test
    void generatesPointPagesRelationsComputedFieldsAndAttestationFromDemoModel() throws IOException {
        TitanGraphqlModelDocument document = model("demo-blog.titan.graphql.yaml");

        String source = TitanGraphqlRoutineSourceGenerator.generate(document);

        assertTrue(source.contains("public static String modelSemanticHash()"), source);
        assertTrue(source.contains("public static List<Map<String,Object>> readRootArticle("), source);
        assertTrue(source.contains("public static List<Map<String,Object>> readRootArticlesForward("), source);
        assertTrue(source.contains("public static List<Map<String,Object>> readRootArticlesBackward("), source);
        assertTrue(source.contains("readRelationArticleAuthor(Connection connection, int localKey)"), source);
        assertTrue(source.contains("readRelationArticleComments(Connection connection, int localKey)"), source);
        assertTrue(source.contains("length(title) AS title_length"), source);
        assertTrue(source.contains("WHERE id = ?"), source);
        assertTrue(source.contains("ORDER BY id ASC LIMIT ?"), source);
        assertTrue(source.contains("ORDER BY id DESC LIMIT ?"), source);
        assertTrue(source.contains("ResultSetMetaData metadata = resultSet.getMetaData()"), source);
        assertFalse(source.contains("email AS email"),
                "a protected scalar must not enter an unguarded generated carrier:\n" + source);
        assertFalse(source.contains("Titan GraphQL proof"),
                "generated routines must contain no fixture row data:\n" + source);
    }

    @Test
    void sameGeneratorProducesUnrelatedCommerceRoutinesWithoutDemoNames() throws IOException {
        String source = TitanGraphqlRoutineSourceGenerator.generate(model("commerce.titan.graphql.yaml"));

        assertTrue(source.contains("readRootCustomer(Connection connection, int id)"), source);
        assertTrue(source.contains("readRootCustomersForward("), source);
        assertTrue(source.contains("readRelationCustomerOrders(Connection connection, int localKey)"), source);
        assertTrue(source.contains("FROM commerce.customers"), source);
        assertTrue(source.contains("FROM commerce.orders WHERE customer_id = ?"), source);
        assertTrue(source.contains("(? = TRUE AND active = ?)"), source);
        assertFalse(source.contains("Article"), source);
        assertFalse(source.contains("articles"), source);
        assertFalse(source.contains("DemoBlog"), source);
    }

    @Test
    void generationIsByteStableForTheSameReviewedModel() throws IOException {
        TitanGraphqlModelDocument document = model("commerce.titan.graphql.yaml");

        assertEquals(TitanGraphqlRoutineSourceGenerator.generate(document),
                TitanGraphqlRoutineSourceGenerator.generate(document));
    }

    @Test
    void rejectsUnsafePhysicalIdentifiersBeforeEmittingSource() throws IOException {
        String source = Files.readString(Path.of("src/test/resources/graphql/commerce.titan.graphql.yaml"))
                .replace("physicalTable: customers", "physicalTable: customers;drop_table");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TitanGraphqlRoutineSourceGenerator.generate(TitanGraphqlModelDocumentYaml.parse(source)));

        assertTrue(failure.getMessage().contains("portable SQL identifier"), failure.getMessage());
    }

    private static TitanGraphqlModelDocument model(String fileName) throws IOException {
        return TitanGraphqlModelDocumentYaml.parse(
                Files.readString(Path.of("src/test/resources/graphql").resolve(fileName)));
    }
}
