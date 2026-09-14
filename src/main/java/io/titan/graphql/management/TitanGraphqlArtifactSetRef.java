package io.titan.graphql.management;

public record TitanGraphqlArtifactSetRef(
        String id,
        String draftId,
        String semanticHash,
        String validationHash,
        String driftHash,
        String sdlHash,
        String introspectionHash,
        String conformanceHash,
        String generatedSqlHash,
        String generationProfile,
        String createdAt
) {
    public TitanGraphqlArtifactSetRef {
        id = ManagementSupport.requireText(id, "artifactSet.id");
        draftId = ManagementSupport.requireText(draftId, "artifactSet.draftId");
        semanticHash = ManagementSupport.requireText(semanticHash, "artifactSet.semanticHash");
        validationHash = ManagementSupport.textOrEmpty(validationHash);
        driftHash = ManagementSupport.textOrEmpty(driftHash);
        sdlHash = ManagementSupport.textOrEmpty(sdlHash);
        introspectionHash = ManagementSupport.textOrEmpty(introspectionHash);
        conformanceHash = ManagementSupport.textOrEmpty(conformanceHash);
        generatedSqlHash = ManagementSupport.textOrEmpty(generatedSqlHash);
        generationProfile = ManagementSupport.textOrEmpty(generationProfile);
        createdAt = ManagementSupport.textOrEmpty(createdAt);
    }
}
