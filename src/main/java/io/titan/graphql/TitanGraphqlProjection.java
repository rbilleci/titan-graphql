package io.titan.graphql;

import java.util.ArrayList;
import java.util.List;

public final class TitanGraphqlProjection {

    private TitanGraphqlProjection() {
    }

    public static ModelBuilder model() {
        return new ModelBuilder();
    }

    public static final class ModelBuilder {

        private final List<ProjectionRetrieval> roots = new ArrayList<>();
        private final List<ProjectionType> types = new ArrayList<>();

        private ModelBuilder() {
        }

        public ModelBuilder pointRoot(String name, String typeName, String requiredIdArgumentName) {
            roots.add(ProjectionRetrieval.point(name, typeName, requiredIdArgumentName));
            return this;
        }

        public RelayRootBuilder relayConnectionRoot(String name, String typeName) {
            return new RelayRootBuilder(this, name, typeName);
        }

        public TypeBuilder type(String name) {
            return new TypeBuilder(this, name);
        }

        public TitanGraphqlProjectionModel build() {
            return new TitanGraphqlProjectionModel(new ProjectionModel(roots, types));
        }
    }

    public static final class RelayRootBuilder {

        private final ModelBuilder parent;
        private final String name;
        private final String typeName;
        private int defaultPageSize = 10;
        private int maxPageSize = 100;
        private ProjectionRetrieval.RetrievalCursorOrdering cursorOrdering =
                ProjectionRetrieval.RetrievalCursorOrdering.ascending("id", "id", "id");
        private final List<ProjectionRetrieval.RetrievalArgument> arguments = new ArrayList<>();
        private final List<ProjectionRetrieval.RetrievalFilterPath> filterPaths = new ArrayList<>();
        private final List<ProjectionRetrieval.RetrievalSortPath> sortPaths = new ArrayList<>();
        private final List<ProjectionRetrieval.RetrievalContextFilter> contextFilters = new ArrayList<>();

        private RelayRootBuilder(ModelBuilder parent, String name, String typeName) {
            this.parent = parent;
            this.name = name;
            this.typeName = typeName;
            sortPaths.add(ProjectionRetrieval.RetrievalSortPath.ascending("id", "id", "id", 0));
        }

        public RelayRootBuilder pageSize(int defaultPageSize, int maxPageSize) {
            this.defaultPageSize = defaultPageSize;
            this.maxPageSize = maxPageSize;
            return this;
        }

        public RelayRootBuilder argumentIntEquals(String name, String columnName) {
            arguments.add(ProjectionRetrieval.RetrievalArgument.intEquals(name, columnName));
            return this;
        }

        public RelayRootBuilder cursorOrderingAscending(String name, String columnName, String cursorPath) {
            cursorOrdering = ProjectionRetrieval.RetrievalCursorOrdering.ascending(name, columnName, cursorPath);
            return this;
        }

        public RelayRootBuilder filterPathString(
                String name,
                String columnName,
                String filterPath,
                int filterHopCount
        ) {
            filterPaths.add(ProjectionRetrieval.RetrievalFilterPath.string(name, columnName, filterPath, filterHopCount));
            return this;
        }

        public RelayRootBuilder sortPathAscending(
                String name,
                String columnName,
                String sortPath,
                int sortHopCount
        ) {
            sortPaths.add(ProjectionRetrieval.RetrievalSortPath.ascending(name, columnName, sortPath, sortHopCount));
            return this;
        }

        public RelayRootBuilder sortPathDescending(
                String name,
                String columnName,
                String sortPath,
                int sortHopCount
        ) {
            sortPaths.add(ProjectionRetrieval.RetrievalSortPath.descending(name, columnName, sortPath, sortHopCount));
            return this;
        }

        public RelayRootBuilder contextFilterBooleanEquals(String name, String columnName, String contextKey) {
            contextFilters.add(ProjectionRetrieval.RetrievalContextFilter.booleanEquals(name, columnName, contextKey));
            return this;
        }

