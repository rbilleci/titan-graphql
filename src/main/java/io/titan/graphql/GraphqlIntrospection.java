package io.titan.graphql;

import java.util.ArrayList;
import java.util.List;

final class GraphqlIntrospection {

    private GraphqlIntrospection() {
    }

    static boolean isIntrospectionOperation(GraphqlAst.AstOperation operation) {
        if (operation.selections().size() != 1 || operation.selections().getFirst() instanceof GraphqlAst.Field == false) {
            return false;
        }
        GraphqlAst.Field root = (GraphqlAst.Field) operation.selections().getFirst();
        return root.name().equals("__schema") || root.name().equals("__type");
    }

    static GraphqlExecution execute(GraphqlSchema schema, GraphqlAst.AstOperation operation) {
        if (operation.fragments().isEmpty() == false) {
            throw new GraphqlException("introspection fragments are not supported yet");
        }
        GraphqlAst.Field root = requireSingleRootField(operation);
        StringBuilder json = new StringBuilder();
        json.append("{\"data\":{\"").append(root.responseKey()).append("\":");
        if (root.name().equals("__schema")) {
            appendSchema(json, schema, root);
        } else if (root.name().equals("__type")) {
            appendType(json, schema, typeNameArgument(root), root.selections());
        } else {
            throw new GraphqlException("unsupported introspection root field '" + root.name() + "'");
        }
        json.append("}}");
        return new GraphqlExecution(json.toString(), new GraphqlPlan());
    }

    private static GraphqlAst.Field requireSingleRootField(GraphqlAst.AstOperation operation) {
        if (operation.selections().size() != 1 || operation.selections().getFirst() instanceof GraphqlAst.Field == false) {
            throw new GraphqlException("exactly one root field is supported");
        }
        GraphqlAst.Field root = (GraphqlAst.Field) operation.selections().getFirst();
        if (root.directives().isEmpty() == false) {
            throw new GraphqlException("introspection root field directives are not supported yet");
        }
        if (root.selections().isEmpty()) {
            throw new GraphqlException("field '" + root.name() + "' requires a selection set");
        }
        return root;
    }

    private static void appendSchema(StringBuilder json, GraphqlSchema schema, GraphqlAst.Field root) {
        requireNoArguments(root, "__schema");
        json.append('{');
        boolean first = true;
        for (GraphqlAst.Field field : fields(root.selections(), "__schema")) {
            if (!first) {
                json.append(',');
            }
            first = false;
            if (field.name().equals("queryType")) {
                json.append('"').append(field.responseKey()).append("\":");
                appendTypeRef(json, IntrospectionTypeRef.named("Query", "OBJECT"), field.selections());
            } else if (field.name().equals("description")) {
                if (field.selections().isEmpty() == false) {
                    throw new GraphqlException("field '__Schema.description' must not have a selection set");
                }
                appendNullField(json, field.responseKey());
            } else if (field.name().equals("mutationType")) {
                if (field.selections().isEmpty()) {
                    throw new GraphqlException("field '__Schema." + field.name() + "' requires a selection set");
                }
                json.append('"').append(field.responseKey()).append("\":");
                if (schema.mutations().isEmpty()) {
                    json.append("null");
                } else {
                    appendTypeRef(json, IntrospectionTypeRef.named("Mutation", "OBJECT"), field.selections());
                }
            } else if (field.name().equals("subscriptionType")) {
                if (field.selections().isEmpty()) {
                    throw new GraphqlException("field '__Schema." + field.name() + "' requires a selection set");
                }
                appendNullField(json, field.responseKey());
            } else if (field.name().equals("types")) {
                json.append('"').append(field.responseKey()).append("\":[");
                List<IntrospectionType> types = schemaTypes(schema);
                for (int i = 0; i < types.size(); i++) {
                    if (i > 0) {
                        json.append(',');
                    }
                    appendTypeRef(json, IntrospectionTypeRef.named(types.get(i).name(), types.get(i).kind()), field.selections());
                }
                json.append(']');
            } else if (field.name().equals("directives")) {
                json.append('"').append(field.responseKey()).append("\":[");
                List<IntrospectionDirective> directives = directives();
                for (int i = 0; i < directives.size(); i++) {
                    if (i > 0) {
                        json.append(',');
                    }
                    appendDirective(json, directives.get(i), field.selections());
                }
                json.append(']');
            } else {
                throw new GraphqlException("unsupported __Schema field '" + field.name() + "'");
            }
        }
        json.append('}');
    }

