package io.titan.graphql;

import java.util.List;
import java.util.Map;

final class GraphqlAst {

    private GraphqlAst() {
    }

    enum OperationType {
        QUERY,
        MUTATION,
        SUBSCRIPTION
    }

    record Document(List<AstOperation> operations, List<FragmentDefinition> fragments) {
    }

    // Renamed from Operation under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    record AstOperation(
            OperationType type,
            String name,
            List<VariableDefinition> variables,
            List<Selection> selections,
            List<FragmentDefinition> fragments
    ) {
        List<Field> fields() {
            return selections.stream()
                    .filter(Field.class::isInstance)
                    .map(Field.class::cast)
                    .toList();
        }
    }

    record VariableDefinition(String name, String typeName, boolean required, Value defaultValue) {
    }

    sealed interface Selection permits Field, FragmentSpread, InlineFragment {
    }

    record Field(
            String name,
            String responseKey,
            Map<String, Value> arguments,
            List<Directive> directives,
            List<Selection> selections
    )
            implements Selection {
    }

    record FragmentDefinition(String name, String typeCondition, List<Selection> selections) {
    }

    record FragmentSpread(String name, List<Directive> directives) implements Selection {
    }

    record InlineFragment(String typeCondition, List<Directive> directives, List<Selection> selections)
            implements Selection {
    }

    record Directive(String name, Map<String, Value> arguments) {
    }

    sealed interface Value permits BooleanValue, EnumValue, FloatValue, IdValue, InputListValue, InputObjectValue,
            IntValue, NullValue, StringValue, VariableValue {
    }

    record BooleanValue(boolean value) implements Value {
    }

    record EnumValue(String value) implements Value {
    }

    record FloatValue(double value) implements Value {
    }

    record IdValue(String value) implements Value {
    }

    record InputListValue(List<Value> values) implements Value {
    }

    record InputObjectValue(Map<String, Value> fields) implements Value {
    }

    record IntValue(long value) implements Value {
    }

    record NullValue() implements Value {
    }

    record StringValue(String value) implements Value {
    }

    record VariableValue(String name) implements Value {
    }
}
