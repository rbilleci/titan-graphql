package io.titan.graphql.controlplane;

import io.titan.graphql.GraphqlException;
import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import io.titan.graphql.management.TitanGraphqlManagementStore;
import io.titan.graphql.management.TitanGraphqlModelDraft;
import io.titan.graphql.management.TitanGraphqlValidationReportRef;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import io.titan.graphql.validation.TitanGraphqlModelDocumentValidator;
import io.titan.graphql.validation.TitanGraphqlValidationReport;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;

public final class TitanGraphqlModelValidationService {
    public record Prepared(
            TitanGraphqlValidationReportRef report,
            TitanGraphqlModelDraft draft,
            Map<String, Object> result
    ) {
    }

    private final TitanGraphqlManagementStore store;

    public TitanGraphqlModelValidationService(TitanGraphqlManagementStore store) {
        this.store = Objects.requireNonNull(store, "management store");
    }

    public Map<String, Object> validate(String draftId) {
        Prepared prepared = prepare(draftId);
        publish(prepared);
        return prepared.result();
    }

    public Prepared prepare(String draftId) {
        if (draftId == null || draftId.isBlank()) {
            throw new GraphqlException("model draft id is required for validation");
        }
        TitanGraphqlModelDraft draft = store.draft(draftId);
        if (draft == null) {
            throw new GraphqlException("unknown model draft '" + draftId + "'");
        }
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(draft.sourceText());
        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);
        TitanGraphqlValidationReportRef reportRef = reportRef("validation-" + draftId, draftId, report);
        TitanGraphqlModelDraft.ModelDraftStatus status = report.valid()
                ? TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED
                : TitanGraphqlModelDraft.ModelDraftStatus.FAILED_VALIDATION;

        TitanGraphqlModelDraft validatedDraft = new TitanGraphqlModelDraft(
                draft.id(), draft.modelId(), status, draft.sourceFormat(), draft.sourceText(),
                draft.canonicalJson(), draft.semanticHash(), reportRef.id(), draft.artifactSetId(),
                draft.driftReportId(), draft.createdBy(), draft.createdAt(), "");
        return new Prepared(reportRef, validatedDraft, Map.of(
                "accepted", report.valid(),
                "draftId", draftId,
                "validationReportId", reportRef.id(),
                "status", status.name(),
                "errors", Math.toIntExact(report.errorCount()),
                "warnings", Math.toIntExact(report.warningCount())));
    }

    public void publish(Prepared prepared) {
        if (store instanceof TitanGraphqlDurableManagementStore durableStore) {
            durableStore.saveValidationOutcome(prepared.report(), prepared.draft());
        } else {
            store.saveValidationReport(prepared.report());
            store.saveDraft(prepared.draft());
        }
    }

    public boolean publishAndComplete(
            Prepared prepared,
            TitanGraphqlControlJobQueue queue,
            TitanGraphqlControlJobQueue.ClaimedJob job,
            String resultJson
    ) throws SQLException {
        if (store instanceof TitanGraphqlDurableManagementStore durableStore
                && durableStore.supportsAtomicJobCompletion()) {
            return durableStore.saveValidationOutcomeAndCompleteJob(
                    prepared.report(), prepared.draft(), queue, job, resultJson);
        }
        publish(prepared);
        return queue.complete(job, resultJson);
    }

    public static TitanGraphqlValidationReportRef reportRef(
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
        String summary = report.valid()
                ? "validation passed"
                : "validation failed with " + report.errorCount() + " error(s)";
        return new TitanGraphqlValidationReportRef(
                id, draftId, status, summary,
                Math.toIntExact(report.errorCount()), Math.toIntExact(report.warningCount()),
                Math.toIntExact(report.infoCount()), report.blocksDeployment(), "");
    }
}
