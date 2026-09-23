package io.titan.graphql.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DatabaseGraphqlInputDescriptorTest {

    private static final String INPUT_DESCRIPTOR = "id1;B:Boolean;N:Int;S:String;"
            + "E:CustomerStatus:ACTIVE|INACTIVE;E:SortDirection:ASC|DESC;"
            + "O:StringFilter:contains=String,in=[String!],isNull=Boolean;"
            + "O:CustomerFilter:name=StringFilter;"
            + "O:CustomerOrderBy:name=SortDirection;";
    private static final String TRUSTED_CONTEXT = "{\"contextVersion\":\""
            + DatabaseGraphqlEngine.TRUSTED_CONTEXT_VERSION + "\"}";
    private static final String OUTPUT_SCHEMA_DESCRIPTOR = outputSchemaDescriptor();

    @Test
    void appliesGeneratedInputObjectsEnumsAndNestedListsBeforeSchemaDispatch() {
        assertEquals("", preflight("query Input($filter: CustomerFilter!) { customer(filter: $filter) }",
                "{\"filter\":{\"name\":{\"contains\":\"North\"}}}"));
        assertEquals("", preflight("query Input($order: [CustomerOrderBy!]) { customer(orderBy: $order) }",
                "{\"order\":[{\"name\":\"DESC\"}]}"));
        // GraphQL's input coercion promotes a non-list value to a one-element list. The generated
        // field binding is still responsible for its schema-specific cardinality policy.
        assertEquals("", preflight("query Input($filter: CustomerFilter!) { customer(filter: $filter) }",
                "{\"filter\":{\"name\":{\"in\":\"North\"}}}"));

        assertInputObjectFailure("query Input($filter: CustomerFilter!) { customer(filter: $filter) }",
                "{\"filter\":7}");
        assertInputObjectFailure("query Input($filter: CustomerFilter!) { customer(filter: $filter) }",
                "{\"filter\":{\"name\":{\"unknown\":\"North\"}}}");
        assertCoercionFailure("query Input($filter: CustomerFilter!) { customer(filter: $filter) }",
                "{\"filter\":{\"name\":{\"in\":[7]}}}");
        assertCoercionFailure("query Input($order: [CustomerOrderBy!]) { customer(orderBy: $order) }",
                "{\"order\":[{\"name\":\"SIDEWAYS\"}]}");
    }

    @Test
    void validatesDatabaseOutputEnumValuesAgainstGeneratedMetadata() {
        assertTrue(DatabaseGraphqlEngine.enumValueIsAllowed("ACTIVE", "ACTIVE|INACTIVE"));
        assertTrue(DatabaseGraphqlEngine.enumValueIsAllowed("INACTIVE", "ACTIVE|INACTIVE"));
        assertFalse(DatabaseGraphqlEngine.enumValueIsAllowed("", "ACTIVE|INACTIVE"));
        assertFalse(DatabaseGraphqlEngine.enumValueIsAllowed(null, "ACTIVE|INACTIVE"));
        assertFalse(DatabaseGraphqlEngine.enumValueIsAllowed("ACT", "ACTIVE|INACTIVE"));
        assertFalse(DatabaseGraphqlEngine.enumValueIsAllowed("UNKNOWN", "ACTIVE|INACTIVE"));
    }

    @Test
    void distinguishesGraphqlEnumLiteralsFromJsonVariableStrings() {
        assertEquals("", preflight(
                "query Input($status: CustomerStatus!) { customer(status: $status) }",
                "{\"status\":\"ACTIVE\"}"));
        assertEquals("", preflight(
                "query Input($status: CustomerStatus! = ACTIVE) { customer(status: $status) }",
                "{}"));
        assertCoercionFailure(
                "query Input($status: CustomerStatus! = \"ACTIVE\") { customer(status: $status) }",
                "{}");
        assertCoercionFailure(
                "query Input($status: CustomerStatus!) { customer(status: $status) }",
                "{\"status\":\"UNKNOWN\"}");

        assertEquals("", schemaArgumentPreflight(
                "{ customer(id: 7, status: ACTIVE) { id } }", "{}"));
        String quotedLiteral = schemaArgumentPreflight(
                "{ customer(id: 7, status: \"ACTIVE\") { id } }", "{}");
        assertTrue(quotedLiteral.contains("argument 'Query.customer.status' cannot be coerced to 'CustomerStatus'"),
                quotedLiteral);

        String explicitNullAtNonNullLocation = schemaArgumentPreflight(
                "query Input($id: Int = 7) { customer(id: $id) { id } }", "{\"id\":null}");
        assertTrue(explicitNullAtNonNullLocation
                        .contains("argument 'Query.customer.id' cannot be coerced to 'Int'"),
                explicitNullAtNonNullLocation);
    }

    @Test
    void validatesInputObjectDefaultsUsingTheSameTypedDescriptor() {
        assertEquals("", preflight("query Input($filter: CustomerFilter = { name: { contains: \"North\" } }) "
                + "{ customer(filter: $filter) }", "{}"));
        assertCoercionFailure("query Input($filter: CustomerFilter = { name: { contains: 7 } }) "
                + "{ customer(filter: $filter) }", "{}");
    }

    @Test
    void appliesAuthoredNestedInputFieldDefaultsDuringCanonicalMaterialization() {
        String inputDescriptor = "id1;N:Int;S:String;"
                + "O:RenameCustomerInput:id=Int!,patch=RenameCustomerPatch!;"
                + "O:RenameCustomerPatch:name=String!;"
                + "D:RenameCustomerPatch.name:27:\"Defaulted by input schema\";";
        String queryFields = "change:Customer:0:O|input=RenameCustomerInput!=I;";
        String customerFields = "id:Int:0:S;";
        String schemaDescriptor = "sd1;5:Query" + queryFields.length() + ":" + queryFields
                + "8:Customer" + customerFields.length() + ":" + customerFields;

        String variableQuery = "query Change($input: RenameCustomerInput!) "
                + "{ change(input: $input) { id } }";
        String variableCarrier = argumentCarrier(variableQuery,
                "{\"input\":{\"id\":7,\"patch\":{}}}", schemaDescriptor, inputDescriptor);
        assertTrue(variableCarrier.startsWith("ma1;"), variableCarrier);
        String variableInput = DatabaseGraphqlEngine.materializedArgumentValue(
                variableCarrier, variableQuery.indexOf("change"), "input");
        assertEquals("7", DatabaseGraphqlEngine.inputObjectPathValue(variableInput, "id"));
        assertEquals("\"Defaulted by input schema\"",
                DatabaseGraphqlEngine.inputObjectPathValue(variableInput, "patch.name"));

        String literalQuery = "{ change(input: { id: 8, patch: {} }) { id } }";
        String literalCarrier = argumentCarrier(literalQuery, "{}", schemaDescriptor, inputDescriptor);
        assertTrue(literalCarrier.startsWith("ma1;"), literalCarrier);
        String literalInput = DatabaseGraphqlEngine.materializedArgumentValue(
                literalCarrier, literalQuery.indexOf("change"), "input");
        assertEquals("\"Defaulted by input schema\"",
                DatabaseGraphqlEngine.inputObjectPathValue(literalInput, "patch.name"));
    }

    @Test
    void materializesOmittedFieldArgumentsFromDelimiterSafeSchemaDefaults() {
        String queryFields = "change:Customer:0:O|id=Int!=S=~Nw,name=String!=S=~IkRlZmF1bHQsID07IG5hbWUi;";
        String customerFields = "id:Int:0:S;name:String:0:S;";
        String schemaDescriptor = "sd1;5:Query" + queryFields.length() + ":" + queryFields
                + "8:Customer" + customerFields.length() + ":" + customerFields;
        String inputDescriptor = "id1;N:Int;S:String;";
        String query = "{ change { id name } }";

        String carrier = argumentCarrier(query, "{}", schemaDescriptor, inputDescriptor);
        assertTrue(carrier.startsWith("ma1;"), carrier);
        int change = query.indexOf("change");
        assertEquals("7", DatabaseGraphqlEngine.materializedArgumentValue(carrier, change, "id"));
        assertEquals("\"Default, =; name\"",
                DatabaseGraphqlEngine.materializedArgumentValue(carrier, change, "name"));

        String variableQuery = "query DefaultAtLocation($name: String) "
                + "{ change(name: $name) { id name } }";
        String variableCarrier = argumentCarrier(
                variableQuery, "{}", schemaDescriptor, inputDescriptor);
        assertTrue(variableCarrier.startsWith("ma1;"), variableCarrier);
        assertEquals("\"Default, =; name\"", DatabaseGraphqlEngine.materializedArgumentValue(
                variableCarrier, variableQuery.indexOf("change"), "name"));

        String nullCarrier = argumentCarrier(
                variableQuery, "{\"name\":null}", schemaDescriptor, inputDescriptor);
        assertTrue(nullCarrier.contains("cannot be coerced to 'String'"), nullCarrier);
    }

    @Test
    void rendersDelimiterSafeFieldArgumentDefaultsAsGraphqlSource() {
        String query = "{ __type(name: \"Query\") { fields { name args { name defaultValue } } } }";
        String ast = DatabaseGraphqlAst.parse(query, DatabaseGraphqlLanguage.documentPlan(query));
        String fields = "change:Customer:0:O|name=String!=S=~IkRlZmF1bHQsID07IG5hbWUi;";

        String response = DatabaseGraphqlEngine.introspectionNamedTypeJson(
                query, ast, query.indexOf("__type"), "{}", "", "Query", "OBJECT", fields, "", "", false);

        assertTrue(response.contains("\"name\":\"name\""), response);
        assertTrue(response.contains("\"defaultValue\":\"\\\"Default, =; name\\\"\""), response);
    }

    @Test
    void exposesSingletonListCoercionToGeneratedBindingsWithoutACollectionCarrier() {
        assertEquals(1, DatabaseGraphqlEngine.inputListValueCount("\"North\""));
        assertEquals("\"North\"", DatabaseGraphqlEngine.inputListValue("\"North\"", 0));
        assertEquals(-1, DatabaseGraphqlEngine.inputListValueCount("null"));
        assertEquals("", DatabaseGraphqlEngine.inputListValue("null", 0));
        assertEquals(2, DatabaseGraphqlEngine.inputListValueCount("[\"North\", \"South\"]"));
        assertEquals("\"South\"", DatabaseGraphqlEngine.inputListValue("[\"North\", \"South\"]", 1));
    }

    @Test
    void materializesSelectedOperationVariablesAndDefaultsForGeneratedBindings() {
        String query = "query Input($id: Int! = 7, $name: String = \"North\", $optional: Boolean) "
                + "{ customer(id: $id, name: $name, active: $optional) { id } }";
        String ast = DatabaseGraphqlAst.parse(query, DatabaseGraphqlLanguage.documentPlan(query));
        int customer = query.indexOf("customer");

        String defaults = DatabaseGraphqlEngine.materializedVariableValuesFromAst(query, ast, "{}");
        assertEquals("7", DatabaseGraphqlEngine.materializedVariableValue(defaults, "id"));
        assertEquals("\"North\"", DatabaseGraphqlEngine.materializedVariableValue(defaults, "name"));
        assertEquals("", DatabaseGraphqlEngine.materializedVariableValue(defaults, "optional"));
        assertEquals("7", DatabaseGraphqlEngine.argumentValueFromAstMaterialized(
                query, ast, customer, "id", defaults));

        String supplied = DatabaseGraphqlEngine.materializedVariableValuesFromAst(
                query, ast, "{\"id\":9,\"name\":\"South\",\"optional\":null}");
        assertEquals("9", DatabaseGraphqlEngine.materializedVariableValue(supplied, "id"));
        assertEquals("\"South\"", DatabaseGraphqlEngine.materializedVariableValue(supplied, "name"));
        assertEquals("null", DatabaseGraphqlEngine.materializedVariableValue(supplied, "optional"));
        assertEquals("null", DatabaseGraphqlEngine.argumentValueFromAstMaterialized(
                query, ast, customer, "active", supplied));

        String directiveQuery = "query Plan($enabled: Boolean! = true) { customer(id: 7) @include(if: $enabled) { id } }";
        String directiveAst = DatabaseGraphqlAst.parse(directiveQuery);
        String defaultedDirectiveValues = DatabaseGraphqlEngine.materializedVariableValuesFromAst(
                directiveQuery, directiveAst, "{}");
        assertEquals(1, DatabaseGraphqlEngine.selectionPlanCount(DatabaseGraphqlEngine.rootSelectionPlanFromAst(
                directiveQuery, directiveAst, defaultedDirectiveValues, "Query", "")));
        String disabledDirectiveValues = DatabaseGraphqlEngine.materializedVariableValuesFromAst(
                directiveQuery, directiveAst, "{\"enabled\":false}");
        assertEquals(0, DatabaseGraphqlEngine.selectionPlanCount(DatabaseGraphqlEngine.rootSelectionPlanFromAst(
                directiveQuery, directiveAst, disabledDirectiveValues, "Query", "")));
    }

    @Test
    void collectsEquivalentFieldsWithArgumentsInDifferentSourceOrder() {
        String query = "{ feed(first: 1, after: null) { id } "
                + "feed(after: null, first: 1) { name } }";
        String ast = DatabaseGraphqlAst.parse(query);
        String variables = DatabaseGraphqlEngine.materializedVariableValuesFromAst(query, ast, "{}");
        String plan = DatabaseGraphqlEngine.rootSelectionPlanFromAst(
                query, ast, variables, "Query", "");

        assertEquals(1, DatabaseGraphqlEngine.selectionPlanCount(plan), plan);
        assertFalse(DatabaseGraphqlEngine.selectionPlanHasDuplicateResponseKeyFromAst(query, ast, plan));

        String conflict = "{ feed(first: 1, after: null) { id } "
                + "feed(after: null, first: 2) { name } }";
        String conflictAst = DatabaseGraphqlAst.parse(conflict);
        String conflictVariables = DatabaseGraphqlEngine.materializedVariableValuesFromAst(
                conflict, conflictAst, "{}");
        String conflictPlan = DatabaseGraphqlEngine.rootSelectionPlanFromAst(
                conflict, conflictAst, conflictVariables, "Query", "");
        assertEquals(2, DatabaseGraphqlEngine.selectionPlanCount(conflictPlan), conflictPlan);
        assertTrue(DatabaseGraphqlEngine.selectionPlanHasDuplicateResponseKeyFromAst(
                conflict, conflictAst, conflictPlan));

        String nested = "{ feed(first: 1, filter: { name: { startsWith: \"N\", "
                + "endsWith: \"wind\" } }) { id } "
                + "feed(filter: { name: { endsWith: \"wind\", startsWith: \"N\" } }, "
                + "first: 1) { name } }";
        String nestedAst = DatabaseGraphqlAst.parse(nested);
        String nestedVariables = DatabaseGraphqlEngine.materializedVariableValuesFromAst(
                nested, nestedAst, "{}");
        String nestedPlan = DatabaseGraphqlEngine.rootSelectionPlanFromAst(
                nested, nestedAst, nestedVariables, "Query", "");
        assertEquals(1, DatabaseGraphqlEngine.selectionPlanCount(nestedPlan), nestedPlan);
        assertFalse(DatabaseGraphqlEngine.selectionPlanHasDuplicateResponseKeyFromAst(
                nested, nestedAst, nestedPlan));

        String reorderedList = "{ feed(filter: { ids: [7, 8] }) { id } "
                + "feed(filter: { ids: [8, 7] }) { name } }";
        String reorderedListAst = DatabaseGraphqlAst.parse(reorderedList);
        String reorderedListVariables = DatabaseGraphqlEngine.materializedVariableValuesFromAst(
                reorderedList, reorderedListAst, "{}");
        String reorderedListPlan = DatabaseGraphqlEngine.rootSelectionPlanFromAst(
                reorderedList, reorderedListAst, reorderedListVariables, "Query", "");
        assertEquals(2, DatabaseGraphqlEngine.selectionPlanCount(reorderedListPlan), reorderedListPlan);
        assertTrue(DatabaseGraphqlEngine.selectionPlanHasDuplicateResponseKeyFromAst(
                reorderedList, reorderedListAst, reorderedListPlan));
    }

    @Test
    void rejectsExecutableDirectivesAtTheUnsupportedOperationLocation() {
        String query = "query Operation($enabled: Boolean = true) @include(if: $enabled) "
                + "{ customer(id: 7) { id } }";
        assertTrue(preflight(query, "{}").contains("directive is not allowed on an operation definition"));
        assertTrue(preflight(query, "{\"enabled\":false}")
                .contains("directive is not allowed on an operation definition"));

        String duplicate = "query @include(if: true) @include(if: true) { customer(id: 7) { id } }";
        assertTrue(preflight(duplicate, "{}").contains("directive is not allowed on an operation definition"));

        String fragmentDefinition = "query { customer(id: 7) { ...CustomerFields } } "
                + "fragment CustomerFields on Customer @include(if: true) { id }";
        assertTrue(preflight(fragmentDefinition, "{}")
                .contains("directive is not allowed on a fragment definition"));
    }

    @Test
    void validatesExecutableDirectivesBelowDisabledAncestors() {
        String valid = "query Hidden($disabled: Boolean! = true) { customer(id: 7) "
                + "@skip(if: $disabled) { name @include(if: false) "
                + "... on Customer @skip(if: true) { id } } }";
        assertEquals("", preflight(valid, "{}"));

        String unsupportedChild = "{ customer(id: 7) @skip(if: true) { name @defer } }";
        String unsupportedChildError = preflight(unsupportedChild, "{}");
        assertTrue(unsupportedChildError.contains("invalid or unsupported directive"), unsupportedChildError);
        assertTrue(unsupportedChildError.contains("\"column\":"
                + (unsupportedChild.indexOf("@defer") + 1)), unsupportedChildError);

        String malformedSpread = "{ customer(id: 7) @skip(if: true) "
                + "{ ...Fields @include(unless: false) } } fragment Fields on Customer { id }";
        String malformedSpreadError = preflight(malformedSpread, "{}");
        assertTrue(malformedSpreadError.contains("invalid or unsupported directive"), malformedSpreadError);
        assertTrue(malformedSpreadError.contains("\"column\":"
                + (malformedSpread.indexOf("@include") + 1)), malformedSpreadError);

        String duplicateInline = "{ customer(id: 7) @skip(if: true) "
                + "{ ... on Customer @skip(if: true) @skip(if: false) { id } } }";
        assertTrue(preflight(duplicateInline, "{}").contains("invalid or unsupported directive"));
    }

    @Test
    void validatesDisabledFieldsAgainstTheInstalledSchemaBeforeExecutionPlanning() {
        assertEquals("", schemaPreflight("{ customer(id: 7) @skip(if: true) "
                + "{ id name @include(if: false) } }"));

        String unknownRoot = "{ absent @skip(if: true) }";
        String unknownRootError = schemaPreflight(unknownRoot);
        assertTrue(unknownRootError.contains("field 'Query.absent' is not defined"), unknownRootError);
        assertTrue(unknownRootError.contains("\"column\":3"), unknownRootError);

        String unknownChild = "{ customer(id: 7) @skip(if: true) { absent } }";
        String unknownChildError = schemaPreflight(unknownChild);
        assertTrue(unknownChildError.contains("field 'Customer.absent' is not defined"), unknownChildError);
        assertTrue(unknownChildError.contains("\"column\":" + (unknownChild.indexOf("absent") + 1)),
                unknownChildError);

        String skippedSpread = "{ customer(id: 7) { ...Bad @include(if: false) } } "
                + "fragment Bad on Customer { absent }";
        assertTrue(schemaPreflight(skippedSpread).contains("field 'Customer.absent' is not defined"));

        String hiddenScalarSelection = "{ customer(id: 7) { name @skip(if: true) { invalid } } }";
        assertTrue(schemaPreflight(hiddenScalarSelection)
                .contains("field 'Customer.name' cannot have a selection"));

        String missingRequiredArgument = "{ customer @skip(if: true) { id } }";
        assertTrue(schemaPreflight(missingRequiredArgument)
                .contains("field 'Query.customer' requires argument 'id'"));

        String unknownArgument = "{ customer(id: 7, unexpected: 1) @skip(if: true) { id } }";
        assertTrue(schemaPreflight(unknownArgument)
                .contains("field 'Query.customer' has an unknown, duplicate, or malformed argument"));
    }

    @Test
    void coercesArgumentValuesAcrossDisabledSelectionsFromGeneratedDescriptors() {
        assertEquals("", schemaArgumentPreflight(
                "query Hidden($term: String!) { customers(filter: { name: { contains: $term } }) "
                        + "@skip(if: true) { id } }", "{\"term\":\"North\"}"));

        String invalidLiteral = "{ customer(id: \"seven\") @skip(if: true) { id } }";
        String invalidLiteralError = schemaArgumentPreflight(invalidLiteral, "{}");
        assertTrue(invalidLiteralError.contains(
                "argument 'Query.customer.id' cannot be coerced to 'Int'"), invalidLiteralError);
        assertTrue(invalidLiteralError.contains("\"column\":"
                + (invalidLiteral.indexOf("\"seven\"") + 1)), invalidLiteralError);

        String incompatibleVariable = "query Hidden($id: String!) "
                + "{ customer(id: $id) @skip(if: true) { id } }";
        String incompatibleVariableError = schemaArgumentPreflight(
                incompatibleVariable, "{\"id\":\"7\"}");
        assertTrue(incompatibleVariableError.contains(
                "cannot use variable '$id' declared as 'String!' at 'Int!'"),
                incompatibleVariableError);

        String nestedVariable = "query Hidden($term: Int!) "
                + "{ customers(filter: { name: { contains: $term } }) @skip(if: true) { id } }";
        String nestedVariableError = schemaArgumentPreflight(nestedVariable, "{\"term\":7}");
        assertTrue(nestedVariableError.contains(
                "cannot use variable '$term' declared as 'Int!' at 'String'"),
                nestedVariableError);
    }

    @Test
    void materializesNestedArgumentVariablesIntoTheSharedCarrier() {
        String query = "query Nested($term: String!) "
                + "{ customers(filter: { name: { contains: $term, in: [$term] } }) "
                + "{ id } }";
        int customers = query.indexOf("customers");
        String variables = "{\"term\":\"North\"}";
        String carrier = schemaArgumentCarrier(query, variables);

        assertTrue(carrier.startsWith("ma1;"), carrier);
        String filter = DatabaseGraphqlEngine.materializedArgumentValue(
                carrier, customers, "filter");
        assertTrue(DatabaseGraphqlEngine.inputObjectHasOnlyFields(filter, "name"), filter);
        String name = DatabaseGraphqlEngine.inputObjectFieldValue(filter, "name");
        assertEquals("\"North\"", DatabaseGraphqlEngine.inputObjectFieldValue(name, "contains"));
        String values = DatabaseGraphqlEngine.inputObjectFieldValue(name, "in");
        assertEquals(1, DatabaseGraphqlEngine.inputListValueCount(values));
        assertEquals("\"North\"", DatabaseGraphqlEngine.inputListValue(values, 0));

        String explicitQuery = "query Nested($explicit: String) "
                + "{ customers(filter: { name: { contains: $explicit } }) { id } }";
        String explicitCarrier = schemaArgumentCarrier(explicitQuery, "{\"explicit\":null}");
        String explicitFilter = DatabaseGraphqlEngine.materializedArgumentValue(
                explicitCarrier, explicitQuery.indexOf("customers"), "filter");
        String explicitName = DatabaseGraphqlEngine.inputObjectFieldValue(explicitFilter, "name");
        assertEquals("null", DatabaseGraphqlEngine.inputObjectFieldValue(explicitName, "contains"));

        String omittedQuery = "query Nested($optional: String) "
                + "{ customers(filter: { name: { contains: $optional } }) { id } }";
        String omittedCarrier = schemaArgumentCarrier(omittedQuery, "{}");
        String omittedFilter = DatabaseGraphqlEngine.materializedArgumentValue(
                omittedCarrier, omittedQuery.indexOf("customers"), "filter");
        String omittedName = DatabaseGraphqlEngine.inputObjectFieldValue(omittedFilter, "name");
        assertEquals("", DatabaseGraphqlEngine.inputObjectFieldValue(omittedName, "contains"));
    }

    @Test
    void validatesOptionalArgumentVariableDeclarationsFromTheRetainedAst() {
        String incompatible = "query Relation($first: String) { customer(id: 999) "
                + "{ orderConnection(first: $first) { edges { node { id } } } } }";
        String incompatibleAst = DatabaseGraphqlAst.parse(incompatible, DatabaseGraphqlLanguage.documentPlan(incompatible));
        int incompatibleField = incompatible.indexOf("orderConnection");

        assertEquals("first", DatabaseGraphqlEngine.optionalArgumentVariableTypeErrorFromAst(
                incompatible, incompatibleAst, incompatibleField, "first=Int,after=String,last=Int,before=String"));

        String compatible = incompatible.replace("$first: String", "$first: Int = 1");
        String compatibleAst = DatabaseGraphqlAst.parse(compatible, DatabaseGraphqlLanguage.documentPlan(compatible));
        assertEquals("", DatabaseGraphqlEngine.optionalArgumentVariableTypeErrorFromAst(
                compatible, compatibleAst, compatible.indexOf("orderConnection"),
                "first=Int,after=String,last=Int,before=String"));
    }

    @Test
    void boundsBroadInputListsIndependentlyOfJsonNesting() {
        String list = "";
        int item = 0;
        while (item < 129) {
            list = list + (item == 0 ? "" : ",") + "\"North\"";
            item++;
        }
        String response = preflight("query Input($filter: CustomerFilter!) { customer(filter: $filter) }",
                "{\"filter\":{\"name\":{\"in\":[" + list + "]}}}");
        assertEquals("\u001eTITAN-GRAPHQL-TRANSPORT/1 ROLLBACK\n"
                + "{\"errors\":[{\"message\":\"variable '$filter' exceeds the input coercion work budget\","
                + "\"locations\":[{\"line\":1,\"column\":13}],"
                + "\"extensions\":{\"code\":\"RESOURCE_LIMIT_ERROR\"}}]}", response);

        String literalResponse = schemaArgumentPreflight(
                "{ customers(filter: { name: { in: [" + list + "] } }) { id } }", "{}");
        assertTrue(literalResponse.contains("input coercion work budget"), literalResponse);
        assertTrue(literalResponse.contains("\"code\":\"RESOURCE_LIMIT_ERROR\""), literalResponse);
        assertFalse(literalResponse.contains("rl1;"), literalResponse);
    }

    @Test
    void boundsTypedInputWorkAcrossTheWholeSelectedOperation() {
        String variableItems = repeatedQuotedItems(64);
        String variableQuery = "query Aggregate($first: [String!]!, $second: [String!]!) { "
                + "a: customers(filter: { name: { in: $first } }) { id } "
                + "b: customers(filter: { name: { in: $second } }) { id } }";
        String variableResponse = preflight(variableQuery,
                "{\"first\":[" + variableItems + "],\"second\":[" + variableItems + "]}");
        assertTrue(variableResponse.contains("variable '$second' exceeds the input coercion work budget"),
                variableResponse);
        assertTrue(variableResponse.contains("\"code\":\"RESOURCE_LIMIT_ERROR\""), variableResponse);
        assertFalse(variableResponse.contains("iw1;"), variableResponse);

        String boundaryVariableItems = repeatedQuotedItems(63);
        assertEquals("", preflight(variableQuery,
                "{\"first\":[" + boundaryVariableItems + "],\"second\":["
                        + boundaryVariableItems + "]}"));

        String reusedVariableQuery = "query Materialize($shared: [String!]!) { "
                + "a: customers(filter: { name: { in: $shared } }) { id } "
                + "b: customers(filter: { name: { in: $shared } }) { id } }";
        String materializationResponse = schemaArgumentPreflight(reusedVariableQuery,
                "{\"shared\":[" + variableItems + "]}");
        assertTrue(materializationResponse.contains(
                "selected operation exceeds the input materialization work budget"),
                materializationResponse);
        assertTrue(materializationResponse.contains("\"code\":\"RESOURCE_LIMIT_ERROR\""),
                materializationResponse);
        assertFalse(materializationResponse.contains("im1;"), materializationResponse);

        String boundaryMaterializationItems = repeatedQuotedItems(60);
        assertEquals("", schemaArgumentPreflight(reusedVariableQuery,
                "{\"shared\":[" + boundaryMaterializationItems + "]}"));

        String literalItems = repeatedQuotedItems(62);
        String literalResponse = schemaArgumentPreflight("{ "
                + "a: customers(filter: { name: { in: [" + literalItems + "] } }) { id } "
                + "b: customers(filter: { name: { in: [" + literalItems + "] } }) { id } }", "{}");
        assertTrue(literalResponse.contains(
                "argument 'Query.customers.filter' exceeds the input coercion work budget"), literalResponse);
        assertTrue(literalResponse.contains("\"code\":\"RESOURCE_LIMIT_ERROR\""), literalResponse);
        assertFalse(literalResponse.contains("iw1;"), literalResponse);

        String boundaryLiteralItems = repeatedQuotedItems(61);
        assertEquals("", schemaArgumentPreflight("{ "
                + "a: customers(filter: { name: { in: [" + boundaryLiteralItems + "] } }) { id } "
                + "b: customers(filter: { name: { in: [" + boundaryLiteralItems + "] } }) { id } }", "{}"));
    }

    @Test
    void boundsTotalSelectedFieldWorkBeforeSchemaDispatch() {
        String withinBudget = breadthQuery(144);
        String exceedsBudget = breadthQuery(145);
        assertEquals(144, DatabaseGraphqlAst.executableFieldCount(
                DatabaseGraphqlAst.parse(withinBudget, DatabaseGraphqlLanguage.documentPlan(withinBudget))));
        assertEquals("", preflight(withinBudget, "{}"));

        String response = preflight(exceedsBudget, "{}");
        assertTrue(response.contains("semantic work budget"), response);
    }

    @Test
    void calculatesIntrospectionInventoryCostBeforeRendering() {
        String query = "{ __schema { types { name fields { name args { name } } "
                + "inputFields { name } enumValues { name } interfaces { name } "
                + "possibleTypes { name } } directives { name args { name } locations } } }";
        String ast = DatabaseGraphqlAst.parse(query, DatabaseGraphqlLanguage.documentPlan(query));
        int typesStart = query.indexOf("types");
        int directivesStart = query.indexOf("directives");

        assertEquals(280, DatabaseGraphqlEngine.introspectionTypeExpansionCostFromAst(
                query, ast, typesStart, "{}", 10, 20, 30, 40, 50, 60, 70));
        assertEquals(18, DatabaseGraphqlEngine.introspectionDirectiveExpansionCostFromAst(
                query, ast, directivesStart, "{}", 3, 5, 10));
        assertEquals(513, DatabaseGraphqlEngine.introspectionTypeExpansionCostFromAst(
                query, ast, typesStart, "{}", 500, 20, 0, 0, 0, 0, 0));
    }

    @Test
    void validatesInputFieldsIncludeDeprecatedAfterVariableMaterialization() {
        String query = "query Metadata($includeDeprecated: Boolean!) { __type(name: \"CustomerFilter\") "
                + "{ inputFields(includeDeprecated: $includeDeprecated) { name } } }";
        String ast = DatabaseGraphqlAst.parse(query, DatabaseGraphqlLanguage.documentPlan(query));
        int inputFieldsStart = query.indexOf("inputFields");

        assertEquals("[{\"name\":\"name\"}]", DatabaseGraphqlEngine.introspectionInputValuesJson(
                query, ast, inputFieldsStart, DatabaseGraphqlEngine.materializedVariableValuesFromAst(
                        query, ast, "{\"includeDeprecated\":false}"), "name=StringFilter=O",
                "__Type.inputFields", true));

        String wrongTypeQuery = query.replace("Boolean!", "String!");
        String wrongTypeAst = DatabaseGraphqlAst.parse(wrongTypeQuery, DatabaseGraphqlLanguage.documentPlan(wrongTypeQuery));
        String response = DatabaseGraphqlEngine.introspectionInputValuesJson(wrongTypeQuery, wrongTypeAst,
                wrongTypeQuery.indexOf("inputFields"), "{\"includeDeprecated\":\"false\"}",
                "name=StringFilter=O", "__Type.inputFields", true);
        assertTrue(response.contains("must be declared as Boolean or Boolean!"), response);

        String nullResponse = DatabaseGraphqlEngine.introspectionInputValuesJson(
                query, ast, inputFieldsStart, DatabaseGraphqlEngine.materializedVariableValuesFromAst(
                        query, ast, "{\"includeDeprecated\":null}"), "name=StringFilter=O",
                "__Type.inputFields", true);
        assertTrue(nullResponse.contains("must be Boolean"), nullResponse);
    }

    @Test
    void rendersSupportedDirectiveIntrospectionFromTheDatabaseAst() {
        String query = "{ __schema { directives { name description isRepeatable locations __typename "
                + "args { name type { kind name ofType { kind name } } } } } }";
        String ast = DatabaseGraphqlAst.parse(query, DatabaseGraphqlLanguage.documentPlan(query));

        String response = DatabaseGraphqlEngine.introspectionDirectivesJson(
                query, ast, query.indexOf("directives"), "{}");

        assertTrue(response.contains("\"name\":\"include\""), response);
        assertTrue(response.contains("\"name\":\"skip\""), response);
        assertTrue(response.contains("\"locations\":[\"FIELD\",\"FRAGMENT_SPREAD\",\"INLINE_FRAGMENT\"]"),
                response);
        assertTrue(response.contains("\"isRepeatable\":false"), response);
        assertTrue(response.contains("\"name\":\"if\""), response);
        assertTrue(response.contains("\"kind\":\"NON_NULL\""), response);
        assertTrue(response.contains("\"name\":\"Boolean\""), response);
        assertTrue(response.contains("\"__typename\":\"__Directive\""), response);
    }

    @Test
    void executesRegisteredConditionalDirectivesFromTheAttachedSchemaDescriptor() {
        String query = "query Conditional($show: Boolean! = true, $hide: Boolean! = false) "
                + "{ customer(id: 7) @visible(if: $show) @hidden(if: $hide) "
                + "{ ...Fields @visible(if: false) "
                + "... on Customer @visible(if: true) { name } } } "
                + "fragment Fields on Customer { id }";
        String ast = DatabaseGraphqlAst.withExecutableDirectiveDescriptor(
                DatabaseGraphqlAst.parse(query),
                "ed1|6:hidden:S:F|7:visible:I:FPI|");

        assertTrue(ast.startsWith("ast1;"), ast);
        assertEquals("ed1|6:hidden:S:F|7:visible:I:FPI|",
                DatabaseGraphqlAst.executableDirectiveDescriptor(ast));
        assertEquals("", DatabaseGraphqlEngine.preflightSelectedOperationFromAst(
                query, ast, "{}", "{}", TRUSTED_CONTEXT, true, INPUT_DESCRIPTOR));

        String defaults = DatabaseGraphqlEngine.materializedVariableValuesFromAst(query, ast, "{}");
        String rootPlan = DatabaseGraphqlEngine.rootSelectionPlanFromAst(
                query, ast, defaults, "Query", "customer");
        assertEquals(1, DatabaseGraphqlEngine.selectionPlanCount(rootPlan), rootPlan);
        int customerStart = DatabaseGraphqlEngine.selectionPlanFieldStart(rootPlan, 0);
        String customerPlan = DatabaseGraphqlEngine.rootFieldSelectionPlanFromAst(
                query, ast, customerStart, defaults, "Customer");
        assertEquals(1, DatabaseGraphqlEngine.selectionPlanCount(customerPlan), customerPlan);
        assertTrue(DatabaseGraphqlEngine.rootFieldMatchesFromAst(
                query, ast, DatabaseGraphqlEngine.selectionPlanFieldStart(customerPlan, 0), "name"));

        String hidden = DatabaseGraphqlEngine.materializedVariableValuesFromAst(
                query, ast, "{\"hide\":true}");
        assertEquals(0, DatabaseGraphqlEngine.selectionPlanCount(DatabaseGraphqlEngine.rootSelectionPlanFromAst(
                query, ast, hidden, "Query", "customer")));

        String wrongLocation = "{ customer(id: 7) { ... on Customer @hidden(if: true) { id } } }";
        String wrongLocationAst = DatabaseGraphqlAst.withExecutableDirectiveDescriptor(
                DatabaseGraphqlAst.parse(wrongLocation),
                "ed1|6:hidden:S:F|7:visible:I:FPI|");
        String wrongLocationError = DatabaseGraphqlEngine.preflightSelectedOperationFromAst(
                wrongLocation, wrongLocationAst, "{}", "{}", TRUSTED_CONTEXT, true, INPUT_DESCRIPTOR);
        assertTrue(wrongLocationError.contains("invalid or unsupported directive"), wrongLocationError);
    }

    @Test
    void introspectsRegisteredConditionalDirectivesFromGeneratedMetadata() {
        String query = "{ __schema { directives { name description isRepeatable locations "
                + "args { name type { kind name ofType { kind name } } } } } }";
        String ast = DatabaseGraphqlAst.withExecutableDirectiveDescriptor(
                DatabaseGraphqlAst.parse(query), "ed1|7:visible:I:FPI|");
        String description = "Conditionally exposes a selection; schema-owned.";
        String descriptor = "dd1;7:visible" + description.length() + ":" + description + "I3:FPI";

        String response = DatabaseGraphqlEngine.introspectionDirectivesJson(
                query, ast, query.indexOf("directives"), "{}", descriptor);

        assertTrue(response.contains("\"name\":\"include\""), response);
        assertTrue(response.contains("\"name\":\"skip\""), response);
        assertTrue(response.contains("\"name\":\"visible\""), response);
        assertTrue(response.contains("\"description\":\"Conditionally exposes a selection; schema-owned.\""),
                response);
        assertEquals(3, response.split("\"isRepeatable\":false", -1).length - 1, response);
    }

    @Test
    void rendersStandardNamedTypeFieldsIndependentlyOfTheRuntimeKind() {
        String query = "{ __type(name: \"String\") { name kind fields { name } inputFields { name } "
                + "interfaces { kind name } enumValues { name } possibleTypes { kind name } "
                + "specifiedByURL isOneOf __typename } }";
        String ast = DatabaseGraphqlAst.parse(query, DatabaseGraphqlLanguage.documentPlan(query));
        int typeStart = query.indexOf("__type");

        String scalar = DatabaseGraphqlEngine.introspectionNamedTypeJson(query, ast, typeStart, "{}", "",
                "String", "SCALAR", "", "", "", false);
        assertTrue(scalar.contains("\"fields\":null"), scalar);
        assertTrue(scalar.contains("\"inputFields\":null"), scalar);
        assertTrue(scalar.contains("\"interfaces\":null"), scalar);
        assertTrue(scalar.contains("\"enumValues\":null"), scalar);
        assertTrue(scalar.contains("\"possibleTypes\":null"), scalar);
        assertTrue(scalar.contains("\"specifiedByURL\":null"), scalar);
        assertTrue(scalar.contains("\"isOneOf\":null"), scalar);
        assertTrue(scalar.contains("\"__typename\":\"__Type\""), scalar);

        String object = DatabaseGraphqlEngine.introspectionNamedTypeJson(query, ast, typeStart, "{}", "",
                "Customer", "OBJECT", "id:Int:0:S;", "", "", false);
        assertTrue(object.contains("\"fields\":[{\"name\":\"id\"}]"), object);
        assertTrue(object.contains("\"interfaces\":[]"), object);
        assertTrue(object.contains("\"inputFields\":null"), object);

        String input = DatabaseGraphqlEngine.introspectionNamedTypeJson(query, ast, typeStart, "{}", "",
                "CustomerFilter", "INPUT_OBJECT", "", "name=String=S", "", false);
        assertTrue(input.contains("\"inputFields\":[{\"name\":\"name\"}]"), input);
        assertTrue(input.contains("\"isOneOf\":false"), input);
        assertTrue(input.contains("\"fields\":null"), input);

        String enumeration = DatabaseGraphqlEngine.introspectionNamedTypeJson(query, ast, typeStart, "{}", "",
                "SortDirection", "ENUM", "", "", "ev1;3:ASC0:00:4:DESC0:00:", false);
        assertTrue(enumeration.contains("\"enumValues\":[{\"name\":\"ASC\"},{\"name\":\"DESC\"}]"),
                enumeration);
        assertTrue(enumeration.contains("\"fields\":null"), enumeration);
    }

    @Test
    void rendersInterfaceAndUnionRelationshipsFromGeneratedMetadata() {
        String query = "{ __type(name: \"Node\") { name kind description fields { name type { kind name ofType { kind name } } } "
                + "interfaces { kind name } possibleTypes { kind name } } }";
        String ast = DatabaseGraphqlAst.parse(query, DatabaseGraphqlLanguage.documentPlan(query));
        int typeStart = query.indexOf("__type");

        String interfaceType = DatabaseGraphqlEngine.introspectionNamedTypeJson(
                query, ast, typeStart, "{}", "", "Node", "INTERFACE",
                "id:Int!:0:RS;", "", "", "An identifiable object.",
                "tl1;", "tl1;O:Customer;O:Order;", false);

        assertTrue(interfaceType.contains("\"kind\":\"INTERFACE\""), interfaceType);
        assertTrue(interfaceType.contains("\"description\":\"An identifiable object.\""), interfaceType);
        assertTrue(interfaceType.contains("\"fields\":[{\"name\":\"id\""), interfaceType);
        assertTrue(interfaceType.contains("\"interfaces\":[]"), interfaceType);
        assertTrue(interfaceType.contains("\"possibleTypes\":[{\"name\":\"Customer\",\"kind\":\"OBJECT\"}"
                + ",{\"name\":\"Order\",\"kind\":\"OBJECT\"}]"), interfaceType);

        String unionType = DatabaseGraphqlEngine.introspectionNamedTypeJson(
                query, ast, typeStart, "{}", "", "SearchResult", "UNION",
                "", "", "", "Searchable objects.", "tl1;",
                "tl1;O:Customer;O:Order;", false);
        assertTrue(unionType.contains("\"kind\":\"UNION\""), unionType);
        assertTrue(unionType.contains("\"fields\":null"), unionType);
        assertTrue(unionType.contains("\"possibleTypes\":["), unionType);
    }

    @Test
    void rendersOutputFieldDescriptionsAndFiltersDeprecatedFields() {
        String descriptor = "id:Int:0:S;nickname:String:1:S;";
        String idDescription = "Customer row identifier.";
        String nicknameDescription = "Historic customer nickname.";
        String reason = "Use name.";
        String metadata = "fm1;"
                + "id".length() + ":id" + idDescription.length() + ":" + idDescription + "0" + "0:;"
                + "nickname".length() + ":nickname" + nicknameDescription.length() + ":"
                + nicknameDescription + "1" + reason.length() + ":" + reason + ";";
        String currentQuery = "{ __type(name: \"Customer\") { fields { "
                + "name description isDeprecated deprecationReason } } }";
        String currentAst = DatabaseGraphqlAst.parse(
                currentQuery, DatabaseGraphqlLanguage.documentPlan(currentQuery));
        String current = DatabaseGraphqlEngine.introspectionNamedTypeJson(
                currentQuery, currentAst, currentQuery.indexOf("__type"), "{}", "",
                "Customer", "OBJECT", descriptor, "", "", "A customer.",
                "tl1;", "tl1;", metadata, false);

        assertTrue(current.contains("\"name\":\"id\""), current);
        assertTrue(current.contains("\"description\":\"Customer row identifier.\""), current);
        assertTrue(current.contains("\"isDeprecated\":false"), current);
        assertFalse(current.contains("nickname"), current);

        String allQuery = currentQuery.replace("fields {", "fields(includeDeprecated: true) {");
        String allAst = DatabaseGraphqlAst.parse(allQuery, DatabaseGraphqlLanguage.documentPlan(allQuery));
        String all = DatabaseGraphqlEngine.introspectionNamedTypeJson(
                allQuery, allAst, allQuery.indexOf("__type"), "{}", "",
                "Customer", "OBJECT", descriptor, "", "", "A customer.",
                "tl1;", "tl1;", metadata, false);

        assertTrue(all.contains("\"name\":\"nickname\""), all);
        assertTrue(all.contains("\"description\":\"Historic customer nickname.\""), all);
        assertTrue(all.contains("\"isDeprecated\":true"), all);
        assertTrue(all.contains("\"deprecationReason\":\"Use name.\""), all);
    }

    @Test
    void validatesAbstractSelectionsPerDeclaredAndPossibleRuntimeType() {
        String schema = abstractOutputSchemaDescriptor();
        String valid = "{ node { __typename id ... on Customer { name } ... on Order { reference } } "
                + "search { ... on Node { id } ... on Customer { value: name } "
                + "... on Order { value: reference } } }";

        assertEquals("", validateSchema(valid, schema));

        String interfaceLeak = validateSchema("{ node { name } }", schema);
        assertTrue(interfaceLeak.contains("field 'Node.name' is not defined"), interfaceLeak);

        String unionLeak = validateSchema("{ search { id } }", schema);
        assertTrue(unionLeak.contains("field 'SearchResult.id' is not defined"), unionLeak);

        String impossible = validateSchema(
                "{ node { ... on Query { customer: node { id } } } }", schema);
        assertTrue(impossible.contains("cannot apply to parent type 'Node'"), impossible);

        String runtimeConflict = validateSchema(
                "{ search { ... on Node { value: id } ... on Customer { value: name } } }", schema);
        assertTrue(runtimeConflict.contains("conflicting fields for one response key"), runtimeConflict);
    }

    @Test
    void rendersDelimiterSafeEnumMetadataAndFiltersDeprecatedValues() {
        String descriptor = "ev1;"
                + "ACTIVE".length() + ":ACTIVE"
                + "Can \"order\", now.".length() + ":Can \"order\", now."
                + "0" + "0:"
                + "LEGACY".length() + ":LEGACY"
                + "Historic: retained.".length() + ":Historic: retained."
                + "1" + "Use ACTIVE, now.".length() + ":Use ACTIVE, now.";
        String currentQuery = "{ __type(name: \"CustomerStatus\") { enumValues { "
                + "name description isDeprecated deprecationReason } } }";
        String currentAst = DatabaseGraphqlAst.parse(
                currentQuery, DatabaseGraphqlLanguage.documentPlan(currentQuery));
        String current = DatabaseGraphqlEngine.introspectionNamedTypeJson(
                currentQuery, currentAst, currentQuery.indexOf("__type"), "{}", "",
                "CustomerStatus", "ENUM", "", "", descriptor, false);

        assertTrue(current.contains("\"name\":\"ACTIVE\""), current);
        assertTrue(current.contains("\"description\":\"Can \\\"order\\\", now.\""), current);
        assertTrue(current.contains("\"isDeprecated\":false"), current);
        assertFalse(current.contains("LEGACY"), current);

        String allQuery = "{ __type(name: \"CustomerStatus\") { enumValues(includeDeprecated: true) { "
                + "name description isDeprecated deprecationReason } } }";
        String allAst = DatabaseGraphqlAst.parse(allQuery, DatabaseGraphqlLanguage.documentPlan(allQuery));
        String all = DatabaseGraphqlEngine.introspectionNamedTypeJson(
                allQuery, allAst, allQuery.indexOf("__type"), "{}", "",
                "CustomerStatus", "ENUM", "", "", descriptor, false);

        assertTrue(all.contains("\"name\":\"LEGACY\""), all);
        assertTrue(all.contains("\"description\":\"Historic: retained.\""), all);
        assertTrue(all.contains("\"isDeprecated\":true"), all);
        assertTrue(all.contains("\"deprecationReason\":\"Use ACTIVE, now.\""), all);
    }

    @Test
    void rendersAuthoredInputFieldDefaultsDescriptionsAndDeprecations() {
        String defaultValue = "\"Default, with: delimiters\"";
        String descriptor = "iv1;"
                + "name".length() + ":name"
                + "String!".length() + ":String!"
                + "S".length() + ":S"
                + "1" + defaultValue.length() + ":" + defaultValue
                + "Replacement name.".length() + ":Replacement name."
                + "0" + "0:"
                + "legacyName".length() + ":legacyName"
                + "String".length() + ":String"
                + "S".length() + ":S"
                + "0" + "0:"
                + "Old field.".length() + ":Old field."
                + "1" + "Use name.".length() + ":Use name.";
        String currentQuery = "{ __type(name: \"RenameCustomerPatch\") { description inputFields { "
                + "name description defaultValue isDeprecated deprecationReason } } }";
        String currentAst = DatabaseGraphqlAst.parse(
                currentQuery, DatabaseGraphqlLanguage.documentPlan(currentQuery));
        String current = DatabaseGraphqlEngine.introspectionNamedTypeJson(
                currentQuery, currentAst, currentQuery.indexOf("__type"), "{}", "",
                "RenameCustomerPatch", "INPUT_OBJECT", "", descriptor, "", "Mutable values.", false);

        assertTrue(current.contains("\"description\":\"Mutable values.\""), current);
        assertTrue(current.contains("\"name\":\"name\""), current);
        assertTrue(current.contains("\"description\":\"Replacement name.\""), current);
        assertTrue(current.contains("\"defaultValue\":\"\\\"Default, with: delimiters\\\"\""), current);
        assertFalse(current.contains("legacyName"), current);

        String allQuery = currentQuery.replace("inputFields {", "inputFields(includeDeprecated: true) {");
        String allAst = DatabaseGraphqlAst.parse(allQuery, DatabaseGraphqlLanguage.documentPlan(allQuery));
        String all = DatabaseGraphqlEngine.introspectionNamedTypeJson(
                allQuery, allAst, allQuery.indexOf("__type"), "{}", "",
                "RenameCustomerPatch", "INPUT_OBJECT", "", descriptor, "", "Mutable values.", false);
        assertTrue(all.contains("\"name\":\"legacyName\""), all);
        assertTrue(all.contains("\"isDeprecated\":true"), all);
        assertTrue(all.contains("\"deprecationReason\":\"Use name.\""), all);
    }

    @Test
    void rendersIntrospectionSchemaFieldsThroughStructuralDescriptorsWithDefaults() {
        String query = "{ __type(name: \"__Type\") { fields(includeDeprecated: true) { name "
                + "args(includeDeprecated: true) { name defaultValue "
                + "type { kind name ofType { kind name } } } "
                + "type { kind name ofType { kind name ofType { kind name } } } } } }";
        String ast = DatabaseGraphqlAst.parse(query, DatabaseGraphqlLanguage.documentPlan(query));
        int typeStart = query.indexOf("__type");

        String response = DatabaseGraphqlEngine.introspectionNamedTypeJson(query, ast, typeStart, "{}", "",
                "__Type", "OBJECT",
                "kind:__TypeKind!:0:RE;fields:[__Field!]:1:RO|includeDeprecated=Boolean!=S=false;",
                "", "", false);

        assertTrue(response.contains("\"name\":\"kind\""), response);
        assertTrue(response.contains("\"kind\":\"NON_NULL\""), response);
        assertTrue(response.contains("\"name\":\"__TypeKind\""), response);
        assertTrue(response.contains("\"name\":\"includeDeprecated\""), response);
        assertTrue(response.contains("\"defaultValue\":\"false\""), response);
        assertTrue(response.contains("\"name\":null,\"kind\":\"NON_NULL\",\"ofType\":{\"name\":\"Boolean\",\"kind\":\"SCALAR\"}"),
                response);
        assertTrue(response.contains("\"kind\":\"LIST\""), response);
        assertTrue(response.contains("\"name\":\"__Field\""), response);
    }

    @Test
    void locatesVariablePreflightErrorsAtTheRetainedDeclaration() {
        String query = """
                query Input(
                  $filter: CustomerFilter!
                ) { customer(filter: $filter) }
                """;

        String response = preflight(query, "{\"filter\":7}");

        assertTrue(response.contains("variable '$filter' input object has an unknown, duplicate, or malformed field"),
                response);
        assertTrue(DatabaseGraphqlEngine.transportResponseJson(response)
                .contains("\"locations\":[{\"line\":2,\"column\":3}]"), response);
    }

    @Test
    void rejectsExpiredOrMalformedTrustedDeadlinesBeforeSchemaDispatch() {
        String query = "query { customer { id } }";
        String noDeadline = preflight(query, "{}", TRUSTED_CONTEXT);
        String zeroDeadline = preflight(query, "{}", "{\"contextVersion\":\""
                + DatabaseGraphqlEngine.TRUSTED_CONTEXT_VERSION + "\",\"deadlineEpochMillis\":0}");
        String expired = preflight(query, "{}", "{\"contextVersion\":\""
                + DatabaseGraphqlEngine.TRUSTED_CONTEXT_VERSION + "\",\"deadlineEpochMillis\":1577836800000}");
        String malformed = preflight(query, "{}", "{\"contextVersion\":\""
                + DatabaseGraphqlEngine.TRUSTED_CONTEXT_VERSION + "\",\"deadlineEpochMillis\":true}");
        String unrepresentableForTheDatabase = preflight(query, "{}", "{\"contextVersion\":\""
                + DatabaseGraphqlEngine.TRUSTED_CONTEXT_VERSION + "\",\"deadlineEpochMillis\":9223372036854775807}");

        assertEquals("", noDeadline);
        assertEquals("", zeroDeadline);
        assertTrue(expired.contains("request deadline exceeded before database execution"), expired);
        assertTrue(malformed.contains("invalid deadlineEpochMillis"), malformed);
        assertTrue(unrepresentableForTheDatabase.contains("invalid deadlineEpochMillis"),
                unrepresentableForTheDatabase);
    }

    private static void assertCoercionFailure(String query, String variables) {
        assertTrue(preflight(query, variables).contains("cannot be coerced"), preflight(query, variables));
    }

    private static void assertInputObjectFailure(String query, String variables) {
        assertTrue(preflight(query, variables).contains("unknown, duplicate, or malformed"), preflight(query, variables));
    }

    private static String preflight(String query, String variables) {
        return preflight(query, variables, TRUSTED_CONTEXT);
    }

    private static String preflight(String query, String variables, String trustedContext) {
        String tokens = DatabaseGraphqlLanguage.documentPlan(query);
        String ast = DatabaseGraphqlAst.parse(query, tokens);
        return DatabaseGraphqlEngine.preflightSelectedOperationFromAst(query, ast, variables, "{}",
                trustedContext, false, INPUT_DESCRIPTOR);
    }

    private static String schemaPreflight(String query) {
        String ast = DatabaseGraphqlAst.parse(query, DatabaseGraphqlLanguage.documentPlan(query));
        return DatabaseGraphqlEngine.validateSelectedOperationSchemaFromAst(
                query, ast, OUTPUT_SCHEMA_DESCRIPTOR);
    }

    private static String validateSchema(String query, String schemaDescriptor) {
        String ast = DatabaseGraphqlAst.parse(query, DatabaseGraphqlLanguage.documentPlan(query));
        return DatabaseGraphqlEngine.validateSelectedOperationSchemaFromAst(
                query, ast, schemaDescriptor);
    }

    private static String schemaArgumentPreflight(String query, String variables) {
        String result = schemaArgumentCarrier(query, variables);
        return result.startsWith("ma1;") ? "" : result;
    }

    private static String schemaArgumentCarrier(String query, String variables) {
        String ast = DatabaseGraphqlAst.parse(query, DatabaseGraphqlLanguage.documentPlan(query));
        String preflight = DatabaseGraphqlEngine.preflightSelectedOperationFromAst(
                query, ast, variables, "{}", TRUSTED_CONTEXT, false, INPUT_DESCRIPTOR);
        if (preflight.length() != 0) return preflight;
        preflight = DatabaseGraphqlEngine.validateSelectedOperationSchemaFromAst(
                query, ast, OUTPUT_SCHEMA_DESCRIPTOR);
        if (preflight.length() != 0) return preflight;
        String materialized = DatabaseGraphqlEngine.materializedVariableValuesFromAst(
                query, ast, variables);
        return DatabaseGraphqlEngine.materializedSelectedOperationArgumentValuesFromAst(
                query, ast, materialized, OUTPUT_SCHEMA_DESCRIPTOR, INPUT_DESCRIPTOR);
    }

    private static String argumentCarrier(
            String query,
            String variables,
            String schemaDescriptor,
            String inputDescriptor
    ) {
        String ast = DatabaseGraphqlAst.parse(query, DatabaseGraphqlLanguage.documentPlan(query));
        String preflight = DatabaseGraphqlEngine.preflightSelectedOperationFromAst(
                query, ast, variables, "{}", TRUSTED_CONTEXT, false, inputDescriptor);
        if (!preflight.isEmpty()) return preflight;
        preflight = DatabaseGraphqlEngine.validateSelectedOperationSchemaFromAst(
                query, ast, schemaDescriptor);
        if (!preflight.isEmpty()) return preflight;
        String materialized = DatabaseGraphqlEngine.materializedVariableValuesFromAst(
                query, ast, variables);
        return DatabaseGraphqlEngine.materializedSelectedOperationArgumentValuesFromAst(
                query, ast, materialized, schemaDescriptor, inputDescriptor);
    }

    private static String outputSchemaDescriptor() {
        String query = "customer:Customer:1:O|id=Int!=S,status=CustomerStatus=E;"
                + "customers:Customer:1:O|filter=CustomerFilter=I;";
        String customer = "id:Int:0:S;name:String:0:S;";
        return "sd1;" + "Query".length() + ":Query" + query.length() + ":" + query
                + "Customer".length() + ":Customer" + customer.length() + ":" + customer;
    }

    private static String abstractOutputSchemaDescriptor() {
        String query = "node:Node:1:T;search:SearchResult:1:U;";
        String node = "id:Int!:0:RS;";
        String search = "__typename:String!:0:S;";
        String customer = "id:Int:0:S;name:String:0:S;";
        String order = "id:Int:0:S;reference:String:0:S;";
        return "sd1;" + schemaRecord("Query", query)
                + schemaRecord("Node", node)
                + schemaRecord("SearchResult", search)
                + schemaRecord("Customer", customer)
                + schemaRecord("Order", order)
                + schemaRecord("@Node", "Customer,Order")
                + schemaRecord("@SearchResult", "Customer,Order")
                + schemaRecord("#Node", "|Node|")
                + schemaRecord("#SearchResult", "|SearchResult|")
                + schemaRecord("#Customer", "|Customer|Node|SearchResult|")
                + schemaRecord("#Order", "|Order|Node|SearchResult|");
    }

    private static String schemaRecord(String name, String fields) {
        return name.length() + ":" + name + fields.length() + ":" + fields;
    }

    private static String breadthQuery(int fieldCount) {
        String fields = "";
        int index = 0;
        while (index < fieldCount) {
            fields = fields + " f" + index;
            index++;
        }
        return "query Work {" + fields + " }";
    }

    private static String repeatedQuotedItems(int itemCount) {
        String items = "";
        int index = 0;
        while (index < itemCount) {
            items = items + (index == 0 ? "" : ",") + "\"North\"";
            index++;
        }
        return items;
    }
}
