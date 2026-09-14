package io.titan.graphql.inference;

import java.util.List;

record TitanGraphqlCatalogSnapshot(
        String catalog,
        List<Schema> schemas
) {
    TitanGraphqlCatalogSnapshot {
        catalog = CatalogSnapshotSupport.textOrEmpty(catalog);
        schemas = CatalogSnapshotSupport.listOrEmpty(schemas);
    }

    record Schema(
            String name,
            String comment,
            List<Table> tables
    ) {
        Schema {
            name = CatalogSnapshotSupport.requireText(name, "schema.name");
            comment = CatalogSnapshotSupport.textOrEmpty(comment);
            tables = CatalogSnapshotSupport.listOrEmpty(tables);
        }
    }

    enum TableKind {
        TABLE,
        VIEW
    }

    record Table(
            String schema,
            String name,
            TableKind kind,
            String comment,
            List<Column> columns,
            PrimaryKey primaryKey,
            List<ForeignKey> foreignKeys,
            List<Index> indexes
    ) {
        Table {
            schema = CatalogSnapshotSupport.requireText(schema, "table.schema");
            name = CatalogSnapshotSupport.requireText(name, "table.name");
            kind = kind == null ? TableKind.TABLE : kind;
            comment = CatalogSnapshotSupport.textOrEmpty(comment);
            columns = CatalogSnapshotSupport.listOrEmpty(columns);
            foreignKeys = CatalogSnapshotSupport.listOrEmpty(foreignKeys);
            indexes = CatalogSnapshotSupport.listOrEmpty(indexes);
        }
    }

    record Column(
            String name,
            String databaseType,
            boolean nullable,
            int ordinalPosition,
            String comment
    ) {
        Column {
            name = CatalogSnapshotSupport.requireText(name, "column.name");
            databaseType = CatalogSnapshotSupport.requireText(databaseType, "column.databaseType");
            comment = CatalogSnapshotSupport.textOrEmpty(comment);
        }
    }

    record PrimaryKey(
            String name,
            List<String> columns
    ) {
        PrimaryKey {
            name = CatalogSnapshotSupport.textOrEmpty(name);
            columns = CatalogSnapshotSupport.nonEmptyTextList(columns, "primaryKey.columns");
        }
    }

    record ForeignKey(
            String name,
            List<String> columns,
            String targetSchema,
            String targetTable,
            List<String> targetColumns,
            String comment
    ) {
        ForeignKey {
            name = CatalogSnapshotSupport.textOrEmpty(name);
            columns = CatalogSnapshotSupport.nonEmptyTextList(columns, "foreignKey.columns");
            targetSchema = CatalogSnapshotSupport.requireText(targetSchema, "foreignKey.targetSchema");
            targetTable = CatalogSnapshotSupport.requireText(targetTable, "foreignKey.targetTable");
            targetColumns = CatalogSnapshotSupport.nonEmptyTextList(targetColumns, "foreignKey.targetColumns");
            comment = CatalogSnapshotSupport.textOrEmpty(comment);
        }
    }

    record Index(
            String name,
            List<String> columns,
            boolean unique,
            String comment
    ) {
        Index {
            name = CatalogSnapshotSupport.requireText(name, "index.name");
            columns = CatalogSnapshotSupport.nonEmptyTextList(columns, "index.columns");
            comment = CatalogSnapshotSupport.textOrEmpty(comment);
        }
    }
}
