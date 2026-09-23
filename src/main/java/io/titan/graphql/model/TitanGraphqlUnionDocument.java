package io.titan.graphql.model;

import java.util.List;

/** A build-time GraphQL union declaration whose members are reviewed object types. */
public record TitanGraphqlUnionDocument(
        String name,
        String description,
        List<String> members
) {
    public TitanGraphqlUnionDocument {
        name = ModelDocumentSupport.requireText(name, "union.name");
        description = ModelDocumentSupport.textOrEmpty(description);
        members = ModelDocumentSupport.listOrEmpty(members);
    }
}
