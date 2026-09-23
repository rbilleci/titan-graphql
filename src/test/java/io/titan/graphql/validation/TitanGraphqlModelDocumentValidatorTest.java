package io.titan.graphql.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.model.TitanGraphqlContextFilterDocument;
import io.titan.graphql.model.TitanGraphqlEnumDocument;
import io.titan.graphql.model.TitanGraphqlFieldDocument;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import io.titan.graphql.model.TitanGraphqlModelMetadata;
import io.titan.graphql.model.TitanGraphqlMutationDocument;
import io.titan.graphql.model.TitanGraphqlPolicyDocument;
import io.titan.graphql.model.TitanGraphqlRelationDocument;
import io.titan.graphql.model.TitanGraphqlRootDocument;
import io.titan.graphql.model.TitanGraphqlTypeDocument;
import java.util.ArrayList;
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
    void rejectsBatchableRelationThatIsNotSelectable() {
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse("""
                apiVersion: titan.graphql/v1alpha1
                kind: ProjectionModel
                metadata: { name: invalid-relation-capabilities }
                roots: {}
                types:
                  Parent:
                    fields:
                      id: { type: Int, column: id }
                    relations:
                      children:
                        target: Child
                        cardinality: many
                        localColumn: id
                        targetColumn: parent_id
                        capabilities:
                          selectable: false
                          batchable: true
                  Child:
                    fields:
                      id: { type: Int, column: id }
                """);

        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);

        assertEquals(List.of(TitanGraphqlValidationIssueCode.INVALID_BINDING),
                report.issues().stream().map(TitanGraphqlValidationIssue::code).toList());
        assertEquals(List.of("$.types.Parent.relations.children.capabilities.batchable"),
                report.issues().stream().map(issue -> issue.modelPath().displayPath()).toList());
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
    void validatesRegisteredDirectiveNamesBehaviorAndLocations() {
        TitanGraphqlModelDocument valid = TitanGraphqlModelDocumentYaml.parse("""
                apiVersion: titan.graphql/v1alpha1
                kind: ProjectionModel
                metadata: { name: directive-model }
                roots: {}
                types: {}
                directives:
                  visible:
                    behavior: includeIf
                    locations: [FIELD, FRAGMENT_SPREAD, INLINE_FRAGMENT]
                """);
        assertTrue(TitanGraphqlModelDocumentValidator.validate(valid).valid());

        TitanGraphqlModelDocument invalid = TitanGraphqlModelDocumentYaml.parse("""
                apiVersion: titan.graphql/v1alpha1
                kind: ProjectionModel
                metadata: { name: invalid-directive-model }
                roots: {}
                types: {}
                directives:
                  include:
                    locations: []
                """);
        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(invalid);
        assertEquals(3, report.errorCount(), report.issues()::toString);
        assertTrue(report.issues().stream().anyMatch(issue -> issue.code()
                == TitanGraphqlValidationIssueCode.DUPLICATE_NAME));
        assertTrue(report.issues().stream().anyMatch(issue -> issue.code()
                == TitanGraphqlValidationIssueCode.MISSING_REQUIRED_FIELD));
    }

    @Test
    void validatesInterfaceUnionAndAbstractPointRootContracts() {
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse("""
                apiVersion: titan.graphql/v1alpha1
                kind: ProjectionModel
                metadata:
                  name: abstract-model
                roots:
                  customer:
                    type: Customer
                    outputType: Node
                    operation: point
                    argument: { name: id, type: Int, column: id }
                  order:
                    type: Order
                    outputType: SearchResult
                    operation: point
                    argument: { name: id, type: Int, column: id }
                  customers:
                    type: Customer
                    outputType: Node
                    operation: connection
                    pagination:
                      mode: relay
                      defaultPageSize: 10
                      maxPageSize: 100
                      totalCount: exact
                      cursor: { path: id, column: id, direction: asc, tieBreaker: id }
                interfaces:
                  Node:
                    fields:
                      id: { type: Int! }
                unions:
                  SearchResult:
                    members: [Customer, Order]
                types:
                  Customer:
                    primaryKey: id
                    interfaces: [Node]
                    fields:
                      id: { type: Int, column: id }
                  Order:
                    primaryKey: id
                    interfaces: [Node]
                    fields:
                      id: { type: Int, column: id }
                """);

        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);

        assertTrue(report.valid(), () -> report.issues().toString());
    }

    @Test
    void rejectsInvalidAbstractTypeBindingsBeforeGeneration() {
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse("""
                apiVersion: titan.graphql/v1alpha1
                kind: ProjectionModel
                metadata:
                  name: invalid-abstract-model
                roots:
                  customers:
                    type: Customer
                    outputType: SearchResult
                    operation: connection
                interfaces:
                  Node:
                    fields:
                      id: { type: ID! }
                unions:
                  SearchResult:
                    members: [Order, Missing]
                types:
                  Customer:
                    interfaces: [Node, Unknown]
                    fields:
                      id: { type: Int, column: id }
                  Order:
                    fields:
                      id: { type: Int, column: id }
                """);

        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);

        assertEquals(List.of(
                TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                TitanGraphqlValidationIssueCode.INVALID_BINDING,
                TitanGraphqlValidationIssueCode.INVALID_BINDING,
                TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE
        ), report.issues().stream().map(TitanGraphqlValidationIssue::code).toList());
        assertEquals(List.of(
                "$.unions.SearchResult.members.Missing",
                "$.roots.customers.outputType",
                "$.types.Customer.interfaces.Node",
                "$.types.Customer.interfaces.Unknown"
        ), report.issues().stream().map(issue -> issue.modelPath().displayPath()).toList());
    }

    @Test
    void rejectsIdStorageOnANonIdField() {
        TitanGraphqlModelDocument valid = validDocument();
        TitanGraphqlTypeDocument sourceType = valid.types().getFirst();
        List<TitanGraphqlFieldDocument> fields = new ArrayList<>(sourceType.fields());
        fields.add(new TitanGraphqlFieldDocument(
                "invalidStorage", "String", "invalid_storage", false, List.of(), List.of(), null, null,
                TitanGraphqlFieldDocument.FieldDocumentIdStorage.STRING));
        TitanGraphqlTypeDocument type = new TitanGraphqlTypeDocument(
                sourceType.name(), sourceType.table(), sourceType.schema(), sourceType.physicalTable(),
                sourceType.primaryKey(), fields, sourceType.relations(), sourceType.policies());
        TitanGraphqlModelDocument document = new TitanGraphqlModelDocument(
                valid.apiVersion(), valid.kind(), valid.metadata(), valid.database(), valid.modules(),
                valid.roots(), List.of(type, valid.types().get(1)), valid.policies(), valid.mutations(), valid.contextFilters(),
                valid.artifacts(), valid.deployment());

        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);

        assertEquals(List.of("$.types.Article.fields.invalidStorage.idStorage"),
                report.issues().stream().map(issue -> issue.modelPath().displayPath()).toList());
        assertTrue(report.blocksDeployment());
    }

    @Test
    void rejectsOutputFieldDeprecationReasonWithoutDeprecation() {
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse("""
                apiVersion: titan.graphql/v1alpha1
                kind: ProjectionModel
                metadata: { name: invalid-field-metadata }
                roots: {}
                types:
                  Customer:
                    fields:
                      id:
                        type: Int
                        column: id
                        deprecationReason: Use nodeId.
                """);

        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);

        assertEquals(List.of(TitanGraphqlValidationIssueCode.INVALID_BINDING),
                report.issues().stream().map(TitanGraphqlValidationIssue::code).toList());
        assertEquals(List.of("$.types.Customer.fields.id.deprecationReason"),
                report.issues().stream().map(issue -> issue.modelPath().displayPath()).toList());
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
    void acceptsDeclaredEnumAsACompositePointKey() {
        TitanGraphqlRootDocument root = new TitanGraphqlRootDocument(
                "customerByIdAndStatus",
                "Customer",
                TitanGraphqlRootDocument.RootDocumentOperation.POINT,
                null,
                null,
                List.of(
                        pointArgument("id", "Int", "id", 0),
                        pointArgument("status", "CustomerStatus", "status", 0)
                ),
                List.of(),
                List.of(),
                List.of()
        );
        TitanGraphqlTypeDocument type = new TitanGraphqlTypeDocument(
                "Customer",
                "customers",
                "",
                "customers",
                "id",
                List.of(field("id", "Int", "id"), field("status", "CustomerStatus", "status")),
                List.of()
        );
        TitanGraphqlModelDocument document = pointDocument(
                root,
                type,
                List.of(new TitanGraphqlEnumDocument("CustomerStatus", List.of("ACTIVE", "INACTIVE")))
        );

        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);

        assertTrue(report.valid(), () -> report.issues().toString());
    }

    @Test
    void validatesDeclaredEnumEqualityBindingsForRootAndRelationConnections() {
        TitanGraphqlRootDocument root = TitanGraphqlRootDocument.connection(
                "customerFeed",
                "Customer",
                new TitanGraphqlRootDocument.RootDocumentPagination(
                        10, 100, TitanGraphqlRootDocument.TotalCountMode.EXACT,
                        new TitanGraphqlRootDocument.Cursor(
                                "id", "id", TitanGraphqlRootDocument.RootDocumentSortDirection.ASC, "id")),
                List.of(pointArgument("status", "CustomerStatus", "status", 0)),
                List.of(), List.of(), List.of());
        TitanGraphqlRelationDocument relation = new TitanGraphqlRelationDocument(
                "orderConnection",
                "Order",
                "id",
                "customer_id",
                TitanGraphqlRelationDocument.RelationDocumentCardinality.MANY,
                false,
                new TitanGraphqlRelationDocument.RelationDocumentPagination(
                        TitanGraphqlRelationDocument.RelationDocumentPaginationMode.RELAY, 10, 100, true),
                List.of(new TitanGraphqlRelationDocument.RelationDocumentArgument(
                        "status", "OrderStatus",
                        TitanGraphqlRelationDocument.RelationDocumentArgumentKind.EQUALS,
                        "status", "status", 0)),
                List.of(), List.of(), 2);
        TitanGraphqlTypeDocument customer = new TitanGraphqlTypeDocument(
                "Customer", "customers", "", "customers", "id",
                List.of(field("id", "Int", "id"), field("status", "CustomerStatus", "status")),
                List.of(relation));
        TitanGraphqlTypeDocument order = new TitanGraphqlTypeDocument(
                "Order", "orders", "", "orders", "id",
                List.of(field("id", "Int", "id"), field("status", "OrderStatus", "status")),
                List.of());
        TitanGraphqlModelDocument document = new TitanGraphqlModelDocument(
                TitanGraphqlModelDocument.CURRENT_API_VERSION,
                TitanGraphqlModelDocument.PROJECTION_MODEL_KIND,
                new TitanGraphqlModelMetadata("enum-connections"),
                null,
                List.of(),
                List.of(root),
                List.of(customer, order),
                List.of(
                        new TitanGraphqlEnumDocument("CustomerStatus", List.of("ACTIVE", "INACTIVE")),
                        new TitanGraphqlEnumDocument("OrderStatus", List.of("OPEN", "CLOSED"))),
                List.of(), List.of(), List.of(), null, null);

        TitanGraphqlValidationReport valid = TitanGraphqlModelDocumentValidator.validate(document);
        assertTrue(valid.valid(), () -> valid.issues().toString());

        TitanGraphqlRootDocument missingFieldRoot = TitanGraphqlRootDocument.connection(
                root.name(), root.type(), root.pagination(),
                List.of(pointArgument("status", "CustomerStatus", "missing_status", 0)),
                List.of(), List.of(), List.of());
        TitanGraphqlModelDocument invalidDocument = new TitanGraphqlModelDocument(
                document.apiVersion(), document.kind(), document.metadata(), document.database(), document.modules(),
                List.of(missingFieldRoot), document.types(), document.enums(), document.policies(),
                document.mutations(), document.contextFilters(), document.artifacts(), document.deployment());
        TitanGraphqlValidationReport invalid = TitanGraphqlModelDocumentValidator.validate(invalidDocument);
        assertEquals(List.of(TitanGraphqlValidationIssueCode.INVALID_BINDING),
                invalid.issues().stream().map(TitanGraphqlValidationIssue::code).toList());
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

    @Test
    void rejectsNullableCursorAndSortBindingsBeforeGeneration() {
        TitanGraphqlFieldDocument nullableTitle = new TitanGraphqlFieldDocument(
                "title", "String", "title", true, List.of(), List.of(), null, null);
        TitanGraphqlTypeDocument type = articleType(List.of(
                field("id", "Int", "id"), nullableTitle), List.of());
        TitanGraphqlRootDocument nullableSortRoot = articlesRoot(
                "Article", List.of(), List.of(sortPath("title", "title", "title", 0)), List.of());
        TitanGraphqlValidationReport nullableSort = TitanGraphqlModelDocumentValidator.validate(
                pointDocument(nullableSortRoot, type));

        assertEquals(1, nullableSort.errorCount());
        assertEquals(TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                nullableSort.issues().getFirst().code());
        assertTrue(nullableSort.issues().getFirst().message().contains("must bind a non-null scalar"));

        TitanGraphqlRootDocument nullableCursorRoot = TitanGraphqlRootDocument.connection(
                "articles",
                "Article",
                new TitanGraphqlRootDocument.RootDocumentPagination(
                        20,
                        100,
                        TitanGraphqlRootDocument.TotalCountMode.EXACT,
                        new TitanGraphqlRootDocument.Cursor(
                                "title", "title",
                                TitanGraphqlRootDocument.RootDocumentSortDirection.ASC, "id")
                ),
                List.of(), List.of(), List.of(), List.of());
        TitanGraphqlValidationReport nullableCursor = TitanGraphqlModelDocumentValidator.validate(
                pointDocument(nullableCursorRoot, type));

        assertEquals(1, nullableCursor.errorCount());
        assertTrue(nullableCursor.issues().getFirst().message().contains("cursor field 'title' must be non-null"));
    }

    @Test
    void acceptsDeclaredOutputEnumAndRejectsUnknownFieldType() {
        TitanGraphqlFieldDocument filterableEnum = new TitanGraphqlFieldDocument(
                "status", "CustomerStatus", "status", false, List.of(),
                List.of("eq", "neq", "in", "isNull"), null, null);
        TitanGraphqlValidationReport valid = TitanGraphqlModelDocumentValidator.validate(enumDocument(
                List.of(new TitanGraphqlEnumDocument("CustomerStatus", List.of("ACTIVE", "INACTIVE"))),
                filterableEnum
        ));
        TitanGraphqlValidationReport unknown = TitanGraphqlModelDocumentValidator.validate(enumDocument(
                List.of(), field("status", "MissingStatus", "status")
        ));

        assertTrue(valid.valid(), () -> valid.issues().toString());
        assertEquals(List.of(TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE),
                unknown.issues().stream().map(TitanGraphqlValidationIssue::code).toList());
        assertEquals("$.types.Customer.fields.status.type",
                unknown.issues().getFirst().modelPath().displayPath());
    }

    @Test
    void acceptsDeclaredEnumAsAnExplicitMutationInput() {
        TitanGraphqlMutationDocument mutation = new TitanGraphqlMutationDocument(
                "setCustomerStatus",
                TitanGraphqlMutationDocument.MutationDocumentOperation.UPDATE,
                "Customer",
                List.of(
                        new TitanGraphqlMutationDocument.MutationDocumentArgument("id", "Int", "id", true),
                        new TitanGraphqlMutationDocument.MutationDocumentArgument(
                                "status", "CustomerStatus", "status", false)
                ),
                List.of(),
                List.of(
                        new TitanGraphqlMutationDocument.MutationDocumentPayloadField("id", "id"),
                        new TitanGraphqlMutationDocument.MutationDocumentPayloadField("status", "status")
                )
        );
        TitanGraphqlModelDocument document = new TitanGraphqlModelDocument(
                TitanGraphqlModelDocument.CURRENT_API_VERSION,
                TitanGraphqlModelDocument.PROJECTION_MODEL_KIND,
                new TitanGraphqlModelMetadata("enum-mutation-model"),
                null,
                List.of(),
                List.of(),
                List.of(new TitanGraphqlTypeDocument(
                        "Customer", "customers", "", "customers", "id",
                        List.of(field("id", "Int", "id"), field("status", "CustomerStatus", "status")),
                        List.of())),
                List.of(new TitanGraphqlEnumDocument("CustomerStatus", List.of("ACTIVE", "INACTIVE"))),
                List.of(),
                List.of(mutation),
                List.of(),
                null,
                null
        );

        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);

        assertTrue(report.valid(), () -> report.issues().toString());
    }

    @Test
    void validatesAuthoredNestedInputDefaultsAndDatabaseBindings() {
        String source = """
                apiVersion: titan.graphql/v1alpha1
                kind: ProjectionModel
                metadata:
                  name: input-validation
                roots: {}
                inputObjects:
                  RenameInput:
                    fields:
                      id:
                        type: Int!
                      patch:
                        type: RenamePatch!
                  RenamePatch:
                    fields:
                      name:
                        type: String!
                        defaultValue: '"Default"'
                mutations:
                  rename:
                    operation: update
                    type: Customer
                    input:
                      name: input
                      type: RenameInput
                    inputBindings:
                      id:
                        path: id
                        type: Int
                        column: id
                        key: true
                      name:
                        path: patch.name
                        type: String
                        column: name
                    payload:
                      name:
                        argument: name
                types:
                  Customer:
                    table: customers
                    fields:
                      id: {type: Int, column: id}
                      name: {type: String, column: name}
                """;
        TitanGraphqlValidationReport valid = TitanGraphqlModelDocumentValidator.validate(
                TitanGraphqlModelDocumentYaml.parse(source));
        TitanGraphqlValidationReport invalidDefault = TitanGraphqlModelDocumentValidator.validate(
                TitanGraphqlModelDocumentYaml.parse(source.replace("'\"Default\"'", "'7'")));
        TitanGraphqlValidationReport invalidPath = TitanGraphqlModelDocumentValidator.validate(
                TitanGraphqlModelDocumentYaml.parse(source.replace("patch.name", "patch.unknown")));
        TitanGraphqlValidationReport wrappedMutationInput = TitanGraphqlModelDocumentValidator.validate(
                TitanGraphqlModelDocumentYaml.parse(source.replace("type: RenameInput", "type: '[RenameInput]'")));

        assertTrue(valid.valid(), () -> valid.issues().toString());
        assertTrue(invalidDefault.issues().stream()
                .anyMatch(issue -> issue.message().contains("invalid default")),
                () -> invalidDefault.issues().toString());
        assertTrue(invalidPath.issues().stream()
                .anyMatch(issue -> issue.message().contains("unknown or unsupported field path")),
                () -> invalidPath.issues().toString());
        assertTrue(wrappedMutationInput.issues().stream()
                .anyMatch(issue -> issue.message().contains("bare input-object name")),
                () -> wrappedMutationInput.issues().toString());
    }

    @Test
    void validatesGeneratedFieldArgumentDefaultsAsTypedGraphqlConstants() {
        String source = """
                apiVersion: titan.graphql/v1alpha1
                kind: ProjectionModel
                metadata: { name: argument-default-validation }
                roots:
                  customer:
                    type: Customer
                    operation: point
                    argument:
                      name: id
                      type: Int
                      column: id
                      defaultValue: 7
                types:
                  Customer:
                    table: customers
                    primaryKey: id
                    fields:
                      id: { type: Int, column: id }
                """;

        TitanGraphqlValidationReport valid = TitanGraphqlModelDocumentValidator.validate(
                TitanGraphqlModelDocumentYaml.parse(source));
        TitanGraphqlValidationReport wrongType = TitanGraphqlModelDocumentValidator.validate(
                TitanGraphqlModelDocumentYaml.parse(source.replace("defaultValue: 7", "defaultValue: '\"bad\"'")));
        TitanGraphqlValidationReport nullDefault = TitanGraphqlModelDocumentValidator.validate(
                TitanGraphqlModelDocumentYaml.parse(source.replace("defaultValue: 7", "defaultValue: 'null'")));
        TitanGraphqlValidationReport variableDefault = TitanGraphqlModelDocumentValidator.validate(
                TitanGraphqlModelDocumentYaml.parse(source.replace("defaultValue: 7", "defaultValue: '$id'")));

        assertTrue(valid.valid(), () -> valid.issues().toString());
        assertTrue(wrongType.issues().stream().anyMatch(issue -> issue.message().contains("invalid default")),
                () -> wrongType.issues().toString());
        assertTrue(nullDefault.issues().stream().anyMatch(issue -> issue.message().contains("invalid default")),
                () -> nullDefault.issues().toString());
        assertTrue(variableDefault.issues().stream()
                        .anyMatch(issue -> issue.message().contains("constant GraphQL value")),
                () -> variableDefault.issues().toString());
    }

    @Test
    void rejectsInvalidEnumDefinitionsAndUnsupportedEnumCapabilities() {
        TitanGraphqlFieldDocument filterableEnum = new TitanGraphqlFieldDocument(
                "status", "CustomerStatus", "status", false, List.of(), List.of("contains"), null, null);
        TitanGraphqlValidationReport invalid = TitanGraphqlModelDocumentValidator.validate(enumDocument(
                List.of(
                        new TitanGraphqlEnumDocument("customerStatus", List.of()),
                        new TitanGraphqlEnumDocument("CustomerStatus", List.of(
                                "ACTIVE", "ACTIVE", "true", "__PRIVATE", "NOT-VALID")),
                        new TitanGraphqlEnumDocument("String", List.of("VALUE")),
                        new TitanGraphqlEnumDocument("Customer", List.of("VALUE"))
                ),
                filterableEnum
        ));

        assertEquals(List.of(
                TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                TitanGraphqlValidationIssueCode.INVALID_BINDING,
                TitanGraphqlValidationIssueCode.MISSING_REQUIRED_FIELD,
                TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                TitanGraphqlValidationIssueCode.UNKNOWN_ENUM_VALUE,
                TitanGraphqlValidationIssueCode.UNKNOWN_ENUM_VALUE,
                TitanGraphqlValidationIssueCode.UNKNOWN_ENUM_VALUE,
                TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY
        ), invalid.issues().stream().map(TitanGraphqlValidationIssue::code).toList());
        assertTrue(invalid.blocksDeployment());
    }

    @Test
    void validatesEnumValueDescriptionAndDeprecationMetadata() {
        TitanGraphqlFieldDocument status = new TitanGraphqlFieldDocument(
                "status", "CustomerStatus", "status", false, List.of(), List.of(), null, null);
        TitanGraphqlValidationReport valid = TitanGraphqlModelDocumentValidator.validate(enumDocument(
                List.of(new TitanGraphqlEnumDocument("CustomerStatus", List.of("ACTIVE", "LEGACY"), List.of(
                        new TitanGraphqlEnumDocument.EnumValueMetadata(
                                "ACTIVE", "Customer can place orders.", false, ""),
                        new TitanGraphqlEnumDocument.EnumValueMetadata(
                                "LEGACY", "Historic status.", true, "Use ACTIVE.")))), status));

        assertTrue(valid.valid(), () -> valid.issues().toString());

        TitanGraphqlValidationReport invalid = TitanGraphqlModelDocumentValidator.validate(enumDocument(
                List.of(new TitanGraphqlEnumDocument("CustomerStatus", List.of("ACTIVE"), List.of(
                        new TitanGraphqlEnumDocument.EnumValueMetadata(
                                "ACTIVE", "", false, "Do not use."),
                        new TitanGraphqlEnumDocument.EnumValueMetadata(
                                "UNKNOWN", "", true, "Use ACTIVE."),
                        new TitanGraphqlEnumDocument.EnumValueMetadata(
                                "UNKNOWN", "", true, "Use ACTIVE.")))), status));

        assertEquals(List.of(
                TitanGraphqlValidationIssueCode.INVALID_BINDING,
                TitanGraphqlValidationIssueCode.UNKNOWN_ENUM_VALUE,
                TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                TitanGraphqlValidationIssueCode.UNKNOWN_ENUM_VALUE
        ), invalid.issues().stream().map(TitanGraphqlValidationIssue::code).toList());
    }

    private static TitanGraphqlModelDocument enumDocument(
            List<TitanGraphqlEnumDocument> enums,
            TitanGraphqlFieldDocument field
    ) {
        return new TitanGraphqlModelDocument(
                TitanGraphqlModelDocument.CURRENT_API_VERSION,
                TitanGraphqlModelDocument.PROJECTION_MODEL_KIND,
                new TitanGraphqlModelMetadata("enum-model"),
                null,
                List.of(),
                List.of(),
                List.of(new TitanGraphqlTypeDocument(
                        "Customer", "customers", "", "customers", "id", List.of(field), List.of())),
                enums,
                List.of(),
                List.of(),
                List.of(),
                null,
                null
        );
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
        return pointDocument(root, type, List.of());
    }

    private static TitanGraphqlModelDocument pointDocument(
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument type,
            List<TitanGraphqlEnumDocument> enums
    ) {
        return new TitanGraphqlModelDocument(
                TitanGraphqlModelDocument.CURRENT_API_VERSION,
                TitanGraphqlModelDocument.PROJECTION_MODEL_KIND,
                new TitanGraphqlModelMetadata("point-keys"),
                null,
                List.of(),
                List.of(root),
                List.of(type),
                enums,
                List.of(),
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
                policies,
                2
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
