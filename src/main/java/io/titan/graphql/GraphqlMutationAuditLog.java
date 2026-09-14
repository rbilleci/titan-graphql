package io.titan.graphql;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Thread-safe in-process audit collector intended for tests and development. */
public final class GraphqlMutationAuditLog implements GraphqlMutationAuditSink {

    private final List<GraphqlMutationAuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(GraphqlMutationAuditEvent event) {
        events.add(event);
    }

    public List<GraphqlMutationAuditEvent> events() {
        return List.copyOf(events);
    }
}
