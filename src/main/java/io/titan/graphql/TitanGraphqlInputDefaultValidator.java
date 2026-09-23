package io.titan.graphql;

import io.titan.graphql.model.TitanGraphqlEnumDocument;
import io.titan.graphql.model.TitanGraphqlInputObjectDocument;
import io.titan.graphql.model.TitanGraphqlModelDocument;

/** Build-time validation for model-authored GraphQL input-field default values. */
public final class TitanGraphqlInputDefaultValidator {

    private static final int MAX_VALUES = 256;

    private TitanGraphqlInputDefaultValidator() {
    }

    /** Returns an empty string when the default is valid, otherwise a deployment-blocking reason. */
    public static String error(
            TitanGraphqlModelDocument document,
            String typeReference,
            String defaultValue
    ) {
        if (document == null || typeReference == null || typeReference.isBlank()
                || defaultValue == null || defaultValue.isBlank()) {
            return "input-field default metadata is incomplete";
        }
        return parsedValueError(document, typeReference, defaultValue, new int[] {0});
    }

    private static String parsedValueError(
            TitanGraphqlModelDocument document,
            String typeReference,
            String defaultValue,
            int[] work
    ) {
        GraphqlAst.Value value;
        try {
            GraphqlAst.Document parsed = GraphqlParser.parseDocument(
                    "query Default($value: " + typeReference + " = " + defaultValue + ") { __typename }");
            if (parsed.operations().size() != 1 || parsed.operations().getFirst().variables().size() != 1) {
                return "default is not one GraphQL constant value";
            }
            GraphqlAst.VariableDefinition definition = parsed.operations().getFirst().variables().getFirst();
            value = definition.defaultValue();
            if (value == null) {
                return "default is not one GraphQL constant value";
            }
        } catch (RuntimeException ex) {
            return "default is not valid GraphQL constant-value syntax";
        }
        return valueError(document, value, typeReference.trim(), work);
    }

    private static String valueError(
            TitanGraphqlModelDocument document,
            GraphqlAst.Value value,
            String typeReference,
            int[] work
    ) {
        work[0]++;
        if (work[0] > MAX_VALUES) {
            return "default exceeds the input coercion work budget";
        }
        boolean nonNull = typeReference.endsWith("!");
        String nullableType = nonNull
                ? typeReference.substring(0, typeReference.length() - 1) : typeReference;
        if (value instanceof GraphqlAst.NullValue) {
            return nonNull ? "null is not allowed for type '" + typeReference + "'" : "";
        }
        if (nullableType.startsWith("[")) {
            if (!nullableType.endsWith("]")) {
                return "type reference is malformed";
            }
            String itemType = nullableType.substring(1, nullableType.length() - 1);
            if (value instanceof GraphqlAst.InputListValue list) {
                for (GraphqlAst.Value item : list.values()) {
                    String error = valueError(document, item, itemType, work);
                    if (!error.isEmpty()) return error;
                }
                return "";
            }
            return valueError(document, value, itemType, work);
        }

        TitanGraphqlEnumDocument enumType = document.enums().stream()
                .filter(candidate -> candidate.name().equals(nullableType)).findFirst().orElse(null);
        if (enumType != null) {
            return value instanceof GraphqlAst.EnumValue enumValue
                    && enumType.values().contains(enumValue.value())
                    ? "" : "value is not declared by enum '" + nullableType + "'";
        }
        TitanGraphqlInputObjectDocument inputObject = document.inputObjects().stream()
                .filter(candidate -> candidate.name().equals(nullableType)).findFirst().orElse(null);
        if (inputObject != null) {
            if (!(value instanceof GraphqlAst.InputObjectValue objectValue)) {
                return "value is not an input object of type '" + nullableType + "'";
            }
            for (String supplied : objectValue.fields().keySet()) {
                if (inputObject.fields().stream().noneMatch(field -> field.name().equals(supplied))) {
                    return "input object '" + nullableType + "' has unknown field '" + supplied + "'";
                }
            }
            for (TitanGraphqlInputObjectDocument.InputField field : inputObject.fields()) {
                GraphqlAst.Value fieldValue = objectValue.fields().get(field.name());
                if (fieldValue == null) {
                    if (!field.defaultValue().isEmpty()) {
                        String error = parsedValueError(document, field.type(), field.defaultValue(), work);
                        if (!error.isEmpty()) return "field '" + field.name() + "': " + error;
                    } else if (field.type().endsWith("!")) {
                        return "required input field '" + field.name() + "' is missing";
                    }
                } else {
                    String error = valueError(document, fieldValue, field.type(), work);
                    if (!error.isEmpty()) return "field '" + field.name() + "': " + error;
                }
            }
            return "";
        }
        return scalarError(value, nullableType);
    }

    private static String scalarError(GraphqlAst.Value value, String type) {
        boolean valid = switch (type) {
            case "Boolean" -> value instanceof GraphqlAst.BooleanValue;
            case "Float", "Decimal" -> value instanceof GraphqlAst.FloatValue
                    || value instanceof GraphqlAst.IntValue;
            case "Int" -> value instanceof GraphqlAst.IntValue integer
                    && integer.value() >= Integer.MIN_VALUE && integer.value() <= Integer.MAX_VALUE;
            case "Long" -> value instanceof GraphqlAst.IntValue;
            case "ID" -> value instanceof GraphqlAst.IntValue || value instanceof GraphqlAst.StringValue;
            case "String", "Date", "DateTime", "Timestamp", "UUID" ->
                    value instanceof GraphqlAst.StringValue;
            default -> false;
        };
        return valid ? "" : "value cannot be coerced to '" + type + "'";
    }
}
