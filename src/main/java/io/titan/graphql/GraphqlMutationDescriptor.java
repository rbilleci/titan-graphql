package io.titan.graphql;

import java.util.List;

public final class GraphqlMutationDescriptor {

    public enum TransactionMode {
        NONE,
        REQUIRED
    }

    public enum AuditMode {
        NONE,
        ATTEMPT,
        ATTEMPT_AND_RESULT
    }

    public record InputObject(String name, List<InputField> fields) {
        public InputObject {
            name = requireText(name, "input.name");
            fields = listOrEmpty(fields);
        }
    }

    public record InputField(
            String name,
            String graphqlType,
            boolean required,
            String description
    ) {
        public InputField {
            name = requireText(name, "inputField.name");
            graphqlType = requireText(graphqlType, "inputField.graphqlType");
            description = textOrEmpty(description);
        }
    }

    public record PayloadObject(String name, List<PayloadField> fields) {
        public PayloadObject {
            name = requireText(name, "payload.name");
            fields = listOrEmpty(fields);
        }
    }

    public record PayloadField(
            String name,
            String graphqlType,
            boolean required,
            String description
    ) {
        public PayloadField {
            name = requireText(name, "payloadField.name");
            graphqlType = requireText(graphqlType, "payloadField.graphqlType");
            description = textOrEmpty(description);
        }
    }

    public record AuthorizationMetadata(
            boolean requiresAuthenticatedActor,
            String policyName,
            List<String> requiredRoles
    ) {
        public AuthorizationMetadata {
            policyName = textOrEmpty(policyName);
            requiredRoles = listOrEmpty(requiredRoles);
        }

        public static AuthorizationMetadata none() {
            return new AuthorizationMetadata(false, "", List.of());
        }
    }

    public record TransactionMetadata(
            TransactionMode mode,
            String idempotencyKeyInputField,
            String conflictPolicy
    ) {
        public TransactionMetadata {
            mode = mode == null ? TransactionMode.NONE : mode;
            idempotencyKeyInputField = textOrEmpty(idempotencyKeyInputField);
            conflictPolicy = textOrEmpty(conflictPolicy);
        }

        public static TransactionMetadata none() {
            return new TransactionMetadata(TransactionMode.NONE, "", "");
        }
    }

    public record AuditMetadata(
            AuditMode mode,
            String eventType,
            boolean includeInput,
            boolean includePayload
    ) {
        public AuditMetadata {
            mode = mode == null ? AuditMode.NONE : mode;
            eventType = textOrEmpty(eventType);
        }

        public static AuditMetadata none() {
            return new AuditMetadata(AuditMode.NONE, "", false, false);
        }
    }

    private final String name;
    private final String commandName;
    private final String description;
    private final InputObject input;
    private final PayloadObject payload;
    private final AuthorizationMetadata authorization;
    private final TransactionMetadata transaction;
    private final AuditMetadata audit;

    public GraphqlMutationDescriptor(
            String name,
            String commandName,
            String description,
            InputObject input,
            PayloadObject payload,
            AuthorizationMetadata authorization,
            TransactionMetadata transaction,
            AuditMetadata audit
    ) {
        this.name = requireText(name, "mutation.name");
        this.commandName = requireText(commandName, "mutation.commandName");
        this.description = textOrEmpty(description);
        if (input == null) {
            throw new IllegalArgumentException("mutation.input is required");
        }
        if (payload == null) {
            throw new IllegalArgumentException("mutation.payload is required");
        }
        this.input = input;
        this.payload = payload;
        this.authorization = authorization == null ? AuthorizationMetadata.none() : authorization;
        this.transaction = transaction == null ? TransactionMetadata.none() : transaction;
        this.audit = audit == null ? AuditMetadata.none() : audit;
    }

    public String name() {
        return name;
    }

    public String commandName() {
        return commandName;
    }

    public String description() {
        return description;
    }

    public InputObject input() {
        return input;
    }

    public PayloadObject payload() {
        return payload;
    }

    public AuthorizationMetadata authorization() {
        return authorization;
    }

    public TransactionMetadata transaction() {
        return transaction;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static <T> List<T> listOrEmpty(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
