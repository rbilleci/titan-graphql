package io.titan.graphql;

import io.titan.graphql.demo.blog.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class GraphqlMutationExecutorTest {

    @Test
    void executesMetadataBackedCommandMutationInJavaMode() {
        GraphqlMutationAuditLog auditLog = new GraphqlMutationAuditLog();
        GraphqlMutationExecutor executor = new GraphqlMutationExecutor(
                schema(importModelMutation()),
                Map.of("titan.management.importModelDocument", request -> {
                    assertEquals("workspace-1", request.input().get("workspaceId"));
                    assertEquals("apiVersion: titan.graphql/v1alpha1", request.input().get("yaml"));
                    assertEquals("req-7", request.context().requestId());
                    return GraphqlMutationCommandResult.of(Map.of(
                            "draftId", "draft-42",
                            "accepted", true
                    ));
                }),
                auditLog
        );

        GraphqlExecution execution = executor.execute(
                GraphqlParser.parseSelectedOperation(GraphqlRequest.of("""
                        mutation Import($workspaceId: ID!, $yaml: String!) {
                          imported: importModelDocument(input: { workspaceId: $workspaceId, yaml: $yaml }) {
                            id: draftId
                            accepted
                          }
                        }
                        """, "Import", Map.of(
                        "workspaceId", "workspace-1",
                        "yaml", "apiVersion: titan.graphql/v1alpha1"
                ))),
                context("platform", "req-7")
        );

        assertEquals("{\"data\":{\"imported\":{\"id\":\"draft-42\",\"accepted\":true}}}", execution.json());
        assertEquals(2, auditLog.events().size());
        assertEquals(GraphqlMutationAuditEvent.MutationAuditStatus.ATTEMPT, auditLog.events().getFirst().status());
        assertEquals(Map.of(
                "workspaceId", "workspace-1",
                "yaml", "apiVersion: titan.graphql/v1alpha1"
        ), auditLog.events().getFirst().input());
        assertEquals(GraphqlMutationAuditEvent.MutationAuditStatus.SUCCESS, auditLog.events().get(1).status());
        assertEquals(Map.of(), auditLog.events().get(1).payload());
        assertEquals("platform", auditLog.events().get(1).actorRole());
        assertEquals("req-7", auditLog.events().get(1).requestId());
    }

    @Test
    void returnsGraphqlShapedErrorForInvalidInput() {
        GraphqlMutationExecutor executor = new GraphqlMutationExecutor(
                schema(importModelMutation()),
                Map.of("titan.management.importModelDocument", request -> GraphqlMutationCommandResult.of(Map.of())),
                new GraphqlMutationAuditLog()
        );

        GraphqlExecution execution = executor.execute(
                GraphqlParser.parseSelectedOperation(GraphqlRequest.of("""
                        mutation Import {
                          importModelDocument(input: { workspaceId: 1, extra: "nope" }) { draftId }
                        }
                        """, "Import")),
                context("platform", "req-8")
        );

        assertTrue(execution.json().contains("input field 'extra' is not supported"));
        assertTrue(execution.json().contains("\"code\":\"VALIDATION_ERROR\""));
    }

    @Test
    void auditsCommandFailureAndReturnsErrorEnvelope() {
        GraphqlMutationAuditLog auditLog = new GraphqlMutationAuditLog();
        GraphqlMutationExecutor executor = new GraphqlMutationExecutor(
                schema(importModelMutation()),
                Map.of("titan.management.importModelDocument", request -> {
                    throw new GraphqlException("model document failed semantic validation");
                }),
                auditLog
        );

        GraphqlExecution execution = executor.execute(
                GraphqlParser.parseSelectedOperation(GraphqlRequest.of("""
                        mutation Import {
                          importModelDocument(input: {
                            workspaceId: "workspace-1",
                            yaml: "apiVersion: titan.graphql/v1alpha1"
                          }) { draftId }
                        }
                        """, "Import")),
                context("platform", "req-9")
        );

        assertTrue(execution.json().contains("model document failed semantic validation"));
        assertEquals(2, auditLog.events().size());
        assertEquals(GraphqlMutationAuditEvent.MutationAuditStatus.FAILURE, auditLog.events().get(1).status());
        assertEquals("model document failed semantic validation", auditLog.events().get(1).errorMessage());
    }

    @Test
    void rejectsInvalidPayloadSelectionBeforeCallingHandler() {
        AtomicBoolean called = new AtomicBoolean();
        GraphqlMutationExecutor executor = new GraphqlMutationExecutor(
                schema(importModelMutation()),
                Map.of("titan.management.importModelDocument", request -> {
                    called.set(true);
                    return GraphqlMutationCommandResult.of(Map.of());
                }),
                new GraphqlMutationAuditLog()
        );

        GraphqlExecution execution = executor.execute(
                GraphqlParser.parseSelectedOperation(GraphqlRequest.of("""
                        mutation Import {
                          importModelDocument(input: {
                            workspaceId: "workspace-1",
                            yaml: "apiVersion: titan.graphql/v1alpha1"
                          }) { missingField }
                        }
                        """, "Import")),
                context("platform", "req-invalid-payload")
        );

        assertTrue(execution.json().contains("payload field 'missingField' is not supported"));
        assertFalse(called.get());
    }

    @Test
    void preservesNullableInputAndPayloadValues() {
        GraphqlMutationDescriptor mutation = new GraphqlMutationDescriptor(
                "annotate",
                "example.annotate",
                "",
                new GraphqlMutationDescriptor.InputObject("AnnotateInput", List.of(
                        new GraphqlMutationDescriptor.InputField("note", "String", false, "")
                )),
                new GraphqlMutationDescriptor.PayloadObject("AnnotatePayload", List.of(
                        new GraphqlMutationDescriptor.PayloadField("note", "String", false, "")
                )),
                GraphqlMutationDescriptor.AuthorizationMetadata.none(),
                GraphqlMutationDescriptor.TransactionMetadata.none(),
                GraphqlMutationDescriptor.AuditMetadata.none()
        );
        GraphqlMutationExecutor executor = new GraphqlMutationExecutor(
                schema(mutation),
                Map.of("example.annotate", request -> {
                    assertTrue(request.input().containsKey("note"));
                    assertNull(request.input().get("note"));
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("note", null);
                    return GraphqlMutationCommandResult.of(payload);
                }),
                GraphqlMutationAuditSink.NONE
        );

        GraphqlExecution execution = executor.execute(
                GraphqlParser.parseSelectedOperation(GraphqlRequest.of(
                        "mutation { annotate(input: { note: null }) { note } }", "")),
                context("platform", "req-null")
        );

        assertEquals("{\"data\":{\"annotate\":{\"note\":null}}}", execution.json());
    }

    @Test
    void resolvesSchemaDeclaredMutationInputObjectVariable() {
        GraphqlMutationDescriptor mutation = importModelMutation();
        GraphqlSchema schema = schema(mutation);
        GraphqlMutationExecutor executor = new GraphqlMutationExecutor(
                schema,
                Map.of(mutation.commandName(), request -> GraphqlMutationCommandResult.of(Map.of(
                        "draftId", request.input().get("workspaceId"),
                        "accepted", true
                ))),
                GraphqlMutationAuditSink.NONE
        );
        Map<String, Object> input = Map.of(
                "workspaceId", "workspace-variable",
                "yaml", "apiVersion: titan.graphql/v1alpha1"
        );

        GraphqlExecution execution = executor.execute(
                GraphqlParser.parseSelectedOperation(GraphqlRequest.of("""
                        mutation Import($input: ImportModelDocumentInput!) {
                          importModelDocument(input: $input) { draftId accepted }
                        }
                        """, "Import", Map.of("input", input)), schema),
                context("platform", "req-input-variable")
        );

        assertEquals("{\"data\":{\"importModelDocument\":{\"draftId\":\"workspace-variable\","
                + "\"accepted\":true}}}", execution.json());
    }

    @Test
    void keepsApplicationGraphqlPathQueryOnly() {
        GraphqlExecution execution = GraphqlEngine.execute(
                new DemoBlogGraphqlModel(new GraphqlPolicy(), new DemoBlogFixtureStore(), new GraphqlJsonWriter()),
                new GraphqlJsonWriter(),
                GraphqlRequest.of("mutation Import { importModelDocument(input: { workspaceId: 1 }) { draftId } }", "Import"),
                context("platform", "req-10")
        );

        assertTrue(execution.json().contains("operation type 'mutation' is not supported"));
        assertTrue(execution.json().contains("\"code\":\"UNSUPPORTED_OPERATION\""));
    }

    private static GraphqlSchema schema(GraphqlMutationDescriptor mutation) {
        return new GraphqlSchema(List.of(), List.of(), List.of(), List.of(mutation));
    }

    private static GraphqlRequestContext context(String actorRole, String requestId) {
        return new GraphqlRequestContext(
                1L,
                actorRole,
                "actor-1",
                "tenant-1",
                requestId,
                "idem-" + requestId,
                List.of(),
                List.of(),
                false,
                false,
                false,
                0L
        );
    }

    private static GraphqlMutationDescriptor importModelMutation() {
        return new GraphqlMutationDescriptor(
                "importModelDocument",
                "titan.management.importModelDocument",
                "Import a reviewable model document draft.",
                new GraphqlMutationDescriptor.InputObject("ImportModelDocumentInput", List.of(
                        new GraphqlMutationDescriptor.InputField("workspaceId", "ID", true, "Target workspace."),
                        new GraphqlMutationDescriptor.InputField("yaml", "String", true, "Human-authored YAML source.")
                )),
                new GraphqlMutationDescriptor.PayloadObject("ImportModelDocumentPayload", List.of(
                        new GraphqlMutationDescriptor.PayloadField("draftId", "ID", true, "Created draft id."),
                        new GraphqlMutationDescriptor.PayloadField("accepted", "Boolean", true, "Whether the command was accepted.")
                )),
                new GraphqlMutationDescriptor.AuthorizationMetadata(
                        true,
                        "canManageGraphqlModels",
                        List.of("admin", "platform")
                ),
                new GraphqlMutationDescriptor.TransactionMetadata(
                        GraphqlMutationDescriptor.TransactionMode.REQUIRED,
                        "",
                        ""
                ),
                new GraphqlMutationDescriptor.AuditMetadata(
                        GraphqlMutationDescriptor.AuditMode.ATTEMPT_AND_RESULT,
                        "model_document_imported",
                        true,
                        false
                )
        );
    }
}
