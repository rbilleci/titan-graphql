package io.titan.graphql.management;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.titan.graphql.artifact.TitanGraphqlArtifactKind;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.management.ManagementAudit.AuditRecord;
import io.titan.management.ManagementCommands.CommandInvocation;
import io.titan.management.ManagementIdempotency.IdempotencyRecord;
import io.titan.management.ManagementRecords.ArtifactRef;
import io.titan.management.ManagementRecords.Deployment;
import io.titan.management.ManagementRecords.DeploymentStatus;
import io.titan.management.ManagementRecords.Draft;
import io.titan.management.ManagementRecords.DraftStatus;
import io.titan.management.ManagementRecords.VerificationStatus;
import io.titan.management.ManagementTransactions.DeploymentActivationExecution;
import io.titan.management.ManagementTransactions.DeploymentActivationRequest;
import io.titan.management.ManagementTransactions.FileTransactionalMutationStore;
import io.titan.management.ManagementTransactions.TransactionalCommandExecution;
import io.titan.management.ManagementTransactions.TransactionalCommandResult;
import io.titan.management.ManagementTransactions.TransactionalMutationStore;
import io.titan.graphql.artifact.TitanGraphqlEntryPointRef;
import io.titan.graphql.artifact.TitanGraphqlRollbackScriptRef;
import io.titan.graphql.artifact.TitanGraphqlVerificationDiagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// TG-BLK-003 (CLOSED): durable JDBC management store available; durability depends on the injected
// TransactionalMutationStore. Core dogfooded the management store — it now ships
// JdbcTransactionalMutationStore over Titan-transpiled routines (+ JDBC idempotency/audit stores and
// ManagementSchemaInstaller), proven durable + concurrent on PG 16 + MySQL 8.4. This store is built
// over that JDBC store in the opt-in jdbc mode (titan.graphql.management.store=jdbc, wired by
// TitanGraphqlManagementStoreFactory) — "durable" then means a real JDBC store on transpiled
// routines. When built over FileTransactionalMutationStore (the file/in-memory DEFAULT) "durable"
// means the file-backed, single-process boundary. Two recorded routine-design gaps are known and
// non-blocking: the adapter performs the typed activate_deployment precondition in-transaction (the
// void routine cannot return a typed failure), and passes the canonical input hash for the import
// routine's single collapsed hash column. See docs/titan-blocker-register.md TG-BLK-003.
public final class TitanGraphqlDurableManagementStore implements TitanGraphqlManagementStore {
    private static final Instant DEFAULT_INSTANT = Instant.EPOCH;

    private final TitanGraphqlInMemoryManagementStore graphQlStore = new TitanGraphqlInMemoryManagementStore();
    private final Map<String, TitanGraphqlArtifactEvidenceRef> artifactEvidenceByArtifactSetId = new LinkedHashMap<>();
    private final TransactionalMutationStore titanStore;
    private final ProductStateJournal productStateJournal;

    public TitanGraphqlDurableManagementStore(Path transactionLogPath) {
        this(
                new FileTransactionalMutationStore(transactionLogPath),
                new ProductStateJournal(productStateLogPath(transactionLogPath))
        );
    }

    public TitanGraphqlDurableManagementStore(TransactionalMutationStore titanStore) {
        this(titanStore, null);
    }

    private TitanGraphqlDurableManagementStore(
            TransactionalMutationStore titanStore,
            ProductStateJournal productStateJournal
    ) {
        this.titanStore = titanStore;
        this.productStateJournal = productStateJournal;
        if (productStateJournal != null) {
            productStateJournal.loadInto(graphQlStore, artifactEvidenceByArtifactSetId);
        }
    }

    public TransactionalMutationStore titanStore() {
        return titanStore;
    }

    public List<AuditRecord> auditRecords() {
        return titanStore.auditRecords();
    }

    public List<IdempotencyRecord> idempotencyRecords() {
        return titanStore.idempotencyRecords();
    }

    public List<Draft> titanDrafts() {
        return titanStore.drafts();
    }

    public List<ArtifactRef> titanArtifactRefs() {
        return titanStore.artifactRefs();
    }

    public List<Deployment> titanDeployments() {
        return titanStore.deployments();
    }

