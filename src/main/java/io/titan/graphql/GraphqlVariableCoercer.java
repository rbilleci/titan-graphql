package io.titan.graphql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GraphqlVariableCoercer {

    private GraphqlVariableCoercer() {
    }

    static GraphqlAst.AstOperation apply(GraphqlAst.AstOperation operation, Map<String, Object> suppliedVariables) {
        Map<String, GraphqlAst.VariableDefinition> definitions = variableDefinitions(operation);
        Map<String, Object> supplied = suppliedVariables == null ? Map.of() : suppliedVariables;
        validateNoUnknownVariables(definitions, supplied);
        List<String> usedVariables = usedVariables(operation);
        validateNoUnusedVariables(definitions, usedVariables);
        Map<String, GraphqlAst.Value> values = coercedValues(definitions, supplied, usedVariables);
        return new GraphqlAst.AstOperation(
                operation.type(),
                operation.name(),
                operation.variables(),
                resolveSelections(operation.selections(), values),
                resolveFragments(operation.fragments(), values)
        );
    }

    private static Map<String, GraphqlAst.VariableDefinition> variableDefinitions(GraphqlAst.AstOperation operation) {
        Map<String, GraphqlAst.VariableDefinition> definitions = new LinkedHashMap<>();
        for (GraphqlAst.VariableDefinition definition : operation.variables()) {
            if (isSupportedVariableType(namedType(definition.typeName())) == false) {
                throw new GraphqlException("variable '$" + definition.name()
                        + "' has unsupported type '" + definition.typeName() + "'");
            }
            definitions.put(definition.name(), definition);
        }
        return definitions;
    }

    private static void validateNoUnknownVariables(
            Map<String, GraphqlAst.VariableDefinition> definitions,
            Map<String, Object> supplied
    ) {
        for (String name : supplied.keySet()) {
            if (definitions.containsKey(name) == false) {
                throw new GraphqlException("variable '$" + name + "' is not declared by the selected operation");
            }
        }
    }

    private static void validateNoUnusedVariables(
            Map<String, GraphqlAst.VariableDefinition> definitions,
            List<String> usedVariables
    ) {
        for (String name : definitions.keySet()) {
            if (usedVariables.contains(name) == false) {
                throw new GraphqlException("variable '$" + name + "' is never used");
            }
        }
    }

    private static Map<String, GraphqlAst.Value> coercedValues(
            Map<String, GraphqlAst.VariableDefinition> definitions,
            Map<String, Object> supplied,
            List<String> usedVariables
    ) {
        Map<String, GraphqlAst.Value> values = new LinkedHashMap<>();
        for (GraphqlAst.VariableDefinition definition : definitions.values()) {
            boolean suppliedValue = supplied.containsKey(definition.name());
            Object raw = supplied.get(definition.name());
            if (raw == null && suppliedValue) {
                String type = namedType(definition.typeName());
                String kind = isScalarVariableType(type) ? "scalar type" : "type";
                throw new GraphqlException("variable '$" + definition.name()
                        + "' cannot be null for " + kind + " '" + type + "'");
            }
            if (suppliedValue) {
                values.put(definition.name(), coerceJsonValue(definition, raw));
            } else if (definition.defaultValue() != null) {
                values.put(definition.name(), coerceLiteralValue(definition, definition.defaultValue()));
            } else if (definition.required()) {
                throw new GraphqlException("required variable '$" + definition.name() + "' is missing");
            } else if (usedVariables.contains(definition.name())) {
                throw new GraphqlException("variable '$" + definition.name() + "' is not provided");
            }
        }
        return values;
    }

    private static List<GraphqlAst.Selection> resolveSelections(
            List<GraphqlAst.Selection> selections,
            Map<String, GraphqlAst.Value> values
    ) {
        List<GraphqlAst.Selection> resolved = new ArrayList<>();
        for (GraphqlAst.Selection selection : selections) {
            if (selection instanceof GraphqlAst.Field field) {
                resolved.add(resolveField(field, values));
            } else if (selection instanceof GraphqlAst.FragmentSpread fragmentSpread) {
                resolved.add(new GraphqlAst.FragmentSpread(
                        fragmentSpread.name(),
                        resolveDirectives(fragmentSpread.directives(), values)
                ));
            } else if (selection instanceof GraphqlAst.InlineFragment inlineFragment) {
                resolved.add(new GraphqlAst.InlineFragment(
                        inlineFragment.typeCondition(),
                        resolveDirectives(inlineFragment.directives(), values),
                        resolveSelections(inlineFragment.selections(), values)
                ));
            }
        }
        return List.copyOf(resolved);
    }

    private static GraphqlAst.Field resolveField(GraphqlAst.Field field, Map<String, GraphqlAst.Value> values) {
        Map<String, GraphqlAst.Value> arguments = new LinkedHashMap<>();
        for (Map.Entry<String, GraphqlAst.Value> argument : field.arguments().entrySet()) {
            arguments.put(argument.getKey(), resolveValue(argument.getValue(), values));
        }
        return new GraphqlAst.Field(
                field.name(),
                field.responseKey(),
                arguments,
                resolveDirectives(field.directives(), values),
                resolveSelections(field.selections(), values)
        );
    }

    private static List<GraphqlAst.Directive> resolveDirectives(
            List<GraphqlAst.Directive> directives,
            Map<String, GraphqlAst.Value> values
    ) {
        List<GraphqlAst.Directive> resolved = new ArrayList<>();
        for (GraphqlAst.Directive directive : directives) {
            Map<String, GraphqlAst.Value> arguments = new LinkedHashMap<>();
            for (Map.Entry<String, GraphqlAst.Value> argument : directive.arguments().entrySet()) {
                arguments.put(argument.getKey(), resolveValue(argument.getValue(), values));
            }
            resolved.add(new GraphqlAst.Directive(directive.name(), arguments));
        }
        return List.copyOf(resolved);
    }

    private static List<GraphqlAst.FragmentDefinition> resolveFragments(
            List<GraphqlAst.FragmentDefinition> fragments,
            Map<String, GraphqlAst.Value> values
    ) {
        List<GraphqlAst.FragmentDefinition> resolved = new ArrayList<>();
        for (GraphqlAst.FragmentDefinition fragment : fragments) {
            resolved.add(new GraphqlAst.FragmentDefinition(
                    fragment.name(),
                    fragment.typeCondition(),
                    resolveSelections(fragment.selections(), values)
            ));
        }
        return List.copyOf(resolved);
    }

    private static GraphqlAst.Value resolveValue(GraphqlAst.Value value, Map<String, GraphqlAst.Value> values) {
        if (value instanceof GraphqlAst.VariableValue variableValue) {
            GraphqlAst.Value resolved = values.get(variableValue.name());
            if (resolved == null) {
                throw new GraphqlException("variable '$" + variableValue.name() + "' is not declared");
            }
            return resolved;
        }
        if (value instanceof GraphqlAst.InputListValue listValue) {
            List<GraphqlAst.Value> resolved = new ArrayList<>();
            for (GraphqlAst.Value entry : listValue.values()) {
                resolved.add(resolveValue(entry, values));
            }
            return new GraphqlAst.InputListValue(List.copyOf(resolved));
        }
        if (value instanceof GraphqlAst.InputObjectValue objectValue) {
            Map<String, GraphqlAst.Value> resolved = new LinkedHashMap<>();
            for (Map.Entry<String, GraphqlAst.Value> field : objectValue.fields().entrySet()) {
                resolved.put(field.getKey(), resolveValue(field.getValue(), values));
            }
            return new GraphqlAst.InputObjectValue(Map.copyOf(resolved));
        }
        return value;
    }

    private static List<String> usedVariables(GraphqlAst.AstOperation operation) {
        List<String> variables = new ArrayList<>();
        Map<String, GraphqlAst.FragmentDefinition> fragments = fragmentDefinitions(operation.fragments());
        collectUsedVariables(operation.selections(), variables, fragments, new ArrayList<>());
        return List.copyOf(variables);
    }

    private static Map<String, GraphqlAst.FragmentDefinition> fragmentDefinitions(
            List<GraphqlAst.FragmentDefinition> fragments
    ) {
        Map<String, GraphqlAst.FragmentDefinition> definitions = new LinkedHashMap<>();
        for (GraphqlAst.FragmentDefinition fragment : fragments) {
            definitions.put(fragment.name(), fragment);
        }
        return definitions;
    }

    private static void collectUsedVariables(
            List<? extends GraphqlAst.Selection> selections,
            List<String> variables,
            Map<String, GraphqlAst.FragmentDefinition> fragments,
            List<String> activeFragments
    ) {
        for (GraphqlAst.Selection selection : selections) {
            if (selection instanceof GraphqlAst.Field field) {
                for (GraphqlAst.Value value : field.arguments().values()) {
                    collectUsedVariable(value, variables);
                }
                collectUsedDirectiveVariables(field.directives(), variables);
                collectUsedVariables(field.selections(), variables, fragments, activeFragments);
            } else if (selection instanceof GraphqlAst.FragmentSpread fragmentSpread) {
                collectUsedDirectiveVariables(fragmentSpread.directives(), variables);
                if (activeFragments.contains(fragmentSpread.name())) {
                    continue;
                }
                GraphqlAst.FragmentDefinition fragment = fragments.get(fragmentSpread.name());
                if (fragment == null) {
                    continue;
                }
                List<String> nestedFragments = new ArrayList<>(activeFragments);
                nestedFragments.add(fragmentSpread.name());
                collectUsedVariables(fragment.selections(), variables, fragments, nestedFragments);
            } else if (selection instanceof GraphqlAst.InlineFragment inlineFragment) {
                collectUsedDirectiveVariables(inlineFragment.directives(), variables);
                collectUsedVariables(inlineFragment.selections(), variables, fragments, activeFragments);
            }
        }
    }

    private static void collectUsedDirectiveVariables(
            List<GraphqlAst.Directive> directives,
            List<String> variables
    ) {
        for (GraphqlAst.Directive directive : directives) {
            for (GraphqlAst.Value value : directive.arguments().values()) {
                collectUsedVariable(value, variables);
            }
        }
    }

    private static void collectUsedVariable(GraphqlAst.Value value, List<String> variables) {
        if (value instanceof GraphqlAst.VariableValue variableValue
                && variables.contains(variableValue.name()) == false) {
            variables.add(variableValue.name());
        } else if (value instanceof GraphqlAst.InputListValue listValue) {
            for (GraphqlAst.Value entry : listValue.values()) {
                collectUsedVariable(entry, variables);
            }
        } else if (value instanceof GraphqlAst.InputObjectValue objectValue) {
            for (GraphqlAst.Value field : objectValue.fields().values()) {
                collectUsedVariable(field, variables);
            }
        }
    }

    private static GraphqlAst.Value coerceJsonValue(GraphqlAst.VariableDefinition definition, Object value) {
        return switch (namedType(definition.typeName())) {
            case "ID" -> coerceJsonId(definition, value);
            case "String" -> coerceJsonString(definition, value);
            case "Int" -> coerceJsonInt(definition, value);
            case "Float" -> coerceJsonFloat(definition, value);
            case "Boolean" -> coerceJsonBoolean(definition, value);
            case "ArticleFilter" -> coerceJsonArticleFilter(definition, value);
            case "ArticleOrderBy" -> coerceJsonArticleOrderBy(definition, value);
            default -> throw new GraphqlException("variable '$" + definition.name()
                    + "' has unsupported type '" + definition.typeName() + "'");
        };
    }

    private static GraphqlAst.Value coerceLiteralValue(
            GraphqlAst.VariableDefinition definition,
            GraphqlAst.Value value
    ) {
        return switch (namedType(definition.typeName())) {
            case "ID" -> {
                if (value instanceof GraphqlAst.IdValue) {
                    yield value;
                }
                if (value instanceof GraphqlAst.StringValue stringValue) {
                    yield new GraphqlAst.IdValue(stringValue.value());
                }
                if (value instanceof GraphqlAst.IntValue intValue) {
                    yield new GraphqlAst.IdValue(Long.toString(intValue.value()));
                }
                throw incompatibleDefault(definition);
            }
            case "String" -> {
                if (value instanceof GraphqlAst.StringValue) {
                    yield value;
                }
                throw incompatibleDefault(definition);
            }
            case "Int" -> {
                if (value instanceof GraphqlAst.IntValue) {
                    yield value;
                }
                throw incompatibleDefault(definition);
            }
            case "Float" -> {
                if (value instanceof GraphqlAst.FloatValue) {
                    yield value;
                }
                if (value instanceof GraphqlAst.IntValue intValue) {
                    yield new GraphqlAst.FloatValue(intValue.value());
                }
                throw incompatibleDefault(definition);
            }
            case "Boolean" -> {
                if (value instanceof GraphqlAst.BooleanValue) {
                    yield value;
                }
                throw incompatibleDefault(definition);
            }
            case "ArticleFilter", "ArticleOrderBy" -> value;
            default -> throw new GraphqlException("variable '$" + definition.name()
                    + "' has unsupported type '" + definition.typeName() + "'");
        };
    }

    private static GraphqlAst.Value coerceJsonId(GraphqlAst.VariableDefinition definition, Object value) {
        if (value instanceof String stringValue) {
            return new GraphqlAst.IdValue(stringValue);
        }
        if (isIntegralNumber(value)) {
            return new GraphqlAst.IdValue(Long.toString(((Number) value).longValue()));
        }
        throw incompatibleJson(definition);
    }

    private static GraphqlAst.Value coerceJsonString(GraphqlAst.VariableDefinition definition, Object value) {
        if (value instanceof String stringValue) {
            return new GraphqlAst.StringValue(stringValue);
        }
        throw incompatibleJson(definition);
    }

    private static GraphqlAst.Value coerceJsonInt(GraphqlAst.VariableDefinition definition, Object value) {
        if (isIntegralNumber(value)) {
            return new GraphqlAst.IntValue(((Number) value).longValue());
        }
        throw incompatibleJson(definition);
    }

    private static GraphqlAst.Value coerceJsonFloat(GraphqlAst.VariableDefinition definition, Object value) {
        if (value instanceof Number numberValue) {
            return new GraphqlAst.FloatValue(numberValue.doubleValue());
        }
        throw incompatibleJson(definition);
    }

    private static GraphqlAst.Value coerceJsonBoolean(GraphqlAst.VariableDefinition definition, Object value) {
        if (value instanceof Boolean booleanValue) {
            return new GraphqlAst.BooleanValue(booleanValue);
        }
        throw incompatibleJson(definition);
    }

    private static GraphqlAst.Value coerceJsonArticleFilter(GraphqlAst.VariableDefinition definition, Object value) {
        if (value instanceof Map<?, ?> objectValue) {
            return coerceArticleFilterObject(definition, objectValue);
        }
        throw incompatibleJson(definition);
    }

    private static GraphqlAst.Value coerceJsonArticleOrderBy(GraphqlAst.VariableDefinition definition, Object value) {
        if (isListType(definition.typeName())) {
            if (value instanceof List<?> listValue) {
                List<GraphqlAst.Value> orders = new ArrayList<>();
                for (Object entry : listValue) {
                    orders.add(coerceArticleOrderObject(definition, entry));
                }
                return new GraphqlAst.InputListValue(List.copyOf(orders));
            }
            throw incompatibleJson(definition);
        }
        return coerceArticleOrderObject(definition, value);
    }

    private static GraphqlAst.Value coerceArticleFilterObject(
            GraphqlAst.VariableDefinition definition,
            Map<?, ?> objectValue
    ) {
        Map<String, GraphqlAst.Value> fields = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : objectValue.entrySet()) {
            if ((entry.getKey() instanceof String) == false) {
                throw incompatibleJson(definition);
            }
            String key = (String) entry.getKey();
            Object raw = entry.getValue();
            if ("and".equals(key) || "or".equals(key)) {
                if ((raw instanceof List<?>) == false) {
                    throw incompatibleJson(definition);
                }
                List<?> listValue = (List<?>) raw;
                List<GraphqlAst.Value> values = new ArrayList<>();
                for (Object item : listValue) {
                    if ((item instanceof Map<?, ?>) == false) {
                        throw incompatibleJson(definition);
                    }
                    Map<?, ?> child = (Map<?, ?>) item;
                    values.add(coerceArticleFilterObject(definition, child));
                }
                fields.put(key, new GraphqlAst.InputListValue(List.copyOf(values)));
            } else if ("not".equals(key)) {
                if ((raw instanceof Map<?, ?>) == false) {
                    throw incompatibleJson(definition);
                }
                Map<?, ?> child = (Map<?, ?>) raw;
                fields.put(key, coerceArticleFilterObject(definition, child));
            } else {
                if ((raw instanceof Map<?, ?>) == false) {
                    throw incompatibleJson(definition);
                }
                Map<?, ?> scalarFilter = (Map<?, ?>) raw;
                fields.put(key, coerceGenericInputObject(definition, scalarFilter, false));
            }
        }
        return new GraphqlAst.InputObjectValue(Map.copyOf(fields));
    }

    private static GraphqlAst.Value coerceArticleOrderObject(GraphqlAst.VariableDefinition definition, Object value) {
        if ((value instanceof Map<?, ?>) == false) {
            throw incompatibleJson(definition);
        }
        Map<?, ?> objectValue = (Map<?, ?>) value;
        return coerceGenericInputObject(definition, objectValue, true);
    }

    private static GraphqlAst.Value coerceGenericInputObject(
            GraphqlAst.VariableDefinition definition,
            Map<?, ?> objectValue,
            boolean stringValuesAreEnums
    ) {
        Map<String, GraphqlAst.Value> fields = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : objectValue.entrySet()) {
            if ((entry.getKey() instanceof String) == false) {
                throw incompatibleJson(definition);
            }
            String key = (String) entry.getKey();
            fields.put(key, coerceGenericInputValue(definition, entry.getValue(), stringValuesAreEnums));
        }
        return new GraphqlAst.InputObjectValue(Map.copyOf(fields));
    }

    private static GraphqlAst.Value coerceGenericInputValue(
            GraphqlAst.VariableDefinition definition,
            Object value,
            boolean stringValuesAreEnums
    ) {
        if (value == null) {
            return new GraphqlAst.NullValue();
        }
        if (value instanceof String stringValue) {
            return stringValuesAreEnums
                    ? new GraphqlAst.EnumValue(stringValue)
                    : new GraphqlAst.StringValue(stringValue);
        }
        if (value instanceof Boolean booleanValue) {
            return new GraphqlAst.BooleanValue(booleanValue);
        }
        if (isIntegralNumber(value)) {
            return new GraphqlAst.IntValue(((Number) value).longValue());
        }
        if (value instanceof Number numberValue) {
            return new GraphqlAst.FloatValue(numberValue.doubleValue());
        }
        if (value instanceof List<?> listValue) {
            List<GraphqlAst.Value> values = new ArrayList<>();
            for (Object entry : listValue) {
                values.add(coerceGenericInputValue(definition, entry, stringValuesAreEnums));
            }
            return new GraphqlAst.InputListValue(List.copyOf(values));
        }
        if (value instanceof Map<?, ?> objectValue) {
            return coerceGenericInputObject(definition, objectValue, stringValuesAreEnums);
        }
        throw incompatibleJson(definition);
    }

    private static boolean isIntegralNumber(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return true;
        }
        if (value instanceof Number numberValue) {
            double doubleValue = numberValue.doubleValue();
            return Double.isFinite(doubleValue) && Math.rint(doubleValue) == doubleValue;
        }
        return false;
    }

    private static boolean isSupportedVariableType(String typeName) {
        return isScalarVariableType(typeName)
                || typeName.equals("ArticleFilter")
                || typeName.equals("ArticleOrderBy");
    }

    private static boolean isScalarVariableType(String typeName) {
        return typeName.equals("ID")
                || typeName.equals("String")
                || typeName.equals("Int")
                || typeName.equals("Float")
                || typeName.equals("Boolean");
    }

    private static String namedType(String typeName) {
        String result = typeName;
        boolean changed = true;
        while (changed) {
            changed = false;
            if (result.endsWith("!")) {
                result = result.substring(0, result.length() - 1);
                changed = true;
            }
            if (result.startsWith("[") && result.endsWith("]")) {
                result = result.substring(1, result.length() - 1);
                changed = true;
            }
        }
        return result;
    }

    private static boolean isListType(String typeName) {
        return typeName.startsWith("[");
    }

    private static GraphqlException incompatibleJson(GraphqlAst.VariableDefinition definition) {
        return new GraphqlException("variable '$" + definition.name()
                + "' cannot be coerced to '" + namedType(definition.typeName()) + "'");
    }

    private static GraphqlException incompatibleDefault(GraphqlAst.VariableDefinition definition) {
        return new GraphqlException("default value for variable '$" + definition.name()
                + "' cannot be coerced to '" + definition.typeName() + "'");
    }
}
