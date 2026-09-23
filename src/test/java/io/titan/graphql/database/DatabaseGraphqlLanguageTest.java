package io.titan.graphql.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit-level evidence for the scalar language core, independent of schema dispatch. */
class DatabaseGraphqlLanguageTest {

    @Test
    void indexesSelectedOperationFromTheBoundedTokenStream() {
        String query = """
                # The selected operation is deliberately after an unrelated operation.
                query Ignored($value: Int!) { customer(id: $value) { id } }
                query Selected($value: Int = 7) @skip(if: false) {
                  found: customer(id: $value) { id name }
                }
                fragment CustomerFields on Customer { id name }
                """;

        String range = DatabaseGraphqlLanguage.selectedOperationRange(query, "Selected");
        assertEquals(query.indexOf("query Selected"), DatabaseGraphqlLanguage.rangeStart(range));
        assertEquals(query.indexOf("\nfragment CustomerFields"), DatabaseGraphqlLanguage.rangeEnd(range));
        assertEquals("query Selected($value: Int = 7) @skip(if: false) {\n"
                        + "  found: customer(id: $value) { id name }\n}",
                query.substring(DatabaseGraphqlLanguage.rangeStart(range), DatabaseGraphqlLanguage.rangeEnd(range)));
        assertTrue(DatabaseGraphqlLanguage.lexicalTokenStream(query).startsWith("v1;"));
        String tokens = DatabaseGraphqlLanguage.lexicalTokenStream(query);
        assertEquals(range, DatabaseGraphqlLanguage.selectedOperationRange(query, tokens, "Selected"));
    }

    @Test
    void preservesAnonymousOperationAndRejectsAmbiguousOrInvalidDocuments() {
        String anonymous = "{ customer(id: 7) { note(text: \"{ not syntax }\") { id } } }";
        String anonymousRange = DatabaseGraphqlLanguage.selectedOperationRange(anonymous, "");
        assertEquals(0, DatabaseGraphqlLanguage.rangeStart(anonymousRange));
        assertEquals(anonymous.length(), DatabaseGraphqlLanguage.rangeEnd(anonymousRange));

        assertEquals("", DatabaseGraphqlLanguage.selectedOperationRange(
                "query One { customer(id: 7) { id } } query Two { customer(id: 8) { id } }", ""));
        assertEquals("", DatabaseGraphqlLanguage.selectedOperationRange(
                "query One { customer(id: 7) { id } } query One { customer(id: 8) { id } }", "One"));
        assertEquals("", DatabaseGraphqlLanguage.lexicalTokenStream("{ customer(id: 01) { id } }"));
        assertFalse(DatabaseGraphqlLanguage.selectedOperationRange("{ customer(id: 7) { id }", "").length() > 0);
    }

    @Test
    void locatesTheFirstLexicalFailureWithoutChangingTheValidTokenCarrier() {
        String invalidCharacter = "query Example {\n  customer(id: 7) ?\n}";
        String unclosedSelection = "query Example {\n  customer(id: 7) { id }";

        assertEquals(invalidCharacter.indexOf('?'), DatabaseGraphqlLanguage.lexicalErrorOffset(invalidCharacter));
        assertEquals(unclosedSelection.indexOf('{'),
                DatabaseGraphqlLanguage.lexicalErrorOffset(unclosedSelection));
        assertEquals(-1, DatabaseGraphqlLanguage.lexicalErrorOffset("{ customer(id: 7) { id } }"));
    }

    @Test
    void identifiesTheSelectedOperationKindFromTheSameTokenizedDocument() {
        String document = "query Read { customer(id: 7) { id } } mutation Write { updateCustomer(id: 7) { id } }";
        assertEquals("query", DatabaseGraphqlLanguage.selectedOperationKind(document, "Read"));
        assertEquals("mutation", DatabaseGraphqlLanguage.selectedOperationKind(document, "Write"));
        assertEquals("subscription", DatabaseGraphqlLanguage.selectedOperationKind(
                "subscription Events { customerEvents { id } }", ""));
        assertEquals("", DatabaseGraphqlLanguage.selectedOperationKind(document, "Missing"));
        assertEquals("query", DatabaseGraphqlLanguage.operationKind(
                DatabaseGraphqlLanguage.documentPlan("query Read { customer(id: 7) { id } }")));
        assertEquals("mutation", DatabaseGraphqlLanguage.operationKind(
                DatabaseGraphqlLanguage.documentPlan("mutation Write { updateCustomer(id: 7) { id } }")));
        assertEquals("subscription", DatabaseGraphqlLanguage.operationKind(
                DatabaseGraphqlLanguage.documentPlan("subscription Events { customerEvents { id } }")));
    }

