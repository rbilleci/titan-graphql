package io.titan.graphql;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GraphqlValidator {

    private GraphqlValidator() {
    }

    public static GraphqlSelection validate(GraphqlSchema schema, GraphqlAst.AstOperation operation, String actorRole) {
        return validate(schema, operation, GraphqlRequestContext.legacy(0L, actorRole));
    }

    public static GraphqlSelection validate(GraphqlSchema schema, GraphqlAst.AstOperation operation, GraphqlRequestContext context) {
        if (operation.type() == GraphqlAst.OperationType.MUTATION) {
            if (schema.mutations().isEmpty() == false) {
                throw GraphqlException.mutationExecutionNotImplemented();
            }
            throw GraphqlException.mutationSchemaNotConfigured();
        }
        if (operation.type() == GraphqlAst.OperationType.SUBSCRIPTION) {
            throw GraphqlException.unsupportedOperation("subscription");
        }
        operation = applyRuntimeDirectives(operation);
        Map<String, GraphqlAst.FragmentDefinition> fragments = validateFragments(schema, operation);
        List<GraphqlAst.Field> rootFields = mergeResponseKeys(
                expandSelections(schema, "Query", operation.selections(), fragments),
                "root selection"
        );
        if (rootFields.size() != 1) {
            throw new GraphqlException("exactly one root field is supported");
        }
        GraphqlAst.Field root = rootFields.getFirst();
        validateRootMetaField(root, context);
        GraphqlRootField rootField = schema.rootField(root.name());
        if (rootField == null) {
            throw new GraphqlException("unsupported root field '" + root.name() + "'");
        }
        if (rootField.retrievalCapabilities().directRoot() == false) {
            throw new GraphqlException("root field '" + root.name() + "' is not available for direct queries");
        }
        Map<String, Object> rootKeyValues = rootField.resultCardinality()
                == GraphqlRootField.ResultCardinality.ONE
                ? requirePointKeyArguments(root.arguments(), rootField, root.name())
                : Map.of();
        Object firstRootKey = rootField.pointKeyArguments().isEmpty()
                ? null : rootKeyValues.get(rootField.pointKeyArguments().getFirst().name());
        long rootId = firstRootKey instanceof Number number ? number.longValue() : -1L;
        if (root.arguments().containsKey(rootField.limitArgumentName())
                && rootField.retrievalCapabilities().supportsLimitArgument() == false) {
            throw new GraphqlException("root field '" + root.name() + "' does not support limit arguments");
        }
        if (rootField.rootPaginationMode() == GraphqlRootField.RootPaginationMode.RELAY_CONNECTION
                && root.arguments().containsKey("limit")) {
            throw new GraphqlException("root field '" + root.name() + "' does not support limit arguments");
        }
        RootArgumentPlan rootArgumentPlan = rootField.resultCardinality() == GraphqlRootField.ResultCardinality.MANY
                ? rootFilters(schema, root.arguments(), rootField, root.name(), context.actorRole())
                : RootArgumentPlan.empty();
        GraphqlSelection.RootPagination rootPagination = rootPagination(
                root.arguments(),
                rootField,
                root.name(),
                rootArgumentPlan.orderBy()
        );
        validateRootCursorOrdering(rootField, root.name());
        int rootLimit = rootField.resultCardinality() == GraphqlRootField.ResultCardinality.MANY
                ? rootLimit(root.arguments(), rootField, root.name(), rootPagination)
                : 1;
        if (root.selections().isEmpty()) {
            throw new GraphqlException("field '" + root.name() + "' requires a selection set");
        }

        GraphqlObjectType rootType = requireType(schema, rootField.typeName());
        RootSelection rootSelection = rootSelection(
                schema,
                rootType,
                root,
                rootField,
                context.actorRole(),
                fragments
        );
        GraphqlSelection.Builder builder = GraphqlSelection.builder(
                rootField.name(),
                root.responseKey(),
                rootField.retrievalName(),
                rootField.retrievalShape(),
                rootField.retrievalCapabilities(),
                rootField.cursorOrdering(),
                rootType.name(),
                rootId,
                rootLimit,
                rootPagination,
                rootSelection.connectionSelection(),
                rootField.resultCardinality(),
                rootArgumentPlan.filters(),
                rootField.contextFilters(),
                rootArgumentPlan.generatedFilters(),
                rootArgumentPlan.orderBy(),
                rootKeyValues
        );
        for (GraphqlSelection.FieldSelection field : rootSelection.nodeSelections()) {
            builder.add(field);
        }
        return builder.build();
    }

    private static void validateRootMetaField(GraphqlAst.Field root, GraphqlRequestContext context) {
        if (root.name().equals("__schema") || root.name().equals("__type")) {
            if (context.introspectionEnabled() == false) {
                throw new GraphqlException("introspection is disabled for root field '" + root.name() + "'");
            }
            throw new GraphqlException("introspection root field '" + root.name() + "' is not implemented yet");
        }
    }

    private static GraphqlAst.AstOperation applyRuntimeDirectives(GraphqlAst.AstOperation operation) {
        return new GraphqlAst.AstOperation(
                operation.type(),
                operation.name(),
                operation.variables(),
                applyRuntimeDirectives(operation.selections()),
                applyFragmentDirectives(operation.fragments())
        );
    }

    private static List<GraphqlAst.FragmentDefinition> applyFragmentDirectives(
            List<GraphqlAst.FragmentDefinition> fragments
    ) {
        List<GraphqlAst.FragmentDefinition> pruned = new ArrayList<>();
        for (GraphqlAst.FragmentDefinition fragment : fragments) {
            pruned.add(new GraphqlAst.FragmentDefinition(
                    fragment.name(),
                    fragment.typeCondition(),
                    applyRuntimeDirectives(fragment.selections())
            ));
        }
        return List.copyOf(pruned);
    }

    private static List<GraphqlAst.Selection> applyRuntimeDirectives(List<GraphqlAst.Selection> selections) {
        List<GraphqlAst.Selection> pruned = new ArrayList<>();
        for (GraphqlAst.Selection selection : selections) {
            if (selection instanceof GraphqlAst.Field field) {
                if (shouldInclude(field.directives())) {
                    pruned.add(new GraphqlAst.Field(
                            field.name(),
                            field.responseKey(),
                            field.arguments(),
                            List.of(),
                            applyRuntimeDirectives(field.selections())
                    ));
                }
            } else if (selection instanceof GraphqlAst.FragmentSpread fragmentSpread) {
                if (shouldInclude(fragmentSpread.directives())) {
                    pruned.add(new GraphqlAst.FragmentSpread(fragmentSpread.name(), List.of()));
                }
            } else if (selection instanceof GraphqlAst.InlineFragment inlineFragment) {
                if (shouldInclude(inlineFragment.directives())) {
                    pruned.add(new GraphqlAst.InlineFragment(
                            inlineFragment.typeCondition(),
                            List.of(),
                            applyRuntimeDirectives(inlineFragment.selections())
                    ));
                }
            }
        }
        return List.copyOf(pruned);
    }

    private static boolean shouldInclude(List<GraphqlAst.Directive> directives) {
        boolean include = true;
        for (GraphqlAst.Directive directive : directives) {
            if (directive.name().equals("include")) {
                include = include && directiveBooleanArgument(directive);
            } else if (directive.name().equals("skip")) {
                include = include && directiveBooleanArgument(directive) == false;
            } else {
                throw new GraphqlException("unsupported directive '@" + directive.name() + "'");
            }
        }
        return include;
    }

    private static boolean directiveBooleanArgument(GraphqlAst.Directive directive) {
        if (directive.arguments().containsKey("if") == false) {
            throw new GraphqlException("directive '@" + directive.name() + "' requires argument 'if'");
        }
        if (directive.arguments().size() != 1) {
            throw new GraphqlException("directive '@" + directive.name() + "' only supports argument 'if'");
        }
        GraphqlAst.Value value = directive.arguments().get("if");
        if (value instanceof GraphqlAst.BooleanValue booleanValue) {
            return booleanValue.value();
        }
        throw new GraphqlException("directive '@" + directive.name() + "' argument 'if' must be Boolean");
    }

    private static RootSelection rootSelection(
            GraphqlSchema schema,
            GraphqlObjectType rootType,
            GraphqlAst.Field root,
            GraphqlRootField rootField,
            String actorRole,
            Map<String, GraphqlAst.FragmentDefinition> fragments
    ) {
        if (rootField.rootPaginationMode() != GraphqlRootField.RootPaginationMode.RELAY_CONNECTION) {
            List<GraphqlSelection.FieldSelection> fields = new ArrayList<>();
            for (GraphqlAst.Field field : mergeResponseKeys(
                    expandSelections(schema, rootType.name(), root.selections(), fragments),
                    root.name()
            )) {
                fields.add(validateField(schema, rootType, field, actorRole, 0, Integer.MAX_VALUE, fragments));
            }
            return new RootSelection(fields, GraphqlSelection.RootConnectionSelection.none());
        }
        return relayConnectionSelection(schema, rootType, root, rootField, actorRole, fragments);
    }

    private static RootSelection relayConnectionSelection(
            GraphqlSchema schema,
            GraphqlObjectType rootType,
            GraphqlAst.Field root,
            GraphqlRootField rootField,
            String actorRole,
            Map<String, GraphqlAst.FragmentDefinition> fragments
    ) {
        boolean edgesSelected = false;
        boolean edgeCursorSelected = false;
        boolean edgeNodeSelected = false;
        boolean totalCountSelected = false;
        boolean pageInfoSelected = false;
        List<String> pageInfoFields = new ArrayList<>();
        List<GraphqlSelection.FieldSelection> nodeSelections = new ArrayList<>();
        for (GraphqlAst.Field field : mergeResponseKeys(
                expandSelections(schema, rootType.name(), root.selections(), fragments),
                root.name()
        )) {
            if (field.name().equals("edges")) {
                edgesSelected = true;
                if (!field.arguments().isEmpty()) {
                    throw new GraphqlException("field '" + root.name() + ".edges' does not accept arguments");
                }
                if (field.selections().isEmpty()) {
                    throw new GraphqlException("field 'edges' requires a selection set");
                }
                for (GraphqlAst.Field edgeField : expandSelections(
                        schema,
                        rootType.name() + "Edge",
                        field.selections(),
                        fragments
                )) {
                    if (edgeField.name().equals("cursor")) {
                        edgeCursorSelected = true;
                        requireNoArguments(edgeField, root.name() + ".edges.cursor");
                        requireScalar(edgeField);
                    } else if (edgeField.name().equals("node")) {
                        edgeNodeSelected = true;
                        requireNoArguments(edgeField, root.name() + ".edges.node");
                        if (edgeField.selections().isEmpty()) {
                            throw new GraphqlException("field 'node' requires a selection set");
                        }
                        for (GraphqlAst.Field nodeField : mergeResponseKeys(
                                expandSelections(schema, rootType.name(), edgeField.selections(), fragments),
                                root.name() + ".edges.node"
                        )) {
                            nodeSelections.add(validateField(
                                    schema,
                                    rootType,
                                    nodeField,
                                    actorRole,
                                    0,
                                    Integer.MAX_VALUE,
                                    fragments
                            ));
                        }
                    } else {
                        throw new GraphqlException("unsupported " + root.name() + ".edges field '" + edgeField.name() + "'");
                    }
                }
            } else if (field.name().equals("totalCount")) {
                if (rootField.retrievalCapabilities().supportsTotalCount() == false) {
                    throw new GraphqlException("unsupported " + root.name() + " field 'totalCount'");
                }
                totalCountSelected = true;
                requireNoArguments(field, root.name() + ".totalCount");
                requireScalar(field);
            } else if (field.name().equals("pageInfo")) {
                pageInfoSelected = true;
                requireNoArguments(field, root.name() + ".pageInfo");
                if (field.selections().isEmpty()) {
                    throw new GraphqlException("field 'pageInfo' requires a selection set");
                }
                for (GraphqlAst.Field pageInfoField : expandSelections(
                        schema,
                        "PageInfo",
                        field.selections(),
                        fragments
                )) {
                    requireNoArguments(pageInfoField, root.name() + ".pageInfo." + pageInfoField.name());
                    requireScalar(pageInfoField);
                    validatePageInfoField(root.name(), pageInfoField.name());
                    pageInfoFields.add(pageInfoField.name());
                }
            } else {
                throw new GraphqlException("root field '" + root.name()
                        + "' returns a Relay connection; select 'edges', 'totalCount', and/or 'pageInfo'");
            }
        }
        if (edgesSelected == false && totalCountSelected == false && pageInfoSelected == false) {
            throw new GraphqlException("root field '" + root.name() + "' requires a connection selection");
        }
        return new RootSelection(
                nodeSelections,
                new GraphqlSelection.RootConnectionSelection(
                        true,
                        edgesSelected,
                        edgeCursorSelected,
                        edgeNodeSelected,
                        totalCountSelected,
                        pageInfoSelected,
                        pageInfoFields
                )
        );
    }

    private static void requireNoArguments(GraphqlAst.Field field, String fieldName) {
        if (!field.arguments().isEmpty()) {
            throw new GraphqlException(fieldName + " does not accept arguments");
        }
    }

    private static void validatePageInfoField(String rootName, String fieldName) {
        if (fieldName.equals("hasNextPage")
                || fieldName.equals("hasPreviousPage")
                || fieldName.equals("startCursor")
                || fieldName.equals("endCursor")) {
            return;
        }
        throw new GraphqlException("unsupported " + rootName + ".pageInfo field '" + fieldName + "'");
    }

    private static List<GraphqlAst.Field> mergeResponseKeys(List<GraphqlAst.Field> fields, String selectionName) {
        Map<String, GraphqlAst.Field> merged = new LinkedHashMap<>();
        for (GraphqlAst.Field field : fields) {
            GraphqlAst.Field previous = merged.get(field.responseKey());
            if (previous == null) {
                merged.put(field.responseKey(), field);
                continue;
            }
            if (canMerge(previous, field) == false) {
                throw new GraphqlException("conflicting response key '" + field.responseKey()
                        + "' in " + selectionName);
            }
        }
        return List.copyOf(merged.values());
    }

    private static boolean canMerge(GraphqlAst.Field left, GraphqlAst.Field right) {
        if (left.name().equals(right.name()) == false
                || left.responseKey().equals(right.responseKey()) == false
                || left.arguments().equals(right.arguments()) == false) {
            return false;
        }
        return left.selections().equals(right.selections());
    }

    private static Map<String, GraphqlAst.FragmentDefinition> validateFragments(
            GraphqlSchema schema,
            GraphqlAst.AstOperation operation
    ) {
        Map<String, GraphqlAst.FragmentDefinition> fragments = new LinkedHashMap<>();
        for (GraphqlAst.FragmentDefinition fragment : operation.fragments()) {
            if (fragments.put(fragment.name(), fragment) != null) {
                throw new GraphqlException("duplicate fragment '" + fragment.name() + "'");
            }
            validateTypeCondition(schema, fragment.typeCondition());
        }
        validateFragmentCycles(fragments);
        Set<String> usedFragments = new HashSet<>();
        collectFragmentSpreads(operation.selections(), fragments, usedFragments);
        for (String fragmentName : fragments.keySet()) {
            if (usedFragments.contains(fragmentName) == false) {
                throw new GraphqlException("fragment '" + fragmentName + "' is never used");
            }
        }
        return fragments;
    }

    private static void validateFragmentCycles(Map<String, GraphqlAst.FragmentDefinition> fragments) {
        for (String fragmentName : fragments.keySet()) {
            validateFragmentCycle(fragmentName, fragments, new ArrayList<>());
        }
    }

    private static void validateFragmentCycle(
            String fragmentName,
            Map<String, GraphqlAst.FragmentDefinition> fragments,
            List<String> path
    ) {
        if (path.contains(fragmentName)) {
            throw new GraphqlException("fragment cycle detected at '" + fragmentName + "'");
        }
        GraphqlAst.FragmentDefinition fragment = fragments.get(fragmentName);
        if (fragment == null) {
            throw new GraphqlException("unknown fragment '" + fragmentName + "'");
        }
        List<String> nextPath = new ArrayList<>(path);
        nextPath.add(fragmentName);
        for (GraphqlAst.Selection selection : fragment.selections()) {
            collectCycle(selection, fragments, nextPath);
        }
    }

    private static void collectCycle(
            GraphqlAst.Selection selection,
            Map<String, GraphqlAst.FragmentDefinition> fragments,
            List<String> path
    ) {
        if (selection instanceof GraphqlAst.FragmentSpread fragmentSpread) {
            validateFragmentCycle(fragmentSpread.name(), fragments, path);
        } else if (selection instanceof GraphqlAst.Field field) {
            for (GraphqlAst.Selection nested : field.selections()) {
                collectCycle(nested, fragments, path);
            }
        } else if (selection instanceof GraphqlAst.InlineFragment inlineFragment) {
            for (GraphqlAst.Selection nested : inlineFragment.selections()) {
                collectCycle(nested, fragments, path);
            }
        }
    }

    private static void collectFragmentSpreads(
            List<GraphqlAst.Selection> selections,
            Map<String, GraphqlAst.FragmentDefinition> fragments,
            Set<String> used
    ) {
        for (GraphqlAst.Selection selection : selections) {
            if (selection instanceof GraphqlAst.FragmentSpread fragmentSpread) {
                if (used.add(fragmentSpread.name())) {
                    GraphqlAst.FragmentDefinition fragment = fragments.get(fragmentSpread.name());
                    if (fragment == null) {
                        throw new GraphqlException("unknown fragment '" + fragmentSpread.name() + "'");
                    }
                    collectFragmentSpreads(fragment.selections(), fragments, used);
                }
            } else if (selection instanceof GraphqlAst.Field field) {
                collectFragmentSpreads(field.selections(), fragments, used);
            } else if (selection instanceof GraphqlAst.InlineFragment inlineFragment) {
                collectFragmentSpreads(inlineFragment.selections(), fragments, used);
            }
        }
    }

    private static List<GraphqlAst.Field> expandSelections(
            GraphqlSchema schema,
            String parentTypeName,
            List<GraphqlAst.Selection> selections,
            Map<String, GraphqlAst.FragmentDefinition> fragments
    ) {
        List<GraphqlAst.Field> fields = new ArrayList<>();
        for (GraphqlAst.Selection selection : selections) {
            if (selection instanceof GraphqlAst.Field field) {
                fields.add(field);
            } else if (selection instanceof GraphqlAst.FragmentSpread fragmentSpread) {
                GraphqlAst.FragmentDefinition fragment = fragments.get(fragmentSpread.name());
                if (fragment == null) {
                    throw new GraphqlException("unknown fragment '" + fragmentSpread.name() + "'");
                }
                validateTypeCondition(schema, fragment.typeCondition());
                if (fragment.typeCondition().equals(parentTypeName)) {
                    fields.addAll(expandSelections(schema, parentTypeName, fragment.selections(), fragments));
                }
            } else if (selection instanceof GraphqlAst.InlineFragment inlineFragment) {
                validateTypeCondition(schema, inlineFragment.typeCondition());
                if (inlineFragment.typeCondition().equals(parentTypeName)) {
                    fields.addAll(expandSelections(schema, parentTypeName, inlineFragment.selections(), fragments));
                }
            }
        }
        return List.copyOf(fields);
    }

    private static void validateTypeCondition(GraphqlSchema schema, String typeCondition) {
        if (typeCondition.equals("Query")
                || typeCondition.equals("PageInfo")
                || schema.type(typeCondition) != null
                || isGeneratedEdgeType(schema, typeCondition)) {
            return;
        }
        throw new GraphqlException("fragment type condition '" + typeCondition + "' is not defined");
    }

    private static boolean isGeneratedEdgeType(GraphqlSchema schema, String typeCondition) {
        if (typeCondition.endsWith("Edge") == false || typeCondition.length() <= "Edge".length()) {
            return false;
        }
        return schema.type(typeCondition.substring(0, typeCondition.length() - "Edge".length())) != null;
    }

    private static GraphqlSelection.FieldSelection validateField(
            GraphqlSchema schema,
            GraphqlObjectType parentType,
            GraphqlAst.Field field,
            String actorRole,
            int relationHopCount,
            int activeSelectionHopBudget,
            Map<String, GraphqlAst.FragmentDefinition> fragments
    ) {
        if (field.name().equals("__typename")) {
            requireNoArguments(field, parentType.name() + ".__typename");
            requireScalar(field);
            return new GraphqlSelection.FieldSelection(
                    "__typename",
                    field.responseKey(),
                    GraphqlFieldDescriptor.FieldKind.SCALAR,
                    parentType.name(),
                    List.of()
            );
        }
        GraphqlFieldDescriptor descriptor = parentType.field(field.name());
        if (descriptor == null) {
            throw new GraphqlException("unsupported " + parentType.name() + " field '" + field.name() + "'");
        }
        if (!descriptor.canRead(actorRole)) {
            throw GraphqlException.authorization("field '" + parentType.name() + "." + field.name()
                    + "' is not authorized for actor role '" + actorRole + "'");
        }
        if (descriptor.kind() == GraphqlFieldDescriptor.FieldKind.SCALAR) {
            if (descriptor.computedExpression().present()
                    && descriptor.computedExpression().selectable() == false) {
                throw new GraphqlException(parentType.name() + "." + field.name() + " is not selectable");
            }
            if (!field.arguments().isEmpty()) {
                throw new GraphqlException(parentType.name() + "." + field.name() + " does not accept arguments");
            }
            requireScalar(field);
            return new GraphqlSelection.FieldSelection(
                    descriptor.name(),
                    field.responseKey(),
                    descriptor.kind(),
                    parentType.name(),
                    List.of()
            );
        }

        GraphqlFieldDescriptor.RelationCapabilities capabilities = descriptor.relationCapabilities();
        if (capabilities.selectable() == false) {
            throw new GraphqlException(parentType.name() + "." + field.name() + " is not selectable");
        }
        validateDeclaredSortPaths(parentType, descriptor);
        List<GraphqlSelection.RelationArgument> relationArguments = relationArguments(
                field.arguments(),
                parentType,
                descriptor
        );
        GraphqlFieldDescriptor.RelationRetrievalShape relationRetrievalShape = relationRetrievalShape(
                descriptor,
                relationArguments
        );
        int nextRelationHopCount = relationHopCount + 1;
        int nextSelectionHopBudget = Math.min(activeSelectionHopBudget, capabilities.selectionHopBudget());
        if (nextRelationHopCount > nextSelectionHopBudget) {
            throw new GraphqlException(parentType.name() + "." + field.name()
                    + " exceeds selection hop budget of " + nextSelectionHopBudget);
        }
        GraphqlObjectType targetType = requireType(schema, descriptor.targetTypeName());
        RelationSelection relationSelection = relationSelection(
                schema,
                parentType,
                targetType,
                field,
                descriptor,
                relationArguments,
                actorRole,
                nextRelationHopCount,
                nextSelectionHopBudget,
                fragments
        );
        return new GraphqlSelection.FieldSelection(
                descriptor.name(),
                field.responseKey(),
                descriptor.kind(),
                targetType.name(),
                descriptor.relationRetrievalName(relationRetrievalShape),
                relationRetrievalShape,
                relationArguments,
                relationSelection.connectionSelection(),
                relationSelection.nodeSelections()
        );
    }

    private static RelationSelection relationSelection(
            GraphqlSchema schema,
            GraphqlObjectType parentType,
            GraphqlObjectType targetType,
            GraphqlAst.Field field,
            GraphqlFieldDescriptor descriptor,
            List<GraphqlSelection.RelationArgument> relationArguments,
            String actorRole,
            int relationHopCount,
            int selectionHopBudget,
            Map<String, GraphqlAst.FragmentDefinition> fragments
    ) {
        if (field.selections().isEmpty()) {
            throw new GraphqlException("field '" + field.name() + "' requires a selection set");
        }
        if (descriptor.relationCapabilities().paginationMode()
                != GraphqlFieldDescriptor.RelationPaginationMode.RELAY_CONNECTION) {
            List<GraphqlSelection.FieldSelection> nestedSelections = new ArrayList<>();
            for (GraphqlAst.Field nested : mergeResponseKeys(
                    expandSelections(schema, targetType.name(), field.selections(), fragments),
                    field.name()
            )) {
                nestedSelections.add(validateField(
                        schema,
                        targetType,
                        nested,
                        actorRole,
                        relationHopCount,
                        selectionHopBudget,
                        fragments
                ));
            }
            return new RelationSelection(nestedSelections, GraphqlSelection.RelationConnectionSelection.none());
        }
        return relationConnectionSelection(
                schema,
                parentType,
                targetType,
                field,
                descriptor,
                relationArguments,
                actorRole,
                relationHopCount,
                selectionHopBudget,
                fragments
        );
    }

    private static RelationSelection relationConnectionSelection(
            GraphqlSchema schema,
            GraphqlObjectType parentType,
            GraphqlObjectType targetType,
            GraphqlAst.Field relation,
            GraphqlFieldDescriptor descriptor,
            List<GraphqlSelection.RelationArgument> relationArguments,
            String actorRole,
            int relationHopCount,
            int selectionHopBudget,
            Map<String, GraphqlAst.FragmentDefinition> fragments
    ) {
        boolean edgesSelected = false;
        boolean edgeCursorSelected = false;
        boolean edgeNodeSelected = false;
        boolean pageInfoSelected = false;
        boolean totalCountSelected = false;
        List<String> pageInfoFields = new ArrayList<>();
        List<GraphqlSelection.FieldSelection> nodeSelections = new ArrayList<>();
        for (GraphqlAst.Field field : mergeResponseKeys(
                expandSelections(schema, targetType.name(), relation.selections(), fragments),
                relation.name()
        )) {
            if (field.name().equals("edges")) {
                edgesSelected = true;
                if (!field.arguments().isEmpty()) {
                    throw new GraphqlException("field '" + relation.name() + ".edges' does not accept arguments");
                }
                if (field.selections().isEmpty()) {
                    throw new GraphqlException("field 'edges' requires a selection set");
                }
                for (GraphqlAst.Field edgeField : expandSelections(
                        schema,
                        targetType.name() + "Edge",
                        field.selections(),
                        fragments
                )) {
                    if (edgeField.name().equals("cursor")) {
                        edgeCursorSelected = true;
                        requireNoArguments(edgeField, relation.name() + ".edges.cursor");
                        requireScalar(edgeField);
                    } else if (edgeField.name().equals("node")) {
                        edgeNodeSelected = true;
                        requireNoArguments(edgeField, relation.name() + ".edges.node");
                        if (edgeField.selections().isEmpty()) {
                            throw new GraphqlException("field 'node' requires a selection set");
                        }
                        for (GraphqlAst.Field nodeField : mergeResponseKeys(
                                expandSelections(schema, targetType.name(), edgeField.selections(), fragments),
                                relation.name() + ".edges.node"
                        )) {
                            nodeSelections.add(validateField(
                                    schema,
                                    targetType,
                                    nodeField,
                                    actorRole,
                                    relationHopCount,
                                    selectionHopBudget,
                                    fragments
                            ));
                        }
                    } else {
                        throw new GraphqlException("unsupported " + relation.name()
                                + ".edges field '" + edgeField.name() + "'");
                    }
                }
            } else if (field.name().equals("pageInfo")) {
                pageInfoSelected = true;
                requireNoArguments(field, relation.name() + ".pageInfo");
                if (field.selections().isEmpty()) {
                    throw new GraphqlException("field 'pageInfo' requires a selection set");
                }
                for (GraphqlAst.Field pageInfoField : expandSelections(
                        schema,
                        "PageInfo",
                        field.selections(),
                        fragments
                )) {
                    requireNoArguments(pageInfoField, relation.name() + ".pageInfo." + pageInfoField.name());
                    requireScalar(pageInfoField);
                    validatePageInfoField(relation.name(), pageInfoField.name());
                    pageInfoFields.add(pageInfoField.name());
                }
            } else if (field.name().equals("totalCount")) {
                if (descriptor.relationCapabilities().supportsTotalCount() == false) {
                    throw new GraphqlException("unsupported " + relation.name() + " field 'totalCount'");
                }
                totalCountSelected = true;
                requireNoArguments(field, relation.name() + ".totalCount");
                requireScalar(field);
            } else {
                throw new GraphqlException("relation field '" + relation.name()
                        + "' returns a Relay connection; select 'edges', 'totalCount', and/or 'pageInfo'");
            }
        }
        if (edgesSelected == false && totalCountSelected == false && pageInfoSelected == false) {
            throw new GraphqlException("relation field '" + relation.name() + "' requires a connection selection");
        }
        validateRelationCursorOrdering(parentType, descriptor);
        return new RelationSelection(
                nodeSelections,
                new GraphqlSelection.RelationConnectionSelection(
                        true,
                        edgesSelected,
                        edgeCursorSelected,
                        edgeNodeSelected,
                        pageInfoSelected,
                        totalCountSelected,
                        relationPageSize(descriptor, relationArguments),
                        pageInfoFields
                )
        );
    }

    private static GraphqlObjectType requireType(GraphqlSchema schema, String typeName) {
        GraphqlObjectType type = schema.type(typeName);
        if (type == null) {
            throw new GraphqlException("schema type '" + typeName + "' is not registered");
        }
        return type;
    }

    private static void requireScalar(GraphqlAst.Field field) {
        if (!field.selections().isEmpty()) {
            throw new GraphqlException("scalar field '" + field.name() + "' cannot have a selection set");
        }
    }

    private static Map<String, Object> requirePointKeyArguments(
            Map<String, GraphqlAst.Value> arguments,
            GraphqlRootField rootField,
            String fieldName
    ) {
        if (rootField.retrievalCapabilities().supportsKeyArgument() == false) {
            throw new GraphqlException("root field '" + fieldName + "' does not support key arguments");
        }
        List<GraphqlRootField.PointKeyArgument> keyArguments = rootField.pointKeyArguments();
        if (keyArguments.isEmpty()) {
            throw new GraphqlException("root field '" + fieldName + "' has no point-key metadata");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (GraphqlRootField.PointKeyArgument key : keyArguments) {
            GraphqlAst.Value value = arguments.get(key.name());
            if (value == null) {
                throw new GraphqlException("required argument '" + key.name() + "' is missing");
            }
            values.put(key.name(), pointKeyValue(key, value));
        }
        if (arguments.size() != keyArguments.size()) {
            throw new GraphqlException("unsupported argument on " + fieldName
                    + "; point key requires exactly "
                    + keyArguments.stream().map(GraphqlRootField.PointKeyArgument::name).toList());
        }
        return Map.copyOf(values);
    }

    private static Object pointKeyValue(
            GraphqlRootField.PointKeyArgument key,
            GraphqlAst.Value value
    ) {
        String type = key.graphqlType().replace("!", "").trim();
        if (type.equals("Int") || type.equals("Long")) {
            if (value instanceof GraphqlAst.IntValue intValue) return intValue.value();
            throw new GraphqlException("argument '" + key.name() + "' must be an integer");
        }
        if (type.equals("ID") && value instanceof GraphqlAst.IntValue intValue) {
            return Long.toString(intValue.value());
        }
        if ((type.equals("String") || type.equals("ID") || type.equals("UUID"))
                && value instanceof GraphqlAst.StringValue stringValue) {
            if (type.equals("UUID")) {
                if (!stringValue.value().matches(
                        "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                    throw new GraphqlException("argument '" + key.name() + "' must be a UUID");
                }
                try {
                    return java.util.UUID.fromString(stringValue.value());
                } catch (IllegalArgumentException exception) {
                    throw new GraphqlException("argument '" + key.name() + "' must be a UUID");
                }
            }
            return stringValue.value();
        }
        throw new GraphqlException("argument '" + key.name() + "' must be a " + type);
    }

    private static int optionalLimitArgument(
            Map<String, GraphqlAst.Value> arguments,
            String name,
            String fieldName,
            int defaultLimit,
            int maxLimit
    ) {
        if (arguments.isEmpty()) {
            return defaultLimit;
        }
        GraphqlAst.Value value = arguments.get(name);
        if (value == null) {
            return defaultLimit;
        }
        if (value instanceof GraphqlAst.IntValue intValue) {
            long limit = intValue.value();
            if (limit < 0L || limit > maxLimit) {
                throw new GraphqlException("argument '" + name + "' must be between 0 and " + maxLimit);
            }
            return (int) limit;
        }
        throw new GraphqlException("argument '" + name + "' must be an integer");
    }

    private static int rootLimit(
            Map<String, GraphqlAst.Value> arguments,
            GraphqlRootField rootField,
            String fieldName,
            GraphqlSelection.RootPagination rootPagination
    ) {
        if (rootField.rootPaginationMode() == GraphqlRootField.RootPaginationMode.RELAY_CONNECTION) {
            if (rootPagination.first() != null) {
                return rootPagination.first();
            }
            if (rootPagination.last() != null) {
                return rootPagination.last();
            }
            return rootField.defaultLimit();
        }
        if (hasRootRelayPaginationArgument(arguments)) {
            throw new GraphqlException("root field '" + fieldName + "' does not support Relay pagination arguments");
        }
        return optionalLimitArgument(
                arguments,
                rootField.limitArgumentName(),
                fieldName,
                rootField.defaultLimit(),
                rootField.maxLimit()
        );
    }

    private static GraphqlSelection.RootPagination rootPagination(
            Map<String, GraphqlAst.Value> arguments,
            GraphqlRootField rootField,
            String fieldName,
            List<GraphqlSelection.RootOrder> orderBy
    ) {
        if (rootField.rootPaginationMode() != GraphqlRootField.RootPaginationMode.RELAY_CONNECTION) {
            if (hasRootRelayPaginationArgument(arguments)) {
                throw new GraphqlException("root field '" + fieldName + "' does not support Relay pagination arguments");
            }
            return GraphqlSelection.RootPagination.none();
        }
        Integer first = optionalRelayIntArgument(arguments, "first", rootField.maxLimit());
        Integer last = optionalRelayIntArgument(arguments, "last", rootField.maxLimit());
        if (first != null && last != null) {
            throw new GraphqlException("root field '" + fieldName + "' cannot combine 'first' and 'last'");
        }
        if ((arguments.containsKey("after") || arguments.containsKey("before")) && orderBy.size() > 1) {
            throw new GraphqlException("root field '" + fieldName
                    + "' supports Relay cursors with at most one generated order path");
        }
        RootCursor after = optionalRelayCursorArgument(arguments, "after", rootField, orderBy);
        RootCursor before = optionalRelayCursorArgument(arguments, "before", rootField, orderBy);
        return new GraphqlSelection.RootPagination(
                first,
                after.rawCursor(),
                after.payload(),
                last,
                before.rawCursor(),
                before.payload()
        );
    }

    private static void validateRootCursorOrdering(
            GraphqlRootField rootField,
            String fieldName
    ) {
        if (rootField.rootPaginationMode() == GraphqlRootField.RootPaginationMode.RELAY_CONNECTION
                && rootField.cursorOrdering().declared() == false) {
            throw new GraphqlException("root field '" + fieldName
                    + "' requires cursor ordering metadata for Relay pagination");
        }
    }

    private static Integer optionalRelayIntArgument(
            Map<String, GraphqlAst.Value> arguments,
            String name,
            int maxLimit
    ) {
        GraphqlAst.Value value = arguments.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof GraphqlAst.IntValue intValue) {
            long pageSize = intValue.value();
            if (pageSize < 0L || pageSize > maxLimit) {
                throw new GraphqlException("argument '" + name + "' must be between 0 and " + maxLimit);
            }
            return (int) pageSize;
        }
        throw new GraphqlException("argument '" + name + "' must be an integer");
    }

    private static RootCursor optionalRelayCursorArgument(
            Map<String, GraphqlAst.Value> arguments,
            String name,
            GraphqlRootField rootField,
            List<GraphqlSelection.RootOrder> orderBy
    ) {
        GraphqlAst.Value value = arguments.get(name);
        if (value == null) {
            return RootCursor.none();
        }
        if (value instanceof GraphqlAst.StringValue stringValue) {
            if (orderBy.isEmpty() == false) {
                return new RootCursor(
                        stringValue.value(),
                        GraphqlCursorCodec.decodeForOrdering(stringValue.value(), orderBy.getFirst())
                );
            }
            return new RootCursor(
                    stringValue.value(),
                    GraphqlCursorCodec.decodeForOrdering(stringValue.value(), rootField.cursorOrdering())
            );
        }
        throw new GraphqlException("argument '" + name + "' must be a string cursor");
    }

    private record RootArgumentPlan(
            List<GraphqlSelection.RootFilter> filters,
            List<GraphqlSelection.GeneratedRootFilter> generatedFilters,
            List<GraphqlSelection.RootOrder> orderBy
    ) {
        public RootArgumentPlan {
            filters = List.copyOf(filters);
            generatedFilters = List.copyOf(generatedFilters);
            orderBy = List.copyOf(orderBy);
        }

        public static RootArgumentPlan empty() {
            return new RootArgumentPlan(List.of(), List.of(), List.of());
        }
    }

    private static RootArgumentPlan rootFilters(
            GraphqlSchema schema,
            Map<String, GraphqlAst.Value> arguments,
            GraphqlRootField rootField,
            String fieldName,
            String actorRole
    ) {
        List<GraphqlSelection.RootFilter> filters = new ArrayList<>();
        List<GraphqlSelection.GeneratedRootFilter> generatedFilters = new ArrayList<>();
        List<GraphqlSelection.RootOrder> orderBy = new ArrayList<>();
        GraphqlRootField.RetrievalCapabilities capabilities = rootField.retrievalCapabilities();
        for (Map.Entry<String, GraphqlAst.Value> argument : arguments.entrySet()) {
            if (argument.getKey().equals(rootField.limitArgumentName())
                    || isRootRelayPaginationArgument(argument.getKey())) {
                continue;
            }
            if (argument.getKey().equals("filter")) {
                generatedFilters.add(validateGeneratedRootFilter(
                        schema, rootField, fieldName, argument.getValue(), actorRole));
                continue;
            }
            if (argument.getKey().equals("orderBy")) {
                orderBy.addAll(validateGeneratedRootOrderBy(rootField, fieldName, argument.getValue()));
                continue;
            }
            if (capabilities.supportsFilterArguments() == false && rootField.filterArguments().isEmpty() == false) {
                throw new GraphqlException("root field '" + fieldName + "' does not support filter arguments");
            }
            GraphqlRootArgumentDescriptor descriptor = rootField.filterArgument(argument.getKey());
            if (descriptor == null && isStructuredInput(argument.getValue())) {
                throw new GraphqlException("root field '" + fieldName
                        + "' does not support structured argument '" + argument.getKey() + "' yet");
            }
            if (descriptor == null) {
                throw unsupportedManyArgument(fieldName, rootField);
            }
            if (descriptor.kind() == GraphqlRootArgumentDescriptor.RootArgumentKind.INT_EQUALS
                    && argument.getValue() instanceof GraphqlAst.IntValue intValue) {
                filters.add(new GraphqlSelection.RootFilter(
                        descriptor.name(),
                        descriptor.columnName(),
                        intValue.value()
                ));
            } else {
                throw new GraphqlException("argument '" + descriptor.name() + "' must be an integer");
            }
        }
        return new RootArgumentPlan(filters, generatedFilters, orderBy);
    }

    private static GraphqlSelection.GeneratedRootFilter validateGeneratedRootFilter(
            GraphqlSchema schema,
            GraphqlRootField rootField,
            String fieldName,
            GraphqlAst.Value value,
            String actorRole
    ) {
        if (rootField.retrievalCapabilities().supportsFilterArguments() == false) {
            throw new GraphqlException("root field '" + fieldName + "' does not support generated filter arguments");
        }
        GraphqlObjectType type = requireType(schema, rootField.typeName());
        return validateObjectFilter(
                schema, rootField, type, value, rootField.typeName() + "Filter", actorRole);
    }

    private static GraphqlSelection.GeneratedRootFilter validateObjectFilter(
            GraphqlSchema schema,
            GraphqlRootField rootField,
            GraphqlObjectType type,
            GraphqlAst.Value value,
            String filterTypeName,
            String actorRole
    ) {
        if (!(value instanceof GraphqlAst.InputObjectValue objectValue)) {
            throw new GraphqlException("argument 'filter' must be an input object");
        }
        List<GraphqlSelection.GeneratedRootFilter> children = new ArrayList<>();
        for (Map.Entry<String, GraphqlAst.Value> field : objectValue.fields().entrySet()) {
            if (field.getKey().equals("and") || field.getKey().equals("or")) {
                children.add(validateFilterList(
                        schema, rootField, type, field.getValue(), field.getKey(), actorRole));
                continue;
            }
            if (field.getKey().equals("not")) {
                children.add(new GraphqlSelection.GeneratedRootFilter(
                        GraphqlSelection.GeneratedRootFilterKind.NOT,
                        "",
                        "",
                        "",
                        0,
                        "",
                        null,
                        List.of(),
                        List.of(validateObjectFilter(
                                schema, rootField, type, field.getValue(), filterTypeName, actorRole))
                ));
                continue;
            }
            GraphqlFieldDescriptor scalar = type.field(field.getKey());
            if (scalar != null && scalar.kind() == GraphqlFieldDescriptor.FieldKind.SCALAR
                    && scalar.scalarFilterCapabilities().operators().isEmpty() == false) {
                requireFilterAuthorization(type.name(), scalar, actorRole);
                children.add(validateScalarFilter(
                        field.getKey(),
                        field.getValue(),
                        scalar.columnName(),
                        scalar.name(),
                        0,
                        scalar.graphqlType(),
                        scalar.scalarFilterCapabilities().operators()
                ));
                continue;
            }
            GraphqlRootArgumentDescriptor rootArgument = rootField.filterArgument(field.getKey());
            if (rootArgument != null) {
                children.add(validateScalarFilter(
                        field.getKey(),
                        field.getValue(),
                        rootArgument.columnName(),
                        rootArgument.name(),
                        0,
                        scalarType(rootArgument.name(), rootArgument.columnName()),
                        List.of(
                                GraphqlFieldDescriptor.ScalarFilterOperator.EQ,
                                GraphqlFieldDescriptor.ScalarFilterOperator.NEQ,
                                GraphqlFieldDescriptor.ScalarFilterOperator.IN,
                                GraphqlFieldDescriptor.ScalarFilterOperator.IS_NULL,
                                GraphqlFieldDescriptor.ScalarFilterOperator.LT,
                                GraphqlFieldDescriptor.ScalarFilterOperator.LTE,
                                GraphqlFieldDescriptor.ScalarFilterOperator.GT,
                                GraphqlFieldDescriptor.ScalarFilterOperator.GTE
                        )
                ));
                continue;
            }
            GraphqlRootField.RootFieldFilterPath filterPath = rootField.filterPath(field.getKey());
            if (filterPath != null) {
                requireFilterPathAuthorization(schema, type, filterPath, actorRole);
                children.add(validateScalarFilter(
                        field.getKey(),
                        field.getValue(),
                        filterPath.columnName(),
                        filterPath.filterPath(),
                        filterPath.filterHopCount(),
                        filterPath.scalarType(),
                        filterPath.operators()
                ));
                continue;
            }
            throw new GraphqlException("unknown field '" + field.getKey() + "' on " + filterTypeName);
        }
        return new GraphqlSelection.GeneratedRootFilter(
                GraphqlSelection.GeneratedRootFilterKind.AND,
                "",
                "",
                "",
                0,
                "",
                null,
                List.of(),
                children
        );
    }

    private static GraphqlSelection.GeneratedRootFilter validateFilterList(
            GraphqlSchema schema,
            GraphqlRootField rootField,
            GraphqlObjectType type,
            GraphqlAst.Value value,
            String fieldName,
            String actorRole
    ) {
        if (!(value instanceof GraphqlAst.InputListValue listValue)) {
            throw new GraphqlException("filter field '" + fieldName + "' must be a list");
        }
        List<GraphqlSelection.GeneratedRootFilter> children = new ArrayList<>();
        for (GraphqlAst.Value item : listValue.values()) {
            children.add(validateObjectFilter(
                    schema, rootField, type, item, rootField.typeName() + "Filter", actorRole));
        }
        return new GraphqlSelection.GeneratedRootFilter(
                fieldName.equals("and")
                        ? GraphqlSelection.GeneratedRootFilterKind.AND
                        : GraphqlSelection.GeneratedRootFilterKind.OR,
                "",
                "",
                "",
                0,
                "",
                null,
                List.of(),
                children
        );
    }

    private static void requireFilterPathAuthorization(
            GraphqlSchema schema,
            GraphqlObjectType rootType,
            GraphqlRootField.RootFieldFilterPath filterPath,
            String actorRole
    ) {
        String[] segments = filterPath.filterPath().split("\\.", -1);
        GraphqlObjectType current = rootType;
        for (int index = 0; index < segments.length; index++) {
            GraphqlFieldDescriptor descriptor = current.field(segments[index]);
            if (descriptor == null) {
                throw new GraphqlException("filter path '" + filterPath.name()
                        + "' references unknown field '" + current.name() + "." + segments[index] + "'");
            }
            requireFilterAuthorization(current.name(), descriptor, actorRole);
            if (index < segments.length - 1) {
                if (descriptor.kind() != GraphqlFieldDescriptor.FieldKind.RELATION) {
                    throw new GraphqlException("filter path '" + filterPath.name()
                            + "' crosses non-relation field '" + current.name() + "." + descriptor.name() + "'");
                }
                current = requireType(schema, descriptor.targetTypeName());
            }
        }
    }

    private static void requireFilterAuthorization(
            String typeName,
            GraphqlFieldDescriptor descriptor,
            String actorRole
    ) {
        if (!descriptor.canRead(actorRole)) {
            throw GraphqlException.authorization("filter field '" + typeName + "." + descriptor.name()
                    + "' is not authorized for actor role '" + actorRole + "'");
        }
    }

    private static GraphqlSelection.GeneratedRootFilter validateScalarFilter(
            String fieldName,
            GraphqlAst.Value value,
            String columnName,
            String filterPath,
            int filterHopCount,
            String scalarType,
            List<GraphqlFieldDescriptor.ScalarFilterOperator> supported
    ) {
        if (!(value instanceof GraphqlAst.InputObjectValue objectValue)) {
            throw new GraphqlException("filter field '" + fieldName + "' must be an input object");
        }
        List<GraphqlSelection.GeneratedRootFilter> operatorFilters = new ArrayList<>();
        for (Map.Entry<String, GraphqlAst.Value> operator : objectValue.fields().entrySet()) {
            GraphqlFieldDescriptor.ScalarFilterOperator kind = scalarFilterOperator(operator.getKey());
            if (kind == null || supported.contains(kind) == false) {
                throw new GraphqlException("unsupported filter operator '" + operator.getKey()
                        + "' on field '" + fieldName + "'");
            }
            operatorFilters.add(new GraphqlSelection.GeneratedRootFilter(
                    GraphqlSelection.GeneratedRootFilterKind.SCALAR,
                    fieldName,
                    columnName,
                    filterPath,
                    filterHopCount,
                    scalarType,
                    generatedFilterOperator(kind),
                    validateScalarFilterValue(fieldName, operator.getKey(), operator.getValue(), scalarType),
                    List.of()
            ));
        }
        if (operatorFilters.isEmpty()) {
            throw new GraphqlException("filter field '" + fieldName + "' must include an operator");
        }
        if (operatorFilters.size() == 1) {
            return operatorFilters.getFirst();
        }
        return new GraphqlSelection.GeneratedRootFilter(
                GraphqlSelection.GeneratedRootFilterKind.AND,
                "",
                "",
                "",
                0,
                "",
                null,
                List.of(),
                operatorFilters
        );
    }

    private static GraphqlSelection.GeneratedRootFilterOperator generatedFilterOperator(
            GraphqlFieldDescriptor.ScalarFilterOperator operator
    ) {
        return switch (operator) {
            case EQ -> GraphqlSelection.GeneratedRootFilterOperator.EQ;
            case NEQ -> GraphqlSelection.GeneratedRootFilterOperator.NEQ;
            case IN -> GraphqlSelection.GeneratedRootFilterOperator.IN;
            case IS_NULL -> GraphqlSelection.GeneratedRootFilterOperator.IS_NULL;
            case LT -> GraphqlSelection.GeneratedRootFilterOperator.LT;
            case LTE -> GraphqlSelection.GeneratedRootFilterOperator.LTE;
            case GT -> GraphqlSelection.GeneratedRootFilterOperator.GT;
            case GTE -> GraphqlSelection.GeneratedRootFilterOperator.GTE;
            case CONTAINS -> GraphqlSelection.GeneratedRootFilterOperator.CONTAINS;
            case STARTS_WITH -> GraphqlSelection.GeneratedRootFilterOperator.STARTS_WITH;
            case ENDS_WITH -> GraphqlSelection.GeneratedRootFilterOperator.ENDS_WITH;
        };
    }

    private static GraphqlFieldDescriptor.ScalarFilterOperator scalarFilterOperator(String name) {
        return switch (name) {
            case "eq" -> GraphqlFieldDescriptor.ScalarFilterOperator.EQ;
            case "neq" -> GraphqlFieldDescriptor.ScalarFilterOperator.NEQ;
            case "in" -> GraphqlFieldDescriptor.ScalarFilterOperator.IN;
            case "isNull" -> GraphqlFieldDescriptor.ScalarFilterOperator.IS_NULL;
            case "lt" -> GraphqlFieldDescriptor.ScalarFilterOperator.LT;
            case "lte" -> GraphqlFieldDescriptor.ScalarFilterOperator.LTE;
            case "gt" -> GraphqlFieldDescriptor.ScalarFilterOperator.GT;
            case "gte" -> GraphqlFieldDescriptor.ScalarFilterOperator.GTE;
            case "contains" -> GraphqlFieldDescriptor.ScalarFilterOperator.CONTAINS;
            case "startsWith" -> GraphqlFieldDescriptor.ScalarFilterOperator.STARTS_WITH;
            case "endsWith" -> GraphqlFieldDescriptor.ScalarFilterOperator.ENDS_WITH;
            default -> null;
        };
    }

    private static List<GraphqlSelection.GeneratedRootFilterValue> validateScalarFilterValue(
            String fieldName,
            String operator,
            GraphqlAst.Value value,
            String scalarType
    ) {
        if (operator.equals("isNull")) {
            if (value instanceof GraphqlAst.BooleanValue booleanValue) {
                return List.of(new GraphqlSelection.GeneratedRootFilterValue(
                        "Boolean",
                        "",
                        0L,
                        booleanValue.value(),
                        false
                ));
            }
            throw new GraphqlException("filter operator 'isNull' on field '" + fieldName + "' must be Boolean");
        }
        if (operator.equals("in")) {
            if (!(value instanceof GraphqlAst.InputListValue listValue)) {
                throw new GraphqlException("filter operator 'in' on field '" + fieldName + "' must be a list");
            }
            List<GraphqlSelection.GeneratedRootFilterValue> values = new ArrayList<>();
            for (GraphqlAst.Value item : listValue.values()) {
                values.add(validateScalarLiteral(fieldName, "in", item, scalarType, false));
            }
            return List.copyOf(values);
        }
        if (operator.equals("contains") || operator.equals("startsWith") || operator.equals("endsWith")) {
            if (scalarType.equals("String") == false) {
                throw new GraphqlException("unsupported filter operator '" + operator
                        + "' on field '" + fieldName + "'");
            }
            return List.of(validateScalarLiteral(fieldName, operator, value, scalarType, false));
        }
        if (operator.equals("lt") || operator.equals("lte") || operator.equals("gt") || operator.equals("gte")) {
            if (scalarType.equals("Int") == false) {
                throw new GraphqlException("unsupported filter operator '" + operator
                        + "' on field '" + fieldName + "'");
            }
            return List.of(validateScalarLiteral(fieldName, operator, value, scalarType, false));
        }
        return List.of(validateScalarLiteral(fieldName, operator, value, scalarType, true));
    }

    private static GraphqlSelection.GeneratedRootFilterValue validateScalarLiteral(
            String fieldName,
            String operator,
            GraphqlAst.Value value,
            String scalarType,
            boolean allowNull
    ) {
        if (allowNull && value instanceof GraphqlAst.NullValue) {
            return new GraphqlSelection.GeneratedRootFilterValue(scalarType, "", 0L, false, true);
        }
        if (scalarType.equals("Int") && value instanceof GraphqlAst.IntValue intValue) {
            return new GraphqlSelection.GeneratedRootFilterValue(scalarType, "", intValue.value(), false, false);
        }
        if (scalarType.equals("String") && value instanceof GraphqlAst.StringValue stringValue) {
            return new GraphqlSelection.GeneratedRootFilterValue(scalarType, stringValue.value(), 0L, false, false);
        }
        throw new GraphqlException("filter operator '" + operator + "' on field '" + fieldName
                + "' must use " + scalarType + " values");
    }

    private static List<GraphqlSelection.RootOrder> validateGeneratedRootOrderBy(
            GraphqlRootField rootField,
            String fieldName,
            GraphqlAst.Value value
    ) {
        if (rootField.sortPaths().isEmpty()) {
            throw new GraphqlException("root field '" + fieldName + "' does not support generated orderBy arguments");
        }
        if (!(value instanceof GraphqlAst.InputListValue listValue)) {
            throw new GraphqlException("argument 'orderBy' must be a list");
        }
        List<GraphqlSelection.RootOrder> orderBy = new ArrayList<>();
        for (GraphqlAst.Value item : listValue.values()) {
            if (!(item instanceof GraphqlAst.InputObjectValue objectValue)) {
                throw new GraphqlException("argument 'orderBy' entries must be input objects");
            }
            for (Map.Entry<String, GraphqlAst.Value> entry : objectValue.fields().entrySet()) {
                GraphqlRootField.RootFieldSortPath sortPath = rootSortPath(rootField, entry.getKey());
                if (sortPath == null) {
                    throw new GraphqlException("unknown sort path '" + entry.getKey()
                            + "' on " + rootField.typeName() + "OrderBy");
                }
                if (sortPath.sortHopCount() > 1) {
                    throw new GraphqlException("sort path '" + sortPath.name() + "' exceeds root sort hop budget");
                }
                if (entry.getValue() instanceof GraphqlAst.EnumValue enumValue
                        && (enumValue.value().equals("ASC") || enumValue.value().equals("DESC"))) {
                    orderBy.add(new GraphqlSelection.RootOrder(
                            sortPath.name(),
                            sortPath.columnName(),
                            sortPath.sortPath(),
                            sortPath.sortHopCount(),
                            enumValue.value().equals("ASC")
                                    ? GraphqlRootField.RootCursorDirection.ASC
                                    : GraphqlRootField.RootCursorDirection.DESC,
                            sortPath.nullOrdering(),
                            sortPath.tieBreakerColumnName()
                    ));
                    continue;
                }
                throw new GraphqlException("sort path '" + entry.getKey() + "' must be ASC or DESC");
            }
        }
        return List.copyOf(orderBy);
    }

    private static GraphqlRootField.RootFieldSortPath rootSortPath(GraphqlRootField rootField, String name) {
        for (GraphqlRootField.RootFieldSortPath sortPath : rootField.sortPaths()) {
            if (sortPath.name().equals(name)) {
                return sortPath;
            }
        }
        return null;
    }

    private static boolean isStructuredInput(GraphqlAst.Value value) {
        return value instanceof GraphqlAst.InputListValue
                || value instanceof GraphqlAst.InputObjectValue
                || value instanceof GraphqlAst.EnumValue
                || value instanceof GraphqlAst.NullValue;
    }

    private static boolean hasRootRelayPaginationArgument(Map<String, GraphqlAst.Value> arguments) {
        for (String name : arguments.keySet()) {
            if (isRootRelayPaginationArgument(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRootRelayPaginationArgument(String name) {
        return name.equals("first") || name.equals("after") || name.equals("last") || name.equals("before");
    }

    private static String scalarType(String fieldName, String columnName) {
        String normalizedField = fieldName.toLowerCase();
        String normalizedColumn = columnName.toLowerCase();
        if (normalizedField.equals("id")
                || normalizedField.endsWith("id")
                || normalizedColumn.equals("id")
                || normalizedColumn.endsWith("_id")) {
            return "Int";
        }
        return "String";
    }

    private static GraphqlException unsupportedManyArgument(String fieldName, GraphqlRootField rootField) {
        String supported = "'" + rootField.limitArgumentName() + "'";
        for (GraphqlRootArgumentDescriptor argument : rootField.filterArguments()) {
            supported = supported + ", '" + argument.name() + "'";
        }
        return new GraphqlException("unsupported argument on " + fieldName + "; supported arguments are " + supported);
    }

    private static List<GraphqlSelection.RelationArgument> relationArguments(
            Map<String, GraphqlAst.Value> arguments,
            GraphqlObjectType parentType,
            GraphqlFieldDescriptor relation
    ) {
        if (arguments.isEmpty()) {
            return List.of();
        }
        List<GraphqlSelection.RelationArgument> validated = new ArrayList<>();
        for (GraphqlRelationArgumentDescriptor descriptor : relation.relationArguments()) {
            GraphqlAst.Value value = arguments.get(descriptor.name());
            if (value == null) {
                continue;
            }
            validateRelationCapability(parentType, relation, descriptor);
            validated.add(validateRelationArgumentValue(parentType, relation, descriptor, value));
        }
        for (String name : arguments.keySet()) {
            if (relation.relationArgument(name) == null) {
                throw unsupportedRelationArgument(parentType, relation);
            }
        }
        validateRelationRelayArguments(parentType, relation, validated);
        return List.copyOf(validated);
    }

    private static void validateRelationCapability(
            GraphqlObjectType parentType,
            GraphqlFieldDescriptor relation,
            GraphqlRelationArgumentDescriptor descriptor
    ) {
        GraphqlFieldDescriptor.RelationCapabilities capabilities = relation.relationCapabilities();
        switch (descriptor.kind()) {
            case INT_EQUALS -> {
                if (capabilities.supportsFiltering() == false) {
                    throw new GraphqlException(parentType.name() + "." + relation.name()
                            + " does not support filter arguments");
                }
                if (descriptor.filterHopCount() > capabilities.filterHopBudget()) {
                    throw new GraphqlException(parentType.name() + "." + relation.name()
                            + " filter argument '" + descriptor.name()
                            + "' exceeds filter hop budget of " + capabilities.filterHopBudget());
                }
            }
            case RELAY_FIRST, RELAY_AFTER, RELAY_LAST, RELAY_BEFORE -> {
                if (capabilities.paginationMode() != GraphqlFieldDescriptor.RelationPaginationMode.RELAY_CONNECTION) {
                    throw new GraphqlException(parentType.name() + "." + relation.name()
                            + " does not support Relay pagination arguments");
                }
            }
        }
    }

    private static void validateDeclaredSortPaths(
            GraphqlObjectType parentType,
            GraphqlFieldDescriptor relation
    ) {
        if (relation.relationSortPaths().isEmpty()) {
            return;
        }
        GraphqlFieldDescriptor.RelationCapabilities capabilities = relation.relationCapabilities();
        if (capabilities.supportsSorting() == false
                && capabilities.paginationMode() != GraphqlFieldDescriptor.RelationPaginationMode.RELAY_CONNECTION) {
            throw new GraphqlException(parentType.name() + "." + relation.name()
                    + " declares sort paths but does not support sorting");
        }
        for (GraphqlFieldDescriptor.RelationSortPath sortPath : relation.relationSortPaths()) {
            if (sortPath.sortHopCount() > capabilities.sortHopBudget()) {
                throw new GraphqlException(parentType.name() + "." + relation.name()
                        + " sort path '" + sortPath.name()
                        + "' exceeds sort hop budget of " + capabilities.sortHopBudget());
            }
        }
    }

    private static GraphqlSelection.RelationArgument validateRelationArgumentValue(
            GraphqlObjectType parentType,
            GraphqlFieldDescriptor relation,
            GraphqlRelationArgumentDescriptor descriptor,
            GraphqlAst.Value value
    ) {
        return switch (descriptor.kind()) {
            case INT_EQUALS -> intRelationArgument(descriptor, value);
            case RELAY_FIRST, RELAY_LAST -> relayIntArgument(relation, descriptor, value);
            case RELAY_AFTER, RELAY_BEFORE -> relayCursorArgument(parentType, relation, descriptor, value);
        };
    }

    private static GraphqlSelection.RelationArgument intRelationArgument(
            GraphqlRelationArgumentDescriptor descriptor,
            GraphqlAst.Value value
    ) {
        if (value instanceof GraphqlAst.IntValue intValue) {
            return GraphqlSelection.RelationArgument.intValue(
                    descriptor.name(),
                    descriptor.columnName(),
                    descriptor.kind(),
                    descriptor.filterPath(),
                    descriptor.filterHopCount(),
                    intValue.value()
            );
        }
        throw new GraphqlException("argument '" + descriptor.name() + "' must be an integer");
    }

    private static GraphqlSelection.RelationArgument relayIntArgument(
            GraphqlFieldDescriptor relation,
            GraphqlRelationArgumentDescriptor descriptor,
            GraphqlAst.Value value
    ) {
        if (value instanceof GraphqlAst.IntValue intValue) {
            long pageSize = intValue.value();
            int maxPageSize = relation.relationCapabilities().maxPageSize();
            if (pageSize < 0L || pageSize > maxPageSize) {
                throw new GraphqlException("argument '" + descriptor.name()
                        + "' must be between 0 and " + maxPageSize);
            }
            return GraphqlSelection.RelationArgument.intValue(
                    descriptor.name(),
                    descriptor.columnName(),
                    descriptor.kind(),
                    descriptor.filterPath(),
                    descriptor.filterHopCount(),
                    pageSize
            );
        }
        throw new GraphqlException("argument '" + descriptor.name() + "' must be an integer");
    }

    private static GraphqlSelection.RelationArgument relayCursorArgument(
            GraphqlObjectType parentType,
            GraphqlFieldDescriptor relation,
            GraphqlRelationArgumentDescriptor descriptor,
            GraphqlAst.Value value
    ) {
        if (value instanceof GraphqlAst.StringValue stringValue) {
            validateRelationCursorOrdering(parentType, relation);
            return GraphqlSelection.RelationArgument.cursorValue(
                    descriptor.name(),
                    descriptor.kind(),
                    stringValue.value(),
                    GraphqlCursorCodec.decodeForOrdering(
                            stringValue.value(),
                            relation.relationSortPaths().getFirst()
                    )
            );
        }
        throw new GraphqlException("argument '" + descriptor.name() + "' must be a string cursor");
    }

    private static void validateRelationRelayArguments(
            GraphqlObjectType parentType,
            GraphqlFieldDescriptor relation,
            List<GraphqlSelection.RelationArgument> arguments
    ) {
        boolean hasFirst = false;
        boolean hasLast = false;
        boolean hasRelayArgument = false;
        for (GraphqlSelection.RelationArgument argument : arguments) {
            switch (argument.kind()) {
                case RELAY_FIRST -> {
                    hasFirst = true;
                    hasRelayArgument = true;
                }
                case RELAY_LAST -> {
                    hasLast = true;
                    hasRelayArgument = true;
                }
                case RELAY_AFTER, RELAY_BEFORE -> hasRelayArgument = true;
                case INT_EQUALS -> {
                }
            }
        }
        if (hasFirst && hasLast) {
            throw new GraphqlException(parentType.name() + "." + relation.name()
                    + " cannot combine 'first' and 'last'");
        }
        if (hasRelayArgument) {
            validateRelationCursorOrdering(parentType, relation);
        }
    }

    private static void validateRelationCursorOrdering(
            GraphqlObjectType parentType,
            GraphqlFieldDescriptor relation
    ) {
        if (relation.relationSortPaths().isEmpty()) {
            throw new GraphqlException(parentType.name() + "." + relation.name()
                    + " requires relation sort metadata for Relay pagination");
        }
    }

    private static GraphqlException unsupportedRelationArgument(
            GraphqlObjectType parentType,
            GraphqlFieldDescriptor relation
    ) {
        if (relation.relationArguments().isEmpty()) {
            return new GraphqlException(parentType.name() + "." + relation.name()
                    + " does not accept relation arguments");
        }
        String supported = "'" + relation.relationArguments().getFirst().name() + "'";
        for (int i = 1; i < relation.relationArguments().size(); i++) {
            supported = supported + ", '" + relation.relationArguments().get(i).name() + "'";
        }
        return new GraphqlException("unsupported argument on " + parentType.name() + "." + relation.name()
                + "; supported arguments are " + supported);
    }

    private static GraphqlFieldDescriptor.RelationRetrievalShape relationRetrievalShape(
            GraphqlFieldDescriptor relation,
            List<GraphqlSelection.RelationArgument> arguments
    ) {
        boolean hasFilter = false;
        boolean hasRelayPagination = false;
        for (GraphqlSelection.RelationArgument argument : arguments) {
            switch (argument.kind()) {
                case INT_EQUALS -> hasFilter = true;
                case RELAY_FIRST, RELAY_AFTER, RELAY_LAST, RELAY_BEFORE -> hasRelayPagination = true;
            }
        }
        if (hasRelayPagination) {
            return GraphqlFieldDescriptor.RelationRetrievalShape.RELAY_CONNECTION_PAGE;
        }
        if (relation.relationCapabilities().paginationMode()
                == GraphqlFieldDescriptor.RelationPaginationMode.RELAY_CONNECTION) {
            return GraphqlFieldDescriptor.RelationRetrievalShape.RELAY_CONNECTION_PAGE;
        }
        if (hasFilter) {
            return GraphqlFieldDescriptor.RelationRetrievalShape.FILTERED_BATCH;
        }
        return GraphqlFieldDescriptor.RelationRetrievalShape.BATCH_LOOKUP;
    }

    private static int relationPageSize(
            GraphqlFieldDescriptor relation,
            List<GraphqlSelection.RelationArgument> arguments
    ) {
        for (GraphqlSelection.RelationArgument argument : arguments) {
            if (argument.kind() == GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_FIRST
                    || argument.kind() == GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_LAST) {
                return (int) argument.intValue();
            }
        }
        return relation.relationCapabilities().defaultPageSize();
    }

    private record RootSelection(
            List<GraphqlSelection.FieldSelection> nodeSelections,
            GraphqlSelection.RootConnectionSelection connectionSelection
    ) {
        public RootSelection {
            nodeSelections = List.copyOf(nodeSelections);
        }
    }

    private record RelationSelection(
            List<GraphqlSelection.FieldSelection> nodeSelections,
            GraphqlSelection.RelationConnectionSelection connectionSelection
    ) {
        public RelationSelection {
            nodeSelections = List.copyOf(nodeSelections);
        }
    }

    private record RootCursor(
            String rawCursor,
            GraphqlCursorCodec.CursorPayload payload
    ) {
        public static RootCursor none() {
            return new RootCursor("", null);
        }
    }
}
