package io.titan.graphql.inference;

import io.titan.graphql.model.TitanGraphqlModelDocument;

record TitanGraphqlCatalogModelInferenceResult(
        TitanGraphqlModelDocument document,
        TitanGraphqlCatalogInferenceReport report
) {
    TitanGraphqlCatalogModelInferenceResult {
        if (document == null) {
            throw new IllegalArgumentException("document is required");
        }
        if (report == null) {
            throw new IllegalArgumentException("report is required");
        }
    }
}