    @Test
    void derivesOperationAndFieldSelectionBoundariesFromTokens() {
        String operation = "query Lookup($filter: Filter = { note: \"} is data\" }) "
                + "@skip(if: false) { found: customer(filter: $filter) @include(if: true) { id name } }";
        int rootSelection = DatabaseGraphqlLanguage.firstOperationSelectionStart(operation);
        int fieldStart = operation.indexOf("found:");
        int directiveStart = operation.indexOf("@include");
        int childSelection = operation.indexOf("{ id name }");
        String plan = DatabaseGraphqlLanguage.documentPlan(operation);

        assertEquals(operation.indexOf("{ found:"), rootSelection);
        assertEquals(directiveStart, DatabaseGraphqlLanguage.fieldHeaderEnd(operation, fieldStart));
        assertEquals(childSelection, DatabaseGraphqlLanguage.fieldSelectionStart(operation, fieldStart));
        assertEquals(rootSelection, DatabaseGraphqlLanguage.firstOperationSelectionStart(operation, plan));
        assertEquals(directiveStart, DatabaseGraphqlLanguage.fieldHeaderEnd(operation, plan, fieldStart));
        assertEquals(childSelection, DatabaseGraphqlLanguage.fieldSelectionStart(operation, plan, fieldStart));
        assertEquals(1, DatabaseGraphqlEngine.rootFieldCountForType(operation, plan, "Query"));
        assertEquals(2, DatabaseGraphqlEngine.selectionFieldCount(operation, plan, fieldStart, "{}", "Customer"));
        assertEquals(-1, DatabaseGraphqlLanguage.fieldSelectionStart("{ customer(id: 7) }", 2));
        assertEquals(-1, DatabaseGraphqlLanguage.fieldSelectionStart(operation, "not a language plan", fieldStart));
    }

    @Test
    void carriesDirectInlineFragmentItemsForPlanDrivenExpansion() {
        String operation = "{ customer(id: 7) { ... on Customer @include(if: true) { id } } }";
        String plan = DatabaseGraphqlLanguage.documentPlan(operation);
        int customer = operation.indexOf("customer");
        int customerSelection = DatabaseGraphqlLanguage.fieldSelectionStart(operation, plan, customer);
        String inlineItem = DatabaseGraphqlLanguage.selectionItemInfo(plan, customerSelection, 0);
        int spreadStart = operation.indexOf("... on");
        String inlineInfo = DatabaseGraphqlLanguage.inlineFragmentInfo(plan, spreadStart);

        assertEquals(spreadStart, DatabaseGraphqlLanguage.planInfoValue(inlineItem, 0));
        assertTrue(inlineItem.endsWith(":I"), inlineItem);
        assertEquals(operation.indexOf("Customer"), DatabaseGraphqlLanguage.planInfoValue(inlineInfo, 0));
        assertEquals(operation.indexOf("{ id }"), DatabaseGraphqlLanguage.planInfoValue(inlineInfo, 3));
    }

    @Test
    void selectedDocumentUsesTheLanguageRangeBeforeReachableFragmentRetention() {
        String query = """
                query Unselected { customer(id: 8) { id } }
                query Selected { ...QueryFields }
                fragment QueryFields on Query { customer(id: 7) { ...CustomerFields } }
                fragment CustomerFields on Customer { id name }
                fragment UnusedFields on Customer { rating }
                """;

        String selected = DatabaseGraphqlLanguage.selectedOperationDocument(query, "Selected");
        assertEquals(query.length(), selected.length(), selected);
        assertEquals(query.indexOf("query Selected"), selected.indexOf("query Selected"), selected);
        assertEquals(query.indexOf("fragment QueryFields"), selected.indexOf("fragment QueryFields"), selected);
        assertEquals(query.indexOf("fragment CustomerFields"), selected.indexOf("fragment CustomerFields"), selected);
        assertTrue(selected.contains("fragment QueryFields on Query"), selected);
        assertTrue(selected.contains("fragment CustomerFields on Customer"), selected);
        assertFalse(selected.contains("Unselected"), selected);
        assertFalse(selected.contains("UnusedFields"), selected);
        assertTrue(DatabaseGraphqlLanguage.documentPlan(selected).length() > 0, selected);
        assertEquals(selected, DatabaseGraphqlEngine.selectedOperationDocument(query, "Selected"));
        assertEquals(selected, DatabaseGraphqlLanguage.selectedOperationDocument(query,
                DatabaseGraphqlLanguage.lexicalTokenStream(query), "Selected"));
    }

