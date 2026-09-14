package io.titan.graphql.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.model.TitanGraphqlFieldDocument;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlPolicyDocument;
import io.titan.graphql.model.TitanGraphqlRelationDocument;
import io.titan.graphql.model.TitanGraphqlTypeDocument;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TitanGraphqlCatalogModelInferenceTest {

    @Test
    void infersReviewOnlyDraftFromDemoBlogCatalog() {
        TitanGraphqlCatalogModelInferenceResult result = TitanGraphqlCatalogModelInference.inferConservativeDraftResult(
                TitanGraphqlCatalogFixtures.demoBlog()
        );
        TitanGraphqlModelDocument draft = result.document();

        assertEquals("demoBlogDraft", draft.metadata().name());
        assertEquals(List.of("inferred", "review-required"), draft.metadata().tags());
        assertEquals("demo_blog", draft.database().catalog());
        assertEquals("public", draft.database().defaultSchema());
        assertEquals(3, draft.database().tables().size());
        assertTrue(draft.roots().isEmpty());

        TitanGraphqlTypeDocument article = draft.types().get(0);
        assertEquals("Article", article.name());
        assertEquals("articles", article.table());
        assertEquals("public", article.schema());
        assertEquals("articles", article.physicalTable());
        assertEquals("id", article.primaryKey());
        assertEquals(List.of("id", "authorId", "title", "published"), fieldNames(article));
        assertEquals("Int", article.fields().get(0).type());
        assertEquals("Boolean", article.fields().get(3).type());

        TitanGraphqlRelationDocument author = article.relations().get(0);
        assertEquals("author", author.name());
        assertEquals("User", author.targetType());
        assertEquals("author_id", author.localColumn());
        assertEquals("id", author.targetColumn());
        assertEquals(TitanGraphqlRelationDocument.RelationDocumentCardinality.ONE, author.cardinality());
        assertEquals(List.of(TitanGraphqlCatalogModelInference.RELATION_REVIEW_POLICY), author.policies());

        assertTrue(result.report().skippedObjects().isEmpty());
        assertTrue(result.report().warnings().isEmpty());
        assertTrue(result.report().inferredObjects().stream()
                .anyMatch(object -> object.kind().equals("TABLE")
                        && object.path().equals("public.articles")
                        && object.output().equals("type Article")));
        assertTrue(result.report().reviewDecisions().stream()
                .anyMatch(decision -> decision.code().equals("PUBLIC_ROOTS_DISABLED")
                        && decision.path().equals("Article")));
    }

    @Test
    void marksSensitiveLookingFieldsWithReviewPolicy() {
        TitanGraphqlModelDocument draft = TitanGraphqlCatalogModelInference.inferConservativeDraft(
                TitanGraphqlCatalogFixtures.demoBlog()
        );

        TitanGraphqlTypeDocument user = draft.types().get(1);
        TitanGraphqlFieldDocument email = user.fields().get(2);

        assertEquals("email", email.name());
        assertEquals("String", email.type());
        assertTrue(email.nullable());
        assertEquals(List.of(TitanGraphqlCatalogModelInference.SENSITIVE_FIELD_REVIEW_POLICY), email.policies());
    }

    @Test
    void declaresOnlyPoliciesNeededByTheInferredDraft() {
        TitanGraphqlModelDocument draft = TitanGraphqlCatalogModelInference.inferConservativeDraft(
                TitanGraphqlCatalogFixtures.demoBlog()
        );

        assertEquals(2, draft.policies().size());
        assertEquals(
                List.of(
                        TitanGraphqlCatalogModelInference.RELATION_REVIEW_POLICY,
                        TitanGraphqlCatalogModelInference.SENSITIVE_FIELD_REVIEW_POLICY
                ),
                draft.policies().stream().map(TitanGraphqlPolicyDocument::name).toList()
        );
        for (TitanGraphqlPolicyDocument policy : draft.policies()) {
            assertEquals(TitanGraphqlPolicyDocument.Effect.DENY, policy.effect());
            assertEquals("review_required", policy.expression());
        }
    }

    @Test
    void keepsDraftGenerationDeterministic() {
        TitanGraphqlModelDocument first = TitanGraphqlCatalogModelInference.inferConservativeDraft(
                TitanGraphqlCatalogFixtures.demoBlog()
        );
        TitanGraphqlModelDocument second = TitanGraphqlCatalogModelInference.inferConservativeDraft(
                TitanGraphqlCatalogFixtures.demoBlog()
        );

        assertEquals(first, second);
    }

    @Test
    void mapsUnknownDatabaseTypesConservativelyToString() {
        TitanGraphqlCatalogSnapshot snapshot = new TitanGraphqlCatalogSnapshot(
                "custom",
                List.of(new TitanGraphqlCatalogSnapshot.Schema(
                        "public",
                        "",
                        List.of(new TitanGraphqlCatalogSnapshot.Table(
                                "public",
                                "api_keys",
                                TitanGraphqlCatalogSnapshot.TableKind.TABLE,
                                "",
                                List.of(
                                        new TitanGraphqlCatalogSnapshot.Column("id", "uuid", false, 1, ""),
                                        new TitanGraphqlCatalogSnapshot.Column("token_hash", "bytea", false, 2, "")
                                ),
                                new TitanGraphqlCatalogSnapshot.PrimaryKey("api_keys_pkey", List.of("id")),
                                List.of(),
                                List.of()
                        ))
                ))
        );

        TitanGraphqlModelDocument draft = TitanGraphqlCatalogModelInference.inferConservativeDraft(snapshot);

        assertTrue(draft.roots().isEmpty());
        assertEquals("ApiKey", draft.types().get(0).name());
        assertEquals("String", draft.types().get(0).fields().get(0).type());
        assertEquals("tokenHash", draft.types().get(0).fields().get(1).name());
        assertEquals(List.of(TitanGraphqlCatalogModelInference.SENSITIVE_FIELD_REVIEW_POLICY),
                draft.types().get(0).fields().get(1).policies());
    }

    @Test
    void reportsSkippedObjectsWarningsAndReviewDecisions() {
        TitanGraphqlCatalogSnapshot snapshot = new TitanGraphqlCatalogSnapshot(
                "custom",
                List.of(new TitanGraphqlCatalogSnapshot.Schema(
                        "public",
                        "",
                        List.of(
                                new TitanGraphqlCatalogSnapshot.Table(
                                        "public",
                                        "active_users",
                                        TitanGraphqlCatalogSnapshot.TableKind.VIEW,
                                        "",
                                        List.of(new TitanGraphqlCatalogSnapshot.Column("id", "integer", false, 1, "")),
                                        new TitanGraphqlCatalogSnapshot.PrimaryKey("active_users_pkey", List.of("id")),
                                        List.of(),
                                        List.of()
                                ),
                                new TitanGraphqlCatalogSnapshot.Table(
                                        "public",
                                        "events",
                                        TitanGraphqlCatalogSnapshot.TableKind.TABLE,
                                        "",
                                        List.of(new TitanGraphqlCatalogSnapshot.Column("payload", "jsonb", true, 1, "")),
                                        null,
                                        List.of(),
                                        List.of()
                                )
                        )
                ))
        );

        TitanGraphqlCatalogModelInferenceResult result = TitanGraphqlCatalogModelInference.inferConservativeDraftResult(snapshot);

        assertEquals(1, result.document().types().size());
        assertEquals("Event", result.document().types().get(0).name());
        assertEquals("String", result.document().types().get(0).fields().get(0).type());
        assertEquals(1, result.report().skippedObjects().size());
        assertEquals("VIEW", result.report().skippedObjects().get(0).kind());
        assertEquals("public.active_users", result.report().skippedObjects().get(0).path());
        assertTrue(result.report().warnings().stream()
                .anyMatch(warning -> warning.code().equals("MISSING_PRIMARY_KEY")
                        && warning.path().equals("public.events")));
        assertTrue(result.report().warnings().stream()
                .anyMatch(warning -> warning.code().equals("UNKNOWN_DATABASE_TYPE")
                        && warning.path().equals("public.events.payload")));
        assertTrue(result.report().reviewDecisions().stream()
                .anyMatch(decision -> decision.code().equals("YAML_EXPORT_BLOCKED")
                        && decision.path().equals("titan.graphql.yaml")));
    }

    @Test
    void rendersInferenceReportAsTerminalTextAndJson() {
        TitanGraphqlCatalogModelInferenceResult result = TitanGraphqlCatalogModelInference.inferConservativeDraftResult(
                TitanGraphqlCatalogFixtures.demoBlog()
        );

        String text = TitanGraphqlCatalogInferenceReportRenderer.terminalText(result.report());
        String json = TitanGraphqlCatalogInferenceReportRenderer.json(result.report());

        assertTrue(text.startsWith("Inference report: "));
        assertTrue(text.contains("inferred TABLE public.articles -> type Article"));
        assertTrue(text.contains("review PUBLIC_ROOTS_DISABLED Article"));
        assertTrue(text.contains("review YAML_EXPORT_BLOCKED titan.graphql.yaml"));
        assertTrue(json.contains("\"inferredObjects\""));
        assertTrue(json.contains("\"code\":\"PUBLIC_ROOTS_DISABLED\""));
        assertTrue(json.contains("\"path\":\"titan.graphql.yaml\""));
    }

    @Test
    void rejectsMissingSnapshot() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TitanGraphqlCatalogModelInference.inferConservativeDraft(null)
        );
    }

    private static List<String> fieldNames(TitanGraphqlTypeDocument type) {
        return type.fields().stream().map(TitanGraphqlFieldDocument::name).toList();
    }
}
