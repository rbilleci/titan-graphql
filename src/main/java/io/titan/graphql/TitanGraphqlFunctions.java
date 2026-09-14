package io.titan.graphql;

public final class TitanGraphqlFunctions {

    private TitanGraphqlFunctions() {
    }

    public static String executeGraphql(String query, long actorId, String actorRole) {
        return io.titan.graphql.demo.blog.DemoBlogTitanGraphqlFunctions.executeGraphql(query, actorId, actorRole);
    }

    public static String executeGraphqlRequest(String query, String operationName, long actorId, String actorRole) {
        return io.titan.graphql.demo.blog.DemoBlogTitanGraphqlFunctions.executeGraphqlRequest(
                query,
                operationName,
                actorId,
                actorRole
        );
    }

    public static String executeGraphqlRequestWithVariables(
            String query,
            String operationName,
            String variablesJson,
            String extensionsJson,
            long actorId,
            String actorRole
    ) {
        return io.titan.graphql.demo.blog.DemoBlogTitanGraphqlFunctions.executeGraphqlRequestWithVariables(
                query,
                operationName,
                variablesJson,
                extensionsJson,
                actorId,
                actorRole
        );
    }

    public static String executeGraphqlRequestWithContext(
            String query,
            String operationName,
            String variablesJson,
            String extensionsJson,
            long actorId,
            String actorRole,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility,
            boolean enableIntrospection
    ) {
        return io.titan.graphql.demo.blog.DemoBlogTitanGraphqlFunctions.executeGraphqlRequestWithContext(
                query,
                operationName,
                variablesJson,
                extensionsJson,
                actorId,
                actorRole,
                enablePublishedVisibility,
                hasArticleVisibility,
                articleVisibility,
                enableIntrospection
        );
    }

    public static String executeGraphqlRequestWithCompactContext(
            String query,
            String operationName,
            String variablesJson,
            String extensionsJson,
            long actorId,
            String actorRole,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility,
            boolean enableIntrospection,
            String tenantId,
            String requestId,
            String policyFlags,
            String enabledContextFilters,
            long deadlineBudgetMillis
    ) {
        return io.titan.graphql.demo.blog.DemoBlogTitanGraphqlFunctions.executeGraphqlRequestWithCompactContext(
                query,
                operationName,
                variablesJson,
                extensionsJson,
                actorId,
                actorRole,
                enablePublishedVisibility,
                hasArticleVisibility,
                articleVisibility,
                enableIntrospection,
                tenantId,
                requestId,
                policyFlags,
                enabledContextFilters,
                deadlineBudgetMillis
        );
    }

    public static String executeGraphqlWithContext(
            String query,
            long actorId,
            String actorRole,
            boolean enablePublishedVisibility,
            boolean hasArticleVisibility,
            boolean articleVisibility
    ) {
        return io.titan.graphql.demo.blog.DemoBlogTitanGraphqlFunctions.executeGraphqlWithContext(
                query,
                actorId,
                actorRole,
                enablePublishedVisibility,
                hasArticleVisibility,
                articleVisibility
        );
    }

    public static String executeGraphqlWithIntrospection(
            String query,
            long actorId,
            String actorRole,
            boolean enableIntrospection
    ) {
        return io.titan.graphql.demo.blog.DemoBlogTitanGraphqlFunctions.executeGraphqlWithIntrospection(
                query,
                actorId,
                actorRole,
                enableIntrospection
        );
    }

    public static GraphqlExecution executeGraphqlWithPlan(String query, long actorId, String actorRole) {
        return GraphqlRuntimeRegistry.activeRuntime().executeWithPlan(
                GraphqlRequest.query(query),
                GraphqlRequestContext.legacy(actorId, actorRole)
        );
    }
}
