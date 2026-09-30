package io.titan.graphql.frontend;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Process entry point for the standalone database GraphQL HTTP frontend.
 *
 * <p>Configuration intentionally comes only from deployment environment variables and the
 * package-generated descriptor; it has no route to a JVM GraphQL runtime or a caller-selected
 * database routine.</p>
 */
public final class DatabaseGraphqlHttpServerMain {

    private DatabaseGraphqlHttpServerMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            throw new IllegalArgumentException("the database HTTP frontend accepts configuration only from environment");
        }
        String descriptor = requiredEnvironment("TITAN_GRAPHQL_FRONTEND_DESCRIPTOR");
        String jdbcUrl = requiredEnvironment("TITAN_GRAPHQL_JDBC_URL");
        String jdbcUsername = requiredEnvironment("TITAN_GRAPHQL_JDBC_USERNAME");
        String jdbcPassword = requiredEnvironment("TITAN_GRAPHQL_JDBC_PASSWORD");
        int port = positiveInt(environment("TITAN_GRAPHQL_HTTP_PORT", "8080"), "TITAN_GRAPHQL_HTTP_PORT");
        int maxBodyBytes = positiveInt(
                environment("TITAN_GRAPHQL_HTTP_MAX_BODY_BYTES", "1048576"),
                "TITAN_GRAPHQL_HTTP_MAX_BODY_BYTES");
        int statementTimeoutSeconds = positiveInt(
                environment("TITAN_GRAPHQL_DATABASE_STATEMENT_TIMEOUT_SECONDS", "30"),
                "TITAN_GRAPHQL_DATABASE_STATEMENT_TIMEOUT_SECONDS");
        boolean trustRequestContextHeaders = strictBoolean(
                environment("TITAN_GRAPHQL_HTTP_TRUST_REQUEST_CONTEXT_HEADERS", "false"),
                "TITAN_GRAPHQL_HTTP_TRUST_REQUEST_CONTEXT_HEADERS");
        DatabaseWholeRequestClient.ConnectionProvider connectionProvider =
                () -> DriverManager.getConnection(jdbcUrl, jdbcUsername, jdbcPassword);
        DatabaseGraphqlHttpServer.Configuration configuration =
                DatabaseGraphqlHttpServer.fromDeploymentDescriptor(
                        Path.of(descriptor),
                        connectionProvider,
                        trustRequestContextHeaders,
                        maxBodyBytes,
                        statementTimeoutSeconds);
        String adminDescriptor = environment("TITAN_GRAPHQL_ADMIN_FRONTEND_DESCRIPTOR", "");
        DatabaseGraphqlHttpServer.AdminConfiguration admin = null;
        if (!adminDescriptor.isEmpty()) {
            DatabaseGraphqlHttpServer.Configuration adminDatabase =
                    DatabaseGraphqlHttpServer.fromDeploymentDescriptor(
                            Path.of(adminDescriptor), connectionProvider, false,
                            maxBodyBytes, statementTimeoutSeconds);
            admin = new DatabaseGraphqlHttpServer.AdminConfiguration(
                    adminDatabase,
                    requiredEnvironment("TITAN_GRAPHQL_ADMIN_ACCESS_TOKEN"),
                    environment("TITAN_GRAPHQL_ADMIN_ROLE", "operator"),
                    environment("TITAN_GRAPHQL_ADMIN_ACTOR_KEY", "titan-admin"));
        }
        Map<String, DatabaseGraphqlHttpServer.Configuration> previews = new LinkedHashMap<>();
        String previewRegistry = environment("TITAN_GRAPHQL_PREVIEW_FRONTEND_REGISTRY", "");
        if (!previewRegistry.isEmpty()) {
            Properties entries = new Properties() {
                @Override
                public synchronized Object put(Object key, Object value) {
                    if (containsKey(key)) {
                        throw new IllegalArgumentException("duplicate preview build ID in registry: " + key);
                    }
                    return super.put(key, value);
                }
            };
            try (java.io.Reader reader = Files.newBufferedReader(Path.of(previewRegistry), StandardCharsets.UTF_8)) {
                entries.load(reader);
            }
            for (String previewBuildId : entries.stringPropertyNames()) {
                previews.put(previewBuildId, DatabaseGraphqlHttpServer.fromDeploymentDescriptor(
                        Path.of(entries.getProperty(previewBuildId)), connectionProvider,
                        trustRequestContextHeaders, maxBodyBytes, statementTimeoutSeconds));
            }
        }
        DatabaseGraphqlHttpServer server = DatabaseGraphqlHttpServer.start(
                new InetSocketAddress("0.0.0.0", port), configuration, admin, previews);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "database-graphql-http-shutdown"));
        System.out.println("database GraphQL HTTP frontend listening on port " + port);
        Thread.currentThread().join();
    }

    private static String requiredEnvironment(String name) {
        String value = environment(name, "");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must be configured");
        }
        return value;
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value.trim();
    }

    private static int positiveInt(String value, String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new NumberFormatException(value);
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be a positive integer");
        }
    }

    private static boolean strictBoolean(String value, String name) {
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException(name + " must be true or false");
    }
}
