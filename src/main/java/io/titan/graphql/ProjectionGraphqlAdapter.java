package io.titan.graphql;

import java.util.ArrayList;
import java.util.List;

public final class ProjectionGraphqlAdapter {

    private ProjectionGraphqlAdapter() {
    }

    public static GraphqlSchema adapt(ProjectionModel model) {
        List<GraphqlRootField> roots = new ArrayList<>();
        for (ProjectionRetrieval retrieval : model.retrievals()) {
            roots.add(adaptRetrieval(retrieval));
        }

        List<GraphqlObjectType> types = new ArrayList<>();
        List<GraphqlTableDescriptor> tables = new ArrayList<>();
        for (ProjectionType type : model.types()) {
            types.add(adaptType(type));
            tables.add(new GraphqlTableDescriptor(
                    type.tableName(),
                    type.schemaName(),
                    type.physicalTableName(),
                    type.primaryKeyColumnName()
            ));
        }

        return new GraphqlSchema(roots, types, tables);
    }

    private static GraphqlRootField adaptRetrieval(ProjectionRetrieval retrieval) {
        List<GraphqlRootArgumentDescriptor> arguments = new ArrayList<>();
        for (ProjectionRetrieval.RetrievalArgument argument : retrieval.arguments()) {
            arguments.add(adaptArgument(argument));
        }
        return new GraphqlRootField(
                retrieval.name(),
                retrieval.name(),
                retrieval.typeName(),
                retrieval.requiredIdArgumentName(),
                retrieval.limitArgumentName(),
                adaptShape(retrieval.operationShape()),
                adaptPagination(retrieval.paginationMode()),
                adaptCardinality(retrieval.cardinality()),
                retrieval.defaultLimit(),
                retrieval.maxLimit(),
                arguments,
                adaptCapabilities(retrieval.capabilities()),
                adaptCursorOrdering(retrieval.cursorOrdering()),
                adaptRootFilterPaths(retrieval.filterPaths()),
                adaptRootSortPaths(retrieval.sortPaths()),
                adaptRootContextFilters(retrieval.contextFilters())
        );
    }

    private static GraphqlRootArgumentDescriptor adaptArgument(ProjectionRetrieval.RetrievalArgument argument) {
        return switch (argument.kind()) {
            case INT_EQUALS -> GraphqlRootArgumentDescriptor.intEquals(argument.name(), argument.columnName());
        };
    }

    private static GraphqlObjectType adaptType(ProjectionType type) {
        List<GraphqlFieldDescriptor> fields = new ArrayList<>();
        for (ProjectionField field : type.fields()) {
            if (field.computedExpression() == null) {
                fields.add(GraphqlFieldDescriptor.scalarColumn(
                        field.name(),
                        field.columnName(),
                        field.graphqlType(),
                        field.nullable(),
                        field.policy(),
                        adaptFilterCapabilities(field.filterCapabilities()),
                        adaptSortCapabilities(field.sortCapabilities())
                ));
            } else {
                fields.add(GraphqlFieldDescriptor.computedScalar(
                        field.name(),
                        field.graphqlType(),
                        field.policy(),
                        adaptComputedExpression(field.computedExpression())
                ));
            }
        }
        for (ProjectionRelation relation : type.relations()) {
            fields.add(GraphqlFieldDescriptor.relation(
                    relation.name(),
                    relation.targetTypeName(),
                    relation.localColumnName(),
                    relation.targetColumnName(),
                    adaptCardinality(relation.cardinality()),
                    relation.nullable(),
                    adaptCapabilities(relation.capabilities()),
                    adaptRetrievals(relation.retrievals()),
                    adaptRelationArguments(relation.arguments()),
                    adaptSortPaths(relation.sortPaths())
            ));
        }
        return new GraphqlObjectType(type.name(), type.tableName(), fields);
    }

    private static GraphqlRootField.ResultCardinality adaptCardinality(ProjectionRetrieval.RetrievalCardinality cardinality) {
        return switch (cardinality) {
            case ONE -> GraphqlRootField.ResultCardinality.ONE;
            case MANY -> GraphqlRootField.ResultCardinality.MANY;
        };
    }

    private static GraphqlRootField.RootRetrievalShape adaptShape(ProjectionRetrieval.OperationShape operationShape) {
        return switch (operationShape) {
            case POINT_LOOKUP -> GraphqlRootField.RootRetrievalShape.POINT_LOOKUP;
            case LIST_QUERY -> GraphqlRootField.RootRetrievalShape.LIST_QUERY;
        };
    }

    private static GraphqlRootField.RootPaginationMode adaptPagination(
            ProjectionRetrieval.RetrievalPaginationMode paginationMode
    ) {
        return switch (paginationMode) {
            case NONE -> GraphqlRootField.RootPaginationMode.NONE;
            case RELAY_CONNECTION -> GraphqlRootField.RootPaginationMode.RELAY_CONNECTION;
        };
    }

