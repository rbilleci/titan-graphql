package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraphqlLexerParserTest {

    @Test
    void parsesShorthandQueryDocument() {
        GraphqlAst.AstOperation operation = GraphqlParser.parse("{ article(id: 1) { id } }");

        assertEquals(GraphqlAst.OperationType.QUERY, operation.type());
        assertEquals("", operation.name());
        assertEquals("article", operation.fields().getFirst().name());
    }

    @Test
    void parsesAnonymousArticleQueryWithNestedAuthor() {
        GraphqlAst.AstOperation operation = GraphqlParser.parse("""
                query {
                  article(id: 1) {
                    id
                    title
                    author {
                      id
                      name
                    }
                  }
                }
                """);

        assertEquals("", operation.name());
        assertEquals(GraphqlAst.OperationType.QUERY, operation.type());
        assertEquals(1, operation.fields().size());
        GraphqlAst.Field article = operation.fields().getFirst();
        assertEquals("article", article.name());
        assertEquals(1L, ((GraphqlAst.IntValue) article.arguments().get("id")).value());
        assertEquals(3, article.selections().size());
    }

    @Test
    void parsesNamedSingleQueryOperation() {
        GraphqlAst.AstOperation operation = GraphqlParser.parse("""
                query ArticleById {
                  article(id: 1) {
                    id
                  }
                }
                """);

        assertEquals("ArticleById", operation.name());
        assertEquals(GraphqlAst.OperationType.QUERY, operation.type());
        assertEquals(1, operation.fields().size());
        assertEquals("article", operation.fields().getFirst().name());
    }

    @Test
    void modelsRequestEnvelopeFields() {
        GraphqlRequest request = GraphqlRequest.of("query ArticleById { article(id: 1) { id } }", "ArticleById");

        assertEquals("query ArticleById { article(id: 1) { id } }", request.query());
        assertEquals("ArticleById", request.operationName());
        assertEquals(true, request.variables().isEmpty());
        assertEquals(true, request.extensions().isEmpty());
        assertEquals("ArticleById", GraphqlParser.parse(request).name());
    }

    @Test
    void parsesAndCoercesOperationVariablesFromRequestEnvelope() {
        GraphqlAst.AstOperation operation = GraphqlParser.parse(GraphqlRequest.of("""
                query WithVariables($id: Int!, $cursor: ID!, $title: String!, $ratio: Float!, $published: Boolean!) {
                  article(id: $id, cursor: $cursor, title: $title, ratio: $ratio, published: $published) {
                    id
                  }
                }
                """, "WithVariables", Map.of(
                "id", 2,
                "cursor", "article:2",
                "title", "Titan",
                "ratio", 1.5d,
                "published", true
        )));

        GraphqlAst.Field article = operation.fields().getFirst();
        assertEquals(5, operation.variables().size());
        assertEquals(2L, ((GraphqlAst.IntValue) article.arguments().get("id")).value());
        assertEquals("article:2", ((GraphqlAst.IdValue) article.arguments().get("cursor")).value());
        assertEquals("Titan", ((GraphqlAst.StringValue) article.arguments().get("title")).value());
        assertEquals(1.5d, ((GraphqlAst.FloatValue) article.arguments().get("ratio")).value());
        assertEquals(true, ((GraphqlAst.BooleanValue) article.arguments().get("published")).value());
    }

    @Test
    void appliesDefaultVariableValuesBeforeValidation() {
        GraphqlAst.AstOperation operation = GraphqlParser.parse(GraphqlRequest.of("""
                query WithDefault($id: Int = 2) {
                  article(id: $id) {
                    id
                  }
                }
                """, "WithDefault", Map.of()));

        GraphqlAst.Field article = operation.fields().getFirst();
        assertEquals(2L, ((GraphqlAst.IntValue) article.arguments().get("id")).value());
    }

    @Test
    void keepsIdVariablesDistinctFromInts() {
        GraphqlAst.AstOperation operation = GraphqlParser.parse(GraphqlRequest.of("""
                query WithId($cursor: ID = "comment:100") {
                  article(id: 1, after: $cursor) {
                    id
                  }
                }
                """, "WithId", Map.of()));

        GraphqlAst.Field article = operation.fields().getFirst();
        assertInstanceOf(GraphqlAst.IdValue.class, article.arguments().get("after"));
        assertEquals("comment:100", ((GraphqlAst.IdValue) article.arguments().get("after")).value());
    }

    @Test
    void parsesStructuredInputLiteralValues() {
        GraphqlAst.AstOperation operation = GraphqlParser.parse("""
                query {
                  articles(filter: { title: { eq: "Titan", in: ["Titan", "GraphQL"], isNull: null }, status: PUBLISHED }) {
                    edges { node { id } }
                  }
                }
                """);

        GraphqlAst.Field articles = operation.fields().getFirst();
        GraphqlAst.InputObjectValue filter = assertInstanceOf(
                GraphqlAst.InputObjectValue.class,
                articles.arguments().get("filter")
        );
        GraphqlAst.InputObjectValue title = assertInstanceOf(
                GraphqlAst.InputObjectValue.class,
                filter.fields().get("title")
        );
        GraphqlAst.InputListValue titles = assertInstanceOf(
                GraphqlAst.InputListValue.class,
                title.fields().get("in")
        );
        assertEquals(List.of(
                new GraphqlAst.StringValue("Titan"),
                new GraphqlAst.StringValue("GraphQL")
        ), titles.values());
        assertInstanceOf(GraphqlAst.NullValue.class, title.fields().get("isNull"));
        assertEquals(new GraphqlAst.EnumValue("PUBLISHED"), filter.fields().get("status"));
    }

    @Test
    void resolvesVariablesInsideStructuredInputValues() {
        GraphqlAst.AstOperation operation = GraphqlParser.parse(GraphqlRequest.of("""
                query WithFilter($title: String!) {
                  articles(filter: { title: { eq: $title } }) {
                    edges { node { id } }
                  }
                }
                """, "WithFilter", Map.of("title", "Titan")));

        GraphqlAst.Field articles = operation.fields().getFirst();
        GraphqlAst.InputObjectValue filter = (GraphqlAst.InputObjectValue) articles.arguments().get("filter");
        GraphqlAst.InputObjectValue title = (GraphqlAst.InputObjectValue) filter.fields().get("title");
        assertEquals(new GraphqlAst.StringValue("Titan"), title.fields().get("eq"));
    }

    @Test
    void rejectsDuplicateInputObjectFields() {
        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlParser.parse("""
                        query {
                          articles(filter: { title: { eq: "Titan", eq: "GraphQL" } }) {
                            edges { node { id } }
                          }
                        }
                        """)
        );

        assertEquals("duplicate input object field 'eq' at offset 51", error.getMessage());
    }

    @Test
    void rejectsUnknownSuppliedVariables() {
        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlParser.parse(GraphqlRequest.of("""
                        query WithVariables($id: Int!) {
                          article(id: $id) { id }
                        }
                        """, "WithVariables", Map.of("id", 1, "extra", 2)))
        );

        assertEquals("variable '$extra' is not declared by the selected operation", error.getMessage());
    }

    @Test
    void rejectsUnusedVariableDefinitions() {
        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlParser.parse("""
                        query WithVariables($id: Int!, $unused: Int) {
                          article(id: $id) { id }
                        }
                        """)
        );

        assertEquals("variable '$unused' is never used", error.getMessage());
    }

    @Test
    void rejectsMissingRequiredVariables() {
        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlParser.parse(GraphqlRequest.of("""
                        query WithVariables($id: Int!) {
                          article(id: $id) { id }
                        }
                        """, "WithVariables", Map.of()))
        );

        assertEquals("required variable '$id' is missing", error.getMessage());
    }

    @Test
    void rejectsIncompatibleVariableValues() {
        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlParser.parse(GraphqlRequest.of("""
                        query WithVariables($id: Int!) {
                          article(id: $id) { id }
                        }
                        """, "WithVariables", Map.of("id", "not-an-int")))
        );

        assertEquals("variable '$id' cannot be coerced to 'Int'", error.getMessage());
    }

    @Test
    void rejectsExplicitNullVariableValues() {
        Map<String, Object> variables = new java.util.LinkedHashMap<>();
        variables.put("id", null);

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlParser.parse(GraphqlRequest.of("""
                        query WithVariables($id: Int!) {
                          article(id: $id) { id }
                        }
                        """, "WithVariables", variables))
        );

        assertEquals("variable '$id' cannot be null for scalar type 'Int'", error.getMessage());
    }

    @Test
    void rejectsMissingRequestQuery() {
        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlRequest.query("")
        );

        assertEquals(true, error.getMessage().contains("request field 'query' is required"));
    }

    @Test
    void rejectsMultiOperationDocumentsUntilSelectionIsImplemented() {
        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlParser.parse("""
                        query First { article(id: 1) { id } }
                        query Second { article(id: 2) { id } }
                        """)
        );

        assertEquals("multiple operations require operationName", error.getMessage());
    }

    @Test
    void selectsNamedOperationFromRequestEnvelope() {
        GraphqlAst.AstOperation operation = GraphqlParser.parse(GraphqlRequest.of("""
                query First { article(id: 1) { id } }
                query Second { article(id: 2) { title } }
                """, "Second"));

        assertEquals("Second", operation.name());
        assertEquals("article", operation.fields().getFirst().name());
        assertEquals(2L, ((GraphqlAst.IntValue) operation.fields().getFirst().arguments().get("id")).value());
    }

    @Test
    void rejectsUnknownOperationName() {
        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlParser.parse(GraphqlRequest.of(
                        "query First { article(id: 1) { id } }",
                        "Second"
                ))
        );

        assertEquals("operationName 'Second' was not found", error.getMessage());
    }

    @Test
    void rejectsSelectedUnsupportedOperationType() {
        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlParser.parse(GraphqlRequest.of("""
                        query First { article(id: 1) { id } }
                        mutation Change { article(id: 2) { id } }
                        """, "Change"))
        );

        assertEquals("operation type 'mutation' is not supported", error.getMessage());
    }

    @Test
    void selectsNamedMutationOperationAsFirstClassAst() {
        GraphqlAst.AstOperation operation = GraphqlParser.parseSelectedOperation(GraphqlRequest.of("""
                query Read { article(id: 1) { id } }
                mutation Change($id: ID!) {
                  updateArticle(input: { id: $id, title: "Updated" }) {
                    article { id title }
                  }
                }
                """, "Change", Map.of("id", "42")));

        assertEquals(GraphqlAst.OperationType.MUTATION, operation.type());
        assertEquals("Change", operation.name());
        assertEquals("updateArticle", operation.fields().getFirst().name());
        GraphqlAst.InputObjectValue input = assertInstanceOf(
                GraphqlAst.InputObjectValue.class,
                operation.fields().getFirst().arguments().get("input")
        );
        assertEquals("42", ((GraphqlAst.IdValue) input.fields().get("id")).value());
    }

    @Test
    void selectedMutationKeepsOperationNameValidation() {
        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlParser.parseSelectedOperation(GraphqlRequest.of("""
                        mutation Change { updateArticle(input: { id: 1 }) { article { id } } }
                        mutation Change { updateArticle(input: { id: 2 }) { article { id } } }
                        """, "Change"))
        );

        assertEquals("operationName 'Change' is ambiguous", error.getMessage());
    }

    @Test
    void rejectsAmbiguousOperationName() {
        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlParser.parse(GraphqlRequest.of("""
                        query First { article(id: 1) { id } }
                        query First { article(id: 2) { id } }
                        """, "First"))
        );

        assertEquals("operationName 'First' is ambiguous", error.getMessage());
    }

    @Test
    void rejectsMutationOperationType() {
        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlParser.parse("mutation UpdateArticle { article(id: 1) { id } }")
        );

        assertEquals(true, error.getMessage().contains("operation type 'mutation' is not supported"));
    }

    @Test
    void rejectsSubscriptionOperationType() {
        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlParser.parse("subscription ArticleEvents { article(id: 1) { id } }")
        );

        assertEquals(true, error.getMessage().contains("operation type 'subscription' is not supported"));
    }

    @Test
    void parsesFieldAliasesAsResponseKeys() {
        GraphqlAst.AstOperation operation = GraphqlParser.parse("{ post: article(id: 1) { headline: title } }");

        GraphqlAst.Field root = operation.fields().getFirst();
        assertEquals("article", root.name());
        assertEquals("post", root.responseKey());
        GraphqlAst.Field title = (GraphqlAst.Field) root.selections().getFirst();
        assertEquals("title", title.name());
        assertEquals("headline", title.responseKey());
    }

    @Test
    void parsesNamedAndInlineFragments() {
        GraphqlAst.AstOperation operation = GraphqlParser.parse("""
                query ArticleWithFragments {
                  article(id: 1) {
                    ...ArticleFields
                    ... on Article { byline: title }
                  }
                }

                fragment ArticleFields on Article {
                  headline: title
                  author { name }
                }
                """);

        GraphqlAst.Field article = operation.fields().getFirst();
        assertEquals(2, article.selections().size());
        assertInstanceOf(GraphqlAst.FragmentSpread.class, article.selections().getFirst());
        assertInstanceOf(GraphqlAst.InlineFragment.class, article.selections().get(1));
        assertEquals(1, operation.fragments().size());
        assertEquals("ArticleFields", operation.fragments().getFirst().name());
    }

    @Test
    void parsesDirectivesOnFieldsAndFragments() {
        GraphqlAst.AstOperation operation = GraphqlParser.parse("""
                query ArticleWithDirectives($showAuthor: Boolean = true) {
                  article(id: 1) {
                    title @include(if: true)
                    ...ArticleFields @skip(if: false)
                    ... on Article @include(if: $showAuthor) {
                      author { name }
                    }
                  }
                }

                fragment ArticleFields on Article {
                  id
                }
                """);

        GraphqlAst.Field article = operation.fields().getFirst();
        GraphqlAst.Field title = (GraphqlAst.Field) article.selections().getFirst();
        GraphqlAst.FragmentSpread spread = (GraphqlAst.FragmentSpread) article.selections().get(1);
        GraphqlAst.InlineFragment inline = (GraphqlAst.InlineFragment) article.selections().get(2);

        assertEquals("include", title.directives().getFirst().name());
        assertEquals(true, ((GraphqlAst.BooleanValue) title.directives().getFirst().arguments().get("if")).value());
        assertEquals("skip", spread.directives().getFirst().name());
        assertEquals("include", inline.directives().getFirst().name());
        assertEquals(1, operation.variables().size());
    }
}
