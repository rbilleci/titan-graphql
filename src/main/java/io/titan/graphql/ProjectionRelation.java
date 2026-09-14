package io.titan.graphql;

import java.util.List;

public final class ProjectionRelation {

    // Renamed from Cardinality under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum ProjectionRelationCardinality {
        ONE,
        MANY
    }

    // Renamed from PaginationMode under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum ProjectionRelationPaginationMode {
        NONE,
        RELAY_CONNECTION
    }

    // Renamed from RetrievalShape under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum ProjectionRelationRetrievalShape {
        BATCH_LOOKUP,
        FILTERED_BATCH,
        RELAY_CONNECTION_PAGE
    }

    // Renamed from SortDirection under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public enum ProjectionRelationSortDirection {
        ASC,
        DESC
    }

    public record Retrievals(
            String batchName,
            String filteredBatchName,
            String connectionPageName
    ) {
        public static Retrievals currentDefault(String relationName) {
            return new Retrievals(
                    relationName + "ByParent",
                    relationName + "ByParentFilter",
                    relationName + "ConnectionPage"
            );
        }

        String nameFor(ProjectionRelationRetrievalShape shape) {
            return switch (shape) {
                case BATCH_LOOKUP -> batchName;
                case FILTERED_BATCH -> filteredBatchName;
                case RELAY_CONNECTION_PAGE -> connectionPageName;
            };
        }
    }

    // Renamed from Capabilities under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record ProjectionRelationCapabilities(
            boolean selectable,
            boolean batchable,
            boolean supportsFiltering,
            boolean supportsSorting,
            ProjectionRelationPaginationMode paginationMode,
            int selectionHopBudget,
            int filterHopBudget,
            int sortHopBudget,
            int defaultPageSize,
            int maxPageSize,
            boolean supportsTotalCount
    ) {
        ProjectionRelationCapabilities(
                boolean selectable,
                boolean batchable,
                boolean supportsFiltering,
                boolean supportsSorting,
                ProjectionRelationPaginationMode paginationMode,
                int selectionHopBudget,
                int filterHopBudget,
                int sortHopBudget,
                int defaultPageSize,
                int maxPageSize
        ) {
            this(
                    selectable,
                    batchable,
                    supportsFiltering,
                    supportsSorting,
                    paginationMode,
                    selectionHopBudget,
                    filterHopBudget,
                    sortHopBudget,
                    defaultPageSize,
                    maxPageSize,
                    false
            );
        }

        ProjectionRelationCapabilities(
                boolean selectable,
                boolean batchable,
                boolean supportsFiltering,
                boolean supportsSorting,
                ProjectionRelationPaginationMode paginationMode,
                int selectionHopBudget,
                int filterHopBudget,
                int sortHopBudget,
                int maxPageSize
        ) {
            this(
                    selectable,
                    batchable,
                    supportsFiltering,
                    supportsSorting,
                    paginationMode,
                    selectionHopBudget,
                    filterHopBudget,
                    sortHopBudget,
                    10,
                    maxPageSize
            );
        }

        ProjectionRelationCapabilities(
                boolean selectable,
                boolean batchable,
                boolean supportsFiltering,
                boolean supportsSorting,
                ProjectionRelationPaginationMode paginationMode,
                int selectionHopBudget,
                int filterHopBudget,
                int sortHopBudget
        ) {
            this(
                    selectable,
                    batchable,
                    supportsFiltering,
                    supportsSorting,
                    paginationMode,
                    selectionHopBudget,
                    filterHopBudget,
                    sortHopBudget,
                    10,
                    100
            );
        }

        public static ProjectionRelationCapabilities currentDefault() {
            return new ProjectionRelationCapabilities(true, true, false, false, ProjectionRelationPaginationMode.NONE, 2, 0, 0, 10, 100, false);
        }

        public static ProjectionRelationCapabilities relayConnectionWithTotalCount(
                boolean supportsFiltering,
                boolean supportsSorting,
                int selectionHopBudget,
                int filterHopBudget,
                int sortHopBudget,
                int defaultPageSize,
                int maxPageSize
        ) {
            return new ProjectionRelationCapabilities(
                    true,
                    true,
                    supportsFiltering,
                    supportsSorting,
                    ProjectionRelationPaginationMode.RELAY_CONNECTION,
                    selectionHopBudget,
                    filterHopBudget,
                    sortHopBudget,
                    defaultPageSize,
                    maxPageSize,
                    true
            );
        }
    }

    // Renamed from Argument under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record ProjectionRelationArgument(String name, String columnName, ProjectionRelationArgumentKind kind, String filterPath, int filterHopCount) {
        // Renamed from Kind under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
        // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
        enum ProjectionRelationArgumentKind {
            INT_EQUALS,
            RELAY_FIRST,
            RELAY_AFTER,
            RELAY_LAST,
            RELAY_BEFORE
        }

        public static ProjectionRelationArgument intEquals(String name, String columnName) {
            return intEquals(name, columnName, columnName, 0);
        }

        public static ProjectionRelationArgument intEquals(String name, String columnName, String filterPath, int filterHopCount) {
            return new ProjectionRelationArgument(name, columnName, ProjectionRelationArgumentKind.INT_EQUALS, filterPath, filterHopCount);
        }

        public static ProjectionRelationArgument relayFirst() {
            return new ProjectionRelationArgument("first", "", ProjectionRelationArgumentKind.RELAY_FIRST, "", 0);
        }

        public static ProjectionRelationArgument relayAfter() {
            return new ProjectionRelationArgument("after", "", ProjectionRelationArgumentKind.RELAY_AFTER, "", 0);
        }

        public static ProjectionRelationArgument relayLast() {
            return new ProjectionRelationArgument("last", "", ProjectionRelationArgumentKind.RELAY_LAST, "", 0);
        }

        public static ProjectionRelationArgument relayBefore() {
            return new ProjectionRelationArgument("before", "", ProjectionRelationArgumentKind.RELAY_BEFORE, "", 0);
        }
    }

    // Renamed from SortPath under TG-BLK-005 (closed by titan 705180d — SQL names now qualify by
    // enclosing type); the unique simple name is kept deliberately, it reads better in SQL.
    public record ProjectionRelationSortPath(
            String name,
            String columnName,
            String sortPath,
            int sortHopCount,
            ProjectionRelationSortDirection direction,
            String tieBreakerColumnName
    ) {
        public static ProjectionRelationSortPath ascending(String name, String columnName, String sortPath, int sortHopCount) {
            return new ProjectionRelationSortPath(name, columnName, sortPath, sortHopCount, ProjectionRelationSortDirection.ASC, "id");
        }

        public static ProjectionRelationSortPath descending(String name, String columnName, String sortPath, int sortHopCount) {
            return new ProjectionRelationSortPath(name, columnName, sortPath, sortHopCount, ProjectionRelationSortDirection.DESC, "id");
        }
    }

    private final String name;
    private final String targetTypeName;
    private final String localColumnName;
    private final String targetColumnName;
    private final ProjectionRelationCardinality cardinality;
    private final boolean nullable;
    private final ProjectionRelationCapabilities capabilities;
    private final Retrievals retrievals;
    private final List<ProjectionRelationArgument> arguments;
    private final List<ProjectionRelationSortPath> sortPaths;

    private ProjectionRelation(
            String name,
            String targetTypeName,
            String localColumnName,
            String targetColumnName,
            ProjectionRelationCardinality cardinality,
            boolean nullable,
            ProjectionRelationCapabilities capabilities,
            Retrievals retrievals,
            List<ProjectionRelationArgument> arguments,
            List<ProjectionRelationSortPath> sortPaths
    ) {
        this.name = name;
        this.targetTypeName = targetTypeName;
        this.localColumnName = localColumnName;
        this.targetColumnName = targetColumnName;
        this.cardinality = cardinality;
        this.nullable = nullable;
        this.capabilities = capabilities;
        this.retrievals = retrievals;
        this.arguments = List.copyOf(arguments);
        this.sortPaths = List.copyOf(sortPaths);
    }

    public static ProjectionRelation one(
            String name,
            String targetTypeName,
            String localColumnName,
            String targetColumnName,
            boolean nullable
    ) {
        return one(name, targetTypeName, localColumnName, targetColumnName, nullable, ProjectionRelationCapabilities.currentDefault());
    }

    public static ProjectionRelation one(
            String name,
            String targetTypeName,
            String localColumnName,
            String targetColumnName,
            boolean nullable,
            ProjectionRelationCapabilities capabilities
    ) {
        return new ProjectionRelation(
                name,
                targetTypeName,
                localColumnName,
                targetColumnName,
                ProjectionRelationCardinality.ONE,
                nullable,
                capabilities,
                Retrievals.currentDefault(name),
                List.of(),
                List.of()
        );
    }

    public static ProjectionRelation one(
            String name,
            String targetTypeName,
            String localColumnName,
            String targetColumnName,
            boolean nullable,
            ProjectionRelationCapabilities capabilities,
            List<ProjectionRelationArgument> arguments
    ) {
        return new ProjectionRelation(
                name,
                targetTypeName,
                localColumnName,
                targetColumnName,
                ProjectionRelationCardinality.ONE,
                nullable,
                capabilities,
                Retrievals.currentDefault(name),
                arguments,
                List.of()
        );
    }

    public static ProjectionRelation one(
            String name,
            String targetTypeName,
            String localColumnName,
            String targetColumnName,
            boolean nullable,
            ProjectionRelationCapabilities capabilities,
            List<ProjectionRelationArgument> arguments,
            List<ProjectionRelationSortPath> sortPaths
    ) {
        return new ProjectionRelation(
                name,
                targetTypeName,
                localColumnName,
                targetColumnName,
                ProjectionRelationCardinality.ONE,
                nullable,
                capabilities,
                Retrievals.currentDefault(name),
                arguments,
                sortPaths
        );
    }

    public static ProjectionRelation many(
            String name,
            String targetTypeName,
            String localColumnName,
            String targetColumnName,
            boolean nullable
    ) {
        return many(name, targetTypeName, localColumnName, targetColumnName, nullable, ProjectionRelationCapabilities.currentDefault());
    }

    public static ProjectionRelation many(
            String name,
            String targetTypeName,
            String localColumnName,
            String targetColumnName,
            boolean nullable,
            ProjectionRelationCapabilities capabilities
    ) {
        return new ProjectionRelation(
                name,
                targetTypeName,
                localColumnName,
                targetColumnName,
                ProjectionRelationCardinality.MANY,
                nullable,
                capabilities,
                Retrievals.currentDefault(name),
                List.of(),
                List.of()
        );
    }

    public static ProjectionRelation many(
            String name,
            String targetTypeName,
            String localColumnName,
            String targetColumnName,
            boolean nullable,
            ProjectionRelationCapabilities capabilities,
            List<ProjectionRelationArgument> arguments
    ) {
        return new ProjectionRelation(
                name,
                targetTypeName,
                localColumnName,
                targetColumnName,
                ProjectionRelationCardinality.MANY,
                nullable,
                capabilities,
                Retrievals.currentDefault(name),
                arguments,
                List.of()
        );
    }

    public static ProjectionRelation many(
            String name,
            String targetTypeName,
            String localColumnName,
            String targetColumnName,
            boolean nullable,
            ProjectionRelationCapabilities capabilities,
            List<ProjectionRelationArgument> arguments,
            List<ProjectionRelationSortPath> sortPaths
    ) {
        return new ProjectionRelation(
                name,
                targetTypeName,
                localColumnName,
                targetColumnName,
                ProjectionRelationCardinality.MANY,
                nullable,
                capabilities,
                Retrievals.currentDefault(name),
                arguments,
                sortPaths
        );
    }

    public String name() {
        return name;
    }

    public String targetTypeName() {
        return targetTypeName;
    }

    public String localColumnName() {
        return localColumnName;
    }

    public String targetColumnName() {
        return targetColumnName;
    }

    public ProjectionRelationCardinality cardinality() {
        return cardinality;
    }

    public boolean nullable() {
        return nullable;
    }

    public ProjectionRelationCapabilities capabilities() {
        return capabilities;
    }

    public Retrievals retrievals() {
        return retrievals;
    }

    public String retrievalName(ProjectionRelationRetrievalShape shape) {
        return retrievals.nameFor(shape);
    }

    public List<ProjectionRelationArgument> arguments() {
        return arguments;
    }

    public List<ProjectionRelationSortPath> sortPaths() {
        return sortPaths;
    }
}
