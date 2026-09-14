package io.titan.graphql.codegen;

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
            if (sort.hops() != 0) {
                continue;
            }
            String tieBreaker = sort.tieBreaker().isBlank() ? sort.column() : sort.tieBreaker();
            emitPageCarriers(source, context, type, root,
                    "Order" + javaTypeName(sort.name()) + "Asc",
                    sort.column(), tieBreaker, TitanGraphqlRootDocument.RootDocumentSortDirection.ASC);
            emitPageCarriers(source, context, type, root,
                    "Order" + javaTypeName(sort.name()) + "Desc",
                    sort.column(), tieBreaker, TitanGraphqlRootDocument.RootDocumentSortDirection.DESC);
        }
        if (pagination.totalCount() == TitanGraphqlRootDocument.TotalCountMode.EXACT) {
            PredicateContract countPredicate = rootPredicate(context, type, root, null);
            emitCarrier(source, "countRoot" + javaTypeName(root.name()),
                    countPredicate.parameters(), "SELECT COUNT(*) AS total_count FROM "
                            + identifier(context.schema(type), "schema") + "."
                            + identifier(context.table(type), "table") + countPredicate.sql());
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
        PredicateContract pagePredicate = rootPredicate(context, type, root, sort);
        List<Parameter> parameters = new ArrayList<>(pagePredicate.parameters());
        parameters.add(new Parameter("pageSize", "int", "setInt"));
        String base = selectList(context, type) + pagePredicate.sql();
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
                clauses.add("(? = FALSE OR " + identifier(argument.column(), "root argument column") + " = ?)");
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
            String column = identifier(filter.column(), "context filter column");
            clauses.add(filter.failClosed()
                    ? "(? = FALSE OR (? = TRUE AND " + column + " = ?))"
                    : "(? = FALSE OR ? = FALSE OR " + column + " = ?)");
        }
        return new PredicateContract(List.copyOf(parameters),
                clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses));
    }

    private static String cursorClause(SortContract sort, String operator) {
        if (!sort.compound()) {
            return "(? = FALSE OR " + sort.expression() + " " + operator + " ?)";
        }
        return "(? = FALSE OR (" + sort.expression() + " " + operator + " ? OR ("
                + sort.expression() + " = ? AND " + sort.tieBreakerExpression()
                + " " + operator + " ?)))";
    }

    private static SortContract sortContract(
            GenerationContext context,
            TitanGraphqlTypeDocument type,
            String binding,
            String tieBreakerBinding,
            TitanGraphqlRootDocument.RootDocumentSortDirection direction
    ) {
        String normalizedTie = tieBreakerBinding == null || tieBreakerBinding.isBlank()
                ? binding : tieBreakerBinding;
        return new SortContract(
                binding,
                context.sortExpression(type, binding),
                context.graphqlType(type, binding),
                normalizedTie,
                context.sortExpression(type, normalizedTie),
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
        StringBuilder sql = new StringBuilder(selectList(context, target))
                .append(" WHERE ").append(identifier(relation.targetColumn(), "relation target column"))
                .append(" = ?");
        appendRelationOrder(sql, context, target, relation);
        emitCarrier(source, "readRelation" + javaTypeName(owner.name()) + javaTypeName(relation.name()),
                List.of(localKey), sql.toString());
        for (int batchSize : RELATION_BATCH_SIZES) {
            List<Parameter> batchParameters = new ArrayList<>();
            for (int index = 1; index <= batchSize; index++) {
                batchParameters.add(new Parameter("localKey" + index, localKey.javaType(), localKey.setter()));
            }
            String batchSql = selectList(context, target, List.of(
                    identifier(relation.targetColumn(), "relation target column")
                            + " AS __titan_parent_key"))
                    + " WHERE " + identifier(relation.targetColumn(), "relation target column")
                    + " IN (" + String.join(", ", java.util.Collections.nCopies(batchSize, "?")) + ")";
            StringBuilder orderedBatchSql = new StringBuilder(batchSql);
            appendRelationOrder(orderedBatchSql, context, target, relation);
            emitCarrier(source, "readRelation" + javaTypeName(owner.name())
                    + javaTypeName(relation.name()) + "Batch" + batchSize,
                    batchParameters, orderedBatchSql.toString());
        }
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
        List<String> projections = new ArrayList<>();
        for (TitanGraphqlFieldDocument field : type.fields()) {
            if (!field.policies().isEmpty()) {
                continue; // Protected scalars require a policy-specific generated routine.
            }
            if (field.computed() == null) {
                projections.add(identifier(field.column(), "field column") + " AS "
                        + sqlAlias(field.name()));
            } else if (field.computed().selectable()) {
                projections.add(computedExpression(type, field) + " AS "
                        + sqlAlias(field.name()));
            }
        }
        for (TitanGraphqlRelationDocument relation : type.relations()) {
            if (relation.policies().isEmpty()) {
                projections.add(identifier(relation.localColumn(), "relation local column") + " AS "
                        + hiddenRelationAlias(relation.name()));
            }
        }
        projections.addAll(extraProjections);
        if (projections.isEmpty()) {
            throw unsupported("type '" + type.name() + "' has no unprotected selectable scalar fields");
        }
        return "SELECT " + String.join(", ", projections) + " FROM "
                + identifier(context.schema(type), "schema") + "."
                + identifier(context.table(type), "table");
    }

    private static String computedExpression(TitanGraphqlTypeDocument type, TitanGraphqlFieldDocument field) {
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
                    identifier(required.column(), "computed required column"));
        }
        if (expression.contains("{") || !expression.matches("[A-Za-z0-9_().,+*/% -]+")) {
            throw unsupported("computed field '" + type.name() + "." + field.name()
                    + "' has an unsupported SQL template");
        }
        return expression;
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
            for (TitanGraphqlFieldDocument field : type.fields()) {
                if (!binding.equals(field.column()) && !binding.equals(field.name())) {
                    continue;
                }
                return field.computed() == null
                        ? identifier(field.column(), "sort column")
                        : computedExpression(type, field);
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
