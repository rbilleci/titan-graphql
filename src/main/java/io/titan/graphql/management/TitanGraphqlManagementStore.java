package io.titan.graphql.management;

import java.util.List;

public interface TitanGraphqlManagementStore {

    TitanGraphqlManagementStore saveWorkspace(TitanGraphqlManagedWorkspace workspace);

    TitanGraphqlManagementStore saveModel(TitanGraphqlManagedModel model);

    TitanGraphqlManagementStore saveDraft(TitanGraphqlModelDraft draft);

    TitanGraphqlManagementStore saveValidationReport(TitanGraphqlValidationReportRef report);

    TitanGraphqlManagementStore saveArtifactSet(TitanGraphqlArtifactSetRef artifactSet);

    TitanGraphqlManagementStore saveDriftReport(TitanGraphqlDriftReportRef report);

    TitanGraphqlManagementStore savePreviewBuild(TitanGraphqlPreviewBuild previewBuild);

    TitanGraphqlManagementStore savePreviewContractTestReport(TitanGraphqlPreviewContractTestReport report);

    TitanGraphqlManagementStore saveObservedOperation(TitanGraphqlObservedOperation operation);

    TitanGraphqlManagementStore observeOperation(TitanGraphqlObservedOperation operation);

    TitanGraphqlObservedOperation approveObservedOperation(String observedOperationId, String approvedBy, String approvedAt);

    TitanGraphqlObservedOperation rejectObservedOperation(String observedOperationId, String rejectedBy, String rejectedAt);

    TitanGraphqlManagementStore saveOperationRegistry(TitanGraphqlOperationRegistry registry);

    TitanGraphqlManagementStore saveUsageReport(TitanGraphqlUsageReport report);

    TitanGraphqlManagementStore saveDeployment(TitanGraphqlDeployment deployment);

    TitanGraphqlManagedWorkspace workspace(String id);

    TitanGraphqlManagedModel model(String id);

    TitanGraphqlModelDraft draft(String id);

    TitanGraphqlValidationReportRef validationReport(String id);

    TitanGraphqlArtifactSetRef artifactSet(String id);

    TitanGraphqlDriftReportRef driftReport(String id);

    TitanGraphqlPreviewBuild previewBuild(String id);

    TitanGraphqlPreviewContractTestReport previewContractTestReport(String id);

    TitanGraphqlObservedOperation observedOperation(String id);

    TitanGraphqlOperationRegistry operationRegistry(String id);

    TitanGraphqlUsageReport usageReport(String id);

    TitanGraphqlDeployment deployment(String id);

    List<TitanGraphqlManagedWorkspace> workspaces();

    List<TitanGraphqlManagedModel> models();

    List<TitanGraphqlModelDraft> drafts();

    List<TitanGraphqlValidationReportRef> validationReports();

    List<TitanGraphqlArtifactSetRef> artifactSets();

    List<TitanGraphqlDeployment> deployments();

    List<TitanGraphqlObservedOperation> observedOperations();

    List<TitanGraphqlObservedOperation> observedOperations(String modelId, String environment);

    List<TitanGraphqlObservedOperation> observedOperations(String modelId, String environment, String role);

    List<TitanGraphqlUsageReport> usageReports();

    List<TitanGraphqlUsageReport> usageReports(String modelId, String environment);

    List<TitanGraphqlUsageReport> usageReports(String modelId, String environment, String version, String window);

    List<TitanGraphqlPreviewContractTestReport> previewContractTestReports(String previewBuildId);
}
