package io.titan.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

@Path("/preview/{previewBuildId}/graphql")
public final class GraphqlPreviewHttpResource {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    record GraphqlPreviewHttpResult(int status, String mediaType, String body) {
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({GraphqlHttpResource.GRAPHQL_RESPONSE_JSON, MediaType.APPLICATION_JSON})
    public Response postResponse(
            @PathParam("previewBuildId") String previewBuildId,
            Map<String, Object> request,
            @HeaderParam("Accept") String acceptHeader,
            @HeaderParam("X-Titan-Actor-Id") String actorIdHeader,
            @HeaderParam("X-Titan-Actor-Role") String actorRoleHeader,
            @HeaderParam("X-Titan-Context-Filter-Published-Visibility") String publishedVisibilityHeader,
            @HeaderParam("X-Titan-Article-Visibility") String articleVisibilityHeader,
            @HeaderParam("X-Titan-Introspection") String introspectionHeader,
            @HeaderParam("X-Titan-Tenant-Id") String tenantIdHeader,
            @HeaderParam("X-Titan-Request-Id") String requestIdHeader,
            @HeaderParam("X-Titan-Policy-Flags") String policyFlagsHeader,
            @HeaderParam("X-Titan-Context-Filters") String enabledContextFiltersHeader,
            @HeaderParam("X-Titan-Deadline-Budget-Millis") String deadlineBudgetMillisHeader
    ) {
        GraphqlPreviewHttpResult result = negotiatePost(
                previewBuildId,
                request,
                acceptHeader,
                GraphqlHttpResource.httpContext(
                        actorIdHeader,
                        actorRoleHeader,
                        publishedVisibilityHeader,
                        articleVisibilityHeader,
                        introspectionHeader,
                        tenantIdHeader,
                        requestIdHeader,
                        policyFlagsHeader,
                        enabledContextFiltersHeader,
                        deadlineBudgetMillisHeader
                )
        );
        return Response
                .status(result.status())
                .type(result.mediaType())
                .entity(result.body())
                .build();
    }

    @GET
    @Produces({GraphqlHttpResource.GRAPHQL_RESPONSE_JSON, MediaType.APPLICATION_JSON})
    public Response getResponse(
            @PathParam("previewBuildId") String previewBuildId,
            @QueryParam("query") String query,
            @QueryParam("operationName") String operationName,
            @QueryParam("variables") String variablesJson,
            @QueryParam("extensions") String extensionsJson,
            @HeaderParam("Accept") String acceptHeader,
            @HeaderParam("X-Titan-Actor-Id") String actorIdHeader,
            @HeaderParam("X-Titan-Actor-Role") String actorRoleHeader,
            @HeaderParam("X-Titan-Context-Filter-Published-Visibility") String publishedVisibilityHeader,
            @HeaderParam("X-Titan-Article-Visibility") String articleVisibilityHeader,
            @HeaderParam("X-Titan-Introspection") String introspectionHeader,
            @HeaderParam("X-Titan-Tenant-Id") String tenantIdHeader,
            @HeaderParam("X-Titan-Request-Id") String requestIdHeader,
            @HeaderParam("X-Titan-Policy-Flags") String policyFlagsHeader,
            @HeaderParam("X-Titan-Context-Filters") String enabledContextFiltersHeader,
            @HeaderParam("X-Titan-Deadline-Budget-Millis") String deadlineBudgetMillisHeader
    ) {
        GraphqlPreviewHttpResult result = negotiateGet(
                previewBuildId,
                query,
                operationName,
                variablesJson,
                extensionsJson,
                acceptHeader,
                GraphqlHttpResource.httpContext(
                        actorIdHeader,
                        actorRoleHeader,
                        publishedVisibilityHeader,
                        articleVisibilityHeader,
                        introspectionHeader,
                        tenantIdHeader,
                        requestIdHeader,
                        policyFlagsHeader,
                        enabledContextFiltersHeader,
                        deadlineBudgetMillisHeader
                )
        );
        return Response
                .status(result.status())
                .type(result.mediaType())
                .entity(result.body())
                .build();
    }

