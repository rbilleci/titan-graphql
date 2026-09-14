package io.titan.graphql;

import io.titan.graphql.demo.blog.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphqlSchemaPrinterTest {

    @Test
    void printsDefaultDemoSchemaSnapshot() {
        String schema = GraphqlSchemaPrinter.print(DemoBlogGraphqlSchema.create(new GraphqlPolicy()));

        assertEquals("""
                directive @relationSortPath(name: String!, column: String!, path: String!, hops: Int!, direction: String!, tieBreaker: String!) repeatable on FIELD_DEFINITION

                type Query {
                  article(id: Int!): Article
                  articles(first: Int, after: String, last: Int, before: String, authorId: Int, filter: ArticleFilter, orderBy: [ArticleOrderBy!]): ArticleConnection!
                }

                input IntFilter {
                  eq: Int
                  neq: Int
                  in: [Int!]
                  isNull: Boolean
                  lt: Int
                  lte: Int
                  gt: Int
                  gte: Int
                }

                input StringFilter {
                  eq: String
                  neq: String
                  in: [String!]
                  isNull: Boolean
                  contains: String
                  startsWith: String
                  endsWith: String
                }

                input ArticleFilter {
                  id: IntFilter
                  title: StringFilter
                  titleLength: IntFilter
                  authorId: IntFilter
                  authorName: StringFilter
                  and: [ArticleFilter!]
                  or: [ArticleFilter!]
                  not: ArticleFilter
                }

                enum SortDirection {
                  ASC
                  DESC
                }

                input ArticleOrderBy {
                  id: SortDirection
                  title: SortDirection
                  titleLength: SortDirection
                  authorName: SortDirection
                }

                type Article {
                  id: Int
                  title: String
                  titleLength: Int
                  author: User!
                  comments(first: Int, after: String, last: Int, before: String): CommentConnection! @relationSortPath(name: "id", column: "id", path: "id", hops: 0, direction: "ASC", tieBreaker: "id")
                }

                type User {
                  id: Int
                  name: String
                  email: String
                }

                type Comment {
                  id: Int
                  body: String
                  author: User!
                }

                type ArticleConnection {
                  edges: [ArticleEdge!]!
                  pageInfo: PageInfo!
                  totalCount: Int!
                }

                type ArticleEdge {
                  cursor: String!
                  node: Article!
                }

                type CommentConnection {
                  edges: [CommentEdge!]!
                  pageInfo: PageInfo!
                  totalCount: Int!
                }

                type CommentEdge {
                  cursor: String!
                  node: Comment!
                }

                type PageInfo {
                  hasNextPage: Boolean
                  hasPreviousPage: Boolean
                  startCursor: String
                  endCursor: String
                }
                """, schema);
    }

    @Test
    void generatedSchemaDocumentationMatchesPrinterOutput() throws IOException {
        String documentation = Files.readString(Path.of("docs/generated-schema.md"));
        String schema = GraphqlSchemaPrinter.print(DemoBlogGraphqlSchema.create(new GraphqlPolicy()));

        assertEquals(schema, firstFencedGraphqlBlock(documentation),
                "docs/generated-schema.md must mirror the generated demo schema");
    }

    @Test
    void demoBlogYamlBackedProjectionPrintsSameSchemaAsJavaDescriptor() throws IOException {
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(readDemoBlogFixture());
        GraphqlPolicy policy = new GraphqlPolicy();

        GraphqlSchema yamlBackedSchema = ProjectionGraphqlAdapter.adapt(
                TitanGraphqlProjectionModelAdapter.adapt(document, policy)
        );
        GraphqlSchema javaBackedSchema = DemoBlogGraphqlSchema.create(policy);

        assertEquals(
                GraphqlSchemaPrinter.print(javaBackedSchema),
                GraphqlSchemaPrinter.print(yamlBackedSchema)
        );
    }

    @Test
    void modelAdapterRejectsUnsupportedPolicyExpressions() {
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse("""
                apiVersion: titan.graphql/v1alpha1
                kind: ProjectionModel
                metadata:
                  name: demo
                policies:
                  customPolicy:
                    appliesTo: [Article.title]
                    mode: reject
                    expression:
                      kind: named
                      name: customPredicate
                types:
                  Article:
                    table: articles
                    primaryKey: id
                    fields:
                      id:
                        column: id
                        type: Int
                      title:
                        column: title
                        type: String
                        policy: customPolicy
                roots:
                  article:
                    type: Article
                    operation: point
                    argument:
                      name: id
                      type: Int
                      kind: equals
                      column: id
                """);

        TitanGraphqlProjectionModelAdapterException error = assertThrows(
                TitanGraphqlProjectionModelAdapterException.class,
                () -> TitanGraphqlProjectionModelAdapter.adapt(document, new GraphqlPolicy())
        );

        assertEquals("UNSUPPORTED_POLICY", error.code());
    }

    @Test
    void printsDeclaredRelationArgumentsAndSortPathMetadata() {
        GraphqlSchema schema = ProjectionGraphqlAdapter.adapt(new ProjectionModel(
                List.of(ProjectionRetrieval.point("article", "Article", "id")),
                List.of(
                        new ProjectionType(
                                "Article",
                                "articles",
                                "public",
                                "articles",
                                "id",
                                List.of(ProjectionField.column("id", "id")),
                                List.of(ProjectionRelation.many(
                                        "comments",
                                        "Comment",
                                        "id",
                                        "article_id",
                                        false,
                                        new ProjectionRelation.ProjectionRelationCapabilities(
                                                true,
                                                true,
                                                true,
                                                true,
                                                ProjectionRelation.ProjectionRelationPaginationMode.RELAY_CONNECTION,
                                                3,
                                                1,
                                                1
                                        ),
                                        List.of(
                                                ProjectionRelation.ProjectionRelationArgument.intEquals(
                                                        "authorName",
                                                        "author_id",
                                                        "author.name",
                                                        1
                                                ),
                                                ProjectionRelation.ProjectionRelationArgument.relayFirst(),
                                                ProjectionRelation.ProjectionRelationArgument.relayAfter()
                                        ),
                                        List.of(ProjectionRelation.ProjectionRelationSortPath.descending(
                                                "authorName",
                                                "author_id",
                                                "author.name",
                                                1
                                        ))
                                ))
                        ),
                        new ProjectionType(
                                "Comment",
                                "comments",
                                "public",
                                "comments",
                                "id",
                                List.of(ProjectionField.column("id", "id")),
                                List.of()
                        )
                )
        ));

        String printed = GraphqlSchemaPrinter.print(schema);

        assertTrue(printed.contains("directive @relationSortPath("));
        assertTrue(printed.contains("comments(authorName: Int, first: Int, after: String): CommentConnection!"));
        assertTrue(printed.contains("@relationSortPath(name: \"authorName\", column: \"author_id\", path: \"author.name\", hops: 1, direction: \"DESC\", tieBreaker: \"id\")"));
        assertTrue(printed.contains("type CommentConnection {\n"));
        assertTrue(printed.contains("  edges: [CommentEdge!]!\n"));
        assertTrue(printed.contains("type CommentEdge {\n"));
        assertTrue(printed.contains("  node: Comment!\n"));
        assertTrue(printed.contains("type PageInfo {\n"));
    }

    @Test
    void printsRelayConnectionCapableRootWithoutOffsetPagination() {
        GraphqlSchema schema = ProjectionGraphqlAdapter.adapt(new ProjectionModel(
                List.of(ProjectionRetrieval.relayConnection(
                        "articles",
                        "Article",
                        10,
                        100,
                        List.of(ProjectionRetrieval.RetrievalArgument.intEquals("authorId", "author_id"))
                )),
                List.of(new ProjectionType(
                        "Article",
                        "articles",
                        "public",
                        "articles",
                        "id",
                        List.of(ProjectionField.column("id", "id")),
                        List.of()
                ))
        ));

        String printed = GraphqlSchemaPrinter.print(schema);

        assertTrue(printed.contains(
                "articles(first: Int, after: String, last: Int, before: String, authorId: Int, filter: ArticleFilter, orderBy: [ArticleOrderBy!]): ArticleConnection!"
        ));
        assertTrue(printed.contains("input ArticleFilter {\n"));
        assertTrue(printed.contains("  authorId: IntFilter\n"));
        assertTrue(printed.contains("input ArticleOrderBy {\n"));
        assertTrue(printed.contains("type ArticleConnection {\n"));
        assertTrue(printed.contains("  edges: [ArticleEdge!]!\n"));
        assertTrue(printed.contains("  totalCount: Int!\n"));
        assertTrue(printed.contains("type ArticleEdge {\n"));
        assertTrue(printed.contains("  cursor: String!\n"));
        assertTrue(printed.contains("  node: Article!\n"));
        assertTrue(printed.contains("  hasNextPage: Boolean\n"));
        assertTrue(printed.contains("  hasPreviousPage: Boolean\n"));
        assertTrue(printed.contains("  startCursor: String\n"));
        assertTrue(printed.contains("  endCursor: String\n"));
        assertTrue(printed.contains("limit: Int") == false);
    }

    private static String firstFencedGraphqlBlock(String documentation) {
        String openingFence = "```graphql\n";
        int start = documentation.indexOf(openingFence);
        assertTrue(start >= 0, "docs/generated-schema.md must contain a graphql fenced block");
        int contentStart = start + openingFence.length();
        int end = documentation.indexOf("```", contentStart);
        assertTrue(end >= 0, "docs/generated-schema.md must close the graphql fenced block");
        return documentation.substring(contentStart, end);
    }

    private static String readDemoBlogFixture() throws IOException {
        try (InputStream stream = GraphqlSchemaPrinterTest.class
                .getResourceAsStream("/graphql/demo-blog.titan.graphql.yaml")) {
            if (stream == null) {
                throw new IllegalStateException("demo-blog model fixture is missing");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
