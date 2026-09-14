package io.titan.graphql;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record GraphqlMutationAuditEvent(
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
    public enum MutationAuditStatus {
        ATTEMPT,
        SUCCESS,
        FAILURE
    }

    public GraphqlMutationAuditEvent {
        input = input == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(input));
        payload = payload == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        errorMessage = errorMessage == null ? "" : errorMessage;
        actorRole = actorRole == null ? "" : actorRole;
        requestId = requestId == null ? "" : requestId;
    }
}
