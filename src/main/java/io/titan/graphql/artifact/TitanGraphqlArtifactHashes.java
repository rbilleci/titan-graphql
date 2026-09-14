package io.titan.graphql.artifact;

public record TitanGraphqlArtifactHashes(
        String validationHash,
        String driftHash,
        String sdlHash,
        String introspectionHash,
        String conformanceHash,
        String generatedSqlHash
) {
    public TitanGraphqlArtifactHashes {
        validationHash = textOrEmpty(validationHash);
        driftHash = textOrEmpty(driftHash);
        sdlHash = textOrEmpty(sdlHash);
        introspectionHash = textOrEmpty(introspectionHash);
        conformanceHash = textOrEmpty(conformanceHash);
        generatedSqlHash = textOrEmpty(generatedSqlHash);
    }

    public static TitanGraphqlArtifactHashes empty() {
        return new TitanGraphqlArtifactHashes("", "", "", "", "", "");
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
