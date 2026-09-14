package io.titan.graphql;

import io.titan.graphql.demo.blog.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GraphqlValidatorSchemaTest {

    @Test
    void rejectsSelectedMutationWhenNoMutationSchemaExists() {
        GraphqlAst.AstOperation operation = GraphqlParser.parseSelectedOperation(GraphqlRequest.of("""
                query Read { article(id: 1) { id } }
                mutation Change { updateArticle(input: { id: 1 }) { article { id } } }
                """, "Change"));

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(DemoBlogGraphqlSchema.create(new GraphqlPolicy()), operation, "writer")
        );

        assertEquals("mutation operation selected but no mutation schema is configured", error.getMessage());
        assertEquals(GraphqlException.UNSUPPORTED_OPERATION, error.code());
    }

    @Test
    void validatesSelectionAgainstAlternateSchema() {
        GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField("profile", "Profile", "id")),
                List.of(new GraphqlObjectType("Profile", List.of(
                        GraphqlFieldDescriptor.scalar("id"),
                        GraphqlFieldDescriptor.scalar("displayName")
                )))
        );

        GraphqlSelection selection = GraphqlValidator.validate(
                schema,
                GraphqlParser.parse("{ profile(id: 7) { id displayName } }"),
                "reader");

        assertEquals("profile", selection.rootFieldName());
        assertEquals("Profile", selection.rootTypeName());
        assertEquals(7L, selection.rootId());
        assertEquals(List.of("id", "displayName"), selection.scalarFieldNames());
    }

    @Test
    void validatesListRootLimitAgainstAlternateSchema() {
        GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField(
                        "profiles",
                        "Profile",
                        "",
                        "limit",
                        GraphqlRootField.ResultCardinality.MANY,
                        25
                )),
                List.of(new GraphqlObjectType("Profile", List.of(
                        GraphqlFieldDescriptor.scalar("id"),
                        GraphqlFieldDescriptor.scalar("displayName")
                )))
        );

        GraphqlSelection selection = GraphqlValidator.validate(
                schema,
                GraphqlParser.parse("{ profiles(limit: 3) { id displayName } }"),
                "reader");

        assertEquals("profiles", selection.rootFieldName());
        assertEquals(GraphqlRootField.ResultCardinality.MANY, selection.rootCardinality());
        assertEquals(3, selection.rootLimit());
        assertEquals(List.of("id", "displayName"), selection.scalarFieldNames());
    }

    @Test
    void validatesListRootLimitAgainstDeclaredRootBound() {
        GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField(
                        "profiles",
                        "profileBySmallPage",
                        "Profile",
                        "",
                        "limit",
                        GraphqlRootField.ResultCardinality.MANY,
                        2,
                        5,
                        List.of()
                )),
                List.of(new GraphqlObjectType("Profile", List.of(
                        GraphqlFieldDescriptor.scalar("id"),
                        GraphqlFieldDescriptor.scalar("displayName")
                )))
        );

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ profiles(limit: 6) { id } }"),
                        "reader"
                )
        );

        assertEquals("argument 'limit' must be between 0 and 5", error.getMessage());
    }

    @Test
    void validatesDescriptorBackedListRootFiltersAgainstAlternateSchema() {
        GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField(
                        "profiles",
                        "Profile",
                        "",
                        "limit",
                        GraphqlRootField.ResultCardinality.MANY,
                        25,
                        List.of(GraphqlRootArgumentDescriptor.intEquals("organizationId", "organization_id"))
                )),
                List.of(new GraphqlObjectType("Profile", List.of(
                        GraphqlFieldDescriptor.scalar("id"),
                        GraphqlFieldDescriptor.scalar("displayName")
                )))
        );

        GraphqlSelection selection = GraphqlValidator.validate(
                schema,
                GraphqlParser.parse("{ profiles(organizationId: 9, limit: 3) { id displayName } }"),
                "reader");

        assertEquals(3, selection.rootLimit());
        assertEquals(1, selection.rootFilters().size());
        assertEquals("organizationId", selection.rootFilters().getFirst().argumentName());
        assertEquals("organization_id", selection.rootFilters().getFirst().columnName());
        assertEquals(9L, selection.rootFilters().getFirst().value());
    }

    @Test
    void rejectsRetrievalThatIsNotAvailableAsDirectRoot() {
        GraphqlSchema schema = ProjectionGraphqlAdapter.adapt(new ProjectionModel(
                List.of(ProjectionRetrieval.point(
                        "profileById",
                        "Profile",
                        "id",
                        new ProjectionRetrieval.ProjectionRetrievalCapabilities(false, true, false, false, false)
                )),
                List.of(new ProjectionType(
                        "Profile",
                        "profiles",
                        "app",
                        "profiles",
                        "id",
                        List.of(ProjectionField.column("id", "id")),
                        List.of()
                ))
        ));

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ profileById(id: 7) { id } }"),
                        "reader"
                )
        );

        assertEquals("root field 'profileById' is not available for direct queries", error.getMessage());
    }

    @Test
    void rejectsFilterArgumentWhenRetrievalCapabilityDoesNotDeclareFilters() {
        GraphqlSchema schema = ProjectionGraphqlAdapter.adapt(new ProjectionModel(
                List.of(ProjectionRetrieval.list(
                        "profiles",
                        "Profile",
                        "limit",
                        10,
                        100,
                        List.of(ProjectionRetrieval.RetrievalArgument.intEquals("organizationId", "organization_id")),
                        new ProjectionRetrieval.ProjectionRetrievalCapabilities(true, false, true, false, false)
                )),
                List.of(new ProjectionType(
                        "Profile",
                        "profiles",
                        "app",
                        "profiles",
                        "id",
                        List.of(ProjectionField.column("id", "id")),
                        List.of()
                ))
        ));

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ profiles(organizationId: 9) { id } }"),
                        "reader"
                )
        );

        assertEquals("root field 'profiles' does not support filter arguments", error.getMessage());
    }

    @Test
    void validatesRelayRootPaginationArgumentsAsMetadata() {
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
        String cursor = GraphqlCursorCodec.encode(GraphqlCursorCodec.payload(
                schema.rootField("articles").cursorOrdering(),
                "1",
                "1"
        ));

        GraphqlSelection selection = GraphqlValidator.validate(
                schema,
                        GraphqlParser.parse("{ articles(first: 3, after: \"" + cursor + "\", authorId: 10) { edges { cursor node { id } } totalCount pageInfo { hasNextPage endCursor } } }"),
                "reader"
        );

        assertEquals("articles", selection.rootFieldName());
        assertEquals(GraphqlRootField.RootPaginationMode.RELAY_CONNECTION, schema.rootField("articles").rootPaginationMode());
        assertEquals(3, selection.rootLimit());
        assertEquals(3, selection.rootPagination().first());
        assertEquals(cursor, selection.rootPagination().after());
        assertEquals("id", selection.rootPagination().afterCursor().orderingName());
        assertEquals("1", selection.rootPagination().afterCursor().value());
        assertEquals("1", selection.rootPagination().afterCursor().tieBreakerValue());
        assertEquals(null, selection.rootPagination().last());
        assertEquals("", selection.rootPagination().before());
        assertEquals(true, selection.rootConnectionSelection().selected());
        assertEquals(true, selection.rootConnectionSelection().edges());
        assertEquals(true, selection.rootConnectionSelection().edgeCursor());
        assertEquals(true, selection.rootConnectionSelection().edgeNode());
        assertEquals(true, selection.rootConnectionSelection().totalCount());
        assertEquals(true, selection.rootConnectionSelection().pageInfo());
        assertEquals(List.of("hasNextPage", "endCursor"), selection.rootConnectionSelection().pageInfoFields());
        assertEquals(List.of("id"), selection.scalarFieldNames());
        assertEquals("author_id", selection.rootFilters().getFirst().columnName());
        assertEquals(10L, selection.rootFilters().getFirst().value());
    }

    @Test
    void validatesGeneratedRootFilterShapeForPlanning() {
        GraphqlSelection selection = GraphqlValidator.validate(
                DemoBlogGraphqlSchema.create(new GraphqlPolicy()),
                GraphqlParser.parse("""
                        {
                          articles(filter: { title: { eq: "Titan" } }) {
                            edges { node { id } }
                          }
                        }
                        """),
                "reader"
        );

        GraphqlSelection.GeneratedRootFilter root = selection.generatedRootFilters().getFirst();
        GraphqlSelection.GeneratedRootFilter title = root.children().getFirst();
        assertEquals(GraphqlSelection.GeneratedRootFilterKind.AND, root.kind());
        assertEquals(GraphqlSelection.GeneratedRootFilterOperator.EQ, title.operator());
        assertEquals("title", title.fieldName());
        assertEquals("title", title.columnName());
        assertEquals("Titan", title.values().getFirst().stringValue());
    }

    @Test
    void validatesGeneratedRootFilterCompositionForPlanning() {
        GraphqlSelection selection = GraphqlValidator.validate(
                DemoBlogGraphqlSchema.create(new GraphqlPolicy()),
                GraphqlParser.parse("""
                        {
                          articles(filter: {
                            title: { in: ["Titan", "GraphQL"] }
                            authorId: { eq: 10 }
                            and: [{ id: { in: [1, 2] } }]
                            not: { title: { isNull: true } }
                          }) {
                            edges { node { id } }
                          }
                        }
                        """),
                "reader"
        );

        GraphqlSelection.GeneratedRootFilter root = selection.generatedRootFilters().getFirst();
        assertEquals(GraphqlSelection.GeneratedRootFilterKind.AND, root.kind());
        assertEquals(4, root.children().size());
        GraphqlSelection.GeneratedRootFilter title = generatedFilterByField(root, "title");
        GraphqlSelection.GeneratedRootFilter authorId = generatedFilterByField(root, "authorId");
        assertEquals(GraphqlSelection.GeneratedRootFilterOperator.IN, title.operator());
        assertEquals(2, title.values().size());
        assertEquals("author_id", authorId.columnName());
        assertEquals(1, generatedFiltersByKind(root, GraphqlSelection.GeneratedRootFilterKind.AND));
        assertEquals(1, generatedFiltersByKind(root, GraphqlSelection.GeneratedRootFilterKind.NOT));
    }

    @Test
    void rejectsUnknownGeneratedRootFilterFields() {
        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        DemoBlogGraphqlSchema.create(new GraphqlPolicy()),
                        GraphqlParser.parse("""
                                {
                                  articles(filter: { missing: { eq: "Titan" } }) {
                                    edges { node { id } }
                                  }
                                }
                                """),
                        "reader"
                )
        );

        assertEquals("unknown field 'missing' on ArticleFilter", error.getMessage());
    }

    @Test
    void validatesGeneratedRootStringFilterOperators() {
        GraphqlSelection selection = GraphqlValidator.validate(
                DemoBlogGraphqlSchema.create(new GraphqlPolicy()),
                GraphqlParser.parse("""
                        {
                          articles(filter: {
                            title: {
                              contains: "Titan"
                              startsWith: "Titan"
                              endsWith: "proof"
                            }
                          }) {
                            edges { node { id } }
                          }
                        }
                        """),
                "reader"
        );

        GraphqlSelection.GeneratedRootFilter title = selection.generatedRootFilters().getFirst().children().getFirst();
        assertEquals(GraphqlSelection.GeneratedRootFilterKind.AND, title.kind());
        List<GraphqlSelection.GeneratedRootFilterOperator> operators = title.children()
                .stream()
                .map(GraphqlSelection.GeneratedRootFilter::operator)
                .toList();
        assertTrue(operators.contains(GraphqlSelection.GeneratedRootFilterOperator.CONTAINS));
        assertTrue(operators.contains(GraphqlSelection.GeneratedRootFilterOperator.STARTS_WITH));
        assertTrue(operators.contains(GraphqlSelection.GeneratedRootFilterOperator.ENDS_WITH));
    }

    @Test
    void validatesGeneratedRootIntComparisonFilterOperators() {
        GraphqlSelection selection = GraphqlValidator.validate(
                DemoBlogGraphqlSchema.create(new GraphqlPolicy()),
                GraphqlParser.parse("""
                        {
                          articles(filter: {
                            id: {
                              lt: 10
                              lte: 2
                              gt: 0
                              gte: 1
                            }
                          }) {
                            edges { node { id } }
                          }
                        }
                        """),
                "reader"
        );

        GraphqlSelection.GeneratedRootFilter id = selection.generatedRootFilters().getFirst().children().getFirst();
        assertEquals(GraphqlSelection.GeneratedRootFilterKind.AND, id.kind());
        List<GraphqlSelection.GeneratedRootFilterOperator> operators = id.children()
                .stream()
                .map(GraphqlSelection.GeneratedRootFilter::operator)
                .toList();
        assertTrue(operators.contains(GraphqlSelection.GeneratedRootFilterOperator.LT));
        assertTrue(operators.contains(GraphqlSelection.GeneratedRootFilterOperator.LTE));
        assertTrue(operators.contains(GraphqlSelection.GeneratedRootFilterOperator.GT));
        assertTrue(operators.contains(GraphqlSelection.GeneratedRootFilterOperator.GTE));
    }

    @Test
    void validatesGeneratedComputedRootFilterOperators() {
        GraphqlSelection selection = GraphqlValidator.validate(
                DemoBlogGraphqlSchema.create(new GraphqlPolicy()),
                GraphqlParser.parse("""
                        {
                          articles(filter: { titleLength: { gte: 19 } }) {
                            edges { node { id titleLength } }
                          }
                        }
                        """),
                "reader"
        );

        GraphqlSelection.GeneratedRootFilter titleLength = selection.generatedRootFilters().getFirst().children().getFirst();
        assertEquals(GraphqlSelection.GeneratedRootFilterOperator.GTE, titleLength.operator());
        assertEquals("titleLength", titleLength.fieldName());
        assertEquals("titleLength", titleLength.columnName());
        assertEquals("Int", titleLength.scalarType());
        assertEquals(19L, titleLength.values().getFirst().intValue());
    }

    @Test
    void rejectsMalformedGeneratedRootFilterOperators() {
        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        DemoBlogGraphqlSchema.create(new GraphqlPolicy()),
                        GraphqlParser.parse("""
                                {
                                  articles(filter: { title: { matches: "Titan" } }) {
                                    edges { node { id } }
                                  }
                                }
                                """),
                        "reader"
                )
        );

        assertEquals("unsupported filter operator 'matches' on field 'title'", error.getMessage());
    }

    @Test
    void rejectsStringOperatorsOnGeneratedIntFilters() {
        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        DemoBlogGraphqlSchema.create(new GraphqlPolicy()),
                        GraphqlParser.parse("""
                                {
                                  articles(filter: { id: { contains: "1" } }) {
                                    edges { node { id } }
                                  }
                                }
                                """),
                        "reader"
                )
        );

        assertEquals("unsupported filter operator 'contains' on field 'id'", error.getMessage());
    }

    @Test
    void rejectsNumericComparisonOperatorsOnGeneratedStringFilters() {
        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        DemoBlogGraphqlSchema.create(new GraphqlPolicy()),
                        GraphqlParser.parse("""
                                {
                                  articles(filter: { title: { gt: 1 } }) {
                                    edges { node { id } }
                                  }
                                }
                                """),
                        "reader"
                )
        );

        assertEquals("unsupported filter operator 'gt' on field 'title'", error.getMessage());
    }

    @Test
    void validatesGeneratedRootOrderByShapeForPlanning() {
        GraphqlSelection selection = GraphqlValidator.validate(
                DemoBlogGraphqlSchema.create(new GraphqlPolicy()),
                GraphqlParser.parse("""
                        {
                          articles(orderBy: [{ title: DESC }, { titleLength: ASC }, { authorName: ASC }]) {
                            edges { node { id } }
                          }
                        }
                        """),
                "reader"
        );

        assertEquals(3, selection.rootOrderBy().size());
        assertEquals("title", selection.rootOrderBy().get(0).name());
        assertEquals(GraphqlRootField.RootCursorDirection.DESC, selection.rootOrderBy().get(0).direction());
        assertEquals("titleLength", selection.rootOrderBy().get(1).name());
        assertEquals("titleLength", selection.rootOrderBy().get(1).sortPath());
        assertEquals(GraphqlRootField.RootCursorDirection.ASC, selection.rootOrderBy().get(1).direction());
        assertEquals("author_id", selection.rootOrderBy().get(2).columnName());
        assertEquals("author.name", selection.rootOrderBy().get(2).sortPath());
        assertEquals(GraphqlRootField.RootCursorDirection.ASC, selection.rootOrderBy().get(2).direction());
    }

    @Test
    void rejectsUnknownGeneratedRootOrderByPaths() {
        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        DemoBlogGraphqlSchema.create(new GraphqlPolicy()),
                        GraphqlParser.parse("""
                                {
                                  articles(orderBy: [{ missing: ASC }]) {
                                    edges { node { id } }
                                  }
                                }
                                """),
                        "reader"
                )
        );

        assertEquals("unknown sort path 'missing' on ArticleOrderBy", error.getMessage());
    }

    @Test
    void rejectsInvalidGeneratedRootOrderByDirections() {
        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        DemoBlogGraphqlSchema.create(new GraphqlPolicy()),
                        GraphqlParser.parse("""
                                {
                                  articles(orderBy: [{ title: SIDEWAYS }]) {
                                    edges { node { id } }
                                  }
                                }
                                """),
                        "reader"
                )
        );

        assertEquals("sort path 'title' must be ASC or DESC", error.getMessage());
    }

    @Test
    void rejectsMalformedRelayRootCursors() {
        GraphqlSchema schema = ProjectionGraphqlAdapter.adapt(new ProjectionModel(
                List.of(ProjectionRetrieval.relayConnection(
                        "articles",
                        "Article",
                        10,
                        100,
                        List.of()
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

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ articles(first: 3, after: \"not-a-cursor\") { edges { node { id } } } }"),
                        "reader"
                )
        );

        assertEquals("invalid Relay cursor", error.getMessage());
    }

    @Test
    void rejectsRelayRootCursorsForDifferentOrdering() {
        GraphqlSchema schema = ProjectionGraphqlAdapter.adapt(new ProjectionModel(
                List.of(ProjectionRetrieval.relayConnection(
                        "articles",
                        "Article",
                        10,
                        100,
                        List.of(),
                        new ProjectionRetrieval.RetrievalCursorOrdering(
                                "publishedAt",
                                "published_at",
                                "publishedAt",
                                ProjectionRetrieval.RetrievalCursorDirection.DESC,
                                "id"
                        )
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
        String cursor = GraphqlCursorCodec.encode(new GraphqlCursorCodec.CursorPayload(
                "id",
                "id",
                GraphqlRootField.RootCursorDirection.ASC,
                "1",
                "id",
                "1"
        ));

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ articles(first: 3, after: \"" + cursor + "\") { edges { node { id } } } }"),
                        "reader"
                )
        );

        assertEquals("Relay cursor does not match root cursor ordering", error.getMessage());
    }

    @Test
    void rejectsDirectNodeFieldsUnderRelayRootConnections() {
        GraphqlSchema schema = ProjectionGraphqlAdapter.adapt(new ProjectionModel(
                List.of(ProjectionRetrieval.relayConnection(
                        "articles",
                        "Article",
                        10,
                        100,
                        List.of()
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

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ articles(first: 3) { id } }"),
                        "reader"
                )
        );

        assertEquals(
                "root field 'articles' returns a Relay connection; select 'edges', 'totalCount', and/or 'pageInfo'",
                error.getMessage()
        );
    }

    @Test
    void rejectsUnsupportedPageInfoFieldsUnderRelayRootConnections() {
        GraphqlSchema schema = ProjectionGraphqlAdapter.adapt(new ProjectionModel(
                List.of(ProjectionRetrieval.relayConnection(
                        "articles",
                        "Article",
                        10,
                        100,
                        List.of()
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

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ articles(first: 3) { pageInfo { totalCount } } }"),
                        "reader"
                )
        );

        assertEquals("unsupported articles.pageInfo field 'totalCount'", error.getMessage());
    }

    @Test
    void rejectsRelayRootArgumentsWhenRootIsNotRelayConnection() {
        GraphqlSchema schema = ProjectionGraphqlAdapter.adapt(new ProjectionModel(
                List.of(ProjectionRetrieval.list(
                        "articles",
                        "Article",
                        "limit",
                        10,
                        List.of()
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

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ articles(first: 3) { id } }"),
                        "reader"
                )
        );

        assertEquals("root field 'articles' does not support Relay pagination arguments", error.getMessage());
    }

    @Test
    void rejectsRelayRootPageSizePastDeclaredMaxLimit() {
        GraphqlSchema schema = ProjectionGraphqlAdapter.adapt(new ProjectionModel(
                List.of(ProjectionRetrieval.relayConnection(
                        "articles",
                        "Article",
                        10,
                        25,
                        List.of()
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

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ articles(first: 26) { id } }"),
                        "reader"
                )
        );

        assertEquals("argument 'first' must be between 0 and 25", error.getMessage());
    }

    @Test
    void rejectsRelayRootPaginationWhenCursorOrderingIsMissing() {
        GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField(
                        "articles",
                        "articles",
                        "Article",
                        "",
                        "",
                        GraphqlRootField.RootRetrievalShape.LIST_QUERY,
                        GraphqlRootField.RootPaginationMode.RELAY_CONNECTION,
                        GraphqlRootField.ResultCardinality.MANY,
                        10,
                        100,
                        List.of(),
                        new GraphqlRootField.RetrievalCapabilities(true, false, true, false, false),
                        new GraphqlRootField.RootCursorOrdering(
                                "",
                                "",
                                "",
                                GraphqlRootField.RootCursorDirection.ASC,
                                ""
                        )
                )),
                List.of(new GraphqlObjectType("Article", List.of(GraphqlFieldDescriptor.scalar("id"))))
        );

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ articles(first: 3) { id } }"),
                        "reader"
                )
        );

        assertEquals("root field 'articles' requires cursor ordering metadata for Relay pagination", error.getMessage());
    }

    @Test
    void rejectsRelationSelectionPastDeclaredHopBudget() {
        GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField("profiles", "Profile", "", "limit", GraphqlRootField.ResultCardinality.MANY, 25)),
                List.of(
                        new GraphqlObjectType("Profile", List.of(
                                GraphqlFieldDescriptor.scalar("id"),
                                GraphqlFieldDescriptor.relation(
                                        "organization",
                                        "Organization",
                                        "organization_id",
                                        "id",
                                        GraphqlFieldDescriptor.RelationCardinality.ONE,
                                        false,
                                        new GraphqlFieldDescriptor.RelationCapabilities(
                                                true,
                                                true,
                                                false,
                                                false,
                                                GraphqlFieldDescriptor.RelationPaginationMode.NONE,
                                                1,
                                                0,
                                                0
                                        )
                                )
                        )),
                        new GraphqlObjectType("Organization", List.of(
                                GraphqlFieldDescriptor.scalar("name"),
                                GraphqlFieldDescriptor.relation(
                                        "owner",
                                        "User",
                                        "owner_id",
                                        "id",
                                        GraphqlFieldDescriptor.RelationCardinality.ONE,
                                        false
                                )
                        )),
                        new GraphqlObjectType("User", List.of(GraphqlFieldDescriptor.scalar("name")))
                )
        );

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ profiles { organization { owner { name } } } }"),
                        "reader"
                )
        );

        assertEquals("Organization.owner exceeds selection hop budget of 1", error.getMessage());
    }

    @Test
    void rejectsRelationThatIsNotSelectable() {
        GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField("profile", "Profile", "id")),
                List.of(
                        new GraphqlObjectType("Profile", List.of(
                                GraphqlFieldDescriptor.relation(
                                        "secretOwner",
                                        "User",
                                        "owner_id",
                                        "id",
                                        GraphqlFieldDescriptor.RelationCardinality.ONE,
                                        false,
                                        GraphqlFieldDescriptor.RelationCapabilities.none()
                                )
                        )),
                        new GraphqlObjectType("User", List.of(GraphqlFieldDescriptor.scalar("name")))
                )
        );

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ profile(id: 1) { secretOwner { name } } }"),
                        "reader"
                )
        );

        assertEquals("Profile.secretOwner is not selectable", error.getMessage());
    }

    @Test
    void rejectsUnsupportedDefaultRelationArguments() {
        GraphqlSchema schema = DemoBlogGraphqlSchema.create(new GraphqlPolicy());

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ articles(first: 1) { edges { node { comments(authorId: 10) { edges { node { id } } } } } } }"),
                        "reader"
                )
        );

        assertEquals(
                "unsupported argument on Article.comments; supported arguments are 'first', 'after', 'last', 'before'",
                error.getMessage()
        );
    }

    @Test
    void rejectsDeclaredRelationFilterWhenCapabilityDoesNotAllowFiltering() {
        GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField("article", "Article", "id")),
                List.of(
                        new GraphqlObjectType("Article", List.of(
                                GraphqlFieldDescriptor.relation(
                                        "comments",
                                        "Comment",
                                        "id",
                                        "article_id",
                                        GraphqlFieldDescriptor.RelationCardinality.MANY,
                                        false,
                                        GraphqlFieldDescriptor.RelationCapabilities.currentDefault(),
                                        List.of(GraphqlRelationArgumentDescriptor.intEquals("authorId", "author_id"))
                                )
                        )),
                        new GraphqlObjectType("Comment", List.of(GraphqlFieldDescriptor.scalar("id")))
                )
        );

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ article(id: 1) { comments(authorId: 10) { id } } }"),
                        "reader"
                )
        );

        assertEquals("Article.comments does not support filter arguments", error.getMessage());
    }

    @Test
    void rejectsRelayRelationArgumentsWhenPaginationModeIsNotRelayConnection() {
        GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField("article", "Article", "id")),
                List.of(
                        new GraphqlObjectType("Article", List.of(
                                GraphqlFieldDescriptor.relation(
                                        "comments",
                                        "Comment",
                                        "id",
                                        "article_id",
                                        GraphqlFieldDescriptor.RelationCardinality.MANY,
                                        false,
                                        GraphqlFieldDescriptor.RelationCapabilities.currentDefault(),
                                        List.of(GraphqlRelationArgumentDescriptor.relayFirst())
                                )
                        )),
                        new GraphqlObjectType("Comment", List.of(GraphqlFieldDescriptor.scalar("id")))
                )
        );

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ article(id: 1) { comments(first: 2) { id } } }"),
                        "reader"
                )
        );

        assertEquals("Article.comments does not support Relay pagination arguments", error.getMessage());
    }

    @Test
    void rejectsDirectNodeFieldsUnderRelayRelationConnections() {
        GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField("article", "Article", "id")),
                List.of(
                        new GraphqlObjectType("Article", List.of(
                                GraphqlFieldDescriptor.relation(
                                        "comments",
                                        "Comment",
                                        "id",
                                        "article_id",
                                        GraphqlFieldDescriptor.RelationCardinality.MANY,
                                        false,
                                        new GraphqlFieldDescriptor.RelationCapabilities(
                                                true,
                                                true,
                                                false,
                                                false,
                                                GraphqlFieldDescriptor.RelationPaginationMode.RELAY_CONNECTION,
                                                2,
                                                0,
                                                0
                                        ),
                                        GraphqlFieldDescriptor.RelationRetrievals.currentDefault("comments"),
                                        List.of(GraphqlRelationArgumentDescriptor.relayFirst()),
                                        List.of(GraphqlFieldDescriptor.RelationSortPath.ascending(
                                                "id",
                                                "id",
                                                "id",
                                                0
                                        ))
                                )
                        )),
                        new GraphqlObjectType("Comment", List.of(GraphqlFieldDescriptor.scalar("id")))
                )
        );

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ article(id: 1) { comments(first: 2) { id } } }"),
                        "reader"
                )
        );

        assertEquals(
                "relation field 'comments' returns a Relay connection; select 'edges', 'totalCount', and/or 'pageInfo'",
                error.getMessage()
        );
    }

    @Test
    void preservesValidatedRelationFilterAndRelayArguments() {
        GraphqlFieldDescriptor.RelationSortPath cursorOrdering = GraphqlFieldDescriptor.RelationSortPath.ascending(
                "id",
                "id",
                "id",
                0
        );
        GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField("article", "Article", "id")),
                List.of(
                        new GraphqlObjectType("Article", List.of(
                                GraphqlFieldDescriptor.relation(
                                        "comments",
                                        "Comment",
                                        "id",
                                        "article_id",
                                        GraphqlFieldDescriptor.RelationCardinality.MANY,
                                        false,
                                        new GraphqlFieldDescriptor.RelationCapabilities(
                                                true,
                                                true,
                                                true,
                                                false,
                                                GraphqlFieldDescriptor.RelationPaginationMode.RELAY_CONNECTION,
                                                2,
                                                1,
                                                0
                                        ),
                                        GraphqlFieldDescriptor.RelationRetrievals.currentDefault("comments"),
                                        List.of(
                                                GraphqlRelationArgumentDescriptor.intEquals("authorId", "author_id"),
                                                GraphqlRelationArgumentDescriptor.relayFirst(),
                                                GraphqlRelationArgumentDescriptor.relayAfter()
                                        ),
                                        List.of(cursorOrdering)
                                )
                        )),
                        new GraphqlObjectType("Comment", List.of(GraphqlFieldDescriptor.scalar("id")))
                )
        );
        String cursor = GraphqlCursorCodec.encode(GraphqlCursorCodec.payload(cursorOrdering, "c1", "1"));

        GraphqlSelection selection = GraphqlValidator.validate(
                schema,
                GraphqlParser.parse("{ article(id: 1) { comments(authorId: 10, first: 2, after: \"" + cursor + "\") { edges { cursor node { id } } pageInfo { hasNextPage startCursor } } } }"),
                "reader"
        );

        GraphqlSelection.FieldSelection comments = selection.relation("comments");
        assertEquals(3, comments.relationArguments().size());
        assertEquals("author_id", comments.relationArguments().get(0).columnName());
        assertEquals("author_id", comments.relationArguments().get(0).filterPath());
        assertEquals(0, comments.relationArguments().get(0).filterHopCount());
        assertEquals(10L, comments.relationArguments().get(0).intValue());
        assertEquals(GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_FIRST, comments.relationArguments().get(1).kind());
        assertEquals(2L, comments.relationArguments().get(1).intValue());
        assertEquals(cursor, comments.relationArguments().get(2).stringValue());
        assertEquals("id", comments.relationArguments().get(2).cursorPayload().orderingName());
        assertEquals("c1", comments.relationArguments().get(2).cursorPayload().value());
        assertEquals(true, comments.relationConnectionSelection().selected());
        assertEquals(true, comments.relationConnectionSelection().edges());
        assertEquals(true, comments.relationConnectionSelection().edgeCursor());
        assertEquals(true, comments.relationConnectionSelection().edgeNode());
        assertEquals(true, comments.relationConnectionSelection().pageInfo());
        assertEquals(List.of("hasNextPage", "startCursor"), comments.relationConnectionSelection().pageInfoFields());
    }

    @Test
    void rejectsRelationRelayCursorsForDifferentOrdering() {
        GraphqlFieldDescriptor.RelationSortPath cursorOrdering = GraphqlFieldDescriptor.RelationSortPath.ascending(
                "createdAt",
                "created_at",
                "createdAt",
                0
        );
        GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField("article", "Article", "id")),
                List.of(
                        new GraphqlObjectType("Article", List.of(
                                GraphqlFieldDescriptor.relation(
                                        "comments",
                                        "Comment",
                                        "id",
                                        "article_id",
                                        GraphqlFieldDescriptor.RelationCardinality.MANY,
                                        false,
                                        new GraphqlFieldDescriptor.RelationCapabilities(
                                                true,
                                                true,
                                                false,
                                                true,
                                                GraphqlFieldDescriptor.RelationPaginationMode.RELAY_CONNECTION,
                                                2,
                                                0,
                                                0
                                        ),
                                        GraphqlFieldDescriptor.RelationRetrievals.currentDefault("comments"),
                                        List.of(
                                                GraphqlRelationArgumentDescriptor.relayFirst(),
                                                GraphqlRelationArgumentDescriptor.relayAfter()
                                        ),
                                        List.of(cursorOrdering)
                                )
                        )),
                        new GraphqlObjectType("Comment", List.of(GraphqlFieldDescriptor.scalar("id")))
                )
        );
        String cursor = GraphqlCursorCodec.encode(new GraphqlCursorCodec.CursorPayload(
                "id",
                "id",
                GraphqlRootField.RootCursorDirection.ASC,
                "1",
                "id",
                "1"
        ));

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ article(id: 1) { comments(first: 2, after: \"" + cursor + "\") { edges { node { id } } } } }"),
                        "reader"
                )
        );

        assertEquals("Relay cursor does not match relation cursor ordering", error.getMessage());
    }

    @Test
    void acceptsTotalCountOnCountCapableRelayRelationConnections() {
        GraphqlSelection selection = GraphqlValidator.validate(
                DemoBlogGraphqlSchema.create(new GraphqlPolicy()),
                GraphqlParser.parse("{ article(id: 1) { comments(first: 2) { totalCount pageInfo { hasNextPage } } } }"),
                "reader"
        );

        GraphqlSelection.FieldSelection comments = selection.relation("comments");
        assertEquals(true, comments.relationConnectionSelection().selected());
        assertEquals(true, comments.relationConnectionSelection().totalCount());
        assertEquals(true, comments.relationConnectionSelection().pageInfo());
    }

    @Test
    void rejectsTotalCountOnRelayRelationConnectionsWithoutCountCapability() {
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
                                                false,
                                                true,
                                                ProjectionRelation.ProjectionRelationPaginationMode.RELAY_CONNECTION,
                                                2,
                                                0,
                                                0
                                        ),
                                        List.of(ProjectionRelation.ProjectionRelationArgument.relayFirst()),
                                        List.of(ProjectionRelation.ProjectionRelationSortPath.ascending("id", "id", "id", 0))
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

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ article(id: 1) { comments(first: 2) { totalCount } } }"),
                        "reader"
                )
        );

        assertEquals("unsupported comments field 'totalCount'", error.getMessage());
    }

    @Test
    void rejectsRelationRelayPagesThatCombineFirstAndLast() {
        GraphqlFieldDescriptor.RelationSortPath cursorOrdering = GraphqlFieldDescriptor.RelationSortPath.ascending(
                "id",
                "id",
                "id",
                0
        );
        GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField("article", "Article", "id")),
                List.of(
                        new GraphqlObjectType("Article", List.of(
                                GraphqlFieldDescriptor.relation(
                                        "comments",
                                        "Comment",
                                        "id",
                                        "article_id",
                                        GraphqlFieldDescriptor.RelationCardinality.MANY,
                                        false,
                                        new GraphqlFieldDescriptor.RelationCapabilities(
                                                true,
                                                true,
                                                false,
                                                true,
                                                GraphqlFieldDescriptor.RelationPaginationMode.RELAY_CONNECTION,
                                                2,
                                                0,
                                                0
                                        ),
                                        GraphqlFieldDescriptor.RelationRetrievals.currentDefault("comments"),
                                        List.of(
                                                GraphqlRelationArgumentDescriptor.relayFirst(),
                                                GraphqlRelationArgumentDescriptor.relayLast()
                                        ),
                                        List.of(cursorOrdering)
                                )
                        )),
                        new GraphqlObjectType("Comment", List.of(GraphqlFieldDescriptor.scalar("id")))
                )
        );

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ article(id: 1) { comments(first: 2, last: 1) { edges { node { id } } } } }"),
                        "reader"
                )
        );

        assertEquals("Article.comments cannot combine 'first' and 'last'", error.getMessage());
    }

    @Test
    void rejectsRelationRelayPageSizePastDeclaredMax() {
        GraphqlFieldDescriptor.RelationSortPath cursorOrdering = GraphqlFieldDescriptor.RelationSortPath.ascending(
                "id",
                "id",
                "id",
                0
        );
        GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField("article", "Article", "id")),
                List.of(
                        new GraphqlObjectType("Article", List.of(
                                GraphqlFieldDescriptor.relation(
                                        "comments",
                                        "Comment",
                                        "id",
                                        "article_id",
                                        GraphqlFieldDescriptor.RelationCardinality.MANY,
                                        false,
                                        new GraphqlFieldDescriptor.RelationCapabilities(
                                                true,
                                                true,
                                                false,
                                                true,
                                                GraphqlFieldDescriptor.RelationPaginationMode.RELAY_CONNECTION,
                                                2,
                                                0,
                                                0,
                                                3
                                        ),
                                        GraphqlFieldDescriptor.RelationRetrievals.currentDefault("comments"),
                                        List.of(GraphqlRelationArgumentDescriptor.relayFirst()),
                                        List.of(cursorOrdering)
                                )
                        )),
                        new GraphqlObjectType("Comment", List.of(GraphqlFieldDescriptor.scalar("id")))
                )
        );

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ article(id: 1) { comments(first: 4) { edges { node { id } } } } }"),
                        "reader"
                )
        );

        assertEquals("argument 'first' must be between 0 and 3", error.getMessage());
    }

    @Test
    void rejectsRelationFilterPastDeclaredFilterHopBudget() {
        GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField("article", "Article", "id")),
                List.of(
                        new GraphqlObjectType("Article", List.of(
                                GraphqlFieldDescriptor.relation(
                                        "comments",
                                        "Comment",
                                        "id",
                                        "article_id",
                                        GraphqlFieldDescriptor.RelationCardinality.MANY,
                                        false,
                                        new GraphqlFieldDescriptor.RelationCapabilities(
                                                true,
                                                true,
                                                true,
                                                false,
                                                GraphqlFieldDescriptor.RelationPaginationMode.NONE,
                                                2,
                                                1,
                                                0
                                        ),
                                        List.of(GraphqlRelationArgumentDescriptor.intEquals(
                                                "authorName",
                                                "author_id",
                                                "author.name",
                                                2
                                        ))
                                )
                        )),
                        new GraphqlObjectType("Comment", List.of(GraphqlFieldDescriptor.scalar("id")))
                )
        );

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ article(id: 1) { comments(authorName: 10) { id } } }"),
                        "reader"
                )
        );

        assertEquals(
                "Article.comments filter argument 'authorName' exceeds filter hop budget of 1",
                error.getMessage()
        );
    }

    @Test
    void rejectsDeclaredSortPathWhenCapabilityDoesNotAllowSorting() {
        GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField("article", "Article", "id")),
                List.of(
                        new GraphqlObjectType("Article", List.of(
                                GraphqlFieldDescriptor.relation(
                                        "comments",
                                        "Comment",
                                        "id",
                                        "article_id",
                                        GraphqlFieldDescriptor.RelationCardinality.MANY,
                                        false,
                                        GraphqlFieldDescriptor.RelationCapabilities.currentDefault(),
                                        GraphqlFieldDescriptor.RelationRetrievals.currentDefault("comments"),
                                        List.of(),
                                        List.of(GraphqlFieldDescriptor.RelationSortPath.ascending(
                                                "authorName",
                                                "author_id",
                                                "author.name",
                                                1
                                        ))
                                )
                        )),
                        new GraphqlObjectType("Comment", List.of(GraphqlFieldDescriptor.scalar("id")))
                )
        );

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ article(id: 1) { comments { id } } }"),
                        "reader"
                )
        );

        assertEquals("Article.comments declares sort paths but does not support sorting", error.getMessage());
    }

    @Test
    void rejectsRelationSortPathPastDeclaredSortHopBudget() {
        GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField("article", "Article", "id")),
                List.of(
                        new GraphqlObjectType("Article", List.of(
                                GraphqlFieldDescriptor.relation(
                                        "comments",
                                        "Comment",
                                        "id",
                                        "article_id",
                                        GraphqlFieldDescriptor.RelationCardinality.MANY,
                                        false,
                                        new GraphqlFieldDescriptor.RelationCapabilities(
                                                true,
                                                true,
                                                false,
                                                true,
                                                GraphqlFieldDescriptor.RelationPaginationMode.NONE,
                                                2,
                                                0,
                                                1
                                        ),
                                        GraphqlFieldDescriptor.RelationRetrievals.currentDefault("comments"),
                                        List.of(),
                                        List.of(GraphqlFieldDescriptor.RelationSortPath.ascending(
                                                "authorName",
                                                "author_id",
                                                "author.name",
                                                2
                                        ))
                                )
                        )),
                        new GraphqlObjectType("Comment", List.of(GraphqlFieldDescriptor.scalar("id")))
                )
        );

        GraphqlException error = assertThrows(
                GraphqlException.class,
                () -> GraphqlValidator.validate(
                        schema,
                        GraphqlParser.parse("{ article(id: 1) { comments { id } } }"),
                        "reader"
                )
        );

        assertEquals("Article.comments sort path 'authorName' exceeds sort hop budget of 1", error.getMessage());
    }

    @Test
    void engineCanExecuteAlternateDataModel() {
        GraphqlExecution execution = GraphqlEngine.execute(
                new ProfileDataModel(),
                new GraphqlJsonWriter(),
                "{ profile(id: 7) { id displayName } }",
                "reader"
        );

        assertEquals("{\"data\":{\"profile\":{\"id\":7,\"displayName\":\"Ada\"}}}", execution.json());
        assertEquals(1, execution.plan().readStepCount());
        assertEquals("profile", execution.plan().readSteps().getFirst().name());
    }

    @Test
    void appliesFieldPoliciesFromSchema() {
        GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField("profile", "Profile", "id")),
                List.of(new GraphqlObjectType("Profile", List.of(
                        GraphqlFieldDescriptor.scalar("id"),
                        GraphqlFieldDescriptor.scalar("secret", role -> "admin".equals(role))
                )))
        );

        GraphqlExecution execution = executeWith(schema, "{ profile(id: 7) { secret } }", "reader");

        assertTrue(execution.json().contains("Profile.secret"));
        assertTrue(execution.json().contains("not authorized"));
    }

    private static GraphqlExecution executeWith(GraphqlSchema schema, String query, String actorRole) {
        try {
            GraphqlSelection selection = GraphqlValidator.validate(schema, GraphqlParser.parse(query), actorRole);
            return new GraphqlExecution(selection.rootFieldName(), new GraphqlPlan());
        } catch (GraphqlException ex) {
            return new GraphqlExecution(GraphqlJsonWriter.error(ex), new GraphqlPlan());
        }
    }

    private static GraphqlSelection.GeneratedRootFilter generatedFilterByField(
            GraphqlSelection.GeneratedRootFilter root,
            String fieldName
    ) {
        for (GraphqlSelection.GeneratedRootFilter child : root.children()) {
            if (child.fieldName().equals(fieldName)) {
                return child;
            }
        }
        return null;
    }

    private static int generatedFiltersByKind(
            GraphqlSelection.GeneratedRootFilter root,
            GraphqlSelection.GeneratedRootFilterKind kind
    ) {
        int count = 0;
        for (GraphqlSelection.GeneratedRootFilter child : root.children()) {
            if (child.kind() == kind) {
                count++;
            }
        }
        return count;
    }

    private static final class ProfileDataModel implements GraphqlDataModel {

        @Override
        public GraphqlSchema schema() {
            return new GraphqlSchema(
                    List.of(new GraphqlRootField("profile", "Profile", "id")),
                    List.of(new GraphqlObjectType("Profile", List.of(
                            GraphqlFieldDescriptor.scalar("id"),
                            GraphqlFieldDescriptor.scalar("displayName")
                    )))
            );
        }

        @Override
        public GraphqlExecution execute(GraphqlSelection selection, GraphqlRequestContext context) {
            GraphqlPlan plan = new GraphqlPlan();
            plan.addReadStep("profile", "SELECT id, display_name FROM profiles WHERE id = " + selection.rootId());
            return new GraphqlExecution("{\"data\":{\"profile\":{\"id\":"
                    + selection.rootId() + ",\"displayName\":\"Ada\"}}}", plan);
        }
    }
}
