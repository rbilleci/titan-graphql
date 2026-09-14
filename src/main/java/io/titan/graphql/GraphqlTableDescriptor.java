package io.titan.graphql;

public final class GraphqlTableDescriptor {

    private final String name;
    private final String schemaName;
    private final String tableName;
    private final String primaryKeyColumnName;

    public GraphqlTableDescriptor(String name, String schemaName, String tableName, String primaryKeyColumnName) {
        this.name = name;
        this.schemaName = schemaName;
        this.tableName = tableName;
        this.primaryKeyColumnName = primaryKeyColumnName;
    }

    public String name() {
        return name;
    }

    public String schemaName() {
        return schemaName;
    }

    public String tableName() {
        return tableName;
    }

    public String primaryKeyColumnName() {
        return primaryKeyColumnName;
    }

    public String qualifiedName() {
        if (schemaName == null || schemaName.isBlank()) {
            return tableName;
        }
        return schemaName + "." + tableName;
    }
}
