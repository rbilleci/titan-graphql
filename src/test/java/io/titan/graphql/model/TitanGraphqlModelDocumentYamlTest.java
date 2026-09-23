package io.titan.graphql.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class TitanGraphqlModelDocumentYamlTest {

    @Test
    void parsesDemoBlogYamlFixtureIntoCanonicalIr() throws IOException {
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(readDemoBlogFixture());

        assertEquals("demo-blog", document.metadata().name());
        assertEquals(2, document.modules().size());
        assertEquals(2, document.roots().size());
        assertEquals(3, document.types().size());
        assertEquals(1, document.policies().size());
        assertEquals(1, document.contextFilters().size());

        TitanGraphqlRootDocument articles = document.roots().get(1);
        assertEquals("articles", articles.name());
        assertEquals(TitanGraphqlRootDocument.RootDocumentOperation.CONNECTION, articles.operation());
        assertEquals(TitanGraphqlRootDocument.TotalCountMode.EXACT, articles.pagination().totalCount());
        assertEquals(TitanGraphqlRootDocument.RootDocumentSortDirection.ASC, articles.pagination().cursor().direction());
        assertEquals("authorName", articles.filterPaths().get(0).name());
        assertEquals(1, articles.filterPaths().get(0).hops());
        assertEquals(4, articles.sortPaths().size());
        assertEquals("authorName", articles.sortPaths().get(3).name());
        assertEquals(1, articles.sortPaths().get(3).hops());

        TitanGraphqlTypeDocument article = document.types().get(0);
        TitanGraphqlFieldDocument titleLength = article.fields().get(2);
        assertEquals("titleLength", titleLength.name());
        assertEquals(TitanGraphqlFieldDocument.FieldDocumentExpressionKind.SQL_TEMPLATE, titleLength.computed().expressionKind());
        assertEquals("length({title})", titleLength.computed().sqlTemplate());
        assertEquals(TitanGraphqlFieldDocument.FieldDocumentCostClass.ROW_LOCAL, titleLength.computed().costClass());
        assertEquals(TitanGraphqlFieldDocument.FieldDocumentSortDirection.ASC, titleLength.sort().direction());

        TitanGraphqlRelationDocument comments = article.relations().get(1);
        assertEquals(TitanGraphqlRelationDocument.RelationDocumentCardinality.MANY, comments.cardinality());
        assertEquals(TitanGraphqlRelationDocument.RelationDocumentPaginationMode.RELAY, comments.pagination().mode());
        assertTrue(comments.pagination().totalCount());
        assertEquals(2, comments.selectionHopBudget());
        assertTrue(comments.selectable());
        assertTrue(comments.batchable());
        assertEquals(4, comments.arguments().size());

        assertEquals(TitanGraphqlPolicyDocument.Effect.DENY, document.policies().get(0).effect());
        assertEquals("adminOnly", document.policies().get(0).expression());
        assertEquals(TitanGraphqlContextFilterDocument.Operator.BOOLEAN_EQUALS, document.contextFilters().get(0).operator());
        assertEquals("build/generated/titan-graphql", document.artifacts().outputDirectory());
        assertEquals("/preview/demo-blog/graphql", document.deployment().previewEndpoint());
        assertFalse(TitanGraphqlModelDocumentJson.semanticHash(document).isBlank());
    }

    @Test
    void reportsMalformedYamlWithSourceLocation() {
        TitanGraphqlModelDocumentYamlException error = assertThrows(
                TitanGraphqlModelDocumentYamlException.class,
                () -> TitanGraphqlModelDocumentYaml.parse("""
                        apiVersion: titan.graphql/v1alpha1
                        metadata:
                          name: demo
                          owner: [unterminated
                        """)
        );

        assertEquals("INVALID_YAML", error.code());
        assertTrue(error.hasSourceLocation());
    }

    @Test
    void rejectsUnsupportedTopLevelFields() {
        TitanGraphqlModelDocumentYamlException error = assertThrows(
                TitanGraphqlModelDocumentYamlException.class,
                () -> TitanGraphqlModelDocumentYaml.parse("""
                        apiVersion: titan.graphql/v1alpha1
                        kind: ProjectionModel
                        metadata:
                          name: demo
                        imports: []
                        """)
        );

        assertEquals("UNSUPPORTED_FIELD", error.code());
    }

    @Test
    void rejectsUnknownEnumValues() {
        TitanGraphqlModelDocumentYamlException error = assertThrows(
                TitanGraphqlModelDocumentYamlException.class,
                () -> TitanGraphqlModelDocumentYaml.parse("""
                        apiVersion: titan.graphql/v1alpha1
                        kind: ProjectionModel
                        metadata:
                          name: demo
                        roots:
                          article:
                            type: Article
                            operation: stream
                        """)
        );

        assertEquals("UNKNOWN_ENUM_VALUE", error.code());
    }

    @Test
    void parsesRegisteredConditionalDirectivesIntoCanonicalIdentity() {
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse("""
                apiVersion: titan.graphql/v1alpha1
                kind: ProjectionModel
                metadata: { name: directives-model }
                roots: {}
                types: {}
                directives:
                  visible:
                    description: Conditionally expose this selection.
                    behavior: includeIf
                    locations: [INLINE_FRAGMENT, FIELD, FRAGMENT_SPREAD]
                """);

        assertEquals(1, document.directives().size());
        TitanGraphqlDirectiveDocument directive = document.directives().get(0);
        assertEquals("visible", directive.name());
        assertEquals(TitanGraphqlDirectiveDocument.Behavior.INCLUDE_IF, directive.behavior());
        assertEquals(3, directive.locations().size());
        String canonical = TitanGraphqlModelDocumentJson.canonicalJson(document);
        assertTrue(canonical.contains("\"directives\":[{\"behavior\":\"INCLUDE_IF\""), canonical);
        assertTrue(canonical.indexOf("FIELD") < canonical.indexOf("FRAGMENT_SPREAD"), canonical);
    }

    @Test
    void parsesDeclaredSchemaEnumAndFieldReference() {
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse("""
                apiVersion: titan.graphql/v1alpha1
                kind: ProjectionModel
                metadata:
                  name: enum-model
                roots: {}
                enums:
                  CustomerStatus:
                    values: [ACTIVE, INACTIVE, LEGACY]
                    valueMetadata:
                      ACTIVE:
                        description: Customer can place orders.
                      LEGACY:
                        deprecated: true
                        deprecationReason: Use INACTIVE.
                types:
                  Customer:
                    fields:
                      status:
                        column: status
                        type: CustomerStatus
                        nullable: false
                """);

        assertEquals(1, document.enums().size());
        assertEquals("CustomerStatus", document.enums().getFirst().name());
        assertEquals(java.util.List.of("ACTIVE", "INACTIVE", "LEGACY"), document.enums().getFirst().values());
        assertEquals(2, document.enums().getFirst().valueMetadata().size());
        assertEquals("Customer can place orders.",
                document.enums().getFirst().valueMetadata().getFirst().description());
        assertTrue(document.enums().getFirst().valueMetadata().get(1).deprecated());
        assertEquals("Use INACTIVE.", document.enums().getFirst().valueMetadata().get(1).deprecationReason());
        assertEquals("CustomerStatus", document.types().getFirst().fields().getFirst().type());
    }

    @Test
    void parsesAuthoredInputObjectsDefaultsAndMutationBindings() {
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse("""
                apiVersion: titan.graphql/v1alpha1
                kind: ProjectionModel
                metadata:
                  name: input-model
                roots: {}
                inputObjects:
                  RenameInput:
                    description: Rename request.
                    fields:
                      id:
                        type: Int!
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
                        path: name
                        type: String
                        column: name
                    payload:
                      name:
                        argument: name
                types:
                  Customer:
                    table: customers
                    fields:
                      id:
                        type: Int
                        column: id
                      name:
                        type: String
                        column: name
                """);

        assertEquals("RenameInput", document.inputObjects().getFirst().name());
        assertEquals("\"Default\"",
                document.inputObjects().getFirst().fields().get(1).defaultValue());
        assertEquals("input", document.mutations().getFirst().input().name());
        assertEquals("name", document.mutations().getFirst().inputBindings().get(1).path());
        assertFalse(TitanGraphqlModelDocumentJson.semanticHash(document).isBlank());
    }

    @Test
    void parsesDefaultsOnGeneratedFieldArguments() {
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse("""
                apiVersion: titan.graphql/v1alpha1
                kind: ProjectionModel
                metadata: { name: argument-defaults }
                roots:
                  customer:
                    type: Customer
                    operation: point
                    argument:
                      name: id
                      type: Int
                      column: id
                      defaultValue: 7
                mutations:
                  rename:
                    operation: update
                    type: Customer
                    arguments:
                      id: { type: Int, column: id, key: true, defaultValue: 7 }
                      name: { type: String, column: name, defaultValue: '\"Default, =; name\"' }
                    payload:
                      name: { argument: name }
                types:
                  Customer:
                    fields:
                      id: { type: Int, column: id }
                      name: { type: String, column: name }
                    relations:
                      orders:
                        target: Customer
                        localColumn: id
                        targetColumn: id
                        arguments:
                          id: { type: Int, kind: equals, column: id, defaultValue: 7 }
                """);

        assertEquals("7", document.roots().getFirst().argument().defaultValue());
        assertEquals("\"Default, =; name\"",
                document.mutations().getFirst().arguments().get(1).defaultValue());
        assertEquals("7", document.types().getFirst().relations().getFirst()
                .arguments().getFirst().defaultValue());
        assertTrue(TitanGraphqlModelDocumentJson.canonicalJson(document).contains("defaultValue"));
    }

    @Test
    void parsesAbstractOutputTypesAndPointRootBinding() {
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
                    argument:
                      name: id
                      type: Int
                      column: id
                interfaces:
                  Node:
                    description: An identifiable object.
                    fields:
                      id:
                        type: Int!
                        description: Stable identifier.
                unions:
                  SearchResult:
                    description: Searchable objects.
                    members: [Customer, Order]
                types:
                  Customer:
                    description: A customer account.
                    interfaces: [Node]
                    fields:
                      id:
                        type: Int
                        column: id
                        description: Customer identifier.
                        deprecated: true
                        deprecationReason: Use nodeId.
                  Order:
                    interfaces: [Node]
                    fields:
                      id:
                        type: Int
                        column: id
                """);

        assertEquals("Node", document.roots().getFirst().outputType());
        assertEquals("An identifiable object.", document.interfaces().getFirst().description());
        assertEquals("Int!", document.interfaces().getFirst().fields().getFirst().type());
        assertEquals(java.util.List.of("Customer", "Order"), document.unions().getFirst().members());
        assertEquals(java.util.List.of("Node"), document.types().getFirst().interfaces());
        assertEquals("Customer identifier.", document.types().getFirst().fields().getFirst().description());
        assertTrue(document.types().getFirst().fields().getFirst().deprecated());
        assertEquals("Use nodeId.", document.types().getFirst().fields().getFirst().deprecationReason());
        assertFalse(TitanGraphqlModelDocumentJson.semanticHash(document).isBlank());
    }

    @Test
    void rejectsMissingRequiredFields() {
        TitanGraphqlModelDocumentYamlException error = assertThrows(
                TitanGraphqlModelDocumentYamlException.class,
                () -> TitanGraphqlModelDocumentYaml.parse("""
                        apiVersion: titan.graphql/v1alpha1
                        kind: ProjectionModel
                        roots: {}
                        """)
        );

        assertEquals("MISSING_REQUIRED_FIELD", error.code());
    }

    private static String readDemoBlogFixture() throws IOException {
        try (InputStream stream = TitanGraphqlModelDocumentYamlTest.class
                .getResourceAsStream("/graphql/demo-blog.titan.graphql.yaml")) {
            if (stream == null) {
                throw new IllegalStateException("demo-blog model fixture is missing");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final String DEMO_BLOG_YAML = """
            apiVersion: titan.graphql/v1alpha1
            kind: ProjectionModel
            metadata:
              name: demo-blog
              version: 2026.05.31
              owner: platform
              description: Bounded demo blog model used to prove Titan GraphQL.
              tags: [demo, query-only]
            modules:
              content:
                owner: content-platform
                roots: [article, articles]
                types: [Article, Comment]
              identity:
                owner: identity-platform
                types: [User]
                policies: [canReadUserEmail]
            database:
              catalog: demo_blog
              defaultSchema: public
              tables:
                articles:
                  physicalName: articles
                  primaryKey: id
                users:
                  physicalName: users
                  primaryKey: id
                comments:
                  physicalName: comments
                  primaryKey: id
            policies:
              canReadUserEmail:
                description: User email is visible only to admins.
                appliesTo: [User.email]
                mode: reject
                expression:
                  kind: named
                  name: adminOnly
            contextFilters:
              publishedVisibility:
                description: Restrict articles by the published flag when enabled.
                type: booleanEquals
                column: published
                contextKey: articleVisibility
                failClosed: true
                phase: beforeClientFilters
            types:
              Article:
                table: articles
                fields:
                  id:
                    column: id
                    type: Int
                    filter:
                      operators: [eq, neq, in, isNull, lt, lte, gt, gte]
                    sort:
                      default: asc
                      tieBreaker: id
                  title:
                    column: title
                    type: String
                    filter:
                      operators: [eq, neq, in, isNull, contains, startsWith, endsWith]
                    sort:
                      default: asc
                      tieBreaker: id
                  titleLength:
                    type: Int
                    computed:
                      kind: sqlTemplate
                      template: "length({title})"
                      requiredColumns: [title]
                      deterministic: true
                      nullable: false
                      sensitive: false
                      costClass: rowLocal
                    selectable: true
                    filter:
                      operators: [eq, neq, in, isNull, lt, lte, gt, gte]
                    sort:
                      default: asc
                      tieBreaker: id
                relations:
                  author:
                    target: User
                    cardinality: one
                    nullable: false
                    localColumn: author_id
                    targetColumn: id
                    capabilities:
                      selectable: true
                      batchable: true
                      selectionHopBudget: 2
                  comments:
                    target: Comment
                    cardinality: many
                    nullable: false
                    localColumn: id
                    targetColumn: article_id
                    capabilities:
                      selectable: true
                      batchable: true
                      pagination: relay
                      totalCount: exact
                      defaultPageSize: 10
                      maxPageSize: 100
                    arguments:
                      first:
                        kind: relayFirst
                      after:
                        kind: relayAfter
                    sortPaths:
                      id:
                        column: id
                        path: id
                        direction: asc
                        tieBreaker: id
              User:
                table: users
                fields:
                  id:
                    column: id
                    type: Int
                  name:
                    column: name
                    type: String
                  email:
                    column: email
                    type: String
                    policy: canReadUserEmail
              Comment:
                table: comments
                fields:
                  id:
                    column: id
                    type: Int
                  body:
                    column: body
                    type: String
                relations:
                  author:
                    target: User
                    cardinality: one
                    nullable: false
                    localColumn: author_id
                    targetColumn: id
            roots:
              article:
                type: Article
                operation: point
                argument:
                  name: id
                  type: Int
                  column: id
              articles:
                type: Article
                operation: connection
                pagination:
                  mode: relay
                  defaultPageSize: 10
                  maxPageSize: 100
                  totalCount: exact
                  cursor:
                    path: id
                    column: id
                    direction: asc
                    tieBreaker: id
                arguments:
                  authorId:
                    type: Int
                    kind: equals
                    column: author_id
                filterPaths:
                  authorName:
                    type: String
                    column: author_id
                    path: author.name
                    hops: 1
                    operators: [eq, neq, in, isNull, contains, startsWith, endsWith]
                sortPaths:
                  id:
                    column: id
                    path: id
                    direction: asc
                    tieBreaker: id
                contextFilters:
                  - publishedVisibility
            artifacts:
              generatedSchema:
                enabled: true
                path: build/generated/titan-graphql/schema.graphql
              introspection:
                enabled: true
                path: build/generated/titan-graphql/introspection.json
              conformance:
                enabled: true
                path: build/generated/titan-graphql/conformance.md
              sql:
                enabled: true
                dialects: [postgres]
                path: build/generated/titan-graphql/sql
            deployment:
              environment: staging
              preview:
                enabled: true
                route: /preview/demo-blog/graphql
                expiresAfter: P7D
              runtime:
                endpoint: /graphql
                managementEndpoint: /admin/graphql
            """;
}
