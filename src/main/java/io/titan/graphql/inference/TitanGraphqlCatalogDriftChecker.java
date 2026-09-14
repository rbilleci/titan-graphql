package io.titan.graphql.inference;

import io.titan.graphql.model.TitanGraphqlFieldDocument;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlRelationDocument;
import io.titan.graphql.model.TitanGraphqlRootDocument;
import io.titan.graphql.model.TitanGraphqlTypeDocument;
import io.titan.graphql.validation.TitanGraphqlModelPath;
import io.titan.graphql.validation.TitanGraphqlSourceLocation;
import io.titan.graphql.validation.TitanGraphqlValidationIssue;
import io.titan.graphql.validation.TitanGraphqlValidationIssueCode;
import io.titan.graphql.validation.TitanGraphqlValidationReport;
import io.titan.graphql.validation.TitanGraphqlValidationSeverity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TitanGraphqlCatalogDriftChecker {

    private TitanGraphqlCatalogDriftChecker() {
    }

    static TitanGraphqlValidationReport checkBindings(
            TitanGraphqlModelDocument document,
            TitanGraphqlCatalogSnapshot snapshot
    ) {
        if (document == null) {
            throw new IllegalArgumentException("model document is required");
        }
        if (snapshot == null) {
            throw new IllegalArgumentException("catalog snapshot is required");
        }
        DriftContext context = new DriftContext(document, new CatalogIndex(snapshot));
        context.check();
        return new TitanGraphqlValidationReport(context.issues);
    }

    private static final class DriftContext {

        private final TitanGraphqlModelDocument document;
        private final CatalogIndex catalog;
        private final List<TitanGraphqlValidationIssue> issues;
        private final Map<String, TitanGraphqlTypeDocument> typesByName;
        private final Map<TitanGraphqlTypeDocument, TitanGraphqlCatalogSnapshot.Table> tablesByType;

        DriftContext(TitanGraphqlModelDocument document, CatalogIndex catalog) {
            this.document = document;
            this.catalog = catalog;
            this.issues = new ArrayList<>();
            this.typesByName = new LinkedHashMap<>();
            this.tablesByType = new LinkedHashMap<>();
        }

        void check() {
            indexTypes();
            checkTypes();
            checkRoots();
        }

        private void indexTypes() {
            for (TitanGraphqlTypeDocument type : document.types()) {
                typesByName.putIfAbsent(type.name(), type);
            }
        }

        private void checkTypes() {
            for (TitanGraphqlTypeDocument type : document.types()) {
                TitanGraphqlCatalogSnapshot.Table table = catalog.table(schema(type), tableName(type));
                if (table == null) {
                    issue(
                            "MISSING_TABLE",
                            "Type '" + type.name() + "' binds missing table '" + schema(type) + "." + tableName(type) + "'.",
                            path("types", type.name()),
                            Map.of("schema", schema(type), "table", tableName(type))
                    );
                    continue;
                }
                tablesByType.put(type, table);
                checkPrimaryKey(type, table);
                checkFields(type, table);
                checkRelations(type, table);
            }
        }

        private void checkPrimaryKey(TitanGraphqlTypeDocument type, TitanGraphqlCatalogSnapshot.Table table) {
            if (type.primaryKey().isBlank()) {
                return;
            }
            if (table.primaryKey() == null || !table.primaryKey().columns().contains(type.primaryKey())) {
                issue(
                        "MISSING_KEY",
                        "Type '" + type.name() + "' primary key '" + type.primaryKey()
                                + "' is missing from table '" + table.schema() + "." + table.name() + "'.",
                        path("types", type.name(), "primaryKey"),
                        Map.of("schema", table.schema(), "table", table.name(), "column", type.primaryKey())
                );
            }
        }

        private void checkFields(TitanGraphqlTypeDocument type, TitanGraphqlCatalogSnapshot.Table table) {
            for (TitanGraphqlFieldDocument field : type.fields()) {
                if (!field.column().isBlank()) {
                    checkColumnField(type, table, field);
                }
                if (field.computed() != null) {
                    checkComputedField(type, table, field);
                }
            }
        }

        private void checkColumnField(
                TitanGraphqlTypeDocument type,
                TitanGraphqlCatalogSnapshot.Table table,
                TitanGraphqlFieldDocument field
        ) {
            TitanGraphqlCatalogSnapshot.Column column = catalog.column(table, field.column());
            if (column == null) {
                issue(
                        "MISSING_COLUMN",
                        "Field '" + type.name() + "." + field.name() + "' binds missing column '"
                                + field.column() + "'.",
                        path("types", type.name(), "fields", field.name()),
                        columnMetadata(table, field.column())
                );
                return;
            }
            String actualScalar = graphqlScalar(column.databaseType());
            if (!actualScalar.equals(field.type())) {
                issue(
                        "SCALAR_TYPE_MISMATCH",
                        "Field '" + type.name() + "." + field.name() + "' expects GraphQL scalar '"
                                + field.type() + "' but column '" + column.name() + "' maps to '" + actualScalar + "'.",
                        path("types", type.name(), "fields", field.name(), "type"),
                        columnMetadata(table, column.name(), Map.of(
                                "expectedScalar", field.type(),
                                "actualScalar", actualScalar,
                                "databaseType", column.databaseType()
                        ))
                );
            }
            if (field.nullable() != column.nullable()) {
                issue(
                        "NULLABILITY_MISMATCH",
                        "Field '" + type.name() + "." + field.name() + "' nullable=" + field.nullable()
                                + " but column '" + column.name() + "' nullable=" + column.nullable() + ".",
                        path("types", type.name(), "fields", field.name(), "nullable"),
                        columnMetadata(table, column.name(), Map.of(
                                "modelNullable", Boolean.toString(field.nullable()),
                                "catalogNullable", Boolean.toString(column.nullable())
                        ))
                );
            }
        }

        private void checkComputedField(
                TitanGraphqlTypeDocument type,
                TitanGraphqlCatalogSnapshot.Table table,
                TitanGraphqlFieldDocument field
        ) {
            for (String requiredColumn : field.computed().requiredColumns()) {
                if (catalog.column(table, requiredColumn) == null) {
                    issue(
                            "MISSING_COMPUTED_REQUIRED_COLUMN",
                            "Computed field '" + type.name() + "." + field.name()
                                    + "' requires missing column '" + requiredColumn + "'.",
                            path("types", type.name(), "fields", field.name(), "computed", "requiredColumns", requiredColumn),
                            columnMetadata(table, requiredColumn)
                    );
                }
            }
        }

        private void checkRelations(TitanGraphqlTypeDocument type, TitanGraphqlCatalogSnapshot.Table table) {
            for (TitanGraphqlRelationDocument relation : type.relations()) {
                TitanGraphqlTypeDocument targetType = typesByName.get(relation.targetType());
                TitanGraphqlCatalogSnapshot.Table targetTable = targetType == null
                        ? null
                        : catalog.table(schema(targetType), tableName(targetType));
                boolean localColumnExists = catalog.column(table, relation.localColumn()) != null;
                boolean targetColumnExists = targetTable != null && catalog.column(targetTable, relation.targetColumn()) != null;
                boolean foreignKeyExists = targetTable != null
                        && catalog.hasForeignKey(table, relation.localColumn(), targetTable, relation.targetColumn());
                if (!localColumnExists || !targetColumnExists || !foreignKeyExists) {
                    issue(
                            "BROKEN_RELATION_JOIN",
                            "Relation '" + type.name() + "." + relation.name()
                                    + "' no longer matches catalog join metadata.",
                            path("types", type.name(), "relations", relation.name()),
                            Map.of(
                                    "localSchema", table.schema(),
                                    "localTable", table.name(),
                                    "localColumn", relation.localColumn(),
                                    "targetType", relation.targetType(),
                                    "targetSchema", targetTable == null ? "" : targetTable.schema(),
                                    "targetTable", targetTable == null ? "" : targetTable.name(),
                                    "targetColumn", relation.targetColumn(),
                                    "localColumnExists", Boolean.toString(localColumnExists),
                                    "targetColumnExists", Boolean.toString(targetColumnExists),
                                    "foreignKeyExists", Boolean.toString(foreignKeyExists)
                            )
                    );
                }
            }
        }

        private void checkRoots() {
            for (TitanGraphqlRootDocument root : document.roots()) {
                TitanGraphqlTypeDocument type = typesByName.get(root.type());
                TitanGraphqlCatalogSnapshot.Table table = type == null ? null : tablesByType.get(type);
                if (table == null) {
                    continue;
                }
                if (root.argument() != null) {
                    checkRootColumn(root, table, root.argument().column(), "arguments", root.argument().name(), true);
                }
                for (TitanGraphqlRootDocument.RootDocumentArgument argument : root.arguments()) {
                    if (argument.hops() == 0 && !argument.column().isBlank()) {
                        checkRootColumn(root, table, argument.column(), "arguments", argument.name(), true);
                    }
                }
                for (TitanGraphqlRootDocument.RootDocumentFilterPath filterPath : root.filterPaths()) {
                    if (filterPath.hops() == 0) {
                        checkRootColumn(root, table, filterPath.column(), "filterPaths", filterPath.name(), true);
                    }
                }
                for (TitanGraphqlRootDocument.RootDocumentSortPath sortPath : root.sortPaths()) {
                    if (sortPath.hops() == 0) {
                        checkRootColumn(root, table, sortPath.column(), "sortPaths", sortPath.name(), true);
                    }
                }
            }
        }

        private void checkRootColumn(
                TitanGraphqlRootDocument root,
                TitanGraphqlCatalogSnapshot.Table table,
                String column,
                String section,
                String name,
                boolean requireIndex
        ) {
            if (catalog.column(table, column) == null) {
                issue(
                        "MISSING_COLUMN",
                        "Root '" + root.name() + "' " + section + " binding '" + name
                                + "' references missing column '" + column + "'.",
                        path("roots", root.name(), section, name),
                        columnMetadata(table, column)
                );
                return;
            }
            if (requireIndex && !catalog.hasLeadingIndex(table, column)) {
                issue(
                        "MISSING_INDEX",
                        "Root '" + root.name() + "' " + section + " binding '" + name
                                + "' has no catalog index starting with column '" + column + "'.",
                        path("roots", root.name(), section, name),
                        columnMetadata(table, column)
                );
            }
        }

        private String schema(TitanGraphqlTypeDocument type) {
            if (!type.schema().isBlank()) {
                return type.schema();
            }
            if (!document.database().defaultSchema().isBlank()) {
                return document.database().defaultSchema();
            }
            return catalog.defaultSchema();
        }

        private String tableName(TitanGraphqlTypeDocument type) {
            if (!type.physicalTable().isBlank()) {
                return type.physicalTable();
            }
            if (!type.table().isBlank()) {
                return type.table();
            }
            return type.name();
        }

        private Map<String, String> columnMetadata(TitanGraphqlCatalogSnapshot.Table table, String column) {
            return columnMetadata(table, column, Map.of());
        }

        private Map<String, String> columnMetadata(
                TitanGraphqlCatalogSnapshot.Table table,
                String column,
                Map<String, String> extra
        ) {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("schema", table.schema());
            metadata.put("table", table.name());
            metadata.put("column", column);
            metadata.putAll(extra);
            return metadata;
        }

        private void issue(String driftKind, String message, TitanGraphqlModelPath path, Map<String, String> metadata) {
            Map<String, String> issueMetadata = new LinkedHashMap<>();
            issueMetadata.put("driftKind", driftKind);
            issueMetadata.putAll(metadata);
            issues.add(new TitanGraphqlValidationIssue(
                    TitanGraphqlValidationIssueCode.DRIFT_DETECTED,
                    TitanGraphqlValidationSeverity.ERROR,
                    message,
                    path,
                    TitanGraphqlSourceLocation.none(),
                    true,
                    issueMetadata
            ));
        }

        private TitanGraphqlModelPath path(String... segments) {
            return TitanGraphqlModelPath.of(segments);
        }
    }

    private static final class CatalogIndex {

        private final Map<String, TitanGraphqlCatalogSnapshot.Table> tables;
        private final String defaultSchema;

        CatalogIndex(TitanGraphqlCatalogSnapshot snapshot) {
            this.tables = new LinkedHashMap<>();
            String firstSchema = "";
            for (TitanGraphqlCatalogSnapshot.Schema schema : snapshot.schemas()) {
                if (firstSchema.isBlank()) {
                    firstSchema = schema.name();
                }
                for (TitanGraphqlCatalogSnapshot.Table table : schema.tables()) {
                    tables.put(key(table.schema(), table.name()), table);
                }
            }
            this.defaultSchema = firstSchema;
        }

        String defaultSchema() {
            return defaultSchema;
        }

        TitanGraphqlCatalogSnapshot.Table table(String schema, String table) {
            return tables.get(key(schema, table));
        }

        TitanGraphqlCatalogSnapshot.Column column(TitanGraphqlCatalogSnapshot.Table table, String column) {
            if (table == null || column == null || column.isBlank()) {
                return null;
            }
            for (TitanGraphqlCatalogSnapshot.Column candidate : table.columns()) {
                if (candidate.name().equals(column)) {
                    return candidate;
                }
            }
            return null;
        }

        boolean hasLeadingIndex(TitanGraphqlCatalogSnapshot.Table table, String column) {
            if (table.primaryKey() != null && !table.primaryKey().columns().isEmpty()
                    && table.primaryKey().columns().get(0).equals(column)) {
                return true;
            }
            for (TitanGraphqlCatalogSnapshot.Index index : table.indexes()) {
                if (!index.columns().isEmpty() && index.columns().get(0).equals(column)) {
                    return true;
                }
            }
            return false;
        }

        boolean hasForeignKey(
                TitanGraphqlCatalogSnapshot.Table table,
                String column,
                TitanGraphqlCatalogSnapshot.Table targetTable,
                String targetColumn
        ) {
            for (TitanGraphqlCatalogSnapshot.ForeignKey foreignKey : table.foreignKeys()) {
                if (foreignKey.targetSchema().equals(targetTable.schema())
                        && foreignKey.targetTable().equals(targetTable.name())
                        && foreignKey.columns().equals(List.of(column))
                        && foreignKey.targetColumns().equals(List.of(targetColumn))) {
                    return true;
                }
            }
            return false;
        }

        private String key(String schema, String table) {
            return schema + "." + table;
        }
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
}
