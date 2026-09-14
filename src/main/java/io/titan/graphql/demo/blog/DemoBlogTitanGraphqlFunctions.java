package io.titan.graphql.demo.blog;

import titan.dsl.StoredFunction;

public final class DemoBlogTitanGraphqlFunctions {

    private DemoBlogTitanGraphqlFunctions() {
    }

    @StoredFunction
    public static String executeGraphql(String query, long actorId, String actorRole) {
        return executeDemoBlogGraphqlJson(query, actorRole, false, false, false, false);
    }

    @StoredFunction
    public static String executeGraphqlRequest(String query, String operationName, long actorId, String actorRole) {
        return executeDemoBlogGraphqlJson(query, operationName, actorRole, false, false, false, false);
    }

    @StoredFunction
    public static String executeGraphqlRequestWithVariables(
            String query,
            String operationName,
            String variablesJson,
            String extensionsJson,
            long actorId,
            String actorRole
    ) {
        return executeDemoBlogGraphqlJson(
                query,
                operationName,
                variablesJson,
                extensionsJson,
                actorRole,
                false,
                false,
                false,
                false
        );
    }

    @StoredFunction
    public static String executeGraphqlRequestWithContext(
            String query,
            String operationName,
            String variablesJson,
            String extensionsJson,
            long actorId,
            String actorRole,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility,
            boolean enableIntrospection
    ) {
        return executeGraphqlRequestWithCompactContext(
                query,
                operationName,
                variablesJson,
                extensionsJson,
                actorId,
                actorRole,
                enablePublishedVisibility,
                hasArticleVisibility,
                articleVisibility,
                enableIntrospection,
                "",
                "",
                "",
                "",
                0L
        );
    }

    @StoredFunction
    public static String executeGraphqlRequestWithCompactContext(
            String query,
            String operationName,
            String variablesJson,
            String extensionsJson,
            long actorId,
            String actorRole,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility,
            boolean enableIntrospection,
            String tenantId,
            String requestId,
            String policyFlags,
            String enabledContextFilters,
            long deadlineBudgetMillis
    ) {
        boolean contextFilterEnabled = enablePublishedVisibility
                || contextListContains(enabledContextFilters, "publishedVisibility");
        return executeDemoBlogGraphqlJson(
                query,
                operationName,
                variablesJson,
                extensionsJson,
                actorRole,
                contextFilterEnabled,
                hasArticleVisibility,
                articleVisibility,
                enableIntrospection
        );
    }

    @StoredFunction
    public static String executeGraphqlWithContext(
            String query,
            long actorId,
            String actorRole,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility
    ) {
        return executeDemoBlogGraphqlJson(
                query,
                actorRole,
                enablePublishedVisibility,
                hasArticleVisibility,
                articleVisibility,
                false
        );
    }

    @StoredFunction
    public static String executeGraphqlWithIntrospection(
            String query,
            long actorId,
            String actorRole,
            boolean enableIntrospection
    ) {
        return executeDemoBlogGraphqlJson(query, actorRole, false, false, false, enableIntrospection);
    }

    private static String executeDemoBlogGraphqlJson(
            String query,
            String actorRole,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility,
            boolean enableIntrospection
    ) {
        return executeDemoBlogGraphqlJson(
                query,
                "",
                actorRole,
                enablePublishedVisibility,
                hasArticleVisibility,
                articleVisibility,
                enableIntrospection
        );
    }

    private static String executeDemoBlogGraphqlJson(
            String query,
            String operationName,
            String actorRole,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility,
            boolean enableIntrospection
    ) {
        return executeDemoBlogGraphqlJson(
                query,
                operationName,
                "",
                "",
                actorRole,
                enablePublishedVisibility,
                hasArticleVisibility,
                articleVisibility,
                enableIntrospection
        );
    }

    private static String executeDemoBlogGraphqlJson(
            String query,
            String operationName,
            String variablesJson,
            String extensionsJson,
            String actorRole,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility,
            boolean enableIntrospection
    ) {
        String source = query == null ? "" : query;
        String selectedOperationError = operationNameSelectionError(source, operationName);
        if (selectedOperationError.isEmpty() == false) {
            return errorJson(selectedOperationError);
        }
        source = selectNamedOperationSource(source, operationName);
        String envelopeError = requestEnvelopeValidationError(source, variablesJson, extensionsJson);
        if (envelopeError.isEmpty() == false) {
            return errorJson(envelopeError);
        }
        source = lowerJsonVariables(source, variablesJson);
        String operationError = operationValidationError(source);
        if (operationError.isEmpty() == false) {
            return errorJson(operationError);
        }
        String directiveError = runtimeDirectiveValidationError(source);
        if (directiveError.isEmpty() == false) {
            return errorJson(directiveError);
        }
        String namedFragmentError = namedFragmentValidationError(source);
        if (namedFragmentError.isEmpty() == false) {
            return errorJson(namedFragmentError);
        }
        source = expandNamedFragments(source);
        source = pruneRuntimeDirectives(source);
        String fragmentError = inlineFragmentValidationError(source);
        if (fragmentError.isEmpty() == false) {
            return errorJson(fragmentError);
        }
        source = expandInlineFragments(source);
        String rootError = rootValidationError(source, enableIntrospection);
        if (rootError.isEmpty() == false) {
            return errorJson(rootError);
        }
        String rootName = rootFieldName(source);
        if ("__schema".equals(rootName) || "__type".equals(rootName)) {
            String introspectionError = introspectionValidationError(source, rootName);
            if (introspectionError.isEmpty() == false) {
                return errorJson(introspectionError);
            }
            return renderIntrospectionJson(source, rootName);
        }
        String validationError = selectionValidationError(source);
        if (validationError.isEmpty() == false) {
            return errorJson(validationError);
        }
        String argumentError = "articles".equals(rootName)
                ? articlesArgumentValidationError(source)
                : articleArgumentValidationError(source);
        if (argumentError.isEmpty() == false) {
            return errorJson(argumentError);
        }
        if (userEmailFieldRequested(source) && isAdmin(actorRole) == false) {
            return errorJson("field 'User.email' is not authorized for actor role '" + actorRole + "'");
        }

        if ("articles".equals(rootName)) {
            long pageSize = articlesPageSize(source);
            if (pageSize < 0 || pageSize > 100) {
                return errorJson("argument '" + articlesPageSizeArgumentName(source) + "' must be between 0 and 100");
            }
            long after = articlesCursorArgument(source, "after");
            if (after == -2L) {
                return errorJson("argument 'after' must be an article cursor");
            }
            long before = articlesCursorArgument(source, "before");
            if (before == -2L) {
                return errorJson("argument 'before' must be an article cursor");
            }
            long first = articlesIntArgument(source, "first", 10);
            if (first == -2L) {
                return errorJson("expected ':' after argument name");
            }
            if (first == -3L) {
                return errorJson("argument 'first' must be an integer");
            }
            long last = articlesIntArgument(source, "last", 10);
            if (last == -2L) {
                return errorJson("expected ':' after argument name");
            }
            if (last == -3L) {
                return errorJson("argument 'last' must be an integer");
            }
            long authorId = articlesAuthorId(source);
            if (authorId == -2L) {
                return errorJson("expected ':' after argument name");
            }
            if (authorId == -3L) {
                return errorJson("argument 'authorId' must be an integer");
            }
            String generatedFilterError = generatedArticleFilterValidationError(source);
            if (generatedFilterError.isEmpty() == false) {
                return errorJson(generatedFilterError);
            }
            String generatedOrderError = generatedArticleOrderValidationError(source);
            if (generatedOrderError.isEmpty() == false) {
                return errorJson(generatedOrderError);
            }
            return renderArticlesConnectionJson(
                    source,
                    pageSize,
                    after,
                    before,
                    articlesHasArgument(source, "last"),
                    authorId,
                    actorRole,
                    enablePublishedVisibility,
                    hasArticleVisibility,
                    articleVisibility
            );
        }

        long articleId = articleId(source);
        if (articleId == -1L) {
            return errorJson("required argument 'id' is missing");
        }
        if (articleId == -2L) {
            return errorJson("expected ':' after argument name");
        }
        if (articleId == -3L) {
            return errorJson("argument 'id' must be an integer");
        }
        if (articleId != 1L && articleId != 2L) {
            return "{\"data\":{\"article\":null}}";
        }
        return renderArticleJson(source, articleId, actorRole);
    }

    private static String requestEnvelopeValidationError(
            String query,
            String variablesJson,
            String extensionsJson
    ) {
        String variablesError = jsonObjectEnvelopeError(variablesJson, "variables");
        if (variablesError.isEmpty() == false) {
            return variablesError;
        }
        String extensionsError = jsonObjectEnvelopeError(extensionsJson, "extensions");
        if (extensionsError.isEmpty() == false) {
            return extensionsError;
        }
        String variables = normalizeJsonObject(variablesJson);
        if (variables.isEmpty() == false) {
            String variableEntryError = variableJsonEntriesValidationError(query, variables);
            if (variableEntryError.isEmpty() == false) {
                return variableEntryError;
            }
        }
        String definitionError = variableDefinitionsValidationError(query);
        if (definitionError.isEmpty() == false) {
            return definitionError;
        }
        return missingVariableValidationError(query, variables);
    }

    private static boolean contextListContains(String names, String expectedName) {
        String value = names == null ? "" : names;
        int start = 0;
        while (start <= value.length()) {
            int comma = value.indexOf(',', start);
            int end = comma == -1 ? value.length() : comma;
            String token = value.substring(start, end).trim();
            if (token.equals(expectedName)) {
                return true;
            }
            if (comma == -1) {
                return false;
            }
            start = comma + 1;
        }
        return false;
    }

    private static String jsonObjectEnvelopeError(String json, String fieldName) {
        String value = normalizeJson(json);
        if (value.isEmpty()) {
            return "";
        }
        if (value.charAt(0) != '{') {
            return "request field '" + fieldName + "' must be a JSON object";
        }
        int end = skipJsonObject(value, 0);
        if (end <= 0 || nextNonWhitespace(value, end, value.length()) < value.length()) {
            return "request field '" + fieldName + "' must be a valid JSON object";
        }
        return "";
    }

    private static String variableJsonEntriesValidationError(String query, String variablesJson) {
        int end = skipJsonObject(variablesJson, 0);
        int position = nextNonWhitespace(variablesJson, 1, end - 1);
        while (position < end - 1) {
            if (variablesJson.charAt(position) != '"') {
                return "request field 'variables' must be a valid JSON object";
            }
            int nameEnd = skipJsonString(variablesJson, position);
            String name = jsonStringLiteralValue(variablesJson, position, nameEnd);
            if (variableDefinitionType(query, name).isEmpty()) {
                return "variable '$" + name + "' is not declared by the selected operation";
            }
            int separator = nextNonWhitespace(variablesJson, nameEnd, end - 1);
            if (separator >= end - 1 || variablesJson.charAt(separator) != ':') {
                return "request field 'variables' must be a valid JSON object";
            }
            int valueStart = nextNonWhitespace(variablesJson, separator + 1, end - 1);
            int valueEnd = skipJsonValue(variablesJson, valueStart);
            if (valueEnd <= valueStart) {
                return "request field 'variables' must be a valid JSON object";
            }
            String valueError = jsonVariableValueValidationError(
                    name,
                    variableDefinitionType(query, name),
                    variablesJson,
                    valueStart,
                    valueEnd
            );
            if (valueError.isEmpty() == false) {
                return valueError;
            }
            position = nextNonWhitespace(variablesJson, valueEnd, end - 1);
            if (position < end - 1) {
                if (variablesJson.charAt(position) != ',') {
                    return "request field 'variables' must be a valid JSON object";
                }
                position = nextNonWhitespace(variablesJson, position + 1, end - 1);
            }
        }
        return "";
    }

    private static String jsonVariableValueValidationError(
            String name,
            String typeName,
            String json,
            int valueStart,
            int valueEnd
    ) {
        String namedType = variableNamedType(typeName);
        if (isExactNameAt(json, "null", valueStart)) {
            String kind = isScalarJsonVariableType(namedType) ? "scalar type" : "type";
            return "variable '$" + name + "' cannot be null for " + kind + " '" + namedType + "'";
        }
        if ("ArticleFilter".equals(namedType)) {
            if (valueStart < valueEnd && json.charAt(valueStart) == '{') {
                return "";
            }
            return "variable '$" + name + "' expected ArticleFilter JSON object";
        }
        if ("ArticleOrderBy".equals(namedType)) {
            if (variableTypeIsList(typeName)) {
                if (valueStart < valueEnd && json.charAt(valueStart) == '[') {
                    return "";
                }
                return "variable '$" + name + "' expected ArticleOrderBy JSON list";
            }
            if (valueStart < valueEnd && json.charAt(valueStart) == '{') {
                return "";
            }
            return "variable '$" + name + "' expected ArticleOrderBy JSON object";
        }
        if ("Boolean".equals(namedType)) {
            if (isExactNameAt(json, "true", valueStart) || isExactNameAt(json, "false", valueStart)) {
                return "";
            }
            return "variable '$" + name + "' expected Boolean JSON value";
        }
        if ("Int".equals(namedType)) {
            if (isIntegerLiteral(json, valueStart, valueEnd)) {
                return "";
            }
            return "variable '$" + name + "' expected Int JSON value";
        }
        if ("Float".equals(namedType)) {
            if (isJsonNumberLiteral(json, valueStart, valueEnd)) {
                return "";
            }
            return "variable '$" + name + "' expected Float JSON value";
        }
        if ("ID".equals(namedType)) {
            if (isIntegerLiteral(json, valueStart, valueEnd)) {
                return "";
            }
            if (json.charAt(valueStart) == '"' && isIntegerLiteral(json, valueStart + 1, valueEnd - 1)) {
                return "";
            }
            return "variable '$" + name + "' expected ID JSON value";
        }
        if ("String".equals(namedType)) {
            if (json.charAt(valueStart) == '"') {
                return "";
            }
            return "variable '$" + name + "' expected String JSON value";
        }
        return "variable '$" + name + "' has unsupported type '" + typeName + "'";
    }

    private static boolean isJsonNumberLiteral(String json, int valueStart, int valueEnd) {
        valueStart = nextNonWhitespace(json, valueStart, valueEnd);
        while (valueEnd > valueStart && isWhitespace(json.charAt(valueEnd - 1))) {
            valueEnd--;
        }
        if (valueStart >= valueEnd) {
            return false;
        }
        int position = valueStart;
        if (json.charAt(position) == '-') {
            position++;
        }
        boolean sawDigit = false;
        while (position < valueEnd && json.charAt(position) >= '0' && json.charAt(position) <= '9') {
            sawDigit = true;
            position++;
        }
        if (position < valueEnd && json.charAt(position) == '.') {
            position++;
            while (position < valueEnd && json.charAt(position) >= '0' && json.charAt(position) <= '9') {
                sawDigit = true;
                position++;
            }
        }
        return sawDigit && position == valueEnd;
    }

    private static String variableDefinitionsValidationError(String query) {
        int position = variableDefinitionsStart(query);
        int end = variableDefinitionsEnd(query, position);
        while (position < end) {
            if (query.charAt(position) == '$') {
                int nameStart = position + 1;
                int nameEnd = nameStart;
                while (nameEnd < end && isNamePart(query.charAt(nameEnd))) {
                    nameEnd++;
                }
                String name = query.substring(nameStart, nameEnd);
                String typeName = variableDefinitionType(query, name);
                if (isSupportedJsonVariableType(typeName) == false) {
                    return "variable '$" + name + "' has unsupported type '" + typeName + "'";
                }
                if (queryUsesVariable(query, name) == false) {
                    return "variable '$" + name + "' is never used";
                }
                position = nameEnd;
            } else {
                position++;
            }
        }
        return "";
    }

    private static boolean isSupportedJsonVariableType(String typeName) {
        String namedType = variableNamedType(typeName);
        return isScalarJsonVariableType(namedType)
                || "ArticleFilter".equals(namedType)
                || "ArticleOrderBy".equals(namedType);
    }

    private static boolean isScalarJsonVariableType(String typeName) {
        return "ID".equals(typeName)
                || "String".equals(typeName)
                || "Int".equals(typeName)
                || "Float".equals(typeName)
                || "Boolean".equals(typeName);
    }

    private static String missingVariableValidationError(String query, String variablesJson) {
        int position = variableDefinitionsStart(query);
        int end = variableDefinitionsEnd(query, position);
        while (position < end) {
            if (query.charAt(position) == '$') {
                int nameStart = position + 1;
                int nameEnd = nameStart;
                while (nameEnd < end && isNamePart(query.charAt(nameEnd))) {
                    nameEnd++;
                }
                String name = query.substring(nameStart, nameEnd);
                if (jsonObjectHasField(variablesJson, name) == false) {
                    String defaultValue = variableDefinitionDefaultValue(query, name);
                    if (defaultValue.isEmpty()) {
                        if (variableDefinitionRequired(query, name)) {
                            return "required variable '$" + name + "' is missing";
                        }
                        if (queryUsesVariable(query, name)) {
                            return "variable '$" + name + "' is not provided";
                        }
                    }
                }
                position = nameEnd;
            } else {
                position++;
            }
        }
        return "";
    }

    private static String lowerJsonVariables(String query, String variablesJson) {
        String variables = normalizeJsonObject(variablesJson);
        int definitionsStart = variableDefinitionsStart(query);
        int definitionsEnd = variableDefinitionsEnd(query, definitionsStart);
        String result = "";
        boolean inVariable = false;
        String name = "";
        int variableStart = 0;
        int position = 0;
        while (position < query.length()) {
            char current = query.charAt(position);
            if (inVariable) {
                if (isNamePart(current)) {
                    name = name + current;
                } else {
                    result = result + loweredVariableText(query, variables, name, variableStart, definitionsEnd);
                    if (current == '$') {
                        name = "";
                        variableStart = position;
                    } else {
                        inVariable = false;
                        result = result + current;
                    }
                }
            } else if (current == '$') {
                inVariable = true;
                name = "";
                variableStart = position;
            } else {
                result = result + current;
            }
            position++;
        }
        if (inVariable) {
            result = result + loweredVariableText(query, variables, name, variableStart, definitionsEnd);
        }
        return result;
    }

    private static String loweredVariableText(
            String query,
            String variables,
            String name,
            int variableStart,
            int definitionsEnd
    ) {
        String literal = jsonObjectFieldGraphqlLiteral(
                variables,
                name,
                variableDefinitionType(query, name),
                variableDefinitionDefaultValue(query, name)
        );
        if (literal.isEmpty() == false && variableStart >= definitionsEnd) {
            return literal;
        }
        return "$" + name;
    }

    private static String jsonObjectFieldGraphqlLiteral(
            String variablesJson,
            String name,
            String typeName,
            String defaultValue
    ) {
        int valueStart = jsonObjectFieldValueStart(variablesJson, name);
        if (valueStart < 0) {
            return defaultValue;
        }
        int valueEnd = skipJsonValue(variablesJson, valueStart);
        String namedType = variableNamedType(typeName);
        if ("ID".equals(namedType) && variablesJson.charAt(valueStart) == '"') {
            return jsonStringLiteralValue(variablesJson, valueStart, valueEnd);
        }
        if ("ArticleFilter".equals(namedType)) {
            return jsonInputValueLiteral(variablesJson, valueStart, valueEnd, false);
        }
        if ("ArticleOrderBy".equals(namedType)) {
            return jsonInputValueLiteral(variablesJson, valueStart, valueEnd, true);
        }
        return variablesJson.substring(valueStart, valueEnd);
    }

    private static String jsonInputValueLiteral(String json, int valueStart, int valueEnd, boolean stringValuesAreEnums) {
        valueStart = nextNonWhitespace(json, valueStart, valueEnd);
        while (valueEnd > valueStart && isWhitespace(json.charAt(valueEnd - 1))) {
            valueEnd--;
        }
        if (valueStart >= valueEnd) {
            return "";
        }
        char first = json.charAt(valueStart);
        if (first != '{' && first != '[') {
            if (first == '"' && stringValuesAreEnums) {
                return jsonStringLiteralValue(json, valueStart, valueEnd);
            }
            return json.substring(valueStart, valueEnd);
        }
        // Iterative scanner over the nested object/list literal: one forward cursor,
        // whole-token jumps for strings and scalars, canonical separators emitted in
        // place of the recursive descent (GAP-004 bounded traversal contract).
        String literal = "";
        int position = valueStart;
        while (position < valueEnd) {
            char c = json.charAt(position);
            if (c == '"') {
                int stringEnd = skipJsonString(json, position);
                int next = nextNonWhitespace(json, stringEnd, valueEnd);
                boolean objectFieldName = next < valueEnd && json.charAt(next) == ':';
                if (objectFieldName || stringValuesAreEnums) {
                    literal = literal + jsonStringLiteralValue(json, position, stringEnd);
                } else {
                    literal = literal + json.substring(position, stringEnd);
                }
                position = stringEnd;
            } else if (c == ',') {
                literal = literal + ", ";
                position++;
            } else if (c == ':') {
                literal = literal + ": ";
                position++;
            } else if (c == '{' || c == '}' || c == '[' || c == ']') {
                literal = literal + c;
                position++;
            } else if (isWhitespace(c)) {
                position++;
            } else {
                int scalarEnd = skipJsonValue(json, position);
                literal = literal + json.substring(position, scalarEnd);
                position = scalarEnd;
            }
        }
        return literal;
    }

    private static String normalizeJsonObject(String json) {
        String value = normalizeJson(json);
        if (value.equals("{}")) {
            return "";
        }
        return value;
    }

    private static String normalizeJson(String json) {
        return json == null ? "" : json.strip();
    }

    private static int variableDefinitionsStart(String query) {
        int first = nextNonWhitespace(query, 0, query.length());
        if (isOperationDefinitionAt(query, first) == false) {
            return query.length();
        }
        int selectionStart = query.indexOf("{", first);
        int parenStart = query.indexOf("(", first);
        if (parenStart < 0 || selectionStart < 0 || parenStart > selectionStart) {
            return query.length();
        }
        return parenStart;
    }

    private static int variableDefinitionsEnd(String query, int definitionsStart) {
        if (definitionsStart >= query.length()) {
            return query.length();
        }
        return matchingParen(query, definitionsStart);
    }

    private static String variableDefinitionType(String query, String variableName) {
        int position = variableDefinitionPosition(query, variableName);
        if (position < 0) {
            return "";
        }
        int definitionsEnd = variableDefinitionEntryEnd(query, position);
        int separator = query.indexOf(":", position);
        if (separator < 0 || separator > definitionsEnd) {
            return "";
        }
        int typeStart = nextNonWhitespace(query, separator + 1, definitionsEnd);
        int typeEnd = variableDefinitionTypeEnd(query, typeStart, definitionsEnd);
        return query.substring(typeStart, typeEnd).replace(" ", "");
    }

    private static int variableDefinitionTypeEnd(String query, int typeStart, int definitionsEnd) {
        int position = typeStart;
        int nested = 0;
        while (position < definitionsEnd) {
            char c = query.charAt(position);
            if (c == '[') {
                nested++;
            } else if (c == ']') {
                nested--;
            } else if ((c == '=' || c == ',') && nested == 0) {
                return position;
            }
            position++;
        }
        return position;
    }

