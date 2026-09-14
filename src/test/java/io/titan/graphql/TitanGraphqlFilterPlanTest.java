package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TitanGraphqlFilterPlanTest {

    private static final TitanGraphqlFilterLayout.Binding TITLE_EQ =
            new TitanGraphqlFilterLayout.Binding("title", "title", "title", "String", "eq", 0, false, 1);
    private static final TitanGraphqlFilterLayout.Binding TITLE_IN =
            new TitanGraphqlFilterLayout.Binding("title", "title", "title", "String", "in", 0, false, 2);

    @Test
    void distributesBooleanTreeIntoBoundedDnf() {
        GraphqlSelection.GeneratedRootFilter filter = node(GraphqlSelection.GeneratedRootFilterKind.AND,
                scalar("a"), node(GraphqlSelection.GeneratedRootFilterKind.OR, scalar("b"), scalar("c")));

        TitanGraphqlFilterPlan.Plan plan = TitanGraphqlFilterPlan.compile(List.of(filter), List.of(TITLE_EQ));

        assertEquals(2, plan.groups().size());
        assertEquals(List.of("a", "b"), strings(plan.groups().get(0)));
        assertEquals(List.of("a", "c"), strings(plan.groups().get(1)));
    }

    @Test
    void appliesDeMorganAndExpandsInWithoutDynamicSql() {
        GraphqlSelection.GeneratedRootFilter filter = node(GraphqlSelection.GeneratedRootFilterKind.NOT,
                node(GraphqlSelection.GeneratedRootFilterKind.OR, scalar("a"), in("b", "c")));

        TitanGraphqlFilterPlan.Plan plan = TitanGraphqlFilterPlan.compile(
                List.of(filter), List.of(TITLE_EQ, TITLE_IN));

        assertEquals(1, plan.groups().size());
        assertEquals(3, plan.groups().getFirst().terms().size());
        assertTrue(plan.groups().getFirst().terms().stream().allMatch(TitanGraphqlFilterPlan.Term::negated));
    }

    @Test
    void treatsEmptyInAsFalseAndRejectsExpressionsBeyondCarrierBudget() {
        assertTrue(TitanGraphqlFilterPlan.compile(List.of(in()), List.of(TITLE_IN)).alwaysFalse());
        assertTrue(TitanGraphqlFilterPlan.compile(
                List.of(node(GraphqlSelection.GeneratedRootFilterKind.NOT, in())), List.of(TITLE_IN)).alwaysTrue());

        GraphqlException failure = assertThrows(GraphqlException.class,
                () -> TitanGraphqlFilterPlan.compile(List.of(in("a", "b", "c", "d")), List.of(TITLE_IN)));
        assertTrue(failure.getMessage().contains("static carrier budget"), failure.getMessage());
    }

    private static List<String> strings(TitanGraphqlFilterPlan.Group group) {
        return group.terms().stream().map(term -> term.value().stringValue()).toList();
    }

    private static GraphqlSelection.GeneratedRootFilter scalar(String value) {
        return new GraphqlSelection.GeneratedRootFilter(
                GraphqlSelection.GeneratedRootFilterKind.SCALAR, "title", "title", "title", 0,
                "String", GraphqlSelection.GeneratedRootFilterOperator.EQ, List.of(value(value)), List.of());
    }

    private static GraphqlSelection.GeneratedRootFilter in(String... values) {
        return new GraphqlSelection.GeneratedRootFilter(
                GraphqlSelection.GeneratedRootFilterKind.SCALAR, "title", "title", "title", 0,
                "String", GraphqlSelection.GeneratedRootFilterOperator.IN,
                java.util.Arrays.stream(values).map(TitanGraphqlFilterPlanTest::value).toList(), List.of());
    }

    private static GraphqlSelection.GeneratedRootFilter node(
            GraphqlSelection.GeneratedRootFilterKind kind,
            GraphqlSelection.GeneratedRootFilter... children
    ) {
        return new GraphqlSelection.GeneratedRootFilter(
                kind, "", "", "", 0, "", null, List.of(), List.of(children));
    }

    private static GraphqlSelection.GeneratedRootFilterValue value(String value) {
        return new GraphqlSelection.GeneratedRootFilterValue("String", value, 0, false, false);
    }
}
