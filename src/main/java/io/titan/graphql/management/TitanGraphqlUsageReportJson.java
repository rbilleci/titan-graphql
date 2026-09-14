package io.titan.graphql.management;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

public final class TitanGraphqlUsageReportJson {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private TitanGraphqlUsageReportJson() {
    }

    public static String reportJson(TitanGraphqlUsageReport report) {
        if (report == null) {
            throw new IllegalArgumentException("usage report is required");
        }
        try {
            return JSON.writeValueAsString(report);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("usage report could not be serialized", ex);
        }
    }
}
