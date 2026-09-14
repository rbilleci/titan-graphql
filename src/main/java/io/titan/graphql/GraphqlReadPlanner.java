package io.titan.graphql;

import java.util.ArrayList;
import java.util.List;

public final class GraphqlReadPlanner {

    private GraphqlReadPlanner() {
    }

    public static GraphqlReadPlan plan(GraphqlSchema schema, GraphqlSelection selection) {
        GraphqlObjectType rootType = requireType(schema, selection.rootTypeName());
        GraphqlTableDescriptor rootTable = requireTable(schema, rootType);
        GraphqlReadPlan.RootRead rootRead = new GraphqlReadPlan.RootRead(
                selection.rootFieldName(),
                selection.rootRetrievalName(),
                selection.rootRetrievalShape(),
                selection.rootRetrievalCapabilities(),
                selection.rootCursorOrdering(),
                rootType.name(),
                rootTable.qualifiedName(),
                projectedRootColumns(rootType, selection),
                selection.rootCardinality(),
                rootTable.primaryKeyColumnName(),
                selection.rootId(),
                selection.rootLimit(),
                selection.rootPagination(),
                rootCursorWindow(selection),
                countStrategy(selection),
                selection.rootContextFilters(),
                selection.rootConnectionSelection(),
                selection.rootFilters(),
                selection.generatedRootFilters(),
                selection.rootOrderBy()
        );

        List<GraphqlReadPlan.RelationRead> relationReads = new ArrayList<>();
        planRelations(schema, rootType, selection.rootFieldName(), selection.fields(), relationReads);
        return new GraphqlReadPlan(rootRead, relationReads);
    }

    private static GraphqlReadPlan.CountStrategy countStrategy(GraphqlSelection selection) {
        if (selection.rootConnectionSelection().totalCount()
                && selection.rootRetrievalCapabilities().supportsTotalCount()) {
            return GraphqlReadPlan.CountStrategy.EXACT_VISIBLE_ROWS;
        }
        return GraphqlReadPlan.CountStrategy.NONE;
    }

    private static List<String> projectedRootColumns(GraphqlObjectType rootType, GraphqlSelection selection) {
        List<String> columns = new ArrayList<>();
        for (GraphqlSelection.FieldSelection field : selection.fields()) {
            if (isTypename(field)) {
                continue;
            }
            GraphqlFieldDescriptor descriptor = requireField(rootType, field.name());
            if (descriptor.kind() == GraphqlFieldDescriptor.FieldKind.SCALAR) {
                addScalarColumns(columns, descriptor);
            } else if (descriptor.localColumnName().isBlank() == false) {
                addColumn(columns, descriptor.localColumnName());
            }
        }
        if (selection.rootConnectionSelection().selected()) {
            addColumn(columns, selection.rootCursorOrdering().columnName());
            addColumn(columns, selection.rootCursorOrdering().tieBreakerColumnName());
        }
        for (GraphqlRootField.RootContextFilter filter : selection.rootContextFilters()) {
            addColumn(columns, filter.columnName());
        }
        addGeneratedFilterColumns(rootType, columns, selection.generatedRootFilters());
        for (GraphqlSelection.RootOrder order : selection.rootOrderBy()) {
            addRootOrderColumns(rootType, columns, order);
        }
        return List.copyOf(columns);
    }

    private static GraphqlReadPlan.RootCursorWindow rootCursorWindow(GraphqlSelection selection) {
        if (selection.rootConnectionSelection().selected() == false) {
            return GraphqlReadPlan.RootCursorWindow.none(selection.rootLimit());
        }
        GraphqlRootField.RootCursorOrdering ordering = selection.rootCursorOrdering();
        GraphqlSelection.RootPagination pagination = selection.rootPagination();
        return new GraphqlReadPlan.RootCursorWindow(
                pagination.last() == null
                        ? GraphqlReadPlan.RootCursorWindowDirection.FORWARD
                        : GraphqlReadPlan.RootCursorWindowDirection.BACKWARD,
                afterOperator(ordering.direction()),
                pagination.afterCursor(),
                beforeOperator(ordering.direction()),
                pagination.beforeCursor(),
                selection.rootLimit(),
                selection.rootLimit() + 1
        );
    }

