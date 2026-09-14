package io.titan.graphql;

import io.titan.graphql.demo.blog.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class GraphqlReadPlannerTest {

    @Test
    void plansPointRootAndRelationFromDemoBlogDescriptors() {
        GraphqlSchema schema = DemoBlogGraphqlSchema.create(new GraphqlPolicy());
        GraphqlSelection selection = GraphqlValidator.validate(
                schema,
                GraphqlParser.parse("{ article(id: 1) { title author { name } } }"),
                "reader");

        GraphqlReadPlan plan = GraphqlReadPlanner.plan(schema, selection);

        assertEquals("article", plan.rootRead().stepName());
        assertEquals("article", plan.rootRead().retrievalName());
        assertEquals(GraphqlRootField.RootRetrievalShape.POINT_LOOKUP, plan.rootRead().retrievalShape());
        assertEquals(true, plan.rootRead().retrievalCapabilities().directRoot());
        assertEquals(true, plan.rootRead().retrievalCapabilities().supportsKeyArgument());
        assertEquals(false, plan.rootRead().retrievalCapabilities().supportsLimitArgument());
        assertEquals(false, plan.rootRead().retrievalCapabilities().supportsFilterArguments());
        assertEquals("Article", plan.rootRead().typeName());
        assertEquals("public.articles", plan.rootRead().tableName());
        assertEquals(List.of("title", "author_id"), plan.rootRead().columnNames());
        assertEquals(GraphqlRootField.ResultCardinality.ONE, plan.rootRead().cardinality());
        assertEquals("id", plan.rootRead().keyColumnName());
        assertEquals(1L, plan.rootRead().keyValue());

        GraphqlReadPlan.RelationRead author = plan.relationRead("article.author");
        assertEquals("authorByParent", author.retrievalName());
        assertEquals(GraphqlFieldDescriptor.RelationRetrievalShape.BATCH_LOOKUP, author.retrievalShape());
        assertEquals("Article", author.parentTypeName());
        assertEquals("author", author.fieldName());
        assertEquals("User", author.targetTypeName());
        assertEquals("public.users", author.targetTableName());
        assertEquals(List.of("id", "name"), author.targetColumnNames());
        assertEquals("author_id", author.localColumnName());
        assertEquals("id", author.targetColumnName());
        assertEquals(GraphqlFieldDescriptor.RelationCardinality.ONE, author.cardinality());
        assertEquals(true, author.capabilities().selectable());
        assertEquals(true, author.capabilities().batchable());
        assertEquals(false, author.capabilities().supportsFiltering());
        assertEquals(false, author.capabilities().supportsSorting());
        assertEquals(GraphqlFieldDescriptor.RelationPaginationMode.NONE, author.capabilities().paginationMode());
        assertEquals(2, author.capabilities().selectionHopBudget());
        assertEquals(0, author.capabilities().filterHopBudget());
        assertEquals(0, author.capabilities().sortHopBudget());
    }

    @Test
    void plansComputedScalarRequiredColumns() {
        GraphqlSchema schema = DemoBlogGraphqlSchema.create(new GraphqlPolicy());
        GraphqlSelection selection = GraphqlValidator.validate(
                schema,
                GraphqlParser.parse("{ article(id: 1) { titleLength } }"),
                "reader");

        GraphqlReadPlan plan = GraphqlReadPlanner.plan(schema, selection);

        assertEquals(List.of("title"), plan.rootRead().columnNames());
    }

    @Test
    void plansListRootAndBatchedRelationFromDemoBlogDescriptors() {
        GraphqlSchema schema = DemoBlogGraphqlSchema.create(new GraphqlPolicy());
        GraphqlSelection selection = GraphqlValidator.validate(
                schema,
                GraphqlParser.parse("{ articles(authorId: 10, first: 2) { edges { node { title author { name } } } } }"),
                "reader");

        GraphqlReadPlan plan = GraphqlReadPlanner.plan(schema, selection);

        assertEquals("articles", plan.rootRead().stepName());
        assertEquals("articles", plan.rootRead().retrievalName());
        assertEquals(GraphqlRootField.RootRetrievalShape.LIST_QUERY, plan.rootRead().retrievalShape());
        assertEquals(true, plan.rootRead().retrievalCapabilities().directRoot());
        assertEquals(false, plan.rootRead().retrievalCapabilities().supportsKeyArgument());
        assertEquals(false, plan.rootRead().retrievalCapabilities().supportsLimitArgument());
        assertEquals(true, plan.rootRead().retrievalCapabilities().supportsFilterArguments());
        assertEquals("public.articles", plan.rootRead().tableName());
        assertEquals(List.of("title", "author_id", "id", "published"), plan.rootRead().columnNames());
        assertEquals(GraphqlRootField.ResultCardinality.MANY, plan.rootRead().cardinality());
        assertEquals(2, plan.rootRead().limit());
        assertEquals("author_id", plan.rootRead().filters().getFirst().columnName());
        assertEquals(10L, plan.rootRead().filters().getFirst().value());
        assertEquals("articles.author", plan.relationReads().getFirst().stepName());
        assertEquals(List.of("id", "name"), plan.relationReads().getFirst().targetColumnNames());
    }

    @Test
    void plansGeneratedRootFilterAndOrderMetadata() {
        GraphqlSchema schema = DemoBlogGraphqlSchema.create(new GraphqlPolicy());
        GraphqlSelection selection = GraphqlValidator.validate(
                schema,
                GraphqlParser.parse("""
                        {
                          articles(
                            filter: {
                              title: { in: ["Titan", "GraphQL"] }
                              titleLength: { gt: 10 }
                              authorId: { eq: 10 }
                              authorName: { startsWith: "Ada" }
                              not: { id: { isNull: true } }
                            }
                            orderBy: [{ title: DESC }, { titleLength: ASC }, { authorName: ASC }]
                          ) {
                            edges { node { id title } }
                          }
                        }
                        """),
                "reader");

        GraphqlReadPlan plan = GraphqlReadPlanner.plan(schema, selection);

        GraphqlSelection.GeneratedRootFilter rootFilter = plan.rootRead().generatedFilters().getFirst();
        assertEquals(GraphqlSelection.GeneratedRootFilterKind.AND, rootFilter.kind());
        assertEquals(5, rootFilter.children().size());
        GraphqlSelection.GeneratedRootFilter title = generatedFilterByField(rootFilter, "title");
        GraphqlSelection.GeneratedRootFilter titleLength = generatedFilterByField(rootFilter, "titleLength");
        GraphqlSelection.GeneratedRootFilter authorId = generatedFilterByField(rootFilter, "authorId");
        assertEquals(GraphqlSelection.GeneratedRootFilterOperator.IN, title.operator());
        assertEquals("title", title.columnName());
        assertEquals("GraphQL", title.values().get(1).stringValue());
        assertEquals(GraphqlSelection.GeneratedRootFilterOperator.GT, titleLength.operator());
        assertEquals("titleLength", titleLength.columnName());
        assertEquals(10L, titleLength.values().getFirst().intValue());
        assertEquals(GraphqlSelection.GeneratedRootFilterOperator.EQ, authorId.operator());
        assertEquals("author_id", authorId.columnName());
        assertEquals(10L, authorId.values().getFirst().intValue());
        assertEquals("publishedVisibility", plan.rootRead().contextFilters().getFirst().name());
        assertEquals("published", plan.rootRead().contextFilters().getFirst().columnName());
        assertEquals(GraphqlRootField.RootContextFilterValueType.BOOLEAN,
                plan.rootRead().contextFilters().getFirst().valueType());
        assertEquals("articleVisibility", plan.rootRead().contextFilters().getFirst().contextKey());
        assertEquals(GraphqlRootField.RootContextFilterPhase.BEFORE_CLIENT_FILTERS,
                plan.rootRead().contextFilters().getFirst().phase());
        GraphqlSelection.GeneratedRootFilter authorName = generatedFilterByField(rootFilter, "authorName");
        assertEquals(GraphqlSelection.GeneratedRootFilterOperator.STARTS_WITH, authorName.operator());
        assertEquals("author_id", authorName.columnName());
        assertEquals("author.name", authorName.filterPath());
        assertEquals(1, authorName.filterHopCount());
        assertEquals("Ada", authorName.values().getFirst().stringValue());
        GraphqlSelection.GeneratedRootFilter not = generatedFiltersByKind(
                rootFilter,
                GraphqlSelection.GeneratedRootFilterKind.NOT
        ).getFirst();
        assertEquals(GraphqlSelection.GeneratedRootFilterOperator.IS_NULL,
                not.children().getFirst().children().getFirst().operator());
        assertEquals(3, plan.rootRead().orderBy().size());
        assertEquals("title", plan.rootRead().orderBy().get(0).columnName());
        assertEquals(GraphqlRootField.RootCursorDirection.DESC, plan.rootRead().orderBy().get(0).direction());
        assertEquals("titleLength", plan.rootRead().orderBy().get(1).sortPath());
        assertEquals(GraphqlRootField.RootCursorDirection.ASC, plan.rootRead().orderBy().get(1).direction());
        assertEquals("author.name", plan.rootRead().orderBy().get(2).sortPath());
        assertEquals(GraphqlRootField.RootCursorDirection.ASC, plan.rootRead().orderBy().get(2).direction());
    }

    @Test
    void plansOneToManyRelationFromDemoBlogDescriptors() {
        GraphqlSchema schema = DemoBlogGraphqlSchema.create(new GraphqlPolicy());
        GraphqlSelection selection = GraphqlValidator.validate(
                schema,
                GraphqlParser.parse("{ articles(first: 2) { edges { node { title comments(first: 2) { edges { node { body author { name } } } totalCount } } } } }"),
                "reader");

        GraphqlReadPlan plan = GraphqlReadPlanner.plan(schema, selection);

        assertEquals(List.of("title", "id", "published"), plan.rootRead().columnNames());
        GraphqlReadPlan.RelationRead comments = plan.relationRead("articles.comments");
        assertEquals("commentsConnectionPage", comments.retrievalName());
        assertEquals(GraphqlFieldDescriptor.RelationRetrievalShape.RELAY_CONNECTION_PAGE, comments.retrievalShape());
        assertEquals("Comment", comments.targetTypeName());
        assertEquals("public.comments", comments.targetTableName());
        assertEquals(List.of("article_id", "body", "author_id", "id"), comments.targetColumnNames());
        assertEquals("id", comments.localColumnName());
        assertEquals("article_id", comments.targetColumnName());
        assertEquals(GraphqlFieldDescriptor.RelationCardinality.MANY, comments.cardinality());
        assertEquals(true, comments.capabilities().selectable());
        assertEquals(true, comments.capabilities().batchable());
        assertEquals(true, comments.capabilities().supportsTotalCount());
        assertEquals(GraphqlReadPlan.CountStrategy.EXACT_VISIBLE_ROWS, comments.countStrategy());

        GraphqlReadPlan.RelationRead commentAuthor = plan.relationRead("articles.comments.author");
        assertEquals("authorByParent", commentAuthor.retrievalName());
        assertEquals(GraphqlFieldDescriptor.RelationRetrievalShape.BATCH_LOOKUP, commentAuthor.retrievalShape());
        assertEquals("Comment", commentAuthor.parentTypeName());
        assertEquals("author", commentAuthor.fieldName());
        assertEquals("User", commentAuthor.targetTypeName());
        assertEquals("public.users", commentAuthor.targetTableName());
        assertEquals(List.of("id", "name"), commentAuthor.targetColumnNames());
        assertEquals("author_id", commentAuthor.localColumnName());
        assertEquals("id", commentAuthor.targetColumnName());
        assertEquals(GraphqlFieldDescriptor.RelationCardinality.ONE, commentAuthor.cardinality());
        assertEquals(2, commentAuthor.capabilities().selectionHopBudget());
    }

    @Test
    void plansAlternateModelRelationWithoutDemoBlogNames() {
        GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField(
                        "profiles",
                        "Profile",
                        "",
                        "limit",
                        GraphqlRootField.ResultCardinality.MANY,
                        25
                )),
                List.of(
                        new GraphqlObjectType("Profile", "profiles", List.of(
                                GraphqlFieldDescriptor.scalarColumn("displayName", "display_name"),
                                GraphqlFieldDescriptor.relation(
                                        "organization",
                                        "Organization",
                                        "organization_id",
                                        "id",
                                        GraphqlFieldDescriptor.RelationCardinality.ONE,
                                        false
                                )
                        )),
                        new GraphqlObjectType("Organization", "organizations", List.of(
                                GraphqlFieldDescriptor.scalarColumn("name", "org_name")
                        ))
                ),
                List.of(
                        new GraphqlTableDescriptor("profiles", "app", "profiles", "id"),
                        new GraphqlTableDescriptor("organizations", "app", "organizations", "id")
                )
        );
        GraphqlSelection selection = GraphqlValidator.validate(
                schema,
                GraphqlParser.parse("{ profiles(limit: 3) { displayName organization { name } } }"),
                "reader");

        GraphqlReadPlan plan = GraphqlReadPlanner.plan(schema, selection);

        assertEquals("app.profiles", plan.rootRead().tableName());
        assertEquals(List.of("display_name", "organization_id"), plan.rootRead().columnNames());
        assertEquals(3, plan.rootRead().limit());
        GraphqlReadPlan.RelationRead organization = plan.relationRead("profiles.organization");
        assertEquals("app.organizations", organization.targetTableName());
        assertEquals(List.of("id", "org_name"), organization.targetColumnNames());
        assertEquals("organization_id", organization.localColumnName());
        assertEquals("id", organization.targetColumnName());
    }

    @Test
    void plansValidatedRelationArgumentsAsMetadata() {
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
                                                false,
                                                ProjectionRelation.ProjectionRelationPaginationMode.RELAY_CONNECTION,
                                                2,
                                                1,
                                                0
                                        ),
                                        List.of(
                                                ProjectionRelation.ProjectionRelationArgument.intEquals("authorId", "author_id"),
                                                ProjectionRelation.ProjectionRelationArgument.relayFirst()
                                        ),
                                        List.of(ProjectionRelation.ProjectionRelationSortPath.ascending(
                                                "id",
                                                "id",
                                                "id",
                                                0
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
        GraphqlSelection selection = GraphqlValidator.validate(
                schema,
                GraphqlParser.parse("{ article(id: 1) { comments(authorId: 10, first: 2) { edges { cursor node { id } } pageInfo { hasNextPage endCursor } } } }"),
                "reader");

        GraphqlReadPlan plan = GraphqlReadPlanner.plan(schema, selection);

        GraphqlReadPlan.RelationRead comments = plan.relationRead("article.comments");
        assertEquals("commentsConnectionPage", comments.retrievalName());
        assertEquals(GraphqlFieldDescriptor.RelationRetrievalShape.RELAY_CONNECTION_PAGE, comments.retrievalShape());
        assertEquals(2, comments.arguments().size());
        assertEquals("authorId", comments.arguments().get(0).argumentName());
        assertEquals("author_id", comments.arguments().get(0).columnName());
        assertEquals("author_id", comments.arguments().get(0).filterPath());
        assertEquals(0, comments.arguments().get(0).filterHopCount());
        assertEquals(10L, comments.arguments().get(0).intValue());
        assertEquals(GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_FIRST, comments.arguments().get(1).kind());
        assertEquals(2L, comments.arguments().get(1).intValue());
        assertEquals(true, comments.connectionSelection().selected());
        assertEquals(true, comments.connectionSelection().edges());
        assertEquals(true, comments.connectionSelection().edgeCursor());
        assertEquals(true, comments.connectionSelection().edgeNode());
        assertEquals(true, comments.connectionSelection().pageInfo());
        assertEquals(List.of("hasNextPage", "endCursor"), comments.connectionSelection().pageInfoFields());
        assertEquals(GraphqlReadPlan.RelationCursorWindowDirection.FORWARD, comments.cursorWindow().direction());
        assertEquals(">", comments.cursorWindow().afterComparisonOperator());
        assertEquals("<", comments.cursorWindow().beforeComparisonOperator());
        assertEquals(null, comments.cursorWindow().afterCursor());
        assertEquals(null, comments.cursorWindow().beforeCursor());
        assertEquals(2, comments.cursorWindow().requestedRowCount());
        assertEquals(3, comments.cursorWindow().fetchRowCount());
    }

    @Test
    void plansFilteredRelationRetrievalShapeWhenOnlyFilterArgumentsArePresent() {
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
                                                false,
                                                ProjectionRelation.ProjectionRelationPaginationMode.NONE,
                                                2,
                                                1,
                                                0
                                        ),
                                        List.of(ProjectionRelation.ProjectionRelationArgument.intEquals(
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
        GraphqlSelection selection = GraphqlValidator.validate(
                schema,
                GraphqlParser.parse("{ article(id: 1) { comments(authorName: 10) { id } } }"),
                "reader");

        GraphqlReadPlan plan = GraphqlReadPlanner.plan(schema, selection);

        GraphqlReadPlan.RelationRead comments = plan.relationRead("article.comments");
        assertEquals("commentsByParentFilter", comments.retrievalName());
        assertEquals(GraphqlFieldDescriptor.RelationRetrievalShape.FILTERED_BATCH, comments.retrievalShape());
        assertEquals("authorName", comments.arguments().getFirst().argumentName());
        assertEquals("author.name", comments.arguments().getFirst().filterPath());
        assertEquals(1, comments.arguments().getFirst().filterHopCount());
    }

    @Test
    void plansRelayRootPaginationArgumentsAsMetadata() {
        GraphqlSchema schema = ProjectionGraphqlAdapter.adapt(new ProjectionModel(
                List.of(ProjectionRetrieval.relayConnection(
                        "articles",
                        "Article",
                        10,
                        100,
                        List.of(ProjectionRetrieval.RetrievalArgument.intEquals("authorId", "author_id")),
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
        String cursor = GraphqlCursorCodec.encode(GraphqlCursorCodec.payload(
                schema.rootField("articles").cursorOrdering(),
                "2026-05-30T00:00:00Z",
                "5"
        ));
        GraphqlSelection selection = GraphqlValidator.validate(
                schema,
                GraphqlParser.parse("{ articles(first: 5, after: \"" + cursor + "\", authorId: 10) { edges { cursor node { id } } totalCount pageInfo { hasNextPage startCursor } } }"),
                "reader"
        );

        GraphqlReadPlan plan = GraphqlReadPlanner.plan(schema, selection);

        assertEquals("articles", plan.rootRead().retrievalName());
        assertEquals(GraphqlRootField.RootPaginationMode.RELAY_CONNECTION, schema.rootField("articles").rootPaginationMode());
        assertEquals("publishedAt", plan.rootRead().cursorOrdering().name());
        assertEquals("published_at", plan.rootRead().cursorOrdering().columnName());
        assertEquals("publishedAt", plan.rootRead().cursorOrdering().cursorPath());
        assertEquals(GraphqlRootField.RootCursorDirection.DESC, plan.rootRead().cursorOrdering().direction());
        assertEquals("id", plan.rootRead().cursorOrdering().tieBreakerColumnName());
        assertEquals(5, plan.rootRead().limit());
        assertEquals(5, plan.rootRead().pagination().first());
        assertEquals(cursor, plan.rootRead().pagination().after());
        assertEquals("publishedAt", plan.rootRead().pagination().afterCursor().orderingName());
        assertEquals("2026-05-30T00:00:00Z", plan.rootRead().pagination().afterCursor().value());
        assertEquals("5", plan.rootRead().pagination().afterCursor().tieBreakerValue());
        assertEquals(GraphqlReadPlan.RootCursorWindowDirection.FORWARD, plan.rootRead().cursorWindow().direction());
        assertEquals("<", plan.rootRead().cursorWindow().afterComparisonOperator());
        assertEquals(">", plan.rootRead().cursorWindow().beforeComparisonOperator());
        assertEquals("5", plan.rootRead().cursorWindow().afterCursor().tieBreakerValue());
        assertEquals(null, plan.rootRead().cursorWindow().beforeCursor());
        assertEquals(5, plan.rootRead().cursorWindow().requestedRowCount());
        assertEquals(6, plan.rootRead().cursorWindow().fetchRowCount());
        assertEquals(GraphqlReadPlan.CountStrategy.EXACT_VISIBLE_ROWS, plan.rootRead().countStrategy());
        assertEquals(true, plan.rootRead().connectionSelection().selected());
        assertEquals(true, plan.rootRead().connectionSelection().edges());
        assertEquals(true, plan.rootRead().connectionSelection().edgeCursor());
        assertEquals(true, plan.rootRead().connectionSelection().edgeNode());
        assertEquals(true, plan.rootRead().connectionSelection().totalCount());
        assertEquals(true, plan.rootRead().connectionSelection().pageInfo());
        assertEquals(List.of("hasNextPage", "startCursor"), plan.rootRead().connectionSelection().pageInfoFields());
        assertEquals(List.of("id", "published_at"), plan.rootRead().columnNames());
        assertEquals("author_id", plan.rootRead().filters().getFirst().columnName());
        assertEquals(10L, plan.rootRead().filters().getFirst().value());
    }

    @Test
    void plansBackwardRelayRootCursorWindowAsMetadata() {
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
        String cursor = GraphqlCursorCodec.encode(GraphqlCursorCodec.payload(
                schema.rootField("articles").cursorOrdering(),
                "2026-05-30T00:00:00Z",
                "9"
        ));
        GraphqlSelection selection = GraphqlValidator.validate(
                schema,
                GraphqlParser.parse("{ articles(last: 4, before: \"" + cursor + "\") { edges { node { id } } pageInfo { hasPreviousPage } } }"),
                "reader"
        );

        GraphqlReadPlan plan = GraphqlReadPlanner.plan(schema, selection);

        assertEquals(GraphqlReadPlan.RootCursorWindowDirection.BACKWARD, plan.rootRead().cursorWindow().direction());
        assertEquals("<", plan.rootRead().cursorWindow().afterComparisonOperator());
        assertEquals(null, plan.rootRead().cursorWindow().afterCursor());
        assertEquals(">", plan.rootRead().cursorWindow().beforeComparisonOperator());
        assertEquals("9", plan.rootRead().cursorWindow().beforeCursor().tieBreakerValue());
        assertEquals(4, plan.rootRead().cursorWindow().requestedRowCount());
        assertEquals(5, plan.rootRead().cursorWindow().fetchRowCount());
    }

    @Test
    void plansDeclaredRelationSortPathsAsMetadata() {
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
                                                ProjectionRelation.ProjectionRelationPaginationMode.NONE,
                                                2,
                                                0,
                                                1
                                        ),
                                        List.of(),
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
        GraphqlSelection selection = GraphqlValidator.validate(
                schema,
                GraphqlParser.parse("{ article(id: 1) { comments { id } } }"),
                "reader");

        GraphqlReadPlan plan = GraphqlReadPlanner.plan(schema, selection);

        GraphqlFieldDescriptor.RelationSortPath sortPath = plan.relationRead("article.comments")
                .sortPaths()
                .getFirst();
        assertEquals("authorName", sortPath.name());
        assertEquals("author_id", sortPath.columnName());
        assertEquals("author.name", sortPath.sortPath());
        assertEquals(1, sortPath.sortHopCount());
        assertEquals(GraphqlFieldDescriptor.RelationSortDirection.DESC, sortPath.direction());
        assertEquals("id", sortPath.tieBreakerColumnName());
    }

    @Test
    void plansBackwardRelationRelayCursorWindowAsMetadata() {
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
                                        List.of(
                                                ProjectionRelation.ProjectionRelationArgument.relayLast(),
                                                ProjectionRelation.ProjectionRelationArgument.relayBefore()
                                        ),
                                        List.of(ProjectionRelation.ProjectionRelationSortPath.descending(
                                                "createdAt",
                                                "created_at",
                                                "createdAt",
                                                0
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
        GraphqlFieldDescriptor.RelationSortPath ordering = schema.type("Article")
                .field("comments")
                .relationSortPaths()
                .getFirst();
        String cursor = GraphqlCursorCodec.encode(GraphqlCursorCodec.payload(
                ordering,
                "2026-05-30T00:00:00Z",
                "7"
        ));
        GraphqlSelection selection = GraphqlValidator.validate(
                schema,
                GraphqlParser.parse("{ article(id: 1) { comments(last: 4, before: \"" + cursor + "\") { edges { node { id } } pageInfo { hasPreviousPage } } } }"),
                "reader");

        GraphqlReadPlan plan = GraphqlReadPlanner.plan(schema, selection);

        GraphqlReadPlan.RelationRead comments = plan.relationRead("article.comments");
        assertEquals(GraphqlReadPlan.RelationCursorWindowDirection.BACKWARD, comments.cursorWindow().direction());
        assertEquals("<", comments.cursorWindow().afterComparisonOperator());
        assertEquals(null, comments.cursorWindow().afterCursor());
        assertEquals(">", comments.cursorWindow().beforeComparisonOperator());
        assertEquals("7", comments.cursorWindow().beforeCursor().tieBreakerValue());
        assertEquals(4, comments.cursorWindow().requestedRowCount());
        assertEquals(5, comments.cursorWindow().fetchRowCount());
    }

    @Test
    void plansRelationRelayCursorWindowWithDeclaredDefaultPageSize() {
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
                                                0,
                                                3,
                                                25
                                        ),
                                        List.of(ProjectionRelation.ProjectionRelationArgument.relayAfter()),
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
        GraphqlFieldDescriptor.RelationSortPath ordering = schema.type("Article")
                .field("comments")
                .relationSortPaths()
                .getFirst();
        String cursor = GraphqlCursorCodec.encode(GraphqlCursorCodec.payload(ordering, "100", "100"));
        GraphqlSelection selection = GraphqlValidator.validate(
                schema,
                GraphqlParser.parse("{ article(id: 1) { comments(after: \"" + cursor + "\") { edges { node { id } } pageInfo { hasNextPage } } } }"),
                "reader");

        GraphqlReadPlan plan = GraphqlReadPlanner.plan(schema, selection);

        GraphqlReadPlan.RelationRead comments = plan.relationRead("article.comments");
        assertEquals("commentsConnectionPage", comments.retrievalName());
        assertEquals(GraphqlFieldDescriptor.RelationRetrievalShape.RELAY_CONNECTION_PAGE, comments.retrievalShape());
        assertEquals(3, comments.connectionSelection().pageSize());
        assertEquals(3, comments.cursorWindow().requestedRowCount());
        assertEquals(4, comments.cursorWindow().fetchRowCount());
        assertEquals("100", comments.cursorWindow().afterCursor().tieBreakerValue());
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
        throw new AssertionError("missing generated root filter field " + fieldName);
    }

    private static List<GraphqlSelection.GeneratedRootFilter> generatedFiltersByKind(
            GraphqlSelection.GeneratedRootFilter root,
            GraphqlSelection.GeneratedRootFilterKind kind
    ) {
        List<GraphqlSelection.GeneratedRootFilter> matches = new java.util.ArrayList<>();
        for (GraphqlSelection.GeneratedRootFilter child : root.children()) {
            if (child.kind() == kind) {
                matches.add(child);
            }
        }
        return List.copyOf(matches);
    }
}
