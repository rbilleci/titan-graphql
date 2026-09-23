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
        assertTrue(definitions.has("interfaces"));
        assertTrue(definitions.has("interface"));
        assertTrue(definitions.has("interfaceField"));
        assertTrue(definitions.has("unions"));
        assertTrue(definitions.has("union"));
        assertTrue(definitions.has("enums"));
        assertTrue(definitions.has("schemaEnum"));
        assertTrue(definitions.has("schemaEnumValueMetadata"));
        assertTrue(definitions.has("directives"));
        assertTrue(definitions.has("directive"));
        assertTrue(definitions.has("policy"));
        assertTrue(definitions.has("contextFilter"));
        assertTrue(definitions.has("artifacts"));
        assertTrue(definitions.has("deployment"));
        assertEquals("#/$defs/interfaces", property(schema, "interfaces").path("$ref").asText());
        assertEquals("#/$defs/unions", property(schema, "unions").path("$ref").asText());
        assertEquals("#/$defs/directives", property(schema, "directives").path("$ref").asText());
        assertEquals("string", definitions.path("root").path("properties")
                .path("outputType").path("type").asText());
        assertEquals(false, definitions.path("root").path("additionalProperties").asBoolean());
        assertEquals(false, definitions.path("root").path("properties").has("projections"),
                "v1alpha1 roots bind exactly one physical projection; a heterogeneous source contract "
                        + "must be introduced as a versioned model feature");
        assertTrue(definitions.path("type").path("properties").has("interfaces"));
        assertEquals("string", definitions.path("field").path("properties")
                .path("deprecationReason").path("type").asText());
        assertEquals("string", definitions.path("rootArgument").path("properties")
                .path("defaultValue").path("type").asText());
        assertEquals("string", definitions.path("relationArgument").path("properties")
                .path("defaultValue").path("type").asText());
        assertEquals("string", definitions.path("mutationArgument").path("properties")
                .path("defaultValue").path("type").asText());
        assertEquals("string", definitions.path("mutationInput").path("properties")
                .path("defaultValue").path("type").asText());
        assertEquals(1, definitions.path("union").path("properties")
                .path("members").path("minItems").asInt());
        assertEquals(Set.of("includeIf", "skipIf"),
                enumSet(definitions.path("directive").path("properties").path("behavior")));
        assertEquals("boolean", definitions.path("relationCapabilities").path("properties")
                .path("selectable").path("type").asText());
        assertEquals("boolean", definitions.path("relationCapabilities").path("properties")
                .path("batchable").path("type").asText());
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

        JsonNode enumValues = definitions.path("schemaEnum").path("properties").path("values");
        assertEquals(1, enumValues.path("minItems").asInt());
        assertTrue(enumValues.path("uniqueItems").asBoolean());
        assertEquals("^[_A-Za-z][_0-9A-Za-z]*$", enumValues.path("items").path("pattern").asText());
        JsonNode enumMetadata = definitions.path("schemaEnum").path("properties").path("valueMetadata");
        assertEquals("#/$defs/schemaEnumValueMetadata",
                enumMetadata.path("patternProperties").path("^[_A-Za-z][_0-9A-Za-z]*$").path("$ref").asText());
        assertEquals("boolean", definitions.path("schemaEnumValueMetadata").path("properties")
                .path("deprecated").path("type").asText());
        assertEquals("#/$defs/enums", property(readSchema(), "enums").path("$ref").asText());
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
