package io.titan.graphql.frontend;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.sql.DriverManager;

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
        DatabaseGraphqlHttpServer.Configuration configuration =
                DatabaseGraphqlHttpServer.fromDeploymentDescriptor(
                        Path.of(descriptor),
                        () -> DriverManager.getConnection(jdbcUrl, jdbcUsername, jdbcPassword),
                        trustRequestContextHeaders,
                        maxBodyBytes,
                        statementTimeoutSeconds);
        DatabaseGraphqlHttpServer server = DatabaseGraphqlHttpServer.start(
                new InetSocketAddress("0.0.0.0", port), configuration);
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
