package io.titan.graphql.management;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.titan.graphql.artifact.TitanGraphqlArtifactKind;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.graphql.controlplane.TitanGraphqlControlJobQueue;
import io.titan.management.ManagementAudit.AuditRecord;
import io.titan.management.ManagementCommands.CommandInvocation;
import io.titan.management.ManagementIdempotency.IdempotencyRecord;
import io.titan.management.JdbcTransactionalMutationStore;
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
import io.titan.management.ManagementTransactions.TransactionalCommandHandler;
import io.titan.management.ManagementTransactions.TransactionalCommandResult;
import io.titan.management.ManagementTransactions.TransactionalMutationStore;
import io.titan.graphql.artifact.TitanGraphqlEntryPointRef;
import io.titan.graphql.artifact.TitanGraphqlRollbackScriptRef;
import io.titan.graphql.artifact.TitanGraphqlVerificationDiagnostic;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

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
// routine's single collapsed hash column.
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
        this(titanStore, (ProductStateJournal) null);
    }

    public TitanGraphqlDurableManagementStore(TransactionalMutationStore titanStore, DataSource productStateDataSource) {
        this(titanStore, new ProductStateJournal(productStateDataSource));
    }

    private TitanGraphqlDurableManagementStore(
            TransactionalMutationStore titanStore,
            ProductStateJournal productStateJournal
    ) {
        this.titanStore = titanStore;
        this.productStateJournal = productStateJournal;
        if (productStateJournal != null) {
            productStateJournal.loadInto(graphQlStore, artifactEvidenceByArtifactSetId);
            productStateJournal.backfillObservedOperations(graphQlStore.observedOperations());
            productStateJournal.backfillOperationRegistries(graphQlStore.operationRegistries());
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
        TransactionalCommandExecution execution = titanStore.execute(invocation,
                importCommandHandler(model, draft), attemptAt, outcomeAt);
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

    public boolean importModelDocumentAndCompleteJob(
            CommandInvocation invocation,
            TitanGraphqlManagedModel model,
            TitanGraphqlModelDraft draft,
            TitanGraphqlValidationReportRef validationReport,
            Instant attemptAt,
            Instant outcomeAt,
            TitanGraphqlControlJobQueue queue,
            TitanGraphqlControlJobQueue.ClaimedJob job,
            String resultJson
    ) {
        if (!supportsAtomicJobCompletion()) {
            throw new IllegalStateException("atomic import job completion requires JDBC management state");
        }
        java.util.Objects.requireNonNull(queue, "queue");
        JdbcTransactionalMutationStore jdbcStore = (JdbcTransactionalMutationStore) titanStore;
        try (Connection connection = productStateJournal.dataSource.getConnection()) {
            if (!connection.getAutoCommit()) {
                throw new IllegalStateException("model import requires an auto-commit connection");
            }
            connection.setAutoCommit(false);
            try {
                TransactionalCommandExecution execution = jdbcStore.execute(connection, invocation,
                        importCommandHandler(model, draft), attemptAt, outcomeAt);
                if (!execution.success()) {
                    throw new IllegalStateException("model import command failed: "
                            + execution.outcomeRecord().errorCode());
                }
                if (!queue.complete(connection, job, resultJson)) {
                    connection.rollback();
                    return false;
                }
                productStateJournal.append(connection, "model", model);
                productStateJournal.append(connection, "draft", draft);
                if (validationReport != null) {
                    productStateJournal.append(connection, "validationReport", validationReport);
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to publish model import in the management database", failure);
        }
        graphQlStore.saveModel(model);
        graphQlStore.saveDraft(draft);
        if (validationReport != null) {
            graphQlStore.saveValidationReport(validationReport);
        }
        return true;
    }

    private TransactionalCommandHandler importCommandHandler(
            TitanGraphqlManagedModel model,
            TitanGraphqlModelDraft draft
    ) {
        return (command, transaction) -> {
            Draft titanDraft = toTitanDraft(draft, model.workspaceId());
            if (transaction != null) {
                transaction.putDraft(titanDraft);
            }
            return TransactionalCommandResult.success("sha256:" + sha256(titanDraft.stableJson()), titanDraft.id());
        };
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

    public TitanGraphqlDurableManagementStore saveGeneratedArtifacts(
            TitanGraphqlArtifactSetRef artifactSet,
            TitanGraphqlModelDraft draft,
            TitanGraphqlGap005ArtifactMetadata metadata
    ) {
        persistGeneratedArtifacts(artifactSet, draft, metadata, null);
        return this;
    }

    public boolean supportsAtomicArtifactJobCompletion() {
        return supportsAtomicJobCompletion();
    }

    public boolean supportsAtomicJobCompletion() {
        return titanStore instanceof JdbcTransactionalMutationStore
                && productStateJournal != null && productStateJournal.dataSource != null;
    }

    public void saveValidationOutcome(
            TitanGraphqlValidationReportRef report,
            TitanGraphqlModelDraft draft
    ) {
        persistValidationOutcome(report, draft, null);
    }

    public boolean saveValidationOutcomeAndCompleteJob(
            TitanGraphqlValidationReportRef report,
            TitanGraphqlModelDraft draft,
            TitanGraphqlControlJobQueue queue,
            TitanGraphqlControlJobQueue.ClaimedJob job,
            String resultJson
    ) {
        if (!supportsAtomicJobCompletion()) {
            throw new IllegalStateException("atomic validation job completion requires JDBC management state");
        }
        java.util.Objects.requireNonNull(queue, "queue");
        return persistValidationOutcome(report, draft,
                connection -> queue.complete(connection, job, resultJson));
    }

    private boolean persistValidationOutcome(
            TitanGraphqlValidationReportRef report,
            TitanGraphqlModelDraft draft,
            CompletionGate completionGate
    ) {
        if (report == null || draft == null || !report.draftId().equals(draft.id())
                || !report.id().equals(draft.validationReportId())) {
            throw new IllegalArgumentException("validation report and draft must reference each other");
        }
        Draft titanDraft = toTitanDraft(draft);
        if (supportsAtomicJobCompletion()) {
            JdbcTransactionalMutationStore jdbcStore = (JdbcTransactionalMutationStore) titanStore;
            try (Connection connection = productStateJournal.dataSource.getConnection()) {
                if (!connection.getAutoCommit()) {
                    throw new IllegalStateException("validation publication requires an auto-commit connection");
                }
                connection.setAutoCommit(false);
                try {
                    if (completionGate != null && !completionGate.complete(connection)) {
                        connection.rollback();
                        return false;
                    }
                    jdbcStore.seedDraft(connection, titanDraft);
                    jdbcStore.transitionDraftStatus(connection, titanDraft);
                    productStateJournal.append(connection, "validationReport", report);
                    productStateJournal.append(connection, "draft", draft);
                    connection.commit();
                } catch (SQLException | RuntimeException failure) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                    throw failure;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("failed to publish validation in the management database", failure);
            }
        } else {
            if (completionGate != null) {
                throw new IllegalStateException("atomic validation job completion requires JDBC management state");
            }
            titanStore.seedDraft(titanDraft);
            if (productStateJournal != null) {
                productStateJournal.append("validationReport", report);
                productStateJournal.append("draft", draft);
            }
        }
        graphQlStore.saveValidationReport(report);
        graphQlStore.saveDraft(draft);
        return true;
    }

    public boolean saveGeneratedArtifactsAndCompleteJob(
            TitanGraphqlArtifactSetRef artifactSet,
            TitanGraphqlModelDraft draft,
            TitanGraphqlGap005ArtifactMetadata metadata,
            TitanGraphqlControlJobQueue queue,
            TitanGraphqlControlJobQueue.ClaimedJob job,
            String resultJson
    ) {
        if (!supportsAtomicArtifactJobCompletion()) {
            throw new IllegalStateException("atomic artifact job completion requires JDBC management state");
        }
        java.util.Objects.requireNonNull(queue, "queue");
        return persistGeneratedArtifacts(artifactSet, draft, metadata,
                connection -> queue.complete(connection, job, resultJson));
    }

    private boolean persistGeneratedArtifacts(
            TitanGraphqlArtifactSetRef artifactSet,
            TitanGraphqlModelDraft draft,
            TitanGraphqlGap005ArtifactMetadata metadata,
            CompletionGate completionGate
    ) {
        if (artifactSet == null || draft == null || !artifactSet.draftId().equals(draft.id())
                || !artifactSet.id().equals(draft.artifactSetId())) {
            throw new IllegalArgumentException("generated artifact set and draft must reference each other");
        }
        Draft titanDraft = toTitanDraft(draft);
        ArtifactRef titanArtifactRef = metadata == null ? null : toTitanArtifactRef(artifactSet, metadata);
        TitanGraphqlArtifactEvidenceRef evidence = null;
        if (metadata != null) {
            evidence = new TitanGraphqlArtifactEvidenceRef(
                    artifactSet.id(),
                    metadata.artifactId(),
                    metadata.verificationStatus(),
                    metadata.verificationDiagnostics(),
                    metadata.entryPoints(),
                    metadata.rollbackScripts());
        }
        if (titanStore instanceof JdbcTransactionalMutationStore jdbcStore
                && productStateJournal != null && productStateJournal.dataSource != null) {
            if (!publishGeneratedArtifactsJdbc(
                    jdbcStore, titanDraft, titanArtifactRef, artifactSet, draft, evidence, completionGate)) {
                return false;
            }
        } else {
            if (completionGate != null) {
                throw new IllegalStateException("atomic artifact job completion requires JDBC management state");
            }
            if (titanArtifactRef == null) {
                titanStore.seedDraft(titanDraft);
            } else {
                titanStore.seedArtifactGeneration(titanDraft, titanArtifactRef);
            }
            if (productStateJournal != null) {
                productStateJournal.appendArtifactGeneration(artifactSet, draft, evidence);
            }
        }
        graphQlStore.saveArtifactSet(artifactSet);
        graphQlStore.saveDraft(draft);
        if (evidence != null) {
            artifactEvidenceByArtifactSetId.put(evidence.artifactSetId(), evidence);
        }
        return true;
    }

    private boolean publishGeneratedArtifactsJdbc(
            JdbcTransactionalMutationStore jdbcStore,
            Draft titanDraft,
            ArtifactRef titanArtifactRef,
            TitanGraphqlArtifactSetRef artifactSet,
            TitanGraphqlModelDraft draft,
            TitanGraphqlArtifactEvidenceRef evidence,
            CompletionGate completionGate
    ) {
        try (Connection connection = productStateJournal.dataSource.getConnection()) {
            if (!connection.getAutoCommit()) {
                throw new IllegalStateException("artifact publication requires an auto-commit connection");
            }
            connection.setAutoCommit(false);
            try {
                if (completionGate != null && !completionGate.complete(connection)) {
                    connection.rollback();
                    return false;
                }
                if (titanArtifactRef == null) {
                    jdbcStore.seedDraft(connection, titanDraft);
                } else {
                    jdbcStore.seedArtifactGeneration(connection, titanDraft, titanArtifactRef);
                }
                jdbcStore.transitionDraftStatus(connection, titanDraft);
                productStateJournal.appendArtifactGeneration(connection, artifactSet, draft, evidence);
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to publish generated artifacts in the management database", failure);
        }
    }

    @FunctionalInterface
    private interface CompletionGate {
        boolean complete(Connection connection) throws SQLException;
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
        if (previewBuild.status() != TitanGraphqlPreviewBuild.PreviewBuildStatus.READY) {
            throw new IllegalArgumentException("verified preview build must be READY");
        }
        TitanGraphqlOperationRegistry registry = graphQlStore.operationRegistry(previewBuild.operationRegistryId());
        if (registry == null) {
            throw new IllegalArgumentException("verified preview build requires an operation registry");
        }
        if (!previewBuild.modelId().equals(registry.modelId())
                || !previewBuild.environment().equals(registry.environment())) {
            throw new IllegalArgumentException("preview build operation registry model or environment does not match");
        }
        if (registry.mode() != TitanGraphqlOperationRegistry.RegistryMode.ENFORCE) {
            throw new IllegalArgumentException("verified preview build requires ENFORCE operation registry mode");
        }
        java.time.Instant expiresAt;
        try {
            expiresAt = java.time.Instant.parse(previewBuild.expiresAt());
        } catch (java.time.format.DateTimeParseException invalid) {
            throw new IllegalArgumentException("verified preview build requires an ISO-8601 expiration", invalid);
        }
        if (!expiresAt.isAfter(java.time.Instant.now())) {
            throw new IllegalArgumentException("verified preview build expiration must be in the future");
        }
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
    public synchronized TitanGraphqlDurableManagementStore saveObservedOperation(TitanGraphqlObservedOperation operation) {
        TitanGraphqlObservedOperation persisted = productStateJournal != null && productStateJournal.dataSource != null
                ? productStateJournal.persistObservedOperation(operation, false)
                : operation;
        if (productStateJournal == null || productStateJournal.dataSource == null) {
            appendProductState("observedOperation", persisted);
        }
        graphQlStore.saveObservedOperation(persisted);
        return this;
    }

    @Override
    public synchronized TitanGraphqlDurableManagementStore observeOperation(TitanGraphqlObservedOperation operation) {
        TitanGraphqlObservedOperation merged;
        if (productStateJournal != null && productStateJournal.dataSource != null) {
            merged = productStateJournal.persistObservedOperation(operation, true);
        } else {
            TitanGraphqlObservedOperation existing = graphQlStore.observedOperation(operation.id());
            merged = existing == null ? operation : existing.mergeObservation(operation);
            appendProductState("observedOperation", merged);
        }
        graphQlStore.saveObservedOperation(merged);
        return this;
    }

    @Override
    public synchronized TitanGraphqlObservedOperation approveObservedOperation(
            String observedOperationId, String approvedBy, String approvedAt
    ) {
        return reviewObservedOperation(observedOperationId, approvedBy, approvedAt, true);
    }

    @Override
    public synchronized TitanGraphqlObservedOperation rejectObservedOperation(
            String observedOperationId, String rejectedBy, String rejectedAt
    ) {
        return reviewObservedOperation(observedOperationId, rejectedBy, rejectedAt, false);
    }

    public synchronized boolean reviewObservedOperationAndCompleteJob(
            String observedOperationId,
            String reviewedBy,
            String reviewedAt,
            boolean approve,
            TitanGraphqlControlJobQueue queue,
            TitanGraphqlControlJobQueue.ClaimedJob job
    ) {
        if (!supportsAtomicJobCompletion()) {
            throw new IllegalStateException("atomic operation review requires JDBC management state");
        }
        java.util.Objects.requireNonNull(queue, "queue");
        java.util.Objects.requireNonNull(job, "job");
        OperationReviewEntry persisted = productStateJournal.persistOperationReview(
                observedOperationId, reviewedBy, reviewedAt, approve, queue, job);
        if (persisted == null) {
            return false;
        }
        graphQlStore.saveObservedOperation(persisted.operation());
        graphQlStore.saveOperationRegistry(persisted.registry());
        return true;
    }

    private TitanGraphqlObservedOperation reviewObservedOperation(
            String observedOperationId, String reviewedBy, String reviewedAt, boolean approve
    ) {
        if (productStateJournal != null && productStateJournal.dataSource != null) {
            OperationReviewEntry persisted = productStateJournal.persistOperationReview(
                    observedOperationId, reviewedBy, reviewedAt, approve);
            graphQlStore.saveObservedOperation(persisted.operation());
            graphQlStore.saveOperationRegistry(persisted.registry());
            return persisted.operation();
        }
        TitanGraphqlObservedOperation existing = graphQlStore.observedOperation(observedOperationId);
        if (existing == null) {
            throw new IllegalArgumentException("unknown observed operation '" + observedOperationId + "'");
        }
        String registryId = TitanGraphqlInMemoryManagementStore.operationRegistryId(
                existing.modelId(), existing.environment());
        TitanGraphqlInMemoryManagementStore staged = new TitanGraphqlInMemoryManagementStore();
        staged.saveObservedOperation(existing);
        TitanGraphqlOperationRegistry currentRegistry = graphQlStore.operationRegistry(registryId);
        if (currentRegistry != null) {
            staged.saveOperationRegistry(currentRegistry);
        }
        TitanGraphqlObservedOperation reviewed = approve
                ? staged.approveObservedOperation(observedOperationId, reviewedBy, reviewedAt)
                : staged.rejectObservedOperation(observedOperationId, reviewedBy, reviewedAt);
        TitanGraphqlOperationRegistry reviewedRegistry = staged.operationRegistry(registryId);
        OperationReviewEntry entry = new OperationReviewEntry(reviewed, reviewedRegistry);
        appendProductState("operationReview", entry);
        graphQlStore.saveObservedOperation(reviewed);
        graphQlStore.saveOperationRegistry(reviewedRegistry);
        return reviewed;
    }

    @Override
    public synchronized TitanGraphqlDurableManagementStore saveOperationRegistry(TitanGraphqlOperationRegistry registry) {
        if (productStateJournal != null && productStateJournal.dataSource != null) {
            productStateJournal.persistOperationRegistry(
                    graphQlStore.operationRegistry(registry.id()), registry);
        } else {
            appendProductState("operationRegistry", registry);
        }
        graphQlStore.saveOperationRegistry(registry);
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
    public synchronized TitanGraphqlObservedOperation observedOperation(String id) {
        return graphQlStore.observedOperation(id);
    }

    @Override
    public synchronized TitanGraphqlOperationRegistry operationRegistry(String id) {
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
    public synchronized List<TitanGraphqlObservedOperation> observedOperations() {
        return graphQlStore.observedOperations();
    }

    @Override
    public synchronized List<TitanGraphqlObservedOperation> observedOperations(String modelId, String environment) {
        return graphQlStore.observedOperations(modelId, environment);
    }

    @Override
    public synchronized List<TitanGraphqlObservedOperation> observedOperations(
            String modelId, String environment, String role
    ) {
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
        return sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
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

    private record OperationReviewEntry(
            TitanGraphqlObservedOperation operation,
            TitanGraphqlOperationRegistry registry
    ) {
    }

    private static final class ProductStateJournal {
        private static final JsonMapper JSON = new JsonMapper();
        private static final String TABLE = "management.graphql_product_state";
        private static final String OBSERVED_TABLE = "management.graphql_observed_operations";
        private static final String REGISTRY_TABLE = "management.graphql_operation_registries";
        private static final String REGISTRY_OPERATIONS_TABLE = "management.graphql_registry_operations";
        private static final String INSERT_OBSERVED = "INSERT INTO " + OBSERVED_TABLE
                + " (id, model_id, environment, role, client, operation_hash, operation_name, "
                + "document, status, depth, estimated_cost, field_usage_json, first_seen_at, "
                + "last_seen_at, observed_count, operation_json) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        private static final String UPDATE_OBSERVED = "UPDATE " + OBSERVED_TABLE
                + " SET model_id = ?, environment = ?, role = ?, client = ?, operation_hash = ?, "
                + "operation_name = ?, document = ?, status = ?, depth = ?, estimated_cost = ?, "
                + "field_usage_json = ?, first_seen_at = ?, last_seen_at = ?, observed_count = ?, "
                + "operation_json = ? WHERE id = ?";

        private final Path path;
        private final DataSource dataSource;

        ProductStateJournal(Path path) {
            this.path = path;
            this.dataSource = null;
        }

        ProductStateJournal(DataSource dataSource) {
            this.path = null;
            this.dataSource = java.util.Objects.requireNonNull(dataSource, "product state data source");
        }

        void backfillObservedOperations(List<TitanGraphqlObservedOperation> operations) {
            if (dataSource == null) {
                return;
            }
            for (TitanGraphqlObservedOperation operation : operations) {
                try (Connection connection = dataSource.getConnection()) {
                    try {
                        insertObserved(connection, operation);
                    } catch (SQLException duplicate) {
                        if (!isDuplicateKey(duplicate)) {
                            throw duplicate;
                        }
                    }
                } catch (SQLException failure) {
                    throw new IllegalStateException("failed to backfill observed operation " + operation.id(), failure);
                }
            }
        }

        void backfillOperationRegistries(List<TitanGraphqlOperationRegistry> registries) {
            if (dataSource == null) {
                return;
            }
            for (TitanGraphqlOperationRegistry registry : registries) {
                try (Connection connection = dataSource.getConnection()) {
                    if (!connection.getAutoCommit()) {
                        throw new IllegalStateException("registry backfill requires auto-commit connection");
                    }
                    connection.setAutoCommit(false);
                    try {
                        lockReviewMutex(connection);
                        TitanGraphqlOperationRegistry projected = projectedRegistry(connection, registry.id());
                        if (projected == null) {
                            insertRegistry(connection, registry);
                            replaceRegistryOperations(connection, registry);
                        } else if (!projected.operations().isEmpty()
                                && !registryOperationRowsExist(connection, registry.id())) {
                            replaceRegistryOperations(connection, projected);
                        }
                        connection.commit();
                    } catch (SQLException | RuntimeException failure) {
                        connection.rollback();
                        throw failure;
                    } finally {
                        connection.setAutoCommit(true);
                    }
                } catch (SQLException failure) {
                    throw new IllegalStateException("failed to backfill operation registry " + registry.id(), failure);
                }
            }
        }

        synchronized TitanGraphqlObservedOperation persistObservedOperation(
                TitanGraphqlObservedOperation operation, boolean merge
        ) {
            if (dataSource == null) {
                throw new IllegalStateException("JDBC observed-operation projection is required");
            }
            for (int attempt = 0; attempt < 3; attempt++) {
                try (Connection connection = dataSource.getConnection()) {
                    if (!connection.getAutoCommit()) {
                        throw new IllegalStateException("observed-operation publication requires auto-commit connection");
                    }
                    connection.setAutoCommit(false);
                    try {
                        TitanGraphqlObservedOperation existing = observedForUpdate(connection, operation.id());
                        TitanGraphqlObservedOperation persisted = merge && existing != null
                                ? existing.mergeObservation(operation) : operation;
                        if (existing == null) {
                            insertObserved(connection, persisted);
                        } else {
                            updateObserved(connection, persisted);
                        }
                        append(connection, "observedOperation", persisted);
                        connection.commit();
                        return persisted;
                    } catch (SQLException | RuntimeException failure) {
                        connection.rollback();
                        if (failure instanceof SQLException sqlFailure
                                && isDuplicateKey(sqlFailure) && attempt < 2) {
                            continue;
                        }
                        throw new IllegalStateException("failed to publish observed operation "
                                + operation.id(), failure);
                    } finally {
                        connection.setAutoCommit(true);
                    }
                } catch (SQLException failure) {
                    throw new IllegalStateException("failed to publish observed operation "
                            + operation.id(), failure);
                }
            }
            throw new IllegalStateException("observed-operation publication exhausted duplicate-key retries");
        }

        synchronized OperationReviewEntry persistOperationReview(
                String observedOperationId, String reviewedBy, String reviewedAt, boolean approve
        ) {
            return persistOperationReview(observedOperationId, reviewedBy, reviewedAt, approve, null, null);
        }

        synchronized OperationReviewEntry persistOperationReview(
                String observedOperationId,
                String reviewedBy,
                String reviewedAt,
                boolean approve,
                TitanGraphqlControlJobQueue queue,
                TitanGraphqlControlJobQueue.ClaimedJob job
        ) {
            if (dataSource == null) {
                throw new IllegalStateException("JDBC observed-operation projection is required");
            }
            try (Connection connection = dataSource.getConnection()) {
                if (!connection.getAutoCommit()) {
                    throw new IllegalStateException("operation review requires auto-commit connection");
                }
                connection.setAutoCommit(false);
                try {
                    lockReviewMutex(connection);
                    TitanGraphqlInMemoryManagementStore staged = currentGraphQlState();
                    TitanGraphqlObservedOperation existing = staged.observedOperation(observedOperationId);
                    if (existing == null) {
                        throw new IllegalArgumentException(
                                "unknown observed operation '" + observedOperationId + "'");
                    }
                    TitanGraphqlObservedOperation current = observedForUpdate(connection, observedOperationId);
                    if (!existing.equals(current)) {
                        throw new IllegalStateException(
                                "observed operation changed before review: " + observedOperationId);
                    }
                    TitanGraphqlObservedOperation reviewed = approve
                            ? staged.approveObservedOperation(observedOperationId, reviewedBy, reviewedAt)
                            : staged.rejectObservedOperation(observedOperationId, reviewedBy, reviewedAt);
                    String registryId = TitanGraphqlInMemoryManagementStore.operationRegistryId(
                            reviewed.modelId(), reviewed.environment());
                    OperationReviewEntry review = new OperationReviewEntry(
                            reviewed, staged.operationRegistry(registryId));
                    if (queue != null && !queue.complete(connection, job, reviewResultJson(review))) {
                        connection.rollback();
                        return null;
                    }
                    updateObserved(connection, reviewed);
                    upsertRegistry(connection, review.registry());
                    append(connection, "operationReview", review);
                    connection.commit();
                    return review;
                } catch (SQLException | RuntimeException failure) {
                    connection.rollback();
                    throw failure;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException failure) {
                throw new IllegalStateException(
                        "failed to publish operation review " + observedOperationId, failure);
            }
        }

        private String reviewResultJson(OperationReviewEntry review) throws SQLException {
            TitanGraphqlObservedOperation operation = review.operation();
            try {
                return JSON.writeValueAsString(Map.of(
                        "accepted", true,
                        "observedOperationId", operation.id(),
                        "operationRegistryId", review.registry().id(),
                        "modelId", operation.modelId(),
                        "environment", operation.environment(),
                        "role", operation.role(),
                        "client", operation.client(),
                        "operationHash", operation.operationHash(),
                        "status", operation.status().name()));
            } catch (JsonProcessingException failure) {
                throw new SQLException("operation review result could not be serialized", failure);
            }
        }

        synchronized void persistOperationRegistry(
                TitanGraphqlOperationRegistry expected,
                TitanGraphqlOperationRegistry registry
        ) {
            if (dataSource == null) {
                throw new IllegalStateException("JDBC operation registry is required");
            }
            try (Connection connection = dataSource.getConnection()) {
                if (!connection.getAutoCommit()) {
                    throw new IllegalStateException("operation registry publication requires auto-commit connection");
                }
                connection.setAutoCommit(false);
                try {
                    lockReviewMutex(connection);
                    TitanGraphqlOperationRegistry current = currentGraphQlState().operationRegistry(registry.id());
                    if (!java.util.Objects.equals(expected, current)) {
                        throw new IllegalStateException("operation registry changed before publication: "
                                + registry.id());
                    }
                    upsertRegistry(connection, registry);
                    append(connection, "operationRegistry", registry);
                    connection.commit();
                } catch (SQLException | RuntimeException failure) {
                    connection.rollback();
                    throw failure;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("failed to publish operation registry " + registry.id(), failure);
            }
        }

        private void lockReviewMutex(Connection connection) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM management.graphql_review_mutex WHERE id = 1 FOR UPDATE");
                    ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("management review mutex lock row is missing");
                }
            }
        }

        private TitanGraphqlInMemoryManagementStore currentGraphQlState() {
            TitanGraphqlInMemoryManagementStore current = new TitanGraphqlInMemoryManagementStore();
            loadInto(current, new LinkedHashMap<>());
            return current;
        }

        private void upsertRegistry(Connection connection, TitanGraphqlOperationRegistry registry)
                throws SQLException {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + REGISTRY_TABLE + " SET model_id = ?, environment = ?, mode = ?, "
                            + "operations_json = ?, updated_at = ?, registry_json = ? WHERE id = ?")) {
                bindRegistryDetails(update, registry, 1);
                update.setString(7, registry.id());
                if (update.executeUpdate() == 0) {
                    insertRegistry(connection, registry);
                }
            }
            replaceRegistryOperations(connection, registry);
        }

        private TitanGraphqlOperationRegistry projectedRegistry(Connection connection, String id)
                throws SQLException {
            try (PreparedStatement lookup = connection.prepareStatement(
                    "SELECT registry_json FROM " + REGISTRY_TABLE + " WHERE id = ? FOR UPDATE")) {
                lookup.setString(1, id);
                try (ResultSet rows = lookup.executeQuery()) {
                    if (!rows.next()) {
                        return null;
                    }
                    try {
                        return JSON.readValue(rows.getString(1), TitanGraphqlOperationRegistry.class);
                    } catch (IOException malformed) {
                        throw new SQLException("stored operation registry is malformed: " + id, malformed);
                    }
                }
            }
        }

        private boolean registryOperationRowsExist(Connection connection, String id) throws SQLException {
            try (PreparedStatement lookup = connection.prepareStatement(
                    "SELECT operation_id FROM " + REGISTRY_OPERATIONS_TABLE + " WHERE registry_id = ? LIMIT 1")) {
                lookup.setString(1, id);
                try (ResultSet rows = lookup.executeQuery()) {
                    return rows.next();
                }
            }
        }

        private void replaceRegistryOperations(Connection connection, TitanGraphqlOperationRegistry registry)
                throws SQLException {
            try (PreparedStatement remove = connection.prepareStatement(
                    "DELETE FROM " + REGISTRY_OPERATIONS_TABLE + " WHERE registry_id = ?")) {
                remove.setString(1, registry.id());
                remove.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + REGISTRY_OPERATIONS_TABLE + " (registry_id, operation_id, operation_position, "
                            + "operation_hash, document_hash, operation_name, document, status, roles_json, clients_json) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                int position = 0;
                for (TitanGraphqlOperationRegistry.RegisteredOperation operation : registry.operations()) {
                    insert.setString(1, registry.id());
                    insert.setString(2, operation.id());
                    insert.setInt(3, position++);
                    insert.setString(4, operation.operationHash());
                    insert.setString(5, sha256(operation.document()));
                    insert.setString(6, operation.operationName());
                    insert.setString(7, operation.document());
                    insert.setString(8, operation.status().name());
                    try {
                        insert.setString(9, JSON.writeValueAsString(operation.roles()));
                        insert.setString(10, JSON.writeValueAsString(operation.clients()));
                    } catch (JsonProcessingException failure) {
                        throw new SQLException("registry operation scope could not be serialized", failure);
                    }
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }

        private void insertRegistry(Connection connection, TitanGraphqlOperationRegistry registry)
                throws SQLException {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + REGISTRY_TABLE + " (id, model_id, environment, mode, "
                            + "operations_json, updated_at, registry_json) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, registry.id());
                bindRegistryDetails(insert, registry, 2);
                insert.executeUpdate();
            }
        }

        private void bindRegistryDetails(
                PreparedStatement statement, TitanGraphqlOperationRegistry registry, int first
        ) throws SQLException {
            statement.setString(first, registry.modelId());
            statement.setString(first + 1, registry.environment());
            statement.setString(first + 2, registry.mode().name());
            try {
                statement.setString(first + 3, JSON.writeValueAsString(registry.operations()));
                statement.setString(first + 5, JSON.writeValueAsString(registry));
            } catch (JsonProcessingException failure) {
                throw new SQLException("operation registry could not be serialized", failure);
            }
            statement.setString(first + 4, registry.updatedAt());
        }

        private TitanGraphqlObservedOperation observedForUpdate(Connection connection, String id) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT operation_json FROM " + OBSERVED_TABLE + " WHERE id = ? FOR UPDATE")) {
                statement.setString(1, id);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return null;
                    }
                    try {
                        return JSON.readValue(rows.getString(1), TitanGraphqlObservedOperation.class);
                    } catch (IOException malformed) {
                        throw new IllegalStateException("stored observed operation is malformed: " + id, malformed);
                    }
                }
            }
        }

        private void insertObserved(Connection connection, TitanGraphqlObservedOperation operation) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(INSERT_OBSERVED)) {
                statement.setString(1, operation.id());
                bindObserved(statement, operation, 2);
                statement.executeUpdate();
            }
        }

        private void updateObserved(Connection connection, TitanGraphqlObservedOperation operation) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(UPDATE_OBSERVED)) {
                bindObserved(statement, operation, 1);
                statement.setString(16, operation.id());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("observed operation disappeared before update: " + operation.id());
                }
            }
        }

        private void bindObserved(
                PreparedStatement statement, TitanGraphqlObservedOperation operation, int start
        ) throws SQLException {
            statement.setString(start, operation.modelId());
            statement.setString(start + 1, operation.environment());
            statement.setString(start + 2, operation.role());
            statement.setString(start + 3, operation.client());
            statement.setString(start + 4, operation.operationHash());
            statement.setString(start + 5, operation.operationName());
            statement.setString(start + 6, operation.document());
            statement.setString(start + 7, operation.status().name());
            statement.setInt(start + 8, operation.depth());
            statement.setInt(start + 9, operation.estimatedCost());
            try {
                statement.setString(start + 10, JSON.writeValueAsString(operation.fieldUsage()));
                statement.setString(start + 14, JSON.writeValueAsString(operation));
            } catch (JsonProcessingException failure) {
                throw new SQLException("observed operation could not be serialized", failure);
            }
            statement.setString(start + 11, operation.firstSeenAt());
            statement.setString(start + 12, operation.lastSeenAt());
            statement.setInt(start + 13, operation.observedCount());
        }

        private static boolean isDuplicateKey(SQLException failure) {
            return "23505".equals(failure.getSQLState())
                    || "23000".equals(failure.getSQLState());
        }

        void loadInto(
                TitanGraphqlInMemoryManagementStore store,
                Map<String, TitanGraphqlArtifactEvidenceRef> artifactEvidenceByArtifactSetId
        ) {
            if (dataSource == null && Files.exists(path) == false) {
                return;
            }
            try {
                for (String line : readEntries()) {
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
                        case "artifactGeneration" -> {
                            ArtifactGenerationEntry generation =
                                    JSON.treeToValue(value, ArtifactGenerationEntry.class);
                            store.saveArtifactSet(generation.artifactSet());
                            store.saveDraft(generation.draft());
                            if (generation.evidence() != null) {
                                artifactEvidenceByArtifactSetId.put(
                                        generation.evidence().artifactSetId(), generation.evidence());
                            }
                        }
                        case "driftReport" -> store.saveDriftReport(JSON.treeToValue(value, TitanGraphqlDriftReportRef.class));
                        case "previewBuild" -> store.savePreviewBuild(JSON.treeToValue(value, TitanGraphqlPreviewBuild.class));
                        case "previewContractTestReport" -> store.savePreviewContractTestReport(
                                JSON.treeToValue(value, TitanGraphqlPreviewContractTestReport.class));
                        case "observedOperation" -> store.saveObservedOperation(JSON.treeToValue(value, TitanGraphqlObservedOperation.class));
                        case "operationReview" -> {
                            OperationReviewEntry review = JSON.treeToValue(value, OperationReviewEntry.class);
                            store.saveObservedOperation(review.operation());
                            store.saveOperationRegistry(review.registry());
                        }
                        case "operationRegistry" -> store.saveOperationRegistry(JSON.treeToValue(value, TitanGraphqlOperationRegistry.class));
                        case "usageReport" -> store.saveUsageReport(JSON.treeToValue(value, TitanGraphqlUsageReport.class));
                        case "deployment" -> store.saveDeployment(JSON.treeToValue(value, TitanGraphqlDeployment.class));
                        default -> throw new IllegalStateException("unknown GraphQL management product state type '" + type + "'");
                    }
                }
            } catch (IOException | SQLException ex) {
                throw new IllegalStateException("failed to load GraphQL management product state journal "
                        + location(), ex);
            }
        }

        synchronized void append(String type, Object value) {
            try {
                String line = render(type, value);
                if (dataSource == null) {
                    Path parent = path.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    recoverTornTail();
                    ByteBuffer bytes = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
                    try (FileChannel channel = FileChannel.open(path,
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                        while (bytes.hasRemaining()) {
                            channel.write(bytes);
                        }
                        channel.force(true);
                    }
                } else {
                    try (Connection connection = dataSource.getConnection();
                         PreparedStatement statement = connection.prepareStatement(
                                 "INSERT INTO " + TABLE + " (entry_json) VALUES (?)")) {
                        statement.setString(1, line);
                        statement.executeUpdate();
                    }
                }
            } catch (IOException | SQLException ex) {
                throw new IllegalStateException("failed to append GraphQL management product state journal "
                        + location(), ex);
            }
        }

        private void append(Connection connection, String type, Object value) {
            if (dataSource == null) {
                throw new IllegalStateException("JDBC product state journal is required");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + TABLE + " (entry_json) VALUES (?)")) {
                statement.setString(1, render(type, value));
                statement.executeUpdate();
            } catch (SQLException failure) {
                throw new IllegalStateException("failed to append GraphQL management product state journal "
                        + location(), failure);
            }
        }

        private String render(String type, Object value) {
            try {
                return JSON.writeValueAsString(new ProductStateEntry(type, JSON.valueToTree(value)));
            } catch (JsonProcessingException failure) {
                throw new IllegalStateException("failed to render GraphQL management product state", failure);
            }
        }

        private synchronized List<String> readEntries() throws IOException, SQLException {
            if (dataSource == null) {
                recoverTornTail();
                return Files.readAllLines(path, StandardCharsets.UTF_8);
            }
            List<String> entries = new ArrayList<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT entry_json FROM " + TABLE + " ORDER BY entry_id");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    entries.add(rows.getString(1));
                }
            }
            return entries;
        }

        private void recoverTornTail() throws IOException {
            if (!Files.exists(path) || Files.size(path) == 0) {
                return;
            }
            byte[] contents = Files.readAllBytes(path);
            if (contents[contents.length - 1] == '\n') {
                return;
            }
            int completeLength = 0;
            for (int index = contents.length - 1; index >= 0; index--) {
                if (contents[index] == '\n') {
                    completeLength = index + 1;
                    break;
                }
            }
            // The newline terminates a committed entry; an unterminated tail cannot be replayed.
            byte[] tail = Arrays.copyOfRange(contents, completeLength, contents.length);
            Path backup = path.resolveSibling(path.getFileName() + ".torn-" + sha256(tail));
            if (Files.exists(backup)) {
                if (!Arrays.equals(Files.readAllBytes(backup), tail)) {
                    throw new IOException("torn product state backup differs from journal tail: " + backup);
                }
            } else {
                Path temporary = Files.createTempFile(
                        path.toAbsolutePath().getParent(), path.getFileName() + ".torn-", ".tmp");
                try {
                    Files.write(temporary, tail);
                    try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                        channel.force(true);
                    }
                    try {
                        Files.move(temporary, backup, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException unsupported) {
                        Files.move(temporary, backup);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                channel.truncate(completeLength);
                channel.force(true);
            }
        }

        private String location() {
            return dataSource == null ? path.toString() : TABLE;
        }

        void appendArtifactGeneration(
                TitanGraphqlArtifactSetRef artifactSet,
                TitanGraphqlModelDraft draft,
                TitanGraphqlArtifactEvidenceRef evidence
        ) {
            append("artifactGeneration", new ArtifactGenerationEntry(artifactSet, draft, evidence));
        }

        void appendArtifactGeneration(
                Connection connection,
                TitanGraphqlArtifactSetRef artifactSet,
                TitanGraphqlModelDraft draft,
                TitanGraphqlArtifactEvidenceRef evidence
        ) {
            append(connection, "artifactGeneration", new ArtifactGenerationEntry(artifactSet, draft, evidence));
        }

        private record ProductStateEntry(String type, JsonNode value) {
        }

        private record ArtifactGenerationEntry(
                TitanGraphqlArtifactSetRef artifactSet,
                TitanGraphqlModelDraft draft,
                TitanGraphqlArtifactEvidenceRef evidence
        ) {
        }
    }
}
