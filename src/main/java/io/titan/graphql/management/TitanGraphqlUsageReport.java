package io.titan.graphql.management;

import java.util.List;

public record TitanGraphqlUsageReport(
        String id,
        String modelId,
        String environment,
        String version,
        String window,
        String summary,
        int totalOperations,
        int totalRequests,
        List<OperationUsage> operations,
        List<FieldUsage> fields,
        List<DimensionUsage> roles,
        List<DimensionUsage> clients,
        List<DimensionUsage> environments,
        List<DimensionUsage> versions,
        List<DeprecatedFieldUsage> deprecatedFieldsInUse,
        List<UnusedField> unusedFields,
        List<PolicyRejection> policyRejections,
        List<SlowOperation> slowOperations,
        String generatedAt
) {
    public record OperationUsage(
            String operationId,
            String operationName,
            String operationHash,
            String client,
            String role,
            String version,
            int requestCount,
            int errorCount
    ) {
        public OperationUsage {
            operationId = ManagementSupport.requireText(operationId, "operationUsage.operationId");
            operationName = ManagementSupport.textOrEmpty(operationName);
            operationHash = ManagementSupport.requireText(operationHash, "operationUsage.operationHash");
            client = ManagementSupport.textOrEmpty(client);
            role = ManagementSupport.textOrEmpty(role);
            version = ManagementSupport.textOrEmpty(version);
            requireNonNegative(requestCount, "operationUsage.requestCount");
            requireNonNegative(errorCount, "operationUsage.errorCount");
        }
    }

    public record FieldUsage(
            String fieldPath,
            int requestCount,
            int operationCount,
            String lastSeenAt
    ) {
        public FieldUsage {
            fieldPath = ManagementSupport.requireText(fieldPath, "fieldUsage.fieldPath");
            requireNonNegative(requestCount, "fieldUsage.requestCount");
            requireNonNegative(operationCount, "fieldUsage.operationCount");
            lastSeenAt = ManagementSupport.textOrEmpty(lastSeenAt);
        }
    }

    public record DimensionUsage(
            String name,
            int requestCount,
            int operationCount
    ) {
        public DimensionUsage {
            name = ManagementSupport.requireText(name, "dimensionUsage.name");
            requireNonNegative(requestCount, "dimensionUsage.requestCount");
            requireNonNegative(operationCount, "dimensionUsage.operationCount");
        }
    }

    public record DeprecatedFieldUsage(
            String fieldPath,
            String deprecatedSince,
            int requestCount,
            String lastSeenAt
    ) {
        public DeprecatedFieldUsage {
            fieldPath = ManagementSupport.requireText(fieldPath, "deprecatedFieldUsage.fieldPath");
            deprecatedSince = ManagementSupport.textOrEmpty(deprecatedSince);
            requireNonNegative(requestCount, "deprecatedFieldUsage.requestCount");
            lastSeenAt = ManagementSupport.textOrEmpty(lastSeenAt);
        }
    }

    public record UnusedField(
            String fieldPath,
            String reason
    ) {
        public UnusedField {
            fieldPath = ManagementSupport.requireText(fieldPath, "unusedField.fieldPath");
            reason = ManagementSupport.textOrEmpty(reason);
        }
    }

    public record PolicyRejection(
            String policyName,
            String fieldPath,
            String role,
            String client,
            int rejectionCount
    ) {
        public PolicyRejection {
            policyName = ManagementSupport.requireText(policyName, "policyRejection.policyName");
            fieldPath = ManagementSupport.textOrEmpty(fieldPath);
            role = ManagementSupport.textOrEmpty(role);
            client = ManagementSupport.textOrEmpty(client);
            requireNonNegative(rejectionCount, "policyRejection.rejectionCount");
        }
    }

    public record SlowOperation(
            String operationId,
            String operationName,
            String operationHash,
            long p95Millis,
            long maxMillis,
            int sampleCount
    ) {
        public SlowOperation {
            operationId = ManagementSupport.requireText(operationId, "slowOperation.operationId");
            operationName = ManagementSupport.textOrEmpty(operationName);
            operationHash = ManagementSupport.requireText(operationHash, "slowOperation.operationHash");
            requireNonNegative(p95Millis, "slowOperation.p95Millis");
            requireNonNegative(maxMillis, "slowOperation.maxMillis");
            requireNonNegative(sampleCount, "slowOperation.sampleCount");
        }
    }

    public TitanGraphqlUsageReport {
        id = ManagementSupport.requireText(id, "usageReport.id");
        modelId = ManagementSupport.requireText(modelId, "usageReport.modelId");
        environment = ManagementSupport.requireText(environment, "usageReport.environment");
        version = ManagementSupport.textOrEmpty(version);
        window = ManagementSupport.requireText(window, "usageReport.window");
        summary = ManagementSupport.textOrEmpty(summary);
        requireNonNegative(totalOperations, "usageReport.totalOperations");
        requireNonNegative(totalRequests, "usageReport.totalRequests");
        operations = ManagementSupport.listOrEmpty(operations);
        fields = ManagementSupport.listOrEmpty(fields);
        roles = ManagementSupport.listOrEmpty(roles);
        clients = ManagementSupport.listOrEmpty(clients);
        environments = ManagementSupport.listOrEmpty(environments);
        versions = ManagementSupport.listOrEmpty(versions);
        deprecatedFieldsInUse = ManagementSupport.listOrEmpty(deprecatedFieldsInUse);
        unusedFields = ManagementSupport.listOrEmpty(unusedFields);
        policyRejections = ManagementSupport.listOrEmpty(policyRejections);
        slowOperations = ManagementSupport.listOrEmpty(slowOperations);
        generatedAt = ManagementSupport.textOrEmpty(generatedAt);
    }

    private static void requireNonNegative(long value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " cannot be negative");
        }
    }
}
