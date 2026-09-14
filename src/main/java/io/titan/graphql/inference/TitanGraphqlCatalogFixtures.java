package io.titan.graphql.inference;

import java.util.List;

final class TitanGraphqlCatalogFixtures {

    private TitanGraphqlCatalogFixtures() {
    }

    static TitanGraphqlCatalogSnapshot demoBlog() {
        return new TitanGraphqlCatalogSnapshot(
                "demo_blog",
                List.of(new TitanGraphqlCatalogSnapshot.Schema(
                        "public",
                        "Demo blog application schema.",
                        List.of(articles(), users(), comments())
                ))
        );
    }

    private static TitanGraphqlCatalogSnapshot.Table articles() {
        return new TitanGraphqlCatalogSnapshot.Table(
                "public",
                "articles",
                TitanGraphqlCatalogSnapshot.TableKind.TABLE,
                "Published and draft blog articles.",
                List.of(
                        column("id", "integer", false, 1, "Article identifier."),
                        column("author_id", "integer", false, 2, "User that authored the article."),
                        column("title", "text", false, 3, "Article title."),
                        column("published", "boolean", false, 4, "Whether the article is publicly visible.")
                ),
                primaryKey("articles_pkey", "id"),
                List.of(new TitanGraphqlCatalogSnapshot.ForeignKey(
                        "articles_author_id_fkey",
                        List.of("author_id"),
                        "public",
                        "users",
                        List.of("id"),
                        "Article author relation."
                )),
                List.of(
                        index("articles_pkey", List.of("id"), true),
                        index("articles_author_id_idx", List.of("author_id"), false),
                        index("articles_published_id_idx", List.of("published", "id"), false)
                )
        );
    }

    private static TitanGraphqlCatalogSnapshot.Table users() {
        return new TitanGraphqlCatalogSnapshot.Table(
                "public",
                "users",
                TitanGraphqlCatalogSnapshot.TableKind.TABLE,
                "Application users.",
                List.of(
                        column("id", "integer", false, 1, "User identifier."),
                        column("name", "text", false, 2, "Display name."),
                        column("email", "text", true, 3, "Sensitive contact email.")
                ),
                primaryKey("users_pkey", "id"),
                List.of(),
                List.of(
                        index("users_pkey", List.of("id"), true),
                        index("users_email_key", List.of("email"), true)
                )
        );
    }

    private static TitanGraphqlCatalogSnapshot.Table comments() {
        return new TitanGraphqlCatalogSnapshot.Table(
                "public",
                "comments",
                TitanGraphqlCatalogSnapshot.TableKind.TABLE,
                "Reader comments on articles.",
                List.of(
                        column("id", "integer", false, 1, "Comment identifier."),
                        column("article_id", "integer", false, 2, "Article that owns the comment."),
                        column("author_id", "integer", false, 3, "User that wrote the comment."),
                        column("body", "text", false, 4, "Comment body."),
                        column("flagged", "boolean", false, 5, "Moderation flag.")
                ),
                primaryKey("comments_pkey", "id"),
                List.of(
                        new TitanGraphqlCatalogSnapshot.ForeignKey(
                                "comments_article_id_fkey",
                                List.of("article_id"),
                                "public",
                                "articles",
                                List.of("id"),
                                "Comment article relation."
                        ),
                        new TitanGraphqlCatalogSnapshot.ForeignKey(
                                "comments_author_id_fkey",
                                List.of("author_id"),
                                "public",
                                "users",
                                List.of("id"),
                                "Comment author relation."
                        )
                ),
                List.of(
                        index("comments_pkey", List.of("id"), true),
                        index("comments_article_id_idx", List.of("article_id", "id"), false),
                        index("comments_author_id_idx", List.of("author_id"), false)
                )
        );
    }

    private static TitanGraphqlCatalogSnapshot.Column column(
            String name,
            String databaseType,
            boolean nullable,
            int ordinalPosition,
            String comment
    ) {
        return new TitanGraphqlCatalogSnapshot.Column(name, databaseType, nullable, ordinalPosition, comment);
    }

    private static TitanGraphqlCatalogSnapshot.PrimaryKey primaryKey(String name, String column) {
        return new TitanGraphqlCatalogSnapshot.PrimaryKey(name, List.of(column));
    }

    private static TitanGraphqlCatalogSnapshot.Index index(String name, List<String> columns, boolean unique) {
        return new TitanGraphqlCatalogSnapshot.Index(name, columns, unique, "");
    }
}
