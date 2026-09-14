package io.titan.graphql;

import io.titan.graphql.management.TitanGraphqlOperationRegistry;
import io.titan.graphql.management.TitanGraphqlPreviewBuild;
import io.titan.graphql.management.TitanGraphqlPreviewContractTestReport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

final class GraphqlPreviewContractTestRunner {

    private GraphqlPreviewContractTestRunner() {
    }

    static TitanGraphqlPreviewContractTestReport run(
            TitanGraphqlPreviewBuild previewBuild,
            TitanGraphqlOperationRegistry registry,
            GraphqlRequestContext context,
            String createdAt
    ) {
        if (previewBuild == null) {
            throw new IllegalArgumentException("preview build is required");
        }
        if (registry == null) {
            throw new IllegalArgumentException("operation registry is required");
        }
        if (previewBuild.modelId().equals(registry.modelId()) == false) {
            throw new IllegalArgumentException("preview build model does not match operation registry model");
        }
        if (previewBuild.environment().equals(registry.environment()) == false) {
            throw new IllegalArgumentException("preview build environment does not match operation registry environment");
        }
        if (previewBuild.operationRegistryId().isBlank() == false
                && previewBuild.operationRegistryId().equals(registry.id()) == false) {
            throw new IllegalArgumentException("preview build operation registry does not match supplied registry");
        }

        List<TitanGraphqlPreviewContractTestReport.OperationResult> results = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        for (TitanGraphqlOperationRegistry.RegisteredOperation operation : registry.operations()) {
            if (operation.status() != TitanGraphqlOperationRegistry.RegisteredOperationStatus.APPROVED) {
                skipped++;
                results.add(new TitanGraphqlPreviewContractTestReport.OperationResult(
                        operation.id(),
                        operation.operationName(),
                        operation.operationHash(),
                        TitanGraphqlPreviewContractTestReport.ContractTestOperationStatus.SKIPPED,
                        "",
                        "operation status " + operation.status() + " is not approved for preview contract testing"
                ));
                continue;
            }

            GraphqlExecution execution = GraphqlPreviewRuntimeRouter.execute(
                    previewBuild.id(),
                    new GraphqlRequest(operation.document(), operation.operationName(), Map.of(), clientExtension(operation)),
                    context == null ? GraphqlRequestContext.legacy(0L, "") : context,
                    registry
            );
            String responseHash = sha256(execution.json());
            if (execution.json().contains("\"errors\"")) {
                failed++;
                results.add(new TitanGraphqlPreviewContractTestReport.OperationResult(
                        operation.id(),
                        operation.operationName(),
                        operation.operationHash(),
                        TitanGraphqlPreviewContractTestReport.ContractTestOperationStatus.FAIL,
                        responseHash,
                        "preview execution returned GraphQL errors"
                ));
            } else {
                passed++;
                results.add(new TitanGraphqlPreviewContractTestReport.OperationResult(
                        operation.id(),
                        operation.operationName(),
                        operation.operationHash(),
                        TitanGraphqlPreviewContractTestReport.ContractTestOperationStatus.PASS,
                        responseHash,
                        "preview execution completed without GraphQL errors"
                ));
            }
        }

        TitanGraphqlPreviewContractTestReport.ContractTestReportStatus status = failed > 0
                ? TitanGraphqlPreviewContractTestReport.ContractTestReportStatus.FAIL
                : passed > 0
                        ? TitanGraphqlPreviewContractTestReport.ContractTestReportStatus.PASS
                        : TitanGraphqlPreviewContractTestReport.ContractTestReportStatus.BLOCKED;
        String id = "contract-test-" + previewBuild.id() + "-" + shortHash(previewBuild.id() + ":" + registry.id() + ":" + createdAt);
        return new TitanGraphqlPreviewContractTestReport(
                id,
                previewBuild.id(),
                previewBuild.artifactSetId(),
                registry.id(),
                status,
                registry.operations().size(),
                passed,
                failed,
                skipped,
                results,
                createdAt
        );
    }

    private static Map<String, Object> clientExtension(TitanGraphqlOperationRegistry.RegisteredOperation operation) {
        if (operation.clients().size() == 1) {
            return Map.of("client", operation.clients().getFirst());
        }
        return Map.of();
    }

    private static String shortHash(String content) {
        return sha256(content).substring(0, 12);
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
