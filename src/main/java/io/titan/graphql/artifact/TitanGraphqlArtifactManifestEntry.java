package io.titan.graphql.artifact;

public record TitanGraphqlArtifactManifestEntry(
        TitanGraphqlArtifactKind kind,
        String name,
        boolean enabled,
        String path,
        String hash
) {
    public TitanGraphqlArtifactManifestEntry {
        if (kind == null) {
            throw new IllegalArgumentException("artifact kind is required");
        }
        name = name == null || name.isBlank() ? kind.manifestName() : name;
        path = textOrEmpty(path);
        hash = textOrEmpty(hash);
    }

    public static TitanGraphqlArtifactManifestEntry of(
            TitanGraphqlArtifactKind kind,
            boolean enabled,
            String outputDirectory,
            String hash
    ) {
        return new TitanGraphqlArtifactManifestEntry(
                kind,
                kind.manifestName(),
                enabled,
                outputPath(outputDirectory, kind.defaultFileName()),
                hash
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

    private static String textOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
