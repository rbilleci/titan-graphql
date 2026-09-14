package io.titan.graphql.management;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TitanGraphqlInMemoryManagementStore implements TitanGraphqlManagementStore {

    private final Map<String, TitanGraphqlManagedWorkspace> workspaces = new LinkedHashMap<>();
    private final Map<String, TitanGraphqlManagedModel> models = new LinkedHashMap<>();
    private final Map<String, TitanGraphqlModelDraft> drafts = new LinkedHashMap<>();
    private final Map<String, TitanGraphqlValidationReportRef> validationReports = new LinkedHashMap<>();
    private final Map<String, TitanGraphqlArtifactSetRef> artifactSets = new LinkedHashMap<>();
    private final Map<String, TitanGraphqlDriftReportRef> driftReports = new LinkedHashMap<>();
    private final Map<String, TitanGraphqlPreviewBuild> previewBuilds = new LinkedHashMap<>();
    private final Map<String, TitanGraphqlPreviewContractTestReport> previewContractTestReports = new LinkedHashMap<>();
    private final Map<String, TitanGraphqlObservedOperation> observedOperations = new LinkedHashMap<>();
    private final Map<String, TitanGraphqlOperationRegistry> operationRegistries = new LinkedHashMap<>();
    private final Map<String, TitanGraphqlUsageReport> usageReports = new LinkedHashMap<>();
    private final Map<String, TitanGraphqlDeployment> deployments = new LinkedHashMap<>();

    @Override
    public TitanGraphqlInMemoryManagementStore saveWorkspace(TitanGraphqlManagedWorkspace workspace) {
        workspaces.put(workspace.id(), workspace);
        return this;
    }

    @Override
    public TitanGraphqlInMemoryManagementStore saveModel(TitanGraphqlManagedModel model) {
        models.put(model.id(), model);
        return this;
    }

    @Override
    public TitanGraphqlInMemoryManagementStore saveDraft(TitanGraphqlModelDraft draft) {
        drafts.put(draft.id(), draft);
        return this;
    }

    @Override
    public TitanGraphqlInMemoryManagementStore saveValidationReport(TitanGraphqlValidationReportRef report) {
        validationReports.put(report.id(), report);
        return this;
    }

    @Override
    public TitanGraphqlInMemoryManagementStore saveArtifactSet(TitanGraphqlArtifactSetRef artifactSet) {
        artifactSets.put(artifactSet.id(), artifactSet);
        return this;
    }

    @Override
    public TitanGraphqlInMemoryManagementStore saveDriftReport(TitanGraphqlDriftReportRef report) {
        driftReports.put(report.id(), report);
        return this;
    }

    @Override
    public TitanGraphqlInMemoryManagementStore savePreviewBuild(TitanGraphqlPreviewBuild previewBuild) {
        previewBuilds.put(previewBuild.id(), previewBuild);
        return this;
    }

    @Override
    public TitanGraphqlInMemoryManagementStore savePreviewContractTestReport(TitanGraphqlPreviewContractTestReport report) {
        previewContractTestReports.put(report.id(), report);
        return this;
    }

    @Override
    public TitanGraphqlInMemoryManagementStore saveObservedOperation(TitanGraphqlObservedOperation operation) {
        observedOperations.put(operation.id(), operation);
        return this;
    }

    @Override
    public TitanGraphqlInMemoryManagementStore observeOperation(TitanGraphqlObservedOperation operation) {
        observedOperations.merge(operation.id(), operation, TitanGraphqlObservedOperation::mergeObservation);
        return this;
    }

    @Override
    public TitanGraphqlObservedOperation approveObservedOperation(String observedOperationId, String approvedBy, String approvedAt) {
        TitanGraphqlObservedOperation approved = reviewObservedOperation(
                observedOperationId,
                TitanGraphqlObservedOperation.ObservedOperationStatus.APPROVED
        );
        upsertRegistryOperation(approved, TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED, approvedBy, approvedAt);
        return approved;
    }

    @Override
    public TitanGraphqlObservedOperation rejectObservedOperation(String observedOperationId, String rejectedBy, String rejectedAt) {
        TitanGraphqlObservedOperation rejected = reviewObservedOperation(
                observedOperationId,
                TitanGraphqlObservedOperation.ObservedOperationStatus.REJECTED
        );
        upsertRegistryOperation(rejected, TitanGraphqlOperationRegistry.RegisteredOperationStatus.REJECTED, rejectedBy, rejectedAt);
        return rejected;
    }

    @Override
    public TitanGraphqlInMemoryManagementStore saveOperationRegistry(TitanGraphqlOperationRegistry registry) {
        operationRegistries.put(registry.id(), registry);
        return this;
    }

    @Override
    public TitanGraphqlInMemoryManagementStore saveUsageReport(TitanGraphqlUsageReport report) {
        usageReports.put(report.id(), report);
        return this;
    }

    @Override
    public TitanGraphqlInMemoryManagementStore saveDeployment(TitanGraphqlDeployment deployment) {
        deployments.put(deployment.id(), deployment);
        return this;
    }

    @Override
    public TitanGraphqlManagedWorkspace workspace(String id) {
        return workspaces.get(id);
    }

    @Override
    public TitanGraphqlManagedModel model(String id) {
        return models.get(id);
    }

    @Override
    public TitanGraphqlModelDraft draft(String id) {
        return drafts.get(id);
    }

    @Override
    public TitanGraphqlValidationReportRef validationReport(String id) {
        return validationReports.get(id);
    }

    @Override
    public TitanGraphqlArtifactSetRef artifactSet(String id) {
        return artifactSets.get(id);
    }

    @Override
    public TitanGraphqlDriftReportRef driftReport(String id) {
        return driftReports.get(id);
    }

    @Override
    public TitanGraphqlPreviewBuild previewBuild(String id) {
        return previewBuilds.get(id);
    }

    @Override
    public TitanGraphqlPreviewContractTestReport previewContractTestReport(String id) {
        return previewContractTestReports.get(id);
    }

    @Override
    public TitanGraphqlObservedOperation observedOperation(String id) {
        return observedOperations.get(id);
    }

    @Override
    public TitanGraphqlOperationRegistry operationRegistry(String id) {
        return operationRegistries.get(id);
    }

    @Override
    public TitanGraphqlUsageReport usageReport(String id) {
        return usageReports.get(id);
    }

    @Override
    public TitanGraphqlDeployment deployment(String id) {
        return deployments.get(id);
    }

    @Override
    public List<TitanGraphqlManagedWorkspace> workspaces() {
        return List.copyOf(workspaces.values());
    }

    @Override
    public List<TitanGraphqlManagedModel> models() {
        return List.copyOf(models.values());
    }

    @Override
    public List<TitanGraphqlModelDraft> drafts() {
        return List.copyOf(drafts.values());
    }

    @Override
    public List<TitanGraphqlValidationReportRef> validationReports() {
        return List.copyOf(validationReports.values());
    }

    @Override
    public List<TitanGraphqlArtifactSetRef> artifactSets() {
        return List.copyOf(artifactSets.values());
    }

    @Override
    public List<TitanGraphqlDeployment> deployments() {
        return List.copyOf(deployments.values());
    }

    @Override
    public List<TitanGraphqlObservedOperation> observedOperations() {
        return List.copyOf(observedOperations.values());
    }

    @Override
    public List<TitanGraphqlObservedOperation> observedOperations(String modelId, String environment) {
        return observedOperations.values().stream()
                .filter(operation -> operation.modelId().equals(modelId))
                .filter(operation -> operation.environment().equals(environment))
                .toList();
    }

    @Override
    public List<TitanGraphqlObservedOperation> observedOperations(String modelId, String environment, String role) {
        return observedOperations.values().stream()
                .filter(operation -> operation.modelId().equals(modelId))
                .filter(operation -> operation.environment().equals(environment))
                .filter(operation -> operation.role().equals(role))
                .toList();
    }

    @Override
    public List<TitanGraphqlUsageReport> usageReports() {
        return List.copyOf(usageReports.values());
    }

    @Override
    public List<TitanGraphqlUsageReport> usageReports(String modelId, String environment) {
        return usageReports.values().stream()
                .filter(report -> report.modelId().equals(modelId))
                .filter(report -> report.environment().equals(environment))
                .toList();
    }

    @Override
    public List<TitanGraphqlUsageReport> usageReports(
            String modelId,
            String environment,
            String version,
            String window
    ) {
        String normalizedVersion = ManagementSupport.textOrEmpty(version);
        String normalizedWindow = ManagementSupport.requireText(window, "usageReport.window");
        return usageReports.values().stream()
                .filter(report -> report.modelId().equals(modelId))
                .filter(report -> report.environment().equals(environment))
                .filter(report -> report.version().equals(normalizedVersion))
                .filter(report -> report.window().equals(normalizedWindow))
                .toList();
    }

    @Override
    public List<TitanGraphqlPreviewContractTestReport> previewContractTestReports(String previewBuildId) {
        return previewContractTestReports.values().stream()
                .filter(report -> report.previewBuildId().equals(previewBuildId))
                .toList();
    }

    private TitanGraphqlObservedOperation reviewObservedOperation(
            String observedOperationId,
            TitanGraphqlObservedOperation.ObservedOperationStatus status
    ) {
        TitanGraphqlObservedOperation operation = observedOperations.get(observedOperationId);
        if (operation == null) {
            throw new IllegalArgumentException("unknown observed operation '" + observedOperationId + "'");
        }
        TitanGraphqlObservedOperation reviewed = operation.withStatus(status);
        observedOperations.put(reviewed.id(), reviewed);
        return reviewed;
    }

    private void upsertRegistryOperation(
            TitanGraphqlObservedOperation observed,
            TitanGraphqlOperationRegistry.RegisteredOperationStatus status,
            String reviewedBy,
            String reviewedAt
    ) {
        String registryId = operationRegistryId(observed.modelId(), observed.environment());
        TitanGraphqlOperationRegistry existing = operationRegistries.get(registryId);
        List<TitanGraphqlOperationRegistry.RegisteredOperation> operations = existing == null
                ? List.of()
                : existing.operations();
        java.util.ArrayList<TitanGraphqlOperationRegistry.RegisteredOperation> nextOperations = new java.util.ArrayList<>();
        boolean replaced = false;
        for (TitanGraphqlOperationRegistry.RegisteredOperation operation : operations) {
            if (operation.operationHash().equals(observed.operationHash())
                    && operation.roles().contains(observed.role())
                    && operation.clients().contains(observed.client())) {
                nextOperations.add(registryOperation(observed, status, reviewedBy, reviewedAt));
                replaced = true;
            } else {
                nextOperations.add(operation);
            }
        }
        if (replaced == false) {
            nextOperations.add(registryOperation(observed, status, reviewedBy, reviewedAt));
        }
        operationRegistries.put(registryId, new TitanGraphqlOperationRegistry(
                registryId,
                observed.modelId(),
                observed.environment(),
                existing == null ? TitanGraphqlOperationRegistry.RegistryMode.OBSERVE : existing.mode(),
                nextOperations,
                ManagementSupport.textOrEmpty(reviewedAt)
        ));
    }

    private static TitanGraphqlOperationRegistry.RegisteredOperation registryOperation(
            TitanGraphqlObservedOperation observed,
            TitanGraphqlOperationRegistry.RegisteredOperationStatus status,
            String reviewedBy,
            String reviewedAt
    ) {
        return new TitanGraphqlOperationRegistry.RegisteredOperation(
                "registry-operation-" + observed.id(),
                observed.operationName(),
                observed.operationHash(),
                observed.document(),
                status,
                List.of(observed.role()),
                List.of(observed.client()),
                observed.depth(),
                observed.estimatedCost(),
                observed.fieldUsage(),
                observed.lastSeenAt(),
                reviewedBy,
                reviewedAt
        );
    }

    public static String operationRegistryId(String modelId, String environment) {
        return "registry-" + stableId(modelId) + "-" + stableId(environment);
    }

    private static String stableId(String text) {
        String normalized = ManagementSupport.requireText(text, "operationRegistry.identity")
                .toLowerCase(java.util.Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return normalized.isBlank() ? "unnamed" : normalized;
    }
}
