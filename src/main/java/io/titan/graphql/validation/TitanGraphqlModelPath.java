package io.titan.graphql.validation;

import java.util.List;

public record TitanGraphqlModelPath(List<String> segments) {

    public TitanGraphqlModelPath {
        if (segments == null) {
            segments = List.of();
        }
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                throw new IllegalArgumentException("model path segments must be non-empty");
            }
        }
        segments = List.copyOf(segments);
    }

    public static TitanGraphqlModelPath root() {
        return new TitanGraphqlModelPath(List.of());
    }

    public static TitanGraphqlModelPath of(String... segments) {
        return new TitanGraphqlModelPath(List.of(segments));
    }

    public boolean isRoot() {
        return segments.isEmpty();
    }

    public String displayPath() {
        return isRoot() ? "$" : "$." + String.join(".", segments);
    }
}
