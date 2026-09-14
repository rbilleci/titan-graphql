package io.titan.graphql.validation;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record TitanGraphqlValidationIssue(
        TitanGraphqlValidationIssueCode code,
        TitanGraphqlValidationSeverity severity,
        String message,
        TitanGraphqlModelPath modelPath,
        TitanGraphqlSourceLocation sourceLocation,
        boolean blocksDeployment,
        Map<String, String> metadata
) {

    public TitanGraphqlValidationIssue {
        if (code == null) {
            throw new IllegalArgumentException("validation issue code is required");
        }
        if (severity == null) {
            throw new IllegalArgumentException("validation issue severity is required");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("validation issue message is required");
        }
        if (modelPath == null) {
            modelPath = TitanGraphqlModelPath.root();
        }
        if (sourceLocation == null) {
            sourceLocation = TitanGraphqlSourceLocation.none();
        }
        if (metadata == null) {
            metadata = Map.of();
        } else {
            TreeMap<String, String> sorted = new TreeMap<>();
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("validation issue metadata keys must be non-empty");
                }
                sorted.put(key, value == null ? "" : value);
            }
            metadata = Collections.unmodifiableMap(sorted);
        }
    }

    public static TitanGraphqlValidationIssue error(
            TitanGraphqlValidationIssueCode code,
            String message,
            TitanGraphqlModelPath modelPath,
            TitanGraphqlSourceLocation sourceLocation
    ) {
        return new TitanGraphqlValidationIssue(
                code,
                TitanGraphqlValidationSeverity.ERROR,
                message,
                modelPath,
                sourceLocation,
                true,
                Map.of()
        );
    }
}
