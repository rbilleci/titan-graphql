package io.titan.graphql.inference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Public bridge from Titan codegen's {@code schema.json} to a conservative GraphQL model draft. */
public final class TitanGraphqlSchemaInference {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TitanGraphqlSchemaInference() {
    }

    public static InferenceResult infer(Path codegenSchemaJson, String catalog) {
        try {
            return infer(Files.readString(codegenSchemaJson), catalog);
        } catch (IOException ex) {
            throw new IllegalArgumentException("could not read Titan codegen schema "
                    + codegenSchemaJson.toAbsolutePath(), ex);
        }
    }

    public static InferenceResult infer(String codegenSchemaJson, String catalog) {
        JsonNode root;
        try {
            root = JSON.readTree(codegenSchemaJson);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Titan codegen schema.json is not valid JSON", ex);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Titan codegen schema.json must be a JSON object");
        }
        TitanGraphqlCatalogSnapshot snapshot = snapshot(root, catalog);
        TitanGraphqlCatalogModelInferenceResult inferred =
                TitanGraphqlCatalogModelInference.inferConservativeDraftResult(snapshot);
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (TitanGraphqlCatalogInferenceReport.SkippedObject skipped : inferred.report().skippedObjects()) {
            diagnostics.add(new Diagnostic("SKIPPED", skipped.kind(), skipped.path(), skipped.reason()));
        }
        for (TitanGraphqlCatalogInferenceReport.Warning warning : inferred.report().warnings()) {
            diagnostics.add(new Diagnostic("WARNING", warning.code(), warning.path(), warning.message()));
        }
        for (TitanGraphqlCatalogInferenceReport.ReviewDecision decision : inferred.report().reviewDecisions()) {
            diagnostics.add(new Diagnostic("REVIEW", decision.code(), decision.path(), decision.message()));
        }
        return new InferenceResult(inferred.document(), diagnostics);
    }

    private static TitanGraphqlCatalogSnapshot snapshot(JsonNode root, String catalog) {
        Map<String, List<TitanGraphqlCatalogSnapshot.Table>> bySchema = new LinkedHashMap<>();
        appendOwners(root.path("tables"), TitanGraphqlCatalogSnapshot.TableKind.TABLE, bySchema);
        appendOwners(root.path("views"), TitanGraphqlCatalogSnapshot.TableKind.VIEW, bySchema);
        List<TitanGraphqlCatalogSnapshot.Schema> schemas = new ArrayList<>();
        bySchema.forEach((name, tables) -> schemas.add(
                new TitanGraphqlCatalogSnapshot.Schema(name, "", tables)));
        return new TitanGraphqlCatalogSnapshot(catalog == null ? "" : catalog, schemas);
    }

    private static void appendOwners(
            JsonNode owners,
            TitanGraphqlCatalogSnapshot.TableKind kind,
            Map<String, List<TitanGraphqlCatalogSnapshot.Table>> bySchema
    ) {
        if (!owners.isArray()) {
            return;
        }
        for (JsonNode owner : owners) {
            String schema = text(owner, "schema");
            String name = required(owner, "name", kind.name().toLowerCase());
            List<TitanGraphqlCatalogSnapshot.Column> columns = new ArrayList<>();
            int ordinal = 1;
            for (JsonNode column : owner.path("columns")) {
                columns.add(new TitanGraphqlCatalogSnapshot.Column(
                        required(column, "name", "column"),
                        required(column, "sqlType", "column"),
                        column.path("nullable").asBoolean(true),
                        ordinal++,
                        ""
                ));
            }
            TitanGraphqlCatalogSnapshot.PrimaryKey primaryKey = null;
            for (JsonNode constraint : owner.path("constraints")) {
                if ("PRIMARY_KEY".equals(text(constraint, "type"))) {
                    primaryKey = new TitanGraphqlCatalogSnapshot.PrimaryKey(
                            text(constraint, "name"), strings(constraint.path("columns")));
                    break;
                }
            }
            List<TitanGraphqlCatalogSnapshot.ForeignKey> foreignKeys = new ArrayList<>();
            for (JsonNode foreignKey : owner.path("foreignKeys")) {
                foreignKeys.add(new TitanGraphqlCatalogSnapshot.ForeignKey(
                        text(foreignKey, "name"),
                        strings(foreignKey.path("columns")),
                        required(foreignKey, "referencedSchema", "foreign key"),
                        required(foreignKey, "referencedTable", "foreign key"),
                        strings(foreignKey.path("referencedColumns")),
                        ""
                ));
            }
            List<TitanGraphqlCatalogSnapshot.Index> indexes = new ArrayList<>();
            for (JsonNode index : owner.path("indexes")) {
                indexes.add(new TitanGraphqlCatalogSnapshot.Index(
                        required(index, "name", "index"),
                        strings(index.path("columns")),
                        index.path("unique").asBoolean(false),
                        ""
                ));
            }
            bySchema.computeIfAbsent(schema, ignored -> new ArrayList<>()).add(
                    new TitanGraphqlCatalogSnapshot.Table(
                            schema, name, kind, "", columns, primaryKey, foreignKeys, indexes));
        }
    }

    private static String required(JsonNode node, String field, String owner) {
        String value = text(node, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Titan codegen " + owner + " requires '" + field + "'");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : "";
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode value : node) {
                if (value.isTextual()) values.add(value.asText());
            }
        }
        return values;
    }

    public record InferenceResult(TitanGraphqlModelDocument document, List<Diagnostic> diagnostics) {
        public InferenceResult {
            if (document == null) throw new IllegalArgumentException("document is required");
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    public record Diagnostic(String severity, String code, String path, String message) {
    }
}
