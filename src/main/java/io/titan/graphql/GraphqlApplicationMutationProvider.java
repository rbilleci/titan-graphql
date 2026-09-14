package io.titan.graphql;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Application-owned custom mutation registrations for the schema-driven runtime.
 *
 * <p>Reads remain generated from the reviewed model. Writes are deliberately explicit: an
 * application supplies descriptors and dependency-injected command handlers. A handler owns the
 * transaction/idempotency contract declared by its descriptor; Titan GraphQL owns parsing,
 * validation, authorization, dispatch, payload shaping, and audit event delivery.</p>
 */
public interface GraphqlApplicationMutationProvider {

    List<GraphqlMutationDescriptor> descriptors();

    Map<String, GraphqlMutationCommandHandler> handlers();

    default GraphqlMutationAuditSink auditSink() {
        return GraphqlMutationAuditSink.NONE;
    }

    static GraphqlApplicationMutationProvider none() {
        return of(List.of(), Map.of(), GraphqlMutationAuditSink.NONE);
    }

    static GraphqlApplicationMutationProvider of(
            List<GraphqlMutationDescriptor> descriptors,
            Map<String, GraphqlMutationCommandHandler> handlers,
            GraphqlMutationAuditSink auditSink
    ) {
        List<GraphqlMutationDescriptor> suppliedDescriptors = descriptors == null ? List.of() : descriptors;
        Map<String, GraphqlMutationCommandHandler> suppliedHandlers = handlers == null ? Map.of() : handlers;
        validate(suppliedDescriptors, suppliedHandlers);
        List<GraphqlMutationDescriptor> descriptorSnapshot = List.copyOf(suppliedDescriptors);
        Map<String, GraphqlMutationCommandHandler> handlerSnapshot = Map.copyOf(suppliedHandlers);
        GraphqlMutationAuditSink sink = auditSink == null ? GraphqlMutationAuditSink.NONE : auditSink;
        return new GraphqlApplicationMutationProvider() {
            @Override
            public List<GraphqlMutationDescriptor> descriptors() {
                return descriptorSnapshot;
            }

            @Override
            public Map<String, GraphqlMutationCommandHandler> handlers() {
                return handlerSnapshot;
            }

            @Override
            public GraphqlMutationAuditSink auditSink() {
                return sink;
            }
        };
    }

    private static void validate(
            List<GraphqlMutationDescriptor> descriptors,
            Map<String, GraphqlMutationCommandHandler> handlers
    ) {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> commands = new LinkedHashMap<>();
        for (GraphqlMutationDescriptor descriptor : descriptors) {
            if (descriptor == null) {
                throw new IllegalArgumentException("custom mutation descriptor must not be null");
            }
            if (names.putIfAbsent(descriptor.name(), descriptor.commandName()) != null) {
                throw new IllegalArgumentException("duplicate custom mutation name '" + descriptor.name() + "'");
            }
            if (commands.putIfAbsent(descriptor.commandName(), descriptor.name()) != null) {
                throw new IllegalArgumentException("duplicate custom mutation command '"
                        + descriptor.commandName() + "'");
            }
            if (!handlers.containsKey(descriptor.commandName())) {
                throw new IllegalArgumentException("no command handler is registered for custom mutation '"
                        + descriptor.name() + "'");
            }
        }
        for (Map.Entry<String, GraphqlMutationCommandHandler> handler : handlers.entrySet()) {
            if (handler.getKey() == null || handler.getKey().isBlank() || handler.getValue() == null) {
                throw new IllegalArgumentException("custom mutation handler registrations require a command and handler");
            }
            if (!commands.containsKey(handler.getKey())) {
                throw new IllegalArgumentException("custom mutation handler '" + handler.getKey()
                        + "' has no descriptor");
            }
        }
    }
}
