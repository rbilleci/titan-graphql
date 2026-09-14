package io.titan.graphql;

import java.util.List;

public final class ProjectionRetrieval {

    // Renamed from Cardinality under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RetrievalCardinality {
        ONE,
        MANY
    }

    public enum OperationShape {
        POINT_LOOKUP,
        LIST_QUERY
    }

    // Renamed from PaginationMode under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RetrievalPaginationMode {
        NONE,
        RELAY_CONNECTION
    }

    // Renamed from CursorDirection under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RetrievalCursorDirection {
        ASC,
        DESC
    }

    // Renamed from NullOrdering under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RetrievalNullOrdering {
        NULLS_FIRST,
        NULLS_LAST
    }

    // Renamed from ContextFilterValueType under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RetrievalContextFilterValueType {
        BOOLEAN,
        ID,
        STRING
    }

    // Renamed from ContextFilterPhase under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RetrievalContextFilterPhase {
        BEFORE_CLIENT_FILTERS
    }

    // Renamed from ContextFilter under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record RetrievalContextFilter(
            String name,
            String columnName,
            RetrievalContextFilterValueType valueType,
            String contextKey,
            boolean failClosed,
            RetrievalContextFilterPhase phase
    ) {
        public static RetrievalContextFilter booleanEquals(String name, String columnName, String contextKey) {
            return new RetrievalContextFilter(
                    name,
                    columnName,
                    RetrievalContextFilterValueType.BOOLEAN,
                    contextKey,
                    true,
                    RetrievalContextFilterPhase.BEFORE_CLIENT_FILTERS
            );
        }
    }

    // Renamed from CursorOrdering under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record RetrievalCursorOrdering(
            String name,
            String columnName,
            String cursorPath,
            RetrievalCursorDirection direction,
            String tieBreakerColumnName
    ) {
        public static RetrievalCursorOrdering ascending(String name, String columnName, String cursorPath) {
            return new RetrievalCursorOrdering(name, columnName, cursorPath, RetrievalCursorDirection.ASC, "id");
        }
    }

    // Renamed from SortPath under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record RetrievalSortPath(
            String name,
            String columnName,
            String sortPath,
            int sortHopCount,
            RetrievalCursorDirection direction,
            RetrievalNullOrdering nullOrdering,
            String tieBreakerColumnName
    ) {
        public static RetrievalSortPath ascending(String name, String columnName, String sortPath, int sortHopCount) {
            return new RetrievalSortPath(name, columnName, sortPath, sortHopCount, RetrievalCursorDirection.ASC, RetrievalNullOrdering.NULLS_LAST, "id");
        }

        public static RetrievalSortPath descending(String name, String columnName, String sortPath, int sortHopCount) {
            return new RetrievalSortPath(name, columnName, sortPath, sortHopCount, RetrievalCursorDirection.DESC, RetrievalNullOrdering.NULLS_LAST, "id");
        }
    }

    // Renamed from FilterPath under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record RetrievalFilterPath(
            String name,
            String columnName,
            String filterPath,
            int filterHopCount,
            String scalarType,
            ProjectionField.FilterCapabilities filterCapabilities
    ) {
        public static RetrievalFilterPath string(String name, String columnName, String filterPath, int filterHopCount) {
            return new RetrievalFilterPath(
                    name,
                    columnName,
                    filterPath,
                    filterHopCount,
                    "String",
                    ProjectionField.FilterCapabilities.defaultString()
            );
        }
    }

    // Renamed from Capabilities under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record ProjectionRetrievalCapabilities(
            boolean directRoot,
            boolean supportsKeyArgument,
            boolean supportsLimitArgument,
            boolean supportsFilterArguments,
            boolean supportsTotalCount
    ) {
        public static ProjectionRetrievalCapabilities pointRoot() {
            return new ProjectionRetrievalCapabilities(true, true, false, false, false);
        }

        public static ProjectionRetrievalCapabilities listRoot() {
            return new ProjectionRetrievalCapabilities(true, false, true, true, false);
        }

        public static ProjectionRetrievalCapabilities connectionRoot() {
            return new ProjectionRetrievalCapabilities(true, false, false, true, true);
        }
    }

    // Renamed from Argument under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record RetrievalArgument(String name, String columnName, RetrievalArgumentKind kind) {
        // Renamed from Kind under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
        // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
        enum RetrievalArgumentKind {
            INT_EQUALS
        }

        public static RetrievalArgument intEquals(String name, String columnName) {
            return new RetrievalArgument(name, columnName, RetrievalArgumentKind.INT_EQUALS);
        }
    }

    private final String name;
    private final String typeName;
    private final String requiredIdArgumentName;
    private final String limitArgumentName;
    private final OperationShape operationShape;
    private final RetrievalPaginationMode paginationMode;
    private final RetrievalCardinality cardinality;
    private final int defaultLimit;
    private final int maxLimit;
    private final List<RetrievalArgument> arguments;
    private final ProjectionRetrievalCapabilities capabilities;
    private final RetrievalCursorOrdering cursorOrdering;
    private final List<RetrievalFilterPath> filterPaths;
    private final List<RetrievalSortPath> sortPaths;
    private final List<RetrievalContextFilter> contextFilters;

    private ProjectionRetrieval(
            String name,
            String typeName,
            String requiredIdArgumentName,
            String limitArgumentName,
            OperationShape operationShape,
            RetrievalPaginationMode paginationMode,
            RetrievalCardinality cardinality,
            int defaultLimit,
            int maxLimit,
            List<RetrievalArgument> arguments,
            ProjectionRetrievalCapabilities capabilities,
            RetrievalCursorOrdering cursorOrdering,
            List<RetrievalFilterPath> filterPaths,
            List<RetrievalSortPath> sortPaths,
            List<RetrievalContextFilter> contextFilters
    ) {
        this.name = name;
        this.typeName = typeName;
        this.requiredIdArgumentName = requiredIdArgumentName;
        this.limitArgumentName = limitArgumentName;
        this.operationShape = operationShape;
        this.paginationMode = paginationMode;
        this.cardinality = cardinality;
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
        this.arguments = List.copyOf(arguments);
        this.capabilities = capabilities;
        this.cursorOrdering = cursorOrdering;
        this.filterPaths = List.copyOf(filterPaths);
        this.sortPaths = List.copyOf(sortPaths);
        this.contextFilters = List.copyOf(contextFilters);
    }

    public static ProjectionRetrieval point(String name, String typeName, String requiredIdArgumentName) {
        return point(name, typeName, requiredIdArgumentName, ProjectionRetrievalCapabilities.pointRoot());
    }

    static ProjectionRetrieval point(
            String name,
            String typeName,
            String requiredIdArgumentName,
            ProjectionRetrievalCapabilities capabilities
    ) {
        return new ProjectionRetrieval(
                name,
                typeName,
                requiredIdArgumentName,
                "",
                OperationShape.POINT_LOOKUP,
                RetrievalPaginationMode.NONE,
                RetrievalCardinality.ONE,
                1,
                1,
                List.of(),
                capabilities,
                RetrievalCursorOrdering.ascending("id", "id", "id"),
                List.of(),
                List.of(),
                List.of()
        );
    }

    static ProjectionRetrieval list(
            String name,
            String typeName,
            String limitArgumentName,
            int defaultLimit,
            List<RetrievalArgument> arguments
    ) {
        return list(name, typeName, limitArgumentName, defaultLimit, 100, arguments);
    }

    static ProjectionRetrieval list(
            String name,
            String typeName,
            String limitArgumentName,
            int defaultLimit,
            int maxLimit,
            List<RetrievalArgument> arguments
    ) {
        return list(name, typeName, limitArgumentName, defaultLimit, maxLimit, arguments, ProjectionRetrievalCapabilities.listRoot());
    }

    static ProjectionRetrieval list(
            String name,
            String typeName,
            String limitArgumentName,
            int defaultLimit,
            int maxLimit,
            List<RetrievalArgument> arguments,
            ProjectionRetrievalCapabilities capabilities
    ) {
        return list(
                name,
                typeName,
                limitArgumentName,
                defaultLimit,
                maxLimit,
                RetrievalPaginationMode.NONE,
                arguments,
                capabilities
        );
    }

    public static ProjectionRetrieval relayConnection(
            String name,
            String typeName,
            int defaultLimit,
            int maxLimit,
            List<RetrievalArgument> arguments
    ) {
        return relayConnection(
                name,
                typeName,
                defaultLimit,
                maxLimit,
                arguments,
                RetrievalCursorOrdering.ascending("id", "id", "id"),
                List.of(),
                List.of(RetrievalSortPath.ascending("id", "id", "id", 0))
        );
    }

    public static ProjectionRetrieval relayConnection(
            String name,
            String typeName,
            int defaultLimit,
            int maxLimit,
            List<RetrievalArgument> arguments,
            RetrievalCursorOrdering cursorOrdering
    ) {
        return list(
                name,
                typeName,
                "",
                defaultLimit,
                maxLimit,
                RetrievalPaginationMode.RELAY_CONNECTION,
                arguments,
                ProjectionRetrievalCapabilities.connectionRoot(),
                cursorOrdering,
                List.of(),
                List.of(RetrievalSortPath.ascending(cursorOrdering.name(), cursorOrdering.columnName(), cursorOrdering.cursorPath(), 0)),
                List.of()
        );
    }

    public static ProjectionRetrieval relayConnection(
            String name,
            String typeName,
            int defaultLimit,
            int maxLimit,
            List<RetrievalArgument> arguments,
            RetrievalCursorOrdering cursorOrdering,
            List<RetrievalSortPath> sortPaths
    ) {
        return relayConnection(
                name,
                typeName,
                defaultLimit,
                maxLimit,
                arguments,
                cursorOrdering,
                List.of(),
                sortPaths
        );
    }

    public static ProjectionRetrieval relayConnection(
            String name,
            String typeName,
            int defaultLimit,
            int maxLimit,
            List<RetrievalArgument> arguments,
            RetrievalCursorOrdering cursorOrdering,
            List<RetrievalFilterPath> filterPaths,
            List<RetrievalSortPath> sortPaths
    ) {
        return relayConnection(
                name,
                typeName,
                defaultLimit,
                maxLimit,
                arguments,
                cursorOrdering,
                filterPaths,
                sortPaths,
                List.of()
        );
    }

    public static ProjectionRetrieval relayConnection(
            String name,
            String typeName,
            int defaultLimit,
            int maxLimit,
            List<RetrievalArgument> arguments,
            RetrievalCursorOrdering cursorOrdering,
            List<RetrievalFilterPath> filterPaths,
            List<RetrievalSortPath> sortPaths,
            List<RetrievalContextFilter> contextFilters
    ) {
        return list(
                name,
                typeName,
                "",
                defaultLimit,
                maxLimit,
                RetrievalPaginationMode.RELAY_CONNECTION,
                arguments,
                ProjectionRetrievalCapabilities.connectionRoot(),
                cursorOrdering,
                filterPaths,
                sortPaths,
                contextFilters
        );
    }

    static ProjectionRetrieval list(
            String name,
            String typeName,
            String limitArgumentName,
            int defaultLimit,
            int maxLimit,
            RetrievalPaginationMode paginationMode,
            List<RetrievalArgument> arguments,
            ProjectionRetrievalCapabilities capabilities
    ) {
        return list(
                name,
                typeName,
                limitArgumentName,
                defaultLimit,
                maxLimit,
                paginationMode,
                arguments,
                capabilities,
                RetrievalCursorOrdering.ascending("id", "id", "id")
        );
    }

    static ProjectionRetrieval list(
            String name,
            String typeName,
            String limitArgumentName,
            int defaultLimit,
            int maxLimit,
            RetrievalPaginationMode paginationMode,
            List<RetrievalArgument> arguments,
            ProjectionRetrievalCapabilities capabilities,
            RetrievalCursorOrdering cursorOrdering
    ) {
        return list(
                name,
                typeName,
                limitArgumentName,
                defaultLimit,
                maxLimit,
                paginationMode,
                arguments,
                capabilities,
                cursorOrdering,
                List.of(),
                List.of(),
                List.of()
        );
    }

    static ProjectionRetrieval list(
            String name,
            String typeName,
            String limitArgumentName,
            int defaultLimit,
            int maxLimit,
            RetrievalPaginationMode paginationMode,
            List<RetrievalArgument> arguments,
            ProjectionRetrievalCapabilities capabilities,
            RetrievalCursorOrdering cursorOrdering,
            List<RetrievalFilterPath> filterPaths,
            List<RetrievalSortPath> sortPaths
    ) {
        return list(
                name,
                typeName,
                limitArgumentName,
                defaultLimit,
                maxLimit,
                paginationMode,
                arguments,
                capabilities,
                cursorOrdering,
                filterPaths,
                sortPaths,
                List.of()
        );
    }

    static ProjectionRetrieval list(
            String name,
            String typeName,
            String limitArgumentName,
            int defaultLimit,
            int maxLimit,
            RetrievalPaginationMode paginationMode,
            List<RetrievalArgument> arguments,
            ProjectionRetrievalCapabilities capabilities,
            RetrievalCursorOrdering cursorOrdering,
            List<RetrievalFilterPath> filterPaths,
            List<RetrievalSortPath> sortPaths,
            List<RetrievalContextFilter> contextFilters
    ) {
        return new ProjectionRetrieval(
                name,
                typeName,
                "",
                limitArgumentName,
                OperationShape.LIST_QUERY,
                paginationMode,
                RetrievalCardinality.MANY,
                defaultLimit,
                maxLimit,
                arguments,
                capabilities,
                cursorOrdering,
                filterPaths,
                sortPaths,
                contextFilters
        );
    }

    public String name() {
        return name;
    }

    public String typeName() {
        return typeName;
    }

    public String requiredIdArgumentName() {
        return requiredIdArgumentName;
    }

    public String limitArgumentName() {
        return limitArgumentName;
    }

    public OperationShape operationShape() {
        return operationShape;
    }

    public RetrievalPaginationMode paginationMode() {
        return paginationMode;
    }

    public RetrievalCardinality cardinality() {
        return cardinality;
    }

    public int defaultLimit() {
        return defaultLimit;
    }

    public int maxLimit() {
        return maxLimit;
    }

    public List<RetrievalArgument> arguments() {
        return arguments;
    }

    public ProjectionRetrievalCapabilities capabilities() {
        return capabilities;
    }

    public RetrievalCursorOrdering cursorOrdering() {
        return cursorOrdering;
    }

    public List<RetrievalFilterPath> filterPaths() {
        return filterPaths;
    }

    public List<RetrievalSortPath> sortPaths() {
        return sortPaths;
    }

    public List<RetrievalContextFilter> contextFilters() {
        return contextFilters;
    }
}