    private static String variableNamedType(String typeName) {
        String result = typeName == null ? "" : typeName;
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

    private static boolean variableTypeIsList(String typeName) {
        String value = typeName == null ? "" : typeName;
        while (value.endsWith("!")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.startsWith("[");
    }

    private static boolean variableDefinitionRequired(String query, String variableName) {
        int position = variableDefinitionPosition(query, variableName);
        if (position < 0) {
            return false;
        }
        int definitionsEnd = variableDefinitionEntryEnd(query, position);
        int separator = query.indexOf(":", position);
        int valueSeparator = query.indexOf("=", position);
        int limit = valueSeparator >= 0 && valueSeparator < definitionsEnd ? valueSeparator : definitionsEnd;
        return query.indexOf("!", separator) >= 0 && query.indexOf("!", separator) < limit;
    }

    private static String variableDefinitionDefaultValue(String query, String variableName) {
        int position = variableDefinitionPosition(query, variableName);
        if (position < 0) {
            return "";
        }
        int definitionsEnd = variableDefinitionEntryEnd(query, position);
        int defaultStart = query.indexOf("=", position);
        if (defaultStart < 0 || defaultStart > definitionsEnd) {
            return "";
        }
        int valueStart = nextNonWhitespace(query, defaultStart + 1, definitionsEnd);
        int valueEnd = skipInputValue(query, valueStart, definitionsEnd);
        return query.substring(valueStart, valueEnd);
    }

    private static int variableDefinitionPosition(String query, String variableName) {
        int position = variableDefinitionsStart(query);
        int end = variableDefinitionsEnd(query, position);
        while (position < end) {
            if (query.charAt(position) == '$') {
                int nameStart = position + 1;
                int nameEnd = nameStart;
                while (nameEnd < end && isNamePart(query.charAt(nameEnd))) {
                    nameEnd++;
                }
                if (variableName.equals(query.substring(nameStart, nameEnd))) {
                    return position;
                }
                position = nameEnd;
            } else {
                position++;
            }
        }
        return -1;
    }

    private static int variableDefinitionEntryEnd(String query, int position) {
        int definitionsEnd = variableDefinitionsEnd(query, variableDefinitionsStart(query));
        int nested = 0;
        while (position < definitionsEnd) {
            char c = query.charAt(position);
            if (c == '[' || c == '(' || c == '{') {
                nested++;
            } else if ((c == ']' || c == ')' || c == '}') && nested > 0) {
                nested--;
            } else if (c == ',' && nested == 0) {
                return position;
            }
            position++;
        }
        return definitionsEnd;
    }

    private static boolean queryUsesVariable(String query, String variableName) {
        int definitionsEnd = variableDefinitionsEnd(query, variableDefinitionsStart(query));
        return indexOfVariable(query, variableName, definitionsEnd) >= 0;
    }

    private static int indexOfVariable(String query, String variableName, int start) {
        String marker = "$" + variableName;
        int position = query.indexOf(marker, start);
        while (position >= 0) {
            int after = position + marker.length();
            if (after >= query.length() || isNamePart(query.charAt(after)) == false) {
                return position;
            }
            position = query.indexOf(marker, after);
        }
        return -1;
    }

    private static boolean jsonObjectHasField(String json, String fieldName) {
        return jsonObjectFieldValueStart(json, fieldName) >= 0;
    }

    private static int jsonObjectFieldValueStart(String json, String fieldName) {
        if (json == null || json.isEmpty()) {
            return -1;
        }
        int end = skipJsonObject(json, 0);
        int position = nextNonWhitespace(json, 1, end - 1);
        while (position < end - 1) {
            int nameEnd = skipJsonString(json, position);
            String name = jsonStringLiteralValue(json, position, nameEnd);
            int separator = nextNonWhitespace(json, nameEnd, end - 1);
            int valueStart = nextNonWhitespace(json, separator + 1, end - 1);
            int valueEnd = skipJsonValue(json, valueStart);
            if (fieldName.equals(name)) {
                return valueStart;
            }
            position = nextNonWhitespace(json, valueEnd, end - 1);
            if (position < end - 1 && json.charAt(position) == ',') {
                position = nextNonWhitespace(json, position + 1, end - 1);
            }
        }
        return -1;
    }

    private static int skipJsonObject(String json, int position) {
        if (position >= json.length() || json.charAt(position) != '{') {
            return -1;
        }
        return skipJsonValue(json, position);
    }

    private static int skipJsonValue(String json, int position) {
        position = nextNonWhitespace(json, position, json.length());
        if (position >= json.length()) {
            return position;
        }
        char c = json.charAt(position);
        if (c == '"') {
            return skipJsonString(json, position);
        }
        if (c == '{') {
            String segment = json.substring(position);
            int depth = 0;
            boolean inString = false;
            boolean previousBackslash = false;
            int cursor = 0;
            while (cursor < segment.length()) {
                char current = segment.charAt(cursor);
                if (inString) {
                    if (current == '"' && previousBackslash == false) {
                        inString = false;
                    }
                    previousBackslash = current == '\\';
                } else if (current == '"') {
                    inString = true;
                    previousBackslash = false;
                } else if (current == '{') {
                    depth++;
                } else if (current == '}') {
                    depth--;
                    if (depth == 0) {
                        return position + cursor + 1;
                    }
                }
                cursor++;
            }
            return json.length();
        }
        if (c == '[') {
            String segment = json.substring(position);
            int depth = 0;
            boolean inString = false;
            boolean previousBackslash = false;
            int cursor = 0;
            while (cursor < segment.length()) {
                char current = segment.charAt(cursor);
                if (inString) {
                    if (current == '"' && previousBackslash == false) {
                        inString = false;
                    }
                    previousBackslash = current == '\\';
                } else if (current == '"') {
                    inString = true;
                    previousBackslash = false;
                } else if (current == '[') {
                    depth++;
                } else if (current == ']') {
                    depth--;
                    if (depth == 0) {
                        return position + cursor + 1;
                    }
                }
                cursor++;
            }
            return json.length();
        }
        while (position < json.length()
                && json.charAt(position) != ','
                && json.charAt(position) != '}'
                && json.charAt(position) != ']'
                && isWhitespace(json.charAt(position)) == false) {
            position++;
        }
        return position;
    }

    private static int skipJsonString(String json, int position) {
        if (position >= json.length()) {
            return position + 1;
        }
        String segment = json.substring(position + 1);
        boolean previousBackslash = json.charAt(position) == '\\';
        int cursor = 0;
        while (cursor < segment.length()) {
            char current = segment.charAt(cursor);
            if (current == '"' && previousBackslash == false) {
                return position + cursor + 2;
            }
            previousBackslash = current == '\\';
            cursor++;
        }
        return json.length();
    }

    private static String jsonStringLiteralValue(String json, int valueStart, int valueEnd) {
        if (valueStart >= valueEnd || json.charAt(valueStart) != '"') {
            return "";
        }
        return json.substring(valueStart + 1, valueEnd - 1);
    }

    private static String operationValidationError(String query) {
        int first = nextNonWhitespace(query, 0, query.length());
        if (first >= query.length()) {
            return "expected selection set";
        }
        if (isNameStart(query.charAt(first))) {
            int nameEnd = first + 1;
            while (nameEnd < query.length() && isNamePart(query.charAt(nameEnd))) {
                nameEnd++;
            }
            String operation = query.substring(first, nameEnd);
            if ("mutation".equals(operation) || "subscription".equals(operation)) {
                return "operation type '" + operation + "' is not supported";
            }
            if ("fragment".equals(operation)) {
                return "fragments are not supported";
            }
        }
        int selectionStart = query.indexOf("{");
        if (selectionStart < 0) {
            return "expected selection set";
        }
        int selectionEnd = selectionEnd(query, selectionStart);
        int trailing = nextNonWhitespace(query, selectionEnd + 1, query.length());
        if (trailing < query.length()) {
            if (isExactNameAt(query, "fragment", trailing)) {
                return trailingFragmentDefinitionValidationError(query, trailing);
            }
            if (isOperationDefinitionAt(query, trailing)) {
                return "multiple operations require operationName";
            }
            return "unexpected trailing GraphQL input";
        }
        return "";
    }

    private static String operationNameSelectionError(String query, String operationName) {
        String selectedName = operationName == null ? "" : operationName;
        if (selectedName.isEmpty()) {
            return "";
        }
        int selectedCount = 0;
        String selectedType = "";
        int position = nextNonWhitespace(query, 0, query.length());
        while (position < query.length()) {
            if (isExactNameAt(query, "fragment", position)) {
                int next = fragmentDefinitionEnd(query, position);
                if (next <= position) {
                    position = query.length();
                } else {
                    position = nextNonWhitespace(query, next, query.length());
                }
            } else {
                String operationType = operationDefinitionTypeAt(query, position);
                if (operationType.isEmpty()) {
                    position = query.length();
                } else {
                    String currentName = operationDefinitionName(query, position, operationType);
                    if (selectedName.equals(currentName)) {
                        selectedCount++;
                        selectedType = operationType;
                    }
                    int next = operationDefinitionEnd(query, position);
                    if (next <= position) {
                        position = query.length();
                    } else {
                        position = nextNonWhitespace(query, next, query.length());
                    }
                }
            }
        }
        if (selectedCount == 0) {
            return "operationName '" + selectedName + "' was not found";
        }
        if (selectedCount > 1) {
            return "operationName '" + selectedName + "' is ambiguous";
        }
        if ("mutation".equals(selectedType) || "subscription".equals(selectedType)) {
            return "operation type '" + selectedType + "' is not supported";
        }
        return "";
    }

    private static String selectNamedOperationSource(String query, String operationName) {
        String selectedName = operationName == null ? "" : operationName;
        if (selectedName.isEmpty()) {
            return query;
        }
        int position = nextNonWhitespace(query, 0, query.length());
        while (position < query.length()) {
            if (isExactNameAt(query, "fragment", position)) {
                int next = fragmentDefinitionEnd(query, position);
                if (next <= position) {
                    position = query.length();
                } else {
                    position = nextNonWhitespace(query, next, query.length());
                }
            } else {
                String operationType = operationDefinitionTypeAt(query, position);
                if (operationType.isEmpty()) {
                    position = query.length();
                } else {
                    int next = operationDefinitionEnd(query, position);
                    if (selectedName.equals(operationDefinitionName(query, position, operationType))) {
                        return query.substring(position, next) + topLevelFragmentDefinitions(query);
                    }
                    if (next <= position) {
                        position = query.length();
                    } else {
                        position = nextNonWhitespace(query, next, query.length());
                    }
                }
            }
        }
        return query;
    }

    private static String topLevelFragmentDefinitions(String query) {
        String fragments = "";
        int position = nextNonWhitespace(query, 0, query.length());
        while (position < query.length()) {
            if (isExactNameAt(query, "fragment", position)) {
                int next = fragmentDefinitionEnd(query, position);
                if (next <= position) {
                    position = query.length();
                } else {
                    fragments = fragments + " " + query.substring(position, next);
                    position = nextNonWhitespace(query, next, query.length());
                }
            } else {
                String operationType = operationDefinitionTypeAt(query, position);
                if (operationType.isEmpty()) {
                    position = query.length();
                } else {
                    int next = operationDefinitionEnd(query, position);
                    if (next <= position) {
                        position = query.length();
                    } else {
                        position = nextNonWhitespace(query, next, query.length());
                    }
                }
            }
        }
        return fragments;
    }

    private static String operationDefinitionTypeAt(String query, int position) {
        if (isExactNameAt(query, "query", position)) {
            return "query";
        }
        if (isExactNameAt(query, "mutation", position)) {
            return "mutation";
        }
        if (isExactNameAt(query, "subscription", position)) {
            return "subscription";
        }
        return "";
    }

    private static String operationDefinitionName(String query, int position, String operationType) {
        int nameStart = nextNonWhitespace(query, position + operationType.length(), query.length());
        if (nameStart < query.length() && isNameStart(query.charAt(nameStart))) {
            int nameEnd = nameStart + 1;
            while (nameEnd < query.length() && isNamePart(query.charAt(nameEnd))) {
                nameEnd++;
            }
            return query.substring(nameStart, nameEnd);
        }
        return "";
    }

    private static int operationDefinitionEnd(String query, int position) {
        int selectionStart = query.indexOf("{", position);
        if (selectionStart < 0) {
            return position;
        }
        int selectionEnd = selectionEnd(query, selectionStart);
        if (selectionEnd < selectionStart) {
            return position;
        }
        return selectionEnd + 1;
    }

    private static String trailingFragmentDefinitionValidationError(String query, int position) {
        while (position < query.length()) {
            if (isExactNameAt(query, "fragment", position) == false) {
                if (isOperationDefinitionAt(query, position)) {
                    return "multiple operations require operationName";
                }
                return "unexpected trailing GraphQL input";
            }
            int next = fragmentDefinitionEnd(query, position);
            if (next <= position) {
                return "expected fragment definition";
            }
            position = nextNonWhitespace(query, next, query.length());
        }
        return "";
    }

    private static String namedFragmentValidationError(String query) {
        int fragmentsStart = fragmentDefinitionsStart(query);
        boolean hasDefinitions = fragmentsStart < query.length();
        boolean hasNamedSpread = selectionContainsNamedFragmentSpread(query, query.indexOf("{"));
        if (hasDefinitions == false && hasNamedSpread == false) {
            return "";
        }
        if (hasDefinitions == false) {
            String unknown = firstUnknownNamedFragmentSpread(query);
            if (unknown.isEmpty() == false) {
                return "unknown fragment '" + unknown + "'";
            }
            return "";
        }
        int position = fragmentsStart;
        while (position < query.length()) {
            String name = fragmentDefinitionName(query, position);
            if (name.isEmpty()) {
                return "expected fragment name";
            }
            if (fragmentDefinitionCount(query, name) > 1) {
                return "duplicate fragment '" + name + "'";
            }
            String typeName = fragmentDefinitionType(query, position);
            if (typeName.isEmpty()) {
                return "expected fragment type condition";
            }
            if (isKnownInlineFragmentType(typeName) == false) {
                return "fragment type condition '" + typeName + "' is not defined";
            }
            position = nextNonWhitespace(query, fragmentDefinitionEnd(query, position), query.length());
        }
        String unknown = firstUnknownNamedFragmentSpread(query);
        if (unknown.isEmpty() == false) {
            return "unknown fragment '" + unknown + "'";
        }
        position = fragmentsStart;
        while (position < query.length()) {
            String name = fragmentDefinitionName(query, position);
            if (fragmentCycleDetected(query, name).isEmpty() == false) {
                return "fragment cycle detected at '" + name + "'";
            }
            if (fragmentReachableFromOperation(query, name, "") == false) {
                return "fragment '" + name + "' is never used";
            }
            position = nextNonWhitespace(query, fragmentDefinitionEnd(query, position), query.length());
        }
        return "";
    }

    private static int fragmentDefinitionsStart(String query) {
        int selectionStart = query.indexOf("{");
        if (selectionStart < 0) {
            return query.length();
        }
        int operationEnd = selectionEnd(query, selectionStart);
        int next = nextNonWhitespace(query, operationEnd + 1, query.length());
        if (next < query.length() && isExactNameAt(query, "fragment", next)) {
            return next;
        }
        return query.length();
    }

    private static int fragmentDefinitionEnd(String query, int position) {
        if (isExactNameAt(query, "fragment", position) == false) {
            return position;
        }
        int selectionStart = query.indexOf("{", position);
        if (selectionStart < 0) {
            return position;
        }
        return selectionEnd(query, selectionStart) + 1;
    }

    private static String fragmentDefinitionName(String query, int position) {
        if (isExactNameAt(query, "fragment", position) == false) {
            return "";
        }
        int nameStart = nextNonWhitespace(query, position + "fragment".length(), query.length());
        int nameEnd = nameStart;
        while (nameEnd < query.length() && isNamePart(query.charAt(nameEnd))) {
            nameEnd++;
        }
        if (nameStart == nameEnd || "on".equals(query.substring(nameStart, nameEnd))) {
            return "";
        }
        return query.substring(nameStart, nameEnd);
    }

    private static String fragmentDefinitionType(String query, int position) {
        int nameStart = nextNonWhitespace(query, position + "fragment".length(), query.length());
        int nameEnd = nameStart;
        while (nameEnd < query.length() && isNamePart(query.charAt(nameEnd))) {
            nameEnd++;
        }
        int onStart = nextNonWhitespace(query, nameEnd, query.length());
        if (isExactNameAt(query, "on", onStart) == false) {
            return "";
        }
        int typeStart = nextNonWhitespace(query, onStart + 2, query.length());
        int typeEnd = typeStart;
        while (typeEnd < query.length() && isNamePart(query.charAt(typeEnd))) {
            typeEnd++;
        }
        return query.substring(typeStart, typeEnd);
    }

    private static int fragmentDefinitionSelectionStart(String query, String fragmentName) {
        int position = fragmentDefinitionsStart(query);
        while (position < query.length()) {
            if (fragmentDefinitionName(query, position).equals(fragmentName)) {
                return query.indexOf("{", position);
            }
            position = nextNonWhitespace(query, fragmentDefinitionEnd(query, position), query.length());
        }
        return -1;
    }

    private static int fragmentDefinitionCount(String query, String fragmentName) {
        int count = 0;
        int position = fragmentDefinitionsStart(query);
        while (position < query.length()) {
            if (fragmentDefinitionName(query, position).equals(fragmentName)) {
                count++;
            }
            position = nextNonWhitespace(query, fragmentDefinitionEnd(query, position), query.length());
        }
        return count;
    }

    private static boolean selectionContainsNamedFragmentSpread(String query, int selectionStart) {
        if (selectionStart < 0) {
            return false;
        }
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            if (startsNamedFragmentSpread(query, position)) {
                return true;
            }
            position++;
        }
        return false;
    }

    private static String firstUnknownNamedFragmentSpread(String query) {
        int selectionStart = query.indexOf("{");
        String unknown = firstUnknownNamedFragmentSpreadInSelection(query, selectionStart);
        if (unknown.isEmpty() == false) {
            return unknown;
        }
        int position = fragmentDefinitionsStart(query);
        while (position < query.length()) {
            int fragmentSelection = query.indexOf("{", position);
            unknown = firstUnknownNamedFragmentSpreadInSelection(query, fragmentSelection);
            if (unknown.isEmpty() == false) {
                return unknown;
            }
            position = nextNonWhitespace(query, fragmentDefinitionEnd(query, position), query.length());
        }
        return "";
    }

    private static String firstUnknownNamedFragmentSpreadInSelection(String query, int selectionStart) {
        if (selectionStart < 0) {
            return "";
        }
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            if (startsNamedFragmentSpread(query, position)) {
                String name = namedFragmentSpreadName(query, position, selectionEnd);
                if (fragmentDefinitionCount(query, name) == 0) {
                    return name;
                }
            }
            position++;
        }
        return "";
    }

