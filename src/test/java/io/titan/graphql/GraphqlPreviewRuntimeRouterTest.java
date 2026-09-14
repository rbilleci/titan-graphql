package io.titan.graphql;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.management.TitanGraphqlOperationRegistry;
import io.titan.graphql.management.TitanGraphqlPreviewBuild;
import io.titan.graphql.management.TitanGraphqlPreviewContractTestReport;
import io.titan.graphql.management.TitanGraphqlPreviewContractTestReportJson;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GraphqlPreviewRuntimeRouterTest {

    @AfterEach
    void clearPreviewCandidates() {
        GraphqlPreviewRuntimeRouter.clearCandidatesForTests();
    }

    @Test
    void routesPreviewRequestToRegisteredCandidateSchema() {
        GraphqlPreviewRuntimeRouter.registerCandidate(
                "preview-001",
                "artifact-001",
                new PreviewOnlyDataModel()
        );

        GraphqlPreviewHttpResource.GraphqlPreviewHttpResult response =
                new GraphqlPreviewHttpResource().negotiatePost(
                        "preview-001",
                        Map.of("query", "{ previewArticle(id: 1) { id title previewOnly } }"),
                        MediaType.APPLICATION_JSON,
                        GraphqlHttpResource.GraphqlHttpContext.defaults()
                );

        assertEquals(200, response.status());
        assertEquals(MediaType.APPLICATION_JSON, response.mediaType());
        assertEquals(
                "{\"data\":{\"previewArticle\":{\"id\":1,\"title\":\"Candidate title\",\"previewOnly\":\"candidate-artifact\"}}}",
                response.body()
        );
    }

    @Test
    void keepsStableApplicationGraphqlIndependentFromPreviewCandidates() {
        GraphqlPreviewRuntimeRouter.registerCandidate(
                "preview-001",
                "artifact-001",
                new PreviewOnlyDataModel()
        );

        GraphqlPreviewHttpResource.GraphqlPreviewHttpResult preview =
                new GraphqlPreviewHttpResource().negotiatePost(
                        "preview-001",
                        Map.of("query", "{ previewArticle(id: 1) { id previewOnly } }"),
                        "application/graphql-response+json",
                        GraphqlHttpResource.GraphqlHttpContext.defaults()
                );
        GraphqlHttpResource.GraphqlHttpResult stable =
                new GraphqlHttpResource().negotiatePost(
                        Map.of("query", "{ previewArticle(id: 1) { id previewOnly } }"),
                        "application/graphql-response+json"
                );

        assertEquals("{\"data\":{\"previewArticle\":{\"id\":1,\"previewOnly\":\"candidate-artifact\"}}}", preview.body());
        assertTrue(stable.body().contains("unsupported root field 'previewArticle'"));
        assertTrue(stable.body().contains(GraphqlException.VALIDATION_ERROR));
    }

    @Test
    void introspectsPreviewCandidateSchemaWithoutChangingStableSchema() {
        GraphqlPreviewRuntimeRouter.registerCandidate(
                "preview-001",
                "artifact-001",
                new PreviewOnlyDataModel()
        );
        GraphqlHttpResource.GraphqlHttpContext introspection =
                new GraphqlHttpResource.GraphqlHttpContext(0L, "reader", false, false, false, true, "");

        GraphqlPreviewHttpResource.GraphqlPreviewHttpResult preview =
                new GraphqlPreviewHttpResource().negotiateGet(
                        "preview-001",
                        "{ __type(name: \"PreviewArticle\") { name fields { name } } }",
                        "",
                        "",
                        "",
                        MediaType.APPLICATION_JSON,
                        introspection
                );
        GraphqlHttpResource.GraphqlHttpResult stable =
                new GraphqlHttpResource().negotiatePost(
                        Map.of("query", "{ __type(name: \"PreviewArticle\") { name fields { name } } }"),
                        MediaType.APPLICATION_JSON,
                        introspection
                );

        assertTrue(preview.body().contains("\"name\":\"PreviewArticle\""));
        assertTrue(preview.body().contains("\"name\":\"previewOnly\""));
        assertEquals("{\"data\":{\"__type\":null}}", stable.body());
    }

    @Test
    void reportsUnknownPreviewBuildWithoutFallingBackToStableSchema() {
        GraphqlPreviewHttpResource.GraphqlPreviewHttpResult response =
                new GraphqlPreviewHttpResource().negotiatePost(
                        "missing-preview",
                        Map.of("query", "{ article(id: 1) { id } }"),
                        MediaType.APPLICATION_JSON,
                        GraphqlHttpResource.GraphqlHttpContext.defaults()
                );

        assertTrue(response.body().contains("preview build 'missing-preview' is not registered"));
        assertTrue(response.body().contains(GraphqlException.VALIDATION_ERROR));
    }

    @Test
    void bootsPreviewGraphqlRouteOverHttp() {
        GraphqlPreviewRuntimeRouter.registerCandidate(
                "preview-001",
                "artifact-001",
                new PreviewOnlyDataModel()
        );

        given()
                .contentType(MediaType.APPLICATION_JSON)
                .accept("application/graphql-response+json")
                .body("""
                        {
                          "query": "{ previewArticle(id: 1) { id title previewOnly } }"
                        }
                        """)
                .when()
                .post("/preview/preview-001/graphql")
                .then()
                .statusCode(200)
                .header("Content-Type", startsWith("application/graphql-response+json"))
                .body("data.previewArticle.id", equalTo(1))
                .body("data.previewArticle.title", equalTo("Candidate title"))
                .body("data.previewArticle.previewOnly", equalTo("candidate-artifact"));
    }

    @Test
    void runsApprovedOperationContractTestsAgainstPreviewCandidate() {
        GraphqlPreviewRuntimeRouter.registerCandidate(
                "preview-001",
                "artifact-001",
                new PreviewOnlyDataModel()
        );
        TitanGraphqlPreviewBuild previewBuild = new TitanGraphqlPreviewBuild(
                "preview-001",
                "model-001",
                "draft-001",
                "artifact-001",
                "dev",
                TitanGraphqlPreviewBuild.PreviewBuildStatus.READY,
                "/preview/preview-001/graphql",
                "",
                "schema-sha",
                "",
                "",
                "registry-001",
                "",
                "test-operator",
                "2026-06-01T19:40:00Z"
        );
        TitanGraphqlOperationRegistry registry = new TitanGraphqlOperationRegistry(
                "registry-001",
                "model-001",
                "dev",
                TitanGraphqlOperationRegistry.RegistryMode.WARN,
                List.of(
                        new TitanGraphqlOperationRegistry.RegisteredOperation(
                                "operation-approved",
                                "PreviewArticleContract",
                                "operation-sha",
                                "query PreviewArticleContract { previewArticle(id: 1) { id previewOnly } }",
                                TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED,
                                List.of("viewer"),
                                List.of("portal"),
                                2,
                                3,
                                List.of("PreviewArticle.id", "PreviewArticle.previewOnly"),
                                "2026-06-01T19:39:00Z",
                                "test-operator",
                                "2026-06-01T19:40:00Z"
                        ),
                        new TitanGraphqlOperationRegistry.RegisteredOperation(
                                "operation-observed",
                                "ObservedOnly",
                                "observed-sha",
                                "query ObservedOnly { previewArticle(id: 1) { id } }",
                                TitanGraphqlOperationRegistry.RegisteredOperationStatus.OBSERVED,
                                List.of("viewer"),
                                List.of("portal"),
                                2,
                                1,
                                List.of("PreviewArticle.id"),
                                "2026-06-01T19:39:30Z",
                                "",
                                ""
                        )
                ),
                "2026-06-01T19:41:00Z"
        );

        TitanGraphqlPreviewContractTestReport report = GraphqlPreviewContractTestRunner.run(
                previewBuild,
                registry,
                GraphqlRequestContext.legacy(0L, "viewer"),
                "2026-06-01T19:42:00Z"
        );
        String json = TitanGraphqlPreviewContractTestReportJson.reportJson(report);

        assertEquals(TitanGraphqlPreviewContractTestReport.ContractTestReportStatus.PASS, report.status());
        assertEquals(2, report.totalOperations());
        assertEquals(1, report.passedOperations());
        assertEquals(0, report.failedOperations());
        assertEquals(1, report.skippedOperations());
        assertEquals(TitanGraphqlPreviewContractTestReport.ContractTestOperationStatus.PASS, report.operationResults().get(0).status());
        assertEquals(64, report.operationResults().get(0).responseHash().length());
        assertEquals(TitanGraphqlPreviewContractTestReport.ContractTestOperationStatus.SKIPPED, report.operationResults().get(1).status());
        assertTrue(json.contains("\"previewBuildId\":\"preview-001\""));
        assertTrue(json.contains("\"operationRegistryId\":\"registry-001\""));
        assertTrue(json.contains("\"responseHash\""));
    }

    @Test
    void warnsForUnknownPreviewOperationsInWarnMode() {
        GraphqlPreviewRuntimeRouter.registerCandidate(
                "preview-001",
                "artifact-001",
                new PreviewOnlyDataModel(),
                registry(
                        TitanGraphqlOperationRegistry.RegistryMode.WARN,
                        List.of(approvedOperation(
                                "operation-approved",
                                "ApprovedPreview",
                                "query ApprovedPreview { previewArticle(id: 1) { id } }",
                                TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED
                        ))
                )
        );

        GraphqlPreviewHttpResource.GraphqlPreviewHttpResult response =
                new GraphqlPreviewHttpResource().negotiatePost(
                        "preview-001",
                        Map.of(
                                "query", "query NewPreview { previewArticle(id: 1) { id previewOnly } }",
                                "operationName", "NewPreview",
                                "extensions", Map.of("client", "portal")
                        ),
                        MediaType.APPLICATION_JSON,
                        new GraphqlHttpResource.GraphqlHttpContext(0L, "viewer", false, false, false, false, "")
                );

        assertTrue(response.body().contains("\"data\""));
        assertTrue(response.body().contains("\"extensions\":{\"warnings\""));
        assertTrue(response.body().contains("operation registry warning: unknown operation"));
        assertTrue(response.body().contains("OPERATION_REGISTRY_WARNING"));
    }

    @Test
    void rejectsUnknownPreviewOperationsInEnforceMode() {
        GraphqlPreviewRuntimeRouter.registerCandidate(
                "preview-001",
                "artifact-001",
                new PreviewOnlyDataModel(),
                registry(TitanGraphqlOperationRegistry.RegistryMode.ENFORCE, List.of())
        );

        GraphqlPreviewHttpResource.GraphqlPreviewHttpResult response =
                new GraphqlPreviewHttpResource().negotiatePost(
                        "preview-001",
                        Map.of(
                                "query", "query NewPreview { previewArticle(id: 1) { id } }",
                                "operationName", "NewPreview",
                                "extensions", Map.of("client", "portal")
                        ),
                        MediaType.APPLICATION_JSON,
                        new GraphqlHttpResource.GraphqlHttpContext(0L, "viewer", false, false, false, false, "")
                );

        assertTrue(response.body().contains("operation registry rejected unknown operation"));
        assertTrue(response.body().contains("OPERATION_REGISTRY_REJECTED"));
        assertTrue(response.body().contains("\"mode\":\"ENFORCE\""));
        assertTrue(response.body().contains("\"status\":\"UNKNOWN\""));
    }

    @Test
    void observesUnknownPreviewOperationsWithoutWarningOrRejection() {
        GraphqlPreviewRuntimeRouter.registerCandidate(
                "preview-001",
                "artifact-001",
                new PreviewOnlyDataModel(),
                registry(TitanGraphqlOperationRegistry.RegistryMode.OBSERVE, List.of())
        );

        GraphqlPreviewHttpResource.GraphqlPreviewHttpResult response =
                new GraphqlPreviewHttpResource().negotiatePost(
                        "preview-001",
                        Map.of("query", "query NewPreview { previewArticle(id: 1) { id } }"),
                        MediaType.APPLICATION_JSON,
                        new GraphqlHttpResource.GraphqlHttpContext(0L, "viewer", false, false, false, false, "")
                );

        assertEquals("{\"data\":{\"previewArticle\":{\"id\":1}}}", response.body());
    }

    @Test
    void rejectsRejectedPreviewOperationsInEnforceMode() {
        String document = "query RejectedPreview { previewArticle(id: 1) { id } }";
        GraphqlPreviewRuntimeRouter.registerCandidate(
                "preview-001",
                "artifact-001",
                new PreviewOnlyDataModel(),
                registry(
                        TitanGraphqlOperationRegistry.RegistryMode.ENFORCE,
                        List.of(approvedOperation(
                                "operation-rejected",
                                "RejectedPreview",
                                document,
                                TitanGraphqlOperationRegistry.RegisteredOperationStatus.REJECTED
                        ))
                )
        );

        GraphqlPreviewHttpResource.GraphqlPreviewHttpResult response =
                new GraphqlPreviewHttpResource().negotiatePost(
                        "preview-001",
                        Map.of(
                                "query", document,
                                "operationName", "RejectedPreview",
                                "extensions", Map.of("client", "portal")
                        ),
                        MediaType.APPLICATION_JSON,
                        new GraphqlHttpResource.GraphqlHttpContext(0L, "viewer", false, false, false, false, "")
                );

        assertTrue(response.body().contains("operation registry rejected rejected operation"));
        assertTrue(response.body().contains("\"status\":\"REJECTED\""));
    }

    @Test
    void permitsApprovedPreviewOperationsInEnforceModeForMatchingRoleAndClient() {
        String document = "query ApprovedPreview { previewArticle(id: 1) { id previewOnly } }";
        GraphqlPreviewRuntimeRouter.registerCandidate(
                "preview-001",
                "artifact-001",
                new PreviewOnlyDataModel(),
                registry(
                        TitanGraphqlOperationRegistry.RegistryMode.ENFORCE,
                        List.of(approvedOperation(
                                "operation-approved",
                                "ApprovedPreview",
                                document,
                                TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED
                        ))
                )
        );

        GraphqlPreviewHttpResource.GraphqlPreviewHttpResult response =
                new GraphqlPreviewHttpResource().negotiatePost(
                        "preview-001",
                        Map.of(
                                "query", document,
                                "operationName", "ApprovedPreview",
                                "extensions", Map.of("client", "portal")
                        ),
                        MediaType.APPLICATION_JSON,
                        new GraphqlHttpResource.GraphqlHttpContext(0L, "viewer", false, false, false, false, "")
                );

        assertEquals("{\"data\":{\"previewArticle\":{\"id\":1,\"previewOnly\":\"candidate-artifact\"}}}", response.body());
    }

    @Test
    void treatsApprovedOperationForDifferentClientAsUnknownInEnforceMode() {
        String document = "query ApprovedPreview { previewArticle(id: 1) { id } }";
        GraphqlPreviewRuntimeRouter.registerCandidate(
                "preview-001",
                "artifact-001",
                new PreviewOnlyDataModel(),
                registry(
                        TitanGraphqlOperationRegistry.RegistryMode.ENFORCE,
                        List.of(new TitanGraphqlOperationRegistry.RegisteredOperation(
                                "operation-approved",
                                "ApprovedPreview",
                                GraphqlOperationRegistryEnforcement.operationHash(document),
                                document,
                                TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED,
                                List.of("viewer"),
                                List.of("portal"),
                                2,
                                1,
                                List.of("PreviewArticle.id"),
                                "",
                                "test-operator",
                                ""
                        ))
                )
        );

        GraphqlPreviewHttpResource.GraphqlPreviewHttpResult response =
                new GraphqlPreviewHttpResource().negotiatePost(
                        "preview-001",
                        Map.of(
                                "query", document,
                                "operationName", "ApprovedPreview",
                                "extensions", Map.of("client", "admin-tool")
                        ),
                        MediaType.APPLICATION_JSON,
                        new GraphqlHttpResource.GraphqlHttpContext(0L, "viewer", false, false, false, false, "")
                );

        assertTrue(response.body().contains("operation registry rejected unknown operation"));
        assertTrue(response.body().contains("\"status\":\"UNKNOWN\""));
    }

    @Test
    void marksPreviewContractTestFailureWhenCandidateReturnsGraphqlErrors() {
        GraphqlPreviewRuntimeRouter.registerCandidate(
                "preview-001",
                "artifact-001",
                new PreviewOnlyDataModel()
        );
        TitanGraphqlPreviewBuild previewBuild = new TitanGraphqlPreviewBuild(
                "preview-001",
                "model-001",
                "draft-001",
                "artifact-001",
                "dev",
                TitanGraphqlPreviewBuild.PreviewBuildStatus.READY,
                "/preview/preview-001/graphql",
                "",
                "",
                "",
                "",
                "registry-001",
                "",
                "",
                ""
        );
        TitanGraphqlOperationRegistry registry = new TitanGraphqlOperationRegistry(
                "registry-001",
                "model-001",
                "dev",
                TitanGraphqlOperationRegistry.RegistryMode.WARN,
                List.of(new TitanGraphqlOperationRegistry.RegisteredOperation(
                        "operation-broken",
                        "BrokenPreviewContract",
                        "broken-sha",
                        "query BrokenPreviewContract { previewArticle(id: 1) { missingField } }",
                        TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED,
                        List.of("viewer"),
                        List.of("portal"),
                        2,
                        2,
                        List.of("PreviewArticle.missingField"),
                        "",
                        "test-operator",
                        ""
                )),
                ""
        );

        TitanGraphqlPreviewContractTestReport report = GraphqlPreviewContractTestRunner.run(
                previewBuild,
                registry,
                GraphqlRequestContext.legacy(0L, "viewer"),
                "2026-06-01T19:43:00Z"
        );

        assertEquals(TitanGraphqlPreviewContractTestReport.ContractTestReportStatus.FAIL, report.status());
        assertEquals(1, report.failedOperations());
        assertEquals("preview execution returned GraphQL errors", report.operationResults().getFirst().message());
    }

    private static TitanGraphqlOperationRegistry registry(
            TitanGraphqlOperationRegistry.RegistryMode mode,
            List<TitanGraphqlOperationRegistry.RegisteredOperation> operations
    ) {
        return new TitanGraphqlOperationRegistry(
                "registry-001",
                "model-001",
                "dev",
                mode,
                operations,
                "2026-06-01T20:05:00Z"
        );
    }

    private static TitanGraphqlOperationRegistry.RegisteredOperation approvedOperation(
            String id,
            String name,
            String document,
            TitanGraphqlOperationRegistry.RegisteredOperationStatus status
    ) {
        return new TitanGraphqlOperationRegistry.RegisteredOperation(
                id,
                name,
                GraphqlOperationRegistryEnforcement.operationHash(document),
                document,
                status,
                List.of("viewer"),
                List.of("portal"),
                2,
                3,
                List.of("PreviewArticle.id"),
                "2026-06-01T20:04:00Z",
                "test-operator",
                "2026-06-01T20:05:00Z"
        );
    }

    private static final class PreviewOnlyDataModel implements GraphqlDataModel {

        private final GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField("previewArticle", "PreviewArticle", "id")),
                List.of(new GraphqlObjectType(
                        "PreviewArticle",
                        List.of(
                                GraphqlFieldDescriptor.scalar("id"),
                                GraphqlFieldDescriptor.scalar("title"),
                                GraphqlFieldDescriptor.scalarColumn("previewOnly", "preview_only")
                        )
                ))
        );

        @Override
        public GraphqlSchema schema() {
            return schema;
        }

        @Override
        public GraphqlExecution execute(GraphqlSelection selection, GraphqlRequestContext context) {
            String json = "{\"data\":{\"" + selection.rootResponseKey() + "\":{\"id\":1";
            if (selection.scalarFieldNames().contains("title")) {
                json += ",\"title\":\"Candidate title\"";
            }
            if (selection.scalarFieldNames().contains("previewOnly")) {
                json += ",\"previewOnly\":\"candidate-artifact\"";
            }
            json += "}}}";
            return new GraphqlExecution(json, new GraphqlPlan());
        }
    }
}
