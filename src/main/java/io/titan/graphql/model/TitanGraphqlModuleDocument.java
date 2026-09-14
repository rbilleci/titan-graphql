package io.titan.graphql.model;

import java.util.List;

public record TitanGraphqlModuleDocument(
        String name,
        String owner,
        List<String> roots,
        List<String> types,
        List<String> fields,
        List<String> relations,
        List<String> policies
) {
    public TitanGraphqlModuleDocument {
        name = ModelDocumentSupport.requireText(name, "module.name");
        owner = ModelDocumentSupport.textOrEmpty(owner);
        roots = ModelDocumentSupport.listOrEmpty(roots);
        types = ModelDocumentSupport.listOrEmpty(types);
        fields = ModelDocumentSupport.listOrEmpty(fields);
        relations = ModelDocumentSupport.listOrEmpty(relations);
        policies = ModelDocumentSupport.listOrEmpty(policies);
    }
}
