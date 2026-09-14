package io.titan.graphql.demo.blog;

import io.titan.graphql.*;

public final class DemoBlogGraphqlRuntime implements GraphqlModelRuntime {

    public static final String NAME = "demo-blog";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String execute(GraphqlRuntimeRequest request, GraphqlRequestContext context) {
        return DemoBlogTitanGraphqlFunctions.executeGraphqlRequestWithCompactContext(
                request.query(),
                request.operationName(),
                request.variablesJson(),
                request.extensionsJson(),
                context.actorId(),
                context.actorRole(),
                context.contextFilterEnabled("publishedVisibility"),
                context.hasArticleVisibility(),
                context.articleVisibility(),
                context.introspectionEnabled(),
                context.tenantId(),
                context.requestId(),
                String.join(",", context.policyFlags()),
                String.join(",", context.enabledContextFilters()),
                context.deadlineEpochMillis()
        );
    }

    @Override
    public GraphqlExecution executeWithPlan(GraphqlRequest request, GraphqlRequestContext context) {
        GraphqlJsonWriter jsonWriter = new GraphqlJsonWriter();
        GraphqlDataModel dataModel = new DemoBlogGraphqlModel(new GraphqlPolicy(), new DemoBlogFixtureStore(), jsonWriter);
        return GraphqlEngine.execute(dataModel, jsonWriter, request, context);
    }
}
