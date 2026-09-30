package io.titan.graphql.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseGraphqlHttpServerPreviewTest {

    private static final String HASH = "0".repeat(64);
    private static final DatabaseWholeRequestClient.EntryPoint ENTRY_POINT =
            new DatabaseWholeRequestClient.EntryPoint("public", "execute_graphql_request");

    @Test
    void expiredPreviewReturnsGoneBeforeOpeningDatabase() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        DatabaseGraphqlHttpServer.Configuration application = new DatabaseGraphqlHttpServer.Configuration(
                DatabaseWholeRequestClient.Dialect.POSTGRESQL,
                () -> {
                    connections.incrementAndGet();
                    throw new java.sql.SQLException("database should not open");
                }, ENTRY_POINT, HASH, HASH, HASH, false);
        DatabaseGraphqlHttpServer.Configuration preview = new DatabaseGraphqlHttpServer.Configuration(
                DatabaseWholeRequestClient.Dialect.POSTGRESQL,
                application.connectionProvider(), ENTRY_POINT, HASH, HASH, HASH, HASH,
                "registry-expired", "expired", Instant.now().minusSeconds(60), false, 1024, 30, HASH);
        try (DatabaseGraphqlHttpServer server = DatabaseGraphqlHttpServer.start(
                new InetSocketAddress("127.0.0.1", 0), application, null, Map.of("expired", preview))) {
            URI endpoint = URI.create(server.endpointUri().toString().replace("/graphql",
                    "/preview/expired/graphql?query=%7B__typename%7D"));
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(endpoint).GET().build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(410, response.statusCode(), response.body());
            assertTrue(response.body().contains("preview build has expired"), response.body());
            assertEquals(0, connections.get());
        }
    }

    @Test
    void previewRouteRejectsDescriptorForAnotherBuild() {
        DatabaseGraphqlHttpServer.Configuration application = new DatabaseGraphqlHttpServer.Configuration(
                DatabaseWholeRequestClient.Dialect.POSTGRESQL,
                () -> {
                    throw new java.sql.SQLException("database should not open");
                }, ENTRY_POINT, HASH, HASH, HASH, false);
        DatabaseGraphqlHttpServer.Configuration preview = new DatabaseGraphqlHttpServer.Configuration(
                DatabaseWholeRequestClient.Dialect.POSTGRESQL,
                application.connectionProvider(), ENTRY_POINT, HASH, HASH, HASH, HASH, "", "another",
                Instant.now().plusSeconds(3600), false, 1024, 30);

        assertThrows(IllegalArgumentException.class, () -> DatabaseGraphqlHttpServer.start(
                new InetSocketAddress("127.0.0.1", 0), application, null, Map.of("candidate", preview)));
    }

    @Test
    void previewRouteRequiresBoundBuildIdAndExpiration() {
        DatabaseGraphqlHttpServer.Configuration application = new DatabaseGraphqlHttpServer.Configuration(
                DatabaseWholeRequestClient.Dialect.POSTGRESQL,
                () -> {
                    throw new java.sql.SQLException("database should not open");
                }, ENTRY_POINT, HASH, HASH, HASH, false);
        DatabaseGraphqlHttpServer.Configuration missingId = new DatabaseGraphqlHttpServer.Configuration(
                DatabaseWholeRequestClient.Dialect.POSTGRESQL,
                application.connectionProvider(), ENTRY_POINT, HASH, HASH, HASH, HASH, "", "",
                Instant.now().plusSeconds(3600), false, 1024, 30);
        assertThrows(IllegalArgumentException.class, () -> DatabaseGraphqlHttpServer.start(
                new InetSocketAddress("127.0.0.1", 0), application, null, Map.of("candidate", missingId)));

        DatabaseGraphqlHttpServer.Configuration missingExpiration = new DatabaseGraphqlHttpServer.Configuration(
                DatabaseWholeRequestClient.Dialect.POSTGRESQL,
                application.connectionProvider(), ENTRY_POINT, HASH, HASH, HASH, HASH, "", "candidate",
                null, false, 1024, 30);
        assertThrows(IllegalArgumentException.class, () -> DatabaseGraphqlHttpServer.start(
                new InetSocketAddress("127.0.0.1", 0), application, null,
                Map.of("candidate", missingExpiration)));

        DatabaseGraphqlHttpServer.Configuration missingSeal = new DatabaseGraphqlHttpServer.Configuration(
                DatabaseWholeRequestClient.Dialect.POSTGRESQL,
                application.connectionProvider(), ENTRY_POINT, HASH, HASH, HASH, HASH,
                "registry-candidate", "candidate", Instant.now().plusSeconds(3600), false, 1024, 30);
        assertThrows(IllegalArgumentException.class, () -> DatabaseGraphqlHttpServer.start(
                new InetSocketAddress("127.0.0.1", 0), application, null, Map.of("candidate", missingSeal)));
    }

    @Test
    void sealedPreviewRejectsChangedInstalledPackageBeforeRoutine() throws Exception {
        AtomicInteger packageLookups = new AtomicInteger();
        AtomicBoolean rowRead = new AtomicBoolean();
        ResultSet rows = (ResultSet) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "next" -> !rowRead.getAndSet(true);
                    case "getString" -> "1".repeat(64);
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        PreparedStatement lookup = (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "setQueryTimeout", "setString", "close" -> null;
                    case "executeQuery" -> rows;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Connection connection = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getAutoCommit" -> true;
                    case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                    case "setTransactionIsolation", "setAutoCommit", "rollback", "close" -> null;
                    case "prepareStatement" -> {
                        assertTrue(((String) arguments[0]).contains("titan_graphql_package_identity"));
                        packageLookups.incrementAndGet();
                        yield lookup;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        DatabaseGraphqlHttpServer.Configuration application = new DatabaseGraphqlHttpServer.Configuration(
                DatabaseWholeRequestClient.Dialect.POSTGRESQL, () -> connection,
                ENTRY_POINT, HASH, HASH, HASH, false);
        DatabaseGraphqlHttpServer.Configuration preview = new DatabaseGraphqlHttpServer.Configuration(
                DatabaseWholeRequestClient.Dialect.POSTGRESQL, () -> connection,
                ENTRY_POINT, HASH, HASH, HASH, HASH, "registry-preview", "candidate",
                Instant.now().plusSeconds(3600), false, 1024, 30, HASH);

        try (DatabaseGraphqlHttpServer server = DatabaseGraphqlHttpServer.start(
                new InetSocketAddress("127.0.0.1", 0), application, null, Map.of("candidate", preview))) {
            URI endpoint = URI.create(server.endpointUri().toString().replace("/graphql",
                    "/preview/candidate/graphql?query=%7B__typename%7D"));
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(endpoint).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, response.statusCode(), response.body());
            assertEquals(1, packageLookups.get());
        }
    }

    @Test
    void descriptorRejectsMalformedExpiration(@TempDir Path directory) throws Exception {
        Path descriptor = directory.resolve("preview.properties");
        Files.writeString(descriptor, "schema-version=titan.graphql.database-frontend-deployment.v1\n"
                + "database-dialect=postgresql\n"
                + "entry-point-schema=public\n"
                + "entry-point-routine=execute_graphql_request\n"
                + "model-semantic-sha256=" + HASH + "\n"
                + "runtime-identity-sha256=" + HASH + "\n"
                + "package-identity-sha256=" + HASH + "\n"
                + "deployment-fingerprint=" + HASH + "\n"
                + "preview-expires-at=not-an-instant\n");

        assertThrows(IllegalArgumentException.class, () -> DatabaseGraphqlHttpServer.fromDeploymentDescriptor(
                descriptor, () -> null, false, 1024, 30));
    }
}
