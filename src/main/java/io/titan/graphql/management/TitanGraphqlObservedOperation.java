package io.titan.graphql.management;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public record TitanGraphqlObservedOperation(
        String id,
        String modelId,
        String environment,
        String role,
        String client,
        String operationHash,
        String operationName,
        String document,
        ObservedOperationStatus status,
        int depth,
        int estimatedCost,
        List<String> fieldUsage,
        String firstSeenAt,
        String lastSeenAt,
        int observedCount
) {
    // Renamed from Status under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum ObservedOperationStatus {
        OBSERVED,
        APPROVED,
        REJECTED,
        DEPRECATED
    }

    public static TitanGraphqlObservedOperation observed(
            String modelId,
            String environment,
            String role,
            String client,
            String operationHash,
            String operationName,
            String document,
            int depth,
            int estimatedCost,
            List<String> fieldUsage,
            String observedAt
    ) {
        return new TitanGraphqlObservedOperation(
                observedOperationId(modelId, environment, role, client, operationHash),
                modelId,
                environment,
                role,
                client,
                operationHash,
                operationName,
                document,
                ObservedOperationStatus.OBSERVED,
                depth,
                estimatedCost,
                fieldUsage,
                observedAt,
                observedAt,
                1
        );
    }

    public static String observedOperationId(
            String modelId,
            String environment,
            String role,
            String client,
            String operationHash
    ) {
        String key = ManagementSupport.requireText(modelId, "observedOperation.modelId")
                + "\n" + ManagementSupport.requireText(environment, "observedOperation.environment")
                + "\n" + ManagementSupport.requireText(role, "observedOperation.role")
                + "\n" + ManagementSupport.requireText(client, "observedOperation.client")
                + "\n" + ManagementSupport.requireText(operationHash, "observedOperation.operationHash");
        return "observed-operation-" + sha256Hex(key).substring(0, 16);
    }

    public TitanGraphqlObservedOperation {
        id = ManagementSupport.requireText(id, "observedOperation.id");
        modelId = ManagementSupport.requireText(modelId, "observedOperation.modelId");
        environment = ManagementSupport.requireText(environment, "observedOperation.environment");
        role = ManagementSupport.requireText(role, "observedOperation.role");
        client = ManagementSupport.requireText(client, "observedOperation.client");
        operationHash = ManagementSupport.requireText(operationHash, "observedOperation.operationHash");
        operationName = ManagementSupport.textOrEmpty(operationName);
        document = ManagementSupport.requireText(document, "observedOperation.document");
        status = status == null ? ObservedOperationStatus.OBSERVED : status;
        if (depth < 0 || estimatedCost < 0 || observedCount < 0) {
            throw new IllegalArgumentException("observed operation depth, estimatedCost, and observedCount cannot be negative");
        }
        fieldUsage = ManagementSupport.listOrEmpty(fieldUsage);
        firstSeenAt = ManagementSupport.textOrEmpty(firstSeenAt);
        lastSeenAt = ManagementSupport.textOrEmpty(lastSeenAt);
    }

    TitanGraphqlObservedOperation mergeObservation(TitanGraphqlObservedOperation next) {
        if (id.equals(next.id()) == false) {
            throw new IllegalArgumentException("observed operation observations must share the same id");
        }
        return new TitanGraphqlObservedOperation(
                id,
                modelId,
                environment,
                role,
                client,
                operationHash,
                next.operationName(),
                next.document(),
                status,
                next.depth(),
                next.estimatedCost(),
                next.fieldUsage(),
                firstSeenAt.isEmpty() ? next.firstSeenAt() : firstSeenAt,
                next.lastSeenAt().isEmpty() ? lastSeenAt : next.lastSeenAt(),
                observedCount + Math.max(1, next.observedCount())
        );
    }

    public TitanGraphqlObservedOperation withStatus(ObservedOperationStatus nextStatus) {
        return new TitanGraphqlObservedOperation(
                id,
                modelId,
                environment,
                role,
                client,
                operationHash,
                operationName,
                document,
                nextStatus,
                depth,
                estimatedCost,
                fieldUsage,
                firstSeenAt,
                lastSeenAt,
                observedCount
        );
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
