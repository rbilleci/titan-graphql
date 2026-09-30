package io.titan.graphql;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.titan.graphql.codegen.PreviewCandidateHttpServingResource;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("docker")
@Tag("database-engine-preview-candidate")
@QuarkusTest
@QuarkusTestResource(value = PreviewCandidateHttpServingResource.class, restrictToAnnotatedClass = true)
final class PreviewCandidateGraphqlHttpIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OPERATION = "query Probe { __typename }";
    private static final String RESTRICTED_OPERATION = "query PrivateProbe { __typename }";

    @Test
    void publishedCandidateServesAtPublicPreviewUrl() throws Exception {
        Response response = given()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(GraphqlHttpResource.GRAPHQL_RESPONSE_JSON)
                .body(Map.of("query", OPERATION))
                .post("/preview/preview-http-candidate/graphql");
        assertEquals(200, response.statusCode(), response::asString);
        JsonNode body = JSON.readTree(response.asString());
        assertFalse(body.has("errors"), body::toString);
        assertEquals("Query", body.at("/data/__typename").asText(), body::toString);

        Response get = given()
                .accept(GraphqlHttpResource.GRAPHQL_RESPONSE_JSON)
                .queryParam("query", OPERATION)
                .get("/preview/preview-http-candidate/graphql");
        assertEquals(200, get.statusCode(), get::asString);
        JsonNode getBody = JSON.readTree(get.asString());
        assertFalse(getBody.has("errors"), getBody::toString);
        assertEquals("Query", getBody.at("/data/__typename").asText(), getBody::toString);

        Response spoofedRole = given()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(GraphqlHttpResource.GRAPHQL_RESPONSE_JSON)
                .header("X-Titan-Actor-Role", "operator")
                .body(Map.of("query", RESTRICTED_OPERATION))
                .post("/preview/preview-http-candidate/graphql");
        assertEquals(200, spoofedRole.statusCode(), spoofedRole::asString);
        assertEquals("OPERATION_REGISTRY_REJECTED",
                JSON.readTree(spoofedRole.asString()).at("/errors/0/extensions/code").asText(),
                spoofedRole::asString);

        Response unknown = given()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(GraphqlHttpResource.GRAPHQL_RESPONSE_JSON)
                .body(Map.of("query", OPERATION))
                .post("/preview/unknown-candidate/graphql");
        assertEquals(404, unknown.statusCode(), unknown::asString);
    }
}
