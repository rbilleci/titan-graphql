package io.titan.graphql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GraphqlObjectType {

    private final String name;
    private final String tableName;
    private final Map<String, GraphqlFieldDescriptor> fields;

    public GraphqlObjectType(String name, List<GraphqlFieldDescriptor> fields) {
        this(name, "", fields);
    }

    public GraphqlObjectType(String name, String tableName, List<GraphqlFieldDescriptor> fields) {
        this.name = name;
        this.tableName = tableName;
        this.fields = new LinkedHashMap<>();
        for (GraphqlFieldDescriptor field : fields) {
            this.fields.put(field.name(), field);
        }
    }

    public String name() {
        return name;
    }

    public String tableName() {
        return tableName;
    }

    public GraphqlFieldDescriptor field(String fieldName) {
        return fields.get(fieldName);
    }

    public List<GraphqlFieldDescriptor> fields() {
        return List.copyOf(fields.values());
    }
}
