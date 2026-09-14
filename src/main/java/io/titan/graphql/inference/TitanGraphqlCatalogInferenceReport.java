package io.titan.graphql.inference;

import java.util.List;

record TitanGraphqlCatalogInferenceReport(
        List<InferredObject> inferredObjects,
        List<SkippedObject> skippedObjects,
        List<Warning> warnings,
        List<ReviewDecision> reviewDecisions
) {
    TitanGraphqlCatalogInferenceReport {
        inferredObjects = CatalogSnapshotSupport.listOrEmpty(inferredObjects);
        skippedObjects = CatalogSnapshotSupport.listOrEmpty(skippedObjects);
        warnings = CatalogSnapshotSupport.listOrEmpty(warnings);
        reviewDecisions = CatalogSnapshotSupport.listOrEmpty(reviewDecisions);
    }

    record InferredObject(
            String kind,
            String path,
            String output,
            String reason
    ) {
        InferredObject {
            kind = CatalogSnapshotSupport.requireText(kind, "inferredObject.kind");
            path = CatalogSnapshotSupport.requireText(path, "inferredObject.path");
            output = CatalogSnapshotSupport.textOrEmpty(output);
            reason = CatalogSnapshotSupport.textOrEmpty(reason);
        }
    }

    record SkippedObject(
            String kind,
            String path,
            String reason
    ) {
        SkippedObject {
            kind = CatalogSnapshotSupport.requireText(kind, "skippedObject.kind");
            path = CatalogSnapshotSupport.requireText(path, "skippedObject.path");
            reason = CatalogSnapshotSupport.requireText(reason, "skippedObject.reason");
        }
    }

    record Warning(
            String code,
            String path,
            String message
    ) {
        Warning {
            code = CatalogSnapshotSupport.requireText(code, "warning.code");
            path = CatalogSnapshotSupport.requireText(path, "warning.path");
            message = CatalogSnapshotSupport.requireText(message, "warning.message");
        }
    }

    record ReviewDecision(
            String code,
            String path,
            String message
    ) {
        ReviewDecision {
            code = CatalogSnapshotSupport.requireText(code, "reviewDecision.code");
            path = CatalogSnapshotSupport.requireText(path, "reviewDecision.path");
            message = CatalogSnapshotSupport.requireText(message, "reviewDecision.message");
        }
    }
}