    private static String fragmentCycleDetected(String query, String fragmentName) {
        // GAP-004 iterative form of the previous recursive descent: compute the
        // reflexive-transitive closure of named-spread targets from fragmentName with
        // a "|Name|" string worklist, then report the first closure member that can
        // reach itself again through at least one spread edge. A cycle is reachable
        // from fragmentName exactly when such a member exists.
        String visited = "|" + fragmentName + "|";
        String pending = fragmentDirectSpreadTargets(query, fragmentName);
        while (pending.isEmpty() == false) {
            String current = firstPathFragment(pending);
            pending = restOfPathFragments(pending);
            if (pathContainsFragment(visited, current) == false) {
                visited = visited + "|" + current + "|";
                pending = pending + fragmentDirectSpreadTargets(query, current);
            }
        }
        String remaining = visited;
        while (remaining.isEmpty() == false) {
            String candidate = firstPathFragment(remaining);
            remaining = restOfPathFragments(remaining);
            if (fragmentSpreadsReachFragment(query, candidate, candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private static boolean fragmentSpreadsReachFragment(String query, String startFragment, String targetFragment) {
        String visited = "";
        String pending = fragmentDirectSpreadTargets(query, startFragment);
        while (pending.isEmpty() == false) {
            String current = firstPathFragment(pending);
            pending = restOfPathFragments(pending);
            if (current.equals(targetFragment)) {
                return true;
            }
            if (pathContainsFragment(visited, current) == false) {
                visited = visited + "|" + current + "|";
                pending = pending + fragmentDirectSpreadTargets(query, current);
            }
        }
        return false;
    }

    private static String fragmentDirectSpreadTargets(String query, String fragmentName) {
        int selectionStart = fragmentDefinitionSelectionStart(query, fragmentName);
        if (selectionStart < 0) {
            return "";
        }
        int selectionEnd = selectionEnd(query, selectionStart);
        String targets = "";
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            if (startsNamedFragmentSpread(query, position)) {
                targets = targets + "|" + namedFragmentSpreadName(query, position, selectionEnd) + "|";
            }
            position++;
        }
        return targets;
    }

    private static String firstPathFragment(String path) {
        if (path.isEmpty()) {
            return "";
        }
        return path.substring(1, path.indexOf("|", 1));
    }

    private static String restOfPathFragments(String path) {
        if (path.isEmpty()) {
            return path;
        }
        return path.substring(path.indexOf("|", 1) + 1);
    }

    private static int traversalFrameNumber(String token) {
        // Decodes one non-negative cursor/bound value stored in a string-encoded
        // traversal frame (GAP-004 scalar traversal state; frames only ever store
        // values produced by in-bounds scan positions, so digits-only is exact).
        int value = 0;
        int cursor = 0;
        while (cursor < token.length()) {
            value = value * 10 + token.charAt(cursor) - '0';
            cursor++;
        }
        return value;
    }

    private static boolean fragmentReachableFromOperation(String query, String fragmentName, String visited) {
        int selectionStart = query.indexOf("{");
        return fragmentReachableFromSelection(query, selectionStart, fragmentName, visited);
    }

    private static boolean fragmentReachableFromSelection(
            String query,
            int selectionStart,
            String fragmentName,
            String visited
    ) {
        // GAP-004 iterative form of the previous recursive descent: breadth-first
        // worklist over runtime-directive-included spread targets, tracked as
        // "|Name|" path strings. The visited parameter seeds the set of fragments
        // whose definitions must not be expanded again.
        if (selectionStart < 0) {
            return false;
        }
        String seen = visited;
        String pending = includedNamedSpreadTargets(query, selectionStart);
        while (pending.isEmpty() == false) {
            String current = firstPathFragment(pending);
            pending = restOfPathFragments(pending);
            if (current.equals(fragmentName)) {
                return true;
            }
            if (pathContainsFragment(seen, current) == false) {
                seen = seen + "|" + current + "|";
                int nestedSelection = fragmentDefinitionSelectionStart(query, current);
                if (nestedSelection >= 0) {
                    pending = pending + includedNamedSpreadTargets(query, nestedSelection);
                }
            }
        }
        return false;
    }

    private static String includedNamedSpreadTargets(String query, int selectionStart) {
        if (selectionStart < 0) {
            return "";
        }
        int selectionEnd = selectionEnd(query, selectionStart);
        String targets = "";
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            if (startsNamedFragmentSpread(query, position)) {
                int spreadNameEnd = namedFragmentSpreadNameEnd(query, position, selectionEnd);
                int directiveEnd = afterRuntimeDirectives(query, spreadNameEnd, selectionEnd);
                if (runtimeDirectivesInclude(query, spreadNameEnd, directiveEnd)) {
                    targets = targets + "|" + namedFragmentSpreadName(query, position, selectionEnd) + "|";
                }
            }
            position++;
        }
        return targets;
    }

    private static boolean pathContainsFragment(String path, String fragmentName) {
        return path.indexOf("|" + fragmentName + "|") >= 0;
    }

    private static String expandNamedFragments(String query) {
        if (query.indexOf("...") < 0) {
            return query;
        }
        int selectionStart = query.indexOf("{");
        if (selectionStart < 0) {
            return query;
        }
        // GAP-004 iterative form of the previous recursive descent: repeat
        // single-level spread-splicing passes over the operation selection until no
        // named spread remains. namedFragmentValidationError rejects fragment cycles
        // and unknown spreads before expansion runs, so the spread graph is acyclic
        // and one pass per fragment definition suffices; the pass budget also bounds
        // spread-like text that can never expand (for example "..." inside argument
        // strings). Fragment definitions stay in the working text for lookup and are
        // stripped at the end, exactly as before.
        String current = query;
        int remainingPasses = fragmentDefinitionTotal(query) + 1;
        while (remainingPasses > 0 && selectionContainsNamedFragmentSpread(current, current.indexOf("{"))) {
            int passSelectionStart = current.indexOf("{");
            current = current.substring(0, passSelectionStart)
                    + expandNamedFragmentsInSelection(current, passSelectionStart, "Query")
                    + current.substring(selectionEnd(current, passSelectionStart) + 1);
            remainingPasses--;
        }
        return current.substring(0, fragmentDefinitionsStart(current));
    }

    private static int fragmentDefinitionTotal(String query) {
        int count = 0;
        int position = fragmentDefinitionsStart(query);
        while (position < query.length()) {
            int definitionEnd = fragmentDefinitionEnd(query, position);
            if (definitionEnd <= position) {
                return count;
            }
            count++;
            position = nextNonWhitespace(query, definitionEnd, query.length());
        }
        return count;
    }

    private static String expandNamedFragmentsInSelection(String query, int selectionStart, String parentType) {
        // One single-level expansion pass: a forward scan with a "|"-delimited
        // string stack of enclosing types. Included spreads whose fragment type
        // condition matches the current type are spliced as raw fragment selection
        // text (rescanned by the bounded fixpoint loop above); all other spread
        // sites are dropped. Field and inline-fragment sub-selections are entered
        // in place, and their closing "}" is emitted by the "}" branch exactly
        // where the recursion emitted it.
        int selectionEnd = selectionEnd(query, selectionStart);
        String result = "{";
        String typeStack = "";
        String currentType = parentType;
        int position = selectionStart + 1;
        int nestedDepth = 0;
        int argumentDepth = 0;
        while (position < selectionEnd) {
            char c = query.charAt(position);
            if (nestedDepth == 0 && argumentDepth == 0 && startsNamedFragmentSpread(query, position)) {
                String name = namedFragmentSpreadName(query, position, selectionEnd);
                int nameEnd = namedFragmentSpreadNameEnd(query, position, selectionEnd);
                int directiveEnd = afterRuntimeDirectives(query, nameEnd, selectionEnd);
                if (runtimeDirectivesInclude(query, nameEnd, directiveEnd)
                        && currentType.equals(fragmentDefinitionType(query, fragmentDefinitionPosition(query, name)))) {
                    int fragmentSelectionStart = fragmentDefinitionSelectionStart(query, name);
                    result = result + query.substring(
                            fragmentSelectionStart + 1,
                            selectionEnd(query, fragmentSelectionStart)
                    );
                }
                position = directiveEnd;
            } else if (nestedDepth == 0 && argumentDepth == 0 && startsInlineFragment(query, position)) {
                int typeStart = inlineFragmentTypeStart(query, position);
                int typeEnd = typeStart;
                while (typeEnd < selectionEnd && isNamePart(query.charAt(typeEnd))) {
                    typeEnd++;
                }
                int directiveEnd = afterRuntimeDirectives(query, typeEnd, selectionEnd);
                int fragmentSelectionStart = nextNonWhitespace(query, directiveEnd, selectionEnd);
                result = result + query.substring(position, fragmentSelectionStart) + "{";
                typeStack = currentType + "|" + typeStack;
                position = fragmentSelectionStart + 1;
            } else if (c == '{') {
                nestedDepth++;
                position++;
            } else if (c == '}') {
                if (nestedDepth > 0) {
                    nestedDepth--;
                } else if (typeStack.isEmpty() == false) {
                    result = result + "}";
                    int frameEnd = typeStack.indexOf("|");
                    currentType = typeStack.substring(0, frameEnd);
                    typeStack = typeStack.substring(frameEnd + 1);
                }
                position++;
            } else if (nestedDepth == 0 && c == '(') {
                argumentDepth++;
                result = result + c;
                position++;
            } else if (nestedDepth == 0 && c == ')') {
                if (argumentDepth > 0) {
                    argumentDepth--;
                }
                result = result + c;
                position++;
            } else if (nestedDepth == 0 && argumentDepth == 0 && isNameStart(c)) {
                int fieldStart = position;
                while (position < selectionEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                String fieldName = fieldName(query, fieldStart, position, selectionEnd);
                int fieldNameEnd = fieldNameEnd(query, position, selectionEnd);
                int afterArguments = fieldAfterArguments(query, fieldNameEnd, selectionEnd);
                int next = nextNonWhitespace(query, afterArguments, selectionEnd);
                if (next < selectionEnd && query.charAt(next) == '{') {
                    result = result + query.substring(fieldStart, next) + "{";
                    typeStack = currentType + "|" + typeStack;
                    currentType = childType(currentType, fieldName);
                    position = next + 1;
                } else {
                    int fieldEnd = topLevelFieldEnd(query, fieldNameEnd, selectionEnd);
                    result = result + query.substring(fieldStart, fieldEnd);
                    position = fieldEnd;
                }
            } else {
                result = result + c;
                position++;
            }
        }
        return result + "}";
    }

    private static int fragmentDefinitionPosition(String query, String fragmentName) {
        int position = fragmentDefinitionsStart(query);
        while (position < query.length()) {
            if (fragmentDefinitionName(query, position).equals(fragmentName)) {
                return position;
            }
            position = nextNonWhitespace(query, fragmentDefinitionEnd(query, position), query.length());
        }
        return query.length();
    }

    private static boolean startsNamedFragmentSpread(String query, int position) {
        if (position + 3 > query.length() || query.startsWith("...", position) == false) {
            return false;
        }
        int nameStart = nextNonWhitespace(query, position + 3, query.length());
        return isExactNameAt(query, "on", nameStart) == false
                && nameStart < query.length()
                && isNameStart(query.charAt(nameStart));
    }

    private static String namedFragmentSpreadName(String query, int position, int selectionEnd) {
        int nameStart = nextNonWhitespace(query, position + 3, selectionEnd);
        int nameEnd = nameStart;
        while (nameEnd < selectionEnd && isNamePart(query.charAt(nameEnd))) {
            nameEnd++;
        }
        return query.substring(nameStart, nameEnd);
    }

    private static int namedFragmentSpreadNameEnd(String query, int position, int selectionEnd) {
        int nameEnd = nextNonWhitespace(query, position + 3, selectionEnd);
        while (nameEnd < selectionEnd && isNamePart(query.charAt(nameEnd))) {
            nameEnd++;
        }
        return nameEnd;
    }

    private static String runtimeDirectiveValidationError(String query) {
        int firstSelection = query.indexOf("{");
        int firstDirective = query.indexOf("@");
        if (firstDirective >= 0 && firstSelection >= 0 && firstDirective < firstSelection) {
            return "operation directives are not supported in SQL mode";
        }
        int directiveStart = query.indexOf("@");
        while (directiveStart >= 0) {
            int nameStart = directiveStart + 1;
            int nameEnd = nameStart;
            while (nameEnd < query.length() && isNamePart(query.charAt(nameEnd))) {
                nameEnd++;
            }
            if (nameStart == nameEnd) {
                return "expected directive name";
            }
            String name = query.substring(nameStart, nameEnd);
            if ("include".equals(name) == false && "skip".equals(name) == false) {
                return "unsupported directive '@" + name + "'";
            }
            int argumentsStart = nextNonWhitespace(query, nameEnd, query.length());
            if (argumentsStart >= query.length() || query.charAt(argumentsStart) != '(') {
                return "directive '@" + name + "' requires argument 'if'";
            }
            int argumentsEnd = matchingParen(query, argumentsStart);
            if (argumentsEnd >= query.length()) {
                return "expected ')' after directive arguments";
            }
            String argumentError = directiveIfArgumentValidationError(query, name, argumentsStart, argumentsEnd);
            if (argumentError.isEmpty() == false) {
                return argumentError;
            }
            directiveStart = query.indexOf("@", argumentsEnd + 1);
        }
        return "";
    }

    private static String directiveIfArgumentValidationError(
            String query,
            String directiveName,
            int argumentsStart,
            int argumentsEnd
    ) {
        int position = argumentsStart + 1;
        int ifCount = 0;
        while (position < argumentsEnd) {
            char c = query.charAt(position);
            if (isNameStart(c)) {
                int nameStart = position;
                while (position < argumentsEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                String name = query.substring(nameStart, position);
                if ("if".equals(name) == false) {
                    return "unsupported argument '" + name + "' on directive '@" + directiveName + "'";
                }
                ifCount++;
                int separator = nextNonWhitespace(query, position, argumentsEnd);
                if (separator >= argumentsEnd || query.charAt(separator) != ':') {
                    return "expected ':' after directive argument name";
                }
                int valueStart = nextNonWhitespace(query, separator + 1, argumentsEnd);
                if (isExactNameAt(query, "true", valueStart)) {
                    position = valueStart + 4;
                } else if (isExactNameAt(query, "false", valueStart)) {
                    position = valueStart + 5;
                } else {
                    return "directive '@" + directiveName + "' argument 'if' must be a Boolean literal in SQL mode";
                }
            } else {
                position++;
            }
        }
        if (ifCount == 0) {
            return "directive '@" + directiveName + "' requires argument 'if'";
        }
        if (ifCount > 1) {
            return "duplicate directive argument 'if'";
        }
        return "";
    }

    private static String pruneRuntimeDirectives(String query) {
        if (query.indexOf("@") < 0) {
            return query;
        }
        int selectionStart = query.indexOf("{");
        if (selectionStart < 0) {
            return query;
        }
        return query.substring(0, selectionStart)
                + pruneRuntimeDirectivesInSelection(query, selectionStart)
                + query.substring(selectionEnd(query, selectionStart) + 1);
    }

    private static String pruneRuntimeDirectivesInSelection(String query, int selectionStart) {
        // GAP-004 iterative form of the previous recursive descent: one forward scan
        // over the whole selection text. Included nested selections are entered in
        // place (emit the prefix and "{", keep scanning); their closing "}" is then
        // emitted by the regular "}" branch, exactly where the recursion emitted it.
        // Excluded regions are skipped wholesale, as before.
        int selectionEnd = selectionEnd(query, selectionStart);
        String result = "{";
        int position = selectionStart + 1;
        int nestedDepth = 0;
        int argumentDepth = 0;
        while (position < selectionEnd) {
            char c = query.charAt(position);
            if (nestedDepth == 0 && argumentDepth == 0 && startsInlineFragment(query, position)) {
                int typeStart = inlineFragmentTypeStart(query, position);
                int typeEnd = typeStart;
                while (typeEnd < selectionEnd && isNamePart(query.charAt(typeEnd))) {
                    typeEnd++;
                }
                int directiveEnd = afterRuntimeDirectives(query, typeEnd, selectionEnd);
                int fragmentSelectionStart = nextNonWhitespace(query, directiveEnd, selectionEnd);
                if (runtimeDirectivesInclude(query, typeEnd, directiveEnd)) {
                    result = result + query.substring(position, typeEnd) + "{";
                    position = fragmentSelectionStart + 1;
                } else {
                    position = selectionEnd(query, fragmentSelectionStart) + 1;
                }
            } else if (c == '{') {
                nestedDepth++;
                result = result + c;
                position++;
            } else if (c == '}') {
                if (nestedDepth > 0) {
                    nestedDepth--;
                }
                result = result + c;
                position++;
            } else if (nestedDepth == 0 && c == '(') {
                argumentDepth++;
                result = result + c;
                position++;
            } else if (nestedDepth == 0 && c == ')') {
                if (argumentDepth > 0) {
                    argumentDepth--;
                }
                result = result + c;
                position++;
            } else if (nestedDepth == 0 && argumentDepth == 0 && isNameStart(c)) {
                int fieldStart = position;
                while (position < selectionEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                int fieldNameEnd = fieldNameEnd(query, position, selectionEnd);
                int afterArguments = fieldAfterArguments(query, fieldNameEnd, selectionEnd);
                int directiveEnd = afterRuntimeDirectives(query, afterArguments, selectionEnd);
                int next = nextNonWhitespace(query, directiveEnd, selectionEnd);
                if (runtimeDirectivesInclude(query, afterArguments, directiveEnd)) {
                    result = result + query.substring(fieldStart, afterArguments);
                    if (next < selectionEnd && query.charAt(next) == '{') {
                        result = result + "{";
                        position = next + 1;
                    } else {
                        result = result + " ";
                        position = topLevelFieldEnd(query, directiveEnd, selectionEnd);
                    }
                } else {
                    if (next < selectionEnd && query.charAt(next) == '{') {
                        position = selectionEnd(query, next) + 1;
                    } else {
                        position = topLevelFieldEnd(query, directiveEnd, selectionEnd);
                    }
                }
            } else {
                result = result + c;
                position++;
            }
        }
        return result + "}";
    }

    private static int afterRuntimeDirectives(String query, int position, int selectionEnd) {
        int next = nextNonWhitespace(query, position, selectionEnd);
        while (next < selectionEnd && query.charAt(next) == '@') {
            int nameEnd = next + 1;
            while (nameEnd < selectionEnd && isNamePart(query.charAt(nameEnd))) {
                nameEnd++;
            }
            int argumentsStart = nextNonWhitespace(query, nameEnd, selectionEnd);
            int argumentsEnd = matchingParen(query, argumentsStart);
            next = nextNonWhitespace(query, argumentsEnd + 1, selectionEnd);
        }
        return next;
    }

    private static boolean runtimeDirectivesInclude(String query, int position, int directivesEnd) {
        int next = nextNonWhitespace(query, position, directivesEnd);
        boolean included = true;
        while (next < directivesEnd && query.charAt(next) == '@') {
            int nameStart = next + 1;
            int nameEnd = nameStart;
            while (nameEnd < directivesEnd && isNamePart(query.charAt(nameEnd))) {
                nameEnd++;
            }
            String name = query.substring(nameStart, nameEnd);
            int argumentsStart = nextNonWhitespace(query, nameEnd, directivesEnd);
            int argumentsEnd = matchingParen(query, argumentsStart);
            boolean value = directiveIfBooleanValue(query, argumentsStart, argumentsEnd);
            if ("include".equals(name) && value == false) {
                included = false;
            }
            if ("skip".equals(name) && value) {
                included = false;
            }
            next = nextNonWhitespace(query, argumentsEnd + 1, directivesEnd);
        }
        return included;
    }

    private static boolean directiveIfBooleanValue(String query, int argumentsStart, int argumentsEnd) {
        int ifName = indexOfName(query, "if", argumentsStart);
        int separator = query.indexOf(":", ifName);
        int valueStart = nextNonWhitespace(query, separator + 1, argumentsEnd);
        return isExactNameAt(query, "true", valueStart);
    }

    private static String inlineFragmentValidationError(String query) {
        int position = query.indexOf("...");
        while (position >= 0) {
            int afterEllipsis = nextNonWhitespace(query, position + 3, query.length());
            if (isExactNameAt(query, "on", afterEllipsis) == false) {
                return "fragments are not supported";
            }
            int typeStart = nextNonWhitespace(query, afterEllipsis + 2, query.length());
            int typeEnd = typeStart;
            while (typeEnd < query.length() && isNamePart(query.charAt(typeEnd))) {
                typeEnd++;
            }
            if (typeStart == typeEnd) {
                return "expected inline fragment type condition";
            }
            String typeName = query.substring(typeStart, typeEnd);
            if (isKnownInlineFragmentType(typeName) == false) {
                return "fragment type condition '" + typeName + "' is not defined";
            }
            int selectionStart = nextNonWhitespace(query, typeEnd, query.length());
            if (selectionStart >= query.length() || query.charAt(selectionStart) != '{') {
                return "inline fragment requires a selection set";
            }
            position = query.indexOf("...", position + 3);
        }
        return "";
    }

    private static boolean isKnownInlineFragmentType(String typeName) {
        return "Article".equals(typeName)
                || "User".equals(typeName)
                || "Comment".equals(typeName)
                || "ArticleConnection".equals(typeName)
                || "ArticleEdge".equals(typeName)
                || "CommentConnection".equals(typeName)
                || "CommentEdge".equals(typeName)
                || "PageInfo".equals(typeName);
    }

    private static String expandInlineFragments(String query) {
        if (query.indexOf("...") < 0) {
            return query;
        }
        int selectionStart = query.indexOf("{");
        if (selectionStart < 0) {
            return query;
        }
        return query.substring(0, selectionStart)
                + expandInlineFragmentsInSelection(query, selectionStart, "Query")
                + query.substring(selectionEnd(query, selectionStart) + 1);
    }

    private static String expandInlineFragmentsInSelection(String query, int selectionStart, String parentType) {
        // GAP-004 iterative form of the previous recursive descent: one forward scan
        // with a "|"-delimited string stack of enclosing types. Field sub-selections
        // are entered in place ("F" frame, braces preserved); matching inline
        // fragments are spliced in place ("I" frame, braces stripped); non-matching
        // inline fragments are skipped wholesale, exactly as before.
        int selectionEnd = selectionEnd(query, selectionStart);
        String result = "{";
        String typeStack = "";
        String currentType = parentType;
        int position = selectionStart + 1;
        int nestedDepth = 0;
        int argumentDepth = 0;
        while (position < selectionEnd) {
            char c = query.charAt(position);
            if (nestedDepth == 0 && argumentDepth == 0 && startsInlineFragment(query, position)) {
                int typeStart = inlineFragmentTypeStart(query, position);
                int typeEnd = typeStart;
                while (typeEnd < selectionEnd && isNamePart(query.charAt(typeEnd))) {
                    typeEnd++;
                }
                String typeName = query.substring(typeStart, typeEnd);
                int fragmentSelectionStart = nextNonWhitespace(query, typeEnd, selectionEnd);
                if (typeName.equals(currentType)) {
                    typeStack = "I" + currentType + "|" + typeStack;
                    position = fragmentSelectionStart + 1;
                } else {
                    position = selectionEnd(query, fragmentSelectionStart) + 1;
                }
            } else if (c == '{') {
                nestedDepth++;
                position++;
            } else if (c == '}') {
                if (nestedDepth > 0) {
                    nestedDepth--;
                } else if (typeStack.isEmpty() == false) {
                    if (typeStack.charAt(0) == 'F') {
                        result = result + "}";
                    }
                    int frameEnd = typeStack.indexOf("|");
                    currentType = typeStack.substring(1, frameEnd);
                    typeStack = typeStack.substring(frameEnd + 1);
                }
                position++;
            } else if (nestedDepth == 0 && c == '(') {
                argumentDepth++;
                result = result + c;
                position++;
            } else if (nestedDepth == 0 && c == ')') {
                if (argumentDepth > 0) {
                    argumentDepth--;
                }
                result = result + c;
                position++;
            } else if (nestedDepth == 0 && argumentDepth == 0 && isNameStart(c)) {
                int fieldStart = position;
                while (position < selectionEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                String fieldName = fieldName(query, fieldStart, position, selectionEnd);
                int fieldNameEnd = fieldNameEnd(query, position, selectionEnd);
                int afterArguments = fieldAfterArguments(query, fieldNameEnd, selectionEnd);
                int next = nextNonWhitespace(query, afterArguments, selectionEnd);
                if (next < selectionEnd && query.charAt(next) == '{') {
                    result = result + query.substring(fieldStart, next) + "{";
                    typeStack = "F" + currentType + "|" + typeStack;
                    currentType = childType(currentType, fieldName);
                    position = next + 1;
                } else {
                    int fieldEnd = topLevelFieldEnd(query, fieldNameEnd, selectionEnd);
                    result = result + query.substring(fieldStart, fieldEnd);
                    position = fieldEnd;
                }
            } else {
                result = result + c;
                position++;
            }
        }
        return result + "}";
    }

    private static boolean startsInlineFragment(String query, int position) {
        return position + 3 <= query.length()
                && query.startsWith("...", position)
                && isExactNameAt(query, "on", nextNonWhitespace(query, position + 3, query.length()));
    }

    private static int inlineFragmentTypeStart(String query, int position) {
        int onStart = nextNonWhitespace(query, position + 3, query.length());
        return nextNonWhitespace(query, onStart + 2, query.length());
    }

    private static int fieldAfterArguments(String query, int position, int selectionEnd) {
        int next = nextNonWhitespace(query, position, selectionEnd);
        if (next >= selectionEnd || query.charAt(next) != '(') {
            return position;
        }
        int depth = 1;
        next++;
        while (next < selectionEnd && depth > 0) {
            char c = query.charAt(next);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            next++;
        }
        return next;
    }

    private static String childType(String parentType, String fieldName) {
        if ("Query".equals(parentType) && "article".equals(fieldName)) {
            return "Article";
        }
        if ("Query".equals(parentType) && "articles".equals(fieldName)) {
            return "ArticleConnection";
        }
        if ("Article".equals(parentType) && "author".equals(fieldName)) {
            return "User";
        }
        if ("Article".equals(parentType) && "comments".equals(fieldName)) {
            return "CommentConnection";
        }
        if ("ArticleConnection".equals(parentType) && "edges".equals(fieldName)) {
            return "ArticleEdge";
        }
        if ("ArticleConnection".equals(parentType) && "pageInfo".equals(fieldName)) {
            return "PageInfo";
        }
        if ("ArticleEdge".equals(parentType) && "node".equals(fieldName)) {
            return "Article";
        }
        if ("CommentConnection".equals(parentType) && "edges".equals(fieldName)) {
            return "CommentEdge";
        }
        if ("CommentConnection".equals(parentType) && "pageInfo".equals(fieldName)) {
            return "PageInfo";
        }
        if ("CommentEdge".equals(parentType) && "node".equals(fieldName)) {
            return "Comment";
        }
        if ("Comment".equals(parentType) && "author".equals(fieldName)) {
            return "User";
        }
        return "";
    }

    private static boolean isOperationDefinitionAt(String query, int position) {
        return isExactNameAt(query, "query", position)
                || isExactNameAt(query, "mutation", position)
                || isExactNameAt(query, "subscription", position);
    }

    private static boolean isExactNameAt(String query, String name, int position) {
        if (position + name.length() > query.length() || query.startsWith(name, position) == false) {
            return false;
        }
        return isNameAt(query, name, position);
    }

    private static String rootValidationError(String query, boolean enableIntrospection) {
        int selectionStart = query.indexOf("{");
        if (selectionStart < 0) {
            return "expected selection set";
        }
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        int nestedDepth = 0;
        int argumentDepth = 0;
        int rootCount = 0;
        String rootName = "";
        while (position < selectionEnd) {
            char c = query.charAt(position);
            if (c == '{') {
                nestedDepth++;
                position++;
            } else if (c == '}') {
                if (nestedDepth > 0) {
                    nestedDepth--;
                }
                position++;
            } else if (nestedDepth == 0 && c == '(') {
                argumentDepth++;
                position++;
            } else if (nestedDepth == 0 && c == ')') {
                if (argumentDepth > 0) {
                    argumentDepth--;
                }
                position++;
            } else if (nestedDepth == 0 && argumentDepth == 0 && isNameStart(c)) {
                int nameStart = position;
                while (position < selectionEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                String name = fieldName(query, nameStart, position, selectionEnd);
                position = fieldNameEnd(query, position, selectionEnd);
                if (rootCount == 0) {
                    rootName = name;
                }
                rootCount++;
            } else {
                position++;
            }
        }
        if (rootCount == 0) {
            return "selection set must contain at least one field";
        }
        if (rootCount > 1) {
            return "exactly one root field is supported";
        }
        if (("__schema".equals(rootName) || "__type".equals(rootName)) && enableIntrospection == false) {
            return "introspection is disabled for root field '" + rootName + "'";
        }
        if ("article".equals(rootName) == false
                && "articles".equals(rootName) == false
                && "__schema".equals(rootName) == false
                && "__type".equals(rootName) == false) {
            return "unsupported root field '" + rootName + "'";
        }
        return "";
    }

    private static String introspectionValidationError(String query, String rootName) {
        int rootStart = indexOfName(query, rootName, 0);
        if (rootStart < 0) {
            return "unsupported introspection root field '" + rootName + "'";
        }
        int rootNameEnd = rootStart + rootName.length();
        int rootArgumentsStart = nextNonWhitespace(query, rootNameEnd, query.length());
        if ("__schema".equals(rootName) && rootArgumentsStart < query.length() && query.charAt(rootArgumentsStart) == '(') {
            return "field '__schema' does not support arguments";
        }
        if ("__type".equals(rootName)) {
            String typeArgumentError = typeNameArgumentValidationError(query, rootNameEnd);
            if (typeArgumentError.isEmpty() == false) {
                return typeArgumentError;
            }
        }
        int rootSelectionStart = rootFieldSelectionStart(query, rootStart);
        if (rootSelectionStart < 0) {
            return "field '" + rootName + "' requires a selection set";
        }
        if ("__schema".equals(rootName)) {
            return schemaSelectionValidationError(query, rootSelectionStart);
        }
        return typeSelectionValidationError(query, rootSelectionStart);
    }

    private static String typeNameArgumentValidationError(String query, int rootNameEnd) {
        int argumentsStart = nextNonWhitespace(query, rootNameEnd, query.length());
        if (argumentsStart >= query.length() || query.charAt(argumentsStart) != '(') {
            return "field '__type' requires argument 'name'";
        }
        int argumentsEnd = matchingParen(query, argumentsStart);
        if (argumentsEnd >= query.length()) {
            return "expected ')' after __type arguments";
        }
        int nameArgument = topLevelArgumentNamePosition(query, "name", argumentsStart, argumentsEnd);
        if (nameArgument < 0) {
            return "field '__type' requires argument 'name'";
        }
        int position = argumentsStart + 1;
        int argumentCount = 0;
        while (position < argumentsEnd) {
            if (isNameStart(query.charAt(position))) {
                argumentCount++;
                while (position < argumentsEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                position = skipArgumentValue(query, position, argumentsEnd);
            } else {
                position++;
            }
        }
        if (argumentCount != 1) {
            return "field '__type' supports only argument 'name'";
        }
        int separator = query.indexOf(":", nameArgument);
        if (separator < 0 || separator > argumentsEnd) {
            return "expected ':' after __type argument name";
        }
        int valueStart = nextNonWhitespace(query, separator + 1, argumentsEnd);
        if (valueStart >= argumentsEnd) {
            return "argument '__type.name' must be a string";
        }
        if (query.charAt(valueStart) == '"') {
            return "";
        }
        if (isNameStart(query.charAt(valueStart))) {
            return "";
        }
        return "argument '__type.name' must be a string";
    }

    private static String schemaSelectionValidationError(String query, int selectionStart) {
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            int fieldStart = nextTopLevelFieldStart(query, position, selectionEnd);
            if (fieldStart < 0) {
                return "";
            }
            int fieldEnd = fieldTokenEnd(query, fieldStart, selectionEnd);
            String fieldName = fieldName(query, fieldStart, fieldEnd, selectionEnd);
            int fieldNameEnd = fieldNameEnd(query, fieldEnd, selectionEnd);
            if (fieldHasArguments(query, fieldNameEnd, selectionEnd)) {
                return "field '__Schema." + fieldName + "' does not support arguments";
            }
            int fieldSelectionStart = fieldSelectionStart(query, fieldNameEnd, selectionEnd);
            if ("queryType".equals(fieldName)
                    || "mutationType".equals(fieldName)
                    || "subscriptionType".equals(fieldName)
                    || "types".equals(fieldName)) {
                if (fieldSelectionStart < 0) {
                    return "field '__Schema." + fieldName + "' requires a selection set";
                }
                String typeRefError = typeReferenceSelectionValidationError(query, fieldSelectionStart);
                if (typeRefError.isEmpty() == false) {
                    return typeRefError;
                }
            } else if ("description".equals(fieldName)) {
                if (fieldSelectionStart >= 0) {
                    return "field '__Schema.description' must not have a selection set";
                }
            } else if ("directives".equals(fieldName)) {
                if (fieldSelectionStart < 0) {
                    return "field '__Schema.directives' requires a selection set";
                }
                String directiveError = introspectionDirectiveSelectionValidationError(query, fieldSelectionStart);
                if (directiveError.isEmpty() == false) {
                    return directiveError;
                }
            } else {
                return "unsupported __Schema field '" + fieldName + "'";
            }
            position = topLevelFieldEnd(query, fieldNameEnd, selectionEnd);
        }
        return "";
    }

    private static String typeSelectionValidationError(String query, int selectionStart) {
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            int fieldStart = nextTopLevelFieldStart(query, position, selectionEnd);
            if (fieldStart < 0) {
                return "";
            }
            int fieldEnd = fieldTokenEnd(query, fieldStart, selectionEnd);
            String fieldName = fieldName(query, fieldStart, fieldEnd, selectionEnd);
            int fieldNameEnd = fieldNameEnd(query, fieldEnd, selectionEnd);
            int fieldSelectionStart = fieldSelectionStart(query, fieldNameEnd, selectionEnd);
            if ("name".equals(fieldName) || "kind".equals(fieldName) || "description".equals(fieldName)) {
                if (fieldHasArguments(query, fieldNameEnd, selectionEnd)) {
                    return "field '__Type." + fieldName + "' does not support arguments";
                }
                if (fieldSelectionStart >= 0) {
                    return "field '__Type." + fieldName + "' must not have a selection set";
                }
            } else if ("fields".equals(fieldName)) {
                String argumentError = introspectionOptionalIncludeDeprecatedArgumentValidationError(
                        query,
                        "__Type.fields",
                        fieldNameEnd,
                        selectionEnd);
                if (argumentError.isEmpty() == false) {
                    return argumentError;
                }
                if (fieldSelectionStart < 0) {
                    return "field '__Type.fields' requires a selection set";
                }
                String fieldError = introspectionFieldSelectionValidationError(query, fieldSelectionStart);
                if (fieldError.isEmpty() == false) {
                    return fieldError;
                }
            } else if ("inputFields".equals(fieldName)) {
                String argumentError = introspectionOptionalIncludeDeprecatedArgumentValidationError(
                        query,
                        "__Type.inputFields",
                        fieldNameEnd,
                        selectionEnd);
                if (argumentError.isEmpty() == false) {
                    return argumentError;
                }
                if (fieldSelectionStart < 0) {
                    return "field '__Type.inputFields' requires a selection set";
                }
                String inputFieldError = introspectionInputValueSelectionValidationError(query, fieldSelectionStart);
                if (inputFieldError.isEmpty() == false) {
                    return inputFieldError;
                }
            } else if ("enumValues".equals(fieldName)) {
                String argumentError = introspectionOptionalIncludeDeprecatedArgumentValidationError(
                        query,
                        "__Type.enumValues",
                        fieldNameEnd,
                        selectionEnd);
                if (argumentError.isEmpty() == false) {
                    return argumentError;
                }
                if (fieldSelectionStart < 0) {
                    return "field '__Type.enumValues' requires a selection set";
                }
                String enumValueError = introspectionEnumValueSelectionValidationError(query, fieldSelectionStart);
                if (enumValueError.isEmpty() == false) {
                    return enumValueError;
                }
            } else {
                return "unsupported __Type field '" + fieldName + "'";
            }
            position = topLevelFieldEnd(query, fieldNameEnd, selectionEnd);
        }
        return "";
    }

    private static String introspectionOptionalIncludeDeprecatedArgumentValidationError(
            String query,
            String fieldName,
            int fieldNameEnd,
            int selectionEnd
    ) {
        int argumentsStart = nextNonWhitespace(query, fieldNameEnd, selectionEnd);
        if (argumentsStart >= selectionEnd || query.charAt(argumentsStart) != '(') {
            return "";
        }
        int argumentsEnd = matchingParen(query, argumentsStart);
        if (argumentsEnd >= query.length()) {
            return "expected ')' after " + fieldName + " arguments";
        }
        int includeDeprecatedArgument = topLevelArgumentNamePosition(
                query,
                "includeDeprecated",
                argumentsStart,
                argumentsEnd);
        int position = argumentsStart + 1;
        int argumentCount = 0;
        while (position < argumentsEnd) {
            if (isNameStart(query.charAt(position))) {
                argumentCount++;
                while (position < argumentsEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                position = skipArgumentValue(query, position, argumentsEnd);
            } else {
                position++;
            }
        }
        if (argumentCount != 1 || includeDeprecatedArgument < 0) {
            return "field '" + fieldName + "' supports only argument 'includeDeprecated'";
        }
        int separator = query.indexOf(":", includeDeprecatedArgument);
        if (separator < 0 || separator > argumentsEnd) {
            return "expected ':' after " + fieldName + ".includeDeprecated argument";
        }
        int valueStart = nextNonWhitespace(query, separator + 1, argumentsEnd);
        if (isExactNameAt(query, "true", valueStart) || isExactNameAt(query, "false", valueStart)) {
            return "";
        }
        return "argument '" + fieldName + ".includeDeprecated' must be Boolean";
    }

    private static String introspectionEnumValueSelectionValidationError(String query, int selectionStart) {
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            int fieldStart = nextTopLevelFieldStart(query, position, selectionEnd);
            if (fieldStart < 0) {
                return "";
            }
            int fieldEnd = fieldTokenEnd(query, fieldStart, selectionEnd);
            String fieldName = fieldName(query, fieldStart, fieldEnd, selectionEnd);
            int fieldNameEnd = fieldNameEnd(query, fieldEnd, selectionEnd);
            if (fieldHasArguments(query, fieldNameEnd, selectionEnd)) {
                return "field '__EnumValue." + fieldName + "' does not support arguments";
            }
            int fieldSelectionStart = fieldSelectionStart(query, fieldNameEnd, selectionEnd);
            if ("name".equals(fieldName)
                    || "description".equals(fieldName)
                    || "isDeprecated".equals(fieldName)
                    || "deprecationReason".equals(fieldName)) {
                if (fieldSelectionStart >= 0) {
                    return "field '__EnumValue." + fieldName + "' must not have a selection set";
                }
            } else {
                return "unsupported __EnumValue field '" + fieldName + "'";
            }
            position = topLevelFieldEnd(query, fieldNameEnd, selectionEnd);
        }
        return "";
    }

    private static String introspectionDirectiveSelectionValidationError(String query, int selectionStart) {
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            int fieldStart = nextTopLevelFieldStart(query, position, selectionEnd);
            if (fieldStart < 0) {
                return "";
            }
            int fieldEnd = fieldTokenEnd(query, fieldStart, selectionEnd);
            String fieldName = fieldName(query, fieldStart, fieldEnd, selectionEnd);
            int fieldNameEnd = fieldNameEnd(query, fieldEnd, selectionEnd);
            if (fieldHasArguments(query, fieldNameEnd, selectionEnd)) {
                return "field '__Directive." + fieldName + "' does not support arguments";
            }
            int fieldSelectionStart = fieldSelectionStart(query, fieldNameEnd, selectionEnd);
            if ("name".equals(fieldName)
                    || "description".equals(fieldName)
                    || "isRepeatable".equals(fieldName)
                    || "locations".equals(fieldName)) {
                if (fieldSelectionStart >= 0) {
                    return "field '__Directive." + fieldName + "' must not have a selection set";
                }
            } else if ("args".equals(fieldName)) {
                if (fieldSelectionStart < 0) {
                    return "field '__Directive.args' requires a selection set";
                }
                String argumentError = introspectionInputValueSelectionValidationError(query, fieldSelectionStart);
                if (argumentError.isEmpty() == false) {
                    return argumentError;
                }
            } else {
                return "unsupported __Directive field '" + fieldName + "'";
            }
            position = topLevelFieldEnd(query, fieldNameEnd, selectionEnd);
        }
        return "";
    }

    private static String introspectionFieldSelectionValidationError(String query, int selectionStart) {
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            int fieldStart = nextTopLevelFieldStart(query, position, selectionEnd);
            if (fieldStart < 0) {
                return "";
            }
            int fieldEnd = fieldTokenEnd(query, fieldStart, selectionEnd);
            String fieldName = fieldName(query, fieldStart, fieldEnd, selectionEnd);
            int fieldNameEnd = fieldNameEnd(query, fieldEnd, selectionEnd);
            if (fieldHasArguments(query, fieldNameEnd, selectionEnd)) {
                return "field '__Field." + fieldName + "' does not support arguments";
            }
            int fieldSelectionStart = fieldSelectionStart(query, fieldNameEnd, selectionEnd);
            if ("name".equals(fieldName)
                    || "description".equals(fieldName)
                    || "isDeprecated".equals(fieldName)
                    || "deprecationReason".equals(fieldName)) {
                if (fieldSelectionStart >= 0) {
                    return "field '__Field." + fieldName + "' must not have a selection set";
                }
            } else if ("type".equals(fieldName)) {
                if (fieldSelectionStart < 0) {
                    return "field '__Field.type' requires a selection set";
                }
                String typeRefError = typeReferenceSelectionValidationError(query, fieldSelectionStart);
                if (typeRefError.isEmpty() == false) {
                    return typeRefError;
                }
            } else if ("args".equals(fieldName)) {
                if (fieldSelectionStart < 0) {
                    return "field '__Field.args' requires a selection set";
                }
                String argumentError = introspectionInputValueSelectionValidationError(query, fieldSelectionStart);
                if (argumentError.isEmpty() == false) {
                    return argumentError;
                }
            } else {
                return "unsupported __Field field '" + fieldName + "'";
            }
            position = topLevelFieldEnd(query, fieldNameEnd, selectionEnd);
        }
        return "";
    }

    private static String introspectionInputValueSelectionValidationError(String query, int selectionStart) {
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            int fieldStart = nextTopLevelFieldStart(query, position, selectionEnd);
            if (fieldStart < 0) {
                return "";
            }
            int fieldEnd = fieldTokenEnd(query, fieldStart, selectionEnd);
            String fieldName = fieldName(query, fieldStart, fieldEnd, selectionEnd);
            int fieldNameEnd = fieldNameEnd(query, fieldEnd, selectionEnd);
            if (fieldHasArguments(query, fieldNameEnd, selectionEnd)) {
                return "field '__InputValue." + fieldName + "' does not support arguments";
            }
            int fieldSelectionStart = fieldSelectionStart(query, fieldNameEnd, selectionEnd);
            if ("name".equals(fieldName)
                    || "defaultValue".equals(fieldName)
                    || "description".equals(fieldName)
                    || "isDeprecated".equals(fieldName)
                    || "deprecationReason".equals(fieldName)) {
                if (fieldSelectionStart >= 0) {
                    return "field '__InputValue." + fieldName + "' must not have a selection set";
                }
            } else if ("type".equals(fieldName)) {
                if (fieldSelectionStart < 0) {
                    return "field '__InputValue.type' requires a selection set";
                }
                String typeRefError = typeReferenceSelectionValidationError(query, fieldSelectionStart);
                if (typeRefError.isEmpty() == false) {
                    return typeRefError;
                }
            } else {
                return "unsupported __InputValue field '" + fieldName + "'";
            }
            position = topLevelFieldEnd(query, fieldNameEnd, selectionEnd);
        }
        return "";
    }

    private static String typeReferenceSelectionValidationError(String query, int selectionStart) {
        // GAP-004 iterative form of the previous recursive descent: nested ofType
        // selections are entered in place, with the parent's resume position
        // (topLevelFieldEnd, computed before descending — it is a pure function of
        // positions known at that point) and the parent's bound saved as a
        // "resume,end|" string frame. A level with no further top-level fields pops
        // its frame exactly where the recursion returned "". Validation errors
        // return immediately, exactly like the recursion propagating the first
        // depth-first error unchanged.
        String frames = "";
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        while (position >= 0) {
            int fieldStart = -1;
            if (position < selectionEnd) {
                fieldStart = nextTopLevelFieldStart(query, position, selectionEnd);
            }
            if (fieldStart < 0) {
                if (frames.isEmpty()) {
                    return "";
                }
                String frame = frames.substring(0, frames.indexOf("|"));
                frames = frames.substring(frames.indexOf("|") + 1);
                position = traversalFrameNumber(frame.substring(0, frame.indexOf(",")));
                selectionEnd = traversalFrameNumber(frame.substring(frame.indexOf(",") + 1));
            } else {
                int fieldEnd = fieldTokenEnd(query, fieldStart, selectionEnd);
                String fieldName = fieldName(query, fieldStart, fieldEnd, selectionEnd);
                int fieldNameEnd = fieldNameEnd(query, fieldEnd, selectionEnd);
                if (fieldHasArguments(query, fieldNameEnd, selectionEnd)) {
                    return "field '__Type." + fieldName + "' does not support arguments";
                }
                int fieldSelectionStart = fieldSelectionStart(query, fieldNameEnd, selectionEnd);
                if ("name".equals(fieldName) || "kind".equals(fieldName) || "description".equals(fieldName)) {
                    if (fieldSelectionStart >= 0) {
                        return "field '__Type." + fieldName + "' must not have a selection set";
                    }
                    position = topLevelFieldEnd(query, fieldNameEnd, selectionEnd);
                } else if ("ofType".equals(fieldName)) {
                    if (fieldSelectionStart < 0) {
                        return "field '__Type.ofType' requires a selection set";
                    }
                    int resume = topLevelFieldEnd(query, fieldNameEnd, selectionEnd);
                    frames = resume + "," + selectionEnd + "|" + frames;
                    selectionEnd = selectionEnd(query, fieldSelectionStart);
                    position = fieldSelectionStart + 1;
                } else {
                    return "unsupported __Type reference field '" + fieldName + "'";
                }
            }
        }
        return "";
    }

    private static boolean fieldHasArguments(String query, int fieldNameEnd, int selectionEnd) {
        int next = nextNonWhitespace(query, fieldNameEnd, selectionEnd);
        return next < selectionEnd && query.charAt(next) == '(';
    }

    private static int fieldSelectionStart(String query, int fieldNameEnd, int selectionEnd) {
        int afterArguments = fieldAfterArguments(query, fieldNameEnd, selectionEnd);
        int next = nextNonWhitespace(query, afterArguments, selectionEnd);
        if (next < selectionEnd && query.charAt(next) == '{') {
            return next;
        }
        return -1;
    }

    private static String renderIntrospectionJson(String query, String rootName) {
        String rootKey = rootResponseKey(query);
        if ("__schema".equals(rootName)) {
            return "{\"data\":{\"" + rootKey + "\":" + renderSchemaJson(query) + "}}";
        }
        return "{\"data\":{\"" + rootKey + "\":" + renderTypeJson(query, typeNameArgument(query)) + "}}";
    }

    private static String renderSchemaJson(String query) {
        int rootStart = indexOfName(query, "__schema", 0);
        int selectionStart = rootFieldSelectionStart(query, rootStart);
        int selectionEnd = selectionEnd(query, selectionStart);
        String json = "{";
        boolean first = true;
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            int fieldStart = nextTopLevelFieldStart(query, position, selectionEnd);
            if (fieldStart < 0) {
                position = selectionEnd;
            } else {
                int fieldEnd = fieldTokenEnd(query, fieldStart, selectionEnd);
                String responseKey = query.substring(fieldStart, fieldEnd);
                String fieldName = fieldName(query, fieldStart, fieldEnd, selectionEnd);
                int fieldSelectionStart = fieldSelectionStart(query, fieldNameEnd(query, fieldEnd, selectionEnd), selectionEnd);
                if (first == false) {
                    json = json + ",";
                }
                if ("queryType".equals(fieldName)) {
                    json = json + "\"" + responseKey + "\":" + renderTypeReferenceJson(query, fieldSelectionStart, "Query", "OBJECT");
                } else if ("description".equals(fieldName)
                        || "mutationType".equals(fieldName)
                        || "subscriptionType".equals(fieldName)) {
                    json = json + "\"" + responseKey + "\":null";
                } else if ("types".equals(fieldName)) {
                    json = json + "\"" + responseKey + "\":" + renderSchemaTypesJson(query, fieldSelectionStart);
                } else {
                    json = json + "\"" + responseKey + "\":" + renderSchemaDirectivesJson(query, fieldSelectionStart);
                }
                first = false;
                position = topLevelFieldEnd(query, fieldEnd, selectionEnd);
            }
        }
        return json + "}";
    }

    private static String renderSchemaTypesJson(String query, int selectionStart) {
        return "["
                + renderTypeReferenceJson(query, selectionStart, "Query", "OBJECT") + ","
                + renderTypeReferenceJson(query, selectionStart, "Article", "OBJECT") + ","
                + renderTypeReferenceJson(query, selectionStart, "User", "OBJECT") + ","
                + renderTypeReferenceJson(query, selectionStart, "Comment", "OBJECT") + ","
                + renderTypeReferenceJson(query, selectionStart, "Boolean", "SCALAR") + ","
                + renderTypeReferenceJson(query, selectionStart, "Int", "SCALAR") + ","
                + renderTypeReferenceJson(query, selectionStart, "String", "SCALAR") + ","
                + renderTypeReferenceJson(query, selectionStart, "ArticleConnection", "OBJECT") + ","
                + renderTypeReferenceJson(query, selectionStart, "ArticleEdge", "OBJECT") + ","
                + renderTypeReferenceJson(query, selectionStart, "CommentConnection", "OBJECT") + ","
                + renderTypeReferenceJson(query, selectionStart, "CommentEdge", "OBJECT") + ","
                + renderTypeReferenceJson(query, selectionStart, "PageInfo", "OBJECT") + ","
                + renderTypeReferenceJson(query, selectionStart, "IntFilter", "INPUT_OBJECT") + ","
                + renderTypeReferenceJson(query, selectionStart, "StringFilter", "INPUT_OBJECT") + ","
                + renderTypeReferenceJson(query, selectionStart, "ArticleFilter", "INPUT_OBJECT") + ","
                + renderTypeReferenceJson(query, selectionStart, "SortDirection", "ENUM") + ","
                + renderTypeReferenceJson(query, selectionStart, "ArticleOrderBy", "INPUT_OBJECT")
                + "]";
    }

    private static String renderSchemaDirectivesJson(String query, int selectionStart) {
        return "["
                + renderDirectiveJson(
                        query,
                        selectionStart,
                        "include",
                        "[\"FIELD\",\"FRAGMENT_SPREAD\",\"INLINE_FRAGMENT\"]") + ","
                + renderDirectiveJson(
                        query,
                        selectionStart,
                        "skip",
                        "[\"FIELD\",\"FRAGMENT_SPREAD\",\"INLINE_FRAGMENT\"]") + ","
                + renderDirectiveJson(
                        query,
                        selectionStart,
                        "relationSortPath",
                        "[\"FIELD_DEFINITION\"]")
                + "]";
    }

    private static String renderTypeJson(String query, String typeName) {
        String kind = introspectionTypeKind(typeName);
        if (kind.isEmpty()) {
            return "null";
        }
        int rootStart = indexOfName(query, "__type", 0);
        int selectionStart = rootFieldSelectionStart(query, rootStart);
        int selectionEnd = selectionEnd(query, selectionStart);
        String json = "{";
        boolean first = true;
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            int fieldStart = nextTopLevelFieldStart(query, position, selectionEnd);
            if (fieldStart < 0) {
                position = selectionEnd;
            } else {
                int fieldEnd = fieldTokenEnd(query, fieldStart, selectionEnd);
                String responseKey = query.substring(fieldStart, fieldEnd);
                String fieldName = fieldName(query, fieldStart, fieldEnd, selectionEnd);
                if (first == false) {
                    json = json + ",";
                }
                if ("name".equals(fieldName)) {
                    json = json + "\"" + responseKey + "\":\"" + typeName + "\"";
                } else if ("kind".equals(fieldName)) {
                    json = json + "\"" + responseKey + "\":\"" + kind + "\"";
                } else if ("description".equals(fieldName)) {
                    json = json + "\"" + responseKey + "\":null";
                } else if ("fields".equals(fieldName)) {
                    int fieldSelectionStart = fieldSelectionStart(query, fieldNameEnd(query, fieldEnd, selectionEnd), selectionEnd);
                    json = json + "\"" + responseKey + "\":" + renderTypeFieldsJson(query, fieldSelectionStart, typeName, kind);
                } else if ("inputFields".equals(fieldName)) {
                    int fieldSelectionStart = fieldSelectionStart(query, fieldNameEnd(query, fieldEnd, selectionEnd), selectionEnd);
                    json = json + "\"" + responseKey + "\":" + renderTypeInputFieldsJson(query, fieldSelectionStart, typeName, kind);
                } else {
                    int fieldSelectionStart = fieldSelectionStart(query, fieldNameEnd(query, fieldEnd, selectionEnd), selectionEnd);
                    json = json + "\"" + responseKey + "\":" + renderTypeEnumValuesJson(query, fieldSelectionStart, typeName, kind);
                }
                first = false;
                position = topLevelFieldEnd(query, fieldEnd, selectionEnd);
            }
        }
        return json + "}";
    }

    private static String renderTypeFieldsJson(String query, int selectionStart, String typeName, String kind) {
        if ("OBJECT".equals(kind) == false) {
            return "null";
        }
        if ("Query".equals(typeName)) {
            return "["
                    + renderIntrospectionFieldJson(
                            query,
                            selectionStart,
                            "article",
                            introspectionNamedType("Article", "OBJECT")) + ","
                    + renderIntrospectionFieldJson(
                            query,
                            selectionStart,
                            "articles",
                            introspectionNonNullType(
                                    introspectionNamedType("ArticleConnection", "OBJECT")))
                    + "]";
        }
        if ("Article".equals(typeName)) {
            return "["
                    + renderIntrospectionFieldJson(query, selectionStart, "id", introspectionNamedType("Int", "SCALAR")) + ","
                    + renderIntrospectionFieldJson(query, selectionStart, "title", introspectionNamedType("String", "SCALAR")) + ","
                    + renderIntrospectionFieldJson(query, selectionStart, "titleLength", introspectionNamedType("Int", "SCALAR")) + ","
                    + renderIntrospectionFieldJson(
                            query,
                            selectionStart,
                            "author",
                            introspectionNonNullType(introspectionNamedType("User", "OBJECT"))) + ","
                    + renderIntrospectionFieldJson(
                            query,
                            selectionStart,
                            "comments",
                            introspectionNonNullType(
                                    introspectionNamedType("CommentConnection", "OBJECT")))
                    + "]";
        }
        if ("User".equals(typeName)) {
            return "["
                    + renderIntrospectionFieldJson(query, selectionStart, "id", introspectionNamedType("Int", "SCALAR")) + ","
                    + renderIntrospectionFieldJson(query, selectionStart, "name", introspectionNamedType("String", "SCALAR")) + ","
                    + renderIntrospectionFieldJson(query, selectionStart, "email", introspectionNamedType("String", "SCALAR"))
                    + "]";
        }
        if ("Comment".equals(typeName)) {
            return "["
                    + renderIntrospectionFieldJson(query, selectionStart, "id", introspectionNamedType("Int", "SCALAR")) + ","
                    + renderIntrospectionFieldJson(query, selectionStart, "body", introspectionNamedType("String", "SCALAR")) + ","
                    + renderIntrospectionFieldJson(
                            query,
                            selectionStart,
                            "author",
                            introspectionNonNullType(introspectionNamedType("User", "OBJECT")))
                    + "]";
        }
        return "[]";
    }

    private static String renderTypeInputFieldsJson(String query, int selectionStart, String typeName, String kind) {
        if ("INPUT_OBJECT".equals(kind) == false) {
            return "null";
        }
        if ("IntFilter".equals(typeName)) {
            return "["
                    + renderInputValueJson(query, selectionStart, "eq", introspectionNamedType("Int", "SCALAR")) + ","
                    + renderInputValueJson(query, selectionStart, "neq", introspectionNamedType("Int", "SCALAR")) + ","
                    + renderInputValueJson(
                            query,
                            selectionStart,
                            "in",
                            introspectionListType(
                                    introspectionNonNullType(
                                            introspectionNamedType("Int", "SCALAR")))) + ","
                    + renderInputValueJson(query, selectionStart, "isNull", introspectionNamedType("Boolean", "SCALAR")) + ","
                    + renderInputValueJson(query, selectionStart, "lt", introspectionNamedType("Int", "SCALAR")) + ","
                    + renderInputValueJson(query, selectionStart, "lte", introspectionNamedType("Int", "SCALAR")) + ","
                    + renderInputValueJson(query, selectionStart, "gt", introspectionNamedType("Int", "SCALAR")) + ","
                    + renderInputValueJson(query, selectionStart, "gte", introspectionNamedType("Int", "SCALAR"))
                    + "]";
        }
        if ("StringFilter".equals(typeName)) {
            return "["
                    + renderInputValueJson(query, selectionStart, "eq", introspectionNamedType("String", "SCALAR")) + ","
                    + renderInputValueJson(query, selectionStart, "neq", introspectionNamedType("String", "SCALAR")) + ","
                    + renderInputValueJson(
                            query,
                            selectionStart,
                            "in",
                            introspectionListType(
                                    introspectionNonNullType(
                                            introspectionNamedType("String", "SCALAR")))) + ","
                    + renderInputValueJson(query, selectionStart, "isNull", introspectionNamedType("Boolean", "SCALAR")) + ","
                    + renderInputValueJson(query, selectionStart, "contains", introspectionNamedType("String", "SCALAR")) + ","
                    + renderInputValueJson(query, selectionStart, "startsWith", introspectionNamedType("String", "SCALAR")) + ","
                    + renderInputValueJson(query, selectionStart, "endsWith", introspectionNamedType("String", "SCALAR"))
                    + "]";
        }
        if ("ArticleFilter".equals(typeName)) {
            return "["
                    + renderInputValueJson(query, selectionStart, "id", introspectionNamedType("IntFilter", "INPUT_OBJECT")) + ","
                    + renderInputValueJson(query, selectionStart, "title", introspectionNamedType("StringFilter", "INPUT_OBJECT")) + ","
                    + renderInputValueJson(query, selectionStart, "titleLength", introspectionNamedType("IntFilter", "INPUT_OBJECT")) + ","
                    + renderInputValueJson(query, selectionStart, "authorId", introspectionNamedType("IntFilter", "INPUT_OBJECT")) + ","
                    + renderInputValueJson(query, selectionStart, "authorName", introspectionNamedType("StringFilter", "INPUT_OBJECT")) + ","
                    + renderInputValueJson(
                            query,
                            selectionStart,
                            "and",
                            introspectionListType(
                                    introspectionNonNullType(
                                            introspectionNamedType("ArticleFilter", "INPUT_OBJECT")))) + ","
                    + renderInputValueJson(
                            query,
                            selectionStart,
                            "or",
                            introspectionListType(
                                    introspectionNonNullType(
                                            introspectionNamedType("ArticleFilter", "INPUT_OBJECT")))) + ","
                    + renderInputValueJson(query, selectionStart, "not", introspectionNamedType("ArticleFilter", "INPUT_OBJECT"))
                    + "]";
        }
        if ("ArticleOrderBy".equals(typeName)) {
            return "["
                    + renderInputValueJson(query, selectionStart, "id", introspectionNamedType("SortDirection", "ENUM")) + ","
                    + renderInputValueJson(query, selectionStart, "title", introspectionNamedType("SortDirection", "ENUM")) + ","
                    + renderInputValueJson(query, selectionStart, "titleLength", introspectionNamedType("SortDirection", "ENUM")) + ","
                    + renderInputValueJson(query, selectionStart, "authorName", introspectionNamedType("SortDirection", "ENUM"))
                    + "]";
        }
        return "[]";
    }

    private static String renderTypeEnumValuesJson(String query, int selectionStart, String typeName, String kind) {
        if ("ENUM".equals(kind) == false) {
            return "null";
        }
        if ("SortDirection".equals(typeName)) {
            return "["
                    + renderEnumValueJson(query, selectionStart, "ASC") + ","
                    + renderEnumValueJson(query, selectionStart, "DESC")
                    + "]";
        }
        return "[]";
    }

    private static String renderDirectiveJson(
            String query,
            int selectionStart,
            String directiveName,
            String locationsJson
    ) {
        int selectionEnd = selectionEnd(query, selectionStart);
        String json = "{";
        boolean first = true;
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            int fieldStart = nextTopLevelFieldStart(query, position, selectionEnd);
            if (fieldStart < 0) {
                position = selectionEnd;
            } else {
                int fieldEnd = fieldTokenEnd(query, fieldStart, selectionEnd);
                String responseKey = query.substring(fieldStart, fieldEnd);
                String fieldName = fieldName(query, fieldStart, fieldEnd, selectionEnd);
                if (first == false) {
                    json = json + ",";
                }
                if ("name".equals(fieldName)) {
                    json = json + "\"" + responseKey + "\":\"" + directiveName + "\"";
                } else if ("locations".equals(fieldName)) {
                    json = json + "\"" + responseKey + "\":" + locationsJson;
                } else if ("description".equals(fieldName)) {
                    json = json + "\"" + responseKey + "\":null";
                } else if ("isRepeatable".equals(fieldName)) {
                    json = json + "\"" + responseKey + "\":" + directiveRepeatable(directiveName);
                } else {
                    int argsSelectionStart = fieldSelectionStart(query, fieldNameEnd(query, fieldEnd, selectionEnd), selectionEnd);
                    json = json + "\"" + responseKey + "\":[";
                    int argumentCount = directiveArgumentCount(directiveName);
                    for (int i = 0; i < argumentCount; i++) {
                        if (i > 0) {
                            json = json + ",";
                        }
                        json = json + renderInputValueJson(
                                query,
                                argsSelectionStart,
                                directiveArgumentName(directiveName, i),
                                directiveArgumentType(directiveName, i));
                    }
                    json = json + "]";
                }
                first = false;
                position = topLevelFieldEnd(query, fieldEnd, selectionEnd);
            }
        }
        return json + "}";
    }

