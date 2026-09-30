package io.titan.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

final class LegacyPreviewGraphqlTestClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    GraphqlPreviewHttpResource.GraphqlPreviewHttpResult negotiatePost(
            String previewBuildId,
            Map<String, Object> request,
            String mediaType,
            GraphqlHttpResource.GraphqlHttpContext context
    ) {
        return execute(previewBuildId, new GraphqlRequest(
                (String) request.get("query"),
                request.get("operationName") instanceof String name ? name : "",
                object(request.get("variables")), object(request.get("extensions"))), mediaType, context);
    }

    GraphqlPreviewHttpResource.GraphqlPreviewHttpResult negotiateGet(
            String previewBuildId,
            String query,
            String operationName,
            String variablesJson,
            String extensionsJson,
            String mediaType,
            GraphqlHttpResource.GraphqlHttpContext context
    ) {
        return execute(previewBuildId, new GraphqlRequest(query, operationName == null ? "" : operationName,
                objectJson(variablesJson), objectJson(extensionsJson)), mediaType, context);
    }

    private static GraphqlPreviewHttpResource.GraphqlPreviewHttpResult execute(
            String previewBuildId,
            GraphqlRequest request,
            String mediaType,
            GraphqlHttpResource.GraphqlHttpContext context
    ) {
        GraphqlRequestContext trustedContext = new GraphqlRequestContext(
                context.actorId(), context.actorRole(),
                context.actorId() > 0L ? "actor-" + context.actorId() : "",
                context.tenantId(), context.requestId(), "",
                commaSeparated(context.policyFlags()), commaSeparated(context.enabledContextFilters()),
                context.enableIntrospection(), context.hasArticleVisibility(),
                context.articleVisibility(), context.deadlineBudgetMillis());
        return new GraphqlPreviewHttpResource.GraphqlPreviewHttpResult(200, mediaType,
                GraphqlPreviewRuntimeRouter.execute(previewBuildId, request, trustedContext).json());
    }

    private static List<String> commaSeparated(String value) {
        return value == null || value.isBlank() ? List.of()
                : List.of(value.split(",")).stream().map(String::trim)
                        .filter(item -> !item.isEmpty()).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }

    private static Map<String, Object> objectJson(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(value, JSON_OBJECT);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("GraphQL request object could not be parsed", invalid);
        }
    }
}
