package io.titan.graphql;

/** Receives redaction-aware application mutation audit events. */
@FunctionalInterface
public interface GraphqlMutationAuditSink {

    GraphqlMutationAuditSink NONE = event -> { };

    /**
     * Records an event without throwing. Correctness-critical audit data should be written by the
     * command handler in the same transaction/outbox as its domain change; this hook is delivery
     * and observability integration, not an automatic transaction boundary.
     */
    void record(GraphqlMutationAuditEvent event);
}
