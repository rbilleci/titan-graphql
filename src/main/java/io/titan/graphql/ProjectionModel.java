package io.titan.graphql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProjectionModel {

    private final Map<String, ProjectionRetrieval> retrievals;
    private final Map<String, ProjectionType> types;

    public ProjectionModel(List<ProjectionRetrieval> retrievals, List<ProjectionType> types) {
        this.retrievals = new LinkedHashMap<>();
        this.types = new LinkedHashMap<>();
        for (ProjectionRetrieval retrieval : retrievals) {
            this.retrievals.put(retrieval.name(), retrieval);
        }
        for (ProjectionType type : types) {
            this.types.put(type.name(), type);
        }
    }

    public List<ProjectionRetrieval> retrievals() {
        return List.copyOf(retrievals.values());
    }

    public ProjectionRetrieval retrieval(String name) {
        return retrievals.get(name);
    }

    public List<ProjectionType> types() {
        return List.copyOf(types.values());
    }

    public ProjectionType type(String name) {
        return types.get(name);
    }
}
