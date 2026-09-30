package io.titan.graphql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TitanGraphqlConstantValueParser {
    private static final int MAX_TOKENS = 1024;
    private static final int MAX_DEPTH = 64;

    private final List<GraphqlToken> tokens;
    private int position;

    private TitanGraphqlConstantValueParser(String source) {
        tokens = TitanGraphqlConstantLexer.lex(source);
        if (tokens.size() > MAX_TOKENS) {
            throw new IllegalArgumentException("GraphQL constant exceeds the token budget");
        }
    }

    static GraphqlAst.Value parse(String source) {
        TitanGraphqlConstantValueParser parser = new TitanGraphqlConstantValueParser(source);
        GraphqlAst.Value value = parser.value(0);
        parser.require(GraphqlTokenType.EOF);
        return value;
    }

    private GraphqlAst.Value value(int depth) {
        if (depth >= MAX_DEPTH) {
            throw new IllegalArgumentException("GraphQL constant exceeds the nesting budget");
        }
        GraphqlToken token = tokens.get(position++);
        return switch (token.type()) {
            case LEFT_BRACKET -> list(depth + 1);
            case LEFT_BRACE -> object(depth + 1);
            case INT -> new GraphqlAst.IntValue(Long.parseLong(token.text()));
            case FLOAT -> new GraphqlAst.FloatValue(Double.parseDouble(token.text()));
            case STRING -> new GraphqlAst.StringValue(token.text());
            case NAME -> switch (token.text()) {
                case "true" -> new GraphqlAst.BooleanValue(true);
                case "false" -> new GraphqlAst.BooleanValue(false);
                case "null" -> new GraphqlAst.NullValue();
                default -> new GraphqlAst.EnumValue(token.text());
            };
            default -> throw new IllegalArgumentException("expected GraphQL constant value");
        };
    }

    private GraphqlAst.Value list(int depth) {
        List<GraphqlAst.Value> values = new ArrayList<>();
        while (peek() != GraphqlTokenType.RIGHT_BRACKET) {
            if (peek() == GraphqlTokenType.EOF) {
                throw new IllegalArgumentException("unterminated GraphQL constant list");
            }
            values.add(value(depth));
            skipComma();
        }
        position++;
        return new GraphqlAst.InputListValue(List.copyOf(values));
    }

    private GraphqlAst.Value object(int depth) {
        Map<String, GraphqlAst.Value> fields = new LinkedHashMap<>();
        while (peek() != GraphqlTokenType.RIGHT_BRACE) {
            if (peek() == GraphqlTokenType.EOF) {
                throw new IllegalArgumentException("unterminated GraphQL constant object");
            }
            String name = require(GraphqlTokenType.NAME).text();
            require(GraphqlTokenType.COLON);
            if (fields.put(name, value(depth)) != null) {
                throw new IllegalArgumentException("duplicate GraphQL constant field");
            }
            skipComma();
        }
        position++;
        return new GraphqlAst.InputObjectValue(Map.copyOf(fields));
    }

    private void skipComma() {
        if (peek() == GraphqlTokenType.COMMA) {
            position++;
        }
    }

    private GraphqlToken require(GraphqlTokenType type) {
        GraphqlToken token = tokens.get(position);
        if (token.type() != type) {
            throw new IllegalArgumentException("expected GraphQL constant " + type);
        }
        position++;
        return token;
    }

    private GraphqlTokenType peek() {
        return tokens.get(position).type();
    }
}
