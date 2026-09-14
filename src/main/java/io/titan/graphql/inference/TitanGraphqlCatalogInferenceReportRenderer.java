package io.titan.graphql.inference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

final class TitanGraphqlCatalogInferenceReportRenderer {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private TitanGraphqlCatalogInferenceReportRenderer() {
    }

    static String terminalText(TitanGraphqlCatalogInferenceReport report) {
        if (report == null) {
            throw new IllegalArgumentException("inference report is required");
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Inference report: ")
                .append(report.inferredObjects().size()).append(" inferred object")
                .append(plural(report.inferredObjects().size()))
                .append(", ")
                .append(report.skippedObjects().size()).append(" skipped object")
                .append(plural(report.skippedObjects().size()))
                .append(", ")
                .append(report.warnings().size()).append(" warning")
                .append(plural(report.warnings().size()))
                .append(", ")
                .append(report.reviewDecisions().size()).append(" review decision")
                .append(plural(report.reviewDecisions().size()));

        for (TitanGraphqlCatalogInferenceReport.InferredObject object : report.inferredObjects()) {
            builder.append(System.lineSeparator())
                    .append("inferred ")
                    .append(object.kind())
                    .append(" ")
                    .append(object.path());
            if (!object.output().isBlank()) {
                builder.append(" -> ").append(object.output());
            }
            if (!object.reason().isBlank()) {
                builder.append(" - ").append(object.reason());
            }
        }
        for (TitanGraphqlCatalogInferenceReport.SkippedObject object : report.skippedObjects()) {
            builder.append(System.lineSeparator())
                    .append("skipped ")
                    .append(object.kind())
                    .append(" ")
                    .append(object.path())
                    .append(" - ")
                    .append(object.reason());
        }
        for (TitanGraphqlCatalogInferenceReport.Warning warning : report.warnings()) {
            builder.append(System.lineSeparator())
                    .append("warning ")
                    .append(warning.code())
                    .append(" ")
                    .append(warning.path())
                    .append(" - ")
                    .append(warning.message());
        }
        for (TitanGraphqlCatalogInferenceReport.ReviewDecision decision : report.reviewDecisions()) {
            builder.append(System.lineSeparator())
                    .append("review ")
                    .append(decision.code())
                    .append(" ")
                    .append(decision.path())
                    .append(" - ")
                    .append(decision.message());
        }
        return builder.toString();
    }

    static String json(TitanGraphqlCatalogInferenceReport report) {
        if (report == null) {
            throw new IllegalArgumentException("inference report is required");
        }
        try {
            return JSON.writeValueAsString(report);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("inference report could not be serialized", ex);
        }
    }

    private static String plural(long count) {
        return count == 1 ? "" : "s";
    }
}