    private static void appendType(
            StringBuilder json,
            GraphqlSchema schema,
            String typeName,
            List<GraphqlAst.Selection> selections
    ) {
        IntrospectionType type = type(schema, typeName);
        if (type == null) {
            json.append("null");
            return;
        }
        json.append('{');
        boolean first = true;
        for (GraphqlAst.Field field : fields(selections, "__Type")) {
            if (!first) {
                json.append(',');
            }
            first = false;
            switch (field.name()) {
                case "name" -> appendStringField(json, field.responseKey(), type.name());
                case "kind" -> appendStringField(json, field.responseKey(), type.kind());
                case "description" -> appendNullField(json, field.responseKey());
                case "fields" -> {
                    json.append('"').append(field.responseKey()).append("\":");
                    appendFields(json, schema, type.name(), type.kind(), field.selections());
                }
                case "inputFields" -> {
                    json.append('"').append(field.responseKey()).append("\":");
                    appendInputFields(json, schema, type.name(), type.kind(), field.selections());
                }
                case "enumValues" -> {
                    json.append('"').append(field.responseKey()).append("\":");
                    appendEnumValues(json, type.name(), type.kind(), field.selections());
                }
                default -> throw new GraphqlException("unsupported __Type field '" + field.name() + "'");
            }
        }
        json.append('}');
    }

