package io.titan.graphql.codegen;

import com.sun.source.util.JavacTask;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TitanGraphqlDatabaseEngineSourceGeneratorTest {

    private static final String RUNTIME_IDENTITY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void emitsLockedTargetReadsForEveryGeneratedUpdateMutation() throws IOException {
        TitanGraphqlModelDocument model = commerceModel();

        String postgreSqlSource = TitanGraphqlDatabaseEngineSourceGenerator.generate(model, RUNTIME_IDENTITY);
        String mySqlSource = TitanGraphqlDatabaseEngineSourceGenerator.generateMySqlProcedure(model, RUNTIME_IDENTITY);

        assertEquals(model.mutations().size(), occurrences(postgreSqlSource, "SELECT 1 AS tgql_lock FROM "));
        assertEquals(model.mutations().size(), occurrences(mySqlSource, "SELECT 1 AS tgql_lock FROM "));
        assertEquals(model.mutations().size(), occurrences(postgreSqlSource, " FOR UPDATE"));
        assertEquals(model.mutations().size(), occurrences(mySqlSource, " FOR UPDATE"));
        assertEquals(model.mutations().size(), occurrences(postgreSqlSource, "SELECT COUNT(*) AS tgql_found FROM "));
        assertEquals(model.mutations().size(), occurrences(mySqlSource, "SELECT COUNT(*) AS tgql_found FROM "));
        assertFalse(postgreSqlSource.contains("SELECT 1 AS tgql_found FROM "));
        assertFalse(mySqlSource.contains("SELECT 1 AS tgql_found FROM "));
    }

    @Test
    void canonicalizesDeclaredEnumValueOrderInGeneratedRuntimeBehavior() throws IOException {
        TitanGraphqlModelDocument canonical = commerceModel();
        TitanGraphqlModelDocument reordered = TitanGraphqlModelDocumentYaml.parse(
                commerceModelSource().replace(
                        "values: [ACTIVE, INACTIVE, LEGACY]", "values: [LEGACY, INACTIVE, ACTIVE]"));

        assertEquals(
                TitanGraphqlDatabaseEngineSourceGenerator.generate(canonical, RUNTIME_IDENTITY),
                TitanGraphqlDatabaseEngineSourceGenerator.generate(reordered, RUNTIME_IDENTITY)
        );
        assertEquals(
                TitanGraphqlDatabaseEngineSourceGenerator.generateMySqlProcedure(canonical, RUNTIME_IDENTITY),
                TitanGraphqlDatabaseEngineSourceGenerator.generateMySqlProcedure(reordered, RUNTIME_IDENTITY)
        );
    }

    @Test
    void emitsFixedSlotPartitionedRelationConnectionPageSql() throws IOException {
        TitanGraphqlModelDocument model = commerceModel();

        String postgreSql = TitanGraphqlDatabaseEngineSourceGenerator.relationConnectionBatchPageSql(
                model, "Customer", "orderConnection", true);
        String mySql = TitanGraphqlDatabaseEngineSourceGenerator.relationConnectionBatchPageSql(
                model, "Customer", "orderConnection", false);

        for (String sql : List.of(postgreSql, mySql)) {
            assertTrue(sql.contains("ROW_NUMBER() OVER (PARTITION BY tgql_batch_keys.tgql_parent_index"), sql);
            assertTrue(sql.contains("customer_id = tgql_batch_keys.tgql_parent_key"), sql);
            assertTrue(sql.contains("(? = FALSE OR id = ?)"), sql);
            assertTrue(sql.contains("(? = FALSE OR status = ?)"), sql);
            assertTrue(sql.contains("WHERE tgql_batch_row <= ?"), sql);
            assertTrue(sql.endsWith("ORDER BY tgql_batch_parent_index, tgql_batch_row"), sql);
        }
        assertTrue(postgreSql.contains("(VALUES (0, ?, ?), (1, ?, ?),"), postgreSql);
        assertTrue(postgreSql.contains("(63, ?, ?)) AS tgql_batch_keys"), postgreSql);
        assertTrue(mySql.contains("SELECT 0 AS tgql_parent_index, ? AS tgql_parent_active, ? AS tgql_parent_key"),
                mySql);
        assertTrue(mySql.contains("UNION ALL SELECT 63, ?, ?) tgql_batch_keys"), mySql);
        assertEquals(63, occurrences(mySql, " UNION ALL "));
        assertEquals(139, occurrences(postgreSql, "?"));
        assertEquals(139, occurrences(mySql, "?"));
    }

    @Test
    void emitsFixedSlotRelationConnectionCountAndBoundarySql() throws IOException {
        TitanGraphqlModelDocument model = commerceModel();

        for (boolean postgreSql : List.of(true, false)) {
            String count = TitanGraphqlDatabaseEngineSourceGenerator.relationConnectionBatchCountSql(
                    model, "Customer", "orderConnection", postgreSql);
            String boundary = TitanGraphqlDatabaseEngineSourceGenerator.relationConnectionBatchBoundarySql(
                    model, "Customer", "orderConnection", postgreSql);

            assertTrue(count.contains("LEFT JOIN (SELECT customer_id AS tgql_join_key, "
                    + "id AS tgql_count_match FROM commerce.orders"), count);
            assertTrue(count.contains("COUNT(tgql_count_rows.tgql_count_match) AS tgql_total_count"), count);
            assertTrue(count.contains("WHERE tgql_batch_keys.tgql_parent_active = TRUE"), count);
            assertTrue(count.endsWith("ORDER BY tgql_batch_keys.tgql_parent_index"), count);

            assertTrue(boundary.contains("CASE WHEN COUNT(tgql_boundary_rows.tgql_boundary_match) > 0 "
                    + "THEN TRUE ELSE FALSE END AS tgql_has_opposite"), boundary);
            assertTrue(boundary.contains("(? = TRUE AND id <= ?)"), boundary);
            assertTrue(boundary.contains("(? = TRUE AND id >= ?)"), boundary);
            assertTrue(boundary.contains("LEFT JOIN (SELECT customer_id AS tgql_join_key, "
                    + "id AS tgql_boundary_match FROM commerce.orders"), boundary);
            assertEquals(132, occurrences(count, "?"));
            assertEquals(136, occurrences(boundary, "?"));

            if (postgreSql) {
                assertTrue(count.contains("(63, ?, ?)) AS tgql_batch_keys"), count);
                assertTrue(boundary.contains("(63, ?, ?)) AS tgql_batch_keys"), boundary);
            } else {
                assertEquals(63, occurrences(count, " UNION ALL "));
                assertEquals(63, occurrences(boundary, " UNION ALL "));
            }
        }
    }

    @Test
    void bindsReviewedHiddenIntegerConnectionArgumentsByDeclaredType() {
        TitanGraphqlModelDocument model = TitanGraphqlModelDocumentYaml.parse("""
                apiVersion: titan.graphql/v1alpha1
                kind: ProjectionModel
                metadata: { name: hidden-connection-argument }
                roots:
                  articles:
                    type: Article
                    operation: connection
                    pagination:
                      mode: relay
                      defaultPageSize: 10
                      maxPageSize: 100
                      totalCount: exact
                      cursor: { path: id, column: id, direction: asc, tieBreaker: id }
                    arguments:
                      authorId: { type: Int, kind: equals, column: author_id, path: author_id }
                types:
                  Article:
                    table: articles
                    schema: public
                    primaryKey: id
                    fields:
                      id: { type: Int, column: id, nullable: false }
                """);

        for (String source : List.of(
                TitanGraphqlDatabaseEngineSourceGenerator.generate(model, RUNTIME_IDENTITY),
                TitanGraphqlDatabaseEngineSourceGenerator.generateMySqlProcedure(model, RUNTIME_IDENTITY))) {
            assertTrue(source.contains("(? = FALSE OR tgql_root.author_id = ?)"), source);
            assertTrue(source.contains(".setLong("), source);
        }
    }

    @Test
    void emitsAbstractTypeMetadataAndConcreteRuntimeConditionSets() {
        TitanGraphqlModelDocument model = TitanGraphqlModelDocumentYaml.parse("""
                apiVersion: titan.graphql/v1alpha1
                kind: ProjectionModel
                metadata: { name: abstract-model }
                roots:
                  customer:
                    type: Customer
                    outputType: Node
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
                    description: An identifiable object.
                    fields:
                      id: { type: Int!, description: Stable interface identifier. }
                unions:
                  SearchResult:
                    description: Searchable objects.
                    members: [Customer, Order]
                types:
                  Customer:
                    table: customers
                    schema: public
                    primaryKey: id
                    interfaces: [Node]
                    fields:
                      id: { type: Int, column: id, description: Customer identifier. }
                      name:
                        type: String
                        column: name
                        description: Legacy customer name.
                        deprecated: true
                        deprecationReason: Use displayName.
                  Order:
                    table: orders
                    schema: public
                    primaryKey: id
                    interfaces: [Node]
                    fields:
                      id: { type: Int, column: id }
                """);

        String postgreSql = TitanGraphqlDatabaseEngineSourceGenerator.generate(model, RUNTIME_IDENTITY);
        String mySql = TitanGraphqlDatabaseEngineSourceGenerator.generateMySqlProcedure(model, RUNTIME_IDENTITY);

        for (String source : new String[]{postgreSql, mySql}) {
            assertTrue(source.contains("customer:Node:1:T|id=Int!=S;"), source);
            assertTrue(source.contains("customers:NodeConnection:0:O"), source);
            assertTrue(source.contains("node:Node:0:T;"), source);
            assertTrue(source.contains("\"Node\", \"INTERFACE\""), source);
            assertTrue(source.contains("\"SearchResult\", \"UNION\""), source);
            assertTrue(source.contains("tl1;O:Customer;O:Order;"), source);
            assertTrue(source.contains("|Customer|Node|SearchResult|"), source);
            assertTrue(source.contains("|Order|Node|SearchResult|"), source);
            assertTrue(source.contains("@Node"), source);
            assertTrue(source.contains("#Customer"), source);
            assertTrue(source.contains("Stable interface identifier."), source);
            assertTrue(source.contains("Customer identifier."), source);
            assertTrue(source.contains("Legacy customer name."), source);
            assertTrue(source.contains("Use displayName."), source);
        }
    }

    @Test
    void emitsOneRequestLocalLanguagePlanForEverySelectionPath() throws IOException {
        TitanGraphqlModelDocument model = commerceModel();

        String postgreSqlSource = TitanGraphqlDatabaseEngineSourceGenerator.generate(model, RUNTIME_IDENTITY);
        String mySqlSource = TitanGraphqlDatabaseEngineSourceGenerator.generateMySqlProcedure(model, RUNTIME_IDENTITY);

        for (String source : new String[]{postgreSqlSource, mySqlSource}) {
            assertTrue(source.contains("tgql_root.node_id = tgql_f00.long_value"), source);
            assertFalse(source.contains("tgql_root.node_id = tgql_f00.string_value"), source);
            assertTrue(source.contains(
                    "String expectedRuntimeIdentity, String expectedPackageIdentity"), source);
            assertTrue(source.contains(
                    "matchesExpectedPackageIdentity(expectedPackageIdentity, installedPackageIdentity)"), source);
            assertEquals(1, occurrences(source, "String languagePlan ="), source);
            assertEquals(1, occurrences(source, "String requestAst = DatabaseGraphqlAst.parse(query, languagePlan);"), source);
            assertTrue(source.contains("rootSelectionPlanFromAst(query, requestAst, variablesJson, \"Query\", \""), source);
            assertTrue(source.contains("rootFieldSelectionPlanFromAst(query, requestAst, queryRootStart, variablesJson"), source);
            assertTrue(source.contains("selectionFieldStartFromAst(query, requestAst,"), source);
            assertTrue(source.contains("selectionFieldCountFromAst(query, requestAst,"), source);
            assertTrue(source.contains("fieldSelectionPlanFromAst(query, requestAst,"), source);
            assertTrue(source.contains("relayConnectionSelectionIsCountOnlyFromAst(query, requestAst,"), source);
            assertTrue(source.contains("COUNT(tgql_count_rows.tgql_count_match) AS tgql_total_count"), source);
            assertTrue(source.contains("relation Relay count batch did not return exactly one row per active parent"), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.batchPlanFirstOccurrence("), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.batchParentKeysForPlan("), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.batchParentActivityForPlan("), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.batchParentOwnersForPlan("), source);
            assertTrue(source.contains("relation Relay plan partition is invalid"), source);
            assertTrue(source.contains("\"Query|CustomerConnection|CustomerEdge|Customer,Node,SearchResult\""), source);
            assertTrue(source.contains("rootFieldSelectionPlanFromAst(query, requestAst, queryRootStart, variablesJson, \"CustomerConnection\")"), source);
            assertTrue(source.contains("selectionPlanFieldStartForFieldFromAst(query, requestAst,"), source);
            assertTrue(source.contains("selectionPlanFirstFieldStartForFieldFromAst(query, requestAst,"), source);
            assertTrue(source.contains("selectionPlanFieldCountForFieldFromAst(query, requestAst,"), source);
            assertTrue(source.contains("fieldHasNoSelectionSetFromAst(query, requestAst,"), source);
            assertTrue(source.contains("rootFieldMatchesFromAst(query, requestAst,"), source);
            assertTrue(source.contains("responseKeyFromAst(query, requestAst,"), source);
            assertTrue(source.contains("selectionPlanHasDuplicateResponseKeyFromAst(query, requestAst,"), source);
            assertTrue(source.contains("fieldArgumentCountFromAst(requestAst,"), source);
            assertTrue(source.contains("fieldArgumentCountFromAst(requestAst, queryRootStart) > 2"), source);
            assertFalse(source.contains("fieldArgumentCountFromAst(requestAst, queryRootStart) != "), source);
            assertFalse(source.contains("fieldArgumentCountFromAst(requestAst, mutationRootStart) != "), source);
            assertFalse(source.contains("fieldArgumentCountFromAst(requestAst, mutationValidationRootStart) != "), source);
            assertTrue(source.contains("fieldHasOnlyArgumentsFromAst(query, requestAst,"), source);
            assertTrue(source.contains("materializedVariableValuesFromAst(query, requestAst, variablesJson)"), source);
            assertTrue(source.contains("variablesJson = materializedVariables"), source);
            assertFalse(source.contains("operationDirectiveInclusionFromAst("), source);
            assertFalse(source.contains("operationInclusion"), source);
            assertTrue(source.contains("materializedArgumentValue(materializedArguments,"), source);
            assertEquals(2, occurrences(source,
                    "argumentValueFromAstMaterialized(query, requestAst,"), source);
            assertTrue(source.contains("int introspectionItems = 0;"), source);
            assertTrue(source.contains("introspectionRootExpansionCost(query, requestAst"), source);
            assertTrue(source.contains("selected operation exceeds the introspection expansion budget"), source);
            assertTrue(source.contains("introspectionTypeExpansionCostFromAst(query, requestAst"), source);
            assertFalse(source.contains("argumentValueFromAst(query, requestAst,"), source);
            assertTrue(source.contains("argumentVariableTypeIsCompatibleFromAst(query, requestAst,"), source);
            assertTrue(source.contains("argumentVariableTypeIsCompatibleWithNullableArgumentFromAst(query, requestAst,"), source);
            assertTrue(source.contains("optionalArgumentVariableTypeErrorFromAst(query, requestAst,"), source);
            assertTrue(source.contains("relation connection 'Customer.orderConnection' has an unknown, duplicate, or malformed argument"), source);
            assertTrue(source.contains("boolean RelationConnectionValida_"), source);
            assertTrue(source.contains("connection node 'Order'"), source);
            assertTrue(source.contains("errorJsonAt(\"selected operation contains an unknown or unsupported root field\", query, queryRootStart)"), source);
            assertTrue(source.contains("errorJsonAt(\"field 'Customer.name' cannot have a selection\", query,"), source);
            assertTrue(source.contains("DatabaseGraphqlAst.operationKind(requestAst)"), source);
            assertTrue(source.contains("String documentTokens = DatabaseGraphqlLanguage.lexicalTokenStream(query);"), source);
            assertTrue(source.contains("DatabaseGraphqlAst.withExecutableDirectiveDescriptor(requestAst, \"ed1|6:hidden:S:F|7:visible:I:FPI|\")"), source);
            assertTrue(source.contains("dd1;6:hidden58:Skips a field when its required Boolean condition is true.S1:F"), source);
            assertTrue(source.contains("7:visible65:Includes a selection when its required Boolean condition is true.I3:FPI"), source);
            assertTrue(source.contains("preflightDocument(query, documentTokens, variablesJson, extensionsJson,"), source);
            assertTrue(source.contains("selectedOperationDocument(query, documentTokens, operationName)"), source);
            assertTrue(source.contains("preflightSelectedOperationFromAst(query, requestAst, variablesJson, extensionsJson,"), source);
            assertTrue(source.contains("validateSelectedOperationSchemaFromAst(query, requestAst, \"sd1;"), source);
            assertTrue(source.contains("materializedSelectedOperationArgumentValuesFromAst(query, requestAst, materializedVariables, \"sd1;"), source);
            assertTrue(source.contains("customer:Customer:1:O|id=Int!=S;"), source);
            assertTrue(source.contains("customerByIdAndStatus:Customer:1:O|id=Int!=S,status=CustomerStatus!=E=~SU5BQ1RJVkU;"), source);
            assertTrue(source.indexOf("validateSelectedOperationSchemaFromAst(query, requestAst, \"sd1;")
                    < source.indexOf("materializedVariableValuesFromAst(query, requestAst, variablesJson)"), source);
            assertTrue(source.indexOf("materializedVariableValuesFromAst(query, requestAst, variablesJson)")
                    < source.indexOf("materializedSelectedOperationArgumentValuesFromAst(query, requestAst, materializedVariables, \"sd1;"), source);
            assertTrue(source.contains("long deadlineEpochMillis = DatabaseGraphqlEngine.trustedContextDeadlineEpochMillis(trustedContextJson);"), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.deadlineExpired(deadlineEpochMillis)"), source);
            assertTrue(source.contains("request deadline exceeded during database execution"), source);
            assertTrue(source.contains("internalErrorJson(\"installed model identity does not match request\")"), source);
            assertTrue(source.contains("authorizationErrorJsonAt(\"introspection is disabled\", query,"), source);
            assertTrue(source.contains("authorizationErrorJsonAt(\"root 'country' is not authorized\", query,"), source);
            assertTrue(source.contains("relation 'Customer.orderConnection' is not authorized"), source);
            assertFalse(source.contains("root 'orderConnection' is not authorized"), source);
            assertTrue(source.contains("rollbackAuthorizationErrorJson(\"mutation 'renameCustomer' is not authorized\")"), source);
            assertTrue(source.contains("rollbackExecutionErrorJson(\"mutation 'renameCustomer' target row does not exist\")"), source);
            assertTrue(source.contains("deadlineExceededErrorJson(\"request deadline exceeded during database execution\")"), source);
            assertTrue(source.contains("\"id1;"), source);
            assertTrue(source.contains("O:CustomerFilter:"), source);
            assertTrue(source.contains("E:CustomerStatus:ACTIVE|INACTIVE|LEGACY;"), source);
            assertTrue(source.contains("E:OrderStatus:CLOSED|OPEN;"), source);
            assertTrue(source.contains("E:SortDirection:ASC|DESC;"), source);
            assertTrue(source.contains("ev1;"), source);
            assertTrue(source.contains("Customer can place orders."), source);
            assertTrue(source.contains("Historic status retained for compatibility."), source);
            assertTrue(source.contains("Use INACTIVE."), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.enumArgument("), source);
            assertTrue(source.contains("mutation argument 'status' must be a CustomerStatus enum value"), source);
            assertTrue(source.contains("argument 'status' must be a CustomerStatus enum value"), source);
            assertTrue(source.contains("connection argument 'status' must be a CustomerStatus enum value"), source);
            assertTrue(source.contains("connection argument 'status' must be a OrderStatus enum value"), source);
            assertFalse(source.contains("rootFieldSelectionPlan(query, queryRootStart, variablesJson"), source);
            assertFalse(source.contains("rootFieldSelectionPlan(query, languagePlan, queryRootStart, variablesJson"), source);
            assertFalse(source.contains("selectionFieldStart(query, queryRootStart,"), source);
            assertFalse(source.contains("selectionFieldStart(query, languagePlan, queryRootStart,"), source);
            assertFalse(source.contains("rootFieldMatches(query, queryRootStart,"), source);
            assertFalse(source.contains("rootFieldMatches(query, languagePlan, queryRootStart,"), source);
            assertFalse(source.contains("responseKey(query, queryRootStart,"), source);
            assertFalse(source.contains("responseKey(query, languagePlan, queryRootStart,"), source);
            assertFalse(source.contains("selectionPlanHasDuplicateResponseKey(query, queryRootPlan)"), source);
            assertFalse(source.contains("argumentValue(query, queryRootStart,"), source);
            assertFalse(source.contains("argumentValue(query, languagePlan,"), source);
            assertFalse(source.contains("argumentVariableTypeIsCompatible(query, queryRootStart,"), source);
            assertFalse(source.contains("argumentVariableTypeIsCompatible(query, languagePlan,"), source);
            assertFalse(source.contains("argumentVariableTypeIsCompatibleWithNullableArgument(query, languagePlan,"), source);
            assertFalse(source.contains("fieldArgumentCount(query, languagePlan,"), source);
            assertFalse(source.contains("fieldHasOnlyArguments(query, languagePlan,"), source);
            assertFalse(source.contains("DatabaseGraphqlLanguage.operationKind(languagePlan)"), source);
            assertFalse(source.contains("preflightSelectedOperation(query, languagePlan, variablesJson, extensionsJson,"), source);
            assertFalse(source.contains("preflightSelectedOperation(query, languagePlan, requestAst, variablesJson, extensionsJson,"), source);
            assertFalse(source.contains("operationTypeIsMutation(query, operationName)"), source);
            assertFalse(source.contains("preflightRequest(query, operationName"), source);
            assertTrue(source.contains("customer"), source);
        }
        assertTrue(mySqlSource.contains(
                "Connection connection, String requestQuery, String operationName, String variablesJson,"),
                mySqlSource);
        assertTrue(mySqlSource.contains(
                "String query = requestQuery;"), mySqlSource);
        assertFalse(mySqlSource.contains(
                "Connection connection, String query, String operationName, String variablesJson,"),
                mySqlSource);
        assertTrue(mySqlSource.contains(
                "boolean directOperationSelection = false;"), mySqlSource);
        assertTrue(mySqlSource.contains(
                "directOperationSelection = DatabaseGraphqlLanguage.canUseSingleOperationDocumentDirectly(query, documentTokens);"),
                mySqlSource);
        assertTrue(mySqlSource.contains(
                "if (!directOperationSelection) query = DatabaseGraphqlLanguage.selectedOperationDocument(query, documentTokens, operationName);"),
                mySqlSource);
        assertTrue(mySqlSource.contains(
                "String languagePlan = directOperationSelection ? directDocumentPlan : DatabaseGraphqlLanguage.documentPlan(query);"),
                mySqlSource);
        assertTrue(mySqlSource.contains(
                "String selectedOperationKind = DatabaseGraphqlAst.operationKind(requestAst);"),
                mySqlSource);
        assertTrue(mySqlSource.contains("database engine produced an invalid transport response\\\","
                + "\\\"extensions\\\":{\\\"code\\\":\\\"INTERNAL_ERROR\\\"}"), mySqlSource);
        assertTrue(mySqlSource.contains("response exceeds the database engine character budget\\\","
                + "\\\"extensions\\\":{\\\"code\\\":\\\"RESOURCE_LIMIT_ERROR\\\"}"), mySqlSource);
        assertTrue(mySqlSource.contains(
                "if (response.length() == 0 && selectedOperationKind.equals(\"query\")) {"), mySqlSource);
        assertTrue(mySqlSource.contains(
                "if (selectedOperationKind.equals(\"mutation\")) {"), mySqlSource);
        assertEquals(1, occurrences(mySqlSource,
                "DatabaseGraphqlAst.operationKind(requestAst)"), mySqlSource);
    }

    @Test
    void emitsDatabaseDeadlineCheckpointsBeforeGeneratedJdbcWork() throws IOException {
        TitanGraphqlModelDocument model = commerceModel();

        String postgreSqlSource = TitanGraphqlDatabaseEngineSourceGenerator.generate(model, RUNTIME_IDENTITY);
        String mySqlSource = TitanGraphqlDatabaseEngineSourceGenerator.generateMySqlProcedure(model, RUNTIME_IDENTITY);

        assertTrue(postgreSqlSource.contains(
                "if (DatabaseGraphqlEngine.deadlineExpired(deadlineEpochMillis)) return "
                        + "DatabaseGraphqlEngine.deadlineExceededErrorJson(\"request deadline exceeded during database execution\");"),
                postgreSqlSource);
        assertTrue(mySqlSource.contains(
                "if (response.length() == 0 && DatabaseGraphqlEngine.deadlineExpired(deadlineEpochMillis)) {\n"
                        + "                    response = DatabaseGraphqlEngine.deadlineExceededErrorJson("
                        + "\"request deadline exceeded during database execution\");"),
                mySqlSource);
        assertDeadlineCheckpointPrecedes(postgreSqlSource, "PreparedStatement ConnectionPageStatement_");
        assertDeadlineCheckpointPrecedes(mySqlSource, "PreparedStatement ConnectionPageStatement_");
        assertDeadlineCheckpointPrecedes(postgreSqlSource, "PreparedStatement ConnectionBoundaryStatem_");
        assertDeadlineCheckpointPrecedes(mySqlSource, "PreparedStatement ConnectionBoundaryStatem_");
        assertDeadlineCheckpointPrecedes(postgreSqlSource, "PreparedStatement ConnectionCountStatement_");
        assertDeadlineCheckpointPrecedes(mySqlSource, "PreparedStatement ConnectionCountStatement_");
        assertDeadlineCheckpointPrecedes(postgreSqlSource, "PreparedStatement RelationStatement");
        assertDeadlineCheckpointPrecedes(mySqlSource, "PreparedStatement RelationStatement");
        assertDeadlineCheckpointPrecedes(postgreSqlSource, "PreparedStatement MutationLockStatement");
        assertDeadlineCheckpointPrecedes(mySqlSource, "PreparedStatement MutationLockStatement");
        assertDeadlineCheckpointPrecedes(postgreSqlSource, "PreparedStatement MutationCheckStatement");
        assertDeadlineCheckpointPrecedes(mySqlSource, "PreparedStatement MutationCheckStatement");
        assertDeadlineCheckpointPrecedes(postgreSqlSource, "PreparedStatement MutationStatement");
        assertDeadlineCheckpointPrecedes(mySqlSource, "PreparedStatement MutationStatement");
        assertDeadlineCheckpointFollows(postgreSqlSource, "while (ConnectionPageResultSet_");
        assertDeadlineCheckpointFollows(mySqlSource, "while (ConnectionPageResultSet_");
        assertDeadlineCheckpointFollows(postgreSqlSource, "while (RelationResultSet_");
        assertDeadlineCheckpointFollows(mySqlSource, "while (RelationResultSet_");
        assertDeadlineCheckpointFollows(postgreSqlSource, "while (RelationOutputIndex_");
        assertDeadlineCheckpointFollows(mySqlSource, "while (RelationOutputIndex_");
        for (String source : new String[]{postgreSqlSource, mySqlSource}) {
            assertApplicationStatementReservationPrecedes(source,
                    "PreparedStatement statement = connection.prepareStatement");
            assertApplicationStatementReservationPrecedes(source,
                    "PreparedStatement ConnectionPageStatement_");
            assertApplicationStatementReservationPrecedes(source,
                    "PreparedStatement ConnectionBoundaryStatem_");
            assertApplicationStatementReservationPrecedes(source,
                    "PreparedStatement ConnectionCountStatement_");
            assertApplicationStatementReservationPrecedes(source,
                    "PreparedStatement RelationStatement");
            assertApplicationStatementReservationPrecedes(source,
                    "PreparedStatement MutationLockStatement");
            assertApplicationStatementReservationPrecedes(source,
                    "PreparedStatement MutationCheckStatement");
            assertApplicationStatementReservationPrecedes(source,
                    "PreparedStatement MutationStatement");
            int identityLookup = source.indexOf("PreparedStatement packageIdentityStatement");
            int firstReservation = source.indexOf("applicationSqlStatements >= 64");
            assertTrue(identityLookup >= 0 && firstReservation > identityLookup, source);
        }
        assertTrue(postgreSqlSource.contains(
                "return DatabaseGraphqlEngine.rollbackDeadlineExceededErrorJson(\"request deadline exceeded during database execution\");"),
                postgreSqlSource);
        assertTrue(mySqlSource.contains(
                "response = DatabaseGraphqlEngine.rollbackDeadlineExceededErrorJson(\"request deadline exceeded during database execution\");"),
                mySqlSource);
    }

    @Test
    void emitsDatabaseResidentPointRootNonNullPropagation() throws IOException {
        TitanGraphqlModelDocument model = commerceModel();

        String postgreSqlSource = TitanGraphqlDatabaseEngineSourceGenerator.generate(model, RUNTIME_IDENTITY);
        String mySqlSource = TitanGraphqlDatabaseEngineSourceGenerator.generateMySqlProcedure(model, RUNTIME_IDENTITY);

        for (String source : new String[]{postgreSqlSource, mySqlSource}) {
            assertTrue(source.contains("tgql_name_is_null"), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.appendExecutionError("), source);
            assertTrue(source.contains("Cannot return null for non-nullable field Customer.name."), source);
            assertTrue(source.contains("Cannot return null for non-nullable field Order.reference."), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.enumValueIsAllowed("), source);
            assertTrue(source.contains("Enum 'CustomerStatus' cannot represent the stored value."), source);
            assertTrue(source.contains("ACTIVE|INACTIVE"), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.appendExecutionPath(\"[]\""), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.appendExecutionPathIndex("), source);
            assertTrue(source.contains(")), query, "), source);
            assertTrue(source.contains("String completedResponse = \"{\\\"data\\\":{\"")
                    || source.contains("response = \"TITAN-GRAPHQL-TRANSPORT/1 ROLLBACK"), source);
            assertTrue(source.contains("String nullDataResponse = \"{\\\"data\\\":null\"")
                    || source.contains("response = \"TITAN-GRAPHQL-TRANSPORT/1 ROLLBACK"), source);
            assertFalse(source.contains("DatabaseGraphqlEngine.executionResponseJson("), source);
            assertFalse(source.contains("DatabaseGraphqlEngine.executionResponseWithNullDataJson("), source);
            assertTrue(source.contains("while (ConnectionPageResultSet_"), source);
            assertTrue(source.contains("if (!rootPropagatedNull"), source);
            assertTrue(source.contains("exceeds selection hop budget of 2"), source);
            assertTrue(source.contains("tgql_relation_customer_join"), source);
            assertTrue(source.contains("while (RelationResultSet_"), source);
            assertFalse(source.contains("if (RelationResultSet_"), source);
            assertTrue(source.contains("boolean RelationPropagatedNull_"), source);
            assertTrue(source.contains("ORDER BY id LIMIT 101"), source);
            assertTrue(source.contains("exceeds database row budget of 100"), source);
            assertTrue(source.contains("if (RelationItemIndex_") && source.contains(" >= 100L)"), source);
            assertTrue(source.contains("int applicationSqlStatements = 0"), source);
            assertTrue(source.contains("request exceeds application SQL statement budget of 64"), source);
            assertTrue(source.contains("mutationCount > 21"), source);
            assertTrue(source.contains("long decodedApplicationRows = 0L"), source);
            assertTrue(source.contains("request exceeds decoded application row budget of 1000"), source);
            assertTrue(source.contains("trustedContextFlag(trustedContextJson, \"includeExecutionMetrics\")"),
                    source);
            assertTrue(source.contains("DatabaseGraphqlEngine.appendExecutionMetrics("), source);
            assertTrue(source.contains("rollbackResourceLimitErrorJson("), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.responseAssemblyExceeded("), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.prependJsonItem("), source);
            assertTrue(source.contains("Cannot return null for non-nullable field Order.customer."), source);
            assertTrue(source.contains("tgql_batch_keys.tgql_parent_index AS tgql_batch_parent_index, "
                    + "ROW_NUMBER() OVER (PARTITION BY tgql_batch_keys.tgql_parent_index"), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.appendBatchParentKey("), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.appendBatchParentActivity("), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.batchParentActive("), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.batchRelationValue("), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.batchRelationObjectValue("), source);
            assertTrue(source.contains("BatchParentOwners_"), source);
            assertTrue(source.contains("nested relation batch carriers exceed"), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.replaceBatchRelationPlaceholder("), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.replaceBatchRelationCarrierPlaceholder("), source);
            assertTrue(source.contains("static String executeQueryRootCustomers("), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.queryRootExecutionCarrier("), source);
            assertTrue(source.contains("DatabaseGraphqlEngine.queryRootExecutionCarrierIsValid("), source);
            int publicEntry = source.indexOf("public static ");
            int firstRootHelper = source.indexOf("static String executeQueryRootApiClient(");
            assertTrue(publicEntry >= 0 && firstRootHelper > publicEntry, source);
            assertTrue(firstRootHelper - publicEntry < 250_000,
                    "the public whole-request routine must dispatch roots instead of inlining them");
            assertTrue(source.contains("relation batch produced an invalid parent-key carrier"), source);
            assertTrue(source.contains("relation batch placeholder was missing or ambiguous"), source);
            assertFalse(source.contains(".next() && response.length() == 0"), source);
            assertTrue(source.contains("ConnectionNodeSelectionP_"), source);
            assertTrue(source.contains("ConnectionNodeExecutionP_"), source);
            assertFalse(source.contains("nested relation Order.customer is not enabled"), source);
        }
        assertTrue(postgreSqlSource.contains("JOIN (VALUES (0, ?, ?), (1, ?, ?)"), postgreSqlSource);
        assertTrue(mySqlSource.contains("JOIN (SELECT 0 AS tgql_parent_index, ? AS tgql_parent_active"),
                mySqlSource);
        assertTrue(mySqlSource.contains("TITAN-GRAPHQL-TRANSPORT/1 ROLLBACK"), mySqlSource);
        assertTrue(mySqlSource.contains("TITAN-GRAPHQL-TRANSPORT/1 COMMIT"), mySqlSource);
        assertFalse(mySqlSource.contains("DatabaseGraphqlEngine.transactionOutcomeJson("), mySqlSource);
        assertFalse(mySqlSource.contains("DatabaseGraphqlEngine.transportOutcome("), mySqlSource);
        assertFalse(mySqlSource.contains("DatabaseGraphqlEngine.transportResponseJson("), mySqlSource);
        assertTrue(mySqlSource.contains("boolean framedResponse = response != null"), mySqlSource);
        assertTrue(mySqlSource.contains("responseJson.length() > 16384"), mySqlSource);
        assertTrue(mySqlSource.contains("response exceeds the database engine character budget"), mySqlSource);
    }

    @Test
    void excludesRelationsWhoseSelectableCapabilityIsFalse() {
        TitanGraphqlModelDocument model = TitanGraphqlModelDocumentYaml.parse("""
                apiVersion: titan.graphql/v1alpha1
                kind: ProjectionModel
                metadata: { name: private-relation }
                roots:
                  parents:
                    type: Parent
                    operation: connection
                    pagination:
                      mode: relay
                      defaultPageSize: 10
                      maxPageSize: 100
                      totalCount: exact
                      cursor: { path: id, column: id, direction: asc, tieBreaker: id }
                types:
                  Parent:
                    table: parents
                    schema: public
                    primaryKey: id
                    fields:
                      id: { type: Int, column: id, nullable: false }
                    relations:
                      privateChildren:
                        target: Child
                        cardinality: many
                        localColumn: id
                        targetColumn: parent_id
                        capabilities: { selectable: false, batchable: false }
                  Child:
                    table: children
                    schema: public
                    primaryKey: id
                    fields:
                      id: { type: Int, column: id, nullable: false }
                      parentId: { type: Int, column: parent_id, nullable: false }
                """);

        String postgreSql = TitanGraphqlDatabaseEngineSourceGenerator.generate(model, RUNTIME_IDENTITY);
        String mySql = TitanGraphqlDatabaseEngineSourceGenerator.generateMySqlProcedure(model, RUNTIME_IDENTITY);

        assertFalse(postgreSql.contains("privateChildren"), postgreSql);
        assertFalse(mySql.contains("privateChildren"), mySql);
    }

    @Test
    void emitsCapabilityGatedDatabaseResidentIntrospectionIdentityRoots() throws IOException {
        TitanGraphqlModelDocument model = commerceModel();

        String postgreSqlSource = TitanGraphqlDatabaseEngineSourceGenerator.generate(model, RUNTIME_IDENTITY);
        String mySqlSource = TitanGraphqlDatabaseEngineSourceGenerator.generateMySqlProcedure(model, RUNTIME_IDENTITY);

        for (String source : new String[]{postgreSqlSource, mySqlSource}) {
            assertTrue(source.contains("rootFieldMatchesFromAst(query, requestAst, queryRootStart, \"__schema\")"), source);
            assertTrue(source.contains("rootFieldMatchesFromAst(query, requestAst, queryRootStart, \"__type\")"), source);
            assertTrue(source.contains("trustedContextFlag(trustedContextJson, \"introspectionEnabled\")"), source);
            assertTrue(source.contains("argumentValueFromAstMaterialized(query, requestAst, queryRootStart, \"name\", materializedVariables)"), source);
            assertTrue(source.contains("selectionFieldStartFromAst(query, requestAst,"), source);
            assertTrue(source.contains("\"__Schema\""), source);
            assertTrue(source.contains("\"__Type\""), source);
            assertTrue(source.contains("orders:Order:0:LNO;"), source);
            assertTrue(source.contains("CustomerConnection\""), source);
            assertTrue(source.contains("introspectionNamedTypeJson"), source);
            assertTrue(source.contains("introspectionDirectivesJson"), source);
            assertTrue(source.contains("\"__Schema\", \"OBJECT\""), source);
            assertTrue(source.contains("\"__TypeKind\", \"ENUM\""), source);
            assertTrue(source.contains("\"CustomerStatus\", \"ENUM\""), source);
            assertTrue(source.contains("\"OrderStatus\", \"ENUM\""), source);
            assertTrue(source.contains("status:CustomerStatus:0:E;"), source);
            assertTrue(source.contains("nullableStatus:CustomerStatus:1:E;"), source);
            assertTrue(source.contains("status:OrderStatus:0:E;"), source);
            assertTrue(source.contains("6:ACTIVE26:Customer can place orders."), source);
            assertTrue(source.contains("E:CustomerStatus:ACTIVE|INACTIVE|LEGACY;"), source);
            assertTrue(source.contains("status=CustomerStatusFilter"), source);
            assertTrue(source.contains("O:CustomerStatusFilter:eq=CustomerStatus,in=[CustomerStatus!],"
                    + "isNull=Boolean,neq=CustomerStatus;"), source);
            assertTrue(source.contains("eq=CustomerStatus=E,in=[CustomerStatus!]=E,isNull=Boolean=S,"
                    + "neq=CustomerStatus=E"), source);
            assertTrue(source.contains("fields:[__Field!]:1:RO|includeDeprecated=Boolean!=S=false;"), source);
            assertTrue(source.contains("args:[__InputValue!]!:0:RO|includeDeprecated=Boolean!=S=false;"), source);
            assertTrue(source.contains("locations:[__DirectiveLocation!]!:0:RE;"), source);
            assertTrue(source.contains("ev1;6:SCALAR0:00:6:OBJECT0:00:9:INTERFACE"), source);
            assertTrue(source.contains("customer:Customer:1:O|id=Int!=S;"), source);
            assertTrue(source.contains("customerByIdAndStatus:Customer:1:O|id=Int!=S,status=CustomerStatus!=E=~SU5BQ1RJVkU;"), source);
            assertTrue(source.contains("customers:CustomerConnection:0:O|after=String=S,before=String=S,"), source);
            assertTrue(source.contains("customerFeed:CustomerConnection:0:O|after=String=S,before=String=S,"
                    + "filter=CustomerFilter=I,first=Int=S,last=Int=S,status=CustomerStatus=E;"), source);
            assertTrue(source.contains("orderConnection:OrderConnection:0:O|after=String=S,before=String=S,"
                    + "first=Int=S,last=Int=S,orderId=Int=S,status=OrderStatus=E;"), source);
            assertTrue(source.contains("orderBy=[CustomerOrderBy!]=I;"), source);
            assertTrue(source.contains("renameCustomer:Customer:0:O|id=Int!=S,name=String!=S;"), source);
            assertTrue(source.contains("renameCustomerWithInput:Customer:0:O|"
                    + "input=RenameCustomerInput!=I=~e2lkOiA4LCBwYXRjaDoge319;"), source);
            assertTrue(source.contains("setCustomerStatus:Customer:0:O|id=Int!=S,status=CustomerStatus!=E=~QUNUSVZF;"), source);
            assertTrue(source.contains("D:Query.defaultedCustomerFeed.status:8:INACTIVE;"), source);
            assertTrue(source.contains("D:Customer.openOrderConnection.status:4:OPEN;"), source);
            assertTrue(source.contains("D:Mutation.setCustomerStatus.status:6:ACTIVE;"), source);
            assertTrue(source.contains("D:Mutation.renameCustomerWithInput.input:18:{id: 8, patch: {}};"), source);
            assertTrue(source.contains("O:RenameCustomerInput:id=Int!,patch=RenameCustomerPatch!;"), source);
            assertTrue(source.contains("O:RenameCustomerPatch:name=String!;"), source);
            assertTrue(source.contains("D:RenameCustomerPatch.name:"), source);
            assertTrue(source.contains("iv1;2:id4:Int!1:S0"), source);
            assertTrue(source.contains("inputObjectPathValue("), source);
            assertTrue(source.contains("\"patch.name\""), source);
            assertTrue(source.contains("introspection is disabled"), source);
            assertFalse(source.contains("GraphqlIntrospection"), source);
        }
    }

    @Test
    void scopesRecursiveRelationConnectionsByTypedAncestry(@TempDir Path compilationDirectory) throws IOException {
        // The commerce graph is cyclic (Customer -> Order -> Customer). Raise the reviewed
        // generation bound by one only for this source-level regression: a later
        // Customer.orderConnection must not redeclare the outer connection's JDBC locals merely
        // because the GraphQL field name is the same. This is generator metadata, not a request
        // shortcut or an HTTP-side execution path.
        TitanGraphqlModelDocument model = recursiveRelationModel();

        assertRecursiveRelationSourceAnalyzes(model, compilationDirectory, false);
        assertRecursiveRelationSourceAnalyzes(model, compilationDirectory, true);
    }

    @Test
    void physicallyRenamedModelGeneratesWithoutMaintainedFixtureDispatch(@TempDir Path compilationDirectory)
            throws IOException {
        TitanGraphqlModelDocument model;
        try (InputStream input = getClass().getResourceAsStream("/graphql/renamed-vault.titan.graphql.yaml")) {
            assertNotNull(input, "renamed model test resource");
            model = TitanGraphqlModelDocumentYaml.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }

        for (boolean mysql : List.of(false, true)) {
            String source = mysql
                    ? TitanGraphqlDatabaseEngineSourceGenerator.generateMySqlProcedure(model, RUNTIME_IDENTITY)
                    : TitanGraphqlDatabaseEngineSourceGenerator.generate(model, RUNTIME_IDENTITY);
            assertTrue(source.contains("archivedRecordsX7"), source);
            assertTrue(source.contains("VaultRecordX7"), source);
            assertTrue(source.contains("archive_x7.physical_records_x7"), source);
            assertTrue(source.contains("record_key_x7"), source);
            assertTrue(source.contains("displayCaptionX7"), source);
            for (String fixtureName : List.of("Article", "articles", "Customer", "customers", "commerce.orders")) {
                assertFalse(source.contains(fixtureName),
                        () -> "renamed generated artifact leaked maintained fixture name '" + fixtureName + "'");
            }
            assertGeneratedSourceAnalyzes(compilationDirectory,
                    mysql ? "GeneratedDatabaseGraphqlMySqlProcedure" : "GeneratedDatabaseGraphqlSchema",
                    source);
        }
    }

    private static void assertRecursiveRelationSourceAnalyzes(
            TitanGraphqlModelDocument model,
            Path compilationDirectory,
            boolean mysql
    ) throws IOException {
        // Keep only one recursive dialect source and javac AST reachable at a time. Holding both
        // multi-megabyte sources while javac retains the first AST makes the full-suite worker's
        // peak memory depend on test order even though either dialect analyzes independently.
        String source = mysql
                ? TitanGraphqlDatabaseEngineSourceGenerator.generateMySqlProcedure(model, RUNTIME_IDENTITY)
                : TitanGraphqlDatabaseEngineSourceGenerator.generate(model, RUNTIME_IDENTITY);
        assertTrue(occurrences(source, "PreparedStatement ConnectionPageStatement_") >= 1, source);
        assertGeneratedSourceAnalyzes(compilationDirectory,
                mysql ? "GeneratedDatabaseGraphqlMySqlProcedure" : "GeneratedDatabaseGraphqlSchema",
                source);
    }

    private static TitanGraphqlModelDocument commerceModel() throws IOException {
        return TitanGraphqlModelDocumentYaml.parse(commerceModelSource());
    }

    private static TitanGraphqlModelDocument recursiveRelationModel() {
        return TitanGraphqlModelDocumentYaml.parse("""
                apiVersion: titan.graphql/v1alpha1
                kind: ProjectionModel
                metadata: { name: recursive-relations }
                roots:
                  parents:
                    type: Parent
                    operation: connection
                    pagination:
                      mode: relay
                      defaultPageSize: 10
                      maxPageSize: 100
                      totalCount: exact
                      cursor: { path: id, column: id, direction: asc, tieBreaker: id }
                types:
                  Parent:
                    table: parents
                    schema: public
                    primaryKey: id
                    fields:
                      id: { type: Int, column: id, nullable: false }
                    relations:
                      children:
                        target: Child
                        cardinality: many
                        localColumn: id
                        targetColumn: parent_id
                        capabilities:
                          selectable: true
                          batchable: true
                          pagination: relay
                          totalCount: exact
                          defaultPageSize: 10
                          maxPageSize: 100
                          selectionHopBudget: 3
                        arguments:
                          childId:
                            type: Int
                            kind: equals
                            column: id
                            path: id
                  Child:
                    table: children
                    schema: public
                    primaryKey: id
                    fields:
                      id: { type: Int, column: id, nullable: false }
                      parentId: { type: Int, column: parent_id, nullable: false }
                    relations:
                      parent:
                        target: Parent
                        cardinality: one
                        localColumn: parent_id
                        targetColumn: id
                        capabilities:
                          selectable: true
                          batchable: true
                          selectionHopBudget: 3
                """);
    }

    private static String commerceModelSource() throws IOException {
        try (InputStream stream = TitanGraphqlDatabaseEngineSourceGeneratorTest.class
                .getResourceAsStream("/graphql/commerce.titan.graphql.yaml")) {
            if (stream == null) {
                throw new IOException("commerce model test resource is missing");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int position = 0;
        while ((position = value.indexOf(needle, position)) >= 0) {
            count++;
            position += needle.length();
        }
        return count;
    }

    private static void assertGeneratedSourceAnalyzes(
            Path compilationDirectory,
            String className,
            String source
    ) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK compiler is required for generated-source verification");
        Path sourceFile = compilationDirectory.resolve(className + ".java");
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            // Generated engine source is Titan input, not a JVM runtime artifact. Parsing and
            // attribution are the same front-end contract Titan uses; calling `generate()` would
            // reject a valid database routine merely because javac tries to emit one enormous JVM
            // method before Titan can lower it into database routines.
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics, List.of(
                    "-proc:none", "-classpath", System.getProperty("java.class.path")), null,
                    files.getJavaFileObjects(sourceFile.toFile()));
            task.parse();
            task.analyze();
            assertTrue(diagnostics.getDiagnostics().stream()
                            .noneMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR),
                    () -> diagnostics.getDiagnostics().stream().map(Diagnostic::toString)
                            .reduce("generated source did not analyze", (left, right) -> left + "\n" + right));
        }
    }

    private static void assertDeadlineCheckpointPrecedes(String source, String jdbcMarker) {
        int jdbc = source.indexOf(jdbcMarker);
        assertTrue(jdbc >= 0, source);
        int windowStart = Math.max(0, jdbc - 2_048);
        assertTrue(source.substring(windowStart, jdbc)
                        .contains("DatabaseGraphqlEngine.deadlineExpired(deadlineEpochMillis)"),
                source);
    }

    private static void assertDeadlineCheckpointFollows(String source, String loopMarker) {
        int loop = source.indexOf(loopMarker);
        assertTrue(loop >= 0, source);
        // The loop begins with the generated row-budget sentinel before its existing deadline
        // checkpoint. Keep the structural assertion local to that loop without assuming a
        // particular digest length for generated local names.
        int windowEnd = Math.min(source.length(), loop + 2_048);
        String body = source.substring(loop, windowEnd);
        assertTrue(body.contains("DatabaseGraphqlEngine.deadlineExpired(deadlineEpochMillis)"), source);
        if (source.contains("CREATE PROCEDURE")) {
            assertTrue(body.contains("if (response.length() == 0) {"), source);
        }
    }

    private static void assertApplicationStatementReservationPrecedes(String source, String jdbcMarker) {
        int jdbc = source.indexOf(jdbcMarker);
        assertTrue(jdbc >= 0, source);
        int windowStart = Math.max(0, jdbc - 2_048);
        assertTrue(source.substring(windowStart, jdbc).contains("applicationSqlStatements >= 64"), source);
    }
}
