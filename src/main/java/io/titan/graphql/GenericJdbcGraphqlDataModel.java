package io.titan.graphql;

import static titan.dsl.DSL.select;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.titan.runtime.jdbc.JdbcExecutor;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import titan.dsl.Column;
import titan.dsl.Condition;
import titan.dsl.Nullability;
import titan.dsl.SQLType;
import titan.dsl.SelectBuilder;
import titan.dsl.SortField;
import titan.dsl.Table;

/**
 * Metadata-driven read executor for a {@link GraphqlSchema}.
 *
 * <p>The schema and validated selection determine every table, column, predicate, ordering,
 * and relation read. Titan DSL renders parameterized, dialect-aware SQL and Titan's JDBC
 * runtime owns binding, timeouts, and row mapping. Adding a schema therefore requires a
 * projection model, not handwritten query or resolver code. Mutations remain explicit
 * extension points through {@link GraphqlDataModel#executeMutation}.</p>
 */
public final class GenericJdbcGraphqlDataModel implements GraphqlDataModel {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GraphqlSchema schema;
    private final JdbcExecutor jdbc;

    public GenericJdbcGraphqlDataModel(GraphqlSchema schema, DataSource dataSource) {
        this(schema, JdbcExecutor.create(dataSource));
    }

    public GenericJdbcGraphqlDataModel(GraphqlSchema schema, JdbcExecutor jdbc) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc").withQueryTimeout(Duration.ofSeconds(30));
    }

    @Override
    public GraphqlSchema schema() {
        return schema;
    }

    @Override
    public GraphqlExecution execute(GraphqlSelection selection, GraphqlRequestContext context) {
        validateSupportedSelection(selection);
        GraphqlReadPlan readPlan = GraphqlReadPlanner.plan(schema, selection);
        GraphqlPlan observablePlan = new GraphqlPlan();
        List<Row> roots = readRoot(readPlan.rootRead(), context, observablePlan);

        Object result;
        if (selection.rootCardinality() == GraphqlRootField.ResultCardinality.ONE) {
            result = roots.isEmpty() ? null : renderObject(
                    selection.rootTypeName(), selection.rootFieldName(), selection.fields(), roots.getFirst(),
                    readPlan, observablePlan);
        } else if (selection.rootConnectionSelection().selected()) {
            result = renderRootConnection(selection, readPlan, roots, context, observablePlan);
        } else {
            List<Object> values = new ArrayList<>();
            for (Row root : roots) {
                values.add(renderObject(selection.rootTypeName(), selection.rootFieldName(), selection.fields(), root,
                        readPlan, observablePlan));
            }
            result = values;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put(selection.rootResponseKey(), result);
        try {
            return new GraphqlExecution(JSON.writeValueAsString(Map.of("data", data)), observablePlan);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("generic GraphQL result could not be serialized", ex);
        }
    }

    private List<Row> readRoot(
            GraphqlReadPlan.RootRead read,
            GraphqlRequestContext context,
            GraphqlPlan plan
    ) {
        RuntimeTable table = RuntimeTable.fromQualifiedName(read.tableName());
        List<String> columns = distinct(read.columnNames(), read.keyColumnName());
        SelectBuilder query = select(columns(table, columns)).from(table);
        Condition condition = rootCondition(table, read, context);
        if (condition != null) {
            query.where(condition);
        }
        applyRootOrdering(query, table, read);
        query.limit(read.cardinality() == GraphqlRootField.ResultCardinality.ONE
                ? 1 : read.cursorWindow().fetchRowCount());
        plan.addReadStep(read.stepName(), query.render(jdbc.dialect()).sql());
        return jdbc.fetch(query, resultSet -> row(resultSet, columns));
    }

    private Object renderRootConnection(
            GraphqlSelection selection,
            GraphqlReadPlan readPlan,
            List<Row> fetched,
            GraphqlRequestContext context,
            GraphqlPlan plan
    ) {
        GraphqlReadPlan.RootRead read = readPlan.rootRead();
        if (selection.rootPagination().last() != null
                || !selection.rootPagination().after().isEmpty()
                || !selection.rootPagination().before().isEmpty()) {
            throw unsupported("Relay cursors and backward root pagination");
        }
        int requested = read.cursorWindow().requestedRowCount();
        boolean hasNextPage = fetched.size() > requested;
        List<Row> page = fetched.size() > requested
                ? new ArrayList<>(fetched.subList(0, requested)) : fetched;
        GraphqlSelection.RootConnectionSelection connection = selection.rootConnectionSelection();
        Map<String, Object> value = new LinkedHashMap<>();
        if (connection.edges()) {
            List<Object> edges = new ArrayList<>();
            for (Row row : page) {
                Map<String, Object> edge = new LinkedHashMap<>();
                if (connection.edgeCursor()) edge.put("cursor", rootCursor(selection, read, row));
                if (connection.edgeNode()) edge.put("node", renderObject(
                        selection.rootTypeName(), selection.rootFieldName(), selection.fields(), row, readPlan, plan));
                edges.add(edge);
            }
            value.put("edges", edges);
        }
        if (connection.totalCount()) value.put("totalCount", countRoot(read, context, plan));
        if (connection.pageInfo()) {
            Map<String, Object> pageInfo = new LinkedHashMap<>();
            for (String field : connection.pageInfoFields()) {
                switch (field) {
                    case "hasNextPage" -> pageInfo.put(field, hasNextPage);
                    case "hasPreviousPage" -> pageInfo.put(field, false);
                    case "startCursor" -> pageInfo.put(field,
                            page.isEmpty() ? null : rootCursor(selection, read, page.getFirst()));
                    case "endCursor" -> pageInfo.put(field,
                            page.isEmpty() ? null : rootCursor(selection, read, page.getLast()));
                    default -> throw unsupported("pageInfo field '" + field + "'");
                }
            }
            value.put("pageInfo", pageInfo);
        }
        return value;
    }

    private long countRoot(GraphqlReadPlan.RootRead read, GraphqlRequestContext context, GraphqlPlan plan) {
        RuntimeTable table = RuntimeTable.fromQualifiedName(read.tableName());
        SelectBuilder count = select(titan.dsl.DSL.count()).from(table);
        Condition condition = rootCondition(table, read, context);
        if (condition != null) count.where(condition);
        plan.addReadStep(read.stepName() + ".totalCount", count.render(jdbc.dialect()).sql());
        return jdbc.fetch(count, resultSet -> resultSet.getLong(1)).getFirst();
    }

    private static String rootCursor(
            GraphqlSelection selection,
            GraphqlReadPlan.RootRead read,
            Row row
    ) {
        if (!selection.rootOrderBy().isEmpty()) {
            GraphqlSelection.RootOrder order = selection.rootOrderBy().getFirst();
            return GraphqlCursorCodec.encode(GraphqlCursorCodec.payload(
                    order,
                    String.valueOf(row.value(order.columnName())),
                    String.valueOf(row.value(order.tieBreakerColumnName()))));
        }
        return GraphqlCursorCodec.encode(GraphqlCursorCodec.payload(
                read.cursorOrdering(),
                String.valueOf(row.value(read.cursorOrdering().columnName())),
                String.valueOf(row.value(read.cursorOrdering().tieBreakerColumnName()))));
    }

    private static Condition rootCondition(
            RuntimeTable table,
            GraphqlReadPlan.RootRead read,
            GraphqlRequestContext context
    ) {
        Condition condition = null;
        if (read.cardinality() == GraphqlRootField.ResultCardinality.ONE) {
            condition = table.column(read.keyColumnName(), SQLType.BIGINT).eq(read.keyValue());
        }
        for (GraphqlSelection.RootFilter filter : read.filters()) {
            condition = and(condition, table.column(filter.columnName(), SQLType.BIGINT).eq(filter.value()));
        }
        for (GraphqlRootField.RootContextFilter filter : read.contextFilters()) {
            if (!context.contextFilterEnabled(filter.name())) continue;
            Object value = context.contextValue(filter.contextKey());
            if (value == null && filter.failClosed()) {
                throw new GraphqlException("required request context value '" + filter.contextKey()
                        + "' is missing for filter '" + filter.name() + "'");
            }
            if (value != null) {
                condition = and(condition, table.column(filter.columnName(), contextSqlType(filter)).eq(value));
            }
        }
        for (GraphqlSelection.GeneratedRootFilter filter : read.generatedFilters()) {
            condition = and(condition, generatedCondition(table, filter));
        }
        return condition;
    }

    private Map<String, Object> renderObject(
            String typeName,
            String path,
            List<GraphqlSelection.FieldSelection> selections,
            Row row,
            GraphqlReadPlan readPlan,
            GraphqlPlan plan
    ) {
        GraphqlObjectType type = requireType(typeName);
        Map<String, Object> result = new LinkedHashMap<>();
        for (GraphqlSelection.FieldSelection selection : selections) {
            if ("__typename".equals(selection.name())) {
                result.put(selection.responseKey(), typeName);
                continue;
            }
            GraphqlFieldDescriptor field = requireField(type, selection.name());
            if (field.kind() == GraphqlFieldDescriptor.FieldKind.SCALAR) {
                if (field.computedExpression().present()) {
                    throw unsupported("computed field '" + typeName + "." + field.name()
                            + "' needs a generated SQL expression");
                }
                result.put(selection.responseKey(), jsonValue(row.value(field.columnName())));
                continue;
            }
            result.put(selection.responseKey(), readRelation(
                    path + "." + field.name(), selection, row, readPlan, plan));
        }
        return result;
    }

    private Object readRelation(
            String path,
            GraphqlSelection.FieldSelection selection,
            Row parent,
            GraphqlReadPlan readPlan,
            GraphqlPlan plan
    ) {
        GraphqlReadPlan.RelationRead read = requireRelationRead(readPlan, path);
        Object localValue = parent.value(read.localColumnName());
        if (localValue == null) {
            return read.cardinality() == GraphqlFieldDescriptor.RelationCardinality.ONE
                    ? null : List.of();
        }
        GraphqlObjectType targetType = requireType(read.targetTypeName());
        GraphqlTableDescriptor targetDescriptor = requireTable(targetType);
        RuntimeTable table = RuntimeTable.fromQualifiedName(read.targetTableName());
        List<String> selectedColumns = read.targetColumnNames();
        Condition relationCondition = table.column(read.targetColumnName(), sqlType(localValue)).eq(localValue);
        for (GraphqlSelection.RelationArgument argument : read.arguments()) {
            if (argument.kind() != GraphqlRelationArgumentDescriptor.RelationArgumentKind.INT_EQUALS
                    || argument.filterHopCount() != 0) {
                continue;
            }
            relationCondition = relationCondition.and(
                    table.column(argument.columnName(), SQLType.BIGINT).eq(argument.intValue()));
        }
        SelectBuilder query = select(columns(table, selectedColumns)).from(table).where(relationCondition);
        int limit = read.cardinality() == GraphqlFieldDescriptor.RelationCardinality.ONE
                ? 1
                : relationLimit(selection, read);
        if (read.cardinality() == GraphqlFieldDescriptor.RelationCardinality.MANY
                || !read.sortPaths().isEmpty()) {
            applyRelationOrdering(query, table, read, targetDescriptor.primaryKeyColumnName());
        }
        query.limit(limit);
        plan.addReadStep(path, query.render(jdbc.dialect()).sql());
        List<Row> rows = jdbc.fetch(query, resultSet -> row(resultSet, selectedColumns));

        if (read.cardinality() == GraphqlFieldDescriptor.RelationCardinality.ONE) {
            return rows.isEmpty() ? null : renderObject(
                    targetType.name(), path, selection.selections(), rows.getFirst(), readPlan, plan);
        }
        List<Object> values = new ArrayList<>();
        for (Row child : rows) {
            values.add(renderObject(targetType.name(), path, selection.selections(), child, readPlan, plan));
        }
        return values;
    }

    private static int relationLimit(
            GraphqlSelection.FieldSelection selection,
            GraphqlReadPlan.RelationRead read
    ) {
        if (selection.relationConnectionSelection().selected()) {
            throw unsupported("Relay relation connection '" + read.fieldName()
                    + "' is not implemented by the generic JDBC executor yet");
        }
        return read.capabilities().maxPageSize();
    }

    private static void applyRootOrdering(
            SelectBuilder query,
            RuntimeTable table,
            GraphqlReadPlan.RootRead read
    ) {
        if (read.orderBy().isEmpty()) {
            if (read.cardinality() == GraphqlRootField.ResultCardinality.MANY) {
                GraphqlRootField.RootCursorOrdering ordering = read.cursorOrdering();
                Column<Object> column = table.column(ordering.columnName(), SQLType.UNKNOWN);
                SortField field = ordering.direction() == GraphqlRootField.RootCursorDirection.ASC
                        ? column.asc() : column.desc();
                if (ordering.tieBreakerColumnName().equals(ordering.columnName())) {
                    query.orderBy(field);
                } else {
                    Column<Object> tieBreaker = table.column(ordering.tieBreakerColumnName(), SQLType.UNKNOWN);
                    query.orderBy(field, ordering.direction() == GraphqlRootField.RootCursorDirection.ASC
                            ? tieBreaker.asc() : tieBreaker.desc());
                }
            }
            return;
        }
        List<SortField> fields = new ArrayList<>();
        Set<String> orderedColumns = new LinkedHashSet<>();
        for (GraphqlSelection.RootOrder order : read.orderBy()) {
            if (order.sortHopCount() != 0) {
                throw unsupported("relation-hop root ordering '" + order.name() + "'");
            }
            Column<Object> column = table.column(order.columnName(), SQLType.UNKNOWN);
            SortField field = order.direction() == GraphqlRootField.RootCursorDirection.ASC
                    ? column.asc() : column.desc();
            fields.add(order.nullOrdering() == GraphqlRootField.RootNullOrdering.NULLS_FIRST
                    ? field.nullsFirst() : field.nullsLast());
            orderedColumns.add(order.columnName());
            if (!order.tieBreakerColumnName().isBlank()
                    && orderedColumns.add(order.tieBreakerColumnName())) {
                Column<Object> tieBreaker = table.column(order.tieBreakerColumnName(), SQLType.UNKNOWN);
                fields.add(order.direction() == GraphqlRootField.RootCursorDirection.ASC
                        ? tieBreaker.asc() : tieBreaker.desc());
            }
        }
        query.orderBy(fields.toArray(SortField[]::new));
    }

    private static void applyRelationOrdering(
            SelectBuilder query,
            RuntimeTable table,
            GraphqlReadPlan.RelationRead read,
            String fallbackColumn
    ) {
        if (read.sortPaths().isEmpty()) {
            if (fallbackColumn == null || fallbackColumn.isBlank()) {
                throw unsupported("unordered to-many relation '" + read.fieldName()
                        + "' because its target has no single primary key or declared sort path");
            }
            query.orderBy(table.column(fallbackColumn, SQLType.UNKNOWN).asc());
            return;
        }
        GraphqlFieldDescriptor.RelationSortPath ordering = read.sortPaths().getFirst();
        if (ordering.sortHopCount() != 0) {
            throw unsupported("relation-hop ordering '" + ordering.name() + "'");
        }
        Column<Object> column = table.column(ordering.columnName(), SQLType.UNKNOWN);
        SortField field = ordering.direction() == GraphqlFieldDescriptor.RelationSortDirection.ASC
                ? column.asc() : column.desc();
        if (ordering.tieBreakerColumnName().isBlank()
                || ordering.tieBreakerColumnName().equals(ordering.columnName())) {
            query.orderBy(field);
        } else {
            Column<Object> tieBreaker = table.column(ordering.tieBreakerColumnName(), SQLType.UNKNOWN);
            query.orderBy(field, ordering.direction() == GraphqlFieldDescriptor.RelationSortDirection.ASC
                    ? tieBreaker.asc() : tieBreaker.desc());
        }
    }

    private static Condition generatedCondition(
            RuntimeTable table,
            GraphqlSelection.GeneratedRootFilter filter
    ) {
        if (filter.kind() != GraphqlSelection.GeneratedRootFilterKind.SCALAR) {
            Condition combined = null;
            for (GraphqlSelection.GeneratedRootFilter child : filter.children()) {
                Condition next = generatedCondition(table, child);
                combined = combined == null ? next
                        : filter.kind() == GraphqlSelection.GeneratedRootFilterKind.OR
                        ? combined.or(next) : combined.and(next);
            }
            if (combined == null) {
                combined = Condition.of("1 = 1");
            }
            return filter.kind() == GraphqlSelection.GeneratedRootFilterKind.NOT
                    ? combined.not() : combined;
        }
        if (filter.filterHopCount() != 0) {
            throw unsupported("relation-hop filter '" + filter.fieldName() + "'");
        }
        SQLType type = sqlType(filter.scalarType());
        Column<Object> column = table.column(filter.columnName(), type);
        List<Object> values = filter.values().stream().map(GenericJdbcGraphqlDataModel::filterValue).toList();
        Object value = values.isEmpty() ? null : values.getFirst();
        return switch (filter.operator()) {
            case EQ -> column.eq(value);
            case NEQ -> column.ne(value);
            case IN -> column.in(values);
            case IS_NULL -> Boolean.TRUE.equals(value) ? column.isNull() : column.isNotNull();
            case LT -> column.lt(value);
            case LTE -> column.le(value);
            case GT -> column.gt(value);
            case GTE -> column.ge(value);
            case CONTAINS -> column.like("%" + value + "%");
            case STARTS_WITH -> column.like(value + "%");
            case ENDS_WITH -> column.like("%" + value);
        };
    }

    private static Object filterValue(GraphqlSelection.GeneratedRootFilterValue value) {
        if (value.nullValue()) {
            return null;
        }
        return switch (value.scalarType()) {
            case "Int", "ID" -> value.intValue();
            case "Boolean" -> value.booleanValue();
            default -> value.stringValue();
        };
    }

    private static Condition and(Condition left, Condition right) {
        return left == null ? right : left.and(right);
    }

    private static List<String> distinct(List<String> columns, String requiredColumn) {
        Set<String> values = new LinkedHashSet<>(columns);
        if (requiredColumn != null && !requiredColumn.isBlank()) values.add(requiredColumn);
        return List.copyOf(values);
    }

    private static Column<?>[] columns(RuntimeTable table, List<String> names) {
        return names.stream().map(name -> table.column(name, SQLType.UNKNOWN)).toArray(Column<?>[]::new);
    }

    private static Row row(ResultSet resultSet, List<String> columns) throws SQLException {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            values.put(columns.get(index), resultSet.getObject(index + 1));
        }
        return new Row(values);
    }

    private GraphqlObjectType requireType(String name) {
        GraphqlObjectType type = schema.type(name);
        if (type == null) {
            throw new IllegalStateException("GraphQL type '" + name + "' has no schema descriptor");
        }
        return type;
    }

    private GraphqlTableDescriptor requireTable(GraphqlObjectType type) {
        GraphqlTableDescriptor table = schema.table(type.tableName());
        if (table == null) {
            throw new IllegalStateException("GraphQL type '" + type.name() + "' has no table binding");
        }
        return table;
    }

    private static GraphqlReadPlan.RelationRead requireRelationRead(GraphqlReadPlan plan, String path) {
        GraphqlReadPlan.RelationRead read = plan.relationRead(path);
        if (read == null) {
            throw new IllegalStateException("GraphQL read plan has no relation step '" + path + "'");
        }
        return read;
    }

    private static GraphqlFieldDescriptor requireField(GraphqlObjectType type, String name) {
        GraphqlFieldDescriptor field = type.field(name);
        if (field == null) {
            throw new IllegalStateException("GraphQL field '" + type.name() + "." + name + "' has no descriptor");
        }
        return field;
    }

    private static Object jsonValue(Object value) {
        if (value instanceof TemporalAccessor || value instanceof UUID) {
            return value.toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros();
        }
        return value;
    }

    private static SQLType sqlType(Object value) {
        if (value instanceof Boolean) return SQLType.BOOLEAN;
        if (value instanceof Byte) return SQLType.TINYINT;
        if (value instanceof Short) return SQLType.SMALLINT;
        if (value instanceof Integer) return SQLType.INTEGER;
        if (value instanceof Long) return SQLType.BIGINT;
        if (value instanceof Float) return SQLType.REAL;
        if (value instanceof Double) return SQLType.DOUBLE;
        if (value instanceof BigDecimal) return SQLType.DECIMAL;
        if (value instanceof UUID) return SQLType.UUID;
        return SQLType.VARCHAR;
    }

    private static SQLType sqlType(String graphqlType) {
        return switch (graphqlType) {
            case "Int", "ID" -> SQLType.BIGINT;
            case "Boolean" -> SQLType.BOOLEAN;
            case "Float" -> SQLType.DOUBLE;
            default -> SQLType.VARCHAR;
        };
    }

    private static SQLType contextSqlType(GraphqlRootField.RootContextFilter filter) {
        return switch (filter.valueType()) {
            case BOOLEAN -> SQLType.BOOLEAN;
            case ID -> SQLType.BIGINT;
            case STRING -> SQLType.VARCHAR;
        };
    }

    private void validateSupportedSelection(GraphqlSelection selection) {
        if (selection.rootConnectionSelection().selected()
                && (selection.rootPagination().last() != null
                || !selection.rootPagination().after().isEmpty()
                || !selection.rootPagination().before().isEmpty())) {
            throw unsupported("Relay cursors and backward root pagination");
        }
        validateFields(selection.rootTypeName(), selection.fields(),
                selection.rootCardinality() == GraphqlRootField.ResultCardinality.MANY);
    }

    private void validateFields(
            String typeName,
            List<GraphqlSelection.FieldSelection> selections,
            boolean collectionParent
    ) {
        GraphqlObjectType type = requireType(typeName);
        for (GraphqlSelection.FieldSelection selection : selections) {
            if ("__typename".equals(selection.name())) continue;
            GraphqlFieldDescriptor field = requireField(type, selection.name());
            if (field.kind() == GraphqlFieldDescriptor.FieldKind.SCALAR) {
                if (field.computedExpression().present()) {
                    throw unsupported("computed field '" + typeName + "." + field.name()
                            + "' until its SQL expression is generated");
                }
                continue;
            }
            if (collectionParent) {
                throw unsupported("relation '" + typeName + "." + field.name()
                        + "' on a collection root until batched relation execution is available");
            }
            if (selection.relationConnectionSelection().selected()) {
                throw unsupported("Relay relation connection '" + typeName + "." + field.name() + "'");
            }
            for (GraphqlSelection.RelationArgument argument : selection.relationArguments()) {
                if (argument.kind() == GraphqlRelationArgumentDescriptor.RelationArgumentKind.INT_EQUALS
                        && argument.filterHopCount() != 0) {
                    throw unsupported("relation-hop filter '" + typeName + "." + field.name()
                            + "." + argument.argumentName() + "'");
                }
            }
            validateFields(field.targetTypeName(), selection.selections(),
                    field.relationCardinality() == GraphqlFieldDescriptor.RelationCardinality.MANY);
        }
    }

    private static GraphqlException unsupported(String detail) {
        return new GraphqlException("generic GraphQL JDBC execution does not support " + detail);
    }

    private record Row(Map<String, Object> values) {
        Object value(String column) {
            return values.get(column);
        }
    }

    private static final class RuntimeTable extends Table<Map<String, Object>> {
        RuntimeTable(String name, String schema) {
            super(identifier(name, "table"), identifier(schema == null ? "" : schema, "schema"));
        }

        static RuntimeTable fromQualifiedName(String name) {
            int split = name.lastIndexOf('.');
            return split < 0
                    ? new RuntimeTable(name, "")
                    : new RuntimeTable(name.substring(split + 1), name.substring(0, split));
        }

        Column<Object> column(String name, SQLType type) {
            return column(identifier(name, "column"), type, Nullability.NULLABLE);
        }

        private static String identifier(String value, String label) {
            if (value.isBlank() || value.matches("[A-Za-z_][A-Za-z0-9_$]*")) return value;
            throw new IllegalArgumentException("unsafe " + label + " identifier '" + value + "'");
        }
    }
}
