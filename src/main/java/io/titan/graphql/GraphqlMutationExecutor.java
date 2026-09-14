package io.titan.graphql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GraphqlMutationExecutor {

    private final GraphqlSchema schema;
    private final Map<String, GraphqlMutationCommandHandler> handlers;
    private final GraphqlMutationAuditLog auditLog;

    GraphqlMutationExecutor(
            GraphqlSchema schema,
            Map<String, GraphqlMutationCommandHandler> handlers,
            GraphqlMutationAuditLog auditLog
    ) {
        this.schema = schema;
        this.handlers = handlers == null ? Map.of() : Map.copyOf(handlers);
        this.auditLog = auditLog;
    }

    GraphqlExecution execute(GraphqlAst.AstOperation operation, GraphqlRequestContext context) {
        try {
            return executeMutation(operation, context);
        } catch (GraphqlException ex) {
            return new GraphqlExecution(GraphqlJsonWriter.error(ex), new GraphqlPlan());
        }
    }

    private GraphqlExecution executeMutation(GraphqlAst.AstOperation operation, GraphqlRequestContext context) {
        if (operation.type() != GraphqlAst.OperationType.MUTATION) {
            throw GraphqlException.unsupportedOperation(operation.type().name().toLowerCase());
        }
        if (operation.selections().size() != 1 || operation.selections().getFirst() instanceof GraphqlAst.Field == false) {
            throw new GraphqlException("mutation operation must select exactly one mutation field");
        }
        GraphqlAst.Field field = (GraphqlAst.Field) operation.selections().getFirst();
        GraphqlMutationDescriptor descriptor = schema.mutation(field.name());
        if (descriptor == null) {
            throw new GraphqlException("unsupported mutation field '" + field.name() + "'");
        }
        authorize(descriptor, context);
        GraphqlMutationCommandHandler handler = handlers.get(descriptor.commandName());
        if (handler == null) {
            throw new GraphqlException("no command handler is registered for mutation '" + descriptor.name() + "'");
        }
        Map<String, Object> input = coerceInput(descriptor, field);
        recordAudit(descriptor, context, GraphqlMutationAuditEvent.MutationAuditStatus.ATTEMPT, input, Map.of(), "");
        try {
            GraphqlMutationCommandResult result = handler.handle(
                    new GraphqlMutationCommandHandler.GraphqlMutationCommandRequest(descriptor, input, context)
            );
            Map<String, Object> payload = result == null ? Map.of() : result.payload();
            String json = renderPayload(field, descriptor, payload);
            recordAudit(descriptor, context, GraphqlMutationAuditEvent.MutationAuditStatus.SUCCESS, input, payload, "");
            return new GraphqlExecution(json, new GraphqlPlan());
        } catch (GraphqlException ex) {
            recordAudit(descriptor, context, GraphqlMutationAuditEvent.MutationAuditStatus.FAILURE, input, Map.of(), ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            String message = "mutation command handler failed: " + ex.getMessage();
            recordAudit(descriptor, context, GraphqlMutationAuditEvent.MutationAuditStatus.FAILURE, input, Map.of(), message);
            throw new GraphqlException(message);
        }
    }

    private static void authorize(GraphqlMutationDescriptor descriptor, GraphqlRequestContext context) {
        GraphqlMutationDescriptor.AuthorizationMetadata authorization = descriptor.authorization();
        if (authorization.requiresAuthenticatedActor() && context.actorRole().isBlank()) {
            throw GraphqlException.authorization("mutation '" + descriptor.name() + "' requires an authenticated actor");
        }
        if (authorization.requiredRoles().isEmpty() == false
                && authorization.requiredRoles().contains(context.actorRole()) == false) {
            throw GraphqlException.authorization("actor role is not authorized for mutation '" + descriptor.name() + "'");
        }
    }

    private static Map<String, Object> coerceInput(GraphqlMutationDescriptor descriptor, GraphqlAst.Field field) {
        if (field.arguments().size() != 1 || field.arguments().containsKey("input") == false) {
            throw new GraphqlException("mutation '" + descriptor.name() + "' requires argument 'input'");
        }
        GraphqlAst.Value value = field.arguments().get("input");
        if (!(value instanceof GraphqlAst.InputObjectValue objectValue)) {
            throw new GraphqlException("mutation '" + descriptor.name() + "' argument 'input' must be an object");
        }
        Map<String, GraphqlMutationDescriptor.InputField> declared = new LinkedHashMap<>();
        for (GraphqlMutationDescriptor.InputField inputField : descriptor.input().fields()) {
            declared.put(inputField.name(), inputField);
        }
        Map<String, Object> coerced = new LinkedHashMap<>();
        for (String suppliedName : objectValue.fields().keySet()) {
            if (declared.containsKey(suppliedName) == false) {
                throw new GraphqlException("mutation '" + descriptor.name()
                        + "' input field '" + suppliedName + "' is not supported");
            }
        }
        for (GraphqlMutationDescriptor.InputField inputField : descriptor.input().fields()) {
            GraphqlAst.Value supplied = objectValue.fields().get(inputField.name());
            if (supplied == null) {
                if (inputField.required()) {
                    throw new GraphqlException("mutation '" + descriptor.name()
                            + "' requires input field '" + inputField.name() + "'");
                }
                continue;
            }
            Object coercedValue = coerceValue(descriptor.name(), inputField, supplied);
            if (coercedValue == null && inputField.required()) {
                throw new GraphqlException("mutation '" + descriptor.name()
                        + "' input field '" + inputField.name() + "' cannot be null");
            }
            coerced.put(inputField.name(), coercedValue);
        }
        return Map.copyOf(coerced);
    }

    private static Object coerceValue(
            String mutationName,
            GraphqlMutationDescriptor.InputField inputField,
            GraphqlAst.Value value
    ) {
        if (value instanceof GraphqlAst.NullValue) {
            return null;
        }
        String type = namedType(inputField.graphqlType());
        if (type.equals("String")) {
            if (value instanceof GraphqlAst.StringValue stringValue) {
                return stringValue.value();
            }
        } else if (type.equals("ID")) {
            if (value instanceof GraphqlAst.StringValue stringValue) {
                return stringValue.value();
            }
            if (value instanceof GraphqlAst.IdValue idValue) {
                return idValue.value();
            }
            if (value instanceof GraphqlAst.IntValue intValue) {
                return Long.toString(intValue.value());
            }
        } else if (type.equals("Boolean")) {
            if (value instanceof GraphqlAst.BooleanValue booleanValue) {
                return booleanValue.value();
            }
        } else if (type.equals("Int")) {
            if (value instanceof GraphqlAst.IntValue intValue) {
                return intValue.value();
            }
        } else {
            throw new GraphqlException("mutation '" + mutationName
                    + "' input field '" + inputField.name() + "' has unsupported type '" + inputField.graphqlType() + "'");
        }
        throw new GraphqlException("mutation '" + mutationName
                + "' input field '" + inputField.name() + "' must be " + inputField.graphqlType());
    }

    private static String renderPayload(
            GraphqlAst.Field field,
            GraphqlMutationDescriptor descriptor,
            Map<String, Object> payload
    ) {
        Map<String, GraphqlMutationDescriptor.PayloadField> declared = new LinkedHashMap<>();
        for (GraphqlMutationDescriptor.PayloadField payloadField : descriptor.payload().fields()) {
            declared.put(payloadField.name(), payloadField);
        }
        StringBuilder json = new StringBuilder();
        json.append("{\"data\":{\"").append(escape(field.responseKey())).append("\":{");
        boolean first = true;
        for (GraphqlAst.Selection selection : field.selections()) {
            if (!(selection instanceof GraphqlAst.Field payloadSelection)) {
                throw new GraphqlException("mutation '" + descriptor.name() + "' only supports direct payload fields");
            }
            if (payloadSelection.selections().isEmpty() == false) {
                throw new GraphqlException("mutation '" + descriptor.name()
                        + "' payload field '" + payloadSelection.name() + "' does not support nested selections");
            }
            GraphqlMutationDescriptor.PayloadField payloadField = declared.get(payloadSelection.name());
            if (payloadField == null) {
                throw new GraphqlException("mutation '" + descriptor.name()
                        + "' payload field '" + payloadSelection.name() + "' is not supported");
            }
            Object value = payload.get(payloadField.name());
            if (value == null && payloadField.required()) {
                throw new GraphqlException("mutation '" + descriptor.name()
                        + "' required payload field '" + payloadField.name() + "' was not returned");
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(payloadSelection.responseKey())).append("\":");
            appendJsonValue(json, value);
        }
        json.append("}}}");
        return json.toString();
    }

    private void recordAudit(
            GraphqlMutationDescriptor descriptor,
            GraphqlRequestContext context,
            GraphqlMutationAuditEvent.MutationAuditStatus status,
            Map<String, Object> input,
            Map<String, Object> payload,
            String errorMessage
    ) {
        if (auditLog == null || descriptor.audit().mode() == GraphqlMutationDescriptor.AuditMode.NONE) {
            return;
        }
        if (descriptor.audit().mode() == GraphqlMutationDescriptor.AuditMode.ATTEMPT
                && status != GraphqlMutationAuditEvent.MutationAuditStatus.ATTEMPT) {
            return;
        }
        auditLog.record(new GraphqlMutationAuditEvent(
                descriptor.name(),
                descriptor.commandName(),
                descriptor.audit().eventType(),
                status,
                descriptor.audit().includeInput() ? input : Map.of(),
                descriptor.audit().includePayload() ? payload : Map.of(),
                errorMessage,
                context.actorRole(),
                context.requestId()
        ));
    }

    private static String namedType(String graphqlType) {
        String type = graphqlType;
        while (type.endsWith("!")) {
            type = type.substring(0, type.length() - 1);
        }
        return type;
    }

    private static void appendJsonValue(StringBuilder json, Object value) {
        if (value == null) {
            json.append("null");
        } else if (value instanceof Boolean || value instanceof Number) {
            json.append(value);
        } else {
            json.append('"').append(escape(value.toString())).append('"');
        }
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder();
        String text = value == null ? "" : value;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(ch);
            }
        }
        return escaped.toString();
    }
}