    GraphqlPreviewHttpResult negotiatePost(
            String previewBuildId,
            Map<String, Object> request,
            String acceptHeader,
            GraphqlHttpResource.GraphqlHttpContext context
    ) {
        String responseType = GraphqlHttpResource.responseMediaType(acceptHeader);
        if (responseType.isEmpty()) {
            return notAcceptable();
        }
        if (context.validationError().isEmpty() == false) {
            return new GraphqlPreviewHttpResult(
                    Response.Status.OK.getStatusCode(),
                    responseType,
                    GraphqlHttpResource.errorJson(context.validationError())
            );
        }
        String transportError = GraphqlHttpResource.transportValidationError(request);
        if (transportError.isEmpty() == false) {
            return new GraphqlPreviewHttpResult(
                    Response.Status.OK.getStatusCode(),
                    responseType,
                    GraphqlHttpResource.errorJson(transportError)
            );
        }
        return new GraphqlPreviewHttpResult(
                Response.Status.OK.getStatusCode(),
                responseType,
                execute(
                        previewBuildId,
                        (String) request.get("query"),
                        stringField(request, "operationName"),
                        jsonObjectField(request, "variables"),
                        jsonObjectField(request, "extensions"),
                        context
                )
        );
    }

    GraphqlPreviewHttpResult negotiateGet(
            String previewBuildId,
            String query,
            String operationName,
            String variablesJson,
            String extensionsJson,
            String acceptHeader,
            GraphqlHttpResource.GraphqlHttpContext context
    ) {
        String responseType = GraphqlHttpResource.responseMediaType(acceptHeader);
        if (responseType.isEmpty()) {
            return notAcceptable();
        }
        if (context.validationError().isEmpty() == false) {
            return new GraphqlPreviewHttpResult(
                    Response.Status.OK.getStatusCode(),
                    responseType,
                    GraphqlHttpResource.errorJson(context.validationError())
            );
        }
        if (query == null || query.isBlank()) {
            return new GraphqlPreviewHttpResult(
                    Response.Status.OK.getStatusCode(),
                    responseType,
                    GraphqlHttpResource.errorJson("GraphQL GET query parameter 'query' is required")
            );
        }
        Map<String, Object> variables;
        Map<String, Object> extensions;
        try {
            variables = readJsonObject(variablesJson, "variables");
            extensions = readJsonObject(extensionsJson, "extensions");
        } catch (IllegalArgumentException ex) {
            return new GraphqlPreviewHttpResult(
                    Response.Status.OK.getStatusCode(),
                    responseType,
                    GraphqlHttpResource.errorJson(ex.getMessage())
            );
        }
        return new GraphqlPreviewHttpResult(
                Response.Status.OK.getStatusCode(),
                responseType,
                execute(previewBuildId, query, operationName == null ? "" : operationName, variables, extensions, context)
        );
    }

    private static String execute(
            String previewBuildId,
            String query,
            String operationName,
            Map<String, Object> variables,
            Map<String, Object> extensions,
            GraphqlHttpResource.GraphqlHttpContext context
    ) {
        return GraphqlPreviewRuntimeRouter.execute(
                previewBuildId,
                new GraphqlRequest(query, operationName, variables, extensions),
                new GraphqlRequestContext(
                        context.actorId(),
                        context.actorRole(),
                        context.actorId() > 0L ? "actor-" + context.actorId() : "",
                        context.tenantId(),
                        context.requestId(),
                        "",
                        commaSeparatedList(context.policyFlags()),
                        commaSeparatedList(context.enabledContextFilters()),
                        context.enableIntrospection(),
                        context.hasArticleVisibility(),
                        context.articleVisibility(),
                        context.deadlineBudgetMillis()
                )
        ).json();
    }

    private static List<String> commaSeparatedList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(",")).stream()
                .map(String::trim)
                .filter(item -> item.isEmpty() == false)
                .toList();
    }

    private static GraphqlPreviewHttpResult notAcceptable() {
        return new GraphqlPreviewHttpResult(
                Response.Status.NOT_ACCEPTABLE.getStatusCode(),
                GraphqlHttpResource.GRAPHQL_RESPONSE_JSON,
                GraphqlHttpResource.errorJson("GraphQL response media type is not acceptable")
        );
    }

    private static String stringField(Map<String, Object> request, String field) {
        Object value = request.get(field);
        return value instanceof String string ? string : "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonObjectField(Map<String, Object> request, String field) {
        if (request.containsKey(field) == false || request.get(field) == null) {
            return Map.of();
        }
        return (Map<String, Object>) request.get(field);
    }

    private static Map<String, Object> readJsonObject(String json, String field) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, JSON_OBJECT);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("request field '" + field + "' must be a JSON object", ex);
        }
    }
}
