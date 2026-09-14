package io.titan.graphql.demo.blog;

import io.titan.graphql.*;

import java.util.List;

public final class DemoBlogGraphqlSchema {

    private DemoBlogGraphqlSchema() {
    }

    public static GraphqlSchema create(GraphqlPolicy policy) {
        return ProjectionGraphqlAdapter.adapt(projectionModel(policy));
    }

    public static ProjectionModel projectionModel(GraphqlPolicy policy) {
        return new ProjectionModel(
                List.of(
                        ProjectionRetrieval.point("article", "Article", "id"),
                        ProjectionRetrieval.relayConnection(
                                "articles",
                                "Article",
                                10,
                                100,
                                List.of(ProjectionRetrieval.RetrievalArgument.intEquals("authorId", "author_id")),
                                ProjectionRetrieval.RetrievalCursorOrdering.ascending("id", "id", "id"),
                                List.of(ProjectionRetrieval.RetrievalFilterPath.string(
                                        "authorName",
                                        "author_id",
                                        "author.name",
                                        1
                                )),
                                List.of(
                                        ProjectionRetrieval.RetrievalSortPath.ascending("id", "id", "id", 0),
                                        ProjectionRetrieval.RetrievalSortPath.ascending("title", "title", "title", 0),
                                        ProjectionRetrieval.RetrievalSortPath.ascending("titleLength", "titleLength", "titleLength", 0),
                                        ProjectionRetrieval.RetrievalSortPath.ascending("authorName", "author_id", "author.name", 1)
                                ),
                                List.of(ProjectionRetrieval.RetrievalContextFilter.booleanEquals(
                                        "publishedVisibility",
                                        "published",
                                        "articleVisibility"
                                ))
                        )
                ),
                List.of(
                        new ProjectionType(
                                "Article",
                                "articles",
                                "public",
                                "articles",
                                "id",
                                List.of(
                                        ProjectionField.column("id", "id"),
                                        ProjectionField.column("title", "title"),
                                        ProjectionField.computed(
                                                ProjectionField.ProjectionComputedExpression.filterableSortableSqlTemplate(
                                                        "titleLength",
                                                        "Int",
                                                        "length({title})",
                                                        List.of("title")
                                                ),
                                                GraphqlFieldPolicy.ALLOW
                                        )
                                ),
                                List.of(
                                        ProjectionRelation.one(
                                                "author",
                                                "User",
                                                "author_id",
                                                "id",
                                                false
                                        ),
                                        ProjectionRelation.many(
                                                "comments",
                                                "Comment",
                                                "id",
                                                "article_id",
                                                false,
                                                ProjectionRelation.ProjectionRelationCapabilities.relayConnectionWithTotalCount(
                                                        false,
                                                        true,
                                                        2,
                                                        0,
                                                        0,
                                                        10,
                                                        100
                                                ),
                                                List.of(
                                                        ProjectionRelation.ProjectionRelationArgument.relayFirst(),
                                                        ProjectionRelation.ProjectionRelationArgument.relayAfter(),
                                                        ProjectionRelation.ProjectionRelationArgument.relayLast(),
                                                        ProjectionRelation.ProjectionRelationArgument.relayBefore()
                                                ),
                                                List.of(ProjectionRelation.ProjectionRelationSortPath.ascending(
                                                        "id",
                                                        "id",
                                                        "id",
                                                        0
                                                ))
                                        )
                                )
                        ),
                        new ProjectionType(
                                "User",
                                "users",
                                "public",
                                "users",
                                "id",
                                List.of(
                                        ProjectionField.column("id", "id"),
                                        ProjectionField.column("name", "name"),
                                        ProjectionField.column("email", "email", policy::canReadUserEmail)
                                ),
                                List.of()
                        ),
                        new ProjectionType(
                                "Comment",
                                "comments",
                                "public",
                                "comments",
                                "id",
                                List.of(
                                        ProjectionField.column("id", "id"),
                                        ProjectionField.column("body", "body")
                                ),
                                List.of(
                                        ProjectionRelation.one(
                                                "author",
                                                "User",
                                                "author_id",
                                                "id",
                                                false
                                        )
                                )
                        )
                )
        );
    }
}
