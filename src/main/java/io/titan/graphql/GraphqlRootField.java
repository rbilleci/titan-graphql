package io.titan.graphql;

import java.util.List;

public final class GraphqlRootField {

    private static final int DEFAULT_MAX_LIMIT = 100;

    public enum ResultCardinality {
        ONE,
        MANY
    }

    // Renamed from RetrievalShape under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RootRetrievalShape {
        POINT_LOOKUP,
        LIST_QUERY
    }

    public enum RootPaginationMode {
        NONE,
        RELAY_CONNECTION
    }

    // Renamed from CursorDirection under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RootCursorDirection {
        ASC,
        DESC
    }

    // Renamed from NullOrdering under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RootNullOrdering {
        NULLS_FIRST,
        NULLS_LAST
    }

    // Renamed from ContextFilterValueType under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RootContextFilterValueType {
        BOOLEAN,
        ID,
        STRING
    }

    // Renamed from ContextFilterPhase under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RootContextFilterPhase {
        BEFORE_CLIENT_FILTERS
    }

    // Renamed from ContextFilter under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record RootContextFilter(
            String name,
            String columnName,
            RootContextFilterValueType valueType,
            String contextKey,
            boolean failClosed,
            RootContextFilterPhase phase
    ) {
    }

    // Renamed from CursorOrdering under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record RootCursorOrdering(
            String name,
            String columnName,
            String cursorPath,
            RootCursorDirection direction,
            String tieBreakerColumnName
    ) {
        public static RootCursorOrdering ascending(String name, String columnName, String cursorPath) {
            return new RootCursorOrdering(name, columnName, cursorPath, RootCursorDirection.ASC, "id");
        }

        boolean declared() {
            return name.isBlank() == false
                    && columnName.isBlank() == false
                    && cursorPath.isBlank() == false
                    && tieBreakerColumnName.isBlank() == false;
        }
    }

    // Renamed from SortPath under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record RootFieldSortPath(
            String name,
            String columnName,
            String sortPath,
            int sortHopCount,
            RootCursorDirection direction,
            RootNullOrdering nullOrdering,
            String tieBreakerColumnName
    ) {
    }

    // Renamed from FilterPath under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record RootFieldFilterPath(
            String name,
            String columnName,
            String filterPath,
            int filterHopCount,
            String scalarType,
            List<GraphqlFieldDescriptor.ScalarFilterOperator> operators
    ) {
        public RootFieldFilterPath {
            operators = List.copyOf(operators);
        }
    }

    /** One required, equality-bound component of a point-root key. */
    public record PointKeyArgument(String name, String graphqlType, String columnName) {
        public PointKeyArgument {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("point key name is required");
            if (graphqlType == null || graphqlType.isBlank()) {
                throw new IllegalArgumentException("point key GraphQL type is required");
            }
            if (columnName == null) columnName = "";
        }
    }

    public record RetrievalCapabilities(
            boolean directRoot,
            boolean supportsKeyArgument,
            boolean supportsLimitArgument,
            boolean supportsFilterArguments,
            boolean supportsTotalCount
    ) {
        public static RetrievalCapabilities currentDefault(
                ResultCardinality cardinality,
                List<GraphqlRootArgumentDescriptor> filters
        ) {
            return switch (cardinality) {
                case ONE -> new RetrievalCapabilities(true, true, false, false, false);
                case MANY -> new RetrievalCapabilities(true, false, true, filters.isEmpty() == false, false);
            };
        }
    }

    private final String name;
    private final String retrievalName;
    private final String typeName;
    private final String requiredIdArgumentName;
    private final String limitArgumentName;
    private final RootRetrievalShape retrievalShape;
    private final RootPaginationMode rootPaginationMode;
    private final ResultCardinality resultCardinality;
    private final int defaultLimit;
    private final int maxLimit;
    private final List<GraphqlRootArgumentDescriptor> filterArguments;
    private final RetrievalCapabilities retrievalCapabilities;
    private final RootCursorOrdering cursorOrdering;
    private final List<RootFieldFilterPath> filterPaths;
    private final List<RootFieldSortPath> sortPaths;
    private final List<RootContextFilter> contextFilters;
    private final List<PointKeyArgument> pointKeyArguments;

    public GraphqlRootField(String name, String typeName, String requiredIdArgumentName) {
        this(name, typeName, requiredIdArgumentName, "", ResultCardinality.ONE, 1);
    }

    public GraphqlRootField(
            String name,
            String typeName,
            String requiredIdArgumentName,
            String limitArgumentName,
            ResultCardinality resultCardinality,
            int defaultLimit
    ) {
        this(name, typeName, requiredIdArgumentName, limitArgumentName, resultCardinality, defaultLimit, List.of());
    }

    public GraphqlRootField(
            String name,
            String typeName,
            String requiredIdArgumentName,
            String limitArgumentName,
            ResultCardinality resultCardinality,
            int defaultLimit,
            List<GraphqlRootArgumentDescriptor> filterArguments
    ) {
        this(name, name, typeName, requiredIdArgumentName, limitArgumentName, resultCardinality, defaultLimit, DEFAULT_MAX_LIMIT, filterArguments);
    }

    public GraphqlRootField(
            String name,
            String retrievalName,
            String typeName,
            String requiredIdArgumentName,
            String limitArgumentName,
            ResultCardinality resultCardinality,
            int defaultLimit,
            int maxLimit,
            List<GraphqlRootArgumentDescriptor> filterArguments
    ) {
        this(
                name,
                retrievalName,
                typeName,
                requiredIdArgumentName,
                limitArgumentName,
                defaultShape(resultCardinality),
                RootPaginationMode.NONE,
                resultCardinality,
                defaultLimit,
                maxLimit,
                filterArguments,
                RetrievalCapabilities.currentDefault(resultCardinality, filterArguments),
                RootCursorOrdering.ascending("id", "id", "id"),
                List.of(),
                List.of(),
                List.of()
        );
    }

    public GraphqlRootField(
            String name,
            String retrievalName,
            String typeName,
            String requiredIdArgumentName,
            String limitArgumentName,
            RootRetrievalShape retrievalShape,
            RootPaginationMode rootPaginationMode,
            ResultCardinality resultCardinality,
            int defaultLimit,
            int maxLimit,
            List<GraphqlRootArgumentDescriptor> filterArguments,
            RetrievalCapabilities retrievalCapabilities,
            RootCursorOrdering cursorOrdering,
            List<RootFieldFilterPath> filterPaths,
            List<RootFieldSortPath> sortPaths,
            List<RootContextFilter> contextFilters
    ) {
        this(
                name, retrievalName, typeName, requiredIdArgumentName, limitArgumentName,
                retrievalShape, rootPaginationMode, resultCardinality, defaultLimit, maxLimit,
                filterArguments, retrievalCapabilities, cursorOrdering, filterPaths, sortPaths,
                contextFilters,
                resultCardinality == ResultCardinality.ONE && requiredIdArgumentName != null
                        && !requiredIdArgumentName.isBlank()
                        ? List.of(new PointKeyArgument(requiredIdArgumentName, "Int", "")) : List.of()
        );
    }

    public GraphqlRootField(
            String name,
            String retrievalName,
            String typeName,
            String requiredIdArgumentName,
            String limitArgumentName,
            RootRetrievalShape retrievalShape,
            RootPaginationMode rootPaginationMode,
            ResultCardinality resultCardinality,
            int defaultLimit,
            int maxLimit,
            List<GraphqlRootArgumentDescriptor> filterArguments,
            RetrievalCapabilities retrievalCapabilities,
            RootCursorOrdering cursorOrdering,
            List<RootFieldFilterPath> filterPaths,
            List<RootFieldSortPath> sortPaths,
            List<RootContextFilter> contextFilters,
            List<PointKeyArgument> pointKeyArguments
    ) {
        this.name = name;
        this.retrievalName = retrievalName;
        this.typeName = typeName;
        this.requiredIdArgumentName = requiredIdArgumentName;
        this.limitArgumentName = limitArgumentName;
        this.retrievalShape = retrievalShape;
        this.rootPaginationMode = rootPaginationMode;
        this.resultCardinality = resultCardinality;
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
        this.filterArguments = List.copyOf(filterArguments);
        this.retrievalCapabilities = retrievalCapabilities;
        this.cursorOrdering = cursorOrdering;
        this.filterPaths = List.copyOf(filterPaths);
        this.sortPaths = List.copyOf(sortPaths);
        this.contextFilters = List.copyOf(contextFilters);
        this.pointKeyArguments = List.copyOf(pointKeyArguments);
    }

    public GraphqlRootField(
            String name,
            String retrievalName,
            String typeName,
            String requiredIdArgumentName,
            String limitArgumentName,
            RootRetrievalShape retrievalShape,
            RootPaginationMode rootPaginationMode,
            ResultCardinality resultCardinality,
            int defaultLimit,
            int maxLimit,
            List<GraphqlRootArgumentDescriptor> filterArguments,
            RetrievalCapabilities retrievalCapabilities,
            RootCursorOrdering cursorOrdering,
            List<RootFieldFilterPath> filterPaths,
            List<RootFieldSortPath> sortPaths
    ) {
        this(
                name,
                retrievalName,
                typeName,
                requiredIdArgumentName,
                limitArgumentName,
                retrievalShape,
                rootPaginationMode,
                resultCardinality,
                defaultLimit,
                maxLimit,
                filterArguments,
                retrievalCapabilities,
                cursorOrdering,
                filterPaths,
                sortPaths,
                List.of()
        );
    }

    public GraphqlRootField(
            String name,
            String retrievalName,
            String typeName,
            String requiredIdArgumentName,
            String limitArgumentName,
            RootRetrievalShape retrievalShape,
            RootPaginationMode rootPaginationMode,
            ResultCardinality resultCardinality,
            int defaultLimit,
            int maxLimit,
            List<GraphqlRootArgumentDescriptor> filterArguments,
            RetrievalCapabilities retrievalCapabilities,
            RootCursorOrdering cursorOrdering
    ) {
        this(
                name,
                retrievalName,
                typeName,
                requiredIdArgumentName,
                limitArgumentName,
                retrievalShape,
                rootPaginationMode,
                resultCardinality,
                defaultLimit,
                maxLimit,
                filterArguments,
                retrievalCapabilities,
                cursorOrdering,
                List.of(),
                List.of(),
                List.of()
        );
    }

    public GraphqlRootField(
            String name,
            String retrievalName,
            String typeName,
            String requiredIdArgumentName,
            String limitArgumentName,
            ResultCardinality resultCardinality,
            int defaultLimit,
            List<GraphqlRootArgumentDescriptor> filterArguments
    ) {
        this(name, retrievalName, typeName, requiredIdArgumentName, limitArgumentName, resultCardinality, defaultLimit, DEFAULT_MAX_LIMIT, filterArguments);
    }

    public String name() {
        return name;
    }

    public String retrievalName() {
        return retrievalName;
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

    public RootRetrievalShape retrievalShape() {
        return retrievalShape;
    }

    public RootPaginationMode rootPaginationMode() {
        return rootPaginationMode;
    }

    public ResultCardinality resultCardinality() {
        return resultCardinality;
    }

    public int defaultLimit() {
        return defaultLimit;
    }

    public int maxLimit() {
        return maxLimit;
    }

    public List<GraphqlRootArgumentDescriptor> filterArguments() {
        return filterArguments;
    }

    public RetrievalCapabilities retrievalCapabilities() {
        return retrievalCapabilities;
    }

    public RootCursorOrdering cursorOrdering() {
        return cursorOrdering;
    }

    public List<RootFieldFilterPath> filterPaths() {
        return filterPaths;
    }

    public List<RootFieldSortPath> sortPaths() {
        return sortPaths;
    }

    public List<RootContextFilter> contextFilters() {
        return contextFilters;
    }

    public List<PointKeyArgument> pointKeyArguments() {
        return pointKeyArguments;
    }

    public String requiredIdArgumentType() {
        return pointKeyArguments.isEmpty() ? "Int" : pointKeyArguments.getFirst().graphqlType();
    }

    public RootFieldFilterPath filterPath(String name) {
        for (RootFieldFilterPath filterPath : filterPaths) {
            if (filterPath.name().equals(name)) {
                return filterPath;
            }
        }
        return null;
    }

    public GraphqlRootArgumentDescriptor filterArgument(String name) {
        for (GraphqlRootArgumentDescriptor argument : filterArguments) {
            if (argument.name().equals(name)) {
                return argument;
            }
        }
        return null;
    }

    private static RootRetrievalShape defaultShape(ResultCardinality cardinality) {
        return switch (cardinality) {
            case ONE -> RootRetrievalShape.POINT_LOOKUP;
            case MANY -> RootRetrievalShape.LIST_QUERY;
        };
    }
}
