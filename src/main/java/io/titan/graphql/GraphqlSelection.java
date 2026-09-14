package io.titan.graphql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GraphqlSelection {

    private final String rootFieldName;
    private final String rootResponseKey;
    private final String rootRetrievalName;
    private final GraphqlRootField.RootRetrievalShape rootRetrievalShape;
    private final GraphqlRootField.RetrievalCapabilities rootRetrievalCapabilities;
    private final GraphqlRootField.RootCursorOrdering rootCursorOrdering;
    private final String rootTypeName;
    private final long rootId;
    private final Map<String, Object> rootKeyValues;
    private final int rootLimit;
    private final RootPagination rootPagination;
    private final RootConnectionSelection rootConnectionSelection;
    private final GraphqlRootField.ResultCardinality rootCardinality;
    private final List<RootFilter> rootFilters;
    private final List<GraphqlRootField.RootContextFilter> rootContextFilters;
    private final List<GeneratedRootFilter> generatedRootFilters;
    private final List<RootOrder> rootOrderBy;
    private final List<FieldSelection> fields;

    public GraphqlSelection(String rootFieldName, String rootTypeName, long rootId, List<FieldSelection> fields) {
        this(
                rootFieldName,
                rootFieldName,
                rootFieldName,
                GraphqlRootField.RootRetrievalShape.POINT_LOOKUP,
                new GraphqlRootField.RetrievalCapabilities(true, true, false, false, false),
                GraphqlRootField.RootCursorOrdering.ascending("id", "id", "id"),
                rootTypeName,
                rootId,
                1,
                RootPagination.none(),
                RootConnectionSelection.none(),
                GraphqlRootField.ResultCardinality.ONE,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                fields
        );
    }

    public GraphqlSelection(
            String rootFieldName,
            String rootResponseKey,
            String rootRetrievalName,
            GraphqlRootField.RootRetrievalShape rootRetrievalShape,
            GraphqlRootField.RetrievalCapabilities rootRetrievalCapabilities,
            GraphqlRootField.RootCursorOrdering rootCursorOrdering,
            String rootTypeName,
            long rootId,
            int rootLimit,
            RootPagination rootPagination,
            RootConnectionSelection rootConnectionSelection,
            GraphqlRootField.ResultCardinality rootCardinality,
            List<RootFilter> rootFilters,
            List<FieldSelection> fields
    ) {
        this(
                rootFieldName,
                rootResponseKey,
                rootRetrievalName,
                rootRetrievalShape,
                rootRetrievalCapabilities,
                rootCursorOrdering,
                rootTypeName,
                rootId,
                rootLimit,
                rootPagination,
                rootConnectionSelection,
                rootCardinality,
                rootFilters,
                List.of(),
                List.of(),
                List.of(),
                fields
        );
    }

    public GraphqlSelection(
            String rootFieldName,
            String rootResponseKey,
            String rootRetrievalName,
            GraphqlRootField.RootRetrievalShape rootRetrievalShape,
            GraphqlRootField.RetrievalCapabilities rootRetrievalCapabilities,
            GraphqlRootField.RootCursorOrdering rootCursorOrdering,
            String rootTypeName,
            long rootId,
            int rootLimit,
            RootPagination rootPagination,
            RootConnectionSelection rootConnectionSelection,
            GraphqlRootField.ResultCardinality rootCardinality,
            List<RootFilter> rootFilters,
            List<GraphqlRootField.RootContextFilter> rootContextFilters,
            List<GeneratedRootFilter> generatedRootFilters,
            List<RootOrder> rootOrderBy,
            List<FieldSelection> fields
    ) {
        this(
                rootFieldName, rootResponseKey, rootRetrievalName, rootRetrievalShape,
                rootRetrievalCapabilities, rootCursorOrdering, rootTypeName, rootId, rootLimit,
                rootPagination, rootConnectionSelection, rootCardinality, rootFilters,
                rootContextFilters, generatedRootFilters, rootOrderBy, fields, Map.of()
        );
    }

    public GraphqlSelection(
            String rootFieldName,
            String rootResponseKey,
            String rootRetrievalName,
            GraphqlRootField.RootRetrievalShape rootRetrievalShape,
            GraphqlRootField.RetrievalCapabilities rootRetrievalCapabilities,
            GraphqlRootField.RootCursorOrdering rootCursorOrdering,
            String rootTypeName,
            long rootId,
            int rootLimit,
            RootPagination rootPagination,
            RootConnectionSelection rootConnectionSelection,
            GraphqlRootField.ResultCardinality rootCardinality,
            List<RootFilter> rootFilters,
            List<GraphqlRootField.RootContextFilter> rootContextFilters,
            List<GeneratedRootFilter> generatedRootFilters,
            List<RootOrder> rootOrderBy,
            List<FieldSelection> fields,
            Map<String, Object> rootKeyValues
    ) {
        this.rootFieldName = rootFieldName;
        this.rootResponseKey = rootResponseKey;
        this.rootRetrievalName = rootRetrievalName;
        this.rootRetrievalShape = rootRetrievalShape;
        this.rootRetrievalCapabilities = rootRetrievalCapabilities;
        this.rootCursorOrdering = rootCursorOrdering;
        this.rootTypeName = rootTypeName;
        this.rootId = rootId;
        this.rootKeyValues = Collections.unmodifiableMap(new LinkedHashMap<>(rootKeyValues));
        this.rootLimit = rootLimit;
        this.rootPagination = rootPagination;
        this.rootConnectionSelection = rootConnectionSelection;
        this.rootCardinality = rootCardinality;
        this.rootFilters = List.copyOf(rootFilters);
        this.rootContextFilters = List.copyOf(rootContextFilters);
        this.generatedRootFilters = List.copyOf(generatedRootFilters);
        this.rootOrderBy = List.copyOf(rootOrderBy);
        this.fields = List.copyOf(fields);
    }

    public String rootFieldName() {
        return rootFieldName;
    }

    public String rootResponseKey() {
        return rootResponseKey;
    }

    public String rootRetrievalName() {
        return rootRetrievalName;
    }

    public GraphqlRootField.RootRetrievalShape rootRetrievalShape() {
        return rootRetrievalShape;
    }

    public GraphqlRootField.RetrievalCapabilities rootRetrievalCapabilities() {
        return rootRetrievalCapabilities;
    }

    public GraphqlRootField.RootCursorOrdering rootCursorOrdering() {
        return rootCursorOrdering;
    }

    public String rootTypeName() {
        return rootTypeName;
    }

    public long rootId() {
        return rootId;
    }

    public Map<String, Object> rootKeyValues() {
        return rootKeyValues;
    }

    public int rootLimit() {
        return rootLimit;
    }

    public RootPagination rootPagination() {
        return rootPagination;
    }

    public RootConnectionSelection rootConnectionSelection() {
        return rootConnectionSelection;
    }

    public GraphqlRootField.ResultCardinality rootCardinality() {
        return rootCardinality;
    }

    public List<RootFilter> rootFilters() {
        return rootFilters;
    }

    List<GraphqlRootField.RootContextFilter> rootContextFilters() {
        return rootContextFilters;
    }

    public List<GeneratedRootFilter> generatedRootFilters() {
        return generatedRootFilters;
    }

    public List<RootOrder> rootOrderBy() {
        return rootOrderBy;
    }

    public Long rootFilterValue(String name) {
        for (RootFilter filter : rootFilters) {
            if (filter.argumentName().equals(name)) {
                return filter.value();
            }
        }
        return null;
    }

    public List<FieldSelection> fields() {
        return fields;
    }

    public List<String> scalarFieldNames() {
        List<String> names = new ArrayList<>();
        for (FieldSelection field : fields) {
            if (field.kind() == GraphqlFieldDescriptor.FieldKind.SCALAR) {
                names.add(field.name());
            }
        }
        return Collections.unmodifiableList(names);
    }

    public FieldSelection relation(String name) {
        for (FieldSelection field : fields) {
            if (field.kind() == GraphqlFieldDescriptor.FieldKind.RELATION && field.name().equals(name)) {
                return field;
            }
        }
        return null;
    }

    public boolean includesRelation(String name) {
        return relation(name) != null;
    }

    public static Builder builder(String rootFieldName, String rootTypeName, long rootId) {
        return new Builder(
                rootFieldName,
                rootFieldName,
                rootFieldName,
                GraphqlRootField.RootRetrievalShape.POINT_LOOKUP,
                new GraphqlRootField.RetrievalCapabilities(true, true, false, false, false),
                GraphqlRootField.RootCursorOrdering.ascending("id", "id", "id"),
                rootTypeName,
                rootId,
                1,
                RootPagination.none(),
                RootConnectionSelection.none(),
                GraphqlRootField.ResultCardinality.ONE,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of()
        );
    }

    static Builder builder(
            String rootFieldName,
            String rootTypeName,
            long rootId,
            int rootLimit,
            GraphqlRootField.ResultCardinality rootCardinality
    ) {
        return new Builder(
                rootFieldName,
                rootFieldName,
                rootFieldName,
                defaultShape(rootCardinality),
                GraphqlRootField.RetrievalCapabilities.currentDefault(rootCardinality, List.of()),
                GraphqlRootField.RootCursorOrdering.ascending("id", "id", "id"),
                rootTypeName,
                rootId,
                rootLimit,
                RootPagination.none(),
                RootConnectionSelection.none(),
                rootCardinality,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of()
        );
    }

    static Builder builder(
            String rootFieldName,
            String rootResponseKey,
            String rootRetrievalName,
            GraphqlRootField.RootRetrievalShape rootRetrievalShape,
            GraphqlRootField.RetrievalCapabilities rootRetrievalCapabilities,
            GraphqlRootField.RootCursorOrdering rootCursorOrdering,
            String rootTypeName,
            long rootId,
            int rootLimit,
            RootPagination rootPagination,
            RootConnectionSelection rootConnectionSelection,
            GraphqlRootField.ResultCardinality rootCardinality,
            List<RootFilter> rootFilters
    ) {
        return builder(
                rootFieldName,
                rootResponseKey,
                rootRetrievalName,
                rootRetrievalShape,
                rootRetrievalCapabilities,
                rootCursorOrdering,
                rootTypeName,
                rootId,
                rootLimit,
                rootPagination,
                rootConnectionSelection,
                rootCardinality,
                rootFilters,
                List.of(),
                List.of(),
                List.of(),
                Map.of()
        );
    }

    static Builder builder(
            String rootFieldName,
            String rootResponseKey,
            String rootRetrievalName,
            GraphqlRootField.RootRetrievalShape rootRetrievalShape,
            GraphqlRootField.RetrievalCapabilities rootRetrievalCapabilities,
            GraphqlRootField.RootCursorOrdering rootCursorOrdering,
            String rootTypeName,
            long rootId,
            int rootLimit,
            RootPagination rootPagination,
            RootConnectionSelection rootConnectionSelection,
            GraphqlRootField.ResultCardinality rootCardinality,
            List<RootFilter> rootFilters,
            List<GraphqlRootField.RootContextFilter> rootContextFilters,
            List<GeneratedRootFilter> generatedRootFilters,
            List<RootOrder> rootOrderBy
    ) {
        return builder(
                rootFieldName, rootResponseKey, rootRetrievalName, rootRetrievalShape,
                rootRetrievalCapabilities, rootCursorOrdering, rootTypeName, rootId, rootLimit,
                rootPagination, rootConnectionSelection, rootCardinality, rootFilters,
                rootContextFilters, generatedRootFilters, rootOrderBy, Map.of()
        );
    }

    static Builder builder(
            String rootFieldName,
            String rootResponseKey,
            String rootRetrievalName,
            GraphqlRootField.RootRetrievalShape rootRetrievalShape,
            GraphqlRootField.RetrievalCapabilities rootRetrievalCapabilities,
            GraphqlRootField.RootCursorOrdering rootCursorOrdering,
            String rootTypeName,
            long rootId,
            int rootLimit,
            RootPagination rootPagination,
            RootConnectionSelection rootConnectionSelection,
            GraphqlRootField.ResultCardinality rootCardinality,
            List<RootFilter> rootFilters,
            List<GraphqlRootField.RootContextFilter> rootContextFilters,
            List<GeneratedRootFilter> generatedRootFilters,
            List<RootOrder> rootOrderBy,
            Map<String, Object> rootKeyValues
    ) {
        return new Builder(
                rootFieldName,
                rootResponseKey,
                rootRetrievalName,
                rootRetrievalShape,
                rootRetrievalCapabilities,
                rootCursorOrdering,
                rootTypeName,
                rootId,
                rootLimit,
                rootPagination,
                rootConnectionSelection,
                rootCardinality,
                rootFilters,
                rootContextFilters,
                generatedRootFilters,
                rootOrderBy,
                rootKeyValues
        );
    }

    public record RootFilter(String argumentName, String columnName, long value) {
    }

    public enum GeneratedRootFilterKind {
        SCALAR,
        AND,
        OR,
        NOT
    }

    public enum GeneratedRootFilterOperator {
        EQ,
        NEQ,
        IN,
        IS_NULL,
        LT,
        LTE,
        GT,
        GTE,
        CONTAINS,
        STARTS_WITH,
        ENDS_WITH
    }

    public record GeneratedRootFilter(
            GeneratedRootFilterKind kind,
            String fieldName,
            String columnName,
            String filterPath,
            int filterHopCount,
            String scalarType,
            GeneratedRootFilterOperator operator,
            List<GeneratedRootFilterValue> values,
            List<GeneratedRootFilter> children
    ) {
        public GeneratedRootFilter {
            values = List.copyOf(values);
            children = List.copyOf(children);
        }
    }

    public record GeneratedRootFilterValue(
            String scalarType,
            String stringValue,
            long intValue,
            boolean booleanValue,
            boolean nullValue
    ) {
    }

    public record RootOrder(
            String name,
            String columnName,
            String sortPath,
            int sortHopCount,
            GraphqlRootField.RootCursorDirection direction,
            GraphqlRootField.RootNullOrdering nullOrdering,
            String tieBreakerColumnName
    ) {
    }

    public record RootPagination(
            Integer first,
            String after,
            GraphqlCursorCodec.CursorPayload afterCursor,
            Integer last,
            String before,
            GraphqlCursorCodec.CursorPayload beforeCursor
    ) {
        public static RootPagination none() {
            return new RootPagination(null, "", null, null, "", null);
        }

        boolean hasArguments() {
            return first != null || after.isEmpty() == false || last != null || before.isEmpty() == false;
        }
    }

    public record RootConnectionSelection(
            boolean selected,
            boolean edges,
            boolean edgeCursor,
            boolean edgeNode,
            boolean totalCount,
            boolean pageInfo,
            List<String> pageInfoFields
    ) {
        public RootConnectionSelection {
            pageInfoFields = List.copyOf(pageInfoFields);
        }

        public static RootConnectionSelection none() {
            return new RootConnectionSelection(false, false, false, false, false, false, List.of());
        }
    }

    public record RelationArgument(
            String argumentName,
            String columnName,
            GraphqlRelationArgumentDescriptor.RelationArgumentKind kind,
            String filterPath,
            int filterHopCount,
            long intValue,
            String stringValue,
            GraphqlCursorCodec.CursorPayload cursorPayload
    ) {
        public static RelationArgument intValue(
                String argumentName,
                String columnName,
                GraphqlRelationArgumentDescriptor.RelationArgumentKind kind,
                String filterPath,
                int filterHopCount,
                long value
        ) {
            return new RelationArgument(argumentName, columnName, kind, filterPath, filterHopCount, value, "", null);
        }

        public static RelationArgument stringValue(
                String argumentName,
                GraphqlRelationArgumentDescriptor.RelationArgumentKind kind,
                String value
        ) {
            return new RelationArgument(argumentName, "", kind, "", 0, 0L, value, null);
        }

        public static RelationArgument cursorValue(
                String argumentName,
                GraphqlRelationArgumentDescriptor.RelationArgumentKind kind,
                String value,
                GraphqlCursorCodec.CursorPayload cursorPayload
        ) {
            return new RelationArgument(argumentName, "", kind, "", 0, 0L, value, cursorPayload);
        }
    }

    public record FieldSelection(
            String name,
            String responseKey,
            GraphqlFieldDescriptor.FieldKind kind,
            String typeName,
            String relationRetrievalName,
            GraphqlFieldDescriptor.RelationRetrievalShape relationRetrievalShape,
            List<RelationArgument> relationArguments,
            RelationConnectionSelection relationConnectionSelection,
            List<FieldSelection> selections
    ) {
        public FieldSelection {
            responseKey = responseKey == null || responseKey.isBlank() ? name : responseKey;
            relationArguments = List.copyOf(relationArguments);
            relationConnectionSelection = relationConnectionSelection == null
                    ? RelationConnectionSelection.none()
                    : relationConnectionSelection;
            selections = List.copyOf(selections);
        }

        FieldSelection(
                String name,
                String responseKey,
                GraphqlFieldDescriptor.FieldKind kind,
                String typeName,
                List<FieldSelection> selections
        ) {
            this(
                    name,
                    responseKey,
                    kind,
                    typeName,
                    "",
                    GraphqlFieldDescriptor.RelationRetrievalShape.BATCH_LOOKUP,
                    List.of(),
                    RelationConnectionSelection.none(),
                    selections
            );
        }

        List<String> scalarFieldNames() {
            List<String> names = new ArrayList<>();
            for (FieldSelection selection : selections) {
                if (selection.kind() == GraphqlFieldDescriptor.FieldKind.SCALAR) {
                    names.add(selection.name());
                }
            }
            return Collections.unmodifiableList(names);
        }
    }

    public record RelationConnectionSelection(
            boolean selected,
            boolean edges,
            boolean edgeCursor,
            boolean edgeNode,
            boolean pageInfo,
            boolean totalCount,
            int pageSize,
            List<String> pageInfoFields
    ) {
        public RelationConnectionSelection {
            pageInfoFields = List.copyOf(pageInfoFields);
        }

        RelationConnectionSelection(
                boolean selected,
                boolean edges,
                boolean edgeCursor,
                boolean edgeNode,
                boolean pageInfo,
                List<String> pageInfoFields
        ) {
            this(selected, edges, edgeCursor, edgeNode, pageInfo, false, 0, pageInfoFields);
        }

        public static RelationConnectionSelection none() {
            return new RelationConnectionSelection(false, false, false, false, false, false, 0, List.of());
        }
    }

    public static final class Builder {
        private final String rootFieldName;
        private final String rootResponseKey;
        private final String rootRetrievalName;
        private final GraphqlRootField.RootRetrievalShape rootRetrievalShape;
        private final GraphqlRootField.RetrievalCapabilities rootRetrievalCapabilities;
        private final GraphqlRootField.RootCursorOrdering rootCursorOrdering;
        private final String rootTypeName;
        private final long rootId;
        private final Map<String, Object> rootKeyValues;
        private final int rootLimit;
        private final RootPagination rootPagination;
        private final RootConnectionSelection rootConnectionSelection;
        private final GraphqlRootField.ResultCardinality rootCardinality;
        private final List<RootFilter> rootFilters;
        private final List<GraphqlRootField.RootContextFilter> rootContextFilters;
        private final List<GeneratedRootFilter> generatedRootFilters;
        private final List<RootOrder> rootOrderBy;
        private final List<FieldSelection> fields = new ArrayList<>();

        private Builder(
                String rootFieldName,
                String rootResponseKey,
                String rootRetrievalName,
                GraphqlRootField.RootRetrievalShape rootRetrievalShape,
                GraphqlRootField.RetrievalCapabilities rootRetrievalCapabilities,
                GraphqlRootField.RootCursorOrdering rootCursorOrdering,
                String rootTypeName,
                long rootId,
                int rootLimit,
                RootPagination rootPagination,
                RootConnectionSelection rootConnectionSelection,
                GraphqlRootField.ResultCardinality rootCardinality,
                List<RootFilter> rootFilters,
                List<GraphqlRootField.RootContextFilter> rootContextFilters,
                List<GeneratedRootFilter> generatedRootFilters,
                List<RootOrder> rootOrderBy,
                Map<String, Object> rootKeyValues
        ) {
            this.rootFieldName = rootFieldName;
            this.rootResponseKey = rootResponseKey == null || rootResponseKey.isBlank()
                    ? rootFieldName
                    : rootResponseKey;
            this.rootRetrievalName = rootRetrievalName;
            this.rootRetrievalShape = rootRetrievalShape;
            this.rootRetrievalCapabilities = rootRetrievalCapabilities;
            this.rootCursorOrdering = rootCursorOrdering;
            this.rootTypeName = rootTypeName;
            this.rootId = rootId;
            this.rootKeyValues = Collections.unmodifiableMap(new LinkedHashMap<>(rootKeyValues));
            this.rootLimit = rootLimit;
            this.rootPagination = rootPagination;
            this.rootConnectionSelection = rootConnectionSelection;
            this.rootCardinality = rootCardinality;
            this.rootFilters = List.copyOf(rootFilters);
            this.rootContextFilters = List.copyOf(rootContextFilters);
            this.generatedRootFilters = List.copyOf(generatedRootFilters);
            this.rootOrderBy = List.copyOf(rootOrderBy);
        }

        void add(FieldSelection selection) {
            fields.add(selection);
        }

        GraphqlSelection build() {
            return new GraphqlSelection(
                    rootFieldName,
                    rootResponseKey,
                    rootRetrievalName,
                    rootRetrievalShape,
                    rootRetrievalCapabilities,
                    rootCursorOrdering,
                    rootTypeName,
                    rootId,
                    rootLimit,
                    rootPagination,
                    rootConnectionSelection,
                    rootCardinality,
                    rootFilters,
                    rootContextFilters,
                    generatedRootFilters,
                    rootOrderBy,
                    fields,
                    rootKeyValues
            );
        }
    }

    private static GraphqlRootField.RootRetrievalShape defaultShape(GraphqlRootField.ResultCardinality cardinality) {
        return switch (cardinality) {
            case ONE -> GraphqlRootField.RootRetrievalShape.POINT_LOOKUP;
            case MANY -> GraphqlRootField.RootRetrievalShape.LIST_QUERY;
        };
    }
}
