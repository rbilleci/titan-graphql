package io.titan.graphql;

import io.titan.graphql.management.TitanGraphqlOperationRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

final class GraphqlOperationRegistryEnforcement {

    private static final String WARNING_CODE = "OPERATION_REGISTRY_WARNING";
    private static final String REJECTION_CODE = "OPERATION_REGISTRY_REJECTED";

    private GraphqlOperationRegistryEnforcement() {
    }

    // Renamed from Mode under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    enum EnforcementMode {
        OBSERVE,
        WARN,
        ENFORCE
    }

    // Renamed from Status under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    enum EnforcementStatus {
        APPROVED,
        REJECTED,
        UNKNOWN
    }

    record Decision(
            EnforcementMode mode,
            EnforcementStatus status,
            boolean allowed,
            boolean warning,
            String message,
            String operationHash
    ) {
        String errorJson() {
            return "{\"errors\":[{\"message\":\""
                    + escape(message)
                    + "\",\"extensions\":{\"code\":\""
                    + REJECTION_CODE
                    + "\",\"operationRegistry\":{\"mode\":\""
                    + mode
                    + "\",\"status\":\""
                    + status
                    + "\",\"operationHash\":\""
                    + escape(operationHash)
                    + "\"}}}]}";
        }

        String applyWarning(String json) {
            if (warning == false || json == null || json.endsWith("}") == false) {
                return json;
            }
            String warningJson = "\"extensions\":{\"warnings\":[{\"message\":\""
                    + escape(message)
                    + "\",\"extensions\":{\"code\":\""
                    + WARNING_CODE
                    + "\",\"operationRegistry\":{\"mode\":\""
                    + mode
                    + "\",\"status\":\""
                    + status
                    + "\",\"operationHash\":\""
                    + escape(operationHash)
                    + "\"}}}]}";
            if (json.equals("{}")) {
                return "{" + warningJson + "}";
            }
            return json.substring(0, json.length() - 1) + "," + warningJson + "}";
        }
    }

    static Decision evaluate(
            TitanGraphqlOperationRegistry registry,
            GraphqlRequest request,
            GraphqlRequestContext context
    ) {
        String document = request == null ? "" : request.query();
        String operationHash = operationHash(document);
        if (registry == null) {
            return allow(EnforcementMode.OBSERVE, EnforcementStatus.UNKNOWN, operationHash);
        }

        EnforcementMode mode = enforcementMode(registry.mode());
        TitanGraphqlOperationRegistry.RegisteredOperation operation = matchingOperation(registry, request, context, operationHash);
        EnforcementStatus status = status(operation);
        if (status == EnforcementStatus.APPROVED || mode == EnforcementMode.OBSERVE) {
            return allow(mode, status, operationHash);
        }
        String message = message(mode, status, operationHash);
        if (mode == EnforcementMode.WARN) {
            return new Decision(mode, status, true, true, message, operationHash);
        }
        return new Decision(mode, status, false, false, message, operationHash);
    }

    static String operationHash(String document) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((document == null ? "" : document).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static Decision allow(EnforcementMode mode, EnforcementStatus status, String operationHash) {
        return new Decision(mode, status, true, false, "", operationHash);
    }

    private static EnforcementMode enforcementMode(TitanGraphqlOperationRegistry.RegistryMode registryMode) {
        if (registryMode == TitanGraphqlOperationRegistry.RegistryMode.WARN) {
            return EnforcementMode.WARN;
        }
        if (registryMode == TitanGraphqlOperationRegistry.RegistryMode.ENFORCE) {
            return EnforcementMode.ENFORCE;
        }
        return EnforcementMode.OBSERVE;
    }

    private static TitanGraphqlOperationRegistry.RegisteredOperation matchingOperation(
            TitanGraphqlOperationRegistry registry,
            GraphqlRequest request,
            GraphqlRequestContext context,
            String operationHash
    ) {
        String operationName = request == null ? "" : text(request.operationName());
        String document = request == null ? "" : text(request.query());
        String actorRole = context == null ? "" : text(context.actorRole());
        String client = client(request);
        for (TitanGraphqlOperationRegistry.RegisteredOperation operation : registry.operations()) {
            if (matchesDocument(operation, operationHash, document) == false) {
                continue;
            }
            if (operationName.isEmpty() == false
                    && operation.operationName().isEmpty() == false
                    && operation.operationName().equals(operationName) == false) {
                continue;
            }
            if (matches(operation.roles(), actorRole) && matches(operation.clients(), client)) {
                return operation;
            }
        }
        return null;
    }

    private static boolean matchesDocument(
            TitanGraphqlOperationRegistry.RegisteredOperation operation,
            String operationHash,
            String document
    ) {
        return operation.operationHash().equals(operationHash) || operation.document().equals(document);
    }

    private static boolean matches(List<String> allowed, String value) {
        return allowed.isEmpty() || (value.isEmpty() == false && allowed.contains(value));
    }

    private static String client(GraphqlRequest request) {
        if (request == null) {
            return "";
        }
        Map<String, Object> extensions = request.extensions();
        Object client = extensions.get("client");
        if (client == null) {
            client = extensions.get("clientId");
        }
        if (client == null) {
            client = extensions.get("titanClient");
        }
        return client instanceof String string ? string.trim() : "";
    }

    private static EnforcementStatus status(TitanGraphqlOperationRegistry.RegisteredOperation operation) {
        if (operation == null) {
            return EnforcementStatus.UNKNOWN;
        }
        if (operation.status() == TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED) {
            return EnforcementStatus.APPROVED;
        }
        if (operation.status() == TitanGraphqlOperationRegistry.RegisteredOperationStatus.REJECTED
                || operation.status() == TitanGraphqlOperationRegistry.RegisteredOperationStatus.DEPRECATED) {
            return EnforcementStatus.REJECTED;
        }
        return EnforcementStatus.UNKNOWN;
    }

    private static String message(EnforcementMode mode, EnforcementStatus status, String operationHash) {
        String statusText = status == EnforcementStatus.REJECTED ? "rejected" : "unknown";
        if (mode == EnforcementMode.WARN) {
            return "operation registry warning: " + statusText + " operation '" + operationHash + "'";
        }
        return "operation registry rejected " + statusText + " operation '" + operationHash + "'";
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                escaped.append('\\').append(c);
            } else if (c == '\n') {
                escaped.append("\\n");
            } else if (c == '\r') {
                escaped.append("\\r");
            } else if (c == '\t') {
                escaped.append("\\t");
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