    public TransactionalCommandExecution importModelDocument(
            CommandInvocation invocation,
            TitanGraphqlManagedModel model,
            TitanGraphqlModelDraft draft,
            TitanGraphqlValidationReportRef validationReport,
            Instant attemptAt,
            Instant outcomeAt
    ) {
        TransactionalCommandExecution execution = titanStore.execute(invocation, (command, transaction) -> {
            Draft titanDraft = toTitanDraft(draft, model.workspaceId());
            // The file store stages the draft on the in-process TransactionContext; the JDBC store
            // (jdbc mode) passes a null transaction because its Titan-transpiled import routine is the
            // durable authority that upserts the draft server-side from the invocation + this result's
            // resultRef. Guard the staging call so the same handler is faithful over BOTH stores.
            if (transaction != null) {
                transaction.putDraft(titanDraft);
            }
            return TransactionalCommandResult.success("sha256:" + sha256(titanDraft.stableJson()), titanDraft.id());
        }, attemptAt, outcomeAt);
        if (execution.success()) {
            graphQlStore.saveModel(model);
            graphQlStore.saveDraft(draft);
            if (validationReport != null) {
                graphQlStore.saveValidationReport(validationReport);
            }
            appendProductState("model", model);
            appendProductState("draft", draft);
            if (validationReport != null) {
                appendProductState("validationReport", validationReport);
            }
        }
        return execution;
    }

    public TransactionalCommandExecution importModelDocument(
            CommandInvocation invocation,
            TitanGraphqlModelDraft draft,
            Instant attemptAt,
            Instant outcomeAt
    ) {
        TitanGraphqlManagedModel model = graphQlStore.model(draft.modelId());
        if (model == null) {
            throw new IllegalArgumentException("durable import requires a known GraphQL model wrapper");
        }
        return importModelDocument(invocation, model, draft, graphQlStore.validationReport(draft.validationReportId()),
                attemptAt, outcomeAt);
    }

    @Override
    public TitanGraphqlDurableManagementStore saveWorkspace(TitanGraphqlManagedWorkspace workspace) {
        graphQlStore.saveWorkspace(workspace);
        appendProductState("workspace", workspace);
        return this;
    }

    @Override
    public TitanGraphqlDurableManagementStore saveModel(TitanGraphqlManagedModel model) {
        graphQlStore.saveModel(model);
        appendProductState("model", model);
        return this;
    }

    @Override
    public TitanGraphqlDurableManagementStore saveDraft(TitanGraphqlModelDraft draft) {
        graphQlStore.saveDraft(draft);
        titanStore.seedDraft(toTitanDraft(draft));
        appendProductState("draft", draft);
        return this;
    }

    @Override
    public TitanGraphqlDurableManagementStore saveValidationReport(TitanGraphqlValidationReportRef report) {
        graphQlStore.saveValidationReport(report);
        appendProductState("validationReport", report);
        return this;
    }

    @Override
    public TitanGraphqlDurableManagementStore saveArtifactSet(TitanGraphqlArtifactSetRef artifactSet) {
        graphQlStore.saveArtifactSet(artifactSet);
        appendProductState("artifactSet", artifactSet);
        return this;
    }

    public TitanGraphqlDurableManagementStore saveArtifactSet(
            TitanGraphqlArtifactSetRef artifactSet,
            TitanGraphqlGap005ArtifactMetadata metadata
    ) {
        graphQlStore.saveArtifactSet(artifactSet);
        titanStore.seedArtifactRef(toTitanArtifactRef(artifactSet, metadata));
        appendProductState("artifactSet", artifactSet);
        TitanGraphqlArtifactEvidenceRef evidence = new TitanGraphqlArtifactEvidenceRef(
                artifactSet.id(),
                metadata.artifactId(),
                metadata.verificationStatus(),
                metadata.verificationDiagnostics(),
                metadata.entryPoints(),
                metadata.rollbackScripts());
        artifactEvidenceByArtifactSetId.put(evidence.artifactSetId(), evidence);
        appendProductState("artifactEvidence", evidence);
        return this;
    }

    /** The recorded core package evidence for an artifact set, or {@code null} when none was recorded. */
    public TitanGraphqlArtifactEvidenceRef artifactEvidence(String artifactSetId) {
        return artifactEvidenceByArtifactSetId.get(artifactSetId);
    }

