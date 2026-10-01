package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

final class DatabaseEngineMeasurements {

    private DatabaseEngineMeasurements() {
    }

    static JsonNode measure(String model, String dialect, int parents, int childrenPerParent,
            Callable<JsonNode> request) throws Exception {
        JsonNode expected = request.call();
        assertFalse(expected.has("errors"), expected::toString);
        assertTrue(expected.at("/extensions/titanExecution/applicationSqlStatements").isIntegralNumber(),
                expected::toString);
        assertTrue(expected.at("/extensions/titanExecution/decodedApplicationRows").isIntegralNumber(),
                expected::toString);
        assertEquals(expected, request.call());
        long[] elapsed = new long[10];
        for (int index = 0; index < elapsed.length; index++) {
            long started = System.nanoTime();
            JsonNode actual = request.call();
            elapsed[index] = System.nanoTime() - started;
            assertEquals(expected, actual, "measurement request changed its result");
        }
        Arrays.sort(elapsed);
        System.out.println("DATABASE_REQUEST_MEASUREMENT model=" + model + " dialect=" + dialect
                + " parents=" + parents + " childrenPerParent=" + childrenPerParent
                + " warmups=2 samples=" + elapsed.length
                + " p50Micros=" + TimeUnit.NANOSECONDS.toMicros(elapsed[4])
                + " p95Micros=" + TimeUnit.NANOSECONDS.toMicros(elapsed[9])
                + " applicationSqlStatements="
                + expected.at("/extensions/titanExecution/applicationSqlStatements").asInt(-1)
                + " decodedApplicationRows="
                + expected.at("/extensions/titanExecution/decodedApplicationRows").asLong(-1));
        return expected;
    }

    static void recordInstallation(String model, String dialect, long started) {
        System.out.println("DATABASE_INSTALL_MEASUREMENT model=" + model + " dialect=" + dialect
                + " installMicros=" + TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started));
    }
}
