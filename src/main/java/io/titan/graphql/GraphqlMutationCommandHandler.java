package io.titan.graphql;

import java.util.Map;

interface GraphqlMutationCommandHandler {

    GraphqlMutationCommandResult handle(GraphqlMutationCommandRequest request);

    record GraphqlMutationCommandRequest(
            GraphqlMutationDescriptor descriptor,
            Map<String, Object> input,
            GraphqlRequestContext context
    ) {
        public GraphqlMutationCommandRequest {
            input = input == null ? Map.of() : Map.copyOf(input);
        }
    }
}
