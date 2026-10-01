import com.fasterxml.jackson.databind.ObjectMapper;
import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import io.titan.graphql.management.TitanGraphqlInMemoryManagementStore;
import io.titan.graphql.management.TitanGraphqlObservedOperation;
import io.titan.graphql.management.TitanGraphqlOperationRegistry;
import io.titan.management.JdbcTransactionalMutationStore;
import io.titan.runtime.jdbc.SingleConnectionDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class LocalDogfoodControl {
    public static void main(String[] args) throws Exception {
        if (args.length != 5 || !List.of("observe", "enforce-public-read").contains(args[0])) {
            throw new IllegalArgumentException("usage: LocalDogfoodControl <observe|enforce-public-read> <jdbc-url> <model-id> <query-file> <run-id>");
        }
        java.util.UUID.fromString(args[4]);
        String document = Files.readString(Path.of(args[3]), StandardCharsets.UTF_8);
        if (!document.trim().equals("query LocalJob($id: ID!) { job(id: $id) { id jobType status attemptCount } }")) {
            throw new IllegalArgumentException("only the reviewed local job-metadata operation is permitted");
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(document.getBytes(StandardCharsets.UTF_8)));
        try (var connection = DriverManager.getConnection(args[1],
                System.getenv("TITAN_GRAPHQL_CONTROL_DB_USER"),
                System.getenv("TITAN_GRAPHQL_CONTROL_DB_PASSWORD"))) {
            try (var statement = connection.createStatement()) {
                statement.execute("SET search_path TO management");
            }
            var source = new SingleConnectionDataSource(connection);
            var store = new TitanGraphqlDurableManagementStore(new JdbcTransactionalMutationStore(source), source);
            var model = store.model(args[2]);
            var expectedModel = Files.readString(Path.of(args[3]).resolveSibling("jobs.titan.graphql.yaml"));
            if (model == null || !model.name().equals("local-control-jobs")
                    || !model.workspaceId().equals("workspace-local-dogfood")
                    || !store.draft(model.currentDraftId()).sourceText().equals(expectedModel)) {
                throw new IllegalArgumentException("only the checked-in local job-metadata model is permitted");
            }
            String registryId = TitanGraphqlInMemoryManagementStore.operationRegistryId(args[2], "dogfood");
            if (args[0].equals("observe")) {
                var observed = TitanGraphqlObservedOperation.observed(args[2], "dogfood", "public",
                        "local-dogfood-" + args[4], hash, "LocalJob", document, 2, 5,
                        List.of("Query.job", "JobMetadata.id", "JobMetadata.jobType",
                                "JobMetadata.status", "JobMetadata.attemptCount"), Instant.now().toString());
                if (store.observedOperation(observed.id()) == null) {
                    store.saveObservedOperation(observed);
                }
                System.out.println(new ObjectMapper().writeValueAsString(
                        Map.of("observedOperationId", observed.id(), "registryId", registryId)));
            } else {
                var registry = store.operationRegistry(registryId);
                if (registry == null || registry.operations().isEmpty()) {
                    throw new IllegalStateException("the reviewed operation registry is required");
                }
                var approved = registry.operations().stream().filter(op -> op.document().equals(document)
                        && op.operationHash().equals(hash)
                        && op.status() == TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED).toList();
                if (approved.size() != registry.operations().size()) {
                    throw new IllegalStateException("only approvals of the exact local read are permitted");
                }
                var op = approved.getLast();
                if (!op.document().equals(document) || !op.operationHash().equals(hash)
                        || op.status() != TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED) {
                    throw new IllegalStateException("the exact operation must pass management review first");
                }
                // This local model exposes no payloads or writes. Empty role scope deliberately
                // permits its reviewed metadata read without trusting caller-supplied actor headers.
                var publicRead = new TitanGraphqlOperationRegistry.RegisteredOperation(op.id(),
                        op.operationName(), op.operationHash(), op.document(), op.status(), List.of(),
                        List.of(), op.depth(), op.estimatedCost(), op.fieldUsage(), op.lastSeenAt(),
                        op.approvedBy(), op.approvedAt());
                store.saveOperationRegistry(new TitanGraphqlOperationRegistry(registry.id(), registry.modelId(),
                        registry.environment(), TitanGraphqlOperationRegistry.RegistryMode.ENFORCE,
                        List.of(publicRead), Instant.now().toString()));
                System.out.println(new ObjectMapper().writeValueAsString(Map.of("registryId", registryId)));
            }
        }
    }
}
