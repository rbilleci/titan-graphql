package io.titan.graphql.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DatabaseGraphqlTypeReferenceTest {

    @Test
    void encodesNestedListAndNonNullWrappersInPostfixOrder() {
        assertEquals("tr1;N:Int;", DatabaseGraphqlTypeReference.parse("Int"));
        assertEquals("tr1;N:Int;!;", DatabaseGraphqlTypeReference.parse("Int!"));
        assertEquals("tr1;N:Int;!;L;!;", DatabaseGraphqlTypeReference.parse("[Int!]!"));
        assertEquals("tr1;N:Input;L;!;L;", DatabaseGraphqlTypeReference.parse("[[Input]!]"));
        assertEquals("Int", DatabaseGraphqlTypeReference.sourceText(
                DatabaseGraphqlTypeReference.parse("Int")));
        assertEquals("[Int!]!", DatabaseGraphqlTypeReference.sourceText(
                DatabaseGraphqlTypeReference.parse("[Int!]!")));
        assertEquals("Input", DatabaseGraphqlTypeReference.namedType(
                DatabaseGraphqlTypeReference.parse("[[Input]!]")));
        assertTrue(DatabaseGraphqlTypeReference.isOuterNonNull(
                DatabaseGraphqlTypeReference.parse("[Input!]!")));
        assertFalse(DatabaseGraphqlTypeReference.isOuterNonNull(
                DatabaseGraphqlTypeReference.parse("[Input!]")));
        assertEquals("tr1;N:Input;!;L;!;", DatabaseGraphqlTypeReference.withOuterNonNull(
                DatabaseGraphqlTypeReference.parse("[Input!]")));
        String nested = DatabaseGraphqlTypeReference.parse("[[Input!]!]!");
        assertTrue(DatabaseGraphqlTypeReference.isOuterList(nested));
        assertEquals("tr1;N:Input;!;L;!;", DatabaseGraphqlTypeReference.listItemType(nested));
        assertEquals("tr1;N:Input;!;L;!;L;", DatabaseGraphqlTypeReference.withoutOuterNonNull(nested));
        assertTrue(DatabaseGraphqlTypeReference.isOuterList(DatabaseGraphqlTypeReference.listItemType(nested)));
        assertEquals("tr1;N:Input;!;", DatabaseGraphqlTypeReference.listItemType(
                DatabaseGraphqlTypeReference.listItemType(nested)));
    }

    @Test
    void rejectsMalformedOrOverwrappedReferences() {
        assertEquals("", DatabaseGraphqlTypeReference.parse(""));
        assertEquals("", DatabaseGraphqlTypeReference.parse("[Int"));
        assertEquals("", DatabaseGraphqlTypeReference.parse("Int!!"));
        assertEquals("", DatabaseGraphqlTypeReference.parse("1nt"));
        assertEquals("", DatabaseGraphqlTypeReference.parse("Int]"));
        assertEquals("", DatabaseGraphqlTypeReference.withOuterNonNull("tr1;L;"));
        assertFalse(DatabaseGraphqlTypeReference.isOuterList("tr1;L;"));
        assertEquals("", DatabaseGraphqlTypeReference.listItemType("tr1;L;"));
        assertEquals("", DatabaseGraphqlTypeReference.withoutOuterNonNull("tr1;L;"));
    }

    @Test
    void acceptsIgnoredTextRetainedAtAnAstRangeBoundary() {
        assertEquals("tr1;N:Input;!;L;!;",
                DatabaseGraphqlTypeReference.parse(" [ Input ! # comment\n ] !  "));
    }

    @Test
    void appliesGraphqlVariableCompatibilityAcrossEveryListAndNonNullWrapper() {
        String integer = DatabaseGraphqlTypeReference.parse("Int");
        String requiredInteger = DatabaseGraphqlTypeReference.parse("Int!");
        String nullableItems = DatabaseGraphqlTypeReference.parse("[Int]");
        String requiredItems = DatabaseGraphqlTypeReference.parse("[Int!]");

        assertTrue(DatabaseGraphqlTypeReference.isVariableUsageAllowed(requiredInteger, integer, false, false));
        assertFalse(DatabaseGraphqlTypeReference.isVariableUsageAllowed(integer, requiredInteger, false, false));
        assertTrue(DatabaseGraphqlTypeReference.isVariableUsageAllowed(integer, requiredInteger, true, false));
        assertTrue(DatabaseGraphqlTypeReference.isVariableUsageAllowed(integer, requiredInteger, false, true));
        assertTrue(DatabaseGraphqlTypeReference.isVariableUsageAllowed(requiredItems, nullableItems, false, false));
        assertFalse(DatabaseGraphqlTypeReference.isVariableUsageAllowed(nullableItems, requiredItems, false, false));
        assertFalse(DatabaseGraphqlTypeReference.isVariableUsageAllowed(
                DatabaseGraphqlTypeReference.parse("[String]"), nullableItems, false, false));
    }
}
