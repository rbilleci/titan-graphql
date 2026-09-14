package io.titan.graphql;

import io.titan.management.ManagementCommands;
import java.util.LinkedHashMap;
import java.util.Map;

final class TitanGraphqlGap006CommandContext {

    private TitanGraphqlGap006CommandContext() {
    }

    static ManagementCommands.CommandInvocation importModelDocument(
            Map<String, Object> graphqlInput,
            GraphqlRequestContext context
    ) {
        Map<String, String> titanInput = new LinkedHashMap<>();
        titanInput.put("workspaceId", text(graphqlInput.get("workspaceId")));
        titanInput.put("sourceFormat", "yaml");
        titanInput.put("sourceText", text(graphqlInput.get("yaml")));
        String workspaceId = titanInput.get("workspaceId");
        return new ManagementCommands.CommandInvocation(
                ManagementCommands.ManagementCommandDescriptors.importModelDocument(),
                new ManagementCommands.ActorContext(
                        context.actorKey(),
                        context.actorRole(),
                        workspaceId,
                        context.actorKey().isBlank() == false
                ),
                new ManagementCommands.RequestContext(context.requestId(), context.idempotencyKey()),
                titanInput
        );
    }

    static void requireValid(ManagementCommands.CommandInvocation invocation) {
        ManagementCommands.CommandValidation validation = invocation.validate();
        if (validation.valid()) {
            return;
        }
        throw new GraphqlException(String.join("; ", validation.errors()));
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
