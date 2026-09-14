package io.titan.graphql.model;

public record TitanGraphqlContextFilterDocument(
        String name,
        String column,
        String contextKey,
        Operator operator,
        boolean applyBeforeClientFilters,
        boolean failClosed
) {
    public TitanGraphqlContextFilterDocument {
        name = ModelDocumentSupport.requireText(name, "contextFilter.name");
        column = ModelDocumentSupport.requireText(column, "contextFilter.column");
        contextKey = ModelDocumentSupport.requireText(contextKey, "contextFilter.contextKey");
        operator = operator == null ? Operator.EQUALS : operator;
    }

    public enum Operator {
        EQUALS,
        BOOLEAN_EQUALS,
        IN
    }
}
