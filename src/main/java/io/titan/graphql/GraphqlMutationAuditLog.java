package io.titan.graphql;

import java.util.ArrayList;
import java.util.List;

final class GraphqlMutationAuditLog {

    private final List<GraphqlMutationAuditEvent> events = new ArrayList<>();

    void record(GraphqlMutationAuditEvent event) {
        events.add(event);
    }

    List<GraphqlMutationAuditEvent> events() {
        return List.copyOf(events);
    }
}
