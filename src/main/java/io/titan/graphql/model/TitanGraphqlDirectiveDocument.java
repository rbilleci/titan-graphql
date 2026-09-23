package io.titan.graphql.model;

import java.util.List;

/**
 * A reviewed executable directive whose behavior is compiled into the database language engine.
 *
 * <p>The v1alpha1 contract intentionally exposes only conditional selection behavior. This keeps
 * directive execution deterministic and schema-driven: a directive either includes its attached
 * selection when {@code if} is true or skips it when {@code if} is true. Both behaviors have the
 * generated argument {@code if: Boolean!}; arbitrary resolver callbacks are not permitted.</p>
 */
public record TitanGraphqlDirectiveDocument(
        String name,
        String description,
        List<ExecutableLocation> locations,
        Behavior behavior
) {
    public TitanGraphqlDirectiveDocument {
        name = ModelDocumentSupport.requireText(name, "directive.name");
        description = ModelDocumentSupport.textOrEmpty(description);
        locations = ModelDocumentSupport.listOrEmpty(locations);
    }

    public enum ExecutableLocation {
        FIELD,
        FRAGMENT_SPREAD,
        INLINE_FRAGMENT
    }

    public enum Behavior {
        INCLUDE_IF,
        SKIP_IF
    }
}
