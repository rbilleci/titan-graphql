package io.titan.graphql;

public final class GraphqlRelationArgumentDescriptor {

    // Renamed from Kind under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum RelationArgumentKind {
        INT_EQUALS,
        RELAY_FIRST,
        RELAY_AFTER,
        RELAY_LAST,
        RELAY_BEFORE
    }

    private final String name;
    private final String columnName;
    private final RelationArgumentKind kind;
    private final String filterPath;
    private final int filterHopCount;

    private GraphqlRelationArgumentDescriptor(
            String name,
            String columnName,
            RelationArgumentKind kind,
            String filterPath,
            int filterHopCount
    ) {
        this.name = name;
        this.columnName = columnName;
        this.kind = kind;
        this.filterPath = filterPath;
        this.filterHopCount = filterHopCount;
    }

    public static GraphqlRelationArgumentDescriptor intEquals(String name, String columnName) {
        return intEquals(name, columnName, columnName, 0);
    }

    static GraphqlRelationArgumentDescriptor intEquals(
            String name,
            String columnName,
            String filterPath,
            int filterHopCount
    ) {
        return new GraphqlRelationArgumentDescriptor(name, columnName, RelationArgumentKind.INT_EQUALS, filterPath, filterHopCount);
    }

    public static GraphqlRelationArgumentDescriptor relayFirst() {
        return new GraphqlRelationArgumentDescriptor("first", "", RelationArgumentKind.RELAY_FIRST, "", 0);
    }

    public static GraphqlRelationArgumentDescriptor relayAfter() {
        return new GraphqlRelationArgumentDescriptor("after", "", RelationArgumentKind.RELAY_AFTER, "", 0);
    }

    public static GraphqlRelationArgumentDescriptor relayLast() {
        return new GraphqlRelationArgumentDescriptor("last", "", RelationArgumentKind.RELAY_LAST, "", 0);
    }

    public static GraphqlRelationArgumentDescriptor relayBefore() {
        return new GraphqlRelationArgumentDescriptor("before", "", RelationArgumentKind.RELAY_BEFORE, "", 0);
    }

    public String name() {
        return name;
    }

    public String columnName() {
        return columnName;
    }

    public RelationArgumentKind kind() {
        return kind;
    }

    public String filterPath() {
        return filterPath;
    }

    public int filterHopCount() {
        return filterHopCount;
    }
}
