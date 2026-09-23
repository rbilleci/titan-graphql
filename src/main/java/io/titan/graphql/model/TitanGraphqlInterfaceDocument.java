package io.titan.graphql.model;

import java.util.List;

/** A build-time GraphQL interface declaration consumed by the database-resident engine. */
public record TitanGraphqlInterfaceDocument(
        String name,
        String description,
        List<InterfaceField> fields
) {
    public TitanGraphqlInterfaceDocument {
        name = ModelDocumentSupport.requireText(name, "interface.name");
        description = ModelDocumentSupport.textOrEmpty(description);
        fields = ModelDocumentSupport.listOrEmpty(fields);
    }

    /** One public output field required from every implementing object. */
    public record InterfaceField(
            String name,
            String type,
            String description,
            boolean deprecated,
            String deprecationReason
    ) {
        public InterfaceField {
            name = ModelDocumentSupport.requireText(name, "interface.field.name");
            type = ModelDocumentSupport.requireText(type, "interface.field.type");
            description = ModelDocumentSupport.textOrEmpty(description);
            deprecationReason = ModelDocumentSupport.textOrEmpty(deprecationReason);
        }
    }
}
