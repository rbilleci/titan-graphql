package io.titan.graphql;

import io.titan.graphql.model.TitanGraphqlContextFilterDocument;
import io.titan.graphql.model.TitanGraphqlFieldDocument;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlPolicyDocument;
import io.titan.graphql.model.TitanGraphqlRelationDocument;
import io.titan.graphql.model.TitanGraphqlRootDocument;
import io.titan.graphql.model.TitanGraphqlTypeDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TitanGraphqlProjectionModelAdapter {

    private TitanGraphqlProjectionModelAdapter() {
    }

    static ProjectionModel adapt(TitanGraphqlModelDocument document, GraphqlPolicy policy) {
        if (!TitanGraphqlModelDocument.CURRENT_API_VERSION.equals(document.apiVersion())) {
            throw unsupported("UNSUPPORTED_API_VERSION", "unsupported apiVersion '" + document.apiVersion() + "'");
        }
        if (!TitanGraphqlModelDocument.PROJECTION_MODEL_KIND.equals(document.kind())) {
            throw unsupported("UNSUPPORTED_KIND", "unsupported kind '" + document.kind() + "'");
        }

        AdapterContext context = new AdapterContext(document, policy);
        List<ProjectionRetrieval> retrievals = new ArrayList<>();
        for (TitanGraphqlRootDocument root : document.roots()) {
            retrievals.add(root(root, context));
        }
        List<ProjectionType> types = new ArrayList<>();
        for (TitanGraphqlTypeDocument type : document.types()) {
            types.add(type(type, context));
        }
        return new ProjectionModel(retrievals, types);
    }

    private static ProjectionRetrieval root(TitanGraphqlRootDocument root, AdapterContext context) {
        return switch (root.operation()) {
            case POINT -> pointRoot(root, context);
            case CONNECTION -> connectionRoot(root, context);
        };
    }

    private static ProjectionRetrieval pointRoot(TitanGraphqlRootDocument root, AdapterContext context) {
        if (root.argument() != null && !root.arguments().isEmpty()) {
            throw unsupported("UNSUPPORTED_ROOT_ARGUMENT", "point root '" + root.name()
                    + "' cannot declare both argument and arguments");
        }
        List<TitanGraphqlRootDocument.RootDocumentArgument> modelArguments = root.argument() == null
                ? root.arguments() : List.of(root.argument());
        if (modelArguments.isEmpty()) {
            throw unsupported("UNSUPPORTED_ROOT_ARGUMENT", "point root '" + root.name()
                    + "' requires one or more key arguments");
        }
        String primaryKey = context.primaryKeyForType(root.type());
        if (modelArguments.size() == 1 && !primaryKey.equals(modelArguments.getFirst().column())) {
            throw unsupported("UNSUPPORTED_POINT_ROOT_KEY",
                    "point root '" + root.name() + "' maps argument '" + modelArguments.getFirst().name()
                            + "' to column '" + modelArguments.getFirst().column()
                            + "' but a scalar point key requires the single primary key column '"
                            + primaryKey + "'");
        }
        List<ProjectionRetrieval.RetrievalKeyArgument> keyArguments = new ArrayList<>();
        Map<String, Boolean> names = new LinkedHashMap<>();
        Map<String, Boolean> columns = new LinkedHashMap<>();
        TitanGraphqlTypeDocument type = context.type(root.type());
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : modelArguments.stream()
                .sorted(java.util.Comparator.comparing(
                        TitanGraphqlRootDocument.RootDocumentArgument::name)).toList()) {
            requireRootArgumentKind(argument, TitanGraphqlRootDocument.RootDocumentArgumentKind.EQUALS, root.name());
            if (argument.hops() != 0) {
                throw unsupported("UNSUPPORTED_POINT_ROOT_KEY", "point root '" + root.name()
                        + "' key argument '" + argument.name() + "' cannot traverse relations");
            }
            requirePointKeyType(argument.type(), "point root '" + root.name()
                    + "' argument '" + argument.name() + "'");
            TitanGraphqlFieldDocument field = type.fields().stream()
                    .filter(candidate -> argument.column().equals(candidate.column()))
                    .findFirst()
                    .orElseThrow(() -> unsupported("UNSUPPORTED_POINT_ROOT_KEY", "point root '"
                            + root.name() + "' key column '" + argument.column()
                            + "' is not a scalar field on type '" + root.type() + "'"));
            if (!normalizeType(field.type()).equals(normalizeType(argument.type()))) {
                throw unsupported("UNSUPPORTED_ARGUMENT_TYPE", "point root '" + root.name()
                        + "' argument '" + argument.name() + "' type '" + argument.type()
                        + "' does not match field type '" + field.type() + "'");
            }
            if (names.put(argument.name(), true) != null || columns.put(argument.column(), true) != null) {
                throw unsupported("UNSUPPORTED_POINT_ROOT_KEY", "point root '" + root.name()
                        + "' has duplicate key argument names or columns");
            }
            keyArguments.add(new ProjectionRetrieval.RetrievalKeyArgument(
                    argument.name(), normalizeType(argument.type()), argument.column()));
        }
        return ProjectionRetrieval.point(root.name(), root.type(), keyArguments);
    }

    private static void requirePointKeyType(String type, String context) {
        if (!List.of("Int", "Long", "String", "ID", "UUID").contains(normalizeType(type))) {
            throw unsupported("UNSUPPORTED_ARGUMENT_TYPE", context
                    + " must be Int, Long, String, ID, or UUID");
        }
    }

    private static String normalizeType(String type) {
        return type == null ? "" : type.replace("!", "").trim();
    }

    private static ProjectionRetrieval connectionRoot(TitanGraphqlRootDocument root, AdapterContext context) {
        TitanGraphqlRootDocument.RootDocumentPagination pagination = root.pagination();
        if (pagination == null) {
            throw unsupported("UNSUPPORTED_ROOT_PAGINATION", "connection root '" + root.name() + "' requires relay pagination");
        }
        if (pagination.totalCount() != TitanGraphqlRootDocument.TotalCountMode.EXACT) {
            throw unsupported("UNSUPPORTED_ROOT_TOTAL_COUNT",
                    "connection root '" + root.name() + "' requires exact totalCount");
        }
        return ProjectionRetrieval.relayConnection(
                root.name(),
                root.type(),
                pagination.defaultPageSize(),
                pagination.maxPageSize(),
                rootArguments(root),
                cursorOrdering(root, pagination.cursor()),
                rootFilterPaths(root),
                rootSortPaths(root),
                rootContextFilters(root, context)
        );
    }

    private static List<ProjectionRetrieval.RetrievalArgument> rootArguments(TitanGraphqlRootDocument root) {
        List<ProjectionRetrieval.RetrievalArgument> arguments = new ArrayList<>();
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : root.arguments()) {
            requireRootArgumentKind(argument, TitanGraphqlRootDocument.RootDocumentArgumentKind.EQUALS, root.name());
            requireType(argument.type(), "Int", "root '" + root.name() + "' argument '" + argument.name() + "'");
            arguments.add(ProjectionRetrieval.RetrievalArgument.intEquals(argument.name(), argument.column()));
        }
        return arguments;
    }

    private static ProjectionRetrieval.RetrievalCursorOrdering cursorOrdering(
            TitanGraphqlRootDocument root,
            TitanGraphqlRootDocument.Cursor cursor
    ) {
        if (cursor == null) {
            throw unsupported("UNSUPPORTED_ROOT_CURSOR", "connection root '" + root.name() + "' requires a cursor");
        }
        return new ProjectionRetrieval.RetrievalCursorOrdering(
                cursor.path(),
                cursor.column(),
                cursor.path(),
                cursor.direction() == TitanGraphqlRootDocument.RootDocumentSortDirection.DESC
                        ? ProjectionRetrieval.RetrievalCursorDirection.DESC
                        : ProjectionRetrieval.RetrievalCursorDirection.ASC,
                defaultText(cursor.tieBreaker(), "id")
        );
    }

    private static List<ProjectionRetrieval.RetrievalFilterPath> rootFilterPaths(TitanGraphqlRootDocument root) {
        List<ProjectionRetrieval.RetrievalFilterPath> paths = new ArrayList<>();
        for (TitanGraphqlRootDocument.RootDocumentFilterPath path : root.filterPaths()) {
            paths.add(new ProjectionRetrieval.RetrievalFilterPath(
                    path.name(),
                    path.column(),
                    defaultText(path.path(), path.column()),
                    path.hops(),
                    path.type(),
                    filterCapabilities(path.type(), path.operators(), "root filterPath '" + path.name() + "'")
            ));
        }
        return paths;
    }

    private static List<ProjectionRetrieval.RetrievalSortPath> rootSortPaths(TitanGraphqlRootDocument root) {
        List<ProjectionRetrieval.RetrievalSortPath> paths = new ArrayList<>();
        for (TitanGraphqlRootDocument.RootDocumentSortPath path : root.sortPaths()) {
            requireRootNullOrdering(path);
            paths.add(new ProjectionRetrieval.RetrievalSortPath(
                    path.name(),
                    path.column(),
                    defaultText(path.path(), path.column()),
                    path.hops(),
                    path.direction() == TitanGraphqlRootDocument.RootDocumentSortDirection.DESC
                            ? ProjectionRetrieval.RetrievalCursorDirection.DESC
                            : ProjectionRetrieval.RetrievalCursorDirection.ASC,
                    ProjectionRetrieval.RetrievalNullOrdering.NULLS_LAST,
                    defaultText(path.tieBreaker(), "id")
            ));
        }
        return paths;
    }

    private static List<ProjectionRetrieval.RetrievalContextFilter> rootContextFilters(
            TitanGraphqlRootDocument root,
            AdapterContext context
    ) {
        List<ProjectionRetrieval.RetrievalContextFilter> filters = new ArrayList<>();
        for (String name : root.contextFilters()) {
            TitanGraphqlContextFilterDocument filter = context.contextFilter(name);
            if (filter.operator() != TitanGraphqlContextFilterDocument.Operator.BOOLEAN_EQUALS) {
                throw unsupported("UNSUPPORTED_CONTEXT_FILTER",
                        "context filter '" + name + "' must use booleanEquals");
            }
            if (!filter.applyBeforeClientFilters() || !filter.failClosed()) {
                throw unsupported("UNSUPPORTED_CONTEXT_FILTER",
                        "context filter '" + name + "' must be fail-closed before client filters");
            }
            filters.add(ProjectionRetrieval.RetrievalContextFilter.booleanEquals(
                    filter.name(),
                    filter.column(),
                    filter.contextKey()
            ));
        }
        return filters;
    }

    private static ProjectionType type(TitanGraphqlTypeDocument type, AdapterContext context) {
        return new ProjectionType(
                type.name(),
                type.table(),
                defaultText(type.schema(), context.defaultSchema()),
                defaultText(type.physicalTable(), type.table()),
                defaultText(type.primaryKey(), context.primaryKey(type.table())),
                fields(type, context),
                relations(type, context)
        );
    }

    private static List<ProjectionField> fields(TitanGraphqlTypeDocument type, AdapterContext context) {
        List<ProjectionField> fields = new ArrayList<>();
        for (TitanGraphqlFieldDocument field : type.fields()) {
            fields.add(field(field, context));
        }
        return fields;
    }

    private static ProjectionField field(TitanGraphqlFieldDocument field, AdapterContext context) {
        GraphqlFieldPolicy policy = fieldPolicy(field, context);
        if (field.computed() != null) {
            return ProjectionField.computed(computed(field), policy);
        }
        ProjectionField projectionField = ProjectionField.column(
                field.name(), field.column(), field.type(), field.nullable(), policy);
        requireFieldType(field, projectionField);
        requireFilterOperators(
                projectionField.filterCapabilities(),
                field.filterOperators(),
                "field '" + field.name() + "'"
        );
        requireFieldSort(field, projectionField);
        return projectionField;
    }

    private static ProjectionField.ProjectionComputedExpression computed(TitanGraphqlFieldDocument field) {
        TitanGraphqlFieldDocument.Computed computed = field.computed();
        if (computed.expressionKind() != TitanGraphqlFieldDocument.FieldDocumentExpressionKind.SQL_TEMPLATE) {
            throw unsupported("UNSUPPORTED_COMPUTED_EXPRESSION",
                    "computed field '" + field.name() + "' must use sqlTemplate");
        }
        ProjectionField.ProjectionComputedExpression expression = new ProjectionField.ProjectionComputedExpression(
                field.name(),
                field.type(),
                ProjectionField.ProjectionExpressionKind.SQL_TEMPLATE,
                computed.sqlTemplate(),
                computed.selectable(),
                computed.filterable(),
                computed.sortable(),
                field.nullable(),
                computed.deterministic(),
                computed.sensitive(),
                computed.requiredColumns(),
                computed.costClass() == TitanGraphqlFieldDocument.FieldDocumentCostClass.RELATION_DEPENDENT
                        ? ProjectionField.ProjectionCostClass.RELATION_DEPENDENT
                        : computed.costClass() == TitanGraphqlFieldDocument.FieldDocumentCostClass.CONSTANT
                                ? ProjectionField.ProjectionCostClass.CONSTANT
                                : ProjectionField.ProjectionCostClass.ROW_LOCAL
        );
        requireFilterOperators(
                expression.filterable()
                        ? computedFilterCapabilities(expression.graphqlType())
                        : ProjectionField.FilterCapabilities.none(),
                field.filterOperators(),
                "computed field '" + field.name() + "'"
        );
        requireFieldSort(field, ProjectionField.computed(expression, GraphqlFieldPolicy.ALLOW));
        return expression;
    }

    private static List<ProjectionRelation> relations(
            TitanGraphqlTypeDocument type,
            AdapterContext context
    ) {
        List<ProjectionRelation> relations = new ArrayList<>();
        for (TitanGraphqlRelationDocument relation : type.relations()) {
            relations.add(relation(relation, context));
        }
        return relations;
    }

    private static ProjectionRelation relation(
            TitanGraphqlRelationDocument relation,
            AdapterContext context
    ) {
        if (!relation.policies().isEmpty()) {
            for (String policyName : relation.policies()) {
                context.policy(policyName);
            }
            throw unsupported("UNSUPPORTED_RELATION_POLICY",
                    "relation '" + relation.name() + "' has policies that the projection adapter cannot "
                            + "enforce before reading; remove the relation from public exposure or implement "
                            + "an enforceable relation policy");
        }
        ProjectionRelation.ProjectionRelationCapabilities capabilities = relationCapabilities(relation);
        List<ProjectionRelation.ProjectionRelationArgument> arguments = relationArguments(relation);
        List<ProjectionRelation.ProjectionRelationSortPath> sortPaths = relationSortPaths(relation);
        if (relation.cardinality() == TitanGraphqlRelationDocument.RelationDocumentCardinality.MANY) {
            return ProjectionRelation.many(
                    relation.name(),
                    relation.targetType(),
                    relation.localColumn(),
                    relation.targetColumn(),
                    relation.nullable(),
                    capabilities,
                    arguments,
                    sortPaths
            );
        }
        return ProjectionRelation.one(
                relation.name(),
                relation.targetType(),
                relation.localColumn(),
                relation.targetColumn(),
                relation.nullable(),
                capabilities,
                arguments,
                sortPaths
        );
    }

    private static ProjectionRelation.ProjectionRelationCapabilities relationCapabilities(TitanGraphqlRelationDocument relation) {
        TitanGraphqlRelationDocument.RelationDocumentPagination pagination = relation.pagination();
        if (pagination == null || pagination.mode() == TitanGraphqlRelationDocument.RelationDocumentPaginationMode.NONE) {
            if (relation.sortPaths().isEmpty()) {
                return ProjectionRelation.ProjectionRelationCapabilities.currentDefault();
            }
            return new ProjectionRelation.ProjectionRelationCapabilities(
                    true,
                    true,
                    false,
                    true,
                    ProjectionRelation.ProjectionRelationPaginationMode.NONE,
                    2,
                    0,
                    1
            );
        }
        if (pagination.mode() != TitanGraphqlRelationDocument.RelationDocumentPaginationMode.RELAY || !pagination.totalCount()) {
            throw unsupported("UNSUPPORTED_RELATION_PAGINATION",
                    "relation '" + relation.name() + "' must use relay pagination with exact totalCount");
        }
        return ProjectionRelation.ProjectionRelationCapabilities.relayConnectionWithTotalCount(
                false,
                !relation.sortPaths().isEmpty(),
                2,
                0,
                0,
                pagination.defaultPageSize(),
                pagination.maxPageSize()
        );
    }

    private static List<ProjectionRelation.ProjectionRelationArgument> relationArguments(TitanGraphqlRelationDocument relation) {
        List<ProjectionRelation.ProjectionRelationArgument> arguments = new ArrayList<>();
        for (TitanGraphqlRelationDocument.RelationDocumentArgument argument : relation.arguments()) {
            arguments.add(switch (argument.kind()) {
                case EQUALS -> {
                    requireType(argument.type(), "Int", "relation '" + relation.name() + "' argument '" + argument.name() + "'");
                    yield ProjectionRelation.ProjectionRelationArgument.intEquals(
                            argument.name(),
                            argument.column(),
                            defaultText(argument.path(), argument.column()),
                            argument.hops()
                    );
                }
                case RELAY_FIRST -> ProjectionRelation.ProjectionRelationArgument.relayFirst();
                case RELAY_AFTER -> ProjectionRelation.ProjectionRelationArgument.relayAfter();
                case RELAY_LAST -> ProjectionRelation.ProjectionRelationArgument.relayLast();
                case RELAY_BEFORE -> ProjectionRelation.ProjectionRelationArgument.relayBefore();
            });
        }
        return arguments;
    }

    private static List<ProjectionRelation.ProjectionRelationSortPath> relationSortPaths(TitanGraphqlRelationDocument relation) {
        List<ProjectionRelation.ProjectionRelationSortPath> paths = new ArrayList<>();
        for (TitanGraphqlRelationDocument.RelationDocumentSortPath path : relation.sortPaths()) {
            paths.add(new ProjectionRelation.ProjectionRelationSortPath(
                    path.name(),
                    path.column(),
                    defaultText(path.path(), path.column()),
                    path.hops(),
                    path.direction() == TitanGraphqlRelationDocument.RelationDocumentSortDirection.DESC
                            ? ProjectionRelation.ProjectionRelationSortDirection.DESC
                            : ProjectionRelation.ProjectionRelationSortDirection.ASC,
                    defaultText(path.tieBreaker(), "id")
            ));
        }
        return paths;
    }

    private static GraphqlFieldPolicy fieldPolicy(TitanGraphqlFieldDocument field, AdapterContext context) {
        if (field.policies().isEmpty()) {
            return GraphqlFieldPolicy.ALLOW;
        }
        if (field.policies().size() > 1) {
            throw unsupported("UNSUPPORTED_POLICY", "field '" + field.name() + "' has multiple policies");
        }
        TitanGraphqlPolicyDocument policy = context.policy(field.policies().getFirst());
        if (policy.effect() != TitanGraphqlPolicyDocument.Effect.DENY
                || !"adminOnly".equals(policy.expression())) {
            throw unsupported("UNSUPPORTED_POLICY",
                    "policy '" + policy.name() + "' is not supported by the projection adapter");
        }
        return context.policy()::canReadUserEmail;
    }

    private static ProjectionField.FilterCapabilities filterCapabilities(
            String type,
            List<String> operators,
            String context
    ) {
        ProjectionField.FilterCapabilities capabilities = "String".equals(type)
                ? ProjectionField.FilterCapabilities.defaultString()
                : ProjectionField.FilterCapabilities.defaultScalar();
        requireFilterOperators(capabilities, operators, context);
        return capabilities;
    }

    private static ProjectionField.FilterCapabilities computedFilterCapabilities(String graphqlType) {
        if ("String".equals(graphqlType)) {
            return ProjectionField.FilterCapabilities.defaultString();
        }
        return ProjectionField.FilterCapabilities.defaultScalar();
    }

    private static void requireFilterOperators(
            ProjectionField.FilterCapabilities capabilities,
            List<String> operators,
            String context
    ) {
        if (operators.isEmpty()) {
            return;
        }
        List<ProjectionField.FilterOperator> expected = new ArrayList<>();
        for (String operator : operators) {
            expected.add(filterOperator(operator, context));
        }
        if (!expected.equals(capabilities.operators())) {
            throw unsupported("UNSUPPORTED_FILTER_OPERATORS",
                    context + " declares unsupported filter operators " + operators);
        }
    }

    private static ProjectionField.FilterOperator filterOperator(String operator, String context) {
        String normalized = operator.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        for (ProjectionField.FilterOperator candidate : ProjectionField.FilterOperator.values()) {
            if (candidate.name().replace("_", "").toLowerCase(Locale.ROOT).equals(normalized)) {
                return candidate;
            }
        }
        throw unsupported("UNSUPPORTED_FILTER_OPERATORS", context + " has unknown operator '" + operator + "'");
    }

    private static void requireFieldSort(TitanGraphqlFieldDocument field, ProjectionField projectionField) {
        if (field.sort() == null) {
            return;
        }
        if (!projectionField.sortCapabilities().sortable()
                || !projectionField.sortCapabilities().sortPath().equals(defaultText(field.sort().path(), field.name()))
                || !defaultText(field.sort().tieBreaker(), "id").equals("id")
                || field.sort().direction() != TitanGraphqlFieldDocument.FieldDocumentSortDirection.ASC
                || field.sort().nulls() != TitanGraphqlFieldDocument.FieldDocumentNullOrdering.LAST) {
            throw unsupported("UNSUPPORTED_FIELD_SORT",
                    "field '" + field.name() + "' declares unsupported sort capabilities");
        }
    }

    private static void requireFieldType(TitanGraphqlFieldDocument field, ProjectionField projectionField) {
        if (!field.type().equals(projectionField.graphqlType())) {
            throw unsupported("UNSUPPORTED_FIELD_TYPE",
                    "field '" + field.name() + "' declares unsupported GraphQL type '" + field.type() + "'");
        }
    }

    private static void requireRootNullOrdering(TitanGraphqlRootDocument.RootDocumentSortPath path) {
        if (path.nulls() != TitanGraphqlRootDocument.RootDocumentNullOrdering.LAST) {
            throw unsupported("UNSUPPORTED_SORT_NULLS",
                    "root sortPath '" + path.name() + "' declares unsupported null ordering");
        }
    }

    private static void requireRootArgumentKind(
            TitanGraphqlRootDocument.RootDocumentArgument argument,
            TitanGraphqlRootDocument.RootDocumentArgumentKind expected,
            String rootName
    ) {
        if (argument.kind() != expected) {
            throw unsupported("UNSUPPORTED_ROOT_ARGUMENT",
                    "root '" + rootName + "' argument '" + argument.name() + "' has unsupported kind");
        }
    }

    private static void requireType(String actual, String expected, String context) {
        if (!expected.equals(actual)) {
            throw unsupported("UNSUPPORTED_ARGUMENT_TYPE", context + " must be " + expected);
        }
    }

    private static String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static TitanGraphqlProjectionModelAdapterException unsupported(String code, String message) {
        return new TitanGraphqlProjectionModelAdapterException(code, message);
    }

    private static final class AdapterContext {

        private final GraphqlPolicy policy;
        private final String defaultSchema;
        private final Map<String, String> primaryKeys;
        private final Map<String, String> typePrimaryKeys;
        private final Map<String, TitanGraphqlPolicyDocument> policies;
        private final Map<String, TitanGraphqlContextFilterDocument> contextFilters;
        private final Map<String, TitanGraphqlTypeDocument> types;

        AdapterContext(TitanGraphqlModelDocument document, GraphqlPolicy policy) {
            this.policy = policy;
            this.defaultSchema = defaultText(document.database().defaultSchema(), "public");
            this.primaryKeys = new LinkedHashMap<>();
            document.database().tables().forEach(table -> primaryKeys.put(table.name(), table.primaryKey()));
            this.typePrimaryKeys = new LinkedHashMap<>();
            document.types().forEach(type -> typePrimaryKeys.put(
                    type.name(), defaultText(type.primaryKey(), primaryKey(type.table()))));
            this.policies = new LinkedHashMap<>();
            document.policies().forEach(policyDocument -> policies.put(policyDocument.name(), policyDocument));
            this.contextFilters = new LinkedHashMap<>();
            document.contextFilters().forEach(filter -> contextFilters.put(filter.name(), filter));
            this.types = new LinkedHashMap<>();
            document.types().forEach(type -> types.put(type.name(), type));
        }

        GraphqlPolicy policy() {
            return policy;
        }

        String defaultSchema() {
            return defaultSchema;
        }

        String primaryKey(String tableName) {
            return primaryKeys.getOrDefault(tableName, "");
        }

        String primaryKeyForType(String typeName) {
            String primaryKey = typePrimaryKeys.get(typeName);
            if (primaryKey == null) {
                throw unsupported("UNKNOWN_ROOT_TYPE", "unknown root type '" + typeName + "'");
            }
            return primaryKey;
        }

        TitanGraphqlTypeDocument type(String typeName) {
            TitanGraphqlTypeDocument type = types.get(typeName);
            if (type == null) {
                throw unsupported("UNKNOWN_ROOT_TYPE", "unknown root type '" + typeName + "'");
            }
            return type;
        }

        TitanGraphqlPolicyDocument policy(String name) {
            TitanGraphqlPolicyDocument policyDocument = policies.get(name);
            if (policyDocument == null) {
                throw unsupported("UNKNOWN_POLICY", "unknown policy '" + name + "'");
            }
            return policyDocument;
        }

        TitanGraphqlContextFilterDocument contextFilter(String name) {
            TitanGraphqlContextFilterDocument filter = contextFilters.get(name);
            if (filter == null) {
                throw unsupported("UNKNOWN_CONTEXT_FILTER", "unknown context filter '" + name + "'");
            }
            return filter;
        }
    }
}
