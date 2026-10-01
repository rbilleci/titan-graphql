package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;

final class GeneratedDatabaseEngineCorpus {

    private static final String FIELDS = "identifier: id displayName: name active rating verified";
    private static final Selection[] SELECTIONS = {
        new Selection(FIELDS, ""),
        new Selection("... on Customer { " + FIELDS + " }", ""),
        new Selection("...Fields", " fragment Fields on Customer { " + FIELDS + " }"),
        new Selection("identifier: id ...Fields", " fragment Fields on Customer { " + FIELDS + " }")
    };

    private GeneratedDatabaseEngineCorpus() {
    }

    static void assertCommerce(DatabaseEngineExpectedResultCorpus.Executor executor) throws Exception {
        JsonNode expected;
        try (InputStream input = GeneratedDatabaseEngineCorpus.class.getResourceAsStream(
                "/database-engine-corpus/commerce-generated-v1.json")) {
            if (input == null) {
                throw new IllegalStateException("missing generated-corpus expected result");
            }
            expected = new ObjectMapper().readTree(input);
        }
        int count = 0;
        for (boolean variables : new boolean[] {false, true}) {
            String parameters = variables ? "($id: Int!, $code: String!)" : "";
            String customerId = variables ? "$id" : "7";
            String countryCode = variables ? "$code" : "\"NL\"";
            String variablesJson = variables ? "{\"id\":7,\"code\":\"NL\"}" : "{}";
            for (Selection selection : SELECTIONS) {
                for (String directive : new String[] {"", " @include(if: true)", " @skip(if: false)"}) {
                    String document = "query Generated" + parameters + " { selected: customer(id: "
                            + customerId + ")" + directive + " { " + selection.body()
                            + " } country(code: " + countryCode + ") { code name } }"
                            + selection.definitions() + " query Unselected { country(code: \"NL\") { code } }";
                    for (boolean trivia : new boolean[] {false, true}) {
                        String query = trivia ? "# grammar variant\n" + document.replace(" ", ",\n") : document;
                        JsonNode actual = executor.execute(query, "Generated", variablesJson,
                                "reader", false, false, false, "");
                        assertEquals(expected, actual, "generated database request: " + query);
                        count++;
                    }
                }
            }
        }
        System.out.println("GENERATED_DATABASE_CORPUS cases=" + count);
    }

    private record Selection(String body, String definitions) {
    }
}