    private static String afterOperator(GraphqlRootField.RootCursorDirection direction) {
        return switch (direction) {
            case ASC -> ">";
            case DESC -> "<";
        };
    }

    private static String beforeOperator(GraphqlRootField.RootCursorDirection direction) {
        return switch (direction) {
            case ASC -> "<";
            case DESC -> ">";
        };
    }

    private static String afterOperator(GraphqlFieldDescriptor.RelationSortDirection direction) {
        return switch (direction) {
            case ASC -> ">";
            case DESC -> "<";
        };
    }

    private static String beforeOperator(GraphqlFieldDescriptor.RelationSortDirection direction) {
        return switch (direction) {
            case ASC -> "<";
            case DESC -> ">";
        };
    }

    private static void planRelations(
            GraphqlSchema schema,
            GraphqlObjectType parentType,
            String parentPath,
            List<GraphqlSelection.FieldSelection> fields,
            List<GraphqlReadPlan.RelationRead> relationReads
    ) {
        for (GraphqlSelection.FieldSelection field : fields) {
            if (field.kind() == GraphqlFieldDescriptor.FieldKind.RELATION) {
                GraphqlReadPlan.RelationRead read = planRelation(schema, parentType, parentPath, field);
                relationReads.add(read);
                GraphqlObjectType targetType = requireType(schema, read.targetTypeName());
                planRelations(schema, targetType, read.stepName(), field.selections(), relationReads);
            }
        }
    }

    private static GraphqlReadPlan.RelationRead planRelation(
            GraphqlSchema schema,
            GraphqlObjectType parentType,
            String parentPath,
            GraphqlSelection.FieldSelection field
    ) {
        GraphqlFieldDescriptor relation = requireField(parentType, field.name());
        GraphqlObjectType targetType = requireType(schema, relation.targetTypeName());
        GraphqlTableDescriptor targetTable = requireTable(schema, targetType);
        List<String> targetColumns = new ArrayList<>();
        addColumn(targetColumns, relation.targetColumnName());
        for (GraphqlSelection.FieldSelection nested : field.selections()) {
            if (isTypename(nested)) {
                continue;
            }
            GraphqlFieldDescriptor targetField = requireField(targetType, nested.name());
            if (targetField.kind() == GraphqlFieldDescriptor.FieldKind.SCALAR) {
                addScalarColumns(targetColumns, targetField);
            } else {
                addColumn(targetColumns, targetField.localColumnName());
            }
        }
        if (field.relationConnectionSelection().selected()) {
            GraphqlFieldDescriptor.RelationSortPath ordering = relation.relationSortPaths().getFirst();
            addColumn(targetColumns, ordering.columnName());
            addColumn(targetColumns, ordering.tieBreakerColumnName());
        }
        return new GraphqlReadPlan.RelationRead(
                parentPath + "." + field.name(),
                field.relationRetrievalName(),
                field.relationRetrievalShape(),
                parentType.name(),
                field.name(),
                targetType.name(),
                targetTable.qualifiedName(),
                targetColumns,
                relation.localColumnName(),
                relation.targetColumnName(),
                relation.relationCardinality(),
                relation.nullable(),
                relation.relationCapabilities(),
                field.relationConnectionSelection(),
                relationCursorWindow(field, relation),
                relationCountStrategy(field),
                relation.relationSortPaths(),
                field.relationArguments()
        );
    }

    private static GraphqlReadPlan.CountStrategy relationCountStrategy(GraphqlSelection.FieldSelection field) {
        if (field.relationConnectionSelection().totalCount()
                && field.relationConnectionSelection().selected()) {
            return GraphqlReadPlan.CountStrategy.EXACT_VISIBLE_ROWS;
        }
        return GraphqlReadPlan.CountStrategy.NONE;
    }

