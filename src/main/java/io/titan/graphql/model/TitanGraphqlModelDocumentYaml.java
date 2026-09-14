package io.titan.graphql.model;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TitanGraphqlModelDocumentYaml {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> TOP_LEVEL_FIELDS = List.of(
            "apiVersion",
            "kind",
            "metadata",
            "database",
            "modules",
            "roots",
            "types",
            "policies",
            "contextFilters",
            "artifacts",
            "deployment"
    );

    private TitanGraphqlModelDocumentYaml() {
    }

    public static TitanGraphqlModelDocument parse(String source) {
        if (source == null || source.isBlank()) {
            throw error("EMPTY_DOCUMENT", "titan.graphql.yaml is empty");
        }
        JsonNode root;
        try {
            root = YAML.readTree(source);
        } catch (JsonProcessingException ex) {
            JsonLocation location = ex.getLocation();
            throw new TitanGraphqlModelDocumentYamlException(
                    "INVALID_YAML",
                    "titan.graphql.yaml is not valid YAML: " + ex.getOriginalMessage(),
                    line(location),
                    column(location),
                    ex
            );
        }
        if (root == null || !root.isObject()) {
            throw error("INVALID_DOCUMENT", "titan.graphql.yaml must contain a mapping document");
        }
        rejectUnsupportedTopLevelFields(root);
        try {
            return new TitanGraphqlModelDocument(
                    text(root, "apiVersion"),
                    text(root, "kind"),
                    metadata(required(root, "metadata")),
                    database(root.path("database")),
                    modules(root.path("modules")),
                    roots(root.path("roots")),
                    types(root.path("types")),
                    policies(root.path("policies")),
                    contextFilters(root.path("contextFilters")),
                    artifacts(root.path("artifacts")),
                    deployment(root.path("deployment"))
            );
        } catch (TitanGraphqlModelDocumentYamlException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw new TitanGraphqlModelDocumentYamlException(
                    "INVALID_MODEL_DOCUMENT",
                    ex.getMessage(),
                    -1,
                    -1,
                    ex
            );
        }
    }

    private static void rejectUnsupportedTopLevelFields(JsonNode root) {
        Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!TOP_LEVEL_FIELDS.contains(field)) {
                throw error("UNSUPPORTED_FIELD", "unsupported top-level field '" + field + "'");
            }
        }
    }

    private static TitanGraphqlModelMetadata metadata(JsonNode node) {
        return new TitanGraphqlModelMetadata(
                text(node, "name"),
                text(node, "version"),
                text(node, "owner"),
                text(node, "description"),
                stringList(node.path("tags"))
        );
    }

    private static TitanGraphqlDatabaseDocument database(JsonNode node) {
        if (missing(node)) {
            return TitanGraphqlDatabaseDocument.empty();
        }
        List<TitanGraphqlDatabaseDocument.TableBinding> tables = new ArrayList<>();
        JsonNode tableNodes = node.path("tables");
        fields(tableNodes).forEach(entry -> tables.add(new TitanGraphqlDatabaseDocument.TableBinding(
                entry.getKey(),
                text(entry.getValue(), "physicalName"),
                text(entry.getValue(), "schema"),
                text(entry.getValue(), "primaryKey")
        )));
        return new TitanGraphqlDatabaseDocument(text(node, "catalog"), text(node, "defaultSchema"), tables);
    }

    private static List<TitanGraphqlModuleDocument> modules(JsonNode node) {
        List<TitanGraphqlModuleDocument> modules = new ArrayList<>();
        fields(node).forEach(entry -> modules.add(new TitanGraphqlModuleDocument(
                entry.getKey(),
                text(entry.getValue(), "owner"),
                stringList(entry.getValue().path("roots")),
                stringList(entry.getValue().path("types")),
                stringList(entry.getValue().path("fields")),
                stringList(entry.getValue().path("relations")),
                stringList(entry.getValue().path("policies"))
        )));
        return modules;
    }

    private static List<TitanGraphqlRootDocument> roots(JsonNode node) {
        List<TitanGraphqlRootDocument> roots = new ArrayList<>();
        fields(node).forEach(entry -> {
            JsonNode value = entry.getValue();
            roots.add(new TitanGraphqlRootDocument(
                    entry.getKey(),
                    text(value, "type"),
                    enumValue(TitanGraphqlRootDocument.RootDocumentOperation.class, text(value, "operation"), "root.operation"),
                    argument(value.path("argument"), TitanGraphqlRootDocument.RootDocumentArgumentKind.EQUALS),
                    rootPagination(value.path("pagination")),
                    rootArguments(value.path("arguments")),
                    rootFilterPaths(value.path("filterPaths")),
                    rootSortPaths(value.path("sortPaths")),
                    stringList(value.path("contextFilters")),
                    stringList(value.path("policies"))
            ));
        });
        return roots;
    }

    private static TitanGraphqlRootDocument.RootDocumentPagination rootPagination(JsonNode node) {
        if (missing(node)) {
            return null;
        }
        return new TitanGraphqlRootDocument.RootDocumentPagination(
                intValue(node, "defaultPageSize"),
                intValue(node, "maxPageSize"),
                enumValue(TitanGraphqlRootDocument.TotalCountMode.class, text(node, "totalCount"), "root.pagination.totalCount"),
                rootCursor(node.path("cursor"))
        );
    }

    private static TitanGraphqlRootDocument.Cursor rootCursor(JsonNode node) {
        if (missing(node)) {
            return null;
        }
        return new TitanGraphqlRootDocument.Cursor(
                text(node, "path"),
                text(node, "column"),
                enumValue(TitanGraphqlRootDocument.RootDocumentSortDirection.class, text(node, "direction"), "root.pagination.cursor.direction"),
                text(node, "tieBreaker")
        );
    }

    private static List<TitanGraphqlRootDocument.RootDocumentArgument> rootArguments(JsonNode node) {
        List<TitanGraphqlRootDocument.RootDocumentArgument> arguments = new ArrayList<>();
        fields(node).forEach(entry -> arguments.add(argument(entry.getKey(), entry.getValue())));
        return arguments;
    }

    private static TitanGraphqlRootDocument.RootDocumentArgument argument(JsonNode node, TitanGraphqlRootDocument.RootDocumentArgumentKind defaultKind) {
        if (missing(node)) {
            return null;
        }
        return new TitanGraphqlRootDocument.RootDocumentArgument(
                text(node, "name"),
                text(node, "type"),
                enumValue(TitanGraphqlRootDocument.RootDocumentArgumentKind.class, defaultText(text(node, "kind"), canonical(defaultKind.name())), "root.argument.kind"),
                text(node, "column"),
                text(node, "path"),
                intValue(node, "hops")
        );
    }

    private static TitanGraphqlRootDocument.RootDocumentArgument argument(String name, JsonNode node) {
        return new TitanGraphqlRootDocument.RootDocumentArgument(
                name,
                text(node, "type"),
                enumValue(TitanGraphqlRootDocument.RootDocumentArgumentKind.class, text(node, "kind"), "root.argument.kind"),
                text(node, "column"),
                text(node, "path"),
                intValue(node, "hops")
        );
    }

    private static List<TitanGraphqlRootDocument.RootDocumentFilterPath> rootFilterPaths(JsonNode node) {
        List<TitanGraphqlRootDocument.RootDocumentFilterPath> paths = new ArrayList<>();
        fields(node).forEach(entry -> paths.add(new TitanGraphqlRootDocument.RootDocumentFilterPath(
                entry.getKey(),
                text(entry.getValue(), "type"),
                text(entry.getValue(), "column"),
                text(entry.getValue(), "path"),
                intValue(entry.getValue(), "hops"),
                stringList(entry.getValue().path("operators"))
        )));
        return paths;
    }

    private static List<TitanGraphqlRootDocument.RootDocumentSortPath> rootSortPaths(JsonNode node) {
        List<TitanGraphqlRootDocument.RootDocumentSortPath> paths = new ArrayList<>();
        fields(node).forEach(entry -> paths.add(new TitanGraphqlRootDocument.RootDocumentSortPath(
                entry.getKey(),
                text(entry.getValue(), "column"),
                text(entry.getValue(), "path"),
                enumValue(TitanGraphqlRootDocument.RootDocumentSortDirection.class, text(entry.getValue(), "direction"), "root.sortPath.direction"),
                enumValue(TitanGraphqlRootDocument.RootDocumentNullOrdering.class, text(entry.getValue(), "nulls"), "root.sortPath.nulls"),
                text(entry.getValue(), "tieBreaker"),
                intValue(entry.getValue(), "hops")
        )));
        return paths;
    }

    private static List<TitanGraphqlTypeDocument> types(JsonNode node) {
        List<TitanGraphqlTypeDocument> types = new ArrayList<>();
        fields(node).forEach(entry -> types.add(new TitanGraphqlTypeDocument(
                entry.getKey(),
                text(entry.getValue(), "table"),
                text(entry.getValue(), "schema"),
                text(entry.getValue(), "physicalTable"),
                text(entry.getValue(), "primaryKey"),
                fieldsDocuments(entry.getValue().path("fields")),
                relations(entry.getValue().path("relations")),
                stringList(entry.getValue().path("policies"))
        )));
        return types;
    }

    private static List<TitanGraphqlFieldDocument> fieldsDocuments(JsonNode node) {
        List<TitanGraphqlFieldDocument> fields = new ArrayList<>();
        fields(node).forEach(entry -> fields.add(field(entry.getKey(), entry.getValue())));
        return fields;
    }

    private static TitanGraphqlFieldDocument field(String name, JsonNode node) {
        return new TitanGraphqlFieldDocument(
                name,
                text(node, "type"),
                text(node, "column"),
                boolValue(node, "nullable"),
                fieldPolicies(node),
                stringList(node.path("filter").path("operators")),
                fieldSort(node.path("sort"), name),
                computed(node)
        );
    }

    private static List<String> fieldPolicies(JsonNode node) {
        List<String> policies = stringList(node.path("policies"));
        String policy = text(node, "policy");
        if (!policy.isBlank()) {
            List<String> merged = new ArrayList<>(policies);
            merged.add(policy);
            return merged;
        }
        return policies;
    }

    private static TitanGraphqlFieldDocument.Sort fieldSort(JsonNode node, String defaultPath) {
        if (missing(node)) {
            return null;
        }
        return new TitanGraphqlFieldDocument.Sort(
                defaultText(text(node, "path"), defaultPath),
                enumValue(TitanGraphqlFieldDocument.FieldDocumentSortDirection.class, text(node, "default"), "field.sort.default"),
                enumValue(TitanGraphqlFieldDocument.FieldDocumentNullOrdering.class, text(node, "nulls"), "field.sort.nulls"),
                text(node, "tieBreaker")
        );
    }

    private static TitanGraphqlFieldDocument.Computed computed(JsonNode fieldNode) {
        JsonNode node = fieldNode.path("computed");
        if (missing(node)) {
            return null;
        }
        return new TitanGraphqlFieldDocument.Computed(
                enumValue(TitanGraphqlFieldDocument.FieldDocumentExpressionKind.class, text(node, "kind"), "field.computed.kind"),
                text(node, "template"),
                !fieldNode.has("selectable") || fieldNode.path("selectable").asBoolean(),
                !missing(fieldNode.path("filter")),
                !missing(fieldNode.path("sort")),
                boolValue(node, "deterministic"),
                boolValue(node, "sensitive"),
                stringList(node.path("requiredColumns")),
                enumValue(TitanGraphqlFieldDocument.FieldDocumentCostClass.class, text(node, "costClass"), "field.computed.costClass")
        );
    }

    private static List<TitanGraphqlRelationDocument> relations(JsonNode node) {
        List<TitanGraphqlRelationDocument> relations = new ArrayList<>();
        fields(node).forEach(entry -> {
            JsonNode value = entry.getValue();
            JsonNode capabilities = value.path("capabilities");
            relations.add(new TitanGraphqlRelationDocument(
                    entry.getKey(),
                    defaultText(text(value, "targetType"), text(value, "target")),
                    text(value, "localColumn"),
                    text(value, "targetColumn"),
                    enumValue(TitanGraphqlRelationDocument.RelationDocumentCardinality.class, text(value, "cardinality"), "relation.cardinality"),
                    boolValue(value, "nullable"),
                    relationPagination(capabilities),
                    relationArguments(value.path("arguments")),
                    relationSortPaths(value.path("sortPaths")),
                    stringList(value.path("policies"))
            ));
        });
        return relations;
    }

    private static TitanGraphqlRelationDocument.RelationDocumentPagination relationPagination(JsonNode capabilities) {
        if (missing(capabilities)) {
            return null;
        }
        return new TitanGraphqlRelationDocument.RelationDocumentPagination(
                enumValue(TitanGraphqlRelationDocument.RelationDocumentPaginationMode.class, text(capabilities, "pagination"), "relation.capabilities.pagination"),
                intValue(capabilities, "defaultPageSize"),
                intValue(capabilities, "maxPageSize"),
                "exact".equalsIgnoreCase(text(capabilities, "totalCount"))
        );
    }

    private static List<TitanGraphqlRelationDocument.RelationDocumentArgument> relationArguments(JsonNode node) {
        List<TitanGraphqlRelationDocument.RelationDocumentArgument> arguments = new ArrayList<>();
        fields(node).forEach(entry -> arguments.add(new TitanGraphqlRelationDocument.RelationDocumentArgument(
                entry.getKey(),
                text(entry.getValue(), "type"),
                enumValue(TitanGraphqlRelationDocument.RelationDocumentArgumentKind.class, text(entry.getValue(), "kind"), "relation.argument.kind"),
                text(entry.getValue(), "column"),
                text(entry.getValue(), "path"),
                intValue(entry.getValue(), "hops")
        )));
        return arguments;
    }

    private static List<TitanGraphqlRelationDocument.RelationDocumentSortPath> relationSortPaths(JsonNode node) {
        List<TitanGraphqlRelationDocument.RelationDocumentSortPath> paths = new ArrayList<>();
        fields(node).forEach(entry -> paths.add(new TitanGraphqlRelationDocument.RelationDocumentSortPath(
                entry.getKey(),
                text(entry.getValue(), "column"),
                text(entry.getValue(), "path"),
                enumValue(TitanGraphqlRelationDocument.RelationDocumentSortDirection.class, text(entry.getValue(), "direction"), "relation.sortPath.direction"),
                text(entry.getValue(), "tieBreaker"),
                intValue(entry.getValue(), "hops")
        )));
        return paths;
    }

    private static List<TitanGraphqlPolicyDocument> policies(JsonNode node) {
        List<TitanGraphqlPolicyDocument> policies = new ArrayList<>();
        fields(node).forEach(entry -> policies.add(new TitanGraphqlPolicyDocument(
                entry.getKey(),
                text(entry.getValue(), "description"),
                "reject".equalsIgnoreCase(text(entry.getValue(), "mode"))
                        ? TitanGraphqlPolicyDocument.Effect.DENY
                        : TitanGraphqlPolicyDocument.Effect.ALLOW,
                stringList(entry.getValue().path("appliesTo")),
                policyExpression(entry.getValue().path("expression"))
        )));
        return policies;
    }

    private static String policyExpression(JsonNode node) {
        if (missing(node)) {
            return "";
        }
        String name = text(node, "name");
        if (!name.isBlank()) {
            return name;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw error("INVALID_MODEL_DOCUMENT", "policy expression could not be normalized");
        }
    }

    private static List<TitanGraphqlContextFilterDocument> contextFilters(JsonNode node) {
        List<TitanGraphqlContextFilterDocument> filters = new ArrayList<>();
        fields(node).forEach(entry -> filters.add(new TitanGraphqlContextFilterDocument(
                entry.getKey(),
                text(entry.getValue(), "column"),
                text(entry.getValue(), "contextKey"),
                enumValue(TitanGraphqlContextFilterDocument.Operator.class, defaultText(text(entry.getValue(), "operator"), text(entry.getValue(), "type")), "contextFilter.type"),
                "beforeClientFilters".equalsIgnoreCase(text(entry.getValue(), "phase")),
                boolValue(entry.getValue(), "failClosed")
        )));
        return filters;
    }

    private static TitanGraphqlArtifactOptions artifacts(JsonNode node) {
        if (missing(node)) {
            return TitanGraphqlArtifactOptions.defaults();
        }
        String outputDirectory = firstPath(node.path("generatedSchema"), node.path("introspection"), node.path("conformance"), node.path("sql"));
        return new TitanGraphqlArtifactOptions(
                enabled(node.path("generatedSchema")),
                enabled(node.path("introspection")),
                enabled(node.path("conformance")),
                enabled(node.path("sql")),
                outputDirectory
        );
    }

    private static String firstPath(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            String path = text(node, "path");
            if (!path.isBlank()) {
                int slash = path.lastIndexOf('/');
                return slash > 0 ? path.substring(0, slash) : path;
            }
        }
        return "";
    }

    private static boolean enabled(JsonNode node) {
        return missing(node) || !node.has("enabled") || node.path("enabled").asBoolean();
    }

    private static TitanGraphqlDeploymentDocument deployment(JsonNode node) {
        if (missing(node)) {
            return TitanGraphqlDeploymentDocument.empty();
        }
        return new TitanGraphqlDeploymentDocument(
                text(node, "environment"),
                text(node.path("preview"), "route"),
                text(node.path("runtime"), "endpoint"),
                stringList(node.path("requiredApprovals"))
        );
    }

    private static List<Map.Entry<String, JsonNode>> fields(JsonNode node) {
        if (missing(node)) {
            return List.of();
        }
        if (!node.isObject()) {
            throw error("INVALID_MODEL_DOCUMENT", "expected mapping node");
        }
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        node.fields().forEachRemaining(entries::add);
        return entries;
    }

    private static List<String> stringList(JsonNode node) {
        if (missing(node)) {
            return List.of();
        }
        if (!node.isArray()) {
            throw error("INVALID_MODEL_DOCUMENT", "expected string list");
        }
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (missing(value)) {
            throw error("MISSING_REQUIRED_FIELD", field + " is required");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        if (missing(node) || !node.has(field) || node.path(field).isNull()) {
            return "";
        }
        return node.path(field).asText();
    }

    private static int intValue(JsonNode node, String field) {
        if (missing(node) || !node.has(field) || node.path(field).isNull()) {
            return 0;
        }
        return node.path(field).asInt();
    }

    private static boolean boolValue(JsonNode node, String field) {
        return !missing(node) && node.has(field) && node.path(field).asBoolean();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = normalizeEnum(value);
        for (E candidate : type.getEnumConstants()) {
            if (normalizeEnum(candidate.name()).equals(normalized)) {
                return candidate;
            }
        }
        throw error("UNKNOWN_ENUM_VALUE", "unknown " + fieldName + " value '" + value + "'");
    }

    private static String normalizeEnum(String value) {
        return value.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static String canonical(String enumName) {
        String lower = enumName.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        boolean uppercaseNext = false;
        for (int index = 0; index < lower.length(); index++) {
            char current = lower.charAt(index);
            if (current == '_') {
                uppercaseNext = true;
            } else if (uppercaseNext) {
                out.append(Character.toUpperCase(current));
                uppercaseNext = false;
            } else {
                out.append(current);
            }
        }
        return out.toString();
    }

    private static String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static boolean missing(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    private static int line(JsonLocation location) {
        return location == null ? -1 : (int) location.getLineNr();
    }

    private static int column(JsonLocation location) {
        return location == null ? -1 : (int) location.getColumnNr();
    }

    private static TitanGraphqlModelDocumentYamlException error(String code, String message) {
        return new TitanGraphqlModelDocumentYamlException(code, message, -1, -1, null);
    }
}
