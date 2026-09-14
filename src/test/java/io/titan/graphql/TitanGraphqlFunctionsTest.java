package io.titan.graphql;

import io.titan.graphql.demo.blog.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.management.TitanGraphqlInMemoryManagementStore;
import io.titan.graphql.management.TitanGraphqlManagementProjection;
import io.titan.graphql.management.TitanGraphqlObservedOperation;
import io.titan.graphql.management.TitanGraphqlOperationRegistry;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

class TitanGraphqlFunctionsTest {

    @Test
    void quarkusPostResourceDelegatesAcceptedEnvelopeToLoweredRequestPath() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("id", "1");
        variables.put("visible", true);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("query", """
                query Pick($id: ID!, $visible: Boolean!) {
                  article(id: $id) {
                    id
                    title @include(if: $visible)
                  }
                }
                """);
        request.put("operationName", "Pick");
        request.put("variables", variables);
        request.put("extensions", Map.of());

        String json = new GraphqlHttpResource().post(request);

        assertEquals("{\"data\":{\"article\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}}}", json);
    }

    @Test
    void quarkusPostResourcePrefersGraphqlResponseMediaType() {
        GraphqlHttpResource.GraphqlHttpResult response = new GraphqlHttpResource().negotiatePost(
                Map.of("query", "{ article(id: 1) { id } }"),
                "application/graphql-response+json, application/json;q=0.9"
        );

        assertEquals(200, response.status());
        assertEquals("application/graphql-response+json", response.mediaType());
        assertEquals("{\"data\":{\"article\":{\"id\":1}}}", response.body());
    }

    @Test
    void quarkusPostResourceFallsBackToJsonMediaType() {
        GraphqlHttpResource.GraphqlHttpResult response = new GraphqlHttpResource().negotiatePost(
                Map.of("query", "{ article(id: 1) { id } }"),
                MediaType.APPLICATION_JSON
        );

        assertEquals(200, response.status());
        assertEquals(MediaType.APPLICATION_JSON, response.mediaType());
        assertEquals("{\"data\":{\"article\":{\"id\":1}}}", response.body());
    }

    @Test
    void quarkusPostResourceHonorsAcceptQualityFallback() {
        GraphqlHttpResource.GraphqlHttpResult response = new GraphqlHttpResource().negotiatePost(
                Map.of("query", "{ article(id: 1) { id } }"),
                "application/graphql-response+json;q=0.5, application/json;q=1.0"
        );

        assertEquals(200, response.status());
        assertEquals(MediaType.APPLICATION_JSON, response.mediaType());
    }

    @Test
    void quarkusPostResourceUsesGraphqlResponseForWildcardOrMissingAccept() {
        GraphqlHttpResource resource = new GraphqlHttpResource();

        GraphqlHttpResource.GraphqlHttpResult wildcard =
                resource.negotiatePost(Map.of("query", "{ article(id: 1) { id } }"), "*/*");
        GraphqlHttpResource.GraphqlHttpResult missing =
                resource.negotiatePost(Map.of("query", "{ article(id: 1) { id } }"), null);

        assertEquals(200, wildcard.status());
        assertEquals("application/graphql-response+json", wildcard.mediaType());
        assertEquals(200, missing.status());
        assertEquals("application/graphql-response+json", missing.mediaType());
    }

    @Test
    void quarkusGetResourceExecutesQueryParameter() {
        GraphqlHttpResource.GraphqlHttpResult response = new GraphqlHttpResource().negotiateGet(
                "{ article(id: 1) { id title } }",
                null,
                null,
                null,
                MediaType.APPLICATION_JSON
        );

        assertEquals(200, response.status());
        assertEquals(MediaType.APPLICATION_JSON, response.mediaType());
        assertEquals("{\"data\":{\"article\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}}}", response.body());
    }

    @Test
    void quarkusGetResourceTransportsOperationNameAndVariables() {
        GraphqlHttpResource.GraphqlHttpResult response = new GraphqlHttpResource().negotiateGet(
                """
                query One {
                  article(id: 2) { id title }
                }
                query Pick($id: ID!, $showTitle: Boolean!) {
                  article(id: $id) {
                    id
                    title @include(if: $showTitle)
                  }
                }
                """,
                "Pick",
                "{\"id\":\"1\",\"showTitle\":true}",
                "{}",
                "application/graphql-response+json"
        );

        assertEquals(200, response.status());
        assertEquals("application/graphql-response+json", response.mediaType());
        assertEquals("{\"data\":{\"article\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}}}", response.body());
    }

    @Test
    void quarkusGetResourceSharesContextExtraction() {
        GraphqlHttpResource.GraphqlHttpResult response = new GraphqlHttpResource().negotiateGet(
                "{ articles(first: 2) { edges { node { id title } } totalCount } }",
                null,
                null,
                null,
                MediaType.APPLICATION_JSON,
                GraphqlHttpResource.httpContext(
                        "10",
                        "reader",
                        null,
                        "true",
                        null,
                        "tenant-a",
                        "request-7",
                        "can-preview",
                        "publishedVisibility",
                        "2500"
                )
        );

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}}],\"totalCount\":1}}}",
                response.body()
        );
    }

    @Test
    void quarkusGetResourceValidatesQueryAndVariableParameters() {
        GraphqlHttpResource resource = new GraphqlHttpResource();

        GraphqlHttpResource.GraphqlHttpResult missingQuery =
                resource.negotiateGet(null, null, null, null, MediaType.APPLICATION_JSON);
        GraphqlHttpResource.GraphqlHttpResult invalidVariables =
                resource.negotiateGet("{ article(id: 1) { id } }", null, "[]", null, MediaType.APPLICATION_JSON);

        assertTrue(missingQuery.body().contains("GraphQL GET query parameter 'query' is required"));
        assertErrorCode(missingQuery.body(), GraphqlException.VALIDATION_ERROR);
        assertTrue(invalidVariables.body().contains("request field 'variables' must be a JSON object"));
        assertErrorCode(invalidVariables.body(), GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void quarkusGetResourceRejectsMutationOperationsThroughQueryOnlyEngine() {
        GraphqlHttpResource.GraphqlHttpResult response = new GraphqlHttpResource().negotiateGet(
                "mutation Change { article(id: 1) { id } }",
                null,
                null,
                null,
                MediaType.APPLICATION_JSON
        );

        assertTrue(response.body().contains("operation type 'mutation' is not supported"));
        assertErrorCode(response.body(), GraphqlException.UNSUPPORTED_OPERATION);
    }

    @Test
    void quarkusPostResourceRejectsUnsupportedAcceptMediaType() {
        GraphqlHttpResource.GraphqlHttpResult response = new GraphqlHttpResource().negotiatePost(
                Map.of("query", "{ article(id: 1) { id } }"),
                "text/plain"
        );

        assertEquals(406, response.status());
        assertEquals("application/graphql-response+json", response.mediaType());
        assertTrue(response.body().contains("GraphQL response media type is not acceptable"));
        assertErrorCode(response.body(), GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void quarkusPostResourceRejectsUnknownEnvelopeFields() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("query", "{ article(id: 1) { id } }");
        request.put("extra", true);

        String json = new GraphqlHttpResource().post(request);

        assertTrue(json.contains("unknown GraphQL request field 'extra'"));
        assertErrorCode(json, GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void quarkusPostResourceRequiresQueryString() {
        String json = new GraphqlHttpResource().post(Map.of("query", 12));

        assertTrue(json.contains("GraphQL request field 'query' is required and must be a string"));
        assertErrorCode(json, GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void quarkusPostResourceRequiresVariablesAndExtensionsObjects() {
        GraphqlHttpResource resource = new GraphqlHttpResource();

        String variablesJson = resource.post(Map.of(
                "query", "{ article(id: 1) { id } }",
                "variables", List.of("id")
        ));
        String extensionsJson = resource.post(Map.of(
                "query", "{ article(id: 1) { id } }",
                "extensions", "trace"
        ));

        assertTrue(variablesJson.contains("GraphQL request field 'variables' must be a JSON object"));
        assertErrorCode(variablesJson, GraphqlException.VALIDATION_ERROR);
        assertTrue(extensionsJson.contains("GraphQL request field 'extensions' must be a JSON object"));
        assertErrorCode(extensionsJson, GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void quarkusPostResourceExtractsActorRoleForAuthorization() {
        Map<String, Object> request = Map.of("query", "{ article(id: 1) { author { email } } }");

        GraphqlHttpResource.GraphqlHttpResult reader = new GraphqlHttpResource().negotiatePost(
                request,
                MediaType.APPLICATION_JSON,
                new GraphqlHttpResource.GraphqlHttpContext(10L, "reader", false, false, false, false, "")
        );
        GraphqlHttpResource.GraphqlHttpResult admin = new GraphqlHttpResource().negotiatePost(
                request,
                MediaType.APPLICATION_JSON,
                new GraphqlHttpResource.GraphqlHttpContext(10L, "admin", false, false, false, false, "")
        );

        assertTrue(reader.body().contains("field 'User.email' is not authorized for actor role 'reader'"));
        assertErrorCode(reader.body(), GraphqlException.AUTHORIZATION_ERROR);
        assertEquals("{\"data\":{\"article\":{\"author\":{\"email\":\"ada@example.test\"}}}}", admin.body());
    }

    @Test
    void quarkusPostResourceExtractsPublishedVisibilityContext() {
        GraphqlHttpResource.GraphqlHttpResult response = new GraphqlHttpResource().negotiatePost(
                Map.of("query", "{ articles(first: 2) { edges { node { id title } } totalCount } }"),
                MediaType.APPLICATION_JSON,
                new GraphqlHttpResource.GraphqlHttpContext(10L, "reader", true, true, true, false, "")
        );

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}}],\"totalCount\":1}}}",
                response.body()
        );
    }

    @Test
    void quarkusPostResourceFailsClosedWhenPublishedVisibilityValueIsMissing() {
        GraphqlHttpResource.GraphqlHttpResult response = new GraphqlHttpResource().negotiatePost(
                Map.of("query", "{ articles(first: 2) { edges { node { id title } } totalCount } }"),
                MediaType.APPLICATION_JSON,
                new GraphqlHttpResource.GraphqlHttpContext(10L, "reader", true, false, false, false, "")
        );

        assertEquals("{\"data\":{\"articles\":{\"edges\":[],\"totalCount\":0}}}", response.body());
    }

    @Test
    void quarkusPostResourceExtractsEnabledIntrospectionPolicy() {
        GraphqlHttpResource.GraphqlHttpResult disabled = new GraphqlHttpResource().negotiatePost(
                Map.of("query", "{ __schema { queryType { name } } }"),
                MediaType.APPLICATION_JSON,
                new GraphqlHttpResource.GraphqlHttpContext(10L, "reader", false, false, false, false, "")
        );
        GraphqlHttpResource.GraphqlHttpResult enabled = new GraphqlHttpResource().negotiatePost(
                Map.of("query", "{ __schema { queryType { name } } }"),
                MediaType.APPLICATION_JSON,
                new GraphqlHttpResource.GraphqlHttpContext(10L, "reader", false, false, false, true, "")
        );

        assertTrue(disabled.body().contains("introspection is disabled"));
        assertEquals("{\"data\":{\"__schema\":{\"queryType\":{\"name\":\"Query\"}}}}", enabled.body());
    }

    @Test
    void adminPostResourceServesManagementSchemaWithSeparateIntrospectionPolicy() {
        GraphqlAdminHttpResource resource = new GraphqlAdminHttpResource();
        Map<String, Object> request = Map.of(
                "query",
                "{ __type(name: \"ManagedWorkspace\") { name fields { name } } }"
        );

        GraphqlAdminHttpResource.GraphqlAdminHttpResult disabled = resource.negotiatePost(
                request,
                MediaType.APPLICATION_JSON
        );
        GraphqlAdminHttpResource.GraphqlAdminHttpResult enabled = resource.negotiatePost(
                request,
                MediaType.APPLICATION_JSON,
                new GraphqlAdminHttpResource.GraphqlAdminHttpContext("", "operator", "request-9", "", true, "")
        );

        assertTrue(disabled.body().contains("introspection is disabled"));
        assertTrue(enabled.body().contains("\"name\":\"ManagedWorkspace\""));
        assertTrue(enabled.body().contains("\"name\":\"defaultEnvironment\""));
    }

    @Test
    void adminPostResourceIntrospectsManagementMutationSchema() {
        GraphqlAdminHttpResource resource = new GraphqlAdminHttpResource();
        GraphqlAdminHttpResource.GraphqlAdminHttpResult schema = resource.negotiatePost(
                Map.of("query", "{ __schema { mutationType { name } } }"),
                MediaType.APPLICATION_JSON,
                new GraphqlAdminHttpResource.GraphqlAdminHttpContext("", "operator", "request-schema", "", true, "")
        );
        GraphqlAdminHttpResource.GraphqlAdminHttpResult mutation = resource.negotiatePost(
                Map.of("query", "{ __type(name: \"Mutation\") { fields { name args { name type { kind name ofType { kind name } } } } } }"),
                MediaType.APPLICATION_JSON,
                new GraphqlAdminHttpResource.GraphqlAdminHttpContext("", "operator", "request-mutation", "", true, "")
        );
        GraphqlAdminHttpResource.GraphqlAdminHttpResult input = resource.negotiatePost(
                Map.of("query", "{ __type(name: \"ImportModelDocumentInput\") { inputFields { name type { kind name ofType { kind name } } } } }"),
                MediaType.APPLICATION_JSON,
                new GraphqlAdminHttpResource.GraphqlAdminHttpContext("", "operator", "request-input", "", true, "")
        );

        assertEquals("{\"data\":{\"__schema\":{\"mutationType\":{\"name\":\"Mutation\"}}}}", schema.body());
        assertTrue(mutation.body().contains("\"name\":\"importModelDocument\""));
        assertTrue(mutation.body().contains("\"name\":\"validateModelDraft\""));
        assertTrue(mutation.body().contains("\"name\":\"generateModelArtifacts\""));
        assertTrue(input.body().contains("\"name\":\"workspaceId\""));
        assertTrue(input.body().contains("\"name\":\"yaml\""));
    }

    @Test
    void adminGetResourceKeepsApplicationSchemaSeparate() {
        GraphqlAdminHttpResource.GraphqlAdminHttpResult admin = new GraphqlAdminHttpResource().negotiateGet(
                "{ __type(name: \"ManagedGraphqlModel\") { name fields { name } } }",
                null,
                null,
                null,
                MediaType.APPLICATION_JSON,
                new GraphqlAdminHttpResource.GraphqlAdminHttpContext("", "operator", "", "", true, "")
        );
        GraphqlHttpResource.GraphqlHttpResult application = new GraphqlHttpResource().negotiatePost(
                Map.of("query", "{ __type(name: \"ManagedGraphqlModel\") { name } }"),
                MediaType.APPLICATION_JSON,
                new GraphqlHttpResource.GraphqlHttpContext(10L, "reader", false, false, false, true, "")
        );

        assertTrue(admin.body().contains("\"name\":\"ManagedGraphqlModel\""));
        assertEquals("{\"data\":{\"__type\":null}}", application.body());
    }

    @Test
    void adminResourceRejectsManagementQueriesUntilStorageIsWired() {
        GraphqlAdminHttpResource.GraphqlAdminHttpResult response = new GraphqlAdminHttpResource().negotiatePost(
                Map.of("query", "{ workspace(id: 1) { id name } }"),
                MediaType.APPLICATION_JSON,
                new GraphqlAdminHttpResource.GraphqlAdminHttpContext("", "operator", "", "", true, "")
        );

        assertTrue(response.body().contains("management data queries are not implemented until management storage is wired"));
        assertErrorCode(response.body(), GraphqlException.UNSUPPORTED_OPERATION);
    }

    @Test
    void adminResourceValidatesManagementIntrospectionHeader() {
        GraphqlAdminHttpResource.GraphqlAdminHttpContext context =
                GraphqlAdminHttpResource.httpContext("", "operator", "request-9", "", "enabled");

        assertEquals("X-Titan-Management-Introspection must be true or false", context.validationError());
    }

    @Test
    void adminResourceImportsValidatesAndGeneratesModelDraftArtifacts() {
        GraphqlAdminHttpResource resource = new GraphqlAdminHttpResource();
        String yaml = readDemoBlogFixture();
        String draftId = draftId(yaml);

        Map<String, Object> importVariables = new LinkedHashMap<>();
        importVariables.put("yaml", yaml);
        GraphqlAdminHttpResource.GraphqlAdminHttpResult imported = resource.negotiatePost(
                Map.of(
                        "query", """
                                mutation Import($yaml: String!) {
                                  importModelDocument(input: { workspaceId: "workspace-001", yaml: $yaml }) {
                                    accepted
                                    draftId
                                    modelId
                                    errors
                                    warnings
                                  }
                                }
                                """,
                        "variables", importVariables
                ),
                MediaType.APPLICATION_JSON,
                new GraphqlAdminHttpResource.GraphqlAdminHttpContext(
                        "actor-operator",
                        "operator",
                        "request-import",
                        "idem-import",
                        true,
                        "")
        );

        assertTrue(imported.body().contains("\"accepted\":true"));
        assertTrue(imported.body().contains("\"draftId\":\"" + draftId + "\""));
        assertTrue(imported.body().contains("\"modelId\":\"model-demo-blog\""));
        assertTrue(imported.body().contains("\"errors\":0"));

        GraphqlAdminHttpResource.GraphqlAdminHttpResult validated = resource.negotiatePost(
                Map.of("query", """
                        mutation Validate {
                          validateModelDraft(input: { draftId: "%s" }) {
                            accepted
                            validationReportId
                            status
                            errors
                          }
                        }
                        """.formatted(draftId)),
                MediaType.APPLICATION_JSON,
                new GraphqlAdminHttpResource.GraphqlAdminHttpContext("", "admin", "request-validate", "", true, "")
        );

        assertTrue(validated.body().contains("\"accepted\":true"));
        assertTrue(validated.body().contains("\"validationReportId\":\"validation-" + draftId + "\""));
        assertTrue(validated.body().contains("\"status\":\"VALIDATED\""));

        GraphqlAdminHttpResource.GraphqlAdminHttpResult generated = resource.negotiatePost(
                Map.of("query", """
                        mutation Generate {
                          generateModelArtifacts(input: { draftId: "%s", generationProfile: "development", enableIntrospection: true }) {
                            accepted
                            artifactSetId
                            generatedArtifacts
                            sdlHash
                            introspectionHash
                          }
                        }
                        """.formatted(draftId)),
                MediaType.APPLICATION_JSON,
                new GraphqlAdminHttpResource.GraphqlAdminHttpContext("", "platform", "request-generate", "", true, "")
        );

        assertTrue(generated.body().contains("\"accepted\":true"));
        assertTrue(generated.body().contains("\"artifactSetId\":\"artifact-" + draftId + "\""));
        // SDL + introspection + conformance + the four real GAP-005 package metadata files
        // (the demo-blog model requests SQL artifacts; the metadataOnly placeholder is gone).
        assertTrue(generated.body().contains("\"generatedArtifacts\":7"));
        assertTrue(generated.body().contains("\"sdlHash\":\""));
        assertTrue(generated.body().contains("\"introspectionHash\":\""));
    }

    @Test
    void adminResourceReportsSemanticValidationFailureWithoutDeployingDraft() {
        GraphqlAdminHttpResource resource = new GraphqlAdminHttpResource();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("yaml", """
                apiVersion: titan.graphql/v1alpha1
                kind: ProjectionModel
                metadata:
                  name: invalid-model
                roots:
                  broken:
                    type: MissingType
                    operation: point
                    argument:
                      name: id
                      type: Int
                      kind: equals
                      column: id
                      path: id
                """);

        GraphqlAdminHttpResource.GraphqlAdminHttpResult imported = resource.negotiatePost(
                Map.of(
                        "query", """
                                mutation Import($yaml: String!) {
                                  importModelDocument(input: { workspaceId: "workspace-001", yaml: $yaml }) {
                                    accepted
                                    status
                                    errors
                                  }
                                }
                                """,
                        "variables", variables
                ),
                MediaType.APPLICATION_JSON,
                new GraphqlAdminHttpResource.GraphqlAdminHttpContext(
                        "actor-operator",
                        "operator",
                        "request-invalid",
                        "idem-invalid",
                        true,
                        "")
        );

        assertTrue(imported.body().contains("\"accepted\":false"));
        assertTrue(imported.body().contains("\"status\":\"FAILED_VALIDATION\""));
        assertTrue(imported.body().contains("\"errors\":"));
    }

    @Test
    void adminResourceRejectsUnauthorizedManagementMutation() {
        GraphqlAdminHttpResource.GraphqlAdminHttpResult response = new GraphqlAdminHttpResource().negotiatePost(
                Map.of("query", """
                        mutation Validate {
                          validateModelDraft(input: { draftId: "draft-missing" }) { accepted }
                        }
                        """),
                MediaType.APPLICATION_JSON,
                new GraphqlAdminHttpResource.GraphqlAdminHttpContext("", "viewer", "request-viewer", "", true, "")
        );

        assertTrue(response.body().contains("actor role is not authorized for mutation 'validateModelDraft'"));
        assertErrorCode(response.body(), GraphqlException.AUTHORIZATION_ERROR);
    }

    @Test
    void managementImportMutationRecordsAuditEvents() {
        GraphqlManagementMutationSupport support = new GraphqlManagementMutationSupport();
        GraphqlSchema schema = support.withManagementMutations(ProjectionGraphqlAdapter.adapt(
                TitanGraphqlManagementProjection.projectionModel().toProjectionModel()
        ));
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("yaml", readDemoBlogFixture());
        GraphqlAst.AstOperation operation = GraphqlParser.parseSelectedOperation(new GraphqlRequest(
                """
                mutation Import($yaml: String!) {
                  importModelDocument(input: { workspaceId: "workspace-001", yaml: $yaml }) {
                    accepted
                    draftId
                  }
                }
                """,
                "Import",
                variables,
                Map.of()
        ));

        GraphqlExecution execution = support.executeMutation(
                schema,
                operation,
                new GraphqlRequestContext(
                        0L,
                        "operator",
                        "actor-operator",
                        "",
                        "request-audit",
                        "idem-audit",
                        List.of("management"),
                        List.of(),
                        true,
                        false,
                        false,
                        0L)
        );

        assertTrue(execution.json().contains("\"accepted\":true"));
        assertEquals(2, support.auditLog().events().size());
        assertEquals(GraphqlMutationAuditEvent.MutationAuditStatus.ATTEMPT, support.auditLog().events().get(0).status());
        assertEquals(GraphqlMutationAuditEvent.MutationAuditStatus.SUCCESS, support.auditLog().events().get(1).status());
        assertEquals("request-audit", support.auditLog().events().get(1).requestId());
    }

    @Test
    void managementObservedOperationReviewMutationsUpdateStoreAndAuditEvents() {
        GraphqlManagementMutationSupport support = new GraphqlManagementMutationSupport();
        TitanGraphqlObservedOperation observed = TitanGraphqlObservedOperation.observed(
                "model-001",
                "prod",
                "viewer",
                "portal",
                "operation-sha",
                "ArticleById",
                "query ArticleById { article(id: 1) { id } }",
                2,
                3,
                List.of("Article.id"),
                "2026-06-01T19:42:00Z"
        );
        support.store().observeOperation(observed);
        GraphqlSchema schema = support.withManagementMutations(ProjectionGraphqlAdapter.adapt(
                TitanGraphqlManagementProjection.projectionModel().toProjectionModel()
        ));

        GraphqlExecution execution = support.executeMutation(
                schema,
                GraphqlParser.parseSelectedOperation(GraphqlRequest.of("""
                        mutation Approve {
                          approveObservedOperation(input: {
                            observedOperationId: "%s",
                            reviewedAt: "2026-06-01T20:00:00Z"
                          }) {
                            accepted
                            observedOperationId
                            operationRegistryId
                            status
                          }
                        }
                        """.formatted(observed.id()), "Approve")),
                new GraphqlRequestContext(
                        0L,
                        "operator",
                        "",
                        "",
                        "request-approve",
                        "",
                        List.of("management"),
                        List.of(),
                        true,
                        false,
                        false,
                        0L)
        );

        assertTrue(execution.json().contains("\"accepted\":true"));
        assertTrue(execution.json().contains("\"status\":\"APPROVED\""));
        assertEquals(TitanGraphqlObservedOperation.ObservedOperationStatus.APPROVED, support.store().observedOperation(observed.id()).status());
        TitanGraphqlOperationRegistry registry = support.store().operationRegistry(
                TitanGraphqlInMemoryManagementStore.operationRegistryId("model-001", "prod")
        );
        assertEquals(TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED, registry.operations().getFirst().status());
        assertEquals("operator", registry.operations().getFirst().approvedBy());
        assertEquals(2, support.auditLog().events().size());
        assertEquals(GraphqlMutationAuditEvent.MutationAuditStatus.SUCCESS, support.auditLog().events().get(1).status());
        assertEquals("request-approve", support.auditLog().events().get(1).requestId());

        GraphqlExecution rejected = support.executeMutation(
                schema,
                GraphqlParser.parseSelectedOperation(GraphqlRequest.of("""
                        mutation Reject {
                          rejectObservedOperation(input: { observedOperationId: "%s" }) {
                            accepted
                            status
                          }
                        }
                        """.formatted(observed.id()), "Reject")),
                new GraphqlRequestContext(
                        0L,
                        "operator",
                        "",
                        "",
                        "request-reject",
                        "",
                        List.of("management"),
                        List.of(),
                        true,
                        false,
                        false,
                        0L)
        );

        assertTrue(rejected.json().contains("\"status\":\"REJECTED\""));
        assertEquals(TitanGraphqlObservedOperation.ObservedOperationStatus.REJECTED, support.store().observedOperation(observed.id()).status());
    }

    @Test
    void quarkusPostResourceValidatesContextHeaders() {
        GraphqlHttpResource.GraphqlHttpContext actorId = GraphqlHttpResource.httpContext("abc", null, null, null, null);
        GraphqlHttpResource.GraphqlHttpContext visibility =
                GraphqlHttpResource.httpContext(null, null, "yes", null, null);
        GraphqlHttpResource.GraphqlHttpContext introspection =
                GraphqlHttpResource.httpContext(null, null, null, null, "enabled");
        GraphqlHttpResource.GraphqlHttpContext deadlineText =
                GraphqlHttpResource.httpContext(null, null, null, null, null, null, null, null, null, "soon");
        GraphqlHttpResource.GraphqlHttpContext deadlineNegative =
                GraphqlHttpResource.httpContext(null, null, null, null, null, null, null, null, null, "-1");

        assertEquals("X-Titan-Actor-Id must be an integer", actorId.validationError());
        assertEquals("X-Titan-Context-Filter-Published-Visibility must be true or false", visibility.validationError());
        assertEquals("X-Titan-Introspection must be true or false", introspection.validationError());
        assertEquals("X-Titan-Deadline-Budget-Millis must be a non-negative integer", deadlineText.validationError());
        assertEquals("X-Titan-Deadline-Budget-Millis must be a non-negative integer", deadlineNegative.validationError());
    }

    @Test
    void quarkusPostResourceBuildsContextFromHeaders() {
        GraphqlHttpResource.GraphqlHttpContext context =
                GraphqlHttpResource.httpContext(
                        "42",
                        "admin",
                        "true",
                        "false",
                        "true",
                        "tenant-a",
                        "request-7",
                        "can-preview, trace",
                        "publishedVisibility, tenantIsolation",
                        "2500"
                );

        assertEquals(42L, context.actorId());
        assertEquals("admin", context.actorRole());
        assertTrue(context.enablePublishedVisibility());
        assertTrue(context.hasArticleVisibility());
        assertEquals(false, context.articleVisibility());
        assertTrue(context.enableIntrospection());
        assertEquals("tenant-a", context.tenantId());
        assertEquals("request-7", context.requestId());
        assertEquals("can-preview, trace", context.policyFlags());
        assertEquals("publishedVisibility, tenantIsolation", context.enabledContextFilters());
        assertEquals(2500L, context.deadlineBudgetMillis());
    }

    @Test
    void quarkusPostResourceActivatesContextFilterFromCompactContextNames() {
        GraphqlHttpResource.GraphqlHttpResult response = new GraphqlHttpResource().negotiatePost(
                Map.of("query", "{ articles(first: 2) { edges { node { id title } } totalCount } }"),
                MediaType.APPLICATION_JSON,
                GraphqlHttpResource.httpContext(
                        "10",
                        "reader",
                        null,
                        "true",
                        null,
                        "tenant-a",
                        "request-7",
                        "can-preview",
                        "tenantIsolation, publishedVisibility",
                        "2500"
                )
        );

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}}],\"totalCount\":1}}}",
                response.body()
        );
    }

    @Test
    void publicStoredFunctionAcceptsCompactContextFields() {
        String json = TitanGraphqlFunctions.executeGraphqlRequestWithCompactContext(
                "{ articles(first: 2) { edges { node { id title } } totalCount } }",
                "",
                "",
                "",
                10L,
                "reader",
                false,
                true,
                true,
                false,
                "tenant-a",
                "request-7",
                "can-preview",
                "tenantIsolation, publishedVisibility",
                2500L
        );

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}}],\"totalCount\":1}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionExecutesSqlKernelForArticleAuthorQuery() {
        String json = TitanGraphqlFunctions.executeGraphql("""
                query {
                  article(id: 1) {
                    id
                    title
                    author {
                      id
                      name
                    }
                  }
                }
                """, 10L, "reader");

        assertEquals(
                "{\"data\":{\"article\":{\"id\":1,\"title\":\"Titan GraphQL proof\",\"author\":{\"id\":10,\"name\":\"Ada Lovelace\"}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionMatchesJavaComputedArticleField() {
        String query = "{ article(id: 1) { title titleLength } }";
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(query, 10L, "reader");
        String json = TitanGraphqlFunctions.executeGraphql(query, 10L, "reader");

        assertEquals("{\"data\":{\"article\":{\"title\":\"Titan GraphQL proof\",\"titleLength\":19}}}", json);
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionRejectsUnauthorizedEmail() {
        String json = TitanGraphqlFunctions.executeGraphql("""
                query {
                  article(id: 1) {
                    author {
                      email
                    }
                  }
                }
                """, 10L, "reader");

        assertTrue(json.contains("User.email"));
        assertTrue(json.contains("not authorized"));
        assertErrorCode(json, GraphqlException.AUTHORIZATION_ERROR);
    }

    @Test
    void javaModeSerializesParseErrorEnvelopeWithLocationAndCode() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("""
                query {
                  article(id: 1) {
                    title @
                  }
                }
                """, 10L, "reader");

        assertEquals(0, execution.plan().readStepCount());
        assertTrue(execution.json().contains("\"locations\":["));
        assertErrorCode(execution.json(), GraphqlException.PARSE_ERROR);
    }

    @Test
    void javaModeSerializesValidationErrorEnvelopeWithCode() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("{ viewer { id } }", 10L, "reader");

        assertEquals(0, execution.plan().readStepCount());
        assertTrue(execution.json().contains("unsupported root field"));
        assertErrorCode(execution.json(), GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void javaModeSerializesAuthorizationErrorEnvelopeWithCode() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("""
                query {
                  article(id: 1) {
                    author {
                      email
                    }
                  }
                }
                """, 10L, "reader");

        assertEquals(0, execution.plan().readStepCount());
        assertTrue(execution.json().contains("not authorized"));
        assertErrorCode(execution.json(), GraphqlException.AUTHORIZATION_ERROR);
    }

    @Test
    void javaModeSerializesUnsupportedOperationErrorEnvelopeWithCode() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(
                "mutation Change { article(id: 1) { id } }",
                10L,
                "reader");

        assertEquals(0, execution.plan().readStepCount());
        assertTrue(execution.json().contains("operation type 'mutation' is not supported"));
        assertErrorCode(execution.json(), GraphqlException.UNSUPPORTED_OPERATION);
    }

    @Test
    void javaModeRejectsDisabledSchemaIntrospectionWithGraphqlError() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(
                "{ __schema { queryType { name } } }",
                10L,
                "reader");

        assertEquals(0, execution.plan().readStepCount());
        assertTrue(execution.json().contains("introspection is disabled for root field '__schema'"));
        assertErrorCode(execution.json(), GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void javaModeRejectsDisabledTypeIntrospectionWithGraphqlError() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(
                "{ __type(name: \"Article\") { name } }",
                10L,
                "reader");

        assertEquals(0, execution.plan().readStepCount());
        assertTrue(execution.json().contains("introspection is disabled for root field '__type'"));
        assertErrorCode(execution.json(), GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void publicStoredFunctionRejectsDisabledIntrospectionLikeJavaMode() {
        String schemaQuery = "{ __schema { queryType { name } } }";
        String typeQuery = "{ __type(name: \"Article\") { name } }";

        assertEquals(
                TitanGraphqlFunctions.executeGraphqlWithPlan(schemaQuery, 10L, "reader").json(),
                TitanGraphqlFunctions.executeGraphql(schemaQuery, 10L, "reader")
        );
        assertEquals(
                TitanGraphqlFunctions.executeGraphqlWithPlan(typeQuery, 10L, "reader").json(),
                TitanGraphqlFunctions.executeGraphql(typeQuery, 10L, "reader")
        );
    }

    @Test
    void javaModeRendersEnabledSchemaIntrospectionSmokeSubset() {
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query("{ __schema { queryType { name } types { name kind } } }"),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));

        assertEquals(0, execution.plan().readStepCount());
        assertEquals(
                "{\"data\":{\"__schema\":{\"queryType\":{\"name\":\"Query\"},\"types\":["
                        + "{\"name\":\"Query\",\"kind\":\"OBJECT\"},"
                        + "{\"name\":\"Article\",\"kind\":\"OBJECT\"},"
                        + "{\"name\":\"User\",\"kind\":\"OBJECT\"},"
                        + "{\"name\":\"Comment\",\"kind\":\"OBJECT\"},"
                        + "{\"name\":\"Boolean\",\"kind\":\"SCALAR\"},"
                        + "{\"name\":\"Int\",\"kind\":\"SCALAR\"},"
                        + "{\"name\":\"String\",\"kind\":\"SCALAR\"},"
                        + "{\"name\":\"ArticleConnection\",\"kind\":\"OBJECT\"},"
                        + "{\"name\":\"ArticleEdge\",\"kind\":\"OBJECT\"},"
                        + "{\"name\":\"CommentConnection\",\"kind\":\"OBJECT\"},"
                        + "{\"name\":\"CommentEdge\",\"kind\":\"OBJECT\"},"
                        + "{\"name\":\"PageInfo\",\"kind\":\"OBJECT\"},"
                        + "{\"name\":\"IntFilter\",\"kind\":\"INPUT_OBJECT\"},"
                        + "{\"name\":\"StringFilter\",\"kind\":\"INPUT_OBJECT\"},"
                        + "{\"name\":\"ArticleFilter\",\"kind\":\"INPUT_OBJECT\"},"
                        + "{\"name\":\"SortDirection\",\"kind\":\"ENUM\"},"
                        + "{\"name\":\"ArticleOrderBy\",\"kind\":\"INPUT_OBJECT\"}"
                        + "]}}}",
                execution.json());
    }

    @Test
    void javaModeRendersEnabledTypeIntrospectionSmokeSubset() {
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query("{ __type(name: \"Article\") { name kind fields { name type { name kind } } } }"),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));

        assertEquals(0, execution.plan().readStepCount());
        assertEquals(
                "{\"data\":{\"__type\":{\"name\":\"Article\",\"kind\":\"OBJECT\",\"fields\":["
                        + "{\"name\":\"id\",\"type\":{\"name\":\"Int\",\"kind\":\"SCALAR\"}},"
                        + "{\"name\":\"title\",\"type\":{\"name\":\"String\",\"kind\":\"SCALAR\"}},"
                        + "{\"name\":\"titleLength\",\"type\":{\"name\":\"Int\",\"kind\":\"SCALAR\"}},"
                        + "{\"name\":\"author\",\"type\":{\"name\":null,\"kind\":\"NON_NULL\"}},"
                        + "{\"name\":\"comments\",\"type\":{\"name\":null,\"kind\":\"NON_NULL\"}}"
                        + "]}}}",
                execution.json());
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaSchemaSmokeSubset() {
        String query = "{ __schema { queryType { name } types { name kind } } }";
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaSchemaNullableMetadataSubset() {
        String query = """
                { __schema {
                    description
                    mutationType { name }
                    subscriptionType { name }
                  }
                }
                """;
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertEquals(
                "{\"data\":{\"__schema\":{\"description\":null,\"mutationType\":null,\"subscriptionType\":null}}}",
                execution.json());
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaDirectiveMetadataSubset() {
        String query = """
                { __schema {
                    directives {
                      name
                      description
                      isRepeatable
                      locations
                      args {
                        name
                        type { name kind ofType { name kind } }
                      }
                    }
                  }
                }
                """;
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertTrue(execution.json().contains(
                "{\"name\":\"include\",\"description\":null,\"isRepeatable\":false,"
                        + "\"locations\":[\"FIELD\",\"FRAGMENT_SPREAD\",\"INLINE_FRAGMENT\"],"
                        + "\"args\":[{\"name\":\"if\",\"type\":{\"name\":null,\"kind\":\"NON_NULL\","
                        + "\"ofType\":{\"name\":\"Boolean\",\"kind\":\"SCALAR\"}}}]}"));
        assertTrue(execution.json().contains(
                "{\"name\":\"relationSortPath\",\"description\":null,\"isRepeatable\":true,"
                        + "\"locations\":[\"FIELD_DEFINITION\"],\"args\":["
                        + "{\"name\":\"name\",\"type\":{\"name\":null,\"kind\":\"NON_NULL\","
                        + "\"ofType\":{\"name\":\"String\",\"kind\":\"SCALAR\"}}}"));
        assertTrue(execution.json().contains(
                "{\"name\":\"hops\",\"type\":{\"name\":null,\"kind\":\"NON_NULL\","
                        + "\"ofType\":{\"name\":\"Int\",\"kind\":\"SCALAR\"}}}"));
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaTypeSmokeSubset() {
        String query = "{ __type(name: \"Article\") { name kind fields { name type { name kind } } } }";
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaFieldArgsSubset() {
        String query = "{ __type(name: \"Query\") { fields { name args { name type { name kind } } } } }";
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertEquals(
                "{\"data\":{\"__type\":{\"fields\":["
                        + "{\"name\":\"article\",\"args\":[{\"name\":\"id\",\"type\":{\"name\":null,\"kind\":\"NON_NULL\"}}]},"
                        + "{\"name\":\"articles\",\"args\":["
                        + "{\"name\":\"first\",\"type\":{\"name\":\"Int\",\"kind\":\"SCALAR\"}},"
                        + "{\"name\":\"after\",\"type\":{\"name\":\"String\",\"kind\":\"SCALAR\"}},"
                        + "{\"name\":\"last\",\"type\":{\"name\":\"Int\",\"kind\":\"SCALAR\"}},"
                        + "{\"name\":\"before\",\"type\":{\"name\":\"String\",\"kind\":\"SCALAR\"}},"
                        + "{\"name\":\"authorId\",\"type\":{\"name\":\"Int\",\"kind\":\"SCALAR\"}},"
                        + "{\"name\":\"filter\",\"type\":{\"name\":\"ArticleFilter\",\"kind\":\"INPUT_OBJECT\"}},"
                        + "{\"name\":\"orderBy\",\"type\":{\"name\":null,\"kind\":\"LIST\"}}"
                        + "]}]}}}",
                execution.json());
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaTypeWrapperSubset() {
        String query = """
                { __type(name: "Query") {
                    fields {
                      name
                      type { name kind ofType { name kind } }
                      args { name type { name kind ofType { name kind ofType { name kind } } } }
                    }
                  }
                }
                """;
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertTrue(execution.json().contains(
                "{\"name\":\"articles\",\"type\":{\"name\":null,\"kind\":\"NON_NULL\","
                        + "\"ofType\":{\"name\":\"ArticleConnection\",\"kind\":\"OBJECT\"}}"));
        assertTrue(execution.json().contains(
                "{\"name\":\"id\",\"type\":{\"name\":null,\"kind\":\"NON_NULL\","
                        + "\"ofType\":{\"name\":\"Int\",\"kind\":\"SCALAR\",\"ofType\":null}}}"));
        assertTrue(execution.json().contains(
                "{\"name\":\"orderBy\",\"type\":{\"name\":null,\"kind\":\"LIST\","
                        + "\"ofType\":{\"name\":null,\"kind\":\"NON_NULL\","
                        + "\"ofType\":{\"name\":\"ArticleOrderBy\",\"kind\":\"INPUT_OBJECT\"}}}}"));
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaRelationTypeWrapperSubset() {
        String query = """
                { __type(name: "Article") {
                    fields {
                      name
                      type { name kind ofType { name kind } }
                    }
                  }
                }
                """;
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertTrue(execution.json().contains(
                "{\"name\":\"author\",\"type\":{\"name\":null,\"kind\":\"NON_NULL\","
                        + "\"ofType\":{\"name\":\"User\",\"kind\":\"OBJECT\"}}}"));
        assertTrue(execution.json().contains(
                "{\"name\":\"comments\",\"type\":{\"name\":null,\"kind\":\"NON_NULL\","
                        + "\"ofType\":{\"name\":\"CommentConnection\",\"kind\":\"OBJECT\"}}}"));
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaRelationFieldArgsSubset() {
        String query = "{ __type(name: \"Article\") { fields { name args { name type { name kind } } } } }";
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertTrue(execution.json().contains(
                "{\"name\":\"comments\",\"args\":["
                        + "{\"name\":\"first\",\"type\":{\"name\":\"Int\",\"kind\":\"SCALAR\"}},"
                        + "{\"name\":\"after\",\"type\":{\"name\":\"String\",\"kind\":\"SCALAR\"}},"
                        + "{\"name\":\"last\",\"type\":{\"name\":\"Int\",\"kind\":\"SCALAR\"}},"
                        + "{\"name\":\"before\",\"type\":{\"name\":\"String\",\"kind\":\"SCALAR\"}}"
                        + "]}"));
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaInputFieldSubset() {
        String query = """
                { __type(name: "ArticleFilter") {
                    name
                    kind
                    inputFields {
                      name
                      type { name kind ofType { name kind ofType { name kind } } }
                    }
                  }
                }
                """;
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertTrue(execution.json().contains("\"name\":\"ArticleFilter\",\"kind\":\"INPUT_OBJECT\""));
        assertTrue(execution.json().contains(
                "{\"name\":\"title\",\"type\":{\"name\":\"StringFilter\",\"kind\":\"INPUT_OBJECT\",\"ofType\":null}}"));
        assertTrue(execution.json().contains(
                "{\"name\":\"titleLength\",\"type\":{\"name\":\"IntFilter\",\"kind\":\"INPUT_OBJECT\",\"ofType\":null}}"));
        assertTrue(execution.json().contains(
                "{\"name\":\"and\",\"type\":{\"name\":null,\"kind\":\"LIST\","
                        + "\"ofType\":{\"name\":null,\"kind\":\"NON_NULL\","
                        + "\"ofType\":{\"name\":\"ArticleFilter\",\"kind\":\"INPUT_OBJECT\"}}}}"));
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaScalarInputFieldSubset() {
        String query = """
                { __type(name: "IntFilter") {
                    inputFields {
                      name
                      type { name kind ofType { name kind } }
                    }
                  }
                }
                """;
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertTrue(execution.json().contains(
                "{\"name\":\"in\",\"type\":{\"name\":null,\"kind\":\"LIST\","
                        + "\"ofType\":{\"name\":null,\"kind\":\"NON_NULL\"}}}"));
        assertTrue(execution.json().contains("{\"name\":\"lt\",\"type\":{\"name\":\"Int\",\"kind\":\"SCALAR\""));
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaOrderInputFieldSubset() {
        String query = """
                { __type(name: "ArticleOrderBy") {
                    inputFields {
                      name
                      type { name kind }
                    }
                  }
                }
                """;
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertEquals(
                "{\"data\":{\"__type\":{\"inputFields\":["
                        + "{\"name\":\"id\",\"type\":{\"name\":\"SortDirection\",\"kind\":\"ENUM\"}},"
                        + "{\"name\":\"title\",\"type\":{\"name\":\"SortDirection\",\"kind\":\"ENUM\"}},"
                        + "{\"name\":\"titleLength\",\"type\":{\"name\":\"SortDirection\",\"kind\":\"ENUM\"}},"
                        + "{\"name\":\"authorName\",\"type\":{\"name\":\"SortDirection\",\"kind\":\"ENUM\"}}"
                        + "]}}}",
                execution.json());
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaEnumValueSubset() {
        String query = """
                { __type(name: "SortDirection") {
                    name
                    kind
                    enumValues {
                      name
                    }
                  }
                }
                """;
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertEquals(
                "{\"data\":{\"__type\":{\"name\":\"SortDirection\",\"kind\":\"ENUM\","
                        + "\"enumValues\":[{\"name\":\"ASC\"},{\"name\":\"DESC\"}]}}}",
                execution.json());
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaFieldsIncludeDeprecatedSubset() {
        String query = """
                { __type(name: "Article") {
                    fields(includeDeprecated: true) {
                      name
                      type { name kind }
                    }
                  }
                }
                """;
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertTrue(execution.json().contains("{\"name\":\"title\",\"type\":{\"name\":\"String\",\"kind\":\"SCALAR\"}}"));
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaEnumValuesIncludeDeprecatedSubset() {
        String query = """
                { __type(name: "SortDirection") {
                    enumValues(includeDeprecated: false) {
                      name
                    }
                  }
                }
                """;
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertEquals(
                "{\"data\":{\"__type\":{\"enumValues\":[{\"name\":\"ASC\"},{\"name\":\"DESC\"}]}}}",
                execution.json());
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaInputValueDefaultValueSubset() {
        String query = """
                { __type(name: "Query") {
                    fields {
                      name
                      args {
                        name
                        defaultValue
                        type { name kind }
                      }
                    }
                  }
                }
                """;
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertTrue(execution.json().contains(
                "{\"name\":\"id\",\"defaultValue\":null,\"type\":{\"name\":null,\"kind\":\"NON_NULL\"}}"));
        assertTrue(execution.json().contains(
                "{\"name\":\"filter\",\"defaultValue\":null,\"type\":{\"name\":\"ArticleFilter\",\"kind\":\"INPUT_OBJECT\"}}"));
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaInputFieldDefaultValueSubset() {
        String query = """
                { __type(name: "ArticleFilter") {
                    inputFields {
                      name
                      defaultValue
                      type { name kind }
                    }
                  }
                }
                """;
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertTrue(execution.json().contains(
                "{\"name\":\"title\",\"defaultValue\":null,\"type\":{\"name\":\"StringFilter\",\"kind\":\"INPUT_OBJECT\"}}"));
        assertTrue(execution.json().contains(
                "{\"name\":\"authorName\",\"defaultValue\":null,\"type\":{\"name\":\"StringFilter\",\"kind\":\"INPUT_OBJECT\"}}"));
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaInputFieldsIncludeDeprecatedSubset() {
        String query = """
                { __type(name: "ArticleOrderBy") {
                    inputFields(includeDeprecated: false) {
                      name
                      defaultValue
                      type { name kind }
                    }
                  }
                }
                """;
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertEquals(
                "{\"data\":{\"__type\":{\"inputFields\":["
                        + "{\"name\":\"id\",\"defaultValue\":null,\"type\":{\"name\":\"SortDirection\",\"kind\":\"ENUM\"}},"
                        + "{\"name\":\"title\",\"defaultValue\":null,\"type\":{\"name\":\"SortDirection\",\"kind\":\"ENUM\"}},"
                        + "{\"name\":\"titleLength\",\"defaultValue\":null,\"type\":{\"name\":\"SortDirection\",\"kind\":\"ENUM\"}},"
                        + "{\"name\":\"authorName\",\"defaultValue\":null,\"type\":{\"name\":\"SortDirection\",\"kind\":\"ENUM\"}}]}}}",
                execution.json());
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaDescriptionSubset() {
        String query = """
                { __type(name: "SortDirection") {
                    name
                    description
                    enumValues {
                      name
                      description
                    }
                  }
                }
                """;
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertEquals(
                "{\"data\":{\"__type\":{\"name\":\"SortDirection\",\"description\":null,"
                        + "\"enumValues\":[{\"name\":\"ASC\",\"description\":null},"
                        + "{\"name\":\"DESC\",\"description\":null}]}}}",
                execution.json());
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaNestedDescriptionSubset() {
        String query = """
                { __type(name: "Query") {
                    fields {
                      name
                      description
                      type { name kind description }
                      args {
                        name
                        description
                        type { name kind description }
                      }
                    }
                  }
                }
                """;
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertTrue(execution.json().contains(
                "{\"name\":\"article\",\"description\":null,\"type\":{\"name\":\"Article\","
                        + "\"kind\":\"OBJECT\",\"description\":null}"));
        assertTrue(execution.json().contains(
                "{\"name\":\"id\",\"description\":null,\"type\":{\"name\":null,"
                        + "\"kind\":\"NON_NULL\",\"description\":null}}"));
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaFieldDeprecationSubset() {
        String query = """
                { __type(name: "Article") {
                    fields(includeDeprecated: true) {
                      name
                      isDeprecated
                      deprecationReason
                    }
                  }
                }
                """;
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertTrue(execution.json().contains(
                "{\"name\":\"title\",\"isDeprecated\":false,\"deprecationReason\":null}"));
        assertTrue(execution.json().contains(
                "{\"name\":\"comments\",\"isDeprecated\":false,\"deprecationReason\":null}"));
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaEnumDeprecationSubset() {
        String query = """
                { __type(name: "SortDirection") {
                    enumValues(includeDeprecated: true) {
                      name
                      isDeprecated
                      deprecationReason
                    }
                  }
                }
                """;
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertEquals(
                "{\"data\":{\"__type\":{\"enumValues\":["
                        + "{\"name\":\"ASC\",\"isDeprecated\":false,\"deprecationReason\":null},"
                        + "{\"name\":\"DESC\",\"isDeprecated\":false,\"deprecationReason\":null}]}}}",
                execution.json());
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaInputValueDeprecationSubset() {
        String query = """
                { __type(name: "Query") {
                    fields {
                      name
                      args {
                        name
                        isDeprecated
                        deprecationReason
                      }
                    }
                  }
                }
                """;
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertTrue(execution.json().contains(
                "{\"name\":\"id\",\"isDeprecated\":false,\"deprecationReason\":null}"));
        assertTrue(execution.json().contains(
                "{\"name\":\"orderBy\",\"isDeprecated\":false,\"deprecationReason\":null}"));
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionMatchesJavaInputFieldDeprecationSubset() {
        String query = """
                { __type(name: "ArticleFilter") {
                    inputFields(includeDeprecated: true) {
                      name
                      isDeprecated
                      deprecationReason
                    }
                  }
                }
                """;
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.introspectionEnabled(10L, "reader"));
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(query, 10L, "reader", true);

        assertTrue(execution.json().contains(
                "{\"name\":\"title\",\"isDeprecated\":false,\"deprecationReason\":null}"));
        assertTrue(execution.json().contains(
                "{\"name\":\"authorName\",\"isDeprecated\":false,\"deprecationReason\":null}"));
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithIntrospectionRejectsUnsupportedBroadIntrospectionFields() {
        String json = TitanGraphqlFunctions.executeGraphqlWithIntrospection(
                "{ __type(name: \"Article\") { name fields(filterDeprecated: true) { name } } }",
                10L,
                "reader",
                true);

        assertTrue(json.contains("field '__Type.fields' supports only argument 'includeDeprecated'"));
        assertErrorCode(json, GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void javaModeExecutesQueryWithVariablesFromRequestEnvelope() {
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.of("""
                        query ArticleById($id: Int!) {
                          article(id: $id) {
                            title
                          }
                        }
                        """, "ArticleById", Map.of("id", 2)),
                "reader"
        );

        assertEquals(1, execution.plan().readStepCount());
        assertEquals("{\"data\":{\"article\":{\"title\":\"Stored functions as APIs\"}}}", execution.json());
    }

    @Test
    void javaModeAppliesPublishedVisibilityContextFilterBeforeCountsAndCursors() {
        String cursor = articleCursor(1);
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query("{ articles(first: 2) { edges { cursor node { id title } } totalCount pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }"),
                GraphqlRequestContext.articleVisibility(10L, "reader", true)
        );

        assertEquals(1, execution.plan().readStepCount());
        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"cursor\":\"" + cursor
                        + "\",\"node\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}}],\"totalCount\":1,\"pageInfo\":{\"hasNextPage\":false,\"hasPreviousPage\":false,\"startCursor\":\""
                        + cursor + "\",\"endCursor\":\"" + cursor + "\"}}}}",
                execution.json()
        );
    }

    @Test
    void javaModeComposesPublishedVisibilityContextFilterWithClientFilters() {
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query("{ articles(first: 2, filter: {authorId: {eq: 11}}) { edges { node { id title } } totalCount } }"),
                GraphqlRequestContext.articleVisibility(10L, "reader", true)
        );

        assertEquals("{\"data\":{\"articles\":{\"edges\":[],\"totalCount\":0}}}", execution.json());
    }

    @Test
    void javaModeFailsClosedWhenEnabledContextFilterKeyIsMissing() {
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query("{ articles(first: 2) { edges { node { id } } totalCount } }"),
                GraphqlRequestContext.missingArticleVisibility(10L, "reader")
        );

        assertEquals("{\"data\":{\"articles\":{\"edges\":[],\"totalCount\":0}}}", execution.json());
    }

    @Test
    void javaModeKeepsContextFiltersOutOfClientArguments() {
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query("{ articles(first: 2, articleVisibility: true) { edges { node { id } } } }"),
                GraphqlRequestContext.articleVisibility(10L, "reader", true)
        );

        assertTrue(execution.json().contains("unsupported argument on articles"));
        assertTrue(execution.json().contains("articleVisibility") == false);
    }

    @Test
    void publicStoredFunctionWithContextMatchesJavaPublishedVisibilityRowsAndCounts() {
        String query = "{ articles(first: 2) { edges { node { id title } } totalCount } }";
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.articleVisibility(10L, "reader", true)
        );
        String json = TitanGraphqlFunctions.executeGraphqlWithContext(query, 10L, "reader", true, true, true);

        assertEquals("{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}}],\"totalCount\":1}}}", json);
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithContextComposesVisibilityWithClientFilters() {
        String query = "{ articles(first: 2, filter: {authorId: {eq: 11}}) { edges { node { id title } } totalCount } }";
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.articleVisibility(10L, "reader", true)
        );
        String json = TitanGraphqlFunctions.executeGraphqlWithContext(query, 10L, "reader", true, true, true);

        assertEquals("{\"data\":{\"articles\":{\"edges\":[],\"totalCount\":0}}}", json);
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithContextFailsClosedWhenVisibilityKeyIsMissing() {
        String query = "{ articles(first: 2) { edges { node { id } } totalCount } }";
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(query),
                GraphqlRequestContext.missingArticleVisibility(10L, "reader")
        );
        String json = TitanGraphqlFunctions.executeGraphqlWithContext(query, 10L, "reader", true, false, false);

        assertEquals("{\"data\":{\"articles\":{\"edges\":[],\"totalCount\":0}}}", json);
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionWithContextMatchesJavaPublishedVisibilityCursorWindow() {
        String javaQuery = "{ articles(first: 2, after: \"" + articleCursor(1L)
                + "\") { edges { node { id } } totalCount pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }";
        String sqlQuery = "{ articles(first: 2, after: \"article:1\") { edges { node { id } } totalCount pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }";
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.query(javaQuery),
                GraphqlRequestContext.articleVisibility(10L, "reader", true)
        );
        String json = TitanGraphqlFunctions.executeGraphqlWithContext(sqlQuery, 10L, "reader", true, true, true);

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[],\"totalCount\":1,\"pageInfo\":{\"hasNextPage\":false,\"hasPreviousPage\":true,\"startCursor\":null,\"endCursor\":null}}}}",
                json
        );
        assertEquals(execution.json(), json);
    }

    @Test
    void yamlBackedDemoBlogSchemaMatchesJavaQueryContractSmoke() {
        assertYamlBackedResponseMatchesJava("""
                {
                  article(id: 1) {
                    id
                    title
                    titleLength
                    author {
                      id
                      name
                    }
                  }
                }
                """, GraphqlRequestContext.legacy(10L, "reader"));
        assertYamlBackedResponseMatchesJava("""
                {
                  articles(first: 2) {
                    edges {
                      cursor
                      node {
                        id
                        title
                      }
                    }
                    totalCount
                    pageInfo {
                      hasNextPage
                      hasPreviousPage
                      startCursor
                      endCursor
                    }
                  }
                }
                """, GraphqlRequestContext.legacy(10L, "reader"));
        assertYamlBackedResponseMatchesJava("""
                {
                  articles(
                    first: 2,
                    filter: {title: {contains: "GraphQL"}},
                    orderBy: [{titleLength: DESC}]
                  ) {
                    edges {
                      node {
                        id
                        titleLength
                      }
                    }
                    totalCount
                  }
                }
                """, GraphqlRequestContext.legacy(10L, "reader"));
        assertYamlBackedResponseMatchesJava("""
                {
                  article(id: 1) {
                    comments(first: 2) {
                      edges {
                        node {
                          id
                          body
                          author {
                            name
                          }
                        }
                      }
                      totalCount
                    }
                  }
                }
                """, GraphqlRequestContext.legacy(10L, "reader"));
        assertYamlBackedResponseMatchesJava("""
                {
                  articles(first: 2) {
                    edges {
                      node {
                        id
                      }
                    }
                    totalCount
                  }
                }
                """, GraphqlRequestContext.missingArticleVisibility(10L, "reader"));
        assertYamlBackedResponseMatchesJava("""
                {
                  __type(name: "Article") {
                    name
                    kind
                    fields {
                      name
                      type {
                        name
                        kind
                        ofType {
                          name
                          kind
                        }
                      }
                    }
                  }
                }
                """, GraphqlRequestContext.introspectionEnabled(10L, "reader"));
    }

    @Test
    void javaModeRendersRootScalarAndRelationAliases() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("""
                query {
                  post: article(id: 1) {
                    headline: title
                    writer: author {
                      displayName: name
                    }
                  }
                }
                """, 10L, "reader");

        assertEquals(2, execution.plan().readStepCount());
        assertEquals(
                "{\"data\":{\"post\":{\"headline\":\"Titan GraphQL proof\",\"writer\":{\"displayName\":\"Ada Lovelace\"}}}}",
                execution.json()
        );
    }

    @Test
    void javaModeRejectsConflictingResponseKeys() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("""
                query {
                  article(id: 1) {
                    title
                    title: id
                  }
                }
                """, 10L, "reader");

        assertEquals(0, execution.plan().readStepCount());
        assertTrue(execution.json().contains("conflicting response key 'title'"));
        assertErrorCode(execution.json(), GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void javaModeMergesIdenticalDuplicateResponseKeys() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("""
                query {
                  article(id: 1) {
                    title
                    title
                    writer: author {
                      name
                    }
                    writer: author {
                      name
                    }
                  }
                }
                """, 10L, "reader");

        assertEquals(2, execution.plan().readStepCount());
        assertEquals(
                "{\"data\":{\"article\":{\"title\":\"Titan GraphQL proof\",\"writer\":{\"name\":\"Ada Lovelace\"}}}}",
                execution.json()
        );
    }

    @Test
    void javaModeRendersObjectTypenameMetaFields() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("""
                query {
                  article(id: 1) {
                    __typename
                    kind: __typename
                    title
                    author {
                      __typename
                    }
                    comments(first: 1) {
                      edges {
                        node {
                          __typename
                          body
                        }
                      }
                    }
                  }
                }
                """, 10L, "reader");

        assertEquals(3, execution.plan().readStepCount());
        assertEquals(
                "{\"data\":{\"article\":{\"__typename\":\"Article\",\"kind\":\"Article\",\"title\":\"Titan GraphQL proof\",\"author\":{\"__typename\":\"User\"},\"comments\":{\"edges\":[{\"node\":{\"__typename\":\"Comment\",\"body\":\"First comment\"}}]}}}}",
                execution.json()
        );
    }

    @Test
    void javaModeExpandsNamedFragments() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("""
                query ArticleWithFragments {
                  article(id: 1) {
                    ...ArticleFields
                    author {
                      ...UserFields
                    }
                  }
                }

                fragment ArticleFields on Article {
                  headline: title
                }

                fragment UserFields on User {
                  displayName: name
                }
                """, 10L, "reader");

        assertEquals(2, execution.plan().readStepCount());
        assertEquals(
                "{\"data\":{\"article\":{\"headline\":\"Titan GraphQL proof\",\"author\":{\"displayName\":\"Ada Lovelace\"}}}}",
                execution.json()
        );
    }

    @Test
    void javaModeExpandsInlineFragments() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("""
                query {
                  article(id: 1) {
                    ... on Article {
                      headline: title
                    }
                    author {
                      ... on User {
                        displayName: name
                      }
                    }
                  }
                }
                """, 10L, "reader");

        assertEquals(2, execution.plan().readStepCount());
        assertEquals(
                "{\"data\":{\"article\":{\"headline\":\"Titan GraphQL proof\",\"author\":{\"displayName\":\"Ada Lovelace\"}}}}",
                execution.json()
        );
    }

    @Test
    void javaModeCoercesVariablesUsedInsideFragments() {
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.of("""
                        query ArticleWithFragmentVariable($commentFirst: Int!) {
                          article(id: 1) {
                            ...CommentFields
                          }
                        }

                        fragment CommentFields on Article {
                          comments(first: $commentFirst) {
                            edges {
                              node {
                                body
                              }
                            }
                          }
                        }
                        """, "ArticleWithFragmentVariable", Map.of("commentFirst", 1)),
                "reader"
        );

        assertEquals(2, execution.plan().readStepCount());
        assertEquals(
                "{\"data\":{\"article\":{\"comments\":{\"edges\":[{\"node\":{\"body\":\"First comment\"}}]}}}}",
                execution.json()
        );
    }

    @Test
    void javaModeRejectsFragmentCycles() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("""
                query {
                  article(id: 1) {
                    ...ArticleFields
                  }
                }

                fragment ArticleFields on Article {
                  ...MoreArticleFields
                }

                fragment MoreArticleFields on Article {
                  ...ArticleFields
                }
                """, 10L, "reader");

        assertEquals(0, execution.plan().readStepCount());
        assertTrue(execution.json().contains("fragment cycle detected"));
        assertErrorCode(execution.json(), GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void javaModeRejectsUnusedFragments() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("""
                query {
                  article(id: 1) {
                    title
                  }
                }

                fragment UnusedFields on Article {
                  id
                }
                """, 10L, "reader");

        assertEquals(0, execution.plan().readStepCount());
        assertTrue(execution.json().contains("fragment 'UnusedFields' is never used"));
        assertErrorCode(execution.json(), GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void javaModeRejectsUnknownFragmentTypeConditions() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("""
                query {
                  article(id: 1) {
                    ...UnknownFields
                  }
                }

                fragment UnknownFields on UnknownType {
                  id
                }
                """, 10L, "reader");

        assertEquals(0, execution.plan().readStepCount());
        assertTrue(execution.json().contains("fragment type condition 'UnknownType' is not defined"));
        assertErrorCode(execution.json(), GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void javaModeAppliesFieldIncludeAndSkipDirectives() {
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.of("""
                        query ArticleWithDirectives($showTitle: Boolean!, $skipAuthor: Boolean!) {
                          article(id: 1) {
                            title @include(if: $showTitle)
                            author @skip(if: $skipAuthor) {
                              name
                            }
                          }
                        }
                        """, "ArticleWithDirectives", Map.of(
                        "showTitle", true,
                        "skipAuthor", true
                )),
                "reader"
        );

        assertEquals(1, execution.plan().readStepCount());
        assertEquals(
                "{\"data\":{\"article\":{\"title\":\"Titan GraphQL proof\"}}}",
                execution.json()
        );
    }

    @Test
    void javaModeAppliesFragmentDirectivesBeforeValidation() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("""
                query {
                  article(id: 1) {
                    title
                    ...MissingFields @skip(if: true)
                    ... on Article @include(if: false) {
                      missingField
                    }
                  }
                }
                """, 10L, "reader");

        assertEquals(1, execution.plan().readStepCount());
        assertEquals(
                "{\"data\":{\"article\":{\"title\":\"Titan GraphQL proof\"}}}",
                execution.json()
        );
    }

    @Test
    void javaModeRejectsUnknownRuntimeDirectives() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("""
                query {
                  article(id: 1) {
                    title @defer(if: true)
                  }
                }
                """, 10L, "reader");

        assertEquals(0, execution.plan().readStepCount());
        assertTrue(execution.json().contains("unsupported directive '@defer'"));
        assertErrorCode(execution.json(), GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void javaModeRejectsNonBooleanDirectiveArguments() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("""
                query {
                  article(id: 1) {
                    title @include(if: "yes")
                  }
                }
                """, 10L, "reader");

        assertEquals(0, execution.plan().readStepCount());
        assertTrue(execution.json().contains("directive '@include' argument 'if' must be Boolean"));
        assertErrorCode(execution.json(), GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void javaModeSerializesVariableValidationErrorsWithCode() {
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.of("""
                        query ArticleById($id: Int!) {
                          article(id: $id) {
                            title
                          }
                        }
                        """, "ArticleById", Map.of()),
                "reader"
        );

        assertEquals(0, execution.plan().readStepCount());
        assertTrue(execution.json().contains("required variable '$id' is missing"));
        assertErrorCode(execution.json(), GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void publicStoredFunctionSerializesUnsupportedOperationCode() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "mutation Change { article(id:1){id} }",
                10L,
                "reader");

        assertErrorCode(json, GraphqlException.UNSUPPORTED_OPERATION);
    }

    @Test
    void publicStoredFunctionAppliesLiteralFieldDirectives() {
        String json = TitanGraphqlFunctions.executeGraphql("""
                query {
                  article(id: 1) {
                    id @include(if: false)
                    kind: __typename @skip(if: false)
                    title @include(if: true)
                    author @skip(if: true) {
                      missingField
                    }
                  }
                }
                """, 10L, "reader");

        assertEquals(
                "{\"data\":{\"article\":{\"kind\":\"Article\",\"title\":\"Titan GraphQL proof\"}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionAppliesLiteralInlineFragmentDirectives() {
        String json = TitanGraphqlFunctions.executeGraphql("""
                query {
                  article(id: 1) {
                    title
                    ... on Article @skip(if: true) {
                      missingField
                    }
                    ... on Article @include(if: true) {
                      author {
                        name
                      }
                    }
                  }
                }
                """, 10L, "reader");

        assertEquals(
                "{\"data\":{\"article\":{\"title\":\"Titan GraphQL proof\",\"author\":{\"name\":\"Ada Lovelace\"}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionRejectsVariableBackedSqlModeDirectives() {
        String json = TitanGraphqlFunctions.executeGraphql("""
                query {
                  article(id: 1) {
                    title @skip(if: $skipTitle)
                  }
                }
                """, 10L, "reader");

        assertTrue(json.contains("directive '@skip' argument 'if' must be a Boolean literal in SQL mode"));
        assertErrorCode(json, GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void publicStoredFunctionAllowsAdminEmailWithoutImplicitAuthorId() {
        String json = TitanGraphqlFunctions.executeGraphql("""
                query {
                  article(id: 1) {
                    author {
                      email
                    }
                  }
                }
                """, 99L, "admin");

        assertEquals(
                "{\"data\":{\"article\":{\"author\":{\"email\":\"ada@example.test\"}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionHandlesCompactQueryWithoutImplicitIds() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){title author{name}}}",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"article\":{\"title\":\"Titan GraphQL proof\",\"author\":{\"name\":\"Ada Lovelace\"}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionDoesNotTreatSelectedIdAsMissingArgument() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article{author{id}}}",
                10L,
                "reader");

        assertTrue(json.contains("required argument 'id' is missing"));
    }

    @Test
    void publicStoredFunctionRejectsCommentsWithoutSelectionSet() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){id comments}}",
                10L,
                "reader");

        assertTrue(json.contains("field 'comments' requires a selection set"));
    }

    @Test
    void publicStoredFunctionRejectsUnsupportedArticleField() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){slug}}",
                10L,
                "reader");

        assertTrue(json.contains("unsupported Article field 'slug'"));
    }

    @Test
    void publicStoredFunctionRejectsUnsupportedAuthorField() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){author{id handle}}}",
                10L,
                "reader");

        assertTrue(json.contains("unsupported User field 'handle'"));
    }

    @Test
    void publicStoredFunctionRejectsAuthorWithoutSelectionSet() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){author}}",
                10L,
                "reader");

        assertTrue(json.contains("field 'author' requires a selection set"));
    }

    @Test
    void publicStoredFunctionRejectsArticleScalarSelectionSet() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){title{value}}}",
                10L,
                "reader");

        assertTrue(json.contains("scalar field 'title' cannot have a selection set"));
    }

    @Test
    void publicStoredFunctionRejectsAuthorScalarSelectionSet() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){author{name{first}}}}",
                10L,
                "reader");

        assertTrue(json.contains("scalar field 'name' cannot have a selection set"));
    }

    @Test
    void publicStoredFunctionExecutesArticleCommentsRelation() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){title comments(first:2){edges{node{id body}}}}}",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"article\":{\"title\":\"Titan GraphQL proof\",\"comments\":{\"edges\":[{\"node\":{\"id\":100,\"body\":\"First comment\"}},{\"node\":{\"id\":101,\"body\":\"Second comment\"}}]}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionDoesNotTreatCommentIdAsArticleId() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){comments{edges{node{id}}}}}",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"article\":{\"comments\":{\"edges\":[{\"node\":{\"id\":100}},{\"node\":{\"id\":101}}]}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionRejectsUnsupportedCommentField() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){comments{edges{node{handle}}}}}",
                10L,
                "reader");

        assertTrue(json.contains("unsupported Comment field 'handle'"));
    }

    @Test
    void publicStoredFunctionRejectsLegacyCommentsNodeSelection() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){comments{id body}}}",
                10L,
                "reader");

        assertTrue(json.contains("relation field 'comments' returns a Relay connection"));
    }

    @Test
    void publicStoredFunctionExecutesCommentConnectionPageInfo() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){comments(first:1){edges{cursor node{id}} totalCount pageInfo{hasNextPage hasPreviousPage startCursor endCursor}}}}",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"article\":{\"comments\":{\"edges\":[{\"cursor\":\"comment:100\",\"node\":{\"id\":100}}],\"totalCount\":2,\"pageInfo\":{\"hasNextPage\":true,\"hasPreviousPage\":false,\"startCursor\":\"comment:100\",\"endCursor\":\"comment:100\"}}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionExecutesCommentConnectionAfterCursor() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){comments(first:1, after:\"comment:100\"){edges{cursor node{id}} pageInfo{hasNextPage hasPreviousPage startCursor endCursor}}}}",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"article\":{\"comments\":{\"edges\":[{\"cursor\":\"comment:101\",\"node\":{\"id\":101}}],\"pageInfo\":{\"hasNextPage\":false,\"hasPreviousPage\":true,\"startCursor\":\"comment:101\",\"endCursor\":\"comment:101\"}}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionExecutesBackwardCommentConnectionPage() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){comments(last:1, before:\"comment:101\"){edges{cursor node{id}} pageInfo{hasNextPage hasPreviousPage startCursor endCursor}}}}",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"article\":{\"comments\":{\"edges\":[{\"cursor\":\"comment:100\",\"node\":{\"id\":100}}],\"pageInfo\":{\"hasNextPage\":true,\"hasPreviousPage\":false,\"startCursor\":\"comment:100\",\"endCursor\":\"comment:100\"}}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionRejectsInvalidCommentCursor() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){comments(after:\"article:100\"){edges{node{id}}}}}",
                10L,
                "reader");

        assertTrue(json.contains("argument 'after' must be a comment cursor"));
    }

    @Test
    void publicStoredFunctionExecutesNestedCommentAuthorRelation() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){comments{edges{node{author{name}}}}}}",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"article\":{\"comments\":{\"edges\":[{\"node\":{\"author\":{\"name\":\"Grace Hopper\"}}},{\"node\":{\"author\":{\"name\":\"Ada Lovelace\"}}}]}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionRejectsUnauthorizedNestedCommentAuthorEmail() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){comments{edges{node{author{email}}}}}}",
                10L,
                "reader");

        assertTrue(json.contains("User.email"));
        assertTrue(json.contains("not authorized"));
    }

    @Test
    void publicStoredFunctionAllowsNestedCommentAuthorEmailForAdmin() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){comments{edges{node{body author{email}}}}}}",
                99L,
                "admin");

        assertEquals(
                "{\"data\":{\"article\":{\"comments\":{\"edges\":[{\"node\":{\"body\":\"First comment\",\"author\":{\"email\":\"grace@example.test\"}}},{\"node\":{\"body\":\"Second comment\",\"author\":{\"email\":\"ada@example.test\"}}}]}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionRejectsCommentScalarSelectionSet() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){comments{edges{node{body{value}}}}}}",
                10L,
                "reader");

        assertTrue(json.contains("scalar field 'body' cannot have a selection set"));
    }

    @Test
    void publicStoredFunctionRejectsMultipleRootFields() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){id} article(id:2){title}}",
                10L,
                "reader");

        assertTrue(json.contains("exactly one root field is supported"));
    }

    @Test
    void publicStoredFunctionRejectsUnsupportedRootArgument() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1, slug:\"proof\"){id}}",
                10L,
                "reader");

        assertTrue(json.contains("unsupported argument on article; only 'id' is allowed"));
    }

    @Test
    void publicStoredFunctionRejectsArticleFieldArguments() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){title(format:\"plain\")}}",
                10L,
                "reader");

        assertTrue(json.contains("Article.title does not accept arguments"));
    }

    @Test
    void publicStoredFunctionRejectsAuthorRelationArguments() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){author(role:\"primary\"){id}}}",
                10L,
                "reader");

        assertTrue(json.contains("Article.author does not accept arguments"));
    }

    @Test
    void publicStoredFunctionRejectsAuthorFieldArguments() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){author{id(format:\"raw\")}}}",
                10L,
                "reader");

        assertTrue(json.contains("User.id does not accept arguments"));
    }

    @Test
    void publicStoredFunctionRejectsMutationOperation() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "mutation { article(id:1){id} }",
                10L,
                "reader");

        assertTrue(json.contains("operation type 'mutation' is not supported"));
    }

    @Test
    void publicStoredFunctionRejectsSubscriptionOperation() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "subscription { article(id:1){id} }",
                10L,
                "reader");

        assertTrue(json.contains("operation type 'subscription' is not supported"));
    }

    @Test
    void publicStoredFunctionAcceptsNamedQueryOperation() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "query FetchArticle { article(id:1){id} }",
                10L,
                "reader");

        assertTrue(json.contains("\"article\":{\"id\":1}"));
    }

    @Test
    void publicStoredFunctionRejectsMultiOperationDocument() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "query First { article(id:1){id} } query Second { article(id:2){id} }",
                10L,
                "reader");

        assertTrue(json.contains("multiple operations require operationName"));
    }

    @Test
    void publicStoredFunctionRequestSelectsNamedQueryOperation() {
        String query = """
                query First { article(id:1){id title} }
                query Second { article(id:2){id title} }
                """;

        String json = TitanGraphqlFunctions.executeGraphqlRequest(query, "Second", 10L, "reader");

        assertEquals(
                "{\"data\":{\"article\":{\"id\":2,\"title\":\"Stored functions as APIs\"}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionRequestSelectsNamedOperationWithFragments() {
        String query = """
                query First { article(id:1){id} }
                query Second { article(id:2){...ArticleFields} }
                fragment ArticleFields on Article { id title }
                """;

        String json = TitanGraphqlFunctions.executeGraphqlRequest(query, "Second", 10L, "reader");

        assertEquals(
                "{\"data\":{\"article\":{\"id\":2,\"title\":\"Stored functions as APIs\"}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionRequestRejectsUnknownOperationName() {
        String json = TitanGraphqlFunctions.executeGraphqlRequest(
                "query First { article(id:1){id} }",
                "Second",
                10L,
                "reader");

        assertTrue(json.contains("operationName 'Second' was not found"));
        assertErrorCode(json, GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void publicStoredFunctionRequestRejectsAmbiguousOperationName() {
        String json = TitanGraphqlFunctions.executeGraphqlRequest(
                "query First { article(id:1){id} } query First { article(id:2){id} }",
                "First",
                10L,
                "reader");

        assertTrue(json.contains("operationName 'First' is ambiguous"));
        assertErrorCode(json, GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void publicStoredFunctionRequestRejectsSelectedUnsupportedOperation() {
        String json = TitanGraphqlFunctions.executeGraphqlRequest(
                "query First { article(id:1){id} } mutation Change { article(id:2){id} }",
                "Change",
                10L,
                "reader");

        assertTrue(json.contains("operation type 'mutation' is not supported"));
        assertErrorCode(json, GraphqlException.UNSUPPORTED_OPERATION);
    }

    @Test
    void publicStoredFunctionRequestLowersScalarVariables() {
        String query = "query Fetch($first: Int!) { articles(first: $first) { edges { node { id title } } } }";
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.of(query, "Fetch", Map.of("first", 1)),
                "reader");

        String json = TitanGraphqlFunctions.executeGraphqlRequestWithVariables(
                query,
                "Fetch",
                "{\"first\":1}",
                "{}",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}}]}}}",
                json
        );
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionRequestUsesVariableDefaults() {
        String query = "query Fetch($first: Int! = 1) { articles(first: $first) { edges { node { id title } } } }";
        GraphqlExecution execution = executeRequest(GraphqlRequest.of(query, "Fetch"), "reader");

        String json = TitanGraphqlFunctions.executeGraphqlRequestWithVariables(
                query,
                "Fetch",
                "{}",
                "{}",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}}]}}}",
                json
        );
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionRequestLowersBooleanDirectiveVariables() {
        String query = "query Fetch($withTitle: Boolean!) { article(id: 1) { id title @include(if: $withTitle) } }";
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.of(query, "Fetch", Map.of("withTitle", false)),
                "reader");

        String json = TitanGraphqlFunctions.executeGraphqlRequestWithVariables(
                query,
                "Fetch",
                "{\"withTitle\":false}",
                "{}",
                10L,
                "reader");

        assertEquals("{\"data\":{\"article\":{\"id\":1}}}", json);
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionRequestLowersStructuredFilterVariables() {
        String query = """
                query Fetch($filter: ArticleFilter!) {
                  articles(filter: $filter, first: 2) {
                    edges { node { id title } }
                    totalCount
                  }
                }
                """;
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("authorName", Map.of("startsWith", "Grace"));
        filter.put("not", Map.of("title", Map.of("contains", "GraphQL")));
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.of(query, "Fetch", Map.of("filter", filter)),
                "reader");

        String json = TitanGraphqlFunctions.executeGraphqlRequestWithVariables(
                query,
                "Fetch",
                "{\"filter\":{\"authorName\":{\"startsWith\":\"Grace\"},\"not\":{\"title\":{\"contains\":\"GraphQL\"}}}}",
                "{}",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":2,\"title\":\"Stored functions as APIs\"}}],\"totalCount\":1}}}",
                json
        );
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionRequestLowersStructuredOrderVariables() {
        String query = """
                query Fetch($orderBy: [ArticleOrderBy!]!) {
                  articles(orderBy: $orderBy, first: 2) {
                    edges { node { id titleLength } }
                  }
                }
                """;
        Map<String, Object> firstOrder = new LinkedHashMap<>();
        firstOrder.put("titleLength", "DESC");
        Map<String, Object> secondOrder = new LinkedHashMap<>();
        secondOrder.put("id", "ASC");
        GraphqlExecution execution = executeRequest(
                GraphqlRequest.of(query, "Fetch", Map.of("orderBy", List.of(firstOrder, secondOrder))),
                "reader");

        String json = TitanGraphqlFunctions.executeGraphqlRequestWithVariables(
                query,
                "Fetch",
                "{\"orderBy\":[{\"titleLength\":\"DESC\"},{\"id\":\"ASC\"}]}",
                "{}",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":2,\"titleLength\":24}},{\"node\":{\"id\":1,\"titleLength\":19}}]}}}",
                json
        );
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionRequestRejectsMalformedStructuredVariable() {
        String json = TitanGraphqlFunctions.executeGraphqlRequestWithVariables(
                "query Fetch($filter: ArticleFilter!) { articles(filter: $filter, first: 2) { edges { node { id } } } }",
                "Fetch",
                "{\"filter\":[]}",
                "{}",
                10L,
                "reader");

        assertTrue(json.contains("variable '$filter' expected ArticleFilter JSON object"));
        assertErrorCode(json, GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void publicStoredFunctionRequestRejectsMalformedVariableEnvelope() {
        String json = TitanGraphqlFunctions.executeGraphqlRequestWithVariables(
                "query Fetch($id: ID!) { article(id: $id) { id } }",
                "Fetch",
                "[]",
                "{}",
                10L,
                "reader");

        assertTrue(json.contains("request field 'variables' must be a JSON object"));
        assertErrorCode(json, GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void publicStoredFunctionRequestRejectsMalformedExtensionsEnvelope() {
        String json = TitanGraphqlFunctions.executeGraphqlRequestWithVariables(
                "query Fetch($id: ID!) { article(id: $id) { id } }",
                "Fetch",
                "{\"id\":1}",
                "[]",
                10L,
                "reader");

        assertTrue(json.contains("request field 'extensions' must be a JSON object"));
        assertErrorCode(json, GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void publicStoredFunctionRequestRejectsUnknownVariable() {
        String json = TitanGraphqlFunctions.executeGraphqlRequestWithVariables(
                "query Fetch($id: ID!) { article(id: $id) { id } }",
                "Fetch",
                "{\"id\":1,\"extra\":2}",
                "{}",
                10L,
                "reader");

        assertTrue(json.contains("variable '$extra' is not declared by the selected operation"));
        assertErrorCode(json, GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void publicStoredFunctionRequestRejectsMissingRequiredVariable() {
        String json = TitanGraphqlFunctions.executeGraphqlRequestWithVariables(
                "query Fetch($id: ID!) { article(id: $id) { id } }",
                "Fetch",
                "{}",
                "{}",
                10L,
                "reader");

        assertTrue(json.contains("required variable '$id' is missing"));
        assertErrorCode(json, GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void publicStoredFunctionRequestRejectsUnusedVariableDefinition() {
        String json = TitanGraphqlFunctions.executeGraphqlRequestWithVariables(
                "query Fetch($id: ID!, $unused: Int) { article(id: $id) { id } }",
                "Fetch",
                "{\"id\":1}",
                "{}",
                10L,
                "reader");

        assertTrue(json.contains("variable '$unused' is never used"));
        assertErrorCode(json, GraphqlException.VALIDATION_ERROR);
    }

    @Test
    void publicStoredFunctionRejectsTrailingInput() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){id}} trailing",
                10L,
                "reader");

        assertTrue(json.contains("unexpected trailing GraphQL input"));
    }

    @Test
    void publicStoredFunctionRejectsEmptySelectionSet() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{}",
                10L,
                "reader");

        assertTrue(json.contains("selection set must contain at least one field"));
    }

    @Test
    void publicStoredFunctionRendersRootAndNestedAliases() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{post: article(id:1){headline: title writer: author { displayName: name }}}",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"post\":{\"headline\":\"Titan GraphQL proof\",\"writer\":{\"displayName\":\"Ada Lovelace\"}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionRendersTypenameMetaFields() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){__typename kind: __typename title author { __typename } comments(first:1) { edges { node { __typename body } } }}}",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"article\":{\"__typename\":\"Article\",\"kind\":\"Article\",\"title\":\"Titan GraphQL proof\",\"author\":{\"__typename\":\"User\"},\"comments\":{\"edges\":[{\"node\":{\"__typename\":\"Comment\",\"body\":\"First comment\"}}]}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionRendersOnlyAliasedTypenameOnce() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){kind: __typename author { roleType: __typename } comments(first:1) { edges { node { commentType: __typename } } }}}",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"article\":{\"kind\":\"Article\",\"author\":{\"roleType\":\"User\"},\"comments\":{\"edges\":[{\"node\":{\"commentType\":\"Comment\"}}]}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionRendersConnectionAliases() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ feed: articles(first: 1) { rows: edges { mark: cursor item: node { headline: title } } info: pageInfo { more: hasNextPage start: startCursor } } }",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"feed\":{\"rows\":[{\"mark\":\"article:1\",\"item\":{\"headline\":\"Titan GraphQL proof\"}}],\"info\":{\"more\":true,\"start\":\"article:1\"}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionRejectsConflictingAliasResponseKeys() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ article(id: 1) { title title: id } }",
                10L,
                "reader");

        assertTrue(json.contains("conflicting response key 'title'"));
    }

    @Test
    void publicStoredFunctionRejectsConflictingDuplicateArguments() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ article(id: 1) { comments(first: 1) { edges { node { body } } } comments(first: 2) { edges { node { body } } } } }",
                10L,
                "reader");

        assertTrue(json.contains("conflicting response key 'comments'"));
    }

    @Test
    void publicStoredFunctionAllowsIdenticalDuplicateResponseKeys() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ article(id: 1) { title title } }",
                10L,
                "reader");

        assertEquals("{\"data\":{\"article\":{\"title\":\"Titan GraphQL proof\"}}}", json);
    }

    @Test
    void publicStoredFunctionExpandsNamedFragments() {
        String json = TitanGraphqlFunctions.executeGraphql(
                """
                query {
                  article(id:1) {
                    ...ArticleFields
                    author {
                      ...UserFields
                    }
                  }
                }
                fragment ArticleFields on Article {
                  title
                }
                fragment UserFields on User {
                  name
                }
                """,
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"article\":{\"title\":\"Titan GraphQL proof\",\"author\":{\"name\":\"Ada Lovelace\"}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionExpandsNamedFragmentsInConnectionNodes() {
        String json = TitanGraphqlFunctions.executeGraphql(
                """
                query {
                  articles(first: 1) {
                    edges {
                      node {
                        ...ArticleFields
                      }
                    }
                  }
                }
                fragment ArticleFields on Article {
                  title
                  comments(first: 1) {
                    edges {
                      node {
                        ...CommentFields
                      }
                    }
                  }
                }
                fragment CommentFields on Comment {
                  body
                }
                """,
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"title\":\"Titan GraphQL proof\",\"comments\":{\"edges\":[{\"node\":{\"body\":\"First comment\"}}]}}}]}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionRejectsUnknownNamedFragment() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{article(id:1){...ArticleFields}}",
                10L,
                "reader");

        assertTrue(json.contains("unknown fragment 'ArticleFields'"));
    }

    @Test
    void publicStoredFunctionRejectsUnusedNamedFragment() {
        String json = TitanGraphqlFunctions.executeGraphql(
                """
                {article(id:1){title}}
                fragment ArticleFields on Article {
                  id
                }
                """,
                10L,
                "reader");

        assertTrue(json.contains("fragment 'ArticleFields' is never used"));
    }

    @Test
    void publicStoredFunctionRejectsCyclicNamedFragments() {
        String json = TitanGraphqlFunctions.executeGraphql(
                """
                {article(id:1){...ArticleFields}}
                fragment ArticleFields on Article {
                  ...MoreArticleFields
                }
                fragment MoreArticleFields on Article {
                  ...ArticleFields
                }
                """,
                10L,
                "reader");

        assertTrue(json.contains("fragment cycle detected"));
    }

    @Test
    void publicStoredFunctionRejectsUnknownFragmentTypeCondition() {
        String json = TitanGraphqlFunctions.executeGraphql(
                """
                {article(id:1){...ArticleFields}}
                fragment ArticleFields on UnknownType {
                  id
                }
                """,
                10L,
                "reader");

        assertTrue(json.contains("fragment type condition 'UnknownType' is not defined"));
    }

    @Test
    void publicStoredFunctionExpandsInlineFragments() {
        String json = TitanGraphqlFunctions.executeGraphql("""
                query {
                  article(id: 1) {
                    ... on Article {
                      headline: title
                      author {
                        ... on User {
                          displayName: name
                        }
                      }
                      comments(first: 1) {
                        edges {
                          node {
                            ... on Comment {
                              body
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """, 10L, "reader");

        assertEquals(
                "{\"data\":{\"article\":{\"headline\":\"Titan GraphQL proof\",\"author\":{\"displayName\":\"Ada Lovelace\"},\"comments\":{\"edges\":[{\"node\":{\"body\":\"First comment\"}}]}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionSkipsNonMatchingInlineFragments() {
        String json = TitanGraphqlFunctions.executeGraphql("""
                query {
                  article(id: 1) {
                    title
                    author {
                      ... on Article {
                        missingArticleField
                      }
                      ... on User {
                        name
                      }
                    }
                  }
                }
                """, 10L, "reader");

        assertEquals(
                "{\"data\":{\"article\":{\"title\":\"Titan GraphQL proof\",\"author\":{\"name\":\"Ada Lovelace\"}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionRejectsUnknownInlineFragmentTypeCondition() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ article(id: 1) { ... on MissingType { title } } }",
                10L,
                "reader");

        assertTrue(json.contains("fragment type condition 'MissingType' is not defined"));
    }

    @Test
    void publicStoredFunctionExecutesArticlesListRoot() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ articles(first: 2) { edges { cursor node { id title } } } }",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"cursor\":\"article:1\",\"node\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}},{\"cursor\":\"article:2\",\"node\":{\"id\":2,\"title\":\"Stored functions as APIs\"}}]}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionExecutesArticlesListRootWithAuthors() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ articles(first: 2) { edges { node { title author { name } } } } }",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"title\":\"Titan GraphQL proof\",\"author\":{\"name\":\"Ada Lovelace\"}}},{\"node\":{\"title\":\"Stored functions as APIs\",\"author\":{\"name\":\"Grace Hopper\"}}}]}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionExecutesArticlesListRootWithComments() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ articles(first: 2) { edges { node { id comments(first: 2) { edges { node { body } } } } } } }",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1,\"comments\":{\"edges\":[{\"node\":{\"body\":\"First comment\"}},{\"node\":{\"body\":\"Second comment\"}}]}}},{\"node\":{\"id\":2,\"comments\":{\"edges\":[{\"node\":{\"body\":\"API comment\"}}]}}}]}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionExecutesArticlesListRootWithCommentCursors() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ articles(first: 2) { edges { node { id comments(after:\"comment:100\") { edges { node { id } } pageInfo { hasPreviousPage startCursor endCursor } } } } } }",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1,\"comments\":{\"edges\":[{\"node\":{\"id\":101}}],\"pageInfo\":{\"hasPreviousPage\":true,\"startCursor\":\"comment:101\",\"endCursor\":\"comment:101\"}}}},{\"node\":{\"id\":2,\"comments\":{\"edges\":[{\"node\":{\"id\":102}}],\"pageInfo\":{\"hasPreviousPage\":false,\"startCursor\":\"comment:102\",\"endCursor\":\"comment:102\"}}}}]}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionExecutesArticlesConnectionPageInfo() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ articles(first: 1) { edges { cursor node { id } } totalCount pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"cursor\":\"article:1\",\"node\":{\"id\":1}}],\"totalCount\":2,\"pageInfo\":{\"hasNextPage\":true,\"hasPreviousPage\":false,\"startCursor\":\"article:1\",\"endCursor\":\"article:1\"}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionExecutesArticlesConnectionAfterCursor() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ articles(first: 1, after: \"article:1\") { edges { node { id } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":2}}],\"pageInfo\":{\"hasNextPage\":false,\"hasPreviousPage\":true,\"startCursor\":\"article:2\",\"endCursor\":\"article:2\"}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionExecutesArticlesConnectionBackwardCursor() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ articles(last: 1, before: \"article:2\") { edges { cursor node { id } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"cursor\":\"article:1\",\"node\":{\"id\":1}}],\"pageInfo\":{\"hasNextPage\":true,\"hasPreviousPage\":false,\"startCursor\":\"article:1\",\"endCursor\":\"article:1\"}}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionRejectsUnauthorizedArticlesAuthorEmail() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ articles(first: 2) { edges { node { author { email } } } } }",
                10L,
                "reader");

        assertTrue(json.contains("User.email"));
        assertTrue(json.contains("not authorized"));
    }

    @Test
    void publicStoredFunctionUsesDefaultArticlesLimit() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ articles { edges { node { id } } } }",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1}},{\"node\":{\"id\":2}}]}}}",
                json
        );
    }

    @Test
    void publicStoredFunctionAllowsZeroArticlesPageSize() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ articles(first: 0) { edges { node { id } } } }",
                10L,
                "reader");

        assertEquals("{\"data\":{\"articles\":{\"edges\":[]}}}", json);
    }

    @Test
    void publicStoredFunctionRejectsUnsupportedArticlesArgument() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ articles(id: 1) { edges { node { id } } } }",
                10L,
                "reader");

        assertTrue(json.contains("unsupported argument on articles; supported arguments are 'first', 'after', 'last', 'before', 'authorId', 'filter', 'orderBy'"));
    }

    @Test
    void publicStoredFunctionRejectsLegacyArticlesLimitArgument() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ articles(limit: 1) { edges { node { id } } } }",
                10L,
                "reader");

        assertTrue(json.contains("unsupported argument on articles; supported arguments are 'first', 'after', 'last', 'before', 'authorId', 'filter', 'orderBy'"));
    }

    @Test
    void executesFirstArticleAuthorQueryAndRecordsDslPlan() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("""
                query {
                  article(id: 1) {
                    id
                    title
                    author {
                      id
                      name
                    }
                  }
                }
                """, 10L, "reader");

        assertEquals(
                "{\"data\":{\"article\":{\"id\":1,\"title\":\"Titan GraphQL proof\",\"author\":{\"id\":10,\"name\":\"Ada Lovelace\"}}}}",
                execution.json()
        );
        assertEquals(2, execution.plan().readStepCount());
        assertTrue(execution.plan().readSteps().get(0).sql().contains("FROM public.articles"));
        assertTrue(execution.plan().readSteps().get(1).sql().contains("FROM public.users"));
    }

    @Test
    void rejectsUserEmailForNonAdminBeforeExecutionPlanning() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("""
                query {
                  article(id: 1) {
                    author {
                      email
                    }
                  }
                }
                """, 10L, "reader");

        assertEquals(0, execution.plan().readStepCount());
        assertTrue(execution.json().contains("User.email"));
        assertTrue(execution.json().contains("not authorized"));
    }

    @Test
    void allowsUserEmailForAdmin() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("""
                query {
                  article(id: 1) {
                    author {
                      id
                      email
                    }
                  }
                }
                """, 99L, "admin");

        assertEquals(
                "{\"data\":{\"article\":{\"author\":{\"id\":10,\"email\":\"ada@example.test\"}}}}",
                execution.json()
        );
        assertEquals(2, execution.plan().readStepCount());
    }

    @Test
    void returnsExplicitErrorForUnsupportedRoot() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("{ viewer { id } }", 10L, "reader");

        assertEquals(0, execution.plan().readStepCount());
        assertTrue(execution.json().contains("unsupported root field"));
    }

    @Test
    void missingArticleReturnsNullAndStillShowsPointLookupPlan() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("{ article(id: 404) { id title } }", 10L, "reader");

        assertEquals("{\"data\":{\"article\":null}}", execution.json());
        assertEquals(1, execution.plan().readStepCount());
    }

    @Test
    void genericEngineExecutesArticlesListRoot() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(
                "{ articles(first: 2) { edges { node { id title } } } }",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}},{\"node\":{\"id\":2,\"title\":\"Stored functions as APIs\"}}]}}}",
                execution.json()
        );
        assertEquals(1, execution.plan().readStepCount());
        assertEquals("articles", execution.plan().readSteps().getFirst().name());
    }

    @Test
    void genericEngineUsesDefaultArticlesLimit() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(
                "{ articles { edges { node { id } } } }",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1}},{\"node\":{\"id\":2}}]}}}",
                execution.json()
        );
        assertEquals(1, execution.plan().readStepCount());
    }

    @Test
    void genericEngineAllowsZeroArticlesPageSize() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(
                "{ articles(first: 0) { edges { node { id } } } }",
                10L,
                "reader");

        assertEquals("{\"data\":{\"articles\":{\"edges\":[]}}}", execution.json());
        assertEquals(1, execution.plan().readStepCount());
    }

    @Test
    void genericEngineExecutesArticlesListRootWithAuthorFilter() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(
                "{ articles(authorId: 10, first: 2) { edges { node { id title } } } }",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}}]}}}",
                execution.json()
        );
        assertEquals(1, execution.plan().readStepCount());
        assertTrue(execution.plan().readSteps().getFirst().sql().contains("WHERE author_id = 10"));
    }

    @Test
    void genericEngineExecutesGeneratedRootFilter() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(
                "{ articles(filter: { title: { eq: \"Titan GraphQL proof\" } }, first: 2) { edges { node { id title } } } }",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}}]}}}",
                execution.json()
        );
        assertEquals(1, execution.plan().readStepCount());
    }

    @Test
    void genericEngineExecutesGeneratedRootFilterCompositionAndOrder() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan("""
                {
                  articles(
                    filter: {
                      or: [
                        { authorId: { eq: 10 } }
                        { title: { in: ["Stored functions as APIs"] } }
                      ]
                      authorId: { neq: 999 }
                      not: { id: { eq: 404 } }
                      id: { isNull: false }
                    }
                    orderBy: [{ title: ASC }]
                    first: 2
                  ) {
                    edges { node { id title } }
                  }
                }
                """, 10L, "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":2,\"title\":\"Stored functions as APIs\"}},{\"node\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}}]}}}",
                execution.json()
        );
        assertEquals(1, execution.plan().readStepCount());
    }

    @Test
    void publicStoredFunctionMatchesJavaGeneratedRootFilter() {
        String query = "{ articles(filter: { title: { eq: \"Titan GraphQL proof\" } }, first: 2) { edges { node { id title } } } }";
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(query, 10L, "reader");
        String json = TitanGraphqlFunctions.executeGraphql(query, 10L, "reader");

        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionMatchesJavaGeneratedRootFilterCompositionAndOrder() {
        String query = """
                {
                  articles(
                    filter: {
                      or: [
                        { authorId: { eq: 10 } }
                        { title: { in: ["Stored functions as APIs"] } }
                      ]
                      authorId: { neq: 999 }
                      not: { id: { eq: 404 } }
                      id: { isNull: false }
                    }
                    orderBy: [{ title: ASC }]
                    first: 2
                  ) {
                    edges { node { id title } }
                  }
                }
                """;
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(query, 10L, "reader");
        String json = TitanGraphqlFunctions.executeGraphql(query, 10L, "reader");

        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionMatchesJavaGeneratedStringFilters() {
        String query = """
                {
                  articles(
                    filter: {
                      title: {
                        contains: "GraphQL"
                        startsWith: "Titan"
                        endsWith: "proof"
                      }
                    }
                    first: 2
                  ) {
                    edges { node { id title } }
                  }
                }
                """;
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(query, 10L, "reader");
        String json = TitanGraphqlFunctions.executeGraphql(query, 10L, "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}}]}}}",
                execution.json()
        );
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionMatchesJavaGeneratedIntComparisonFilters() {
        String query = """
                {
                  articles(
                    filter: {
                      id: { gt: 1, gte: 2 }
                      authorId: { lt: 12, lte: 11 }
                    }
                    first: 2
                  ) {
                    edges { node { id title } }
                  }
                }
                """;
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(query, 10L, "reader");
        String json = TitanGraphqlFunctions.executeGraphql(query, 10L, "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":2,\"title\":\"Stored functions as APIs\"}}]}}}",
                execution.json()
        );
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionMatchesJavaGeneratedComputedFilterAndOrder() {
        String query = """
                {
                  articles(
                    filter: { titleLength: { gt: 18 } }
                    orderBy: [{ titleLength: DESC }, { id: ASC }]
                    first: 2
                  ) {
                    edges { node { id titleLength } }
                  }
                }
                """;
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(query, 10L, "reader");
        String json = TitanGraphqlFunctions.executeGraphql(query, 10L, "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":2,\"titleLength\":24}},{\"node\":{\"id\":1,\"titleLength\":19}}]}}}",
                execution.json()
        );
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionRejectsStringOperatorsOnGeneratedIntFilter() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ articles(filter: { id: { contains: \"1\" } }, first: 2) { edges { node { id } } } }",
                10L,
                "reader");

        assertTrue(json.contains("unsupported filter operator 'contains' on field 'id'"));
    }

    @Test
    void publicStoredFunctionRejectsNumericComparisonOperatorsOnGeneratedStringFilter() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ articles(filter: { title: { gt: 1 } }, first: 2) { edges { node { id } } } }",
                10L,
                "reader");

        assertTrue(json.contains("unsupported filter operator 'gt' on field 'title'"));
    }

    @Test
    void publicStoredFunctionMatchesJavaGeneratedEmptyOrFilter() {
        String query = "{ articles(filter: { or: [] }, first: 2) { edges { node { id title } } } }";
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(query, 10L, "reader");
        String json = TitanGraphqlFunctions.executeGraphql(query, 10L, "reader");

        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionRejectsUnknownGeneratedFilterField() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ articles(filter: { unknown: { eq: 1 } }, first: 2) { edges { node { id } } } }",
                10L,
                "reader");

        assertTrue(json.contains("unknown field 'unknown' on ArticleFilter"));
    }

    @Test
    void publicStoredFunctionMatchesJavaGeneratedRelationHopFilter() {
        String query = "{ articles(filter: { authorName: { eq: \"Ada Lovelace\" } }, first: 2) { edges { node { id title } } } }";
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(query, 10L, "reader");
        String json = TitanGraphqlFunctions.executeGraphql(query, 10L, "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}}]}}}",
                execution.json()
        );
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionMatchesJavaGeneratedRelationHopStringFilterComposition() {
        String query = """
                {
                  articles(
                    filter: {
                      authorName: { startsWith: "Grace", endsWith: "Hopper" }
                      not: { title: { contains: "GraphQL" } }
                    }
                    first: 2
                  ) {
                    edges { node { id title } }
                  }
                }
                """;
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(query, 10L, "reader");
        String json = TitanGraphqlFunctions.executeGraphql(query, 10L, "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":2,\"title\":\"Stored functions as APIs\"}}]}}}",
                execution.json()
        );
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionMatchesJavaGeneratedRelationHopOrder() {
        String query = "{ articles(orderBy: [{ authorName: DESC }], first: 2) { edges { node { id } } } }";
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(query, 10L, "reader");
        String json = TitanGraphqlFunctions.executeGraphql(query, 10L, "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":2}},{\"node\":{\"id\":1}}]}}}",
                execution.json()
        );
        assertEquals(execution.json(), json);
    }

    @Test
    void publicStoredFunctionExecutesGeneratedOrderWithRelayCursors() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ articles(orderBy: [{ title: ASC }], after: \"article:2\", first: 1) { edges { node { id } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1}}],\"pageInfo\":{\"hasNextPage\":false,\"hasPreviousPage\":true,\"startCursor\":\"article:1\",\"endCursor\":\"article:1\"}}}}",
                json
        );
    }

    @Test
    void genericEngineExecutesGeneratedRelationHopOrder() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(
                "{ articles(orderBy: [{ authorName: ASC }], first: 2) { edges { node { id } } } }",
                10L,
                "reader");

        assertEquals(1, execution.plan().readStepCount());
        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1}},{\"node\":{\"id\":2}}]}}}",
                execution.json()
        );
    }

    @Test
    void genericEngineExecutesGeneratedRelationHopFilter() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(
                "{ articles(filter: { authorName: { startsWith: \"Ada\" } }, first: 2) { edges { node { id } } } }",
                10L,
                "reader");

        assertEquals(1, execution.plan().readStepCount());
        assertEquals("{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1}}]}}}", execution.json());
    }

    @Test
    void genericEngineExecutesGeneratedOrderWithRelayCursors() {
        String cursor = articleOrderCursor("title", "title", GraphqlRootField.RootCursorDirection.ASC, "Stored functions as APIs", 2L);
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(
                "{ articles(orderBy: [{ title: ASC }], after: \"" + cursor
                        + "\", first: 1) { edges { cursor node { id } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }",
                10L,
                "reader");

        String expectedCursor = articleOrderCursor("title", "title", GraphqlRootField.RootCursorDirection.ASC, "Titan GraphQL proof", 1L);
        assertEquals(1, execution.plan().readStepCount());
        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"cursor\":\"" + expectedCursor
                        + "\",\"node\":{\"id\":1}}],\"pageInfo\":{\"hasNextPage\":false,\"hasPreviousPage\":true,\"startCursor\":\""
                        + expectedCursor + "\",\"endCursor\":\"" + expectedCursor + "\"}}}}",
                execution.json()
        );
    }

    @Test
    void genericEngineSkipsRelationReadsWhenListRootReturnsNoParents() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(
                "{ articles(first: 0) { edges { node { id author { name } comments { edges { node { body } } } } } } }",
                10L,
                "reader");

        assertEquals("{\"data\":{\"articles\":{\"edges\":[]}}}", execution.json());
        assertEquals(1, execution.plan().readStepCount());
    }

    @Test
    void genericEngineExecutesArticlesListRootWithBatchedAuthorRead() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(
                "{ articles(first: 2) { edges { node { title author { name } } } } }",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"title\":\"Titan GraphQL proof\",\"author\":{\"name\":\"Ada Lovelace\"}}},{\"node\":{\"title\":\"Stored functions as APIs\",\"author\":{\"name\":\"Grace Hopper\"}}}]}}}",
                execution.json()
        );
        assertEquals(2, execution.plan().readStepCount());
        assertEquals("articles.author", execution.plan().readSteps().get(1).name());
        assertTrue(execution.plan().readSteps().get(1).sql().contains("WHERE id IN (10, 11)"));
    }

    @Test
    void genericEngineExecutesArticleCommentsRelation() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(
                "{ article(id: 1) { title comments(first: 2) { edges { node { id body } } } } }",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"article\":{\"title\":\"Titan GraphQL proof\",\"comments\":{\"edges\":[{\"node\":{\"id\":100,\"body\":\"First comment\"}},{\"node\":{\"id\":101,\"body\":\"Second comment\"}}]}}}}",
                execution.json()
        );
        assertEquals(2, execution.plan().readStepCount());
        assertEquals("article.comments", execution.plan().readSteps().get(1).name());
    }

    @Test
    void genericEngineExecutesArticlesListRootWithBatchedCommentsRead() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(
                "{ articles(first: 2) { edges { node { id comments(first: 2) { edges { node { body } } } } } } }",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1,\"comments\":{\"edges\":[{\"node\":{\"body\":\"First comment\"}},{\"node\":{\"body\":\"Second comment\"}}]}}},{\"node\":{\"id\":2,\"comments\":{\"edges\":[{\"node\":{\"body\":\"API comment\"}}]}}}]}}}",
                execution.json()
        );
        assertEquals(2, execution.plan().readStepCount());
        assertEquals("articles.comments", execution.plan().readSteps().get(1).name());
        assertTrue(execution.plan().readSteps().get(1).sql().contains("WHERE article_id IN (1, 2)"));
    }

    @Test
    void genericEngineExecutesNestedCommentAuthorRelation() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(
                "{ article(id: 1) { comments(first: 2) { edges { node { body author { name } } } } } }",
                10L,
                "reader");

        assertEquals(
                "{\"data\":{\"article\":{\"comments\":{\"edges\":[{\"node\":{\"body\":\"First comment\",\"author\":{\"name\":\"Grace Hopper\"}}},{\"node\":{\"body\":\"Second comment\",\"author\":{\"name\":\"Ada Lovelace\"}}}]}}}}",
                execution.json()
        );
        assertEquals(3, execution.plan().readStepCount());
        assertEquals("article.comments.author", execution.plan().readSteps().get(2).name());
        assertTrue(execution.plan().readSteps().get(2).sql().contains("WHERE id IN (11, 10)"));
    }

    @Test
    void genericEngineRejectsUnsupportedArticlesArgument() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(
                "{ articles(id: 1) { edges { node { id } } } }",
                10L,
                "reader");

        assertTrue(execution.json().contains("unsupported argument on articles"));
    }

    @Test
    void genericEngineRejectsWrongTypedAuthorFilter() {
        GraphqlExecution execution = TitanGraphqlFunctions.executeGraphqlWithPlan(
                "{ articles(authorId: \"10\") { edges { node { id } } } }",
                10L,
                "reader");

        assertTrue(execution.json().contains("argument 'authorId' must be an integer"));
    }

    @Test
    void genericEngineExecutesRelayRootConnection() {
        GraphqlExecution execution = executeRelayArticles(
                "{ articles(first: 1) { edges { cursor node { id title } } totalCount pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }",
                "reader");
        String firstCursor = articleCursor(1L);

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"cursor\":\"" + firstCursor
                        + "\",\"node\":{\"id\":1,\"title\":\"Titan GraphQL proof\"}}],\"totalCount\":2,\"pageInfo\":{\"hasNextPage\":true,\"hasPreviousPage\":false,\"startCursor\":\""
                        + firstCursor + "\",\"endCursor\":\"" + firstCursor + "\"}}}}",
                execution.json()
        );
        assertEquals(1, execution.plan().readStepCount());
    }

    @Test
    void genericEngineExecutesRelayRootConnectionAfterCursor() {
        GraphqlExecution execution = executeRelayArticles(
                "{ articles(first: 1, after: \"" + articleCursor(1L)
                        + "\") { edges { node { id } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }",
                "reader");
        String secondCursor = articleCursor(2L);

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":2}}],\"pageInfo\":{\"hasNextPage\":false,\"hasPreviousPage\":true,\"startCursor\":\""
                        + secondCursor + "\",\"endCursor\":\"" + secondCursor + "\"}}}}",
                execution.json()
        );
        assertEquals(1, execution.plan().readStepCount());
    }

    @Test
    void genericEngineExecutesRelayRootConnectionWithRelations() {
        GraphqlExecution execution = executeRelayArticles(
                "{ articles(first: 1) { edges { node { title author { name } } } pageInfo { hasNextPage } } }",
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"title\":\"Titan GraphQL proof\",\"author\":{\"name\":\"Ada Lovelace\"}}}],\"pageInfo\":{\"hasNextPage\":true}}}}",
                execution.json()
        );
        assertEquals(2, execution.plan().readStepCount());
        assertEquals("articles.author", execution.plan().readSteps().get(1).name());
    }

    @Test
    void genericEngineExecutesRelayRelationConnection() {
        GraphqlExecution execution = executeRelayComments(
                "{ article(id: 1) { title comments(first: 1) { edges { cursor node { id body } } totalCount pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } } }",
                "reader");
        String firstCursor = commentCursor(100L);

        assertEquals(
                "{\"data\":{\"article\":{\"title\":\"Titan GraphQL proof\",\"comments\":{\"edges\":[{\"cursor\":\""
                        + firstCursor + "\",\"node\":{\"id\":100,\"body\":\"First comment\"}}],\"totalCount\":2,\"pageInfo\":{\"hasNextPage\":true,\"hasPreviousPage\":false,\"startCursor\":\""
                        + firstCursor + "\",\"endCursor\":\"" + firstCursor + "\"}}}}}",
                execution.json()
        );
        assertEquals(2, execution.plan().readStepCount());
        assertEquals("article.comments", execution.plan().readSteps().get(1).name());
    }

    @Test
    void genericEngineExecutesRelayRelationConnectionAfterCursorWithNestedAuthor() {
        GraphqlExecution execution = executeRelayComments(
                "{ article(id: 1) { comments(first: 1, after: \"" + commentCursor(100L)
                        + "\") { edges { node { id author { name } } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } } }",
                "reader");
        String secondCursor = commentCursor(101L);

        assertEquals(
                "{\"data\":{\"article\":{\"comments\":{\"edges\":[{\"node\":{\"id\":101,\"author\":{\"name\":\"Ada Lovelace\"}}}],\"pageInfo\":{\"hasNextPage\":false,\"hasPreviousPage\":true,\"startCursor\":\""
                        + secondCursor + "\",\"endCursor\":\"" + secondCursor + "\"}}}}}",
                execution.json()
        );
        assertEquals(3, execution.plan().readStepCount());
        assertEquals("article.comments.author", execution.plan().readSteps().get(2).name());
        assertTrue(execution.plan().readSteps().get(2).sql().contains("WHERE id IN (10)"));
    }

    @Test
    void genericEngineUsesDefaultPageSizeForCursorOnlyRelayRelationConnection() {
        GraphqlExecution execution = executeRelayComments(
                "{ article(id: 1) { comments(after: \"" + commentCursor(100L)
                        + "\") { edges { node { id body } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } } }",
                "reader");
        String secondCursor = commentCursor(101L);

        assertEquals(
                "{\"data\":{\"article\":{\"comments\":{\"edges\":[{\"node\":{\"id\":101,\"body\":\"Second comment\"}}],\"pageInfo\":{\"hasNextPage\":false,\"hasPreviousPage\":true,\"startCursor\":\""
                        + secondCursor + "\",\"endCursor\":\"" + secondCursor + "\"}}}}}",
                execution.json()
        );
        assertEquals(2, execution.plan().readStepCount());
    }

    @Test
    void genericEngineExecutesRelayRelationConnectionPerListParent() {
        GraphqlExecution execution = executeRelayComments(
                "{ articles(first: 2) { edges { node { id comments(first: 1) { edges { node { id author { name } } } pageInfo { hasNextPage } } } } } }",
                "reader");

        assertEquals(
                "{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1,\"comments\":{\"edges\":[{\"node\":{\"id\":100,\"author\":{\"name\":\"Grace Hopper\"}}}],\"pageInfo\":{\"hasNextPage\":true}}}},{\"node\":{\"id\":2,\"comments\":{\"edges\":[{\"node\":{\"id\":102,\"author\":{\"name\":\"Ada Lovelace\"}}}],\"pageInfo\":{\"hasNextPage\":false}}}}]}}}",
                execution.json()
        );
        assertEquals(3, execution.plan().readStepCount());
        assertEquals("articles.comments.author", execution.plan().readSteps().get(2).name());
        assertTrue(execution.plan().readSteps().get(2).sql().contains("WHERE id IN (11, 10)"));
    }

    @Test
    void publicSqlKernelExposesArticlesRoot() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ articles(first: 2) { edges { node { id } } } }",
                10L,
                "reader");

        assertEquals("{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":1}},{\"node\":{\"id\":2}}]}}}", json);
    }

    @Test
    void publicSqlKernelExposesArticlesAuthorFilter() {
        String json = TitanGraphqlFunctions.executeGraphql(
                "{ articles(authorId: 11, first: 2) { totalCount edges { node { id title } } } }",
                10L,
                "reader");

        assertEquals("{\"data\":{\"articles\":{\"edges\":[{\"node\":{\"id\":2,\"title\":\"Stored functions as APIs\"}}],\"totalCount\":1}}}", json);
    }

    private static GraphqlExecution executeRelayArticles(String query, String actorRole) {
        GraphqlSchema schema = ProjectionGraphqlAdapter.adapt(new ProjectionModel(
                List.of(ProjectionRetrieval.point("article", "Article", "id"),
                        ProjectionRetrieval.relayConnection(
                                "articles",
                                "Article",
                                1,
                                100,
                                List.of(ProjectionRetrieval.RetrievalArgument.intEquals("authorId", "author_id"))
                        )),
                DemoBlogGraphqlSchema.projectionModel(new GraphqlPolicy()).types()
        ));
        DemoBlogGraphqlExecutor executor = new DemoBlogGraphqlExecutor(schema, new DemoBlogFixtureStore(), new GraphqlJsonWriter());
        try {
            GraphqlSelection selection = GraphqlValidator.validate(schema, GraphqlParser.parse(query), actorRole);
            return executor.execute(selection, GraphqlRequestContext.legacy(0L, actorRole));
        } catch (GraphqlException ex) {
            return new GraphqlExecution(GraphqlJsonWriter.error(ex), new GraphqlPlan());
        }
    }

    private static GraphqlExecution executeRelayComments(String query, String actorRole) {
        GraphqlSchema schema = ProjectionGraphqlAdapter.adapt(new ProjectionModel(
                List.of(ProjectionRetrieval.point("article", "Article", "id"),
                        ProjectionRetrieval.relayConnection(
                                "articles",
                                "Article",
                                10,
                                100,
                                List.of(ProjectionRetrieval.RetrievalArgument.intEquals("authorId", "author_id"))
                        )),
                List.of(
                        new ProjectionType(
                                "Article",
                                "articles",
                                "public",
                                "articles",
                                "id",
                                List.of(
                                        ProjectionField.column("id", "id"),
                                        ProjectionField.column("title", "title")
                                ),
                                List.of(
                                        ProjectionRelation.one(
                                                "author",
                                                "User",
                                                "author_id",
                                                "id",
                                                false
                                        ),
                                        ProjectionRelation.many(
                                                "comments",
                                                "Comment",
                                                "id",
                                                "article_id",
                                                false,
                                                ProjectionRelation.ProjectionRelationCapabilities.relayConnectionWithTotalCount(
                                                        false,
                                                        true,
                                                        2,
                                                        0,
                                                        0,
                                                        10,
                                                        100
                                                ),
                                                List.of(
                                                        ProjectionRelation.ProjectionRelationArgument.relayFirst(),
                                                        ProjectionRelation.ProjectionRelationArgument.relayAfter(),
                                                        ProjectionRelation.ProjectionRelationArgument.relayLast(),
                                                        ProjectionRelation.ProjectionRelationArgument.relayBefore()
                                                ),
                                                List.of(ProjectionRelation.ProjectionRelationSortPath.ascending("id", "id", "id", 0))
                                        )
                                )
                        ),
                        new ProjectionType(
                                "User",
                                "users",
                                "public",
                                "users",
                                "id",
                                List.of(
                                        ProjectionField.column("id", "id"),
                                        ProjectionField.column("name", "name"),
                                        ProjectionField.column("email", "email", new GraphqlPolicy()::canReadUserEmail)
                                ),
                                List.of()
                        ),
                        new ProjectionType(
                                "Comment",
                                "comments",
                                "public",
                                "comments",
                                "id",
                                List.of(
                                        ProjectionField.column("id", "id"),
                                        ProjectionField.column("body", "body")
                                ),
                                List.of(
                                        ProjectionRelation.one(
                                                "author",
                                                "User",
                                                "author_id",
                                                "id",
                                                false
                                        )
                                )
                        )
                )
        ));
        DemoBlogGraphqlExecutor executor = new DemoBlogGraphqlExecutor(schema, new DemoBlogFixtureStore(), new GraphqlJsonWriter());
        try {
            GraphqlSelection selection = GraphqlValidator.validate(schema, GraphqlParser.parse(query), actorRole);
            return executor.execute(selection, GraphqlRequestContext.legacy(0L, actorRole));
        } catch (GraphqlException ex) {
            return new GraphqlExecution(GraphqlJsonWriter.error(ex), new GraphqlPlan());
        }
    }

    private static String articleCursor(long id) {
        return GraphqlCursorCodec.encode(GraphqlCursorCodec.payload(
                GraphqlRootField.RootCursorOrdering.ascending("id", "id", "id"),
                Long.toString(id),
                Long.toString(id)
        ));
    }

    private static String articleOrderCursor(
            String name,
            String sortPath,
            GraphqlRootField.RootCursorDirection direction,
            String value,
            long id
    ) {
        return GraphqlCursorCodec.encode(new GraphqlCursorCodec.CursorPayload(
                name,
                sortPath,
                direction,
                value,
                "id",
                Long.toString(id)
        ));
    }

    private static String commentCursor(long id) {
        return GraphqlCursorCodec.encode(new GraphqlCursorCodec.CursorPayload(
                "id",
                "id",
                GraphqlRootField.RootCursorDirection.ASC,
                Long.toString(id),
                "id",
                Long.toString(id)
        ));
    }

    private static void assertErrorCode(String json, String code) {
        assertTrue(json.contains("\"extensions\":{\"code\":\"" + code + "\"}"), json);
    }

    private static GraphqlExecution executeRequest(GraphqlRequest request, String actorRole) {
        return executeRequest(request, GraphqlRequestContext.legacy(0L, actorRole));
    }

    private static GraphqlExecution executeRequest(GraphqlRequest request, GraphqlRequestContext context) {
        GraphqlJsonWriter jsonWriter = new GraphqlJsonWriter();
        GraphqlDataModel dataModel = new DemoBlogGraphqlModel(new GraphqlPolicy(), new DemoBlogFixtureStore(), jsonWriter);
        return GraphqlEngine.execute(dataModel, jsonWriter, request, context);
    }

    private static void assertYamlBackedResponseMatchesJava(String query, GraphqlRequestContext context) {
        GraphqlRequest request = GraphqlRequest.query(query);
        GraphqlExecution javaExecution = executeRequest(request, context);
        GraphqlExecution yamlExecution = executeYamlBackedRequest(request, context);

        assertTrue(javaExecution.json().contains("\"errors\"") == false, javaExecution.json());
        assertEquals(javaExecution.json(), yamlExecution.json());
    }

    private static GraphqlExecution executeYamlBackedRequest(GraphqlRequest request, GraphqlRequestContext context) {
        GraphqlJsonWriter jsonWriter = new GraphqlJsonWriter();
        GraphqlSchema schema = yamlBackedDemoBlogSchema(new GraphqlPolicy());
        DemoBlogGraphqlExecutor executor = new DemoBlogGraphqlExecutor(schema, new DemoBlogFixtureStore(), jsonWriter);
        GraphqlDataModel dataModel = new GraphqlDataModel() {
            @Override
            public GraphqlSchema schema() {
                return schema;
            }

            @Override
            public GraphqlExecution execute(GraphqlSelection selection, GraphqlRequestContext requestContext) {
                return executor.execute(selection, requestContext);
            }
        };
        return GraphqlEngine.execute(dataModel, jsonWriter, request, context);
    }

    private static GraphqlSchema yamlBackedDemoBlogSchema(GraphqlPolicy policy) {
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(readDemoBlogFixture());
        return ProjectionGraphqlAdapter.adapt(TitanGraphqlProjectionModelAdapter.adapt(document, policy));
    }

    private static String readDemoBlogFixture() {
        try (InputStream stream = TitanGraphqlFunctionsTest.class.getResourceAsStream(
                "/graphql/demo-blog.titan.graphql.yaml"
        )) {
            if (stream == null) {
                throw new IllegalStateException("missing demo-blog model fixture");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read demo-blog model fixture", ex);
        }
    }

    private static String draftId(String yaml) {
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(yaml);
        String hash = TitanGraphqlModelDocumentJson.semanticHash(document);
        return "draft-" + hash.substring(0, 12);
    }
}
