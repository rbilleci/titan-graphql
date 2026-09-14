package io.titan.graphql.artifact;

public record TitanGraphqlGeneratedArtifact(
        TitanGraphqlArtifactKind kind,
        String path,
        String content,
        String hash
) {
    public TitanGraphqlGeneratedArtifact {
        if (kind == null) {
            throw new IllegalArgumentException("artifact kind is required");
        }
        path = textOrEmpty(path);
        content = textOrEmpty(content);
        hash = textOrEmpty(hash);
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