    private static int directiveArgumentCount(String directiveName) {
        if ("relationSortPath".equals(directiveName)) {
            return 6;
        }
        return 1;
    }

    private static boolean directiveRepeatable(String directiveName) {
        return "relationSortPath".equals(directiveName);
    }

    private static String directiveArgumentName(String directiveName, int index) {
        if ("relationSortPath".equals(directiveName)) {
            if (index == 0) {
                return "name";
            }
            if (index == 1) {
                return "column";
            }
            if (index == 2) {
                return "path";
            }
            if (index == 3) {
                return "hops";
            }
            if (index == 4) {
                return "direction";
            }
            return "tieBreaker";
        }
        return "if";
    }

    private static String directiveArgumentType(String directiveName, int index) {
        if ("relationSortPath".equals(directiveName) && index == 3) {
            return introspectionNonNullType(introspectionNamedType("Int", "SCALAR"));
        }
        if ("relationSortPath".equals(directiveName)) {
            return introspectionNonNullType(introspectionNamedType("String", "SCALAR"));
        }
        return introspectionNonNullType(introspectionNamedType("Boolean", "SCALAR"));
    }

    private static String renderIntrospectionFieldJson(
            String query,
            int selectionStart,
            String fieldName,
            String type
    ) {
        int selectionEnd = selectionEnd(query, selectionStart);
        String json = "{";
        boolean first = true;
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            int selectedFieldStart = nextTopLevelFieldStart(query, position, selectionEnd);
            if (selectedFieldStart < 0) {
                position = selectionEnd;
            } else {
                int selectedFieldEnd = fieldTokenEnd(query, selectedFieldStart, selectionEnd);
                String responseKey = query.substring(selectedFieldStart, selectedFieldEnd);
                String selectedFieldName = fieldName(query, selectedFieldStart, selectedFieldEnd, selectionEnd);
                if (first == false) {
                    json = json + ",";
                }
                if ("name".equals(selectedFieldName)) {
                    json = json + "\"" + responseKey + "\":\"" + fieldName + "\"";
                } else if ("description".equals(selectedFieldName)) {
                    json = json + "\"" + responseKey + "\":null";
                } else if ("isDeprecated".equals(selectedFieldName)) {
                    json = json + "\"" + responseKey + "\":false";
                } else if ("deprecationReason".equals(selectedFieldName)) {
                    json = json + "\"" + responseKey + "\":null";
                } else if ("type".equals(selectedFieldName)) {
                    int typeSelectionStart = fieldSelectionStart(query, fieldNameEnd(query, selectedFieldEnd, selectionEnd), selectionEnd);
                    json = json + "\"" + responseKey + "\":" + renderTypeReferenceJson(query, typeSelectionStart, type);
                } else {
                    int argsSelectionStart = fieldSelectionStart(query, fieldNameEnd(query, selectedFieldEnd, selectionEnd), selectionEnd);
                    json = json + "\"" + responseKey + "\":" + renderIntrospectionFieldArgsJson(query, argsSelectionStart, fieldName);
                }
                first = false;
                position = topLevelFieldEnd(query, selectedFieldEnd, selectionEnd);
            }
        }
        return json + "}";
    }

    private static String renderEnumValueJson(String query, int selectionStart, String enumValueName) {
        int selectionEnd = selectionEnd(query, selectionStart);
        String json = "{";
        boolean first = true;
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            int fieldStart = nextTopLevelFieldStart(query, position, selectionEnd);
            if (fieldStart < 0) {
                position = selectionEnd;
            } else {
                int fieldEnd = fieldTokenEnd(query, fieldStart, selectionEnd);
                String responseKey = query.substring(fieldStart, fieldEnd);
                String fieldName = fieldName(query, fieldStart, fieldEnd, selectionEnd);
                if (first == false) {
                    json = json + ",";
                }
                if ("name".equals(fieldName)) {
                    json = json + "\"" + responseKey + "\":\"" + enumValueName + "\"";
                } else if ("isDeprecated".equals(fieldName)) {
                    json = json + "\"" + responseKey + "\":false";
                } else {
                    json = json + "\"" + responseKey + "\":null";
                }
                first = false;
                position = topLevelFieldEnd(query, fieldEnd, selectionEnd);
            }
        }
        return json + "}";
    }

    private static String renderIntrospectionFieldArgsJson(String query, int selectionStart, String fieldName) {
        if ("article".equals(fieldName)) {
            return "["
                    + renderInputValueJson(
                            query,
                            selectionStart,
                            "id",
                            introspectionNonNullType(introspectionNamedType("Int", "SCALAR")))
                    + "]";
        }
        if ("articles".equals(fieldName)) {
            return "["
                    + renderInputValueJson(query, selectionStart, "first", introspectionNamedType("Int", "SCALAR")) + ","
                    + renderInputValueJson(query, selectionStart, "after", introspectionNamedType("String", "SCALAR")) + ","
                    + renderInputValueJson(query, selectionStart, "last", introspectionNamedType("Int", "SCALAR")) + ","
                    + renderInputValueJson(query, selectionStart, "before", introspectionNamedType("String", "SCALAR")) + ","
                    + renderInputValueJson(query, selectionStart, "authorId", introspectionNamedType("Int", "SCALAR")) + ","
                    + renderInputValueJson(
                            query,
                            selectionStart,
                            "filter",
                            introspectionNamedType("ArticleFilter", "INPUT_OBJECT")) + ","
                    + renderInputValueJson(
                            query,
                            selectionStart,
                            "orderBy",
                            introspectionListType(
                                    introspectionNonNullType(
                                            introspectionNamedType("ArticleOrderBy", "INPUT_OBJECT"))))
                    + "]";
        }
        if ("comments".equals(fieldName)) {
            return "["
                    + renderInputValueJson(query, selectionStart, "first", introspectionNamedType("Int", "SCALAR")) + ","
                    + renderInputValueJson(query, selectionStart, "after", introspectionNamedType("String", "SCALAR")) + ","
                    + renderInputValueJson(query, selectionStart, "last", introspectionNamedType("Int", "SCALAR")) + ","
                    + renderInputValueJson(query, selectionStart, "before", introspectionNamedType("String", "SCALAR"))
                    + "]";
        }
        return "[]";
    }

    private static String renderInputValueJson(
            String query,
            int selectionStart,
            String argumentName,
            String type
    ) {
        int selectionEnd = selectionEnd(query, selectionStart);
        String json = "{";
        boolean first = true;
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            int fieldStart = nextTopLevelFieldStart(query, position, selectionEnd);
            if (fieldStart < 0) {
                position = selectionEnd;
            } else {
                int fieldEnd = fieldTokenEnd(query, fieldStart, selectionEnd);
                String responseKey = query.substring(fieldStart, fieldEnd);
                String fieldName = fieldName(query, fieldStart, fieldEnd, selectionEnd);
                if (first == false) {
                    json = json + ",";
                }
                if ("name".equals(fieldName)) {
                    json = json + "\"" + responseKey + "\":\"" + argumentName + "\"";
                } else if ("defaultValue".equals(fieldName)) {
                    json = json + "\"" + responseKey + "\":null";
                } else if ("description".equals(fieldName)) {
                    json = json + "\"" + responseKey + "\":null";
                } else if ("isDeprecated".equals(fieldName)) {
                    json = json + "\"" + responseKey + "\":false";
                } else if ("deprecationReason".equals(fieldName)) {
                    json = json + "\"" + responseKey + "\":null";
                } else {
                    int typeSelectionStart = fieldSelectionStart(query, fieldNameEnd(query, fieldEnd, selectionEnd), selectionEnd);
                    json = json + "\"" + responseKey + "\":" + renderTypeReferenceJson(query, typeSelectionStart, type);
                }
                first = false;
                position = topLevelFieldEnd(query, fieldEnd, selectionEnd);
            }
        }
        return json + "}";
    }

    private static String renderTypeReferenceJson(String query, int selectionStart, String typeName, String kind) {
        return renderTypeReferenceJson(query, selectionStart, introspectionNamedType(typeName, kind));
    }

    private static String renderTypeReferenceJson(String query, int selectionStart, String type) {
        // GAP-004 iterative form of the previous recursive descent over ofType
        // selections: nested selections are entered in place, appending "{" exactly
        // where the recursive call opened its object. The parent's resume position
        // (topLevelFieldEnd, a pure function of positions known before descending),
        // bound, and string-encoded type descriptor are saved as a
        // "resume,end,type|" frame; type descriptors ("N:.."/"!:.."/"L:..") never
        // contain ',' or '|'. A level with no further top-level fields closes its
        // object with "}" and pops, restoring the parent with first=false — the
        // state the recursion left the parent in after the call returned.
        String frames = "";
        int selectionEnd = selectionEnd(query, selectionStart);
        String json = "{";
        boolean first = true;
        int position = selectionStart + 1;
        while (position >= 0) {
            int fieldStart = -1;
            if (position < selectionEnd) {
                fieldStart = nextTopLevelFieldStart(query, position, selectionEnd);
            }
            if (fieldStart < 0) {
                json = json + "}";
                if (frames.isEmpty()) {
                    return json;
                }
                String frame = frames.substring(0, frames.indexOf("|"));
                frames = frames.substring(frames.indexOf("|") + 1);
                int firstComma = frame.indexOf(",");
                int secondComma = frame.indexOf(",", firstComma + 1);
                position = traversalFrameNumber(frame.substring(0, firstComma));
                selectionEnd = traversalFrameNumber(frame.substring(firstComma + 1, secondComma));
                type = frame.substring(secondComma + 1);
                first = false;
            } else {
                int fieldEnd = fieldTokenEnd(query, fieldStart, selectionEnd);
                String responseKey = query.substring(fieldStart, fieldEnd);
                String fieldName = fieldName(query, fieldStart, fieldEnd, selectionEnd);
                if (first == false) {
                    json = json + ",";
                }
                if ("name".equals(fieldName)) {
                    json = json + "\"" + responseKey + "\":" + nullableJsonString(introspectionTypeName(type));
                    first = false;
                    position = topLevelFieldEnd(query, fieldEnd, selectionEnd);
                } else if ("kind".equals(fieldName)) {
                    json = json + "\"" + responseKey + "\":\"" + introspectionTypeKindRef(type) + "\"";
                    first = false;
                    position = topLevelFieldEnd(query, fieldEnd, selectionEnd);
                } else if ("description".equals(fieldName)) {
                    json = json + "\"" + responseKey + "\":null";
                    first = false;
                    position = topLevelFieldEnd(query, fieldEnd, selectionEnd);
                } else {
                    int ofTypeSelectionStart = fieldSelectionStart(query, fieldNameEnd(query, fieldEnd, selectionEnd), selectionEnd);
                    json = json + "\"" + responseKey + "\":";
                    if (introspectionOfType(type) == null) {
                        json = json + "null";
                        first = false;
                        position = topLevelFieldEnd(query, fieldEnd, selectionEnd);
                    } else {
                        int resume = topLevelFieldEnd(query, fieldEnd, selectionEnd);
                        frames = resume + "," + selectionEnd + "," + type + "|" + frames;
                        type = introspectionOfType(type);
                        json = json + "{";
                        first = true;
                        selectionEnd = selectionEnd(query, ofTypeSelectionStart);
                        position = ofTypeSelectionStart + 1;
                    }
                }
            }
        }
        return json;
    }

    private static String nullableJsonString(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value + "\"";
    }

    private static String typeNameArgument(String query) {
        int rootStart = indexOfName(query, "__type", 0);
        int argumentsStart = query.indexOf("(", rootStart);
        int argumentsEnd = matchingParen(query, argumentsStart);
        int nameArgument = topLevelArgumentNamePosition(query, "name", argumentsStart, argumentsEnd);
        int separator = query.indexOf(":", nameArgument);
        int valueStart = nextNonWhitespace(query, separator + 1, argumentsEnd);
        int valueEnd = valueStart;
        if (query.charAt(valueStart) == '"') {
            valueEnd++;
            while (valueEnd < argumentsEnd && query.charAt(valueEnd) != '"') {
                valueEnd++;
            }
            return stringLiteralValue(query, valueStart, valueEnd + 1);
        }
        while (valueEnd < argumentsEnd && isNamePart(query.charAt(valueEnd))) {
            valueEnd++;
        }
        return query.substring(valueStart, valueEnd);
    }

    private static String introspectionTypeKind(String typeName) {
        if ("Query".equals(typeName)
                || "Article".equals(typeName)
                || "User".equals(typeName)
                || "Comment".equals(typeName)
                || "ArticleConnection".equals(typeName)
                || "ArticleEdge".equals(typeName)
                || "CommentConnection".equals(typeName)
                || "CommentEdge".equals(typeName)
                || "PageInfo".equals(typeName)) {
            return "OBJECT";
        }
        if ("Boolean".equals(typeName) || "Int".equals(typeName) || "String".equals(typeName)) {
            return "SCALAR";
        }
        if ("IntFilter".equals(typeName)
                || "StringFilter".equals(typeName)
                || "ArticleFilter".equals(typeName)
                || "ArticleOrderBy".equals(typeName)) {
            return "INPUT_OBJECT";
        }
        if ("SortDirection".equals(typeName)) {
            return "ENUM";
        }
        return "";
    }

    private static String introspectionNamedType(String name, String kind) {
        return "N:" + name + ":" + kind;
    }

    private static String introspectionNonNullType(String ofType) {
        return "!:" + ofType;
    }

    private static String introspectionListType(String ofType) {
        return "L:" + ofType;
    }

    private static String introspectionTypeName(String type) {
        if (type.startsWith("N:")) {
            int separator = type.indexOf(':', 2);
            return type.substring(2, separator);
        }
        return null;
    }

    private static String introspectionTypeKindRef(String type) {
        if (type.startsWith("!:")) {
            return "NON_NULL";
        }
        if (type.startsWith("L:")) {
            return "LIST";
        }
        int separator = type.indexOf(':', 2);
        return type.substring(separator + 1);
    }

    private static String introspectionOfType(String type) {
        if (type.startsWith("!:") || type.startsWith("L:")) {
            return type.substring(2);
        }
        return null;
    }

    private static String errorJson(String message) {
        return "{\"errors\":[{\"message\":\"" + message + "\",\"extensions\":{\"code\":\""
                + errorCode(message) + "\"}}]}";
    }

    private static String errorCode(String message) {
        if (message.startsWith("operation type '") && message.endsWith("' is not supported")) {
            return "UNSUPPORTED_OPERATION";
        }
        if (message.contains("not authorized")) {
            return "AUTHORIZATION_ERROR";
        }
        if (message.startsWith("expected ")
                || message.startsWith("unexpected ")
                || message.equals("fragments are not supported")
                || message.equals("directives are not supported")) {
            return "PARSE_ERROR";
        }
        return "VALIDATION_ERROR";
    }

    private static boolean isAdmin(String actorRole) {
        // String.equalsIgnoreCase is outside core's lowering table (since titan e13b7b6 it
        // is rejected at transpile time with a positioned TITAN-E001 instead of being
        // silently emitted — TG-BLK-007, closed). Lower via the supported
        // toLowerCase -> LOWER(...) path; behavior is identical for the ASCII role names
        // this kernel compares.
        return actorRole != null && "admin".equals(actorRole.toLowerCase());
    }

    private static long articleId(String query) {
        int articleName = indexOfName(query, "article", 0);
        if (articleName < 0) {
            return -1L;
        }
        int argumentsStart = query.indexOf("(", articleName);
        int selectionStart = query.indexOf("{", articleName);
        if (argumentsStart < 0 || (selectionStart >= 0 && selectionStart < argumentsStart)) {
            return -1L;
        }
        int argumentsEnd = matchingParen(query, argumentsStart);
        if (argumentsEnd < 0) {
            return -2L;
        }
        int idName = indexOfName(query, "id", argumentsStart);
        if (idName < 0) {
            return -1L;
        }
        if (idName > argumentsEnd) {
            return -1L;
        }
        int separator = query.indexOf(":", idName);
        if (separator < 0 || separator > argumentsEnd) {
            return -2L;
        }
        int position = separator + 1;
        while (position < query.length() && isWhitespace(query.charAt(position))) {
            position++;
        }
        long value = 0L;
        boolean sawDigit = false;
        boolean reading = true;
        while (position < query.length() && reading) {
            char c = query.charAt(position);
            if (c < '0' || c > '9') {
                reading = false;
            } else {
                sawDigit = true;
                value = value * 10L + c - '0';
                position++;
            }
        }
        if (sawDigit == false) {
            return -3L;
        }
        return value;
    }

    private static long articlesIntArgument(String query, String argumentName, int defaultValue) {
        int articlesName = indexOfName(query, "articles", 0);
        if (articlesName < 0) {
            return defaultValue;
        }
        int argumentsStart = query.indexOf("(", articlesName);
        int selectionStart = query.indexOf("{", articlesName);
        if (argumentsStart < 0 || (selectionStart >= 0 && selectionStart < argumentsStart)) {
            return defaultValue;
        }
        int argumentsEnd = matchingParen(query, argumentsStart);
        if (argumentsEnd < 0) {
            return -2L;
        }
        int argument = topLevelArgumentNamePosition(query, argumentName, argumentsStart, argumentsEnd);
        if (argument < 0) {
            return defaultValue;
        }
        int separator = query.indexOf(":", argument);
        if (separator < 0 || separator > argumentsEnd) {
            return -2L;
        }
        int position = separator + 1;
        while (position < query.length() && isWhitespace(query.charAt(position))) {
            position++;
        }
        long value = 0L;
        boolean sawDigit = false;
        boolean reading = true;
        while (position < query.length() && reading) {
            char c = query.charAt(position);
            if (c < '0' || c > '9') {
                reading = false;
            } else {
                sawDigit = true;
                value = value * 10L + c - '0';
                position++;
            }
        }
        if (sawDigit == false) {
            return -3L;
        }
        return value;
    }

    private static long articlesPageSize(String query) {
        return articlesHasArgument(query, "last")
                ? articlesIntArgument(query, "last", 10)
                : articlesIntArgument(query, "first", 10);
    }

    private static String articlesPageSizeArgumentName(String query) {
        return articlesHasArgument(query, "last") ? "last" : "first";
    }

    private static boolean articlesHasArgument(String query, String argumentName) {
        int articlesName = indexOfName(query, "articles", 0);
        if (articlesName < 0) {
            return false;
        }
        int argumentsStart = query.indexOf("(", articlesName);
        int selectionStart = query.indexOf("{", articlesName);
        if (argumentsStart < 0 || (selectionStart >= 0 && selectionStart < argumentsStart)) {
            return false;
        }
        int argumentsEnd = matchingParen(query, argumentsStart);
        if (argumentsEnd < 0) {
            return false;
        }
        int argument = topLevelArgumentNamePosition(query, argumentName, argumentsStart, argumentsEnd);
        return argument >= 0;
    }

    private static long articlesAuthorId(String query) {
        int articlesName = indexOfName(query, "articles", 0);
        if (articlesName < 0) {
            return -1L;
        }
        int argumentsStart = query.indexOf("(", articlesName);
        int selectionStart = query.indexOf("{", articlesName);
        if (argumentsStart < 0 || (selectionStart >= 0 && selectionStart < argumentsStart)) {
            return -1L;
        }
        int argumentsEnd = matchingParen(query, argumentsStart);
        if (argumentsEnd < 0) {
            return -2L;
        }
        int authorIdName = topLevelArgumentNamePosition(query, "authorId", argumentsStart, argumentsEnd);
        if (authorIdName < 0) {
            return -1L;
        }
        int separator = query.indexOf(":", authorIdName);
        if (separator < 0 || separator > argumentsEnd) {
            return -2L;
        }
        int position = separator + 1;
        while (position < query.length() && isWhitespace(query.charAt(position))) {
            position++;
        }
        long value = 0L;
        boolean sawDigit = false;
        boolean reading = true;
        while (position < query.length() && reading) {
            char c = query.charAt(position);
            if (c < '0' || c > '9') {
                reading = false;
            } else {
                sawDigit = true;
                value = value * 10L + c - '0';
                position++;
            }
        }
        if (sawDigit == false) {
            return -3L;
        }
        return value;
    }

    private static long articlesCursorArgument(String query, String argumentName) {
        int articlesName = indexOfName(query, "articles", 0);
        if (articlesName < 0) {
            return -1L;
        }
        int argumentsStart = query.indexOf("(", articlesName);
        int selectionStart = query.indexOf("{", articlesName);
        if (argumentsStart < 0 || (selectionStart >= 0 && selectionStart < argumentsStart)) {
            return -1L;
        }
        int argumentsEnd = matchingParen(query, argumentsStart);
        if (argumentsEnd < 0) {
            return -2L;
        }
        int argument = topLevelArgumentNamePosition(query, argumentName, argumentsStart, argumentsEnd);
        if (argument < 0) {
            return -1L;
        }
        int separator = query.indexOf(":", argument);
        if (separator < 0 || separator > argumentsEnd) {
            return -2L;
        }
        int position = separator + 1;
        while (position < argumentsEnd && isWhitespace(query.charAt(position))) {
            position++;
        }
        if (position >= argumentsEnd || query.charAt(position) != '"') {
            return -2L;
        }
        position++;
        String prefix = "article:";
        int prefixPosition = 0;
        while (prefixPosition < prefix.length()) {
            if (position >= argumentsEnd || query.charAt(position) != prefix.charAt(prefixPosition)) {
                return -2L;
            }
            position++;
            prefixPosition++;
        }
        long value = 0L;
        boolean sawDigit = false;
        while (position < argumentsEnd && query.charAt(position) >= '0' && query.charAt(position) <= '9') {
            sawDigit = true;
            value = value * 10L + query.charAt(position) - '0';
            position++;
        }
        if (sawDigit == false || position >= argumentsEnd || query.charAt(position) != '"') {
            return -2L;
        }
        return value;
    }

    private static String articleArgumentValidationError(String query) {
        int articleName = indexOfName(query, "article", 0);
        if (articleName < 0) {
            return "";
        }
        int argumentsStart = query.indexOf("(", articleName);
        int selectionStart = query.indexOf("{", articleName);
        if (argumentsStart < 0 || (selectionStart >= 0 && selectionStart < argumentsStart)) {
            return "";
        }
        int argumentsEnd = matchingParen(query, argumentsStart);
        if (argumentsEnd < 0) {
            return "";
        }
        int position = argumentsStart + 1;
        int idCount = 0;
        while (position < argumentsEnd) {
            char c = query.charAt(position);
            if (isNameStart(c)) {
                int nameStart = position;
                while (position < argumentsEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                String name = query.substring(nameStart, position);
                if ("id".equals(name) == false) {
                    return "unsupported argument on article; only 'id' is allowed";
                }
                idCount++;
                position = skipArgumentValue(query, position, argumentsEnd);
            } else {
                position++;
            }
        }
        if (idCount > 1) {
            return "duplicate argument 'id'";
        }
        return "";
    }

    private static String articlesArgumentValidationError(String query) {
        int articlesName = indexOfName(query, "articles", 0);
        if (articlesName < 0) {
            return "";
        }
        int argumentsStart = query.indexOf("(", articlesName);
        int selectionStart = query.indexOf("{", articlesName);
        if (argumentsStart < 0 || (selectionStart >= 0 && selectionStart < argumentsStart)) {
            return "";
        }
        int argumentsEnd = matchingParen(query, argumentsStart);
        if (argumentsEnd < 0) {
            return "";
        }
        int position = argumentsStart + 1;
        int firstCount = 0;
        int afterCount = 0;
        int lastCount = 0;
        int beforeCount = 0;
        int authorIdCount = 0;
        int filterCount = 0;
        int orderByCount = 0;
        while (position < argumentsEnd) {
            char c = query.charAt(position);
            if (isNameStart(c)) {
                int nameStart = position;
                while (position < argumentsEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                String name = query.substring(nameStart, position);
                if ("first".equals(name)) {
                    firstCount++;
                } else if ("after".equals(name)) {
                    afterCount++;
                } else if ("last".equals(name)) {
                    lastCount++;
                } else if ("before".equals(name)) {
                    beforeCount++;
                } else if ("authorId".equals(name)) {
                    authorIdCount++;
                } else if ("filter".equals(name)) {
                    filterCount++;
                } else if ("orderBy".equals(name)) {
                    orderByCount++;
                } else {
                    return "unsupported argument on articles; supported arguments are 'first', 'after', 'last', 'before', 'authorId', 'filter', 'orderBy'";
                }
                position = skipArgumentValue(query, position, argumentsEnd);
            } else {
                position++;
            }
        }
        if (firstCount > 1) {
            return "duplicate argument 'first'";
        }
        if (afterCount > 1) {
            return "duplicate argument 'after'";
        }
        if (lastCount > 1) {
            return "duplicate argument 'last'";
        }
        if (beforeCount > 1) {
            return "duplicate argument 'before'";
        }
        if (firstCount > 0 && lastCount > 0) {
            return "cannot combine 'first' and 'last' on articles";
        }
        if (authorIdCount > 1) {
            return "duplicate argument 'authorId'";
        }
        if (filterCount > 1) {
            return "duplicate argument 'filter'";
        }
        if (orderByCount > 1) {
            return "duplicate argument 'orderBy'";
        }
        long first = articlesIntArgument(query, "first", 10);
        if (first < 0L || first > 100L) {
            return "argument 'first' must be between 0 and 100";
        }
        long last = articlesIntArgument(query, "last", 10);
        if (last < 0L || last > 100L) {
            return "argument 'last' must be between 0 and 100";
        }
        long after = articlesCursorArgument(query, "after");
        if (after == -2L) {
            return "argument 'after' must be an article cursor";
        }
        long before = articlesCursorArgument(query, "before");
        if (before == -2L) {
            return "argument 'before' must be an article cursor";
        }
        return "";
    }

    private static String commentsArgumentValidationError(String query, int position, int selectionStart) {
        int argumentsStart = nextNonWhitespace(query, position, selectionStart);
        if (argumentsStart >= selectionStart || query.charAt(argumentsStart) != '(') {
            return "";
        }
        int argumentsEnd = query.indexOf(")", argumentsStart);
        if (argumentsEnd < 0 || argumentsEnd > selectionStart) {
            return "expected ')' after comments arguments";
        }
        int scan = argumentsStart + 1;
        int firstCount = 0;
        int afterCount = 0;
        int lastCount = 0;
        int beforeCount = 0;
        while (scan < argumentsEnd) {
            char c = query.charAt(scan);
            if (isNameStart(c)) {
                int nameStart = scan;
                while (scan < argumentsEnd && isNamePart(query.charAt(scan))) {
                    scan++;
                }
                String name = query.substring(nameStart, scan);
                if ("first".equals(name)) {
                    firstCount++;
                } else if ("after".equals(name)) {
                    afterCount++;
                } else if ("last".equals(name)) {
                    lastCount++;
                } else if ("before".equals(name)) {
                    beforeCount++;
                } else {
                    return "unsupported argument on comments; supported arguments are 'first', 'after', 'last', 'before'";
                }
                scan = skipArgumentValue(query, scan, argumentsEnd);
            } else {
                scan++;
            }
        }
        if (firstCount > 1) {
            return "duplicate argument 'first'";
        }
        if (afterCount > 1) {
            return "duplicate argument 'after'";
        }
        if (lastCount > 1) {
            return "duplicate argument 'last'";
        }
        if (beforeCount > 1) {
            return "duplicate argument 'before'";
        }
        if (firstCount > 0 && lastCount > 0) {
            return "cannot combine 'first' and 'last' on comments";
        }
        int first = commentsIntArgument(query, "first", 10);
        if (first < 0 || first > 100) {
            return "argument 'first' must be between 0 and 100";
        }
        int last = commentsIntArgument(query, "last", 10);
        if (last < 0 || last > 100) {
            return "argument 'last' must be between 0 and 100";
        }
        long after = commentsCursorArgument(query, "after");
        if (after == -2L) {
            return "argument 'after' must be a comment cursor";
        }
        long before = commentsCursorArgument(query, "before");
        if (before == -2L) {
            return "argument 'before' must be a comment cursor";
        }
        return "";
    }

    private static String generatedArticleFilterValidationError(String query) {
        int valueStart = topLevelArgumentValueStart(query, "filter");
        if (valueStart < 0) {
            return "";
        }
        if (valueStart >= query.length() || query.charAt(valueStart) != '{') {
            return "argument 'filter' must be an input object";
        }
        return validateGeneratedArticleFilterObject(query, valueStart, matchingBrace(query, valueStart));
    }

    private static String validateGeneratedArticleFilterObject(String query, int objectStart, int objectEnd) {
        // GAP-004 iterative form of the previous validateGeneratedArticleFilterObject /
        // validateGeneratedArticleFilterList mutual recursion: one walker with an
        // explicit string-encoded frame stack. Object contexts validate fields in
        // textual order; "and"/"or" values descend into a list context (frame
        // "L<resume>,<end>,<name>|" remembers where the list resumes), "not" values
        // and list entries descend into an object context (frame "O<resume>,<end>|").
        // Resume positions are the parent's post-value advance (nextNonWhitespace
        // plus optional ',' skip) computed before descending — pure position
        // arithmetic over values already known there. A context that runs out of
        // content pops its frame exactly where the recursion returned ""; validation
        // errors return immediately, exactly like the recursion propagating the
        // first depth-first error unchanged.
        String frames = "";
        boolean inList = false;
        String listFieldName = "";
        int end = objectEnd;
        int position = objectStart + 1;
        while (position >= 0) {
            if (position < end) {
                position = nextNonWhitespace(query, position, end);
            }
            if (position >= end) {
                if (frames.isEmpty()) {
                    return "";
                }
                String frame = frames.substring(0, frames.indexOf("|"));
                frames = frames.substring(frames.indexOf("|") + 1);
                int firstComma = frame.indexOf(",");
                inList = frame.charAt(0) == 'L';
                position = traversalFrameNumber(frame.substring(1, firstComma));
                if (inList) {
                    int secondComma = frame.indexOf(",", firstComma + 1);
                    end = traversalFrameNumber(frame.substring(firstComma + 1, secondComma));
                    listFieldName = frame.substring(secondComma + 1);
                } else {
                    end = traversalFrameNumber(frame.substring(firstComma + 1));
                }
            } else if (inList) {
                if (query.charAt(position) != '{') {
                    return "filter field '" + listFieldName + "' entries must be input objects";
                }
                int entryEnd = matchingBrace(query, position);
                int resume = nextNonWhitespace(query, entryEnd + 1, end);
                if (resume < end && query.charAt(resume) == ',') {
                    resume++;
                }
                frames = "L" + resume + "," + end + "," + listFieldName + "|" + frames;
                end = entryEnd;
                position = position + 1;
                inList = false;
            } else {
                int nameStart = position;
                while (position < end && isNamePart(query.charAt(position))) {
                    position++;
                }
                if (nameStart == position) {
                    return "expected filter field name";
                }
                String name = query.substring(nameStart, position);
                int separator = nextNonWhitespace(query, position, end);
                if (separator >= end || query.charAt(separator) != ':') {
                    return "expected ':' after filter field name";
                }
                int valueStart = nextNonWhitespace(query, separator + 1, end);
                int valueEnd = skipInputValue(query, valueStart, end);
                int resume = nextNonWhitespace(query, valueEnd, end);
                if (resume < end && query.charAt(resume) == ',') {
                    resume++;
                }
                if ("and".equals(name) || "or".equals(name)) {
                    if (valueStart >= valueEnd || query.charAt(valueStart) != '[') {
                        return "filter field '" + name + "' must be a list";
                    }
                    frames = "O" + resume + "," + end + "|" + frames;
                    end = matchingBracket(query, valueStart);
                    position = valueStart + 1;
                    inList = true;
                    listFieldName = name;
                } else if ("not".equals(name)) {
                    if (valueStart >= valueEnd || query.charAt(valueStart) != '{') {
                        return "filter field 'not' must be an input object";
                    }
                    frames = "O" + resume + "," + end + "|" + frames;
                    end = matchingBrace(query, valueStart);
                    position = valueStart + 1;
                } else if ("id".equals(name) || "authorId".equals(name) || "title".equals(name)
                        || "titleLength".equals(name)
                        || "authorName".equals(name)) {
                    String error = validateGeneratedArticleScalarFilter(query, name, valueStart, valueEnd);
                    if (error.isEmpty() == false) {
                        return error;
                    }
                    position = resume;
                } else {
                    return "unknown field '" + name + "' on ArticleFilter";
                }
            }
        }
        return "";
    }

    private static String validateGeneratedArticleScalarFilter(String query, String fieldName, int valueStart, int valueEnd) {
        if (valueStart >= valueEnd || query.charAt(valueStart) != '{') {
            return "filter field '" + fieldName + "' must be an input object";
        }
        int objectEnd = matchingBrace(query, valueStart);
        int position = valueStart + 1;
        boolean sawOperator = false;
        while (position < objectEnd) {
            position = nextNonWhitespace(query, position, objectEnd);
            if (position >= objectEnd) {
                position = objectEnd;
            } else {
                int nameStart = position;
                while (position < objectEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                String operator = query.substring(nameStart, position);
                if ("eq".equals(operator) == false && "neq".equals(operator) == false
                        && "in".equals(operator) == false && "isNull".equals(operator) == false
                        && "lt".equals(operator) == false && "lte".equals(operator) == false
                        && "gt".equals(operator) == false && "gte".equals(operator) == false
                        && "contains".equals(operator) == false && "startsWith".equals(operator) == false
                        && "endsWith".equals(operator) == false) {
                    return "unsupported filter operator '" + operator + "' on field '" + fieldName + "'";
                }
                int separator = nextNonWhitespace(query, position, objectEnd);
                if (separator >= objectEnd || query.charAt(separator) != ':') {
                    return "expected ':' after filter operator";
                }
                int operatorValueStart = nextNonWhitespace(query, separator + 1, objectEnd);
                int operatorValueEnd = skipInputValue(query, operatorValueStart, objectEnd);
                String error = validateGeneratedArticleScalarFilterValue(
                        query,
                        fieldName,
                        operator,
                        operatorValueStart,
                        operatorValueEnd
                );
                if (error.isEmpty() == false) {
                    return error;
                }
                sawOperator = true;
                position = nextNonWhitespace(query, operatorValueEnd, objectEnd);
                if (position < objectEnd && query.charAt(position) == ',') {
                    position++;
                }
            }
        }
        return sawOperator ? "" : "filter field '" + fieldName + "' must include an operator";
    }

    private static String validateGeneratedArticleScalarFilterValue(
            String query,
            String fieldName,
            String operator,
            int valueStart,
            int valueEnd
    ) {
        if ("isNull".equals(operator)) {
            if (isExactNameAt(query, "true", valueStart) || isExactNameAt(query, "false", valueStart)) {
                return "";
            }
            return "filter operator 'isNull' on field '" + fieldName + "' must be Boolean";
        }
        if ("in".equals(operator)) {
            if (valueStart >= valueEnd || query.charAt(valueStart) != '[') {
                return "filter operator 'in' on field '" + fieldName + "' must be a list";
            }
            int listEnd = matchingBracket(query, valueStart);
            int position = valueStart + 1;
            while (position < listEnd) {
                position = nextNonWhitespace(query, position, listEnd);
                if (position >= listEnd) {
                    return "";
                }
                int itemEnd = skipInputValue(query, position, listEnd);
                String error = validateGeneratedArticleScalarLiteral(query, fieldName, "in", position, itemEnd, false);
                if (error.isEmpty() == false) {
                    return error;
                }
                position = nextNonWhitespace(query, itemEnd, listEnd);
                if (position < listEnd && query.charAt(position) == ',') {
                    position++;
                }
            }
            return "";
        }
        if ("contains".equals(operator) || "startsWith".equals(operator) || "endsWith".equals(operator)) {
            if ("title".equals(fieldName) == false && "authorName".equals(fieldName) == false) {
                return "unsupported filter operator '" + operator + "' on field '" + fieldName + "'";
            }
            return validateGeneratedArticleScalarLiteral(query, fieldName, operator, valueStart, valueEnd, false);
        }
        if ("lt".equals(operator) || "lte".equals(operator) || "gt".equals(operator) || "gte".equals(operator)) {
            if ("id".equals(fieldName) == false && "authorId".equals(fieldName) == false
                    && "titleLength".equals(fieldName) == false) {
                return "unsupported filter operator '" + operator + "' on field '" + fieldName + "'";
            }
            return validateGeneratedArticleScalarLiteral(query, fieldName, operator, valueStart, valueEnd, false);
        }
        return validateGeneratedArticleScalarLiteral(query, fieldName, operator, valueStart, valueEnd, true);
    }

    private static String validateGeneratedArticleScalarLiteral(
            String query,
            String fieldName,
            String operator,
            int valueStart,
            int valueEnd,
            boolean allowNull
    ) {
        if (allowNull && isExactNameAt(query, "null", valueStart)) {
            return "";
        }
        if ("title".equals(fieldName) || "authorName".equals(fieldName)) {
            if (valueStart < valueEnd && query.charAt(valueStart) == '"') {
                return "";
            }
            return "filter operator '" + operator + "' on field '" + fieldName + "' must use String values";
        }
        if (isIntegerLiteral(query, valueStart, valueEnd)) {
            return "";
        }
        return "filter operator '" + operator + "' on field '" + fieldName + "' must use Int values";
    }

    private static String generatedArticleOrderValidationError(String query) {
        int valueStart = topLevelArgumentValueStart(query, "orderBy");
        if (valueStart < 0) {
            return "";
        }
        if (valueStart >= query.length() || query.charAt(valueStart) != '[') {
            return "argument 'orderBy' must be a list";
        }
        int listEnd = matchingBracket(query, valueStart);
        int position = valueStart + 1;
        while (position < listEnd) {
            position = nextNonWhitespace(query, position, listEnd);
            if (position >= listEnd) {
                return "";
            }
            if (query.charAt(position) != '{') {
                return "argument 'orderBy' entries must be input objects";
            }
            int objectEnd = matchingBrace(query, position);
            String error = validateGeneratedArticleOrderObject(query, position, objectEnd);
            if (error.isEmpty() == false) {
                return error;
            }
            position = nextNonWhitespace(query, objectEnd + 1, listEnd);
            if (position < listEnd && query.charAt(position) == ',') {
                position++;
            }
        }
        return "";
    }

    private static String validateGeneratedArticleOrderObject(String query, int objectStart, int objectEnd) {
        int position = objectStart + 1;
        while (position < objectEnd) {
            position = nextNonWhitespace(query, position, objectEnd);
            if (position >= objectEnd) {
                return "";
            }
            int nameStart = position;
            while (position < objectEnd && isNamePart(query.charAt(position))) {
                position++;
            }
            String name = query.substring(nameStart, position);
            if ("id".equals(name) == false && "title".equals(name) == false
                    && "titleLength".equals(name) == false && "authorName".equals(name) == false) {
                return "unknown sort path '" + name + "' on ArticleOrderBy";
            }
            int separator = nextNonWhitespace(query, position, objectEnd);
            if (separator >= objectEnd || query.charAt(separator) != ':') {
                return "expected ':' after generated order path";
            }
            int valueStart = nextNonWhitespace(query, separator + 1, objectEnd);
            if (isExactNameAt(query, "ASC", valueStart) == false
                    && isExactNameAt(query, "DESC", valueStart) == false) {
                return "sort path '" + name + "' must use SortDirection";
            }
            position = skipInputValue(query, valueStart, objectEnd);
            position = nextNonWhitespace(query, position, objectEnd);
            if (position < objectEnd && query.charAt(position) == ',') {
                position++;
            }
        }
        return "";
    }

    private static int skipArgumentValue(String query, int position, int argumentsEnd) {
        int separator = query.indexOf(":", position);
        if (separator < 0 || separator > argumentsEnd) {
            return position;
        }
        position = skipInputValue(query, nextNonWhitespace(query, separator + 1, argumentsEnd), argumentsEnd);
        if (position < argumentsEnd && query.charAt(position) == ',') {
            position++;
        }
        return position;
    }

    private static int topLevelArgumentNamePosition(
            String query,
            String argumentName,
            int argumentsStart,
            int argumentsEnd
    ) {
        int position = argumentsStart + 1;
        while (position < argumentsEnd) {
            char c = query.charAt(position);
            if (isNameStart(c)) {
                int nameStart = position;
                while (position < argumentsEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                if (argumentName.equals(query.substring(nameStart, position))) {
                    return nameStart;
                }
                position = skipArgumentValue(query, position, argumentsEnd);
            } else {
                position++;
            }
        }
        return -1;
    }

    private static int topLevelArgumentValueStart(String query, String argumentName) {
        int articlesName = indexOfName(query, "articles", 0);
        if (articlesName < 0) {
            return -1;
        }
        int argumentsStart = query.indexOf("(", articlesName);
        int selectionStart = query.indexOf("{", articlesName);
        if (argumentsStart < 0 || (selectionStart >= 0 && selectionStart < argumentsStart)) {
            return -1;
        }
        int argumentsEnd = matchingParen(query, argumentsStart);
        int argument = topLevelArgumentNamePosition(query, argumentName, argumentsStart, argumentsEnd);
        if (argument < 0) {
            return -1;
        }
        int separator = query.indexOf(":", argument);
        if (separator < 0 || separator > argumentsEnd) {
            return -1;
        }
        return nextNonWhitespace(query, separator + 1, argumentsEnd);
    }

    private static int topLevelArgumentValueEnd(String query, int valueStart) {
        int articlesName = indexOfName(query, "articles", 0);
        int argumentsStart = query.indexOf("(", articlesName);
        int argumentsEnd = matchingParen(query, argumentsStart);
        return skipInputValue(query, valueStart, argumentsEnd);
    }

    private static int skipInputValue(String query, int position, int limit) {
        position = nextNonWhitespace(query, position, limit);
        if (position >= limit) {
            return position;
        }
        char c = query.charAt(position);
        if (c == '{') {
            return matchingBrace(query, position) + 1;
        }
        if (c == '[') {
            return matchingBracket(query, position) + 1;
        }
        if (c == '"') {
            position++;
            while (position < limit) {
                if (query.charAt(position) == '"' && query.charAt(position - 1) != '\\') {
                    return position + 1;
                }
                position++;
            }
            return position;
        }
        while (position < limit && query.charAt(position) != ',' && query.charAt(position) != '}'
                && query.charAt(position) != ']' && query.charAt(position) != ')') {
            position++;
        }
        return position;
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t';
    }

    private static String renderArticleJson(String query, long articleId, String actorRole) {
        return "{\"data\":{\"" + rootResponseKey(query) + "\":" + renderArticleObjectJson(query, articleId, actorRole) + "}}";
    }

    private static String renderArticlesConnectionJson(
            String query,
            long limit,
            long after,
            long before,
            boolean backward,
            long authorIdFilter,
            String actorRole,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility
    ) {
        String json = "{\"data\":{\"" + rootResponseKey(query) + "\":{";
        boolean firstConnection = true;
        if (articlesConnectionFieldRequested(query, "edges")) {
            json = json + "\"" + responseKeyInRootSelection(query, "edges") + "\":[";
            long emitted = 0L;
            long skipped = 0L;
            long skip = backward
                    ? articlesWindowCount(
                    query,
                    after,
                    before,
                    authorIdFilter,
                    enablePublishedVisibility,
                    hasArticleVisibility,
                    articleVisibility
            ) - limit
                    : 0L;
            if (skip < 0L) {
                skip = 0L;
            }
            long firstArticleId = orderedArticleId(query, 0);
            long secondArticleId = orderedArticleId(query, 1);
            if (articleInWindow(query, firstArticleId, after, before, authorIdFilter,
                    enablePublishedVisibility, hasArticleVisibility, articleVisibility)) {
                if (skipped < skip) {
                    skipped++;
                } else if (emitted < limit) {
                    json = json + renderArticleEdgeJson(query, firstArticleId, actorRole);
                    emitted++;
                }
            }
            if (articleInWindow(query, secondArticleId, after, before, authorIdFilter,
                    enablePublishedVisibility, hasArticleVisibility, articleVisibility)) {
                if (skipped < skip) {
                    skipped++;
                } else if (emitted < limit) {
                    if (emitted > 0L) {
                        json = json + ",";
                    }
                    json = json + renderArticleEdgeJson(query, secondArticleId, actorRole);
                    emitted++;
                }
            }
            json = json + "]";
            firstConnection = false;
        }
        if (articlesConnectionFieldRequested(query, "totalCount")) {
            if (firstConnection == false) {
                json = json + ",";
            }
            json = json + "\"" + responseKeyInRootSelection(query, "totalCount") + "\":"
                    + articlesVisibleCount(
                    query,
                    authorIdFilter,
                    enablePublishedVisibility,
                    hasArticleVisibility,
                    articleVisibility
            );
            firstConnection = false;
        }
        if (articlesConnectionFieldRequested(query, "pageInfo")) {
            if (firstConnection == false) {
                json = json + ",";
            }
            json = json + "\"" + responseKeyInRootSelection(query, "pageInfo") + "\":"
                    + renderArticlePageInfoJson(
                    query,
                    limit,
                    after,
                    before,
                    backward,
                    authorIdFilter,
                    enablePublishedVisibility,
                    hasArticleVisibility,
                    articleVisibility
            );
        }
        return json + "}}}";
    }

    private static String renderArticleEdgeJson(String query, long articleId, String actorRole) {
        String json = "{";
        boolean first = true;
        if (articleEdgeFieldRequested(query, "cursor")) {
            json = json + "\"" + responseKeyInArticleEdgeSelection(query, "cursor") + "\":\"article:" + articleId + "\"";
            first = false;
        }
        if (articleEdgeFieldRequested(query, "node")) {
            if (first == false) {
                json = json + ",";
            }
            json = json + "\"" + responseKeyInArticleEdgeSelection(query, "node") + "\":"
                    + renderArticleObjectJson(query, articleId, actorRole);
        }
        return json + "}";
    }

    private static String renderArticlePageInfoJson(
            String query,
            long limit,
            long after,
            long before,
            boolean backward,
            long authorIdFilter,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility
    ) {
        long total = articlesWindowCount(
                query,
                after,
                before,
                authorIdFilter,
                enablePublishedVisibility,
                hasArticleVisibility,
                articleVisibility
        );
        String json = "{";
        boolean first = true;
        if (articlePageInfoFieldRequested(query, "hasNextPage")) {
            json = json + "\"" + responseKeyInArticlePageInfoSelection(query, "hasNextPage") + "\":"
                    + articlesHasNextPage(
                    query,
                    total,
                    limit,
                    before,
                    backward,
                    authorIdFilter,
                    enablePublishedVisibility,
                    hasArticleVisibility,
                    articleVisibility
            );
            first = false;
        }
        if (articlePageInfoFieldRequested(query, "hasPreviousPage")) {
            if (first == false) {
                json = json + ",";
            }
            json = json + "\"" + responseKeyInArticlePageInfoSelection(query, "hasPreviousPage") + "\":"
                    + articlesHasPreviousPage(
                    query,
                    total,
                    limit,
                    after,
                    backward,
                    authorIdFilter,
                    enablePublishedVisibility,
                    hasArticleVisibility,
                    articleVisibility
            );
            first = false;
        }
        if (articlePageInfoFieldRequested(query, "startCursor")) {
            if (first == false) {
                json = json + ",";
            }
            json = json + "\"" + responseKeyInArticlePageInfoSelection(query, "startCursor") + "\":"
                    + nullableArticleCursor(articlesPageCursor(
                    query,
                    limit,
                    after,
                    before,
                    backward,
                    authorIdFilter,
                    true,
                    enablePublishedVisibility,
                    hasArticleVisibility,
                    articleVisibility
            ));
            first = false;
        }
        if (articlePageInfoFieldRequested(query, "endCursor")) {
            if (first == false) {
                json = json + ",";
            }
            json = json + "\"" + responseKeyInArticlePageInfoSelection(query, "endCursor") + "\":"
                    + nullableArticleCursor(articlesPageCursor(
                    query,
                    limit,
                    after,
                    before,
                    backward,
                    authorIdFilter,
                    false,
                    enablePublishedVisibility,
                    hasArticleVisibility,
                    articleVisibility
            ));
        }
        return json + "}";
    }

    private static String nullableArticleCursor(long articleId) {
        if (articleId < 0L) {
            return "null";
        }
        return "\"article:" + articleId + "\"";
    }

    private static boolean articleInWindow(
            String query,
            long articleId,
            long after,
            long before,
            long authorIdFilter,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility
    ) {
        long authorId = articleId == 1L ? 10L : 11L;
        return articleMatchesPublishedVisibility(articleId, enablePublishedVisibility, hasArticleVisibility, articleVisibility)
                && (authorIdFilter < 0L || authorIdFilter == authorId)
                && generatedArticleFilterMatches(query, articleId)
                && articleAfterCursor(query, articleId, after)
                && articleBeforeCursor(query, articleId, before);
    }

    private static boolean articleAfterCursor(String query, long articleId, long after) {
        return after < 0L || compareArticleOrder(query, articleId, after) > 0;
    }

    private static boolean articleBeforeCursor(String query, long articleId, long before) {
        return before < 0L || compareArticleOrder(query, articleId, before) < 0;
    }

    private static boolean articleMatchesPublishedVisibility(
            long articleId,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility
    ) {
        if (enablePublishedVisibility == false) {
            return true;
        }
        if (hasArticleVisibility == false) {
            return false;
        }
        return articlePublished(articleId) == articleVisibility;
    }

    private static boolean articlePublished(long articleId) {
        return articleId == 1L;
    }

    private static long articlesWindowCount(
            String query,
            long after,
            long before,
            long authorIdFilter,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility
    ) {
        long count = 0L;
        if (articleInWindow(query, 1L, after, before, authorIdFilter,
                enablePublishedVisibility, hasArticleVisibility, articleVisibility)) {
            count++;
        }
        if (articleInWindow(query, 2L, after, before, authorIdFilter,
                enablePublishedVisibility, hasArticleVisibility, articleVisibility)) {
            count++;
        }
        return count;
    }

    private static long articlesVisibleCount(
            String query,
            long authorIdFilter,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility
    ) {
        return articlesWindowCount(
                query,
                -1L,
                -1L,
                authorIdFilter,
                enablePublishedVisibility,
                hasArticleVisibility,
                articleVisibility
        );
    }

    private static boolean articlesHasNextPage(
            String query,
            long total,
            long limit,
            long before,
            boolean backward,
            long authorIdFilter,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility
    ) {
        if (backward) {
            return before >= 0L && articlesAfterOrAtCount(
                    query,
                    before,
                    authorIdFilter,
                    enablePublishedVisibility,
                    hasArticleVisibility,
                    articleVisibility
            ) > 0L;
        }
        return total > limit;
    }

    private static boolean articlesHasPreviousPage(
            String query,
            long total,
            long limit,
            long after,
            boolean backward,
            long authorIdFilter,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility
    ) {
        if (backward) {
            return total > limit;
        }
        return after >= 0L && articlesBeforeOrAtCount(
                query,
                after,
                authorIdFilter,
                enablePublishedVisibility,
                hasArticleVisibility,
                articleVisibility
        ) > 0L;
    }

    private static long articlesBeforeOrAtCount(
            String query,
            long cursor,
            long authorIdFilter,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility
    ) {
        long count = 0L;
        if (articleMatchesCursorSide(query, 1L, cursor, false, authorIdFilter,
                enablePublishedVisibility, hasArticleVisibility, articleVisibility)) {
            count++;
        }
        if (articleMatchesCursorSide(query, 2L, cursor, false, authorIdFilter,
                enablePublishedVisibility, hasArticleVisibility, articleVisibility)) {
            count++;
        }
        return count;
    }

    private static long articlesAfterOrAtCount(
            String query,
            long cursor,
            long authorIdFilter,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility
    ) {
        long count = 0L;
        if (articleMatchesCursorSide(query, 1L, cursor, true, authorIdFilter,
                enablePublishedVisibility, hasArticleVisibility, articleVisibility)) {
            count++;
        }
        if (articleMatchesCursorSide(query, 2L, cursor, true, authorIdFilter,
                enablePublishedVisibility, hasArticleVisibility, articleVisibility)) {
            count++;
        }
        return count;
    }

    private static boolean articleMatchesCursorSide(
            String query,
            long articleId,
            long cursor,
            boolean afterOrAt,
            long authorIdFilter,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility
    ) {
        long authorId = articleId == 1L ? 10L : 11L;
        if (articleMatchesPublishedVisibility(articleId, enablePublishedVisibility, hasArticleVisibility, articleVisibility) == false
                || (authorIdFilter >= 0L && authorIdFilter != authorId)
                || generatedArticleFilterMatches(query, articleId) == false) {
            return false;
        }
        int comparison = compareArticleOrder(query, articleId, cursor);
        return afterOrAt ? comparison >= 0 : comparison <= 0;
    }

    private static long articlesPageCursor(
            String query,
            long limit,
            long after,
            long before,
            boolean backward,
            long authorIdFilter,
            boolean first,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility
    ) {
        long count = 0L;
        long total = articlesWindowCount(
                query,
                after,
                before,
                authorIdFilter,
                enablePublishedVisibility,
                hasArticleVisibility,
                articleVisibility
        );
        long skip = backward ? total - limit : 0L;
        if (skip < 0L) {
            skip = 0L;
        }
        long selected = -1L;
        long firstArticleId = orderedArticleId(query, 0);
        long secondArticleId = orderedArticleId(query, 1);
        selected = selectArticleCursor(query, firstArticleId, after, before, authorIdFilter, skip, count, limit,
                selected, first, enablePublishedVisibility, hasArticleVisibility, articleVisibility);
        if (articleInWindow(query, firstArticleId, after, before, authorIdFilter,
                enablePublishedVisibility, hasArticleVisibility, articleVisibility)) {
            count++;
        }
        selected = selectArticleCursor(query, secondArticleId, after, before, authorIdFilter, skip, count, limit,
                selected, first, enablePublishedVisibility, hasArticleVisibility, articleVisibility);
        return selected;
    }

    private static long selectArticleCursor(
            String query,
            long articleId,
            long after,
            long before,
            long authorIdFilter,
            long skip,
            long count,
            long limit,
            long current,
            boolean first,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility
    ) {
        if (articleInWindow(query, articleId, after, before, authorIdFilter,
                enablePublishedVisibility, hasArticleVisibility, articleVisibility) == false) {
            return current;
        }
        if (count < skip || count - skip >= limit) {
            return current;
        }
        if (first && current < 0L) {
            return articleId;
        }
        if (first == false) {
            return articleId;
        }
        return current;
    }

    private static long orderedArticleId(String query, int index) {
        int comparison = compareArticleOrder(query, 1L, 2L);
        if (index == 0) {
            return comparison <= 0 ? 1L : 2L;
        }
        return comparison <= 0 ? 2L : 1L;
    }

    private static int compareArticleOrder(String query, long leftArticleId, long rightArticleId) {
        int valueStart = topLevelArgumentValueStart(query, "orderBy");
        if (valueStart < 0) {
            return compareLong(leftArticleId, rightArticleId);
        }
        int listEnd = matchingBracket(query, valueStart);
        int position = valueStart + 1;
        while (position < listEnd) {
            position = nextNonWhitespace(query, position, listEnd);
            if (position >= listEnd) {
                position = listEnd;
            } else {
                int objectEnd = matchingBrace(query, position);
                int fieldStart = nextNonWhitespace(query, position + 1, objectEnd);
                int fieldEnd = fieldStart;
                while (fieldEnd < objectEnd && isNamePart(query.charAt(fieldEnd))) {
                    fieldEnd++;
                }
                String fieldName = query.substring(fieldStart, fieldEnd);
                int separator = nextNonWhitespace(query, fieldEnd, objectEnd);
                int directionStart = nextNonWhitespace(query, separator + 1, objectEnd);
                boolean descending = isExactNameAt(query, "DESC", directionStart);
                int comparison = compareArticleOrderField(leftArticleId, rightArticleId, fieldName);
                if (comparison != 0) {
                    return descending ? -comparison : comparison;
                }
                position = nextNonWhitespace(query, objectEnd + 1, listEnd);
                if (position < listEnd && query.charAt(position) == ',') {
                    position++;
                }
            }
        }
        return compareLong(leftArticleId, rightArticleId);
    }

    private static int compareArticleOrderField(long leftArticleId, long rightArticleId, String fieldName) {
        if ("title".equals(fieldName)) {
            return articleTitle(leftArticleId).compareTo(articleTitle(rightArticleId));
        }
        if ("titleLength".equals(fieldName)) {
            return compareLong(articleTitle(leftArticleId).length(), articleTitle(rightArticleId).length());
        }
        if ("authorName".equals(fieldName)) {
            return articleAuthorName(leftArticleId).compareTo(articleAuthorName(rightArticleId));
        }
        return compareLong(leftArticleId, rightArticleId);
    }

    private static int compareLong(long left, long right) {
        if (left < right) {
            return -1;
        }
        if (left > right) {
            return 1;
        }
        return 0;
    }

    private static boolean generatedArticleFilterMatches(String query, long articleId) {
        int valueStart = topLevelArgumentValueStart(query, "filter");
        if (valueStart < 0) {
            return true;
        }
        return generatedArticleFilterObjectMatches(query, valueStart, matchingBrace(query, valueStart), articleId);
    }

    private static boolean generatedArticleFilterObjectMatches(
            String query,
            int objectStart,
            int objectEnd,
            long articleId
    ) {
        // GAP-004 iterative form of the previous generatedArticleFilterObjectMatches /
        // generatedArticleFilterListMatches mutual recursion: a small-step evaluator
        // with an explicit string-encoded frame stack that preserves the original
        // evaluation order and short-circuiting exactly. Object contexts are
        // conjunctions over their fields in textual order (first false wins);
        // "and"/"or" values descend into a list context — a conjunction/disjunction
        // over entry objects ("L<all 0|1><resume>,<end>|" frames); "not" values
        // descend into an object context through a negating consumer frame
        // ("O<negate 0|1><resume>,<end>|"). When a context completes, its boolean
        // result is combined through the popped frame: a parent decided by the child
        // result completes immediately (the recursion's early return, so later
        // fields/entries are never evaluated, exactly as before), an undecided
        // parent resumes at the stored position. Resume positions are the parent's
        // post-value advance computed before descending — pure position arithmetic
        // over the immutable query text. Scalar operator predicates run at the same
        // textual points in the same order as the recursive evaluator.
        String frames = "";
        boolean inList = false;
        boolean listAll = false;
        boolean haveResult = false;
        boolean result = false;
        int end = objectEnd;
        int position = objectStart + 1;
        while (position >= 0) {
            if (haveResult) {
                if (frames.isEmpty()) {
                    return result;
                }
                String frame = frames.substring(0, frames.indexOf("|"));
                frames = frames.substring(frames.indexOf("|") + 1);
                boolean frameFlag = frame.charAt(1) == '1';
                int comma = frame.indexOf(",");
                int resume = traversalFrameNumber(frame.substring(2, comma));
                int frameEnd = traversalFrameNumber(frame.substring(comma + 1));
                if (frame.charAt(0) == 'O') {
                    boolean matches = result;
                    if (frameFlag) {
                        matches = result == false;
                    }
                    if (matches == false) {
                        result = false;
                    } else {
                        haveResult = false;
                        inList = false;
                        position = resume;
                        end = frameEnd;
                    }
                } else if (frameFlag && result == false) {
                    result = false;
                } else if (frameFlag == false && result) {
                    result = true;
                } else {
                    haveResult = false;
                    inList = true;
                    listAll = frameFlag;
                    position = resume;
                    end = frameEnd;
                }
            } else if (inList) {
                if (position < end) {
                    position = nextNonWhitespace(query, position, end);
                }
                if (position >= end) {
                    result = listAll;
                    haveResult = true;
                } else {
                    int entryEnd = matchingBrace(query, position);
                    int resume = nextNonWhitespace(query, entryEnd + 1, end);
                    if (resume < end && query.charAt(resume) == ',') {
                        resume++;
                    }
                    String allFlag = "0";
                    if (listAll) {
                        allFlag = "1";
                    }
                    frames = "L" + allFlag + resume + "," + end + "|" + frames;
                    end = entryEnd;
                    position = position + 1;
                    inList = false;
                }
            } else {
                if (position < end) {
                    position = nextNonWhitespace(query, position, end);
                }
                if (position >= end) {
                    result = true;
                    haveResult = true;
                } else {
                    int nameStart = position;
                    while (position < end && isNamePart(query.charAt(position))) {
                        position++;
                    }
                    String name = query.substring(nameStart, position);
                    int separator = nextNonWhitespace(query, position, end);
                    int valueStart = nextNonWhitespace(query, separator + 1, end);
                    int valueEnd = skipInputValue(query, valueStart, end);
                    int resume = nextNonWhitespace(query, valueEnd, end);
                    if (resume < end && query.charAt(resume) == ',') {
                        resume++;
                    }
                    if ("and".equals(name) || "or".equals(name)) {
                        frames = "O0" + resume + "," + end + "|" + frames;
                        listAll = "and".equals(name);
                        end = matchingBracket(query, valueStart);
                        position = valueStart + 1;
                        inList = true;
                    } else if ("not".equals(name)) {
                        frames = "O1" + resume + "," + end + "|" + frames;
                        end = matchingBrace(query, valueStart);
                        position = valueStart + 1;
                    } else {
                        boolean matches = generatedArticleScalarFilterMatches(query, name, valueStart, valueEnd, articleId);
                        if (matches == false) {
                            result = false;
                            haveResult = true;
                        } else {
                            position = resume;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean generatedArticleScalarFilterMatches(
            String query,
            String fieldName,
            int objectStart,
            int objectEnd,
            long articleId
    ) {
        int scalarEnd = matchingBrace(query, objectStart);
        int position = objectStart + 1;
        while (position < scalarEnd) {
            position = nextNonWhitespace(query, position, scalarEnd);
            if (position >= scalarEnd) {
                return true;
            }
            int operatorStart = position;
            while (position < scalarEnd && isNamePart(query.charAt(position))) {
                position++;
            }
            String operator = query.substring(operatorStart, position);
            int separator = nextNonWhitespace(query, position, scalarEnd);
            int valueStart = nextNonWhitespace(query, separator + 1, scalarEnd);
            int valueEnd = skipInputValue(query, valueStart, scalarEnd);
            boolean matches = generatedArticleScalarOperatorMatches(query, fieldName, operator, valueStart, valueEnd, articleId);
            if (matches == false) {
                return false;
            }
            position = nextNonWhitespace(query, valueEnd, scalarEnd);
            if (position < scalarEnd && query.charAt(position) == ',') {
                position++;
            }
        }
        return true;
    }

    private static boolean generatedArticleScalarOperatorMatches(
            String query,
            String fieldName,
            String operator,
            int valueStart,
            int valueEnd,
            long articleId
    ) {
        if ("isNull".equals(operator)) {
            return isExactNameAt(query, "false", valueStart);
        }
        if ("in".equals(operator)) {
            boolean contains = generatedArticleInListMatches(query, fieldName, valueStart, articleId);
            return contains;
        }
        if ("contains".equals(operator)) {
            return articleStringFilterValue(fieldName, articleId).contains(stringLiteralValue(query, valueStart, valueEnd));
        }
        if ("startsWith".equals(operator)) {
            return articleStringFilterValue(fieldName, articleId).startsWith(stringLiteralValue(query, valueStart, valueEnd));
        }
        if ("endsWith".equals(operator)) {
            return articleStringFilterValue(fieldName, articleId).endsWith(stringLiteralValue(query, valueStart, valueEnd));
        }
        if ("lt".equals(operator) || "lte".equals(operator) || "gt".equals(operator) || "gte".equals(operator)) {
            long actual = articleIntFilterValue(fieldName, articleId);
            long expected = integerLiteralValue(query, valueStart, valueEnd);
            if ("lt".equals(operator)) {
                return actual < expected;
            }
            if ("lte".equals(operator)) {
                return actual <= expected;
            }
            if ("gt".equals(operator)) {
                return actual > expected;
            }
            return actual >= expected;
        }
        boolean equals = generatedArticleScalarValueEquals(query, fieldName, valueStart, valueEnd, articleId);
        return "neq".equals(operator) ? equals == false : equals;
    }

    private static boolean generatedArticleInListMatches(String query, String fieldName, int listStart, long articleId) {
        int listEnd = matchingBracket(query, listStart);
        int position = listStart + 1;
        while (position < listEnd) {
            position = nextNonWhitespace(query, position, listEnd);
            if (position >= listEnd) {
                return false;
            }
            int itemEnd = skipInputValue(query, position, listEnd);
            if (generatedArticleScalarValueEquals(query, fieldName, position, itemEnd, articleId)) {
                return true;
            }
            position = nextNonWhitespace(query, itemEnd, listEnd);
            if (position < listEnd && query.charAt(position) == ',') {
                position++;
            }
        }
        return false;
    }

    private static boolean generatedArticleScalarValueEquals(
            String query,
            String fieldName,
            int valueStart,
            int valueEnd,
            long articleId
    ) {
        if (isExactNameAt(query, "null", valueStart)) {
            return false;
        }
        if ("title".equals(fieldName) || "authorName".equals(fieldName)) {
            return articleStringFilterValue(fieldName, articleId).equals(stringLiteralValue(query, valueStart, valueEnd));
        }
        long expected = integerLiteralValue(query, valueStart, valueEnd);
        if ("id".equals(fieldName)) {
            return articleId == expected;
        }
        if ("titleLength".equals(fieldName)) {
            return articleTitle(articleId).length() == expected;
        }
        return articleAuthorId(articleId) == expected;
    }

    private static long articleIntFilterValue(String fieldName, long articleId) {
        if ("id".equals(fieldName)) {
            return articleId;
        }
        if ("titleLength".equals(fieldName)) {
            return articleTitle(articleId).length();
        }
        return articleAuthorId(articleId);
    }

    private static String articleStringFilterValue(String fieldName, long articleId) {
        if ("authorName".equals(fieldName)) {
            return articleAuthorName(articleId);
        }
        return articleTitle(articleId);
    }

    private static long articleAuthorId(long articleId) {
        return articleId == 1L ? 10L : 11L;
    }

    private static String articleTitle(long articleId) {
        return articleId == 1L ? "Titan GraphQL proof" : "Stored functions as APIs";
    }

    private static String articleAuthorName(long articleId) {
        return articleId == 1L ? "Ada Lovelace" : "Grace Hopper";
    }

    private static long integerLiteralValue(String query, int valueStart, int valueEnd) {
        long value = 0L;
        int position = valueStart;
        while (position < valueEnd && query.charAt(position) >= '0' && query.charAt(position) <= '9') {
            value = value * 10L + query.charAt(position) - '0';
            position++;
        }
        return value;
    }

    private static boolean isIntegerLiteral(String query, int valueStart, int valueEnd) {
        valueStart = nextNonWhitespace(query, valueStart, valueEnd);
        while (valueEnd > valueStart && isWhitespace(query.charAt(valueEnd - 1))) {
            valueEnd--;
        }
        if (valueStart >= valueEnd) {
            return false;
        }
        int position = valueStart;
        while (position < valueEnd) {
            char c = query.charAt(position);
            if (c < '0' || c > '9') {
                return false;
            }
            position++;
        }
        return true;
    }

    private static String stringLiteralValue(String query, int valueStart, int valueEnd) {
        if (valueStart >= valueEnd || query.charAt(valueStart) != '"') {
            return "";
        }
        return query.substring(valueStart + 1, valueEnd - 1);
    }

    private static String renderArticlesJson(String query, long limit, long authorIdFilter, String actorRole) {
        String json = "{\"data\":{\"articles\":[";
        long articleId = 1L;
        long count = 0L;
        boolean first = true;
        while (articleId <= 2L && count < limit) {
            long authorId = articleId == 1L ? 10L : 11L;
            if (authorIdFilter < 0L || authorIdFilter == authorId) {
                if (first == false) {
                    json = json + ",";
                }
                json = json + renderArticleObjectJson(query, articleId, actorRole);
                first = false;
                count++;
            }
            articleId++;
        }
        return json + "]}}";
    }

    private static String renderArticleObjectJson(String query, long articleId, String actorRole) {
        String articleTitle = articleId == 1L ? "Titan GraphQL proof" : "Stored functions as APIs";
        long authorId = articleId == 1L ? 10L : 11L;
        String authorName = articleId == 1L ? "Ada Lovelace" : "Grace Hopper";
        String authorEmail = articleId == 1L ? "ada@example.test" : "grace@example.test";

        String json = "{";
        boolean first = true;
        if (articleFieldRequested(query, "__typename")) {
            json = json + typenameFieldsJson(query, articleObjectSelectionStart(query), "Article");
            first = false;
        }
        if (articleFieldRequested(query, "id")) {
            json = json + "\"" + responseKeyInArticleSelection(query, "id") + "\":" + articleId;
            first = false;
        }
        if (articleFieldRequested(query, "title")) {
            if (first == false) {
                json = json + ",";
            }
            json = json + "\"" + responseKeyInArticleSelection(query, "title") + "\":\"" + articleTitle + "\"";
            first = false;
        }
        if (articleFieldRequested(query, "titleLength")) {
            if (first == false) {
                json = json + ",";
            }
            json = json + "\"" + responseKeyInArticleSelection(query, "titleLength") + "\":" + articleTitle.length();
            first = false;
        }
        if (articleFieldRequested(query, "author")) {
            if (first == false) {
                json = json + ",";
            }
            json = json + "\"" + responseKeyInArticleSelection(query, "author") + "\":{";
            boolean firstAuthor = true;
            if (authorFieldRequested(query, "__typename")) {
                json = json + typenameFieldsJson(query, articleFieldSelectionStart(query, "author"), "User");
                firstAuthor = false;
            }
            if (authorFieldRequested(query, "id")) {
                json = json + "\"" + responseKeyInAuthorSelection(query, "id") + "\":" + authorId;
                firstAuthor = false;
            }
            if (authorFieldRequested(query, "name")) {
                if (firstAuthor == false) {
                    json = json + ",";
                }
                json = json + "\"" + responseKeyInAuthorSelection(query, "name") + "\":\"" + authorName + "\"";
                firstAuthor = false;
            }
            if (authorFieldRequested(query, "email") && isAdmin(actorRole)) {
                if (firstAuthor == false) {
                    json = json + ",";
                }
                json = json + "\"" + responseKeyInAuthorSelection(query, "email") + "\":\"" + authorEmail + "\"";
            }
            json = json + "}";
            first = false;
        }
        if (articleFieldRequested(query, "comments")) {
            if (first == false) {
                json = json + ",";
            }
            json = json + "\"" + responseKeyInArticleSelection(query, "comments") + "\":"
                    + renderCommentsJson(query, articleId, actorRole);
        }
        return json + "}";
    }

    private static String renderCommentsJson(String query, long articleId, String actorRole) {
        boolean backward = commentsHasArgument(query, "last");
        int limit = commentsPageSize(query);
        long after = commentsCursorArgument(query, "after");
        long before = commentsCursorArgument(query, "before");
        String json = "{";
        boolean firstConnection = true;
        if (commentsConnectionFieldRequested(query, "edges")) {
            json = json + "\"" + responseKeyInCommentsSelection(query, "edges") + "\":[";
            long emitted = 0L;
            long skipped = 0L;
            long skip = backward ? commentsWindowCount(articleId, after, before) - limit : 0L;
            if (skip < 0L) {
                skip = 0L;
            }
            if (articleId == 1L) {
                if (commentInWindow(100L, after, before) && skipped < skip) {
                    skipped++;
                } else if (commentInWindow(100L, after, before) && emitted < limit) {
                    json = json + renderCommentEdgeJson(query, 100L, "First comment", actorRole);
                    emitted++;
                }
                if (commentInWindow(101L, after, before) && skipped < skip) {
                    skipped++;
                } else if (commentInWindow(101L, after, before) && emitted < limit) {
                    if (emitted > 0L) {
                        json = json + ",";
                    }
                    json = json + renderCommentEdgeJson(query, 101L, "Second comment", actorRole);
                    emitted++;
                }
            }
            if (articleId == 2L) {
                if (commentInWindow(102L, after, before) && skipped < skip) {
                    skipped++;
                } else if (commentInWindow(102L, after, before) && emitted < limit) {
                    json = json + renderCommentEdgeJson(query, 102L, "API comment", actorRole);
                }
            }
            json = json + "]";
            firstConnection = false;
        }
        if (commentsConnectionFieldRequested(query, "totalCount")) {
            if (firstConnection == false) {
                json = json + ",";
            }
            json = json + "\"" + responseKeyInCommentsSelection(query, "totalCount") + "\":"
                    + commentsTotalCount(articleId);
            firstConnection = false;
        }
        if (commentsConnectionFieldRequested(query, "pageInfo")) {
            if (firstConnection == false) {
                json = json + ",";
            }
            json = json + "\"" + responseKeyInCommentsSelection(query, "pageInfo") + "\":"
                    + renderCommentPageInfoJson(query, articleId, limit, after, before, backward);
        }
        return json + "}";
    }

    private static String renderCommentEdgeJson(String query, long commentId, String body, String actorRole) {
        String json = "{";
        boolean first = true;
        if (commentEdgeFieldRequested(query, "cursor")) {
            json = json + "\"" + responseKeyInCommentEdgeSelection(query, "cursor") + "\":\"comment:" + commentId + "\"";
            first = false;
        }
        if (commentEdgeFieldRequested(query, "node")) {
            if (first == false) {
                json = json + ",";
            }
            json = json + "\"" + responseKeyInCommentEdgeSelection(query, "node") + "\":"
                    + renderCommentObjectJson(query, commentId, body, actorRole);
        }
        return json + "}";
    }

    private static String renderCommentPageInfoJson(
            String query,
            long articleId,
            int limit,
            long after,
            long before,
            boolean backward
    ) {
        long total = commentsWindowCount(articleId, after, before);
        long skipped = backward ? total - limit : 0L;
        if (skipped < 0L) {
            skipped = 0L;
        }
        long visible = total - skipped;
        if (visible > limit) {
            visible = limit;
        }
        String json = "{";
        boolean first = true;
        if (commentPageInfoFieldRequested(query, "hasNextPage")) {
            json = json + "\"" + responseKeyInCommentPageInfoSelection(query, "hasNextPage") + "\":"
                    + commentsHasNextPage(articleId, total, limit, before, backward);
            first = false;
        }
        if (commentPageInfoFieldRequested(query, "hasPreviousPage")) {
            if (first == false) {
                json = json + ",";
            }
            json = json + "\"" + responseKeyInCommentPageInfoSelection(query, "hasPreviousPage") + "\":"
                    + commentsHasPreviousPage(articleId, total, limit, after, backward);
            first = false;
        }
        if (commentPageInfoFieldRequested(query, "startCursor")) {
            if (first == false) {
                json = json + ",";
            }
            json = json + "\"" + responseKeyInCommentPageInfoSelection(query, "startCursor") + "\":"
                    + nullableCommentCursor(commentsPageCursor(articleId, limit, after, before, backward, true));
            first = false;
        }
        if (commentPageInfoFieldRequested(query, "endCursor")) {
            if (first == false) {
                json = json + ",";
            }
            json = json + "\"" + responseKeyInCommentPageInfoSelection(query, "endCursor") + "\":"
                    + nullableCommentCursor(commentsPageCursor(articleId, limit, after, before, backward, false));
        }
        return json + "}";
    }

    private static String nullableCommentCursor(long commentId) {
        if (commentId < 0L) {
            return "null";
        }
        return "\"comment:" + commentId + "\"";
    }

    private static int commentsPageSize(String query) {
        return commentsHasArgument(query, "last")
                ? commentsIntArgument(query, "last", 10)
                : commentsIntArgument(query, "first", 10);
    }

    private static int commentsIntArgument(String query, String argumentName, int defaultValue) {
        int commentsName = indexOfName(query, "comments", 0);
        if (commentsName < 0) {
            return defaultValue;
        }
        int argumentsStart = query.indexOf("(", commentsName);
        int selectionStart = query.indexOf("{", commentsName);
        if (argumentsStart < 0 || (selectionStart >= 0 && selectionStart < argumentsStart)) {
            return defaultValue;
        }
        int argumentsEnd = query.indexOf(")", argumentsStart);
        if (argumentsEnd < 0) {
            return defaultValue;
        }
        int argument = indexOfName(query, argumentName, argumentsStart);
        if (argument < 0 || argument > argumentsEnd) {
            return defaultValue;
        }
        int separator = query.indexOf(":", argument);
        if (separator < 0 || separator > argumentsEnd) {
            return defaultValue;
        }
        int position = separator + 1;
        while (position < query.length() && isWhitespace(query.charAt(position))) {
            position++;
        }
        int value = 0;
        boolean sawDigit = false;
        boolean reading = true;
        while (position < query.length() && reading) {
            char c = query.charAt(position);
            if (c < '0' || c > '9') {
                reading = false;
            } else {
                sawDigit = true;
                value = value * 10 + c - '0';
                position++;
            }
        }
        return sawDigit ? value : defaultValue;
    }

    private static boolean commentsHasArgument(String query, String argumentName) {
        int commentsName = indexOfName(query, "comments", 0);
        if (commentsName < 0) {
            return false;
        }
        int argumentsStart = query.indexOf("(", commentsName);
        int selectionStart = query.indexOf("{", commentsName);
        if (argumentsStart < 0 || (selectionStart >= 0 && selectionStart < argumentsStart)) {
            return false;
        }
        int argumentsEnd = query.indexOf(")", argumentsStart);
        if (argumentsEnd < 0) {
            return false;
        }
        int argument = indexOfName(query, argumentName, argumentsStart);
        return argument >= 0 && argument < argumentsEnd;
    }

    private static long commentsCursorArgument(String query, String argumentName) {
        int commentsName = indexOfName(query, "comments", 0);
        if (commentsName < 0) {
            return -1L;
        }
        int argumentsStart = query.indexOf("(", commentsName);
        int selectionStart = query.indexOf("{", commentsName);
        if (argumentsStart < 0 || (selectionStart >= 0 && selectionStart < argumentsStart)) {
            return -1L;
        }
        int argumentsEnd = query.indexOf(")", argumentsStart);
        if (argumentsEnd < 0) {
            return -2L;
        }
        int argument = indexOfName(query, argumentName, argumentsStart);
        if (argument < 0 || argument > argumentsEnd) {
            return -1L;
        }
        int separator = query.indexOf(":", argument);
        if (separator < 0 || separator > argumentsEnd) {
            return -2L;
        }
        int position = separator + 1;
        while (position < argumentsEnd && isWhitespace(query.charAt(position))) {
            position++;
        }
        if (position >= argumentsEnd || query.charAt(position) != '"') {
            return -2L;
        }
        position++;
        String prefix = "comment:";
        int prefixPosition = 0;
        while (prefixPosition < prefix.length()) {
            if (position >= argumentsEnd || query.charAt(position) != prefix.charAt(prefixPosition)) {
                return -2L;
            }
            position++;
            prefixPosition++;
        }
        long value = 0L;
        boolean sawDigit = false;
        while (position < argumentsEnd && query.charAt(position) >= '0' && query.charAt(position) <= '9') {
            sawDigit = true;
            value = value * 10L + query.charAt(position) - '0';
            position++;
        }
        if (sawDigit == false || position >= argumentsEnd || query.charAt(position) != '"') {
            return -2L;
        }
        return value;
    }

    private static boolean commentInWindow(long commentId, long after, long before) {
        return (after < 0L || commentId > after) && (before < 0L || commentId < before);
    }

    private static long commentsTotalCount(long articleId) {
        long count = 0L;
        if (articleId == 1L) {
            count = count + 2L;
        }
        if (articleId == 2L) {
            count = count + 1L;
        }
        return count;
    }

    private static long commentsWindowCount(long articleId, long after, long before) {
        long count = 0L;
        if (articleId == 1L && commentInWindow(100L, after, before)) {
            count++;
        }
        if (articleId == 1L && commentInWindow(101L, after, before)) {
            count++;
        }
        if (articleId == 2L && commentInWindow(102L, after, before)) {
            count++;
        }
        return count;
    }

    private static boolean commentsHasNextPage(long articleId, long total, int limit, long before, boolean backward) {
        if (backward) {
            return before >= 0L && commentsAfterOrAtCount(articleId, before) > 0L;
        }
        return total > limit;
    }

    private static boolean commentsHasPreviousPage(long articleId, long total, int limit, long after, boolean backward) {
        if (backward) {
            return total > limit;
        }
        return after >= 0L && commentsBeforeOrAtCount(articleId, after) > 0L;
    }

    private static long commentsBeforeOrAtCount(long articleId, long cursor) {
        long count = 0L;
        if (articleId == 1L && 100L <= cursor) {
            count++;
        }
        if (articleId == 1L && 101L <= cursor) {
            count++;
        }
        if (articleId == 2L && 102L <= cursor) {
            count++;
        }
        return count;
    }

    private static long commentsAfterOrAtCount(long articleId, long cursor) {
        long count = 0L;
        if (articleId == 1L && 100L >= cursor) {
            count++;
        }
        if (articleId == 1L && 101L >= cursor) {
            count++;
        }
        if (articleId == 2L && 102L >= cursor) {
            count++;
        }
        return count;
    }

    private static long commentsPageCursor(
            long articleId,
            int limit,
            long after,
            long before,
            boolean backward,
            boolean first
    ) {
        long count = 0L;
        long total = commentsWindowCount(articleId, after, before);
        long skip = backward ? total - limit : 0L;
        if (skip < 0L) {
            skip = 0L;
        }
        long selected = -1L;
        if (articleId == 1L) {
            selected = selectCommentCursor(100L, after, before, skip, count, limit, selected, first);
            if (commentInWindow(100L, after, before)) {
                count++;
            }
            selected = selectCommentCursor(101L, after, before, skip, count, limit, selected, first);
        } else if (articleId == 2L) {
            selected = selectCommentCursor(102L, after, before, skip, count, limit, selected, first);
        }
        return selected;
    }

    private static long selectCommentCursor(
            long commentId,
            long after,
            long before,
            long skip,
            long count,
            int limit,
            long current,
            boolean first
    ) {
        if (commentInWindow(commentId, after, before) == false) {
            return current;
        }
        if (count < skip || count - skip >= limit) {
            return current;
        }
        if (first && current < 0L) {
            return commentId;
        }
        if (first == false) {
            return commentId;
        }
        return current;
    }

    private static String renderCommentObjectJson(String query, long commentId, String body, String actorRole) {
        long authorId = commentId == 100L ? 11L : 10L;
        String authorName = commentId == 100L ? "Grace Hopper" : "Ada Lovelace";
        String authorEmail = commentId == 100L ? "grace@example.test" : "ada@example.test";

        String json = "{";
        boolean first = true;
        if (commentFieldRequested(query, "__typename")) {
            json = json + typenameFieldsJson(query, commentNodeSelectionStart(query), "Comment");
            first = false;
        }
        if (commentFieldRequested(query, "id")) {
            json = json + "\"" + responseKeyInCommentSelection(query, "id") + "\":" + commentId;
            first = false;
        }
        if (commentFieldRequested(query, "body")) {
            if (first == false) {
                json = json + ",";
            }
            json = json + "\"" + responseKeyInCommentSelection(query, "body") + "\":\"" + body + "\"";
            first = false;
        }
        if (commentFieldRequested(query, "author")) {
            if (first == false) {
                json = json + ",";
            }
            json = json + "\"" + responseKeyInCommentSelection(query, "author") + "\":{";
            boolean firstAuthor = true;
            if (commentAuthorFieldRequested(query, "__typename")) {
                json = json + typenameFieldsJson(
                        query,
                        fieldSelectionStartInSelection(query, commentNodeSelectionStart(query), "author"),
                        "User");
                firstAuthor = false;
            }
            if (commentAuthorFieldRequested(query, "id")) {
                json = json + "\"" + responseKeyInCommentAuthorSelection(query, "id") + "\":" + authorId;
                firstAuthor = false;
            }
            if (commentAuthorFieldRequested(query, "name")) {
                if (firstAuthor == false) {
                    json = json + ",";
                }
                json = json + "\"" + responseKeyInCommentAuthorSelection(query, "name") + "\":\"" + authorName + "\"";
                firstAuthor = false;
            }
            if (commentAuthorFieldRequested(query, "email") && isAdmin(actorRole)) {
                if (firstAuthor == false) {
                    json = json + ",";
                }
                json = json + "\"" + responseKeyInCommentAuthorSelection(query, "email") + "\":\"" + authorEmail + "\"";
            }
            json = json + "}";
        }
        return json + "}";
    }

    private static boolean articleFieldRequested(String query, String fieldName) {
        int selectionStart = articleObjectSelectionStart(query);
        if (selectionStart < 0) {
            return false;
        }
        return fieldRequestedInSelection(query, selectionStart, fieldName);
    }

    private static boolean authorFieldRequested(String query, String fieldName) {
        int selectionStart = articleFieldSelectionStart(query, "author");
        if (selectionStart < 0) {
            return false;
        }
        return fieldRequestedInSelection(query, selectionStart, fieldName);
    }

    private static boolean commentFieldRequested(String query, String fieldName) {
        int selectionStart = commentNodeSelectionStart(query);
        if (selectionStart < 0) {
            return false;
        }
        return fieldRequestedInSelection(query, selectionStart, fieldName);
    }

    private static boolean commentAuthorFieldRequested(String query, String fieldName) {
        int commentNodeSelectionStart = commentNodeSelectionStart(query);
        if (commentNodeSelectionStart < 0) {
            return false;
        }
        int authorSelectionStart = fieldSelectionStartInSelection(query, commentNodeSelectionStart, "author");
        if (authorSelectionStart < 0) {
            return false;
        }
        return fieldRequestedInSelection(query, authorSelectionStart, fieldName);
    }

    private static boolean commentsConnectionFieldRequested(String query, String fieldName) {
        int commentsSelectionStart = articleFieldSelectionStart(query, "comments");
        if (commentsSelectionStart < 0) {
            return false;
        }
        return fieldRequestedInSelection(query, commentsSelectionStart, fieldName);
    }

    private static boolean articlesConnectionFieldRequested(String query, String fieldName) {
        int rootStart = indexOfName(query, "articles", 0);
        int selectionStart = rootFieldSelectionStart(query, rootStart);
        if (selectionStart < 0) {
            return false;
        }
        return fieldRequestedInSelection(query, selectionStart, fieldName);
    }

    private static boolean articleEdgeFieldRequested(String query, String fieldName) {
        int rootStart = indexOfName(query, "articles", 0);
        int selectionStart = rootFieldSelectionStart(query, rootStart);
        if (selectionStart < 0) {
            return false;
        }
        int edgeSelectionStart = fieldSelectionStartInSelection(query, selectionStart, "edges");
        if (edgeSelectionStart < 0) {
            return false;
        }
        return fieldRequestedInSelection(query, edgeSelectionStart, fieldName);
    }

    private static boolean articlePageInfoFieldRequested(String query, String fieldName) {
        int rootStart = indexOfName(query, "articles", 0);
        int selectionStart = rootFieldSelectionStart(query, rootStart);
        if (selectionStart < 0) {
            return false;
        }
        int pageInfoSelectionStart = fieldSelectionStartInSelection(query, selectionStart, "pageInfo");
        if (pageInfoSelectionStart < 0) {
            return false;
        }
        return fieldRequestedInSelection(query, pageInfoSelectionStart, fieldName);
    }

    private static boolean commentEdgeFieldRequested(String query, String fieldName) {
        int edgeSelectionStart = commentEdgeSelectionStart(query);
        if (edgeSelectionStart < 0) {
            return false;
        }
        return fieldRequestedInSelection(query, edgeSelectionStart, fieldName);
    }

    private static boolean commentPageInfoFieldRequested(String query, String fieldName) {
        int commentsSelectionStart = articleFieldSelectionStart(query, "comments");
        if (commentsSelectionStart < 0) {
            return false;
        }
        int pageInfoSelectionStart = fieldSelectionStartInSelection(query, commentsSelectionStart, "pageInfo");
        if (pageInfoSelectionStart < 0) {
            return false;
        }
        return fieldRequestedInSelection(query, pageInfoSelectionStart, fieldName);
    }

    private static int commentNodeSelectionStart(String query) {
        int edgeSelectionStart = commentEdgeSelectionStart(query);
        if (edgeSelectionStart < 0) {
            return -1;
        }
        return fieldSelectionStartInSelection(query, edgeSelectionStart, "node");
    }

    private static int commentEdgeSelectionStart(String query) {
        int commentsSelectionStart = articleFieldSelectionStart(query, "comments");
        if (commentsSelectionStart < 0) {
            return -1;
        }
        return fieldSelectionStartInSelection(query, commentsSelectionStart, "edges");
    }

    private static boolean userEmailFieldRequested(String query) {
        return authorFieldRequested(query, "email") || commentAuthorFieldRequested(query, "email");
    }

    private static int articleFieldSelectionStart(String query, String fieldName) {
        int selectionStart = articleObjectSelectionStart(query);
        if (selectionStart < 0) {
            return -1;
        }
        return fieldSelectionStartInSelection(query, selectionStart, fieldName);
    }

    private static int articleObjectSelectionStart(String query) {
        int rootStart = indexOfName(query, rootFieldName(query), 0);
        int selectionStart = rootFieldSelectionStart(query, rootStart);
        if (selectionStart < 0) {
            return -1;
        }
        if ("articles".equals(rootFieldName(query)) == false) {
            return selectionStart;
        }
        int edgeSelectionStart = fieldSelectionStartInSelection(query, selectionStart, "edges");
        if (edgeSelectionStart < 0) {
            return -1;
        }
        return fieldSelectionStartInSelection(query, edgeSelectionStart, "node");
    }

    private static int fieldSelectionStartInSelection(String query, int selectionStart, String fieldName) {
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        int nestedDepth = 0;
        int argumentDepth = 0;
        while (position < selectionEnd) {
            char c = query.charAt(position);
            if (c == '{') {
                nestedDepth++;
                position++;
            } else if (c == '}') {
                if (nestedDepth > 0) {
                    nestedDepth--;
                }
                position++;
            } else if (nestedDepth == 0 && c == '(') {
                argumentDepth++;
                position++;
            } else if (nestedDepth == 0 && c == ')') {
                if (argumentDepth > 0) {
                    argumentDepth--;
                }
                position++;
            } else if (nestedDepth == 0 && argumentDepth == 0 && isNameStart(c)) {
                int nameStart = position;
                while (position < selectionEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                if (fieldName.equals(fieldName(query, nameStart, position, selectionEnd))) {
                    position = fieldNameEnd(query, position, selectionEnd);
                    int nestedSelectionStart = query.indexOf("{", position);
                    if (nestedSelectionStart < 0 || nestedSelectionStart >= selectionEnd) {
                        return -1;
                    }
                    return nestedSelectionStart;
                }
            } else {
                position++;
            }
        }
        return -1;
    }

    private static boolean fieldRequestedInSelection(String query, int selectionStart, String fieldName) {
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        int nestedDepth = 0;
        int argumentDepth = 0;
        while (position < selectionEnd) {
            char c = query.charAt(position);
            if (c == '{') {
                nestedDepth++;
                position++;
            } else if (c == '}') {
                if (nestedDepth > 0) {
                    nestedDepth--;
                }
                position++;
            } else if (nestedDepth == 0 && c == '(') {
                argumentDepth++;
                position++;
            } else if (nestedDepth == 0 && c == ')') {
                if (argumentDepth > 0) {
                    argumentDepth--;
                }
                position++;
            } else if (nestedDepth == 0 && argumentDepth == 0 && isNameStart(c)) {
                int nameStart = position;
                while (position < selectionEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                if (fieldName.equals(fieldName(query, nameStart, position, selectionEnd))) {
                    return true;
                }
            } else {
                position++;
            }
        }
        return false;
    }

    private static String selectionValidationError(String query) {
        String rootName = rootFieldName(query);
        int rootStart = indexOfName(query, rootName, 0);
        int selectionStart = rootFieldSelectionStart(query, rootStart);
        if (selectionStart < 0) {
            return "field '" + rootName + "' requires a selection set";
        }
        if ("articles".equals(rootName)) {
            return articleConnectionSelectionValidationError(query, selectionStart);
        }
        return articleSelectionValidationError(query, selectionStart);
    }

    private static String rootFieldName(String query) {
        int selectionStart = query.indexOf("{");
        if (selectionStart < 0) {
            return "";
        }
        int position = nextNonWhitespace(query, selectionStart + 1, query.length());
        if (position >= query.length() || isNameStart(query.charAt(position)) == false) {
            return "";
        }
        int nameStart = position;
        while (position < query.length() && isNamePart(query.charAt(position))) {
            position++;
        }
        return fieldName(query, nameStart, position, query.length());
    }

    private static String rootResponseKey(String query) {
        int selectionStart = query.indexOf("{");
        if (selectionStart < 0) {
            return "";
        }
        int position = nextNonWhitespace(query, selectionStart + 1, query.length());
        if (position >= query.length() || isNameStart(query.charAt(position)) == false) {
            return "";
        }
        int nameStart = position;
        while (position < query.length() && isNamePart(query.charAt(position))) {
            position++;
        }
        return query.substring(nameStart, position);
    }

    private static String responseKeyInArticleSelection(String query, String fieldName) {
        return responseKeyInSelection(query, articleObjectSelectionStart(query), fieldName);
    }

    private static String responseKeyInAuthorSelection(String query, String fieldName) {
        return responseKeyInSelection(query, articleFieldSelectionStart(query, "author"), fieldName);
    }

    private static String responseKeyInCommentSelection(String query, String fieldName) {
        return responseKeyInSelection(query, commentNodeSelectionStart(query), fieldName);
    }

    private static String responseKeyInCommentAuthorSelection(String query, String fieldName) {
        int commentNodeSelectionStart = commentNodeSelectionStart(query);
        if (commentNodeSelectionStart < 0) {
            return fieldName;
        }
        return responseKeyInSelection(
                query,
                fieldSelectionStartInSelection(query, commentNodeSelectionStart, "author"),
                fieldName);
    }

    private static String responseKeyInRootSelection(String query, String fieldName) {
        int rootStart = indexOfName(query, rootFieldName(query), 0);
        if (rootStart < 0) {
            return fieldName;
        }
        return responseKeyInSelection(query, rootFieldSelectionStart(query, rootStart), fieldName);
    }

    private static String responseKeyInArticleEdgeSelection(String query, String fieldName) {
        int rootStart = indexOfName(query, "articles", 0);
        if (rootStart < 0) {
            return fieldName;
        }
        int selectionStart = rootFieldSelectionStart(query, rootStart);
        if (selectionStart < 0) {
            return fieldName;
        }
        return responseKeyInSelection(query, fieldSelectionStartInSelection(query, selectionStart, "edges"), fieldName);
    }

    private static String responseKeyInArticlePageInfoSelection(String query, String fieldName) {
        int rootStart = indexOfName(query, "articles", 0);
        if (rootStart < 0) {
            return fieldName;
        }
        int selectionStart = rootFieldSelectionStart(query, rootStart);
        if (selectionStart < 0) {
            return fieldName;
        }
        return responseKeyInSelection(query, fieldSelectionStartInSelection(query, selectionStart, "pageInfo"), fieldName);
    }

    private static String responseKeyInCommentsSelection(String query, String fieldName) {
        return responseKeyInSelection(query, articleFieldSelectionStart(query, "comments"), fieldName);
    }

    private static String responseKeyInCommentEdgeSelection(String query, String fieldName) {
        return responseKeyInSelection(query, commentEdgeSelectionStart(query), fieldName);
    }

    private static String responseKeyInCommentPageInfoSelection(String query, String fieldName) {
        int commentsSelectionStart = articleFieldSelectionStart(query, "comments");
        if (commentsSelectionStart < 0) {
            return fieldName;
        }
        return responseKeyInSelection(query, fieldSelectionStartInSelection(query, commentsSelectionStart, "pageInfo"), fieldName);
    }

    private static String typenameFieldsJson(String query, int selectionStart, String typeName) {
        if (selectionStart < 0) {
            return "";
        }
        String json = "";
        boolean first = true;
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        int nestedDepth = 0;
        int argumentDepth = 0;
        while (position < selectionEnd) {
            char c = query.charAt(position);
            if (c == '{') {
                nestedDepth++;
                position++;
            } else if (c == '}') {
                if (nestedDepth > 0) {
                    nestedDepth--;
                }
                position++;
            } else if (nestedDepth == 0 && c == '(') {
                argumentDepth++;
                position++;
            } else if (nestedDepth == 0 && c == ')') {
                if (argumentDepth > 0) {
                    argumentDepth--;
                }
                position++;
            } else if (nestedDepth == 0 && argumentDepth == 0 && isNameStart(c)) {
                int responseStart = position;
                while (position < selectionEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                if ("__typename".equals(fieldName(query, responseStart, position, selectionEnd))) {
                    if (first == false) {
                        json = json + ",";
                    }
                    json = json + "\"" + query.substring(responseStart, position) + "\":\"" + typeName + "\"";
                    first = false;
                }
                position = fieldNameEnd(query, position, selectionEnd);
            } else {
                position++;
            }
        }
        return json;
    }

    private static String responseKeyInSelection(String query, int selectionStart, String fieldName) {
        if (selectionStart < 0) {
            return fieldName;
        }
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        int nestedDepth = 0;
        int argumentDepth = 0;
        while (position < selectionEnd) {
            char c = query.charAt(position);
            if (c == '{') {
                nestedDepth++;
                position++;
            } else if (c == '}') {
                if (nestedDepth > 0) {
                    nestedDepth--;
                }
                position++;
            } else if (nestedDepth == 0 && c == '(') {
                argumentDepth++;
                position++;
            } else if (nestedDepth == 0 && c == ')') {
                if (argumentDepth > 0) {
                    argumentDepth--;
                }
                position++;
            } else if (nestedDepth == 0 && argumentDepth == 0 && isNameStart(c)) {
                int responseStart = position;
                while (position < selectionEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                if (fieldName.equals(fieldName(query, responseStart, position, selectionEnd))) {
                    return query.substring(responseStart, position);
                }
            } else {
                position++;
            }
        }
        return fieldName;
    }

    private static String fieldName(String query, int nameStart, int nameEnd, int selectionEnd) {
        int next = nextNonWhitespace(query, nameEnd, selectionEnd);
        if (next >= selectionEnd || query.charAt(next) != ':') {
            return query.substring(nameStart, nameEnd);
        }
        int fieldStart = nextNonWhitespace(query, next + 1, selectionEnd);
        int fieldEnd = fieldStart;
        while (fieldEnd < selectionEnd && isNamePart(query.charAt(fieldEnd))) {
            fieldEnd++;
        }
        return query.substring(fieldStart, fieldEnd);
    }

    private static int fieldNameEnd(String query, int nameEnd, int selectionEnd) {
        int next = nextNonWhitespace(query, nameEnd, selectionEnd);
        if (next >= selectionEnd || query.charAt(next) != ':') {
            return nameEnd;
        }
        int fieldEnd = nextNonWhitespace(query, next + 1, selectionEnd);
        while (fieldEnd < selectionEnd && isNamePart(query.charAt(fieldEnd))) {
            fieldEnd++;
        }
        return fieldEnd;
    }

    private static int rootFieldSelectionStart(String query, int rootStart) {
        if (rootStart < 0) {
            return -1;
        }
        int fieldNameEnd = rootStart;
        while (fieldNameEnd < query.length() && isNamePart(query.charAt(fieldNameEnd))) {
            fieldNameEnd++;
        }
        int afterArguments = fieldAfterArguments(query, fieldNameEnd, query.length());
        int selectionStart = nextNonWhitespace(query, afterArguments, query.length());
        if (selectionStart < query.length() && query.charAt(selectionStart) == '{') {
            return selectionStart;
        }
        return -1;
    }

    private static String responseKeyConflictError(String query, int selectionStart) {
        int selectionEnd = selectionEnd(query, selectionStart);
        int left = selectionStart + 1;
        while (left < selectionEnd) {
            int leftStart = nextTopLevelFieldStart(query, left, selectionEnd);
            if (leftStart < 0) {
                return "";
            }
            int leftEnd = fieldTokenEnd(query, leftStart, selectionEnd);
            String leftResponseKey = query.substring(leftStart, leftEnd);
            String leftFieldName = fieldName(query, leftStart, leftEnd, selectionEnd);
            String leftSignature = compactFieldSignature(query, fieldNameStart(query, leftEnd, selectionEnd),
                    topLevelFieldEnd(query, fieldNameEnd(query, leftEnd, selectionEnd), selectionEnd));
            int right = fieldNameEnd(query, leftEnd, selectionEnd);
            while (right < selectionEnd) {
                int rightStart = nextTopLevelFieldStart(query, right, selectionEnd);
                if (rightStart < 0) {
                    right = selectionEnd;
                } else {
                    int rightEnd = fieldTokenEnd(query, rightStart, selectionEnd);
                    String rightResponseKey = query.substring(rightStart, rightEnd);
                    String rightFieldName = fieldName(query, rightStart, rightEnd, selectionEnd);
                    if (leftResponseKey.equals(rightResponseKey) && leftFieldName.equals(rightFieldName) == false) {
                        return "conflicting response key '" + leftResponseKey + "'";
                    }
                    if (leftResponseKey.equals(rightResponseKey)
                            && leftSignature.equals(compactFieldSignature(
                            query,
                            fieldNameStart(query, rightEnd, selectionEnd),
                            topLevelFieldEnd(query, fieldNameEnd(query, rightEnd, selectionEnd), selectionEnd))) == false) {
                        return "conflicting response key '" + leftResponseKey + "'";
                    }
                    right = fieldNameEnd(query, rightEnd, selectionEnd);
                }
            }
            left = fieldNameEnd(query, leftEnd, selectionEnd);
        }
        return "";
    }

    private static int fieldNameStart(String query, int nameEnd, int selectionEnd) {
        int next = nextNonWhitespace(query, nameEnd, selectionEnd);
        if (next >= selectionEnd || query.charAt(next) != ':') {
            return nameEnd - (nameEnd - previousNameStart(query, nameEnd));
        }
        return nextNonWhitespace(query, next + 1, selectionEnd);
    }

    private static int previousNameStart(String query, int nameEnd) {
        int position = nameEnd - 1;
        while (position >= 0 && isNamePart(query.charAt(position))) {
            position--;
        }
        return position + 1;
    }

    private static int topLevelFieldEnd(String query, int position, int selectionEnd) {
        int nestedDepth = 0;
        int argumentDepth = 0;
        while (position < selectionEnd) {
            char c = query.charAt(position);
            if (c == '{') {
                nestedDepth++;
                position++;
            } else if (c == '}') {
                if (nestedDepth == 0) {
                    return position;
                }
                nestedDepth--;
                position++;
            } else if (nestedDepth == 0 && c == '(') {
                argumentDepth++;
                position++;
            } else if (nestedDepth == 0 && c == ')') {
                if (argumentDepth > 0) {
                    argumentDepth--;
                }
                position++;
            } else if (nestedDepth == 0 && argumentDepth == 0 && isNameStart(c)) {
                return position;
            } else if (nestedDepth == 0 && argumentDepth == 0 && startsInlineFragment(query, position)) {
                return position;
            } else {
                position++;
            }
        }
        return selectionEnd;
    }

    private static String compactFieldSignature(String query, int start, int end) {
        String signature = "";
        int position = start;
        while (position < end) {
            char c = query.charAt(position);
            if (isWhitespace(c) == false) {
                signature = signature + c;
            }
            position++;
        }
        return signature;
    }

    private static int nextTopLevelFieldStart(String query, int position, int selectionEnd) {
        int nestedDepth = 0;
        int argumentDepth = 0;
        while (position < selectionEnd) {
            char c = query.charAt(position);
            if (c == '{') {
                nestedDepth++;
                position++;
            } else if (c == '}') {
                if (nestedDepth > 0) {
                    nestedDepth--;
                }
                position++;
            } else if (nestedDepth == 0 && c == '(') {
                argumentDepth++;
                position++;
            } else if (nestedDepth == 0 && c == ')') {
                if (argumentDepth > 0) {
                    argumentDepth--;
                }
                position++;
            } else if (nestedDepth == 0 && argumentDepth == 0 && isNameStart(c)) {
                return position;
            } else {
                position++;
            }
        }
        return -1;
    }

    private static int fieldTokenEnd(String query, int position, int selectionEnd) {
        while (position < selectionEnd && isNamePart(query.charAt(position))) {
            position++;
        }
        return position;
    }

    private static String articleSelectionValidationError(String query, int selectionStart) {
        String conflictError = responseKeyConflictError(query, selectionStart);
        if (conflictError.isEmpty() == false) {
            return conflictError;
        }
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            char c = query.charAt(position);
            if (isNameStart(c)) {
                int nameStart = position;
                while (position < selectionEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                String name = fieldName(query, nameStart, position, selectionEnd);
                position = fieldNameEnd(query, position, selectionEnd);
                if ("id".equals(name) == false
                        && "title".equals(name) == false
                        && "titleLength".equals(name) == false
                        && "author".equals(name) == false
                        && "comments".equals(name) == false
                        && "__typename".equals(name) == false) {
                    return "unsupported Article field '" + name + "'";
                }
                if ("comments".equals(name)) {
                    int commentsSelectionStart = query.indexOf("{", position);
                    if (commentsSelectionStart < 0 || commentsSelectionStart >= selectionEnd) {
                        return "field 'comments' requires a selection set";
                    }
                    String commentsArgumentError = commentsArgumentValidationError(query, position, commentsSelectionStart);
                    if (commentsArgumentError.isEmpty() == false) {
                        return commentsArgumentError;
                    }
                    String commentsError = commentConnectionSelectionValidationError(query, commentsSelectionStart);
                    if (commentsError.isEmpty() == false) {
                        return commentsError;
                    }
                    position = selectionEnd(query, commentsSelectionStart) + 1;
                } else {
                    String argumentError = fieldArgumentError(query, "Article", name, position, selectionEnd);
                    if (argumentError.isEmpty() == false) {
                        return argumentError;
                    }
                }
                if ("id".equals(name) || "title".equals(name) || "titleLength".equals(name)
                        || "__typename".equals(name)) {
                    String scalarError = scalarSelectionSetError(query, name, position, selectionEnd);
                    if (scalarError.isEmpty() == false) {
                        return scalarError;
                    }
                } else if ("author".equals(name)) {
                    int authorSelectionStart = query.indexOf("{", position);
                    if (authorSelectionStart < 0 || authorSelectionStart >= selectionEnd) {
                        return "field 'author' requires a selection set";
                    }
                    String authorError = authorSelectionValidationError(query, authorSelectionStart);
                    if (authorError.isEmpty() == false) {
                        return authorError;
                    }
                    position = selectionEnd(query, authorSelectionStart) + 1;
                }
            }
            position++;
        }
        return "";
    }

    private static String authorSelectionValidationError(String query, int selectionStart) {
        String conflictError = responseKeyConflictError(query, selectionStart);
        if (conflictError.isEmpty() == false) {
            return conflictError;
        }
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            char c = query.charAt(position);
            if (isNameStart(c)) {
                int nameStart = position;
                while (position < selectionEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                String name = fieldName(query, nameStart, position, selectionEnd);
                position = fieldNameEnd(query, position, selectionEnd);
                if ("id".equals(name) == false
                        && "name".equals(name) == false
                        && "email".equals(name) == false
                        && "__typename".equals(name) == false) {
                    return "unsupported User field '" + name + "'";
                }
                String argumentError = fieldArgumentError(query, "User", name, position, selectionEnd);
                if (argumentError.isEmpty() == false) {
                    return argumentError;
                }
                String scalarError = scalarSelectionSetError(query, name, position, selectionEnd);
                if (scalarError.isEmpty() == false) {
                    return scalarError;
                }
            }
            position++;
        }
        return "";
    }

    private static String articleConnectionSelectionValidationError(String query, int selectionStart) {
        String conflictError = responseKeyConflictError(query, selectionStart);
        if (conflictError.isEmpty() == false) {
            return conflictError;
        }
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        boolean sawConnectionField = false;
        while (position < selectionEnd) {
            char c = query.charAt(position);
            if (isNameStart(c)) {
                int nameStart = position;
                while (position < selectionEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                String name = fieldName(query, nameStart, position, selectionEnd);
                position = fieldNameEnd(query, position, selectionEnd);
                if ("edges".equals(name)) {
                    sawConnectionField = true;
                    String argumentError = fieldArgumentError(query, "articles", "edges", position, selectionEnd);
                    if (argumentError.isEmpty() == false) {
                        return argumentError;
                    }
                    int edgeSelectionStart = query.indexOf("{", position);
                    if (edgeSelectionStart < 0 || edgeSelectionStart >= selectionEnd) {
                        return "field 'edges' requires a selection set";
                    }
                    String edgeError = articleEdgeSelectionValidationError(query, edgeSelectionStart);
                    if (edgeError.isEmpty() == false) {
                        return edgeError;
                    }
                    position = selectionEnd(query, edgeSelectionStart) + 1;
                } else if ("totalCount".equals(name)) {
                    sawConnectionField = true;
                    String argumentError = fieldArgumentError(query, "articles", "totalCount", position, selectionEnd);
                    if (argumentError.isEmpty() == false) {
                        return argumentError;
                    }
                    String scalarError = scalarSelectionSetError(query, name, position, selectionEnd);
                    if (scalarError.isEmpty() == false) {
                        return scalarError;
                    }
                } else if ("pageInfo".equals(name)) {
                    sawConnectionField = true;
                    String argumentError = fieldArgumentError(query, "articles", "pageInfo", position, selectionEnd);
                    if (argumentError.isEmpty() == false) {
                        return argumentError;
                    }
                    int pageInfoSelectionStart = query.indexOf("{", position);
                    if (pageInfoSelectionStart < 0 || pageInfoSelectionStart >= selectionEnd) {
                        return "field 'pageInfo' requires a selection set";
                    }
                    String pageInfoError = articlePageInfoSelectionValidationError(query, pageInfoSelectionStart);
                    if (pageInfoError.isEmpty() == false) {
                        return pageInfoError;
                    }
                    position = selectionEnd(query, pageInfoSelectionStart) + 1;
                } else {
                    return "root field 'articles' returns a Relay connection; select 'edges' and/or 'pageInfo'";
                }
            }
            position++;
        }
        if (sawConnectionField == false) {
            return "root field 'articles' requires a connection selection";
        }
        return "";
    }

    private static String articleEdgeSelectionValidationError(String query, int selectionStart) {
        String conflictError = responseKeyConflictError(query, selectionStart);
        if (conflictError.isEmpty() == false) {
            return conflictError;
        }
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            char c = query.charAt(position);
            if (isNameStart(c)) {
                int nameStart = position;
                while (position < selectionEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                String name = fieldName(query, nameStart, position, selectionEnd);
                position = fieldNameEnd(query, position, selectionEnd);
                if ("cursor".equals(name)) {
                    String argumentError = fieldArgumentError(query, "articles.edges", name, position, selectionEnd);
                    if (argumentError.isEmpty() == false) {
                        return argumentError;
                    }
                    String scalarError = scalarSelectionSetError(query, name, position, selectionEnd);
                    if (scalarError.isEmpty() == false) {
                        return scalarError;
                    }
                } else if ("node".equals(name)) {
                    String argumentError = fieldArgumentError(query, "articles.edges", name, position, selectionEnd);
                    if (argumentError.isEmpty() == false) {
                        return argumentError;
                    }
                    int nodeSelectionStart = query.indexOf("{", position);
                    if (nodeSelectionStart < 0 || nodeSelectionStart >= selectionEnd) {
                        return "field 'node' requires a selection set";
                    }
                    String nodeError = articleSelectionValidationError(query, nodeSelectionStart);
                    if (nodeError.isEmpty() == false) {
                        return nodeError;
                    }
                    position = selectionEnd(query, nodeSelectionStart) + 1;
                } else {
                    return "unsupported articles.edges field '" + name + "'";
                }
            }
            position++;
        }
        return "";
    }

    private static String articlePageInfoSelectionValidationError(String query, int selectionStart) {
        String conflictError = responseKeyConflictError(query, selectionStart);
        if (conflictError.isEmpty() == false) {
            return conflictError;
        }
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            char c = query.charAt(position);
            if (isNameStart(c)) {
                int nameStart = position;
                while (position < selectionEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                String name = fieldName(query, nameStart, position, selectionEnd);
                position = fieldNameEnd(query, position, selectionEnd);
                if ("hasNextPage".equals(name) == false
                        && "hasPreviousPage".equals(name) == false
                        && "startCursor".equals(name) == false
                        && "endCursor".equals(name) == false) {
                    return "unsupported articles.pageInfo field '" + name + "'";
                }
                String argumentError = fieldArgumentError(query, "articles.pageInfo", name, position, selectionEnd);
                if (argumentError.isEmpty() == false) {
                    return argumentError;
                }
                String scalarError = scalarSelectionSetError(query, name, position, selectionEnd);
                if (scalarError.isEmpty() == false) {
                    return scalarError;
                }
            }
            position++;
        }
        return "";
    }

    private static String commentConnectionSelectionValidationError(String query, int selectionStart) {
        String conflictError = responseKeyConflictError(query, selectionStart);
        if (conflictError.isEmpty() == false) {
            return conflictError;
        }
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        boolean sawConnectionField = false;
        while (position < selectionEnd) {
            char c = query.charAt(position);
            if (isNameStart(c)) {
                int nameStart = position;
                while (position < selectionEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                String name = fieldName(query, nameStart, position, selectionEnd);
                position = fieldNameEnd(query, position, selectionEnd);
                if ("edges".equals(name)) {
                    sawConnectionField = true;
                    String argumentError = fieldArgumentError(query, "comments", "edges", position, selectionEnd);
                    if (argumentError.isEmpty() == false) {
                        return argumentError;
                    }
                    int edgeSelectionStart = query.indexOf("{", position);
                    if (edgeSelectionStart < 0 || edgeSelectionStart >= selectionEnd) {
                        return "field 'edges' requires a selection set";
                    }
                    String edgeError = commentEdgeSelectionValidationError(query, edgeSelectionStart);
                    if (edgeError.isEmpty() == false) {
                        return edgeError;
                    }
                    position = selectionEnd(query, edgeSelectionStart) + 1;
                } else if ("pageInfo".equals(name)) {
                    sawConnectionField = true;
                    String argumentError = fieldArgumentError(query, "comments", "pageInfo", position, selectionEnd);
                    if (argumentError.isEmpty() == false) {
                        return argumentError;
                    }
                    int pageInfoSelectionStart = query.indexOf("{", position);
                    if (pageInfoSelectionStart < 0 || pageInfoSelectionStart >= selectionEnd) {
                        return "field 'pageInfo' requires a selection set";
                    }
                    String pageInfoError = pageInfoSelectionValidationError(query, pageInfoSelectionStart);
                    if (pageInfoError.isEmpty() == false) {
                        return pageInfoError;
                    }
                    position = selectionEnd(query, pageInfoSelectionStart) + 1;
                } else if ("totalCount".equals(name)) {
                    sawConnectionField = true;
                    String argumentError = fieldArgumentError(query, "comments", "totalCount", position, selectionEnd);
                    if (argumentError.isEmpty() == false) {
                        return argumentError;
                    }
                    String scalarError = scalarSelectionSetError(query, name, position, selectionEnd);
                    if (scalarError.isEmpty() == false) {
                        return scalarError;
                    }
                } else {
                    return "relation field 'comments' returns a Relay connection; select 'edges', 'totalCount', and/or 'pageInfo'";
                }
            }
            position++;
        }
        if (sawConnectionField == false) {
            return "relation field 'comments' requires a connection selection";
        }
        return "";
    }

    private static String commentEdgeSelectionValidationError(String query, int selectionStart) {
        String conflictError = responseKeyConflictError(query, selectionStart);
        if (conflictError.isEmpty() == false) {
            return conflictError;
        }
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            char c = query.charAt(position);
            if (isNameStart(c)) {
                int nameStart = position;
                while (position < selectionEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                String name = fieldName(query, nameStart, position, selectionEnd);
                position = fieldNameEnd(query, position, selectionEnd);
                if ("cursor".equals(name)) {
                    String argumentError = fieldArgumentError(query, "comments.edges", name, position, selectionEnd);
                    if (argumentError.isEmpty() == false) {
                        return argumentError;
                    }
                    String scalarError = scalarSelectionSetError(query, name, position, selectionEnd);
                    if (scalarError.isEmpty() == false) {
                        return scalarError;
                    }
                } else if ("node".equals(name)) {
                    String argumentError = fieldArgumentError(query, "comments.edges", name, position, selectionEnd);
                    if (argumentError.isEmpty() == false) {
                        return argumentError;
                    }
                    int nodeSelectionStart = query.indexOf("{", position);
                    if (nodeSelectionStart < 0 || nodeSelectionStart >= selectionEnd) {
                        return "field 'node' requires a selection set";
                    }
                    String nodeError = commentSelectionValidationError(query, nodeSelectionStart);
                    if (nodeError.isEmpty() == false) {
                        return nodeError;
                    }
                    position = selectionEnd(query, nodeSelectionStart) + 1;
                } else {
                    return "unsupported comments.edges field '" + name + "'";
                }
            }
            position++;
        }
        return "";
    }

    private static String pageInfoSelectionValidationError(String query, int selectionStart) {
        String conflictError = responseKeyConflictError(query, selectionStart);
        if (conflictError.isEmpty() == false) {
            return conflictError;
        }
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            char c = query.charAt(position);
            if (isNameStart(c)) {
                int nameStart = position;
                while (position < selectionEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                String name = fieldName(query, nameStart, position, selectionEnd);
                position = fieldNameEnd(query, position, selectionEnd);
                if ("hasNextPage".equals(name) == false
                        && "hasPreviousPage".equals(name) == false
                        && "startCursor".equals(name) == false
                        && "endCursor".equals(name) == false) {
                    return "unsupported comments.pageInfo field '" + name + "'";
                }
                String argumentError = fieldArgumentError(query, "comments.pageInfo", name, position, selectionEnd);
                if (argumentError.isEmpty() == false) {
                    return argumentError;
                }
                String scalarError = scalarSelectionSetError(query, name, position, selectionEnd);
                if (scalarError.isEmpty() == false) {
                    return scalarError;
                }
            }
            position++;
        }
        return "";
    }

    private static String commentSelectionValidationError(String query, int selectionStart) {
        String conflictError = responseKeyConflictError(query, selectionStart);
        if (conflictError.isEmpty() == false) {
            return conflictError;
        }
        int selectionEnd = selectionEnd(query, selectionStart);
        int position = selectionStart + 1;
        while (position < selectionEnd) {
            char c = query.charAt(position);
            if (isNameStart(c)) {
                int nameStart = position;
                while (position < selectionEnd && isNamePart(query.charAt(position))) {
                    position++;
                }
                String name = fieldName(query, nameStart, position, selectionEnd);
                position = fieldNameEnd(query, position, selectionEnd);
                if ("id".equals(name) == false
                        && "body".equals(name) == false
                        && "author".equals(name) == false
                        && "__typename".equals(name) == false) {
                    return "unsupported Comment field '" + name + "'";
                }
                String argumentError = fieldArgumentError(query, "Comment", name, position, selectionEnd);
                if (argumentError.isEmpty() == false) {
                    return argumentError;
                }
                if ("id".equals(name) || "body".equals(name) || "__typename".equals(name)) {
                    String scalarError = scalarSelectionSetError(query, name, position, selectionEnd);
                    if (scalarError.isEmpty() == false) {
                        return scalarError;
                    }
                } else if ("author".equals(name)) {
                    int authorSelectionStart = query.indexOf("{", position);
                    if (authorSelectionStart < 0 || authorSelectionStart >= selectionEnd) {
                        return "field 'author' requires a selection set";
                    }
                    String authorError = authorSelectionValidationError(query, authorSelectionStart);
                    if (authorError.isEmpty() == false) {
                        return authorError;
                    }
                    position = selectionEnd(query, authorSelectionStart) + 1;
                }
            }
            position++;
        }
        return "";
    }

    private static String aliasError(String query, int position, int selectionEnd) {
        int next = nextNonWhitespace(query, position, selectionEnd);
        if (next < selectionEnd && query.charAt(next) == ':') {
            return "aliases are not supported";
        }
        return "";
    }

    private static String fieldArgumentError(String query, String parentType, String fieldName, int position, int selectionEnd) {
        int next = nextNonWhitespace(query, position, selectionEnd);
        if (next < selectionEnd && query.charAt(next) == '(') {
            return parentType + "." + fieldName + " does not accept arguments";
        }
        return "";
    }

    private static String scalarSelectionSetError(String query, String fieldName, int position, int selectionEnd) {
        int next = nextNonWhitespace(query, position, selectionEnd);
        if (next < selectionEnd && query.charAt(next) == '{') {
            return "scalar field '" + fieldName + "' cannot have a selection set";
        }
        return "";
    }

    private static int nextNonWhitespace(String query, int position, int selectionEnd) {
        int next = position;
        while (next < selectionEnd && isWhitespace(query.charAt(next))) {
            next++;
        }
        return next;
    }

    private static int selectionEnd(String query, int selectionStart) {
        if (selectionStart >= query.length()) {
            return query.length();
        }
        String segment = query.substring(selectionStart);
        int depth = 0;
        int cursor = 0;
        while (cursor < segment.length()) {
            char c = segment.charAt(cursor);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return selectionStart + cursor;
                }
            }
            cursor++;
        }
        return query.length();
    }

    private static int matchingParen(String query, int parenStart) {
        if (parenStart >= query.length()) {
            return query.length();
        }
        String segment = query.substring(parenStart);
        int depth = 0;
        int cursor = 0;
        while (cursor < segment.length()) {
            char c = segment.charAt(cursor);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return parenStart + cursor;
                }
            }
            cursor++;
        }
        return query.length();
    }

    private static int matchingBrace(String query, int braceStart) {
        if (braceStart >= query.length()) {
            return query.length();
        }
        String segment = query.substring(braceStart);
        int depth = 0;
        int cursor = 0;
        while (cursor < segment.length()) {
            char c = segment.charAt(cursor);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return braceStart + cursor;
                }
            }
            cursor++;
        }
        return query.length();
    }

    private static int matchingBracket(String query, int bracketStart) {
        if (bracketStart >= query.length()) {
            return query.length();
        }
        String segment = query.substring(bracketStart);
        int depth = 0;
        int cursor = 0;
        while (cursor < segment.length()) {
            char c = segment.charAt(cursor);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return bracketStart + cursor;
                }
            }
            cursor++;
        }
        return query.length();
    }

    private static boolean containsName(String query, String name) {
        return indexOfName(query, name, 0) >= 0;
    }

    private static int indexOfName(String query, String name, int start) {
        int position = start;
        int found = query.indexOf(name, position);
        while (found >= 0 && isNameAt(query, name, found) == false) {
            position = found + 1;
            found = query.indexOf(name, position);
        }
        return found;
    }

    private static boolean isNameAt(String query, String name, int position) {
        int before = position - 1;
        int after = position + name.length();
        boolean validBefore = before < 0 || isNamePart(query.charAt(before)) == false;
        boolean validAfter = after >= query.length() || isNamePart(query.charAt(after)) == false;
        return validBefore && validAfter;
    }

    private static boolean isNameStart(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || c == '_';
    }

    private static boolean isNamePart(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_';
    }
}
