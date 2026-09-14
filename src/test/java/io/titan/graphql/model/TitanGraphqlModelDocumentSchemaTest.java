package io.titan.graphql.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class TitanGraphqlModelDocumentSchemaTest {

    private static final Path SCHEMA_PATH = Path.of("docs/schema/titan.graphql.schema.json");

    @Test
    void schemaFileIsValidJson() throws IOException {
        JsonNode schema = readSchema();

        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.path("$schema").asText());
        assertEquals("Titan GraphQL Model Document", schema.path("title").asText());
        assertEquals("object", schema.path("type").asText());
        assertEquals(false, schema.path("additionalProperties").asBoolean());
    }

    @Test
    void schemaCoversRequiredEnvelopeAndCommonNestedStructures() throws IOException {
        JsonNode schema = readSchema();

        assertEquals(
                Set.of("apiVersion", "kind", "metadata", "roots", "types"),
                stringSet(schema.path("required"))
        );
        assertEquals("titan.graphql/v1alpha1", property(schema, "apiVersion").path("const").asText());
        assertEquals("ProjectionModel", property(schema, "kind").path("const").asText());

        JsonNode definitions = schema.path("$defs");
        assertTrue(definitions.has("root"));
        assertTrue(definitions.has("field"));
        assertTrue(definitions.has("relation"));
        assertTrue(definitions.has("policy"));
        assertTrue(definitions.has("contextFilter"));
        assertTrue(definitions.has("artifacts"));
        assertTrue(definitions.has("deployment"));
    }

    @Test
    void schemaDocumentsCurrentAuthoringEnums() throws IOException {
        JsonNode definitions = readSchema().path("$defs");

        assertEquals(Set.of("point", "connection"), enumSet(definitions.path("root").path("properties").path("operation")));
        assertEquals(Set.of("exact", "estimated", "none"), enumSet(definitions.path("rootPagination").path("properties").path("totalCount")));
        assertEquals(Set.of("equals", "relayFirst", "relayAfter", "relayLast", "relayBefore"),
                enumSet(definitions.path("rootArgument").path("properties").path("kind")));
        assertEquals(Set.of("one", "many"), enumSet(definitions.path("relation").path("properties").path("cardinality")));
        assertEquals(Set.of("sqlTemplate", "generatedSqlHelper", "materializedColumn", "javaOnlyExperimental"),
                enumSet(definitions.path("computed").path("properties").path("kind")));
        assertEquals(Set.of("constant", "rowLocal", "relationDependent"),
                enumSet(definitions.path("computed").path("properties").path("costClass")));
        assertEquals(Set.of("allow", "reject", "mask", "filter"),
                enumSet(definitions.path("policy").path("properties").path("mode")));
        assertEquals(Set.of("equals", "booleanEquals", "in"), enumSet(definitions.path("contextFilterType")));
        assertEquals(Set.of("asc", "desc"), enumSet(definitions.path("sortDirection")));
        assertEquals(Set.of("first", "last"), enumSet(definitions.path("nullOrdering")));
    }

    private static JsonNode readSchema() throws IOException {
        return JsonMapper.builder().build().readTree(Files.readString(SCHEMA_PATH));
    }

    private static JsonNode property(JsonNode schema, String property) {
        return schema.path("properties").path(property);
    }

    private static Set<String> enumSet(JsonNode node) {
        return stringSet(node.path("enum"));
    }

    private static Set<String> stringSet(JsonNode node) {
        assertTrue(node.isArray(), "expected array node but got " + node);
        return node.valueStream().map(JsonNode::asText).collect(Collectors.toSet());
    }
}
