package io.titan.graphql.validation;

public record TitanGraphqlSourceLocation(String source, int line, int column) {

    public TitanGraphqlSourceLocation {
        source = source == null ? "" : source;
        if (line < 0) {
            throw new IllegalArgumentException("source line must be zero or positive");
        }
        if (column < 0) {
            throw new IllegalArgumentException("source column must be zero or positive");
        }
        if ((line == 0) != (column == 0)) {
            throw new IllegalArgumentException("source line and column must both be present or both be absent");
        }
    }

    public static TitanGraphqlSourceLocation none() {
        return new TitanGraphqlSourceLocation("", 0, 0);
    }

    public static TitanGraphqlSourceLocation at(String source, int line, int column) {
        return new TitanGraphqlSourceLocation(source, line, column);
    }

    public boolean hasLineColumn() {
        return line > 0 && column > 0;
    }
}
