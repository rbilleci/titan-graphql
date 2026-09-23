package io.titan.graphql.model;

import java.util.List;

/**
 * A reviewed custom mutation binding.
 *
 * <p>Custom mutations stay explicit, but their request decoding, authorization, SQL binding,
 * response shaping, and transaction outcome are emitted into the database engine. This is not a
 * JVM resolver contract.</p>
 */
public record TitanGraphqlMutationDocument(
        String name,
        MutationDocumentOperation operation,
        String type,
        List<MutationDocumentArgument> arguments,
        MutationDocumentInput input,
        List<MutationDocumentInputBinding> inputBindings,
        List<String> policies,
        List<MutationDocumentPayloadField> payload
) {
    public enum MutationDocumentOperation {
        UPDATE
    }

    public record MutationDocumentArgument(
            String name,
            String type,
            String column,
            boolean key,
            String defaultValue
    ) {
        public MutationDocumentArgument {
            defaultValue = ModelDocumentSupport.textOrEmpty(defaultValue);
        }

        public MutationDocumentArgument(String name, String type, String column, boolean key) {
            this(name, type, column, key, "");
        }
    }

    /** One public input-object argument for a custom mutation. */
    public record MutationDocumentInput(
            String name,
            String type,
            String defaultValue
    ) {
        public MutationDocumentInput {
            name = ModelDocumentSupport.requireText(name, "mutation.input.name");
            type = ModelDocumentSupport.requireText(type, "mutation.input.type");
            defaultValue = ModelDocumentSupport.textOrEmpty(defaultValue);
        }

        public MutationDocumentInput(String name, String type) {
            this(name, type, "");
        }
    }

    /** Maps a field path inside {@link MutationDocumentInput} to one reviewed database column. */
    public record MutationDocumentInputBinding(
            String name,
            String path,
            String type,
            String column,
            boolean key
    ) {
        public MutationDocumentInputBinding {
            name = ModelDocumentSupport.requireText(name, "mutation.inputBinding.name");
            path = ModelDocumentSupport.requireText(path, "mutation.inputBinding.path");
            type = ModelDocumentSupport.requireText(type, "mutation.inputBinding.type");
            column = ModelDocumentSupport.requireText(column, "mutation.inputBinding.column");
        }
    }

    public record MutationDocumentPayloadField(
            String name,
            String argument
    ) {
    }

    public TitanGraphqlMutationDocument {
        arguments = ModelDocumentSupport.listOrEmpty(arguments);
        inputBindings = ModelDocumentSupport.listOrEmpty(inputBindings);
        policies = ModelDocumentSupport.listOrEmpty(policies);
        payload = ModelDocumentSupport.listOrEmpty(payload);
    }

    /** Compatibility constructor for existing flat scalar-argument mutation bindings. */
    public TitanGraphqlMutationDocument(
            String name,
            MutationDocumentOperation operation,
            String type,
            List<MutationDocumentArgument> arguments,
            List<String> policies,
            List<MutationDocumentPayloadField> payload
    ) {
        this(name, operation, type, arguments, null, List.of(), policies, payload);
    }
}
