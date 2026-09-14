package io.titan.graphql;

import java.util.ArrayList;
import java.util.List;

/** Compiles a validated generated-filter tree to a small deterministic disjunctive-normal form. */
public final class TitanGraphqlFilterPlan {

    public static final int MAX_GROUPS = 3;
    public static final int MAX_TERMS_PER_GROUP = 3;

    private TitanGraphqlFilterPlan() {
    }

    public static Plan compile(
            List<GraphqlSelection.GeneratedRootFilter> roots,
            List<TitanGraphqlFilterLayout.Binding> bindings
    ) {
        Plan result = truePlan();
        for (GraphqlSelection.GeneratedRootFilter root : roots) {
            result = and(result, compile(root, bindings, false));
        }
        requireWithinBudget(result);
        return result;
    }

    private static Plan compile(
            GraphqlSelection.GeneratedRootFilter filter,
            List<TitanGraphqlFilterLayout.Binding> bindings,
            boolean negated
    ) {
        return switch (filter.kind()) {
            case SCALAR -> scalar(filter, bindings, negated);
            case NOT -> {
                if (filter.children().size() != 1) {
                    throw new GraphqlException("generated NOT filter must contain exactly one child");
                }
                yield compile(filter.children().getFirst(), bindings, !negated);
            }
            case AND, OR -> {
                boolean conjunction = filter.kind() == GraphqlSelection.GeneratedRootFilterKind.AND;
                if (negated) conjunction = !conjunction;
                Plan result = conjunction ? truePlan() : falsePlan();
                for (GraphqlSelection.GeneratedRootFilter child : filter.children()) {
                    Plan next = compile(child, bindings, negated);
                    result = conjunction ? and(result, next) : or(result, next);
                }
                yield result;
            }
        };
    }

    private static Plan scalar(
            GraphqlSelection.GeneratedRootFilter filter,
            List<TitanGraphqlFilterLayout.Binding> bindings,
            boolean negated
    ) {
        String operator = TitanGraphqlFilterLayout.normalizeOperator(filter.operator().name());
        TitanGraphqlFilterLayout.Binding binding = TitanGraphqlFilterLayout.require(
                bindings, filter.fieldName(), operator);
        if (filter.operator() == GraphqlSelection.GeneratedRootFilterOperator.IN) {
            Plan result = negated ? truePlan() : falsePlan();
            for (GraphqlSelection.GeneratedRootFilterValue value : filter.values()) {
                Plan item = termPlan(new Term(binding, value, negated));
                result = negated ? and(result, item) : or(result, item);
            }
            return result;
        }
        if (filter.values().size() != 1) {
            throw new GraphqlException("generated filter '" + filter.fieldName() + "."
                    + operator + "' must contain exactly one value");
        }
        GraphqlSelection.GeneratedRootFilterValue value = filter.values().getFirst();
        if (filter.operator() == GraphqlSelection.GeneratedRootFilterOperator.IS_NULL && negated) {
            value = new GraphqlSelection.GeneratedRootFilterValue(
                    value.scalarType(), value.stringValue(), value.intValue(),
                    !value.booleanValue(), value.nullValue());
            negated = false;
        }
        return termPlan(new Term(binding, value, negated));
    }

    private static Plan and(Plan left, Plan right) {
        if (left.groups().isEmpty() || right.groups().isEmpty()) return falsePlan();
        List<Group> groups = new ArrayList<>();
        for (Group a : left.groups()) {
            for (Group b : right.groups()) {
                List<Term> terms = new ArrayList<>(a.terms());
                terms.addAll(b.terms());
                groups.add(new Group(terms));
            }
        }
        Plan result = new Plan(groups);
        requireWithinBudget(result);
        return result;
    }

    private static Plan or(Plan left, Plan right) {
        List<Group> groups = new ArrayList<>(left.groups());
        groups.addAll(right.groups());
        Plan result = new Plan(groups);
        requireWithinBudget(result);
        return result;
    }

    private static Plan termPlan(Term term) {
        return new Plan(List.of(new Group(List.of(term))));
    }

    private static Plan truePlan() {
        return new Plan(List.of(new Group(List.of())));
    }

    private static Plan falsePlan() {
        return new Plan(List.of());
    }

    private static void requireWithinBudget(Plan plan) {
        if (plan.groups().size() > MAX_GROUPS) {
            throw budget("more than " + MAX_GROUPS + " OR groups");
        }
        for (Group group : plan.groups()) {
            if (group.terms().size() > MAX_TERMS_PER_GROUP) {
                throw budget("more than " + MAX_TERMS_PER_GROUP + " AND predicates in an OR group");
            }
        }
    }

    private static GraphqlException budget(String detail) {
        return new GraphqlException("generated root filter exceeds the static carrier budget: "
                + detail + " (maximum DNF is " + MAX_GROUPS + "x" + MAX_TERMS_PER_GROUP + ")");
    }

    public record Plan(List<Group> groups) {
        public Plan {
            groups = List.copyOf(groups);
        }

        public boolean alwaysTrue() {
            return groups.size() == 1 && groups.getFirst().terms().isEmpty();
        }

        public boolean alwaysFalse() {
            return groups.isEmpty();
        }
    }

    public record Group(List<Term> terms) {
        public Group {
            terms = List.copyOf(terms);
        }
    }

    public record Term(
            TitanGraphqlFilterLayout.Binding binding,
            GraphqlSelection.GeneratedRootFilterValue value,
            boolean negated
    ) {
    }
}
