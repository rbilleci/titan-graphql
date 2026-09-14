package io.titan.graphql.model;

import java.util.List;

public record TitanGraphqlPolicyDocument(
        String name,
        String description,
        Effect effect,
        List<String> appliesTo,
        String expression
) {
    public TitanGraphqlPolicyDocument {
        name = ModelDocumentSupport.requireText(name, "policy.name");
        description = ModelDocumentSupport.textOrEmpty(description);
        effect = effect == null ? Effect.ALLOW : effect;
        appliesTo = ModelDocumentSupport.listOrEmpty(appliesTo);
        expression = ModelDocumentSupport.textOrEmpty(expression);
    }

    public enum Effect {
        ALLOW,
        DENY
    }
}
