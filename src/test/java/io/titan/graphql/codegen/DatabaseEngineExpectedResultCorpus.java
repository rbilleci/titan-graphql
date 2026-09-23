package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;

/**
 * Fixed, transport-neutral expected results for the database-resident engine.
 *
 * <p>The corpus contains concrete GraphQL response JSON rather than deriving expectations from
 * the former JVM/demo runtime. Each dialect executes the same requests through its installed
 * public entry point, so two implementations cannot mask the same semantic mistake.</p>
 */
final class DatabaseEngineExpectedResultCorpus {

    private static final ObjectMapper JSON = new ObjectMapper();

    private DatabaseEngineExpectedResultCorpus() {
    }

    interface Executor {
        JsonNode execute(
                String query,
                String operationName,
                String variablesJson,
                String actorRole,
                boolean allowMutations,
                boolean allowIntrospection
        ) throws Exception;
    }

    static void assertCommerceV1(Executor executor) throws Exception {
        JsonNode corpus = read("/database-engine-corpus/commerce-v1.json");
        assertEquals("titan.graphql.database-engine-corpus/v1", corpus.path("schemaVersion").asText());
        for (JsonNode testCase : corpus.path("cases")) {
            String name = testCase.path("name").asText();
            JsonNode actual = executor.execute(
                    testCase.path("query").asText(),
                    testCase.path("operationName").asText(),
                    testCase.path("variables").asText(),
                    testCase.path("actorRole").asText(),
                    testCase.path("allowMutations").asBoolean(false),
                    testCase.path("allowIntrospection").asBoolean(false));
            assertEquals(testCase.path("expected"), actual, "database-engine corpus case " + name);
        }
    }

    private static JsonNode read(String resource) throws IOException {
        try (InputStream input = DatabaseEngineExpectedResultCorpus.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("missing database-engine expected-result corpus " + resource);
            }
            return JSON.readTree(input);
        }
    }
}
