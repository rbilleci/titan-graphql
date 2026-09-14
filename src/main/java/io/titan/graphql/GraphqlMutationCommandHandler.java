package io.titan.graphql;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@FunctionalInterface
public interface GraphqlMutationCommandHandler {

    GraphqlMutationCommandResult handle(GraphqlMutationCommandRequest request);

    record GraphqlMutationCommandRequest(
            GraphqlMutationDescriptor descriptor,
            Map<String, Object> input,
            GraphqlRequestContext context
    ) {
        public GraphqlMutationCommandRequest {
            input = input == null
                    ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(input));
        }
    }
}
