package io.titan.graphql.management;

import java.util.List;

public record TitanGraphqlOperationRegistry(
        String id,
        String modelId,
        String environment,
        RegistryMode mode,
        List<RegisteredOperation> operations,
        String updatedAt
) {
    // Renamed from Mode under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RegistryMode {
        OBSERVE,
        LEARN,
        WARN,
        ENFORCE
    }

    // Renamed from OperationStatus under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RegisteredOperationStatus {
        OBSERVED,
        APPROVED,
        REJECTED,
        DEPRECATED
    }

    // Renamed from Operation under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record RegisteredOperation(
            String id,
            String operationName,
            String operationHash,
            String document,
            RegisteredOperationStatus status,
            List<String> roles,
            List<String> clients,
            int depth,
            int estimatedCost,
            List<String> fieldUsage,
            String lastSeenAt,
            String approvedBy,
            String approvedAt
    ) {
        public RegisteredOperation {
            id = ManagementSupport.requireText(id, "operation.id");
            operationName = ManagementSupport.textOrEmpty(operationName);
            operationHash = ManagementSupport.requireText(operationHash, "operation.operationHash");
            document = ManagementSupport.requireText(document, "operation.document");
            status = status == null ? RegisteredOperationStatus.OBSERVED : status;
            roles = ManagementSupport.listOrEmpty(roles);
            clients = ManagementSupport.listOrEmpty(clients);
            if (depth < 0 || estimatedCost < 0) {
                throw new IllegalArgumentException("operation depth and estimatedCost cannot be negative");
            }
            fieldUsage = ManagementSupport.listOrEmpty(fieldUsage);
            lastSeenAt = ManagementSupport.textOrEmpty(lastSeenAt);
            approvedBy = ManagementSupport.textOrEmpty(approvedBy);
            approvedAt = ManagementSupport.textOrEmpty(approvedAt);
        }
    }

    public TitanGraphqlOperationRegistry {
        id = ManagementSupport.requireText(id, "operationRegistry.id");
        modelId = ManagementSupport.requireText(modelId, "operationRegistry.modelId");
        environment = ManagementSupport.requireText(environment, "operationRegistry.environment");
        mode = mode == null ? RegistryMode.OBSERVE : mode;
        operations = ManagementSupport.listOrEmpty(operations);
        updatedAt = ManagementSupport.textOrEmpty(updatedAt);
    }
}
