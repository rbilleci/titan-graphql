package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class GraphqlApplicationMutationProviderTest {

    @Test
    void snapshotsValidRegistrations() {
        GraphqlMutationDescriptor mutation = mutation("renameCustomer", "commerce.renameCustomer");
        GraphqlApplicationMutationProvider provider = GraphqlApplicationMutationProvider.of(
                List.of(mutation),
                Map.of(mutation.commandName(), request -> GraphqlMutationCommandResult.of(Map.of())),
                null
        );

        assertEquals(List.of(mutation), provider.descriptors());
        assertEquals(List.of(mutation.commandName()), provider.handlers().keySet().stream().toList());
    }

    @Test
    void rejectsMissingAndOrphanHandlers() {
        GraphqlMutationDescriptor mutation = mutation("renameCustomer", "commerce.renameCustomer");

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> GraphqlApplicationMutationProvider.of(List.of(mutation), Map.of(), null));
        assertTrue(missing.getMessage().contains("no command handler"));

        IllegalArgumentException orphan = assertThrows(IllegalArgumentException.class,
                () -> GraphqlApplicationMutationProvider.of(
                        List.of(),
                        Map.of("commerce.renameCustomer", request -> GraphqlMutationCommandResult.of(Map.of())),
                        null));
        assertTrue(orphan.getMessage().contains("has no descriptor"));
    }

    @Test
    void rejectsDuplicatePublicNamesAndCommands() {
        GraphqlMutationCommandHandler handler = request -> GraphqlMutationCommandResult.of(Map.of());

        IllegalArgumentException duplicateName = assertThrows(IllegalArgumentException.class,
                () -> GraphqlApplicationMutationProvider.of(
                        List.of(
                                mutation("renameCustomer", "commerce.renameCustomer"),
                                mutation("renameCustomer", "commerce.renameCustomerV2")),
                        Map.of(
                                "commerce.renameCustomer", handler,
                                "commerce.renameCustomerV2", handler),
                        null));
        assertTrue(duplicateName.getMessage().contains("duplicate custom mutation name"));

        IllegalArgumentException duplicateCommand = assertThrows(IllegalArgumentException.class,
                () -> GraphqlApplicationMutationProvider.of(
                        List.of(
                                mutation("renameCustomer", "commerce.renameCustomer"),
                                mutation("renameAccount", "commerce.renameCustomer")),
                        Map.of("commerce.renameCustomer", handler),
                        null));
        assertTrue(duplicateCommand.getMessage().contains("duplicate custom mutation command"));
    }

    private static GraphqlMutationDescriptor mutation(String name, String command) {
        return new GraphqlMutationDescriptor(
                name,
                command,
                "",
                new GraphqlMutationDescriptor.InputObject(name + "Input", List.of()),
                new GraphqlMutationDescriptor.PayloadObject(name + "Payload", List.of()),
                GraphqlMutationDescriptor.AuthorizationMetadata.none(),
                GraphqlMutationDescriptor.TransactionMetadata.none(),
                GraphqlMutationDescriptor.AuditMetadata.none()
        );
    }
}
