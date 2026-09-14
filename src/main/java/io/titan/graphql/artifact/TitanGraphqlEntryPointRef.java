package io.titan.graphql.artifact;

import java.util.List;

/**
 * One packaged stored-function entry point as published by core in the real
 * {@code titan-artifact.json} manifest. Replaces the former runtime-reflection scan of the
 * kernel class: the Java identity comes from the manifest's {@code entryPoints[].java}
 * record, the SQL identities from {@code entryPoints[].sql[]} joined against the object
 * inventory.
 */
public record TitanGraphqlEntryPointRef(
        String id,
        String className,
        String methodName,
        List<String> parameterTypes,
        String securityMode,
        List<TitanGraphqlSqlRoutineRef> routines
) {
    public TitanGraphqlEntryPointRef {
        id = requireText(id, "entryPoint.id");
        className = requireText(className, "entryPoint.className");
        methodName = requireText(methodName, "entryPoint.methodName");
        parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
        securityMode = securityMode == null ? "" : securityMode;
        routines = routines == null ? List.of() : List.copyOf(routines);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
