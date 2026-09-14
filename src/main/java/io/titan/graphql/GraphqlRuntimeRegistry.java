package io.titan.graphql;

final class GraphqlRuntimeRegistry {

    private static final GraphqlModelRuntime ACTIVE_RUNTIME = loadActiveRuntime();
    private static final GraphqlModelRuntime MANAGEMENT_RUNTIME = loadManagementRuntime();

    private GraphqlRuntimeRegistry() {
    }

    static GraphqlModelRuntime activeRuntime() {
        return ACTIVE_RUNTIME;
    }

    static GraphqlModelRuntime managementRuntime() {
        return MANAGEMENT_RUNTIME;
    }

    private static GraphqlModelRuntime loadActiveRuntime() {
        return loadRuntime("io.titan.graphql.demo.blog.DemoBlogGraphqlRuntime", "active GraphQL model runtime");
    }

    private static GraphqlModelRuntime loadManagementRuntime() {
        return loadRuntime(
                "io.titan.graphql.management.TitanGraphqlManagementRuntime",
                "management GraphQL model runtime"
        );
    }

    private static GraphqlModelRuntime loadRuntime(String className, String description) {
        try {
            Class<?> runtimeClass = Class.forName(className);
            return (GraphqlModelRuntime) runtimeClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("failed to load " + description, ex);
        }
    }
}
