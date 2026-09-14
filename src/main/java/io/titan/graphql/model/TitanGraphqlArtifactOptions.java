package io.titan.graphql.model;

public record TitanGraphqlArtifactOptions(
        boolean generateSdl,
        boolean generateIntrospection,
        boolean generateConformance,
        boolean generateSql,
        String outputDirectory
) {
    public TitanGraphqlArtifactOptions {
        outputDirectory = ModelDocumentSupport.textOrEmpty(outputDirectory);
    }

    public static TitanGraphqlArtifactOptions defaults() {
        return new TitanGraphqlArtifactOptions(true, true, true, true, "build/generated/titan-graphql");
    }
}
