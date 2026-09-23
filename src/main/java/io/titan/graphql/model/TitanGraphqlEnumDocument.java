package io.titan.graphql.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** A schema enum whose values and optional introspection metadata are build-time model data. */
public record TitanGraphqlEnumDocument(
        String name,
        List<String> values,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<EnumValueMetadata> valueMetadata
) {
    public TitanGraphqlEnumDocument(String name, List<String> values) {
        this(name, values, List.of());
    }

    public TitanGraphqlEnumDocument {
        name = ModelDocumentSupport.requireText(name, "enum.name");
        values = ModelDocumentSupport.listOrEmpty(values);
        valueMetadata = ModelDocumentSupport.listOrEmpty(valueMetadata);
    }

    /** Optional description and deprecation metadata for one declared enum value. */
    public record EnumValueMetadata(
            String name,
            String description,
            boolean deprecated,
            String deprecationReason
    ) {
        public EnumValueMetadata {
            name = ModelDocumentSupport.requireText(name, "enum.valueMetadata.name");
            description = ModelDocumentSupport.textOrEmpty(description);
            deprecationReason = ModelDocumentSupport.textOrEmpty(deprecationReason);
        }
    }
}
