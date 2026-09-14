package io.titan.graphql;

import java.util.ArrayList;
import java.util.List;

final class GraphqlLexer {

    private final String input;
    private final List<GraphqlToken> tokens = new ArrayList<>();
    private int position;

    GraphqlLexer(String input) {
        this.input = input == null ? "" : input;
    }

    static List<GraphqlToken> lex(String input) {
        return new GraphqlLexer(input).lexTokens();
    }

    List<GraphqlToken> lex() {
        return lexTokens();
    }

    private List<GraphqlToken> lexTokens() {
        while (!isAtEnd()) {
            char c = input.charAt(position);
            if (Character.isWhitespace(c)) {
                position++;
            } else if (c == '#') {
                skipComment();
            } else if (isNameStart(c)) {
                lexName();
            } else if (Character.isDigit(c)) {
                lexNumber();
            } else {
                lexSymbolOrString(c);
            }
        }
        tokens.add(new GraphqlToken(GraphqlTokenType.EOF, "", position));
        return List.copyOf(tokens);
    }

    private void lexName() {
        int start = position;
        position++;
        while (!isAtEnd() && isNamePart(input.charAt(position))) {
            position++;
        }
        tokens.add(new GraphqlToken(GraphqlTokenType.NAME, input.substring(start, position), start));
    }

    private void lexNumber() {
        int start = position;
        position++;
        while (!isAtEnd() && Character.isDigit(input.charAt(position))) {
            position++;
        }
        boolean isFloat = false;
        if (!isAtEnd() && input.charAt(position) == '.') {
            isFloat = true;
            position++;
            if (isAtEnd() || Character.isDigit(input.charAt(position)) == false) {
                throw error("expected digit after decimal point", start);
            }
            while (!isAtEnd() && Character.isDigit(input.charAt(position))) {
                position++;
            }
        }
        tokens.add(new GraphqlToken(
                isFloat ? GraphqlTokenType.FLOAT : GraphqlTokenType.INT,
                input.substring(start, position),
                start
        ));
    }

    private void lexSymbolOrString(char c) {
        int start = position;
        position++;
        switch (c) {
            case '!' -> tokens.add(new GraphqlToken(GraphqlTokenType.BANG, "!", start));
            case ':' -> tokens.add(new GraphqlToken(GraphqlTokenType.COLON, ":", start));
            case ',' -> tokens.add(new GraphqlToken(GraphqlTokenType.COMMA, ",", start));
            case '$' -> tokens.add(new GraphqlToken(GraphqlTokenType.DOLLAR, "$", start));
            case '=' -> tokens.add(new GraphqlToken(GraphqlTokenType.EQUALS, "=", start));
            case '{' -> tokens.add(new GraphqlToken(GraphqlTokenType.LEFT_BRACE, "{", start));
            case '}' -> tokens.add(new GraphqlToken(GraphqlTokenType.RIGHT_BRACE, "}", start));
            case '[' -> tokens.add(new GraphqlToken(GraphqlTokenType.LEFT_BRACKET, "[", start));
            case ']' -> tokens.add(new GraphqlToken(GraphqlTokenType.RIGHT_BRACKET, "]", start));
            case '(' -> tokens.add(new GraphqlToken(GraphqlTokenType.LEFT_PAREN, "(", start));
            case ')' -> tokens.add(new GraphqlToken(GraphqlTokenType.RIGHT_PAREN, ")", start));
            case '"' -> lexString(start);
            case '.' -> lexEllipsis(start);
            case '@' -> tokens.add(new GraphqlToken(GraphqlTokenType.AT, "@", start));
            default -> throw error("unexpected character '" + c + "'", start);
        }
    }

    private void lexEllipsis(int start) {
        if (position + 1 < input.length()
                && input.charAt(position) == '.'
                && input.charAt(position + 1) == '.') {
            position = position + 2;
            tokens.add(new GraphqlToken(GraphqlTokenType.ELLIPSIS, "...", start));
            return;
        }
        throw error("unsupported GraphQL syntax '.'", start);
    }

    private void lexString(int start) {
        StringBuilder value = new StringBuilder();
        while (!isAtEnd()) {
            char c = input.charAt(position);
            position++;
            if (c == '"') {
                tokens.add(new GraphqlToken(GraphqlTokenType.STRING, value.toString(), start));
                return;
            }
            if (c == '\\') {
                if (isAtEnd()) {
                    throw error("unterminated string escape", start);
                }
                char escaped = input.charAt(position);
                position++;
                value.append(escaped);
            } else {
                value.append(c);
            }
        }
        throw error("unterminated string literal", start);
    }

    private void skipComment() {
        while (!isAtEnd() && input.charAt(position) != '\n') {
            position++;
        }
    }

    private boolean isAtEnd() {
        return position >= input.length();
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private GraphqlException error(String message, int offset) {
        return GraphqlException.parse(message, input, offset);
    }
}
