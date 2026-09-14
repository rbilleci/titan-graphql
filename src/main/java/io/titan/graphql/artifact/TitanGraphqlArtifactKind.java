package io.titan.graphql.artifact;

public enum TitanGraphqlArtifactKind {
    GENERATED_SCHEMA_SDL("generatedSchemaSdl", "schema.graphql"),
    INTROSPECTION_JSON("introspectionJson", "introspection.json"),
    CONFORMANCE_MATRIX("conformanceMatrix", "conformance.json"),
    GENERATED_SQL("generatedSql", "generated.sql"),
    TITAN_ARTIFACT_METADATA("titanArtifactMetadata", "titan-artifact.json"),
    TITAN_OBJECT_INVENTORY("titanObjectInventory", "titan-object-inventory.json"),
    TITAN_INSTALL_PLAN("titanInstallPlan", "titan-install-plan.json"),
    TITAN_INSTALL_VERIFICATION("titanInstallVerification", "titan-install-verification.json");

    private final String manifestName;
    private final String defaultFileName;

    TitanGraphqlArtifactKind(String manifestName, String defaultFileName) {
        this.manifestName = manifestName;
        this.defaultFileName = defaultFileName;
    }

    public String manifestName() {
        return manifestName;
    }

    public String defaultFileName() {
        return defaultFileName;
    }
}
