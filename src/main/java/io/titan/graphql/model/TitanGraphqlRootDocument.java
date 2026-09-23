package io.titan.graphql.model;

import java.util.List;

public record TitanGraphqlRootDocument(
        String name,
        String type,
        RootDocumentOperation operation,
        RootDocumentArgument argument,
        RootDocumentPagination pagination,
        List<RootDocumentArgument> arguments,
        List<RootDocumentFilterPath> filterPaths,
        List<RootDocumentSortPath> sortPaths,
        List<String> contextFilters,
        List<String> policies,
        String outputType
) {
    public TitanGraphqlRootDocument {
        name = ModelDocumentSupport.requireText(name, "root.name");
        type = ModelDocumentSupport.requireText(type, "root.type");
        if (operation == null) {
            throw new IllegalArgumentException("root.operation is required");
        }
        arguments = ModelDocumentSupport.listOrEmpty(arguments);
        filterPaths = ModelDocumentSupport.listOrEmpty(filterPaths);
        sortPaths = ModelDocumentSupport.listOrEmpty(sortPaths);
        contextFilters = ModelDocumentSupport.listOrEmpty(contextFilters);
        policies = ModelDocumentSupport.listOrEmpty(policies);
        outputType = ModelDocumentSupport.textOrEmpty(outputType);
    }

    public TitanGraphqlRootDocument(
            String name,
            String type,
            RootDocumentOperation operation,
            RootDocumentArgument argument,
            RootDocumentPagination pagination,
            List<RootDocumentArgument> arguments,
            List<RootDocumentFilterPath> filterPaths,
            List<RootDocumentSortPath> sortPaths,
            List<String> contextFilters,
            List<String> policies
    ) {
        this(name, type, operation, argument, pagination, arguments, filterPaths, sortPaths,
                contextFilters, policies, "");
    }

    public TitanGraphqlRootDocument(
            String name,
            String type,
            RootDocumentOperation operation,
            RootDocumentArgument argument,
            RootDocumentPagination pagination,
            List<RootDocumentArgument> arguments,
            List<RootDocumentFilterPath> filterPaths,
            List<RootDocumentSortPath> sortPaths,
            List<String> contextFilters
    ) {
        this(name, type, operation, argument, pagination, arguments, filterPaths, sortPaths,
                contextFilters, List.of(), "");
    }

    public static TitanGraphqlRootDocument point(String name, String type, RootDocumentArgument argument) {
        return new TitanGraphqlRootDocument(
                name,
                type,
                RootDocumentOperation.POINT,
                argument,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ""
        );
    }

    public static TitanGraphqlRootDocument connection(
            String name,
            String type,
            RootDocumentPagination pagination,
            List<RootDocumentArgument> arguments,
            List<RootDocumentFilterPath> filterPaths,
            List<RootDocumentSortPath> sortPaths,
            List<String> contextFilters
    ) {
        return new TitanGraphqlRootDocument(
                name,
                type,
                RootDocumentOperation.CONNECTION,
                null,
                pagination,
                arguments,
                filterPaths,
                sortPaths,
                contextFilters,
                List.of(),
                ""
        );
    }

    // Renamed from Operation under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RootDocumentOperation {
        POINT,
        CONNECTION
    }

    // Renamed from ArgumentKind under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RootDocumentArgumentKind {
        EQUALS,
        RELAY_FIRST,
        RELAY_AFTER,
        RELAY_LAST,
        RELAY_BEFORE
    }

    public enum TotalCountMode {
        EXACT,
        ESTIMATED,
        NONE
    }

    // Renamed from SortDirection under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RootDocumentSortDirection {
        ASC,
        DESC
    }

    // Renamed from NullOrdering under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RootDocumentNullOrdering {
        FIRST,
        LAST
    }

    // Renamed from Argument under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record RootDocumentArgument(
            String name,
            String type,
            RootDocumentArgumentKind kind,
            String column,
            String path,
            int hops,
            String defaultValue
    ) {
        public RootDocumentArgument {
            name = ModelDocumentSupport.requireText(name, "root.argument.name");
            type = ModelDocumentSupport.textOrEmpty(type);
            kind = kind == null ? RootDocumentArgumentKind.EQUALS : kind;
            column = ModelDocumentSupport.textOrEmpty(column);
            path = ModelDocumentSupport.textOrEmpty(path);
            defaultValue = ModelDocumentSupport.textOrEmpty(defaultValue);
        }

        public RootDocumentArgument(
                String name,
                String type,
                RootDocumentArgumentKind kind,
                String column,
                String path,
                int hops
        ) {
            this(name, type, kind, column, path, hops, "");
        }

        public static RootDocumentArgument equals(String name, String type, String column) {
            return new RootDocumentArgument(name, type, RootDocumentArgumentKind.EQUALS, column, column, 0, "");
        }
    }

    // Renamed from Pagination under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record RootDocumentPagination(
            int defaultPageSize,
            int maxPageSize,
            TotalCountMode totalCount,
            Cursor cursor
    ) {
        public RootDocumentPagination {
            totalCount = totalCount == null ? TotalCountMode.NONE : totalCount;
        }
    }

    public record Cursor(
            String path,
            String column,
            RootDocumentSortDirection direction,
            String tieBreaker
    ) {
        public Cursor {
            path = ModelDocumentSupport.requireText(path, "root.pagination.cursor.path");
            column = ModelDocumentSupport.requireText(column, "root.pagination.cursor.column");
            direction = direction == null ? RootDocumentSortDirection.ASC : direction;
            tieBreaker = ModelDocumentSupport.textOrEmpty(tieBreaker);
        }
    }

    // Renamed from FilterPath under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record RootDocumentFilterPath(
            String name,
            String type,
            String column,
            String path,
            int hops,
            List<String> operators
    ) {
        public RootDocumentFilterPath {
            name = ModelDocumentSupport.requireText(name, "root.filterPath.name");
            type = ModelDocumentSupport.requireText(type, "root.filterPath.type");
            column = ModelDocumentSupport.requireText(column, "root.filterPath.column");
            path = ModelDocumentSupport.textOrEmpty(path);
            operators = ModelDocumentSupport.listOrEmpty(operators);
        }
    }

    // Renamed from SortPath under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record RootDocumentSortPath(
            String name,
            String column,
            String path,
            RootDocumentSortDirection direction,
            RootDocumentNullOrdering nulls,
            String tieBreaker,
            int hops
    ) {
        public RootDocumentSortPath {
            name = ModelDocumentSupport.requireText(name, "root.sortPath.name");
            column = ModelDocumentSupport.requireText(column, "root.sortPath.column");
            path = ModelDocumentSupport.textOrEmpty(path);
            direction = direction == null ? RootDocumentSortDirection.ASC : direction;
            nulls = nulls == null ? RootDocumentNullOrdering.LAST : nulls;
            tieBreaker = ModelDocumentSupport.textOrEmpty(tieBreaker);
        }
    }
}
