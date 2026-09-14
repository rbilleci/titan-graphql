package io.titan.graphql.management;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

public final class TitanGraphqlPreviewContractTestReportJson {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private TitanGraphqlPreviewContractTestReportJson() {
    }

    public static String reportJson(TitanGraphqlPreviewContractTestReport report) {
        if (report == null) {
            throw new IllegalArgumentException("preview contract test report is required");
        }
        try {
            return JSON.writeValueAsString(report);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("preview contract test report could not be serialized", ex);
        }
    }
}
