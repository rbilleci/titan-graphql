package io.titan.graphql;

import io.titan.graphql.controlplane.TitanGraphqlControlJobQueue;
import io.titan.graphql.controlplane.TitanGraphqlModelValidationService;
import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import io.titan.graphql.management.TitanGraphqlManagedModel;
import io.titan.graphql.management.TitanGraphqlManagementStore;
import io.titan.graphql.management.TitanGraphqlModelDraft;
import io.titan.graphql.management.TitanGraphqlValidationReportRef;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import io.titan.graphql.validation.TitanGraphqlModelDocumentValidator;
import io.titan.graphql.validation.TitanGraphqlValidationReport;
import io.titan.management.ManagementCommands.CommandInvocation;
import io.titan.management.ManagementTransactions.TransactionalCommandExecution;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class TitanGraphqlModelImportService {
    public record Prepared(
            CommandInvocation invocation,
            TitanGraphqlManagedModel model,
            TitanGraphqlModelDraft draft,
            TitanGraphqlValidationReportRef report,
            Map<String, Object> result
    ) {
    }

    private final TitanGraphqlManagementStore store;

    public TitanGraphqlModelImportService(TitanGraphqlManagementStore store) {
        this.store = Objects.requireNonNull(store, "management store");
    }

    public Map<String, Object> importDocument(Map<String, Object> input, GraphqlRequestContext context) {
        Prepared prepared = prepare(input, context);
        publish(prepared);
        return prepared.result();
    }

    public Prepared prepare(Map<String, Object> input, GraphqlRequestContext context) {
        CommandInvocation invocation = TitanGraphqlGap006CommandContext.importModelDocument(input, context);
        if (!(store instanceof TitanGraphqlDurableManagementStore)) {
            TitanGraphqlGap006CommandContext.requireValid(invocation);
        }
        String workspaceId = text(input.get("workspaceId"));
        String source = text(input.get("yaml"));
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(source);
        String semanticHash = TitanGraphqlModelDocumentJson.semanticHash(document);
        String modelId = "model-" + stableId(document.metadata().name());
        String draftId = "draft-" + shortHash(semanticHash);
        String canonicalJson = TitanGraphqlModelDocumentJson.canonicalJson(document);
        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);
        TitanGraphqlValidationReportRef reportRef = TitanGraphqlModelValidationService.reportRef(
                "validation-" + draftId, draftId, report);
        TitanGraphqlModelDraft.ModelDraftStatus status = report.valid()
                ? TitanGraphqlModelDraft.ModelDraftStatus.IMPORTED
                : TitanGraphqlModelDraft.ModelDraftStatus.FAILED_VALIDATION;
        TitanGraphqlManagedModel model = new TitanGraphqlManagedModel(
                modelId, workspaceId, document.metadata().name(), document.metadata().name(),
                document.metadata().description(), draftId, "", reportRef.id(), "", "", "");
        TitanGraphqlModelDraft draft = new TitanGraphqlModelDraft(
                draftId, modelId, status, TitanGraphqlModelDraft.SourceFormat.YAML, source,
                canonicalJson, semanticHash, reportRef.id(), "", "", context.actorRole(), "", "");
        Map<String, Object> result = Map.of(
                "accepted", report.valid(),
                "draftId", draftId,
                "modelId", modelId,
                "semanticHash", semanticHash,
                "validationReportId", reportRef.id(),
                "status", status.name(),
                "errors", Math.toIntExact(report.errorCount()),
                "warnings", Math.toIntExact(report.warningCount()));
        return new Prepared(invocation, model, draft, reportRef, result);
    }

    public void publish(Prepared prepared) {
        if (store instanceof TitanGraphqlDurableManagementStore durableStore) {
            TransactionalCommandExecution execution = durableStore.importModelDocument(
                    prepared.invocation(), prepared.model(), prepared.draft(), prepared.report(),
                    Instant.now(), Instant.now());
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
            store.saveModel(prepared.model());
            store.saveDraft(prepared.draft());
            store.saveValidationReport(prepared.report());
        }
    }

    public boolean publishAndComplete(
            Prepared prepared,
            TitanGraphqlControlJobQueue queue,
            TitanGraphqlControlJobQueue.ClaimedJob job,
            String resultJson
    ) throws SQLException {
        if (!(store instanceof TitanGraphqlDurableManagementStore durableStore)
                || !durableStore.supportsAtomicJobCompletion()) {
            throw new IllegalStateException("model import job requires JDBC management state");
        }
        return durableStore.importModelDocumentAndCompleteJob(
                prepared.invocation(), prepared.model(), prepared.draft(), prepared.report(),
                Instant.now(), Instant.now(), queue, job, resultJson);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String stableId(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return normalized.isBlank() ? "unnamed" : normalized;
    }

    private static String shortHash(String hash) {
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }
}
