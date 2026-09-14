package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraphqlConformanceMatrixTest {

    @Test
    void matrixClassifiesKeyQueryContractRows() throws IOException {
        Map<String, String> classifications = classifications();

        assertEquals("ACCEPTED", classifications.get("QC1-OPERATIONS-QUERY"));
        assertEquals("ACCEPTED", classifications.get("QC1-OPERATIONS-SELECTED"));
        assertEquals("ACCEPTED", classifications.get("QC1-REQUEST-ENVELOPE"));
        assertEquals("ACCEPTED", classifications.get("QC2-ERROR-SHAPE"));
        assertEquals("ACCEPTED", classifications.get("QC3-VARIABLES-SCALAR"));
        assertEquals("ACCEPTED", classifications.get("QC3-INPUT-OBJECTS"));
        assertEquals("ACCEPTED", classifications.get("QC4-ALIASES"));
        assertEquals("ACCEPTED", classifications.get("QC5-FRAGMENTS"));
        assertEquals("ACCEPTED", classifications.get("QC6-FILTER-SCALAR"));
        assertEquals("ACCEPTED", classifications.get("QC6-SORT-CURSOR"));
        assertEquals("ACCEPTED", classifications.get("QC7-TOTAL-COUNT-ROOT"));
        assertEquals("ACCEPTED", classifications.get("QC8-COMPUTED-FILTER-SORT"));
        assertEquals("ACCEPTED", classifications.get("QC9-CONTEXT-FILTER"));
        assertEquals("ACCEPTED", classifications.get("QC10-INTROSPECTION-STABLE"));
        assertEquals("ACCEPTED", classifications.get("QC10-INTROSPECTION-FIELD-ARGS"));
        assertEquals("ACCEPTED", classifications.get("QC10-INTROSPECTION-TYPE-WRAPPERS"));
        assertEquals("ACCEPTED", classifications.get("QC10-INTROSPECTION-INPUT-FIELDS"));
        assertEquals("ACCEPTED", classifications.get("QC10-INTROSPECTION-ENUM-VALUES"));
        assertEquals("ACCEPTED", classifications.get("QC10-INTROSPECTION-INCLUDE-DEPRECATED"));
        assertEquals("ACCEPTED", classifications.get("QC10-INTROSPECTION-INPUT-INCLUDE-DEPRECATED"));
        assertEquals("ACCEPTED", classifications.get("QC10-INTROSPECTION-DEFAULT-VALUES"));
        assertEquals("ACCEPTED", classifications.get("QC10-INTROSPECTION-DESCRIPTIONS"));
        assertEquals("ACCEPTED", classifications.get("QC10-INTROSPECTION-DEPRECATIONS"));
        assertEquals("ACCEPTED", classifications.get("QC10-INTROSPECTION-INPUT-DEPRECATIONS"));
        assertEquals("ACCEPTED", classifications.get("QC10-INTROSPECTION-DIRECTIVES"));
        assertEquals("ACCEPTED", classifications.get("QC10-INTROSPECTION-DISABLE"));
        assertEquals("ACCEPTED", classifications.get("QC11-QUARKUS-POST"));
        assertEquals("ACCEPTED", classifications.get("QC11-QUARKUS-GET"));
        assertEquals("OUT_OF_SCOPE", classifications.get("OOS-MUTATION-EXECUTION"));

        assertTrue(classifications.containsValue("ACCEPTED"));
        assertTrue(classifications.containsValue("OUT_OF_SCOPE"));
    }

    @Test
    void matrixUsesOnlyKnownClassificationValues() throws IOException {
        for (Map.Entry<String, String> entry : classifications().entrySet()) {
            assertTrue(
                    entry.getValue().equals("ACCEPTED")
                            || entry.getValue().equals("JAVA_ONLY")
                            || entry.getValue().equals("PENDING")
                            || entry.getValue().equals("OUT_OF_SCOPE"),
                    "unexpected classification for " + entry.getKey() + ": " + entry.getValue()
            );
        }
    }

    private static Map<String, String> classifications() throws IOException {
        String markdown = Files.readString(Path.of("docs/query-contract-conformance.md"));
        Map<String, String> classifications = new LinkedHashMap<>();
        for (String line : markdown.split("\\R")) {
            if (line.startsWith("| QC") || line.startsWith("| OOS")) {
                String[] cells = line.split("\\|", -1);
                if (cells.length >= 6) {
                    classifications.put(cells[1].trim(), cells[4].trim());
                }
            }
        }
        return classifications;
    }
}
