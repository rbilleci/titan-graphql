package io.titan.graphql;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.titan.graphql.artifact.TitanGraphqlArtifactsDirectory;
import io.titan.graphql.artifact.TitanGraphqlPackageBinding;
import io.titan.graphql.conformance.GraphqlSqlModeConformanceCorpus;
import io.titan.graphql.sqlmode.GraphqlSqlEntryPointDispatch.Invocation;
import jakarta.ws.rs.core.MediaType;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The W5.1 live-serving proof: Quarkus boots with {@code titan.graphql.execution.mode=sql}
 * and a datasource pointing at a container carrying the deployed packaged kernel
 * ({@link GraphqlSqlModeServingResource}), and the REAL HTTP layer answers GraphQL requests
 * from the database.
 *
 * <p>Asserts, over HTTP: a representative corpus subset (spanning all six kernel entry-point
 * shapes) answers identically to the Java kernel; telemetry rows land in
 * {@code titan_runtime.telemetry} of the serving database; and the mode surface headers
 * report {@code sql} plus the exact reviewed-model/package deployment fingerprint.</p>
 *
 * <p>Booting Quarkus for real (rather than invoking the resource class directly) was chosen
 * deliberately: the demo claim is that the configured mode, the CDI datasource wiring, and
 * the HTTP transport together serve from the database — resource-level invocation would not
 * prove the config/Agroal path.</p>
 */
