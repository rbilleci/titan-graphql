package io.titan.graphql.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TitanGraphqlValidationReportRendererTest {

    @Test
    void rendersEmptyReportForTerminalAndJsonConsumers() {
        TitanGraphqlValidationReport report = TitanGraphqlValidationReport.empty();

        assertEquals(
                "Validation report: valid (0 errors, 0 warnings, 0 info, deployable)",
                TitanGraphqlValidationReportRenderer.terminalText(report)
        );
        assertEquals(
                "{\"blocksDeployment\":false,\"errorCount\":0,\"infoCount\":0,\"issues\":[],"
                        + "\"valid\":true,\"warningCount\":0}",
                TitanGraphqlValidationReportRenderer.json(report)
        );
    }

    @Test
    void rendersIssuesWithLocationsPathsMetadataAndEscapedJson() {
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        metadata.put("target", "Article.author");
        metadata.put("hint", "Use \"User\" type");
        TitanGraphqlValidationIssue warning = new TitanGraphqlValidationIssue(
                TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                TitanGraphqlValidationSeverity.WARNING,
                "Relation target type User is not declared.",
                TitanGraphqlModelPath.of("types", "Article", "relations", "author"),
                TitanGraphqlSourceLocation.at("titan.graphql.yaml", 42, 9),
                false,
                metadata
        );
        TitanGraphqlValidationIssue error = TitanGraphqlValidationIssue.error(
                TitanGraphqlValidationIssueCode.INVALID_BINDING,
                "Root article binds to a missing table.",
                TitanGraphqlModelPath.of("roots", "article"),
                TitanGraphqlSourceLocation.none()
        );
        TitanGraphqlValidationReport report = new TitanGraphqlValidationReport(List.of(warning, error));

        assertEquals(String.join(System.lineSeparator(),
                "Validation report: invalid (1 error, 1 warning, 0 info, blocks deployment)",
                "warning UNKNOWN_REFERENCE at titan.graphql.yaml:42:9 $.types.Article.relations.author"
                        + " - Relation target type User is not declared.",
                "  hint: Use \"User\" type",
                "  target: Article.author",
                "error INVALID_BINDING $.roots.article - Root article binds to a missing table."
        ), TitanGraphqlValidationReportRenderer.terminalText(report));

        String json = TitanGraphqlValidationReportRenderer.json(report);

        assertEquals(
                "{\"blocksDeployment\":true,\"errorCount\":1,\"infoCount\":0,\"issues\":["
                        + "{\"blocksDeployment\":false,\"code\":\"UNKNOWN_REFERENCE\","
                        + "\"message\":\"Relation target type User is not declared.\","
                        + "\"metadata\":{\"hint\":\"Use \\\"User\\\" type\",\"target\":\"Article.author\"},"
                        + "\"modelPath\":\"$.types.Article.relations.author\",\"severity\":\"WARNING\","
                        + "\"sourceLocation\":{\"column\":9,\"line\":42,\"source\":\"titan.graphql.yaml\"}},"
                        + "{\"blocksDeployment\":true,\"code\":\"INVALID_BINDING\","
                        + "\"message\":\"Root article binds to a missing table.\",\"metadata\":{},"
                        + "\"modelPath\":\"$.roots.article\",\"severity\":\"ERROR\","
                        + "\"sourceLocation\":{\"column\":0,\"line\":0,\"source\":\"\"}}],"
                        + "\"valid\":false,\"warningCount\":1}",
                json
        );
    }

    @Test
    void rejectsNullReport() {
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlValidationReportRenderer.terminalText(null));
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlValidationReportRenderer.json(null));
    }

    @Test
    void keepsJsonUsableForTools() {
        String json = TitanGraphqlValidationReportRenderer.json(new TitanGraphqlValidationReport(List.of(
                TitanGraphqlValidationIssue.error(
                        TitanGraphqlValidationIssueCode.MISSING_REQUIRED_FIELD,
                        "metadata.name is required.",
                        TitanGraphqlModelPath.of("metadata", "name"),
                        TitanGraphqlSourceLocation.at("model.yaml", 3, 5)
                )
        )));

        assertTrue(json.contains("\"code\":\"MISSING_REQUIRED_FIELD\""));
        assertTrue(json.contains("\"modelPath\":\"$.metadata.name\""));
        assertTrue(json.contains("\"sourceLocation\":{\"column\":5,\"line\":3,\"source\":\"model.yaml\"}"));
    }
}
