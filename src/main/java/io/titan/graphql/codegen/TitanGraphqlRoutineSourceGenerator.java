package io.titan.graphql.codegen;

import io.titan.graphql.TitanGraphqlFilterLayout;
import io.titan.graphql.TitanGraphqlFilterPlan;
import io.titan.graphql.model.TitanGraphqlContextFilterDocument;
import io.titan.graphql.model.TitanGraphqlFieldDocument;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlRelationDocument;
import io.titan.graphql.model.TitanGraphqlRootDocument;
import io.titan.graphql.model.TitanGraphqlTypeDocument;
import io.titan.graphql.validation.TitanGraphqlModelDocumentValidator;
import io.titan.graphql.validation.TitanGraphqlValidationReport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the static, Titan-transpilable database read boundary from a reviewed model.
 *
 * <p>Every emitted SELECT is derived from physical bindings in the model. The generated methods
 * use Titan's pure metadata-driven JDBC carrier shape: Titan compiles it to a JSONB-returning
 * function on PostgreSQL and an open-result-set procedure on MySQL. No result is interpreted in
 * generated Java, which preserves Titan's fail-safe carrier contract.</p>
 */
public final class TitanGraphqlRoutineSourceGenerator {

    public static final String DEFAULT_PACKAGE = "io.titan.graphql.generated";
    public static final String DEFAULT_CLASS = "GeneratedTitanGraphqlReads";
    private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]*";
    private static final List<Integer> RELATION_BATCH_SIZES = List.of(2, 4, 8, 16, 32, 64);
    private static final List<Integer> FILTER_IN_ARITIES = List.of(0, 1, 2, 4, 8, 16);

    private TitanGraphqlRoutineSourceGenerator() {
    }

    public static String generate(TitanGraphqlModelDocument document) {
        return generate(document, DEFAULT_PACKAGE, DEFAULT_CLASS);
    }

    public static String generate(TitanGraphqlModelDocument document, String packageName, String className) {
        if (document == null) {
            throw new IllegalArgumentException("model document is required");
        }
        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);
        if (report.blocksDeployment()) {
            throw new IllegalArgumentException("GraphQL model has " + report.errorCount()
                    + " deployment-blocking validation error(s)");
        }
        requirePackage(packageName);
        requireIdentifier(className, "generated class");

        GenerationContext context = new GenerationContext(document);
        String semanticHash = TitanGraphqlModelDocumentJson.semanticHash(document);
        StringBuilder source = new StringBuilder();
        source.append("package ").append(packageName).append(";\n\n")
                .append("import titan.dsl.StoredFunction;\n")
                .append("import java.sql.*;\n")
                .append("import java.util.*;\n\n")
                .append("/** Generated from reviewed model ")
                .append(javaComment(document.metadata().name())).append('@')
                .append(javaComment(document.metadata().version())).append(". DO NOT EDIT. */\n")
                .append("public final class ").append(className).append(" {\n")
                .append("    private ").append(className).append("() { }\n\n")
                .append("    @StoredFunction\n")
                .append("    public static String modelSemanticHash() {\n")
                .append("        return \"").append(semanticHash).append("\";\n")
                .append("    }\n");

        document.roots().stream().sorted(Comparator.comparing(TitanGraphqlRootDocument::name))
                .forEach(root -> emitRoot(source, context, root));
        document.types().stream().sorted(Comparator.comparing(TitanGraphqlTypeDocument::name))
                .forEach(type -> type.relations().stream()
                        .sorted(Comparator.comparing(TitanGraphqlRelationDocument::name))
                        .forEach(relation -> emitRelation(source, context, type, relation)));
        source.append("}\n");
        return source.toString();
    }

    private static void emitRoot(
            StringBuilder source,
            GenerationContext context,
            TitanGraphqlRootDocument root
    ) {
        TitanGraphqlTypeDocument type = context.type(root.type());
        if (root.operation() == TitanGraphqlRootDocument.RootDocumentOperation.POINT) {
            List<TitanGraphqlRootDocument.RootDocumentArgument> keys = pointKeyArguments(root);
            List<Parameter> parameters = new ArrayList<>();
            List<String> predicates = new ArrayList<>();
            for (TitanGraphqlRootDocument.RootDocumentArgument key : keys) {
                if (key.kind() != TitanGraphqlRootDocument.RootDocumentArgumentKind.EQUALS
                        || key.hops() != 0) {
                    throw unsupported("point root '" + root.name()
                            + "' requires local equals key arguments");
                }
                parameters.add(context.parameter(key.name(), key.type(), key.column(), type));
                predicates.add(identifier(key.column(), "root key column") + " = ?");
            }
            String sql = selectList(context, type) + " WHERE " + String.join(" AND ", predicates);
            emitCarrier(source, "readRoot" + javaTypeName(root.name()), parameters, sql);
            return;
        }

        TitanGraphqlRootDocument.RootDocumentPagination pagination = root.pagination();
        if (pagination == null || pagination.cursor() == null) {
            throw unsupported("connection root '" + root.name() + "' requires cursor pagination");
        }
        TitanGraphqlRootDocument.Cursor cursor = pagination.cursor();
        String cursorTieBreaker = cursor.tieBreaker().isBlank() ? cursor.column() : cursor.tieBreaker();
        emitPageCarriers(source, context, type, root, "",
                cursor.column(), cursorTieBreaker, cursor.direction());
        for (TitanGraphqlRootDocument.RootDocumentSortPath sort : root.sortPaths()) {
            String tieBreaker = sort.tieBreaker().isBlank() ? sort.column() : sort.tieBreaker();
            String methodSuffix = "Order" + javaTypeName(sort.name());
            if (sort.hops() == 0) {
                emitPageCarriers(source, context, type, root,
                        methodSuffix + "Asc", sort.column(), tieBreaker,
                        TitanGraphqlRootDocument.RootDocumentSortDirection.ASC);
                emitPageCarriers(source, context, type, root,
                        methodSuffix + "Desc", sort.column(), tieBreaker,
                        TitanGraphqlRootDocument.RootDocumentSortDirection.DESC);
            } else if (sort.hops() == 1) {
                emitRelationHopPageCarriers(source, context, type, root, sort,
                        methodSuffix + "Asc", TitanGraphqlRootDocument.RootDocumentSortDirection.ASC);
                emitRelationHopPageCarriers(source, context, type, root, sort,
                        methodSuffix + "Desc", TitanGraphqlRootDocument.RootDocumentSortDirection.DESC);
            } else {
                throw unsupported("root sort path '" + root.name() + "." + sort.name()
                        + "' exceeds the generated relation-hop limit of 1");
            }
        }
        if (pagination.totalCount() == TitanGraphqlRootDocument.TotalCountMode.EXACT) {
            PredicateContract countPredicate = rootPredicate(context, type, root, null);
            emitCarrier(source, "countRoot" + javaTypeName(root.name()),
                    countPredicate.parameters(), "SELECT COUNT(*) AS total_count FROM "
                            + identifier(context.schema(type), "schema") + "."
                            + identifier(context.table(type), "table") + countPredicate.sql());
        }
        for (ScalarFilterContract filter : localScalarFilters(context, type, root)) {
            emitScalarFilterCarriers(source, context, type, root, cursor, cursorTieBreaker, filter);
        }
        List<TitanGraphqlFilterLayout.Binding> filterBindings =
                TitanGraphqlFilterLayout.bindings(context.document, type, root);
        if (!filterBindings.isEmpty()) {
            emitFilterPlanCarriers(source, context, type, root, null, cursor.direction(), filterBindings);
            for (TitanGraphqlRootDocument.RootDocumentSortPath sort : root.sortPaths()) {
                emitFilterPlanCarriers(source, context, type, root, sort,
                        TitanGraphqlRootDocument.RootDocumentSortDirection.ASC, filterBindings);
                emitFilterPlanCarriers(source, context, type, root, sort,
                        TitanGraphqlRootDocument.RootDocumentSortDirection.DESC, filterBindings);
            }
        }
    }

    private static void emitFilterPlanCarriers(
            StringBuilder source,
            GenerationContext context,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            TitanGraphqlRootDocument.RootDocumentSortPath modelSort,
            TitanGraphqlRootDocument.RootDocumentSortDirection direction,
            List<TitanGraphqlFilterLayout.Binding> bindings
    ) {
        String rootAlias = "tgql_root";
        FilterCarrierContract filter = filterCarrier(context, type, root, bindings, rootAlias);
        String from = identifier(context.schema(type), "schema") + "."
                + identifier(context.table(type), "table") + " " + rootAlias + filter.joins();
        List<String> extra = new ArrayList<>();
        SortContract sort;
        String suffix = "FilterPlan";
        if (modelSort == null) {
            TitanGraphqlRootDocument.Cursor cursor = root.pagination().cursor();
            String tie = cursor.tieBreaker().isBlank() ? cursor.column() : cursor.tieBreaker();
            sort = sortContract(context, type, cursor.column(), tie, cursor.direction(), rootAlias);
        } else {
            suffix = "Order" + javaTypeName(modelSort.name())
                    + (direction == TitanGraphqlRootDocument.RootDocumentSortDirection.ASC ? "Asc" : "Desc")
                    + suffix;
            String tie = modelSort.tieBreaker().isBlank() ? context.primaryKey(type) : modelSort.tieBreaker();
            if (modelSort.hops() == 0) {
                sort = sortContract(context, type, modelSort.column(), tie, direction, rootAlias);
            } else {
                RelationPath resolved = relationPath(context, type, root.name(), modelSort.name(),
                        modelSort.path(), modelSort.column(), modelSort.hops(), "sort");
                String alias = "tgql_order";
                from += join(context, rootAlias, resolved, alias);
                String expression = resolved.field().computed() == null
                        ? columnExpression(alias, resolved.field().column())
                        : computedExpression(resolved.target(), resolved.field(), alias);
                sort = new SortContract(modelSort.name(), expression, resolved.field().type(), tie,
                        context.sortExpression(type, tie, rootAlias), context.graphqlType(type, tie), direction);
                extra.add(expression + " AS " + sqlAlias(modelSort.name()));
            }
        }
        from += filter.slotJoins();
        String select = selectList(context, type, extra, rootAlias, from);
        PredicateContract base = rootPredicate(context, type, root, sort, rootAlias, null);
        String where = base.sql() + (base.sql().isEmpty() ? " WHERE " : " AND ") + filter.predicate();
        List<Parameter> parameters = new ArrayList<>(filter.parameters());
        parameters.addAll(base.parameters());
        parameters.add(new Parameter("pageSize", "int", "setInt"));
        requireRoutineParameterBudget(root.name(), parameters);
        String reverse = direction == TitanGraphqlRootDocument.RootDocumentSortDirection.ASC ? "DESC" : "ASC";
        String method = "readRoot" + javaTypeName(root.name()) + suffix;
        emitCarrier(source, method + "Forward", parameters,
                select + where + orderBy(sort.expression(), direction.name(), sort.tieBreakerExpression())
                        + " LIMIT ?");
        emitCarrier(source, method + "Backward", parameters,
                select + where + orderBy(sort.expression(), reverse, sort.tieBreakerExpression()) + " LIMIT ?");
        if (root.pagination().totalCount() == TitanGraphqlRootDocument.TotalCountMode.EXACT
                && modelSort == null) {
            PredicateContract countBase = rootPredicate(context, type, root, null, rootAlias, null);
            List<Parameter> countParameters = new ArrayList<>(filter.parameters());
            countParameters.addAll(countBase.parameters());
            requireRoutineParameterBudget(root.name(), countParameters);
            String countWhere = countBase.sql() + (countBase.sql().isEmpty() ? " WHERE " : " AND ")
                    + filter.predicate();
            emitCarrier(source, "countRoot" + javaTypeName(root.name()) + "FilterPlan", countParameters,
                    "SELECT COUNT(*) AS total_count FROM " + from + countWhere);
        }
    }

    private static FilterCarrierContract filterCarrier(
            GenerationContext context,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            List<TitanGraphqlFilterLayout.Binding> bindings,
            String rootAlias
    ) {
        List<String> valueTypes = TitanGraphqlFilterLayout.valueTypes(bindings);
        List<Parameter> parameters = new ArrayList<>();
        StringBuilder slots = new StringBuilder();
        List<String> groups = new ArrayList<>();
        for (int group = 1; group <= TitanGraphqlFilterPlan.MAX_GROUPS; group++) {
            String groupAlias = "tgql_fg" + group;
            parameters.add(new Parameter("filterGroup" + group + "Active", "boolean", "setBoolean"));
            slots.append(" CROSS JOIN (SELECT ? AS active) ").append(groupAlias);
            List<String> terms = new ArrayList<>();
            for (int term = 1; term <= TitanGraphqlFilterPlan.MAX_TERMS_PER_GROUP; term++) {
                String prefix = "filter" + group + term;
                String alias = "tgql_f" + group + term;
                parameters.add(new Parameter(prefix + "Selector", "int", "setInt"));
                parameters.add(new Parameter(prefix + "Negated", "boolean", "setBoolean"));
                parameters.add(new Parameter(prefix + "Null", "boolean", "setBoolean"));
                StringBuilder derived = new StringBuilder(" CROSS JOIN (SELECT ? AS selector, ? AS negated, ? AS null_value");
                for (String valueType : valueTypes) {
                    Parameter value = context.parameter(prefix + javaTypeName(valueType), valueType,
                            bindings.getFirst().binding(), type);
                    parameters.add(value);
                    derived.append(", ? AS ").append(valueAlias(valueType));
                }
                slots.append(derived).append(") ").append(alias);
                String selected = selectorPredicate(context, type, root, bindings, rootAlias, alias);
                terms.add("(CASE WHEN " + alias + ".negated = TRUE THEN NOT (" + selected
                        + ") ELSE (" + selected + ") END)");
            }
            groups.add("(" + groupAlias + ".active = TRUE AND " + String.join(" AND ", terms) + ")");
        }
        return new FilterCarrierContract(List.copyOf(parameters), relationFilterJoins(context, type, root,
                bindings, rootAlias), slots.toString(), "(" + String.join(" OR ", groups) + ")", valueTypes);
    }

    private static String selectorPredicate(
            GenerationContext context,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            List<TitanGraphqlFilterLayout.Binding> bindings,
            String rootAlias,
            String slotAlias
    ) {
        StringBuilder sql = new StringBuilder("CASE ").append(slotAlias).append(".selector ");
        for (TitanGraphqlFilterLayout.Binding binding : bindings) {
            String expression = filterExpression(context, type, root, binding, rootAlias);
            String value = slotAlias + "." + valueAlias(binding.graphqlType());
            String predicate = switch (binding.operator()) {
                case "eq" -> "CASE WHEN " + slotAlias + ".null_value = TRUE THEN " + expression
                        + " IS NULL ELSE " + expression + " = " + value + " END";
                case "neq" -> "CASE WHEN " + slotAlias + ".null_value = TRUE THEN " + expression
                        + " IS NOT NULL ELSE " + expression + " <> " + value + " END";
                case "isnull" -> "CASE WHEN " + slotAlias + ".boolean_value = TRUE THEN " + expression
                        + " IS NULL ELSE " + expression + " IS NOT NULL END";
                case "lt" -> expression + " < " + value;
                case "lte" -> expression + " <= " + value;
                case "gt" -> expression + " > " + value;
                case "gte" -> expression + " >= " + value;
                case "contains", "startswith", "endswith" -> expression + " LIKE "
                        + slotAlias + ".string_value ESCAPE '!'";
                case "in" -> expression + " = " + value;
                default -> throw unsupported("unknown filter operator '" + binding.operator() + "'");
            };
            sql.append("WHEN ").append(binding.selector()).append(" THEN ").append(predicate).append(' ');
        }
        return sql.append("ELSE TRUE END").toString();
    }

    private static String filterExpression(GenerationContext context, TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root, TitanGraphqlFilterLayout.Binding binding, String rootAlias) {
        if (binding.hops() == 0) {
            return binding.computed() ? context.sortExpression(type, binding.binding(), rootAlias)
                    : columnExpression(rootAlias, binding.binding());
        }
        RelationPath path = relationPath(context, type, root.name(), binding.fieldName(), binding.path(),
                "", binding.hops(), "filter");
        String alias = "tgql_filter_" + sqlAlias(path.relation().name());
        return binding.computed() ? computedExpression(path.target(), path.field(), alias)
                : columnExpression(alias, binding.binding());
    }

    private static String relationFilterJoins(GenerationContext context, TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root, List<TitanGraphqlFilterLayout.Binding> bindings, String rootAlias) {
        StringBuilder joins = new StringBuilder();
        List<String> emitted = new ArrayList<>();
        for (TitanGraphqlFilterLayout.Binding binding : bindings) {
            if (binding.hops() == 0) continue;
            RelationPath path = relationPath(context, type, root.name(), binding.fieldName(), binding.path(),
                    "", binding.hops(), "filter");
            if (!emitted.contains(path.relation().name())) {
                emitted.add(path.relation().name());
                joins.append(join(context, rootAlias, path,
                        "tgql_filter_" + sqlAlias(path.relation().name())));
            }
        }
        return joins.toString();
    }

    private static String join(GenerationContext context, String rootAlias, RelationPath path, String alias) {
        String kind = path.relation().nullable() ? " LEFT JOIN " : " JOIN ";
        return kind + identifier(context.schema(path.target()), "relation schema") + "."
                + identifier(context.table(path.target()), "relation table") + " " + alias + " ON "
                + columnExpression(rootAlias, path.relation().localColumn()) + " = "
                + columnExpression(alias, path.relation().targetColumn());
    }

    private static RelationPath relationPath(GenerationContext context, TitanGraphqlTypeDocument type,
            String rootName, String name, String pathValue, String localColumn, int hops, String purpose) {
        if (hops != 1) throw unsupported("root " + purpose + " path '" + rootName + "." + name
                + "' exceeds the generated relation-hop limit of 1");
        String[] path = pathValue.split("\\.", -1);
        if (path.length != 2) throw unsupported("one-hop root " + purpose + " path '" + rootName + "."
                + name + "' must use relation.field syntax");
        TitanGraphqlRelationDocument relation = type.relations().stream()
                .filter(candidate -> candidate.name().equals(path[0])).findFirst()
                .orElseThrow(() -> unsupported("unknown relation '" + path[0] + "'"));
        if (relation.cardinality() != TitanGraphqlRelationDocument.RelationDocumentCardinality.ONE) {
            throw unsupported("root " + purpose + " path '" + rootName + "." + name
                    + "' requires a to-one relation");
        }
        if (!localColumn.isBlank() && !localColumn.equals(relation.localColumn())) {
            throw unsupported("root " + purpose + " path '" + rootName + "." + name
                    + "' must bind local column '" + relation.localColumn() + "'");
        }
        TitanGraphqlTypeDocument target = context.type(relation.targetType());
        TitanGraphqlFieldDocument field = target.fields().stream()
                .filter(candidate -> candidate.name().equals(path[1])).findFirst()
                .orElseThrow(() -> unsupported("unknown relation scalar '" + pathValue + "'"));
        return new RelationPath(relation, target, field);
    }

    private static String normalizeGraphqlType(String type) {
        return TitanGraphqlFilterLayout.normalizeGraphqlType(type);
    }

    private static String valueAlias(String graphqlType) {
        try {
            return TitanGraphqlFilterLayout.valueKind(graphqlType) + "_value";
        } catch (IllegalArgumentException failure) {
            throw unsupported(failure.getMessage());
        }
    }

    private static void requireRoutineParameterBudget(String rootName, List<Parameter> parameters) {
        if (parameters.size() > 96) {
            throw unsupported("root '" + rootName + "' needs " + parameters.size()
                    + " static filter carrier parameters; maximum is 96");
        }
    }

    private static void emitScalarFilterCarriers(
            StringBuilder source,
            GenerationContext context,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            TitanGraphqlRootDocument.Cursor cursor,
            String cursorTieBreaker,
            ScalarFilterContract scalarFilter
    ) {
        List<Integer> arities = "in".equals(scalarFilter.operator()) ? FILTER_IN_ARITIES : List.of(-1);
        for (int arity : arities) {
            String suffix = filterMethodSuffix(scalarFilter.name(), scalarFilter.operator(), arity);
            SortContract sort = sortContract(
                    context, type, cursor.column(), cursorTieBreaker, cursor.direction());
            emitPageCarriers(source, context, type, root, suffix, cursor.direction(), sort,
                    selectList(context, type), "", scalarFilter.withInArity(arity));
            if (root.pagination().totalCount() == TitanGraphqlRootDocument.TotalCountMode.EXACT) {
                PredicateContract predicate = rootPredicate(context, type, root, null, "",
                        scalarFilter.withInArity(arity));
                emitCarrier(source, "countRoot" + javaTypeName(root.name()) + suffix,
                        predicate.parameters(), "SELECT COUNT(*) AS total_count FROM "
                                + identifier(context.schema(type), "schema") + "."
                                + identifier(context.table(type), "table") + predicate.sql());
            }
        }
    }

    private static List<TitanGraphqlRootDocument.RootDocumentArgument> pointKeyArguments(
            TitanGraphqlRootDocument root
    ) {
        if (root.argument() != null && !root.arguments().isEmpty()) {
            throw unsupported("point root '" + root.name()
                    + "' cannot declare both argument and arguments");
        }
        List<TitanGraphqlRootDocument.RootDocumentArgument> keys = root.argument() == null
                ? root.arguments() : List.of(root.argument());
        if (keys.isEmpty()) {
            throw unsupported("point root '" + root.name() + "' requires one or more key arguments");
        }
        return keys.stream()
                .sorted(Comparator.comparing(TitanGraphqlRootDocument.RootDocumentArgument::name))
                .toList();
    }

    private static void emitPageCarriers(
            StringBuilder source,
            GenerationContext context,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            String methodSuffix,
            String sortBinding,
            String tieBreakerBinding,
            TitanGraphqlRootDocument.RootDocumentSortDirection direction
    ) {
        SortContract sort = sortContract(context, type, sortBinding, tieBreakerBinding, direction);
        emitPageCarriers(source, context, type, root, methodSuffix, direction, sort,
                selectList(context, type), "", null);
    }

    private static void emitRelationHopPageCarriers(
            StringBuilder source,
            GenerationContext context,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            TitanGraphqlRootDocument.RootDocumentSortPath modelSort,
            String methodSuffix,
            TitanGraphqlRootDocument.RootDocumentSortDirection direction
    ) {
        String[] path = modelSort.path().split("\\.", -1);
        if (path.length != 2 || path[0].isBlank() || path[1].isBlank()) {
            throw unsupported("one-hop root sort path '" + root.name() + "." + modelSort.name()
                    + "' must use relation.field syntax");
        }
        TitanGraphqlRelationDocument relation = type.relations().stream()
                .filter(candidate -> candidate.name().equals(path[0]))
                .findFirst()
                .orElseThrow(() -> unsupported("one-hop root sort path '" + root.name() + "."
                        + modelSort.name() + "' references unknown relation '" + path[0] + "'"));
        if (relation.cardinality() != TitanGraphqlRelationDocument.RelationDocumentCardinality.ONE
                || relation.nullable()) {
            throw unsupported("one-hop root sort path '" + root.name() + "." + modelSort.name()
                    + "' requires a non-null to-one relation");
        }
        if (!modelSort.column().equals(relation.localColumn())) {
            throw unsupported("one-hop root sort path '" + root.name() + "." + modelSort.name()
                    + "' must bind column '" + relation.localColumn() + "' for relation '"
                    + relation.name() + "'");
        }
        TitanGraphqlTypeDocument target = context.type(relation.targetType());
        TitanGraphqlFieldDocument targetField = target.fields().stream()
                .filter(field -> field.name().equals(path[1]))
                .findFirst()
                .orElseThrow(() -> unsupported("one-hop root sort path '" + root.name() + "."
                        + modelSort.name() + "' references unknown scalar field '" + path[1] + "'"));
        if (targetField.computed() != null || targetField.nullable()) {
            throw unsupported("one-hop root sort path '" + root.name() + "." + modelSort.name()
                    + "' requires a stored non-null scalar field");
        }
        String rootAlias = "tgql_root";
        String targetAlias = "tgql_sort";
        String sortExpression = columnExpression(targetAlias, targetField.column());
        String tieBreaker = modelSort.tieBreaker().isBlank()
                ? context.primaryKey(type) : modelSort.tieBreaker();
        SortContract sort = new SortContract(
                modelSort.name(),
                sortExpression,
                targetField.type(),
                tieBreaker,
                context.sortExpression(type, tieBreaker, rootAlias),
                context.graphqlType(type, tieBreaker),
                direction
        );
        String from = identifier(context.schema(type), "schema") + "."
                + identifier(context.table(type), "table") + " " + rootAlias
                + " JOIN " + identifier(context.schema(target), "relation schema") + "."
                + identifier(context.table(target), "relation table") + " " + targetAlias
                + " ON " + columnExpression(rootAlias, relation.localColumn())
                + " = " + columnExpression(targetAlias, relation.targetColumn());
        String select = selectList(
                context,
                type,
                List.of(sortExpression + " AS " + sqlAlias(modelSort.name())),
                rootAlias,
                from
        );
        emitPageCarriers(source, context, type, root, methodSuffix, direction, sort, select, rootAlias, null);
    }

    private static void emitPageCarriers(
            StringBuilder source,
            GenerationContext context,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            String methodSuffix,
            TitanGraphqlRootDocument.RootDocumentSortDirection direction,
            SortContract sort,
            String select,
            String rootQualifier,
            ScalarFilterContract scalarFilter
    ) {
        PredicateContract pagePredicate = rootPredicate(context, type, root, sort, rootQualifier, scalarFilter);
        List<Parameter> parameters = new ArrayList<>(pagePredicate.parameters());
        parameters.add(new Parameter("pageSize", "int", "setInt"));
        String base = select + pagePredicate.sql();
        String reverseDirection = direction == TitanGraphqlRootDocument.RootDocumentSortDirection.ASC
                ? "DESC" : "ASC";
        String rootMethod = "readRoot" + javaTypeName(root.name()) + methodSuffix;
        emitCarrier(source, rootMethod + "Forward", parameters,
                base + orderBy(sort.expression(), direction.name(), sort.tieBreakerExpression()) + " LIMIT ?");
        emitCarrier(source, rootMethod + "Backward", parameters,
                base + orderBy(sort.expression(), reverseDirection, sort.tieBreakerExpression()) + " LIMIT ?");
    }

    private static PredicateContract rootPredicate(
            GenerationContext context,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            SortContract sort
    ) {
        return rootPredicate(context, type, root, sort, "", null);
    }

    private static PredicateContract rootPredicate(
            GenerationContext context,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            SortContract sort,
            String rootQualifier
    ) {
        return rootPredicate(context, type, root, sort, rootQualifier, null);
    }

    private static PredicateContract rootPredicate(
            GenerationContext context,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            SortContract sort,
            String rootQualifier,
            ScalarFilterContract scalarFilter
    ) {
        List<Parameter> parameters = new ArrayList<>();
        List<String> clauses = new ArrayList<>();
        if (sort != null) {
            String afterOperator = sort.direction() == TitanGraphqlRootDocument.RootDocumentSortDirection.ASC
                    ? ">" : "<";
            String beforeOperator = sort.direction() == TitanGraphqlRootDocument.RootDocumentSortDirection.ASC
                    ? "<" : ">";
            parameters.add(new Parameter("hasAfter", "boolean", "setBoolean"));
            parameters.add(context.parameter("after", sort.graphqlType(), sort.binding(), type));
            if (sort.compound()) {
                parameters.add(context.parameter("afterEqual", sort.graphqlType(), sort.binding(), type));
                parameters.add(context.parameter("afterTie", sort.tieBreakerGraphqlType(),
                        sort.tieBreakerBinding(), type));
            }
            parameters.add(new Parameter("hasBefore", "boolean", "setBoolean"));
            parameters.add(context.parameter("before", sort.graphqlType(), sort.binding(), type));
            if (sort.compound()) {
                parameters.add(context.parameter("beforeEqual", sort.graphqlType(), sort.binding(), type));
                parameters.add(context.parameter("beforeTie", sort.tieBreakerGraphqlType(),
                        sort.tieBreakerBinding(), type));
            }
            clauses.add(cursorClause(sort, afterOperator));
            clauses.add(cursorClause(sort, beforeOperator));
        }
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : root.arguments()) {
            if (argument.kind() == TitanGraphqlRootDocument.RootDocumentArgumentKind.EQUALS
                    && argument.hops() == 0) {
                parameters.add(new Parameter("has" + javaTypeName(argument.name()), "boolean", "setBoolean"));
                parameters.add(context.parameter(argument.name(), argument.type(), argument.column(), type));
                clauses.add("(? = FALSE OR " + columnExpression(rootQualifier, argument.column()) + " = ?)");
            }
        }
        for (String contextFilterName : root.contextFilters()) {
            TitanGraphqlContextFilterDocument filter = context.contextFilter(contextFilterName);
            String suffix = javaTypeName(filter.contextKey());
            parameters.add(new Parameter("apply" + suffix, "boolean", "setBoolean"));
            parameters.add(new Parameter("has" + suffix, "boolean", "setBoolean"));
            String filterType = filter.operator() == TitanGraphqlContextFilterDocument.Operator.BOOLEAN_EQUALS
                    ? "Boolean" : context.graphqlType(type, filter.column());
            parameters.add(context.parameter(filter.contextKey(), filterType, filter.column(), type));
            String column = columnExpression(rootQualifier, filter.column());
            clauses.add(filter.failClosed()
                    ? "(? = FALSE OR (? = TRUE AND " + column + " = ?))"
                    : "(? = FALSE OR ? = FALSE OR " + column + " = ?)");
        }
        if (scalarFilter != null) {
            clauses.add(scalarFilterPredicate(context, type, scalarFilter, parameters, rootQualifier));
        }
        return new PredicateContract(List.copyOf(parameters),
                clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses));
    }

    private static String scalarFilterPredicate(
            GenerationContext context,
            TitanGraphqlTypeDocument type,
            ScalarFilterContract filter,
            List<Parameter> parameters,
            String qualifier
    ) {
        String expression = filter.computed()
                ? context.sortExpression(type, filter.binding(), qualifier)
                : columnExpression(qualifier, filter.binding());
        String parameterName = "filter" + javaTypeName(filter.name());
        return switch (filter.operator()) {
            case "eq", "neq" -> {
                parameters.add(new Parameter(parameterName + "Null", "boolean", "setBoolean"));
                parameters.add(context.parameter(parameterName + "Value", filter.graphqlType(),
                        filter.binding(), type));
                String comparison = "eq".equals(filter.operator()) ? " = ?" : " <> ?";
                String nullCheck = "eq".equals(filter.operator()) ? " IS NULL" : " IS NOT NULL";
                yield "(CASE WHEN ? = TRUE THEN " + expression + nullCheck
                        + " ELSE " + expression + comparison + " END)";
            }
            case "isnull" -> {
                parameters.add(new Parameter(parameterName + "IsNull", "boolean", "setBoolean"));
                yield "(CASE WHEN ? = TRUE THEN " + expression + " IS NULL ELSE "
                        + expression + " IS NOT NULL END)";
            }
            case "lt", "lte", "gt", "gte" -> {
                parameters.add(context.parameter(parameterName + "Value", filter.graphqlType(),
                        filter.binding(), type));
                String operator = switch (filter.operator()) {
                    case "lt" -> " < ?";
                    case "lte" -> " <= ?";
                    case "gt" -> " > ?";
                    default -> " >= ?";
                };
                yield expression + operator;
            }
            case "contains", "startswith", "endswith" -> {
                parameters.add(context.parameter(parameterName + "Pattern", "String", filter.binding(), type));
                yield expression + " LIKE ? ESCAPE '!'";
            }
            case "in" -> {
                if (filter.inArity() == 0) {
                    yield "1 = 0";
                }
                List<String> placeholders = new ArrayList<>();
                for (int index = 1; index <= filter.inArity(); index++) {
                    parameters.add(context.parameter(parameterName + "Value" + index,
                            filter.graphqlType(), filter.binding(), type));
                    placeholders.add("?");
                }
                yield expression + " IN (" + String.join(", ", placeholders) + ")";
            }
            default -> throw unsupported("root filter '" + filter.name()
                    + "' uses unsupported operator '" + filter.operator() + "'");
        };
    }

    private static String cursorClause(SortContract sort, String operator) {
        if (!sort.compound()) {
            return "(? = FALSE OR " + sort.expression() + " " + operator + " ?)";
        }
        return "(? = FALSE OR (" + sort.expression() + " " + operator + " ? OR ("
                + sort.expression() + " = ? AND " + sort.tieBreakerExpression()
                + " " + operator + " ?)))";
    }

    private static List<ScalarFilterContract> localScalarFilters(
            GenerationContext context,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root
    ) {
        return TitanGraphqlFilterLayout.bindings(context.document, type, root).stream()
                .filter(binding -> binding.hops() == 0)
                .map(binding -> new ScalarFilterContract(
                        binding.fieldName(), binding.binding(), binding.graphqlType(),
                        binding.operator(), binding.computed(), -1))
                .toList();
    }

    private static String normalizeFilterOperator(String operator) {
        try {
            return TitanGraphqlFilterLayout.normalizeOperator(operator);
        } catch (IllegalArgumentException failure) {
            throw unsupported(failure.getMessage());
        }
    }

    /** Inventory method suffix shared by generated carriers and the generic compiled runtime. */
    public static String filterMethodSuffix(String fieldName, String operator, int inArity) {
        String normalized = normalizeFilterOperator(operator);
        String suffix = "Filter" + javaTypeName(fieldName) + javaTypeName(normalized);
        return "in".equals(normalized) ? suffix + inArity : suffix;
    }

    private static SortContract sortContract(
            GenerationContext context,
            TitanGraphqlTypeDocument type,
            String binding,
            String tieBreakerBinding,
            TitanGraphqlRootDocument.RootDocumentSortDirection direction
    ) {
        return sortContract(context, type, binding, tieBreakerBinding, direction, "");
    }

    private static SortContract sortContract(
            GenerationContext context,
            TitanGraphqlTypeDocument type,
            String binding,
            String tieBreakerBinding,
            TitanGraphqlRootDocument.RootDocumentSortDirection direction,
            String qualifier
    ) {
        String normalizedTie = tieBreakerBinding == null || tieBreakerBinding.isBlank()
                ? binding : tieBreakerBinding;
        return new SortContract(
                binding,
                context.sortExpression(type, binding, qualifier),
                context.graphqlType(type, binding),
                normalizedTie,
                context.sortExpression(type, normalizedTie, qualifier),
                context.graphqlType(type, normalizedTie),
                direction
        );
    }

    private static void emitRelation(
            StringBuilder source,
            GenerationContext context,
            TitanGraphqlTypeDocument owner,
            TitanGraphqlRelationDocument relation
    ) {
        if (!relation.policies().isEmpty()) {
            return; // Never generate an unguarded carrier for a protected relation.
        }
        TitanGraphqlTypeDocument target = context.type(relation.targetType());
        String graphqlType = context.graphqlTypeOrNull(owner, relation.localColumn());
        if (graphqlType == null) {
            graphqlType = context.graphqlType(target, relation.targetColumn());
        }
        Parameter localKey = context.parameter("localKey", graphqlType, relation.localColumn(), owner);
        List<Parameter> relationParameters = new ArrayList<>();
        List<String> relationClauses = new ArrayList<>();
        for (TitanGraphqlRelationDocument.RelationDocumentArgument argument : relationFilterArguments(relation)) {
            if (argument.hops() != 0) {
                throw unsupported("relation '" + relation.name() + "' uses relation-hop filtering");
            }
            relationParameters.add(new Parameter(
                    "has" + javaTypeName(argument.name()), "boolean", "setBoolean"));
            relationParameters.add(context.parameter(
                    argument.name(), argument.type(), argument.column(), target));
            relationClauses.add("(? = FALSE OR "
                    + identifier(argument.column(), "relation argument column") + " = ?)");
        }
        List<Parameter> directParameters = new ArrayList<>();
        directParameters.add(localKey);
        directParameters.addAll(relationParameters);
        StringBuilder sql = new StringBuilder(selectList(context, target))
                .append(" WHERE ").append(identifier(relation.targetColumn(), "relation target column"))
                .append(" = ?");
        for (String clause : relationClauses) {
            sql.append(" AND ").append(clause);
        }
        appendRelationOrder(sql, context, target, relation);
        emitCarrier(source, "readRelation" + javaTypeName(owner.name()) + javaTypeName(relation.name()),
                directParameters, sql.toString());
        for (int batchSize : RELATION_BATCH_SIZES) {
            List<Parameter> batchParameters = new ArrayList<>();
            for (int index = 1; index <= batchSize; index++) {
                batchParameters.add(new Parameter("localKey" + index, localKey.javaType(), localKey.setter()));
            }
            batchParameters.addAll(relationParameters);
            String batchSql = selectList(context, target, List.of(
                    identifier(relation.targetColumn(), "relation target column")
                            + " AS __titan_parent_key"))
                    + " WHERE " + identifier(relation.targetColumn(), "relation target column")
                    + " IN (" + String.join(", ", java.util.Collections.nCopies(batchSize, "?")) + ")";
            StringBuilder orderedBatchSql = new StringBuilder(batchSql);
            for (String clause : relationClauses) {
                orderedBatchSql.append(" AND ").append(clause);
            }
            appendRelationOrder(orderedBatchSql, context, target, relation);
            emitCarrier(source, "readRelation" + javaTypeName(owner.name())
                    + javaTypeName(relation.name()) + "Batch" + batchSize,
                    batchParameters, orderedBatchSql.toString());
        }
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

    private static void appendRelationOrder(
            StringBuilder sql,
            GenerationContext context,
            TitanGraphqlTypeDocument target,
            TitanGraphqlRelationDocument relation
    ) {
        if (!relation.sortPaths().isEmpty()) {
            TitanGraphqlRelationDocument.RelationDocumentSortPath sort = relation.sortPaths().getFirst();
            if (sort.hops() != 0) {
                throw unsupported("relation '" + relation.name() + "' uses relation-hop ordering");
            }
            String tieBreaker = sort.tieBreaker().isBlank() ? sort.column() : sort.tieBreaker();
            sql.append(orderBy(
                    context.sortExpression(target, sort.column()),
                    sort.direction().name(),
                    context.sortExpression(target, tieBreaker)));
            return;
        }
        if (relation.cardinality() == TitanGraphqlRelationDocument.RelationDocumentCardinality.MANY) {
            if (target.primaryKey().isBlank()) {
                throw unsupported("to-many relation '" + relation.name()
                        + "' has no sort path or target primary key");
            }
            String primaryKey = identifier(target.primaryKey(), "target primary key");
            sql.append(orderBy(primaryKey, "ASC", primaryKey));
        }
    }

    private static String selectList(GenerationContext context, TitanGraphqlTypeDocument type) {
        return selectList(context, type, List.of());
    }

    private static String selectList(
            GenerationContext context,
            TitanGraphqlTypeDocument type,
            List<String> extraProjections
    ) {
        String from = identifier(context.schema(type), "schema") + "."
                + identifier(context.table(type), "table");
        return selectList(context, type, extraProjections, "", from);
    }

    private static String selectList(
            GenerationContext context,
            TitanGraphqlTypeDocument type,
            List<String> extraProjections,
            String qualifier,
            String from
    ) {
        List<String> projections = new ArrayList<>();
        for (TitanGraphqlFieldDocument field : type.fields()) {
            if (!field.policies().isEmpty()) {
                continue; // Protected scalars require a policy-specific generated routine.
            }
            if (field.computed() == null) {
                projections.add(columnExpression(qualifier, field.column()) + " AS "
                        + sqlAlias(field.name()));
            } else if (field.computed().selectable()) {
                projections.add(computedExpression(type, field, qualifier) + " AS "
                        + sqlAlias(field.name()));
            }
        }
        for (TitanGraphqlRelationDocument relation : type.relations()) {
            if (relation.policies().isEmpty()) {
                projections.add(columnExpression(qualifier, relation.localColumn()) + " AS "
                        + hiddenRelationAlias(relation.name()));
            }
        }
        projections.addAll(extraProjections);
        if (projections.isEmpty()) {
            throw unsupported("type '" + type.name() + "' has no unprotected selectable scalar fields");
        }
        return "SELECT " + String.join(", ", projections) + " FROM " + from;
    }

    private static String computedExpression(TitanGraphqlTypeDocument type, TitanGraphqlFieldDocument field) {
        return computedExpression(type, field, "");
    }

    private static String computedExpression(
            TitanGraphqlTypeDocument type,
            TitanGraphqlFieldDocument field,
            String qualifier
    ) {
        String expression = field.computed().sqlTemplate();
        if (expression.isBlank() || expression.contains(";") || expression.contains("--")
                || expression.contains("/*") || expression.contains("'") || expression.contains("\"")) {
            throw unsupported("computed field '" + type.name() + "." + field.name()
                    + "' has an unsafe SQL template");
        }
        for (String requiredName : field.computed().requiredColumns()) {
            TitanGraphqlFieldDocument required = type.fields().stream()
                    .filter(candidate -> requiredName.equals(candidate.name()))
                    .findFirst()
                    .orElseThrow(() -> unsupported("computed field '" + type.name() + "." + field.name()
                            + "' references unknown required field '" + requiredName + "'"));
            expression = expression.replace("{" + requiredName + "}",
                    columnExpression(qualifier, required.column()));
        }
        if (expression.contains("{") || !expression.matches("[A-Za-z0-9_().,+*/% -]+")) {
            throw unsupported("computed field '" + type.name() + "." + field.name()
                    + "' has an unsupported SQL template");
        }
        return expression;
    }

    private static String columnExpression(String qualifier, String column) {
        String safeColumn = identifier(column, "column");
        return qualifier == null || qualifier.isBlank()
                ? safeColumn : identifier(qualifier, "table alias") + "." + safeColumn;
    }

    private static String orderBy(String expression, String direction, String tieBreakerExpression) {
        StringBuilder order = new StringBuilder(" ORDER BY ").append(expression).append(' ').append(direction);
        if (tieBreakerExpression != null && !tieBreakerExpression.isBlank()
                && !tieBreakerExpression.equals(expression)) {
            order.append(", ").append(tieBreakerExpression).append(' ').append(direction);
        }
        return order.toString();
    }

    private static void emitCarrier(
            StringBuilder source,
            String methodName,
            List<Parameter> parameters,
            String sql
    ) {
        source.append("\n    @StoredFunction\n")
                .append("    public static List<Map<String,Object>> ").append(methodName)
                .append("(Connection connection");
        for (Parameter parameter : parameters) {
            source.append(", ").append(parameter.javaType()).append(' ').append(parameter.name());
        }
        source.append(") throws SQLException {\n")
                .append("        List<Map<String,Object>> rows = new ArrayList<>();\n")
                .append("        PreparedStatement statement = connection.prepareStatement(\"")
                .append(javaString(sql)).append("\");\n");
        for (int index = 0; index < parameters.size(); index++) {
            Parameter parameter = parameters.get(index);
            source.append("        statement.").append(parameter.setter()).append('(')
                    .append(index + 1).append(", ").append(parameter.name()).append(");\n");
        }
        source.append("        ResultSet resultSet = statement.executeQuery();\n")
                .append("        ResultSetMetaData metadata = resultSet.getMetaData();\n")
                .append("        while (resultSet.next()) {\n")
                .append("            Map<String,Object> row = new LinkedHashMap<>();\n")
                .append("            for (int column = 1; column <= metadata.getColumnCount(); column++) {\n")
                .append("                row.put(metadata.getColumnLabel(column), resultSet.getObject(column));\n")
                .append("            }\n")
                .append("            rows.add(row);\n")
                .append("        }\n")
                .append("        return rows;\n")
                .append("    }\n");
    }

    /** Stable Java method-name suffix shared by generation and inventory-driven invocation. */
    public static String javaTypeName(String value) {
        StringBuilder result = new StringBuilder();
        boolean capitalize = true;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isLetterOrDigit(character)) {
                capitalize = true;
            } else if (capitalize) {
                result.append(Character.toUpperCase(character));
                capitalize = false;
            } else {
                result.append(character);
            }
        }
        if (result.isEmpty() || !Character.isJavaIdentifierStart(result.charAt(0))) {
            throw unsupported("GraphQL name '" + value + "' cannot form a generated Java method");
        }
        return result.toString();
    }

    /** Portable unquoted result label; clients map it back through the reviewed field model. */
    public static String sqlAlias(String graphqlName) {
        requireIdentifier(graphqlName, "GraphQL field");
        StringBuilder alias = new StringBuilder();
        for (int index = 0; index < graphqlName.length(); index++) {
            char character = graphqlName.charAt(index);
            if (Character.isUpperCase(character)) {
                if (!alias.isEmpty()) {
                    alias.append('_');
                }
                alias.append(Character.toLowerCase(character));
            } else {
                alias.append(Character.toLowerCase(character));
            }
        }
        return identifier(alias.toString(), "generated result alias");
    }

    /** Internal carrier label that retains a relation join key without exposing it in GraphQL. */
    public static String hiddenRelationAlias(String relationName) {
        requireIdentifier(relationName, "GraphQL relation");
        return "__titan_relation_" + sqlAlias(relationName);
    }

    private static String identifier(String value, String label) {
        requireIdentifier(value, label);
        return value;
    }

    private static void requireIdentifier(String value, String label) {
        if (value == null || !value.matches(IDENTIFIER)) {
            throw unsupported(label + " '" + value + "' is not a portable SQL identifier");
        }
    }

    private static void requirePackage(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            throw unsupported("generated package is required");
        }
        for (String segment : packageName.split("\\.")) {
            requireIdentifier(segment, "generated package segment");
        }
    }

    private static IllegalArgumentException unsupported(String message) {
        return new IllegalArgumentException("model cannot generate Titan GraphQL routines: " + message);
    }

    private static String javaString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String javaComment(String value) {
        return value.replace("*/", "* /").replace('\n', ' ').replace('\r', ' ');
    }

    private record Parameter(String name, String javaType, String setter) {
    }

    private record PredicateContract(List<Parameter> parameters, String sql) {
    }

    private record FilterCarrierContract(
            List<Parameter> parameters,
            String joins,
            String slotJoins,
            String predicate,
            List<String> valueTypes
    ) {
    }

    private record RelationPath(
            TitanGraphqlRelationDocument relation,
            TitanGraphqlTypeDocument target,
            TitanGraphqlFieldDocument field
    ) {
    }

    private record ScalarFilterContract(
            String name,
            String binding,
            String graphqlType,
            String operator,
            boolean computed,
            int inArity
    ) {
        ScalarFilterContract withInArity(int value) {
            return new ScalarFilterContract(name, binding, graphqlType, operator, computed, value);
        }
    }

    private record SortContract(
            String binding,
            String expression,
            String graphqlType,
            String tieBreakerBinding,
            String tieBreakerExpression,
            String tieBreakerGraphqlType,
            TitanGraphqlRootDocument.RootDocumentSortDirection direction
    ) {
        boolean compound() {
            return !tieBreakerExpression.equals(expression);
        }
    }

    private static final class GenerationContext {
        private final TitanGraphqlModelDocument document;
        private final Map<String, TitanGraphqlTypeDocument> types = new LinkedHashMap<>();
        private final Map<String, TitanGraphqlContextFilterDocument> contextFilters = new LinkedHashMap<>();

        private GenerationContext(TitanGraphqlModelDocument document) {
            this.document = document;
            document.types().forEach(type -> types.put(type.name(), type));
            document.contextFilters().forEach(filter -> contextFilters.put(filter.name(), filter));
        }

        private TitanGraphqlTypeDocument type(String name) {
            TitanGraphqlTypeDocument type = types.get(name);
            if (type == null) {
                throw unsupported("unknown type '" + name + "'");
            }
            return type;
        }

        private TitanGraphqlContextFilterDocument contextFilter(String name) {
            TitanGraphqlContextFilterDocument filter = contextFilters.get(name);
            if (filter == null) {
                throw unsupported("unknown context filter '" + name + "'");
            }
            if (filter.operator() == TitanGraphqlContextFilterDocument.Operator.IN) {
                throw unsupported("context filter '" + name + "' uses unsupported IN semantics");
            }
            return filter;
        }

        private String schema(TitanGraphqlTypeDocument type) {
            if (!type.schema().isBlank()) {
                return type.schema();
            }
            return document.database().defaultSchema();
        }

        private String table(TitanGraphqlTypeDocument type) {
            if (!type.physicalTable().isBlank()) {
                return type.physicalTable();
            }
            if (!type.table().isBlank()) {
                return type.table();
            }
            throw unsupported("type '" + type.name() + "' has no physical table binding");
        }

        private String primaryKey(TitanGraphqlTypeDocument type) {
            if (!type.primaryKey().isBlank()) {
                return type.primaryKey();
            }
            return document.database().tables().stream()
                    .filter(table -> table.name().equals(type.table()))
                    .map(table -> table.primaryKey())
                    .filter(primaryKey -> !primaryKey.isBlank())
                    .findFirst()
                    .orElseThrow(() -> unsupported("type '" + type.name()
                            + "' has no primary key binding for stable ordering"));
        }

        private String graphqlType(TitanGraphqlTypeDocument type, String column) {
            String typeName = graphqlTypeOrNull(type, column);
            if (typeName != null) {
                return typeName;
            }
            throw unsupported("column '" + column + "' on type '" + type.name()
                    + "' has no scalar type binding");
        }

        private String graphqlTypeOrNull(TitanGraphqlTypeDocument type, String column) {
            for (TitanGraphqlFieldDocument field : type.fields()) {
                if (column.equals(field.column()) || column.equals(field.name())) {
                    return field.type();
                }
            }
            return null;
        }

        private String sortExpression(TitanGraphqlTypeDocument type, String binding) {
            return sortExpression(type, binding, "");
        }

        private String sortExpression(TitanGraphqlTypeDocument type, String binding, String qualifier) {
            for (TitanGraphqlFieldDocument field : type.fields()) {
                if (!binding.equals(field.column()) && !binding.equals(field.name())) {
                    continue;
                }
                return field.computed() == null
                        ? columnExpression(qualifier, field.column())
                        : computedExpression(type, field, qualifier);
            }
            throw unsupported("sort binding '" + binding + "' on type '" + type.name()
                    + "' has no scalar field binding");
        }

        private Parameter parameter(String name, String graphqlType, String column, TitanGraphqlTypeDocument type) {
            requireIdentifier(name, "generated parameter");
            String normalized = graphqlType == null ? "" : graphqlType.replace("!", "").trim();
            return switch (normalized) {
                case "Int" -> new Parameter(name, "int", "setInt");
                case "Long" -> new Parameter(name, "long", "setLong");
                case "Boolean" -> new Parameter(name, "boolean", "setBoolean");
                case "Float" -> new Parameter(name, "double", "setDouble");
                case "UUID" -> new Parameter(name, "UUID", "setObject");
                case "String", "ID", "Date", "DateTime", "Timestamp" ->
                        new Parameter(name, "String", "setString");
                default -> throw unsupported("column '" + column + "' on type '" + type.name()
                        + "' uses unsupported GraphQL parameter type '" + graphqlType + "'");
            };
        }
    }
}
