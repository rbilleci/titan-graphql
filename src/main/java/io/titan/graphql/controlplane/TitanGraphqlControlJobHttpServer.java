package io.titan.graphql.controlplane;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;

public final class TitanGraphqlControlJobHttpServer implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final int MAX_BODY_BYTES = 65_536;
    private static final String PATH = "/artifact-jobs";

    private final HttpServer server;
    private final ExecutorService executor;

    private TitanGraphqlControlJobHttpServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static TitanGraphqlControlJobHttpServer start(
            InetSocketAddress address,
            DataSource dataSource,
            TitanGraphqlControlJobQueue.Dialect dialect,
            String bearerToken
    ) throws IOException {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(dialect, "dialect");
        if (address.getAddress() == null || !address.getAddress().isLoopbackAddress()) {
            throw new IllegalArgumentException("control-plane API must bind to a loopback address");
        }
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalArgumentException("control-plane API bearer token is required");
        }
        TitanGraphqlControlJobQueue queue = new TitanGraphqlControlJobQueue(dataSource, dialect, Clock.systemUTC());
        HttpServer server = HttpServer.create(address, 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext(PATH, exchange -> handle(exchange, dataSource, queue, bearerToken));
        server.start();
        return new TitanGraphqlControlJobHttpServer(server, executor);
    }

    public URI endpointUri() {
        String host = server.getAddress().getAddress().getHostAddress();
        return URI.create("http://" + (host.contains(":") ? "[" + host + "]" : host)
                + ":" + server.getAddress().getPort() + PATH);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static void handle(
            HttpExchange exchange,
            DataSource dataSource,
            TitanGraphqlControlJobQueue queue,
            String bearerToken
    ) throws IOException {
        try {
            if (!authorized(exchange, bearerToken)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                send(exchange, 401, error("UNAUTHORIZED", "a valid bearer token is required"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            if (PATH.equals(path)) {
                if (!"POST".equals(method)) {
                    exchange.getResponseHeaders().set("Allow", "POST");
                    send(exchange, 405, error("METHOD_NOT_ALLOWED", "POST is required"));
                    return;
                }
                request(exchange, dataSource, queue);
                return;
            }
            if (path.startsWith(PATH + "/")) {
                if (!"GET".equals(method)) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    send(exchange, 405, error("METHOD_NOT_ALLOWED", "GET is required"));
                    return;
                }
                status(exchange, queue, path.substring(PATH.length() + 1));
                return;
            }
            send(exchange, 404, error("NOT_FOUND", "artifact job route was not found"));
        } catch (SQLException unavailable) {
            send(exchange, 503, error("DATABASE_UNAVAILABLE", "control-plane database is unavailable"));
        } catch (RuntimeException failure) {
            send(exchange, 500, error("INTERNAL_ERROR", "control-plane request failed"));
        } finally {
            exchange.close();
        }
    }

    private static void request(
            HttpExchange exchange,
            DataSource dataSource,
            TitanGraphqlControlJobQueue queue
    ) throws IOException, SQLException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !(contentType.equalsIgnoreCase("application/json")
                || contentType.toLowerCase(Locale.ROOT).startsWith("application/json;"))) {
            send(exchange, 415, error("UNSUPPORTED_MEDIA_TYPE", "application/json is required"));
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            send(exchange, 413, error("PAYLOAD_TOO_LARGE", "artifact job request exceeds the body limit"));
            return;
        }
        JsonNode input;
        try {
            input = JSON.readTree(body);
        } catch (IOException invalid) {
            send(exchange, 400, error("INVALID_REQUEST", "artifact job request must be valid JSON"));
            return;
        }
        if (input == null || !input.isObject() || input.size() != 4
                || !text(input, "requestKey") || !text(input, "draftId")
                || !text(input, "generationProfile") || !input.path("enableIntrospection").isBoolean()) {
            send(exchange, 400, error("INVALID_REQUEST", "artifact job request fields are invalid"));
            return;
        }
        String requestKey = input.path("requestKey").asText();
        String draftId = input.path("draftId").asText();
        String profile = input.path("generationProfile").asText();
        if (requestKey.isBlank() || requestKey.length() > 128 || draftId.isBlank() || draftId.length() > 191
                || profile.isBlank() || profile.length() > 128) {
            send(exchange, 400, error("INVALID_REQUEST", "artifact job request fields exceed their bounds"));
            return;
        }
        TitanGraphqlControlJobQueue.JobReference reference;
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getAutoCommit()) {
                throw new SQLException("control-plane API requires an auto-commit datasource");
            }
            connection.setAutoCommit(false);
            try {
                reference = TitanGraphqlArtifactGenerationJobRunner.request(queue, connection,
                        requestKey, draftId, profile, input.path("enableIntrospection").asBoolean());
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                if (failure instanceof IllegalArgumentException) {
                    send(exchange, 409, error("REQUEST_KEY_CONFLICT", "request key is bound to another payload"));
                    return;
                }
                throw failure;
            }
        }
        ObjectNode response = JSON.createObjectNode();
        response.put("id", reference.id());
        response.put("status", reference.status().name());
        response.put("created", reference.created());
        exchange.getResponseHeaders().set("Location", PATH + "/" + reference.id());
        send(exchange, reference.created() ? 202 : 200, response);
    }

    private static void status(HttpExchange exchange, TitanGraphqlControlJobQueue queue, String id)
            throws IOException, SQLException {
        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException invalid) {
            send(exchange, 400, error("INVALID_JOB_ID", "artifact job id must be a UUID"));
            return;
        }
        var state = queue.find(id);
        if (state.isEmpty() || !TitanGraphqlArtifactGenerationJobRunner.JOB_TYPE.equals(state.orElseThrow().type())) {
            send(exchange, 404, error("NOT_FOUND", "artifact job was not found"));
            return;
        }
        var job = state.orElseThrow();
        ObjectNode response = JSON.createObjectNode();
        response.put("id", job.id());
        response.put("status", job.status().name());
        response.put("attempt", job.attempt());
        if (job.resultJson() == null) {
            response.putNull("result");
        } else {
            try {
                response.set("result", JSON.readTree(job.resultJson()));
            } catch (IOException invalidResult) {
                send(exchange, 503, error("INVALID_JOB_RESULT", "stored artifact job result is invalid"));
                return;
            }
        }
        if (job.failureCode() == null) {
            response.putNull("failureCode");
        } else {
            response.put("failureCode", job.failureCode());
        }
        send(exchange, 200, response);
    }

    private static boolean text(JsonNode input, String field) {
        return input.path(field).isTextual();
    }

    private static boolean authorized(HttpExchange exchange, String token) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8));
    }

    private static ObjectNode error(String code, String message) {
        ObjectNode response = JSON.createObjectNode();
        response.put("code", code);
        response.put("message", message);
        return response;
    }

    private static void send(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
