package io.titan.graphql.frontend;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Standalone HTTP host for a database-resident GraphQL engine.
 *
 * <p>The host performs transport work only: JSON-envelope decoding, media negotiation, request
 * size enforcement, trusted-context construction, and one database invocation. It does not
 * inspect the GraphQL document or response, and contains no GraphQL parser, validator, planner,
 * schema, cursor, policy, or response-rendering implementation.</p>
 */
public final class DatabaseGraphqlHttpServer implements AutoCloseable {

    public static final String GRAPHQL_RESPONSE_JSON = "application/graphql-response+json";
    public static final String EXECUTION_MODE_HEADER = "X-Titan-Execution-Mode";
    public static final String DEPLOYMENT_FINGERPRINT_HEADER = "X-Titan-Deployment-Fingerprint";
    private static final int DEFAULT_MAX_BODY_BYTES = 1_048_576;
    private static final String CONTEXT_VALUES_HEADER = "X-Titan-Context-Values";
    private static final ObjectMapper JSON = new ObjectMapper()
            // The tree model otherwise retains only the last duplicate member. Reject ambiguity
            // before serializing variables/extensions so a thin transport layer never changes a
            // value that the transpiled GraphQL engine is meant to validate.
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    /**
     * Deployment-owned inputs. In particular, the entry point and expected model hash are never
     * read from an HTTP request. Package tooling is responsible for deriving this configuration
     * from the verified deployment manifest.
     */
    public record Configuration(
            DatabaseWholeRequestClient.Dialect dialect,
            DatabaseWholeRequestClient.ConnectionProvider connectionProvider,
            DatabaseWholeRequestClient.EntryPoint entryPoint,
            String expectedModelSemanticHash,
            String expectedRuntimeIdentity,
            String expectedPackageIdentity,
            String deploymentFingerprint,
            boolean trustRequestContextHeaders,
            int maxBodyBytes,
            int statementTimeoutSeconds
    ) {
        public Configuration {
            Objects.requireNonNull(dialect, "dialect");
            Objects.requireNonNull(connectionProvider, "connectionProvider");
            Objects.requireNonNull(entryPoint, "entryPoint");
            if (expectedModelSemanticHash == null || !expectedModelSemanticHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("expected model semantic hash must be a lowercase SHA-256 value");
            }
            if (expectedRuntimeIdentity == null || !expectedRuntimeIdentity.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("expected runtime identity must be a lowercase SHA-256 value");
            }
            if (expectedPackageIdentity == null || !expectedPackageIdentity.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("expected package identity must be a lowercase SHA-256 value");
            }
            if (deploymentFingerprint == null
                    || (deploymentFingerprint.isEmpty() == false
                    && deploymentFingerprint.matches("[0-9a-f]{64}") == false)) {
                throw new IllegalArgumentException("deployment fingerprint must be empty or a lowercase SHA-256 value");
            }
            if (maxBodyBytes <= 0) {
                throw new IllegalArgumentException("max body bytes must be positive");
            }
            if (statementTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("database statement timeout must be positive");
            }
        }

        public Configuration(
                DatabaseWholeRequestClient.Dialect dialect,
                DatabaseWholeRequestClient.ConnectionProvider connectionProvider,
                DatabaseWholeRequestClient.EntryPoint entryPoint,
                String expectedModelSemanticHash,
                String expectedRuntimeIdentity,
                String expectedPackageIdentity,
                boolean trustRequestContextHeaders
        ) {
            this(dialect, connectionProvider, entryPoint, expectedModelSemanticHash, expectedRuntimeIdentity,
                    expectedPackageIdentity, "",
                    trustRequestContextHeaders, DEFAULT_MAX_BODY_BYTES,
                    DatabaseWholeRequestClient.DEFAULT_STATEMENT_TIMEOUT_SECONDS);
        }
    }

