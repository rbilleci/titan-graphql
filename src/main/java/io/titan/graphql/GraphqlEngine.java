package io.titan.graphql;

public final class GraphqlEngine {

    private GraphqlEngine() {
    }

    public static GraphqlExecution execute(GraphqlDataModel dataModel, GraphqlJsonWriter jsonWriter, String query, String actorRole) {
        try {
            return execute(dataModel, jsonWriter, GraphqlRequest.query(query), GraphqlRequestContext.legacy(0L, actorRole));
        } catch (GraphqlException ex) {
            return new GraphqlExecution(GraphqlJsonWriter.error(ex), new GraphqlPlan());
        }
    }

    public static GraphqlExecution execute(GraphqlDataModel dataModel, GraphqlJsonWriter jsonWriter, GraphqlRequest request, String actorRole) {
        return execute(dataModel, jsonWriter, request, GraphqlRequestContext.legacy(0L, actorRole));
    }

    public static GraphqlExecution execute(
            GraphqlDataModel dataModel,
            GraphqlJsonWriter jsonWriter,
            GraphqlRequest request,
            GraphqlRequestContext context
    ) {
        try {
            GraphqlAst.AstOperation operation = dataModel.schema().mutations().isEmpty()
                    ? GraphqlParser.parse(request)
                    : GraphqlParser.parseSelectedOperation(request);
            if (GraphqlIntrospection.isIntrospectionOperation(operation) && context.introspectionEnabled()) {
                return GraphqlIntrospection.execute(dataModel.schema(), operation);
            }
            if (operation.type() == GraphqlAst.OperationType.MUTATION
                    && dataModel.schema().mutations().isEmpty() == false) {
                return dataModel.executeMutation(operation, context);
            }
            GraphqlSelection selection = GraphqlValidator.validate(dataModel.schema(), operation, context);
            return dataModel.execute(selection, context);
        } catch (GraphqlException ex) {
            return new GraphqlExecution(GraphqlJsonWriter.error(ex), new GraphqlPlan());
        }
    }
}