    private static GraphqlRootField.RetrievalCapabilities adaptCapabilities(
            ProjectionRetrieval.ProjectionRetrievalCapabilities capabilities
    ) {
        return new GraphqlRootField.RetrievalCapabilities(
                capabilities.directRoot(),
                capabilities.supportsKeyArgument(),
                capabilities.supportsLimitArgument(),
                capabilities.supportsFilterArguments(),
                capabilities.supportsTotalCount()
        );
    }

    private static GraphqlRootField.RootCursorOrdering adaptCursorOrdering(
            ProjectionRetrieval.RetrievalCursorOrdering cursorOrdering
    ) {
        return new GraphqlRootField.RootCursorOrdering(
                cursorOrdering.name(),
                cursorOrdering.columnName(),
                cursorOrdering.cursorPath(),
                adaptCursorDirection(cursorOrdering.direction()),
                cursorOrdering.tieBreakerColumnName()
        );
    }

    private static GraphqlRootField.RootCursorDirection adaptCursorDirection(
            ProjectionRetrieval.RetrievalCursorDirection direction
    ) {
        return switch (direction) {
            case ASC -> GraphqlRootField.RootCursorDirection.ASC;
            case DESC -> GraphqlRootField.RootCursorDirection.DESC;
        };
    }

    private static List<GraphqlRootField.RootFieldSortPath> adaptRootSortPaths(List<ProjectionRetrieval.RetrievalSortPath> sortPaths) {
        List<GraphqlRootField.RootFieldSortPath> adapted = new ArrayList<>();
        for (ProjectionRetrieval.RetrievalSortPath sortPath : sortPaths) {
            adapted.add(new GraphqlRootField.RootFieldSortPath(
                    sortPath.name(),
                    sortPath.columnName(),
                    sortPath.sortPath(),
                    sortPath.sortHopCount(),
                    adaptCursorDirection(sortPath.direction()),
                    adaptNullOrdering(sortPath.nullOrdering()),
                    sortPath.tieBreakerColumnName()
            ));
        }
        return adapted;
    }

    private static List<GraphqlRootField.RootFieldFilterPath> adaptRootFilterPaths(
            List<ProjectionRetrieval.RetrievalFilterPath> filterPaths
    ) {
        List<GraphqlRootField.RootFieldFilterPath> adapted = new ArrayList<>();
        for (ProjectionRetrieval.RetrievalFilterPath filterPath : filterPaths) {
            adapted.add(new GraphqlRootField.RootFieldFilterPath(
                    filterPath.name(),
                    filterPath.columnName(),
                    filterPath.filterPath(),
                    filterPath.filterHopCount(),
                    filterPath.scalarType(),
                    adaptFilterCapabilities(filterPath.filterCapabilities()).operators()
            ));
        }
        return adapted;
    }

    private static List<GraphqlRootField.RootContextFilter> adaptRootContextFilters(
            List<ProjectionRetrieval.RetrievalContextFilter> contextFilters
    ) {
        List<GraphqlRootField.RootContextFilter> adapted = new ArrayList<>();
        for (ProjectionRetrieval.RetrievalContextFilter contextFilter : contextFilters) {
            adapted.add(new GraphqlRootField.RootContextFilter(
                    contextFilter.name(),
                    contextFilter.columnName(),
                    adaptContextFilterValueType(contextFilter.valueType()),
                    contextFilter.contextKey(),
                    contextFilter.failClosed(),
                    adaptContextFilterPhase(contextFilter.phase())
            ));
        }
        return adapted;
    }

    private static GraphqlRootField.RootContextFilterValueType adaptContextFilterValueType(
            ProjectionRetrieval.RetrievalContextFilterValueType valueType
    ) {
        return switch (valueType) {
            case BOOLEAN -> GraphqlRootField.RootContextFilterValueType.BOOLEAN;
            case ID -> GraphqlRootField.RootContextFilterValueType.ID;
            case STRING -> GraphqlRootField.RootContextFilterValueType.STRING;
        };
    }

    private static GraphqlRootField.RootContextFilterPhase adaptContextFilterPhase(
            ProjectionRetrieval.RetrievalContextFilterPhase phase
    ) {
        return switch (phase) {
            case BEFORE_CLIENT_FILTERS -> GraphqlRootField.RootContextFilterPhase.BEFORE_CLIENT_FILTERS;
        };
    }

    private static GraphqlRootField.RootNullOrdering adaptNullOrdering(ProjectionRetrieval.RetrievalNullOrdering nullOrdering) {
        return switch (nullOrdering) {
            case NULLS_FIRST -> GraphqlRootField.RootNullOrdering.NULLS_FIRST;
            case NULLS_LAST -> GraphqlRootField.RootNullOrdering.NULLS_LAST;
        };
    }