    private static void appendFields(
            StringBuilder json,
            GraphqlSchema schema,
            String typeName,
            String kind,
            List<GraphqlAst.Selection> selections
    ) {
        if (kind.equals("OBJECT") == false) {
            json.append("null");
            return;
        }
        List<IntrospectionField> fields = typeName.equals("Query")
                ? queryFields(schema)
                : typeName.equals("Mutation")
                        ? mutationFields(schema)
                        : objectFields(schema, typeName);
        json.append('[');
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendField(json, fields.get(i), selections);
        }
        json.append(']');
    }

    private static void appendEnumValues(
            StringBuilder json,
            String typeName,
            String kind,
            List<GraphqlAst.Selection> selections
    ) {
        if (kind.equals("ENUM") == false) {
            json.append("null");
            return;
        }
        List<IntrospectionEnumValue> values = enumValues(typeName);
        json.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendEnumValue(json, values.get(i), selections);
        }
        json.append(']');
    }

    private static void appendInputFields(
            StringBuilder json,
            GraphqlSchema schema,
            String typeName,
            String kind,
            List<GraphqlAst.Selection> selections
    ) {
        if (kind.equals("INPUT_OBJECT") == false) {
            json.append("null");
            return;
        }
        List<IntrospectionArgument> fields = inputFields(schema, typeName);
        json.append('[');
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendArgument(json, fields.get(i), selections);
        }
        json.append(']');
    }

    private static void appendField(StringBuilder json, IntrospectionField introspectionField, List<GraphqlAst.Selection> selections) {
        json.append('{');
        boolean first = true;
        for (GraphqlAst.Field field : fields(selections, "__Field")) {
            if (!first) {
                json.append(',');
            }
            first = false;
            if (field.name().equals("name")) {
                appendStringField(json, field.responseKey(), introspectionField.name());
            } else if (field.name().equals("description")) {
                appendNullField(json, field.responseKey());
            } else if (field.name().equals("isDeprecated")) {
                appendBooleanField(json, field.responseKey(), false);
            } else if (field.name().equals("deprecationReason")) {
                appendNullField(json, field.responseKey());
            } else if (field.name().equals("type")) {
                json.append('"').append(field.responseKey()).append("\":");
                appendTypeRef(json, introspectionField.type(), field.selections());
            } else if (field.name().equals("args")) {
                json.append('"').append(field.responseKey()).append("\":");
                appendArguments(json, introspectionField.arguments(), field.selections());
            } else {
                throw new GraphqlException("unsupported __Field field '" + field.name() + "'");
            }
        }
        json.append('}');
    }

    private static void appendEnumValue(
            StringBuilder json,
            IntrospectionEnumValue enumValue,
            List<GraphqlAst.Selection> selections
    ) {
        json.append('{');
        boolean first = true;
        for (GraphqlAst.Field field : fields(selections, "__EnumValue")) {
            if (!first) {
                json.append(',');
            }
            first = false;
            if (field.name().equals("name")) {
                appendStringField(json, field.responseKey(), enumValue.name());
            } else if (field.name().equals("description")) {
                appendNullField(json, field.responseKey());
            } else if (field.name().equals("isDeprecated")) {
                appendBooleanField(json, field.responseKey(), false);
            } else if (field.name().equals("deprecationReason")) {
                appendNullField(json, field.responseKey());
            } else {
                throw new GraphqlException("unsupported __EnumValue field '" + field.name() + "'");
            }
        }
        json.append('}');
    }

    private static void appendArguments(
            StringBuilder json,
            List<IntrospectionArgument> arguments,
            List<GraphqlAst.Selection> selections
    ) {
        json.append('[');
        for (int i = 0; i < arguments.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendArgument(json, arguments.get(i), selections);
        }
        json.append(']');
    }

    private static void appendDirective(
            StringBuilder json,
            IntrospectionDirective directive,
            List<GraphqlAst.Selection> selections
    ) {
        json.append('{');
        boolean first = true;
        for (GraphqlAst.Field field : fields(selections, "__Directive")) {
            if (!first) {
                json.append(',');
            }
            first = false;
            if (field.name().equals("name")) {
                appendStringField(json, field.responseKey(), directive.name());
            } else if (field.name().equals("description")) {
                appendNullField(json, field.responseKey());
            } else if (field.name().equals("isRepeatable")) {
                appendBooleanField(json, field.responseKey(), directive.repeatable());
            } else if (field.name().equals("locations")) {
                json.append('"').append(field.responseKey()).append("\":[");
                for (int i = 0; i < directive.locations().size(); i++) {
                    if (i > 0) {
                        json.append(',');
                    }
                    json.append('"').append(directive.locations().get(i)).append('"');
                }
                json.append(']');
            } else if (field.name().equals("args")) {
                json.append('"').append(field.responseKey()).append("\":");
                appendArguments(json, directive.arguments(), field.selections());
            } else {
                throw new GraphqlException("unsupported __Directive field '" + field.name() + "'");
            }
        }
        json.append('}');
    }

    private static void appendArgument(
            StringBuilder json,
            IntrospectionArgument introspectionArgument,
            List<GraphqlAst.Selection> selections
    ) {
        json.append('{');
        boolean first = true;
        for (GraphqlAst.Field field : fields(selections, "__InputValue")) {
            if (!first) {
                json.append(',');
            }
            first = false;
            if (field.name().equals("name")) {
                appendStringField(json, field.responseKey(), introspectionArgument.name());
            } else if (field.name().equals("description")) {
                appendNullField(json, field.responseKey());
            } else if (field.name().equals("type")) {
                json.append('"').append(field.responseKey()).append("\":");
                appendTypeRef(json, introspectionArgument.type(), field.selections());
            } else if (field.name().equals("defaultValue")) {
                json.append('"').append(field.responseKey()).append("\":null");
            } else if (field.name().equals("isDeprecated")) {
                appendBooleanField(json, field.responseKey(), false);
            } else if (field.name().equals("deprecationReason")) {
                appendNullField(json, field.responseKey());
            } else {
                throw new GraphqlException("unsupported __InputValue field '" + field.name() + "'");
            }
        }
        json.append('}');
    }

    private static void appendTypeRef(
            StringBuilder json,
            IntrospectionTypeRef type,
            List<GraphqlAst.Selection> selections
    ) {
        json.append('{');
        boolean first = true;
        for (GraphqlAst.Field field : fields(selections, "__Type")) {
            if (!first) {
                json.append(',');
            }
            first = false;
            if (field.name().equals("name")) {
                appendNullableStringField(json, field.responseKey(), type.name());
            } else if (field.name().equals("kind")) {
                appendStringField(json, field.responseKey(), type.kind());
            } else if (field.name().equals("description")) {
                appendNullField(json, field.responseKey());
            } else if (field.name().equals("ofType")) {
                json.append('"').append(field.responseKey()).append("\":");
                if (type.ofType() == null) {
                    json.append("null");
                } else {
                    appendTypeRef(json, type.ofType(), field.selections());
                }
            } else {
                throw new GraphqlException("unsupported __Type reference field '" + field.name() + "'");
            }
        }
        json.append('}');
    }

    private static List<GraphqlAst.Field> fields(List<GraphqlAst.Selection> selections, String parentName) {
        if (selections.isEmpty()) {
            throw new GraphqlException("field '" + parentName + "' requires a selection set");
        }
        List<GraphqlAst.Field> fields = new ArrayList<>();
        for (GraphqlAst.Selection selection : selections) {
            if (selection instanceof GraphqlAst.Field field) {
                if (field.directives().isEmpty() == false) {
                    throw new GraphqlException("introspection field directives are not supported yet");
                }
                validateArguments(field, parentName + "." + field.name());
                fields.add(field);
            } else {
                throw new GraphqlException("introspection fragments are not supported yet");
            }
        }
        return List.copyOf(fields);
    }

    private static void validateArguments(GraphqlAst.Field field, String fieldName) {
        if (fieldName.equals("__Type.fields")
                || fieldName.equals("__Type.inputFields")
                || fieldName.equals("__Type.enumValues")) {
            requireOptionalIncludeDeprecatedArgument(field, fieldName);
            return;
        }
        requireNoArguments(field, fieldName);
    }

    private static void requireOptionalIncludeDeprecatedArgument(GraphqlAst.Field field, String fieldName) {
        if (field.arguments().isEmpty()) {
            return;
        }
        if (field.arguments().size() != 1 || field.arguments().containsKey("includeDeprecated") == false) {
            throw new GraphqlException("field '" + fieldName + "' supports only argument 'includeDeprecated'");
        }
        if (field.arguments().get("includeDeprecated") instanceof GraphqlAst.BooleanValue == false) {
            throw new GraphqlException("argument '" + fieldName + ".includeDeprecated' must be Boolean");
        }
    }

    private static void requireNoArguments(GraphqlAst.Field field, String fieldName) {
        if (field.arguments().isEmpty() == false) {
            throw new GraphqlException("field '" + fieldName + "' does not support arguments");
        }
    }

    private static String typeNameArgument(GraphqlAst.Field root) {
        if (root.arguments().size() != 1 || root.arguments().containsKey("name") == false) {
            throw new GraphqlException("field '__type' requires argument 'name'");
        }
        GraphqlAst.Value value = root.arguments().get("name");
        if (value instanceof GraphqlAst.StringValue stringValue) {
            return stringValue.value();
        }
        if (value instanceof GraphqlAst.IdValue idValue) {
            return idValue.value();
        }
        throw new GraphqlException("argument '__type.name' must be a string");
    }

    private static List<IntrospectionType> schemaTypes(GraphqlSchema schema) {
        List<IntrospectionType> types = new ArrayList<>();
        types.add(new IntrospectionType("Query", "OBJECT"));
        if (schema.mutations().isEmpty() == false) {
            types.add(new IntrospectionType("Mutation", "OBJECT"));
        }
        for (GraphqlObjectType type : schema.types()) {
            types.add(new IntrospectionType(type.name(), "OBJECT"));
        }
        addScalarTypes(types, schema);
        for (GraphqlRootField rootField : schema.rootFields()) {
            if (rootField.rootPaginationMode() == GraphqlRootField.RootPaginationMode.RELAY_CONNECTION) {
                types.add(new IntrospectionType(rootField.typeName() + "Connection", "OBJECT"));
                types.add(new IntrospectionType(rootField.typeName() + "Edge", "OBJECT"));
            }
        }
        for (GraphqlObjectType objectType : schema.types()) {
            for (GraphqlFieldDescriptor field : objectType.fields()) {
                if (field.kind() == GraphqlFieldDescriptor.FieldKind.RELATION
                        && field.relationCapabilities().paginationMode()
                        == GraphqlFieldDescriptor.RelationPaginationMode.RELAY_CONNECTION) {
                    addType(types, field.targetTypeName() + "Connection", "OBJECT");
                    addType(types, field.targetTypeName() + "Edge", "OBJECT");
                }
            }
        }
        types.add(new IntrospectionType("PageInfo", "OBJECT"));
        for (String scalarFilterType : scalarFilterTypeNames(schema)) {
            addType(types, scalarFilterType, "INPUT_OBJECT");
        }
        for (GraphqlRootField rootField : schema.rootFields()) {
            if (shouldExposeRootFilter(rootField, schema)) {
                addType(types, rootField.typeName() + "Filter", "INPUT_OBJECT");
            }
        }
        if (hasRootSortPaths(schema)) {
            addType(types, "SortDirection", "ENUM");
        }
        for (GraphqlRootField rootField : schema.rootFields()) {
            if (rootField.sortPaths().isEmpty() == false) {
                addType(types, rootField.typeName() + "OrderBy", "INPUT_OBJECT");
            }
        }
        for (GraphqlMutationDescriptor mutation : schema.mutations()) {
            addType(types, mutation.input().name(), "INPUT_OBJECT");
            addType(types, mutation.payload().name(), "OBJECT");
        }
        return List.copyOf(types);
    }

    private static void addScalarTypes(List<IntrospectionType> types, GraphqlSchema schema) {
        addType(types, "Boolean", "SCALAR");
        for (GraphqlObjectType objectType : schema.types()) {
            for (GraphqlFieldDescriptor field : objectType.fields()) {
                if (field.kind() == GraphqlFieldDescriptor.FieldKind.SCALAR) {
                    addType(types, field.graphqlType(), "SCALAR");
                }
            }
        }
        if (schema.mutations().isEmpty() == false) {
            for (GraphqlMutationDescriptor mutation : schema.mutations()) {
                addScalarType(types, mutation.input().fields());
                addScalarPayloadType(types, mutation.payload().fields());
            }
        }
    }

    private static void addScalarType(List<IntrospectionType> types, List<GraphqlMutationDescriptor.InputField> fields) {
        for (GraphqlMutationDescriptor.InputField field : fields) {
            String type = namedType(field.graphqlType());
            if (scalarKind(type, "").equals("SCALAR")) {
                addType(types, type, "SCALAR");
            }
        }
    }

    private static void addScalarPayloadType(List<IntrospectionType> types, List<GraphqlMutationDescriptor.PayloadField> fields) {
        for (GraphqlMutationDescriptor.PayloadField field : fields) {
            String type = namedType(field.graphqlType());
            if (scalarKind(type, "").equals("SCALAR")) {
                addType(types, type, "SCALAR");
            }
        }
    }

    private static void addType(List<IntrospectionType> types, String name, String kind) {
        if (name.isBlank()) {
            return;
        }
        for (IntrospectionType type : types) {
            if (type.name().equals(name)) {
                return;
            }
        }
        types.add(new IntrospectionType(name, kind));
    }

    private static IntrospectionType type(GraphqlSchema schema, String typeName) {
        if (typeName.equals("Query")) {
            return new IntrospectionType("Query", "OBJECT");
        }
        if (typeName.equals("Mutation") && schema.mutations().isEmpty() == false) {
            return new IntrospectionType("Mutation", "OBJECT");
        }
        GraphqlObjectType objectType = schema.type(typeName);
        if (objectType != null) {
            return new IntrospectionType(objectType.name(), "OBJECT");
        }
        for (IntrospectionType type : schemaTypes(schema)) {
            if (type.name().equals(typeName)) {
                return type;
            }
        }
        return null;
    }

    private static List<IntrospectionField> queryFields(GraphqlSchema schema) {
        List<IntrospectionField> fields = new ArrayList<>();
        for (GraphqlRootField rootField : schema.rootFields()) {
            fields.add(new IntrospectionField(
                    rootField.name(),
                    rootField.rootPaginationMode() == GraphqlRootField.RootPaginationMode.RELAY_CONNECTION
                            ? IntrospectionTypeRef.nonNull(
                                    IntrospectionTypeRef.named(rootField.typeName() + "Connection", "OBJECT"))
                            : IntrospectionTypeRef.named(rootField.typeName(), "OBJECT"),
                    rootArguments(rootField)
            ));
        }
        return List.copyOf(fields);
    }

    private static List<IntrospectionField> mutationFields(GraphqlSchema schema) {
        List<IntrospectionField> fields = new ArrayList<>();
        for (GraphqlMutationDescriptor mutation : schema.mutations()) {
            fields.add(new IntrospectionField(
                    mutation.name(),
                    IntrospectionTypeRef.nonNull(IntrospectionTypeRef.named(mutation.payload().name(), "OBJECT")),
                    List.of(new IntrospectionArgument(
                            "input",
                            IntrospectionTypeRef.nonNull(IntrospectionTypeRef.named(mutation.input().name(), "INPUT_OBJECT"))
                    ))
            ));
        }
        return List.copyOf(fields);
    }

    private static List<IntrospectionField> objectFields(GraphqlSchema schema, String typeName) {
        GraphqlObjectType objectType = schema.type(typeName);
        List<IntrospectionField> fields = new ArrayList<>();
        if (objectType == null) {
            for (GraphqlMutationDescriptor mutation : schema.mutations()) {
                if (mutation.payload().name().equals(typeName)) {
                    for (GraphqlMutationDescriptor.PayloadField field : mutation.payload().fields()) {
                        fields.add(new IntrospectionField(
                                field.name(),
                                typeRef(field.graphqlType(), field.required(), "OBJECT"),
                                List.of()
                        ));
                    }
                    return List.copyOf(fields);
                }
            }
            return List.of();
        }
        for (GraphqlFieldDescriptor field : objectType.fields()) {
            if (field.kind() == GraphqlFieldDescriptor.FieldKind.SCALAR) {
                fields.add(new IntrospectionField(
                        field.name(),
                        IntrospectionTypeRef.named(field.graphqlType(), "SCALAR"),
                        List.of()
                ));
            } else {
                IntrospectionTypeRef type = field.relationCapabilities().paginationMode()
                        == GraphqlFieldDescriptor.RelationPaginationMode.RELAY_CONNECTION
                                ? IntrospectionTypeRef.nonNull(
                                        IntrospectionTypeRef.named(field.targetTypeName() + "Connection", "OBJECT"))
                                : IntrospectionTypeRef.nonNull(
                                        IntrospectionTypeRef.named(field.targetTypeName(), "OBJECT"));
                fields.add(new IntrospectionField(
                        field.name(),
                        type,
                        relationArguments(field)
                ));
            }
        }
        return List.copyOf(fields);
    }

    private static List<IntrospectionArgument> rootArguments(GraphqlRootField rootField) {
        List<IntrospectionArgument> arguments = new ArrayList<>();
        if (rootField.requiredIdArgumentName().isBlank() == false) {
            arguments.add(new IntrospectionArgument(
                    rootField.requiredIdArgumentName(),
                    IntrospectionTypeRef.nonNull(IntrospectionTypeRef.named("Int", "SCALAR"))
            ));
        }
        if (rootField.rootPaginationMode() == GraphqlRootField.RootPaginationMode.RELAY_CONNECTION) {
            arguments.add(new IntrospectionArgument("first", IntrospectionTypeRef.named("Int", "SCALAR")));
            arguments.add(new IntrospectionArgument("after", IntrospectionTypeRef.named("String", "SCALAR")));
            arguments.add(new IntrospectionArgument("last", IntrospectionTypeRef.named("Int", "SCALAR")));
            arguments.add(new IntrospectionArgument("before", IntrospectionTypeRef.named("String", "SCALAR")));
        } else if (rootField.limitArgumentName().isBlank() == false) {
            arguments.add(new IntrospectionArgument(rootField.limitArgumentName(), IntrospectionTypeRef.named("Int", "SCALAR")));
        }
        for (GraphqlRootArgumentDescriptor argument : rootField.filterArguments()) {
            arguments.add(new IntrospectionArgument(argument.name(), IntrospectionTypeRef.named("Int", "SCALAR")));
        }
        if (rootField.filterPaths().isEmpty() == false) {
            arguments.add(new IntrospectionArgument(
                    "filter",
                    IntrospectionTypeRef.named(rootField.typeName() + "Filter", "INPUT_OBJECT")
            ));
        }
        if (rootField.sortPaths().isEmpty() == false) {
            arguments.add(new IntrospectionArgument(
                    "orderBy",
                    IntrospectionTypeRef.list(
                            IntrospectionTypeRef.nonNull(
                                    IntrospectionTypeRef.named(rootField.typeName() + "OrderBy", "INPUT_OBJECT")))
            ));
        }
        return List.copyOf(arguments);
    }

    private static List<IntrospectionArgument> relationArguments(GraphqlFieldDescriptor field) {
        List<IntrospectionArgument> arguments = new ArrayList<>();
        for (GraphqlRelationArgumentDescriptor argument : field.relationArguments()) {
            arguments.add(new IntrospectionArgument(
                    argument.name(),
                    IntrospectionTypeRef.named(relationArgumentType(argument), "SCALAR")
            ));
        }
        return List.copyOf(arguments);
    }

    private static String relationArgumentType(GraphqlRelationArgumentDescriptor argument) {
        return switch (argument.kind()) {
            case INT_EQUALS, RELAY_FIRST, RELAY_LAST -> "Int";
            case RELAY_AFTER, RELAY_BEFORE -> "String";
        };
    }

    private static List<IntrospectionArgument> inputFields(GraphqlSchema schema, String typeName) {
        if (typeName.equals("IntFilter")) {
            return scalarFilterFields("Int");
        }
        if (typeName.equals("StringFilter")) {
            return scalarFilterFields("String");
        }
        for (GraphqlMutationDescriptor mutation : schema.mutations()) {
            if (mutation.input().name().equals(typeName)) {
                List<IntrospectionArgument> fields = new ArrayList<>();
                for (GraphqlMutationDescriptor.InputField field : mutation.input().fields()) {
                    fields.add(new IntrospectionArgument(
                            field.name(),
                            typeRef(field.graphqlType(), field.required(), "INPUT_OBJECT")
                    ));
                }
                return List.copyOf(fields);
            }
        }
        if (typeName.endsWith("Filter")) {
            String objectTypeName = typeName.substring(0, typeName.length() - "Filter".length());
            for (GraphqlRootField rootField : schema.rootFields()) {
                if (rootField.typeName().equals(objectTypeName) && shouldExposeRootFilter(rootField, schema)) {
                    return objectFilterFields(schema, rootField);
                }
            }
        }
        if (typeName.endsWith("OrderBy")) {
            String objectTypeName = typeName.substring(0, typeName.length() - "OrderBy".length());
            for (GraphqlRootField rootField : schema.rootFields()) {
                if (rootField.typeName().equals(objectTypeName) && rootField.sortPaths().isEmpty() == false) {
                    return objectOrderFields(rootField);
                }
            }
        }
        return List.of();
    }

    private static List<IntrospectionArgument> scalarFilterFields(String scalarType) {
        List<IntrospectionArgument> fields = new ArrayList<>();
        IntrospectionTypeRef scalar = IntrospectionTypeRef.named(scalarType, "SCALAR");
        fields.add(new IntrospectionArgument("eq", scalar));
        fields.add(new IntrospectionArgument("neq", scalar));
        fields.add(new IntrospectionArgument(
                "in",
                IntrospectionTypeRef.list(IntrospectionTypeRef.nonNull(scalar))
        ));
        fields.add(new IntrospectionArgument("isNull", IntrospectionTypeRef.named("Boolean", "SCALAR")));
        if (scalarType.equals("Int")) {
            fields.add(new IntrospectionArgument("lt", scalar));
            fields.add(new IntrospectionArgument("lte", scalar));
            fields.add(new IntrospectionArgument("gt", scalar));
            fields.add(new IntrospectionArgument("gte", scalar));
        }
        if (scalarType.equals("String")) {
            fields.add(new IntrospectionArgument("contains", scalar));
            fields.add(new IntrospectionArgument("startsWith", scalar));
            fields.add(new IntrospectionArgument("endsWith", scalar));
        }
        return List.copyOf(fields);
    }

    private static List<IntrospectionArgument> objectFilterFields(GraphqlSchema schema, GraphqlRootField rootField) {
        List<IntrospectionArgument> fields = new ArrayList<>();
        GraphqlObjectType type = schema.type(rootField.typeName());
        for (GraphqlFieldDescriptor field : type.fields()) {
            if (field.kind() == GraphqlFieldDescriptor.FieldKind.SCALAR
                    && field.scalarFilterCapabilities().operators().isEmpty() == false) {
                fields.add(new IntrospectionArgument(
                        field.name(),
                        IntrospectionTypeRef.named(field.graphqlType() + "Filter", "INPUT_OBJECT")
                ));
            }
        }
        for (GraphqlRootArgumentDescriptor argument : rootField.filterArguments()) {
            fields.add(new IntrospectionArgument(
                    argument.name(),
                    IntrospectionTypeRef.named(scalarFilterType(argument.name(), argument.columnName()), "INPUT_OBJECT")
            ));
        }
        for (GraphqlRootField.RootFieldFilterPath filterPath : rootField.filterPaths()) {
            fields.add(new IntrospectionArgument(
                    filterPath.name(),
                    IntrospectionTypeRef.named(filterPath.scalarType() + "Filter", "INPUT_OBJECT")
            ));
        }
        IntrospectionTypeRef self = IntrospectionTypeRef.named(rootField.typeName() + "Filter", "INPUT_OBJECT");
        fields.add(new IntrospectionArgument("and", IntrospectionTypeRef.list(IntrospectionTypeRef.nonNull(self))));
        fields.add(new IntrospectionArgument("or", IntrospectionTypeRef.list(IntrospectionTypeRef.nonNull(self))));
        fields.add(new IntrospectionArgument("not", self));
        return List.copyOf(fields);
    }

    private static List<IntrospectionArgument> objectOrderFields(GraphqlRootField rootField) {
        List<IntrospectionArgument> fields = new ArrayList<>();
        for (GraphqlRootField.RootFieldSortPath sortPath : rootField.sortPaths()) {
            fields.add(new IntrospectionArgument(
                    sortPath.name(),
                    IntrospectionTypeRef.named("SortDirection", "ENUM")
            ));
        }
        return List.copyOf(fields);
    }

    private static IntrospectionTypeRef typeRef(String graphqlType, boolean required, String defaultKind) {
        boolean nonNull = required;
        String type = graphqlType;
        while (type.endsWith("!")) {
            nonNull = true;
            type = type.substring(0, type.length() - 1);
        }
        IntrospectionTypeRef ref = IntrospectionTypeRef.named(type, scalarKind(type, defaultKind));
        return nonNull ? IntrospectionTypeRef.nonNull(ref) : ref;
    }

    private static String namedType(String graphqlType) {
        String type = graphqlType;
        while (type.endsWith("!")) {
            type = type.substring(0, type.length() - 1);
        }
        return type;
    }

    private static String scalarKind(String type, String defaultKind) {
        return switch (type) {
            case "Boolean", "ID", "Int", "String" -> "SCALAR";
            default -> defaultKind;
        };
    }

    private static List<String> scalarFilterTypeNames(GraphqlSchema schema) {
        List<String> names = new ArrayList<>();
        for (GraphqlRootField rootField : schema.rootFields()) {
            GraphqlObjectType type = schema.type(rootField.typeName());
            if (type == null || shouldExposeRootFilter(rootField, schema) == false) {
                continue;
            }
            for (GraphqlFieldDescriptor field : type.fields()) {
                if (field.kind() == GraphqlFieldDescriptor.FieldKind.SCALAR
                        && field.scalarFilterCapabilities().operators().isEmpty() == false) {
                    addName(names, field.graphqlType() + "Filter");
                }
            }
            for (GraphqlRootArgumentDescriptor argument : rootField.filterArguments()) {
                addName(names, scalarFilterType(argument.name(), argument.columnName()));
            }
            for (GraphqlRootField.RootFieldFilterPath filterPath : rootField.filterPaths()) {
                addName(names, filterPath.scalarType() + "Filter");
            }
        }
        return List.copyOf(names);
    }

    private static void addName(List<String> names, String name) {
        if (names.contains(name) == false) {
            names.add(name);
        }
    }

    private static boolean shouldExposeRootFilter(GraphqlRootField rootField, GraphqlSchema schema) {
        return rootField.retrievalCapabilities().supportsFilterArguments()
                && schema.type(rootField.typeName()) != null;
    }

    private static boolean hasRootSortPaths(GraphqlSchema schema) {
        for (GraphqlRootField rootField : schema.rootFields()) {
            if (rootField.sortPaths().isEmpty() == false) {
                return true;
            }
        }
        return false;
    }

    private static List<IntrospectionEnumValue> enumValues(String typeName) {
        if (typeName.equals("SortDirection")) {
            return List.of(new IntrospectionEnumValue("ASC"), new IntrospectionEnumValue("DESC"));
        }
        return List.of();
    }

    private static List<IntrospectionDirective> directives() {
        return List.of(
                new IntrospectionDirective(
                        "include",
                        false,
                        List.of("FIELD", "FRAGMENT_SPREAD", "INLINE_FRAGMENT"),
                        List.of(new IntrospectionArgument(
                                "if",
                                IntrospectionTypeRef.nonNull(IntrospectionTypeRef.named("Boolean", "SCALAR"))))
                ),
                new IntrospectionDirective(
                        "skip",
                        false,
                        List.of("FIELD", "FRAGMENT_SPREAD", "INLINE_FRAGMENT"),
                        List.of(new IntrospectionArgument(
                                "if",
                                IntrospectionTypeRef.nonNull(IntrospectionTypeRef.named("Boolean", "SCALAR"))))
                ),
                new IntrospectionDirective(
                        "relationSortPath",
                        true,
                        List.of("FIELD_DEFINITION"),
                        List.of(
                                new IntrospectionArgument(
                                        "name",
                                        IntrospectionTypeRef.nonNull(IntrospectionTypeRef.named("String", "SCALAR"))),
                                new IntrospectionArgument(
                                        "column",
                                        IntrospectionTypeRef.nonNull(IntrospectionTypeRef.named("String", "SCALAR"))),
                                new IntrospectionArgument(
                                        "path",
                                        IntrospectionTypeRef.nonNull(IntrospectionTypeRef.named("String", "SCALAR"))),
                                new IntrospectionArgument(
                                        "hops",
                                        IntrospectionTypeRef.nonNull(IntrospectionTypeRef.named("Int", "SCALAR"))),
                                new IntrospectionArgument(
                                        "direction",
                                        IntrospectionTypeRef.nonNull(IntrospectionTypeRef.named("String", "SCALAR"))),
                                new IntrospectionArgument(
                                        "tieBreaker",
                                        IntrospectionTypeRef.nonNull(IntrospectionTypeRef.named("String", "SCALAR")))
                        )
                )
        );
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

    private static void appendStringField(StringBuilder json, String name, String value) {
        json.append('"').append(name).append("\":\"").append(GraphqlJsonWriter.escape(value)).append('"');
    }

    private static void appendNullableStringField(StringBuilder json, String name, String value) {
        json.append('"').append(name).append("\":");
        if (value == null) {
            json.append("null");
        } else {
            json.append('"').append(GraphqlJsonWriter.escape(value)).append('"');
        }
    }

    private static void appendNullField(StringBuilder json, String name) {
        json.append('"').append(name).append("\":null");
    }

    private static void appendBooleanField(StringBuilder json, String name, boolean value) {
        json.append('"').append(name).append("\":").append(value);
    }

    private record IntrospectionType(String name, String kind) {
    }

    private record IntrospectionField(
            String name,
            IntrospectionTypeRef type,
            List<IntrospectionArgument> arguments
    ) {
        private IntrospectionField {
            arguments = List.copyOf(arguments);
        }
    }

    private record IntrospectionArgument(String name, IntrospectionTypeRef type) {
    }

    private record IntrospectionEnumValue(String name) {
    }

    private record IntrospectionDirective(
            String name,
            boolean repeatable,
            List<String> locations,
            List<IntrospectionArgument> arguments
    ) {
        private IntrospectionDirective {
            locations = List.copyOf(locations);
            arguments = List.copyOf(arguments);
        }
    }

    private record IntrospectionTypeRef(String name, String kind, IntrospectionTypeRef ofType) {
        static IntrospectionTypeRef named(String name, String kind) {
            return new IntrospectionTypeRef(name, kind, null);
        }

        static IntrospectionTypeRef nonNull(IntrospectionTypeRef ofType) {
            return new IntrospectionTypeRef(null, "NON_NULL", ofType);
        }

        static IntrospectionTypeRef list(IntrospectionTypeRef ofType) {
            return new IntrospectionTypeRef(null, "LIST", ofType);
        }
    }
}
