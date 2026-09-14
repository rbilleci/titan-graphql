package io.titan.graphql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GraphqlSchemaPrinter {

    private GraphqlSchemaPrinter() {
    }

    static String print(GraphqlSchema schema) {
        StringBuilder builder = new StringBuilder();
        if (hasRelationSortPaths(schema)) {
            builder.append("directive @relationSortPath(name: String!, column: String!, path: String!, hops: Int!, direction: String!, tieBreaker: String!) repeatable on FIELD_DEFINITION\n\n");
        }
        builder.append("type Query {\n");
        for (GraphqlRootField rootField : schema.rootFields()) {
            builder.append("  ")
                    .append(rootField.name())
                    .append(arguments(rootArguments(rootField)))
                    .append(": ")
                    .append(rootType(rootField))
                    .append("\n");
        }
        builder.append("}\n");

        if (schema.mutations().isEmpty() == false) {
            builder.append("\n")
                    .append("type Mutation {\n");
            for (GraphqlMutationDescriptor mutation : schema.mutations()) {
                builder.append("  ")
                        .append(mutation.name())
                        .append("(input: ")
                        .append(mutation.input().name())
                        .append("!): ")
                        .append(mutation.payload().name())
                        .append("!\n");
            }
            builder.append("}\n");
        }

        for (String scalarFilterType : scalarFilterTypeNames(schema)) {
            builder.append("\n")
                    .append("input ")
                    .append(scalarFilterType)
                    .append(" {\n")
                    .append(scalarFilterFields(scalarFilterType))
                    .append("}\n");
        }
        for (GraphqlRootField rootField : schema.rootFields()) {
            if (shouldPrintRootFilter(rootField, schema)) {
                builder.append("\n")
                        .append("input ")
                        .append(rootField.typeName())
                        .append("Filter {\n")
                        .append(objectFilterFields(rootField, schema))
                        .append("}\n");
            }
        }
        if (hasRootSortPaths(schema)) {
            builder.append("\n")
                    .append("enum SortDirection {\n")
                    .append("  ASC\n")
                    .append("  DESC\n")
                    .append("}\n");
        }
        for (GraphqlRootField rootField : schema.rootFields()) {
            if (rootField.sortPaths().isEmpty() == false) {
                builder.append("\n")
                        .append("input ")
                        .append(rootField.typeName())
                        .append("OrderBy {\n");
                for (GraphqlRootField.RootFieldSortPath sortPath : rootField.sortPaths()) {
                    builder.append("  ")
                            .append(sortPath.name())
                            .append(": SortDirection\n");
                }
                builder.append("}\n");
            }
        }

        for (GraphqlObjectType type : schema.types()) {
            builder.append("\n")
                    .append("type ")
                    .append(type.name())
                    .append(" {\n");
            for (GraphqlFieldDescriptor field : type.fields()) {
                builder.append("  ")
                        .append(field.name())
                        .append(fieldArguments(field))
                        .append(": ")
                        .append(fieldType(field));
                for (GraphqlFieldDescriptor.RelationSortPath sortPath : field.relationSortPaths()) {
                    builder.append(" @relationSortPath(")
                            .append("name: \"").append(sortPath.name()).append("\", ")
                            .append("column: \"").append(sortPath.columnName()).append("\", ")
                            .append("path: \"").append(sortPath.sortPath()).append("\", ")
                            .append("hops: ").append(sortPath.sortHopCount()).append(", ")
                            .append("direction: \"").append(sortPath.direction()).append("\", ")
                            .append("tieBreaker: \"").append(sortPath.tieBreakerColumnName()).append("\")");
                }
                builder.append("\n");
            }
            builder.append("}\n");
        }
        for (GraphqlMutationDescriptor mutation : schema.mutations()) {
            builder.append("\n")
                    .append("input ")
                    .append(mutation.input().name())
                    .append(" {\n");
            for (GraphqlMutationDescriptor.InputField field : mutation.input().fields()) {
                builder.append("  ")
                        .append(field.name())
                        .append(": ")
                        .append(requiredType(field.graphqlType(), field.required()))
                        .append("\n");
            }
            builder.append("}\n")
                    .append("\n")
                    .append("type ")
                    .append(mutation.payload().name())
                    .append(" {\n");
            for (GraphqlMutationDescriptor.PayloadField field : mutation.payload().fields()) {
                builder.append("  ")
                        .append(field.name())
                        .append(": ")
                        .append(requiredType(field.graphqlType(), field.required()))
                        .append("\n");
            }
            builder.append("}\n");
        }
        for (String typeName : connectionTypeNames(schema)) {
            builder.append("\n")
                    .append("type ")
                    .append(typeName)
                    .append("Connection {\n")
                    .append("  edges: [")
                    .append(typeName)
                    .append("Edge!]!\n")
                    .append("  pageInfo: PageInfo!\n");
            if (connectionSupportsTotalCount(schema, typeName)) {
                builder.append("  totalCount: Int!\n");
            }
            builder.append("}\n")
                    .append("\n")
                    .append("type ")
                    .append(typeName)
                    .append("Edge {\n")
                    .append("  cursor: String!\n")
                    .append("  node: ")
                    .append(typeName)
                    .append("!\n")
                    .append("}\n");
        }
        if (connectionTypeNames(schema).isEmpty() == false) {
            builder.append("\n")
                    .append("type PageInfo {\n")
                    .append("  hasNextPage: Boolean\n")
                    .append("  hasPreviousPage: Boolean\n")
                    .append("  startCursor: String\n")
                    .append("  endCursor: String\n")
                    .append("}\n");
        }
        return builder.toString();
    }

    private static String requiredType(String graphqlType, boolean required) {
        if (required && graphqlType.endsWith("!") == false) {
            return graphqlType + "!";
        }
        return graphqlType;
    }

    private static boolean hasRelationSortPaths(GraphqlSchema schema) {
        for (GraphqlObjectType type : schema.types()) {
            for (GraphqlFieldDescriptor field : type.fields()) {
                if (field.relationSortPaths().isEmpty() == false) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasRootSortPaths(GraphqlSchema schema) {
        for (GraphqlRootField rootField : schema.rootFields()) {
            if (rootField.sortPaths().isEmpty() == false) {
                return true;
            }
        }
        return false;
    }

    private static String rootType(GraphqlRootField rootField) {
        if (rootField.rootPaginationMode() == GraphqlRootField.RootPaginationMode.RELAY_CONNECTION) {
            return rootField.typeName() + "Connection!";
        }
        if (rootField.resultCardinality() == GraphqlRootField.ResultCardinality.MANY) {
            return "[" + rootField.typeName() + "!]!";
        }
        return rootField.typeName();
    }

    private static String fieldType(GraphqlFieldDescriptor field) {
        if (field.kind() == GraphqlFieldDescriptor.FieldKind.SCALAR) {
            return field.graphqlType();
        }
        if (field.relationCapabilities().paginationMode()
                == GraphqlFieldDescriptor.RelationPaginationMode.RELAY_CONNECTION) {
            return field.targetTypeName() + "Connection!";
        }
        if (field.relationCardinality() == GraphqlFieldDescriptor.RelationCardinality.MANY) {
            return "[" + field.targetTypeName() + "!]!";
        }
        return field.nullable() ? field.targetTypeName() : field.targetTypeName() + "!";
    }

    private static String fieldArguments(GraphqlFieldDescriptor field) {
        if (field.kind() != GraphqlFieldDescriptor.FieldKind.RELATION) {
            return "";
        }
        List<String> args = new ArrayList<>();
        for (GraphqlRelationArgumentDescriptor argument : field.relationArguments()) {
            args.add(argument.name() + ": " + relationArgumentType(argument));
        }
        return arguments(args);
    }

    private static List<String> rootArguments(GraphqlRootField rootField) {
        List<String> args = new ArrayList<>();
        if (rootField.rootPaginationMode() == GraphqlRootField.RootPaginationMode.RELAY_CONNECTION) {
            args.add("first: Int");
            args.add("after: String");
            args.add("last: Int");
            args.add("before: String");
        }
        if (rootField.resultCardinality() == GraphqlRootField.ResultCardinality.ONE
                && rootField.requiredIdArgumentName().isEmpty() == false) {
            args.add(rootField.requiredIdArgumentName() + ": Int!");
        }
        if (rootField.rootPaginationMode() != GraphqlRootField.RootPaginationMode.RELAY_CONNECTION
                && rootField.resultCardinality() == GraphqlRootField.ResultCardinality.MANY
                && rootField.limitArgumentName().isEmpty() == false) {
            args.add(rootField.limitArgumentName() + ": Int");
        }
        for (GraphqlRootArgumentDescriptor argument : rootField.filterArguments()) {
            args.add(argument.name() + ": Int");
        }
        if (rootField.retrievalCapabilities().supportsFilterArguments()) {
            args.add("filter: " + rootField.typeName() + "Filter");
        }
        if (rootField.sortPaths().isEmpty() == false) {
            args.add("orderBy: [" + rootField.typeName() + "OrderBy!]");
        }
        return args;
    }

    private static Set<String> connectionTypeNames(GraphqlSchema schema) {
        Set<String> names = new LinkedHashSet<>();
        for (GraphqlRootField rootField : schema.rootFields()) {
            if (rootField.rootPaginationMode() == GraphqlRootField.RootPaginationMode.RELAY_CONNECTION) {
                names.add(rootField.typeName());
            }
        }
        for (GraphqlObjectType type : schema.types()) {
            for (GraphqlFieldDescriptor field : type.fields()) {
                if (field.kind() == GraphqlFieldDescriptor.FieldKind.RELATION
                        && field.relationCapabilities().paginationMode()
                        == GraphqlFieldDescriptor.RelationPaginationMode.RELAY_CONNECTION) {
                    names.add(field.targetTypeName());
                }
            }
        }
        return names;
    }

    private static boolean connectionSupportsTotalCount(GraphqlSchema schema, String typeName) {
        for (GraphqlRootField rootField : schema.rootFields()) {
            if (rootField.rootPaginationMode() == GraphqlRootField.RootPaginationMode.RELAY_CONNECTION
                    && rootField.typeName().equals(typeName)
                    && rootField.retrievalCapabilities().supportsTotalCount()) {
                return true;
            }
        }
        for (GraphqlObjectType type : schema.types()) {
            for (GraphqlFieldDescriptor field : type.fields()) {
                if (field.kind() == GraphqlFieldDescriptor.FieldKind.RELATION
                        && field.relationCapabilities().paginationMode()
                        == GraphqlFieldDescriptor.RelationPaginationMode.RELAY_CONNECTION
                        && field.targetTypeName().equals(typeName)
                        && field.relationCapabilities().supportsTotalCount()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String arguments(List<String> args) {
        if (args.isEmpty()) {
            return "";
        }
        return "(" + String.join(", ", args) + ")";
    }

    private static Set<String> scalarFilterTypeNames(GraphqlSchema schema) {
        Set<String> names = new LinkedHashSet<>();
        for (GraphqlRootField rootField : schema.rootFields()) {
            GraphqlObjectType type = schema.type(rootField.typeName());
            if (type == null || shouldPrintRootFilter(rootField, schema) == false) {
                continue;
            }
            for (GraphqlFieldDescriptor field : type.fields()) {
                if (field.kind() == GraphqlFieldDescriptor.FieldKind.SCALAR
                        && field.scalarFilterCapabilities().operators().isEmpty() == false) {
                    names.add(field.graphqlType() + "Filter");
                }
            }
            for (GraphqlRootArgumentDescriptor argument : rootField.filterArguments()) {
                names.add(scalarFilterType(argument.name(), argument.columnName()));
            }
            for (GraphqlRootField.RootFieldFilterPath filterPath : rootField.filterPaths()) {
                names.add(filterPath.scalarType() + "Filter");
            }
        }
        return names;
    }

    private static boolean shouldPrintRootFilter(GraphqlRootField rootField, GraphqlSchema schema) {
        return rootField.retrievalCapabilities().supportsFilterArguments()
                && schema.type(rootField.typeName()) != null;
    }

    private static String scalarFilterFields(String scalarFilterType) {
        String scalarType = scalarFilterType.substring(0, scalarFilterType.length() - "Filter".length());
        return "  eq: " + scalarType + "\n"
                + "  neq: " + scalarType + "\n"
                + "  in: [" + scalarType + "!]\n"
                + "  isNull: Boolean\n"
                + numericFilterFields(scalarType)
                + stringFilterFields(scalarType);
    }

    private static String numericFilterFields(String scalarType) {
        if (scalarType.equals("Int") == false) {
            return "";
        }
        return "  lt: Int\n"
                + "  lte: Int\n"
                + "  gt: Int\n"
                + "  gte: Int\n";
    }

    private static String stringFilterFields(String scalarType) {
        if (scalarType.equals("String") == false) {
            return "";
        }
        return "  contains: String\n"
                + "  startsWith: String\n"
                + "  endsWith: String\n";
    }

    private static String objectFilterFields(GraphqlRootField rootField, GraphqlSchema schema) {
        GraphqlObjectType type = schema.type(rootField.typeName());
        Map<String, String> fields = new LinkedHashMap<>();
        for (GraphqlFieldDescriptor field : type.fields()) {
            if (field.kind() == GraphqlFieldDescriptor.FieldKind.SCALAR
                    && field.scalarFilterCapabilities().operators().isEmpty() == false) {
                fields.put(field.name(), field.graphqlType() + "Filter");
            }
        }
        for (GraphqlRootArgumentDescriptor argument : rootField.filterArguments()) {
            fields.put(argument.name(), scalarFilterType(argument.name(), argument.columnName()));
        }
        for (GraphqlRootField.RootFieldFilterPath filterPath : rootField.filterPaths()) {
            fields.put(filterPath.name(), filterPath.scalarType() + "Filter");
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            builder.append("  ")
                    .append(field.getKey())
                    .append(": ")
                    .append(field.getValue())
                    .append("\n");
        }
        String filterName = rootField.typeName() + "Filter";
        builder.append("  and: [").append(filterName).append("!]\n")
                .append("  or: [").append(filterName).append("!]\n")
                .append("  not: ").append(filterName).append("\n");
        return builder.toString();
    }

    private static String relationArgumentType(GraphqlRelationArgumentDescriptor argument) {
        return switch (argument.kind()) {
            case INT_EQUALS, RELAY_FIRST, RELAY_LAST -> "Int";
            case RELAY_AFTER, RELAY_BEFORE -> "String";
        };
    }

    private static String scalarType(String fieldName, String columnName) {
        String normalizedField = fieldName.toLowerCase();
        String normalizedColumn = columnName.toLowerCase();
        if (normalizedField.equals("id")
                || normalizedField.endsWith("id")
                || normalizedColumn.equals("id")
                || normalizedColumn.endsWith("_id")) {
            return "Int";
        }
        return "String";
    }

    private static String scalarFilterType(String fieldName, String columnName) {
        return scalarType(fieldName, columnName) + "Filter";
    }
}