@Tag("docker")
@Tag("legacy-sql")
@QuarkusTest
@QuarkusTestResource(value = GraphqlSqlModeServingResource.class, restrictToAnnotatedClass = true)
class GraphqlSqlModeHttpIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The representative corpus subset (by corpus case name): every one of the six
     * {@link Invocation} shapes is covered, across nine conformance areas.
     */
    private static final List<String> REPRESENTATIVE_CASES = List.of(
            "shorthand-article-author",            // QC1,  Execute
            "select-second-operation",             // QC1,  Request
            "envelope-variables-and-extensions",   // QC1,  RequestWithVariables
            "structured-filter-variable",          // QC3,  RequestWithVariables
            "root-and-nested-aliases",             // QC4,  Execute
            "title-eq-filter",                     // QC6,  Execute
            "root-total-count",                    // QC7,  Execute
            "computed-title-length",               // QC8,  Execute
            "published-visibility-rows-and-counts",// QC9,  WithContext
            "visibility-fails-closed-without-key", // QC9,  WithContext
            "compact-context-enabled-filter-names",// QC9,  CompactContext
            "schema-smoke-subset",                 // QC10, WithIntrospection
            "post-delegated-envelope"              // QC11, CompactContext
    );

    @Test
    void sqlModeAnswersRepresentativeCorpusOverHttpIdenticallyToJavaKernel() throws Exception {
        List<GraphqlSqlModeConformanceCorpus.Case> cases = representativeCases();
        assertTrue(cases.size() >= 10, "representative subset shrank below 10: " + cases.size());
        assertEquals(REPRESENTATIVE_CASES.size(), cases.size(), "corpus case names drifted");
        assertEquals(6,
                cases.stream().map(corpusCase -> corpusCase.invocation().getClass()).distinct().count(),
                "the subset must span all six kernel entry-point shapes");

        for (GraphqlSqlModeConformanceCorpus.Case corpusCase : cases) {
            String javaJson = GraphqlSqlModeConformanceCorpus.executeJavaMode(corpusCase.invocation());
            Response httpResponse = httpExecute(corpusCase.invocation());

            String label = corpusCase.rowId() + " / " + corpusCase.name();
            assertEquals(200, httpResponse.statusCode(), label);
            assertEquals("sql", httpResponse.header(GraphqlHttpResource.EXECUTION_MODE_HEADER), label);
            assertEquals(
                    MAPPER.readTree(javaJson),
                    MAPPER.readTree(httpResponse.asString()),
                    () -> label + "\n  java-mode json: " + javaJson + "\n  sql-mode http json: "
                            + httpResponse.asString());
        }
    }

    @Test
    void sqlModeAnswersGetRequestsFromTheDatabaseToo() throws Exception {
        // The GET transport passes variables/extensions JSON verbatim — same corpus shape,
        // other entry door.
        GraphqlSqlModeConformanceCorpus.Case corpusCase = corpusCase("get-delegated-query-only-request");
        Invocation.CompactContext invocation = (Invocation.CompactContext) corpusCase.invocation();
        String javaJson = GraphqlSqlModeConformanceCorpus.executeJavaMode(invocation);

        Response httpResponse = given()
                .accept(MediaType.APPLICATION_JSON)
                .queryParam("query", invocation.query())
                .queryParam("operationName", invocation.operationName())
                .queryParam("variables", invocation.variablesJson())
                .queryParam("extensions", invocation.extensionsJson())
                .when()
                .get("/graphql");

        assertEquals(200, httpResponse.statusCode());
        assertEquals("sql", httpResponse.header(GraphqlHttpResource.EXECUTION_MODE_HEADER));
        assertEquals(MAPPER.readTree(javaJson), MAPPER.readTree(httpResponse.asString()));
    }

    @Test
    void modeSurfaceReportsSqlAndTheDeployedArtifactFingerprint() {
        // integrationTest creates the exact reviewed-model/package sidecar after installation.
        String expectedFingerprint = TitanGraphqlPackageBinding
                .read(TitanGraphqlArtifactsDirectory.configuredDirectory())
                .deploymentFingerprint();

        Response response = given()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(GraphqlHttpResource.GRAPHQL_RESPONSE_JSON)
                .body(Map.of("query", "{ article(id: 1) { id } }"))
                .post("/graphql");

        assertEquals(200, response.statusCode());
        assertEquals("sql", response.header(GraphqlHttpResource.EXECUTION_MODE_HEADER));
        assertEquals(expectedFingerprint, response.header(GraphqlHttpResource.DEPLOYMENT_FINGERPRINT_HEADER));
        assertNotEquals("unavailable", response.header(GraphqlHttpResource.DEPLOYMENT_FINGERPRINT_HEADER));
    }

    @Test
    void telemetryRowsLandInTheServingDatabase() throws Exception {
        given()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(GraphqlHttpResource.GRAPHQL_RESPONSE_JSON)
                .body(Map.of("query", "{ article(id: 1) { id title } }"))
                .post("/graphql")
                .then()
                .statusCode(200);

        // Programmatic config lookup (not @ConfigProperty injection: undefined-in-default-profile
        // injection points would fail validation when OTHER tests boot Quarkus).
        org.eclipse.microprofile.config.Config config = org.eclipse.microprofile.config.ConfigProvider.getConfig();
        String jdbcUrl = config.getValue("quarkus.datasource.jdbc.url", String.class);
        String dbUsername = config.getValue("quarkus.datasource.username", String.class);
        String dbPassword = config.getValue("quarkus.datasource.password", String.class);

        // The client-side sink rows carry the placeholder SELECT text; rows the routines write
        // from inside the database carry routine names instead, so this filter counts exactly
        // the HTTP-layer telemetry.
        try (Connection connection = DriverManager.getConnection(jdbcUrl, dbUsername, dbPassword);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT count(*) FROM titan_runtime.telemetry"
                             + " WHERE procedure_name LIKE"
                             + " 'SELECT public.execute_graphql_request_with_compact_context%'"
                             + " AND status = 'success'")) {
            assertTrue(resultSet.next());
            long successRows = resultSet.getLong(1);
            assertTrue(successRows >= 1,
                    "expected HTTP-layer telemetry rows in titan_runtime.telemetry, found " + successRows);
        }
    }

    // --- HTTP mapping of corpus invocations --------------------------------------------------

    /**
     * Maps a corpus invocation onto the HTTP transport. Every kernel entry point is sugar over
     * the compact-context entry point (verified in {@code DemoBlogTitanGraphqlFunctions}), and
     * the HTTP layer always delegates the full compact-context tuple, so each shape has an
     * exact HTTP equivalent: request body fields plus {@code X-Titan-*} context headers.
     */
    private static Response httpExecute(Invocation invocation) throws Exception {
        return switch (invocation) {
            case Invocation.Execute c -> graphqlPost(c.actorId(), c.actorRole(), body(c.query(), "", "", ""));
            case Invocation.Request c ->
                    graphqlPost(c.actorId(), c.actorRole(), body(c.query(), c.operationName(), "", ""));
            case Invocation.RequestWithVariables c -> graphqlPost(c.actorId(), c.actorRole(),
                    body(c.query(), c.operationName(), c.variablesJson(), c.extensionsJson()));
            case Invocation.WithContext c -> {
                RequestSpecification request = baseRequest(c.actorId(), c.actorRole())
                        .header("X-Titan-Context-Filter-Published-Visibility",
                                String.valueOf(c.enablePublishedVisibility()));
                if (c.hasArticleVisibility()) {
                    request = request.header("X-Titan-Article-Visibility", String.valueOf(c.articleVisibility()));
                }
                yield request.body(body(c.query(), "", "", "")).post("/graphql");
            }
            case Invocation.WithIntrospection c -> baseRequest(c.actorId(), c.actorRole())
                    .header("X-Titan-Introspection", String.valueOf(c.enableIntrospection()))
                    .body(body(c.query(), "", "", ""))
                    .post("/graphql");
            case Invocation.CompactContext c -> {
                RequestSpecification request = baseRequest(c.actorId(), c.actorRole())
                        .header("X-Titan-Context-Filter-Published-Visibility",
                                String.valueOf(c.enablePublishedVisibility()))
                        .header("X-Titan-Introspection", String.valueOf(c.enableIntrospection()));
                if (c.hasArticleVisibility()) {
                    request = request.header("X-Titan-Article-Visibility", String.valueOf(c.articleVisibility()));
                }
                if (c.tenantId().isEmpty() == false) {
                    request = request.header("X-Titan-Tenant-Id", c.tenantId());
                }
                if (c.requestId().isEmpty() == false) {
                    request = request.header("X-Titan-Request-Id", c.requestId());
                }
                if (c.policyFlags().isEmpty() == false) {
                    request = request.header("X-Titan-Policy-Flags", c.policyFlags());
                }
                if (c.enabledContextFilters().isEmpty() == false) {
                    request = request.header("X-Titan-Context-Filters", c.enabledContextFilters());
                }
                if (c.deadlineBudgetMillis() != 0L) {
                    request = request.header("X-Titan-Deadline-Budget-Millis",
                            String.valueOf(c.deadlineBudgetMillis()));
                }
                yield request
                        .body(body(c.query(), c.operationName(), c.variablesJson(), c.extensionsJson()))
                        .post("/graphql");
            }
        };
    }

    private static Response graphqlPost(long actorId, String actorRole, Map<String, Object> body) {
        return baseRequest(actorId, actorRole).body(body).post("/graphql");
    }

    private static RequestSpecification baseRequest(long actorId, String actorRole) {
        return given()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(GraphqlHttpResource.GRAPHQL_RESPONSE_JSON)
                .header("X-Titan-Actor-Id", String.valueOf(actorId))
                .header("X-Titan-Actor-Role", actorRole);
    }

    private static Map<String, Object> body(
            String query, String operationName, String variablesJson, String extensionsJson) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        if (operationName.isEmpty() == false) {
            body.put("operationName", operationName);
        }
        if (variablesJson.isEmpty() == false) {
            body.put("variables", MAPPER.readValue(variablesJson, Map.class));
        }
        if (extensionsJson.isEmpty() == false) {
            body.put("extensions", MAPPER.readValue(extensionsJson, Map.class));
        }
        return body;
    }

    private static List<GraphqlSqlModeConformanceCorpus.Case> representativeCases() {
        List<GraphqlSqlModeConformanceCorpus.Case> selected = REPRESENTATIVE_CASES.stream()
                .map(GraphqlSqlModeHttpIT::corpusCase)
                .toList();
        assertFalse(selected.isEmpty());
        return selected;
    }

    private static GraphqlSqlModeConformanceCorpus.Case corpusCase(String name) {
        return GraphqlSqlModeConformanceCorpus.cases().stream()
                .filter(corpusCase -> corpusCase.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("corpus case not found: " + name));
    }
}
