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
        List<String> policies
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
        this(name, table, schema, physicalTable, primaryKey, fields, relations, List.of());
    }
}
