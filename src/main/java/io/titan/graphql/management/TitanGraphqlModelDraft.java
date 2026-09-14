package io.titan.graphql.management;

public record TitanGraphqlModelDraft(
        String id,
        String modelId,
        ModelDraftStatus status,
        SourceFormat sourceFormat,
        String sourceText,
        String canonicalJson,
        String semanticHash,
        String validationReportId,
        String artifactSetId,
        String driftReportId,
        String createdBy,
        String createdAt,
        String updatedAt
) {
    // Renamed from Status under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum ModelDraftStatus {
        EMPTY,
        IMPORTED,
        VALIDATED,
        FAILED_VALIDATION,
        READY_FOR_REVIEW,
        DEPLOYED,
        ARCHIVED
    }

    public enum SourceFormat {
        YAML,
        JSON
    }

    public TitanGraphqlModelDraft {
        id = ManagementSupport.requireText(id, "draft.id");
        modelId = ManagementSupport.requireText(modelId, "draft.modelId");
        status = status == null ? ModelDraftStatus.EMPTY : status;
        sourceFormat = sourceFormat == null ? SourceFormat.YAML : sourceFormat;
        sourceText = ManagementSupport.textOrEmpty(sourceText);
        canonicalJson = ManagementSupport.textOrEmpty(canonicalJson);
        semanticHash = ManagementSupport.textOrEmpty(semanticHash);
        validationReportId = ManagementSupport.textOrEmpty(validationReportId);
        artifactSetId = ManagementSupport.textOrEmpty(artifactSetId);
        driftReportId = ManagementSupport.textOrEmpty(driftReportId);
        createdBy = ManagementSupport.textOrEmpty(createdBy);
        createdAt = ManagementSupport.textOrEmpty(createdAt);
        updatedAt = ManagementSupport.textOrEmpty(updatedAt);
    }
}
