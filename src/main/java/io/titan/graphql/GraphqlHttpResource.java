package io.titan.graphql;

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
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/graphql")
public final class GraphqlHttpResource {

    private static final List<String> REQUEST_FIELDS = List.of("query", "operationName", "variables", "extensions");
    static final String GRAPHQL_RESPONSE_JSON = "application/graphql-response+json";
    private static final String DEFAULT_ACTOR_ROLE = "reader";

    /** Mode surface (completion plan W5.1): which engine answered this response. */
    static final String EXECUTION_MODE_HEADER = "X-Titan-Execution-Mode";
    /** Mode surface: the deployed package's manifest {@code artifactId} (SQL mode only). */
    static final String DEPLOYMENT_FINGERPRINT_HEADER = "X-Titan-Deployment-Fingerprint";
    static final String EXECUTION_MODE_UNAVAILABLE = "EXECUTION_MODE_UNAVAILABLE";
    private static final int SERVICE_UNAVAILABLE = 503;

    private final GraphqlExecutionEngine engine;
    private final boolean trustRequestContextHeaders;

    /**
     * CDI/Quarkus path. Request-context headers are opt-in because they carry authorization
     * and visibility inputs and must only be supplied by an authenticated, trusted gateway.
     */
    @Inject
    public GraphqlHttpResource(
            GraphqlExecutionEngine engine,
            @ConfigProperty(name = "titan.graphql.http.trust-request-context-headers", defaultValue = "false")
            boolean trustRequestContextHeaders
    ) {
        this.engine = engine;
        this.trustRequestContextHeaders = trustRequestContextHeaders;
    }

    /** Direct-construction path with a safe default for plain JAX-RS and unit tests. */
    public GraphqlHttpResource(GraphqlExecutionEngine engine) {
        this(engine, false);
    }

    /** Direct-construction path (unit tests, plain JAX-RS): system-config engine, default java. */
    public GraphqlHttpResource() {
        this(GraphqlExecutionEngine.fromSystemConfig());
    }

    record GraphqlHttpResult(int status, String mediaType, String body) {
    }