    /**
     * Rollback surface for a deployment: the per-dialect refs to the real
     * {@code titan-rollback.<dialect>.sql} packaged with the deployment's artifact set. Each ref
     * carries the manifest's authoritative statement count and raw-byte sha256 plus its
     * {@link TitanGraphqlRollbackScriptRef#status() integrity status} — a script whose on-disk
     * bytes drifted from the manifest reads as {@code DRIFTED}, not a clean present, so callers
     * must consult the status rather than {@code present()} alone before trusting the script.
     */
    public List<TitanGraphqlRollbackScriptRef> deploymentRollbackScripts(String deploymentId) {
        return requireDeploymentEvidence(deploymentId).rollbackScripts();
    }

    /** The packaged stored-function entry points behind a deployment (manifest + inventory truth). */
    public List<TitanGraphqlEntryPointRef> deploymentEntryPoints(String deploymentId) {
        return requireDeploymentEvidence(deploymentId).entryPoints();
    }

    private TitanGraphqlArtifactEvidenceRef requireDeploymentEvidence(String deploymentId) {
        TitanGraphqlDeployment deployment = graphQlStore.deployment(deploymentId);
        if (deployment == null) {
            throw new IllegalArgumentException("unknown deployment '" + deploymentId + "'");
        }
        TitanGraphqlArtifactEvidenceRef evidence = artifactEvidenceByArtifactSetId.get(deployment.artifactSetId());
        if (evidence == null) {
            throw new IllegalArgumentException(
                    "no Titan GAP-005 artifact evidence recorded for artifact set '"
                            + deployment.artifactSetId() + "' of deployment '" + deploymentId + "'");
        }
        return evidence;
    }

    @Override
    public TitanGraphqlDurableManagementStore saveDriftReport(TitanGraphqlDriftReportRef report) {
        graphQlStore.saveDriftReport(report);
        appendProductState("driftReport", report);
        return this;
    }

    @Override
    public TitanGraphqlDurableManagementStore savePreviewBuild(TitanGraphqlPreviewBuild previewBuild) {
        graphQlStore.savePreviewBuild(previewBuild);
        appendProductState("previewBuild", previewBuild);
        return this;
    }

    public TitanGraphqlPreviewBuildManifest saveVerifiedPreviewBuild(TitanGraphqlPreviewBuild previewBuild) {
        ArtifactRef artifactRef = requirePassedArtifactRef(previewBuild.artifactSetId());
        TitanGraphqlArtifactSetRef graphQlArtifactSet = requireGraphqlArtifactSet(previewBuild.artifactSetId());
        requireSameHash(
                normalizeHash(graphQlArtifactSet.generatedSqlHash(), "GraphQL artifact generated SQL hash"),
                artifactRef.artifactHash(),
                "preview build artifact hash");
        if (!previewBuild.schemaHash().isBlank()) {
            requireSameHash(
                    normalizeHash(previewBuild.schemaHash(), "preview build schema hash"),
                    normalizeHash(graphQlArtifactSet.sdlHash(), "GraphQL artifact SDL hash"),
                    "preview build schema hash");
        }
        String manifestPath = previewBuild.artifactManifestPath().isBlank()
                ? artifactRef.manifestPath()
                : previewBuild.artifactManifestPath();
        if (!manifestPath.equals(artifactRef.manifestPath())) {
            throw new IllegalArgumentException("preview build artifact manifest path does not match Titan GAP-005 metadata");
        }
        String manifestHash = previewBuild.artifactManifestHash().isBlank()
                ? artifactRef.manifestContentHash()
                : normalizeHash(previewBuild.artifactManifestHash(), "preview build artifact manifest hash");
        requireSameHash(manifestHash, artifactRef.manifestContentHash(), "preview build artifact manifest hash");
        TitanGraphqlPreviewBuild verified = new TitanGraphqlPreviewBuild(
                previewBuild.id(),
                previewBuild.modelId(),
                previewBuild.draftId(),
                previewBuild.artifactSetId(),
                previewBuild.environment(),
                previewBuild.status(),
                previewBuild.previewEndpoint(),
                previewBuild.previewConsoleUrl(),
                previewBuild.schemaHash(),
                manifestPath,
                stripHashPrefix(manifestHash),
                previewBuild.operationRegistryId(),
                previewBuild.expiresAt(),
                previewBuild.createdBy(),
                previewBuild.createdAt());
        graphQlStore.savePreviewBuild(verified);
        appendProductState("previewBuild", verified);
        return TitanGraphqlPreviewBuildManifest.fromVerifiedPreviewBuild(
                verified,
                graphQlArtifactSet,
                artifactRef.manifestPath(),
                stripHashPrefix(artifactRef.manifestContentHash()));
    }

