package io.titan.graphql.inference;

import java.util.List;

final class CatalogSnapshotSupport {

    private CatalogSnapshotSupport() {
    }

    static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    static String textOrEmpty(String value) {
        return value == null ? "" : value;
    }

    static <T> List<T> listOrEmpty(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    static List<String> nonEmptyTextList(List<String> values, String fieldName) {
        List<String> copy = listOrEmpty(values);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        for (String value : copy) {
            requireText(value, fieldName);
        }
        return copy;
    }
}
