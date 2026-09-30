package io.titan.graphql.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

final class DatabaseGraphqlOperationRegistryTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void scopesUseExactStringMembershipAndRejectMalformedArrays() {
        assertTrue(DatabaseGraphqlEngine.operationRegistryScopeMatches("[]", ""));
        assertTrue(DatabaseGraphqlEngine.operationRegistryScopeMatches("[ ]", "reader"));
        assertTrue(DatabaseGraphqlEngine.operationRegistryScopeMatches("[\"reader\",\"operator\"]", "reader"));
        assertFalse(DatabaseGraphqlEngine.operationRegistryScopeMatches("[\"reader\"]", ""));
        assertFalse(DatabaseGraphqlEngine.operationRegistryScopeMatches("[\"reader\"]", "Reader"));
        assertFalse(DatabaseGraphqlEngine.operationRegistryScopeMatches("[\"reader\",]", "reader"));
        assertFalse(DatabaseGraphqlEngine.operationRegistryScopeMatches("{\"reader\":true}", "reader"));
    }

    @Test
    void clientExtensionUsesLegacyPrecedence() {
        assertEquals("portal", DatabaseGraphqlEngine.operationRegistryClient(
                "{\"client\":\" portal \",\"clientId\":\"other\"}"));
        assertEquals("second", DatabaseGraphqlEngine.operationRegistryClient(
                "{\"clientId\":\"second\",\"titanClient\":\"third\"}"));
        assertEquals("second", DatabaseGraphqlEngine.operationRegistryClient(
                "{\"client\":null,\"clientId\":\"second\"}"));
        assertEquals("", DatabaseGraphqlEngine.operationRegistryClient(
                "{\"client\":42,\"clientId\":\"second\"}"));
    }

    @Test
    void enforcementAndWarningsRetainGraphqlExtensions() throws Exception {
        assertEquals("", DatabaseGraphqlEngine.operationRegistryRejectionJson("OBSERVE", "UNKNOWN", "hash"));
        assertEquals("", DatabaseGraphqlEngine.operationRegistryRejectionJson("ENFORCE", "APPROVED", "hash"));
        JsonNode rejected = JSON.readTree(DatabaseGraphqlEngine.operationRegistryRejectionJson(
                "ENFORCE", "REJECTED", "hash"));
        assertEquals("OPERATION_REGISTRY_REJECTED", rejected.at("/errors/0/extensions/code").asText());
        assertEquals("REJECTED", rejected.at("/errors/0/extensions/operationRegistry/status").asText());
        JsonNode unknown = JSON.readTree(DatabaseGraphqlEngine.operationRegistryRejectionJson(
                "ENFORCE", "OBSERVED", "hash"));
        assertEquals("UNKNOWN", unknown.at("/errors/0/extensions/operationRegistry/status").asText());

        String measured = DatabaseGraphqlEngine.appendExecutionMetrics("{\"data\":{\"id\":1}}", 2, 3L);
        JsonNode warned = JSON.readTree(DatabaseGraphqlEngine.appendOperationRegistryWarning(
                measured, "WARN", "DEPRECATED", "hash"));
        assertEquals(2, warned.at("/extensions/titanExecution/applicationSqlStatements").asInt());
        assertEquals("OPERATION_REGISTRY_WARNING", warned.at("/extensions/warnings/0/extensions/code").asText());
        assertEquals("REJECTED", warned.at("/extensions/warnings/0/extensions/operationRegistry/status").asText());
        assertEquals("{\"data\":{\"id\":1}}", DatabaseGraphqlEngine.appendOperationRegistryWarning(
                "{\"data\":{\"id\":1}}", "OBSERVE", "UNKNOWN", "hash"));
    }
}
