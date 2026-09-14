package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
}
