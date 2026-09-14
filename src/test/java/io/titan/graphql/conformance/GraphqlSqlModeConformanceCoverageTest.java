package io.titan.graphql.conformance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Docker-free guard binding the conformance matrix to the SQL-mode corpus.
 *
 * <p>Every ACCEPTED row of {@code docs/query-contract-conformance.md} must be covered by at
 * least one {@link GraphqlSqlModeConformanceCorpus} case (executed against live PostgreSQL by
 * {@code GraphqlSqlModeEquivalenceIT}), except rows explicitly listed in
 * {@link GraphqlSqlModeConformanceCorpus#ROWS_WITHOUT_EXECUTABLE_BEHAVIOR} with a documented
 * reason. Conversely, every corpus case must reference a real matrix row — so the matrix's
 * SQL-mode evidence column cannot silently drift from what the IT actually executes.</p>
 */
class GraphqlSqlModeConformanceCoverageTest {

    @Test
    void everyAcceptedMatrixRowHasSqlModeCorpusCoverage() throws IOException {
        Map<String, String> classifications = matrixClassifications();
        Set<String> coveredRows = corpusRowIds();

        for (Map.Entry<String, String> entry : classifications.entrySet()) {
            if ("ACCEPTED".equals(entry.getValue()) == false) {
                continue;
            }
            if (GraphqlSqlModeConformanceCorpus.ROWS_WITHOUT_EXECUTABLE_BEHAVIOR.contains(entry.getKey())) {
                continue;
            }
            assertTrue(coveredRows.contains(entry.getKey()),
                    "ACCEPTED matrix row " + entry.getKey()
                            + " has no SQL-mode corpus case; add one to GraphqlSqlModeConformanceCorpus"
                            + " or demote the row with a documented reason");
        }
    }

    @Test
    void everyCorpusCaseReferencesAKnownMatrixRow() throws IOException {
        Map<String, String> classifications = matrixClassifications();

        for (String rowId : corpusRowIds()) {
            assertTrue(classifications.containsKey(rowId),
                    "corpus case references unknown conformance matrix row " + rowId);
        }
    }

    @Test
    void exemptRowsAreNotSecretlyExecutable() throws IOException {
        Map<String, String> classifications = matrixClassifications();
        Set<String> coveredRows = corpusRowIds();

        for (String exempt : GraphqlSqlModeConformanceCorpus.ROWS_WITHOUT_EXECUTABLE_BEHAVIOR) {
            assertTrue(classifications.containsKey(exempt),
                    "exempt row " + exempt + " is not in the conformance matrix");
            assertFalse(coveredRows.contains(exempt),
                    "row " + exempt + " is exempt from corpus coverage but has corpus cases;"
                            + " remove the exemption");
        }
    }

    @Test
    void corpusCaseNamesAreUniquePerRow() {
        Set<String> seen = new LinkedHashSet<>();
        for (GraphqlSqlModeConformanceCorpus.Case corpusCase : GraphqlSqlModeConformanceCorpus.cases()) {
            assertTrue(seen.add(corpusCase.rowId() + "/" + corpusCase.name()),
                    "duplicate corpus case " + corpusCase.rowId() + "/" + corpusCase.name());
        }
    }

    private static Set<String> corpusRowIds() {
        Set<String> rows = new LinkedHashSet<>();
        for (GraphqlSqlModeConformanceCorpus.Case corpusCase : GraphqlSqlModeConformanceCorpus.cases()) {
            rows.add(corpusCase.rowId());
        }
        return rows;
    }

    /** Same matrix parsing as {@code GraphqlConformanceMatrixTest}. */
    private static Map<String, String> matrixClassifications() throws IOException {
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
