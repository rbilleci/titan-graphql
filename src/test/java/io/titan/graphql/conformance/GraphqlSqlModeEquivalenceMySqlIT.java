package io.titan.graphql.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.titan.runtime.testing.DatabaseTarget;
import io.titan.runtime.testing.EquivalenceOracle;
import io.titan.runtime.testing.TitanTest;
import io.titan.runtime.testing.TitanTestContext;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * MySQL leg of the SQL-mode equivalence proof (completion plan W5.2): the same 97-case
 * conformance corpus as {@link GraphqlSqlModeEquivalenceIT}, executed Java-vs-MySQL.
 *
 * <p>Core's {@code TitanTestExtension} provisions a per-test database on a live
 * Testcontainers {@code mysql:8.4}. The test deploys the MySQL demo DDL
 * ({@code ddl/mysql/titan_graphql_mysql.sql}) and the real packaged MySQL artifacts emitted by
 * {@code titanPackage} ({@code mysql/R__titan_010_runtime.sql} +
 * {@code mysql/R__titan_020_routines.sql}), then compares every corpus case between the
 * in-JVM Java kernel and {@code SELECT public.execute_graphql*(...)} on MySQL as canonical
 * JSON through core's {@link EquivalenceOracle} — exactly the PostgreSQL leg's method.</p>
 *
 * <p><b>Honest accounting at titan HEAD (TG-BLK-012, docs/titan-blocker-register.md):</b>
 * 91 of 97 cases compare strictly. The remaining 6 form a single divergence class — MySQL
 * renders boolean routine values as JSON numbers {@code 1}/{@code 0} where Java and
 * PostgreSQL render {@code true}/{@code false} (core backlog B-10). Those cases are listed
 * in {@link #KNOWN_DIVERGENT_TG_BLK_012} and are asserted to STILL diverge in exactly that
 * boolean-rendering way: the MySQL response must equal the Java response after coercing
 * integer {@code 1}/{@code 0} to booleans only at positions where the Java leg holds a
 * boolean, with at least one such coercion. Any other divergence fails the leg, and so does
 * a tracked case that compares strictly equal — when core delivers B-10, each fixed case
 * flips loudly back to strict comparison by being removed from the allowlist.</p>
 */
@Tag("docker")
@TitanTest(targets = DatabaseTarget.MYSQL)
class GraphqlSqlModeEquivalenceMySqlIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * TG-BLK-012 known-divergence allowlist — now EMPTY. Core's B-10 fix (titan f9e3b43) makes
     * MySQL render boolean values as true/false in text/JSON output, so all six formerly-tracked
     * cases (connection-aliases, generated-order-with-relay-cursor,
     * relation-total-count-with-page-info, relation-after-cursor, visibility-cursor-window,
     * schema-directives) now compare strictly equal and rejoin the strict corpus. The empty
     * allowlist stays as a guard: a returning boolean-parity regression fails the strict
     * comparison loudly rather than silently re-diverging.
     */
    private static final Set<String> KNOWN_DIVERGENT_TG_BLK_012 = Set.of();

    @Test
    void sqlModeMatchesJavaModeAcrossConformanceCorpusOnMySql(TitanTestContext context) throws Exception {
        Connection connection = context.connection(DatabaseTarget.MYSQL);
        DemoBlogSqlDeployment.deployPackagedKernelMySql(connection);

        List<GraphqlSqlModeConformanceCorpus.Case> cases = GraphqlSqlModeConformanceCorpus.cases();
        List<String> failures = new ArrayList<>();
        Set<String> unseenAllowlist = new LinkedHashSet<>(KNOWN_DIVERGENT_TG_BLK_012);
        int strictEquivalent = 0;
        int trackedDivergent = 0;
        for (GraphqlSqlModeConformanceCorpus.Case corpusCase : cases) {
            String label = caseLabel(corpusCase);
            boolean allowlisted = unseenAllowlist.remove(label);
            String javaJson = GraphqlSqlModeConformanceCorpus.executeJavaMode(corpusCase.invocation());
            String sqlJson;
            try {
                sqlJson = GraphqlSqlModeConformanceCorpus.executeSqlMode(connection, corpusCase.invocation());
            } catch (SQLException ex) {
                failures.add(label + "\n  mysql-mode execution failed: " + ex.getMessage());
                continue;
            }

            Object javaTree = parseCanonical(javaJson);
            Object sqlTree = parseCanonical(sqlJson);
            Optional<EquivalenceOracle.Divergence> strict = EquivalenceOracle.compareScalars(javaTree, sqlTree);
            if (strict.isEmpty()) {
                if (allowlisted) {
                    failures.add(label + "\n  TG-BLK-012 allowlist entry compared STRICTLY EQUAL —"
                            + " core's B-10 fix has landed for this case; remove it from"
                            + " KNOWN_DIVERGENT_TG_BLK_012 so it stays under strict comparison.");
                } else {
                    strictEquivalent++;
                }
                continue;
            }
            if (!allowlisted) {
                failures.add(label + "\n  " + strict.orElseThrow().describe().replace("\n", "\n  ")
                        + "\n  java-mode json:  " + javaJson
                        + "\n  mysql-mode json: " + sqlJson);
                continue;
            }

            // TG-BLK-012: the case must diverge in EXACTLY the boolean 1/0 way — equal after
            // coercing MySQL integer 1/0 to booleans at Java-boolean positions, nothing else.
            BooleanRenderingCoercion coercion = new BooleanRenderingCoercion();
            Object coercedSqlTree = coercion.coerce(javaTree, sqlTree);
            Optional<EquivalenceOracle.Divergence> residual =
                    EquivalenceOracle.compareScalars(javaTree, coercedSqlTree);
            if (residual.isPresent() || coercion.coercions == 0) {
                failures.add(label + "\n  TG-BLK-012 allowlist entry diverges in a way the boolean"
                        + " 1/0-vs-true/false class does NOT explain"
                        + (coercion.coercions == 0
                                ? " (no boolean-position 1/0 value found)" : "")
                        + ":\n  " + residual.map(EquivalenceOracle.Divergence::describe)
                                .orElse(strict.orElseThrow().describe()).replace("\n", "\n  ")
                        + "\n  java-mode json:  " + javaJson
                        + "\n  mysql-mode json: " + sqlJson);
                continue;
            }
            trackedDivergent++;
        }

        for (String stale : unseenAllowlist) {
            failures.add(stale + "\n  TG-BLK-012 allowlist entry matched no corpus case — stale entry?");
        }

        System.out.println("[sql-mode-equivalence-mysql] corpus cases compared: " + cases.size()
                + ", strict-equivalent: " + strictEquivalent
                + ", tracked-divergent (TG-BLK-012 boolean rendering): " + trackedDivergent);
        assertTrue(failures.isEmpty(), () -> failures.size() + " of " + cases.size()
                + " corpus cases failed the MySQL equivalence contract (strict for "
                + (cases.size() - KNOWN_DIVERGENT_TG_BLK_012.size())
                + " cases, tracked TG-BLK-012 boolean divergence for "
                + KNOWN_DIVERGENT_TG_BLK_012.size() + "):\n\n"
                + String.join("\n\n", failures));
    }

    private static String caseLabel(GraphqlSqlModeConformanceCorpus.Case corpusCase) {
        return corpusCase.rowId() + " / " + corpusCase.name();
    }

    /**
     * Rewrites the MySQL canonical tree by replacing integer {@code 1}/{@code 0} with
     * {@code true}/{@code false} at exactly the positions where the Java tree holds a
     * boolean, counting every replacement. All other values pass through untouched, so any
     * non-boolean divergence survives into the recomparison.
     */
    private static final class BooleanRenderingCoercion {
        private int coercions;

        Object coerce(Object javaValue, Object sqlValue) {
            if (javaValue instanceof Boolean
                    && (sqlValue instanceof Integer || sqlValue instanceof Long)
                    && (((Number) sqlValue).longValue() == 0L || ((Number) sqlValue).longValue() == 1L)) {
                coercions++;
                return ((Number) sqlValue).longValue() == 1L;
            }
            if (javaValue instanceof Map<?, ?> javaMap && sqlValue instanceof Map<?, ?> sqlMap) {
                Map<Object, Object> coerced = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : sqlMap.entrySet()) {
                    coerced.put(entry.getKey(), coerce(javaMap.get(entry.getKey()), entry.getValue()));
                }
                return coerced;
            }
            if (javaValue instanceof List<?> javaList && sqlValue instanceof List<?> sqlList) {
                List<Object> coerced = new ArrayList<>(sqlList.size());
                for (int i = 0; i < sqlList.size(); i++) {
                    coerced.add(coerce(i < javaList.size() ? javaList.get(i) : null, sqlList.get(i)));
                }
                return coerced;
            }
            return sqlValue;
        }
    }

    private static Object parseCanonical(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode tree = MAPPER.readTree(json);
            return MAPPER.convertValue(tree, Object.class);
        } catch (IOException notJson) {
            return json;
        }
    }
}
