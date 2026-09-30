package io.titan.graphql;

import io.titan.graphql.controlplane.TitanGraphqlArtifactGenerationService;
import io.titan.graphql.controlplane.TitanGraphqlModelValidationService;
import io.titan.graphql.management.TitanGraphqlInMemoryManagementStore;
import io.titan.graphql.management.TitanGraphqlManagementStore;
import io.titan.graphql.management.TitanGraphqlObservedOperation;
import java.util.List;
import java.util.Map;

final class GraphqlManagementMutationSupport {

    private static final String IMPORT_COMMAND = "titan.management.importModelDocument";
    private static final String VALIDATE_COMMAND = "titan.management.validateModelDraft";
    private static final String GENERATE_COMMAND = "titan.management.generateModelArtifacts";
    private static final String APPROVE_OPERATION_COMMAND = "titan.management.approveObservedOperation";
    private static final String REJECT_OPERATION_COMMAND = "titan.management.rejectObservedOperation";

    private final TitanGraphqlManagementStore store;
    private final GraphqlMutationAuditLog auditLog = new GraphqlMutationAuditLog();

    GraphqlManagementMutationSupport() {
        this(defaultManagementStore());
    }

    GraphqlManagementMutationSupport(TitanGraphqlManagementStore store) {
        this.store = java.util.Objects.requireNonNull(store, "management store");
    }

    GraphqlSchema withManagementMutations(GraphqlSchema base) {
        return new GraphqlSchema(
                base.rootFields(),
                base.types(),
                List.of(),
                List.of(
                        importModelDocument(),
                        validateModelDraft(),
                        generateModelArtifacts(),
                        approveObservedOperation(),
                        rejectObservedOperation()
                )
        );
    }

    GraphqlExecution executeMutation(GraphqlSchema schema, GraphqlAst.AstOperation operation, GraphqlRequestContext context) {
        GraphqlMutationExecutor executor = new GraphqlMutationExecutor(
                schema,
                Map.of(
                        IMPORT_COMMAND, this::importModelDocument,
                        VALIDATE_COMMAND, this::validateModelDraft,
                        GENERATE_COMMAND, this::generateModelArtifacts,
                        APPROVE_OPERATION_COMMAND, this::approveObservedOperation,
                        REJECT_OPERATION_COMMAND, this::rejectObservedOperation
                ),
                auditLog
        );
        return executor.execute(operation, context);
    }

    TitanGraphqlManagementStore store() {
        return store;
    }

    GraphqlMutationAuditLog auditLog() {
        return auditLog;
    }

    static TitanGraphqlManagementStore defaultManagementStore() {
        // Store selection (dogfood Phase C): titan.graphql.management.store = file (default —
        // Docker-free, unchanged file/in-memory path) | jdbc (the durable dogfooded core JDBC store
        // over the Quarkus datasource). See TitanGraphqlManagementStoreFactory.
        return TitanGraphqlManagementStoreFactory.fromRuntimeConfig();
    }

    private GraphqlMutationCommandResult importModelDocument(
            GraphqlMutationCommandHandler.GraphqlMutationCommandRequest request
    ) {
        return GraphqlMutationCommandResult.of(new TitanGraphqlModelImportService(store)
                .importDocument(request.input(), request.context()));
    }

    private GraphqlMutationCommandResult validateModelDraft(
            GraphqlMutationCommandHandler.GraphqlMutationCommandRequest request
    ) {
        return GraphqlMutationCommandResult.of(new TitanGraphqlModelValidationService(store)
                .validate(text(request.input().get("draftId"))));
    }

    private GraphqlMutationCommandResult generateModelArtifacts(
            GraphqlMutationCommandHandler.GraphqlMutationCommandRequest request
    ) {
        String draftId = text(request.input().get("draftId"));
        String generationProfile = textOrDefault(request.input().get("generationProfile"), "development");
        boolean enableIntrospection = Boolean.TRUE.equals(request.input().get("enableIntrospection"));
        return GraphqlMutationCommandResult.of(new TitanGraphqlArtifactGenerationService(store)
                .generate(draftId, generationProfile, enableIntrospection).payload());
    }

    private GraphqlMutationCommandResult approveObservedOperation(
            GraphqlMutationCommandHandler.GraphqlMutationCommandRequest request
    ) {
        String observedOperationId = text(request.input().get("observedOperationId"));
        String reviewedAt = text(request.input().get("reviewedAt"));
        TitanGraphqlObservedOperation operation = store.approveObservedOperation(
                observedOperationId,
                request.context().actorRole(),
                reviewedAt
        );
        return operationReviewPayload(operation, true);
    }

    private GraphqlMutationCommandResult rejectObservedOperation(
            GraphqlMutationCommandHandler.GraphqlMutationCommandRequest request
    ) {
        String observedOperationId = text(request.input().get("observedOperationId"));
        String reviewedAt = text(request.input().get("reviewedAt"));
        TitanGraphqlObservedOperation operation = store.rejectObservedOperation(
                observedOperationId,
                request.context().actorRole(),
                reviewedAt
        );
        return operationReviewPayload(operation, true);
    }

    private static GraphqlMutationDescriptor importModelDocument() {
        return descriptor(
                "importModelDocument",
                IMPORT_COMMAND,
                new GraphqlMutationDescriptor.InputObject("ImportModelDocumentInput", List.of(
                        input("workspaceId", "ID", true),
                        input("yaml", "String", true),
                        input("requestId", "String", false)
                )),
                new GraphqlMutationDescriptor.PayloadObject("ImportModelDocumentPayload", lifecyclePayload(List.of(
                        output("modelId", "ID", true),
                        output("semanticHash", "String", true)
                )))
        );
    }