        public ModelBuilder addRoot() {
            parent.roots.add(ProjectionRetrieval.relayConnection(
                    name,
                    typeName,
                    defaultPageSize,
                    maxPageSize,
                    arguments,
                    cursorOrdering,
                    filterPaths,
                    sortPaths,
                    contextFilters
            ));
            return parent;
        }
    }

    public static final class TypeBuilder {

        private final ModelBuilder parent;
        private final String name;
        private String tableName = "";
        private String schemaName = "public";
        private String physicalTableName = "";
        private String primaryKeyColumnName = "id";
        private final List<ProjectionField> fields = new ArrayList<>();
        private final List<ProjectionRelation> relations = new ArrayList<>();

        private TypeBuilder(ModelBuilder parent, String name) {
            this.parent = parent;
            this.name = name;
        }

        public TypeBuilder table(String tableName) {
            return table("public", tableName, tableName, "id");
        }

        public TypeBuilder table(
                String schemaName,
                String tableName,
                String physicalTableName,
                String primaryKeyColumnName
        ) {
            this.schemaName = schemaName;
            this.tableName = tableName;
            this.physicalTableName = physicalTableName;
            this.primaryKeyColumnName = primaryKeyColumnName;
            return this;
        }

        public TypeBuilder scalarField(String name, String columnName) {
            fields.add(ProjectionField.column(name, columnName));
            return this;
        }

        public TypeBuilder scalarField(
                String name,
                String columnName,
                GraphqlFieldAuthorization authorization
        ) {
            fields.add(ProjectionField.column(name, columnName, authorization::canRead));
            return this;
        }

        public ComputedFieldBuilder computedSqlTemplateField(
                String name,
                String graphqlType,
                String sqlTemplate,
                List<String> requiredColumns
        ) {
            return new ComputedFieldBuilder(this, name, graphqlType, sqlTemplate, requiredColumns);
        }

        public RelationBuilder oneRelation(
                String name,
                String targetTypeName,
                String localColumnName,
                String targetColumnName
        ) {
            return new RelationBuilder(this, name, targetTypeName, localColumnName, targetColumnName, true);
        }

        public RelationBuilder manyRelation(
                String name,
                String targetTypeName,
                String localColumnName,
                String targetColumnName
        ) {
            return new RelationBuilder(this, name, targetTypeName, localColumnName, targetColumnName, false);
        }

        public ModelBuilder addType() {
            String effectiveTableName = tableName.isEmpty() ? name : tableName;
            String effectivePhysicalTableName = physicalTableName.isEmpty() ? effectiveTableName : physicalTableName;
            parent.types.add(new ProjectionType(
                    name,
                    effectiveTableName,
                    schemaName,
                    effectivePhysicalTableName,
                    primaryKeyColumnName,
                    fields,
                    relations
            ));
            return parent;
        }
    }

    public static final class ComputedFieldBuilder {

        private final TypeBuilder parent;
        private final String name;
        private final String graphqlType;
        private final String sqlTemplate;
        private final List<String> requiredColumns;
        private boolean filterable;
        private boolean sortable;
        private GraphqlFieldAuthorization authorization = GraphqlFieldAuthorization.ALLOW;

        private ComputedFieldBuilder(
                TypeBuilder parent,
                String name,
                String graphqlType,
                String sqlTemplate,
                List<String> requiredColumns
        ) {
            this.parent = parent;
            this.name = name;
            this.graphqlType = graphqlType;
            this.sqlTemplate = sqlTemplate;
            this.requiredColumns = List.copyOf(requiredColumns);
        }

        public ComputedFieldBuilder filterable() {
            filterable = true;
            return this;
        }

        public ComputedFieldBuilder sortable() {
            sortable = true;
            return this;
        }

        public ComputedFieldBuilder authorization(GraphqlFieldAuthorization authorization) {
            this.authorization = authorization;
            return this;
        }

