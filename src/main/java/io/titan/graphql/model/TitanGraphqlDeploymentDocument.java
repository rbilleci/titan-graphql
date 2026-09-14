package io.titan.graphql.model;

import java.util.List;

public record TitanGraphqlDeploymentDocument(
        String environment,
        String previewEndpoint,
        String runtimeEndpoint,
        List<String> requiredApprovals
) {
    public TitanGraphqlDeploymentDocument {
        environment = ModelDocumentSupport.textOrEmpty(environment);
        previewEndpoint = ModelDocumentSupport.textOrEmpty(previewEndpoint);
        runtimeEndpoint = ModelDocumentSupport.textOrEmpty(runtimeEndpoint);
        requiredApprovals = ModelDocumentSupport.listOrEmpty(requiredApprovals);
    }

    public static TitanGraphqlDeploymentDocument empty() {
        return new TitanGraphqlDeploymentDocument("", "", "", List.of());
    }
}
