package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphqlPreviewDatabaseRuntimeTest {

    @Test
    void configuredPreviewRouteDoesNotFallBackToJvmRuntime(@TempDir Path directory) throws Exception {
        Path descriptor = directory.resolve("candidate.properties");
        Files.writeString(descriptor, "schema-version=titan.graphql.database-frontend-deployment.v1\n"
                + "database-dialect=postgresql\n"
                + "entry-point-schema=management_graphql\n"
                + "entry-point-routine=execute_graphql_request\n"
                + "model-semantic-sha256=" + "0".repeat(64) + "\n"
                + "runtime-identity-sha256=" + "1".repeat(64) + "\n"
                + "package-identity-sha256=" + "2".repeat(64) + "\n"
                + "preview-build-id=candidate\n"
                + "preview-expires-at=" + Instant.now().plusSeconds(3600) + "\n"
                + "operation-registry-id=registry-candidate\n"
                + "preview-deployment-sha256=" + "3".repeat(64) + "\n");
        Path registry = directory.resolve("preview-packages.properties");
        Files.writeString(registry, "candidate=candidate.properties\n");
        DataSource unavailable = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getConnection")) {
                        throw new SQLException("database unavailable");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        GraphqlPreviewHttpResource resource = new GraphqlPreviewHttpResource(
                registry.toString(), () -> unavailable, false);

        GraphqlPreviewHttpResource.GraphqlPreviewHttpResult missing = resource.negotiatePost(
                "unknown", Map.of("query", "{ __typename }"), GraphqlHttpResource.GRAPHQL_RESPONSE_JSON,
                GraphqlHttpResource.GraphqlHttpContext.defaults());
        assertEquals(404, missing.status());
        assertTrue(missing.body().contains("not registered"), missing::body);

        GraphqlPreviewHttpResource.GraphqlPreviewHttpResult failed = resource.negotiatePost(
                "candidate", Map.of("query", "{ __typename }"), GraphqlHttpResource.GRAPHQL_RESPONSE_JSON,
                GraphqlHttpResource.GraphqlHttpContext.defaults());
        assertEquals(503, failed.status());
        assertTrue(failed.body().contains("EXECUTION_MODE_UNAVAILABLE"), failed::body);
    }

    @Test
    void descriptorRegistryRejectsDuplicatePreviewIds(@TempDir Path directory) throws Exception {
        Path registry = directory.resolve("preview-packages.properties");
        Files.writeString(registry, "candidate=first.properties\ncandidate=second.properties\n");

        assertThrows(IllegalArgumentException.class,
                () -> new GraphqlPreviewHttpResource(registry.toString(), () -> null, false));
    }

    @Test
    void previewDescriptorRequiresBuildIdAndExpiration(@TempDir Path directory) throws Exception {
        Path descriptor = directory.resolve("candidate.properties");
        String base = "schema-version=titan.graphql.database-frontend-deployment.v1\n"
                + "database-dialect=postgresql\n"
                + "entry-point-schema=public\n"
                + "entry-point-routine=execute_graphql_request\n"
                + "model-semantic-sha256=" + "0".repeat(64) + "\n"
                + "runtime-identity-sha256=" + "1".repeat(64) + "\n"
                + "package-identity-sha256=" + "2".repeat(64) + "\n";
        Path registry = directory.resolve("preview-packages.properties");
        Files.writeString(registry, "candidate=candidate.properties\n");

        Files.writeString(descriptor, base + "preview-expires-at=" + Instant.now().plusSeconds(3600) + "\n");
        assertThrows(IllegalArgumentException.class,
                () -> new GraphqlPreviewHttpResource(registry.toString(), () -> null, false));

        Files.writeString(descriptor, base + "preview-build-id=candidate\n");
        assertThrows(IllegalArgumentException.class,
                () -> new GraphqlPreviewHttpResource(registry.toString(), () -> null, false));

        Files.writeString(descriptor, base + "preview-build-id=candidate\npreview-expires-at="
                + Instant.now().plusSeconds(3600) + "\n");
        assertThrows(IllegalArgumentException.class,
                () -> new GraphqlPreviewHttpResource(registry.toString(), () -> null, false));
    }

    @Test
    void previewExpiryAndRegistryReplacementTakeEffectWithoutRestart(@TempDir Path directory) throws Exception {
        Path descriptor = directory.resolve("candidate.properties");
        String base = "schema-version=titan.graphql.database-frontend-deployment.v1\n"
                + "database-dialect=postgresql\n"
                + "entry-point-schema=management_graphql\n"
                + "entry-point-routine=execute_graphql_request\n"
                + "model-semantic-sha256=" + "0".repeat(64) + "\n"
                + "runtime-identity-sha256=" + "1".repeat(64) + "\n"
                + "package-identity-sha256=" + "2".repeat(64) + "\n"
                + "preview-build-id=candidate\n"
                + "operation-registry-id=registry-candidate\n"
                + "preview-deployment-sha256=" + "3".repeat(64) + "\n";
        Files.writeString(descriptor, base + "preview-expires-at="
                + Instant.now().minusSeconds(60) + "\n");
        Path registry = directory.resolve("preview-packages.properties");
        Files.writeString(registry, "candidate=candidate.properties\n");
        GraphqlPreviewHttpResource resource = new GraphqlPreviewHttpResource(
                registry.toString(), () -> {
                    throw new IllegalStateException("database should not open for an expired preview");
                }, false);

        assertEquals(410, resource.negotiatePost("candidate", Map.of("query", "{ __typename }"),
                GraphqlHttpResource.GRAPHQL_RESPONSE_JSON,
                GraphqlHttpResource.GraphqlHttpContext.defaults()).status());

        Path replacement = directory.resolve("replacement.properties");
        Files.writeString(replacement, "");
        Files.move(replacement, registry, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(404, resource.negotiatePost("candidate", Map.of("query", "{ __typename }"),
                GraphqlHttpResource.GRAPHQL_RESPONSE_JSON,
                GraphqlHttpResource.GraphqlHttpContext.defaults()).status());

        Files.writeString(descriptor, base + "preview-expires-at="
                + Instant.now().plusSeconds(3600) + "\n");
        Files.writeString(replacement, "candidate=candidate.properties\n");
        Files.move(replacement, registry, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(503, resource.negotiatePost("candidate", Map.of("query", "{ __typename }"),
                GraphqlHttpResource.GRAPHQL_RESPONSE_JSON,
                GraphqlHttpResource.GraphqlHttpContext.defaults()).status());

        Files.writeString(descriptor, base + "preview-expires-at=not-an-instant\n");
        assertEquals(503, resource.negotiatePost("candidate", Map.of("query", "{ __typename }"),
                GraphqlHttpResource.GRAPHQL_RESPONSE_JSON,
                GraphqlHttpResource.GraphqlHttpContext.defaults()).status());
        Files.writeString(descriptor, base.replace("preview-build-id=candidate", "preview-build-id=other")
                + "preview-expires-at=" + Instant.now().plusSeconds(3600) + "\n");
        assertEquals(503, resource.negotiatePost("candidate", Map.of("query", "{ __typename }"),
                GraphqlHttpResource.GRAPHQL_RESPONSE_JSON,
                GraphqlHttpResource.GraphqlHttpContext.defaults()).status());
    }
}
