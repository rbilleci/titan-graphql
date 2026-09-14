package io.titan.graphql.artifact;

import java.util.List;

public record TitanGraphqlGeneratedArtifactSet(
        TitanGraphqlArtifactSet manifest,
        List<TitanGraphqlGeneratedArtifact> artifacts
) {
    public TitanGraphqlGeneratedArtifactSet {
        if (manifest == null) {
            throw new IllegalArgumentException("artifact manifest is required");
        }
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    public TitanGraphqlGeneratedArtifact artifact(TitanGraphqlArtifactKind kind) {
        for (TitanGraphqlGeneratedArtifact artifact : artifacts) {
            if (artifact.kind() == kind) {
                return artifact;
            }
        }
        return null;
    }
}
