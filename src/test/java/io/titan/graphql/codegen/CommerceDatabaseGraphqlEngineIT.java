package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.titan.graphql.GraphqlApplicationMutationProvider;
import io.titan.graphql.GraphqlCursorCodec;
import io.titan.graphql.GraphqlExecutionEngine;
import io.titan.graphql.GraphqlHttpResource;
import io.titan.graphql.GraphqlRequestContext;
import io.titan.graphql.GraphqlRootField;
import io.titan.graphql.GraphqlRuntimeRequest;
import io.titan.graphql.database.DatabaseGraphqlWholeRequestRuntime;
import io.titan.runtime.jdbc.SingleConnectionDataSource;
import io.titan.runtime.testing.DatabaseTarget;
import io.titan.runtime.testing.TitanTest;
import io.titan.runtime.testing.TitanTestContext;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Direct PostgreSQL verification for the unrelated generated whole-request package. */
@Tag("docker")
@Tag("database-engine-commerce")
@TitanTest(targets = {DatabaseTarget.POSTGRESQL})
class CommerceDatabaseGraphqlEngineIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void thinAdapterForwardsTheWholeEnvelopeAndOwnsTheDatabaseTransaction(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
        DatabaseGraphqlWholeRequestRuntime runtime = new DatabaseGraphqlWholeRequestRuntime(
                DatabaseGraphqlWholeRequestRuntime.Dialect.POSTGRESQL,
                () -> context.connection(DatabaseTarget.POSTGRESQL),
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.runtimeIdentity(),
                DatabaseEngineTestRequestContract.packageIdentity(),
                "Titan PostgreSQL test connection");

        DatabaseGraphqlWholeRequestRuntime replacedPackage = new DatabaseGraphqlWholeRequestRuntime(
                DatabaseGraphqlWholeRequestRuntime.Dialect.POSTGRESQL,
                () -> context.connection(DatabaseTarget.POSTGRESQL),
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                "0000000000000000000000000000000000000000000000000000000000000000",
                DatabaseEngineTestRequestContract.packageIdentity(),
                "Titan PostgreSQL test connection");
        JsonNode replacedPackageResponse = JSON.readTree(replacedPackage.execute(
                new GraphqlRuntimeRequest("mutation { renameCustomer(id: 7, name: \"Must not execute\") { id } }",
                        "", "{}", "{}", true),
                GraphqlRequestContext.legacy(11L, "editor")));
        assertTrue(replacedPackageResponse.at("/errors/0/message").asText().contains("runtime identity"),
                replacedPackageResponse::toString);
        assertEquals("Northwind", customerName(connection, 7));

        DatabaseGraphqlWholeRequestRuntime stalePackage = new DatabaseGraphqlWholeRequestRuntime(
                DatabaseGraphqlWholeRequestRuntime.Dialect.POSTGRESQL,
                () -> context.connection(DatabaseTarget.POSTGRESQL),
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.runtimeIdentity(),
                "0000000000000000000000000000000000000000000000000000000000000000",
                "Titan PostgreSQL test connection");
        JsonNode stalePackageResponse = JSON.readTree(stalePackage.execute(
                new GraphqlRuntimeRequest("{ deliberately invalid before GraphQL parsing }", "", "{}", "{}", false),
                GraphqlRequestContext.legacy(11L, "reader")));
        assertTrue(stalePackageResponse.at("/errors/0/message").asText().contains("package identity"),
                stalePackageResponse::toString);

        JsonNode query = JSON.readTree(runtime.execute(
                new GraphqlRuntimeRequest("{ customer(id: 7) { name } }", "", "{}", "{}", false),
                GraphqlRequestContext.legacy(11L, "reader")));
        assertEquals("Northwind", query.at("/data/customer/name").asText(), query::toString);

        JsonNode getMutation = JSON.readTree(runtime.execute(
                new GraphqlRuntimeRequest("mutation { renameCustomer(id: 7, name: \"Forbidden\") { id } }",
                        "", "{}", "{}", false),
                GraphqlRequestContext.legacy(11L, "editor")));
        assertTrue(getMutation.at("/errors/0/message").asText().contains("not allowed"), getMutation::toString);
        assertEquals("Northwind", customerName(connection, 7));

        JsonNode failedMutation = JSON.readTree(runtime.execute(
                new GraphqlRuntimeRequest("""
                        mutation {
                          first: renameCustomer(id: 7, name: "Must roll back") { id }
                          second: renameCustomer(id: 999, name: "Missing") { id }
                        }
                        """, "", "{}", "{}", true),
                GraphqlRequestContext.legacy(11L, "editor")));
        assertTrue(failedMutation.at("/extensions/titanTransactionOutcome").isMissingNode(),
                "transport lifecycle data must not leak into the GraphQL response: " + failedMutation);
        assertEquals("Northwind", customerName(connection, 7));

        JsonNode committedMutation = JSON.readTree(runtime.execute(
                new GraphqlRuntimeRequest("mutation { renameCustomer(id: 7, name: \"Adapter committed\") { mutationType: __typename name } }",
                        "", "{}", "{}", true),
                GraphqlRequestContext.legacy(11L, "editor")));
        assertTrue(committedMutation.at("/extensions/titanTransactionOutcome").isMissingNode(),
                "transport lifecycle data must not leak into the GraphQL response: " + committedMutation);
        assertEquals("Customer", committedMutation.at("/data/renameCustomer/mutationType").asText(),
                committedMutation::toString);
        assertEquals("Adapter committed", customerName(connection, 7));
    }

    @Test
    void jaxRsAdapterUsesOnlyTheBoundWholeRequestEngine(TitanTestContext context) throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
        GraphqlHttpResource resource = new GraphqlHttpResource(databaseEngine(connection), true);

        Response get = resource.getResponse(
                "{ customer(id: 7) { name } }", null, null, null, "application/graphql-response+json",
                "11", "reader", null, null, null, null, null, null, null, null);

        assertEquals(200, get.getStatus());
        assertEquals("database", get.getHeaderString("X-Titan-Execution-Mode"));
        assertTrue(get.getHeaderString("X-Titan-Deployment-Fingerprint").matches("[0-9a-f]{64}"));
        JsonNode response = JSON.readTree(String.valueOf(get.getEntity()));
        assertEquals("Northwind", response.at("/data/customer/name").asText(), response::toString);

        Response post = resource.postResponse(
                Map.of("query", "mutation { renameCustomer(id: 7, name: \"HTTP committed\") { name } }"),
                "application/graphql-response+json", "11", "editor", null, null, null,
                null, null, null, null, null);
        assertEquals(200, post.getStatus());
        assertEquals("HTTP committed", JSON.readTree(String.valueOf(post.getEntity()))
                .at("/data/renameCustomer/name").asText());
        assertEquals("HTTP committed", customerName(connection, 7));
    }

    @Test
    void installedEntryPointRejectsExpiredAndUnrepresentableTrustedDeadlinesInsidePostgreSql(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);

        JsonNode expired = execute(connection, "{ customer(id: 7) { name } }", "", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.trustedContext("reader", 1577836800000L));
        JsonNode unrepresentable = execute(connection, "{ customer(id: 7) { name } }", "", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.trustedContext("reader", Long.MAX_VALUE));

        assertTrue(expired.at("/errors/0/message").asText()
                .contains("request deadline exceeded before database execution"), expired::toString);
        assertTrue(unrepresentable.at("/errors/0/message").asText()
                .contains("invalid deadlineEpochMillis"), unrepresentable::toString);
    }

    private static GraphqlExecutionEngine databaseEngine(Connection connection) {
        return new GraphqlExecutionEngine(
                "database",
                () -> new SingleConnectionDataSource(connection),
                "Titan PostgreSQL HTTP test connection",
                "src/test/resources/graphql/commerce.titan.graphql.yaml",
                GraphqlApplicationMutationProvider.none(),
                "postgresql");
    }

    @Test
    void installedEntryPointServesTrustedDatabaseResidentIntrospectionIdentity(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);

        JsonNode disabled = execute(connection, "{ __schema { queryType { name } } }", "", "{}", "reader");
        assertTrue(disabled.at("/errors/0/message").asText().contains("introspection is disabled"),
                disabled::toString);

        JsonNode introspection = execute(connection, """
                query SchemaIdentity($name: String!, $includeDeprecated: Boolean!) {
                  schema: __schema {
                    queryType { name kind description ofType { kind name } fields { name type { kind name ofType { kind name } } } }
                    mutationType { name kind fields { name type { kind name ofType { kind name } } } }
                    subscriptionType { name kind }
                    description
                  }
                  requested: __type(name: $name) {
                    name kind description ofType { kind name }
                    fields(includeDeprecated: $includeDeprecated) { name type { kind name ofType { kind name ofType { kind name ofType { kind name } } } } }
                  }
                  queryArguments: __type(name: "Query") {
                    fields { name args { name description defaultValue isDeprecated deprecationReason __typename
                      type { kind name ofType { kind name ofType { kind name } } }
                    } }
                  }
                  mutationArguments: __type(name: "Mutation") {
                    fields { name args { name type { kind name ofType { kind name } } } }
                  }
                  connectionType: __type(name: "CustomerConnection") {
                    name kind
                    fields { name type { kind name ofType { kind name ofType { kind name ofType { kind name } } } } }
                  }
                  edgeType: __type(name: "CustomerEdge") {
                    name kind fields { name type { kind name ofType { kind name } } }
                  }
                  pageInfoType: __type(name: "PageInfo") { name kind fields { name } }
                  stringType: __type(name: "String") { name kind }
                  absent: __type(name: "NotAType") { name kind }
                }
                """, "SchemaIdentity", "{\"name\":\"Customer\",\"includeDeprecated\":true}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.introspectionTrustedContext("reader"));
        assertEquals("Query", introspection.at("/data/schema/queryType/name").asText(), introspection::toString);
        assertEquals("OBJECT", introspection.at("/data/schema/queryType/kind").asText(), introspection::toString);
        assertTrue(introspection.at("/data/schema/queryType/description").isNull(), introspection::toString);
        assertTrue(introspection.at("/data/schema/queryType/ofType").isNull(), introspection::toString);
        assertEquals("Mutation", introspection.at("/data/schema/mutationType/name").asText(), introspection::toString);
        assertEquals("OBJECT", introspection.at("/data/schema/mutationType/kind").asText(), introspection::toString);
        JsonNode customerRoot = fieldNamed(introspection.at("/data/schema/queryType/fields"), "customer");
        assertEquals("OBJECT", customerRoot.at("/type/kind").asText(), introspection::toString);
        assertEquals("Customer", customerRoot.at("/type/name").asText(), introspection::toString);
        JsonNode customersRoot = fieldNamed(introspection.at("/data/schema/queryType/fields"), "customers");
        assertEquals("NON_NULL", customersRoot.at("/type/kind").asText(), introspection::toString);
        assertEquals("OBJECT", customersRoot.at("/type/ofType/kind").asText(), introspection::toString);
        assertEquals("CustomerConnection", customersRoot.at("/type/ofType/name").asText(), introspection::toString);
        JsonNode renameCustomer = fieldNamed(introspection.at("/data/schema/mutationType/fields"), "renameCustomer");
        assertEquals("NON_NULL", renameCustomer.at("/type/kind").asText(), introspection::toString);
        assertEquals("Customer", renameCustomer.at("/type/ofType/name").asText(), introspection::toString);
        assertTrue(introspection.at("/data/schema/subscriptionType").isNull(), introspection::toString);
        assertTrue(introspection.at("/data/schema/description").isNull(), introspection::toString);
        JsonNode schemaTypes = execute(connection,
                "query SchemaTypes { __schema { types { name kind } } }", "SchemaTypes", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.introspectionTrustedContext("reader"));
        assertTrue(schemaTypes.at("/data/__schema/types").findValuesAsText("name").contains("Customer"),
                schemaTypes::toString);
        assertTrue(schemaTypes.at("/data/__schema/types").findValuesAsText("name").contains("UUID"),
                schemaTypes::toString);
        assertTrue(schemaTypes.at("/data/__schema/types").findValuesAsText("name").contains("CustomerConnection"),
                schemaTypes::toString);
        assertTrue(schemaTypes.at("/data/__schema/types").findValuesAsText("name").contains("CustomerEdge"),
                schemaTypes::toString);
        assertTrue(schemaTypes.at("/data/__schema/types").findValuesAsText("name").contains("PageInfo"),
                schemaTypes::toString);
        assertTrue(schemaTypes.at("/data/__schema/types").findValuesAsText("name").contains("CustomerFilter"),
                schemaTypes::toString);
        assertTrue(schemaTypes.at("/data/__schema/types").findValuesAsText("name").contains("StringFilter"),
                schemaTypes::toString);
        assertTrue(schemaTypes.at("/data/__schema/types").findValuesAsText("name").contains("CustomerOrderBy"),
                schemaTypes::toString);
        assertTrue(schemaTypes.at("/data/__schema/types").findValuesAsText("name").contains("SortDirection"),
                schemaTypes::toString);
        assertTrue(schemaTypes.at("/data/__schema/types").findValuesAsText("name").contains("CustomerStatus"),
                schemaTypes::toString);
        JsonNode directiveIntrospection = execute(connection, """
                query DirectiveMetadata {
                  __schema {
                    directives {
                      name description isRepeatable locations __typename
                      args { name type { kind name ofType { kind name } } }
                    }
                  }
                }
                """, "DirectiveMetadata", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.introspectionTrustedContext("reader"));
        JsonNode includeDirective = fieldNamed(
                directiveIntrospection.at("/data/__schema/directives"), "include");
        assertTrue(includeDirective.at("/description").isNull(), directiveIntrospection::toString);
        assertFalse(includeDirective.at("/isRepeatable").asBoolean(), directiveIntrospection::toString);
        assertEquals("__Directive", includeDirective.at("/__typename").asText(),
                directiveIntrospection::toString);
        assertEquals("FIELD", includeDirective.at("/locations/0").asText(), directiveIntrospection::toString);
        assertEquals("FRAGMENT_SPREAD", includeDirective.at("/locations/1").asText(),
                directiveIntrospection::toString);
        assertEquals("INLINE_FRAGMENT", includeDirective.at("/locations/2").asText(),
                directiveIntrospection::toString);
        JsonNode includeIf = fieldNamed(includeDirective.at("/args"), "if");
        assertEquals("NON_NULL", includeIf.at("/type/kind").asText(), directiveIntrospection::toString);
        assertEquals("Boolean", includeIf.at("/type/ofType/name").asText(), directiveIntrospection::toString);
        assertEquals("skip", fieldNamed(directiveIntrospection.at("/data/__schema/directives"), "skip")
                .at("/name").asText(), directiveIntrospection::toString);
        JsonNode hiddenDirective = fieldNamed(
                directiveIntrospection.at("/data/__schema/directives"), "hidden");
        assertEquals("Skips a field when its required Boolean condition is true.",
                hiddenDirective.at("/description").asText(), directiveIntrospection::toString);
        assertEquals(1, hiddenDirective.at("/locations").size(), directiveIntrospection::toString);
        assertEquals("FIELD", hiddenDirective.at("/locations/0").asText(), directiveIntrospection::toString);
        JsonNode visibleDirective = fieldNamed(
                directiveIntrospection.at("/data/__schema/directives"), "visible");
        assertEquals("Includes a selection when its required Boolean condition is true.",
                visibleDirective.at("/description").asText(), directiveIntrospection::toString);
        assertEquals(3, visibleDirective.at("/locations").size(), directiveIntrospection::toString);
        assertEquals("Customer", introspection.at("/data/requested/name").asText(), introspection::toString);
        assertEquals("OBJECT", introspection.at("/data/requested/kind").asText(), introspection::toString);
        JsonNode nicknameType = introspection.at("/data/requested/fields").findValues("name").isEmpty()
                ? null : introspection.at("/data/requested/fields");
        assertNotNull(nicknameType, introspection::toString);
        JsonNode nickname = null;
        for (JsonNode field : nicknameType) {
            if (field.at("/name").asText().equals("nickname")) nickname = field;
        }
        assertNotNull(nickname, introspection::toString);
        assertEquals("SCALAR", nickname.at("/type/kind").asText(), introspection::toString);
        assertEquals("String", nickname.at("/type/name").asText(), introspection::toString);
        JsonNode id = null;
        for (JsonNode field : nicknameType) {
            if (field.at("/name").asText().equals("id")) id = field;
        }
        assertNotNull(id, introspection::toString);
        assertEquals("NON_NULL", id.at("/type/kind").asText(), introspection::toString);
        assertEquals("SCALAR", id.at("/type/ofType/kind").asText(), introspection::toString);
        assertEquals("Int", id.at("/type/ofType/name").asText(), introspection::toString);
        JsonNode status = fieldNamed(nicknameType, "status");
        assertEquals("NON_NULL", status.at("/type/kind").asText(), introspection::toString);
        assertEquals("ENUM", status.at("/type/ofType/kind").asText(), introspection::toString);
        assertEquals("CustomerStatus", status.at("/type/ofType/name").asText(), introspection::toString);
        JsonNode nullableStatus = fieldNamed(nicknameType, "nullableStatus");
        assertEquals("ENUM", nullableStatus.at("/type/kind").asText(), introspection::toString);
        assertEquals("CustomerStatus", nullableStatus.at("/type/name").asText(), introspection::toString);
        JsonNode customerArguments = fieldNamed(introspection.at("/data/queryArguments/fields"), "customer")
                .at("/args");
        JsonNode customerId = fieldNamed(customerArguments, "id");
        assertEquals("NON_NULL", customerId.at("/type/kind").asText(), introspection::toString);
        assertEquals("SCALAR", customerId.at("/type/ofType/kind").asText(), introspection::toString);
        assertEquals("Int", customerId.at("/type/ofType/name").asText(), introspection::toString);
        assertTrue(customerId.at("/description").isNull(), introspection::toString);
        assertTrue(customerId.at("/defaultValue").isNull(), introspection::toString);
        assertFalse(customerId.at("/isDeprecated").asBoolean(), introspection::toString);
        assertTrue(customerId.at("/deprecationReason").isNull(), introspection::toString);
        assertEquals("__InputValue", customerId.at("/__typename").asText(), introspection::toString);
        JsonNode enumPointArguments = fieldNamed(
                introspection.at("/data/queryArguments/fields"), "customerByIdAndStatus").at("/args");
        JsonNode enumPointStatus = fieldNamed(enumPointArguments, "status");
        assertEquals("NON_NULL", enumPointStatus.at("/type/kind").asText(), introspection::toString);
        assertEquals("ENUM", enumPointStatus.at("/type/ofType/kind").asText(), introspection::toString);
        assertEquals("CustomerStatus", enumPointStatus.at("/type/ofType/name").asText(),
                introspection::toString);
        JsonNode customerFeedArguments = fieldNamed(
                introspection.at("/data/queryArguments/fields"), "customerFeed").at("/args");
        JsonNode customerFeedStatus = fieldNamed(customerFeedArguments, "status");
        assertEquals("ENUM", customerFeedStatus.at("/type/kind").asText(), introspection::toString);
        assertEquals("CustomerStatus", customerFeedStatus.at("/type/name").asText(), introspection::toString);
        JsonNode customersArguments = fieldNamed(introspection.at("/data/queryArguments/fields"), "customers")
                .at("/args");
        JsonNode customerFilter = fieldNamed(customersArguments, "filter");
        assertEquals("INPUT_OBJECT", customerFilter.at("/type/kind").asText(), introspection::toString);
        assertEquals("CustomerFilter", customerFilter.at("/type/name").asText(), introspection::toString);
        JsonNode customerOrderBy = fieldNamed(customersArguments, "orderBy");
        assertEquals("LIST", customerOrderBy.at("/type/kind").asText(), introspection::toString);
        assertEquals("NON_NULL", customerOrderBy.at("/type/ofType/kind").asText(), introspection::toString);
        assertEquals("INPUT_OBJECT", customerOrderBy.at("/type/ofType/ofType/kind").asText(), introspection::toString);
        assertEquals("CustomerOrderBy", customerOrderBy.at("/type/ofType/ofType/name").asText(), introspection::toString);
        JsonNode renameCustomerArguments = fieldNamed(introspection.at("/data/mutationArguments/fields"), "renameCustomer")
                .at("/args");
        JsonNode renameCustomerName = fieldNamed(renameCustomerArguments, "name");
        assertEquals("NON_NULL", renameCustomerName.at("/type/kind").asText(), introspection::toString);
        assertEquals("SCALAR", renameCustomerName.at("/type/ofType/kind").asText(), introspection::toString);
        assertEquals("String", renameCustomerName.at("/type/ofType/name").asText(), introspection::toString);
        JsonNode setCustomerStatusArguments = fieldNamed(
                introspection.at("/data/mutationArguments/fields"), "setCustomerStatus").at("/args");
        JsonNode setCustomerStatusValue = fieldNamed(setCustomerStatusArguments, "status");
        assertEquals("NON_NULL", setCustomerStatusValue.at("/type/kind").asText(), introspection::toString);
        assertEquals("ENUM", setCustomerStatusValue.at("/type/ofType/kind").asText(), introspection::toString);
        assertEquals("CustomerStatus", setCustomerStatusValue.at("/type/ofType/name").asText(),
                introspection::toString);
        JsonNode relationIntrospection = execute(connection, """
                { __type(name: "Customer") {
                    fields { name args { name type { kind name ofType { kind name } } } }
                  } }
                """, "", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.introspectionTrustedContext("reader"));
        JsonNode orderConnectionArguments = fieldNamed(
                relationIntrospection.at("/data/__type/fields"), "orderConnection").at("/args");
        JsonNode orderConnectionStatus = fieldNamed(orderConnectionArguments, "status");
        assertEquals("ENUM", orderConnectionStatus.at("/type/kind").asText(), relationIntrospection::toString);
        assertEquals("OrderStatus", orderConnectionStatus.at("/type/name").asText(),
                relationIntrospection::toString);
        JsonNode orders = null;
        for (JsonNode field : nicknameType) {
            if (field.at("/name").asText().equals("orders")) orders = field;
        }
        assertNotNull(orders, introspection::toString);
        assertEquals("NON_NULL", orders.at("/type/kind").asText(), introspection::toString);
        assertEquals("LIST", orders.at("/type/ofType/kind").asText(), introspection::toString);
        assertEquals("NON_NULL", orders.at("/type/ofType/ofType/kind").asText(), introspection::toString);
        assertEquals("OBJECT", orders.at("/type/ofType/ofType/ofType/kind").asText(), introspection::toString);
        assertEquals("Order", orders.at("/type/ofType/ofType/ofType/name").asText(), introspection::toString);
        assertEquals("CustomerConnection", introspection.at("/data/connectionType/name").asText(), introspection::toString);
        assertEquals("OBJECT", introspection.at("/data/connectionType/kind").asText(), introspection::toString);
        JsonNode edges = fieldNamed(introspection.at("/data/connectionType/fields"), "edges");
        assertEquals("NON_NULL", edges.at("/type/kind").asText(), introspection::toString);
        assertEquals("LIST", edges.at("/type/ofType/kind").asText(), introspection::toString);
        assertEquals("NON_NULL", edges.at("/type/ofType/ofType/kind").asText(), introspection::toString);
        assertEquals("CustomerEdge", edges.at("/type/ofType/ofType/ofType/name").asText(), introspection::toString);
        assertEquals("CustomerEdge", introspection.at("/data/edgeType/name").asText(), introspection::toString);
        assertEquals("Customer", fieldNamed(introspection.at("/data/edgeType/fields"), "node")
                .at("/type/ofType/name").asText(), introspection::toString);
        assertEquals("PageInfo", introspection.at("/data/pageInfoType/name").asText(), introspection::toString);
        assertTrue(introspection.at("/data/pageInfoType/fields").findValuesAsText("name").contains("hasNextPage"),
                introspection::toString);
        assertEquals("String", introspection.at("/data/stringType/name").asText(), introspection::toString);
        assertEquals("SCALAR", introspection.at("/data/stringType/kind").asText(), introspection::toString);
        assertTrue(introspection.at("/data/absent").isNull(), introspection::toString);

        JsonNode enumType = execute(connection, """
                { __type(name: "CustomerStatus") {
                    name kind enumValues { name description isDeprecated deprecationReason __typename }
                  } }
                """, "", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.introspectionTrustedContext("reader"));
        assertEquals("CustomerStatus", enumType.at("/data/__type/name").asText(), enumType::toString);
        assertEquals("ENUM", enumType.at("/data/__type/kind").asText(), enumType::toString);
        assertEquals(2, enumType.at("/data/__type/enumValues").size(), enumType::toString);
        assertEquals("ACTIVE", enumType.at("/data/__type/enumValues/0/name").asText(), enumType::toString);
        assertEquals("Customer can place orders.",
                enumType.at("/data/__type/enumValues/0/description").asText(), enumType::toString);
        assertFalse(enumType.at("/data/__type/enumValues/0/isDeprecated").asBoolean(), enumType::toString);
        assertTrue(enumType.at("/data/__type/enumValues/0/deprecationReason").isNull(), enumType::toString);
        assertEquals("INACTIVE", enumType.at("/data/__type/enumValues/1/name").asText(), enumType::toString);
        assertEquals("__EnumValue", enumType.at("/data/__type/enumValues/0/__typename").asText(),
                enumType::toString);

        JsonNode allEnumValues = execute(connection, """
                { __type(name: "CustomerStatus") {
                    enumValues(includeDeprecated: true) {
                      name description isDeprecated deprecationReason
                    }
                  } }
                """, "", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.introspectionTrustedContext("reader"));
        assertEquals(3, allEnumValues.at("/data/__type/enumValues").size(), allEnumValues::toString);
        assertEquals("LEGACY", allEnumValues.at("/data/__type/enumValues/2/name").asText(),
                allEnumValues::toString);
        assertEquals("Historic status retained for compatibility.",
                allEnumValues.at("/data/__type/enumValues/2/description").asText(), allEnumValues::toString);
        assertTrue(allEnumValues.at("/data/__type/enumValues/2/isDeprecated").asBoolean(),
                allEnumValues::toString);
        assertEquals("Use INACTIVE.",
                allEnumValues.at("/data/__type/enumValues/2/deprecationReason").asText(),
                allEnumValues::toString);

        JsonNode outputFieldMetadata = execute(connection, """
                query OutputFieldMetadata($includeDeprecated: Boolean!) {
                  current: __type(name: "Customer") {
                    fields { name description isDeprecated deprecationReason }
                  }
                  all: __type(name: "Customer") {
                    fields(includeDeprecated: $includeDeprecated) {
                      name description isDeprecated deprecationReason
                    }
                  }
                  node: __type(name: "Node") {
                    fields { name description isDeprecated deprecationReason }
                  }
                }
                """, "OutputFieldMetadata", "{\"includeDeprecated\":true}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.introspectionTrustedContext("reader"));
        assertFalse(outputFieldMetadata.at("/data/current/fields").findValuesAsText("name")
                .contains("nickname"), outputFieldMetadata::toString);
        JsonNode authoredId = fieldNamed(outputFieldMetadata.at("/data/all/fields"), "id");
        assertEquals("Customer row identifier.", authoredId.at("/description").asText(),
                outputFieldMetadata::toString);
        assertFalse(authoredId.at("/isDeprecated").asBoolean(), outputFieldMetadata::toString);
        assertTrue(authoredId.at("/deprecationReason").isNull(), outputFieldMetadata::toString);
        JsonNode authoredNickname = fieldNamed(outputFieldMetadata.at("/data/all/fields"), "nickname");
        assertEquals("Historic customer nickname.", authoredNickname.at("/description").asText(),
                outputFieldMetadata::toString);
        assertTrue(authoredNickname.at("/isDeprecated").asBoolean(), outputFieldMetadata::toString);
        assertEquals("Use name.", authoredNickname.at("/deprecationReason").asText(),
                outputFieldMetadata::toString);
        JsonNode nodeId = fieldNamed(outputFieldMetadata.at("/data/node/fields"), "id");
        assertEquals("Stable row identifier.", nodeId.at("/description").asText(),
                outputFieldMetadata::toString);
        assertFalse(nodeId.at("/isDeprecated").asBoolean(), outputFieldMetadata::toString);
        assertTrue(nodeId.at("/deprecationReason").isNull(), outputFieldMetadata::toString);

        JsonNode abstractTypes = execute(connection, """
                { node: __type(name: "Node") {
                    name kind description fields { name type { kind name ofType { kind name } } }
                    interfaces { name kind } possibleTypes { name kind }
                  }
                  search: __type(name: "SearchResult") {
                    name kind description fields { name } possibleTypes { name kind }
                  }
                  customerObject: __type(name: "Customer") { interfaces { name kind } }
                  queryObject: __type(name: "Query") {
                    fields { name type { kind name ofType { kind name } } }
                  }
                }
                """, "", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.introspectionTrustedContext("reader"));
        assertEquals("INTERFACE", abstractTypes.at("/data/node/kind").asText(), abstractTypes::toString);
        assertEquals("An object with a stable integer identifier.",
                abstractTypes.at("/data/node/description").asText(), abstractTypes::toString);
        assertEquals(2, abstractTypes.at("/data/node/possibleTypes").size(), abstractTypes::toString);
        assertEquals("UNION", abstractTypes.at("/data/search/kind").asText(), abstractTypes::toString);
        assertTrue(abstractTypes.at("/data/search/fields").isNull(), abstractTypes::toString);
        assertEquals(2, abstractTypes.at("/data/search/possibleTypes").size(), abstractTypes::toString);
        assertEquals("Node", abstractTypes.at("/data/customerObject/interfaces/0/name").asText(),
                abstractTypes::toString);
        assertEquals("INTERFACE", fieldNamed(abstractTypes.at("/data/queryObject/fields"), "nodeCustomer")
                .at("/type/kind").asText(), abstractTypes::toString);
        assertEquals("UNION", fieldNamed(abstractTypes.at("/data/queryObject/fields"), "searchCustomer")
                .at("/type/kind").asText(), abstractTypes::toString);

        JsonNode invalidNestedType = execute(connection, """
                { __type(name: "Customer") {
                    fields { type { ofType { name { invalid } } } }
                  } }
                """, "", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.introspectionTrustedContext("reader"));
        assertTrue(invalidNestedType.at("/errors/0/message").asText()
                .contains("introspection scalar field has an invalid selection"), invalidNestedType::toString);

        JsonNode invalidIncludeDeprecated = execute(connection, """
                { __type(name: "Customer") { fields(includeDeprecated: 1) { name } } }
                """, "", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.introspectionTrustedContext("reader"));
        assertTrue(invalidIncludeDeprecated.at("/errors/0/message").asText()
                .contains("argument '__Type.fields.includeDeprecated' must be Boolean"),
                invalidIncludeDeprecated::toString);
    }

    private static JsonNode fieldNamed(JsonNode fields, String name) {
        for (JsonNode field : fields) {
            if (name.equals(field.path("name").asText())) return field;
        }
        throw new AssertionError("missing introspection field '" + name + "' in " + fields);
    }

    @Test
    void installedEntryPointServesTheStandardNamedTypeSurfaceFromOneGenericRoutine(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
        JsonNode response = execute(connection, """
                query StandardNamedTypes {
                  objectType: __type(name: "Customer") { ...NamedType }
                  scalarType: __type(name: "String") { ...NamedType }
                  inputType: __type(name: "CustomerFilter") { ...NamedType }
                  enumType: __type(name: "SortDirection") { ...NamedType }
                }
                fragment NamedType on __Type {
                  typeName: name typeKind: kind description
                  fields { name }
                  inputFields { name }
                  interfaces { kind name }
                  enumValues { name }
                  possibleTypes { kind name }
                  specifiedByURL isOneOf __typename
                }
                """, "StandardNamedTypes", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.introspectionTrustedContext("reader"));

        assertEquals("Customer", response.at("/data/objectType/typeName").asText(), response::toString);
        assertEquals("OBJECT", response.at("/data/objectType/typeKind").asText(), response::toString);
        assertEquals("id", fieldNamed(response.at("/data/objectType/fields"), "id").at("/name").asText(),
                response::toString);
        assertEquals("Node", response.at("/data/objectType/interfaces/0/name").asText(), response::toString);
        assertTrue(response.at("/data/objectType/inputFields").isNull(), response::toString);
        assertTrue(response.at("/data/objectType/enumValues").isNull(), response::toString);
        assertTrue(response.at("/data/objectType/possibleTypes").isNull(), response::toString);

        assertEquals("SCALAR", response.at("/data/scalarType/typeKind").asText(), response::toString);
        assertTrue(response.at("/data/scalarType/fields").isNull(), response::toString);
        assertTrue(response.at("/data/scalarType/inputFields").isNull(), response::toString);
        assertTrue(response.at("/data/scalarType/interfaces").isNull(), response::toString);
        assertTrue(response.at("/data/scalarType/specifiedByURL").isNull(), response::toString);

        assertEquals("INPUT_OBJECT", response.at("/data/inputType/typeKind").asText(), response::toString);
        assertEquals("name", fieldNamed(response.at("/data/inputType/inputFields"), "name").at("/name").asText(),
                response::toString);
        assertFalse(response.at("/data/inputType/isOneOf").asBoolean(), response::toString);
        assertTrue(response.at("/data/inputType/fields").isNull(), response::toString);

        assertEquals("ENUM", response.at("/data/enumType/typeKind").asText(), response::toString);
        assertEquals("ASC", response.at("/data/enumType/enumValues/0/name").asText(), response::toString);
        assertEquals("DESC", response.at("/data/enumType/enumValues/1/name").asText(), response::toString);
        assertTrue(response.at("/data/enumType/fields").isNull(), response::toString);
        assertEquals("__Type", response.at("/data/enumType/__typename").asText(), response::toString);
    }

    @Test
    void installedEntryPointDescribesTheIntrospectionSchemaThroughTheGenericRuntime(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);

        JsonNode response = execute(connection, DatabaseEngineIntrospectionMetaContract.QUERY,
                "IntrospectionMetaSchema", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.introspectionTrustedContext("reader"));

        DatabaseEngineIntrospectionMetaContract.assertResponse(response);
    }

    @Test
    void installedEntryPointServesGeneratedInputTypeIntrospectionInBoundedRequests(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
        String modelHash = DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml");
        String trustedContext = DatabaseEngineTestRequestContract.introspectionTrustedContext("reader");

        JsonNode customerFilter = execute(connection, """
                query InputFields($includeDeprecated: Boolean!) {
                  __type(name: "CustomerFilter") {
                    name kind inputFields(includeDeprecated: $includeDeprecated) { name type { kind name } }
                  }
                }
                """, "InputFields", "{\"includeDeprecated\":false}", "reader", false, modelHash, trustedContext);
        assertEquals("INPUT_OBJECT", customerFilter.at("/data/__type/kind").asText(), customerFilter::toString);
        JsonNode customerNameFilter = fieldNamed(customerFilter.at("/data/__type/inputFields"), "name");
        assertEquals("INPUT_OBJECT", customerNameFilter.at("/type/kind").asText(), customerFilter::toString);
        assertEquals("StringFilter", customerNameFilter.at("/type/name").asText(), customerFilter::toString);

        JsonNode stringFilter = execute(connection, """
                { __type(name: "StringFilter") {
                    name kind inputFields { name type { kind name ofType { kind name ofType { kind name } } } }
                  } }
                """, "", "{}", "reader", false, modelHash, trustedContext);
        assertEquals("INPUT_OBJECT", stringFilter.at("/data/__type/kind").asText(), stringFilter::toString);
        JsonNode stringInFilter = fieldNamed(stringFilter.at("/data/__type/inputFields"), "in");
        assertEquals("LIST", stringInFilter.at("/type/kind").asText(), stringFilter::toString);
        assertEquals("NON_NULL", stringInFilter.at("/type/ofType/kind").asText(), stringFilter::toString);
        assertEquals("SCALAR", stringInFilter.at("/type/ofType/ofType/kind").asText(), stringFilter::toString);
        assertEquals("String", stringInFilter.at("/type/ofType/ofType/name").asText(), stringFilter::toString);

        JsonNode customerStatusFilter = execute(connection, """
                { __type(name: "CustomerStatusFilter") {
                    name kind inputFields { name type { kind name ofType { kind name ofType { kind name } } } }
                  } }
                """, "", "{}", "reader", false, modelHash, trustedContext);
        assertEquals("INPUT_OBJECT", customerStatusFilter.at("/data/__type/kind").asText(),
                customerStatusFilter::toString);
        JsonNode statusEqualsFilter = fieldNamed(customerStatusFilter.at("/data/__type/inputFields"), "eq");
        assertEquals("ENUM", statusEqualsFilter.at("/type/kind").asText(), customerStatusFilter::toString);
        assertEquals("CustomerStatus", statusEqualsFilter.at("/type/name").asText(),
                customerStatusFilter::toString);
        JsonNode statusInFilter = fieldNamed(customerStatusFilter.at("/data/__type/inputFields"), "in");
        assertEquals("LIST", statusInFilter.at("/type/kind").asText(), customerStatusFilter::toString);
        assertEquals("NON_NULL", statusInFilter.at("/type/ofType/kind").asText(), customerStatusFilter::toString);
        assertEquals("ENUM", statusInFilter.at("/type/ofType/ofType/kind").asText(),
                customerStatusFilter::toString);
        assertEquals("CustomerStatus", statusInFilter.at("/type/ofType/ofType/name").asText(),
                customerStatusFilter::toString);
        JsonNode statusIsNullFilter = fieldNamed(customerStatusFilter.at("/data/__type/inputFields"), "isNull");
        assertEquals("SCALAR", statusIsNullFilter.at("/type/kind").asText(), customerStatusFilter::toString);
        assertEquals("Boolean", statusIsNullFilter.at("/type/name").asText(), customerStatusFilter::toString);

        JsonNode orderBy = execute(connection, """
                { __type(name: "CustomerOrderBy") {
                    name kind inputFields { name type { kind name } }
                  } }
                """, "", "{}", "reader", false, modelHash, trustedContext);
        assertEquals("INPUT_OBJECT", orderBy.at("/data/__type/kind").asText(), orderBy::toString);
        JsonNode customerOrderByName = fieldNamed(orderBy.at("/data/__type/inputFields"), "name");
        assertEquals("ENUM", customerOrderByName.at("/type/kind").asText(), orderBy::toString);
        assertEquals("SortDirection", customerOrderByName.at("/type/name").asText(), orderBy::toString);

        JsonNode sortDirection = execute(connection, """
                query EnumValues($includeDeprecated: Boolean!) {
                  __type(name: "SortDirection") {
                    name kind
                    enumValues(includeDeprecated: $includeDeprecated) {
                      name description isDeprecated deprecationReason __typename
                    }
                  }
                }
                """, "EnumValues", "{\"includeDeprecated\":true}", "reader", false, modelHash, trustedContext);
        assertEquals("SortDirection", sortDirection.at("/data/__type/name").asText(), sortDirection::toString);
        assertEquals("ENUM", sortDirection.at("/data/__type/kind").asText(), sortDirection::toString);
        assertEquals("ASC", sortDirection.at("/data/__type/enumValues/0/name").asText(), sortDirection::toString);
        assertEquals("DESC", sortDirection.at("/data/__type/enumValues/1/name").asText(), sortDirection::toString);
        assertTrue(sortDirection.at("/data/__type/enumValues/0/description").isNull(), sortDirection::toString);
        assertFalse(sortDirection.at("/data/__type/enumValues/0/isDeprecated").asBoolean(), sortDirection::toString);
        assertTrue(sortDirection.at("/data/__type/enumValues/0/deprecationReason").isNull(), sortDirection::toString);
        assertEquals("__EnumValue", sortDirection.at("/data/__type/enumValues/0/__typename").asText(),
                sortDirection::toString);

        String tooBroadQuery = "{";
        int fieldIndex = 0;
        while (fieldIndex < 145) {
            tooBroadQuery = tooBroadQuery + " f" + fieldIndex;
            fieldIndex++;
        }
        JsonNode tooBroad = execute(connection, tooBroadQuery + "}", "", "{}", "reader", false, modelHash,
                DatabaseEngineTestRequestContract.trustedContext("reader"));
        assertTrue(tooBroad.at("/errors/0/message").asText().contains("semantic work budget"), tooBroad::toString);
    }

    @Test
    void installedEntryPointPropagatesDatabaseNullForNonNullPointLeaf(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE commerce.customers ALTER COLUMN name DROP NOT NULL");
            statement.executeUpdate("UPDATE commerce.customers SET name = NULL WHERE id = 7");
        }

        JsonNode response = execute(connection, """
                { brokenCustomer: customer(id: 7) { name }
                  healthyCountry: country(code: "NL") { code } }
                """, "", "{}", "reader");

        assertTrue(response.at("/data/brokenCustomer").isNull(), response::toString);
        assertEquals("NL", response.at("/data/healthyCountry/code").asText(), response::toString);
        assertTrue(response.at("/errors/0/message").asText()
                .contains("Cannot return null for non-nullable field Customer.name"), response::toString);
        assertEquals("brokenCustomer", response.at("/errors/0/path/0").asText(), response::toString);
        assertEquals("name", response.at("/errors/0/path/1").asText(), response::toString);
    }

    @Test
    void installedEntryPointCompletesAndValidatesDeclaredOutputEnum(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);

        JsonNode valid = execute(connection, """
                { direct: customer(id: 7) { status nullableStatus }
                  customerFeed(first: 1) { edges { node { status } } } }
                """, "", "{}", "reader");
        assertEquals("ACTIVE", valid.at("/data/direct/status").asText(), valid::toString);
        assertEquals("ACTIVE", valid.at("/data/direct/nullableStatus").asText(), valid::toString);
        assertEquals("ACTIVE", valid.at("/data/customerFeed/edges/0/node/status").asText(), valid::toString);

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE commerce.customers SET status = 'UNKNOWN' WHERE id = 7");
        }
        String query = """
                { brokenCustomer: customer(id: 7) { status }
                  healthyCountry: country(code: "NL") { code } }
                """;
        JsonNode invalid = execute(connection, query, "", "{}", "reader");

        assertTrue(invalid.at("/data/brokenCustomer").isNull(), invalid::toString);
        assertEquals("NL", invalid.at("/data/healthyCountry/code").asText(), invalid::toString);
        assertTrue(invalid.at("/errors/0/message").asText()
                .contains("Enum 'CustomerStatus' cannot represent the stored value"), invalid::toString);
        assertEquals("brokenCustomer", invalid.at("/errors/0/path/0").asText(), invalid::toString);
        assertEquals("status", invalid.at("/errors/0/path/1").asText(), invalid::toString);
        assertEquals(1, invalid.at("/errors/0/locations/0/line").asInt(), invalid::toString);
        assertEquals(query.indexOf("status") + 1, invalid.at("/errors/0/locations/0/column").asInt(),
                invalid::toString);

        String nullableQuery = "{ customer(id: 7) { id nullableStatus } }";
        JsonNode nullableInvalid = execute(connection, nullableQuery, "", "{}", "reader");
        assertEquals(7, nullableInvalid.at("/data/customer/id").asInt(), nullableInvalid::toString);
        assertTrue(nullableInvalid.at("/data/customer/nullableStatus").isNull(), nullableInvalid::toString);
        assertTrue(nullableInvalid.at("/errors/0/message").asText()
                .contains("Enum 'CustomerStatus' cannot represent the stored value"), nullableInvalid::toString);
        assertEquals("customer", nullableInvalid.at("/errors/0/path/0").asText(), nullableInvalid::toString);
        assertEquals("nullableStatus", nullableInvalid.at("/errors/0/path/1").asText(), nullableInvalid::toString);

        String connectionQuery = "{ customerFeed(first: 1) { edges { node { status } } } }";
        JsonNode connectionInvalid = execute(connection, connectionQuery, "", "{}", "reader");
        assertTrue(connectionInvalid.at("/data").isNull(), connectionInvalid::toString);
        assertEquals("customerFeed", connectionInvalid.at("/errors/0/path/0").asText(),
                connectionInvalid::toString);
        assertEquals("edges", connectionInvalid.at("/errors/0/path/1").asText(), connectionInvalid::toString);
        assertEquals(0, connectionInvalid.at("/errors/0/path/2").asInt(), connectionInvalid::toString);
        assertEquals("node", connectionInvalid.at("/errors/0/path/3").asText(), connectionInvalid::toString);
        assertEquals("status", connectionInvalid.at("/errors/0/path/4").asText(), connectionInvalid::toString);
    }

    @Test
    void installedEntryPointBubblesNonNullRelationElementToNullablePointRoot(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE commerce.orders ALTER COLUMN reference DROP NOT NULL");
            statement.executeUpdate("UPDATE commerce.orders SET reference = NULL WHERE id = 70");
        }

        String query = """
                { brokenCustomer: customer(id: 7) { orders { reference } }
                  healthyCountry: country(code: "NL") { code } }
                """;
        JsonNode response = execute(connection, query, "", "{}", "reader");

        assertTrue(response.at("/data/brokenCustomer").isNull(), response::toString);
        assertEquals("NL", response.at("/data/healthyCountry/code").asText(), response::toString);
        assertTrue(response.at("/errors/0/message").asText()
                .contains("Cannot return null for non-nullable field Order.reference"), response::toString);
        assertEquals("brokenCustomer", response.at("/errors/0/path/0").asText(), response::toString);
        assertEquals("orders", response.at("/errors/0/path/1").asText(), response::toString);
        assertEquals(0, response.at("/errors/0/path/2").asInt(), response::toString);
        assertEquals("reference", response.at("/errors/0/path/3").asText(), response::toString);
        assertEquals(1, response.at("/errors/0/locations/0/line").asInt(), response::toString);
        assertEquals(query.indexOf("reference") + 1, response.at("/errors/0/locations/0/column").asInt(),
                response::toString);
    }

    @Test
    void installedEntryPointPropagatesNonNullConnectionNodeThroughTheConnectionRoot(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE commerce.customers ALTER COLUMN name DROP NOT NULL");
            statement.executeUpdate("UPDATE commerce.customers SET name = NULL WHERE id = 7");
        }

        String query = """
                { customerFeed(first: 1) { edges { node { name } } }
                  healthyCountry: country(code: "NL") { code } }
                """;
        JsonNode response = execute(connection, query, "", "{}", "reader");

        assertTrue(response.at("/data").isNull(), response::toString);
        assertTrue(response.at("/errors/0/message").asText()
                .contains("Cannot return null for non-nullable field Customer.name"), response::toString);
        assertEquals("customerFeed", response.at("/errors/0/path/0").asText(), response::toString);
        assertEquals("edges", response.at("/errors/0/path/1").asText(), response::toString);
        assertEquals(0, response.at("/errors/0/path/2").asInt(), response::toString);
        assertEquals("node", response.at("/errors/0/path/3").asText(), response::toString);
        assertEquals("name", response.at("/errors/0/path/4").asText(), response::toString);
        assertEquals(1, response.at("/errors/0/locations/0/line").asInt(), response::toString);
        assertEquals(query.indexOf("name") + 1, response.at("/errors/0/locations/0/column").asInt(),
                response::toString);
    }

    @Test
    void installedEntryPointStopsNonNullLeafPropagationAtNullableRelation(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE commerce.customers ALTER COLUMN name DROP NOT NULL");
            statement.executeUpdate("UPDATE commerce.customers SET name = NULL WHERE id = 7");
        }

        String query = """
                { customer(id: 7) { id orders { id nullableCustomer { name } } }
                  healthyCountry: country(code: "NL") { code } }
                """;
        JsonNode response = execute(connection, query, "", "{}", "reader");

        assertEquals(7, response.at("/data/customer/id").asInt(), response::toString);
        assertEquals(70, response.at("/data/customer/orders/0/id").asInt(), response::toString);
        assertTrue(response.at("/data/customer/orders/0/nullableCustomer").isNull(), response::toString);
        assertEquals("NL", response.at("/data/healthyCountry/code").asText(), response::toString);
        assertTrue(response.at("/errors/0/message").asText()
                .contains("Cannot return null for non-nullable field Customer.name"), response::toString);
        assertEquals("customer", response.at("/errors/0/path/0").asText(), response::toString);
        assertEquals("orders", response.at("/errors/0/path/1").asText(), response::toString);
        assertEquals(0, response.at("/errors/0/path/2").asInt(), response::toString);
        assertEquals("nullableCustomer", response.at("/errors/0/path/3").asText(), response::toString);
        assertEquals("name", response.at("/errors/0/path/4").asText(), response::toString);
        assertEquals(1, response.at("/errors/0/locations/0/line").asInt(), response::toString);
        assertEquals(query.indexOf("name") + 1, response.at("/errors/0/locations/0/column").asInt(),
                response::toString);
    }

    @Test
    void installedEntryPointServesTwoHopRelationWithinModelBudget(TitanTestContext context) throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);

        JsonNode twoHops = execute(connection, """
                { customer(id: 7) { orders { customer { name } } } }
                """, "", "{}", "reader");
        assertEquals("Northwind", twoHops.at("/data/customer/orders/0/customer/name").asText(), twoHops::toString);

        JsonNode mergedNestedRelation = execute(connection, """
                { customer(id: 7) { orders { nullableCustomer { name } nullableCustomer { id } } } }
                """, "", "{}", "reader");
        assertEquals("Northwind", mergedNestedRelation.at("/data/customer/orders/0/nullableCustomer/name").asText(),
                mergedNestedRelation::toString);
        assertEquals(7, mergedNestedRelation.at("/data/customer/orders/0/nullableCustomer/id").asInt(),
                mergedNestedRelation::toString);

        JsonNode fragmentMergedNestedRelation = execute(connection, """
                { customer(id: 7) { orders { ...OrderName } orders { ...OrderId } } }
                fragment OrderName on Order { customer { name } }
                fragment OrderId on Order { customer { id } }
                """, "", "{}", "reader");
        assertEquals("Northwind", fragmentMergedNestedRelation.at("/data/customer/orders/0/customer/name").asText(),
                fragmentMergedNestedRelation::toString);
        assertEquals(7, fragmentMergedNestedRelation.at("/data/customer/orders/0/customer/id").asInt(),
                fragmentMergedNestedRelation::toString);

        JsonNode inlineFragmentMergedNestedRelation = execute(connection, """
                { customer(id: 7) {
                  orders { ... on Order { customer { name } } }
                  orders { ... on Order { customer { id } } }
                } }
                """, "", "{}", "reader");
        assertEquals("Northwind", inlineFragmentMergedNestedRelation.at("/data/customer/orders/0/customer/name").asText(),
                inlineFragmentMergedNestedRelation::toString);
        assertEquals(7, inlineFragmentMergedNestedRelation.at("/data/customer/orders/0/customer/id").asInt(),
                inlineFragmentMergedNestedRelation::toString);

        JsonNode tooDeep = execute(connection, """
                { customer(id: 7) { orders { customer { orders { id } } } } }
                """, "", "{}", "reader");
        assertTrue(tooDeep.at("/errors/0/message").asText().contains("exceeds selection hop budget of 2"),
                tooDeep::toString);
    }

    @Test
    void installedEntryPointServesBoundedRelationTreeFromConnectionNode(TitanTestContext context) throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);

        JsonNode response = execute(connection, """
                { customerFeed(first: 1) {
                    edges { node {
                      customerId: id
                      purchases: orders { customer { id name } }
                    } }
                  } }
                """, "", "{}", "reader");

        assertEquals(7, response.at("/data/customerFeed/edges/0/node/customerId").asInt(), response::toString);
        assertEquals("Northwind", response.at("/data/customerFeed/edges/0/node/purchases/0/customer/name").asText(),
                response::toString);
        assertEquals(7, response.at("/data/customerFeed/edges/0/node/purchases/0/customer/id").asInt(),
                response::toString);

        JsonNode mergedNestedRelation = execute(connection, """
                { customerFeed(first: 1) {
                    edges { node {
                      orders { customer { name } }
                      orders { customer { id } }
                    } }
                  } }
                """, "", "{}", "reader");
        assertEquals("Northwind", mergedNestedRelation.at("/data/customerFeed/edges/0/node/orders/0/customer/name").asText(),
                mergedNestedRelation::toString);
        assertEquals(7, mergedNestedRelation.at("/data/customerFeed/edges/0/node/orders/0/customer/id").asInt(),
                mergedNestedRelation::toString);

        JsonNode fragmentMergedNestedRelation = execute(connection, """
                { customerFeed(first: 1) { edges { node {
                  orders { ...OrderName }
                  orders { ...OrderId }
                } } } }
                fragment OrderName on Order { customer { name } }
                fragment OrderId on Order { customer { id } }
                """, "", "{}", "reader");
        assertEquals("Northwind", fragmentMergedNestedRelation.at("/data/customerFeed/edges/0/node/orders/0/customer/name").asText(),
                fragmentMergedNestedRelation::toString);
        assertEquals(7, fragmentMergedNestedRelation.at("/data/customerFeed/edges/0/node/orders/0/customer/id").asInt(),
                fragmentMergedNestedRelation::toString);

        JsonNode inlineFragmentMergedNestedRelation = execute(connection, """
                { customerFeed(first: 1) { edges { node {
                  orders { ... on Order { customer { name } } }
                  orders { ... on Order { customer { id } } }
                } } } }
                """, "", "{}", "reader");
        assertEquals("Northwind", inlineFragmentMergedNestedRelation.at("/data/customerFeed/edges/0/node/orders/0/customer/name").asText(),
                inlineFragmentMergedNestedRelation::toString);
        assertEquals(7, inlineFragmentMergedNestedRelation.at("/data/customerFeed/edges/0/node/orders/0/customer/id").asInt(),
                inlineFragmentMergedNestedRelation::toString);

        JsonNode mergedConnectionRoot = execute(connection, """
                { customerFeed(first: 1) { edges { node { id } } }
                  customerFeed(first: 1) { totalCount } }
                """, "", "{}", "reader");
        assertEquals(7, mergedConnectionRoot.at("/data/customerFeed/edges/0/node/id").asInt(),
                mergedConnectionRoot::toString);
        assertEquals(2, mergedConnectionRoot.at("/data/customerFeed/totalCount").asInt(),
                mergedConnectionRoot::toString);

        JsonNode reorderedArgumentMerge = execute(connection, """
                { customerFeed(first: 1, after: null,
                    filter: { name: { startsWith: "N", endsWith: "wind" } }) { edges { node { id } } }
                  customerFeed(filter: { name: { endsWith: "wind", startsWith: "N" } },
                    after: null, first: 1) { edges { node { name } } } }
                """, "", "{}", "reader");
        assertEquals(7, reorderedArgumentMerge.at("/data/customerFeed/edges/0/node/id").asInt(),
                reorderedArgumentMerge::toString);
        assertEquals("Northwind", reorderedArgumentMerge.at("/data/customerFeed/edges/0/node/name").asText(),
                reorderedArgumentMerge::toString);

        JsonNode reorderedArgumentConflict = execute(connection, """
                { customerFeed(first: 1, after: null) { totalCount }
                  customerFeed(after: null, first: 2) { totalCount } }
                """, "", "{}", "reader");
        assertTrue(reorderedArgumentConflict.at("/errors/0/message").asText().contains("conflicting fields"),
                reorderedArgumentConflict::toString);

        JsonNode conflictingConnectionAlias = execute(connection, """
                { customerFeed(first: 1) {
                    value: edges { cursor }
                    value: pageInfo { hasNextPage }
                  } }
                """, "", "{}", "reader");
        assertTrue(conflictingConnectionAlias.at("/errors/0/message").asText()
                        .contains("has conflicting fields"),
                conflictingConnectionAlias::toString);

        JsonNode conflictingEdgeAlias = execute(connection, """
                { customerFeed(first: 1) {
                    edges { value: cursor value: node { id } }
                  } }
                """, "", "{}", "reader");
        assertTrue(conflictingEdgeAlias.at("/errors/0/message").asText()
                        .contains("has conflicting fields"),
                conflictingEdgeAlias::toString);

        JsonNode conflictingPageInfoAlias = execute(connection, """
                { customerFeed(first: 1) {
                    pageInfo { value: hasNextPage value: endCursor }
                  } }
                """, "", "{}", "reader");
        assertTrue(conflictingPageInfoAlias.at("/errors/0/message").asText()
                        .contains("has conflicting fields"),
                conflictingPageInfoAlias::toString);

        JsonNode mergedConnectionRootChildren = execute(connection, """
                { customerFeed(first: 1) {
                    edges { node { id } }
                    pageInfo { hasNextPage }
                  }
                  customerFeed(first: 1) {
                    edges { node { name } }
                    pageInfo { endCursor }
                  } }
                """, "", "{}", "reader");
        assertEquals(7, mergedConnectionRootChildren.at("/data/customerFeed/edges/0/node/id").asInt(),
                mergedConnectionRootChildren::toString);
        assertEquals("Northwind", mergedConnectionRootChildren.at("/data/customerFeed/edges/0/node/name").asText(),
                mergedConnectionRootChildren::toString);
        assertTrue(mergedConnectionRootChildren.at("/data/customerFeed/pageInfo/hasNextPage").asBoolean(),
                mergedConnectionRootChildren::toString);
        assertTrue(mergedConnectionRootChildren.at("/data/customerFeed/pageInfo/endCursor").isTextual(),
                mergedConnectionRootChildren::toString);

        JsonNode fragmentMergedConnectionRoot = execute(connection, """
                { customerFeed(first: 1) { ... ConnectionEdges }
                  customerFeed(first: 1) { ... on CustomerConnection { totalCount } } }
                fragment ConnectionEdges on CustomerConnection { edges { node { id } } }
                """, "", "{}", "reader");
        assertEquals(7, fragmentMergedConnectionRoot.at("/data/customerFeed/edges/0/node/id").asInt(),
                fragmentMergedConnectionRoot::toString);
        assertEquals(2, fragmentMergedConnectionRoot.at("/data/customerFeed/totalCount").asInt(),
                fragmentMergedConnectionRoot::toString);

        JsonNode aliasedConnectionScalars = execute(connection, """
                { customerFeed(first: 1) {
                    visibleTotal: totalCount
                    exportedTotal: totalCount
                    connectionKind: __typename
                    exportedConnectionKind: __typename
                  } }
                """, "", "{}", "reader");
        assertEquals(2, aliasedConnectionScalars.at("/data/customerFeed/visibleTotal").asInt(),
                aliasedConnectionScalars::toString);
        assertEquals(2, aliasedConnectionScalars.at("/data/customerFeed/exportedTotal").asInt(),
                aliasedConnectionScalars::toString);
        assertEquals("CustomerConnection", aliasedConnectionScalars.at("/data/customerFeed/connectionKind").asText(),
                aliasedConnectionScalars::toString);
        assertEquals("CustomerConnection", aliasedConnectionScalars.at("/data/customerFeed/exportedConnectionKind").asText(),
                aliasedConnectionScalars::toString);

        JsonNode aliasedPageInfoScalars = execute(connection, """
                { customerFeed(first: 1) {
                    pageInfo {
                      next: hasNextPage
                      exportedNext: hasNextPage
                      cursor: endCursor
                      exportedCursor: endCursor
                      pageKind: __typename
                      exportedPageKind: __typename
                    }
                  } }
                """, "", "{}", "reader");
        assertTrue(aliasedPageInfoScalars.at("/data/customerFeed/pageInfo/next").asBoolean(),
                aliasedPageInfoScalars::toString);
        assertTrue(aliasedPageInfoScalars.at("/data/customerFeed/pageInfo/exportedNext").asBoolean(),
                aliasedPageInfoScalars::toString);
        assertTrue(aliasedPageInfoScalars.at("/data/customerFeed/pageInfo/cursor").isTextual(),
                aliasedPageInfoScalars::toString);
        assertEquals(aliasedPageInfoScalars.at("/data/customerFeed/pageInfo/cursor").asText(),
                aliasedPageInfoScalars.at("/data/customerFeed/pageInfo/exportedCursor").asText(),
                aliasedPageInfoScalars::toString);
        assertEquals("PageInfo", aliasedPageInfoScalars.at("/data/customerFeed/pageInfo/pageKind").asText(),
                aliasedPageInfoScalars::toString);
        assertEquals("PageInfo", aliasedPageInfoScalars.at("/data/customerFeed/pageInfo/exportedPageKind").asText(),
                aliasedPageInfoScalars::toString);

        JsonNode invalidAliasedConnectionScalar = execute(connection, """
                { customerFeed(first: 1) {
                    good: totalCount
                    malformed: totalCount(unused: true)
                  } }
                """, "", "{}", "reader");
        assertTrue(invalidAliasedConnectionScalar.at("/errors/0/message").asText()
                        .contains("field 'CustomerConnection.totalCount' has an unknown, duplicate, or malformed argument"),
                invalidAliasedConnectionScalar::toString);

        JsonNode invalidAliasedPageInfoScalar = execute(connection, """
                { customerFeed(first: 1) {
                    pageInfo { good: hasNextPage malformed: hasNextPage { nested } }
                  } }
                """, "", "{}", "reader");
        assertTrue(invalidAliasedPageInfoScalar.at("/errors/0/message").asText()
                        .contains("field 'PageInfo.hasNextPage' cannot have a selection"),
                invalidAliasedPageInfoScalar::toString);

        JsonNode aliasedEdgeScalars = execute(connection, """
                { customerFeed(first: 1) {
                    edges {
                      position: cursor
                      exportedPosition: cursor
                      edgeKind: __typename
                      exportedEdgeKind: __typename
                      node { id }
                    }
                  } }
                """, "", "{}", "reader");
        assertEquals(7, aliasedEdgeScalars.at("/data/customerFeed/edges/0/node/id").asInt(),
                aliasedEdgeScalars::toString);
        assertEquals(aliasedEdgeScalars.at("/data/customerFeed/edges/0/position").asText(),
                aliasedEdgeScalars.at("/data/customerFeed/edges/0/exportedPosition").asText(),
                aliasedEdgeScalars::toString);
        assertEquals("CustomerEdge", aliasedEdgeScalars.at("/data/customerFeed/edges/0/edgeKind").asText(),
                aliasedEdgeScalars::toString);
        assertEquals("CustomerEdge", aliasedEdgeScalars.at("/data/customerFeed/edges/0/exportedEdgeKind").asText(),
                aliasedEdgeScalars::toString);

        JsonNode invalidAliasedEdgeScalar = execute(connection, """
                { customerFeed(first: 1) {
                    edges { good: cursor malformed: cursor { nested } }
                  } }
                """, "", "{}", "reader");
        assertTrue(invalidAliasedEdgeScalar.at("/errors/0/message").asText()
                        .contains("field 'CustomerEdge.cursor' cannot have a selection"),
                invalidAliasedEdgeScalar::toString);
    }

    @Test
    void installedEntryPointRejectsUnpaginatedRelationAboveDatabaseRowBudget(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
        // Deployment seeds one Order for customer 7; add 100 to cross the static 100-row budget
        // by exactly one sentinel row without changing the generated schema or query shape.
        CommerceDatabaseEngineDeployment.addOrdersForCustomer(connection, 7, 701, 100);

        JsonNode response = execute(connection, "{ customer(id: 7) { orders { id } } }", "", "{}", "reader");
        assertTrue(response.at("/errors/0/message").asText().contains("Customer.orders"), response::toString);
        assertTrue(response.at("/errors/0/message").asText().contains("row budget of 100"), response::toString);
        assertTrue(response.at("/errors/0/path/0").asText().equals("customer"), response::toString);
        assertTrue(response.at("/errors/0/path/1").asText().equals("orders"), response::toString);
        assertTrue(response.at("/data/customer").isNull(), response::toString);
    }

    @Test
    void installedEntryPointServesGenericRelationRelayConnectionInsideTheRoutine(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
        CommerceDatabaseEngineDeployment.addOrdersForCustomer(connection, 7, 71, 1);

        JsonNode firstPage = execute(connection, """
                { customer(id: 7) { orderConnection(first: 1) {
                    connectionType: __typename
                    edges { edgeType: __typename cursor node { nodeType: __typename id reference } }
                    totalCount
                    pageInfo { pageType: __typename hasNextPage hasPreviousPage startCursor endCursor }
                  } } }
                """, "", "{}", "reader");
        assertEquals("OrderConnection", firstPage.at("/data/customer/orderConnection/connectionType").asText(),
                firstPage::toString);
        assertEquals("OrderEdge", firstPage.at("/data/customer/orderConnection/edges/0/edgeType").asText(),
                firstPage::toString);
        assertEquals("Order", firstPage.at("/data/customer/orderConnection/edges/0/node/nodeType").asText(),
                firstPage::toString);
        assertEquals(70, firstPage.at("/data/customer/orderConnection/edges/0/node/id").asInt(), firstPage::toString);
        assertEquals(2, firstPage.at("/data/customer/orderConnection/totalCount").asInt(), firstPage::toString);
        assertTrue(firstPage.at("/data/customer/orderConnection/pageInfo/hasNextPage").asBoolean(), firstPage::toString);
        assertFalse(firstPage.at("/data/customer/orderConnection/pageInfo/hasPreviousPage").asBoolean(), firstPage::toString);
        assertEquals("PageInfo", firstPage.at("/data/customer/orderConnection/pageInfo/pageType").asText(),
                firstPage::toString);

        String cursor = firstPage.at("/data/customer/orderConnection/pageInfo/endCursor").asText();
        JsonNode continuation = execute(connection, """
                { customer(id: 7) { orderConnection(first: 1, after: \"%s\") {
                    edges { node { id } } pageInfo { hasPreviousPage }
                  } } }
                """.formatted(cursor), "", "{}", "reader");
        assertEquals(71, continuation.at("/data/customer/orderConnection/edges/0/node/id").asInt(),
                continuation::toString);
        assertTrue(continuation.at("/data/customer/orderConnection/pageInfo/hasPreviousPage").asBoolean(),
                continuation::toString);

        JsonNode filtered = execute(connection, """
                { customer(id: 7) { orderConnection(orderId: 71, first: 10) {
                    edges { node { id reference } } totalCount
                  } } }
                """, "", "{}", "reader");
        assertEquals(71, filtered.at("/data/customer/orderConnection/edges/0/node/id").asInt(), filtered::toString);
        assertEquals(1, filtered.at("/data/customer/orderConnection/totalCount").asInt(), filtered::toString);

        JsonNode connectionNode = execute(connection, """
                { customers(first: 1) { edges { node { id orderConnection(first: 1) {
                    edges { node { id } } totalCount pageInfo { hasNextPage }
                  } } } } }
                """, "", "{}", "reader");
        assertEquals(70, connectionNode.at("/data/customers/edges/0/node/orderConnection/edges/0/node/id").asInt(),
                connectionNode::toString);
        assertEquals(2, connectionNode.at("/data/customers/edges/0/node/orderConnection/totalCount").asInt(),
                connectionNode::toString);

        JsonNode invalidVariableOnAbsentParent = execute(connection, """
                query RelationVariableType($first: String) {
                  customer(id: 999) {
                    orderConnection(first: $first) { edges { node { id } } }
                  }
                }
                """, "RelationVariableType", "{\"first\":\"1\"}", "reader");
        assertTrue(invalidVariableOnAbsentParent.at("/errors/0/message").asText()
                        .contains("argument 'Customer.orderConnection.first' cannot use variable '$first' declared as 'String' at 'Int'"),
                invalidVariableOnAbsentParent::toString);

        JsonNode invalidSelectionOnAbsentParent = execute(connection, """
                {
                  customer(id: 999) {
                    orderConnection(first: 1) { edges { node { missingField } } }
                  }
                }
                """, "", "{}", "reader");
        assertTrue(invalidSelectionOnAbsentParent.at("/errors/0/message").asText()
                        .contains("field 'Order.missingField' is not defined by the installed schema"),
                invalidSelectionOnAbsentParent::toString);
    }

    @Test
    void installedEntryPointRejectsAggregateNestedRelationWorkAboveDatabaseRowBudget(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
        // Ten customers with exactly 100 orders each stay below every per-relation sentinel.
        // Their ten page rows plus relation rows exceed the shared request allowance through one
        // root page and one relation batch, isolating row accounting from the statement cap.
        CommerceDatabaseEngineDeployment.addCustomers(connection, 9, 8);
        CommerceDatabaseEngineDeployment.addOrdersForCustomer(connection, 7, 701, 99);
        for (long customerId = 8; customerId <= 16; customerId++) {
            CommerceDatabaseEngineDeployment.addOrdersForCustomer(
                    connection, customerId, customerId * 1_000L, 100);
        }

        JsonNode response = execute(connection,
                "{ customers(first: 10) { edges { node { orders { id } } } } }", "", "{}", "reader");
        assertTrue(response.at("/errors/0/message").asText().contains("decoded application row budget of 1000"),
                response::toString);
        assertEquals("RESOURCE_LIMIT_ERROR", response.at("/errors/0/extensions/code").asText(), response::toString);
        assertTrue(response.path("data").isMissingNode(), response::toString);
    }

    @Test
    void installedEntryPointBatchesSixtyFiveParentRelationsInTwoFixedChunks(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
        // The seed supplies customers 7 and 8 and one order for customer 7. Add 63 customers and
        // one order for customers 8..71. The 65 selected parents require two 64-key chunks; the
        // former per-parent executor would exceed the request's 64-statement allowance.
        CommerceDatabaseEngineDeployment.addCustomers(connection, 9, 63);
        CommerceDatabaseEngineDeployment.addOneOrderPerCustomer(connection, 8, 64);

        JsonNode response = execute(connection,
                "{ customers(first: 65) { edges { node { id orders { id customer { id } } } } } }",
                "", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.executionMetricsTrustedContext("reader"));

        JsonNode edges = response.at("/data/customers/edges");
        assertEquals(65, edges.size(), response::toString);
        // One page, two Customer.orders chunks, then two Order.customer chunks.
        assertEquals(5, response.at("/extensions/titanExecution/applicationSqlStatements").asInt(),
                response::toString);
        // 65 page + 65 first-level + 65 second-level rows and one Relay sentinel reservation.
        assertEquals(196, response.at("/extensions/titanExecution/decodedApplicationRows").asLong(),
                response::toString);
        assertEquals(7, edges.get(0).at("/node/id").asLong(), response::toString);
        assertEquals(71, edges.get(64).at("/node/id").asLong(), response::toString);
        for (JsonNode edge : edges) {
            assertEquals(1, edge.at("/node/orders").size(), response::toString);
            assertEquals(edge.at("/node/id").asLong(),
                    edge.at("/node/orders/0/customer/id").asLong(), response::toString);
        }

        JsonNode duplicateNonPrimaryJoin = execute(connection,
                "{ customers(first: 2) { edges { node { id rankPeers { id sortRank } } } } }",
                "", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.executionMetricsTrustedContext("reader"));
        JsonNode peerEdges = duplicateNonPrimaryJoin.at("/data/customers/edges");
        assertEquals(2, peerEdges.size(), duplicateNonPrimaryJoin::toString);
        assertEquals(2, duplicateNonPrimaryJoin.at("/extensions/titanExecution/applicationSqlStatements").asInt(),
                duplicateNonPrimaryJoin::toString);
        assertEquals(9, duplicateNonPrimaryJoin.at("/extensions/titanExecution/decodedApplicationRows").asLong(),
                duplicateNonPrimaryJoin::toString);
        for (JsonNode edge : peerEdges) {
            assertEquals(3, edge.at("/node/rankPeers").size(), duplicateNonPrimaryJoin::toString);
            assertEquals(7, edge.at("/node/rankPeers/0/id").asInt(), duplicateNonPrimaryJoin::toString);
            assertEquals(8, edge.at("/node/rankPeers/1/id").asInt(), duplicateNonPrimaryJoin::toString);
            assertEquals(10, edge.at("/node/rankPeers/2/id").asInt(), duplicateNonPrimaryJoin::toString);
        }

        JsonNode partitionedUnpaginatedPlans = execute(connection, """
                { customers(first: 65) { edges { node {
                    id orderIds: orders { id } orderReferences: orders { reference }
                  } } } }
                """, "", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.executionMetricsTrustedContext("reader"));
        JsonNode partitionedRelationEdges = partitionedUnpaginatedPlans.at("/data/customers/edges");
        assertEquals(65, partitionedRelationEdges.size(), partitionedUnpaginatedPlans::toString);
        assertEquals(5,
                partitionedUnpaginatedPlans.at("/extensions/titanExecution/applicationSqlStatements").asInt(),
                partitionedUnpaginatedPlans::toString);
        assertEquals(196,
                partitionedUnpaginatedPlans.at("/extensions/titanExecution/decodedApplicationRows").asLong(),
                partitionedUnpaginatedPlans::toString);
        for (JsonNode edge : partitionedRelationEdges) {
            assertEquals(1, edge.at("/node/orderIds").size(), partitionedUnpaginatedPlans::toString);
            assertEquals(1, edge.at("/node/orderReferences").size(), partitionedUnpaginatedPlans::toString);
        }

        // Keep the unpaginated/deeper baseline dense, then remove the last fixture order so the
        // relation-Relay batches must preserve one active parent with zero matching children.
        CommerceDatabaseEngineDeployment.removeOrder(connection, 1_000_071L);

        JsonNode relayCounts = execute(connection, """
                { customers(first: 65) { edges { node {
                    id counted: orderConnection(status: OPEN) {
                      connectionType: __typename total: totalCount repeated: totalCount
                    }
                  } } } }
                """, "", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.executionMetricsTrustedContext("reader"));
        JsonNode countEdges = relayCounts.at("/data/customers/edges");
        assertEquals(65, countEdges.size(), relayCounts::toString);
        // One root page plus two fixed 64-parent count chunks; the former executor needed one
        // count statement per parent and breached the 64-statement request allowance.
        assertEquals(3, relayCounts.at("/extensions/titanExecution/applicationSqlStatements").asInt(),
                relayCounts::toString);
        assertEquals(131, relayCounts.at("/extensions/titanExecution/decodedApplicationRows").asLong(),
                relayCounts::toString);
        for (JsonNode edge : countEdges) {
            long customerId = edge.at("/node/id").asLong();
            assertEquals("OrderConnection", edge.at("/node/counted/connectionType").asText(),
                    relayCounts::toString);
            assertEquals(customerId == 71 ? 0 : 1,
                    edge.at("/node/counted/total").asInt(), relayCounts::toString);
            assertEquals(customerId == 71 ? 0 : 1,
                    edge.at("/node/counted/repeated").asInt(), relayCounts::toString);
        }

        JsonNode relayPages = execute(connection, """
                { customers(first: 65) { edges { node {
                    id paged: orderConnection(first: 1) {
                      edges { node { id customer { id } } }
                    }
                  } } } }
                """, "", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.executionMetricsTrustedContext("reader"));
        JsonNode pageEdges = relayPages.at("/data/customers/edges");
        assertEquals(65, pageEdges.size(), relayPages::toString);
        // One root page, two relation-page chunks, and one nested-customer chunk: the only
        // parent in the second page chunk has zero edges, so it schedules no nested work.
        assertEquals(4, relayPages.at("/extensions/titanExecution/applicationSqlStatements").asInt(),
                relayPages::toString);
        assertEquals(194, relayPages.at("/extensions/titanExecution/decodedApplicationRows").asLong(),
                relayPages::toString);
        for (JsonNode edge : pageEdges) {
            long customerId = edge.at("/node/id").asLong();
            if (customerId == 71) {
                assertEquals(0, edge.at("/node/paged/edges").size(), relayPages::toString);
                continue;
            }
            assertEquals(1, edge.at("/node/paged/edges").size(), relayPages::toString);
            assertEquals(customerId == 7 ? 70L : 1_000_000L + customerId,
                    edge.at("/node/paged/edges/0/node/id").asLong(), relayPages::toString);
            assertEquals(customerId,
                    edge.at("/node/paged/edges/0/node/customer/id").asLong(), relayPages::toString);
        }

        JsonNode partitionedRelayPlans = execute(connection, """
                query PartitionedRelationPlans($openStatus: OrderStatus!) {
                  customers(first: 65) { edges { node {
                    id
                    openPage: orderConnection(first: 1, status: $openStatus) {
                      edges { node { id } }
                    }
                    closedCount: orderConnection(status: CLOSED) { totalCount }
                  } } }
                }
                """, "PartitionedRelationPlans", "{\"openStatus\":\"OPEN\"}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.executionMetricsTrustedContext("reader"));
        JsonNode partitionedEdges = partitionedRelayPlans.at("/data/customers/edges");
        assertEquals(65, partitionedEdges.size(), partitionedRelayPlans::toString);
        // One root page, two OPEN page chunks, and two distinct CLOSED count chunks. Each alias
        // has its own AST/argument/selection identity; neither may fall back to per-parent reads.
        assertEquals(5, partitionedRelayPlans.at("/extensions/titanExecution/applicationSqlStatements").asInt(),
                partitionedRelayPlans::toString);
        assertEquals(195, partitionedRelayPlans.at("/extensions/titanExecution/decodedApplicationRows").asLong(),
                partitionedRelayPlans::toString);
        for (JsonNode edge : partitionedEdges) {
            long customerId = edge.at("/node/id").asLong();
            assertEquals(customerId == 71 ? 0 : 1, edge.at("/node/openPage/edges").size(),
                    partitionedRelayPlans::toString);
            assertEquals(0, edge.at("/node/closedCount/totalCount").asInt(),
                    partitionedRelayPlans::toString);
        }

        JsonNode forwardWindow = execute(connection, """
                { customers(first: 1) { edges { node {
                    paged: orderConnection(first: 1) {
                      edges { cursor node { id } }
                      pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                    }
                  } } } }
                """, "", "{}", "reader");
        assertEquals(70, forwardWindow.at("/data/customers/edges/0/node/paged/edges/0/node/id").asLong(),
                forwardWindow::toString);
        String forwardCursor = forwardWindow
                .at("/data/customers/edges/0/node/paged/edges/0/cursor").asText();
        assertFalse(forwardCursor.isBlank(), forwardWindow::toString);
        assertEquals(forwardCursor,
                forwardWindow.at("/data/customers/edges/0/node/paged/pageInfo/startCursor").asText(),
                forwardWindow::toString);
        assertEquals(forwardCursor,
                forwardWindow.at("/data/customers/edges/0/node/paged/pageInfo/endCursor").asText(),
                forwardWindow::toString);
        assertFalse(forwardWindow.at("/data/customers/edges/0/node/paged/pageInfo/hasNextPage").asBoolean(),
                forwardWindow::toString);
        assertFalse(forwardWindow.at("/data/customers/edges/0/node/paged/pageInfo/hasPreviousPage").asBoolean(),
                forwardWindow::toString);

        CommerceDatabaseEngineDeployment.addOneOrderPerCustomer(connection, 7, 65, 2_000_000L);
        JsonNode backwardPages = execute(connection, """
                { customers(first: 65) { edges { node {
                    id paged: orderConnection(last: 1) {
                      edges { node { id customer { id } } }
                    }
                  } } } }
                """, "", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.executionMetricsTrustedContext("reader"));
        JsonNode backwardEdges = backwardPages.at("/data/customers/edges");
        assertEquals(65, backwardEdges.size(), backwardPages::toString);
        assertEquals(5, backwardPages.at("/extensions/titanExecution/applicationSqlStatements").asInt(),
                backwardPages::toString);
        assertEquals(260, backwardPages.at("/extensions/titanExecution/decodedApplicationRows").asLong(),
                backwardPages::toString);
        for (JsonNode edge : backwardEdges) {
            long customerId = edge.at("/node/id").asLong();
            assertEquals(2_000_000L + customerId,
                    edge.at("/node/paged/edges/0/node/id").asLong(), backwardPages::toString);
            assertEquals(customerId, edge.at("/node/paged/edges/0/node/customer/id").asLong(),
                    backwardPages::toString);
        }
        JsonNode backwardWindow = execute(connection, """
                { customers(first: 1) { edges { node {
                    paged: orderConnection(last: 1) {
                      edges { cursor node { id } }
                      pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                    }
                  } } } }
                """, "", "{}", "reader");
        assertEquals(2_000_007L,
                backwardWindow.at("/data/customers/edges/0/node/paged/edges/0/node/id").asLong(),
                backwardWindow::toString);
        String customerSevenBefore = backwardWindow
                .at("/data/customers/edges/0/node/paged/edges/0/cursor").asText();
        assertFalse(customerSevenBefore.isBlank(), backwardWindow::toString);
        assertEquals(customerSevenBefore,
                backwardWindow.at("/data/customers/edges/0/node/paged/pageInfo/startCursor").asText(),
                backwardWindow::toString);
        assertEquals(customerSevenBefore,
                backwardWindow.at("/data/customers/edges/0/node/paged/pageInfo/endCursor").asText(),
                backwardWindow::toString);
        assertFalse(backwardWindow.at("/data/customers/edges/0/node/paged/pageInfo/hasNextPage").asBoolean(),
                backwardWindow::toString);
        assertTrue(backwardWindow.at("/data/customers/edges/0/node/paged/pageInfo/hasPreviousPage").asBoolean(),
                backwardWindow::toString);
        JsonNode boundedBackward = execute(connection, """
                { customers(first: 1) { edges { node {
                    paged: orderConnection(last: 1, before: "%s") {
                      edges { node { id } }
                      pageInfo { hasNextPage hasPreviousPage }
                    }
                  } } } }
                """.formatted(customerSevenBefore), "", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.executionMetricsTrustedContext("reader"));
        assertEquals(70, boundedBackward.at("/data/customers/edges/0/node/paged/edges/0/node/id").asLong(),
                boundedBackward::toString);
        assertTrue(boundedBackward.at("/data/customers/edges/0/node/paged/pageInfo/hasNextPage").asBoolean(),
                boundedBackward::toString);
        assertFalse(boundedBackward.at("/data/customers/edges/0/node/paged/pageInfo/hasPreviousPage").asBoolean(),
                boundedBackward::toString);
        assertEquals(3, boundedBackward.at("/extensions/titanExecution/applicationSqlStatements").asInt(),
                boundedBackward::toString);
        assertEquals(4, boundedBackward.at("/extensions/titanExecution/decodedApplicationRows").asLong(),
                boundedBackward::toString);

        // Exercise the inverse shape with 65 Order parents sharing one private customer_id join
        // key. The generated slot table must preserve duplicate keys and still use two chunks.
        CommerceDatabaseEngineDeployment.addOrdersForCustomer(connection, 7, 1_000, 64);
        JsonNode toOne = execute(connection,
                "{ orders(first: 65) { edges { node { id customer { id } } } } }",
                "", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                DatabaseEngineTestRequestContract.executionMetricsTrustedContext("reader"));
        JsonNode orderEdges = toOne.at("/data/orders/edges");
        assertEquals(65, orderEdges.size(), toOne::toString);
        assertEquals(3, toOne.at("/extensions/titanExecution/applicationSqlStatements").asInt(),
                toOne::toString);
        assertEquals(131, toOne.at("/extensions/titanExecution/decodedApplicationRows").asLong(),
                toOne::toString);
        for (JsonNode edge : orderEdges) {
            assertEquals(7, edge.at("/node/customer/id").asLong(), toOne::toString);
        }
    }

    @Test
    void installedEntryPointRejectsTheSixtyFifthApplicationStatement(TitanTestContext context) throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);

        JsonNode response = execute(connection, repeatedRenameCustomerQuery(22), "", "{}", "editor", true);
        assertTrue(response.at("/errors/0/message").asText()
                        .contains("application SQL statement budget of 64"), response::toString);
        assertEquals("RESOURCE_LIMIT_ERROR", response.at("/errors/0/extensions/code").asText(), response::toString);
        assertTrue(response.at("/data").isNull(), response::toString);
        assertEquals("Northwind", customerName(connection, 7), "the resource rejection must roll back prior roots");
    }

    @Test
    void installedEntryPointServesUnrelatedSchemaKeysPoliciesAndLiveRows(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);

        JsonNode typeNames = execute(connection, """
                { queryType: __typename customer(id: 7) {
                    customerType: __typename
                    orders { orderType: __typename }
                  } }
                """, "", "{}", "reader");
        assertEquals("Query", typeNames.at("/data/queryType").asText(), typeNames::toString);
        assertEquals("Customer", typeNames.at("/data/customer/customerType").asText(), typeNames::toString);
        assertEquals("Order", typeNames.at("/data/customer/orders/0/orderType").asText(), typeNames::toString);
        JsonNode invalidTypeName = execute(connection, "{ __typename(unused: true) }", "", "{}", "reader");
        assertTrue(invalidTypeName.at("/errors/0/message").asText().contains("__typename' has an argument"),
                invalidTypeName::toString);
        JsonNode nestedTypeName = execute(connection, "{ __typename { invalid } }", "", "{}", "reader");
        assertTrue(nestedTypeName.at("/errors/0/message").asText().contains("__typename' cannot have a selection"),
                nestedTypeName::toString);
        String nestedScalarQuery = "{ customer(id: 7) { name { invalid } } }";
        JsonNode nestedScalar = execute(connection, nestedScalarQuery, "", "{}", "reader");
        assertTrue(nestedScalar.at("/errors/0/message").asText().contains("Customer.name' cannot have a selection"),
                nestedScalar::toString);
        assertEquals(1, nestedScalar.at("/errors/0/locations/0/line").asInt(), nestedScalar::toString);
        assertEquals(nestedScalarQuery.indexOf("name") + 1,
                nestedScalar.at("/errors/0/locations/0/column").asInt(), nestedScalar::toString);

        JsonNode customer = execute(connection,
                """
                { customer(id: 7) {
                    id nodeId externalId sortRank name nickname active status nullableStatus
                    rating creditLimit verified
                  } }
                """, "", "{}", "reader");
        assertEquals(7, customer.at("/data/customer/id").asInt(), customer::toString);
        assertEquals("7001", customer.at("/data/customer/nodeId").asText(), customer::toString);
        assertEquals("7001", customer.at("/data/customer/externalId").asText(), customer::toString);
        assertEquals(10, customer.at("/data/customer/sortRank").asInt(), customer::toString);
        assertEquals("Northwind", customer.at("/data/customer/name").asText());
        assertTrue(customer.at("/data/customer/nickname").isNull());
        assertTrue(customer.at("/data/customer/active").asBoolean());
        assertEquals("ACTIVE", customer.at("/data/customer/status").asText(), customer::toString);
        assertEquals("ACTIVE", customer.at("/data/customer/nullableStatus").asText(), customer::toString);
        assertEquals(42, customer.at("/data/customer/rating").asInt());
        assertEquals(1250.5D, customer.at("/data/customer/creditLimit").asDouble(), customer::toString);
        assertTrue(customer.at("/data/customer/verified").isNull());

        JsonNode enumPointLiteral = execute(connection,
                "{ customerByIdAndStatus(id: 7, status: ACTIVE) { id status } }", "", "{}", "reader");
        assertEquals(7, enumPointLiteral.at("/data/customerByIdAndStatus/id").asInt(),
                enumPointLiteral::toString);
        assertEquals("ACTIVE", enumPointLiteral.at("/data/customerByIdAndStatus/status").asText(),
                enumPointLiteral::toString);

        JsonNode enumPointVariable = execute(connection, """
                query CustomerByStatus($status: CustomerStatus!) {
                  customerByIdAndStatus(id: 8, status: $status) { id status }
                }
                """, "CustomerByStatus", "{\"status\":\"INACTIVE\"}", "reader");
        assertEquals(8, enumPointVariable.at("/data/customerByIdAndStatus/id").asInt(),
                enumPointVariable::toString);
        assertEquals("INACTIVE", enumPointVariable.at("/data/customerByIdAndStatus/status").asText(),
                enumPointVariable::toString);

        JsonNode enumPointMismatch = execute(connection,
                "{ customerByIdAndStatus(id: 7, status: INACTIVE) { id } }", "", "{}", "reader");
        assertTrue(enumPointMismatch.at("/data/customerByIdAndStatus").isNull(), enumPointMismatch::toString);

        JsonNode quotedEnumPoint = execute(connection,
                "{ customerByIdAndStatus(id: 7, status: \"ACTIVE\") { id } }", "", "{}", "reader");
        assertTrue(quotedEnumPoint.at("/errors/0/message").asText()
                        .contains("cannot be coerced to 'CustomerStatus'"), quotedEnumPoint::toString);

        JsonNode unknownEnumPoint = execute(connection, """
                query CustomerByStatus($status: CustomerStatus!) {
                  customerByIdAndStatus(id: 8, status: $status) { id }
                }
                """, "CustomerByStatus", "{\"status\":\"UNKNOWN\"}", "reader");
        assertTrue(unknownEnumPoint.at("/errors/0/message").asText()
                        .contains("cannot be coerced to 'CustomerStatus'"), unknownEnumPoint::toString);

        JsonNode enumConnections = execute(connection, """
                query EnumConnections($customerStatus: CustomerStatus!, $orderStatus: OrderStatus!) {
                  customerFeed(first: 10, status: $customerStatus) {
                    edges { node { id status } }
                    totalCount
                  }
                  customer(id: 7) {
                    orderConnection(first: 10, status: $orderStatus) {
                      edges { node { id status } }
                      totalCount
                    }
                  }
                }
                """, "EnumConnections", "{\"customerStatus\":\"ACTIVE\",\"orderStatus\":\"OPEN\"}", "reader");
        assertEquals(1, enumConnections.at("/data/customerFeed/totalCount").asInt(), enumConnections::toString);
        assertEquals(7, enumConnections.at("/data/customerFeed/edges/0/node/id").asInt(),
                enumConnections::toString);
        assertEquals("ACTIVE", enumConnections.at("/data/customerFeed/edges/0/node/status").asText(),
                enumConnections::toString);
        assertEquals(1, enumConnections.at("/data/customer/orderConnection/totalCount").asInt(),
                enumConnections::toString);
        assertEquals(70, enumConnections.at("/data/customer/orderConnection/edges/0/node/id").asInt(),
                enumConnections::toString);
        assertEquals("OPEN", enumConnections.at("/data/customer/orderConnection/edges/0/node/status").asText(),
                enumConnections::toString);

        JsonNode closedOrders = execute(connection,
                "{ customer(id: 7) { orderConnection(first: 10, status: CLOSED) { totalCount } } }",
                "", "{}", "reader");
        assertEquals(0, closedOrders.at("/data/customer/orderConnection/totalCount").asInt(),
                closedOrders::toString);

        JsonNode quotedEnumConnection = execute(connection,
                "{ customerFeed(first: 10, status: \"ACTIVE\") { totalCount } }", "", "{}", "reader");
        assertTrue(quotedEnumConnection.at("/errors/0/message").asText()
                        .contains("cannot be coerced to 'CustomerStatus'"), quotedEnumConnection::toString);

        JsonNode unknownRelationEnum = execute(connection, """
                query OrdersByStatus($status: OrderStatus!) {
                  customer(id: 7) { orderConnection(first: 10, status: $status) { totalCount } }
                }
                """, "OrdersByStatus", "{\"status\":\"UNKNOWN\"}", "reader");
        assertTrue(unknownRelationEnum.at("/errors/0/message").asText()
                        .contains("cannot be coerced to 'OrderStatus'"), unknownRelationEnum::toString);

        JsonNode numericId = execute(connection,
                "{ customerByNodeId(id: \"7001\") { nodeId externalId } }", "", "{}", "reader");
        assertTrue(numericId.at("/data/customerByNodeId/nodeId").isTextual(), numericId::toString);
        assertEquals("7001", numericId.at("/data/customerByNodeId/nodeId").asText(), numericId::toString);
        assertEquals("7001", numericId.at("/data/customerByNodeId/externalId").asText(), numericId::toString);

        JsonNode stringId = execute(connection,
                "{ customerByExternalId(id: 7001) { nodeId externalId name } }", "", "{}", "reader");
        assertEquals("7001", stringId.at("/data/customerByExternalId/nodeId").asText(), stringId::toString);
        assertEquals("7001", stringId.at("/data/customerByExternalId/externalId").asText(), stringId::toString);
        assertEquals("Northwind", stringId.at("/data/customerByExternalId/name").asText(), stringId::toString);

        JsonNode opaqueStringId = execute(connection,
                "{ customerByExternalId(id: \"customer:adventure\") { externalId name } }", "", "{}", "reader");
        assertEquals("customer:adventure", opaqueStringId.at("/data/customerByExternalId/externalId").asText(),
                opaqueStringId::toString);
        assertEquals("Adventure Works", opaqueStringId.at("/data/customerByExternalId/name").asText(),
                opaqueStringId::toString);

        JsonNode integralIdFilter = execute(connection, """
                { customerFeed(first: 10, filter: { nodeId: { in: [7001] } }) {
                    edges { node { nodeId name } }
                    totalCount
                  } }
                """, "", "{}", "reader");
        assertEquals(1, integralIdFilter.at("/data/customerFeed/totalCount").asInt(), integralIdFilter::toString);
        assertEquals("7001", integralIdFilter.at("/data/customerFeed/edges/0/node/nodeId").asText(),
                integralIdFilter::toString);
        assertEquals("Northwind", integralIdFilter.at("/data/customerFeed/edges/0/node/name").asText(),
                integralIdFilter::toString);

        JsonNode stringIdFilter = execute(connection, """
                { customerFeed(first: 10, filter: { externalId: { in: [7001, "customer:adventure"] } }) {
                    edges { node { externalId name } }
                    totalCount
                  } }
                """, "", "{}", "reader");
        assertEquals(2, stringIdFilter.at("/data/customerFeed/totalCount").asInt(), stringIdFilter::toString);
        assertEquals("7001", stringIdFilter.at("/data/customerFeed/edges/0/node/externalId").asText(),
                stringIdFilter::toString);
        assertEquals("customer:adventure", stringIdFilter.at("/data/customerFeed/edges/1/node/externalId").asText(),
                stringIdFilter::toString);

        JsonNode typedScalarInFilters = execute(connection, """
                { customerFeed(first: 10, filter: {
                    rating: { in: [42] }
                    active: { in: [true] }
                    creditLimit: { in: [1250.5] }
                  }) { edges { node { name } } totalCount } }
                """, "", "{}", "reader");
        assertEquals(1, typedScalarInFilters.at("/data/customerFeed/totalCount").asInt(),
                typedScalarInFilters::toString);
        assertEquals("Northwind", typedScalarInFilters.at("/data/customerFeed/edges/0/node/name").asText(),
                typedScalarInFilters::toString);

        JsonNode uuidInFilter = execute(connection, """
                { apiClients(first: 10, filter: { id: { in: ["11111111-2222-3333-4444-555555555555"] } }) {
                    edges { node { id label } }
                    totalCount
                  } }
                """, "", "{}", "reader");
        assertEquals(1, uuidInFilter.at("/data/apiClients/totalCount").asInt(), uuidInFilter::toString);
        assertEquals("public-client", uuidInFilter.at("/data/apiClients/edges/0/node/label").asText(),
                uuidInFilter::toString);

        // GraphQL names are case-sensitive. This is especially important for the MySQL package,
        // whose usual default collations are case-insensitive; the transpiler must retain Java
        // String.equals semantics while dispatching generated fields.
        JsonNode wrongCaseRoot = execute(connection,
                "{ Customer(id: 7) { name } }", "", "{}", "reader");
        assertTrue(wrongCaseRoot.at("/errors/0/message").asText()
                        .contains("field 'Query.Customer' is not defined by the installed schema"),
                wrongCaseRoot::toString);
        assertEquals(1, wrongCaseRoot.at("/errors/0/locations/0/line").asInt(), wrongCaseRoot::toString);
        assertEquals(3, wrongCaseRoot.at("/errors/0/locations/0/column").asInt(), wrongCaseRoot::toString);

        JsonNode nullablePrimitives = execute(connection,
                "{ customer(id: 8) { rating verified } }", "", "{}", "reader");
        assertTrue(nullablePrimitives.at("/data/customer/rating").isNull(), nullablePrimitives::toString);
        assertTrue(nullablePrimitives.at("/data/customer/verified").asBoolean(), nullablePrimitives::toString);

        JsonNode nestedRelation = execute(connection,
                "{ customer(id: 7) { name orders { id reference } } }", "", "{}", "reader");
        assertEquals("NW-001", nestedRelation.at("/data/customer/orders/0/reference").asText());

        JsonNode repeatedRelation = execute(connection, """
                {
                  customer(id: 7) {
                    orderIds: orders { id }
                    orderReferences: orders { reference }
                  }
                }
                """, "", "{}", "reader");
        assertEquals(70, repeatedRelation.at("/data/customer/orderIds/0/id").asInt(), repeatedRelation::toString);
        assertEquals("NW-001", repeatedRelation.at("/data/customer/orderReferences/0/reference").asText(),
                repeatedRelation::toString);

        JsonNode repeatedRelationScalar = execute(connection, """
                {
                  customer(id: 7) {
                    orders {
                      canonicalReference: reference
                      displayReference: reference
                      identifier: id
                    }
                  }
                }
                """, "", "{}", "reader");
        assertEquals("NW-001", repeatedRelationScalar.at("/data/customer/orders/0/canonicalReference").asText(),
                repeatedRelationScalar::toString);
        assertEquals("NW-001", repeatedRelationScalar.at("/data/customer/orders/0/displayReference").asText(),
                repeatedRelationScalar::toString);
        assertEquals(70, repeatedRelationScalar.at("/data/customer/orders/0/identifier").asInt(),
                repeatedRelationScalar::toString);

        JsonNode mergedRelationResponseKey = execute(connection,
                "{ customer(id: 7) { orders { repeated: reference repeated: reference } } }", "", "{}", "reader");
        assertEquals("NW-001", mergedRelationResponseKey.at("/data/customer/orders/0/repeated").asText(),
                mergedRelationResponseKey::toString);

        JsonNode mergedRelationChildSelections = execute(connection, """
                { customer(id: 7) {
                    orders { id }
                    orders { reference }
                  } }
                """, "", "{}", "reader");
        assertEquals(70, mergedRelationChildSelections.at("/data/customer/orders/0/id").asInt(),
                mergedRelationChildSelections::toString);
        assertEquals("NW-001", mergedRelationChildSelections.at("/data/customer/orders/0/reference").asText(),
                mergedRelationChildSelections::toString);

        JsonNode mergedPointRootRelationChildren = execute(connection, """
                {
                  customer(id: 7) { orders { id } }
                  customer(id: 7) { orders { reference } }
                }
                """, "", "{}", "reader");
        assertEquals(70, mergedPointRootRelationChildren.at("/data/customer/orders/0/id").asInt(),
                mergedPointRootRelationChildren::toString);
        assertEquals("NW-001", mergedPointRootRelationChildren.at("/data/customer/orders/0/reference").asText(),
                mergedPointRootRelationChildren::toString);

        JsonNode variableAlias = execute(connection,
                "query FindCountry($countryCode: String!) { selected: country(code: $countryCode) { code name } }",
                "FindCountry", "{\"countryCode\":\"NL\"}", "reader");
        assertEquals("NL", variableAlias.at("/data/selected/code").asText());
        assertEquals("Netherlands", variableAlias.at("/data/selected/name").asText());

        JsonNode selectedOperation = execute(connection, """
                query First($ignored: String = "}") { customer(id: 7) { name } }
                query Second { country(code: "NL") { code } }
                """, "Second", "{}", "reader");
        assertEquals("NL", selectedOperation.at("/data/country/code").asText());
        JsonNode selectedOperationIgnoresOtherOperationsFragments = execute(connection, """
                query First($show: Boolean!) {
                  customer(id: 7) { ... StaleCustomerFragment }
                }
                query Selected($id: Int!) {
                  selected: customer(id: $id) { id name }
                }
                fragment StaleCustomerFragment on Customer {
                  name @include(if: $show)
                }
                """, "Selected", "{\"id\":7}", "reader");
        assertEquals(7, selectedOperationIgnoresOtherOperationsFragments.at("/data/selected/id").asInt(),
                selectedOperationIgnoresOtherOperationsFragments::toString);
        assertEquals("Northwind", selectedOperationIgnoresOtherOperationsFragments.at("/data/selected/name").asText(),
                selectedOperationIgnoresOtherOperationsFragments::toString);
        JsonNode ambiguousOperation = execute(connection, """
                query First($ignored: String = "}") { customer(id: 7) { name } }
                query Second { country(code: "NL") { code } }
                """, "", "{}", "reader");
        assertTrue(ambiguousOperation.at("/errors/0/message").asText().contains("operation selection"));

        JsonNode multipleRoots = execute(connection,
                "{ customer(id: 7) { id } country(code: \"NL\") { name } }", "", "{}", "reader");
        assertEquals(7, multipleRoots.at("/data/customer/id").asInt());
        assertEquals("Netherlands", multipleRoots.at("/data/country/name").asText());

        JsonNode repeatedRoot = execute(connection, """
                {
                  firstCustomer: customer(id: 7) { name }
                  secondCustomer: customer(id: 8) { name }
                }
                """, "", "{}", "reader");
        assertEquals("Northwind", repeatedRoot.at("/data/firstCustomer/name").asText(), repeatedRoot::toString);
        assertEquals("Adventure Works", repeatedRoot.at("/data/secondCustomer/name").asText(), repeatedRoot::toString);

        JsonNode duplicateRootResponseKey = execute(connection, """
                {
                  repeated: customer(id: 7) { id }
                  repeated: country(code: "NL") { code }
                }
                """, "", "{}", "reader");
        assertTrue(duplicateRootResponseKey.at("/errors/0/message").asText().contains("conflicting fields"),
                duplicateRootResponseKey::toString);

        JsonNode repeatedScalar = execute(connection, """
                {
                  customer(id: 7) {
                    canonicalName: name
                    displayName: name
                    identifier: id
                  }
                }
                """, "", "{}", "reader");
        assertEquals("Northwind", repeatedScalar.at("/data/customer/canonicalName").asText(), repeatedScalar::toString);
        assertEquals("Northwind", repeatedScalar.at("/data/customer/displayName").asText(), repeatedScalar::toString);
        assertEquals(7, repeatedScalar.at("/data/customer/identifier").asInt(), repeatedScalar::toString);

        JsonNode mergedResponseKey = execute(connection,
                "{ customer(id: 7) { repeated: name repeated: name } }", "", "{}", "reader");
        assertEquals("Northwind", mergedResponseKey.at("/data/customer/repeated").asText(),
                mergedResponseKey::toString);

        JsonNode mergedFragmentResponseKey = execute(connection, """
                query MergedFragment {
                  repeated: customer(id: 7) { name }
                  ... SameCustomer
                }
                fragment SameCustomer on Query { repeated: customer(id: 7) { name } }
                """, "MergedFragment", "{}", "reader");
        assertEquals("Northwind", mergedFragmentResponseKey.at("/data/repeated/name").asText(),
                mergedFragmentResponseKey::toString);

        // GraphQL collects compatible duplicate root fields by response key and merges their
        // child selections. The generated database plan must do this itself; the HTTP/JDBC layer
        // only forwards the complete operation once.
        JsonNode mergedPointRootSelections = execute(connection, """
                {
                  customer(id: 7) { id }
                  customer(id: 7) { name }
                }
                """, "", "{}", "reader");
        assertEquals(7, mergedPointRootSelections.at("/data/customer/id").asInt(),
                mergedPointRootSelections::toString);
        assertEquals("Northwind", mergedPointRootSelections.at("/data/customer/name").asText(),
                mergedPointRootSelections::toString);

        JsonNode mergedFragmentPointRootSelections = execute(connection, """
                query MergePointFragments {
                  ... CustomerIdentifier
                  ... CustomerName
                }
                fragment CustomerIdentifier on Query { customer(id: 7) { id } }
                fragment CustomerName on Query { customer(id: 7) { name } }
                """, "MergePointFragments", "{}", "reader");
        assertEquals(7, mergedFragmentPointRootSelections.at("/data/customer/id").asInt(),
                mergedFragmentPointRootSelections::toString);
        assertEquals("Northwind", mergedFragmentPointRootSelections.at("/data/customer/name").asText(),
                mergedFragmentPointRootSelections::toString);

        JsonNode fragments = execute(connection, """
                query FragmentedCustomer($show: Boolean! = true) {
                  ... RootCustomer @include(if: $show)
                }
                fragment RootCustomer on Query {
                  found: customer(id: 7) { ... CustomerIdentity }
                }
                fragment CustomerIdentity on Customer {
                  ... on Customer { identifier: id }
                  name
                }
                """, "FragmentedCustomer", "{}", "reader");
        assertEquals(7, fragments.at("/data/found/identifier").asInt(), fragments::toString);
        assertEquals("Northwind", fragments.at("/data/found/name").asText(), fragments::toString);

        JsonNode invalidFragmentType = execute(connection, """
                query { ... WrongType }
                fragment WrongType on Customer { id }
                """, "", "{}", "reader");
        assertTrue(invalidFragmentType.at("/errors/0/message").asText()
                        .contains("cannot apply to parent type 'Query'"),
                invalidFragmentType::toString);
        JsonNode fragmentCycle = execute(connection, """
                query { ... First }
                fragment First on Query { ... Second }
                fragment Second on Query { ... First }
                """, "", "{}", "reader");
        assertTrue(fragmentCycle.at("/errors/0/message").asText()
                        .contains("fragment cycle is not supported"),
                fragmentCycle::toString);

        JsonNode customerFeed = execute(connection, """
                query CustomerFeed($pageSize: Int! = 1) {
                  feed: customerFeed(first: $pageSize) {
                    connectionType: __typename
                    itemEdges: edges { edgeType: __typename item: node { nodeType: __typename id name rating verified } }
                    count: totalCount
                    pagination: pageInfo { pageInfoType: __typename more: hasNextPage hasPreviousPage start: startCursor end: endCursor }
                  }
                }
                """, "CustomerFeed", "{}", "reader");
        assertEquals(7, customerFeed.at("/data/feed/itemEdges/0/item/id").asInt(), customerFeed::toString);
        assertEquals("CustomerConnection", customerFeed.at("/data/feed/connectionType").asText(), customerFeed::toString);
        assertEquals("CustomerEdge", customerFeed.at("/data/feed/itemEdges/0/edgeType").asText(), customerFeed::toString);
        assertEquals("Customer", customerFeed.at("/data/feed/itemEdges/0/item/nodeType").asText(), customerFeed::toString);
        assertEquals("PageInfo", customerFeed.at("/data/feed/pagination/pageInfoType").asText(), customerFeed::toString);
        assertEquals("Northwind", customerFeed.at("/data/feed/itemEdges/0/item/name").asText(), customerFeed::toString);
        assertEquals(42, customerFeed.at("/data/feed/itemEdges/0/item/rating").asInt(), customerFeed::toString);
        assertTrue(customerFeed.at("/data/feed/itemEdges/0/item/verified").isNull(), customerFeed::toString);
        assertEquals(2, customerFeed.at("/data/feed/count").asInt(), customerFeed::toString);
        assertTrue(customerFeed.at("/data/feed/pagination/more").asBoolean(), customerFeed::toString);
        assertTrue(customerFeed.at("/data/feed/pagination/hasPreviousPage").isBoolean(), customerFeed::toString);
        assertTrue(customerFeed.at("/data/feed/pagination/hasPreviousPage").asBoolean() == false, customerFeed::toString);

        JsonNode customStringOrder = execute(connection, """
                { customers(first: 1, orderBy: [{ name: ASC }]) {
                    edges { cursor node { id name } }
                    totalCount
                    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                  } }
                """, "", "{}", "reader");
        assertEquals(8, customStringOrder.at("/data/customers/edges/0/node/id").asInt(), customStringOrder::toString);
        assertEquals("Adventure Works", customStringOrder.at("/data/customers/edges/0/node/name").asText(),
                customStringOrder::toString);
        String firstNameCursor = GraphqlCursorCodec.encode(new GraphqlCursorCodec.CursorPayload(
                "name", "name", GraphqlRootField.RootCursorDirection.ASC, "Adventure Works", "id", "8"));
        assertEquals(firstNameCursor, customStringOrder.at("/data/customers/edges/0/cursor").asText(),
                customStringOrder::toString);
        assertEquals(2, customStringOrder.at("/data/customers/totalCount").asInt(), customStringOrder::toString);
        assertTrue(customStringOrder.at("/data/customers/pageInfo/hasNextPage").asBoolean(), customStringOrder::toString);
        assertTrue(customStringOrder.at("/data/customers/pageInfo/hasPreviousPage").asBoolean() == false,
                customStringOrder::toString);

        JsonNode customStringOrderAfter = execute(connection, """
                { customers(first: 1, orderBy: [{ name: ASC }], after: "%s") {
                    edges { cursor node { id name } }
                    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                  } }
                """.formatted(firstNameCursor), "", "{}", "reader");
        assertEquals(7, customStringOrderAfter.at("/data/customers/edges/0/node/id").asInt(),
                customStringOrderAfter::toString);
        String secondNameCursor = GraphqlCursorCodec.encode(new GraphqlCursorCodec.CursorPayload(
                "name", "name", GraphqlRootField.RootCursorDirection.ASC, "Northwind", "id", "7"));
        assertEquals(secondNameCursor, customStringOrderAfter.at("/data/customers/edges/0/cursor").asText(),
                customStringOrderAfter::toString);
        assertTrue(customStringOrderAfter.at("/data/customers/pageInfo/hasNextPage").asBoolean() == false,
                customStringOrderAfter::toString);
        assertTrue(customStringOrderAfter.at("/data/customers/pageInfo/hasPreviousPage").asBoolean(),
                customStringOrderAfter::toString);

        JsonNode customStringOrderLast = execute(connection, """
                { customers(last: 1, orderBy: [{ name: ASC }]) {
                    edges { cursor node { id name } }
                    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                  } }
                """, "", "{}", "reader");
        assertEquals(7, customStringOrderLast.at("/data/customers/edges/0/node/id").asInt(),
                customStringOrderLast::toString);
        assertEquals(secondNameCursor, customStringOrderLast.at("/data/customers/edges/0/cursor").asText(),
                customStringOrderLast::toString);
        assertTrue(customStringOrderLast.at("/data/customers/pageInfo/hasNextPage").asBoolean() == false,
                customStringOrderLast::toString);
        assertTrue(customStringOrderLast.at("/data/customers/pageInfo/hasPreviousPage").asBoolean(),
                customStringOrderLast::toString);

        JsonNode customStringOrderBefore = execute(connection, """
                { customers(last: 1, orderBy: [{ name: ASC }], before: "%s") {
                    edges { cursor node { id name } }
                    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                  } }
                """.formatted(secondNameCursor), "", "{}", "reader");
        assertEquals(8, customStringOrderBefore.at("/data/customers/edges/0/node/id").asInt(),
                customStringOrderBefore::toString);
        assertEquals(firstNameCursor, customStringOrderBefore.at("/data/customers/edges/0/cursor").asText(),
                customStringOrderBefore::toString);
        assertTrue(customStringOrderBefore.at("/data/customers/pageInfo/hasNextPage").asBoolean(),
                customStringOrderBefore::toString);
        assertTrue(customStringOrderBefore.at("/data/customers/pageInfo/hasPreviousPage").asBoolean() == false,
                customStringOrderBefore::toString);

        JsonNode customStringOrderVariable = execute(connection, """
                query CustomerOrder($orderBy: [CustomerOrderBy!]) {
                  customers(first: 1, orderBy: $orderBy) { edges { cursor node { id name } } }
                }
                """, "CustomerOrder", "{\"orderBy\":[{\"name\":\"DESC\"}]}", "reader");
        assertEquals(7, customStringOrderVariable.at("/data/customers/edges/0/node/id").asInt(),
                customStringOrderVariable::toString);
        assertEquals("Northwind", customStringOrderVariable.at("/data/customers/edges/0/node/name").asText(),
                customStringOrderVariable::toString);
        String firstDescendingNameCursor = GraphqlCursorCodec.encode(new GraphqlCursorCodec.CursorPayload(
                "name", "name", GraphqlRootField.RootCursorDirection.DESC, "Northwind", "id", "7"));
        assertEquals(firstDescendingNameCursor, customStringOrderVariable.at("/data/customers/edges/0/cursor").asText(),
                customStringOrderVariable::toString);

        JsonNode customStringOrderDescendingAfter = execute(connection, """
                { customers(first: 1, orderBy: [{ name: DESC }], after: "%s") {
                    edges { cursor node { id name } }
                  } }
                """.formatted(firstDescendingNameCursor), "", "{}", "reader");
        assertEquals(8, customStringOrderDescendingAfter.at("/data/customers/edges/0/node/id").asInt(),
                customStringOrderDescendingAfter::toString);

        JsonNode customIntegralOrder = execute(connection, """
                { customers(first: 1, orderBy: [{ id: DESC }]) {
                    edges { cursor node { id } }
                    pageInfo { hasNextPage hasPreviousPage }
                  } }
                """, "", "{}", "reader");
        assertEquals(8, customIntegralOrder.at("/data/customers/edges/0/node/id").asInt(),
                customIntegralOrder::toString);
        String firstDescendingIdCursor = GraphqlCursorCodec.encode(new GraphqlCursorCodec.CursorPayload(
                "id", "id", GraphqlRootField.RootCursorDirection.DESC, "8", "id", "8"));
        assertEquals(firstDescendingIdCursor, customIntegralOrder.at("/data/customers/edges/0/cursor").asText(),
                customIntegralOrder::toString);
        assertTrue(customIntegralOrder.at("/data/customers/pageInfo/hasNextPage").asBoolean(),
                customIntegralOrder::toString);

        JsonNode customIntegralOrderAfter = execute(connection, """
                { customers(first: 1, orderBy: [{ id: DESC }], after: "%s") {
                    edges { cursor node { id } }
                    pageInfo { hasNextPage hasPreviousPage }
                  } }
                """.formatted(firstDescendingIdCursor), "", "{}", "reader");
        assertEquals(7, customIntegralOrderAfter.at("/data/customers/edges/0/node/id").asInt(),
                customIntegralOrderAfter::toString);
        assertTrue(customIntegralOrderAfter.at("/data/customers/pageInfo/hasNextPage").asBoolean() == false,
                customIntegralOrderAfter::toString);

        JsonNode customIntegralTupleOrder = execute(connection, """
                { customers(first: 1, orderBy: [{ sortRank: ASC }]) {
                    edges { cursor node { id sortRank } }
                    pageInfo { hasNextPage hasPreviousPage }
                  } }
                """, "", "{}", "reader");
        assertEquals(7, customIntegralTupleOrder.at("/data/customers/edges/0/node/id").asInt(),
                customIntegralTupleOrder::toString);
        String firstIntegralTupleCursor = GraphqlCursorCodec.encode(new GraphqlCursorCodec.CursorPayload(
                "sortRank", "sortRank", GraphqlRootField.RootCursorDirection.ASC, "10", "id", "7"));
        assertEquals(firstIntegralTupleCursor, customIntegralTupleOrder.at("/data/customers/edges/0/cursor").asText(),
                customIntegralTupleOrder::toString);
        assertTrue(customIntegralTupleOrder.at("/data/customers/pageInfo/hasNextPage").asBoolean(),
                customIntegralTupleOrder::toString);

        JsonNode customIntegralTupleAfter = execute(connection, """
                { customers(first: 1, orderBy: [{ sortRank: ASC }], after: "%s") {
                    edges { cursor node { id sortRank } }
                    pageInfo { hasNextPage hasPreviousPage }
                  } }
                """.formatted(firstIntegralTupleCursor), "", "{}", "reader");
        assertEquals(8, customIntegralTupleAfter.at("/data/customers/edges/0/node/id").asInt(),
                customIntegralTupleAfter::toString);
        String secondIntegralTupleCursor = GraphqlCursorCodec.encode(new GraphqlCursorCodec.CursorPayload(
                "sortRank", "sortRank", GraphqlRootField.RootCursorDirection.ASC, "10", "id", "8"));
        assertEquals(secondIntegralTupleCursor, customIntegralTupleAfter.at("/data/customers/edges/0/cursor").asText(),
                customIntegralTupleAfter::toString);
        assertTrue(customIntegralTupleAfter.at("/data/customers/pageInfo/hasNextPage").asBoolean() == false,
                customIntegralTupleAfter::toString);

        JsonNode customIntegralTupleBefore = execute(connection, """
                { customers(last: 1, orderBy: [{ sortRank: ASC }], before: "%s") {
                    edges { node { id sortRank } }
                    pageInfo { hasNextPage hasPreviousPage }
                  } }
                """.formatted(secondIntegralTupleCursor), "", "{}", "reader");
        assertEquals(7, customIntegralTupleBefore.at("/data/customers/edges/0/node/id").asInt(),
                customIntegralTupleBefore::toString);
        assertTrue(customIntegralTupleBefore.at("/data/customers/pageInfo/hasPreviousPage").asBoolean() == false,
                customIntegralTupleBefore::toString);

        JsonNode customUuidOrder = execute(connection, """
                { apiClients(first: 1, orderBy: [{ id: ASC }]) {
                    edges { cursor node { id label } }
                    pageInfo { hasNextPage }
                  } }
                """, "", "{}", "reader");
        assertEquals("11111111-2222-3333-4444-555555555555",
                customUuidOrder.at("/data/apiClients/edges/0/node/id").asText(), customUuidOrder::toString);
        String firstUuidCursor = GraphqlCursorCodec.encode(new GraphqlCursorCodec.CursorPayload(
                "id", "id", GraphqlRootField.RootCursorDirection.ASC,
                "11111111-2222-3333-4444-555555555555", "sort_rank", "7"));
        assertEquals(firstUuidCursor, customUuidOrder.at("/data/apiClients/edges/0/cursor").asText(),
                customUuidOrder::toString);
        assertTrue(customUuidOrder.at("/data/apiClients/pageInfo/hasNextPage").asBoolean(),
                customUuidOrder::toString);

        JsonNode customUuidOrderAfter = execute(connection, """
                { apiClients(first: 1, orderBy: [{ id: ASC }], after: "%s") {
                    edges { cursor node { id label } }
                    pageInfo { hasNextPage hasPreviousPage }
                  } }
                """.formatted(firstUuidCursor), "", "{}", "reader");
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                customUuidOrderAfter.at("/data/apiClients/edges/0/node/id").asText(), customUuidOrderAfter::toString);
        assertTrue(customUuidOrderAfter.at("/data/apiClients/pageInfo/hasNextPage").asBoolean() == false,
                customUuidOrderAfter::toString);
        assertTrue(customUuidOrderAfter.at("/data/apiClients/pageInfo/hasPreviousPage").asBoolean(),
                customUuidOrderAfter::toString);

        String invalidUuidCursor = GraphqlCursorCodec.encode(new GraphqlCursorCodec.CursorPayload(
                "id", "id", GraphqlRootField.RootCursorDirection.ASC, "not-a-uuid", "sort_rank", "7"));
        JsonNode malformedCustomUuidCursor = execute(connection, """
                { apiClients(first: 1, orderBy: [{ id: ASC }], after: "%s") { edges { node { id } } } }
                """.formatted(invalidUuidCursor), "", "{}", "reader");
        assertTrue(malformedCustomUuidCursor.at("/errors/0/message").asText().contains("not a cursor"),
                malformedCustomUuidCursor::toString);

        JsonNode malformedCustomStringCursor = execute(connection,
                "{ customers(first: 1, orderBy: [{ name: ASC }], after: \"tgqlc1.bmFtZQ.bmFtZQ.QVND.wA.aWQ.OA\") { edges { node { id } } } }",
                "", "{}", "reader");
        assertTrue(malformedCustomStringCursor.at("/errors/0/message").asText().contains("not a cursor"),
                malformedCustomStringCursor::toString);

        JsonNode relationOrder = execute(connection,
                "{ orders(first: 1, orderBy: [{ customerName: ASC }]) { edges { node { id } } } }",
                "", "{}", "reader");
        assertEquals(70, relationOrder.at("/data/orders/edges/0/node/id").asInt(), relationOrder::toString);

        JsonNode literalConjunctiveFilter = execute(connection, """
                { customerFeed(first: 10, filter: { name: { startsWith: "North", endsWith: "wind" } }) {
                    edges { node { id name } }
                    totalCount
                  } }
                """, "", "{}", "reader");
        assertEquals(1, literalConjunctiveFilter.at("/data/customerFeed/totalCount").asInt(),
                literalConjunctiveFilter::toString);
        assertEquals(7, literalConjunctiveFilter.at("/data/customerFeed/edges/0/node/id").asInt(),
                literalConjunctiveFilter::toString);

        JsonNode variableFilter = execute(connection, """
                query CustomerNameFilter($filter: CustomerFilter!) {
                  customerFeed(first: 10, filter: $filter) { edges { node { name } } totalCount }
                }
                """, "CustomerNameFilter", "{\"filter\":{\"name\":{\"contains\":\"wind\"}}}", "reader");
        assertEquals(1, variableFilter.at("/data/customerFeed/totalCount").asInt(), variableFilter::toString);
        assertEquals("Northwind", variableFilter.at("/data/customerFeed/edges/0/node/name").asText(),
                variableFilter::toString);
        JsonNode literalEnumFilter = execute(connection, """
                { customerFeed(first: 10, filter: { status: { eq: ACTIVE } }) {
                    edges { node { id status } } totalCount
                  } }
                """, "", "{}", "reader");
        assertEquals(1, literalEnumFilter.at("/data/customerFeed/totalCount").asInt(),
                literalEnumFilter::toString);
        assertEquals(7, literalEnumFilter.at("/data/customerFeed/edges/0/node/id").asInt(),
                literalEnumFilter::toString);
        assertEquals("ACTIVE", literalEnumFilter.at("/data/customerFeed/edges/0/node/status").asText(),
                literalEnumFilter::toString);
        JsonNode variableEnumFilter = execute(connection, """
                query CustomerStatusFilter($filter: CustomerFilter!) {
                  customerFeed(first: 10, filter: $filter) { edges { node { id status } } totalCount }
                }
                """, "CustomerStatusFilter", "{\"filter\":{\"status\":{\"in\":[\"INACTIVE\"]}}}", "reader");
        assertEquals(1, variableEnumFilter.at("/data/customerFeed/totalCount").asInt(),
                variableEnumFilter::toString);
        assertEquals(8, variableEnumFilter.at("/data/customerFeed/edges/0/node/id").asInt(),
                variableEnumFilter::toString);
        JsonNode enumIsNotNullFilter = execute(connection,
                "{ customerFeed(filter: { status: { isNull: false } }) { edges { node { id } } totalCount } }",
                "", "{}", "reader");
        assertEquals(2, enumIsNotNullFilter.at("/data/customerFeed/totalCount").asInt(),
                enumIsNotNullFilter::toString);
        JsonNode quotedLiteralEnumFilter = execute(connection,
                "{ customerFeed(filter: { status: { eq: \"ACTIVE\" } }) { edges { node { id } } } }",
                "", "{}", "reader");
        assertTrue(quotedLiteralEnumFilter.at("/errors/0/message").asText().contains("cannot be coerced"),
                quotedLiteralEnumFilter::toString);
        JsonNode unknownVariableEnumFilter = execute(connection, """
                query UnknownCustomerStatusFilter($filter: CustomerFilter!) {
                  customerFeed(first: 10, filter: $filter) { edges { node { id } } }
                }
                """, "UnknownCustomerStatusFilter", "{\"filter\":{\"status\":{\"eq\":\"UNKNOWN\"}}}",
                "reader");
        assertTrue(unknownVariableEnumFilter.at("/errors/0/message").asText().contains("cannot be coerced"),
                unknownVariableEnumFilter::toString);
        JsonNode malformedVariableFilter = execute(connection, """
                query MalformedCustomerFilter($filter: CustomerFilter!) {
                  customerFeed(first: 10, filter: $filter) { edges { node { id } } }
                }
                """, "MalformedCustomerFilter", "{\"filter\":7}", "reader");
        assertTrue(malformedVariableFilter.at("/errors/0/message").asText()
                        .contains("unknown, duplicate, or malformed"),
                malformedVariableFilter::toString);
        JsonNode invalidVariableFilterListItem = execute(connection, """
                query InvalidCustomerFilterListItem($filter: CustomerFilter!) {
                  customerFeed(first: 10, filter: $filter) { edges { node { id } } }
                }
                """, "InvalidCustomerFilterListItem", "{\"filter\":{\"name\":{\"in\":[7]}}}", "reader");
        assertTrue(invalidVariableFilterListItem.at("/errors/0/message").asText().contains("cannot be coerced"),
                invalidVariableFilterListItem::toString);
        String overBudgetInputValues = "";
        int overBudgetInputIndex = 0;
        while (overBudgetInputIndex < 129) {
            overBudgetInputValues = overBudgetInputValues
                    + (overBudgetInputIndex == 0 ? "" : ",") + "\"Northwind\"";
            overBudgetInputIndex++;
        }
        JsonNode overBudgetVariableFilter = execute(connection, """
                query OverBudgetCustomerFilter($filter: CustomerFilter!) {
                  customerFeed(first: 10, filter: $filter) { edges { node { id } } }
                }
                """, "OverBudgetCustomerFilter",
                "{\"filter\":{\"name\":{\"in\":[" + overBudgetInputValues + "]}}}", "reader");
        assertTrue(overBudgetVariableFilter.at("/errors/0/message").asText().contains("input coercion work budget"),
                overBudgetVariableFilter::toString);

        JsonNode literalInFilter = execute(connection, """
                { customerFeed(first: 10, filter: { name: { in: ["Adventure Works"] } }) {
                    edges { node { id name } }
                    totalCount
                  } }
                """, "", "{}", "reader");
        assertEquals(1, literalInFilter.at("/data/customerFeed/totalCount").asInt(), literalInFilter::toString);
        assertEquals(8, literalInFilter.at("/data/customerFeed/edges/0/node/id").asInt(),
                literalInFilter::toString);
        JsonNode emptyInFilter = execute(connection,
                "{ customerFeed(filter: { name: { in: [] } }) { edges { node { id } } totalCount } }",
                "", "{}", "reader");
        assertEquals(0, emptyInFilter.at("/data/customerFeed/totalCount").asInt(), emptyInFilter::toString);
        assertEquals(0, emptyInFilter.at("/data/customerFeed/edges").size(), emptyInFilter::toString);
        JsonNode overLimitInFilter = execute(connection,
                "{ customerFeed(filter: { name: { in: [\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\"] } }) "
                        + "{ edges { node { id } } } }",
                "", "{}", "reader");
        assertTrue(overLimitInFilter.at("/errors/0/message").asText().contains("static carrier budget"),
                overLimitInFilter::toString);
        JsonNode variableInFilter = execute(connection, """
                query CustomerInFilter($filter: CustomerFilter!) {
                  customerFeed(first: 10, filter: $filter) { edges { node { id } } totalCount }
                }
                """, "CustomerInFilter", "{\"filter\":{\"name\":{\"in\":[\"Northwind\"]}}}", "reader");
        assertEquals(1, variableInFilter.at("/data/customerFeed/totalCount").asInt(), variableInFilter::toString);
        assertEquals(7, variableInFilter.at("/data/customerFeed/edges/0/node/id").asInt(),
                variableInFilter::toString);
        JsonNode singletonVariableInFilter = execute(connection, """
                query CustomerSingletonInFilter($filter: CustomerFilter!) {
                  customerFeed(first: 10, filter: $filter) { edges { node { id } } totalCount }
                }
                """, "CustomerSingletonInFilter", "{\"filter\":{\"name\":{\"in\":\"Northwind\"}}}", "reader");
        assertEquals(1, singletonVariableInFilter.at("/data/customerFeed/totalCount").asInt(),
                singletonVariableInFilter::toString);
        assertEquals(7, singletonVariableInFilter.at("/data/customerFeed/edges/0/node/id").asInt(),
                singletonVariableInFilter::toString);

        JsonNode duplicateLiteralFilter = execute(connection, """
                { customerFeed(filter: { name: { startsWith: "N", startsWith: "North" } }) {
                    edges { node { id } }
                  } }
                """, "", "{}", "reader");
        assertTrue(duplicateLiteralFilter.at("/errors/0/message").asText().contains("duplicate"),
                duplicateLiteralFilter::toString);
        JsonNode duplicateVariableFilter = execute(connection, """
                query DuplicateCustomerFilter($filter: CustomerFilter!) {
                  customerFeed(filter: $filter) { edges { node { id } } }
                }
                """, "DuplicateCustomerFilter",
                "{\"filter\":{\"name\":{\"contains\":\"N\",\"contains\":\"North\"}}}", "reader");
        assertTrue(duplicateVariableFilter.at("/errors/0/message").asText().contains("duplicate"),
                duplicateVariableFilter::toString);

        String firstCursor = GraphqlCursorCodec.encode(new GraphqlCursorCodec.CursorPayload(
                "id", "id", GraphqlRootField.RootCursorDirection.ASC, "7", "id", "7"));
        assertEquals(firstCursor, customerFeed.at("/data/feed/pagination/start").asText(), customerFeed::toString);
        assertEquals(firstCursor, customerFeed.at("/data/feed/pagination/end").asText(), customerFeed::toString);
        JsonNode emptyCursorPage = execute(connection, """
                { customerFeed(first: 0) {
                    pageInfo { startCursor endCursor hasNextPage hasPreviousPage }
                  } }
                """, "", "{}", "reader");
        assertTrue(emptyCursorPage.at("/data/customerFeed/pageInfo/startCursor").isNull(), emptyCursorPage::toString);
        assertTrue(emptyCursorPage.at("/data/customerFeed/pageInfo/endCursor").isNull(), emptyCursorPage::toString);
        assertTrue(emptyCursorPage.at("/data/customerFeed/pageInfo/hasNextPage").asBoolean(), emptyCursorPage::toString);
        assertTrue(emptyCursorPage.at("/data/customerFeed/pageInfo/hasPreviousPage").asBoolean() == false,
                emptyCursorPage::toString);
        String beforeFirstCursor = GraphqlCursorCodec.encode(new GraphqlCursorCodec.CursorPayload(
                "id", "id", GraphqlRootField.RootCursorDirection.ASC, "0", "id", "0"));
        JsonNode outOfRangeAfter = execute(connection, """
                { customerFeed(first: 1, after: "%s") { pageInfo { hasPreviousPage } } }
                """.formatted(beforeFirstCursor), "", "{}", "reader");
        assertTrue(outOfRangeAfter.at("/data/customerFeed/pageInfo/hasPreviousPage").asBoolean() == false,
                outOfRangeAfter::toString);
        JsonNode afterCursor = execute(connection, """
                { customerFeed(first: 1, after: "%s") {
                    edges { position: cursor node { id } }
                    totalCount
                    pageInfo { hasNextPage hasPreviousPage start: startCursor end: endCursor }
                  } }
                """.formatted(firstCursor), "", "{}", "reader");
        assertEquals(8, afterCursor.at("/data/customerFeed/edges/0/node/id").asInt(), afterCursor::toString);
        String secondCursor = GraphqlCursorCodec.encode(new GraphqlCursorCodec.CursorPayload(
                "id", "id", GraphqlRootField.RootCursorDirection.ASC, "8", "id", "8"));
        assertEquals(secondCursor, afterCursor.at("/data/customerFeed/edges/0/position").asText(), afterCursor::toString);
        assertEquals(secondCursor, afterCursor.at("/data/customerFeed/pageInfo/start").asText(), afterCursor::toString);
        assertEquals(secondCursor, afterCursor.at("/data/customerFeed/pageInfo/end").asText(), afterCursor::toString);
        assertEquals(2, afterCursor.at("/data/customerFeed/totalCount").asInt(), afterCursor::toString);
        assertTrue(afterCursor.at("/data/customerFeed/pageInfo/hasPreviousPage").asBoolean(), afterCursor::toString);
        assertTrue(afterCursor.at("/data/customerFeed/pageInfo/hasNextPage").asBoolean() == false,
                afterCursor::toString);

        JsonNode lastPage = execute(connection, """
                { customerFeed(last: 1) {
                    edges { cursor node { id } }
                    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                  } }
                """, "", "{}", "reader");
        assertEquals(8, lastPage.at("/data/customerFeed/edges/0/node/id").asInt(), lastPage::toString);
        assertEquals(secondCursor, lastPage.at("/data/customerFeed/edges/0/cursor").asText(), lastPage::toString);
        assertEquals(secondCursor, lastPage.at("/data/customerFeed/pageInfo/startCursor").asText(), lastPage::toString);
        assertEquals(secondCursor, lastPage.at("/data/customerFeed/pageInfo/endCursor").asText(), lastPage::toString);
        assertTrue(lastPage.at("/data/customerFeed/pageInfo/hasPreviousPage").asBoolean(), lastPage::toString);
        assertTrue(lastPage.at("/data/customerFeed/pageInfo/hasNextPage").asBoolean() == false, lastPage::toString);

        JsonNode beforeCursor = execute(connection, """
                { customerFeed(last: 1, before: "%s") {
                    edges { cursor node { id } }
                    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                  } }
                """.formatted(secondCursor), "", "{}", "reader");
        assertEquals(7, beforeCursor.at("/data/customerFeed/edges/0/node/id").asInt(), beforeCursor::toString);
        assertEquals(firstCursor, beforeCursor.at("/data/customerFeed/edges/0/cursor").asText(), beforeCursor::toString);
        assertEquals(firstCursor, beforeCursor.at("/data/customerFeed/pageInfo/startCursor").asText(), beforeCursor::toString);
        assertEquals(firstCursor, beforeCursor.at("/data/customerFeed/pageInfo/endCursor").asText(), beforeCursor::toString);
        assertTrue(beforeCursor.at("/data/customerFeed/pageInfo/hasPreviousPage").asBoolean() == false,
                beforeCursor::toString);
        assertTrue(beforeCursor.at("/data/customerFeed/pageInfo/hasNextPage").asBoolean(), beforeCursor::toString);

        JsonNode mixedPagination = execute(connection,
                "{ customerFeed(first: 1, last: 1) { edges { node { id } } } }", "", "{}", "reader");
        assertTrue(mixedPagination.at("/errors/0/message").asText().contains("cannot be used together"),
                mixedPagination::toString);

        JsonNode invalidCursor = execute(connection,
                "{ customerFeed(first: 1, after: \"7\") { edges { node { id } } } }", "", "{}", "reader");
        assertTrue(invalidCursor.at("/errors/0/message").asText().contains("not a cursor"), invalidCursor::toString);
        JsonNode trailingCursorPart = execute(connection,
                "{ customerFeed(first: 1, after: \"" + firstCursor + ".\") { edges { node { id } } } }",
                "", "{}", "reader");
        assertTrue(trailingCursorPart.at("/errors/0/message").asText().contains("not a cursor"),
                trailingCursorPart::toString);

        JsonNode oversizedGraphqlInt = execute(connection,
                "{ customer(id: 2147483648) { id } }", "", "{}", "reader");
        assertTrue(oversizedGraphqlInt.at("/errors/0/message").asText().contains("cannot be coerced to 'Int'"),
                oversizedGraphqlInt::toString);
        JsonNode oversizedGraphqlIntVariable = execute(connection,
                "query($id: Int!) { customer(id: $id) { id } }", "", "{\"id\":2147483648}", "reader");
        assertTrue(oversizedGraphqlIntVariable.at("/errors/0/message").asText().contains("cannot be coerced to 'Int'"),
                oversizedGraphqlIntVariable::toString);
        JsonNode wrappingGraphqlInt = execute(connection,
                "{ customer(id: 18446744073709551623) { id } }", "", "{}", "reader");
        assertTrue(wrappingGraphqlInt.at("/errors/0/message").asText().contains("cannot be coerced to 'Int'"),
                "an out-of-range integer literal must not wrap to the existing customer key: " + wrappingGraphqlInt);
        JsonNode wrappingGraphqlIntVariable = execute(connection,
                "query($id: Int!) { customer(id: $id) { id } }", "", "{\"id\":18446744073709551623}", "reader");
        assertTrue(wrappingGraphqlIntVariable.at("/errors/0/message").asText().contains("cannot be coerced to 'Int'"),
                "an out-of-range JSON integer must not wrap to the existing customer key: " + wrappingGraphqlIntVariable);

        JsonNode malformed = execute(connection, "", "", "{}", "reader");
        assertTrue(malformed.at("/errors/0/message").asText().contains("query"));

        JsonNode emptyObjectSelection = execute(connection, "{ customer(id: 7) { } }", "", "{}", "reader");
        assertTrue(emptyObjectSelection.at("/errors/0/message").asText()
                        .contains("selection for type 'Customer' is invalid or empty"),
                emptyObjectSelection::toString);

        JsonNode oversizedVariables = execute(connection, "{ customer(id: 7) { id } }", "",
                "{\"padding\":\"" + "x".repeat(16_385) + "\"}", "reader");
        assertTrue(oversizedVariables.at("/errors/0/message").asText().contains("envelope exceeds"),
                oversizedVariables::toString);

        JsonNode unsupportedSubscription = execute(connection,
                "subscription { customer(id: 7) { id } }", "", "{}", "reader");
        assertTrue(unsupportedSubscription.at("/errors/0/message").asText().contains("subscription"),
                unsupportedSubscription::toString);

        JsonNode quotedInteger = execute(connection, "{ customer(id: \"7\") { id } }", "", "{}", "reader");
        assertTrue(quotedInteger.at("/errors/0/message").asText().contains("cannot be coerced to 'Int'"));
        JsonNode bareString = execute(connection, "{ country(code: NL) { code } }", "", "{}", "reader");
        assertTrue(bareString.at("/errors/0/message").asText().contains("cannot be coerced to 'String'"));

        JsonNode identityMismatch = execute(connection, "{ customer(id: 7) { id } }", "", "{}", "reader",
                false, "not-the-installed-model");
        assertTrue(identityMismatch.at("/errors/0/message").asText().contains("model identity"));
        JsonNode contextMismatch = execute(connection, "{ customer(id: 7) { id } }", "", "{}", "reader",
                false, DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                "{\"actorRole\":\"reader\"}");
        assertTrue(contextMismatch.at("/errors/0/message").asText().contains("contextVersion"));

        JsonNode denied = execute(connection, "{ country(code: \"NL\") { name } }", "", "{}", "anonymous");
        assertTrue(denied.at("/errors/0/message").asText().contains("root 'country' is not authorized"));
        assertEquals("AUTHORIZATION_ERROR", denied.at("/errors/0/extensions/code").asText(), denied::toString);
        assertEquals(1, denied.at("/errors/0/locations/0/line").asInt(), denied::toString);

        JsonNode typePolicyDenied = execute(connection, """
                { guarded: customers(first: 1, filter: { active: { eq: true } },
                    orderBy: [{ name: ASC }]) { totalCount } }
                """, "", "{}", "anonymous");
        assertTrue(typePolicyDenied.at("/errors/0/message").asText()
                .contains("rows for 'customers' is not authorized"), typePolicyDenied::toString);
        assertEquals("AUTHORIZATION_ERROR", typePolicyDenied.at("/errors/0/extensions/code").asText(),
                typePolicyDenied::toString);
        assertFalse(typePolicyDenied.at("/errors/0/locations").isMissingNode(), typePolicyDenied::toString);

        JsonNode unselectedProtectedField = execute(connection,
                "{ customer(id: 7) { id name } }", "", "{}", "fieldReader");
        assertEquals("Northwind", unselectedProtectedField.at("/data/customer/name").asText(),
                unselectedProtectedField::toString);
        JsonNode fieldPolicyDenied = execute(connection, """
                { searchCustomer(id: 7) {
                    ... on Customer { ...ProtectedVerification }
                    ... on Order { id }
                  } }
                fragment ProtectedVerification on Customer { protectedVerification: verified }
                """, "", "{}", "fieldReader");
        assertTrue(fieldPolicyDenied.at("/errors/0/message").asText()
                .contains("field 'Customer.verified' is not authorized"), fieldPolicyDenied::toString);
        assertEquals("AUTHORIZATION_ERROR", fieldPolicyDenied.at("/errors/0/extensions/code").asText(),
                fieldPolicyDenied::toString);
        assertFalse(fieldPolicyDenied.at("/errors/0/locations").isMissingNode(), fieldPolicyDenied::toString);

        JsonNode unselectedProtectedRelation = execute(connection,
                "{ customer(id: 7) { id name } }", "", "{}", "relationReader");
        assertEquals(7, unselectedProtectedRelation.at("/data/customer/id").asInt(),
                unselectedProtectedRelation::toString);
        JsonNode relationPolicyDenied = execute(connection, """
                { customer(id: 7) { ...CustomerOrders } }
                fragment CustomerOrders on Customer { protectedOrders: orders { id } }
                """, "", "{}", "relationReader");
        assertTrue(relationPolicyDenied.at("/errors/0/message").asText()
                .contains("relation 'Customer.orders' is not authorized"), relationPolicyDenied::toString);
        assertFalse(relationPolicyDenied.at("/errors/0/locations").isMissingNode(), relationPolicyDenied::toString);
        JsonNode relayRelationPolicyDenied = execute(connection, """
                { customers(first: 2) { edges { node {
                    protectedOrders: orderConnection(first: 1) {
                      protectedCount: totalCount edges { node { id } }
                    }
                  } } } }
                """, "", "{}", "relationReader");
        assertTrue(relayRelationPolicyDenied.at("/errors/0/message").asText()
                .contains("relation 'Customer.orderConnection' is not authorized"),
                relayRelationPolicyDenied::toString);
        JsonNode nestedToOnePolicyDenied = execute(connection,
                "{ customer(id: 7) { orders { id protectedCustomer: customer { id } } } }",
                "", "{}", "fieldReader");
        assertTrue(nestedToOnePolicyDenied.at("/errors/0/message").asText()
                .contains("relation 'Order.customer' is not authorized"), nestedToOnePolicyDenied::toString);

        JsonNode uuid = execute(connection,
                "{ apiClient(id: \"11111111-2222-3333-4444-555555555555\") { id sortRank label } }", "", "{}", "reader");
        assertEquals("public-client", uuid.at("/data/apiClient/label").asText());
        assertEquals(7, uuid.at("/data/apiClient/sortRank").asInt(), uuid::toString);

        JsonNode composite = execute(connection,
                "{ inventoryItem(warehouse: \"AMS\", sku: \"TG-42\") { warehouse sku quantity } }",
                "", "{}", "reader");
        assertEquals(17, composite.at("/data/inventoryItem/quantity").asInt());

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE commerce.customers SET name = 'Changed inside PostgreSQL' WHERE id = 7");
        }
        JsonNode changed = execute(connection, "{ customer(id: 7) { name } }", "", "{}", "reader");
        assertEquals("Changed inside PostgreSQL", changed.at("/data/customer/name").asText());

        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE commerce.customers ALTER COLUMN name TYPE TEXT");
            statement.executeUpdate("UPDATE commerce.customers SET name = repeat('x', 16384) WHERE id = 7");
        }
        JsonNode responseBudget = execute(connection, "{ customer(id: 7) { name } }", "", "{}", "reader");
        assertTrue(responseBudget.at("/errors/0/message").asText().contains("response exceeds"),
                responseBudget::toString);
        assertEquals("RESOURCE_LIMIT_ERROR", responseBudget.at("/errors/0/extensions/code").asText(),
                responseBudget::toString);
    }

    @Test
    void installedEntryPointClassifiesTheFinalResponseBudget(TitanTestContext context) throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE commerce.customers ALTER COLUMN name TYPE TEXT");
            statement.executeUpdate("UPDATE commerce.customers SET name = repeat('x', 16384) WHERE id = 7");
        }

        JsonNode response = execute(connection, "{ customer(id: 7) { name } }", "", "{}", "reader");
        assertTrue(response.at("/errors/0/message").asText().contains("response exceeds"), response::toString);
        assertEquals("RESOURCE_LIMIT_ERROR", response.at("/errors/0/extensions/code").asText(), response::toString);
    }

    @Test
    void installedEntryPointRejectsKnownInputFanOutBeforeQueueExpansion(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
        String values = "";
        int index = 0;
        while (index < 129) {
            values = values + (index == 0 ? "" : ",") + "\"Northwind\"";
            index++;
        }

        JsonNode response = execute(connection, """
                query OverBudgetCustomerFilter($filter: CustomerFilter!) {
                  customerFeed(first: 10, filter: $filter) { edges { node { id } } }
                }
                """, "OverBudgetCustomerFilter",
                "{\"filter\":{\"name\":{\"in\":[" + values + "]}}}", "reader");
        assertTrue(response.at("/errors/0/message").asText().contains("input coercion work budget"),
                response::toString);
        assertEquals("RESOURCE_LIMIT_ERROR", response.at("/errors/0/extensions/code").asText(),
                response::toString);
    }

    @Test
    void installedEntryPointExecutesAuthoredNestedInputObjectsAndDefaults(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            JsonNode metadata = execute(connection, """
                    {
                      __type(name: "RenameCustomerPatch") {
                        kind description
                        inputFields {
                          name description defaultValue
                          type { kind name ofType { kind name } }
                        }
                      }
                    }
                    """, "", "{}", "reader", false,
                    DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                    DatabaseEngineTestRequestContract.introspectionTrustedContext("reader"));
            assertTrue(metadata.path("errors").isMissingNode(), metadata::toString);
            assertEquals("INPUT_OBJECT", metadata.at("/data/__type/kind").asText(), metadata::toString);
            assertEquals("Mutable customer values.", metadata.at("/data/__type/description").asText(),
                    metadata::toString);
            JsonNode nameField = fieldNamed(metadata.at("/data/__type/inputFields"), "name");
            assertEquals("Replacement customer name.", nameField.at("/description").asText(), metadata::toString);
            assertEquals("\"Defaulted by input schema\"", nameField.at("/defaultValue").asText(),
                    metadata::toString);
            assertEquals("NON_NULL", nameField.at("/type/kind").asText(), metadata::toString);
            assertEquals("String", nameField.at("/type/ofType/name").asText(), metadata::toString);

            JsonNode explicit = execute(connection, """
                    mutation RenameWithInput($input: RenameCustomerInput!) {
                      renameCustomerWithInput(input: $input) { id name }
                    }
                    """, "RenameWithInput",
                    "{\"input\":{\"id\":7,\"patch\":{\"name\":\"Nested variable\"}}}",
                    "editor", true);
            assertEquals("Nested variable", explicit.at("/data/renameCustomerWithInput/name").asText(),
                    explicit::toString);
            assertEquals("Nested variable", customerName(connection, 7));
            connection.rollback();

            JsonNode defaulted = execute(connection,
                    "mutation { renameCustomerWithInput(input: { id: 7, patch: {} }) { name } }",
                    "", "{}", "editor", true);
            assertEquals("Defaulted by input schema",
                    defaulted.at("/data/renameCustomerWithInput/name").asText(), defaulted::toString);
            assertEquals("Defaulted by input schema", customerName(connection, 7));
            connection.rollback();
            assertEquals("Northwind", customerName(connection, 7));
        } finally {
            connection.rollback();
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    @Test
    void installedMutationIsSerialAndLetsTheCallerCommitOrRollBack(TitanTestContext context) throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            JsonNode laterRootValidationFailure = execute(connection, """
                    mutation {
                      first: renameCustomer(id: 7, name: "Must not be staged") { id }
                      second: renameCustomer(id: 8) { id }
                    }
                    """, "", "{}", "editor", true);
            assertTrue(laterRootValidationFailure.at("/errors/0/message").asText().length() != 0,
                    laterRootValidationFailure::toString);
            assertTrue(laterRootValidationFailure.at("/extensions/titanTransactionOutcome").isMissingNode(),
                    laterRootValidationFailure::toString);
            assertEquals("Northwind", customerName(connection, 7));

            JsonNode duplicateResponseKey = execute(connection, """
                    mutation {
                      repeated: renameCustomer(id: 7, name: "Must not be written") { id }
                      repeated: renameCustomer(id: 8, name: "Must not be written") { id }
                    }
                    """, "", "{}", "editor", true);
            assertTrue(duplicateResponseKey.at("/errors/0/message").asText().contains("conflicting fields"),
                    duplicateResponseKey::toString);
            assertTrue(duplicateResponseKey.at("/extensions/titanTransactionOutcome").isMissingNode(),
                    duplicateResponseKey::toString);
            assertEquals("Northwind", customerName(connection, 7));

            JsonNode failed = execute(connection, """
                    mutation RenameTwice {
                      first: renameCustomer(id: 7, name: "First staged") { id name }
                      second: renameCustomer(id: 999, name: "Missing target") { id name }
                    }
                    """, "RenameTwice", "{}", "editor", true);
            assertTrue(failed.at("/errors/0/message").asText().contains("target row does not exist"), failed::toString);
            assertTrue(failed.at("/extensions/titanTransactionOutcome").isMissingNode(), failed::toString);
            // The first root was executed before the second one failed; the routine itself did not
            // own the transaction, so the same caller can still see the staged write.
            assertEquals("First staged", customerName(connection, 7));
            connection.rollback();
            assertEquals("Northwind", customerName(connection, 7));

            JsonNode succeeded = execute(connection,
                    "mutation RenameCustomer($customerId: Int!, $newName: String!) { "
                            + "changed: renameCustomer(id: $customerId, name: $newName) { id name } }",
                    "RenameCustomer", "{\"customerId\":7,\"newName\":\"Committed by caller\"}", "editor", true);
            assertEquals("Committed by caller", succeeded.at("/data/changed/name").asText());
            assertTrue(succeeded.at("/extensions/titanTransactionOutcome").isMissingNode(), succeeded::toString);
            JsonNode booleanMutation = execute(connection,
                    "mutation SetCustomerActive($active: Boolean!) { setCustomerActive(id: 7, active: $active) { id active } }",
                    "SetCustomerActive", "{\"active\":false}", "editor", true);
            assertTrue(booleanMutation.at("/data/setCustomerActive/active").isBoolean(), booleanMutation::toString);
            assertTrue(booleanMutation.at("/data/setCustomerActive/active").asBoolean() == false,
                    booleanMutation::toString);
            JsonNode decimalMutation = execute(connection,
                    "mutation SetCustomerCredit($credit: Float!) { setCustomerCreditLimit(id: 7, creditLimit: $credit) { id creditLimit } }",
                    "SetCustomerCredit", "{\"credit\":1234.75}", "editor", true);
            assertEquals(1234.75d, decimalMutation.at("/data/setCustomerCreditLimit/creditLimit").asDouble(), 0.0001d,
                    decimalMutation::toString);
            JsonNode enumVariableMutation = execute(connection,
                    "mutation SetCustomerStatus($status: CustomerStatus!) { "
                            + "setCustomerStatus(id: 7, status: $status) { id status } }",
                    "SetCustomerStatus", "{\"status\":\"INACTIVE\"}", "editor", true);
            assertEquals("INACTIVE", enumVariableMutation.at("/data/setCustomerStatus/status").asText(),
                    enumVariableMutation::toString);
            JsonNode enumLiteralMutation = execute(connection,
                    "mutation { setCustomerStatus(id: 7, status: ACTIVE) { status } }",
                    "", "{}", "editor", true);
            assertEquals("ACTIVE", enumLiteralMutation.at("/data/setCustomerStatus/status").asText(),
                    enumLiteralMutation::toString);
            JsonNode unknownEnumLiteral = execute(connection,
                    "mutation { setCustomerStatus(id: 7, status: UNKNOWN) { status } }",
                    "", "{}", "editor", true);
            assertTrue(unknownEnumLiteral.at("/errors/0/message").asText()
                    .contains("cannot be coerced to 'CustomerStatus'"), unknownEnumLiteral::toString);
            JsonNode quotedEnumLiteral = execute(connection,
                    "mutation { setCustomerStatus(id: 7, status: \"ACTIVE\") { status } }",
                    "", "{}", "editor", true);
            assertTrue(quotedEnumLiteral.at("/errors/0/message").asText()
                    .contains("cannot be coerced to 'CustomerStatus'"), quotedEnumLiteral::toString);
            JsonNode unknownEnumVariable = execute(connection,
                    "mutation SetCustomerStatus($status: CustomerStatus!) { "
                            + "setCustomerStatus(id: 7, status: $status) { status } }",
                    "SetCustomerStatus", "{\"status\":\"UNKNOWN\"}", "editor", true);
            assertTrue(unknownEnumVariable.at("/errors/0/message").asText()
                    .contains("variable '$status' cannot be coerced to 'CustomerStatus'"),
                    unknownEnumVariable::toString);
            assertEquals("ACTIVE", customerStatus(connection, 7));
            JsonNode invalidDecimal = execute(connection,
                    "mutation { setCustomerCreditLimit(id: 7, creditLimit: \"not-a-number\") { id } }",
                    "", "{}", "editor", true);
            assertTrue(invalidDecimal.at("/errors/0/message").asText().contains("cannot be coerced to 'Float'"),
                    invalidDecimal::toString);
            JsonNode largeDecimal = execute(connection,
                    "mutation { setCustomerCreditLimit(id: 7, creditLimit: 1.0e308) { creditLimit } }",
                    "", "{}", "editor", true);
            assertEquals(1.0E308, largeDecimal.at("/data/setCustomerCreditLimit/creditLimit").asDouble(), 1.0E294,
                    largeDecimal::toString);
            JsonNode overflowingDecimal = execute(connection,
                    "mutation { setCustomerCreditLimit(id: 7, creditLimit: 1.8e308) { id } }",
                    "", "{}", "editor", true);
            assertTrue(overflowingDecimal.at("/errors/0/message").asText().contains("cannot be coerced to 'Float'"),
                    overflowingDecimal::toString);
            connection.commit();
            assertEquals("Committed by caller", customerName(connection, 7));
            assertEquals("ACTIVE", customerStatus(connection, 7));
        } finally {
            connection.rollback();
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    @Test
    void installedEntryPointCoercesDeclaredScalarVariablesInsideTheDatabase(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);

        JsonNode defaulted = execute(connection, """
                query DefaultCustomer($customerId: Int! = 7) {
                  customer(id: $customerId) { id }
                }
                """, "DefaultCustomer", "{}", "reader");
        assertEquals(7, defaulted.at("/data/customer/id").asInt(), defaulted::toString);

        JsonNode wrongScalarDefault = execute(connection,
                "query WrongDefault($customerId: Int! = \"7\") { customer(id: $customerId) { id } }",
                "WrongDefault", "{}", "reader");
        assertTrue(wrongScalarDefault.at("/errors/0/message").asText()
                .contains("variable '$customerId' cannot be coerced to 'Int'"), wrongScalarDefault::toString);

        String nullableWithDefault = """
                query NullableCustomer($customerId: Int = 7) {
                  customer(id: $customerId) { id }
                }
                """;
        JsonNode nullableDefaulted = execute(connection, nullableWithDefault, "NullableCustomer", "{}", "reader");
        assertEquals(7, nullableDefaulted.at("/data/customer/id").asInt(), nullableDefaulted::toString);
        JsonNode nullableExplicitNull = execute(connection, nullableWithDefault,
                "NullableCustomer", "{\"customerId\":null}", "reader");
        assertTrue(nullableExplicitNull.at("/errors/0/message").asText()
                .contains("argument 'Query.customer.id' cannot be coerced to 'Int'"),
                nullableExplicitNull::toString);
        assertEquals(2, nullableExplicitNull.at("/errors/0/locations/0/line").asInt(),
                nullableExplicitNull::toString);
        assertEquals(16, nullableExplicitNull.at("/errors/0/locations/0/column").asInt(),
                nullableExplicitNull::toString);

        JsonNode nonNullNullDefault = execute(connection,
                "query NullDefault($customerId: Int! = null) { customer(id: $customerId) { id } }",
                "NullDefault", "{}", "reader");
        assertTrue(nonNullNullDefault.at("/errors/0/message").asText()
                .contains("non-null variable cannot declare a null default value"), nonNullNullDefault::toString);

        JsonNode variableDefault = execute(connection,
                "query VariableDefault($customerId: Int = $fallback) { customer(id: $customerId) { id } }",
                "VariableDefault", "{}", "reader");
        assertTrue(variableDefault.at("/errors/0/message").asText()
                .contains("variable default value cannot reference '$fallback'"), variableDefault::toString);

        JsonNode missing = execute(connection,
                "query MissingCustomer($customerId: Int!) { customer(id: $customerId) { id } }",
                "MissingCustomer", "{}", "reader");
        assertTrue(missing.at("/errors/0/message").asText().contains("required variable '$customerId' is missing"),
                missing::toString);

        JsonNode requiredNull = execute(connection,
                "query NullCustomer($customerId: Int!) { customer(id: $customerId) { id } }",
                "NullCustomer", "{\"customerId\":null}", "reader");
        assertTrue(requiredNull.at("/errors/0/message").asText().contains("variable '$customerId' cannot be null"),
                requiredNull::toString);

        String wrongScalarVariableQuery = "query TypedCustomer($customerId: Int!) { customer(id: $customerId) { id } }";
        JsonNode wrongScalarVariable = execute(connection, wrongScalarVariableQuery,
                "TypedCustomer", "{\"customerId\":\"7\"}", "reader");
        assertTrue(wrongScalarVariable.at("/errors/0/message").asText()
                .contains("variable '$customerId' cannot be coerced to 'Int'"), wrongScalarVariable::toString);
        assertEquals(1, wrongScalarVariable.at("/errors/0/locations/0/line").asInt(),
                wrongScalarVariable::toString);
        assertEquals(wrongScalarVariableQuery.indexOf("$customerId") + 1,
                wrongScalarVariable.at("/errors/0/locations/0/column").asInt(), wrongScalarVariable::toString);

        JsonNode wrongDirectiveVariable = execute(connection,
                "query TypedDirective($show: Boolean!) { customer(id: 7) @include(if: $show) { id } }",
                "TypedDirective", "{\"show\":\"true\"}", "reader");
        assertTrue(wrongDirectiveVariable.at("/errors/0/message").asText()
                .contains("variable '$show' cannot be coerced to 'Boolean'"), wrongDirectiveVariable::toString);

        JsonNode optionalMissing = execute(connection,
                "query OptionalCustomer($customerId: Int) { customer(id: $customerId) { id } }",
                "OptionalCustomer", "{}", "reader");
        assertTrue(optionalMissing.at("/errors/0/message").asText()
                        .contains("cannot use variable '$customerId' declared as 'Int' at 'Int!'"),
                optionalMissing::toString);

        String optionalConnectionVariable = """
                query OptionalConnection($first: Int) {
                  customerFeed(first: $first) { edges { node { id } } }
                }
                """;
        JsonNode omittedOptionalConnectionVariable = execute(connection, optionalConnectionVariable,
                "OptionalConnection", "{}", "reader");
        assertEquals(7, omittedOptionalConnectionVariable.at("/data/customerFeed/edges/0/node/id").asInt(),
                omittedOptionalConnectionVariable::toString);
        JsonNode nullOptionalConnectionVariable = execute(connection, optionalConnectionVariable,
                "OptionalConnection", "{\"first\":null}", "reader");
        assertEquals(7, nullOptionalConnectionVariable.at("/data/customerFeed/edges/0/node/id").asInt(),
                nullOptionalConnectionVariable::toString);

        JsonNode nestedListType = execute(connection,
                "query NestedListType($values: [ [ Int! ]! ]!) { customer(id: 7, unexpected: $values) { id } }",
                "NestedListType", "{\"values\":[[1]]}", "reader");
        assertTrue(nestedListType.at("/errors/0/message").asText().contains("unknown, duplicate, or malformed"),
                nestedListType::toString);
        JsonNode malformedNestedListType = execute(connection,
                "query MalformedNestedListType($values: [[]]!) { customer(id: 7, unexpected: $values) { id } }",
                "MalformedNestedListType", "{\"values\":[[]]}", "reader");
        assertTrue(malformedNestedListType.at("/errors/0/message").asText()
                .contains("malformed variable definitions"), malformedNestedListType::toString);

        JsonNode undeclared = execute(connection,
                "query UndeclaredCustomer { customer(id: $customerId) { id } }",
                "UndeclaredCustomer", "{\"customerId\":7}", "reader");
        assertTrue(undeclared.at("/errors/0/message").asText().contains("undefined variable '$customerId'"),
                undeclared::toString);

        JsonNode duplicateDefinition = execute(connection,
                "query DuplicateCustomer($customerId: Int!, $customerId: Int!) { customer(id: $customerId) { id } }",
                "DuplicateCustomer", "{\"customerId\":7}", "reader");
        assertTrue(duplicateDefinition.at("/errors/0/message").asText().contains("declares variable '$customerId' more than once"),
                duplicateDefinition::toString);

        JsonNode undefinedDirectiveVariable = execute(connection,
                "query UndefinedDirective { customer(id: 7) @include(if: $show) { id } }",
                "UndefinedDirective", "{\"show\":true}", "reader");
        assertTrue(undefinedDirectiveVariable.at("/errors/0/message").asText().contains("undefined variable '$show'"),
                undefinedDirectiveVariable::toString);

        JsonNode unusedVariable = execute(connection,
                "query UnusedCustomer($customerId: Int!, $unused: String) { customer(id: $customerId) { id } }",
                "UnusedCustomer", "{\"customerId\":7}", "reader");
        assertTrue(unusedVariable.at("/errors/0/message").asText().contains("variable '$unused' is never used"),
                unusedVariable::toString);

        JsonNode unknownSuppliedVariable = execute(connection,
                "query KnownCustomer($customerId: Int!) { customer(id: $customerId) { id } }",
                "KnownCustomer", "{\"customerId\":7,\"extra\":true}", "reader");
        assertTrue(unknownSuppliedVariable.at("/errors/0/message").asText()
                .contains("variable '$extra' is not declared"), unknownSuppliedVariable::toString);

        JsonNode malformedVariables = execute(connection,
                "query ValidCustomer($customerId: Int!) { customer(id: $customerId) { id } }",
                "ValidCustomer", "{\"customerId\":}", "reader");
        assertTrue(malformedVariables.at("/errors/0/message").asText().contains("variables' must be a valid JSON object"),
                malformedVariables::toString);

        JsonNode wrongType = execute(connection,
                "query WrongCustomer($customerId: String!) { customer(id: $customerId) { id } }",
                "WrongCustomer", "{\"customerId\":\"7\"}", "reader");
        assertTrue(wrongType.at("/errors/0/message").asText()
                        .contains("cannot use variable '$customerId' declared as 'String!' at 'Int!'"),
                wrongType::toString);

        JsonNode emptyString = execute(connection,
                "{ country(code: \"\") { code } }", "", "{}", "reader");
        assertTrue(emptyString.at("/data/country").isNull(), emptyString::toString);

        String blockStringDefault = "query BlockStringCountry($code: String! = \"\"\"\n"
                + "  NL\n"
                + "\"\"\") { country(code: $code) { code } }";
        JsonNode blockString = execute(connection, blockStringDefault, "BlockStringCountry", "{}", "reader");
        assertEquals("NL", blockString.at("/data/country/code").asText(), blockString::toString);

        String escapedBlockString = "{ country(code: " + "\"\"\"" + "NL\\\"\"\"" + "\"\"\""
                + ") { code } }";
        JsonNode escapedTripleQuote = execute(connection, escapedBlockString, "", "{}", "reader");
        assertTrue(escapedTripleQuote.at("/data/country").isNull(), escapedTripleQuote::toString);

        JsonNode customModelInput = execute(connection,
                "query ApiClient($id: UUID!) { apiClient(id: $id) { label } }",
                "ApiClient", "{\"id\":\"11111111-2222-3333-4444-555555555555\"}", "reader");
        assertEquals("public-client", customModelInput.at("/data/apiClient/label").asText(),
                customModelInput::toString);

        JsonNode invalidUuid = execute(connection,
                "{ apiClient(id: \"not-a-uuid\") { label } }", "", "{}", "reader");
        assertTrue(invalidUuid.at("/errors/0/message").asText()
                        .contains("argument 'Query.apiClient.id' cannot be coerced to 'UUID'"),
                invalidUuid::toString);

        JsonNode outputOnlyVariableType = execute(connection,
                "query OutputOnlyVariableType($customer: Customer!) { customer(id: $customer) { id } }",
                "OutputOnlyVariableType", "{\"customer\":7}", "reader");
        assertTrue(outputOnlyVariableType.at("/errors/0/message").asText()
                .contains("declares unsupported input type 'Customer'"), outputOnlyVariableType::toString);

        JsonNode unknownArgument = execute(connection,
                "{ customer(id: 7, unexpected: 1) { id } }", "", "{}", "reader");
        assertTrue(unknownArgument.at("/errors/0/message").asText().contains("unknown, duplicate, or malformed"),
                unknownArgument::toString);
        JsonNode duplicateArgument = execute(connection,
                "{ customer(id: 7, id: 8) { id } }", "", "{}", "reader");
        assertTrue(duplicateArgument.at("/errors/0/message").asText().contains("unknown, duplicate, or malformed"),
                duplicateArgument::toString);
        JsonNode mutationArgument = execute(connection,
                "mutation { renameCustomer(id: 7, name: \"Ignored\", unexpected: 1) { id } }",
                "", "{}", "editor", true);
        assertTrue(mutationArgument.at("/errors/0/message").asText().contains("unknown, duplicate, or malformed"),
                mutationArgument::toString);
    }

    @Test
    void installedEntryPointEvaluatesBuiltInFieldDirectivesInsideTheDatabase(TitanTestContext context)
            throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);

        String fieldDirective = """
                query CustomerFields($showName: Boolean = true, $skipId: Boolean = false) {
                  selected: customer(id: 7) {
                    id @skip(if: $skipId)
                    name @include(if: $showName)
                  }
                }
                """;
        JsonNode defaulted = execute(connection, fieldDirective, "CustomerFields", "{}", "reader");
        assertEquals(7, defaulted.at("/data/selected/id").asInt(), defaulted::toString);
        assertEquals("Northwind", defaulted.at("/data/selected/name").asText(), defaulted::toString);

        JsonNode omittedFields = execute(connection, fieldDirective, "CustomerFields",
                "{\"showName\":false,\"skipId\":true}", "reader");
        assertEquals(0, omittedFields.at("/data/selected").size(), omittedFields::toString);

        JsonNode omittedRoot = execute(connection,
                "query OmitRoot($visible: Boolean!) { customer(id: 7) @include(if: $visible) { id } }",
                "OmitRoot", "{\"visible\":false}", "reader");
        assertEquals(0, omittedRoot.at("/data").size(), omittedRoot::toString);

        String disabledUnknownRoot = "{ absent @skip(if: true) }";
        JsonNode disabledUnknownRootError = execute(connection, disabledUnknownRoot, "", "{}", "reader");
        assertTrue(disabledUnknownRootError.at("/errors/0/message").asText()
                .contains("field 'Query.absent' is not defined"), disabledUnknownRootError::toString);
        assertEquals(disabledUnknownRoot.indexOf("absent") + 1,
                disabledUnknownRootError.at("/errors/0/locations/0/column").asInt(),
                disabledUnknownRootError::toString);

        String disabledUnknownChild = "{ customer(id: 7) @skip(if: true) { absent } }";
        JsonNode disabledUnknownChildError = execute(connection, disabledUnknownChild, "", "{}", "reader");
        assertTrue(disabledUnknownChildError.at("/errors/0/message").asText()
                .contains("field 'Customer.absent' is not defined"), disabledUnknownChildError::toString);

        String disabledUnknownFragment = "{ customer(id: 7) { ...Bad @include(if: false) } } "
                + "fragment Bad on Customer { absent }";
        JsonNode disabledUnknownFragmentError = execute(
                connection, disabledUnknownFragment, "", "{}", "reader");
        assertTrue(disabledUnknownFragmentError.at("/errors/0/message").asText()
                .contains("field 'Customer.absent' is not defined"), disabledUnknownFragmentError::toString);

        String disabledInvalidDirective = "{ customer(id: 7) @skip(if: true) { name @defer } }";
        JsonNode disabledInvalidDirectiveError = execute(
                connection, disabledInvalidDirective, "", "{}", "reader");
        assertTrue(disabledInvalidDirectiveError.at("/errors/0/message").asText()
                        .contains("invalid or unsupported directive"),
                disabledInvalidDirectiveError::toString);
        assertEquals(disabledInvalidDirective.indexOf("@defer") + 1,
                disabledInvalidDirectiveError.at("/errors/0/locations/0/column").asInt(),
                disabledInvalidDirectiveError::toString);

        String disabledInvalidArgument = "{ customer(id: \"seven\") @skip(if: true) { id } }";
        JsonNode disabledInvalidArgumentError = execute(
                connection, disabledInvalidArgument, "", "{}", "reader");
        assertTrue(disabledInvalidArgumentError.at("/errors/0/message").asText()
                        .contains("argument 'Query.customer.id' cannot be coerced to 'Int'"),
                disabledInvalidArgumentError::toString);

        String disabledNestedVariable = "query Hidden($term: Int!) "
                + "{ customers(filter: { name: { contains: $term } }) @skip(if: true) { totalCount } }";
        JsonNode disabledNestedVariableError = execute(
                connection, disabledNestedVariable, "Hidden", "{\"term\":7}", "reader");
        assertTrue(disabledNestedVariableError.at("/errors/0/message").asText()
                        .contains("cannot use variable '$term' declared as 'Int!' at 'String'"),
                disabledNestedVariableError::toString);

        JsonNode enabledNestedVariable = execute(connection, """
                query NestedFilter($term: String!) {
                  customerFeed(first: 10, filter: { name: { contains: $term } }) {
                    totalCount
                    edges { node { name } }
                  }
                }
                """, "NestedFilter", "{\"term\":\"North\"}", "reader");
        assertEquals(1, enabledNestedVariable.at("/data/customerFeed/totalCount").asInt(),
                enabledNestedVariable::toString);
        assertEquals("Northwind", enabledNestedVariable.at("/data/customerFeed/edges/0/node/name").asText(),
                enabledNestedVariable::toString);

        JsonNode unsupported = execute(connection,
                "{ customer(id: 7) @defer { id } }", "", "{}", "reader");
        assertTrue(unsupported.at("/errors/0/message").asText().contains("unsupported directive"),
                unsupported::toString);

        String operationDirective = "query OperationDirective($enabled: Boolean = true) @include(if: $enabled) "
                + "{ customer(id: 7) { id } }";
        JsonNode operationDirectiveError = execute(connection, operationDirective, "OperationDirective",
                "{\"enabled\":false}", "reader");
        assertTrue(operationDirectiveError.at("/errors/0/message").asText()
                .contains("directive is not allowed on an operation definition"), operationDirectiveError::toString);
        assertEquals(operationDirective.indexOf("@include") + 1,
                operationDirectiveError.at("/errors/0/locations/0/column").asInt(), operationDirectiveError::toString);

        JsonNode skippedMutation = execute(connection,
                "mutation @skip(if: true) { renameCustomer(id: 7, name: \"Never applied\") { name } }",
                "", "{}", "editor", true);
        assertTrue(skippedMutation.at("/errors/0/message").asText()
                .contains("directive is not allowed on an operation definition"), skippedMutation::toString);
        assertEquals("Northwind", customerName(connection, 7));

        JsonNode duplicateOperationDirective = execute(connection,
                "query @include(if: true) @include(if: true) { customer(id: 7) { id } }", "", "{}", "reader");
        assertTrue(duplicateOperationDirective.at("/errors/0/message").asText()
                        .contains("directive is not allowed on an operation definition"),
                duplicateOperationDirective::toString);

        String fragmentDefinition = "fragment CustomerFields on Customer @include(if: true) { id }";
        String fragmentDefinitionDirective = "query { customer(id: 7) { ...CustomerFields } } "
                + fragmentDefinition;
        JsonNode fragmentDefinitionDirectiveError = execute(
                connection, fragmentDefinitionDirective, "", "{}", "reader");
        assertTrue(fragmentDefinitionDirectiveError.at("/errors/0/message").asText()
                        .contains("directive is not allowed on a fragment definition"),
                fragmentDefinitionDirectiveError::toString);
        assertEquals(1, fragmentDefinitionDirectiveError.at("/errors/0/locations/0/line").asInt(),
                fragmentDefinitionDirectiveError::toString);
        assertEquals(fragmentDefinitionDirective.indexOf("@include") + 1,
                fragmentDefinitionDirectiveError.at("/errors/0/locations/0/column").asInt(),
                fragmentDefinitionDirectiveError::toString);

        JsonNode badToken = execute(connection,
                "{ customer(id: 7?) { id } }", "", "{}", "reader");
        assertTrue(badToken.at("/errors/0/message").asText().contains("lexical syntax"), badToken::toString);
        JsonNode unterminatedString = execute(connection,
                "{ country(code: \"NL) { code } }", "", "{}", "reader");
        assertTrue(unterminatedString.at("/errors/0/message").asText().contains("lexical syntax"),
                unterminatedString::toString);

        String overDeepDelimiters = "{ customer(id: 7) " + "{".repeat(129) + "}".repeat(129) + " }";
        JsonNode overDeep = execute(connection, overDeepDelimiters, "", "{}", "reader");
        assertTrue(overDeep.at("/errors/0/message").asText().contains("lexical syntax"), overDeep::toString);

        JsonNode mismatchedDelimiters = execute(connection,
                "{ customer(id: 7] { id } }", "", "{}", "reader");
        assertTrue(mismatchedDelimiters.at("/errors/0/message").asText().contains("lexical syntax"),
                mismatchedDelimiters::toString);
    }

    @Test
    void installedEntryPointMatchesPortableExpectedResultCorpus(TitanTestContext context) throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);

        DatabaseEngineExpectedResultCorpus.assertCommerceV1(
                (query, operationName, variablesJson, actorRole, allowMutations, allowIntrospection) -> execute(
                        connection, query, operationName, variablesJson, actorRole, allowMutations,
                        DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                        allowIntrospection
                                ? DatabaseEngineTestRequestContract.introspectionTrustedContext(actorRole)
                                : DatabaseEngineTestRequestContract.trustedContext(actorRole)));
    }

    @Test
    void tenantContextFilterFailsClosedAndNeverReturnsAnotherTenant(TitanTestContext context) throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        CommerceDatabaseEngineDeployment.deployPostgreSql(connection);
        String query = "{ customers(first: 10) { totalCount edges { node { id tenantKey } } } }";

        JsonNode tenantA = execute(connection, query, "", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                tenantContext("tenant-a"));
        assertEquals(1, tenantA.at("/data/customers/totalCount").asInt(), tenantA::toString);
        assertEquals(7, tenantA.at("/data/customers/edges/0/node/id").asInt(), tenantA::toString);
        assertEquals("tenant-a", tenantA.at("/data/customers/edges/0/node/tenantKey").asText(), tenantA::toString);

        JsonNode tenantB = execute(connection, query, "", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                tenantContext("tenant-b"));
        assertEquals(1, tenantB.at("/data/customers/totalCount").asInt(), tenantB::toString);
        assertEquals(8, tenantB.at("/data/customers/edges/0/node/id").asInt(), tenantB::toString);

        JsonNode missing = execute(connection, query, "", "{}", "reader", false,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"),
                tenantContext(null));
        assertEquals(0, missing.at("/data/customers/totalCount").asInt(), missing::toString);
        assertTrue(missing.at("/data/customers/edges").isEmpty(), missing::toString);
    }

    private static String tenantContext(String tenantKey) {
        String values = tenantKey == null ? "{}" : "{\"tenantKey\":\"" + tenantKey + "\"}";
        return "{\"contextVersion\":\"titan.graphql.request-context/v1\",\"actorRole\":\"reader\","
                + "\"enabledContextFilters\":[\"tenantIsolation\"],\"contextValues\":" + values + "}";
    }

    private static JsonNode execute(
            Connection connection,
            String query,
            String operationName,
            String variablesJson,
            String role
    ) throws Exception {
        return execute(connection, query, operationName, variablesJson, role, false);
    }

    private static JsonNode execute(
            Connection connection,
            String query,
            String operationName,
            String variablesJson,
            String role,
            boolean allowMutations
    ) throws Exception {
        return execute(connection, query, operationName, variablesJson, role, allowMutations,
                DatabaseEngineTestRequestContract.modelHash("/graphql/commerce.titan.graphql.yaml"));
    }

    private static JsonNode execute(
            Connection connection,
            String query,
            String operationName,
            String variablesJson,
            String role,
            boolean allowMutations,
            String expectedModelHash
    ) throws Exception {
        return execute(connection, query, operationName, variablesJson, role, allowMutations, expectedModelHash,
                DatabaseEngineTestRequestContract.trustedContext(role));
    }

    private static JsonNode execute(
            Connection connection,
            String query,
            String operationName,
            String variablesJson,
            String role,
            boolean allowMutations,
            String expectedModelHash,
            String trustedContextJson
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT public.execute_graphql_request(?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            // Exercise the installed routine under the same default upper bound as the thin
            // production HTTP adapter. Deployment/setup time is intentionally outside this
            // assertion; only the complete database-resident GraphQL request is bounded.
            statement.setQueryTimeout(30);
            statement.setString(1, query);
            statement.setString(2, operationName);
            statement.setString(3, variablesJson);
            statement.setString(4, "{}");
            statement.setString(5, trustedContextJson);
            statement.setBoolean(6, allowMutations);
            statement.setString(7, expectedModelHash);
            statement.setString(8, DatabaseEngineTestRequestContract.runtimeIdentity());
            statement.setString(9, DatabaseEngineTestRequestContract.packageIdentity());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return JSON.readTree(DatabaseEngineTestRequestContract.postgresqlResponseJson(resultSet.getString(1)));
            }
        }
    }

    private static String repeatedCustomerOrdersQuery(int roots) {
        StringBuilder query = new StringBuilder("{");
        for (int index = 0; index < roots; index++) {
            query.append(" customer").append(index).append(": customer(id: 7) { orders { id } }");
        }
        return query.append(" }").toString();
    }

    private static String repeatedRenameCustomerQuery(int roots) {
        StringBuilder query = new StringBuilder("mutation {");
        for (int index = 0; index < roots; index++) {
            query.append(" rename").append(index)
                    .append(": renameCustomer(id: 7, name: \"Budget ").append(index).append("\") { id }");
        }
        return query.append(" }").toString();
    }

    private static String customerStatus(Connection connection, long id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM commerce.customers WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getString(1);
            }
        }
    }

    private static String customerName(Connection connection, long id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name FROM commerce.customers WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getString(1);
            }
        }
    }
}
