package io.titan.graphql.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class TitanGraphqlValidationReportTest {

    @Test
    void preservesDeterministicIssueContents() {
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        metadata.put("target", "Article.author");
        metadata.put("source", "Article.authorId");

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
                TitanGraphqlSourceLocation.at("titan.graphql.yaml", 12, 5)
        );

        TitanGraphqlValidationReport report = new TitanGraphqlValidationReport(List.of(warning, error));

        assertFalse(report.valid());
        assertTrue(report.blocksDeployment());
        assertEquals(1, report.warningCount());
        assertEquals(1, report.errorCount());
        assertEquals(0, report.infoCount());
        assertEquals(List.of(warning, error), report.issues());
        assertEquals("$.types.Article.relations.author", warning.modelPath().displayPath());
        assertEquals(List.of("source", "target"), List.copyOf(warning.metadata().keySet()));
        assertTrue(warning.sourceLocation().hasLineColumn());
    }

    @Test
    void emptyReportIsValidAndNonBlocking() {
        TitanGraphqlValidationReport report = TitanGraphqlValidationReport.empty();

        assertTrue(report.valid());
        assertFalse(report.blocksDeployment());
        assertTrue(report.issues().isEmpty());
    }

    @Test
    void copiesCollectionsForStableReportValues() {
        List<TitanGraphqlValidationIssue> issues = new java.util.ArrayList<>();
        issues.add(TitanGraphqlValidationIssue.error(
                TitanGraphqlValidationIssueCode.MISSING_REQUIRED_FIELD,
                "metadata.name is required.",
                TitanGraphqlModelPath.of("metadata", "name"),
                TitanGraphqlSourceLocation.none()
        ));
        TitanGraphqlValidationReport report = new TitanGraphqlValidationReport(issues);
        issues.clear();

        assertEquals(1, report.issues().size());
        assertThrows(UnsupportedOperationException.class, () -> report.issues().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.issues().get(0).metadata().put("x", "y"));
    }

    @Test
    void rejectsInvalidLocationsPathsAndMetadata() {
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlModelPath.of("roots", ""));
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlSourceLocation.at("source", 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new TitanGraphqlValidationIssue(
                TitanGraphqlValidationIssueCode.INTERNAL_ERROR,
                TitanGraphqlValidationSeverity.ERROR,
                "message",
                TitanGraphqlModelPath.root(),
                TitanGraphqlSourceLocation.none(),
                true,
                Map.of("", "value")
        ));
    }
}
