package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import io.titan.graphql.management.TitanGraphqlManagementStore;
import io.titan.graphql.management.TitanGraphqlManagedWorkspace;
import io.titan.graphql.management.TitanGraphqlModelDraft;
import io.titan.graphql.management.TitanGraphqlObservedOperation;
import io.titan.graphql.management.TitanGraphqlOperationRegistry;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import io.titan.management.ManagementAudit.AuditStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TitanGraphqlManagementMutationMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultManagementMutationSupportCanSelectDurableStoreByProperty() {
        String previous = System.getProperty("titan.graphql.management.transactionLog");
        try {
            System.setProperty(
                    "titan.graphql.management.transactionLog",
                    tempDir.resolve("configured-management.log").toString());

            assertTrue(GraphqlManagementMutationSupport.defaultManagementStore()
                    instanceof TitanGraphqlDurableManagementStore);
        } finally {
            if (previous == null) {
                System.clearProperty("titan.graphql.management.transactionLog");
            } else {
                System.setProperty("titan.graphql.management.transactionLog", previous);
            }
        }
    }

    @Test
    void importModelDocumentUsesTitanTransactionIdempotencyAndAudit() {
        TitanGraphqlDurableManagementStore store = durableStore();
        GraphqlManagementMutationSupport support = new GraphqlManagementMutationSupport(store);
        GraphqlSchema schema = managementSchema(support);
        String yaml = readDemoBlogFixture();
        GraphqlAst.AstOperation operation = importOperation(yaml);
        GraphqlRequestContext context = context("request-import", "idem-import");

        GraphqlExecution first = support.executeMutation(schema, operation, context);
        GraphqlExecution replay = support.executeMutation(schema, operation, context);

        assertTrue(first.json().contains("\"accepted\":true"));
        assertEquals(first.json(), replay.json());
        assertEquals(1, store.titanDrafts().size());
        assertEquals(1, store.idempotencyRecords().size());
        assertEquals(4, store.auditRecords().size());
        assertEquals(AuditStatus.ATTEMPT, store.auditRecords().get(0).status());
        assertEquals(AuditStatus.SUCCESS, store.auditRecords().get(1).status());
        assertEquals(AuditStatus.ATTEMPT, store.auditRecords().get(2).status());
        assertEquals(AuditStatus.SUCCESS, store.auditRecords().get(3).status());
    }

    @Test
    void importModelDocumentRejectsSameIdempotencyKeyWithDifferentInput() {
        TitanGraphqlDurableManagementStore store = durableStore();
        GraphqlManagementMutationSupport support = new GraphqlManagementMutationSupport(store);
        GraphqlSchema schema = managementSchema(support);

        support.executeMutation(schema, importOperation(readDemoBlogFixture()), context("request-import", "idem-import"));
        GraphqlExecution conflict = support.executeMutation(
                schema,
                importOperation(readDemoBlogFixture().replace("name: demo-blog", "name: demo-blog-v2")),
                context("request-import", "idem-import"));

        assertTrue(conflict.json().contains("idempotency input mismatch"));
        assertEquals(1, store.titanDrafts().size());
        assertEquals(1, store.idempotencyRecords().size());
        assertEquals(AuditStatus.FAILURE, store.auditRecords().get(3).status());
    }

    @Test
    void importModelDocumentValidationFailureAuditsWithoutWritingDraft() {
        TitanGraphqlDurableManagementStore store = durableStore();
        GraphqlManagementMutationSupport support = new GraphqlManagementMutationSupport(store);
        GraphqlSchema schema = managementSchema(support);

        GraphqlExecution execution = support.executeMutation(
                schema,
                importOperation(readDemoBlogFixture()),
                context("request-import", ""));

        assertTrue(execution.json().contains("requires idempotency key"));
        assertTrue(store.titanDrafts().isEmpty());
        assertTrue(store.idempotencyRecords().isEmpty());
        assertEquals(2, store.auditRecords().size());
        assertEquals(AuditStatus.ATTEMPT, store.auditRecords().get(0).status());
        assertEquals(AuditStatus.FAILURE, store.auditRecords().get(1).status());
    }

    @Test
    void validateModelDraftPersistsProductStateAcrossDurableStoreReload() {
        Path logPath = tempDir.resolve("validate-management.log");
        TitanGraphqlDurableManagementStore importedStore = durableStore(logPath);
        GraphqlSchema importSchema = managementSchema(new GraphqlManagementMutationSupport(importedStore));
        String yaml = readDemoBlogFixture();
        String draftId = draftId(yaml);

        new GraphqlManagementMutationSupport(importedStore).executeMutation(
                importSchema,
                importOperation(yaml),
                context("request-import", "idem-import"));

        TitanGraphqlDurableManagementStore reloadedStore = durableStore(logPath);
        GraphqlManagementMutationSupport support = new GraphqlManagementMutationSupport(reloadedStore);
        GraphqlExecution validated = support.executeMutation(
                managementSchema(support),
                GraphqlParser.parseSelectedOperation(GraphqlRequest.of("""
                        mutation Validate {
                          validateModelDraft(input: { draftId: "%s" }) {
                            accepted
                            validationReportId
                            status
                          }
                        }
                        """.formatted(draftId), "Validate")),
                context("request-validate", ""));

        TitanGraphqlDurableManagementStore finalReload = durableStore(logPath);
        assertTrue(validated.json().contains("\"status\":\"VALIDATED\""));
        assertEquals(TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED, finalReload.draft(draftId).status());
        assertEquals("validation-" + draftId, finalReload.validationReport("validation-" + draftId).id());
    }

    @Test
    void generateModelArtifactsPersistsGraphqlArtifactRefsAcrossDurableStoreReload() {
        Path logPath = tempDir.resolve("generate-management.log");
        TitanGraphqlDurableManagementStore store = durableStore(logPath);
        GraphqlManagementMutationSupport support = new GraphqlManagementMutationSupport(store);
        GraphqlSchema schema = managementSchema(support);
        String yaml = readDemoBlogFixture();
        String draftId = draftId(yaml);

        support.executeMutation(schema, importOperation(yaml), context("request-import", "idem-import"));
        support.executeMutation(schema, GraphqlParser.parseSelectedOperation(GraphqlRequest.of("""
                mutation Validate {
                  validateModelDraft(input: { draftId: "%s" }) { accepted }
                }
                """.formatted(draftId), "Validate")), context("request-validate", ""));
        GraphqlExecution generated = support.executeMutation(
                schema,
                GraphqlParser.parseSelectedOperation(GraphqlRequest.of("""
                        mutation Generate {
                          generateModelArtifacts(input: { draftId: "%s", enableIntrospection: true }) {
                            accepted
                            artifactSetId
                            generatedArtifacts
                          }
                        }
                        """.formatted(draftId), "Generate")),
                context("request-generate", ""));

        TitanGraphqlDurableManagementStore reloaded = durableStore(logPath);
        assertTrue(generated.json().contains("\"artifactSetId\":\"artifact-" + draftId + "\""));
        assertEquals("artifact-" + draftId, reloaded.artifactSet("artifact-" + draftId).id());
        assertEquals("artifact-" + draftId, reloaded.draft(draftId).artifactSetId());
        // The demo-blog model requests SQL artifacts, so the mutation reads the configured
        // GAP-005 package (titan.graphql.artifacts.dir) and seeds the titan artifact ref plus
        // the package evidence — both must survive the durable reload.
        assertEquals("artifact-" + draftId, store.titanArtifactRefs().getFirst().id());
        assertEquals("pending", reloaded.artifactEvidence("artifact-" + draftId).verificationStatus());
        assertEquals("executeGraphqlRequestWithCompactContext",
                reloaded.artifactEvidence("artifact-" + draftId).entryPoints().getFirst().methodName());
        assertTrue(reloaded.artifactEvidence("artifact-" + draftId).rollbackScripts().getFirst().present());
    }

    @Test
    void observedOperationReviewMutationsPersistProductStateAcrossDurableStoreReload() {
        Path logPath = tempDir.resolve("review-management.log");
        TitanGraphqlDurableManagementStore store = durableStore(logPath);
        TitanGraphqlObservedOperation observed = TitanGraphqlObservedOperation.observed(
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
        store.observeOperation(observed);
        GraphqlManagementMutationSupport support = new GraphqlManagementMutationSupport(store);

        GraphqlExecution approved = support.executeMutation(
                managementSchema(support),
                GraphqlParser.parseSelectedOperation(GraphqlRequest.of("""
                        mutation Approve {
                          approveObservedOperation(input: {
                            observedOperationId: "%s",
                            reviewedAt: "2026-06-01T20:00:00Z"
                          }) {
                            accepted
                            status
                          }
                        }
                        """.formatted(observed.id()), "Approve")),
                context("request-approve", ""));

        TitanGraphqlDurableManagementStore reloaded = durableStore(logPath);
        TitanGraphqlOperationRegistry registry = reloaded.operationRegistry(
                "registry-model-001-prod"
        );
        assertTrue(approved.json().contains("\"status\":\"APPROVED\""));
        assertEquals(TitanGraphqlObservedOperation.ObservedOperationStatus.APPROVED, reloaded.observedOperation(observed.id()).status());
        assertEquals(TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED, registry.operations().getFirst().status());
        assertEquals("operator", registry.operations().getFirst().approvedBy());
    }

    private TitanGraphqlDurableManagementStore durableStore() {
        return durableStore(tempDir.resolve("management.log"));
    }

    private TitanGraphqlDurableManagementStore durableStore(Path logPath) {
        return new TitanGraphqlDurableManagementStore(logPath)
                .saveWorkspace(new TitanGraphqlManagedWorkspace(
                        "workspace-001",
                        "platform",
                        "",
                        "prod",
                        "2026-06-09T00:00:00Z",
                        ""));
    }

    private static GraphqlSchema managementSchema(GraphqlManagementMutationSupport support) {
        return support.withManagementMutations(ProjectionGraphqlAdapter.adapt(
                io.titan.graphql.management.TitanGraphqlManagementProjection.projectionModel().toProjectionModel()
        ));
    }

    private static GraphqlAst.AstOperation importOperation(String yaml) {
        return GraphqlParser.parseSelectedOperation(new GraphqlRequest(
                """
                mutation Import($yaml: String!) {
                  importModelDocument(input: { workspaceId: "workspace-001", yaml: $yaml }) {
                    accepted
                    draftId
                    modelId
                    errors
                    warnings
                  }
                }
                """,
                "Import",
                Map.of("yaml", yaml),
                Map.of()
        ));
    }

    private static String draftId(String yaml) {
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(yaml);
        return "draft-" + TitanGraphqlModelDocumentJson.semanticHash(document).substring(0, 12);
    }

    private static GraphqlRequestContext context(String requestId, String idempotencyKey) {
        return new GraphqlRequestContext(
                0L,
                "operator",
                "actor-operator",
                "",
                requestId,
                idempotencyKey,
                List.of("management"),
                List.of(),
                true,
                false,
                false,
                0L);
    }

    private static String readDemoBlogFixture() {
        try (InputStream stream = TitanGraphqlManagementStore.class.getResourceAsStream(
                "/graphql/demo-blog.titan.graphql.yaml"
        )) {
            if (stream == null) {
                throw new IllegalStateException("missing demo-blog model fixture");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read demo-blog model fixture", ex);
        }
    }
}