    private static GraphqlReadPlan.RelationCursorWindow relationCursorWindow(
            GraphqlSelection.FieldSelection field,
            GraphqlFieldDescriptor relation
    ) {
        if (field.relationConnectionSelection().selected() == false) {
            return GraphqlReadPlan.RelationCursorWindow.none(0);
        }
        int requestedRowCount = field.relationConnectionSelection().pageSize();
        GraphqlFieldDescriptor.RelationSortPath ordering = relation.relationSortPaths().getFirst();
        return new GraphqlReadPlan.RelationCursorWindow(
                relationArgument(field.relationArguments(), GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_LAST) == null
                        ? GraphqlReadPlan.RelationCursorWindowDirection.FORWARD
                        : GraphqlReadPlan.RelationCursorWindowDirection.BACKWARD,
                afterOperator(ordering.direction()),
                relationCursor(field.relationArguments(), GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_AFTER),
                beforeOperator(ordering.direction()),
                relationCursor(field.relationArguments(), GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_BEFORE),
                requestedRowCount,
                requestedRowCount + 1
        );
    }

    private static GraphqlCursorCodec.CursorPayload relationCursor(
            List<GraphqlSelection.RelationArgument> arguments,
            GraphqlRelationArgumentDescriptor.RelationArgumentKind kind
    ) {
        GraphqlSelection.RelationArgument argument = relationArgument(arguments, kind);
        return argument == null ? null : argument.cursorPayload();
    }

    private static GraphqlSelection.RelationArgument relationArgument(
            List<GraphqlSelection.RelationArgument> arguments,
            GraphqlRelationArgumentDescriptor.RelationArgumentKind kind
    ) {
        for (GraphqlSelection.RelationArgument argument : arguments) {
            if (argument.kind() == kind) {
                return argument;
            }
        }
        return null;
    }

    private static GraphqlObjectType requireType(GraphqlSchema schema, String typeName) {
        GraphqlObjectType type = schema.type(typeName);
        if (type == null) {
            throw new GraphqlException("unknown GraphQL type '" + typeName + "'");
        }
        return type;
    }

    private static GraphqlFieldDescriptor requireField(GraphqlObjectType type, String fieldName) {
        GraphqlFieldDescriptor field = type.field(fieldName);
        if (field == null) {
            throw new GraphqlException("unknown field '" + type.name() + "." + fieldName + "'");
        }
        return field;
    }

    private static GraphqlTableDescriptor requireTable(GraphqlSchema schema, GraphqlObjectType type) {
        GraphqlTableDescriptor table = schema.table(type.tableName());
        if (table == null) {
            throw new GraphqlException("unknown table descriptor '" + type.tableName() + "'");
        }
        return table;
    }

    private static void addColumn(List<String> columns, String columnName) {
        if (columnName.isBlank()) {
            return;
        }
        if (columns.contains(columnName) == false) {
            columns.add(columnName);
        }
    }

    private static void addScalarColumns(List<String> columns, GraphqlFieldDescriptor field) {
        if (field.computedExpression().present()) {
            for (String column : field.computedExpression().requiredColumns()) {
                addColumn(columns, column);
            }
            return;
        }
        addColumn(columns, field.columnName());
    }

    private static void addGeneratedFilterColumns(
            GraphqlObjectType rootType,
            List<String> columns,
            List<GraphqlSelection.GeneratedRootFilter> filters
    ) {
        for (GraphqlSelection.GeneratedRootFilter filter : filters) {
            if (filter.kind() == GraphqlSelection.GeneratedRootFilterKind.SCALAR) {
                GraphqlFieldDescriptor field = rootType.field(filter.fieldName());
                if (field != null && field.computedExpression().present()) {
                    addScalarColumns(columns, field);
                    continue;
                }
                addColumn(columns, filter.columnName());
            } else {
                addGeneratedFilterColumns(rootType, columns, filter.children());
            }
        }
    }

    private static void addRootOrderColumns(
            GraphqlObjectType rootType,
            List<String> columns,
            GraphqlSelection.RootOrder order
    ) {
        GraphqlFieldDescriptor field = rootType.field(order.name());
        if (field != null && field.computedExpression().present()) {
            addScalarColumns(columns, field);
            return;
        }
        addColumn(columns, order.columnName());
        addColumn(columns, order.tieBreakerColumnName());
    }

    private static boolean isTypename(GraphqlSelection.FieldSelection field) {
        return field.name().equals("__typename");
    }
}
