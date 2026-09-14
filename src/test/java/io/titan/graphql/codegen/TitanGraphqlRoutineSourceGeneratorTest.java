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
        assertTrue(source.contains("public static List<Map<String,Object>> countRootArticles("), source);
        assertTrue(source.contains("readRelationArticleAuthor(Connection connection, "
                + "boolean allowFieldEmail, int localKey)"), source);
        assertTrue(source.contains("readRelationArticleComments(Connection connection, int localKey)"), source);
        assertTrue(source.contains("readRelationArticleAuthorBatch64(Connection connection"), source);
        assertTrue(source.contains("id AS __titan_parent_key FROM public.users WHERE id IN ("), source);
        assertTrue(source.contains("length(title) AS title_length"), source);
        assertTrue(source.contains("WHERE id = ?"), source);
        assertTrue(source.contains("ORDER BY id ASC LIMIT ?"), source);
        assertTrue(source.contains("ORDER BY id DESC LIMIT ?"), source);
        assertTrue(source.contains("readRootArticlesOrderAuthorNameAscForward"), source);
        assertTrue(source.contains("JOIN public.users tgql_sort ON "
                + "tgql_root.author_id = tgql_sort.id"), source);
        assertTrue(source.contains("tgql_sort.name AS author_name"), source);
        assertTrue(source.contains("ORDER BY tgql_sort.name ASC, tgql_root.id ASC LIMIT ?"), source);
        assertTrue(source.contains("readRootArticlesFilterTitleContainsForward"), source);
        assertTrue(source.contains("title LIKE ? ESCAPE '!'"), source);
        assertTrue(source.contains("readRootArticlesFilterIdIn16Forward"), source);
        assertTrue(source.contains("countRootArticlesFilterTitleEq"), source);
        assertTrue(source.contains("readRootArticlesFilterPlanForward"), source);
        assertTrue(source.contains("readRootArticlesOrderTitleDescFilterPlanForward"), source);
        assertTrue(source.contains("LEFT JOIN public.users tgql_filter_author")
                || source.contains("JOIN public.users tgql_filter_author"), source);
        assertTrue(source.contains("CASE tgql_f11.selector"), source);
        assertTrue(source.contains("CASE WHEN ? = TRUE THEN title IS NULL ELSE title = ? END"), source);
        assertFalse(source.contains("FilterEmail"), source);
        assertTrue(source.contains("author_id AS __titan_relation_author"), source);
        assertTrue(source.contains("id AS __titan_relation_comments"), source);
        assertTrue(source.contains("(? = FALSE OR (? = TRUE AND published = ?))"), source);
        assertTrue(source.contains("ResultSetMetaData metadata = resultSet.getMetaData()"), source);
        assertTrue(source.contains("CASE WHEN ? = TRUE THEN email ELSE NULL END AS email"),
                "a protected scalar must be guarded at the generated SQL boundary:\n" + source);
        assertFalse(source.contains("Titan GraphQL proof"),
                "generated routines must contain no fixture row data:\n" + source);
    }

    @Test
    void sameGeneratorProducesUnrelatedCommerceRoutinesWithoutDemoNames() throws IOException {
        String source = TitanGraphqlRoutineSourceGenerator.generate(model("commerce.titan.graphql.yaml"));

        assertTrue(source.contains("readRootCustomer(Connection connection, "
                + "boolean allowRelationOrders, int id)"), source);
        assertTrue(source.contains("readRootCountry(Connection connection, String code)"), source);
        assertTrue(source.contains("readRootApiClient(Connection connection, UUID id)"), source);
        assertTrue(source.contains(
                "readRootInventoryItem(Connection connection, String sku, String warehouse)"), source);
        assertTrue(source.contains("WHERE sku = ? AND warehouse_code = ?"), source);
        assertTrue(source.contains("readRootCustomersForward("), source);
        assertTrue(source.contains("readRelationCustomerOrders(Connection connection, "
                + "boolean allowRelation, int localKey)"), source);
        assertTrue(source.contains("readRelationCustomerOrdersBatch64(Connection connection"), source);
        assertTrue(source.contains("readRelationCustomerOrderConnectionBatch64(Connection connection"), source);
        assertTrue(source.contains("(? = FALSE OR id = ?) ORDER BY id ASC"), source);
        assertTrue(source.contains("customer_id AS __titan_parent_key FROM commerce.orders"), source);
        assertTrue(source.contains("WHERE ? = TRUE AND customer_id = ? ORDER BY id ASC"), source);
        assertTrue(source.contains("FROM commerce.customers"), source);
        assertTrue(source.contains("FROM commerce.orders WHERE ? = TRUE AND customer_id = ?"), source);
        assertTrue(source.contains("public static List<Map<String,Object>> countRootCustomers("), source);
        assertTrue(source.contains("readRootCustomersOrderNameDescForward("), source);
        assertTrue(source.contains("readRootCustomersOrderNameDescFilterPlanForward("), source);
        assertTrue(source.contains("name < ? OR (name = ? AND id < ?)"), source);
        assertTrue(source.contains("ORDER BY name DESC, id DESC LIMIT ?"), source);
        assertTrue(source.contains("readRootOrdersOrderCustomerNameAscForward"), source);
        assertTrue(source.contains("JOIN commerce.customers tgql_sort ON "
                + "tgql_root.customer_id = tgql_sort.id"), source);
        assertTrue(source.contains("ORDER BY tgql_sort.name ASC, tgql_root.id ASC LIMIT ?"), source);
        assertTrue(source.contains("(? = FALSE OR (? = TRUE AND active = ?))"), source);
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
    void generatesSqlGuardsForProtectedRelationsAndTheirBatches() throws IOException {
        String yaml = Files.readString(Path.of(
                "src/test/resources/graphql/demo-blog.titan.graphql.yaml"))
                .replace("      author:\n        target: User",
                        "      author:\n        policies: [canReadUserEmail]\n        target: User");

        String source = TitanGraphqlRoutineSourceGenerator.generate(
                TitanGraphqlModelDocumentYaml.parse(yaml));

        assertTrue(source.contains("CASE WHEN ? = TRUE THEN author_id ELSE NULL END "
                + "AS __titan_relation_author"), source);
        assertTrue(source.contains("readRelationArticleAuthor(Connection connection, "
                + "boolean allowFieldEmail, boolean allowRelation, int localKey)"), source);
        assertTrue(source.contains("FROM public.users WHERE ? = TRUE AND id = ?"), source);
        assertTrue(source.contains("readRelationArticleAuthorBatch64(Connection connection, "
                + "boolean allowFieldEmail, boolean allowRelation"), source);
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