    record GraphqlHttpContext(
            long actorId,
            String actorRole,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility,
            boolean enableIntrospection,
            String tenantId,
            String requestId,
            String policyFlags,
            String enabledContextFilters,
            long deadlineBudgetMillis,
            String validationError
    ) {
        GraphqlHttpContext(
                long actorId,
                String actorRole,
                boolean enablePublishedVisibility,
                boolean hasArticleVisibility,
                boolean articleVisibility,
                boolean enableIntrospection,
                String validationError
        ) {
            this(
                    actorId,
                    actorRole,
                    enablePublishedVisibility,
                    hasArticleVisibility,
                    articleVisibility,
                    enableIntrospection,
                    "",
                    "",
                    "",
                    "",
                    0L,
                    validationError
            );
        }

        public static GraphqlHttpContext defaults() {
            return new GraphqlHttpContext(0L, DEFAULT_ACTOR_ROLE, false, false, false, false, "");
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({GRAPHQL_RESPONSE_JSON, MediaType.APPLICATION_JSON})
    public Response postResponse(
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
        GraphqlHttpResult result = negotiatePost(
                request,
                acceptHeader,
                requestContextFromHeaders(
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
        return withModeSurface(result);
    }

    @GET
    @Produces({GRAPHQL_RESPONSE_JSON, MediaType.APPLICATION_JSON})
    public Response getResponse(
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
        GraphqlHttpResult result = negotiateGet(
                query,
                operationName,
                variablesJson,
                extensionsJson,
                acceptHeader,
                requestContextFromHeaders(
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
        return withModeSurface(result);
    }

    /**
     * Builds the JAX-RS response with the mode surface headers: every response names the
     * engine that answered it, and SQL-mode responses additionally carry the deployment
     * fingerprint for the exact reviewed-model/Titan-package binding.
     */
    private Response withModeSurface(GraphqlHttpResult result) {
        Response.ResponseBuilder response = Response
                .status(result.status())
                .type(result.mediaType())
                .entity(result.body())
                .header(EXECUTION_MODE_HEADER, engine.modeName());
        if (engine.mode() == GraphqlExecutionEngine.Mode.SQL) {
            response.header(DEPLOYMENT_FINGERPRINT_HEADER, engine.fingerprint());
        }
        return response.build();
    }

    public String post(Map<String, Object> request) {
        return executePost(request, GraphqlHttpContext.defaults());
    }

    GraphqlHttpResult negotiatePost(Map<String, Object> request, String acceptHeader) {
        return negotiatePost(request, acceptHeader, GraphqlHttpContext.defaults());
    }

    GraphqlHttpResult negotiatePost(Map<String, Object> request, String acceptHeader, GraphqlHttpContext context) {
        String responseType = responseMediaType(acceptHeader);
        if (responseType.isEmpty()) {
            return new GraphqlHttpResult(
                    Response.Status.NOT_ACCEPTABLE.getStatusCode(),
                    GRAPHQL_RESPONSE_JSON,
                    errorJson("GraphQL response media type is not acceptable")
            );
        }
        if (context.validationError().isEmpty() == false) {
            return new GraphqlHttpResult(Response.Status.OK.getStatusCode(), responseType, errorJson(context.validationError()));
        }
        try {
            return new GraphqlHttpResult(Response.Status.OK.getStatusCode(), responseType, executePost(request, context));
        } catch (GraphqlExecutionModeUnavailableException unavailable) {
            return executionModeUnavailable(responseType, unavailable);
        }
    }

    GraphqlHttpResult negotiateGet(
            String query,
            String operationName,
            String variablesJson,
            String extensionsJson,
            String acceptHeader
    ) {
        return negotiateGet(
                query,
                operationName,
                variablesJson,
                extensionsJson,
                acceptHeader,
                GraphqlHttpContext.defaults()
        );
    }

    GraphqlHttpResult negotiateGet(
            String query,
            String operationName,
            String variablesJson,
            String extensionsJson,
            String acceptHeader,
            GraphqlHttpContext context
    ) {
        String responseType = responseMediaType(acceptHeader);
        if (responseType.isEmpty()) {
            return new GraphqlHttpResult(
                    Response.Status.NOT_ACCEPTABLE.getStatusCode(),
                    GRAPHQL_RESPONSE_JSON,
                    errorJson("GraphQL response media type is not acceptable")
            );
        }
        if (context.validationError().isEmpty() == false) {
            return new GraphqlHttpResult(Response.Status.OK.getStatusCode(), responseType, errorJson(context.validationError()));
        }
        try {
            return new GraphqlHttpResult(
                    Response.Status.OK.getStatusCode(),
                    responseType,
                    executeGet(query, operationName, variablesJson, extensionsJson, context)
            );
        } catch (GraphqlExecutionModeUnavailableException unavailable) {
            return executionModeUnavailable(responseType, unavailable);
        }
    }

    /**
     * Failure honesty (completion plan W5.1): SQL mode with an unreachable or undeployed
     * database answers 503 with a descriptive GraphQL error naming the mode, the datasource,
     * and the remedy — never a silent fallback to Java mode.
     */
    private static GraphqlHttpResult executionModeUnavailable(
            String responseType, GraphqlExecutionModeUnavailableException unavailable) {
        return new GraphqlHttpResult(
                SERVICE_UNAVAILABLE,
                responseType,
                errorJson(unavailable.getMessage(), EXECUTION_MODE_UNAVAILABLE)
        );
    }

    private String executePost(Map<String, Object> request, GraphqlHttpContext context) {
        String transportError = transportValidationError(request);
        if (transportError.isEmpty() == false) {
            return errorJson(transportError);
        }

        String query = (String) request.get("query");
        String operationName = "";
        if (request.containsKey("operationName") && request.get("operationName") != null) {
            operationName = (String) request.get("operationName");
        }
        String variablesJson = jsonObjectField(request, "variables");
        String extensionsJson = jsonObjectField(request, "extensions");
        return engine.runtime().execute(
                new GraphqlRuntimeRequest(query, operationName, variablesJson, extensionsJson),
                requestContext(context)
        );
    }

    private String executeGet(
            String query,
            String operationName,
            String variablesJson,
            String extensionsJson,
            GraphqlHttpContext context
    ) {
        if (query == null || query.isBlank()) {
            return errorJson("GraphQL GET query parameter 'query' is required");
        }
        return engine.runtime().execute(
                new GraphqlRuntimeRequest(
                        query,
                        operationName == null ? "" : operationName,
                        variablesJson == null ? "" : variablesJson,
                        extensionsJson == null ? "" : extensionsJson
                ),
                requestContext(context)
        );
    }

    private static GraphqlRequestContext requestContext(GraphqlHttpContext context) {
        return new GraphqlRequestContext(
                context.actorId(),
                context.actorRole(),
                context.actorId() > 0L ? "actor-" + context.actorId() : "",
                context.tenantId(),
                context.requestId(),
                "",
                listFromHeader(context.policyFlags()),
                enabledContextFilters(context),
                context.enableIntrospection(),
                context.hasArticleVisibility(),
                context.articleVisibility(),
                context.deadlineBudgetMillis()
        );
    }

    private GraphqlHttpContext requestContextFromHeaders(
            String actorIdHeader,
            String actorRoleHeader,
            String publishedVisibilityHeader,
            String articleVisibilityHeader,
            String introspectionHeader,
            String tenantIdHeader,
            String requestIdHeader,
            String policyFlagsHeader,
            String enabledContextFiltersHeader,
            String deadlineBudgetMillisHeader
    ) {
        if (trustRequestContextHeaders == false) {
            return GraphqlHttpContext.defaults();
        }
        return httpContext(
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
        );
    }

    private static List<String> enabledContextFilters(GraphqlHttpContext context) {
        List<String> parsedFilters = listFromHeader(context.enabledContextFilters());
        if (context.enablePublishedVisibility() && parsedFilters.contains("publishedVisibility") == false) {
            List<String> filters = new java.util.ArrayList<>(parsedFilters);
            filters.add("publishedVisibility");
            return List.copyOf(filters);
        }
        return parsedFilters;
    }

    private static List<String> listFromHeader(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String[] parts = value.split(",");
        List<String> values = new java.util.ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty() == false) {
                values.add(trimmed);
            }
        }
        return List.copyOf(values);
    }

    static GraphqlHttpContext httpContext(
            String actorIdHeader,
            String actorRoleHeader,
            String publishedVisibilityHeader,
            String articleVisibilityHeader,
            String introspectionHeader
    ) {
        return httpContext(
                actorIdHeader,
                actorRoleHeader,
                publishedVisibilityHeader,
                articleVisibilityHeader,
                introspectionHeader,
                null,
                null,
                null,
                null,
                null
        );
    }

    static GraphqlHttpContext httpContext(
            String actorIdHeader,
            String actorRoleHeader,
            String publishedVisibilityHeader,
            String articleVisibilityHeader,
            String introspectionHeader,
            String tenantIdHeader,
            String requestIdHeader,
            String policyFlagsHeader,
            String enabledContextFiltersHeader,
            String deadlineBudgetMillisHeader
    ) {
        long actorId = 0L;
        if (actorIdHeader != null && actorIdHeader.isBlank() == false) {
            try {
                actorId = Long.parseLong(actorIdHeader.trim());
            } catch (NumberFormatException ignored) {
                return new GraphqlHttpContext(0L, DEFAULT_ACTOR_ROLE, false, false, false, false,
                        "X-Titan-Actor-Id must be an integer");
            }
        }
        String actorRole = DEFAULT_ACTOR_ROLE;
        if (actorRoleHeader != null && actorRoleHeader.isBlank() == false) {
            actorRole = actorRoleHeader.trim();
        }
        Boolean enablePublishedVisibility = optionalBoolean(publishedVisibilityHeader);
        if (enablePublishedVisibility == null) {
            return new GraphqlHttpContext(0L, DEFAULT_ACTOR_ROLE, false, false, false, false,
                    "X-Titan-Context-Filter-Published-Visibility must be true or false");
        }
        Boolean articleVisibility = optionalBoolean(articleVisibilityHeader);
        if (articleVisibility == null) {
            return new GraphqlHttpContext(0L, DEFAULT_ACTOR_ROLE, false, false, false, false,
                    "X-Titan-Article-Visibility must be true or false");
        }
        Boolean enableIntrospection = optionalBoolean(introspectionHeader);
        if (enableIntrospection == null) {
            return new GraphqlHttpContext(0L, DEFAULT_ACTOR_ROLE, false, false, false, false,
                    "X-Titan-Introspection must be true or false");
        }
        long deadlineBudgetMillis = 0L;
        if (deadlineBudgetMillisHeader != null && deadlineBudgetMillisHeader.isBlank() == false) {
            try {
                deadlineBudgetMillis = Long.parseLong(deadlineBudgetMillisHeader.trim());
            } catch (NumberFormatException ignored) {
                return new GraphqlHttpContext(0L, DEFAULT_ACTOR_ROLE, false, false, false, false,
                        "X-Titan-Deadline-Budget-Millis must be a non-negative integer");
            }
            if (deadlineBudgetMillis < 0L) {
                return new GraphqlHttpContext(0L, DEFAULT_ACTOR_ROLE, false, false, false, false,
                        "X-Titan-Deadline-Budget-Millis must be a non-negative integer");
            }
        }
        return new GraphqlHttpContext(
                actorId,
                actorRole,
                Boolean.TRUE.equals(enablePublishedVisibility),
                articleVisibilityHeader != null && articleVisibilityHeader.isBlank() == false,
                Boolean.TRUE.equals(articleVisibility),
                Boolean.TRUE.equals(enableIntrospection),
                trimHeader(tenantIdHeader),
                trimHeader(requestIdHeader),
                trimHeader(policyFlagsHeader),
                trimHeader(enabledContextFiltersHeader),
                deadlineBudgetMillis,
                ""
        );
    }

    private static String trimHeader(String value) {
        return value == null ? "" : value.trim();
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

    static String responseMediaType(String acceptHeader) {
        if (acceptHeader == null || acceptHeader.isBlank()) {
            return GRAPHQL_RESPONSE_JSON;
        }
        String selected = "";
        double selectedQ = -1.0;
        String[] ranges = acceptHeader.split(",");
        for (String range : ranges) {
            String[] parts = range.split(";");
            String mediaRange = parts[0].trim();
            double q = acceptQuality(parts);
            if (q <= 0.0) {
                continue;
            }
            String candidate = "";
            if (mediaRange.equalsIgnoreCase(GRAPHQL_RESPONSE_JSON)
                    || mediaRange.equals("*/*")
                    || mediaRange.equalsIgnoreCase("application/*")) {
                candidate = GRAPHQL_RESPONSE_JSON;
            } else if (mediaRange.equalsIgnoreCase(MediaType.APPLICATION_JSON)) {
                candidate = MediaType.APPLICATION_JSON;
            }
            if (candidate.isEmpty() == false
                    && (q > selectedQ || (q == selectedQ && candidate.equals(GRAPHQL_RESPONSE_JSON)))) {
                selected = candidate;
                selectedQ = q;
            }
        }
        return selected;
    }

    private static double acceptQuality(String[] parts) {
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.startsWith("q=")) {
                try {
                    return Double.parseDouble(part.substring(2));
                } catch (NumberFormatException ignored) {
                    return 0.0;
                }
            }
        }
        return 1.0;
    }

    static String transportValidationError(Map<String, Object> request) {
        if (request == null) {
            return "GraphQL request body must be a JSON object";
        }
        for (String field : request.keySet()) {
            if (REQUEST_FIELDS.contains(field) == false) {
                return "unknown GraphQL request field '" + field + "'";
            }
        }
        if (request.get("query") instanceof String == false) {
            return "GraphQL request field 'query' is required and must be a string";
        }
        if (request.containsKey("operationName")
                && request.get("operationName") != null
                && request.get("operationName") instanceof String == false) {
            return "GraphQL request field 'operationName' must be a string";
        }
        if (request.containsKey("variables") && request.get("variables") instanceof Map<?, ?> == false) {
            return "GraphQL request field 'variables' must be a JSON object";
        }
        if (request.containsKey("extensions") && request.get("extensions") instanceof Map<?, ?> == false) {
            return "GraphQL request field 'extensions' must be a JSON object";
        }
        return "";
    }

    private static String jsonObjectField(Map<String, Object> request, String field) {
        if (request.containsKey(field) == false) {
            return "";
        }
        return writeJson(request.get(field));
    }

    private static String writeJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String string) {
            return "\"" + escape(string) + "\"";
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        if (value instanceof Boolean bool) {
            return bool.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder json = new StringBuilder();
            json.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String == false) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                if (first == false) {
                    json.append(',');
                }
                json.append('"').append(escape((String) entry.getKey())).append("\":");
                json.append(writeJson(entry.getValue()));
                first = false;
            }
            json.append('}');
            return json.toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder json = new StringBuilder();
            json.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (first == false) {
                    json.append(',');
                }
                json.append(writeJson(item));
                first = false;
            }
            json.append(']');
            return json.toString();
        }
        return "\"" + escape(value.toString()) + "\"";
    }

    static String errorJson(String message) {
        return errorJson(message, GraphqlException.VALIDATION_ERROR);
    }

    static String errorJson(String message, String code) {
        return "{\"errors\":[{\"message\":\""
                + escape(message)
                + "\",\"extensions\":{\"code\":\""
                + escape(code)
                + "\"}}]}";
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                escaped.append('\\').append(c);
            } else if (c == '\n') {
                escaped.append("\\n");
            } else if (c == '\r') {
                escaped.append("\\r");
            } else if (c == '\t') {
                escaped.append("\\t");
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
