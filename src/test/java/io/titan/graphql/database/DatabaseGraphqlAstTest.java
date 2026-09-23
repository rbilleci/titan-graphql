package io.titan.graphql.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DatabaseGraphqlAstTest {

    @Test
    void materializesTypedExecutableNodesWithoutRelexingTheLanguagePlan() {
        String source = "query Lookup($id: Int!) { customer(id: $id) @include(if: true) { id name } }";
        String plan = DatabaseGraphqlLanguage.documentPlan(source);
        String ast = DatabaseGraphqlAst.parse(source, plan);
        int root = source.indexOf("{ customer");
        int customer = source.indexOf("customer");
        int child = source.indexOf("{ id name }");

        assertEquals(9, DatabaseGraphqlAst.nodeCount(ast), ast);
        assertEquals("o", DatabaseGraphqlAst.nodeKind(ast, 0));
        assertEquals(root, DatabaseGraphqlAst.nodeSourceStart(ast, 0));
        assertEquals(-1, DatabaseGraphqlAst.nodeParentSelection(ast, 0));
        assertEquals(root, DatabaseGraphqlAst.operationSelectionStart(ast));
        assertEquals("query", DatabaseGraphqlAst.operationKind(ast));
        assertEquals("query", DatabaseGraphqlAst.nodePayload(ast, 0));
        assertEquals("v", DatabaseGraphqlAst.nodeKind(ast, 1));
        assertEquals("a", DatabaseGraphqlAst.nodeKind(ast, 2));
        assertEquals("f", DatabaseGraphqlAst.nodeKind(ast, 3));
        assertEquals(customer, DatabaseGraphqlAst.nodeSourceStart(ast, 3));
        assertEquals(root, DatabaseGraphqlAst.nodeParentSelection(ast, 3));
        assertEquals(root, DatabaseGraphqlAst.fieldParentSelection(ast, customer));
        assertEquals(customer, DatabaseGraphqlAst.selectionOwnerFieldStart(ast, child));
        assertEquals("f", DatabaseGraphqlAst.nodeKind(ast, 4));
        assertEquals(child, DatabaseGraphqlAst.nodeParentSelection(ast, 4));
        assertEquals("u", DatabaseGraphqlAst.nodeKind(ast, 6));
        assertEquals("a", DatabaseGraphqlAst.nodeKind(ast, 7));
        assertEquals("d", DatabaseGraphqlAst.nodeKind(ast, 8));
        int directive = source.indexOf("@include");
        int directiveName = directive + 1;
        assertEquals(directive + ":" + (directive + "@include(if: true)".length()) + ":"
                        + directiveName + ":" + (directiveName + "include".length()),
                DatabaseGraphqlAst.directiveInfoAtOrAfter(source, ast, directive));
        assertEquals(1, DatabaseGraphqlAst.fieldArgumentCount(ast, directive));
        assertEquals("", DatabaseGraphqlAst.directiveInfoAtOrAfter(source, ast, source.indexOf("{ id name }")));
    }

    @Test
    void retainsTheSelectedOperationDirectiveBoundaryInTheTypedAst() {
        String source = "query Lookup($visible: Boolean!) @include(if: $visible) { customer(id: 7) { id } }";
        String ast = DatabaseGraphqlAst.parse(source);

        assertEquals(source.indexOf("@include"), DatabaseGraphqlAst.operationDirectiveStart(ast));
        assertEquals(-1, DatabaseGraphqlAst.operationDirectiveStart(DatabaseGraphqlAst.parse("{ customer(id: 7) { id } }")));
    }

    @Test
    void retainsTheFirstNamedFragmentDefinitionDirectiveBoundary() {
        String source = "query { customer(id: 7) { ...CustomerFields } } "
                + "fragment CustomerFields on Customer @include(if: true) { id }";

        assertEquals(source.indexOf("@include"),
                DatabaseGraphqlAst.fragmentDefinitionDirectiveStart(DatabaseGraphqlAst.parse(source)));
        assertEquals(-1, DatabaseGraphqlAst.fragmentDefinitionDirectiveStart(DatabaseGraphqlAst.parse(
                source.replace(" @include(if: true)", ""))));
    }

    @Test
    void countsAllExecutableFieldsWithOneScalarAstPass() {
        String source = """
                query Work {
                  customer { id ...CustomerFields }
                }
                fragment CustomerFields on Customer { name orders { id } }
                """;
        String ast = DatabaseGraphqlAst.parse(source);

        assertEquals(5, DatabaseGraphqlAst.executableFieldCount(ast), ast);
        assertEquals(-1, DatabaseGraphqlAst.executableFieldCount("ast1;broken"));
    }

    @Test
    void retainsFragmentTopologyAndSyntaxErrorsAsTypedNodes() {
        String source = "query Lookup { customer(id: 7) { ...CustomerFields } } "
                + "fragment CustomerFields on Customer { id }";
        String ast = DatabaseGraphqlAst.parse(source);

        assertTrue(ast.contains("Np:" + source.indexOf("...CustomerFields") + ":"), ast);
        assertTrue(ast.contains("Nr:" + source.indexOf("CustomerFields", source.indexOf("fragment")) + ":n:"), ast);
        assertEquals(source.indexOf("Customer", source.indexOf("on Customer")) + ":"
                        + (source.indexOf("Customer", source.indexOf("on Customer")) + "Customer".length()) + ":"
                        + source.indexOf("{ id }") + ":" + source.indexOf("{ id }") + ":"
                        + (source.indexOf("{ id }") + "{ id }".length() - 1),
                DatabaseGraphqlAst.fragmentDefinitionInfo(source, ast, "CustomerFields"));
        assertEquals("", DatabaseGraphqlAst.fragmentDefinitionInfo(source, ast, "MissingFields"));
        assertEquals(1, DatabaseGraphqlAst.typeConditionCount(ast));
        String condition = DatabaseGraphqlAst.typeConditionInfo(ast, 0);
        assertEquals(source.indexOf("CustomerFields", source.indexOf("fragment")),
                DatabaseGraphqlLanguage.planInfoValue(condition, 0));
        assertEquals(source.indexOf("Customer", source.indexOf("on Customer")),
                DatabaseGraphqlLanguage.planInfoValue(condition, 1));

        String malformed = "query Broken($id Int) { customer(id: 7) { id } }";
        String errorAst = DatabaseGraphqlAst.parse(malformed);
        assertEquals(1, DatabaseGraphqlAst.nodeCount(errorAst), errorAst);
        assertEquals("e", DatabaseGraphqlAst.nodeKind(errorAst, 0));
        assertEquals("syntax", DatabaseGraphqlAst.nodePayload(errorAst, 0));
        assertEquals(-1, DatabaseGraphqlAst.operationSelectionStart(errorAst));
        assertEquals("", DatabaseGraphqlAst.operationKind(errorAst));
    }

    @Test
    void enumeratesNamedAndTypedInlineFragmentConditionsOnly() {
        String source = "{ customer { ... on Node { id } ... @skip(if: false) { name } ...Fields } } "
                + "fragment Fields on Customer { id }";
        String ast = DatabaseGraphqlAst.parse(source);

        assertEquals(2, DatabaseGraphqlAst.typeConditionCount(ast), ast);
        String inline = DatabaseGraphqlAst.typeConditionInfo(ast, 0);
        String named = DatabaseGraphqlAst.typeConditionInfo(ast, 1);
        assertEquals("Node", source.substring(
                DatabaseGraphqlLanguage.planInfoValue(inline, 1),
                DatabaseGraphqlLanguage.planInfoValue(inline, 2)));
        assertEquals("Customer", source.substring(
                DatabaseGraphqlLanguage.planInfoValue(named, 1),
                DatabaseGraphqlLanguage.planInfoValue(named, 2)));
    }

    @Test
    void exposesDirectSelectionItemsWithoutReturningToSyntaxTopologyRecords() {
        String source = "query Lookup { customer(id: 7) { id ... on Customer { name } ...CustomerFields } } "
                + "fragment CustomerFields on Customer { email }";
        String ast = DatabaseGraphqlAst.parse(source);
        int customerSelection = source.indexOf("{ id ...");
        int id = source.indexOf("id ...");
        int inline = source.indexOf("... on Customer");
        int named = source.indexOf("...CustomerFields");

        assertEquals(3, DatabaseGraphqlAst.selectionItemCount(ast, customerSelection));
        assertTrue(DatabaseGraphqlAst.selectionItemInfo(ast, customerSelection, 0).startsWith(id + ":F:"), ast);
        assertTrue(DatabaseGraphqlAst.selectionItemInfo(ast, customerSelection, 1).startsWith(inline + ":I:"), ast);
        assertTrue(DatabaseGraphqlAst.selectionItemInfo(ast, customerSelection, 2).startsWith(named + ":P:"), ast);
        assertEquals("", DatabaseGraphqlAst.selectionItemInfo(ast, customerSelection, 3));
        assertEquals(-1, DatabaseGraphqlAst.selectionItemCount("", customerSelection));
    }

    @Test
    void compilesOneDirectSelectionPlanOrDefersToTheFragmentWalker() {
        String rootSource = "{ customer { id } }";
        String rootAst = DatabaseGraphqlAst.parse(rootSource);
        assertEquals("v1;" + rootSource.indexOf("customer") + ";",
                DatabaseGraphqlAst.directFieldSelectionPlan(rootAst, rootSource.indexOf('{')),
                "the operation record also contains the root offset but is not a selection item");

        String directSource = "query Lookup { customer { id name } }";
        String directAst = DatabaseGraphqlAst.parse(directSource);
        int directSelection = directSource.indexOf("{ id name }");
        assertEquals("v1;" + directSource.indexOf("id name") + ";" + directSource.indexOf("name") + ";",
                DatabaseGraphqlAst.directFieldSelectionPlan(directAst, directSelection));

        String fragmentSource = "query Lookup { customer { id ...CustomerFields } } "
                + "fragment CustomerFields on Customer { name }";
        String fragmentAst = DatabaseGraphqlAst.parse(fragmentSource);
        assertEquals("fragment;", DatabaseGraphqlAst.directFieldSelectionPlan(fragmentAst,
                fragmentSource.indexOf("{ id ...CustomerFields }")));
        assertEquals("", DatabaseGraphqlAst.directFieldSelectionPlan("", directSelection));
    }

    @Test
    void retainsCompleteFieldMetadataIncludingAliasIdentity() {
        String source = "query Lookup { selected: customer(id: 7) { id } }";
        String ast = DatabaseGraphqlAst.parse(source);
        int field = source.indexOf("selected");
        int childSelection = source.indexOf("{ id }");
        int headerEnd = childSelection;
        int name = source.indexOf("customer");

        assertEquals(headerEnd + ":" + childSelection + ":" + field + ":"
                        + (field + "selected".length()) + ":" + name + ":"
                        + (name + "customer".length()),
                DatabaseGraphqlAst.fieldInfo(ast, field));
        assertEquals(headerEnd, DatabaseGraphqlAst.fieldInfoValue(ast, field, 0));
        assertEquals(childSelection, DatabaseGraphqlAst.fieldInfoValue(ast, field, 1));
        assertEquals(field, DatabaseGraphqlAst.fieldInfoValue(ast, field, 2));
        assertEquals(field + "selected".length(), DatabaseGraphqlAst.fieldInfoValue(ast, field, 3));
        assertEquals(name, DatabaseGraphqlAst.fieldInfoValue(ast, field, 4));
        assertEquals(name + "customer".length(), DatabaseGraphqlAst.fieldInfoValue(ast, field, 5));
        assertEquals(-1, DatabaseGraphqlAst.fieldInfoValue(ast, source.length(), 0));
        assertEquals(field, DatabaseGraphqlAst.selectionOwnerFieldStart(ast, childSelection));
        assertEquals("", DatabaseGraphqlAst.fieldInfo(ast, source.length()));
    }

    @Test
    void retainsFieldArgumentsAndVariableDeclarationRanges() {
        String source = "query Lookup($id: Int! = 7) { customer(id: $id, active: true) { id } }";
        String ast = DatabaseGraphqlAst.parse(source);
        int field = source.indexOf("customer");
        int idName = source.indexOf("id:", field);
        int idValue = source.indexOf("$id", field);
        int activeName = source.indexOf("active", field);
        int activeValue = source.indexOf("true", field);
        int variable = source.indexOf("$id");
        int typeStart = source.indexOf("Int!");
        int typeEnd = source.indexOf('=');
        int defaultStart = source.indexOf("7)");

        assertEquals(2, DatabaseGraphqlAst.fieldArgumentCount(ast, field));
        assertEquals(idName + ":" + (idName + 2) + ":" + idValue + ":" + (idValue + 3),
                DatabaseGraphqlAst.fieldArgumentInfo(ast, field, 0));
        assertEquals(activeName + ":" + (activeName + 6) + ":" + activeValue + ":" + (activeValue + 4),
                DatabaseGraphqlAst.fieldArgumentInfo(ast, field, 1));
        assertEquals(idValue + ":" + (idValue + 3),
                DatabaseGraphqlAst.fieldArgumentValueRange(source, ast, field, "id"));
        assertEquals(activeValue + ":" + (activeValue + 4),
                DatabaseGraphqlAst.fieldArgumentValueRange(source, ast, field, "active"));
        assertEquals("", DatabaseGraphqlAst.fieldArgumentValueRange(source, ast, field, "missing"));
        assertEquals(variable + ":" + typeStart + ":" + typeEnd + ":" + defaultStart + ":"
                        + (defaultStart + 1),
                DatabaseGraphqlAst.variableDefinitionInfo(source, ast, "id"));
        assertEquals("", DatabaseGraphqlAst.variableDefinitionInfo(source, ast, "missing"));
        assertEquals("7", DatabaseGraphqlEngine.argumentValueFromAst(source, ast, field, "id", "{}"));
        assertEquals("true", DatabaseGraphqlEngine.argumentValueFromAst(source, ast, field, "active", "{}"));
        assertTrue(DatabaseGraphqlEngine.argumentVariableTypeIsCompatibleFromAst(source, ast, field, "id", "Int"));
        assertTrue(DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(source, ast, field, ",id,active,"));
    }

    @Test
    void validatesVariableUseWithStructuralListAndNonNullCompatibility() {
        String source = "query Lookup($values: [[Int!]!]!) { customer(id: $values) { id } }";
        String ast = DatabaseGraphqlAst.parse(source);
        int field = source.indexOf("customer");

        assertTrue(DatabaseGraphqlEngine.argumentVariableTypeIsCompatibleFromAst(
                source, ast, field, "id", "[[Int]]"));
        assertFalse(DatabaseGraphqlEngine.argumentVariableTypeIsCompatibleFromAst(
                source, ast, field, "id", "[[String]]"));
    }

    @Test
    void readsDefaultValueSemanticsWithoutReturningToTheLexicalPlan() {
        String nullDefault = "null";
        String nestedDefault = "{ text: \"$notAVariable\", block: \"\"\"$alsoNotAVariable\"\"\", values: [$actual] }";
        int actual = nestedDefault.indexOf("$actual");

        assertEquals("1:n:n", DatabaseGraphqlAst.defaultValueInfo(nullDefault, 0, nullDefault.length()));
        assertEquals("0:" + actual + ":" + (actual + "$actual".length()),
                DatabaseGraphqlAst.defaultValueInfo(nestedDefault, 0, nestedDefault.length()));
        assertEquals("", DatabaseGraphqlAst.defaultValueInfo(nestedDefault, 3, 3));
    }

    @Test
    void comparesMergeRangesWithoutTheLegacyTokenPlan() {
        String source = "customer(id: 7, note: \"a b\") @skip(if: false) "
                + "customer( id: 7 # ignored\n, note: \"a b\" ) @skip(if:false) "
                + "customer(id: 7, note: \"ab\") @skip(if: false)";
        int firstStart = source.indexOf("customer");
        int secondStart = source.indexOf("customer", firstStart + 1);
        int thirdStart = source.indexOf("customer", secondStart + 1);

        assertTrue(DatabaseGraphqlAst.tokenRangesEqual(source, firstStart, secondStart, secondStart, thirdStart));
        assertFalse(DatabaseGraphqlAst.tokenRangesEqual(source, firstStart, secondStart, thirdStart, source.length()));
    }
}
