package io.titan.graphql.management;

import java.util.List;

final class ManagementSupport {

    private ManagementSupport() {
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
}
