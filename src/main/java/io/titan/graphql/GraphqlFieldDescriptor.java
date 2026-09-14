package io.titan.graphql;

import java.util.List;

public final class GraphqlFieldDescriptor {

    // Renamed from Kind under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum FieldKind {
        SCALAR,
        RELATION
    }

    public enum RelationCardinality {
        ONE,
        MANY
    }

    public enum RelationPaginationMode {
        NONE,
        RELAY_CONNECTION
    }

    public enum RelationRetrievalShape {
        BATCH_LOOKUP,
        FILTERED_BATCH,
        RELAY_CONNECTION_PAGE
    }

    public enum RelationSortDirection {
        ASC,
        DESC
    }

    // Renamed from NullOrdering under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum FieldNullOrdering {
        NULLS_FIRST,
        NULLS_LAST
    }

    public enum ScalarFilterOperator {
        EQ,
        NEQ,
        IN,
        IS_NULL,
        LT,
        LTE,
        GT,
        GTE,
        CONTAINS,
        STARTS_WITH,
        ENDS_WITH
    }

    public enum ComputedExpressionKind {
        SQL_TEMPLATE,
        GENERATED_SQL_HELPER,
        MATERIALIZED_COLUMN,
        JAVA_ONLY_EXPERIMENTAL
    }

    public enum ComputedCostClass {
        CONSTANT,
        ROW_LOCAL,
        RELATION_DEPENDENT
    }

    // Renamed from ComputedExpression under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record FieldComputedExpression(
            String name,
            String graphqlType,
            ComputedExpressionKind expressionKind,
            String sqlTemplateOrFunction,
            boolean selectable,
            boolean filterable,
            boolean sortable,
            boolean nullable,
            boolean deterministic,
            boolean sensitive,
            List<String> requiredColumns,
            ComputedCostClass costClass
    ) {
        public static FieldComputedExpression none() {
            return new FieldComputedExpression(
                    "",
                    "",
                    ComputedExpressionKind.SQL_TEMPLATE,
                    "",
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    List.of(),
                    ComputedCostClass.CONSTANT
            );
        }

        boolean present() {
            return name.isEmpty() == false;
        }
    }

    public record ScalarFilterCapabilities(List<ScalarFilterOperator> operators) {
        public static ScalarFilterCapabilities none() {
            return new ScalarFilterCapabilities(List.of());
        }

        public static ScalarFilterCapabilities defaultScalar() {
            return new ScalarFilterCapabilities(List.of(
                    ScalarFilterOperator.EQ,
                    ScalarFilterOperator.NEQ,
                    ScalarFilterOperator.IN,
                    ScalarFilterOperator.IS_NULL,
                    ScalarFilterOperator.CONTAINS,
                    ScalarFilterOperator.STARTS_WITH,
                    ScalarFilterOperator.ENDS_WITH
            ));
        }
    }

    public record ScalarSortCapabilities(
            boolean sortable,
            String sortPath,
            int hopCount,
            RelationSortDirection direction,
            FieldNullOrdering nullOrdering,
            String tieBreakerColumnName
    ) {
        public static ScalarSortCapabilities none() {
            return new ScalarSortCapabilities(false, "", 0, RelationSortDirection.ASC, FieldNullOrdering.NULLS_LAST, "");
        }

        public static ScalarSortCapabilities scalar(String fieldName) {
            return new ScalarSortCapabilities(true, fieldName, 0, RelationSortDirection.ASC, FieldNullOrdering.NULLS_LAST, "id");
        }
    }

    public record RelationRetrievals(
            String batchName,
            String filteredBatchName,
            String connectionPageName
    ) {
        public static RelationRetrievals currentDefault(String relationName) {
            return new RelationRetrievals(
                    relationName + "ByParent",
                    relationName + "ByParentFilter",
                    relationName + "ConnectionPage"
            );
        }

        String nameFor(RelationRetrievalShape shape) {
            return switch (shape) {
                case BATCH_LOOKUP -> batchName;
                case FILTERED_BATCH -> filteredBatchName;
                case RELAY_CONNECTION_PAGE -> connectionPageName;
            };
        }
    }

    public record RelationCapabilities(
            boolean selectable,
            boolean batchable,
            boolean supportsFiltering,
            boolean supportsSorting,
            RelationPaginationMode paginationMode,
            int selectionHopBudget,
            int filterHopBudget,
            int sortHopBudget,
            int defaultPageSize,
            int maxPageSize,
            boolean supportsTotalCount
    ) {
        RelationCapabilities(
                boolean selectable,
                boolean batchable,
                boolean supportsFiltering,
                boolean supportsSorting,
                RelationPaginationMode paginationMode,
                int selectionHopBudget,
                int filterHopBudget,
                int sortHopBudget,
                int defaultPageSize,
                int maxPageSize
        ) {
            this(
                    selectable,
                    batchable,
                    supportsFiltering,
                    supportsSorting,
                    paginationMode,
                    selectionHopBudget,
                    filterHopBudget,
                    sortHopBudget,
                    defaultPageSize,
                    maxPageSize,
                    false
            );
        }

        RelationCapabilities(
                boolean selectable,
                boolean batchable,
                boolean supportsFiltering,
                boolean supportsSorting,
                RelationPaginationMode paginationMode,
                int selectionHopBudget,
                int filterHopBudget,
                int sortHopBudget,
                int maxPageSize
        ) {
            this(
                    selectable,
                    batchable,
                    supportsFiltering,
                    supportsSorting,
                    paginationMode,
                    selectionHopBudget,
                    filterHopBudget,
                    sortHopBudget,
                    10,
                    maxPageSize
            );
        }

        RelationCapabilities(
                boolean selectable,
                boolean batchable,
                boolean supportsFiltering,
                boolean supportsSorting,
                RelationPaginationMode paginationMode,
                int selectionHopBudget,
                int filterHopBudget,
                int sortHopBudget
        ) {
            this(
                    selectable,
                    batchable,
                    supportsFiltering,
                    supportsSorting,
                    paginationMode,
                    selectionHopBudget,
                    filterHopBudget,
                    sortHopBudget,
                    10,
                    100
            );
        }

        public static RelationCapabilities none() {
            return new RelationCapabilities(false, false, false, false, RelationPaginationMode.NONE, 0, 0, 0, 0, 100, false);
        }

        public static RelationCapabilities currentDefault() {
            return new RelationCapabilities(true, true, false, false, RelationPaginationMode.NONE, 2, 0, 0, 10, 100, false);
        }
    }

    public record RelationSortPath(
            String name,
            String columnName,
            String sortPath,
            int sortHopCount,
            RelationSortDirection direction,
            String tieBreakerColumnName
    ) {
        public static RelationSortPath ascending(String name, String columnName, String sortPath, int sortHopCount) {
            return new RelationSortPath(name, columnName, sortPath, sortHopCount, RelationSortDirection.ASC, "id");
        }

        public static RelationSortPath descending(String name, String columnName, String sortPath, int sortHopCount) {
            return new RelationSortPath(name, columnName, sortPath, sortHopCount, RelationSortDirection.DESC, "id");
        }
    }

    private final String name;
    private final FieldKind kind;
    private final String columnName;
    private final String graphqlType;
    private final String targetTypeName;
    private final String localColumnName;
    private final String targetColumnName;
    private final RelationCardinality relationCardinality;
    private final boolean nullable;
    private final GraphqlFieldPolicy policy;
    private final RelationCapabilities relationCapabilities;
    private final RelationRetrievals relationRetrievals;
    private final List<GraphqlRelationArgumentDescriptor> relationArguments;
    private final List<RelationSortPath> relationSortPaths;
    private final ScalarFilterCapabilities scalarFilterCapabilities;
    private final ScalarSortCapabilities scalarSortCapabilities;
    private final FieldComputedExpression computedExpression;

    private GraphqlFieldDescriptor(
            String name,
            FieldKind kind,
            String columnName,
            String graphqlType,
            String targetTypeName,
            String localColumnName,
            String targetColumnName,
            RelationCardinality relationCardinality,
            boolean nullable,
            GraphqlFieldPolicy policy,
            RelationCapabilities relationCapabilities,
            RelationRetrievals relationRetrievals,
            List<GraphqlRelationArgumentDescriptor> relationArguments,
            List<RelationSortPath> relationSortPaths,
            ScalarFilterCapabilities scalarFilterCapabilities,
            ScalarSortCapabilities scalarSortCapabilities,
            FieldComputedExpression computedExpression
    ) {
        this.name = name;
        this.kind = kind;
        this.columnName = columnName;
        this.graphqlType = graphqlType;
        this.targetTypeName = targetTypeName;
        this.localColumnName = localColumnName;
        this.targetColumnName = targetColumnName;
        this.relationCardinality = relationCardinality;
        this.nullable = nullable;
        this.policy = policy;
        this.relationCapabilities = relationCapabilities;
        this.relationRetrievals = relationRetrievals;
        this.relationArguments = List.copyOf(relationArguments);
        this.relationSortPaths = List.copyOf(relationSortPaths);
        this.scalarFilterCapabilities = scalarFilterCapabilities;
        this.scalarSortCapabilities = scalarSortCapabilities;
        this.computedExpression = computedExpression;
    }

    public static GraphqlFieldDescriptor scalar(String name) {
        return scalar(name, GraphqlFieldPolicy.ALLOW);
    }

    public static GraphqlFieldDescriptor scalar(String name, GraphqlFieldPolicy policy) {
        return scalarColumn(name, name, policy);
    }

    public static GraphqlFieldDescriptor scalarColumn(String name, String columnName) {
        return scalarColumn(name, columnName, GraphqlFieldPolicy.ALLOW);
    }

    public static GraphqlFieldDescriptor scalarColumn(String name, String columnName, GraphqlFieldPolicy policy) {
        return scalarColumn(
                name,
                columnName,
                policy,
                ScalarFilterCapabilities.defaultScalar(),
                ScalarSortCapabilities.scalar(name)
        );
    }

    static GraphqlFieldDescriptor scalarColumn(
            String name,
            String columnName,
            GraphqlFieldPolicy policy,
            ScalarFilterCapabilities scalarFilterCapabilities,
            ScalarSortCapabilities scalarSortCapabilities
    ) {
        return scalarColumn(name, columnName, inferredGraphqlType(name, columnName), policy,
                scalarFilterCapabilities, scalarSortCapabilities);
    }

    static GraphqlFieldDescriptor scalarColumn(
            String name,
            String columnName,
            String graphqlType,
            GraphqlFieldPolicy policy,
            ScalarFilterCapabilities scalarFilterCapabilities,
            ScalarSortCapabilities scalarSortCapabilities
    ) {
        return scalarColumn(name, columnName, graphqlType, false, policy,
                scalarFilterCapabilities, scalarSortCapabilities);
    }

    static GraphqlFieldDescriptor scalarColumn(
            String name,
            String columnName,
            String graphqlType,
            boolean nullable,
            GraphqlFieldPolicy policy,
            ScalarFilterCapabilities scalarFilterCapabilities,
            ScalarSortCapabilities scalarSortCapabilities
    ) {
        return new GraphqlFieldDescriptor(
                name,
                FieldKind.SCALAR,
                columnName,
                graphqlType,
                "",
                "",
                "",
                RelationCardinality.ONE,
                nullable,
                policy,
                RelationCapabilities.none(),
                RelationRetrievals.currentDefault(name),
                List.of(),
                List.of(),
                scalarFilterCapabilities,
                scalarSortCapabilities,
                FieldComputedExpression.none()
        );
    }

    static GraphqlFieldDescriptor computedScalar(
            String name,
            String graphqlType,
            GraphqlFieldPolicy policy,
            FieldComputedExpression computedExpression
    ) {
        return new GraphqlFieldDescriptor(
                name,
                FieldKind.SCALAR,
                computedExpression.name(),
                graphqlType,
                "",
                "",
                "",
                RelationCardinality.ONE,
                computedExpression.nullable(),
                policy,
                RelationCapabilities.none(),
                RelationRetrievals.currentDefault(name),
                List.of(),
                List.of(),
                computedExpression.filterable()
                        ? computedFilterCapabilities(computedExpression.graphqlType())
                        : ScalarFilterCapabilities.none(),
                computedExpression.sortable()
                        ? ScalarSortCapabilities.scalar(computedExpression.name())
                        : ScalarSortCapabilities.none(),
                computedExpression
        );
    }

    private static ScalarFilterCapabilities computedFilterCapabilities(String graphqlType) {
        if (graphqlType.equals("String")) {
            return ScalarFilterCapabilities.defaultScalar();
        }
        return new ScalarFilterCapabilities(List.of(
                ScalarFilterOperator.EQ,
                ScalarFilterOperator.NEQ,
                ScalarFilterOperator.IN,
                ScalarFilterOperator.IS_NULL,
                ScalarFilterOperator.LT,
                ScalarFilterOperator.LTE,
                ScalarFilterOperator.GT,
                ScalarFilterOperator.GTE
        ));
    }

    public static GraphqlFieldDescriptor relation(String name, String targetTypeName) {
        return relation(name, targetTypeName, "", "", RelationCardinality.ONE, false);
    }

    static GraphqlFieldDescriptor relation(
            String name,
            String targetTypeName,
            String localColumnName,
            String targetColumnName,
            RelationCardinality relationCardinality,
            boolean nullable
    ) {
        return relation(
                name,
                targetTypeName,
                localColumnName,
                targetColumnName,
                relationCardinality,
                nullable,
                RelationCapabilities.currentDefault(),
                List.of()
        );
    }

    static GraphqlFieldDescriptor relation(
            String name,
            String targetTypeName,
            String localColumnName,
            String targetColumnName,
            RelationCardinality relationCardinality,
            boolean nullable,
            RelationCapabilities relationCapabilities
    ) {
        return relation(
                name,
                targetTypeName,
                localColumnName,
                targetColumnName,
                relationCardinality,
                nullable,
                relationCapabilities,
                List.of()
        );
    }

    static GraphqlFieldDescriptor relation(
            String name,
            String targetTypeName,
            String localColumnName,
            String targetColumnName,
            RelationCardinality relationCardinality,
            boolean nullable,
            RelationCapabilities relationCapabilities,
            List<GraphqlRelationArgumentDescriptor> relationArguments
    ) {
        return new GraphqlFieldDescriptor(
                name,
                FieldKind.RELATION,
                "",
                "",
                targetTypeName,
                localColumnName,
                targetColumnName,
                relationCardinality,
                nullable,
                GraphqlFieldPolicy.ALLOW,
                relationCapabilities,
                RelationRetrievals.currentDefault(name),
                relationArguments,
                List.of(),
                ScalarFilterCapabilities.none(),
                ScalarSortCapabilities.none(),
                FieldComputedExpression.none()
        );
    }

    static GraphqlFieldDescriptor relation(
            String name,
            String targetTypeName,
            String localColumnName,
            String targetColumnName,
            RelationCardinality relationCardinality,
            boolean nullable,
            GraphqlFieldPolicy policy,
            RelationCapabilities relationCapabilities,
            RelationRetrievals relationRetrievals,
            List<GraphqlRelationArgumentDescriptor> relationArguments
    ) {
        return new GraphqlFieldDescriptor(
                name,
                FieldKind.RELATION,
                "",
                "",
                targetTypeName,
                localColumnName,
                targetColumnName,
                relationCardinality,
                nullable,
                policy,
                relationCapabilities,
                relationRetrievals,
                relationArguments,
                List.of(),
                ScalarFilterCapabilities.none(),
                ScalarSortCapabilities.none(),
                FieldComputedExpression.none()
        );
    }

    static GraphqlFieldDescriptor relation(
            String name,
            String targetTypeName,
            String localColumnName,
            String targetColumnName,
            RelationCardinality relationCardinality,
            boolean nullable,
            GraphqlFieldPolicy policy,
            RelationCapabilities relationCapabilities,
            RelationRetrievals relationRetrievals,
            List<GraphqlRelationArgumentDescriptor> relationArguments,
            List<RelationSortPath> relationSortPaths
    ) {
        return new GraphqlFieldDescriptor(
                name, FieldKind.RELATION, "", "", targetTypeName, localColumnName,
                targetColumnName, relationCardinality, nullable, policy, relationCapabilities,
                relationRetrievals, relationArguments, relationSortPaths,
                ScalarFilterCapabilities.none(), ScalarSortCapabilities.none(),
                FieldComputedExpression.none());
    }

    static GraphqlFieldDescriptor relation(
            String name,
            String targetTypeName,
            String localColumnName,
            String targetColumnName,
            RelationCardinality relationCardinality,
            boolean nullable,
            RelationCapabilities relationCapabilities,
            RelationRetrievals relationRetrievals,
            List<GraphqlRelationArgumentDescriptor> relationArguments,
            List<RelationSortPath> relationSortPaths
    ) {
        return new GraphqlFieldDescriptor(
                name,
                FieldKind.RELATION,
                "",
                "",
                targetTypeName,
                localColumnName,
                targetColumnName,
                relationCardinality,
                nullable,
                GraphqlFieldPolicy.ALLOW,
                relationCapabilities,
                relationRetrievals,
                relationArguments,
                relationSortPaths,
                ScalarFilterCapabilities.none(),
                ScalarSortCapabilities.none(),
                FieldComputedExpression.none()
        );
    }

    public String name() {
        return name;
    }

    public FieldKind kind() {
        return kind;
    }

    public String columnName() {
        return columnName;
    }

    public String graphqlType() {
        return graphqlType;
    }

    public String targetTypeName() {
        return targetTypeName;
    }

    public String localColumnName() {
        return localColumnName;
    }

    public String targetColumnName() {
        return targetColumnName;
    }

    public RelationCardinality relationCardinality() {
        return relationCardinality;
    }

    public boolean nullable() {
        return nullable;
    }

    public boolean canRead(String actorRole) {
        return policy.canRead(actorRole);
    }

    public RelationCapabilities relationCapabilities() {
        return relationCapabilities;
    }

    public RelationRetrievals relationRetrievals() {
        return relationRetrievals;
    }

    public String relationRetrievalName(RelationRetrievalShape shape) {
        return relationRetrievals.nameFor(shape);
    }

    public List<GraphqlRelationArgumentDescriptor> relationArguments() {
        return relationArguments;
    }

    public List<RelationSortPath> relationSortPaths() {
        return relationSortPaths;
    }

    public ScalarFilterCapabilities scalarFilterCapabilities() {
        return scalarFilterCapabilities;
    }

    public ScalarSortCapabilities scalarSortCapabilities() {
        return scalarSortCapabilities;
    }

    public FieldComputedExpression computedExpression() {
        return computedExpression;
    }

    public GraphqlRelationArgumentDescriptor relationArgument(String name) {
        for (GraphqlRelationArgumentDescriptor argument : relationArguments) {
            if (argument.name().equals(name)) {
                return argument;
            }
        }
        return null;
    }

    private static String inferredGraphqlType(String name, String columnName) {
        String normalizedName = name.toLowerCase();
        String normalizedColumn = columnName.toLowerCase();
        if (normalizedName.equals("id")
                || normalizedName.endsWith("id")
                || normalizedColumn.equals("id")
                || normalizedColumn.endsWith("_id")) {
            return "Int";
        }
        return "String";
    }
}
