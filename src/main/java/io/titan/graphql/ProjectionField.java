package io.titan.graphql;

import java.util.List;

public final class ProjectionField {

    public enum FilterOperator {
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

    // Renamed from SortDirection under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum ProjectionSortDirection {
        ASC,
        DESC
    }

    // Renamed from NullOrdering under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum ProjectionNullOrdering {
        NULLS_FIRST,
        NULLS_LAST
    }

    // Renamed from ExpressionKind under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum ProjectionExpressionKind {
        SQL_TEMPLATE,
        GENERATED_SQL_HELPER,
        MATERIALIZED_COLUMN,
        JAVA_ONLY_EXPERIMENTAL
    }

    // Renamed from CostClass under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum ProjectionCostClass {
        CONSTANT,
        ROW_LOCAL,
        RELATION_DEPENDENT
    }

    // Renamed from ComputedExpression under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record ProjectionComputedExpression(
            String name,
            String graphqlType,
            ProjectionExpressionKind expressionKind,
            String sqlTemplateOrFunction,
            boolean selectable,
            boolean filterable,
            boolean sortable,
            boolean nullable,
            boolean deterministic,
            boolean sensitive,
            List<String> requiredColumns,
            ProjectionCostClass costClass
    ) {
        public static ProjectionComputedExpression selectableSqlTemplate(
                String name,
                String graphqlType,
                String sqlTemplate,
                List<String> requiredColumns
        ) {
            return new ProjectionComputedExpression(
                    name,
                    graphqlType,
                    ProjectionExpressionKind.SQL_TEMPLATE,
                    sqlTemplate,
                    true,
                    false,
                    false,
                    false,
                    true,
                    false,
                    requiredColumns,
                    ProjectionCostClass.ROW_LOCAL
            );
        }

        public static ProjectionComputedExpression filterableSortableSqlTemplate(
                String name,
                String graphqlType,
                String sqlTemplate,
                List<String> requiredColumns
        ) {
            return new ProjectionComputedExpression(
                    name,
                    graphqlType,
                    ProjectionExpressionKind.SQL_TEMPLATE,
                    sqlTemplate,
                    true,
                    true,
                    true,
                    false,
                    true,
                    false,
                    requiredColumns,
                    ProjectionCostClass.ROW_LOCAL
            );
        }
    }

    public record FilterCapabilities(List<FilterOperator> operators) {
        public static FilterCapabilities none() {
            return new FilterCapabilities(List.of());
        }

        public static FilterCapabilities defaultScalar() {
            return new FilterCapabilities(List.of(
                    FilterOperator.EQ,
                    FilterOperator.NEQ,
                    FilterOperator.IN,
                    FilterOperator.IS_NULL,
                    FilterOperator.LT,
                    FilterOperator.LTE,
                    FilterOperator.GT,
                    FilterOperator.GTE
            ));
        }

        public static FilterCapabilities defaultString() {
            return new FilterCapabilities(List.of(
                    FilterOperator.EQ,
                    FilterOperator.NEQ,
                    FilterOperator.IN,
                    FilterOperator.IS_NULL,
                    FilterOperator.CONTAINS,
                    FilterOperator.STARTS_WITH,
                    FilterOperator.ENDS_WITH
            ));
        }

        public static FilterCapabilities inferred(String name, String columnName) {
            if (isStringField(name, columnName)) {
                return defaultString();
            }
            return defaultScalar();
        }
    }

    public record SortCapabilities(
            boolean sortable,
            String sortPath,
            int hopCount,
            ProjectionSortDirection direction,
            ProjectionNullOrdering nullOrdering,
            String tieBreakerColumnName
    ) {
        public static SortCapabilities scalar(String fieldName) {
            return new SortCapabilities(true, fieldName, 0, ProjectionSortDirection.ASC, ProjectionNullOrdering.NULLS_LAST, "id");
        }
    }

    private final String name;
    private final String columnName;
    private final String graphqlType;
    private final GraphqlFieldPolicy policy;
    private final FilterCapabilities filterCapabilities;
    private final SortCapabilities sortCapabilities;
    private final ProjectionComputedExpression computedExpression;

    private ProjectionField(
            String name,
            String columnName,
            String graphqlType,
            GraphqlFieldPolicy policy,
            FilterCapabilities filterCapabilities,
            SortCapabilities sortCapabilities,
            ProjectionComputedExpression computedExpression
    ) {
        this.name = name;
        this.columnName = columnName;
        this.graphqlType = graphqlType;
        this.policy = policy;
        this.filterCapabilities = filterCapabilities;
        this.sortCapabilities = sortCapabilities;
        this.computedExpression = computedExpression;
    }

    public static ProjectionField column(String name, String columnName) {
        return column(name, columnName, GraphqlFieldPolicy.ALLOW);
    }

    public static ProjectionField column(String name, String columnName, GraphqlFieldPolicy policy) {
        return new ProjectionField(
                name,
                columnName,
                inferredGraphqlType(name, columnName),
                policy,
                FilterCapabilities.inferred(name, columnName),
                SortCapabilities.scalar(name),
                null
        );
    }

    public static ProjectionField computed(ProjectionComputedExpression computedExpression, GraphqlFieldPolicy policy) {
        return new ProjectionField(
                computedExpression.name(),
                computedExpression.name(),
                computedExpression.graphqlType(),
                policy,
                computedExpression.filterable()
                        ? computedFilterCapabilities(computedExpression.graphqlType())
                        : FilterCapabilities.none(),
                computedExpression.sortable()
                        ? SortCapabilities.scalar(computedExpression.name())
                        : new SortCapabilities(
                                false,
                                "",
                                0,
                                ProjectionSortDirection.ASC,
                                ProjectionNullOrdering.NULLS_LAST,
                                ""
                        ),
                computedExpression
        );
    }

    private static FilterCapabilities computedFilterCapabilities(String graphqlType) {
        if (graphqlType.equals("String")) {
            return FilterCapabilities.defaultString();
        }
        return FilterCapabilities.defaultScalar();
    }

    private static boolean isStringField(String name, String columnName) {
        String normalizedName = name.toLowerCase();
        String normalizedColumn = columnName.toLowerCase();
        return normalizedName.equals("id") == false
                && normalizedName.endsWith("id") == false
                && normalizedColumn.equals("id") == false
                && normalizedColumn.endsWith("_id") == false;
    }

    private static String inferredGraphqlType(String name, String columnName) {
        return isStringField(name, columnName) ? "String" : "Int";
    }

    public String name() {
        return name;
    }

    public String columnName() {
        return columnName;
    }

    public String graphqlType() {
        return graphqlType;
    }

    public GraphqlFieldPolicy policy() {
        return policy;
    }

    public FilterCapabilities filterCapabilities() {
        return filterCapabilities;
    }

    public SortCapabilities sortCapabilities() {
        return sortCapabilities;
    }

    public ProjectionComputedExpression computedExpression() {
        return computedExpression;
    }
}
