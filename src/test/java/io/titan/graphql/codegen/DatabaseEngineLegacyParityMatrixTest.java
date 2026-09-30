package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class DatabaseEngineLegacyParityMatrixTest {

    private static final Pattern CONTRACT_ID = Pattern.compile(
            "(?:QC[0-9]+|APP|OOS)-[A-Z0-9-]+");

    @Test
    void m5MapClassifiesEveryLegacyContractExactlyOnce() throws Exception {
        String legacy = Files.readString(Path.of("docs/query-contract-conformance.md"));
        String parity = Files.readString(Path.of("docs/database-engine-m5-parity.md"));
        Set<String> legacyIds = new LinkedHashSet<>();
        for (String line : legacy.split("\\R")) {
            if (line.startsWith("| QC") || line.startsWith("| APP") || line.startsWith("| OOS")) {
                Matcher id = CONTRACT_ID.matcher(line);
                if (id.find()) {
                    legacyIds.add(id.group());
                }
            }
        }
        List<String> mapped = new ArrayList<>();
        for (String line : parity.split("\\R")) {
            if (line.startsWith("| `QC") || line.startsWith("| `APP") || line.startsWith("| `OOS")) {
                Matcher ids = CONTRACT_ID.matcher(line.substring(0, line.indexOf('|', 1)));
                while (ids.find()) {
                    mapped.add(ids.group());
                }
            }
        }
        assertEquals(legacyIds, new LinkedHashSet<>(mapped));
        assertEquals(legacyIds.size(), mapped.size(), "a legacy contract must appear in one M5 row");
    }
}
