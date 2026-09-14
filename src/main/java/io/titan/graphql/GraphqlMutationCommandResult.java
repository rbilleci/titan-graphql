package io.titan.graphql;

import java.util.Map;

record GraphqlMutationCommandResult(Map<String, Object> payload) {

    GraphqlMutationCommandResult {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    static GraphqlMutationCommandResult of(Map<String, Object> payload) {
        return new GraphqlMutationCommandResult(payload);
    }
}
