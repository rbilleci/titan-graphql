package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.model.TitanGraphqlPolicyDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TitanGraphqlPolicyCompilerTest {

    @Test
    void compilesRolePoliciesAndConjoinsMultipleReviewedRules() {
        Map<String, TitanGraphqlPolicyDocument> policies = Map.of(
                "staff", policy("staff", "roleIn:admin, operator"),
                "signedIn", policy("signedIn", "authenticated"));

        GraphqlFieldPolicy compiled = TitanGraphqlPolicyCompiler.compile(
                List.of("staff", "signedIn"), policies::get);

        assertTrue(compiled.canRead("ADMIN"));
        assertTrue(compiled.canRead("operator"));
        assertFalse(compiled.canRead("reader"));
        assertFalse(compiled.canRead(""));
    }

    @Test
    void preservesAdminOnlyCompatibilityWithoutBindingToAFieldName() {
        GraphqlFieldPolicy compiled = TitanGraphqlPolicyCompiler.compile(
                List.of("restricted"), ignored -> policy("restricted", "adminOnly"));

        assertTrue(compiled.canRead("admin"));
        assertFalse(compiled.canRead("reader"));
    }

    @Test
    void failsClosedForUnknownOrNonRejectPolicies() {
        assertThrows(TitanGraphqlProjectionModelAdapterException.class,
                () -> TitanGraphqlPolicyCompiler.compile(List.of("x"), ignored -> policy("x", "customJava")));
        TitanGraphqlPolicyDocument allow = new TitanGraphqlPolicyDocument(
                "x", "", TitanGraphqlPolicyDocument.Effect.ALLOW, List.of(), "allowAll");
        assertThrows(TitanGraphqlProjectionModelAdapterException.class,
                () -> TitanGraphqlPolicyCompiler.compile(List.of("x"), ignored -> allow));
    }

    @Test
    void projectionAdapterAppliesTheSameCompilerToRelations() throws Exception {
        String yaml = Files.readString(Path.of(
                "src/test/resources/graphql/demo-blog.titan.graphql.yaml"))
                .replace("      author:\n        target: User",
                        "      author:\n        policies: [canReadUserEmail]\n        target: User");
        GraphqlSchema schema = ProjectionGraphqlAdapter.adapt(TitanGraphqlProjectionModelAdapter.adapt(
                TitanGraphqlModelDocumentYaml.parse(yaml), new GraphqlPolicy()));
        GraphqlFieldDescriptor author = schema.type("Article").field("author");

        assertTrue(author.canRead("admin"));
        assertFalse(author.canRead("reader"));
    }

    private static TitanGraphqlPolicyDocument policy(String name, String expression) {
        return new TitanGraphqlPolicyDocument(
                name, "", TitanGraphqlPolicyDocument.Effect.DENY, List.of(), expression);
    }
}