    /**
     * Reads the immutable package-selection values emitted by package tooling. The descriptor
     * cannot configure a driver, URL, credentials, or arbitrary routine name from a client
     * request; connection acquisition remains deployment-owned.
     */
    public static Configuration fromDeploymentDescriptor(
            Path descriptor,
            DatabaseWholeRequestClient.ConnectionProvider connectionProvider,
            boolean trustRequestContextHeaders,
            int maxBodyBytes,
            int statementTimeoutSeconds
    ) throws IOException {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(connectionProvider, "connectionProvider");
        Properties properties = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(descriptor, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String schemaVersion = requiredProperty(properties, "schema-version");
        if (schemaVersion.equals("titan.graphql.database-frontend-deployment.v1") == false) {
            throw new IllegalArgumentException("unsupported database frontend descriptor schema " + schemaVersion);
        }
        String dialectName = requiredProperty(properties, "database-dialect");
        DatabaseWholeRequestClient.Dialect dialect = switch (dialectName) {
            case "postgresql" -> DatabaseWholeRequestClient.Dialect.POSTGRESQL;
            case "mysql" -> DatabaseWholeRequestClient.Dialect.MYSQL;
            default -> throw new IllegalArgumentException("unsupported database frontend dialect " + dialectName);
        };
        return new Configuration(
                dialect,
                connectionProvider,
                new DatabaseWholeRequestClient.EntryPoint(
                        requiredProperty(properties, "entry-point-schema"),
                        requiredProperty(properties, "entry-point-routine")),
                requiredProperty(properties, "model-semantic-sha256"),
                requiredProperty(properties, "runtime-identity-sha256"),
                requiredProperty(properties, "package-identity-sha256"),
                requiredProperty(properties, "deployment-fingerprint"),
                trustRequestContextHeaders,
                maxBodyBytes,
                statementTimeoutSeconds);
    }

    private final HttpServer server;
    private final ExecutorService executor;

    private DatabaseGraphqlHttpServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /** Starts a host bound to the supplied address (use port {@code 0} for an ephemeral port). */
    public static DatabaseGraphqlHttpServer start(InetSocketAddress address, Configuration configuration)
            throws IOException {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(configuration, "configuration");
        HttpServer server = HttpServer.create(address, 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        com.sun.net.httpserver.HttpContext context = server.createContext("/graphql", new Handler(configuration));
        context.getAttributes().put("maxBodyBytes", configuration.maxBodyBytes());
        server.start();
        return new DatabaseGraphqlHttpServer(server, executor);
    }

    /** The absolute HTTP endpoint address after the server is started. */
    public URI endpointUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/graphql");
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static final class Handler implements HttpHandler {

        private final Configuration configuration;
        private final DatabaseWholeRequestClient client;

        private Handler(Configuration configuration) {
            this.configuration = configuration;
            this.client = new DatabaseWholeRequestClient(
                    configuration.dialect(), configuration.connectionProvider(), configuration.entryPoint(),
                    configuration.statementTimeoutSeconds());
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String responseType = responseMediaType(exchange.getRequestHeaders().getFirst("Accept"));
            if (responseType.isEmpty()) {
                send(exchange, 406, GRAPHQL_RESPONSE_JSON,
                        transportError("GraphQL response media type is not acceptable"),
                        configuration.deploymentFingerprint());
                return;
            }
            try {
                Envelope envelope = switch (exchange.getRequestMethod().toUpperCase(Locale.ROOT)) {
                    case "POST" -> postEnvelope(exchange);
                    case "GET" -> getEnvelope(exchange);
                    default -> null;
                };
                if (envelope == null) {
                    exchange.getResponseHeaders().set("Allow", "GET, POST");
                    send(exchange, 405, responseType, transportError("only GET and POST are supported"),
                            configuration.deploymentFingerprint());
                    return;
                }
                TrustedContext trustedContext = trustedContext(exchange, configuration.trustRequestContextHeaders(),
                        configuration.statementTimeoutSeconds());
                String response = client.execute(new DatabaseWholeRequestClient.Request(
                        envelope.query(), envelope.operationName(), envelope.variablesJson(), envelope.extensionsJson(),
                        trustedContext.json(), envelope.allowMutations(), configuration.expectedModelSemanticHash(),
                        configuration.expectedRuntimeIdentity(), configuration.expectedPackageIdentity(),
                        trustedContext.statementTimeoutSeconds()))
                        .responseJson();
                send(exchange, 200, responseType, response, configuration.deploymentFingerprint());
            } catch (TransportException badRequest) {
                send(exchange, badRequest.status(), responseType, transportError(badRequest.getMessage()),
                        configuration.deploymentFingerprint());
            } catch (SQLException databaseUnavailable) {
                send(exchange, 503, responseType,
                        transportError("database whole-request engine is unavailable"),
                        configuration.deploymentFingerprint());
            } catch (RuntimeException unexpected) {
                send(exchange, 503, responseType,
                        transportError("database whole-request engine is unavailable"),
                        configuration.deploymentFingerprint());
            }
        }
    }

    private record Envelope(
            String query,
            String operationName,
            String variablesJson,
            String extensionsJson,
            boolean allowMutations
    ) {
    }

    /** Trusted context plus the transport-only deadline for the one JDBC routine invocation. */
    private record TrustedContext(String json, int statementTimeoutSeconds) {
    }

    private static Envelope postEnvelope(HttpExchange exchange) throws IOException, TransportException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || contentType.toLowerCase(Locale.ROOT).startsWith("application/json") == false) {
            throw new TransportException(415, "GraphQL POST requires an application/json request body");
        }
        byte[] bytes = readAtMost(exchange, maxBodyBytes(exchange));
        JsonNode root;
        try {
            root = JSON.readTree(bytes);
        } catch (IOException malformedJson) {
            throw new TransportException(400, "GraphQL request body must be valid JSON");
        }
        if (root == null || root.isObject() == false) {
            throw new TransportException(400, "GraphQL request body must be a JSON object");
        }
        for (java.util.Iterator<String> names = root.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (name.equals("query") == false && name.equals("operationName") == false
                    && name.equals("variables") == false && name.equals("extensions") == false) {
                throw new TransportException(400, "unknown GraphQL request field '" + name + "'");
            }
        }
        JsonNode query = root.get("query");
        if (query == null || query.isTextual() == false) {
            throw new TransportException(400, "GraphQL request field 'query' is required and must be a string");
        }
        JsonNode operationName = root.get("operationName");
        if (operationName != null && operationName.isNull() == false && operationName.isTextual() == false) {
            throw new TransportException(400, "GraphQL request field 'operationName' must be a string");
        }
        return new Envelope(
                query.textValue(),
                operationName == null || operationName.isNull() ? "" : operationName.textValue(),
                jsonObject(root.get("variables"), "variables"),
                jsonObject(root.get("extensions"), "extensions"),
                true);
    }

