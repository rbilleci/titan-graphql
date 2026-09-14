package io.titan.graphql;

import io.titan.graphql.artifact.TitanGraphqlArtifactSet;
import io.titan.graphql.artifact.TitanGraphqlArtifactsDirectory;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.graphql.artifact.TitanGraphqlGeneratedArtifactSet;
import io.titan.graphql.artifact.TitanGraphqlIntrospectionArtifactPolicy;
import io.titan.graphql.management.TitanGraphqlArtifactSetRef;
import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import io.titan.graphql.management.TitanGraphqlInMemoryManagementStore;
import io.titan.graphql.management.TitanGraphqlManagementStore;
import io.titan.graphql.management.TitanGraphqlManagedModel;
import io.titan.graphql.management.TitanGraphqlModelDraft;
import io.titan.graphql.management.TitanGraphqlObservedOperation;
import io.titan.graphql.management.TitanGraphqlValidationReportRef;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import io.titan.graphql.validation.TitanGraphqlModelDocumentValidator;
import io.titan.graphql.validation.TitanGraphqlValidationReport;
import io.titan.management.ManagementCommands.CommandInvocation;
import io.titan.management.ManagementTransactions.TransactionalCommandExecution;
import java.time.Instant;
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
        CommandInvocation invocation = TitanGraphqlGap006CommandContext.importModelDocument(
                request.input(),
                request.context()
        );
        boolean durable = store instanceof TitanGraphqlDurableManagementStore;
        if (!durable) {
            TitanGraphqlGap006CommandContext.requireValid(invocation);
        }
        String workspaceId = text(request.input().get("workspaceId"));
        String source = text(request.input().get("yaml"));
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(source);
        String semanticHash = TitanGraphqlModelDocumentJson.semanticHash(document);
        String modelId = "model-" + stableId(document.metadata().name());
        String draftId = "draft-" + shortHash(semanticHash);
        String canonicalJson = TitanGraphqlModelDocumentJson.canonicalJson(document);

        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);
        TitanGraphqlValidationReportRef reportRef = validationReportRef("validation-" + draftId, draftId, report);
        TitanGraphqlModelDraft.ModelDraftStatus status = report.valid()
                ? TitanGraphqlModelDraft.ModelDraftStatus.IMPORTED
                : TitanGraphqlModelDraft.ModelDraftStatus.FAILED_VALIDATION;
        TitanGraphqlManagedModel model = new TitanGraphqlManagedModel(
                modelId,
                workspaceId,
                document.metadata().name(),
                document.metadata().name(),
                document.metadata().description(),
                draftId,
                "",
                reportRef.id(),
                "",
                "",
                ""
        );
        TitanGraphqlModelDraft draft = new TitanGraphqlModelDraft(
                draftId,
                modelId,
                status,
                TitanGraphqlModelDraft.SourceFormat.YAML,
                source,
                canonicalJson,
                semanticHash,
                reportRef.id(),
                "",
                "",
                request.context().actorRole(),
                "",
                ""
        );
        if (store instanceof TitanGraphqlDurableManagementStore durableStore) {
            TransactionalCommandExecution execution = durableStore.importModelDocument(
                    invocation,
                    model,
                    draft,
                    reportRef,
                    Instant.now(),
                    Instant.now());
            if (execution.conflict()) {
                throw new GraphqlException("idempotency input mismatch for mutation 'importModelDocument'");
            }
            if (!execution.success()) {
                String message = execution.outcomeRecord().errorMessage() == null
                        ? "durable importModelDocument transaction failed"
                        : execution.outcomeRecord().errorMessage();
                throw new GraphqlException(message);
            }
        } else {
            store.saveModel(model);
            store.saveDraft(draft);
            store.saveValidationReport(reportRef);
        }
        return GraphqlMutationCommandResult.of(Map.of(
                "accepted", report.valid(),
                "draftId", draftId,
                "modelId", modelId,
                "semanticHash", semanticHash,
                "validationReportId", reportRef.id(),
                "status", status.name(),
                "errors", Math.toIntExact(report.errorCount()),
                "warnings", Math.toIntExact(report.warningCount())
        ));
    }

    private GraphqlMutationCommandResult validateModelDraft(
            GraphqlMutationCommandHandler.GraphqlMutationCommandRequest request
    ) {
        String draftId = text(request.input().get("draftId"));
        TitanGraphqlModelDraft draft = requireDraft(draftId);
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(draft.sourceText());
        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);
        TitanGraphqlValidationReportRef reportRef = validationReportRef("validation-" + draftId, draftId, report);
        TitanGraphqlModelDraft.ModelDraftStatus status = report.valid()
                ? TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED
                : TitanGraphqlModelDraft.ModelDraftStatus.FAILED_VALIDATION;

        store.saveValidationReport(reportRef);
        store.saveDraft(new TitanGraphqlModelDraft(
                draft.id(),
                draft.modelId(),
                status,
                draft.sourceFormat(),
                draft.sourceText(),
                draft.canonicalJson(),
                draft.semanticHash(),
                reportRef.id(),
                draft.artifactSetId(),
                draft.driftReportId(),
                draft.createdBy(),
                draft.createdAt(),
                ""
        ));
        return GraphqlMutationCommandResult.of(Map.of(
                "accepted", report.valid(),
                "draftId", draftId,
                "validationReportId", reportRef.id(),
                "status", status.name(),
                "errors", Math.toIntExact(report.errorCount()),
                "warnings", Math.toIntExact(report.warningCount())
        ));
    }

    private GraphqlMutationCommandResult generateModelArtifacts(
            GraphqlMutationCommandHandler.GraphqlMutationCommandRequest request
    ) {
        String draftId = text(request.input().get("draftId"));
        String generationProfile = textOrDefault(request.input().get("generationProfile"), "development");
        boolean enableIntrospection = Boolean.TRUE.equals(request.input().get("enableIntrospection"));
        TitanGraphqlModelDraft draft = requireDraft(draftId);
        TitanGraphqlValidationReportRef validation = store.validationReport(draft.validationReportId());
        if (validation == null || validation.blocksDeployment()) {
            throw new GraphqlException("draft '" + draftId + "' must pass validation before artifacts can be generated");
        }
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(draft.sourceText());
        String artifactSetId = "artifact-" + draftId;
        // Real-artifact wiring: when the model requests SQL artifacts, the GAP-005 metadata is
        // read from the configured titanPackage output directory (default
        // build/generated/migrations/titan; -Dtitan.graphql.artifacts.dir /
        // TITAN_GRAPHQL_ARTIFACTS_DIR override). A missing package directory surfaces as a
        // descriptive error — the former 'metadataOnly' placeholder is gone.
        TitanGraphqlGap005ArtifactMetadata gap005Metadata = document.artifacts().generateSql()
                ? TitanGraphqlArtifactsDirectory.readGap005Metadata()
                : null;
        TitanGraphqlGeneratedArtifactSet generated = TitanGraphqlGeneratedArtifactWorkflow.generateFromModelDocument(
                artifactSetId,
                draftId,
                document,
                enableIntrospection
                        ? TitanGraphqlIntrospectionArtifactPolicy.ENABLED
                        : TitanGraphqlIntrospectionArtifactPolicy.DISABLED,
                generationProfile,
                "",
                gap005Metadata
        );
        TitanGraphqlArtifactSet manifest = generated.manifest();
        TitanGraphqlArtifactSetRef ref = new TitanGraphqlArtifactSetRef(
                manifest.id(),
                manifest.draftId(),
                manifest.semanticHash(),
                manifest.validationHash(),
                manifest.driftHash(),
                manifest.sdlHash(),
                manifest.introspectionHash(),
                manifest.conformanceHash(),
                manifest.generatedSqlHash(),
                manifest.generationProfile(),
                manifest.createdAt()
        );
        if (gap005Metadata != null && store instanceof TitanGraphqlDurableManagementStore durableStore) {
            // Seeds the titan-store artifact ref AND the durable evidence (verification
            // status/diagnostics, entry points, rollback summaries) from the real package.
            durableStore.saveArtifactSet(ref, gap005Metadata);
        } else {
            store.saveArtifactSet(ref);
        }
        store.saveDraft(new TitanGraphqlModelDraft(
                draft.id(),
                draft.modelId(),
                TitanGraphqlModelDraft.ModelDraftStatus.READY_FOR_REVIEW,
                draft.sourceFormat(),
                draft.sourceText(),
                draft.canonicalJson(),
                draft.semanticHash(),
                draft.validationReportId(),
                ref.id(),
                draft.driftReportId(),
                draft.createdBy(),
                draft.createdAt(),
                ""
        ));
        return GraphqlMutationCommandResult.of(Map.of(
                "accepted", true,
                "draftId", draftId,
                "artifactSetId", ref.id(),
                "semanticHash", ref.semanticHash(),
                "sdlHash", ref.sdlHash(),
                "introspectionHash", ref.introspectionHash(),
                "conformanceHash", ref.conformanceHash(),
                "generatedSqlHash", ref.generatedSqlHash(),
                "generatedArtifacts", generated.artifacts().size()
        ));
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

    private TitanGraphqlModelDraft requireDraft(String draftId) {
        TitanGraphqlModelDraft draft = store.draft(draftId);
        if (draft == null) {
            throw new GraphqlException("unknown model draft '" + draftId + "'");
        }
        return draft;
    }

    private static TitanGraphqlValidationReportRef validationReportRef(
            String id,
            String draftId,
            TitanGraphqlValidationReport report
    ) {
        TitanGraphqlValidationReportRef.ValidationReportStatus status = TitanGraphqlValidationReportRef.ValidationReportStatus.PASS;
        if (report.errorCount() > 0) {
            status = TitanGraphqlValidationReportRef.ValidationReportStatus.FAIL;
        } else if (report.warningCount() > 0) {
            status = TitanGraphqlValidationReportRef.ValidationReportStatus.WARN;
        }
        return new TitanGraphqlValidationReportRef(
                id,
                draftId,
                status,
                summary(report),
                Math.toIntExact(report.errorCount()),
                Math.toIntExact(report.warningCount()),
                Math.toIntExact(report.infoCount()),
                report.blocksDeployment(),
                ""
        );
    }

    private static String summary(TitanGraphqlValidationReport report) {
        if (report.valid()) {
            return "validation passed";
        }
        return "validation failed with " + report.errorCount() + " error(s)";
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

    private static String stableId(String text) {
        String normalized = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return normalized.isBlank() ? "unnamed" : normalized;
    }

    private static String shortHash(String hash) {
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }
}
