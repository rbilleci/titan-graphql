package io.titan.graphql;

public final class GraphqlRootArgumentDescriptor {

    // Renamed from Kind under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RootArgumentKind {
        INT_EQUALS
    }

    private final String name;
    private final String columnName;
    private final RootArgumentKind kind;

    public GraphqlRootArgumentDescriptor(String name, String columnName, RootArgumentKind kind) {
        this.name = name;
        this.columnName = columnName;
        this.kind = kind;
    }

    public static GraphqlRootArgumentDescriptor intEquals(String name, String columnName) {
        return new GraphqlRootArgumentDescriptor(name, columnName, RootArgumentKind.INT_EQUALS);
    }

    public String name() {
        return name;
    }

    public String columnName() {
        return columnName;
    }

    public RootArgumentKind kind() {
        return kind;
    }
}