        public TypeBuilder addField() {
            ProjectionField.ProjectionComputedExpression expression = new ProjectionField.ProjectionComputedExpression(
                    name,
                    graphqlType,
                    ProjectionField.ProjectionExpressionKind.SQL_TEMPLATE,
                    sqlTemplate,
                    true,
                    filterable,
                    sortable,
                    false,
                    true,
                    false,
                    requiredColumns,
                    ProjectionField.ProjectionCostClass.ROW_LOCAL
            );
            parent.fields.add(ProjectionField.computed(expression, authorization::canRead));
            return parent;
        }
    }

    public static final class RelationBuilder {

        private final TypeBuilder parent;
        private final String name;
        private final String targetTypeName;
        private final String localColumnName;
        private final String targetColumnName;
        private final boolean one;
        private boolean nullable;
        private ProjectionRelation.ProjectionRelationCapabilities capabilities = ProjectionRelation.ProjectionRelationCapabilities.currentDefault();
        private final List<ProjectionRelation.ProjectionRelationArgument> arguments = new ArrayList<>();
        private final List<ProjectionRelation.ProjectionRelationSortPath> sortPaths = new ArrayList<>();

        private RelationBuilder(
                TypeBuilder parent,
                String name,
                String targetTypeName,
                String localColumnName,
                String targetColumnName,
                boolean one
        ) {
            this.parent = parent;
            this.name = name;
            this.targetTypeName = targetTypeName;
            this.localColumnName = localColumnName;
            this.targetColumnName = targetColumnName;
            this.one = one;
        }

        public RelationBuilder nullable() {
            nullable = true;
            return this;
        }

        public RelationBuilder relayConnectionWithTotalCount(
                boolean supportsFiltering,
                boolean supportsSorting,
                int selectionHopBudget,
                int filterHopBudget,
                int sortHopBudget,
                int defaultPageSize,
                int maxPageSize
        ) {
            capabilities = ProjectionRelation.ProjectionRelationCapabilities.relayConnectionWithTotalCount(
                    supportsFiltering,
                    supportsSorting,
                    selectionHopBudget,
                    filterHopBudget,
                    sortHopBudget,
                    defaultPageSize,
                    maxPageSize
            );
            return this;
        }

        public RelationBuilder argumentIntEquals(String name, String columnName) {
            arguments.add(ProjectionRelation.ProjectionRelationArgument.intEquals(name, columnName));
            return this;
        }

        public RelationBuilder relayPaginationArguments() {
            arguments.add(ProjectionRelation.ProjectionRelationArgument.relayFirst());
            arguments.add(ProjectionRelation.ProjectionRelationArgument.relayAfter());
            arguments.add(ProjectionRelation.ProjectionRelationArgument.relayLast());
            arguments.add(ProjectionRelation.ProjectionRelationArgument.relayBefore());
            return this;
        }

        public RelationBuilder sortPathAscending(
                String name,
                String columnName,
                String sortPath,
                int sortHopCount
        ) {
            sortPaths.add(ProjectionRelation.ProjectionRelationSortPath.ascending(name, columnName, sortPath, sortHopCount));
            return this;
        }

        public RelationBuilder sortPathDescending(
                String name,
                String columnName,
                String sortPath,
                int sortHopCount
        ) {
            sortPaths.add(ProjectionRelation.ProjectionRelationSortPath.descending(name, columnName, sortPath, sortHopCount));
            return this;
        }

        public TypeBuilder addRelation() {
            ProjectionRelation relation = one
                    ? ProjectionRelation.one(
                            name,
                            targetTypeName,
                            localColumnName,
                            targetColumnName,
                            nullable,
                            capabilities,
                            arguments,
                            sortPaths
                    )
                    : ProjectionRelation.many(
                            name,
                            targetTypeName,
                            localColumnName,
                            targetColumnName,
                            nullable,
                            capabilities,
                            arguments,
                            sortPaths
                    );
            parent.relations.add(relation);
            return parent;
        }
    }
}
