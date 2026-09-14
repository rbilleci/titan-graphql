package io.titan.graphql.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.model.TitanGraphqlArtifactOptions;
import io.titan.graphql.model.TitanGraphqlDeploymentDocument;
import io.titan.graphql.model.TitanGraphqlFieldDocument;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelMetadata;
import io.titan.graphql.model.TitanGraphqlRelationDocument;
import io.titan.graphql.model.TitanGraphqlRootDocument;
import io.titan.graphql.model.TitanGraphqlTypeDocument;
import io.titan.graphql.validation.TitanGraphqlValidationIssue;
import io.titan.graphql.validation.TitanGraphqlValidationReport;
import io.titan.graphql.validation.TitanGraphqlValidationReportRenderer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TitanGraphqlCatalogDriftCheckerTest {

    @Test
    void acceptsModelBindingsThatMatchCatalogSnapshot() {
        TitanGraphqlModelDocument document = withArticleRoots(
                TitanGraphqlCatalogModelInference.inferConservativeDraft(TitanGraphqlCatalogFixtures.demoBlog())
        );

        TitanGraphqlValidationReport report = TitanGraphqlCatalogDriftChecker.checkBindings(
                document,
                TitanGraphqlCatalogFixtures.demoBlog()
        );

        assertTrue(report.valid());
        assertTrue(report.issues().isEmpty());
    }

    @Test
    void detectsMissingTableColumnScalarNullabilityAndPrimaryKeyDrift() {
        TitanGraphqlModelDocument document = withArticleRoots(
                TitanGraphqlCatalogModelInference.inferConservativeDraft(TitanGraphqlCatalogFixtures.demoBlog())
        );
        TitanGraphqlCatalogSnapshot drifted = new TitanGraphqlCatalogSnapshot(
                "demo_blog",
                List.of(new TitanGraphqlCatalogSnapshot.Schema(
                        "public",
                        "",
                        List.of(new TitanGraphqlCatalogSnapshot.Table(
                                "public",
                                "articles",
                                TitanGraphqlCatalogSnapshot.TableKind.TABLE,
                                "",
                                List.of(
                                        new TitanGraphqlCatalogSnapshot.Column("id", "text", true, 1, ""),
                                        new TitanGraphqlCatalogSnapshot.Column("author_id", "integer", false, 2, ""),
                                        new TitanGraphqlCatalogSnapshot.Column("published", "boolean", false, 4, "")
                                ),
                                new TitanGraphqlCatalogSnapshot.PrimaryKey("articles_pkey", List.of("slug")),
                                List.of(),
                                List.of()
                        ))
                ))
        );

        TitanGraphqlValidationReport report = TitanGraphqlCatalogDriftChecker.checkBindings(document, drifted);

        assertEquals(
                List.of(
                        "MISSING_KEY",
                        "SCALAR_TYPE_MISMATCH",
                        "NULLABILITY_MISMATCH",
                        "MISSING_COLUMN",
                        "BROKEN_RELATION_JOIN",
                        "MISSING_TABLE",
                        "MISSING_TABLE",
                        "MISSING_INDEX",
                        "MISSING_INDEX",
                        "MISSING_INDEX"
                ),
                driftKinds(report)
        );
        assertTrue(report.blocksDeployment());
        assertTrue(report.issues().stream()
                .anyMatch(issue -> issue.modelPath().displayPath().equals("$.types.Article.fields.title")));
        assertTrue(report.issues().stream()
                .anyMatch(issue -> "text".equals(issue.metadata().get("databaseType"))
                        && "String".equals(issue.metadata().get("actualScalar"))));
    }

    @Test
    void detectsComputedRootIndexAndRelationJoinDrift() {
        TitanGraphqlModelDocument document = withComputedTitleLength(withArticleRoots(
                TitanGraphqlCatalogModelInference.inferConservativeDraft(TitanGraphqlCatalogFixtures.demoBlog())
        ));
        TitanGraphqlCatalogSnapshot drifted = new TitanGraphqlCatalogSnapshot(
                "demo_blog",
                List.of(new TitanGraphqlCatalogSnapshot.Schema(
                        "public",
                        "",
                        List.of(
                                new TitanGraphqlCatalogSnapshot.Table(
                                        "public",
                                        "articles",
                                        TitanGraphqlCatalogSnapshot.TableKind.TABLE,
                                        "",
                                        List.of(
                                                new TitanGraphqlCatalogSnapshot.Column("id", "integer", false, 1, ""),
                                                new TitanGraphqlCatalogSnapshot.Column("author_id", "integer", false, 2, ""),
                                                new TitanGraphqlCatalogSnapshot.Column("published", "boolean", false, 3, "")
                                        ),
                                        new TitanGraphqlCatalogSnapshot.PrimaryKey("articles_pkey", List.of("id")),
                                        List.of(),
                                        List.of(new TitanGraphqlCatalogSnapshot.Index("articles_pkey", List.of("id"), true, ""))
                                ),
                                new TitanGraphqlCatalogSnapshot.Table(
                                        "public",
                                        "users",
                                        TitanGraphqlCatalogSnapshot.TableKind.TABLE,
                                        "",
                                        List.of(new TitanGraphqlCatalogSnapshot.Column("id", "integer", false, 1, "")),
                                        new TitanGraphqlCatalogSnapshot.PrimaryKey("users_pkey", List.of("id")),
                                        List.of(),
                                        List.of(new TitanGraphqlCatalogSnapshot.Index("users_pkey", List.of("id"), true, ""))
                                ),
                                commentsTable()
                        )
                ))
        );

        TitanGraphqlValidationReport report = TitanGraphqlCatalogDriftChecker.checkBindings(document, drifted);

        assertTrue(driftKinds(report).contains("MISSING_COMPUTED_REQUIRED_COLUMN"));
        assertTrue(driftKinds(report).contains("BROKEN_RELATION_JOIN"));
        assertTrue(driftKinds(report).contains("MISSING_INDEX"));
        assertTrue(report.issues().stream()
                .anyMatch(issue -> issue.modelPath().displayPath()
                        .equals("$.types.Article.fields.titleLength.computed.requiredColumns.title")));
        assertTrue(report.issues().stream()
                .anyMatch(issue -> issue.modelPath().displayPath().equals("$.roots.articles.filterPaths.published")));
    }

    @Test
    void rendersDriftReportThroughSharedTerminalAndJsonSurfaces() {
        TitanGraphqlModelDocument document = new TitanGraphqlModelDocument(
                new TitanGraphqlModelMetadata("demo-blog"),
                List.of(),
                List.of(new TitanGraphqlTypeDocument(
                        "Article",
                        "articles",
                        "public",
                        "",
                        "id",
                        List.of(),
                        List.of()
                ))
        );
        TitanGraphqlValidationReport report = TitanGraphqlCatalogDriftChecker.checkBindings(
                document,
                new TitanGraphqlCatalogSnapshot("demo_blog", List.of())
        );

        assertEquals(String.join(System.lineSeparator(),
                "Validation report: invalid (1 error, 0 warnings, 0 info, blocks deployment)",
                "error DRIFT_DETECTED $.types.Article - Type 'Article' binds missing table 'public.articles'.",
                "  driftKind: MISSING_TABLE",
                "  schema: public",
                "  table: articles"
        ), TitanGraphqlValidationReportRenderer.terminalText(report));
        assertEquals(
                "{\"blocksDeployment\":true,\"errorCount\":1,\"infoCount\":0,\"issues\":["
                        + "{\"blocksDeployment\":true,\"code\":\"DRIFT_DETECTED\","
                        + "\"message\":\"Type 'Article' binds missing table 'public.articles'.\","
                        + "\"metadata\":{\"driftKind\":\"MISSING_TABLE\",\"schema\":\"public\",\"table\":\"articles\"},"
                        + "\"modelPath\":\"$.types.Article\",\"severity\":\"ERROR\","
                        + "\"sourceLocation\":{\"column\":0,\"line\":0,\"source\":\"\"}}],"
                        + "\"valid\":false,\"warningCount\":0}",
                TitanGraphqlValidationReportRenderer.json(report)
        );
    }

    @Test
    void rejectsMissingInputs() {
        TitanGraphqlModelDocument document = TitanGraphqlCatalogModelInference.inferConservativeDraft(
                TitanGraphqlCatalogFixtures.demoBlog()
        );

        assertThrows(IllegalArgumentException.class,
                () -> TitanGraphqlCatalogDriftChecker.checkBindings(null, TitanGraphqlCatalogFixtures.demoBlog()));
        assertThrows(IllegalArgumentException.class,
                () -> TitanGraphqlCatalogDriftChecker.checkBindings(document, null));
    }

    private static TitanGraphqlModelDocument withArticleRoots(TitanGraphqlModelDocument document) {
        return new TitanGraphqlModelDocument(
                document.apiVersion(),
                document.kind(),
                document.metadata(),
                document.database(),
                document.modules(),
                List.of(
                        TitanGraphqlRootDocument.point(
                                "article",
                                "Article",
                                TitanGraphqlRootDocument.RootDocumentArgument.equals("id", "Int!", "id")
                        ),
                        TitanGraphqlRootDocument.connection(
                                "articles",
                                "Article",
                                new TitanGraphqlRootDocument.RootDocumentPagination(
                                        20,
                                        100,
                                        TitanGraphqlRootDocument.TotalCountMode.EXACT,
                                        new TitanGraphqlRootDocument.Cursor("id", "id", TitanGraphqlRootDocument.RootDocumentSortDirection.ASC, "")
                                ),
                                List.of(),
                                List.of(new TitanGraphqlRootDocument.RootDocumentFilterPath(
                                        "published",
                                        "Boolean",
                                        "published",
                                        "published",
                                        0,
                                        List.of("eq")
                                )),
                                List.of(new TitanGraphqlRootDocument.RootDocumentSortPath(
                                        "id",
                                        "id",
                                        "id",
                                        TitanGraphqlRootDocument.RootDocumentSortDirection.ASC,
                                        TitanGraphqlRootDocument.RootDocumentNullOrdering.LAST,
                                        "",
                                        0
                                )),
                                List.of()
                        )
                ),
                document.types(),
                document.policies(),
                document.contextFilters(),
                TitanGraphqlArtifactOptions.defaults(),
                TitanGraphqlDeploymentDocument.empty()
        );
    }

    private static TitanGraphqlModelDocument withComputedTitleLength(TitanGraphqlModelDocument document) {
        List<TitanGraphqlTypeDocument> types = new ArrayList<>();
        for (TitanGraphqlTypeDocument type : document.types()) {
            if (!type.name().equals("Article")) {
                types.add(type);
                continue;
            }
            List<TitanGraphqlFieldDocument> fields = new ArrayList<>(type.fields());
            fields.add(new TitanGraphqlFieldDocument(
                    "titleLength",
                    "Int",
                    "",
                    false,
                    List.of(),
                    List.of(),
                    null,
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
            ));
            types.add(new TitanGraphqlTypeDocument(
                    type.name(),
                    type.table(),
                    type.schema(),
                    type.physicalTable(),
                    type.primaryKey(),
                    fields,
                    type.relations()
            ));
        }
        return new TitanGraphqlModelDocument(
                document.apiVersion(),
                document.kind(),
                document.metadata(),
                document.database(),
                document.modules(),
                document.roots(),
                types,
                document.policies(),
                document.contextFilters(),
                document.artifacts(),
                document.deployment()
        );
    }

    private static TitanGraphqlCatalogSnapshot.Table commentsTable() {
        return new TitanGraphqlCatalogSnapshot.Table(
                "public",
                "comments",
                TitanGraphqlCatalogSnapshot.TableKind.TABLE,
                "",
                List.of(
                        new TitanGraphqlCatalogSnapshot.Column("id", "integer", false, 1, ""),
                        new TitanGraphqlCatalogSnapshot.Column("article_id", "integer", false, 2, "")
                ),
                new TitanGraphqlCatalogSnapshot.PrimaryKey("comments_pkey", List.of("id")),
                List.of(new TitanGraphqlCatalogSnapshot.ForeignKey(
                        "comments_article_id_fkey",
                        List.of("article_id"),
                        "public",
                        "articles",
                        List.of("id"),
                        ""
                )),
                List.of(new TitanGraphqlCatalogSnapshot.Index("comments_pkey", List.of("id"), true, ""))
        );
    }

    private static List<String> driftKinds(TitanGraphqlValidationReport report) {
        return report.issues().stream()
                .map(TitanGraphqlValidationIssue::metadata)
                .map(metadata -> metadata.get("driftKind"))
                .toList();
    }
}
