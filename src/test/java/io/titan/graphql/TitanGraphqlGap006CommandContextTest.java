package io.titan.graphql;

import io.titan.management.ManagementCommands;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TitanGraphqlGap006CommandContextTest {

    @Test
    void mapsImportModelDocumentToTitanGap006CommandInvocation() {
        ManagementCommands.CommandInvocation invocation = TitanGraphqlGap006CommandContext.importModelDocument(
                Map.of(
                        "workspaceId", "workspace-001",
                        "yaml", "apiVersion: titan.graphql/v1alpha1"
                ),
                managementContext("operator", "actor-operator", "request-import", "idem-import")
        );

        ManagementCommands.CommandValidation validation = invocation.validate();

        assertTrue(validation.valid());
        assertEquals("management.importModelDocument", invocation.descriptor().commandName());
        assertEquals("operator", invocation.actor().role());
        assertEquals("actor-operator", invocation.actor().actorId());
        assertEquals("workspace-001", invocation.actor().scope());
        assertEquals("request-import", invocation.request().requestId());
        assertEquals("idem-import", invocation.request().idempotencyKey());
        assertEquals("yaml", invocation.input().get("sourceFormat"));
        assertEquals("apiVersion: titan.graphql/v1alpha1", invocation.input().get("sourceText"));
    }

    @Test
    void returnsTitanGap006ValidationErrorsForMissingCommandContext() {
        ManagementCommands.CommandInvocation invocation = TitanGraphqlGap006CommandContext.importModelDocument(
                Map.of("workspaceId", "workspace-001", "yaml", "apiVersion: titan.graphql/v1alpha1"),
                managementContext("operator", "", "request-import", "")
        );

        GraphqlException failure = assertThrows(
                GraphqlException.class,
                () -> TitanGraphqlGap006CommandContext.requireValid(invocation)
        );

        assertTrue(failure.getMessage().contains("TITAN-MGMT-E002"));
        assertTrue(failure.getMessage().contains("TITAN-MGMT-E003"));
    }

    @Test
    void adminImportEndpointRequiresGap006ActorAndIdempotencyContextBeforeHandlerRuns() {
        GraphqlAdminHttpResource.GraphqlAdminHttpResult response = new GraphqlAdminHttpResource().negotiatePost(
                Map.of("query", """
                        mutation Import {
                          importModelDocument(input: { workspaceId: "workspace-001", yaml: "not: yaml: model" }) {
                            accepted
                            draftId
                          }
                        }
                        """),
                "application/json",
                new GraphqlAdminHttpResource.GraphqlAdminHttpContext(
                        "",
                        "operator",
                        "request-import",
                        "",
                        true,
                        "")
        );

        assertTrue(response.body().contains("TITAN-MGMT-E002"));
        assertTrue(response.body().contains("TITAN-MGMT-E003"));
    }

    private static GraphqlRequestContext managementContext(
            String actorRole,
            String actorKey,
            String requestId,
            String idempotencyKey
    ) {
        return new GraphqlRequestContext(
                0L,
                actorRole,
                actorKey,
                "",
                requestId,
                idempotencyKey,
                List.of("management"),
                List.of(),
                true,
                false,
                false,
                0L
        );
    }
}
