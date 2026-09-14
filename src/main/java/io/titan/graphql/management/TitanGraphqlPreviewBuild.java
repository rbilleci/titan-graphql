package io.titan.graphql.management;

public record TitanGraphqlPreviewBuild(
        String id,
        String modelId,
        String draftId,
        String artifactSetId,
        String environment,
        PreviewBuildStatus status,
        String previewEndpoint,
        String previewConsoleUrl,
        String schemaHash,
        String artifactManifestPath,
        String artifactManifestHash,
        String operationRegistryId,
        String expiresAt,
        String createdBy,
        String createdAt
) {
    // Renamed from Status under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum PreviewBuildStatus {
        CREATING,
        READY,
        FAILED,
        EXPIRED,
        PROMOTED
    }

    public TitanGraphqlPreviewBuild {
        id = ManagementSupport.requireText(id, "previewBuild.id");
        modelId = ManagementSupport.requireText(modelId, "previewBuild.modelId");
        draftId = ManagementSupport.requireText(draftId, "previewBuild.draftId");
        artifactSetId = ManagementSupport.requireText(artifactSetId, "previewBuild.artifactSetId");
        environment = ManagementSupport.requireText(environment, "previewBuild.environment");
        status = status == null ? PreviewBuildStatus.CREATING : status;
        previewEndpoint = ManagementSupport.textOrEmpty(previewEndpoint);
        previewConsoleUrl = ManagementSupport.textOrEmpty(previewConsoleUrl);
        schemaHash = ManagementSupport.textOrEmpty(schemaHash);
        artifactManifestPath = ManagementSupport.textOrEmpty(artifactManifestPath);
        artifactManifestHash = ManagementSupport.textOrEmpty(artifactManifestHash);
        operationRegistryId = ManagementSupport.textOrEmpty(operationRegistryId);
        expiresAt = ManagementSupport.textOrEmpty(expiresAt);
        createdBy = ManagementSupport.textOrEmpty(createdBy);
        createdAt = ManagementSupport.textOrEmpty(createdAt);
    }
}