    private static GraphqlFieldDescriptor.ScalarFilterCapabilities adaptFilterCapabilities(
            ProjectionField.FilterCapabilities capabilities
    ) {
        List<GraphqlFieldDescriptor.ScalarFilterOperator> operators = new ArrayList<>();
        for (ProjectionField.FilterOperator operator : capabilities.operators()) {
            operators.add(scalarFilterOperator(operator));
        }
        return new GraphqlFieldDescriptor.ScalarFilterCapabilities(operators);
    }

    private static GraphqlFieldDescriptor.ScalarFilterOperator scalarFilterOperator(
            ProjectionField.FilterOperator operator
    ) {
        return switch (operator) {
            case EQ -> GraphqlFieldDescriptor.ScalarFilterOperator.EQ;
            case NEQ -> GraphqlFieldDescriptor.ScalarFilterOperator.NEQ;
            case IN -> GraphqlFieldDescriptor.ScalarFilterOperator.IN;
            case IS_NULL -> GraphqlFieldDescriptor.ScalarFilterOperator.IS_NULL;
            case LT -> GraphqlFieldDescriptor.ScalarFilterOperator.LT;
            case LTE -> GraphqlFieldDescriptor.ScalarFilterOperator.LTE;
            case GT -> GraphqlFieldDescriptor.ScalarFilterOperator.GT;
            case GTE -> GraphqlFieldDescriptor.ScalarFilterOperator.GTE;
            case CONTAINS -> GraphqlFieldDescriptor.ScalarFilterOperator.CONTAINS;
            case STARTS_WITH -> GraphqlFieldDescriptor.ScalarFilterOperator.STARTS_WITH;
            case ENDS_WITH -> GraphqlFieldDescriptor.ScalarFilterOperator.ENDS_WITH;
        };
    }

    private static GraphqlFieldDescriptor.FieldComputedExpression adaptComputedExpression(
            ProjectionField.ProjectionComputedExpression expression
    ) {
        return new GraphqlFieldDescriptor.FieldComputedExpression(
                expression.name(),
                expression.graphqlType(),
                adaptExpressionKind(expression.expressionKind()),
                expression.sqlTemplateOrFunction(),
                expression.selectable(),
                expression.filterable(),
                expression.sortable(),
                expression.nullable(),
                expression.deterministic(),
                expression.sensitive(),
                expression.requiredColumns(),
                adaptCostClass(expression.costClass())
        );
    }

    private static GraphqlFieldDescriptor.ComputedExpressionKind adaptExpressionKind(
            ProjectionField.ProjectionExpressionKind kind
    ) {
        return switch (kind) {
            case SQL_TEMPLATE -> GraphqlFieldDescriptor.ComputedExpressionKind.SQL_TEMPLATE;
            case GENERATED_SQL_HELPER -> GraphqlFieldDescriptor.ComputedExpressionKind.GENERATED_SQL_HELPER;
            case MATERIALIZED_COLUMN -> GraphqlFieldDescriptor.ComputedExpressionKind.MATERIALIZED_COLUMN;
            case JAVA_ONLY_EXPERIMENTAL -> GraphqlFieldDescriptor.ComputedExpressionKind.JAVA_ONLY_EXPERIMENTAL;
        };
    }

    private static GraphqlFieldDescriptor.ComputedCostClass adaptCostClass(ProjectionField.ProjectionCostClass costClass) {
        return switch (costClass) {
            case CONSTANT -> GraphqlFieldDescriptor.ComputedCostClass.CONSTANT;
            case ROW_LOCAL -> GraphqlFieldDescriptor.ComputedCostClass.ROW_LOCAL;
            case RELATION_DEPENDENT -> GraphqlFieldDescriptor.ComputedCostClass.RELATION_DEPENDENT;
        };
    }

    private static GraphqlFieldDescriptor.ScalarSortCapabilities adaptSortCapabilities(
            ProjectionField.SortCapabilities capabilities
    ) {
        return new GraphqlFieldDescriptor.ScalarSortCapabilities(
                capabilities.sortable(),
                capabilities.sortPath(),
                capabilities.hopCount(),
                adaptScalarSortDirection(capabilities.direction()),
                adaptScalarNullOrdering(capabilities.nullOrdering()),
                capabilities.tieBreakerColumnName()
        );
    }

    private static GraphqlFieldDescriptor.RelationSortDirection adaptScalarSortDirection(
            ProjectionField.ProjectionSortDirection direction
    ) {
        return switch (direction) {
            case ASC -> GraphqlFieldDescriptor.RelationSortDirection.ASC;
            case DESC -> GraphqlFieldDescriptor.RelationSortDirection.DESC;
        };
    }

