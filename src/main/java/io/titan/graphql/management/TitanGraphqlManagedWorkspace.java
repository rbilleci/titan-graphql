package io.titan.graphql.management;

public record TitanGraphqlManagedWorkspace(
        String id,
        String name,
        String description,
        String defaultEnvironment,
        String createdAt,
        String updatedAt
) {
    public TitanGraphqlManagedWorkspace {
        id = ManagementSupport.requireText(id, "workspace.id");
        name = ManagementSupport.requireText(name, "workspace.name");
        description = ManagementSupport.textOrEmpty(description);
        defaultEnvironment = ManagementSupport.textOrEmpty(defaultEnvironment);
        createdAt = ManagementSupport.textOrEmpty(createdAt);
        updatedAt = ManagementSupport.textOrEmpty(updatedAt);
    }
}
