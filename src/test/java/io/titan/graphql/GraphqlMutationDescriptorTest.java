package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GraphqlMutationDescriptorTest {

    @Test
    void describesCommandStyleMutationMetadata() {
        GraphqlMutationDescriptor mutation = importModelMutation();

        assertEquals("importModelDocument", mutation.name());
        assertEquals("titan.management.importModelDocument", mutation.commandName());
        assertEquals("Import a reviewable model document draft.", mutation.description());

        assertEquals("ImportModelDocumentInput", mutation.input().name());
        assertEquals(3, mutation.input().fields().size());
        assertEquals("workspaceId", mutation.input().fields().getFirst().name());
        assertEquals("ID", mutation.input().fields().getFirst().graphqlType());
        assertEquals(true, mutation.input().fields().getFirst().required());
        assertEquals("yaml", mutation.input().fields().get(1).name());
        assertEquals("String", mutation.input().fields().get(1).graphqlType());

        assertEquals("ImportModelDocumentPayload", mutation.payload().name());
        assertEquals("draftId", mutation.payload().fields().getFirst().name());
        assertEquals("ID", mutation.payload().fields().getFirst().graphqlType());
        assertEquals(true, mutation.payload().fields().getFirst().required());
        assertEquals("validation", mutation.payload().fields().get(1).name());
        assertEquals("ValidationReport", mutation.payload().fields().get(1).graphqlType());

        assertEquals(true, mutation.authorization().requiresAuthenticatedActor());
        assertEquals("canManageGraphqlModels", mutation.authorization().policyName());
        assertEquals(List.of("admin", "platform"), mutation.authorization().requiredRoles());

        assertEquals(GraphqlMutationDescriptor.TransactionMode.REQUIRED, mutation.transaction().mode());
        assertEquals("requestId", mutation.transaction().idempotencyKeyInputField());
        assertEquals("REJECT_CONFLICT", mutation.transaction().conflictPolicy());

        assertEquals(GraphqlMutationDescriptor.AuditMode.ATTEMPT_AND_RESULT, mutation.audit().mode());
        assertEquals("model_document_imported", mutation.audit().eventType());
        assertEquals(true, mutation.audit().includeInput());
        assertEquals(false, mutation.audit().includePayload());
    }

    @Test
    void appliesSafeMetadataDefaultsAndCopiesCollections() {
        List<GraphqlMutationDescriptor.InputField> inputFields = new ArrayList<>();
        inputFields.add(new GraphqlMutationDescriptor.InputField("draftId", "ID", true, null));
        List<GraphqlMutationDescriptor.PayloadField> payloadFields = new ArrayList<>();
        payloadFields.add(new GraphqlMutationDescriptor.PayloadField("ok", "Boolean", true, null));

        GraphqlMutationDescriptor mutation = new GraphqlMutationDescriptor(
                "validateModelDraft",
                "titan.management.validateModelDraft",
                null,
                new GraphqlMutationDescriptor.InputObject("ValidateModelDraftInput", inputFields),
                new GraphqlMutationDescriptor.PayloadObject("ValidateModelDraftPayload", payloadFields),
                null,
                null,
                null
        );
        inputFields.clear();
        payloadFields.clear();

        assertEquals("", mutation.description());
        assertEquals("", mutation.input().fields().getFirst().description());
        assertEquals("", mutation.payload().fields().getFirst().description());
        assertEquals(false, mutation.authorization().requiresAuthenticatedActor());
        assertTrue(mutation.authorization().policyName().isEmpty());
        assertTrue(mutation.authorization().requiredRoles().isEmpty());
        assertEquals(GraphqlMutationDescriptor.TransactionMode.NONE, mutation.transaction().mode());
        assertEquals(GraphqlMutationDescriptor.AuditMode.NONE, mutation.audit().mode());
        assertEquals(1, mutation.input().fields().size());
        assertEquals(1, mutation.payload().fields().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> mutation.input().fields().add(new GraphqlMutationDescriptor.InputField("extra", "String", false, ""))
        );
    }

    @Test
    void attachesMutationDescriptorsToSchemaWithoutRuntimeBehavior() {
        GraphqlMutationDescriptor importModel = importModelMutation();
        GraphqlMutationDescriptor replacement = new GraphqlMutationDescriptor(
                "importModelDocument",
                "titan.management.importModelDocumentV2",
                "",
                importModel.input(),
                importModel.payload(),
                importModel.authorization(),
                importModel.transaction(),
                importModel.audit()
        );
        GraphqlMutationDescriptor validateModel = new GraphqlMutationDescriptor(
                "validateModelDraft",
                "titan.management.validateModelDraft",
                "",
                new GraphqlMutationDescriptor.InputObject("ValidateModelDraftInput", List.of(
                        new GraphqlMutationDescriptor.InputField("draftId", "ID", true, "")
                )),
                new GraphqlMutationDescriptor.PayloadObject("ValidateModelDraftPayload", List.of(
                        new GraphqlMutationDescriptor.PayloadField("validation", "ValidationReport", true, "")
                )),
                null,
                null,
                null
        );

        GraphqlSchema schema = new GraphqlSchema(
                List.of(),
                List.of(),
                List.of(),
                List.of(importModel, replacement, validateModel)
        );

        assertEquals(2, schema.mutations().size());
        assertEquals("titan.management.importModelDocumentV2", schema.mutation("importModelDocument").commandName());
        assertEquals("validateModelDraft", schema.mutations().get(1).name());
        assertEquals(null, schema.rootField("importModelDocument"));
        assertThrows(UnsupportedOperationException.class, () -> schema.mutations().clear());

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parseSelectedOperation(GraphqlRequest.of(
                                "mutation Import { importModelDocument(input: { workspaceId: 1, yaml: \"apiVersion: titan.graphql/v1alpha1\" }) { draftId } }",
                                "Import"
                        )),
                        "admin"
                )
        );
        assertEquals("mutation operation selected but mutation execution is not implemented yet", error.getMessage());
        assertEquals(GraphqlException.UNSUPPORTED_OPERATION, error.code());
    }

    @Test
    void rejectsMissingRequiredDescriptorFields() {
        GraphqlMutationDescriptor.InputObject input = new GraphqlMutationDescriptor.InputObject("Input", List.of());
        GraphqlMutationDescriptor.PayloadObject payload = new GraphqlMutationDescriptor.PayloadObject("Payload", List.of());

        assertThrows(IllegalArgumentException.class, () -> new GraphqlMutationDescriptor(
                "",
                "command",
                "",
                input,
                payload,
                null,
                null,
                null
        ));
        assertThrows(IllegalArgumentException.class, () -> new GraphqlMutationDescriptor(
                "name",
                "",
                "",
                input,
                payload,
                null,
                null,
                null
        ));
        assertThrows(IllegalArgumentException.class, () -> new GraphqlMutationDescriptor.InputField("", "ID", true, ""));
        assertThrows(IllegalArgumentException.class, () -> new GraphqlMutationDescriptor.PayloadField("ok", "", true, ""));
        assertThrows(IllegalArgumentException.class, () -> new GraphqlMutationDescriptor(
                "name",
                "command",
                "",
                null,
                payload,
                null,
                null,
                null
        ));
    }

    private static GraphqlMutationDescriptor importModelMutation() {
        return new GraphqlMutationDescriptor(
                "importModelDocument",
                "titan.management.importModelDocument",
                "Import a reviewable model document draft.",
                new GraphqlMutationDescriptor.InputObject("ImportModelDocumentInput", List.of(
                        new GraphqlMutationDescriptor.InputField("workspaceId", "ID", true, "Target workspace."),
                        new GraphqlMutationDescriptor.InputField("yaml", "String", true, "Human-authored YAML source."),
                        new GraphqlMutationDescriptor.InputField("requestId", "String", false, "Optional idempotency key.")
                )),
                new GraphqlMutationDescriptor.PayloadObject("ImportModelDocumentPayload", List.of(
                        new GraphqlMutationDescriptor.PayloadField("draftId", "ID", true, "Created draft id."),
                        new GraphqlMutationDescriptor.PayloadField("validation", "ValidationReport", true, "Validation report.")
                )),
                new GraphqlMutationDescriptor.AuthorizationMetadata(
                        true,
                        "canManageGraphqlModels",
                        List.of("admin", "platform")
                ),
                new GraphqlMutationDescriptor.TransactionMetadata(
                        GraphqlMutationDescriptor.TransactionMode.REQUIRED,
                        "requestId",
                        "REJECT_CONFLICT"
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
