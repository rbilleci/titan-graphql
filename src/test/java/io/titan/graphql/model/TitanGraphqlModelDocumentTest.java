package io.titan.graphql.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TitanGraphqlModelDocumentTest {

    @Test
    void appliesExplicitDefaults() {
        TitanGraphqlModelDocument document = new TitanGraphqlModelDocument(
                new TitanGraphqlModelMetadata("demo-blog"),
                List.of(),
                List.of()
        );

        assertEquals(TitanGraphqlModelDocument.CURRENT_API_VERSION, document.apiVersion());
        assertEquals(TitanGraphqlModelDocument.PROJECTION_MODEL_KIND, document.kind());
        assertEquals("", document.database().catalog());
        assertTrue(document.modules().isEmpty());
        assertTrue(document.policies().isEmpty());
        assertTrue(document.contextFilters().isEmpty());
        assertTrue(document.artifacts().generateSdl());
        assertEquals("build/generated/titan-graphql", document.artifacts().outputDirectory());
        assertEquals("", document.deployment().environment());
    }

    @Test
    void copiesCollectionsForStableEquality() {
        List<TitanGraphqlRootDocument> roots = new ArrayList<>();
        roots.add(TitanGraphqlRootDocument.point(
                "article",
                "Article",
                TitanGraphqlRootDocument.RootDocumentArgument.equals("id", "Int", "id")
        ));
        List<TitanGraphqlTypeDocument> types = new ArrayList<>();
        types.add(articleType());

        TitanGraphqlModelDocument first = new TitanGraphqlModelDocument(
                new TitanGraphqlModelMetadata("demo-blog"),
                roots,
                types
        );
        roots.clear();
        types.clear();
        TitanGraphqlModelDocument second = new TitanGraphqlModelDocument(
                new TitanGraphqlModelMetadata("demo-blog"),
                List.of(TitanGraphqlRootDocument.point(
                        "article",
                        "Article",
                        TitanGraphqlRootDocument.RootDocumentArgument.equals("id", "Int", "id")
                )),
                List.of(articleType())
        );

        assertEquals(second, first);
        assertThrows(UnsupportedOperationException.class, () -> first.roots().add(second.roots().get(0)));
    }

    @Test
    void representsDemoBlogModelVocabulary() {
        TitanGraphqlModelDocument document = demoBlogDocument();

        assertEquals("demo-blog", document.metadata().name());
        assertEquals(1, document.modules().size());
        assertEquals(2, document.roots().size());
        assertEquals(3, document.types().size());
        assertEquals(1, document.contextFilters().size());

        TitanGraphqlRootDocument articles = document.roots().get(1);
        assertEquals(TitanGraphqlRootDocument.RootDocumentOperation.CONNECTION, articles.operation());
        assertEquals(TitanGraphqlRootDocument.TotalCountMode.EXACT, articles.pagination().totalCount());
        assertEquals("publishedVisibility", articles.contextFilters().get(0));
        assertEquals("authorName", articles.filterPaths().get(0).name());
        assertEquals(1, articles.filterPaths().get(0).hops());

        TitanGraphqlTypeDocument article = document.types().get(0);
        TitanGraphqlFieldDocument titleLength = article.fields().get(2);
        assertEquals("titleLength", titleLength.name());
        assertEquals(TitanGraphqlFieldDocument.FieldDocumentExpressionKind.SQL_TEMPLATE, titleLength.computed().expressionKind());
        assertTrue(titleLength.computed().filterable());
        assertTrue(titleLength.computed().sortable());
        assertEquals(List.of("title"), titleLength.computed().requiredColumns());

        TitanGraphqlRelationDocument comments = article.relations().get(1);
        assertEquals(TitanGraphqlRelationDocument.RelationDocumentCardinality.MANY, comments.cardinality());
        assertEquals(TitanGraphqlRelationDocument.RelationDocumentPaginationMode.RELAY, comments.pagination().mode());
        assertTrue(comments.pagination().totalCount());
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertThrows(IllegalArgumentException.class, () -> new TitanGraphqlModelMetadata(""));
        assertThrows(IllegalArgumentException.class, () -> new TitanGraphqlModelDocument(
                null,
                TitanGraphqlModelDocument.PROJECTION_MODEL_KIND,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));
    }

    @Test
    void preservesOrderedListsForFutureCanonicalSerialization() {
        TitanGraphqlModelDocument document = demoBlogDocument();

        assertEquals(List.of("article", "articles"), document.modules().get(0).roots());
        assertEquals("article", document.roots().get(0).name());
        assertEquals("articles", document.roots().get(1).name());
        assertFalse(document.types().get(1).fields().get(2).policies().isEmpty());
    }

    @Test
    void serializesCanonicalJsonWithStableKeysAndDefaults() {
        TitanGraphqlModelDocument document = demoBlogDocument();

        String json = TitanGraphqlModelDocumentJson.canonicalJson(document);

        assertTrue(json.startsWith("{\"apiVersion\":\"titan.graphql/v1alpha1\",\"artifacts\":"));
        assertTrue(json.contains("\"metadata\":{\"description\":\"Bounded demo blog model.\",\"name\":\"demo-blog\""));
        assertTrue(json.contains("\"types\":[{\"description\":\"\",\"fields\":[{\"column\":\"id\",\"computed\":null"));
        assertTrue(json.contains("\"generateConformance\":true"));
        assertFalse(json.contains("\n"));
    }

    @Test
    void semanticHashIsStableAcrossInsignificantOrdering() {
        TitanGraphqlModelDocument document = demoBlogDocument();
        TitanGraphqlModelDocument reordered = new TitanGraphqlModelDocument(
                document.apiVersion(),
                document.kind(),
                new TitanGraphqlModelMetadata(
                        document.metadata().name(),
                        document.metadata().version(),
                        document.metadata().owner(),
                        document.metadata().description(),
                        List.of("query-only", "demo")
                ),
                new TitanGraphqlDatabaseDocument(
                        document.database().catalog(),
                        document.database().defaultSchema(),
                        List.of(
                                document.database().tables().get(2),
                                document.database().tables().get(0),
                                document.database().tables().get(1)
                        )
                ),
                List.of(new TitanGraphqlModuleDocument(
                        "content",
                        "content-platform",
                        List.of("articles", "article"),
                        List.of("Comment", "Article"),
                        List.of("Article.titleLength"),
                        List.of("Article.comments"),
                        List.of("canReadUserEmail")
                )),
                List.of(document.roots().get(1), document.roots().get(0)),
                List.of(document.types().get(2), document.types().get(0), document.types().get(1)),
                document.policies(),
                document.contextFilters(),
                document.artifacts(),
                document.deployment()
        );

        assertEquals(
                TitanGraphqlModelDocumentJson.canonicalJson(document),
                TitanGraphqlModelDocumentJson.canonicalJson(reordered)
        );
        assertEquals(
                TitanGraphqlModelDocumentJson.semanticHash(document),
                TitanGraphqlModelDocumentJson.semanticHash(reordered)
        );
        assertTrue(TitanGraphqlModelDocumentJson.semanticHash(document).matches("[0-9a-f]{64}"));
    }

    @Test
    void semanticHashChangesForBehaviorAffectingModelChanges() {
        TitanGraphqlModelDocument document = demoBlogDocument();
        TitanGraphqlModelDocument changed = new TitanGraphqlModelDocument(
                document.apiVersion(),
                document.kind(),
                document.metadata(),
                document.database(),
                document.modules(),
                document.roots(),
                List.of(articleType(), userType(), new TitanGraphqlTypeDocument(
                        "Comment",
                        "comments",
                        "public",
                        "comments",
                        "id",
                        List.of(
                                scalarField("id", "Int", "id"),
                                scalarField("body", "String", "body"),
                                scalarField("flagged", "Boolean", "flagged")
                        ),
                        List.of(oneRelation("author", "User", "author_id", "id"))
                )),
                document.policies(),
                document.contextFilters(),
                document.artifacts(),
                document.deployment()
        );

        assertNotEquals(
                TitanGraphqlModelDocumentJson.semanticHash(document),
                TitanGraphqlModelDocumentJson.semanticHash(changed)
        );
    }

    @Test
    void canonicalEnumOrderIsStableAndEnumValuesAffectSemanticIdentity() {
        TitanGraphqlModelDocument first = enumDocument(List.of(
                new TitanGraphqlEnumDocument("CustomerStatus", List.of("INACTIVE", "ACTIVE"), List.of(
                        new TitanGraphqlEnumDocument.EnumValueMetadata(
                                "INACTIVE", "Unavailable", true, "Use ACTIVE."),
                        new TitanGraphqlEnumDocument.EnumValueMetadata(
                                "ACTIVE", "Available", false, ""))),
                new TitanGraphqlEnumDocument("CustomerTier", List.of("PREMIUM", "STANDARD"))
        ));
        TitanGraphqlModelDocument reordered = enumDocument(List.of(
                new TitanGraphqlEnumDocument("CustomerTier", List.of("STANDARD", "PREMIUM")),
                new TitanGraphqlEnumDocument("CustomerStatus", List.of("ACTIVE", "INACTIVE"), List.of(
                        new TitanGraphqlEnumDocument.EnumValueMetadata(
                                "ACTIVE", "Available", false, ""),
                        new TitanGraphqlEnumDocument.EnumValueMetadata(
                                "INACTIVE", "Unavailable", true, "Use ACTIVE.")))
        ));
        TitanGraphqlModelDocument changed = enumDocument(List.of(
                new TitanGraphqlEnumDocument("CustomerStatus", List.of("ACTIVE", "INACTIVE"), List.of(
                        new TitanGraphqlEnumDocument.EnumValueMetadata(
                                "ACTIVE", "Available", false, ""),
                        new TitanGraphqlEnumDocument.EnumValueMetadata(
                                "INACTIVE", "Unavailable", true, "Use SUSPENDED."))),
                new TitanGraphqlEnumDocument("CustomerTier", List.of("PREMIUM", "STANDARD"))
        ));

        assertEquals(TitanGraphqlModelDocumentJson.canonicalJson(first),
                TitanGraphqlModelDocumentJson.canonicalJson(reordered));
        assertEquals(TitanGraphqlModelDocumentJson.semanticHash(first),
                TitanGraphqlModelDocumentJson.semanticHash(reordered));
        assertNotEquals(TitanGraphqlModelDocumentJson.semanticHash(first),
                TitanGraphqlModelDocumentJson.semanticHash(changed));
        assertTrue(TitanGraphqlModelDocumentJson.canonicalJson(first).contains("valueMetadata"));
    }

    private static TitanGraphqlModelDocument enumDocument(List<TitanGraphqlEnumDocument> enums) {
        return new TitanGraphqlModelDocument(
                TitanGraphqlModelDocument.CURRENT_API_VERSION,
                TitanGraphqlModelDocument.PROJECTION_MODEL_KIND,
                new TitanGraphqlModelMetadata("enum-model"),
                null,
                List.of(),
                List.of(),
                List.of(),
                enums,
                List.of(),
                List.of(),
                List.of(),
                null,
                null
        );
    }

    private static TitanGraphqlModelDocument demoBlogDocument() {
        return new TitanGraphqlModelDocument(
                TitanGraphqlModelDocument.CURRENT_API_VERSION,
                TitanGraphqlModelDocument.PROJECTION_MODEL_KIND,
                new TitanGraphqlModelMetadata(
                        "demo-blog",
                        "2026.05.31",
                        "platform",
                        "Bounded demo blog model.",
                        List.of("demo", "query-only")
                ),
                new TitanGraphqlDatabaseDocument(
                        "demo_blog",
                        "public",
                        List.of(
                                new TitanGraphqlDatabaseDocument.TableBinding("articles", "articles", "public", "id"),
                                new TitanGraphqlDatabaseDocument.TableBinding("users", "users", "public", "id"),
                                new TitanGraphqlDatabaseDocument.TableBinding("comments", "comments", "public", "id")
                        )
                ),
                List.of(new TitanGraphqlModuleDocument(
                        "content",
                        "content-platform",
                        List.of("article", "articles"),
                        List.of("Article", "Comment"),
                        List.of("Article.titleLength"),
                        List.of("Article.comments"),
                        List.of("canReadUserEmail")
                )),
                List.of(articleRoot(), articlesRoot()),
                List.of(articleType(), userType(), commentType()),
                List.of(new TitanGraphqlPolicyDocument(
                        "canReadUserEmail",
                        "Only privileged callers can read email.",
                        TitanGraphqlPolicyDocument.Effect.ALLOW,
                        List.of("User.email"),
                        "actor.canReadUserEmail"
                )),
                List.of(new TitanGraphqlContextFilterDocument(
                        "publishedVisibility",
                        "published",
                        "articleVisibility",
                        TitanGraphqlContextFilterDocument.Operator.BOOLEAN_EQUALS,
                        true,
                        true
                )),
                TitanGraphqlArtifactOptions.defaults(),
                new TitanGraphqlDeploymentDocument("dev", "/preview/demo-blog", "/graphql", List.of("platform"))
        );
    }

    private static TitanGraphqlRootDocument articleRoot() {
        return TitanGraphqlRootDocument.point(
                "article",
                "Article",
                TitanGraphqlRootDocument.RootDocumentArgument.equals("id", "Int", "id")
        );
    }

    private static TitanGraphqlRootDocument articlesRoot() {
        return TitanGraphqlRootDocument.connection(
                "articles",
                "Article",
                new TitanGraphqlRootDocument.RootDocumentPagination(
                        10,
                        100,
                        TitanGraphqlRootDocument.TotalCountMode.EXACT,
                        new TitanGraphqlRootDocument.Cursor(
                                "id",
                                "id",
                                TitanGraphqlRootDocument.RootDocumentSortDirection.ASC,
                                "id"
                        )
                ),
                List.of(TitanGraphqlRootDocument.RootDocumentArgument.equals("authorId", "Int", "author_id")),
                List.of(new TitanGraphqlRootDocument.RootDocumentFilterPath(
                        "authorName",
                        "String",
                        "author_id",
                        "author.name",
                        1,
                        List.of("eq", "neq", "in", "isNull", "contains", "startsWith", "endsWith")
                )),
                List.of(
                        new TitanGraphqlRootDocument.RootDocumentSortPath(
                                "id",
                                "id",
                                "id",
                                TitanGraphqlRootDocument.RootDocumentSortDirection.ASC,
                                TitanGraphqlRootDocument.RootDocumentNullOrdering.LAST,
                                "id",
                                0
                        ),
                        new TitanGraphqlRootDocument.RootDocumentSortPath(
                                "authorName",
                                "author_id",
                                "author.name",
                                TitanGraphqlRootDocument.RootDocumentSortDirection.ASC,
                                TitanGraphqlRootDocument.RootDocumentNullOrdering.LAST,
                                "id",
                                1
                        )
                ),
                List.of("publishedVisibility")
        );
    }

    private static TitanGraphqlTypeDocument articleType() {
        return new TitanGraphqlTypeDocument(
                "Article",
                "articles",
                "public",
                "articles",
                "id",
                List.of(
                        scalarField("id", "Int", "id"),
                        scalarField("title", "String", "title"),
                        new TitanGraphqlFieldDocument(
                                "titleLength",
                                "Int",
                                "",
                                false,
                                List.of(),
                                List.of("eq", "neq", "in", "isNull", "lt", "lte", "gt", "gte"),
                                new TitanGraphqlFieldDocument.Sort(
                                        "titleLength",
                                        TitanGraphqlFieldDocument.FieldDocumentSortDirection.ASC,
                                        TitanGraphqlFieldDocument.FieldDocumentNullOrdering.LAST,
                                        "id"
                                ),
                                new TitanGraphqlFieldDocument.Computed(
                                        TitanGraphqlFieldDocument.FieldDocumentExpressionKind.SQL_TEMPLATE,
                                        "length({title})",
                                        true,
                                        true,
                                        true,
                                        true,
                                        false,
                                        List.of("title"),
                                        TitanGraphqlFieldDocument.FieldDocumentCostClass.ROW_LOCAL
                                )
                        )
                ),
                List.of(
                        oneRelation("author", "User", "author_id", "id"),
                        new TitanGraphqlRelationDocument(
                                "comments",
                                "Comment",
                                "id",
                                "article_id",
                                TitanGraphqlRelationDocument.RelationDocumentCardinality.MANY,
                                false,
                                new TitanGraphqlRelationDocument.RelationDocumentPagination(
                                        TitanGraphqlRelationDocument.RelationDocumentPaginationMode.RELAY,
                                        10,
                                        100,
                                        true
                                ),
                                List.of(
                                        new TitanGraphqlRelationDocument.RelationDocumentArgument(
                                                "first",
                                                "Int",
                                                TitanGraphqlRelationDocument.RelationDocumentArgumentKind.RELAY_FIRST,
                                                "",
                                                "",
                                                0
                                        ),
                                        new TitanGraphqlRelationDocument.RelationDocumentArgument(
                                                "after",
                                                "String",
                                                TitanGraphqlRelationDocument.RelationDocumentArgumentKind.RELAY_AFTER,
                                                "",
                                                "",
                                                0
                                        )
                                ),
                                List.of(new TitanGraphqlRelationDocument.RelationDocumentSortPath(
                                        "id",
                                        "id",
                                        "id",
                                        TitanGraphqlRelationDocument.RelationDocumentSortDirection.ASC,
                                        "id",
                                        0
                                )),
                                List.of(),
                                2,
                                true,
                                true
                        )
                )
        );
    }

    private static TitanGraphqlTypeDocument userType() {
        return new TitanGraphqlTypeDocument(
                "User",
                "users",
                "public",
                "users",
                "id",
                List.of(
                        scalarField("id", "Int", "id"),
                        scalarField("name", "String", "name"),
                        new TitanGraphqlFieldDocument(
                                "email",
                                "String",
                                "email",
                                false,
                                List.of("canReadUserEmail"),
                                List.of("eq", "neq", "in", "isNull", "contains", "startsWith", "endsWith"),
                                new TitanGraphqlFieldDocument.Sort(
                                        "email",
                                        TitanGraphqlFieldDocument.FieldDocumentSortDirection.ASC,
                                        TitanGraphqlFieldDocument.FieldDocumentNullOrdering.LAST,
                                        "id"
                                ),
                                null
                        )
                ),
                List.of()
        );
    }

    private static TitanGraphqlTypeDocument commentType() {
        return new TitanGraphqlTypeDocument(
                "Comment",
                "comments",
                "public",
                "comments",
                "id",
                List.of(
                        scalarField("id", "Int", "id"),
                        scalarField("body", "String", "body")
                ),
                List.of(oneRelation("author", "User", "author_id", "id"))
        );
    }

    private static TitanGraphqlFieldDocument scalarField(String name, String type, String column) {
        return TitanGraphqlFieldDocument.column(
                name,
                type,
                column,
                List.of("eq", "neq", "in", "isNull"),
                new TitanGraphqlFieldDocument.Sort(
                        name,
                        TitanGraphqlFieldDocument.FieldDocumentSortDirection.ASC,
                        TitanGraphqlFieldDocument.FieldDocumentNullOrdering.LAST,
                        "id"
                )
        );
    }

    private static TitanGraphqlRelationDocument oneRelation(
            String name,
            String targetType,
            String localColumn,
            String targetColumn
    ) {
        return new TitanGraphqlRelationDocument(
                name,
                targetType,
                localColumn,
                targetColumn,
                TitanGraphqlRelationDocument.RelationDocumentCardinality.ONE,
                false,
                new TitanGraphqlRelationDocument.RelationDocumentPagination(
                        TitanGraphqlRelationDocument.RelationDocumentPaginationMode.NONE,
                        0,
                        0,
                        false
                ),
                List.of(),
                List.of(),
                List.of(),
                2,
                true,
                true
        );
    }
}
