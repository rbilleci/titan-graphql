package io.titan.graphql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProjectionType {

    private final String name;
    private final String tableName;
    private final String schemaName;
    private final String physicalTableName;
    private final String primaryKeyColumnName;
    private final Map<String, ProjectionField> fields;
    private final Map<String, ProjectionRelation> relations;

    public ProjectionType(
            String name,
            String tableName,
            String schemaName,
            String physicalTableName,
            String primaryKeyColumnName,
            List<ProjectionField> fields,
            List<ProjectionRelation> relations
    ) {
        this.name = name;
        this.tableName = tableName;
        this.schemaName = schemaName;
        this.physicalTableName = physicalTableName;
        this.primaryKeyColumnName = primaryKeyColumnName;
        this.fields = new LinkedHashMap<>();
        this.relations = new LinkedHashMap<>();
        for (ProjectionField field : fields) {
            this.fields.put(field.name(), field);
        }
        for (ProjectionRelation relation : relations) {
            this.relations.put(relation.name(), relation);
        }
    }

    public String name() {
        return name;
    }

    public String tableName() {
        return tableName;
    }

    public String schemaName() {
        return schemaName;
    }

    public String physicalTableName() {
        return physicalTableName;
    }

    public String primaryKeyColumnName() {
        return primaryKeyColumnName;
    }

    public List<ProjectionField> fields() {
        return List.copyOf(fields.values());
    }

    public ProjectionField field(String name) {
        return fields.get(name);
    }

    public List<ProjectionRelation> relations() {
        return List.copyOf(relations.values());
    }

    public ProjectionRelation relation(String name) {
        return relations.get(name);
    }
}
