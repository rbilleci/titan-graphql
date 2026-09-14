package io.titan.graphql;

public record GraphqlRuntimeRequest(String query, String operationName, String variablesJson, String extensionsJson) {

    public GraphqlRuntimeRequest {
        query = query == null ? "" : query;
        operationName = operationName == null ? "" : operationName;
        variablesJson = variablesJson == null ? "" : variablesJson;
        extensionsJson = extensionsJson == null ? "" : extensionsJson;
    }
}
