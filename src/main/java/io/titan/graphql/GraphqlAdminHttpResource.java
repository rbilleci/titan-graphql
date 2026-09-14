package io.titan.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/admin/graphql")
public final class GraphqlAdminHttpResource {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_MANAGEMENT_ROLE = "operator";
    private final String accessToken;
    private final String authenticatedRole;
    private final String authenticatedActorKey;

    /**
     * The management plane is disabled until a bearer token is configured. The token and the
     * effective role come from server configuration, never caller-controlled headers.
     */
    @Inject
    public GraphqlAdminHttpResource(
            @ConfigProperty(name = "titan.graphql.admin.access-token", defaultValue = "") String accessToken,
            @ConfigProperty(name = "titan.graphql.admin.role", defaultValue = DEFAULT_MANAGEMENT_ROLE)
            String authenticatedRole,
            @ConfigProperty(name = "titan.graphql.admin.actor-key", defaultValue = "titan-admin")
            String authenticatedActorKey
    ) {
        this.accessToken = accessToken;
        this.authenticatedRole = authenticatedRole;
        this.authenticatedActorKey = authenticatedActorKey;
    }

    /** Direct construction is retained for the embedded Java API and its unit tests. */
    public GraphqlAdminHttpResource() {
        this("", DEFAULT_MANAGEMENT_ROLE, "");
    }

    record GraphqlAdminHttpResult(int status, String mediaType, String body) {
    }

