package io.titan.graphql.artifact;

/**
 * One deployed SQL routine behind a packaged Titan entry point, joined from the real
 * {@code titan-artifact.json} entry-point record and the matching
 * {@code titan-object-inventory.json} object (kind, schema-qualified name, signature).
 */
public record TitanGraphqlSqlRoutineRef(
        String dialect,
        String objectId,
        String objectKind,
        String schemaName,
        String routineName,
        String qualifiedName,
        String signature,
        String returnType
) {
    public TitanGraphqlSqlRoutineRef {
        dialect = requireText(dialect, "sqlRoutine.dialect");
        objectId = requireText(objectId, "sqlRoutine.objectId");
        objectKind = requireText(objectKind, "sqlRoutine.objectKind");
        schemaName = requireText(schemaName, "sqlRoutine.schemaName");
        routineName = requireText(routineName, "sqlRoutine.routineName");
        qualifiedName = requireText(qualifiedName, "sqlRoutine.qualifiedName");
        signature = signature == null ? "" : signature;
        returnType = returnType == null ? "" : returnType;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
