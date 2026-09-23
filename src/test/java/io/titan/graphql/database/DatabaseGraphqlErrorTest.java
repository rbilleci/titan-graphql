package io.titan.graphql.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DatabaseGraphqlErrorTest {

    @Test
    void encodesRetainedAstOffsetsAsGraphqlLineAndColumnLocations() {
        String source = "query Example {\r\n  unknownRoot { id }\r\n}";
        String response = DatabaseGraphqlEngine.errorJsonAt(
                "unknown root", source, source.indexOf("unknownRoot"));

        assertEquals("{\"errors\":[{\"message\":\"unknown root\",\"locations\":[{\"line\":2,\"column\":3}],"
                        + "\"extensions\":{\"code\":\"VALIDATION_ERROR\"}}]}",
                DatabaseGraphqlEngine.transportResponseJson(response));
    }

    @Test
    void rejectsUntrustedLocationOffsetsWithoutWalkingUnboundedSource() {
        String response = DatabaseGraphqlEngine.errorJsonAt("unknown root", "query { x }", -1);

        assertEquals("{\"errors\":[{\"message\":\"unknown root\","
                        + "\"extensions\":{\"code\":\"VALIDATION_ERROR\"}}]}",
                DatabaseGraphqlEngine.transportResponseJson(response));
    }

    @Test
    void assignsCodesAtTheFailureSourceWithoutInspectingMessages() {
        assertEquals("PARSE_ERROR", code(DatabaseGraphqlEngine.parseErrorJsonAt(
                "same message", "{ ? }", 2)));
        assertEquals("AUTHORIZATION_ERROR", code(DatabaseGraphqlEngine.authorizationErrorJson("same message")));
        assertEquals("UNSUPPORTED_OPERATION", code(
                DatabaseGraphqlEngine.unsupportedOperationErrorJson("same message")));
        assertEquals("RESOURCE_LIMIT_ERROR", code(DatabaseGraphqlEngine.resourceLimitErrorJson("same message")));
        assertEquals("DEADLINE_EXCEEDED", code(DatabaseGraphqlEngine.deadlineExceededErrorJson("same message")));
        assertEquals("INTERNAL_ERROR", code(DatabaseGraphqlEngine.internalErrorJson("same message")));
        assertEquals("EXECUTION_ERROR", code(DatabaseGraphqlEngine.rollbackExecutionErrorJson("same message")));
    }

    @Test
    void classifiesTerminalTransportAndResponseBudgetFailures() {
        assertEquals("INTERNAL_ERROR", code(DatabaseGraphqlEngine.transactionOutcomeJson("{}", "INVALID")));
        assertEquals("RESOURCE_LIMIT_ERROR", code(DatabaseGraphqlEngine.transactionOutcomeJson(
                "x".repeat(16_385), "ROLLBACK")));
    }

    @Test
    void capsJsonAssemblyAtEachAppendWithoutExposingItsPrivateCarrier() {
        String exactMember = DatabaseGraphqlEngine.appendJsonMember("", "n", "x".repeat(16_380));
        assertEquals(16_384, exactMember.length());
        assertFalse(DatabaseGraphqlEngine.responseAssemblyExceeded(exactMember));

        String overflow = DatabaseGraphqlEngine.appendJsonMember("", "n", "x".repeat(16_381));
        assertTrue(DatabaseGraphqlEngine.responseAssemblyExceeded(overflow));
        assertEquals("RESOURCE_LIMIT_ERROR", code(DatabaseGraphqlEngine.transactionOutcomeJson(
                "{\"data\":" + overflow + "}", "ROLLBACK")));

        String userMarker = DatabaseGraphqlEngine.jsonString(
                "\u001eTITAN-GRAPHQL-RESPONSE-ASSEMBLY-LIMIT");
        assertFalse(DatabaseGraphqlEngine.responseAssemblyExceeded(
                DatabaseGraphqlEngine.appendJsonItem("", userMarker)));
        assertEquals("second,first", DatabaseGraphqlEngine.prependJsonItem("first", "second"));
    }

    @Test
    void appendsBoundedExecutionMetricsToACompletedResponse() {
        assertEquals(
                "{\"data\":{\"customer\":null},\"extensions\":{\"titanExecution\":{"
                        + "\"applicationSqlStatements\":3,\"decodedApplicationRows\":130}}}",
                DatabaseGraphqlEngine.appendExecutionMetrics(
                        "{\"data\":{\"customer\":null}}", 3, 130L));

        assertTrue(DatabaseGraphqlEngine.responseAssemblyExceeded(
                DatabaseGraphqlEngine.appendExecutionMetrics("{}", -1, 0L)));
        assertTrue(DatabaseGraphqlEngine.responseAssemblyExceeded(
                DatabaseGraphqlEngine.appendExecutionMetrics(
                        "{\"data\":\"" + "x".repeat(16_370) + "\"}", 1, 1L)));
    }

    @Test
    void carriesBatchedRelationKeysItemsNullsAndPlaceholdersWithoutCollections() {
        String keys = DatabaseGraphqlEngine.appendBatchParentKey("", "7:customer");
        keys = DatabaseGraphqlEngine.appendBatchParentKey(keys, "8");
        assertEquals(2, DatabaseGraphqlEngine.batchParentKeyCount(keys));
        assertEquals("7:customer", DatabaseGraphqlEngine.batchParentKey(keys, 0));

        String activity = DatabaseGraphqlEngine.appendBatchParentActivity("", true);
        activity = DatabaseGraphqlEngine.appendBatchParentActivity(activity, false);
        assertEquals(2, DatabaseGraphqlEngine.batchParentActivityCount(activity));
        assertTrue(DatabaseGraphqlEngine.batchParentActive(activity, 0));
        assertFalse(DatabaseGraphqlEngine.batchParentActive(activity, 1));
        String owners = DatabaseGraphqlEngine.appendBatchParentKey("", "0");
        owners = DatabaseGraphqlEngine.appendBatchParentKey(owners, "1000");
        assertEquals(0, DatabaseGraphqlEngine.batchParentOwnerIndex(owners, 0));
        assertEquals(1000, DatabaseGraphqlEngine.batchParentOwnerIndex(owners, 1));
        assertEquals(-1, DatabaseGraphqlEngine.batchParentOwnerIndex(owners, 2));

        String plans = DatabaseGraphqlEngine.appendBatchParentKey("", "41");
        plans = DatabaseGraphqlEngine.appendBatchParentKey(plans, "57");
        plans = DatabaseGraphqlEngine.appendBatchParentKey(plans, "41");
        String planKeys = DatabaseGraphqlEngine.appendBatchParentKey("", "customer-1");
        planKeys = DatabaseGraphqlEngine.appendBatchParentKey(planKeys, "customer-1");
        planKeys = DatabaseGraphqlEngine.appendBatchParentKey(planKeys, "customer-2");
        String planActivity = DatabaseGraphqlEngine.appendBatchParentActivity("", true);
        planActivity = DatabaseGraphqlEngine.appendBatchParentActivity(planActivity, false);
        planActivity = DatabaseGraphqlEngine.appendBatchParentActivity(planActivity, true);
        assertEquals(41, DatabaseGraphqlEngine.batchPlanStart(plans, 0));
        assertTrue(DatabaseGraphqlEngine.batchPlanFirstOccurrence(plans, 0));
        assertTrue(DatabaseGraphqlEngine.batchPlanFirstOccurrence(plans, 1));
        assertFalse(DatabaseGraphqlEngine.batchPlanFirstOccurrence(plans, 2));
        assertEquals("bk1;10:customer-110:customer-2",
                DatabaseGraphqlEngine.batchParentKeysForPlan(planKeys, plans, 41));
        assertEquals("ba1;11",
                DatabaseGraphqlEngine.batchParentActivityForPlan(planActivity, plans, 41));
        assertEquals("bk1;1:01:2",
                DatabaseGraphqlEngine.batchParentOwnersForPlan(plans, 41));
        assertEquals("", DatabaseGraphqlEngine.batchParentKeysForPlan(planKeys, "bk1;2:41", 41));

        String rows = DatabaseGraphqlEngine.appendBatchRelationItem("", 0, "{\"id\":70}");
        rows = DatabaseGraphqlEngine.appendBatchRelationItem(rows, 0, "{\"id\":71}");
        rows = DatabaseGraphqlEngine.appendBatchRelationNull(rows, 1);
        assertEquals("[{\"id\":70},{\"id\":71}]", DatabaseGraphqlEngine.batchRelationValue(rows, 0));
        assertEquals("null", DatabaseGraphqlEngine.batchRelationValue(rows, 1));
        assertEquals("[]", DatabaseGraphqlEngine.batchRelationValue(rows, 2));
        assertEquals("null", DatabaseGraphqlEngine.batchRelationObjectValue("br1;", 0));
        assertEquals(0, DatabaseGraphqlEngine.batchRelationRecordCount("br1;", 0));
        assertEquals("{\"id\":70}", DatabaseGraphqlEngine.batchRelationObjectValue(
                DatabaseGraphqlEngine.appendBatchRelationItem("br1;", 0, "{\"id\":70}"), 0));
        assertEquals(2, DatabaseGraphqlEngine.batchRelationRecordCount(rows, 0));
        assertEquals("", DatabaseGraphqlEngine.batchRelationObjectValue(rows, 0));

        String relayRows = DatabaseGraphqlEngine.appendBatchRelationItem(
                "br1;", 0, "{\"cursor\":\"a\"}");
        relayRows = DatabaseGraphqlEngine.appendBatchRelationItem(
                relayRows, 1, "{\"cursor\":\"x\"}");
        relayRows = DatabaseGraphqlEngine.appendBatchRelationItem(
                relayRows, 0, "{\"cursor\":\"b\"}");
        relayRows = DatabaseGraphqlEngine.appendBatchRelationItem(
                relayRows, 0, "{\"cursor\":\"sentinel\"}");
        assertEquals("[{\"cursor\":\"a\"},{\"cursor\":\"b\"}]",
                DatabaseGraphqlEngine.batchRelationLimitedValue(relayRows, 0, 2, false));
        assertEquals("[{\"cursor\":\"b\"},{\"cursor\":\"a\"}]",
                DatabaseGraphqlEngine.batchRelationLimitedValue(relayRows, 0, 2, true));
        assertEquals("{\"cursor\":\"a\"}",
                DatabaseGraphqlEngine.batchRelationItem(relayRows, 0, 0));
        assertEquals("{\"cursor\":\"b\"}",
                DatabaseGraphqlEngine.batchRelationItem(relayRows, 0, 1));
        assertEquals("", DatabaseGraphqlEngine.batchRelationItem(relayRows, 0, 3));
        assertEquals("", DatabaseGraphqlEngine.batchRelationLimitedValue(
                "br1;0:I:10:x", 0, 2, false));
        assertEquals("", DatabaseGraphqlEngine.batchRelationLimitedValue(
                relayRows, 0, 101, false));

        String placeholder = DatabaseGraphqlEngine.batchRelationPlaceholder(41, 0);
        String object = "{\"orders\":" + placeholder + "}";
        assertEquals("{\"orders\":[{\"id\":70},{\"id\":71}]}",
                DatabaseGraphqlEngine.replaceBatchRelationPlaceholder(
                        object, 41, 0, DatabaseGraphqlEngine.batchRelationValue(rows, 0)));
        assertEquals("", DatabaseGraphqlEngine.replaceBatchRelationPlaceholder(object, 42, 0, "[]"));

        String nestedPlaceholder = DatabaseGraphqlEngine.batchRelationPlaceholder(57, 0);
        String nestedRows = DatabaseGraphqlEngine.appendBatchRelationItem(
                "br1;", 3, "{\"id\":70,\"customer\":" + nestedPlaceholder + "}");
        nestedRows = DatabaseGraphqlEngine.appendBatchRelationItem(nestedRows, 4, "{\"id\":80}");
        nestedRows = DatabaseGraphqlEngine.replaceBatchRelationCarrierPlaceholder(
                nestedRows, 57, 0, "{\"id\":7}");
        assertEquals("[{\"id\":70,\"customer\":{\"id\":7}}]",
                DatabaseGraphqlEngine.batchRelationValue(nestedRows, 3));
        assertEquals("[{\"id\":80}]", DatabaseGraphqlEngine.batchRelationValue(nestedRows, 4));
        assertEquals("", DatabaseGraphqlEngine.replaceBatchRelationCarrierPlaceholder(
                nestedRows, 58, 0, "null"));

        String rootCarrier = DatabaseGraphqlEngine.queryRootExecutionCarrier(
                "[{\"id\":7}]", "{\"message\":\"warning\"}", 5, 196L, false);
        assertTrue(DatabaseGraphqlEngine.queryRootExecutionCarrierIsValid(rootCarrier));
        assertEquals(5, DatabaseGraphqlEngine.queryRootExecutionStatementCount(rootCarrier));
        assertEquals(196L, DatabaseGraphqlEngine.queryRootExecutionDecodedRows(rootCarrier));
        assertFalse(DatabaseGraphqlEngine.queryRootExecutionPropagatedNull(rootCarrier));
        assertEquals("{\"message\":\"warning\"}",
                DatabaseGraphqlEngine.queryRootExecutionErrors(rootCarrier));
        assertEquals("[{\"id\":7}]", DatabaseGraphqlEngine.queryRootExecutionValue(rootCarrier));
        assertFalse(DatabaseGraphqlEngine.queryRootExecutionCarrierIsValid(rootCarrier + "x"));
        assertEquals("", DatabaseGraphqlEngine.queryRootExecutionCarrier("null", "", 65, 0L, true));
    }

    @Test
    void assemblesPartialExecutionDataWithAnAliasedPath() {
        String path = DatabaseGraphqlEngine.appendExecutionPath("[]", "brokenCustomer");
        path = DatabaseGraphqlEngine.appendExecutionPath(path, "orders");
        path = DatabaseGraphqlEngine.appendExecutionPathIndex(path, 0);
        path = DatabaseGraphqlEngine.appendExecutionPath(path, "reference");
        String source = "{ brokenCustomer { orders { reference } } }";
        String errors = DatabaseGraphqlEngine.appendExecutionError(
                "", "non-null violation", path, source, source.indexOf("reference"));

        assertEquals("{\"data\":{\"brokenCustomer\":null,\"healthyCountry\":{\"code\":\"NL\"}},"
                        + "\"errors\":[{\"message\":\"non-null violation\",\"locations\":[{\"line\":1,\"column\":29}],"
                        + "\"path\":[\"brokenCustomer\",\"orders\",0,\"reference\"],"
                        + "\"extensions\":{\"code\":\"EXECUTION_ERROR\"}}]}",
                DatabaseGraphqlEngine.executionResponseJson(
                        "\"brokenCustomer\":null,\"healthyCountry\":{\"code\":\"NL\"}", errors));
    }

    @Test
    void assemblesNullOperationDataAfterANonNullConnectionRootViolation() {
        String source = "{ customerFeed { edges { node { name } } } }";
        String path = "[\"customerFeed\",\"edges\",0,\"node\",\"name\"]";
        String errors = DatabaseGraphqlEngine.appendExecutionError(
                "", "non-null violation", path, source, source.indexOf("name"));

        assertEquals("{\"data\":null,\"errors\":[{\"message\":\"non-null violation\",\"locations\":[{\"line\":1,\"column\":33}],"
                        + "\"path\":[\"customerFeed\",\"edges\",0,\"node\",\"name\"],"
                        + "\"extensions\":{\"code\":\"EXECUTION_ERROR\"}}]}",
                DatabaseGraphqlEngine.executionResponseWithNullDataJson(errors));
    }

    private static String code(String response) {
        String json = DatabaseGraphqlEngine.transportResponseJson(response);
        String marker = "\"code\":\"";
        int start = json.indexOf(marker);
        return start < 0 ? "" : json.substring(start + marker.length(), json.indexOf('"', start + marker.length()));
    }
}