    private static GraphqlFieldDescriptor.FieldNullOrdering adaptScalarNullOrdering(
            ProjectionField.ProjectionNullOrdering nullOrdering
    ) {
        return switch (nullOrdering) {
            case NULLS_FIRST -> GraphqlFieldDescriptor.FieldNullOrdering.NULLS_FIRST;
            case NULLS_LAST -> GraphqlFieldDescriptor.FieldNullOrdering.NULLS_LAST;
        };
    }

    private static GraphqlFieldDescriptor.RelationCardinality adaptCardinality(ProjectionRelation.ProjectionRelationCardinality cardinality) {
        return switch (cardinality) {
            case ONE -> GraphqlFieldDescriptor.RelationCardinality.ONE;
            case MANY -> GraphqlFieldDescriptor.RelationCardinality.MANY;
        };
    }

    private static List<GraphqlRelationArgumentDescriptor> adaptRelationArguments(
            List<ProjectionRelation.ProjectionRelationArgument> arguments
    ) {
        List<GraphqlRelationArgumentDescriptor> adapted = new ArrayList<>();
        for (ProjectionRelation.ProjectionRelationArgument argument : arguments) {
            adapted.add(adaptRelationArgument(argument));
        }
        return adapted;
    }

    private static GraphqlRelationArgumentDescriptor adaptRelationArgument(ProjectionRelation.ProjectionRelationArgument argument) {
        return switch (argument.kind()) {
            case INT_EQUALS -> GraphqlRelationArgumentDescriptor.intEquals(
                    argument.name(),
                    argument.columnName(),
                    argument.filterPath(),
                    argument.filterHopCount()
            );
            case RELAY_FIRST -> GraphqlRelationArgumentDescriptor.relayFirst();
            case RELAY_AFTER -> GraphqlRelationArgumentDescriptor.relayAfter();
            case RELAY_LAST -> GraphqlRelationArgumentDescriptor.relayLast();
            case RELAY_BEFORE -> GraphqlRelationArgumentDescriptor.relayBefore();
        };
    }

    private static GraphqlFieldDescriptor.RelationCapabilities adaptCapabilities(
            ProjectionRelation.ProjectionRelationCapabilities capabilities
    ) {
        return new GraphqlFieldDescriptor.RelationCapabilities(
                capabilities.selectable(),
                capabilities.batchable(),
                capabilities.supportsFiltering(),
                capabilities.supportsSorting(),
                adaptPagination(capabilities.paginationMode()),
                capabilities.selectionHopBudget(),
                capabilities.filterHopBudget(),
                capabilities.sortHopBudget(),
                capabilities.defaultPageSize(),
                capabilities.maxPageSize(),
                capabilities.supportsTotalCount()
        );
    }

    private static GraphqlFieldDescriptor.RelationRetrievals adaptRetrievals(
            ProjectionRelation.Retrievals retrievals
    ) {
        return new GraphqlFieldDescriptor.RelationRetrievals(
                retrievals.batchName(),
                retrievals.filteredBatchName(),
                retrievals.connectionPageName()
        );
    }

    private static List<GraphqlFieldDescriptor.RelationSortPath> adaptSortPaths(
            List<ProjectionRelation.ProjectionRelationSortPath> sortPaths
    ) {
        List<GraphqlFieldDescriptor.RelationSortPath> adapted = new ArrayList<>();
        for (ProjectionRelation.ProjectionRelationSortPath sortPath : sortPaths) {
            adapted.add(new GraphqlFieldDescriptor.RelationSortPath(
                    sortPath.name(),
                    sortPath.columnName(),
                    sortPath.sortPath(),
                    sortPath.sortHopCount(),
                    adaptSortDirection(sortPath.direction()),
                    sortPath.tieBreakerColumnName()
            ));
        }
        return adapted;
    }

    private static GraphqlFieldDescriptor.RelationSortDirection adaptSortDirection(
            ProjectionRelation.ProjectionRelationSortDirection direction
    ) {
        return switch (direction) {
            case ASC -> GraphqlFieldDescriptor.RelationSortDirection.ASC;
            case DESC -> GraphqlFieldDescriptor.RelationSortDirection.DESC;
        };
    }

    private static GraphqlFieldDescriptor.RelationPaginationMode adaptPagination(
            ProjectionRelation.ProjectionRelationPaginationMode paginationMode
    ) {
        return switch (paginationMode) {
            case NONE -> GraphqlFieldDescriptor.RelationPaginationMode.NONE;
            case RELAY_CONNECTION -> GraphqlFieldDescriptor.RelationPaginationMode.RELAY_CONNECTION;
        };
    }
}
