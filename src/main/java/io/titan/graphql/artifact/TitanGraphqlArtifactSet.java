package io.titan.graphql.artifact;

import io.titan.graphql.model.TitanGraphqlArtifactOptions;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import java.util.List;

public record TitanGraphqlArtifactSet(
        String id,
        String draftId,
        String modelName,
        String modelVersion,
        String semanticHash,
        String validationHash,
        String driftHash,
        String sdlHash,
        String introspectionHash,
        String conformanceHash,
        String generatedSqlHash,
        String outputDirectory,
        String generationProfile,
        String createdAt,
        List<TitanGraphqlArtifactManifestEntry> artifacts
) {
    public TitanGraphqlArtifactSet {
        id = requireText(id, "artifactSet.id");
        draftId = requireText(draftId, "artifactSet.draftId");
        modelName = requireText(modelName, "artifactSet.modelName");
        modelVersion = textOrEmpty(modelVersion);
        semanticHash = requireText(semanticHash, "artifactSet.semanticHash");
        validationHash = textOrEmpty(validationHash);
        driftHash = textOrEmpty(driftHash);
        sdlHash = textOrEmpty(sdlHash);
        introspectionHash = textOrEmpty(introspectionHash);
        conformanceHash = textOrEmpty(conformanceHash);
        generatedSqlHash = textOrEmpty(generatedSqlHash);
        outputDirectory = textOrEmpty(outputDirectory);
        generationProfile = textOrEmpty(generationProfile);
        createdAt = textOrEmpty(createdAt);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    public static TitanGraphqlArtifactSet fromModelDocument(
            String id,
            String draftId,
            TitanGraphqlModelDocument document,
            TitanGraphqlArtifactHashes hashes,
            String generationProfile,
            String createdAt
    ) {
        if (document == null) {
            throw new IllegalArgumentException("model document is required");
        }
        TitanGraphqlArtifactHashes artifactHashes = hashes == null ? TitanGraphqlArtifactHashes.empty() : hashes;
        TitanGraphqlArtifactOptions options = document.artifacts();
        String outputDirectory = options.outputDirectory();
        return new TitanGraphqlArtifactSet(
                id,
                draftId,
                document.metadata().name(),
                document.metadata().version(),
                TitanGraphqlModelDocumentJson.semanticHash(document),
                artifactHashes.validationHash(),
                artifactHashes.driftHash(),
                artifactHashes.sdlHash(),
                artifactHashes.introspectionHash(),
                artifactHashes.conformanceHash(),
                artifactHashes.generatedSqlHash(),
                outputDirectory,
                generationProfile,
                createdAt,
                List.of(
                        TitanGraphqlArtifactManifestEntry.of(
                                TitanGraphqlArtifactKind.GENERATED_SCHEMA_SDL,
                                options.generateSdl(),
                                outputDirectory,
                                artifactHashes.sdlHash()
                        ),
                        TitanGraphqlArtifactManifestEntry.of(
                                TitanGraphqlArtifactKind.INTROSPECTION_JSON,
                                options.generateIntrospection(),
                                outputDirectory,
                                artifactHashes.introspectionHash()
                        ),
                        TitanGraphqlArtifactManifestEntry.of(
                                TitanGraphqlArtifactKind.CONFORMANCE_MATRIX,
                                options.generateConformance(),
                                outputDirectory,
                                artifactHashes.conformanceHash()
                        ),
                        TitanGraphqlArtifactManifestEntry.of(
                                TitanGraphqlArtifactKind.GENERATED_SQL,
                                options.generateSql(),
                                outputDirectory,
                                artifactHashes.generatedSqlHash()
                        )
                )
        );
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
