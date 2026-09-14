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
        boolean hasArticleVisibility,
        boolean articleVisibility,
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
            boolean hasArticleVisibility,
            boolean articleVisibility,
            long deadlineEpochMillis
    ) {
        this(actorId, actorRole, actorKey, tenantId, requestId, idempotencyKey, policyFlags,
                enabledContextFilters, introspectionEnabled, hasArticleVisibility, articleVisibility,
                deadlineEpochMillis, Map.of());
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

    public static GraphqlRequestContext legacy(long actorId, String actorRole) {
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
                false,
                false,
                0L
        );
    }

    public static GraphqlRequestContext articleVisibility(long actorId, String actorRole, boolean articleVisibility) {
        return new GraphqlRequestContext(
                actorId,
                actorRole,
                actorId > 0L ? "actor-" + actorId : "",
                "",
                "",
                "",
                List.of(),
                List.of("publishedVisibility"),
                false,
                true,
                articleVisibility,
                0L
        );
    }

    public static GraphqlRequestContext missingArticleVisibility(long actorId, String actorRole) {
        return new GraphqlRequestContext(
                actorId,
                actorRole,
                actorId > 0L ? "actor-" + actorId : "",
                "",
                "",
                "",
                List.of(),
                List.of("publishedVisibility"),
                false,
                false,
                false,
                0L
        );
    }

    public static GraphqlRequestContext introspectionEnabled(long actorId, String actorRole) {
        return new GraphqlRequestContext(
                actorId,
                actorRole,
                actorId > 0L ? "actor-" + actorId : "",
                "",
                "",
                "",
                List.of(),
                List.of(),
                true,
                false,
                false,
                0L
        );
    }

    public boolean contextFilterEnabled(String name) {
        return enabledContextFilters.contains(name);
    }

    /** Returns a standard or caller-supplied value used by metadata-declared context filters. */
    public Object contextValue(String key) {
        if (values.containsKey(key)) return values.get(key);
        return switch (key) {
            case "actorId" -> actorId;
            case "actorRole" -> actorRole;
            case "actorKey" -> actorKey;
            case "tenantId" -> tenantId;
            case "requestId" -> requestId;
            case "articleVisibility" -> hasArticleVisibility ? articleVisibility : null;
            default -> null;
        };
    }

    public GraphqlRequestContext withValues(Map<String, Object> additionalValues) {
        return new GraphqlRequestContext(
                actorId, actorRole, actorKey, tenantId, requestId, idempotencyKey, policyFlags,
                enabledContextFilters, introspectionEnabled, hasArticleVisibility, articleVisibility,
                deadlineEpochMillis, additionalValues);
    }
}
