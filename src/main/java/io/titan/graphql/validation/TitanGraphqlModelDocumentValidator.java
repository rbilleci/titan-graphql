package io.titan.graphql.validation;

import io.titan.graphql.model.TitanGraphqlContextFilterDocument;
import io.titan.graphql.model.TitanGraphqlFieldDocument;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModuleDocument;
import io.titan.graphql.model.TitanGraphqlPolicyDocument;
import io.titan.graphql.model.TitanGraphqlRelationDocument;
import io.titan.graphql.model.TitanGraphqlRootDocument;
import io.titan.graphql.model.TitanGraphqlTypeDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TitanGraphqlModelDocumentValidator {

    private TitanGraphqlModelDocumentValidator() {
    }

    public static TitanGraphqlValidationReport validate(TitanGraphqlModelDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("model document is required");
        }
        ValidationContext context = new ValidationContext(document);
        context.validate();
        return new TitanGraphqlValidationReport(context.issues);
    }

    private static final class ValidationContext {

        private final TitanGraphqlModelDocument document;
        private final List<TitanGraphqlValidationIssue> issues;
        private final Map<String, TitanGraphqlRootDocument> roots;
        private final Map<String, TitanGraphqlTypeDocument> types;
        private final Map<String, TitanGraphqlPolicyDocument> policies;
        private final Map<String, TitanGraphqlContextFilterDocument> contextFilters;
        private final Map<String, TitanGraphqlModuleDocument> modules;

        ValidationContext(TitanGraphqlModelDocument document) {
            this.document = document;
            this.issues = new ArrayList<>();
            this.roots = new LinkedHashMap<>();
            this.types = new LinkedHashMap<>();
            this.policies = new LinkedHashMap<>();
            this.contextFilters = new LinkedHashMap<>();
            this.modules = new LinkedHashMap<>();
        }

        void validate() {
            indexTopLevelNames();
            validateRoots();
            validateTypes();
        }

        private void indexTopLevelNames() {
            for (TitanGraphqlModuleDocument module : document.modules()) {
                index(modules, module.name(), module, path("modules", module.name()), "module");
            }
            for (TitanGraphqlRootDocument root : document.roots()) {
                index(roots, root.name(), root, path("roots", root.name()), "root");
            }
            for (TitanGraphqlTypeDocument type : document.types()) {
                index(types, type.name(), type, path("types", type.name()), "type");
            }
            for (TitanGraphqlPolicyDocument policy : document.policies()) {
                index(policies, policy.name(), policy, path("policies", policy.name()), "policy");
            }
            for (TitanGraphqlContextFilterDocument contextFilter : document.contextFilters()) {
                index(contextFilters, contextFilter.name(), contextFilter,
                        path("contextFilters", contextFilter.name()), "context filter");
            }
        }

        private <T> void index(Map<String, T> index, String name, T value, TitanGraphqlModelPath path, String label) {
            if (index.containsKey(name)) {
                issue(
                        TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                        "Duplicate " + label + " name '" + name + "'.",
                        path
                );
                return;
            }
            index.put(name, value);
        }

        private void validateRoots() {
            for (TitanGraphqlRootDocument root : document.roots()) {
                TitanGraphqlModelPath rootPath = path("roots", root.name());
                TitanGraphqlTypeDocument type = types.get(root.type());
                if (type == null) {
                    issue(
                            TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                            "Root '" + root.name() + "' references unknown type '" + root.type() + "'.",
                            rootPath
                    );
                    continue;
                }
                for (String contextFilter : root.contextFilters()) {
                    if (!contextFilters.containsKey(contextFilter)) {
                        issue(
                                TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                                "Root '" + root.name() + "' references unknown context filter '" + contextFilter + "'.",
                                path("roots", root.name(), "contextFilters", contextFilter)
                        );
                    }
                }
                validateRootFilterPaths(root, type);
                validateRootSortPaths(root, type);
            }
        }

        private void validateRootFilterPaths(TitanGraphqlRootDocument root, TitanGraphqlTypeDocument type) {
            TypeIndex typeIndex = new TypeIndex(type);
            for (TitanGraphqlRootDocument.RootDocumentFilterPath filterPath : root.filterPaths()) {
                if (filterPath.hops() != 0) {
                    continue;
                }
                TitanGraphqlFieldDocument field = typeIndex.field(filterPath.name(), filterPath.path(), filterPath.column());
                if (field == null) {
                    issue(
                            TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                            "Root '" + root.name() + "' filter '" + filterPath.name()
                                    + "' does not bind to a field on type '" + type.name() + "'.",
                            path("roots", root.name(), "filterPaths", filterPath.name())
                    );
                    continue;
                }
                if (field.computed() != null && !field.computed().filterable()) {
                    issue(
                            TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                            "Root '" + root.name() + "' filter '" + filterPath.name()
                                    + "' references non-filterable computed field '" + field.name() + "'.",
                            path("roots", root.name(), "filterPaths", filterPath.name())
                    );
                }
            }
        }

        private void validateRootSortPaths(TitanGraphqlRootDocument root, TitanGraphqlTypeDocument type) {
            TypeIndex typeIndex = new TypeIndex(type);
            for (TitanGraphqlRootDocument.RootDocumentSortPath sortPath : root.sortPaths()) {
                if (sortPath.hops() != 0) {
                    continue;
                }
                TitanGraphqlFieldDocument field = typeIndex.field(sortPath.name(), sortPath.path(), sortPath.column());
                if (field == null) {
                    issue(
                            TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                            "Root '" + root.name() + "' sort '" + sortPath.name()
                                    + "' does not bind to a field on type '" + type.name() + "'.",
                            path("roots", root.name(), "sortPaths", sortPath.name())
                    );
                    continue;
                }
                if (field.computed() != null && !field.computed().sortable()) {
                    issue(
                            TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                            "Root '" + root.name() + "' sort '" + sortPath.name()
                                    + "' references non-sortable computed field '" + field.name() + "'.",
                            path("roots", root.name(), "sortPaths", sortPath.name())
                    );
                }
            }
        }

        private void validateTypes() {
            for (TitanGraphqlTypeDocument type : document.types()) {
                TypeIndex typeIndex = new TypeIndex(type);
                validateDuplicateTypeMembers(type);
                validateFields(type, typeIndex);
                validateRelations(type);
            }
        }

        private void validateDuplicateTypeMembers(TitanGraphqlTypeDocument type) {
            Set<String> fieldNames = new LinkedHashSet<>();
            for (TitanGraphqlFieldDocument field : type.fields()) {
                if (!fieldNames.add(field.name())) {
                    issue(
                            TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                            "Duplicate field name '" + field.name() + "' on type '" + type.name() + "'.",
                            path("types", type.name(), "fields", field.name())
                    );
                }
            }
            Set<String> relationNames = new LinkedHashSet<>();
            for (TitanGraphqlRelationDocument relation : type.relations()) {
                if (!relationNames.add(relation.name())) {
                    issue(
                            TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                            "Duplicate relation name '" + relation.name() + "' on type '" + type.name() + "'.",
                            path("types", type.name(), "relations", relation.name())
                    );
                }
            }
        }

        private void validateFields(TitanGraphqlTypeDocument type, TypeIndex typeIndex) {
            for (TitanGraphqlFieldDocument field : type.fields()) {
                for (String policy : field.policies()) {
                    if (!policies.containsKey(policy)) {
                        issue(
                                TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                                "Field '" + type.name() + "." + field.name()
                                        + "' references unknown policy '" + policy + "'.",
                                path("types", type.name(), "fields", field.name(), "policies", policy)
                        );
                    }
                }
                if (field.computed() == null) {
                    continue;
                }
                for (String requiredColumn : field.computed().requiredColumns()) {
                    if (!typeIndex.binds(requiredColumn)) {
                        issue(
                                TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                                "Computed field '" + type.name() + "." + field.name()
                                        + "' requires unknown column or field '" + requiredColumn + "'.",
                                path("types", type.name(), "fields", field.name(), "computed", "requiredColumns", requiredColumn)
                        );
                    }
                }
            }
        }

        private void validateRelations(TitanGraphqlTypeDocument type) {
            for (TitanGraphqlRelationDocument relation : type.relations()) {
                if (!types.containsKey(relation.targetType())) {
                    issue(
                            TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                            "Relation '" + type.name() + "." + relation.name()
                                    + "' references unknown target type '" + relation.targetType() + "'.",
                            path("types", type.name(), "relations", relation.name())
                    );
                }
                for (String policy : relation.policies()) {
                    if (!policies.containsKey(policy)) {
                        issue(
                                TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                                "Relation '" + type.name() + "." + relation.name()
                                        + "' references unknown policy '" + policy + "'.",
                                path("types", type.name(), "relations", relation.name(), "policies", policy)
                        );
                    }
                }
            }
        }

        private void issue(TitanGraphqlValidationIssueCode code, String message, TitanGraphqlModelPath path) {
            issues.add(TitanGraphqlValidationIssue.error(
                    code,
                    message,
                    path,
                    TitanGraphqlSourceLocation.none()
            ));
        }

        private TitanGraphqlModelPath path(String... segments) {
            return TitanGraphqlModelPath.of(segments);
        }
    }

    private static final class TypeIndex {

        private final Map<String, TitanGraphqlFieldDocument> fieldsByName;
        private final Map<String, TitanGraphqlFieldDocument> fieldsByColumn;
        private final Set<String> bindings;

        TypeIndex(TitanGraphqlTypeDocument type) {
            this.fieldsByName = new LinkedHashMap<>();
            this.fieldsByColumn = new LinkedHashMap<>();
            this.bindings = new LinkedHashSet<>();
            for (TitanGraphqlFieldDocument field : type.fields()) {
                fieldsByName.putIfAbsent(field.name(), field);
                bindings.add(field.name());
                if (!field.column().isBlank()) {
                    fieldsByColumn.putIfAbsent(field.column(), field);
                    bindings.add(field.column());
                }
            }
        }

        TitanGraphqlFieldDocument field(String name, String path, String column) {
            TitanGraphqlFieldDocument field = byNameOrColumn(path);
            if (field != null) {
                return field;
            }
            field = byNameOrColumn(name);
            if (field != null) {
                return field;
            }
            return byNameOrColumn(column);
        }

        boolean binds(String nameOrColumn) {
            return bindings.contains(nameOrColumn);
        }

        private TitanGraphqlFieldDocument byNameOrColumn(String nameOrColumn) {
            if (nameOrColumn == null || nameOrColumn.isBlank()) {
                return null;
            }
            TitanGraphqlFieldDocument field = fieldsByName.get(nameOrColumn);
            if (field != null) {
                return field;
            }
            return fieldsByColumn.get(nameOrColumn);
        }
    }
}
