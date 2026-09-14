package io.titan.graphql;

import java.util.Map;

record GraphqlMutationAuditEvent(
        String mutationName,
        String commandName,
        String eventType,
        MutationAuditStatus status,
        Map<String, Object> input,
        Map<String, Object> payload,
        String errorMessage,
        String actorRole,
        String requestId
) {

    // Renamed from Status under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    enum MutationAuditStatus {
        ATTEMPT,
        SUCCESS,
        FAILURE
    }

    GraphqlMutationAuditEvent {
        input = input == null ? Map.of() : Map.copyOf(input);
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        errorMessage = errorMessage == null ? "" : errorMessage;
        actorRole = actorRole == null ? "" : actorRole;
        requestId = requestId == null ? "" : requestId;
    }
}