    private static Envelope getEnvelope(HttpExchange exchange) throws TransportException {
        Map<String, String> query = queryParameters(exchange.getRequestURI().getRawQuery());
        String document = query.get("query");
        if (document == null || document.isBlank()) {
            throw new TransportException(400, "GraphQL GET query parameter 'query' is required");
        }
        return new Envelope(
                document,
                query.getOrDefault("operationName", ""),
                query.getOrDefault("variables", ""),
                query.getOrDefault("extensions", ""),
                false);
    }

    private static int maxBodyBytes(HttpExchange exchange) {
        // The configured limit is checked by readAtMost. This helper exists only to keep the
        // request path explicit; the handler's configuration is captured by the exchange context.
        Object limit = exchange.getHttpContext().getAttributes().get("maxBodyBytes");
        return limit instanceof Integer integer ? integer : DEFAULT_MAX_BODY_BYTES;
    }

    private static byte[] readAtMost(HttpExchange exchange, int maxBodyBytes) throws IOException, TransportException {
        String header = exchange.getRequestHeaders().getFirst("Content-Length");
        if (header != null && header.isBlank() == false) {
            try {
                if (Long.parseLong(header) > maxBodyBytes) {
                    throw new TransportException(413, "GraphQL request body exceeds the configured size limit");
                }
            } catch (NumberFormatException invalidLength) {
                throw new TransportException(400, "Content-Length must be an integer");
            }
        }
        try (java.io.InputStream input = exchange.getRequestBody();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > maxBodyBytes) {
                    throw new TransportException(413, "GraphQL request body exceeds the configured size limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String jsonObject(JsonNode value, String fieldName) throws TransportException {
        // GraphQL-over-HTTP permits a nullable variables/extensions envelope member. A JSON null
        // means the member is absent; preserve that transport fact as the empty optional value
        // for the database engine rather than rejecting it or inventing an object in the HTTP
        // process. The engine remains the sole owner of GraphQL input/default semantics.
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isObject() == false) {
            throw new TransportException(400, "GraphQL request field '" + fieldName + "' must be a JSON object");
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException serializationFailure) {
            throw new TransportException(400, "GraphQL request field '" + fieldName + "' is invalid");
        }
    }

    private static Map<String, String> queryParameters(String rawQuery) throws TransportException {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return values;
        }
        for (String part : rawQuery.split("&", -1)) {
            int separator = part.indexOf('=');
            String rawName = separator < 0 ? part : part.substring(0, separator);
            String rawValue = separator < 0 ? "" : part.substring(separator + 1);
            String name = decodeQueryComponent(rawName);
            if (values.putIfAbsent(name, decodeQueryComponent(rawValue)) != null) {
                throw new TransportException(400, "duplicate GraphQL GET parameter '" + name + "'");
            }
        }
        return values;
    }

