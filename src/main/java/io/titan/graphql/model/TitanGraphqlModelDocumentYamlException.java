package io.titan.graphql.model;

public final class TitanGraphqlModelDocumentYamlException extends IllegalArgumentException {

    private final String code;
    private final int line;
    private final int column;

    TitanGraphqlModelDocumentYamlException(String code, String message, int line, int column, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.line = line;
        this.column = column;
    }

    public String code() {
        return code;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    public boolean hasSourceLocation() {
        return line > 0 && column > 0;
    }
}
