package io.titan.graphql.model;

import java.util.List;

public record TitanGraphqlDatabaseDocument(
        String catalog,
        String defaultSchema,
        List<TableBinding> tables
) {
    public TitanGraphqlDatabaseDocument {
        catalog = ModelDocumentSupport.textOrEmpty(catalog);
        defaultSchema = ModelDocumentSupport.textOrEmpty(defaultSchema);
        tables = ModelDocumentSupport.listOrEmpty(tables);
    }

    public static TitanGraphqlDatabaseDocument empty() {
        return new TitanGraphqlDatabaseDocument("", "", List.of());
    }

    public record TableBinding(
            String name,
            String physicalName,
            String schema,
            String primaryKey
    ) {
        public TableBinding {
            name = ModelDocumentSupport.requireText(name, "database.tables.name");
            physicalName = ModelDocumentSupport.requireText(physicalName, "database.tables.physicalName");
            schema = ModelDocumentSupport.textOrEmpty(schema);
            primaryKey = ModelDocumentSupport.textOrEmpty(primaryKey);
        }
    }
}
