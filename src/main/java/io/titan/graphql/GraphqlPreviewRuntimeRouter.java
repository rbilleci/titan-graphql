package io.titan.graphql;

import io.titan.graphql.management.TitanGraphqlOperationRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class GraphqlPreviewRuntimeRouter {

    private static final Map<String, PreviewCandidate> CANDIDATES = new ConcurrentHashMap<>();

    private GraphqlPreviewRuntimeRouter() {
    }

    record PreviewCandidate(
            String previewBuildId,
            String artifactSetId,
            GraphqlDataModel dataModel,
            TitanGraphqlOperationRegistry operationRegistry
    ) {
        PreviewCandidate {
            previewBuildId = requireText(previewBuildId, "previewBuildId");
            artifactSetId = requireText(artifactSetId, "artifactSetId");
            if (dataModel == null) {
                throw new IllegalArgumentException("preview data model is required");
            }
        }
    }

    static void registerCandidate(String previewBuildId, String artifactSetId, GraphqlDataModel dataModel) {
        registerCandidate(previewBuildId, artifactSetId, dataModel, null);
    }

    static void registerCandidate(
            String previewBuildId,
            String artifactSetId,
            GraphqlDataModel dataModel,
            TitanGraphqlOperationRegistry operationRegistry
    ) {
        PreviewCandidate candidate = new PreviewCandidate(previewBuildId, artifactSetId, dataModel, operationRegistry);
        CANDIDATES.put(candidate.previewBuildId(), candidate);
    }

    static void clearCandidatesForTests() {
        CANDIDATES.clear();
    }

    static GraphqlExecution execute(
            String previewBuildId,
            GraphqlRequest request,
            GraphqlRequestContext context
    ) {
        PreviewCandidate candidate = CANDIDATES.get(previewBuildId == null ? "" : previewBuildId);
        if (candidate == null) {
            return new GraphqlExecution(
                    GraphqlJsonWriter.error(
                            "preview build '" + (previewBuildId == null ? "" : previewBuildId) + "' is not registered",
                            GraphqlException.VALIDATION_ERROR
                    ),
                    new GraphqlPlan()
            );
        }
        return executeCandidate(candidate, request, context, candidate.operationRegistry());
    }

    static GraphqlExecution execute(
            String previewBuildId,
            GraphqlRequest request,
            GraphqlRequestContext context,
            TitanGraphqlOperationRegistry operationRegistry
    ) {
        PreviewCandidate candidate = CANDIDATES.get(previewBuildId == null ? "" : previewBuildId);
        if (candidate == null) {
            return new GraphqlExecution(
                    GraphqlJsonWriter.error(
                            "preview build '" + (previewBuildId == null ? "" : previewBuildId) + "' is not registered",
                            GraphqlException.VALIDATION_ERROR
                    ),
                    new GraphqlPlan()
            );
        }
        TitanGraphqlOperationRegistry effectiveRegistry = operationRegistry == null
                ? candidate.operationRegistry()
                : operationRegistry;
        return executeCandidate(candidate, request, context, effectiveRegistry);
    }

    private static GraphqlExecution executeCandidate(
            PreviewCandidate candidate,
            GraphqlRequest request,
            GraphqlRequestContext context,
            TitanGraphqlOperationRegistry operationRegistry
    ) {
        GraphqlOperationRegistryEnforcement.Decision decision =
                GraphqlOperationRegistryEnforcement.evaluate(operationRegistry, request, context);
        if (decision.allowed() == false) {
            return new GraphqlExecution(decision.errorJson(), new GraphqlPlan());
        }
        GraphqlExecution execution = GraphqlEngine.execute(candidate.dataModel(), new GraphqlJsonWriter(), request, context);
        return new GraphqlExecution(decision.applyWarning(execution.json()), execution.plan());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
