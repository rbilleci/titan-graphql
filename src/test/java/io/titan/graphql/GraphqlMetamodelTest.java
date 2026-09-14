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
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphqlMetamodelTest {

    @Test
    void demoBlogSchemaMapsGraphqlTypesOverPhysicalTables() {
        GraphqlSchema schema = DemoBlogGraphqlSchema.create(new GraphqlPolicy());

        GraphqlTableDescriptor articles = schema.table("articles");
        assertEquals("public.articles", articles.qualifiedName());
        assertEquals("id", articles.primaryKeyColumnName());

        GraphqlObjectType article = schema.type("Article");
        assertEquals("articles", article.tableName());
        assertEquals("id", article.field("id").columnName());
        assertEquals("title", article.field("title").columnName());
        assertEquals("Int", article.field("titleLength").graphqlType());
        assertEquals(true, article.field("titleLength").computedExpression().present());
        assertEquals(
                GraphqlFieldDescriptor.ComputedExpressionKind.SQL_TEMPLATE,
                article.field("titleLength").computedExpression().expressionKind()
        );
        assertEquals("length({title})", article.field("titleLength").computedExpression().sqlTemplateOrFunction());
        assertEquals(List.of("title"), article.field("titleLength").computedExpression().requiredColumns());
        assertEquals(true, article.field("titleLength").computedExpression().filterable());
        assertEquals(true, article.field("titleLength").computedExpression().sortable());
        assertEquals(
                List.of(GraphqlFieldDescriptor.ScalarFilterOperator.EQ,
                        GraphqlFieldDescriptor.ScalarFilterOperator.NEQ,
                        GraphqlFieldDescriptor.ScalarFilterOperator.IN,
                        GraphqlFieldDescriptor.ScalarFilterOperator.IS_NULL,
                        GraphqlFieldDescriptor.ScalarFilterOperator.LT,
                        GraphqlFieldDescriptor.ScalarFilterOperator.LTE,
                        GraphqlFieldDescriptor.ScalarFilterOperator.GT,
                        GraphqlFieldDescriptor.ScalarFilterOperator.GTE),
                article.field("id").scalarFilterCapabilities().operators()
        );
        assertEquals(
                article.field("id").scalarFilterCapabilities().operators(),
                article.field("titleLength").scalarFilterCapabilities().operators()
        );
        assertEquals(true, article.field("titleLength").scalarSortCapabilities().sortable());
        assertEquals("titleLength", article.field("titleLength").scalarSortCapabilities().sortPath());
        assertEquals(
                List.of(GraphqlFieldDescriptor.ScalarFilterOperator.EQ,
                        GraphqlFieldDescriptor.ScalarFilterOperator.NEQ,
                        GraphqlFieldDescriptor.ScalarFilterOperator.IN,
                        GraphqlFieldDescriptor.ScalarFilterOperator.IS_NULL,
                        GraphqlFieldDescriptor.ScalarFilterOperator.CONTAINS,
                        GraphqlFieldDescriptor.ScalarFilterOperator.STARTS_WITH,
                        GraphqlFieldDescriptor.ScalarFilterOperator.ENDS_WITH),
                article.field("title").scalarFilterCapabilities().operators()
        );
        assertEquals(true, article.field("title").scalarSortCapabilities().sortable());
        assertEquals(
                GraphqlFieldDescriptor.FieldNullOrdering.NULLS_LAST,
                article.field("title").scalarSortCapabilities().nullOrdering()
        );
    }

    @Test
    void demoBlogProjectionModelIsSourceForGraphqlSchema() {
        ProjectionModel model = DemoBlogGraphqlSchema.projectionModel(new GraphqlPolicy());

        ProjectionType article = model.type("Article");
        assertEquals("articles", article.tableName());
        assertEquals("public", article.schemaName());
        assertEquals("articles", article.physicalTableName());
        assertEquals("id", article.primaryKeyColumnName());
        assertEquals("title", article.field("title").columnName());
        assertEquals("titleLength", article.field("titleLength").columnName());
        assertEquals("Int", article.field("titleLength").graphqlType());
        assertEquals(true, article.field("titleLength").computedExpression().selectable());
        assertEquals(true, article.field("titleLength").computedExpression().filterable());
        assertEquals(true, article.field("titleLength").computedExpression().sortable());
        assertEquals(ProjectionField.ProjectionExpressionKind.SQL_TEMPLATE,
                article.field("titleLength").computedExpression().expressionKind());
        assertEquals(List.of("title"), article.field("titleLength").computedExpression().requiredColumns());
        assertEquals(
                List.of(ProjectionField.FilterOperator.EQ,
                        ProjectionField.FilterOperator.NEQ,
                        ProjectionField.FilterOperator.IN,
                        ProjectionField.FilterOperator.IS_NULL,
                        ProjectionField.FilterOperator.LT,
                        ProjectionField.FilterOperator.LTE,
                        ProjectionField.FilterOperator.GT,
                        ProjectionField.FilterOperator.GTE),
                article.field("id").filterCapabilities().operators()
        );
        assertEquals(
                article.field("id").filterCapabilities().operators(),
                article.field("titleLength").filterCapabilities().operators()
        );
        assertEquals(true, article.field("titleLength").sortCapabilities().sortable());
        assertEquals(
                List.of(ProjectionField.FilterOperator.EQ,
                        ProjectionField.FilterOperator.NEQ,
                        ProjectionField.FilterOperator.IN,
                        ProjectionField.FilterOperator.IS_NULL,
                        ProjectionField.FilterOperator.CONTAINS,
                        ProjectionField.FilterOperator.STARTS_WITH,
                        ProjectionField.FilterOperator.ENDS_WITH),
                article.field("title").filterCapabilities().operators()
        );
        assertEquals(true, article.field("title").sortCapabilities().sortable());
        assertEquals(ProjectionField.ProjectionNullOrdering.NULLS_LAST, article.field("title").sortCapabilities().nullOrdering());

        ProjectionRelation comments = article.relation("comments");
        assertEquals("Comment", comments.targetTypeName());
        assertEquals("id", comments.localColumnName());
        assertEquals("article_id", comments.targetColumnName());
        assertEquals(ProjectionRelation.ProjectionRelationCardinality.MANY, comments.cardinality());
        assertEquals(true, comments.capabilities().selectable());
        assertEquals(true, comments.capabilities().batchable());
        assertEquals(false, comments.capabilities().supportsFiltering());
        assertEquals(true, comments.capabilities().supportsSorting());
        assertEquals(ProjectionRelation.ProjectionRelationPaginationMode.RELAY_CONNECTION, comments.capabilities().paginationMode());
        assertEquals(2, comments.capabilities().selectionHopBudget());
        assertEquals(0, comments.capabilities().filterHopBudget());
        assertEquals(0, comments.capabilities().sortHopBudget());
        assertEquals("commentsByParent", comments.retrievalName(ProjectionRelation.ProjectionRelationRetrievalShape.BATCH_LOOKUP));
        assertEquals(
                "commentsByParentFilter",
                comments.retrievalName(ProjectionRelation.ProjectionRelationRetrievalShape.FILTERED_BATCH)
        );
        assertEquals(
                "commentsConnectionPage",
                comments.retrievalName(ProjectionRelation.ProjectionRelationRetrievalShape.RELAY_CONNECTION_PAGE)
        );
        assertEquals(4, comments.arguments().size());
        assertEquals(ProjectionRelation.ProjectionRelationArgument.ProjectionRelationArgumentKind.RELAY_FIRST, comments.arguments().get(0).kind());
        assertEquals(ProjectionRelation.ProjectionRelationArgument.ProjectionRelationArgumentKind.RELAY_AFTER, comments.arguments().get(1).kind());
        assertEquals(ProjectionRelation.ProjectionRelationArgument.ProjectionRelationArgumentKind.RELAY_LAST, comments.arguments().get(2).kind());
        assertEquals(ProjectionRelation.ProjectionRelationArgument.ProjectionRelationArgumentKind.RELAY_BEFORE, comments.arguments().get(3).kind());
        assertEquals("id", comments.sortPaths().getFirst().columnName());

        ProjectionRetrieval articles = model.retrieval("articles");
        assertEquals(ProjectionRetrieval.RetrievalCardinality.MANY, articles.cardinality());
        assertEquals(ProjectionRetrieval.OperationShape.LIST_QUERY, articles.operationShape());
        assertEquals(ProjectionRetrieval.RetrievalPaginationMode.RELAY_CONNECTION, articles.paginationMode());
        assertEquals("id", articles.cursorOrdering().name());
        assertEquals("id", articles.cursorOrdering().columnName());
        assertEquals("id", articles.cursorOrdering().cursorPath());
        assertEquals(ProjectionRetrieval.RetrievalCursorDirection.ASC, articles.cursorOrdering().direction());
        assertEquals("id", articles.cursorOrdering().tieBreakerColumnName());
        assertEquals("", articles.limitArgumentName());
        assertEquals(10, articles.defaultLimit());
        assertEquals(100, articles.maxLimit());
        assertEquals(true, articles.capabilities().directRoot());
        assertEquals(false, articles.capabilities().supportsKeyArgument());
        assertEquals(false, articles.capabilities().supportsLimitArgument());
        assertEquals(true, articles.capabilities().supportsFilterArguments());
        assertEquals("authorId", articles.arguments().getFirst().name());
        assertEquals("author_id", articles.arguments().getFirst().columnName());
        assertEquals("authorName", articles.filterPaths().getFirst().name());
        assertEquals("author_id", articles.filterPaths().getFirst().columnName());
        assertEquals("author.name", articles.filterPaths().getFirst().filterPath());
        assertEquals(1, articles.filterPaths().getFirst().filterHopCount());
        assertEquals("String", articles.filterPaths().getFirst().scalarType());
        assertEquals("id", articles.sortPaths().get(0).name());
        assertEquals("title", articles.sortPaths().get(1).name());
        assertEquals("titleLength", articles.sortPaths().get(2).name());
        assertEquals("titleLength", articles.sortPaths().get(2).sortPath());
        assertEquals(0, articles.sortPaths().get(2).sortHopCount());
        assertEquals("authorName", articles.sortPaths().get(3).name());
        assertEquals("author.name", articles.sortPaths().get(3).sortPath());
        assertEquals(1, articles.sortPaths().get(3).sortHopCount());
        assertEquals("publishedVisibility", articles.contextFilters().getFirst().name());
        assertEquals("published", articles.contextFilters().getFirst().columnName());
        assertEquals(ProjectionRetrieval.RetrievalContextFilterValueType.BOOLEAN, articles.contextFilters().getFirst().valueType());
        assertEquals("articleVisibility", articles.contextFilters().getFirst().contextKey());
        assertEquals(true, articles.contextFilters().getFirst().failClosed());
        assertEquals(
                ProjectionRetrieval.RetrievalContextFilterPhase.BEFORE_CLIENT_FILTERS,
                articles.contextFilters().getFirst().phase()
        );

        ProjectionRetrieval articleRoot = model.retrieval("article");
        assertEquals(ProjectionRetrieval.OperationShape.POINT_LOOKUP, articleRoot.operationShape());
        assertEquals("id", articleRoot.requiredIdArgumentName());
        assertEquals(true, articleRoot.capabilities().directRoot());
        assertEquals(true, articleRoot.capabilities().supportsKeyArgument());
        assertEquals(false, articleRoot.capabilities().supportsLimitArgument());
        assertEquals(false, articleRoot.capabilities().supportsFilterArguments());
    }

    @Test
    void publicProjectionFacadeCanDeclareNonDemoSampleModel() {
        TitanGraphqlProjectionModel publicModel = TitanGraphqlProjection.model()
                .pointRoot("product", "Product", "id")
                .relayConnectionRoot("products", "Product")
                .pageSize(20, 200)
                .argumentIntEquals("vendorId", "vendor_id")
                .cursorOrderingAscending("id", "id", "id")
                .filterPathString("vendorName", "vendor_id", "vendor.name", 1)
                .sortPathAscending("name", "name", "name", 0)
                .sortPathDescending("vendorName", "vendor_id", "vendor.name", 1)
                .contextFilterBooleanEquals("activeCatalog", "active", "catalogActive")
                .addRoot()
                .type("Product")
                .table("catalog", "products", "products", "id")
                .scalarField("id", "id")
                .scalarField("name", "name")
                .scalarField("costCents", "cost_cents", actorRole -> "admin".equals(actorRole))
                .computedSqlTemplateField("nameLength", "Int", "length({name})", List.of("name"))
                .filterable()
                .sortable()
                .addField()
                .oneRelation("vendor", "Vendor", "vendor_id", "id")
                .addRelation()
                .manyRelation("reviews", "Review", "id", "product_id")
                .relayConnectionWithTotalCount(false, true, 2, 0, 0, 10, 50)
                .relayPaginationArguments()
                .sortPathAscending("id", "id", "id", 0)
                .addRelation()
                .addType()
                .type("Vendor")
                .table("catalog", "vendors", "vendors", "id")
                .scalarField("id", "id")
                .scalarField("name", "name")
                .addType()
                .type("Review")
                .table("catalog", "reviews", "reviews", "id")
                .scalarField("id", "id")
                .scalarField("body", "body")
                .addType()
                .build();

        ProjectionModel model = publicModel.toProjectionModel();
        GraphqlSchema schema = ProjectionGraphqlAdapter.adapt(model);

        assertEquals(List.of("product", "products"), publicModel.rootNames());
        assertEquals(List.of("Product", "Vendor", "Review"), publicModel.typeNames());
        assertEquals("catalog.products", schema.table("products").qualifiedName());
        assertEquals("id", schema.table("products").primaryKeyColumnName());

        GraphqlRootField products = schema.rootField("products");
        assertEquals(GraphqlRootField.RootPaginationMode.RELAY_CONNECTION, products.rootPaginationMode());
        assertEquals(20, products.defaultLimit());
        assertEquals(200, products.maxLimit());
        assertEquals(true, products.retrievalCapabilities().supportsTotalCount());
        assertEquals("vendorId", products.filterArguments().getFirst().name());
        assertEquals("vendor_id", products.filterPaths().getFirst().columnName());
        assertEquals("vendor.name", products.filterPaths().getFirst().filterPath());
        assertEquals("name", products.sortPaths().get(1).name());
        assertEquals(GraphqlRootField.RootCursorDirection.DESC, products.sortPaths().get(2).direction());
        assertEquals("activeCatalog", products.contextFilters().getFirst().name());

        GraphqlObjectType product = schema.type("Product");
        assertEquals("name", product.field("name").columnName());
        assertEquals(true, product.field("nameLength").computedExpression().filterable());
        assertEquals(true, product.field("nameLength").computedExpression().sortable());
        assertEquals(false, product.field("costCents").canRead("reader"));
        assertEquals(true, product.field("costCents").canRead("admin"));

        GraphqlFieldDescriptor reviews = product.field("reviews");
        assertEquals(GraphqlFieldDescriptor.RelationPaginationMode.RELAY_CONNECTION, reviews.relationCapabilities().paginationMode());
        assertEquals(true, reviews.relationCapabilities().supportsTotalCount());
        assertEquals("first", reviews.relationArguments().getFirst().name());
        assertEquals("id", reviews.relationSortPaths().getFirst().name());

        String sdl = publicModel.schemaDefinition();
        assertEquals(true, sdl.contains("type Product"));
        assertEquals(true, sdl.contains("products("));
        assertEquals(true, sdl.contains("vendorId: Int"));
        assertEquals(true, sdl.contains(": ProductConnection!"));
        assertEquals(true, sdl.contains("reviews(first: Int, after: String, last: Int, before: String): ReviewConnection!"));
    }

    @Test
    void projectionModelAdaptsToCurrentGraphqlSchemaContract() {
        GraphqlSchema schema = ProjectionGraphqlAdapter.adapt(
                DemoBlogGraphqlSchema.projectionModel(new GraphqlPolicy())
        );

        GraphqlRootField articles = schema.rootField("articles");
        assertEquals("articles", articles.retrievalName());
        assertEquals(GraphqlRootField.RootRetrievalShape.LIST_QUERY, articles.retrievalShape());
        assertEquals(GraphqlRootField.RootPaginationMode.RELAY_CONNECTION, articles.rootPaginationMode());
        assertEquals(GraphqlRootField.ResultCardinality.MANY, articles.resultCardinality());
        assertEquals("Article", articles.typeName());
        assertEquals("", articles.limitArgumentName());
        assertEquals(100, articles.maxLimit());
        assertEquals(true, articles.retrievalCapabilities().directRoot());
        assertEquals(false, articles.retrievalCapabilities().supportsKeyArgument());
        assertEquals(false, articles.retrievalCapabilities().supportsLimitArgument());
        assertEquals(true, articles.retrievalCapabilities().supportsFilterArguments());
        assertEquals("author_id", articles.filterArgument("authorId").columnName());
        assertEquals("title", schema.type("Article").field("title").scalarSortCapabilities().sortPath());
        assertEquals(true, schema.type("Article").field("titleLength").computedExpression().deterministic());
        assertEquals(false, schema.type("Article").field("titleLength").computedExpression().sensitive());
        assertEquals(true, schema.type("Article").field("titleLength").scalarSortCapabilities().sortable());
        assertEquals(
                GraphqlFieldDescriptor.FieldNullOrdering.NULLS_LAST,
                schema.type("Article").field("title").scalarSortCapabilities().nullOrdering()
        );
        assertEquals("titleLength", articles.sortPaths().get(2).name());
        assertEquals("titleLength", articles.sortPaths().get(2).sortPath());
        assertEquals("authorName", articles.sortPaths().get(3).name());
        assertEquals("author.name", articles.sortPaths().get(3).sortPath());
        assertEquals("publishedVisibility", articles.contextFilters().getFirst().name());
        assertEquals("published", articles.contextFilters().getFirst().columnName());
        assertEquals(GraphqlRootField.RootContextFilterValueType.BOOLEAN, articles.contextFilters().getFirst().valueType());
        assertEquals("articleVisibility", articles.contextFilters().getFirst().contextKey());
        assertEquals(true, articles.contextFilters().getFirst().failClosed());
        assertEquals(
                GraphqlRootField.RootContextFilterPhase.BEFORE_CLIENT_FILTERS,
                articles.contextFilters().getFirst().phase()
        );
        assertEquals("authorName", articles.filterPaths().getFirst().name());
        assertEquals("author.name", articles.filterPaths().getFirst().filterPath());
        assertEquals(1, articles.filterPaths().getFirst().filterHopCount());
        assertEquals("String", articles.filterPaths().getFirst().scalarType());

        GraphqlFieldDescriptor comments = schema.type("Article").field("comments");
        assertEquals(GraphqlFieldDescriptor.FieldKind.RELATION, comments.kind());
        assertEquals("Comment", comments.targetTypeName());
        assertEquals(GraphqlFieldDescriptor.RelationCardinality.MANY, comments.relationCardinality());
        assertEquals(true, comments.relationCapabilities().selectable());
        assertEquals(true, comments.relationCapabilities().batchable());
        assertEquals(false, comments.relationCapabilities().supportsFiltering());
        assertEquals(true, comments.relationCapabilities().supportsSorting());
        assertEquals(
                GraphqlFieldDescriptor.RelationPaginationMode.RELAY_CONNECTION,
                comments.relationCapabilities().paginationMode()
        );
        assertEquals(2, comments.relationCapabilities().selectionHopBudget());
        assertEquals(0, comments.relationCapabilities().filterHopBudget());
        assertEquals(0, comments.relationCapabilities().sortHopBudget());
        assertEquals(
                "commentsByParent",
                comments.relationRetrievalName(GraphqlFieldDescriptor.RelationRetrievalShape.BATCH_LOOKUP)
        );
        assertEquals(
                "commentsByParentFilter",
                comments.relationRetrievalName(GraphqlFieldDescriptor.RelationRetrievalShape.FILTERED_BATCH)
        );
        assertEquals(
                "commentsConnectionPage",
                comments.relationRetrievalName(GraphqlFieldDescriptor.RelationRetrievalShape.RELAY_CONNECTION_PAGE)
        );
        assertEquals(4, comments.relationArguments().size());
        assertEquals(GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_FIRST, comments.relationArgument("first").kind());
        assertEquals(GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_AFTER, comments.relationArgument("after").kind());
        assertEquals(GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_LAST, comments.relationArgument("last").kind());
        assertEquals(GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_BEFORE, comments.relationArgument("before").kind());
        assertEquals("id", comments.relationSortPaths().getFirst().columnName());
        assertEquals("public.comments", schema.table("comments").qualifiedName());
    }

    @Test
    void demoBlogYamlAdaptsToCurrentProjectionModelContract() throws IOException {
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(readDemoBlogFixture());

        ProjectionModel model = TitanGraphqlProjectionModelAdapter.adapt(document, new GraphqlPolicy());

        ProjectionType article = model.type("Article");
        assertEquals("articles", article.tableName());
        assertEquals("public", article.schemaName());
        assertEquals("articles", article.physicalTableName());
        assertEquals("id", article.primaryKeyColumnName());
        assertEquals("titleLength", article.field("titleLength").columnName());
        assertEquals(ProjectionField.ProjectionExpressionKind.SQL_TEMPLATE,
                article.field("titleLength").computedExpression().expressionKind());
        assertEquals("length({title})", article.field("titleLength").computedExpression().sqlTemplateOrFunction());
        assertEquals(List.of("title"), article.field("titleLength").computedExpression().requiredColumns());
        assertEquals(true, article.field("titleLength").computedExpression().filterable());
        assertEquals(true, article.field("titleLength").computedExpression().sortable());

        ProjectionRelation comments = article.relation("comments");
        assertEquals(ProjectionRelation.ProjectionRelationPaginationMode.RELAY_CONNECTION, comments.capabilities().paginationMode());
        assertEquals(true, comments.capabilities().supportsTotalCount());
        assertEquals(4, comments.arguments().size());
        assertEquals(ProjectionRelation.ProjectionRelationArgument.ProjectionRelationArgumentKind.RELAY_LAST, comments.arguments().get(2).kind());
        assertEquals("id", comments.sortPaths().getFirst().sortPath());

        ProjectionRetrieval articles = model.retrieval("articles");
        assertEquals(ProjectionRetrieval.RetrievalPaginationMode.RELAY_CONNECTION, articles.paginationMode());
        assertEquals(true, articles.capabilities().supportsTotalCount());
        assertEquals("authorName", articles.filterPaths().getFirst().name());
        assertEquals("author.name", articles.filterPaths().getFirst().filterPath());
        assertEquals(1, articles.filterPaths().getFirst().filterHopCount());
        assertEquals("titleLength", articles.sortPaths().get(2).name());
        assertEquals("authorName", articles.sortPaths().get(3).name());
        assertEquals(1, articles.sortPaths().get(3).sortHopCount());
        assertEquals("publishedVisibility", articles.contextFilters().getFirst().name());
        assertEquals("articleVisibility", articles.contextFilters().getFirst().contextKey());
        assertEquals(true, model.type("User").field("email").policy().canRead("admin"));
        assertEquals(false, model.type("User").field("email").policy().canRead("reader"));
    }

    @Test
    void pointRootCannotSilentlyTargetAColumnOtherThanTheDeclaredPrimaryKey() throws IOException {
        String yaml = readDemoBlogFixture().replaceFirst(
                "(?s)(operation: point\\s+argument:.*?column:) id",
                "$1 author_id");
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(yaml);

        TitanGraphqlProjectionModelAdapterException failure = assertThrows(
                TitanGraphqlProjectionModelAdapterException.class,
                () -> TitanGraphqlProjectionModelAdapter.adapt(document, new GraphqlPolicy()));

        assertEquals("UNSUPPORTED_POINT_ROOT_KEY", failure.code());
        assertTrue(failure.getMessage().contains("author_id"), failure.getMessage());
        assertTrue(failure.getMessage().contains("primary key column 'id'"), failure.getMessage());
    }

    @Test
    void relationCapabilitiesCanDeclareFutureRelayPaginationWithoutOffsetSemantics() {
        ProjectionRelation comments = ProjectionRelation.many(
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
                        2,
                        1,
                        7,
                        25
                )
        );
        ProjectionModel model = new ProjectionModel(
                List.of(ProjectionRetrieval.point("article", "Article", "id")),
                List.of(
                        new ProjectionType(
                                "Article",
                                "articles",
                                "public",
                                "articles",
                                "id",
                                List.of(ProjectionField.column("id", "id")),
                                List.of(comments)
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
        );

        GraphqlFieldDescriptor adapted = ProjectionGraphqlAdapter.adapt(model)
                .type("Article")
                .field("comments");

        assertEquals(true, adapted.relationCapabilities().selectable());
        assertEquals(true, adapted.relationCapabilities().batchable());
        assertEquals(true, adapted.relationCapabilities().supportsFiltering());
        assertEquals(true, adapted.relationCapabilities().supportsSorting());
        assertEquals(
                GraphqlFieldDescriptor.RelationPaginationMode.RELAY_CONNECTION,
                adapted.relationCapabilities().paginationMode()
        );
        assertEquals(3, adapted.relationCapabilities().selectionHopBudget());
        assertEquals(2, adapted.relationCapabilities().filterHopBudget());
        assertEquals(1, adapted.relationCapabilities().sortHopBudget());
        assertEquals(7, comments.capabilities().defaultPageSize());
        assertEquals(7, adapted.relationCapabilities().defaultPageSize());
        assertEquals(25, comments.capabilities().maxPageSize());
        assertEquals(25, adapted.relationCapabilities().maxPageSize());
        assertEquals(false, adapted.relationCapabilities().supportsTotalCount());
    }

    @Test
    void rootRetrievalsCanDeclareFutureRelayPaginationWithoutOffsetSemantics() {
        ProjectionRetrieval articles = ProjectionRetrieval.relayConnection(
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
        );
        ProjectionModel model = new ProjectionModel(
                List.of(articles),
                List.of(new ProjectionType(
                        "Article",
                        "articles",
                        "public",
                        "articles",
                        "id",
                        List.of(ProjectionField.column("id", "id")),
                        List.of()
                ))
        );

        GraphqlRootField adapted = ProjectionGraphqlAdapter.adapt(model).rootField("articles");

        assertEquals(ProjectionRetrieval.RetrievalPaginationMode.RELAY_CONNECTION, articles.paginationMode());
        assertEquals("", articles.limitArgumentName());
        assertEquals(ProjectionRetrieval.OperationShape.LIST_QUERY, articles.operationShape());
        assertEquals("publishedAt", articles.cursorOrdering().name());
        assertEquals("published_at", articles.cursorOrdering().columnName());
        assertEquals("publishedAt", articles.cursorOrdering().cursorPath());
        assertEquals(ProjectionRetrieval.RetrievalCursorDirection.DESC, articles.cursorOrdering().direction());
        assertEquals("id", articles.cursorOrdering().tieBreakerColumnName());
        assertEquals(GraphqlRootField.RootPaginationMode.RELAY_CONNECTION, adapted.rootPaginationMode());
        assertEquals("publishedAt", adapted.cursorOrdering().name());
        assertEquals("published_at", adapted.cursorOrdering().columnName());
        assertEquals("publishedAt", adapted.cursorOrdering().cursorPath());
        assertEquals(GraphqlRootField.RootCursorDirection.DESC, adapted.cursorOrdering().direction());
        assertEquals("id", adapted.cursorOrdering().tieBreakerColumnName());
        assertEquals("", adapted.limitArgumentName());
        assertEquals("author_id", adapted.filterArgument("authorId").columnName());
    }

    @Test
    void relationArgumentDescriptorsCanDeclareFiltersAndRelayPagination() {
        ProjectionRelation comments = ProjectionRelation.many(
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
                        3,
                        1,
                        0
                ),
                List.of(
                        ProjectionRelation.ProjectionRelationArgument.intEquals("authorName", "author_id", "author.name", 1),
                        ProjectionRelation.ProjectionRelationArgument.relayFirst(),
                        ProjectionRelation.ProjectionRelationArgument.relayAfter()
                )
        );
        ProjectionModel model = new ProjectionModel(
                List.of(ProjectionRetrieval.point("article", "Article", "id")),
                List.of(
                        new ProjectionType(
                                "Article",
                                "articles",
                                "public",
                                "articles",
                                "id",
                                List.of(ProjectionField.column("id", "id")),
                                List.of(comments)
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
        );

        GraphqlFieldDescriptor adapted = ProjectionGraphqlAdapter.adapt(model)
                .type("Article")
                .field("comments");

        assertEquals("author_id", adapted.relationArgument("authorName").columnName());
        assertEquals("author.name", adapted.relationArgument("authorName").filterPath());
        assertEquals(1, adapted.relationArgument("authorName").filterHopCount());
        assertEquals(GraphqlRelationArgumentDescriptor.RelationArgumentKind.INT_EQUALS, adapted.relationArgument("authorName").kind());
        assertEquals(GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_FIRST, adapted.relationArgument("first").kind());
        assertEquals(GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_AFTER, adapted.relationArgument("after").kind());
    }

    @Test
    void relationSortPathDescriptorsCanDeclareStableOrdering() {
        ProjectionRelation comments = ProjectionRelation.many(
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
                        3,
                        0,
                        1
                ),
                List.of(),
                List.of(ProjectionRelation.ProjectionRelationSortPath.ascending(
                        "authorName",
                        "author_id",
                        "author.name",
                        1
                ))
        );
        ProjectionModel model = new ProjectionModel(
                List.of(ProjectionRetrieval.point("article", "Article", "id")),
                List.of(
                        new ProjectionType(
                                "Article",
                                "articles",
                                "public",
                                "articles",
                                "id",
                                List.of(ProjectionField.column("id", "id")),
                                List.of(comments)
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
        );

        GraphqlFieldDescriptor adapted = ProjectionGraphqlAdapter.adapt(model)
                .type("Article")
                .field("comments");

        ProjectionRelation.ProjectionRelationSortPath projectionSort = comments.sortPaths().getFirst();
        assertEquals("authorName", projectionSort.name());
        assertEquals("author_id", projectionSort.columnName());
        assertEquals("author.name", projectionSort.sortPath());
        assertEquals(1, projectionSort.sortHopCount());
        assertEquals(ProjectionRelation.ProjectionRelationSortDirection.ASC, projectionSort.direction());
        assertEquals("id", projectionSort.tieBreakerColumnName());

        GraphqlFieldDescriptor.RelationSortPath adaptedSort = adapted.relationSortPaths().getFirst();
        assertEquals("authorName", adaptedSort.name());
        assertEquals("author_id", adaptedSort.columnName());
        assertEquals("author.name", adaptedSort.sortPath());
        assertEquals(1, adaptedSort.sortHopCount());
        assertEquals(GraphqlFieldDescriptor.RelationSortDirection.ASC, adaptedSort.direction());
        assertEquals("id", adaptedSort.tieBreakerColumnName());
    }

    @Test
    void demoBlogSchemaDescribesAuthorRelationJoin() {
        GraphqlSchema schema = DemoBlogGraphqlSchema.create(new GraphqlPolicy());

        GraphqlFieldDescriptor author = schema.type("Article").field("author");

        assertEquals(GraphqlFieldDescriptor.FieldKind.RELATION, author.kind());
        assertEquals("User", author.targetTypeName());
        assertEquals("author_id", author.localColumnName());
        assertEquals("id", author.targetColumnName());
        assertEquals(GraphqlFieldDescriptor.RelationCardinality.ONE, author.relationCardinality());
    }

    @Test
    void demoBlogSchemaDescribesCommentsRelationJoin() {
        GraphqlSchema schema = DemoBlogGraphqlSchema.create(new GraphqlPolicy());

        GraphqlFieldDescriptor comments = schema.type("Article").field("comments");

        assertEquals(GraphqlFieldDescriptor.FieldKind.RELATION, comments.kind());
        assertEquals("Comment", comments.targetTypeName());
        assertEquals("id", comments.localColumnName());
        assertEquals("article_id", comments.targetColumnName());
        assertEquals(GraphqlFieldDescriptor.RelationCardinality.MANY, comments.relationCardinality());
        assertEquals("comments", schema.type("Comment").tableName());
        assertEquals("body", schema.type("Comment").field("body").columnName());
        assertEquals("public.comments", schema.table("comments").qualifiedName());

        GraphqlFieldDescriptor commentAuthor = schema.type("Comment").field("author");
        assertEquals(GraphqlFieldDescriptor.FieldKind.RELATION, commentAuthor.kind());
        assertEquals("User", commentAuthor.targetTypeName());
        assertEquals("author_id", commentAuthor.localColumnName());
        assertEquals("id", commentAuthor.targetColumnName());
        assertEquals(GraphqlFieldDescriptor.RelationCardinality.ONE, commentAuthor.relationCardinality());
    }

    @Test
    void schemaCanDescribeListRootWithoutChangingCurrentValidatorContract() {
        GraphqlSchema schema = new GraphqlSchema(
                List.of(new GraphqlRootField(
                        "profiles",
                        "Profile",
                        "",
                        "limit",
                        GraphqlRootField.ResultCardinality.MANY,
                        25
                )),
                List.of(new GraphqlObjectType("Profile", "profiles", List.of(
                        GraphqlFieldDescriptor.scalarColumn("id", "profile_id"),
                        GraphqlFieldDescriptor.scalarColumn("displayName", "display_name")
                ))),
                List.of(new GraphqlTableDescriptor("profiles", "app", "profiles", "profile_id"))
        );

        GraphqlRootField root = schema.rootField("profiles");
        assertEquals(GraphqlRootField.ResultCardinality.MANY, root.resultCardinality());
        assertEquals(GraphqlRootField.RootRetrievalShape.LIST_QUERY, root.retrievalShape());
        assertEquals("limit", root.limitArgumentName());
        assertEquals(25, root.defaultLimit());
        assertEquals(100, root.maxLimit());
        assertEquals("display_name", schema.type("Profile").field("displayName").columnName());
        assertEquals("app.profiles", schema.table("profiles").qualifiedName());
    }

    private static String readDemoBlogFixture() throws IOException {
        try (InputStream stream = GraphqlMetamodelTest.class
                .getResourceAsStream("/graphql/demo-blog.titan.graphql.yaml")) {
            if (stream == null) {
                throw new IllegalStateException("demo-blog model fixture is missing");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
