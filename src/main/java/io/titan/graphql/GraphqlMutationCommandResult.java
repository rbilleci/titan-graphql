package io.titan.graphql;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record GraphqlMutationCommandResult(Map<String, Object> payload) {

    public GraphqlMutationCommandResult {
        payload = payload == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public static GraphqlMutationCommandResult of(Map<String, Object> payload) {
        return new GraphqlMutationCommandResult(payload);
    }
}
