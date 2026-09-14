package io.titan.graphql.model;

import java.util.List;

public record TitanGraphqlFieldDocument(
        String name,
        String type,
        String column,
        boolean nullable,
        List<String> policies,
        List<String> filterOperators,
        Sort sort,
        Computed computed
) {
    public TitanGraphqlFieldDocument {
        name = ModelDocumentSupport.requireText(name, "field.name");
        type = ModelDocumentSupport.requireText(type, "field.type");
        column = ModelDocumentSupport.textOrEmpty(column);
        policies = ModelDocumentSupport.listOrEmpty(policies);
        filterOperators = ModelDocumentSupport.listOrEmpty(filterOperators);
    }

    public static TitanGraphqlFieldDocument column(
            String name,
            String type,
            String column,
            List<String> filterOperators,
            Sort sort
    ) {
        return new TitanGraphqlFieldDocument(name, type, column, false, List.of(), filterOperators, sort, null);
    }

    // Renamed from ExpressionKind under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum FieldDocumentExpressionKind {
        SQL_TEMPLATE,
        GENERATED_SQL_HELPER,
        MATERIALIZED_COLUMN,
        JAVA_ONLY_EXPERIMENTAL
    }

    // Renamed from CostClass under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum FieldDocumentCostClass {
        CONSTANT,
        ROW_LOCAL,
        RELATION_DEPENDENT
    }

    // Renamed from SortDirection under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum FieldDocumentSortDirection {
        ASC,
        DESC
    }

    // Renamed from NullOrdering under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum FieldDocumentNullOrdering {
        FIRST,
        LAST
    }

    public record Sort(
            String path,
            FieldDocumentSortDirection direction,
            FieldDocumentNullOrdering nulls,
            String tieBreaker
    ) {
        public Sort {
            path = ModelDocumentSupport.textOrEmpty(path);
            direction = direction == null ? FieldDocumentSortDirection.ASC : direction;
            nulls = nulls == null ? FieldDocumentNullOrdering.LAST : nulls;
            tieBreaker = ModelDocumentSupport.textOrEmpty(tieBreaker);
        }
    }

    public record Computed(
            FieldDocumentExpressionKind expressionKind,
            String sqlTemplate,
            boolean selectable,
            boolean filterable,
            boolean sortable,
            boolean deterministic,
            boolean sensitive,
            List<String> requiredColumns,
            FieldDocumentCostClass costClass
    ) {
        public Computed {
            expressionKind = expressionKind == null ? FieldDocumentExpressionKind.SQL_TEMPLATE : expressionKind;
            sqlTemplate = ModelDocumentSupport.textOrEmpty(sqlTemplate);
            requiredColumns = ModelDocumentSupport.listOrEmpty(requiredColumns);
            costClass = costClass == null ? FieldDocumentCostClass.ROW_LOCAL : costClass;
        }
    }
}
