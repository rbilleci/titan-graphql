package io.titan.graphql.management;

import io.titan.graphql.artifact.TitanGraphqlArtifactSet;

public record TitanGraphqlPreviewBuildManifest(
        String id,
        String modelId,
        String draftId,
        String artifactSetId,
        String artifactManifestPath,
        String artifactManifestHash,
        String environment,
        TitanGraphqlPreviewBuild.PreviewBuildStatus status,
        Endpoint endpoint,
        Expiration expiration,
        String schemaHash,
        String createdBy,
        String createdAt
) {
    public record Endpoint(
            String graphqlEndpoint,
            String consoleUrl,
            boolean unstable
    ) {
        public Endpoint {
            graphqlEndpoint = ManagementSupport.textOrEmpty(graphqlEndpoint);
            consoleUrl = ManagementSupport.textOrEmpty(consoleUrl);
        }
    }

    public record Expiration(
            String expiresAt,
            boolean expires
    ) {
        public Expiration {
            expiresAt = ManagementSupport.textOrEmpty(expiresAt);
        }
    }

    public TitanGraphqlPreviewBuildManifest {
        id = ManagementSupport.requireText(id, "previewBuildManifest.id");
        modelId = ManagementSupport.requireText(modelId, "previewBuildManifest.modelId");
        draftId = ManagementSupport.requireText(draftId, "previewBuildManifest.draftId");
        artifactSetId = ManagementSupport.requireText(artifactSetId, "previewBuildManifest.artifactSetId");
        artifactManifestPath = ManagementSupport.textOrEmpty(artifactManifestPath);
        artifactManifestHash = ManagementSupport.textOrEmpty(artifactManifestHash);
        environment = ManagementSupport.requireText(environment, "previewBuildManifest.environment");
        status = status == null ? TitanGraphqlPreviewBuild.PreviewBuildStatus.CREATING : status;
        endpoint = endpoint == null ? new Endpoint("", "", true) : endpoint;
        expiration = expiration == null ? new Expiration("", false) : expiration;
        schemaHash = ManagementSupport.textOrEmpty(schemaHash);
        createdBy = ManagementSupport.textOrEmpty(createdBy);
        createdAt = ManagementSupport.textOrEmpty(createdAt);
    }

    public static TitanGraphqlPreviewBuildManifest fromPreviewBuild(
            TitanGraphqlPreviewBuild previewBuild,
            TitanGraphqlArtifactSet artifactSet
    ) {
        if (previewBuild == null) {
            throw new IllegalArgumentException("preview build is required");
        }
        if (artifactSet == null) {
            throw new IllegalArgumentException("artifact set is required");
        }
        if (!previewBuild.artifactSetId().equals(artifactSet.id())) {
            throw new IllegalArgumentException("preview build artifact set does not match manifest artifact set");
        }
        if (!previewBuild.draftId().equals(artifactSet.draftId())) {
            throw new IllegalArgumentException("preview build draft does not match manifest artifact set");
        }
        String artifactManifestPath = previewBuild.artifactManifestPath().isBlank()
                ? outputPath(artifactSet.outputDirectory(), "manifest.json")
                : previewBuild.artifactManifestPath();
        return new TitanGraphqlPreviewBuildManifest(
                previewBuild.id(),
                previewBuild.modelId(),
                previewBuild.draftId(),
                previewBuild.artifactSetId(),
                artifactManifestPath,
                previewBuild.artifactManifestHash(),
                previewBuild.environment(),
                previewBuild.status(),
                new Endpoint(previewBuild.previewEndpoint(), previewBuild.previewConsoleUrl(), true),
                new Expiration(previewBuild.expiresAt(), !previewBuild.expiresAt().isBlank()),
                previewBuild.schemaHash(),
                previewBuild.createdBy(),
                previewBuild.createdAt()
        );
    }

    public static TitanGraphqlPreviewBuildManifest fromVerifiedPreviewBuild(
            TitanGraphqlPreviewBuild previewBuild,
            TitanGraphqlArtifactSetRef artifactSet,
            String artifactManifestPath,
            String artifactManifestHash
    ) {
        if (previewBuild == null) {
            throw new IllegalArgumentException("preview build is required");
        }
        if (artifactSet == null) {
            throw new IllegalArgumentException("artifact set is required");
        }
        if (!previewBuild.artifactSetId().equals(artifactSet.id())) {
            throw new IllegalArgumentException("preview build artifact set does not match manifest artifact set");
        }
        if (!previewBuild.draftId().equals(artifactSet.draftId())) {
            throw new IllegalArgumentException("preview build draft does not match manifest artifact set");
        }
        return new TitanGraphqlPreviewBuildManifest(
                previewBuild.id(),
                previewBuild.modelId(),
                previewBuild.draftId(),
                previewBuild.artifactSetId(),
                ManagementSupport.requireText(artifactManifestPath, "preview build artifact manifest path"),
                ManagementSupport.requireText(artifactManifestHash, "preview build artifact manifest hash"),
                previewBuild.environment(),
                previewBuild.status(),
                new Endpoint(previewBuild.previewEndpoint(), previewBuild.previewConsoleUrl(), true),
                new Expiration(previewBuild.expiresAt(), !previewBuild.expiresAt().isBlank()),
                previewBuild.schemaHash(),
                previewBuild.createdBy(),
                previewBuild.createdAt()
        );
    }

    private static String outputPath(String outputDirectory, String fileName) {
        if (outputDirectory == null || outputDirectory.isBlank()) {
            return fileName;
        }
        String normalized = outputDirectory.endsWith("/")
                ? outputDirectory.substring(0, outputDirectory.length() - 1)
                : outputDirectory;
        return normalized + "/" + fileName;
    }
}