    @Test
    void preservesOriginalLocationsWhileMaskingAnUnselectedClosure() {
        String query = "query Ignored { customer(id: 8) { id } } "
                + "query Selected { customer(id: 7) { ...CustomerFields } } "
                + "fragment CustomerFields on Customer @include(if: true) { id }";

        String selected = DatabaseGraphqlLanguage.selectedOperationDocument(query, "Selected");

        assertEquals(query.length(), selected.length(), selected);
        assertEquals(query.indexOf("query Selected"), selected.indexOf("query Selected"), selected);
        assertEquals(query.indexOf("fragment CustomerFields"), selected.indexOf("fragment CustomerFields"), selected);
        assertEquals(query.indexOf("@include"), selected.indexOf("@include"), selected);
        assertFalse(selected.contains("Ignored"), selected);
    }

    @Test
    void carriesAParsedSelectionIndexForOperationFieldsAndFragmentDefinitions() {
        String selected = "query Lookup { alias: customer(id: 7) @include(if: true) { ...CustomerFields } }\n"
                + "fragment CustomerFields on Customer { id owner { name } }";
        String plan = DatabaseGraphqlLanguage.documentPlan(selected);
        int root = selected.indexOf("{ alias:");
        int customer = selected.indexOf("alias:");
        int owner = selected.indexOf("owner");
        int ownerSelection = selected.indexOf("{ name }");

        assertTrue(plan.contains("|O" + root + ":"), plan);
        assertTrue(plan.contains("F" + customer + ":"), plan);
        assertTrue(plan.contains("N" + customer + ":"), plan);
        assertEquals("alias", DatabaseGraphqlLanguage.fieldResponseKey(selected, plan, customer));
        assertEquals("customer", DatabaseGraphqlLanguage.fieldName(selected, plan, customer));
        assertEquals(selected.indexOf("customer"), DatabaseGraphqlLanguage.rangeStart(
                DatabaseGraphqlLanguage.fieldNameRange(selected, plan, customer)));
        assertEquals("owner", DatabaseGraphqlLanguage.fieldResponseKey(selected, plan, owner));
        assertEquals("owner", DatabaseGraphqlLanguage.fieldName(selected, plan, owner));
        String fragmentInfo = DatabaseGraphqlLanguage.fragmentDefinitionInfo(selected, plan, "CustomerFields");
        assertEquals(selected.indexOf("Customer", selected.indexOf("on Customer")),
                DatabaseGraphqlLanguage.fragmentDefinitionInfoValue(fragmentInfo, 0));
        assertEquals(selected.indexOf("{ id owner { name } }"),
                DatabaseGraphqlLanguage.fragmentDefinitionInfoValue(fragmentInfo, 3));
        assertEquals("", DatabaseGraphqlLanguage.fragmentDefinitionInfo(selected, plan, "MissingFields"));
        int customerSelection = DatabaseGraphqlLanguage.fieldSelectionStart(selected, plan, customer);
        String spreadItem = DatabaseGraphqlLanguage.selectionItemInfo(plan, customerSelection, 0);
        assertEquals(selected.indexOf("...CustomerFields"), DatabaseGraphqlLanguage.planInfoValue(spreadItem, 0));
        assertTrue(spreadItem.endsWith(":P"), spreadItem);
        String spreadInfo = DatabaseGraphqlLanguage.namedFragmentSpreadInfo(plan,
                DatabaseGraphqlLanguage.planInfoValue(spreadItem, 0));
        assertEquals(selected.indexOf("CustomerFields", selected.indexOf("...CustomerFields")),
                DatabaseGraphqlLanguage.planInfoValue(spreadInfo, 0));
        assertEquals(root, DatabaseGraphqlLanguage.firstOperationSelectionStart(selected, plan));
        assertEquals(selected.indexOf("@include"), DatabaseGraphqlLanguage.fieldHeaderEnd(selected, plan, customer));
        assertEquals(selected.indexOf("{ ...CustomerFields }"),
                DatabaseGraphqlLanguage.fieldSelectionStart(selected, plan, customer));
        assertEquals(ownerSelection, DatabaseGraphqlLanguage.fieldSelectionStart(selected, plan, owner));
        assertEquals(-1, DatabaseGraphqlLanguage.fieldSelectionStart(selected, plan, selected.indexOf("name")));

        String emptyChild = "{ customer(id: 7) { } }";
        String emptyChildPlan = DatabaseGraphqlLanguage.documentPlan(emptyChild);
        assertTrue(emptyChildPlan.length() != 0, emptyChildPlan);
        assertEquals(emptyChild.indexOf("{ }"), DatabaseGraphqlLanguage.fieldSelectionStart(emptyChild,
                emptyChildPlan, emptyChild.indexOf("customer")));
    }

