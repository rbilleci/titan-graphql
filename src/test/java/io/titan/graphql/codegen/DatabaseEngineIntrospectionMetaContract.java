package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Shared PostgreSQL/MySQL assertions for database-resident introspection self-description. */
final class DatabaseEngineIntrospectionMetaContract {

    static final String QUERY = """
            query IntrospectionMetaSchema {
              schema: __schema {
                types { name kind }
                directives { name args(includeDeprecated: true) { name } }
              }
              schemaType: __type(name: "__Schema") { name kind fields(includeDeprecated: true) { name } }
              typeType: __type(name: "__Type") {
                name kind
                fields(includeDeprecated: true) {
                  name
                  args(includeDeprecated: true) {
                    name defaultValue type { kind name ofType { kind name } }
                  }
                  type { kind name ofType { kind name ofType { kind name ofType { kind name } } } }
                }
              }
              fieldType: __type(name: "__Field") { name kind fields(includeDeprecated: true) { name } }
              inputValueType: __type(name: "__InputValue") { name kind fields(includeDeprecated: true) { name } }
              enumValueType: __type(name: "__EnumValue") { name kind fields(includeDeprecated: true) { name } }
              directiveType: __type(name: "__Directive") {
                name kind
                fields(includeDeprecated: true) {
                  name
                  type { kind name ofType { kind name ofType { kind name ofType { kind name } } } }
                }
              }
              typeKind: __type(name: "__TypeKind") { name kind enumValues(includeDeprecated: true) { name } }
              directiveLocation: __type(name: "__DirectiveLocation") {
                name kind enumValues(includeDeprecated: true) { name }
              }
            }
            """;

    private DatabaseEngineIntrospectionMetaContract() {
    }

    static void assertResponse(JsonNode response) {
        JsonNode schemaTypes = response.at("/data/schema/types");
        for (String typeName : List.of("__Schema", "__Type", "__Field", "__InputValue", "__EnumValue",
                "__Directive", "__TypeKind", "__DirectiveLocation")) {
            assertTrue(schemaTypes.findValuesAsText("name").contains(typeName), response::toString);
        }

        assertMetaObject(response, "schemaType", "__Schema", "types");
        assertMetaObject(response, "fieldType", "__Field", "args");
        assertMetaObject(response, "inputValueType", "__InputValue", "defaultValue");
        assertMetaObject(response, "enumValueType", "__EnumValue", "isDeprecated");
        assertMetaObject(response, "directiveType", "__Directive", "locations");

        JsonNode typeFields = response.at("/data/typeType/fields");
        assertEquals("__Type", response.at("/data/typeType/name").asText(), response::toString);
        assertEquals("OBJECT", response.at("/data/typeType/kind").asText(), response::toString);
        JsonNode kind = fieldNamed(typeFields, "kind");
        assertEquals("NON_NULL", kind.at("/type/kind").asText(), response::toString);
        assertEquals("ENUM", kind.at("/type/ofType/kind").asText(), response::toString);
        assertEquals("__TypeKind", kind.at("/type/ofType/name").asText(), response::toString);
        JsonNode fields = fieldNamed(typeFields, "fields");
        assertEquals("LIST", fields.at("/type/kind").asText(), response::toString);
        assertEquals("NON_NULL", fields.at("/type/ofType/kind").asText(), response::toString);
        assertEquals("__Field", fields.at("/type/ofType/ofType/name").asText(), response::toString);
        JsonNode includeDeprecated = fieldNamed(fields.at("/args"), "includeDeprecated");
        assertEquals("false", includeDeprecated.at("/defaultValue").asText(), response::toString);
        assertEquals("NON_NULL", includeDeprecated.at("/type/kind").asText(), response::toString);
        assertEquals("SCALAR", includeDeprecated.at("/type/ofType/kind").asText(), response::toString);
        assertEquals("Boolean", includeDeprecated.at("/type/ofType/name").asText(), response::toString);

        JsonNode includeDirective = fieldNamed(response.at("/data/schema/directives"), "include");
        assertEquals("if", fieldNamed(includeDirective.at("/args"), "if").at("/name").asText(),
                response::toString);

        JsonNode locations = fieldNamed(response.at("/data/directiveType/fields"), "locations");
        assertEquals("NON_NULL", locations.at("/type/kind").asText(), response::toString);
        assertEquals("LIST", locations.at("/type/ofType/kind").asText(), response::toString);
        assertEquals("NON_NULL", locations.at("/type/ofType/ofType/kind").asText(), response::toString);
        assertEquals("ENUM", locations.at("/type/ofType/ofType/ofType/kind").asText(), response::toString);
        assertEquals("__DirectiveLocation", locations.at("/type/ofType/ofType/ofType/name").asText(),
                response::toString);

        assertEquals("ENUM", response.at("/data/typeKind/kind").asText(), response::toString);
        assertTrue(response.at("/data/typeKind/enumValues").findValuesAsText("name").contains("NON_NULL"),
                response::toString);
        assertEquals("ENUM", response.at("/data/directiveLocation/kind").asText(), response::toString);
        assertTrue(response.at("/data/directiveLocation/enumValues").findValuesAsText("name")
                .contains("VARIABLE_DEFINITION"), response::toString);
        assertTrue(response.at("/data/directiveLocation/enumValues").findValuesAsText("name")
                .contains("INPUT_FIELD_DEFINITION"), response::toString);
    }

    private static void assertMetaObject(JsonNode response, String responseName, String typeName, String fieldName) {
        JsonNode type = response.at("/data/" + responseName);
        assertEquals(typeName, type.at("/name").asText(), response::toString);
        assertEquals("OBJECT", type.at("/kind").asText(), response::toString);
        assertNotNull(fieldNamed(type.at("/fields"), fieldName), response::toString);
    }

    private static JsonNode fieldNamed(JsonNode fields, String name) {
        for (JsonNode field : fields) {
            if (name.equals(field.path("name").asText())) {
                return field;
            }
        }
        throw new AssertionError("missing introspection field '" + name + "' in " + fields);
    }
}
