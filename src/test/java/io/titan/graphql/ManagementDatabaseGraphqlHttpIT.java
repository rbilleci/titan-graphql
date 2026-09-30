package io.titan.graphql;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.titan.graphql.codegen.ManagementDatabaseHttpServingResource;
import io.titan.graphql.controlplane.TitanGraphqlControlJobQueue;
import io.titan.graphql.controlplane.TitanGraphqlModelValidationJobRunner;
import io.titan.graphql.controlplane.TitanGraphqlModelValidationService;
import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import io.titan.management.JdbcTransactionalMutationStore;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.core.MediaType;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("docker")
@Tag("database-engine-management")
@QuarkusTest
@QuarkusTestResource(value = ManagementDatabaseHttpServingResource.class, restrictToAnnotatedClass = true)
final class ManagementDatabaseGraphqlHttpIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JOB_ID = "00000000-0000-0000-0000-000000000071";

    @Test
    void publicAdminUrlQueuesAndPollsDatabaseControlJob() throws Exception {
        Response unauthenticated = given()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", "{ modelDraft(id: \"http-draft\") { id } }"))
                .post("/admin/graphql");
        assertEquals(401, unauthenticated.statusCode());

        JsonNode draft = post("{ modelDraft(id: \"http-draft\") { id workspaceId status } }");
        assertEquals("http-draft", draft.at("/data/modelDraft/id").asText(), draft::toString);
        assertEquals("http-workspace", draft.at("/data/modelDraft/workspaceId").asText(), draft::toString);
        assertEquals("imported", draft.at("/data/modelDraft/status").asText(), draft::toString);

        JsonNode requested = post("mutation { requestModelValidation(id: \"" + JOB_ID
                + "\", draftId: \"http-draft\") { id draftId } }");
        assertEquals(JOB_ID, requested.at("/data/requestModelValidation/id").asText(), requested::toString);
        assertEquals("http-draft", requested.at("/data/requestModelValidation/draftId").asText(),
                requested::toString);

        JsonNode pending = post("{ controlJob(id: \"" + JOB_ID + "\") { id type status attemptCount } }");
        assertEquals(JOB_ID, pending.at("/data/controlJob/id").asText(), pending::toString);
        assertEquals("model.validate", pending.at("/data/controlJob/type").asText(), pending::toString);
        assertEquals("pending", pending.at("/data/controlJob/status").asText(), pending::toString);
        assertEquals(0, pending.at("/data/controlJob/attemptCount").asInt(), pending::toString);

        DataSource dataSource = CDI.current().select(DataSource.class).get();
        TitanGraphqlDurableManagementStore store = new TitanGraphqlDurableManagementStore(
                new JdbcTransactionalMutationStore(dataSource), dataSource);
        TitanGraphqlControlJobQueue queue = new TitanGraphqlControlJobQueue(
                dataSource, TitanGraphqlControlJobQueue.Dialect.POSTGRESQL, Clock.systemUTC());
        assertTrue(new TitanGraphqlModelValidationJobRunner(queue,
                new TitanGraphqlModelValidationService(store)).runOne(Duration.ofMinutes(1)));

        JsonNode completed = post("{ controlJob(id: \"" + JOB_ID
                + "\") { id status attemptCount resultJson failureCode } }");
        assertEquals("succeeded", completed.at("/data/controlJob/status").asText(), completed::toString);
        assertEquals(1, completed.at("/data/controlJob/attemptCount").asInt(), completed::toString);
        JsonNode result = JSON.readTree(completed.at("/data/controlJob/resultJson").asText());
        assertTrue(result.path("accepted").asBoolean(), result::toString);
        assertEquals("http-draft", result.path("draftId").asText(), result::toString);
        JsonNode validated = post("{ modelDraft(id: \"http-draft\") { status } }");
        assertEquals("validated", validated.at("/data/modelDraft/status").asText(), validated::toString);

        Response rejectedGet = given()
                .accept(GraphqlHttpResource.GRAPHQL_RESPONSE_JSON)
                .header("Authorization", "Bearer database-admin-token")
                .queryParam("query", "mutation { requestModelValidation(id: \"" + JOB_ID
                        + "\", draftId: \"http-draft\") { id } }")
                .get("/admin/graphql");
        assertEquals(200, rejectedGet.statusCode());
        assertTrue(JSON.readTree(rejectedGet.asString()).has("errors"), rejectedGet::asString);
    }

    private static JsonNode post(String query) throws Exception {
        Response response = given()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(GraphqlHttpResource.GRAPHQL_RESPONSE_JSON)
                .header("Authorization", "Bearer database-admin-token")
                .body(Map.of("query", query))
                .post("/admin/graphql");
        assertEquals(200, response.statusCode(), response::asString);
        return JSON.readTree(response.asString());
    }
}