    @Test
    void carriesCompleteArgumentValueRangesInTheSelectionIndex() {
        String operation = "query Lookup($id: Int!) { alias: customer(id: $id, filter: { note: \"data\", ids: [7, 8] }) { id } }";
        String plan = DatabaseGraphqlLanguage.documentPlan(operation);
        int customer = operation.indexOf("alias:");
        int idStart = operation.indexOf("$id", operation.indexOf("customer("));
        int filterStart = operation.indexOf("{ note:");

        assertEquals("$id", operation.substring(
                DatabaseGraphqlLanguage.rangeStart(DatabaseGraphqlLanguage.fieldArgumentValueRange(
                        operation, plan, customer, "id")),
                DatabaseGraphqlLanguage.rangeEnd(DatabaseGraphqlLanguage.fieldArgumentValueRange(
                        operation, plan, customer, "id"))));
        assertEquals(idStart + ":" + (idStart + "$id".length()),
                DatabaseGraphqlLanguage.fieldArgumentValueRange(operation, plan, customer, "id"));
        String filterRange = DatabaseGraphqlLanguage.fieldArgumentValueRange(operation, plan, customer, "filter");
        assertEquals("{ note: \"data\", ids: [7, 8] }", operation.substring(
                DatabaseGraphqlLanguage.rangeStart(filterRange), DatabaseGraphqlLanguage.rangeEnd(filterRange)));
        assertEquals(filterStart, DatabaseGraphqlLanguage.rangeStart(filterRange));
        assertEquals("", DatabaseGraphqlLanguage.fieldArgumentValueRange(operation, plan, customer, "missing"));
        assertEquals(2, DatabaseGraphqlLanguage.fieldArgumentCount(plan, customer));
        assertTrue(DatabaseGraphqlLanguage.fieldHasOnlyArguments(operation, plan, customer, ",id,filter,"));
        assertFalse(DatabaseGraphqlLanguage.fieldHasOnlyArguments(operation, plan, customer, ",id,"));

        String duplicate = "{ customer(id: 7, id: 8) { id } }";
        String duplicatePlan = DatabaseGraphqlLanguage.documentPlan(duplicate);
        assertEquals(2, DatabaseGraphqlLanguage.fieldArgumentCount(duplicatePlan, duplicate.indexOf("customer")));
        assertFalse(DatabaseGraphqlLanguage.fieldHasOnlyArguments(duplicate, duplicatePlan,
                duplicate.indexOf("customer"), ",id,"));
    }

    @Test
    void comparesMergeHeadsFromLexicalTokensRatherThanIgnoredSourceCharacters() {
        String operation = "{ one: customer(id: 7) { id } two: customer( id : 7 ) { id } "
                + "three: customer(id: 8) { id } }";
        String plan = DatabaseGraphqlLanguage.documentPlan(operation);
        int one = operation.indexOf("one:");
        int two = operation.indexOf("two:");
        int three = operation.indexOf("three:");
        int oneName = DatabaseGraphqlLanguage.rangeStart(DatabaseGraphqlLanguage.fieldNameRange(operation, plan, one));
        int twoName = DatabaseGraphqlLanguage.rangeStart(DatabaseGraphqlLanguage.fieldNameRange(operation, plan, two));
        int threeName = DatabaseGraphqlLanguage.rangeStart(DatabaseGraphqlLanguage.fieldNameRange(operation, plan, three));
        int oneEnd = DatabaseGraphqlLanguage.fieldHeaderEnd(operation, plan, one);
        int twoEnd = DatabaseGraphqlLanguage.fieldHeaderEnd(operation, plan, two);
        int threeEnd = DatabaseGraphqlLanguage.fieldHeaderEnd(operation, plan, three);

        assertTrue(DatabaseGraphqlLanguage.tokenRangesEqual(operation, plan, oneName, oneEnd, twoName, twoEnd));
        assertFalse(DatabaseGraphqlLanguage.tokenRangesEqual(operation, plan, oneName, oneEnd, threeName, threeEnd));
    }

