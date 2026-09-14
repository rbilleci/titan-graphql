package io.titan.graphql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GraphqlParser {

    private final List<GraphqlToken> tokens;
    private final String source;
    private int current;

    GraphqlParser(String query) {
        this.source = query == null ? "" : query;
        this.tokens = GraphqlLexer.lex(query);
    }

    static GraphqlAst.AstOperation parse(String query) {
        return parse(GraphqlRequest.query(query));
    }

    static GraphqlAst.AstOperation parse(GraphqlRequest request) {
        GraphqlAst.AstOperation operation = parseSelectedOperation(request);
        if (operation.type() != GraphqlAst.OperationType.QUERY) {
            throw GraphqlException.unsupportedOperation(operation.type().name().toLowerCase());
        }
        return operation;
    }

    static GraphqlAst.AstOperation parseSelectedOperation(GraphqlRequest request) {
        return parseSelectedOperation(request, null);
    }

    static GraphqlAst.AstOperation parseSelectedOperation(GraphqlRequest request, GraphqlSchema schema) {
        GraphqlAst.Document document = new GraphqlParser(request.query()).parseDocument();
        GraphqlAst.AstOperation operation = selectOperation(document, request.operationName());
        return GraphqlVariableCoercer.apply(operation, request.variables(), schema);
    }

    static GraphqlAst.Document parseDocument(String query) {
        return new GraphqlParser(query).parseDocument();
    }

    static GraphqlAst.OperationType selectedOperationType(String query, String operationName) {
        GraphqlAst.Document document = new GraphqlParser(query).parseDocument();
        return selectOperation(document, operationName == null ? "" : operationName).type();
    }

    GraphqlAst.AstOperation parse() {
        GraphqlAst.AstOperation operation = selectOperation(parseDocument(), "");
        if (operation.type() != GraphqlAst.OperationType.QUERY) {
            throw GraphqlException.unsupportedOperation(operation.type().name().toLowerCase());
        }
        return operation;
    }

    private GraphqlAst.Document parseDocument() {
        List<GraphqlAst.AstOperation> operations = new ArrayList<>();
        List<GraphqlAst.FragmentDefinition> fragments = new ArrayList<>();
        if (check(GraphqlTokenType.LEFT_BRACE)) {
            operations.add(parseShorthandOperation());
            consume(GraphqlTokenType.EOF, "unexpected trailing GraphQL input");
            return new GraphqlAst.Document(List.copyOf(operations), List.copyOf(fragments));
        }
        while (!check(GraphqlTokenType.EOF)) {
            if (checkName("fragment")) {
                fragments.add(parseFragmentDefinition());
            } else {
                operations.add(parseOperationDefinition());
            }
        }
        return new GraphqlAst.Document(List.copyOf(operations), List.copyOf(fragments));
    }

    private GraphqlAst.AstOperation parseOperationDefinition() {
        GraphqlAst.OperationType operationType = parseOperationType();
        String operationName = "";
        if (check(GraphqlTokenType.NAME)) {
            operationName = tokens.get(current++).text();
        }
        List<GraphqlAst.VariableDefinition> variables = parseVariableDefinitions();
        List<GraphqlAst.Selection> selections = parseSelectionSet();
        return new GraphqlAst.AstOperation(operationType, operationName, variables, selections, List.of());
    }

    private GraphqlAst.FragmentDefinition parseFragmentDefinition() {
        consume(GraphqlTokenType.NAME, "expected 'fragment'");
        GraphqlToken name = consume(GraphqlTokenType.NAME, "expected fragment name");
        if (name.text().equals("on")) {
            throw error(name, "fragment name cannot be 'on'");
        }
        consumeName("on", "expected 'on' before fragment type condition");
        GraphqlToken typeCondition = consume(GraphqlTokenType.NAME, "expected fragment type condition");
        return new GraphqlAst.FragmentDefinition(name.text(), typeCondition.text(), parseSelectionSet());
    }

    private GraphqlAst.AstOperation parseShorthandOperation() {
        return new GraphqlAst.AstOperation(GraphqlAst.OperationType.QUERY, "", List.of(), parseSelectionSet(), List.of());
    }

    private List<GraphqlAst.VariableDefinition> parseVariableDefinitions() {
        if (!match(GraphqlTokenType.LEFT_PAREN)) {
            return List.of();
        }
        List<GraphqlAst.VariableDefinition> variables = new ArrayList<>();
        Map<String, Boolean> names = new LinkedHashMap<>();
        while (!check(GraphqlTokenType.RIGHT_PAREN) && !check(GraphqlTokenType.EOF)) {
            consume(GraphqlTokenType.DOLLAR, "expected '$' before variable name");
            GraphqlToken name = consume(GraphqlTokenType.NAME, "expected variable name");
            if (names.put(name.text(), Boolean.TRUE) != null) {
                throw error(name, "duplicate variable '$" + name.text() + "'");
            }
            consume(GraphqlTokenType.COLON, "expected ':' after variable name");
            String type = parseVariableType();
            boolean required = type.endsWith("!");
            GraphqlAst.Value defaultValue = null;
            if (match(GraphqlTokenType.EQUALS)) {
                defaultValue = parseLiteralValue();
            }
            variables.add(new GraphqlAst.VariableDefinition(name.text(), type, required, defaultValue));
            match(GraphqlTokenType.COMMA);
        }
        consume(GraphqlTokenType.RIGHT_PAREN, "expected ')' after variable definitions");
        return List.copyOf(variables);
    }

    private String parseVariableType() {
        if (match(GraphqlTokenType.LEFT_BRACKET)) {
            String itemType = parseVariableType();
            consume(GraphqlTokenType.RIGHT_BRACKET, "expected closing variable list type");
            String suffix = match(GraphqlTokenType.BANG) ? "!" : "";
            return "[" + itemType + "]" + suffix;
        }
        GraphqlToken type = consume(GraphqlTokenType.NAME, "expected variable type");
        String suffix = match(GraphqlTokenType.BANG) ? "!" : "";
        return type.text() + suffix;
    }

    private GraphqlAst.OperationType parseOperationType() {
        if (matchName("query")) {
            return GraphqlAst.OperationType.QUERY;
        }
        if (matchName("mutation")) {
            return GraphqlAst.OperationType.MUTATION;
        }
        if (matchName("subscription")) {
            return GraphqlAst.OperationType.SUBSCRIPTION;
        }
        throw error(peek(), "expected operation type");
    }

    private List<GraphqlAst.Selection> parseSelectionSet() {
        consume(GraphqlTokenType.LEFT_BRACE, "expected selection set");
        List<GraphqlAst.Selection> selections = new ArrayList<>();
        while (!check(GraphqlTokenType.RIGHT_BRACE) && !check(GraphqlTokenType.EOF)) {
            selections.add(parseSelection());
        }
        consume(GraphqlTokenType.RIGHT_BRACE, "expected closing selection set");
        if (selections.isEmpty()) {
            throw error(previous(), "selection set must contain at least one field");
        }
        return List.copyOf(selections);
    }

    private GraphqlAst.Selection parseSelection() {
        if (match(GraphqlTokenType.ELLIPSIS)) {
            if (matchName("on")) {
                GraphqlToken typeCondition = consume(GraphqlTokenType.NAME, "expected inline fragment type condition");
                return new GraphqlAst.InlineFragment(
                        typeCondition.text(),
                        parseDirectives(),
                        parseSelectionSet()
                );
            }
            GraphqlToken fragmentName = consume(GraphqlTokenType.NAME, "expected fragment name");
            return new GraphqlAst.FragmentSpread(fragmentName.text(), parseDirectives());
        }
        return parseField();
    }

    private GraphqlAst.Field parseField() {
        GraphqlToken firstName = consume(GraphqlTokenType.NAME, "expected field name");
        String responseKey = firstName.text();
        String name = firstName.text();
        if (match(GraphqlTokenType.COLON)) {
            GraphqlToken aliasedName = consume(GraphqlTokenType.NAME, "expected field name after alias");
            name = aliasedName.text();
        }
        Map<String, GraphqlAst.Value> arguments = parseArguments();
        List<GraphqlAst.Directive> directives = parseDirectives();
        List<GraphqlAst.Selection> selections = check(GraphqlTokenType.LEFT_BRACE)
                ? parseSelectionSet()
                : List.of();
        return new GraphqlAst.Field(name, responseKey, arguments, directives, selections);
    }

    private List<GraphqlAst.Directive> parseDirectives() {
        if (!check(GraphqlTokenType.AT)) {
            return List.of();
        }
        List<GraphqlAst.Directive> directives = new ArrayList<>();
        Map<String, Boolean> names = new LinkedHashMap<>();
        while (match(GraphqlTokenType.AT)) {
            GraphqlToken name = consume(GraphqlTokenType.NAME, "expected directive name");
            if (names.put(name.text(), Boolean.TRUE) != null) {
                throw error(name, "duplicate directive '@" + name.text() + "'");
            }
            directives.add(new GraphqlAst.Directive(name.text(), parseArguments()));
        }
        return List.copyOf(directives);
    }

    private Map<String, GraphqlAst.Value> parseArguments() {
        if (!match(GraphqlTokenType.LEFT_PAREN)) {
            return Map.of();
        }
        Map<String, GraphqlAst.Value> arguments = new LinkedHashMap<>();
        while (!check(GraphqlTokenType.RIGHT_PAREN) && !check(GraphqlTokenType.EOF)) {
            GraphqlToken name = consume(GraphqlTokenType.NAME, "expected argument name");
            consume(GraphqlTokenType.COLON, "expected ':' after argument name");
            GraphqlAst.Value value = parseInputValue();
            if (arguments.put(name.text(), value) != null) {
                throw error(name, "duplicate argument '" + name.text() + "'");
            }
            match(GraphqlTokenType.COMMA);
        }
        consume(GraphqlTokenType.RIGHT_PAREN, "expected ')' after arguments");
        return Map.copyOf(arguments);
    }

    private GraphqlAst.Value parseInputValue() {
        if (match(GraphqlTokenType.DOLLAR)) {
            GraphqlToken name = consume(GraphqlTokenType.NAME, "expected variable name");
            return new GraphqlAst.VariableValue(name.text());
        }
        return parseLiteralValue();
    }

    private GraphqlAst.Value parseLiteralValue() {
        if (match(GraphqlTokenType.LEFT_BRACKET)) {
            return parseListValue();
        }
        if (match(GraphqlTokenType.LEFT_BRACE)) {
            return parseObjectValue();
        }
        if (match(GraphqlTokenType.INT)) {
            try {
                return new GraphqlAst.IntValue(Long.parseLong(previous().text()));
            } catch (NumberFormatException ex) {
                throw error(previous(), "integer value is out of range");
            }
        }
        if (match(GraphqlTokenType.FLOAT)) {
            try {
                return new GraphqlAst.FloatValue(Double.parseDouble(previous().text()));
            } catch (NumberFormatException ex) {
                throw error(previous(), "float value is out of range");
            }
        }
        if (match(GraphqlTokenType.STRING)) {
            return new GraphqlAst.StringValue(previous().text());
        }
        if (match(GraphqlTokenType.NAME)) {
            if (previous().text().equals("true")) {
                return new GraphqlAst.BooleanValue(true);
            }
            if (previous().text().equals("false")) {
                return new GraphqlAst.BooleanValue(false);
            }
            if (previous().text().equals("null")) {
                return new GraphqlAst.NullValue();
            }
            return new GraphqlAst.EnumValue(previous().text());
        }
        throw error(peek(), "expected argument value");
    }

    private GraphqlAst.Value parseListValue() {
        List<GraphqlAst.Value> values = new ArrayList<>();
        while (!check(GraphqlTokenType.RIGHT_BRACKET) && !check(GraphqlTokenType.EOF)) {
            values.add(parseInputValue());
            match(GraphqlTokenType.COMMA);
        }
        consume(GraphqlTokenType.RIGHT_BRACKET, "expected closing input list");
        return new GraphqlAst.InputListValue(List.copyOf(values));
    }

    private GraphqlAst.Value parseObjectValue() {
        Map<String, GraphqlAst.Value> fields = new LinkedHashMap<>();
        while (!check(GraphqlTokenType.RIGHT_BRACE) && !check(GraphqlTokenType.EOF)) {
            GraphqlToken name = consume(GraphqlTokenType.NAME, "expected input object field name");
            consume(GraphqlTokenType.COLON, "expected ':' after input object field name");
            if (fields.put(name.text(), parseInputValue()) != null) {
                throw error(name, "duplicate input object field '" + name.text() + "'");
            }
            match(GraphqlTokenType.COMMA);
        }
        consume(GraphqlTokenType.RIGHT_BRACE, "expected closing input object");
        return new GraphqlAst.InputObjectValue(Map.copyOf(fields));
    }

    private boolean matchName(String expected) {
        if (checkName(expected) == false) {
            return false;
        }
        current++;
        return true;
    }

    private boolean checkName(String expected) {
        return check(GraphqlTokenType.NAME) && peek().text().equals(expected);
    }

    private void consumeName(String expected, String message) {
        if (matchName(expected) == false) {
            throw error(peek(), message);
        }
    }

    private boolean match(GraphqlTokenType type) {
        if (!check(type)) {
            return false;
        }
        current++;
        return true;
    }

    private GraphqlToken consume(GraphqlTokenType type, String message) {
        if (check(type)) {
            return tokens.get(current++);
        }
        throw error(peek(), message);
    }

    private boolean check(GraphqlTokenType type) {
        return peek().type() == type;
    }

    private GraphqlToken peek() {
        return tokens.get(current);
    }

    private GraphqlToken previous() {
        return tokens.get(current - 1);
    }

    private GraphqlException error(GraphqlToken token, String message) {
        return GraphqlException.parse(message, source, token.offset());
    }

    private static GraphqlAst.AstOperation selectOperation(GraphqlAst.Document document, String operationName) {
        if (document.operations().isEmpty()) {
            throw new GraphqlException("expected selection set");
        }
        String selectedName = operationName == null ? "" : operationName;
        GraphqlAst.AstOperation operation;
        if (selectedName.isEmpty()) {
            if (document.operations().size() != 1) {
                throw new GraphqlException("multiple operations require operationName");
            }
            operation = document.operations().getFirst();
        } else {
            operation = null;
            for (GraphqlAst.AstOperation candidate : document.operations()) {
                if (selectedName.equals(candidate.name())) {
                    if (operation != null) {
                        throw new GraphqlException("operationName '" + selectedName + "' is ambiguous");
                    }
                    operation = candidate;
                }
            }
            if (operation == null) {
                throw new GraphqlException("operationName '" + selectedName + "' was not found");
            }
        }
        return new GraphqlAst.AstOperation(
                operation.type(),
                operation.name(),
                operation.variables(),
                operation.selections(),
                document.fragments()
        );
    }
}
