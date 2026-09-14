package io.titan.graphql.management;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

public final class TitanGraphqlPreviewBuildManifestJson {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private TitanGraphqlPreviewBuildManifestJson() {
    }

    public static String manifestJson(TitanGraphqlPreviewBuildManifest manifest) {
        if (manifest == null) {
            throw new IllegalArgumentException("preview build manifest is required");
        }
        try {
            return JSON.writeValueAsString(manifest);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("preview build manifest could not be serialized", ex);
        }
    }
}
