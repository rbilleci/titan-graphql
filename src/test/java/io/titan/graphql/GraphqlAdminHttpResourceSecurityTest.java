package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.core.Response;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphqlAdminHttpResourceSecurityTest {

    @Test
    void transportEndpointIsHiddenUntilAServerSideTokenIsConfigured() {
        Response response = new GraphqlAdminHttpResource().postResponse(
                Map.of("query", "{ __typename }"),
                GraphqlHttpResource.GRAPHQL_RESPONSE_JSON,
                null,
                null,
                null,
                null
        );

        assertEquals(404, response.getStatus());
    }

    @Test
    void transportEndpointRejectsMissingOrIncorrectBearerToken() {
        GraphqlAdminHttpResource resource = new GraphqlAdminHttpResource("test-token", "operator", "test-admin");

        Response missing = resource.postResponse(
                Map.of("query", "{ __typename }"),
                GraphqlHttpResource.GRAPHQL_RESPONSE_JSON,
                null,
                null,
                null,
                null
        );
        Response incorrect = resource.postResponse(
                Map.of("query", "{ __typename }"),
                GraphqlHttpResource.GRAPHQL_RESPONSE_JSON,
                "Bearer incorrect",
                null,
                null,
                null
        );

        assertEquals(401, missing.getStatus());
        assertEquals(401, incorrect.getStatus());
    }

    @Test
    void authenticatedHttpRouteRequiresDatabaseDescriptor() {
        GraphqlAdminHttpResource resource = new GraphqlAdminHttpResource("test-token", "operator", "test-admin");
        Response post = resource.postResponse(
                Map.of("query", "{ __typename }"), GraphqlHttpResource.GRAPHQL_RESPONSE_JSON,
                "Bearer test-token", null, null, null);
        Response get = resource.getResponse(
                "{ __typename }", "", "", "", GraphqlHttpResource.GRAPHQL_RESPONSE_JSON,
                "Bearer test-token", null, null, null);

        assertEquals(503, post.getStatus());
        assertEquals(503, get.getStatus());
        assertTrue(((String) post.getEntity()).contains("EXECUTION_MODE_UNAVAILABLE"));
        assertTrue(((String) get.getEntity()).contains("EXECUTION_MODE_UNAVAILABLE"));
    }

    @Test
    void configuredDatabaseAdminRouteFailsClosedWithoutJvmFallback(@TempDir Path directory) throws Exception {
        Path descriptor = directory.resolve("management.properties");
        Files.writeString(descriptor, "schema-version=titan.graphql.database-frontend-deployment.v1\n"
                + "database-dialect=postgresql\n"
                + "entry-point-schema=management_graphql\n"
                + "entry-point-routine=execute_graphql_request\n"
                + "model-semantic-sha256=" + "0".repeat(64) + "\n"
                + "runtime-identity-sha256=" + "1".repeat(64) + "\n"
                + "package-identity-sha256=" + "2".repeat(64) + "\n");
        DataSource unavailable = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getConnection")) {
                        throw new SQLException("database unavailable");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        GraphqlAdminHttpResource resource = new GraphqlAdminHttpResource(
                "test-token", "operator", "server-admin", descriptor.toString(), () -> unavailable);
        Response response = resource.postResponse(
                Map.of("query", "{ __typename }"), GraphqlHttpResource.GRAPHQL_RESPONSE_JSON,
                "Bearer test-token", null, null, null);

        assertEquals(503, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("EXECUTION_MODE_UNAVAILABLE"));
        assertEquals(401, resource.postResponse(
                Map.of("query", "{ __typename }"), GraphqlHttpResource.GRAPHQL_RESPONSE_JSON,
                null, null, null, null).getStatus());
    }

    @Test
    void configuredDatabaseAdminRouteRejectsUnsupportedDescriptorVersion(@TempDir Path directory) throws Exception {
        Path descriptor = directory.resolve("management.properties");
        Files.writeString(descriptor, "schema-version=unknown\n");

        assertThrows(IllegalArgumentException.class, () -> new GraphqlAdminHttpResource(
                "test-token", "operator", "server-admin", descriptor.toString(), () -> null));
    }
}
