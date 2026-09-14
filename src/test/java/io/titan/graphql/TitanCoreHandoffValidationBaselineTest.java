package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TitanCoreHandoffValidationBaselineTest {

    @Test
    void baselineDocumentNamesCurrentGraphqlOwnedBoundaries() throws IOException {
        String baseline = Files.readString(Path.of("docs/titan-core-handoff-validation.md"));

        assertTrue(baseline.contains("GraphqlAdminHttpResource"));
        assertTrue(baseline.contains("X-Titan-Management-Actor-Role"));
        assertTrue(baseline.contains("TitanGraphqlInMemoryManagementStore"));
        assertTrue(baseline.contains("TitanGraphqlGeneratedArtifactWorkflow"));
        assertTrue(baseline.contains("TITAN-E001"));
    }

    @Test
    void mutationRuntimeRowsPromoteOnlyTheBoundedManagementSurface() throws IOException {
        Map<String, String> classifications = mutationRuntimeClassifications();

        assertEquals("ACCEPTED", classifications.get("DXR8-MUTATION-PARSE"));
        assertEquals("ACCEPTED", classifications.get("DXR8-MUTATION-DESCRIPTOR"));
        assertEquals("ACCEPTED", classifications.get("DXR8-MUTATION-EXECUTOR"));
        assertEquals("ACCEPTED", classifications.get("DXR8-MUTATION-INPUTS"));
        assertEquals("ACCEPTED", classifications.get("DXR8-MUTATION-PAYLOADS"));
        assertEquals("ACCEPTED", classifications.get("DXR8-MUTATION-AUTHZ"));
        assertEquals("ACCEPTED", classifications.get("DXR8-MUTATION-AUDIT"));
        assertEquals("ACCEPTED", classifications.get("DXR8-MUTATION-TX"));
        assertEquals("ACCEPTED", classifications.get("DXR8-MUTATION-GENERATED-SCHEMA"));
        assertEquals("ACCEPTED", classifications.get("DXR8-MUTATION-LOWERED"));
        assertEquals("OUT_OF_SCOPE", classifications.get("DXR8-APP-CRUD"));
        assertEquals("OUT_OF_SCOPE", classifications.get("DXR8-NESTED-WRITES"));

        String boundary = Files.readString(Path.of("docs/mutation-runtime-lowering-boundary.md"));
        assertTrue(boundary.contains("`TG-HANDOFF-M6.1` closes the current handoff"));
        assertTrue(boundary.contains("This does not promote application `/graphql` mutation"));
    }

    @Test
    void closedGapLedgerDoesNotDeclareNewTitanCoreGap() throws IOException {
        String ledger = Files.readString(Path.of("docs/titan-core-gap-ledger.md"));

        assertTrue(ledger.contains("There is no current `TITAN-GQL-GAP-007`"));
        assertTrue(ledger.contains("Titan GraphQL Handoff Consumption Status"));
        assertFalse(ledger.contains("## TITAN-GQL-GAP-007"));
    }

    @Test
    void roadmapPointsToExecutableBaselineGate() throws IOException {
        String roadmap = Files.readString(Path.of("docs/titan-core-handoff-roadmap.md"));
        String baseline = Files.readString(Path.of("docs/titan-core-handoff-validation.md"));

        assertTrue(roadmap.contains("docs/titan-core-handoff-validation.md"));
        assertTrue(baseline.contains("./gradlew test --tests io.titan.graphql.GraphqlRuntimeBoundaryTest"));
        assertTrue(baseline.contains("./gradlew test --tests io.titan.graphql.TitanCoreHandoffValidationBaselineTest"));
        assertTrue(baseline.contains("git diff --check"));
        assertTrue(baseline.contains("git diff --cached --check"));
        assertTrue(baseline.contains("conformance promotion and handoff closeout slice"));
    }

    @Test
    void uiHandoffDocsKeepProductExpansionDistinctFromAcceptedBackendEvidence() throws IOException {
        String workflows = Files.readString(Path.of("docs/ui/workflows.md"));
        String states = Files.readString(Path.of("docs/ui/state-model.md"));

        assertTrue(workflows.contains("Backend handoff status from `TG-HANDOFF-M6.1`"));
        assertTrue(workflows.contains("Application `/graphql` writes"));
        assertTrue(states.contains("Titan GAP-005 artifact verification"));
        assertTrue(states.contains("Titan GAP-006"));
    }

    private static Map<String, String> mutationRuntimeClassifications() throws IOException {
        String markdown = Files.readString(Path.of("docs/mutation-runtime-lowering-boundary.md"));
        Map<String, String> classifications = new LinkedHashMap<>();
        for (String line : markdown.split("\\R")) {
            if (line.startsWith("| DXR8-")) {
                String[] cells = line.split("\\|", -1);
                if (cells.length >= 6) {
                    classifications.put(cells[1].trim(), cells[4].trim());
                }
            }
        }
        return classifications;
    }
}
