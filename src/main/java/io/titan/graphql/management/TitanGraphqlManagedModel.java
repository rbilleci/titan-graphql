package io.titan.graphql.management;

public record TitanGraphqlManagedModel(
        String id,
        String workspaceId,
        String name,
        String displayName,
        String description,
        String currentDraftId,
        String activeDeploymentId,
        String latestValidationReportId,
        String latestArtifactSetId,
        String createdAt,
        String updatedAt
) {
    public TitanGraphqlManagedModel {
        id = ManagementSupport.requireText(id, "model.id");
        workspaceId = ManagementSupport.requireText(workspaceId, "model.workspaceId");
        name = ManagementSupport.requireText(name, "model.name");
        displayName = ManagementSupport.textOrEmpty(displayName);
        description = ManagementSupport.textOrEmpty(description);
        currentDraftId = ManagementSupport.textOrEmpty(currentDraftId);
        activeDeploymentId = ManagementSupport.textOrEmpty(activeDeploymentId);
        latestValidationReportId = ManagementSupport.textOrEmpty(latestValidationReportId);
        latestArtifactSetId = ManagementSupport.textOrEmpty(latestArtifactSetId);
        createdAt = ManagementSupport.textOrEmpty(createdAt);
        updatedAt = ManagementSupport.textOrEmpty(updatedAt);
    }
}
