package io.titan.graphql.codegen;

import io.titan.graphql.TitanGraphqlFilterLayout;
import io.titan.graphql.model.TitanGraphqlFieldDocument;
import io.titan.graphql.model.TitanGraphqlContextFilterDocument;
import io.titan.graphql.model.TitanGraphqlDirectiveDocument;
import io.titan.graphql.model.TitanGraphqlEnumDocument;
import io.titan.graphql.model.TitanGraphqlInputObjectDocument;
import io.titan.graphql.model.TitanGraphqlInterfaceDocument;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlMutationDocument;
import io.titan.graphql.model.TitanGraphqlPolicyDocument;
import io.titan.graphql.model.TitanGraphqlRelationDocument;
import io.titan.graphql.model.TitanGraphqlRootDocument;
import io.titan.graphql.model.TitanGraphqlTypeDocument;
import io.titan.graphql.model.TitanGraphqlUnionDocument;
import io.titan.graphql.validation.TitanGraphqlModelDocumentValidator;
import io.titan.graphql.validation.TitanGraphqlValidationReport;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Emits the schema-specific half of the database-resident GraphQL engine.
 *
 * <p>The generated source has no model parser, reflection, collection, HTTP, or framework
 * dependency. It consists solely of constant SQL and static branches derived from the reviewed
 * model. The common request scanner, JSON shaping, and transport contract remain in
 * {@code src/database-engine}; this is deliberately not a replacement per-schema runtime.</p>
 */
public final class TitanGraphqlDatabaseEngineSourceGenerator {

    public static final String PACKAGE = "io.titan.graphql.database.generated";
    public static final String CLASS = "GeneratedDatabaseGraphqlSchema";
    // Each slot is emitted into page, count, and opposite-boundary SQL for every reviewed `in`
    // path. Eight bounds generated request work while still providing a useful list query without
    // request-shaped SQL. Package deployment has its own explicit MySQL packet-size requirement.
    private static final int MAX_STATIC_IN_FILTER_VALUES = 8;
    private static final int MAX_FILTER_PLAN_GROUPS = 3;
    private static final int MAX_FILTER_PLAN_TERMS_PER_GROUP = 3;
    private static final int MAX_STATIC_RELATION_BATCH_KEYS = 64;
    // Relations without a reviewed pagination contract must still have a deterministic database
    // work boundary. Fetching one sentinel row lets the generated executor reject overflow rather
    // than silently truncating a GraphQL list. This is deliberately a per-parent safeguard; the
    // later batching/cost-model milestone must bound aggregate nested-relation work as well.
    private static final int MAX_UNPAGINATED_RELATION_ROWS = 100;
    // A connection with many parents must not each receive an independent 100-row allowance.
    // This counts relation rows decoded across the whole request; the per-relation sentinel still
    // limits the one additional row scanned to discover a local overflow.
    private static final int MAX_APPLICATION_SQL_STATEMENTS_PER_REQUEST = 64;
    private static final int MAX_DECODED_APPLICATION_ROWS_PER_REQUEST = 1000;
    private static final int MAX_INTROSPECTION_ITEMS_PER_REQUEST = 512;
    // Keep this in sync with DatabaseGraphqlEngine's private response boundary. This source set
    // cannot depend on database-engine at build time, and the MySQL public procedure must inline
    // the check instead of making a post-result-set helper call.
    private static final int MAX_RESPONSE_CHARACTERS = 16384;

    private TitanGraphqlDatabaseEngineSourceGenerator() {
    }

    public static String generate(TitanGraphqlModelDocument document, String runtimeIdentity) {
        if (document == null) {
            throw new IllegalArgumentException("model document is required");
        }
        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);
        if (report.blocksDeployment()) {
            throw new IllegalArgumentException("GraphQL model has " + report.errorCount()
                    + " deployment-blocking validation error(s): " + report.issues());
        }
        StringBuilder source = new StringBuilder();
        source.append("package ").append(PACKAGE).append(";\n\n")
                .append("import io.titan.graphql.database.DatabaseGraphqlEngine;\n")
                .append("import io.titan.graphql.database.DatabaseGraphqlAst;\n")
                .append("import io.titan.graphql.database.DatabaseGraphqlLanguage;\n")
                .append("import java.sql.*;\n\n")
                .append("import titan.dsl.StoredFunction;\n\n")
                .append("/** Generated from the reviewed model. DO NOT EDIT. */\n")
                .append("public final class ").append(CLASS).append(" {\n")
                .append("    private ").append(CLASS).append("() { }\n\n");
        emitExecute(source, document, TitanGraphqlModelDocumentJson.semanticHash(document), runtimeIdentity);
        emitQueryRootHelpers(source, document, false);
        emitPostgresInternalHelpers(source, document);
        source.append("}\n");
        return source.toString();
    }

    /** Partitions collected Relay relation occurrences by exact AST-backed operation plan. */
    private static void emitRelayRelationPlanBatches(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument sourceType,
            TitanGraphqlRelationDocument relation,
            boolean mysql,
            String scope,
            String parentJsonCarrier,
            String propagatedNull,
            String decodedApplicationRows,
            String nodeAncestorTypePath,
            int relationHops,
            int activeSelectionHopBudget
    ) {
        String indent = mysql ? "                " : "            ";
        String nested = indent + "    ";
        String plans = batchPlanStarts(scope, relation);
        String allKeys = batchParentKeys(scope, relation);
        String allActivity = batchParentActivity(scope, relation);
        String allPaths = batchParentPaths(scope, relation);
        String partitionScope = scope + "_relay_plan_" + relation.name();
        String planCount = scopedLocal("relayPlanCount", partitionScope, "value");
        String planIndex = scopedLocal("relayPlanIndex", partitionScope, "value");
        String selectedStart = scopedLocal("relayPlanStart", partitionScope, "value");
        String keys = scopedLocal("relayPlanKeys", partitionScope, "value");
        String activity = scopedLocal("relayPlanActivity", partitionScope, "value");
        String paths = scopedLocal("relayPlanPaths", partitionScope, "value");
        String owners = scopedLocal("relayPlanOwners", partitionScope, "value");
        String rows = scopedLocal("relayPlanRows", partitionScope, "value");
        String cursors = scopedLocal("relayPlanCursors", partitionScope, "value");
        String counts = scopedLocal("relayPlanCounts", partitionScope, "value");
        String boundaries = scopedLocal("relayPlanBoundaries", partitionScope, "value");

        source.append(indent).append("int ").append(planCount)
                .append(" = DatabaseGraphqlEngine.batchParentKeyCount(").append(plans).append(");\n")
                .append(indent).append("if (").append(planCount).append(" < 0 || ")
                .append(planCount).append(" != DatabaseGraphqlEngine.batchParentKeyCount(")
                .append(allKeys).append(") || ").append(planCount)
                .append(" != DatabaseGraphqlEngine.batchParentActivityCount(").append(allActivity)
                .append(") || ").append(planCount)
                .append(" != DatabaseGraphqlEngine.batchParentKeyCount(").append(allPaths).append(")) {\n");
        emitInternalFailureAt(source, mysql, nested,
                "relation Relay plan carrier does not match its parent carriers", "0");
        source.append(indent).append("}\n")
                .append(indent).append("int ").append(planIndex).append(" = 0;\n")
                .append(indent).append("while (").append(planIndex).append(" < ").append(planCount)
                .append(mysql ? " && response.length() == 0" : "").append(") {\n")
                .append(nested).append("int ").append(selectedStart)
                .append(" = DatabaseGraphqlEngine.batchPlanStart(").append(plans).append(", ")
                .append(planIndex).append(");\n")
                .append(nested).append("if (").append(selectedStart).append(" < 0) {\n");
        emitInternalFailureAt(source, mysql, nested + "    ",
                "relation Relay plan carrier contains an invalid AST start", "0");
        source.append(nested).append("} else if (DatabaseGraphqlEngine.batchPlanFirstOccurrence(")
                .append(plans).append(", ").append(planIndex).append(")) {\n")
                .append(nested).append("    String ").append(keys)
                .append(" = DatabaseGraphqlEngine.batchParentKeysForPlan(").append(allKeys).append(", ")
                .append(plans).append(", ").append(selectedStart).append(");\n")
                .append(nested).append("    String ").append(activity)
                .append(" = DatabaseGraphqlEngine.batchParentActivityForPlan(").append(allActivity)
                .append(", ").append(plans).append(", ").append(selectedStart).append(");\n")
                .append(nested).append("    String ").append(paths)
                .append(" = DatabaseGraphqlEngine.batchParentKeysForPlan(").append(allPaths).append(", ")
                .append(plans).append(", ").append(selectedStart).append(");\n")
                .append(nested).append("    String ").append(owners)
                .append(" = DatabaseGraphqlEngine.batchParentOwnersForPlan(").append(plans).append(", ")
                .append(selectedStart).append(");\n")
                .append(nested).append("    int relayPlanParentCount = DatabaseGraphqlEngine.batchParentKeyCount(")
                .append(keys).append(");\n")
                .append(nested).append("    if (relayPlanParentCount <= 0 || relayPlanParentCount != ")
                .append("DatabaseGraphqlEngine.batchParentActivityCount(").append(activity)
                .append(") || relayPlanParentCount != DatabaseGraphqlEngine.batchParentKeyCount(")
                .append(paths).append(") || relayPlanParentCount != DatabaseGraphqlEngine.batchParentKeyCount(")
                .append(owners).append(") || DatabaseGraphqlEngine.responseAssemblyExceeded(").append(keys)
                .append(") || DatabaseGraphqlEngine.responseAssemblyExceeded(").append(activity)
                .append(") || DatabaseGraphqlEngine.responseAssemblyExceeded(").append(paths)
                .append(") || DatabaseGraphqlEngine.responseAssemblyExceeded(").append(owners)
                .append(")) {\n");
        emitInternalFailureAt(source, mysql, nested + "        ",
                "relation Relay plan partition is invalid", selectedStart);
        source.append(nested).append("    }\n");
        emitRelayRelationCountBatch(source, document, sourceType, relation, mysql, scope,
                parentJsonCarrier, propagatedNull, decodedApplicationRows, nodeAncestorTypePath,
                selectedStart, keys, activity, paths, owners, counts);
        emitRelayRelationPageBatch(source, document, root, sourceType, relation, mysql, scope,
                parentJsonCarrier, propagatedNull, decodedApplicationRows, nodeAncestorTypePath,
                relationHops, activeSelectionHopBudget, selectedStart, keys, activity, paths, owners,
                rows, cursors, counts, boundaries);
        source.append(nested).append("}\n")
                .append(nested).append(planIndex).append("++;\n")
                .append(indent).append("}\n");
    }

    /** Partitions unpaginated relation occurrences by alias/selection AST plan before batching. */
    private static void emitUnpaginatedRelationPlanBatches(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument sourceType,
            TitanGraphqlRelationDocument relation,
            boolean mysql,
            String scope,
            String parentJsonCarrier,
            String propagatedNull,
            String decodedApplicationRows,
            String nodeAncestorTypePath,
            int relationHops,
            int activeSelectionHopBudget,
            String parentRowsCarrier,
            String allParentOwnersCarrier
    ) {
        String indent = mysql ? "                " : "            ";
        String nested = indent + "    ";
        String plans = batchPlanStarts(scope, relation);
        String allKeys = batchParentKeys(scope, relation);
        String allActivity = batchParentActivity(scope, relation);
        String allPaths = batchParentPaths(scope, relation);
        String partitionScope = scope + "_relation_plan_" + relation.name();
        String planCount = scopedLocal("relationPlanCount", partitionScope, "value");
        String planIndex = scopedLocal("relationPlanIndex", partitionScope, "value");
        String selectedStart = scopedLocal("relationPlanStart", partitionScope, "value");
        String keys = scopedLocal("relationPlanKeys", partitionScope, "value");
        String activity = scopedLocal("relationPlanActivity", partitionScope, "value");
        String paths = scopedLocal("relationPlanPaths", partitionScope, "value");
        String placeholderOwners = scopedLocal("relationPlanPlaceholderOwners", partitionScope, "value");
        String parentOwners = scopedLocal("relationPlanParentOwners", partitionScope, "value");
        String rows = scopedLocal("relationPlanRows", partitionScope, "value");
        String parentCount = scopedLocal("relationPlanParentCount", partitionScope, "value");

        source.append(indent).append("int ").append(planCount)
                .append(" = DatabaseGraphqlEngine.batchParentKeyCount(").append(plans).append(");\n")
                .append(indent).append("if (").append(planCount).append(" < 0 || ")
                .append(planCount).append(" != DatabaseGraphqlEngine.batchParentKeyCount(")
                .append(allKeys).append(") || ").append(planCount)
                .append(" != DatabaseGraphqlEngine.batchParentActivityCount(").append(allActivity)
                .append(") || ").append(planCount)
                .append(" != DatabaseGraphqlEngine.batchParentKeyCount(").append(allPaths).append(")) {\n");
        emitInternalFailureAt(source, mysql, nested,
                "relation plan carrier does not match its parent carriers", "0");
        source.append(indent).append("}\n")
                .append(indent).append("int ").append(planIndex).append(" = 0;\n")
                .append(indent).append("while (").append(planIndex).append(" < ").append(planCount)
                .append(mysql ? " && response.length() == 0" : "").append(") {\n")
                .append(nested).append("int ").append(selectedStart)
                .append(" = DatabaseGraphqlEngine.batchPlanStart(").append(plans).append(", ")
                .append(planIndex).append(");\n")
                .append(nested).append("if (").append(selectedStart).append(" < 0) {\n");
        emitInternalFailureAt(source, mysql, nested + "    ",
                "relation plan carrier contains an invalid AST start", "0");
        source.append(nested).append("} else if (DatabaseGraphqlEngine.batchPlanFirstOccurrence(")
                .append(plans).append(", ").append(planIndex).append(")) {\n")
                .append(nested).append("    String ").append(keys)
                .append(" = DatabaseGraphqlEngine.batchParentKeysForPlan(").append(allKeys).append(", ")
                .append(plans).append(", ").append(selectedStart).append(");\n")
                .append(nested).append("    String ").append(activity)
                .append(" = DatabaseGraphqlEngine.batchParentActivityForPlan(").append(allActivity)
                .append(", ").append(plans).append(", ").append(selectedStart).append(");\n")
                .append(nested).append("    String ").append(paths)
                .append(" = DatabaseGraphqlEngine.batchParentKeysForPlan(").append(allPaths).append(", ")
                .append(plans).append(", ").append(selectedStart).append(");\n")
                .append(nested).append("    String ").append(placeholderOwners)
                .append(" = DatabaseGraphqlEngine.batchParentOwnersForPlan(").append(plans).append(", ")
                .append(selectedStart).append(");\n");
        if (parentRowsCarrier != null) {
            source.append(nested).append("    String ").append(parentOwners)
                    .append(" = DatabaseGraphqlEngine.batchParentKeysForPlan(")
                    .append(allParentOwnersCarrier).append(", ").append(plans).append(", ")
                    .append(selectedStart).append(");\n");
        }
        source.append(nested).append("    int ").append(parentCount)
                .append(" = DatabaseGraphqlEngine.batchParentKeyCount(").append(keys).append(");\n")
                .append(nested).append("    if (").append(parentCount).append(" <= 0 || ")
                .append(parentCount).append(" != DatabaseGraphqlEngine.batchParentActivityCount(")
                .append(activity).append(") || ").append(parentCount)
                .append(" != DatabaseGraphqlEngine.batchParentKeyCount(").append(paths).append(") || ")
                .append(parentCount).append(" != DatabaseGraphqlEngine.batchParentKeyCount(")
                .append(placeholderOwners).append(")");
        if (parentRowsCarrier != null) {
            source.append(" || ").append(parentCount)
                    .append(" != DatabaseGraphqlEngine.batchParentKeyCount(").append(parentOwners).append(")");
        }
        source.append(") {\n");
        emitInternalFailureAt(source, mysql, nested + "        ",
                "relation plan partition is invalid", selectedStart);
        source.append(nested).append("    }\n")
                .append(nested).append("    String ").append(rows).append(" = \"br1;\";\n");
        emitUnpaginatedRelationBatch(source, document, root, sourceType, relation, mysql, scope,
                parentJsonCarrier, propagatedNull, decodedApplicationRows, nodeAncestorTypePath,
                relationHops, activeSelectionHopBudget, parentRowsCarrier,
                parentRowsCarrier == null ? null : parentOwners,
                selectedStart, keys, activity, paths, rows, placeholderOwners);
        source.append(nested).append("}\n")
                .append(nested).append(planIndex).append("++;\n")
                .append(indent).append("}\n");
    }

    /**
     * Emits the MySQL entry-point variant for the same reviewed model. MySQL forbids the dynamic
     * prepared reads used by this proof inside a stored function, so its public entry is a
     * procedure that returns exactly one final {@code response_json} result set. The procedure
     * consumes every internal read itself; the final result set is the dialect adapter for the
     * same logical whole-request response contract as PostgreSQL's text-returning function.
     */
    public static String generateMySqlProcedure(TitanGraphqlModelDocument document, String runtimeIdentity) {
        if (document == null) {
            throw new IllegalArgumentException("model document is required");
        }
        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);
        if (report.blocksDeployment()) {
            throw new IllegalArgumentException("GraphQL model has " + report.errorCount()
                    + " deployment-blocking validation error(s): " + report.issues());
        }
        StringBuilder source = new StringBuilder();
        source.append("package ").append(PACKAGE).append(";\n\n")
                .append("import io.titan.graphql.database.DatabaseGraphqlEngine;\n")
                .append("import io.titan.graphql.database.DatabaseGraphqlAst;\n")
                .append("import io.titan.graphql.database.DatabaseGraphqlLanguage;\n")
                .append("import java.sql.*;\n\n")
                .append("import titan.dsl.SQL;\n")
                .append("import titan.dsl.SqlDialect;\n")
                .append("import titan.dsl.StoredProcedure;\n\n")
                .append("/** Generated from the reviewed model. DO NOT EDIT. */\n")
                .append("public final class GeneratedDatabaseGraphqlMySqlProcedure {\n")
                .append("    private GeneratedDatabaseGraphqlMySqlProcedure() { }\n\n");
        emitMySqlProcedureExecute(source, document, TitanGraphqlModelDocumentJson.semanticHash(document), runtimeIdentity);
        emitQueryRootHelpers(source, document, true);
        emitMySqlInternalHelpers(source, document);
        source.append("}\n");
        return source.toString();
    }

    private static void emitExecute(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            String modelHash,
            String runtimeIdentity
    ) {
        requireRuntimeIdentity(runtimeIdentity);
        String inputTypeDescriptor = inputTypeDescriptor(document);
        String schemaValidationDescriptor = schemaValidationDescriptor(document);
        String executableDirectiveDescriptor = executableDirectiveDescriptor(document);
        source.append("    @StoredFunction\n")
                .append("    public static String executeGraphqlRequest(\n")
                .append("            Connection connection, String query, String operationName, String variablesJson,\n")
                .append("            String extensionsJson, String trustedContextJson, boolean allowMutations, String expectedModelHash,\n")
                .append("            String expectedRuntimeIdentity, String expectedPackageIdentity\n")
                .append("    ) throws SQLException {\n")
                .append("        if (!DatabaseGraphqlEngine.matchesExpectedModelHash(expectedModelHash, \"")
                .append(javaString(modelHash)).append("\")) return DatabaseGraphqlEngine.internalErrorJson(\"installed model identity does not match request\");\n")
                .append("        if (!DatabaseGraphqlEngine.matchesExpectedRuntimeIdentity(expectedRuntimeIdentity, \"")
                .append(javaString(runtimeIdentity)).append("\")) return DatabaseGraphqlEngine.internalErrorJson(\"installed runtime identity does not match request\");\n")
                .append("        PreparedStatement packageIdentityStatement = connection.prepareStatement(\"SELECT package_identity FROM public.titan_graphql_package_identity WHERE entry_point = ?\");\n")
                .append("        packageIdentityStatement.setString(1, \"public.execute_graphql_request\");\n")
                .append("        ResultSet packageIdentityResultSet = packageIdentityStatement.executeQuery();\n")
                .append("        String installedPackageIdentity = \"\";\n")
                .append("        if (packageIdentityResultSet.next()) installedPackageIdentity = packageIdentityResultSet.getString(\"package_identity\");\n")
                .append("        if (!DatabaseGraphqlEngine.matchesExpectedPackageIdentity(expectedPackageIdentity, installedPackageIdentity)) return DatabaseGraphqlEngine.internalErrorJson(\"installed package identity does not match request\");\n")
                .append("        String documentTokens = DatabaseGraphqlLanguage.lexicalTokenStream(query);\n")
                .append("        String preflight = DatabaseGraphqlEngine.preflightDocument(query, documentTokens, variablesJson, extensionsJson, trustedContextJson);\n")
                .append("        if (preflight.length() != 0) return preflight;\n")
                .append("        query = DatabaseGraphqlLanguage.selectedOperationDocument(query, documentTokens, operationName);\n")
                .append("        if (query.length() == 0) return DatabaseGraphqlEngine.errorJson(\"operation selection failed: provide one operation or its exact operation name\");\n")
                .append("        String languagePlan = DatabaseGraphqlLanguage.documentPlan(query);\n")
                .append("        String requestAst = DatabaseGraphqlAst.parse(query, languagePlan);\n")
                .append("        if (requestAst.length() == 0) return DatabaseGraphqlEngine.internalErrorJson(\"selected operation has an invalid typed AST\");\n")
                .append("        requestAst = DatabaseGraphqlAst.withExecutableDirectiveDescriptor(requestAst, \"")
                .append(javaString(executableDirectiveDescriptor)).append("\");\n")
                .append("        if (requestAst.length() == 0) return DatabaseGraphqlEngine.internalErrorJson(\"installed executable directive metadata is invalid\");\n")
                .append("        preflight = DatabaseGraphqlEngine.preflightSelectedOperationFromAst(query, requestAst, variablesJson, extensionsJson, trustedContextJson, allowMutations, \"")
                .append(javaString(inputTypeDescriptor)).append("\");\n")
                .append("        if (preflight.length() != 0) return preflight;\n")
                .append("        preflight = DatabaseGraphqlEngine.validateSelectedOperationSchemaFromAst(query, requestAst, \"")
                .append(javaString(schemaValidationDescriptor)).append("\");\n")
                .append("        if (preflight.length() != 0) return preflight;\n")
                .append("        String materializedVariables = DatabaseGraphqlEngine.materializedVariableValuesFromAst(query, requestAst, variablesJson);\n")
                .append("        if (materializedVariables.length() == 0) return DatabaseGraphqlEngine.internalErrorJson(\"selected operation variable materialization failed\");\n")
                .append("        String materializedArguments = DatabaseGraphqlEngine.materializedSelectedOperationArgumentValuesFromAst(query, requestAst, materializedVariables, \"")
                .append(javaString(schemaValidationDescriptor)).append("\", \"")
                .append(javaString(inputTypeDescriptor)).append("\");\n")
                .append("        if (DatabaseGraphqlEngine.isErrorJson(materializedArguments)) return materializedArguments;\n")
                .append("        if (!materializedArguments.startsWith(\"ma1;\")) return DatabaseGraphqlEngine.internalErrorJson(\"selected operation argument materialization failed\");\n")
                .append("        variablesJson = materializedVariables;\n")
                .append("        long deadlineEpochMillis = DatabaseGraphqlEngine.trustedContextDeadlineEpochMillis(trustedContextJson);\n")
                .append("        String actorRole = DatabaseGraphqlEngine.trustedContextString(trustedContextJson, \"actorRole\");\n")
                .append("        int applicationSqlStatements = 0;\n")
                .append("        long decodedApplicationRows = 0L;\n");
        emitMutationExecute(source, document, false);
        source.append("        String members = \"\";\n")
                .append("        String executionErrors = \"\";\n")
                .append("        int introspectionItems = 0;\n")
                .append("        int requestedRootCount = DatabaseGraphqlEngine.rootFieldCountForTypeFromAst(query, requestAst, \"Query\");\n")
                .append("        String queryRootPlan = DatabaseGraphqlEngine.rootSelectionPlanFromAst(query, requestAst, variablesJson, \"Query\", \"")
                .append(javaString(pointRootNames(document))).append("\");\n")
                .append("        int enabledRootCount = DatabaseGraphqlEngine.selectionPlanCount(queryRootPlan);\n")
                .append("        if (enabledRootCount < 0) return DatabaseGraphqlEngine.errorJson(\"selected operation has an invalid or unsupported directive\");\n")
                .append("        if (DatabaseGraphqlEngine.selectionPlanHasDuplicateResponseKeyFromAst(query, requestAst, queryRootPlan)) return DatabaseGraphqlEngine.errorJson(\"selected operation needs unsupported field merging for a duplicate response key\");\n")
                .append("        int introspectionBudgetRootIndex = 0;\n")
                .append("        while (introspectionBudgetRootIndex < enabledRootCount) {\n")
                .append("            int introspectionBudgetRootStart = DatabaseGraphqlEngine.selectionPlanFieldStart(queryRootPlan, introspectionBudgetRootIndex);\n")
                .append("            int introspectionRootCost = introspectionRootExpansionCost(query, requestAst, variablesJson, materializedVariables, introspectionBudgetRootStart);\n")
                .append("            if (introspectionRootCost > ").append(MAX_INTROSPECTION_ITEMS_PER_REQUEST)
                .append(" - introspectionItems) return DatabaseGraphqlEngine.resourceLimitErrorJsonAt(\"selected operation exceeds the introspection expansion budget\", query, introspectionBudgetRootStart);\n")
                .append("            introspectionItems = introspectionItems + introspectionRootCost;\n")
                .append("            introspectionBudgetRootIndex++;\n")
                .append("        }\n")
                .append("        int queryRootIndex = 0;\n")
                .append("        while (queryRootIndex < enabledRootCount) {\n")
                .append("            int queryRootStart = DatabaseGraphqlEngine.selectionPlanFieldStart(queryRootPlan, queryRootIndex);\n")
                .append("            if (DatabaseGraphqlEngine.deadlineExpired(deadlineEpochMillis)) return DatabaseGraphqlEngine.deadlineExceededErrorJson(\"request deadline exceeded during database execution\");\n")
                .append("            boolean queryRootMatched = false;\n");
        String introspectionMembers = scopedLocal("introspectionRootMembers", "Query", "value");
        source.append("            String ").append(introspectionMembers)
                .append(" = executeIntrospectionRoot(query, requestAst, variablesJson, materializedVariables, trustedContextJson, queryRootStart);\n")
                .append("            if (DatabaseGraphqlEngine.isErrorJson(").append(introspectionMembers).append(")) return ")
                .append(introspectionMembers).append(";\n")
                .append("            if (").append(introspectionMembers).append(".length() != 0) {\n")
                .append("                queryRootMatched = true;\n")
                .append("                members = DatabaseGraphqlEngine.appendJsonItem(members, ")
                .append(introspectionMembers).append(");\n")
                .append("            }\n");
        source.append("            if (!queryRootMatched && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, queryRootStart, \"__typename\")) {\n")
                .append("                queryRootMatched = true;\n")
                .append("                if (!DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, queryRootStart, \"\")) return DatabaseGraphqlEngine.errorJsonAt(\"field 'Query.__typename' has an argument\", query, queryRootStart);\n")
                .append("                if (!DatabaseGraphqlEngine.fieldHasNoSelectionSetFromAst(query, requestAst, queryRootStart)) return DatabaseGraphqlEngine.errorJsonAt(\"field 'Query.__typename' cannot have a selection\", query, queryRootStart);\n")
                .append("                members = DatabaseGraphqlEngine.appendJsonMember(members, DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, queryRootStart, \"__typename\"), DatabaseGraphqlEngine.jsonString(\"Query\"));\n")
                .append("            }\n");

        for (TitanGraphqlRootDocument root : sortedRoots(document)) {
            source.append("            if (!queryRootMatched && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, queryRootStart, \"")
                    .append(javaString(root.name())).append("\")) {\n")
                    .append("                queryRootMatched = true;\n");
            if (supportsGeneratedQueryRoot(root, document)) {
                emitQueryRootDispatchBranch(source, root, false);
            } else {
                source.append("                return DatabaseGraphqlEngine.unsupportedOperationErrorJsonAt(\"")
                        .append(javaString(connectionSupportFailure(root))).append("\", query, queryRootStart);\n")
                        .append("            }\n");
            }
        }
        source.append("            if (!queryRootMatched) {\n")
                .append("                return DatabaseGraphqlEngine.errorJsonAt(\"selected operation contains an unknown or unsupported root field\", query, queryRootStart);\n")
                .append("            }\n")
                .append("            if (DatabaseGraphqlEngine.responseAssemblyExceeded(members) || DatabaseGraphqlEngine.responseAssemblyExceeded(executionErrors)) return DatabaseGraphqlEngine.resourceLimitErrorJson(\"response exceeds the database engine character budget\");\n")
                .append("            queryRootIndex++;\n")
                .append("        }\n")
                .append("        if (requestedRootCount == 0) {\n")
                .append("            return DatabaseGraphqlEngine.errorJson(\"selected operation has no supported query root\");\n")
                .append("        }\n")
                .append("        String completedResponse = \"{\\\"data\\\":{\" + (members == null ? \"\" : members) + \"}\";\n")
                .append("        if (executionErrors == null || executionErrors.length() == 0) completedResponse = completedResponse + \"}\";\n")
                .append("        else completedResponse = completedResponse + \",\\\"errors\\\":[\" + executionErrors + \"]}\";\n")
                .append("        if (DatabaseGraphqlEngine.trustedContextFlag(trustedContextJson, \"includeExecutionMetrics\")) completedResponse = DatabaseGraphqlEngine.appendExecutionMetrics(completedResponse, applicationSqlStatements, decodedApplicationRows);\n")
                .append("        if (DatabaseGraphqlEngine.responseAssemblyExceeded(completedResponse)) return DatabaseGraphqlEngine.resourceLimitErrorJson(\"response exceeds the database engine character budget\");\n")
                .append("        return DatabaseGraphqlEngine.transactionOutcomeJson(completedResponse, \"ROLLBACK\");\n")
                .append("    }\n\n");
    }

    private static void emitMySqlProcedureExecute(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            String modelHash,
            String runtimeIdentity
    ) {
        requireRuntimeIdentity(runtimeIdentity);
        String inputTypeDescriptor = inputTypeDescriptor(document);
        String schemaValidationDescriptor = schemaValidationDescriptor(document);
        String executableDirectiveDescriptor = executableDirectiveDescriptor(document);
        source.append("    @StoredProcedure\n")
                .append("    public static void executeGraphqlRequest(\n")
                .append("            Connection connection, String requestQuery, String operationName, String variablesJson,\n")
                .append("            String extensionsJson, String trustedContextJson, boolean allowMutations, String expectedModelHash,\n")
                .append("            String expectedRuntimeIdentity, String expectedPackageIdentity\n")
                .append("    ) throws SQLException {\n")
                .append("        String response = \"\";\n")
                .append("        String query = requestQuery;\n")
                .append("        if (!DatabaseGraphqlEngine.matchesExpectedModelHash(expectedModelHash, \"")
                .append(javaString(modelHash)).append("\")) {\n")
                .append("            response = DatabaseGraphqlEngine.internalErrorJson(\"installed model identity does not match request\");\n")
                .append("        } else if (!DatabaseGraphqlEngine.matchesExpectedRuntimeIdentity(expectedRuntimeIdentity, \"")
                .append(javaString(runtimeIdentity)).append("\")) {\n")
                .append("            response = DatabaseGraphqlEngine.internalErrorJson(\"installed runtime identity does not match request\");\n")
                .append("        } else {\n")
                .append("            PreparedStatement packageIdentityStatement = connection.prepareStatement(\"SELECT package_identity FROM titan_graphql_package_identity WHERE entry_point = ?\");\n")
                .append("            packageIdentityStatement.setString(1, \"public.execute_graphql_request\");\n")
                .append("            ResultSet packageIdentityResultSet = packageIdentityStatement.executeQuery();\n")
                .append("            String installedPackageIdentity = \"\";\n")
                .append("            if (packageIdentityResultSet.next()) installedPackageIdentity = packageIdentityResultSet.getString(\"package_identity\");\n")
                .append("            if (!DatabaseGraphqlEngine.matchesExpectedPackageIdentity(expectedPackageIdentity, installedPackageIdentity)) {\n")
                .append("            response = DatabaseGraphqlEngine.internalErrorJson(\"installed package identity does not match request\");\n")
                .append("            } else {\n")
                .append("            String documentTokens = DatabaseGraphqlLanguage.lexicalTokenStream(query);\n")
                .append("            String preflight = DatabaseGraphqlEngine.preflightDocument(query, documentTokens, variablesJson, extensionsJson, trustedContextJson);\n")
                .append("            if (preflight.length() != 0) {\n")
                .append("                response = preflight;\n")
                .append("            } else {\n")
                // With no requested operation name, a document containing exactly one operation
                // is already the complete selected document. Keep its database-built language
                // plan and continue directly, rather than entering the much deeper
                // fragment-selection helper chain merely to reproduce the same source. Explicit
                // operation names and multi-operation documents retain the common selector.
                .append("            String directDocumentPlan = \"\";\n")
                .append("            boolean directOperationSelection = false;\n")
                .append("            if (operationName.length() == 0) {\n")
                .append("                directOperationSelection = DatabaseGraphqlLanguage.canUseSingleOperationDocumentDirectly(query, documentTokens);\n")
                .append("                if (directOperationSelection) directDocumentPlan = DatabaseGraphqlLanguage.documentPlan(query);\n")
                .append("            }\n")
                .append("            if (!directOperationSelection) query = DatabaseGraphqlLanguage.selectedOperationDocument(query, documentTokens, operationName);\n")
                .append("            if (query.length() == 0) {\n")
                .append("                response = DatabaseGraphqlEngine.errorJson(\"operation selection failed: provide one operation or its exact operation name\");\n")
                .append("            } else {\n")
                .append("            String languagePlan = directOperationSelection ? directDocumentPlan : DatabaseGraphqlLanguage.documentPlan(query);\n")
                .append("            String requestAst = DatabaseGraphqlAst.parse(query, languagePlan);\n")
                .append("            if (requestAst.length() != 0) requestAst = DatabaseGraphqlAst.withExecutableDirectiveDescriptor(requestAst, \"")
                .append(javaString(executableDirectiveDescriptor)).append("\");\n")
                .append("            String selectedOperationKind = DatabaseGraphqlAst.operationKind(requestAst);\n")
                .append("            preflight = DatabaseGraphqlEngine.preflightSelectedOperationFromAst(query, requestAst, variablesJson, extensionsJson, trustedContextJson, allowMutations, \"")
                .append(javaString(inputTypeDescriptor)).append("\");\n")
                .append("            if (requestAst.length() != 0 && preflight.length() == 0) preflight = DatabaseGraphqlEngine.validateSelectedOperationSchemaFromAst(query, requestAst, \"")
                .append(javaString(schemaValidationDescriptor)).append("\");\n")
                .append("            if (requestAst.length() == 0) {\n")
                .append("                response = DatabaseGraphqlEngine.internalErrorJson(\"selected operation has an invalid typed AST\");\n")
                .append("            } else if (preflight.length() != 0) {\n")
                .append("                response = preflight;\n")
                .append("            } else {\n")
                .append("            String materializedVariables = DatabaseGraphqlEngine.materializedVariableValuesFromAst(query, requestAst, variablesJson);\n")
                .append("            String materializedArguments = materializedVariables.length() == 0 ? \"\" : DatabaseGraphqlEngine.materializedSelectedOperationArgumentValuesFromAst(query, requestAst, materializedVariables, \"")
                .append(javaString(schemaValidationDescriptor)).append("\", \"")
                .append(javaString(inputTypeDescriptor)).append("\");\n")
                .append("            if (materializedVariables.length() == 0) {\n")
                .append("                response = DatabaseGraphqlEngine.internalErrorJson(\"selected operation variable materialization failed\");\n")
                .append("            } else if (DatabaseGraphqlEngine.isErrorJson(materializedArguments)) {\n")
                .append("                response = materializedArguments;\n")
                .append("            } else if (!materializedArguments.startsWith(\"ma1;\")) {\n")
                .append("                response = DatabaseGraphqlEngine.internalErrorJson(\"selected operation argument materialization failed\");\n")
                .append("            } else {\n")
                .append("            variablesJson = materializedVariables;\n")
                .append("            long deadlineEpochMillis = DatabaseGraphqlEngine.trustedContextDeadlineEpochMillis(trustedContextJson);\n")
                .append("            String actorRole = DatabaseGraphqlEngine.trustedContextString(trustedContextJson, \"actorRole\");\n")
                .append("            int applicationSqlStatements = 0;\n")
                .append("            long decodedApplicationRows = 0L;\n");
        emitMutationExecute(source, document, true);
        source.append("            if (response.length() == 0 && !selectedOperationKind.equals(\"query\") && !selectedOperationKind.equals(\"mutation\")) {\n")
                .append("                response = DatabaseGraphqlEngine.internalErrorJson(\"selected operation has an invalid typed AST operation kind\");\n")
                .append("            }\n")
                .append("            if (response.length() == 0 && selectedOperationKind.equals(\"query\")) {\n")
                .append("                if (DatabaseGraphqlEngine.deadlineExpired(deadlineEpochMillis)) {\n")
                .append("                    response = DatabaseGraphqlEngine.deadlineExceededErrorJson(\"request deadline exceeded during database execution\");\n")
                .append("                } else {\n")
                .append("            String members = \"\";\n")
                .append("            String executionErrors = \"\";\n")
                .append("            int introspectionItems = 0;\n")
                .append("            int requestedRootCount = DatabaseGraphqlEngine.rootFieldCountForTypeFromAst(query, requestAst, \"Query\");\n")
                .append("            String queryRootPlan = DatabaseGraphqlEngine.rootSelectionPlanFromAst(query, requestAst, variablesJson, \"Query\", \"")
                .append(javaString(pointRootNames(document))).append("\");\n")
                .append("            int enabledRootCount = DatabaseGraphqlEngine.selectionPlanCount(queryRootPlan);\n")
                .append("            if (enabledRootCount < 0) { response = DatabaseGraphqlEngine.errorJson(\"selected operation has an invalid or unsupported directive\"); }\n")
                .append("            if (response.length() == 0 && DatabaseGraphqlEngine.selectionPlanHasDuplicateResponseKeyFromAst(query, requestAst, queryRootPlan)) { response = DatabaseGraphqlEngine.errorJson(\"selected operation needs unsupported field merging for a duplicate response key\"); }\n")
                .append("            int introspectionBudgetRootIndex = 0;\n")
                .append("            while (introspectionBudgetRootIndex < enabledRootCount && response.length() == 0) {\n")
                .append("                int introspectionBudgetRootStart = DatabaseGraphqlEngine.selectionPlanFieldStart(queryRootPlan, introspectionBudgetRootIndex);\n")
                .append("                int introspectionRootCost = introspectionRootExpansionCost(query, requestAst, variablesJson, materializedVariables, introspectionBudgetRootStart);\n")
                .append("                if (introspectionRootCost > ").append(MAX_INTROSPECTION_ITEMS_PER_REQUEST)
                .append(" - introspectionItems) { response = DatabaseGraphqlEngine.resourceLimitErrorJsonAt(\"selected operation exceeds the introspection expansion budget\", query, introspectionBudgetRootStart); }\n")
                .append("                if (response.length() == 0) introspectionItems = introspectionItems + introspectionRootCost;\n")
                .append("                introspectionBudgetRootIndex++;\n")
                .append("            }\n")
                .append("            int queryRootIndex = 0;\n")
                .append("            while (queryRootIndex < enabledRootCount && response.length() == 0) {\n")
                .append("                int queryRootStart = DatabaseGraphqlEngine.selectionPlanFieldStart(queryRootPlan, queryRootIndex);\n")
                .append("                if (DatabaseGraphqlEngine.deadlineExpired(deadlineEpochMillis)) {\n")
                .append("                    response = DatabaseGraphqlEngine.deadlineExceededErrorJson(\"request deadline exceeded during database execution\");\n")
                .append("                } else {\n")
                .append("                boolean queryRootMatched = false;\n");
        String introspectionMembers = scopedLocal("introspectionRootMembers", "Query", "value");
        source.append("                if (response.length() == 0) {\n")
                .append("                    String ").append(introspectionMembers)
                .append(" = executeIntrospectionRoot(query, requestAst, variablesJson, materializedVariables, trustedContextJson, queryRootStart);\n")
                .append("                    if (DatabaseGraphqlEngine.isErrorJson(").append(introspectionMembers).append(")) {\n")
                .append("                        response = ").append(introspectionMembers).append(";\n")
                .append("                    } else if (").append(introspectionMembers).append(".length() != 0) {\n")
                .append("                        queryRootMatched = true;\n")
                .append("                        members = DatabaseGraphqlEngine.appendJsonItem(members, ")
                .append(introspectionMembers).append(");\n")
                .append("                    }\n")
                .append("                }\n");
        source.append("                if (!queryRootMatched && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, queryRootStart, \"__typename\")) {\n")
                .append("                    queryRootMatched = true;\n")
                .append("                    if (!DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, queryRootStart, \"\")) { response = DatabaseGraphqlEngine.errorJsonAt(\"field 'Query.__typename' has an argument\", query, queryRootStart); }\n")
                .append("                    if (response.length() == 0 && !DatabaseGraphqlEngine.fieldHasNoSelectionSetFromAst(query, requestAst, queryRootStart)) { response = DatabaseGraphqlEngine.errorJsonAt(\"field 'Query.__typename' cannot have a selection\", query, queryRootStart); }\n")
                .append("                    if (response.length() == 0) { members = DatabaseGraphqlEngine.appendJsonMember(members, DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, queryRootStart, \"__typename\"), DatabaseGraphqlEngine.jsonString(\"Query\")); }\n")
                .append("                }\n");

        for (TitanGraphqlRootDocument root : sortedRoots(document)) {
            source.append("                if (!queryRootMatched && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, queryRootStart, \"")
                    .append(javaString(root.name())).append("\")) {\n")
                    .append("                    queryRootMatched = true;\n");
            if (supportsGeneratedQueryRoot(root, document)) {
                emitQueryRootDispatchBranch(source, root, true);
            } else {
                source.append("                    response = DatabaseGraphqlEngine.unsupportedOperationErrorJsonAt(\"")
                        .append(javaString(connectionSupportFailure(root))).append("\", query, queryRootStart);\n")
                        .append("                }\n");
            }
        }
        source.append("                if (!queryRootMatched && response.length() == 0) {\n")
                .append("                    response = DatabaseGraphqlEngine.errorJsonAt(\"selected operation contains an unknown or unsupported root field\", query, queryRootStart);\n")
                .append("                }\n")
                .append("                if (response.length() == 0 && (DatabaseGraphqlEngine.responseAssemblyExceeded(members) || DatabaseGraphqlEngine.responseAssemblyExceeded(executionErrors))) {\n")
                .append("                    response = DatabaseGraphqlEngine.resourceLimitErrorJson(\"response exceeds the database engine character budget\");\n")
                .append("                }\n")
                .append("                }\n")
                .append("                queryRootIndex++;\n")
                .append("            }\n")
                .append("            if (response.length() == 0 && requestedRootCount == 0) {\n")
                .append("                response = DatabaseGraphqlEngine.errorJson(\"selected operation has no supported query root\");\n")
                .append("            }\n")
                .append("            if (response.length() == 0) {\n")
                .append("                response = \"TITAN-GRAPHQL-TRANSPORT/1 ROLLBACK\\n{\\\"data\\\":{\" + (members == null ? \"\" : members) + \"}\";\n")
                .append("                if (executionErrors == null || executionErrors.length() == 0) response = response + \"}\";\n")
                .append("                else response = response + \",\\\"errors\\\":[\" + executionErrors + \"]}\";\n")
                .append("                if (DatabaseGraphqlEngine.trustedContextFlag(trustedContextJson, \"includeExecutionMetrics\")) {\n")
                .append("                    String measuredResponse = DatabaseGraphqlEngine.appendExecutionMetrics(response.substring(response.indexOf(\"\\n\") + 1), applicationSqlStatements, decodedApplicationRows);\n")
                .append("                    if (DatabaseGraphqlEngine.responseAssemblyExceeded(measuredResponse)) response = DatabaseGraphqlEngine.resourceLimitErrorJson(\"response exceeds the database engine character budget\");\n")
                .append("                    else response = response.substring(0, response.indexOf(\"\\n\") + 1) + measuredResponse;\n")
                .append("                }\n")
                .append("            }\n")
                .append("                }\n")
                .append("            }\n")
                .append("            }\n")
                .append("            }\n")
                .append("            }\n")
                .append("            }\n")
                // Close the package-identity success branch before leaving the original
                // model/runtime identity gate. Keep this guard around all parsing and database
                // access so a stale or substituted routine package fails before it can process a
                // request.
                .append("            }\n")
                .append("        }\n")
                // MySQL routines may materialize dynamic result sets in the request procedure. Do
                // not hand the final framed response back through separately transpiled helper
                // functions: MySQL can otherwise lose their scalar return value after that
                // procedure-local dynamic-SQL work. Validate and decode the fixed transport frame
                // in the public routine itself, including the same completed-response bound that
                // DatabaseGraphqlEngine.transportResponse applies on the normal helper path.
                .append("        String transactionOutcome = \"ROLLBACK\";\n")
                .append("        String responseJson = \"{\\\"errors\\\":[{\\\"message\\\":\\\"database engine produced an invalid transport response\\\",\\\"extensions\\\":{\\\"code\\\":\\\"INTERNAL_ERROR\\\"}}]}\";\n")
                .append("        boolean framedResponse = response != null && (response.startsWith(\"\\u001eTITAN-GRAPHQL-TRANSPORT/1 COMMIT\\n\") || response.startsWith(\"\\u001eTITAN-GRAPHQL-TRANSPORT/1 ROLLBACK\\n\"));\n")
                .append("        if (framedResponse) {\n")
                .append("            transactionOutcome = response.startsWith(\"\\u001eTITAN-GRAPHQL-TRANSPORT/1 COMMIT\\n\") ? \"COMMIT\" : \"ROLLBACK\";\n")
                .append("            int responsePayloadStart = response.indexOf(\"\\n\") + 1;\n")
                .append("            responseJson = response.substring(responsePayloadStart);\n")
                .append("        }\n")
                .append("        if (DatabaseGraphqlEngine.responseAssemblyExceeded(responseJson) || responseJson.length() > ").append(MAX_RESPONSE_CHARACTERS).append(") {\n")
                .append("            transactionOutcome = \"ROLLBACK\";\n")
                .append("            responseJson = \"{\\\"errors\\\":[{\\\"message\\\":\\\"response exceeds the database engine character budget\\\",\\\"extensions\\\":{\\\"code\\\":\\\"RESOURCE_LIMIT_ERROR\\\"}}]}\";\n")
                .append("        }\n")
                // SQL annotations are appended after the Java body by Titan. `response` is in lexical
                // scope here, and the final procedure result set is the only one visible to the caller.
                .append("        @SQL(dialect = SqlDialect.MYSQL, value = \"SELECT :responseJson AS response_json, :transactionOutcome AS transaction_outcome\")\n")
                .append("        String emittedResponse = responseJson;\n")
                .append("    }\n\n");
    }

    /**
     * Keeps the public whole-request routine small by dispatching each schema root to a reachable
     * source-local Titan helper. The helper still owns all SQL, completion, policy, batching, and
     * ledger work; the private scalar carrier returns only the state needed to assemble the next
     * root in the same request.
     */
    private static void emitQueryRootDispatchBranch(
            StringBuilder source,
            TitanGraphqlRootDocument root,
            boolean mysql
    ) {
        String indent = mysql ? "                    " : "                ";
        String nested = indent + "    ";
        String carrier = local("queryRootExecution", root.name());
        String value = local("rootValue", root.name());
        String propagatedNull = local("rootPropagatedNull", root.name());
        source.append(indent).append("String ").append(carrier).append(" = ")
                .append(queryRootHelperName(root)).append("(connection, query, requestAst, variablesJson, ")
                .append("materializedArguments, trustedContextJson, actorRole, deadlineEpochMillis, ")
                .append("queryRootStart, applicationSqlStatements, decodedApplicationRows, executionErrors);\n");
        if (mysql) {
            source.append(indent).append("if (DatabaseGraphqlEngine.isErrorJson(").append(carrier)
                    .append(")) {\n")
                    .append(nested).append("response = ").append(carrier).append(";\n")
                    .append(indent).append("} else if (DatabaseGraphqlEngine.responseAssemblyExceeded(")
                    .append(carrier).append(")) {\n")
                    .append(nested).append("response = DatabaseGraphqlEngine.resourceLimitErrorJson(\"response exceeds the database engine character budget\");\n")
                    .append(indent).append("} else if (!DatabaseGraphqlEngine.queryRootExecutionCarrierIsValid(")
                    .append(carrier).append(")) {\n")
                    .append(nested).append("response = DatabaseGraphqlEngine.internalErrorJson(\"generated query root returned an invalid execution carrier\");\n")
                    .append(indent).append("} else {\n");
        } else {
            source.append(indent).append("if (DatabaseGraphqlEngine.isErrorJson(").append(carrier)
                    .append(")) return ").append(carrier).append(";\n")
                    .append(indent).append("if (DatabaseGraphqlEngine.responseAssemblyExceeded(")
                    .append(carrier).append(")) return DatabaseGraphqlEngine.resourceLimitErrorJson(\"response exceeds the database engine character budget\");\n")
                    .append(indent).append("if (!DatabaseGraphqlEngine.queryRootExecutionCarrierIsValid(")
                    .append(carrier).append(")) return DatabaseGraphqlEngine.internalErrorJson(\"generated query root returned an invalid execution carrier\");\n")
                    .append(indent).append("{\n");
        }
        source.append(nested).append("applicationSqlStatements = DatabaseGraphqlEngine.queryRootExecutionStatementCount(")
                .append(carrier).append(");\n")
                .append(nested).append("decodedApplicationRows = DatabaseGraphqlEngine.queryRootExecutionDecodedRows(")
                .append(carrier).append(");\n")
                .append(nested).append("executionErrors = DatabaseGraphqlEngine.queryRootExecutionErrors(")
                .append(carrier).append(");\n")
                .append(nested).append("String ").append(value)
                .append(" = DatabaseGraphqlEngine.queryRootExecutionValue(").append(carrier).append(");\n")
                .append(nested).append("boolean ").append(propagatedNull)
                .append(" = DatabaseGraphqlEngine.queryRootExecutionPropagatedNull(").append(carrier).append(");\n");
        if (root.operation() == TitanGraphqlRootDocument.RootDocumentOperation.POINT) {
            source.append(nested).append("if (").append(propagatedNull).append(") ").append(value)
                    .append(" = \"null\";\n");
        } else if (mysql) {
            source.append(nested).append("if (").append(propagatedNull).append(") {\n")
                    .append(nested).append("    response = \"TITAN-GRAPHQL-TRANSPORT/1 ROLLBACK\\n{\\\"data\\\":null\";\n")
                    .append(nested).append("    if (executionErrors.length() == 0) response = response + \"}\";\n")
                    .append(nested).append("    else response = response + \",\\\"errors\\\":[\" + executionErrors + \"]}\";\n")
                    .append(nested).append("    if (DatabaseGraphqlEngine.trustedContextFlag(trustedContextJson, \"includeExecutionMetrics\")) {\n")
                    .append(nested).append("        String measuredNullResponse = DatabaseGraphqlEngine.appendExecutionMetrics(response.substring(response.indexOf(\"\\n\") + 1), applicationSqlStatements, decodedApplicationRows);\n")
                    .append(nested).append("        if (DatabaseGraphqlEngine.responseAssemblyExceeded(measuredNullResponse)) response = DatabaseGraphqlEngine.resourceLimitErrorJson(\"response exceeds the database engine character budget\");\n")
                    .append(nested).append("        else response = response.substring(0, response.indexOf(\"\\n\") + 1) + measuredNullResponse;\n")
                    .append(nested).append("    }\n")
                    .append(nested).append("}\n");
        } else {
            source.append(nested).append("if (").append(propagatedNull).append(") {\n")
                    .append(nested).append("    String nullDataResponse = \"{\\\"data\\\":null\";\n")
                    .append(nested).append("    if (executionErrors.length() == 0) nullDataResponse = nullDataResponse + \"}\";\n")
                    .append(nested).append("    else nullDataResponse = nullDataResponse + \",\\\"errors\\\":[\" + executionErrors + \"]}\";\n")
                    .append(nested).append("    if (DatabaseGraphqlEngine.trustedContextFlag(trustedContextJson, \"includeExecutionMetrics\")) nullDataResponse = DatabaseGraphqlEngine.appendExecutionMetrics(nullDataResponse, applicationSqlStatements, decodedApplicationRows);\n")
                    .append(nested).append("    if (DatabaseGraphqlEngine.responseAssemblyExceeded(nullDataResponse)) return DatabaseGraphqlEngine.resourceLimitErrorJson(\"response exceeds the database engine character budget\");\n")
                    .append(nested).append("    return DatabaseGraphqlEngine.transactionOutcomeJson(nullDataResponse, \"ROLLBACK\");\n")
                    .append(nested).append("}\n");
        }
        if (mysql) {
            source.append(nested).append("if (response.length() == 0) {\n")
                    .append(nested).append("    members = DatabaseGraphqlEngine.appendJsonMember(members, ")
                    .append("DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, queryRootStart, \"")
                    .append(javaString(root.name())).append("\"), ").append(value).append(");\n")
                    .append(nested).append("}\n");
        } else {
            source.append(nested).append("members = DatabaseGraphqlEngine.appendJsonMember(members, ")
                    .append("DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, queryRootStart, \"")
                    .append(javaString(root.name())).append("\"), ").append(value).append(");\n");
        }
        source.append(indent).append("}\n")
                .append(mysql ? "                }\n" : "            }\n");
    }

    private static void emitQueryRootHelpers(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            boolean mysql
    ) {
        for (TitanGraphqlRootDocument root : sortedRoots(document)) {
            if (!supportsGeneratedQueryRoot(root, document)) {
                continue;
            }
            TitanGraphqlTypeDocument rootType = type(document, root.type());
            if (root.operation() == TitanGraphqlRootDocument.RootDocumentOperation.CONNECTION) {
                emitConnectionCustomCursorHelper(source, document, root, rootType);
                emitConnectionFilterBinderHelper(source, document, root, rootType);
                emitConnectionCursorBinderHelpers(source, document, root, rootType);
            }
            source.append("    static String ").append(queryRootHelperName(root)).append("(\n")
                    .append("            Connection connection, String query, String requestAst, String variablesJson,\n")
                    .append("            String materializedArguments, String trustedContextJson, String actorRole, long deadlineEpochMillis,\n")
                    .append("            int queryRootStart, int applicationSqlStatements, long decodedApplicationRows,\n")
                    .append("            String executionErrors\n")
                    .append("    ) throws SQLException {\n");
            if (mysql) {
                source.append("        String response = \"\";\n");
            }
            String value = local("rootValue", root.name());
            String propagatedNull = local("rootPropagatedNull", root.name());
            if (root.operation() == TitanGraphqlRootDocument.RootDocumentOperation.POINT) {
                String path = local("rootExecutionPath", root.name());
                source.append("        String ").append(path)
                        .append(" = DatabaseGraphqlEngine.appendExecutionPath(\"[]\", ")
                        .append("DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, queryRootStart, \"")
                        .append(javaString(root.name())).append("\"));\n")
                        .append("        boolean ").append(propagatedNull).append(" = false;\n");
                if (mysql) {
                    source.append("        String ").append(value).append(" = \"null\";\n");
                    emitInlineMySqlPointRoot(source, document, root, rootType, "queryRootStart", value,
                            path, propagatedNull, "decodedApplicationRows");
                } else {
                    emitInlinePointRoot(source, document, root, rootType, "queryRootStart", value,
                            path, propagatedNull, "decodedApplicationRows");
                    source.append("        if (DatabaseGraphqlEngine.isErrorJson(").append(value)
                            .append(")) return ").append(value).append(";\n");
                }
            } else {
                if (mysql) {
                    source.append("        String ").append(value).append(" = \"\";\n");
                }
                source.append("        boolean ").append(propagatedNull).append(" = false;\n");
                emitInlineConnectionRoot(source, document, root, rootType, "queryRootStart", value,
                        propagatedNull, mysql, "decodedApplicationRows");
                if (!mysql) {
                    source.append("        if (DatabaseGraphqlEngine.isErrorJson(").append(value)
                            .append(")) return ").append(value).append(";\n");
                }
            }
            if (mysql) {
                source.append("        if (response.length() != 0) return response;\n");
            }
            source.append("        return DatabaseGraphqlEngine.queryRootExecutionCarrier(")
                    .append(value).append(", executionErrors, applicationSqlStatements, decodedApplicationRows, ")
                    .append(propagatedNull).append(");\n")
                    .append("    }\n\n");
        }
    }

    private static String connectionFilterBinderHelperName(TitanGraphqlRootDocument root) {
        return "bindConnectionFilters" + javaTypeName(root.name());
    }

    private static String connectionPageCursorBinderHelperName(TitanGraphqlRootDocument root) {
        return "bindConnectionPageCursor" + javaTypeName(root.name());
    }

    private static String connectionBoundaryCursorBinderHelperName(TitanGraphqlRootDocument root) {
        return "bindConnectionBoundaryCursor" + javaTypeName(root.name());
    }

    private static String connectionCustomCursorHelperName(TitanGraphqlRootDocument root) {
        return "decodeConnectionCustomCursor" + javaTypeName(root.name());
    }

    /**
     * Keeps schema-specific tuple-cursor branches out of the already-large root executor. The helper
     * returns only a bounded scalar carrier, so it remains an ordinary Titan source-local function and
     * does not move a JDBC handle across a routine boundary.
     */
    private static void emitConnectionCustomCursorHelper(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument type
    ) {
        List<TitanGraphqlRootDocument.RootDocumentSortPath> supported =
                supportedConnectionCustomOrders(document, type, root);
        if (supported.isEmpty()) return;
        source.append("    private static String ").append(connectionCustomCursorHelperName(root))
                .append("(String cursor, String customOrderPath, boolean descending) {\n");
        for (TitanGraphqlRootDocument.RootDocumentSortPath sort : supported) {
            String tieBreaker = connectionCustomOrderTieBreaker(type, sort);
            TitanGraphqlFieldDocument valueField = connectionCustomOrderField(document, type, root, sort);
            TitanGraphqlFieldDocument tieField = fieldByColumn(type, tieBreaker);
            source.append("        if (customOrderPath.equals(\"")
                    .append(javaString(sort.name())).append("\")) {\n");
            if (connectionCustomOrderUsesTextCursor(valueField)) {
                source.append("            if (!DatabaseGraphqlEngine.relayCursorStringLongTieIsValid(cursor, \"")
                        .append(javaString(sort.name())).append("\", \"")
                        .append(javaString(sort.path())).append("\", descending ? \"DESC\" : \"ASC\", \"")
                        .append(javaString(tieBreaker)).append("\")) return \"\";\n")
                        .append("            String value = DatabaseGraphqlEngine.relayCursorStringLongTieValue(cursor);\n")
                        .append("            long tie = DatabaseGraphqlEngine.relayCursorStringLongTieBreakerValue(cursor);\n");
                if (isUuid(valueField.type())) {
                    source.append("            if (!DatabaseGraphqlEngine.uuidTextIsValid(value)) return \"\";\n");
                }
                source.append("            if (DatabaseGraphqlEngine.invalidIntegerForScalar(tie, \"")
                        .append(javaString(tieField.type())).append("\")) return \"\";\n")
                        .append("            return DatabaseGraphqlEngine.connectionCursorCarrier(value, 0L, tie);\n");
            } else {
                source.append("            if (!DatabaseGraphqlEngine.relayCursorLongLongTieIsValid(cursor, \"")
                        .append(javaString(sort.name())).append("\", \"")
                        .append(javaString(sort.path())).append("\", descending ? \"DESC\" : \"ASC\", \"")
                        .append(javaString(tieBreaker)).append("\")) return \"\";\n")
                        .append("            long value = DatabaseGraphqlEngine.relayCursorLongLongTieValue(cursor);\n")
                        .append("            long tie = DatabaseGraphqlEngine.relayCursorLongLongTieBreakerValue(cursor);\n")
                        .append("            if (DatabaseGraphqlEngine.invalidIntegerForScalar(value, \"")
                        .append(javaString(valueField.type())).append("\") || ")
                        .append("DatabaseGraphqlEngine.invalidIntegerForScalar(tie, \"")
                        .append(javaString(tieField.type())).append("\")) return \"\";\n")
                        .append("            return DatabaseGraphqlEngine.connectionCursorCarrier(\"\", value, tie);\n");
            }
            source.append("        }\n");
        }
        source.append("        return \"\";\n")
                .append("    }\n\n");
    }

    private static void emitConnectionCursorBinderCall(
            StringBuilder source,
            String indent,
            String helper,
            String statement,
            String customOrder,
            String customOrderPath,
            String customOrderDescending,
            String afterPresent,
            String afterValue,
            String customAfterStringValue,
            String customAfterLongValue,
            String customAfterTieValue,
            String beforePresent,
            String beforeValue,
            String customBeforeStringValue,
            String customBeforeLongValue,
            String customBeforeTieValue,
            String backward,
            String pageSize
    ) {
        source.append(indent).append(helper).append("(").append(statement).append(", ")
                .append(customOrder).append(", ").append(customOrderPath).append(", ")
                .append(customOrderDescending).append(", ").append(afterPresent).append(", ")
                .append(afterValue).append(", ").append(customAfterStringValue).append(", ")
                .append(customAfterLongValue).append(", ").append(customAfterTieValue).append(", ")
                .append(beforePresent).append(", ").append(beforeValue).append(", ")
                .append(customBeforeStringValue).append(", ").append(customBeforeLongValue).append(", ")
                .append(customBeforeTieValue).append(", ").append(backward).append(", ")
                .append(pageSize).append(");\n");
    }

    private static void emitConnectionCursorBinderHelpers(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument type
    ) {
        List<TitanGraphqlFilterLayout.Binding> filters = supportedConnectionFilters(document, type, root);
        List<TitanGraphqlRootDocument.RootDocumentSortPath> customOrders =
                supportedConnectionCustomOrders(document, type, root);
        int start = 1 + (filters.isEmpty() ? 0
                : MAX_FILTER_PLAN_GROUPS * (1 + MAX_FILTER_PLAN_TERMS_PER_GROUP * 7))
                + root.arguments().size() * 2 + root.contextFilters().size() * 3;
        emitConnectionCursorBinderSignature(source, connectionPageCursorBinderHelperName(root));
        emitConnectionCursorParameters(source, "statement", start, document, root, type, customOrders,
                "customOrder", "customOrderPath", "customOrderDescending",
                "afterPresent", "afterValue", "customAfterStringValue", "customAfterLongValue",
                "customAfterTieValue", "beforePresent", "beforeValue", "customBeforeStringValue",
                "customBeforeLongValue", "customBeforeTieValue", "backward", "pageSize", "        ", false);
        source.append("    }\n\n");

        emitConnectionCursorBinderSignature(source, connectionBoundaryCursorBinderHelperName(root));
        emitConnectionBoundaryParameters(source, "statement", start, document, root, type, customOrders,
                "customOrder", "customOrderPath", "customOrderDescending",
                "afterPresent", "afterValue", "customAfterStringValue", "customAfterLongValue",
                "customAfterTieValue", "beforePresent", "beforeValue", "customBeforeStringValue",
                "customBeforeLongValue", "customBeforeTieValue", "backward", "        ", false);
        source.append("    }\n\n");
    }

    private static void emitConnectionCursorBinderSignature(StringBuilder source, String name) {
        source.append("    private static void ").append(name).append("(\n")
                .append("            PreparedStatement statement, boolean customOrder, String customOrderPath,\n")
                .append("            boolean customOrderDescending, boolean afterPresent, long afterValue,\n")
                .append("            String customAfterStringValue, long customAfterLongValue, long customAfterTieValue,\n")
                .append("            boolean beforePresent, long beforeValue, String customBeforeStringValue,\n")
                .append("            long customBeforeLongValue, long customBeforeTieValue, boolean backward, long pageSize\n")
                .append("    ) throws SQLException {\n");
    }

    /** Keeps the fixed typed JDBC carrier outside the already-large generated root executor. */
    private static void emitConnectionFilterBinderHelper(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument type
    ) {
        List<TitanGraphqlFilterLayout.Binding> filters = supportedConnectionFilters(document, type, root);
        if (filters.isEmpty()) return;
        source.append("    private static void ").append(connectionFilterBinderHelperName(root))
                .append("(PreparedStatement statement, String filterPlan) throws SQLException {\n");
        int parameter = 1;
        for (int group = 0; group < MAX_FILTER_PLAN_GROUPS; group++) {
            source.append("        statement.setBoolean(").append(parameter++)
                    .append(", DatabaseGraphqlEngine.filterPlanGroupActive(filterPlan, ")
                    .append(group).append("));\n");
            for (int term = 0; term < MAX_FILTER_PLAN_TERMS_PER_GROUP; term++) {
                String suffix = "G" + group + "T" + term;
                String selector = "selector" + suffix;
                String negated = "negated" + suffix;
                String nullValue = "nullValue" + suffix;
                String raw = "raw" + suffix;
                String booleanValue = "booleanValue" + suffix;
                String longValue = "longValue" + suffix;
                String decimalValue = "decimalValue" + suffix;
                String stringValue = "stringValue" + suffix;
                source.append("        int ").append(selector)
                        .append(" = DatabaseGraphqlEngine.filterPlanTermSelector(filterPlan, ")
                        .append(group).append(", ").append(term).append(");\n")
                        .append("        boolean ").append(negated)
                        .append(" = DatabaseGraphqlEngine.filterPlanTermNegated(filterPlan, ")
                        .append(group).append(", ").append(term).append(");\n")
                        .append("        boolean ").append(nullValue)
                        .append(" = DatabaseGraphqlEngine.filterPlanTermNull(filterPlan, ")
                        .append(group).append(", ").append(term).append(");\n")
                        .append("        String ").append(raw)
                        .append(" = DatabaseGraphqlEngine.filterPlanTermValue(filterPlan, ")
                        .append(group).append(", ").append(term).append(");\n")
                        .append("        boolean ").append(booleanValue).append(" = false;\n")
                        .append("        long ").append(longValue).append(" = 0L;\n")
                        .append("        double ").append(decimalValue).append(" = 0.0;\n")
                        .append("        String ").append(stringValue).append(" = \"\";\n");
                emitConnectionFilterSlotDecode(source, "        ", "", nullValue,
                        connectionFilterSelectorCondition(document, filters, selector, "boolean"),
                        booleanValue + " = DatabaseGraphqlEngine.booleanArgument(" + raw + ") == 1;");
                emitConnectionFilterSlotDecode(source, "        ", "", nullValue,
                        connectionFilterSelectorCondition(document, filters, selector, "integralId"),
                        longValue + " = DatabaseGraphqlEngine.integralIdArgument(" + raw + ");");
                emitConnectionFilterSlotDecode(source, "        ", "", nullValue,
                        connectionFilterSelectorCondition(document, filters, selector, "long"),
                        longValue + " = DatabaseGraphqlEngine.longArgument(" + raw + ");");
                emitConnectionFilterSlotDecode(source, "        ", "", nullValue,
                        connectionFilterSelectorCondition(document, filters, selector, "decimal"),
                        decimalValue + " = DatabaseGraphqlEngine.decimalArgument(" + raw + ");");
                emitConnectionFilterSlotDecode(source, "        ", "", nullValue,
                        connectionFilterSelectorCondition(document, filters, selector, "enum"),
                        stringValue + " = DatabaseGraphqlEngine.enumArgument(" + raw + ");");
                emitConnectionFilterSlotDecode(source, "        ", "", nullValue,
                        connectionFilterSelectorCondition(document, filters, selector, "stringId"),
                        stringValue + " = DatabaseGraphqlEngine.stringIdArgument(" + raw + ");");
                emitConnectionFilterSlotDecode(source, "        ", "", nullValue,
                        connectionFilterSelectorCondition(document, filters, selector, "string"),
                        stringValue + " = DatabaseGraphqlEngine.stringArgument(" + raw + ");");
                source.append("        statement.setInt(").append(parameter++).append(", ")
                        .append(selector).append(");\n")
                        .append("        statement.setBoolean(").append(parameter++).append(", ")
                        .append(negated).append(");\n")
                        .append("        statement.setBoolean(").append(parameter++).append(", ")
                        .append(nullValue).append(");\n")
                        .append("        statement.setBoolean(").append(parameter++).append(", ")
                        .append(booleanValue).append(");\n")
                        .append("        statement.setLong(").append(parameter++).append(", ")
                        .append(longValue).append(");\n")
                        .append("        statement.setDouble(").append(parameter++).append(", ")
                        .append(decimalValue).append(");\n")
                        .append("        statement.setString(").append(parameter++).append(", ")
                        .append(stringValue).append(");\n");
            }
        }
        source.append("    }\n\n");
    }

    private static boolean supportsGeneratedQueryRoot(
            TitanGraphqlRootDocument root,
            TitanGraphqlModelDocument document
    ) {
        return root.operation() == TitanGraphqlRootDocument.RootDocumentOperation.POINT
                || supportsBasicRelayConnection(root, type(document, root.type()), document);
    }

    private static String queryRootHelperName(TitanGraphqlRootDocument root) {
        return "executeQueryRoot" + javaTypeName(root.name());
    }

    /**
     * Splits schema introspection into a reachable source-local helper. Titan lowers this static
     * Java method to an internal database routine, retaining one public whole-request entry point
     * while avoiding the JVM's per-method bytecode ceiling as generated branches grow.
     */
    private static void emitIntrospectionExpansionCostHelper(
            StringBuilder source,
            TitanGraphqlModelDocument document
    ) {
        List<IntrospectionNamedType> namedTypes = introspectionNamedTypes(document);
        IntrospectionTypeCost allTypes = new IntrospectionTypeCost(0, 0, 0, 0, 0, 0, 0);
        for (IntrospectionNamedType namedType : namedTypes) {
            allTypes = allTypes.plus(introspectionTypeCost(document, namedType.name(), namedType.kind()));
        }
        int directiveItems = 2 + document.directives().size();
        int directiveArguments = directiveItems;
        int directiveLocations = 6;
        for (TitanGraphqlDirectiveDocument directive : document.directives()) {
            directiveLocations += directive.locations().size();
        }

        source.append("    static int introspectionRootExpansionCost(\n")
                .append("            String query, String requestAst, String variablesJson, String materializedVariables,\n")
                .append("            int queryRootStart\n")
                .append("    ) {\n")
                .append("        if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, queryRootStart, \"__schema\")) {\n")
                .append("            String plan = DatabaseGraphqlEngine.introspectionSelectionPlanFromAst(query, requestAst, queryRootStart, variablesJson, \"__Schema\");\n")
                .append("            int cost = 1;\n")
                .append("            int selectedStart = DatabaseGraphqlEngine.selectionPlanFieldStartForFieldFromAst(query, requestAst, plan, \"queryType\");\n");
        emitIntrospectionCostAddition(source, document, "            ", "cost", "selectedStart",
                introspectionTypeCost(document, "Query", "OBJECT"));
        source.append("            selectedStart = DatabaseGraphqlEngine.selectionPlanFieldStartForFieldFromAst(query, requestAst, plan, \"mutationType\");\n");
        emitIntrospectionCostAddition(source, document, "            ", "cost", "selectedStart",
                introspectionTypeCost(document, "Mutation", "OBJECT"));
        source.append("            selectedStart = DatabaseGraphqlEngine.selectionPlanFieldStartForFieldFromAst(query, requestAst, plan, \"subscriptionType\");\n")
                .append("            if (selectedStart >= 0) cost++;\n")
                .append("            selectedStart = DatabaseGraphqlEngine.selectionPlanFieldStartForFieldFromAst(query, requestAst, plan, \"types\");\n");
        emitIntrospectionCostAddition(source, document, "            ", "cost", "selectedStart", allTypes);
        source.append("            selectedStart = DatabaseGraphqlEngine.selectionPlanFieldStartForFieldFromAst(query, requestAst, plan, \"directives\");\n")
                .append("            if (selectedStart >= 0) cost = cost + DatabaseGraphqlEngine.introspectionDirectiveExpansionCostFromAst(query, requestAst, selectedStart, variablesJson, ")
                .append(directiveItems).append(", ").append(directiveArguments).append(", ")
                .append(directiveLocations).append(");\n")
                .append("            return cost;\n")
                .append("        }\n")
                .append("        if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, queryRootStart, \"__type\")) {\n")
                .append("            String argument = DatabaseGraphqlEngine.argumentValueFromAstMaterialized(query, requestAst, queryRootStart, \"name\", materializedVariables);\n")
                .append("            if (!DatabaseGraphqlEngine.stringArgumentIsValid(argument)) return 0;\n")
                .append("            String typeName = DatabaseGraphqlEngine.stringArgument(argument);\n")
                .append("            int cost = 0;\n");
        for (IntrospectionNamedType namedType : namedTypes) {
            source.append("            if (cost == 0 && typeName.equals(\"")
                    .append(javaString(namedType.name())).append("\")) {\n");
            emitIntrospectionCostAddition(source, document, "                ", "cost", "queryRootStart",
                    introspectionTypeCost(document, namedType.name(), namedType.kind()));
            source.append("            }\n");
        }
        source.append("            return cost;\n")
                .append("        }\n")
                .append("        return 0;\n")
                .append("    }\n\n");
    }

    private static void emitIntrospectionCostAddition(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            String indent,
            String accumulator,
            String fieldStart,
            IntrospectionTypeCost cost
    ) {
        source.append(indent).append("if (").append(fieldStart).append(" >= 0) ")
                .append(accumulator).append(" = ").append(accumulator)
                .append(" + DatabaseGraphqlEngine.introspectionTypeExpansionCostFromAst(query, requestAst, ")
                .append(fieldStart).append(", variablesJson, ")
                .append(cost.types()).append(", ").append(cost.fields()).append(", ")
                .append(cost.fieldArguments()).append(", ").append(cost.inputFields()).append(", ")
                .append(cost.enumValues()).append(", ").append(cost.interfaces()).append(", ")
                .append(cost.possibleTypes()).append(");\n");
    }

    private static void emitPostgresInternalHelpers(
            StringBuilder source,
            TitanGraphqlModelDocument document
    ) {
        emitIntrospectionExpansionCostHelper(source, document);
        source.append("    static String executeIntrospectionRoot(\n")
                .append("            String query, String requestAst, String variablesJson, String materializedVariables,\n")
                .append("            String trustedContextJson, int queryRootStart\n")
                .append("    ) {\n")
                .append("        boolean queryRootMatched = false;\n")
                .append("        String members = \"\";\n");
        emitIntrospectionRootDispatch(source, document, false);
        source.append("        if (!queryRootMatched) return \"\";\n")
                .append("        return members;\n")
                .append("    }\n\n");
    }

    /**
     * The MySQL metadata helper remains pure: it returns either a member fragment, no-match, or
     * the normal GraphQL error envelope for the public procedure to return as its one result.
     */
    private static void emitMySqlInternalHelpers(
            StringBuilder source,
            TitanGraphqlModelDocument document
    ) {
        emitIntrospectionExpansionCostHelper(source, document);
        source.append("    static String executeIntrospectionRoot(\n")
                .append("            String query, String requestAst, String variablesJson, String materializedVariables,\n")
                .append("            String trustedContextJson, int queryRootStart\n")
                .append("    ) {\n")
                .append("        String response = \"\";\n")
                .append("        boolean queryRootMatched = false;\n")
                .append("        String members = \"\";\n");
        emitIntrospectionRootDispatch(source, document, true);
        source.append("        if (response.length() != 0) return response;\n")
                .append("        if (!queryRootMatched) return \"\";\n")
                .append("        return members;\n")
                .append("    }\n\n");
    }

    /**
     * Emits the database-side roots that are part of the GraphQL introspection contract.  This is
     * deliberately schema-specialized: the generated routine receives no JVM schema, and the
     * authenticated capability check happens before any metadata is exposed.  The first slice
     * covers the stable schema/type identity surface; fields, input objects, directives, and
     * wrappers are added by later language-core work rather than routed back through the old JVM
     * executor.
     */
    private static void emitIntrospectionRootDispatch(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            boolean mysql
    ) {
        String indent = mysql ? "                " : "            ";
        String continueCondition = mysql ? "response.length() == 0" : "true";
        String schemaValue = scopedLocal("introspectionSchemaValue", "Query", "value");
        String typeArgument = scopedLocal("introspectionTypeArgument", "Query", "value");
        String typeName = scopedLocal("introspectionTypeName", "Query", "value");
        String typeValue = scopedLocal("introspectionTypeValue", "Query", "value");
        String matchedType = scopedLocal("introspectionTypeMatched", "Query", "value");

        source.append(indent).append("if (!queryRootMatched && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, queryRootStart, \"__schema\")) {\n")
                .append(indent).append("    queryRootMatched = true;\n")
                .append(indent).append("    if (!DatabaseGraphqlEngine.trustedContextFlag(trustedContextJson, \"introspectionEnabled\")) {\n");
        emitIntrospectionAuthorizationFailure(source, mysql, indent + "        ",
                "introspection is disabled", "queryRootStart");
        source.append(indent).append("    }\n")
                .append(indent).append("    if (").append(continueCondition)
                .append(" && !DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, queryRootStart, \"\")) {\n");
        emitIntrospectionFailure(source, mysql, indent + "        ", "field '__schema' does not support arguments", "queryRootStart");
        source.append(indent).append("    }\n")
                .append(indent).append("    String ").append(schemaValue).append(" = \"null\";\n")
                .append(indent).append("    if (").append(continueCondition).append(") {\n");
        emitIntrospectionSchemaValue(source, document, mysql, indent + "        ", "queryRootStart", schemaValue);
        source.append(indent).append("    }\n")
                .append(indent).append("    if (").append(continueCondition).append(") {\n")
                .append(indent).append("        members = DatabaseGraphqlEngine.appendJsonMember(members, DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, queryRootStart, \"__schema\"), ")
                .append(schemaValue).append(");\n")
                .append(indent).append("    }\n")
                .append(indent).append("}\n");

        source.append(indent).append("if (!queryRootMatched && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, queryRootStart, \"__type\")) {\n")
                .append(indent).append("    queryRootMatched = true;\n")
                .append(indent).append("    if (!DatabaseGraphqlEngine.trustedContextFlag(trustedContextJson, \"introspectionEnabled\")) {\n");
        emitIntrospectionAuthorizationFailure(source, mysql, indent + "        ",
                "introspection is disabled", "queryRootStart");
        source.append(indent).append("    }\n")
                .append(indent).append("    if (").append(continueCondition)
                .append(" && !DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, queryRootStart, \",name,\")) {\n");
        emitIntrospectionFailure(source, mysql, indent + "        ", "field '__type' requires exactly one argument 'name'", "queryRootStart");
        source.append(indent).append("    }\n")
                .append(indent).append("    if (").append(continueCondition)
                .append(" && !DatabaseGraphqlEngine.argumentVariableTypeIsCompatibleFromAst(query, requestAst, queryRootStart, \"name\", \"String\")) {\n");
        emitIntrospectionFailure(source, mysql, indent + "        ", "variable for '__type.name' must be declared as String!", "queryRootStart");
        source.append(indent).append("    }\n")
                .append(indent).append("    String ").append(typeArgument)
                .append(" = DatabaseGraphqlEngine.argumentValueFromAstMaterialized(query, requestAst, queryRootStart, \"name\", materializedVariables);\n")
                .append(indent).append("    if (").append(continueCondition).append(" && ").append(typeArgument).append(".length() == 0) {\n");
        emitIntrospectionFailure(source, mysql, indent + "        ", "field '__type' requires argument 'name'", "queryRootStart");
        source.append(indent).append("    }\n")
                .append(indent).append("    if (").append(continueCondition).append(" && !DatabaseGraphqlEngine.stringArgumentIsValid(")
                .append(typeArgument).append(")) {\n");
        emitIntrospectionFailure(source, mysql, indent + "        ", "argument '__type.name' must be String", "queryRootStart");
        source.append(indent).append("    }\n")
                .append(indent).append("    String ").append(typeName).append(" = DatabaseGraphqlEngine.stringArgument(")
                .append(typeArgument).append(");\n")
                .append(indent).append("    String ").append(typeValue).append(" = \"null\";\n")
                .append(indent).append("    boolean ").append(matchedType).append(" = false;\n")
                .append(indent).append("    if (").append(continueCondition).append(" && ").append(typeName).append(".equals(\"Query\")) {\n")
                .append(indent).append("        ").append(matchedType).append(" = true;\n");
        emitIntrospectionTypeValue(source, document, mysql, indent + "        ", "queryRootStart", typeValue, "Query", "OBJECT");
        source.append(indent).append("    }\n");
        if (!document.mutations().isEmpty()) {
            source.append(indent).append("    if (").append(continueCondition).append(" && !").append(matchedType)
                    .append(" && ").append(typeName).append(".equals(\"Mutation\")) {\n")
                    .append(indent).append("        ").append(matchedType).append(" = true;\n");
            emitIntrospectionTypeValue(source, document, mysql, indent + "        ", "queryRootStart", typeValue, "Mutation", "OBJECT");
            source.append(indent).append("    }\n");
        }
        for (TitanGraphqlTypeDocument type : sortedTypes(document)) {
            source.append(indent).append("    if (").append(continueCondition).append(" && !").append(matchedType)
                    .append(" && ").append(typeName).append(".equals(\"").append(javaString(type.name())).append("\")) {\n")
                    .append(indent).append("        ").append(matchedType).append(" = true;\n");
            emitIntrospectionTypeValue(source, document, mysql, indent + "        ", "queryRootStart", typeValue, type.name(), "OBJECT");
            source.append(indent).append("    }\n");
        }
        for (TitanGraphqlInterfaceDocument interfaceType : sortedInterfaces(document)) {
            source.append(indent).append("    if (").append(continueCondition).append(" && !").append(matchedType)
                    .append(" && ").append(typeName).append(".equals(\"")
                    .append(javaString(interfaceType.name())).append("\")) {\n")
                    .append(indent).append("        ").append(matchedType).append(" = true;\n");
            emitIntrospectionTypeValue(source, document, mysql, indent + "        ", "queryRootStart", typeValue,
                    interfaceType.name(), "INTERFACE");
            source.append(indent).append("    }\n");
        }
        for (TitanGraphqlUnionDocument union : sortedUnions(document)) {
            source.append(indent).append("    if (").append(continueCondition).append(" && !").append(matchedType)
                    .append(" && ").append(typeName).append(".equals(\"")
                    .append(javaString(union.name())).append("\")) {\n")
                    .append(indent).append("        ").append(matchedType).append(" = true;\n");
            emitIntrospectionTypeValue(source, document, mysql, indent + "        ", "queryRootStart", typeValue,
                    union.name(), "UNION");
            source.append(indent).append("    }\n");
        }
        for (String derivedType : introspectionDerivedObjectTypes(document)) {
            source.append(indent).append("    if (").append(continueCondition).append(" && !").append(matchedType)
                    .append(" && ").append(typeName).append(".equals(\"").append(javaString(derivedType)).append("\")) {\n")
                    .append(indent).append("        ").append(matchedType).append(" = true;\n");
            emitIntrospectionTypeValue(source, document, mysql, indent + "        ", "queryRootStart", typeValue, derivedType, "OBJECT");
            source.append(indent).append("    }\n");
        }
        for (String inputType : introspectionInputObjectNames(document)) {
            source.append(indent).append("    if (").append(continueCondition).append(" && !").append(matchedType)
                    .append(" && ").append(typeName).append(".equals(\"").append(javaString(inputType)).append("\")) {\n")
                    .append(indent).append("        ").append(matchedType).append(" = true;\n");
            emitIntrospectionTypeValue(source, document, mysql, indent + "        ", "queryRootStart", typeValue,
                    inputType, "INPUT_OBJECT");
            source.append(indent).append("    }\n");
        }
        if (introspectionHasSortDirection(document)) {
            source.append(indent).append("    if (").append(continueCondition).append(" && !").append(matchedType)
                    .append(" && ").append(typeName).append(".equals(\"SortDirection\")) {\n")
                    .append(indent).append("        ").append(matchedType).append(" = true;\n");
            emitIntrospectionTypeValue(source, document, mysql, indent + "        ", "queryRootStart", typeValue,
                    "SortDirection", "ENUM");
            source.append(indent).append("    }\n");
        }
        for (TitanGraphqlEnumDocument enumType : sortedEnums(document)) {
            source.append(indent).append("    if (").append(continueCondition).append(" && !").append(matchedType)
                    .append(" && ").append(typeName).append(".equals(\"")
                    .append(javaString(enumType.name())).append("\")) {\n")
                    .append(indent).append("        ").append(matchedType).append(" = true;\n");
            emitIntrospectionTypeValue(source, document, mysql, indent + "        ", "queryRootStart", typeValue,
                    enumType.name(), "ENUM");
            source.append(indent).append("    }\n");
        }
        for (String scalar : introspectionScalarTypes(document)) {
            source.append(indent).append("    if (").append(continueCondition).append(" && !").append(matchedType)
                    .append(" && ").append(typeName).append(".equals(\"").append(scalar).append("\")) {\n")
                    .append(indent).append("        ").append(matchedType).append(" = true;\n");
            emitIntrospectionTypeValue(source, document, mysql, indent + "        ", "queryRootStart", typeValue, scalar, "SCALAR");
            source.append(indent).append("    }\n");
        }
        for (String metaType : introspectionMetaObjectTypes()) {
            source.append(indent).append("    if (").append(continueCondition).append(" && !").append(matchedType)
                    .append(" && ").append(typeName).append(".equals(\"").append(metaType).append("\")) {\n")
                    .append(indent).append("        ").append(matchedType).append(" = true;\n");
            emitIntrospectionTypeValue(source, document, mysql, indent + "        ", "queryRootStart", typeValue,
                    metaType, "OBJECT");
            source.append(indent).append("    }\n");
        }
        for (String metaType : introspectionMetaEnumTypes()) {
            source.append(indent).append("    if (").append(continueCondition).append(" && !").append(matchedType)
                    .append(" && ").append(typeName).append(".equals(\"").append(metaType).append("\")) {\n")
                    .append(indent).append("        ").append(matchedType).append(" = true;\n");
            emitIntrospectionTypeValue(source, document, mysql, indent + "        ", "queryRootStart", typeValue,
                    metaType, "ENUM");
            source.append(indent).append("    }\n");
        }
        source.append(indent).append("    if (").append(continueCondition).append(") {\n")
                .append(indent).append("        members = DatabaseGraphqlEngine.appendJsonMember(members, DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, queryRootStart, \"__type\"), ")
                .append(typeValue).append(");\n")
                .append(indent).append("    }\n")
                .append(indent).append("}\n");
    }

    /** Emits the supported stable fields of {@code __Schema}. */
    private static void emitIntrospectionSchemaValue(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            boolean mysql,
            String indent,
            String fieldStart,
            String value
    ) {
        String continueCondition = mysql ? "response.length() == 0" : "true";
        String scope = "introspection_schema";
        String queryTypeStart = scopedLocal("introspectionSchemaField", scope, "queryType");
        String mutationTypeStart = scopedLocal("introspectionSchemaField", scope, "mutationType");
        String subscriptionTypeStart = scopedLocal("introspectionSchemaField", scope, "subscriptionType");
        String typesStart = scopedLocal("introspectionSchemaField", scope, "types");
        String directivesStart = scopedLocal("introspectionSchemaField", scope, "directives");
        String descriptionStart = scopedLocal("introspectionSchemaField", scope, "description");
        String typeNameStart = scopedLocal("introspectionSchemaField", scope, "typename");
        String selectionPlan = scopedLocal("introspectionSchemaPlan", scope, "value");
        String selectedCount = scopedLocal("introspectionSchemaSelected", scope, "value");
        String members = scopedLocal("introspectionSchemaMembers", scope, "value");
        String queryTypeValue = scopedLocal("introspectionSchemaQuery", scope, "value");
        String mutationShapeValue = scopedLocal("introspectionSchemaMutationShape", scope, "value");
        String subscriptionShapeValue = scopedLocal("introspectionSchemaSubscriptionShape", scope, "value");
        String typesItems = scopedLocal("introspectionSchemaTypesItems", scope, "value");
        String typesSelectionPlan = scopedLocal("introspectionSchemaTypesPlan", scope, "value");
        String typesSelectionValidated = scopedLocal("introspectionSchemaTypesValidated", scope, "value");
        String directivesValue = scopedLocal("introspectionSchemaDirectives", scope, "value");

        source.append(indent).append("String ").append(selectionPlan)
                .append(" = DatabaseGraphqlEngine.introspectionSelectionPlanFromAst(query, requestAst, ")
                .append(fieldStart).append(", variablesJson, \"__Schema\");\n");
        emitIntrospectionSelectionStart(source, indent, queryTypeStart, selectionPlan, "queryType");
        emitIntrospectionSelectionStart(source, indent, mutationTypeStart, selectionPlan, "mutationType");
        emitIntrospectionSelectionStart(source, indent, subscriptionTypeStart, selectionPlan, "subscriptionType");
        emitIntrospectionSelectionStart(source, indent, typesStart, selectionPlan, "types");
        emitIntrospectionSelectionStart(source, indent, directivesStart, selectionPlan, "directives");
        emitIntrospectionSelectionStart(source, indent, descriptionStart, selectionPlan, "description");
        emitIntrospectionSelectionStart(source, indent, typeNameStart, selectionPlan, "__typename");
        source.append(indent).append("int ").append(selectedCount).append(" = 0;\n");
        emitIntrospectionSelectedIncrement(source, indent, selectedCount, queryTypeStart);
        emitIntrospectionSelectedIncrement(source, indent, selectedCount, mutationTypeStart);
        emitIntrospectionSelectedIncrement(source, indent, selectedCount, subscriptionTypeStart);
        emitIntrospectionSelectedIncrement(source, indent, selectedCount, typesStart);
        emitIntrospectionSelectedIncrement(source, indent, selectedCount, directivesStart);
        emitIntrospectionSelectedIncrement(source, indent, selectedCount, descriptionStart);
        emitIntrospectionSelectedIncrement(source, indent, selectedCount, typeNameStart);
        emitIntrospectionFailureIf(source, mysql, indent, selectedCount + " == 0",
                "field '__schema' requires a selection set", fieldStart);
        emitIntrospectionFailureIf(source, mysql, indent, selectedCount
                        + " != DatabaseGraphqlEngine.selectionPlanCount(" + selectionPlan + ")",
                "unsupported field in __Schema selection", fieldStart);
        for (String start : List.of(queryTypeStart, mutationTypeStart, subscriptionTypeStart, typesStart,
                directivesStart, descriptionStart, typeNameStart)) {
            emitIntrospectionFailureIf(source, mysql, indent, start + " >= 0 && !DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, "
                            + start + ", \"\")",
                    "introspection field does not support arguments", start);
        }
        for (String start : List.of(descriptionStart, typeNameStart)) {
            emitIntrospectionFailureIf(source, mysql, indent, start + " >= 0 && !DatabaseGraphqlEngine.fieldHasNoSelectionSetFromAst(query, requestAst, "
                            + start + ")",
                    "introspection scalar field cannot have a selection", start);
        }
        source.append(indent).append("if (").append(continueCondition).append(") {\n")
                .append(indent).append("    String ").append(members).append(" = \"\";\n");
        source.append(indent).append("    if (").append(queryTypeStart).append(" >= 0) {\n")
                .append(indent).append("        String ").append(queryTypeValue).append(" = \"\";\n");
        emitIntrospectionTypeValue(source, document, mysql, indent + "        ", queryTypeStart, queryTypeValue, "Query", "OBJECT");
        source.append(indent).append("        if (").append(continueCondition).append(") ").append(members)
                .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(members)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(queryTypeStart)
                .append(", \"queryType\"), ").append(queryTypeValue).append(");\n")
                .append(indent).append("    }\n");
        source.append(indent).append("    if (").append(mutationTypeStart).append(" >= 0) {\n")
                .append(indent).append("        String ").append(mutationShapeValue).append(" = \"\";\n");
        emitIntrospectionTypeValue(source, document, mysql, indent + "        ", mutationTypeStart, mutationShapeValue, "Mutation", "OBJECT");
        source.append(indent).append("        if (").append(continueCondition).append(") ").append(members)
                .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(members)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(mutationTypeStart)
                .append(", \"mutationType\"), ").append(document.mutations().isEmpty() ? "\"null\"" : mutationShapeValue).append(");\n")
                .append(indent).append("    }\n");
        source.append(indent).append("    if (").append(subscriptionTypeStart).append(" >= 0) {\n")
                .append(indent).append("        String ").append(subscriptionShapeValue).append(" = \"\";\n");
        emitIntrospectionTypeValue(source, document, mysql, indent + "        ", subscriptionTypeStart, subscriptionShapeValue, "Subscription", "OBJECT");
        source.append(indent).append("        if (").append(continueCondition).append(") ").append(members)
                .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(members)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(subscriptionTypeStart)
                .append(", \"subscriptionType\"), \"null\");\n")
                .append(indent).append("    }\n");
        source.append(indent).append("    if (").append(typesStart).append(" >= 0) {\n")
                .append(indent).append("        String ").append(typesItems).append(" = \"\";\n")
                // `types` has one request shape shared by every generated schema type. Compile
                // it once for the enclosing list rather than making every list item reopen the
                // identical fragment-expanded selection from the request AST.
                .append(indent).append("        String ").append(typesSelectionPlan)
                .append(" = DatabaseGraphqlEngine.introspectionSelectionPlanFromAst(query, requestAst, ")
                .append(typesStart).append(", variablesJson, \"__Type\");\n")
                .append(indent).append("        boolean ").append(typesSelectionValidated).append(" = false;\n");
        emitIntrospectionSchemaTypeListItem(source, document, mysql, indent + "        ", typesStart, typesItems,
                typesSelectionPlan, typesSelectionValidated,
                "Query", "OBJECT");
        if (!document.mutations().isEmpty()) {
            emitIntrospectionSchemaTypeListItem(source, document, mysql, indent + "        ", typesStart, typesItems,
                    typesSelectionPlan, typesSelectionValidated,
                    "Mutation", "OBJECT");
        }
        for (TitanGraphqlTypeDocument type : sortedTypes(document)) {
            emitIntrospectionSchemaTypeListItem(source, document, mysql, indent + "        ", typesStart, typesItems,
                    typesSelectionPlan, typesSelectionValidated,
                    type.name(), "OBJECT");
        }
        for (TitanGraphqlInterfaceDocument interfaceType : sortedInterfaces(document)) {
            emitIntrospectionSchemaTypeListItem(source, document, mysql, indent + "        ", typesStart,
                    typesItems, typesSelectionPlan, typesSelectionValidated,
                    interfaceType.name(), "INTERFACE");
        }
        for (TitanGraphqlUnionDocument union : sortedUnions(document)) {
            emitIntrospectionSchemaTypeListItem(source, document, mysql, indent + "        ", typesStart,
                    typesItems, typesSelectionPlan, typesSelectionValidated,
                    union.name(), "UNION");
        }
        for (String derivedType : introspectionDerivedObjectTypes(document)) {
            emitIntrospectionSchemaTypeListItem(source, document, mysql, indent + "        ", typesStart, typesItems,
                    typesSelectionPlan, typesSelectionValidated,
                    derivedType, "OBJECT");
        }
        for (String inputType : introspectionInputObjectNames(document)) {
            emitIntrospectionSchemaTypeListItem(source, document, mysql, indent + "        ", typesStart, typesItems,
                    typesSelectionPlan, typesSelectionValidated,
                    inputType, "INPUT_OBJECT");
        }
        if (introspectionHasSortDirection(document)) {
            emitIntrospectionSchemaTypeListItem(source, document, mysql, indent + "        ", typesStart, typesItems,
                    typesSelectionPlan, typesSelectionValidated,
                    "SortDirection", "ENUM");
        }
        for (TitanGraphqlEnumDocument enumType : sortedEnums(document)) {
            emitIntrospectionSchemaTypeListItem(source, document, mysql, indent + "        ", typesStart, typesItems,
                    typesSelectionPlan, typesSelectionValidated, enumType.name(), "ENUM");
        }
        for (String scalar : introspectionScalarTypes(document)) {
            emitIntrospectionSchemaTypeListItem(source, document, mysql, indent + "        ", typesStart, typesItems,
                    typesSelectionPlan, typesSelectionValidated,
                    scalar, "SCALAR");
        }
        for (String metaType : introspectionMetaObjectTypes()) {
            emitIntrospectionSchemaTypeListItem(source, document, mysql, indent + "        ", typesStart, typesItems,
                    typesSelectionPlan, typesSelectionValidated, metaType, "OBJECT");
        }
        for (String metaType : introspectionMetaEnumTypes()) {
            emitIntrospectionSchemaTypeListItem(source, document, mysql, indent + "        ", typesStart, typesItems,
                    typesSelectionPlan, typesSelectionValidated, metaType, "ENUM");
        }
        source.append(indent).append("        if (").append(continueCondition).append(") ").append(members)
                .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(members)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(typesStart)
                .append(", \"types\"), \"[\" + ").append(typesItems).append(" + \"]\");\n")
                .append(indent).append("    }\n");
        source.append(indent).append("    if (").append(directivesStart).append(" >= 0) {\n")
                .append(indent).append("        String ").append(directivesValue)
                .append(" = DatabaseGraphqlEngine.introspectionDirectivesJson(query, requestAst, ")
                .append(directivesStart).append(", variablesJson, \"")
                .append(javaString(introspectionDirectiveDescriptor(document))).append("\");\n")
                .append(indent).append("        if (DatabaseGraphqlEngine.isErrorJson(")
                .append(directivesValue).append(")) {\n");
        if (mysql) {
            source.append(indent).append("            response = ").append(directivesValue).append(";\n");
        } else {
            source.append(indent).append("            return ").append(directivesValue).append(";\n");
        }
        source.append(indent).append("        }\n")
                .append(indent).append("        if (").append(continueCondition).append(") ").append(members)
                .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(members)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                .append(directivesStart).append(", \"directives\"), ").append(directivesValue).append(");\n")
                .append(indent).append("    }\n");
        source.append(indent).append("    if (").append(descriptionStart).append(" >= 0) ").append(members)
                .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(members)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(descriptionStart)
                .append(", \"description\"), \"null\");\n")
                .append(indent).append("    if (").append(typeNameStart).append(" >= 0) ").append(members)
                .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(members)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(typeNameStart)
                .append(", \"__typename\"), DatabaseGraphqlEngine.jsonString(\"__Schema\"));\n")
                .append(indent).append("    ").append(value).append(" = \"{\" + ").append(members).append(" + \"}\";\n")
                .append(indent).append("}\n");
    }

    private static void emitIntrospectionSchemaTypeListItem(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            boolean mysql,
            String indent,
            String typesStart,
            String items,
            String selectionPlan,
            String selectionValidated,
            String typeName,
            String kind
    ) {
        String continueCondition = mysql ? "response.length() == 0" : "true";
        String value = scopedLocal("introspectionSchemaTypeValue", typeName, "value");
        source.append(indent).append("{\n")
                .append(indent).append("    String ").append(value).append(" = \"\";\n");
        emitGenericIntrospectionTypeValue(source, document, mysql, indent + "    ", typesStart, value, typeName, kind,
                selectionPlan, selectionValidated);
        source.append(indent).append("    if (").append(continueCondition).append(") ")
                .append(selectionValidated).append(" = true;\n");
        source.append(indent).append("    if (").append(continueCondition).append(") ").append(items)
                .append(" = DatabaseGraphqlEngine.appendJsonItem(").append(items).append(", ").append(value)
                .append(");\n")
                .append(indent).append("}\n");
    }

    /** Emits one named {@code __Type} object from model constants, validating its selected fields. */
    private static void emitIntrospectionTypeValue(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            boolean mysql,
            String indent,
            String fieldStart,
            String value,
            String typeName,
            String kind
    ) {
        emitGenericIntrospectionTypeValue(source, document, mysql, indent, fieldStart, value, typeName, kind,
                null, "false");
    }

    /** Emits a call to the shared transpiled named-type interpreter with generated metadata only. */
    private static void emitGenericIntrospectionTypeValue(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            boolean mysql,
            String indent,
            String fieldStart,
            String value,
            String typeName,
            String kind,
            String reusableSelectionPlan,
            String selectionPrevalidated
    ) {
        TitanGraphqlTypeDocument modelType = document.types().stream()
                .filter(candidate -> candidate.name().equals(typeName)).findFirst().orElse(null);
        TitanGraphqlInterfaceDocument interfaceType = interfaceType(document, typeName);
        TitanGraphqlUnionDocument union = unionType(document, typeName);
        String fieldsDescriptor = modelType != null
                ? introspectionScalarFieldDescriptor(document, modelType)
                : interfaceType != null ? introspectionInterfaceFieldDescriptor(document, interfaceType)
                : introspectionMetaObjectFieldDescriptor(typeName);
        if (modelType == null && fieldsDescriptor.length() == 0) {
            fieldsDescriptor = introspectionDerivedObjectFieldDescriptor(document, typeName);
        }
        String inputFieldsDescriptor = kind.equals("INPUT_OBJECT")
                ? introspectionInputObjectFieldDescriptor(document, typeName) : "";
        String enumValuesDescriptor = kind.equals("ENUM")
                ? introspectionEnumValueDescriptor(document, typeName) : "";
        TitanGraphqlInputObjectDocument inputObject = kind.equals("INPUT_OBJECT")
                ? inputObjectType(document, typeName) : null;
        String typeDescription = modelType != null ? modelType.description()
                : interfaceType != null ? interfaceType.description()
                : union != null ? union.description()
                : inputObject == null ? "" : inputObject.description();
        String interfacesDescriptor = introspectionInterfacesDescriptor(modelType);
        String possibleTypesDescriptor = introspectionPossibleTypesDescriptor(document, interfaceType, union);
        String fieldMetadataDescriptor = introspectionOutputFieldMetadataDescriptor(
                fieldsDescriptor, modelType, interfaceType);
        String selectionPlan = reusableSelectionPlan == null ? "\"\"" : reusableSelectionPlan;
        source.append(indent).append(value)
                .append(" = DatabaseGraphqlEngine.introspectionNamedTypeJson(query, requestAst, ")
                .append(fieldStart).append(", variablesJson, ").append(selectionPlan).append(", \"")
                .append(javaString(typeName)).append("\", \"").append(javaString(kind)).append("\", \"")
                .append(javaString(fieldsDescriptor)).append("\", \"")
                .append(javaString(inputFieldsDescriptor)).append("\", \"")
                .append(javaString(enumValuesDescriptor)).append("\", \"")
                .append(javaString(typeDescription)).append("\", \"")
                .append(javaString(interfacesDescriptor)).append("\", \"")
                .append(javaString(possibleTypesDescriptor)).append("\", \"")
                .append(javaString(fieldMetadataDescriptor)).append("\", ").append(selectionPrevalidated)
                .append(");\n")
                .append(indent).append("if (DatabaseGraphqlEngine.isErrorJson(").append(value).append(")) {\n");
        if (mysql) {
            source.append(indent).append("    response = ").append(value).append(";\n");
        } else {
            source.append(indent).append("    return ").append(value).append(";\n");
        }
        source.append(indent).append("}\n");
    }


    private static void emitIntrospectionSelectionStart(
            StringBuilder source,
            String indent,
            String local,
            String selectionPlan,
            String field
    ) {
        source.append(indent).append("int ").append(local)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStartForFieldFromAst(query, requestAst, ")
                .append(selectionPlan).append(", \"").append(javaString(field)).append("\");\n");
    }

    private static void emitIntrospectionSelectedIncrement(StringBuilder source, String indent, String count, String start) {
        source.append(indent).append("if (").append(start).append(" >= 0) ").append(count).append("++;\n");
    }

    private static void emitIntrospectionFailureIf(
            StringBuilder source,
            boolean mysql,
            String indent,
            String condition,
            String message,
            String position
    ) {
        String guardedCondition = mysql ? "response.length() == 0 && " + condition : condition;
        source.append(indent).append("if (").append(guardedCondition).append(") {\n");
        emitIntrospectionFailure(source, mysql, indent + "    ", message, position);
        source.append(indent).append("}\n");
    }

    private static void emitIntrospectionFailure(
            StringBuilder source,
            boolean mysql,
            String indent,
            String message,
            String position
    ) {
        source.append(indent);
        if (mysql) {
            source.append("response = ");
        } else {
            source.append("return ");
        }
        source.append("DatabaseGraphqlEngine.errorJsonAt(\"").append(javaString(message))
                .append("\", query, ").append(position).append(");\n");
    }

    private static void emitIntrospectionAuthorizationFailure(
            StringBuilder source,
            boolean mysql,
            String indent,
            String message,
            String position
    ) {
        source.append(indent);
        if (mysql) {
            source.append("response = ");
        } else {
            source.append("return ");
        }
        source.append("DatabaseGraphqlEngine.authorizationErrorJsonAt(\"").append(javaString(message))
                .append("\", query, ").append(position).append(");\n");
    }

    /** Emits custom mutations in document order, which is GraphQL's required serial order. */
    private static void emitMutationExecute(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            boolean mysql
    ) {
        String indent = mysql ? "            " : "        ";
        String nested = indent + "    ";
        if (document.mutations().isEmpty()) {
            source.append(indent).append("if (")
                    .append(mysql ? "selectedOperationKind.equals(\"mutation\")"
                            : "DatabaseGraphqlAst.operationKind(requestAst).equals(\"mutation\")")
                    .append(") {").append("\n");
            emitMutationCodedFailure(source, mysql, nested,
                    "this schema has no enabled custom mutations", "rollbackUnsupportedOperationErrorJson");
            source.append(indent).append("}\n");
            return;
        }

        source.append(indent).append("if (")
                .append(mysql ? "selectedOperationKind.equals(\"mutation\")"
                        : "DatabaseGraphqlAst.operationKind(requestAst).equals(\"mutation\")")
                .append(") {\n");
        if (mysql) {
            source.append(nested).append("if (DatabaseGraphqlEngine.deadlineExpired(deadlineEpochMillis)) {\n")
                    .append(nested).append("    response = DatabaseGraphqlEngine.rollbackDeadlineExceededErrorJson(\"request deadline exceeded during database execution\");\n")
                    .append(nested).append("} else {\n");
        } else {
            source.append(nested).append("if (DatabaseGraphqlEngine.deadlineExpired(deadlineEpochMillis)) return DatabaseGraphqlEngine.rollbackDeadlineExceededErrorJson(\"request deadline exceeded during database execution\");\n");
        }
        source.append(nested).append("String mutationMembers = \"\";\n")
                .append(nested).append("int requestedMutationCount = DatabaseGraphqlEngine.rootFieldCountForTypeFromAst(query, requestAst, \"Mutation\");\n")
                .append(nested).append("String mutationPlan = DatabaseGraphqlEngine.rootSelectionPlanFromAst(query, requestAst, variablesJson, \"Mutation\", \"\");\n")
                .append(nested).append("int mutationCount = DatabaseGraphqlEngine.selectionPlanCount(mutationPlan);\n");
        if (mysql) {
            source.append(nested).append("if (mutationCount < 0) {\n");
            emitMutationFailure(source, true, nested + "    ", "selected mutation has an invalid or unsupported directive");
            source.append(nested).append("}\n")
                    .append(nested).append("if (response.length() == 0 && DatabaseGraphqlEngine.selectionPlanHasDuplicateResponseKeyFromAst(query, requestAst, mutationPlan)) {\n");
            emitMutationFailure(source, true, nested + "    ", "selected mutation needs unsupported field merging for a duplicate response key");
            source.append(nested).append("}\n")
                    .append(nested).append("if (requestedMutationCount == 0 && response.length() == 0) {\n");
            emitMutationFailure(source, true, nested + "    ", "selected mutation has no root fields");
            source.append(nested).append("}\n");
        } else {
            source.append(nested).append("if (mutationCount < 0) ");
            emitMutationFailure(source, false, "", "selected mutation has an invalid or unsupported directive");
            source.append(nested).append("if (DatabaseGraphqlEngine.selectionPlanHasDuplicateResponseKeyFromAst(query, requestAst, mutationPlan)) ");
            emitMutationFailure(source, false, "", "selected mutation needs unsupported field merging for a duplicate response key");
            source.append(nested).append("if (requestedMutationCount == 0) ");
            emitMutationFailure(source, false, "", "selected mutation has no root fields");
        }
        // Every generated generic mutation has a fixed lock/existence/update footprint. Reject a
        // statically impossible operation before repeating payload/argument validation for every
        // root and before the first serial effect. Dynamic query/relation statements retain their
        // just-in-time reservations because their count depends on selected fields and live rows.
        int maximumGenericMutationRoots = MAX_APPLICATION_SQL_STATEMENTS_PER_REQUEST / 3;
        String statementBudgetMessage = "request exceeds application SQL statement budget of "
                + MAX_APPLICATION_SQL_STATEMENTS_PER_REQUEST;
        if (mysql) {
            source.append(nested).append("if (response.length() == 0 && mutationCount > ")
                    .append(maximumGenericMutationRoots).append(") {\n")
                    .append(nested).append("    response = DatabaseGraphqlEngine.rollbackResourceLimitErrorJson(\"")
                    .append(statementBudgetMessage).append("\");\n")
                    .append(nested).append("}\n");
        } else {
            source.append(nested).append("if (mutationCount > ").append(maximumGenericMutationRoots)
                    .append(") return DatabaseGraphqlEngine.rollbackResourceLimitErrorJson(\"")
                    .append(statementBudgetMessage).append("\");\n");
        }
        emitMutationPrevalidation(source, document, mysql, "mutationPlan", "mutationCount", nested);
        if (mysql) {
            source.append(nested).append("int mutationIndex = 0;\n")
                    .append(nested).append("while (mutationIndex < mutationCount && response.length() == 0) {\n");
        } else {
            source.append(nested).append("int mutationIndex = 0;\n")
                    .append(nested).append("while (mutationIndex < mutationCount) {\n");
        }
        String loop = nested + "    ";
        source.append(loop).append("int mutationRootStart = DatabaseGraphqlEngine.selectionPlanFieldStart(mutationPlan, mutationIndex);\n");
        if (mysql) {
            source.append(loop).append("if (DatabaseGraphqlEngine.deadlineExpired(deadlineEpochMillis)) {\n")
                    .append(loop).append("    response = DatabaseGraphqlEngine.rollbackDeadlineExceededErrorJson(\"request deadline exceeded during database execution\");\n")
                    .append(loop).append("} else {\n");
        } else {
            source.append(loop).append("if (DatabaseGraphqlEngine.deadlineExpired(deadlineEpochMillis)) return DatabaseGraphqlEngine.rollbackDeadlineExceededErrorJson(\"request deadline exceeded during database execution\");\n");
        }
        source.append(loop).append("boolean mutationMatched = false;\n");
        for (TitanGraphqlMutationDocument mutation : sortedMutations(document)) {
            source.append(loop).append("if (!mutationMatched && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, mutationRootStart, \"")
                    .append(javaString(mutation.name())).append("\")) {\n")
                    .append(loop).append("    mutationMatched = true;\n");
            emitInlineMutation(source, document, mutation, "mutationRootStart", "mutationMembers", mysql, loop + "    ");
            source.append(loop).append("}\n");
        }
        source.append(loop).append("if (!mutationMatched) {\n");
        emitMutationFailure(source, mysql, loop + "    ", "selected mutation contains an unknown or unsupported root field");
        source.append(loop).append("}\n");
        if (mysql) {
            source.append(loop).append("}\n");
        }
        if (mysql) {
            source.append(loop).append("if (response.length() == 0 && DatabaseGraphqlEngine.responseAssemblyExceeded(mutationMembers)) {\n")
                    .append(loop).append("    response = DatabaseGraphqlEngine.rollbackResourceLimitErrorJson(\"response exceeds the database engine character budget\");\n")
                    .append(loop).append("}\n");
        } else {
            source.append(loop).append("if (DatabaseGraphqlEngine.responseAssemblyExceeded(mutationMembers)) return DatabaseGraphqlEngine.rollbackResourceLimitErrorJson(\"response exceeds the database engine character budget\");\n");
        }
        source.append(loop).append("mutationIndex++;\n")
                .append(nested).append("}\n");
        if (mysql) {
            source.append(nested).append("if (response.length() == 0) {\n")
                    .append(nested).append("    response = \"TITAN-GRAPHQL-TRANSPORT/1 COMMIT\\n{\\\"data\\\":{\" + mutationMembers + \"}}\";\n")
                    .append(nested).append("    if (DatabaseGraphqlEngine.trustedContextFlag(trustedContextJson, \"includeExecutionMetrics\")) {\n")
                    .append(nested).append("        String measuredMutationResponse = DatabaseGraphqlEngine.appendExecutionMetrics(response.substring(response.indexOf(\"\\n\") + 1), applicationSqlStatements, decodedApplicationRows);\n")
                    .append(nested).append("        if (DatabaseGraphqlEngine.responseAssemblyExceeded(measuredMutationResponse)) response = DatabaseGraphqlEngine.rollbackResourceLimitErrorJson(\"response exceeds the database engine character budget\");\n")
                    .append(nested).append("        else response = response.substring(0, response.indexOf(\"\\n\") + 1) + measuredMutationResponse;\n")
                    .append(nested).append("    }\n")
                    .append(nested).append("}\n");
        } else {
            source.append(nested).append("String completedMutationResponse = \"{\\\"data\\\":{\" + mutationMembers + \"}}\";\n")
                    .append(nested).append("if (DatabaseGraphqlEngine.trustedContextFlag(trustedContextJson, \"includeExecutionMetrics\")) completedMutationResponse = DatabaseGraphqlEngine.appendExecutionMetrics(completedMutationResponse, applicationSqlStatements, decodedApplicationRows);\n")
                    .append(nested).append("if (DatabaseGraphqlEngine.responseAssemblyExceeded(completedMutationResponse)) return DatabaseGraphqlEngine.rollbackResourceLimitErrorJson(\"response exceeds the database engine character budget\");\n")
                    .append(nested).append("return DatabaseGraphqlEngine.transactionOutcomeJson(completedMutationResponse, \"COMMIT\");\n");
        }
        if (mysql) {
            source.append(nested).append("}\n");
        }
        source.append(indent).append("}\n");
    }

    /**
     * Validates every selected mutation root before the serial execution pass can issue a write.
     * The execution pass still performs its own coercion because this scalar Phase 1 generator
     * does not materialize a schema-aware input-value tree. Keeping the validation pass
     * database-resident is nevertheless essential: a malformed later root must not stage an
     * earlier update simply because GraphQL mutation roots execute serially.
     */
    private static void emitMutationPrevalidation(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            boolean mysql,
            String mutationPlan,
            String mutationCount,
            String indent
    ) {
        String index = "mutationValidationIndex";
        String rootStart = "mutationValidationRootStart";
        String matched = "mutationValidationMatched";
        if (mysql) {
            source.append(indent).append("int ").append(index).append(" = 0;\n")
                    .append(indent).append("while (").append(index).append(" < ").append(mutationCount)
                    .append(" && response.length() == 0) {\n");
        } else {
            source.append(indent).append("int ").append(index).append(" = 0;\n")
                    .append(indent).append("while (").append(index).append(" < ").append(mutationCount).append(") {\n");
        }
        String loop = indent + "    ";
        source.append(loop).append("int ").append(rootStart).append(" = DatabaseGraphqlEngine.selectionPlanFieldStart(")
                .append(mutationPlan).append(", ").append(index).append(");\n")
                .append(loop).append("boolean ").append(matched).append(" = false;\n");
        for (TitanGraphqlMutationDocument mutation : sortedMutations(document)) {
            source.append(loop).append("if (!").append(matched)
                    .append(" && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ").append(rootStart).append(", \"")
                    .append(javaString(mutation.name())).append("\")) {\n")
                    .append(loop).append("    ").append(matched).append(" = true;\n");
            TitanGraphqlTypeDocument type = type(document, mutation.type());
            emitMutationPolicyGuards(source, document, mutation, mysql, loop + "    ");
            if (mysql) {
                source.append(loop).append("    if (response.length() == 0) {\n");
                emitInlineMutationPrevalidation(source, document, mutation, type, rootStart, true, loop + "        ");
                source.append(loop).append("    }\n");
            } else {
                emitInlineMutationPrevalidation(source, document, mutation, type, rootStart, false, loop + "    ");
            }
            source.append(loop).append("}\n");
        }
        source.append(loop).append("if (!").append(matched).append(") {\n");
        emitMutationFailure(source, mysql, loop + "    ", "selected mutation contains an unknown or unsupported root field");
        source.append(loop).append("}\n")
                .append(loop).append(index).append("++;\n")
                .append(indent).append("}\n");
    }

    /** Emits the schema-specialized, no-SQL validation portion of one mutation root. */
    private static void emitInlineMutationPrevalidation(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlMutationDocument mutation,
            TitanGraphqlTypeDocument type,
            String rootStart,
            boolean mysql,
            String indent
    ) {
        // PostgreSQL identifiers are capped at 63 bytes after Titan lowers these Java locals.
        // Keep the phase marker short enough that different argument suffixes still survive.
        String scope = "mutation_pre_" + mutation.name();
        String selectedCount = scopedLocal("mutationSelectedCount", scope, "payload");
        String typeNameStart = scopedLocal("mutationTypeNameStart", scope, "payload");
        source.append(indent).append("int ").append(selectedCount).append(" = 0;\n");
        source.append(indent).append("int ").append(typeNameStart)
                .append(" = DatabaseGraphqlEngine.selectionFieldStartFromAst(query, requestAst, ").append(rootStart)
                .append(", \"__typename\", variablesJson, \"").append(javaString(type.name())).append("\");\n")
                .append(indent).append("if (").append(typeNameStart).append(" >= 0) ").append(selectedCount).append("++;\n");
        emitTypeNameLeafValidation(source, mysql, indent, mysql ? "response.length() == 0 && " : "",
                typeNameStart, type.name());
        for (TitanGraphqlMutationDocument.MutationDocumentPayloadField payload : sortedPayload(mutation)) {
            String fieldStart = scopedLocal("mutationFieldStart", scope, payload.name());
            source.append(indent).append("int ").append(fieldStart)
                    .append(" = DatabaseGraphqlEngine.selectionFieldStartFromAst(query, requestAst, ").append(rootStart)
                    .append(", \"").append(javaString(payload.name())).append("\", variablesJson, \"")
                    .append(javaString(type.name())).append("\");\n")
                    .append(indent).append("if (").append(fieldStart).append(" >= 0) ").append(selectedCount).append("++;\n");
            emitScalarLeafValidation(source, mysql, indent, mysql ? "response.length() == 0 && " : "",
                    fieldStart, type.name() + "." + payload.name());
        }
        source.append(indent).append("if (").append(selectedCount).append(" == 0) {\n");
        emitMutationFailure(source, mysql, indent + "    ", "mutation '" + mutation.name() + "' selection must contain at least one field");
        source.append(indent).append("}\n");
        source.append(indent).append("if (").append(selectedCount)
                .append(" != DatabaseGraphqlEngine.selectionFieldCountFromAst(query, requestAst, ").append(rootStart).append(", variablesJson, \"")
                .append(javaString(type.name())).append("\")) {\n");
        emitMutationFailure(source, mysql, indent + "    ", "unknown or unsupported field in mutation '" + mutation.name() + "' selection");
        source.append(indent).append("}\n");
        source.append(indent).append("if (DatabaseGraphqlEngine.fieldArgumentCountFromAst(requestAst, ").append(rootStart)
                .append(") > ").append(mutationPublicArgumentCount(mutation)).append(") {\n");
        emitMutationFailure(source, mysql, indent + "    ", "mutation '" + mutation.name() + "' has an unknown, duplicate, or malformed argument");
        source.append(indent).append("}\n");

        emitMutationBindingCoercion(source, document, mutation, type, rootStart, scope, mysql, indent);
    }

    private static void emitInlineMutation(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlMutationDocument mutation,
            String rootStart,
            String members,
            boolean mysql,
            String indent
    ) {
        TitanGraphqlTypeDocument type = type(document, mutation.type());
        emitMutationPolicyGuards(source, document, mutation, mysql, indent);
        // Root-level dispatch checks the deadline before it enters this mutation. Recheck after
        // policy evaluation and coercion setup, immediately before the mutation body can open
        // its lock/existence/update statements.
        emitExecutionDeadlineCheck(source, mysql, indent, true);
        if (mysql) {
            source.append(indent).append("if (response.length() == 0) {\n");
            emitInlineMutationBody(source, document, mutation, type, rootStart, members, true, indent + "    ");
            source.append(indent).append("}\n");
        } else {
            emitInlineMutationBody(source, document, mutation, type, rootStart, members, false, indent);
        }
    }

    /** Materializes reviewed scalar bindings from either flat arguments or one input object. */
    private static void emitMutationBindingCoercion(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlMutationDocument mutation,
            TitanGraphqlTypeDocument type,
            String rootStart,
            String scope,
            boolean mysql,
            String indent
    ) {
        for (MutationBinding argument : sortedMutationArguments(mutation)) {
            String raw = scopedLocal("mutationArgument", scope, argument.name());
            String value = scopedLocal("mutationArgumentValue", scope, argument.name());
            String booleanRaw = scopedLocal("mutationBoolRaw", scope, argument.name());
            String publicType = mutationPublicArgumentType(mutation, argument);
            source.append(indent)
                    .append("if (!DatabaseGraphqlEngine.argumentVariableTypeIsCompatibleFromAst(query, requestAst, ")
                    .append(rootStart).append(", \"").append(javaString(argument.sourceArgument()))
                    .append("\", \"").append(javaString(publicType)).append("\")) {\n");
            emitMutationFailure(source, mysql, indent + "    ", "variable for mutation argument '"
                    + argument.sourceArgument() + "' must be declared as " + publicType + "!");
            source.append(indent).append("}\n")
                    .append(indent).append("String ").append(raw)
                    .append(" = DatabaseGraphqlEngine.materializedArgumentValue(materializedArguments, ")
                    .append(rootStart).append(", \"").append(javaString(argument.sourceArgument()))
                    .append("\");\n");
            if (!argument.sourcePath().isEmpty()) {
                source.append(indent).append(raw).append(" = DatabaseGraphqlEngine.inputObjectPathValue(")
                        .append(raw).append(", \"").append(javaString(argument.sourcePath())).append("\");\n");
            }
            source.append(indent).append("if (").append(raw).append(".length() == 0) {\n");
            emitMutationFailure(source, mysql, indent + "    ", argument.sourcePath().isEmpty()
                    ? "required mutation argument '" + argument.name() + "' is missing"
                    : "required mutation input field '" + argument.sourcePath() + "' is missing");
            source.append(indent).append("}\n");
            emitMutationArgumentCoercion(source, document, type, argument, raw, value, booleanRaw, mysql, indent);
        }
    }

    private static void emitInlineMutationBody(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlMutationDocument mutation,
            TitanGraphqlTypeDocument type,
            String rootStart,
            String members,
            boolean mysql,
            String indent
    ) {
        String scope = "mutation_" + mutation.name();
        String selectedCount = scopedLocal("mutationSelectedCount", scope, "payload");
        String typeNameStart = scopedLocal("mutationTypeNameStart", scope, "payload");
        source.append(indent).append("int ").append(selectedCount).append(" = 0;\n");
        source.append(indent).append("int ").append(typeNameStart)
                .append(" = DatabaseGraphqlEngine.selectionFieldStartFromAst(query, requestAst, ").append(rootStart)
                .append(", \"__typename\", variablesJson, \"").append(javaString(type.name())).append("\");\n")
                .append(indent).append("if (").append(typeNameStart).append(" >= 0) ").append(selectedCount).append("++;\n");
        for (TitanGraphqlMutationDocument.MutationDocumentPayloadField payload : sortedPayload(mutation)) {
            String fieldStart = scopedLocal("mutationFieldStart", scope, payload.name());
            source.append(indent).append("int ").append(fieldStart)
                    .append(" = DatabaseGraphqlEngine.selectionFieldStartFromAst(query, requestAst, ").append(rootStart)
                    .append(", \"").append(javaString(payload.name())).append("\", variablesJson, \"")
                    .append(javaString(type.name())).append("\");\n")
                    .append(indent).append("if (").append(fieldStart).append(" >= 0) ").append(selectedCount).append("++;\n");
        }
        source.append(indent).append("if (").append(selectedCount)
                .append(" != DatabaseGraphqlEngine.selectionFieldCountFromAst(query, requestAst, ").append(rootStart).append(", variablesJson, \"")
                .append(javaString(type.name())).append("\")) {\n");
        emitMutationFailure(source, mysql, indent + "    ", "unknown or unsupported field in mutation '" + mutation.name() + "' selection");
        source.append(indent).append("}\n");
        source.append(indent).append("if (DatabaseGraphqlEngine.fieldArgumentCountFromAst(requestAst, ").append(rootStart)
                .append(") > ").append(mutationPublicArgumentCount(mutation)).append(") {\n");
        emitMutationFailure(source, mysql, indent + "    ", "mutation '" + mutation.name() + "' has an unknown, duplicate, or malformed argument");
        source.append(indent).append("}\n");

        emitMutationBindingCoercion(source, document, mutation, type, rootStart, scope, mysql, indent);

        // Locking and existence are deliberately separate reads. Titan's portable typed
        // single-row reader represents a no-row SELECT as NULL, while the aggregate count has a
        // stable zero result. The first read prevents a concurrent delete after validation; the
        // second preserves the normal GraphQL absent-target error rather than surfacing a JDBC
        // routine exception.
        String lockStatement = scopedLocal("mutationLockStatement", scope, "target");
        String lockResultSet = scopedLocal("mutationLockResultSet", scope, "target");
        String lockValue = scopedLocal("mutationLockValue", scope, "target");
        emitExecutionDeadlineCheck(source, mysql, indent, true);
        emitApplicationStatementReservation(source, mysql, indent, true, rootStart);
        // This read can materialize at most one row. Reserve that known capacity before opening
        // the cursor so Titan retains its portable next/getter single-row lowering shape.
        emitDecodedApplicationRowReservation(source, mysql, indent, true, rootStart);
        if (mysql) source.append(indent).append("if (response.length() == 0) {\n");
        String lockIndent = mysql ? indent + "    " : indent;
        source.append(lockIndent).append("PreparedStatement ").append(lockStatement)
                .append(" = connection.prepareStatement(\"").append(javaString(mutationTargetLockSql(type, mutation, !mysql)))
                .append("\");\n");
        int lockIndex = 1;
        for (MutationBinding argument : sortedMutationArguments(mutation)) {
            if (argument.key()) {
                emitMutationSetter(source, lockIndent, lockStatement, lockIndex++, scope, type, argument);
            }
        }
        source.append(lockIndent).append("ResultSet ").append(lockResultSet).append(" = ")
                .append(lockStatement).append(".executeQuery();\n")
                .append(lockIndent).append("if (").append(lockResultSet).append(".next()) {\n");
        source.append(lockIndent).append("    long ").append(lockValue).append(" = ").append(lockResultSet)
                .append(".getLong(\"tgql_lock\");\n")
                ;
        source.append(lockIndent).append("}\n");
        if (mysql) source.append(indent).append("}\n");

        // The lock itself can consume the remaining request budget. Do not issue the
        // definitive-existence query after that point. MySQL needs an explicit scope because
        // assigning its terminal response alone does not short-circuit following statements.
        emitExecutionDeadlineCheck(source, mysql, indent, true);
        emitApplicationStatementReservation(source, mysql, indent, true, rootStart);
        // COUNT(*) deterministically materializes one row; pre-reserve it for the same portable
        // single-row transfer contract used by point and boundary reads.
        emitDecodedApplicationRowReservation(source, mysql, indent, true, rootStart);
        if (mysql) {
            source.append(indent).append("if (response.length() == 0) {\n");
        }
        String checkIndent = mysql ? indent + "    " : indent;
        String checkStatement = scopedLocal("mutationCheckStatement", scope, "target");
        String checkResultSet = scopedLocal("mutationCheckResultSet", scope, "target");
        String targetCount = scopedLocal("mutationTargetCount", scope, "target");
        source.append(checkIndent).append("PreparedStatement ").append(checkStatement)
                .append(" = connection.prepareStatement(\"").append(javaString(mutationTargetSql(type, mutation, !mysql)))
                .append("\");\n");
        int checkIndex = 1;
        for (MutationBinding argument : sortedMutationArguments(mutation)) {
            if (argument.key()) {
                emitMutationSetter(source, checkIndent, checkStatement, checkIndex++, scope, type, argument);
            }
        }
        source.append(checkIndent).append("ResultSet ").append(checkResultSet).append(" = ")
                .append(checkStatement).append(".executeQuery();\n")
                .append(checkIndent).append("long ").append(targetCount).append(" = 0L;\n")
                .append(checkIndent).append("if (").append(checkResultSet).append(".next()) {\n");
        source.append(checkIndent).append("    ").append(targetCount).append(" = ").append(checkResultSet)
                .append(".getLong(\"tgql_found\");\n")
                ;
        source.append(checkIndent).append("}\n")
                .append(checkIndent).append("if (").append(mysql ? "response.length() == 0 && " : "")
                .append(targetCount).append(" == 0L) {\n");
        emitMutationCodedFailure(source, mysql, checkIndent + "    ",
                "mutation '" + mutation.name() + "' target row does not exist", "rollbackExecutionErrorJson");
        source.append(checkIndent).append("}\n");
        if (mysql) {
            source.append(indent).append("}\n");
        }

        // The update is a separate effect after the two target reads, so give it its own
        // deadline fence and retain a rollback outcome for either dialect.
        emitExecutionDeadlineCheck(source, mysql, indent, true);
        emitApplicationStatementReservation(source, mysql, indent, true, rootStart);
        if (mysql) {
            source.append(indent).append("if (response.length() == 0) {\n");
        }
        String statement = scopedLocal("mutationStatement", scope, "update");
        String jdbcIndent = mysql ? indent + "    " : indent;
        source.append(jdbcIndent).append("PreparedStatement ").append(statement)
                .append(" = connection.prepareStatement(\"").append(javaString(updateSql(type, mutation, !mysql)))
                .append("\");\n");
        int index = 1;
        for (MutationBinding argument : sortedMutationArguments(mutation)) {
            if (!argument.key()) {
                emitMutationSetter(source, jdbcIndent, statement, index++, scope, type, argument);
            }
        }
        for (MutationBinding argument : sortedMutationArguments(mutation)) {
            if (argument.key()) {
                emitMutationSetter(source, jdbcIndent, statement, index++, scope, type, argument);
            }
        }
        source.append(jdbcIndent).append(statement).append(".executeUpdate();\n");
        if (mysql) {
            source.append(indent).append("}\n")
                    .append(indent).append("if (response.length() == 0) {\n");
            jdbcIndent = indent + "    ";
        }

        String objectMembers = scopedLocal("mutationObjectMembers", scope, "payload");
        source.append(jdbcIndent).append("String ").append(objectMembers).append(" = \"\";\n");
        source.append(jdbcIndent).append("if (").append(typeNameStart).append(" >= 0) {\n")
                .append(jdbcIndent).append("    ").append(objectMembers)
                .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(objectMembers)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(typeNameStart)
                .append(", \"__typename\"), DatabaseGraphqlEngine.jsonString(\"")
                .append(javaString(type.name())).append("\"));\n")
                .append(jdbcIndent).append("}\n");
        for (TitanGraphqlMutationDocument.MutationDocumentPayloadField payload : sortedPayload(mutation)) {
            MutationBinding argument = mutationArgument(mutation, payload.argument());
            TitanGraphqlFieldDocument field = fieldByColumn(type, argument.column());
            String fieldStart = scopedLocal("mutationFieldStart", scope, payload.name());
            String value = scopedLocal("mutationArgumentValue", scope, argument.name());
            source.append(jdbcIndent).append("if (").append(fieldStart).append(" >= 0) {\n");
            if (isId(argument.type())) {
                source.append(jdbcIndent).append("    String ").append(value).append("Json = DatabaseGraphqlEngine.jsonString(\"\" + ")
                        .append(value).append(");\n");
            } else if (isLong(field) || isDecimal(argument.type())) {
                source.append(jdbcIndent).append("    String ").append(value).append("Json = \"\" + ")
                        .append(value).append(";\n");
            } else if (isBoolean(argument.type())) {
                source.append(jdbcIndent).append("    String ").append(value).append("Json = ")
                        .append(value).append(" ? \"true\" : \"false\";\n");
            } else {
                source.append(jdbcIndent).append("    String ").append(value).append("Json = DatabaseGraphqlEngine.jsonString(")
                        .append(value).append(");\n");
            }
            source.append(jdbcIndent).append("    ").append(objectMembers)
                    .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(objectMembers)
                    .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(fieldStart)
                    .append(", \"").append(javaString(payload.name())).append("\"), ")
                    .append(value).append("Json);\n")
                    .append(jdbcIndent).append("}\n");
        }
        source.append(jdbcIndent).append(members).append(" = DatabaseGraphqlEngine.appendJsonMember(")
                .append(members).append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(rootStart)
                .append(", \"").append(javaString(mutation.name())).append("\"), \"{\" + ")
                .append(objectMembers).append(" + \"}\");\n");
        if (mysql) {
            source.append(indent).append("}\n");
        }
    }

    private static void emitMutationSetter(
            StringBuilder source,
            String indent,
            String statement,
            int index,
            String scope,
            TitanGraphqlTypeDocument type,
            MutationBinding argument
    ) {
        source.append(indent).append(statement).append('.').append(jdbcSetter(fieldByColumn(type, argument.column())))
                .append('(').append(index).append(", ")
                .append(scopedLocal("mutationArgumentValue", scope, argument.name())).append(");\n");
    }

    /** Emits one reviewed mutation argument coercion into the transpiled validation/execution body. */
    private static void emitMutationArgumentCoercion(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument type,
            MutationBinding argument,
            String raw,
            String value,
            String booleanRaw,
            boolean mysql,
            String indent
    ) {
        TitanGraphqlFieldDocument field = fieldByColumn(type, argument.column());
        TitanGraphqlEnumDocument enumType = enumType(document, inputNamedType(argument.type()));
        if (enumType != null) {
            source.append(indent).append("String ").append(value).append(" = DatabaseGraphqlEngine.enumArgument(")
                    .append(raw).append(");\n")
                    .append(indent).append("if (!DatabaseGraphqlEngine.enumValueIsAllowed(").append(value)
                    .append(", \"").append(javaString(String.join("|", sortedEnumValues(enumType)))).append("\")) {\n");
            emitMutationFailure(source, mysql, indent + "    ", "mutation argument '" + argument.name()
                    + "' must be a " + enumType.name() + " enum value");
            source.append(indent).append("}\n");
        } else if (isLong(field) && !isId(argument.type())) {
            source.append(indent).append("long ").append(value).append(" = DatabaseGraphqlEngine.longArgument(")
                    .append(raw).append(");\n")
                    .append(indent).append("if (DatabaseGraphqlEngine.invalidIntegerForScalar(").append(value)
                    .append(", \"").append(javaString(argument.type())).append("\")) {\n");
            emitMutationFailure(source, mysql, indent + "    ", "mutation argument '" + argument.name()
                    + "' must be an integer");
            source.append(indent).append("}\n");
        } else if (isId(argument.type())) {
            emitMutationIdArgumentCoercion(source, mysql, indent, raw, value, field,
                    "mutation argument '" + argument.name() + "'");
        } else if (isBoolean(argument.type())) {
            source.append(indent).append("int ").append(booleanRaw).append(" = DatabaseGraphqlEngine.booleanArgument(")
                    .append(raw).append(");\n")
                    .append(indent).append("if (").append(booleanRaw).append(" < 0) {\n");
            emitMutationFailure(source, mysql, indent + "    ", "mutation argument '" + argument.name()
                    + "' must be a boolean");
            source.append(indent).append("}\n")
                    .append(indent).append("boolean ").append(value).append(" = ").append(booleanRaw).append(" == 1;\n");
        } else if (isDecimal(argument.type())) {
            source.append(indent).append("if (!DatabaseGraphqlEngine.decimalArgumentIsValid(").append(raw).append(")) {\n");
            emitMutationFailure(source, mysql, indent + "    ", "mutation argument '" + argument.name()
                    + "' must be a number");
            source.append(indent).append("}\n")
                    .append(indent).append("double ").append(value).append(" = DatabaseGraphqlEngine.decimalArgument(")
                    .append(raw).append(");\n");
        } else if (isUuid(argument.type())) {
            source.append(indent).append("if (!DatabaseGraphqlEngine.uuidArgumentIsValid(").append(raw).append(")) {\n");
            emitMutationFailure(source, mysql, indent + "    ", "mutation argument '" + argument.name()
                    + "' must be a UUID");
            source.append(indent).append("}\n")
                    .append(indent).append("String ").append(value).append(" = DatabaseGraphqlEngine.stringArgument(")
                    .append(raw).append(");\n");
        } else {
            source.append(indent).append("String ").append(value).append(" = DatabaseGraphqlEngine.stringArgument(")
                    .append(raw).append(");\n")
                    .append(indent).append("if (!DatabaseGraphqlEngine.stringArgumentIsValid(").append(raw).append(")) {\n");
            emitMutationFailure(source, mysql, indent + "    ", "mutation argument '" + argument.name()
                    + "' must be a string");
            source.append(indent).append("}\n");
        }
    }

    private static void emitMutationIdArgumentCoercion(
            StringBuilder source,
            boolean mysql,
            String indent,
            String raw,
            String value,
            TitanGraphqlFieldDocument field,
            String label
    ) {
        source.append(indent).append("if (!DatabaseGraphqlEngine.idArgumentIsValid(").append(raw).append(")) {\n");
        emitMutationFailure(source, mysql, indent + "    ", label + " must be an ID");
        source.append(indent).append("}\n");
        if (isLong(field)) {
            source.append(indent).append("long ").append(value).append(" = DatabaseGraphqlEngine.integralIdArgument(")
                    .append(raw).append(");\n")
                    .append(indent).append("if (DatabaseGraphqlEngine.invalidLong(").append(value).append(")) {\n");
            emitMutationFailure(source, mysql, indent + "    ", label + " must be an integral ID");
            source.append(indent).append("}\n");
        } else {
            source.append(indent).append("String ").append(value).append(" = DatabaseGraphqlEngine.stringIdArgument(")
                    .append(raw).append(");\n");
        }
    }

    private static void emitMutationPolicyGuards(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlMutationDocument mutation,
            boolean mysql,
            String indent
    ) {
        for (String name : mutation.policies()) {
            TitanGraphqlPolicyDocument policy = policy(document, name);
            if (policy.effect() != TitanGraphqlPolicyDocument.Effect.DENY) {
                throw new IllegalArgumentException("database engine supports reject policies only: " + name);
            }
            source.append(indent).append("if (!DatabaseGraphqlEngine.roleAllowed(actorRole, \"")
                    .append(javaString(policy.expression())).append("\")) {\n");
            emitMutationCodedFailure(source, mysql, indent + "    ",
                    "mutation '" + mutation.name() + "' is not authorized", "rollbackAuthorizationErrorJson");
            source.append(indent).append("}\n");
        }
    }

    private static void emitMutationFailure(StringBuilder source, boolean mysql, String indent, String message) {
        emitMutationCodedFailure(source, mysql, indent, message, "rollbackErrorJson");
    }

    private static void emitMutationCodedFailure(
            StringBuilder source,
            boolean mysql,
            String indent,
            String message,
            String factory
    ) {
        if (mysql) {
            source.append(indent).append("response = DatabaseGraphqlEngine.").append(factory).append("(\"")
                    .append(javaString(message)).append("\");\n");
        } else {
            source.append(indent).append("return DatabaseGraphqlEngine.").append(factory).append("(\"")
                    .append(javaString(message)).append("\");\n");
        }
    }

    /**
     * Emits a deadline checkpoint in the public generated routine. PostgreSQL returns immediately;
     * MySQL assigns its terminal response, and the surrounding response guards prevent the next
     * JDBC statement from running. Mutation paths request an explicit rollback outcome because an
     * earlier serial root may already have changed state.
     */
    private static void emitExecutionDeadlineCheck(
            StringBuilder source,
            boolean mysql,
            String indent,
            boolean rollback
    ) {
        String factory = rollback ? "rollbackDeadlineExceededErrorJson" : "deadlineExceededErrorJson";
        if (mysql) {
            source.append(indent).append("if (response.length() == 0 && DatabaseGraphqlEngine.deadlineExpired(deadlineEpochMillis)) {\n")
                    .append(indent).append("    response = DatabaseGraphqlEngine.").append(factory)
                    .append("(\"request deadline exceeded during database execution\");\n")
                    .append(indent).append("}\n");
        } else {
            source.append(indent).append("if (DatabaseGraphqlEngine.deadlineExpired(deadlineEpochMillis)) return ")
                    .append("DatabaseGraphqlEngine.").append(factory)
                    .append("(\"request deadline exceeded during database execution\");\n");
        }
    }

    /** Reserves one non-identity application statement before it is prepared or executed. */
    private static void emitApplicationStatementReservation(
            StringBuilder source,
            boolean mysql,
            String indent,
            boolean rollback,
            String sourceOffset
    ) {
        String message = "request exceeds application SQL statement budget of "
                + MAX_APPLICATION_SQL_STATEMENTS_PER_REQUEST;
        if (mysql) {
            source.append(indent).append("if (response.length() == 0 && applicationSqlStatements >= ")
                    .append(MAX_APPLICATION_SQL_STATEMENTS_PER_REQUEST).append(") {\n")
                    .append(indent).append("    response = DatabaseGraphqlEngine.")
                    .append(rollback ? "rollbackResourceLimitErrorJson" : "resourceLimitErrorJsonAt")
                    .append("(\"").append(message).append("\"")
                    .append(rollback ? "" : ", query, " + sourceOffset).append(");\n")
                    .append(indent).append("}\n")
                    .append(indent).append("if (response.length() == 0) applicationSqlStatements++;\n");
        } else {
            source.append(indent).append("if (applicationSqlStatements >= ")
                    .append(MAX_APPLICATION_SQL_STATEMENTS_PER_REQUEST).append(") return DatabaseGraphqlEngine.")
                    .append(rollback ? "rollbackResourceLimitErrorJson" : "resourceLimitErrorJsonAt")
                    .append("(\"").append(message).append("\"")
                    .append(rollback ? "" : ", query, " + sourceOffset).append(");\n")
                    .append(indent).append("applicationSqlStatements++;\n");
        }
    }

    private static void emitDecodedApplicationRowReservation(
            StringBuilder source,
            boolean mysql,
            String indent,
            boolean rollback,
            String sourceOffset
    ) {
        String message = "request exceeds decoded application row budget of "
                + MAX_DECODED_APPLICATION_ROWS_PER_REQUEST;
        if (mysql) {
            source.append(indent).append("if (decodedApplicationRows >= ")
                    .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("L) {\n")
                    .append(indent).append("    response = DatabaseGraphqlEngine.")
                    .append(rollback ? "rollbackResourceLimitErrorJson" : "resourceLimitErrorJsonAt")
                    .append("(\"").append(message).append("\"")
                    .append(rollback ? "" : ", query, " + sourceOffset).append(");\n")
                    .append(indent).append("}\n")
                    .append(indent).append("if (response.length() == 0) decodedApplicationRows++;\n");
        } else {
            source.append(indent).append("if (decodedApplicationRows >= ")
                    .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("L) return DatabaseGraphqlEngine.")
                    .append(rollback ? "rollbackResourceLimitErrorJson" : "resourceLimitErrorJsonAt")
                    .append("(\"").append(message).append("\"")
                    .append(rollback ? "" : ", query, " + sourceOffset).append(");\n")
                    .append(indent).append("decodedApplicationRows++;\n");
        }
    }

    /**
     * Emits a point-root read directly into the public function. Titan's standard JDBC lowering
     * deliberately treats a Connection as entry-point infrastructure; passing it through an
     * internal helper is not a portable routine call. Keeping the query in this public body also
     * makes the typed column reads visible to the lowerer before GraphQL JSON assembly resumes.
     */
    private static void emitInlinePointRoot(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument type,
            String rootStart,
            String rootValue,
            String rootPath,
            String propagatedNull,
            String nestedRelationRows
    ) {
        List<TitanGraphqlRootDocument.RootDocumentArgument> arguments = pointArguments(root);
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("point root '" + root.name() + "' needs at least one equals argument");
        }
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : arguments) {
            if (argument.kind() != TitanGraphqlRootDocument.RootDocumentArgumentKind.EQUALS || argument.hops() != 0) {
                throw new IllegalArgumentException("point root '" + root.name()
                        + "' currently requires local equals arguments");
            }
        }
        emitPolicyGuards(source, document, root.policies(), "root '" + root.name() + "'", rootStart, "            ");
        emitPolicyGuards(source, document, type.policies(), "rows for '" + root.name() + "'", rootStart, "            ");
        emitPointSelectionValidation(source, document, root, type, rootStart, false);
        source.append("            if (DatabaseGraphqlEngine.fieldArgumentCountFromAst(requestAst, ").append(rootStart).append(") > ")
                .append(arguments.size()).append(") return DatabaseGraphqlEngine.errorJsonAt(\"root '")
                .append(javaString(root.name())).append("' has an unknown, duplicate, or malformed argument\", query, ")
                .append(rootStart).append(");\n");
        for (int index = 0; index < arguments.size(); index++) {
            TitanGraphqlRootDocument.RootDocumentArgument argument = arguments.get(index);
            TitanGraphqlFieldDocument field = fieldByColumn(type, argument.column());
            TitanGraphqlEnumDocument inputEnum = enumType(document, inputNamedType(argument.type()));
            String raw = scopedLocal("argument", root.name(), argument.name()) + index;
            String value = scopedLocal("argumentValue", root.name(), argument.name()) + index;
            String booleanRaw = scopedLocal("argumentBoolRaw", root.name(), argument.name()) + index;
            source.append("            if (!DatabaseGraphqlEngine.argumentVariableTypeIsCompatibleFromAst(query, requestAst, ")
                    .append(rootStart).append(", \"").append(javaString(argument.name())).append("\", \"")
                    .append(javaString(argument.type())).append("\")) return DatabaseGraphqlEngine.errorJsonAt(\"variable for argument '")
                    .append(javaString(argument.name())).append("' must be declared as ")
                    .append(javaString(argument.type())).append("!\", query, ").append(rootStart).append(");\n")
                    .append("            String ").append(raw).append(" = DatabaseGraphqlEngine.materializedArgumentValue(materializedArguments, ")
                    .append(rootStart).append(", \"").append(javaString(argument.name())).append("\");\n")
                    .append("            if (").append(raw).append(".length() == 0) return DatabaseGraphqlEngine.errorJsonAt(\"required argument '")
                    .append(javaString(argument.name())).append("' is missing\", query, ").append(rootStart).append(");\n")
                    .append("            if (DatabaseGraphqlEngine.argumentValueIsNull(").append(raw)
                    .append(")) return DatabaseGraphqlEngine.errorJsonAt(\"required argument '")
                    .append(javaString(argument.name())).append("' cannot be null\", query, ").append(rootStart).append(");\n");
            if (inputEnum != null) {
                source.append("            String ").append(value).append(" = DatabaseGraphqlEngine.enumArgument(")
                        .append(raw).append(");\n")
                        .append("            if (!DatabaseGraphqlEngine.enumValueIsAllowed(").append(value)
                        .append(", \"").append(javaString(String.join("|", sortedEnumValues(inputEnum))))
                        .append("\")) return DatabaseGraphqlEngine.errorJsonAt(\"argument '")
                        .append(javaString(argument.name())).append("' must be a ")
                        .append(javaString(inputEnum.name())).append(" enum value\", query, ")
                        .append(rootStart).append(");\n");
            } else if (isLong(field) && !isId(argument.type())) {
                source.append("            long ").append(value).append(" = DatabaseGraphqlEngine.longArgument(").append(raw).append(");\n")
                        .append("            if (DatabaseGraphqlEngine.invalidIntegerForScalar(").append(value)
                        .append(", \"").append(javaString(argument.type())).append("\")) return DatabaseGraphqlEngine.errorJsonAt(\"argument '")
                        .append(javaString(argument.name())).append("' must be an integer\", query, ").append(rootStart).append(");\n");
            } else if (isId(argument.type())) {
                source.append("            if (!DatabaseGraphqlEngine.idArgumentIsValid(").append(raw)
                        .append(")) return DatabaseGraphqlEngine.errorJsonAt(\"argument '")
                        .append(javaString(argument.name())).append("' must be an ID\", query, ").append(rootStart).append(");\n");
                if (isLong(field)) {
                    source.append("            long ").append(value).append(" = DatabaseGraphqlEngine.integralIdArgument(")
                            .append(raw).append(");\n");
                } else {
                    source.append("            String ").append(value).append(" = DatabaseGraphqlEngine.stringIdArgument(")
                            .append(raw).append(");\n");
                }
            } else if (isBoolean(argument.type())) {
                source.append("            int ").append(booleanRaw).append(" = DatabaseGraphqlEngine.booleanArgument(").append(raw).append(");\n")
                        .append("            if (").append(booleanRaw).append(" < 0) return DatabaseGraphqlEngine.errorJsonAt(\"argument '")
                        .append(javaString(argument.name())).append("' must be a boolean\", query, ").append(rootStart).append(");\n")
                        .append("            boolean ").append(value).append(" = ").append(booleanRaw).append(" == 1;\n");
            } else if (isDecimal(argument.type())) {
                source.append("            if (!DatabaseGraphqlEngine.decimalArgumentIsValid(").append(raw)
                        .append(")) return DatabaseGraphqlEngine.errorJsonAt(\"argument '")
                        .append(javaString(argument.name())).append("' must be a number\", query, ").append(rootStart).append(");\n")
                        .append("            double ").append(value).append(" = DatabaseGraphqlEngine.decimalArgument(")
                        .append(raw).append(");\n");
            } else if (isUuid(argument.type())) {
                source.append("            if (!DatabaseGraphqlEngine.uuidArgumentIsValid(").append(raw)
                        .append(")) return DatabaseGraphqlEngine.errorJsonAt(\"argument '")
                        .append(javaString(argument.name())).append("' must be a UUID\", query, ").append(rootStart).append(");\n")
                        .append("            String ").append(value).append(" = DatabaseGraphqlEngine.stringArgument(")
                        .append(raw).append(");\n");
            } else {
                source.append("            String ").append(value).append(" = DatabaseGraphqlEngine.stringArgument(").append(raw).append(");\n")
                        .append("            if (!DatabaseGraphqlEngine.stringArgumentIsValid(").append(raw).append(")) return DatabaseGraphqlEngine.errorJsonAt(\"argument '")
                        .append(javaString(argument.name())).append("' must be a string\", query, ").append(rootStart).append(");\n");
            }
        }
        // Do not allow argument and selection validation to consume the request deadline and
        // then begin the root's database read. A statement already in progress is bounded by the
        // frontend's JDBC timeout; this guard is the database-resident pre-execution checkpoint.
        emitExecutionDeadlineCheck(source, false, "            ", false);
        emitApplicationStatementReservation(source, false, "            ", false, rootStart);
        // Point SQL has a static one-row carrier contract. Reserving before JDBC work preserves
        // Titan's recognized next/getter transfer while still rejecting before materialization.
        emitDecodedApplicationRowReservation(source, false, "            ", false, rootStart);
        source.append("            PreparedStatement statement = connection.prepareStatement(\"")
                .append(javaString(pointSql(type, arguments))).append("\");\n");
        for (int index = 0; index < arguments.size(); index++) {
            TitanGraphqlRootDocument.RootDocumentArgument argument = arguments.get(index);
            String value = scopedLocal("argumentValue", root.name(), argument.name()) + index;
            String setter = jdbcSetter(fieldByColumn(type, argument.column()));
            source.append("            statement.").append(setter).append('(').append(index + 1)
                    .append(", ").append(value).append(");\n");
        }
        for (int index = 0; index < arguments.size(); index++) {
            TitanGraphqlRootDocument.RootDocumentArgument argument = arguments.get(index);
            String value = scopedLocal("argumentValue", root.name(), argument.name()) + index;
            String setter = jdbcSetter(fieldByColumn(type, argument.column()));
            source.append("            statement.").append(setter).append('(').append(arguments.size() + index + 1)
                    .append(", ").append(value).append(");\n");
        }
        source.append("            ResultSet resultSet = statement.executeQuery();\n")
                .append("            if (!resultSet.next()) { throw new SQLException(\"point root read produced no row\"); }\n")
                .append("            boolean rootFound = resultSet.getBoolean(\"tgql_found\");\n");
        for (TitanGraphqlFieldDocument field : sortedFields(type)) {
            emitFieldRead(source, root.name(), field);
        }
        for (TitanGraphqlRelationDocument relation : selectableRelations(type)) {
            emitRelationJoinRead(source, document, type, root.name(), relation, "resultSet", "            ");
        }
        source.append("            String ").append(rootValue).append(" = \"null\";\n")
                .append("            if (rootFound) {\n")
                .append("                String objectMembers = \"\";\n");
        emitPointObjectOutput(source, document, root, type, false, rootPath, propagatedNull, nestedRelationRows);
        source.append("                ").append(rootValue).append(" = \"{\" + objectMembers + \"}\";\n")
                .append("            }\n");
    }

    /** Emits the procedure-safe form of a point read: errors assign the final response instead of returning. */
    private static void emitInlineMySqlPointRoot(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument type,
            String rootStart,
            String rootValue,
            String rootPath,
            String propagatedNull,
            String nestedRelationRows
    ) {
        List<TitanGraphqlRootDocument.RootDocumentArgument> arguments = pointArguments(root);
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("point root '" + root.name() + "' needs at least one equals argument");
        }
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : arguments) {
            if (argument.kind() != TitanGraphqlRootDocument.RootDocumentArgumentKind.EQUALS || argument.hops() != 0) {
                throw new IllegalArgumentException("point root '" + root.name()
                        + "' currently requires local equals arguments");
            }
        }
        emitMySqlPolicyGuards(source, document, root.policies(), "root '" + root.name() + "'", rootStart, "                ");
        emitMySqlPolicyGuards(source, document, type.policies(), "rows for '" + root.name() + "'", rootStart, "                ");
        emitPointSelectionValidation(source, document, root, type, rootStart, true);
        source.append("                if (response.length() == 0 && DatabaseGraphqlEngine.fieldArgumentCountFromAst(requestAst, ")
                .append(rootStart).append(") > ").append(arguments.size()).append(") {\n")
                .append("                    response = DatabaseGraphqlEngine.errorJsonAt(\"root '")
                .append(javaString(root.name())).append("' has an unknown, duplicate, or malformed argument\", query, ")
                .append(rootStart).append(");\n")
                .append("                }\n");
        for (int index = 0; index < arguments.size(); index++) {
            TitanGraphqlRootDocument.RootDocumentArgument argument = arguments.get(index);
            TitanGraphqlFieldDocument field = fieldByColumn(type, argument.column());
            TitanGraphqlEnumDocument inputEnum = enumType(document, inputNamedType(argument.type()));
            String raw = scopedLocal("argument", root.name(), argument.name()) + index;
            String value = scopedLocal("argumentValue", root.name(), argument.name()) + index;
            String booleanRaw = scopedLocal("argumentBoolRaw", root.name(), argument.name()) + index;
            source.append("                if (response.length() == 0 && !DatabaseGraphqlEngine.argumentVariableTypeIsCompatibleFromAst(query, requestAst, ")
                    .append(rootStart).append(", \"").append(javaString(argument.name())).append("\", \"")
                    .append(javaString(argument.type())).append("\")) {\n")
                    .append("                    response = DatabaseGraphqlEngine.errorJsonAt(\"variable for argument '")
                    .append(javaString(argument.name())).append("' must be declared as ")
                    .append(javaString(argument.type())).append("!\", query, ").append(rootStart).append(");\n")
                    .append("                }\n")
                    .append("                String ").append(raw).append(" = DatabaseGraphqlEngine.materializedArgumentValue(materializedArguments, ")
                    .append(rootStart).append(", \"").append(javaString(argument.name())).append("\");\n")
                    .append("                if (response.length() == 0 && ").append(raw).append(".length() == 0) {\n")
                    .append("                    response = DatabaseGraphqlEngine.errorJsonAt(\"required argument '")
                    .append(javaString(argument.name())).append("' is missing\", query, ").append(rootStart).append(");\n")
                    .append("                }\n")
                    .append("                if (response.length() == 0 && DatabaseGraphqlEngine.argumentValueIsNull(")
                    .append(raw).append(")) {\n")
                    .append("                    response = DatabaseGraphqlEngine.errorJsonAt(\"required argument '")
                    .append(javaString(argument.name())).append("' cannot be null\", query, ").append(rootStart).append(");\n")
                    .append("                }\n");
            if (inputEnum != null) {
                source.append("                String ").append(value).append(" = DatabaseGraphqlEngine.enumArgument(")
                        .append(raw).append(");\n")
                        .append("                if (response.length() == 0 && !DatabaseGraphqlEngine.enumValueIsAllowed(")
                        .append(value).append(", \"")
                        .append(javaString(String.join("|", sortedEnumValues(inputEnum)))).append("\")) {\n")
                        .append("                    response = DatabaseGraphqlEngine.errorJsonAt(\"argument '")
                        .append(javaString(argument.name())).append("' must be a ")
                        .append(javaString(inputEnum.name())).append(" enum value\", query, ")
                        .append(rootStart).append(");\n")
                        .append("                }\n");
            } else if (isLong(field) && !isId(argument.type())) {
                source.append("                long ").append(value).append(" = DatabaseGraphqlEngine.longArgument(").append(raw).append(");\n")
                        .append("                if (response.length() == 0 && DatabaseGraphqlEngine.invalidIntegerForScalar(").append(value)
                        .append(", \"").append(javaString(argument.type())).append("\")) {\n")
                        .append("                    response = DatabaseGraphqlEngine.errorJsonAt(\"argument '")
                        .append(javaString(argument.name())).append("' must be an integer\", query, ").append(rootStart).append(");\n")
                        .append("                }\n");
            } else if (isId(argument.type())) {
                source.append("                if (response.length() == 0 && !DatabaseGraphqlEngine.idArgumentIsValid(")
                        .append(raw).append(")) {\n")
                        .append("                    response = DatabaseGraphqlEngine.errorJsonAt(\"argument '")
                        .append(javaString(argument.name())).append("' must be an ID\", query, ").append(rootStart).append(");\n")
                        .append("                }\n");
                if (isLong(field)) {
                    source.append("                long ").append(value).append(" = DatabaseGraphqlEngine.integralIdArgument(")
                            .append(raw).append(");\n");
                } else {
                    source.append("                String ").append(value).append(" = DatabaseGraphqlEngine.stringIdArgument(")
                            .append(raw).append(");\n");
                }
            } else if (isBoolean(argument.type())) {
                source.append("                int ").append(booleanRaw).append(" = DatabaseGraphqlEngine.booleanArgument(").append(raw).append(");\n")
                        .append("                if (response.length() == 0 && ").append(booleanRaw).append(" < 0) {\n")
                        .append("                    response = DatabaseGraphqlEngine.errorJsonAt(\"argument '")
                        .append(javaString(argument.name())).append("' must be a boolean\", query, ").append(rootStart).append(");\n")
                        .append("                }\n")
                        .append("                boolean ").append(value).append(" = ").append(booleanRaw).append(" == 1;\n");
            } else if (isDecimal(argument.type())) {
                source.append("                if (response.length() == 0 && !DatabaseGraphqlEngine.decimalArgumentIsValid(")
                        .append(raw).append(")) {\n")
                        .append("                    response = DatabaseGraphqlEngine.errorJsonAt(\"argument '")
                        .append(javaString(argument.name())).append("' must be a number\", query, ").append(rootStart).append(");\n")
                        .append("                }\n")
                        .append("                double ").append(value).append(" = DatabaseGraphqlEngine.decimalArgument(")
                        .append(raw).append(");\n");
            } else if (isUuid(argument.type())) {
                source.append("                if (response.length() == 0 && !DatabaseGraphqlEngine.uuidArgumentIsValid(")
                        .append(raw).append(")) {\n")
                        .append("                    response = DatabaseGraphqlEngine.errorJsonAt(\"argument '")
                        .append(javaString(argument.name())).append("' must be a UUID\", query, ").append(rootStart).append(");\n")
                        .append("                }\n")
                        .append("                String ").append(value).append(" = DatabaseGraphqlEngine.stringArgument(")
                        .append(raw).append(");\n");
            } else {
                source.append("                String ").append(value).append(" = DatabaseGraphqlEngine.stringArgument(").append(raw).append(");\n")
                        .append("                if (response.length() == 0 && !DatabaseGraphqlEngine.stringArgumentIsValid(").append(raw).append(")) {\n")
                        .append("                    response = DatabaseGraphqlEngine.errorJsonAt(\"argument '")
                        .append(javaString(argument.name())).append("' must be a string\", query, ").append(rootStart).append(");\n")
                        .append("                }\n");
            }
        }
        // The MySQL response guard below prevents JDBC work after this checkpoint has produced
        // the terminal GraphQL error.
        emitExecutionDeadlineCheck(source, true, "                ", false);
        emitApplicationStatementReservation(source, true, "                ", false, rootStart);
        emitDecodedApplicationRowReservation(source, true, "                ", false, rootStart);
        source.append("                if (response.length() == 0) {\n")
                .append("                    PreparedStatement statement = connection.prepareStatement(\"")
                .append(javaString(mySqlPointSql(type, arguments))).append("\");\n");
        for (int index = 0; index < arguments.size(); index++) {
            TitanGraphqlRootDocument.RootDocumentArgument argument = arguments.get(index);
            String value = scopedLocal("argumentValue", root.name(), argument.name()) + index;
            String setter = jdbcSetter(fieldByColumn(type, argument.column()));
            source.append("                    statement.").append(setter).append('(').append(index + 1)
                    .append(", ").append(value).append(");\n");
        }
        source.append("                    ResultSet resultSet = statement.executeQuery();\n")
                .append("                    boolean rootFound = false;\n");
        for (TitanGraphqlFieldDocument field : sortedFields(type)) {
            emitMySqlFieldDeclaration(source, root.name(), field);
        }
        for (TitanGraphqlRelationDocument relation : selectableRelations(type)) {
            emitMySqlRelationJoinDeclaration(source, document, type, root.name(), relation, "                    ");
        }
        source.append("                    if (resultSet.next()) {\n")
                .append("                        rootFound = resultSet.getBoolean(\"tgql_found\");\n");
        for (TitanGraphqlFieldDocument field : sortedFields(type)) {
            emitMySqlFieldAssignment(source, root.name(), field, "resultSet", "                        ");
        }
        for (TitanGraphqlRelationDocument relation : selectableRelations(type)) {
            emitMySqlRelationJoinAssignment(source, document, type, root.name(), relation,
                    "resultSet", "                        ");
        }
        source.append("                    }\n")
                .append("                    ").append(rootValue).append(" = \"null\";\n")
                .append("                    if (rootFound) {\n")
                .append("                        String objectMembers = \"\";\n");
        emitPointObjectOutput(source, document, root, type, true, rootPath, propagatedNull, nestedRelationRows);
        source.append("                        ").append(rootValue).append(" = \"{\" + objectMembers + \"}\";\n")
                .append("                    }\n")
                .append("                }\n");
    }

    /** Validates every planned object selection before opening the point-read statement. */
    private static void emitPointSelectionValidation(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument type,
            String rootStart,
            boolean mysql
    ) {
        String indent = mysql ? "                " : "            ";
        String nested = indent + "    ";
        String active = mysql ? " && response.length() == 0" : "";
        String plan = scopedLocal("pointSelectionPlan", root.name(), "value");
        String count = scopedLocal("pointSelectionCount", root.name(), "value");
        String index = scopedLocal("pointSelectionIndex", root.name(), "value");
        String selectionStart = scopedLocal("pointSelectionStart", root.name(), "value");
        String matched = scopedLocal("pointSelectionMatched", root.name(), "value");

        source.append(indent).append("String ").append(plan).append(" = DatabaseGraphqlEngine.rootFieldSelectionPlanFromAst(query, ")
                .append("requestAst, ").append(rootStart).append(", variablesJson, \"")
                .append(javaString(runtimeTypeConditions(document, type))).append("\");\n")
                .append(indent).append("int ").append(count).append(" = DatabaseGraphqlEngine.selectionPlanCount(")
                .append(plan).append(");\n");
        if (mysql) {
            source.append(indent).append("if (").append(count).append(" < 0) {\n")
                    .append(nested).append("response = DatabaseGraphqlEngine.errorJsonAt(\"object selection plan is invalid or exceeds the database engine budget\", query, ")
                    .append(rootStart).append(");\n")
                    .append(indent).append("}\n")
                    .append(indent).append("if (response.length() == 0 && ").append(count).append(" == 0) {\n")
                    .append(nested).append("response = DatabaseGraphqlEngine.errorJsonAt(\"object selection must contain at least one field\", query, ")
                    .append(rootStart).append(");\n")
                    .append(indent).append("}\n")
                    .append(indent).append("if (response.length() == 0 && DatabaseGraphqlEngine.selectionPlanHasDuplicateResponseKeyFromAst(query, requestAst, ")
                    .append(plan).append(")) {\n")
                    .append(nested).append("response = DatabaseGraphqlEngine.errorJsonAt(\"object selection needs unsupported field merging for a duplicate response key\", query, ")
                    .append(rootStart).append(");\n")
                    .append(indent).append("}\n");
        } else {
            source.append(indent).append("if (").append(count).append(" < 0) return DatabaseGraphqlEngine.errorJsonAt(\"object selection plan is invalid or exceeds the database engine budget\", query, ")
                    .append(rootStart).append(");\n")
                    .append(indent).append("if (").append(count).append(" == 0) return DatabaseGraphqlEngine.errorJsonAt(\"object selection must contain at least one field\", query, ")
                    .append(rootStart).append(");\n")
                    .append(indent).append("if (DatabaseGraphqlEngine.selectionPlanHasDuplicateResponseKeyFromAst(query, requestAst, ").append(plan)
                    .append(")) return DatabaseGraphqlEngine.errorJsonAt(\"object selection needs unsupported field merging for a duplicate response key\", query, ")
                    .append(rootStart).append(");\n");
        }
        source.append(indent).append("int ").append(index).append(" = 0;\n")
                .append(indent).append("while (").append(index).append(" < ").append(count).append(active).append(") {\n")
                .append(nested).append("int ").append(selectionStart).append(" = DatabaseGraphqlEngine.selectionPlanFieldStart(")
                .append(plan).append(", ").append(index).append(");\n")
                .append(nested).append("boolean ").append(matched).append(" = false;\n");
        emitTypeNameSelectionValidation(source, mysql, nested, matched, selectionStart, type.name());
        for (TitanGraphqlFieldDocument field : sortedFields(type)) {
            source.append(nested).append("if (!").append(matched).append(" && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                    .append(selectionStart).append(", \"").append(javaString(field.name())).append("\")) {\n")
                    .append(nested).append("    ").append(matched).append(" = true;\n")
                    .append(nested).append("    if (!DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, ")
                    .append(selectionStart).append(", \"\")) {\n");
            emitConnectionFailureAt(source, mysql, nested + "        ",
                    "field '" + type.name() + "." + field.name() + "' has an argument", selectionStart);
            source.append(nested).append("    }\n");
            emitScalarLeafSelectionValidation(source, mysql, nested, mysql ? "response.length() == 0 && " : "",
                    selectionStart, type.name() + "." + field.name());
            if (!field.policies().isEmpty()) {
                source.append(nested).append("    if (true").append(active).append(") {\n");
                if (mysql) {
                    emitMySqlPolicyGuards(source, document, field.policies(),
                            "field '" + type.name() + "." + field.name() + "'", selectionStart, nested + "        ");
                } else {
                    emitPolicyGuards(source, document, field.policies(),
                            "field '" + type.name() + "." + field.name() + "'", selectionStart, nested + "        ");
                }
                source.append(nested).append("    }\n");
            }
            source.append(nested).append("}\n");
        }
        for (TitanGraphqlRelationDocument relation : selectableRelations(type)) {
            source.append(nested).append("if (!").append(matched).append(" && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                    .append(selectionStart).append(", \"").append(javaString(relation.name())).append("\")) {\n")
                    .append(nested).append("    ").append(matched).append(" = true;\n");
            if (!isBasicRelayRelationConnection(document, type, relation)) {
                source.append(nested).append("    if (!DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, ")
                        .append(selectionStart).append(", \"\")) {\n");
                emitConnectionFailureAt(source, mysql, nested + "        ",
                        "relation '" + type.name() + "." + relation.name() + "' has an argument", selectionStart);
                source.append(nested).append("    }\n");
            }
            emitRelationSelectionValidation(source, document, root, type, relation, selectionStart, mysql,
                    1, relation.selectionHopBudget(), root.name(),
                    "Query|" + runtimeTypeConditionPathEntry(document, type));
            source.append(nested).append("}\n");
        }
        source.append(nested).append("if (!").append(matched).append(active).append(") {\n");
        emitConnectionFailureAt(source, mysql, nested + "    ",
                "unknown or unsupported field in '" + root.name() + "' selection", selectionStart);
        source.append(nested).append("}\n")
                .append(nested).append(index).append("++;\n")
                .append(indent).append("}\n");
    }

    /** Emits selected point-object members in request order, including distinct scalar aliases. */
    private static void emitPointObjectOutput(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument type,
            boolean mysql,
            String rootPath,
            String propagatedNull,
            String nestedRelationRows
    ) {
        String indent = mysql ? "                        " : "                ";
        String nested = indent + "    ";
        String active = mysql ? " && response.length() == 0" : "";
        String plan = scopedLocal("pointSelectionPlan", root.name(), "value");
        String count = scopedLocal("pointSelectionCount", root.name(), "value");
        String index = scopedLocal("pointOutputIndex", root.name(), "value");
        String selectionStart = scopedLocal("pointOutputStart", root.name(), "value");
        String rendered = scopedLocal("pointOutputRendered", root.name(), "value");
        source.append(indent).append("int ").append(index).append(" = 0;\n")
                .append(indent).append("while (").append(index).append(" < ").append(count).append(active)
                .append(" && !").append(propagatedNull).append(") {\n")
                .append(nested).append("int ").append(selectionStart).append(" = DatabaseGraphqlEngine.selectionPlanFieldStart(")
                .append(plan).append(", ").append(index).append(");\n")
                .append(nested).append("boolean ").append(rendered).append(" = false;\n");
        emitTypeNameOutput(source, nested, rendered, selectionStart, type.name(), "objectMembers", mysql);
        for (TitanGraphqlFieldDocument field : sortedFields(type)) {
            source.append(nested).append("if (!").append(rendered).append(" && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                    .append(selectionStart).append(", \"").append(javaString(field.name())).append("\")) {\n");
            emitFieldOutputWithNonNullPropagation(source, document, field, type.name(), selectionStart,
                    scopedLocal("fieldValue", root.name(), field.name()),
                    scopedLocal("fieldNull", root.name(), field.name()), "objectMembers", nested + "    ",
                    rootPath, propagatedNull, mysql);
            source.append(nested).append("    ").append(rendered).append(" = true;\n")
                    .append(nested).append("}\n");
        }
        for (TitanGraphqlRelationDocument relation : selectableRelations(type)) {
            source.append(nested).append("if (!").append(propagatedNull).append(" && !").append(rendered)
                    .append(" && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                    .append(selectionStart).append(", \"").append(javaString(relation.name())).append("\")) {\n");
            emitRelationOutput(source, document, root, type, relation, selectionStart, mysql, rootPath, propagatedNull,
                    root.name(), "objectMembers", 1, relation.selectionHopBudget(),
                    "Query|" + runtimeTypeConditionPathEntry(document, type),
                    nestedRelationRows);
            source.append(nested).append("    ").append(rendered).append(" = true;\n")
                    .append(nested).append("}\n");
        }
        source.append(nested).append("if (!").append(rendered).append(active).append(") {\n");
        emitInternalFailure(source, mysql, nested + "    ",
                "object selection changed after validation in '" + root.name() + "'");
        source.append(nested).append("}\n")
                .append(nested).append(index).append("++;\n")
                .append(indent).append("}\n");
    }

    /** Validates the built-in composite-type field without involving a schema resolver. */
    private static void emitTypeNameSelectionValidation(
            StringBuilder source,
            boolean mysql,
            String indent,
            String matched,
            String selectionStart,
            String typeName
    ) {
        source.append(indent).append("if (!").append(matched)
                .append(" && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(selectionStart).append(", \"__typename\")) {\n")
                .append(indent).append("    ").append(matched).append(" = true;\n")
                .append(indent).append("    if (!DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, ")
                .append(selectionStart).append(", \"\")) {\n");
        emitConnectionFailureAt(source, mysql, indent + "        ",
                "field '" + typeName + ".__typename' has an argument", selectionStart);
        source.append(indent).append("    }\n")
                .append(indent).append("    if (!DatabaseGraphqlEngine.fieldHasNoSelectionSetFromAst(query, requestAst, ")
                .append(selectionStart).append(")) {\n");
        emitConnectionFailureAt(source, mysql, indent + "        ",
                "field '" + typeName + ".__typename' cannot have a selection", selectionStart);
        source.append(indent).append("    }\n")
                .append(indent).append("}\n");
    }

    /** Emits the built-in composite-type field using the response alias selected by the request. */
    private static void emitTypeNameOutput(
            StringBuilder source,
            String indent,
            String rendered,
            String selectionStart,
            String typeName,
            String members,
            boolean mysql
    ) {
        source.append(indent).append("if (!").append(rendered)
                .append(" && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(selectionStart).append(", \"__typename\")) {\n")
                .append(indent).append("    ").append(members)
                .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(members)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(selectionStart)
                .append(", \"__typename\"), DatabaseGraphqlEngine.jsonString(\"")
                .append(javaString(typeName)).append("\"));\n");
        if (mysql) {
            source.append(indent).append("    if (response.length() == 0 && DatabaseGraphqlEngine.responseAssemblyExceeded(")
                    .append(members).append(")) { response = DatabaseGraphqlEngine.resourceLimitErrorJson(\"response exceeds the database engine character budget\"); }\n");
        } else {
            source.append(indent).append("    if (DatabaseGraphqlEngine.responseAssemblyExceeded(")
                    .append(members).append(")) return DatabaseGraphqlEngine.resourceLimitErrorJson(\"response exceeds the database engine character budget\");\n");
        }
        source.append(indent).append("    ").append(rendered).append(" = true;\n")
                .append(indent).append("}\n");
    }

    /** Emits the argument- and selection-free rule for an explicitly located {@code __typename}. */
    private static void emitTypeNameLeafValidation(
            StringBuilder source,
            boolean mysql,
            String indent,
            String guarded,
            String selectionStart,
            String typeName
    ) {
        emitConnectionFailureAtIf(
                source, mysql, indent,
                guarded + selectionStart + " >= 0 && !DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, "
                        + selectionStart + ", \"\")",
                "field '" + typeName + ".__typename' has an argument",
                selectionStart
        );
        emitConnectionFailureAtIf(
                source, mysql, indent,
                guarded + selectionStart + " >= 0 && !DatabaseGraphqlEngine.fieldHasNoSelectionSetFromAst(query, requestAst, "
                        + selectionStart + ")",
                "field '" + typeName + ".__typename' cannot have a selection",
                selectionStart
        );
    }

    /** Emits GraphQL's rule that a scalar field cannot contain a child selection. */
    private static void emitScalarLeafSelectionValidation(
            StringBuilder source,
            boolean mysql,
            String indent,
            String guarded,
            String selectionStart,
            String qualifiedFieldName
    ) {
        emitConnectionFailureAtIf(
                source, mysql, indent,
                guarded + selectionStart + " >= 0 && !DatabaseGraphqlEngine.fieldHasNoSelectionSetFromAst(query, requestAst, "
                        + selectionStart + ")",
                "field '" + qualifiedFieldName + "' cannot have a selection",
                selectionStart
        );
    }

    /** Emits both scalar leaf rules where the caller has not already checked arguments. */
    private static void emitScalarLeafValidation(
            StringBuilder source,
            boolean mysql,
            String indent,
            String guarded,
            String selectionStart,
            String qualifiedFieldName
    ) {
        emitConnectionFailureAtIf(
                source, mysql, indent,
                guarded + selectionStart + " >= 0 && !DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, "
                        + selectionStart + ", \"\")",
                "field '" + qualifiedFieldName + "' has an argument",
                selectionStart
        );
        emitScalarLeafSelectionValidation(source, mysql, indent, guarded, selectionStart, qualifiedFieldName);
    }

    /**
     * Emits the first deliberately small connection plan. It is model-specialized (not a
     * customer-specific resolver): a reviewed root with forward {@code first}/{@code after}
     * pagination, a stable scalar cursor, local equality predicates, and Boolean context
     * predicates becomes two constant SQL reads inside the installed routine. Unsupported Relay
     * shapes stay fail-closed until their complete database plans are available.
     */
    private static void emitInlineConnectionRoot(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument type,
            String rootStart,
            String rootValue,
            String propagatedNull,
            boolean mysql,
            String nestedRelationRows
    ) {
        emitInlineConnection(source, document, root, type, rootStart, rootValue, propagatedNull, mysql,
                nestedRelationRows, null, "", "\"[]\"", "", null, "", 0, Integer.MAX_VALUE, false);
    }

    /**
     * Emits the one bounded Relay executor used by both query roots and relation connections.
     * A relation supplies its already-projected parent key as a mandatory first SQL predicate;
     * roots leave that binding absent.  Keeping the two entry shapes in this emitter is important:
     * cursor coercion, selection planning, page flags, counts, and node completion must stay one
     * database implementation rather than gradually diverging into a relation-specific resolver.
     */
    private static void emitInlineConnection(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument type,
            String rootStart,
            String rootValue,
            String propagatedNull,
            boolean mysql,
            String nestedRelationRows,
            String selectionAncestorTypePath,
            String selectionParentType,
            String connectionBasePath,
            String requiredTargetColumn,
            TitanGraphqlFieldDocument requiredSourceField,
            String requiredSourceValue,
            int relationHops,
            int activeSelectionHopBudget,
            boolean validationOnly
    ) {
        String indent = mysql ? "                " : "            ";
        String nested = indent + "    ";
        String guarded = (mysql ? "response.length() == 0 && " : "") + "!" + propagatedNull + " && ";
        String publicNodeType = connectionPublicNodeType(root, type);
        String publicConnectionType = publicNodeType + "Connection";
        String publicEdgeType = publicNodeType + "Edge";
        // A generated relation can recur through a cyclic schema inside its reviewed hop budget.
        // Scope locals by the static typed ancestry, rather than only by the GraphQL field name:
        // `Customer.orderConnection` and a later `Customer.orderConnection` in the same emitted
        // method otherwise redeclare the JDBC/page locals that Titan lowers into routine locals.
        // The ancestry is compile-time metadata, never request text, so this remains static
        // schema specialization and preserves a single whole-request public entry point.
        String scope = connectionLocalScope(root, selectionAncestorTypePath)
                + (validationOnly ? "_preflight" : "");
        String connectionAncestorTypePath = selectionAncestorTypePath == null
                ? "Query|" + publicConnectionType
                // `selectionAncestorTypePath` already ends at the parent object type. Adding the
                // parent again makes fragment-aware selection planning look for an impossible
                // path such as Query|Customer|Customer|OrderConnection and silently drops edge
                // fields. The child connection is the next type segment.
                : selectionAncestorTypePath + "|" + publicConnectionType;
        String nodeAncestorTypePath = connectionAncestorTypePath + "|" + publicEdgeType;
        // These JDBC/result/JSON temporaries are part of the generated routine's lexical scope,
        // not the shared Java generator.  Derive every one from the connection scope so a Relay
        // relation selected beneath a Relay root never redeclares the parent's work locals.
        String pageStatement = scopedLocal("connectionPageStatement", scope, "value");
        String pageResultSet = scopedLocal("connectionPageResultSet", scope, "value");
        String emittedRows = scopedLocal("connectionEmittedRows", scope, "value");
        String emittedCursor = scopedLocal("connectionEmittedCursor", scope, "value");
        String edgeMembers = scopedLocal("connectionEdgeMembers", scope, "value");
        String nodeValue = scopedLocal("connectionNodeValue", scope, "value");
        String nodeMembers = scopedLocal("connectionNodeMembers", scope, "value");
        String boundaryStatement = scopedLocal("connectionBoundaryStatement", scope, "value");
        String boundaryResultSet = scopedLocal("connectionBoundaryResultSet", scope, "value");
        String savedHasPrevious = scopedLocal("connectionSavedHasPrevious", scope, "value");
        String countStatement = scopedLocal("connectionCountStatement", scope, "value");
        String countResultSet = scopedLocal("connectionCountResultSet", scope, "value");
        String totalCountValue = scopedLocal("connectionTotalCountValue", scope, "value");
        String pageInfoMembers = scopedLocal("connectionPageInfoMembers", scope, "value");
        String pageInfoValue = scopedLocal("connectionPageInfoValue", scope, "value");
        TitanGraphqlRootDocument.RootDocumentPagination pagination = root.pagination();
        TitanGraphqlRootDocument.Cursor cursor = pagination.cursor();
        String cursorDirection = cursor.direction() == TitanGraphqlRootDocument.RootDocumentSortDirection.DESC
                ? "DESC" : "ASC";
        String cursorTieBreaker = cursor.tieBreaker().isBlank() ? "id" : cursor.tieBreaker();
        List<TitanGraphqlRootDocument.RootDocumentArgument> connectionArguments = root.arguments();
        List<TitanGraphqlContextFilterDocument> connectionContextFilters = root.contextFilters().stream()
                .map(name -> contextFilter(document, name)).toList();
        List<TitanGraphqlFilterLayout.Binding> connectionFilters = supportedConnectionFilters(document, type, root);
        List<TitanGraphqlRootDocument.RootDocumentSortPath> customOrders =
                supportedConnectionCustomOrders(document, type, root);
        String allowedConnectionArguments = connectionAllowedArgumentNames(document, type, root);

        if (!mysql) {
            source.append(indent).append("String ").append(rootValue).append(" = \"\";\n");
        }
        source.append(indent).append("if (").append(propagatedNull).append(") ").append(rootValue)
                .append(" = \"null\";\n");
        // Relation selections are preflighted before their parent statement opens. Re-emitting the
        // derived connection contract's policies here is redundant and, on MySQL's assignment-style
        // error path, could overwrite the canonical relation diagnostic with a synthetic root one.
        if (selectionAncestorTypePath == null) {
            if (mysql) {
                emitMySqlPolicyGuards(source, document, root.policies(), "root '" + root.name() + "'", rootStart, indent);
                emitMySqlPolicyGuards(source, document, type.policies(), "rows for '" + root.name() + "'", rootStart, indent);
            } else {
                emitPolicyGuards(source, document, root.policies(), "root '" + root.name() + "'", rootStart, indent);
                emitPolicyGuards(source, document, type.policies(), "rows for '" + root.name() + "'", rootStart, indent);
            }
        }

        String edgesStart = scopedLocal("connectionEdgesStart", scope, "value");
        String totalCountStart = scopedLocal("connectionTotalCountStart", scope, "value");
        String totalCountSelectionCount = scopedLocal("connectionTotalCountSelectionCount", scope, "value");
        String totalCountSelectionIndex = scopedLocal("connectionTotalCountSelectionIndex", scope, "value");
        String totalCountSelectionFieldStart = scopedLocal("connectionTotalCountSelectionFieldStart", scope, "value");
        String pageInfoStart = scopedLocal("connectionPageInfoStart", scope, "value");
        String connectionTypeNameStart = scopedLocal("connectionTypeNameStart", scope, "value");
        String connectionTypeNameSelectionCount = scopedLocal("connectionTypeNameSelectionCount", scope, "value");
        String connectionTypeNameSelectionIndex = scopedLocal("connectionTypeNameSelectionIndex", scope, "value");
        String connectionTypeNameSelectionFieldStart = scopedLocal("connectionTypeNameSelectionFieldStart", scope, "value");
        String connectionSelectionPlan = scopedLocal("connectionSelectionPlan", scope, "value");
        String connectionSelectionCount = scopedLocal("connectionSelectionCount", scope, "value");
        String selectedCount = scopedLocal("connectionSelectedCount", scope, "value");
        String edgeCursorStart = scopedLocal("connectionEdgeCursorStart", scope, "value");
        String edgeCursorSelectionCount = scopedLocal("connectionEdgeCursorSelectionCount", scope, "value");
        String nodeStart = scopedLocal("connectionNodeStart", scope, "value");
        String edgeTypeNameStart = scopedLocal("connectionEdgeTypeNameStart", scope, "value");
        String edgeTypeNameSelectionCount = scopedLocal("connectionEdgeTypeNameSelectionCount", scope, "value");
        String edgesSelectionPlan = scopedLocal("connectionEdgesSelectionPlan", scope, "value");
        String edgesSelectionCount = scopedLocal("connectionEdgesSelectionCount", scope, "value");
        String edgesSelectedCount = scopedLocal("connectionEdgesSelectedCount", scope, "value");
        String edgesSelectionIndex = scopedLocal("connectionEdgesSelectionIndex", scope, "value");
        String edgesSelectionFieldStart = scopedLocal("connectionEdgesSelectionFieldStart", scope, "value");
        String connectionPath = scopedLocal("connectionExecutionPath", scope, "value");
        String hasNextStart = scopedLocal("connectionHasNextPageStart", scope, "value");
        String hasPreviousStart = scopedLocal("connectionHasPreviousPageStart", scope, "value");
        String startCursorStart = scopedLocal("connectionStartCursorStart", scope, "value");
        String endCursorStart = scopedLocal("connectionEndCursorStart", scope, "value");
        String pageInfoTypeNameStart = scopedLocal("connectionPageInfoTypeNameStart", scope, "value");
        String pageInfoSelectionPlan = scopedLocal("connectionPageInfoSelectionPlan", scope, "value");
        String pageInfoSelectionCount = scopedLocal("connectionPageInfoSelectionCount", scope, "value");
        String pageInfoSelectedCount = scopedLocal("connectionPageInfoSelectedCount", scope, "value");
        String pageInfoSelectionIndex = scopedLocal("connectionPageInfoSelectionIndex", scope, "value");
        String pageInfoSelectionFieldStart = scopedLocal("connectionPageInfoSelectionFieldStart", scope, "value");

        source.append(indent).append("String ").append(connectionPath)
                .append(" = DatabaseGraphqlEngine.appendExecutionPath(").append(connectionBasePath).append(", ")
                .append("DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(rootStart)
                .append(", \"").append(javaString(root.name())).append("\"));\n");

        source.append(indent).append("String ").append(connectionSelectionPlan).append(" = ");
        if (selectionAncestorTypePath == null) {
            source.append("DatabaseGraphqlEngine.rootFieldSelectionPlanFromAst(query, requestAst, ")
                    .append(rootStart).append(", variablesJson, \"").append(javaString(publicConnectionType))
                    .append("\");\n");
        } else {
            source.append("DatabaseGraphqlEngine.fieldSelectionPlanFromAst(query, requestAst, ")
                    .append(rootStart).append(", variablesJson, \"").append(javaString(selectionParentType))
                    .append("\", \"").append(javaString(publicConnectionType)).append("\", \"")
                    .append(javaString(selectionAncestorTypePath)).append("\");\n");
        }
        source.append(indent).append("int ").append(connectionSelectionCount)
                .append(" = DatabaseGraphqlEngine.selectionPlanCount(").append(connectionSelectionPlan).append(");\n")
                .append(indent).append("int ").append(edgesStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStartForFieldFromAst(query, requestAst, ")
                .append(connectionSelectionPlan).append(", \"edges\");\n")
                .append(indent).append("int ").append(totalCountStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFirstFieldStartForFieldFromAst(query, requestAst, ")
                .append(connectionSelectionPlan).append(", \"totalCount\");\n")
                .append(indent).append("int ").append(totalCountSelectionCount)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldCountForFieldFromAst(query, requestAst, ")
                .append(connectionSelectionPlan).append(", \"totalCount\");\n")
                .append(indent).append("int ").append(pageInfoStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStartForFieldFromAst(query, requestAst, ")
                .append(connectionSelectionPlan).append(", \"pageInfo\");\n")
                .append(indent).append("int ").append(connectionTypeNameStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFirstFieldStartForFieldFromAst(query, requestAst, ")
                .append(connectionSelectionPlan).append(", \"__typename\");\n")
                .append(indent).append("int ").append(connectionTypeNameSelectionCount)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldCountForFieldFromAst(query, requestAst, ")
                .append(connectionSelectionPlan).append(", \"__typename\");\n")
                .append(indent).append("int ").append(selectedCount).append(" = 0;\n")
                .append(indent).append("if (").append(edgesStart).append(" >= 0) ").append(selectedCount).append("++;\n")
                .append(indent).append(selectedCount).append(" += ").append(totalCountSelectionCount).append(";\n")
                .append(indent).append("if (").append(pageInfoStart).append(" >= 0) ").append(selectedCount).append("++;\n")
                .append(indent).append(selectedCount).append(" += ").append(connectionTypeNameSelectionCount).append(";\n");
        emitConnectionFailureIf(
                source,
                mysql,
                indent,
                guarded + "(" + connectionSelectionCount + " < 0 || " + selectedCount + " != " + connectionSelectionCount + ")",
                "unknown or unsupported field in connection root '" + root.name() + "' selection"
        );
        emitConnectionFailureAtIf(
                source,
                mysql,
                indent,
                guarded + "DatabaseGraphqlEngine.selectionPlanHasDuplicateResponseKeyFromAst(query, requestAst, "
                        + connectionSelectionPlan + ")",
                "connection root '" + root.name() + "' has conflicting fields for one response key",
                rootStart
        );
        emitConnectionFailureIf(
                source,
                mysql,
                indent,
                guarded + "!DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, " + rootStart + ", \""
                        + javaString(allowedConnectionArguments) + "\")",
                "connection root '" + root.name() + "' has an unknown, duplicate, or malformed argument"
        );
        source.append(indent).append("int ").append(connectionTypeNameSelectionIndex).append(" = 0;\n")
                .append(indent).append("while (").append(connectionTypeNameSelectionIndex).append(" < ")
                .append(connectionSelectionCount).append(") {\n")
                .append(nested).append("int ").append(connectionTypeNameSelectionFieldStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStart(").append(connectionSelectionPlan)
                .append(", ").append(connectionTypeNameSelectionIndex).append(");\n")
                .append(nested).append("if (").append(guarded)
                .append("DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(connectionTypeNameSelectionFieldStart).append(", \"__typename\")) {\n");
        emitTypeNameLeafValidation(source, mysql, nested + "    ", mysql ? "response.length() == 0 && " : "",
                connectionTypeNameSelectionFieldStart, publicConnectionType);
        source.append(nested).append("}\n")
                .append(nested).append(connectionTypeNameSelectionIndex).append("++;\n")
                .append(indent).append("}\n");

        for (TitanGraphqlRootDocument.RootDocumentArgument argument : connectionArguments) {
            String argumentScope = scope + "_" + argument.name();
            String raw = scopedLocal("connectionArgumentRaw", argumentScope, "value");
            String present = scopedLocal("connectionArgumentPresent", argumentScope, "value");
            String value = scopedLocal("connectionArgumentValue", argumentScope, "value");
            String supplied = scopedLocal("suppliedConnectionArgument", argumentScope, "value");
            TitanGraphqlEnumDocument inputEnum = enumType(document, inputNamedType(argument.type()));
            source.append(indent).append("String ").append(raw).append(" = DatabaseGraphqlEngine.materializedArgumentValue(materializedArguments, ")
                    .append(rootStart).append(", \"").append(javaString(argument.name())).append("\");\n")
                    .append(indent).append("boolean ").append(present).append(" = false;\n")
                    .append(indent).append(inputEnum == null ? "long " : "String ").append(value)
                    .append(inputEnum == null ? " = 0L;\n" : " = \"\";\n")
                    .append(indent).append("if (").append(guarded).append(raw).append(".length() != 0) {\n")
                    .append(nested).append("if (!DatabaseGraphqlEngine.argumentVariableTypeIsCompatibleWithNullableArgumentFromAst(query, requestAst, ")
                    .append(rootStart).append(", \"").append(javaString(argument.name())).append("\", \"")
                    .append(javaString(argument.type())).append("\")) {").append("\n");
            emitConnectionFailure(source, mysql, nested + "    ", "variable for connection argument '"
                    + argument.name() + "' must be declared as " + argument.type() + " or " + argument.type() + "!");
            source.append(nested).append("}\n");
            if (inputEnum != null) {
                source.append(nested).append("String ").append(supplied)
                        .append(" = DatabaseGraphqlEngine.enumArgument(").append(raw).append(");\n")
                        .append(nested).append("if (").append(mysql ? "response.length() == 0 && " : "")
                        .append("!DatabaseGraphqlEngine.enumValueIsAllowed(").append(supplied).append(", \"")
                        .append(javaString(String.join("|", sortedEnumValues(inputEnum)))).append("\")) {\n");
                emitConnectionFailure(source, mysql, nested + "    ", "connection argument '"
                        + argument.name() + "' must be a " + inputEnum.name() + " enum value");
            } else {
                source.append(nested).append("long ").append(supplied).append(" = DatabaseGraphqlEngine.longArgument(")
                        .append(raw).append(");\n")
                        .append(nested).append("if (").append(mysql ? "response.length() == 0 && " : "")
                        .append("DatabaseGraphqlEngine.invalidIntegerForScalar(").append(supplied).append(", \"")
                        .append(javaString(argument.type())).append("\")) {").append("\n");
                emitConnectionFailure(source, mysql, nested + "    ", "connection argument '"
                        + argument.name() + "' must be an integer");
            }
            source.append(nested).append("}\n")
                    .append(nested).append("if (").append(mysql ? "response.length() == 0" : "true").append(") {")
                    .append("\n").append(nested).append("    ").append(present).append(" = true;\n")
                    .append(nested).append("    ").append(value).append(" = ").append(supplied).append(";\n")
                    .append(nested).append("}\n")
                    .append(indent).append("}\n");
        }
        emitConnectionFilterBindings(
                source, document, root, scope, rootStart, mysql, indent, nested, guarded, connectionFilters,
                selectionAncestorTypePath == null);
        for (TitanGraphqlContextFilterDocument filter : connectionContextFilters) {
            String filterScope = scope + "_" + filter.name();
            String enabled = scopedLocal("connectionContextFilterEnabled", filterScope, "value");
            String value = scopedLocal("connectionContextFilterValue", filterScope, "value");
            source.append(indent).append("boolean ").append(enabled)
                    .append(" = DatabaseGraphqlEngine.trustedContextStringArrayContains(trustedContextJson, \"enabledContextFilters\", \"")
                    .append(javaString(filter.name())).append("\");\n")
                    .append(indent).append(filter.operator() == TitanGraphqlContextFilterDocument.Operator.BOOLEAN_EQUALS
                            ? "int " : "String ").append(value)
                    .append(filter.operator() == TitanGraphqlContextFilterDocument.Operator.BOOLEAN_EQUALS
                            ? " = DatabaseGraphqlEngine.trustedContextBoolean(trustedContextJson, \""
                            : " = DatabaseGraphqlEngine.trustedContextStringValue(trustedContextJson, \"")
                    .append(javaString(filter.contextKey())).append("\");\n");
        }

        source.append(indent).append("int ").append(edgeCursorStart).append(" = -1;\n")
                .append(indent).append("int ").append(edgeCursorSelectionCount).append(" = 0;\n")
                .append(indent).append("int ").append(nodeStart).append(" = -1;\n")
                .append(indent).append("int ").append(edgeTypeNameStart).append(" = -1;\n")
                .append(indent).append("int ").append(edgeTypeNameSelectionCount).append(" = 0;\n")
                .append(indent).append("String ").append(edgesSelectionPlan).append(" = \"\";\n")
                .append(indent).append("int ").append(edgesSelectionCount).append(" = 0;\n")
                .append(indent).append("int ").append(edgesSelectedCount).append(" = 0;\n");
        source.append(indent).append("int ").append(hasNextStart).append(" = -1;\n")
                .append(indent).append("int ").append(hasPreviousStart).append(" = -1;\n")
                .append(indent).append("int ").append(startCursorStart).append(" = -1;\n")
                .append(indent).append("int ").append(endCursorStart).append(" = -1;\n")
                .append(indent).append("int ").append(pageInfoTypeNameStart).append(" = -1;\n")
                .append(indent).append("String ").append(pageInfoSelectionPlan).append(" = \"\";\n")
                .append(indent).append("int ").append(pageInfoSelectionCount).append(" = 0;\n")
                .append(indent).append("int ").append(pageInfoSelectedCount).append(" = 0;\n");

        source.append(indent).append("if (").append(guarded).append(edgesStart).append(" >= 0) {\n")
                .append(nested).append("if (!DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, ").append(edgesStart)
                .append(", \"\")) {").append("\n");
        emitConnectionFailure(source, mysql, nested + "    ", "connection field '" + root.name() + ".edges' has an argument");
        source.append(nested).append("}\n")
                .append(nested).append(edgesSelectionPlan)
                .append(" = DatabaseGraphqlEngine.fieldSelectionPlanFromAst(query, requestAst, ")
                .append(edgesStart).append(", variablesJson, \"").append(javaString(publicConnectionType))
                .append("\", \"").append(javaString(publicEdgeType)).append("\", \"")
                .append(javaString(connectionAncestorTypePath)).append("\");\n")
                .append(nested).append(edgesSelectionCount).append(" = DatabaseGraphqlEngine.selectionPlanCount(")
                .append(edgesSelectionPlan).append(");\n");
        emitConnectionFailureAtIf(
                source,
                mysql,
                nested,
                (mysql ? "response.length() == 0 && " : "")
                        + "DatabaseGraphqlEngine.selectionPlanHasDuplicateResponseKeyFromAst(query, requestAst, "
                        + edgesSelectionPlan + ")",
                "connection field '" + root.name() + ".edges' has conflicting fields for one response key",
                edgesStart
        );
        source.append(nested).append(edgeCursorStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFirstFieldStartForFieldFromAst(query, requestAst, ")
                .append(edgesSelectionPlan).append(", \"cursor\");\n")
                .append(nested).append(edgeCursorSelectionCount)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldCountForFieldFromAst(query, requestAst, ")
                .append(edgesSelectionPlan).append(", \"cursor\");\n")
                .append(nested).append(nodeStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStartForFieldFromAst(query, requestAst, ")
                .append(edgesSelectionPlan).append(", \"node\");\n")
                .append(nested).append(edgeTypeNameStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFirstFieldStartForFieldFromAst(query, requestAst, ")
                .append(edgesSelectionPlan).append(", \"__typename\");\n")
                .append(nested).append(edgeTypeNameSelectionCount)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldCountForFieldFromAst(query, requestAst, ")
                .append(edgesSelectionPlan).append(", \"__typename\");\n")
                .append(nested).append(edgesSelectedCount).append(" += ").append(edgeCursorSelectionCount).append(";\n")
                .append(nested).append("if (").append(nodeStart).append(" >= 0) ").append(edgesSelectedCount).append("++;\n")
                .append(nested).append(edgesSelectedCount).append(" += ").append(edgeTypeNameSelectionCount).append(";\n")
                .append(nested).append("if (").append(edgesSelectionCount).append(" < 0 || ").append(edgesSelectedCount)
                .append(" == 0 || ").append(edgesSelectedCount).append(" != ").append(edgesSelectionCount)
                .append(") {").append("\n");
        emitConnectionFailure(source, mysql, nested + "    ", "connection field '" + root.name() + ".edges' supports only cursor and node");
        source.append(nested).append("}\n")
                .append(nested).append("int ").append(edgesSelectionIndex).append(" = 0;\n")
                .append(nested).append("while (").append(edgesSelectionIndex).append(" < ").append(edgesSelectionCount).append(") {\n")
                .append(nested).append("    int ").append(edgesSelectionFieldStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStart(").append(edgesSelectionPlan)
                .append(", ").append(edgesSelectionIndex).append(");\n")
                .append(nested).append("    boolean edgeKnownField = DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(edgesSelectionFieldStart).append(", \"cursor\") || DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(edgesSelectionFieldStart).append(", \"node\") || DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(edgesSelectionFieldStart).append(", \"__typename\");\n")
                .append(nested).append("    if (!edgeKnownField) {\n");
        emitConnectionFailureAt(source, mysql, nested + "        ", "connection field '" + root.name()
                + ".edges' supports only cursor and node", edgesSelectionFieldStart);
        source.append(nested).append("    }\n")
                .append(nested).append("    boolean edgeScalarField = DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(edgesSelectionFieldStart).append(", \"cursor\") || DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(edgesSelectionFieldStart).append(", \"__typename\");\n")
                .append(nested).append("    if (").append(mysql ? "response.length() == 0 && " : "")
                .append("edgeScalarField && !DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, ")
                .append(edgesSelectionFieldStart).append(", \"\")) {\n");
        emitConnectionFailureAt(source, mysql, nested + "        ", "connection edge scalar field has an argument",
                edgesSelectionFieldStart);
        source.append(nested).append("    }\n")
                .append(nested).append("    if (").append(mysql ? "response.length() == 0 && " : "")
                .append("edgeScalarField && !DatabaseGraphqlEngine.fieldHasNoSelectionSetFromAst(query, requestAst, ")
                .append(edgesSelectionFieldStart).append(")) {\n");
        emitConnectionFailureAt(source, mysql, nested + "        ", "connection edge scalar field cannot have a selection",
                edgesSelectionFieldStart);
        source.append(nested).append("    }\n")
                .append(nested).append("    ").append(edgesSelectionIndex).append("++;\n")
                .append(nested).append("}\n")
                .append(indent).append("}\n");
        emitConnectionNodeSelectionValidation(source, document, root, type, mysql, nodeStart, scope, indent,
                nodeAncestorTypePath, relationHops, activeSelectionHopBudget);

        source.append(indent).append("int ").append(totalCountSelectionIndex).append(" = 0;\n")
                .append(indent).append("while (").append(totalCountSelectionIndex).append(" < ")
                .append(connectionSelectionCount).append(") {\n")
                .append(nested).append("int ").append(totalCountSelectionFieldStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStart(").append(connectionSelectionPlan)
                .append(", ").append(totalCountSelectionIndex).append(");\n")
                .append(nested).append("if (").append(guarded)
                .append("DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(totalCountSelectionFieldStart).append(", \"totalCount\")) {\n")
                .append(nested).append("    if (!DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, ")
                .append(totalCountSelectionFieldStart).append(", \"\")) {\n");
        emitConnectionFailureAt(source, mysql, nested + "        ", "connection field '" + root.name()
                + ".totalCount' has an argument", totalCountSelectionFieldStart);
        source.append(nested).append("    }\n");
        emitScalarLeafSelectionValidation(source, mysql, nested + "    ", mysql ? "response.length() == 0 && " : "",
                totalCountSelectionFieldStart, publicConnectionType + ".totalCount");
        source.append(nested).append("}\n")
                .append(nested).append(totalCountSelectionIndex).append("++;\n")
                .append(indent).append("}\n");
        source.append(indent).append("if (").append(guarded).append(pageInfoStart).append(" >= 0) {\n")
                .append(nested).append("if (!DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, ").append(pageInfoStart).append(", \"\")) {").append("\n");
        emitConnectionFailure(source, mysql, nested + "    ", "connection field '" + root.name() + ".pageInfo' has an argument");
        source.append(nested).append("}\n")
                .append(nested).append(pageInfoSelectionPlan)
                .append(" = DatabaseGraphqlEngine.fieldSelectionPlanFromAst(query, requestAst, ")
                .append(pageInfoStart).append(", variablesJson, \"").append(javaString(publicConnectionType))
                .append("\", \"PageInfo\", \"")
                .append(javaString(connectionAncestorTypePath)).append("\");\n")
                .append(nested).append(pageInfoSelectionCount).append(" = DatabaseGraphqlEngine.selectionPlanCount(")
                .append(pageInfoSelectionPlan).append(");\n");
        emitConnectionFailureAtIf(
                source,
                mysql,
                nested,
                (mysql ? "response.length() == 0 && " : "")
                        + "DatabaseGraphqlEngine.selectionPlanHasDuplicateResponseKeyFromAst(query, requestAst, "
                        + pageInfoSelectionPlan + ")",
                "connection field '" + root.name() + ".pageInfo' has conflicting fields for one response key",
                pageInfoStart
        );
        source.append(nested).append(hasNextStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFirstFieldStartForFieldFromAst(query, requestAst, ")
                .append(pageInfoSelectionPlan).append(", \"hasNextPage\");\n")
                .append(nested).append(hasPreviousStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFirstFieldStartForFieldFromAst(query, requestAst, ")
                .append(pageInfoSelectionPlan).append(", \"hasPreviousPage\");\n")
                .append(nested).append(startCursorStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFirstFieldStartForFieldFromAst(query, requestAst, ")
                .append(pageInfoSelectionPlan).append(", \"startCursor\");\n")
                .append(nested).append(endCursorStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFirstFieldStartForFieldFromAst(query, requestAst, ")
                .append(pageInfoSelectionPlan).append(", \"endCursor\");\n")
                .append(nested).append(pageInfoTypeNameStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFirstFieldStartForFieldFromAst(query, requestAst, ")
                .append(pageInfoSelectionPlan).append(", \"__typename\");\n")
                .append(nested).append(pageInfoSelectedCount).append(" = ").append(pageInfoSelectionCount).append(";\n")
                .append(nested).append("if (").append(mysql ? "response.length() == 0 && " : "")
                .append("(").append(pageInfoSelectionCount).append(" <= 0 || ").append(pageInfoSelectedCount)
                .append(" != ").append(pageInfoSelectionCount).append(")) {").append("\n");
        emitConnectionFailure(source, mysql, nested + "    ", "unknown or unsupported field in connection pageInfo");
        source.append(nested).append("}\n");
        source.append(nested).append("int ").append(pageInfoSelectionIndex).append(" = 0;\n")
                .append(nested).append("while (").append(pageInfoSelectionIndex).append(" < ")
                .append(pageInfoSelectionCount).append(") {\n")
                .append(nested).append("    int ").append(pageInfoSelectionFieldStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStart(").append(pageInfoSelectionPlan)
                .append(", ").append(pageInfoSelectionIndex).append(");\n")
                .append(nested).append("    boolean pageInfoKnownField = DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(pageInfoSelectionFieldStart).append(", \"hasNextPage\") || ")
                .append("DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ").append(pageInfoSelectionFieldStart)
                .append(", \"hasPreviousPage\") || DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(pageInfoSelectionFieldStart).append(", \"startCursor\") || ")
                .append("DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ").append(pageInfoSelectionFieldStart)
                .append(", \"endCursor\") || DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(pageInfoSelectionFieldStart).append(", \"__typename\");\n")
                .append(nested).append("    if (!pageInfoKnownField) {\n");
        emitConnectionFailureAt(source, mysql, nested + "        ", "unknown or unsupported field in connection pageInfo",
                pageInfoSelectionFieldStart);
        source.append(nested).append("    }\n")
                .append(nested).append("    if (").append(mysql ? "response.length() == 0 && " : "")
                .append("pageInfoKnownField && !DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, ")
                .append(pageInfoSelectionFieldStart).append(", \"\")) {\n");
        emitConnectionFailureAt(source, mysql, nested + "        ", "connection pageInfo scalar field has an argument",
                pageInfoSelectionFieldStart);
        source.append(nested).append("    }\n")
                .append(nested).append("    if (").append(mysql ? "response.length() == 0 && " : "")
                .append("pageInfoKnownField && !DatabaseGraphqlEngine.fieldHasNoSelectionSetFromAst(query, requestAst, ")
                .append(pageInfoSelectionFieldStart).append(")) {\n");
        emitConnectionFailureAt(source, mysql, nested + "        ", "connection pageInfo scalar field cannot have a selection",
                pageInfoSelectionFieldStart);
        source.append(nested).append("    }\n")
                .append(nested).append("    ").append(pageInfoSelectionIndex).append("++;\n")
                .append(nested).append("}\n");
        source.append(nested)
                .append(indent).append("}\n");

        String firstRaw = scopedLocal("connectionFirstRaw", scope, "value");
        String lastRaw = scopedLocal("connectionLastRaw", scope, "value");
        String suppliedFirst = scopedLocal("suppliedConnectionFirst", scope, "value");
        String suppliedLast = scopedLocal("suppliedConnectionLast", scope, "value");
        String pageSize = scopedLocal("connectionPageSize", scope, "value");
        String backward = scopedLocal("connectionBackward", scope, "value");
        String afterRaw = scopedLocal("connectionAfterRaw", scope, "value");
        String afterCursor = scopedLocal("connectionAfterCursor", scope, "value");
        String afterPresent = scopedLocal("connectionAfterPresent", scope, "value");
        String afterValue = scopedLocal("connectionAfterValue", scope, "value");
        String customOrder = scopedLocal("connectionCustomOrder", scope, "value");
        String customOrderPath = scopedLocal("connectionCustomOrderPath", scope, "value");
        String customOrderDescending = scopedLocal("connectionCustomOrderDescending", scope, "value");
        String customAfterStringValue = scopedLocal("connectionCustomAfterStringValue", scope, "value");
        String customAfterLongValue = scopedLocal("connectionCustomAfterLongValue", scope, "value");
        String customAfterTieValue = scopedLocal("connectionCustomAfterTieValue", scope, "value");
        String beforeRaw = scopedLocal("connectionBeforeRaw", scope, "value");
        String beforeCursor = scopedLocal("connectionBeforeCursor", scope, "value");
        String beforePresent = scopedLocal("connectionBeforePresent", scope, "value");
        String beforeValue = scopedLocal("connectionBeforeValue", scope, "value");
        String customBeforeStringValue = scopedLocal("connectionCustomBeforeStringValue", scope, "value");
        String customBeforeLongValue = scopedLocal("connectionCustomBeforeLongValue", scope, "value");
        String customBeforeTieValue = scopedLocal("connectionCustomBeforeTieValue", scope, "value");
        source.append(indent).append("String ").append(firstRaw).append(" = DatabaseGraphqlEngine.materializedArgumentValue(materializedArguments, ")
                .append(rootStart).append(", \"first\");\n")
                .append(indent).append("String ").append(lastRaw).append(" = DatabaseGraphqlEngine.materializedArgumentValue(materializedArguments, ")
                .append(rootStart).append(", \"last\");\n")
                .append(indent).append("long ").append(pageSize).append(" = ").append(pagination.defaultPageSize()).append("L;\n")
                .append(indent).append("boolean ").append(backward).append(" = false;\n")
                .append(indent).append("if (").append(guarded).append(firstRaw).append(".length() != 0 && !DatabaseGraphqlEngine.argumentValueIsNull(").append(firstRaw).append(")) {\n")
                .append(nested).append("if (!DatabaseGraphqlEngine.argumentVariableTypeIsCompatibleWithNullableArgumentFromAst(query, requestAst, ").append(rootStart)
                .append(", \"first\", \"Int\")) {").append("\n");
        emitConnectionFailure(source, mysql, nested + "    ", "variable for connection argument 'first' must be declared as Int or Int!");
        source.append(nested).append("}\n")
                .append(nested).append("long ").append(suppliedFirst)
                .append(" = DatabaseGraphqlEngine.longArgument(").append(firstRaw).append(");\n")
                .append(nested).append("if (").append(mysql ? "response.length() == 0 && " : "")
                .append("DatabaseGraphqlEngine.invalidIntegerForScalar(").append(suppliedFirst)
                .append(", \"Int\")) {").append("\n");
        emitConnectionFailure(source, mysql, nested + "    ", "connection argument 'first' must be an integer");
        source.append(nested).append("}\n")
                .append(nested).append("if (").append(mysql ? "response.length() == 0" : "true").append(") ")
                .append(pageSize).append(" = ").append(suppliedFirst).append(";\n")
                .append(indent).append("}\n");
        source.append(indent).append("if (").append(guarded).append(firstRaw).append(".length() != 0 && !DatabaseGraphqlEngine.argumentValueIsNull(").append(firstRaw).append(") && ")
                .append(lastRaw).append(".length() != 0 && !DatabaseGraphqlEngine.argumentValueIsNull(").append(lastRaw).append(")) {\n");
        emitConnectionFailure(source, mysql, nested,
                "connection arguments 'first' and 'last' cannot be used together");
        source.append(indent).append("}\n")
                .append(indent).append("if (").append(guarded).append(lastRaw).append(".length() != 0 && !DatabaseGraphqlEngine.argumentValueIsNull(").append(lastRaw).append(")) {\n")
                .append(nested).append("if (!DatabaseGraphqlEngine.argumentVariableTypeIsCompatibleWithNullableArgumentFromAst(query, requestAst, ")
                .append(rootStart).append(", \"last\", \"Int\")) {\n");
        emitConnectionFailure(source, mysql, nested + "    ", "variable for connection argument 'last' must be declared as Int or Int!");
        source.append(nested).append("}\n")
                .append(nested).append("long ").append(suppliedLast)
                .append(" = DatabaseGraphqlEngine.longArgument(").append(lastRaw).append(");\n")
                .append(nested).append("if (").append(mysql ? "response.length() == 0 && " : "")
                .append("DatabaseGraphqlEngine.invalidIntegerForScalar(").append(suppliedLast)
                .append(", \"Int\")) {\n");
        emitConnectionFailure(source, mysql, nested + "    ", "connection argument 'last' must be an integer");
        source.append(nested).append("}\n")
                .append(nested).append("if (").append(mysql ? "response.length() == 0" : "true").append(") {\n")
                .append(nested).append("    ").append(pageSize).append(" = ").append(suppliedLast).append(";\n")
                .append(nested).append("    ").append(backward).append(" = true;\n")
                .append(nested).append("}\n")
                .append(indent).append("}\n");
        emitConnectionFailureIf(
                source,
                mysql,
                indent,
                guarded + pageSize + " < 0L || " + pageSize + " > " + pagination.maxPageSize() + "L",
                "connection argument 'first' or 'last' is outside this root's page-size budget"
        );

        emitConnectionCustomOrderBinding(source, type, root, scope, customOrders, mysql, indent, nested, guarded,
                rootStart, customOrder, customOrderPath, customOrderDescending);

        source.append(indent).append("String ").append(afterRaw).append(" = DatabaseGraphqlEngine.materializedArgumentValue(materializedArguments, ")
                .append(rootStart).append(", \"after\");\n")
                .append(indent).append("boolean ").append(afterPresent).append(" = false;\n")
                .append(indent).append("long ").append(afterValue).append(" = 0L;\n")
                .append(indent).append("String ").append(customAfterStringValue).append(" = \"\";\n")
                .append(indent).append("long ").append(customAfterLongValue).append(" = 0L;\n")
                .append(indent).append("long ").append(customAfterTieValue).append(" = 0L;\n")
                .append(indent).append("if (").append(guarded).append(afterRaw).append(".length() != 0 && !DatabaseGraphqlEngine.argumentValueIsNull(").append(afterRaw).append(")) {\n")
                .append(nested).append("if (!DatabaseGraphqlEngine.argumentVariableTypeIsCompatibleWithNullableArgumentFromAst(query, requestAst, ")
                .append(rootStart).append(", \"after\", \"String\")) {\n");
        emitConnectionFailure(source, mysql, nested + "    ",
                "variable for connection argument 'after' must be declared as String or String!");
        source.append(nested).append("}\n")
                .append(nested).append("String ").append(afterCursor).append(" = DatabaseGraphqlEngine.stringArgument(")
                .append(afterRaw).append(");\n")
                .append(nested).append("if (").append(mysql ? "response.length() == 0 && " : "")
                .append(afterCursor).append(".length() == 0) {\n");
        emitConnectionFailure(source, mysql, nested + "    ",
                "connection argument 'after' must be a Relay cursor string");
        source.append(nested).append("}\n");
        emitConnectionCustomCursorDecode(source, document, type, root, customOrders, mysql, nested,
                customOrder, customOrderPath, afterCursor, customAfterStringValue, customAfterLongValue, customAfterTieValue,
                customOrderDescending, "after");
        source.append(nested).append("if (!").append(customOrder).append(") {\n")
                .append(nested).append(afterValue)
                .append(" = DatabaseGraphqlEngine.relayCursorLongValue(").append(afterCursor).append(", \"")
                .append(javaString(cursor.path())).append("\", \"").append(javaString(cursor.path()))
                .append("\", \"").append(cursorDirection).append("\", \"")
                .append(javaString(cursorTieBreaker)).append("\");\n")
                .append(nested).append("if (").append(mysql ? "response.length() == 0 && " : "")
                .append("DatabaseGraphqlEngine.invalidIntegerForScalar(").append(afterValue).append(", \"Int\")) {\n");
        emitConnectionFailure(source, mysql, nested + "    ",
                "connection argument 'after' is not a cursor for this root ordering");
        source.append(nested).append("}\n")
                .append(nested).append("}\n")
                .append(nested).append("if (").append(mysql ? "response.length() == 0" : "true").append(") ")
                .append(afterPresent).append(" = true;\n")
                .append(indent).append("}\n");

        source.append(indent).append("String ").append(beforeRaw).append(" = DatabaseGraphqlEngine.materializedArgumentValue(materializedArguments, ")
                .append(rootStart).append(", \"before\");\n")
                .append(indent).append("boolean ").append(beforePresent).append(" = false;\n")
                .append(indent).append("long ").append(beforeValue).append(" = 0L;\n")
                .append(indent).append("String ").append(customBeforeStringValue).append(" = \"\";\n")
                .append(indent).append("long ").append(customBeforeLongValue).append(" = 0L;\n")
                .append(indent).append("long ").append(customBeforeTieValue).append(" = 0L;\n")
                .append(indent).append("if (").append(guarded).append(beforeRaw).append(".length() != 0 && !DatabaseGraphqlEngine.argumentValueIsNull(").append(beforeRaw).append(")) {\n")
                .append(nested).append("if (!DatabaseGraphqlEngine.argumentVariableTypeIsCompatibleWithNullableArgumentFromAst(query, requestAst, ")
                .append(rootStart).append(", \"before\", \"String\")) {\n");
        emitConnectionFailure(source, mysql, nested + "    ",
                "variable for connection argument 'before' must be declared as String or String!");
        source.append(nested).append("}\n")
                .append(nested).append("String ").append(beforeCursor).append(" = DatabaseGraphqlEngine.stringArgument(")
                .append(beforeRaw).append(");\n")
                .append(nested).append("if (").append(mysql ? "response.length() == 0 && " : "")
                .append(beforeCursor).append(".length() == 0) {\n");
        emitConnectionFailure(source, mysql, nested + "    ",
                "connection argument 'before' must be a Relay cursor string");
        source.append(nested).append("}\n");
        emitConnectionCustomCursorDecode(source, document, type, root, customOrders, mysql, nested,
                customOrder, customOrderPath, beforeCursor, customBeforeStringValue, customBeforeLongValue, customBeforeTieValue,
                customOrderDescending, "before");
        source.append(nested).append("if (!").append(customOrder).append(") {\n")
                .append(nested).append(beforeValue)
                .append(" = DatabaseGraphqlEngine.relayCursorLongValue(").append(beforeCursor).append(", \"")
                .append(javaString(cursor.path())).append("\", \"").append(javaString(cursor.path()))
                .append("\", \"").append(cursorDirection).append("\", \"")
                .append(javaString(cursorTieBreaker)).append("\");\n")
                .append(nested).append("if (").append(mysql ? "response.length() == 0 && " : "")
                .append("DatabaseGraphqlEngine.invalidIntegerForScalar(").append(beforeValue).append(", \"Int\")) {\n");
        emitConnectionFailure(source, mysql, nested + "    ",
                "connection argument 'before' is not a cursor for this root ordering");
        source.append(nested).append("}\n")
                .append(nested).append("}\n")
                .append(nested).append("if (").append(mysql ? "response.length() == 0" : "true").append(") ")
                .append(beforePresent).append(" = true;\n")
                .append(indent).append("}\n");
        emitConnectionFailureIf(
                source,
                mysql,
                indent,
                guarded + backward + " && " + afterPresent,
                "connection argument 'after' cannot be used with backward 'last' pagination"
        );
        emitConnectionFailureIf(
                source,
                mysql,
                indent,
                guarded + "!" + backward + " && " + beforePresent,
                "connection argument 'before' requires backward 'last' pagination"
        );

        // Relation selections are prevalidated before their parent query executes. Reuse the
        // complete Relay selection/argument path above, but deliberately stop before declaring
        // result state or opening the first static statement. The normal per-row call below still
        // owns execution and response completion.
        if (validationOnly) {
            return;
        }

        String hasNext = scopedLocal("connectionHasNextPage", scope, "value");
        String hasPrevious = scopedLocal("connectionHasPreviousPage", scope, "value");
        String edgesItems = scopedLocal("connectionEdgesItems", scope, "value");
        String connectionMembers = scopedLocal("connectionMembers", scope, "value");
        String connectionStartCursor = scopedLocal("connectionStartCursor", scope, "value");
        String connectionEndCursor = scopedLocal("connectionEndCursor", scope, "value");
        boolean rootCollectionBatch = requiredTargetColumn.isBlank() && relationHops == 0;
        source.append(indent).append("boolean ").append(hasNext).append(" = false;\n")
                .append(indent).append("boolean ").append(hasPrevious).append(" = false;\n")
                .append(indent).append("String ").append(connectionMembers).append(" = \"\";\n")
                .append(indent).append("String ").append(edgesItems).append(" = \"\";\n")
                .append(indent).append("String ").append(connectionStartCursor).append(" = \"\";\n")
                .append(indent).append("String ").append(connectionEndCursor).append(" = \"\";\n");
        if (rootCollectionBatch) {
            for (TitanGraphqlRelationDocument relation : selectableRelations(type)) {
                if (supportsUnpaginatedRelationBatch(type, relation, document)
                        || supportsRelayRelationBatch(type, relation, document)) {
                    source.append(indent).append("String ").append(batchParentKeys(scope, relation))
                            .append(" = \"bk1;\";\n")
                            .append(indent).append("String ").append(batchParentActivity(scope, relation))
                            .append(" = \"ba1;\";\n")
                            .append(indent).append("String ").append(batchParentPaths(scope, relation))
                            .append(" = \"bk1;\";\n")
                            .append(indent).append("String ").append(batchPlanStarts(scope, relation))
                            .append(" = \"bk1;\";\n");
                }
            }
        }
        // Connection argument/cursor validation can be substantial. Recheck before its first
        // page statement; the existing MySQL guard below observes any response this emits.
        emitExecutionDeadlineCheck(source, mysql, indent, false);
        source.append(indent).append("if (").append(guarded).append("(").append(edgesStart).append(" >= 0 || ")
                .append(pageInfoStart).append(" >= 0)) {\n");
        emitApplicationStatementReservation(source, mysql, nested, false, rootStart);
        // The opposite-page probe returns zero or one carrier row. Reserve its maximum before
        // the cursor so its getter remains in Titan's portable single-row transfer shape.
        emitDecodedApplicationRowReservation(source, mysql, nested, false, rootStart);
        if (mysql) source.append(nested).append("if (response.length() == 0) {\n");
        source.append(nested).append("PreparedStatement ").append(pageStatement).append(" = connection.prepareStatement(\"")
                .append(javaString(connectionSqlWithCustomOrders(document, root, type, cursor, connectionArguments,
                        connectionContextFilters, connectionFilters, customOrders, !mysql,
                        requiredTargetColumn))).append("\");\n");
        int connectionParameter = 1;
        connectionParameter = emitConnectionFilterParameters(source, pageStatement, connectionParameter,
                scope, connectionFilters, nested, selectionAncestorTypePath == null, root);
        if (requiredSourceField != null) {
            source.append(nested).append(pageStatement).append('.').append(jdbcSetter(requiredSourceField))
                    .append("(").append(connectionParameter++).append(", ").append(requiredSourceValue).append(");\n");
        }
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : connectionArguments) {
            String argumentScope = scope + "_" + argument.name();
            TitanGraphqlFieldDocument argumentField = fieldByColumn(type, argument.column());
            source.append(nested).append(pageStatement).append(".setBoolean(").append(connectionParameter++)
                    .append(", ").append(scopedLocal("connectionArgumentPresent", argumentScope, "value")).append(");\n")
                    .append(nested).append(pageStatement).append('.').append(jdbcSetter(argumentField, argument.type())).append('(')
                    .append(connectionParameter++)
                    .append(", ").append(scopedLocal("connectionArgumentValue", argumentScope, "value")).append(");\n");
        }
        for (TitanGraphqlContextFilterDocument filter : connectionContextFilters) {
            String filterScope = scope + "_" + filter.name();
            String enabled = scopedLocal("connectionContextFilterEnabled", filterScope, "value");
            String value = scopedLocal("connectionContextFilterValue", filterScope, "value");
            boolean booleanValue = filter.operator() == TitanGraphqlContextFilterDocument.Operator.BOOLEAN_EQUALS;
            source.append(nested).append(pageStatement).append(".setBoolean(").append(connectionParameter++)
                    .append(", ").append(enabled).append(");\n")
                    .append(nested).append(pageStatement).append(".setBoolean(").append(connectionParameter++)
                    .append(", ").append(booleanValue ? value + " >= 0"
                            : "DatabaseGraphqlEngine.trustedContextStringValuePresent(trustedContextJson, \""
                            + javaString(filter.contextKey()) + "\")").append(");\n")
                    .append(nested).append(pageStatement).append(booleanValue ? ".setBoolean(" : ".setString(")
                    .append(connectionParameter++).append(", ").append(booleanValue ? value + " == 1" : value)
                    .append(");\n");
        }
        connectionParameter = emitConnectionCursorParameters(source, pageStatement, connectionParameter,
                document, root, type, customOrders, customOrder, customOrderPath, customOrderDescending,
                afterPresent, afterValue, customAfterStringValue, customAfterLongValue, customAfterTieValue,
                beforePresent, beforeValue, customBeforeStringValue, customBeforeLongValue, customBeforeTieValue,
                backward, pageSize, nested, selectionAncestorTypePath == null);
        source.append(nested).append("ResultSet ").append(pageResultSet).append(" = ").append(pageStatement).append(".executeQuery();\n")
                .append(nested).append("long ").append(emittedRows).append(" = 0L;\n")
                .append(nested).append("while (").append(pageResultSet).append(".next()) {\n")
                .append(nested).append("    if (!").append(propagatedNull)
                .append(mysql ? " && response.length() == 0" : "").append(") {\n");
        // A page is bounded by the generated root contract, but decoding each row and assembling
        // nested edge JSON still consumes database-routine time. Recheck at every materialized
        // row instead of allowing one long cursor loop to pass the request deadline unchecked.
        emitExecutionDeadlineCheck(source, mysql, nested + "    ", false);
        if (mysql) {
            source.append(nested).append("    if (response.length() == 0) {\n");
        }
        source.append(nested).append("    if (").append(emittedRows).append(" < ").append(pageSize).append(") {\n");
        // Root and relation connections share one decoded-row allowance. Charge before reading
        // the row projection or opening any descendant relation statement.
        source.append(nested).append("        if (").append(nestedRelationRows).append(" >= ")
                .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("L) {\n");
        if (mysql) {
            source.append(nested).append("            response = DatabaseGraphqlEngine.resourceLimitErrorJsonAt(\"request exceeds decoded application row budget of ")
                    .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("\", query, ")
                    .append(rootStart).append(");\n")
                    .append(nested).append("            break;\n");
        } else {
            source.append(nested).append("            return DatabaseGraphqlEngine.resourceLimitErrorJsonAt(\"request exceeds decoded application row budget of ")
                    .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("\", query, ")
                    .append(rootStart).append(");\n");
        }
        source.append(nested).append("        }\n")
                .append(nested).append("        ").append(nestedRelationRows).append("++;\n");
        // Read the static projection in SQL-column order even when the client only asks for an
        // edge cursor. Titan's JDBC lowering materializes result columns from getter order.
        // Keeping these getters aligned with the scalar and private-join SQL projection avoids a
        // cursor-only request changing the row carrier's Boolean/Long positions.
        for (TitanGraphqlFieldDocument field : sortedFields(type)) {
            emitFieldRead(source, scope, field, pageResultSet, nested + "        ");
        }
        // A connection node can select the same bounded relation tree as a point object. Read
        // its reviewed, private local keys in projection order before relation output opens the
        // next static statement. This keeps all completion database-resident.
        for (TitanGraphqlRelationDocument relation : selectableRelations(type)) {
            emitRelationJoinRead(source, document, type, scope, relation, pageResultSet, nested + "        ");
        }
        for (TitanGraphqlRootDocument.RootDocumentSortPath sort : customOrders) {
            emitConnectionCustomOrderRead(
                    source, document, type, root, scope, sort, pageResultSet, nested + "        ");
        }
        TitanGraphqlFieldDocument cursorField = fieldByColumn(type, cursor.column());
        source.append(nested).append("        String ").append(emittedCursor).append(" = \"\";\n")
                .append(nested).append("        if (!").append(customOrder).append(") ").append(emittedCursor).append(" = DatabaseGraphqlEngine.relayCursorForLong(\"")
                .append(javaString(cursor.path())).append("\", \"").append(javaString(cursor.path()))
                .append("\", \"").append(cursorDirection).append("\", \"")
                .append(javaString(cursorTieBreaker)).append("\", ")
                .append(scopedLocal("fieldValue", scope, cursorField.name())).append(");\n");
        for (TitanGraphqlRootDocument.RootDocumentSortPath sort : customOrders) {
            TitanGraphqlFieldDocument value = connectionCustomOrderField(document, type, root, sort);
            TitanGraphqlFieldDocument tieBreaker = fieldByColumn(type, connectionCustomOrderTieBreaker(type, sort));
            source.append(nested).append("        if (").append(customOrder).append(" && ").append(customOrderPath)
                    .append(".equals(\"").append(javaString(sort.name())).append("\")) ").append(emittedCursor).append(" = ");
            if (connectionCustomOrderUsesTextCursor(value)) {
                source.append("DatabaseGraphqlEngine.relayCursorForStringLongTie(\"")
                        .append(javaString(sort.name())).append("\", \"").append(javaString(sort.path()))
                        .append("\", ").append(customOrderDescending).append(" ? \"DESC\" : \"ASC\", \"")
                        .append(javaString(connectionCustomOrderTieBreaker(type, sort))).append("\", ")
                        .append(connectionCustomOrderRowLocal(scope, sort)).append(", ")
                        .append(scopedLocal("fieldValue", scope, tieBreaker.name())).append(");\n");
            } else {
                source.append("DatabaseGraphqlEngine.relayCursorForLongLongTie(\"")
                        .append(javaString(sort.name())).append("\", \"").append(javaString(sort.path()))
                        .append("\", ").append(customOrderDescending).append(" ? \"DESC\" : \"ASC\", \"")
                        .append(javaString(connectionCustomOrderTieBreaker(type, sort))).append("\", ")
                        .append(connectionCustomOrderRowLocal(scope, sort)).append(", ")
                        .append(scopedLocal("fieldValue", scope, tieBreaker.name())).append(");\n");
            }
        }
        source.append(nested).append("        if (").append(backward).append(") {\n")
                .append(nested).append("            ").append(connectionStartCursor).append(" = ").append(emittedCursor).append(";\n")
                .append(nested).append("            if (").append(connectionEndCursor).append(".length() == 0) ")
                .append(connectionEndCursor).append(" = ").append(emittedCursor).append(";\n")
                .append(nested).append("        } else {\n")
                .append(nested).append("            if (").append(connectionStartCursor).append(".length() == 0) ")
                .append(connectionStartCursor).append(" = ").append(emittedCursor).append(";\n")
                .append(nested).append("            ").append(connectionEndCursor).append(" = ").append(emittedCursor).append(";\n")
                .append(nested).append("        }\n")
                .append(nested).append("        if (").append(edgesStart).append(" >= 0) {\n")
                .append(nested).append("            String ").append(edgeMembers).append(" = \"\";\n")
                .append(nested).append("            boolean ").append(scopedLocal("connectionNodePropagatedNull", scope, "value"))
                .append(" = false;\n")
                .append(nested).append("            String ").append(nodeValue).append(" = \"\";\n")
                .append(nested).append("            if (").append(nodeStart).append(" >= 0) {\n");
        String connectionNodePath = scopedLocal("connectionNodeExecutionPath", scope, "value");
        String connectionNodePropagatedNull = scopedLocal("connectionNodePropagatedNull", scope, "value");
        source.append(nested).append("                String ").append(connectionNodePath)
                .append(" = DatabaseGraphqlEngine.appendExecutionPath(DatabaseGraphqlEngine.appendExecutionPathIndex(")
                .append("DatabaseGraphqlEngine.appendExecutionPath(").append(connectionPath)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                .append(edgesStart).append(", \"edges\")), ").append(emittedRows).append("), DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                .append(nodeStart).append(", \"node\"));\n")
                .append(nested).append("                String ").append(nodeMembers).append(" = \"\";\n");
        emitConnectionNodeObjectOutput(source, document, root, type, mysql, scope, nodeMembers,
                connectionNodePath, connectionNodePropagatedNull, nested + "                ", nestedRelationRows,
                nodeAncestorTypePath, relationHops, activeSelectionHopBudget, rootCollectionBatch);
        source.append(nested).append("                ").append(nodeValue).append(" = ").append(connectionNodePropagatedNull)
                .append(" ? \"null\" : \"{\" + ").append(nodeMembers).append(" + \"}\";\n")
                .append(nested).append("                if (").append(connectionNodePropagatedNull).append(") ")
                .append(propagatedNull).append(" = true;\n")
                .append(nested).append("            }\n")
                .append(nested).append("            int ").append(edgesSelectionIndex).append(" = 0;\n")
                .append(nested).append("            int ").append(edgesSelectionFieldStart).append(" = -1;\n")
                .append(nested).append("            while (").append(edgesSelectionIndex).append(" < ").append(edgesSelectionCount).append(") {\n")
                .append(nested).append("                ").append(edgesSelectionFieldStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStart(").append(edgesSelectionPlan)
                .append(", ").append(edgesSelectionIndex).append(");\n")
                .append(nested).append("                if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(edgesSelectionFieldStart).append(", \"__typename\")) {\n")
                .append(nested).append("                    ").append(edgeMembers).append(" = DatabaseGraphqlEngine.appendJsonMember(").append(edgeMembers).append(", ")
                .append("DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(edgesSelectionFieldStart)
                .append(", \"__typename\"), DatabaseGraphqlEngine.jsonString(\"").append(javaString(publicEdgeType))
                .append("\"));\n")
                .append(nested).append("                } else if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(edgesSelectionFieldStart).append(", \"cursor\")) {\n")
                .append(nested).append("                    ").append(edgeMembers).append(" = DatabaseGraphqlEngine.appendJsonMember(").append(edgeMembers).append(", ")
                .append("DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(edgesSelectionFieldStart)
                .append(", \"cursor\"), DatabaseGraphqlEngine.jsonString(").append(emittedCursor).append("));\n")
                .append(nested).append("                } else if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(edgesSelectionFieldStart).append(", \"node\")) {\n")
                .append(nested).append("                    ").append(edgeMembers).append(" = DatabaseGraphqlEngine.appendJsonMember(").append(edgeMembers).append(", ")
                .append("DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(edgesSelectionFieldStart)
                .append(", \"node\"), ").append(nodeValue).append(");\n")
                .append(nested).append("                }\n")
                .append(nested).append("                ").append(edgesSelectionIndex).append("++;\n")
                .append(nested).append("            }\n")
                .append(nested).append("            if (!").append(propagatedNull).append(") {\n")
                .append(nested).append("            String edgeValue = \"{\" + ").append(edgeMembers).append(" + \"}\";\n")
                .append(nested).append("            if (").append(backward).append(") {\n")
                .append(nested).append("                ").append(edgesItems).append(" = DatabaseGraphqlEngine.prependJsonItem(")
                .append(edgesItems).append(", edgeValue);\n")
                .append(nested).append("            } else {\n")
                .append(nested).append("                ").append(edgesItems).append(" = DatabaseGraphqlEngine.appendJsonItem(")
                .append(edgesItems).append(", edgeValue);\n")
                .append(nested).append("            }\n")
                .append(nested).append("            }\n")
                .append(nested).append("        }\n")
                .append(nested).append("    } else {\n")
                .append(nested).append("        if (").append(backward).append(") ").append(hasPrevious)
                .append(" = true; else ").append(hasNext).append(" = true;\n")
                .append(nested).append("    }\n")
                .append(nested).append("    ").append(emittedRows).append("++;\n");
        if (mysql) {
            source.append(nested).append("    }\n");
        }
        source.append(nested).append("    }\n")
                .append(nested).append("}\n");
        if (mysql) source.append(nested).append("}\n");
        source.append(indent).append("}\n");
        if (rootCollectionBatch) {
            for (TitanGraphqlRelationDocument relation : selectableRelations(type)) {
                if (supportsUnpaginatedRelationBatch(type, relation, document)) {
                    emitUnpaginatedRelationPlanBatches(source, document, root, type, relation, mysql, scope,
                            edgesItems, propagatedNull, nestedRelationRows, nodeAncestorTypePath,
                            relationHops + 1,
                            Math.min(activeSelectionHopBudget, relation.selectionHopBudget()), null, null);
                } else if (supportsRelayRelationBatch(type, relation, document)) {
                    emitRelayRelationPlanBatches(source, document, root, type, relation, mysql, scope,
                            edgesItems, propagatedNull, nestedRelationRows, nodeAncestorTypePath,
                            relationHops + 1,
                            Math.min(activeSelectionHopBudget, relation.selectionHopBudget()));
                }
            }
        }
        source.append(indent).append("if (").append(guarded).append(edgesStart).append(" >= 0) {\n")
                .append(nested).append("String edgesValue = \"[\" + ").append(edgesItems).append(" + \"]\";\n")
                .append(nested).append(connectionMembers).append(" = DatabaseGraphqlEngine.appendJsonMember(")
                .append(connectionMembers).append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(edgesStart)
                .append(", \"edges\"), edgesValue);\n")
                .append(indent).append("}\n");

        // Opaque cursors are structurally valid even when their value has never named a visible
        // row. Ask the same model-derived scope for the opposite page boundary instead of using
        // `after`/`before` presence as a page flag. In backward mode the meaning is mirrored.
        emitExecutionDeadlineCheck(source, mysql, indent, false);
        source.append(indent).append("if (").append(guarded).append("((!").append(backward).append(" && ")
                .append(afterPresent).append(" && ").append(hasPreviousStart).append(" >= 0) || (")
                .append(backward).append(" && ").append(beforePresent).append(" && ")
                .append(hasNextStart).append(" >= 0))) {\n");
        emitApplicationStatementReservation(source, mysql, nested, false, rootStart);
        if (mysql) source.append(nested).append("if (response.length() == 0) {\n");
        source.append(nested).append("PreparedStatement ").append(boundaryStatement).append(" = connection.prepareStatement(\"")
                .append(javaString(connectionBoundarySqlWithCustomOrders(document, root, type, cursor, connectionArguments,
                        connectionContextFilters, connectionFilters, customOrders, !mysql, requiredTargetColumn)))
                .append("\");\n");
        int boundaryParameter = 1;
        boundaryParameter = emitConnectionFilterParameters(source, boundaryStatement, boundaryParameter,
                scope, connectionFilters, nested, selectionAncestorTypePath == null, root);
        if (requiredSourceField != null) {
            source.append(nested).append(boundaryStatement).append('.').append(jdbcSetter(requiredSourceField))
                    .append("(").append(boundaryParameter++).append(", ").append(requiredSourceValue).append(");\n");
        }
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : connectionArguments) {
            String argumentScope = scope + "_" + argument.name();
            TitanGraphqlFieldDocument argumentField = fieldByColumn(type, argument.column());
            source.append(nested).append(boundaryStatement).append(".setBoolean(").append(boundaryParameter++)
                    .append(", ").append(scopedLocal("connectionArgumentPresent", argumentScope, "value")).append(");\n")
                    .append(nested).append(boundaryStatement).append('.').append(jdbcSetter(argumentField, argument.type())).append('(')
                    .append(boundaryParameter++)
                    .append(", ").append(scopedLocal("connectionArgumentValue", argumentScope, "value")).append(");\n");
        }
        for (TitanGraphqlContextFilterDocument filter : connectionContextFilters) {
            String filterScope = scope + "_" + filter.name();
            String enabled = scopedLocal("connectionContextFilterEnabled", filterScope, "value");
            String value = scopedLocal("connectionContextFilterValue", filterScope, "value");
            boolean booleanValue = filter.operator() == TitanGraphqlContextFilterDocument.Operator.BOOLEAN_EQUALS;
            source.append(nested).append(boundaryStatement).append(".setBoolean(").append(boundaryParameter++)
                    .append(", ").append(enabled).append(");\n")
                    .append(nested).append(boundaryStatement).append(".setBoolean(").append(boundaryParameter++)
                    .append(", ").append(booleanValue ? value + " >= 0"
                            : "DatabaseGraphqlEngine.trustedContextStringValuePresent(trustedContextJson, \""
                            + javaString(filter.contextKey()) + "\")").append(");\n")
                    .append(nested).append(boundaryStatement).append(booleanValue ? ".setBoolean(" : ".setString(")
                    .append(boundaryParameter++).append(", ").append(booleanValue ? value + " == 1" : value)
                    .append(");\n");
        }
        boundaryParameter = emitConnectionBoundaryParameters(source, boundaryStatement, boundaryParameter,
                document, root, type, customOrders, customOrder, customOrderPath, customOrderDescending,
                afterPresent, afterValue, customAfterStringValue, customAfterLongValue, customAfterTieValue,
                beforePresent, beforeValue, customBeforeStringValue, customBeforeLongValue, customBeforeTieValue,
                backward, nested, selectionAncestorTypePath == null);
        source.append(nested).append("ResultSet ").append(boundaryResultSet).append(" = ").append(boundaryStatement).append(".executeQuery();\n")
                // Titan lowers a JDBC getter directly into its assigned request local. Keep the
                // backward extra-row result while using that proven shape for the opposite flag.
                .append(nested).append("boolean ").append(savedHasPrevious).append(" = ").append(hasPrevious).append(";\n")
                .append(nested).append("if (").append(boundaryResultSet).append(".next()) {\n");
        source.append(nested).append("    ").append(hasPrevious)
                .append(" = ").append(boundaryResultSet).append(".getBoolean(\"tgql_has_opposite\");\n")
                ;
        source.append(nested).append("}\n")
                .append(nested).append("if (").append(backward).append(") {\n")
                .append(nested).append("    ").append(hasNext).append(" = ").append(hasPrevious).append(";\n")
                .append(nested).append("    ").append(hasPrevious).append(" = ").append(savedHasPrevious).append(";\n")
                .append(nested).append("}\n");
        if (mysql) source.append(nested).append("}\n");
        source.append(indent).append("}\n");

        emitExecutionDeadlineCheck(source, mysql, indent, false);
        source.append(indent).append("if (").append(guarded).append(totalCountStart).append(" >= 0) {\n");
        emitApplicationStatementReservation(source, mysql, nested, false, rootStart);
        // COUNT(*) always exposes one row, so reserve it before the cursor transfer.
        emitDecodedApplicationRowReservation(source, mysql, nested, false, rootStart);
        if (mysql) source.append(nested).append("if (response.length() == 0) {\n");
        source.append(nested).append("PreparedStatement ").append(countStatement).append(" = connection.prepareStatement(\"")
                .append(javaString(connectionCountSql(document, root, type, connectionArguments, connectionContextFilters,
                        connectionFilters, !mysql, requiredTargetColumn))).append("\");\n");
        int countParameter = 1;
        countParameter = emitConnectionFilterParameters(source, countStatement, countParameter,
                scope, connectionFilters, nested, selectionAncestorTypePath == null, root);
        if (requiredSourceField != null) {
            source.append(nested).append(countStatement).append('.').append(jdbcSetter(requiredSourceField))
                    .append("(").append(countParameter++).append(", ").append(requiredSourceValue).append(");\n");
        }
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : connectionArguments) {
            String argumentScope = scope + "_" + argument.name();
            TitanGraphqlFieldDocument argumentField = fieldByColumn(type, argument.column());
            source.append(nested).append(countStatement).append(".setBoolean(").append(countParameter++)
                    .append(", ").append(scopedLocal("connectionArgumentPresent", argumentScope, "value")).append(");\n")
                    .append(nested).append(countStatement).append('.').append(jdbcSetter(argumentField, argument.type())).append('(')
                    .append(countParameter++)
                    .append(", ").append(scopedLocal("connectionArgumentValue", argumentScope, "value")).append(");\n");
        }
        for (TitanGraphqlContextFilterDocument filter : connectionContextFilters) {
            String filterScope = scope + "_" + filter.name();
            String enabled = scopedLocal("connectionContextFilterEnabled", filterScope, "value");
            String value = scopedLocal("connectionContextFilterValue", filterScope, "value");
            boolean booleanValue = filter.operator() == TitanGraphqlContextFilterDocument.Operator.BOOLEAN_EQUALS;
            source.append(nested).append(countStatement).append(".setBoolean(").append(countParameter++)
                    .append(", ").append(enabled).append(");\n")
                    .append(nested).append(countStatement).append(".setBoolean(").append(countParameter++)
                    .append(", ").append(booleanValue ? value + " >= 0"
                            : "DatabaseGraphqlEngine.trustedContextStringValuePresent(trustedContextJson, \""
                            + javaString(filter.contextKey()) + "\")").append(");\n")
                    .append(nested).append(countStatement).append(booleanValue ? ".setBoolean(" : ".setString(")
                    .append(countParameter++).append(", ").append(booleanValue ? value + " == 1" : value)
                    .append(");\n");
        }
        source.append(nested).append("ResultSet ").append(countResultSet).append(" = ").append(countStatement).append(".executeQuery();\n")
                .append(nested).append("if (!").append(countResultSet).append(".next()) {").append("\n");
        // Titan recognizes this exact early JDBC guard and lowers the following getter into an
        // INTO target. COUNT(*) always supplies a row; an absence is therefore an installation
        // failure rather than a client GraphQL validation outcome.
        source.append(nested).append("    throw new SQLException(\"connection total-count read produced no row\");\n");
        source.append(nested).append("}\n");
        source.append(nested).append("long ").append(totalCountValue).append(" = ")
                .append(countResultSet).append(".getLong(\"tgql_total_count\");\n");
        source.append(nested).append(totalCountSelectionIndex).append(" = 0;\n")
                .append(nested).append("int ").append(totalCountSelectionFieldStart).append(" = -1;\n")
                .append(nested).append("while (").append(totalCountSelectionIndex).append(" < ")
                .append(connectionSelectionCount).append(") {\n")
                .append(nested).append("    ").append(totalCountSelectionFieldStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStart(").append(connectionSelectionPlan)
                .append(", ").append(totalCountSelectionIndex).append(");\n")
                .append(nested).append("    if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(totalCountSelectionFieldStart).append(", \"totalCount\")) {\n")
                .append(nested).append("        ").append(connectionMembers).append(" = DatabaseGraphqlEngine.appendJsonMember(")
                .append(connectionMembers).append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                .append(totalCountSelectionFieldStart).append(", \"totalCount\"), \"\" + ").append(totalCountValue).append(");\n")
                .append(nested).append("    }\n")
                .append(nested).append("    ").append(totalCountSelectionIndex).append("++;\n")
                .append(nested).append("}\n");
        if (mysql) source.append(nested).append("}\n");
        source.append(indent).append("}\n")
                .append(indent).append("if (").append(guarded).append(pageInfoStart).append(" >= 0) {\n")
                .append(nested).append("String ").append(pageInfoMembers).append(" = \"\";\n")
                .append(nested).append("int ").append(pageInfoSelectionIndex).append(" = 0;\n")
                .append(nested).append("int ").append(pageInfoSelectionFieldStart).append(" = -1;\n")
                .append(nested).append("while (").append(pageInfoSelectionIndex).append(" < ")
                .append(pageInfoSelectionCount).append(") {\n")
                .append(nested).append("    ").append(pageInfoSelectionFieldStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStart(").append(pageInfoSelectionPlan)
                .append(", ").append(pageInfoSelectionIndex).append(");\n")
                .append(nested).append("    if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(pageInfoSelectionFieldStart).append(", \"__typename\")) {\n")
                .append(nested).append("        ").append(pageInfoMembers).append(" = DatabaseGraphqlEngine.appendJsonMember(").append(pageInfoMembers).append(", ")
                .append("DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(pageInfoSelectionFieldStart)
                .append(", \"__typename\"), DatabaseGraphqlEngine.jsonString(\"PageInfo\"));\n")
                .append(nested).append("    } else if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(pageInfoSelectionFieldStart).append(", \"hasNextPage\")) {\n")
                .append(nested).append("        ").append(pageInfoMembers).append(" = DatabaseGraphqlEngine.appendJsonMember(").append(pageInfoMembers).append(", ")
                .append("DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(pageInfoSelectionFieldStart)
                .append(", \"hasNextPage\"), ").append(hasNext).append(" ? \"true\" : \"false\");\n")
                .append(nested).append("    } else if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(pageInfoSelectionFieldStart).append(", \"hasPreviousPage\")) {\n")
                .append(nested).append("        ").append(pageInfoMembers).append(" = DatabaseGraphqlEngine.appendJsonMember(").append(pageInfoMembers).append(", ")
                .append("DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(pageInfoSelectionFieldStart)
                .append(", \"hasPreviousPage\"), ").append(hasPrevious).append(" ? \"true\" : \"false\");\n")
                .append(nested).append("    } else if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(pageInfoSelectionFieldStart).append(", \"startCursor\")) {\n")
                .append(nested).append("        ").append(pageInfoMembers).append(" = DatabaseGraphqlEngine.appendJsonMember(").append(pageInfoMembers).append(", ")
                .append("DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(pageInfoSelectionFieldStart)
                .append(", \"startCursor\"), ").append(connectionStartCursor)
                .append(".length() == 0 ? \"null\" : DatabaseGraphqlEngine.jsonString(").append(connectionStartCursor).append("));\n")
                .append(nested).append("    } else if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(pageInfoSelectionFieldStart).append(", \"endCursor\")) {\n")
                .append(nested).append("        ").append(pageInfoMembers).append(" = DatabaseGraphqlEngine.appendJsonMember(").append(pageInfoMembers).append(", ")
                .append("DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(pageInfoSelectionFieldStart)
                .append(", \"endCursor\"), ").append(connectionEndCursor)
                .append(".length() == 0 ? \"null\" : DatabaseGraphqlEngine.jsonString(").append(connectionEndCursor).append("));\n")
                .append(nested).append("    }\n")
                .append(nested).append("    ").append(pageInfoSelectionIndex).append("++;\n")
                .append(nested).append("}\n")
                .append(nested).append("String ").append(pageInfoValue).append(" = \"{\" + ").append(pageInfoMembers).append(" + \"}\";\n")
                .append(nested).append(connectionMembers).append(" = DatabaseGraphqlEngine.appendJsonMember(")
                .append(connectionMembers).append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(pageInfoStart)
                .append(", \"pageInfo\"), ").append(pageInfoValue).append(");\n")
                .append(indent).append("}\n")
                .append(indent).append("if (").append(guarded).append(connectionTypeNameStart).append(" >= 0) {\n")
                .append(nested).append(connectionTypeNameSelectionIndex).append(" = 0;\n")
                .append(nested).append("int ").append(connectionTypeNameSelectionFieldStart).append(" = -1;\n")
                .append(nested).append("while (").append(connectionTypeNameSelectionIndex).append(" < ")
                .append(connectionSelectionCount).append(") {\n")
                .append(nested).append("    ").append(connectionTypeNameSelectionFieldStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStart(").append(connectionSelectionPlan)
                .append(", ").append(connectionTypeNameSelectionIndex).append(");\n")
                .append(nested).append("    if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(connectionTypeNameSelectionFieldStart).append(", \"__typename\")) {\n")
                .append(nested).append("        ").append(connectionMembers).append(" = DatabaseGraphqlEngine.appendJsonMember(")
                .append(connectionMembers).append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                .append(connectionTypeNameSelectionFieldStart).append(", \"__typename\"), DatabaseGraphqlEngine.jsonString(\"")
                .append(javaString(publicConnectionType)).append("\"));\n")
                .append(nested).append("    }\n")
                .append(nested).append("    ").append(connectionTypeNameSelectionIndex).append("++;\n")
                .append(nested).append("}\n")
                .append(indent).append("}\n")
                .append(indent).append("if (").append(propagatedNull).append(") ").append(rootValue)
                .append(" = \"null\"; else ").append(rootValue).append(" = \"{\" + ")
                .append(connectionMembers).append(" + \"}\";\n");
    }

    /**
     * Validates a Relay node with the same request-order plan used by point objects. Direct
     * field-name probes cannot represent aliases. Its typed ancestry plan retains each response
     * key while recursively collecting compatible repeated relations before rendering.
     */
    private static void emitConnectionNodeSelectionValidation(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument type,
            boolean mysql,
            String nodeStart,
            String scope,
            String indent,
            String nodeAncestorTypePath,
            int relationHops,
            int activeSelectionHopBudget
    ) {
        String nested = indent + "    ";
        String active = nodeStart + " >= 0" + (mysql ? " && response.length() == 0" : "");
        String plan = scopedLocal("connectionNodeSelectionPlan", scope, "value");
        String count = scopedLocal("connectionNodeSelectionCount", scope, "value");
        String index = scopedLocal("connectionNodeSelectionIndex", scope, "value");
        String fieldStart = scopedLocal("connectionNodeSelectionStart", scope, "value");
        String matched = scopedLocal("connectionNodeSelectionMatched", scope, "value");

        source.append(indent).append("String ").append(plan).append(" = \"\";\n")
                .append(indent).append("int ").append(count).append(" = 0;\n")
                .append(indent).append("if (").append(active).append(") {\n")
                .append(nested).append("if (!DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, ")
                .append(nodeStart).append(", \"\")) {\n");
        emitConnectionFailure(source, mysql, nested + "    ",
                "connection field '" + root.name() + ".edges.node' has an argument");
        source.append(nested).append("}\n")
                .append(nested).append("if (").append(mysql ? "response.length() == 0" : "true").append(") {\n")
                .append(nested).append("    ").append(plan)
                .append(" = DatabaseGraphqlEngine.fieldSelectionPlanFromAst(query, requestAst, ")
                .append(nodeStart).append(", variablesJson, \"")
                .append(javaString(connectionPublicNodeType(root, type))).append("Edge\", \"")
                .append(javaString(runtimeTypeConditions(document, type))).append("\", \"")
                .append(javaString(nodeAncestorTypePath))
                .append("\");\n")
                .append(nested).append("    ").append(count).append(" = DatabaseGraphqlEngine.selectionPlanCount(")
                .append(plan).append(");\n")
                .append(nested).append("}\n");
        if (mysql) {
            source.append(nested).append("if (response.length() == 0 && ").append(count).append(" < 0) {\n")
                    .append(nested).append("    response = DatabaseGraphqlEngine.errorJsonAt(\"connection node selection plan is invalid or exceeds the database engine budget\", query, ")
                    .append(nodeStart).append(");\n")
                    .append(nested).append("}\n")
                    .append(nested).append("if (response.length() == 0 && ").append(count).append(" == 0) {\n")
                    .append(nested).append("    response = DatabaseGraphqlEngine.errorJsonAt(\"connection node selection must contain at least one field\", query, ")
                    .append(nodeStart).append(");\n")
                    .append(nested).append("}\n")
                    .append(nested).append("if (response.length() == 0 && DatabaseGraphqlEngine.selectionPlanHasDuplicateResponseKeyFromAst(query, requestAst, ")
                    .append(plan).append(")) {\n")
                    .append(nested).append("    response = DatabaseGraphqlEngine.errorJsonAt(\"connection node selection needs unsupported field merging for a duplicate response key\", query, ")
                    .append(nodeStart).append(");\n")
                    .append(nested).append("}\n");
        } else {
            source.append(nested).append("if (").append(count).append(" < 0) return DatabaseGraphqlEngine.errorJsonAt(\"connection node selection plan is invalid or exceeds the database engine budget\", query, ")
                    .append(nodeStart).append(");\n")
                    .append(nested).append("if (").append(count).append(" == 0) return DatabaseGraphqlEngine.errorJsonAt(\"connection node selection must contain at least one field\", query, ")
                    .append(nodeStart).append(");\n")
                    .append(nested).append("if (DatabaseGraphqlEngine.selectionPlanHasDuplicateResponseKeyFromAst(query, requestAst, ")
                    .append(plan).append(")) return DatabaseGraphqlEngine.errorJsonAt(\"connection node selection needs unsupported field merging for a duplicate response key\", query, ")
                    .append(nodeStart).append(");\n");
        }
        source.append(nested).append("int ").append(index).append(" = 0;\n")
                .append(nested).append("while (").append(index).append(" < ").append(count)
                .append(mysql ? " && response.length() == 0" : "").append(") {\n")
                .append(nested).append("    int ").append(fieldStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStart(").append(plan).append(", ")
                .append(index).append(");\n")
                .append(nested).append("    boolean ").append(matched).append(" = false;\n");
        emitTypeNameSelectionValidation(source, mysql, nested + "    ", matched, fieldStart, type.name());
        for (TitanGraphqlFieldDocument field : sortedFields(type)) {
            source.append(nested).append("    if (!").append(matched)
                    .append(" && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                    .append(fieldStart).append(", \"").append(javaString(field.name())).append("\")) {\n")
                    .append(nested).append("        ").append(matched).append(" = true;\n")
                    .append(nested).append("        if (!DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, ")
                    .append(fieldStart).append(", \"\")) {\n");
            emitConnectionFailureAt(source, mysql, nested + "            ",
                    "field '" + type.name() + "." + field.name() + "' has an argument", fieldStart);
            source.append(nested).append("        }\n");
            emitScalarLeafSelectionValidation(source, mysql, nested + "        ",
                    mysql ? "response.length() == 0 && " : "", fieldStart, type.name() + "." + field.name());
            if (!field.policies().isEmpty()) {
                source.append(nested).append("        if (true").append(mysql ? " && response.length() == 0" : "")
                        .append(") {\n");
                if (mysql) {
                    emitMySqlPolicyGuards(source, document, field.policies(),
                            "field '" + type.name() + "." + field.name() + "'", fieldStart, nested + "            ");
                } else {
                    emitPolicyGuards(source, document, field.policies(),
                            "field '" + type.name() + "." + field.name() + "'", fieldStart, nested + "            ");
                }
                source.append(nested).append("        }\n");
            }
            source.append(nested).append("    }\n");
        }
        for (TitanGraphqlRelationDocument relation : selectableRelations(type)) {
            source.append(nested).append("    if (!").append(matched)
                    .append(" && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                    .append(fieldStart).append(", \"").append(javaString(relation.name())).append("\")) {\n")
                    .append(nested).append("        ").append(matched).append(" = true;\n");
            if (!isBasicRelayRelationConnection(document, type, relation)) {
                source.append(nested).append("        if (!DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, ")
                        .append(fieldStart).append(", \"\")) {\n");
                emitConnectionFailureAt(source, mysql, nested + "            ",
                        "relation '" + type.name() + "." + relation.name() + "' has an argument", fieldStart);
                source.append(nested).append("        }\n");
            }
            int nextRelationHops = relationHops + 1;
            int nextSelectionHopBudget = Math.min(activeSelectionHopBudget, relation.selectionHopBudget());
            if (nextRelationHops <= nextSelectionHopBudget) {
                emitRelationSelectionValidation(source, document, root, type, relation, fieldStart, mysql,
                        nextRelationHops, nextSelectionHopBudget, scope,
                        nodeAncestorTypePath + "|" + runtimeTypeConditionPathEntry(document, type));
            } else {
                emitConnectionFailureAt(source, mysql, nested + "        ",
                        "relation '" + type.name() + "." + relation.name()
                                + "' exceeds selection hop budget of " + nextSelectionHopBudget,
                        fieldStart);
            }
            source.append(nested).append("    }\n");
        }
        source.append(nested).append("    if (!").append(matched)
                .append(mysql ? " && response.length() == 0" : "").append(") {\n");
        emitConnectionFailureAt(source, mysql, nested + "        ",
                "unknown or unsupported field in connection node '" + type.name() + "'", fieldStart);
        source.append(nested).append("    }\n")
                .append(nested).append("    ").append(index).append("++;\n")
                .append(nested).append("}\n")
                .append(indent).append("}\n");
    }

    /** Renders a validated connection node in request-plan order, preserving aliases. */
    private static void emitConnectionNodeObjectOutput(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument type,
            boolean mysql,
            String scope,
            String nodeMembers,
            String nodePath,
            String propagatedNull,
            String indent,
            String nestedRelationRows,
            String nodeAncestorTypePath,
            int relationHops,
            int activeSelectionHopBudget,
            boolean rootCollectionBatch
    ) {
        String plan = scopedLocal("connectionNodeSelectionPlan", scope, "value");
        String count = scopedLocal("connectionNodeSelectionCount", scope, "value");
        String index = scopedLocal("connectionNodeOutputIndex", scope, "value");
        String fieldStart = scopedLocal("connectionNodeOutputStart", scope, "value");
        String rendered = scopedLocal("connectionNodeOutputRendered", scope, "value");
        source.append(indent).append("int ").append(index).append(" = 0;\n")
                .append(indent).append("while (").append(index).append(" < ").append(count)
                .append(mysql ? " && response.length() == 0" : "").append(" && !").append(propagatedNull)
                .append(") {\n")
                .append(indent).append("    int ").append(fieldStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStart(").append(plan).append(", ")
                .append(index).append(");\n")
                .append(indent).append("    boolean ").append(rendered).append(" = false;\n");
        emitTypeNameOutput(source, indent + "    ", rendered, fieldStart, type.name(), nodeMembers, mysql);
        for (TitanGraphqlFieldDocument field : sortedFields(type)) {
            source.append(indent).append("    if (!").append(rendered)
                    .append(" && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                    .append(fieldStart).append(", \"").append(javaString(field.name())).append("\")) {\n");
            emitFieldOutputWithNonNullPropagation(source, document, field, type.name(), fieldStart,
                    scopedLocal("fieldValue", scope, field.name()), scopedLocal("fieldNull", scope, field.name()),
                    nodeMembers, indent + "        ", nodePath, propagatedNull, mysql);
            source.append(indent).append("        ").append(rendered).append(" = true;\n")
                    .append(indent).append("    }\n");
        }
        for (TitanGraphqlRelationDocument relation : selectableRelations(type)) {
            int nextRelationHops = relationHops + 1;
            int nextSelectionHopBudget = Math.min(activeSelectionHopBudget, relation.selectionHopBudget());
            if (nextRelationHops <= nextSelectionHopBudget) {
                source.append(indent).append("    if (!").append(propagatedNull).append(" && !").append(rendered)
                        .append(" && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                        .append(fieldStart).append(", \"").append(javaString(relation.name())).append("\")) {\n");
                boolean unpaginatedBatch = supportsUnpaginatedRelationBatch(type, relation, document);
                boolean relayBatch = supportsRelayRelationBatch(type, relation, document);
                if (rootCollectionBatch && (unpaginatedBatch || relayBatch)) {
                    TitanGraphqlFieldDocument sourceField = fieldByColumn(type, relation.localColumn());
                    boolean hiddenSourceColumn = sourceField == null;
                    if (hiddenSourceColumn) {
                        sourceField = relationJoinField(document, type, relation);
                    }
                    String keys = batchParentKeys(scope, relation);
                    String activity = batchParentActivity(scope, relation);
                    String paths = batchParentPaths(scope, relation);
                    String planStarts = batchPlanStarts(scope, relation);
                    String parentIndex = scopedLocal("batchParentIndex", scope, relation.name());
                    String relationValue = scopedLocal("batchRelationValue", scope, relation.name());
                    String sourceValue = hiddenSourceColumn
                            ? scopedLocal("relationJoinValue", scope, relation.name())
                            : scopedLocal("fieldValue", scope, sourceField.name());
                    String sourceNull = hiddenSourceColumn
                            ? scopedLocal("relationJoinNull", scope, relation.name())
                            : scopedLocal("fieldNull", scope, sourceField.name());
                    source.append(indent).append("        if (");
                    if (relayBatch) {
                        TitanGraphqlTypeDocument targetType = type(document, relation.targetType());
                        TitanGraphqlRootDocument contract = relationConnectionContract(relation);
                        source.append("(DatabaseGraphqlEngine.relayConnectionSelectionIsCountOnlyFromAst(query, requestAst, ")
                                .append(fieldStart).append(", variablesJson, \"")
                                .append(javaString(runtimeTypeConditionPathEntry(document, type))).append("\", \"")
                                .append(javaString(connectionPublicNodeType(contract, targetType) + "Connection"))
                                .append("\", \"")
                                .append(javaString(nodeAncestorTypePath + "|"
                                        + runtimeTypeConditionPathEntry(document, type)))
                                .append("\") || DatabaseGraphqlEngine.relayConnectionSelectionHasPageFromAst(query, requestAst, ")
                                .append(fieldStart).append(", variablesJson, \"")
                                .append(javaString(runtimeTypeConditionPathEntry(document, type))).append("\", \"")
                                .append(javaString(connectionPublicNodeType(contract, targetType) + "Connection"))
                                .append("\", \"")
                                .append(javaString(nodeAncestorTypePath + "|"
                                        + runtimeTypeConditionPathEntry(document, type)))
                                .append("\")) && (");
                    }
                    if (relayBatch) {
                        source.append("true)");
                    } else {
                        source.append("true");
                    }
                    source.append(") {\n")
                            .append(indent).append("            ");
                    source.append("int ").append(parentIndex)
                            .append(" = DatabaseGraphqlEngine.batchParentKeyCount(").append(keys).append(");\n")
                            .append(indent).append("            ").append(keys)
                            .append(" = DatabaseGraphqlEngine.appendBatchParentKey(").append(keys).append(", ")
                            .append(batchKeyString(sourceField, sourceValue))
                            .append(");\n")
                            .append(indent).append("            ").append(activity)
                            .append(" = DatabaseGraphqlEngine.appendBatchParentActivity(").append(activity)
                            .append(", !").append(sourceNull).append(");\n")
                            .append(indent).append("            ").append(paths)
                            .append(" = DatabaseGraphqlEngine.appendBatchParentKey(").append(paths)
                            .append(", ").append(nodePath).append(");\n");
                    source.append(indent).append("            ").append(planStarts)
                            .append(" = DatabaseGraphqlEngine.appendBatchParentKey(")
                            .append(planStarts).append(", \"\" + ").append(fieldStart).append(");\n");
                    source.append(indent).append("            if (DatabaseGraphqlEngine.responseAssemblyExceeded(")
                            .append(keys).append(") || DatabaseGraphqlEngine.responseAssemblyExceeded(")
                            .append(activity).append(") || DatabaseGraphqlEngine.responseAssemblyExceeded(")
                            .append(paths).append(")");
                    source.append(" || DatabaseGraphqlEngine.responseAssemblyExceeded(")
                            .append(planStarts).append(")");
                    source.append(") {\n");
                    if (mysql) {
                        source.append(indent).append("                response = DatabaseGraphqlEngine.resourceLimitErrorJson(\"relation batch parent keys exceed the database engine character budget\");\n");
                    } else {
                        source.append(indent).append("                return DatabaseGraphqlEngine.resourceLimitErrorJson(\"relation batch parent keys exceed the database engine character budget\");\n");
                    }
                    source.append(indent).append("            }\n")
                            .append(indent).append("            String ").append(relationValue)
                            .append(" = DatabaseGraphqlEngine.batchRelationPlaceholder(").append(fieldStart)
                            .append(", ").append(parentIndex).append(");\n")
                            .append(indent).append("            ").append(nodeMembers)
                            .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(nodeMembers)
                            .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                            .append(fieldStart).append(", \"").append(javaString(relation.name())).append("\"), ")
                            .append(relationValue).append(");\n")
                            .append(indent).append("        } else {\n");
                    emitRelationOutput(source, document, root, type, relation, fieldStart, mysql,
                            nodePath, propagatedNull, scope, nodeMembers, nextRelationHops, nextSelectionHopBudget,
                            nodeAncestorTypePath + "|" + runtimeTypeConditionPathEntry(document, type),
                            nestedRelationRows);
                    source.append(indent).append("        }\n");
                } else {
                    emitRelationOutput(source, document, root, type, relation, fieldStart, mysql,
                            nodePath, propagatedNull, scope, nodeMembers, nextRelationHops, nextSelectionHopBudget,
                            nodeAncestorTypePath + "|" + runtimeTypeConditionPathEntry(document, type),
                            nestedRelationRows);
                }
                source.append(indent).append("        ").append(rendered).append(" = true;\n")
                        .append(indent).append("    }\n");
            }
        }
        source.append(indent).append("    if (!").append(rendered)
                .append(mysql ? " && response.length() == 0" : "").append(") {\n");
        emitInternalFailureAt(source, mysql, indent + "        ",
                "connection node selection changed after validation for '" + type.name() + "'", fieldStart);
        source.append(indent).append("    }\n")
                .append(indent).append("    ").append(index).append("++;\n")
                .append(indent).append("}\n");
    }

    private static void emitConnectionFailureIf(
            StringBuilder source,
            boolean mysql,
            String indent,
            String condition,
            String message
    ) {
        source.append(indent).append("if (").append(condition).append(") {\n");
        emitConnectionFailure(source, mysql, indent + "    ", message);
        source.append(indent).append("}\n");
    }

    /** Emits a schema-validation failure with the typed AST node's GraphQL source location. */
    private static void emitConnectionFailureAtIf(
            StringBuilder source,
            boolean mysql,
            String indent,
            String condition,
            String message,
            String sourceOffset
    ) {
        source.append(indent).append("if (").append(condition).append(") {\n");
        emitConnectionFailureAt(source, mysql, indent + "    ", message, sourceOffset);
        source.append(indent).append("}\n");
    }

    private static void emitConnectionFailure(StringBuilder source, boolean mysql, String indent, String message) {
        if (mysql) {
            source.append(indent).append("response = DatabaseGraphqlEngine.errorJson(\"")
                    .append(javaString(message)).append("\");\n");
        } else {
            source.append(indent).append("return DatabaseGraphqlEngine.errorJson(\"")
                    .append(javaString(message)).append("\");\n");
        }
    }

    private static void emitInternalFailure(StringBuilder source, boolean mysql, String indent, String message) {
        if (mysql) {
            source.append(indent).append("response = DatabaseGraphqlEngine.internalErrorJson(\"")
                    .append(javaString(message)).append("\");\n");
        } else {
            source.append(indent).append("return DatabaseGraphqlEngine.internalErrorJson(\"")
                    .append(javaString(message)).append("\");\n");
        }
    }

    /** Uses only a retained AST source offset; no HTTP-side parser or source rescan is introduced. */
    private static void emitConnectionFailureAt(
            StringBuilder source,
            boolean mysql,
            String indent,
            String message,
            String sourceOffset
    ) {
        if (mysql) {
            source.append(indent).append("response = DatabaseGraphqlEngine.errorJsonAt(\"")
                    .append(javaString(message)).append("\", query, ").append(sourceOffset).append(");\n");
        } else {
            source.append(indent).append("return DatabaseGraphqlEngine.errorJsonAt(\"")
                    .append(javaString(message)).append("\", query, ").append(sourceOffset).append(");\n");
        }
    }

    private static void emitInternalFailureAt(
            StringBuilder source,
            boolean mysql,
            String indent,
            String message,
            String sourceOffset
    ) {
        if (mysql) {
            source.append(indent).append("response = DatabaseGraphqlEngine.internalErrorJsonAt(\"")
                    .append(javaString(message)).append("\", query, ").append(sourceOffset).append(");\n");
        } else {
            source.append(indent).append("return DatabaseGraphqlEngine.internalErrorJsonAt(\"")
                    .append(javaString(message)).append("\", query, ").append(sourceOffset).append(");\n");
        }
    }

    /** Supports reviewed local/computed and one-hop non-null tuple orderings. */
    private static List<TitanGraphqlRootDocument.RootDocumentSortPath> supportedConnectionCustomOrders(
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root
    ) {
        List<TitanGraphqlRootDocument.RootDocumentSortPath> supported = new ArrayList<>();
        for (TitanGraphqlRootDocument.RootDocumentSortPath sort : root.sortPaths()) {
            TitanGraphqlFieldDocument value = connectionCustomOrderField(document, type, root, sort);
            boolean stableRelation = sort.hops() == 0 || sort.hops() == 1
                    && !connectionRelationPath(document, type, root.name(), sort.name(), sort.path(), "sort")
                            .relation().nullable();
            TitanGraphqlFieldDocument tieBreaker = fieldByColumn(type, connectionCustomOrderTieBreaker(type, sort));
            if (sort.hops() <= 1 && stableRelation && value != null && !value.nullable()
                    && (connectionCustomOrderUsesTextCursor(value) || isLong(value))
                    && tieBreaker != null && !tieBreaker.nullable() && isLong(tieBreaker)) {
                supported.add(sort);
            }
        }
        return List.copyOf(supported);
    }

    private static TitanGraphqlFieldDocument connectionCustomOrderField(
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            TitanGraphqlRootDocument.RootDocumentSortPath sort
    ) {
        if (sort.hops() == 0) {
            return type.fields().stream()
                    .filter(field -> field.name().equals(sort.name()) || field.name().equals(sort.path())
                            || !field.column().isBlank() && field.column().equals(sort.column()))
                    .findFirst().orElse(null);
        }
        if (sort.hops() != 1) return null;
        return connectionRelationPath(document, type, root.name(), sort.name(), sort.path(), "sort").field();
    }

    /** UUID values use the text cursor codec but retain a UUID-specific SQL parameter type. */
    private static boolean connectionCustomOrderUsesTextCursor(TitanGraphqlFieldDocument field) {
        return "String".equals(field.type()) || isUuid(field.type()) || isStringId(field);
    }

    private static String connectionCustomOrderTieBreaker(
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument.RootDocumentSortPath sort
    ) {
        return sort.tieBreaker().isBlank() ? type.primaryKey() : sort.tieBreaker();
    }

    /** Emits literal/JSON variable coercion for the generated {@code [TypeOrderBy!]} input. */
    private static void emitConnectionCustomOrderBinding(
            StringBuilder source,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            String scope,
            List<TitanGraphqlRootDocument.RootDocumentSortPath> supported,
            boolean mysql,
            String indent,
            String nested,
            String guarded,
            String rootStart,
            String customOrder,
            String customOrderPath,
            String customOrderDescending
    ) {
        source.append(indent).append("boolean ").append(customOrder).append(" = false;\n")
                .append(indent).append("String ").append(customOrderPath).append(" = \"\";\n")
                .append(indent).append("boolean ").append(customOrderDescending).append(" = false;\n");
        if (root.sortPaths().isEmpty()) {
            return;
        }
        String raw = scopedLocal("connectionOrderByRaw", scope, "value");
        String count = scopedLocal("connectionOrderByCount", scope, "value");
        String item = scopedLocal("connectionOrderByItem", scope, "value");
        String names = root.sortPaths().stream()
                .map(TitanGraphqlRootDocument.RootDocumentSortPath::name)
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        String inputType = root.type() + "OrderBy";
        source.append(indent).append("String ").append(raw).append(" = DatabaseGraphqlEngine.materializedArgumentValue(materializedArguments, ")
                .append(rootStart).append(", \"orderBy\");\n")
                .append(indent).append("if (").append(guarded).append(raw).append(".length() != 0 && !DatabaseGraphqlEngine.argumentValueIsNull(")
                .append(raw).append(") && !DatabaseGraphqlEngine.argumentVariableTypeIsCompatibleWithNullableArgumentFromAst(query, requestAst, ")
                .append(rootStart).append(", \"orderBy\", \"[").append(javaString(inputType)).append("!]\")) {\n");
        emitConnectionFailure(source, mysql, nested,
                "variable for connection argument 'orderBy' must be declared as [" + inputType + "!] or ["
                        + inputType + "!]!");
        source.append(indent).append("}\n")
                .append(indent).append("if (").append(guarded).append(raw).append(".length() != 0 && !DatabaseGraphqlEngine.argumentValueIsNull(")
                .append(raw).append(")) {\n")
                .append(nested).append("int ").append(count).append(" = DatabaseGraphqlEngine.inputListValueCount(")
                .append(raw).append(");\n")
                .append(nested).append("if (").append(count).append(" < 0 || ").append(count).append(" > 1) {\n");
        emitConnectionFailure(source, mysql, nested + "    ",
                "connection argument 'orderBy' supports at most one reviewed sort path");
        source.append(nested).append("}\n")
                .append(nested).append("if (").append(mysql ? "response.length() == 0 && " : "")
                .append(count).append(" == 1) {\n")
                .append(nested).append("    String ").append(item).append(" = DatabaseGraphqlEngine.inputListValue(")
                .append(raw).append(", 0);\n")
                .append(nested).append("    if (!DatabaseGraphqlEngine.inputObjectHasOnlyFields(").append(item).append(", \"")
                .append(javaString(names)).append("\")) {\n");
        emitConnectionFailure(source, mysql, nested + "        ",
                "connection argument 'orderBy' has an unknown, duplicate, or malformed sort path");
        source.append(nested).append("    }\n");
        for (TitanGraphqlRootDocument.RootDocumentSortPath sort : root.sortPaths()) {
            String directionRaw = scopedLocal("connectionOrderByDirectionRaw", scope, sort.name());
            String direction = scopedLocal("connectionOrderByDirection", scope, sort.name());
            source.append(nested).append("    String ").append(directionRaw)
                    .append(" = DatabaseGraphqlEngine.inputObjectFieldValue(").append(item).append(", \"")
                    .append(javaString(sort.name())).append("\");\n")
                    .append(nested).append("    if (").append(mysql ? "response.length() == 0 && " : "")
                    .append(directionRaw).append(".length() != 0) {\n")
                    .append(nested).append("        if (").append(customOrder).append(") {\n");
            emitConnectionFailure(source, mysql, nested + "            ",
                    "connection argument 'orderBy' supports at most one reviewed sort path");
            source.append(nested).append("        }\n")
                    .append(nested).append("        String ").append(direction)
                    .append(" = DatabaseGraphqlEngine.enumArgument(").append(directionRaw).append(");\n")
                    .append(nested).append("        if (!\"ASC\".equals(").append(direction).append(") && !\"DESC\".equals(")
                    .append(direction).append(")) {\n");
            emitConnectionFailure(source, mysql, nested + "            ",
                    "connection order path '" + sort.name() + "' must be ASC or DESC");
            source.append(nested).append("        }\n");
            if (supported.contains(sort)) {
                source.append(nested).append("        if (").append(mysql ? "response.length() == 0" : "true").append(") {\n")
                        .append(nested).append("            ").append(customOrder).append(" = true;\n")
                        .append(nested).append("            ").append(customOrderPath).append(" = \"")
                        .append(javaString(sort.name())).append("\";\n")
                        .append(nested).append("            ").append(customOrderDescending).append(" = \"DESC\".equals(")
                        .append(direction).append(");\n")
                        .append(nested).append("        }\n");
            } else {
                emitConnectionFailure(source, mysql, nested + "        ",
                        "connection order path '" + sort.name()
                                + "' is not yet supported by the database engine");
            }
            source.append(nested).append("    }\n");
        }
        source.append(nested).append("}\n")
                .append(indent).append("}\n");
    }

    /** Emits metadata-bound String/integral tuple-cursor validation after the order input is known. */
    private static void emitConnectionCustomCursorDecode(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            List<TitanGraphqlRootDocument.RootDocumentSortPath> supported,
            boolean mysql,
            String indent,
            String customOrder,
            String customOrderPath,
            String cursor,
            String stringValue,
            String longValue,
            String tieValue,
            String customOrderDescending,
            String argumentName
    ) {
        if (supported.isEmpty()) return;
        String carrier = local("connectionCustom" + javaTypeName(argumentName) + "CursorCarrier", root.name());
        source.append(indent).append("if (").append(customOrder).append(") {\n")
                .append(indent).append("    String ").append(carrier).append(" = ")
                .append(connectionCustomCursorHelperName(root)).append("(").append(cursor).append(", ")
                .append(customOrderPath).append(", ").append(customOrderDescending).append(");\n")
                .append(indent).append("    if (!DatabaseGraphqlEngine.connectionCursorCarrierIsValid(")
                .append(carrier).append(")) {\n");
        emitConnectionFailure(source, mysql, indent + "        ",
                "connection argument '" + argumentName + "' is not a cursor for the selected root ordering");
        source.append(indent).append("    }\n")
                .append(indent).append("    if (").append(mysql ? "response.length() == 0" : "true").append(") {\n")
                .append(indent).append("        ").append(stringValue)
                .append(" = DatabaseGraphqlEngine.connectionCursorCarrierStringValue(").append(carrier).append(");\n")
                .append(indent).append("        ").append(longValue)
                .append(" = DatabaseGraphqlEngine.connectionCursorCarrierLongValue(").append(carrier).append(");\n")
                .append(indent).append("        ").append(tieValue)
                .append(" = DatabaseGraphqlEngine.connectionCursorCarrierTieValue(").append(carrier).append(");\n")
                .append(indent).append("    }\n")
                .append(indent).append("}\n");
    }

    private static List<TitanGraphqlFilterLayout.Binding> supportedConnectionFilters(
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root
    ) {
        List<TitanGraphqlFilterLayout.Binding> supported = new ArrayList<>();
        for (TitanGraphqlFilterLayout.Binding candidate : TitanGraphqlFilterLayout.bindings(document, type, root)) {
            if (candidate.hops() > 1 || !connectionFilterTypeSupported(document, candidate)
                    || !connectionFilterOperatorSupported(document, candidate)) {
                continue;
            }
            supported.add(candidate);
        }
        return List.copyOf(supported);
    }

    /** Returns all arguments exposed by one reviewed Relay connection in AST-name form. */
    private static String connectionAllowedArgumentNames(
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root
    ) {
        String names = "first,after,last,before";
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : root.arguments()) {
            names = names + "," + argument.name();
        }
        if (!supportedConnectionFilters(document, type, root).isEmpty()) {
            names = names + ",filter";
        }
        if (!root.sortPaths().isEmpty()) {
            names = names + ",orderBy";
        }
        return names;
    }

    /**
     * Encodes optional Relay argument locations for shared typed-AST validation. This is emitted
     * schema metadata, rather than a per-model runtime branch or a frontend validation path.
     */
    private static String connectionOptionalArgumentTypes(
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root
    ) {
        String descriptor = "first=Int,after=String,last=Int,before=String";
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : root.arguments()) {
            descriptor = descriptor + "," + argument.name() + "=" + argument.type();
        }
        if (!supportedConnectionFilters(document, type, root).isEmpty()) {
            descriptor = descriptor + ",filter=" + type.name() + "Filter";
        }
        if (!root.sortPaths().isEmpty()) {
            descriptor = descriptor + ",orderBy=[" + type.name() + "OrderBy!]";
        }
        return descriptor;
    }

    private static boolean connectionFilterTypeSupported(
            TitanGraphqlModelDocument document,
            TitanGraphqlFilterLayout.Binding filter
    ) {
        String type = filter.graphqlType();
        return isLong(filter) || isBoolean(type) || isDecimal(type) || isUuid(type)
                || "String".equals(type) || "Date".equals(type) || "DateTime".equals(type)
                || "Timestamp".equals(type) || isId(type) || enumType(document, inputNamedType(type)) != null;
    }

    private static boolean connectionFilterOperatorSupported(
            TitanGraphqlModelDocument document,
            TitanGraphqlFilterLayout.Binding filter
    ) {
        String operator = filter.operator();
        if (enumType(document, inputNamedType(filter.graphqlType())) != null) {
            return operator.equals("eq") || operator.equals("neq") || operator.equals("in")
                    || operator.equals("isnull");
        }
        if (operator.equals("in")) {
            return connectionFilterInTypeSupported(document, filter);
        }
        if (operator.equals("contains") || operator.equals("startswith") || operator.equals("endswith")) {
            return connectionFilterStringType(filter);
        }
        return operator.equals("eq") || operator.equals("neq") || operator.equals("lt")
                || operator.equals("lte") || operator.equals("gt") || operator.equals("gte")
                || operator.equals("isnull");
    }

    private static boolean connectionFilterStringType(TitanGraphqlFilterLayout.Binding filter) {
        String graphqlType = filter.graphqlType();
        return "String".equals(graphqlType)
                || (isId(graphqlType)
                && filter.idStorage() == TitanGraphqlFieldDocument.FieldDocumentIdStorage.STRING)
                || "Date".equals(graphqlType) || "DateTime".equals(graphqlType)
                || "Timestamp".equals(graphqlType);
    }

    /**
     * The SQL predicate is the same for every reviewed scalar list; the generated local and JDBC
     * setter must nevertheless follow the declared GraphQL scalar and, for {@code ID}, its reviewed
     * physical representation. This keeps a request value out of the SQL text and prevents a
     * client from selecting a JDBC type.
     */
    private static boolean connectionFilterInTypeSupported(
            TitanGraphqlModelDocument document,
            TitanGraphqlFilterLayout.Binding filter
    ) {
        String graphqlType = filter.graphqlType();
        return connectionFilterStringType(filter) || isLong(filter) || isBoolean(graphqlType)
                || isDecimal(graphqlType) || isUuid(graphqlType)
                || enumType(document, inputNamedType(graphqlType)) != null;
    }

    private static boolean connectionFilterUsesBooleanValue(TitanGraphqlFilterLayout.Binding filter) {
        return filter.operator().equals("isnull") || isBoolean(filter.graphqlType());
    }

    private static boolean connectionFilterNeedsRepeatedValue(TitanGraphqlFilterLayout.Binding filter) {
        return filter.operator().equals("startswith") || filter.operator().equals("endswith");
    }

    private static String connectionFilterLocal(
            String prefix,
            String scope,
            TitanGraphqlFilterLayout.Binding filter
    ) {
        return scopedLocal(prefix, scope, filter.fieldName() + "_" + filter.operator());
    }

    private static String connectionFilterInItemLocal(
            String prefix,
            String scope,
            TitanGraphqlFilterLayout.Binding filter,
            int index
    ) {
        return scopedLocal(prefix, scope, filter.fieldName() + "_" + filter.operator() + "_" + index);
    }

    private static String connectionFilterFieldNames(List<TitanGraphqlFilterLayout.Binding> filters) {
        return String.join(",", connectionFilterFieldNamesList(filters));
    }

    private static List<String> connectionFilterFieldNamesList(List<TitanGraphqlFilterLayout.Binding> filters) {
        List<String> names = new ArrayList<>();
        for (TitanGraphqlFilterLayout.Binding filter : filters) {
            if (!names.contains(filter.fieldName())) {
                names.add(filter.fieldName());
            }
        }
        return List.copyOf(names);
    }

    private static List<TitanGraphqlFilterLayout.Binding> connectionFiltersForField(
            List<TitanGraphqlFilterLayout.Binding> filters,
            String fieldName
    ) {
        List<TitanGraphqlFilterLayout.Binding> result = new ArrayList<>();
        for (TitanGraphqlFilterLayout.Binding filter : filters) {
            if (filter.fieldName().equals(fieldName)) {
                result.add(filter);
            }
        }
        return List.copyOf(result);
    }

    private static String connectionFilterOperatorNames(List<TitanGraphqlFilterLayout.Binding> filters) {
        List<String> names = new ArrayList<>();
        for (TitanGraphqlFilterLayout.Binding filter : filters) {
            String graphqlName = graphqlFilterOperatorName(filter.operator());
            if (!names.contains(graphqlName)) {
                names.add(graphqlName);
            }
        }
        return String.join(",", names);
    }

    private static String graphqlFilterOperatorName(String normalizedOperator) {
        return switch (normalizedOperator) {
            case "isnull" -> "isNull";
            case "startswith" -> "startsWith";
            case "endswith" -> "endsWith";
            default -> normalizedOperator;
        };
    }

    /** Emits the model-specialized 3x3 DNF carrier consumed by constant generated SQL. */
    private static void emitConnectionFilterBindings(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            String scope,
            String rootStart,
            boolean mysql,
            String indent,
            String nested,
            String guarded,
            List<TitanGraphqlFilterLayout.Binding> filters,
            boolean extractedBinding
    ) {
        if (filters.isEmpty()) {
            return;
        }
        String rawFilter = scopedLocal("connectionFilterRaw", scope, "value");
        String filterPlan = scopedLocal("connectionFilterPlan", scope, "value");
        String filterType = root.type() + "Filter";
        source.append(indent).append("String ").append(rawFilter).append(" = DatabaseGraphqlEngine.materializedArgumentValue(materializedArguments, ")
                .append(rootStart).append(", \"filter\");\n")
                .append(indent).append("if (").append(guarded).append(rawFilter)
                .append(".length() != 0 && !DatabaseGraphqlEngine.argumentValueIsNull(").append(rawFilter)
                .append(") && !DatabaseGraphqlEngine.argumentVariableTypeIsCompatibleWithNullableArgumentFromAst(query, requestAst, ")
                .append(rootStart).append(", \"filter\", \"").append(javaString(filterType)).append("\")) {\n");
        emitConnectionFailure(source, mysql, nested,
                "variable for connection argument 'filter' must be declared as " + filterType + " or " + filterType + "!");
        source.append(indent).append("}\n")
                .append(indent).append("String ").append(filterPlan)
                .append(" = DatabaseGraphqlEngine.generatedFilterPlan(").append(rawFilter).append(", \"")
                .append(javaString(connectionFilterDescriptor(filters))).append("\");\n")
                .append(indent).append("if (").append(guarded)
                .append("DatabaseGraphqlEngine.filterPlanIsFailure(").append(filterPlan).append(")) {\n");
        if (mysql) {
            source.append(nested).append("response = DatabaseGraphqlEngine.errorJsonAt(")
                    .append("DatabaseGraphqlEngine.filterPlanFailureMessage(").append(filterPlan)
                    .append("), query, ").append(rootStart).append(");\n");
        } else {
            source.append(nested).append("return DatabaseGraphqlEngine.errorJsonAt(")
                    .append("DatabaseGraphqlEngine.filterPlanFailureMessage(").append(filterPlan)
                    .append("), query, ").append(rootStart).append(");\n");
        }
        source.append(indent).append("}\n");
        if (extractedBinding) {
            return;
        }
        for (int group = 0; group < MAX_FILTER_PLAN_GROUPS; group++) {
            String active = connectionFilterPlanLocal("connectionFilterGroupActive", scope, group, -1);
            source.append(indent).append("boolean ").append(active)
                    .append(" = DatabaseGraphqlEngine.filterPlanGroupActive(").append(filterPlan).append(", ")
                    .append(group).append(");\n");
            for (int term = 0; term < MAX_FILTER_PLAN_TERMS_PER_GROUP; term++) {
                emitConnectionFilterPlanSlot(
                        source, document, scope, mysql, indent, nested, guarded, filterPlan,
                        rootStart, filters, group, term);
            }
        }
    }

    private static String connectionFilterDescriptor(List<TitanGraphqlFilterLayout.Binding> filters) {
        String descriptor = "";
        for (TitanGraphqlFilterLayout.Binding filter : filters) {
            descriptor = descriptor + (descriptor.isEmpty() ? "" : ",") + filter.fieldName() + "."
                    + graphqlFilterOperatorName(filter.operator()) + "=" + filter.selector();
        }
        return descriptor;
    }

    private static String connectionFilterPlanLocal(
            String prefix,
            String scope,
            int group,
            int term
    ) {
        return scopedLocal(prefix, scope, term < 0 ? "g" + group : "g" + group + "t" + term);
    }

    /** Declares and validates one statically typed filter carrier slot. */
    private static void emitConnectionFilterPlanSlot(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            String scope,
            boolean mysql,
            String indent,
            String nested,
            String guarded,
            String filterPlan,
            String rootStart,
            List<TitanGraphqlFilterLayout.Binding> filters,
            int group,
            int term
    ) {
        String selector = connectionFilterPlanLocal("connectionFilterSelector", scope, group, term);
        String negated = connectionFilterPlanLocal("connectionFilterNegated", scope, group, term);
        String nullValue = connectionFilterPlanLocal("connectionFilterNull", scope, group, term);
        String raw = connectionFilterPlanLocal("connectionFilterRaw", scope, group, term);
        String booleanValue = connectionFilterPlanLocal("connectionFilterBoolean", scope, group, term);
        String longValue = connectionFilterPlanLocal("connectionFilterLong", scope, group, term);
        String decimalValue = connectionFilterPlanLocal("connectionFilterDecimal", scope, group, term);
        String stringValue = connectionFilterPlanLocal("connectionFilterString", scope, group, term);
        source.append(indent).append("int ").append(selector)
                .append(" = DatabaseGraphqlEngine.filterPlanTermSelector(").append(filterPlan).append(", ")
                .append(group).append(", ").append(term).append(");\n")
                .append(indent).append("boolean ").append(negated)
                .append(" = DatabaseGraphqlEngine.filterPlanTermNegated(").append(filterPlan).append(", ")
                .append(group).append(", ").append(term).append(");\n")
                .append(indent).append("boolean ").append(nullValue)
                .append(" = DatabaseGraphqlEngine.filterPlanTermNull(").append(filterPlan).append(", ")
                .append(group).append(", ").append(term).append(");\n")
                .append(indent).append("String ").append(raw)
                .append(" = DatabaseGraphqlEngine.filterPlanTermValue(").append(filterPlan).append(", ")
                .append(group).append(", ").append(term).append(");\n")
                .append(indent).append("boolean ").append(booleanValue).append(" = false;\n")
                .append(indent).append("long ").append(longValue).append(" = 0L;\n")
                .append(indent).append("double ").append(decimalValue).append(" = 0.0;\n")
                .append(indent).append("String ").append(stringValue).append(" = \"\";\n");
        emitConnectionFilterSlotDecode(source, indent, guarded, nullValue,
                connectionFilterSelectorCondition(document, filters, selector, "boolean"),
                booleanValue + " = DatabaseGraphqlEngine.booleanArgument(" + raw + ") == 1;");
        emitConnectionFilterSlotDecode(source, indent, guarded, nullValue,
                connectionFilterSelectorCondition(document, filters, selector, "integralId"),
                longValue + " = DatabaseGraphqlEngine.integralIdArgument(" + raw + ");");
        emitConnectionFilterSlotDecode(source, indent, guarded, nullValue,
                connectionFilterSelectorCondition(document, filters, selector, "long"),
                longValue + " = DatabaseGraphqlEngine.longArgument(" + raw + ");");
        emitConnectionFilterSlotDecode(source, indent, guarded, nullValue,
                connectionFilterSelectorCondition(document, filters, selector, "decimal"),
                decimalValue + " = DatabaseGraphqlEngine.decimalArgument(" + raw + ");");
        emitConnectionFilterSlotDecode(source, indent, guarded, nullValue,
                connectionFilterSelectorCondition(document, filters, selector, "enum"),
                stringValue + " = DatabaseGraphqlEngine.enumArgument(" + raw + ");");
        emitConnectionFilterSlotDecode(source, indent, guarded, nullValue,
                connectionFilterSelectorCondition(document, filters, selector, "stringId"),
                stringValue + " = DatabaseGraphqlEngine.stringIdArgument(" + raw + ");");
        emitConnectionFilterSlotDecode(source, indent, guarded, nullValue,
                connectionFilterSelectorCondition(document, filters, selector, "string"),
                stringValue + " = DatabaseGraphqlEngine.stringArgument(" + raw + ");");
    }

    private static void emitConnectionFilterSlotDecode(
            StringBuilder source,
            String indent,
            String guarded,
            String nullValue,
            String selectorCondition,
            String assignment
    ) {
        if (selectorCondition.equals("false")) return;
        source.append(indent).append("if (").append(guarded).append("!").append(nullValue)
                .append(" && (").append(selectorCondition).append(")) ").append(assignment).append("\n");
    }

    private static String connectionFilterSelectorCondition(
            TitanGraphqlModelDocument document,
            List<TitanGraphqlFilterLayout.Binding> filters,
            String selector,
            String kind
    ) {
        List<String> conditions = new ArrayList<>();
        for (TitanGraphqlFilterLayout.Binding filter : filters) {
            TitanGraphqlEnumDocument inputEnum = enumType(document, inputNamedType(filter.graphqlType()));
            boolean matches = switch (kind) {
                case "boolean" -> connectionFilterUsesBooleanValue(filter);
                case "integralId" -> isLong(filter) && isId(filter.graphqlType());
                case "long" -> isLong(filter) && !isId(filter.graphqlType());
                case "decimal" -> isDecimal(filter.graphqlType());
                case "enum" -> inputEnum != null && !filter.operator().equals("isnull");
                case "stringId" -> isId(filter.graphqlType()) && !isLong(filter);
                case "string" -> inputEnum == null && !connectionFilterUsesBooleanValue(filter)
                        && !isLong(filter) && !isDecimal(filter.graphqlType()) && !isId(filter.graphqlType());
                default -> false;
            };
            if (matches) conditions.add(selector + " == " + filter.selector());
        }
        return conditions.isEmpty() ? "false" : String.join(" || ", conditions);
    }

    private static void emitConnectionFilterBinding(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            String scope,
            boolean mysql,
            String indent,
            String nested,
            String guarded,
            String fieldRaw,
            TitanGraphqlFilterLayout.Binding filter
    ) {
        String raw = connectionFilterLocal("connectionFilterValueRaw", scope, filter);
        String present = connectionFilterLocal("connectionFilterPresent", scope, filter);
        String value = connectionFilterLocal("connectionFilterValue", scope, filter);
        source.append(indent).append("String ").append(raw).append(" = DatabaseGraphqlEngine.inputObjectFieldValue(")
                .append(fieldRaw).append(", \"")
                .append(javaString(graphqlFilterOperatorName(filter.operator())))
                .append("\");\n")
                .append(indent).append("boolean ").append(present).append(" = false;\n");
        if (filter.operator().equals("in")) {
            emitConnectionInFilterBinding(
                    source, document, scope, mysql, indent, nested, guarded, raw, present, filter);
            return;
        }
        TitanGraphqlEnumDocument inputEnum = enumType(document, inputNamedType(filter.graphqlType()));
        // `isNull` describes the nullable state of a field, not a value of the field's declared
        // type. Keep it on the shared Boolean coercion/binding path even when the field itself is
        // an enum; only value-bearing enum operators are decoded as enum tokens.
        if (inputEnum != null && !filter.operator().equals("isnull")) {
            source.append(indent).append("String ").append(value).append(" = DatabaseGraphqlEngine.enumArgument(")
                    .append(raw).append(");\n")
                    .append(indent).append("if (").append(guarded).append(raw).append(".length() != 0) {\n")
                    .append(nested).append("if (!DatabaseGraphqlEngine.enumValueIsAllowed(").append(value)
                    .append(", \"").append(javaString(String.join("|", sortedEnumValues(inputEnum)))).append("\")) {\n");
            emitConnectionFailure(source, mysql, nested + "    ", "connection filter '"
                    + filter.fieldName() + "." + filter.operator() + "' must be a "
                    + inputEnum.name() + " enum value");
            source.append(nested).append("}\n")
                    .append(nested).append("if (").append(mysql ? "response.length() == 0" : "true").append(") {\n")
                    .append(nested).append("    ").append(present).append(" = true;\n")
                    .append(nested).append("}\n")
                    .append(indent).append("}\n");
            return;
        }
        if (connectionFilterUsesBooleanValue(filter)) {
            source.append(indent).append("boolean ").append(value).append(" = false;\n")
                    .append(indent).append("if (").append(guarded).append(raw).append(".length() != 0) {\n")
                    .append(nested).append("int suppliedFilterValue = DatabaseGraphqlEngine.booleanArgument(")
                    .append(raw).append(");\n")
                    .append(nested).append("if (").append(mysql ? "response.length() == 0 && " : "")
                    .append("suppliedFilterValue < 0) {\n");
            emitConnectionFailure(source, mysql, nested + "    ", "connection filter '"
                    + filter.fieldName() + "." + filter.operator() + "' must be a boolean");
            source.append(nested).append("}\n")
                    .append(nested).append("if (").append(mysql ? "response.length() == 0" : "true").append(") {\n")
                    .append(nested).append("    ").append(present).append(" = true;\n")
                    .append(nested).append("    ").append(value).append(" = suppliedFilterValue == 1;\n")
                    .append(nested).append("}\n")
                    .append(indent).append("}\n");
            return;
        }
        if (isLong(filter)) {
            source.append(indent).append("long ").append(value).append(" = 0L;\n")
                    .append(indent).append("if (").append(guarded).append(raw).append(".length() != 0) {\n")
                    .append(nested).append("long suppliedFilterValue = DatabaseGraphqlEngine.")
                    .append(isId(filter.graphqlType()) ? "integralIdArgument" : "longArgument")
                    .append("(").append(raw).append(");\n")
                    .append(nested).append("if (").append(mysql ? "response.length() == 0 && " : "")
                    .append(isId(filter.graphqlType())
                            ? "!DatabaseGraphqlEngine.idArgumentIsValid(" + raw
                                    + ") || DatabaseGraphqlEngine.invalidLong(suppliedFilterValue)"
                            : "DatabaseGraphqlEngine.invalidIntegerForScalar(suppliedFilterValue, \""
                                    + javaString(filter.graphqlType()) + "\")")
                    .append(") {\n");
            emitConnectionFailure(source, mysql, nested + "    ", "connection filter '"
                    + filter.fieldName() + "." + filter.operator() + "' must be a "
                    + (isId(filter.graphqlType()) ? "numeric ID" : "integer"));
            source.append(nested).append("}\n")
                    .append(nested).append("if (").append(mysql ? "response.length() == 0" : "true").append(") {\n")
                    .append(nested).append("    ").append(present).append(" = true;\n")
                    .append(nested).append("    ").append(value).append(" = suppliedFilterValue;\n")
                    .append(nested).append("}\n")
                    .append(indent).append("}\n");
            return;
        }
        if (isDecimal(filter.graphqlType())) {
            source.append(indent).append("double ").append(value).append(" = 0.0;\n")
                    .append(indent).append("if (").append(guarded).append(raw).append(".length() != 0) {\n")
                    .append(nested).append("if (!DatabaseGraphqlEngine.decimalArgumentIsValid(").append(raw).append(")) {\n");
            emitConnectionFailure(source, mysql, nested + "    ", "connection filter '"
                    + filter.fieldName() + "." + filter.operator() + "' must be a number");
            source.append(nested).append("}\n")
                    .append(nested).append("if (").append(mysql ? "response.length() == 0" : "true").append(") {\n")
                    .append(nested).append("    ").append(present).append(" = true;\n")
                    .append(nested).append("    ").append(value).append(" = DatabaseGraphqlEngine.decimalArgument(").append(raw).append(");\n")
                    .append(nested).append("}\n")
                    .append(indent).append("}\n");
            return;
        }
        source.append(indent).append("String ").append(value).append(" = DatabaseGraphqlEngine.")
                .append(isId(filter.graphqlType()) ? "stringIdArgument" : "stringArgument")
                .append("(")
                .append(raw).append(");\n")
                .append(indent).append("if (").append(guarded).append(raw).append(".length() != 0) {\n")
                .append(nested).append("if (!DatabaseGraphqlEngine.")
                .append(isId(filter.graphqlType()) ? "idArgumentIsValid" : "stringArgumentIsValid")
                .append("(").append(raw).append(")");
        if (isUuid(filter.graphqlType())) {
            source.append(" || !DatabaseGraphqlEngine.uuidArgumentIsValid(").append(raw).append(")");
        }
        source.append(") {\n");
        emitConnectionFailure(source, mysql, nested + "    ", "connection filter '"
                + filter.fieldName() + "." + filter.operator() + "' must be a "
                + (isUuid(filter.graphqlType()) ? "UUID" : isId(filter.graphqlType()) ? "ID" : "string"));
        source.append(nested).append("}\n")
                .append(nested).append("if (").append(mysql ? "response.length() == 0" : "true").append(") {\n")
                .append(nested).append("    ").append(present).append(" = true;\n")
                .append(nested).append("}\n")
                .append(indent).append("}\n");
    }

    private static void emitConnectionInFilterBinding(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            String scope,
            boolean mysql,
            String indent,
            String nested,
            String guarded,
            String raw,
            String present,
            TitanGraphqlFilterLayout.Binding filter
    ) {
        TitanGraphqlEnumDocument inputEnum = enumType(document, inputNamedType(filter.graphqlType()));
        String count = connectionFilterLocal("connectionFilterInCount", scope, filter);
        source.append(indent).append("int ").append(count).append(" = -1;\n")
                .append(indent).append("if (").append(guarded).append(raw).append(".length() != 0")
                .append(" && !DatabaseGraphqlEngine.argumentValueIsNull(").append(raw).append(")) {\n")
                .append(nested).append(count).append(" = DatabaseGraphqlEngine.inputListValueCount(").append(raw).append(");\n")
                .append(nested).append("if (").append(count).append(" < 0 || ").append(count)
                .append(" > ").append(MAX_STATIC_IN_FILTER_VALUES).append(") {\n");
        emitConnectionFailure(source, mysql, nested + "    ", "connection filter '"
                + filter.fieldName() + ".in' must be a list with at most " + MAX_STATIC_IN_FILTER_VALUES
                + " " + (inputEnum == null ? connectionFilterInValueDescription(filter) : inputEnum.name() + " enum")
                + " values");
        source.append(nested).append("}\n")
                .append(nested).append("if (").append(mysql ? "response.length() == 0" : "true").append(") {\n")
                .append(nested).append("    ").append(present).append(" = true;\n")
                .append(nested).append("}\n")
                .append(indent).append("}\n");
        for (int index = 0; index < MAX_STATIC_IN_FILTER_VALUES; index++) {
            String itemRaw = connectionFilterInItemLocal("connectionFilterInItemRaw", scope, filter, index);
            String itemPresent = connectionFilterInItemLocal("connectionFilterInItemPresent", scope, filter, index);
            String itemValue = connectionFilterInItemLocal("connectionFilterInItemValue", scope, filter, index);
            source.append(indent).append("String ").append(itemRaw).append(" = DatabaseGraphqlEngine.inputListValue(")
                    .append(raw).append(", ").append(index).append(");\n")
                    .append(indent).append("boolean ").append(itemPresent).append(" = false;\n");
            if (inputEnum != null) {
                source.append(indent).append("String ").append(itemValue)
                        .append(" = DatabaseGraphqlEngine.enumArgument(").append(itemRaw).append(");\n")
                        .append(indent).append("if (").append(guarded).append(present).append(" && ")
                        .append(index).append(" < ").append(count).append(") {\n")
                        .append(nested).append("if (!DatabaseGraphqlEngine.enumValueIsAllowed(").append(itemValue)
                        .append(", \"").append(javaString(String.join("|", sortedEnumValues(inputEnum))))
                        .append("\")) {\n");
                emitConnectionFailure(source, mysql, nested + "    ", "connection filter '"
                        + filter.fieldName() + ".in' must contain only " + inputEnum.name() + " enum values");
            } else if (isLong(filter)) {
                source.append(indent).append("long ").append(itemValue).append(" = DatabaseGraphqlEngine.")
                        .append(isId(filter.graphqlType()) ? "integralIdArgument(" : "longArgument(")
                        .append(itemRaw).append(");\n")
                        .append(indent).append("if (").append(guarded).append(present).append(" && ")
                        .append(index).append(" < ").append(count).append(") {\n")
                        .append(nested).append("if (")
                        .append(isId(filter.graphqlType())
                                ? "!DatabaseGraphqlEngine.idArgumentIsValid(" + itemRaw
                                        + ") || DatabaseGraphqlEngine.invalidLong(" + itemValue + ")"
                                : "DatabaseGraphqlEngine.invalidIntegerForScalar(" + itemValue + ", \""
                                        + javaString(filter.graphqlType()) + "\")")
                        .append(") {\n");
                emitConnectionFailure(source, mysql, nested + "    ", "connection filter '"
                        + filter.fieldName() + ".in' must contain only "
                        + connectionFilterInValueDescription(filter) + " values");
            } else if (isBoolean(filter.graphqlType())) {
                String booleanValue = connectionFilterInItemLocal("connectionFilterInItemBoolean", scope, filter, index);
                source.append(indent).append("int ").append(booleanValue).append(" = DatabaseGraphqlEngine.booleanArgument(")
                        .append(itemRaw).append(");\n")
                        .append(indent).append("boolean ").append(itemValue).append(" = false;\n")
                        .append(indent).append("if (").append(guarded).append(present).append(" && ")
                        .append(index).append(" < ").append(count).append(") {\n")
                        .append(nested).append("if (").append(booleanValue).append(" < 0) {\n");
                emitConnectionFailure(source, mysql, nested + "    ", "connection filter '"
                        + filter.fieldName() + ".in' must contain only Boolean values");
                source.append(nested).append("}\n")
                        .append(nested).append("if (").append(mysql ? "response.length() == 0" : "true").append(") {\n")
                        .append(nested).append("    ").append(itemPresent).append(" = true;\n")
                        .append(nested).append("    ").append(itemValue).append(" = ").append(booleanValue)
                        .append(" == 1;\n")
                        .append(nested).append("}\n")
                        .append(indent).append("}\n");
                continue;
            } else if (isDecimal(filter.graphqlType())) {
                source.append(indent).append("double ").append(itemValue).append(" = 0.0;\n")
                        .append(indent).append("if (").append(guarded).append(present).append(" && ")
                        .append(index).append(" < ").append(count).append(") {\n")
                        .append(nested).append("if (!DatabaseGraphqlEngine.decimalArgumentIsValid(").append(itemRaw)
                        .append(")) {\n");
                emitConnectionFailure(source, mysql, nested + "    ", "connection filter '"
                        + filter.fieldName() + ".in' must contain only number values");
                source.append(nested).append("}\n")
                        .append(nested).append("if (").append(mysql ? "response.length() == 0" : "true").append(") {\n")
                        .append(nested).append("    ").append(itemPresent).append(" = true;\n")
                        .append(nested).append("    ").append(itemValue).append(" = DatabaseGraphqlEngine.decimalArgument(")
                        .append(itemRaw).append(");\n")
                        .append(nested).append("}\n")
                        .append(indent).append("}\n");
                continue;
            } else {
                source.append(indent).append("String ").append(itemValue).append(" = DatabaseGraphqlEngine.")
                        .append(isId(filter.graphqlType()) ? "stringIdArgument" : "stringArgument")
                        .append("(").append(itemRaw).append(");\n")
                        .append(indent).append("if (").append(guarded).append(present).append(" && ")
                        .append(index).append(" < ").append(count).append(") {\n")
                        .append(nested).append("if (!DatabaseGraphqlEngine.")
                        .append(isId(filter.graphqlType()) ? "idArgumentIsValid" : "stringArgumentIsValid")
                        .append("(").append(itemRaw).append(")");
                if (isUuid(filter.graphqlType())) {
                    source.append(" || !DatabaseGraphqlEngine.uuidArgumentIsValid(").append(itemRaw).append(")");
                }
                source.append(") {\n");
                emitConnectionFailure(source, mysql, nested + "    ", "connection filter '"
                        + filter.fieldName() + ".in' must contain only "
                        + connectionFilterInValueDescription(filter) + " values");
            }
            source.append(nested).append("}\n")
                    .append(nested).append("if (").append(mysql ? "response.length() == 0" : "true").append(") {\n")
                    .append(nested).append("    ").append(itemPresent).append(" = true;\n")
                    .append(nested).append("}\n")
                    .append(indent).append("}\n");
        }
    }

    private static String connectionFilterInValueDescription(TitanGraphqlFilterLayout.Binding filter) {
        if (isId(filter.graphqlType())) {
            return isLong(filter) ? "integral ID" : "ID";
        }
        if (isBoolean(filter.graphqlType())) {
            return "Boolean";
        }
        if (isDecimal(filter.graphqlType())) {
            return "number";
        }
        if (isUuid(filter.graphqlType())) {
            return "UUID";
        }
        if (isLong(filter)) {
            return "integer";
        }
        return "string";
    }

    private static int emitConnectionFilterParameters(
            StringBuilder source,
            String statement,
            int parameter,
            String scope,
            List<TitanGraphqlFilterLayout.Binding> filters,
            String indent,
            boolean extractedBinding,
            TitanGraphqlRootDocument root
    ) {
        int next = parameter;
        if (filters.isEmpty()) {
            return next;
        }
        if (extractedBinding) {
            source.append(indent).append(connectionFilterBinderHelperName(root)).append("(")
                    .append(statement).append(", ")
                    .append(scopedLocal("connectionFilterPlan", scope, "value")).append(");\n");
            return next + MAX_FILTER_PLAN_GROUPS
                    * (1 + MAX_FILTER_PLAN_TERMS_PER_GROUP * 7);
        }
        for (int group = 0; group < MAX_FILTER_PLAN_GROUPS; group++) {
            source.append(indent).append(statement).append(".setBoolean(").append(next++)
                    .append(", ").append(connectionFilterPlanLocal(
                            "connectionFilterGroupActive", scope, group, -1)).append(");\n");
            for (int term = 0; term < MAX_FILTER_PLAN_TERMS_PER_GROUP; term++) {
                source.append(indent).append(statement).append(".setInt(").append(next++).append(", ")
                        .append(connectionFilterPlanLocal("connectionFilterSelector", scope, group, term))
                        .append(");\n")
                        .append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                        .append(connectionFilterPlanLocal("connectionFilterNegated", scope, group, term))
                        .append(");\n")
                        .append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                        .append(connectionFilterPlanLocal("connectionFilterNull", scope, group, term))
                        .append(");\n")
                        .append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                        .append(connectionFilterPlanLocal("connectionFilterBoolean", scope, group, term))
                        .append(");\n")
                        .append(indent).append(statement).append(".setLong(").append(next++).append(", ")
                        .append(connectionFilterPlanLocal("connectionFilterLong", scope, group, term))
                        .append(");\n")
                        .append(indent).append(statement).append(".setDouble(").append(next++).append(", ")
                        .append(connectionFilterPlanLocal("connectionFilterDecimal", scope, group, term))
                        .append(");\n")
                        .append(indent).append(statement).append(".setString(").append(next++).append(", ")
                        .append(connectionFilterPlanLocal("connectionFilterString", scope, group, term))
                        .append(");\n");
            }
        }
        return next;
    }

    /** Emits every bind for the constant SQL default-or-custom connection plan. */
    private static int emitConnectionCursorParameters(
            StringBuilder source,
            String statement,
            int parameter,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument type,
            List<TitanGraphqlRootDocument.RootDocumentSortPath> customOrders,
            String customOrder,
            String customOrderPath,
            String customOrderDescending,
            String afterPresent,
            String afterValue,
            String customAfterStringValue,
            String customAfterLongValue,
            String customAfterTieValue,
            String beforePresent,
            String beforeValue,
            String customBeforeStringValue,
            String customBeforeLongValue,
            String customBeforeTieValue,
            String backward,
            String pageSize,
            String indent,
            boolean extractedBinding
    ) {
        int next = parameter;
        if (extractedBinding) {
            emitConnectionCursorBinderCall(source, indent, connectionPageCursorBinderHelperName(root), statement,
                    customOrder, customOrderPath, customOrderDescending,
                    afterPresent, afterValue, customAfterStringValue, customAfterLongValue, customAfterTieValue,
                    beforePresent, beforeValue, customBeforeStringValue, customBeforeLongValue, customBeforeTieValue,
                    backward, pageSize);
            return emitConnectionCursorParameters(new StringBuilder(), statement, parameter, document, root, type,
                    customOrders, customOrder, customOrderPath, customOrderDescending,
                    afterPresent, afterValue, customAfterStringValue, customAfterLongValue, customAfterTieValue,
                    beforePresent, beforeValue, customBeforeStringValue, customBeforeLongValue, customBeforeTieValue,
                    backward, pageSize, indent, false);
        }
        if (customOrders.isEmpty()) {
            source.append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                    .append(afterPresent).append(");\n")
                    .append(indent).append(statement).append(".setLong(").append(next++).append(", ")
                    .append(afterValue).append(");\n")
                    .append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                    .append(beforePresent).append(");\n")
                    .append(indent).append(statement).append(".setLong(").append(next++).append(", ")
                    .append(beforeValue).append(");\n");
            for (int index = 0; index < 2; index++) {
                source.append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                        .append(backward).append(");\n");
            }
            source.append(indent).append(statement).append(".setLong(").append(next++).append(", ")
                    .append(pageSize).append(" + 1L);\n");
            return next;
        }
        // Default cursor predicate, enabled precisely when no custom sort path was selected.
        source.append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                .append(customOrder).append(");\n")
                .append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                .append(afterPresent).append(");\n")
                .append(indent).append(statement).append(".setLong(").append(next++).append(", ")
                .append(afterValue).append(");\n")
                .append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                .append(beforePresent).append(");\n")
                .append(indent).append(statement).append(".setLong(").append(next++).append(", ")
                .append(beforeValue).append(");\n");
        for (TitanGraphqlRootDocument.RootDocumentSortPath sort : customOrders) {
            String pathActive = customOrderPath + ".equals(\"" + javaString(sort.name()) + "\")";
            String value = connectionCustomOrderCursorValue(
                    document, type, root, sort, customAfterStringValue, customAfterLongValue);
            String valueSetter = connectionCustomOrderCursorSetter(document, type, root, sort);
            source.append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                    .append(customOrder).append(");\n")
                    .append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                    .append(pathActive).append(");\n");
            next = emitConnectionCustomTupleCursorParameters(source, statement, next, afterPresent,
                    customOrderDescending, valueSetter, value, customAfterTieValue, indent);
            value = connectionCustomOrderCursorValue(
                    document, type, root, sort, customBeforeStringValue, customBeforeLongValue);
            next = emitConnectionCustomTupleCursorParameters(source, statement, next, beforePresent,
                    customOrderDescending, valueSetter, value, customBeforeTieValue, indent);
        }
        // Default ORDER BY has a forward and a backward CASE branch.
        for (int index = 0; index < 2; index++) {
            source.append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                    .append(customOrder).append(");\n")
                    .append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                    .append(backward).append(");\n");
        }
        for (TitanGraphqlRootDocument.RootDocumentSortPath sort : customOrders) {
            String pathActive = customOrderPath + ".equals(\"" + javaString(sort.name()) + "\")";
            // ASC forward, DESC forward, ASC backward, DESC backward. Each condition appears
            // once for the sort field and again for its tie breaker.
            for (int state = 0; state < 4; state++) {
                for (int field = 0; field < 2; field++) {
                    source.append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                            .append(customOrder).append(");\n")
                            .append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                            .append(pathActive).append(");\n")
                            .append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                            .append(customOrderDescending).append(");\n")
                            .append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                            .append(backward).append(");\n");
                }
            }
        }
        source.append(indent).append(statement).append(".setLong(").append(next++).append(", ")
                .append(pageSize).append(" + 1L);\n");
        return next;
    }

    /** Binds one after/before custom tuple predicate (present, then ASC and DESC branches). */
    private static int emitConnectionCustomTupleCursorParameters(
            StringBuilder source,
            String statement,
            int parameter,
            String present,
            String descending,
            String valueSetter,
            String value,
            String tieValue,
            String indent
    ) {
        int next = parameter;
        source.append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                .append(present).append(");\n");
        for (int branch = 0; branch < 2; branch++) {
            source.append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                    .append(descending).append(");\n")
                    .append(indent).append(statement).append('.').append(valueSetter).append('(').append(next++).append(", ")
                    .append(value).append(");\n")
                    .append(indent).append(statement).append('.').append(valueSetter).append('(').append(next++).append(", ")
                    .append(value).append(");\n")
                    .append(indent).append(statement).append(".setLong(").append(next++).append(", ")
                    .append(tieValue).append(");\n");
        }
        return next;
    }

    /** Selects the generated local whose JDBC type matches this reviewed tuple sort value. */
    private static String connectionCustomOrderCursorValue(
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            TitanGraphqlRootDocument.RootDocumentSortPath sort,
            String stringValue,
            String longValue
    ) {
        TitanGraphqlFieldDocument field = connectionCustomOrderField(document, type, root, sort);
        return connectionCustomOrderUsesTextCursor(field) ? stringValue : longValue;
    }

    /** The query's placeholder type follows the model field rather than request cursor text. */
    private static String connectionCustomOrderCursorSetter(
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            TitanGraphqlRootDocument.RootDocumentSortPath sort
    ) {
        TitanGraphqlFieldDocument field = connectionCustomOrderField(document, type, root, sort);
        return connectionCustomOrderUsesTextCursor(field) ? "setString" : "setLong";
    }

    /** Emits every bind for the constant-SQL opposite-page boundary probe. */
    private static int emitConnectionBoundaryParameters(
            StringBuilder source,
            String statement,
            int parameter,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument type,
            List<TitanGraphqlRootDocument.RootDocumentSortPath> customOrders,
            String customOrder,
            String customOrderPath,
            String customOrderDescending,
            String afterPresent,
            String afterValue,
            String customAfterStringValue,
            String customAfterLongValue,
            String customAfterTieValue,
            String beforePresent,
            String beforeValue,
            String customBeforeStringValue,
            String customBeforeLongValue,
            String customBeforeTieValue,
            String backward,
            String indent,
            boolean extractedBinding
    ) {
        int next = parameter;
        if (extractedBinding) {
            emitConnectionCursorBinderCall(source, indent, connectionBoundaryCursorBinderHelperName(root), statement,
                    customOrder, customOrderPath, customOrderDescending,
                    afterPresent, afterValue, customAfterStringValue, customAfterLongValue, customAfterTieValue,
                    beforePresent, beforeValue, customBeforeStringValue, customBeforeLongValue, customBeforeTieValue,
                    backward, "0L");
            return emitConnectionBoundaryParameters(new StringBuilder(), statement, parameter, document, root, type,
                    customOrders, customOrder, customOrderPath, customOrderDescending,
                    afterPresent, afterValue, customAfterStringValue, customAfterLongValue, customAfterTieValue,
                    beforePresent, beforeValue, customBeforeStringValue, customBeforeLongValue, customBeforeTieValue,
                    backward, indent, false);
        }
        if (customOrders.isEmpty()) {
            source.append(indent).append(statement).append(".setBoolean(").append(next++).append(", !")
                    .append(backward).append(" && ").append(afterPresent).append(");\n")
                    .append(indent).append(statement).append(".setLong(").append(next++).append(", ")
                    .append(afterValue).append(");\n")
                    .append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                    .append(backward).append(" && ").append(beforePresent).append(");\n")
                    .append(indent).append(statement).append(".setLong(").append(next++).append(", ")
                    .append(beforeValue).append(");\n");
            return next;
        }
        source.append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                .append(customOrder).append(");\n")
                .append(indent).append(statement).append(".setBoolean(").append(next++).append(", !")
                .append(backward).append(" && ").append(afterPresent).append(");\n")
                .append(indent).append(statement).append(".setLong(").append(next++).append(", ")
                .append(afterValue).append(");\n")
                .append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                .append(backward).append(" && ").append(beforePresent).append(");\n")
                .append(indent).append(statement).append(".setLong(").append(next++).append(", ")
                .append(beforeValue).append(");\n");
        for (TitanGraphqlRootDocument.RootDocumentSortPath sort : customOrders) {
            String pathActive = customOrderPath + ".equals(\"" + javaString(sort.name()) + "\")";
            String value = connectionCustomOrderCursorValue(
                    document, type, root, sort, customAfterStringValue, customAfterLongValue);
            String valueSetter = connectionCustomOrderCursorSetter(document, type, root, sort);
            source.append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                    .append(customOrder).append(");\n")
                    .append(indent).append(statement).append(".setBoolean(").append(next++).append(", ")
                    .append(pathActive).append(");\n");
            next = emitConnectionCustomTupleCursorParameters(source, statement, next,
                    "!" + backward + " && " + afterPresent,
                    customOrderDescending, valueSetter, value, customAfterTieValue, indent);
            value = connectionCustomOrderCursorValue(
                    document, type, root, sort, customBeforeStringValue, customBeforeLongValue);
            next = emitConnectionCustomTupleCursorParameters(source, statement, next,
                    backward + " && " + beforePresent,
                    customOrderDescending, valueSetter, value, customBeforeTieValue, indent);
        }
        return next;
    }

    /**
     * Validates the bounded one-hop relation selection before any database read. The field-start
     * locals intentionally live in the root block: rendering the relation later reuses the exact
     * request positions to preserve aliases in the nested JSON object.
     */
    private static void emitRelationSelectionValidation(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument sourceType,
            TitanGraphqlRelationDocument relation,
            String relationStart,
            boolean mysql,
            int relationHops,
            int activeSelectionHopBudget,
            String sourceScope,
            String ancestorTypePath
    ) {
        TitanGraphqlTypeDocument targetType = type(document, relation.targetType());
        String relationScope = sourceScope + "_" + relation.name();
        String indent = mysql ? "                " : "            ";
        String nestedIndent = indent + "    ";
        String plan = scopedLocal("relationSelectionPlan", relationScope, "value");
        String count = scopedLocal("relationSelectionCount", relationScope, "value");
        String index = scopedLocal("relationSelectionIndex", relationScope, "value");
        String fieldStart = scopedLocal("relationSelectionStart", relationScope, "value");
        String matched = scopedLocal("relationSelectionMatched", relationScope, "value");
        String active = relationStart + " >= 0" + (mysql ? " && response.length() == 0" : "");
        source.append(indent).append("if (").append(active).append(") {\n");
        // Policy is part of the operation plan and must reject before the parent statement opens.
        // Keep this ahead of the Relay validation-only early return as well as list/object plans.
        if (mysql) {
            emitMySqlPolicyGuards(source, document, relation.policies(),
                    "relation '" + sourceType.name() + "." + relation.name() + "'", relationStart, nestedIndent);
            emitMySqlPolicyGuards(source, document, targetType.policies(),
                    "rows for relation '" + sourceType.name() + "." + relation.name() + "'", relationStart, nestedIndent);
        } else {
            emitPolicyGuards(source, document, relation.policies(),
                    "relation '" + sourceType.name() + "." + relation.name() + "'", relationStart, nestedIndent);
            emitPolicyGuards(source, document, targetType.policies(),
                    "rows for relation '" + sourceType.name() + "." + relation.name() + "'", relationStart, nestedIndent);
        }
        // The shared Relay emitter validates connection/edge/pageInfo/node selections itself,
        // before it opens its first static statement.  Its plan has a different typed child
        // frontier (TargetConnection rather than Target), so it must not be treated as a list
        // selection here.
        if (isBasicRelayRelationConnection(document, sourceType, relation)) {
            TitanGraphqlRootDocument connectionContract = relationConnectionContract(relation);
            String allowedArguments = connectionAllowedArgumentNames(document, targetType, connectionContract);
            String optionalArgumentTypes = connectionOptionalArgumentTypes(document, targetType, connectionContract);
            String typeError = scopedLocal("relationConnectionArgumentTypeError", relationScope, "value");
            source.append(nestedIndent).append("if (!DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, ")
                    .append(relationStart).append(", \"").append(javaString(allowedArguments)).append("\")) {\n");
            emitConnectionFailureAt(source, mysql, nestedIndent + "    ",
                    "relation connection '" + sourceType.name() + "." + relation.name()
                            + "' has an unknown, duplicate, or malformed argument",
                    relationStart);
            source.append(nestedIndent).append("}\n")
                    .append(nestedIndent).append("String ").append(typeError)
                    .append(" = DatabaseGraphqlEngine.optionalArgumentVariableTypeErrorFromAst(query, requestAst, ")
                    .append(relationStart).append(", \"").append(javaString(optionalArgumentTypes)).append("\");\n")
                    .append(nestedIndent).append("if (")
                    .append(mysql ? "response.length() == 0 && " : "")
                    .append(typeError).append(".length() != 0) {\n");
            if (mysql) {
                source.append(nestedIndent).append("    response = DatabaseGraphqlEngine.errorJsonAt(\"variable for relation connection '")
                        .append(javaString(sourceType.name())).append(".").append(javaString(relation.name()))
                        .append("' has an incompatible declaration for argument '\" + ")
                        .append(typeError).append(" + \"'\", query, ").append(relationStart).append(");\n");
            } else {
                source.append(nestedIndent).append("    return DatabaseGraphqlEngine.errorJsonAt(\"variable for relation connection '")
                        .append(javaString(sourceType.name())).append(".").append(javaString(relation.name()))
                        .append("' has an incompatible declaration for argument '\" + ")
                        .append(typeError).append(" + \"'\", query, ").append(relationStart).append(");\n");
            }
            source.append(nestedIndent).append("}\n");
            String validationValue = scopedLocal("relationConnectionValidationValue", relationScope, "value");
            String validationPropagatedNull = scopedLocal(
                    "relationConnectionValidationPropagatedNull", relationScope, "value");
            if (mysql) {
                source.append(nestedIndent).append("String ").append(validationValue).append(" = \"\";\n");
            }
            source.append(nestedIndent).append("boolean ").append(validationPropagatedNull)
                    .append(" = false;\n");
            emitInlineConnection(source, document, connectionContract, targetType, relationStart, validationValue,
                    validationPropagatedNull, mysql, "", ancestorTypePath,
                    runtimeTypeConditionPathEntry(document, sourceType), "\"[]\"",
                    "", null, "", relationHops, activeSelectionHopBudget, true);
            source.append(indent).append("}\n");
            return;
        }
        source.append(nestedIndent).append("String ").append(plan).append(" = DatabaseGraphqlEngine.fieldSelectionPlanFromAst(query, ")
                .append("requestAst, ").append(relationStart).append(", variablesJson, \"")
                .append(javaString(runtimeTypeConditionPathEntry(document, sourceType))).append("\", \"")
                .append(javaString(runtimeTypeConditions(document, targetType)))
                .append("\", \"").append(javaString(ancestorTypePath)).append("\");\n")
                .append(nestedIndent).append("int ").append(count).append(" = DatabaseGraphqlEngine.selectionPlanCount(")
                .append(plan).append(");\n");
        if (mysql) {
            source.append(nestedIndent).append("if (").append(count).append(" < 0) {\n")
                    .append(nestedIndent).append("    response = DatabaseGraphqlEngine.errorJsonAt(\"relation selection plan is invalid or exceeds the database engine budget\", query, ")
                    .append(relationStart).append(");\n")
                    .append(nestedIndent).append("}\n")
                    .append(nestedIndent).append("if (response.length() == 0 && ").append(count).append(" == 0) {\n")
                    .append(nestedIndent).append("    response = DatabaseGraphqlEngine.errorJsonAt(\"relation selection must contain at least one field\", query, ")
                    .append(relationStart).append(");\n")
                    .append(nestedIndent).append("}\n")
                    .append(nestedIndent).append("if (response.length() == 0 && DatabaseGraphqlEngine.selectionPlanHasDuplicateResponseKeyFromAst(query, requestAst, ")
                    .append(plan).append(")) {\n")
                    .append(nestedIndent).append("    response = DatabaseGraphqlEngine.errorJsonAt(\"relation selection needs unsupported field merging for a duplicate response key\", query, ")
                    .append(relationStart).append(");\n")
                    .append(nestedIndent).append("}\n");
        } else {
            source.append(nestedIndent).append("if (").append(count).append(" < 0) return DatabaseGraphqlEngine.errorJsonAt(\"relation selection plan is invalid or exceeds the database engine budget\", query, ")
                    .append(relationStart).append(");\n")
                    .append(nestedIndent).append("if (").append(count).append(" == 0) return DatabaseGraphqlEngine.errorJsonAt(\"relation selection must contain at least one field\", query, ")
                    .append(relationStart).append(");\n")
                    .append(nestedIndent).append("if (DatabaseGraphqlEngine.selectionPlanHasDuplicateResponseKeyFromAst(query, requestAst, ").append(plan)
                    .append(")) return DatabaseGraphqlEngine.errorJsonAt(\"relation selection needs unsupported field merging for a duplicate response key\", query, ")
                    .append(relationStart).append(");\n");
        }
        source.append(nestedIndent).append("int ").append(index).append(" = 0;\n")
                .append(nestedIndent).append("while (").append(index).append(" < ").append(count)
                .append(mysql ? " && response.length() == 0" : "").append(") {\n")
                .append(nestedIndent).append("    int ").append(fieldStart).append(" = DatabaseGraphqlEngine.selectionPlanFieldStart(")
                .append(plan).append(", ").append(index).append(");\n")
                .append(nestedIndent).append("    boolean ").append(matched).append(" = false;\n");
        emitTypeNameSelectionValidation(source, mysql, nestedIndent + "    ", matched, fieldStart, targetType.name());
        for (TitanGraphqlFieldDocument field : sortedFields(targetType)) {
            source.append(nestedIndent).append("    if (!").append(matched).append(" && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                    .append(fieldStart).append(", \"").append(javaString(field.name())).append("\")) {\n")
                    .append(nestedIndent).append("        ").append(matched).append(" = true;\n")
                    .append(nestedIndent).append("        if (!DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, ")
                    .append(fieldStart).append(", \"\")) {\n");
            emitConnectionFailureAt(source, mysql, nestedIndent + "            ",
                    "field '" + targetType.name() + "." + field.name() + "' has an argument", fieldStart);
            source.append(nestedIndent).append("        }\n");
            emitScalarLeafSelectionValidation(source, mysql, nestedIndent + "        ",
                    mysql ? "response.length() == 0 && " : "", fieldStart,
                    targetType.name() + "." + field.name());
            if (!field.policies().isEmpty()) {
                source.append(nestedIndent).append("        if (true").append(mysql ? " && response.length() == 0" : "").append(") {\n");
                if (mysql) {
                    emitMySqlPolicyGuards(source, document, field.policies(),
                            "field '" + targetType.name() + "." + field.name() + "'", fieldStart, nestedIndent + "            ");
                } else {
                    emitPolicyGuards(source, document, field.policies(),
                            "field '" + targetType.name() + "." + field.name() + "'", fieldStart, nestedIndent + "            ");
                }
                source.append(nestedIndent).append("        }\n");
            }
            source.append(nestedIndent).append("    }\n");
        }
        for (TitanGraphqlRelationDocument nested : selectableRelations(targetType)) {
            source.append(nestedIndent).append("    if (!").append(matched).append(" && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                    .append(fieldStart).append(", \"").append(javaString(nested.name())).append("\")) {\n")
                    .append(nestedIndent).append("        ").append(matched).append(" = true;\n");
            if (!isBasicRelayRelationConnection(document, targetType, nested)) {
                source.append(nestedIndent).append("        if (!DatabaseGraphqlEngine.fieldHasOnlyArgumentsFromAst(query, requestAst, ")
                        .append(fieldStart).append(", \"\")) {\n");
                emitConnectionFailureAt(source, mysql, nestedIndent + "            ",
                        "relation '" + targetType.name() + "." + nested.name() + "' has an argument", fieldStart);
                source.append(nestedIndent).append("        }\n");
            }
            int nextRelationHops = relationHops + 1;
            int nextSelectionHopBudget = Math.min(activeSelectionHopBudget, nested.selectionHopBudget());
            if (nextRelationHops > nextSelectionHopBudget) {
                emitConnectionFailureAt(source, mysql, nestedIndent + "        ",
                        "relation '" + targetType.name() + "." + nested.name()
                                + "' exceeds selection hop budget of " + nextSelectionHopBudget, fieldStart);
            } else {
                emitRelationSelectionValidation(source, document, root, targetType, nested, fieldStart, mysql,
                        nextRelationHops, nextSelectionHopBudget, relationScope,
                        ancestorTypePath + "|" + runtimeTypeConditionPathEntry(document, targetType));
            }
            source.append(nestedIndent).append("    }\n");
        }
        source.append(nestedIndent).append("    if (!").append(matched)
                .append(mysql ? " && response.length() == 0" : "").append(") {\n");
        emitConnectionFailureAt(source, mysql, nestedIndent + "        ",
                "unknown or unsupported field in relation '" + sourceType.name() + "." + relation.name() + "' selection",
                fieldStart);
        source.append(nestedIndent).append("    }\n")
                .append(nestedIndent).append("    ").append(index).append("++;\n")
                .append(nestedIndent).append("}\n")
                .append(indent).append("}\n");
    }

    /** Emits one model-specialized, database-internal relation read and shapes its nested JSON. */
    private static void emitRelationOutput(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument sourceType,
            TitanGraphqlRelationDocument relation,
            String relationStart,
            boolean mysql,
            String rootPath,
            String propagatedNull,
            String sourceScope,
            String parentMembers,
            int relationHops,
            int activeSelectionHopBudget,
            String ancestorTypePath,
            String nestedRelationRows
    ) {
        TitanGraphqlTypeDocument targetType = type(document, relation.targetType());
        String relationScope = sourceScope + "_" + relation.name();
        String sourceIndent = mysql ? "                        " : "                ";
        String nestedIndent = sourceIndent + "    ";
        String active = relationStart + " >= 0" + (mysql ? " && response.length() == 0" : "");
        source.append(sourceIndent).append("if (").append(active).append(") {\n");
        if (isBasicRelayRelationConnection(document, sourceType, relation)) {
            TitanGraphqlRootDocument connectionContract = relationConnectionContract(relation);
            TitanGraphqlFieldDocument sourceField = fieldByColumn(sourceType, relation.localColumn());
            boolean hiddenSourceColumn = sourceField == null;
            if (hiddenSourceColumn) {
                sourceField = relationJoinField(document, sourceType, relation);
            }
            if (sourceField == null) {
                emitRelationUnsupported(source, sourceType, relation, mysql, nestedIndent,
                        "a local join column with a reviewed scalar type");
                source.append(sourceIndent).append("}\n");
                return;
            }
            String sourceValue = hiddenSourceColumn
                    ? scopedLocal("relationJoinValue", sourceScope, relation.name())
                    : scopedLocal("fieldValue", sourceScope, sourceField.name());
            String relationValue = scopedLocal("relationValue", sourceScope, relation.name());
            String relationPropagatedNull = scopedLocal("relationPropagatedNull", sourceScope, relation.name());
            // Root procedures already declare their connection value before entering the shared
            // executor. A relation value is local to this object-selection branch, so MySQL
            // needs its own declaration (the executor deliberately leaves MySQL values owned by
            // its procedure caller to support assignment-style error completion).
            if (mysql) {
                source.append(nestedIndent).append("String ").append(relationValue).append(" = \"\";\n");
            }
            source.append(nestedIndent).append("boolean ").append(relationPropagatedNull).append(" = false;\n");
            emitInlineConnection(source, document, connectionContract, targetType, relationStart, relationValue,
                    relationPropagatedNull, mysql, nestedRelationRows, ancestorTypePath,
                    runtimeTypeConditionPathEntry(document, sourceType), rootPath,
                    relation.targetColumn(), sourceField, sourceValue, relationHops, activeSelectionHopBudget, false);
            source.append(nestedIndent).append("if (").append(relationPropagatedNull).append(") {\n")
                    .append(nestedIndent).append("    ").append(relationValue).append(" = \"null\";\n");
            if (!relation.nullable()) {
                source.append(nestedIndent).append("    ").append(propagatedNull).append(" = true;\n");
            }
            source.append(nestedIndent).append("}\n")
                    .append(nestedIndent).append(parentMembers).append(" = DatabaseGraphqlEngine.appendJsonMember(")
                    .append(parentMembers).append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                    .append(relationStart).append(", \"").append(javaString(relation.name())).append("\"), ")
                    .append(relationValue).append(");\n")
                    .append(sourceIndent).append("}\n");
            return;
        }
        if (!relation.arguments().isEmpty() || (relation.pagination() != null
                && relation.pagination().mode() != TitanGraphqlRelationDocument.RelationDocumentPaginationMode.NONE)) {
            emitRelationUnsupported(source, sourceType, relation, mysql, nestedIndent,
                    "arguments or pagination");
            source.append(sourceIndent).append("}\n");
            return;
        }
        TitanGraphqlFieldDocument sourceField = fieldByColumn(sourceType, relation.localColumn());
        boolean hiddenSourceColumn = sourceField == null;
        if (hiddenSourceColumn) {
            sourceField = relationJoinField(document, sourceType, relation);
        }
        if (sourceField == null) {
            emitRelationUnsupported(source, sourceType, relation, mysql, nestedIndent,
                    "a local join column with a reviewed scalar type");
            source.append(sourceIndent).append("}\n");
            return;
        }
        String sourceValue = hiddenSourceColumn
                ? scopedLocal("relationJoinValue", sourceScope, relation.name())
                : scopedLocal("fieldValue", sourceScope, sourceField.name());
        String statement = scopedLocal("relationStatement", sourceScope, relation.name());
        String resultSet = scopedLocal("relationResultSet", sourceScope, relation.name());
        String relationValue = scopedLocal("relationValue", sourceScope, relation.name());
        // A child object may become null because one of its non-null leaves (or a non-null
        // descendant relation) failed. Keep that completion state local to this relation until
        // its declared wrapper determines whether it bubbles into the parent object.
        String relationPropagatedNull = scopedLocal("relationPropagatedNull", sourceScope, relation.name());
        String relationPlan = scopedLocal("relationSelectionPlan", relationScope, "value");
        String relationCount = scopedLocal("relationSelectionCount", relationScope, "value");
        String relationPath = scopedLocal("relationExecutionPath", relationScope, "value");
        // A parent read may have completed while a request deadline elapsed. Check immediately
        // before this nested relation can start its own database statement.
        emitExecutionDeadlineCheck(source, mysql, sourceIndent, false);
        emitApplicationStatementReservation(source, mysql, sourceIndent, false, relationStart);
        if (mysql) {
            // The deadline helper records the terminal response; do not continue into a nested
            // statement after that response has been set.
            source.append(nestedIndent).append("if (response.length() == 0) {\n");
        }
        source.append(nestedIndent).append("String ").append(relationPlan)
                .append(" = DatabaseGraphqlEngine.fieldSelectionPlanFromAst(query, requestAst, ").append(relationStart)
                .append(", variablesJson, \"")
                .append(javaString(runtimeTypeConditionPathEntry(document, sourceType))).append("\", \"")
                .append(javaString(runtimeTypeConditions(document, targetType))).append("\", \"")
                .append(javaString(ancestorTypePath)).append("\");\n")
                .append(nestedIndent).append("int ").append(relationCount)
                .append(" = DatabaseGraphqlEngine.selectionPlanCount(").append(relationPlan).append(");\n");
        source.append(nestedIndent).append("String ").append(relationPath)
                .append(" = DatabaseGraphqlEngine.appendExecutionPath(").append(rootPath)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(relationStart)
                .append(", \"").append(javaString(relation.name())).append("\"));\n");
        source.append(nestedIndent).append("boolean ").append(relationPropagatedNull).append(" = false;\n");
        source.append(nestedIndent).append("PreparedStatement ").append(statement)
                .append(" = connection.prepareStatement(\"")
                .append(javaString(relationSql(targetType, relation))).append("\");\n")
                .append(nestedIndent).append(statement).append('.').append(jdbcSetter(sourceField))
                .append("(1, ").append(sourceValue).append(");\n")
                .append(nestedIndent).append("ResultSet ").append(resultSet).append(" = ")
                .append(statement).append(".executeQuery();\n");
        if (relation.cardinality() == TitanGraphqlRelationDocument.RelationDocumentCardinality.MANY) {
            String items = scopedLocal("relationItems", sourceScope, relation.name());
            String itemIndex = scopedLocal("relationItemIndex", sourceScope, relation.name());
            source.append(nestedIndent).append("String ").append(items).append(" = \"\";\n")
                    .append(nestedIndent).append("long ").append(itemIndex).append(" = 0L;\n")
                    .append(nestedIndent).append("while (").append(resultSet).append(".next()) {\n")
                    .append(nestedIndent).append("    if (").append(nestedRelationRows).append(" >= ")
                    .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("L) {\n");
            if (mysql) {
                source.append(nestedIndent).append("        response = DatabaseGraphqlEngine.resourceLimitErrorJsonAt(\"request exceeds decoded application row budget of ")
                        .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("\", query, ")
                        .append(relationStart).append(");\n")
                        .append(nestedIndent).append("        break;\n");
            } else {
                source.append(nestedIndent).append("        return DatabaseGraphqlEngine.resourceLimitErrorJsonAt(\"request exceeds decoded application row budget of ")
                        .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("\", query, ")
                        .append(relationStart).append(");\n");
            }
            source.append(nestedIndent).append("    }\n")
                    // The static SQL reads one sentinel row beyond the budget. Do not decode or
                    // recurse into that row: report an ordinary field execution error, let the
                    // existing non-null completion logic bubble it correctly, and stop reading.
                    .append(nestedIndent).append("    if (").append(itemIndex).append(" >= ")
                    .append(MAX_UNPAGINATED_RELATION_ROWS).append("L) {\n")
                    .append(nestedIndent).append("        executionErrors = DatabaseGraphqlEngine.appendExecutionError(")
                    .append("executionErrors, \"relation '").append(javaString(sourceType.name())).append('.')
                    .append(javaString(relation.name())).append("' exceeds database row budget of ")
                    .append(MAX_UNPAGINATED_RELATION_ROWS).append("\", ").append(relationPath)
                    .append(", query, ").append(relationStart).append(");\n")
                    .append(nestedIndent).append("        ").append(relationPropagatedNull).append(" = true;\n")
                    .append(nestedIndent).append("        break;\n")
                    .append(nestedIndent).append("    }\n")
                    .append(nestedIndent).append("    if (!").append(relationPropagatedNull)
                    .append(mysql ? " && response.length() == 0" : "").append(") {\n");
            emitExecutionDeadlineCheck(source, mysql, nestedIndent + "    ", false);
            if (mysql) {
                source.append(nestedIndent).append("    if (response.length() == 0) {\n");
            }
            source.append(nestedIndent).append("    ").append(nestedRelationRows).append("++;\n");
            emitRelationObject(source, document, root, targetType, relationScope, relationPlan, relationCount, resultSet,
                    items, false, mysql, nestedIndent + "    ", relationPath, itemIndex, relationPropagatedNull,
                    relationHops, activeSelectionHopBudget, ancestorTypePath, nestedRelationRows, false, "");
            source.append(nestedIndent).append("    ").append(itemIndex).append("++;\n");
            if (mysql) {
                source.append(nestedIndent).append("    }\n");
            }
            source.append(nestedIndent).append("    }\n");
            source.append(nestedIndent).append("}\n")
                    .append(nestedIndent).append("String ").append(relationValue).append(" = \"[\" + ")
                    .append(items).append(" + \"]\";\n");
        } else {
            source.append(nestedIndent).append("String ").append(relationValue).append(" = \"null\";\n")
                    // Deliberately use the cursor shape even for a to-one relation. Its body is
                    // the model-specialized JSON executor and can itself contain relation reads;
                    // a JDBC single-row `if (rs.next())` only represents a row-to-local transfer.
                    // The model's cardinality contract makes this loop run at most once.
                    .append(nestedIndent).append("while (").append(resultSet).append(".next()) {\n");
            source.append(nestedIndent).append("    if (").append(nestedRelationRows).append(" >= ")
                    .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("L) {\n");
            if (mysql) {
                source.append(nestedIndent).append("        response = DatabaseGraphqlEngine.resourceLimitErrorJsonAt(\"request exceeds decoded application row budget of ")
                        .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("\", query, ")
                        .append(relationStart).append(");\n")
                        .append(nestedIndent).append("        break;\n");
            } else {
                source.append(nestedIndent).append("        return DatabaseGraphqlEngine.resourceLimitErrorJsonAt(\"request exceeds decoded application row budget of ")
                        .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("\", query, ")
                        .append(relationStart).append(");\n");
            }
            source.append(nestedIndent).append("    }\n")
                    .append(nestedIndent).append("    ").append(nestedRelationRows).append("++;\n");
            emitExecutionDeadlineCheck(source, mysql, nestedIndent + "    ", false);
            if (mysql) {
                source.append(nestedIndent).append("    if (response.length() == 0) {\n");
            }
            emitRelationObject(source, document, root, targetType, relationScope, relationPlan, relationCount, resultSet,
                    relationValue, true, mysql, nestedIndent + "    ", relationPath, "-1L", relationPropagatedNull,
                    relationHops, activeSelectionHopBudget, ancestorTypePath, nestedRelationRows, false, "");
            if (mysql) {
                source.append(nestedIndent).append("    }\n");
            }
            source.append(nestedIndent).append("}\n");
        }
        if (relation.cardinality() == TitanGraphqlRelationDocument.RelationDocumentCardinality.ONE && !relation.nullable()) {
            source.append(nestedIndent).append("if (").append(relationValue).append(".equals(\"null\") && !")
                    .append(relationPropagatedNull).append(") {\n")
                    .append(nestedIndent).append("    executionErrors = DatabaseGraphqlEngine.appendExecutionError(")
                    .append("executionErrors, \"Cannot return null for non-nullable field ")
                    .append(javaString(sourceType.name())).append('.').append(javaString(relation.name())).append(".\", ")
                    .append("DatabaseGraphqlEngine.appendExecutionPath(").append(rootPath)
                    .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(relationStart)
                    .append(", \"").append(javaString(relation.name())).append("\")), query, ")
                    .append(relationStart).append(");\n")
                    .append(nestedIndent).append("    ").append(relationPropagatedNull).append(" = true;\n")
                    .append(nestedIndent).append("}\n");
        }
        source.append(nestedIndent).append("if (").append(relationPropagatedNull).append(") {\n")
                .append(nestedIndent).append("    ").append(relationValue).append(" = \"null\";\n");
        if (relation.cardinality() == TitanGraphqlRelationDocument.RelationDocumentCardinality.MANY || !relation.nullable()) {
            source.append(nestedIndent).append("    ").append(propagatedNull).append(" = true;\n");
        }
        source.append(nestedIndent).append("}\n");
       source.append(nestedIndent).append(parentMembers).append(" = DatabaseGraphqlEngine.appendJsonMember(")
               .append(parentMembers).append(", ")
               .append("DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(relationStart).append(", \"")
               .append(javaString(relation.name())).append("\"), ").append(relationValue).append(");\n")
                ;
       if (mysql) {
            source.append(nestedIndent).append("}\n");
        }
        source
                .append(sourceIndent).append("}\n");
    }

    /**
     * Executes one fixed-arity relation batch after the root page cursor is consumed. The parent
     * key carrier and JSON placeholders were produced while rendering the page, so no root row is
     * re-read and the thin frontend remains unaware of batching.
     */
    private static void emitUnpaginatedRelationBatch(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument sourceType,
            TitanGraphqlRelationDocument relation,
            boolean mysql,
            String scope,
            String parentJsonCarrier,
            String propagatedNull,
            String decodedApplicationRows,
            String nodeAncestorTypePath,
            int relationHops,
            int activeSelectionHopBudget,
            String parentRowsCarrier,
            String parentOwnersCarrier,
            String selectedStart,
            String keys,
            String activity,
            String paths,
            String rows,
            String replacementOwnersCarrier
    ) {
        TitanGraphqlTypeDocument targetType = type(document, relation.targetType());
        TitanGraphqlFieldDocument keyField = relationJoinField(document, sourceType, relation);
        String indent = mysql ? "                " : "            ";
        String nested = indent + "    ";
        String batchScope = scope + "_batch_" + relation.name();
        String keyCount = scopedLocal("batchKeyCount", batchScope, "value");
        String activityCount = scopedLocal("batchActivityCount", batchScope, "value");
        String pathCount = scopedLocal("batchPathCount", batchScope, "value");
        String ownerCount = scopedLocal("batchOwnerCount", batchScope, "value");
        String ownerIndex = scopedLocal("batchOwnerIndex", batchScope, "value");
        String placeholderIndex = scopedLocal("batchPlaceholderIndex", batchScope, "value");
        String relationPlan = scopedLocal("batchRelationPlan", batchScope, "value");
        String relationCount = scopedLocal("batchRelationCount", batchScope, "value");
        String offset = scopedLocal("batchOffset", batchScope, "value");
        String statement = scopedLocal("batchStatement", batchScope, "value");
        String resultSet = scopedLocal("batchResultSet", batchScope, "value");
        String returnedSlot = scopedLocal("batchReturnedSlot", batchScope, "value");
        String parentIndex = scopedLocal("batchResolvedParentIndex", batchScope, "value");
        String rowOrdinal = scopedLocal("batchRowOrdinal", batchScope, "value");
        String parentPath = scopedLocal("batchParentPath", batchScope, "value");
        String relationPath = scopedLocal("batchRelationPath", batchScope, "value");
        String item = scopedLocal("batchRelationItem", batchScope, "value");
        String itemNull = scopedLocal("batchRelationItemNull", batchScope, "value");
        String replaceIndex = scopedLocal("batchReplaceIndex", batchScope, "value");
        String batchValue = scopedLocal("batchValue", batchScope, "value");
        String batchRecordCount = scopedLocal("batchRecordCount", batchScope, "value");
        String completionParentPath = scopedLocal("batchCompletionParentPath", batchScope, "value");
        String completionRelationPath = scopedLocal("batchCompletionRelationPath", batchScope, "value");
        int maximumRelationRows = relation.cardinality()
                == TitanGraphqlRelationDocument.RelationDocumentCardinality.ONE
                ? 1 : MAX_UNPAGINATED_RELATION_ROWS;
        String relationOverflowMessage = relation.cardinality()
                == TitanGraphqlRelationDocument.RelationDocumentCardinality.ONE
                ? "relation '" + sourceType.name() + "." + relation.name() + "' returned more than one row"
                : "relation '" + sourceType.name() + "." + relation.name()
                + "' exceeds database row budget of " + MAX_UNPAGINATED_RELATION_ROWS;

        source.append(indent).append("if (").append(selectedStart).append(" >= 0 && !")
                .append(propagatedNull).append(mysql ? " && response.length() == 0" : "")
                .append(") {\n")
                .append(nested).append("int ").append(keyCount)
                .append(" = DatabaseGraphqlEngine.batchParentKeyCount(").append(keys).append(");\n")
                .append(nested).append("int ").append(activityCount)
                .append(" = DatabaseGraphqlEngine.batchParentActivityCount(").append(activity).append(");\n")
                .append(nested).append("int ").append(pathCount)
                .append(" = DatabaseGraphqlEngine.batchParentKeyCount(").append(paths).append(");\n");
        if (parentOwnersCarrier != null) {
            source.append(nested).append("int ").append(ownerCount)
                    .append(" = DatabaseGraphqlEngine.batchParentKeyCount(")
                    .append(parentOwnersCarrier).append(");\n");
        }
        source.append(nested).append("if (").append(keyCount).append(" < 0 || ")
                .append(activityCount).append(" != ").append(keyCount).append(" || ")
                .append(pathCount).append(" != ").append(keyCount);
        if (parentOwnersCarrier != null) {
            source.append(" || ").append(ownerCount).append(" != ").append(keyCount);
        }
        source.append(") {\n");
        emitInternalFailureAt(source, mysql, nested + "    ",
                "relation batch produced an invalid parent-key carrier", selectedStart);
        source.append(nested).append("}\n")
                .append(nested).append("String ").append(relationPlan)
                .append(" = DatabaseGraphqlEngine.fieldSelectionPlanFromAst(query, requestAst, ")
                .append(selectedStart).append(", variablesJson, \"")
                .append(javaString(runtimeTypeConditionPathEntry(document, sourceType))).append("\", \"")
                .append(javaString(runtimeTypeConditions(document, targetType))).append("\", \"")
                .append(javaString(nodeAncestorTypePath + "|" + runtimeTypeConditionPathEntry(document, sourceType)))
                .append("\");\n")
                .append(nested).append("int ").append(relationCount)
                .append(" = DatabaseGraphqlEngine.selectionPlanCount(").append(relationPlan).append(");\n");
        for (TitanGraphqlRelationDocument child : selectableRelations(targetType)) {
            int childHops = relationHops + 1;
            int childBudget = Math.min(activeSelectionHopBudget, child.selectionHopBudget());
            if (childHops <= childBudget && supportsUnpaginatedRelationBatch(targetType, child, document)) {
                source.append(nested).append("String ").append(batchParentKeys(batchScope, child))
                        .append(" = \"bk1;\";\n")
                        .append(nested).append("String ").append(batchParentActivity(batchScope, child))
                        .append(" = \"ba1;\";\n")
                        .append(nested).append("String ").append(batchParentPaths(batchScope, child))
                        .append(" = \"bk1;\";\n")
                        .append(nested).append("String ").append(batchParentOwners(batchScope, child))
                        .append(" = \"bk1;\";\n")
                        .append(nested).append("String ").append(batchPlanStarts(batchScope, child))
                        .append(" = \"bk1;\";\n");
            }
        }
        source.append(nested).append("int ").append(offset).append(" = 0;\n")
                .append(nested).append("while (").append(offset).append(" < ").append(keyCount)
                .append(mysql ? " && response.length() == 0" : "").append(") {\n");
        emitExecutionDeadlineCheck(source, mysql, nested + "    ", false);
        emitApplicationStatementReservation(source, mysql, nested + "    ", false, selectedStart);
        if (mysql) {
            source.append(nested).append("    if (response.length() == 0) {\n");
        }
        source.append(nested).append("    PreparedStatement ").append(statement)
                .append(" = connection.prepareStatement(\"")
                .append(javaString(unpaginatedRelationBatchSql(targetType, relation, keyField, !mysql))).append("\");\n");
        emitFixedRelationBatchKeyParameters(
                source, nested + "    ", statement, offset, keyCount, activity, keys, keyField, 1);
        source.append(nested).append("    ResultSet ").append(resultSet).append(" = ")
                .append(statement).append(".executeQuery();\n")
                .append(nested).append("    while (").append(resultSet).append(".next()) {\n");
        if (mysql) {
            // Titan's MySQL cursor lowering recognizes a bare ResultSet.next() loop. Preserve
            // the same fail-closed behavior with an immediate body guard instead of a compound
            // loop predicate.
            source.append(nested).append("        if (response.length() != 0) break;\n");
        }
        source
                .append(nested).append("        if (").append(decodedApplicationRows).append(" >= ")
                .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("L) {\n");
        if (mysql) {
            source.append(nested).append("            response = DatabaseGraphqlEngine.resourceLimitErrorJsonAt(\"request exceeds decoded application row budget of ")
                    .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("\", query, ")
                    .append(selectedStart).append(");\n")
                    .append(nested).append("            break;\n");
        } else {
            source.append(nested).append("            return DatabaseGraphqlEngine.resourceLimitErrorJsonAt(\"request exceeds decoded application row budget of ")
                    .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("\", query, ")
                    .append(selectedStart).append(");\n");
        }
        source.append(nested).append("        }\n")
                .append(nested).append("        ").append(decodedApplicationRows).append("++;\n");
        source.append(nested).append("        int ").append(returnedSlot).append(" = ")
                .append(resultSet).append(".getInt(\"tgql_batch_parent_index\");\n")
                .append(nested).append("        int ").append(parentIndex).append(" = ")
                .append(offset).append(" + ").append(returnedSlot).append(";\n")
                .append(nested).append("        long ").append(rowOrdinal).append(" = ")
                .append(resultSet).append(".getLong(\"tgql_batch_row\");\n")
                .append(nested).append("        String ").append(parentPath)
                .append(" = DatabaseGraphqlEngine.batchParentKey(").append(paths).append(", ")
                .append(parentIndex).append(");\n")
                .append(nested).append("        String ").append(relationPath)
                .append(" = DatabaseGraphqlEngine.appendExecutionPath(").append(parentPath)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                .append(selectedStart).append(", \"").append(javaString(relation.name())).append("\"));\n")
                .append(nested).append("        if (").append(returnedSlot).append(" < 0 || ")
                .append(returnedSlot).append(" >= ").append(MAX_STATIC_RELATION_BATCH_KEYS)
                .append(" || ").append(parentIndex).append(" >= ").append(keyCount)
                .append(" || !DatabaseGraphqlEngine.batchParentActive(").append(activity).append(", ")
                .append(parentIndex).append(")) {\n");
        emitInternalFailureAt(source, mysql, nested + "            ",
                "relation batch returned a row outside its reviewed parent keys", selectedStart);
        source.append(nested).append("        } else if (").append(rowOrdinal).append(" > ")
                .append(maximumRelationRows).append("L) {\n")
                .append(nested).append("            executionErrors = DatabaseGraphqlEngine.appendExecutionError(")
                .append("executionErrors, \"").append(javaString(relationOverflowMessage)).append("\", ")
                .append(relationPath)
                .append(", query, ").append(selectedStart).append(");\n")
                .append(nested).append("            ").append(rows)
                .append(" = DatabaseGraphqlEngine.appendBatchRelationNull(").append(rows).append(", ")
                .append(parentIndex).append(");\n")
                .append(nested).append("        } else {\n")
                .append(nested).append("            String ").append(item).append(" = \"\";\n")
                .append(nested).append("            boolean ").append(itemNull).append(" = false;\n");
        emitRelationObject(source, document, root, targetType, batchScope, relationPlan, relationCount, resultSet,
                item, relation.cardinality() == TitanGraphqlRelationDocument.RelationDocumentCardinality.ONE,
                mysql, nested + "            ", relationPath, rowOrdinal + " - 1L", itemNull,
                relationHops, activeSelectionHopBudget,
                nodeAncestorTypePath + "|" + runtimeTypeConditionPathEntry(document, sourceType),
                decodedApplicationRows, true, parentIndex);
        source.append(nested).append("            if (").append(itemNull).append(") ")
                .append(rows).append(" = DatabaseGraphqlEngine.appendBatchRelationNull(").append(rows)
                .append(", ").append(parentIndex).append(");\n")
                .append(nested).append("            else ").append(rows)
                .append(" = DatabaseGraphqlEngine.appendBatchRelationItem(").append(rows).append(", ")
                .append(parentIndex).append(", ").append(item).append(");\n")
                .append(nested).append("        }\n")
                .append(nested).append("    }\n")
                .append(nested).append("    ").append(offset).append(" = ").append(offset).append(" + ")
                .append(MAX_STATIC_RELATION_BATCH_KEYS).append(";\n");
        if (mysql) {
            source.append(nested).append("    }\n");
        }
        source.append(nested).append("}\n");
        for (TitanGraphqlRelationDocument child : selectableRelations(targetType)) {
            int childHops = relationHops + 1;
            int childBudget = Math.min(activeSelectionHopBudget, child.selectionHopBudget());
            if (childHops <= childBudget && supportsUnpaginatedRelationBatch(targetType, child, document)) {
                emitUnpaginatedRelationPlanBatches(source, document, root, targetType, child, mysql, batchScope,
                        rows, propagatedNull, decodedApplicationRows,
                        nodeAncestorTypePath + "|" + runtimeTypeConditionPathEntry(document, sourceType),
                        childHops, childBudget, rows, batchParentOwners(batchScope, child));
            }
        }
        source.append(nested).append("int ").append(replaceIndex).append(" = 0;\n")
                .append(nested).append("while (").append(replaceIndex).append(" < ").append(keyCount)
                .append(mysql ? " && response.length() == 0" : "").append(") {\n")
                .append(nested).append("    String ").append(batchValue)
                .append(" = DatabaseGraphqlEngine.")
                .append(relation.cardinality() == TitanGraphqlRelationDocument.RelationDocumentCardinality.ONE
                        ? "batchRelationObjectValue(" : "batchRelationValue(")
                .append(rows).append(", ").append(replaceIndex).append(");\n")
                .append(nested).append("    if (").append(batchValue).append(".length() == 0) {\n");
        emitInternalFailureAt(source, mysql, nested + "        ",
                "relation batch produced an invalid scalar carrier", selectedStart);
        source.append(nested).append("    } else {\n");
        if (relation.cardinality() == TitanGraphqlRelationDocument.RelationDocumentCardinality.ONE) {
            source.append(nested).append("        int ").append(batchRecordCount)
                    .append(" = DatabaseGraphqlEngine.batchRelationRecordCount(").append(rows).append(", ")
                    .append(replaceIndex).append(");\n")
                    .append(nested).append("        if (").append(batchRecordCount).append(" < 0) {\n");
            emitInternalFailureAt(source, mysql, nested + "            ",
                    "relation batch produced an invalid object carrier", selectedStart);
            source.append(nested).append("        }\n")
                    .append(nested).append("        String ").append(completionParentPath)
                    .append(" = DatabaseGraphqlEngine.batchParentKey(").append(paths).append(", ")
                    .append(replaceIndex).append(");\n")
                    .append(nested).append("        String ").append(completionRelationPath)
                    .append(" = DatabaseGraphqlEngine.appendExecutionPath(").append(completionParentPath)
                    .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                    .append(selectedStart).append(", \"").append(javaString(relation.name())).append("\"));\n");
            if (!relation.nullable()) {
                source.append(nested).append("        if (").append(batchValue).append(".equals(\"null\") && ")
                        .append(batchRecordCount).append(" == 0) {\n")
                        .append(nested).append("            executionErrors = DatabaseGraphqlEngine.appendExecutionError(")
                        .append("executionErrors, \"Cannot return null for non-nullable field ")
                        .append(javaString(sourceType.name())).append('.').append(javaString(relation.name()))
                        .append(".\", ").append(completionRelationPath).append(", query, ")
                        .append(selectedStart).append(");\n")
                        .append(nested).append("        }\n");
            }
        }
        if (relation.cardinality() == TitanGraphqlRelationDocument.RelationDocumentCardinality.MANY
                || !relation.nullable()) {
            if (parentRowsCarrier == null) {
                source.append(nested).append("        if (").append(batchValue).append(".equals(\"null\")) ")
                        .append(propagatedNull).append(" = true;\n");
            } else {
                source.append(nested).append("        if (").append(batchValue).append(".equals(\"null\")) {\n")
                        .append(nested).append("            int ").append(ownerIndex)
                        .append(" = DatabaseGraphqlEngine.batchParentOwnerIndex(").append(parentOwnersCarrier)
                        .append(", ").append(replaceIndex).append(");\n")
                        .append(nested).append("            if (").append(ownerIndex).append(" < 0) {\n");
                emitInternalFailureAt(source, mysql, nested + "                ",
                        "relation batch produced an invalid parent-owner carrier", selectedStart);
                source.append(nested).append("            } else {\n")
                        .append(nested).append("                ").append(parentRowsCarrier)
                        .append(" = DatabaseGraphqlEngine.appendBatchRelationNull(")
                        .append(parentRowsCarrier).append(", ").append(ownerIndex).append(");\n")
                        .append(nested).append("            }\n")
                        .append(nested).append("        }\n");
            }
        }
        if (replacementOwnersCarrier != null) {
            source.append(nested).append("        int ").append(placeholderIndex)
                    .append(" = DatabaseGraphqlEngine.batchParentOwnerIndex(")
                    .append(replacementOwnersCarrier).append(", ").append(replaceIndex).append(");\n")
                    .append(nested).append("        if (").append(placeholderIndex).append(" < 0) {\n");
            emitInternalFailureAt(source, mysql, nested + "            ",
                    "relation batch produced an invalid placeholder-owner carrier", selectedStart);
            source.append(nested).append("        }\n");
        }
        source.append(nested).append("        ").append(parentJsonCarrier)
                .append(" = DatabaseGraphqlEngine.")
                .append(parentRowsCarrier == null
                        ? "replaceBatchRelationPlaceholder(" : "replaceBatchRelationCarrierPlaceholder(")
                .append(parentJsonCarrier)
                .append(", ").append(selectedStart).append(", ")
                .append(replacementOwnersCarrier == null ? replaceIndex : placeholderIndex)
                .append(", ").append(batchValue).append(");\n")
                .append(nested).append("        if (").append(parentJsonCarrier).append(".length() == 0) {\n");
        emitInternalFailureAt(source, mysql, nested + "            ",
                "relation batch placeholder was missing or ambiguous", selectedStart);
        source.append(nested).append("        }\n")
                .append(nested).append("        if (DatabaseGraphqlEngine.responseAssemblyExceeded(")
                .append(parentJsonCarrier).append(")) {\n");
        if (mysql) {
            source.append(nested).append("            response = DatabaseGraphqlEngine.resourceLimitErrorJson(\"response exceeds the database engine character budget\");\n");
        } else {
            source.append(nested).append("            return DatabaseGraphqlEngine.resourceLimitErrorJson(\"response exceeds the database engine character budget\");\n");
        }
        source.append(nested).append("        }\n")
                .append(nested).append("    }\n")
                .append(nested).append("    ").append(replaceIndex).append("++;\n")
                .append(nested).append("}\n")
                .append(indent).append("}\n");
    }

    /**
     * Executes an exact-count-only Relay relation plan once per fixed parent chunk. This is the
     * first runtime slice of relation-Relay batching: page/pageInfo plans deliberately remain on
     * the complete per-parent executor until their partitioned page and boundary carriers are
     * wired. Selection parsing, SQL execution, aliases, and response replacement all remain in
     * the transpiled database routine.
     */
    private static void emitRelayRelationCountBatch(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument sourceType,
            TitanGraphqlRelationDocument relation,
            boolean mysql,
            String scope,
            String parentJsonCarrier,
            String propagatedNull,
            String decodedApplicationRows,
            String nodeAncestorTypePath,
            String selectedStart,
            String keys,
            String activity,
            String paths,
            String owners,
            String counts
    ) {
        TitanGraphqlTypeDocument targetType = type(document, relation.targetType());
        TitanGraphqlFieldDocument keyField = relationJoinField(document, sourceType, relation);
        TitanGraphqlRootDocument contract = relationConnectionContract(relation);
        String connectionType = connectionPublicNodeType(contract, targetType) + "Connection";
        String indent = mysql ? "                " : "            ";
        String nested = indent + "    ";
        String batchScope = scope + "_relay_batch_" + relation.name();
        String keyCount = scopedLocal("relayBatchKeyCount", batchScope, "value");
        String activityCount = scopedLocal("relayBatchActivityCount", batchScope, "value");
        String pathCount = scopedLocal("relayBatchPathCount", batchScope, "value");
        String selectionPlan = scopedLocal("relayBatchSelectionPlan", batchScope, "value");
        String selectionCount = scopedLocal("relayBatchSelectionCount", batchScope, "value");
        String offset = scopedLocal("relayBatchOffset", batchScope, "value");
        String statement = scopedLocal("relayBatchCountStatement", batchScope, "value");
        String resultSet = scopedLocal("relayBatchCountResultSet", batchScope, "value");
        String returnedSlot = scopedLocal("relayBatchReturnedSlot", batchScope, "value");
        String parentIndex = scopedLocal("relayBatchParentIndex", batchScope, "value");
        String totalCount = scopedLocal("relayBatchTotalCount", batchScope, "value");
        String replaceIndex = scopedLocal("relayBatchReplaceIndex", batchScope, "value");
        String recordCount = scopedLocal("relayBatchRecordCount", batchScope, "value");
        String countValue = scopedLocal("relayBatchCountValue", batchScope, "value");
        String connectionMembers = scopedLocal("relayBatchConnectionMembers", batchScope, "value");
        String selectionIndex = scopedLocal("relayBatchSelectionIndex", batchScope, "value");
        String selectionStart = scopedLocal("relayBatchSelectionStart", batchScope, "value");
        String connectionValue = scopedLocal("relayBatchConnectionValue", batchScope, "value");
        String replaceOwner = scopedLocal("relayBatchReplaceOwner", batchScope, "value");

        source.append(indent).append("if (").append(selectedStart).append(" >= 0 && ")
                .append("DatabaseGraphqlEngine.relayConnectionSelectionIsCountOnlyFromAst(query, requestAst, ")
                .append(selectedStart).append(", variablesJson, \"")
                .append(javaString(runtimeTypeConditionPathEntry(document, sourceType))).append("\", \"")
                .append(javaString(connectionType)).append("\", \"")
                .append(javaString(nodeAncestorTypePath + "|"
                        + runtimeTypeConditionPathEntry(document, sourceType)))
                .append("\") && !")
                .append(propagatedNull).append(mysql ? " && response.length() == 0" : "")
                .append(") {\n")
                .append(nested).append("String ").append(counts).append(" = \"br1;\";\n")
                .append(nested).append("int ").append(keyCount)
                .append(" = DatabaseGraphqlEngine.batchParentKeyCount(").append(keys).append(");\n")
                .append(nested).append("int ").append(activityCount)
                .append(" = DatabaseGraphqlEngine.batchParentActivityCount(").append(activity).append(");\n")
                .append(nested).append("int ").append(pathCount)
                .append(" = DatabaseGraphqlEngine.batchParentKeyCount(").append(paths).append(");\n")
                .append(nested).append("if (").append(keyCount).append(" < 0 || ")
                .append(activityCount).append(" != ").append(keyCount).append(" || ")
                .append(pathCount).append(" != ").append(keyCount).append(") {\n");
        emitInternalFailureAt(source, mysql, nested + "    ",
                "relation Relay batch produced an invalid parent-key carrier", selectedStart);
        source.append(nested).append("}\n")
                .append(nested).append("String ").append(selectionPlan)
                .append(" = DatabaseGraphqlEngine.fieldSelectionPlanFromAst(query, requestAst, ")
                .append(selectedStart).append(", variablesJson, \"")
                .append(javaString(runtimeTypeConditionPathEntry(document, sourceType))).append("\", \"")
                .append(javaString(connectionType)).append("\", \"")
                .append(javaString(nodeAncestorTypePath + "|"
                        + runtimeTypeConditionPathEntry(document, sourceType)))
                .append("\");\n")
                .append(nested).append("int ").append(selectionCount)
                .append(" = DatabaseGraphqlEngine.selectionPlanCount(").append(selectionPlan).append(");\n")
                .append(nested).append("if (!DatabaseGraphqlEngine.relayConnectionSelectionIsCountOnlyFromAst(")
                .append("query, requestAst, ").append(selectedStart).append(", variablesJson, \"")
                .append(javaString(runtimeTypeConditionPathEntry(document, sourceType))).append("\", \"")
                .append(javaString(connectionType)).append("\", \"")
                .append(javaString(nodeAncestorTypePath + "|"
                        + runtimeTypeConditionPathEntry(document, sourceType)))
                .append("\")) {\n");
        emitInternalFailureAt(source, mysql, nested + "    ",
                "relation Relay batch selection changed after collection", selectedStart);
        source.append(nested).append("}\n");

        for (TitanGraphqlRootDocument.RootDocumentArgument argument : contract.arguments()) {
            String argumentScope = batchScope + "_" + argument.name();
            String raw = scopedLocal("relayBatchArgumentRaw", argumentScope, "value");
            String present = scopedLocal("relayBatchArgumentPresent", argumentScope, "value");
            String value = scopedLocal("relayBatchArgumentValue", argumentScope, "value");
            TitanGraphqlEnumDocument inputEnum = enumType(document, inputNamedType(argument.type()));
            source.append(nested).append("String ").append(raw)
                    .append(" = DatabaseGraphqlEngine.materializedArgumentValue(materializedArguments, ")
                    .append(selectedStart).append(", \"").append(javaString(argument.name())).append("\");\n")
                    .append(nested).append("boolean ").append(present).append(" = ")
                    .append(raw).append(".length() != 0;\n")
                    .append(nested).append(inputEnum == null ? "long " : "String ").append(value)
                    .append(" = ").append(inputEnum == null
                            ? "DatabaseGraphqlEngine.longArgument(" + raw + ")"
                            : "DatabaseGraphqlEngine.enumArgument(" + raw + ")")
                    .append(";\n");
        }

        source.append(nested).append("int ").append(offset).append(" = 0;\n")
                .append(nested).append("while (").append(offset).append(" < ").append(keyCount)
                .append(mysql ? " && response.length() == 0" : "").append(") {\n");
        emitExecutionDeadlineCheck(source, mysql, nested + "    ", false);
        emitApplicationStatementReservation(source, mysql, nested + "    ", false, selectedStart);
        if (mysql) {
            source.append(nested).append("    if (response.length() == 0) {\n");
        }
        source.append(nested).append("    PreparedStatement ").append(statement)
                .append(" = connection.prepareStatement(\"")
                .append(javaString(relationConnectionBatchCountSql(
                        document, sourceType.name(), relation.name(), !mysql)))
                .append("\");\n");
        int parameter = emitFixedRelationBatchKeyParameters(
                source, nested + "    ", statement, offset, keyCount, activity, keys, keyField, 1);
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : contract.arguments()) {
            String argumentScope = batchScope + "_" + argument.name();
            TitanGraphqlFieldDocument argumentField = fieldByColumn(targetType, argument.column());
            source.append(nested).append("    ").append(statement).append(".setBoolean(")
                    .append(parameter++).append(", ")
                    .append(scopedLocal("relayBatchArgumentPresent", argumentScope, "value")).append(");\n")
                    .append(nested).append("    ").append(statement).append('.')
                    .append(jdbcSetter(argumentField, argument.type())).append('(').append(parameter++).append(", ")
                    .append(scopedLocal("relayBatchArgumentValue", argumentScope, "value")).append(");\n");
        }
        source.append(nested).append("    ResultSet ").append(resultSet).append(" = ")
                .append(statement).append(".executeQuery();\n")
                .append(nested).append("    while (").append(resultSet).append(".next()) {\n");
        if (mysql) {
            source.append(nested).append("        if (response.length() != 0) break;\n");
        }
        source.append(nested).append("        if (").append(decodedApplicationRows).append(" >= ")
                .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("L) {\n");
        if (mysql) {
            source.append(nested).append("            response = DatabaseGraphqlEngine.resourceLimitErrorJsonAt(\"request exceeds decoded application row budget of ")
                    .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("\", query, ")
                    .append(selectedStart).append(");\n")
                    .append(nested).append("            break;\n");
        } else {
            source.append(nested).append("            return DatabaseGraphqlEngine.resourceLimitErrorJsonAt(\"request exceeds decoded application row budget of ")
                    .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("\", query, ")
                    .append(selectedStart).append(");\n");
        }
        source.append(nested).append("        }\n")
                .append(nested).append("        ").append(decodedApplicationRows).append("++;\n")
                .append(nested).append("        int ").append(returnedSlot).append(" = ")
                .append(resultSet).append(".getInt(\"tgql_batch_parent_index\");\n")
                .append(nested).append("        int ").append(parentIndex).append(" = ")
                .append(offset).append(" + ").append(returnedSlot).append(";\n")
                .append(nested).append("        long ").append(totalCount).append(" = ")
                .append(resultSet).append(".getLong(\"tgql_total_count\");\n")
                .append(nested).append("        if (").append(returnedSlot).append(" < 0 || ")
                .append(returnedSlot).append(" >= ").append(MAX_STATIC_RELATION_BATCH_KEYS)
                .append(" || ").append(parentIndex).append(" >= ").append(keyCount)
                .append(" || !DatabaseGraphqlEngine.batchParentActive(").append(activity).append(", ")
                .append(parentIndex).append(")) {\n");
        emitInternalFailureAt(source, mysql, nested + "            ",
                "relation Relay count batch returned a row outside its reviewed parent keys", selectedStart);
        source.append(nested).append("        } else {\n")
                .append(nested).append("            ").append(counts)
                .append(" = DatabaseGraphqlEngine.appendBatchRelationItem(").append(counts).append(", ")
                .append(parentIndex).append(", \"\" + ").append(totalCount).append(");\n")
                .append(nested).append("        }\n")
                .append(nested).append("    }\n")
                .append(nested).append("    ").append(offset).append(" = ").append(offset).append(" + ")
                .append(MAX_STATIC_RELATION_BATCH_KEYS).append(";\n");
        if (mysql) {
            source.append(nested).append("    }\n");
        }
        source.append(nested).append("}\n")
                .append(nested).append("int ").append(replaceIndex).append(" = 0;\n")
                .append(nested).append("while (").append(replaceIndex).append(" < ").append(keyCount)
                .append(mysql ? " && response.length() == 0" : "").append(") {\n")
                .append(nested).append("    String ").append(countValue).append(" = \"0\";\n")
                .append(nested).append("    if (DatabaseGraphqlEngine.batchParentActive(").append(activity)
                .append(", ").append(replaceIndex).append(")) {\n")
                .append(nested).append("        int ").append(recordCount)
                .append(" = DatabaseGraphqlEngine.batchRelationRecordCount(").append(counts).append(", ")
                .append(replaceIndex).append(");\n")
                .append(nested).append("        if (").append(recordCount).append(" != 1) {\n");
        emitInternalFailureAt(source, mysql, nested + "            ",
                "relation Relay count batch did not return exactly one row per active parent", selectedStart);
        source.append(nested).append("        } else {\n")
                .append(nested).append("            ").append(countValue)
                .append(" = DatabaseGraphqlEngine.batchRelationItem(").append(counts).append(", ")
                .append(replaceIndex).append(", 0);\n")
                .append(nested).append("        }\n")
                .append(nested).append("    }\n")
                .append(nested).append("    String ").append(connectionMembers).append(" = \"\";\n")
                .append(nested).append("    int ").append(selectionIndex).append(" = 0;\n")
                .append(nested).append("    while (").append(selectionIndex).append(" < ")
                .append(selectionCount).append(") {\n")
                .append(nested).append("        int ").append(selectionStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStart(").append(selectionPlan)
                .append(", ").append(selectionIndex).append(");\n")
                .append(nested).append("        if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(selectionStart).append(", \"totalCount\")) {\n")
                .append(nested).append("            ").append(connectionMembers)
                .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(connectionMembers)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                .append(selectionStart).append(", \"totalCount\"), ").append(countValue).append(");\n")
                .append(nested).append("        } else if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(selectionStart).append(", \"__typename\")) {\n")
                .append(nested).append("            ").append(connectionMembers)
                .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(connectionMembers)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                .append(selectionStart).append(", \"__typename\"), DatabaseGraphqlEngine.jsonString(\"")
                .append(javaString(connectionType)).append("\"));\n")
                .append(nested).append("        }\n")
                .append(nested).append("        ").append(selectionIndex).append("++;\n")
                .append(nested).append("    }\n")
                .append(nested).append("    String ").append(connectionValue).append(" = \"{\" + ")
                .append(connectionMembers).append(" + \"}\";\n")
                .append(nested).append("    int ").append(replaceOwner)
                .append(" = DatabaseGraphqlEngine.batchParentOwnerIndex(").append(owners).append(", ")
                .append(replaceIndex).append(");\n")
                .append(nested).append("    if (").append(replaceOwner).append(" < 0) {\n");
        emitInternalFailureAt(source, mysql, nested + "        ",
                "relation Relay batch produced an invalid parent-owner carrier", selectedStart);
        source.append(nested).append("    }\n")
                .append(nested).append("    ").append(parentJsonCarrier)
                .append(" = DatabaseGraphqlEngine.replaceBatchRelationPlaceholder(")
                .append(parentJsonCarrier).append(", ").append(selectedStart).append(", ")
                .append(replaceOwner).append(", ").append(connectionValue).append(");\n")
                .append(nested).append("    if (").append(parentJsonCarrier).append(".length() == 0) {\n");
        emitInternalFailureAt(source, mysql, nested + "        ",
                "relation Relay batch placeholder was missing or ambiguous", selectedStart);
        source.append(nested).append("    }\n")
                .append(nested).append("    if (DatabaseGraphqlEngine.responseAssemblyExceeded(")
                .append(parentJsonCarrier).append(")) {\n");
        if (mysql) {
            source.append(nested).append("        response = DatabaseGraphqlEngine.resourceLimitErrorJson(\"response exceeds the database engine character budget\");\n");
        } else {
            source.append(nested).append("        return DatabaseGraphqlEngine.resourceLimitErrorJson(\"response exceeds the database engine character budget\");\n");
        }
        source.append(nested).append("    }\n")
                .append(nested).append("    ").append(replaceIndex).append("++;\n")
                .append(nested).append("}\n")
                .append(indent).append("}\n");
    }

    /** Executes partitioned Relay pages for every collected parent and completes each connection. */
    private static void emitRelayRelationPageBatch(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument sourceType,
            TitanGraphqlRelationDocument relation,
            boolean mysql,
            String scope,
            String parentJsonCarrier,
            String propagatedNull,
            String decodedApplicationRows,
            String nodeAncestorTypePath,
            int relationHops,
            int activeSelectionHopBudget,
            String selectedStart,
            String keys,
            String activity,
            String paths,
            String owners,
            String pageRows,
            String cursors,
            String counts,
            String boundaries
    ) {
        TitanGraphqlTypeDocument targetType = type(document, relation.targetType());
        TitanGraphqlFieldDocument keyField = relationJoinField(document, sourceType, relation);
        TitanGraphqlRootDocument contract = relationConnectionContract(relation);
        TitanGraphqlRootDocument.Cursor cursor = contract.pagination().cursor();
        String publicNodeType = connectionPublicNodeType(contract, targetType);
        String connectionType = publicNodeType + "Connection";
        String edgeType = publicNodeType + "Edge";
        String selectionAncestorPath = nodeAncestorTypePath + "|"
                + runtimeTypeConditionPathEntry(document, sourceType);
        String connectionAncestorPath = selectionAncestorPath + "|" + connectionType;
        String edgeAncestorPath = connectionAncestorPath + "|" + edgeType;
        String indent = mysql ? "                " : "            ";
        String nested = indent + "    ";
        String batchScope = scope + "_relay_page_batch_" + relation.name();
        String keyCount = scopedLocal("relayPageKeyCount", batchScope, "value");
        String connectionPlan = scopedLocal("relayPageConnectionPlan", batchScope, "value");
        String connectionCount = scopedLocal("relayPageConnectionCount", batchScope, "value");
        String edgesStart = scopedLocal("relayPageEdgesStart", batchScope, "value");
        String totalCountStart = scopedLocal("relayPageTotalCountStart", batchScope, "value");
        String pageInfoStart = scopedLocal("relayPageInfoStart", batchScope, "value");
        String edgesPlan = scopedLocal("relayPageEdgesPlan", batchScope, "value");
        String edgesCount = scopedLocal("relayPageEdgesCount", batchScope, "value");
        String nodeStart = scopedLocal("relayPageNodeStart", batchScope, "value");
        String nodePlan = scopedLocal("relayPageNodePlan", batchScope, "value");
        String nodeCount = scopedLocal("relayPageNodeCount", batchScope, "value");
        String pageInfoPlan = scopedLocal("relayPageInfoPlan", batchScope, "value");
        String pageInfoCount = scopedLocal("relayPageInfoCount", batchScope, "value");
        String hasNextStart = scopedLocal("relayPageHasNextStart", batchScope, "value");
        String hasPreviousStart = scopedLocal("relayPageHasPreviousStart", batchScope, "value");
        String firstRaw = scopedLocal("relayPageFirstRaw", batchScope, "value");
        String lastRaw = scopedLocal("relayPageLastRaw", batchScope, "value");
        String pageSize = scopedLocal("relayPageSize", batchScope, "value");
        String backward = scopedLocal("relayPageBackward", batchScope, "value");
        String afterRaw = scopedLocal("relayPageAfterRaw", batchScope, "value");
        String afterPresent = scopedLocal("relayPageAfterPresent", batchScope, "value");
        String afterValue = scopedLocal("relayPageAfterValue", batchScope, "value");
        String beforeRaw = scopedLocal("relayPageBeforeRaw", batchScope, "value");
        String beforePresent = scopedLocal("relayPageBeforePresent", batchScope, "value");
        String beforeValue = scopedLocal("relayPageBeforeValue", batchScope, "value");
        String offset = scopedLocal("relayPageOffset", batchScope, "value");
        String statement = scopedLocal("relayPageStatement", batchScope, "value");
        String resultSet = scopedLocal("relayPageResultSet", batchScope, "value");
        String returnedSlot = scopedLocal("relayPageReturnedSlot", batchScope, "value");
        String parentIndex = scopedLocal("relayPageParentIndex", batchScope, "value");
        String rowOrdinal = scopedLocal("relayPageRowOrdinal", batchScope, "value");
        String parentPath = scopedLocal("relayPageParentPath", batchScope, "value");
        String relationPath = scopedLocal("relayPageRelationPath", batchScope, "value");
        String edgePath = scopedLocal("relayPageEdgePath", batchScope, "value");
        String nodePath = scopedLocal("relayPageNodePath", batchScope, "value");
        String nodeValue = scopedLocal("relayPageNodeValue", batchScope, "value");
        String nodeNull = scopedLocal("relayPageNodeNull", batchScope, "value");
        String emittedCursor = scopedLocal("relayPageCursor", batchScope, "value");
        String edgeMembers = scopedLocal("relayPageEdgeMembers", batchScope, "value");
        String edgeIndex = scopedLocal("relayPageEdgeIndex", batchScope, "value");
        String edgeFieldStart = scopedLocal("relayPageEdgeFieldStart", batchScope, "value");
        String edgeValue = scopedLocal("relayPageEdgeValue", batchScope, "value");
        String countStatement = scopedLocal("relayPageCountStatement", batchScope, "value");
        String countResultSet = scopedLocal("relayPageCountResultSet", batchScope, "value");
        String boundaryStatement = scopedLocal("relayPageBoundaryStatement", batchScope, "value");
        String boundaryResultSet = scopedLocal("relayPageBoundaryResultSet", batchScope, "value");
        String replaceIndex = scopedLocal("relayPageReplaceIndex", batchScope, "value");
        String recordCount = scopedLocal("relayPageRecordCount", batchScope, "value");
        String cursorRecordCount = scopedLocal("relayPageCursorRecordCount", batchScope, "value");
        String countRecordCount = scopedLocal("relayPageCountRecordCount", batchScope, "value");
        String boundaryRecordCount = scopedLocal("relayPageBoundaryRecordCount", batchScope, "value");
        String boundaryNeeded = scopedLocal("relayPageBoundaryNeeded", batchScope, "value");
        String retainedCount = scopedLocal("relayPageRetainedCount", batchScope, "value");
        String edgesValue = scopedLocal("relayPageEdgesValue", batchScope, "value");
        String hasNext = scopedLocal("relayPageHasNext", batchScope, "value");
        String hasPrevious = scopedLocal("relayPageHasPrevious", batchScope, "value");
        String startCursor = scopedLocal("relayPageStartCursor", batchScope, "value");
        String endCursor = scopedLocal("relayPageEndCursor", batchScope, "value");
        String countValue = scopedLocal("relayPageCountValue", batchScope, "value");
        String connectionMembers = scopedLocal("relayPageConnectionMembers", batchScope, "value");
        String connectionIndex = scopedLocal("relayPageConnectionIndex", batchScope, "value");
        String connectionFieldStart = scopedLocal("relayPageConnectionFieldStart", batchScope, "value");
        String pageInfoMembers = scopedLocal("relayPageInfoMembers", batchScope, "value");
        String pageInfoIndex = scopedLocal("relayPageInfoIndex", batchScope, "value");
        String pageInfoFieldStart = scopedLocal("relayPageInfoFieldStart", batchScope, "value");
        String connectionValue = scopedLocal("relayPageConnectionValue", batchScope, "value");
        String replaceOwner = scopedLocal("relayPageReplaceOwner", batchScope, "value");

        source.append(indent).append("if (").append(selectedStart).append(" >= 0 && ")
                .append("DatabaseGraphqlEngine.relayConnectionSelectionHasPageFromAst(query, requestAst, ")
                .append(selectedStart).append(", variablesJson, \"")
                .append(javaString(runtimeTypeConditionPathEntry(document, sourceType))).append("\", \"")
                .append(javaString(connectionType)).append("\", \"")
                .append(javaString(selectionAncestorPath)).append("\") && !")
                .append(propagatedNull).append(mysql ? " && response.length() == 0" : "")
                .append(") {\n")
                .append(nested).append("String ").append(pageRows).append(" = \"br1;\";\n")
                .append(nested).append("String ").append(cursors).append(" = \"br1;\";\n")
                .append(nested).append("String ").append(counts).append(" = \"br1;\";\n")
                .append(nested).append("String ").append(boundaries).append(" = \"br1;\";\n")
                .append(nested).append("int ").append(keyCount)
                .append(" = DatabaseGraphqlEngine.batchParentKeyCount(").append(keys).append(");\n")
                .append(nested).append("String ").append(connectionPlan)
                .append(" = DatabaseGraphqlEngine.fieldSelectionPlanFromAst(query, requestAst, ")
                .append(selectedStart).append(", variablesJson, \"")
                .append(javaString(runtimeTypeConditionPathEntry(document, sourceType))).append("\", \"")
                .append(javaString(connectionType)).append("\", \"")
                .append(javaString(selectionAncestorPath)).append("\");\n")
                .append(nested).append("int ").append(connectionCount)
                .append(" = DatabaseGraphqlEngine.selectionPlanCount(").append(connectionPlan).append(");\n")
                .append(nested).append("int ").append(edgesStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStartForFieldFromAst(query, requestAst, ")
                .append(connectionPlan).append(", \"edges\");\n")
                .append(nested).append("int ").append(totalCountStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFirstFieldStartForFieldFromAst(query, requestAst, ")
                .append(connectionPlan).append(", \"totalCount\");\n")
                .append(nested).append("int ").append(pageInfoStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStartForFieldFromAst(query, requestAst, ")
                .append(connectionPlan).append(", \"pageInfo\");\n")
                .append(nested).append("String ").append(edgesPlan).append(" = \"\";\n")
                .append(nested).append("int ").append(edgesCount).append(" = 0;\n")
                .append(nested).append("int ").append(nodeStart).append(" = -1;\n")
                .append(nested).append("String ").append(nodePlan).append(" = \"v1;\";\n")
                .append(nested).append("int ").append(nodeCount).append(" = 0;\n")
                .append(nested).append("if (").append(edgesStart).append(" >= 0) {\n")
                .append(nested).append("    ").append(edgesPlan)
                .append(" = DatabaseGraphqlEngine.fieldSelectionPlanFromAst(query, requestAst, ")
                .append(edgesStart).append(", variablesJson, \"").append(javaString(connectionType))
                .append("\", \"").append(javaString(edgeType)).append("\", \"")
                .append(javaString(connectionAncestorPath)).append("\");\n")
                .append(nested).append("    ").append(edgesCount)
                .append(" = DatabaseGraphqlEngine.selectionPlanCount(").append(edgesPlan).append(");\n")
                .append(nested).append("    ").append(nodeStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStartForFieldFromAst(query, requestAst, ")
                .append(edgesPlan).append(", \"node\");\n")
                .append(nested).append("}\n")
                .append(nested).append("if (").append(nodeStart).append(" >= 0) {\n")
                .append(nested).append("    ").append(nodePlan)
                .append(" = DatabaseGraphqlEngine.fieldSelectionPlanFromAst(query, requestAst, ")
                .append(nodeStart).append(", variablesJson, \"").append(javaString(edgeType))
                .append("\", \"").append(javaString(runtimeTypeConditions(document, targetType)))
                .append("\", \"").append(javaString(edgeAncestorPath)).append("\");\n")
                .append(nested).append("    ").append(nodeCount)
                .append(" = DatabaseGraphqlEngine.selectionPlanCount(").append(nodePlan).append(");\n")
                .append(nested).append("}\n")
                .append(nested).append("String ").append(pageInfoPlan).append(" = \"v1;\";\n")
                .append(nested).append("int ").append(pageInfoCount).append(" = 0;\n")
                .append(nested).append("if (").append(pageInfoStart).append(" >= 0) {\n")
                .append(nested).append("    ").append(pageInfoPlan)
                .append(" = DatabaseGraphqlEngine.fieldSelectionPlanFromAst(query, requestAst, ")
                .append(pageInfoStart).append(", variablesJson, \"").append(javaString(connectionType))
                .append("\", \"PageInfo\", \"").append(javaString(connectionAncestorPath)).append("\");\n")
                .append(nested).append("    ").append(pageInfoCount)
                .append(" = DatabaseGraphqlEngine.selectionPlanCount(").append(pageInfoPlan).append(");\n")
                .append(nested).append("}\n")
                .append(nested).append("int ").append(hasNextStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFirstFieldStartForFieldFromAst(query, requestAst, ")
                .append(pageInfoPlan).append(", \"hasNextPage\");\n")
                .append(nested).append("int ").append(hasPreviousStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFirstFieldStartForFieldFromAst(query, requestAst, ")
                .append(pageInfoPlan).append(", \"hasPreviousPage\");\n")
                .append(nested).append("String ").append(firstRaw)
                .append(" = DatabaseGraphqlEngine.materializedArgumentValue(materializedArguments, ")
                .append(selectedStart).append(", \"first\");\n")
                .append(nested).append("String ").append(lastRaw)
                .append(" = DatabaseGraphqlEngine.materializedArgumentValue(materializedArguments, ")
                .append(selectedStart).append(", \"last\");\n")
                .append(nested).append("long ").append(pageSize).append(" = ")
                .append(contract.pagination().defaultPageSize()).append("L;\n")
                .append(nested).append("boolean ").append(backward).append(" = false;\n")
                .append(nested).append("if (").append(firstRaw)
                .append(".length() != 0 && !DatabaseGraphqlEngine.argumentValueIsNull(").append(firstRaw)
                .append(")) ").append(pageSize).append(" = DatabaseGraphqlEngine.longArgument(")
                .append(firstRaw).append(");\n")
                .append(nested).append("if (").append(lastRaw)
                .append(".length() != 0 && !DatabaseGraphqlEngine.argumentValueIsNull(").append(lastRaw)
                .append(")) { ").append(pageSize).append(" = DatabaseGraphqlEngine.longArgument(")
                .append(lastRaw).append("); ").append(backward).append(" = true; }\n")
                .append(nested).append("String ").append(afterRaw)
                .append(" = DatabaseGraphqlEngine.materializedArgumentValue(materializedArguments, ")
                .append(selectedStart).append(", \"after\");\n")
                .append(nested).append("boolean ").append(afterPresent).append(" = ")
                .append(afterRaw).append(".length() != 0 && !DatabaseGraphqlEngine.argumentValueIsNull(")
                .append(afterRaw).append(");\n")
                .append(nested).append("long ").append(afterValue).append(" = 0L;\n")
                .append(nested).append("if (").append(afterPresent).append(") ").append(afterValue)
                .append(" = DatabaseGraphqlEngine.relayCursorLongValue(DatabaseGraphqlEngine.stringArgument(")
                .append(afterRaw).append("), \"").append(javaString(cursor.path())).append("\", \"")
                .append(javaString(cursor.path())).append("\", \"")
                .append(cursor.direction() == TitanGraphqlRootDocument.RootDocumentSortDirection.DESC ? "DESC" : "ASC")
                .append("\", \"").append(javaString(cursor.tieBreaker().isBlank() ? "id" : cursor.tieBreaker()))
                .append("\");\n")
                .append(nested).append("String ").append(beforeRaw)
                .append(" = DatabaseGraphqlEngine.materializedArgumentValue(materializedArguments, ")
                .append(selectedStart).append(", \"before\");\n")
                .append(nested).append("boolean ").append(beforePresent).append(" = ")
                .append(beforeRaw).append(".length() != 0 && !DatabaseGraphqlEngine.argumentValueIsNull(")
                .append(beforeRaw).append(");\n")
                .append(nested).append("long ").append(beforeValue).append(" = 0L;\n")
                .append(nested).append("if (").append(beforePresent).append(") ").append(beforeValue)
                .append(" = DatabaseGraphqlEngine.relayCursorLongValue(DatabaseGraphqlEngine.stringArgument(")
                .append(beforeRaw).append("), \"").append(javaString(cursor.path())).append("\", \"")
                .append(javaString(cursor.path())).append("\", \"")
                .append(cursor.direction() == TitanGraphqlRootDocument.RootDocumentSortDirection.DESC ? "DESC" : "ASC")
                .append("\", \"").append(javaString(cursor.tieBreaker().isBlank() ? "id" : cursor.tieBreaker()))
                .append("\");\n");

        for (TitanGraphqlRootDocument.RootDocumentArgument argument : contract.arguments()) {
            String argumentScope = batchScope + "_" + argument.name();
            String raw = scopedLocal("relayPageArgumentRaw", argumentScope, "value");
            String present = scopedLocal("relayPageArgumentPresent", argumentScope, "value");
            String value = scopedLocal("relayPageArgumentValue", argumentScope, "value");
            TitanGraphqlEnumDocument inputEnum = enumType(document, inputNamedType(argument.type()));
            source.append(nested).append("String ").append(raw)
                    .append(" = DatabaseGraphqlEngine.materializedArgumentValue(materializedArguments, ")
                    .append(selectedStart).append(", \"").append(javaString(argument.name())).append("\");\n")
                    .append(nested).append("boolean ").append(present).append(" = ")
                    .append(raw).append(".length() != 0;\n")
                    .append(nested).append(inputEnum == null ? "long " : "String ").append(value)
                    .append(" = ").append(inputEnum == null
                            ? "DatabaseGraphqlEngine.longArgument(" + raw + ")"
                            : "DatabaseGraphqlEngine.enumArgument(" + raw + ")")
                    .append(";\n");
        }

        for (TitanGraphqlRelationDocument child : selectableRelations(targetType)) {
            int childHops = relationHops + 1;
            int childBudget = Math.min(activeSelectionHopBudget, child.selectionHopBudget());
            if (childHops <= childBudget && supportsUnpaginatedRelationBatch(targetType, child, document)) {
                source.append(nested).append("String ").append(batchParentKeys(batchScope, child))
                        .append(" = \"bk1;\";\n")
                        .append(nested).append("String ").append(batchParentActivity(batchScope, child))
                        .append(" = \"ba1;\";\n")
                        .append(nested).append("String ").append(batchParentPaths(batchScope, child))
                        .append(" = \"bk1;\";\n")
                        .append(nested).append("String ").append(batchParentOwners(batchScope, child))
                        .append(" = \"bk1;\";\n")
                        .append(nested).append("String ").append(batchPlanStarts(batchScope, child))
                        .append(" = \"bk1;\";\n");
            }
        }

        source.append(nested).append("int ").append(offset).append(" = 0;\n")
                .append(nested).append("while (").append(offset).append(" < ").append(keyCount)
                .append(mysql ? " && response.length() == 0" : "").append(") {\n");
        emitExecutionDeadlineCheck(source, mysql, nested + "    ", false);
        emitApplicationStatementReservation(source, mysql, nested + "    ", false, selectedStart);
        if (mysql) source.append(nested).append("    if (response.length() == 0) {\n");
        source.append(nested).append("    PreparedStatement ").append(statement)
                .append(" = connection.prepareStatement(\"")
                .append(javaString(relationConnectionBatchPageSql(
                        document, sourceType.name(), relation.name(), !mysql))).append("\");\n")
                .append(nested).append("    ").append(statement).append(".setBoolean(1, ")
                .append(backward).append(");\n")
                .append(nested).append("    ").append(statement).append(".setBoolean(2, ")
                .append(backward).append(");\n");
        int parameter = emitFixedRelationBatchKeyParameters(
                source, nested + "    ", statement, offset, keyCount, activity, keys, keyField, 3);
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : contract.arguments()) {
            String argumentScope = batchScope + "_" + argument.name();
            TitanGraphqlFieldDocument argumentField = fieldByColumn(targetType, argument.column());
            source.append(nested).append("    ").append(statement).append(".setBoolean(")
                    .append(parameter++).append(", ")
                    .append(scopedLocal("relayPageArgumentPresent", argumentScope, "value")).append(");\n")
                    .append(nested).append("    ").append(statement).append('.')
                    .append(jdbcSetter(argumentField, argument.type())).append('(').append(parameter++).append(", ")
                    .append(scopedLocal("relayPageArgumentValue", argumentScope, "value")).append(");\n");
        }
        source.append(nested).append("    ").append(statement).append(".setBoolean(")
                .append(parameter++).append(", ").append(afterPresent).append(");\n")
                .append(nested).append("    ").append(statement).append(".setLong(")
                .append(parameter++).append(", ").append(afterValue).append(");\n")
                .append(nested).append("    ").append(statement).append(".setBoolean(")
                .append(parameter++).append(", ").append(beforePresent).append(");\n")
                .append(nested).append("    ").append(statement).append(".setLong(")
                .append(parameter++).append(", ").append(beforeValue).append(");\n")
                .append(nested).append("    ").append(statement).append(".setLong(")
                .append(parameter).append(", ").append(pageSize).append(" + 1L);\n")
                .append(nested).append("    ResultSet ").append(resultSet).append(" = ")
                .append(statement).append(".executeQuery();\n")
                .append(nested).append("    while (").append(resultSet).append(".next()) {\n");
        if (mysql) source.append(nested).append("        if (response.length() != 0) break;\n");
        source.append(nested).append("        if (").append(decodedApplicationRows).append(" >= ")
                .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("L) {\n");
        if (mysql) {
            source.append(nested).append("            response = DatabaseGraphqlEngine.resourceLimitErrorJsonAt(\"request exceeds decoded application row budget of ")
                    .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("\", query, ")
                    .append(selectedStart).append("); break;\n");
        } else {
            source.append(nested).append("            return DatabaseGraphqlEngine.resourceLimitErrorJsonAt(\"request exceeds decoded application row budget of ")
                    .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("\", query, ")
                    .append(selectedStart).append(");\n");
        }
        source.append(nested).append("        }\n")
                .append(nested).append("        ").append(decodedApplicationRows).append("++;\n")
                .append(nested).append("        int ").append(returnedSlot).append(" = ")
                .append(resultSet).append(".getInt(\"tgql_batch_parent_index\");\n")
                .append(nested).append("        int ").append(parentIndex).append(" = ")
                .append(offset).append(" + ").append(returnedSlot).append(";\n")
                .append(nested).append("        long ").append(rowOrdinal).append(" = ")
                .append(resultSet).append(".getLong(\"tgql_batch_row\");\n")
                .append(nested).append("        if (").append(returnedSlot).append(" < 0 || ")
                .append(returnedSlot).append(" >= ").append(MAX_STATIC_RELATION_BATCH_KEYS)
                .append(" || ").append(parentIndex).append(" >= ").append(keyCount)
                .append(" || !DatabaseGraphqlEngine.batchParentActive(").append(activity).append(", ")
                .append(parentIndex).append(")) {\n");
        emitInternalFailureAt(source, mysql, nested + "            ",
                "relation Relay page batch returned a row outside its reviewed parent keys", selectedStart);
        source.append(nested).append("        } else if (").append(rowOrdinal).append(" > ")
                .append(pageSize).append(") {\n")
                .append(nested).append("            ").append(pageRows)
                .append(" = DatabaseGraphqlEngine.appendBatchRelationItem(").append(pageRows)
                .append(", ").append(parentIndex).append(", \"null\");\n")
                .append(nested).append("        } else {\n")
                .append(nested).append("            String ").append(parentPath)
                .append(" = DatabaseGraphqlEngine.batchParentKey(").append(paths).append(", ")
                .append(parentIndex).append(");\n")
                .append(nested).append("            String ").append(relationPath)
                .append(" = DatabaseGraphqlEngine.appendExecutionPath(").append(parentPath)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                .append(selectedStart).append(", \"").append(javaString(relation.name())).append("\"));\n")
                .append(nested).append("            String ").append(edgePath)
                .append(" = DatabaseGraphqlEngine.appendExecutionPathIndex(DatabaseGraphqlEngine.appendExecutionPath(")
                .append(relationPath).append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                .append(edgesStart).append(", \"edges\")), ").append(rowOrdinal).append(" - 1L);\n")
                .append(nested).append("            String ").append(nodePath)
                .append(" = DatabaseGraphqlEngine.appendExecutionPath(").append(edgePath)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                .append(nodeStart).append(", \"node\"));\n")
                .append(nested).append("            String ").append(nodeValue).append(" = \"\";\n")
                .append(nested).append("            boolean ").append(nodeNull).append(" = false;\n");
        emitRelationObject(source, document, root, targetType, batchScope, nodePlan, nodeCount, resultSet,
                nodeValue, true, mysql, nested + "            ", nodePath, rowOrdinal + " - 1L", nodeNull,
                relationHops, activeSelectionHopBudget, edgeAncestorPath,
                decodedApplicationRows, true, parentIndex);
        TitanGraphqlFieldDocument cursorField = fieldByColumn(targetType, cursor.column());
        source.append(nested).append("            String ").append(emittedCursor)
                .append(" = DatabaseGraphqlEngine.relayCursorForLong(\"")
                .append(javaString(cursor.path())).append("\", \"").append(javaString(cursor.path()))
                .append("\", \"")
                .append(cursor.direction() == TitanGraphqlRootDocument.RootDocumentSortDirection.DESC ? "DESC" : "ASC")
                .append("\", \"").append(javaString(cursor.tieBreaker().isBlank() ? "id" : cursor.tieBreaker()))
                .append("\", ").append(scopedLocal("fieldValue", batchScope, cursorField.name())).append(");\n")
                .append(nested).append("            String ").append(edgeMembers).append(" = \"\";\n")
                .append(nested).append("            int ").append(edgeIndex).append(" = 0;\n")
                .append(nested).append("            while (").append(edgeIndex).append(" < ")
                .append(edgesCount).append(") {\n")
                .append(nested).append("                int ").append(edgeFieldStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStart(").append(edgesPlan)
                .append(", ").append(edgeIndex).append(");\n")
                .append(nested).append("                if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(edgeFieldStart).append(", \"cursor\")) ").append(edgeMembers)
                .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(edgeMembers)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                .append(edgeFieldStart).append(", \"cursor\"), DatabaseGraphqlEngine.jsonString(")
                .append(emittedCursor).append("));\n")
                .append(nested).append("                else if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(edgeFieldStart).append(", \"node\")) ").append(edgeMembers)
                .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(edgeMembers)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                .append(edgeFieldStart).append(", \"node\"), ").append(nodeValue).append(");\n")
                .append(nested).append("                else if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(edgeFieldStart).append(", \"__typename\")) ").append(edgeMembers)
                .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(edgeMembers)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                .append(edgeFieldStart).append(", \"__typename\"), DatabaseGraphqlEngine.jsonString(\"")
                .append(javaString(edgeType)).append("\"));\n")
                .append(nested).append("                ").append(edgeIndex).append("++;\n")
                .append(nested).append("            }\n")
                .append(nested).append("            String ").append(edgeValue).append(" = \"{\" + ")
                .append(edgeMembers).append(" + \"}\";\n")
                .append(nested).append("            if (").append(nodeNull).append(") ")
                .append(propagatedNull).append(" = true;\n")
                .append(nested).append("            else {\n")
                .append(nested).append("                ").append(pageRows)
                .append(" = DatabaseGraphqlEngine.appendBatchRelationItem(").append(pageRows)
                .append(", ").append(parentIndex).append(", ").append(edgeValue).append(");\n")
                .append(nested).append("                ").append(cursors)
                .append(" = DatabaseGraphqlEngine.appendBatchRelationItem(").append(cursors)
                .append(", ").append(parentIndex).append(", ").append(emittedCursor).append(");\n")
                .append(nested).append("            }\n")
                .append(nested).append("        }\n")
                .append(nested).append("    }\n")
                .append(nested).append("    ").append(offset).append(" = ").append(offset).append(" + ")
                .append(MAX_STATIC_RELATION_BATCH_KEYS).append(";\n");
        if (mysql) source.append(nested).append("    }\n");
        source.append(nested).append("}\n");
        for (TitanGraphqlRelationDocument child : selectableRelations(targetType)) {
            int childHops = relationHops + 1;
            int childBudget = Math.min(activeSelectionHopBudget, child.selectionHopBudget());
            if (childHops <= childBudget && supportsUnpaginatedRelationBatch(targetType, child, document)) {
                emitUnpaginatedRelationPlanBatches(source, document, root, targetType, child, mysql, batchScope,
                        pageRows, propagatedNull, decodedApplicationRows, edgeAncestorPath,
                        childHops, childBudget, pageRows, batchParentOwners(batchScope, child));
            }
        }

        // Exact count is optional and shares the already-materialized equality arguments.
        source.append(nested).append("if (").append(totalCountStart).append(" >= 0")
                .append(mysql ? " && response.length() == 0" : "").append(") {\n")
                .append(nested).append("    ").append(offset).append(" = 0;\n")
                .append(nested).append("    while (").append(offset).append(" < ").append(keyCount)
                .append(mysql ? " && response.length() == 0" : "").append(") {\n");
        emitExecutionDeadlineCheck(source, mysql, nested + "        ", false);
        emitApplicationStatementReservation(source, mysql, nested + "        ", false, selectedStart);
        if (mysql) source.append(nested).append("        if (response.length() == 0) {\n");
        source.append(nested).append("        PreparedStatement ").append(countStatement)
                .append(" = connection.prepareStatement(\"")
                .append(javaString(relationConnectionBatchCountSql(
                        document, sourceType.name(), relation.name(), !mysql))).append("\");\n");
        parameter = emitFixedRelationBatchKeyParameters(
                source, nested + "        ", countStatement, offset, keyCount, activity, keys, keyField, 1);
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : contract.arguments()) {
            String argumentScope = batchScope + "_" + argument.name();
            TitanGraphqlFieldDocument argumentField = fieldByColumn(targetType, argument.column());
            source.append(nested).append("        ").append(countStatement).append(".setBoolean(")
                    .append(parameter++).append(", ")
                    .append(scopedLocal("relayPageArgumentPresent", argumentScope, "value")).append(");\n")
                    .append(nested).append("        ").append(countStatement).append('.')
                    .append(jdbcSetter(argumentField, argument.type())).append('(').append(parameter++).append(", ")
                    .append(scopedLocal("relayPageArgumentValue", argumentScope, "value")).append(");\n");
        }
        source.append(nested).append("        ResultSet ").append(countResultSet).append(" = ")
                .append(countStatement).append(".executeQuery();\n")
                .append(nested).append("        while (").append(countResultSet).append(".next()) {\n");
        if (mysql) source.append(nested).append("            if (response.length() != 0) break;\n");
        source.append(nested).append("            if (").append(decodedApplicationRows).append(" >= ")
                .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("L) {\n");
        if (mysql) {
            source.append(nested).append("                response = DatabaseGraphqlEngine.resourceLimitErrorJsonAt(\"request exceeds decoded application row budget of ")
                    .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("\", query, ")
                    .append(selectedStart).append("); break;\n");
        } else {
            source.append(nested).append("                return DatabaseGraphqlEngine.resourceLimitErrorJsonAt(\"request exceeds decoded application row budget of ")
                    .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("\", query, ")
                    .append(selectedStart).append(");\n");
        }
        source.append(nested).append("            }\n")
                .append(nested).append("            int ").append(returnedSlot).append(" = ")
                .append(countResultSet).append(".getInt(\"tgql_batch_parent_index\");\n")
                .append(nested).append("            int ").append(parentIndex).append(" = ")
                .append(offset).append(" + ").append(returnedSlot).append(";\n")
                .append(nested).append("            long total = ").append(countResultSet)
                .append(".getLong(\"tgql_total_count\");\n")
                .append(nested).append("            ").append(decodedApplicationRows).append("++;\n")
                .append(nested).append("            if (").append(returnedSlot).append(" < 0 || ")
                .append(returnedSlot).append(" >= ").append(MAX_STATIC_RELATION_BATCH_KEYS)
                .append(" || ").append(parentIndex).append(" >= ").append(keyCount)
                .append(" || !DatabaseGraphqlEngine.batchParentActive(").append(activity).append(", ")
                .append(parentIndex).append(")) {\n");
        emitInternalFailureAt(source, mysql, nested + "                ",
                "relation Relay count batch returned a row outside its reviewed parent keys", selectedStart);
        source.append(nested).append("            } else {\n")
                .append(nested).append("                ").append(counts)
                .append(" = DatabaseGraphqlEngine.appendBatchRelationItem(").append(counts)
                .append(", ").append(parentIndex).append(", \"\" + total);\n")
                .append(nested).append("            }\n")
                .append(nested).append("        }\n")
                .append(nested).append("        ").append(offset).append(" = ").append(offset).append(" + ")
                .append(MAX_STATIC_RELATION_BATCH_KEYS).append(";\n");
        if (mysql) source.append(nested).append("        }\n");
        source.append(nested).append("    }\n")
                .append(nested).append("}\n");

        // Opposite-boundary probes are emitted only when the selected pageInfo field needs them.
        source.append(nested).append("if (((!").append(backward).append(" && ").append(afterPresent)
                .append(" && ").append(hasPreviousStart).append(" >= 0) || (").append(backward)
                .append(" && ").append(beforePresent).append(" && ").append(hasNextStart).append(" >= 0))")
                .append(mysql ? " && response.length() == 0" : "").append(") {\n")
                .append(nested).append("    ").append(offset).append(" = 0;\n")
                .append(nested).append("    while (").append(offset).append(" < ").append(keyCount)
                .append(mysql ? " && response.length() == 0" : "").append(") {\n");
        emitExecutionDeadlineCheck(source, mysql, nested + "        ", false);
        emitApplicationStatementReservation(source, mysql, nested + "        ", false, selectedStart);
        if (mysql) source.append(nested).append("        if (response.length() == 0) {\n");
        source.append(nested).append("        PreparedStatement ").append(boundaryStatement)
                .append(" = connection.prepareStatement(\"")
                .append(javaString(relationConnectionBatchBoundarySql(
                        document, sourceType.name(), relation.name(), !mysql))).append("\");\n");
        parameter = emitFixedRelationBatchKeyParameters(
                source, nested + "        ", boundaryStatement, offset, keyCount, activity, keys, keyField, 1);
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : contract.arguments()) {
            String argumentScope = batchScope + "_" + argument.name();
            TitanGraphqlFieldDocument argumentField = fieldByColumn(targetType, argument.column());
            source.append(nested).append("        ").append(boundaryStatement).append(".setBoolean(")
                    .append(parameter++).append(", ")
                    .append(scopedLocal("relayPageArgumentPresent", argumentScope, "value")).append(");\n")
                    .append(nested).append("        ").append(boundaryStatement).append('.')
                    .append(jdbcSetter(argumentField, argument.type())).append('(').append(parameter++).append(", ")
                    .append(scopedLocal("relayPageArgumentValue", argumentScope, "value")).append(");\n");
        }
        source.append(nested).append("        ").append(boundaryStatement).append(".setBoolean(")
                .append(parameter++).append(", !").append(backward).append(" && ").append(afterPresent).append(");\n")
                .append(nested).append("        ").append(boundaryStatement).append(".setLong(")
                .append(parameter++).append(", ").append(afterValue).append(");\n")
                .append(nested).append("        ").append(boundaryStatement).append(".setBoolean(")
                .append(parameter++).append(", ").append(backward).append(" && ").append(beforePresent).append(");\n")
                .append(nested).append("        ").append(boundaryStatement).append(".setLong(")
                .append(parameter).append(", ").append(beforeValue).append(");\n")
                .append(nested).append("        ResultSet ").append(boundaryResultSet).append(" = ")
                .append(boundaryStatement).append(".executeQuery();\n")
                .append(nested).append("        while (").append(boundaryResultSet).append(".next()) {\n");
        if (mysql) source.append(nested).append("            if (response.length() != 0) break;\n");
        source.append(nested).append("            if (").append(decodedApplicationRows).append(" >= ")
                .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("L) {\n");
        if (mysql) {
            source.append(nested).append("                response = DatabaseGraphqlEngine.resourceLimitErrorJsonAt(\"request exceeds decoded application row budget of ")
                    .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("\", query, ")
                    .append(selectedStart).append("); break;\n");
        } else {
            source.append(nested).append("                return DatabaseGraphqlEngine.resourceLimitErrorJsonAt(\"request exceeds decoded application row budget of ")
                    .append(MAX_DECODED_APPLICATION_ROWS_PER_REQUEST).append("\", query, ")
                    .append(selectedStart).append(");\n");
        }
        source.append(nested).append("            }\n")
                .append(nested).append("            int ").append(returnedSlot).append(" = ")
                .append(boundaryResultSet).append(".getInt(\"tgql_batch_parent_index\");\n")
                .append(nested).append("            int ").append(parentIndex).append(" = ")
                .append(offset).append(" + ").append(returnedSlot).append(";\n")
                .append(nested).append("            boolean opposite = ").append(boundaryResultSet)
                .append(".getBoolean(\"tgql_has_opposite\");\n")
                .append(nested).append("            ").append(decodedApplicationRows).append("++;\n")
                .append(nested).append("            if (").append(returnedSlot).append(" < 0 || ")
                .append(returnedSlot).append(" >= ").append(MAX_STATIC_RELATION_BATCH_KEYS)
                .append(" || ").append(parentIndex).append(" >= ").append(keyCount)
                .append(" || !DatabaseGraphqlEngine.batchParentActive(").append(activity).append(", ")
                .append(parentIndex).append(")) {\n");
        emitInternalFailureAt(source, mysql, nested + "                ",
                "relation Relay boundary batch returned a row outside its reviewed parent keys", selectedStart);
        source.append(nested).append("            } else {\n")
                .append(nested).append("                ").append(boundaries)
                .append(" = DatabaseGraphqlEngine.appendBatchRelationItem(").append(boundaries)
                .append(", ").append(parentIndex).append(", opposite ? \"true\" : \"false\");\n")
                .append(nested).append("            }\n")
                .append(nested).append("        }\n")
                .append(nested).append("        ").append(offset).append(" = ").append(offset).append(" + ")
                .append(MAX_STATIC_RELATION_BATCH_KEYS).append(";\n");
        if (mysql) source.append(nested).append("        }\n");
        source.append(nested).append("    }\n")
                .append(nested).append("}\n")
                .append(nested).append("int ").append(replaceIndex).append(" = 0;\n")
                .append(nested).append("while (").append(replaceIndex).append(" < ").append(keyCount)
                .append(mysql ? " && response.length() == 0" : "").append(") {\n")
                .append(nested).append("    int ").append(recordCount)
                .append(" = DatabaseGraphqlEngine.batchRelationRecordCount(").append(pageRows).append(", ")
                .append(replaceIndex).append(");\n")
                .append(nested).append("    int ").append(cursorRecordCount)
                .append(" = DatabaseGraphqlEngine.batchRelationRecordCount(").append(cursors).append(", ")
                .append(replaceIndex).append(");\n")
                .append(nested).append("    if (").append(recordCount).append(" < 0 || ")
                .append(recordCount).append(" > ").append(pageSize).append(" + 1L || ")
                .append(cursorRecordCount).append(" != (").append(recordCount).append(" > ")
                .append(pageSize).append(" ? ").append(pageSize).append(" : ").append(recordCount)
                .append(")) {\n");
        emitInternalFailureAt(source, mysql, nested + "        ",
                "relation Relay page batch produced an invalid page carrier", selectedStart);
        source.append(nested).append("    }\n")
                .append(nested).append("    int ").append(countRecordCount)
                .append(" = DatabaseGraphqlEngine.batchRelationRecordCount(").append(counts).append(", ")
                .append(replaceIndex).append(");\n")
                .append(nested).append("    int ").append(boundaryRecordCount)
                .append(" = DatabaseGraphqlEngine.batchRelationRecordCount(").append(boundaries).append(", ")
                .append(replaceIndex).append(");\n")
                .append(nested).append("    boolean ").append(boundaryNeeded).append(" = (!")
                .append(backward).append(" && ").append(afterPresent).append(" && ")
                .append(hasPreviousStart).append(" >= 0) || (").append(backward).append(" && ")
                .append(beforePresent).append(" && ").append(hasNextStart).append(" >= 0);\n")
                .append(nested).append("    if (DatabaseGraphqlEngine.batchParentActive(").append(activity)
                .append(", ").append(replaceIndex).append(") && ((").append(totalCountStart)
                .append(" >= 0 && ").append(countRecordCount).append(" != 1) || (")
                .append(boundaryNeeded).append(" && ").append(boundaryRecordCount).append(" != 1))) {\n");
        emitInternalFailureAt(source, mysql, nested + "        ",
                "relation Relay page batch produced an incomplete scalar carrier", selectedStart);
        source.append(nested).append("    }\n")
                .append(nested).append("    long ").append(retainedCount).append(" = ").append(recordCount)
                .append(" > ").append(pageSize).append(" ? ").append(pageSize).append(" : ")
                .append(recordCount).append(";\n")
                .append(nested).append("    String ").append(edgesValue)
                .append(" = DatabaseGraphqlEngine.batchRelationLimitedValue(").append(pageRows).append(", ")
                .append(replaceIndex).append(", ").append(pageSize).append(", ")
                .append(backward).append(");\n")
                .append(nested).append("    if (").append(edgesValue).append(".length() == 0) {\n");
        emitInternalFailureAt(source, mysql, nested + "        ",
                "relation Relay page batch could not assemble its edge carrier", selectedStart);
        source.append(nested).append("    }\n")
                .append(nested).append("    if (").append(edgesValue).append(".equals(\"null\")) ")
                .append(propagatedNull).append(" = true;\n")
                .append(nested).append("    boolean ").append(hasNext).append(" = !").append(backward)
                .append(" && ").append(recordCount).append(" > ").append(pageSize).append(";\n")
                .append(nested).append("    boolean ").append(hasPrevious).append(" = ").append(backward)
                .append(" && ").append(recordCount).append(" > ").append(pageSize).append(";\n")
                .append(nested).append("    if (!").append(backward).append(" && ").append(afterPresent)
                .append(" && ").append(hasPreviousStart).append(" >= 0) ").append(hasPrevious)
                .append(" = DatabaseGraphqlEngine.batchRelationItem(").append(boundaries).append(", ")
                .append(replaceIndex).append(", 0).equals(\"true\");\n")
                .append(nested).append("    if (").append(backward).append(" && ").append(beforePresent)
                .append(" && ").append(hasNextStart).append(" >= 0) ").append(hasNext)
                .append(" = DatabaseGraphqlEngine.batchRelationItem(").append(boundaries).append(", ")
                .append(replaceIndex).append(", 0).equals(\"true\");\n")
                .append(nested).append("    String ").append(startCursor).append(" = \"\";\n")
                .append(nested).append("    String ").append(endCursor).append(" = \"\";\n")
                .append(nested).append("    if (").append(retainedCount).append(" > 0) {\n")
                .append(nested).append("        ").append(startCursor)
                .append(" = DatabaseGraphqlEngine.batchRelationItem(").append(cursors).append(", ")
                .append(replaceIndex).append(", ").append(backward).append(" ? ").append(retainedCount)
                .append(" - 1 : 0);\n")
                .append(nested).append("        ").append(endCursor)
                .append(" = DatabaseGraphqlEngine.batchRelationItem(").append(cursors).append(", ")
                .append(replaceIndex).append(", ").append(backward).append(" ? 0 : ").append(retainedCount)
                .append(" - 1);\n")
                .append(nested).append("    }\n")
                .append(nested).append("    String ").append(countValue).append(" = \"0\";\n")
                .append(nested).append("    if (").append(totalCountStart).append(" >= 0 && DatabaseGraphqlEngine.batchParentActive(")
                .append(activity).append(", ").append(replaceIndex).append(")) ").append(countValue)
                .append(" = DatabaseGraphqlEngine.batchRelationItem(").append(counts).append(", ")
                .append(replaceIndex).append(", 0);\n")
                .append(nested).append("    String ").append(connectionMembers).append(" = \"\";\n")
                .append(nested).append("    int ").append(connectionIndex).append(" = 0;\n")
                .append(nested).append("    while (").append(connectionIndex).append(" < ")
                .append(connectionCount).append(") {\n")
                .append(nested).append("        int ").append(connectionFieldStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStart(").append(connectionPlan)
                .append(", ").append(connectionIndex).append(");\n")
                .append(nested).append("        if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(connectionFieldStart).append(", \"edges\")) ").append(connectionMembers)
                .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(connectionMembers)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                .append(connectionFieldStart).append(", \"edges\"), ").append(edgesValue).append(");\n")
                .append(nested).append("        else if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(connectionFieldStart).append(", \"totalCount\")) ").append(connectionMembers)
                .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(connectionMembers)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                .append(connectionFieldStart).append(", \"totalCount\"), ").append(countValue).append(");\n")
                .append(nested).append("        else if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(connectionFieldStart).append(", \"pageInfo\")) {\n")
                .append(nested).append("            String ").append(pageInfoMembers).append(" = \"\";\n")
                .append(nested).append("            int ").append(pageInfoIndex).append(" = 0;\n")
                .append(nested).append("            while (").append(pageInfoIndex).append(" < ")
                .append(pageInfoCount).append(") {\n")
                .append(nested).append("                int ").append(pageInfoFieldStart)
                .append(" = DatabaseGraphqlEngine.selectionPlanFieldStart(").append(pageInfoPlan)
                .append(", ").append(pageInfoIndex).append(");\n")
                .append(nested).append("                String pageInfoValue = \"null\";\n")
                .append(nested).append("                if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(pageInfoFieldStart).append(", \"hasNextPage\")) pageInfoValue = ")
                .append(hasNext).append(" ? \"true\" : \"false\";\n")
                .append(nested).append("                else if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(pageInfoFieldStart).append(", \"hasPreviousPage\")) pageInfoValue = ")
                .append(hasPrevious).append(" ? \"true\" : \"false\";\n")
                .append(nested).append("                else if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(pageInfoFieldStart).append(", \"startCursor\")) pageInfoValue = ")
                .append(startCursor).append(".length() == 0 ? \"null\" : DatabaseGraphqlEngine.jsonString(")
                .append(startCursor).append(");\n")
                .append(nested).append("                else if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(pageInfoFieldStart).append(", \"endCursor\")) pageInfoValue = ")
                .append(endCursor).append(".length() == 0 ? \"null\" : DatabaseGraphqlEngine.jsonString(")
                .append(endCursor).append(");\n")
                .append(nested).append("                else if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(pageInfoFieldStart).append(", \"__typename\")) pageInfoValue = DatabaseGraphqlEngine.jsonString(\"PageInfo\");\n")
                .append(nested).append("                ").append(pageInfoMembers)
                .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(pageInfoMembers)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                .append(pageInfoFieldStart).append(", \"\"), pageInfoValue);\n")
                .append(nested).append("                ").append(pageInfoIndex).append("++;\n")
                .append(nested).append("            }\n")
                .append(nested).append("            ").append(connectionMembers)
                .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(connectionMembers)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                .append(connectionFieldStart).append(", \"pageInfo\"), \"{\" + ")
                .append(pageInfoMembers).append(" + \"}\");\n")
                .append(nested).append("        } else if (DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                .append(connectionFieldStart).append(", \"__typename\")) ").append(connectionMembers)
                .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(connectionMembers)
                .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                .append(connectionFieldStart).append(", \"__typename\"), DatabaseGraphqlEngine.jsonString(\"")
                .append(javaString(connectionType)).append("\"));\n")
                .append(nested).append("        ").append(connectionIndex).append("++;\n")
                .append(nested).append("    }\n")
                .append(nested).append("    String ").append(connectionValue).append(" = \"{\" + ")
                .append(connectionMembers).append(" + \"}\";\n")
                .append(nested).append("    int ").append(replaceOwner)
                .append(" = DatabaseGraphqlEngine.batchParentOwnerIndex(").append(owners).append(", ")
                .append(replaceIndex).append(");\n")
                .append(nested).append("    if (").append(replaceOwner).append(" < 0) {\n");
        emitInternalFailureAt(source, mysql, nested + "        ",
                "relation Relay page batch produced an invalid parent-owner carrier", selectedStart);
        source.append(nested).append("    }\n")
                .append(nested).append("    ").append(parentJsonCarrier)
                .append(" = DatabaseGraphqlEngine.replaceBatchRelationPlaceholder(")
                .append(parentJsonCarrier).append(", ").append(selectedStart).append(", ")
                .append(replaceOwner).append(", ").append(connectionValue).append(");\n")
                .append(nested).append("    if (").append(parentJsonCarrier).append(".length() == 0) {\n");
        emitInternalFailureAt(source, mysql, nested + "        ",
                "relation Relay page batch placeholder was missing or ambiguous", selectedStart);
        source.append(nested).append("    }\n")
                .append(nested).append("    if (DatabaseGraphqlEngine.responseAssemblyExceeded(")
                .append(parentJsonCarrier).append(")) {\n");
        if (mysql) {
            source.append(nested).append("        response = DatabaseGraphqlEngine.resourceLimitErrorJson(\"response exceeds the database engine character budget\");\n");
        } else {
            source.append(nested).append("        return DatabaseGraphqlEngine.resourceLimitErrorJson(\"response exceeds the database engine character budget\");\n");
        }
        source.append(nested).append("    }\n")
                .append(nested).append("    ").append(replaceIndex).append("++;\n")
                .append(nested).append("}\n")
                .append(indent).append("}\n");
    }

    private static void emitRelationObject(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument targetType,
            String relationScope,
            String relationPlan,
            String relationCount,
            String resultSet,
            String destination,
            boolean one,
            boolean mysql,
            String indent,
            String relationPath,
            String itemIndex,
            String propagatedNull,
            int relationHops,
            int activeSelectionHopBudget,
            String ancestorTypePath,
            String nestedRelationRows,
            boolean batchNestedRelations,
            String batchOwnerIndex
    ) {
        for (TitanGraphqlFieldDocument field : sortedFields(targetType)) {
            emitFieldRead(source, relationScope, field, resultSet, indent);
        }
        for (TitanGraphqlRelationDocument relation : selectableRelations(targetType)) {
            emitRelationJoinRead(source, document, targetType, relationScope, relation, resultSet, indent);
        }
        String objectMembers = scopedLocal("relationObjectMembers", relationScope, "value");
        String object = scopedLocal("relationObject", relationScope, "value");
        String index = scopedLocal("relationOutputIndex", relationScope, "value");
        String fieldStart = scopedLocal("relationOutputStart", relationScope, "value");
        String rendered = scopedLocal("relationOutputRendered", relationScope, "value");
        String objectPath = scopedLocal("relationObjectPath", relationScope, "value");
        source.append(indent).append("String ").append(objectPath).append(" = ");
        if (one) {
            source.append(relationPath);
        } else {
            source.append("DatabaseGraphqlEngine.appendExecutionPathIndex(").append(relationPath)
                    .append(", ").append(itemIndex).append(")");
        }
        source.append(";\n");
        source.append(indent).append("String ").append(objectMembers).append(" = \"\";\n");
        source.append(indent).append("int ").append(index).append(" = 0;\n")
                .append(indent).append("while (").append(index).append(" < ").append(relationCount)
                .append(mysql ? " && response.length() == 0" : "").append(" && !").append(propagatedNull)
                .append(") {\n");
        // Relation selection rendering is bounded by the request AST, but it is repeated for
        // every returned child. Recheck before each per-row selection walk as well as at the
        // ResultSet boundary so nested JSON assembly cannot run unchecked past the deadline.
        emitExecutionDeadlineCheck(source, mysql, indent + "    ", false);
        if (mysql) {
            source.append(indent).append("    if (response.length() == 0) {\n");
        }
        source.append(indent).append("    int ").append(fieldStart).append(" = DatabaseGraphqlEngine.selectionPlanFieldStart(")
                .append(relationPlan).append(", ").append(index).append(");\n")
                .append(indent).append("    boolean ").append(rendered).append(" = false;\n");
        emitTypeNameOutput(source, indent + "    ", rendered, fieldStart, targetType.name(), objectMembers, mysql);
        for (TitanGraphqlFieldDocument field : sortedFields(targetType)) {
            String value = scopedLocal("fieldValue", relationScope, field.name());
            source.append(indent).append("    if (!").append(rendered).append(" && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                    .append(fieldStart).append(", \"").append(javaString(field.name())).append("\")) {\n");
            emitFieldOutputWithNonNullPropagation(source, document, field, targetType.name(), fieldStart, value,
                    scopedLocal("fieldNull", relationScope, field.name()), objectMembers, indent + "        ",
                    objectPath, propagatedNull, mysql);
            source.append(indent).append("        ").append(rendered).append(" = true;\n")
                    .append(indent).append("    }\n");
        }
        for (TitanGraphqlRelationDocument nested : selectableRelations(targetType)) {
            int nextRelationHops = relationHops + 1;
            int nextSelectionHopBudget = Math.min(activeSelectionHopBudget, nested.selectionHopBudget());
            if (nextRelationHops <= nextSelectionHopBudget) {
                source.append(indent).append("    if (!").append(propagatedNull).append(" && !").append(rendered)
                        .append(" && DatabaseGraphqlEngine.rootFieldMatchesFromAst(query, requestAst, ")
                        .append(fieldStart).append(", \"").append(javaString(nested.name())).append("\")) {\n");
                if (batchNestedRelations && supportsUnpaginatedRelationBatch(targetType, nested, document)) {
                    TitanGraphqlFieldDocument sourceField = fieldByColumn(targetType, nested.localColumn());
                    boolean hiddenSourceColumn = sourceField == null;
                    if (hiddenSourceColumn) {
                        sourceField = relationJoinField(document, targetType, nested);
                    }
                    String keys = batchParentKeys(relationScope, nested);
                    String activity = batchParentActivity(relationScope, nested);
                    String paths = batchParentPaths(relationScope, nested);
                    String owners = batchParentOwners(relationScope, nested);
                    String planStarts = batchPlanStarts(relationScope, nested);
                    String parentIndex = scopedLocal("batchParentIndex", relationScope, nested.name());
                    String relationValue = scopedLocal("batchRelationValue", relationScope, nested.name());
                    String sourceValue = hiddenSourceColumn
                            ? scopedLocal("relationJoinValue", relationScope, nested.name())
                            : scopedLocal("fieldValue", relationScope, sourceField.name());
                    String sourceNull = hiddenSourceColumn
                            ? scopedLocal("relationJoinNull", relationScope, nested.name())
                            : scopedLocal("fieldNull", relationScope, sourceField.name());
                    source.append(indent).append("        int ").append(parentIndex)
                            .append(" = DatabaseGraphqlEngine.batchParentKeyCount(").append(keys).append(");\n")
                            .append(indent).append("        ").append(keys)
                            .append(" = DatabaseGraphqlEngine.appendBatchParentKey(").append(keys).append(", ")
                            .append(batchKeyString(sourceField, sourceValue)).append(");\n")
                            .append(indent).append("        ").append(activity)
                            .append(" = DatabaseGraphqlEngine.appendBatchParentActivity(").append(activity)
                            .append(", !").append(sourceNull).append(");\n")
                            .append(indent).append("        ").append(paths)
                            .append(" = DatabaseGraphqlEngine.appendBatchParentKey(").append(paths)
                            .append(", ").append(objectPath).append(");\n")
                            .append(indent).append("        ").append(owners)
                            .append(" = DatabaseGraphqlEngine.appendBatchParentKey(").append(owners)
                            .append(", \"\" + ").append(batchOwnerIndex).append(");\n")
                            .append(indent).append("        ").append(planStarts)
                            .append(" = DatabaseGraphqlEngine.appendBatchParentKey(").append(planStarts)
                            .append(", \"\" + ").append(fieldStart).append(");\n")
                            .append(indent).append("        if (DatabaseGraphqlEngine.responseAssemblyExceeded(")
                            .append(keys).append(") || DatabaseGraphqlEngine.responseAssemblyExceeded(")
                            .append(activity).append(") || DatabaseGraphqlEngine.responseAssemblyExceeded(")
                            .append(paths).append(") || DatabaseGraphqlEngine.responseAssemblyExceeded(")
                            .append(owners).append(") || DatabaseGraphqlEngine.responseAssemblyExceeded(")
                            .append(planStarts).append(")) {\n");
                    if (mysql) {
                        source.append(indent).append("            response = DatabaseGraphqlEngine.resourceLimitErrorJson(\"nested relation batch carriers exceed the database engine character budget\");\n");
                    } else {
                        source.append(indent).append("            return DatabaseGraphqlEngine.resourceLimitErrorJson(\"nested relation batch carriers exceed the database engine character budget\");\n");
                    }
                    source.append(indent).append("        }\n")
                            .append(indent).append("        String ").append(relationValue)
                            .append(" = DatabaseGraphqlEngine.batchRelationPlaceholder(").append(fieldStart)
                            .append(", ").append(parentIndex).append(");\n")
                            .append(indent).append("        ").append(objectMembers)
                            .append(" = DatabaseGraphqlEngine.appendJsonMember(").append(objectMembers)
                            .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                            .append(fieldStart).append(", \"").append(javaString(nested.name())).append("\"), ")
                            .append(relationValue).append(");\n");
                } else {
                    emitRelationOutput(source, document, root, targetType, nested, fieldStart, mysql, objectPath,
                            propagatedNull, relationScope, objectMembers, nextRelationHops, nextSelectionHopBudget,
                            ancestorTypePath + "|" + runtimeTypeConditionPathEntry(document, targetType),
                            nestedRelationRows);
                }
                source.append(indent).append("        ").append(rendered).append(" = true;\n")
                        .append(indent).append("    }\n");
            }
        }
        source.append(indent).append("    if (!").append(rendered)
                .append(mysql ? " && response.length() == 0" : "").append(") {\n");
        emitInternalFailureAt(source, mysql, indent + "        ",
                "relation selection changed after validation for '" + targetType.name() + "'", fieldStart);
        source.append(indent).append("    }\n")
                .append(indent).append("    ").append(index).append("++;\n");
        if (mysql) {
            source.append(indent).append("    }\n");
        }
        source.append(indent).append("}\n");
        source.append(indent).append("String ").append(object).append(" = \"{\" + ")
                .append(objectMembers).append(" + \"}\";\n");
        if (one) {
            source.append(indent).append(destination).append(" = ").append(object).append(";\n");
        } else {
            source.append(indent).append(destination).append(" = DatabaseGraphqlEngine.appendJsonItem(")
                    .append(destination).append(", ").append(object).append(");\n");
        }
        if (mysql) {
            source.append(indent).append("if (response.length() == 0 && DatabaseGraphqlEngine.responseAssemblyExceeded(")
                    .append(destination).append(")) { response = DatabaseGraphqlEngine.resourceLimitErrorJson(\"response exceeds the database engine character budget\"); }\n");
        } else {
            source.append(indent).append("if (DatabaseGraphqlEngine.responseAssemblyExceeded(")
                    .append(destination).append(")) return DatabaseGraphqlEngine.resourceLimitErrorJson(\"response exceeds the database engine character budget\");\n");
        }
    }

    private static void emitRelationUnsupported(
            StringBuilder source,
            TitanGraphqlTypeDocument sourceType,
            TitanGraphqlRelationDocument relation,
            boolean mysql,
            String indent,
            String reason
    ) {
        String message = "relation '" + sourceType.name() + "." + relation.name() + "' needs " + reason
                + " support in the database engine proof";
        if (mysql) {
            source.append(indent).append("response = DatabaseGraphqlEngine.unsupportedOperationErrorJson(\"")
                    .append(javaString(message)).append("\");\n");
        } else {
            source.append(indent).append("return DatabaseGraphqlEngine.unsupportedOperationErrorJson(\"")
                    .append(javaString(message)).append("\");\n");
        }
    }

    private static void emitFieldRead(StringBuilder source, String rootName, TitanGraphqlFieldDocument field) {
        emitFieldRead(source, rootName, field, "resultSet", "        ");
    }

    private static void emitFieldRead(
            StringBuilder source,
            String rootName,
            TitanGraphqlFieldDocument field,
            String resultSet,
            String indent
    ) {
        String value = scopedLocal("fieldValue", rootName, field.name());
        String nullFlag = scopedLocal("fieldNull", rootName, field.name());
        source.append(indent).append("boolean ").append(nullFlag).append(" = ").append(resultSet)
                .append(".getBoolean(\"").append(javaString(nullableFieldAlias(field))).append("\");\n");
        if (isLong(field)) {
            source.append(indent).append("long ").append(value).append(" = ").append(resultSet).append(".getLong(\"")
                    .append(javaString(field.name())).append("\");\n");
        } else if (isBoolean(field.type())) {
            source.append(indent).append("boolean ").append(value).append(" = ").append(resultSet).append(".getBoolean(\"")
                    .append(javaString(field.name())).append("\");\n");
        } else if (isDecimal(field.type())) {
            source.append(indent).append("double ").append(value).append(" = ").append(resultSet).append(".getDouble(\"")
                    .append(javaString(field.name())).append("\");\n");
        } else {
            source.append(indent).append("String ").append(value).append(" = ").append(resultSet).append(".getString(\"")
                    .append(javaString(field.name())).append("\");\n");
        }
    }

    private static String connectionCustomOrderRowLocal(
            String scope,
            TitanGraphqlRootDocument.RootDocumentSortPath sort
    ) {
        return scopedLocal("connectionCustomOrderRowValue", scope, sort.name());
    }

    private static void emitConnectionCustomOrderRead(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            String scope,
            TitanGraphqlRootDocument.RootDocumentSortPath sort,
            String resultSet,
            String indent
    ) {
        TitanGraphqlFieldDocument field = connectionCustomOrderField(document, type, root, sort);
        String getter = isLong(field) ? "getLong" : "getString";
        String javaType = isLong(field) ? "long" : "String";
        source.append(indent).append(javaType).append(" ").append(connectionCustomOrderRowLocal(scope, sort))
                .append(" = ").append(resultSet).append(".").append(getter).append("(\"")
                .append(javaString(connectionCustomOrderProjectionAlias(sort))).append("\");\n");
    }

    /**
     * Reads a physical local join column which is deliberately not exposed as a GraphQL field.
     * Relation traversal still needs it to bind the next database statement; projection aliases
     * keep that carrier private to the generated routine.
     */
    private static void emitRelationJoinRead(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument sourceType,
            String sourceScope,
            TitanGraphqlRelationDocument relation,
            String resultSet,
            String indent
    ) {
        if (fieldByColumn(sourceType, relation.localColumn()) != null) {
            return;
        }
        TitanGraphqlFieldDocument joinField = relationJoinField(document, sourceType, relation);
        if (joinField == null) {
            return;
        }
        String value = scopedLocal("relationJoinValue", sourceScope, relation.name());
        String nullFlag = scopedLocal("relationJoinNull", sourceScope, relation.name());
        source.append(indent).append("boolean ").append(nullFlag).append(" = ").append(resultSet)
                .append(".getBoolean(\"").append(javaString(relationJoinNullAlias(relation))).append("\");\n");
        String getter = isLong(joinField) ? "getLong"
                : isBoolean(joinField.type()) ? "getBoolean"
                : isDecimal(joinField.type()) ? "getDouble"
                : "getString";
        String javaType = isLong(joinField) ? "long"
                : isBoolean(joinField.type()) ? "boolean"
                : isDecimal(joinField.type()) ? "double"
                : "String";
        source.append(indent).append(javaType).append(" ").append(value).append(" = ").append(resultSet)
                .append(".").append(getter).append("(\"").append(javaString(relationJoinAlias(relation))).append("\");\n");
    }

    private static void emitMySqlRelationJoinDeclaration(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument sourceType,
            String sourceScope,
            TitanGraphqlRelationDocument relation,
            String indent
    ) {
        if (fieldByColumn(sourceType, relation.localColumn()) != null) {
            return;
        }
        TitanGraphqlFieldDocument joinField = relationJoinField(document, sourceType, relation);
        if (joinField == null) {
            return;
        }
        String value = scopedLocal("relationJoinValue", sourceScope, relation.name());
        String nullFlag = scopedLocal("relationJoinNull", sourceScope, relation.name());
        source.append(indent).append("boolean ").append(nullFlag).append(" = true;\n");
        if (isLong(joinField)) {
            source.append(indent).append("long ").append(value).append(" = 0L;\n");
        } else if (isBoolean(joinField.type())) {
            source.append(indent).append("boolean ").append(value).append(" = false;\n");
        } else if (isDecimal(joinField.type())) {
            source.append(indent).append("double ").append(value).append(" = 0.0;\n");
        } else {
            source.append(indent).append("String ").append(value).append(" = \"\";\n");
        }
    }

    private static void emitMySqlRelationJoinAssignment(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument sourceType,
            String sourceScope,
            TitanGraphqlRelationDocument relation,
            String resultSet,
            String indent
    ) {
        if (fieldByColumn(sourceType, relation.localColumn()) != null) {
            return;
        }
        TitanGraphqlFieldDocument joinField = relationJoinField(document, sourceType, relation);
        if (joinField == null) {
            return;
        }
        String value = scopedLocal("relationJoinValue", sourceScope, relation.name());
        String nullFlag = scopedLocal("relationJoinNull", sourceScope, relation.name());
        String getter = isLong(joinField) ? "getLong"
                : isBoolean(joinField.type()) ? "getBoolean"
                : isDecimal(joinField.type()) ? "getDouble"
                : "getString";
        source.append(indent).append(nullFlag).append(" = ").append(resultSet).append(".getBoolean(\"")
                .append(javaString(relationJoinNullAlias(relation))).append("\");\n")
                .append(indent).append(value).append(" = ").append(resultSet).append(".").append(getter)
                .append("(\"").append(javaString(relationJoinAlias(relation))).append("\");\n");
    }

    private static void emitMySqlFieldDeclaration(
            StringBuilder source,
            String rootName,
            TitanGraphqlFieldDocument field
    ) {
        String value = scopedLocal("fieldValue", rootName, field.name());
        String nullFlag = scopedLocal("fieldNull", rootName, field.name());
        source.append("                    boolean ").append(nullFlag).append(" = true;\n");
        if (isLong(field)) {
            source.append("                    long ").append(value).append(" = 0L;\n");
        } else if (isBoolean(field.type())) {
            source.append("                    boolean ").append(value).append(" = false;\n");
        } else if (isDecimal(field.type())) {
            source.append("                    double ").append(value).append(" = 0.0;\n");
        } else {
            source.append("                    String ").append(value).append(" = \"\";\n");
        }
    }

    private static void emitMySqlFieldAssignment(
            StringBuilder source,
            String rootName,
            TitanGraphqlFieldDocument field,
            String resultSet,
            String indent
    ) {
        String value = scopedLocal("fieldValue", rootName, field.name());
        String nullFlag = scopedLocal("fieldNull", rootName, field.name());
        source.append(indent).append(nullFlag).append(" = ").append(resultSet).append(".getBoolean(\"")
                .append(javaString(nullableFieldAlias(field))).append("\");\n");
        String getter = isLong(field) ? "getLong"
                : isBoolean(field.type()) ? "getBoolean"
                : isDecimal(field.type()) ? "getDouble"
                : "getString";
        source.append(indent).append(value).append(" = ").append(resultSet).append('.').append(getter)
                .append("(\"").append(javaString(field.name())).append("\");\n");
    }

    private static void emitFieldOutput(StringBuilder source, String rootName, TitanGraphqlFieldDocument field) {
        String fieldStart = scopedLocal("fieldStart", rootName, field.name());
        String value = scopedLocal("fieldValue", rootName, field.name());
        emitFieldOutput(source, field, fieldStart, value, scopedLocal("fieldNull", rootName, field.name()),
                "objectMembers", "        ");
    }

    private static void emitFieldOutput(
            StringBuilder source,
            TitanGraphqlFieldDocument field,
            String fieldStart,
            String value,
            String nullFlag,
            String objectMembers,
            String indent
    ) {
        source.append(indent).append("if (").append(fieldStart).append(" >= 0) {\n");
        emitFieldJsonMember(source, field, fieldStart, value, nullFlag, objectMembers, indent + "    ", false);
        source.append(indent).append("}\n");
    }

    /**
     * Emits an object leaf while retaining the execution-time null marker for a reviewed
     * non-null field. Point roots are nullable, so a corrupt database value nulls that root and
     * contributes a GraphQL execution error while independent roots can still complete.
     */
    private static void emitFieldOutputWithNonNullPropagation(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            TitanGraphqlFieldDocument field,
            String parentType,
            String fieldStart,
            String value,
            String nullFlag,
            String objectMembers,
            String indent,
            String objectPath,
            String propagatedNull,
            boolean mysql
    ) {
        source.append(indent).append("if (").append(fieldStart).append(" >= 0 && !")
                .append(propagatedNull).append(") {\n");
        TitanGraphqlEnumDocument enumType = enumType(document, inputNamedType(field.type()));
        String enumInvalid = value + "EnumInvalid";
        if (enumType != null) {
            source.append(indent).append("    boolean ").append(enumInvalid).append(" = !")
                    .append(nullFlag).append(" && !DatabaseGraphqlEngine.enumValueIsAllowed(")
                    .append(value).append(", \"")
                    .append(javaString(String.join("|", sortedEnumValues(enumType)))).append("\");\n")
                    .append(indent).append("    if (").append(enumInvalid).append(") {\n")
                    .append(indent).append("        executionErrors = DatabaseGraphqlEngine.appendExecutionError(")
                    .append("executionErrors, \"Enum '").append(javaString(enumType.name()))
                    .append("' cannot represent the stored value.\", ")
                    .append("DatabaseGraphqlEngine.appendExecutionPath(").append(objectPath)
                    .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                    .append(fieldStart).append(", \"").append(javaString(field.name()))
                    .append("\")), query, ").append(fieldStart).append(");\n")
                    .append(indent).append("    }\n");
        }
        if (!field.nullable()) {
            source.append(indent).append("    if (").append(nullFlag)
                    .append(enumType == null ? "" : " || " + enumInvalid).append(") {\n");
            if (enumType != null) {
                source.append(indent).append("        if (!").append(enumInvalid).append(") {\n");
            }
            source.append(indent).append(enumType == null ? "        " : "            ")
                    .append("executionErrors = DatabaseGraphqlEngine.appendExecutionError(")
                    .append("executionErrors, \"Cannot return null for non-nullable field ")
                    .append(javaString(parentType)).append('.').append(javaString(field.name())).append(".\", ")
                    .append("DatabaseGraphqlEngine.appendExecutionPath(").append(objectPath)
                    .append(", DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ")
                    .append(fieldStart).append(", \"").append(javaString(field.name()))
                    .append("\")), query, ").append(fieldStart).append(");\n");
            if (enumType != null) {
                source.append(indent).append("        }\n");
            }
            source
                    .append(indent).append("        ").append(propagatedNull).append(" = true;\n")
                    .append(indent).append("    } else {\n");
            emitFieldJsonMember(source, field, fieldStart, value, nullFlag, objectMembers, indent + "        ", mysql);
            source.append(indent).append("    }\n");
        } else {
            if (enumType != null) {
                source.append(indent).append("    if (").append(enumInvalid).append(") ")
                        .append(nullFlag).append(" = true;\n");
            }
            emitFieldJsonMember(source, field, fieldStart, value, nullFlag, objectMembers, indent + "    ", mysql);
        }
        source.append(indent).append("}\n");
    }

    /** Renders one already-selected scalar member; selection and propagation are owned by callers. */
    private static void emitFieldJsonMember(
            StringBuilder source,
            TitanGraphqlFieldDocument field,
            String fieldStart,
            String value,
            String nullFlag,
            String objectMembers,
            String indent,
            boolean mysql
    ) {
        source.append(indent).append("    String ").append(value).append("Json = ");
        if (field.nullable()) {
            source.append(nullFlag).append(" ? \"null\" : ");
        }
        if (isId(field.type())) {
            source.append("DatabaseGraphqlEngine.jsonString(\"\" + ").append(value).append(");\n");
        } else if (isLong(field) || isDecimal(field.type())) {
            source.append("\"\" + ").append(value).append(";\n");
        } else if (isBoolean(field.type())) {
            source.append("(").append(value).append(" ? \"true\" : \"false\");\n");
        } else {
            source.append("DatabaseGraphqlEngine.jsonString(").append(value).append(");\n");
        }
        source.append(indent).append("    ").append(objectMembers).append(" = DatabaseGraphqlEngine.appendJsonMember(")
                .append(objectMembers).append(", ")
                .append("DatabaseGraphqlEngine.responseKeyFromAst(query, requestAst, ").append(fieldStart).append(", \"")
                .append(javaString(field.name())).append("\"), ").append(value).append("Json);\n");
        if (mysql) {
            source.append(indent).append("    if (response.length() == 0 && DatabaseGraphqlEngine.responseAssemblyExceeded(")
                    .append(objectMembers).append(")) { response = DatabaseGraphqlEngine.resourceLimitErrorJson(\"response exceeds the database engine character budget\"); }\n");
        } else {
            source.append(indent).append("    if (DatabaseGraphqlEngine.responseAssemblyExceeded(")
                    .append(objectMembers).append(")) return DatabaseGraphqlEngine.resourceLimitErrorJson(\"response exceeds the database engine character budget\");\n");
        }
    }

    private static void emitPolicyGuards(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            List<String> names,
            String protectedTarget,
            String position,
            String indent
    ) {
        for (String name : names) {
            TitanGraphqlPolicyDocument policy = policy(document, name);
            if (policy.effect() != TitanGraphqlPolicyDocument.Effect.DENY) {
                throw new IllegalArgumentException("database engine supports reject policies only: " + name);
            }
            source.append(indent).append("if (!DatabaseGraphqlEngine.roleAllowed(actorRole, \"")
                    .append(javaString(policy.expression())).append("\")) return DatabaseGraphqlEngine.authorizationErrorJsonAt(\"")
                    .append(javaString(protectedTarget)).append(" is not authorized\", query, ")
                    .append(position).append(");\n");
        }
    }

    private static void emitMySqlPolicyGuards(
            StringBuilder source,
            TitanGraphqlModelDocument document,
            List<String> names,
            String protectedTarget,
            String position,
            String indent
    ) {
        for (String name : names) {
            TitanGraphqlPolicyDocument policy = policy(document, name);
            if (policy.effect() != TitanGraphqlPolicyDocument.Effect.DENY) {
                throw new IllegalArgumentException("database engine supports reject policies only: " + name);
            }
            source.append(indent).append("if (!DatabaseGraphqlEngine.roleAllowed(actorRole, \"")
                    .append(javaString(policy.expression())).append("\")) {\n")
                    .append(indent).append("    response = DatabaseGraphqlEngine.authorizationErrorJsonAt(\"")
                    .append(javaString(protectedTarget)).append(" is not authorized\", query, ")
                    .append(position).append(");\n")
                    .append(indent).append("}\n");
        }
    }

    private static String pointSql(
            TitanGraphqlTypeDocument type,
            List<TitanGraphqlRootDocument.RootDocumentArgument> arguments
    ) {
        List<String> columns = new ArrayList<>();
        appendProjectedColumns(columns, type);
        appendRelationJoinProjectionColumns(columns, type);
        List<String> predicates = new ArrayList<>();
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : arguments) {
            predicates.add(identifier(argument.column(), "root argument column") + " = "
                    + postgreSqlParameter(argument));
        }
        List<String> absentColumns = new ArrayList<>();
        for (TitanGraphqlFieldDocument field : sortedFields(type)) {
            absentColumns.add("TRUE AS " + identifier(nullableFieldAlias(field), "field null flag"));
            absentColumns.add("NULL AS " + identifier(field.name(), "field name"));
        }
        appendAbsentRelationJoinColumns(absentColumns, type);
        String where = String.join(" AND ", predicates);
        String table = qualifiedTable(type);
        // The fallback row lets the supported JDBC guard/read shape materialize a typed `found`
        // flag even when a point lookup misses. This keeps GraphQL's null result inside the
        // transcompiled routine rather than translating a no-row JDBC condition in the adapter.
        return "SELECT TRUE AS tgql_found, " + String.join(", ", columns) + " FROM " + table
                + " WHERE " + where + " UNION ALL SELECT FALSE AS tgql_found, "
                + String.join(", ", absentColumns) + " WHERE NOT EXISTS (SELECT 1 FROM " + table
                + " WHERE " + where + ")";
    }

    /** MySQL's JDBC single-row lowering accepts a plain one-block projection only. */
    private static String mySqlPointSql(
            TitanGraphqlTypeDocument type,
            List<TitanGraphqlRootDocument.RootDocumentArgument> arguments
    ) {
        List<String> columns = new ArrayList<>();
        appendProjectedColumns(columns, type);
        appendRelationJoinProjectionColumns(columns, type);
        List<String> predicates = new ArrayList<>();
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : arguments) {
            predicates.add(identifier(argument.column(), "root argument column") + " = ?");
        }
        return "SELECT TRUE AS tgql_found, " + String.join(", ", columns) + " FROM "
                + qualifiedTable(type) + " WHERE " + String.join(" AND ", predicates);
    }

    /**
     * Constant, model-specialized forward/backward page read. Optional local equality arguments,
     * reviewed Boolean context predicates, cursor values, and the direction flag remain JDBC
     * parameters; no request value is ever interpolated into generated SQL.
     */
    private static String connectionSql(
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument.Cursor cursor,
            List<TitanGraphqlRootDocument.RootDocumentArgument> arguments,
            List<TitanGraphqlContextFilterDocument> contextFilters,
            List<TitanGraphqlFilterLayout.Binding> filters,
            boolean postgreSql
    ) {
        return connectionSql(type, cursor, arguments, contextFilters, filters, postgreSql, "");
    }

    /** Adds a reviewed parent-key predicate for a relation-bound connection page. */
    private static String connectionSql(
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument.Cursor cursor,
            List<TitanGraphqlRootDocument.RootDocumentArgument> arguments,
            List<TitanGraphqlContextFilterDocument> contextFilters,
            List<TitanGraphqlFilterLayout.Binding> filters,
            boolean postgreSql,
            String requiredTargetColumn
    ) {
        List<String> columns = new ArrayList<>();
        appendProjectedColumns(columns, type);
        appendRelationJoinProjectionColumns(columns, type);
        boolean descending = cursor.direction() == TitanGraphqlRootDocument.RootDocumentSortDirection.DESC;
        String afterOperator = descending ? " < ?" : " > ?";
        String beforeOperator = descending ? " > ?" : " < ?";
        String where = connectionWhere(arguments, contextFilters, filters, postgreSql, requiredTargetColumn);
        String column = identifier(cursor.column(), "connection cursor column");
        String cursorPredicate = "(? = FALSE OR " + column + afterOperator + ") AND (? = FALSE OR "
                + column + beforeOperator + ")";
        // `backward` is bound twice.  In its true branch fetch in reverse source order, then the
        // generated JSON loop prepends each edge to restore the connection's declared order.
        String order = descending
                ? "CASE WHEN ? = FALSE THEN " + column + " END DESC, CASE WHEN ? = TRUE THEN " + column + " END ASC"
                : "CASE WHEN ? = TRUE THEN " + column + " END DESC, CASE WHEN ? = FALSE THEN " + column + " END ASC";
        return "SELECT " + String.join(", ", columns) + " FROM " + qualifiedTable(type)
                + (where.isEmpty() ? " WHERE " + cursorPredicate : where + " AND " + cursorPredicate)
                + " ORDER BY " + order + " LIMIT ?";
    }

    /**
     * One constant relation-connection page statement for up to 64 parent slots. JDBC placeholder
     * order is the two window-direction flags, every active/key slot, shared equality and cursor
     * arguments, then the final per-parent sentinel limit. Window partitioning gives each parent
     * an independent Relay page while duplicate join keys retain distinct parent indexes.
     */
    static String relationConnectionBatchPageSql(
            TitanGraphqlModelDocument document,
            String sourceTypeName,
            String relationName,
            boolean postgreSql
    ) {
        RelationConnectionBatchSqlPlan plan = relationConnectionBatchSqlPlan(
                document, sourceTypeName, relationName);
        TitanGraphqlTypeDocument targetType = plan.targetType();
        TitanGraphqlRelationDocument relation = plan.relation();
        TitanGraphqlFieldDocument keyField = plan.keyField();
        TitanGraphqlRootDocument contract = plan.contract();
        TitanGraphqlRootDocument.Cursor cursor = contract.pagination().cursor();
        List<String> columns = new ArrayList<>();
        columns.add("tgql_batch_keys.tgql_parent_index AS tgql_batch_parent_index");
        boolean descending = cursor.direction() == TitanGraphqlRootDocument.RootDocumentSortDirection.DESC;
        String cursorColumn = identifier(cursor.column(), "relation connection cursor column");
        String order = descending
                ? "CASE WHEN ? = FALSE THEN " + cursorColumn
                        + " END DESC, CASE WHEN ? = TRUE THEN " + cursorColumn + " END ASC"
                : "CASE WHEN ? = TRUE THEN " + cursorColumn
                        + " END DESC, CASE WHEN ? = FALSE THEN " + cursorColumn + " END ASC";
        columns.add("ROW_NUMBER() OVER (PARTITION BY tgql_batch_keys.tgql_parent_index ORDER BY "
                + order + ") AS tgql_batch_row");
        appendProjectedColumns(columns, targetType);
        appendRelationJoinProjectionColumns(columns, targetType);

        String afterOperator = descending ? " < ?" : " > ?";
        String beforeOperator = descending ? " > ?" : " < ?";
        String cursorPredicate = "(? = FALSE OR " + cursorColumn + afterOperator
                + ") AND (? = FALSE OR " + cursorColumn + beforeOperator + ")";
        String where = connectionWhere(contract.arguments(), List.of(), List.of(), postgreSql);
        String predicates = where.isEmpty()
                ? " WHERE " + cursorPredicate
                : where + " AND " + cursorPredicate;
        String targetColumn = identifier(relation.targetColumn(), "relation connection target column");
        String inner = "SELECT " + String.join(", ", columns) + " FROM "
                + qualifiedTable(targetType) + " JOIN " + fixedRelationBatchKeyTable(keyField, postgreSql)
                + " ON tgql_batch_keys.tgql_parent_active = TRUE AND " + targetColumn
                + " = tgql_batch_keys.tgql_parent_key" + predicates;
        return "SELECT * FROM (" + inner + ") tgql_relation_connection_batch"
                + " WHERE tgql_batch_row <= ? ORDER BY tgql_batch_parent_index, tgql_batch_row";
    }

    /** One exact-count row per active parent slot, including parents with zero matching children. */
    static String relationConnectionBatchCountSql(
            TitanGraphqlModelDocument document,
            String sourceTypeName,
            String relationName,
            boolean postgreSql
    ) {
        RelationConnectionBatchSqlPlan plan = relationConnectionBatchSqlPlan(
                document, sourceTypeName, relationName);
        String where = connectionWhere(plan.contract().arguments(), List.of(), List.of(), postgreSql);
        String filtered = "SELECT "
                + identifier(plan.relation().targetColumn(), "relation connection target column")
                + " AS tgql_join_key, "
                + identifier(plan.contract().pagination().cursor().column(), "relation connection cursor column")
                + " AS tgql_count_match FROM " + qualifiedTable(plan.targetType()) + where;
        return "SELECT tgql_batch_keys.tgql_parent_index AS tgql_batch_parent_index, "
                + "COUNT(tgql_count_rows.tgql_count_match) AS tgql_total_count FROM "
                + fixedRelationBatchKeyTable(plan.keyField(), postgreSql)
                + " LEFT JOIN (" + filtered + ") tgql_count_rows ON "
                + "tgql_count_rows.tgql_join_key = tgql_batch_keys.tgql_parent_key"
                + " WHERE tgql_batch_keys.tgql_parent_active = TRUE"
                + " GROUP BY tgql_batch_keys.tgql_parent_index"
                + " ORDER BY tgql_batch_keys.tgql_parent_index";
    }

    /**
     * One opposite-page flag per active parent. Slot parameters precede equality and cursor binds
     * because the filtered target subquery occurs after the fixed slot table in SQL text.
     */
    static String relationConnectionBatchBoundarySql(
            TitanGraphqlModelDocument document,
            String sourceTypeName,
            String relationName,
            boolean postgreSql
    ) {
        RelationConnectionBatchSqlPlan plan = relationConnectionBatchSqlPlan(
                document, sourceTypeName, relationName);
        TitanGraphqlRootDocument.Cursor cursor = plan.contract().pagination().cursor();
        boolean descending = cursor.direction() == TitanGraphqlRootDocument.RootDocumentSortDirection.DESC;
        String cursorColumn = identifier(cursor.column(), "relation connection cursor column");
        String afterOperator = descending ? " >= ?" : " <= ?";
        String beforeOperator = descending ? " <= ?" : " >= ?";
        String boundary = "((? = TRUE AND " + cursorColumn + afterOperator
                + ") OR (? = TRUE AND " + cursorColumn + beforeOperator + "))";
        String where = connectionWhere(plan.contract().arguments(), List.of(), List.of(), postgreSql);
        String predicates = where.isEmpty() ? " WHERE " + boundary : where + " AND " + boundary;
        String filtered = "SELECT "
                + identifier(plan.relation().targetColumn(), "relation connection target column")
                + " AS tgql_join_key, " + cursorColumn + " AS tgql_boundary_match FROM "
                + qualifiedTable(plan.targetType()) + predicates;
        return "SELECT tgql_batch_keys.tgql_parent_index AS tgql_batch_parent_index, "
                + "CASE WHEN COUNT(tgql_boundary_rows.tgql_boundary_match) > 0 THEN TRUE ELSE FALSE END "
                + "AS tgql_has_opposite FROM "
                + fixedRelationBatchKeyTable(plan.keyField(), postgreSql)
                + " LEFT JOIN (" + filtered + ") tgql_boundary_rows ON "
                + "tgql_boundary_rows.tgql_join_key = tgql_batch_keys.tgql_parent_key"
                + " WHERE tgql_batch_keys.tgql_parent_active = TRUE"
                + " GROUP BY tgql_batch_keys.tgql_parent_index"
                + " ORDER BY tgql_batch_keys.tgql_parent_index";
    }

    private static RelationConnectionBatchSqlPlan relationConnectionBatchSqlPlan(
            TitanGraphqlModelDocument document,
            String sourceTypeName,
            String relationName
    ) {
        TitanGraphqlTypeDocument sourceType = type(document, sourceTypeName);
        TitanGraphqlRelationDocument relation = sourceType.relations().stream()
                .filter(candidate -> candidate.name().equals(relationName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown relation '" + sourceTypeName + "." + relationName + "'"));
        if (!isBasicRelayRelationConnection(document, sourceType, relation) || !relation.batchable()) {
            throw new IllegalArgumentException(
                    "relation '" + sourceTypeName + "." + relationName
                            + "' is not a batchable Relay connection");
        }
        return new RelationConnectionBatchSqlPlan(
                sourceType,
                type(document, relation.targetType()),
                relation,
                relationJoinField(document, sourceType, relation),
                relationConnectionContract(relation));
    }

    private record RelationConnectionBatchSqlPlan(
            TitanGraphqlTypeDocument sourceType,
            TitanGraphqlTypeDocument targetType,
            TitanGraphqlRelationDocument relation,
            TitanGraphqlFieldDocument keyField,
            TitanGraphqlRootDocument contract
    ) {
    }

    /**
     * Produces one constant SQL statement for the default integer cursor and every supported
     * generated local scalar ordering. JDBC SQL text must stay compile-time constant in Titan's strict
     * safety mode, so generated booleans select a reviewed predicate/order branch instead of
     * choosing a SQL String at runtime.
     */
    private static String connectionSqlWithCustomOrders(
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument.Cursor cursor,
            List<TitanGraphqlRootDocument.RootDocumentArgument> arguments,
            List<TitanGraphqlContextFilterDocument> contextFilters,
            List<TitanGraphqlFilterLayout.Binding> filters,
            List<TitanGraphqlRootDocument.RootDocumentSortPath> customOrders,
            boolean postgreSql,
            String requiredTargetColumn
    ) {
        String rootAlias = "tgql_root";
        List<String> columns = new ArrayList<>();
        appendProjectedColumns(columns, type, rootAlias);
        appendRelationJoinProjectionColumns(columns, type, rootAlias);
        for (TitanGraphqlRootDocument.RootDocumentSortPath sort : customOrders) {
            columns.add(connectionCustomOrderExpression(document, type, root, sort, rootAlias)
                    + " AS " + identifier(connectionCustomOrderProjectionAlias(sort), "custom-order projection"));
        }
        boolean descending = cursor.direction() == TitanGraphqlRootDocument.RootDocumentSortDirection.DESC;
        String column = qualifiedColumn(rootAlias, cursor.column(), "connection cursor column");
        String from = qualifiedTable(type) + " " + rootAlias + connectionFilterSlotJoins(filters);
        String where = connectionWhere(document, type, root, arguments, contextFilters, filters,
                postgreSql, rootAlias, requiredTargetColumn);
        if (customOrders.isEmpty()) {
            String afterOperator = descending ? " < ?" : " > ?";
            String beforeOperator = descending ? " > ?" : " < ?";
            String cursorPredicate = "(? = FALSE OR " + column + afterOperator + ") AND (? = FALSE OR "
                    + column + beforeOperator + ")";
            String order = descending
                    ? "CASE WHEN ? = FALSE THEN " + column + " END DESC, CASE WHEN ? = TRUE THEN "
                            + column + " END ASC"
                    : "CASE WHEN ? = TRUE THEN " + column + " END DESC, CASE WHEN ? = FALSE THEN "
                            + column + " END ASC";
            return "SELECT " + String.join(", ", columns) + " FROM " + from
                    + (where.isEmpty() ? " WHERE " + cursorPredicate : where + " AND " + cursorPredicate)
                    + " ORDER BY " + order + " LIMIT ?";
        }
        String defaultAfter = "(? = FALSE OR " + column + (descending ? " < ?" : " > ?") + ")";
        String defaultBefore = "(? = FALSE OR " + column + (descending ? " > ?" : " < ?") + ")";
        List<String> branches = new ArrayList<>();
        branches.add("(? = FALSE AND (" + defaultAfter + " AND " + defaultBefore + "))");
        for (TitanGraphqlRootDocument.RootDocumentSortPath sort : customOrders) {
            String value = connectionCustomOrderExpression(document, type, root, sort, rootAlias);
            String tie = qualifiedColumn(rootAlias, connectionCustomOrderTieBreaker(type, sort),
                    "connection custom-order tie-breaker column");
            String valueParameter = connectionCustomOrderValueParameter(
                    connectionCustomOrderField(document, type, root, sort), postgreSql);
            branches.add("(? = TRUE AND ? = TRUE AND "
                    + connectionCustomOrderCursorPredicate(value, tie, valueParameter) + ")");
        }
        String predicate = "(" + String.join(" OR ", branches) + ")";
        return "SELECT " + String.join(", ", columns) + " FROM " + from
                + (where.isEmpty() ? " WHERE " + predicate : where + " AND " + predicate)
                + " ORDER BY " + connectionOrderWithCustomOrders(
                        document, root, column, descending, customOrders, type, rootAlias)
                + " LIMIT ?";
    }

    private static String connectionSqlWithCustomOrders(
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument.Cursor cursor,
            List<TitanGraphqlRootDocument.RootDocumentArgument> arguments,
            List<TitanGraphqlContextFilterDocument> contextFilters,
            List<TitanGraphqlFilterLayout.Binding> filters,
            List<TitanGraphqlRootDocument.RootDocumentSortPath> customOrders,
        boolean postgreSql
    ) {
        return connectionSqlWithCustomOrders(type, cursor, arguments, contextFilters, filters, customOrders,
                postgreSql, "");
    }

    /** Uses the same static relay page shape when a relation supplies a mandatory parent key. */
    private static String connectionSqlWithCustomOrders(
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument.Cursor cursor,
            List<TitanGraphqlRootDocument.RootDocumentArgument> arguments,
            List<TitanGraphqlContextFilterDocument> contextFilters,
            List<TitanGraphqlFilterLayout.Binding> filters,
            List<TitanGraphqlRootDocument.RootDocumentSortPath> customOrders,
            boolean postgreSql,
            String requiredTargetColumn
    ) {
        if (customOrders.isEmpty()) {
            return connectionSql(type, cursor, arguments, contextFilters, filters, postgreSql, requiredTargetColumn);
        }
        List<String> columns = new ArrayList<>();
        appendProjectedColumns(columns, type);
        appendRelationJoinProjectionColumns(columns, type);
        boolean descending = cursor.direction() == TitanGraphqlRootDocument.RootDocumentSortDirection.DESC;
        String column = identifier(cursor.column(), "connection cursor column");
        String defaultAfter = "(? = FALSE OR " + column + (descending ? " < ?" : " > ?") + ")";
        String defaultBefore = "(? = FALSE OR " + column + (descending ? " > ?" : " < ?") + ")";
        List<String> branches = new ArrayList<>();
        branches.add("(? = FALSE AND (" + defaultAfter + " AND " + defaultBefore + "))");
        for (TitanGraphqlRootDocument.RootDocumentSortPath sort : customOrders) {
            String valueColumn = identifier(sort.column(), "connection custom-order column");
            String tieBreakerColumn = identifier(connectionCustomOrderTieBreaker(type, sort),
                    "connection custom-order tie-breaker column");
            String valueParameter = connectionCustomOrderValueParameter(type, sort, postgreSql);
            branches.add("(? = TRUE AND ? = TRUE AND "
                    + connectionCustomOrderCursorPredicate(valueColumn, tieBreakerColumn, valueParameter) + ")");
        }
        String where = connectionWhere(arguments, contextFilters, filters, postgreSql, requiredTargetColumn);
        String predicate = "(" + String.join(" OR ", branches) + ")";
        return "SELECT " + String.join(", ", columns) + " FROM " + qualifiedTable(type)
                + (where.isEmpty() ? " WHERE " + predicate : where + " AND " + predicate)
                + " ORDER BY " + connectionOrderWithCustomOrders(column, descending, customOrders, type)
                + " LIMIT ?";
    }

    /** Binds direction with parameters but keeps all comparison/operator text static. */
    private static String connectionCustomOrderCursorPredicate(
            String valueColumn,
            String tieBreakerColumn,
            String valueParameter
    ) {
        String ascendingAfter = tupleComparison(valueColumn, tieBreakerColumn, valueParameter, ">", ">");
        String descendingAfter = tupleComparison(valueColumn, tieBreakerColumn, valueParameter, "<", "<");
        String ascendingBefore = tupleComparison(valueColumn, tieBreakerColumn, valueParameter, "<", "<");
        String descendingBefore = tupleComparison(valueColumn, tieBreakerColumn, valueParameter, ">", ">");
        return "(? = FALSE OR ((? = FALSE AND " + ascendingAfter + ") OR (? = TRUE AND "
                + descendingAfter + "))) AND (? = FALSE OR ((? = FALSE AND " + ascendingBefore
                + ") OR (? = TRUE AND " + descendingBefore + ")))";
    }

    private static String tupleComparison(String valueColumn, String tieBreakerColumn, String valueParameter,
                                          String valueOperator, String tieBreakerOperator) {
        return "(" + valueColumn + " " + valueOperator + " " + valueParameter + " OR (" + valueColumn + " = "
                + valueParameter + " AND "
                + tieBreakerColumn + " " + tieBreakerOperator + " ?))";
    }

    /** PostgreSQL's dynamic routine binding needs a UUID cast; MySQL stores the proof as CHAR. */
    private static String connectionCustomOrderValueParameter(
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument.RootDocumentSortPath sort,
            boolean postgreSql
    ) {
        TitanGraphqlFieldDocument value = fieldByColumn(type, sort.column());
        return postgreSql && isUuid(value.type()) ? "CAST(? AS UUID)" : "?";
    }

    private static String connectionCustomOrderValueParameter(
            TitanGraphqlFieldDocument value,
            boolean postgreSql
    ) {
        return postgreSql && isUuid(value.type()) ? "CAST(? AS UUID)" : "?";
    }

    private static String connectionCustomOrderProjectionAlias(
            TitanGraphqlRootDocument.RootDocumentSortPath sort
    ) {
        return "tgql_order_" + sort.name();
    }

    private static String connectionCustomOrderExpression(
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            TitanGraphqlRootDocument.RootDocumentSortPath sort,
            String rootAlias
    ) {
        if (sort.hops() == 0) {
            return fieldExpression(type, connectionCustomOrderField(document, type, root, sort), rootAlias);
        }
        ConnectionRelationPath path = connectionRelationPath(
                document, type, root.name(), sort.name(), sort.path(), "sort");
        return connectionRelationExpression(path, rootAlias, "tgql_sort_" + sort.name());
    }

    private static String connectionOrderWithCustomOrders(
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            String defaultColumn,
            boolean defaultDescending,
            List<TitanGraphqlRootDocument.RootDocumentSortPath> customOrders,
            TitanGraphqlTypeDocument type,
            String rootAlias
    ) {
        List<String> cases = new ArrayList<>();
        String defaultForwardDirection = defaultDescending ? "DESC" : "ASC";
        String defaultBackwardDirection = defaultDescending ? "ASC" : "DESC";
        cases.add("CASE WHEN ? = FALSE AND ? = FALSE THEN " + defaultColumn + " END "
                + defaultForwardDirection);
        cases.add("CASE WHEN ? = FALSE AND ? = TRUE THEN " + defaultColumn + " END "
                + defaultBackwardDirection);
        for (TitanGraphqlRootDocument.RootDocumentSortPath sort : customOrders) {
            String value = connectionCustomOrderExpression(document, type, root, sort, rootAlias);
            String tie = qualifiedColumn(rootAlias, connectionCustomOrderTieBreaker(type, sort),
                    "connection custom-order tie-breaker column");
            appendCustomOrderCases(cases, value, tie, false, false, "ASC");
            appendCustomOrderCases(cases, value, tie, true, false, "DESC");
            appendCustomOrderCases(cases, value, tie, false, true, "DESC");
            appendCustomOrderCases(cases, value, tie, true, true, "ASC");
        }
        return String.join(", ", cases);
    }

    private static String connectionOrderWithCustomOrders(
            String defaultColumn,
            boolean defaultDescending,
            List<TitanGraphqlRootDocument.RootDocumentSortPath> customOrders,
            TitanGraphqlTypeDocument type
    ) {
        List<String> cases = new ArrayList<>();
        String defaultForwardDirection = defaultDescending ? "DESC" : "ASC";
        String defaultBackwardDirection = defaultDescending ? "ASC" : "DESC";
        cases.add("CASE WHEN ? = FALSE AND ? = FALSE THEN " + defaultColumn + " END " + defaultForwardDirection);
        cases.add("CASE WHEN ? = FALSE AND ? = TRUE THEN " + defaultColumn + " END " + defaultBackwardDirection);
        for (TitanGraphqlRootDocument.RootDocumentSortPath sort : customOrders) {
            String value = identifier(sort.column(), "connection custom-order column");
            String tie = identifier(connectionCustomOrderTieBreaker(type, sort),
                    "connection custom-order tie-breaker column");
            appendCustomOrderCases(cases, value, tie, false, false, "ASC");
            appendCustomOrderCases(cases, value, tie, true, false, "DESC");
            appendCustomOrderCases(cases, value, tie, false, true, "DESC");
            appendCustomOrderCases(cases, value, tie, true, true, "ASC");
        }
        return String.join(", ", cases);
    }

    /** Each two-column order case repeats its four boolean bind positions for value and tie. */
    private static void appendCustomOrderCases(
            List<String> cases,
            String value,
            String tie,
            boolean descending,
            boolean backward,
            String sqlDirection
    ) {
        String condition = "? = TRUE AND ? = TRUE AND ? = " + (descending ? "TRUE" : "FALSE")
                + " AND ? = " + (backward ? "TRUE" : "FALSE");
        cases.add("CASE WHEN " + condition + " THEN " + value + " END " + sqlDirection);
        cases.add("CASE WHEN " + condition + " THEN " + tie + " END " + sqlDirection);
    }

    private static String connectionCountSql(
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument type,
            List<TitanGraphqlRootDocument.RootDocumentArgument> arguments,
            List<TitanGraphqlContextFilterDocument> contextFilters,
            List<TitanGraphqlFilterLayout.Binding> filters,
            boolean postgreSql,
            String requiredTargetColumn
    ) {
        String rootAlias = "tgql_root";
        return "SELECT COUNT(*) AS tgql_total_count FROM " + qualifiedTable(type) + " " + rootAlias
                + connectionFilterSlotJoins(filters)
                + connectionWhere(document, type, root, arguments, contextFilters, filters,
                        postgreSql, rootAlias, requiredTargetColumn);
    }

    private static String connectionCountSql(
            TitanGraphqlTypeDocument type,
            List<TitanGraphqlRootDocument.RootDocumentArgument> arguments,
            List<TitanGraphqlContextFilterDocument> contextFilters,
            List<TitanGraphqlFilterLayout.Binding> filters,
            boolean postgreSql
    ) {
        return connectionCountSql(type, arguments, contextFilters, filters, postgreSql, "");
    }

    /** Exact counts remain in the same parent-key authorization scope as the page read. */
    private static String connectionCountSql(
            TitanGraphqlTypeDocument type,
            List<TitanGraphqlRootDocument.RootDocumentArgument> arguments,
            List<TitanGraphqlContextFilterDocument> contextFilters,
            List<TitanGraphqlFilterLayout.Binding> filters,
            boolean postgreSql,
            String requiredTargetColumn
    ) {
        return "SELECT COUNT(*) AS tgql_total_count FROM " + qualifiedTable(type)
                + connectionWhere(arguments, contextFilters, filters, postgreSql, requiredTargetColumn);
    }

    /** Computes the forward/backward opposite page flag in the same authorization scope. */
    private static String connectionBoundarySql(
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument.Cursor cursor,
            List<TitanGraphqlRootDocument.RootDocumentArgument> arguments,
            List<TitanGraphqlContextFilterDocument> contextFilters,
            List<TitanGraphqlFilterLayout.Binding> filters,
            boolean postgreSql
    ) {
        return connectionBoundarySql(type, cursor, arguments, contextFilters, filters, postgreSql, "");
    }

    private static String connectionBoundarySql(
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument.Cursor cursor,
            List<TitanGraphqlRootDocument.RootDocumentArgument> arguments,
            List<TitanGraphqlContextFilterDocument> contextFilters,
            List<TitanGraphqlFilterLayout.Binding> filters,
            boolean postgreSql,
            String requiredTargetColumn
    ) {
        String where = connectionWhere(arguments, contextFilters, filters, postgreSql, requiredTargetColumn);
        boolean descending = cursor.direction() == TitanGraphqlRootDocument.RootDocumentSortDirection.DESC;
        String column = identifier(cursor.column(), "connection cursor column");
        String afterOperator = descending ? " >= ?" : " <= ?";
        String beforeOperator = descending ? " <= ?" : " >= ?";
        String predicate = "((? = TRUE AND " + column + afterOperator + ") OR (? = TRUE AND "
                + column + beforeOperator + "))";
        return "SELECT TRUE AS tgql_has_opposite FROM " + qualifiedTable(type)
                + (where.isEmpty() ? " WHERE " + predicate : where + " AND " + predicate)
                + " LIMIT 1";
    }

    /** Constant-SQL companion to {@link #connectionSqlWithCustomOrders}. */
    private static String connectionBoundarySqlWithCustomOrders(
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument.Cursor cursor,
            List<TitanGraphqlRootDocument.RootDocumentArgument> arguments,
            List<TitanGraphqlContextFilterDocument> contextFilters,
            List<TitanGraphqlFilterLayout.Binding> filters,
            List<TitanGraphqlRootDocument.RootDocumentSortPath> customOrders,
            boolean postgreSql,
            String requiredTargetColumn
    ) {
        String rootAlias = "tgql_root";
        String from = qualifiedTable(type) + " " + rootAlias + connectionFilterSlotJoins(filters);
        String where = connectionWhere(document, type, root, arguments, contextFilters, filters,
                postgreSql, rootAlias, requiredTargetColumn);
        boolean descending = cursor.direction() == TitanGraphqlRootDocument.RootDocumentSortDirection.DESC;
        String column = qualifiedColumn(rootAlias, cursor.column(), "connection cursor column");
        if (customOrders.isEmpty()) {
            String afterOperator = descending ? " >= ?" : " <= ?";
            String beforeOperator = descending ? " <= ?" : " >= ?";
            String predicate = "((? = TRUE AND " + column + afterOperator + ") OR (? = TRUE AND "
                    + column + beforeOperator + "))";
            return "SELECT TRUE AS tgql_has_opposite FROM " + from
                    + (where.isEmpty() ? " WHERE " + predicate : where + " AND " + predicate)
                    + " LIMIT 1";
        }
        String defaultAfter = column + (descending ? " >= ?" : " <= ?");
        String defaultBefore = column + (descending ? " <= ?" : " >= ?");
        List<String> branches = new ArrayList<>();
        branches.add("(? = FALSE AND ((? = TRUE AND " + defaultAfter + ") OR (? = TRUE AND "
                + defaultBefore + ")))" );
        for (TitanGraphqlRootDocument.RootDocumentSortPath sort : customOrders) {
            String value = connectionCustomOrderExpression(document, type, root, sort, rootAlias);
            String tie = qualifiedColumn(rootAlias, connectionCustomOrderTieBreaker(type, sort),
                    "connection custom-order tie-breaker column");
            String valueParameter = connectionCustomOrderValueParameter(
                    connectionCustomOrderField(document, type, root, sort), postgreSql);
            branches.add("(? = TRUE AND ? = TRUE AND "
                    + connectionCustomOrderBoundaryPredicate(value, tie, valueParameter) + ")");
        }
        String predicate = "(" + String.join(" OR ", branches) + ")";
        return "SELECT TRUE AS tgql_has_opposite FROM " + from
                + (where.isEmpty() ? " WHERE " + predicate : where + " AND " + predicate)
                + " LIMIT 1";
    }

    private static String connectionBoundarySqlWithCustomOrders(
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument.Cursor cursor,
            List<TitanGraphqlRootDocument.RootDocumentArgument> arguments,
            List<TitanGraphqlContextFilterDocument> contextFilters,
            List<TitanGraphqlFilterLayout.Binding> filters,
            List<TitanGraphqlRootDocument.RootDocumentSortPath> customOrders,
        boolean postgreSql
    ) {
        return connectionBoundarySqlWithCustomOrders(type, cursor, arguments, contextFilters, filters, customOrders,
                postgreSql, "");
    }

    /** Keeps opposite-page detection inside the same static relation predicate as page/count. */
    private static String connectionBoundarySqlWithCustomOrders(
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument.Cursor cursor,
            List<TitanGraphqlRootDocument.RootDocumentArgument> arguments,
            List<TitanGraphqlContextFilterDocument> contextFilters,
            List<TitanGraphqlFilterLayout.Binding> filters,
            List<TitanGraphqlRootDocument.RootDocumentSortPath> customOrders,
            boolean postgreSql,
            String requiredTargetColumn
    ) {
        if (customOrders.isEmpty()) {
            return connectionBoundarySql(type, cursor, arguments, contextFilters, filters, postgreSql,
                    requiredTargetColumn);
        }
        String where = connectionWhere(arguments, contextFilters, filters, postgreSql, requiredTargetColumn);
        boolean descending = cursor.direction() == TitanGraphqlRootDocument.RootDocumentSortDirection.DESC;
        String column = identifier(cursor.column(), "connection cursor column");
        String defaultAfter = column + (descending ? " >= ?" : " <= ?");
        String defaultBefore = column + (descending ? " <= ?" : " >= ?");
        List<String> branches = new ArrayList<>();
        branches.add("(? = FALSE AND ((? = TRUE AND " + defaultAfter + ") OR (? = TRUE AND "
                + defaultBefore + ")))");
        for (TitanGraphqlRootDocument.RootDocumentSortPath sort : customOrders) {
            String value = identifier(sort.column(), "connection custom-order column");
            String tie = identifier(connectionCustomOrderTieBreaker(type, sort),
                    "connection custom-order tie-breaker column");
            String valueParameter = connectionCustomOrderValueParameter(type, sort, postgreSql);
            branches.add("(? = TRUE AND ? = TRUE AND "
                    + connectionCustomOrderBoundaryPredicate(value, tie, valueParameter) + ")");
        }
        String predicate = "(" + String.join(" OR ", branches) + ")";
        return "SELECT TRUE AS tgql_has_opposite FROM " + qualifiedTable(type)
                + (where.isEmpty() ? " WHERE " + predicate : where + " AND " + predicate)
                + " LIMIT 1";
    }

    private static String connectionCustomOrderBoundaryPredicate(String value, String tie, String valueParameter) {
        String ascendingAfter = tupleComparison(value, tie, valueParameter, "<", "<=");
        String descendingAfter = tupleComparison(value, tie, valueParameter, ">", ">=");
        String ascendingBefore = tupleComparison(value, tie, valueParameter, ">", ">=");
        String descendingBefore = tupleComparison(value, tie, valueParameter, "<", "<=");
        String after = "(? = TRUE AND ((? = FALSE AND " + ascendingAfter + ") OR (? = TRUE AND "
                + descendingAfter + ")))";
        String before = "(? = TRUE AND ((? = FALSE AND " + ascendingBefore + ") OR (? = TRUE AND "
                + descendingBefore + ")))";
        return "(" + after + " OR " + before + ")";
    }

    /**
     * A filter predicate is completely generated from reviewed metadata.  The present flag lets
     * one static SQL statement represent an omitted optional predicate without interpolating any
     * request text.  String matching uses POSITION/LEFT/RIGHT rather than LIKE so user text such
     * as '%' and '_' remains literal on both supported databases.
     */
    private static String connectionFilterPredicate(TitanGraphqlFilterLayout.Binding filter, boolean postgreSql) {
        String column = identifier(filter.binding(), "connection filter column");
        String parameter = connectionFilterValueParameter(filter, postgreSql);
        return switch (filter.operator()) {
            case "eq" -> "(? = FALSE OR " + column + " = " + parameter + ")";
            case "neq" -> "(? = FALSE OR " + column + " <> " + parameter + ")";
            case "in" -> connectionStringInFilterPredicate(column, parameter);
            case "lt" -> "(? = FALSE OR " + column + " < " + parameter + ")";
            case "lte" -> "(? = FALSE OR " + column + " <= " + parameter + ")";
            case "gt" -> "(? = FALSE OR " + column + " > " + parameter + ")";
            case "gte" -> "(? = FALSE OR " + column + " >= " + parameter + ")";
            case "isnull" -> "(? = FALSE OR (? = TRUE AND " + column + " IS NULL)"
                    + " OR (? = FALSE AND " + column + " IS NOT NULL))";
            case "contains" -> "(? = FALSE OR POSITION(? IN " + column + ") > 0)";
            case "startswith" -> "(? = FALSE OR LEFT(" + column + ", CHAR_LENGTH(?)) = ?)";
            case "endswith" -> "(? = FALSE OR RIGHT(" + column + ", CHAR_LENGTH(?)) = ?)";
            default -> throw new IllegalArgumentException("unsupported database connection filter operator '"
                    + filter.operator() + "'");
        };
    }

    private static String connectionFilterSlotJoins(List<TitanGraphqlFilterLayout.Binding> filters) {
        if (filters.isEmpty()) return "";
        String joins = "";
        for (int group = 0; group < MAX_FILTER_PLAN_GROUPS; group++) {
            joins = joins + " CROSS JOIN (SELECT ? AS active) tgql_fg" + group;
            for (int term = 0; term < MAX_FILTER_PLAN_TERMS_PER_GROUP; term++) {
                joins = joins + " CROSS JOIN (SELECT ? AS selector, ? AS negated, ? AS null_value, "
                        + "? AS boolean_value, ? AS long_value, ? AS decimal_value, ? AS string_value) "
                        + connectionFilterSlotAlias(group, term);
            }
        }
        return joins;
    }

    private static String connectionFilterSlotAlias(int group, int term) {
        return "tgql_f" + group + term;
    }

    private static String connectionFilterPlanPredicate(
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            List<TitanGraphqlFilterLayout.Binding> filters,
            boolean postgreSql,
            String rootAlias
    ) {
        List<String> groups = new ArrayList<>();
        for (int group = 0; group < MAX_FILTER_PLAN_GROUPS; group++) {
            List<String> terms = new ArrayList<>();
            for (int term = 0; term < MAX_FILTER_PLAN_TERMS_PER_GROUP; term++) {
                String alias = connectionFilterSlotAlias(group, term);
                String selected = connectionFilterSelectorPredicate(
                        document, type, root, filters, postgreSql, rootAlias, alias);
                // Every selector predicate is evaluated once. COALESCE gives nullable database
                // comparisons the GraphQL filter value false; Boolean inequality applies the
                // pushed-down De Morgan marker without duplicating the large static CASE.
                terms.add("(COALESCE((" + selected + "), FALSE) <> " + alias + ".negated)");
            }
            groups.add("(tgql_fg" + group + ".active = TRUE AND "
                    + String.join(" AND ", terms) + ")");
        }
        return "(" + String.join(" OR ", groups) + ")";
    }

    private static String connectionFilterSelectorPredicate(
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            List<TitanGraphqlFilterLayout.Binding> filters,
            boolean postgreSql,
            String rootAlias,
            String slotAlias
    ) {
        String sql = "CASE " + slotAlias + ".selector ";
        for (TitanGraphqlFilterLayout.Binding filter : filters) {
            String expression = connectionFilterExpression(document, type, root, filter, rootAlias);
            String value = connectionFilterSlotValue(document, filter, postgreSql, slotAlias);
            String predicate = switch (filter.operator()) {
                case "eq", "in" -> "CASE WHEN " + slotAlias + ".null_value = TRUE THEN "
                        + expression + " IS NULL ELSE " + expression + " = " + value + " END";
                case "neq" -> "CASE WHEN " + slotAlias + ".null_value = TRUE THEN "
                        + expression + " IS NOT NULL ELSE " + expression + " <> " + value + " END";
                case "isnull" -> "CASE WHEN " + slotAlias + ".boolean_value = TRUE THEN "
                        + expression + " IS NULL ELSE " + expression + " IS NOT NULL END";
                case "lt" -> expression + " < " + value;
                case "lte" -> expression + " <= " + value;
                case "gt" -> expression + " > " + value;
                case "gte" -> expression + " >= " + value;
                case "contains" -> "POSITION(" + value + " IN " + expression + ") > 0";
                case "startswith" -> "LEFT(" + expression + ", CHAR_LENGTH(" + value + ")) = " + value;
                case "endswith" -> "RIGHT(" + expression + ", CHAR_LENGTH(" + value + ")) = " + value;
                default -> throw new IllegalArgumentException("unsupported database connection filter operator '"
                        + filter.operator() + "'");
            };
            sql = sql + "WHEN " + filter.selector() + " THEN " + predicate + " ";
        }
        return sql + "ELSE TRUE END";
    }

    private static String connectionFilterExpression(
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            TitanGraphqlFilterLayout.Binding filter,
            String rootAlias
    ) {
        if (filter.hops() == 0) {
            if (filter.computed()) {
                TitanGraphqlFieldDocument field = type.fields().stream()
                        .filter(candidate -> candidate.name().equals(filter.binding()))
                        .findFirst().orElseThrow();
                return fieldExpression(type, field, rootAlias);
            }
            return qualifiedColumn(rootAlias, filter.binding(), "connection filter column");
        }
        ConnectionRelationPath path = connectionRelationPath(
                document, type, root.name(), filter.fieldName(), filter.path(), "filter");
        return connectionRelationExpression(path, rootAlias, "tgql_filter_" + filter.fieldName());
    }

    private static String connectionFilterSlotValue(
            TitanGraphqlModelDocument document,
            TitanGraphqlFilterLayout.Binding filter,
            boolean postgreSql,
            String slotAlias
    ) {
        // ID is a GraphQL serialization type, not a database storage type.  Keep the SQL
        // carrier aligned with the reviewed physical representation used by the decoder and
        // JDBC binder; otherwise an integral ID produces BIGINT = TEXT in PostgreSQL.
        if (isLong(filter)) return slotAlias + ".long_value";
        String kind = TitanGraphqlFilterLayout.valueKind(document, filter.graphqlType());
        if (kind.equals("boolean")) return slotAlias + ".boolean_value";
        if (kind.equals("float")) return slotAlias + ".decimal_value";
        String value = slotAlias + ".string_value";
        if (!postgreSql) return value;
        String type = inputNamedType(filter.graphqlType());
        return switch (type) {
            case "UUID" -> "CAST(" + value + " AS UUID)";
            case "Date" -> "CAST(" + value + " AS DATE)";
            case "DateTime", "Timestamp" -> "CAST(" + value + " AS TIMESTAMP)";
            default -> value;
        };
    }

    private static String connectionStringInFilterPredicate(String column, String parameter) {
        String predicate = "(? = FALSE OR (";
        for (int index = 0; index < MAX_STATIC_IN_FILTER_VALUES; index++) {
            if (index != 0) {
                predicate = predicate + " OR ";
            }
            predicate = predicate + "(? = TRUE AND " + column + " = " + parameter + ")";
        }
        return predicate + "))";
    }

    private static String connectionWhere(
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument type,
            TitanGraphqlRootDocument root,
            List<TitanGraphqlRootDocument.RootDocumentArgument> arguments,
            List<TitanGraphqlContextFilterDocument> contextFilters,
            List<TitanGraphqlFilterLayout.Binding> filters,
            boolean postgreSql,
            String rootAlias,
            String requiredTargetColumn
    ) {
        List<String> predicates = new ArrayList<>();
        if (requiredTargetColumn != null && !requiredTargetColumn.isBlank()) {
            predicates.add(qualifiedColumn(rootAlias, requiredTargetColumn,
                    "relation connection target column") + " = ?");
        }
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : arguments) {
            predicates.add("(? = FALSE OR " + qualifiedColumn(rootAlias, argument.column(),
                    "connection argument column") + " = ?)");
        }
        for (TitanGraphqlContextFilterDocument filter : contextFilters) {
            String column = qualifiedColumn(rootAlias, filter.column(), "context filter column");
            predicates.add(filter.failClosed()
                    ? "(? = FALSE OR (? = TRUE AND " + column + " = ?))"
                    : "(? = FALSE OR ? = FALSE OR " + column + " = ?)");
        }
        if (!filters.isEmpty()) {
            predicates.add(connectionFilterPlanPredicate(
                    document, type, root, filters, postgreSql, rootAlias));
        }
        return predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
    }

    private static String connectionWhere(
            List<TitanGraphqlRootDocument.RootDocumentArgument> arguments,
            List<TitanGraphqlContextFilterDocument> contextFilters,
            List<TitanGraphqlFilterLayout.Binding> filters,
            boolean postgreSql
    ) {
        return connectionWhere(arguments, contextFilters, filters, postgreSql, "");
    }

    /**
     * The optional first predicate is only metadata-derived relation SQL.  Its value is bound by
     * the caller before every optional GraphQL argument, so request text never chooses SQL.
     */
    private static String connectionWhere(
            List<TitanGraphqlRootDocument.RootDocumentArgument> arguments,
            List<TitanGraphqlContextFilterDocument> contextFilters,
            List<TitanGraphqlFilterLayout.Binding> filters,
            boolean postgreSql,
            String requiredTargetColumn
    ) {
        List<String> predicates = new ArrayList<>();
        if (requiredTargetColumn != null && !requiredTargetColumn.isBlank()) {
            predicates.add(identifier(requiredTargetColumn, "relation connection target column") + " = ?");
        }
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : arguments) {
            predicates.add("(? = FALSE OR " + identifier(argument.column(), "connection argument column") + " = ?)");
        }
        for (TitanGraphqlContextFilterDocument filter : contextFilters) {
            String column = identifier(filter.column(), "context filter column");
            predicates.add(filter.failClosed()
                    ? "(? = FALSE OR (? = TRUE AND " + column + " = ?))"
                    : "(? = FALSE OR ? = FALSE OR " + column + " = ?)");
        }
        for (TitanGraphqlFilterLayout.Binding filter : filters) {
            predicates.add(connectionFilterPredicate(filter, postgreSql));
        }
        return predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
    }

    /** PostgreSQL cannot infer UUID equality from a JDBC text carrier; MySQL's reviewed CHAR form can. */
    private static String connectionFilterValueParameter(
            TitanGraphqlFilterLayout.Binding filter,
            boolean postgreSql
    ) {
        return postgreSql && isUuid(filter.graphqlType()) ? "CAST(? AS UUID)" : "?";
    }

    private static String relationSql(
            TitanGraphqlTypeDocument targetType,
            TitanGraphqlRelationDocument relation
    ) {
        List<String> columns = new ArrayList<>();
        appendProjectedColumns(columns, targetType);
        appendRelationJoinProjectionColumns(columns, targetType);
        String order = targetType.primaryKey().isBlank()
                ? ""
                : " ORDER BY " + identifier(targetType.primaryKey(), "target primary key");
        String limit = relation.cardinality() == TitanGraphqlRelationDocument.RelationDocumentCardinality.MANY
                ? " LIMIT " + (MAX_UNPAGINATED_RELATION_ROWS + 1)
                : "";
        return "SELECT " + String.join(", ", columns) + " FROM " + qualifiedTable(targetType)
                + " WHERE " + identifier(relation.targetColumn(), "relation target column") + " = ?" + order + limit;
    }

    /** One constant 64-slot batch with a per-parent sentinel row on both supported dialects. */
    private static String unpaginatedRelationBatchSql(
            TitanGraphqlTypeDocument targetType,
            TitanGraphqlRelationDocument relation,
            TitanGraphqlFieldDocument keyField,
            boolean postgreSql
    ) {
        List<String> columns = new ArrayList<>();
        String targetColumn = identifier(relation.targetColumn(), "relation target column");
        // Titan lowers ResultSet reads to positional FETCH targets in Java read order. The batch
        // parent slot and ordinal are read before the ordinary relation object, so they must lead
        // the projection even though generated Java addresses them by alias. Joining a fixed
        // slot table (rather than OR-ing keys) preserves duplicate local keys for to-one batches.
        columns.add("tgql_batch_keys.tgql_parent_index AS tgql_batch_parent_index");
        String orderColumn = targetType.primaryKey().isBlank()
                ? targetColumn : identifier(targetType.primaryKey(), "target primary key");
        columns.add("ROW_NUMBER() OVER (PARTITION BY tgql_batch_keys.tgql_parent_index ORDER BY "
                + orderColumn + ") AS tgql_batch_row");
        appendProjectedColumns(columns, targetType);
        appendRelationJoinProjectionColumns(columns, targetType);
        int maximumRows = relation.cardinality()
                == TitanGraphqlRelationDocument.RelationDocumentCardinality.ONE
                ? 1 : MAX_UNPAGINATED_RELATION_ROWS;
        return "SELECT * FROM (SELECT " + String.join(", ", columns) + " FROM "
                + qualifiedTable(targetType) + " JOIN " + fixedRelationBatchKeyTable(keyField, postgreSql)
                + " ON tgql_batch_keys.tgql_parent_active = TRUE AND " + targetColumn
                + " = tgql_batch_keys.tgql_parent_key) tgql_relation_batch WHERE tgql_batch_row <= "
                + (maximumRows + 1)
                + " ORDER BY tgql_batch_parent_index, tgql_batch_row";
    }

    /** Static parent-slot table shared by unpaginated and Relay relation batches. */
    private static String fixedRelationBatchKeyTable(
            TitanGraphqlFieldDocument keyField,
            boolean postgreSql
    ) {
        List<String> slots = new ArrayList<>();
        String valueParameter = postgreSql && isUuid(keyField.type()) ? "CAST(? AS UUID)" : "?";
        for (int slot = 0; slot < MAX_STATIC_RELATION_BATCH_KEYS; slot++) {
            if (postgreSql) {
                slots.add("(" + slot + ", ?, " + valueParameter + ")");
            } else if (slot == 0) {
                slots.add("SELECT 0 AS tgql_parent_index, ? AS tgql_parent_active, " + valueParameter
                        + " AS tgql_parent_key");
            } else {
                slots.add("SELECT " + slot + ", ?, " + valueParameter);
            }
        }
        return postgreSql
                ? "(VALUES " + String.join(", ", slots)
                        + ") AS tgql_batch_keys(tgql_parent_index, tgql_parent_active, tgql_parent_key)"
                : "(" + String.join(" UNION ALL ", slots) + ") tgql_batch_keys";
    }

    /** Emits the active/key pair for every fixed slot and returns the next JDBC parameter index. */
    private static int emitFixedRelationBatchKeyParameters(
            StringBuilder source,
            String indent,
            String statement,
            String offset,
            String keyCount,
            String activity,
            String keys,
            TitanGraphqlFieldDocument keyField,
            int firstParameter
    ) {
        int parameter = firstParameter;
        for (int slot = 0; slot < MAX_STATIC_RELATION_BATCH_KEYS; slot++) {
            String slotIndex = offset + " + " + slot;
            String slotActive = slotIndex + " < " + keyCount
                    + " && DatabaseGraphqlEngine.batchParentActive(" + activity + ", " + slotIndex + ")";
            String keyExpression = "(" + slotActive + " ? DatabaseGraphqlEngine.batchParentKey(" + keys
                    + ", " + slotIndex + ") : \"" + javaString(inactiveBatchKey(keyField)) + "\")";
            source.append(indent).append(statement).append(".setBoolean(")
                    .append(parameter++).append(", ").append(slotActive).append(");\n")
                    .append(indent).append(statement).append('.').append(jdbcSetter(keyField))
                    .append('(').append(parameter++).append(", ")
                    .append(batchKeyBindingValue(keyField, keyExpression)).append(");\n");
        }
        return parameter;
    }

    private static String updateSql(
            TitanGraphqlTypeDocument type,
            TitanGraphqlMutationDocument mutation,
            boolean postgreSql
    ) {
        List<String> assignments = new ArrayList<>();
        List<String> predicates = new ArrayList<>();
        for (MutationBinding argument : sortedMutationArguments(mutation)) {
            String parameter = postgreSql ? postgreSqlParameter(argument) : "?";
            if (argument.key()) {
                predicates.add(identifier(argument.column(), "mutation key column") + " = " + parameter);
            } else {
                assignments.add(identifier(argument.column(), "mutation assignment column") + " = " + parameter);
            }
        }
        if (assignments.isEmpty() || predicates.isEmpty()) {
            throw new IllegalArgumentException("mutation '" + mutation.name()
                    + "' needs at least one key and one assignment binding");
        }
        return "UPDATE " + qualifiedTable(type) + " SET " + String.join(", ", assignments)
                + " WHERE " + String.join(" AND ", predicates);
    }

    /** Produces the always-one-row absence check after {@link #mutationTargetLockSql}. */
    private static String mutationTargetSql(
            TitanGraphqlTypeDocument type,
            TitanGraphqlMutationDocument mutation,
            boolean postgreSql
    ) {
        List<String> predicates = new ArrayList<>();
        for (MutationBinding argument : sortedMutationArguments(mutation)) {
            if (argument.key()) {
                predicates.add(identifier(argument.column(), "mutation key column") + " = "
                        + (postgreSql ? postgreSqlParameter(argument) : "?"));
            }
        }
        if (predicates.isEmpty()) {
            throw new IllegalArgumentException("mutation '" + mutation.name() + "' needs at least one key binding");
        }
        return "SELECT COUNT(*) AS tgql_found FROM " + qualifiedTable(type) + " WHERE "
                + String.join(" AND ", predicates);
    }

    /**
     * Locks a modeled mutation target without making a NULL-on-no-row value the existence
     * authority.
     *
     * <p>The following {@link #mutationTargetSql count query} has a stable zero when this select
     * finds nothing. When it finds a row, this {@code FOR UPDATE} lock persists through the
     * generated update, preventing a concurrent delete from turning an otherwise valid mutation
     * into a silent no-op.</p>
     */
    private static String mutationTargetLockSql(
            TitanGraphqlTypeDocument type,
            TitanGraphqlMutationDocument mutation,
            boolean postgreSql
    ) {
        List<String> predicates = new ArrayList<>();
        for (MutationBinding argument : sortedMutationArguments(mutation)) {
            if (argument.key()) {
                predicates.add(identifier(argument.column(), "mutation key column") + " = "
                        + (postgreSql ? postgreSqlParameter(argument) : "?"));
            }
        }
        if (predicates.isEmpty()) {
            throw new IllegalArgumentException("mutation '" + mutation.name() + "' needs at least one key binding");
        }
        return "SELECT 1 AS tgql_lock FROM " + qualifiedTable(type) + " WHERE "
                + String.join(" AND ", predicates) + " FOR UPDATE";
    }

    private static TitanGraphqlFieldDocument fieldByColumn(TitanGraphqlTypeDocument type, String column) {
        return type.fields().stream()
                .filter(field -> field.column().equals(column))
                .findFirst()
                .orElse(null);
    }

    /**
     * A relation's local column has the same reviewed scalar representation as the referenced
     * target column. This is the type authority for a private local join carrier when that column
     * is intentionally absent from the GraphQL object fields.
     */
    private static TitanGraphqlFieldDocument relationJoinField(
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument sourceType,
            TitanGraphqlRelationDocument relation
    ) {
        if (fieldByColumn(sourceType, relation.localColumn()) != null) {
            return fieldByColumn(sourceType, relation.localColumn());
        }
        TitanGraphqlTypeDocument targetType = type(document, relation.targetType());
        return fieldByColumn(targetType, relation.targetColumn());
    }

    private static String relationJoinAlias(TitanGraphqlRelationDocument relation) {
        return "tgql_relation_" + relation.name() + "_join";
    }

    private static String relationJoinNullAlias(TitanGraphqlRelationDocument relation) {
        return relationJoinAlias(relation) + "_is_null";
    }

    /**
     * PostgreSQL's dynamic EXECUTE binds Java strings as TEXT. Schema metadata therefore owns
     * the cast for database-native scalar keys, rather than asking the thin caller to know a
     * physical column type. MySQL's CHAR-backed UUID proof accepts its string binding directly.
     */
    private static String postgreSqlParameter(TitanGraphqlRootDocument.RootDocumentArgument argument) {
        if ("UUID".equals(argument.type())) {
            return "CAST(? AS UUID)";
        }
        return "?";
    }

    private static String postgreSqlParameter(MutationBinding argument) {
        if ("UUID".equals(argument.type())) {
            return "CAST(? AS UUID)";
        }
        return "?";
    }

    private static String fieldExpression(TitanGraphqlTypeDocument type, TitanGraphqlFieldDocument field) {
        return fieldExpression(type, field, "");
    }

    private static String fieldExpression(
            TitanGraphqlTypeDocument type,
            TitanGraphqlFieldDocument field,
            String qualifier
    ) {
        if (!field.column().isBlank()) {
            return qualifiedColumn(qualifier, field.column(), "field column");
        }
        if (field.computed() == null || field.computed().sqlTemplate().isBlank()) {
            throw new IllegalArgumentException("field '" + type.name() + "." + field.name()
                    + "' has no database expression");
        }
        String expression = field.computed().sqlTemplate();
        for (TitanGraphqlFieldDocument required : type.fields()) {
            if (!required.column().isBlank()) {
                expression = expression.replace("{" + required.name() + "}",
                        qualifiedColumn(qualifier, required.column(), "computed field column"));
            }
        }
        if (expression.contains("{") || !expression.matches("[A-Za-z0-9_().,+*/% -]+")) {
            throw new IllegalArgumentException("computed field '" + type.name() + "." + field.name()
                    + "' has an unsafe SQL expression");
        }
        return expression;
    }

    private static String qualifiedColumn(String qualifier, String column, String label) {
        String value = identifier(column, label);
        return qualifier == null || qualifier.isBlank() ? value : qualifier + "." + value;
    }

    private static ConnectionRelationPath connectionRelationPath(
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument source,
            String rootName,
            String publicName,
            String path,
            String purpose
    ) {
        String[] segments = path.split("\\.", -1);
        if (segments.length != 2 || segments[0].isBlank() || segments[1].isBlank()) {
            throw new IllegalArgumentException("one-hop root " + purpose + " path '" + rootName + "."
                    + publicName + "' must use relation.field syntax");
        }
        TitanGraphqlRelationDocument relation = source.relations().stream()
                .filter(candidate -> candidate.name().equals(segments[0]))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("root " + purpose + " path '"
                        + rootName + "." + publicName + "' references unknown relation '" + segments[0] + "'"));
        if (relation.cardinality() != TitanGraphqlRelationDocument.RelationDocumentCardinality.ONE) {
            throw new IllegalArgumentException("root " + purpose + " path '" + rootName + "."
                    + publicName + "' requires a to-one relation");
        }
        TitanGraphqlTypeDocument target = type(document, relation.targetType());
        TitanGraphqlFieldDocument field = target.fields().stream()
                .filter(candidate -> candidate.name().equals(segments[1]))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("root " + purpose + " path '"
                        + rootName + "." + publicName + "' references unknown field '" + path + "'"));
        return new ConnectionRelationPath(relation, target, field);
    }

    private static String connectionRelationExpression(
            ConnectionRelationPath path,
            String rootAlias,
            String alias
    ) {
        return "(SELECT " + fieldExpression(path.target(), path.field(), alias) + " FROM "
                + qualifiedTable(path.target()) + " " + alias + " WHERE "
                + qualifiedColumn(alias, path.relation().targetColumn(), "relation target column") + " = "
                + qualifiedColumn(rootAlias, path.relation().localColumn(), "relation local column") + ")";
    }

    private record ConnectionRelationPath(
            TitanGraphqlRelationDocument relation,
            TitanGraphqlTypeDocument target,
            TitanGraphqlFieldDocument field
    ) {
    }

    /**
     * Project every model field with an explicit SQL null marker. JDBC primitive getters otherwise
     * erase a database-null distinction (zero or false), including when corrupt or migrated data
     * violates a reviewed GraphQL non-null contract. The generated routine carries the marker into
     * JSON completion and non-null propagation without involving the HTTP adapter.
     */
    private static void appendProjectedColumns(List<String> columns, TitanGraphqlTypeDocument type) {
        appendProjectedColumns(columns, type, "");
    }

    private static void appendProjectedColumns(
            List<String> columns,
            TitanGraphqlTypeDocument type,
            String qualifier
    ) {
        for (TitanGraphqlFieldDocument field : sortedFields(type)) {
            String expression = fieldExpression(type, field, qualifier);
            columns.add("(" + expression + ") IS NULL AS "
                    + identifier(nullableFieldAlias(field), "field null flag"));
            columns.add(expression + " AS " + identifier(field.name(), "field name"));
        }
    }

    /**
     * A relation can use a physical local key which is deliberately absent from the public
     * GraphQL type. Point and relation reads need a typed private carrier for that key before
     * they can bind the next static relation query. Connection reads include the same carrier
     * and decode it in the matching projection order, so a connection node can use the generic
     * relation executor without a JVM completion fallback.
     */
    private static void appendRelationJoinProjectionColumns(
            List<String> columns,
            TitanGraphqlTypeDocument type
    ) {
        appendRelationJoinProjectionColumns(columns, type, "");
    }

    private static void appendRelationJoinProjectionColumns(
            List<String> columns,
            TitanGraphqlTypeDocument type,
            String qualifier
    ) {
        for (TitanGraphqlRelationDocument relation : selectableRelations(type)) {
            if (fieldByColumn(type, relation.localColumn()) == null) {
                String expression = qualifiedColumn(qualifier, relation.localColumn(), "relation local join column");
                columns.add("(" + expression + ") IS NULL AS "
                        + identifier(relationJoinNullAlias(relation), "relation local join null flag"));
                columns.add(expression + " AS " + identifier(relationJoinAlias(relation), "relation local join value"));
            }
        }
    }

    private static void appendAbsentRelationJoinColumns(
            List<String> columns,
            TitanGraphqlTypeDocument type
    ) {
        for (TitanGraphqlRelationDocument relation : selectableRelations(type)) {
            if (fieldByColumn(type, relation.localColumn()) == null) {
                columns.add("TRUE AS " + identifier(relationJoinNullAlias(relation), "relation local join null flag"));
                columns.add("NULL AS " + identifier(relationJoinAlias(relation), "relation local join value"));
            }
        }
    }

    private static String nullableFieldAlias(TitanGraphqlFieldDocument field) {
        return "tgql_" + field.name() + "_is_null";
    }

    private static String qualifiedTable(TitanGraphqlTypeDocument type) {
        String schema = identifier(type.schema(), "type schema");
        String table = type.physicalTable().isBlank() ? type.table() : type.physicalTable();
        return schema + "." + identifier(table, "type table");
    }

    private static List<TitanGraphqlRootDocument.RootDocumentArgument> pointArguments(TitanGraphqlRootDocument root) {
        if (root.argument() != null) {
            return List.of(root.argument());
        }
        return root.arguments().stream()
                .filter(argument -> argument.kind() == TitanGraphqlRootDocument.RootDocumentArgumentKind.EQUALS)
                .toList();
    }

    /**
     * Emits the generated input-schema descriptor consumed by the database language core.
     *
     * <p>Unlike the former comma-list, this records scalar kinds, enum values, input-object
     * fields, nested scalar-filter fields, and list wrappers. It remains a deterministic string
     * literal in generated source: no runtime model parsing, reflection, or object graph reaches
     * the transpiled serving path.</p>
     */
    private static String inputTypeDescriptor(TitanGraphqlModelDocument document) {
        Map<String, Character> scalarTypes = new TreeMap<>();
        Map<String, String> enumTypes = new TreeMap<>();
        Map<String, Map<String, String>> objects = new TreeMap<>();
        addInputScalar(scalarTypes, "Boolean");
        addInputScalar(scalarTypes, "Float");
        addInputScalar(scalarTypes, "ID");
        addInputScalar(scalarTypes, "Int");
        addInputScalar(scalarTypes, "String");
        for (TitanGraphqlEnumDocument enumType : sortedEnums(document)) {
            putInputEnum(enumTypes, enumType.name(), String.join("|", sortedEnumValues(enumType)));
        }
        boolean hasSortDirection = false;

        for (TitanGraphqlInputObjectDocument inputObject : sortedInputObjects(document)) {
            Map<String, String> fields = inputObject(objects, inputObject.name());
            for (TitanGraphqlInputObjectDocument.InputField field : inputObject.fields()) {
                putInputField(fields, field.name(), field.type());
                String namedType = inputNamedType(field.type());
                if (enumType(document, namedType) == null && inputObjectType(document, namedType) == null) {
                    addInputScalar(scalarTypes, namedType);
                }
            }
        }

        for (TitanGraphqlRootDocument root : sortedRoots(document)) {
            if (root.argument() != null) {
                if (enumType(document, inputNamedType(root.argument().type())) == null) {
                    addInputScalar(scalarTypes, root.argument().type());
                }
            }
            for (TitanGraphqlRootDocument.RootDocumentArgument argument : root.arguments()) {
                if (enumType(document, inputNamedType(argument.type())) == null) {
                    addInputScalar(scalarTypes, argument.type());
                }
            }
            TitanGraphqlTypeDocument type = type(document, root.type());
            List<TitanGraphqlFilterLayout.Binding> filters = supportedConnectionFilters(document, type, root);
            if (!filters.isEmpty()) {
                Map<String, String> rootFilter = inputObject(objects, root.type() + "Filter");
                putInputField(rootFilter, "and", "[" + root.type() + "Filter!]");
                putInputField(rootFilter, "not", root.type() + "Filter");
                putInputField(rootFilter, "or", "[" + root.type() + "Filter!]");
                for (TitanGraphqlFilterLayout.Binding filter : filters) {
                    String scalarType = inputNamedType(filter.graphqlType());
                    if (enumType(document, scalarType) == null) {
                        addInputScalar(scalarTypes, scalarType);
                    }
                    String scalarFilter = scalarType + "Filter";
                    putInputField(rootFilter, filter.fieldName(), scalarFilter);
                    Map<String, String> scalarFilterFields = inputObject(objects, scalarFilter);
                    String operator = graphqlFilterOperatorName(filter.operator());
                    String valueType = filter.operator().equals("isnull") ? "Boolean"
                            : filter.operator().equals("in") ? "[" + scalarType + "!]" : scalarType;
                    putInputField(scalarFilterFields, operator, valueType);
                }
            }
            if (!root.sortPaths().isEmpty()) {
                hasSortDirection = true;
                Map<String, String> orderBy = inputObject(objects, root.type() + "OrderBy");
                for (TitanGraphqlRootDocument.RootDocumentSortPath sort : root.sortPaths()) {
                    putInputField(orderBy, sort.name(), "SortDirection");
                }
            }
        }
        for (TitanGraphqlTypeDocument type : sortedTypes(document)) {
            for (TitanGraphqlRelationDocument relation : selectableRelations(type)) {
                for (TitanGraphqlRelationDocument.RelationDocumentArgument argument : relation.arguments()) {
                    if (!argument.type().isBlank()
                            && enumType(document, inputNamedType(argument.type())) == null) {
                        addInputScalar(scalarTypes, argument.type());
                    }
                }
            }
        }
        for (TitanGraphqlMutationDocument mutation : sortedMutations(document)) {
            for (MutationBinding argument
                    : sortedMutationArguments(mutation)) {
                if (enumType(document, inputNamedType(argument.type())) == null) {
                    addInputScalar(scalarTypes, argument.type());
                }
            }
        }

        String descriptor = "id1;";
        for (Map.Entry<String, Character> scalar : scalarTypes.entrySet()) {
            descriptor = descriptor + scalar.getValue() + ":" + scalar.getKey() + ";";
        }
        if (hasSortDirection) {
            putInputEnum(enumTypes, "SortDirection", "ASC|DESC");
        }
        for (Map.Entry<String, String> enumType : enumTypes.entrySet()) {
            descriptor = descriptor + "E:" + enumType.getKey() + ":" + enumType.getValue() + ";";
        }
        for (Map.Entry<String, Map<String, String>> object : objects.entrySet()) {
            if (object.getValue().isEmpty()) {
                throw new IllegalArgumentException("generated input object '" + object.getKey()
                        + "' has no fields");
            }
            String fields = "";
            for (Map.Entry<String, String> field : object.getValue().entrySet()) {
                fields = fields + (fields.isEmpty() ? "" : ",") + field.getKey() + "=" + field.getValue();
            }
            descriptor = descriptor + "O:" + object.getKey() + ":" + fields + ";";
        }
        for (TitanGraphqlInputObjectDocument inputObject : sortedInputObjects(document)) {
            for (TitanGraphqlInputObjectDocument.InputField field : inputObject.fields()) {
                if (!field.defaultValue().isEmpty()) {
                    String key = inputObject.name() + "." + field.name();
                    descriptor = descriptor + "D:" + key + ":" + field.defaultValue().length()
                            + ":" + field.defaultValue() + ";";
                }
            }
        }
        for (TitanGraphqlRootDocument root : sortedRoots(document)) {
            if (root.operation() == TitanGraphqlRootDocument.RootDocumentOperation.POINT) {
                for (TitanGraphqlRootDocument.RootDocumentArgument argument : pointArguments(root)) {
                    descriptor = appendArgumentDefault(descriptor, "Query", root.name(),
                            argument.name(), argument.defaultValue());
                }
            } else {
                for (TitanGraphqlRootDocument.RootDocumentArgument argument : root.arguments()) {
                    descriptor = appendArgumentDefault(descriptor, "Query", root.name(),
                            argument.name(), argument.defaultValue());
                }
            }
        }
        for (TitanGraphqlTypeDocument type : sortedTypes(document)) {
            for (TitanGraphqlRelationDocument relation : selectableRelations(type)) {
                for (TitanGraphqlRelationDocument.RelationDocumentArgument argument : relation.arguments()) {
                    descriptor = appendArgumentDefault(descriptor, type.name(), relation.name(),
                            argument.name(), argument.defaultValue());
                }
            }
        }
        for (TitanGraphqlMutationDocument mutation : sortedMutations(document)) {
            if (mutation.input() != null) {
                descriptor = appendArgumentDefault(descriptor, "Mutation", mutation.name(),
                        mutation.input().name(), mutation.input().defaultValue());
            } else {
                for (TitanGraphqlMutationDocument.MutationDocumentArgument argument : mutation.arguments()) {
                    descriptor = appendArgumentDefault(descriptor, "Mutation", mutation.name(),
                            argument.name(), argument.defaultValue());
                }
            }
        }
        return descriptor;
    }

    private static String appendArgumentDefault(
            String descriptor,
            String parentType,
            String fieldName,
            String argumentName,
            String defaultValue
    ) {
        if (defaultValue == null || defaultValue.isEmpty()) return descriptor;
        String key = parentType + "." + fieldName + "." + argumentName;
        return descriptor + "D:" + key + ":" + defaultValue.length() + ":" + defaultValue + ";";
    }

    /**
     * Builds the compact object-field schema consumed by database-resident structural validation.
     * The field payload is the same executable/introspection descriptor used elsewhere by this
     * generator; length-prefixed type records make the outer carrier delimiter-safe.
     */
    private static String schemaValidationDescriptor(TitanGraphqlModelDocument document) {
        Map<String, String> types = new TreeMap<>();
        putSchemaValidationType(types, "Query",
                introspectionQueryFieldDescriptor(document)
                        + "__schema:__Schema!:0:O;"
                        + "__type:__Type:1:O|name=String!=S;");
        if (!document.mutations().isEmpty()) {
            putSchemaValidationType(types, "Mutation", introspectionMutationFieldDescriptor(document));
        }
        for (TitanGraphqlTypeDocument type : sortedTypes(document)) {
            putSchemaValidationType(types, type.name(), introspectionScalarFieldDescriptor(document, type));
        }
        for (TitanGraphqlInterfaceDocument interfaceType : sortedInterfaces(document)) {
            putSchemaValidationType(types, interfaceType.name(),
                    introspectionInterfaceFieldDescriptor(document, interfaceType));
        }
        for (TitanGraphqlUnionDocument union : sortedUnions(document)) {
            // __typename is intrinsic, but a non-empty descriptor keeps union selections in the
            // generic schema-validation work queue until abstract-scope expansion is applied.
            putSchemaValidationType(types, union.name(), "__typename:String!:0:S;");
        }
        for (String type : introspectionDerivedObjectTypes(document)) {
            putSchemaValidationType(types, type, introspectionDerivedObjectFieldDescriptor(document, type));
        }
        for (String type : introspectionMetaObjectTypes()) {
            putSchemaValidationType(types, type, introspectionMetaObjectFieldDescriptor(type));
        }
        for (TitanGraphqlTypeDocument type : sortedTypes(document)) {
            putSchemaValidationType(types, "#" + type.name(), runtimeTypeConditions(document, type));
        }
        for (TitanGraphqlInterfaceDocument interfaceType : sortedInterfaces(document)) {
            putSchemaValidationType(types, "#" + interfaceType.name(), "|" + interfaceType.name() + "|");
            String possibleTypes = abstractPossibleTypeNames(document, interfaceType, null);
            putSchemaValidationType(types, "@" + interfaceType.name(),
                    possibleTypes.isEmpty() ? "-" : possibleTypes);
        }
        for (TitanGraphqlUnionDocument union : sortedUnions(document)) {
            putSchemaValidationType(types, "#" + union.name(), "|" + union.name() + "|");
            putSchemaValidationType(types, "@" + union.name(),
                    abstractPossibleTypeNames(document, null, union));
        }

        String descriptor = "sd1;";
        for (Map.Entry<String, String> type : types.entrySet()) {
            descriptor = descriptor + type.getKey().length() + ":" + type.getKey()
                    + type.getValue().length() + ":" + type.getValue();
        }
        return descriptor;
    }

    private static void putSchemaValidationType(Map<String, String> types, String name, String fields) {
        if (name == null || name.isBlank() || fields == null || fields.isBlank()) {
            throw new IllegalArgumentException("generated schema-validation object descriptor is empty");
        }
        String existing = types.putIfAbsent(name, fields);
        if (existing != null && !existing.equals(fields)) {
            throw new IllegalArgumentException("generated schema-validation type '" + name
                    + "' has incompatible field descriptors");
        }
    }

    private static Map<String, String> inputObject(Map<String, Map<String, String>> objects, String type) {
        Map<String, String> fields = objects.get(type);
        if (fields == null) {
            fields = new TreeMap<>();
            objects.put(type, fields);
        }
        return fields;
    }

    private static void putInputField(Map<String, String> fields, String name, String type) {
        String existing = fields.putIfAbsent(name, type);
        if (existing != null && !existing.equals(type)) {
            throw new IllegalArgumentException("generated input field '" + name
                    + "' has incompatible types '" + existing + "' and '" + type + "'");
        }
    }

    private static void putInputEnum(Map<String, String> enums, String name, String values) {
        String existing = enums.putIfAbsent(name, values);
        if (existing != null && !existing.equals(values)) {
            throw new IllegalArgumentException("generated input enum '" + name
                    + "' has incompatible value sets '" + existing + "' and '" + values + "'");
        }
    }

    private static void addInputScalar(Map<String, Character> scalarTypes, String typeReference) {
        String type = inputNamedType(typeReference);
        Character kind = inputScalarKind(type);
        Character existing = scalarTypes.putIfAbsent(type, kind);
        if (existing != null && existing.charValue() != kind.charValue()) {
            throw new IllegalArgumentException("generated input scalar '" + type + "' has conflicting kinds");
        }
    }

    private static String inputNamedType(String typeReference) {
        String type = typeReference == null ? "" : typeReference.trim();
        int position = 0;
        while (position < type.length() && (type.charAt(position) == '[' || type.charAt(position) == ' ')) {
            position++;
        }
        int start = position;
        while (position < type.length() && ((type.charAt(position) >= 'A' && type.charAt(position) <= 'Z')
                || (type.charAt(position) >= 'a' && type.charAt(position) <= 'z')
                || (type.charAt(position) >= '0' && type.charAt(position) <= '9')
                || type.charAt(position) == '_')) {
            position++;
        }
        if (start == position || position < type.length() && type.charAt(position) != '!' && type.charAt(position) != ']'
                && type.charAt(position) != ' ') {
            throw new IllegalArgumentException("generated input type is malformed: '" + typeReference + "'");
        }
        return type.substring(start, position);
    }

    private static char inputScalarKind(String type) {
        return switch (type) {
            case "Boolean" -> 'B';
            case "Float", "Decimal" -> 'F';
            case "ID" -> 'I';
            case "Int" -> 'N';
            case "Long" -> 'L';
            case "String", "Date", "DateTime", "Timestamp" -> 'S';
            case "UUID" -> 'U';
            default -> throw new IllegalArgumentException("generated input scalar '" + type + "' is unsupported");
        };
    }

    private static boolean supportsBasicRelayConnection(
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument type,
            TitanGraphqlModelDocument document
    ) {
        if (root.pagination() == null || root.pagination().cursor() == null
                || root.pagination().totalCount() != TitanGraphqlRootDocument.TotalCountMode.EXACT
                || root.pagination().defaultPageSize() < 1
                || root.pagination().maxPageSize() < root.pagination().defaultPageSize()) {
            return false;
        }
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : root.arguments()) {
            TitanGraphqlFieldDocument argumentField = fieldByColumn(type, argument.column());
            if (argument.kind() != TitanGraphqlRootDocument.RootDocumentArgumentKind.EQUALS
                    || !supportsConnectionEqualityArgument(
                            document, argumentField, argument.type(), argument.hops())) {
                return false;
            }
        }
        for (String contextFilterName : root.contextFilters()) {
            TitanGraphqlContextFilterDocument filter = contextFilter(document, contextFilterName);
            TitanGraphqlFieldDocument filterField = fieldByColumn(type, filter.column());
            if (filter.operator() != TitanGraphqlContextFilterDocument.Operator.BOOLEAN_EQUALS
                    && (filter.operator() != TitanGraphqlContextFilterDocument.Operator.EQUALS
                    || filterField == null || !"String".equals(filterField.type()))) {
                return false;
            }
        }
        TitanGraphqlRootDocument.Cursor cursor = root.pagination().cursor();
        TitanGraphqlFieldDocument cursorField = fieldByColumn(type, cursor.column());
        if (cursorField == null || cursorField.nullable() || !isLong(cursorField)) {
            return false;
        }
        return (cursor.tieBreaker().isBlank() && cursor.column().equals("id"))
                || cursor.tieBreaker().equals(cursor.column());
    }

    /**
     * Relation Relay support deliberately starts with the same durable contract as root Relay:
     * one reviewed local sort, exact count, local scalar equality inputs, and all four Relay
     * controls.  Wider relation filter/order paths remain fail-closed until their complete
     * static SQL and cursor codecs are present; this must never fall back to the JVM executor.
     */
    private static boolean isBasicRelayRelationConnection(
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument sourceType,
            TitanGraphqlRelationDocument relation
    ) {
        TitanGraphqlRelationDocument.RelationDocumentPagination pagination = relation.pagination();
        if (relation.cardinality() != TitanGraphqlRelationDocument.RelationDocumentCardinality.MANY
                || pagination == null
                || pagination.mode() != TitanGraphqlRelationDocument.RelationDocumentPaginationMode.RELAY
                || !pagination.totalCount()
                || pagination.defaultPageSize() < 1
                || pagination.maxPageSize() < pagination.defaultPageSize()
                || relation.sortPaths().isEmpty()) {
            return false;
        }
        TitanGraphqlTypeDocument targetType = type(document, relation.targetType());
        TitanGraphqlRelationDocument.RelationDocumentSortPath ordering = relation.sortPaths().getFirst();
        TitanGraphqlFieldDocument cursorField = fieldByColumn(targetType, ordering.column());
        String tieBreaker = ordering.tieBreaker().isBlank() ? ordering.column() : ordering.tieBreaker();
        if (ordering.hops() != 0 || cursorField == null || cursorField.nullable() || !isLong(cursorField)
                || !tieBreaker.equals(ordering.column())) {
            return false;
        }
        TitanGraphqlFieldDocument sourceField = fieldByColumn(sourceType, relation.localColumn());
        if (sourceField == null) {
            sourceField = relationJoinField(document, sourceType, relation);
        }
        // A target join key may deliberately be absent from the public object projection (as it
        // is for Order.customer_id in the Commerce proof).  The source carrier is still typed
        // and the target column remains model-reviewed static SQL, exactly like direct relation
        // reads; requiring a public target field would incorrectly disable that generic shape.
        if (sourceField == null) {
            return false;
        }
        boolean first = false;
        boolean after = false;
        boolean last = false;
        boolean before = false;
        for (TitanGraphqlRelationDocument.RelationDocumentArgument argument : relation.arguments()) {
            switch (argument.kind()) {
                case EQUALS -> {
                    TitanGraphqlFieldDocument argumentField = fieldByColumn(targetType, argument.column());
                    if (!supportsConnectionEqualityArgument(
                            document, argumentField, argument.type(), argument.hops())) {
                        return false;
                    }
                }
                case RELAY_FIRST -> first = argument.name().equals("first");
                case RELAY_AFTER -> after = argument.name().equals("after");
                case RELAY_LAST -> last = argument.name().equals("last");
                case RELAY_BEFORE -> before = argument.name().equals("before");
            }
        }
        return first && after && last && before;
    }

    /** Converts one supported relation contract into the shared root-connection executor shape. */
    private static TitanGraphqlRootDocument relationConnectionContract(TitanGraphqlRelationDocument relation) {
        TitanGraphqlRelationDocument.RelationDocumentPagination relationPagination = relation.pagination();
        TitanGraphqlRelationDocument.RelationDocumentSortPath ordering = relation.sortPaths().getFirst();
        List<TitanGraphqlRootDocument.RootDocumentArgument> arguments = relation.arguments().stream()
                .filter(argument -> argument.kind() == TitanGraphqlRelationDocument.RelationDocumentArgumentKind.EQUALS)
                .map(argument -> new TitanGraphqlRootDocument.RootDocumentArgument(
                        argument.name(), argument.type(), TitanGraphqlRootDocument.RootDocumentArgumentKind.EQUALS,
                        argument.column(), argument.path(), argument.hops()))
                .toList();
        TitanGraphqlRootDocument.Cursor cursor = new TitanGraphqlRootDocument.Cursor(
                ordering.path().isBlank() ? ordering.column() : ordering.path(), ordering.column(),
                ordering.direction() == TitanGraphqlRelationDocument.RelationDocumentSortDirection.DESC
                        ? TitanGraphqlRootDocument.RootDocumentSortDirection.DESC
                        : TitanGraphqlRootDocument.RootDocumentSortDirection.ASC,
                ordering.tieBreaker());
        return new TitanGraphqlRootDocument(
                relation.name(), relation.targetType(), TitanGraphqlRootDocument.RootDocumentOperation.CONNECTION,
                null, new TitanGraphqlRootDocument.RootDocumentPagination(
                        relationPagination.defaultPageSize(), relationPagination.maxPageSize(),
                        TitanGraphqlRootDocument.TotalCountMode.EXACT, cursor),
                arguments, List.of(), List.of(), List.of(), relation.policies());
    }

    private static String connectionSupportFailure(TitanGraphqlRootDocument root) {
        return "connection root '" + root.name() + "' is not enabled: the current database plan requires "
                + "an exact-count Relay root with a non-null local cursor, local integer or declared-enum "
                + "equals arguments, and "
                + "Boolean context filters only; client filters and sort paths are not implemented";
    }

    /** One reviewed local equality input supported by the shared root/relation Relay executor. */
    private static boolean supportsConnectionEqualityArgument(
            TitanGraphqlModelDocument document,
            TitanGraphqlFieldDocument field,
            String argumentType,
            int hops
    ) {
        if (hops != 0) {
            return false;
        }
        String namedType = inputNamedType(argumentType);
        TitanGraphqlEnumDocument declaredEnum = enumType(document, namedType);
        if (field == null) {
            // Retain the existing reviewed hidden-column integer contract. Enum bindings require
            // a modeled field so validation and JDBC storage typing cannot silently diverge.
            return declaredEnum == null && isLong(namedType);
        }
        if (declaredEnum != null) {
            return namedType.equals(inputNamedType(field.type()));
        }
        return isLong(namedType) && isLong(field);
    }

    private static TitanGraphqlTypeDocument type(TitanGraphqlModelDocument document, String name) {
        return document.types().stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("root references unknown type '" + name + "'"));
    }

    private static TitanGraphqlEnumDocument enumType(TitanGraphqlModelDocument document, String name) {
        return document.enums().stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElse(null);
    }

    private static TitanGraphqlInterfaceDocument interfaceType(
            TitanGraphqlModelDocument document,
            String name
    ) {
        return document.interfaces().stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElse(null);
    }

    private static TitanGraphqlUnionDocument unionType(TitanGraphqlModelDocument document, String name) {
        return document.unions().stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElse(null);
    }

    private static TitanGraphqlInputObjectDocument inputObjectType(
            TitanGraphqlModelDocument document,
            String name
    ) {
        return document.inputObjects().stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElse(null);
    }

    private static TitanGraphqlPolicyDocument policy(TitanGraphqlModelDocument document, String name) {
        return document.policies().stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("model references unknown policy '" + name + "'"));
    }

    private static TitanGraphqlContextFilterDocument contextFilter(
            TitanGraphqlModelDocument document,
            String name
    ) {
        return document.contextFilters().stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("model references unknown context filter '" + name + "'"));
    }

    /** Public Relay node type; physical projection remains concrete for static SQL and completion. */
    private static String connectionPublicNodeType(
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument physicalType
    ) {
        return root.outputType().isEmpty() ? physicalType.name() : root.outputType();
    }

    private static List<TitanGraphqlRootDocument> sortedRoots(TitanGraphqlModelDocument document) {
        return document.roots().stream().sorted(Comparator.comparing(TitanGraphqlRootDocument::name)).toList();
    }

    private static List<TitanGraphqlTypeDocument> sortedTypes(TitanGraphqlModelDocument document) {
        return document.types().stream().sorted(Comparator.comparing(TitanGraphqlTypeDocument::name)).toList();
    }

    /** Public schema and execution must consume the retained relation capability, not raw YAML. */
    private static List<TitanGraphqlRelationDocument> selectableRelations(TitanGraphqlTypeDocument type) {
        return type.relations().stream()
                .filter(TitanGraphqlRelationDocument::selectable)
                .sorted(Comparator.comparing(TitanGraphqlRelationDocument::name))
                .toList();
    }

    /** Concrete runtime type plus every abstract condition that can apply to that object. */
    private static String runtimeTypeConditions(
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument type
    ) {
        List<String> names = new ArrayList<>();
        names.add(type.name());
        type.interfaces().stream().sorted().forEach(names::add);
        for (TitanGraphqlUnionDocument union : sortedUnions(document)) {
            if (union.members().contains(type.name())) {
                names.add(union.name());
            }
        }
        return "|" + String.join("|", names) + "|";
    }

    /** Runtime-condition set encoded as one delimiter-safe entry in a {@code |}-separated path. */
    private static String runtimeTypeConditionPathEntry(
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument type
    ) {
        List<String> names = new ArrayList<>();
        names.add(type.name());
        type.interfaces().stream().sorted().forEach(names::add);
        for (TitanGraphqlUnionDocument union : sortedUnions(document)) {
            if (union.members().contains(type.name())) {
                names.add(union.name());
            }
        }
        return String.join(",", names);
    }

    private static List<TitanGraphqlInterfaceDocument> sortedInterfaces(TitanGraphqlModelDocument document) {
        return document.interfaces().stream()
                .sorted(Comparator.comparing(TitanGraphqlInterfaceDocument::name)).toList();
    }

    private static List<TitanGraphqlUnionDocument> sortedUnions(TitanGraphqlModelDocument document) {
        return document.unions().stream().sorted(Comparator.comparing(TitanGraphqlUnionDocument::name)).toList();
    }

    private static List<TitanGraphqlEnumDocument> sortedEnums(TitanGraphqlModelDocument document) {
        return document.enums().stream().sorted(Comparator.comparing(TitanGraphqlEnumDocument::name)).toList();
    }

    private static List<TitanGraphqlInputObjectDocument> sortedInputObjects(TitanGraphqlModelDocument document) {
        return document.inputObjects().stream()
                .sorted(Comparator.comparing(TitanGraphqlInputObjectDocument::name))
                .toList();
    }

    private static List<TitanGraphqlDirectiveDocument> sortedDirectives(TitanGraphqlModelDocument document) {
        return document.directives().stream()
                .sorted(Comparator.comparing(TitanGraphqlDirectiveDocument::name))
                .toList();
    }

    /** Compact, semicolon-free directive semantics carried with the invocation-local AST. */
    private static String executableDirectiveDescriptor(TitanGraphqlModelDocument document) {
        String descriptor = "ed1|";
        for (TitanGraphqlDirectiveDocument directive : sortedDirectives(document)) {
            String locations = directiveLocationMarkers(directive);
            String behavior = directive.behavior() == TitanGraphqlDirectiveDocument.Behavior.INCLUDE_IF
                    ? "I" : "S";
            descriptor = descriptor + directive.name().length() + ":" + directive.name()
                    + ":" + behavior + ":" + locations + "|";
        }
        return descriptor;
    }

    /** Length-prefixed metadata for custom directive introspection. */
    private static String introspectionDirectiveDescriptor(TitanGraphqlModelDocument document) {
        String descriptor = "dd1;";
        for (TitanGraphqlDirectiveDocument directive : sortedDirectives(document)) {
            String locations = directiveLocationMarkers(directive);
            String behavior = directive.behavior() == TitanGraphqlDirectiveDocument.Behavior.INCLUDE_IF
                    ? "I" : "S";
            descriptor = descriptor + directive.name().length() + ":" + directive.name()
                    + directive.description().length() + ":" + directive.description()
                    + behavior + locations.length() + ":" + locations;
        }
        return descriptor;
    }

    private static String directiveLocationMarkers(TitanGraphqlDirectiveDocument directive) {
        String markers = "";
        if (directive.locations().contains(TitanGraphqlDirectiveDocument.ExecutableLocation.FIELD)) {
            markers = markers + "F";
        }
        if (directive.locations().contains(TitanGraphqlDirectiveDocument.ExecutableLocation.FRAGMENT_SPREAD)) {
            markers = markers + "P";
        }
        if (directive.locations().contains(TitanGraphqlDirectiveDocument.ExecutableLocation.INLINE_FRAGMENT)) {
            markers = markers + "I";
        }
        return markers;
    }

    private static List<String> sortedEnumValues(TitanGraphqlEnumDocument enumType) {
        return enumType.values().stream().sorted().toList();
    }

    /** Scalar names exposed by the reviewed model's current schema metadata. */
    private static List<String> introspectionScalarTypes(TitanGraphqlModelDocument document) {
        List<String> result = new ArrayList<>();
        addIntrospectionScalar(result, "Boolean"); // Built-in directive and introspection support require it.
        for (TitanGraphqlTypeDocument type : document.types()) {
            for (TitanGraphqlFieldDocument field : type.fields()) {
                addIntrospectionScalar(result, field.type());
            }
        }
        for (TitanGraphqlInputObjectDocument inputObject : document.inputObjects()) {
            for (TitanGraphqlInputObjectDocument.InputField field : inputObject.fields()) {
                String named = inputNamedType(field.type());
                if (enumType(document, named) == null && inputObjectType(document, named) == null) {
                    addIntrospectionScalar(result, named);
                }
            }
        }
        for (TitanGraphqlMutationDocument mutation : document.mutations()) {
            for (TitanGraphqlMutationDocument.MutationDocumentArgument argument : mutation.arguments()) {
                addIntrospectionScalar(result, argument.type());
            }
        }
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }

    /** Standard introspection object types are part of every executable GraphQL schema. */
    private static List<String> introspectionMetaObjectTypes() {
        return List.of("__Schema", "__Type", "__Field", "__InputValue", "__EnumValue", "__Directive");
    }

    /** Standard introspection enums are schema types, not HTTP- or JVM-owned constants. */
    private static List<String> introspectionMetaEnumTypes() {
        return List.of("__TypeKind", "__DirectiveLocation");
    }

    /**
     * Describes the introspection schema through the same compact metadata consumed for model
     * objects. {@code R<kind>} means that the type column is a complete GraphQL type reference,
     * which preserves list/null wrappers without introducing a meta-schema-specific renderer.
     */
    private static String introspectionMetaObjectFieldDescriptor(String typeName) {
        if (typeName.equals("__Schema")) {
            return introspectionMetaField("description", "String", "S", "")
                    + introspectionMetaField("types", "[__Type!]!", "O", "")
                    + introspectionMetaField("queryType", "__Type!", "O", "")
                    + introspectionMetaField("mutationType", "__Type", "O", "")
                    + introspectionMetaField("subscriptionType", "__Type", "O", "")
                    + introspectionMetaField("directives", "[__Directive!]!", "O", "");
        }
        if (typeName.equals("__Type")) {
            String includeDeprecated = "includeDeprecated=Boolean!=S=false";
            return introspectionMetaField("kind", "__TypeKind!", "E", "")
                    + introspectionMetaField("name", "String", "S", "")
                    + introspectionMetaField("description", "String", "S", "")
                    + introspectionMetaField("specifiedByURL", "String", "S", "")
                    + introspectionMetaField("fields", "[__Field!]", "O", includeDeprecated)
                    + introspectionMetaField("interfaces", "[__Type!]", "O", "")
                    + introspectionMetaField("possibleTypes", "[__Type!]", "O", "")
                    + introspectionMetaField("enumValues", "[__EnumValue!]", "O", includeDeprecated)
                    + introspectionMetaField("inputFields", "[__InputValue!]", "O", includeDeprecated)
                    + introspectionMetaField("ofType", "__Type", "O", "")
                    + introspectionMetaField("isOneOf", "Boolean", "S", "");
        }
        if (typeName.equals("__Field")) {
            return introspectionMetaField("name", "String!", "S", "")
                    + introspectionMetaField("description", "String", "S", "")
                    + introspectionMetaField("args", "[__InputValue!]!", "O",
                    "includeDeprecated=Boolean!=S=false")
                    + introspectionMetaField("type", "__Type!", "O", "")
                    + introspectionMetaField("isDeprecated", "Boolean!", "S", "")
                    + introspectionMetaField("deprecationReason", "String", "S", "");
        }
        if (typeName.equals("__InputValue")) {
            return introspectionMetaField("name", "String!", "S", "")
                    + introspectionMetaField("description", "String", "S", "")
                    + introspectionMetaField("type", "__Type!", "O", "")
                    + introspectionMetaField("defaultValue", "String", "S", "")
                    + introspectionMetaField("isDeprecated", "Boolean!", "S", "")
                    + introspectionMetaField("deprecationReason", "String", "S", "");
        }
        if (typeName.equals("__EnumValue")) {
            return introspectionMetaField("name", "String!", "S", "")
                    + introspectionMetaField("description", "String", "S", "")
                    + introspectionMetaField("isDeprecated", "Boolean!", "S", "")
                    + introspectionMetaField("deprecationReason", "String", "S", "");
        }
        if (typeName.equals("__Directive")) {
            return introspectionMetaField("name", "String!", "S", "")
                    + introspectionMetaField("description", "String", "S", "")
                    + introspectionMetaField("isRepeatable", "Boolean!", "S", "")
                    + introspectionMetaField("locations", "[__DirectiveLocation!]!", "E", "")
                    + introspectionMetaField("args", "[__InputValue!]!", "O",
                    "includeDeprecated=Boolean!=S=false");
        }
        return "";
    }

    private static String introspectionMetaField(
            String name,
            String typeReference,
            String namedKind,
            String arguments
    ) {
        return introspectionFieldDescriptor(name, typeReference, !typeReference.endsWith("!"),
                "R" + namedKind, arguments);
    }

    private static String introspectionEnumValueDescriptor(
            TitanGraphqlModelDocument document,
            String typeName
    ) {
        if (typeName.equals("SortDirection")) {
            return introspectionEnumValueDescriptor(List.of("ASC", "DESC"), null);
        }
        if (typeName.equals("__TypeKind")) {
            return introspectionEnumValueDescriptor(
                    List.of("SCALAR", "OBJECT", "INTERFACE", "UNION", "ENUM", "INPUT_OBJECT", "LIST", "NON_NULL"),
                    null);
        }
        if (typeName.equals("__DirectiveLocation")) {
            return introspectionEnumValueDescriptor(List.of(
                    "QUERY", "MUTATION", "SUBSCRIPTION", "FIELD", "FRAGMENT_DEFINITION", "FRAGMENT_SPREAD",
                    "INLINE_FRAGMENT", "VARIABLE_DEFINITION", "SCHEMA", "SCALAR", "OBJECT", "FIELD_DEFINITION",
                    "ARGUMENT_DEFINITION", "INTERFACE", "UNION", "ENUM", "ENUM_VALUE", "INPUT_OBJECT",
                    "INPUT_FIELD_DEFINITION"), null);
        }
        TitanGraphqlEnumDocument enumType = enumType(document, typeName);
        if (enumType != null) {
            return introspectionEnumValueDescriptor(sortedEnumValues(enumType), enumType.valueMetadata());
        }
        return "ev1;";
    }

    /**
     * Emits delimiter-safe, versioned enum-value metadata for the transpiled introspection path.
     * Empty descriptions/reasons represent GraphQL null; deprecated values without an authored
     * reason expose the standard {@code @deprecated} default reason.
     */
    private static String introspectionEnumValueDescriptor(
            List<String> values,
            List<TitanGraphqlEnumDocument.EnumValueMetadata> metadata
    ) {
        Map<String, TitanGraphqlEnumDocument.EnumValueMetadata> metadataByValue = new TreeMap<>();
        if (metadata != null) {
            for (TitanGraphqlEnumDocument.EnumValueMetadata valueMetadata : metadata) {
                metadataByValue.put(valueMetadata.name(), valueMetadata);
            }
        }
        String descriptor = "ev1;";
        for (String value : values) {
            TitanGraphqlEnumDocument.EnumValueMetadata valueMetadata = metadataByValue.get(value);
            String description = valueMetadata == null ? "" : valueMetadata.description();
            boolean deprecated = valueMetadata != null && valueMetadata.deprecated();
            String reason = valueMetadata == null ? "" : valueMetadata.deprecationReason();
            if (deprecated && reason.isBlank()) {
                reason = "No longer supported";
            }
            descriptor = descriptor + value.length() + ":" + value
                    + description.length() + ":" + description
                    + (deprecated ? "1" : "0")
                    + reason.length() + ":" + reason;
        }
        return descriptor;
    }

    private static void addIntrospectionScalar(List<String> scalars, String typeReference) {
        String type = inputNamedType(typeReference);
        if (List.of("Boolean", "Date", "DateTime", "Decimal", "Float", "ID", "Int", "Long", "String",
                "Timestamp", "UUID").contains(type) && scalars.contains(type) == false) {
            scalars.add(type);
        }
    }

    /** Stable, delimiter-safe metadata consumed by the shared transpiled scalar-field renderer. */
    private static String introspectionScalarFieldDescriptor(
            TitanGraphqlModelDocument document,
            TitanGraphqlTypeDocument type
    ) {
        String result = "";
        for (TitanGraphqlFieldDocument field : sortedFields(type)) {
            String kind = enumType(document, inputNamedType(field.type())) == null ? "S" : "E";
            result = result + field.name() + ":" + field.type() + ":" + (field.nullable() ? "1" : "0")
                    + ":" + kind + ";";
        }
        for (TitanGraphqlRelationDocument relation : selectableRelations(type)) {
            if (isBasicRelayRelationConnection(document, type, relation)) {
                result = result + introspectionFieldDescriptor(relation.name(), relation.targetType() + "Connection",
                        relation.nullable(), "O", introspectionRelationConnectionArgumentDescriptor(document, relation));
                continue;
            }
            boolean many = relation.cardinality() == TitanGraphqlRelationDocument.RelationDocumentCardinality.MANY;
            result = result + relation.name() + ":" + relation.targetType() + ":"
                    + (many || !relation.nullable() ? "0" : "1") + ":" + (many ? "LNO" : "O") + ";";
        }
        return result;
    }

    /** Stable field metadata for an authored GraphQL interface. */
    private static String introspectionInterfaceFieldDescriptor(
            TitanGraphqlModelDocument document,
            TitanGraphqlInterfaceDocument interfaceType
    ) {
        String result = "";
        for (TitanGraphqlInterfaceDocument.InterfaceField field : interfaceType.fields().stream()
                .sorted(Comparator.comparing(TitanGraphqlInterfaceDocument.InterfaceField::name)).toList()) {
            result = result + introspectionFieldDescriptor(field.name(), field.type(),
                    !field.type().endsWith("!"), "R" + introspectionOutputKind(document, field.type()), "");
        }
        return result;
    }

    /**
     * Produces one length-counted metadata record for every output-field descriptor row. Model
     * and interface fields contribute authored values; generated roots, relations, wrappers, and
     * introspection types deliberately receive empty non-deprecated records.
     */
    private static String introspectionOutputFieldMetadataDescriptor(
            String fieldsDescriptor,
            TitanGraphqlTypeDocument modelType,
            TitanGraphqlInterfaceDocument interfaceType
    ) {
        Map<String, String> descriptions = new TreeMap<>();
        Map<String, Boolean> deprecated = new TreeMap<>();
        Map<String, String> reasons = new TreeMap<>();
        if (modelType != null) {
            for (TitanGraphqlFieldDocument field : modelType.fields()) {
                descriptions.put(field.name(), field.description());
                deprecated.put(field.name(), field.deprecated());
                reasons.put(field.name(), field.deprecationReason());
            }
        }
        if (interfaceType != null) {
            for (TitanGraphqlInterfaceDocument.InterfaceField field : interfaceType.fields()) {
                descriptions.put(field.name(), field.description());
                deprecated.put(field.name(), field.deprecated());
                reasons.put(field.name(), field.deprecationReason());
            }
        }
        String result = "fm1;";
        int position = 0;
        while (fieldsDescriptor != null && position < fieldsDescriptor.length()) {
            int colon = fieldsDescriptor.indexOf(':', position);
            int end = fieldsDescriptor.indexOf(';', position);
            if (colon <= position || end <= colon) {
                throw new IllegalArgumentException("generated introspection field descriptor is malformed");
            }
            String name = fieldsDescriptor.substring(position, colon);
            String description = descriptions.getOrDefault(name, "");
            boolean isDeprecated = deprecated.getOrDefault(name, false);
            String reason = reasons.getOrDefault(name, "");
            if (isDeprecated && reason.isEmpty()) {
                reason = "No longer supported";
            }
            result = result + name.length() + ":" + name
                    + description.length() + ":" + description
                    + (isDeprecated ? "1" : "0")
                    + reason.length() + ":" + reason + ";";
            position = end + 1;
        }
        return result;
    }

    private static String introspectionOutputKind(
            TitanGraphqlModelDocument document,
            String typeReference
    ) {
        String named = inputNamedType(typeReference);
        if (enumType(document, named) != null) return "E";
        if (interfaceType(document, named) != null) return "T";
        if (unionType(document, named) != null) return "U";
        if (document.types().stream().anyMatch(candidate -> candidate.name().equals(named))) return "O";
        return "S";
    }

    private static String introspectionInterfacesDescriptor(TitanGraphqlTypeDocument type) {
        String descriptor = "tl1;";
        if (type == null) return descriptor;
        for (String interfaceName : type.interfaces().stream().sorted().toList()) {
            descriptor = descriptor + "T:" + interfaceName + ";";
        }
        return descriptor;
    }

    private static String introspectionPossibleTypesDescriptor(
            TitanGraphqlModelDocument document,
            TitanGraphqlInterfaceDocument interfaceType,
            TitanGraphqlUnionDocument union
    ) {
        String descriptor = "tl1;";
        if (interfaceType != null) {
            for (TitanGraphqlTypeDocument type : sortedTypes(document)) {
                if (type.interfaces().contains(interfaceType.name())) {
                    descriptor = descriptor + "O:" + type.name() + ";";
                }
            }
        } else if (union != null) {
            for (String member : union.members().stream().sorted().toList()) {
                descriptor = descriptor + "O:" + member + ";";
            }
        }
        return descriptor;
    }

    private static String abstractPossibleTypeNames(
            TitanGraphqlModelDocument document,
            TitanGraphqlInterfaceDocument interfaceType,
            TitanGraphqlUnionDocument union
    ) {
        List<String> names = new ArrayList<>();
        if (interfaceType != null) {
            for (TitanGraphqlTypeDocument type : sortedTypes(document)) {
                if (type.interfaces().contains(interfaceType.name())) names.add(type.name());
            }
        } else if (union != null) {
            names.addAll(union.members());
            names.sort(Comparator.naturalOrder());
        }
        return String.join(",", names);
    }

    /**
     * Describes only query roots that this generated routine can actually execute.  A point root
     * returns a nullable object; a reviewed Relay connection returns a non-null connection
     * object.  Keeping this derived from the same support predicate as dispatch prevents the
     * capability-gated introspection view from advertising a root the installed routine rejects.
     */
    private static String introspectionQueryFieldDescriptor(TitanGraphqlModelDocument document) {
        String result = "";
        for (TitanGraphqlRootDocument root : sortedRoots(document)) {
            TitanGraphqlTypeDocument type = type(document, root.type());
            if (root.operation() == TitanGraphqlRootDocument.RootDocumentOperation.POINT) {
                String outputType = root.outputType().isEmpty() ? type.name() : root.outputType();
                result = result + introspectionFieldDescriptor(root.name(), outputType, true,
                        introspectionOutputKind(document, outputType),
                        introspectionPointArgumentDescriptor(document, root));
            } else if (supportsBasicRelayConnection(root, type, document)) {
                result = result + introspectionFieldDescriptor(root.name(),
                        connectionPublicNodeType(root, type) + "Connection", false, "O",
                        introspectionConnectionArgumentDescriptor(document, root, type));
            }
        }
        return result;
    }

    /** A custom generated mutation returns its reviewed model object and is non-null on success. */
    private static String introspectionMutationFieldDescriptor(TitanGraphqlModelDocument document) {
        String result = "";
        for (TitanGraphqlMutationDocument mutation : sortedMutations(document)) {
            result = result + introspectionFieldDescriptor(mutation.name(), mutation.type(), false, "O",
                    introspectionMutationArgumentDescriptor(document, mutation));
        }
        return result;
    }

    /** Emits one object-field descriptor, optionally carrying its generated executable inputs. */
    private static String introspectionFieldDescriptor(
            String name,
            String type,
            boolean nullable,
            String kind,
            String arguments
    ) {
        String descriptor = name + ":" + type + ":" + (nullable ? "1" : "0") + ":" + kind;
        return arguments.length() == 0 ? descriptor + ";" : descriptor + "|" + arguments + ";";
    }

    /** Point-root bindings require every reviewed equality argument before they execute SQL. */
    private static String introspectionPointArgumentDescriptor(
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root
    ) {
        Map<String, String> arguments = new TreeMap<>();
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : pointArguments(root)) {
            putIntrospectionArgument(arguments, argument.name(), requiredInputType(argument.type()),
                    introspectionInputKind(document, argument.type()), argument.defaultValue());
        }
        return introspectionArguments(arguments);
    }

    /**
     * The connection descriptor mirrors the actual generated argument acceptance: optional
     * Relay controls plus the reviewed local equality/filter/order inputs enabled for that root.
     */
    private static String introspectionConnectionArgumentDescriptor(
            TitanGraphqlModelDocument document,
            TitanGraphqlRootDocument root,
            TitanGraphqlTypeDocument type
    ) {
        Map<String, String> arguments = new TreeMap<>();
        putIntrospectionArgument(arguments, "after", "String", "S");
        putIntrospectionArgument(arguments, "before", "String", "S");
        putIntrospectionArgument(arguments, "first", "Int", "S");
        putIntrospectionArgument(arguments, "last", "Int", "S");
        for (TitanGraphqlRootDocument.RootDocumentArgument argument : root.arguments()) {
            putIntrospectionArgument(arguments, argument.name(), argument.type(),
                    introspectionInputKind(document, argument.type()), argument.defaultValue());
        }
        if (!supportedConnectionFilters(document, type, root).isEmpty()) {
            putIntrospectionArgument(arguments, "filter", type.name() + "Filter", "I");
        }
        if (!root.sortPaths().isEmpty()) {
            putIntrospectionArgument(arguments, "orderBy", "[" + type.name() + "OrderBy!]", "I");
        }
        return introspectionArguments(arguments);
    }

    /** The relation entry point accepts Relay controls plus only its reviewed equality inputs. */
    private static String introspectionRelationConnectionArgumentDescriptor(
            TitanGraphqlModelDocument document,
            TitanGraphqlRelationDocument relation
    ) {
        Map<String, String> arguments = new TreeMap<>();
        putIntrospectionArgument(arguments, "after", "String", "S");
        putIntrospectionArgument(arguments, "before", "String", "S");
        putIntrospectionArgument(arguments, "first", "Int", "S");
        putIntrospectionArgument(arguments, "last", "Int", "S");
        for (TitanGraphqlRelationDocument.RelationDocumentArgument argument : relation.arguments()) {
            if (argument.kind() == TitanGraphqlRelationDocument.RelationDocumentArgumentKind.EQUALS) {
                putIntrospectionArgument(arguments, argument.name(), argument.type(),
                        introspectionInputKind(document, argument.type()), argument.defaultValue());
            }
        }
        return introspectionArguments(arguments);
    }

    /** Mutation bindings currently require every explicit reviewed scalar input. */
    private static String introspectionMutationArgumentDescriptor(
            TitanGraphqlModelDocument document,
            TitanGraphqlMutationDocument mutation
    ) {
        Map<String, String> arguments = new TreeMap<>();
        if (mutation.input() != null) {
            putIntrospectionArgument(arguments, mutation.input().name(), requiredInputType(mutation.input().type()),
                    introspectionInputKind(document, mutation.input().type()), mutation.input().defaultValue());
            return introspectionArguments(arguments);
        }
        for (MutationBinding argument : sortedMutationArguments(mutation)) {
            TitanGraphqlMutationDocument.MutationDocumentArgument modelArgument = mutation.arguments().stream()
                    .filter(candidate -> candidate.name().equals(argument.name())).findFirst().orElseThrow();
            putIntrospectionArgument(arguments, argument.name(), requiredInputType(argument.type()),
                    introspectionInputKind(document, argument.type()), modelArgument.defaultValue());
        }
        return introspectionArguments(arguments);
    }

    private static void putIntrospectionArgument(Map<String, String> arguments, String name, String type, String kind) {
        putIntrospectionArgument(arguments, name, type, kind, "");
    }

    private static void putIntrospectionArgument(
            Map<String, String> arguments,
            String name,
            String type,
            String kind,
            String defaultValue
    ) {
        String value = type + "=" + kind;
        if (defaultValue != null && !defaultValue.isEmpty()) {
            value = value + "=~" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(defaultValue.getBytes(StandardCharsets.UTF_8));
        }
        String existing = arguments.putIfAbsent(name, value);
        if (existing != null && !existing.equals(value)) {
            throw new IllegalArgumentException("generated introspection argument '" + name
                    + "' has incompatible types '" + existing + "' and '" + value + "'");
        }
    }

    private static String introspectionArguments(Map<String, String> arguments) {
        String result = "";
        for (Map.Entry<String, String> argument : arguments.entrySet()) {
            result = result + (result.length() == 0 ? "" : ",") + argument.getKey() + "=" + argument.getValue();
        }
        return result;
    }

    private static String requiredInputType(String type) {
        String normalized = type == null ? "" : type.trim();
        if (normalized.length() == 0) {
            throw new IllegalArgumentException("generated introspection argument type is missing");
        }
        return normalized.endsWith("!") ? normalized : normalized + "!";
    }

    /** Maps generator-owned input types to GraphQL introspection kind markers. */
    private static String introspectionInputKind(TitanGraphqlModelDocument document, String typeReference) {
        String named = inputNamedType(typeReference);
        if (named.equals("SortDirection") || enumType(document, named) != null) {
            return "E";
        }
        if (introspectionInputObjectNames(document).contains(named)) {
            return "I";
        }
        inputScalarKind(named);
        return "S";
    }

    /** Generated input objects are schema data, not runtime model objects. */
    private static List<String> introspectionInputObjectNames(TitanGraphqlModelDocument document) {
        List<String> names = new ArrayList<>();
        for (TitanGraphqlInputObjectDocument inputObject : sortedInputObjects(document)) {
            addIntrospectionDerivedObjectType(names, inputObject.name());
        }
        for (TitanGraphqlRootDocument root : sortedRoots(document)) {
            TitanGraphqlTypeDocument type = type(document, root.type());
            List<TitanGraphqlFilterLayout.Binding> filters = supportedConnectionFilters(document, type, root);
            if (!filters.isEmpty()) {
                addIntrospectionDerivedObjectType(names, type.name() + "Filter");
                for (TitanGraphqlFilterLayout.Binding filter : filters) {
                    addIntrospectionDerivedObjectType(names, inputNamedType(filter.graphqlType()) + "Filter");
                }
            }
            if (!root.sortPaths().isEmpty()) {
                addIntrospectionDerivedObjectType(names, type.name() + "OrderBy");
            }
        }
        return List.copyOf(names);
    }

    /** Whether generated input objects expose the shared order-direction enum. */
    private static boolean introspectionHasSortDirection(TitanGraphqlModelDocument document) {
        for (TitanGraphqlRootDocument root : sortedRoots(document)) {
            if (!root.sortPaths().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Describes fields of the generated input objects from the same reviewed bindings used by
     * preflight coercion. The values use the shared {@code name=Type=kind} input descriptor, so
     * {@code __Type.inputFields} and {@code __Field.args} cannot diverge through a JVM schema.
     */
    private static String introspectionInputObjectFieldDescriptor(
            TitanGraphqlModelDocument document,
            String typeName
    ) {
        TitanGraphqlInputObjectDocument authored = inputObjectType(document, typeName);
        if (authored != null) {
            String descriptor = "iv1;";
            List<TitanGraphqlInputObjectDocument.InputField> fields = authored.fields().stream()
                    .sorted(Comparator.comparing(TitanGraphqlInputObjectDocument.InputField::name))
                    .toList();
            for (TitanGraphqlInputObjectDocument.InputField field : fields) {
                String kind = introspectionInputKind(document, field.type());
                String reason = field.deprecated()
                        ? field.deprecationReason().isEmpty() ? "No longer supported" : field.deprecationReason()
                        : "";
                descriptor = descriptor
                        + field.name().length() + ":" + field.name()
                        + field.type().length() + ":" + field.type()
                        + kind.length() + ":" + kind
                        + (field.defaultValue().isEmpty() ? "0" : "1")
                        + field.defaultValue().length() + ":" + field.defaultValue()
                        + field.description().length() + ":" + field.description()
                        + (field.deprecated() ? "1" : "0")
                        + reason.length() + ":" + reason;
            }
            return descriptor;
        }
        Map<String, String> fields = new TreeMap<>();
        for (TitanGraphqlRootDocument root : sortedRoots(document)) {
            TitanGraphqlTypeDocument type = type(document, root.type());
            List<TitanGraphqlFilterLayout.Binding> filters = supportedConnectionFilters(document, type, root);
            if (!filters.isEmpty() && typeName.equals(type.name() + "Filter")) {
                putIntrospectionArgument(fields, "and", "[" + type.name() + "Filter!]", "I");
                putIntrospectionArgument(fields, "not", type.name() + "Filter", "I");
                putIntrospectionArgument(fields, "or", "[" + type.name() + "Filter!]", "I");
                for (TitanGraphqlFilterLayout.Binding filter : filters) {
                    String scalarType = inputNamedType(filter.graphqlType());
                    putIntrospectionArgument(fields, filter.fieldName(), scalarType + "Filter", "I");
                }
            }
            for (TitanGraphqlFilterLayout.Binding filter : filters) {
                String scalarType = inputNamedType(filter.graphqlType());
                if (!typeName.equals(scalarType + "Filter")) {
                    continue;
                }
                String operator = graphqlFilterOperatorName(filter.operator());
                String valueType = filter.operator().equals("isnull") ? "Boolean"
                        : filter.operator().equals("in") ? "[" + scalarType + "!]" : scalarType;
                putIntrospectionArgument(fields, operator, valueType,
                        filter.operator().equals("isnull") ? "S" : introspectionInputKind(document, scalarType));
            }
            if (!root.sortPaths().isEmpty() && typeName.equals(type.name() + "OrderBy")) {
                for (TitanGraphqlRootDocument.RootDocumentSortPath sort : root.sortPaths()) {
                    putIntrospectionArgument(fields, sort.name(), "SortDirection", "E");
                }
            }
        }
        return introspectionArguments(fields);
    }

    /** Metadata for generated Relay wrapper objects, derived from executable connection bindings. */
    private static String introspectionDerivedObjectFieldDescriptor(
            TitanGraphqlModelDocument document,
            String typeName
    ) {
        if (typeName.equals("Query")) {
            return introspectionQueryFieldDescriptor(document);
        }
        if (typeName.equals("Mutation")) {
            return introspectionMutationFieldDescriptor(document);
        }
        if (typeName.equals("PageInfo") && !introspectionConnectionTypes(document).isEmpty()) {
            return "endCursor:String:1:S;hasNextPage:Boolean:0:S;hasPreviousPage:Boolean:0:S;startCursor:String:1:S;";
        }
        for (String connectionType : introspectionConnectionTypes(document)) {
            String targetName = connectionType.substring(0, connectionType.length() - "Connection".length());
            if (typeName.equals(connectionType)) {
                return "edges:" + targetName + "Edge:0:LNO;pageInfo:PageInfo:0:O;totalCount:Int:0:S;";
            }
            if (typeName.equals(targetName + "Edge")) {
                return "cursor:String:0:S;node:" + targetName + ":0:"
                        + introspectionOutputKind(document, targetName) + ";";
            }
        }
        return "";
    }

    /**
     * The derived object inventory is intentionally generated from the same executable-root
     * predicate as dispatch and descriptors.  It avoids stale connection metadata and makes
     * every emitted object reference resolvable through {@code __type}.
     */
    private static List<String> introspectionDerivedObjectTypes(TitanGraphqlModelDocument document) {
        List<String> result = new ArrayList<>();
        for (String connectionType : introspectionConnectionTypes(document)) {
            addIntrospectionDerivedObjectType(result, connectionType);
            addIntrospectionDerivedObjectType(result, connectionType.substring(0,
                    connectionType.length() - "Connection".length()) + "Edge");
        }
        if (!result.isEmpty()) {
            addIntrospectionDerivedObjectType(result, "PageInfo");
        }
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }

    /** Every derived Relay wrapper that the installed routine can actually execute. */
    private static List<String> introspectionConnectionTypes(TitanGraphqlModelDocument document) {
        List<String> result = new ArrayList<>();
        for (TitanGraphqlRootDocument root : sortedRoots(document)) {
            TitanGraphqlTypeDocument type = type(document, root.type());
            if (supportsBasicRelayConnection(root, type, document)) {
                addIntrospectionDerivedObjectType(result,
                        connectionPublicNodeType(root, type) + "Connection");
            }
        }
        for (TitanGraphqlTypeDocument sourceType : sortedTypes(document)) {
            for (TitanGraphqlRelationDocument relation : selectableRelations(sourceType)) {
                if (isBasicRelayRelationConnection(document, sourceType, relation)) {
                    addIntrospectionDerivedObjectType(result, relation.targetType() + "Connection");
                }
            }
        }
        return List.copyOf(result);
    }

    private static void addIntrospectionDerivedObjectType(List<String> types, String typeName) {
        if (!types.contains(typeName)) {
            types.add(typeName);
        }
    }

    /** Point roots can use the bounded database-side child-selection merge plan. */
    private static String pointRootNames(TitanGraphqlModelDocument document) {
        String names = "";
        for (TitanGraphqlRootDocument root : sortedRoots(document)) {
            if (root.operation() == TitanGraphqlRootDocument.RootDocumentOperation.POINT) {
                names = names.length() == 0 ? root.name() : names + "," + root.name();
            }
        }
        return names;
    }

    private static List<TitanGraphqlMutationDocument> sortedMutations(TitanGraphqlModelDocument document) {
        return document.mutations().stream().sorted(Comparator.comparing(TitanGraphqlMutationDocument::name)).toList();
    }

    private static List<MutationBinding> sortedMutationArguments(
            TitanGraphqlMutationDocument mutation
    ) {
        if (mutation.input() == null) {
            return mutation.arguments().stream()
                    .map(argument -> new MutationBinding(argument.name(), argument.type(), argument.column(),
                            argument.key(), argument.name(), ""))
                    .sorted(Comparator.comparing(MutationBinding::name))
                    .toList();
        }
        return mutation.inputBindings().stream()
                .map(binding -> new MutationBinding(binding.name(), binding.type(), binding.column(),
                        binding.key(), mutation.input().name(), binding.path()))
                .sorted(Comparator.comparing(MutationBinding::name))
                .toList();
    }

    private static int mutationPublicArgumentCount(TitanGraphqlMutationDocument mutation) {
        return mutation.input() == null ? mutation.arguments().size() : 1;
    }

    private static String mutationPublicArgumentType(
            TitanGraphqlMutationDocument mutation,
            MutationBinding binding
    ) {
        return mutation.input() == null ? binding.type() : mutation.input().type();
    }

    private static List<TitanGraphqlMutationDocument.MutationDocumentPayloadField> sortedPayload(
            TitanGraphqlMutationDocument mutation
    ) {
        return mutation.payload().stream()
                .sorted(Comparator.comparing(TitanGraphqlMutationDocument.MutationDocumentPayloadField::name))
                .toList();
    }

    private static MutationBinding mutationArgument(
            TitanGraphqlMutationDocument mutation,
            String name
    ) {
        return sortedMutationArguments(mutation).stream().filter(argument -> argument.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("mutation '" + mutation.name()
                        + "' payload references unknown argument '" + name + "'"));
    }

    private record MutationBinding(
            String name,
            String type,
            String column,
            boolean key,
            String sourceArgument,
            String sourcePath
    ) {
    }

    private static List<TitanGraphqlFieldDocument> sortedFields(TitanGraphqlTypeDocument type) {
        return type.fields().stream().sorted(Comparator.comparing(TitanGraphqlFieldDocument::name)).toList();
    }

    private static boolean isLong(String graphqlType) {
        return "Int".equals(graphqlType) || "Long".equals(graphqlType);
    }

    private static boolean isLong(TitanGraphqlFieldDocument field) {
        return isLong(field.type()) || isIntegralId(field);
    }

    private static boolean isLong(TitanGraphqlFilterLayout.Binding filter) {
        return isLong(filter.graphqlType()) || (isId(filter.graphqlType())
                && filter.idStorage() == TitanGraphqlFieldDocument.FieldDocumentIdStorage.INTEGRAL);
    }

    private static boolean isIntegralId(TitanGraphqlFieldDocument field) {
        return isId(field.type())
                && field.idStorage() == TitanGraphqlFieldDocument.FieldDocumentIdStorage.INTEGRAL;
    }

    private static boolean isStringId(TitanGraphqlFieldDocument field) {
        return isId(field.type())
                && field.idStorage() == TitanGraphqlFieldDocument.FieldDocumentIdStorage.STRING;
    }

    private static boolean isId(String graphqlType) {
        return "ID".equals(graphqlType);
    }

    private static boolean isBoolean(String graphqlType) {
        return "Boolean".equals(graphqlType);
    }

    private static boolean isDecimal(String graphqlType) {
        return "Float".equals(graphqlType) || "Decimal".equals(graphqlType);
    }

    private static boolean isUuid(String graphqlType) {
        return "UUID".equals(graphqlType);
    }

    private static String jdbcSetter(String graphqlType) {
        if (isLong(graphqlType)) {
            return "setLong";
        }
        if (isBoolean(graphqlType)) {
            return "setBoolean";
        }
        if (isDecimal(graphqlType)) {
            return "setDouble";
        }
        return "setString";
    }

    private static String jdbcSetter(TitanGraphqlFieldDocument field) {
        if (isLong(field)) {
            return "setLong";
        }
        return jdbcSetter(field.type());
    }

    /**
     * Selects storage-aware binding when the argument targets a modeled field, while preserving
     * the reviewed hidden-column integer argument contract used by unrelated schemas.
     */
    private static String jdbcSetter(TitanGraphqlFieldDocument field, String argumentType) {
        return field == null ? jdbcSetter(argumentType) : jdbcSetter(field);
    }

    private static String jdbcSetter(TitanGraphqlFilterLayout.Binding filter) {
        if (isLong(filter)) {
            return "setLong";
        }
        return jdbcSetter(filter.graphqlType());
    }

    private static boolean supportsUnpaginatedRelationBatch(
            TitanGraphqlTypeDocument sourceType,
            TitanGraphqlRelationDocument relation,
            TitanGraphqlModelDocument document
    ) {
        boolean common = relation.selectable() && relation.batchable()
                && relation.arguments().isEmpty()
                && (relation.pagination() == null
                || relation.pagination().mode() == TitanGraphqlRelationDocument.RelationDocumentPaginationMode.NONE)
                && relationJoinField(document, sourceType, relation) != null;
        if (!common) {
            return false;
        }
        if (relation.cardinality() == TitanGraphqlRelationDocument.RelationDocumentCardinality.ONE) {
            return true;
        }
        // The fixed-slot SQL joins by parent slot, not by source-row identity. A reviewed scalar
        // local join therefore remains correct for MANY relations even when it is non-primary or
        // duplicated across parents: each duplicate slot receives its own ordered child rows.
        return true;
    }

    /** Parent-cardinality-independent Relay execution currently begins with exact-count plans. */
    private static boolean supportsRelayRelationBatch(
            TitanGraphqlTypeDocument sourceType,
            TitanGraphqlRelationDocument relation,
            TitanGraphqlModelDocument document
    ) {
        return relation.selectable() && relation.batchable()
                && relationJoinField(document, sourceType, relation) != null
                && isBasicRelayRelationConnection(document, sourceType, relation);
    }

    private static String batchParentKeys(String scope, TitanGraphqlRelationDocument relation) {
        return scopedLocal("batchParentKeys", scope, relation.name());
    }

    private static String batchPlanStarts(String scope, TitanGraphqlRelationDocument relation) {
        return scopedLocal("batchPlanStarts", scope, relation.name());
    }

    private static String batchParentActivity(String scope, TitanGraphqlRelationDocument relation) {
        return scopedLocal("batchParentActivity", scope, relation.name());
    }

    private static String batchParentPaths(String scope, TitanGraphqlRelationDocument relation) {
        return scopedLocal("batchParentPaths", scope, relation.name());
    }

    private static String batchParentOwners(String scope, TitanGraphqlRelationDocument relation) {
        return scopedLocal("batchParentOwners", scope, relation.name());
    }

    private static String batchKeyString(TitanGraphqlFieldDocument field, String value) {
        if (isBoolean(field.type())) {
            return "(" + value + " ? \"true\" : \"false\")";
        }
        if (isLong(field) || isDecimal(field.type())) {
            return "(\"\" + " + value + ")";
        }
        return value;
    }

    private static String batchKeyBindingValue(TitanGraphqlFieldDocument field, String encodedValue) {
        if (isLong(field)) {
            return "DatabaseGraphqlEngine.longArgument(" + encodedValue + ")";
        }
        if (isBoolean(field.type())) {
            return encodedValue + ".equals(\"true\")";
        }
        if (isDecimal(field.type())) {
            return "DatabaseGraphqlEngine.decimalArgument(" + encodedValue + ")";
        }
        return encodedValue;
    }

    private static String inactiveBatchKey(TitanGraphqlFieldDocument field) {
        if (isLong(field)) {
            return "0";
        }
        if (isBoolean(field.type())) {
            return "false";
        }
        if (isDecimal(field.type())) {
            return "0";
        }
        if (isUuid(field.type())) {
            return "00000000-0000-0000-0000-000000000000";
        }
        return "";
    }

    private static String local(String prefix, String name) {
        return prefix + javaTypeName(name);
    }

    private static List<IntrospectionNamedType> introspectionNamedTypes(
            TitanGraphqlModelDocument document
    ) {
        List<IntrospectionNamedType> result = new ArrayList<>();
        result.add(new IntrospectionNamedType("Query", "OBJECT"));
        if (!document.mutations().isEmpty()) result.add(new IntrospectionNamedType("Mutation", "OBJECT"));
        for (TitanGraphqlTypeDocument type : sortedTypes(document)) {
            result.add(new IntrospectionNamedType(type.name(), "OBJECT"));
        }
        for (TitanGraphqlInterfaceDocument type : sortedInterfaces(document)) {
            result.add(new IntrospectionNamedType(type.name(), "INTERFACE"));
        }
        for (TitanGraphqlUnionDocument type : sortedUnions(document)) {
            result.add(new IntrospectionNamedType(type.name(), "UNION"));
        }
        for (String type : introspectionDerivedObjectTypes(document)) {
            result.add(new IntrospectionNamedType(type, "OBJECT"));
        }
        for (String type : introspectionInputObjectNames(document)) {
            result.add(new IntrospectionNamedType(type, "INPUT_OBJECT"));
        }
        if (introspectionHasSortDirection(document)) {
            result.add(new IntrospectionNamedType("SortDirection", "ENUM"));
        }
        for (TitanGraphqlEnumDocument type : sortedEnums(document)) {
            result.add(new IntrospectionNamedType(type.name(), "ENUM"));
        }
        for (String type : introspectionScalarTypes(document)) {
            result.add(new IntrospectionNamedType(type, "SCALAR"));
        }
        for (String type : introspectionMetaObjectTypes()) {
            result.add(new IntrospectionNamedType(type, "OBJECT"));
        }
        for (String type : introspectionMetaEnumTypes()) {
            result.add(new IntrospectionNamedType(type, "ENUM"));
        }
        return List.copyOf(result);
    }

    private static IntrospectionTypeCost introspectionTypeCost(
            TitanGraphqlModelDocument document,
            String typeName,
            String kind
    ) {
        TitanGraphqlTypeDocument modelType = document.types().stream()
                .filter(candidate -> candidate.name().equals(typeName)).findFirst().orElse(null);
        TitanGraphqlInterfaceDocument interfaceType = interfaceType(document, typeName);
        TitanGraphqlUnionDocument union = unionType(document, typeName);
        String fields = modelType != null ? introspectionScalarFieldDescriptor(document, modelType)
                : interfaceType != null ? introspectionInterfaceFieldDescriptor(document, interfaceType)
                : introspectionMetaObjectFieldDescriptor(typeName);
        if (modelType == null && fields.length() == 0) {
            fields = introspectionDerivedObjectFieldDescriptor(document, typeName);
        }
        int fieldItems = delimiterCount(fields, ';');
        int fieldArguments = introspectionFieldArgumentCount(fields);
        int inputFields = 0;
        if (kind.equals("INPUT_OBJECT")) {
            TitanGraphqlInputObjectDocument authored = inputObjectType(document, typeName);
            inputFields = authored == null
                    ? commaSeparatedItemCount(introspectionInputObjectFieldDescriptor(document, typeName))
                    : authored.fields().size();
        }
        int enumValues = 0;
        if (kind.equals("ENUM")) {
            if (typeName.equals("SortDirection")) enumValues = 2;
            else if (typeName.equals("__TypeKind")) enumValues = 8;
            else if (typeName.equals("__DirectiveLocation")) enumValues = 19;
            else {
                TitanGraphqlEnumDocument enumType = enumType(document, typeName);
                enumValues = enumType == null ? 0 : enumType.values().size();
            }
        }
        int interfaces = typeListItemCount(introspectionInterfacesDescriptor(modelType));
        int possibleTypes = typeListItemCount(
                introspectionPossibleTypesDescriptor(document, interfaceType, union));
        return new IntrospectionTypeCost(
                1, fieldItems, fieldArguments, inputFields, enumValues, interfaces, possibleTypes);
    }

    private static int introspectionFieldArgumentCount(String descriptor) {
        int count = 0;
        int position = 0;
        while (descriptor != null && position < descriptor.length()) {
            int end = descriptor.indexOf(';', position);
            if (end < 0) break;
            int pipe = descriptor.indexOf('|', position);
            if (pipe >= position && pipe < end) {
                count += commaSeparatedItemCount(descriptor.substring(pipe + 1, end));
            }
            position = end + 1;
        }
        return count;
    }

    private static int commaSeparatedItemCount(String value) {
        return value == null || value.length() == 0 ? 0 : delimiterCount(value, ',') + 1;
    }

    private static int typeListItemCount(String descriptor) {
        return descriptor == null || !descriptor.startsWith("tl1;")
                ? 0 : Math.max(0, delimiterCount(descriptor, ';') - 1);
    }

    private static int delimiterCount(String value, char delimiter) {
        int count = 0;
        int index = 0;
        while (value != null && index < value.length()) {
            if (value.charAt(index) == delimiter) count++;
            index++;
        }
        return count;
    }

    private record IntrospectionNamedType(String name, String kind) {
    }

    private record IntrospectionTypeCost(
            int types,
            int fields,
            int fieldArguments,
            int inputFields,
            int enumValues,
            int interfaces,
            int possibleTypes
    ) {
        private IntrospectionTypeCost plus(IntrospectionTypeCost other) {
            return new IntrospectionTypeCost(
                    types + other.types,
                    fields + other.fields,
                    fieldArguments + other.fieldArguments,
                    inputFields + other.inputFields,
                    enumValues + other.enumValues,
                    interfaces + other.interfaces,
                    possibleTypes + other.possibleTypes);
        }
    }

    /**
     * A connection field may recur through a bounded cyclic relation graph.  This static path is
     * deliberately part of generated-local identity only: GraphQL matching and response keys
     * continue to use the model's original field name.
     */
    private static String connectionLocalScope(
            TitanGraphqlRootDocument root,
            String selectionAncestorTypePath
    ) {
        return (selectionAncestorTypePath == null ? "Query" : selectionAncestorTypePath)
                + "|" + root.name() + "Connection";
    }

    private static String scopedLocal(String prefix, String rootName, String name) {
        // Titan lowers Java locals to SQL identifiers. PostgreSQL keeps only the first 63 bytes,
        // so appending arbitrary operation and argument names can make otherwise distinct
        // declarations collide. Keep a readable role prefix and use a stable 64-bit scope digest
        // for the dynamic portion. The same logical scope always receives the same local name.
        String compactPrefix = javaTypeName(prefix);
        if (compactPrefix.length() > 24) {
            compactPrefix = compactPrefix.substring(0, 24);
        }
        // The generated name can occur thousands of times in a large transpiled routine. `_` is
        // not emitted by javaTypeName or the base-36 digest, so it remains an unambiguous scope
        // delimiter while avoiding needless routine-size growth on MySQL.
        return compactPrefix + "_" + scopedLocalDigest(prefix, rootName, name);
    }

    private static String scopedLocalDigest(String prefix, String rootName, String name) {
        String value = prefix + "\u0000" + rootName + "\u0000" + name;
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return Long.toUnsignedString(hash, 36);
    }

    private static String javaTypeName(String value) {
        StringBuilder result = new StringBuilder();
        boolean capitalize = true;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!Character.isLetterOrDigit(current)) {
                capitalize = true;
            } else if (capitalize) {
                result.append(Character.toUpperCase(current));
                capitalize = false;
            } else {
                result.append(current);
            }
        }
        if (result.length() == 0 || Character.isDigit(result.charAt(0))) {
            return "Value" + result;
        }
        return result.toString();
    }

    private static String identifier(String value, String label) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(label + " must be a safe SQL identifier");
        }
        return value;
    }

    private static String javaString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void requireRuntimeIdentity(String identity) {
        if (identity == null || identity.matches("[0-9a-f]{64}") == false) {
            throw new IllegalArgumentException("database runtime identity must be a lowercase SHA-256 value");
        }
    }
}
