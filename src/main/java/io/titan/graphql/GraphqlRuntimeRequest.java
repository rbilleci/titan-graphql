package io.titan.graphql;

/**
 * Transport-neutral request envelope. {@code allowMutations} is a transport policy flag, not a
 * GraphQL semantic decision: a database-resident engine receives it with the original document
 * and selects/rejects the requested operation itself.
 */
public record GraphqlRuntimeRequest(
        String query,
        String operationName,
        String variablesJson,
        String extensionsJson,
        boolean allowMutations
) {

    public GraphqlRuntimeRequest(String query, String operationName, String variablesJson, String extensionsJson) {
        this(query, operationName, variablesJson, extensionsJson, true);
    }

    public GraphqlRuntimeRequest {
        query = query == null ? "" : query;
        operationName = operationName == null ? "" : operationName;
        variablesJson = variablesJson == null ? "" : variablesJson;
        extensionsJson = extensionsJson == null ? "" : extensionsJson;
    }
}
