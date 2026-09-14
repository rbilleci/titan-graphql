package io.titan.graphql;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GraphqlHttpResourceQuarkusSmokeTest {

    @Test
    void bootsAndServesPostGraphqlOverHttp() {
        given()
                .contentType(MediaType.APPLICATION_JSON)
                .accept("application/graphql-response+json")
                .body("""
                        {
                          "query": "query Pick($id: ID!, $showTitle: Boolean!) { article(id: $id) { id title @include(if: $showTitle) } }",
                          "operationName": "Pick",
                          "variables": {
                            "id": "1",
                            "showTitle": true
                          },
                          "extensions": {}
                        }
                        """)
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .header("Content-Type", startsWith("application/graphql-response+json"))
                // Test profile explicitly selects the small Java reference kernel.
                .header("X-Titan-Execution-Mode", equalTo("java"))
                .body("data.article.id", equalTo(1))
                .body("data.article.title", equalTo("Titan GraphQL proof"));
    }

    @Test
    void bootsAndServesGetGraphqlWithContextHeaders() {
        given()
                .accept(MediaType.APPLICATION_JSON)
                .queryParam("query", "{ articles(first: 2) { edges { node { id title } } totalCount } }")
                .header("X-Titan-Context-Filters", "publishedVisibility")
                .header("X-Titan-Article-Visibility", "true")
                .when()
                .get("/graphql")
                .then()
                .statusCode(200)
                .header("Content-Type", startsWith(MediaType.APPLICATION_JSON))
                .body("data.articles.totalCount", equalTo(1))
                .body("data.articles.edges[0].node.id", equalTo(1))
                .body("data.articles.edges[0].node.title", equalTo("Titan GraphQL proof"));
    }

    @Test
    void bootsAndServesAdminGraphqlSeparatelyFromApplicationGraphql() {
        given()
                .contentType(MediaType.APPLICATION_JSON)
                .accept("application/graphql-response+json")
                .header("Authorization", "Bearer test-admin-token")
                .header("X-Titan-Management-Introspection", "true")
                .body("""
                        {
                          "query": "{ __type(name: \\"ManagedWorkspace\\") { name fields { name } } }"
                        }
                        """)
                .when()
                .post("/admin/graphql")
                .then()
                .statusCode(200)
                .header("Content-Type", startsWith("application/graphql-response+json"))
                .body("data.__type.name", equalTo("ManagedWorkspace"))
                .body("data.__type.fields[0].name", equalTo("id"));
    }
}
