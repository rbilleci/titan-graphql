package io.titan.graphql.management;

public record TitanGraphqlDeployment(
        String id,
        String modelId,
        String draftId,
        String artifactSetId,
        String environment,
        DeploymentStatus status,
        String deployedBy,
        String deployedAt,
        String rollbackTargetDeploymentId,
        String runtimeHealthId
) {
    // Renamed from Status under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum DeploymentStatus {
        PENDING,
        DEPLOYING,
        ACTIVE,
        FAILED,
        ROLLED_BACK
    }

    public TitanGraphqlDeployment {
        id = ManagementSupport.requireText(id, "deployment.id");
        modelId = ManagementSupport.requireText(modelId, "deployment.modelId");
        draftId = ManagementSupport.requireText(draftId, "deployment.draftId");
        artifactSetId = ManagementSupport.requireText(artifactSetId, "deployment.artifactSetId");
        environment = ManagementSupport.requireText(environment, "deployment.environment");
        status = status == null ? DeploymentStatus.PENDING : status;
        deployedBy = ManagementSupport.textOrEmpty(deployedBy);
        deployedAt = ManagementSupport.textOrEmpty(deployedAt);
        rollbackTargetDeploymentId = ManagementSupport.textOrEmpty(rollbackTargetDeploymentId);
        runtimeHealthId = ManagementSupport.textOrEmpty(runtimeHealthId);
    }
}
