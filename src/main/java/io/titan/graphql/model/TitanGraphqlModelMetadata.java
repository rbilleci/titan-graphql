package io.titan.graphql.model;

import java.util.List;

public record TitanGraphqlModelMetadata(
        String name,
        String version,
        String owner,
        String description,
        List<String> tags
) {
    public TitanGraphqlModelMetadata {
        name = ModelDocumentSupport.requireText(name, "metadata.name");
        version = ModelDocumentSupport.textOrEmpty(version);
        owner = ModelDocumentSupport.textOrEmpty(owner);
        description = ModelDocumentSupport.textOrEmpty(description);
        tags = ModelDocumentSupport.listOrEmpty(tags);
    }

    public TitanGraphqlModelMetadata(String name) {
        this(name, "", "", "", List.of());
    }
}
