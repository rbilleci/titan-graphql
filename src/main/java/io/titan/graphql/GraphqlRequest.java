package io.titan.graphql;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record GraphqlRequest(String query, String operationName, Map<String, Object> variables, Map<String, Object> extensions) {

    public GraphqlRequest {
        if (query == null || query.isBlank()) {
            throw new GraphqlException("request field 'query' is required");
        }
        operationName = operationName == null ? "" : operationName;
        variables = immutableMapAllowingNulls(variables);
        extensions = immutableMapAllowingNulls(extensions);
    }

    public static GraphqlRequest query(String query) {
        return new GraphqlRequest(query, "", Map.of(), Map.of());
    }

    public static GraphqlRequest of(String query, String operationName) {
        return new GraphqlRequest(query, operationName, Map.of(), Map.of());
    }

    public static GraphqlRequest of(String query, String operationName, Map<String, Object> variables) {
        return new GraphqlRequest(query, operationName, variables, Map.of());
    }

    private static Map<String, Object> immutableMapAllowingNulls(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