    @Override
    public TitanGraphqlDurableManagementStore savePreviewContractTestReport(TitanGraphqlPreviewContractTestReport report) {
        graphQlStore.savePreviewContractTestReport(report);
        appendProductState("previewContractTestReport", report);
        return this;
    }

    @Override
    public TitanGraphqlDurableManagementStore saveObservedOperation(TitanGraphqlObservedOperation operation) {
        graphQlStore.saveObservedOperation(operation);
        appendProductState("observedOperation", operation);
        return this;
    }

    @Override
    public TitanGraphqlDurableManagementStore observeOperation(TitanGraphqlObservedOperation operation) {
        graphQlStore.observeOperation(operation);
        appendProductState("observedOperation", graphQlStore.observedOperation(operation.id()));
        return this;
    }

    @Override
    public TitanGraphqlObservedOperation approveObservedOperation(String observedOperationId, String approvedBy, String approvedAt) {
        TitanGraphqlObservedOperation operation = graphQlStore.approveObservedOperation(observedOperationId, approvedBy, approvedAt);
        appendProductState("observedOperation", operation);
        appendOperationRegistry(operation.modelId(), operation.environment());
        return operation;
    }

    @Override
    public TitanGraphqlObservedOperation rejectObservedOperation(String observedOperationId, String rejectedBy, String rejectedAt) {
        TitanGraphqlObservedOperation operation = graphQlStore.rejectObservedOperation(observedOperationId, rejectedBy, rejectedAt);
        appendProductState("observedOperation", operation);
        appendOperationRegistry(operation.modelId(), operation.environment());
        return operation;
    }

    @Override
    public TitanGraphqlDurableManagementStore saveOperationRegistry(TitanGraphqlOperationRegistry registry) {
        graphQlStore.saveOperationRegistry(registry);
        appendProductState("operationRegistry", registry);
        return this;
    }

    @Override
    public TitanGraphqlDurableManagementStore saveUsageReport(TitanGraphqlUsageReport report) {
        graphQlStore.saveUsageReport(report);
        appendProductState("usageReport", report);
        return this;
    }

    @Override
    public TitanGraphqlDurableManagementStore saveDeployment(TitanGraphqlDeployment deployment) {
        if (deployment.status() == TitanGraphqlDeployment.DeploymentStatus.ACTIVE) {
            throw new IllegalArgumentException("durable active deployment requires Titan GAP-006 activation");
        }
        graphQlStore.saveDeployment(deployment);
        TitanGraphqlManagedModel model = graphQlStore.model(deployment.modelId());
        if (model != null) {
            titanStore.seedDeployment(toTitanDeployment(deployment, model.workspaceId()));
        }
        appendProductState("deployment", deployment);
        return this;
    }

    public DeploymentActivationExecution activateDeployment(
            TitanGraphqlDeployment deployment,
            String actorId,
            String actorRole,
            String requestId,
            String idempotencyKey,
            Instant attemptAt,
            Instant outcomeAt
    ) {
        TitanGraphqlManagedModel model = graphQlStore.model(deployment.modelId());
        if (model == null) {
            throw new IllegalArgumentException("durable deployment requires a known GraphQL model wrapper");
        }
        // Activation consumes the real titan-install-verification.json evidence: anything but
        // a passed verification is refused with core's actual status and diagnostic.
        ArtifactRef artifactRef = requirePassedArtifactRef(deployment.artifactSetId());
        DeploymentActivationExecution execution = titanStore.activateDeployment(
                new DeploymentActivationRequest(
                        actorId,
                        actorRole,
                        model.workspaceId(),
                        requestId,
                        idempotencyKey,
                        deployment.id(),
                        deployment.artifactSetId(),
                        deployment.environment(),
                        artifactRef.artifactHash(),
                        artifactRef.packageMode(),
                        artifactRef.dialect(),
                        artifactRef.manifestContentHash(),
                        artifactRef.objectInventoryHash(),
                        artifactRef.installPlanHash(),
                        artifactRef.installVerificationHash(),
                        artifactRef.sourceInputsHash()),
                attemptAt,
                outcomeAt);
        if (execution.success()) {
            TitanGraphqlDeployment active = new TitanGraphqlDeployment(
                    deployment.id(),
                    deployment.modelId(),
                    deployment.draftId(),
                    deployment.artifactSetId(),
                    deployment.environment(),
                    TitanGraphqlDeployment.DeploymentStatus.ACTIVE,
                    actorRole,
                    execution.deployment().activatedAt().toString(),
                    deployment.rollbackTargetDeploymentId(),
                    deployment.runtimeHealthId());
            graphQlStore.saveDeployment(active);
            appendProductState("deployment", active);
        }
        return execution;
    }