    @Test
    void retainsAnEscapedTripleQuoteBlockStringAsOneArgumentValue() {
        String operation = "{ country(code: " + "\"\"\"" + "NL\\\"\"\"" + "\"\"\""
                + ") { code } }";
        String plan = DatabaseGraphqlLanguage.documentPlan(operation);

        assertTrue(plan.length() != 0, plan);
        String range = DatabaseGraphqlLanguage.fieldArgumentValueRange(operation, plan,
                operation.indexOf("country"), "code");
        assertTrue(DatabaseGraphqlLanguage.rangeEnd(range) > DatabaseGraphqlLanguage.rangeStart(range), range);
    }

    @Test
    void carriesDirectiveNodesAndTheirIfArgumentValues() {
        String operation = "query Lookup($visible: Boolean!) @skip(if: false) { "
                + "customer(id: 7) @include(if: $visible) { id } }";
        String plan = DatabaseGraphqlLanguage.documentPlan(operation);
        int operationDirective = operation.indexOf("@skip");
        int fieldDirective = operation.indexOf("@include");
        int rootSelection = operation.indexOf("{ customer");

        assertEquals("skip", DatabaseGraphqlLanguage.directiveName(operation, plan, operationDirective));
        assertEquals("include", DatabaseGraphqlLanguage.directiveName(operation, plan, fieldDirective));
        assertTrue(DatabaseGraphqlLanguage.directiveEnd(plan, fieldDirective) > fieldDirective);
        assertEquals("false", operation.substring(
                DatabaseGraphqlLanguage.rangeStart(DatabaseGraphqlLanguage.fieldArgumentValueRange(
                        operation, plan, operationDirective, "if")),
                DatabaseGraphqlLanguage.rangeEnd(DatabaseGraphqlLanguage.fieldArgumentValueRange(
                        operation, plan, operationDirective, "if"))));
        assertEquals(1, DatabaseGraphqlLanguage.nodeArgumentCount(plan, fieldDirective));
        assertTrue(DatabaseGraphqlLanguage.nodeHasOnlyArguments(operation, plan, fieldDirective, ",if,"));
        assertTrue(DatabaseGraphqlLanguage.hasDirectiveBefore(plan, rootSelection));
        assertEquals(rootSelection, DatabaseGraphqlLanguage.nextTokenStartAtOrAfter(plan,
                DatabaseGraphqlLanguage.directiveEnd(plan, operationDirective)));
        assertTrue(DatabaseGraphqlLanguage.tokenIsPunctuationAt(operation, plan, rootSelection, '{'));
        assertEquals(operation.indexOf("{ id }"), DatabaseGraphqlLanguage.nextTokenStartAtOrAfter(plan,
                DatabaseGraphqlLanguage.directiveEnd(plan, fieldDirective)));
    }

    @Test
    void carriesParsedOperationVariableDefinitionsIncludingNestedDefaults() {
        String operation = "query Lookup($ids: [ Int! ] = [7, 8], $filter: Filter = { note: \"} data\" }, "
                + "$include: Boolean!) { customer(id: $ids) @include(if: $include) { id } }";
        String plan = DatabaseGraphqlLanguage.documentPlan(operation);
        int ids = operation.indexOf("$ids");
        int filter = operation.indexOf("$filter");
        int include = operation.indexOf("$include");
        int includeUse = operation.lastIndexOf("$include");
        int idsUse = operation.lastIndexOf("$ids");

        assertTrue(plan.contains("V" + ids + ":"), plan);
        assertEquals(3, DatabaseGraphqlLanguage.variableDefinitionCount(plan));
        assertEquals(ids, DatabaseGraphqlLanguage.variableDefinitionStart(operation, plan, "ids"));
        assertEquals(filter, DatabaseGraphqlLanguage.variableDefinitionStart(operation, plan, "filter"));
        assertEquals(include, DatabaseGraphqlLanguage.variableDefinitionStartAt(plan, 2));
        assertEquals(ids + "$ids".length(), DatabaseGraphqlLanguage.variableDefinitionNameEnd(plan, ids));
        assertEquals(operation.indexOf("[ Int! ]"), DatabaseGraphqlLanguage.variableTypeStart(plan, ids));
        assertEquals(operation.indexOf("= [7"), DatabaseGraphqlLanguage.variableTypeEnd(plan, ids));
        assertEquals("[Int!]", DatabaseGraphqlLanguage.tokenRangeText(operation, plan,
                DatabaseGraphqlLanguage.variableTypeStart(plan, ids),
                DatabaseGraphqlLanguage.variableTypeEnd(plan, ids)));
        assertEquals(operation.indexOf("[7, 8]"), DatabaseGraphqlLanguage.variableDefaultValueStart(plan, ids));
        assertEquals(operation.indexOf(", $filter"), DatabaseGraphqlLanguage.variableDefaultValueEnd(plan, ids));
        assertEquals(-1, DatabaseGraphqlLanguage.variableDefinitionStart(operation, plan, "missing"));
        assertEquals(-1, DatabaseGraphqlLanguage.variableDefaultValueStart(plan, include));
        assertEquals(2, DatabaseGraphqlLanguage.variableReferenceCount(plan));
        assertEquals(idsUse, DatabaseGraphqlLanguage.variableReferenceStartAt(plan, 0));
        assertEquals(includeUse, DatabaseGraphqlLanguage.variableReferenceStartAt(plan, 1));
        assertEquals(includeUse + "$include".length(), DatabaseGraphqlLanguage.variableReferenceNameEnd(plan, includeUse));
        assertEquals(-1, DatabaseGraphqlLanguage.variableDefinitionCount("not a language plan"));
        String malformedHeaderPlan = DatabaseGraphqlLanguage.documentPlan(
                "query Broken($id Int) { customer(id: 7) { id } }");
        assertTrue(malformedHeaderPlan.contains("X;"), malformedHeaderPlan);
        assertEquals(-1, DatabaseGraphqlLanguage.variableDefinitionCount(malformedHeaderPlan));
    }

