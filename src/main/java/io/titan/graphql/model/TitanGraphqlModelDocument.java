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
        List<TitanGraphqlInterfaceDocument> interfaces,
        List<TitanGraphqlUnionDocument> unions,
        List<TitanGraphqlEnumDocument> enums,
        List<TitanGraphqlInputObjectDocument> inputObjects,
        List<TitanGraphqlDirectiveDocument> directives,
        List<TitanGraphqlPolicyDocument> policies,
        List<TitanGraphqlMutationDocument> mutations,
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
        interfaces = ModelDocumentSupport.listOrEmpty(interfaces);
        unions = ModelDocumentSupport.listOrEmpty(unions);
        enums = ModelDocumentSupport.listOrEmpty(enums);
        inputObjects = ModelDocumentSupport.listOrEmpty(inputObjects);
        directives = ModelDocumentSupport.listOrEmpty(directives);
        policies = ModelDocumentSupport.listOrEmpty(policies);
        mutations = ModelDocumentSupport.listOrEmpty(mutations);
        contextFilters = ModelDocumentSupport.listOrEmpty(contextFilters);
        artifacts = artifacts == null ? TitanGraphqlArtifactOptions.defaults() : artifacts;
        deployment = deployment == null ? TitanGraphqlDeploymentDocument.empty() : deployment;
    }

    /** Compatibility constructor for documents authored before executable directives. */
    public TitanGraphqlModelDocument(
            String apiVersion,
            String kind,
            TitanGraphqlModelMetadata metadata,
            TitanGraphqlDatabaseDocument database,
            List<TitanGraphqlModuleDocument> modules,
            List<TitanGraphqlRootDocument> roots,
            List<TitanGraphqlTypeDocument> types,
            List<TitanGraphqlInterfaceDocument> interfaces,
            List<TitanGraphqlUnionDocument> unions,
            List<TitanGraphqlEnumDocument> enums,
            List<TitanGraphqlInputObjectDocument> inputObjects,
            List<TitanGraphqlPolicyDocument> policies,
            List<TitanGraphqlMutationDocument> mutations,
            List<TitanGraphqlContextFilterDocument> contextFilters,
            TitanGraphqlArtifactOptions artifacts,
            TitanGraphqlDeploymentDocument deployment
    ) {
        this(apiVersion, kind, metadata, database, modules, roots, types, interfaces, unions, enums,
                inputObjects, List.of(), policies, mutations, contextFilters, artifacts, deployment);
    }

    /** Compatibility constructor for documents authored before abstract output types. */
    public TitanGraphqlModelDocument(
            String apiVersion,
            String kind,
            TitanGraphqlModelMetadata metadata,
            TitanGraphqlDatabaseDocument database,
            List<TitanGraphqlModuleDocument> modules,
            List<TitanGraphqlRootDocument> roots,
            List<TitanGraphqlTypeDocument> types,
            List<TitanGraphqlEnumDocument> enums,
            List<TitanGraphqlInputObjectDocument> inputObjects,
            List<TitanGraphqlPolicyDocument> policies,
            List<TitanGraphqlMutationDocument> mutations,
            List<TitanGraphqlContextFilterDocument> contextFilters,
            TitanGraphqlArtifactOptions artifacts,
            TitanGraphqlDeploymentDocument deployment
    ) {
        this(apiVersion, kind, metadata, database, modules, roots, types, List.of(), List.of(), enums,
                inputObjects, List.of(), policies, mutations, contextFilters, artifacts, deployment);
    }

    /** Compatibility constructor for v1alpha1 documents that do not declare input objects. */
    public TitanGraphqlModelDocument(
            String apiVersion,
            String kind,
            TitanGraphqlModelMetadata metadata,
            TitanGraphqlDatabaseDocument database,
            List<TitanGraphqlModuleDocument> modules,
            List<TitanGraphqlRootDocument> roots,
            List<TitanGraphqlTypeDocument> types,
            List<TitanGraphqlEnumDocument> enums,
            List<TitanGraphqlPolicyDocument> policies,
            List<TitanGraphqlMutationDocument> mutations,
            List<TitanGraphqlContextFilterDocument> contextFilters,
            TitanGraphqlArtifactOptions artifacts,
            TitanGraphqlDeploymentDocument deployment
    ) {
        this(apiVersion, kind, metadata, database, modules, roots, types, List.of(), List.of(), enums, List.of(), List.of(),
                policies, mutations, contextFilters, artifacts, deployment);
    }

    /** Compatibility constructor for v1alpha1 documents that do not declare schema enums. */
    public TitanGraphqlModelDocument(
            String apiVersion,
            String kind,
            TitanGraphqlModelMetadata metadata,
            TitanGraphqlDatabaseDocument database,
            List<TitanGraphqlModuleDocument> modules,
            List<TitanGraphqlRootDocument> roots,
            List<TitanGraphqlTypeDocument> types,
            List<TitanGraphqlPolicyDocument> policies,
            List<TitanGraphqlMutationDocument> mutations,
            List<TitanGraphqlContextFilterDocument> contextFilters,
            TitanGraphqlArtifactOptions artifacts,
            TitanGraphqlDeploymentDocument deployment
    ) {
        this(apiVersion, kind, metadata, database, modules, roots, types, List.of(), List.of(), List.of(), List.of(), List.of(),
                policies, mutations, contextFilters, artifacts, deployment);
    }

    /** Compatibility constructor for v1alpha1 documents that do not declare custom mutations. */
    public TitanGraphqlModelDocument(
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
        this(apiVersion, kind, metadata, database, modules, roots, types, List.of(), List.of(), List.of(), List.of(), List.of(),
                policies, List.of(), contextFilters, artifacts, deployment);
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
                List.of(),
                List.of(),
                List.of(),
                List.of(),
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
