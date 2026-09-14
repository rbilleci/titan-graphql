package io.titan.graphql;

import io.titan.graphql.management.TitanGraphqlManagementProjection;

public final class GraphqlManagementDataModel implements GraphqlDataModel {

    private final GraphqlManagementMutationSupport management = new GraphqlManagementMutationSupport();
    private final GraphqlSchema schema = management.withManagementMutations(ProjectionGraphqlAdapter.adapt(
            TitanGraphqlManagementProjection.projectionModel().toProjectionModel()
    ));

    public GraphqlManagementDataModel() {
    }

    @Override
    public GraphqlSchema schema() {
        return schema;
    }

    @Override
    public GraphqlExecution execute(GraphqlSelection selection, GraphqlRequestContext context) {
        return new GraphqlExecution(
                GraphqlJsonWriter.error(
                        "management data queries are not implemented until management storage is wired",
                        GraphqlException.UNSUPPORTED_OPERATION
                ),
                new GraphqlPlan()
        );
    }

    @Override
    public GraphqlExecution executeMutation(GraphqlAst.AstOperation operation, GraphqlRequestContext context) {
        return management.executeMutation(schema, operation, context);
    }
}
