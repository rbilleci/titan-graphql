package io.titan.graphql.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** A build-time GraphQL input-object declaration consumed by the database-resident engine. */
public record TitanGraphqlInputObjectDocument(
        String name,
        String description,
        List<InputField> fields
) {
    public TitanGraphqlInputObjectDocument(String name, List<InputField> fields) {
        this(name, "", fields);
    }

    public TitanGraphqlInputObjectDocument {
        name = ModelDocumentSupport.requireText(name, "inputObject.name");
        description = ModelDocumentSupport.textOrEmpty(description);
        fields = ModelDocumentSupport.listOrEmpty(fields);
    }

    /**
     * One input field. {@code defaultValue} is GraphQL constant-value source; an empty string
     * means that no schema default was declared.
     */
    public record InputField(
            String name,
            String type,
            String description,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) String defaultValue,
            boolean deprecated,
            String deprecationReason
    ) {
        public InputField(String name, String type) {
            this(name, type, "", "", false, "");
        }

        public InputField(String name, String type, String defaultValue) {
            this(name, type, "", defaultValue, false, "");
        }

        public InputField {
            name = ModelDocumentSupport.requireText(name, "inputObject.field.name");
            type = ModelDocumentSupport.requireText(type, "inputObject.field.type");
            description = ModelDocumentSupport.textOrEmpty(description);
            defaultValue = ModelDocumentSupport.textOrEmpty(defaultValue);
            deprecationReason = ModelDocumentSupport.textOrEmpty(deprecationReason);
        }
    }
}
