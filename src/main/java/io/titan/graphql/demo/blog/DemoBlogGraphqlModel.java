package io.titan.graphql.demo.blog;

import io.titan.graphql.*;

public final class DemoBlogGraphqlModel implements GraphqlDataModel {

    private final GraphqlSchema schema;
    private final DemoBlogGraphqlExecutor executor;

    public DemoBlogGraphqlModel(GraphqlPolicy policy, DemoBlogFixtureStore store, GraphqlJsonWriter jsonWriter) {
        this.schema = DemoBlogGraphqlSchema.create(policy);
        this.executor = new DemoBlogGraphqlExecutor(schema, store, jsonWriter);
    }

    @Override
    public GraphqlSchema schema() {
        return schema;
    }

    @Override
    public GraphqlExecution execute(GraphqlSelection selection, GraphqlRequestContext context) {
        return executor.execute(selection, context);
    }
}