    @Test
    void identifiesDefaultValueSemanticsFromLexicalTokens() {
        String operation = "query Defaults($required: Boolean! = null, $nested: Filter = { "
                + "text: \"$notAVariable\", values: [$actual] }) { customer(id: 7) { id } }";
        String plan = DatabaseGraphqlLanguage.documentPlan(operation);
        int required = operation.indexOf("$required");
        int nested = operation.indexOf("$nested");
        int nullStart = DatabaseGraphqlLanguage.variableDefaultValueStart(plan, required);
        int nullEnd = DatabaseGraphqlLanguage.variableDefaultValueEnd(plan, required);
        int nestedStart = DatabaseGraphqlLanguage.variableDefaultValueStart(plan, nested);
        int nestedEnd = DatabaseGraphqlLanguage.variableDefaultValueEnd(plan, nested);

        assertTrue(DatabaseGraphqlLanguage.valueIsNullLiteral(operation, plan, nullStart, nullEnd));
        assertEquals("", DatabaseGraphqlLanguage.valueVariableReferenceRange(operation, plan, nullStart, nullEnd));
        String reference = DatabaseGraphqlLanguage.valueVariableReferenceRange(operation, plan, nestedStart, nestedEnd);
        assertEquals("$actual", operation.substring(DatabaseGraphqlLanguage.rangeStart(reference),
                DatabaseGraphqlLanguage.rangeEnd(reference)));
    }

    @Test
    void walksGraphqlInputObjectAndListValuesFromTheLexicalCarrier() {
        String input = " { country: \"NL\", nested: { codes: [\"BE\", \"NL\"] } } ";
        String inputTokens = DatabaseGraphqlLanguage.lexicalTokenStream(input);

        assertTrue(DatabaseGraphqlLanguage.graphqlInputObjectHasOnlyFields(input, inputTokens, "country,nested"));
        assertFalse(DatabaseGraphqlLanguage.graphqlInputObjectHasOnlyFields(input, "country"));
        assertEquals("\"NL\"", DatabaseGraphqlLanguage.graphqlInputObjectFieldValue(input, "country"));
        assertEquals("{ codes: [\"BE\", \"NL\"] }",
                DatabaseGraphqlLanguage.graphqlInputObjectFieldValue(input, "nested"));

        String list = " [ { code: \"NL\" }, 7, [true, false] ] ";
        String listTokens = DatabaseGraphqlLanguage.lexicalTokenStream(list);
        assertEquals(3, DatabaseGraphqlLanguage.graphqlInputListValueCount(list, listTokens));
        assertEquals("{ code: \"NL\" }", DatabaseGraphqlLanguage.graphqlInputListValue(list, listTokens, 0));
        assertEquals("7", DatabaseGraphqlLanguage.graphqlInputListValue(list, 1));
        assertEquals("[true, false]", DatabaseGraphqlLanguage.graphqlInputListValue(list, 2));
        assertEquals("", DatabaseGraphqlLanguage.graphqlInputListValue(list, 3));
    }
}
