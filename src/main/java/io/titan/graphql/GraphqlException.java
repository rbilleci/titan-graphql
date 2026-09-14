package io.titan.graphql;

import java.util.List;

public final class GraphqlException extends RuntimeException {

    static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    static final String PARSE_ERROR = "PARSE_ERROR";
    static final String AUTHORIZATION_ERROR = "AUTHORIZATION_ERROR";
    static final String UNSUPPORTED_OPERATION = "UNSUPPORTED_OPERATION";

    private final String code;
    private final List<String> path;
    private final int line;
    private final int column;

    public GraphqlException(String message) {
        this(message, VALIDATION_ERROR, List.of(), -1, -1);
    }

    private GraphqlException(String message, String code, List<String> path, int line, int column) {
        super(message);
        this.code = code;
        this.path = List.copyOf(path);
        this.line = line;
        this.column = column;
    }

    static GraphqlException parse(String message, String source, int offset) {
        int safeOffset = Math.max(0, offset);
        int line = 1;
        int column = 1;
        String input = source == null ? "" : source;
        for (int i = 0; i < safeOffset && i < input.length(); i++) {
            if (input.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new GraphqlException(message + " at offset " + offset, PARSE_ERROR, List.of(), line, column);
    }

    static GraphqlException authorization(String message) {
        return new GraphqlException(message, AUTHORIZATION_ERROR, List.of(), -1, -1);
    }

    static GraphqlException unsupportedOperation(String operationType) {
        return new GraphqlException(
                "operation type '" + operationType + "' is not supported",
                UNSUPPORTED_OPERATION,
                List.of(),
                -1,
                -1
        );
    }

    static GraphqlException mutationSchemaNotConfigured() {
        return new GraphqlException(
                "mutation operation selected but no mutation schema is configured",
                UNSUPPORTED_OPERATION,
                List.of(),
                -1,
                -1
        );
    }

    static GraphqlException mutationExecutionNotImplemented() {
        return new GraphqlException(
                "mutation operation selected but mutation execution is not implemented yet",
                UNSUPPORTED_OPERATION,
                List.of(),
                -1,
                -1
        );
    }

    String code() {
        return code;
    }

    List<String> path() {
        return path;
    }

    boolean hasLocation() {
        return line > 0 && column > 0;
    }

    int line() {
        return line;
    }

    int column() {
        return column;
    }
}
