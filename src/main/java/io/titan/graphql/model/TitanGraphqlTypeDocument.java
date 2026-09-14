package io.titan.graphql.model;

import java.util.List;

public record TitanGraphqlTypeDocument(
        String name,
        String table,
        String schema,
        String physicalTable,
        String primaryKey,
        List<TitanGraphqlFieldDocument> fields,
        List<TitanGraphqlRelationDocument> relations
) {
    public TitanGraphqlTypeDocument {
        name = ModelDocumentSupport.requireText(name, "type.name");
        table = ModelDocumentSupport.textOrEmpty(table);
        schema = ModelDocumentSupport.textOrEmpty(schema);
        physicalTable = ModelDocumentSupport.textOrEmpty(physicalTable);
        primaryKey = ModelDocumentSupport.textOrEmpty(primaryKey);
        fields = ModelDocumentSupport.listOrEmpty(fields);
        relations = ModelDocumentSupport.listOrEmpty(relations);
    }
}
