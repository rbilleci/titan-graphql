package io.titan.graphql.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.model.TitanGraphqlModelDocument;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TitanGraphqlSchemaInferenceTest {

    @Test
    void consumesTheSchemaJsonProducedByThisBuildsTitanCodegenTask() {
        TitanGraphqlSchemaInference.InferenceResult result = TitanGraphqlSchemaInference.infer(
                Path.of("build/titan/schema.json"), "demo_blog");

        assertEquals(3, result.document().types().size());
        assertEquals(
                java.util.List.of("Article", "Comment", "User"),
                result.document().types().stream().map(type -> type.name()).toList());
        assertTrue(result.document().roots().isEmpty());
    }

    @Test
    void consumesTitanCodegenSchemaJsonWithoutAParallelFixtureModel() {
        TitanGraphqlSchemaInference.InferenceResult result = TitanGraphqlSchemaInference.infer("""
                {
                  "tables": [
                    {
                      "schema": "sales",
                      "name": "customers",
                      "columns": [
                        {"name":"id","sqlType":"bigint","nullable":false},
                        {"name":"name","sqlType":"varchar","nullable":false}
                      ],
                      "constraints": [
                        {"name":"customers_pkey","type":"PRIMARY_KEY","columns":["id"]}
                      ],
                      "foreignKeys": [],
                      "indexes": []
                    },
                    {
                      "schema": "sales",
                      "name": "orders",
                      "columns": [
                        {"name":"tenant_id","sqlType":"bigint","nullable":false},
                        {"name":"order_id","sqlType":"bigint","nullable":false},
                        {"name":"customer_id","sqlType":"bigint","nullable":false}
                      ],
                      "constraints": [
                        {"name":"orders_pkey","type":"PRIMARY_KEY","columns":["tenant_id","order_id"]}
                      ],
                      "foreignKeys": [
                        {"name":"orders_customer_fk","columns":["customer_id"],
                         "referencedSchema":"sales","referencedTable":"customers",
                         "referencedColumns":["id"]},
                        {"name":"orders_tenant_customer_fk","columns":["tenant_id","customer_id"],
                         "referencedSchema":"sales","referencedTable":"customers",
                         "referencedColumns":["tenant_id","id"]}
                      ],
                      "indexes": []
                    }
                  ],
                  "views": []
                }
                """, "commerce");

        TitanGraphqlModelDocument draft = result.document();
        assertEquals("commerce", draft.database().catalog());
        assertEquals("sales", draft.database().defaultSchema());
        assertEquals(2, draft.types().size());
        assertTrue(draft.roots().isEmpty(), "inference must not expose tables before review");
        assertEquals("customer", draft.types().get(1).relations().getFirst().name());
        assertEquals("", draft.types().get(1).primaryKey(),
                "a composite key must never be silently reduced to its first column");
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().equals("COMPOSITE_PRIMARY_KEY_REQUIRES_EXPLICIT_ROOT")));
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().equals("COMPOSITE_FOREIGN_KEY_REQUIRES_EXPLICIT_RELATION")));
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().equals("PUBLIC_ROOTS_DISABLED")));
    }
}
