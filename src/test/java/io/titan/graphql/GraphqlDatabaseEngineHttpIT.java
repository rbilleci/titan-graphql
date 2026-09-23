package io.titan.graphql;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.titan.graphql.artifact.TitanGraphqlArtifactsDirectory;
import io.titan.graphql.artifact.TitanGraphqlPackageBinding;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * End-to-end cutover proof. Quarkus receives HTTP, forwards one untouched request envelope to
 * the generated database engine, and returns the engine's completed GraphQL response. The
 * resource deploys no legacy SQL kernel, so this cannot pass through a historical routine.
 */
@Tag("docker")
@Tag("database-engine-http")
@QuarkusTest
@QuarkusTestResource(value = GraphqlDatabaseEngineServingResource.class, restrictToAnnotatedClass = true)
class GraphqlDatabaseEngineHttpIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void databaseModeServesAParameterizedOperationOverTheRealHttpTransport() throws Exception {
        String expectedFingerprint = TitanGraphqlPackageBinding
                .read(TitanGraphqlArtifactsDirectory.configuredDirectory())
                .deploymentFingerprint();

        Response response = given()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(GraphqlHttpResource.GRAPHQL_RESPONSE_JSON)
                .body(Map.of(
                        "query",
                        "query FindArticle($articleId: Int!) { selected: article(id: $articleId) { id title } }",
                        "operationName", "FindArticle",
                        "variables", Map.of("articleId", 1)))
                .post("/graphql");

        assertEquals(200, response.statusCode());
        assertEquals("database", response.header(GraphqlHttpResource.EXECUTION_MODE_HEADER));
        assertEquals(expectedFingerprint, response.header(GraphqlHttpResource.DEPLOYMENT_FINGERPRINT_HEADER));
        JsonNode body = JSON.readTree(response.asString());
        assertEquals(1, body.at("/data/selected/id").asInt(), body::toString);
        assertEquals("Titan GraphQL proof", body.at("/data/selected/title").asText(), body::toString);
    }

    @Test
    void databaseModeForwardsGetWithoutParsingItInTheHttpLayer() throws Exception {
        Response response = given()
                .accept(GraphqlHttpResource.GRAPHQL_RESPONSE_JSON)
                .queryParam("query", "mutation { unsupportedMutation { id } }")
                .get("/graphql");

        assertEquals(200, response.statusCode());
        assertEquals("database", response.header(GraphqlHttpResource.EXECUTION_MODE_HEADER));
        JsonNode body = JSON.readTree(response.asString());
        assertTrue(body.has("errors"), body::toString);
        assertTrue(body.at("/errors/0/message").asText().contains("not allowed"), body::toString);
    }
}
