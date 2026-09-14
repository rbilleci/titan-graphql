package io.titan.graphql;

import io.titan.graphql.model.TitanGraphqlPolicyDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Compiles the reviewed, deliberately small v1alpha1 named-policy language. */
public final class TitanGraphqlPolicyCompiler {

    private TitanGraphqlPolicyCompiler() {
    }

    public static GraphqlFieldPolicy compile(
            List<String> policyNames,
            Function<String, TitanGraphqlPolicyDocument> resolver
    ) {
        if (policyNames.isEmpty()) return GraphqlFieldPolicy.ALLOW;
        List<GraphqlFieldPolicy> policies = new ArrayList<>();
        for (String name : policyNames) {
            TitanGraphqlPolicyDocument document = resolver.apply(name);
            if (document.effect() != TitanGraphqlPolicyDocument.Effect.DENY) {
                throw unsupported(document, "only reject/deny policies are enforceable");
            }
            policies.add(expression(document));
        }
        return actorRole -> policies.stream().allMatch(policy -> policy.canRead(actorRole));
    }

    private static GraphqlFieldPolicy expression(TitanGraphqlPolicyDocument policy) {
        String expression = policy.expression().trim();
        if (expression.equals("adminOnly")) {
            return roleEquals("admin"); // v1alpha1 compatibility spelling
        }
        if (expression.equals("authenticated")) {
            return actorRole -> actorRole != null && !actorRole.isBlank();
        }
        if (expression.equals("allowAll")) return GraphqlFieldPolicy.ALLOW;
        if (expression.equals("denyAll")) return actorRole -> false;
        if (expression.startsWith("roleEquals:")) {
            String role = expression.substring("roleEquals:".length()).trim();
            if (role.isEmpty() || role.contains(",")) throw unsupported(policy, "invalid roleEquals value");
            return roleEquals(role);
        }
        if (expression.startsWith("roleIn:")) {
            List<String> roles = java.util.Arrays.stream(expression.substring("roleIn:".length()).split(","))
                    .map(String::trim).filter(value -> !value.isEmpty())
                    .map(value -> value.toLowerCase(Locale.ROOT)).distinct().toList();
            if (roles.isEmpty()) throw unsupported(policy, "roleIn requires at least one role");
            return actorRole -> actorRole != null
                    && roles.contains(actorRole.trim().toLowerCase(Locale.ROOT));
        }
        throw unsupported(policy, "unknown named expression '" + expression + "'");
    }

    private static GraphqlFieldPolicy roleEquals(String expected) {
        return actorRole -> actorRole != null && expected.equalsIgnoreCase(actorRole.trim());
    }

    private static TitanGraphqlProjectionModelAdapterException unsupported(
            TitanGraphqlPolicyDocument policy,
            String detail
    ) {
        return new TitanGraphqlProjectionModelAdapterException(
                "UNSUPPORTED_POLICY", "policy '" + policy.name() + "' cannot be compiled: " + detail);
    }
}
