package io.titan.graphql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GraphqlSchema {

    private final Map<String, GraphqlRootField> rootFields;
    private final Map<String, GraphqlObjectType> types;
    private final Map<String, GraphqlTableDescriptor> tables;
    private final Map<String, GraphqlMutationDescriptor> mutations;

    public GraphqlSchema(List<GraphqlRootField> rootFields, List<GraphqlObjectType> types) {
        this(rootFields, types, List.of());
    }

    public GraphqlSchema(
            List<GraphqlRootField> rootFields,
            List<GraphqlObjectType> types,
            List<GraphqlTableDescriptor> tables
    ) {
        this(rootFields, types, tables, List.of());
    }

    public GraphqlSchema(
            List<GraphqlRootField> rootFields,
            List<GraphqlObjectType> types,
            List<GraphqlTableDescriptor> tables,
            List<GraphqlMutationDescriptor> mutations
    ) {
        this.rootFields = new LinkedHashMap<>();
        this.types = new LinkedHashMap<>();
        this.tables = new LinkedHashMap<>();
        this.mutations = new LinkedHashMap<>();
        for (GraphqlRootField rootField : rootFields) {
            this.rootFields.put(rootField.name(), rootField);
        }
        for (GraphqlObjectType type : types) {
            this.types.put(type.name(), type);
        }
        for (GraphqlTableDescriptor table : tables) {
            this.tables.put(table.name(), table);
        }
        for (GraphqlMutationDescriptor mutation : mutations) {
            this.mutations.put(mutation.name(), mutation);
        }
    }

    public GraphqlRootField rootField(String name) {
        return rootFields.get(name);
    }

    public List<GraphqlRootField> rootFields() {
        return List.copyOf(rootFields.values());
    }

    public GraphqlObjectType type(String name) {
        return types.get(name);
    }

    public List<GraphqlObjectType> types() {
        return List.copyOf(types.values());
    }

    public GraphqlTableDescriptor table(String name) {
        return tables.get(name);
    }

    public GraphqlMutationDescriptor mutation(String name) {
        return mutations.get(name);
    }

    public List<GraphqlMutationDescriptor> mutations() {
        return List.copyOf(mutations.values());
    }
}
