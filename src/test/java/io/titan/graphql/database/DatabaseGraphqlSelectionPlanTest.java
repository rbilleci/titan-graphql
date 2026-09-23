package io.titan.graphql.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DatabaseGraphqlSelectionPlanTest {

    @Test
    void expandsApplicableAbstractFragmentsAndSkipsSiblingConcreteFragments() {
        String query = "{ customer { id ... on Customer { name } ... on Order { reference } "
                + "...NodeFields ...OrderFields } } "
                + "fragment NodeFields on Node { nodeId } "
                + "fragment OrderFields on Order { reference }";
        String ast = DatabaseGraphqlAst.parse(query, DatabaseGraphqlLanguage.documentPlan(query));
        int customer = query.indexOf("customer");

        String plan = DatabaseGraphqlEngine.rootFieldSelectionPlanFromAst(
                query, ast, customer, "{}", "|Customer|Node|SearchResult|");

        assertEquals(3, DatabaseGraphqlEngine.selectionPlanCount(plan), plan);
        assertEquals(query.indexOf("id ..."), DatabaseGraphqlEngine.selectionPlanFieldStart(plan, 0));
        assertEquals(query.indexOf("name"), DatabaseGraphqlEngine.selectionPlanFieldStart(plan, 1));
        assertEquals(query.indexOf("nodeId"), DatabaseGraphqlEngine.selectionPlanFieldStart(plan, 2));
    }

    @Test
    void expandsAbstractFragmentsAcrossConcreteRelationAncestry() {
        String query = "{ customer { ... on Node { orders { ... on Node { id } "
                + "... on Order { detail: reference } } } } }";
        String ast = DatabaseGraphqlAst.parse(query, DatabaseGraphqlLanguage.documentPlan(query));
        int orders = query.indexOf("orders");

        String plan = DatabaseGraphqlEngine.fieldSelectionPlanFromAst(
                query, ast, orders, "{}", "Customer,Node,SearchResult",
                "|Order|Node|SearchResult|", "Query|Customer,Node,SearchResult");

        assertEquals(2, DatabaseGraphqlEngine.selectionPlanCount(plan), plan);
        assertEquals(query.indexOf("id"), DatabaseGraphqlEngine.selectionPlanFieldStart(plan, 0));
        assertEquals(query.indexOf("detail: reference"),
                DatabaseGraphqlEngine.selectionPlanFieldStart(plan, 1));
    }

    @Test
    void expandsAConditionalRelationSelectionThroughNamedFragments() {
        String query = "query Fragmented($showOrders: Boolean!) { found: customer(id: 7) { "
                + "... CustomerIdentity orders @include(if: $showOrders) { ...OrderIdentity } } } "
                + "fragment CustomerIdentity on Customer { id name } "
                + "fragment OrderIdentity on Order { id reference }";
        String languagePlan = DatabaseGraphqlLanguage.documentPlan(query);
        String ast = DatabaseGraphqlAst.parse(query, languagePlan);
        int customer = query.indexOf("found: customer");
        int customerSelection = query.indexOf("{ ... CustomerIdentity");
        int orders = query.indexOf("orders @include");

        assertEquals(2, DatabaseGraphqlLanguage.selectionItemCount(languagePlan, customerSelection), languagePlan);
        assertEquals("67:P", DatabaseGraphqlLanguage.selectionItemInfo(languagePlan, customerSelection, 0), languagePlan);
        assertEquals("88:F", DatabaseGraphqlLanguage.selectionItemInfo(languagePlan, customerSelection, 1), languagePlan);
        assertEquals("175:183:184:184:194", DatabaseGraphqlLanguage.fragmentDefinitionInfo(
                query, languagePlan, "CustomerIdentity"), languagePlan);
        int customerIdentitySelection = query.indexOf("{ id name }");
        assertEquals(2, DatabaseGraphqlLanguage.selectionItemCount(languagePlan, customerIdentitySelection), languagePlan);
        assertEquals("186:F", DatabaseGraphqlLanguage.selectionItemInfo(
                languagePlan, customerIdentitySelection, 0), languagePlan);
        assertEquals(customerSelection, DatabaseGraphqlLanguage.fieldSelectionStart(
                query, languagePlan, customer), languagePlan);
        assertEquals(3, DatabaseGraphqlEngine.selectionFieldCount(
                query, languagePlan, customer, null, "Customer"), languagePlan);
        assertEquals(3, DatabaseGraphqlEngine.selectionFieldCountFromAst(
                query, ast, customer, null, "Customer"), ast);
        assertEquals(orders, DatabaseGraphqlEngine.selectionFieldStart(
                query, languagePlan, customer, "orders", null, "Customer"), languagePlan);
        assertEquals(orders, DatabaseGraphqlEngine.selectionFieldStartFromAst(
                query, ast, customer, "orders", null, "Customer"), ast);
        assertEquals(orders, DatabaseGraphqlEngine.selectionFieldStart(
                query, languagePlan, customer, "orders", "{\"showOrders\":true}", "Customer"), languagePlan);
        assertEquals(2, DatabaseGraphqlEngine.selectionFieldCount(
                query, languagePlan, orders, "{\"showOrders\":true}", "Order"), languagePlan);
        assertEquals(query.indexOf("id reference"), DatabaseGraphqlEngine.selectionFieldStart(
                query, languagePlan, orders, "id", "{\"showOrders\":true}", "Order"), languagePlan);

        String plan = DatabaseGraphqlEngine.fieldSelectionPlan(
                query, languagePlan, orders, "{\"showOrders\":true}", "Customer", "Order");
        String astPlan = DatabaseGraphqlEngine.fieldSelectionPlanFromAst(
                query, ast, orders, "{\"showOrders\":true}", "Customer", "Order");

        assertEquals(2, DatabaseGraphqlEngine.selectionPlanCount(plan), plan + "\n" + languagePlan);
        assertEquals(plan, astPlan, ast);
        assertEquals(query.indexOf("id reference"), DatabaseGraphqlEngine.selectionPlanFieldStart(plan, 0), plan);
        assertEquals(query.indexOf("reference }"), DatabaseGraphqlEngine.selectionPlanFieldStart(plan, 1), plan);
    }

    @Test
    void collectsNestedRelationChildrenAcrossCompatibleAncestorFields() {
        String query = "{ customer(id: 7) { orders { customer { name } } orders { customer { id } } } }";
        String languagePlan = DatabaseGraphqlLanguage.documentPlan(query);
        String ast = DatabaseGraphqlAst.parse(query, languagePlan);
        int firstOrders = query.indexOf("orders");

        String ordersPlan = DatabaseGraphqlEngine.fieldSelectionPlanFromAst(
                query, ast, firstOrders, "{}", "Customer", "Order", "Query|Customer");

        assertEquals(1, DatabaseGraphqlEngine.selectionPlanCount(ordersPlan), ordersPlan);
        int firstCustomer = DatabaseGraphqlEngine.selectionPlanFieldStart(ordersPlan, 0);
        assertEquals(query.indexOf("customer", firstOrders), firstCustomer, ordersPlan);

        String customerPlan = DatabaseGraphqlEngine.fieldSelectionPlanFromAst(
                query, ast, firstCustomer, "{}", "Order", "Customer", "Query|Customer|Order");

        assertEquals(2, DatabaseGraphqlEngine.selectionPlanCount(customerPlan), customerPlan);
        assertEquals(query.indexOf("name"), DatabaseGraphqlEngine.selectionPlanFieldStart(customerPlan, 0),
                customerPlan);
        assertEquals(query.lastIndexOf("id"), DatabaseGraphqlEngine.selectionPlanFieldStart(customerPlan, 1),
                customerPlan);
    }

    @Test
    void collectsNestedRelationChildrenAcrossCompatibleAncestorFieldsInNamedFragments() {
        String query = "{ customer(id: 7) { orders { ...OrderName } orders { ...OrderId } } } "
                + "fragment OrderName on Order { customer { name } } "
                + "fragment OrderId on Order { customer { id } }";
        String languagePlan = DatabaseGraphqlLanguage.documentPlan(query);
        String ast = DatabaseGraphqlAst.parse(query, languagePlan);
        int firstOrders = query.indexOf("orders");

        String ordersPlan = DatabaseGraphqlEngine.fieldSelectionPlanFromAst(
                query, ast, firstOrders, "{}", "Customer", "Order", "Query|Customer");
        assertEquals(1, DatabaseGraphqlEngine.selectionPlanCount(ordersPlan), ordersPlan);
        int firstCustomer = DatabaseGraphqlEngine.selectionPlanFieldStart(ordersPlan, 0);

        String customerPlan = DatabaseGraphqlEngine.fieldSelectionPlanFromAst(
                query, ast, firstCustomer, "{}", "Order", "Customer", "Query|Customer|Order");

        assertEquals(2, DatabaseGraphqlEngine.selectionPlanCount(customerPlan), customerPlan + "\n" + ast);
        assertEquals(query.indexOf("name"), DatabaseGraphqlEngine.selectionPlanFieldStart(customerPlan, 0),
                customerPlan);
        assertEquals(query.lastIndexOf("id"), DatabaseGraphqlEngine.selectionPlanFieldStart(customerPlan, 1),
                customerPlan);
    }

    @Test
    void collectsNestedRelationChildrenAcrossCompatibleAncestorFieldsInInlineFragments() {
        String query = "{ customer(id: 7) { orders { ... on Order { customer { name } } } "
                + "orders { ... on Order { customer { id } } } } }";
        String languagePlan = DatabaseGraphqlLanguage.documentPlan(query);
        String ast = DatabaseGraphqlAst.parse(query, languagePlan);
        int firstOrders = query.indexOf("orders");

        String ordersPlan = DatabaseGraphqlEngine.fieldSelectionPlanFromAst(
                query, ast, firstOrders, "{}", "Customer", "Order", "Query|Customer");
        assertEquals(1, DatabaseGraphqlEngine.selectionPlanCount(ordersPlan), ordersPlan);
        int firstCustomer = DatabaseGraphqlEngine.selectionPlanFieldStart(ordersPlan, 0);

        String customerPlan = DatabaseGraphqlEngine.fieldSelectionPlanFromAst(
                query, ast, firstCustomer, "{}", "Order", "Customer", "Query|Customer|Order");

        assertEquals(2, DatabaseGraphqlEngine.selectionPlanCount(customerPlan), customerPlan);
        assertEquals(query.indexOf("name"), DatabaseGraphqlEngine.selectionPlanFieldStart(customerPlan, 0),
                customerPlan);
        assertEquals(query.lastIndexOf("id"), DatabaseGraphqlEngine.selectionPlanFieldStart(customerPlan, 1),
                customerPlan);
    }

    @Test
    void collectsCompatibleConnectionRootChildrenAcrossRepeatedRoots() {
        String query = "{ customerFeed(first: 1) { edges { node { id } } } "
                + "customerFeed(first: 1) { totalCount } }";
        String languagePlan = DatabaseGraphqlLanguage.documentPlan(query);
        String ast = DatabaseGraphqlAst.parse(query, languagePlan);
        int root = query.indexOf("customerFeed");

        String plan = DatabaseGraphqlEngine.rootFieldSelectionPlanFromAst(
                query, ast, root, "{}", "CustomerConnection");

        assertEquals(2, DatabaseGraphqlEngine.selectionPlanCount(plan), plan);
        assertEquals(query.indexOf("edges"), DatabaseGraphqlEngine.selectionPlanFieldStartForFieldFromAst(
                query, ast, plan, "edges"), plan);
        assertEquals(query.lastIndexOf("totalCount"), DatabaseGraphqlEngine.selectionPlanFieldStartForFieldFromAst(
                query, ast, plan, "totalCount"), plan);
    }

    @Test
    void retainsDistinctAliasesOfOneConnectionScalarInTheAstSelectionPlan() {
        String query = "{ customerFeed(first: 1) { total: totalCount count: totalCount } }";
        String languagePlan = DatabaseGraphqlLanguage.documentPlan(query);
        String ast = DatabaseGraphqlAst.parse(query, languagePlan);
        int root = query.indexOf("customerFeed");

        String plan = DatabaseGraphqlEngine.rootFieldSelectionPlanFromAst(
                query, ast, root, "{}", "CustomerConnection");

        assertEquals(2, DatabaseGraphqlEngine.selectionPlanCount(plan), plan);
        assertEquals(-1, DatabaseGraphqlEngine.selectionPlanFieldStartForFieldFromAst(
                query, ast, plan, "totalCount"), plan);
        assertEquals(query.indexOf("total:"), DatabaseGraphqlEngine.selectionPlanFirstFieldStartForFieldFromAst(
                query, ast, plan, "totalCount"), plan);
        assertEquals(2, DatabaseGraphqlEngine.selectionPlanFieldCountForFieldFromAst(
                query, ast, plan, "totalCount"), plan);
    }

    @Test
    void identifiesOnlyCountAndTypeNameRelaySelectionsForParentBatching() {
        String query = "{ customers { edges { node { orderConnection { "
                + "kind: __typename total: totalCount second: totalCount } } } } }";
        String ast = DatabaseGraphqlAst.parse(query, DatabaseGraphqlLanguage.documentPlan(query));
        int relation = query.indexOf("orderConnection");

        assertEquals(true, DatabaseGraphqlEngine.relayConnectionSelectionIsCountOnlyFromAst(
                query, ast, relation, "{}", "Customer", "OrderConnection",
                "Query|CustomerConnection|CustomerEdge|Customer"));
    }

    @Test
    void rejectsRelayCountBatchWhenEdgesAreAlsoSelected() {
        String query = "{ customers { edges { node { orderConnection { "
                + "totalCount edges { cursor } } } } } }";
        String ast = DatabaseGraphqlAst.parse(query, DatabaseGraphqlLanguage.documentPlan(query));
        int relation = query.indexOf("orderConnection");

        assertEquals(false, DatabaseGraphqlEngine.relayConnectionSelectionIsCountOnlyFromAst(
                query, ast, relation, "{}", "Customer", "OrderConnection",
                "Query|CustomerConnection|CustomerEdge|Customer"));
        assertEquals(true, DatabaseGraphqlEngine.relayConnectionSelectionHasPageFromAst(
                query, ast, relation, "{}", "Customer", "OrderConnection",
                "Query|CustomerConnection|CustomerEdge|Customer"));
    }

    @Test
    void rejectsRelayCountBatchForTypeNameOnlySelection() {
        String query = "{ customers { edges { node { orderConnection { __typename } } } } }";
        String ast = DatabaseGraphqlAst.parse(query, DatabaseGraphqlLanguage.documentPlan(query));
        int relation = query.indexOf("orderConnection");

        assertEquals(false, DatabaseGraphqlEngine.relayConnectionSelectionIsCountOnlyFromAst(
                query, ast, relation, "{}", "Customer", "OrderConnection",
                "Query|CustomerConnection|CustomerEdge|Customer"));
        assertEquals(false, DatabaseGraphqlEngine.relayConnectionSelectionHasPageFromAst(
                query, ast, relation, "{}", "Customer", "OrderConnection",
                "Query|CustomerConnection|CustomerEdge|Customer"));
    }
}
