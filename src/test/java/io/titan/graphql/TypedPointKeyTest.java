package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TypedPointKeyTest {

    private static final String CLIENT_ID = "11111111-2222-3333-4444-555555555555";

    @Test
    void reviewedPointKeysKeepScalarTypesAndAllCompositeComponents() throws IOException {
        GraphqlSchema schema = commerceSchema();

        assertEquals(List.of(
                        GraphqlFieldDescriptor.ScalarFilterOperator.EQ,
                        GraphqlFieldDescriptor.ScalarFilterOperator.IN),
                schema.type("Customer").field("nodeId").scalarFilterCapabilities().operators(),
                "the reviewed model's restricted ID filter surface must not widen in the JVM reference model");
        GraphqlException restrictedOperator = assertThrows(GraphqlException.class, () -> validate(schema,
                "{ customerFeed(first: 1, filter: { nodeId: { neq: 7001 } }) { totalCount } }"));
        assertEquals("unsupported filter operator 'neq' on field 'nodeId'", restrictedOperator.getMessage());

        GraphqlSelection stringKey = validate(schema, "{ country(code: \"NL\") { name } }");
        assertEquals(Map.of("code", "NL"), stringKey.rootKeyValues());

        GraphqlSelection uuidKey = validate(schema,
                "{ apiClient(id: \"" + CLIENT_ID + "\") { label } }");
        assertEquals(Map.of("id", java.util.UUID.fromString(CLIENT_ID)), uuidKey.rootKeyValues());
        GraphqlSelection uuidVariable = GraphqlValidator.validate(schema, GraphqlParser.parse(
                GraphqlRequest.of(
                        "query Client($id: UUID!) { apiClient(id: $id) { label } }",
                        "Client",
                        Map.of("id", CLIENT_ID)
                )), "reader");
        assertEquals(uuidKey.rootKeyValues(), uuidVariable.rootKeyValues());

        GraphqlSelection composite = validate(schema,
                "{ inventoryItem(warehouse: \"AMS\", sku: \"TG-42\") { quantity } }");
        assertEquals(Map.of("sku", "TG-42", "warehouse", "AMS"), composite.rootKeyValues());
        GraphqlReadPlan plan = GraphqlReadPlanner.plan(schema, composite);
        assertEquals(List.of("sku", "warehouse"), plan.rootRead().pointKeyArguments().stream()
                .map(GraphqlRootField.PointKeyArgument::name).toList());
        assertEquals("sku", plan.rootRead().pointKeyArguments().getFirst().columnName());
        assertEquals("warehouse_code", plan.rootRead().pointKeyArguments().get(1).columnName());

        String sdl = GraphqlSchemaPrinter.print(schema);
        assertTrue(sdl.contains("scalar UUID\n\n"), sdl);
        assertTrue(sdl.contains("apiClient(id: UUID!): ApiClient"), sdl);
        assertTrue(sdl.contains(
                "inventoryItem(sku: String!, warehouse: String!): InventoryItem"), sdl);

        String introspection = GraphqlIntrospection.execute(schema, GraphqlParser.parse(
                "{ __type(name: \"Query\") { fields { name args { name type { name kind ofType { name kind } } } } } }"
        )).json();
        assertTrue(introspection.contains("\"name\":\"apiClient\""), introspection);
        assertTrue(introspection.contains("\"name\":\"UUID\""), introspection);
        assertTrue(introspection.contains("\"name\":\"warehouse\""), introspection);
        assertTrue(introspection.contains("\"name\":\"sku\""), introspection);
    }

    @Test
    void malformedOrIncompletePointKeysFailBeforeExecution() throws IOException {
        GraphqlSchema schema = commerceSchema();

        GraphqlException uuid = assertThrows(GraphqlException.class,
                () -> validate(schema, "{ apiClient(id: \"invalid\") { id } }"));
        assertEquals("argument 'id' must be a UUID", uuid.getMessage());

        GraphqlException missing = assertThrows(GraphqlException.class,
                () -> validate(schema, "{ inventoryItem(warehouse: \"AMS\") { quantity } }"));
        assertEquals("required argument 'sku' is missing", missing.getMessage());

        GraphqlException extra = assertThrows(GraphqlException.class,
                () -> validate(schema,
                        "{ inventoryItem(warehouse: \"AMS\", sku: \"TG-42\", extra: \"x\") { quantity } }"));
        assertTrue(extra.getMessage().contains("point key requires exactly"), extra.getMessage());
    }

    private static GraphqlSelection validate(GraphqlSchema schema, String query) {
        return GraphqlValidator.validate(schema, GraphqlParser.parse(query), "reader");
    }

    private static GraphqlSchema commerceSchema() throws IOException {
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(Files.readString(
                Path.of("src/test/resources/graphql/commerce.titan.graphql.yaml")));
        return ProjectionGraphqlAdapter.adapt(
                TitanGraphqlProjectionModelAdapter.adapt(document, new GraphqlPolicy()));
    }
}