    private static GraphqlMutationDescriptor validateModelDraft() {
        return descriptor(
                "validateModelDraft",
                VALIDATE_COMMAND,
                new GraphqlMutationDescriptor.InputObject("ValidateModelDraftInput", List.of(
                        input("draftId", "ID", true)
                )),
                new GraphqlMutationDescriptor.PayloadObject("ValidateModelDraftPayload", lifecyclePayload(List.of()))
        );
    }

    private static GraphqlMutationDescriptor generateModelArtifacts() {
        return descriptor(
                "generateModelArtifacts",
                GENERATE_COMMAND,
                new GraphqlMutationDescriptor.InputObject("GenerateModelArtifactsInput", List.of(
                        input("draftId", "ID", true),
                        input("generationProfile", "String", false),
                        input("enableIntrospection", "Boolean", false)
                )),
                new GraphqlMutationDescriptor.PayloadObject("GenerateModelArtifactsPayload", lifecyclePayload(List.of(
                        output("artifactSetId", "ID", true),
                        output("semanticHash", "String", true),
                        output("sdlHash", "String", false),
                        output("introspectionHash", "String", false),
                        output("conformanceHash", "String", false),
                        output("generatedSqlHash", "String", false),
                        output("generatedArtifacts", "Int", true)
                )))
        );
    }

    private static GraphqlMutationDescriptor approveObservedOperation() {
        return descriptor(
                "approveObservedOperation",
                APPROVE_OPERATION_COMMAND,
                operationReviewInput("ApproveObservedOperationInput"),
                operationReviewPayload("ApproveObservedOperationPayload")
        );
    }

    private static GraphqlMutationDescriptor rejectObservedOperation() {
        return descriptor(
                "rejectObservedOperation",
                REJECT_OPERATION_COMMAND,
                operationReviewInput("RejectObservedOperationInput"),
                operationReviewPayload("RejectObservedOperationPayload")
        );
    }

    private static GraphqlMutationDescriptor.InputObject operationReviewInput(String name) {
        return new GraphqlMutationDescriptor.InputObject(name, List.of(
                input("observedOperationId", "ID", true),
                input("reviewedAt", "String", false),
                input("reason", "String", false),
                input("requestId", "String", false)
        ));
    }

    private static GraphqlMutationDescriptor.PayloadObject operationReviewPayload(String name) {
        return new GraphqlMutationDescriptor.PayloadObject(name, List.of(
                output("accepted", "Boolean", true),
                output("observedOperationId", "ID", true),
                output("operationRegistryId", "ID", true),
                output("modelId", "ID", true),
                output("environment", "String", true),
                output("role", "String", true),
                output("client", "String", true),
                output("operationHash", "String", true),
                output("status", "String", true)
        ));
    }

    private static GraphqlMutationCommandResult operationReviewPayload(
            TitanGraphqlObservedOperation operation,
            boolean accepted
    ) {
        return GraphqlMutationCommandResult.of(Map.of(
                "accepted", accepted,
                "observedOperationId", operation.id(),
                "operationRegistryId", TitanGraphqlInMemoryManagementStore.operationRegistryId(
                        operation.modelId(),
                        operation.environment()
                ),
                "modelId", operation.modelId(),
                "environment", operation.environment(),
                "role", operation.role(),
                "client", operation.client(),
                "operationHash", operation.operationHash(),
                "status", operation.status().name()
        ));
    }

    private static List<GraphqlMutationDescriptor.PayloadField> lifecyclePayload(
            List<GraphqlMutationDescriptor.PayloadField> extra
    ) {
        java.util.ArrayList<GraphqlMutationDescriptor.PayloadField> fields = new java.util.ArrayList<>();
        fields.add(output("accepted", "Boolean", true));
        fields.add(output("draftId", "ID", true));
        fields.add(output("validationReportId", "ID", false));
        fields.add(output("status", "String", false));
        fields.add(output("errors", "Int", false));
        fields.add(output("warnings", "Int", false));
        fields.addAll(extra);
        return List.copyOf(fields);
    }

    private static GraphqlMutationDescriptor descriptor(
            String name,
            String command,
            GraphqlMutationDescriptor.InputObject input,
            GraphqlMutationDescriptor.PayloadObject payload
    ) {
        return new GraphqlMutationDescriptor(
                name,
                command,
                "Management draft lifecycle mutation.",
                input,
                payload,
                new GraphqlMutationDescriptor.AuthorizationMetadata(true, "managementOperator", List.of("operator", "admin", "platform")),
                new GraphqlMutationDescriptor.TransactionMetadata(GraphqlMutationDescriptor.TransactionMode.REQUIRED, "requestId", "rejectDuplicate"),
                new GraphqlMutationDescriptor.AuditMetadata(
                        GraphqlMutationDescriptor.AuditMode.ATTEMPT_AND_RESULT,
                        command,
                        false,
                        true
                )
        );
    }

    private static GraphqlMutationDescriptor.InputField input(String name, String type, boolean required) {
        return new GraphqlMutationDescriptor.InputField(name, type, required, "");
    }

    private static GraphqlMutationDescriptor.PayloadField output(String name, String type, boolean required) {
        return new GraphqlMutationDescriptor.PayloadField(name, type, required, "");
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String textOrDefault(Object value, String defaultValue) {
        String text = text(value);
        return text.isBlank() ? defaultValue : text;
    }

}