    record GraphqlAdminHttpContext(
            String actorKey,
            String actorRole,
            String requestId,
            String idempotencyKey,
            boolean enableIntrospection,
            String validationError
    ) {
        static GraphqlAdminHttpContext defaults() {
            return new GraphqlAdminHttpContext("", DEFAULT_MANAGEMENT_ROLE, "", "", false, "");
        }

        GraphqlRequestContext toGraphqlRequestContext() {
            return new GraphqlRequestContext(
                    0L,
                    actorRole,
                    actorKey,
                    "",
                    requestId,
                    idempotencyKey,
                    List.of("management"),
                    List.of(),
                    enableIntrospection,
                    false,
                    false,
                    0L
            );
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({GraphqlHttpResource.GRAPHQL_RESPONSE_JSON, MediaType.APPLICATION_JSON})
    public Response postResponse(
            Map<String, Object> request,
            @HeaderParam("Accept") String acceptHeader,
            @HeaderParam("Authorization") String authorizationHeader,
            @HeaderParam("X-Titan-Management-Request-Id") String requestIdHeader,
            @HeaderParam("X-Titan-Management-Idempotency-Key") String idempotencyKeyHeader,
            @HeaderParam("X-Titan-Management-Introspection") String introspectionHeader
    ) {
        Response authorizationFailure = authorizationFailure(authorizationHeader);
        if (authorizationFailure != null) {
            return authorizationFailure;
        }
        GraphqlAdminHttpResult result = negotiatePost(
                request,
                acceptHeader,
                authenticatedHttpContext(requestIdHeader, idempotencyKeyHeader, introspectionHeader)
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
            @QueryParam("query") String query,
            @QueryParam("operationName") String operationName,
            @QueryParam("variables") String variablesJson,
            @QueryParam("extensions") String extensionsJson,
            @HeaderParam("Accept") String acceptHeader,
            @HeaderParam("Authorization") String authorizationHeader,
            @HeaderParam("X-Titan-Management-Request-Id") String requestIdHeader,
            @HeaderParam("X-Titan-Management-Idempotency-Key") String idempotencyKeyHeader,
            @HeaderParam("X-Titan-Management-Introspection") String introspectionHeader
    ) {
        Response authorizationFailure = authorizationFailure(authorizationHeader);
        if (authorizationFailure != null) {
            return authorizationFailure;
        }
        GraphqlAdminHttpResult result = negotiateGet(
                query,
                operationName,
                variablesJson,
                extensionsJson,
                acceptHeader,
                authenticatedHttpContext(requestIdHeader, idempotencyKeyHeader, introspectionHeader)
        );
        return Response
                .status(result.status())
                .type(result.mediaType())
                .entity(result.body())
                .build();
    }

    GraphqlAdminHttpResult negotiatePost(Map<String, Object> request, String acceptHeader) {
        return negotiatePost(request, acceptHeader, GraphqlAdminHttpContext.defaults());
    }

    private GraphqlAdminHttpContext authenticatedHttpContext(
            String requestIdHeader,
            String idempotencyKeyHeader,
            String introspectionHeader
    ) {
        return httpContext(
                authenticatedActorKey,
                authenticatedRole,
                requestIdHeader,
                idempotencyKeyHeader,
                introspectionHeader
        );
    }

    private Response authorizationFailure(String authorizationHeader) {
        if (accessToken.isBlank()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        String prefix = "Bearer ";
        if (authorizationHeader == null || authorizationHeader.startsWith(prefix) == false) {
            return Response.status(Response.Status.UNAUTHORIZED).header("WWW-Authenticate", "Bearer").build();
        }
        byte[] provided = authorizationHeader.substring(prefix.length()).getBytes(StandardCharsets.UTF_8);
        byte[] expected = accessToken.getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(expected, provided) == false) {
            return Response.status(Response.Status.UNAUTHORIZED).header("WWW-Authenticate", "Bearer").build();
        }
        return null;
    }

    GraphqlAdminHttpResult negotiatePost(
            Map<String, Object> request,
            String acceptHeader,
            GraphqlAdminHttpContext context
    ) {
        String responseType = GraphqlHttpResource.responseMediaType(acceptHeader);
        if (responseType.isEmpty()) {
            return new GraphqlAdminHttpResult(
                    Response.Status.NOT_ACCEPTABLE.getStatusCode(),
                    GraphqlHttpResource.GRAPHQL_RESPONSE_JSON,
                    GraphqlHttpResource.errorJson("GraphQL response media type is not acceptable")
            );
        }
        if (context.validationError().isEmpty() == false) {
            return new GraphqlAdminHttpResult(
                    Response.Status.OK.getStatusCode(),
                    responseType,
                    GraphqlHttpResource.errorJson(context.validationError())
            );
        }
        return new GraphqlAdminHttpResult(
                Response.Status.OK.getStatusCode(),
                responseType,
                executePost(request, context)
        );
    }

    GraphqlAdminHttpResult negotiateGet(
            String query,
            String operationName,
            String variablesJson,
            String extensionsJson,
            String acceptHeader,
            GraphqlAdminHttpContext context
    ) {
        String responseType = GraphqlHttpResource.responseMediaType(acceptHeader);
        if (responseType.isEmpty()) {
            return new GraphqlAdminHttpResult(
                    Response.Status.NOT_ACCEPTABLE.getStatusCode(),
                    GraphqlHttpResource.GRAPHQL_RESPONSE_JSON,
                    GraphqlHttpResource.errorJson("GraphQL response media type is not acceptable")
            );
        }
        if (context.validationError().isEmpty() == false) {
            return new GraphqlAdminHttpResult(
                    Response.Status.OK.getStatusCode(),
                    responseType,
                    GraphqlHttpResource.errorJson(context.validationError())
            );
        }
        return new GraphqlAdminHttpResult(
                Response.Status.OK.getStatusCode(),
                responseType,
                executeGet(query, operationName, variablesJson, extensionsJson, context)
        );
    }

    static GraphqlAdminHttpContext httpContext(
            String actorIdHeader,
            String actorRoleHeader,
            String requestIdHeader,
            String idempotencyKeyHeader,
            String introspectionHeader
    ) {
        Boolean enableIntrospection = optionalBoolean(introspectionHeader);
        if (enableIntrospection == null) {
            return new GraphqlAdminHttpContext(
                    "",
                    DEFAULT_MANAGEMENT_ROLE,
                    "",
                    "",
                    false,
                    "X-Titan-Management-Introspection must be true or false"
            );
        }
        String actorRole = DEFAULT_MANAGEMENT_ROLE;
        if (actorRoleHeader != null && actorRoleHeader.isBlank() == false) {
            actorRole = actorRoleHeader.trim();
        }
        return new GraphqlAdminHttpContext(
                actorIdHeader == null ? "" : actorIdHeader.trim(),
                actorRole,
                requestIdHeader == null ? "" : requestIdHeader.trim(),
                idempotencyKeyHeader == null ? "" : idempotencyKeyHeader.trim(),
                Boolean.TRUE.equals(enableIntrospection),
                ""
        );
    }

    private static String executePost(Map<String, Object> request, GraphqlAdminHttpContext context) {
        String transportError = GraphqlHttpResource.transportValidationError(request);
        if (transportError.isEmpty() == false) {
            return GraphqlHttpResource.errorJson(transportError);
        }
        String query = (String) request.get("query");
        String operationName = "";
        if (request.containsKey("operationName") && request.get("operationName") != null) {
            operationName = (String) request.get("operationName");
        }
        return execute(
                query,
                operationName,
                jsonObjectField(request, "variables"),
                jsonObjectField(request, "extensions"),
                context
        );
    }

    private static String executeGet(
            String query,
            String operationName,
            String variablesJson,
            String extensionsJson,
            GraphqlAdminHttpContext context
    ) {
        if (query == null || query.isBlank()) {
            return GraphqlHttpResource.errorJson("GraphQL GET query parameter 'query' is required");
        }
        return execute(
                query,
                operationName == null ? "" : operationName,
                variablesJson == null ? "" : variablesJson,
                extensionsJson == null ? "" : extensionsJson,
                context
        );
    }

    private static String execute(
            String query,
            String operationName,
            String variablesJson,
            String extensionsJson,
            GraphqlAdminHttpContext context
    ) {
        return GraphqlRuntimeRegistry.managementRuntime().execute(
                new GraphqlRuntimeRequest(query, operationName, variablesJson, extensionsJson),
                context.toGraphqlRequestContext()
        );
    }

    private static String jsonObjectField(Map<String, Object> request, String field) {
        if (request.containsKey(field) == false || request.get(field) == null) {
            return "";
        }
        try {
            return JSON.writeValueAsString(request.get(field));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("request field '" + field + "' must be a JSON object", ex);
        }
    }

    private static Boolean optionalBoolean(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim();
        if (normalized.equalsIgnoreCase("true")) {
            return true;
        }
        if (normalized.equalsIgnoreCase("false")) {
            return false;
        }
        return null;
    }
}
