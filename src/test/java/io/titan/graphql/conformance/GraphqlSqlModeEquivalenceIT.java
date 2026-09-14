package io.titan.graphql.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.titan.graphql.sqlmode.GraphqlSqlEntryPointDispatch;
import io.titan.runtime.testing.DatabaseTarget;
import io.titan.runtime.testing.EquivalenceOracle;
import io.titan.runtime.testing.TitanTest;
import io.titan.runtime.testing.TitanTestContext;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The product's finish line (completion plan W2): automated SQL-mode equivalence proof.
 *
 * <p>Per test, core's {@code TitanTestExtension} provisions a fresh database on a live
 * Testcontainers PostgreSQL. The test then deploys the real packaged artifacts emitted by
 * {@code titanPackage} ({@code R__titan_010_runtime.sql} + {@code R__titan_020_routines.sql}),
 * applies the demo DDL ({@code ddl/postgres/titan_graphql_postgres.sql}) and the Java-mode fixture rows
 * ({@code DemoBlogFixtureStore} mirrored as SQL), and executes the conformance corpus
 * ({@link GraphqlSqlModeConformanceCorpus}) on both legs:</p>
 *
 * <ul>
 *   <li>Java mode: the kernel's static entry points in this JVM;</li>
 *   <li>SQL mode: {@code SELECT public.<fn>(...)} against the deployed routines.</li>
 * </ul>
 *
 * <p>Results are GraphQL JSON documents; both legs are compared as canonical JSON (parsed
 * trees, object-key-order-insensitive, array-order-sensitive — the GAP-003 semantic-JSON
 * stance) through core's {@link EquivalenceOracle} value normalization. All divergences are
 * collected and reported together, not just the first.</p>
 *
 * <p>Runs under {@code @Tag("docker")} via the {@code integrationTest} task; plain
 * {@code test} stays Docker-free.</p>
 */
@Tag("docker")
@Tag("legacy-sql")
@TitanTest(targets = DatabaseTarget.POSTGRESQL)
class GraphqlSqlModeEquivalenceIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void packagedMigrationsDeployAndAnswerHelloWorldQuery(TitanTestContext context) throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        DemoBlogSqlDeployment.deployPackagedKernel(connection);

        String json = GraphqlSqlModeConformanceCorpus.executeSqlMode(
                connection,
                new GraphqlSqlEntryPointDispatch.Invocation.Execute(
                        "{ article(id: 1) { id title author { id name } } }", 10L, "reader"));

        Optional<EquivalenceOracle.Divergence> divergence = compareCanonicalJson(
                "{\"data\":{\"article\":{\"id\":1,\"title\":\"Titan GraphQL proof\","
                        + "\"author\":{\"id\":10,\"name\":\"Ada Lovelace\"}}}}",
                json);
        assertTrue(divergence.isEmpty(),
                () -> "hello-world query diverged from the expected response:\n"
                        + divergence.orElseThrow().describe() + "\nsql-mode json: " + json);
    }

    @Test
    void sqlModeMatchesJavaModeAcrossConformanceCorpus(TitanTestContext context) throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        DemoBlogSqlDeployment.deployPackagedKernel(connection);

        List<GraphqlSqlModeConformanceCorpus.Case> cases = GraphqlSqlModeConformanceCorpus.cases();
        List<String> divergences = new ArrayList<>();
        for (GraphqlSqlModeConformanceCorpus.Case corpusCase : cases) {
            String javaJson = GraphqlSqlModeConformanceCorpus.executeJavaMode(corpusCase.invocation());
            String sqlJson;
            try {
                sqlJson = GraphqlSqlModeConformanceCorpus.executeSqlMode(connection, corpusCase.invocation());
            } catch (SQLException ex) {
                divergences.add(caseLabel(corpusCase) + "\n  sql-mode execution failed: " + ex.getMessage());
                continue;
            }
            compareCanonicalJson(javaJson, sqlJson).ifPresent(divergence -> divergences.add(
                    caseLabel(corpusCase) + "\n  " + divergence.describe().replace("\n", "\n  ")
                            + "\n  java-mode json: " + javaJson
                            + "\n  sql-mode json:  " + sqlJson));
        }

        System.out.println("[sql-mode-equivalence] corpus cases compared: " + cases.size()
                + ", divergences: " + divergences.size());
        assertTrue(divergences.isEmpty(), () -> divergences.size() + " of " + cases.size()
                + " corpus cases diverged between java mode and deployed SQL mode:\n\n"
                + String.join("\n\n", divergences));
    }

    private static String caseLabel(GraphqlSqlModeConformanceCorpus.Case corpusCase) {
        return corpusCase.rowId() + " / " + corpusCase.name();
    }

    /**
     * Canonical-JSON comparison: both legs are parsed and compared as JSON trees (object key
     * order irrelevant, array order significant) through core's oracle normalization. Inputs
     * that are not valid JSON fall back to raw string comparison so a malformed leg still
     * surfaces as a divergence rather than a crash.
     */
    private static Optional<EquivalenceOracle.Divergence> compareCanonicalJson(String javaJson, String sqlJson) {
        return EquivalenceOracle.compareScalars(parseCanonical(javaJson), parseCanonical(sqlJson));
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
