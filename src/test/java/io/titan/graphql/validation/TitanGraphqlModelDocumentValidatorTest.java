package io.titan.graphql.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.model.TitanGraphqlContextFilterDocument;
import io.titan.graphql.model.TitanGraphqlFieldDocument;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelMetadata;
import io.titan.graphql.model.TitanGraphqlPolicyDocument;
import io.titan.graphql.model.TitanGraphqlRelationDocument;
import io.titan.graphql.model.TitanGraphqlRootDocument;
import io.titan.graphql.model.TitanGraphqlTypeDocument;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TitanGraphqlModelDocumentValidatorTest {

    @Test
    void returnsEmptyReportForValidCanonicalDocument() {
        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(validDocument());

        assertTrue(report.valid());
        assertTrue(report.issues().isEmpty());
    }

    @Test
    void reportsDeterministicDuplicateNamesAndUnknownReferences() {
        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(invalidDocument());

        assertEquals(16, report.errorCount());
        assertEquals(List.of(
                TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE
        ), report.issues().stream().map(TitanGraphqlValidationIssue::code).toList());
        assertEquals(List.of(
                "$.roots.articles",
                "$.types.Article",
                "$.policies.adminOnly",
                "$.contextFilters.publishedVisibility",
                "$.roots.articles.contextFilters.missingContext",
                "$.roots.articles.filterPaths.missingField",
                "$.roots.articles.sortPaths.titleLength",
                "$.roots.articles.contextFilters.missingContext",
                "$.roots.articles.filterPaths.missingField",
                "$.roots.articles.sortPaths.titleLength",
                "$.types.Article.fields.title",
                "$.types.Article.relations.author",
                "$.types.Article.fields.titleLength.computed.requiredColumns.missing_column",
                "$.types.Article.fields.secret.policies.missingPolicy",
                "$.types.Article.relations.author",
                "$.types.Article.relations.author.policies.missingRelationPolicy"
        ), report.issues().stream().map(issue -> issue.modelPath().displayPath()).toList());
        assertTrue(report.blocksDeployment());
    }

    @Test
    void rejectsNullDocument() {
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlModelDocumentValidator.validate(null));
    }

    @Test
    void rejectsUnknownRootAndRowPolicies() {
        TitanGraphqlModelDocument valid = validDocument();
        TitanGraphqlRootDocument sourceRoot = valid.roots().getFirst();
        TitanGraphqlRootDocument root = new TitanGraphqlRootDocument(
                sourceRoot.name(), sourceRoot.type(), sourceRoot.operation(), sourceRoot.argument(),
                sourceRoot.pagination(), sourceRoot.arguments(), sourceRoot.filterPaths(),
                sourceRoot.sortPaths(), sourceRoot.contextFilters(), List.of("missingRootPolicy"));
        TitanGraphqlTypeDocument sourceType = valid.types().getFirst();
        TitanGraphqlTypeDocument type = new TitanGraphqlTypeDocument(
                sourceType.name(), sourceType.table(), sourceType.schema(), sourceType.physicalTable(),
                sourceType.primaryKey(), sourceType.fields(), sourceType.relations(),
                List.of("missingRowPolicy"));
        TitanGraphqlModelDocument document = new TitanGraphqlModelDocument(
                valid.apiVersion(), valid.kind(), valid.metadata(), valid.database(), valid.modules(),
                List.of(root), List.of(type, valid.types().get(1)), valid.policies(),
                valid.contextFilters(), valid.artifacts(), valid.deployment());

        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);

        assertEquals(List.of(
                "$.roots.articles.policies.missingRootPolicy",
                "$.types.Article.policies.missingRowPolicy"
        ), report.issues().stream().map(issue -> issue.modelPath().displayPath()).toList());
    }

    @Test
    void validatesTypedAndCompositePointKeyBindingsBeforeDeployment() {
        TitanGraphqlRootDocument.RootDocumentArgument warehouse = pointArgument(
                "warehouse", "String", "warehouse_code", 0);
        TitanGraphqlRootDocument.RootDocumentArgument sku = pointArgument("sku", "String", "sku", 0);
        TitanGraphqlRootDocument validRoot = new TitanGraphqlRootDocument(
                "inventoryItem",
                "InventoryItem",
                TitanGraphqlRootDocument.RootDocumentOperation.POINT,
                null,
                null,
                List.of(warehouse, sku),
                List.of(),
                List.of(),
                List.of()
        );
        TitanGraphqlTypeDocument type = new TitanGraphqlTypeDocument(
                "InventoryItem",
                "inventory_items",
                "",
                "inventory_items",
                "warehouse_code",
                List.of(
                        field("warehouse", "String", "warehouse_code"),
                        field("sku", "String", "sku")
                ),
                List.of()
        );

        assertTrue(TitanGraphqlModelDocumentValidator.validate(pointDocument(validRoot, type)).valid());

        TitanGraphqlRootDocument invalidRoot = new TitanGraphqlRootDocument(
                "inventoryItem",
                "InventoryItem",
                TitanGraphqlRootDocument.RootDocumentOperation.POINT,
                pointArgument("warehouse", "String", "warehouse_code", 0),
                null,
                List.of(pointArgument("warehouse", "Float", "missing", 1)),
                List.of(),
                List.of(),
                List.of()
        );
        TitanGraphqlValidationReport invalid = TitanGraphqlModelDocumentValidator.validate(
                pointDocument(invalidRoot, type));
        assertEquals(1, invalid.errorCount());
        assertEquals(TitanGraphqlValidationIssueCode.INVALID_BINDING, invalid.issues().getFirst().code());
        assertTrue(invalid.blocksDeployment());
    }

    @Test
    void rejectsIncompleteDuplicateAndMismatchedPointKeys() {
        TitanGraphqlTypeDocument type = new TitanGraphqlTypeDocument(
                "ApiClient",
                "api_clients",
                "",
                "api_clients",
                "id",
                List.of(field("id", "UUID", "id"), field("label", "String", "label")),
                List.of()
        );
        TitanGraphqlRootDocument invalidRoot = new TitanGraphqlRootDocument(
                "apiClient",
                "ApiClient",
                TitanGraphqlRootDocument.RootDocumentOperation.POINT,
                null,
                null,
                List.of(
                        pointArgument("id", "String", "id", 0),
                        pointArgument("id", "Float", "id", 1)
                ),
                List.of(),
                List.of(),
                List.of()
        );

        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(
                pointDocument(invalidRoot, type));
        assertEquals(List.of(
                TitanGraphqlValidationIssueCode.INVALID_BINDING,
                TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                TitanGraphqlValidationIssueCode.INVALID_BINDING,
                TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY
        ), report.issues().stream().map(TitanGraphqlValidationIssue::code).toList());

        TitanGraphqlRootDocument emptyRoot = new TitanGraphqlRootDocument(
                "apiClient",
                "ApiClient",
                TitanGraphqlRootDocument.RootDocumentOperation.POINT,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        TitanGraphqlValidationReport empty = TitanGraphqlModelDocumentValidator.validate(
                pointDocument(emptyRoot, type));
        assertEquals(TitanGraphqlValidationIssueCode.MISSING_REQUIRED_FIELD, empty.issues().getFirst().code());
    }

    @Test
    void validatesOneHopRootSortBindingsBeforeDeployment() {
        TitanGraphqlRelationDocument author = relation("author", "User", List.of());
        TitanGraphqlRootDocument validRoot = articlesRoot(
                "Article",
                List.of(),
                List.of(sortPath("authorName", "author_id", "author.name", 1)),
                List.of()
        );
        TitanGraphqlValidationReport valid = TitanGraphqlModelDocumentValidator.validate(
                relationSortDocument(validRoot, author, List.of(
                        field("id", "Int", "id"),
                        field("name", "String", "name")
                ))
        );
        assertTrue(valid.valid());

        TitanGraphqlRootDocument invalidRoot = articlesRoot(
                "Article",
                List.of(),
                List.of(
                        sortPath("authorName", "wrong_author_id", "author.missing", 1),
                        sortPath("deepAuthorName", "author_id", "author.company.name", 2)
                ),
                List.of()
        );
        TitanGraphqlValidationReport invalid = TitanGraphqlModelDocumentValidator.validate(
                relationSortDocument(invalidRoot, author, List.of(
                        field("id", "Int", "id"),
                        field("name", "String", "name")
                ))
        );
        assertEquals(List.of(
                TitanGraphqlValidationIssueCode.INVALID_BINDING,
                TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY
        ), invalid.issues().stream().map(TitanGraphqlValidationIssue::code).toList());
        assertTrue(invalid.blocksDeployment());
    }

    private static TitanGraphqlModelDocument validDocument() {
        return new TitanGraphqlModelDocument(
                TitanGraphqlModelDocument.CURRENT_API_VERSION,
                TitanGraphqlModelDocument.PROJECTION_MODEL_KIND,
                new TitanGraphqlModelMetadata("demo-blog"),
                null,
                List.of(),
                List.of(articlesRoot(
                        "Article",
                        List.of(filterPath("title", "String", "title", "title", 0)),
                        List.of(sortPath("titleLength", "titleLength", "titleLength", 0)),
                        List.of("publishedVisibility")
                )),
                List.of(
                        articleType(
                                List.of(
                                        field("id", "Int", "id"),
                                        field("title", "String", "title"),
                                        computedField("titleLength", true, true, List.of("title")),
                                        new TitanGraphqlFieldDocument(
                                                "secret",
                                                "String",
                                                "secret",
                                                true,
                                                List.of("adminOnly"),
                                                List.of(),
                                                null,
                                                null
                                        )
                                ),
                                List.of(relation("author", "User", List.of("adminOnly")))
                        ),
                        new TitanGraphqlTypeDocument("User", "users", "", "users", "id", List.of(), List.of())
                ),
                List.of(new TitanGraphqlPolicyDocument(
                        "adminOnly",
                        "",
                        TitanGraphqlPolicyDocument.Effect.DENY,
                        List.of("Article.secret"),
                        "adminOnly"
                )),
                List.of(new TitanGraphqlContextFilterDocument(
                        "publishedVisibility",
                        "published",
                        "publishedOnly",
                        TitanGraphqlContextFilterDocument.Operator.BOOLEAN_EQUALS,
                        true,
                        true
                )),
                null,
                null
        );
    }

    private static TitanGraphqlModelDocument invalidDocument() {
        TitanGraphqlRootDocument root = articlesRoot(
                "Article",
                List.of(filterPath("missingField", "String", "missing", "missing", 0)),
                List.of(sortPath("titleLength", "titleLength", "titleLength", 0)),
                List.of("missingContext")
        );
        return new TitanGraphqlModelDocument(
                TitanGraphqlModelDocument.CURRENT_API_VERSION,
                TitanGraphqlModelDocument.PROJECTION_MODEL_KIND,
                new TitanGraphqlModelMetadata("invalid-demo"),
                null,
                List.of(),
                List.of(root, root),
                List.of(
                        articleType(
                                List.of(
                                        field("title", "String", "title"),
                                        field("title", "String", "title2"),
                                        computedField("titleLength", true, false, List.of("missing_column")),
                                        new TitanGraphqlFieldDocument(
                                                "secret",
                                                "String",
                                                "secret",
                                                true,
                                                List.of("missingPolicy"),
                                                List.of(),
                                                null,
                                                null
                                        )
                                ),
                                List.of(
                                        relation("author", "MissingTarget", List.of("missingRelationPolicy")),
                                        relation("author", "User", List.of())
                                )
                        ),
                        articleType(List.of(field("id", "Int", "id")), List.of()),
                        new TitanGraphqlTypeDocument("User", "users", "", "users", "id", List.of(), List.of())
                ),
                List.of(
                        new TitanGraphqlPolicyDocument("adminOnly", "", TitanGraphqlPolicyDocument.Effect.ALLOW, List.of(), ""),
                        new TitanGraphqlPolicyDocument("adminOnly", "", TitanGraphqlPolicyDocument.Effect.ALLOW, List.of(), "")
                ),
                List.of(
                        new TitanGraphqlContextFilterDocument(
                                "publishedVisibility",
                                "published",
                                "publishedOnly",
                                TitanGraphqlContextFilterDocument.Operator.BOOLEAN_EQUALS,
                                true,
                                true
                        ),
                        new TitanGraphqlContextFilterDocument(
                                "publishedVisibility",
                                "published",
                                "publishedOnly",
                                TitanGraphqlContextFilterDocument.Operator.BOOLEAN_EQUALS,
                                true,
                                true
                        )
                ),
                null,
                null
        );
    }

    private static TitanGraphqlRootDocument articlesRoot(
            String type,
            List<TitanGraphqlRootDocument.RootDocumentFilterPath> filterPaths,
            List<TitanGraphqlRootDocument.RootDocumentSortPath> sortPaths,
            List<String> contextFilters
    ) {
        return TitanGraphqlRootDocument.connection(
                "articles",
                type,
                new TitanGraphqlRootDocument.RootDocumentPagination(
                        20,
                        100,
                        TitanGraphqlRootDocument.TotalCountMode.EXACT,
                        new TitanGraphqlRootDocument.Cursor(
                                "id",
                                "id",
                                TitanGraphqlRootDocument.RootDocumentSortDirection.ASC,
                                "id"
                        )
                ),
                List.of(),
                filterPaths,
                sortPaths,
                contextFilters
        );
    }

    private static TitanGraphqlTypeDocument articleType(
            List<TitanGraphqlFieldDocument> fields,
            List<TitanGraphqlRelationDocument> relations
    ) {
        return new TitanGraphqlTypeDocument("Article", "articles", "", "articles", "id", fields, relations);
    }

    private static TitanGraphqlModelDocument pointDocument(
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument type
    ) {
        return new TitanGraphqlModelDocument(
                TitanGraphqlModelDocument.CURRENT_API_VERSION,
                TitanGraphqlModelDocument.PROJECTION_MODEL_KIND,
                new TitanGraphqlModelMetadata("point-keys"),
                null,
                List.of(),
                List.of(root),
                List.of(type),
                List.of(),
                List.of(),
                null,
                null
        );
    }

    private static TitanGraphqlModelDocument relationSortDocument(
            TitanGraphqlRootDocument root,
            TitanGraphqlRelationDocument relation,
            List<TitanGraphqlFieldDocument> targetFields
    ) {
        return new TitanGraphqlModelDocument(
                TitanGraphqlModelDocument.CURRENT_API_VERSION,
                TitanGraphqlModelDocument.PROJECTION_MODEL_KIND,
                new TitanGraphqlModelMetadata("relation-sort"),
                null,
                List.of(),
                List.of(root),
                List.of(
                        articleType(List.of(
                                field("id", "Int", "id"),
                                field("title", "String", "title")
                        ), List.of(relation)),
                        new TitanGraphqlTypeDocument(
                                "User", "users", "", "users", "id", targetFields, List.of())
                ),
                List.of(),
                List.of(),
                null,
                null
        );
    }

    private static TitanGraphqlRootDocument.RootDocumentArgument pointArgument(
            String name,
            String type,
            String column,
            int hops
    ) {
        return new TitanGraphqlRootDocument.RootDocumentArgument(
                name,
                type,
                TitanGraphqlRootDocument.RootDocumentArgumentKind.EQUALS,
                column,
                column,
                hops
        );
    }

    private static TitanGraphqlFieldDocument field(String name, String type, String column) {
        return new TitanGraphqlFieldDocument(name, type, column, false, List.of(), List.of(), null, null);
    }

    private static TitanGraphqlFieldDocument computedField(
            String name,
            boolean filterable,
            boolean sortable,
            List<String> requiredColumns
    ) {
        return new TitanGraphqlFieldDocument(
                name,
                "Int",
                "",
                false,
                List.of(),
                List.of(),
                null,
                new TitanGraphqlFieldDocument.Computed(
                        TitanGraphqlFieldDocument.FieldDocumentExpressionKind.SQL_TEMPLATE,
                        "length(title)",
                        true,
                        filterable,
                        sortable,
                        true,
                        false,
                        requiredColumns,
                        TitanGraphqlFieldDocument.FieldDocumentCostClass.ROW_LOCAL
                )
        );
    }

    private static TitanGraphqlRelationDocument relation(String name, String targetType, List<String> policies) {
        return new TitanGraphqlRelationDocument(
                name,
                targetType,
                "author_id",
                "id",
                TitanGraphqlRelationDocument.RelationDocumentCardinality.ONE,
                false,
                null,
                List.of(),
                List.of(),
                policies
        );
    }

    private static TitanGraphqlRootDocument.RootDocumentFilterPath filterPath(
            String name,
            String type,
            String column,
            String path,
            int hops
    ) {
        return new TitanGraphqlRootDocument.RootDocumentFilterPath(name, type, column, path, hops, List.of());
    }

    private static TitanGraphqlRootDocument.RootDocumentSortPath sortPath(String name, String column, String path, int hops) {
        return new TitanGraphqlRootDocument.RootDocumentSortPath(
                name,
                column,
                path,
                TitanGraphqlRootDocument.RootDocumentSortDirection.ASC,
                TitanGraphqlRootDocument.RootDocumentNullOrdering.LAST,
                "id",
                hops
        );
    }
}
