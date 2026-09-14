package io.titan.graphql;

import java.util.List;

public final class TitanGraphqlProjectionModel {

    private final ProjectionModel model;

    TitanGraphqlProjectionModel(ProjectionModel model) {
        this.model = model;
    }

    public List<String> rootNames() {
        return model.retrievals().stream()
                .map(ProjectionRetrieval::name)
                .toList();
    }

    public List<String> typeNames() {
        return model.types().stream()
                .map(ProjectionType::name)
                .toList();
    }

    public String schemaDefinition() {
        return GraphqlSchemaPrinter.print(ProjectionGraphqlAdapter.adapt(model));
    }

    ProjectionModel toProjectionModel() {
        return model;
    }
}
