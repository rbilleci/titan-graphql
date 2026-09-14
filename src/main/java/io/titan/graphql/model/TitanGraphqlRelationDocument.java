package io.titan.graphql.model;

import java.util.List;

public record TitanGraphqlRelationDocument(
        String name,
        String targetType,
        String localColumn,
        String targetColumn,
        RelationDocumentCardinality cardinality,
        boolean nullable,
        RelationDocumentPagination pagination,
        List<RelationDocumentArgument> arguments,
        List<RelationDocumentSortPath> sortPaths,
        List<String> policies
) {
    public TitanGraphqlRelationDocument {
        name = ModelDocumentSupport.requireText(name, "relation.name");
        targetType = ModelDocumentSupport.requireText(targetType, "relation.targetType");
        localColumn = ModelDocumentSupport.requireText(localColumn, "relation.localColumn");
        targetColumn = ModelDocumentSupport.requireText(targetColumn, "relation.targetColumn");
        cardinality = cardinality == null ? RelationDocumentCardinality.ONE : cardinality;
        arguments = ModelDocumentSupport.listOrEmpty(arguments);
        sortPaths = ModelDocumentSupport.listOrEmpty(sortPaths);
        policies = ModelDocumentSupport.listOrEmpty(policies);
    }

    // Renamed from Cardinality under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RelationDocumentCardinality {
        ONE,
        MANY
    }

    // Renamed from PaginationMode under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RelationDocumentPaginationMode {
        NONE,
        RELAY
    }

    // Renamed from ArgumentKind under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RelationDocumentArgumentKind {
        EQUALS,
        RELAY_FIRST,
        RELAY_AFTER,
        RELAY_LAST,
        RELAY_BEFORE
    }

    // Renamed from SortDirection under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RelationDocumentSortDirection {
        ASC,
        DESC
    }

    // Renamed from Pagination under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record RelationDocumentPagination(
            RelationDocumentPaginationMode mode,
            int defaultPageSize,
            int maxPageSize,
            boolean totalCount
    ) {
        public RelationDocumentPagination {
            mode = mode == null ? RelationDocumentPaginationMode.NONE : mode;
        }
    }

    // Renamed from Argument under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record RelationDocumentArgument(
            String name,
            String type,
            RelationDocumentArgumentKind kind,
            String column,
            String path,
            int hops
    ) {
        public RelationDocumentArgument {
            name = ModelDocumentSupport.requireText(name, "relation.argument.name");
            type = ModelDocumentSupport.textOrEmpty(type);
            kind = kind == null ? RelationDocumentArgumentKind.EQUALS : kind;
            column = ModelDocumentSupport.textOrEmpty(column);
            path = ModelDocumentSupport.textOrEmpty(path);
        }
    }

    // Renamed from SortPath under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record RelationDocumentSortPath(
            String name,
            String column,
            String path,
            RelationDocumentSortDirection direction,
            String tieBreaker,
            int hops
    ) {
        public RelationDocumentSortPath {
            name = ModelDocumentSupport.requireText(name, "relation.sortPath.name");
            column = ModelDocumentSupport.requireText(column, "relation.sortPath.column");
            path = ModelDocumentSupport.textOrEmpty(path);
            direction = direction == null ? RelationDocumentSortDirection.ASC : direction;
            tieBreaker = ModelDocumentSupport.textOrEmpty(tieBreaker);
        }
    }
}
