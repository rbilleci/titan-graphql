package io.titan.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.graphql.codegen.TitanGraphqlRoutineSourceGenerator;
import io.titan.graphql.model.TitanGraphqlContextFilterDocument;
import io.titan.graphql.model.TitanGraphqlFieldDocument;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlRelationDocument;
import io.titan.graphql.model.TitanGraphqlRootDocument;
import io.titan.graphql.model.TitanGraphqlTypeDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.sqlmode.TitanGraphqlRoutineInvoker;
import io.titan.graphql.validation.TitanGraphqlModelDocumentValidator;
import io.titan.graphql.validation.TitanGraphqlValidationReport;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/** Generic GraphQL data model backed by model-generated, Titan-compiled database routines. */
public final class TitanCompiledGraphqlDataModel implements GraphqlDataModel {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GraphqlSchema schema;
    private final DataSource dataSource;
    private final TitanGraphqlRoutineInvoker invoker;
    private final String semanticHash;
    private final Map<String, TitanGraphqlRootDocument> roots = new LinkedHashMap<>();
    private final Map<String, TitanGraphqlTypeDocument> types = new LinkedHashMap<>();
    private final Map<String, TitanGraphqlContextFilterDocument> contextFilters = new LinkedHashMap<>();

    public TitanCompiledGraphqlDataModel(
            TitanGraphqlModelDocument document,
            DataSource dataSource,
            TitanGraphqlGap005ArtifactMetadata packageMetadata
    ) {
        Objects.requireNonNull(document, "document");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);
        if (report.blocksDeployment()) {
            throw new IllegalArgumentException("GraphQL model document has " + report.errorCount()
                    + " deployment-blocking validation error(s)");
        }
        this.schema = ProjectionGraphqlAdapter.adapt(
                TitanGraphqlProjectionModelAdapter.adapt(document, new GraphqlPolicy()));
        this.invoker = new TitanGraphqlRoutineInvoker(packageMetadata);
        this.semanticHash = TitanGraphqlModelDocumentJson.semanticHash(document);
        document.roots().forEach(root -> roots.put(root.name(), root));
        document.types().forEach(type -> types.put(type.name(), type));
        document.contextFilters().forEach(filter -> contextFilters.put(filter.name(), filter));
    }

    @Override
    public GraphqlSchema schema() {
        return schema;
    }

    @Override
    public GraphqlExecution execute(GraphqlSelection selection, GraphqlRequestContext context) {
        GraphqlReadPlan plan = GraphqlReadPlanner.plan(schema, selection);
        validateSupported(selection);
        GraphqlPlan observable = new GraphqlPlan();
        try (Connection connection = dataSource.getConnection()) {
            invoker.attest(connection, semanticHash);
            List<Row> roots = readRoot(connection, selection, plan.rootRead(), context, observable);
            Map<RelationCacheKey, List<Row>> relationCache = new LinkedHashMap<>();
            Object result;
            if (selection.rootCardinality() == GraphqlRootField.ResultCardinality.ONE) {
                result = roots.isEmpty() ? null : renderObject(connection, selection.rootTypeName(),
                        selection.rootFieldName(), selection.fields(), roots.getFirst(), observable, relationCache);
            } else {
                result = renderConnection(
                        connection, selection, roots, plan, context, observable, relationCache);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(selection.rootResponseKey(), result);
            return new GraphqlExecution(JSON.writeValueAsString(Map.of("data", data)), observable);
        } catch (SQLException ex) {
            throw new TitanCompiledGraphqlExecutionException(
                    "Titan-compiled GraphQL read failed (" + ex.getMessage() + ")", ex);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Titan-compiled GraphQL result could not be serialized", ex);
        }
    }

    private List<Row> readRoot(
            Connection connection,
            GraphqlSelection selection,
            GraphqlReadPlan.RootRead read,
            GraphqlRequestContext context,
            GraphqlPlan observable
    ) throws SQLException {
        TitanGraphqlRootDocument root = requireRoot(selection.rootFieldName());
        String suffix = TitanGraphqlRoutineSourceGenerator.javaTypeName(root.name());
        String method;
        List<Object> parameters;
        if (root.operation() == TitanGraphqlRootDocument.RootDocumentOperation.POINT) {
            method = "readRoot" + suffix;
            parameters = new ArrayList<>();
            List<TitanGraphqlRootDocument.RootDocumentArgument> keys = pointKeyArguments(root);
            for (TitanGraphqlRootDocument.RootDocumentArgument key : keys) {
                Object value = selection.rootKeyValues().get(key.name());
                if (value == null && keys.size() == 1 && selection.rootKeyValues().isEmpty()) {
                    value = selection.rootId();
                }
                if (value == null) {
                    throw new GraphqlException("missing point key argument '" + key.name() + "'");
                }
                parameters.add(coerce(value, key.type()));
            }
            parameters = List.copyOf(parameters);
        } else {
            boolean backward = read.cursorWindow().direction()
                    == GraphqlReadPlan.RootCursorWindowDirection.BACKWARD;
            GraphqlSelection.RootOrder order = selection.rootOrderBy().isEmpty()
                    ? null : selection.rootOrderBy().getFirst();
            String orderSuffix = order == null ? "" : "Order"
                    + TitanGraphqlRoutineSourceGenerator.javaTypeName(order.name())
                    + (order.direction() == GraphqlRootField.RootCursorDirection.ASC ? "Asc" : "Desc");
            method = "readRoot" + suffix + orderSuffix + (backward ? "Backward" : "Forward");
            parameters = rootParameters(root, read, context, true, order);
        }
        observable.addReadStep(read.stepName(), "TITAN PACKAGE " + method + "(?)");
        return rows(invoker.read(connection, method, parameters));
    }

    private static List<TitanGraphqlRootDocument.RootDocumentArgument> pointKeyArguments(
            TitanGraphqlRootDocument root
    ) {
        if (root.argument() != null && !root.arguments().isEmpty()) {
            throw unsupported("point root '" + root.name()
                    + "' declaring both argument and arguments");
        }
        List<TitanGraphqlRootDocument.RootDocumentArgument> keys = root.argument() == null
                ? root.arguments() : List.of(root.argument());
        if (keys.isEmpty()) throw unsupported("point root '" + root.name() + "' without key arguments");
        return keys.stream()
                .sorted(Comparator.comparing(TitanGraphqlRootDocument.RootDocumentArgument::name))
                .toList();
    }

    private Object renderConnection(
            Connection connection,
            GraphqlSelection selection,
            List<Row> fetched,
            GraphqlReadPlan plan,
            GraphqlRequestContext context,
            GraphqlPlan observable,
            Map<RelationCacheKey, List<Row>> relationCache
    ) throws SQLException {
        GraphqlReadPlan.RootRead read = plan.rootRead();
        int requested = read.cursorWindow().requestedRowCount();
        boolean overflow = fetched.size() > requested;
        List<Row> page = new ArrayList<>(fetched.subList(0, Math.min(requested, fetched.size())));
        boolean backward = read.cursorWindow().direction()
                == GraphqlReadPlan.RootCursorWindowDirection.BACKWARD;
        if (backward) Collections.reverse(page);
        prefetchDirectRelations(connection, selection.rootTypeName(), selection.rootFieldName(),
                selection.fields(), page, observable, relationCache);

        boolean hasNextPage = backward ? read.cursorWindow().beforeCursor() != null : overflow;
        boolean hasPreviousPage = backward ? overflow : read.cursorWindow().afterCursor() != null;
        GraphqlSelection.RootConnectionSelection connectionSelection = selection.rootConnectionSelection();
        Map<String, Object> value = new LinkedHashMap<>();
        if (connectionSelection.edges()) {
            List<Object> edges = new ArrayList<>();
            for (Row row : page) {
                Map<String, Object> edge = new LinkedHashMap<>();
                if (connectionSelection.edgeCursor()) edge.put("cursor", rootCursor(selection, read, row));
                if (connectionSelection.edgeNode()) edge.put("node", renderObject(
                        connection, selection.rootTypeName(), selection.rootFieldName(), selection.fields(),
                        row, observable, relationCache));
                edges.add(edge);
            }
            value.put("edges", edges);
        }
        if (connectionSelection.totalCount()) {
            String method = "countRoot" + TitanGraphqlRoutineSourceGenerator.javaTypeName(
                    selection.rootFieldName());
            List<Row> countRows = rows(invoker.read(connection, method,
                    rootParameters(requireRoot(selection.rootFieldName()), read, context, false, null)));
            if (countRows.size() != 1 || !(countRows.getFirst().value("total_count") instanceof Number count)) {
                throw new SQLException("generated count carrier '" + method + "' returned an invalid row");
            }
            observable.addReadStep(read.stepName() + ".totalCount", "TITAN PACKAGE " + method + "(?)");
            value.put("totalCount", count.longValue());
        }
        if (connectionSelection.pageInfo()) {
            Map<String, Object> pageInfo = new LinkedHashMap<>();
            for (String field : connectionSelection.pageInfoFields()) {
                switch (field) {
                    case "hasNextPage" -> pageInfo.put(field, hasNextPage);
                    case "hasPreviousPage" -> pageInfo.put(field, hasPreviousPage);
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

    private List<Object> rootParameters(
            TitanGraphqlRootDocument root,
            GraphqlReadPlan.RootRead read,
            GraphqlRequestContext context,
            boolean cursors,
            GraphqlSelection.RootOrder order
    ) {
        List<Object> parameters = new ArrayList<>();
        if (cursors) {
            String cursorBinding = order == null
                    ? root.pagination().cursor().column() : order.columnName();
            String tieBreakerBinding = order == null
                    ? root.pagination().cursor().tieBreaker() : order.tieBreakerColumnName();
            if (tieBreakerBinding == null || tieBreakerBinding.isBlank()) tieBreakerBinding = cursorBinding;
            addCursorParameters(parameters, read.cursorWindow().afterCursor(),
                    scalarType(root.type(), cursorBinding), tieBreakerBinding,
                    scalarType(root.type(), tieBreakerBinding), cursorBinding);
            addCursorParameters(parameters, read.cursorWindow().beforeCursor(),
                    scalarType(root.type(), cursorBinding), tieBreakerBinding,
                    scalarType(root.type(), tieBreakerBinding), cursorBinding);
        }
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : root.arguments()) {
            if (argument.kind() != TitanGraphqlRootDocument.RootDocumentArgumentKind.EQUALS
                    || argument.hops() != 0) continue;
            Long value = null;
            for (GraphqlSelection.RootFilter filter : read.filters()) {
                if (argument.name().equals(filter.argumentName())) value = filter.value();
            }
            addOptional(parameters, value, argument.type());
        }
        for (String contextFilterName : root.contextFilters()) {
            TitanGraphqlContextFilterDocument filter = requireContextFilter(contextFilterName);
            boolean apply = context.contextFilterEnabled(filter.name());
            Object value = context.contextValue(filter.contextKey());
            if (apply && value == null && filter.failClosed()) {
                throw new GraphqlException("required request context value '" + filter.contextKey()
                        + "' is missing for filter '" + filter.name() + "'");
            }
            String type = filter.operator() == TitanGraphqlContextFilterDocument.Operator.BOOLEAN_EQUALS
                    ? "Boolean" : scalarType(root.type(), filter.column());
            parameters.add(apply);
            addOptional(parameters, value, type);
        }
        if (cursors) parameters.add(read.cursorWindow().fetchRowCount());
        return List.copyOf(parameters);
    }

    private static void addCursorParameters(
            List<Object> parameters,
            GraphqlCursorCodec.CursorPayload cursor,
            String cursorType,
            String tieBreakerBinding,
            String tieBreakerType,
            String cursorBinding
    ) {
        boolean present = cursor != null;
        parameters.add(present);
        Object value = present ? coerce(cursor.value(), cursorType) : defaultValue(cursorType);
        parameters.add(value);
        if (!tieBreakerBinding.equals(cursorBinding)) {
            parameters.add(value);
            parameters.add(present
                    ? coerce(cursor.tieBreakerValue(), tieBreakerType) : defaultValue(tieBreakerType));
        }
    }

    private static void addOptional(List<Object> parameters, Object value, String type) {
        parameters.add(value != null);
        parameters.add(value == null ? defaultValue(type) : coerce(value, type));
    }

    private Map<String, Object> renderObject(
            Connection connection,
            String typeName,
            String path,
            List<GraphqlSelection.FieldSelection> selections,
            Row row,
            GraphqlPlan observable,
            Map<RelationCacheKey, List<Row>> relationCache
    ) throws SQLException {
        TitanGraphqlTypeDocument type = requireType(typeName);
        Map<String, Object> result = new LinkedHashMap<>();
        for (GraphqlSelection.FieldSelection selection : selections) {
            if ("__typename".equals(selection.name())) {
                result.put(selection.responseKey(), typeName);
                continue;
            }
            TitanGraphqlFieldDocument scalar = field(type, selection.name());
            if (scalar != null) {
                result.put(selection.responseKey(), row.value(
                        TitanGraphqlRoutineSourceGenerator.sqlAlias(scalar.name())));
                continue;
            }
            TitanGraphqlRelationDocument relation = relation(type, selection.name());
            Object localKey = row.value(TitanGraphqlRoutineSourceGenerator.hiddenRelationAlias(relation.name()));
            if (localKey == null) {
                result.put(selection.responseKey(), selection.relationConnectionSelection().selected()
                        ? renderRelationConnection(
                                connection,
                                relation.targetType(),
                                path + "." + selection.responseKey(),
                                selection,
                                relation,
                                List.of(),
                                observable,
                                relationCache
                        )
                        : relation.cardinality()
                                == TitanGraphqlRelationDocument.RelationDocumentCardinality.ONE ? null : List.of());
                continue;
            }
            String method = "readRelation" + TitanGraphqlRoutineSourceGenerator.javaTypeName(type.name())
                    + TitanGraphqlRoutineSourceGenerator.javaTypeName(relation.name());
            String relationPath = path + "." + selection.responseKey();
            List<Row> children = relationCache.get(new RelationCacheKey(relationPath, cacheKey(localKey)));
            if (children == null) {
                observable.addReadStep(relationPath, "TITAN PACKAGE " + method + "(?)");
                children = rows(invoker.read(
                        connection,
                        method,
                        relationParameters(selection, relation, List.of(localKey))
                ));
            }
            if (selection.relationConnectionSelection().selected()) {
                result.put(selection.responseKey(), renderRelationConnection(
                        connection,
                        relation.targetType(),
                        relationPath,
                        selection,
                        relation,
                        children,
                        observable,
                        relationCache
                ));
                continue;
            }
            if (relation.cardinality() == TitanGraphqlRelationDocument.RelationDocumentCardinality.ONE) {
                result.put(selection.responseKey(), children.isEmpty() ? null : renderObject(connection,
                        relation.targetType(), relationPath, selection.selections(),
                        children.getFirst(), observable, relationCache));
            } else {
                List<Object> values = new ArrayList<>();
                for (Row child : children) {
                    values.add(renderObject(connection, relation.targetType(), relationPath,
                            selection.selections(), child, observable, relationCache));
                }
                result.put(selection.responseKey(), values);
            }
        }
        return result;
    }

    private Object renderRelationConnection(
            Connection connection,
            String targetTypeName,
            String relationPath,
            GraphqlSelection.FieldSelection selection,
            TitanGraphqlRelationDocument relation,
            List<Row> children,
            GraphqlPlan observable,
            Map<RelationCacheKey, List<Row>> relationCache
    ) throws SQLException {
        if (relation.sortPaths().isEmpty()) {
            throw unsupported("relation connection '" + relation.name() + "' without sort metadata");
        }
        TitanGraphqlRelationDocument.RelationDocumentSortPath ordering = relation.sortPaths().getFirst();
        if (ordering.hops() != 0) {
            throw unsupported("relation-hop ordering on connection '" + relation.name() + "'");
        }
        GraphqlCursorCodec.CursorPayload after = relationCursorArgument(
                selection, GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_AFTER);
        GraphqlCursorCodec.CursorPayload before = relationCursorArgument(
                selection, GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_BEFORE);
        boolean backward = selection.relationArguments().stream().anyMatch(argument ->
                argument.kind() == GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_LAST);
        List<Row> eligible = children.stream()
                .filter(row -> after == null || compareRelationCursor(targetTypeName, row, after, ordering) > 0)
                .filter(row -> before == null || compareRelationCursor(targetTypeName, row, before, ordering) < 0)
                .toList();
        int requested = selection.relationConnectionSelection().pageSize();
        boolean overflow = eligible.size() > requested;
        List<Row> page;
        if (backward) {
            int from = Math.max(0, eligible.size() - requested);
            page = new ArrayList<>(eligible.subList(from, eligible.size()));
        } else {
            page = new ArrayList<>(eligible.subList(0, Math.min(requested, eligible.size())));
        }

        GraphqlSelection.RelationConnectionSelection connectionSelection =
                selection.relationConnectionSelection();
        Map<String, Object> value = new LinkedHashMap<>();
        if (connectionSelection.edges()) {
            List<Object> edges = new ArrayList<>();
            for (Row child : page) {
                Map<String, Object> edge = new LinkedHashMap<>();
                if (connectionSelection.edgeCursor()) {
                    edge.put("cursor", relationCursor(targetTypeName, child, ordering));
                }
                if (connectionSelection.edgeNode()) {
                    edge.put("node", renderObject(
                            connection,
                            targetTypeName,
                            relationPath,
                            selection.selections(),
                            child,
                            observable,
                            relationCache
                    ));
                }
                edges.add(edge);
            }
            value.put("edges", edges);
        }
        if (connectionSelection.totalCount()) {
            value.put("totalCount", children.size());
        }
        if (connectionSelection.pageInfo()) {
            Map<String, Object> pageInfo = new LinkedHashMap<>();
            for (String field : connectionSelection.pageInfoFields()) {
                switch (field) {
                    case "hasNextPage" -> pageInfo.put(field, backward ? before != null : overflow);
                    case "hasPreviousPage" -> pageInfo.put(field, backward ? overflow : after != null);
                    case "startCursor" -> pageInfo.put(field,
                            page.isEmpty() ? null : relationCursor(targetTypeName, page.getFirst(), ordering));
                    case "endCursor" -> pageInfo.put(field,
                            page.isEmpty() ? null : relationCursor(targetTypeName, page.getLast(), ordering));
                    default -> throw unsupported("pageInfo field '" + field + "'");
                }
            }
            value.put("pageInfo", pageInfo);
        }
        return value;
    }

    private static GraphqlCursorCodec.CursorPayload relationCursorArgument(
            GraphqlSelection.FieldSelection selection,
            GraphqlRelationArgumentDescriptor.RelationArgumentKind kind
    ) {
        return selection.relationArguments().stream()
                .filter(argument -> argument.kind() == kind)
                .map(GraphqlSelection.RelationArgument::cursorPayload)
                .findFirst()
                .orElse(null);
    }

    private int compareRelationCursor(
            String targetTypeName,
            Row row,
            GraphqlCursorCodec.CursorPayload cursor,
            TitanGraphqlRelationDocument.RelationDocumentSortPath ordering
    ) {
        int comparison = compareBinding(targetTypeName, row, ordering.column(), cursor.value());
        String tieBreaker = ordering.tieBreaker().isBlank() ? ordering.column() : ordering.tieBreaker();
        if (comparison == 0 && !tieBreaker.equals(ordering.column())) {
            comparison = compareBinding(targetTypeName, row, tieBreaker, cursor.tieBreakerValue());
        }
        return ordering.direction() == TitanGraphqlRelationDocument.RelationDocumentSortDirection.ASC
                ? comparison : -comparison;
    }

    private int compareBinding(String typeName, Row row, String binding, String cursorValue) {
        TitanGraphqlFieldDocument field = fieldForBinding(requireType(typeName), binding);
        Object value = row.value(TitanGraphqlRoutineSourceGenerator.sqlAlias(field.name()));
        return compareValues(value, coerce(cursorValue, field.type()));
    }

    private static int compareValues(Object left, Object right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        if (left instanceof Number || right instanceof Number) {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
        }
        if (left instanceof Boolean leftBoolean && right instanceof Boolean rightBoolean) {
            return leftBoolean.compareTo(rightBoolean);
        }
        return left.toString().compareTo(right.toString());
    }

    private String relationCursor(
            String targetTypeName,
            Row row,
            TitanGraphqlRelationDocument.RelationDocumentSortPath ordering
    ) {
        TitanGraphqlTypeDocument type = requireType(targetTypeName);
        TitanGraphqlFieldDocument orderedField = fieldForBinding(type, ordering.column());
        String tieBreaker = ordering.tieBreaker().isBlank() ? ordering.column() : ordering.tieBreaker();
        TitanGraphqlFieldDocument tieField = fieldForBinding(type, tieBreaker);
        Object value = row.value(TitanGraphqlRoutineSourceGenerator.sqlAlias(orderedField.name()));
        Object tieValue = row.value(TitanGraphqlRoutineSourceGenerator.sqlAlias(tieField.name()));
        return GraphqlCursorCodec.encode(GraphqlCursorCodec.payload(
                new GraphqlFieldDescriptor.RelationSortPath(
                        ordering.name(),
                        ordering.column(),
                        ordering.path().isBlank() ? ordering.column() : ordering.path(),
                        ordering.hops(),
                        ordering.direction() == TitanGraphqlRelationDocument.RelationDocumentSortDirection.ASC
                                ? GraphqlFieldDescriptor.RelationSortDirection.ASC
                                : GraphqlFieldDescriptor.RelationSortDirection.DESC,
                        tieBreaker
                ),
                String.valueOf(value),
                String.valueOf(tieValue)
        ));
    }

    private static TitanGraphqlFieldDocument fieldForBinding(
            TitanGraphqlTypeDocument type,
            String binding
    ) {
        return type.fields().stream()
                .filter(field -> binding.equals(field.name()) || binding.equals(field.column()))
                .findFirst()
                .orElseThrow(() -> unsupported("unmapped scalar column '" + type.name() + "." + binding + "'"));
    }

    private void prefetchDirectRelations(
            Connection connection,
            String ownerTypeName,
            String ownerPath,
            List<GraphqlSelection.FieldSelection> selections,
            List<Row> parents,
            GraphqlPlan observable,
            Map<RelationCacheKey, List<Row>> relationCache
    ) throws SQLException {
        TitanGraphqlTypeDocument owner = requireType(ownerTypeName);
        for (GraphqlSelection.FieldSelection selection : selections) {
            TitanGraphqlRelationDocument relation = relationOrNull(owner, selection.name());
            if (relation == null) continue;
            String relationPath = ownerPath + "." + selection.responseKey();
            Map<String, Object> keys = new LinkedHashMap<>();
            String localAlias = TitanGraphqlRoutineSourceGenerator.hiddenRelationAlias(relation.name());
            for (Row parent : parents) {
                Object key = parent.value(localAlias);
                if (key != null) keys.putIfAbsent(cacheKey(key), key);
            }
            for (String key : keys.keySet()) {
                relationCache.put(new RelationCacheKey(relationPath, key), new ArrayList<>());
            }
            List<Object> values = new ArrayList<>(keys.values());
            for (int offset = 0; offset < values.size(); offset += 64) {
                List<Object> chunk = new ArrayList<>(values.subList(offset, Math.min(offset + 64, values.size())));
                if (chunk.size() == 1) {
                    String method = relationMethod(owner, relation);
                    List<Row> children = rows(invoker.read(
                            connection,
                            method,
                            relationParameters(selection, relation, chunk)
                    ));
                    relationCache.put(new RelationCacheKey(relationPath, cacheKey(chunk.getFirst())), children);
                    observable.addReadStep(relationPath + ".batch", "TITAN PACKAGE " + method + "(?)");
                    continue;
                }
                int arity = batchArity(chunk.size());
                while (chunk.size() < arity) chunk.add(chunk.getLast());
                String method = relationMethod(owner, relation) + "Batch" + arity;
                List<Row> children = rows(invoker.read(
                        connection,
                        method,
                        relationParameters(selection, relation, chunk)
                ));
                for (Row child : children) {
                    Object parentKey = child.value("__titan_parent_key");
                    RelationCacheKey key = new RelationCacheKey(relationPath, cacheKey(parentKey));
                    List<Row> grouped = relationCache.get(key);
                    if (grouped == null) {
                        throw new SQLException("generated batch carrier '" + method
                                + "' returned an undeclared parent key '" + parentKey + "'");
                    }
                    grouped.add(child);
                }
                observable.addReadStep(relationPath + ".batch",
                        "TITAN PACKAGE " + method + "(" + arity + " parameters)");
            }
        }
    }

    private static List<Object> relationParameters(
            GraphqlSelection.FieldSelection selection,
            TitanGraphqlRelationDocument relation,
            List<Object> localKeys
    ) {
        List<Object> parameters = new ArrayList<>(localKeys);
        for (TitanGraphqlRelationDocument.RelationDocumentArgument modelArgument
                : relationFilterArguments(relation)) {
            if (modelArgument.hops() != 0) {
                throw unsupported("relation-hop filter argument '" + modelArgument.name()
                        + "' on relation '" + relation.name() + "'");
            }
            GraphqlSelection.RelationArgument selected = selection.relationArguments().stream()
                    .filter(argument -> argument.kind()
                            == GraphqlRelationArgumentDescriptor.RelationArgumentKind.INT_EQUALS)
                    .filter(argument -> argument.argumentName().equals(modelArgument.name()))
                    .findFirst()
                    .orElse(null);
            Object value = selected == null ? null : selected.intValue();
            addOptional(parameters, value, modelArgument.type());
        }
        return List.copyOf(parameters);
    }

    private static List<TitanGraphqlRelationDocument.RelationDocumentArgument> relationFilterArguments(
            TitanGraphqlRelationDocument relation
    ) {
        return relation.arguments().stream()
                .filter(argument -> argument.kind()
                        == TitanGraphqlRelationDocument.RelationDocumentArgumentKind.EQUALS)
                .sorted(Comparator.comparing(TitanGraphqlRelationDocument.RelationDocumentArgument::name))
                .toList();
    }

    private static String relationMethod(
            TitanGraphqlTypeDocument owner,
            TitanGraphqlRelationDocument relation
    ) {
        return "readRelation" + TitanGraphqlRoutineSourceGenerator.javaTypeName(owner.name())
                + TitanGraphqlRoutineSourceGenerator.javaTypeName(relation.name());
    }

    private static int batchArity(int size) {
        for (int arity : List.of(2, 4, 8, 16, 32, 64)) {
            if (size <= arity) return arity;
        }
        throw new IllegalArgumentException("relation batch exceeds generated maximum arity: " + size);
    }

    private static String cacheKey(Object value) {
        return value == null ? "<null>" : String.valueOf(value);
    }

    private static String rootCursor(
            GraphqlSelection selection,
            GraphqlReadPlan.RootRead read,
            Row row
    ) {
        if (!selection.rootOrderBy().isEmpty()) {
            GraphqlSelection.RootOrder ordering = selection.rootOrderBy().getFirst();
            Object value = row.value(TitanGraphqlRoutineSourceGenerator.sqlAlias(ordering.name()));
            Object tie = row.value(TitanGraphqlRoutineSourceGenerator.sqlAlias(
                    ordering.tieBreakerColumnName().equals(ordering.columnName())
                            ? ordering.name() : ordering.tieBreakerColumnName()));
            return GraphqlCursorCodec.encode(GraphqlCursorCodec.payload(
                    ordering, String.valueOf(value), String.valueOf(tie)));
        }
        GraphqlRootField.RootCursorOrdering ordering = read.cursorOrdering();
        Object value = row.value(TitanGraphqlRoutineSourceGenerator.sqlAlias(ordering.name()));
        Object tie = row.value(TitanGraphqlRoutineSourceGenerator.sqlAlias(
                ordering.tieBreakerColumnName().equals(ordering.columnName())
                        ? ordering.name() : ordering.tieBreakerColumnName()));
        return GraphqlCursorCodec.encode(GraphqlCursorCodec.payload(
                ordering, String.valueOf(value), String.valueOf(tie)));
    }

    private void validateSupported(GraphqlSelection selection) {
        TitanGraphqlRootDocument root = requireRoot(selection.rootFieldName());
        if (!selection.generatedRootFilters().isEmpty()) {
            throw unsupported("generated root filters until filter carriers are emitted");
        }
        if (selection.rootOrderBy().size() > 1) {
            throw unsupported("multiple custom root order paths");
        }
        if (!selection.rootOrderBy().isEmpty()
                && selection.rootOrderBy().getFirst().sortHopCount() != 0) {
            throw unsupported("relation-hop root ordering '"
                    + selection.rootOrderBy().getFirst().name() + "'");
        }
        boolean collection = selection.rootCardinality() == GraphqlRootField.ResultCardinality.MANY;
        validateFields(selection.rootTypeName(), selection.fields(), collection, collection);
    }

    private void validateFields(
            String typeName,
            List<GraphqlSelection.FieldSelection> selections,
            boolean collectionParent,
            boolean batchingAvailable
    ) {
        TitanGraphqlTypeDocument type = requireType(typeName);
        for (GraphqlSelection.FieldSelection selection : selections) {
            if ("__typename".equals(selection.name())) continue;
            TitanGraphqlFieldDocument field = field(type, selection.name());
            if (field != null) {
                if (!field.policies().isEmpty()) {
                    throw unsupported("protected field '" + typeName + "." + field.name()
                            + "' until policy-specific carriers are emitted");
                }
                continue;
            }
            TitanGraphqlRelationDocument relation = relation(type, selection.name());
            if (!relation.policies().isEmpty()) {
                throw unsupported("protected relation '" + typeName + "." + relation.name()
                        + "' until policy-specific carriers are emitted");
            }
            if (collectionParent && !batchingAvailable) {
                throw unsupported("relation '" + typeName + "." + relation.name()
                        + "' beneath a collection until a batched carrier is emitted");
            }
            for (GraphqlSelection.RelationArgument argument : selection.relationArguments()) {
                if (argument.kind() == GraphqlRelationArgumentDescriptor.RelationArgumentKind.INT_EQUALS
                        && argument.filterHopCount() != 0) {
                    throw unsupported("relation-hop filter argument '" + argument.argumentName()
                            + "' on relation '" + typeName + "." + relation.name() + "'");
                }
            }
            validateFields(relation.targetType(), selection.selections(),
                    collectionParent || relation.cardinality()
                            == TitanGraphqlRelationDocument.RelationDocumentCardinality.MANY,
                    false);
        }
    }

    private TitanGraphqlRootDocument requireRoot(String name) {
        TitanGraphqlRootDocument root = roots.get(name);
        if (root == null) throw new GraphqlException("unknown generated root '" + name + "'");
        return root;
    }

    private TitanGraphqlTypeDocument requireType(String name) {
        TitanGraphqlTypeDocument type = types.get(name);
        if (type == null) throw new GraphqlException("unknown generated type '" + name + "'");
        return type;
    }

    private TitanGraphqlContextFilterDocument requireContextFilter(String name) {
        TitanGraphqlContextFilterDocument filter = contextFilters.get(name);
        if (filter == null) throw new GraphqlException("unknown generated context filter '" + name + "'");
        return filter;
    }

    private static TitanGraphqlFieldDocument field(TitanGraphqlTypeDocument type, String name) {
        return type.fields().stream().filter(field -> field.name().equals(name)).findFirst().orElse(null);
    }

    private static TitanGraphqlRelationDocument relation(TitanGraphqlTypeDocument type, String name) {
        return type.relations().stream().filter(relation -> relation.name().equals(name)).findFirst()
                .orElseThrow(() -> new GraphqlException("unknown generated field '" + type.name() + "." + name + "'"));
    }

    private static TitanGraphqlRelationDocument relationOrNull(TitanGraphqlTypeDocument type, String name) {
        return type.relations().stream().filter(relation -> relation.name().equals(name)).findFirst().orElse(null);
    }

    private String scalarType(String typeName, String column) {
        TitanGraphqlFieldDocument field = requireType(typeName).fields().stream()
                .filter(candidate -> column.equals(candidate.column()) || column.equals(candidate.name()))
                .findFirst().orElseThrow(() -> unsupported("unmapped scalar column '" + typeName + "." + column + "'"));
        return field.type();
    }

    private static Object coerce(Object value, String graphqlType) {
        String type = graphqlType == null ? "" : graphqlType.replace("!", "").trim();
        return switch (type) {
            case "Int" -> value instanceof Number number ? number.intValue() : Integer.valueOf(value.toString());
            case "Long" -> value instanceof Number number ? number.longValue() : Long.valueOf(value.toString());
            case "Float" -> value instanceof Number number ? number.doubleValue() : Double.valueOf(value.toString());
            case "Boolean" -> value instanceof Boolean bool ? bool : Boolean.valueOf(value.toString());
            case "UUID" -> value instanceof java.util.UUID uuid
                    ? uuid : java.util.UUID.fromString(value.toString());
            case "String", "ID", "Date", "DateTime", "Timestamp" -> value.toString();
            default -> throw unsupported("GraphQL parameter type '" + graphqlType + "'");
        };
    }

    private static Object defaultValue(String graphqlType) {
        String type = graphqlType == null ? "" : graphqlType.replace("!", "").trim();
        return switch (type) {
            case "Int" -> 0;
            case "Long" -> 0L;
            case "Float" -> 0D;
            case "Boolean" -> false;
            case "UUID" -> new java.util.UUID(0L, 0L);
            case "String", "ID", "Date", "DateTime", "Timestamp" -> "";
            default -> throw unsupported("GraphQL parameter type '" + graphqlType + "'");
        };
    }

    private static List<Row> rows(List<Map<String, Object>> values) {
        return values.stream().map(Row::new).toList();
    }

    private static GraphqlException unsupported(String detail) {
        return new GraphqlException("Titan-compiled generic GraphQL execution does not support " + detail);
    }

    private record Row(Map<String, Object> values) {
        Object value(String name) {
            return values.get(name);
        }
    }

    private record RelationCacheKey(String path, String parentKey) {
    }
}
