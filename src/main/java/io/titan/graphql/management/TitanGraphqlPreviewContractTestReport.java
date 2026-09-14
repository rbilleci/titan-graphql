package io.titan.graphql.management;

import java.util.List;

public record TitanGraphqlPreviewContractTestReport(
        String id,
        String previewBuildId,
        String artifactSetId,
        String operationRegistryId,
        ContractTestReportStatus status,
        int totalOperations,
        int passedOperations,
        int failedOperations,
        int skippedOperations,
        List<OperationResult> operationResults,
        String createdAt
) {
    // Renamed from Status under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum ContractTestReportStatus {
        PASS,
        FAIL,
        BLOCKED
    }

    // Renamed from OperationStatus under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum ContractTestOperationStatus {
        PASS,
        FAIL,
        SKIPPED
    }

    public record OperationResult(
            String operationId,
            String operationName,
            String operationHash,
            ContractTestOperationStatus status,
            String responseHash,
            String message
    ) {
        public OperationResult {
            operationId = ManagementSupport.requireText(operationId, "contractTest.operationId");
            operationName = ManagementSupport.textOrEmpty(operationName);
            operationHash = ManagementSupport.requireText(operationHash, "contractTest.operationHash");
            status = status == null ? ContractTestOperationStatus.FAIL : status;
            responseHash = ManagementSupport.textOrEmpty(responseHash);
            message = ManagementSupport.textOrEmpty(message);
        }
    }

    public TitanGraphqlPreviewContractTestReport {
        id = ManagementSupport.requireText(id, "contractTest.id");
        previewBuildId = ManagementSupport.requireText(previewBuildId, "contractTest.previewBuildId");
        artifactSetId = ManagementSupport.requireText(artifactSetId, "contractTest.artifactSetId");
        operationRegistryId = ManagementSupport.textOrEmpty(operationRegistryId);
        status = status == null ? ContractTestReportStatus.BLOCKED : status;
        if (totalOperations < 0 || passedOperations < 0 || failedOperations < 0 || skippedOperations < 0) {
            throw new IllegalArgumentException("contract test counts cannot be negative");
        }
        operationResults = ManagementSupport.listOrEmpty(operationResults);
        createdAt = ManagementSupport.textOrEmpty(createdAt);
    }
}
