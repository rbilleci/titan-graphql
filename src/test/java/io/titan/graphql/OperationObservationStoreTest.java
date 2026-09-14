package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.titan.graphql.management.TitanGraphqlInMemoryManagementStore;
import io.titan.graphql.management.TitanGraphqlObservedOperation;
import io.titan.graphql.management.TitanGraphqlOperationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class OperationObservationStoreTest {

    @Test
    void learnsObservedOperationsWithoutEnforcement() {
        TitanGraphqlInMemoryManagementStore store = new TitanGraphqlInMemoryManagementStore();
        TitanGraphqlObservedOperation first = TitanGraphqlObservedOperation.observed(
                "model-001",
                "prod",
                "viewer",
                "portal",
                "operation-sha",
                "ArticleById",
                "query ArticleById { article(id: 1) { id title } }",
                2,
                4,
                List.of("Article.id", "Article.title"),
                "2026-06-01T19:42:00Z"
        );
        TitanGraphqlObservedOperation second = TitanGraphqlObservedOperation.observed(
                "model-001",
                "prod",
                "viewer",
                "portal",
                "operation-sha",
                "ArticleById",
                "query ArticleById { article(id: 2) { id title } }",
                2,
                4,
                List.of("Article.id", "Article.title"),
                "2026-06-01T19:45:00Z"
        );

        store.observeOperation(first)
                .observeOperation(second);

        TitanGraphqlObservedOperation learned = store.observedOperation(first.id());
        assertEquals(TitanGraphqlObservedOperation.ObservedOperationStatus.OBSERVED, learned.status());
        assertEquals("operation-sha", learned.operationHash());
        assertEquals("ArticleById", learned.operationName());
        assertEquals("viewer", learned.role());
        assertEquals("portal", learned.client());
        assertEquals("prod", learned.environment());
        assertEquals(2, learned.depth());
        assertEquals(4, learned.estimatedCost());
        assertEquals(List.of("Article.id", "Article.title"), learned.fieldUsage());
        assertEquals("2026-06-01T19:42:00Z", learned.firstSeenAt());
        assertEquals("2026-06-01T19:45:00Z", learned.lastSeenAt());
        assertEquals(2, learned.observedCount());
        assertEquals(List.of(learned), store.observedOperations("model-001", "prod"));
    }

    @Test
    void keysObservedOperationsByEnvironmentRoleClientAndHash() {
        TitanGraphqlObservedOperation viewerPortal = TitanGraphqlObservedOperation.observed(
                "model-001",
                "prod",
                "viewer",
                "portal",
                "operation-sha",
                "ArticleById",
                "query ArticleById { article(id: 1) { id } }",
                2,
                3,
                List.of("Article.id"),
                "2026-06-01T19:42:00Z"
        );
        TitanGraphqlObservedOperation adminPortal = TitanGraphqlObservedOperation.observed(
                "model-001",
                "prod",
                "admin",
                "portal",
                "operation-sha",
                "ArticleById",
                "query ArticleById { article(id: 1) { id } }",
                2,
                3,
                List.of("Article.id"),
                "2026-06-01T19:42:00Z"
        );

        assertEquals(viewerPortal.id(), TitanGraphqlObservedOperation.observedOperationId(
                "model-001",
                "prod",
                "viewer",
                "portal",
                "operation-sha"
        ));
        assertNotEquals(viewerPortal.id(), adminPortal.id());
    }

    @Test
    void approvesAndRejectsObservedOperationsIntoEnvironmentRegistry() {
        TitanGraphqlInMemoryManagementStore store = new TitanGraphqlInMemoryManagementStore();
        TitanGraphqlObservedOperation viewerPortal = observedOperation("viewer", "portal", "operation-sha");
        TitanGraphqlObservedOperation adminTool = observedOperation("admin", "admin-tool", "operation-sha");
        store.observeOperation(viewerPortal)
                .observeOperation(adminTool);

        TitanGraphqlObservedOperation approved = store.approveObservedOperation(
                viewerPortal.id(),
                "operator",
                "2026-06-01T20:00:00Z"
        );
        TitanGraphqlObservedOperation rejected = store.rejectObservedOperation(
                adminTool.id(),
                "operator",
                "2026-06-01T20:01:00Z"
        );

        assertEquals(TitanGraphqlObservedOperation.ObservedOperationStatus.APPROVED, approved.status());
        assertEquals(TitanGraphqlObservedOperation.ObservedOperationStatus.REJECTED, rejected.status());
        assertEquals(List.of(approved), store.observedOperations("model-001", "prod", "viewer"));
        TitanGraphqlOperationRegistry registry = store.operationRegistry(
                TitanGraphqlInMemoryManagementStore.operationRegistryId("model-001", "prod")
        );
        assertEquals(TitanGraphqlOperationRegistry.RegistryMode.OBSERVE, registry.mode());
        assertEquals(2, registry.operations().size());
        assertEquals(TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED, registry.operations().get(0).status());
        assertEquals(List.of("viewer"), registry.operations().get(0).roles());
        assertEquals(List.of("portal"), registry.operations().get(0).clients());
        assertEquals("operator", registry.operations().get(0).approvedBy());
        assertEquals(TitanGraphqlOperationRegistry.RegisteredOperationStatus.REJECTED, registry.operations().get(1).status());
    }

    @Test
    void preservesImmutableFieldUsageAndRejectsInvalidObservedOperations() {
        ArrayList<String> fields = new ArrayList<>();
        fields.add("Article.id");
        TitanGraphqlObservedOperation operation = TitanGraphqlObservedOperation.observed(
                "model-001",
                "dev",
                "viewer",
                "portal",
                "operation-sha",
                "ArticleById",
                "query ArticleById { article(id: 1) { id } }",
                2,
                3,
                fields,
                "2026-06-01T19:42:00Z"
        );
        fields.add("Article.title");

        assertEquals(List.of("Article.id"), operation.fieldUsage());
        assertThrows(UnsupportedOperationException.class, () -> operation.fieldUsage().add("Article.title"));
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlObservedOperation.observed(
                "model-001",
                "dev",
                "viewer",
                "portal",
                "operation-sha",
                "ArticleById",
                "query",
                -1,
                3,
                List.of(),
                ""
        ));
    }

    private static TitanGraphqlObservedOperation observedOperation(String role, String client, String operationHash) {
        return TitanGraphqlObservedOperation.observed(
                "model-001",
                "prod",
                role,
                client,
                operationHash,
                "ArticleById",
                "query ArticleById { article(id: 1) { id } }",
                2,
                3,
                List.of("Article.id"),
                "2026-06-01T19:42:00Z"
        );
    }
}
