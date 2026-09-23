package io.titan.graphql.database;

import java.time.Instant;
import titan.dsl.Text;

/**
 * Shared, database-resident GraphQL request processing.
 *
 * <p>This source set is deliberately free of HTTP, CDI, JSON libraries, and the former JVM
 * GraphQL runtime. Titan transpiles every helper reachable from the generated public entry point.
 * The generated schema binding supplies only schema bindings and constant SQL, while request
 * processing stays here.</p>
 */
public final class DatabaseGraphqlEngine {

    private static final long INVALID_LONG = Long.MIN_VALUE;
    // Both generated dialects compare a deadline as an absolute SQL timestamp.  Keep the
    // trusted value within the common portable range through 9999-12-31T23:59:59.999Z: accepting
    // a larger Java long would make PostgreSQL/MySQL timestamp conversion fail before the engine
    // could return an ordinary GraphQL error.
    private static final long MAX_SUPPORTED_DEADLINE_EPOCH_MILLIS = 253402300799999L;
    public static final String TRUSTED_CONTEXT_VERSION = "titan.graphql.request-context/v1";
    // MySQL's generated public procedure currently uses TEXT parameters.  16 Ki UTF-16 code
    // units remains safely below its 64 KiB UTF-8 limit even for non-ASCII input, so both dialects
    // reach this in-engine guard rather than letting a driver/server parameter limit diverge.
    private static final int MAX_QUERY_CHARACTERS = 16384;
    // Each JSON envelope is independently bounded before the scalar JSON walkers inspect it.
    // Query limits alone do not protect a database routine from a very large variables/extensions
    // document or an unexpectedly oversized trusted-context envelope.
    private static final int MAX_REQUEST_ENVELOPE_CHARACTERS = 16384;
    // The public MySQL procedure currently returns TEXT.  Keep the completed JSON response below
    // the same conservative cross-dialect character budget used for inbound envelopes, rather
    // than allowing a generated routine to assemble an unbounded response in database memory.
    private static final int MAX_RESPONSE_CHARACTERS = 16384;
    // Relation Relay pages use at most 100 materialized edges plus one SQL sentinel. Keep the
    // carrier renderer independently bounded so malformed generated calls cannot turn a private
    // batch carrier into an unbounded response scan.
    private static final int MAX_BATCH_RELATION_ITEMS = 100;
    // Private scalar carrier used to stop response strings growing once an incremental append
    // crosses the public boundary. A raw record separator cannot occur in JSON string output:
    // jsonEscape encodes it, so user data cannot accidentally manufacture this marker.
    private static final String RESPONSE_ASSEMBLY_LIMIT =
            "\u001eTITAN-GRAPHQL-RESPONSE-ASSEMBLY-LIMIT";
    // These bounds cover the scalar work-stack used to walk fragment-expanded selections. They
    // are enforced before generated execution opens any application-data cursor.
    private static final int MAX_FRAGMENT_EXPANSIONS = 512;
    private static final int MAX_FRAGMENT_WORK_CHARACTERS = 262144;
    private static final int MAX_EXECUTION_SELECTION_FIELDS = 512;
    // Bounds the complete selected operation, rather than a single generated selection plan.
    // The largest maintained broad metadata identity request has 120 retained executable fields;
    // the 159-field form that triggered pathological metadata assembly is rejected before any
    // schema dispatch or JDBC application-data work. This remains independent of per-list input
    // coercion and response-character limits.
    private static final int MAX_REQUEST_EXECUTION_FIELD_NODES = 144;
    // JSON envelopes are parsed in the database, including nested variable/input values. Keep
    // the scalar parser stack bounded independently of the GraphQL-token budget.
    private static final int MAX_JSON_NESTING = 128;
    // Typed input coercion traverses descriptor/value pairs with a scalar queue. Bound pair work
    // independently of JSON nesting so a broad but shallow list cannot turn one request into
    // unbounded database string processing.
    private static final int MAX_INPUT_COERCION_ITEMS = 128;
    // Generated root filters are lowered to a fixed database carrier. The small DNF is the
    // public v1alpha1 contract already used by the compiled runtime: at most three OR groups,
    // each containing at most three AND terms. Keeping these bounds here makes the complete
    // Boolean-filter compiler transpilable without request-shaped SQL or recursive routines.
    private static final int MAX_FILTER_PLAN_GROUPS = 3;
    private static final int MAX_FILTER_PLAN_TERMS = 3;
    private static final int MAX_FILTER_PLAN_WORK_ITEMS = 256;
    private static final String FILTER_PLAN_PREFIX = "fp1;";
    private static final String FILTER_PLAN_ERROR_PREFIX = "fe1;";
    // Counts metadata objects emitted by all introspection roots in one selected operation. The
    // generated binding reserves schema-known list fan-out before rendering any inventory item.
    private static final int MAX_INTROSPECTION_ITEMS = 512;
    // After selected-operation preflight, generated bindings consume a request-local carrier of
    // supplied/default variable values instead of repeatedly rescanning the JSON envelope. A
    // request may contain a bounded variables envelope plus defaults in its bounded document, so
    // leave room for both source slices and the carrier's length prefixes.
    private static final int MAX_MATERIALIZED_VARIABLE_CHARACTERS = 65536;
    private static final String MATERIALIZED_VARIABLES_PREFIX = "mv1;";
    private static final String MATERIALIZED_ARGUMENTS_PREFIX = "ma1;";
    private static final String CANONICAL_INPUT_PREFIX = "cv1;";
    private static final int INVALID_SELECTION = -2;
    private static final int INAPPLICABLE_FRAGMENT = -3;
    // Relay cursors must remain byte-for-byte compatible with the existing public JVM codec.
    // The current database connection proof intentionally limits cursor values to signed decimal
    // Longs, but the metadata segments are ordinary GraphQL names and directions. This compact
    // alphabet lets the transpiled engine encode/decode that complete v1 wire shape without a
    // JVM Base64 dependency.
    private static final String BASE64_URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final String PARSE_ERROR = "PARSE_ERROR";
    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    private static final String AUTHORIZATION_ERROR = "AUTHORIZATION_ERROR";
    private static final String UNSUPPORTED_OPERATION = "UNSUPPORTED_OPERATION";
    private static final String EXECUTION_ERROR = "EXECUTION_ERROR";
    private static final String DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED";
    private static final String RESOURCE_LIMIT_ERROR = "RESOURCE_LIMIT_ERROR";
    private static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    // Internal validation helpers return plain diagnostic text on ordinary coercion failures.
    // A work-budget failure needs a distinct scalar carrier so its caller can select the stable
    // RESOURCE_LIMIT_ERROR category without inspecting or coupling to human-readable wording.
    private static final String RESOURCE_LIMIT_FAILURE_PREFIX = "rl1;";
    // Typed-value validators return their charged node count through this private carrier. Their
    // callers aggregate it across the selected operation while keeping the public error contract
    // independent of internal accounting syntax.
    private static final String INPUT_WORK_SUCCESS_PREFIX = "iw1;";
    private static final String INPUT_MATERIALIZATION_SUCCESS_PREFIX = "im1;";

    private DatabaseGraphqlEngine() {
    }

    /**
     * Shared transport-independent request checks. The generated, dialect-specific public entry
     * point invokes this before schema dispatch. {@code trustedContextJson} is constructed by the
     * HTTP adapter after authentication and is never client supplied.
     */
    public static String preflightRequest(
            String query,
            String operationName,
            String variablesJson,
            String extensionsJson,
            String trustedContextJson,
            boolean allowMutations
    ) {
        return preflightRequest(query, operationName, variablesJson, extensionsJson, trustedContextJson,
                allowMutations, "");
    }

    /**
     * Shared transport-independent request checks with a generated, schema-derived input-type
     * descriptor. The descriptor uses comma-delimited GraphQL type names and remains scalar so it
     * can cross the Titan source boundary without a runtime schema object graph.
     */
    public static String preflightRequest(
            String query,
            String operationName,
            String variablesJson,
            String extensionsJson,
            String trustedContextJson,
            boolean allowMutations,
            String allowedInputTypeNames
    ) {
        String documentTokens = documentTokens(query);
        String documentError = preflightDocument(query, documentTokens, variablesJson, extensionsJson,
                trustedContextJson);
        if (documentError.length() != 0) {
            return documentError;
        }
        String selectedOperation = selectedOperationDocument(query, documentTokens, operationName);
        if (selectedOperation.length() == 0) {
            return errorJson("operation selection failed: provide one operation or its exact operation name");
        }
        String languagePlan = documentPlan(selectedOperation);
        return preflightSelectedOperation(selectedOperation, languagePlan, variablesJson, extensionsJson,
                trustedContextJson, allowMutations, allowedInputTypeNames);
    }

    /**
     * Validates request-wide bounds and lexical syntax before operation selection. Generated
     * serving entry points call this once, retain the selected operation and its language plan,
     * then materialize the selected AST. Keeping this stage separate prevents preflight from
     * constructing a second request plan behind the generated executor's back.
     */
    public static String preflightDocument(
            String query,
            String variablesJson,
            String extensionsJson,
            String trustedContextJson
    ) {
        return preflightDocument(query, documentTokens(query), variablesJson, extensionsJson, trustedContextJson);
    }

    /**
     * Performs request-wide preflight from a caller-retained lexical token carrier. Generated
     * entry points retain this carrier for operation selection, so a whole request is lexed once
     * before the selected-operation plan is built.
     */
    public static String preflightDocument(
            String query,
            String documentTokens,
            String variablesJson,
            String extensionsJson,
            String trustedContextJson
    ) {
        if (query == null || query.length() == 0) {
            return errorJson("request field 'query' is required");
        }
        if (query.length() > MAX_QUERY_CHARACTERS) {
            return resourceLimitErrorJson("query exceeds the database engine character budget");
        }
        if (envelopeExceedsCharacterBudget(variablesJson, extensionsJson, trustedContextJson)) {
            return resourceLimitErrorJson("request envelope exceeds the database engine character budget");
        }
        if (documentTokens == null || documentTokens.length() == 0) {
            return parseErrorJsonAt("query contains invalid GraphQL lexical syntax or exceeds the token budget",
                    query, DatabaseGraphqlLanguage.lexicalErrorOffset(query));
        }
        return "";
    }

    /**
     * Completes preflight from the selected operation and the one invocation-local language
     * plan created by a generated serving routine. {@code selectedOperation} must be the result
     * of {@link #selectedOperationDocument(String, String)} after {@link #preflightDocument}
     * succeeds; this method intentionally does not lex or construct another plan.
     */
    public static String preflightSelectedOperation(
            String selectedOperation,
            String languagePlan,
            String variablesJson,
            String extensionsJson,
            String trustedContextJson,
            boolean allowMutations,
            String allowedInputTypeNames
    ) {
        if (languagePlan == null || languagePlan.length() == 0) {
            return parseErrorJsonAt("selected operation has invalid GraphQL lexical syntax or exceeds the token budget",
                    selectedOperation, DatabaseGraphqlLanguage.lexicalErrorOffset(selectedOperation));
        }
        return preflightSelectedOperationFromAst(selectedOperation,
                DatabaseGraphqlAst.parse(selectedOperation, languagePlan), variablesJson, extensionsJson,
                trustedContextJson, allowMutations, allowedInputTypeNames);
    }

    /**
     * AST-aware preflight used by generated serving routines. The selected-operation AST is
     * materialized once by the public routine and must not be rebuilt during variable validation
     * or any preflight semantic check.
     */
    public static String preflightSelectedOperationFromAst(
            String selectedOperation,
            String requestAst,
            String variablesJson,
            String extensionsJson,
            String trustedContextJson,
            boolean allowMutations,
            String allowedInputTypeNames
    ) {
        if (requestAst == null || requestAst.length() == 0) {
            return internalErrorJson("selected operation has invalid typed AST");
        }
        int executionFieldCount = DatabaseGraphqlAst.executableFieldCount(requestAst);
        if (executionFieldCount < 0) {
            return internalErrorJson("selected operation has invalid typed AST");
        }
        if (executionFieldCount > MAX_REQUEST_EXECUTION_FIELD_NODES) {
            return resourceLimitErrorJson("request exceeds the database engine semantic work budget");
        }
        String envelopeError = jsonObjectEnvelopeError(variablesJson, "variables");
        if (envelopeError.length() != 0) {
            return errorJson(envelopeError);
        }
        envelopeError = jsonObjectEnvelopeError(extensionsJson, "extensions");
        if (envelopeError.length() != 0) {
            return errorJson(envelopeError);
        }
        envelopeError = jsonObjectEnvelopeError(trustedContextJson, "trusted context");
        if (envelopeError.length() != 0) {
            return errorJson(envelopeError);
        }
        String variableError = selectedOperationVariableError(selectedOperation, requestAst, variablesJson,
                allowedInputTypeNames);
        if (variableError.length() != 0) {
            boolean resourceLimit = variableError.startsWith(RESOURCE_LIMIT_FAILURE_PREFIX);
            String message = resourceLimit
                    ? variableError.substring(RESOURCE_LIMIT_FAILURE_PREFIX.length()) : variableError;
            int sourceOffset = variableErrorSourceOffset(selectedOperation, requestAst, message);
            return resourceLimit
                    ? codedErrorJsonAt(message, selectedOperation, sourceOffset, RESOURCE_LIMIT_ERROR)
                    : errorJsonAt(message, selectedOperation, sourceOffset);
        }
        int operationDirectiveStart = DatabaseGraphqlAst.operationDirectiveStart(requestAst);
        if (operationDirectiveStart >= 0) {
            return errorJsonAt("directive is not allowed on an operation definition", selectedOperation,
                    operationDirectiveStart);
        }
        int fragmentDefinitionDirectiveStart = DatabaseGraphqlAst.fragmentDefinitionDirectiveStart(requestAst);
        if (fragmentDefinitionDirectiveStart >= 0) {
            return errorJsonAt("directive is not allowed on a fragment definition", selectedOperation,
                    fragmentDefinitionDirectiveStart);
        }
        int invalidExecutableDirectiveStart = selectedOperation.indexOf('@') < 0 ? -1
                : invalidExecutableDirectiveStartFromAst(selectedOperation, requestAst, variablesJson);
        if (invalidExecutableDirectiveStart >= 0) {
            return errorJsonAt("selected operation has an invalid or unsupported directive",
                    selectedOperation, invalidExecutableDirectiveStart);
        }
        // Subscriptions require a durable event transport and a distinct execution contract.  The
        // current proof is request/response only; treating a subscription as a query would be an
        // accidental semantic fallback in the database engine.
        if (DatabaseGraphqlAst.operationKind(requestAst).equals("subscription")) {
            return unsupportedOperationErrorJson("subscription operations are not supported by the database engine yet");
        }
        if (DatabaseGraphqlAst.operationKind(requestAst).equals("mutation") && !allowMutations) {
            return unsupportedOperationErrorJson("mutation operations are not allowed by this transport");
        }
        if (!TRUSTED_CONTEXT_VERSION.equals(trustedContextString(trustedContextJson, "contextVersion"))) {
            return internalErrorJson("trusted context has an unsupported or missing contextVersion");
        }
        long deadlineEpochMillis = trustedContextDeadlineEpochMillis(trustedContextJson);
        if (invalidLong(deadlineEpochMillis)) {
            return internalErrorJson("trusted context has an invalid deadlineEpochMillis");
        }
        if (deadlineEpochMillis != 0L && deadlineExpired(deadlineEpochMillis)) {
            return deadlineExceededErrorJson("request deadline exceeded before database execution");
        }
        return "";
    }

    /**
     * Validates every field use in the selected operation independently of runtime directive
     * inclusion. GraphQL validation applies to the complete selected operation: a field hidden by
     * {@code @skip(if: true)} or {@code @include(if: false)} is not exempt from the schema.
     *
     * <p>The generator supplies an {@code sd1} scalar descriptor containing only executable
     * object fields and their argument names. This routine walks a bounded queue of typed
     * selection sets from the already-retained AST. It deliberately passes {@code null} as the
     * directive-value carrier while expanding each structural selection plan, so all branches are
     * present; normal execution later builds a second plan with materialized variables and omits
     * disabled fields. No application-data statement is opened here.</p>
     */
    public static String validateSelectedOperationSchemaFromAst(
            String query,
            String requestAst,
            String schemaDescriptor
    ) {
        if (query == null || requestAst == null || requestAst.length() == 0
                || schemaDescriptor == null || !schemaDescriptor.startsWith("sd1;")) {
            return internalErrorJson("selected operation schema validation descriptor is invalid");
        }
        int operationSelection = DatabaseGraphqlAst.operationSelectionStart(requestAst);
        String operationKind = DatabaseGraphqlAst.operationKind(requestAst);
        String rootType = operationKind.equals("mutation") ? "Mutation" : "Query";
        if (operationSelection < 0 || schemaTypeFields(schemaDescriptor, rootType).length() == 0) {
            return internalErrorJson("selected operation cannot be validated against the installed schema");
        }
        String typeConditionError = validateSchemaTypeConditions(query, requestAst, schemaDescriptor);
        if (typeConditionError.length() != 0) {
            return typeConditionError;
        }
        // The generated introspection interpreter already validates its complete selection before
        // rendering. With no executable directive anywhere in the source, there is no disabled
        // branch for this additional structural pass to recover. Avoid walking the large standard
        // identity document twice; ordinary/model roots and every directive-bearing request still
        // take the generic validator below.
        if (operationKind.equals("query") && query.indexOf('@') < 0
                && operationHasOnlyIntrospectionRoots(query, requestAst, operationSelection)) {
            return "";
        }

        String work = schemaValidationWorkItem(operationSelection, rootType);
        int workPosition = 0;
        int validatedFields = 0;
        while (workPosition < work.length()) {
            int selectionEnd = work.indexOf(':', workPosition);
            int selectionStart = inputDescriptorDecimal(work, workPosition, selectionEnd);
            int typeLengthEnd = selectionEnd < 0 ? -1 : work.indexOf(':', selectionEnd + 1);
            int typeLength = inputDescriptorDecimal(work, selectionEnd + 1, typeLengthEnd);
            int typeStart = typeLengthEnd < 0 ? -1 : typeLengthEnd + 1;
            int typeEnd = typeStart < 0 || typeLength < 0 ? -1 : typeStart + typeLength;
            int conditionEnd = typeEnd < 0 || typeEnd >= work.length() || work.charAt(typeEnd) != ':'
                    ? -1 : work.indexOf(';', typeEnd + 1);
            int validateConditions = inputDescriptorDecimal(work, typeEnd + 1, conditionEnd);
            if (selectionStart < 0 || typeEnd < typeStart || conditionEnd != typeEnd + 2
                    || (validateConditions != 0 && validateConditions != 1)) {
                return internalErrorJson("selected operation schema-validation work queue is invalid");
            }
            workPosition = conditionEnd + 1;
            String parentType = work.substring(typeStart, typeEnd);
            String typeFields = schemaTypeFields(schemaDescriptor, parentType);
            if (typeFields.length() == 0) {
                return errorJsonAt("selected operation references an unsupported composite type '"
                        + parentType + "'", query, selectionStart);
            }
            if (validateConditions == 1) {
                String fragmentTypeError = validateSelectionFragmentTypeOverlap(
                        query, requestAst, selectionStart, parentType, schemaDescriptor);
                if (fragmentTypeError.length() != 0) {
                    return fragmentTypeError;
                }
            }
            String runtimeTypes = schemaRuntimeTypeConditions(schemaDescriptor, parentType);
            String plan = selectionPlanFromAst(query, requestAst, selectionStart, null, runtimeTypes, "");
            int fieldCount = selectionPlanCount(plan);
            boolean abstractParent = schemaTypeIsAbstract(schemaDescriptor, parentType);
            if (fieldCount < 0 || fieldCount == 0 && !abstractParent) {
                return errorJsonAt("selection for type '" + parentType
                        + "' is invalid or empty", query, selectionStart);
            }
            if (fieldCount > 0 && selectionPlanHasDuplicateResponseKeyFromAst(query, requestAst, plan)) {
                return errorJsonAt("selection for type '" + parentType
                        + "' has conflicting fields for one response key", query, selectionStart);
            }
            int fieldIndex = 0;
            while (fieldIndex < fieldCount) {
                if (validatedFields >= MAX_EXECUTION_SELECTION_FIELDS) {
                    return resourceLimitErrorJson("selected operation exceeds the schema-validation work budget");
                }
                validatedFields++;
                int fieldStart = selectionPlanFieldStart(plan, fieldIndex);
                String selectedFieldInfo = DatabaseGraphqlAst.fieldInfo(requestAst, fieldStart);
                int fieldHeaderEnd = astInfoValue(selectedFieldInfo, 0);
                int childSelection = astInfoValue(selectedFieldInfo, 1);
                int fieldNameStart = astInfoValue(selectedFieldInfo, 4);
                int fieldNameEnd = astInfoValue(selectedFieldInfo, 5);
                if (fieldStart < 0 || fieldHeaderEnd < fieldStart || fieldNameStart < fieldStart
                        || fieldNameEnd <= fieldNameStart || fieldNameEnd > query.length()) {
                    return internalErrorJson("selected operation contains an invalid field node");
                }
                String fieldName = query.substring(fieldNameStart, fieldNameEnd);
                boolean hasProvidedArguments = fieldHasArguments(
                        query, fieldNameEnd, fieldHeaderEnd);
                if (fieldName.equals("__typename")) {
                    if (hasProvidedArguments) {
                        return errorJsonAt("field '" + parentType + ".__typename' has an argument",
                                query, fieldStart);
                    }
                    if (childSelection >= 0) {
                        return errorJsonAt("field '" + parentType
                                + ".__typename' cannot have a selection", query, fieldStart);
                    }
                    fieldIndex++;
                    continue;
                }

                String fieldDescriptor = schemaFieldDescriptor(typeFields, fieldName);
                if (fieldDescriptor.length() == 0) {
                    return errorJsonAt("field '" + parentType + "." + fieldName
                            + "' is not defined by the installed schema", query, fieldStart);
                }
                String arguments = schemaFieldArguments(fieldDescriptor);
                String allowedArguments = schemaArgumentNames(arguments);
                if (allowedArguments.length() == 0 && arguments.length() != 0) {
                    return internalErrorJson("installed schema contains an invalid argument descriptor");
                }
                if (hasProvidedArguments
                        && !fieldHasOnlyArgumentsFromAst(query, requestAst, fieldStart, allowedArguments)) {
                    return errorJsonAt("field '" + parentType + "." + fieldName
                            + "' has an unknown, duplicate, or malformed argument", query, fieldStart);
                }
                String missingArgument = missingRequiredSchemaArgument(
                        query, requestAst, fieldStart, arguments, hasProvidedArguments);
                if (missingArgument.length() != 0) {
                    return errorJsonAt("field '" + parentType + "." + fieldName
                            + "' requires argument '" + missingArgument + "'", query, fieldStart);
                }

                String returnType = schemaFieldReturnType(fieldDescriptor);
                String fieldKind = schemaFieldKind(fieldDescriptor);
                boolean composite = schemaFieldKindIsComposite(fieldKind);
                if (composite) {
                    String childType = DatabaseGraphqlTypeReference.namedType(
                            DatabaseGraphqlTypeReference.parse(returnType));
                    if (childSelection < 0 || childType.length() == 0) {
                        return errorJsonAt("field '" + parentType + "." + fieldName
                                + "' requires a selection", query, fieldStart);
                    }
                    work = appendSchemaValidationChildWork(
                            work, childSelection, childType, fieldKind, schemaDescriptor);
                    if (work.length() > MAX_FRAGMENT_WORK_CHARACTERS) {
                        return resourceLimitErrorJson("selected operation exceeds the schema-validation work budget");
                    }
                } else if (childSelection >= 0) {
                    return errorJsonAt("field '" + parentType + "." + fieldName
                            + "' cannot have a selection", query, fieldStart);
                }
                fieldIndex++;
            }
        }
        return "";
    }

    /**
     * Validates and coerces every supplied field-argument value after operation variables have
     * been materialized, but before directive inclusion or schema dispatch. The preceding
     * structural schema pass has already established field/argument membership; this pass owns
     * the type semantics for literals and variable references, including references nested in
     * list and input-object literals.
     */
    public static String materializedSelectedOperationArgumentValuesFromAst(
            String query,
            String requestAst,
            String materializedVariables,
            String schemaDescriptor,
            String inputDescriptor
    ) {
        if (query == null || requestAst == null || requestAst.length() == 0
                || materializedVariables == null
                || !materializedVariables.startsWith(MATERIALIZED_VARIABLES_PREFIX)
                || schemaDescriptor == null || !schemaDescriptor.startsWith("sd1;")
                || !inputDescriptorIsTyped(inputDescriptor)) {
            return internalErrorJson("selected operation argument-materialization descriptor is invalid");
        }
        int operationSelection = DatabaseGraphqlAst.operationSelectionStart(requestAst);
        String operationKind = DatabaseGraphqlAst.operationKind(requestAst);
        String rootType = operationKind.equals("mutation") ? "Mutation" : "Query";
        if (operationSelection < 0 || schemaTypeFields(schemaDescriptor, rootType).length() == 0) {
            return internalErrorJson("selected operation arguments cannot be materialized against the installed schema");
        }
        // The generated introspection renderer performs the same typed argument checks. Avoid a
        // second full walk of large directive-free metadata queries; any potentially disabled
        // branch contains '@' and therefore takes the generic pass below.
        if (operationKind.equals("query") && query.indexOf('@') < 0
                && operationHasOnlyIntrospectionRoots(query, requestAst, operationSelection)) {
            return MATERIALIZED_ARGUMENTS_PREFIX;
        }

        String materializedArguments = MATERIALIZED_ARGUMENTS_PREFIX;
        String work = schemaValidationWorkItem(operationSelection, rootType);
        int workPosition = 0;
        int validatedFields = 0;
        int inputWorkItems = 0;
        int materializationWorkItems = 0;
        while (workPosition < work.length()) {
            int selectionEnd = work.indexOf(':', workPosition);
            int selectionStart = inputDescriptorDecimal(work, workPosition, selectionEnd);
            int typeLengthEnd = selectionEnd < 0 ? -1 : work.indexOf(':', selectionEnd + 1);
            int typeLength = inputDescriptorDecimal(work, selectionEnd + 1, typeLengthEnd);
            int typeStart = typeLengthEnd < 0 ? -1 : typeLengthEnd + 1;
            int typeEnd = typeStart < 0 || typeLength < 0 ? -1 : typeStart + typeLength;
            int conditionEnd = typeEnd < 0 || typeEnd >= work.length() || work.charAt(typeEnd) != ':'
                    ? -1 : work.indexOf(';', typeEnd + 1);
            int validateConditions = inputDescriptorDecimal(work, typeEnd + 1, conditionEnd);
            if (selectionStart < 0 || typeEnd < typeStart || conditionEnd != typeEnd + 2
                    || (validateConditions != 0 && validateConditions != 1)) {
                return internalErrorJson("selected operation argument-validation work queue is invalid");
            }
            workPosition = conditionEnd + 1;
            String parentType = work.substring(typeStart, typeEnd);
            String typeFields = schemaTypeFields(schemaDescriptor, parentType);
            String runtimeTypes = schemaRuntimeTypeConditions(schemaDescriptor, parentType);
            String plan = selectionPlanFromAst(query, requestAst, selectionStart, null, runtimeTypes, "");
            int fieldCount = selectionPlanCount(plan);
            boolean abstractParent = schemaTypeIsAbstract(schemaDescriptor, parentType);
            if (typeFields.length() == 0 || fieldCount < 0 || fieldCount == 0 && !abstractParent) {
                return errorJsonAt("selection for type '" + parentType
                        + "' cannot be coerced", query, selectionStart);
            }
            int fieldIndex = 0;
            while (fieldIndex < fieldCount) {
                if (validatedFields >= MAX_EXECUTION_SELECTION_FIELDS) {
                    return resourceLimitErrorJson("selected operation exceeds the argument-validation work budget");
                }
                validatedFields++;
                int fieldStart = selectionPlanFieldStart(plan, fieldIndex);
                String selectedFieldInfo = DatabaseGraphqlAst.fieldInfo(requestAst, fieldStart);
                int childSelection = astInfoValue(selectedFieldInfo, 1);
                int fieldNameStart = astInfoValue(selectedFieldInfo, 4);
                int fieldNameEnd = astInfoValue(selectedFieldInfo, 5);
                if (fieldStart < 0 || fieldNameStart < fieldStart || fieldNameEnd <= fieldNameStart
                        || fieldNameEnd > query.length()) {
                    return internalErrorJson("selected operation contains an invalid argument owner");
                }
                String fieldName = query.substring(fieldNameStart, fieldNameEnd);
                if (!fieldName.equals("__typename")) {
                    String fieldDescriptor = schemaFieldDescriptor(typeFields, fieldName);
                    String arguments = schemaFieldArguments(fieldDescriptor);
                    int argumentCount = DatabaseGraphqlAst.fieldArgumentCount(requestAst, fieldStart);
                    if (fieldDescriptor.length() == 0 || argumentCount < 0) {
                        return errorJsonAt("field '" + parentType + "." + fieldName
                                + "' has invalid argument metadata", query, fieldStart);
                    }
                    int argumentIndex = 0;
                    while (argumentIndex < argumentCount) {
                        String argumentInfo = DatabaseGraphqlAst.fieldArgumentInfo(
                                requestAst, fieldStart, argumentIndex);
                        int argumentNameStart = astInfoValue(argumentInfo, 0);
                        int argumentNameEnd = astInfoValue(argumentInfo, 1);
                        int argumentValueStart = astInfoValue(argumentInfo, 2);
                        int argumentValueEnd = astInfoValue(argumentInfo, 3);
                        if (argumentNameStart < 0 || argumentNameEnd <= argumentNameStart
                                || argumentValueStart < 0 || argumentValueEnd <= argumentValueStart
                                || argumentNameEnd > query.length() || argumentValueEnd > query.length()) {
                            return errorJsonAt("field '" + parentType + "." + fieldName
                                    + "' has an invalid argument value", query, fieldStart);
                        }
                        String argumentName = query.substring(argumentNameStart, argumentNameEnd);
                        String argumentDescriptor = schemaArgumentDescriptor(arguments, argumentName);
                        String argumentType = schemaArgumentType(argumentDescriptor);
                        String argumentLabel = "argument '" + parentType + "." + fieldName
                                + "." + argumentName + "'";
                        String argumentValue = query.substring(argumentValueStart, argumentValueEnd);
                        String valueResult = argumentInputValueResult(
                                query, requestAst, materializedVariables, argumentLabel, argumentType,
                                argumentValue, inputDescriptor,
                                schemaArgumentHasDefault(argumentDescriptor),
                                MAX_INPUT_COERCION_ITEMS - inputWorkItems);
                        int valueWorkItems = valueResult.startsWith(INPUT_WORK_SUCCESS_PREFIX)
                                ? inputDescriptorDecimal(valueResult, INPUT_WORK_SUCCESS_PREFIX.length(),
                                valueResult.length()) : -1;
                        if (argumentDescriptor.length() == 0 || argumentType.length() == 0
                                || valueWorkItems < 0) {
                            String failure = valueResult.length() == 0
                                    ? argumentLabel + " has invalid schema metadata" : valueResult;
                            boolean resourceLimit = failure.startsWith(RESOURCE_LIMIT_FAILURE_PREFIX);
                            String message = resourceLimit
                                    ? failure.substring(RESOURCE_LIMIT_FAILURE_PREFIX.length()) : failure;
                            return resourceLimit
                                    ? codedErrorJsonAt(message, query, argumentValueStart, RESOURCE_LIMIT_ERROR)
                                    : errorJsonAt(message, query, argumentValueStart);
                        }
                        inputWorkItems += valueWorkItems;
                        String materializationResult = materializedInputValue(
                                argumentValue, argumentType, materializedVariables, inputDescriptor,
                                schemaArgumentDefaultValue(argumentDescriptor),
                                MAX_INPUT_COERCION_ITEMS - materializationWorkItems);
                        int materializationCountEnd = materializationResult.startsWith(
                                INPUT_MATERIALIZATION_SUCCESS_PREFIX)
                                ? materializationResult.indexOf(
                                ':', INPUT_MATERIALIZATION_SUCCESS_PREFIX.length()) : -1;
                        int materializationCount = inputDescriptorDecimal(
                                materializationResult, INPUT_MATERIALIZATION_SUCCESS_PREFIX.length(),
                                materializationCountEnd);
                        if (materializationCount < 0) {
                            if (materializationResult.startsWith(RESOURCE_LIMIT_FAILURE_PREFIX)) {
                                return codedErrorJsonAt(materializationResult.substring(
                                                RESOURCE_LIMIT_FAILURE_PREFIX.length()), query,
                                        argumentValueStart, RESOURCE_LIMIT_ERROR);
                            }
                            return internalErrorJsonAt(argumentLabel
                                    + " could not be materialized", query, argumentValueStart);
                        }
                        materializationWorkItems += materializationCount;
                        String materializedValue = materializationResult.substring(
                                materializationCountEnd + 1);
                        if (materializedValue.length() != 0) {
                            String entry = fieldStart + ":" + argumentName.length() + ":" + argumentName
                                    + materializedValue.length() + ":" + materializedValue + ";";
                            if (materializedArguments.length() + entry.length()
                                    > MAX_MATERIALIZED_VARIABLE_CHARACTERS) {
                                return resourceLimitErrorJson("selected operation exceeds the argument-materialization budget");
                            }
                            materializedArguments = materializedArguments + entry;
                        }
                        argumentIndex++;
                    }

                    // A field-location default applies when the argument itself is absent. A
                    // supplied variable whose runtime value is omitted was handled above because
                    // its AST argument node must still participate in variable-usage validation.
                    int defaultPosition = 0;
                    while (defaultPosition < arguments.length()) {
                        int defaultEnd = arguments.indexOf(',', defaultPosition);
                        if (defaultEnd < 0) defaultEnd = arguments.length();
                        String defaultArgumentDescriptor = arguments.substring(defaultPosition, defaultEnd);
                        int nameEnd = defaultArgumentDescriptor.indexOf('=');
                        String defaultArgumentName = nameEnd <= 0
                                ? "" : defaultArgumentDescriptor.substring(0, nameEnd);
                        if (defaultArgumentName.length() == 0) {
                            return errorJsonAt("field '" + parentType + "." + fieldName
                                    + "' has invalid default metadata", query, fieldStart);
                        }
                        if (schemaArgumentHasDefault(defaultArgumentDescriptor)
                                && argumentLiteralFromAst(query, requestAst, fieldStart,
                                defaultArgumentName).length() == 0) {
                            String defaultArgumentType = schemaArgumentType(defaultArgumentDescriptor);
                            String defaultArgumentValue = schemaArgumentDefaultValue(defaultArgumentDescriptor);
                            String defaultArgumentLabel = "argument '" + parentType + "." + fieldName
                                    + "." + defaultArgumentName + "'";
                            String defaultResult = argumentInputValueResult(
                                    query, requestAst, materializedVariables, defaultArgumentLabel,
                                    defaultArgumentType, defaultArgumentValue, inputDescriptor, false,
                                    MAX_INPUT_COERCION_ITEMS - inputWorkItems);
                            int defaultWorkItems = defaultResult.startsWith(INPUT_WORK_SUCCESS_PREFIX)
                                    ? inputDescriptorDecimal(defaultResult, INPUT_WORK_SUCCESS_PREFIX.length(),
                                    defaultResult.length()) : -1;
                            String materializationResult = defaultWorkItems >= 0
                                    ? materializedInputValue(defaultArgumentValue, defaultArgumentType,
                                    materializedVariables, inputDescriptor, "",
                                    MAX_INPUT_COERCION_ITEMS - materializationWorkItems) : "";
                            int materializationCountEnd = materializationResult.startsWith(
                                    INPUT_MATERIALIZATION_SUCCESS_PREFIX)
                                    ? materializationResult.indexOf(
                                    ':', INPUT_MATERIALIZATION_SUCCESS_PREFIX.length()) : -1;
                            int materializationCount = inputDescriptorDecimal(
                                    materializationResult, INPUT_MATERIALIZATION_SUCCESS_PREFIX.length(),
                                    materializationCountEnd);
                            String materializedDefault = materializationCount >= 0
                                    ? materializationResult.substring(materializationCountEnd + 1) : "";
                            if (defaultArgumentType.length() == 0 || defaultArgumentValue.length() == 0
                                    || defaultWorkItems < 0) {
                                String failure = defaultResult.length() == 0
                                        ? defaultArgumentLabel + " has invalid schema default metadata"
                                        : defaultResult;
                                boolean resourceLimit = failure.startsWith(RESOURCE_LIMIT_FAILURE_PREFIX);
                                String message = resourceLimit
                                        ? failure.substring(RESOURCE_LIMIT_FAILURE_PREFIX.length()) : failure;
                                return resourceLimit
                                        ? codedErrorJsonAt(message, query, fieldStart, RESOURCE_LIMIT_ERROR)
                                        : errorJsonAt(message, query, fieldStart);
                            }
                            if (materializationCount < 0) {
                                if (materializationResult.startsWith(RESOURCE_LIMIT_FAILURE_PREFIX)) {
                                    return codedErrorJsonAt(materializationResult.substring(
                                                    RESOURCE_LIMIT_FAILURE_PREFIX.length()), query,
                                            fieldStart, RESOURCE_LIMIT_ERROR);
                                }
                                return internalErrorJsonAt(defaultArgumentLabel
                                        + " default could not be materialized", query, fieldStart);
                            }
                            if (materializedDefault.length() == 0) {
                                return internalErrorJsonAt(defaultArgumentLabel
                                        + " has invalid schema default metadata", query, fieldStart);
                            }
                            inputWorkItems += defaultWorkItems;
                            materializationWorkItems += materializationCount;
                            String entry = fieldStart + ":" + defaultArgumentName.length() + ":"
                                    + defaultArgumentName + materializedDefault.length() + ":"
                                    + materializedDefault + ";";
                            if (materializedArguments.length() + entry.length()
                                    > MAX_MATERIALIZED_VARIABLE_CHARACTERS) {
                                return resourceLimitErrorJson("selected operation exceeds the argument-materialization budget");
                            }
                            materializedArguments = materializedArguments + entry;
                        }
                        defaultPosition = defaultEnd + 1;
                    }

                    String fieldKind = schemaFieldKind(fieldDescriptor);
                    if (schemaFieldKindIsComposite(fieldKind)) {
                        String childType = DatabaseGraphqlTypeReference.namedType(
                                DatabaseGraphqlTypeReference.parse(schemaFieldReturnType(fieldDescriptor)));
                        if (childSelection < 0 || childType.length() == 0) {
                            return errorJsonAt("field '" + parentType + "." + fieldName
                                    + "' has invalid composite metadata", query, fieldStart);
                        }
                        work = appendSchemaValidationChildWork(
                                work, childSelection, childType, fieldKind, schemaDescriptor);
                        if (work.length() > MAX_FRAGMENT_WORK_CHARACTERS) {
                            return resourceLimitErrorJson("selected operation exceeds the argument-validation work budget");
                        }
                    }
                }
                fieldIndex++;
            }
        }
        return materializedArguments;
    }

    private static boolean operationHasOnlyIntrospectionRoots(
            String query,
            String requestAst,
            int operationSelection
    ) {
        String plan = selectionPlanFromAst(query, requestAst, operationSelection, null, "Query", "");
        int count = selectionPlanCount(plan);
        if (count <= 0) {
            return false;
        }
        int index = 0;
        while (index < count) {
            int fieldStart = selectionPlanFieldStart(plan, index);
            String name = fieldName(query, requestAst, fieldStart);
            if (!name.equals("__schema") && !name.equals("__type") && !name.equals("__typename")) {
                return false;
            }
            index++;
        }
        return true;
    }

    private static String schemaValidationWorkItem(int selectionStart, String typeName) {
        return schemaValidationWorkItem(selectionStart, typeName, true);
    }

    private static String schemaValidationWorkItem(
            int selectionStart,
            String typeName,
            boolean validateFragmentConditions
    ) {
        if (selectionStart < 0 || typeName == null || typeName.length() == 0) {
            return "";
        }
        return selectionStart + ":" + typeName.length() + ":" + typeName + ":"
                + (validateFragmentConditions ? "1" : "0") + ";";
    }

    private static boolean schemaFieldKindIsComposite(String kind) {
        return kind != null && kind.length() != 0
                && (kind.charAt(kind.length() - 1) == 'O'
                || kind.charAt(kind.length() - 1) == 'T'
                || kind.charAt(kind.length() - 1) == 'U');
    }

    private static boolean schemaFieldKindIsAbstract(String kind) {
        return kind != null && kind.length() != 0
                && (kind.charAt(kind.length() - 1) == 'T'
                || kind.charAt(kind.length() - 1) == 'U');
    }

    /** Adds the declared composite and each concrete possible type for abstract output fields. */
    private static String appendSchemaValidationChildWork(
            String work,
            int selectionStart,
            String childType,
            String fieldKind,
            String schemaDescriptor
    ) {
        String result = work + schemaValidationWorkItem(selectionStart, childType);
        if (!schemaFieldKindIsAbstract(fieldKind)) {
            return result;
        }
        String possibleTypes = schemaAbstractPossibleTypes(schemaDescriptor, childType);
        int position = 0;
        while (position < possibleTypes.length()) {
            int end = possibleTypes.indexOf(',', position);
            if (end < 0) end = possibleTypes.length();
            if (end > position) {
                result = result + schemaValidationWorkItem(
                        selectionStart, possibleTypes.substring(position, end), false);
            }
            position = end + 1;
        }
        return result;
    }

    private static boolean schemaTypeIsAbstract(String descriptor, String typeName) {
        return schemaTypeFields(descriptor, "@" + typeName).length() != 0;
    }

    private static String schemaAbstractPossibleTypes(String descriptor, String typeName) {
        String possible = schemaTypeFields(descriptor, "@" + typeName);
        return possible.equals("-") ? "" : possible;
    }

    private static String schemaRuntimeTypeConditions(String descriptor, String typeName) {
        String conditions = schemaTypeFields(descriptor, "#" + typeName);
        return conditions.startsWith("|") && conditions.endsWith("|") ? conditions : typeName;
    }

    /** Every retained fragment condition must name a composite type in the installed schema. */
    private static String validateSchemaTypeConditions(
            String query,
            String requestAst,
            String schemaDescriptor
    ) {
        int count = DatabaseGraphqlAst.typeConditionCount(requestAst);
        if (count < 0) {
            return internalErrorJson("selected operation has invalid fragment type-condition metadata");
        }
        int index = 0;
        while (index < count) {
            String info = DatabaseGraphqlAst.typeConditionInfo(requestAst, index);
            int nodeStart = astInfoValue(info, 0);
            int typeStart = astInfoValue(info, 1);
            int typeEnd = astInfoValue(info, 2);
            if (nodeStart < 0 || typeStart < 0 || typeEnd <= typeStart || typeEnd > query.length()) {
                return internalErrorJson("selected operation has invalid fragment type-condition metadata");
            }
            String typeName = query.substring(typeStart, typeEnd);
            if (schemaTypeFields(schemaDescriptor, typeName).length() == 0) {
                return errorJsonAt("fragment references unknown or non-composite type '"
                        + typeName + "'", query, nodeStart);
            }
            index++;
        }
        return "";
    }

    /** Validates fragment-spread overlap while preserving the declared (not runtime) parent type. */
    private static String validateSelectionFragmentTypeOverlap(
            String query,
            String requestAst,
            int selectionStart,
            String parentType,
            String schemaDescriptor
    ) {
        String pending = schemaFragmentWorkItem(selectionStart, parentType, "|");
        int expansions = 0;
        while (pending.length() != 0) {
            int selectionEnd = pending.indexOf(':');
            int currentSelection = inputDescriptorDecimal(pending, 0, selectionEnd);
            int typeLengthEnd = selectionEnd < 0 ? -1 : pending.indexOf(':', selectionEnd + 1);
            int typeLength = inputDescriptorDecimal(pending, selectionEnd + 1, typeLengthEnd);
            int typeStart = typeLengthEnd < 0 ? -1 : typeLengthEnd + 1;
            int typeEnd = typeStart < 0 || typeLength < 0 ? -1 : typeStart + typeLength;
            int pathLengthEnd = typeEnd < 0 || typeEnd >= pending.length()
                    || pending.charAt(typeEnd) != ':' ? -1 : pending.indexOf(':', typeEnd + 1);
            int pathLength = inputDescriptorDecimal(pending, typeEnd + 1, pathLengthEnd);
            int pathStart = pathLengthEnd < 0 ? -1 : pathLengthEnd + 1;
            int pathEnd = pathStart < 0 || pathLength < 0 ? -1 : pathStart + pathLength;
            if (currentSelection < 0 || typeEnd < typeStart || pathEnd < pathStart
                    || pathEnd >= pending.length() || pending.charAt(pathEnd) != ';') {
                return internalErrorJson("selected operation fragment-overlap work queue is invalid");
            }
            String currentType = pending.substring(typeStart, typeEnd);
            String path = pending.substring(pathStart, pathEnd);
            pending = pending.substring(pathEnd + 1);
            int itemCount = DatabaseGraphqlAst.selectionItemCount(requestAst, currentSelection);
            if (itemCount < 0) {
                return errorJsonAt("fragment selection is invalid", query, currentSelection);
            }
            int itemIndex = 0;
            while (itemIndex < itemCount) {
                String itemInfo = DatabaseGraphqlAst.selectionItemInfo(
                        requestAst, currentSelection, itemIndex);
                int nodeStart = DatabaseGraphqlLanguage.planInfoValue(itemInfo, 0);
                int kindStart = itemInfo.indexOf(':');
                int payloadStart = kindStart < 0 ? -1 : itemInfo.indexOf(':', kindStart + 1);
                String kind = payloadStart < 0 ? "" : itemInfo.substring(kindStart + 1, payloadStart);
                String payload = payloadStart < 0 ? "" : itemInfo.substring(payloadStart + 1);
                if (nodeStart < 0 || kind.length() != 1) {
                    return errorJsonAt("fragment selection is invalid", query, currentSelection);
                }
                if (kind.equals("P")) {
                    int nameStart = astInfoValue(payload, 0);
                    int nameEnd = astInfoValue(payload, 1);
                    if (nameStart < 0 || nameEnd <= nameStart || nameEnd > query.length()) {
                        return errorJsonAt("named fragment spread is invalid", query, nodeStart);
                    }
                    String fragmentName = query.substring(nameStart, nameEnd);
                    if (fragmentPathContains(path, fragmentName)) {
                        return errorJsonAt("fragment cycle is not supported", query, nodeStart);
                    }
                    String fragment = DatabaseGraphqlAst.fragmentDefinitionInfo(
                            query, requestAst, fragmentName);
                    int conditionStart = astInfoValue(fragment, 0);
                    int conditionEnd = astInfoValue(fragment, 1);
                    int nestedSelection = astInfoValue(fragment, 3);
                    if (conditionStart < 0 || conditionEnd <= conditionStart
                            || conditionEnd > query.length() || nestedSelection < 0) {
                        return errorJsonAt("named fragment definition is invalid", query, nodeStart);
                    }
                    String condition = query.substring(conditionStart, conditionEnd);
                    if (!schemaTypesOverlap(schemaDescriptor, currentType, condition)) {
                        return errorJsonAt("fragment type condition '" + condition
                                + "' cannot apply to parent type '" + currentType + "'", query, nodeStart);
                    }
                    pending = pending + schemaFragmentWorkItem(
                            nestedSelection, condition, path + fragmentName + "|");
                    expansions++;
                } else if (kind.equals("I")) {
                    int conditionStart = astInfoValue(payload, 0);
                    int conditionEnd = astInfoValue(payload, 1);
                    int nestedSelection = astInfoValue(payload, 3);
                    if ((conditionStart < 0) != (conditionEnd < 0) || nestedSelection < 0
                            || conditionEnd > query.length()) {
                        return errorJsonAt("inline fragment is invalid", query, nodeStart);
                    }
                    String condition = conditionStart < 0
                            ? currentType : query.substring(conditionStart, conditionEnd);
                    if (!schemaTypesOverlap(schemaDescriptor, currentType, condition)) {
                        return errorJsonAt("fragment type condition '" + condition
                                + "' cannot apply to parent type '" + currentType + "'", query, nodeStart);
                    }
                    pending = pending + schemaFragmentWorkItem(nestedSelection, condition, path);
                    expansions++;
                } else if (!kind.equals("F")) {
                    return errorJsonAt("fragment selection is invalid", query, nodeStart);
                }
                if (expansions > MAX_FRAGMENT_EXPANSIONS || pending.length() > MAX_FRAGMENT_WORK_CHARACTERS) {
                    return resourceLimitErrorJson("selected operation exceeds the fragment-overlap work budget");
                }
                itemIndex++;
            }
        }
        return "";
    }

    private static String schemaFragmentWorkItem(int selectionStart, String typeName, String path) {
        if (selectionStart < 0 || typeName == null || typeName.length() == 0
                || path == null || path.length() == 0) {
            return "";
        }
        return selectionStart + ":" + typeName.length() + ":" + typeName + ":"
                + path.length() + ":" + path + ";";
    }

    private static boolean schemaTypesOverlap(String schemaDescriptor, String left, String right) {
        if (left == null || right == null || left.length() == 0 || right.length() == 0) return false;
        if (left.equals(right)) return true;
        String leftPossible = schemaPossibleTypeSet(schemaDescriptor, left);
        String rightPossible = schemaPossibleTypeSet(schemaDescriptor, right);
        int position = 0;
        while (position < leftPossible.length()) {
            int end = leftPossible.indexOf(',', position);
            if (end < 0) end = leftPossible.length();
            if (end > position && schemaCommaSetContains(
                    rightPossible, leftPossible.substring(position, end))) {
                return true;
            }
            position = end + 1;
        }
        return false;
    }

    private static String schemaPossibleTypeSet(String schemaDescriptor, String typeName) {
        String possible = schemaAbstractPossibleTypes(schemaDescriptor, typeName);
        return schemaTypeIsAbstract(schemaDescriptor, typeName) ? possible : typeName;
    }

    private static boolean schemaCommaSetContains(String values, String expected) {
        return values != null && expected != null && expected.length() != 0
                && (values.equals(expected) || values.startsWith(expected + ",")
                || values.endsWith("," + expected) || values.indexOf("," + expected + ",") >= 0);
    }

    /** Returns one type's semicolon-delimited field descriptor from an {@code sd1} carrier. */
    private static String schemaTypeFields(String descriptor, String expectedType) {
        if (descriptor == null || !descriptor.startsWith("sd1;")
                || expectedType == null || expectedType.length() == 0) {
            return "";
        }
        int position = 4;
        while (position < descriptor.length()) {
            int typeLengthEnd = descriptor.indexOf(':', position);
            int typeLength = inputDescriptorDecimal(descriptor, position, typeLengthEnd);
            int typeStart = typeLengthEnd < 0 ? -1 : typeLengthEnd + 1;
            int typeEnd = typeStart < 0 || typeLength < 0 ? -1 : typeStart + typeLength;
            int fieldsLengthEnd = typeEnd < 0 || typeEnd > descriptor.length()
                    ? -1 : descriptor.indexOf(':', typeEnd);
            int fieldsLength = inputDescriptorDecimal(descriptor, typeEnd, fieldsLengthEnd);
            int fieldsStart = fieldsLengthEnd < 0 ? -1 : fieldsLengthEnd + 1;
            int fieldsEnd = fieldsStart < 0 || fieldsLength < 0 ? -1 : fieldsStart + fieldsLength;
            if (typeEnd < typeStart || fieldsEnd < fieldsStart || fieldsEnd > descriptor.length()) {
                return "";
            }
            if (descriptor.substring(typeStart, typeEnd).equals(expectedType)) {
                return descriptor.substring(fieldsStart, fieldsEnd);
            }
            position = fieldsEnd;
        }
        return "";
    }

    /** Finds a named field entry within one generated type descriptor. */
    private static String schemaFieldDescriptor(String fields, String expectedField) {
        if (fields == null || expectedField == null || expectedField.length() == 0) {
            return "";
        }
        int position = 0;
        while (position < fields.length()) {
            int end = fields.indexOf(';', position);
            if (end < 0) {
                return "";
            }
            int nameEnd = fields.indexOf(':', position);
            if (nameEnd > position && nameEnd < end
                    && fields.substring(position, nameEnd).equals(expectedField)) {
                return fields.substring(position, end);
            }
            position = end + 1;
        }
        return "";
    }

    private static String schemaFieldReturnType(String fieldDescriptor) {
        int first = fieldDescriptor == null ? -1 : fieldDescriptor.indexOf(':');
        int second = first < 0 ? -1 : fieldDescriptor.indexOf(':', first + 1);
        return first < 0 || second <= first + 1 ? "" : fieldDescriptor.substring(first + 1, second);
    }

    private static String schemaFieldKind(String fieldDescriptor) {
        int first = fieldDescriptor == null ? -1 : fieldDescriptor.indexOf(':');
        int second = first < 0 ? -1 : fieldDescriptor.indexOf(':', first + 1);
        int third = second < 0 ? -1 : fieldDescriptor.indexOf(':', second + 1);
        int end = third < 0 ? -1 : fieldDescriptor.indexOf('|', third + 1);
        if (end < 0 && third >= 0) {
            end = fieldDescriptor.length();
        }
        return third < 0 || end <= third + 1 ? "" : fieldDescriptor.substring(third + 1, end);
    }

    private static String schemaFieldArguments(String fieldDescriptor) {
        int marker = fieldDescriptor == null ? -1 : fieldDescriptor.indexOf('|');
        return marker < 0 || marker + 1 >= fieldDescriptor.length()
                ? "" : fieldDescriptor.substring(marker + 1);
    }

    private static String schemaArgumentNames(String arguments) {
        if (arguments == null || arguments.length() == 0) {
            return "";
        }
        String names = ",";
        int position = 0;
        while (position < arguments.length()) {
            int end = arguments.indexOf(',', position);
            if (end < 0) end = arguments.length();
            int equals = arguments.indexOf('=', position);
            if (equals <= position || equals >= end) {
                return "";
            }
            names = names + arguments.substring(position, equals) + ",";
            position = end + 1;
        }
        return names;
    }

    private static String schemaArgumentDescriptor(String arguments, String expectedName) {
        if (arguments == null || expectedName == null || expectedName.length() == 0) {
            return "";
        }
        int position = 0;
        while (position < arguments.length()) {
            int end = arguments.indexOf(',', position);
            if (end < 0) end = arguments.length();
            int equals = arguments.indexOf('=', position);
            if (equals <= position || equals >= end) {
                return "";
            }
            if (arguments.substring(position, equals).equals(expectedName)) {
                return arguments.substring(position, end);
            }
            position = end + 1;
        }
        return "";
    }

    private static String schemaArgumentType(String argumentDescriptor) {
        int firstEquals = argumentDescriptor == null ? -1 : argumentDescriptor.indexOf('=');
        int secondEquals = firstEquals < 0 ? -1 : argumentDescriptor.indexOf('=', firstEquals + 1);
        return firstEquals < 0 || secondEquals <= firstEquals + 1
                ? "" : DatabaseGraphqlTypeReference.parse(
                        argumentDescriptor.substring(firstEquals + 1, secondEquals));
    }

    private static boolean schemaArgumentHasDefault(String argumentDescriptor) {
        int firstEquals = argumentDescriptor == null ? -1 : argumentDescriptor.indexOf('=');
        int secondEquals = firstEquals < 0 ? -1 : argumentDescriptor.indexOf('=', firstEquals + 1);
        return secondEquals >= 0 && argumentDescriptor.indexOf('=', secondEquals + 1) > secondEquals;
    }

    private static String schemaArgumentDefaultValue(String argumentDescriptor) {
        int firstEquals = argumentDescriptor == null ? -1 : argumentDescriptor.indexOf('=');
        int secondEquals = firstEquals < 0 ? -1 : argumentDescriptor.indexOf('=', firstEquals + 1);
        int thirdEquals = secondEquals < 0 ? -1 : argumentDescriptor.indexOf('=', secondEquals + 1);
        String value = thirdEquals < 0 || thirdEquals + 1 > argumentDescriptor.length()
                ? "" : argumentDescriptor.substring(thirdEquals + 1);
        return decodedSchemaDefaultValue(value);
    }

    /** Decodes delimiter-safe generated defaults while retaining older plain descriptors. */
    private static String decodedSchemaDefaultValue(String value) {
        if (value == null || value.length() == 0 || value.charAt(0) != '~') {
            return value == null ? "" : value;
        }
        String encoded = value.substring(1);
        return encoded.length() != 0 && base64UrlIsValidUtf8(encoded)
                ? Text.base64UrlDecodeUtf8(encoded) : "";
    }

    private static String missingRequiredSchemaArgument(
            String query,
            String requestAst,
            int fieldStart,
            String arguments,
            boolean hasProvidedArguments
    ) {
        int position = 0;
        while (arguments != null && position < arguments.length()) {
            int end = arguments.indexOf(',', position);
            if (end < 0) end = arguments.length();
            int firstEquals = arguments.indexOf('=', position);
            int secondEquals = firstEquals < 0 ? -1 : arguments.indexOf('=', firstEquals + 1);
            int thirdEquals = secondEquals < 0 ? -1 : arguments.indexOf('=', secondEquals + 1);
            if (firstEquals <= position || secondEquals <= firstEquals + 1 || secondEquals >= end) {
                return "";
            }
            String name = arguments.substring(position, firstEquals);
            String type = arguments.substring(firstEquals + 1, secondEquals);
            boolean hasDefault = thirdEquals > secondEquals && thirdEquals < end;
            if (type.endsWith("!") && !hasDefault
                    && (!hasProvidedArguments
                    || argumentLiteralFromAst(query, requestAst, fieldStart, name).length() == 0)) {
                return name;
            }
            position = end + 1;
        }
        return "";
    }

    /** Fast O(header) argument-presence check used before the indexed AST argument scan. */
    private static boolean fieldHasArguments(String query, int nameEnd, int headerEnd) {
        if (query == null || nameEnd < 0 || headerEnd < nameEnd || headerEnd > query.length()) {
            return false;
        }
        int position = skipIgnored(query, nameEnd, headerEnd);
        return position < headerEnd && query.charAt(position) == '(';
    }

    private static boolean envelopeExceedsCharacterBudget(
            String variablesJson,
            String extensionsJson,
            String trustedContextJson
    ) {
        return variablesJson != null && variablesJson.length() > MAX_REQUEST_ENVELOPE_CHARACTERS
                || extensionsJson != null && extensionsJson.length() > MAX_REQUEST_ENVELOPE_CHARACTERS
                || trustedContextJson != null && trustedContextJson.length() > MAX_REQUEST_ENVELOPE_CHARACTERS;
    }

    /** Compares caller intent with the model identity compiled into the installed routine. */
    public static boolean matchesExpectedModelHash(String expectedModelHash, String installedModelHash) {
        return expectedModelHash != null && expectedModelHash.length() != 0
                && expectedModelHash.equals(installedModelHash);
    }

    /** Compares the call's build identity with the identity compiled into this routine closure. */
    public static boolean matchesExpectedRuntimeIdentity(String expectedRuntimeIdentity, String installedRuntimeIdentity) {
        return expectedRuntimeIdentity != null && expectedRuntimeIdentity.length() == 64
                && expectedRuntimeIdentity.equals(installedRuntimeIdentity);
    }

    /**
     * Compares the caller's sealed SQL-inventory identity with the identity installed alongside
     * the public whole-request routine. This stays distinct from the compiled runtime closure:
     * a routine can be valid code while the installed package inventory is stale or substituted.
     */
    public static boolean matchesExpectedPackageIdentity(String expectedPackageIdentity, String installedPackageIdentity) {
        return expectedPackageIdentity != null && expectedPackageIdentity.length() == 64
                && expectedPackageIdentity.equals(installedPackageIdentity);
    }

    /**
     * Extracts exactly one selected operation into a compact self-contained document.
     *
     * <p>The generated engine thereafter receives only the operation kind and its selection set;
     * existing request helpers therefore cannot accidentally inspect a different named operation.
     * Retained fragment definitions follow the selected operation as ordinary GraphQL source: the
     * internal representation never injects sentinel/control characters into a GraphQL document.</p>
     */
    public static String selectedOperationDocument(String query, String operationName) {
        return DatabaseGraphqlLanguage.selectedOperationDocument(query, operationName);
    }

    /** Selects an operation using the request's already-validated bounded lexical token plan. */
    public static String selectedOperationDocument(String query, String documentTokens, String operationName) {
        return DatabaseGraphqlLanguage.selectedOperationDocument(query, documentTokens, operationName);
    }

    /** Returns the bounded lexical carrier reused by request preflight and operation selection. */
    public static String documentTokens(String query) {
        return DatabaseGraphqlLanguage.lexicalTokenStream(query);
    }

    /*
     * The public whole-request routine has a deliberately small transport protocol.  It is not
     * part of the GraphQL response and it must never be derived by looking inside GraphQL JSON.
     * PostgreSQL returns this framed value from its text function; MySQL unwraps it inside the
     * transpiled procedure and publishes the two values as result-set columns.  The standalone
     * JDBC frontend validates only this fixed protocol before it commits or rolls back.
     */
    private static final String TRANSPORT_PREFIX = "\u001eTITAN-GRAPHQL-TRANSPORT/1 ";
    private static final String TRANSPORT_SEPARATOR = "\n";

    public static String errorJson(String message) {
        return codedErrorJson(message, VALIDATION_ERROR);
    }

    /** Returns a syntax/lexing error and its retained source location when available. */
    public static String parseErrorJsonAt(String message, String source, int sourceOffset) {
        return codedErrorJsonAt(message, source, sourceOffset, PARSE_ERROR);
    }

    /** Returns a policy or trusted-permission failure. */
    public static String authorizationErrorJson(String message) {
        return codedErrorJson(message, AUTHORIZATION_ERROR);
    }

    /** Returns a policy or trusted-permission failure with its selected field location. */
    public static String authorizationErrorJsonAt(String message, String source, int sourceOffset) {
        return codedErrorJsonAt(message, source, sourceOffset, AUTHORIZATION_ERROR);
    }

    /** Returns a request for a deliberately unsupported operation or transport capability. */
    public static String unsupportedOperationErrorJson(String message) {
        return codedErrorJson(message, UNSUPPORTED_OPERATION);
    }

    /** Returns an unsupported schema/runtime capability at its selected field location. */
    public static String unsupportedOperationErrorJsonAt(String message, String source, int sourceOffset) {
        return codedErrorJsonAt(message, source, sourceOffset, UNSUPPORTED_OPERATION);
    }

    /** Returns a bounded-work rejection before resolver execution. */
    public static String resourceLimitErrorJson(String message) {
        return codedErrorJson(message, RESOURCE_LIMIT_ERROR);
    }

    /** Returns a source-located bounded-work rejection before resolver execution. */
    public static String resourceLimitErrorJsonAt(String message, String source, int sourceOffset) {
        return codedErrorJsonAt(message, source, sourceOffset, RESOURCE_LIMIT_ERROR);
    }

    /** Returns an expired trusted execution deadline. */
    public static String deadlineExceededErrorJson(String message) {
        return codedErrorJson(message, DEADLINE_EXCEEDED);
    }

    /** Returns a package/runtime invariant failure rather than mislabeling it as client validation. */
    public static String internalErrorJson(String message) {
        return codedErrorJson(message, INTERNAL_ERROR);
    }

    /** Returns a package/runtime invariant failure at the field that exposed it. */
    public static String internalErrorJsonAt(String message, String source, int sourceOffset) {
        return codedErrorJsonAt(message, source, sourceOffset, INTERNAL_ERROR);
    }

    /**
     * Returns one request error with its GraphQL document location when a retained AST offset is
     * available. Invalid direct-call inputs deliberately fall back to the location-free form:
     * generated serving routines reach this helper only after the common document budget and
     * lexical gate have accepted the selected operation.
     */
    public static String errorJsonAt(String message, String source, int sourceOffset) {
        return codedErrorJsonAt(message, source, sourceOffset, VALIDATION_ERROR);
    }

    private static String codedErrorJson(String message, String code) {
        return transportResponse("ROLLBACK", "{\"errors\":[{\"message\":\"" + jsonEscape(message)
                + "\",\"extensions\":{\"code\":\"" + code + "\"}}]}");
    }

    private static String codedErrorJsonAt(String message, String source, int sourceOffset, String code) {
        String location = graphqlLocationJson(source, sourceOffset);
        if (location.length() == 0) {
            return codedErrorJson(message, code);
        }
        return transportResponse("ROLLBACK", "{\"errors\":[{\"message\":\"" + jsonEscape(message)
                + "\",\"locations\":[" + location + "],\"extensions\":{\"code\":\"" + code + "\"}}]}");
    }

    /** Returns one GraphQL location object for a retained source offset, or an empty string. */
    private static String graphqlLocationJson(String source, int sourceOffset) {
        if (source == null || source.length() > MAX_QUERY_CHARACTERS
                || sourceOffset < 0 || sourceOffset >= source.length()) {
            return "";
        }
        int line = 1;
        int column = 1;
        int position = 0;
        while (position < sourceOffset) {
            char current = source.charAt(position);
            if (current == '\r') {
                line++;
                column = 1;
                if (position + 1 < sourceOffset && source.charAt(position + 1) == '\n') {
                    position++;
                }
            } else if (current == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
            position++;
        }
        return "{\"line\":" + line + ",\"column\":" + column + "}";
    }

    /** Signals that the caller-owned transaction must roll back any earlier mutation work. */
    public static String rollbackErrorJson(String message) {
        return rollbackCodedErrorJson(message, VALIDATION_ERROR);
    }

    /** Rolls back mutation work after a resolver/domain failure. */
    public static String rollbackExecutionErrorJson(String message) {
        return rollbackCodedErrorJson(message, EXECUTION_ERROR);
    }

    /** Rolls back mutation work after a policy rejection. */
    public static String rollbackAuthorizationErrorJson(String message) {
        return rollbackCodedErrorJson(message, AUTHORIZATION_ERROR);
    }

    /** Rolls back mutation work after an unsupported mutation surface is selected. */
    public static String rollbackUnsupportedOperationErrorJson(String message) {
        return rollbackCodedErrorJson(message, UNSUPPORTED_OPERATION);
    }

    /** Rolls back mutation work after its trusted execution deadline expires. */
    public static String rollbackDeadlineExceededErrorJson(String message) {
        return rollbackCodedErrorJson(message, DEADLINE_EXCEEDED);
    }

    /** Rolls back mutation work after the request exhausts a database-work allowance. */
    public static String rollbackResourceLimitErrorJson(String message) {
        return rollbackCodedErrorJson(message, RESOURCE_LIMIT_ERROR);
    }

    private static String rollbackCodedErrorJson(String message, String code) {
        return transportResponse("ROLLBACK", "{\"data\":null,\"errors\":[{\"message\":\""
                + jsonEscape(message) + "\",\"extensions\":{\"code\":\"" + code + "\"}}]}");
    }

    /**
     * Attaches the independently consumable transport outcome without exposing it in GraphQL's
     * {@code extensions} object. The name remains for generated-source compatibility.
     */
    public static String transactionOutcomeJson(String response, String outcome) {
        return transportResponse(outcome, response);
    }

    /** Returns the explicit transport outcome from a completed public-routine result. */
    public static String transportOutcome(String framedResponse) {
        int separator = transportSeparator(framedResponse);
        if (separator < 0) {
            return "";
        }
        return framedResponse.substring(TRANSPORT_PREFIX.length(), separator);
    }

    /** Returns the GraphQL response portion of a completed public-routine result. */
    public static String transportResponseJson(String framedResponse) {
        int separator = transportSeparator(framedResponse);
        if (separator < 0) {
            return "";
        }
        return framedResponse.substring(separator + TRANSPORT_SEPARATOR.length());
    }

    private static String transportResponse(String outcome, String response) {
        if (outcome == null || (outcome.equals("COMMIT") == false && outcome.equals("ROLLBACK") == false)) {
            return transportFailure("database engine produced an invalid transaction outcome");
        }
        if (transportSeparator(response) >= 0) {
            return response;
        }
        if (response == null || responseAssemblyExceeded(response)
                || response.length() > MAX_RESPONSE_CHARACTERS) {
            return transportResourceLimitFailure("response exceeds the database engine character budget");
        }
        return TRANSPORT_PREFIX + outcome + TRANSPORT_SEPARATOR + response;
    }

    /* Keep this leaf helper acyclic: Titan rejects recursive helper graphs during lowering. */
    private static String transportFailure(String message) {
        return TRANSPORT_PREFIX + "ROLLBACK" + TRANSPORT_SEPARATOR
                + "{\"errors\":[{\"message\":\"" + jsonEscape(message)
                + "\",\"extensions\":{\"code\":\"" + INTERNAL_ERROR + "\"}}]}";
    }

    /* Keep this leaf helper acyclic for the response that exceeded normal response assembly. */
    private static String transportResourceLimitFailure(String message) {
        return TRANSPORT_PREFIX + "ROLLBACK" + TRANSPORT_SEPARATOR
                + "{\"errors\":[{\"message\":\"" + jsonEscape(message)
                + "\",\"extensions\":{\"code\":\"" + RESOURCE_LIMIT_ERROR + "\"}}]}";
    }

    private static int transportSeparator(String framedResponse) {
        if (framedResponse == null || framedResponse.startsWith(TRANSPORT_PREFIX) == false) {
            return -1;
        }
        int separator = framedResponse.indexOf(TRANSPORT_SEPARATOR, TRANSPORT_PREFIX.length());
        if (separator < 0) {
            return -1;
        }
        String outcome = framedResponse.substring(TRANSPORT_PREFIX.length(), separator);
        if (outcome.equals("COMMIT") == false && outcome.equals("ROLLBACK") == false) {
            return -1;
        }
        return separator;
    }

    public static boolean isErrorJson(String value) {
        String response = transportResponseJson(value);
        if (response.length() == 0) {
            response = value == null ? "" : value;
        }
        return response.length() >= 11 && response.substring(0, 11).equals("{\"errors\":[");
    }

    public static String appendJsonMember(String current, String name, String jsonValue) {
        if (responseAssemblyExceeded(current) || responseAssemblyExceeded(jsonValue)) {
            return RESPONSE_ASSEMBLY_LIMIT;
        }
        String prefix = current.length() == 0 ? "" : ",";
        String escapedName = jsonEscape(name);
        int fixedCharacters = prefix.length() + escapedName.length() + 3;
        if (jsonValue.length() > MAX_RESPONSE_CHARACTERS - fixedCharacters
                || current.length() > MAX_RESPONSE_CHARACTERS - fixedCharacters - jsonValue.length()) {
            return RESPONSE_ASSEMBLY_LIMIT;
        }
        return current + prefix + "\"" + escapedName + "\":" + jsonValue;
    }

    /** Appends a pre-encoded JSON array item without leaking list assembly to generated schemas. */
    public static String appendJsonItem(String current, String jsonValue) {
        if (responseAssemblyExceeded(current) || responseAssemblyExceeded(jsonValue)) {
            return RESPONSE_ASSEMBLY_LIMIT;
        }
        String prefix = current.length() == 0 ? "" : ",";
        int fixedCharacters = prefix.length();
        if (jsonValue.length() > MAX_RESPONSE_CHARACTERS - fixedCharacters
                || current.length() > MAX_RESPONSE_CHARACTERS - fixedCharacters - jsonValue.length()) {
            return RESPONSE_ASSEMBLY_LIMIT;
        }
        return current + prefix + jsonValue;
    }

    /** Prepends one encoded array item while retaining the same incremental response bound. */
    public static String prependJsonItem(String current, String jsonValue) {
        if (responseAssemblyExceeded(current) || responseAssemblyExceeded(jsonValue)) {
            return RESPONSE_ASSEMBLY_LIMIT;
        }
        String separator = current.length() == 0 ? "" : ",";
        int fixedCharacters = separator.length();
        if (jsonValue.length() > MAX_RESPONSE_CHARACTERS - fixedCharacters
                || current.length() > MAX_RESPONSE_CHARACTERS - fixedCharacters - jsonValue.length()) {
            return RESPONSE_ASSEMBLY_LIMIT;
        }
        return jsonValue + separator + current;
    }

    /** True only for the private incremental-assembly overflow carrier or a wrapper containing it. */
    public static boolean responseAssemblyExceeded(String value) {
        return value != null && value.indexOf(RESPONSE_ASSEMBLY_LIMIT) >= 0;
    }

    /**
     * Adds database-work measurements to a completed GraphQL response when the authenticated
     * request context opted into diagnostics. Generated callers invoke this only for response
     * objects they assembled themselves, so no general JSON parser is needed in the database.
     * Package-identity and transport SQL are deliberately outside these application counters.
     */
    public static String appendExecutionMetrics(
            String response,
            int applicationSqlStatements,
            long decodedApplicationRows
    ) {
        if (response == null || response.length() < 2 || response.charAt(0) != '{'
                || response.charAt(response.length() - 1) != '}'
                || applicationSqlStatements < 0 || decodedApplicationRows < 0L
                || responseAssemblyExceeded(response)) {
            return RESPONSE_ASSEMBLY_LIMIT;
        }
        String metrics = ",\"extensions\":{\"titanExecution\":{\"applicationSqlStatements\":"
                + applicationSqlStatements + ",\"decodedApplicationRows\":"
                + decodedApplicationRows + "}}";
        if (response.length() > MAX_RESPONSE_CHARACTERS - metrics.length()) {
            return RESPONSE_ASSEMBLY_LIMIT;
        }
        return response.substring(0, response.length() - 1) + metrics + "}";
    }

    /**
     * Adds one reviewed parent key to the scalar carrier used by generated relation batches.
     * Length-prefixing keeps arbitrary String/UUID keys delimiter-safe without allocating a JVM
     * collection in the transpiled routine.
     */
    public static String appendBatchParentKey(String carrier, String key) {
        String current = carrier == null || carrier.length() == 0 ? "bk1;" : carrier;
        String value = key == null ? "" : key;
        if (!current.startsWith("bk1;")) {
            return "";
        }
        String record = value.length() + ":" + value;
        if (current.length() > MAX_RESPONSE_CHARACTERS - record.length()) {
            return RESPONSE_ASSEMBLY_LIMIT;
        }
        return current + record;
    }

    /** Returns the number of length-prefixed parent keys, or {@code -1} for a malformed carrier. */
    public static int batchParentKeyCount(String carrier) {
        if (carrier == null || !carrier.startsWith("bk1;")) {
            return -1;
        }
        int position = 4;
        int count = 0;
        while (position < carrier.length()) {
            int colon = carrier.indexOf(':', position);
            int length = batchCarrierDecimal(carrier, position, colon);
            int end = colon < 0 || length < 0 ? -1 : colon + 1 + length;
            if (end < colon + 1 || end > carrier.length()) {
                return -1;
            }
            count++;
            position = end;
        }
        return count;
    }

    /** Returns one reviewed parent key from the scalar batch carrier. */
    public static String batchParentKey(String carrier, int expectedIndex) {
        if (expectedIndex < 0 || carrier == null || !carrier.startsWith("bk1;")) {
            return "";
        }
        int position = 4;
        int index = 0;
        while (position < carrier.length()) {
            int colon = carrier.indexOf(':', position);
            int length = batchCarrierDecimal(carrier, position, colon);
            int start = colon < 0 ? -1 : colon + 1;
            int end = start < 0 || length < 0 ? -1 : start + length;
            if (end < start || end > carrier.length()) {
                return "";
            }
            if (index == expectedIndex) {
                return carrier.substring(start, end);
            }
            index++;
            position = end;
        }
        return "";
    }

    /** Records whether the corresponding parent key may participate in a batch join. */
    public static String appendBatchParentActivity(String carrier, boolean active) {
        String current = carrier == null || carrier.length() == 0 ? "ba1;" : carrier;
        if (!current.startsWith("ba1;") || current.length() >= MAX_RESPONSE_CHARACTERS) {
            return RESPONSE_ASSEMBLY_LIMIT;
        }
        return current + (active ? "1" : "0");
    }

    /** Returns the number of activity slots, or {@code -1} for a malformed carrier. */
    public static int batchParentActivityCount(String carrier) {
        if (carrier == null || !carrier.startsWith("ba1;")) {
            return -1;
        }
        int index = 4;
        while (index < carrier.length()) {
            char value = carrier.charAt(index);
            if (value != '0' && value != '1') {
                return -1;
            }
            index++;
        }
        return carrier.length() - 4;
    }

    /** True only for a valid active slot in the private parent-activity carrier. */
    public static boolean batchParentActive(String carrier, int parentIndex) {
        return parentIndex >= 0 && batchParentActivityCount(carrier) > parentIndex
                && carrier.charAt(parentIndex + 4) == '1';
    }

    /** Returns one bounded parent-owner index without a Java narrowing conversion. */
    public static int batchParentOwnerIndex(String carrier, int parentIndex) {
        String value = batchParentKey(carrier, parentIndex);
        int owner = batchCarrierDecimal(value, 0, value.length());
        return owner >= 0 && owner <= 1000 ? owner : -1;
    }

    /** Returns one non-negative AST start recorded in a private batch-plan carrier. */
    public static int batchPlanStart(String carrier, int planIndex) {
        String value = batchParentKey(carrier, planIndex);
        int start = batchCarrierDecimal(value, 0, value.length());
        return start >= 0 && start <= MAX_RESPONSE_CHARACTERS ? start : -1;
    }

    /**
     * True only at the first occurrence of an AST-backed operation plan. The generated executor
     * uses this to visit each distinct alias/argument/selection plan once without a collection.
     */
    public static boolean batchPlanFirstOccurrence(String carrier, int planIndex) {
        int count = batchParentKeyCount(carrier);
        int start = batchPlanStart(carrier, planIndex);
        if (planIndex < 0 || planIndex >= count || start < 0) {
            return false;
        }
        int index = 0;
        while (index < planIndex) {
            if (batchPlanStart(carrier, index) == start) {
                return false;
            }
            index++;
        }
        return true;
    }

    /** Selects the key/path records belonging to one exact AST-backed operation plan. */
    public static String batchParentKeysForPlan(String carrier, String plans, int planStart) {
        int count = batchParentKeyCount(carrier);
        if (count < 0 || batchParentKeyCount(plans) != count || planStart < 0) {
            return "";
        }
        String selected = "bk1;";
        int index = 0;
        while (index < count) {
            if (batchPlanStart(plans, index) == planStart) {
                selected = appendBatchParentKey(selected, batchParentKey(carrier, index));
                if (responseAssemblyExceeded(selected)) {
                    return selected;
                }
            }
            index++;
        }
        return selected;
    }

    /** Selects the activity records belonging to one exact AST-backed operation plan. */
    public static String batchParentActivityForPlan(String carrier, String plans, int planStart) {
        int count = batchParentActivityCount(carrier);
        if (count < 0 || batchParentKeyCount(plans) != count || planStart < 0) {
            return "";
        }
        String selected = "ba1;";
        int index = 0;
        while (index < count) {
            if (batchPlanStart(plans, index) == planStart) {
                selected = appendBatchParentActivity(selected, batchParentActive(carrier, index));
                if (responseAssemblyExceeded(selected)) {
                    return selected;
                }
            }
            index++;
        }
        return selected;
    }

    /** Maps plan-local parent slots back to their request-wide placeholder indexes. */
    public static String batchParentOwnersForPlan(String plans, int planStart) {
        int count = batchParentKeyCount(plans);
        if (count < 0 || planStart < 0) {
            return "";
        }
        String selected = "bk1;";
        int index = 0;
        while (index < count) {
            if (batchPlanStart(plans, index) == planStart) {
                selected = appendBatchParentKey(selected, "" + index);
                if (responseAssemblyExceeded(selected)) {
                    return selected;
                }
            }
            index++;
        }
        return selected;
    }

    /** A private transient JSON placeholder which client strings cannot manufacture unescaped. */
    public static String batchRelationPlaceholder(int fieldStart, int parentIndex) {
        return "\u001dTITAN-GRAPHQL-BATCH:" + fieldStart + ":" + parentIndex + "\u001d";
    }

    /** Appends one completed child object to a parent-indexed scalar relation carrier. */
    public static String appendBatchRelationItem(String carrier, int parentIndex, String jsonValue) {
        return appendBatchRelationRecord(carrier, parentIndex, "I", jsonValue);
    }

    /** Marks a parent relation null after ordinary GraphQL non-null propagation. */
    public static String appendBatchRelationNull(String carrier, int parentIndex) {
        return appendBatchRelationRecord(carrier, parentIndex, "N", "");
    }

    /** Completes one parent's batched list from its bounded scalar row carrier. */
    public static String batchRelationValue(String carrier, int parentIndex) {
        if (parentIndex < 0 || carrier == null || !carrier.startsWith("br1;")) {
            return "";
        }
        String items = "";
        int position = 4;
        while (position < carrier.length()) {
            int first = carrier.indexOf(':', position);
            int second = first < 0 ? -1 : carrier.indexOf(':', first + 1);
            int third = second < 0 ? -1 : carrier.indexOf(':', second + 1);
            int index = batchCarrierDecimal(carrier, position, first);
            int length = batchCarrierDecimal(carrier, second + 1, third);
            int start = third < 0 ? -1 : third + 1;
            int end = start < 0 || length < 0 ? -1 : start + length;
            if (first < 0 || second != first + 2 || third < 0 || end < start || end > carrier.length()) {
                return "";
            }
            if (index == parentIndex) {
                String kind = carrier.substring(first + 1, second);
                if (kind.equals("N")) {
                    return "null";
                }
                if (!kind.equals("I")) {
                    return "";
                }
                items = appendJsonItem(items, carrier.substring(start, end));
                if (responseAssemblyExceeded(items)) {
                    return items;
                }
            }
            position = end;
        }
        return "[" + items + "]";
    }

    /**
     * Completes at most {@code maximumItems} records for one parent. Backward Relay pages are read
     * in reverse database order, so {@code reverse} prepends each retained record and restores the
     * public connection order without a collection or frontend post-processing step.
     */
    public static String batchRelationLimitedValue(
            String carrier,
            int parentIndex,
            long maximumItems,
            boolean reverse
    ) {
        if (parentIndex < 0 || maximumItems < 0 || maximumItems > MAX_BATCH_RELATION_ITEMS
                || carrier == null || !carrier.startsWith("br1;")) {
            return "";
        }
        String items = "";
        int retained = 0;
        int position = 4;
        while (position < carrier.length()) {
            int first = carrier.indexOf(':', position);
            int second = first < 0 ? -1 : carrier.indexOf(':', first + 1);
            int third = second < 0 ? -1 : carrier.indexOf(':', second + 1);
            int index = batchCarrierDecimal(carrier, position, first);
            int length = batchCarrierDecimal(carrier, second + 1, third);
            int start = third < 0 ? -1 : third + 1;
            int end = start < 0 || length < 0 ? -1 : start + length;
            if (first < 0 || second != first + 2 || third < 0 || end < start || end > carrier.length()) {
                return "";
            }
            String kind = carrier.substring(first + 1, second);
            if (!kind.equals("I") && !kind.equals("N")) {
                return "";
            }
            if (index == parentIndex) {
                if (kind.equals("N")) {
                    return "null";
                }
                if (retained < maximumItems) {
                    String value = carrier.substring(start, end);
                    items = reverse ? prependJsonItem(items, value) : appendJsonItem(items, value);
                    if (responseAssemblyExceeded(items)) {
                        return items;
                    }
                    retained++;
                }
            }
            position = end;
        }
        return "[" + items + "]";
    }

    /** Returns one raw JSON item by its per-parent record index, or empty on malformed/absent data. */
    public static String batchRelationItem(String carrier, int parentIndex, long itemIndex) {
        if (parentIndex < 0 || itemIndex < 0 || itemIndex > MAX_BATCH_RELATION_ITEMS
                || carrier == null || !carrier.startsWith("br1;")) {
            return "";
        }
        int matched = 0;
        int position = 4;
        while (position < carrier.length()) {
            int first = carrier.indexOf(':', position);
            int second = first < 0 ? -1 : carrier.indexOf(':', first + 1);
            int third = second < 0 ? -1 : carrier.indexOf(':', second + 1);
            int index = batchCarrierDecimal(carrier, position, first);
            int length = batchCarrierDecimal(carrier, second + 1, third);
            int start = third < 0 ? -1 : third + 1;
            int end = start < 0 || length < 0 ? -1 : start + length;
            if (first < 0 || second != first + 2 || third < 0 || end < start || end > carrier.length()) {
                return "";
            }
            String kind = carrier.substring(first + 1, second);
            if (!kind.equals("I") && !kind.equals("N")) {
                return "";
            }
            if (index == parentIndex) {
                if (matched == itemIndex) {
                    return kind.equals("N") ? "null" : carrier.substring(start, end);
                }
                matched++;
            }
            position = end;
        }
        return "";
    }

    /** Completes a zero-or-one batched relation, rejecting a malformed or multi-row carrier. */
    public static String batchRelationObjectValue(String carrier, int parentIndex) {
        if (parentIndex < 0 || carrier == null || !carrier.startsWith("br1;")) {
            return "";
        }
        String item = "";
        int itemCount = 0;
        int position = 4;
        while (position < carrier.length()) {
            int first = carrier.indexOf(':', position);
            int second = first < 0 ? -1 : carrier.indexOf(':', first + 1);
            int third = second < 0 ? -1 : carrier.indexOf(':', second + 1);
            int index = batchCarrierDecimal(carrier, position, first);
            int length = batchCarrierDecimal(carrier, second + 1, third);
            int start = third < 0 ? -1 : third + 1;
            int end = start < 0 || length < 0 ? -1 : start + length;
            if (first < 0 || second != first + 2 || third < 0 || end < start || end > carrier.length()) {
                return "";
            }
            if (index == parentIndex) {
                String kind = carrier.substring(first + 1, second);
                if (kind.equals("N")) {
                    return "null";
                }
                if (!kind.equals("I") || itemCount != 0) {
                    return "";
                }
                item = carrier.substring(start, end);
                itemCount++;
            }
            position = end;
        }
        return itemCount == 0 ? "null" : item;
    }

    /** Counts item/null records for one parent, or returns {@code -1} for a malformed carrier. */
    public static int batchRelationRecordCount(String carrier, int parentIndex) {
        if (parentIndex < 0 || carrier == null || !carrier.startsWith("br1;")) {
            return -1;
        }
        int count = 0;
        int position = 4;
        while (position < carrier.length()) {
            int first = carrier.indexOf(':', position);
            int second = first < 0 ? -1 : carrier.indexOf(':', first + 1);
            int third = second < 0 ? -1 : carrier.indexOf(':', second + 1);
            int index = batchCarrierDecimal(carrier, position, first);
            int length = batchCarrierDecimal(carrier, second + 1, third);
            int start = third < 0 ? -1 : third + 1;
            int end = start < 0 || length < 0 ? -1 : start + length;
            if (first < 0 || second != first + 2 || third < 0 || end < start || end > carrier.length()) {
                return -1;
            }
            String kind = carrier.substring(first + 1, second);
            if (!kind.equals("I") && !kind.equals("N")) {
                return -1;
            }
            if (index == parentIndex) {
                count++;
            }
            position = end;
        }
        return count;
    }

    /** Replaces exactly one transient relation placeholder while retaining the response bound. */
    public static String replaceBatchRelationPlaceholder(
            String json,
            int fieldStart,
            int parentIndex,
            String relationValue
    ) {
        if (json == null || relationValue == null || responseAssemblyExceeded(json)
                || responseAssemblyExceeded(relationValue)) {
            return RESPONSE_ASSEMBLY_LIMIT;
        }
        String placeholder = batchRelationPlaceholder(fieldStart, parentIndex);
        int start = json.indexOf(placeholder);
        if (start < 0 || json.indexOf(placeholder, start + placeholder.length()) >= 0) {
            return "";
        }
        int resultLength = json.length() - placeholder.length() + relationValue.length();
        if (resultLength > MAX_RESPONSE_CHARACTERS) {
            return RESPONSE_ASSEMBLY_LIMIT;
        }
        return json.substring(0, start) + relationValue + json.substring(start + placeholder.length());
    }

    /**
     * Replaces one transient placeholder inside a length-prefixed relation-row carrier and
     * rewrites that record's encoded length. A plain JSON replacement cannot safely mutate this
     * carrier because a nested relation value normally has a different length than its marker.
     */
    public static String replaceBatchRelationCarrierPlaceholder(
            String carrier,
            int fieldStart,
            int parentIndex,
            String relationValue
    ) {
        if (carrier == null || !carrier.startsWith("br1;") || relationValue == null
                || responseAssemblyExceeded(carrier) || responseAssemblyExceeded(relationValue)) {
            return RESPONSE_ASSEMBLY_LIMIT;
        }
        String placeholder = batchRelationPlaceholder(fieldStart, parentIndex);
        String result = "br1;";
        int replacements = 0;
        int position = 4;
        while (position < carrier.length()) {
            int first = carrier.indexOf(':', position);
            int second = first < 0 ? -1 : carrier.indexOf(':', first + 1);
            int third = second < 0 ? -1 : carrier.indexOf(':', second + 1);
            int index = batchCarrierDecimal(carrier, position, first);
            int length = batchCarrierDecimal(carrier, second + 1, third);
            int start = third < 0 ? -1 : third + 1;
            int end = start < 0 || length < 0 ? -1 : start + length;
            if (index < 0 || first < 0 || second != first + 2 || third < 0
                    || end < start || end > carrier.length()) {
                return "";
            }
            String kind = carrier.substring(first + 1, second);
            if (!kind.equals("I") && !kind.equals("N")) {
                return "";
            }
            String value = carrier.substring(start, end);
            int placeholderStart = value.indexOf(placeholder);
            if (placeholderStart >= 0) {
                if (replacements != 0
                        || value.indexOf(placeholder, placeholderStart + placeholder.length()) >= 0) {
                    return "";
                }
                value = value.substring(0, placeholderStart) + relationValue
                        + value.substring(placeholderStart + placeholder.length());
                replacements++;
            }
            result = appendBatchRelationRecord(result, index, kind, value);
            if (responseAssemblyExceeded(result)) {
                return result;
            }
            position = end;
        }
        return replacements == 1 ? result : "";
    }

    /** Packs one generated query-root result without exposing schema-specific state to the caller. */
    public static String queryRootExecutionCarrier(
            String value,
            String executionErrors,
            int applicationSqlStatements,
            long decodedApplicationRows,
            boolean propagatedNull
    ) {
        String rootValue = value == null ? "" : value;
        String errors = executionErrors == null ? "" : executionErrors;
        if (applicationSqlStatements < 0 || applicationSqlStatements > 64
                || decodedApplicationRows < 0L || decodedApplicationRows > 1000L
                || responseAssemblyExceeded(rootValue) || responseAssemblyExceeded(errors)) {
            return "";
        }
        String carrier = "qr1;" + applicationSqlStatements + ":" + decodedApplicationRows + ":"
                + (propagatedNull ? "1" : "0") + ":" + errors.length() + ":"
                + rootValue.length() + ":" + errors + rootValue;
        return responseAssemblyExceeded(carrier) ? RESPONSE_ASSEMBLY_LIMIT : carrier;
    }

    /** True only for a complete, bounded generated query-root result carrier. */
    public static boolean queryRootExecutionCarrierIsValid(String carrier) {
        return queryRootExecutionCarrierPayloadStart(carrier) >= 0;
    }

    /** Returns the request-wide application statement count after one generated root. */
    public static int queryRootExecutionStatementCount(String carrier) {
        int first = queryRootExecutionCarrierFirstDelimiter(carrier);
        return first < 0 ? -1 : batchCarrierDecimal(carrier, 4, first);
    }

    /** Returns the request-wide decoded-row count after one generated root. */
    public static long queryRootExecutionDecodedRows(String carrier) {
        int first = queryRootExecutionCarrierFirstDelimiter(carrier);
        int second = first < 0 ? -1 : carrier.indexOf(':', first + 1);
        int rows = batchCarrierDecimal(carrier, first + 1, second);
        return rows < 0 ? -1L : rows;
    }

    /** Returns whether ordinary GraphQL completion propagated null through the generated root. */
    public static boolean queryRootExecutionPropagatedNull(String carrier) {
        int first = queryRootExecutionCarrierFirstDelimiter(carrier);
        int second = first < 0 ? -1 : carrier.indexOf(':', first + 1);
        int third = second < 0 ? -1 : carrier.indexOf(':', second + 1);
        return queryRootExecutionCarrierPayloadStart(carrier) >= 0
                && third == second + 2 && carrier.charAt(second + 1) == '1';
    }

    /** Returns the accumulated execution-error member fragment from one generated root. */
    public static String queryRootExecutionErrors(String carrier) {
        int payload = queryRootExecutionCarrierPayloadStart(carrier);
        if (payload < 0) {
            return "";
        }
        int first = queryRootExecutionCarrierFirstDelimiter(carrier);
        int second = carrier.indexOf(':', first + 1);
        int third = carrier.indexOf(':', second + 1);
        int fourth = carrier.indexOf(':', third + 1);
        int errorLength = batchCarrierDecimal(carrier, third + 1, fourth);
        return carrier.substring(payload, payload + errorLength);
    }

    /** Returns the completed JSON value from one generated root. */
    public static String queryRootExecutionValue(String carrier) {
        int payload = queryRootExecutionCarrierPayloadStart(carrier);
        if (payload < 0) {
            return "";
        }
        int first = queryRootExecutionCarrierFirstDelimiter(carrier);
        int second = carrier.indexOf(':', first + 1);
        int third = carrier.indexOf(':', second + 1);
        int fourth = carrier.indexOf(':', third + 1);
        int errorLength = batchCarrierDecimal(carrier, third + 1, fourth);
        return carrier.substring(payload + errorLength);
    }

    /**
     * Packs one metadata-reviewed Relay tuple cursor after a generated schema helper has validated it.
     * The string slot is empty for integral order values; the long slot is zero for text order values.
     */
    public static String connectionCursorCarrier(String stringValue, long longValue, long tieValue) {
        String text = stringValue == null ? "" : stringValue;
        if (responseAssemblyExceeded(text) || invalidLong(longValue) || invalidLong(tieValue)) {
            return "";
        }
        return "cc1;" + text.length() + ":" + longValue + ":" + tieValue + ":" + text;
    }

    /** True only for a complete generated Relay tuple-cursor carrier. */
    public static boolean connectionCursorCarrierIsValid(String carrier) {
        return connectionCursorCarrierPayloadStart(carrier) >= 0;
    }

    /** Returns the validated text order value, or an empty string for an integral order. */
    public static String connectionCursorCarrierStringValue(String carrier) {
        int payload = connectionCursorCarrierPayloadStart(carrier);
        return payload < 0 ? "" : carrier.substring(payload);
    }

    /** Returns the validated integral order value, or zero for a text order. */
    public static long connectionCursorCarrierLongValue(String carrier) {
        int first = connectionCursorCarrierFirstDelimiter(carrier);
        int second = first < 0 ? -1 : carrier.indexOf(':', first + 1);
        return connectionCursorCarrierPayloadStart(carrier) < 0
                ? INVALID_LONG
                : longArgument(carrier.substring(first + 1, second));
    }

    /** Returns the validated stable tie-breaker value. */
    public static long connectionCursorCarrierTieValue(String carrier) {
        int first = connectionCursorCarrierFirstDelimiter(carrier);
        int second = first < 0 ? -1 : carrier.indexOf(':', first + 1);
        int third = second < 0 ? -1 : carrier.indexOf(':', second + 1);
        return connectionCursorCarrierPayloadStart(carrier) < 0
                ? INVALID_LONG
                : longArgument(carrier.substring(second + 1, third));
    }

    private static int connectionCursorCarrierFirstDelimiter(String carrier) {
        return carrier == null || !carrier.startsWith("cc1;") ? -1 : carrier.indexOf(':', 4);
    }

    private static int connectionCursorCarrierPayloadStart(String carrier) {
        int first = connectionCursorCarrierFirstDelimiter(carrier);
        int second = first < 0 ? -1 : carrier.indexOf(':', first + 1);
        int third = second < 0 ? -1 : carrier.indexOf(':', second + 1);
        int textLength = batchCarrierDecimal(carrier, 4, first);
        long longValue = first < 0 || second < 0
                ? INVALID_LONG : longArgument(carrier.substring(first + 1, second));
        long tieValue = second < 0 || third < 0
                ? INVALID_LONG : longArgument(carrier.substring(second + 1, third));
        int payload = third < 0 ? -1 : third + 1;
        if (textLength < 0 || invalidLong(longValue) || invalidLong(tieValue)
                || payload < 0 || payload + textLength != carrier.length()) {
            return -1;
        }
        return payload;
    }

    private static int queryRootExecutionCarrierFirstDelimiter(String carrier) {
        return carrier == null || !carrier.startsWith("qr1;") ? -1 : carrier.indexOf(':', 4);
    }

    private static int queryRootExecutionCarrierPayloadStart(String carrier) {
        int first = queryRootExecutionCarrierFirstDelimiter(carrier);
        int second = first < 0 ? -1 : carrier.indexOf(':', first + 1);
        int third = second < 0 ? -1 : carrier.indexOf(':', second + 1);
        int fourth = third < 0 ? -1 : carrier.indexOf(':', third + 1);
        int fifth = fourth < 0 ? -1 : carrier.indexOf(':', fourth + 1);
        int statements = batchCarrierDecimal(carrier, 4, first);
        int rows = batchCarrierDecimal(carrier, first + 1, second);
        int errorLength = batchCarrierDecimal(carrier, third + 1, fourth);
        int valueLength = batchCarrierDecimal(carrier, fourth + 1, fifth);
        int payload = fifth < 0 ? -1 : fifth + 1;
        if (statements < 0 || statements > 64 || rows < 0 || rows > 1000
                || third != second + 2
                || (carrier.charAt(second + 1) != '0' && carrier.charAt(second + 1) != '1')
                || errorLength < 0 || valueLength < 0 || payload < 0
                || payload + errorLength + valueLength != carrier.length()) {
            return -1;
        }
        return payload;
    }

    private static String appendBatchRelationRecord(
            String carrier,
            int parentIndex,
            String kind,
            String jsonValue
    ) {
        String current = carrier == null || carrier.length() == 0 ? "br1;" : carrier;
        String value = jsonValue == null ? "" : jsonValue;
        if (!current.startsWith("br1;") || parentIndex < 0 || responseAssemblyExceeded(value)) {
            return RESPONSE_ASSEMBLY_LIMIT;
        }
        String record = parentIndex + ":" + kind + ":" + value.length() + ":" + value;
        if (current.length() > MAX_RESPONSE_CHARACTERS - record.length()) {
            return RESPONSE_ASSEMBLY_LIMIT;
        }
        return current + record;
    }

    private static int batchCarrierDecimal(String value, int start, int end) {
        if (value == null || start < 0 || end <= start || end > value.length()) {
            return -1;
        }
        int result = 0;
        int position = start;
        while (position < end) {
            char current = value.charAt(position);
            if (current < '0' || current > '9' || result > 1000000) {
                return -1;
            }
            result = result * 10 + current - '0';
            position++;
        }
        return result;
    }

    /**
     * Appends one execution-time resolver error. Validation errors remain location-oriented;
     * this path-bearing form is for a value that failed during database result materialization.
     */
    public static String appendExecutionError(String current, String message, String pathJson) {
        return appendExecutionError(current, message, pathJson, "", -1);
    }

    /**
     * Appends one execution-time resolver error with the selected field's retained source
     * location when available. A malformed direct-call offset remains path-bearing but
     * location-free rather than scanning arbitrary input.
     */
    public static String appendExecutionError(
            String current,
            String message,
            String pathJson,
            String source,
            int sourceOffset
    ) {
        if (pathJson == null || pathJson.length() < 2 || pathJson.charAt(0) != '['
                || pathJson.charAt(pathJson.length() - 1) != ']') {
            return current;
        }
        String location = graphqlLocationJson(source, sourceOffset);
        return appendJsonItem(current, "{\"message\":\"" + jsonEscape(message) + "\""
                + (location.length() == 0 ? "" : ",\"locations\":[" + location + "]")
                + ",\"path\":" + pathJson + ",\"extensions\":{\"code\":\""
                + EXECUTION_ERROR + "\"}}");
    }

    /** Appends one response-key segment to a generated execution path. */
    public static String appendExecutionPath(String pathJson, String responseKey) {
        String path = pathJson == null || pathJson.equals("[]") ? "" : pathJson.substring(1, pathJson.length() - 1);
        return "[" + (path.length() == 0 ? "" : path + ",") + jsonString(responseKey) + "]";
    }

    /** Appends a zero-based list index to a generated execution path. */
    public static String appendExecutionPathIndex(String pathJson, long index) {
        if (index < 0) {
            return pathJson == null ? "[]" : pathJson;
        }
        String path = pathJson == null || pathJson.equals("[]") ? "" : pathJson.substring(1, pathJson.length() - 1);
        return "[" + (path.length() == 0 ? "" : path + ",") + index + "]";
    }

    /** Renders an execution response after value completion, retaining partial data and errors. */
    public static String executionResponseJson(String members, String executionErrors) {
        String data = "{\"data\":{" + (members == null ? "" : members) + "}";
        if (executionErrors == null || executionErrors.length() == 0) {
            return data + "}";
        }
        return data + ",\"errors\":[" + executionErrors + "]}";
    }

    /** Renders an execution response after a non-null root chain has reached the operation data. */
    public static String executionResponseWithNullDataJson(String executionErrors) {
        String data = "{\"data\":null";
        if (executionErrors == null || executionErrors.length() == 0) {
            return data + "}";
        }
        return data + ",\"errors\":[" + executionErrors + "]}";
    }

    public static String jsonString(String value) {
        return value == null ? "null" : "\"" + jsonEscape(value) + "\"";
    }

    public static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        String escaped = "";
        int position = 0;
        while (position < value.length()) {
            char current = value.charAt(position);
            if (current == '"') {
                escaped = escaped + "\\\"";
            } else if (current == '\\') {
                escaped = escaped + "\\\\";
            } else if (current == '\n') {
                escaped = escaped + "\\n";
            } else if (current == '\r') {
                escaped = escaped + "\\r";
            } else if (current == '\t') {
                escaped = escaped + "\\t";
            } else if (current < ' ') {
                escaped = escaped + " ";
            } else {
                escaped = escaped + current;
            }
            position++;
        }
        return escaped;
    }

    public static String trustedContextString(String trustedContextJson, String fieldName) {
        String value = jsonObjectFieldValue(trustedContextJson, fieldName);
        if (value.length() < 2 || value.charAt(0) != '"') {
            return "";
        }
        return jsonStringValue(value);
    }

    /**
     * Reads the optional absolute request deadline from the authenticated context. A missing
     * value means no additional deadline; a present value must be a non-negative JSON integer in
     * the portable database timestamp range.
     * The HTTP frontend and JDBC adapter still enforce cancellation/statement-timeout behavior,
     * but this check prevents an already-expired request from entering generated schema work.
     */
    public static long trustedContextDeadlineEpochMillis(String trustedContextJson) {
        String value = jsonObjectFieldValue(trustedContextJson, "deadlineEpochMillis");
        if (value.length() == 0) {
            return 0L;
        }
        long deadlineEpochMillis = longArgument(value);
        return deadlineEpochMillis < 0L || deadlineEpochMillis > MAX_SUPPORTED_DEADLINE_EPOCH_MILLIS
                ? INVALID_LONG : deadlineEpochMillis;
    }

    /**
     * Reads a Boolean capability from the authenticated request-context envelope.  Unlike
     * {@link #trustedContextBoolean(String, String)}, this addresses a top-level transport
     * capability rather than a model-defined value in {@code contextValues}.  Missing or
     * malformed flags deliberately fail closed.
     */
    public static boolean trustedContextFlag(String trustedContextJson, String fieldName) {
        return jsonObjectFieldValue(trustedContextJson, fieldName).equals("true");
    }

    /**
     * Reserves the schema-known metadata objects selected below one {@code __Type} position.
     * Counts are supplied by the generated schema binding and therefore require no runtime walk
     * of its inventories. A result above 512 is a saturated over-budget value.
     */
    public static int introspectionTypeExpansionCostFromAst(
            String query,
            String requestAst,
            int typeStart,
            String variablesJson,
            int typeItems,
            int fieldItems,
            int fieldArgumentItems,
            int inputFieldItems,
            int enumValueItems,
            int interfaceItems,
            int possibleTypeItems
    ) {
        if (typeItems < 0 || fieldItems < 0 || fieldArgumentItems < 0 || inputFieldItems < 0
                || enumValueItems < 0 || interfaceItems < 0 || possibleTypeItems < 0) {
            return MAX_INTROSPECTION_ITEMS + 1;
        }
        String plan = introspectionSelectionPlanFromAst(
                query, requestAst, typeStart, variablesJson, "__Type");
        if (selectionPlanCount(plan) < 0) {
            return 0;
        }
        int cost = typeItems;
        int fieldsStart = selectionPlanFieldStartForFieldFromAst(
                query, requestAst, plan, "fields");
        if (fieldsStart >= 0) {
            if (fieldItems > MAX_INTROSPECTION_ITEMS - cost) return MAX_INTROSPECTION_ITEMS + 1;
            cost += fieldItems;
            String fieldPlan = introspectionSelectionPlanFromAst(
                    query, requestAst, fieldsStart, variablesJson, "__Field");
            if (selectionPlanFieldStartForFieldFromAst(
                    query, requestAst, fieldPlan, "args") >= 0) {
                if (fieldArgumentItems > MAX_INTROSPECTION_ITEMS - cost) {
                    return MAX_INTROSPECTION_ITEMS + 1;
                }
                cost += fieldArgumentItems;
            }
        }
        int inputFieldsStart = selectionPlanFieldStartForFieldFromAst(
                query, requestAst, plan, "inputFields");
        if (inputFieldsStart >= 0) {
            if (inputFieldItems > MAX_INTROSPECTION_ITEMS - cost) {
                return MAX_INTROSPECTION_ITEMS + 1;
            }
            cost += inputFieldItems;
        }
        int enumValuesStart = selectionPlanFieldStartForFieldFromAst(
                query, requestAst, plan, "enumValues");
        if (enumValuesStart >= 0) {
            if (enumValueItems > MAX_INTROSPECTION_ITEMS - cost) {
                return MAX_INTROSPECTION_ITEMS + 1;
            }
            cost += enumValueItems;
        }
        int interfacesStart = selectionPlanFieldStartForFieldFromAst(
                query, requestAst, plan, "interfaces");
        if (interfacesStart >= 0) {
            if (interfaceItems > MAX_INTROSPECTION_ITEMS - cost) {
                return MAX_INTROSPECTION_ITEMS + 1;
            }
            cost += interfaceItems;
        }
        int possibleTypesStart = selectionPlanFieldStartForFieldFromAst(
                query, requestAst, plan, "possibleTypes");
        if (possibleTypesStart >= 0) {
            if (possibleTypeItems > MAX_INTROSPECTION_ITEMS - cost) {
                return MAX_INTROSPECTION_ITEMS + 1;
            }
            cost += possibleTypeItems;
        }
        return cost;
    }

    /** Reserves generated directive, argument, and location list items before rendering them. */
    public static int introspectionDirectiveExpansionCostFromAst(
            String query,
            String requestAst,
            int directivesStart,
            String variablesJson,
            int directiveItems,
            int argumentItems,
            int locationItems
    ) {
        if (directiveItems < 0 || argumentItems < 0 || locationItems < 0) {
            return MAX_INTROSPECTION_ITEMS + 1;
        }
        String plan = introspectionSelectionPlanFromAst(
                query, requestAst, directivesStart, variablesJson, "__Directive");
        if (selectionPlanCount(plan) < 0) {
            return 0;
        }
        int cost = directiveItems;
        if (selectionPlanFieldStartForFieldFromAst(query, requestAst, plan, "args") >= 0) {
            if (argumentItems > MAX_INTROSPECTION_ITEMS - cost) {
                return MAX_INTROSPECTION_ITEMS + 1;
            }
            cost += argumentItems;
        }
        if (selectionPlanFieldStartForFieldFromAst(query, requestAst, plan, "locations") >= 0) {
            if (locationItems > MAX_INTROSPECTION_ITEMS - cost) {
                return MAX_INTROSPECTION_ITEMS + 1;
            }
            cost += locationItems;
        }
        return cost;
    }

    /**
     * Renders reviewed object-field metadata for the database-resident {@code __Type.fields}
     * slice. The generator supplies compact field/type/nullability descriptors, so this shared
     * walker never needs a JVM schema, reflection, or a per-field serving callback.
     *
     * <p>The descriptor format is {@code name:Type:nullable:kind|argument=Type=kind,...;} and is
     * generator-owned. The optional argument portion uses input type references, so the same
     * shared walker can describe executable Query/Mutation fields without a JVM schema or a
     * model-specific serving callback. Returning a regular GraphQL error JSON keeps malformed
     * selections on the same public routine path as ordinary query validation.</p>
     */
    /**
     * Renders one generator-described named {@code __Type}. The request shape is generic and is
     * validated independently of the runtime kind: standard introspection clients select fields,
     * input fields, enum values, interfaces, and possible types for every entry in
     * {@code __Schema.types}, expecting null or an empty list where a field does not apply.
     * Keeping that logic here avoids unrolling another schema-specific validator for every type.
     */
    public static String introspectionNamedTypeJson(
            String query,
            String requestAst,
            int typeStart,
            String variablesJson,
            String suppliedSelectionPlan,
            String typeName,
            String kind,
            String fieldsDescriptor,
            String inputFieldsDescriptor,
            String enumValuesDescriptor,
            boolean selectionPrevalidated
    ) {
        return introspectionNamedTypeJson(query, requestAst, typeStart, variablesJson, suppliedSelectionPlan,
                typeName, kind, fieldsDescriptor, inputFieldsDescriptor, enumValuesDescriptor, "",
                selectionPrevalidated);
    }

    /** Renders one named type together with its generator-owned schema description. */
    public static String introspectionNamedTypeJson(
            String query,
            String requestAst,
            int typeStart,
            String variablesJson,
            String suppliedSelectionPlan,
            String typeName,
            String kind,
            String fieldsDescriptor,
            String inputFieldsDescriptor,
            String enumValuesDescriptor,
            String typeDescription,
            boolean selectionPrevalidated
    ) {
        return introspectionNamedTypeJson(query, requestAst, typeStart, variablesJson, suppliedSelectionPlan,
                typeName, kind, fieldsDescriptor, inputFieldsDescriptor, enumValuesDescriptor, typeDescription,
                "tl1;", "tl1;", selectionPrevalidated);
    }

    /** Renders one named type with its object interfaces and abstract possible types. */
    public static String introspectionNamedTypeJson(
            String query,
            String requestAst,
            int typeStart,
            String variablesJson,
            String suppliedSelectionPlan,
            String typeName,
            String kind,
            String fieldsDescriptor,
            String inputFieldsDescriptor,
            String enumValuesDescriptor,
            String typeDescription,
            String interfacesDescriptor,
            String possibleTypesDescriptor,
            boolean selectionPrevalidated
    ) {
        return introspectionNamedTypeJson(query, requestAst, typeStart, variablesJson, suppliedSelectionPlan,
                typeName, kind, fieldsDescriptor, inputFieldsDescriptor, enumValuesDescriptor, typeDescription,
                interfacesDescriptor, possibleTypesDescriptor, "fm1;", selectionPrevalidated);
    }

    /** Renders one named type with delimiter-safe output-field descriptions and deprecations. */
    public static String introspectionNamedTypeJson(
            String query,
            String requestAst,
            int typeStart,
            String variablesJson,
            String suppliedSelectionPlan,
            String typeName,
            String kind,
            String fieldsDescriptor,
            String inputFieldsDescriptor,
            String enumValuesDescriptor,
            String typeDescription,
            String interfacesDescriptor,
            String possibleTypesDescriptor,
            String fieldMetadataDescriptor,
            boolean selectionPrevalidated
    ) {
        if (typeName == null || typeName.length() == 0 || kind == null
                || (!kind.equals("SCALAR") && !kind.equals("OBJECT")
                && !kind.equals("INTERFACE") && !kind.equals("UNION")
                && !kind.equals("INPUT_OBJECT") && !kind.equals("ENUM"))) {
            return errorJsonAt("generated introspection type metadata is malformed", query, typeStart);
        }
        String selectionPlan = suppliedSelectionPlan == null || suppliedSelectionPlan.length() == 0
                ? introspectionSelectionPlanFromAst(query, requestAst, typeStart, variablesJson, "__Type")
                : suppliedSelectionPlan;
        int nameStart = -1;
        int kindStart = -1;
        int descriptionStart = -1;
        int ofTypeStart = -1;
        int fieldsStart = -1;
        int inputFieldsStart = -1;
        int interfacesStart = -1;
        int enumValuesStart = -1;
        int possibleTypesStart = -1;
        int specifiedByUrlStart = -1;
        int oneOfStart = -1;
        int typeNameStart = -1;
        int planCount = selectionPlanCount(selectionPlan);
        int planIndex = 0;
        while (planIndex < planCount) {
            int selectedStart = selectionPlanFieldStart(selectionPlan, planIndex);
            String selectedField = fieldName(query, requestAst, selectedStart);
            if (selectedField.equals("name") && nameStart < 0) nameStart = selectedStart;
            else if (selectedField.equals("kind") && kindStart < 0) kindStart = selectedStart;
            else if (selectedField.equals("description") && descriptionStart < 0) descriptionStart = selectedStart;
            else if (selectedField.equals("ofType") && ofTypeStart < 0) ofTypeStart = selectedStart;
            else if (selectedField.equals("fields") && fieldsStart < 0) fieldsStart = selectedStart;
            else if (selectedField.equals("inputFields") && inputFieldsStart < 0) inputFieldsStart = selectedStart;
            else if (selectedField.equals("interfaces") && interfacesStart < 0) interfacesStart = selectedStart;
            else if (selectedField.equals("enumValues") && enumValuesStart < 0) enumValuesStart = selectedStart;
            else if (selectedField.equals("possibleTypes") && possibleTypesStart < 0) possibleTypesStart = selectedStart;
            else if (selectedField.equals("specifiedByURL") && specifiedByUrlStart < 0) {
                specifiedByUrlStart = selectedStart;
            } else if (selectedField.equals("isOneOf") && oneOfStart < 0) oneOfStart = selectedStart;
            else if (selectedField.equals("__typename") && typeNameStart < 0) typeNameStart = selectedStart;
            planIndex++;
        }
        int selected = (nameStart >= 0 ? 1 : 0) + (kindStart >= 0 ? 1 : 0)
                + (descriptionStart >= 0 ? 1 : 0) + (ofTypeStart >= 0 ? 1 : 0)
                + (fieldsStart >= 0 ? 1 : 0) + (inputFieldsStart >= 0 ? 1 : 0)
                + (interfacesStart >= 0 ? 1 : 0) + (enumValuesStart >= 0 ? 1 : 0)
                + (possibleTypesStart >= 0 ? 1 : 0) + (specifiedByUrlStart >= 0 ? 1 : 0)
                + (oneOfStart >= 0 ? 1 : 0) + (typeNameStart >= 0 ? 1 : 0);
        if (selected == 0) {
            return errorJsonAt("introspection type field requires a selection set", query, typeStart);
        }
        if (selected != planCount) {
            return errorJsonAt("unsupported field in __Type selection", query, typeStart);
        }

        if (!selectionPrevalidated) {
            int[] noArgumentFields = {nameStart, kindStart, descriptionStart, ofTypeStart, interfacesStart,
                    possibleTypesStart, specifiedByUrlStart, oneOfStart, typeNameStart};
            int argumentIndex = 0;
            while (argumentIndex < noArgumentFields.length) {
                int fieldStart = noArgumentFields[argumentIndex];
                if (fieldStart >= 0 && !fieldHasOnlyArgumentsFromAst(query, requestAst, fieldStart, "")) {
                    return errorJsonAt("introspection field does not support arguments", query, fieldStart);
                }
                argumentIndex++;
            }
            int[] scalarFields = {nameStart, kindStart, descriptionStart, specifiedByUrlStart, oneOfStart, typeNameStart};
            int scalarIndex = 0;
            while (scalarIndex < scalarFields.length) {
                int fieldStart = scalarFields[scalarIndex];
                if (fieldStart >= 0 && !fieldHasNoSelectionSetFromAst(query, requestAst, fieldStart)) {
                    return errorJsonAt("introspection scalar field cannot have a selection", query, fieldStart);
                }
                scalarIndex++;
            }
        }

        String ofTypeValue = "null";
        if (ofTypeStart >= 0) {
            String validation = introspectionTypeReferenceJson(query, requestAst, ofTypeStart, variablesJson,
                    "String", "S");
            if (isErrorJson(validation)) {
                return validation;
            }
        }
        String fieldsValue = "null";
        if (fieldsStart >= 0) {
            String rendered = introspectionObjectFieldsJson(query, requestAst, fieldsStart, variablesJson,
                    (kind.equals("OBJECT") || kind.equals("INTERFACE")) && fieldsDescriptor != null
                            ? fieldsDescriptor : "",
                    (kind.equals("OBJECT") || kind.equals("INTERFACE")) && fieldMetadataDescriptor != null
                            ? fieldMetadataDescriptor : "fm1;",
                    selectionPrevalidated);
            if (isErrorJson(rendered)) {
                return rendered;
            }
            if (kind.equals("OBJECT") || kind.equals("INTERFACE")) {
                fieldsValue = rendered;
            }
        }
        String inputFieldsValue = "null";
        if (inputFieldsStart >= 0) {
            String rendered = introspectionInputValuesJson(query, requestAst, inputFieldsStart, variablesJson,
                    kind.equals("INPUT_OBJECT") && inputFieldsDescriptor != null ? inputFieldsDescriptor : "",
                    "__Type.inputFields", true);
            if (isErrorJson(rendered)) {
                return rendered;
            }
            if (kind.equals("INPUT_OBJECT")) {
                inputFieldsValue = rendered;
            }
        }
        String enumValuesValue = "null";
        if (enumValuesStart >= 0) {
            String rendered = introspectionEnumValuesJson(query, requestAst, enumValuesStart, variablesJson,
                    kind.equals("ENUM") && enumValuesDescriptor != null ? enumValuesDescriptor : "");
            if (isErrorJson(rendered)) {
                return rendered;
            }
            if (kind.equals("ENUM")) {
                enumValuesValue = rendered;
            }
        }
        String interfacesValue = "null";
        if (interfacesStart >= 0) {
            if (kind.equals("OBJECT") || kind.equals("INTERFACE")) {
                interfacesValue = introspectionNamedTypeListJson(query, requestAst, interfacesStart,
                        variablesJson, interfacesDescriptor);
                if (isErrorJson(interfacesValue)) {
                    return interfacesValue;
                }
            } else {
                String validation = introspectionTypeReferenceJson(query, requestAst, interfacesStart,
                        variablesJson, "String", "S");
                if (isErrorJson(validation)) {
                    return validation;
                }
            }
        }
        String possibleTypesValue = "null";
        if (possibleTypesStart >= 0) {
            if (kind.equals("INTERFACE") || kind.equals("UNION")) {
                possibleTypesValue = introspectionNamedTypeListJson(query, requestAst, possibleTypesStart,
                        variablesJson, possibleTypesDescriptor);
                if (isErrorJson(possibleTypesValue)) {
                    return possibleTypesValue;
                }
            } else {
                String validation = introspectionTypeReferenceJson(query, requestAst, possibleTypesStart,
                        variablesJson, "String", "S");
                if (isErrorJson(validation)) {
                    return validation;
                }
            }
        }

        String nameKey = nameStart < 0 ? "" : responseKeyFromAst(query, requestAst, nameStart, "name");
        String kindKey = kindStart < 0 ? "" : responseKeyFromAst(query, requestAst, kindStart, "kind");
        String descriptionKey = descriptionStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, descriptionStart, "description");
        String ofTypeKey = ofTypeStart < 0 ? "" : responseKeyFromAst(query, requestAst, ofTypeStart, "ofType");
        String fieldsKey = fieldsStart < 0 ? "" : responseKeyFromAst(query, requestAst, fieldsStart, "fields");
        String inputFieldsKey = inputFieldsStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, inputFieldsStart, "inputFields");
        String interfacesKey = interfacesStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, interfacesStart, "interfaces");
        String enumValuesKey = enumValuesStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, enumValuesStart, "enumValues");
        String possibleTypesKey = possibleTypesStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, possibleTypesStart, "possibleTypes");
        String specifiedByUrlKey = specifiedByUrlStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, specifiedByUrlStart, "specifiedByURL");
        String oneOfKey = oneOfStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, oneOfStart, "isOneOf");
        String typeNameKey = typeNameStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, typeNameStart, "__typename");

        String members = "";
        if (nameStart >= 0) members = appendJsonMember(members, nameKey, jsonString(typeName));
        if (kindStart >= 0) members = appendJsonMember(members, kindKey, jsonString(kind));
        if (descriptionStart >= 0) members = appendJsonMember(members, descriptionKey,
                typeDescription == null || typeDescription.length() == 0 ? "null" : jsonString(typeDescription));
        if (ofTypeStart >= 0) members = appendJsonMember(members, ofTypeKey, ofTypeValue);
        if (fieldsStart >= 0) members = appendJsonMember(members, fieldsKey, fieldsValue);
        if (inputFieldsStart >= 0) members = appendJsonMember(members, inputFieldsKey, inputFieldsValue);
        if (interfacesStart >= 0) members = appendJsonMember(members, interfacesKey, interfacesValue);
        if (enumValuesStart >= 0) members = appendJsonMember(members, enumValuesKey, enumValuesValue);
        if (possibleTypesStart >= 0) members = appendJsonMember(members, possibleTypesKey, possibleTypesValue);
        if (specifiedByUrlStart >= 0) members = appendJsonMember(members, specifiedByUrlKey, "null");
        if (oneOfStart >= 0) members = appendJsonMember(members, oneOfKey,
                kind.equals("INPUT_OBJECT") ? "false" : "null");
        if (typeNameStart >= 0) members = appendJsonMember(members, typeNameKey, jsonString("__Type"));
        return "{" + members + "}";
    }

    /** Renders a generated {@code tl1;} list of {@code kind:name;} named type references. */
    public static String introspectionNamedTypeListJson(
            String query,
            String requestAst,
            int fieldStart,
            String variablesJson,
            String descriptor
    ) {
        if (descriptor == null || !descriptor.startsWith("tl1;")) {
            return errorJsonAt("generated introspection type-list metadata is malformed", query, fieldStart);
        }
        String items = "";
        int position = 4;
        while (position < descriptor.length()) {
            int colon = descriptor.indexOf(':', position);
            int end = colon < 0 ? -1 : descriptor.indexOf(';', colon + 1);
            if (colon != position + 1 || end <= colon + 1) {
                return errorJsonAt("generated introspection type-list metadata is malformed", query, fieldStart);
            }
            String kind = descriptor.substring(position, colon);
            String name = descriptor.substring(colon + 1, end);
            if (!introspectionNamedKindIsValid(kind)) {
                return errorJsonAt("generated introspection type-list metadata is malformed", query, fieldStart);
            }
            String item = introspectionTypeReferenceJson(
                    query, requestAst, fieldStart, variablesJson, name, kind);
            if (isErrorJson(item)) {
                return item;
            }
            items = appendJsonItem(items, item);
            position = end + 1;
        }
        return "[" + items + "]";
    }

    public static String introspectionObjectFieldsJson(
            String query,
            String requestAst,
            int fieldsStart,
            String variablesJson,
            String descriptor,
            boolean selectionPrevalidated
    ) {
        return introspectionObjectFieldsJson(query, requestAst, fieldsStart, variablesJson, descriptor,
                "fm1;", selectionPrevalidated);
    }

    /** Renders output fields together with generated {@code fm1} descriptions/deprecations. */
    public static String introspectionObjectFieldsJson(
            String query,
            String requestAst,
            int fieldsStart,
            String variablesJson,
            String descriptor,
            String fieldMetadataDescriptor,
            boolean selectionPrevalidated
    ) {
        if (fieldMetadataDescriptor == null || !fieldMetadataDescriptor.startsWith("fm1;")) {
            return errorJsonAt("generated introspection field metadata is malformed", query, fieldsStart);
        }
        if (!selectionPrevalidated && !fieldHasOnlyArgumentsFromAst(query, requestAst, fieldsStart, "")) {
            if (!fieldHasOnlyArgumentsFromAst(query, requestAst, fieldsStart, ",includeDeprecated,")) {
                return errorJsonAt("field '__Type.fields' supports only argument 'includeDeprecated'", query,
                        fieldsStart);
            }
            if (!argumentVariableTypeIsCompatibleWithNullableArgumentFromAst(
                    query, requestAst, fieldsStart, "includeDeprecated", "Boolean")) {
                return errorJsonAt("variable for '__Type.fields.includeDeprecated' must be declared as Boolean or Boolean!",
                        query, fieldsStart);
            }
            String includeDeprecated = argumentValueFromAstMaterialized(
                    query, requestAst, fieldsStart, "includeDeprecated", variablesJson);
            if (includeDeprecated.length() != 0
                    && (argumentValueIsNull(includeDeprecated) || booleanArgument(includeDeprecated) < 0)) {
                return errorJsonAt("argument '__Type.fields.includeDeprecated' must be Boolean", query, fieldsStart);
            }
        }
        String includeDeprecatedValue = argumentValueFromAstMaterialized(
                query, requestAst, fieldsStart, "includeDeprecated", variablesJson);
        boolean includeDeprecated = includeDeprecatedValue.length() != 0
                && booleanArgument(includeDeprecatedValue) == 1;
        // All entries in this descriptor list share one `__Field` selection. Build the
        // fragment-expanded plan once; reopening it for each recognised member multiplies the
        // database-side scanner work by both field count and schema width.
        String fieldSelectionPlan = introspectionSelectionPlanFromAst(query, requestAst, fieldsStart,
                variablesJson, "__Field");
        int nameStart = -1;
        int descriptionStart = -1;
        int deprecatedStart = -1;
        int deprecationReasonStart = -1;
        int argsStart = -1;
        int typeStart = -1;
        int typeNameStart = -1;
        int fieldPlanCount = selectionPlanCount(fieldSelectionPlan);
        int fieldPlanIndex = 0;
        while (fieldPlanIndex < fieldPlanCount) {
            int selectedStart = selectionPlanFieldStart(fieldSelectionPlan, fieldPlanIndex);
            String selectedField = fieldName(query, requestAst, selectedStart);
            if (selectedField.equals("name") && nameStart < 0) nameStart = selectedStart;
            else if (selectedField.equals("description") && descriptionStart < 0) descriptionStart = selectedStart;
            else if (selectedField.equals("isDeprecated") && deprecatedStart < 0) deprecatedStart = selectedStart;
            else if (selectedField.equals("deprecationReason") && deprecationReasonStart < 0) {
                deprecationReasonStart = selectedStart;
            } else if (selectedField.equals("args") && argsStart < 0) argsStart = selectedStart;
            else if (selectedField.equals("type") && typeStart < 0) typeStart = selectedStart;
            else if (selectedField.equals("__typename") && typeNameStart < 0) typeNameStart = selectedStart;
            fieldPlanIndex++;
        }
        int selected = (nameStart >= 0 ? 1 : 0) + (descriptionStart >= 0 ? 1 : 0)
                + (deprecatedStart >= 0 ? 1 : 0) + (deprecationReasonStart >= 0 ? 1 : 0) + (argsStart >= 0 ? 1 : 0)
                + (typeStart >= 0 ? 1 : 0) + (typeNameStart >= 0 ? 1 : 0);
        if (selected == 0) {
            return errorJsonAt("field '__Type.fields' requires a selection set", query, fieldsStart);
        }
        if (selected != fieldPlanCount) {
            return errorJsonAt("unsupported field in __Field selection", query, fieldsStart);
        }
        if (!selectionPrevalidated) {
            int[] leafStarts = {nameStart, descriptionStart, deprecatedStart, deprecationReasonStart, typeNameStart};
            int leafIndex = 0;
            while (leafIndex < leafStarts.length) {
                int leafStart = leafStarts[leafIndex];
                if (leafStart >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, leafStart, "")
                        || !fieldHasNoSelectionSetFromAst(query, requestAst, leafStart))) {
                    return errorJsonAt("introspection scalar field has an invalid selection", query, leafStart);
                }
                leafIndex++;
            }
            if (typeStart >= 0 && !fieldHasOnlyArgumentsFromAst(query, requestAst, typeStart, "")) {
                return errorJsonAt("field '__Field.type' does not support arguments", query, typeStart);
            }
            if (argsStart >= 0) {
                if (!fieldHasOnlyArgumentsFromAst(query, requestAst, argsStart, "")) {
                    if (!fieldHasOnlyArgumentsFromAst(query, requestAst, argsStart, ",includeDeprecated,")) {
                        return errorJsonAt("field '__Field.args' supports only argument 'includeDeprecated'", query,
                                argsStart);
                    }
                    if (!argumentVariableTypeIsCompatibleWithNullableArgumentFromAst(
                            query, requestAst, argsStart, "includeDeprecated", "Boolean")) {
                        return errorJsonAt("variable for '__Field.args.includeDeprecated' must be declared as Boolean or Boolean!",
                                query, argsStart);
                    }
                    String argsIncludeDeprecated = argumentValueFromAstMaterialized(
                            query, requestAst, argsStart, "includeDeprecated", variablesJson);
                    if (argsIncludeDeprecated.length() != 0
                            && (argumentValueIsNull(argsIncludeDeprecated)
                            || booleanArgument(argsIncludeDeprecated) < 0)) {
                        return errorJsonAt("argument '__Field.args.includeDeprecated' must be Boolean", query, argsStart);
                    }
                }
            }
        }
        String argumentSelectionPlan = argsStart < 0 ? "" : introspectionSelectionPlanFromAst(query, requestAst,
                argsStart, variablesJson, "__InputValue");
        int typeRefNameStart = -1;
        int typeRefKindStart = -1;
        int typeRefDescriptionStart = -1;
        int typeRefOfTypeStart = -1;
        int typeRefTypeNameStart = -1;
        if (typeStart >= 0) {
            String typeReferenceSelectionPlan = introspectionSelectionPlanFromAst(query, requestAst, typeStart,
                    variablesJson, "__Type");
            int typeReferencePlanCount = selectionPlanCount(typeReferenceSelectionPlan);
            int typeReferencePlanIndex = 0;
            while (typeReferencePlanIndex < typeReferencePlanCount) {
                int selectedStart = selectionPlanFieldStart(typeReferenceSelectionPlan, typeReferencePlanIndex);
                String selectedField = fieldName(query, requestAst, selectedStart);
                if (selectedField.equals("name") && typeRefNameStart < 0) typeRefNameStart = selectedStart;
                else if (selectedField.equals("kind") && typeRefKindStart < 0) typeRefKindStart = selectedStart;
                else if (selectedField.equals("description") && typeRefDescriptionStart < 0) {
                    typeRefDescriptionStart = selectedStart;
                } else if (selectedField.equals("ofType") && typeRefOfTypeStart < 0) {
                    typeRefOfTypeStart = selectedStart;
                } else if (selectedField.equals("__typename") && typeRefTypeNameStart < 0) {
                    typeRefTypeNameStart = selectedStart;
                }
                typeReferencePlanIndex++;
            }
            int typeRefSelected = (typeRefNameStart >= 0 ? 1 : 0) + (typeRefKindStart >= 0 ? 1 : 0)
                    + (typeRefDescriptionStart >= 0 ? 1 : 0) + (typeRefOfTypeStart >= 0 ? 1 : 0)
                    + (typeRefTypeNameStart >= 0 ? 1 : 0);
            if (typeRefSelected == 0 || typeRefSelected != typeReferencePlanCount) {
                return errorJsonAt("unsupported field in __Type selection", query, typeStart);
            }
            int[] typeRefLeaves = {typeRefNameStart, typeRefKindStart, typeRefDescriptionStart, typeRefTypeNameStart};
            int typeRefLeafIndex = 0;
            while (typeRefLeafIndex < typeRefLeaves.length) {
                int typeRefLeafStart = typeRefLeaves[typeRefLeafIndex];
                if (typeRefLeafStart >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, typeRefLeafStart, "")
                        || !fieldHasNoSelectionSetFromAst(query, requestAst, typeRefLeafStart))) {
                    return errorJsonAt("introspection scalar field has an invalid selection", query, typeRefLeafStart);
                }
                typeRefLeafIndex++;
            }
            if (typeRefOfTypeStart >= 0 && !fieldHasOnlyArgumentsFromAst(query, requestAst, typeRefOfTypeStart, "")) {
                return errorJsonAt("field '__Type.ofType' does not support arguments", query, typeRefOfTypeStart);
            }
        }

        // Response aliases are properties of the one shared request selection, not of a
        // generated schema field. Resolve them once before walking the descriptor. In a broad
        // introspection request this avoids reopening the retained AST hundreds of times.
        String nameKey = nameStart < 0 ? "" : responseKeyFromAst(query, requestAst, nameStart, "name");
        String descriptionKey = descriptionStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, descriptionStart, "description");
        String deprecatedKey = deprecatedStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, deprecatedStart, "isDeprecated");
        String deprecationReasonKey = deprecationReasonStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, deprecationReasonStart, "deprecationReason");
        String argsKey = argsStart < 0 ? "" : responseKeyFromAst(query, requestAst, argsStart, "args");
        String typeKey = typeStart < 0 ? "" : responseKeyFromAst(query, requestAst, typeStart, "type");
        String typeNameKey = typeNameStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, typeNameStart, "__typename");
        String typeRefNameKey = typeRefNameStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, typeRefNameStart, "name");
        String typeRefKindKey = typeRefKindStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, typeRefKindStart, "kind");
        String typeRefDescriptionKey = typeRefDescriptionStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, typeRefDescriptionStart, "description");
        String typeRefOfTypeKey = typeRefOfTypeStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, typeRefOfTypeStart, "ofType");
        String typeRefTypeNameKey = typeRefTypeNameStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, typeRefTypeNameStart, "__typename");

        int nestedNameStart = -1;
        int nestedKindStart = -1;
        int nestedDescriptionStart = -1;
        int nestedOfTypeStart = -1;
        int nestedTypeNameStart = -1;
        if (typeRefOfTypeStart >= 0) {
            String nestedSelectionPlan = introspectionSelectionPlanFromAst(query, requestAst,
                    typeRefOfTypeStart, variablesJson, "__Type");
            int nestedPlanCount = selectionPlanCount(nestedSelectionPlan);
            int nestedPlanIndex = 0;
            while (nestedPlanIndex < nestedPlanCount) {
                int selectedStart = selectionPlanFieldStart(nestedSelectionPlan, nestedPlanIndex);
                String selectedField = fieldName(query, requestAst, selectedStart);
                if (selectedField.equals("name") && nestedNameStart < 0) nestedNameStart = selectedStart;
                else if (selectedField.equals("kind") && nestedKindStart < 0) nestedKindStart = selectedStart;
                else if (selectedField.equals("description") && nestedDescriptionStart < 0) {
                    nestedDescriptionStart = selectedStart;
                } else if (selectedField.equals("ofType") && nestedOfTypeStart < 0) {
                    nestedOfTypeStart = selectedStart;
                } else if (selectedField.equals("__typename") && nestedTypeNameStart < 0) {
                    nestedTypeNameStart = selectedStart;
                }
                nestedPlanIndex++;
            }
            int nestedSelected = (nestedNameStart >= 0 ? 1 : 0) + (nestedKindStart >= 0 ? 1 : 0)
                    + (nestedDescriptionStart >= 0 ? 1 : 0) + (nestedOfTypeStart >= 0 ? 1 : 0)
                    + (nestedTypeNameStart >= 0 ? 1 : 0);
            if (nestedSelected == 0 || nestedSelected != nestedPlanCount) {
                return errorJsonAt("unsupported field in __Type selection", query, typeRefOfTypeStart);
            }
            int[] nestedLeaves = {nestedNameStart, nestedKindStart, nestedDescriptionStart, nestedTypeNameStart};
            int nestedLeafIndex = 0;
            while (nestedLeafIndex < nestedLeaves.length) {
                int nestedLeafStart = nestedLeaves[nestedLeafIndex];
                if (nestedLeafStart >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, nestedLeafStart, "")
                        || !fieldHasNoSelectionSetFromAst(query, requestAst, nestedLeafStart))) {
                    return errorJsonAt("introspection scalar field has an invalid selection", query,
                            nestedLeafStart);
                }
                nestedLeafIndex++;
            }
            if (nestedOfTypeStart >= 0 && !fieldHasOnlyArgumentsFromAst(query, requestAst,
                    nestedOfTypeStart, "")) {
                return errorJsonAt("field '__Type.ofType' does not support arguments", query,
                        nestedOfTypeStart);
            }
        }
        String nestedNameKey = nestedNameStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, nestedNameStart, "name");
        String nestedKindKey = nestedKindStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, nestedKindStart, "kind");
        String nestedDescriptionKey = nestedDescriptionStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, nestedDescriptionStart, "description");
        String nestedOfTypeKey = nestedOfTypeStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, nestedOfTypeStart, "ofType");
        String nestedTypeNameKey = nestedTypeNameStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, nestedTypeNameStart, "__typename");

        // An empty render validates the shared args selection once. Fields without arguments can
        // then emit [] directly instead of repeating the same validation for every descriptor row.
        if (argsStart >= 0) {
            String validatedEmptyArguments = introspectionInputValuesJson(query, requestAst, argsStart,
                    variablesJson, "", argumentSelectionPlan, "__Field.args");
            if (isErrorJson(validatedEmptyArguments)) {
                return validatedEmptyArguments;
            }
        }

        String items = "";
        int position = 0;
        int metadataPosition = 4;
        boolean hasFieldMetadata = fieldMetadataDescriptor.length() > metadataPosition;
        while (descriptor != null && position < descriptor.length()) {
            int end = descriptor.indexOf(';', position);
            int first = descriptor.indexOf(':', position);
            int second = first < 0 ? -1 : descriptor.indexOf(':', first + 1);
            int third = second < 0 ? -1 : descriptor.indexOf(':', second + 1);
            if (end <= position || first <= position || second <= first || third <= second || third + 1 >= end) {
                return errorJsonAt("generated introspection field metadata is malformed", query, fieldsStart);
            }
            String fieldName = descriptor.substring(position, first);
            String scalarType = descriptor.substring(first + 1, second);
            String nullableMarker = descriptor.substring(second + 1, third);
            int argumentMarker = descriptor.indexOf('|', third + 1);
            if (argumentMarker >= end) {
                argumentMarker = -1;
            }
            String typeKindMarker = descriptor.substring(third + 1, argumentMarker < 0 ? end : argumentMarker);
            String argumentDescriptor = argumentMarker < 0 ? "" : descriptor.substring(argumentMarker + 1, end);
            String fieldDescription = "";
            boolean fieldDeprecated = false;
            String fieldDeprecationReason = "";
            if (hasFieldMetadata) {
                int metadataNameLengthEnd = fieldMetadataDescriptor.indexOf(':', metadataPosition);
                int metadataNameLength = inputDescriptorDecimal(
                        fieldMetadataDescriptor, metadataPosition, metadataNameLengthEnd);
                int metadataNameStart = metadataNameLengthEnd < 0 ? -1 : metadataNameLengthEnd + 1;
                int metadataNameEnd = metadataNameStart < 0 || metadataNameLength < 0
                        ? -1 : metadataNameStart + metadataNameLength;
                int descriptionLengthEnd = metadataNameEnd < 0
                        ? -1 : fieldMetadataDescriptor.indexOf(':', metadataNameEnd);
                int descriptionLength = inputDescriptorDecimal(
                        fieldMetadataDescriptor, metadataNameEnd, descriptionLengthEnd);
                int descriptionValueStart = descriptionLengthEnd < 0 ? -1 : descriptionLengthEnd + 1;
                int descriptionValueEnd = descriptionValueStart < 0 || descriptionLength < 0
                        ? -1 : descriptionValueStart + descriptionLength;
                int deprecatedPosition = descriptionValueEnd;
                int reasonLengthStart = deprecatedPosition < 0 ? -1 : deprecatedPosition + 1;
                int reasonLengthEnd = reasonLengthStart < 0
                        ? -1 : fieldMetadataDescriptor.indexOf(':', reasonLengthStart);
                int reasonLength = inputDescriptorDecimal(
                        fieldMetadataDescriptor, reasonLengthStart, reasonLengthEnd);
                int reasonStart = reasonLengthEnd < 0 ? -1 : reasonLengthEnd + 1;
                int reasonEnd = reasonStart < 0 || reasonLength < 0 ? -1 : reasonStart + reasonLength;
                if (metadataNameStart < 0 || metadataNameEnd > fieldMetadataDescriptor.length()
                        || descriptionValueStart < 0 || descriptionValueEnd > fieldMetadataDescriptor.length()
                        || deprecatedPosition < 0 || deprecatedPosition >= fieldMetadataDescriptor.length()
                        || (fieldMetadataDescriptor.charAt(deprecatedPosition) != '0'
                        && fieldMetadataDescriptor.charAt(deprecatedPosition) != '1')
                        || reasonStart < 0 || reasonEnd >= fieldMetadataDescriptor.length()
                        || fieldMetadataDescriptor.charAt(reasonEnd) != ';'
                        || !fieldMetadataDescriptor.substring(metadataNameStart, metadataNameEnd).equals(fieldName)) {
                    return errorJsonAt("generated introspection field metadata is malformed", query, fieldsStart);
                }
                fieldDescription = fieldMetadataDescriptor.substring(descriptionValueStart, descriptionValueEnd);
                fieldDeprecated = fieldMetadataDescriptor.charAt(deprecatedPosition) == '1';
                fieldDeprecationReason = fieldMetadataDescriptor.substring(reasonStart, reasonEnd);
                if (!fieldDeprecated && fieldDeprecationReason.length() != 0) {
                    return errorJsonAt("generated introspection field metadata is malformed", query, fieldsStart);
                }
                if (fieldDeprecated && fieldDeprecationReason.length() == 0) {
                    fieldDeprecationReason = "No longer supported";
                }
                metadataPosition = reasonEnd + 1;
            }
            boolean referenceTypeMarker = typeKindMarker.length() == 2
                    && typeKindMarker.charAt(0) == 'R'
                    && introspectionNamedKindIsValid(typeKindMarker.substring(1));
            boolean referenceNullabilityMatches = !referenceTypeMarker
                    || nullableMarker.equals(scalarType.endsWith("!") ? "0" : "1");
            if (fieldName.length() == 0 || scalarType.length() == 0
                    || (!nullableMarker.equals("0") && !nullableMarker.equals("1"))
                    || (!introspectionNamedKindIsValid(typeKindMarker)
                    && !typeKindMarker.equals("LNO") && !referenceTypeMarker)
                    || !referenceNullabilityMatches
                    || (argumentMarker >= 0 && argumentDescriptor.length() == 0)) {
                return errorJsonAt("generated introspection field metadata is malformed", query, fieldsStart);
            }
            if (fieldDeprecated && !includeDeprecated) {
                position = end + 1;
                continue;
            }
            String members = "";
            if (nameStart >= 0) {
                members = appendJsonMember(members, nameKey, jsonString(fieldName));
            }
            if (descriptionStart >= 0) {
                members = appendJsonMember(members, descriptionKey,
                        fieldDescription.length() == 0 ? "null" : jsonString(fieldDescription));
            }
            if (deprecatedStart >= 0) {
                members = appendJsonMember(members, deprecatedKey, fieldDeprecated ? "true" : "false");
            }
            if (deprecationReasonStart >= 0) {
                members = appendJsonMember(members, deprecationReasonKey,
                        fieldDeprecated ? jsonString(fieldDeprecationReason) : "null");
            }
            if (argsStart >= 0) {
                String arguments = "[]";
                if (argumentDescriptor.length() != 0) {
                    arguments = introspectionInputValuesJson(query, requestAst, argsStart, variablesJson,
                            argumentDescriptor, argumentSelectionPlan, "__Field.args");
                    if (isErrorJson(arguments)) {
                        return arguments;
                    }
                }
                members = appendJsonMember(members, argsKey, arguments);
            }
            if (typeStart >= 0) {
                if (referenceTypeMarker) {
                    String type = introspectionTypeReferenceJson(query, requestAst, typeStart, variablesJson,
                            scalarType, typeKindMarker.substring(1));
                    if (isErrorJson(type)) {
                        return type;
                    }
                    members = appendJsonMember(members, typeKey, type);
                } else if (typeKindMarker.equals("LNO")) {
                    if (!nullableMarker.equals("0")) {
                        return errorJsonAt("generated introspection field metadata is malformed", query, fieldsStart);
                    }
                    String type = introspectionNonNullListNonNullObjectTypeReferenceJson(query, requestAst,
                            typeStart, variablesJson, scalarType);
                    if (isErrorJson(type)) {
                        return type;
                    }
                    members = appendJsonMember(members, typeKey, type);
                } else {
                    boolean nonNull = nullableMarker.equals("0");
                    String namedKind = introspectionNamedKindName(typeKindMarker);
                    String typeMembers = "";
                if (typeRefNameStart >= 0) typeMembers = appendJsonMember(typeMembers, typeRefNameKey,
                        nonNull ? "null" : jsonString(scalarType));
                if (typeRefKindStart >= 0) typeMembers = appendJsonMember(typeMembers, typeRefKindKey,
                        jsonString(nonNull ? "NON_NULL" : namedKind));
                if (typeRefDescriptionStart >= 0) typeMembers = appendJsonMember(typeMembers,
                        typeRefDescriptionKey, "null");
                if (typeRefOfTypeStart >= 0) {
                    String nested = "null";
                    if (nonNull) {
                        String nestedMembers = "";
                        if (nestedNameStart >= 0) nestedMembers = appendJsonMember(nestedMembers,
                                nestedNameKey, jsonString(scalarType));
                        if (nestedKindStart >= 0) nestedMembers = appendJsonMember(nestedMembers,
                                nestedKindKey, jsonString(namedKind));
                        if (nestedDescriptionStart >= 0) nestedMembers = appendJsonMember(nestedMembers,
                                nestedDescriptionKey, "null");
                        if (nestedOfTypeStart >= 0) nestedMembers = appendJsonMember(nestedMembers,
                                nestedOfTypeKey, "null");
                        if (nestedTypeNameStart >= 0) nestedMembers = appendJsonMember(nestedMembers,
                                nestedTypeNameKey, jsonString("__Type"));
                        nested = "{" + nestedMembers + "}";
                    }
                    typeMembers = appendJsonMember(typeMembers, typeRefOfTypeKey, nested);
                }
                if (typeRefTypeNameStart >= 0) typeMembers = appendJsonMember(typeMembers,
                        typeRefTypeNameKey, jsonString("__Type"));
                String type = "{" + typeMembers + "}";
                members = appendJsonMember(members, typeKey, type);
                }
            }
            if (typeNameStart >= 0) {
                members = appendJsonMember(members, typeNameKey, jsonString("__Field"));
            }
            items = appendJsonItem(items, "{" + members + "}");
            position = end + 1;
        }
        if (hasFieldMetadata && metadataPosition != fieldMetadataDescriptor.length()) {
            return errorJsonAt("generated introspection field metadata is malformed", query, fieldsStart);
        }
        return "[" + items + "]";
    }

    /**
     * Renders generator-described {@code __InputValue} entries for {@code __Field.args}.
     * The descriptor uses {@code name=Type=kind} entries separated by commas and optionally adds
     * {@code =defaultValue}. Generated schema defaults are GraphQL source text and are returned as
     * strings, while absent defaults and all current deprecation reasons remain explicit nulls.
     */
    public static String introspectionInputValuesJson(
            String query,
            String requestAst,
            int inputValuesStart,
            String variablesJson,
            String descriptor
    ) {
        return introspectionInputValuesJson(query, requestAst, inputValuesStart, variablesJson, descriptor,
                "__Field.args", false);
    }

    /**
     * Renders an input-value list owned by a standard introspection field. The owner comes from
     * generated schema code, so diagnostics distinguish {@code __Field.args} and
     * {@code __Type.inputFields} without using request-derived text.
     */
    public static String introspectionInputValuesJson(
            String query,
            String requestAst,
            int inputValuesStart,
            String variablesJson,
            String descriptor,
            String ownerField
    ) {
        return introspectionInputValuesJson(query, requestAst, inputValuesStart, variablesJson, descriptor,
                ownerField, false);
    }

    /**
     * Renders input metadata with the argument contract owned by the generated introspection
     * field. Only {@code __Type.inputFields} currently supplies {@code includeDeprecated}; its
     * value is validated here, after request-local variable materialization, so the HTTP adapter
     * never evaluates GraphQL arguments.
     */
    public static String introspectionInputValuesJson(
            String query,
            String requestAst,
            int inputValuesStart,
            String variablesJson,
            String descriptor,
            String ownerField,
            boolean supportsIncludeDeprecated
    ) {
        boolean includeDeprecatedValues = false;
        if (supportsIncludeDeprecated) {
            if (!fieldHasOnlyArgumentsFromAst(query, requestAst, inputValuesStart, "")) {
                if (!fieldHasOnlyArgumentsFromAst(query, requestAst, inputValuesStart, ",includeDeprecated,")) {
                    return errorJsonAt("field '" + ownerField + "' supports only argument 'includeDeprecated'", query,
                            inputValuesStart);
                }
                if (!argumentVariableTypeIsCompatibleWithNullableArgumentFromAst(
                        query, requestAst, inputValuesStart, "includeDeprecated", "Boolean")) {
                    return errorJsonAt("variable for '" + ownerField
                            + ".includeDeprecated' must be declared as Boolean or Boolean!", query, inputValuesStart);
                }
                String includeDeprecated = argumentValueFromAstMaterialized(
                        query, requestAst, inputValuesStart, "includeDeprecated", variablesJson);
                if (includeDeprecated.length() != 0
                        && (argumentValueIsNull(includeDeprecated) || booleanArgument(includeDeprecated) < 0)) {
                    return errorJsonAt("argument '" + ownerField + ".includeDeprecated' must be Boolean", query,
                            inputValuesStart);
                }
                includeDeprecatedValues = booleanArgument(includeDeprecated) == 1;
            }
        } else if (!fieldHasOnlyArgumentsFromAst(query, requestAst, inputValuesStart, "")) {
            return errorJsonAt("field '" + ownerField + "' does not support arguments", query, inputValuesStart);
        }
        return introspectionInputValuesJson(query, requestAst, inputValuesStart, variablesJson, descriptor,
                introspectionSelectionPlanFromAst(query, requestAst, inputValuesStart, variablesJson, "__InputValue"),
                ownerField, includeDeprecatedValues);
    }

    /** Uses an already-expanded scalar selection plan when one parent field has many input values. */
    private static String introspectionInputValuesJson(
            String query,
            String requestAst,
            int inputValuesStart,
            String variablesJson,
            String descriptor,
            String selectionPlan,
            String ownerField
    ) {
        return introspectionInputValuesJson(query, requestAst, inputValuesStart, variablesJson, descriptor,
                selectionPlan, ownerField, false);
    }

    private static String introspectionInputValuesJson(
            String query,
            String requestAst,
            int inputValuesStart,
            String variablesJson,
            String descriptor,
            String selectionPlan,
            String ownerField,
            boolean includeDeprecatedValues
    ) {
        int nameStart = -1;
        int descriptionStart = -1;
        int typeStart = -1;
        int defaultValueStart = -1;
        int deprecatedStart = -1;
        int deprecationReasonStart = -1;
        int typeNameStart = -1;
        int planCount = selectionPlanCount(selectionPlan);
        int planIndex = 0;
        while (planIndex < planCount) {
            int selectedStart = selectionPlanFieldStart(selectionPlan, planIndex);
            String selectedField = fieldName(query, requestAst, selectedStart);
            if (selectedField.equals("name") && nameStart < 0) nameStart = selectedStart;
            else if (selectedField.equals("description") && descriptionStart < 0) descriptionStart = selectedStart;
            else if (selectedField.equals("type") && typeStart < 0) typeStart = selectedStart;
            else if (selectedField.equals("defaultValue") && defaultValueStart < 0) {
                defaultValueStart = selectedStart;
            } else if (selectedField.equals("isDeprecated") && deprecatedStart < 0) {
                deprecatedStart = selectedStart;
            } else if (selectedField.equals("deprecationReason") && deprecationReasonStart < 0) {
                deprecationReasonStart = selectedStart;
            } else if (selectedField.equals("__typename") && typeNameStart < 0) typeNameStart = selectedStart;
            planIndex++;
        }
        int selected = (nameStart >= 0 ? 1 : 0) + (descriptionStart >= 0 ? 1 : 0) + (typeStart >= 0 ? 1 : 0)
                + (defaultValueStart >= 0 ? 1 : 0) + (deprecatedStart >= 0 ? 1 : 0)
                + (deprecationReasonStart >= 0 ? 1 : 0) + (typeNameStart >= 0 ? 1 : 0);
        if (selected == 0) {
            return errorJsonAt("field '" + ownerField + "' requires a selection set", query, inputValuesStart);
        }
        if (selected != planCount) {
            return errorJsonAt("unsupported field in __InputValue selection", query, inputValuesStart);
        }
        int[] leaves = {nameStart, descriptionStart, defaultValueStart, deprecatedStart, deprecationReasonStart,
                typeNameStart};
        int leafIndex = 0;
        while (leafIndex < leaves.length) {
            int leafStart = leaves[leafIndex];
            if (leafStart >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, leafStart, "")
                    || !fieldHasNoSelectionSetFromAst(query, requestAst, leafStart))) {
                return errorJsonAt("introspection scalar field has an invalid selection", query, leafStart);
            }
            leafIndex++;
        }
        if (typeStart >= 0 && !fieldHasOnlyArgumentsFromAst(query, requestAst, typeStart, "")) {
            return errorJsonAt("field '__InputValue.type' does not support arguments", query, typeStart);
        }

        // Every descriptor entry has the same selected response shape. Alias lookup reaches into
        // the retained AST, so keep it outside the metadata loop just as the selection plan is.
        String nameKey = nameStart < 0 ? "" : responseKeyFromAst(query, requestAst, nameStart, "name");
        String descriptionKey = descriptionStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, descriptionStart, "description");
        String typeKey = typeStart < 0 ? "" : responseKeyFromAst(query, requestAst, typeStart, "type");
        String defaultValueKey = defaultValueStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, defaultValueStart, "defaultValue");
        String deprecatedKey = deprecatedStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, deprecatedStart, "isDeprecated");
        String deprecationReasonKey = deprecationReasonStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, deprecationReasonStart, "deprecationReason");
        String typeNameKey = typeNameStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, typeNameStart, "__typename");

        if (descriptor != null && descriptor.startsWith("iv1;")) {
            String versionedItems = "";
            int versionedPosition = 4;
            while (versionedPosition < descriptor.length()) {
                int nameLengthEnd = descriptor.indexOf(':', versionedPosition);
                int nameLength = inputDescriptorDecimal(descriptor, versionedPosition, nameLengthEnd);
                int nameValueStart = nameLengthEnd < 0 ? -1 : nameLengthEnd + 1;
                int nameValueEnd = nameValueStart < 0 || nameLength < 0 ? -1 : nameValueStart + nameLength;
                int typeLengthEnd = nameValueEnd < 0 || nameValueEnd > descriptor.length()
                        ? -1 : descriptor.indexOf(':', nameValueEnd);
                int typeLength = inputDescriptorDecimal(descriptor, nameValueEnd, typeLengthEnd);
                int typeValueStart = typeLengthEnd < 0 ? -1 : typeLengthEnd + 1;
                int typeValueEnd = typeValueStart < 0 || typeLength < 0 ? -1 : typeValueStart + typeLength;
                int kindLengthEnd = typeValueEnd < 0 || typeValueEnd > descriptor.length()
                        ? -1 : descriptor.indexOf(':', typeValueEnd);
                int kindLength = inputDescriptorDecimal(descriptor, typeValueEnd, kindLengthEnd);
                int kindValueStart = kindLengthEnd < 0 ? -1 : kindLengthEnd + 1;
                int kindValueEnd = kindValueStart < 0 || kindLength < 0 ? -1 : kindValueStart + kindLength;
                int defaultPresentPosition = kindValueEnd;
                int defaultLengthStart = defaultPresentPosition < 0 ? -1 : defaultPresentPosition + 1;
                int defaultLengthEnd = defaultLengthStart < 0 || defaultLengthStart > descriptor.length()
                        ? -1 : descriptor.indexOf(':', defaultLengthStart);
                int defaultLength = inputDescriptorDecimal(descriptor, defaultLengthStart, defaultLengthEnd);
                int defaultStart = defaultLengthEnd < 0 ? -1 : defaultLengthEnd + 1;
                int defaultEnd = defaultStart < 0 || defaultLength < 0 ? -1 : defaultStart + defaultLength;
                int descriptionLengthEnd = defaultEnd < 0 || defaultEnd > descriptor.length()
                        ? -1 : descriptor.indexOf(':', defaultEnd);
                int descriptionLength = inputDescriptorDecimal(descriptor, defaultEnd, descriptionLengthEnd);
                int descriptionValueStart = descriptionLengthEnd < 0 ? -1 : descriptionLengthEnd + 1;
                int descriptionValueEnd = descriptionValueStart < 0 || descriptionLength < 0
                        ? -1 : descriptionValueStart + descriptionLength;
                int deprecatedPosition = descriptionValueEnd;
                int reasonLengthStart = deprecatedPosition < 0 ? -1 : deprecatedPosition + 1;
                int reasonLengthEnd = reasonLengthStart < 0 || reasonLengthStart > descriptor.length()
                        ? -1 : descriptor.indexOf(':', reasonLengthStart);
                int reasonLength = inputDescriptorDecimal(descriptor, reasonLengthStart, reasonLengthEnd);
                int reasonStart = reasonLengthEnd < 0 ? -1 : reasonLengthEnd + 1;
                int reasonEnd = reasonStart < 0 || reasonLength < 0 ? -1 : reasonStart + reasonLength;
                if (nameValueEnd <= nameValueStart || typeValueEnd <= typeValueStart
                        || kindValueEnd <= kindValueStart || defaultPresentPosition < 0
                        || defaultPresentPosition >= descriptor.length()
                        || descriptor.charAt(defaultPresentPosition) != '0'
                        && descriptor.charAt(defaultPresentPosition) != '1'
                        || defaultEnd < defaultStart || descriptionValueEnd < descriptionValueStart
                        || deprecatedPosition < 0 || deprecatedPosition >= descriptor.length()
                        || descriptor.charAt(deprecatedPosition) != '0'
                        && descriptor.charAt(deprecatedPosition) != '1'
                        || reasonEnd < reasonStart || reasonEnd > descriptor.length()) {
                    return errorJsonAt(
                            "generated introspection input metadata is malformed", query, inputValuesStart);
                }
                String name = descriptor.substring(nameValueStart, nameValueEnd);
                String type = descriptor.substring(typeValueStart, typeValueEnd);
                String kind = descriptor.substring(kindValueStart, kindValueEnd);
                boolean hasDefault = descriptor.charAt(defaultPresentPosition) == '1';
                String defaultValue = descriptor.substring(defaultStart, defaultEnd);
                String description = descriptor.substring(descriptionValueStart, descriptionValueEnd);
                boolean deprecated = descriptor.charAt(deprecatedPosition) == '1';
                String deprecationReason = descriptor.substring(reasonStart, reasonEnd);
                if (!introspectionNamedKindIsValid(kind) || hasDefault && defaultValue.length() == 0
                        || !hasDefault && defaultValue.length() != 0
                        || !deprecated && deprecationReason.length() != 0) {
                    return errorJsonAt(
                            "generated introspection input metadata is malformed", query, inputValuesStart);
                }
                String members = "";
                if (nameStart >= 0) members = appendJsonMember(members, nameKey, jsonString(name));
                if (descriptionStart >= 0) members = appendJsonMember(members, descriptionKey,
                        description.length() == 0 ? "null" : jsonString(description));
                if (typeStart >= 0) {
                    String typeValue = introspectionTypeReferenceJson(
                            query, requestAst, typeStart, variablesJson, type, kind);
                    if (isErrorJson(typeValue)) return typeValue;
                    members = appendJsonMember(members, typeKey, typeValue);
                }
                if (defaultValueStart >= 0) members = appendJsonMember(members, defaultValueKey,
                        hasDefault ? jsonString(defaultValue) : "null");
                if (deprecatedStart >= 0) members = appendJsonMember(
                        members, deprecatedKey, deprecated ? "true" : "false");
                if (deprecationReasonStart >= 0) members = appendJsonMember(members, deprecationReasonKey,
                        deprecationReason.length() == 0 ? "null" : jsonString(deprecationReason));
                if (typeNameStart >= 0) members = appendJsonMember(
                        members, typeNameKey, jsonString("__InputValue"));
                if (includeDeprecatedValues || !deprecated) {
                    versionedItems = appendJsonItem(versionedItems, "{" + members + "}");
                }
                versionedPosition = reasonEnd;
            }
            return "[" + versionedItems + "]";
        }

        String items = "";
        int position = 0;
        while (descriptor != null && position < descriptor.length()) {
            int end = descriptor.indexOf(',', position);
            if (end < 0) {
                end = descriptor.length();
            }
            int first = descriptor.indexOf('=', position);
            int second = first < 0 ? -1 : descriptor.indexOf('=', first + 1);
            if (end <= position || first <= position || second <= first || second + 1 >= end) {
                return errorJsonAt("generated introspection input metadata is malformed", query, inputValuesStart);
            }
            int third = descriptor.indexOf('=', second + 1);
            if (third >= end) {
                third = -1;
            }
            String name = descriptor.substring(position, first);
            String type = descriptor.substring(first + 1, second);
            String kind = descriptor.substring(second + 1, third < 0 ? end : third);
            String encodedDefaultValue = third < 0 ? "" : descriptor.substring(third + 1, end);
            String defaultValue = decodedSchemaDefaultValue(encodedDefaultValue);
            if (name.length() == 0 || type.length() == 0 || !introspectionNamedKindIsValid(kind)
                    || (third >= 0 && (encodedDefaultValue.length() == 0 || defaultValue.length() == 0))) {
                return errorJsonAt("generated introspection input metadata is malformed", query, inputValuesStart);
            }
            String members = "";
            if (nameStart >= 0) {
                members = appendJsonMember(members, nameKey, jsonString(name));
            }
            if (descriptionStart >= 0) {
                members = appendJsonMember(members, descriptionKey, "null");
            }
            if (typeStart >= 0) {
                String typeValue = introspectionTypeReferenceJson(query, requestAst, typeStart, variablesJson, type, kind);
                if (isErrorJson(typeValue)) {
                    return typeValue;
                }
                members = appendJsonMember(members, typeKey, typeValue);
            }
            if (defaultValueStart >= 0) {
                members = appendJsonMember(members, defaultValueKey,
                        defaultValue.length() == 0 ? "null" : jsonString(defaultValue));
            }
            if (deprecatedStart >= 0) {
                members = appendJsonMember(members, deprecatedKey, "false");
            }
            if (deprecationReasonStart >= 0) {
                members = appendJsonMember(members, deprecationReasonKey, "null");
            }
            if (typeNameStart >= 0) {
                members = appendJsonMember(members, typeNameKey, jsonString("__InputValue"));
            }
            items = appendJsonItem(items, "{" + members + "}");
            position = end + 1;
        }
        return "[" + items + "]";
    }

    /**
     * Renders the executable directives implemented by the database language engine. The
     * metadata is deliberately shared and static: generated schemas do not need a JVM schema or
     * a model-specific callback to expose the standard {@code include}/{@code skip} contract.
     */
    public static String introspectionDirectivesJson(
            String query,
            String requestAst,
            int directivesStart,
            String variablesJson
    ) {
        return introspectionDirectivesJson(query, requestAst, directivesStart, variablesJson, "dd1;");
    }

    /** Renders built-in directives plus generator-registered conditional directive metadata. */
    public static String introspectionDirectivesJson(
            String query,
            String requestAst,
            int directivesStart,
            String variablesJson,
            String customDirectiveDescriptor
    ) {
        if (!fieldHasOnlyArgumentsFromAst(query, requestAst, directivesStart, "")) {
            return errorJsonAt("field '__Schema.directives' does not support arguments", query, directivesStart);
        }
        String selectionPlan = introspectionSelectionPlanFromAst(
                query, requestAst, directivesStart, variablesJson, "__Directive");
        int nameStart = introspectionSelectionPlanFieldStart(query, requestAst, selectionPlan, "name");
        int descriptionStart = introspectionSelectionPlanFieldStart(query, requestAst, selectionPlan, "description");
        int repeatableStart = introspectionSelectionPlanFieldStart(query, requestAst, selectionPlan, "isRepeatable");
        int locationsStart = introspectionSelectionPlanFieldStart(query, requestAst, selectionPlan, "locations");
        int argsStart = introspectionSelectionPlanFieldStart(query, requestAst, selectionPlan, "args");
        int typeNameStart = introspectionSelectionPlanFieldStart(query, requestAst, selectionPlan, "__typename");
        int selected = (nameStart >= 0 ? 1 : 0) + (descriptionStart >= 0 ? 1 : 0)
                + (repeatableStart >= 0 ? 1 : 0) + (locationsStart >= 0 ? 1 : 0)
                + (argsStart >= 0 ? 1 : 0) + (typeNameStart >= 0 ? 1 : 0);
        if (selected == 0) {
            return errorJsonAt("field '__Schema.directives' requires a selection set", query, directivesStart);
        }
        if (selected != selectionPlanCount(selectionPlan)) {
            return errorJsonAt("unsupported field in __Directive selection", query, directivesStart);
        }
        int[] leaves = {nameStart, descriptionStart, repeatableStart, locationsStart, typeNameStart};
        int leafIndex = 0;
        while (leafIndex < leaves.length) {
            int leafStart = leaves[leafIndex];
            if (leafStart >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, leafStart, "")
                    || !fieldHasNoSelectionSetFromAst(query, requestAst, leafStart))) {
                return errorJsonAt("introspection directive scalar field has an invalid selection", query,
                        leafStart);
            }
            leafIndex++;
        }
        if (argsStart >= 0) {
            if (!fieldHasOnlyArgumentsFromAst(query, requestAst, argsStart, "")) {
                if (!fieldHasOnlyArgumentsFromAst(query, requestAst, argsStart, ",includeDeprecated,")) {
                    return errorJsonAt("field '__Directive.args' supports only argument 'includeDeprecated'", query,
                            argsStart);
                }
                if (!argumentVariableTypeIsCompatibleWithNullableArgumentFromAst(
                        query, requestAst, argsStart, "includeDeprecated", "Boolean")) {
                    return errorJsonAt("variable for '__Directive.args.includeDeprecated' must be declared as Boolean or Boolean!",
                            query, argsStart);
                }
                String argsIncludeDeprecated = argumentValueFromAstMaterialized(
                        query, requestAst, argsStart, "includeDeprecated", variablesJson);
                if (argsIncludeDeprecated.length() != 0
                        && (argumentValueIsNull(argsIncludeDeprecated)
                        || booleanArgument(argsIncludeDeprecated) < 0)) {
                    return errorJsonAt("argument '__Directive.args.includeDeprecated' must be Boolean", query,
                            argsStart);
                }
            }
        }

        String argumentSelectionPlan = argsStart < 0 ? "" : introspectionSelectionPlanFromAst(
                query, requestAst, argsStart, variablesJson, "__InputValue");
        String directiveArguments = "[]";
        if (argsStart >= 0) {
            directiveArguments = introspectionInputValuesJson(query, requestAst, argsStart, variablesJson,
                    "if=Boolean!=S", argumentSelectionPlan, "__Directive.args");
            if (isErrorJson(directiveArguments)) {
                return directiveArguments;
            }
        }
        String nameKey = nameStart < 0 ? "" : responseKeyFromAst(query, requestAst, nameStart, "name");
        String descriptionKey = descriptionStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, descriptionStart, "description");
        String repeatableKey = repeatableStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, repeatableStart, "isRepeatable");
        String locationsKey = locationsStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, locationsStart, "locations");
        String argsKey = argsStart < 0 ? "" : responseKeyFromAst(query, requestAst, argsStart, "args");
        String typeNameKey = typeNameStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, typeNameStart, "__typename");
        if (customDirectiveDescriptor == null || !customDirectiveDescriptor.startsWith("dd1;")) {
            return errorJson("installed directive descriptor is invalid");
        }
        String descriptor = "dd1;7:include0:I3:FPI4:skip0:S3:FPI"
                + customDirectiveDescriptor.substring(4);
        String items = "";
        int position = 4;
        while (position < descriptor.length()) {
            int nameLengthEnd = descriptor.indexOf(':', position);
            int nameLength = inputDescriptorDecimal(descriptor, position, nameLengthEnd);
            int descriptorNameStart = nameLengthEnd + 1;
            int descriptorNameEnd = nameLength < 0 ? -1 : descriptorNameStart + nameLength;
            int descriptionLengthEnd = descriptorNameEnd < 0 || descriptorNameEnd > descriptor.length()
                    ? -1 : descriptor.indexOf(':', descriptorNameEnd);
            int descriptionLength = inputDescriptorDecimal(
                    descriptor, descriptorNameEnd, descriptionLengthEnd);
            int descriptorDescriptionStart = descriptionLengthEnd + 1;
            int descriptorDescriptionEnd = descriptionLength < 0
                    ? -1 : descriptorDescriptionStart + descriptionLength;
            int behaviorPosition = descriptorDescriptionEnd;
            int locationsLengthEnd = behaviorPosition < 0 || behaviorPosition >= descriptor.length()
                    ? -1 : descriptor.indexOf(':', behaviorPosition + 1);
            int locationsLength = inputDescriptorDecimal(
                    descriptor, behaviorPosition + 1, locationsLengthEnd);
            int locationsValueStart = locationsLengthEnd + 1;
            int locationsValueEnd = locationsLength < 0 ? -1 : locationsValueStart + locationsLength;
            if (nameLengthEnd < position || nameLength < 1 || descriptorNameEnd > descriptor.length()
                    || descriptionLengthEnd < descriptorNameEnd || descriptionLength < 0
                    || descriptorDescriptionEnd > descriptor.length() || behaviorPosition >= descriptor.length()
                    || (descriptor.charAt(behaviorPosition) != 'I'
                    && descriptor.charAt(behaviorPosition) != 'S')
                    || locationsLengthEnd <= behaviorPosition || locationsLength < 1
                    || locationsValueEnd > descriptor.length()) {
                return errorJson("installed directive descriptor is invalid");
            }
            String directiveName = descriptor.substring(descriptorNameStart, descriptorNameEnd);
            String directiveDescription = descriptor.substring(
                    descriptorDescriptionStart, descriptorDescriptionEnd);
            String locationMarkers = descriptor.substring(locationsValueStart, locationsValueEnd);
            String locationsJson = "";
            if (locationMarkers.indexOf('F') >= 0) {
                locationsJson = appendJsonItem(locationsJson, jsonString("FIELD"));
            }
            if (locationMarkers.indexOf('P') >= 0) {
                locationsJson = appendJsonItem(locationsJson, jsonString("FRAGMENT_SPREAD"));
            }
            if (locationMarkers.indexOf('I') >= 0) {
                locationsJson = appendJsonItem(locationsJson, jsonString("INLINE_FRAGMENT"));
            }
            if (locationsJson.length() == 0) {
                return errorJson("installed directive descriptor is invalid");
            }
            String members = "";
            if (nameStart >= 0) {
                members = appendJsonMember(members, nameKey, jsonString(directiveName));
            }
            if (descriptionStart >= 0) {
                members = appendJsonMember(members, descriptionKey,
                        directiveDescription.length() == 0 ? "null" : jsonString(directiveDescription));
            }
            if (repeatableStart >= 0) {
                members = appendJsonMember(members, repeatableKey, "false");
            }
            if (locationsStart >= 0) {
                members = appendJsonMember(members, locationsKey, "[" + locationsJson + "]");
            }
            if (argsStart >= 0) {
                members = appendJsonMember(members, argsKey, directiveArguments);
            }
            if (typeNameStart >= 0) {
                members = appendJsonMember(members, typeNameKey, jsonString("__Directive"));
            }
            items = appendJsonItem(items, "{" + members + "}");
            position = locationsValueEnd;
        }
        return "[" + items + "]";
    }

    /**
     * Renders generator-owned {@code __EnumValue} metadata for {@code __Type.enumValues}. The
     * versioned length-prefixed descriptor is compile-time schema data, so descriptions and
     * deprecation reasons may contain arbitrary delimiters without a runtime model parser,
     * reflection, or a model-specific callback.
     */
    public static String introspectionEnumValuesJson(
            String query,
            String requestAst,
            int enumValuesStart,
            String variablesJson,
            String descriptor
    ) {
        boolean includeDeprecatedValues = false;
        if (!fieldHasOnlyArgumentsFromAst(query, requestAst, enumValuesStart, "")) {
            if (!fieldHasOnlyArgumentsFromAst(query, requestAst, enumValuesStart, ",includeDeprecated,")) {
                return errorJsonAt("field '__Type.enumValues' supports only argument 'includeDeprecated'", query,
                        enumValuesStart);
            }
            if (!argumentVariableTypeIsCompatibleWithNullableArgumentFromAst(
                    query, requestAst, enumValuesStart, "includeDeprecated", "Boolean")) {
                return errorJsonAt("variable for '__Type.enumValues.includeDeprecated' must be declared as Boolean or Boolean!",
                        query, enumValuesStart);
            }
            String includeDeprecated = argumentValueFromAstMaterialized(
                    query, requestAst, enumValuesStart, "includeDeprecated", variablesJson);
            int includeDeprecatedValue = includeDeprecated.length() == 0 ? 0 : booleanArgument(includeDeprecated);
            if (includeDeprecated.length() != 0
                    && (argumentValueIsNull(includeDeprecated) || includeDeprecatedValue < 0)) {
                return errorJsonAt("argument '__Type.enumValues.includeDeprecated' must be Boolean", query,
                        enumValuesStart);
            }
            includeDeprecatedValues = includeDeprecatedValue == 1;
        }

        String selectionPlan = introspectionSelectionPlanFromAst(query, requestAst, enumValuesStart, variablesJson,
                "__EnumValue");
        int nameStart = introspectionSelectionPlanFieldStart(query, requestAst, selectionPlan, "name");
        int descriptionStart = introspectionSelectionPlanFieldStart(query, requestAst, selectionPlan, "description");
        int deprecatedStart = introspectionSelectionPlanFieldStart(query, requestAst, selectionPlan, "isDeprecated");
        int deprecationReasonStart = introspectionSelectionPlanFieldStart(query, requestAst, selectionPlan,
                "deprecationReason");
        int typeNameStart = introspectionSelectionPlanFieldStart(query, requestAst, selectionPlan, "__typename");
        int selected = (nameStart >= 0 ? 1 : 0) + (descriptionStart >= 0 ? 1 : 0)
                + (deprecatedStart >= 0 ? 1 : 0) + (deprecationReasonStart >= 0 ? 1 : 0)
                + (typeNameStart >= 0 ? 1 : 0);
        if (selected == 0) {
            return errorJsonAt("field '__Type.enumValues' requires a selection set", query, enumValuesStart);
        }
        if (selected != selectionPlanCount(selectionPlan)) {
            return errorJsonAt("unsupported field in __EnumValue selection", query, enumValuesStart);
        }
        int[] leaves = {nameStart, descriptionStart, deprecatedStart, deprecationReasonStart, typeNameStart};
        int leafIndex = 0;
        while (leafIndex < leaves.length) {
            int leafStart = leaves[leafIndex];
            if (leafStart >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, leafStart, "")
                    || !fieldHasNoSelectionSetFromAst(query, requestAst, leafStart))) {
                return errorJsonAt("introspection scalar field has an invalid selection", query, leafStart);
            }
            leafIndex++;
        }

        String nameKey = nameStart < 0 ? "" : responseKeyFromAst(query, requestAst, nameStart, "name");
        String descriptionKey = descriptionStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, descriptionStart, "description");
        String deprecatedKey = deprecatedStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, deprecatedStart, "isDeprecated");
        String deprecationReasonKey = deprecationReasonStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, deprecationReasonStart, "deprecationReason");
        String typeNameKey = typeNameStart < 0 ? ""
                : responseKeyFromAst(query, requestAst, typeNameStart, "__typename");

        if (descriptor == null || descriptor.length() == 0) {
            descriptor = "ev1;";
        } else if (!descriptor.startsWith("ev1;")) {
            return errorJsonAt("generated introspection enum metadata is malformed", query, enumValuesStart);
        }
        String items = "";
        int position = 4;
        while (position < descriptor.length()) {
            int nameLengthEnd = descriptor.indexOf(':', position);
            int nameLength = inputDescriptorDecimal(descriptor, position, nameLengthEnd);
            int valueNameStart = nameLengthEnd < 0 ? -1 : nameLengthEnd + 1;
            int valueNameEnd = valueNameStart < 0 || nameLength < 0 ? -1 : valueNameStart + nameLength;
            int descriptionLengthEnd = valueNameEnd < 0 || valueNameEnd > descriptor.length()
                    ? -1 : descriptor.indexOf(':', valueNameEnd);
            int descriptionLength = inputDescriptorDecimal(descriptor, valueNameEnd, descriptionLengthEnd);
            int valueDescriptionStart = descriptionLengthEnd < 0 ? -1 : descriptionLengthEnd + 1;
            int valueDescriptionEnd = valueDescriptionStart < 0 || descriptionLength < 0
                    ? -1 : valueDescriptionStart + descriptionLength;
            int deprecatedPosition = valueDescriptionEnd;
            int reasonLengthStart = deprecatedPosition < 0 ? -1 : deprecatedPosition + 1;
            int reasonLengthEnd = reasonLengthStart < 0 || reasonLengthStart > descriptor.length()
                    ? -1 : descriptor.indexOf(':', reasonLengthStart);
            int reasonLength = inputDescriptorDecimal(descriptor, reasonLengthStart, reasonLengthEnd);
            int reasonStart = reasonLengthEnd < 0 ? -1 : reasonLengthEnd + 1;
            int reasonEnd = reasonStart < 0 || reasonLength < 0 ? -1 : reasonStart + reasonLength;
            if (valueNameEnd <= valueNameStart || valueDescriptionEnd < valueDescriptionStart
                    || deprecatedPosition < 0 || deprecatedPosition >= descriptor.length()
                    || (descriptor.charAt(deprecatedPosition) != '0'
                    && descriptor.charAt(deprecatedPosition) != '1')
                    || reasonEnd < reasonStart || reasonEnd > descriptor.length()) {
                return errorJsonAt("generated introspection enum metadata is malformed", query, enumValuesStart);
            }
            String value = descriptor.substring(valueNameStart, valueNameEnd);
            String description = descriptor.substring(valueDescriptionStart, valueDescriptionEnd);
            boolean deprecated = descriptor.charAt(deprecatedPosition) == '1';
            String deprecationReason = descriptor.substring(reasonStart, reasonEnd);
            String members = "";
            if (nameStart >= 0) {
                members = appendJsonMember(members, nameKey, jsonString(value));
            }
            if (descriptionStart >= 0) {
                members = appendJsonMember(members, descriptionKey,
                        description.length() == 0 ? "null" : jsonString(description));
            }
            if (deprecatedStart >= 0) {
                members = appendJsonMember(members, deprecatedKey, deprecated ? "true" : "false");
            }
            if (deprecationReasonStart >= 0) {
                members = appendJsonMember(members, deprecationReasonKey,
                        deprecationReason.length() == 0 ? "null" : jsonString(deprecationReason));
            }
            if (typeNameStart >= 0) {
                members = appendJsonMember(members, typeNameKey, jsonString("__EnumValue"));
            }
            if (includeDeprecatedValues || !deprecated) {
                items = appendJsonItem(items, "{" + members + "}");
            }
            position = reasonEnd;
        }
        return "[" + items + "]";
    }

    /**
     * Renders a bounded type-reference wrapper chain selected from {@code __Type}.  It consumes
     * the language core's postfix type descriptor and records each selected wrapper in a scalar
     * stack, avoiding recursive routines in the Titan-transpiled call graph.
     */
    public static String introspectionTypeReferenceJson(
            String query,
            String requestAst,
            int typeStart,
            String variablesJson,
            String typeReference,
            String namedKind
    ) {
        if (!introspectionNamedKindIsValid(namedKind)) {
            return errorJsonAt("generated introspection type metadata is malformed", query, typeStart);
        }
        String currentType = DatabaseGraphqlTypeReference.parse(typeReference);
        if (currentType.length() == 0) {
            return errorJsonAt("generated introspection type metadata is malformed", query, typeStart);
        }
        String namedType = DatabaseGraphqlTypeReference.namedType(currentType);
        String levels = "";
        int currentStart = typeStart;
        int depth = 0;
        while (depth < 258) {
            if (!fieldHasOnlyArgumentsFromAst(query, requestAst, currentStart, "")) {
                return errorJsonAt("introspection type field does not support arguments", query, currentStart);
            }
            String selectionPlan = introspectionSelectionPlanFromAst(query, requestAst, currentStart, variablesJson,
                    "__Type");
            int nameStart = -1;
            int kindStart = -1;
            int descriptionStart = -1;
            int ofTypeStart = -1;
            int typeNameStart = -1;
            int planCount = selectionPlanCount(selectionPlan);
            int planIndex = 0;
            while (planIndex < planCount) {
                int selectedStart = selectionPlanFieldStart(selectionPlan, planIndex);
                String selectedField = fieldName(query, requestAst, selectedStart);
                if (selectedField.equals("name") && nameStart < 0) nameStart = selectedStart;
                else if (selectedField.equals("kind") && kindStart < 0) kindStart = selectedStart;
                else if (selectedField.equals("description") && descriptionStart < 0) {
                    descriptionStart = selectedStart;
                } else if (selectedField.equals("ofType") && ofTypeStart < 0) ofTypeStart = selectedStart;
                else if (selectedField.equals("__typename") && typeNameStart < 0) typeNameStart = selectedStart;
                planIndex++;
            }
            int selected = (nameStart >= 0 ? 1 : 0) + (kindStart >= 0 ? 1 : 0)
                    + (descriptionStart >= 0 ? 1 : 0) + (ofTypeStart >= 0 ? 1 : 0)
                    + (typeNameStart >= 0 ? 1 : 0);
            if (selected == 0 || selected != planCount) {
                return errorJsonAt("unsupported field in __Type selection", query, currentStart);
            }
            int[] leaves = {nameStart, kindStart, descriptionStart, typeNameStart};
            int leafIndex = 0;
            while (leafIndex < leaves.length) {
                int leafStart = leaves[leafIndex];
                if (leafStart >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, leafStart, "")
                        || !fieldHasNoSelectionSetFromAst(query, requestAst, leafStart))) {
                    return errorJsonAt("introspection scalar field has an invalid selection", query, leafStart);
                }
                leafIndex++;
            }
            if (ofTypeStart >= 0 && !fieldHasOnlyArgumentsFromAst(query, requestAst, ofTypeStart, "")) {
                return errorJsonAt("field '__Type.ofType' does not support arguments", query, ofTypeStart);
            }
            char wrapper = DatabaseGraphqlTypeReference.isOuterNonNull(currentType) ? 'N'
                    : DatabaseGraphqlTypeReference.isOuterList(currentType) ? 'L' : 'B';
            levels = levels + wrapper + ":" + nameStart + ":" + kindStart + ":" + descriptionStart + ":"
                    + ofTypeStart + ":" + typeNameStart + ";";
            if (ofTypeStart < 0 || wrapper == 'B') {
                break;
            }
            currentType = wrapper == 'N' ? DatabaseGraphqlTypeReference.withoutOuterNonNull(currentType)
                    : DatabaseGraphqlTypeReference.listItemType(currentType);
            if (currentType.length() == 0) {
                return errorJsonAt("generated introspection type metadata is malformed", query, typeStart);
            }
            currentStart = ofTypeStart;
            depth++;
        }
        if (depth == 258) {
            return errorJsonAt("introspection type wrapper budget exceeded", query, typeStart);
        }

        String nested = "null";
        boolean hasNested = false;
        while (levels.length() != 0) {
            int end = levels.length() - 1;
            int start = introspectionLastCharacterIndex(levels, ';', end - 1) + 1;
            String level = levels.substring(start, end);
            levels = levels.substring(0, start);
            char wrapper = level.charAt(0);
            int nameStart = introspectionRecordInteger(level, 0);
            int kindStart = introspectionRecordInteger(level, 1);
            int descriptionStart = introspectionRecordInteger(level, 2);
            int ofTypeStart = introspectionRecordInteger(level, 3);
            int typeNameStart = introspectionRecordInteger(level, 4);
            String kind = wrapper == 'N' ? "NON_NULL" : wrapper == 'L' ? "LIST"
                    : introspectionNamedKindName(namedKind);
            String members = "";
            if (nameStart >= 0) {
                members = appendJsonMember(members, responseKeyFromAst(query, requestAst, nameStart, "name"),
                        wrapper == 'B' ? jsonString(namedType) : "null");
            }
            if (kindStart >= 0) {
                members = appendJsonMember(members, responseKeyFromAst(query, requestAst, kindStart, "kind"),
                        jsonString(kind));
            }
            if (descriptionStart >= 0) {
                members = appendJsonMember(members,
                        responseKeyFromAst(query, requestAst, descriptionStart, "description"), "null");
            }
            if (ofTypeStart >= 0) {
                members = appendJsonMember(members, responseKeyFromAst(query, requestAst, ofTypeStart, "ofType"),
                        hasNested ? nested : "null");
            }
            if (typeNameStart >= 0) {
                members = appendJsonMember(members,
                        responseKeyFromAst(query, requestAst, typeNameStart, "__typename"), jsonString("__Type"));
            }
            nested = "{" + members + "}";
            hasNested = true;
        }
        return nested;
    }

    private static boolean introspectionNamedKindIsValid(String kind) {
        return kind != null && (kind.equals("S") || kind.equals("O") || kind.equals("T")
                || kind.equals("U") || kind.equals("I") || kind.equals("E"));
    }

    private static String introspectionNamedKindName(String kind) {
        return kind.equals("S") ? "SCALAR" : kind.equals("O") ? "OBJECT"
                : kind.equals("T") ? "INTERFACE" : kind.equals("U") ? "UNION"
                : kind.equals("I") ? "INPUT_OBJECT" : "ENUM";
    }

    /** Titan lowers the supported scalar string operations, so keep reverse stack lookup explicit. */
    private static int introspectionLastCharacterIndex(String value, char sought, int position) {
        int cursor = position;
        while (cursor >= 0) {
            if (value.charAt(cursor) == sought) {
                return cursor;
            }
            cursor--;
        }
        return -1;
    }

    /** Reads the zero-based integer after the marker character in one scalar type-stack record. */
    private static int introspectionRecordInteger(String record, int index) {
        int start = 2;
        int found = 0;
        while (found < index) {
            start = record.indexOf(':', start) + 1;
            if (start == 0) {
                return -1;
            }
            found++;
        }
        int end = record.indexOf(':', start);
        if (end < 0) {
            end = record.length();
        }
        boolean negative = start < end && record.charAt(start) == '-';
        int position = negative ? start + 1 : start;
        int value = 0;
        while (position < end) {
            char digit = record.charAt(position);
            if (digit < '0' || digit > '9' || value > 6553
                    || value == 6553 && digit > '6') {
                return -1;
            }
            value = value * 10 + (digit - '0');
            position++;
        }
        if (position == (negative ? start + 1 : start)) {
            return -1;
        }
        return negative && value == 1 ? -1 : negative ? -1 : value;
    }

    /**
     * Renders the bounded {@code NON_NULL -> LIST -> NON_NULL -> OBJECT} type shape used by
     * generated Relay connection {@code edges}.  It deliberately walks selected {@code __Type}
     * levels iteratively: the request remains inside the transpiled routine and needs neither a
     * JVM schema object nor recursive stored-routine calls.
     */
    public static String introspectionNonNullListNonNullObjectTypeReferenceJson(
            String query,
            String requestAst,
            int fieldStart,
            String variablesJson,
            String objectType
    ) {
        // The four wrapper levels each have one shared `__Type` selection shape. Retain one
        // expanded plan per level, rather than repeatedly traversing fragments for every
        // recognised scalar member of every generated connection field.
        String rootSelectionPlan = introspectionSelectionPlanFromAst(query, requestAst, fieldStart, variablesJson,
                "__Type");
        int rootName = introspectionSelectionPlanFieldStart(query, requestAst, rootSelectionPlan, "name");
        int rootKind = introspectionSelectionPlanFieldStart(query, requestAst, rootSelectionPlan, "kind");
        int rootDescription = introspectionSelectionPlanFieldStart(query, requestAst, rootSelectionPlan,
                "description");
        int rootOfType = introspectionSelectionPlanFieldStart(query, requestAst, rootSelectionPlan, "ofType");
        int rootTypeName = introspectionSelectionPlanFieldStart(query, requestAst, rootSelectionPlan, "__typename");
        int rootSelected = (rootName >= 0 ? 1 : 0) + (rootKind >= 0 ? 1 : 0) + (rootDescription >= 0 ? 1 : 0)
                + (rootOfType >= 0 ? 1 : 0) + (rootTypeName >= 0 ? 1 : 0);
        if (rootSelected == 0 || rootSelected != selectionPlanCount(rootSelectionPlan)) {
            return errorJsonAt("unsupported field in __Type selection", query, fieldStart);
        }
        if ((rootName >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, rootName, "") || !fieldHasNoSelectionSetFromAst(query, requestAst, rootName)))
                || (rootKind >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, rootKind, "") || !fieldHasNoSelectionSetFromAst(query, requestAst, rootKind)))
                || (rootDescription >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, rootDescription, "") || !fieldHasNoSelectionSetFromAst(query, requestAst, rootDescription)))
                || (rootTypeName >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, rootTypeName, "") || !fieldHasNoSelectionSetFromAst(query, requestAst, rootTypeName)))) {
            return errorJsonAt("introspection scalar field has an invalid selection", query, fieldStart);
        }
        if (rootOfType >= 0 && !fieldHasOnlyArgumentsFromAst(query, requestAst, rootOfType, "")) {
            return errorJsonAt("field '__Type.ofType' does not support arguments", query, rootOfType);
        }

        int listName = -1;
        int listKind = -1;
        int listDescription = -1;
        int listOfType = -1;
        int listTypeName = -1;
        if (rootOfType >= 0) {
            String listSelectionPlan = introspectionSelectionPlanFromAst(query, requestAst, rootOfType, variablesJson,
                    "__Type");
            listName = introspectionSelectionPlanFieldStart(query, requestAst, listSelectionPlan, "name");
            listKind = introspectionSelectionPlanFieldStart(query, requestAst, listSelectionPlan, "kind");
            listDescription = introspectionSelectionPlanFieldStart(query, requestAst, listSelectionPlan,
                    "description");
            listOfType = introspectionSelectionPlanFieldStart(query, requestAst, listSelectionPlan, "ofType");
            listTypeName = introspectionSelectionPlanFieldStart(query, requestAst, listSelectionPlan, "__typename");
            int listSelected = (listName >= 0 ? 1 : 0) + (listKind >= 0 ? 1 : 0) + (listDescription >= 0 ? 1 : 0)
                    + (listOfType >= 0 ? 1 : 0) + (listTypeName >= 0 ? 1 : 0);
            if (listSelected == 0 || listSelected != selectionPlanCount(listSelectionPlan)) {
                return errorJsonAt("unsupported field in __Type selection", query, rootOfType);
            }
            if ((listName >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, listName, "") || !fieldHasNoSelectionSetFromAst(query, requestAst, listName)))
                    || (listKind >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, listKind, "") || !fieldHasNoSelectionSetFromAst(query, requestAst, listKind)))
                    || (listDescription >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, listDescription, "") || !fieldHasNoSelectionSetFromAst(query, requestAst, listDescription)))
                    || (listTypeName >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, listTypeName, "") || !fieldHasNoSelectionSetFromAst(query, requestAst, listTypeName)))) {
                return errorJsonAt("introspection scalar field has an invalid selection", query, rootOfType);
            }
            if (listOfType >= 0 && !fieldHasOnlyArgumentsFromAst(query, requestAst, listOfType, "")) {
                return errorJsonAt("field '__Type.ofType' does not support arguments", query, listOfType);
            }
        }

        int elementName = -1;
        int elementKind = -1;
        int elementDescription = -1;
        int elementOfType = -1;
        int elementTypeName = -1;
        if (listOfType >= 0) {
            String elementSelectionPlan = introspectionSelectionPlanFromAst(query, requestAst, listOfType,
                    variablesJson, "__Type");
            elementName = introspectionSelectionPlanFieldStart(query, requestAst, elementSelectionPlan, "name");
            elementKind = introspectionSelectionPlanFieldStart(query, requestAst, elementSelectionPlan, "kind");
            elementDescription = introspectionSelectionPlanFieldStart(query, requestAst, elementSelectionPlan,
                    "description");
            elementOfType = introspectionSelectionPlanFieldStart(query, requestAst, elementSelectionPlan, "ofType");
            elementTypeName = introspectionSelectionPlanFieldStart(query, requestAst, elementSelectionPlan,
                    "__typename");
            int elementSelected = (elementName >= 0 ? 1 : 0) + (elementKind >= 0 ? 1 : 0) + (elementDescription >= 0 ? 1 : 0)
                    + (elementOfType >= 0 ? 1 : 0) + (elementTypeName >= 0 ? 1 : 0);
            if (elementSelected == 0 || elementSelected != selectionPlanCount(elementSelectionPlan)) {
                return errorJsonAt("unsupported field in __Type selection", query, listOfType);
            }
            if ((elementName >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, elementName, "") || !fieldHasNoSelectionSetFromAst(query, requestAst, elementName)))
                    || (elementKind >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, elementKind, "") || !fieldHasNoSelectionSetFromAst(query, requestAst, elementKind)))
                    || (elementDescription >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, elementDescription, "") || !fieldHasNoSelectionSetFromAst(query, requestAst, elementDescription)))
                    || (elementTypeName >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, elementTypeName, "") || !fieldHasNoSelectionSetFromAst(query, requestAst, elementTypeName)))) {
                return errorJsonAt("introspection scalar field has an invalid selection", query, listOfType);
            }
            if (elementOfType >= 0 && !fieldHasOnlyArgumentsFromAst(query, requestAst, elementOfType, "")) {
                return errorJsonAt("field '__Type.ofType' does not support arguments", query, elementOfType);
            }
        }

        int objectName = -1;
        int objectKind = -1;
        int objectDescription = -1;
        int objectOfType = -1;
        int objectTypeName = -1;
        if (elementOfType >= 0) {
            String objectSelectionPlan = introspectionSelectionPlanFromAst(query, requestAst, elementOfType,
                    variablesJson, "__Type");
            objectName = introspectionSelectionPlanFieldStart(query, requestAst, objectSelectionPlan, "name");
            objectKind = introspectionSelectionPlanFieldStart(query, requestAst, objectSelectionPlan, "kind");
            objectDescription = introspectionSelectionPlanFieldStart(query, requestAst, objectSelectionPlan,
                    "description");
            objectOfType = introspectionSelectionPlanFieldStart(query, requestAst, objectSelectionPlan, "ofType");
            objectTypeName = introspectionSelectionPlanFieldStart(query, requestAst, objectSelectionPlan,
                    "__typename");
            int objectSelected = (objectName >= 0 ? 1 : 0) + (objectKind >= 0 ? 1 : 0) + (objectDescription >= 0 ? 1 : 0)
                    + (objectOfType >= 0 ? 1 : 0) + (objectTypeName >= 0 ? 1 : 0);
            if (objectSelected == 0 || objectSelected != selectionPlanCount(objectSelectionPlan)) {
                return errorJsonAt("unsupported field in __Type selection", query, elementOfType);
            }
            if ((objectName >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, objectName, "") || !fieldHasNoSelectionSetFromAst(query, requestAst, objectName)))
                    || (objectKind >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, objectKind, "") || !fieldHasNoSelectionSetFromAst(query, requestAst, objectKind)))
                    || (objectDescription >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, objectDescription, "") || !fieldHasNoSelectionSetFromAst(query, requestAst, objectDescription)))
                    || (objectTypeName >= 0 && (!fieldHasOnlyArgumentsFromAst(query, requestAst, objectTypeName, "") || !fieldHasNoSelectionSetFromAst(query, requestAst, objectTypeName)))) {
                return errorJsonAt("introspection scalar field has an invalid selection", query, elementOfType);
            }
            if (objectOfType >= 0 && !fieldHasOnlyArgumentsFromAst(query, requestAst, objectOfType, "")) {
                return errorJsonAt("field '__Type.ofType' does not support arguments", query, objectOfType);
            }
        }

        String object = "null";
        if (elementOfType >= 0) {
            String members = "";
            if (objectName >= 0) members = appendJsonMember(members, responseKeyFromAst(query, requestAst, objectName, "name"), jsonString(objectType));
            if (objectKind >= 0) members = appendJsonMember(members, responseKeyFromAst(query, requestAst, objectKind, "kind"), jsonString("OBJECT"));
            if (objectDescription >= 0) members = appendJsonMember(members, responseKeyFromAst(query, requestAst, objectDescription, "description"), "null");
            if (objectOfType >= 0) members = appendJsonMember(members, responseKeyFromAst(query, requestAst, objectOfType, "ofType"), "null");
            if (objectTypeName >= 0) members = appendJsonMember(members, responseKeyFromAst(query, requestAst, objectTypeName, "__typename"), jsonString("__Type"));
            object = "{" + members + "}";
        }
        String element = "null";
        if (listOfType >= 0) {
            String members = "";
            if (elementName >= 0) members = appendJsonMember(members, responseKeyFromAst(query, requestAst, elementName, "name"), "null");
            if (elementKind >= 0) members = appendJsonMember(members, responseKeyFromAst(query, requestAst, elementKind, "kind"), jsonString("NON_NULL"));
            if (elementDescription >= 0) members = appendJsonMember(members, responseKeyFromAst(query, requestAst, elementDescription, "description"), "null");
            if (elementOfType >= 0) members = appendJsonMember(members, responseKeyFromAst(query, requestAst, elementOfType, "ofType"), object);
            if (elementTypeName >= 0) members = appendJsonMember(members, responseKeyFromAst(query, requestAst, elementTypeName, "__typename"), jsonString("__Type"));
            element = "{" + members + "}";
        }
        String list = "null";
        if (rootOfType >= 0) {
            String members = "";
            if (listName >= 0) members = appendJsonMember(members, responseKeyFromAst(query, requestAst, listName, "name"), "null");
            if (listKind >= 0) members = appendJsonMember(members, responseKeyFromAst(query, requestAst, listKind, "kind"), jsonString("LIST"));
            if (listDescription >= 0) members = appendJsonMember(members, responseKeyFromAst(query, requestAst, listDescription, "description"), "null");
            if (listOfType >= 0) members = appendJsonMember(members, responseKeyFromAst(query, requestAst, listOfType, "ofType"), element);
            if (listTypeName >= 0) members = appendJsonMember(members, responseKeyFromAst(query, requestAst, listTypeName, "__typename"), jsonString("__Type"));
            list = "{" + members + "}";
        }
        String members = "";
        if (rootName >= 0) members = appendJsonMember(members, responseKeyFromAst(query, requestAst, rootName, "name"), "null");
        if (rootKind >= 0) members = appendJsonMember(members, responseKeyFromAst(query, requestAst, rootKind, "kind"), jsonString("NON_NULL"));
        if (rootDescription >= 0) members = appendJsonMember(members, responseKeyFromAst(query, requestAst, rootDescription, "description"), "null");
        if (rootOfType >= 0) members = appendJsonMember(members, responseKeyFromAst(query, requestAst, rootOfType, "ofType"), list);
        if (rootTypeName >= 0) members = appendJsonMember(members, responseKeyFromAst(query, requestAst, rootTypeName, "__typename"), jsonString("__Type"));
        return "{" + members + "}";
    }

    /** Returns whether a non-zero absolute deadline has passed according to the database clock. */
    public static boolean deadlineExpired(long deadlineEpochMillis) {
        return deadlineEpochMillis > 0L
                && Instant.now().compareTo(Instant.ofEpochMilli(deadlineEpochMillis)) >= 0;
    }

    /**
     * Returns a typed Boolean from the authenticated context-value map: {@code 1} for true,
     * {@code 0} for false, and {@code -1} for absent or non-Boolean values. Model-generated
     * context predicates use the third state to enforce their documented fail-closed policy.
     */
    public static int trustedContextBoolean(String trustedContextJson, String contextKey) {
        String values = jsonObjectFieldValue(trustedContextJson, "contextValues");
        String value = jsonObjectFieldValue(values, contextKey);
        if (value.equals("true")) {
            return 1;
        }
        if (value.equals("false")) {
            return 0;
        }
        return -1;
    }

    /** Returns whether a named authenticated context value is a JSON string, including empty strings. */
    public static boolean trustedContextStringValuePresent(String trustedContextJson, String contextKey) {
        String values = jsonObjectFieldValue(trustedContextJson, "contextValues");
        String value = jsonObjectFieldValue(values, contextKey);
        return value.length() >= 2 && value.charAt(0) == '"' && strictJsonStringEnd(value, 0, value.length()) == value.length();
    }

    /** Returns a named authenticated string context value, or an empty value when absent/malformed. */
    public static String trustedContextStringValue(String trustedContextJson, String contextKey) {
        String values = jsonObjectFieldValue(trustedContextJson, "contextValues");
        String value = jsonObjectFieldValue(values, contextKey);
        if (value.length() < 2 || value.charAt(0) != '"'
                || strictJsonStringEnd(value, 0, value.length()) != value.length()) {
            return "";
        }
        return jsonStringValue(value);
    }

    /** Tests whether the trusted context enabled a named, model-defined context filter. */
    public static boolean trustedContextStringArrayContains(
            String trustedContextJson,
            String fieldName,
            String expectedValue
    ) {
        String array = jsonObjectFieldValue(trustedContextJson, fieldName);
        if (array.length() == 0) {
            return false;
        }
        int start = skipJsonWhitespace(array, 0, array.length());
        int end = strictJsonValueEnd(array, start, array.length());
        if (start >= array.length() || array.charAt(start) != '[' || end < 0
                || skipJsonWhitespace(array, end, array.length()) != array.length()) {
            return false;
        }
        int position = start + 1;
        while (position < end - 1) {
            position = skipJsonWhitespace(array, position, end);
            if (position >= end - 1 || array.charAt(position) != '"') {
                return false;
            }
            int itemEnd = strictJsonStringEnd(array, position, end);
            if (itemEnd < 0) {
                return false;
            }
            if (jsonStringValue(array.substring(position, itemEnd)).equals(expectedValue)) {
                return true;
            }
            position = skipJsonWhitespace(array, itemEnd, end);
            if (position < end - 1 && array.charAt(position) == ',') {
                position++;
            } else if (position != end - 1) {
                return false;
            }
        }
        return false;
    }

    public static boolean roleAllowed(String actorRole, String expression) {
        String normalizedRole = actorRole == null ? "" : actorRole.toLowerCase();
        if ("allowAll".equals(expression)) {
            return true;
        }
        if ("denyAll".equals(expression)) {
            return false;
        }
        if ("authenticated".equals(expression)) {
            return actorRole != null && actorRole.length() != 0;
        }
        if ("adminOnly".equals(expression)) {
            return "admin".equals(normalizedRole);
        }
        if (startsWith(expression, "roleEquals:")) {
            return expression.substring(11, expression.length()).toLowerCase().equals(normalizedRole);
        }
        if (startsWith(expression, "roleIn:")) {
            String expected = expression.substring(7, expression.length());
            String candidate = "";
            int position = 0;
            while (position <= expected.length()) {
                if (position == expected.length() || expected.charAt(position) == ',') {
                    if (candidate.toLowerCase().equals(normalizedRole)) {
                        return true;
                    }
                    candidate = "";
                } else {
                    candidate = candidate + expected.charAt(position);
                }
                position++;
            }
            return false;
        }
        return false;
    }

    public static int rootFieldStart(String query, String expectedField, int occurrence) {
        int start = topSelectionStart(query);
        if (start < 0) {
            return -1;
        }
        int end = selectionEnd(query, start);
        int position = start + 1;
        int matched = 0;
        while (position < end) {
            position = skipIgnored(query, position, end);
            if (position >= end || isNameStart(query.charAt(position)) == false) {
                position++;
                continue;
            }
            int fieldStart = position;
            int firstEnd = nameEnd(query, position, end);
            String first = query.substring(position, firstEnd);
            int afterFirst = skipIgnored(query, firstEnd, end);
            String field = first;
            if (afterFirst < end && query.charAt(afterFirst) == ':') {
                int targetStart = skipIgnored(query, afterFirst + 1, end);
                if (isNameAt(query, expectedField, targetStart, end) == false) {
                    position = skipField(query, targetStart, end);
                    continue;
                }
                field = expectedField;
            }
            if (field.equals(expectedField)) {
                if (matched == occurrence) {
                    return fieldStart;
                }
                matched++;
            }
            position = skipField(query, firstEnd, end);
        }
        return -1;
    }

    /**
     * Finds an enabled root field.  A field skipped by a supported directive is intentionally
     * absent from the execution plan and response; malformed or unsupported directives are
     * reported by {@link #rootFieldCount(String, String)} before generated dispatch begins.
     */
    public static int rootFieldStart(String query, String expectedField, int occurrence, String variablesJson) {
        int start = topSelectionStart(query);
        if (start < 0) {
            return -1;
        }
        int end = selectionEnd(query, start);
        int position = start + 1;
        int matched = 0;
        while (position < end) {
            position = skipIgnored(query, position, end);
            if (position >= end || isNameStart(query.charAt(position)) == false) {
                position++;
                continue;
            }
            int fieldStart = position;
            int firstEnd = nameEnd(query, position, end);
            int afterFirst = skipIgnored(query, firstEnd, end);
            String field = firstEnd <= position ? "" : query.substring(position, firstEnd);
            if (afterFirst < end && query.charAt(afterFirst) == ':') {
                int targetStart = skipIgnored(query, afterFirst + 1, end);
                int targetEnd = nameEnd(query, targetStart, end);
                field = targetEnd <= targetStart ? "" : query.substring(targetStart, targetEnd);
            }
            int inclusion = fieldInclusion(query, fieldStart, variablesJson);
            if (field.equals(expectedField) && inclusion > 0) {
                if (matched == occurrence) {
                    return fieldStart;
                }
                matched++;
            }
            position = skipField(query, firstEnd, end);
        }
        return -1;
    }

    /** Returns the start of the zero-based root field in the selected operation. */
    public static int rootFieldStartAt(String query, int ordinal) {
        if (ordinal < 0) {
            return -1;
        }
        int start = topSelectionStart(query);
        if (start < 0) {
            return -1;
        }
        int end = selectionEnd(query, start);
        int position = start + 1;
        int seen = 0;
        while (position < end) {
            position = skipIgnored(query, position, end);
            if (position >= end || isNameStart(query.charAt(position)) == false) {
                position++;
                continue;
            }
            int fieldStart = position;
            if (seen == ordinal) {
                return fieldStart;
            }
            seen++;
            position = skipField(query, nameEnd(query, position, end), end);
        }
        return -1;
    }

    /** Returns the zero-based enabled root field, preserving selected-document order. */
    public static int rootFieldStartAt(String query, int ordinal, String variablesJson) {
        if (ordinal < 0) {
            return -1;
        }
        int start = topSelectionStart(query);
        if (start < 0) {
            return -1;
        }
        int end = selectionEnd(query, start);
        int position = start + 1;
        int seen = 0;
        while (position < end) {
            position = skipIgnored(query, position, end);
            if (position >= end || isNameStart(query.charAt(position)) == false) {
                position++;
                continue;
            }
            int fieldStart = position;
            if (fieldInclusion(query, fieldStart, variablesJson) > 0) {
                if (seen == ordinal) {
                    return fieldStart;
                }
                seen++;
            }
            position = skipField(query, nameEnd(query, position, end), end);
        }
        return -1;
    }

    /** Whether a parsed root field names the supplied schema field, accounting for an alias. */
    public static boolean rootFieldMatches(String query, String languagePlan, int fieldStart, String expectedField) {
        return expectedField != null && expectedField.equals(
                DatabaseGraphqlLanguage.fieldName(query, languagePlan, fieldStart));
    }

    /** Matches a schema field from the request-local typed AST, preserving alias distinction. */
    public static boolean rootFieldMatchesFromAst(
            String query,
            String ast,
            int fieldStart,
            String expectedField
    ) {
        return expectedField != null && expectedField.equals(fieldName(query, ast, fieldStart));
    }

    public static int selectionFieldStart(String query, int parentFieldStart, String expectedField) {
        int start = DatabaseGraphqlLanguage.fieldSelectionStart(query, documentPlan(query), parentFieldStart);
        if (start < 0) {
            return -1;
        }
        int end = selectionEnd(query, start);
        int position = start + 1;
        while (position < end) {
            position = skipIgnored(query, position, end);
            if (position >= end || isNameStart(query.charAt(position)) == false) {
                position++;
                continue;
            }
            int fieldStart = position;
            int firstEnd = nameEnd(query, position, end);
            int afterFirst = skipIgnored(query, firstEnd, end);
            if (isNameAt(query, expectedField, position, firstEnd)) {
                return fieldStart;
            }
            if (afterFirst < end && query.charAt(afterFirst) == ':') {
                int targetStart = skipIgnored(query, afterFirst + 1, end);
                if (isNameAt(query, expectedField, targetStart, end)) {
                    return fieldStart;
                }
            }
            position = skipField(query, firstEnd, end);
        }
        return -1;
    }

    /** Finds an enabled child field, resolving `@skip` and `@include` in the database routine. */
    public static int selectionFieldStart(
            String query,
            int parentFieldStart,
            String expectedField,
            String variablesJson
    ) {
        int start = DatabaseGraphqlLanguage.fieldSelectionStart(query, documentPlan(query), parentFieldStart);
        if (start < 0) {
            return -1;
        }
        int end = selectionEnd(query, start);
        int position = start + 1;
        while (position < end) {
            position = skipIgnored(query, position, end);
            if (position >= end || isNameStart(query.charAt(position)) == false) {
                position++;
                continue;
            }
            int fieldStart = position;
            int firstEnd = nameEnd(query, position, end);
            int afterFirst = skipIgnored(query, firstEnd, end);
            boolean matches = isNameAt(query, expectedField, position, firstEnd);
            if (afterFirst < end && query.charAt(afterFirst) == ':') {
                int targetStart = skipIgnored(query, afterFirst + 1, end);
                matches = isNameAt(query, expectedField, targetStart, end);
            }
            if (matches && fieldInclusion(query, fieldStart, variablesJson) > 0) {
                return fieldStart;
            }
            position = skipField(query, firstEnd, end);
        }
        return -1;
    }

    public static int rootFieldCount(String query) {
        int start = topSelectionStart(query);
        if (start < 0) {
            return 0;
        }
        return selectionFieldCountAt(query, start);
    }

    /** Counts only enabled root selections, or {@code -1} for an invalid/unsupported directive. */
    public static int rootFieldCount(String query, String variablesJson) {
        int start = topSelectionStart(query);
        return start < 0 ? 0 : selectionFieldCountAt(query, start, variablesJson);
    }

    public static int selectionFieldCount(String query, int parentFieldStart) {
        int start = DatabaseGraphqlLanguage.fieldSelectionStart(query, documentPlan(query), parentFieldStart);
        return start < 0 ? 0 : selectionFieldCountAt(query, start);
    }

    /** Counts enabled child selections, or {@code -1} for an invalid/unsupported directive. */
    public static int selectionFieldCount(String query, int parentFieldStart, String variablesJson) {
        int start = DatabaseGraphqlLanguage.fieldSelectionStart(query, documentPlan(query), parentFieldStart);
        return start < 0 ? 0 : selectionFieldCountAt(query, start, variablesJson);
    }

    /** Fragment-aware typed root lookup used by generated Query bindings. */
    public static int rootFieldStart(
            String query,
            String expectedField,
            int occurrence,
            String variablesJson,
            String parentType
    ) {
        int start = topSelectionStart(query);
        return start < 0 ? -1 : fragmentAwareFieldStart(query, start, expectedField, occurrence,
                variablesJson, parentType);
    }

    /** Fragment-aware typed root ordinal used to preserve serial mutation-root order. */
    public static int rootFieldStartAt(String query, int ordinal, String variablesJson, String parentType) {
        int start = topSelectionStart(query);
        return start < 0 ? -1 : fragmentAwareFieldStart(query, start, "", ordinal, variablesJson, parentType);
    }

    /** Counts typed root selections after fragment/directive expansion, or {@code -1} on invalid state. */
    public static int rootFieldCount(String query, String variablesJson, String parentType) {
        int start = topSelectionStart(query);
        return start < 0 ? 0 : fragmentAwareFieldCount(query, start, variablesJson, parentType);
    }

    /** Creates the bounded, invocation-local lexical representation for a selected operation. */
    public static String documentPlan(String query) {
        return DatabaseGraphqlLanguage.documentPlan(query);
    }

    /** Structural counterpart used for the requested-root count before directive evaluation. */
    public static int rootFieldCountForType(String query, String parentType) {
        return rootFieldCountForType(query, documentPlan(query), parentType);
    }

    /**
     * Structural counterpart that consumes the selected operation's invocation-local language
     * plan. Generated serving routines create this plan once and retain it for the request.
     */
    public static int rootFieldCountForType(String query, String languagePlan, String parentType) {
        int start = topSelectionStart(query, languagePlan);
        return start < 0 ? 0 : fragmentAwareFieldCount(query, languagePlan, start, null, parentType);
    }

    /** Counts typed root fields from the caller-retained AST rather than reparsing the request. */
    public static int rootFieldCountForTypeFromAst(String query, String ast, String parentType) {
        int start = DatabaseGraphqlAst.operationSelectionStart(ast);
        return start < 0 ? 0 : fragmentAwareFieldCountFromAst(query, ast, start, null, parentType);
    }

    /**
     * Compiles the selected root set into an invocation-local, scalar execution plan. Each entry
     * is a source location for one enabled, fragment-expanded root field, in GraphQL request
     * order. The generated binding owns schema dispatch; this shared plan owns traversal,
     * directives, fragment expansion, and the work budget.
     *
     * <p>The compact {@code v1;start;...} representation is deliberately not a client format or
     * an AST replacement. It is the first reusable plan carrier compatible with Titan's current
     * scalar-state subset, and prevents a dispatch loop from re-walking the fragment graph for
     * every root ordinal.</p>
     */
    public static String rootSelectionPlan(String query, String variablesJson, String parentType) {
        return rootSelectionPlan(query, documentPlan(query), variablesJson, parentType, "");
    }

    /**
     * Compiles root fields with schema-approved object-root merging. The generated schema passes
     * only point roots here: a pair with the same response key, field name, and argument text is
     * one GraphQL field collection even when its child selections differ. Other root kinds stay
     * separate because their current generated executors do not yet have a recursive merge plan.
     */
    public static String rootSelectionPlan(
            String query,
            String variablesJson,
            String parentType,
            String mergeablePointRootNames
    ) {
        return rootSelectionPlan(query, documentPlan(query), variablesJson, parentType, mergeablePointRootNames);
    }

    /** Compiles selected roots with schema-approved merging using the request-local language plan. */
    public static String rootSelectionPlan(
            String query,
            String languagePlan,
            String variablesJson,
            String parentType,
            String mergeablePointRootNames
    ) {
        int start = topSelectionStart(query, languagePlan);
        return start < 0 ? "" : selectionPlan(query, languagePlan, start, variablesJson, parentType,
                mergeablePointRootNames);
    }

    /** Compiles roots from the one typed AST retained by the generated serving routine. */
    public static String rootSelectionPlanFromAst(
            String query,
            String ast,
            String variablesJson,
            String parentType,
            String mergeablePointRootNames
    ) {
        int start = DatabaseGraphqlAst.operationSelectionStart(ast);
        return start < 0 ? "" : selectionPlanFromAst(query, ast, start, variablesJson, parentType,
                mergeablePointRootNames);
    }

    /** Compiles one selected object field's direct child selection into the same scalar plan form. */
    public static String fieldSelectionPlan(
            String query,
            int parentFieldStart,
            String variablesJson,
            String parentType
    ) {
        return fieldSelectionPlan(query, documentPlan(query), parentFieldStart, variablesJson, parentType, parentType);
    }

    /**
     * Compiles the merged direct children of one object-valued field using the request-local
     * language plan. The typed AST identifies each field's containing selection set, so every
     * merge-compatible sibling in that set contributes its child selections to one output plan
     * without reinterpreting raw selection-topology records.
     */
    public static String fieldSelectionPlan(
            String query,
            String languagePlan,
            int parentFieldStart,
            String variablesJson,
            String siblingParentType,
            String childType
    ) {
        if (languagePlan == null || parentFieldStart < 0) {
            return "";
        }
        String ast = DatabaseGraphqlAst.parse(query, languagePlan);
        int siblingSelectionStart = DatabaseGraphqlAst.fieldParentSelection(ast, parentFieldStart);
        if (siblingSelectionStart < 0) {
            return "";
        }
        String plan = "v1;";
        int occurrence = 0;
        while (occurrence < MAX_EXECUTION_SELECTION_FIELDS) {
            int candidate = fragmentAwareFieldStart(query, languagePlan, siblingSelectionStart, "", occurrence,
                    variablesJson, siblingParentType);
            if (candidate == -1) {
                break;
            }
            if (candidate < 0) {
                return "";
            }
            if (fieldsHaveEquivalentMergeHead(query, languagePlan, parentFieldStart, candidate)) {
                int childSelectionStart = DatabaseGraphqlLanguage.planInfoValue(
                        DatabaseGraphqlAst.fieldInfo(ast, candidate), 1);
                if (childSelectionStart < 0) {
                    return "";
                }
                String childPlan = selectionPlan(query, languagePlan, childSelectionStart, variablesJson, childType, "");
                plan = appendDistinctSelectionPlanEntries(query, languagePlan, plan, childPlan);
                if (plan.length() == 0) {
                    return "";
                }
            }
            occurrence++;
        }
        if (occurrence == MAX_EXECUTION_SELECTION_FIELDS) {
            return "";
        }

        // A point root may itself be collected from multiple compatible occurrences. The first
        // loop merged all relation fields under the selected occurrence, but a relation field
        // under a later compatible point root has a different direct parent selection. Locate
        // the object field that owns this selection, then walk the expanded Query root to merge
        // the corresponding relation selections as well. This keeps the scalar carrier bounded
        // while preventing a valid `{ customer { orders { id } } customer { orders { reference } } }`
        // request from silently omitting the second child selection.
        int siblingOwnerField = DatabaseGraphqlAst.selectionOwnerFieldStart(ast, siblingSelectionStart);
        if (siblingOwnerField < 0) {
            return "";
        }
        int rootSelectionStart = topSelectionStart(query, languagePlan);
        if (rootSelectionStart < 0) {
            return "";
        }
        int rootOccurrence = 0;
        while (rootOccurrence < MAX_EXECUTION_SELECTION_FIELDS) {
            int rootCandidate = fragmentAwareFieldStart(query, languagePlan, rootSelectionStart, "", rootOccurrence,
                    variablesJson, "Query");
            if (rootCandidate == -1) {
                break;
            }
            if (rootCandidate < 0) {
                return "";
            }
            if (rootCandidate != siblingOwnerField
                    && fieldsHaveEquivalentMergeHead(query, languagePlan, siblingOwnerField, rootCandidate)) {
                int rootChildSelection = DatabaseGraphqlLanguage.planInfoValue(
                        DatabaseGraphqlAst.fieldInfo(ast, rootCandidate), 1);
                if (rootChildSelection < 0) {
                    return "";
                }
                int relationOccurrence = 0;
                while (relationOccurrence < MAX_EXECUTION_SELECTION_FIELDS) {
                    int relationCandidate = fragmentAwareFieldStart(query, languagePlan, rootChildSelection, "",
                            relationOccurrence, variablesJson, siblingParentType);
                    if (relationCandidate == -1) {
                        break;
                    }
                    if (relationCandidate < 0) {
                        return "";
                    }
                    if (fieldsHaveEquivalentMergeHead(query, languagePlan, parentFieldStart, relationCandidate)) {
                        int childSelectionStart = DatabaseGraphqlLanguage.planInfoValue(
                                DatabaseGraphqlAst.fieldInfo(ast, relationCandidate), 1);
                        if (childSelectionStart < 0) {
                            return "";
                        }
                        String childPlan = selectionPlan(query, languagePlan, childSelectionStart, variablesJson,
                                childType, "");
                        plan = appendDistinctSelectionPlanEntries(query, languagePlan, plan, childPlan);
                        if (plan.length() == 0) {
                            return "";
                        }
                    }
                    relationOccurrence++;
                }
                if (relationOccurrence == MAX_EXECUTION_SELECTION_FIELDS) {
                    return "";
                }
            }
            rootOccurrence++;
        }
        if (rootOccurrence == MAX_EXECUTION_SELECTION_FIELDS) {
            return "";
        }
        return plan;
    }

    /**
     * AST-retaining variant used by generated serving routines. It is identical to the
     * request-plan form above except it never reconstructs an AST while walking nested fields.
     */
    public static String fieldSelectionPlanFromAst(
            String query,
            String ast,
            int parentFieldStart,
            String variablesJson,
            String siblingParentType,
            String childType
    ) {
        if (ast == null || parentFieldStart < 0) {
            return "";
        }
        int siblingSelectionStart = DatabaseGraphqlAst.fieldParentSelection(ast, parentFieldStart);
        if (siblingSelectionStart < 0) {
            return "";
        }
        String plan = "v1;";
        int occurrence = 0;
        while (occurrence < MAX_EXECUTION_SELECTION_FIELDS) {
            int candidate = fragmentAwareFieldStartFromAst(query, ast, siblingSelectionStart, "", occurrence,
                    variablesJson, siblingParentType);
            if (candidate == -1) {
                break;
            }
            if (candidate < 0 || fieldsHaveEquivalentMergeHeadFromAst(query, ast, parentFieldStart, candidate)
                    == false) {
                if (candidate < 0) return "";
                occurrence++;
                continue;
            }
            int childSelectionStart = fieldInfoValue(ast, candidate, 1);
            if (childSelectionStart < 0) {
                return "";
            }
            String childPlan = selectionPlanFromAst(query, ast, childSelectionStart, variablesJson, childType, "");
            plan = appendDistinctSelectionPlanEntriesFromAst(query, ast, plan, childPlan);
            if (plan.length() == 0) {
                return "";
            }
            occurrence++;
        }
        if (occurrence == MAX_EXECUTION_SELECTION_FIELDS) {
            return "";
        }
        int siblingOwnerField = DatabaseGraphqlAst.selectionOwnerFieldStart(ast, siblingSelectionStart);
        if (siblingOwnerField < 0) {
            return "";
        }
        int rootSelectionStart = DatabaseGraphqlAst.operationSelectionStart(ast);
        if (rootSelectionStart < 0) {
            return "";
        }
        int rootOccurrence = 0;
        while (rootOccurrence < MAX_EXECUTION_SELECTION_FIELDS) {
            int rootCandidate = fragmentAwareFieldStartFromAst(query, ast, rootSelectionStart, "", rootOccurrence,
                    variablesJson, "Query");
            if (rootCandidate == -1) {
                break;
            }
            if (rootCandidate < 0) {
                return "";
            }
            if (rootCandidate != siblingOwnerField
                    && fieldsHaveEquivalentMergeHeadFromAst(query, ast, siblingOwnerField, rootCandidate)) {
                int rootChildSelection = fieldInfoValue(ast, rootCandidate, 1);
                if (rootChildSelection < 0) {
                    return "";
                }
                int relationOccurrence = 0;
                while (relationOccurrence < MAX_EXECUTION_SELECTION_FIELDS) {
                    int relationCandidate = fragmentAwareFieldStartFromAst(query, ast, rootChildSelection, "",
                            relationOccurrence, variablesJson, siblingParentType);
                    if (relationCandidate == -1) {
                        break;
                    }
                    if (relationCandidate < 0) {
                        return "";
                    }
                    if (fieldsHaveEquivalentMergeHeadFromAst(query, ast, parentFieldStart, relationCandidate)) {
                        int childSelectionStart = fieldInfoValue(ast, relationCandidate, 1);
                        if (childSelectionStart < 0) {
                            return "";
                        }
                        String childPlan = selectionPlanFromAst(query, ast, childSelectionStart, variablesJson,
                                childType, "");
                        plan = appendDistinctSelectionPlanEntriesFromAst(query, ast, plan, childPlan);
                        if (plan.length() == 0) {
                            return "";
                        }
                    }
                    relationOccurrence++;
                }
                if (relationOccurrence == MAX_EXECUTION_SELECTION_FIELDS) {
                    return "";
                }
            }
            rootOccurrence++;
        }
        return rootOccurrence == MAX_EXECUTION_SELECTION_FIELDS ? "" : plan;
    }

    /**
     * Returns whether one validated Relay connection selects only exact-count leaves and optional
     * connection type names. Generated executors use this narrow predicate to choose the count-only
     * parent batch without moving selection interpretation into the HTTP frontend. An empty,
     * malformed, or typename-only selection is deliberately ineligible.
     */
    public static boolean relayConnectionSelectionIsCountOnlyFromAst(
            String query,
            String ast,
            int parentFieldStart,
            String variablesJson,
            String siblingParentType,
            String connectionType,
            String ancestorTypePath
    ) {
        String plan = fieldSelectionPlanFromAst(query, ast, parentFieldStart, variablesJson,
                siblingParentType, connectionType, ancestorTypePath);
        int count = selectionPlanCount(plan);
        if (count <= 0) {
            return false;
        }
        boolean selectedCount = false;
        int index = 0;
        while (index < count) {
            int fieldStart = selectionPlanFieldStart(plan, index);
            if (fieldStart < 0) {
                return false;
            }
            if (rootFieldMatchesFromAst(query, ast, fieldStart, "totalCount")) {
                selectedCount = true;
            } else if (!rootFieldMatchesFromAst(query, ast, fieldStart, "__typename")) {
                return false;
            }
            index++;
        }
        return selectedCount;
    }

    /** True when a validated Relay connection needs a partitioned page or pageInfo read. */
    public static boolean relayConnectionSelectionHasPageFromAst(
            String query,
            String ast,
            int parentFieldStart,
            String variablesJson,
            String siblingParentType,
            String connectionType,
            String ancestorTypePath
    ) {
        String plan = fieldSelectionPlanFromAst(query, ast, parentFieldStart, variablesJson,
                siblingParentType, connectionType, ancestorTypePath);
        int count = selectionPlanCount(plan);
        if (count <= 0) {
            return false;
        }
        int index = 0;
        while (index < count) {
            int fieldStart = selectionPlanFieldStart(plan, index);
            if (fieldStart < 0) {
                return false;
            }
            if (rootFieldMatchesFromAst(query, ast, fieldStart, "edges")
                    || rootFieldMatchesFromAst(query, ast, fieldStart, "pageInfo")) {
                return true;
            }
            index++;
        }
        return false;
    }

    /**
     * Compiles direct child selections after collecting every merge-compatible occurrence of the
     * parent field along its complete typed path from {@code Query}. The type path contains the
     * source type of each field in that path, for example
     * {@code Query|Customer|Order} for {@code customer { orders { customer { ... } } }}.
     *
     * <p>This is intentionally a scalar frontier walk rather than a JVM merge tree. Each frontier
     * entry is an AST source offset; at every level it expands only fragment-enabled fields for
     * that generated parent type. The final frontier therefore contains every compatible copy of
     * the requested parent field, and their child selections can be safely coalesced into the
     * existing request-order plan carrier.</p>
     */
    public static String fieldSelectionPlanFromAst(
            String query,
            String ast,
            int parentFieldStart,
            String variablesJson,
            String siblingParentType,
            String childType,
            String ancestorTypePath
    ) {
        if (ancestorTypePath == null || ancestorTypePath.length() == 0) {
            return fieldSelectionPlanFromAst(query, ast, parentFieldStart, variablesJson, siblingParentType,
                    childType);
        }
        if (ast == null || parentFieldStart < 0 || siblingParentType == null || childType == null) {
            return "";
        }
        String ancestry = fieldAncestryPlanFromAst(query, ast, parentFieldStart);
        int ancestryCount = selectionPlanCount(ancestry);
        if (ancestryCount <= 0 || ancestryCount != typePathCount(ancestorTypePath)
                || !typePathEntry(ancestorTypePath, ancestryCount - 1).equals(siblingParentType)) {
            return "";
        }
        int operationSelection = DatabaseGraphqlAst.operationSelectionStart(ast);
        if (operationSelection < 0) {
            return "";
        }

        String frontier = "v1;";
        int level = 0;
        while (level < ancestryCount) {
            int expectedFieldStart = selectionPlanFieldStart(ancestry, level);
            String parentType = typePathEntry(ancestorTypePath, level);
            if (expectedFieldStart < 0 || parentType.length() == 0) {
                return "";
            }
            String nextFrontier = "v1;";
            if (level == 0) {
                nextFrontier = appendMergeHeadMatchesFromAst(query, ast, operationSelection, variablesJson,
                        parentType, expectedFieldStart, nextFrontier);
            } else {
                int frontierCount = selectionPlanCount(frontier);
                if (frontierCount <= 0) {
                    return "";
                }
                int frontierIndex = 0;
                while (frontierIndex < frontierCount) {
                    int ancestorFieldStart = selectionPlanFieldStart(frontier, frontierIndex);
                    int childSelection = fieldInfoValue(ast, ancestorFieldStart, 1);
                    if (ancestorFieldStart < 0 || childSelection < 0) {
                        return "";
                    }
                    nextFrontier = appendMergeHeadMatchesFromAst(query, ast, childSelection, variablesJson,
                            parentType, expectedFieldStart, nextFrontier);
                    if (nextFrontier.length() == 0) {
                        return "";
                    }
                    frontierIndex++;
                }
            }
            if (nextFrontier.length() == 0 || selectionPlanCount(nextFrontier) <= 0) {
                return "";
            }
            frontier = nextFrontier;
            level++;
        }

        String plan = "v1;";
        int frontierCount = selectionPlanCount(frontier);
        int frontierIndex = 0;
        while (frontierIndex < frontierCount) {
            int matchingParentStart = selectionPlanFieldStart(frontier, frontierIndex);
            int childSelection = fieldInfoValue(ast, matchingParentStart, 1);
            if (matchingParentStart < 0 || childSelection < 0) {
                return "";
            }
            String childPlan = selectionPlanFromAst(query, ast, childSelection, variablesJson, childType, "");
            plan = appendDistinctSelectionPlanEntriesFromAst(query, ast, plan, childPlan);
            if (plan.length() == 0) {
                return "";
            }
            frontierIndex++;
        }
        return plan;
    }

    /** Rebuilds one field's complete root-to-leaf ancestry as a bounded source-offset plan. */
    private static String fieldAncestryPlanFromAst(String query, String ast, int fieldStart) {
        int operationSelection = DatabaseGraphqlAst.operationSelectionStart(ast);
        if (operationSelection < 0 || fieldStart < 0) {
            return "";
        }
        String reversed = "";
        int current = fieldStart;
        int count = 0;
        while (count < MAX_EXECUTION_SELECTION_FIELDS) {
            int parentSelection = DatabaseGraphqlAst.fieldParentSelection(ast, current);
            if (parentSelection < 0) {
                return "";
            }
            reversed = current + ";" + reversed;
            count++;
            if (parentSelection == operationSelection) {
                return "v1;" + reversed;
            }
            int enclosingSelection = executionEnclosingSelectionFromAst(query, ast, parentSelection,
                    operationSelection, 0);
            if (enclosingSelection == operationSelection) {
                return "v1;" + reversed;
            }
            current = enclosingSelection < 0 ? -1
                    : DatabaseGraphqlAst.selectionOwnerFieldStart(ast, enclosingSelection);
            if (current < 0) {
                return "";
            }
        }
        return "";
    }

    /**
     * Collapses inline/named fragment topology to the executable selection that owns it. A field
     * physically declared inside a named fragment has no direct source-text parent field, but its
     * spread site does; preserving that route lets relation merge planning remain AST-resident.
     */
    private static int executionEnclosingSelectionFromAst(
            String query,
            String ast,
            int selectionStart,
            int operationSelection,
            int depth
    ) {
        if (selectionStart < 0 || operationSelection < 0 || depth < 0) {
            return -1;
        }
        int currentSelection = selectionStart;
        int steps = depth;
        while (steps < MAX_EXECUTION_SELECTION_FIELDS) {
            if (currentSelection == operationSelection
                    || DatabaseGraphqlAst.selectionOwnerFieldStart(ast, currentSelection) >= 0) {
                return currentSelection;
            }
            int inlineParent = DatabaseGraphqlAst.inlineFragmentParentSelection(ast, currentSelection);
            if (inlineParent >= 0) {
                currentSelection = inlineParent;
                steps++;
                continue;
            }
            String fragmentName = DatabaseGraphqlAst.fragmentDefinitionNameForSelection(query, ast, currentSelection);
            if (fragmentName.length() == 0) {
                return -1;
            }
            currentSelection = DatabaseGraphqlAst.namedFragmentSpreadParentSelection(query, ast, fragmentName, 0);
            if (currentSelection < 0) {
                return -1;
            }
            steps++;
        }
        return -1;
    }

    /** Appends every direct candidate with the same response-key/name/argument merge head. */
    private static String appendMergeHeadMatchesFromAst(
            String query,
            String ast,
            int selectionStart,
            String variablesJson,
            String parentType,
            int expectedFieldStart,
            String plan
    ) {
        if (selectionStart < 0 || expectedFieldStart < 0 || selectionPlanCount(plan) < 0) {
            return "";
        }
        int occurrence = 0;
        while (occurrence < MAX_EXECUTION_SELECTION_FIELDS) {
            int candidate = fragmentAwareFieldStartFromAst(query, ast, selectionStart, "", occurrence,
                    variablesJson, parentType);
            if (candidate == -1) {
                return plan;
            }
            if (candidate < 0) {
                return "";
            }
            if (fieldsHaveEquivalentMergeHeadFromAst(query, ast, expectedFieldStart, candidate)
                    && !selectionPlanContainsFieldStart(plan, candidate)) {
                if (selectionPlanCount(plan) >= MAX_EXECUTION_SELECTION_FIELDS - 1) {
                    return "";
                }
                plan = plan + candidate + ";";
            }
            occurrence++;
        }
        return "";
    }

    private static boolean selectionPlanContainsFieldStart(String plan, int fieldStart) {
        int count = selectionPlanCount(plan);
        int index = 0;
        while (index < count) {
            if (selectionPlanFieldStart(plan, index) == fieldStart) {
                return true;
            }
            index++;
        }
        return false;
    }

    private static int typePathCount(String typePath) {
        if (typePath == null || typePath.length() == 0) {
            return 0;
        }
        int count = 1;
        int position = 0;
        while (position < typePath.length()) {
            int separator = typePath.indexOf('|', position);
            if (separator < 0) {
                return position < typePath.length() ? count : 0;
            }
            if (separator == position || separator == typePath.length() - 1) {
                return 0;
            }
            count++;
            if (count >= MAX_EXECUTION_SELECTION_FIELDS) {
                return 0;
            }
            position = separator + 1;
        }
        return 0;
    }

    private static String typePathEntry(String typePath, int ordinal) {
        if (typePath == null || ordinal < 0) {
            return "";
        }
        int start = 0;
        int index = 0;
        while (start < typePath.length()) {
            int end = typePath.indexOf('|', start);
            if (end < 0) {
                end = typePath.length();
            }
            if (end == start) {
                return "";
            }
            if (index == ordinal) {
                return typePath.substring(start, end);
            }
            if (end >= typePath.length()) {
                return "";
            }
            start = end + 1;
            index++;
        }
        return "";
    }

    /**
     * Collects direct child selections from all merge-compatible copies of one point root. This
     * is intentionally bounded and source-location based: it extends the scalar plan carrier
     * without materializing a JVM AST or letting the HTTP frontend combine GraphQL response JSON.
     */
    public static String rootFieldSelectionPlan(
            String query,
            int rootFieldStart,
            String variablesJson,
            String parentType
    ) {
        return rootFieldSelectionPlan(query, documentPlan(query), rootFieldStart, variablesJson, parentType);
    }

    /** Collects compatible point-root child selections using the request-local language plan. */
    public static String rootFieldSelectionPlan(
            String query,
            String languagePlan,
            int rootFieldStart,
            String variablesJson,
            String parentType
    ) {
        int rootSelectionStart = topSelectionStart(query, languagePlan);
        if (rootSelectionStart < 0 || rootFieldStart < 0) {
            return "";
        }
        String plan = "v1;";
        int occurrence = 0;
        while (occurrence < MAX_EXECUTION_SELECTION_FIELDS) {
            int candidate = fragmentAwareFieldStart(query, languagePlan, rootSelectionStart, "", occurrence,
                    variablesJson, "Query");
            if (candidate == -1) {
                return plan;
            }
            if (candidate < 0) {
                return "";
            }
            if (fieldsHaveEquivalentMergeHead(query, languagePlan, rootFieldStart, candidate)) {
                int childSelectionStart = DatabaseGraphqlLanguage.fieldSelectionStart(query, languagePlan, candidate);
                if (childSelectionStart < 0) {
                    return "";
                }
                String childPlan = selectionPlan(query, languagePlan, childSelectionStart, variablesJson, parentType, "");
                plan = appendDistinctSelectionPlanEntries(query, languagePlan, plan, childPlan);
                if (plan.length() == 0) {
                    return "";
                }
            }
            occurrence++;
        }
        return "";
    }

    /** Collects compatible point-root child selections from the caller-retained typed AST. */
    public static String rootFieldSelectionPlanFromAst(
            String query,
            String ast,
            int rootFieldStart,
            String variablesJson,
            String parentType
    ) {
        int rootSelectionStart = DatabaseGraphqlAst.operationSelectionStart(ast);
        if (rootSelectionStart < 0 || rootFieldStart < 0) {
            return "";
        }
        String plan = "v1;";
        int occurrence = 0;
        while (occurrence < MAX_EXECUTION_SELECTION_FIELDS) {
            int candidate = fragmentAwareFieldStartFromAst(query, ast, rootSelectionStart, "", occurrence,
                    variablesJson, "Query");
            if (candidate == -1) {
                return plan;
            }
            if (candidate < 0) {
                return "";
            }
            if (fieldsHaveEquivalentMergeHeadFromAst(query, ast, rootFieldStart, candidate)) {
                int childSelectionStart = fieldInfoValue(ast, candidate, 1);
                if (childSelectionStart < 0) {
                    return "";
                }
                String childPlan = selectionPlanFromAst(query, ast, childSelectionStart, variablesJson,
                        parentType, "");
                plan = appendDistinctSelectionPlanEntriesFromAst(query, ast, plan, childPlan);
                if (plan.length() == 0) {
                    return "";
                }
            }
            occurrence++;
        }
        return "";
    }

    /** Returns the number of entries in a plan, or {@code -1} when the carrier is invalid. */
    public static int selectionPlanCount(String plan) {
        if (plan == null || !startsWith(plan, "v1;")) {
            return -1;
        }
        int position = 3;
        int count = 0;
        while (position < plan.length()) {
            int end = selectionPlanEntryEnd(plan, position);
            int value = end < 0 ? -1 : selectionPlanEntryValue(plan, position, end);
            if (value < 0 || value >= MAX_QUERY_CHARACTERS) {
                return -1;
            }
            count++;
            if (count >= MAX_EXECUTION_SELECTION_FIELDS) {
                return -1;
            }
            position = end + 1;
        }
        return count;
    }

    /** Returns one source location from a validated scalar selection plan. */
    public static int selectionPlanFieldStart(String plan, int ordinal) {
        if (ordinal < 0 || plan == null || !startsWith(plan, "v1;")) {
            return -1;
        }
        int position = 3;
        int index = 0;
        while (position < plan.length()) {
            int end = selectionPlanEntryEnd(plan, position);
            int value = end < 0 ? -1 : selectionPlanEntryValue(plan, position, end);
            if (value < 0 || value >= MAX_QUERY_CHARACTERS) {
                return -1;
            }
            if (index == ordinal) {
                return value;
            }
            index++;
            if (index >= MAX_EXECUTION_SELECTION_FIELDS) {
                return -1;
            }
            position = end + 1;
        }
        return -1;
    }

    /**
     * Finds one schema field in a request-order AST plan without reopening the parent selection.
     * An ambiguous same-schema-name plan is rejected by returning no field: generated callers then
     * compare their recognized fields with the plan cardinality and fail closed rather than losing
     * an alias or emitting a duplicate JSON member.
     */
    public static int selectionPlanFieldStartForFieldFromAst(
            String query,
            String ast,
            String plan,
            String expectedField
    ) {
        if (expectedField == null || expectedField.length() == 0) {
            return -1;
        }
        int count = selectionPlanCount(plan);
        if (count < 0) {
            return -1;
        }
        int found = -1;
        int index = 0;
        while (index < count) {
            int fieldStart = selectionPlanFieldStart(plan, index);
            if (fieldStart < 0) {
                return -1;
            }
            if (rootFieldMatchesFromAst(query, ast, fieldStart, expectedField)) {
                if (found >= 0) {
                    return -1;
                }
                found = fieldStart;
            }
            index++;
        }
        return found;
    }

    /**
     * Returns the first requested occurrence of one schema field from a validated AST plan.
     * Unlike {@link #selectionPlanFieldStartForFieldFromAst}, this deliberately permits several
     * response-key aliases of the same schema field so generated renderers can validate and emit
     * every response member without reopening the parent selection.
     */
    public static int selectionPlanFirstFieldStartForFieldFromAst(
            String query,
            String ast,
            String plan,
            String expectedField
    ) {
        if (expectedField == null || expectedField.length() == 0) {
            return -1;
        }
        int count = selectionPlanCount(plan);
        if (count < 0) {
            return -1;
        }
        int index = 0;
        while (index < count) {
            int fieldStart = selectionPlanFieldStart(plan, index);
            if (fieldStart < 0) {
                return -1;
            }
            if (rootFieldMatchesFromAst(query, ast, fieldStart, expectedField)) {
                return fieldStart;
            }
            index++;
        }
        return -1;
    }

    /**
     * Counts occurrences of one schema field in a validated AST plan. The count retains distinct
     * response-key aliases; {@code -1} denotes an invalid carrier rather than an absent field.
     */
    public static int selectionPlanFieldCountForFieldFromAst(
            String query,
            String ast,
            String plan,
            String expectedField
    ) {
        if (expectedField == null || expectedField.length() == 0) {
            return -1;
        }
        int count = selectionPlanCount(plan);
        if (count < 0) {
            return -1;
        }
        int matches = 0;
        int index = 0;
        while (index < count) {
            int fieldStart = selectionPlanFieldStart(plan, index);
            if (fieldStart < 0) {
                return -1;
            }
            if (rootFieldMatchesFromAst(query, ast, fieldStart, expectedField)) {
                matches++;
            }
            index++;
        }
        return matches;
    }

    /**
     * Reports a response-key collision that was not safely collapsed while building the plan.
     * Exact field duplicates are collapsed by {@link #selectionPlan}; remaining collisions need
     * a schema-aware, nested field-merge plan and are rejected rather than emitting duplicate
     * JSON object members.
     */
    public static boolean selectionPlanHasDuplicateResponseKey(String query, String languagePlan, String plan) {
        int count = selectionPlanCount(plan);
        if (count < 0) {
            return true;
        }
        String seen = ",";
        int index = 0;
        while (index < count) {
            int fieldStart = selectionPlanFieldStart(plan, index);
            String key = DatabaseGraphqlLanguage.fieldResponseKey(query, languagePlan, fieldStart);
            if (key.length() == 0 || commaSeparatedNameContains(seen, key)) {
                return true;
            }
            seen = seen + key + ",";
            index++;
        }
        return false;
    }

    /** Checks response-key collisions from typed field metadata, not a syntax-index identity lookup. */
    public static boolean selectionPlanHasDuplicateResponseKeyFromAst(String query, String ast, String plan) {
        int count = selectionPlanCount(plan);
        if (count < 0) {
            return true;
        }
        String seen = ",";
        int index = 0;
        while (index < count) {
            int fieldStart = selectionPlanFieldStart(plan, index);
            String key = fieldResponseKey(query, ast, fieldStart);
            if (key.length() == 0 || commaSeparatedNameContains(seen, key)) {
                return true;
            }
            seen = seen + key + ",";
            index++;
        }
        return false;
    }

    /** Finds an enabled field after expansion of named/inline fragments for one generated object type. */
    public static int selectionFieldStart(
            String query,
            int parentFieldStart,
            String expectedField,
            String variablesJson,
            String parentType
    ) {
        return selectionFieldStart(query, documentPlan(query), parentFieldStart, expectedField, variablesJson, parentType);
    }

    /** Finds an enabled, fragment-expanded child using the request-local language plan. */
    public static int selectionFieldStart(
            String query,
            String languagePlan,
            int parentFieldStart,
            String expectedField,
            String variablesJson,
            String parentType
    ) {
        int start = DatabaseGraphqlLanguage.fieldSelectionStart(query, languagePlan, parentFieldStart);
        return start < 0 ? -1 : fragmentAwareFieldStart(query, languagePlan, start, expectedField, 0,
                variablesJson, parentType);
    }

    /** Finds an expanded child from the request-local AST. */
    public static int selectionFieldStartFromAst(
            String query,
            String ast,
            int parentFieldStart,
            String expectedField,
            String variablesJson,
            String parentType
    ) {
        int start = fieldInfoValue(ast, parentFieldStart, 1);
        return start < 0 ? -1 : fragmentAwareFieldStartFromAst(query, ast, start, expectedField, 0,
                variablesJson, parentType);
    }

    /**
     * Compiles one introspection object's direct selections once.  Introspection can render many
     * descriptor entries from the same selected child shape, so reusing this scalar AST plan
     * prevents each entry from repeating fragment-aware lookup for every known field name.
     */
    public static String introspectionSelectionPlanFromAst(
            String query,
            String ast,
            int parentFieldStart,
            String variablesJson,
            String parentType
    ) {
        int selectionStart = fieldInfoValue(ast, parentFieldStart, 1);
        return selectionStart < 0 ? "" : selectionPlanFromAst(query, ast, selectionStart, variablesJson,
                parentType, "");
    }

    /** Finds one schema field in a pre-expanded scalar selection plan without rewalking fragments. */
    private static int introspectionSelectionPlanFieldStart(
            String query,
            String ast,
            String plan,
            String expectedField
    ) {
        int count = selectionPlanCount(plan);
        if (count < 0) {
            return INVALID_SELECTION;
        }
        int index = 0;
        while (index < count) {
            int fieldStart = selectionPlanFieldStart(plan, index);
            if (fieldStart < 0) {
                return INVALID_SELECTION;
            }
            if (fieldName(query, ast, fieldStart).equals(expectedField)) {
                return fieldStart;
            }
            index++;
        }
        return -1;
    }

    /** Counts enabled fields after expansion of named/inline fragments for one generated object type. */
    public static int selectionFieldCount(
            String query,
            int parentFieldStart,
            String variablesJson,
            String parentType
    ) {
        return selectionFieldCount(query, documentPlan(query), parentFieldStart, variablesJson, parentType);
    }

    /** Counts enabled, fragment-expanded children using the request-local language plan. */
    public static int selectionFieldCount(
            String query,
            String languagePlan,
            int parentFieldStart,
            String variablesJson,
            String parentType
    ) {
        int start = DatabaseGraphqlLanguage.fieldSelectionStart(query, languagePlan, parentFieldStart);
        return start < 0 ? 0 : fragmentAwareFieldCount(query, languagePlan, start, variablesJson, parentType);
    }

    /** Counts expanded children from the request-local AST. */
    public static int selectionFieldCountFromAst(
            String query,
            String ast,
            int parentFieldStart,
            String variablesJson,
            String parentType
    ) {
        int start = fieldInfoValue(ast, parentFieldStart, 1);
        return start < 0 ? 0 : fragmentAwareFieldCountFromAst(query, ast, start, variablesJson, parentType);
    }

    /**
     * Traverses a selection set without materializing a Java object graph. The work stack encodes
     * parsed selection offsets, item ordinals, and fragment ancestry as a bounded scalar, so it is
     * invocation-local and transpilable on both supported dialects. Returning a field location
     * preserves the generated bindings' aliases, arguments, and response-key handling while
     * keeping fragment expansion out of their schema-specific branches.
     */
    private static int fragmentAwareFieldStart(
            String query,
            int selectionStart,
            String expectedField,
            int occurrence,
            String variablesJson,
            String parentType
    ) {
        return fragmentAwareFieldStart(query, documentPlan(query), selectionStart, expectedField, occurrence,
                variablesJson, parentType);
    }

    private static int fragmentAwareFieldStart(
            String query,
            String languagePlan,
            int selectionStart,
            String expectedField,
            int occurrence,
            String variablesJson,
            String parentType
    ) {
        if (occurrence < 0) {
            return -1;
        }
        String ast = DatabaseGraphqlAst.parse(query, languagePlan);
        if (ast.length() == 0 || DatabaseGraphqlAst.selectionItemCount(ast, selectionStart) < 0) {
            return INVALID_SELECTION;
        }
        String pending = fragmentStackEntry(selectionStart, 0, "|");
        int expansions = 0;
        int matched = 0;
        while (pending.length() != 0) {
            int firstColon = fragmentStateDelimiter(pending, 0, ':');
            int secondColon = firstColon < 0 ? -1 : fragmentStateDelimiter(pending, firstColon + 1, ':');
            int semicolon = secondColon < 0 ? -1 : fragmentStateDelimiter(pending, secondColon + 1, ';');
            if (firstColon < 0 || secondColon < 0 || semicolon < 0) {
                return INVALID_SELECTION;
            }
            int currentSelection = fragmentStateInt(pending, 0, firstColon);
            int itemIndex = fragmentStateInt(pending, firstColon + 1, secondColon);
            if (currentSelection < 0 || itemIndex < 0) {
                return INVALID_SELECTION;
            }
            String path = pending.substring(secondColon + 1, semicolon);
            pending = pending.substring(semicolon + 1);
            int itemCount = DatabaseGraphqlAst.selectionItemCount(ast, currentSelection);
            if (itemCount < 0 || itemIndex > itemCount) {
                return INVALID_SELECTION;
            }
            while (itemIndex < itemCount) {
                String itemInfo = DatabaseGraphqlAst.selectionItemInfo(ast, currentSelection, itemIndex);
                int nodeStart = DatabaseGraphqlLanguage.planInfoValue(itemInfo, 0);
                int itemKindStart = itemInfo.indexOf(':');
                int itemPayloadStart = itemKindStart < 0 ? -1 : itemInfo.indexOf(':', itemKindStart + 1);
                String itemKind = itemPayloadStart < 0 ? "" : itemInfo.substring(itemKindStart + 1, itemPayloadStart);
                String itemPayload = itemPayloadStart < 0 ? "" : itemInfo.substring(itemPayloadStart + 1);
                if (nodeStart < 0 || itemKind.length() != 1) {
                    return INVALID_SELECTION;
                }
                if (itemKind.equals("F")) {
                    int inclusion = selectionInclusion(query, languagePlan, nodeStart, variablesJson);
                    if (inclusion < 0) {
                        return INVALID_SELECTION;
                    }
                    if (inclusion > 0 && (expectedField.length() == 0
                            || fieldMatches(query, languagePlan, nodeStart, expectedField))) {
                        if (matched == occurrence) {
                            return nodeStart;
                        }
                        matched++;
                    }
                    itemIndex++;
                    continue;
                }
                int nestedSelection;
                String nestedPath = path;
                int inclusion;
                if (itemKind.equals("P")) {
                    int nameStart = astInfoValue(itemPayload, 0);
                    int nameEnd = astInfoValue(itemPayload, 1);
                    int directivesStart = astInfoValue(itemPayload, 2);
                    if (nameStart < 0 || nameEnd <= nameStart || directivesStart < nameEnd
                            || directivesStart > query.length()) {
                        return INVALID_SELECTION;
                    }
                    inclusion = directiveInclusion(query, languagePlan, directivesStart, variablesJson);
                    if (inclusion < 0) {
                        return INVALID_SELECTION;
                    }
                    String fragmentName = query.substring(nameStart, nameEnd);
                    if (inclusion == 0) {
                        itemIndex++;
                        continue;
                    }
                    if (fragmentPathContains(path, fragmentName)) {
                        return INVALID_SELECTION;
                    }
                    nestedSelection = fragmentDefinitionSelectionStartFromAst(
                            query, ast, fragmentName, parentType);
                    if (nestedSelection == INAPPLICABLE_FRAGMENT) {
                        itemIndex++;
                        continue;
                    }
                    if (nestedSelection < 0) {
                        return INVALID_SELECTION;
                    }
                    nestedPath = path + fragmentName + "|";
                } else if (itemKind.equals("I")) {
                    int typeStart = astInfoValue(itemPayload, 0);
                    int typeEnd = astInfoValue(itemPayload, 1);
                    int directivesStart = astInfoValue(itemPayload, 2);
                    nestedSelection = astInfoValue(itemPayload, 3);
                    int selectionEnd = astInfoValue(itemPayload, 4);
                    if ((typeStart < 0) != (typeEnd < 0) || (typeStart >= 0 && typeEnd <= typeStart)
                            || directivesStart < 0 || nestedSelection < directivesStart || selectionEnd <= nestedSelection
                            || selectionEnd > query.length()) {
                        return INVALID_SELECTION;
                    }
                    if (typeStart >= 0 && parentType != null && parentType.length() != 0
                            && !runtimeTypeConditionMatches(
                            parentType, query.substring(typeStart, typeEnd))) {
                        itemIndex++;
                        continue;
                    }
                    inclusion = directiveInclusion(query, languagePlan, directivesStart, variablesJson);
                    if (inclusion < 0) {
                        return INVALID_SELECTION;
                    }
                    if (inclusion == 0) {
                        itemIndex++;
                        continue;
                    }
                } else {
                    return INVALID_SELECTION;
                }
                expansions++;
                if (expansions > MAX_FRAGMENT_EXPANSIONS) {
                    return INVALID_SELECTION;
                }
                // A LIFO selection-item stack preserves GraphQL source order: the spread's
                // parsed child selection runs before the current selection's remaining items.
                pending = fragmentStackEntry(nestedSelection, 0, nestedPath)
                        + fragmentStackEntry(currentSelection, itemIndex + 1, path) + pending;
                if (pending.length() > MAX_FRAGMENT_WORK_CHARACTERS) {
                    return INVALID_SELECTION;
                }
                break;
            }
        }
        return -1;
    }

    /** Traverses the caller-retained AST; no executable-field walk reparses the request. */
    private static int fragmentAwareFieldStartFromAst(
            String query,
            String ast,
            int selectionStart,
            String expectedField,
            int occurrence,
            String variablesJson,
            String parentType
    ) {
        if (occurrence < 0 || ast == null || ast.length() == 0
                || DatabaseGraphqlAst.selectionItemCount(ast, selectionStart) < 0) {
            return INVALID_SELECTION;
        }
        String pending = fragmentStackEntry(selectionStart, 0, "|");
        int expansions = 0;
        int matched = 0;
        while (pending.length() != 0) {
            int firstColon = fragmentStateDelimiter(pending, 0, ':');
            int secondColon = firstColon < 0 ? -1 : fragmentStateDelimiter(pending, firstColon + 1, ':');
            int semicolon = secondColon < 0 ? -1 : fragmentStateDelimiter(pending, secondColon + 1, ';');
            if (firstColon < 0 || secondColon < 0 || semicolon < 0) {
                return INVALID_SELECTION;
            }
            int currentSelection = fragmentStateInt(pending, 0, firstColon);
            int itemIndex = fragmentStateInt(pending, firstColon + 1, secondColon);
            if (currentSelection < 0 || itemIndex < 0) {
                return INVALID_SELECTION;
            }
            String path = pending.substring(secondColon + 1, semicolon);
            pending = pending.substring(semicolon + 1);
            int itemCount = DatabaseGraphqlAst.selectionItemCount(ast, currentSelection);
            if (itemCount < 0 || itemIndex > itemCount) {
                return INVALID_SELECTION;
            }
            while (itemIndex < itemCount) {
                String itemInfo = DatabaseGraphqlAst.selectionItemInfo(ast, currentSelection, itemIndex);
                int nodeStart = DatabaseGraphqlLanguage.planInfoValue(itemInfo, 0);
                int itemKindStart = itemInfo.indexOf(':');
                int itemPayloadStart = itemKindStart < 0 ? -1 : itemInfo.indexOf(':', itemKindStart + 1);
                String itemKind = itemPayloadStart < 0 ? "" : itemInfo.substring(itemKindStart + 1, itemPayloadStart);
                String itemPayload = itemPayloadStart < 0 ? "" : itemInfo.substring(itemPayloadStart + 1);
                if (nodeStart < 0 || itemKind.length() != 1) {
                    return INVALID_SELECTION;
                }
                if (itemKind.equals("F")) {
                    int inclusion = selectionInclusionFromAst(query, ast, nodeStart, variablesJson);
                    if (inclusion < 0) {
                        return INVALID_SELECTION;
                    }
                    if (inclusion > 0 && (expectedField.length() == 0
                            || astFieldMatches(query, ast, nodeStart, expectedField))) {
                        if (matched == occurrence) {
                            return nodeStart;
                        }
                        matched++;
                    }
                    itemIndex++;
                    continue;
                }
                int nestedSelection;
                String nestedPath = path;
                int inclusion;
                if (itemKind.equals("P")) {
                    int nameStart = astInfoValue(itemPayload, 0);
                    int nameEnd = astInfoValue(itemPayload, 1);
                    int directivesStart = astInfoValue(itemPayload, 2);
                    if (nameStart < 0 || nameEnd <= nameStart || directivesStart < nameEnd
                            || directivesStart > query.length()) {
                        return INVALID_SELECTION;
                    }
                    inclusion = directiveInclusionFromAst(query, ast, directivesStart, variablesJson, "P");
                    if (inclusion < 0) {
                        return INVALID_SELECTION;
                    }
                    String fragmentName = query.substring(nameStart, nameEnd);
                    if (inclusion == 0) {
                        itemIndex++;
                        continue;
                    }
                    if (fragmentPathContains(path, fragmentName)) {
                        return INVALID_SELECTION;
                    }
                    nestedSelection = fragmentDefinitionSelectionStartFromAst(
                            query, ast, fragmentName, parentType);
                    if (nestedSelection == INAPPLICABLE_FRAGMENT) {
                        itemIndex++;
                        continue;
                    }
                    if (nestedSelection < 0) {
                        return INVALID_SELECTION;
                    }
                    nestedPath = path + fragmentName + "|";
                } else if (itemKind.equals("I")) {
                    int typeStart = astInfoValue(itemPayload, 0);
                    int typeEnd = astInfoValue(itemPayload, 1);
                    int directivesStart = astInfoValue(itemPayload, 2);
                    nestedSelection = astInfoValue(itemPayload, 3);
                    int selectionEnd = astInfoValue(itemPayload, 4);
                    if ((typeStart < 0) != (typeEnd < 0) || (typeStart >= 0 && typeEnd <= typeStart)
                            || directivesStart < 0 || nestedSelection < directivesStart || selectionEnd <= nestedSelection
                            || selectionEnd > query.length()) {
                        return INVALID_SELECTION;
                    }
                    if (typeStart >= 0 && parentType != null && parentType.length() != 0
                            && !runtimeTypeConditionMatches(
                            parentType, query.substring(typeStart, typeEnd))) {
                        itemIndex++;
                        continue;
                    }
                    inclusion = directiveInclusionFromAst(query, ast, directivesStart, variablesJson, "I");
                    if (inclusion < 0) {
                        return INVALID_SELECTION;
                    }
                    if (inclusion == 0) {
                        itemIndex++;
                        continue;
                    }
                } else {
                    return INVALID_SELECTION;
                }
                expansions++;
                if (expansions > MAX_FRAGMENT_EXPANSIONS) {
                    return INVALID_SELECTION;
                }
                pending = fragmentStackEntry(nestedSelection, 0, nestedPath)
                        + fragmentStackEntry(currentSelection, itemIndex + 1, path) + pending;
                if (pending.length() > MAX_FRAGMENT_WORK_CHARACTERS) {
                    return INVALID_SELECTION;
                }
                break;
            }
        }
        return -1;
    }

    private static String selectionPlan(
            String query,
            String languagePlan,
            int selectionStart,
            String variablesJson,
            String parentType,
            String mergeablePointRootNames
    ) {
        String plan = "v1;";
        int occurrence = 0;
        while (occurrence < MAX_EXECUTION_SELECTION_FIELDS) {
            int fieldStart = fragmentAwareFieldStart(query, languagePlan, selectionStart, "", occurrence, variablesJson, parentType);
            if (fieldStart == -1) {
                return plan;
            }
            if (fieldStart < 0) {
                return "";
            }
            // GraphQL merges exact duplicate fields.  Keep the first selection in source order,
            // which avoids duplicate JSON members and duplicate SQL work while preserving the
            // identical response shape.  A same-key field with a different shape remains in the
            // plan so selectionPlanHasDuplicateResponseKey can reject it explicitly.
            if (!selectionPlanContainsEquivalentField(query, languagePlan, plan, fieldStart)
                    && !selectionPlanContainsMergeablePointRoot(query, languagePlan, plan, fieldStart,
                    mergeablePointRootNames)) {
                plan = plan + fieldStart + ";";
            }
            occurrence++;
        }
        // Keep the same strict limit as the direct traversal: a full-capacity plan is refused
        // rather than leaving no room to distinguish "exactly at the cap" from excess work.
        return "";
    }

    /** Compiles a bounded selection plan from the retained request AST. */
    private static String selectionPlanFromAst(
            String query,
            String ast,
            int selectionStart,
            String variablesJson,
            String parentType,
            String mergeablePointRootNames
    ) {
        String directPlan = DatabaseGraphqlAst.directFieldSelectionPlan(ast, selectionStart);
        if (startsWith(directPlan, "v1;")) {
            int directCount = selectionPlanCount(directPlan);
            if (directCount < 0) {
                return "";
            }
            String plan = "v1;";
            int directIndex = 0;
            while (directIndex < directCount) {
                int fieldStart = selectionPlanFieldStart(directPlan, directIndex);
                if (fieldStart < 0) {
                    return "";
                }
                int inclusion = selectionInclusionFromAst(query, ast, fieldStart, variablesJson);
                if (inclusion < 0) {
                    return "";
                }
                if (inclusion > 0 && !selectionPlanContainsEquivalentFieldFromAst(query, ast, plan, fieldStart)
                        && !selectionPlanContainsMergeablePointRootFromAst(query, ast, plan, fieldStart,
                        mergeablePointRootNames)) {
                    plan = plan + fieldStart + ";";
                }
                directIndex++;
            }
            return plan;
        }
        if (!directPlan.equals("fragment;")) {
            return "";
        }
        String plan = "v1;";
        int occurrence = 0;
        while (occurrence < MAX_EXECUTION_SELECTION_FIELDS) {
            int fieldStart = fragmentAwareFieldStartFromAst(query, ast, selectionStart, "", occurrence,
                    variablesJson, parentType);
            if (fieldStart == -1) {
                return plan;
            }
            if (fieldStart < 0) {
                return "";
            }
            if (!selectionPlanContainsEquivalentFieldFromAst(query, ast, plan, fieldStart)
                    && !selectionPlanContainsMergeablePointRootFromAst(query, ast, plan, fieldStart,
                    mergeablePointRootNames)) {
                plan = plan + fieldStart + ";";
            }
            occurrence++;
        }
        return "";
    }

    /** Returns whether a generated point root already has a compatible collected field. */
    private static boolean selectionPlanContainsMergeablePointRoot(
            String query,
            String languagePlan,
            String plan,
            int fieldStart,
            String mergeablePointRootNames
    ) {
        int actualNameStart = fieldActualNameStart(query, languagePlan, fieldStart);
        if (actualNameStart < 0 || mergeablePointRootNames == null || mergeablePointRootNames.length() == 0) {
            return false;
        }
        int actualNameEnd = DatabaseGraphqlLanguage.rangeEnd(
                DatabaseGraphqlLanguage.fieldNameRange(query, languagePlan, fieldStart));
        if (!commaSeparatedNameContains(mergeablePointRootNames,
                query.substring(actualNameStart, actualNameEnd))) {
            return false;
        }
        int count = selectionPlanCount(plan);
        if (count < 0) {
            return false;
        }
        int index = 0;
        while (index < count) {
            int priorFieldStart = selectionPlanFieldStart(plan, index);
            if (fieldsHaveEquivalentMergeHead(query, languagePlan, priorFieldStart, fieldStart)) {
                return true;
            }
            index++;
        }
        return false;
    }

    private static boolean selectionPlanContainsMergeablePointRootFromAst(
            String query,
            String ast,
            String plan,
            int fieldStart,
            String mergeablePointRootNames
    ) {
        int actualNameStart = fieldInfoValue(ast, fieldStart, 4);
        int actualNameEnd = fieldInfoValue(ast, fieldStart, 5);
        if (actualNameStart < 0 || actualNameEnd <= actualNameStart
                || mergeablePointRootNames == null || mergeablePointRootNames.length() == 0) {
            return false;
        }
        if (!commaSeparatedNameContains(mergeablePointRootNames,
                query.substring(actualNameStart, actualNameEnd))) {
            return false;
        }
        int count = selectionPlanCount(plan);
        if (count < 0) {
            return false;
        }
        int index = 0;
        while (index < count) {
            int priorFieldStart = selectionPlanFieldStart(plan, index);
            if (fieldsHaveEquivalentMergeHeadFromAst(query, ast, priorFieldStart, fieldStart)) {
                return true;
            }
            index++;
        }
        return false;
    }

    /**
     * Implements the root portion of GraphQL's field-merge compatibility check. Directives are
     * evaluated before a field reaches the plan, so the retained identity is response key, actual
     * field name, and arguments. Nested response-key conflicts are still checked by the child
     * plan rather than being silently combined.
     */
    private static boolean fieldsHaveEquivalentMergeHead(
            String query,
            String languagePlan,
            int firstFieldStart,
            int secondFieldStart
    ) {
        String firstResponseKey = DatabaseGraphqlLanguage.fieldResponseKey(query, languagePlan, firstFieldStart);
        if (firstResponseKey.length() == 0 || !firstResponseKey.equals(
                DatabaseGraphqlLanguage.fieldResponseKey(query, languagePlan, secondFieldStart))) {
            return false;
        }
        int firstNameStart = fieldActualNameStart(query, languagePlan, firstFieldStart);
        int secondNameStart = fieldActualNameStart(query, languagePlan, secondFieldStart);
        if (firstNameStart < 0 || secondNameStart < 0) {
            return false;
        }
        int firstHeaderEnd = DatabaseGraphqlLanguage.fieldHeaderEnd(query, languagePlan, firstFieldStart);
        int secondHeaderEnd = DatabaseGraphqlLanguage.fieldHeaderEnd(query, languagePlan, secondFieldStart);
        return firstHeaderEnd > firstNameStart && secondHeaderEnd > secondNameStart
                && DatabaseGraphqlLanguage.tokenRangesEqual(query, languagePlan, firstNameStart, firstHeaderEnd,
                secondNameStart, secondHeaderEnd);
    }

    private static boolean fieldsHaveEquivalentMergeHeadFromAst(
            String query,
            String ast,
            int firstFieldStart,
            int secondFieldStart
    ) {
        String firstResponseKey = fieldResponseKey(query, ast, firstFieldStart);
        if (firstResponseKey.length() == 0 || !firstResponseKey.equals(
                fieldResponseKey(query, ast, secondFieldStart))) {
            return false;
        }
        String firstName = fieldName(query, ast, firstFieldStart);
        return firstName.length() != 0 && firstName.equals(fieldName(query, ast, secondFieldStart))
                && fieldArgumentsHaveEquivalentMergeShapeFromAst(
                query, ast, firstFieldStart, secondFieldStart);
    }

    /**
     * Implements GraphQL's unordered argument-set comparison for field collection. Argument
     * names must match one-to-one while each corresponding value retains its token identity.
     * This deliberately compares source values rather than runtime-coerced values: two different
     * variable references do not become merge-compatible merely because one request supplies the
     * same JSON value for both variables.
     */
    private static boolean fieldArgumentsHaveEquivalentMergeShapeFromAst(
            String query,
            String ast,
            int firstFieldStart,
            int secondFieldStart
    ) {
        int firstCount = DatabaseGraphqlAst.fieldArgumentCount(ast, firstFieldStart);
        int secondCount = DatabaseGraphqlAst.fieldArgumentCount(ast, secondFieldStart);
        if (query == null || firstCount < 0 || firstCount != secondCount) {
            return false;
        }
        int firstIndex = 0;
        while (firstIndex < firstCount) {
            String firstInfo = DatabaseGraphqlAst.fieldArgumentInfo(ast, firstFieldStart, firstIndex);
            int firstNameStart = astInfoValue(firstInfo, 0);
            int firstNameEnd = astInfoValue(firstInfo, 1);
            int firstValueStart = astInfoValue(firstInfo, 2);
            int firstValueEnd = astInfoValue(firstInfo, 3);
            if (firstNameStart < 0 || firstNameEnd <= firstNameStart || firstNameEnd > query.length()
                    || firstValueStart < 0 || firstValueEnd <= firstValueStart
                    || firstValueEnd > query.length()) {
                return false;
            }
            String firstName = query.substring(firstNameStart, firstNameEnd);
            int matches = 0;
            int secondIndex = 0;
            while (secondIndex < secondCount) {
                String secondInfo = DatabaseGraphqlAst.fieldArgumentInfo(ast, secondFieldStart, secondIndex);
                int secondNameStart = astInfoValue(secondInfo, 0);
                int secondNameEnd = astInfoValue(secondInfo, 1);
                int secondValueStart = astInfoValue(secondInfo, 2);
                int secondValueEnd = astInfoValue(secondInfo, 3);
                if (secondNameStart < 0 || secondNameEnd <= secondNameStart
                        || secondNameEnd > query.length() || secondValueStart < 0
                        || secondValueEnd <= secondValueStart || secondValueEnd > query.length()) {
                    return false;
                }
                if (firstName.equals(query.substring(secondNameStart, secondNameEnd))) {
                    matches++;
                    if (matches > 1 || !DatabaseGraphqlLanguage.inputValuesAreEquivalent(
                            query.substring(firstValueStart, firstValueEnd),
                            query.substring(secondValueStart, secondValueEnd))) {
                        return false;
                    }
                }
                secondIndex++;
            }
            if (matches != 1) {
                return false;
            }
            firstIndex++;
        }
        return true;
    }

    /** Appends a validated child plan while retaining exact duplicate collapse and the field cap. */
    private static String appendDistinctSelectionPlanEntries(
            String query,
            String languagePlan,
            String plan,
            String childPlan
    ) {
        int childCount = selectionPlanCount(childPlan);
        if (childCount < 0 || selectionPlanCount(plan) < 0) {
            return "";
        }
        int index = 0;
        while (index < childCount) {
            int childFieldStart = selectionPlanFieldStart(childPlan, index);
            if (childFieldStart < 0) {
                return "";
            }
            if (!selectionPlanContainsEquivalentField(query, languagePlan, plan, childFieldStart)) {
                if (selectionPlanCount(plan) >= MAX_EXECUTION_SELECTION_FIELDS - 1) {
                    return "";
                }
                plan = plan + childFieldStart + ";";
            }
            index++;
        }
        return plan;
    }

    private static String appendDistinctSelectionPlanEntriesFromAst(
            String query,
            String ast,
            String plan,
            String childPlan
    ) {
        int childCount = selectionPlanCount(childPlan);
        if (childCount < 0 || selectionPlanCount(plan) < 0) {
            return "";
        }
        int index = 0;
        while (index < childCount) {
            int childFieldStart = selectionPlanFieldStart(childPlan, index);
            if (childFieldStart < 0) {
                return "";
            }
            if (!selectionPlanContainsEquivalentFieldFromAst(query, ast, plan, childFieldStart)) {
                if (selectionPlanCount(plan) >= MAX_EXECUTION_SELECTION_FIELDS - 1) {
                    return "";
                }
                plan = plan + childFieldStart + ";";
            }
            index++;
        }
        return plan;
    }

    private static boolean selectionPlanContainsEquivalentField(
            String query,
            String languagePlan,
            String plan,
            int fieldStart
    ) {
        int count = selectionPlanCount(plan);
        if (count < 0) {
            return false;
        }
        int index = 0;
        while (index < count) {
            int priorFieldStart = selectionPlanFieldStart(plan, index);
            if (fieldsHaveEquivalentMergeShape(query, languagePlan, priorFieldStart, fieldStart)) {
                return true;
            }
            index++;
        }
        return false;
    }

    private static boolean selectionPlanContainsEquivalentFieldFromAst(
            String query,
            String ast,
            String plan,
            int fieldStart
    ) {
        int count = selectionPlanCount(plan);
        if (count < 0) {
            return false;
        }
        int index = 0;
        while (index < count) {
            int priorFieldStart = selectionPlanFieldStart(plan, index);
            if (fieldsHaveEquivalentMergeShapeFromAst(query, ast, priorFieldStart, fieldStart)) {
                return true;
            }
            index++;
        }
        return false;
    }

    /**
     * A deliberately narrow, but valid, subset of GraphQL field merging: same response key and
     * identical field text after aliases and ignored tokens are removed.  Directives have already
     * been evaluated before a field reaches the plan, so their enabled forms remain part of the
     * identity.  Nested non-identical selections require a real merge tree and are left for the
     * later AST/plan milestone.
     */
    private static boolean fieldsHaveEquivalentMergeShape(
            String query,
            String languagePlan,
            int firstFieldStart,
            int secondFieldStart
    ) {
        String firstResponseKey = DatabaseGraphqlLanguage.fieldResponseKey(query, languagePlan, firstFieldStart);
        if (firstResponseKey.length() == 0 || !firstResponseKey.equals(
                DatabaseGraphqlLanguage.fieldResponseKey(query, languagePlan, secondFieldStart))) {
            return false;
        }
        int firstNameStart = fieldActualNameStart(query, languagePlan, firstFieldStart);
        int secondNameStart = fieldActualNameStart(query, languagePlan, secondFieldStart);
        if (firstNameStart < 0 || secondNameStart < 0) {
            return false;
        }
        int firstEnd = DatabaseGraphqlLanguage.fieldHeaderEnd(query, languagePlan, firstFieldStart);
        int secondEnd = DatabaseGraphqlLanguage.fieldHeaderEnd(query, languagePlan, secondFieldStart);
        return firstEnd > firstNameStart && secondEnd > secondNameStart
                && DatabaseGraphqlLanguage.tokenRangesEqual(query, languagePlan, firstNameStart, firstEnd,
                secondNameStart, secondEnd);
    }

    private static boolean fieldsHaveEquivalentMergeShapeFromAst(
            String query,
            String ast,
            int firstFieldStart,
            int secondFieldStart
    ) {
        String firstResponseKey = fieldResponseKey(query, ast, firstFieldStart);
        if (firstResponseKey.length() == 0 || !firstResponseKey.equals(
                fieldResponseKey(query, ast, secondFieldStart))) {
            return false;
        }
        String firstName = fieldName(query, ast, firstFieldStart);
        return firstName.length() != 0 && firstName.equals(fieldName(query, ast, secondFieldStart))
                && fieldArgumentsHaveEquivalentMergeShapeFromAst(
                query, ast, firstFieldStart, secondFieldStart);
    }

    /** Returns the actual field-name token, skipping an optional response-key alias. */
    private static int fieldActualNameStart(String query, String languagePlan, int fieldStart) {
        return DatabaseGraphqlLanguage.rangeStart(
                DatabaseGraphqlLanguage.fieldNameRange(query, languagePlan, fieldStart));
    }

    private static int fragmentAwareFieldCount(
            String query,
            int selectionStart,
            String variablesJson,
            String parentType
    ) {
        return fragmentAwareFieldCount(query, documentPlan(query), selectionStart, variablesJson, parentType);
    }

    private static int fragmentAwareFieldCount(
            String query,
            String languagePlan,
            int selectionStart,
            String variablesJson,
            String parentType
    ) {
        int count = 0;
        while (count < MAX_EXECUTION_SELECTION_FIELDS) {
            int fieldStart = fragmentAwareFieldStart(query, languagePlan, selectionStart, "", count, variablesJson,
                    parentType);
            if (fieldStart == -1) {
                return count;
            }
            if (fieldStart < 0) {
                return -1;
            }
            count++;
        }
        // Refuse excess rather than allowing a request to turn repeated source-range walks into
        // unbounded database routine work. A later execution-plan representation may raise this.
        return -1;
    }

    private static int fragmentAwareFieldCountFromAst(
            String query,
            String ast,
            int selectionStart,
            String variablesJson,
            String parentType
    ) {
        int count = 0;
        while (count < MAX_EXECUTION_SELECTION_FIELDS) {
            int fieldStart = fragmentAwareFieldStartFromAst(query, ast, selectionStart, "", count,
                    variablesJson, parentType);
            if (fieldStart == -1) {
                return count;
            }
            if (fieldStart < 0) {
                return -1;
            }
            count++;
        }
        return -1;
    }

    /** Returns an expanded directive-free named fragment selection. */
    private static int fragmentDefinitionSelectionStartFromAst(
            String query,
            String ast,
            String expectedName,
            String parentType
    ) {
        String info = DatabaseGraphqlAst.fragmentDefinitionInfo(query, ast, expectedName);
        int typeStart = astInfoValue(info, 0);
        int typeEnd = astInfoValue(info, 1);
        int directivesStart = astInfoValue(info, 2);
        int selection = astInfoValue(info, 3);
        int selectionEnd = astInfoValue(info, 4);
        if (typeStart < 0 || typeEnd <= typeStart || directivesStart < typeEnd || selection != directivesStart
                || selectionEnd <= selection || selectionEnd > query.length()) {
            return INVALID_SELECTION;
        }
        if (parentType != null && parentType.length() != 0
                && !runtimeTypeConditionMatches(parentType, query.substring(typeStart, typeEnd))) {
            return INAPPLICABLE_FRAGMENT;
        }
        return selection;
    }

    /**
     * Matches one fragment condition against an exact type, a generated {@code |A|B|} runtime
     * set, or one comma-delimited entry in a typed ancestry path.
     */
    private static boolean runtimeTypeConditionMatches(String runtimeTypes, String condition) {
        if (runtimeTypes == null || condition == null || condition.length() == 0) {
            return false;
        }
        if (runtimeTypes.equals(condition)) {
            return true;
        }
        if (runtimeTypes.startsWith("|") && runtimeTypes.endsWith("|")) {
            return runtimeTypes.indexOf("|" + condition + "|") >= 0;
        }
        return commaSeparatedNameContains(runtimeTypes, condition);
    }

    private static boolean fieldMatches(String query, String languagePlan, int fieldStart, String expectedField) {
        return expectedField != null && expectedField.equals(
                DatabaseGraphqlLanguage.fieldName(query, languagePlan, fieldStart));
    }

    private static boolean astFieldMatches(String query, String ast, int fieldStart, String expectedField) {
        return expectedField != null && expectedField.equals(fieldName(query, ast, fieldStart));
    }

    private static boolean fragmentPathContains(String path, String name) {
        String wanted = "|" + name + "|";
        int limit = path.length() - wanted.length();
        int position = 0;
        while (position <= limit) {
            if (path.substring(position, position + wanted.length()).equals(wanted)) {
                return true;
            }
            position++;
        }
        return false;
    }

    private static String fragmentStackEntry(int start, int end, String path) {
        return "" + start + ":" + end + ":" + path + ";";
    }

    private static int fragmentStateDelimiter(String value, int start, char delimiter) {
        // Scan from a literal non-negative cursor and apply the requested lower bound in the
        // predicate. Titan intentionally rejects a traversal cursor initialized from a dynamic
        // argument, even when the argument is range-checked by the caller.
        int position = 0;
        while (position < value.length()) {
            if (position >= start && value.charAt(position) == delimiter) return position;
            position++;
        }
        return -1;
    }

    private static int fragmentStateInt(String value, int start, int end) {
        if (start >= end) return -1;
        int result = 0;
        while (start < end) {
            char current = value.charAt(start);
            if (current < '0' || current > '9' || result > MAX_QUERY_CHARACTERS) return -1;
            result = result * 10 + current - '0';
            start++;
        }
        return result;
    }

    private static int selectionPlanEntryEnd(String plan, int start) {
        int position = 0;
        while (position < plan.length()) {
            if (position >= start && plan.charAt(position) == ';') {
                return position;
            }
            position++;
        }
        return -1;
    }

    private static int selectionPlanEntryValue(String plan, int start, int end) {
        if (start >= end) {
            return -1;
        }
        int value = 0;
        while (start < end) {
            char current = plan.charAt(start);
            if (current < '0' || current > '9' || value > MAX_QUERY_CHARACTERS) {
                return -1;
            }
            value = value * 10 + current - '0';
            start++;
        }
        return value;
    }

    /** Returns a response key from the parsed field identity, with a schema-name fallback. */
    public static String responseKey(String query, String languagePlan, int fieldStart, String fieldName) {
        String responseKey = DatabaseGraphqlLanguage.fieldResponseKey(query, languagePlan, fieldStart);
        return responseKey.length() == 0 ? fieldName : responseKey;
    }

    /** Returns a response key from the retained typed field node, with the schema fallback. */
    public static String responseKeyFromAst(
            String query,
            String ast,
            int fieldStart,
            String fieldName
    ) {
        String responseKey = fieldResponseKey(query, ast, fieldStart);
        return responseKey.length() == 0 ? fieldName : responseKey;
    }

    /** Reads one fixed slot from a field node without returning to the legacy syntax index. */
    private static int fieldInfoValue(String ast, int fieldStart, int slot) {
        return DatabaseGraphqlAst.fieldInfoValue(ast, fieldStart, slot);
    }

    /** Returns the aliased-or-schema response key retained on a typed field node. */
    private static String fieldResponseKey(String query, String ast, int fieldStart) {
        int start = fieldInfoValue(ast, fieldStart, 2);
        int end = fieldInfoValue(ast, fieldStart, 3);
        return start < 0 || end <= start || end > query.length() ? "" : query.substring(start, end);
    }

    /** Returns the schema field-name token retained separately from the response-key alias. */
    private static String fieldName(String query, String ast, int fieldStart) {
        int start = fieldInfoValue(ast, fieldStart, 4);
        int end = fieldInfoValue(ast, fieldStart, 5);
        return start < 0 || end <= start || end > query.length() ? "" : query.substring(start, end);
    }

    public static String argumentValue(
            String query,
            int fieldStart,
            String argumentName,
            String variablesJson
    ) {
        return argumentValue(query, documentPlan(query), fieldStart, argumentName, variablesJson);
    }

    /**
     * Resolves an argument using the request's parsed language plan. Generated serving routines
     * pass the single plan created for the selected operation, so variable defaults and
     * declarations are not rediscovered by a source-header scanner at every field.
     */
    public static String argumentValue(
            String query,
            String languagePlan,
            int fieldStart,
            String argumentName,
            String variablesJson
    ) {
        String value = argumentLiteral(query, languagePlan, fieldStart, argumentName);
        if (value.length() == 0 || value.charAt(0) != '$') {
            return value;
        }
        String variableName = value.substring(1, value.length());
        int definition = DatabaseGraphqlLanguage.variableDefinitionStart(query, languagePlan, variableName);
        if (definition < 0) {
            return "";
        }
        String supplied = jsonObjectFieldValue(variablesJson, variableName);
        if (supplied.length() != 0) {
            return supplied;
        }
        int defaultStart = DatabaseGraphqlLanguage.variableDefaultValueStart(languagePlan, definition);
        if (defaultStart < 0) {
            return "";
        }
        int defaultEnd = DatabaseGraphqlLanguage.variableDefaultValueEnd(languagePlan, definition);
        return defaultEnd <= defaultStart ? "" : query.substring(defaultStart, defaultEnd);
    }

    /**
     * AST-backed counterpart used by generated serving routines.  Argument and variable ranges
     * were established once while materializing the selected-operation AST; this path never
     * re-scans the legacy syntax index.
     */
    public static String argumentValueFromAst(
            String query,
            String requestAst,
            int fieldStart,
            String argumentName,
            String variablesJson
    ) {
        String value = argumentLiteralFromAst(query, requestAst, fieldStart, argumentName);
        if (value.length() == 0 || value.charAt(0) != '$') {
            return value;
        }
        String variableInfo = DatabaseGraphqlAst.variableDefinitionInfo(query, requestAst, value.substring(1));
        if (variableInfo.length() == 0) {
            return "";
        }
        String supplied = jsonObjectFieldValue(variablesJson, value.substring(1));
        if (supplied.length() != 0) {
            return supplied;
        }
        int defaultStart = astInfoValue(variableInfo, 3);
        int defaultEnd = astInfoValue(variableInfo, 4);
        return defaultStart < 0 || defaultEnd <= defaultStart ? "" : query.substring(defaultStart, defaultEnd);
    }

    /**
     * Materializes every selected-operation variable once after typed preflight. Values retain
     * their original GraphQL/JSON value spelling for the existing scalar and input-object
     * coercers, but supplied values and operation defaults now share one bounded, invocation-local
     * carrier. This removes repeated envelope scans from generated root/field bindings and is the
     * first layer beneath a later fully canonical typed input-value tree.
     *
     * <p>Carrier entries are length-delimited as
     * {@code mv1;nameLength:namevalueLength:value;}. A zero value length represents an omitted
     * nullable variable; it remains observably distinct from the literal {@code null}.</p>
     */
    public static String materializedVariableValuesFromAst(
            String query,
            String requestAst,
            String variablesJson
    ) {
        if (query == null || requestAst == null || requestAst.length() == 0) {
            return "";
        }
        // This uses the stable scalar Nv record emitted by DatabaseGraphqlAst directly instead
        // of the general node-accessor API.  Keeping this small carrier parser local avoids
        // pulling four generic AST traversal helpers into every MySQL request routine; those
        // helpers can push the reachable stored-function graph beyond MySQL's 256-entry
        // per-connection cache. Typed preflight has already authenticated this AST before this
        // routine is reached, but retain range checks so a malformed carrier fails closed.
        if (!requestAst.startsWith("ast1;")) {
            return "";
        }
        String values = MATERIALIZED_VARIABLES_PREFIX;
        String declared = ",";
        int recordStart = 5;
        while (recordStart < requestAst.length()) {
            int recordEnd = requestAst.indexOf(';', recordStart);
            if (recordEnd <= recordStart || !requestAst.substring(recordStart, recordStart + 1).equals("N")) {
                return "";
            }
            if (recordEnd > recordStart + 2 && requestAst.substring(recordStart, recordStart + 2).equals("Nv")) {
                int first = requestAst.indexOf(':', recordStart + 2);
                int second = first < 0 ? -1 : requestAst.indexOf(':', first + 1);
                int third = second < 0 ? -1 : requestAst.indexOf(':', second + 1);
                int fourth = third < 0 ? -1 : requestAst.indexOf(':', third + 1);
                int fifth = fourth < 0 ? -1 : requestAst.indexOf(':', fourth + 1);
                int sixth = fifth < 0 ? -1 : requestAst.indexOf(':', fifth + 1);
                int seventh = sixth < 0 ? -1 : requestAst.indexOf(':', sixth + 1);
                if (first != recordStart + 2 || second < 0 || third < 0 || fourth < 0 || fifth < 0
                        || sixth < 0 || seventh < 0 || seventh >= recordEnd
                        || !requestAst.substring(second + 1, third).equals("n")) {
                    return "";
                }
                int definitionStart = inputDescriptorDecimal(requestAst, first + 1, second);
                int nameEnd = inputDescriptorDecimal(requestAst, third + 1, fourth);
                int typeStart = inputDescriptorDecimal(requestAst, fourth + 1, fifth);
                int typeEnd = inputDescriptorDecimal(requestAst, fifth + 1, sixth);
                String defaultStartText = requestAst.substring(sixth + 1, seventh);
                String defaultEndText = requestAst.substring(seventh + 1, recordEnd);
                int defaultStart = defaultStartText.equals("n") ? -1
                        : inputDescriptorDecimal(defaultStartText, 0, defaultStartText.length());
                int defaultEnd = defaultEndText.equals("n") ? -1
                        : inputDescriptorDecimal(defaultEndText, 0, defaultEndText.length());
                if (definitionStart < 0 || nameEnd <= definitionStart + 1 || typeStart < nameEnd
                        || typeEnd <= typeStart || typeEnd > query.length()
                        || (defaultStart < 0 && !defaultStartText.equals("n"))
                        || (defaultEnd < 0 && !defaultEndText.equals("n"))
                        || (defaultStart < 0) != (defaultEnd < 0)
                        || (defaultStart >= 0 && (defaultEnd <= defaultStart || defaultEnd > query.length()))) {
                    return "";
                }
                String name = query.substring(definitionStart + 1, nameEnd);
                if (commaSeparatedNameContains(declared, name)) {
                    return "";
                }
                declared = declared + name + ",";
                String value = jsonObjectFieldValue(variablesJson, name);
                if (value.length() == 0 && defaultStart >= 0) {
                    value = query.substring(defaultStart, defaultEnd);
                }
                String entry = name.length() + ":" + name + value.length() + ":" + value + ";";
                if (values.length() + entry.length() > MAX_MATERIALIZED_VARIABLE_CHARACTERS) {
                    return "";
                }
                values = values + entry;
            }
            recordStart = recordEnd + 1;
        }
        return values;
    }

    /** Returns one resolved variable value from {@link #materializedVariableValuesFromAst}. */
    public static String materializedVariableValue(String materializedValues, String expectedName) {
        if (materializedValues == null || expectedName == null || expectedName.length() == 0
                || !materializedValues.startsWith(MATERIALIZED_VARIABLES_PREFIX)) {
            return "";
        }
        int position = MATERIALIZED_VARIABLES_PREFIX.length();
        while (position < materializedValues.length()) {
            int nameLengthEnd = materializedValues.indexOf(':', position);
            int nameLength = inputDescriptorDecimal(materializedValues, position, nameLengthEnd);
            int nameStart = nameLengthEnd < position ? -1 : nameLengthEnd + 1;
            int nameEnd = nameStart < 0 || nameLength < 0 ? -1 : nameStart + nameLength;
            int valueLengthEnd = nameEnd < 0 || nameEnd > materializedValues.length()
                    ? -1 : materializedValues.indexOf(':', nameEnd);
            int valueLength = inputDescriptorDecimal(materializedValues, nameEnd, valueLengthEnd);
            int valueStart = valueLengthEnd < nameEnd ? -1 : valueLengthEnd + 1;
            int valueEnd = valueStart < 0 || valueLength < 0 ? -1 : valueStart + valueLength;
            if (nameEnd < nameStart || valueEnd < valueStart || valueEnd >= materializedValues.length()
                    || materializedValues.charAt(valueEnd) != ';') {
                return "";
            }
            if (materializedValues.substring(nameStart, nameEnd).equals(expectedName)) {
                return materializedValues.substring(valueStart, valueEnd);
            }
            position = valueEnd + 1;
        }
        return "";
    }

    /**
     * AST-backed binding lookup using the request-local materialized variable carrier. Literal
     * arguments remain source slices; variable references never re-open the JSON envelope.
     */
    public static String argumentValueFromAstMaterialized(
            String query,
            String requestAst,
            int fieldStart,
            String argumentName,
            String materializedValues
    ) {
        String value = argumentLiteralFromAst(query, requestAst, fieldStart, argumentName);
        if (value.length() == 0 || value.charAt(0) != '$') {
            return value;
        }
        return materializedVariableValue(materializedValues, value.substring(1));
    }

    /**
     * Returns one prevalidated, fully materialized field argument from the invocation-local
     * {@code ma1} carrier. Generated schema bindings use this path after the shared input pass;
     * they no longer reopen the argument source or independently substitute variables.
     */
    public static String materializedArgumentValue(
            String materializedArguments,
            int fieldStart,
            String expectedName
    ) {
        if (materializedArguments == null
                || !materializedArguments.startsWith(MATERIALIZED_ARGUMENTS_PREFIX)
                || fieldStart < 0 || expectedName == null || expectedName.length() == 0) {
            return "";
        }
        int position = MATERIALIZED_ARGUMENTS_PREFIX.length();
        while (position < materializedArguments.length()) {
            int fieldEnd = materializedArguments.indexOf(':', position);
            int actualFieldStart = inputDescriptorDecimal(materializedArguments, position, fieldEnd);
            int nameLengthEnd = fieldEnd < 0 ? -1 : materializedArguments.indexOf(':', fieldEnd + 1);
            int nameLength = inputDescriptorDecimal(materializedArguments, fieldEnd + 1, nameLengthEnd);
            int nameStart = nameLengthEnd < 0 ? -1 : nameLengthEnd + 1;
            int nameEnd = nameStart < 0 || nameLength < 0 ? -1 : nameStart + nameLength;
            int valueLengthEnd = nameEnd < 0 || nameEnd > materializedArguments.length()
                    ? -1 : materializedArguments.indexOf(':', nameEnd);
            int valueLength = inputDescriptorDecimal(materializedArguments, nameEnd, valueLengthEnd);
            int valueStart = valueLengthEnd < 0 ? -1 : valueLengthEnd + 1;
            int valueEnd = valueStart < 0 || valueLength < 0 ? -1 : valueStart + valueLength;
            if (actualFieldStart < 0 || nameEnd < nameStart || valueEnd < valueStart
                    || valueEnd >= materializedArguments.length()
                    || materializedArguments.charAt(valueEnd) != ';') {
                return "";
            }
            if (actualFieldStart == fieldStart
                    && materializedArguments.substring(nameStart, nameEnd).equals(expectedName)) {
                return materializedArguments.substring(valueStart, valueEnd);
            }
            position = valueEnd + 1;
        }
        return "";
    }

    /**
     * Validates a GraphQL input-object literal or a JSON object obtained from a variable against
     * a generated, comma-separated field descriptor.  The database engine cannot rely on the
     * HTTP process to parse input objects: both literal and variable forms arrive here as the
     * value of a normal GraphQL argument.  This bounded scalar walker deliberately preserves the
     * nested value text for the schema binding, which performs the type-specific coercion.
     *
     * <p>Duplicate fields are rejected for both syntaxes.  JSON permits duplicate member names
     * grammatically, but accepting a different duplicate policy from GraphQL would make literal
     * and variable requests observably different.</p>
     */
    public static boolean inputObjectHasOnlyFields(String value, String allowedNames) {
        if (value == null || value.length() == 0) {
            return false;
        }
        if (value.startsWith(CANONICAL_INPUT_PREFIX)) {
            return canonicalInputObjectHasOnlyFields(value, allowedNames);
        }
        return DatabaseGraphqlLanguage.graphqlInputObjectHasOnlyFields(
                value, DatabaseGraphqlLanguage.lexicalTokenStream(value), allowedNames)
                || jsonInputObjectHasOnlyFields(value, allowedNames);
    }

    /**
     * Returns the raw value for one validated input-object field, or an empty string when the
     * field is absent or the supplied value is not an input object.  Callers first use
     * {@link #inputObjectHasOnlyFields(String, String)} so this helper never turns malformed or
     * duplicate input into a usable value.
     */
    public static String inputObjectFieldValue(String value, String expectedName) {
        if (value == null || value.length() == 0 || expectedName == null || expectedName.length() == 0) {
            return "";
        }
        if (value.startsWith(CANONICAL_INPUT_PREFIX)) {
            return canonicalInputValueAtPath(value, canonicalObjectPath("", expectedName));
        }
        String graphqlValue = DatabaseGraphqlLanguage.graphqlInputObjectFieldValue(
                value, DatabaseGraphqlLanguage.lexicalTokenStream(value), expectedName);
        return graphqlValue.length() != 0 ? graphqlValue : jsonObjectFieldValue(value, expectedName);
    }

    /** Resolves a reviewed dotted field path from a validated, materialized input object. */
    public static String inputObjectPathValue(String value, String path) {
        if (value == null || value.length() == 0 || path == null || path.length() == 0) {
            return "";
        }
        String current = value;
        int position = 0;
        int depth = 0;
        while (position < path.length()) {
            if (depth >= MAX_JSON_NESTING) {
                return "";
            }
            int end = path.indexOf('.', position);
            if (end < 0) end = path.length();
            if (end <= position) {
                return "";
            }
            String field = path.substring(position, end);
            current = inputObjectFieldValue(current, field);
            if (current.length() == 0) {
                return "";
            }
            position = end + 1;
            depth++;
        }
        return current;
    }

    /**
     * Counts values in a GraphQL input-list literal or a JSON array supplied through a variable.
     * A complete non-list input value counts as one item, implementing GraphQL's standard
     * singleton-to-list coercion without materializing a collection. The generated binding still
     * validates the resulting item against its concrete scalar or input-object descriptor.
     * Returns {@code -1} only for an absent value.
     */
    public static int inputListValueCount(String value) {
        if (value == null || value.length() == 0 || argumentValueIsNull(value)) {
            return -1;
        }
        if (value.startsWith(CANONICAL_INPUT_PREFIX)) {
            return canonicalInputListValueCount(value);
        }
        int count = DatabaseGraphqlLanguage.graphqlInputListValueCount(
                value, DatabaseGraphqlLanguage.lexicalTokenStream(value));
        return count < 0 ? 1 : count;
    }

    /**
     * Returns one raw item from an input list or its singleton-coerced non-list value, or the
     * empty string when {@code ordinal} is outside the input. This is intentionally
     * scalar-encoded: a generated static carrier can request at most its reviewed arity without
     * materializing a runtime list in the HTTP frontend or in the database routine.
     */
    public static String inputListValue(String value, int ordinal) {
        if (ordinal < 0 || value == null || value.length() == 0 || argumentValueIsNull(value)) {
            return "";
        }
        if (value.startsWith(CANONICAL_INPUT_PREFIX)) {
            return canonicalInputValueAtPath(value, canonicalListPath("", ordinal));
        }
        String item = DatabaseGraphqlLanguage.graphqlInputListValue(
                value, DatabaseGraphqlLanguage.lexicalTokenStream(value), ordinal);
        if (item.length() != 0) {
            return item;
        }
        return ordinal == 0 && DatabaseGraphqlLanguage.graphqlInputListValueCount(
                value, DatabaseGraphqlLanguage.lexicalTokenStream(value)) < 0 ? value : "";
    }

    /**
     * Compiles one generated filter input to the fixed 3x3 disjunctive-normal-form carrier.
     *
     * <p>The generated descriptor is a comma-separated list of {@code field.operator=selector}
     * bindings. GraphQL names cannot contain the descriptor punctuation, and selectors are
     * generator-owned positive integers. Input values have already passed the shared recursive
     * coercion/materialization pass, so this routine only performs Boolean lowering and retains
     * each scalar as a length-coded value for the generated typed binding.</p>
     *
     * <p>The task and plan stacks are scalar strings because Titan intentionally rejects a
     * recursive routine graph. Negation is pushed to scalar terms with De Morgan's law; an
     * {@code in} predicate becomes an OR of equality terms (or an AND of negated equality terms).
     * Empty objects are true and empty {@code or}/non-negated {@code in} lists are false.</p>
     */
    public static String generatedFilterPlan(String value, String descriptor) {
        if (value == null || value.length() == 0 || argumentValueIsNull(value)) {
            return filterTruePlan();
        }
        if (descriptor == null || descriptor.length() == 0) {
            return FILTER_PLAN_ERROR_PREFIX + "generated filter descriptor is missing";
        }
        String work = filterRecord(filterExpressionTask(value, false));
        String plans = "";
        int workItems = 0;
        while (work.length() != 0) {
            if (workItems >= MAX_FILTER_PLAN_WORK_ITEMS) {
                return filterPlanBudgetError("too many Boolean filter nodes");
            }
            workItems++;
            String task = filterFirstRecord(work);
            work = filterRemainingRecords(work);
            if (task.length() == 0) {
                return FILTER_PLAN_ERROR_PREFIX + "generated filter task carrier is malformed";
            }
            char kind = task.charAt(0);
            if (kind == 'T') {
                plans = filterRecord(filterTruePlan()) + plans;
                continue;
            }
            if (kind == 'F') {
                plans = filterRecord(filterFalsePlan()) + plans;
                continue;
            }
            if (kind == 'A' || kind == 'O') {
                String right = filterFirstRecord(plans);
                plans = filterRemainingRecords(plans);
                String left = filterFirstRecord(plans);
                plans = filterRemainingRecords(plans);
                if (left.length() == 0 || right.length() == 0) {
                    return FILTER_PLAN_ERROR_PREFIX + "generated filter plan stack is malformed";
                }
                String combined = kind == 'A' ? filterAndPlans(left, right) : filterOrPlans(left, right);
                if (filterPlanIsFailure(combined)) {
                    return combined;
                }
                plans = filterRecord(combined) + plans;
                continue;
            }
            if (kind == 'S') {
                String term = filterScalarTaskTerm(task);
                if (term.length() == 0) {
                    return FILTER_PLAN_ERROR_PREFIX + "generated filter scalar task is malformed";
                }
                plans = filterRecord(FILTER_PLAN_PREFIX + filterRecord("g;" + filterRecord(term))) + plans;
                continue;
            }
            if (kind != 'E') {
                return FILTER_PLAN_ERROR_PREFIX + "generated filter task kind is unknown";
            }
            int first = task.indexOf(':', 1);
            int valueLengthEnd = first < 0 ? -1 : task.indexOf(':', first + 1);
            int valueLength = inputDescriptorDecimal(task, first + 1, valueLengthEnd);
            int valueStart = valueLengthEnd < 0 ? -1 : valueLengthEnd + 1;
            int valueEnd = valueStart < 0 || valueLength < 0 ? -1 : valueStart + valueLength;
            if (first != 2 || valueEnd != task.length()) {
                return FILTER_PLAN_ERROR_PREFIX + "generated filter expression task is malformed";
            }
            boolean negated = task.charAt(1) == '1';
            String expression = task.substring(valueStart, valueEnd);
            String expanded = filterExpressionTasks(expression, descriptor, negated);
            if (expanded.startsWith(FILTER_PLAN_ERROR_PREFIX)) {
                return expanded;
            }
            work = expanded + work;
        }
        String result = filterFirstRecord(plans);
        if (result.length() == 0 || filterRemainingRecords(plans).length() != 0) {
            return FILTER_PLAN_ERROR_PREFIX + "generated filter did not produce one plan";
        }
        return result;
    }

    /** Whether the generated filter compiler returned a stable client-facing diagnostic. */
    public static boolean filterPlanIsFailure(String plan) {
        return plan != null && plan.startsWith(FILTER_PLAN_ERROR_PREFIX);
    }

    /** Returns the diagnostic portion of a failed generated filter plan. */
    public static String filterPlanFailureMessage(String plan) {
        return filterPlanIsFailure(plan) ? plan.substring(FILTER_PLAN_ERROR_PREFIX.length()) : "";
    }

    /** True when one of the three fixed SQL OR groups is active. */
    public static boolean filterPlanGroupActive(String plan, int groupIndex) {
        return filterPlanGroup(plan, groupIndex).length() != 0;
    }

    /** Returns a fixed-slot selector, or zero for an inactive term. */
    public static int filterPlanTermSelector(String plan, int groupIndex, int termIndex) {
        String term = filterPlanTerm(plan, groupIndex, termIndex);
        int end = term.indexOf(':');
        return term.length() == 0 ? 0 : inputDescriptorDecimal(term, 0, end);
    }

    /** Returns the fixed-slot De Morgan marker. */
    public static boolean filterPlanTermNegated(String plan, int groupIndex, int termIndex) {
        String term = filterPlanTerm(plan, groupIndex, termIndex);
        int first = term.indexOf(':');
        return first >= 0 && first + 1 < term.length() && term.charAt(first + 1) == '1';
    }

    /** Returns whether the term carries an explicit null scalar. */
    public static boolean filterPlanTermNull(String plan, int groupIndex, int termIndex) {
        String term = filterPlanTerm(plan, groupIndex, termIndex);
        int first = term.indexOf(':');
        int second = first < 0 ? -1 : term.indexOf(':', first + 1);
        return second >= 0 && second + 1 < term.length() && term.charAt(second + 1) == '1';
    }

    /** Returns the raw materialized scalar retained in one fixed filter-plan slot. */
    public static String filterPlanTermValue(String plan, int groupIndex, int termIndex) {
        String term = filterPlanTerm(plan, groupIndex, termIndex);
        int first = term.indexOf(':');
        int second = first < 0 ? -1 : term.indexOf(':', first + 1);
        int third = second < 0 ? -1 : term.indexOf(':', second + 1);
        int fourth = third < 0 ? -1 : term.indexOf(':', third + 1);
        int length = inputDescriptorDecimal(term, third + 1, fourth);
        int start = fourth < 0 ? -1 : fourth + 1;
        int end = start < 0 || length < 0 ? -1 : start + length;
        return end == term.length() ? term.substring(start, end) : "";
    }

    private static String filterExpressionTasks(String value, String descriptor, boolean negated) {
        if (!inputObjectHasOnlyFields(value, filterDescriptorFieldNames(descriptor) + ",and,or,not")) {
            return FILTER_PLAN_ERROR_PREFIX
                    + "connection argument 'filter' has an unknown, duplicate, or malformed field";
        }
        boolean conjunction = !negated;
        String tasks = filterRecord(conjunction ? "T" : "F");
        int position = 0;
        while (position < descriptor.length()) {
            int end = descriptor.indexOf(',', position);
            if (end < 0) end = descriptor.length();
            int dot = descriptor.indexOf('.', position);
            int equals = descriptor.indexOf('=', position);
            if (dot <= position || equals <= dot + 1 || equals >= end) {
                return FILTER_PLAN_ERROR_PREFIX + "generated filter descriptor is malformed";
            }
            String field = descriptor.substring(position, dot);
            String operator = descriptor.substring(dot + 1, equals);
            int selector = inputDescriptorDecimal(descriptor, equals + 1, end);
            if (selector <= 0) {
                return FILTER_PLAN_ERROR_PREFIX + "generated filter selector is malformed";
            }
            String fieldValue = inputObjectFieldValue(value, field);
            String scalarValue = inputObjectFieldValue(fieldValue, operator);
            if (scalarValue.length() != 0) {
                if (argumentValueIsNull(scalarValue) && !operator.equals("eq") && !operator.equals("neq")) {
                    return FILTER_PLAN_ERROR_PREFIX + "connection filter operator '" + operator
                            + "' does not accept null";
                }
                if (operator.equals("in")) {
                    boolean itemConjunction = negated;
                    tasks = tasks + filterRecord(itemConjunction ? "T" : "F");
                    int count = inputListValueCount(scalarValue);
                    if (count < 0) count = 0;
                    int item = 0;
                    while (item < count) {
                        String itemValue = inputListValue(scalarValue, item);
                        tasks = tasks + filterRecord(filterScalarTask(selector, negated, itemValue))
                                + filterRecord(itemConjunction ? "A" : "O");
                        item++;
                    }
                    tasks = tasks + filterRecord(conjunction ? "A" : "O");
                } else {
                    tasks = tasks + filterRecord(filterScalarTask(selector, negated, scalarValue))
                            + filterRecord(conjunction ? "A" : "O");
                }
            }
            position = end + 1;
        }

        String andValue = inputObjectFieldValue(value, "and");
        if (andValue.length() != 0 && !argumentValueIsNull(andValue)) {
            int count = inputListValueCount(andValue);
            int item = 0;
            while (item < count) {
                tasks = tasks + filterRecord(filterExpressionTask(inputListValue(andValue, item), negated))
                        + filterRecord(conjunction ? "A" : "O");
                item++;
            }
        }
        String orValue = inputObjectFieldValue(value, "or");
        if (orValue.length() != 0 && !argumentValueIsNull(orValue)) {
            boolean itemConjunction = negated;
            tasks = tasks + filterRecord(itemConjunction ? "T" : "F");
            int count = inputListValueCount(orValue);
            int item = 0;
            while (item < count) {
                tasks = tasks + filterRecord(filterExpressionTask(inputListValue(orValue, item), negated))
                        + filterRecord(itemConjunction ? "A" : "O");
                item++;
            }
            tasks = tasks + filterRecord(conjunction ? "A" : "O");
        }
        String notValue = inputObjectFieldValue(value, "not");
        if (notValue.length() != 0 && !argumentValueIsNull(notValue)) {
            tasks = tasks + filterRecord(filterExpressionTask(notValue, !negated))
                    + filterRecord(conjunction ? "A" : "O");
        }
        return tasks;
    }

    private static String filterDescriptorFieldNames(String descriptor) {
        String names = "";
        int position = 0;
        while (position < descriptor.length()) {
            int end = descriptor.indexOf(',', position);
            if (end < 0) end = descriptor.length();
            int dot = descriptor.indexOf('.', position);
            if (dot <= position || dot >= end) return "";
            String name = descriptor.substring(position, dot);
            if (!commaSeparatedNameContains("," + names + ",", name)) {
                names = names.length() == 0 ? name : names + "," + name;
            }
            position = end + 1;
        }
        return names;
    }

    private static String filterExpressionTask(String value, boolean negated) {
        return "E" + (negated ? "1" : "0") + ":" + value.length() + ":" + value;
    }

    private static String filterScalarTask(int selector, boolean negated, String value) {
        return "S" + selector + ":" + (negated ? "1" : "0") + ":" + value.length() + ":" + value;
    }

    private static String filterScalarTaskTerm(String task) {
        int first = task.indexOf(':', 1);
        int second = first < 0 ? -1 : task.indexOf(':', first + 1);
        int third = second < 0 ? -1 : task.indexOf(':', second + 1);
        int selector = inputDescriptorDecimal(task, 1, first);
        int valueLength = inputDescriptorDecimal(task, second + 1, third);
        int valueStart = third < 0 ? -1 : third + 1;
        int valueEnd = valueStart < 0 || valueLength < 0 ? -1 : valueStart + valueLength;
        if (selector <= 0 || second != first + 2 || valueEnd != task.length()) return "";
        String value = task.substring(valueStart, valueEnd);
        return selector + ":" + task.charAt(first + 1) + ":"
                + (argumentValueIsNull(value) ? "1" : "0") + ":" + value.length() + ":" + value;
    }

    private static String filterAndPlans(String left, String right) {
        if (filterPlanGroupCount(left) == 0 || filterPlanGroupCount(right) == 0) {
            return filterFalsePlan();
        }
        String result = FILTER_PLAN_PREFIX;
        int leftIndex = 0;
        while (leftIndex < filterPlanGroupCount(left)) {
            String leftGroup = filterPlanGroup(left, leftIndex);
            int rightIndex = 0;
            while (rightIndex < filterPlanGroupCount(right)) {
                String rightGroup = filterPlanGroup(right, rightIndex);
                String group = "g;" + leftGroup.substring(2) + rightGroup.substring(2);
                if (filterGroupTermCount(group) > MAX_FILTER_PLAN_TERMS) {
                    return filterPlanBudgetError("more than 3 AND predicates in an OR group");
                }
                result = result + filterRecord(group);
                if (filterPlanGroupCount(result) > MAX_FILTER_PLAN_GROUPS) {
                    return filterPlanBudgetError("more than 3 OR groups");
                }
                rightIndex++;
            }
            leftIndex++;
        }
        return result;
    }

    private static String filterOrPlans(String left, String right) {
        String result = FILTER_PLAN_PREFIX + left.substring(FILTER_PLAN_PREFIX.length())
                + right.substring(FILTER_PLAN_PREFIX.length());
        return filterPlanGroupCount(result) > MAX_FILTER_PLAN_GROUPS
                ? filterPlanBudgetError("more than 3 OR groups") : result;
    }

    private static String filterTruePlan() {
        return FILTER_PLAN_PREFIX + filterRecord("g;");
    }

    private static String filterFalsePlan() {
        return FILTER_PLAN_PREFIX;
    }

    private static String filterPlanBudgetError(String detail) {
        return FILTER_PLAN_ERROR_PREFIX + "generated root filter exceeds the static carrier budget: "
                + detail + " (maximum DNF is 3x3)";
    }

    private static int filterPlanGroupCount(String plan) {
        if (plan == null || !plan.startsWith(FILTER_PLAN_PREFIX)) return -1;
        int count = 0;
        int position = FILTER_PLAN_PREFIX.length();
        while (position < plan.length()) {
            int colon = plan.indexOf(':', position);
            int length = inputDescriptorDecimal(plan, position, colon);
            int end = colon < 0 || length < 0 ? -1 : colon + 1 + length;
            if (end < 0 || end > plan.length()) return -1;
            count++;
            position = end;
        }
        return count;
    }

    private static String filterPlanGroup(String plan, int groupIndex) {
        if (groupIndex < 0 || plan == null || !plan.startsWith(FILTER_PLAN_PREFIX)) return "";
        int position = FILTER_PLAN_PREFIX.length();
        int current = 0;
        while (position < plan.length()) {
            int colon = plan.indexOf(':', position);
            int length = inputDescriptorDecimal(plan, position, colon);
            int start = colon < 0 ? -1 : colon + 1;
            int end = start < 0 || length < 0 ? -1 : start + length;
            if (end < 0 || end > plan.length()) return "";
            if (current == groupIndex) return plan.substring(start, end);
            current++;
            position = end;
        }
        return "";
    }

    private static int filterGroupTermCount(String group) {
        if (group == null || !group.startsWith("g;")) return -1;
        int count = 0;
        int position = 2;
        while (position < group.length()) {
            int colon = group.indexOf(':', position);
            int length = inputDescriptorDecimal(group, position, colon);
            int end = colon < 0 || length < 0 ? -1 : colon + 1 + length;
            if (end < 0 || end > group.length()) return -1;
            count++;
            position = end;
        }
        return count;
    }

    private static String filterPlanTerm(String plan, int groupIndex, int termIndex) {
        if (termIndex < 0) return "";
        String group = filterPlanGroup(plan, groupIndex);
        if (!group.startsWith("g;")) return "";
        int position = 2;
        int current = 0;
        while (position < group.length()) {
            int colon = group.indexOf(':', position);
            int length = inputDescriptorDecimal(group, position, colon);
            int start = colon < 0 ? -1 : colon + 1;
            int end = start < 0 || length < 0 ? -1 : start + length;
            if (end < 0 || end > group.length()) return "";
            if (current == termIndex) return group.substring(start, end);
            current++;
            position = end;
        }
        return "";
    }

    private static String filterRecord(String value) {
        return value.length() + ":" + value;
    }

    private static String filterFirstRecord(String carrier) {
        if (carrier == null || carrier.length() == 0) return "";
        int colon = carrier.indexOf(':');
        int length = inputDescriptorDecimal(carrier, 0, colon);
        int start = colon < 0 ? -1 : colon + 1;
        int end = start < 0 || length < 0 ? -1 : start + length;
        return end < 0 || end > carrier.length() ? "" : carrier.substring(start, end);
    }

    private static String filterRemainingRecords(String carrier) {
        if (carrier == null || carrier.length() == 0) return "";
        int colon = carrier.indexOf(':');
        int length = inputDescriptorDecimal(carrier, 0, colon);
        int start = colon < 0 ? -1 : colon + 1;
        int end = start < 0 || length < 0 ? -1 : start + length;
        return end < 0 || end > carrier.length() ? "" : carrier.substring(end);
    }

    private static boolean jsonInputObjectHasOnlyFields(String value, String allowedNames) {
        int start = skipJsonWhitespace(value, 0, value.length());
        int end = strictJsonValueEnd(value, start, value.length());
        if (start >= value.length() || value.charAt(start) != '{' || end < 0
                || skipJsonWhitespace(value, end, value.length()) != value.length()) {
            return false;
        }
        String seen = ",";
        int position = start + 1;
        while (position < end - 1) {
            position = skipJsonWhitespace(value, position, end);
            if (position >= end - 1 || value.charAt(position) != '"') {
                return false;
            }
            int nameEnd = strictJsonStringEnd(value, position, end);
            if (nameEnd < 0) {
                return false;
            }
            String name = jsonStringValue(value.substring(position, nameEnd));
            if (!commaSeparatedNameContains(allowedNames, name) || commaSeparatedNameContains(seen, name)) {
                return false;
            }
            seen = seen + name + ",";
            int colon = skipJsonWhitespace(value, nameEnd, end);
            if (colon >= end || value.charAt(colon) != ':') {
                return false;
            }
            int valueStart = skipJsonWhitespace(value, colon + 1, end);
            int valueEnd = strictJsonValueEnd(value, valueStart, end);
            if (valueEnd < 0) {
                return false;
            }
            position = skipJsonWhitespace(value, valueEnd, end);
            if (position < end - 1 && value.charAt(position) == ',') {
                position++;
            } else if (position != end - 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * Ensures a variable reference is declared with the type expected by a generated schema
     * binding. Literal arguments are checked by their scalar coercer instead. For a required
     * scalar argument, a nullable declaration with its own non-null default is also a valid
     * GraphQL variable use; full input-object/list value coercion remains behind the bounded
     * language-engine milestone.
     */
    public static boolean argumentVariableTypeIsCompatible(
            String query,
            int fieldStart,
            String argumentName,
            String expectedScalar
    ) {
        return argumentVariableTypeIsCompatible(query, documentPlan(query), fieldStart, argumentName, expectedScalar);
    }

    /** Validates a generated required argument against a typed declaration retained in the plan. */
    public static boolean argumentVariableTypeIsCompatible(
            String query,
            String languagePlan,
            int fieldStart,
            String argumentName,
            String expectedScalar
    ) {
        String value = argumentLiteral(query, languagePlan, fieldStart, argumentName);
        if (value.length() == 0 || value.charAt(0) != '$') {
            return true;
        }
        int definition = DatabaseGraphqlLanguage.variableDefinitionStart(
                query, languagePlan, value.substring(1, value.length()));
        if (definition < 0) {
            return false;
        }
        String declaredType = DatabaseGraphqlTypeReference.parse(
                variableDeclaredType(query, languagePlan, definition));
        String expectedType = DatabaseGraphqlTypeReference.parse(expectedScalar);
        String requiredType = DatabaseGraphqlTypeReference.withOuterNonNull(expectedType);
        if (declaredType.length() == 0 || expectedType.length() == 0 || requiredType.length() == 0) {
            return false;
        }
        int defaultStart = DatabaseGraphqlLanguage.variableDefaultValueStart(languagePlan, definition);
        int defaultEnd = DatabaseGraphqlLanguage.variableDefaultValueEnd(languagePlan, definition);
        // GraphQL permits a nullable variable at a non-null argument location when the variable
        // itself has a non-null default. The generated binding still rejects an explicit supplied
        // JSON null before it reaches JDBC; this only recognizes the valid declaration shape.
        boolean variableHasNonNullDefault = defaultStart >= 0 && defaultEnd > defaultStart
                && !argumentValueIsNull(query.substring(defaultStart, defaultEnd));
        return DatabaseGraphqlTypeReference.isVariableUsageAllowed(
                declaredType, requiredType, variableHasNonNullDefault, false);
    }

    /** Validates a required generated argument against the typed request AST. */
    public static boolean argumentVariableTypeIsCompatibleFromAst(
            String query,
            String requestAst,
            int fieldStart,
            String argumentName,
            String expectedScalar
    ) {
        String value = argumentLiteralFromAst(query, requestAst, fieldStart, argumentName);
        if (value.length() == 0 || value.charAt(0) != '$') {
            return true;
        }
        String variableInfo = DatabaseGraphqlAst.variableDefinitionInfo(query, requestAst, value.substring(1));
        if (variableInfo.length() == 0) {
            return false;
        }
        String declaredType = DatabaseGraphqlTypeReference.parse(variableDeclaredTypeFromAst(query, variableInfo));
        String expectedType = DatabaseGraphqlTypeReference.parse(expectedScalar);
        String requiredType = DatabaseGraphqlTypeReference.withOuterNonNull(expectedType);
        if (declaredType.length() == 0 || expectedType.length() == 0 || requiredType.length() == 0) {
            return false;
        }
        int defaultStart = astInfoValue(variableInfo, 3);
        int defaultEnd = astInfoValue(variableInfo, 4);
        boolean variableHasNonNullDefault = defaultStart >= 0 && defaultEnd > defaultStart
                && !argumentValueIsNull(query.substring(defaultStart, defaultEnd));
        return DatabaseGraphqlTypeReference.isVariableUsageAllowed(
                declaredType, requiredType, variableHasNonNullDefault, false);
    }

    /**
     * GraphQL permits either a nullable or non-null variable declaration at an optional scalar
     * argument position. Generated connection filters use this instead of treating every scalar
     * argument as a point-key requirement.
     */
    public static boolean argumentVariableTypeIsCompatibleWithNullableArgument(
            String query,
            int fieldStart,
            String argumentName,
            String expectedScalar
    ) {
        return argumentVariableTypeIsCompatibleWithNullableArgument(
                query, documentPlan(query), fieldStart, argumentName, expectedScalar);
    }

    /** Plan-aware counterpart for optional generated scalar or list arguments. */
    public static boolean argumentVariableTypeIsCompatibleWithNullableArgument(
            String query,
            String languagePlan,
            int fieldStart,
            String argumentName,
            String expectedScalar
    ) {
        String value = argumentLiteral(query, languagePlan, fieldStart, argumentName);
        if (value.length() == 0 || value.charAt(0) != '$') {
            return true;
        }
        int definition = DatabaseGraphqlLanguage.variableDefinitionStart(
                query, languagePlan, value.substring(1, value.length()));
        if (definition < 0) {
            return false;
        }
        String declaredType = DatabaseGraphqlTypeReference.parse(
                variableDeclaredType(query, languagePlan, definition));
        String expectedType = DatabaseGraphqlTypeReference.parse(expectedScalar);
        return declaredType.length() != 0 && expectedType.length() != 0
                && DatabaseGraphqlTypeReference.isVariableUsageAllowed(declaredType, expectedType, false, false);
    }

    /** AST-backed counterpart for optional generated scalar, list, and input-object arguments. */
    public static boolean argumentVariableTypeIsCompatibleWithNullableArgumentFromAst(
            String query,
            String requestAst,
            int fieldStart,
            String argumentName,
            String expectedScalar
    ) {
        String value = argumentLiteralFromAst(query, requestAst, fieldStart, argumentName);
        if (value.length() == 0 || value.charAt(0) != '$') {
            return true;
        }
        String variableInfo = DatabaseGraphqlAst.variableDefinitionInfo(query, requestAst, value.substring(1));
        if (variableInfo.length() == 0) {
            return false;
        }
        String declaredType = DatabaseGraphqlTypeReference.parse(variableDeclaredTypeFromAst(query, variableInfo));
        String expectedType = DatabaseGraphqlTypeReference.parse(expectedScalar);
        return declaredType.length() != 0 && expectedType.length() != 0
                && DatabaseGraphqlTypeReference.isVariableUsageAllowed(declaredType, expectedType, false, false);
    }

    /**
     * Checks the declaration shape of every variable used by one generated optional-argument
     * contract before a resolver is allowed to depend on the presence of a parent row.
     *
     * <p>The generator supplies a static comma-separated {@code name=TypeReference} descriptor.
     * Literal values deliberately pass through here: their schema-specific coercion remains with
     * the generated binding. Returning the offending name lets that binding preserve a source
     * location at the owning field without encoding a model-specific schema graph in the common
     * runtime.</p>
     */
    public static String optionalArgumentVariableTypeErrorFromAst(
            String query,
            String requestAst,
            int fieldStart,
            String optionalArgumentTypes
    ) {
        if (optionalArgumentTypes == null) {
            return "";
        }
        int position = 0;
        while (position < optionalArgumentTypes.length()) {
            int end = optionalArgumentTypes.indexOf(',', position);
            if (end < 0) {
                end = optionalArgumentTypes.length();
            }
            int equals = optionalArgumentTypes.indexOf('=', position);
            if (equals <= position || equals >= end) {
                return "";
            }
            String name = optionalArgumentTypes.substring(position, equals);
            String type = optionalArgumentTypes.substring(equals + 1, end);
            if (!argumentVariableTypeIsCompatibleWithNullableArgumentFromAst(
                    query, requestAst, fieldStart, name, type)) {
                return name;
            }
            position = end + 1;
        }
        return "";
    }

    public static long longArgument(String value) {
        if (value == null || value.length() == 0) {
            return INVALID_LONG;
        }
        int start = 0;
        int end = value.length();
        boolean negative = false;
        if (start < end && value.charAt(start) == '-') {
            negative = true;
            start++;
        }
        if (start >= end) {
            return INVALID_LONG;
        }
        long result = 0L;
        while (start < end) {
            char current = value.charAt(start);
            if (current < '0' || current > '9') {
                return INVALID_LONG;
            }
            long digit = current - '0';
            // This parser feeds generated primary-key, pagination, and Int coercion code.  A
            // wrapping multiplication would let a hostile literal such as 2^64 + 7 become the
            // perfectly valid key 7 before the generated scalar-range check sees it.  The
            // scalar representation reserves Long.MIN_VALUE as the invalid sentinel, so reject
            // magnitudes outside Long.MAX_VALUE before performing the multiplication.
            if (result > (Long.MAX_VALUE - digit) / 10L) {
                return INVALID_LONG;
            }
            result = result * 10L + digit;
            start++;
        }
        return negative ? -result : result;
    }

    public static boolean invalidLong(long value) {
        return value == INVALID_LONG;
    }

    /**
     * Applies GraphQL's signed 32-bit {@code Int} bound after the shared decimal scanner has
     * accepted a Java {@code long}. Model {@code Long} and numeric {@code ID} bindings deliberately
     * retain their wider database range.
     */
    public static boolean invalidIntegerForScalar(long value, String graphqlScalar) {
        if (invalidLong(value)) {
            return true;
        }
        return "Int".equals(graphqlScalar) && (value < -2147483648L || value > 2147483647L);
    }

    /**
     * Validates GraphQL's ID input union: either a string token or an integer token. The caller
     * still selects the database storage representation from reviewed model metadata.
     */
    public static boolean idArgumentIsValid(String value) {
        return stringArgumentIsValid(value) || !invalidLong(longArgument(value));
    }

    /** Coerces an ID for an integral backing column, accepting both legal GraphQL ID forms. */
    public static long integralIdArgument(String value) {
        if (stringArgumentIsValid(value)) {
            return longArgument(stringArgument(value));
        }
        return longArgument(value);
    }

    /** Coerces an ID for a string backing column, canonically rendering integer input. */
    public static String stringIdArgument(String value) {
        if (stringArgumentIsValid(value)) {
            return stringArgument(value);
        }
        long integer = longArgument(value);
        return invalidLong(integer) ? "" : "" + integer;
    }

    /**
     * Encodes an integer-cursor Relay token compatible with {@code GraphqlCursorCodec}'s public
     * {@code tgqlc1} format. The caller supplies reviewed model metadata, never client text.
     */
    public static String relayCursorForLong(
            String orderingName,
            String cursorPath,
            String direction,
            String tieBreakerColumn,
            long value
    ) {
        String encodedOrdering = base64UrlEncodeGraphqlName(orderingName);
        String encodedPath = Text.base64UrlEncodeUtf8(cursorPath);
        String encodedDirection = base64UrlEncodeGraphqlName(direction);
        String encodedTieBreaker = base64UrlEncodeGraphqlName(tieBreakerColumn);
        String decimalValue = "" + value;
        String encodedValue = base64UrlEncodeDecimal(decimalValue);
        if (encodedOrdering.length() == 0 || encodedPath.length() == 0 || encodedDirection.length() == 0
                || encodedTieBreaker.length() == 0 || encodedValue.length() == 0) {
            return "";
        }
        return "tgqlc1." + encodedOrdering + "." + encodedPath + "." + encodedDirection + "."
                + encodedValue + "." + encodedTieBreaker + "." + encodedValue;
    }

    /**
     * Validates a {@code tgqlc1} token against reviewed cursor metadata and returns its Long
     * cursor value. The current generic connection subset uses the cursor column as its tie
     * breaker, so both encoded numeric payload positions must match.
     */
    public static long relayCursorLongValue(
            String cursor,
            String orderingName,
            String cursorPath,
            String direction,
            String tieBreakerColumn
    ) {
        if (cursor == null || !cursorPartEquals(cursor, 0, "tgqlc1")
                || !cursorPartEquals(cursor, 1, base64UrlEncodeGraphqlName(orderingName))
                || !cursorPartEquals(cursor, 2, Text.base64UrlEncodeUtf8(cursorPath))
                || !cursorPartEquals(cursor, 3, base64UrlEncodeGraphqlName(direction))
                || !cursorPartEquals(cursor, 5, base64UrlEncodeGraphqlName(tieBreakerColumn))
                || !cursorHasExactlySevenParts(cursor)) {
            return INVALID_LONG;
        }
        String valuePart = cursorPart(cursor, 4);
        String tieBreakerPart = cursorPart(cursor, 6);
        if (valuePart.length() == 0 || !valuePart.equals(tieBreakerPart)) {
            return INVALID_LONG;
        }
        return base64UrlDecodeDecimal(valuePart);
    }

    /**
     * Encodes a numeric sort value plus a distinct numeric stable tie breaker in the public Relay
     * cursor format. The single-value variant above remains for the default cursor plan, where
     * the cursor column and tie breaker are intentionally the same field.
     */
    public static String relayCursorForLongLongTie(
            String orderingName,
            String cursorPath,
            String direction,
            String tieBreakerColumn,
            long value,
            long tieBreakerValue
    ) {
        String encodedOrdering = base64UrlEncodeGraphqlName(orderingName);
        String encodedPath = Text.base64UrlEncodeUtf8(cursorPath);
        String encodedDirection = base64UrlEncodeGraphqlName(direction);
        String encodedTieBreaker = base64UrlEncodeGraphqlName(tieBreakerColumn);
        String encodedValue = base64UrlEncodeDecimal("" + value);
        String encodedTieValue = base64UrlEncodeDecimal("" + tieBreakerValue);
        if (encodedOrdering.length() == 0 || encodedPath.length() == 0 || encodedDirection.length() == 0
                || encodedTieBreaker.length() == 0 || encodedValue.length() == 0 || encodedTieValue.length() == 0) {
            return "";
        }
        return "tgqlc1." + encodedOrdering + "." + encodedPath + "." + encodedDirection + "."
                + encodedValue + "." + encodedTieBreaker + "." + encodedTieValue;
    }

    /** Validates a numeric tuple cursor without assuming its sort value and tie breaker coincide. */
    public static boolean relayCursorLongLongTieIsValid(
            String cursor,
            String orderingName,
            String cursorPath,
            String direction,
            String tieBreakerColumn
    ) {
        if (cursor == null || !cursorPartEquals(cursor, 0, "tgqlc1")
                || !cursorPartEquals(cursor, 1, base64UrlEncodeGraphqlName(orderingName))
                || !cursorPartEquals(cursor, 2, Text.base64UrlEncodeUtf8(cursorPath))
                || !cursorPartEquals(cursor, 3, base64UrlEncodeGraphqlName(direction))
                || !cursorPartEquals(cursor, 5, base64UrlEncodeGraphqlName(tieBreakerColumn))
                || !cursorHasExactlySevenParts(cursor)) {
            return false;
        }
        return !invalidLong(base64UrlDecodeDecimal(cursorPart(cursor, 4)))
                && !invalidLong(base64UrlDecodeDecimal(cursorPart(cursor, 6)));
    }

    /** Returns the numeric sort value from a cursor accepted by {@link #relayCursorLongLongTieIsValid}. */
    public static long relayCursorLongLongTieValue(String cursor) {
        return base64UrlDecodeDecimal(cursorPart(cursor, 4));
    }

    /** Returns the numeric stable tie breaker from a cursor accepted by {@link #relayCursorLongLongTieIsValid}. */
    public static long relayCursorLongLongTieBreakerValue(String cursor) {
        return base64UrlDecodeDecimal(cursorPart(cursor, 6));
    }

    /**
     * Encodes a non-null String sort value and a numeric stable tie breaker in the public v1
     * cursor format.  The value segment is UTF-8 Base64url, exactly as {@code GraphqlCursorCodec}
     * defines it. Reviewed cursor paths use the UTF-8 codec because relation paths may contain dots;
     * local single-name paths retain byte-identical tokens.
     */
    public static String relayCursorForStringLongTie(
            String orderingName,
            String cursorPath,
            String direction,
            String tieBreakerColumn,
            String value,
            long tieBreakerValue
    ) {
        if (value == null) {
            return "";
        }
        String encodedOrdering = base64UrlEncodeGraphqlName(orderingName);
        String encodedPath = Text.base64UrlEncodeUtf8(cursorPath);
        String encodedDirection = base64UrlEncodeGraphqlName(direction);
        String encodedTieBreaker = base64UrlEncodeGraphqlName(tieBreakerColumn);
        String encodedValue = Text.base64UrlEncodeUtf8(value);
        String encodedTieValue = base64UrlEncodeDecimal("" + tieBreakerValue);
        if (encodedOrdering.length() == 0 || encodedPath.length() == 0 || encodedDirection.length() == 0
                || encodedTieBreaker.length() == 0 || encodedTieValue.length() == 0) {
            return "";
        }
        return "tgqlc1." + encodedOrdering + "." + encodedPath + "." + encodedDirection + "."
                + encodedValue + "." + encodedTieBreaker + "." + encodedTieValue;
    }

    /**
     * Validates a String-sort Relay cursor before the native UTF-8 Base64 decode is evaluated.
     * This protects the database conversion intrinsic from hostile malformed byte sequences and
     * makes invalid cursors ordinary GraphQL validation failures rather than dialect exceptions.
     */
    public static boolean relayCursorStringLongTieIsValid(
            String cursor,
            String orderingName,
            String cursorPath,
            String direction,
            String tieBreakerColumn
    ) {
        if (cursor == null || !cursorPartEquals(cursor, 0, "tgqlc1")
                || !cursorPartEquals(cursor, 1, base64UrlEncodeGraphqlName(orderingName))
                || !cursorPartEquals(cursor, 2, Text.base64UrlEncodeUtf8(cursorPath))
                || !cursorPartEquals(cursor, 3, base64UrlEncodeGraphqlName(direction))
                || !cursorPartEquals(cursor, 5, base64UrlEncodeGraphqlName(tieBreakerColumn))
                || !cursorHasExactlySevenParts(cursor)) {
            return false;
        }
        String valuePart = cursorPart(cursor, 4);
        String tieBreakerPart = cursorPart(cursor, 6);
        return base64UrlIsValidUtf8(valuePart) && !invalidLong(base64UrlDecodeDecimal(tieBreakerPart));
    }

    /**
     * Returns the String sort value from a cursor already accepted by
     * {@link #relayCursorStringLongTieIsValid(String, String, String, String, String)}.
     */
    public static String relayCursorStringLongTieValue(String cursor) {
        return Text.base64UrlDecodeUtf8(cursorPart(cursor, 4));
    }

    /** Returns the numeric stable tie breaker from a cursor already validated by the companion API. */
    public static long relayCursorStringLongTieBreakerValue(String cursor) {
        return base64UrlDecodeDecimal(cursorPart(cursor, 6));
    }

    private static boolean cursorPartEquals(String cursor, int ordinal, String expected) {
        return expected != null && expected.length() != 0 && expected.equals(cursorPart(cursor, ordinal));
    }

    /** Returns one dot-delimited cursor segment, or the empty string when it is absent/malformed. */
    private static String cursorPart(String cursor, int ordinal) {
        int start = cursorPartStart(cursor, ordinal);
        if (start < 0) {
            return "";
        }
        int end = start;
        while (end < cursor.length() && cursor.charAt(end) != '.') {
            end++;
        }
        return end <= start ? "" : cursor.substring(start, end);
    }

    /** Returns the start of a cursor segment; ordinal 0 is the entire-prefix segment. */
    private static int cursorPartStart(String cursor, int ordinal) {
        if (cursor == null || ordinal < 0) {
            return -1;
        }
        int currentOrdinal = 0;
        int position = 0;
        while (position <= cursor.length()) {
            if (currentOrdinal == ordinal) {
                return position < cursor.length() ? position : -1;
            }
            while (position < cursor.length() && cursor.charAt(position) != '.') {
                position++;
            }
            if (position >= cursor.length()) {
                return -1;
            }
            position++;
            currentOrdinal++;
        }
        return -1;
    }

    /** Matches Java's {@code split("\\.", -1)} cursor arity, including rejection of a trailing dot. */
    private static boolean cursorHasExactlySevenParts(String cursor) {
        if (cursor == null || cursor.length() == 0 || cursor.charAt(cursor.length() - 1) == '.') {
            return false;
        }
        int dots = 0;
        int position = 0;
        while (position < cursor.length()) {
            if (cursor.charAt(position) == '.') {
                dots++;
            }
            position++;
        }
        return dots == 6;
    }

    private static String base64UrlEncodeGraphqlName(String value) {
        if (value == null || value.length() == 0) {
            return "";
        }
        String encoded = "";
        int position = 0;
        int groupOffset = 0;
        while (position < value.length()) {
            if (groupOffset == 0) {
                int first = graphqlNameAscii(value.charAt(position));
                if (first < 0) return "";
                int second = position + 1 < value.length() ? graphqlNameAscii(value.charAt(position + 1)) : -1;
                int third = position + 2 < value.length() ? graphqlNameAscii(value.charAt(position + 2)) : -1;
                if (second < -1 || third < -1) return "";
                encoded = encoded + base64Character(nonNegativeQuotient(first, 4));
                if (second < 0) {
                    encoded = encoded + base64Character(nonNegativeRemainder(first, 4) * 16);
                } else {
                    encoded = encoded + base64Character(nonNegativeRemainder(first, 4) * 16
                            + nonNegativeQuotient(second, 16));
                    if (third < 0) {
                        encoded = encoded + base64Character(nonNegativeRemainder(second, 16) * 4);
                    } else {
                        encoded = encoded + base64Character(nonNegativeRemainder(second, 16) * 4
                                + nonNegativeQuotient(third, 64))
                                + base64Character(nonNegativeRemainder(third, 64));
                    }
                }
            }
            position++;
            groupOffset++;
            if (groupOffset == 3) groupOffset = 0;
        }
        return encoded;
    }

    private static String base64UrlEncodeDecimal(String value) {
        if (value == null || value.length() == 0) {
            return "";
        }
        String encoded = "";
        int position = 0;
        int groupOffset = 0;
        while (position < value.length()) {
            if (groupOffset == 0) {
                int first = decimalAscii(value.charAt(position));
                if (first < 0) return "";
                int second = position + 1 < value.length() ? decimalAscii(value.charAt(position + 1)) : -1;
                int third = position + 2 < value.length() ? decimalAscii(value.charAt(position + 2)) : -1;
                if (second < -1 || third < -1) return "";
                encoded = encoded + base64Character(nonNegativeQuotient(first, 4));
                if (second < 0) {
                    encoded = encoded + base64Character(nonNegativeRemainder(first, 4) * 16);
                } else {
                    encoded = encoded + base64Character(nonNegativeRemainder(first, 4) * 16
                            + nonNegativeQuotient(second, 16));
                    if (third < 0) {
                        encoded = encoded + base64Character(nonNegativeRemainder(second, 16) * 4);
                    } else {
                        encoded = encoded + base64Character(nonNegativeRemainder(second, 16) * 4
                                + nonNegativeQuotient(third, 64))
                                + base64Character(nonNegativeRemainder(third, 64));
                    }
                }
            }
            position++;
            groupOffset++;
            if (groupOffset == 3) groupOffset = 0;
        }
        return encoded;
    }

    private static String base64Character(int value) {
        return value < 0 || value >= BASE64_URL_ALPHABET.length() ? ""
                : BASE64_URL_ALPHABET.substring(value, value + 1);
    }

    private static int graphqlNameAscii(char value) {
        if (value >= 'A' && value <= 'Z') return value - 'A' + 65;
        if (value >= 'a' && value <= 'z') return value - 'a' + 97;
        if (value >= '0' && value <= '9') return value - '0' + 48;
        return value == '_' ? 95 : -1;
    }

    private static int decimalAscii(char value) {
        if (value >= '0' && value <= '9') return value - '0' + 48;
        return value == '-' ? 45 : -1;
    }

    /** Small bounded arithmetic helpers avoid Java `/` and `%` runtime shims on MySQL. */
    private static int nonNegativeQuotient(int value, int divisor) {
        if (value < 0 || divisor < 1) return -1;
        int quotient = 0;
        int remaining = value;
        while (remaining >= divisor) {
            remaining = remaining - divisor;
            quotient++;
        }
        return quotient;
    }

    private static int nonNegativeRemainder(int value, int divisor) {
        if (value < 0 || divisor < 1) return -1;
        int remaining = value;
        while (remaining >= divisor) {
            remaining = remaining - divisor;
        }
        return remaining;
    }

    private static long base64UrlDecodeDecimal(String value) {
        int length = value == null ? 0 : value.length();
        if (value == null || length == 0 || nonNegativeRemainder(length, 4) == 1) {
            return INVALID_LONG;
        }
        String decimal = "";
        int position = 0;
        int groupOffset = 0;
        while (position < value.length()) {
            if (groupOffset == 0) {
                int first = base64UrlValue(value.charAt(position));
                int second = position + 1 < value.length() ? base64UrlValue(value.charAt(position + 1)) : -1;
                int third = position + 2 < value.length() ? base64UrlValue(value.charAt(position + 2)) : -1;
                int fourth = position + 3 < value.length() ? base64UrlValue(value.charAt(position + 3)) : -1;
                if (first < 0 || second < 0 || third < -1 || fourth < -1) {
                    return INVALID_LONG;
                }
                decimal = decimal + decimalCharacter(first * 4 + nonNegativeQuotient(second, 16));
                if (third >= 0) {
                    decimal = decimal + decimalCharacter(nonNegativeRemainder(second, 16) * 16
                            + nonNegativeQuotient(third, 4));
                    if (fourth >= 0) {
                        decimal = decimal + decimalCharacter(nonNegativeRemainder(third, 4) * 64 + fourth);
                    }
                }
            }
            position++;
            groupOffset++;
            if (groupOffset == 4) groupOffset = 0;
        }
        return decimal.length() == 0 || decimal.charAt(decimal.length() - 1) == '-' ? INVALID_LONG : longArgument(decimal);
    }

    private static int base64UrlValue(char value) {
        // Do not classify the character with text range comparisons. Database collations can
        // make those ranges case-insensitive or reorder lowercase characters. This intrinsic
        // emits a case-exact ASCII lookup on each supported database.
        return Text.base64UrlAlphabetIndex(value);
    }

    /** Validates URL-safe Base64 input and its decoded bytes as strict well-formed UTF-8. */
    private static boolean base64UrlIsValidUtf8(String value) {
        if (value == null || nonNegativeRemainder(value.length(), 4) == 1) {
            return false;
        }
        int position = 0;
        int groupOffset = 0;
        int utf8State = 0;
        while (position < value.length()) {
            if (groupOffset == 0) {
                int first = base64UrlValue(value.charAt(position));
                int second = position + 1 < value.length() ? base64UrlValue(value.charAt(position + 1)) : -1;
                int third = position + 2 < value.length() ? base64UrlValue(value.charAt(position + 2)) : -1;
                int fourth = position + 3 < value.length() ? base64UrlValue(value.charAt(position + 3)) : -1;
                if (first < 0 || second < 0 || third < -1 || fourth < -1) {
                    return false;
                }
                utf8State = utf8StateAfterByte(utf8State, first * 4 + nonNegativeQuotient(second, 16));
                if (utf8State < 0) {
                    return false;
                }
                if (third >= 0) {
                    utf8State = utf8StateAfterByte(utf8State,
                            nonNegativeRemainder(second, 16) * 16 + nonNegativeQuotient(third, 4));
                    if (utf8State < 0) {
                        return false;
                    }
                    if (fourth >= 0) {
                        utf8State = utf8StateAfterByte(utf8State,
                                nonNegativeRemainder(third, 4) * 64 + fourth);
                        if (utf8State < 0) {
                            return false;
                        }
                    }
                }
            }
            position++;
            groupOffset++;
            if (groupOffset == 4) {
                groupOffset = 0;
            }
        }
        return utf8State == 0;
    }

    /**
     * Carries pending UTF-8 continuation count and the permitted next-byte range as one scalar
     * so the transpiled runtime does not need an object/array parser state.  Zero means a complete
     * code point; negative is invalid.
     */
    private static int utf8StateAfterByte(int state, int value) {
        if (value < 0 || value > 255) {
            return -1;
        }
        if (state == 0) {
            if (value <= 127) {
                return 0;
            }
            if (value >= 194 && value <= 223) {
                return utf8State(1, 128, 191);
            }
            if (value == 224) {
                return utf8State(2, 160, 191);
            }
            if ((value >= 225 && value <= 236) || (value >= 238 && value <= 239)) {
                return utf8State(2, 128, 191);
            }
            if (value == 237) {
                return utf8State(2, 128, 159);
            }
            if (value == 240) {
                return utf8State(3, 144, 191);
            }
            if (value >= 241 && value <= 243) {
                return utf8State(3, 128, 191);
            }
            return value == 244 ? utf8State(3, 128, 143) : -1;
        }
        int remaining = nonNegativeQuotient(state, 65536);
        int range = nonNegativeRemainder(state, 65536);
        int minimum = nonNegativeQuotient(range, 256);
        int maximum = nonNegativeRemainder(range, 256);
        if (remaining < 1 || value < minimum || value > maximum) {
            return -1;
        }
        return remaining == 1 ? 0 : utf8State(remaining - 1, 128, 191);
    }

    private static int utf8State(int remaining, int minimum, int maximum) {
        return remaining * 65536 + minimum * 256 + maximum;
    }

    private static String decimalCharacter(int value) {
        if (value >= 48 && value <= 57) return "0123456789".substring(value - 48, value - 47);
        return value == 45 ? "-" : "";
    }

    public static String stringArgument(String value) {
        if (value == null || value.length() == 0) {
            return "";
        }
        if (isBlockStringLiteral(value)) {
            return blockStringValue(value);
        }
        if (value.charAt(0) == '"') {
            return jsonStringValue(value);
        }
        return "";
    }

    /**
     * Distinguishes an invalid non-string token from the legal GraphQL string value {@code ""}.
     * The surrounding request lexer/envelope validator has already checked escaping and closure;
     * generated scalar bindings use this predicate instead of treating decoded length zero as an
     * invalid type.
     */
    public static boolean stringArgumentIsValid(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '"'
                || value.charAt(value.length() - 1) != '"') {
            return false;
        }
        return !isBlockStringLiteralStart(value)
                || value.length() >= 6 && value.charAt(value.length() - 2) == '"'
                && value.charAt(value.length() - 3) == '"';
    }

    /**
     * Decodes a GraphQL enum literal or its JSON-string variable representation. Callers still
     * compare the returned value with their generated enum descriptor; this helper only bridges
     * the two input syntaxes without giving an HTTP parser semantic authority.
     */
    public static String enumArgument(String value) {
        if (value == null || value.length() == 0) {
            return "";
        }
        if (stringArgumentIsValid(value)) {
            return stringArgument(value);
        }
        int start = skipIgnored(value, 0, value.length());
        int end = nameEnd(value, start, value.length());
        return end > start && skipIgnored(value, end, value.length()) == value.length()
                ? value.substring(start, end) : "";
    }

    /** Checks a database/output enum value against the generator-owned pipe-delimited schema values. */
    public static boolean enumValueIsAllowed(String value, String allowedValues) {
        return value != null && value.length() != 0
                && pipeSeparatedNameContains(allowedValues, value);
    }

    /**
     * Decodes GraphQL's triple-quoted block strings without treating them as JSON strings.
     * GraphQL normalizes line endings, removes common indentation after the first line, trims
     * leading/trailing blank lines, and permits an escaped triple quote. Keeping that behavior in
     * this scalar helper ensures defaults and inline arguments share the exact same
     * database-resident coercion path.
     */
    private static String blockStringValue(String literal) {
        if (!isBlockStringLiteral(literal)) {
            return "";
        }
        String unescaped = "";
        int position = 3;
        int limit = literal.length() - 3;
        while (position < limit) {
            if (literal.charAt(position) == '\\' && position + 3 < limit
                    && literal.charAt(position + 1) == '"' && literal.charAt(position + 2) == '"'
                    && literal.charAt(position + 3) == '"') {
                unescaped = unescaped + "\"\"\"";
                position += 4;
            } else {
                unescaped = unescaped + literal.charAt(position);
                position++;
            }
        }
        return normalizedBlockStringValue(normalizedBlockStringLineEndings(unescaped));
    }

    private static boolean isBlockStringLiteral(String value) {
        return isBlockStringLiteralStart(value) && value.length() >= 6
                && value.charAt(value.length() - 1) == '"' && value.charAt(value.length() - 2) == '"'
                && value.charAt(value.length() - 3) == '"';
    }

    private static boolean isBlockStringLiteralStart(String value) {
        return value != null && value.length() >= 3 && value.charAt(0) == '"'
                && value.charAt(1) == '"' && value.charAt(2) == '"';
    }

    /** Normalizes CR/LF combinations so indentation and blank-line rules have one representation. */
    private static String normalizedBlockStringLineEndings(String value) {
        String normalized = "";
        int position = 0;
        while (position < value.length()) {
            char current = value.charAt(position);
            if (current == '\r') {
                normalized = normalized + '\n';
            } else if (current == '\n' && position > 0 && value.charAt(position - 1) == '\r') {
                // The preceding CR already emitted this normalized line break. Keep the scanner
                // cursor monotonic by suppressing the LF output rather than skipping its index.
            } else {
                normalized = normalized + current;
            }
            position++;
        }
        return normalized;
    }

    /** Implements GraphQL BlockStringValue indentation and blank-line normalization. */
    private static String normalizedBlockStringValue(String value) {
        int commonIndent = -1;
        int position = 0;
        int line = 0;
        while (position <= value.length()) {
            int end = blockStringLineEnd(value, position);
            if (line > 0 && !blockStringLineIsBlank(value, position, end)) {
                int indent = blockStringLineIndent(value, position, end);
                if (commonIndent < 0 || indent < commonIndent) {
                    commonIndent = indent;
                }
            }
            if (end == value.length()) {
                break;
            }
            position = end + 1;
            line++;
        }

        String normalized = "";
        position = 0;
        line = 0;
        while (position <= value.length()) {
            int end = blockStringLineEnd(value, position);
            int contentStart = position;
            if (line > 0 && commonIndent > 0) {
                int removed = 0;
                while (contentStart < end && removed < commonIndent
                        && blockStringWhitespace(value.charAt(contentStart))) {
                    contentStart++;
                    removed++;
                }
            }
            if (line > 0) {
                normalized = normalized + "\n";
            }
            normalized = normalized + value.substring(contentStart, end);
            if (end == value.length()) {
                break;
            }
            position = end + 1;
            line++;
        }
        return trimBlockStringBlankLines(normalized);
    }

    private static int blockStringLineEnd(String value, int start) {
        int position = start;
        while (position < value.length() && value.charAt(position) != '\n') {
            position++;
        }
        return position;
    }

    private static boolean blockStringLineIsBlank(String value, int start, int end) {
        while (start < end) {
            if (!blockStringWhitespace(value.charAt(start))) {
                return false;
            }
            start++;
        }
        return true;
    }

    private static int blockStringLineIndent(String value, int start, int end) {
        int indent = 0;
        while (start < end && blockStringWhitespace(value.charAt(start))) {
            indent++;
            start++;
        }
        return indent;
    }

    private static boolean blockStringWhitespace(char value) {
        return value == ' ' || value == '\t';
    }

    private static String trimBlockStringBlankLines(String value) {
        int start = 0;
        while (start < value.length()) {
            int end = blockStringLineEnd(value, start);
            if (!blockStringLineIsBlank(value, start, end)) {
                break;
            }
            start = end == value.length() ? end : end + 1;
        }
        int end = value.length();
        while (end > start) {
            int lineStart = end - 1;
            while (lineStart >= start && value.charAt(lineStart) != '\n') {
                lineStart--;
            }
            int contentStart = lineStart + 1;
            if (!blockStringLineIsBlank(value, contentStart, end)) {
                break;
            }
            end = lineStart < start ? start : lineStart;
        }
        return value.substring(start, end);
    }

    /**
     * Validates the reviewed {@code UUID} scalar before a generated database binding attempts a
     * dialect-specific cast. Its input is the same GraphQL/JSON string representation accepted by
     * {@link #stringArgument(String)}.
     */
    public static boolean uuidArgumentIsValid(String value) {
        return uuidTextIsValid(stringArgument(value));
    }

    /**
     * Validates a decoded UUID value, including values recovered from an opaque Relay cursor.
     * Keeping this separate from {@link #uuidArgumentIsValid(String)} prevents a cursor segment
     * from being treated as GraphQL source text before its dialect-specific database binding.
     */
    public static boolean uuidTextIsValid(String decoded) {
        if (decoded == null || decoded.length() != 36) {
            return false;
        }
        int position = 0;
        while (position < decoded.length()) {
            char current = decoded.charAt(position);
            if (position == 8 || position == 13 || position == 18 || position == 23) {
                if (current != '-') {
                    return false;
                }
            } else if (!((current >= '0' && current <= '9')
                    || (current >= 'a' && current <= 'f')
                    || (current >= 'A' && current <= 'F'))) {
                return false;
            }
            position++;
        }
        return true;
    }

    /**
     * Coerces a GraphQL Boolean literal or a JSON Boolean variable representation. The integer
     * result keeps the generated routine's control-flow subset scalar-only: {@code 1}=true,
     * {@code 0}=false, {@code -1}=invalid.
     */
    public static int booleanArgument(String value) {
        if ("true".equals(value)) {
            return 1;
        }
        if ("false".equals(value)) {
            return 0;
        }
        return -1;
    }

    /**
     * Checks the numeric representation used for GraphQL Float/Decimal arguments without using a
     * JVM number parser. Variables arrive here as a JSON number and literals as their GraphQL
     * spelling; accepting an integer spelling is intentional because GraphQL input coercion
     * permits an integer JSON value for a Float variable.
     */
    public static boolean decimalArgumentIsValid(String value) {
        if (value == null || value.length() == 0) {
            return false;
        }
        int position = 0;
        if (value.charAt(position) == '-') {
            position++;
        }
        if (position >= value.length()) {
            return false;
        }
        if (value.charAt(position) == '0') {
            position++;
            if (position < value.length() && value.charAt(position) >= '0' && value.charAt(position) <= '9') {
                return false;
            }
        } else {
            if (value.charAt(position) < '1' || value.charAt(position) > '9') {
                return false;
            }
            position++;
            while (position < value.length() && value.charAt(position) >= '0' && value.charAt(position) <= '9') {
                position++;
            }
        }
        if (position < value.length() && value.charAt(position) == '.') {
            position++;
            int fractionStart = position;
            while (position < value.length() && value.charAt(position) >= '0' && value.charAt(position) <= '9') {
                position++;
            }
            if (position == fractionStart) {
                return false;
            }
        }
        int mantissaEnd = position;
        int exponent = 0;
        boolean negativeExponent = false;
        if (position < value.length() && (value.charAt(position) == 'e' || value.charAt(position) == 'E')) {
            position++;
            if (position < value.length() && (value.charAt(position) == '+' || value.charAt(position) == '-')) {
                negativeExponent = value.charAt(position) == '-';
                position++;
            }
            int exponentStart = position;
            while (position < value.length() && value.charAt(position) >= '0' && value.charAt(position) <= '9') {
                // Only the range class matters below. Capping avoids integer overflow for an
                // untrusted, arbitrarily long exponent while preserving that it is enormous.
                if (exponent < 10000) {
                    exponent = exponent * 10 + value.charAt(position) - '0';
                }
                position++;
            }
            if (position == exponentStart) {
                return false;
            }
        }
        if (position != value.length()) {
            return false;
        }

        // Determine the decimal exponent of the first non-zero significant digit without first
        // evaluating the number. A database DOUBLE assignment can throw before a post-parse
        // range check gets a chance to turn an out-of-range GraphQL Float into an error response.
        int scan = value.charAt(0) == '-' ? 1 : 0;
        int digitIndex = 0;
        int digitsBeforeDecimal = 0;
        int firstNonZeroDigit = -1;
        int significantDigits = 0;
        long significantPrefix = 0;
        boolean laterSignificantNonZero = false;
        boolean beforeDecimal = true;
        while (scan < mantissaEnd) {
            char current = value.charAt(scan);
            if (current == '.') {
                beforeDecimal = false;
            } else {
                if (beforeDecimal) {
                    digitsBeforeDecimal++;
                }
                if (current != '0' && firstNonZeroDigit < 0) {
                    firstNonZeroDigit = digitIndex;
                }
                if (firstNonZeroDigit >= 0) {
                    if (significantDigits < 17) {
                        significantPrefix = significantPrefix * 10 + current - '0';
                        significantDigits++;
                    } else if (current != '0') {
                        laterSignificantNonZero = true;
                    }
                }
                digitIndex++;
            }
            scan++;
        }
        // Signed zero remains a valid Float regardless of a large exponent.
        if (firstNonZeroDigit < 0) {
            return true;
        }
        while (significantDigits < 17) {
            significantPrefix = significantPrefix * 10;
            significantDigits++;
        }
        int signedExponent = negativeExponent ? -exponent : exponent;
        int leadingDecimalExponent = signedExponent + digitsBeforeDecimal - firstNonZeroDigit - 1;
        if (leadingDecimalExponent > 308) {
            return false;
        }
        // Double.MAX_VALUE is 1.7976931348623157e308. At that exponent compare the first 17
        // significant digits and reject a non-zero tail on an equal prefix conservatively.
        if (leadingDecimalExponent == 308
                && (significantPrefix > 17976931348623157L
                || (significantPrefix == 17976931348623157L && laterSignificantNonZero))) {
            return false;
        }
        double parsed = decimalArgument(value);
        return parsed <= 1.7976931348623157E308 && parsed >= -1.7976931348623157E308;
    }

    /** Parses a syntactically-valid decimal without JVM parsing/reflection dependencies. */
    public static double decimalArgument(String value) {
        int position = 0;
        boolean negative = false;
        if (value != null && value.length() > 0 && value.charAt(position) == '-') {
            negative = true;
            position++;
        }
        double result = 0.0;
        boolean magnitudeExceeded = false;
        while (position < value.length() && value.charAt(position) >= '0' && value.charAt(position) <= '9') {
            if (!magnitudeExceeded) {
                // Do not let an invalid, enormous mantissa overflow before the generated caller
                // can return its normal GraphQL coercion error. The validator rejects this
                // sentinel magnitude; it is solely a safe intermediate representation.
                if (result > 1.7976931348623157E307) {
                    result = 1.7976931348623157E308;
                    magnitudeExceeded = true;
                } else {
                    result = result * 10.0 + (value.charAt(position) - '0');
                }
            }
            position++;
        }
        if (position < value.length() && value.charAt(position) == '.') {
            position++;
            double place = 0.1;
            while (position < value.length() && value.charAt(position) >= '0' && value.charAt(position) <= '9') {
                if (!magnitudeExceeded) {
                    result = result + (value.charAt(position) - '0') * place;
                }
                place = place / 10.0;
                position++;
            }
        }
        int exponent = 0;
        boolean negativeExponent = false;
        if (position < value.length() && (value.charAt(position) == 'e' || value.charAt(position) == 'E')) {
            position++;
            if (position < value.length() && (value.charAt(position) == '+' || value.charAt(position) == '-')) {
                negativeExponent = value.charAt(position) == '-';
                position++;
            }
            while (position < value.length() && value.charAt(position) >= '0' && value.charAt(position) <= '9') {
                if (exponent < 10000) {
                    exponent = exponent * 10 + value.charAt(position) - '0';
                }
                position++;
            }
        }
        if (result == 0.0) {
            return 0.0;
        }
        // decimalArgumentIsValid rejects positive values outside binary64 range before callers
        // reach this method. These guards also keep this low-level helper safe if reused later.
        if (!negativeExponent && exponent > 308) {
            return 1.7976931348623157E308;
        }
        if (negativeExponent && exponent > 324) {
            return 0.0;
        }
        while (exponent > 0) {
            if (!negativeExponent && exponent == 1 && result > 1.7976931348623157E307) {
                return 1.7976931348623157E308;
            }
            result = negativeExponent ? result / 10.0 : result * 10.0;
            exponent--;
        }
        return negative ? -result : result;
    }

    /**
     * Counts syntactically valid arguments on a field. Generated bindings compare this with their
     * reviewed descriptor count, which rejects duplicate and unknown arguments instead of silently
     * ignoring them. Returns {@code -1} for malformed argument syntax.
     */
    public static int fieldArgumentCount(String query, int fieldStart) {
        return fieldArgumentCount(query, documentPlan(query), fieldStart);
    }

    /** Counts parsed argument nodes from the request-local language plan. */
    public static int fieldArgumentCount(String query, String languagePlan, int fieldStart) {
        return DatabaseGraphqlLanguage.fieldArgumentCount(languagePlan, fieldStart);
    }

    /** Counts field arguments from the selected-operation AST retained by generated routines. */
    public static int fieldArgumentCountFromAst(String requestAst, int fieldStart) {
        return DatabaseGraphqlAst.fieldArgumentCount(requestAst, fieldStart);
    }

    /**
     * Validates that every argument is one of the comma-separated reviewed names and appears once.
     * This is used by generated connection roots, where an absent optional argument must be
     * distinguishable from a different unknown argument with the same arity.
     */
    public static boolean fieldHasOnlyArguments(String query, int fieldStart, String allowedNames) {
        return fieldHasOnlyArguments(query, documentPlan(query), fieldStart, allowedNames);
    }

    /** Validates parsed argument nodes from the request-local language plan. */
    public static boolean fieldHasOnlyArguments(
            String query,
            String languagePlan,
            int fieldStart,
            String allowedNames
    ) {
        return DatabaseGraphqlLanguage.fieldHasOnlyArguments(query, languagePlan, fieldStart, allowedNames);
    }

    /**
     * Checks allowed/unique field arguments using typed AST ranges instead of the syntax index.
     * The explicit count and response-local seen set preserve the legacy duplicate rejection
     * contract for generated schema bindings.
     */
    public static boolean fieldHasOnlyArgumentsFromAst(
            String query,
            String requestAst,
            int fieldStart,
            String allowedNames
    ) {
        if (query == null || allowedNames == null) {
            return false;
        }
        int count = DatabaseGraphqlAst.fieldArgumentCount(requestAst, fieldStart);
        if (count < 0) {
            return false;
        }
        String seen = ",";
        int ordinal = 0;
        while (ordinal < count) {
            String info = DatabaseGraphqlAst.fieldArgumentInfo(requestAst, fieldStart, ordinal);
            int nameStart = astInfoValue(info, 0);
            int nameEnd = astInfoValue(info, 1);
            if (nameStart < 0 || nameEnd <= nameStart || nameEnd > query.length()) {
                return false;
            }
            String name = query.substring(nameStart, nameEnd);
            if (!commaSeparatedNameContains(allowedNames, name) || commaSeparatedNameContains(seen, name)) {
                return false;
            }
            seen = seen + name + ",";
            ordinal++;
        }
        return true;
    }

    /**
     * Returns whether a selected field is a leaf after its optional arguments and directives.
     * GraphQL's built-in {@code __typename}, like generated scalar fields, must not accept a
     * nested selection. Keeping this small syntactic check in the shared transpiled runtime lets
     * the schema-specialized generator enforce the rule without a JVM parser.
     */
    public static boolean fieldHasNoSelectionSet(String query, int fieldStart) {
        return fieldHasNoSelectionSet(query, documentPlan(query), fieldStart);
    }

    /** Evaluates leaf-field syntax against the request-local language plan. */
    public static boolean fieldHasNoSelectionSet(String query, String languagePlan, int fieldStart) {
        int position = DatabaseGraphqlLanguage.fieldHeaderEnd(query, languagePlan, fieldStart);
        if (position < 0) {
            return false;
        }
        position = skipDirectives(query, position, query.length());
        return position >= query.length() || query.charAt(position) != '{';
    }

    /** Evaluates leaf syntax from a typed field node whose child-selection offset is retained. */
    public static boolean fieldHasNoSelectionSetFromAst(String query, String ast, int fieldStart) {
        return fieldInfoValue(ast, fieldStart, 1) < 0;
    }

    public static boolean operationTypeIsMutation(String query, String operationName) {
        return DatabaseGraphqlLanguage.selectedOperationKind(query, operationName).equals("mutation");
    }

    public static boolean operationTypeIsSubscription(String query) {
        return DatabaseGraphqlLanguage.selectedOperationKind(query, "").equals("subscription");
    }

    /** Returns whether the selected operation retained in a request plan is a mutation. */
    public static boolean operationPlanIsMutation(String languagePlan) {
        return DatabaseGraphqlLanguage.operationKind(languagePlan).equals("mutation");
    }

    /** Returns whether the selected operation retained in a request plan is a subscription. */
    public static boolean operationPlanIsSubscription(String languagePlan) {
        return DatabaseGraphqlLanguage.operationKind(languagePlan).equals("subscription");
    }

    private static String jsonObjectEnvelopeError(String json, String fieldName) {
        if (json == null || json.length() == 0) {
            return "";
        }
        int start = skipJsonWhitespace(json, 0, json.length());
        if (start >= json.length() || json.charAt(start) != '{') {
            return "request field '" + fieldName + "' must be a JSON object";
        }
        int end = strictJsonValueEnd(json, start, json.length());
        if (end < 0 || skipJsonWhitespace(json, end, json.length()) != json.length()) {
            return "request field '" + fieldName + "' must be a valid JSON object";
        }
        return "";
    }

    private static String jsonObjectFieldValue(String json, String expectedName) {
        if (json == null || json.length() == 0) {
            return "";
        }
        // Generated execution aliases the validated variables envelope to the bounded mv1 carrier
        // after selected-operation preflight. Keeping the existing scalar lookup boundary lets AST
        // selection/directive traversal consume that carrier too, without a second helper family
        // or any repeated envelope scan.
        if (json.startsWith(MATERIALIZED_VARIABLES_PREFIX)) {
            return materializedVariableValue(json, expectedName);
        }
        int start = skipJsonWhitespace(json, 0, json.length());
        if (start >= json.length() || json.charAt(start) != '{') {
            return "";
        }
        int end = strictJsonValueEnd(json, start, json.length());
        if (end < 0) {
            return "";
        }
        int position = start + 1;
        while (position < end - 1) {
            position = skipJsonWhitespace(json, position, end);
            if (position >= end - 1) {
                break;
            }
            if (json.charAt(position) != '"') {
                return "";
            }
            int nameEnd = strictJsonStringEnd(json, position, end);
            if (nameEnd < 0) {
                return "";
            }
            String name = jsonStringValue(json.substring(position, nameEnd));
            int colon = skipJsonWhitespace(json, nameEnd, end);
            if (colon >= end || json.charAt(colon) != ':') {
                return "";
            }
            int valueStart = skipJsonWhitespace(json, colon + 1, end);
            int valueEnd = strictJsonValueEnd(json, valueStart, end);
            if (valueEnd < 0) {
                return "";
            }
            if (name.equals(expectedName)) {
                return json.substring(valueStart, valueEnd);
            }
            position = skipJsonWhitespace(json, valueEnd, end);
            if (position < end - 1 && json.charAt(position) == ',') {
                position++;
            } else if (position != end - 1) {
                return "";
            }
        }
        return "";
    }

    private static String jsonStringValue(String jsonLiteral) {
        if (jsonLiteral.length() < 2 || jsonLiteral.charAt(0) != '"') {
            return "";
        }
        String value = "";
        int position = 1;
        while (position < jsonLiteral.length() - 1) {
            char current = jsonLiteral.charAt(position);
            if (current == '\\' && position + 1 < jsonLiteral.length() - 1) {
                char escaped = jsonLiteral.charAt(position + 1);
                if (escaped == 'b') value = value + '\b';
                else if (escaped == 'f') value = value + '\f';
                else if (escaped == 'n') value = value + '\n';
                else if (escaped == 'r') value = value + '\r';
                else if (escaped == 't') value = value + '\t';
                else if (escaped == 'u' && position + 5 < jsonLiteral.length() - 1) {
                    // Titan represents a Java char as TEXT and cannot lower a UTF-16 numeric
                    // char cast. Preserve a validated escaped code unit rather than silently
                    // substituting a different character. The generated scalar coercers can
                    // still handle direct Unicode JSON text; escape decoding awaits a portable
                    // Titan string-code-point primitive.
                    value = value + "\\u" + jsonLiteral.substring(position + 2, position + 6);
                    position += 6;
                } else {
                    value = value + escaped;
                    position += 2;
                }
            } else {
                value = value + current;
                position++;
            }
        }
        return value;
    }

    /**
     * Strictly parses one JSON value with a bounded, scalar-encoded container stack. This is kept
     * separate from GraphQL's ignored-token rules: JSON permits neither comments nor optional
     * commas, and variable values must never be accepted with GraphQL's more permissive scanner.
     * The returned index is the first character after the value, or {@code -1} on malformed input.
     */
    private static int strictJsonValueEnd(String source, int position, int limit) {
        position = skipJsonWhitespace(source, position, limit);
        String kinds = "";
        String states = "";
        boolean parseValue = true;
        while (true) {
            position = skipJsonWhitespace(source, position, limit);
            if (parseValue) {
                if (position >= limit) {
                    return -1;
                }
                char current = source.charAt(position);
                if (current == '{') {
                    if (kinds.length() >= MAX_JSON_NESTING) {
                        return -1;
                    }
                    kinds = kinds + "o";
                    states = states + "O";
                    position++;
                    parseValue = false;
                    continue;
                }
                if (current == '[') {
                    if (kinds.length() >= MAX_JSON_NESTING) {
                        return -1;
                    }
                    kinds = kinds + "a";
                    states = states + "A";
                    position++;
                    parseValue = false;
                    continue;
                }
                if (current == '"') {
                    position = strictJsonStringEnd(source, position, limit);
                } else if (current == '-' || (current >= '0' && current <= '9')) {
                    position = strictJsonNumberEnd(source, position, limit);
                } else if (startsWithAt(source, "true", position, limit)) {
                    position += 4;
                } else if (startsWithAt(source, "false", position, limit)) {
                    position += 5;
                } else if (startsWithAt(source, "null", position, limit)) {
                    position += 4;
                } else {
                    return -1;
                }
                if (position < 0) {
                    return -1;
                }
                if (kinds.length() == 0) {
                    return position;
                }
                parseValue = false;
                continue;
            }

            if (kinds.length() == 0) {
                return -1;
            }
            int depth = kinds.length() - 1;
            char kind = kinds.charAt(depth);
            char state = states.charAt(depth);
            if (kind == 'o') {
                if (state == 'O' || state == 'K') {
                    if (state == 'O' && position < limit && source.charAt(position) == '}') {
                        kinds = kinds.substring(0, depth);
                        states = states.substring(0, depth);
                        position++;
                        if (kinds.length() == 0) {
                            return position;
                        }
                        continue;
                    }
                    if (position >= limit || source.charAt(position) != '"') {
                        return -1;
                    }
                    position = strictJsonStringEnd(source, position, limit);
                    if (position < 0) {
                        return -1;
                    }
                    states = jsonStackState(states, depth, 'C');
                    continue;
                }
                if (state == 'C') {
                    if (position >= limit || source.charAt(position) != ':') {
                        return -1;
                    }
                    states = jsonStackState(states, depth, 'V');
                    position++;
                    continue;
                }
                if (state == 'V') {
                    states = jsonStackState(states, depth, 'D');
                    parseValue = true;
                    continue;
                }
                if (state == 'D') {
                    if (position < limit && source.charAt(position) == ',') {
                        states = jsonStackState(states, depth, 'K');
                        position++;
                        continue;
                    }
                    if (position < limit && source.charAt(position) == '}') {
                        kinds = kinds.substring(0, depth);
                        states = states.substring(0, depth);
                        position++;
                        if (kinds.length() == 0) {
                            return position;
                        }
                        continue;
                    }
                    return -1;
                }
                return -1;
            }
            if (kind != 'a') {
                return -1;
            }
            if (state == 'A' || state == 'B') {
                if (state == 'A' && position < limit && source.charAt(position) == ']') {
                    kinds = kinds.substring(0, depth);
                    states = states.substring(0, depth);
                    position++;
                    if (kinds.length() == 0) {
                        return position;
                    }
                    continue;
                }
                states = jsonStackState(states, depth, 'D');
                parseValue = true;
                continue;
            }
            if (state == 'D') {
                if (position < limit && source.charAt(position) == ',') {
                    states = jsonStackState(states, depth, 'B');
                    position++;
                    continue;
                }
                if (position < limit && source.charAt(position) == ']') {
                    kinds = kinds.substring(0, depth);
                    states = states.substring(0, depth);
                    position++;
                    if (kinds.length() == 0) {
                        return position;
                    }
                    continue;
                }
            }
            return -1;
        }
    }

    private static String jsonStackState(String states, int depth, char state) {
        return states.substring(0, depth) + state + states.substring(depth + 1, states.length());
    }

    private static int strictJsonStringEnd(String source, int start, int limit) {
        if (start >= limit || source.charAt(start) != '"') {
            return -1;
        }
        int position = start + 1;
        while (position < limit) {
            char current = source.charAt(position);
            if (current == '"') {
                return position + 1;
            }
            if (current < ' ') {
                return -1;
            }
            if (current == '\\') {
                if (position + 1 >= limit) {
                    return -1;
                }
                char escaped = source.charAt(position + 1);
                if (escaped == '"' || escaped == '\\' || escaped == '/' || escaped == 'b'
                        || escaped == 'f' || escaped == 'n' || escaped == 'r' || escaped == 't') {
                    position += 2;
                    continue;
                }
                if (escaped != 'u' || position + 5 >= limit || jsonHexValue(source, position + 2) < 0) {
                    return -1;
                }
                position += 6;
                continue;
            }
            position++;
        }
        return -1;
    }

    private static int strictJsonNumberEnd(String source, int start, int limit) {
        int position = start;
        if (source.charAt(position) == '-') {
            position++;
            if (position >= limit) {
                return -1;
            }
        }
        if (source.charAt(position) == '0') {
            position++;
        } else if (source.charAt(position) >= '1' && source.charAt(position) <= '9') {
            position++;
            while (position < limit && source.charAt(position) >= '0' && source.charAt(position) <= '9') {
                position++;
            }
        } else {
            return -1;
        }
        if (position < limit && source.charAt(position) == '.') {
            position++;
            int fractionalStart = position;
            while (position < limit && source.charAt(position) >= '0' && source.charAt(position) <= '9') {
                position++;
            }
            if (position == fractionalStart) {
                return -1;
            }
        }
        if (position < limit && (source.charAt(position) == 'e' || source.charAt(position) == 'E')) {
            position++;
            if (position < limit && (source.charAt(position) == '+' || source.charAt(position) == '-')) {
                position++;
            }
            int exponentStart = position;
            while (position < limit && source.charAt(position) >= '0' && source.charAt(position) <= '9') {
                position++;
            }
            if (position == exponentStart) {
                return -1;
            }
        }
        return position;
    }

    private static int skipJsonWhitespace(String source, int position, int limit) {
        while (position < limit) {
            char current = source.charAt(position);
            if (current == ' ' || current == '\n' || current == '\r' || current == '\t') {
                position++;
            } else {
                return position;
            }
        }
        return position;
    }

    private static boolean startsWithAt(String source, String value, int start, int limit) {
        if (start + value.length() > limit) {
            return false;
        }
        int position = 0;
        while (position < value.length()) {
            if (source.charAt(start + position) != value.charAt(position)) {
                return false;
            }
            position++;
        }
        return true;
    }

    private static int jsonHexValue(String source, int start) {
        if (start + 4 > source.length()) {
            return -1;
        }
        int value = 0;
        int position = 0;
        while (position < 4) {
            char current = source.charAt(start + position);
            int digit;
            if (current >= '0' && current <= '9') {
                digit = current - '0';
            } else if (current >= 'a' && current <= 'f') {
                digit = 10 + current - 'a';
            } else if (current >= 'A' && current <= 'F') {
                digit = 10 + current - 'A';
            } else {
                return -1;
            }
            value = value * 16 + digit;
            position++;
        }
        return value;
    }

    private static int topSelectionStart(String query) {
        return topSelectionStart(query, documentPlan(query));
    }

    private static int topSelectionStart(String query, String languagePlan) {
        return DatabaseGraphqlAst.operationSelectionStart(DatabaseGraphqlAst.parse(query, languagePlan));
    }

    /** Operation directives need a distinct execution boundary; reject them until it exists. */
    private static boolean operationHasDirectives(String query, String languagePlan) {
        int selectionStart = topSelectionStart(query, languagePlan);
        return selectionStart >= 0 && DatabaseGraphqlLanguage.hasDirectiveBefore(languagePlan, selectionStart);
    }

    /**
     * Validates the selected operation's variable declarations and all `$name` references before
     * generated schema dispatch. Duplicate declarations and undefined references must not depend
     * on which root happens to execute first; the generated scalar schema descriptor supplies the
     * input coercion rules applied here before root dispatch.
     */
    private static String selectedOperationVariableError(
            String query,
            String variablesJson,
            String allowedInputTypeNames
    ) {
        String languagePlan = documentPlan(query);
        return selectedOperationVariableError(query, DatabaseGraphqlAst.parse(query, languagePlan),
                variablesJson, allowedInputTypeNames);
    }

    /** Uses selected-operation variable nodes retained in the invocation-local AST. */
    private static String selectedOperationVariableError(
            String query,
            String ast,
            String variablesJson,
            String allowedInputTypeNames
    ) {
        int selectionStart = DatabaseGraphqlAst.operationSelectionStart(ast);
        if (selectionStart < 0) {
            return "selected operation has malformed variable definitions";
        }
        String declared = ",";
        String used = ",";
        int inputWorkItems = 0;
        if (ast.length() == 0) {
            return "selected operation has malformed variable definitions";
        }
        int astRecordStart = 5;
        int astPosition = 0;
        while (astPosition < ast.length()) {
            char astCharacter = ast.charAt(astPosition);
            if (astPosition >= 5 && astCharacter == ';') {
                int astEnd = astPosition;
                int astFirst = ast.indexOf(':', astRecordStart + 1);
                int astSecond = astFirst < 0 ? -1 : ast.indexOf(':', astFirst + 1);
                int astThird = astSecond < 0 ? -1 : ast.indexOf(':', astSecond + 1);
                if (astEnd <= astRecordStart || ast.charAt(astRecordStart) != 'N' || astFirst != astRecordStart + 2
                        || astSecond < astFirst || astThird < astSecond || astThird >= astEnd) {
                    return "selected operation has malformed variable definitions";
                }
                char astKind = ast.charAt(astRecordStart + 1);
                int definition = astInfoValue(ast.substring(astFirst + 1, astEnd), 0);
                if (definition < 0 || definition > query.length()) {
                    return "selected operation has malformed variable definitions";
                }
                String payload = ast.substring(astThird + 1, astEnd);
                if (astKind == 'e') {
                    return "selected operation has malformed variable definitions";
                }
                if (astKind == 'v') {
                    int nameEnd = astInfoValue(payload, 0);
                    int typeStart = astInfoValue(payload, 1);
                    int typeEnd = astInfoValue(payload, 2);
                    int valueStart = astInfoValue(payload, 3);
                    int valueEnd = astInfoValue(payload, 4);
                    int nameStart = definition + 1;
                    if (nameEnd <= nameStart || typeEnd <= typeStart || typeEnd > query.length()) {
                        return "selected operation has malformed variable definitions";
                    }
                    String name = query.substring(nameStart, nameEnd);
                    if (commaSeparatedNameContains(declared, name)) {
                        return "selected operation declares variable '$" + name + "' more than once";
                }
                declared = declared + name + ",";
                String declaredType = query.substring(typeStart, typeEnd);
                String typeReference = DatabaseGraphqlTypeReference.parse(declaredType);
                if (typeReference.length() == 0) {
                    return "selected operation has malformed variable definitions";
                }
                String declaredNamedType = DatabaseGraphqlTypeReference.namedType(typeReference);
                if (!variableDeclaredTypeIsAllowedInput(declaredNamedType, allowedInputTypeNames)) {
                    return "variable '$" + name + "' declares unsupported input type '" + declaredNamedType + "'";
                }
                if (valueStart >= 0 || valueEnd >= 0) {
                    if (valueEnd <= valueStart || valueEnd > query.length()) {
                        return "selected operation has malformed variable definitions";
                    }
                    String defaultError = variableDefaultValueError(query, valueStart, valueEnd, typeReference);
                    if (defaultError.length() != 0) {
                        return defaultError;
                    }
                    String valueResult = variableInputValueResult(name, typeReference,
                            query.substring(valueStart, valueEnd), allowedInputTypeNames, false,
                            MAX_INPUT_COERCION_ITEMS - inputWorkItems);
                    int valueWorkItems = valueResult.startsWith(INPUT_WORK_SUCCESS_PREFIX)
                            ? inputDescriptorDecimal(valueResult, INPUT_WORK_SUCCESS_PREFIX.length(),
                            valueResult.length()) : -1;
                    if (valueWorkItems < 0) {
                        return valueResult;
                    }
                    inputWorkItems += valueWorkItems;
                    }
                String supplied = jsonObjectFieldValue(variablesJson, name);
                if (supplied.length() == 0) {
                    if (valueStart < 0 && DatabaseGraphqlTypeReference.isOuterNonNull(typeReference)) {
                        return "required variable '$" + name + "' is missing";
                    }
                } else if (supplied.equals("null") && DatabaseGraphqlTypeReference.isOuterNonNull(typeReference)) {
                    return "variable '$" + name + "' cannot be null";
                } else if (!supplied.equals("null")) {
                    String valueResult = variableInputValueResult(name, typeReference, supplied,
                            allowedInputTypeNames, true, MAX_INPUT_COERCION_ITEMS - inputWorkItems);
                    int valueWorkItems = valueResult.startsWith(INPUT_WORK_SUCCESS_PREFIX)
                            ? inputDescriptorDecimal(valueResult, INPUT_WORK_SUCCESS_PREFIX.length(),
                            valueResult.length()) : -1;
                    if (valueWorkItems < 0) {
                        return valueResult;
                    }
                    inputWorkItems += valueWorkItems;
                }
                } else if (astKind == 'u') {
                    int nameEnd = astInfoValue(payload, 0);
                    int referenceStart = definition;
                    if (referenceStart < 0 || nameEnd <= referenceStart + 1 || nameEnd > query.length()) {
                        return "selected operation contains a malformed variable reference";
                    }
                    String name = query.substring(referenceStart + 1, nameEnd);
                    if (!commaSeparatedNameContains(declared, name)) {
                        return "selected operation references undefined variable '$" + name + "'";
                    }
                    if (!commaSeparatedNameContains(used, name)) {
                        used = used + name + ",";
                    }
                }
                astRecordStart = astEnd + 1;
            }
            astPosition++;
        }
        if (astRecordStart != ast.length()) {
            return "selected operation has malformed variable definitions";
        }
        String suppliedVariableError = suppliedVariableError(variablesJson, declared);
        if (suppliedVariableError.length() != 0) {
            return suppliedVariableError;
        }
        String unused = firstCommaSeparatedNameMissingFrom(declared, used);
        if (unused.length() != 0) {
            return "variable '$" + unused + "' is never used";
        }
        return "";
    }

    /**
     * Locates a variable-declaration error without adding a second parser or a request-side error
     * object.  The variable validator already guarantees that the quoted name in these messages
     * is an executable identifier.  A duplicate declaration or an undefined reference has no
     * unique declaration node, so those deliberately retain the location-free response until the
     * typed validation-error carrier can preserve all relevant source nodes.
     */
    private static int variableErrorSourceOffset(String query, String requestAst, String message) {
        if (query == null || requestAst == null || message == null) {
            return -1;
        }
        int marker = message.indexOf("'$", 0);
        int nameStart = marker < 0 ? -1 : marker + 2;
        int nameEnd = nameStart < 0 ? -1 : message.indexOf("'", nameStart);
        if (nameEnd <= nameStart) {
            return -1;
        }
        String definition = DatabaseGraphqlAst.variableDefinitionInfo(
                query, requestAst, message.substring(nameStart, nameEnd));
        int sourceOffset = astInfoValue(definition, 0);
        return sourceOffset >= 0 && sourceOffset < query.length() ? sourceOffset : -1;
    }

    /**
     * Applies the two operation-level default-value rules that do not require an input-object
     * schema: a non-null variable cannot default to {@code null}, and defaults are constants
     * rather than expressions over other variables. Generated bindings retain scalar coercion and
     * model-specific type compatibility.
     */
    private static String variableDefaultValueError(
            String query,
            int defaultStart,
            int defaultEnd,
            String typeReference
    ) {
        String valueInfo = DatabaseGraphqlAst.defaultValueInfo(query, defaultStart, defaultEnd);
        if (valueInfo.length() == 0) {
            return "selected operation has malformed variable definitions";
        }
        if (DatabaseGraphqlTypeReference.isOuterNonNull(typeReference) && astInfoValue(valueInfo, 0) == 1) {
            return "non-null variable cannot declare a null default value";
        }
        int referenceStart = astInfoValue(valueInfo, 1);
        int referenceEnd = astInfoValue(valueInfo, 2);
        if (referenceStart >= defaultStart && referenceEnd > referenceStart && referenceEnd <= defaultEnd) {
            return "variable default value cannot reference '$"
                    + query.substring(referenceStart + 1, referenceEnd) + "'";
        }
        return "";
    }

    /**
     * Validates scalar JSON values at the selected-operation boundary. List and input-object
     * coercion deliberately remain schema-plan work; these named scalar rules are independent of
     * the field that eventually consumes the variable and therefore prevent directive or root
     * dispatch from becoming the first validation authority.
     */
    private static String scalarVariableValueError(String name, String namedType, String value) {
        boolean valid = true;
        if (namedType.equals("Boolean")) {
            valid = booleanArgument(value) >= 0;
        } else if (namedType.equals("Int")) {
            valid = !invalidIntegerForScalar(longArgument(value), "Int");
        } else if (namedType.equals("Float") || namedType.equals("Decimal")) {
            valid = decimalArgumentIsValid(value);
        } else if (namedType.equals("String")) {
            valid = stringArgumentIsValid(value);
        } else if (namedType.equals("UUID")) {
            valid = uuidArgumentIsValid(value);
        } else if (namedType.equals("Long")) {
            valid = !invalidLong(longArgument(value));
        } else if (namedType.equals("ID")) {
            valid = stringArgumentIsValid(value) || !invalidLong(longArgument(value));
        }
        return valid ? "" : "variable '$" + name + "' cannot be coerced to '" + namedType + "'";
    }

    /**
     * Materializes one already-validated argument into a scalar value or a bounded {@code cv1}
     * container. Container nodes are flattened by typed field/index paths so generated bindings
     * can navigate them with the ordinary input-object/list helpers without recursion. An
     * undefined variable at an input-object field omits that field; explicit {@code null} remains
     * a value. An undefined nullable list item becomes {@code null} to retain its position. The
     * private success carrier includes the consumed node count so the caller can enforce one
     * materialization budget across all arguments in the selected operation.
     */
    private static String materializedInputValue(
            String value,
            String typeReference,
            String materializedVariables,
            String inputDescriptor,
            String locationDefaultValue,
            int maximumWorkItems
    ) {
        if (maximumWorkItems <= 0) {
            return RESOURCE_LIMIT_FAILURE_PREFIX
                    + "selected operation exceeds the input materialization work budget";
        }
        String queue = inputMaterializationQueueEntry("", value, typeReference, 0);
        String canonical = CANONICAL_INPUT_PREFIX;
        int queuePosition = 0;
        int workItems = 0;
        while (queuePosition < queue.length()) {
            if (workItems >= maximumWorkItems) {
                return RESOURCE_LIMIT_FAILURE_PREFIX
                        + "selected operation exceeds the input materialization work budget";
            }
            workItems++;
            int pathLengthEnd = queue.indexOf(':', queuePosition);
            int pathLength = inputDescriptorDecimal(queue, queuePosition, pathLengthEnd);
            int pathStart = pathLengthEnd < queuePosition ? -1 : pathLengthEnd + 1;
            int pathEnd = pathStart < 0 || pathLength < 0 ? -1 : pathStart + pathLength;
            int valueLengthEnd = pathEnd < 0 || pathEnd > queue.length() ? -1 : queue.indexOf(':', pathEnd);
            int valueLength = inputDescriptorDecimal(queue, pathEnd, valueLengthEnd);
            int valueStart = valueLengthEnd < pathEnd ? -1 : valueLengthEnd + 1;
            int valueEnd = valueStart < 0 || valueLength < 0 ? -1 : valueStart + valueLength;
            int typeLengthEnd = valueEnd < 0 || valueEnd > queue.length() ? -1 : queue.indexOf(':', valueEnd);
            int typeLength = inputDescriptorDecimal(queue, valueEnd, typeLengthEnd);
            int typeStart = typeLengthEnd < valueEnd ? -1 : typeLengthEnd + 1;
            int typeEnd = typeStart < 0 || typeLength < 0 ? -1 : typeStart + typeLength;
            int depthEnd = typeEnd < 0 || typeEnd > queue.length() ? -1 : queue.indexOf(':', typeEnd);
            int depth = inputDescriptorDecimal(queue, typeEnd, depthEnd);
            if (pathEnd < pathStart || valueEnd < valueStart || typeEnd < typeStart
                    || depthEnd < typeEnd || depth < 0 || depth > MAX_JSON_NESTING) {
                return "";
            }
            queuePosition = depthEnd + 1;
            String path = queue.substring(pathStart, pathEnd);
            String queuedValue = queue.substring(valueStart, valueEnd);
            String queuedType = queue.substring(typeStart, typeEnd);
            String namedType = DatabaseGraphqlTypeReference.namedType(queuedType);
            if (namedType.length() == 0) {
                return "";
            }

            String variableName = inputVariableReferenceName(queuedValue);
            if (variableName.length() != 0) {
                String resolvedValue = materializedVariableValue(materializedVariables, variableName);
                if (resolvedValue.length() == 0) {
                    if (path.length() == 0 && locationDefaultValue != null
                            && locationDefaultValue.length() != 0) {
                        queue = queue + inputMaterializationQueueEntry(
                                path, locationDefaultValue, queuedType, depth);
                    } else if (canonicalPathIsListItem(path)) {
                        canonical = canonical + canonicalInputEntry(path, 'V', "null");
                    }
                    continue;
                }
                queue = queue + inputMaterializationQueueEntry(path, resolvedValue, queuedType, depth);
                continue;
            }

            if (argumentValueIsNull(queuedValue)) {
                canonical = canonical + canonicalInputEntry(path, 'V', "null");
                continue;
            }
            if (DatabaseGraphqlTypeReference.isOuterList(queuedType)) {
                String itemType = DatabaseGraphqlTypeReference.listItemType(queuedType);
                String tokens = DatabaseGraphqlLanguage.lexicalTokenStream(queuedValue);
                int count = DatabaseGraphqlLanguage.graphqlInputListValueCount(queuedValue, tokens);
                if (itemType.length() == 0) {
                    return "";
                }
                canonical = canonical + canonicalInputEntry(path, 'L', "");
                if (count < 0) {
                    queue = queue + inputMaterializationQueueEntry(
                            canonicalListPath(path, 0), queuedValue, itemType, depth + 1);
                } else {
                    // Charge a known fan-out before growing the queue. This keeps a request that
                    // is already over budget from paying to encode and revisit every list item.
                    if (count > maximumWorkItems - workItems) {
                        return RESOURCE_LIMIT_FAILURE_PREFIX
                                + "selected operation exceeds the input materialization work budget";
                    }
                    int item = 0;
                    while (item < count) {
                        String itemValue = DatabaseGraphqlLanguage.graphqlInputListValue(
                                queuedValue, tokens, item);
                        if (itemValue.length() == 0) {
                            return "";
                        }
                        queue = queue + inputMaterializationQueueEntry(
                                canonicalListPath(path, item), itemValue, itemType, depth + 1);
                        item++;
                    }
                }
                continue;
            }

            String descriptorEntry = inputDescriptorEntry(inputDescriptor, namedType);
            if (descriptorEntry.length() == 0) {
                return "";
            }
            if (descriptorEntry.charAt(0) == 'O') {
                canonical = canonical + canonicalInputEntry(path, 'O', "");
                String fields = inputDescriptorEntryPayload(descriptorEntry);
                int fieldStart = 0;
                while (fieldStart < fields.length()) {
                    int fieldEnd = fields.indexOf(',', fieldStart);
                    if (fieldEnd < 0) fieldEnd = fields.length();
                    int equals = fields.indexOf('=', fieldStart);
                    if (equals <= fieldStart || equals >= fieldEnd) {
                        return "";
                    }
                    String fieldName = fields.substring(fieldStart, equals);
                    String fieldType = DatabaseGraphqlTypeReference.parse(
                            fields.substring(equals + 1, fieldEnd));
                    String fieldValue = inputObjectFieldValue(queuedValue, fieldName);
                    if (fieldType.length() == 0) {
                        return "";
                    }
                    if (fieldValue.length() != 0) {
                        queue = queue + inputMaterializationQueueEntry(
                                canonicalObjectPath(path, fieldName), fieldValue, fieldType, depth + 1);
                    } else {
                        String fieldDefault = inputDescriptorFieldDefault(
                                inputDescriptor, namedType, fieldName);
                        if (fieldDefault.length() != 0) {
                            queue = queue + inputMaterializationQueueEntry(
                                    canonicalObjectPath(path, fieldName), fieldDefault, fieldType, depth + 1);
                        }
                    }
                    fieldStart = fieldEnd + 1;
                }
            } else {
                canonical = canonical + canonicalInputEntry(path, 'V', queuedValue);
            }
            if (canonical.length() > MAX_MATERIALIZED_VARIABLE_CHARACTERS
                    || queue.length() > MAX_FRAGMENT_WORK_CHARACTERS) {
                return RESOURCE_LIMIT_FAILURE_PREFIX
                        + "selected operation exceeds the input materialization work budget";
            }
        }
        return INPUT_MATERIALIZATION_SUCCESS_PREFIX + workItems + ":"
                + canonicalInputValueAtPath(canonical, "");
    }

    private static String inputMaterializationQueueEntry(
            String path,
            String value,
            String typeReference,
            int depth
    ) {
        if (path == null || value == null || typeReference == null || depth < 0) {
            return "";
        }
        return path.length() + ":" + path + value.length() + ":" + value
                + typeReference.length() + ":" + typeReference + depth + ":";
    }

    private static String canonicalInputEntry(String path, char kind, String value) {
        if (path == null || value == null || kind != 'V' && kind != 'O' && kind != 'L') {
            return "";
        }
        return path.length() + ":" + path + kind + value.length() + ":" + value + ";";
    }

    private static String canonicalObjectPath(String parent, String fieldName) {
        if (parent == null || fieldName == null || fieldName.length() == 0) {
            return "";
        }
        String segment = "f" + fieldName.length() + ":" + fieldName;
        return parent.length() == 0 ? segment : parent + "/" + segment;
    }

    private static String canonicalListPath(String parent, int ordinal) {
        if (parent == null || ordinal < 0) {
            return "";
        }
        String segment = "i" + ordinal;
        return parent.length() == 0 ? segment : parent + "/" + segment;
    }

    private static boolean canonicalPathIsListItem(String path) {
        if (path == null || path.length() < 2) {
            return false;
        }
        int separator = -1;
        int search = 0;
        while (search < path.length()) {
            int next = path.indexOf('/', search);
            if (next < 0) {
                search = path.length();
            } else {
                separator = next;
                search = next + 1;
            }
        }
        int segmentStart = separator < 0 ? 0 : separator + 1;
        return segmentStart < path.length() && path.charAt(segmentStart) == 'i';
    }

    private static String canonicalInputValueAtPath(String canonical, String expectedPath) {
        if (canonical == null || !canonical.startsWith(CANONICAL_INPUT_PREFIX)
                || expectedPath == null) {
            return "";
        }
        int position = CANONICAL_INPUT_PREFIX.length();
        while (position < canonical.length()) {
            int pathLengthEnd = canonical.indexOf(':', position);
            int pathLength = inputDescriptorDecimal(canonical, position, pathLengthEnd);
            int pathStart = pathLengthEnd < position ? -1 : pathLengthEnd + 1;
            int pathEnd = pathStart < 0 || pathLength < 0 ? -1 : pathStart + pathLength;
            int kindPosition = pathEnd;
            int valueLengthEnd = kindPosition < 0 || kindPosition >= canonical.length()
                    ? -1 : canonical.indexOf(':', kindPosition + 1);
            int valueLength = inputDescriptorDecimal(canonical, kindPosition + 1, valueLengthEnd);
            int valueStart = valueLengthEnd < 0 ? -1 : valueLengthEnd + 1;
            int valueEnd = valueStart < 0 || valueLength < 0 ? -1 : valueStart + valueLength;
            if (pathEnd < pathStart || valueEnd < valueStart || valueEnd >= canonical.length()
                    || canonical.charAt(valueEnd) != ';') {
                return "";
            }
            if (canonical.substring(pathStart, pathEnd).equals(expectedPath)) {
                char kind = canonical.charAt(kindPosition);
                if (kind == 'V') {
                    return canonical.substring(valueStart, valueEnd);
                }
                if (kind == 'O' || kind == 'L') {
                    return canonicalInputSubtree(canonical, expectedPath);
                }
                return "";
            }
            position = valueEnd + 1;
        }
        return "";
    }

    private static String canonicalInputSubtree(String canonical, String rootPath) {
        String subtree = CANONICAL_INPUT_PREFIX;
        String descendantPrefix = rootPath.length() == 0 ? "" : rootPath + "/";
        int position = CANONICAL_INPUT_PREFIX.length();
        while (position < canonical.length()) {
            int pathLengthEnd = canonical.indexOf(':', position);
            int pathLength = inputDescriptorDecimal(canonical, position, pathLengthEnd);
            int pathStart = pathLengthEnd < position ? -1 : pathLengthEnd + 1;
            int pathEnd = pathStart < 0 || pathLength < 0 ? -1 : pathStart + pathLength;
            int kindPosition = pathEnd;
            int valueLengthEnd = kindPosition < 0 || kindPosition >= canonical.length()
                    ? -1 : canonical.indexOf(':', kindPosition + 1);
            int valueLength = inputDescriptorDecimal(canonical, kindPosition + 1, valueLengthEnd);
            int valueStart = valueLengthEnd < 0 ? -1 : valueLengthEnd + 1;
            int valueEnd = valueStart < 0 || valueLength < 0 ? -1 : valueStart + valueLength;
            if (pathEnd < pathStart || valueEnd < valueStart || valueEnd >= canonical.length()
                    || canonical.charAt(valueEnd) != ';') {
                return "";
            }
            String path = canonical.substring(pathStart, pathEnd);
            String relative = "";
            boolean included = path.equals(rootPath);
            if (!included && (rootPath.length() == 0
                    || path.length() > descendantPrefix.length()
                    && path.substring(0, descendantPrefix.length()).equals(descendantPrefix))) {
                included = true;
                relative = rootPath.length() == 0 ? path : path.substring(descendantPrefix.length());
            }
            if (included) {
                subtree = subtree + canonicalInputEntry(relative, canonical.charAt(kindPosition),
                        canonical.substring(valueStart, valueEnd));
            }
            position = valueEnd + 1;
        }
        return subtree;
    }

    private static boolean canonicalInputObjectHasOnlyFields(String canonical, String allowedNames) {
        if (canonicalInputNodeKind(canonical, "") != 'O') {
            return false;
        }
        String seen = ",";
        int position = CANONICAL_INPUT_PREFIX.length();
        while (position < canonical.length()) {
            int pathLengthEnd = canonical.indexOf(':', position);
            int pathLength = inputDescriptorDecimal(canonical, position, pathLengthEnd);
            int pathStart = pathLengthEnd < position ? -1 : pathLengthEnd + 1;
            int pathEnd = pathStart < 0 || pathLength < 0 ? -1 : pathStart + pathLength;
            int kindPosition = pathEnd;
            int valueLengthEnd = kindPosition < 0 || kindPosition >= canonical.length()
                    ? -1 : canonical.indexOf(':', kindPosition + 1);
            int valueLength = inputDescriptorDecimal(canonical, kindPosition + 1, valueLengthEnd);
            int valueStart = valueLengthEnd < 0 ? -1 : valueLengthEnd + 1;
            int valueEnd = valueStart < 0 || valueLength < 0 ? -1 : valueStart + valueLength;
            if (pathEnd < pathStart || valueEnd < valueStart || valueEnd >= canonical.length()
                    || canonical.charAt(valueEnd) != ';') {
                return false;
            }
            String path = canonical.substring(pathStart, pathEnd);
            if (path.length() != 0 && path.indexOf('/') < 0) {
                String fieldName = canonicalInputFieldName(path);
                if (fieldName.length() == 0 || !commaSeparatedNameContains(allowedNames, fieldName)
                        || commaSeparatedNameContains(seen, fieldName)) {
                    return false;
                }
                seen = seen + fieldName + ",";
            }
            position = valueEnd + 1;
        }
        return true;
    }

    private static int canonicalInputListValueCount(String canonical) {
        if (canonicalInputNodeKind(canonical, "") != 'L') {
            return -1;
        }
        int count = 0;
        while (canonicalInputNodeKind(canonical, canonicalListPath("", count)) != 'X') {
            count++;
            if (count > MAX_INPUT_COERCION_ITEMS) {
                return -1;
            }
        }
        return count;
    }

    private static char canonicalInputNodeKind(String canonical, String expectedPath) {
        if (canonical == null || !canonical.startsWith(CANONICAL_INPUT_PREFIX)
                || expectedPath == null) {
            return 'X';
        }
        int position = CANONICAL_INPUT_PREFIX.length();
        while (position < canonical.length()) {
            int pathLengthEnd = canonical.indexOf(':', position);
            int pathLength = inputDescriptorDecimal(canonical, position, pathLengthEnd);
            int pathStart = pathLengthEnd < position ? -1 : pathLengthEnd + 1;
            int pathEnd = pathStart < 0 || pathLength < 0 ? -1 : pathStart + pathLength;
            int kindPosition = pathEnd;
            int valueLengthEnd = kindPosition < 0 || kindPosition >= canonical.length()
                    ? -1 : canonical.indexOf(':', kindPosition + 1);
            int valueLength = inputDescriptorDecimal(canonical, kindPosition + 1, valueLengthEnd);
            int valueStart = valueLengthEnd < 0 ? -1 : valueLengthEnd + 1;
            int valueEnd = valueStart < 0 || valueLength < 0 ? -1 : valueStart + valueLength;
            if (pathEnd < pathStart || valueEnd < valueStart || valueEnd >= canonical.length()
                    || canonical.charAt(valueEnd) != ';') {
                return 'X';
            }
            if (canonical.substring(pathStart, pathEnd).equals(expectedPath)) {
                char kind = canonical.charAt(kindPosition);
                return kind == 'V' || kind == 'O' || kind == 'L' ? kind : 'X';
            }
            position = valueEnd + 1;
        }
        return 'X';
    }

    private static String canonicalInputFieldName(String path) {
        if (path == null || path.length() < 3 || path.charAt(0) != 'f') {
            return "";
        }
        int colon = path.indexOf(':', 1);
        int length = inputDescriptorDecimal(path, 1, colon);
        int start = colon < 0 ? -1 : colon + 1;
        int end = start < 0 || length < 0 ? -1 : start + length;
        return end == path.length() ? path.substring(start, end) : "";
    }

    /**
     * Applies the generated input descriptor to one field argument. Unlike variable-definition
     * coercion, an argument literal may contain variable references at any nested list/object
     * position. Each reference is checked at that exact input location and resolved through the
     * invocation-local materialized-variable carrier before scalar/object validation continues.
     * Success returns the charged node count in an internal {@code iw1} carrier; failure returns
     * either ordinary diagnostic text or the internal resource-limit carrier.
     */
    private static String argumentInputValueResult(
            String query,
            String requestAst,
            String materializedVariables,
            String argumentLabel,
            String typeReference,
            String value,
            String inputDescriptor,
            boolean locationHasDefault,
            int maximumWorkItems
    ) {
        if (maximumWorkItems <= 0) {
            return RESOURCE_LIMIT_FAILURE_PREFIX
                    + argumentLabel + " exceeds the input coercion work budget";
        }
        if (query == null || requestAst == null || argumentLabel == null || argumentLabel.length() == 0
                || DatabaseGraphqlTypeReference.namedType(typeReference).length() == 0
                || !inputDescriptorIsTyped(inputDescriptor)) {
            return argumentLabel + " has invalid input metadata";
        }
        String queue = inputCoercionQueueEntry(value, typeReference, 0, false);
        int queuePosition = 0;
        int workItems = 0;
        while (queuePosition < queue.length()) {
            if (workItems >= maximumWorkItems) {
                return RESOURCE_LIMIT_FAILURE_PREFIX
                        + argumentLabel + " exceeds the input coercion work budget";
            }
            workItems++;
            int valueLengthEnd = queue.indexOf(':', queuePosition);
            int valueLength = inputDescriptorDecimal(queue, queuePosition, valueLengthEnd);
            int valueStart = valueLengthEnd < queuePosition ? -1 : valueLengthEnd + 1;
            int valueEnd = valueStart < 0 || valueLength < 0 ? -1 : valueStart + valueLength;
            int typeLengthEnd = valueEnd < 0 || valueEnd > queue.length() ? -1 : queue.indexOf(':', valueEnd);
            int typeLength = inputDescriptorDecimal(queue, valueEnd, typeLengthEnd);
            int typeStart = typeLengthEnd < valueEnd ? -1 : typeLengthEnd + 1;
            int typeEnd = typeStart < 0 || typeLength < 0 ? -1 : typeStart + typeLength;
            int depthEnd = typeEnd < 0 || typeEnd > queue.length() ? -1 : queue.indexOf(':', typeEnd);
            int depth = inputDescriptorDecimal(queue, typeEnd, depthEnd);
            int originPosition = depthEnd < 0 ? -1 : depthEnd + 1;
            if (valueEnd < valueStart || typeEnd < typeStart || depthEnd < typeEnd || depth < 0
                    || depth > MAX_JSON_NESTING || originPosition < 0 || originPosition + 1 >= queue.length()
                    || queue.charAt(originPosition) != 'G' && queue.charAt(originPosition) != 'J'
                    || queue.charAt(originPosition + 1) != ';') {
                return argumentInputCoercionError(argumentLabel,
                        DatabaseGraphqlTypeReference.namedType(typeReference));
            }
            boolean queuedJsonRepresentation = queue.charAt(originPosition) == 'J';
            queuePosition = originPosition + 2;
            String queuedValue = queue.substring(valueStart, valueEnd);
            String queuedType = queue.substring(typeStart, typeEnd);
            String namedType = DatabaseGraphqlTypeReference.namedType(queuedType);
            if (namedType.length() == 0) {
                return argumentInputCoercionError(argumentLabel,
                        DatabaseGraphqlTypeReference.namedType(typeReference));
            }

            String variableName = inputVariableReferenceName(queuedValue);
            if (variableName.length() != 0) {
                String variableInfo = DatabaseGraphqlAst.variableDefinitionInfo(
                        query, requestAst, variableName);
                String declaredType = DatabaseGraphqlTypeReference.parse(
                        variableDeclaredTypeFromAst(query, variableInfo));
                int defaultStart = astInfoValue(variableInfo, 3);
                int defaultEnd = astInfoValue(variableInfo, 4);
                boolean variableHasNonNullDefault = defaultStart >= 0 && defaultEnd > defaultStart
                        && !argumentValueIsNull(query.substring(defaultStart, defaultEnd));
                boolean useLocationDefault = depth == 0 && locationHasDefault;
                if (declaredType.length() == 0
                        || !DatabaseGraphqlTypeReference.isVariableUsageAllowed(
                        declaredType, queuedType, variableHasNonNullDefault, useLocationDefault)) {
                    String declaredTypeText = DatabaseGraphqlTypeReference.sourceText(declaredType);
                    String queuedTypeText = DatabaseGraphqlTypeReference.sourceText(queuedType);
                    return argumentLabel + " cannot use variable '$" + variableName
                            + "' declared as '" + declaredTypeText + "' at '" + queuedTypeText + "'";
                }
                String resolvedValue = materializedVariableValue(materializedVariables, variableName);
                if (resolvedValue.length() == 0) {
                    if (DatabaseGraphqlTypeReference.isOuterNonNull(queuedType) && !useLocationDefault) {
                        return argumentInputCoercionError(argumentLabel, namedType);
                    }
                    continue;
                }
                // Variable-level validation uses the declaration type. A nullable declaration
                // with a non-null default is allowed at a non-null argument location, but an
                // explicitly supplied JSON null still violates that location. Preserve the
                // variable pass's syntax provenance for non-null enum/scalar values while
                // enforcing the location wrapper here before generated execution begins.
                if (argumentValueIsNull(resolvedValue)) {
                    if (DatabaseGraphqlTypeReference.isOuterNonNull(queuedType)) {
                        return argumentInputCoercionError(argumentLabel, namedType);
                    }
                    continue;
                }
                // The selected-operation variable pass has already validated this value using
                // its actual origin: GraphQL syntax for a default or JSON syntax for a supplied
                // variable. Do not reclassify a JSON enum string as an inline GraphQL string.
                continue;
            }

            if (argumentValueIsNull(queuedValue)) {
                if (DatabaseGraphqlTypeReference.isOuterNonNull(queuedType)) {
                    return argumentInputCoercionError(argumentLabel, namedType);
                }
                continue;
            }
            if (DatabaseGraphqlTypeReference.isOuterList(queuedType)) {
                String itemType = DatabaseGraphqlTypeReference.listItemType(queuedType);
                String tokens = DatabaseGraphqlLanguage.lexicalTokenStream(queuedValue);
                int count = DatabaseGraphqlLanguage.graphqlInputListValueCount(queuedValue, tokens);
                if (itemType.length() == 0) {
                    return argumentInputCoercionError(argumentLabel, namedType);
                }
                // Charge known collection fan-out before appending any child queue entries.
                // MySQL otherwise spends unbounded routine work building a queue for an input
                // that the engine already knows it must reject.
                if (count > maximumWorkItems - workItems) {
                    return RESOURCE_LIMIT_FAILURE_PREFIX
                            + argumentLabel + " exceeds the input coercion work budget";
                }
                if (count < 0) {
                    queue = queue + inputCoercionQueueEntry(
                            queuedValue, itemType, depth + 1, queuedJsonRepresentation);
                } else {
                    int item = 0;
                    while (item < count) {
                        String itemValue = DatabaseGraphqlLanguage.graphqlInputListValue(
                                queuedValue, tokens, item);
                        if (itemValue.length() == 0) {
                            return argumentInputCoercionError(argumentLabel, namedType);
                        }
                        queue = queue + inputCoercionQueueEntry(
                                itemValue, itemType, depth + 1, queuedJsonRepresentation);
                        item++;
                    }
                }
                continue;
            }
            String descriptorEntry = inputDescriptorEntry(inputDescriptor, namedType);
            if (descriptorEntry.length() == 0) {
                return argumentLabel + " uses unsupported input type '" + namedType + "'";
            }
            if (descriptorEntry.charAt(0) == 'O') {
                String allowedFields = inputDescriptorObjectFields(descriptorEntry);
                if (allowedFields.length() == 0 || !inputObjectHasOnlyFields(queuedValue, allowedFields)) {
                    return argumentLabel + " input object has an unknown, duplicate, or malformed field";
                }
                String fields = inputDescriptorEntryPayload(descriptorEntry);
                int fieldStart = 0;
                while (fieldStart < fields.length()) {
                    int fieldEnd = fields.indexOf(',', fieldStart);
                    if (fieldEnd < 0) fieldEnd = fields.length();
                    int equals = fields.indexOf('=', fieldStart);
                    if (equals <= fieldStart || equals >= fieldEnd) {
                        return argumentInputCoercionError(argumentLabel, namedType);
                    }
                    String fieldName = fields.substring(fieldStart, equals);
                    String fieldType = DatabaseGraphqlTypeReference.parse(
                            fields.substring(equals + 1, fieldEnd));
                    if (fieldType.length() == 0) {
                        return argumentInputCoercionError(argumentLabel, namedType);
                    }
                    String fieldValue = inputObjectFieldValue(queuedValue, fieldName);
                    if (fieldValue.length() == 0) {
                        String fieldDefault = inputDescriptorFieldDefault(
                                inputDescriptor, namedType, fieldName);
                        if (fieldDefault.length() != 0) {
                            queue = queue + inputCoercionQueueEntry(fieldDefault, fieldType, depth + 1, false);
                        } else if (DatabaseGraphqlTypeReference.isOuterNonNull(fieldType)) {
                            return argumentInputCoercionError(argumentLabel, namedType);
                        }
                    } else {
                        queue = queue + inputCoercionQueueEntry(
                                fieldValue, fieldType, depth + 1, queuedJsonRepresentation);
                    }
                    fieldStart = fieldEnd + 1;
                }
            } else if (!inputDescriptorScalarValueIsValid(
                    descriptorEntry, queuedValue, queuedJsonRepresentation)) {
                return argumentInputCoercionError(argumentLabel, namedType);
            }
        }
        return INPUT_WORK_SUCCESS_PREFIX + workItems;
    }

    private static String inputVariableReferenceName(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '$') {
            return "";
        }
        int position = 1;
        while (position < value.length()) {
            char current = value.charAt(position);
            boolean valid = current >= 'A' && current <= 'Z'
                    || current >= 'a' && current <= 'z'
                    || current == '_'
                    || position > 1 && current >= '0' && current <= '9';
            if (!valid) {
                return "";
            }
            position++;
        }
        return value.substring(1);
    }

    private static String argumentInputCoercionError(String argumentLabel, String namedType) {
        return argumentLabel + " cannot be coerced to '" + namedType + "'";
    }

    /**
     * Validates a variable default or JSON-supplied value against generated schema metadata.
     *
     * <p>The {@code id1} descriptor is scalar data rather than a JVM schema graph. Entries use
     * {@code B|F|I|L|N|S|U:Name} for scalar types, {@code E:Name:VALUE|...} for enums, and
     * {@code O:Name:field=TypeReference,...} for input objects. The generated package owns the
     * descriptor; this language-core walker owns recursive input semantics. A length-coded work
     * queue keeps the traversal iterative because Titan deliberately rejects recursive routine
     * graphs. Success returns the charged node count in an internal {@code iw1} carrier so the
     * selected operation can enforce one allowance across all variables and defaults.</p>
     */
    private static String variableInputValueResult(
            String variableName,
            String typeReference,
            String value,
            String inputDescriptor,
            boolean jsonRepresentation,
            int maximumWorkItems
    ) {
        if (maximumWorkItems <= 0) {
            return RESOURCE_LIMIT_FAILURE_PREFIX + "variable '$" + variableName
                    + "' exceeds the input coercion work budget";
        }
        if (!inputDescriptorIsTyped(inputDescriptor)) {
            String scalarError = DatabaseGraphqlTypeReference.hasList(typeReference) || argumentValueIsNull(value)
                    ? "" : scalarVariableValueError(variableName,
                    DatabaseGraphqlTypeReference.namedType(typeReference), value);
            return scalarError.length() == 0 ? INPUT_WORK_SUCCESS_PREFIX + "1" : scalarError;
        }
        String queue = inputCoercionQueueEntry(value, typeReference, 0, jsonRepresentation);
        int queuePosition = 0;
        int workItems = 0;
        while (queuePosition < queue.length()) {
            if (workItems >= maximumWorkItems) {
                return RESOURCE_LIMIT_FAILURE_PREFIX + "variable '$" + variableName
                        + "' exceeds the input coercion work budget";
            }
            workItems++;
            int valueLengthEnd = queue.indexOf(':', queuePosition);
            int valueLength = inputDescriptorDecimal(queue, queuePosition, valueLengthEnd);
            int valueStart = valueLengthEnd < queuePosition ? -1 : valueLengthEnd + 1;
            int valueEnd = valueStart < 0 || valueLength < 0 ? -1 : valueStart + valueLength;
            int typeLengthEnd = valueEnd < 0 || valueEnd > queue.length() ? -1 : queue.indexOf(':', valueEnd);
            int typeLength = inputDescriptorDecimal(queue, valueEnd, typeLengthEnd);
            int typeStart = typeLengthEnd < valueEnd ? -1 : typeLengthEnd + 1;
            int typeEnd = typeStart < 0 || typeLength < 0 ? -1 : typeStart + typeLength;
            int depthEnd = typeEnd < 0 || typeEnd > queue.length() ? -1 : queue.indexOf(':', typeEnd);
            int depth = inputDescriptorDecimal(queue, typeEnd, depthEnd);
            int originPosition = depthEnd < 0 ? -1 : depthEnd + 1;
            if (valueEnd < valueStart || typeEnd < typeStart || depthEnd < typeEnd || depth < 0
                    || depth > MAX_JSON_NESTING || originPosition < 0 || originPosition + 1 >= queue.length()
                    || queue.charAt(originPosition) != 'G' && queue.charAt(originPosition) != 'J'
                    || queue.charAt(originPosition + 1) != ';') {
                return inputCoercionError(variableName, DatabaseGraphqlTypeReference.namedType(typeReference));
            }
            boolean queuedJsonRepresentation = queue.charAt(originPosition) == 'J';
            queuePosition = originPosition + 2;
            String queuedValue = queue.substring(valueStart, valueEnd);
            String queuedType = queue.substring(typeStart, typeEnd);
            String namedType = DatabaseGraphqlTypeReference.namedType(queuedType);
            if (namedType.length() == 0) {
                return inputCoercionError(variableName, DatabaseGraphqlTypeReference.namedType(typeReference));
            }
            if (argumentValueIsNull(queuedValue)) {
                if (DatabaseGraphqlTypeReference.isOuterNonNull(queuedType)) {
                    return inputCoercionError(variableName, namedType);
                }
                continue;
            }
            if (DatabaseGraphqlTypeReference.isOuterList(queuedType)) {
                String itemType = DatabaseGraphqlTypeReference.listItemType(queuedType);
                String tokens = DatabaseGraphqlLanguage.lexicalTokenStream(queuedValue);
                int count = DatabaseGraphqlLanguage.graphqlInputListValueCount(queuedValue, tokens);
                if (itemType.length() == 0) {
                    return inputCoercionError(variableName, namedType);
                }
                // Reserve the complete known list fan-out before growing the scalar queue. This
                // makes resource rejection proportional to the bounded source scan, not to the
                // number of child entries that would otherwise be appended and revisited.
                if (count > maximumWorkItems - workItems) {
                    return RESOURCE_LIMIT_FAILURE_PREFIX + "variable '$" + variableName
                            + "' exceeds the input coercion work budget";
                }
                if (count < 0) {
                    queue = queue + inputCoercionQueueEntry(
                            queuedValue, itemType, depth + 1, queuedJsonRepresentation);
                } else {
                    int item = 0;
                    while (item < count) {
                        String itemValue = DatabaseGraphqlLanguage.graphqlInputListValue(queuedValue, tokens, item);
                        if (itemValue.length() == 0) {
                            return inputCoercionError(variableName, namedType);
                        }
                        queue = queue + inputCoercionQueueEntry(
                                itemValue, itemType, depth + 1, queuedJsonRepresentation);
                        item++;
                    }
                }
                continue;
            }
            String descriptorEntry = inputDescriptorEntry(inputDescriptor, namedType);
            if (descriptorEntry.length() == 0) {
                return "variable '$" + variableName + "' declares unsupported input type '" + namedType + "'";
            }
            if (descriptorEntry.charAt(0) == 'O') {
                String allowedFields = inputDescriptorObjectFields(descriptorEntry);
                if (allowedFields.length() == 0 || !inputObjectHasOnlyFields(queuedValue, allowedFields)) {
                    return "variable '$" + variableName
                            + "' input object has an unknown, duplicate, or malformed field";
                }
                String fields = inputDescriptorEntryPayload(descriptorEntry);
                int fieldStart = 0;
                while (fieldStart < fields.length()) {
                    int fieldEnd = fields.indexOf(',', fieldStart);
                    if (fieldEnd < 0) fieldEnd = fields.length();
                    int equals = fields.indexOf('=', fieldStart);
                    if (equals <= fieldStart || equals >= fieldEnd) {
                        return inputCoercionError(variableName, namedType);
                    }
                    String fieldName = fields.substring(fieldStart, equals);
                    String fieldType = DatabaseGraphqlTypeReference.parse(fields.substring(equals + 1, fieldEnd));
                    if (fieldType.length() == 0) {
                        return inputCoercionError(variableName, namedType);
                    }
                    String fieldValue = inputObjectFieldValue(queuedValue, fieldName);
                    if (fieldValue.length() == 0) {
                        String fieldDefault = inputDescriptorFieldDefault(
                                inputDescriptor, namedType, fieldName);
                        if (fieldDefault.length() != 0) {
                            queue = queue + inputCoercionQueueEntry(fieldDefault, fieldType, depth + 1, false);
                        } else if (DatabaseGraphqlTypeReference.isOuterNonNull(fieldType)) {
                            return inputCoercionError(variableName, namedType);
                        }
                    } else {
                        queue = queue + inputCoercionQueueEntry(
                                fieldValue, fieldType, depth + 1, queuedJsonRepresentation);
                    }
                    fieldStart = fieldEnd + 1;
                }
            } else if (!inputDescriptorScalarValueIsValid(
                    descriptorEntry, queuedValue, queuedJsonRepresentation)) {
                return inputCoercionError(variableName, namedType);
            }
        }
        return INPUT_WORK_SUCCESS_PREFIX + workItems;
    }

    private static String inputCoercionQueueEntry(
            String value,
            String type,
            int depth,
            boolean jsonRepresentation
    ) {
        if (value == null || type == null || depth < 0) {
            return "";
        }
        return value.length() + ":" + value + type.length() + ":" + type + depth + ":"
                + (jsonRepresentation ? "J;" : "G;");
    }

    private static String inputCoercionError(String variableName, String namedType) {
        return "variable '$" + variableName + "' cannot be coerced to '" + namedType + "'";
    }

    private static boolean inputDescriptorIsTyped(String descriptor) {
        return descriptor != null && descriptor.startsWith("id1;");
    }

    /** Returns one complete schema entry for a type name in a bounded scalar descriptor. */
    private static String inputDescriptorEntry(String descriptor, String expectedName) {
        if (!inputDescriptorIsTyped(descriptor) || expectedName == null || expectedName.length() == 0) {
            return "";
        }
        int position = 4;
        while (position < descriptor.length()) {
            int end = descriptor.indexOf(';', position);
            int firstColon = descriptor.indexOf(':', position);
            if (end <= position || firstColon != position + 1 || end < firstColon) {
                return "";
            }
            int secondColon = descriptor.indexOf(':', firstColon + 1);
            int nameEnd = secondColon < 0 || secondColon > end ? end : secondColon;
            if (descriptor.substring(position, position + 1).equals("D")) {
                int lengthEnd = secondColon < 0 ? -1 : descriptor.indexOf(':', secondColon + 1);
                int valueLength = inputDescriptorDecimal(descriptor, secondColon + 1, lengthEnd);
                int valueStart = lengthEnd < 0 ? -1 : lengthEnd + 1;
                int valueEnd = valueStart < 0 || valueLength < 0 ? -1 : valueStart + valueLength;
                if (secondColon <= firstColon + 1 || valueEnd < valueStart
                        || valueEnd >= descriptor.length() || descriptor.charAt(valueEnd) != ';') {
                    return "";
                }
                nameEnd = secondColon;
                end = valueEnd;
            }
            if (nameEnd <= firstColon + 1) {
                return "";
            }
            if (descriptor.substring(firstColon + 1, nameEnd).equals(expectedName)) {
                return descriptor.substring(position, end);
            }
            position = end + 1;
        }
        return "";
    }

    private static String inputDescriptorEntryPayload(String entry) {
        int firstColon = entry == null ? -1 : entry.indexOf(':');
        int secondColon = firstColon < 0 ? -1 : entry.indexOf(':', firstColon + 1);
        return secondColon < 0 || secondColon + 1 >= entry.length() ? "" : entry.substring(secondColon + 1);
    }

    /** Returns delimiter-safe GraphQL source for one authored input-field default. */
    private static String inputDescriptorFieldDefault(
            String descriptor,
            String inputObject,
            String fieldName
    ) {
        if (inputObject == null || inputObject.length() == 0
                || fieldName == null || fieldName.length() == 0) {
            return "";
        }
        String entry = inputDescriptorEntry(descriptor, inputObject + "." + fieldName);
        if (entry.length() == 0 || entry.charAt(0) != 'D') {
            return "";
        }
        int first = entry.indexOf(':');
        int second = first < 0 ? -1 : entry.indexOf(':', first + 1);
        int lengthEnd = second < 0 ? -1 : entry.indexOf(':', second + 1);
        int valueLength = inputDescriptorDecimal(entry, second + 1, lengthEnd);
        int valueStart = lengthEnd < 0 ? -1 : lengthEnd + 1;
        int valueEnd = valueStart < 0 || valueLength < 0 ? -1 : valueStart + valueLength;
        return valueEnd == entry.length() ? entry.substring(valueStart, valueEnd) : "";
    }

    private static String inputDescriptorObjectFields(String entry) {
        String fields = inputDescriptorEntryPayload(entry);
        String names = ",";
        int position = 0;
        while (position < fields.length()) {
            int end = fields.indexOf(',', position);
            if (end < 0) end = fields.length();
            int equals = fields.indexOf('=', position);
            if (equals <= position || equals >= end) {
                return "";
            }
            String name = fields.substring(position, equals);
            if (commaSeparatedNameContains(names, name)) {
                return "";
            }
            names = names + name + ",";
            position = end + 1;
        }
        return names.equals(",") ? "" : names;
    }

    private static boolean inputDescriptorScalarValueIsValid(
            String entry,
            String value,
            boolean jsonRepresentation
    ) {
        if (entry == null || entry.length() < 3) {
            return false;
        }
        char kind = entry.charAt(0);
        if (kind == 'E') {
            String values = inputDescriptorEntryPayload(entry);
            String candidate;
            if (jsonRepresentation) {
                candidate = stringArgumentIsValid(value) ? stringArgument(value) : "";
            } else {
                int start = skipIgnored(value, 0, value.length());
                int end = nameEnd(value, start, value.length());
                candidate = end > start && skipIgnored(value, end, value.length()) == value.length()
                        ? value.substring(start, end) : "";
            }
            return candidate.length() != 0 && pipeSeparatedNameContains(values, candidate);
        }
        if (kind == 'B') return booleanArgument(value) >= 0;
        if (kind == 'N') return !invalidIntegerForScalar(longArgument(value), "Int");
        if (kind == 'L') return !invalidLong(longArgument(value));
        if (kind == 'F') return decimalArgumentIsValid(value);
        if (kind == 'S') return stringArgumentIsValid(value);
        if (kind == 'U') return uuidArgumentIsValid(value);
        if (kind == 'I') return stringArgumentIsValid(value) || !invalidLong(longArgument(value));
        return false;
    }

    private static boolean pipeSeparatedNameContains(String values, String expectedName) {
        if (values == null || expectedName == null || expectedName.length() == 0) {
            return false;
        }
        int position = 0;
        while (position <= values.length()) {
            int end = values.indexOf('|', position);
            if (end < 0) end = values.length();
            if (end > position && values.substring(position, end).equals(expectedName)) {
                return true;
            }
            if (end == values.length()) {
                return false;
            }
            position = end + 1;
        }
        return false;
    }

    private static int inputDescriptorDecimal(String value, int start, int end) {
        if (value == null || start < 0 || end <= start || end > value.length()) {
            return -1;
        }
        int result = 0;
        int position = start;
        while (position < end) {
            char current = value.charAt(position);
            if (current < '0' || current > '9' || result > 100000000) {
                return -1;
            }
            result = result * 10 + current - '0';
            position++;
        }
        return result;
    }

    /** Empty descriptors retain compatibility for callers that do not yet have generated metadata. */
    private static boolean variableDeclaredTypeIsAllowedInput(String namedType, String allowedInputTypeNames) {
        if (inputDescriptorIsTyped(allowedInputTypeNames)) {
            return inputDescriptorEntry(allowedInputTypeNames, namedType).length() != 0;
        }
        return allowedInputTypeNames == null || allowedInputTypeNames.length() == 0
                || namedType.length() != 0 && commaSeparatedNameContains(allowedInputTypeNames, namedType);
    }

    /**
     * Returns whether an argument value is GraphQL/JSON {@code null}. Generated descriptors use
     * this only for optional arguments: omission and explicit null both leave the argument's
     * descriptor default in effect. Required arguments still perform their generated non-null
     * validation, and the preflight rejects a null supplied for a non-null variable declaration.
     */
    public static boolean argumentValueIsNull(String value) {
        return "null".equals(value);
    }

    /** Creates the bounded lexical carrier that generated input bindings may reuse locally. */
    public static String inputValueTokens(String value) {
        return DatabaseGraphqlLanguage.lexicalTokenStream(value);
    }

    /**
     * Enforces the selected-operation variable envelope before generated root code sees a value.
     * A Java/Jackson transport decoder is not an authority here: direct database callers receive
     * the same unknown/duplicate-variable rejection as requests arriving through HTTP.
     */
    private static String suppliedVariableError(String variablesJson, String declared) {
        if (variablesJson == null || variablesJson.length() == 0) {
            return "";
        }
        int start = skipJsonWhitespace(variablesJson, 0, variablesJson.length());
        int end = strictJsonValueEnd(variablesJson, start, variablesJson.length());
        if (start >= variablesJson.length() || variablesJson.charAt(start) != '{' || end < 0) {
            return "";
        }
        String supplied = ",";
        int position = start + 1;
        while (position < end - 1) {
            position = skipJsonWhitespace(variablesJson, position, end);
            if (position >= end - 1) {
                break;
            }
            int nameEnd = strictJsonStringEnd(variablesJson, position, end);
            if (nameEnd < 0) {
                return "";
            }
            String name = jsonStringValue(variablesJson.substring(position, nameEnd));
            if (!commaSeparatedNameContains(declared, name)) {
                return "variable '$" + name + "' is not declared by the selected operation";
            }
            if (commaSeparatedNameContains(supplied, name)) {
                return "variables JSON supplies variable '$" + name + "' more than once";
            }
            supplied = supplied + name + ",";
            int colon = skipJsonWhitespace(variablesJson, nameEnd, end);
            if (colon >= end || variablesJson.charAt(colon) != ':') {
                return "";
            }
            int valueStart = skipJsonWhitespace(variablesJson, colon + 1, end);
            int valueEnd = strictJsonValueEnd(variablesJson, valueStart, end);
            if (valueEnd < 0) {
                return "";
            }
            position = skipJsonWhitespace(variablesJson, valueEnd, end);
            if (position < end - 1 && variablesJson.charAt(position) == ',') {
                position++;
            }
        }
        return "";
    }

    /**
     * Returns the first name in the comma-delimited descriptor absent from {@code candidates}.
     * Both values deliberately use leading/trailing commas so membership checks remain exact in
     * the transpilable scalar representation used by the bounded parser.
     */
    private static String firstCommaSeparatedNameMissingFrom(String names, String candidates) {
        int position = 0;
        while (position < names.length()) {
            int start = position;
            while (start < names.length() && names.charAt(start) == ',') {
                start++;
            }
            if (start >= names.length()) {
                return "";
            }
            int end = start;
            while (end < names.length() && names.charAt(end) != ',') {
                end++;
            }
            String name = names.substring(start, end);
            if (!commaSeparatedNameContains(candidates, name)) {
                return name;
            }
            position = end + 1;
        }
        return "";
    }

    /** Returns one field argument from the parsed language plan without applying variables. */
    private static String argumentLiteral(String query, String languagePlan, int fieldStart, String argumentName) {
        String range = DatabaseGraphqlLanguage.fieldArgumentValueRange(query, languagePlan, fieldStart, argumentName);
        int start = DatabaseGraphqlLanguage.rangeStart(range);
        int end = DatabaseGraphqlLanguage.rangeEnd(range);
        return start < 0 || end <= start || end > query.length() ? "" : query.substring(start, end);
    }

    /** Reads one field argument range retained by the typed AST. */
    private static String argumentLiteralFromAst(String query, String requestAst, int fieldStart, String argumentName) {
        String range = DatabaseGraphqlAst.fieldArgumentValueRange(query, requestAst, fieldStart, argumentName);
        int start = astInfoValue(range, 0);
        int end = astInfoValue(range, 1);
        return start < 0 || end <= start || end > query.length() ? "" : query.substring(start, end);
    }

    /** Reads the normalized parsed type range retained by the language plan. */
    private static String variableDeclaredType(String query, String languagePlan, int definitionStart) {
        int typeStart = DatabaseGraphqlLanguage.variableTypeStart(languagePlan, definitionStart);
        int typeEnd = DatabaseGraphqlLanguage.variableTypeEnd(languagePlan, definitionStart);
        return typeEnd <= typeStart || typeEnd > query.length()
                ? "" : DatabaseGraphqlLanguage.tokenRangeText(query, languagePlan, typeStart, typeEnd);
    }

    /** Reads a variable type range retained in an AST variable definition. */
    private static String variableDeclaredTypeFromAst(String query, String variableInfo) {
        int typeStart = astInfoValue(variableInfo, 1);
        int typeEnd = astInfoValue(variableInfo, 2);
        return typeStart < 0 || typeEnd <= typeStart || typeEnd > query.length()
                ? "" : query.substring(typeStart, typeEnd);
    }

    /** Reads a numeric or {@code n} slot from a compact typed-AST range carrier. */
    private static int astInfoValue(String info, int slot) {
        if (info == null || slot < 0) {
            return -1;
        }
        int start = 0;
        int index = 0;
        while (start <= info.length()) {
            int end = info.indexOf(':', start);
            if (end < 0) {
                end = info.length();
            }
            if (index == slot) {
                String value = info.substring(start, end);
                if (value.length() == 0 || value.equals("n")) {
                    return -1;
                }
                int parsed = 0;
                int position = 0;
                while (position < value.length()) {
                    char current = value.charAt(position);
                    if (current < '0' || current > '9' || parsed > 214748364) {
                        return -1;
                    }
                    int digit = current - '0';
                    if (parsed == 214748364 && digit > 7) {
                        return -1;
                    }
                    parsed = parsed * 10 + digit;
                    position++;
                }
                return parsed;
            }
            if (end == info.length()) {
                return -1;
            }
            start = end + 1;
            index++;
        }
        return -1;
    }

    /** Skips syntactically well-formed field directives without evaluating their conditions. */
    private static int skipDirectives(String query, int position, int limit) {
        position = skipIgnored(query, position, limit);
        while (position < limit && query.charAt(position) == '@') {
            position = skipIgnored(query, position + 1, limit);
            if (position >= limit || !isNameStart(query.charAt(position))) {
                return -1;
            }
            position = skipIgnored(query, nameEnd(query, position, limit), limit);
            if (position < limit && query.charAt(position) == '(') {
                int end = matchingEnd(query, position, '(', ')');
                if (end < 0) {
                    return -1;
                }
                position = skipIgnored(query, end + 1, limit);
            }
        }
        return position;
    }

    /**
     * Evaluates GraphQL's built-in field directives with a database-resident Boolean variable
     * lookup. Returns 1 to execute, 0 to omit, and -1 for malformed/unsupported directives.
     */
    private static int fieldInclusion(String query, int fieldStart, String variablesJson) {
        return fieldInclusion(query, documentPlan(query), fieldStart, variablesJson);
    }

    private static int fieldInclusion(String query, String languagePlan, int fieldStart, String variablesJson) {
        int position = DatabaseGraphqlLanguage.fieldHeaderEnd(query, languagePlan, fieldStart);
        if (position < 0) {
            return -1;
        }
        return directiveInclusion(query, languagePlan, position, variablesJson);
    }

    /** Evaluates field directives from the typed node's retained header boundary. */
    private static int fieldInclusionFromAst(
            String query,
            String ast,
            int fieldStart,
            String variablesJson
    ) {
        int position = fieldInfoValue(ast, fieldStart, 0);
        return position < 0 ? -1 : directiveInclusionFromAst(query, ast, position, variablesJson, "F");
    }

    /** Evaluates directives beginning at a parsed field/fragment header. */
    private static int directiveInclusion(String query, String languagePlan, int position, String variablesJson) {
        int limit = query.length();
        int included = 1;
        position = DatabaseGraphqlLanguage.nextTokenStartAtOrAfter(languagePlan, position);
        while (position >= 0 && position < limit) {
            String directive = DatabaseGraphqlLanguage.directiveName(query, languagePlan, position);
            if (directive.length() == 0) {
                return DatabaseGraphqlLanguage.tokenIsPunctuationAt(query, languagePlan, position, '@') ? -1 : included;
            }
            int directiveEnd = DatabaseGraphqlLanguage.directiveEnd(languagePlan, position);
            if (directive.length() == 0 || directiveEnd <= position || directiveEnd > limit) {
                return -1;
            }
            if (variablesJson == null) {
                position = DatabaseGraphqlLanguage.nextTokenStartAtOrAfter(languagePlan, directiveEnd);
                continue;
            }
            if (!directive.equals("skip") && !directive.equals("include")) {
                return -1;
            }
            if (DatabaseGraphqlLanguage.nodeArgumentCount(languagePlan, position) != 1
                    || !DatabaseGraphqlLanguage.nodeHasOnlyArguments(query, languagePlan, position, ",if,")) {
                return -1;
            }
            String range = DatabaseGraphqlLanguage.fieldArgumentValueRange(query, languagePlan, position, "if");
            int conditionStart = DatabaseGraphqlLanguage.rangeStart(range);
            int conditionEnd = DatabaseGraphqlLanguage.rangeEnd(range);
            String condition = conditionStart < 0 || conditionEnd <= conditionStart || conditionEnd > query.length()
                    ? "" : query.substring(conditionStart, conditionEnd);
            int booleanValue = directiveBooleanValue(query, languagePlan, condition, variablesJson);
            if (booleanValue < 0) {
                return -1;
            }
            if ((directive.equals("skip") && booleanValue == 1)
                    || (directive.equals("include") && booleanValue == 0)) {
                included = 0;
            }
            position = DatabaseGraphqlLanguage.nextTokenStartAtOrAfter(languagePlan, directiveEnd);
        }
        return included;
    }

    /**
     * Evaluates contiguous directives retained in the typed request AST.  The AST owns directive
     * identity/end ranges and its argument nodes, while the narrow ignored-text check only proves
     * that the next retained directive belongs to this header rather than a later executable node.
     */
    /** Evaluates the directives attached to one already-parsed executable selection. */
    public static int directiveInclusionFromAst(String query, String ast, int position, String variablesJson) {
        return directiveInclusionFromAst(query, ast, position, variablesJson, "");
    }

    /** Evaluates built-in and generated registered directives at one executable location. */
    private static int directiveInclusionFromAst(
            String query,
            String ast,
            int position,
            String variablesJson,
            String executableLocation
    ) {
        int included = 1;
        String seen = ",";
        String info = DatabaseGraphqlAst.directiveInfoAtOrAfter(query, ast, position);
        while (info.length() != 0) {
            int directiveStart = astInfoValue(info, 0);
            int directiveEnd = astInfoValue(info, 1);
            int nameStart = astInfoValue(info, 2);
            int nameEnd = astInfoValue(info, 3);
            if (directiveStart < position || directiveEnd <= directiveStart || nameStart <= directiveStart
                    || nameEnd <= nameStart || directiveEnd > query.length() || nameEnd > directiveEnd) {
                return -1;
            }
            if (variablesJson != null) {
                String directive = query.substring(nameStart, nameEnd);
                String behavior = directive.equals("skip") ? "S" : directive.equals("include") ? "I"
                        : executableDirectiveBehavior(ast, directive, executableLocation);
                if (behavior.length() != 1 || behavior.equals("-")
                        || commaSeparatedNameContains(seen, directive)
                        || DatabaseGraphqlAst.fieldArgumentCount(ast, directiveStart) != 1
                        || !fieldHasOnlyArgumentsFromAst(query, ast, directiveStart, ",if,")) {
                    return -(directiveStart + 2);
                }
                seen = seen + directive + ",";
                int booleanValue = directiveBooleanValueFromAst(
                        query, ast, argumentLiteralFromAst(query, ast, directiveStart, "if"), variablesJson);
                if (booleanValue < 0) {
                    return -(directiveStart + 2);
                }
                if ((behavior.equals("S") && booleanValue == 1)
                        || (behavior.equals("I") && booleanValue == 0)) {
                    included = 0;
                }
            }
            position = directiveEnd;
            info = DatabaseGraphqlAst.directiveInfoAtOrAfter(query, ast, position);
        }
        return included;
    }

    /** Looks up one generated conditional directive's behavior and validates its location. */
    private static String executableDirectiveBehavior(
            String ast,
            String expectedName,
            String executableLocation
    ) {
        String descriptor = DatabaseGraphqlAst.executableDirectiveDescriptor(ast);
        if (descriptor.length() == 0 || !descriptor.startsWith("ed1|")
                || expectedName == null || expectedName.length() == 0) {
            return "";
        }
        int position = 4;
        while (position < descriptor.length()) {
            int nameLengthEnd = descriptor.indexOf(':', position);
            int nameLength = inputDescriptorDecimal(descriptor, position, nameLengthEnd);
            int nameStart = nameLengthEnd + 1;
            int nameEnd = nameLength < 0 ? -1 : nameStart + nameLength;
            int behaviorSeparator = nameEnd < 0 || nameEnd >= descriptor.length()
                    || descriptor.charAt(nameEnd) != ':' ? -1 : nameEnd;
            int behaviorPosition = behaviorSeparator < 0 ? -1 : behaviorSeparator + 1;
            int locationSeparator = behaviorPosition < 0 || behaviorPosition + 1 >= descriptor.length()
                    || descriptor.charAt(behaviorPosition + 1) != ':' ? -1 : behaviorPosition + 1;
            int locationsStart = locationSeparator < 0 ? -1 : locationSeparator + 1;
            int recordEnd = locationsStart < 0 ? -1 : descriptor.indexOf('|', locationsStart);
            if (nameLengthEnd < position || nameLength < 1 || nameEnd > descriptor.length()
                    || behaviorPosition < 0 || recordEnd < locationsStart
                    || (descriptor.charAt(behaviorPosition) != 'I'
                    && descriptor.charAt(behaviorPosition) != 'S')) {
                return "-";
            }
            if (descriptor.substring(nameStart, nameEnd).equals(expectedName)) {
                String locations = descriptor.substring(locationsStart, recordEnd);
                if (executableLocation.length() != 0 && locations.indexOf(executableLocation) < 0) {
                    return "-";
                }
                return descriptor.substring(behaviorPosition, behaviorPosition + 1);
            }
            position = recordEnd + 1;
        }
        return "";
    }

    /**
     * Validates directives on every retained executable node without applying an ancestor's
     * inclusion result. GraphQL validates the complete selected operation, so an invalid child
     * field, named-spread, or inline-fragment directive cannot hide below a skipped parent.
     *
     * <p>The typed AST contains only the selected operation and its reachable fragment closure.
     * Scanning its executable node records therefore covers exactly the operation being validated
     * without expanding a fragment once per spread. Definition directives are rejected by the
     * preceding location checks.</p>
     */
    private static int invalidExecutableDirectiveStartFromAst(
            String query,
            String ast,
            String variablesJson
    ) {
        int nodeCount = DatabaseGraphqlAst.nodeCount(ast);
        if (query == null || nodeCount < 0) {
            return 0;
        }
        int ordinal = 0;
        while (ordinal < nodeCount) {
            String kind = DatabaseGraphqlAst.nodeKind(ast, ordinal);
            if (kind.equals("f") || kind.equals("p") || kind.equals("i")) {
                int sourceStart = DatabaseGraphqlAst.nodeSourceStart(ast, ordinal);
                String payload = DatabaseGraphqlAst.nodePayload(ast, ordinal);
                int directivesStart = kind.equals("f")
                        ? fieldInfoValue(ast, sourceStart, 0)
                        : astInfoValue(payload, 2);
                if (sourceStart < 0 || directivesStart < sourceStart
                        || directivesStart > query.length()) {
                    return sourceStart < 0 ? 0 : sourceStart;
                }
                String executableLocation = kind.equals("f") ? "F" : kind.equals("p") ? "P" : "I";
                int inclusion = directiveInclusionFromAst(
                        query, ast, directivesStart, variablesJson, executableLocation);
                if (inclusion < 0) {
                    if (inclusion <= -2) {
                        int encodedStart = -inclusion - 2;
                        if (encodedStart >= 0 && encodedStart < query.length()) {
                            return encodedStart;
                        }
                    }
                    String directiveInfo = DatabaseGraphqlAst.directiveInfoAtOrAfter(
                            query, ast, directivesStart);
                    int retainedStart = astInfoValue(directiveInfo, 0);
                    return retainedStart >= 0 ? retainedStart : sourceStart;
                }
            }
            ordinal++;
        }
        return -1;
    }

    private static int selectionInclusion(String query, int fieldStart, String variablesJson) {
        return selectionInclusion(query, documentPlan(query), fieldStart, variablesJson);
    }

    private static int selectionInclusion(String query, String languagePlan, int fieldStart, String variablesJson) {
        return variablesJson == null ? fieldInclusion(query, languagePlan, fieldStart, null)
                : fieldInclusion(query, languagePlan, fieldStart, variablesJson);
    }

    private static int selectionInclusionFromAst(
            String query,
            String ast,
            int fieldStart,
            String variablesJson
    ) {
        return variablesJson == null ? fieldInclusionFromAst(query, ast, fieldStart, null)
                : fieldInclusionFromAst(query, ast, fieldStart, variablesJson);
    }

    /** Resolves a Boolean literal or a declared `Boolean!` variable for a built-in directive. */
    private static int directiveBooleanValue(String query, String languagePlan, String value, String variablesJson) {
        if (value == null || value.length() == 0) {
            return -1;
        }
        if (value.charAt(0) == '$') {
            String variableName = value.substring(1, value.length());
            int definition = DatabaseGraphqlLanguage.variableDefinitionStart(query, languagePlan, variableName);
            String declaredType = definition < 0 ? "" : DatabaseGraphqlTypeReference.parse(
                    variableDeclaredType(query, languagePlan, definition));
            int defaultStart = definition < 0 ? -1
                    : DatabaseGraphqlLanguage.variableDefaultValueStart(languagePlan, definition);
            int defaultEnd = definition < 0 ? -1
                    : DatabaseGraphqlLanguage.variableDefaultValueEnd(languagePlan, definition);
            boolean variableHasNonNullDefault = defaultStart >= 0 && defaultEnd > defaultStart
                    && !argumentValueIsNull(query.substring(defaultStart, defaultEnd));
            // `if` is a Boolean! input location.  Apply the same structural variable-use rule
            // used by generated field bindings: a nullable Boolean is legal when its declaration
            // supplies a non-null default, while an explicit JSON null still fails below when its
            // materialized value is read.  Requiring textual `Boolean!` here rejected a valid
            // GraphQL declaration shape only for directives.
            if (!DatabaseGraphqlTypeReference.isVariableUsageAllowed(
                    declaredType, DatabaseGraphqlTypeReference.parse("Boolean!"),
                    variableHasNonNullDefault, false)) {
                return -1;
            }
            String supplied = jsonObjectFieldValue(variablesJson, variableName);
            if (supplied.length() == 0) {
                if (defaultStart < 0) {
                    return -1;
                }
                supplied = defaultEnd <= defaultStart ? "" : query.substring(defaultStart, defaultEnd);
            }
            value = supplied;
        }
        if (value.equals("true")) {
            return 1;
        }
        return value.equals("false") ? 0 : -1;
    }

    /** Resolves a Boolean directive literal or declared variable from the typed request AST. */
    private static int directiveBooleanValueFromAst(String query, String ast, String value, String variablesJson) {
        if (value == null || value.length() == 0) {
            return -1;
        }
        if (value.charAt(0) == '$') {
            String variableName = value.substring(1);
            String variableInfo = DatabaseGraphqlAst.variableDefinitionInfo(query, ast, variableName);
            String declaredType = DatabaseGraphqlTypeReference.parse(variableDeclaredTypeFromAst(query, variableInfo));
            int defaultStart = astInfoValue(variableInfo, 3);
            int defaultEnd = astInfoValue(variableInfo, 4);
            boolean variableHasNonNullDefault = defaultStart >= 0 && defaultEnd > defaultStart
                    && !argumentValueIsNull(query.substring(defaultStart, defaultEnd));
            if (!DatabaseGraphqlTypeReference.isVariableUsageAllowed(
                    declaredType, DatabaseGraphqlTypeReference.parse("Boolean!"),
                    variableHasNonNullDefault, false)) {
                return -1;
            }
            String supplied = jsonObjectFieldValue(variablesJson, variableName);
            if (supplied.length() == 0) {
                supplied = defaultStart < 0 || defaultEnd <= defaultStart ? ""
                        : query.substring(defaultStart, defaultEnd);
            }
            value = supplied;
        }
        if (value.equals("true")) {
            return 1;
        }
        return value.equals("false") ? 0 : -1;
    }

    private static int selectionFieldCountAt(String query, int selectionStart) {
        int end = selectionEnd(query, selectionStart);
        if (end < 0) {
            return 0;
        }
        int count = 0;
        int position = selectionStart + 1;
        while (position < end) {
            position = skipIgnored(query, position, end);
            if (position >= end) {
                break;
            }
            if (isNameStart(query.charAt(position))) {
                count++;
                position = skipField(query, nameEnd(query, position, end), end);
            } else {
                position++;
            }
        }
        return count;
    }

    private static int selectionFieldCountAt(String query, int selectionStart, String variablesJson) {
        int end = selectionEnd(query, selectionStart);
        if (end < 0) {
            return 0;
        }
        int count = 0;
        int position = selectionStart + 1;
        while (position < end) {
            position = skipIgnored(query, position, end);
            if (position >= end) {
                break;
            }
            if (isNameStart(query.charAt(position))) {
                int inclusion = fieldInclusion(query, position, variablesJson);
                if (inclusion < 0) {
                    return -1;
                }
                if (inclusion > 0) {
                    count++;
                }
                position = skipField(query, nameEnd(query, position, end), end);
            } else {
                position++;
            }
        }
        return count;
    }

    private static int skipField(String query, int position, int limit) {
        position = skipIgnored(query, position, limit);
        if (position < limit && query.charAt(position) == ':') {
            position = skipIgnored(query, position + 1, limit);
            position = nameEnd(query, position, limit);
        }
        position = skipIgnored(query, position, limit);
        if (position < limit && query.charAt(position) == '(') {
            int end = matchingEnd(query, position, '(', ')');
            position = end < 0 ? limit : end + 1;
        }
        position = skipDirectives(query, position, limit);
        if (position < 0) {
            return limit;
        }
        if (position < limit && query.charAt(position) == '{') {
            int end = selectionEnd(query, position);
            position = end < 0 ? limit : end + 1;
        }
        return position;
    }

    private static int skipArgument(String query, int position, int limit) {
        int nameEnd = nameEnd(query, position, limit);
        int colon = skipIgnored(query, nameEnd, limit);
        if (colon >= limit || query.charAt(colon) != ':') return limit;
        int valueStart = skipIgnored(query, colon + 1, limit);
        int valueEnd = valueEnd(query, valueStart, limit);
        int next = skipIgnored(query, valueEnd, limit);
        return next < limit && query.charAt(next) == ',' ? next + 1 : next;
    }

    private static int valueEnd(String source, int position, int limit) {
        if (position >= limit) return position;
        char current = source.charAt(position);
        if (current == '"') {
            // GraphQL values may use a normal or triple-quoted block string. The request's
            // lexical gate has already validated it; use the GraphQL scanner here rather than
            // the JSON-only helper so operation defaults and field arguments retain the full
            // literal range for downstream scalar coercion.
            return stringEnd(source, position, limit);
        }
        if (current == '{') {
            int end = matchingEnd(source, position, '{', '}');
            return end < 0 ? limit : end + 1;
        }
        if (current == '[') {
            int end = matchingEnd(source, position, '[', ']');
            return end < 0 ? limit : end + 1;
        }
        while (position < limit) {
            current = source.charAt(position);
            if (isIgnored(current) || current == ',' || current == ')' || current == '}') break;
            position++;
        }
        return position;
    }

    private static int selectionEnd(String query, int selectionStart) {
        return matchingEnd(query, selectionStart, '{', '}');
    }

    private static int matchingEnd(String source, int start, char open, char close) {
        int limit = source.length();
        int depth = 0;
        int position = start;
        while (position < limit) {
            char current = source.charAt(position);
            if (current == '"') {
                position = stringEnd(source, position, limit);
                continue;
            }
            if (current == '#') {
                while (position < limit && source.charAt(position) != '\n') {
                    position++;
                }
                continue;
            }
            if (current == open) depth++;
            if (current == close) {
                depth--;
                if (depth == 0) return position;
            }
            position++;
        }
        return -1;
    }

    /** Skips a normal or triple-quoted GraphQL string without interpreting its contents as syntax. */
    private static int stringEnd(String source, int start, int limit) {
        boolean block = start + 2 < limit && source.charAt(start + 1) == '"' && source.charAt(start + 2) == '"';
        int position = start + (block ? 3 : 1);
        while (position < limit) {
            if (!block && source.charAt(position) == '\\') {
                position = position + 2;
                continue;
            }
            // A block string may contain an escaped triple quote (\"\"\"). It is content, not
            // the closing delimiter; without this branch a scanner truncates the token and lets
            // the remaining quotes corrupt operation/default/selection traversal.
            if (block && source.charAt(position) == '\\' && position + 3 < limit
                    && source.charAt(position + 1) == '"' && source.charAt(position + 2) == '"'
                    && source.charAt(position + 3) == '"') {
                position = position + 4;
                continue;
            }
            if (source.charAt(position) == '"') {
                if (!block) {
                    return position + 1;
                }
                if (position + 2 < limit && source.charAt(position + 1) == '"'
                        && source.charAt(position + 2) == '"') {
                    return position + 3;
                }
            }
            position++;
        }
        return limit;
    }

    private static int jsonStringEnd(String source, int start, int limit) {
        int position = start + 1;
        while (position < limit) {
            if (source.charAt(position) == '\\') {
                position += 2;
            } else if (source.charAt(position) == '"') {
                return position + 1;
            } else {
                position++;
            }
        }
        return -1;
    }

    private static int skipIgnored(String source, int position, int limit) {
        while (position < limit) {
            char current = source.charAt(position);
            if (isIgnored(current) || current == ',') {
                position++;
            } else if (current == '#') {
                while (position < limit && source.charAt(position) != '\n') position++;
            } else {
                return position;
            }
        }
        return position;
    }

    private static boolean isIgnored(char current) {
        return current == ' ' || current == '\n' || current == '\r' || current == '\t' || current == '\ufeff';
    }

    private static boolean commaSeparatedNameContains(String names, String expected) {
        if (names == null || names.length() == 0 || expected == null || expected.length() == 0) {
            return false;
        }
        String wanted = "," + expected + ",";
        String padded = names.charAt(0) == ',' ? names : "," + names + ",";
        int position = 0;
        int limit = padded.length() - wanted.length();
        while (position <= limit) {
            if (padded.substring(position, position + wanted.length()).equals(wanted)) {
                return true;
            }
            position++;
        }
        return false;
    }

    private static boolean isNameStart(char current) {
        return current == '_' || current >= 'A' && current <= 'Z' || current >= 'a' && current <= 'z';
    }

    private static boolean isNamePart(char current) {
        return isNameStart(current) || current >= '0' && current <= '9';
    }

    private static int nameEnd(String source, int start, int limit) {
        int position = start;
        while (position < limit && isNamePart(source.charAt(position))) position++;
        return position;
    }

    private static boolean isNameAt(String source, String expected, int start, int limit) {
        if (start < 0 || start + expected.length() > limit) return false;
        if (source.substring(start, start + expected.length()).equals(expected) == false) return false;
        return start + expected.length() >= limit || isNamePart(source.charAt(start + expected.length())) == false;
    }

    private static boolean startsWith(String source, String prefix) {
        return source != null && source.length() >= prefix.length()
                && source.substring(0, prefix.length()).equals(prefix);
    }

}
