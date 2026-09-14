package io.titan.graphql.management;

public record TitanGraphqlDriftReportRef(
        String id,
        String draftId,
        DriftReportStatus status,
        String checkedAgainst,
        String summary,
        int issueCount,
        boolean blocksDeployment,
        String createdAt
) {
    // Renamed from Status under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum DriftReportStatus {
        CLEAN,
        WARNING,
        DRIFT_DETECTED
    }

    public TitanGraphqlDriftReportRef {
        id = ManagementSupport.requireText(id, "driftReport.id");
        draftId = ManagementSupport.requireText(draftId, "driftReport.draftId");
        status = status == null ? DriftReportStatus.CLEAN : status;
        checkedAgainst = ManagementSupport.textOrEmpty(checkedAgainst);
        summary = ManagementSupport.textOrEmpty(summary);
        if (issueCount < 0) {
            throw new IllegalArgumentException("driftReport.issueCount cannot be negative");
        }
        createdAt = ManagementSupport.textOrEmpty(createdAt);
    }
}
