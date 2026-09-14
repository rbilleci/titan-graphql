package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.titan.graphql.GraphqlExecution;
import io.titan.graphql.GraphqlExecutionEngine;
import io.titan.graphql.GraphqlModelRuntime;
import io.titan.graphql.GraphqlRequest;
import io.titan.graphql.GraphqlRequestContext;
import io.titan.graphql.TitanCompiledGraphqlRuntime;
import io.titan.graphql.artifact.TitanGraphqlArtifactsDirectory;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.runtime.jdbc.SingleConnectionDataSource;
import io.titan.runtime.testing.DatabaseTarget;
import io.titan.runtime.testing.TitanTest;
import io.titan.runtime.testing.TitanTestContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Independently packaged, unrelated-schema proof for the generic compiled runtime. */
@Tag("docker")
@Tag("commerce-compiled")
@TitanTest(targets = {DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL})
class CommerceCompiledGraphqlIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODEL = "src/test/resources/graphql/commerce.titan.graphql.yaml";
    private static final String CLIENT_ID = "11111111-2222-3333-4444-555555555555";

    @Test
    void independentlyGeneratedPackageServesMutableCommerceModelOnBothDialects(
            TitanTestContext context
    ) throws Exception {
        TitanGraphqlGap005ArtifactMetadata metadata = TitanGraphqlArtifactsDirectory.readGap005Metadata();
        assertTrue(metadata.entryPoints().stream().allMatch(entry ->
                        entry.className().equals("io.titan.graphql.generated.GeneratedTitanGraphqlReads")),
                "commerce package must contain generated carriers only: " + metadata.entryPoints());
        assertTrue(metadata.entryPoints().stream().anyMatch(entry -> entry.methodName().equals("readRootCustomer")));
        assertFalse(metadata.entryPoints().stream().anyMatch(entry ->
                entry.className().contains("DemoBlog") || entry.methodName().contains("Article")));

        for (DatabaseTarget target : List.of(DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL)) {
            Connection connection = context.connection(target);
            deploy(connection, target);
            GraphqlExecutionEngine engine = engine(connection, target);
            GraphqlModelRuntime runtime = engine.runtime();
            assertEquals(TitanCompiledGraphqlRuntime.NAME, runtime.name(), target.name());

            GraphqlExecution point = execute(runtime,
                    "{ customer(id: 7) { id name active orders { id reference } } }",
                    GraphqlRequestContext.legacy(1L, "reader"));
            assertEquals(2, point.plan().readStepCount(), target.name());
            JsonNode pointJson = JSON.readTree(point.json());
            assertEquals("Northwind", pointJson.at("/data/customer/name").asText(), target.name());
            assertEquals("NW-001", pointJson.at("/data/customer/orders/0/reference").asText(), target.name());

            JsonNode stringKey = JSON.readTree(execute(runtime,
                    "{ country(code: \"NL\") { code name } }",
                    GraphqlRequestContext.legacy(1L, "reader")).json());
            assertEquals("Netherlands", stringKey.at("/data/country/name").asText(), target.name());

            JsonNode uuidKey = JSON.readTree(execute(runtime,
                    "{ apiClient(id: \"" + CLIENT_ID + "\") { id label } }",
                    GraphqlRequestContext.legacy(1L, "reader")).json());
            assertEquals(CLIENT_ID, uuidKey.at("/data/apiClient/id").asText(), target.name());

            JsonNode compositeKey = JSON.readTree(execute(runtime,
                    "{ inventoryItem(warehouse: \"AMS\", sku: \"TG-42\") { warehouse sku quantity } }",
                    GraphqlRequestContext.legacy(1L, "reader")).json());
            assertEquals(17, compositeKey.at("/data/inventoryItem/quantity").asInt(), target.name());

            JsonNode invalidUuid = JSON.readTree(execute(runtime,
                    "{ apiClient(id: \"not-a-uuid\") { id } }",
                    GraphqlRequestContext.legacy(1L, "reader")).json());
            assertTrue(invalidUuid.at("/errors/0/message").asText().contains("must be a UUID"), target.name());

            JsonNode incompleteCompositeKey = JSON.readTree(execute(runtime,
                    "{ inventoryItem(warehouse: \"AMS\") { quantity } }",
                    GraphqlRequestContext.legacy(1L, "reader")).json());
            assertTrue(incompleteCompositeKey.at("/errors/0/message").asText()
                    .contains("required argument 'sku' is missing"), target.name());

            GraphqlExecution collection = execute(runtime,
                    "{ customers(first: 2) { edges { cursor node { id name orders { id reference } } } "
                            + "totalCount pageInfo { hasNextPage endCursor } } }",
                    GraphqlRequestContext.legacy(1L, "reader"));
            JsonNode collectionJson = JSON.readTree(collection.json());
            assertEquals(3, collectionJson.at("/data/customers/totalCount").asInt(), target.name());
            assertEquals("AW-001",
                    collectionJson.at("/data/customers/edges/1/node/orders/0/reference").asText(), target.name());
            assertEquals(3, collection.plan().readStepCount(),
                    "root, relation batch, and exact count expected on " + target);

            String firstCursor = collectionJson.at("/data/customers/edges/0/cursor").asText();
            JsonNode continued = JSON.readTree(execute(runtime,
                    "{ customers(first: 1, after: \"" + firstCursor
                            + "\") { edges { node { id } } pageInfo { hasPreviousPage } } }",
                    GraphqlRequestContext.legacy(1L, "reader")).json());
            assertEquals(8, continued.at("/data/customers/edges/0/node/id").asInt(), target.name());

            JsonNode ordered = JSON.readTree(execute(runtime,
                    "{ customers(first: 1, orderBy: [{ name: DESC }]) { "
                            + "edges { cursor node { id name } } pageInfo { hasNextPage } } }",
                    GraphqlRequestContext.legacy(1L, "reader")).json());
            assertEquals("Northwind", ordered.at("/data/customers/edges/0/node/name").asText(), target.name());
            assertEquals(9, ordered.at("/data/customers/edges/0/node/id").asInt(), target.name());
            String orderedCursor = ordered.at("/data/customers/edges/0/cursor").asText();
            JsonNode orderedContinuation = JSON.readTree(execute(runtime,
                    "{ customers(first: 1, after: \"" + orderedCursor
                            + "\", orderBy: [{ name: DESC }]) { edges { cursor node { id name } } } }",
                    GraphqlRequestContext.legacy(1L, "reader")).json());
            assertEquals("Northwind",
                    orderedContinuation.at("/data/customers/edges/0/node/name").asText(), target.name());
            assertEquals(7, orderedContinuation.at("/data/customers/edges/0/node/id").asInt(), target.name());
            String secondOrderedCursor = orderedContinuation.at("/data/customers/edges/0/cursor").asText();
            JsonNode afterTie = JSON.readTree(execute(runtime,
                    "{ customers(first: 1, after: \"" + secondOrderedCursor
                            + "\", orderBy: [{ name: DESC }]) { edges { node { name } } } }",
                    GraphqlRequestContext.legacy(1L, "reader")).json());
            assertEquals("Adventure Works", afterTie.at("/data/customers/edges/0/node/name").asText(),
                    target.name());
            JsonNode orderedBackward = JSON.readTree(execute(runtime,
                    "{ customers(last: 1, orderBy: [{ name: DESC }]) { edges { node { name } } "
                            + "pageInfo { hasPreviousPage } } }",
                    GraphqlRequestContext.legacy(1L, "reader")).json());
            assertEquals("Adventure Works",
                    orderedBackward.at("/data/customers/edges/0/node/name").asText(), target.name());
            assertTrue(orderedBackward.at("/data/customers/pageInfo/hasPreviousPage").asBoolean(), target.name());

            GraphqlExecution relationPageExecution = execute(runtime,
                    "{ customers(first: 2) { edges { node { id orderConnection(first: 1) { "
                            + "edges { cursor node { id reference } } totalCount "
                            + "pageInfo { hasNextPage endCursor } } } } } }",
                    GraphqlRequestContext.legacy(1L, "reader"));
            JsonNode relationPage = JSON.readTree(relationPageExecution.json());
            assertEquals(70, relationPage.at(
                    "/data/customers/edges/0/node/orderConnection/edges/0/node/id").asInt(), target.name());
            assertEquals(2, relationPage.at(
                    "/data/customers/edges/0/node/orderConnection/totalCount").asInt(), target.name());
            assertTrue(relationPage.at(
                    "/data/customers/edges/0/node/orderConnection/pageInfo/hasNextPage").asBoolean(), target.name());
            assertEquals(1, relationPage.at(
                    "/data/customers/edges/1/node/orderConnection/totalCount").asInt(), target.name());
            assertEquals(2, relationPageExecution.plan().readStepCount(), target.name());

            String orderCursor = relationPage.at(
                    "/data/customers/edges/0/node/orderConnection/pageInfo/endCursor").asText();
            JsonNode relationContinuation = JSON.readTree(execute(runtime,
                    "{ customer(id: 7) { orderConnection(first: 1, after: \"" + orderCursor
                            + "\") { edges { node { id } } pageInfo { hasPreviousPage } } } }",
                    GraphqlRequestContext.legacy(1L, "reader")).json());
            assertEquals(71, relationContinuation.at(
                    "/data/customer/orderConnection/edges/0/node/id").asInt(), target.name());
            assertTrue(relationContinuation.at(
                    "/data/customer/orderConnection/pageInfo/hasPreviousPage").asBoolean(), target.name());

            JsonNode filteredRelation = JSON.readTree(execute(runtime,
                    "{ customer(id: 7) { orderConnection(orderId: 71, first: 10) { "
                            + "edges { node { id reference } } totalCount } } }",
                    GraphqlRequestContext.legacy(1L, "reader")).json());
            assertEquals(71, filteredRelation.at(
                    "/data/customer/orderConnection/edges/0/node/id").asInt(), target.name());
            assertEquals(1, filteredRelation.at(
                    "/data/customer/orderConnection/totalCount").asInt(), target.name());

            GraphqlRequestContext activeOnly = new GraphqlRequestContext(
                    1L, "reader", "actor-1", "", "commerce-request", "", List.of(),
                    List.of("activeCustomers"), false, false, false, 0L,
                    Map.of("activeCustomers", true));
            JsonNode filtered = JSON.readTree(execute(runtime,
                    "{ customers(first: 10) { edges { node { id name } } totalCount } }",
                    activeOnly).json());
            assertEquals(2, filtered.at("/data/customers/totalCount").asInt(), target.name());
            assertEquals("Northwind", filtered.at("/data/customers/edges/0/node/name").asText(), target.name());

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE commerce.customers SET name = 'Contoso' WHERE id = 7");
                statement.executeUpdate("INSERT INTO commerce.orders (id, customer_id, reference) "
                        + "VALUES (72, 7, 'CT-003')");
            }

            GraphqlModelRuntime restarted = engine(connection, target).runtime();
            JsonNode changed = JSON.readTree(execute(restarted,
                    "{ customer(id: 7) { name orders { reference } } }",
                    GraphqlRequestContext.legacy(1L, "reader")).json());
            assertEquals("Contoso", changed.at("/data/customer/name").asText(), target.name());
            assertEquals("CT-003", changed.at("/data/customer/orders/2/reference").asText(), target.name());

            JsonNode malformedCursor = JSON.readTree(execute(restarted,
                    "{ customers(first: 1, after: \"not-a-cursor\") { edges { node { id } } } }",
                    GraphqlRequestContext.legacy(1L, "reader")).json());
            assertTrue(malformedCursor.at("/errors/0/message").asText().contains("invalid Relay cursor"),
                    malformedCursor.toString());
        }
    }

    private static GraphqlExecutionEngine engine(Connection connection, DatabaseTarget target) {
        return new GraphqlExecutionEngine(
                "compiled", () -> new SingleConnectionDataSource(connection),
                "isolated commerce " + target.name().toLowerCase(Locale.ROOT), MODEL);
    }

    private static GraphqlExecution execute(
            GraphqlModelRuntime runtime,
            String query,
            GraphqlRequestContext context
    ) {
        return runtime.executeWithPlan(GraphqlRequest.query(query), context);
    }

    private static void deploy(Connection connection, DatabaseTarget target) throws Exception {
        try (Statement statement = connection.createStatement()) {
            if (target == DatabaseTarget.POSTGRESQL) {
                statement.execute("CREATE SCHEMA IF NOT EXISTS commerce");
            } else {
                statement.execute("CREATE DATABASE IF NOT EXISTS public");
                statement.execute("CREATE DATABASE IF NOT EXISTS commerce");
            }
            statement.execute("DROP TABLE IF EXISTS commerce.orders");
            statement.execute("DROP TABLE IF EXISTS commerce.customers");
            statement.execute("DROP TABLE IF EXISTS commerce.inventory_items");
            statement.execute("DROP TABLE IF EXISTS commerce.api_clients");
            statement.execute("DROP TABLE IF EXISTS commerce.countries");
            statement.execute("CREATE TABLE commerce.customers (id BIGINT PRIMARY KEY, "
                    + "name VARCHAR(120) NOT NULL, active BOOLEAN NOT NULL)");
            statement.execute("CREATE TABLE commerce.orders (id BIGINT PRIMARY KEY, "
                    + "customer_id BIGINT NOT NULL, reference VARCHAR(120) NOT NULL, "
                    + "FOREIGN KEY (customer_id) REFERENCES commerce.customers(id))");
            statement.execute("CREATE TABLE commerce.countries (code VARCHAR(8) PRIMARY KEY, "
                    + "name VARCHAR(120) NOT NULL)");
            statement.execute("CREATE TABLE commerce.api_clients (id "
                    + (target == DatabaseTarget.POSTGRESQL ? "UUID" : "CHAR(36)")
                    + " PRIMARY KEY, label VARCHAR(120) NOT NULL)");
            statement.execute("CREATE TABLE commerce.inventory_items (warehouse_code VARCHAR(16) NOT NULL, "
                    + "sku VARCHAR(40) NOT NULL, quantity INTEGER NOT NULL, "
                    + "PRIMARY KEY (warehouse_code, sku))");
        }
        Path migrations = Path.of(System.getProperty(target == DatabaseTarget.POSTGRESQL
                ? "titan.graphql.migrations.dir.commerce"
                : "titan.graphql.migrations.dir.commerce.mysql"));
        apply(connection, target, migrations.resolve("R__titan_010_runtime.sql"));
        apply(connection, target, migrations.resolve("R__titan_020_routines.sql"));
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO commerce.customers (id, name, active) VALUES "
                    + "(7, 'Northwind', true), (8, 'Adventure Works', false), (9, 'Northwind', true)");
            statement.executeUpdate("INSERT INTO commerce.orders (id, customer_id, reference) VALUES "
                    + "(70, 7, 'NW-001'), (71, 7, 'NW-002'), (80, 8, 'AW-001')");
            statement.executeUpdate("INSERT INTO commerce.countries (code, name) VALUES "
                    + "('NL', 'Netherlands')");
            statement.executeUpdate("INSERT INTO commerce.api_clients (id, label) VALUES ('"
                    + CLIENT_ID + "', 'public-client')");
            statement.executeUpdate("INSERT INTO commerce.inventory_items "
                    + "(warehouse_code, sku, quantity) VALUES ('AMS', 'TG-42', 17)");
        }
    }

    private static void apply(Connection connection, DatabaseTarget target, Path script) throws Exception {
        String sql = Files.readString(script);
        if (target == DatabaseTarget.POSTGRESQL) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            return;
        }
        for (String statementSql : splitMySql(sql)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementSql);
            }
        }
    }

    private static List<String> splitMySql(String script) {
        List<String> statements = new ArrayList<>();
        String delimiter = ";";
        StringBuilder buffer = new StringBuilder();
        for (String line : script.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("DELIMITER ")) {
                delimiter = trimmed.substring("DELIMITER ".length()).trim();
                continue;
            }
            if (!buffer.isEmpty()) buffer.append('\n');
            buffer.append(line);
            String current = buffer.toString().trim();
            if (current.endsWith(delimiter)) {
                String statement = current.substring(0, current.length() - delimiter.length()).trim();
                if (!statement.isEmpty()) statements.add(statement);
                buffer.setLength(0);
            }
        }
        if (!buffer.toString().isBlank()) statements.add(buffer.toString().trim());
        return statements;
    }
}
