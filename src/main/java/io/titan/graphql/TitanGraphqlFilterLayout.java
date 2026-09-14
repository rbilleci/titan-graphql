package io.titan.graphql;

import io.titan.graphql.model.TitanGraphqlFieldDocument;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlRelationDocument;
import io.titan.graphql.model.TitanGraphqlRootDocument;
import io.titan.graphql.model.TitanGraphqlTypeDocument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic model-to-carrier filter selector contract shared by generation and execution. */
public final class TitanGraphqlFilterLayout {

    private static final List<String> ROOT_ARGUMENT_OPERATORS =
            List.of("eq", "neq", "in", "isnull", "lt", "lte", "gt", "gte");
    private static final List<String> SUPPORTED_OPERATORS = List.of(
            "eq", "neq", "in", "isnull", "lt", "lte", "gt", "gte",
            "contains", "startswith", "endswith");

    private TitanGraphqlFilterLayout() {
    }

    public static List<Binding> bindings(
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root
    ) {
        return bindings(null, type, root);
    }

    public static List<Binding> bindings(
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root
    ) {
        Map<String, Binding> bindings = new LinkedHashMap<>();
        for (TitanGraphqlFieldDocument field : type.fields()) {
            if (!field.policies().isEmpty()) continue;
            for (String operator : field.filterOperators()) {
                add(bindings, new Binding(
                        field.name(), field.computed() == null ? field.column() : field.name(),
                        field.name(), field.type(), normalizeOperator(operator), 0,
                        field.computed() != null));
            }
        }
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : root.arguments()) {
            if (argument.hops() != 0
                    || argument.kind() != TitanGraphqlRootDocument.RootDocumentArgumentKind.EQUALS) {
                continue;
            }
            for (String operator : ROOT_ARGUMENT_OPERATORS) {
                add(bindings, new Binding(
                        argument.name(), argument.column(), argument.path(), argument.type(),
                        operator, 0, false));
            }
        }
        for (TitanGraphqlRootDocument.RootDocumentFilterPath path : root.filterPaths()) {
            TitanGraphqlFieldDocument field = path.hops() == 0 ? scalarField(type, path)
                    : relationField(document, type, root, path);
            if (field != null && !field.policies().isEmpty()) continue;
            for (String operator : path.operators()) {
                add(bindings, new Binding(
                        path.name(), field != null && field.computed() != null
                                ? field.name() : field == null ? path.column() : field.column(),
                        path.path(), path.type(), normalizeOperator(operator), path.hops(),
                        field != null && field.computed() != null));
            }
        }
        List<Binding> sorted = new ArrayList<>(bindings.values());
        sorted.sort(Comparator.comparing(Binding::fieldName).thenComparing(Binding::operator));
        List<Binding> numbered = new ArrayList<>();
        for (int index = 0; index < sorted.size(); index++) {
            numbered.add(sorted.get(index).withSelector(index + 1));
        }
        return List.copyOf(numbered);
    }

    private static TitanGraphqlFieldDocument relationField(
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            TitanGraphqlRootDocument.RootDocumentFilterPath path
    ) {
        if (document == null) return null;
        if (path.hops() != 1) {
            throw new IllegalArgumentException("root filter path '" + root.name() + "." + path.name()
                    + "' exceeds the static carrier relation-hop limit of 1");
        }
        String[] segments = path.path().split("\\.", -1);
        if (segments.length != 2 || segments[0].isBlank() || segments[1].isBlank()) {
            throw new IllegalArgumentException("one-hop root filter path '" + root.name() + "."
                    + path.name() + "' must use relation.field syntax");
        }
        TitanGraphqlRelationDocument relation = type.relations().stream()
                .filter(candidate -> candidate.name().equals(segments[0]))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("root filter path '"
                        + root.name() + "." + path.name() + "' references unknown relation '"
                        + segments[0] + "'"));
        if (relation.cardinality() != TitanGraphqlRelationDocument.RelationDocumentCardinality.ONE) {
            throw new IllegalArgumentException("root filter path '" + root.name() + "." + path.name()
                    + "' requires a to-one relation");
        }
        if (!relation.policies().isEmpty()) return null;
        TitanGraphqlTypeDocument target = document.types().stream()
                .filter(candidate -> candidate.name().equals(relation.targetType()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("root filter path '"
                        + root.name() + "." + path.name() + "' references unknown target type '"
                        + relation.targetType() + "'"));
        return target.fields().stream().filter(candidate -> candidate.name().equals(segments[1]))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("root filter path '"
                        + root.name() + "." + path.name() + "' references unknown scalar field '"
                        + relation.targetType() + "." + segments[1] + "'"));
    }

    public static Binding require(
            List<Binding> bindings,
            String fieldName,
            String operator
    ) {
        String normalized = normalizeOperator(operator);
        return bindings.stream()
                .filter(binding -> binding.fieldName().equals(fieldName)
                        && binding.operator().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new GraphqlException("generated filter '" + fieldName + "."
                        + operator + "' has no reviewed carrier binding"));
    }

    public static String normalizeOperator(String operator) {
        String normalized = operator == null ? "" : operator.replace("_", "").toLowerCase();
        if (!SUPPORTED_OPERATORS.contains(normalized)) {
            throw new IllegalArgumentException("unknown generated scalar filter operator '" + operator + "'");
        }
        return normalized;
    }

    public static List<String> valueTypes(List<Binding> bindings) {
        List<String> result = new ArrayList<>();
        result.add("Boolean");
        bindings.stream().map(binding -> normalizeGraphqlType(binding.graphqlType())).sorted()
                .forEach(value -> {
                    if (result.stream().noneMatch(existing -> valueKind(existing).equals(valueKind(value)))) {
                        result.add(value);
                    }
                });
        return List.copyOf(result);
    }

    public static String normalizeGraphqlType(String type) {
        return type == null ? "" : type.replace("!", "").trim();
    }

    public static String valueKind(String graphqlType) {
        return switch (normalizeGraphqlType(graphqlType)) {
            case "Int" -> "int";
            case "Long" -> "long";
            case "Boolean" -> "boolean";
            case "Float" -> "float";
            case "UUID" -> "uuid";
            case "String", "ID", "Date", "DateTime", "Timestamp" -> "string";
            default -> throw new IllegalArgumentException(
                    "filter uses unsupported scalar type '" + graphqlType + "'");
        };
    }

    private static TitanGraphqlFieldDocument scalarField(
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument.RootDocumentFilterPath path
    ) {
        return type.fields().stream()
                .filter(field -> field.name().equals(path.path())
                        || field.name().equals(path.name())
                        || field.column().equals(path.column()))
                .findFirst()
                .orElse(null);
    }

    private static void add(Map<String, Binding> bindings, Binding binding) {
        String key = binding.fieldName() + "\u0000" + binding.operator();
        Binding existing = bindings.putIfAbsent(key, binding);
        if (existing != null && !existing.sameContract(binding)) {
            throw new IllegalArgumentException("conflicting root filter binding for '"
                    + binding.fieldName() + "' operator '" + binding.operator() + "'");
        }
    }

    public record Binding(
            String fieldName,
            String binding,
            String path,
            String graphqlType,
            String operator,
            int hops,
            boolean computed,
            int selector
    ) {
        public Binding(
                String fieldName,
                String binding,
                String path,
                String graphqlType,
                String operator,
                int hops,
                boolean computed
        ) {
            this(fieldName, binding, path, graphqlType, operator, hops, computed, 0);
        }

        private Binding withSelector(int value) {
            return new Binding(fieldName, binding, path, graphqlType, operator, hops, computed, value);
        }

        private boolean sameContract(Binding other) {
            return binding.equals(other.binding)
                    && path.equals(other.path)
                    && graphqlType.equals(other.graphqlType)
                    && hops == other.hops
                    && computed == other.computed;
        }
    }
}
