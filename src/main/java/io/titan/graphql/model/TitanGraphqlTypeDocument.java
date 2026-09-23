package io.titan.graphql.model;

import java.util.List;

public record TitanGraphqlTypeDocument(
        String name,
        String table,
        String schema,
        String physicalTable,
        String primaryKey,
        List<TitanGraphqlFieldDocument> fields,
        List<TitanGraphqlRelationDocument> relations,
        List<String> policies,
        String description,
        List<String> interfaces
) {
    public TitanGraphqlTypeDocument {
        name = ModelDocumentSupport.requireText(name, "type.name");
        table = ModelDocumentSupport.textOrEmpty(table);
        schema = ModelDocumentSupport.textOrEmpty(schema);
        physicalTable = ModelDocumentSupport.textOrEmpty(physicalTable);
        primaryKey = ModelDocumentSupport.textOrEmpty(primaryKey);
        fields = ModelDocumentSupport.listOrEmpty(fields);
        relations = ModelDocumentSupport.listOrEmpty(relations);
        policies = ModelDocumentSupport.listOrEmpty(policies);
        description = ModelDocumentSupport.textOrEmpty(description);
        interfaces = ModelDocumentSupport.listOrEmpty(interfaces);
    }

    public TitanGraphqlTypeDocument(
            String name,
            String table,
            String schema,
            String physicalTable,
            String primaryKey,
            List<TitanGraphqlFieldDocument> fields,
            List<TitanGraphqlRelationDocument> relations,
            List<String> policies
    ) {
        this(name, table, schema, physicalTable, primaryKey, fields, relations, policies, "", List.of());
    }

    public TitanGraphqlTypeDocument(
            String name,
            String table,
            String schema,
            String physicalTable,
            String primaryKey,
            List<TitanGraphqlFieldDocument> fields,
            List<TitanGraphqlRelationDocument> relations
    ) {
        this(name, table, schema, physicalTable, primaryKey, fields, relations, List.of(), "", List.of());
    }
}
