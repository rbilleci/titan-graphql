package io.titan.graphql.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;

public final class TitanGraphqlValidationReportRenderer {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private TitanGraphqlValidationReportRenderer() {
    }

    public static String terminalText(TitanGraphqlValidationReport report) {
        if (report == null) {
            throw new IllegalArgumentException("validation report is required");
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Validation report: ")
                .append(report.valid() ? "valid" : "invalid")
                .append(" (")
                .append(summaryText(report))
                .append(", ")
                .append(report.blocksDeployment() ? "blocks deployment" : "deployable")
                .append(")");

        for (TitanGraphqlValidationIssue issue : report.issues()) {
            builder.append(System.lineSeparator())
                    .append(issue.severity().name().toLowerCase())
                    .append(" ")
                    .append(issue.code().name())
                    .append(" ");
            appendLocation(builder, issue);
            builder.append(" - ").append(issue.message());
            for (Map.Entry<String, String> metadata : issue.metadata().entrySet()) {
                builder.append(System.lineSeparator())
                        .append("  ")
                        .append(metadata.getKey())
                        .append(": ")
                        .append(metadata.getValue());
            }
        }
        return builder.toString();
    }

    public static String json(TitanGraphqlValidationReport report) {
        if (report == null) {
            throw new IllegalArgumentException("validation report is required");
        }
        try {
            return JSON.writeValueAsString(toJsonReport(report));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("validation report could not be serialized", ex);
        }
    }

    private static String summaryText(TitanGraphqlValidationReport report) {
        return report.errorCount() + " error" + plural(report.errorCount())
                + ", " + report.warningCount() + " warning" + plural(report.warningCount())
                + ", " + report.infoCount() + " info";
    }

    private static String plural(long count) {
        return count == 1 ? "" : "s";
    }

    private static void appendLocation(StringBuilder builder, TitanGraphqlValidationIssue issue) {
        TitanGraphqlSourceLocation sourceLocation = issue.sourceLocation();
        if (sourceLocation.source().isEmpty() == false && sourceLocation.hasLineColumn()) {
            builder.append("at ")
                    .append(sourceLocation.source())
                    .append(":")
                    .append(sourceLocation.line())
                    .append(":")
                    .append(sourceLocation.column())
                    .append(" ");
        } else if (sourceLocation.source().isEmpty() == false) {
            builder.append("at ").append(sourceLocation.source()).append(" ");
        }
        builder.append(issue.modelPath().displayPath());
    }

    private static JsonReport toJsonReport(TitanGraphqlValidationReport report) {
        return new JsonReport(
                report.valid(),
                report.blocksDeployment(),
                report.errorCount(),
                report.warningCount(),
                report.infoCount(),
                report.issues().stream().map(TitanGraphqlValidationReportRenderer::toJsonIssue).toList()
        );
    }

    private static JsonIssue toJsonIssue(TitanGraphqlValidationIssue issue) {
        return new JsonIssue(
                issue.code().name(),
                issue.severity().name(),
                issue.message(),
                issue.modelPath().displayPath(),
                issue.sourceLocation(),
                issue.blocksDeployment(),
                issue.metadata()
        );
    }

    private record JsonReport(
            boolean valid,
            boolean blocksDeployment,
            long errorCount,
            long warningCount,
            long infoCount,
            List<JsonIssue> issues
    ) {
    }

    private record JsonIssue(
            String code,
            String severity,
            String message,
            String modelPath,
            TitanGraphqlSourceLocation sourceLocation,
            boolean blocksDeployment,
            Map<String, String> metadata
    ) {
    }
}
