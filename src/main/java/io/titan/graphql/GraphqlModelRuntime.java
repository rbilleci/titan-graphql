package io.titan.graphql;

public interface GraphqlModelRuntime {

    String name();

    String execute(GraphqlRuntimeRequest request, GraphqlRequestContext context);

    GraphqlExecution executeWithPlan(GraphqlRequest request, GraphqlRequestContext context);
}
