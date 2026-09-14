package io.titan.graphql;

import java.util.List;
import java.util.Map;

public final class GraphqlReadPlan {

    private final RootRead rootRead;
    private final List<RelationRead> relationReads;

    public GraphqlReadPlan(RootRead rootRead, List<RelationRead> relationReads) {
        this.rootRead = rootRead;
        this.relationReads = List.copyOf(relationReads);
    }

    public RootRead rootRead() {
        return rootRead;
    }

    public List<RelationRead> relationReads() {
        return relationReads;
    }

    public RelationRead relationRead(String name) {
        for (RelationRead read : relationReads) {
            if (read.stepName().equals(name)) {
                return read;
            }
        }
        return null;
    }

    public record RootRead(
            String stepName,
            String retrievalName,
            GraphqlRootField.RootRetrievalShape retrievalShape,
            GraphqlRootField.RetrievalCapabilities retrievalCapabilities,
            GraphqlRootField.RootCursorOrdering cursorOrdering,
            String typeName,
            String tableName,
            List<String> columnNames,
            GraphqlRootField.ResultCardinality cardinality,
            String keyColumnName,
            long keyValue,
            List<GraphqlRootField.PointKeyArgument> pointKeyArguments,
            Map<String, Object> keyValues,
            int limit,
            GraphqlSelection.RootPagination pagination,
            RootCursorWindow cursorWindow,
            CountStrategy countStrategy,
            List<GraphqlRootField.RootContextFilter> contextFilters,
            GraphqlSelection.RootConnectionSelection connectionSelection,
            List<GraphqlSelection.RootFilter> filters,
            List<GraphqlSelection.GeneratedRootFilter> generatedFilters,
            List<GraphqlSelection.RootOrder> orderBy
    ) {
        public RootRead {
            contextFilters = List.copyOf(contextFilters);
            columnNames = List.copyOf(columnNames);
            filters = List.copyOf(filters);
            generatedFilters = List.copyOf(generatedFilters);
            orderBy = List.copyOf(orderBy);
            pointKeyArguments = List.copyOf(pointKeyArguments);
            keyValues = Map.copyOf(keyValues);
        }
    }

    public enum RootCursorWindowDirection {
        NONE,
        FORWARD,
        BACKWARD
    }

    public enum CountStrategy {
        NONE,
        EXACT_VISIBLE_ROWS
    }

    public record RootCursorWindow(
            RootCursorWindowDirection direction,
            String afterComparisonOperator,
            GraphqlCursorCodec.CursorPayload afterCursor,
            String beforeComparisonOperator,
            GraphqlCursorCodec.CursorPayload beforeCursor,
            int requestedRowCount,
            int fetchRowCount
    ) {
        public static RootCursorWindow none(int rowCount) {
            return new RootCursorWindow(
                    RootCursorWindowDirection.NONE,
                    "",
                    null,
                    "",
                    null,
                    rowCount,
                    rowCount
            );
        }
    }

    public record RelationRead(
            String stepName,
            String retrievalName,
            GraphqlFieldDescriptor.RelationRetrievalShape retrievalShape,
            String parentTypeName,
            String fieldName,
            String targetTypeName,
            String targetTableName,
            List<String> targetColumnNames,
            String localColumnName,
            String targetColumnName,
            GraphqlFieldDescriptor.RelationCardinality cardinality,
            boolean nullable,
            GraphqlFieldDescriptor.RelationCapabilities capabilities,
            GraphqlSelection.RelationConnectionSelection connectionSelection,
            RelationCursorWindow cursorWindow,
            CountStrategy countStrategy,
            List<GraphqlFieldDescriptor.RelationSortPath> sortPaths,
            List<GraphqlSelection.RelationArgument> arguments
    ) {
        public RelationRead {
            connectionSelection = connectionSelection == null
                    ? GraphqlSelection.RelationConnectionSelection.none()
                    : connectionSelection;
            cursorWindow = cursorWindow == null
                    ? RelationCursorWindow.none(0)
                    : cursorWindow;
            countStrategy = countStrategy == null ? CountStrategy.NONE : countStrategy;
            targetColumnNames = List.copyOf(targetColumnNames);
            sortPaths = List.copyOf(sortPaths);
            arguments = List.copyOf(arguments);
        }
    }

    public enum RelationCursorWindowDirection {
        NONE,
        FORWARD,
        BACKWARD
    }

    public record RelationCursorWindow(
            RelationCursorWindowDirection direction,
            String afterComparisonOperator,
            GraphqlCursorCodec.CursorPayload afterCursor,
            String beforeComparisonOperator,
            GraphqlCursorCodec.CursorPayload beforeCursor,
            int requestedRowCount,
            int fetchRowCount
    ) {
        public static RelationCursorWindow none(int rowCount) {
            return new RelationCursorWindow(
                    RelationCursorWindowDirection.NONE,
                    "",
                    null,
                    "",
                    null,
                    rowCount,
                    rowCount
            );
        }
    }
}
