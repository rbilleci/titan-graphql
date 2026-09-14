package io.titan.graphql.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Comparator;

public final class TitanGraphqlArtifactManifestJson {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private TitanGraphqlArtifactManifestJson() {
    }

    public static String manifestJson(TitanGraphqlArtifactSet artifactSet) {
        if (artifactSet == null) {
            throw new IllegalArgumentException("artifact set is required");
        }
        try {
            return JSON.writeValueAsString(normalize(artifactSet));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("artifact manifest could not be serialized", ex);
        }
    }

    private static TitanGraphqlArtifactSet normalize(TitanGraphqlArtifactSet artifactSet) {
        return new TitanGraphqlArtifactSet(
                artifactSet.id(),
                artifactSet.draftId(),
                artifactSet.modelName(),
                artifactSet.modelVersion(),
                artifactSet.semanticHash(),
                artifactSet.validationHash(),
                artifactSet.driftHash(),
                artifactSet.sdlHash(),
                artifactSet.introspectionHash(),
                artifactSet.conformanceHash(),
                artifactSet.generatedSqlHash(),
                artifactSet.outputDirectory(),
                artifactSet.generationProfile(),
                artifactSet.createdAt(),
                artifactSet.artifacts().stream()
                        .sorted(Comparator.comparing(
                                entry -> entry.kind().manifestName()))
                        .toList()
        );
    }
}