    @Override
    public TitanGraphqlManagedWorkspace workspace(String id) {
        return graphQlStore.workspace(id);
    }

    @Override
    public TitanGraphqlManagedModel model(String id) {
        return graphQlStore.model(id);
    }

    @Override
    public TitanGraphqlModelDraft draft(String id) {
        TitanGraphqlModelDraft graphQlDraft = graphQlStore.draft(id);
        if (graphQlDraft != null) {
            return graphQlDraft;
        }
        return titanStore.draft(id).map(this::fromTitanDraft).orElse(null);
    }

    @Override
    public TitanGraphqlValidationReportRef validationReport(String id) {
        return graphQlStore.validationReport(id);
    }

    @Override
    public TitanGraphqlArtifactSetRef artifactSet(String id) {
        return graphQlStore.artifactSet(id);
    }

    @Override
    public TitanGraphqlDriftReportRef driftReport(String id) {
        return graphQlStore.driftReport(id);
    }

    @Override
    public TitanGraphqlPreviewBuild previewBuild(String id) {
        return graphQlStore.previewBuild(id);
    }

    @Override
    public TitanGraphqlPreviewContractTestReport previewContractTestReport(String id) {
        return graphQlStore.previewContractTestReport(id);
    }

    @Override
    public TitanGraphqlObservedOperation observedOperation(String id) {
        return graphQlStore.observedOperation(id);
    }

    @Override
    public TitanGraphqlOperationRegistry operationRegistry(String id) {
        return graphQlStore.operationRegistry(id);
    }

    @Override
    public TitanGraphqlUsageReport usageReport(String id) {
        return graphQlStore.usageReport(id);
    }

    @Override
    public TitanGraphqlDeployment deployment(String id) {
        return graphQlStore.deployment(id);
    }

    @Override
    public List<TitanGraphqlManagedWorkspace> workspaces() {
        return graphQlStore.workspaces();
    }

    @Override
    public List<TitanGraphqlManagedModel> models() {
        return graphQlStore.models();
    }

    @Override
    public List<TitanGraphqlModelDraft> drafts() {
        List<TitanGraphqlModelDraft> graphQlDrafts = graphQlStore.drafts();
        if (graphQlDrafts.isEmpty() == false) {
            return graphQlDrafts;
        }
        return titanStore.drafts().stream().map(this::fromTitanDraft).toList();
    }

    @Override
    public List<TitanGraphqlValidationReportRef> validationReports() {
        return graphQlStore.validationReports();
    }

    @Override
    public List<TitanGraphqlArtifactSetRef> artifactSets() {
        return graphQlStore.artifactSets();
    }

    @Override
    public List<TitanGraphqlDeployment> deployments() {
        return graphQlStore.deployments();
    }

    @Override
    public List<TitanGraphqlObservedOperation> observedOperations() {
        return graphQlStore.observedOperations();
    }

    @Override
    public List<TitanGraphqlObservedOperation> observedOperations(String modelId, String environment) {
        return graphQlStore.observedOperations(modelId, environment);
    }

    @Override
    public List<TitanGraphqlObservedOperation> observedOperations(String modelId, String environment, String role) {
        return graphQlStore.observedOperations(modelId, environment, role);
    }

    @Override
    public List<TitanGraphqlUsageReport> usageReports() {
        return graphQlStore.usageReports();
    }

    @Override
    public List<TitanGraphqlUsageReport> usageReports(String modelId, String environment) {
        return graphQlStore.usageReports(modelId, environment);
    }

    @Override
    public List<TitanGraphqlUsageReport> usageReports(String modelId, String environment, String version, String window) {
        return graphQlStore.usageReports(modelId, environment, version, window);
    }

    @Override
    public List<TitanGraphqlPreviewContractTestReport> previewContractTestReports(String previewBuildId) {
        return graphQlStore.previewContractTestReports(previewBuildId);
    }

