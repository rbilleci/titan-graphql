package io.titan.graphql.model;

import java.util.List;

public record TitanGraphqlModelDocument(
        String apiVersion,
        String kind,
        TitanGraphqlModelMetadata metadata,
        TitanGraphqlDatabaseDocument database,
        List<TitanGraphqlModuleDocument> modules,
        List<TitanGraphqlRootDocument> roots,
        List<TitanGraphqlTypeDocument> types,
        List<TitanGraphqlPolicyDocument> policies,
        List<TitanGraphqlContextFilterDocument> contextFilters,
        TitanGraphqlArtifactOptions artifacts,
        TitanGraphqlDeploymentDocument deployment
) {
    public static final String CURRENT_API_VERSION = "titan.graphql/v1alpha1";
    public static final String PROJECTION_MODEL_KIND = "ProjectionModel";

    public TitanGraphqlModelDocument {
        apiVersion = defaultText(apiVersion, CURRENT_API_VERSION);
        kind = defaultText(kind, PROJECTION_MODEL_KIND);
        if (metadata == null) {
            throw new IllegalArgumentException("metadata is required");
        }
        database = database == null ? TitanGraphqlDatabaseDocument.empty() : database;
        modules = ModelDocumentSupport.listOrEmpty(modules);
        roots = ModelDocumentSupport.listOrEmpty(roots);
        types = ModelDocumentSupport.listOrEmpty(types);
        policies = ModelDocumentSupport.listOrEmpty(policies);
        contextFilters = ModelDocumentSupport.listOrEmpty(contextFilters);
        artifacts = artifacts == null ? TitanGraphqlArtifactOptions.defaults() : artifacts;
        deployment = deployment == null ? TitanGraphqlDeploymentDocument.empty() : deployment;
    }

    public TitanGraphqlModelDocument(
            TitanGraphqlModelMetadata metadata,
            List<TitanGraphqlRootDocument> roots,
            List<TitanGraphqlTypeDocument> types
    ) {
        this(
                CURRENT_API_VERSION,
                PROJECTION_MODEL_KIND,
                metadata,
                TitanGraphqlDatabaseDocument.empty(),
                List.of(),
                roots,
                types,
                List.of(),
                List.of(),
                TitanGraphqlArtifactOptions.defaults(),
                TitanGraphqlDeploymentDocument.empty()
        );
    }

    private static String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
