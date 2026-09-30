package io.titan.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

final class LegacyManagementGraphqlTestClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    GraphqlAdminHttpResource.GraphqlAdminHttpResult negotiatePost(
            Map<String, Object> request, String mediaType
    ) {
        return negotiatePost(request, mediaType, GraphqlAdminHttpResource.GraphqlAdminHttpContext.defaults());
    }

    GraphqlAdminHttpResource.GraphqlAdminHttpResult negotiatePost(
            Map<String, Object> request,
            String mediaType,
            GraphqlAdminHttpResource.GraphqlAdminHttpContext context
    ) {
        String result = GraphqlRuntimeRegistry.managementRuntime().execute(
                new GraphqlRuntimeRequest(
                        (String) request.get("query"),
                        request.get("operationName") instanceof String name ? name : "",
                        json(request.get("variables")),
                        json(request.get("extensions")),
                        true),
                context.toGraphqlRequestContext());
        return new GraphqlAdminHttpResource.GraphqlAdminHttpResult(200, mediaType, result);
    }

    GraphqlAdminHttpResource.GraphqlAdminHttpResult negotiateGet(
            String query,
            String operationName,
            String variablesJson,
            String extensionsJson,
            String mediaType,
            GraphqlAdminHttpResource.GraphqlAdminHttpContext context
    ) {
        String result = GraphqlRuntimeRegistry.managementRuntime().execute(
                new GraphqlRuntimeRequest(query, operationName == null ? "" : operationName,
                        variablesJson == null ? "" : variablesJson,
                        extensionsJson == null ? "" : extensionsJson, false),
                context.toGraphqlRequestContext());
        return new GraphqlAdminHttpResource.GraphqlAdminHttpResult(200, mediaType, result);
    }

    private static String json(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("GraphQL request object could not be serialized", invalid);
        }
    }
}