    private Draft toTitanDraft(TitanGraphqlModelDraft draft) {
        TitanGraphqlManagedModel model = graphQlStore.model(draft.modelId());
        if (model == null) {
            throw new IllegalArgumentException("durable draft requires a known GraphQL model wrapper");
        }
        return toTitanDraft(draft, model.workspaceId());
    }

    private Draft toTitanDraft(TitanGraphqlModelDraft draft, String workspaceId) {
        return new Draft(
                draft.id(),
                workspaceId,
                draft.modelId(),
                1L,
                toTitanDraftStatus(draft.status()),
                normalizeHash(draft.semanticHash(), "draft semantic hash"),
                instantOrDefault(draft.createdAt()),
                instantOrDefault(draft.updatedAt()).isBefore(instantOrDefault(draft.createdAt()))
                        ? instantOrDefault(draft.createdAt())
                        : instantOrDefault(draft.updatedAt()),
                java.util.Map.of());
    }

    private TitanGraphqlModelDraft fromTitanDraft(Draft draft) {
        String hash = stripHashPrefix(draft.documentHash());
        return new TitanGraphqlModelDraft(
                draft.id(),
                draft.modelId(),
                fromTitanDraftStatus(draft.status()),
                TitanGraphqlModelDraft.SourceFormat.YAML,
                "",
                "",
                hash,
                "",
                "",
                "",
                "",
                draft.createdAt().toString(),
                draft.updatedAt().toString());
    }

    private static ArtifactRef toTitanArtifactRef(
            TitanGraphqlArtifactSetRef artifactSet,
            TitanGraphqlGap005ArtifactMetadata metadata
    ) {
        String dialect = metadata.dialects().getFirst();
        return new ArtifactRef(
                artifactSet.id(),
                stableArtifactId(metadata.artifactId()),
                normalizeHash(artifactSet.generatedSqlHash(), "generated SQL hash"),
                metadata.artifacts().stream()
                        .filter(artifact -> artifact.kind() == TitanGraphqlArtifactKind.TITAN_ARTIFACT_METADATA)
                        .findFirst()
                        .orElseThrow()
                        .path(),
                metadata.artifacts().stream()
                        .filter(artifact -> artifact.kind() == TitanGraphqlArtifactKind.TITAN_OBJECT_INVENTORY)
                        .findFirst()
                        .orElseThrow()
                        .path(),
                metadata.artifacts().stream()
                        .filter(artifact -> artifact.kind() == TitanGraphqlArtifactKind.TITAN_INSTALL_PLAN)
                        .findFirst()
                        .orElseThrow()
                        .path(),
                metadata.artifacts().stream()
                        .filter(artifact -> artifact.kind() == TitanGraphqlArtifactKind.TITAN_INSTALL_VERIFICATION)
                        .findFirst()
                        .orElseThrow()
                        .path(),
                toTitanVerificationStatus(metadata.verificationStatus()),
                metadata.packageMode(),
                dialect,
                normalizeHash(metadata.manifestContentHash(), "manifest content hash"),
                normalizeHash(metadata.inventoryContentHash(), "object inventory hash"),
                normalizeHash(metadata.installPlanContentHash(), "install plan hash"),
                normalizeHash(metadata.artifacts().stream()
                        .filter(artifact -> artifact.kind() == TitanGraphqlArtifactKind.TITAN_INSTALL_VERIFICATION)
                        .findFirst()
                        .orElseThrow()
                        .hash(), "install verification hash"),
                normalizeHash(metadata.sourceInputsHash(), "source inputs hash"));
    }

    private Deployment toTitanDeployment(TitanGraphqlDeployment deployment, String workspaceId) {
        return new Deployment(
                deployment.id(),
                workspaceId,
                deployment.artifactSetId(),
                deployment.environment(),
                toTitanDeploymentStatus(deployment.status()),
                instantOrDefault(deployment.deployedAt()),
                deployment.status() == TitanGraphqlDeployment.DeploymentStatus.ACTIVE
                        ? instantOrDefault(deployment.deployedAt())
                        : null);
    }

    private static DraftStatus toTitanDraftStatus(TitanGraphqlModelDraft.ModelDraftStatus status) {
        return switch (status) {
            case VALIDATED, READY_FOR_REVIEW, DEPLOYED -> DraftStatus.VALIDATED;
            case ARCHIVED -> DraftStatus.ARCHIVED;
            case EMPTY, IMPORTED, FAILED_VALIDATION -> DraftStatus.IMPORTED;
        };
    }

