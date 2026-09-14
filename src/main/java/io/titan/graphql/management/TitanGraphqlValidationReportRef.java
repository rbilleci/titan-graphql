package io.titan.graphql.management;

public record TitanGraphqlValidationReportRef(
        String id,
        String draftId,
        ValidationReportStatus status,
        String summary,
        int errors,
        int warnings,
        int infos,
        boolean blocksDeployment,
        String createdAt
) {
    // Renamed from Status under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum ValidationReportStatus {
        PASS,
        WARN,
        FAIL
    }

    public TitanGraphqlValidationReportRef {
        id = ManagementSupport.requireText(id, "validationReport.id");
        draftId = ManagementSupport.requireText(draftId, "validationReport.draftId");
        status = status == null ? ValidationReportStatus.PASS : status;
        summary = ManagementSupport.textOrEmpty(summary);
        if (errors < 0 || warnings < 0 || infos < 0) {
            throw new IllegalArgumentException("validation report counts cannot be negative");
        }
        createdAt = ManagementSupport.textOrEmpty(createdAt);
    }
}
