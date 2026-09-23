package io.titan.graphql.inference;

import io.titan.graphql.model.TitanGraphqlArtifactOptions;
import io.titan.graphql.model.TitanGraphqlDatabaseDocument;
import io.titan.graphql.model.TitanGraphqlDeploymentDocument;
import io.titan.graphql.model.TitanGraphqlFieldDocument;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelMetadata;
import io.titan.graphql.model.TitanGraphqlPolicyDocument;
import io.titan.graphql.model.TitanGraphqlRelationDocument;
import io.titan.graphql.model.TitanGraphqlTypeDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TitanGraphqlCatalogModelInference {

    static final String RELATION_REVIEW_POLICY = "reviewRequiredRelationCandidate";
    static final String SENSITIVE_FIELD_REVIEW_POLICY = "reviewRequiredSensitiveField";

    private TitanGraphqlCatalogModelInference() {
    }

    static TitanGraphqlModelDocument inferConservativeDraft(TitanGraphqlCatalogSnapshot snapshot) {
        return inferConservativeDraftResult(snapshot).document();
    }

    static TitanGraphqlCatalogModelInferenceResult inferConservativeDraftResult(TitanGraphqlCatalogSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot is required");
        }

        List<TitanGraphqlDatabaseDocument.TableBinding> tableBindings = new ArrayList<>();
        List<TitanGraphqlTypeDocument> types = new ArrayList<>();
        List<TitanGraphqlCatalogInferenceReport.InferredObject> inferredObjects = new ArrayList<>();
        List<TitanGraphqlCatalogInferenceReport.SkippedObject> skippedObjects = new ArrayList<>();
        List<TitanGraphqlCatalogInferenceReport.Warning> warnings = new ArrayList<>();
        List<TitanGraphqlCatalogInferenceReport.ReviewDecision> reviewDecisions = new ArrayList<>();
        boolean hasRelationCandidates = false;
        boolean hasSensitiveFields = false;

        for (TitanGraphqlCatalogSnapshot.Schema schema : snapshot.schemas()) {
            inferredObjects.add(new TitanGraphqlCatalogInferenceReport.InferredObject(
                    "SCHEMA",
                    schema.name(),
                    "database.defaultSchema=" + defaultSchema(snapshot),
                    "schema is available for draft table bindings"
            ));
            for (TitanGraphqlCatalogSnapshot.Table table : schema.tables()) {
                String tablePath = table.schema() + "." + table.name();
                if (table.kind() != TitanGraphqlCatalogSnapshot.TableKind.TABLE) {
                    skippedObjects.add(new TitanGraphqlCatalogInferenceReport.SkippedObject(
                            table.kind().name(),
                            tablePath,
                            "only base tables are scaffolded in v1alpha1 inference"
                    ));
                    continue;
                }
                if (table.columns().isEmpty()) {
                    skippedObjects.add(new TitanGraphqlCatalogInferenceReport.SkippedObject(
                            "TABLE",
                            tablePath,
                            "table has no columns to expose in a review draft"
                    ));
                    continue;
                }
                String typeName = typeName(table.name());
                String tableBindingName = table.name();
                String primaryKey = primaryKeyColumn(table);
                if (hasCompositePrimaryKey(table)) {
                    warnings.add(new TitanGraphqlCatalogInferenceReport.Warning(
                            "COMPOSITE_PRIMARY_KEY_REQUIRES_EXPLICIT_ROOT",
                            tablePath,
                            "all key columns were discovered, but the current point-root contract accepts one key; "
                                    + "do not expose a point root until an explicit key mapping is configured"
                    ));
                } else if (primaryKey.isBlank()) {
                    warnings.add(new TitanGraphqlCatalogInferenceReport.Warning(
                            "MISSING_PRIMARY_KEY",
                            tablePath,
                            "table was scaffolded without a primary key; root exposure and relation review must stay disabled"
                    ));
                } else {
                    inferredObjects.add(new TitanGraphqlCatalogInferenceReport.InferredObject(
                            "PRIMARY_KEY",
                            tablePath + "." + primaryKey,
                            typeName + ".primaryKey",
                            "primary key column was preserved in the draft"
                    ));
                }
                tableBindings.add(new TitanGraphqlDatabaseDocument.TableBinding(
                        tableBindingName,
                        table.name(),
                        table.schema(),
                        primaryKey
                ));

                List<TitanGraphqlFieldDocument> fields = new ArrayList<>();
                for (TitanGraphqlCatalogSnapshot.Column column : table.columns()) {
                    List<String> fieldPolicies = sensitiveField(column.name())
                            ? List.of(SENSITIVE_FIELD_REVIEW_POLICY)
                            : List.of();
                    hasSensitiveFields = hasSensitiveFields || !fieldPolicies.isEmpty();
                    String graphqlScalar = graphqlScalar(column.databaseType());
                    if (unknownScalar(column.databaseType())) {
                        warnings.add(new TitanGraphqlCatalogInferenceReport.Warning(
                                "UNKNOWN_DATABASE_TYPE",
                                tablePath + "." + column.name(),
                                "database type '" + column.databaseType() + "' was mapped conservatively to String"
                        ));
                    }
                    if (!fieldPolicies.isEmpty()) {
                        reviewDecisions.add(new TitanGraphqlCatalogInferenceReport.ReviewDecision(
                                "SENSITIVE_FIELD_REVIEW_REQUIRED",
                                typeName + "." + fieldName(column.name()),
                                "sensitive-looking field was scaffolded behind a deny policy placeholder"
                        ));
                    }
                    fields.add(new TitanGraphqlFieldDocument(
                            fieldName(column.name()),
                            graphqlScalar,
                            column.name(),
                            column.nullable(),
                            fieldPolicies,
                            List.of(),
                            null,
                            null
                    ));
                    inferredObjects.add(new TitanGraphqlCatalogInferenceReport.InferredObject(
                            "COLUMN",
                            tablePath + "." + column.name(),
                            typeName + "." + fieldName(column.name()),
                            "column was scaffolded as a scalar field"
                    ));
                }

                List<TitanGraphqlRelationDocument> relations = new ArrayList<>();
                for (TitanGraphqlCatalogSnapshot.ForeignKey foreignKey : table.foreignKeys()) {
                    if (foreignKey.columns().size() != 1 || foreignKey.targetColumns().size() != 1) {
                        warnings.add(new TitanGraphqlCatalogInferenceReport.Warning(
                                "COMPOSITE_FOREIGN_KEY_REQUIRES_EXPLICIT_RELATION",
                                tablePath + "." + foreignKey.name(),
                                "all foreign-key columns were discovered, but a multi-column relation cannot be "
                                        + "reduced to its first column; configure the relation explicitly"
                        ));
                        reviewDecisions.add(new TitanGraphqlCatalogInferenceReport.ReviewDecision(
                                "RELATION_NOT_SCAFFOLDED",
                                typeName + "." + relationName(foreignKey),
                                "composite foreign key requires an explicit relation mapping"
                        ));
                        continue;
                    }
                    String relationName = relationName(foreignKey);
                    relations.add(new TitanGraphqlRelationDocument(
                            relationName,
                            typeName(foreignKey.targetTable()),
                            first(foreignKey.columns()),
                            first(foreignKey.targetColumns()),
                            TitanGraphqlRelationDocument.RelationDocumentCardinality.ONE,
                            true,
                            null,
                            List.of(),
                            List.of(),
                            List.of(RELATION_REVIEW_POLICY),
                            2
                    ));
                    hasRelationCandidates = true;
                    inferredObjects.add(new TitanGraphqlCatalogInferenceReport.InferredObject(
                            "FOREIGN_KEY",
                            tablePath + "." + foreignKey.name(),
                            typeName + "." + relationName,
                            "foreign key was scaffolded as a review-required relation candidate"
                    ));
                    reviewDecisions.add(new TitanGraphqlCatalogInferenceReport.ReviewDecision(
                            "RELATION_REVIEW_REQUIRED",
                            typeName + "." + relationName,
                            "foreign-key relation candidate uses a deny policy placeholder until reviewed"
                    ));
                }

                types.add(new TitanGraphqlTypeDocument(
                        typeName,
                        tableBindingName,
                        table.schema(),
                        table.name(),
                        primaryKey,
                        fields,
                        relations
                ));
                inferredObjects.add(new TitanGraphqlCatalogInferenceReport.InferredObject(
                        "TABLE",
                        tablePath,
                        "type " + typeName,
                        "table was scaffolded as a draft object type with no public root"
                ));
                reviewDecisions.add(new TitanGraphqlCatalogInferenceReport.ReviewDecision(
                        "PUBLIC_ROOTS_DISABLED",
                        typeName,
                        "public roots remain disabled by default for inferred tables"
                ));
            }
        }

        List<TitanGraphqlPolicyDocument> policies = new ArrayList<>();
        if (hasRelationCandidates) {
            policies.add(reviewPolicy(
                    RELATION_REVIEW_POLICY,
                    "Placeholder policy for inferred foreign-key relation candidates that require review."
            ));
            inferredObjects.add(new TitanGraphqlCatalogInferenceReport.InferredObject(
                    "POLICY",
                    RELATION_REVIEW_POLICY,
                    "deny placeholder",
                    "policy protects inferred relation candidates until review"
            ));
        }
        if (hasSensitiveFields) {
            policies.add(reviewPolicy(
                    SENSITIVE_FIELD_REVIEW_POLICY,
                    "Placeholder policy for sensitive-looking inferred fields that require review."
            ));
            inferredObjects.add(new TitanGraphqlCatalogInferenceReport.InferredObject(
                    "POLICY",
                    SENSITIVE_FIELD_REVIEW_POLICY,
                    "deny placeholder",
                    "policy protects sensitive-looking fields until review"
            ));
        }

        reviewDecisions.add(new TitanGraphqlCatalogInferenceReport.ReviewDecision(
                "YAML_EXPORT_BLOCKED",
                "titan.graphql.yaml",
                "canonical YAML export is not implemented yet; use canonical JSON or keep the generated draft in memory"
        ));

        TitanGraphqlModelDocument document = new TitanGraphqlModelDocument(
                TitanGraphqlModelDocument.CURRENT_API_VERSION,
                TitanGraphqlModelDocument.PROJECTION_MODEL_KIND,
                new TitanGraphqlModelMetadata(
                        modelName(snapshot),
                        "",
                        "",
                        "Conservative review draft inferred from catalog snapshot.",
                        List.of("inferred", "review-required")
                ),
                new TitanGraphqlDatabaseDocument(snapshot.catalog(), defaultSchema(snapshot), tableBindings),
                List.of(),
                List.of(),
                types,
                policies,
                List.of(),
                TitanGraphqlArtifactOptions.defaults(),
                TitanGraphqlDeploymentDocument.empty()
        );
        return new TitanGraphqlCatalogModelInferenceResult(
                document,
                new TitanGraphqlCatalogInferenceReport(inferredObjects, skippedObjects, warnings, reviewDecisions)
        );
    }

    private static TitanGraphqlPolicyDocument reviewPolicy(String name, String description) {
        return new TitanGraphqlPolicyDocument(
                name,
                description,
                TitanGraphqlPolicyDocument.Effect.DENY,
                List.of(),
                "review_required"
        );
    }

    private static String modelName(TitanGraphqlCatalogSnapshot snapshot) {
        if (!snapshot.catalog().isBlank()) {
            return lowerCamel(snapshot.catalog()) + "Draft";
        }
        if (!snapshot.schemas().isEmpty()) {
            return lowerCamel(snapshot.schemas().get(0).name()) + "Draft";
        }
        return "inferredDraft";
    }

    private static String defaultSchema(TitanGraphqlCatalogSnapshot snapshot) {
        return snapshot.schemas().isEmpty() ? "" : snapshot.schemas().get(0).name();
    }

    private static String primaryKeyColumn(TitanGraphqlCatalogSnapshot.Table table) {
        return table.primaryKey() == null || table.primaryKey().columns().size() != 1
                ? "" : table.primaryKey().columns().getFirst();
    }

    private static boolean hasCompositePrimaryKey(TitanGraphqlCatalogSnapshot.Table table) {
        return table.primaryKey() != null && table.primaryKey().columns().size() > 1;
    }

    private static String first(List<String> values) {
        return values.isEmpty() ? "" : values.get(0);
    }

    private static String typeName(String databaseName) {
        String singular = singularize(databaseName);
        String lowerCamel = lowerCamel(singular);
        return lowerCamel.isEmpty()
                ? "InferredType"
                : Character.toUpperCase(lowerCamel.charAt(0)) + lowerCamel.substring(1);
    }

    private static String fieldName(String databaseName) {
        return lowerCamel(databaseName);
    }

    private static String relationName(TitanGraphqlCatalogSnapshot.ForeignKey foreignKey) {
        String column = first(foreignKey.columns());
        if (column.endsWith("_id") && column.length() > 3) {
            return lowerCamel(column.substring(0, column.length() - 3));
        }
        return lowerCamel(singularize(foreignKey.targetTable()));
    }

    private static String singularize(String name) {
        if (name.endsWith("ies") && name.length() > 3) {
            return name.substring(0, name.length() - 3) + "y";
        }
        if (name.endsWith("s") && name.length() > 1) {
            return name.substring(0, name.length() - 1);
        }
        return name;
    }

    private static String lowerCamel(String databaseName) {
        StringBuilder builder = new StringBuilder();
        boolean capitalizeNext = false;
        for (int index = 0; index < databaseName.length(); index++) {
            char current = databaseName.charAt(index);
            if (!Character.isLetterOrDigit(current)) {
                capitalizeNext = builder.length() > 0;
                continue;
            }
            char next = Character.toLowerCase(current);
            if (capitalizeNext) {
                next = Character.toUpperCase(next);
                capitalizeNext = false;
            }
            builder.append(next);
        }
        return builder.toString();
    }

    private static String graphqlScalar(String databaseType) {
        String normalized = databaseType.toLowerCase(Locale.ROOT);
        if (normalized.contains("bool")) {
            return "Boolean";
        }
        if (normalized.contains("int") || normalized.equals("serial")) {
            return "Int";
        }
        if (normalized.contains("float")
                || normalized.contains("double")
                || normalized.contains("real")
                || normalized.contains("numeric")
                || normalized.contains("decimal")) {
            return "Float";
        }
        return "String";
    }

    private static boolean unknownScalar(String databaseType) {
        String normalized = databaseType.toLowerCase(Locale.ROOT);
        return !(normalized.contains("bool")
                || normalized.contains("int")
                || normalized.equals("serial")
                || normalized.contains("float")
                || normalized.contains("double")
                || normalized.contains("real")
                || normalized.contains("numeric")
                || normalized.contains("decimal")
                || normalized.contains("char")
                || normalized.contains("text")
                || normalized.contains("date")
                || normalized.contains("time"));
    }

    private static boolean sensitiveField(String columnName) {
        String normalized = columnName.toLowerCase(Locale.ROOT);
        return normalized.contains("email")
                || normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("credential");
    }
}
