package io.titan.graphql.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DatabaseGraphqlFilterPlanTest {

    private static final String DESCRIPTOR = "authorName.eq=1,title.gt=2,title.in=3";

    @Test
    void lowersBooleanCompositionToFixedDnfSlots() {
        String plan = DatabaseGraphqlEngine.generatedFilterPlan(
                "{ or: [{ title: { gt: 20 } }, { authorName: { eq: \"Ada\" } }] }",
                DESCRIPTOR);

        assertFalse(DatabaseGraphqlEngine.filterPlanIsFailure(plan), plan);
        assertTrue(DatabaseGraphqlEngine.filterPlanGroupActive(plan, 0), plan);
        assertTrue(DatabaseGraphqlEngine.filterPlanGroupActive(plan, 1), plan);
        assertFalse(DatabaseGraphqlEngine.filterPlanGroupActive(plan, 2), plan);
        assertEquals(2, DatabaseGraphqlEngine.filterPlanTermSelector(plan, 0, 0), plan);
        assertEquals("20", DatabaseGraphqlEngine.filterPlanTermValue(plan, 0, 0), plan);
        assertEquals(1, DatabaseGraphqlEngine.filterPlanTermSelector(plan, 1, 0), plan);
        assertEquals("\"Ada\"", DatabaseGraphqlEngine.filterPlanTermValue(plan, 1, 0), plan);
    }

    @Test
    void appliesDeMorganAndExpandsInToEqualityTerms() {
        String plan = DatabaseGraphqlEngine.generatedFilterPlan(
                "{ not: { title: { in: [\"Titan\", \"GraphQL\"] } } }", DESCRIPTOR);

        assertFalse(DatabaseGraphqlEngine.filterPlanIsFailure(plan), plan);
        assertTrue(DatabaseGraphqlEngine.filterPlanGroupActive(plan, 0), plan);
        assertFalse(DatabaseGraphqlEngine.filterPlanGroupActive(plan, 1), plan);
        assertEquals(3, DatabaseGraphqlEngine.filterPlanTermSelector(plan, 0, 0), plan);
        assertEquals(3, DatabaseGraphqlEngine.filterPlanTermSelector(plan, 0, 1), plan);
        assertTrue(DatabaseGraphqlEngine.filterPlanTermNegated(plan, 0, 0), plan);
        assertTrue(DatabaseGraphqlEngine.filterPlanTermNegated(plan, 0, 1), plan);
    }

    @Test
    void rejectsPlansThatExceedTheStaticCarrier() {
        String plan = DatabaseGraphqlEngine.generatedFilterPlan(
                "{ and: ["
                        + "{ or: [{ title: { gt: 1 } }, { title: { gt: 2 } }] },"
                        + "{ or: [{ authorName: { eq: \"A\" } }, { authorName: { eq: \"B\" } }] }"
                        + "] }",
                DESCRIPTOR);

        assertTrue(DatabaseGraphqlEngine.filterPlanIsFailure(plan), plan);
        assertTrue(DatabaseGraphqlEngine.filterPlanFailureMessage(plan).contains("more than 3 OR groups"), plan);
    }

    @Test
    void retainsExplicitNullAndRejectsUnknownInputFields() {
        String nullPlan = DatabaseGraphqlEngine.generatedFilterPlan(
                "{ authorName: { eq: null } }", DESCRIPTOR);
        assertFalse(DatabaseGraphqlEngine.filterPlanIsFailure(nullPlan), nullPlan);
        assertTrue(DatabaseGraphqlEngine.filterPlanTermNull(nullPlan, 0, 0), nullPlan);
        assertEquals("null", DatabaseGraphqlEngine.filterPlanTermValue(nullPlan, 0, 0), nullPlan);

        String unknown = DatabaseGraphqlEngine.generatedFilterPlan(
                "{ privateField: { eq: 7 } }", DESCRIPTOR);
        assertTrue(DatabaseGraphqlEngine.filterPlanIsFailure(unknown), unknown);
        assertTrue(DatabaseGraphqlEngine.filterPlanFailureMessage(unknown).contains("unknown"), unknown);

        String invalidNull = DatabaseGraphqlEngine.generatedFilterPlan(
                "{ title: { gt: null } }", DESCRIPTOR);
        assertTrue(DatabaseGraphqlEngine.filterPlanIsFailure(invalidNull), invalidNull);
        assertTrue(DatabaseGraphqlEngine.filterPlanFailureMessage(invalidNull).contains("does not accept null"),
                invalidNull);
    }

    @Test
    void roundTripsBoundedCustomCursorCarriers() {
        String text = DatabaseGraphqlEngine.connectionCursorCarrier("Ada", 0L, 17L);
        assertTrue(DatabaseGraphqlEngine.connectionCursorCarrierIsValid(text));
        assertEquals("Ada", DatabaseGraphqlEngine.connectionCursorCarrierStringValue(text));
        assertEquals(0L, DatabaseGraphqlEngine.connectionCursorCarrierLongValue(text));
        assertEquals(17L, DatabaseGraphqlEngine.connectionCursorCarrierTieValue(text));

        String integral = DatabaseGraphqlEngine.connectionCursorCarrier("", -9L, 3L);
        assertTrue(DatabaseGraphqlEngine.connectionCursorCarrierIsValid(integral));
        assertEquals("", DatabaseGraphqlEngine.connectionCursorCarrierStringValue(integral));
        assertEquals(-9L, DatabaseGraphqlEngine.connectionCursorCarrierLongValue(integral));
        assertEquals(3L, DatabaseGraphqlEngine.connectionCursorCarrierTieValue(integral));

        assertFalse(DatabaseGraphqlEngine.connectionCursorCarrierIsValid("cc1;3:0:17:Ad"));
    }

    @Test
    void tupleCursorsAcceptReviewedDottedRelationPaths() {
        String cursor = DatabaseGraphqlEngine.relayCursorForStringLongTie(
                "authorName", "author.name", "DESC", "id", "Grace Hopper", 2L);

        assertFalse(cursor.isEmpty());
        assertTrue(DatabaseGraphqlEngine.relayCursorStringLongTieIsValid(
                cursor, "authorName", "author.name", "DESC", "id"));
        assertEquals("Grace Hopper", DatabaseGraphqlEngine.relayCursorStringLongTieValue(cursor));
        assertEquals(2L, DatabaseGraphqlEngine.relayCursorStringLongTieBreakerValue(cursor));
    }

    @Test
    void readsAuthenticatedStringContextValuesWithoutConflatingEmptyAndMissing() {
        String context = "{\"contextValues\":{\"tenantKey\":\"tenant-a\",\"empty\":\"\",\"wrong\":7}}";

        assertTrue(DatabaseGraphqlEngine.trustedContextStringValuePresent(context, "tenantKey"));
        assertEquals("tenant-a", DatabaseGraphqlEngine.trustedContextStringValue(context, "tenantKey"));
        assertTrue(DatabaseGraphqlEngine.trustedContextStringValuePresent(context, "empty"));
        assertEquals("", DatabaseGraphqlEngine.trustedContextStringValue(context, "empty"));
        assertFalse(DatabaseGraphqlEngine.trustedContextStringValuePresent(context, "missing"));
        assertFalse(DatabaseGraphqlEngine.trustedContextStringValuePresent(context, "wrong"));
    }
}