    private static String decodeQueryComponent(String rawValue) throws TransportException {
        try {
            // RFC 3986 treats '+' as a literal plus in a URI query; URLDecoder treats it as form
            // whitespace, so protect it before decoding percent-encoded UTF-8 data.
            return URLDecoder.decode(rawValue.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedEncoding) {
            throw new TransportException(400, "GraphQL GET parameters must be percent encoded");
        }
    }

    private static TrustedContext trustedContext(
            HttpExchange exchange,
            boolean trustHeaders,
            int configuredStatementTimeoutSeconds
    ) throws TransportException {
        ObjectNode context = JSON.createObjectNode();
        context.put("contextVersion", "titan.graphql.request-context/v1");
        long actorId = 0L;
        String actorRole = "";
        boolean introspectionEnabled = false;
        String tenantId = "";
        String requestId = "";
        List<String> policyFlags = List.of();
        List<String> enabledContextFilters = List.of();
        long deadlineBudgetMillis = 0L;
        ObjectNode values = context.putObject("contextValues");
        if (trustHeaders) {
            actorId = nonNegativeLong(header(exchange, "X-Titan-Actor-Id"), "X-Titan-Actor-Id");
            actorRole = header(exchange, "X-Titan-Actor-Role");
            introspectionEnabled = optionalBoolean(header(exchange, "X-Titan-Introspection"), "X-Titan-Introspection");
            tenantId = header(exchange, "X-Titan-Tenant-Id");
            requestId = header(exchange, "X-Titan-Request-Id");
            policyFlags = commaSeparated(header(exchange, "X-Titan-Policy-Flags"));
            enabledContextFilters = commaSeparated(header(exchange, "X-Titan-Context-Filters"));
            appendTrustedContextValues(exchange, values);
            deadlineBudgetMillis = nonNegativeLong(
                    header(exchange, "X-Titan-Deadline-Budget-Millis"), "X-Titan-Deadline-Budget-Millis");
        }
        context.put("actorId", actorId);
        context.put("actorRole", actorRole);
        context.put("actorKey", actorId > 0L ? "actor-" + actorId : "");
        context.put("tenantId", tenantId);
        context.put("requestId", requestId);
        context.put("idempotencyKey", "");
        stringArray(context.putArray("policyFlags"), policyFlags);
        stringArray(context.putArray("enabledContextFilters"), enabledContextFilters);
        context.put("introspectionEnabled", introspectionEnabled);
        context.put("deadlineEpochMillis",
                deadlineBudgetMillis == 0L ? 0L : Math.addExact(System.currentTimeMillis(), deadlineBudgetMillis));
        try {
            long requestedSeconds = deadlineBudgetMillis == 0L
                    ? configuredStatementTimeoutSeconds
                    : Math.max(1L, (deadlineBudgetMillis + 999L) / 1_000L);
            int statementTimeoutSeconds = (int) Math.min(configuredStatementTimeoutSeconds, requestedSeconds);
            return new TrustedContext(JSON.writeValueAsString(context), statementTimeoutSeconds);
        } catch (IOException serializationFailure) {
            throw new TransportException(400, "trusted request context cannot be serialized");
        }
    }

    /**
     * Adds gateway-supplied named context values without giving the HTTP layer any schema or
     * policy knowledge. Values are JSON scalars so their number/boolean/string/null representation
     * survives unchanged for the transpiled engine's model-bound predicate. One JSON object avoids
     * relying on HTTP header-name casing, which intermediaries are permitted to normalize.
     */
    private static void appendTrustedContextValues(HttpExchange exchange, ObjectNode target)
            throws TransportException {
        List<String> rawValues = headerValues(exchange, CONTEXT_VALUES_HEADER);
        if (rawValues.isEmpty()) {
            return;
        }
        if (rawValues.size() != 1) {
            throw new TransportException(400, "trusted context values header must occur once");
        }
        try {
            JsonNode values = JSON.readTree(rawValues.get(0));
            if (values == null || values.isObject() == false) {
                throw new TransportException(400, "trusted context values header must contain a JSON object");
            }
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = values.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getKey().matches("[A-Za-z_][A-Za-z0-9_]{0,127}") == false) {
                    throw new TransportException(400, "trusted context value name is invalid");
                }
                if (field.getValue().isValueNode() == false) {
                    throw new TransportException(400,
                            "trusted context value '" + field.getKey() + "' must be a JSON scalar");
                }
                target.set(field.getKey(), field.getValue());
            }
        } catch (IOException invalidJson) {
            throw new TransportException(400, "trusted context values header must contain JSON");
        }
    }

    private static List<String> headerValues(HttpExchange exchange, String name) {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null) {
                values.addAll(entry.getValue());
            }
        }
        return values;
    }

    private static String header(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return value == null ? "" : value.trim();
    }

    private static long nonNegativeLong(String value, String headerName) throws TransportException {
        if (value.isEmpty()) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0L) throw new NumberFormatException(value);
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new TransportException(400, headerName + " must be a non-negative integer");
        }
    }

    private static boolean optionalBoolean(String value, String headerName) throws TransportException {
        if (value.isEmpty() || value.equalsIgnoreCase("false")) {
            return false;
        }
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        throw new TransportException(400, headerName + " must be true or false");
    }

    private static List<String> commaSeparated(String value) {
        if (value.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (trimmed.isEmpty() == false) {
                values.add(trimmed);
            }
        }
        return List.copyOf(values);
    }

    private static void stringArray(ArrayNode target, List<String> values) {
        for (String value : values) {
            target.add(value);
        }
    }

    private static String responseMediaType(String acceptHeader) {
        if (acceptHeader == null || acceptHeader.isBlank()) {
            return GRAPHQL_RESPONSE_JSON;
        }
        for (String range : acceptHeader.split(",")) {
            String mediaType = range.split(";", 2)[0].trim();
            if (mediaType.equalsIgnoreCase(GRAPHQL_RESPONSE_JSON) || mediaType.equalsIgnoreCase("application/json")
                    || mediaType.equalsIgnoreCase("application/*") || mediaType.equals("*/*")) {
                return mediaType.equalsIgnoreCase("application/json") ? "application/json" : GRAPHQL_RESPONSE_JSON;
            }
        }
        return "";
    }

    private static void send(
            HttpExchange exchange,
            int status,
            String mediaType,
            String body,
            String deploymentFingerprint
    ) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", mediaType + "; charset=utf-8");
        exchange.getResponseHeaders().set(EXECUTION_MODE_HEADER, "database");
        if (deploymentFingerprint.isEmpty() == false) {
            exchange.getResponseHeaders().set(DEPLOYMENT_FINGERPRINT_HEADER, deploymentFingerprint);
        }
        exchange.sendResponseHeaders(status, response.length);
        try (java.io.OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private static String transportError(String message) {
        return "{\"errors\":[{\"message\":\"" + escapeJson(message)
                + "\",\"extensions\":{\"code\":\"BAD_REQUEST\"}}]}";
    }

    private static String requiredProperty(Properties properties, String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("database frontend descriptor is missing " + key);
        }
        return value;
    }

    private static String escapeJson(String text) {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '"' || character == '\\') escaped.append('\\').append(character);
            else if (character == '\n') escaped.append("\\n");
            else if (character == '\r') escaped.append("\\r");
            else if (character == '\t') escaped.append("\\t");
            else escaped.append(character);
        }
        return escaped.toString();
    }

    private static final class TransportException extends Exception {
        private final int status;

        private TransportException(int status, String message) {
            super(message);
            this.status = status;
        }

        private int status() {
            return status;
        }
    }
}
