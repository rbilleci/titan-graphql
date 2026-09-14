package io.titan.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.graphql.codegen.TitanGraphqlRoutineSourceGenerator;
import io.titan.graphql.model.TitanGraphqlContextFilterDocument;
import io.titan.graphql.model.TitanGraphqlFieldDocument;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlPolicyDocument;
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
    private final TitanGraphqlModelDocument document;
    private final DataSource dataSource;
    private final TitanGraphqlRoutineInvoker invoker;
    private final GraphqlMutationExecutor mutationExecutor;
    private final String semanticHash;
    private final Map<String, TitanGraphqlRootDocument> roots = new LinkedHashMap<>();
    private final Map<String, TitanGraphqlTypeDocument> types = new LinkedHashMap<>();
    private final Map<String, TitanGraphqlContextFilterDocument> contextFilters = new LinkedHashMap<>();
    private final Map<String, TitanGraphqlPolicyDocument> policies = new LinkedHashMap<>();

    public TitanCompiledGraphqlDataModel(
            TitanGraphqlModelDocument document,
            DataSource dataSource,
            TitanGraphqlGap005ArtifactMetadata packageMetadata
    ) {
        this(document, dataSource, packageMetadata, GraphqlApplicationMutationProvider.none());
    }

    public TitanCompiledGraphqlDataModel(
            TitanGraphqlModelDocument document,
            DataSource dataSource,
            TitanGraphqlGap005ArtifactMetadata packageMetadata,
            GraphqlApplicationMutationProvider mutationProvider
    ) {
        Objects.requireNonNull(document, "document");
        this.document = document;
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);
        if (report.blocksDeployment()) {
            throw new IllegalArgumentException("GraphQL model document has " + report.errorCount()
                    + " deployment-blocking validation error(s)");
        }
        GraphqlApplicationMutationProvider suppliedMutations = mutationProvider == null
                ? GraphqlApplicationMutationProvider.none() : mutationProvider;
        // Snapshot application configuration exactly once. Validation, schema publication, and
        // dispatch must observe the same registrations even if a custom provider is stateful.
        GraphqlApplicationMutationProvider mutations = GraphqlApplicationMutationProvider.of(
                suppliedMutations.descriptors(), suppliedMutations.handlers(), suppliedMutations.auditSink());
        GraphqlSchema readSchema = ProjectionGraphqlAdapter.adapt(
                TitanGraphqlProjectionModelAdapter.adapt(document));
        this.schema = new GraphqlSchema(
                readSchema.rootFields(), readSchema.types(), readSchema.tables(), mutations.descriptors());
        this.mutationExecutor = new GraphqlMutationExecutor(
                this.schema, mutations.handlers(), mutations.auditSink());
        this.invoker = new TitanGraphqlRoutineInvoker(packageMetadata);
        this.semanticHash = TitanGraphqlModelDocumentJson.semanticHash(document);
        document.roots().forEach(root -> roots.put(root.name(), root));
        document.types().forEach(type -> types.put(type.name(), type));
        document.contextFilters().forEach(filter -> contextFilters.put(filter.name(), filter));
        document.policies().forEach(policy -> policies.put(policy.name(), policy));
        document.roots().forEach(root ->
                TitanGraphqlPolicyCompiler.compile(root.policies(), this::requirePolicy));
        document.types().forEach(type ->
                TitanGraphqlPolicyCompiler.compile(type.policies(), this::requirePolicy));
    }

    @Override
    public GraphqlSchema schema() {
        return schema;
    }

    @Override
    public GraphqlExecution execute(GraphqlSelection selection, GraphqlRequestContext context) {
        GraphqlReadPlan plan = GraphqlReadPlanner.plan(schema, selection);
        validateSupported(selection, context);
        GraphqlPlan observable = new GraphqlPlan();
        try (Connection connection = dataSource.getConnection()) {
            invoker.attest(connection, semanticHash);
            List<Row> roots = readRoot(connection, selection, plan.rootRead(), context, observable);
            Map<RelationCacheKey, List<Row>> relationCache = new LinkedHashMap<>();
            Object result;
            if (selection.rootCardinality() == GraphqlRootField.ResultCardinality.ONE) {
                result = roots.isEmpty() ? null : renderObject(connection, selection.rootTypeName(),
                        selection.rootFieldName(), selection.fields(), roots.getFirst(), context,
                        observable, relationCache);
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

    @Override
    public GraphqlExecution executeMutation(
            GraphqlAst.AstOperation operation,
            GraphqlRequestContext context
    ) {
        return mutationExecutor.execute(operation, context);
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
            parameters = new ArrayList<>(projectionPolicyParameters(requireType(root.type()), context));
            if (!root.policies().isEmpty()) {
                parameters.add(policyDecision(root.policies(), context));
            }
            TitanGraphqlTypeDocument rootType = requireType(root.type());
            if (!rootType.policies().isEmpty()) {
                parameters.add(policyDecision(rootType.policies(), context));
            }
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
            GraphqlSelection.GeneratedRootFilter filter = simpleScalarRootFilter(
                    selection.generatedRootFilters());
            boolean hasFilterPlan = !selection.generatedRootFilters().isEmpty()
                    && (filter == null || filter.filterHopCount() != 0 || order != null);
            if (hasFilterPlan) filter = null;
            String orderSuffix = order == null ? "" : "Order"
                    + TitanGraphqlRoutineSourceGenerator.javaTypeName(order.name())
                    + (order.direction() == GraphqlRootField.RootCursorDirection.ASC ? "Asc" : "Desc");
            String filterSuffix = hasFilterPlan ? "FilterPlan" : filter == null ? ""
                    : TitanGraphqlRoutineSourceGenerator.filterMethodSuffix(
                            filter.fieldName(), filter.operator().name(), filterInArity(filter));
            method = "readRoot" + suffix + orderSuffix + filterSuffix
                    + (backward ? "Backward" : "Forward");
            parameters = hasFilterPlan
                    ? filterPlanRootParameters(root, selection.generatedRootFilters(), read, context, true, order)
                    : rootParameters(root, read, context, true, order, filter);
            List<Object> guardedParameters = new ArrayList<>(
                    projectionPolicyParameters(requireType(root.type()), context));
            guardedParameters.addAll(parameters);
            parameters = List.copyOf(guardedParameters);
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
                selection.fields(), page, context, observable, relationCache);

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
                        row, context, observable, relationCache));
                edges.add(edge);
            }
            value.put("edges", edges);
        }
        if (connectionSelection.totalCount()) {
            GraphqlSelection.GeneratedRootFilter filter = simpleScalarRootFilter(
                    selection.generatedRootFilters());
            boolean hasFilterPlan = !selection.generatedRootFilters().isEmpty()
                    && (filter == null || filter.filterHopCount() != 0);
            if (hasFilterPlan) filter = null;
            String method = "countRoot" + TitanGraphqlRoutineSourceGenerator.javaTypeName(
                    selection.rootFieldName()) + (hasFilterPlan ? "FilterPlan" : filter == null ? ""
                    : TitanGraphqlRoutineSourceGenerator.filterMethodSuffix(
                            filter.fieldName(), filter.operator().name(), filterInArity(filter)));
            List<Row> countRows = rows(invoker.read(connection, method,
                    hasFilterPlan
                            ? filterPlanRootParameters(requireRoot(selection.rootFieldName()),
                                    selection.generatedRootFilters(), read, context, false, null)
                            : rootParameters(requireRoot(selection.rootFieldName()), read, context,
                                    false, null, filter)));
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
            GraphqlSelection.RootOrder order,
            GraphqlSelection.GeneratedRootFilter generatedFilter
    ) {
        List<Object> parameters = new ArrayList<>();
        if (!root.policies().isEmpty()) {
            parameters.add(policyDecision(root.policies(), context));
        }
        TitanGraphqlTypeDocument rootType = requireType(root.type());
        if (!rootType.policies().isEmpty()) {
            parameters.add(policyDecision(rootType.policies(), context));
        }
        if (cursors) {
            String cursorBinding = order == null
                    ? root.pagination().cursor().column() : order.columnName();
            String tieBreakerBinding = order == null
                    ? root.pagination().cursor().tieBreaker() : order.tieBreakerColumnName();
            if (tieBreakerBinding == null || tieBreakerBinding.isBlank()) tieBreakerBinding = cursorBinding;
            addCursorParameters(parameters, read.cursorWindow().afterCursor(),
                    order == null ? scalarType(root.type(), cursorBinding) : rootOrderScalarType(root, order),
                    tieBreakerBinding,
                    scalarType(root.type(), tieBreakerBinding), cursorBinding);
            addCursorParameters(parameters, read.cursorWindow().beforeCursor(),
                    order == null ? scalarType(root.type(), cursorBinding) : rootOrderScalarType(root, order),
                    tieBreakerBinding,
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
        if (generatedFilter != null) {
            addGeneratedFilterParameters(parameters, generatedFilter);
        }
        if (cursors) parameters.add(read.cursorWindow().fetchRowCount());
        return List.copyOf(parameters);
    }

    private List<Object> filterPlanRootParameters(
            TitanGraphqlRootDocument root,
            List<GraphqlSelection.GeneratedRootFilter> filters,
            GraphqlReadPlan.RootRead read,
            GraphqlRequestContext context,
            boolean cursors,
            GraphqlSelection.RootOrder order
    ) {
        TitanGraphqlTypeDocument type = requireType(root.type());
        List<TitanGraphqlFilterLayout.Binding> bindings =
                TitanGraphqlFilterLayout.bindings(document, type, root);
        TitanGraphqlFilterPlan.Plan plan = TitanGraphqlFilterPlan.compile(filters, bindings);
        List<String> valueTypes = TitanGraphqlFilterLayout.valueTypes(bindings);
        List<Object> parameters = new ArrayList<>();
        for (int groupIndex = 0; groupIndex < TitanGraphqlFilterPlan.MAX_GROUPS; groupIndex++) {
            TitanGraphqlFilterPlan.Group group = groupIndex < plan.groups().size()
                    ? plan.groups().get(groupIndex) : null;
            parameters.add(group != null);
            for (int termIndex = 0; termIndex < TitanGraphqlFilterPlan.MAX_TERMS_PER_GROUP; termIndex++) {
                TitanGraphqlFilterPlan.Term term = group != null && termIndex < group.terms().size()
                        ? group.terms().get(termIndex) : null;
                parameters.add(term == null ? 0 : term.binding().selector());
                parameters.add(term != null && term.negated());
                parameters.add(term != null && term.value().nullValue());
                for (String valueType : valueTypes) {
                    parameters.add(filterSlotValue(term, valueType));
                }
            }
        }
        parameters.addAll(rootParameters(root, read, context, cursors, order, null));
        return List.copyOf(parameters);
    }

    private static Object filterSlotValue(TitanGraphqlFilterPlan.Term term, String valueType) {
        if (term == null) return defaultValue(valueType);
        String valueKind = TitanGraphqlFilterLayout.valueKind(valueType);
        if (term.binding().operator().equals("isnull")) {
            return valueKind.equals("boolean")
                    ? term.value().booleanValue() : defaultValue(valueType);
        }
        if (!valueKind.equals(TitanGraphqlFilterLayout.valueKind(term.binding().graphqlType()))) {
            return defaultValue(valueType);
        }
        if (term.value().nullValue()) return defaultValue(valueType);
        if (List.of("contains", "startswith", "endswith").contains(term.binding().operator())) {
            String escaped = escapeLike(term.value().stringValue());
            return switch (term.binding().operator()) {
                case "contains" -> "%" + escaped + "%";
                case "startswith" -> escaped + "%";
                case "endswith" -> "%" + escaped;
                default -> throw new IllegalStateException("unreachable filter operator");
            };
        }
        return generatedFilterValue(term.value());
    }

    private static void addGeneratedFilterParameters(
            List<Object> parameters,
            GraphqlSelection.GeneratedRootFilter filter
    ) {
        switch (filter.operator()) {
            case EQ, NEQ -> {
                GraphqlSelection.GeneratedRootFilterValue value = filter.values().getFirst();
                parameters.add(value.nullValue());
                parameters.add(value.nullValue()
                        ? defaultValue(filter.scalarType()) : generatedFilterValue(value));
            }
            case IS_NULL -> parameters.add(filter.values().getFirst().booleanValue());
            case LT, LTE, GT, GTE -> parameters.add(generatedFilterValue(filter.values().getFirst()));
            case CONTAINS, STARTS_WITH, ENDS_WITH -> {
                String escaped = escapeLike(filter.values().getFirst().stringValue());
                parameters.add(switch (filter.operator()) {
                    case CONTAINS -> "%" + escaped + "%";
                    case STARTS_WITH -> escaped + "%";
                    case ENDS_WITH -> "%" + escaped;
                    default -> throw new IllegalStateException("unreachable filter operator");
                });
            }
            case IN -> {
                int arity = filterInArity(filter);
                if (arity == 0) return;
                List<GraphqlSelection.GeneratedRootFilterValue> values = filter.values();
                for (int index = 0; index < arity; index++) {
                    parameters.add(generatedFilterValue(values.get(Math.min(index, values.size() - 1))));
                }
            }
        }
    }

    private static Object generatedFilterValue(GraphqlSelection.GeneratedRootFilterValue value) {
        if (value.nullValue()) return null;
        String type = value.scalarType().replace("!", "").trim();
        return switch (type) {
            case "Int" -> Math.toIntExact(value.intValue());
            case "Long" -> value.intValue();
            case "Boolean" -> value.booleanValue();
            case "Float" -> Double.valueOf(value.stringValue());
            case "String", "ID", "Date", "DateTime", "Timestamp" -> value.stringValue();
            case "UUID" -> java.util.UUID.fromString(value.stringValue());
            default -> throw unsupported("generated filter scalar type '" + value.scalarType() + "'");
        };
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static int filterInArity(GraphqlSelection.GeneratedRootFilter filter) {
        int size = filter.values().size();
        for (int arity : List.of(0, 1, 2, 4, 8, 16)) {
            if (size <= arity) return arity;
        }
        throw unsupported("generated IN filter '" + filter.fieldName() + "' with more than 16 values");
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
            GraphqlRequestContext context,
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
                                context,
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
                        relationParameters(selection, relation, List.of(localKey), context)
                ));
                prefetchDirectRelations(connection, relation.targetType(), relationPath,
                        selection.selections(), children, context, observable, relationCache);
            }
            if (selection.relationConnectionSelection().selected()) {
                result.put(selection.responseKey(), renderRelationConnection(
                        connection,
                        relation.targetType(),
                        relationPath,
                        selection,
                        relation,
                        children,
                        context,
                        observable,
                        relationCache
                ));
                continue;
            }
            if (relation.cardinality() == TitanGraphqlRelationDocument.RelationDocumentCardinality.ONE) {
                result.put(selection.responseKey(), children.isEmpty() ? null : renderObject(connection,
                        relation.targetType(), relationPath, selection.selections(),
                        children.getFirst(), context, observable, relationCache));
            } else {
                List<Object> values = new ArrayList<>();
                for (Row child : children) {
                    values.add(renderObject(connection, relation.targetType(), relationPath,
                            selection.selections(), child, context, observable, relationCache));
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
            GraphqlRequestContext context,
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
                            context,
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
            GraphqlRequestContext context,
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
            List<Row> descendants = new ArrayList<>();
            for (int offset = 0; offset < values.size(); offset += 64) {
                List<Object> chunk = new ArrayList<>(values.subList(offset, Math.min(offset + 64, values.size())));
                if (chunk.size() == 1) {
                    String method = relationMethod(owner, relation);
                    List<Row> children = rows(invoker.read(
                            connection,
                            method,
                            relationParameters(selection, relation, chunk, context)
                    ));
                    relationCache.put(new RelationCacheKey(relationPath, cacheKey(chunk.getFirst())), children);
                    descendants.addAll(children);
                    observable.addReadStep(relationPath + ".batch", "TITAN PACKAGE " + method + "(?)");
                    continue;
                }
                int arity = batchArity(chunk.size());
                while (chunk.size() < arity) chunk.add(chunk.getLast());
                String method = relationMethod(owner, relation) + "Batch" + arity;
                List<Row> children = rows(invoker.read(
                        connection,
                        method,
                        relationParameters(selection, relation, chunk, context)
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
                descendants.addAll(children);
                observable.addReadStep(relationPath + ".batch",
                        "TITAN PACKAGE " + method + "(" + arity + " parameters)");
            }
            prefetchDirectRelations(connection, relation.targetType(), relationPath,
                    selection.selections(), descendants, context, observable, relationCache);
        }
    }

    private List<Object> relationParameters(
            GraphqlSelection.FieldSelection selection,
            TitanGraphqlRelationDocument relation,
            List<Object> localKeys,
            GraphqlRequestContext context
    ) {
        List<Object> parameters = new ArrayList<>(
                projectionPolicyParameters(requireType(relation.targetType()), context));
        if (!relation.policies().isEmpty()) {
            parameters.add(policyDecision(relation.policies(), context));
        }
        TitanGraphqlTypeDocument target = requireType(relation.targetType());
        if (!target.policies().isEmpty()) {
            parameters.add(policyDecision(target.policies(), context));
        }
        parameters.addAll(localKeys);
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

    private List<Object> projectionPolicyParameters(
            TitanGraphqlTypeDocument type,
            GraphqlRequestContext context
    ) {
        List<Object> parameters = new ArrayList<>();
        for (TitanGraphqlFieldDocument field : type.fields()) {
            if (!field.policies().isEmpty()
                    && (field.computed() == null || field.computed().selectable())) {
                parameters.add(policyDecision(field.policies(), context));
            }
        }
        for (TitanGraphqlRelationDocument relation : type.relations()) {
            if (!relation.policies().isEmpty()) {
                parameters.add(policyDecision(relation.policies(), context));
            }
        }
        return List.copyOf(parameters);
    }

    private boolean policyDecision(List<String> policyNames, GraphqlRequestContext context) {
        return TitanGraphqlPolicyCompiler.compile(policyNames, this::requirePolicy)
                .canRead(context.actorRole());
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

    private void validateSupported(GraphqlSelection selection, GraphqlRequestContext context) {
        TitanGraphqlRootDocument root = requireRoot(selection.rootFieldName());
        if (!root.policies().isEmpty() && !policyDecision(root.policies(), context)) {
            throw GraphqlException.authorization("root '" + root.name()
                    + "' is not authorized for actor role '" + context.actorRole() + "'");
        }
        selection.generatedRootFilters().forEach(filter -> validateGeneratedFilterTree(root.type(), filter));
        GraphqlSelection.GeneratedRootFilter simple = simpleScalarRootFilter(
                selection.generatedRootFilters());
        if (!selection.generatedRootFilters().isEmpty()
                && (simple == null || simple.filterHopCount() != 0 || !selection.rootOrderBy().isEmpty())) {
            TitanGraphqlFilterPlan.compile(selection.generatedRootFilters(),
                    TitanGraphqlFilterLayout.bindings(document, requireType(root.type()), root));
        }
        if (selection.rootOrderBy().size() > 1) {
            throw unsupported("multiple custom root order paths");
        }
        if (!selection.rootOrderBy().isEmpty()
                && selection.rootOrderBy().getFirst().sortHopCount() > 1) {
            throw unsupported("root ordering beyond one relation hop '"
                    + selection.rootOrderBy().getFirst().name() + "'");
        }
        boolean collection = selection.rootCardinality() == GraphqlRootField.ResultCardinality.MANY;
        validateFields(selection.rootTypeName(), selection.fields(), collection, collection);
    }

    private void validateGeneratedFilterTree(
            String ownerTypeName,
            GraphqlSelection.GeneratedRootFilter filter
    ) {
        if (filter.kind() == GraphqlSelection.GeneratedRootFilterKind.SCALAR) {
            validateGeneratedFilterPolicies(ownerTypeName, filter);
        } else {
            filter.children().forEach(child -> validateGeneratedFilterTree(ownerTypeName, child));
        }
    }

    private void validateGeneratedFilterPolicies(
            String ownerTypeName,
            GraphqlSelection.GeneratedRootFilter filter
    ) {
        TitanGraphqlTypeDocument current = requireType(ownerTypeName);
        String path = filter.filterPath().isBlank() ? filter.fieldName() : filter.filterPath();
        String[] segments = path.split("\\.", -1);
        for (int index = 0; index < segments.length; index++) {
            TitanGraphqlFieldDocument scalar = field(current, segments[index]);
            if (scalar != null) {
                if (!scalar.policies().isEmpty()) {
                    throw unsupported("filtering protected field '" + current.name() + "." + scalar.name() + "'");
                }
                if (index != segments.length - 1) {
                    throw unsupported("generated filter path '" + path + "' crossing a scalar field");
                }
                return;
            }
            TitanGraphqlRelationDocument relation = relationOrNull(current, segments[index]);
            if (relation == null) {
                return; // A root argument may bind a hidden local column rather than a GraphQL field.
            }
            if (!relation.policies().isEmpty()) {
                throw unsupported("filtering through protected relation '" + current.name()
                        + "." + relation.name() + "'");
            }
            current = requireType(relation.targetType());
        }
    }

    private static GraphqlSelection.GeneratedRootFilter simpleScalarRootFilter(
            List<GraphqlSelection.GeneratedRootFilter> roots
    ) {
        if (roots.isEmpty()) return null;
        List<GraphqlSelection.GeneratedRootFilter> scalars = new ArrayList<>();
        for (GraphqlSelection.GeneratedRootFilter root : roots) {
            if (!collectSimpleConjunction(root, scalars)) return null;
        }
        return scalars.size() == 1 ? scalars.getFirst() : null;
    }

    private static boolean collectSimpleConjunction(
            GraphqlSelection.GeneratedRootFilter filter,
            List<GraphqlSelection.GeneratedRootFilter> scalars
    ) {
        if (filter.kind() == GraphqlSelection.GeneratedRootFilterKind.SCALAR) {
            scalars.add(filter);
            return true;
        }
        if (filter.kind() != GraphqlSelection.GeneratedRootFilterKind.AND) return false;
        for (GraphqlSelection.GeneratedRootFilter child : filter.children()) {
            if (!collectSimpleConjunction(child, scalars)) return false;
        }
        return true;
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
                continue;
            }
            TitanGraphqlRelationDocument relation = relation(type, selection.name());
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
                    true);
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

    private TitanGraphqlPolicyDocument requirePolicy(String name) {
        TitanGraphqlPolicyDocument policy = policies.get(name);
        if (policy == null) throw new GraphqlException("unknown generated policy '" + name + "'");
        return policy;
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

    private String rootOrderScalarType(
            TitanGraphqlRootDocument root,
            GraphqlSelection.RootOrder order
    ) {
        if (order.sortHopCount() == 0) {
            return scalarType(root.type(), order.columnName());
        }
        if (order.sortHopCount() != 1) {
            throw unsupported("root ordering '" + order.name() + "' beyond one relation hop");
        }
        String[] path = order.sortPath().split("\\.", -1);
        if (path.length != 2) {
            throw unsupported("one-hop root ordering '" + order.name() + "' without relation.field metadata");
        }
        TitanGraphqlTypeDocument owner = requireType(root.type());
        TitanGraphqlRelationDocument relation = relation(owner, path[0]);
        TitanGraphqlFieldDocument field = field(requireType(relation.targetType()), path[1]);
        if (field == null) {
            throw unsupported("one-hop root ordering '" + order.name() + "' with unknown field '"
                    + order.sortPath() + "'");
        }
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
