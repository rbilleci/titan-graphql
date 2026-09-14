package io.titan.graphql;

public interface GraphqlDataModel {

    GraphqlSchema schema();

    GraphqlExecution execute(GraphqlSelection selection, GraphqlRequestContext context);

    public default GraphqlExecution executeMutation(GraphqlAst.AstOperation operation, GraphqlRequestContext context) {
        return new GraphqlExecution(GraphqlJsonWriter.error(GraphqlException.mutationExecutionNotImplemented()), new GraphqlPlan());
    }
}
