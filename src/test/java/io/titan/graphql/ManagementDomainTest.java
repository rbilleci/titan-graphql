package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.artifact.TitanGraphqlArtifactKind;
import io.titan.graphql.artifact.TitanGraphqlArtifactManifestEntry;
import io.titan.graphql.artifact.TitanGraphqlArtifactSet;
import io.titan.graphql.management.TitanGraphqlArtifactSetRef;
import io.titan.graphql.management.TitanGraphqlDeployment;
import io.titan.graphql.management.TitanGraphqlDriftReportRef;
import io.titan.graphql.management.TitanGraphqlInMemoryManagementStore;
import io.titan.graphql.management.TitanGraphqlManagedModel;
import io.titan.graphql.management.TitanGraphqlManagedWorkspace;
import io.titan.graphql.management.TitanGraphqlManagementProjection;
import io.titan.graphql.management.TitanGraphqlModelDraft;
import io.titan.graphql.management.TitanGraphqlObservedOperation;
import io.titan.graphql.management.TitanGraphqlOperationRegistry;
import io.titan.graphql.management.TitanGraphqlPreviewBuild;
import io.titan.graphql.management.TitanGraphqlPreviewBuildManifest;
import io.titan.graphql.management.TitanGraphqlPreviewBuildManifestJson;
import io.titan.graphql.management.TitanGraphqlPreviewContractTestReport;
import io.titan.graphql.management.TitanGraphqlPreviewContractTestReportJson;
import io.titan.graphql.management.TitanGraphqlUsageReport;
import io.titan.graphql.management.TitanGraphqlUsageReportJson;
import io.titan.graphql.management.TitanGraphqlValidationReportRef;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ManagementDomainTest {

    @Test
    void capturesManagementLifecycleDescriptorsWithExplicitDefaults() {
        TitanGraphqlManagedWorkspace workspace = new TitanGraphqlManagedWorkspace(
                "workspace-001",
                "platform",
                null,
                "dev",
                "2026-06-01T14:30:00Z",
                null
        );
        TitanGraphqlManagedModel model = new TitanGraphqlManagedModel(
                "model-001",
                workspace.id(),
                "demo-blog",
                "Demo Blog",
                "Demo management model.",
                "draft-001",
                "deployment-001",
                "validation-001",
                "artifact-set-001",
                "2026-06-01T14:31:00Z",
                "2026-06-01T14:32:00Z"
        );
        TitanGraphqlModelDraft draft = new TitanGraphqlModelDraft(
                "draft-001",
                model.id(),
                null,
                null,
                "apiVersion: titan.graphql/v1alpha1\n",
                "{}",
                "semantic-sha",
                "validation-001",
                "artifact-set-001",
                "drift-001",
                "test-operator",
                "2026-06-01T14:33:00Z",
                "2026-06-01T14:34:00Z"
        );
        TitanGraphqlValidationReportRef validationReport = new TitanGraphqlValidationReportRef(
                "validation-001",
                draft.id(),
                TitanGraphqlValidationReportRef.ValidationReportStatus.PASS,
                "valid",
                0,
                1,
                2,
                false,
                "2026-06-01T14:35:00Z"
        );
        TitanGraphqlArtifactSetRef artifactSet = new TitanGraphqlArtifactSetRef(
                "artifact-set-001",
                draft.id(),
                "semantic-sha",
                "validation-sha",
                "drift-sha",
                "sdl-sha",
                "introspection-sha",
                "conformance-sha",
                "sql-sha",
                "postgres-demo",
                "2026-06-01T14:36:00Z"
        );
        TitanGraphqlDriftReportRef driftReport = new TitanGraphqlDriftReportRef(
                "drift-001",
                draft.id(),
                TitanGraphqlDriftReportRef.DriftReportStatus.CLEAN,
                "catalog-snapshot-001",
                "clean",
                0,
                false,
                "2026-06-01T14:37:00Z"
        );
        TitanGraphqlPreviewBuild previewBuild = new TitanGraphqlPreviewBuild(
                "preview-001",
                model.id(),
                draft.id(),
                artifactSet.id(),
                "dev",
                TitanGraphqlPreviewBuild.PreviewBuildStatus.READY,
                "/preview/preview-001/graphql",
                "/admin/preview/preview-001",
                "schema-sha",
                "build/generated/titan-graphql/manifest.json",
                "manifest-sha",
                "registry-001",
                "2026-06-02T14:38:00Z",
                "test-operator",
                "2026-06-01T14:38:00Z"
        );
        TitanGraphqlObservedOperation observedOperation = TitanGraphqlObservedOperation.observed(
                model.id(),
                "dev",
                "viewer",
                "portal",
                "operation-sha",
                "ArticleById",
                "query ArticleById { article(id: 1) { id } }",
                2,
                3,
                List.of("Article.id"),
                "2026-06-01T14:39:00Z"
        );
        TitanGraphqlOperationRegistry registry = new TitanGraphqlOperationRegistry(
                "registry-001",
                model.id(),
                "dev",
                null,
                List.of(new TitanGraphqlOperationRegistry.RegisteredOperation(
                        "operation-001",
                        "ArticleById",
                        "operation-sha",
                        "query ArticleById { article(id: 1) { id } }",
                        TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED,
                        List.of("viewer"),
                        List.of("portal"),
                        2,
                        3,
                        List.of("Article.id"),
                        "2026-06-01T14:39:00Z",
                        "test-operator",
                        "2026-06-01T14:40:00Z"
                )),
                "2026-06-01T14:41:00Z"
        );
        TitanGraphqlUsageReport usageReport = new TitanGraphqlUsageReport(
                "usage-001",
                model.id(),
                "dev",
                "2026.06.01",
                "PT24H",
                "one approved operation",
                1,
                42,
                List.of(new TitanGraphqlUsageReport.OperationUsage(
                        "operation-001",
                        "ArticleById",
                        "operation-sha",
                        "portal",
                        "viewer",
                        "2026.06.01",
                        42,
                        0
                )),
                List.of(new TitanGraphqlUsageReport.FieldUsage("Article.id", 42, 1, "2026-06-01T14:41:00Z")),
                List.of(new TitanGraphqlUsageReport.DimensionUsage("viewer", 42, 1)),
                List.of(new TitanGraphqlUsageReport.DimensionUsage("portal", 42, 1)),
                List.of(new TitanGraphqlUsageReport.DimensionUsage("dev", 42, 1)),
                List.of(new TitanGraphqlUsageReport.DimensionUsage("2026.06.01", 42, 1)),
                List.of(new TitanGraphqlUsageReport.DeprecatedFieldUsage(
                        "Article.legacySlug",
                        "2026.04.01",
                        2,
                        "2026-06-01T14:41:00Z"
                )),
                List.of(new TitanGraphqlUsageReport.UnusedField(
                        "Article.titleLength",
                        "no requests observed in PT24H"
                )),
                List.of(new TitanGraphqlUsageReport.PolicyRejection(
                        "viewerArticlePolicy",
                        "Article.draftNotes",
                        "viewer",
                        "portal",
                        3
                )),
                List.of(new TitanGraphqlUsageReport.SlowOperation(
                        "operation-001",
                        "ArticleById",
                        "operation-sha",
                        120,
                        240,
                        42
                )),
                "2026-06-01T14:42:00Z"
        );
        TitanGraphqlDeployment deployment = new TitanGraphqlDeployment(
                "deployment-001",
                model.id(),
                draft.id(),
                artifactSet.id(),
                "dev",
                TitanGraphqlDeployment.DeploymentStatus.ACTIVE,
                "test-operator",
                "2026-06-01T14:43:00Z",
                "",
                "health-001"
        );

        assertEquals("", workspace.updatedAt());
        assertEquals(TitanGraphqlModelDraft.ModelDraftStatus.EMPTY, new TitanGraphqlModelDraft(
                "draft-empty",
                model.id(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ).status());
        assertEquals(TitanGraphqlModelDraft.SourceFormat.YAML, draft.sourceFormat());
        assertEquals(TitanGraphqlOperationRegistry.RegistryMode.OBSERVE, registry.mode());
        assertEquals("operation-sha", registry.operations().getFirst().operationHash());
        assertEquals("sql-sha", artifactSet.generatedSqlHash());
        assertEquals("catalog-snapshot-001", driftReport.checkedAgainst());
        assertEquals("/preview/preview-001/graphql", previewBuild.previewEndpoint());
        assertEquals("manifest-sha", previewBuild.artifactManifestHash());
        assertEquals(TitanGraphqlObservedOperation.ObservedOperationStatus.OBSERVED, observedOperation.status());
        assertEquals("portal", observedOperation.client());
        assertEquals(1, observedOperation.observedCount());
        assertEquals("one approved operation", usageReport.summary());
        assertEquals("2026.06.01", usageReport.version());
        assertEquals(42, usageReport.totalRequests());
        assertEquals("Article.legacySlug", usageReport.deprecatedFieldsInUse().getFirst().fieldPath());
        assertEquals("Article.titleLength", usageReport.unusedFields().getFirst().fieldPath());
        assertEquals("viewerArticlePolicy", usageReport.policyRejections().getFirst().policyName());
        assertEquals(120, usageReport.slowOperations().getFirst().p95Millis());
        assertEquals("health-001", deployment.runtimeHealthId());
        assertEquals("validation-001", model.latestValidationReportId());
        assertEquals(TitanGraphqlValidationReportRef.ValidationReportStatus.PASS, validationReport.status());
    }

    @Test
    void makesNestedCollectionsImmutable() {
        ArrayList<String> roles = new ArrayList<>();
        roles.add("viewer");
        TitanGraphqlOperationRegistry.RegisteredOperation operation = new TitanGraphqlOperationRegistry.RegisteredOperation(
                "operation-001",
                "ArticleById",
                "operation-sha",
                "query ArticleById { article(id: 1) { id } }",
                null,
                roles,
                List.of("portal"),
                2,
                3,
                List.of("Article.id"),
                "",
                "",
                ""
        );
        roles.add("admin");
        TitanGraphqlOperationRegistry registry = new TitanGraphqlOperationRegistry(
                "registry-001",
                "model-001",
                "dev",
                TitanGraphqlOperationRegistry.RegistryMode.WARN,
                List.of(operation),
                ""
        );

        assertEquals(List.of("viewer"), operation.roles());
        assertThrows(UnsupportedOperationException.class, () -> registry.operations().add(operation));
    }

    @Test
    void rejectsMissingRequiredIdentityAndNegativeCounts() {
        assertThrows(IllegalArgumentException.class, () -> new TitanGraphqlManagedWorkspace("", "platform", "", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new TitanGraphqlManagedModel("", "workspace-001", "demo", "", "", "", "", "", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new TitanGraphqlModelDraft("", "model-001", null, null, "", "", "", "", "", "", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new TitanGraphqlValidationReportRef("validation-001", "draft-001", null, "", -1, 0, 0, false, ""));
        assertThrows(IllegalArgumentException.class, () -> new TitanGraphqlArtifactSetRef("artifact-001", "draft-001", "", "", "", "", "", "", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new TitanGraphqlDriftReportRef("drift-001", "draft-001", null, "", "", -1, false, ""));
        assertThrows(IllegalArgumentException.class, () -> new TitanGraphqlPreviewBuild("preview-001", "", "draft-001", "artifact-001", "dev", null, "", "", "", "", "", "", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new TitanGraphqlOperationRegistry.RegisteredOperation("operation-001", "", "sha", "query", null, List.of(), List.of(), -1, 0, List.of(), "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> usageReport("usage-001", "model-001", "dev", "2026.06.01", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> usageReport("usage-001", "model-001", "dev", "2026.06.01", "PT24H", -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new TitanGraphqlUsageReport.OperationUsage(
                "operation-001", "", "sha", "", "", "", -1, 0
        ));
        assertThrows(IllegalArgumentException.class, () -> new TitanGraphqlDeployment("deployment-001", "model-001", "", "artifact-001", "dev", null, "", "", "", ""));
    }

    @Test
    void storesManagementObjectsInInsertionOrderForFocusedTests() {
        TitanGraphqlInMemoryManagementStore store = new TitanGraphqlInMemoryManagementStore()
                .saveWorkspace(new TitanGraphqlManagedWorkspace("workspace-001", "platform", "", "dev", "", ""))
                .saveWorkspace(new TitanGraphqlManagedWorkspace("workspace-002", "product", "", "dev", "", ""))
                .saveModel(new TitanGraphqlManagedModel("model-001", "workspace-001", "demo-blog", "", "", "", "", "", "", "", ""))
                .saveDraft(new TitanGraphqlModelDraft("draft-001", "model-001", TitanGraphqlModelDraft.ModelDraftStatus.IMPORTED, TitanGraphqlModelDraft.SourceFormat.YAML, "source", "{}", "sha", "", "", "", "", "", ""))
                .saveValidationReport(new TitanGraphqlValidationReportRef("validation-001", "draft-001", TitanGraphqlValidationReportRef.ValidationReportStatus.WARN, "warning", 0, 1, 0, false, ""))
                .saveArtifactSet(new TitanGraphqlArtifactSetRef("artifact-001", "draft-001", "semantic-sha", "", "", "", "", "", "", "", ""))
                .saveDriftReport(new TitanGraphqlDriftReportRef("drift-001", "draft-001", TitanGraphqlDriftReportRef.DriftReportStatus.WARNING, "", "warning", 1, false, ""))
                .savePreviewBuild(new TitanGraphqlPreviewBuild("preview-001", "model-001", "draft-001", "artifact-001", "dev", TitanGraphqlPreviewBuild.PreviewBuildStatus.READY, "/preview/preview-001/graphql", "", "", "", "", "", "", "", ""))
                .savePreviewContractTestReport(new TitanGraphqlPreviewContractTestReport(
                        "contract-test-001",
                        "preview-001",
                        "artifact-001",
                        "registry-001",
                        TitanGraphqlPreviewContractTestReport.ContractTestReportStatus.PASS,
                        1,
                        1,
                        0,
                        0,
                        List.of(),
                        "2026-06-01T19:42:00Z"
                ))
                .saveObservedOperation(TitanGraphqlObservedOperation.observed(
                        "model-001",
                        "dev",
                        "viewer",
                        "portal",
                        "operation-sha",
                        "ArticleById",
                        "query ArticleById { article(id: 1) { id } }",
                        2,
                        3,
                        List.of("Article.id"),
                        "2026-06-01T19:43:00Z"
                ))
                .saveOperationRegistry(new TitanGraphqlOperationRegistry("registry-001", "model-001", "dev", TitanGraphqlOperationRegistry.RegistryMode.WARN, List.of(), ""))
                .saveUsageReport(usageReport("usage-001", "model-001", "dev", "2026.06.01", "PT24H", 1, 10))
                .saveUsageReport(usageReport("usage-002", "model-001", "staging", "2026.06.01", "PT24H", 1, 11))
                .saveUsageReport(usageReport("usage-003", "model-001", "dev", "2026.06.02", "PT24H", 1, 12))
                .saveDeployment(new TitanGraphqlDeployment("deployment-001", "model-001", "draft-001", "artifact-001", "dev", TitanGraphqlDeployment.DeploymentStatus.ACTIVE, "", "", "", ""));

        assertEquals(List.of("workspace-001", "workspace-002"), store.workspaces().stream()
                .map(TitanGraphqlManagedWorkspace::id)
                .toList());
        assertEquals("demo-blog", store.model("model-001").name());
        assertEquals(TitanGraphqlModelDraft.ModelDraftStatus.IMPORTED, store.draft("draft-001").status());
        assertEquals(TitanGraphqlValidationReportRef.ValidationReportStatus.WARN, store.validationReport("validation-001").status());
        assertEquals("semantic-sha", store.artifactSet("artifact-001").semanticHash());
        assertEquals("warning", store.driftReport("drift-001").summary());
        assertEquals(TitanGraphqlPreviewBuild.PreviewBuildStatus.READY, store.previewBuild("preview-001").status());
        assertEquals(TitanGraphqlPreviewContractTestReport.ContractTestReportStatus.PASS, store.previewContractTestReport("contract-test-001").status());
        assertEquals(1, store.previewContractTestReports("preview-001").size());
        assertEquals("operation-sha", store.observedOperations("model-001", "dev").getFirst().operationHash());
        assertEquals(TitanGraphqlOperationRegistry.RegistryMode.WARN, store.operationRegistry("registry-001").mode());
        assertEquals("summary", store.usageReport("usage-001").summary());
        assertEquals(List.of("usage-001", "usage-003"), store.usageReports("model-001", "dev").stream()
                .map(TitanGraphqlUsageReport::id)
                .toList());
        assertEquals(List.of("usage-001"), store.usageReports("model-001", "dev", "2026.06.01", "PT24H").stream()
                .map(TitanGraphqlUsageReport::id)
                .toList());
        assertEquals(TitanGraphqlDeployment.DeploymentStatus.ACTIVE, store.deployment("deployment-001").status());
        assertThrows(UnsupportedOperationException.class, () -> store.workspaces().clear());
    }

    @Test
    void rendersDeterministicUsageReportMetadata() {
        TitanGraphqlUsageReport report = usageReport("usage-001", "model-001", "prod", "2026.06.01", "P7D", 2, 99);
        String json = TitanGraphqlUsageReportJson.reportJson(report);

        assertEquals(json, TitanGraphqlUsageReportJson.reportJson(report));
        assertTrue(json.contains("\"environment\":\"prod\""));
        assertTrue(json.contains("\"operationHash\":\"operation-sha\""));
        assertTrue(json.contains("\"deprecatedFieldsInUse\""));
        assertTrue(json.contains("\"policyRejections\""));
        assertTrue(json.contains("\"slowOperations\""));
        assertTrue(json.indexOf("\"clients\"") < json.indexOf("\"deprecatedFieldsInUse\""));
        assertThrows(UnsupportedOperationException.class, () -> report.operations().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.fields().clear());
    }

    @Test
    void rendersDeterministicPreviewContractTestReportMetadata() {
        TitanGraphqlPreviewContractTestReport report = new TitanGraphqlPreviewContractTestReport(
                "contract-test-preview-001",
                "preview-001",
                "artifact-001",
                "registry-001",
                TitanGraphqlPreviewContractTestReport.ContractTestReportStatus.FAIL,
                2,
                1,
                1,
                0,
                List.of(
                        new TitanGraphqlPreviewContractTestReport.OperationResult(
                                "operation-pass",
                                "PreviewArticle",
                                "operation-sha",
                                TitanGraphqlPreviewContractTestReport.ContractTestOperationStatus.PASS,
                                "response-sha",
                                "preview execution completed without GraphQL errors"
                        ),
                        new TitanGraphqlPreviewContractTestReport.OperationResult(
                                "operation-fail",
                                "BrokenPreviewArticle",
                                "broken-sha",
                                TitanGraphqlPreviewContractTestReport.ContractTestOperationStatus.FAIL,
                                "error-sha",
                                "preview execution returned GraphQL errors"
                        )
                ),
                "2026-06-01T19:42:00Z"
        );
        String json = TitanGraphqlPreviewContractTestReportJson.reportJson(report);

        assertEquals(json, TitanGraphqlPreviewContractTestReportJson.reportJson(report));
        assertTrue(json.contains("\"previewBuildId\":\"preview-001\""));
        assertTrue(json.contains("\"artifactSetId\":\"artifact-001\""));
        assertTrue(json.contains("\"failedOperations\":1"));
        assertTrue(json.contains("\"operationHash\":\"operation-sha\""));
        assertTrue(json.indexOf("\"artifactSetId\"") < json.indexOf("\"createdAt\""));
        assertThrows(UnsupportedOperationException.class, () -> report.operationResults().clear());
    }

    @Test
    void rendersDeterministicPreviewBuildManifestForArtifactCandidate() {
        TitanGraphqlPreviewBuild previewBuild = new TitanGraphqlPreviewBuild(
                "preview-001",
                "model-001",
                "draft-001",
                "artifact-set-001",
                "staging",
                TitanGraphqlPreviewBuild.PreviewBuildStatus.READY,
                "/preview/preview-001/graphql",
                "/admin/preview/preview-001",
                "schema-sha",
                "build/generated/titan-graphql/manifest.json",
                "manifest-sha",
                "registry-001",
                "2026-06-02T14:38:00Z",
                "test-operator",
                "2026-06-01T14:38:00Z"
        );
        TitanGraphqlArtifactSet artifactSet = new TitanGraphqlArtifactSet(
                "artifact-set-001",
                "draft-001",
                "demo-blog",
                "2026.06.01",
                "semantic-sha",
                "validation-sha",
                "drift-sha",
                "sdl-sha",
                "introspection-sha",
                "conformance-sha",
                "sql-sha",
                "build/generated/titan-graphql",
                "staging-review",
                "2026-06-01T14:36:00Z",
                List.of(new TitanGraphqlArtifactManifestEntry(
                        TitanGraphqlArtifactKind.GENERATED_SCHEMA_SDL,
                        "generatedSchemaSdl",
                        true,
                        "build/generated/titan-graphql/schema.graphql",
                        "sdl-sha"
                ))
        );

        TitanGraphqlPreviewBuildManifest manifest = TitanGraphqlPreviewBuildManifest.fromPreviewBuild(
                previewBuild,
                artifactSet
        );
        String json = TitanGraphqlPreviewBuildManifestJson.manifestJson(manifest);

        assertEquals("preview-001", manifest.id());
        assertEquals("artifact-set-001", manifest.artifactSetId());
        assertEquals("staging", manifest.environment());
        assertEquals("schema-sha", manifest.schemaHash());
        assertEquals("build/generated/titan-graphql/manifest.json", manifest.artifactManifestPath());
        assertEquals("manifest-sha", manifest.artifactManifestHash());
        assertEquals("/preview/preview-001/graphql", manifest.endpoint().graphqlEndpoint());
        assertTrue(manifest.endpoint().unstable());
        assertTrue(manifest.expiration().expires());
        assertEquals(json, TitanGraphqlPreviewBuildManifestJson.manifestJson(manifest));
        assertTrue(json.contains("\"artifactSetId\":\"artifact-set-001\""));
        assertTrue(json.contains("\"environment\":\"staging\""));
        assertTrue(json.contains("\"graphqlEndpoint\":\"/preview/preview-001/graphql\""));
        assertTrue(json.contains("\"schemaHash\":\"schema-sha\""));
        assertTrue(json.indexOf("\"artifactManifestHash\"") < json.indexOf("\"artifactManifestPath\""));

        TitanGraphqlPreviewBuild implicitManifestPathPreviewBuild = new TitanGraphqlPreviewBuild(
                "preview-implicit",
                "model-001",
                "draft-001",
                "artifact-set-001",
                "staging",
                TitanGraphqlPreviewBuild.PreviewBuildStatus.READY,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                ""
        );
        assertEquals(
                "build/generated/titan-graphql/manifest.json",
                TitanGraphqlPreviewBuildManifest.fromPreviewBuild(
                        implicitManifestPathPreviewBuild,
                        artifactSet
                ).artifactManifestPath()
        );

        TitanGraphqlPreviewBuild mismatchedPreviewBuild = new TitanGraphqlPreviewBuild(
                "preview-002",
                "model-001",
                "draft-002",
                "artifact-set-001",
                "staging",
                TitanGraphqlPreviewBuild.PreviewBuildStatus.READY,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                ""
        );
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlPreviewBuildManifest.fromPreviewBuild(
                mismatchedPreviewBuild,
                artifactSet
        ));
    }

    @Test
    void exposesManagementDescriptorsThroughProjectionSchemaMachinery() {
        String schema = TitanGraphqlManagementProjection.projectionModel().schemaDefinition();

        assertTrue(schema.contains("type Query {"));
        assertTrue(schema.contains("workspaces(first: Int, after: String, last: Int, before: String"));
        assertTrue(schema.contains("model(id: Int!): ManagedGraphqlModel"));
        assertTrue(schema.contains("type ManagedWorkspace {"));
        assertTrue(schema.contains("type ModelDraft {"));
        assertTrue(schema.contains("type ValidationReport {"));
        assertTrue(schema.contains("type ArtifactSet {"));
        assertTrue(schema.contains("type DriftReport {"));
        assertTrue(schema.contains("type Deployment {"));
        assertTrue(schema.contains("type PreviewBuild {"));
        assertTrue(schema.contains("observedOperations(first: Int, after: String, last: Int, before: String"));
        assertTrue(schema.contains("observedOperation(id: Int!): ObservedOperation"));
        assertTrue(schema.contains("type ObservedOperation {"));
        assertTrue(schema.contains("type OperationRegistry {"));
        assertTrue(schema.contains("type UsageReport {"));
        assertTrue(schema.contains("version: String"));
        assertTrue(schema.contains("totalOperations: String"));
        assertTrue(schema.contains("deprecatedFieldsInUse: String"));
    }

    private static TitanGraphqlUsageReport usageReport(
            String id,
            String modelId,
            String environment,
            String version,
            String window,
            int totalOperations,
            int totalRequests
    ) {
        return new TitanGraphqlUsageReport(
                id,
                modelId,
                environment,
                version,
                window,
                "summary",
                totalOperations,
                totalRequests,
                List.of(new TitanGraphqlUsageReport.OperationUsage(
                        "operation-001",
                        "ArticleById",
                        "operation-sha",
                        "portal",
                        "viewer",
                        version,
                        totalRequests,
                        0
                )),
                List.of(new TitanGraphqlUsageReport.FieldUsage(
                        "Article.id",
                        totalRequests,
                        totalOperations,
                        "2026-06-01T20:22:00Z"
                )),
                List.of(new TitanGraphqlUsageReport.DimensionUsage("viewer", totalRequests, totalOperations)),
                List.of(new TitanGraphqlUsageReport.DimensionUsage("portal", totalRequests, totalOperations)),
                List.of(new TitanGraphqlUsageReport.DimensionUsage(environment, totalRequests, totalOperations)),
                List.of(new TitanGraphqlUsageReport.DimensionUsage(version, totalRequests, totalOperations)),
                List.of(new TitanGraphqlUsageReport.DeprecatedFieldUsage(
                        "Article.legacySlug",
                        "2026.04.01",
                        1,
                        "2026-06-01T20:22:00Z"
                )),
                List.of(new TitanGraphqlUsageReport.UnusedField("Article.titleLength", "no requests observed")),
                List.of(new TitanGraphqlUsageReport.PolicyRejection(
                        "viewerArticlePolicy",
                        "Article.draftNotes",
                        "viewer",
                        "portal",
                        1
                )),
                List.of(new TitanGraphqlUsageReport.SlowOperation(
                        "operation-001",
                        "ArticleById",
                        "operation-sha",
                        120,
                        240,
                        totalRequests
                )),
                "2026-06-01T20:22:00Z"
        );
    }
}