    private static TitanGraphqlModelDraft.ModelDraftStatus fromTitanDraftStatus(DraftStatus status) {
        return switch (status) {
            case IMPORTED -> TitanGraphqlModelDraft.ModelDraftStatus.IMPORTED;
            case VALIDATED -> TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED;
            case ARCHIVED -> TitanGraphqlModelDraft.ModelDraftStatus.ARCHIVED;
        };
    }

    private static VerificationStatus toTitanVerificationStatus(String status) {
        String normalized = ManagementSupport.requireText(status, "GAP-005 verification status")
                .toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "passed", "pass", "success" -> VerificationStatus.PASSED;
            case "failed", "fail", "failure" -> VerificationStatus.FAILED;
            default -> VerificationStatus.PENDING;
        };
    }

    private static DeploymentStatus toTitanDeploymentStatus(TitanGraphqlDeployment.DeploymentStatus status) {
        return switch (status) {
            case ACTIVE -> DeploymentStatus.ACTIVE;
            case FAILED -> DeploymentStatus.FAILED;
            case ROLLED_BACK -> DeploymentStatus.ROLLED_BACK;
            case PENDING, DEPLOYING -> DeploymentStatus.PENDING_ACTIVATION;
        };
    }

    private static Instant instantOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_INSTANT;
        }
        return Instant.parse(value);
    }

    private static String normalizeHash(String value, String fieldName) {
        String text = ManagementSupport.requireText(value, fieldName);
        if (text.matches("sha256:[0-9a-f]{64}")) {
            return text;
        }
        if (text.matches("[0-9a-f]{64}")) {
            return "sha256:" + text;
        }
        throw new IllegalArgumentException(fieldName + " must be a lowercase SHA-256 hash");
    }

    private static String stripHashPrefix(String value) {
        return value.startsWith("sha256:") ? value.substring("sha256:".length()) : value;
    }

    private ArtifactRef requirePassedArtifactRef(String artifactSetId) {
        ArtifactRef artifactRef = requireCompleteArtifactRef(artifactSetId);
        if (artifactRef.verificationStatus() != VerificationStatus.PASSED) {
            throw new IllegalArgumentException(
                    "Titan GAP-005 install verification must pass before preview or deployment readiness; "
                            + verificationRefusalReason(artifactSetId, artifactRef));
        }
        return artifactRef;
    }

    /**
     * The real refusal reason from the recorded {@code titan-install-verification.json}
     * evidence (raw status plus core's first diagnostic), falling back to the translated
     * status when only the titan-store artifact ref is available.
     */
    private String verificationRefusalReason(String artifactSetId, ArtifactRef artifactRef) {
        TitanGraphqlArtifactEvidenceRef evidence = artifactEvidenceByArtifactSetId.get(artifactSetId);
        if (evidence == null) {
            return "verification status is " + artifactRef.verificationStatus();
        }
        StringBuilder reason = new StringBuilder()
                .append("titan-install-verification.json status is '")
                .append(evidence.verificationStatus())
                .append("'");
        if (evidence.verificationDiagnostics().isEmpty() == false) {
            TitanGraphqlVerificationDiagnostic diagnostic = evidence.verificationDiagnostics().getFirst();
            reason.append(" (").append(diagnostic.code()).append(": ").append(diagnostic.message()).append(")");
        }
        return reason.toString();
    }

    private ArtifactRef requireCompleteArtifactRef(String artifactSetId) {
        ArtifactRef artifactRef = requireArtifactRef(artifactSetId);
        if (artifactRef.packageMode() == null
                || artifactRef.dialect() == null
                || artifactRef.manifestContentHash() == null
                || artifactRef.objectInventoryHash() == null
                || artifactRef.installPlanHash() == null
                || artifactRef.installVerificationHash() == null
                || artifactRef.sourceInputsHash() == null) {
            throw new IllegalArgumentException("Titan GAP-005 artifact evidence is incomplete");
        }
        return artifactRef;
    }

    private ArtifactRef requireArtifactRef(String artifactSetId) {
        return titanStore.artifactRef(artifactSetId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Titan GAP-005 artifact verification is required for artifact set '" + artifactSetId + "'"));
    }

    private TitanGraphqlArtifactSetRef requireGraphqlArtifactSet(String artifactSetId) {
        TitanGraphqlArtifactSetRef graphQlArtifactSet = graphQlStore.artifactSet(artifactSetId);
        if (graphQlArtifactSet == null) {
            throw new IllegalArgumentException("GraphQL artifact set wrapper is required for artifact set '" + artifactSetId + "'");
        }
        return graphQlArtifactSet;
    }

    private static void requireSameHash(String actual, String expected, String fieldName) {
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(fieldName + " does not match Titan GAP-005 verification evidence");
        }
    }

    private static String stableArtifactId(String artifactId) {
        return ManagementSupport.requireText(artifactId, "artifact id")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private void appendOperationRegistry(String modelId, String environment) {
        TitanGraphqlOperationRegistry registry = graphQlStore.operationRegistry(
                TitanGraphqlInMemoryManagementStore.operationRegistryId(modelId, environment)
        );
        if (registry != null) {
            appendProductState("operationRegistry", registry);
        }
    }

    private void appendProductState(String type, Object value) {
        if (productStateJournal != null) {
            productStateJournal.append(type, value);
        }
    }

    private static Path productStateLogPath(Path transactionLogPath) {
        Path fileName = transactionLogPath.getFileName();
        String suffix = fileName == null ? "management" : fileName.toString();
        return transactionLogPath.resolveSibling(suffix + ".graphql-state.jsonl");
    }

    private static final class ProductStateJournal {
        private static final JsonMapper JSON = new JsonMapper();

        private final Path path;

        ProductStateJournal(Path path) {
            this.path = path;
        }

        void loadInto(
                TitanGraphqlInMemoryManagementStore store,
                Map<String, TitanGraphqlArtifactEvidenceRef> artifactEvidenceByArtifactSetId
        ) {
            if (Files.exists(path) == false) {
                return;
            }
            try {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode node = JSON.readTree(line);
                    String type = node.path("type").asText();
                    JsonNode value = node.path("value");
                    switch (type) {
                        case "artifactEvidence" -> {
                            TitanGraphqlArtifactEvidenceRef evidence =
                                    JSON.treeToValue(value, TitanGraphqlArtifactEvidenceRef.class);
                            artifactEvidenceByArtifactSetId.put(evidence.artifactSetId(), evidence);
                        }
                        case "workspace" -> store.saveWorkspace(JSON.treeToValue(value, TitanGraphqlManagedWorkspace.class));
                        case "model" -> store.saveModel(JSON.treeToValue(value, TitanGraphqlManagedModel.class));
                        case "draft" -> store.saveDraft(JSON.treeToValue(value, TitanGraphqlModelDraft.class));
                        case "validationReport" -> store.saveValidationReport(JSON.treeToValue(value, TitanGraphqlValidationReportRef.class));
                        case "artifactSet" -> store.saveArtifactSet(JSON.treeToValue(value, TitanGraphqlArtifactSetRef.class));
                        case "driftReport" -> store.saveDriftReport(JSON.treeToValue(value, TitanGraphqlDriftReportRef.class));
                        case "previewBuild" -> store.savePreviewBuild(JSON.treeToValue(value, TitanGraphqlPreviewBuild.class));
                        case "previewContractTestReport" -> store.savePreviewContractTestReport(
                                JSON.treeToValue(value, TitanGraphqlPreviewContractTestReport.class));
                        case "observedOperation" -> store.saveObservedOperation(JSON.treeToValue(value, TitanGraphqlObservedOperation.class));
                        case "operationRegistry" -> store.saveOperationRegistry(JSON.treeToValue(value, TitanGraphqlOperationRegistry.class));
                        case "usageReport" -> store.saveUsageReport(JSON.treeToValue(value, TitanGraphqlUsageReport.class));
                        case "deployment" -> store.saveDeployment(JSON.treeToValue(value, TitanGraphqlDeployment.class));
                        default -> throw new IllegalStateException("unknown GraphQL management product state type '" + type + "'");
                    }
                }
            } catch (IOException ex) {
                throw new IllegalStateException("failed to load GraphQL management product state journal " + path, ex);
            }
        }

        void append(String type, Object value) {
            try {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                String line = JSON.writeValueAsString(new ProductStateEntry(type, JSON.valueToTree(value)));
                Files.writeString(
                        path,
                        line + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND
                );
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("failed to render GraphQL management product state", ex);
            } catch (IOException ex) {
                throw new IllegalStateException("failed to append GraphQL management product state journal " + path, ex);
            }
        }

        private record ProductStateEntry(String type, JsonNode value) {
        }
    }
}
