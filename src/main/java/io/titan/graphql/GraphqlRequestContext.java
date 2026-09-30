package io.titan.graphql;

import java.util.List;
import java.util.Map;

public record GraphqlRequestContext(
        long actorId,
        String actorRole,
        String actorKey,
        String tenantId,
        String requestId,
        String idempotencyKey,
        List<String> policyFlags,
        List<String> enabledContextFilters,
        boolean introspectionEnabled,
        long deadlineEpochMillis,
        Map<String, Object> values
) {

    public GraphqlRequestContext(
            long actorId,
            String actorRole,
            String actorKey,
            String tenantId,
            String requestId,
            String idempotencyKey,
            List<String> policyFlags,
            List<String> enabledContextFilters,
            boolean introspectionEnabled,
            long deadlineEpochMillis
    ) {
        this(actorId, actorRole, actorKey, tenantId, requestId, idempotencyKey, policyFlags,
                enabledContextFilters, introspectionEnabled, deadlineEpochMillis, Map.of());
    }

    public GraphqlRequestContext {
        actorRole = actorRole == null ? "" : actorRole;
        actorKey = actorKey == null ? "" : actorKey;
        tenantId = tenantId == null ? "" : tenantId;
        requestId = requestId == null ? "" : requestId;
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey;
        policyFlags = policyFlags == null ? List.of() : List.copyOf(policyFlags);
        enabledContextFilters = enabledContextFilters == null ? List.of() : List.copyOf(enabledContextFilters);
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public static GraphqlRequestContext forActor(long actorId, String actorRole) {
        return new GraphqlRequestContext(
                actorId,
                actorRole,
                actorId > 0L ? "actor-" + actorId : "",
                "",
                "",
                "",
                List.of(),
                List.of(),
                false,
                0L
        );
    }

    public GraphqlRequestContext withValues(Map<String, Object> additionalValues) {
        return new GraphqlRequestContext(
                actorId, actorRole, actorKey, tenantId, requestId, idempotencyKey, policyFlags,
                enabledContextFilters, introspectionEnabled, deadlineEpochMillis, additionalValues);
    }
}
