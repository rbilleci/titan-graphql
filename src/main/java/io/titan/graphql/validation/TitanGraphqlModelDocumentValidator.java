package io.titan.graphql.validation;

import io.titan.graphql.TitanGraphqlInputDefaultValidator;
import io.titan.graphql.model.TitanGraphqlContextFilterDocument;
import io.titan.graphql.model.TitanGraphqlDirectiveDocument;
import io.titan.graphql.model.TitanGraphqlEnumDocument;
import io.titan.graphql.model.TitanGraphqlFieldDocument;
import io.titan.graphql.model.TitanGraphqlInputObjectDocument;
import io.titan.graphql.model.TitanGraphqlInterfaceDocument;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModuleDocument;
import io.titan.graphql.model.TitanGraphqlMutationDocument;
import io.titan.graphql.model.TitanGraphqlPolicyDocument;
import io.titan.graphql.model.TitanGraphqlRelationDocument;
import io.titan.graphql.model.TitanGraphqlRootDocument;
import io.titan.graphql.model.TitanGraphqlTypeDocument;
import io.titan.graphql.model.TitanGraphqlUnionDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TitanGraphqlModelDocumentValidator {

    private static final Set<String> OUTPUT_SCALAR_TYPES = Set.of(
            "Boolean", "Date", "DateTime", "Decimal", "Float", "ID", "Int", "Long", "String",
            "Timestamp", "UUID"
    );

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
        private final Map<String, TitanGraphqlInterfaceDocument> interfaces;
        private final Map<String, TitanGraphqlUnionDocument> unions;
        private final Map<String, TitanGraphqlEnumDocument> enums;
        private final Map<String, TitanGraphqlInputObjectDocument> inputObjects;
        private final Map<String, TitanGraphqlDirectiveDocument> directives;
        private final Map<String, TitanGraphqlPolicyDocument> policies;
        private final Map<String, TitanGraphqlMutationDocument> mutations;
        private final Map<String, TitanGraphqlContextFilterDocument> contextFilters;
        private final Map<String, TitanGraphqlModuleDocument> modules;

        ValidationContext(TitanGraphqlModelDocument document) {
            this.document = document;
            this.issues = new ArrayList<>();
            this.roots = new LinkedHashMap<>();
            this.types = new LinkedHashMap<>();
            this.interfaces = new LinkedHashMap<>();
            this.unions = new LinkedHashMap<>();
            this.enums = new LinkedHashMap<>();
            this.inputObjects = new LinkedHashMap<>();
            this.directives = new LinkedHashMap<>();
            this.policies = new LinkedHashMap<>();
            this.mutations = new LinkedHashMap<>();
            this.contextFilters = new LinkedHashMap<>();
            this.modules = new LinkedHashMap<>();
        }

        void validate() {
            indexTopLevelNames();
            validateEnums();
            validateInputObjects();
            validateDirectives();
            validateInterfaces();
            validateUnions();
            validateRoots();
            validateTypes();
            validateMutations();
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
            for (TitanGraphqlInterfaceDocument interfaceType : document.interfaces()) {
                index(interfaces, interfaceType.name(), interfaceType,
                        path("interfaces", interfaceType.name()), "interface");
            }
            for (TitanGraphqlUnionDocument union : document.unions()) {
                index(unions, union.name(), union, path("unions", union.name()), "union");
            }
            for (TitanGraphqlEnumDocument enumType : document.enums()) {
                index(enums, enumType.name(), enumType, path("enums", enumType.name()), "enum");
                if (types.containsKey(enumType.name()) || OUTPUT_SCALAR_TYPES.contains(enumType.name())
                        || enumType.name().startsWith("__")) {
                    issue(
                            TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                            "Enum name '" + enumType.name() + "' conflicts with another schema type.",
                            path("enums", enumType.name())
                    );
                }
            }
            for (TitanGraphqlInputObjectDocument inputObject : document.inputObjects()) {
                index(inputObjects, inputObject.name(), inputObject,
                        path("inputObjects", inputObject.name()), "input object");
                if (types.containsKey(inputObject.name()) || enums.containsKey(inputObject.name())
                        || OUTPUT_SCALAR_TYPES.contains(inputObject.name())
                        || inputObject.name().startsWith("__")) {
                    issue(
                            TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                            "Input object name '" + inputObject.name()
                                    + "' conflicts with another schema type.",
                            path("inputObjects", inputObject.name())
                    );
                }
            }
            for (TitanGraphqlDirectiveDocument directive : document.directives()) {
                index(directives, directive.name(), directive,
                        path("directives", directive.name()), "directive");
            }
            for (TitanGraphqlPolicyDocument policy : document.policies()) {
                index(policies, policy.name(), policy, path("policies", policy.name()), "policy");
            }
            for (TitanGraphqlMutationDocument mutation : document.mutations()) {
                index(mutations, mutation.name(), mutation, path("mutations", mutation.name()), "mutation");
            }
            for (TitanGraphqlContextFilterDocument contextFilter : document.contextFilters()) {
                index(contextFilters, contextFilter.name(), contextFilter,
                        path("contextFilters", contextFilter.name()), "context filter");
            }
        }

        private void validateDirectives() {
            for (TitanGraphqlDirectiveDocument directive : document.directives()) {
                TitanGraphqlModelPath directivePath = path("directives", directive.name());
                if (!graphqlName(directive.name()) || directive.name().startsWith("__")) {
                    issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                            "Directive name '" + directive.name()
                                    + "' is not a valid public GraphQL directive name.",
                            directivePath);
                }
                if (directive.name().equals("include") || directive.name().equals("skip")) {
                    issue(TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                            "Directive name '" + directive.name()
                                    + "' conflicts with a built-in executable directive.",
                            directivePath);
                }
                if (directive.behavior() == null) {
                    issue(TitanGraphqlValidationIssueCode.MISSING_REQUIRED_FIELD,
                            "Directive '" + directive.name() + "' needs a behavior.",
                            path("directives", directive.name(), "behavior"));
                }
                if (directive.locations().isEmpty()) {
                    issue(TitanGraphqlValidationIssueCode.MISSING_REQUIRED_FIELD,
                            "Directive '" + directive.name()
                                    + "' needs at least one executable location.",
                            path("directives", directive.name(), "locations"));
                }
                Set<TitanGraphqlDirectiveDocument.ExecutableLocation> locations = new LinkedHashSet<>();
                for (TitanGraphqlDirectiveDocument.ExecutableLocation location : directive.locations()) {
                    if (location == null || !locations.add(location)) {
                        issue(TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                                "Directive '" + directive.name()
                                        + "' has a null or duplicate executable location.",
                                path("directives", directive.name(), "locations"));
                    }
                }
            }
        }

        private void validateEnums() {
            for (TitanGraphqlEnumDocument enumType : document.enums()) {
                TitanGraphqlModelPath enumPath = path("enums", enumType.name());
                if (!graphqlName(enumType.name()) || !Character.isUpperCase(enumType.name().charAt(0))) {
                    issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                            "Enum type name '" + enumType.name() + "' is not a valid GraphQL type name.", enumPath);
                }
                if (enumType.values().isEmpty()) {
                    issue(TitanGraphqlValidationIssueCode.MISSING_REQUIRED_FIELD,
                            "Enum '" + enumType.name() + "' needs at least one value.", enumPath);
                }
                Set<String> values = new LinkedHashSet<>();
                for (String value : enumType.values()) {
                    TitanGraphqlModelPath valuePath = path("enums", enumType.name(), "values", value);
                    if (!values.add(value)) {
                        issue(TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                                "Enum '" + enumType.name() + "' has duplicate value '" + value + "'.", valuePath);
                    }
                    if (!graphqlName(value) || value.equals("true") || value.equals("false")
                            || value.equals("null") || value.startsWith("__")) {
                        issue(TitanGraphqlValidationIssueCode.UNKNOWN_ENUM_VALUE,
                                "Enum '" + enumType.name() + "' has invalid value '" + value + "'.", valuePath);
                    }
                }
                Set<String> metadataNames = new LinkedHashSet<>();
                for (TitanGraphqlEnumDocument.EnumValueMetadata metadata : enumType.valueMetadata()) {
                    TitanGraphqlModelPath metadataPath = path(
                            "enums", enumType.name(), "valueMetadata", metadata.name());
                    if (!metadataNames.add(metadata.name())) {
                        issue(TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                                "Enum '" + enumType.name() + "' has duplicate metadata for value '"
                                        + metadata.name() + "'.", metadataPath);
                    }
                    if (!values.contains(metadata.name())) {
                        issue(TitanGraphqlValidationIssueCode.UNKNOWN_ENUM_VALUE,
                                "Enum '" + enumType.name() + "' has metadata for undeclared value '"
                                        + metadata.name() + "'.", metadataPath);
                    }
                    if (!metadata.deprecated() && !metadata.deprecationReason().isBlank()) {
                        issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                                "Enum '" + enumType.name() + "' value '" + metadata.name()
                                        + "' has a deprecation reason but is not deprecated.", metadataPath);
                    }
                }
            }
        }

        private void validateInputObjects() {
            for (TitanGraphqlInputObjectDocument inputObject : document.inputObjects()) {
                TitanGraphqlModelPath inputPath = path("inputObjects", inputObject.name());
                if (!graphqlName(inputObject.name())
                        || !Character.isUpperCase(inputObject.name().charAt(0))) {
                    issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                            "Input object type name '" + inputObject.name()
                                    + "' is not a valid GraphQL type name.", inputPath);
                }
                if (inputObject.fields().isEmpty()) {
                    issue(TitanGraphqlValidationIssueCode.MISSING_REQUIRED_FIELD,
                            "Input object '" + inputObject.name() + "' needs at least one field.", inputPath);
                }
                Set<String> fieldNames = new LinkedHashSet<>();
                for (TitanGraphqlInputObjectDocument.InputField field : inputObject.fields()) {
                    TitanGraphqlModelPath fieldPath = path(
                            "inputObjects", inputObject.name(), "fields", field.name());
                    if (!graphqlName(field.name()) || !fieldNames.add(field.name())) {
                        issue(TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                                "Input object '" + inputObject.name() + "' has an invalid or duplicate field '"
                                        + field.name() + "'.", fieldPath);
                    }
                    String fieldType = validInputTypeReference(field.type());
                    String namedType = inputNamedType(fieldType);
                    if (fieldType.isEmpty() || !OUTPUT_SCALAR_TYPES.contains(namedType)
                            && !enums.containsKey(namedType) && !inputObjects.containsKey(namedType)) {
                        issue(TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                                "Input field '" + inputObject.name() + "." + field.name()
                                        + "' has unknown or malformed type '" + field.type() + "'.", fieldPath);
                    }
                    if (field.defaultValue().contains("$")) {
                        issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                                "Input field '" + inputObject.name() + "." + field.name()
                                        + "' default must be a constant GraphQL value.", fieldPath);
                    }
                    if (!field.defaultValue().isEmpty()) {
                        String defaultError = TitanGraphqlInputDefaultValidator.error(
                                document, field.type(), field.defaultValue());
                        if (!defaultError.isEmpty()) {
                            issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                                    "Input field '" + inputObject.name() + "." + field.name()
                                            + "' has an invalid default: " + defaultError + ".", fieldPath);
                        }
                    }
                    if (!field.deprecated() && !field.deprecationReason().isEmpty()) {
                        issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                                "Input field '" + inputObject.name() + "." + field.name()
                                        + "' has a deprecation reason but is not deprecated.", fieldPath);
                    }
                    if (field.deprecated() && fieldType.endsWith("!") && field.defaultValue().isEmpty()) {
                        issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                                "Required input field '" + inputObject.name() + "." + field.name()
                                        + "' cannot be deprecated without a default.", fieldPath);
                    }
                }
            }
            for (TitanGraphqlInputObjectDocument inputObject : document.inputObjects()) {
                if (hasUnbrokenInputCycle(inputObject.name(), inputObject.name(), new LinkedHashSet<>())) {
                    issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                            "Input object '" + inputObject.name()
                                    + "' has an unbroken non-null singular circular reference.",
                            path("inputObjects", inputObject.name()));
                }
            }
        }

        private void validateInterfaces() {
            for (TitanGraphqlInterfaceDocument interfaceType : document.interfaces()) {
                TitanGraphqlModelPath interfacePath = path("interfaces", interfaceType.name());
                if (!graphqlTypeName(interfaceType.name())) {
                    issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                            "Interface name '" + interfaceType.name()
                                    + "' is not a valid GraphQL type name.", interfacePath);
                }
                if (types.containsKey(interfaceType.name()) || enums.containsKey(interfaceType.name())
                        || inputObjects.containsKey(interfaceType.name())
                        || unions.containsKey(interfaceType.name())
                        || OUTPUT_SCALAR_TYPES.contains(interfaceType.name())) {
                    issue(TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                            "Interface name '" + interfaceType.name()
                                    + "' conflicts with another schema type.", interfacePath);
                }
                if (interfaceType.fields().isEmpty()) {
                    issue(TitanGraphqlValidationIssueCode.MISSING_REQUIRED_FIELD,
                            "Interface '" + interfaceType.name() + "' needs at least one field.", interfacePath);
                }
                Set<String> names = new LinkedHashSet<>();
                for (TitanGraphqlInterfaceDocument.InterfaceField field : interfaceType.fields()) {
                    TitanGraphqlModelPath fieldPath = path(
                            "interfaces", interfaceType.name(), "fields", field.name());
                    if (!graphqlName(field.name()) || !names.add(field.name())) {
                        issue(TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                                "Interface '" + interfaceType.name()
                                        + "' has an invalid or duplicate field '" + field.name() + "'.",
                                fieldPath);
                    }
                    String reference = validOutputTypeReference(field.type());
                    String namedType = inputNamedType(reference);
                    if (reference.isEmpty() || !isOutputType(namedType)) {
                        issue(TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                                "Interface field '" + interfaceType.name() + "." + field.name()
                                        + "' has unknown or malformed output type '" + field.type() + "'.",
                                fieldPath);
                    }
                    if (!field.deprecated() && !field.deprecationReason().isEmpty()) {
                        issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                                "Interface field '" + interfaceType.name() + "." + field.name()
                                        + "' has a deprecation reason but is not deprecated.", fieldPath);
                    }
                }
            }
        }

        private void validateUnions() {
            for (TitanGraphqlUnionDocument union : document.unions()) {
                TitanGraphqlModelPath unionPath = path("unions", union.name());
                if (!graphqlTypeName(union.name())) {
                    issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                            "Union name '" + union.name() + "' is not a valid GraphQL type name.", unionPath);
                }
                if (types.containsKey(union.name()) || enums.containsKey(union.name())
                        || inputObjects.containsKey(union.name())
                        || interfaces.containsKey(union.name())
                        || OUTPUT_SCALAR_TYPES.contains(union.name())) {
                    issue(TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                            "Union name '" + union.name()
                                    + "' conflicts with another schema type.", unionPath);
                }
                if (union.members().isEmpty()) {
                    issue(TitanGraphqlValidationIssueCode.MISSING_REQUIRED_FIELD,
                            "Union '" + union.name() + "' needs at least one object member.", unionPath);
                }
                Set<String> members = new LinkedHashSet<>();
                for (String member : union.members()) {
                    TitanGraphqlModelPath memberPath = path("unions", union.name(), "members", member);
                    if (!members.add(member)) {
                        issue(TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                                "Union '" + union.name() + "' has duplicate member '" + member + "'.",
                                memberPath);
                    }
                    if (!types.containsKey(member)) {
                        issue(TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                                "Union '" + union.name() + "' member '" + member
                                        + "' is not a declared object type.", memberPath);
                    }
                }
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
                for (String policy : root.policies()) {
                    if (!policies.containsKey(policy)) {
                        issue(
                                TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                                "Root '" + root.name() + "' references unknown policy '" + policy + "'.",
                                path("roots", root.name(), "policies", policy)
                        );
                    }
                }
                validateAbstractRootOutput(root, type);
                validatePointRoot(root, type);
                validateConnectionArguments(root, type);
                validateConnectionOrdering(root, type);
                validateRootFilterPaths(root, type);
                validateRootSortPaths(root, type);
            }
        }

        private void validateAbstractRootOutput(
                TitanGraphqlRootDocument root,
                TitanGraphqlTypeDocument physicalType
        ) {
            if (root.outputType().isEmpty()) {
                return;
            }
            TitanGraphqlModelPath outputPath = path("roots", root.name(), "outputType");
            TitanGraphqlInterfaceDocument interfaceType = interfaces.get(root.outputType());
            TitanGraphqlUnionDocument union = unions.get(root.outputType());
            if (interfaceType == null && union == null) {
                issue(TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                        "Root '" + root.name() + "' references unknown abstract output type '"
                                + root.outputType() + "'.", outputPath);
            } else if (interfaceType != null && !physicalType.interfaces().contains(interfaceType.name())) {
                issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                        "Root '" + root.name() + "' physical type '" + physicalType.name()
                                + "' does not implement interface '" + interfaceType.name() + "'.", outputPath);
            } else if (union != null && !union.members().contains(physicalType.name())) {
                issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                        "Root '" + root.name() + "' physical type '" + physicalType.name()
                                + "' is not a member of union '" + union.name() + "'.", outputPath);
            }
        }

        private void validateMutations() {
            for (TitanGraphqlMutationDocument mutation : document.mutations()) {
                TitanGraphqlModelPath mutationPath = path("mutations", mutation.name());
                TitanGraphqlTypeDocument type = types.get(mutation.type());
                if (mutation.operation() != TitanGraphqlMutationDocument.MutationDocumentOperation.UPDATE) {
                    issue(TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                            "Mutation '" + mutation.name() + "' must declare operation update.", mutationPath);
                }
                if (type == null) {
                    issue(TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                            "Mutation '" + mutation.name() + "' references unknown type '" + mutation.type() + "'.",
                            mutationPath);
                    continue;
                }
                TypeIndex typeIndex = new TypeIndex(type);
                Set<String> argumentNames = new LinkedHashSet<>();
                Set<String> argumentColumns = new LinkedHashSet<>();
                boolean hasKey = false;
                boolean hasAssignment = false;
                List<MutationValidationBinding> bindings = mutationValidationBindings(mutation, mutationPath);
                for (MutationValidationBinding argument : bindings) {
                    TitanGraphqlModelPath argumentPath = path("mutations", mutation.name(),
                            argument.inputPath() ? "inputBindings" : "arguments", argument.name());
                    if (!argumentNames.add(argument.name()) || !argumentColumns.add(argument.column())) {
                        issue(TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                                "Mutation '" + mutation.name() + "' has a duplicate argument binding '"
                                        + argument.name() + "'.", argumentPath);
                    }
                    TitanGraphqlFieldDocument field = typeIndex.byColumn(argument.column());
                    if (field == null || field.computed() != null) {
                        issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                                "Mutation '" + mutation.name() + "' argument '" + argument.name()
                                        + "' must bind a stored scalar field on type '" + type.name() + "'.",
                                argumentPath);
                    } else if (!normalizeType(field.type()).equals(normalizeType(argument.type()))) {
                        issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                                "Mutation '" + mutation.name() + "' argument '" + argument.name()
                                        + "' type must match field '" + field.name() + "'.", argumentPath);
                    }
                    String argumentType = normalizeType(argument.type());
                    if (!Set.of("Int", "Long", "String", "ID", "UUID", "Boolean", "Float", "Decimal")
                            .contains(argumentType) && !enums.containsKey(argumentType)) {
                        issue(TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                                "Mutation '" + mutation.name() + "' argument '" + argument.name()
                                        + "' uses an unsupported input type.", argumentPath);
                    }
                    hasKey = hasKey || argument.key();
                    hasAssignment = hasAssignment || !argument.key();
                }
                if (!hasKey || !hasAssignment) {
                    issue(TitanGraphqlValidationIssueCode.MISSING_REQUIRED_FIELD,
                            "Mutation '" + mutation.name()
                                    + "' needs at least one key and one non-key assignment argument.", mutationPath);
                }
                for (String policy : mutation.policies()) {
                    if (!policies.containsKey(policy)) {
                        issue(TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                                "Mutation '" + mutation.name() + "' references unknown policy '" + policy + "'.",
                                path("mutations", mutation.name(), "policies", policy));
                    }
                }
                Set<String> payloadNames = new LinkedHashSet<>();
                for (TitanGraphqlMutationDocument.MutationDocumentPayloadField field : mutation.payload()) {
                    if (!payloadNames.add(field.name()) || !argumentNames.contains(field.argument())) {
                        issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                                "Mutation '" + mutation.name() + "' payload field '" + field.name()
                                        + "' must reference one declared argument.",
                                path("mutations", mutation.name(), "payload", field.name()));
                    }
                }
                if (mutation.payload().isEmpty()) {
                    issue(TitanGraphqlValidationIssueCode.MISSING_REQUIRED_FIELD,
                            "Mutation '" + mutation.name() + "' needs at least one payload field.", mutationPath);
                }
            }
        }

        private void validateConnectionOrdering(
                TitanGraphqlRootDocument root,
                TitanGraphqlTypeDocument type
        ) {
            if (root.operation() != TitanGraphqlRootDocument.RootDocumentOperation.CONNECTION
                    || root.pagination() == null || root.pagination().cursor() == null) {
                return;
            }
            TypeIndex typeIndex = new TypeIndex(type);
            TitanGraphqlRootDocument.Cursor cursor = root.pagination().cursor();
            TitanGraphqlFieldDocument cursorField = typeIndex.field(
                    cursor.path(), cursor.path(), cursor.column());
            if (cursorField != null && cursorField.nullable()) {
                issue(
                        TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                        "Root '" + root.name() + "' cursor field '" + cursor.path()
                                + "' must be non-null for portable stable pagination.",
                        path("roots", root.name(), "pagination", "cursor")
                );
            }
            String tieBreaker = cursor.tieBreaker().isBlank() ? cursor.column() : cursor.tieBreaker();
            TitanGraphqlFieldDocument tieField = typeIndex.field(tieBreaker, tieBreaker, tieBreaker);
            if (tieField != null && tieField.nullable()) {
                issue(
                        TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                        "Root '" + root.name() + "' cursor tie breaker '" + tieBreaker
                                + "' must be non-null for portable stable pagination.",
                        path("roots", root.name(), "pagination", "cursor")
                );
            }
        }

        private List<MutationValidationBinding> mutationValidationBindings(
                TitanGraphqlMutationDocument mutation,
                TitanGraphqlModelPath mutationPath
        ) {
            List<MutationValidationBinding> result = new ArrayList<>();
            if (mutation.input() == null) {
                if (!mutation.inputBindings().isEmpty()) {
                    issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                            "Mutation '" + mutation.name()
                                    + "' declares inputBindings without an input object argument.", mutationPath);
                }
                for (TitanGraphqlMutationDocument.MutationDocumentArgument argument : mutation.arguments()) {
                    validateArgumentDefault("Mutation '" + mutation.name() + "' argument '"
                                    + argument.name() + "'", requiredInputType(argument.type()),
                            argument.defaultValue(),
                            path("mutations", mutation.name(), "arguments", argument.name()));
                    result.add(new MutationValidationBinding(
                            argument.name(), argument.type(), argument.column(), argument.key(), false));
                }
                return result;
            }
            if (!mutation.arguments().isEmpty()) {
                issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                        "Mutation '" + mutation.name()
                                + "' cannot mix flat arguments with an input object argument.", mutationPath);
            }
            if (!graphqlName(mutation.input().name())) {
                issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                        "Mutation '" + mutation.name() + "' input argument name is invalid.", mutationPath);
            }
            String inputTypeReference = validInputTypeReference(mutation.input().type());
            String inputTypeName = inputNamedType(inputTypeReference);
            if (inputTypeReference.isEmpty() || !inputObjects.containsKey(inputTypeName)) {
                issue(TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                        "Mutation '" + mutation.name() + "' references unknown input object '"
                                + mutation.input().type() + "'.", mutationPath);
            }
            if (!inputTypeReference.isEmpty() && !inputTypeReference.equals(inputTypeName)) {
                issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                        "Mutation '" + mutation.name()
                                + "' input type must be a bare input-object name; the generated public argument"
                                + " is already non-null.", mutationPath);
            }
            validateArgumentDefault("Mutation '" + mutation.name() + "' argument '"
                            + mutation.input().name() + "'", requiredInputType(mutation.input().type()),
                    mutation.input().defaultValue(), path("mutations", mutation.name(), "input"));
            if (mutation.inputBindings().isEmpty()) {
                issue(TitanGraphqlValidationIssueCode.MISSING_REQUIRED_FIELD,
                        "Mutation '" + mutation.name() + "' needs at least one input binding.", mutationPath);
            }
            for (TitanGraphqlMutationDocument.MutationDocumentInputBinding binding : mutation.inputBindings()) {
                TitanGraphqlModelPath bindingPath = path(
                        "mutations", mutation.name(), "inputBindings", binding.name());
                TitanGraphqlInputObjectDocument.InputField field = inputFieldAtPath(
                        inputTypeName, binding.path());
                if (field == null) {
                    issue(TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                            "Mutation '" + mutation.name() + "' input binding '" + binding.name()
                                    + "' references unknown or unsupported field path '" + binding.path() + "'.",
                            bindingPath);
                } else if (!normalizeType(field.type()).equals(normalizeType(binding.type()))) {
                    issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                            "Mutation '" + mutation.name() + "' input binding '" + binding.name()
                                    + "' type must match input field '" + binding.path() + "'.", bindingPath);
                } else if (!field.type().endsWith("!") && field.defaultValue().isEmpty()) {
                    issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                            "Mutation '" + mutation.name() + "' database-bound input field '"
                                    + binding.path() + "' must be non-null or declare a default.", bindingPath);
                }
                result.add(new MutationValidationBinding(
                        binding.name(), binding.type(), binding.column(), binding.key(), true));
            }
            return result;
        }

        private TitanGraphqlInputObjectDocument.InputField inputFieldAtPath(
                String inputTypeName,
                String fieldPath
        ) {
            if (inputTypeName == null || inputTypeName.isEmpty()
                    || fieldPath == null || fieldPath.isEmpty()) {
                return null;
            }
            String currentType = inputTypeName;
            int position = 0;
            while (position < fieldPath.length()) {
                int end = fieldPath.indexOf('.', position);
                if (end < 0) end = fieldPath.length();
                if (end <= position) return null;
                TitanGraphqlInputObjectDocument object = inputObjects.get(currentType);
                if (object == null) return null;
                String fieldName = fieldPath.substring(position, end);
                TitanGraphqlInputObjectDocument.InputField field = object.fields().stream()
                        .filter(candidate -> candidate.name().equals(fieldName))
                        .findFirst().orElse(null);
                if (field == null) return null;
                if (end == fieldPath.length()) return field;
                String reference = validInputTypeReference(field.type());
                if (reference.isEmpty() || reference.startsWith("[")) return null;
                currentType = inputNamedType(reference);
                position = end + 1;
            }
            return null;
        }

        private void validateConnectionArguments(
                TitanGraphqlRootDocument root,
                TitanGraphqlTypeDocument type
        ) {
            if (root.operation() != TitanGraphqlRootDocument.RootDocumentOperation.CONNECTION) {
                return;
            }
            TypeIndex typeIndex = new TypeIndex(type);
            Set<String> names = new LinkedHashSet<>();
            Set<String> columns = new LinkedHashSet<>();
            for (TitanGraphqlRootDocument.RootDocumentArgument argument : root.arguments()) {
                TitanGraphqlModelPath argumentPath = path("roots", root.name(), "arguments", argument.name());
                if (!names.add(argument.name())) {
                    issue(TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                            "Connection root '" + root.name() + "' has duplicate argument name '"
                                    + argument.name() + "'.", argumentPath);
                }
                if (argument.column().isBlank() || !columns.add(argument.column())) {
                    issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                            "Connection root '" + root.name() + "' has a missing or duplicate argument column '"
                                    + argument.column() + "'.", argumentPath);
                }
                if (argument.kind() != TitanGraphqlRootDocument.RootDocumentArgumentKind.EQUALS
                        || argument.hops() != 0) {
                    issue(TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                            "Connection root '" + root.name() + "' argument '" + argument.name()
                                    + "' must be a local equality binding.", argumentPath);
                }
                validateConnectionEqualityBinding("Connection root '" + root.name() + "'", argument.name(),
                        argument.type(), argument.column(), typeIndex, type.name(), argumentPath);
                validateArgumentDefault("Connection root '" + root.name() + "' argument '"
                        + argument.name() + "'", argument.type(), argument.defaultValue(), argumentPath);
            }
        }

        private void validatePointRoot(TitanGraphqlRootDocument root, TitanGraphqlTypeDocument type) {
            if (root.operation() != TitanGraphqlRootDocument.RootDocumentOperation.POINT) {
                return;
            }
            TitanGraphqlModelPath rootPath = path("roots", root.name());
            if (root.argument() != null && !root.arguments().isEmpty()) {
                issue(
                        TitanGraphqlValidationIssueCode.INVALID_BINDING,
                        "Point root '" + root.name() + "' cannot declare both argument and arguments.",
                        rootPath
                );
                return;
            }
            List<TitanGraphqlRootDocument.RootDocumentArgument> arguments = root.argument() == null
                    ? root.arguments() : List.of(root.argument());
            if (arguments.isEmpty()) {
                issue(
                        TitanGraphqlValidationIssueCode.MISSING_REQUIRED_FIELD,
                        "Point root '" + root.name() + "' requires one or more key arguments.",
                        rootPath
                );
                return;
            }

            String primaryKey = effectivePrimaryKey(type);
            if (arguments.size() == 1 && !primaryKey.isBlank()
                    && !primaryKey.equals(arguments.getFirst().column())) {
                issue(
                        TitanGraphqlValidationIssueCode.INVALID_BINDING,
                        "Point root '" + root.name() + "' key column '" + arguments.getFirst().column()
                                + "' does not match the scalar primary key column '" + primaryKey + "'.",
                        rootPath
                );
            }

            TypeIndex typeIndex = new TypeIndex(type);
            Set<String> names = new LinkedHashSet<>();
            Set<String> columns = new LinkedHashSet<>();
            for (TitanGraphqlRootDocument.RootDocumentArgument argument : arguments) {
                TitanGraphqlModelPath argumentPath = path("roots", root.name(), "arguments", argument.name());
                if (!names.add(argument.name())) {
                    issue(
                            TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                            "Point root '" + root.name() + "' has duplicate key argument name '"
                                    + argument.name() + "'.",
                            argumentPath
                    );
                }
                if (argument.column().isBlank() || !columns.add(argument.column())) {
                    issue(
                            TitanGraphqlValidationIssueCode.INVALID_BINDING,
                            "Point root '" + root.name() + "' has a missing or duplicate key column '"
                                    + argument.column() + "'.",
                            argumentPath
                    );
                }
                if (argument.kind() != TitanGraphqlRootDocument.RootDocumentArgumentKind.EQUALS
                        || argument.hops() != 0) {
                    issue(
                            TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                            "Point root '" + root.name() + "' key argument '" + argument.name()
                                    + "' must be a local equality binding.",
                            argumentPath
                    );
                }
                String argumentType = normalizeType(argument.type());
                if (!Set.of("Int", "Long", "String", "ID", "UUID").contains(argumentType)
                        && !enums.containsKey(argumentType)) {
                    issue(
                            TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                            "Point root '" + root.name() + "' key argument '" + argument.name()
                                    + "' must use Int, Long, String, ID, UUID, or a declared enum.",
                            argumentPath
                    );
                    continue;
                }
                TitanGraphqlFieldDocument field = typeIndex.byColumn(argument.column());
                if (field == null || field.computed() != null) {
                    issue(
                            TitanGraphqlValidationIssueCode.INVALID_BINDING,
                            "Point root '" + root.name() + "' key column '" + argument.column()
                                    + "' does not bind to a stored scalar field on type '" + type.name() + "'.",
                            argumentPath
                    );
                } else if (!normalizeType(field.type()).equals(argumentType)) {
                    issue(
                            TitanGraphqlValidationIssueCode.INVALID_BINDING,
                            "Point root '" + root.name() + "' key argument '" + argument.name()
                                    + "' type '" + argument.type() + "' does not match field type '"
                                    + field.type() + "'.",
                            argumentPath
                    );
                }
                validateArgumentDefault("Point root '" + root.name() + "' argument '"
                        + argument.name() + "'", requiredInputType(argument.type()),
                        argument.defaultValue(), argumentPath);
            }
        }

        private String normalizeType(String type) {
            return type == null ? "" : type.replace("!", "").trim();
        }

        private String effectivePrimaryKey(TitanGraphqlTypeDocument type) {
            if (!type.primaryKey().isBlank()) {
                return type.primaryKey();
            }
            return document.database().tables().stream()
                    .filter(table -> table.name().equals(type.table()))
                    .map(table -> table.primaryKey())
                    .findFirst()
                    .orElse("");
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
                if (sortPath.hops() == 1) {
                    validateOneHopRootSortPath(root, type, typeIndex, sortPath);
                    continue;
                }
                if (sortPath.hops() != 0) {
                    issue(
                            TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                            "Root '" + root.name() + "' sort '" + sortPath.name()
                                    + "' exceeds the supported one-hop relation limit.",
                            path("roots", root.name(), "sortPaths", sortPath.name())
                    );
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
                if (field.nullable()) {
                    issue(
                            TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                            "Root '" + root.name() + "' sort '" + sortPath.name()
                                    + "' must bind a non-null scalar for portable stable pagination.",
                            path("roots", root.name(), "sortPaths", sortPath.name())
                    );
                }
                String tieBreaker = sortPath.tieBreaker().isBlank()
                        ? effectivePrimaryKey(type) : sortPath.tieBreaker();
                TitanGraphqlFieldDocument tieField = typeIndex.field(
                        tieBreaker, tieBreaker, tieBreaker);
                if (tieField != null && tieField.nullable()) {
                    issue(
                            TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                            "Root '" + root.name() + "' sort '" + sortPath.name()
                                    + "' tie breaker '" + tieBreaker + "' must be non-null.",
                            path("roots", root.name(), "sortPaths", sortPath.name())
                    );
                }
            }
        }

        private void validateOneHopRootSortPath(
                TitanGraphqlRootDocument root,
                TitanGraphqlTypeDocument owner,
                TypeIndex ownerIndex,
                TitanGraphqlRootDocument.RootDocumentSortPath sortPath
        ) {
            TitanGraphqlModelPath modelPath = path("roots", root.name(), "sortPaths", sortPath.name());
            String[] segments = sortPath.path().split("\\.", -1);
            if (segments.length != 2 || segments[0].isBlank() || segments[1].isBlank()) {
                issue(
                        TitanGraphqlValidationIssueCode.INVALID_BINDING,
                        "Root '" + root.name() + "' one-hop sort '" + sortPath.name()
                                + "' must use relation.field syntax.",
                        modelPath
                );
                return;
            }
            TitanGraphqlRelationDocument relation = owner.relations().stream()
                    .filter(candidate -> candidate.name().equals(segments[0]))
                    .findFirst()
                    .orElse(null);
            if (relation == null) {
                issue(
                        TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                        "Root '" + root.name() + "' one-hop sort '" + sortPath.name()
                                + "' references unknown relation '" + segments[0] + "'.",
                        modelPath
                );
                return;
            }
            if (!sortPath.column().equals(relation.localColumn())) {
                issue(
                        TitanGraphqlValidationIssueCode.INVALID_BINDING,
                        "Root '" + root.name() + "' one-hop sort '" + sortPath.name()
                                + "' column must match relation '" + relation.name() + "' local column '"
                                + relation.localColumn() + "'.",
                        modelPath
                );
            }
            if (relation.cardinality() != TitanGraphqlRelationDocument.RelationDocumentCardinality.ONE
                    || relation.nullable()) {
                issue(
                        TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                        "Root '" + root.name() + "' one-hop sort '" + sortPath.name()
                                + "' requires a non-null to-one relation.",
                        modelPath
                );
            }
            TitanGraphqlTypeDocument target = types.get(relation.targetType());
            if (target == null) {
                issue(
                        TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                        "Root '" + root.name() + "' one-hop sort '" + sortPath.name()
                                + "' relation target '" + relation.targetType() + "' is unknown.",
                        modelPath
                );
                return;
            }
            TypeIndex targetIndex = new TypeIndex(target);
            TitanGraphqlFieldDocument targetField = targetIndex.byName(segments[1]);
            if (targetField == null) {
                issue(
                        TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                        "Root '" + root.name() + "' one-hop sort '" + sortPath.name()
                                + "' references unknown scalar field '" + relation.targetType() + "."
                                + segments[1] + "'.",
                        modelPath
                );
            } else if (targetField.computed() != null || targetField.nullable()) {
                issue(
                        TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                        "Root '" + root.name() + "' one-hop sort '" + sortPath.name()
                                + "' requires a stored non-null target scalar field.",
                        modelPath
                );
            }
            String tieBreaker = sortPath.tieBreaker().isBlank()
                    ? effectivePrimaryKey(owner) : sortPath.tieBreaker();
            TitanGraphqlFieldDocument tieField = ownerIndex.field(tieBreaker, tieBreaker, tieBreaker);
            if (tieField == null) {
                issue(
                        TitanGraphqlValidationIssueCode.INVALID_BINDING,
                        "Root '" + root.name() + "' one-hop sort '" + sortPath.name()
                                + "' tie breaker '" + tieBreaker
                                + "' does not bind to a scalar field on type '" + owner.name() + "'.",
                        modelPath
                );
            } else if (tieField.nullable()) {
                issue(
                        TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                        "Root '" + root.name() + "' one-hop sort '" + sortPath.name()
                                + "' tie breaker '" + tieBreaker + "' must be non-null.",
                        modelPath
                );
            }
        }

        private void validateTypes() {
            for (TitanGraphqlTypeDocument type : document.types()) {
                TypeIndex typeIndex = new TypeIndex(type);
                validateDuplicateTypeMembers(type);
                validateFields(type, typeIndex);
                validateRelations(type);
                validateImplementedInterfaces(type, typeIndex);
            }
        }

        private void validateImplementedInterfaces(TitanGraphqlTypeDocument type, TypeIndex typeIndex) {
            Set<String> names = new LinkedHashSet<>();
            for (String interfaceName : type.interfaces()) {
                TitanGraphqlModelPath interfacePath = path("types", type.name(), "interfaces", interfaceName);
                if (!names.add(interfaceName)) {
                    issue(TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                            "Type '" + type.name() + "' declares interface '" + interfaceName
                                    + "' more than once.", interfacePath);
                    continue;
                }
                TitanGraphqlInterfaceDocument interfaceType = interfaces.get(interfaceName);
                if (interfaceType == null) {
                    issue(TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                            "Type '" + type.name() + "' references unknown interface '"
                                    + interfaceName + "'.", interfacePath);
                    continue;
                }
                for (TitanGraphqlInterfaceDocument.InterfaceField required : interfaceType.fields()) {
                    TitanGraphqlFieldDocument field = typeIndex.byName(required.name());
                    if (field == null) {
                        issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                                "Type '" + type.name() + "' does not provide interface field '"
                                        + interfaceName + "." + required.name() + "'.", interfacePath);
                        continue;
                    }
                    String actualType = normalizeType(field.type()) + (field.nullable() ? "" : "!");
                    if (!actualType.equals(required.type())) {
                        issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                                "Type '" + type.name() + "' field '" + field.name() + "' has type '"
                                        + actualType + "' but interface '" + interfaceName
                                        + "' requires '" + required.type() + "'.", interfacePath);
                    }
                }
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
            for (String policy : type.policies()) {
                if (!policies.containsKey(policy)) {
                    issue(
                            TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                            "Type '" + type.name() + "' references unknown row policy '" + policy + "'.",
                            path("types", type.name(), "policies", policy)
                    );
                }
            }
            for (TitanGraphqlFieldDocument field : type.fields()) {
                String fieldType = normalizeType(field.type());
                if (!OUTPUT_SCALAR_TYPES.contains(fieldType) && !enums.containsKey(fieldType)) {
                    issue(
                            TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                            "Field '" + type.name() + "." + field.name()
                                    + "' references unknown output type '" + field.type() + "'.",
                            path("types", type.name(), "fields", field.name(), "type")
                    );
                }
                if (enums.containsKey(fieldType)) {
                    if (field.sort() != null) {
                        issue(
                                TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                                "Enum field '" + type.name() + "." + field.name()
                                        + "' cannot declare a sort capability yet.",
                                path("types", type.name(), "fields", field.name(), "sort")
                        );
                    }
                    for (String operator : field.filterOperators()) {
                        String normalized = operator == null ? ""
                                : operator.replace("_", "").toLowerCase(java.util.Locale.ROOT);
                        if (!Set.of("eq", "neq", "in", "isnull").contains(normalized)) {
                            issue(
                                    TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                                    "Enum field '" + type.name() + "." + field.name()
                                            + "' supports only eq, neq, in, and isNull filters.",
                                    path("types", type.name(), "fields", field.name(), "filter", operator)
                            );
                        }
                    }
                }
                if (!"ID".equals(normalizeType(field.type())) && field.idStorage() != null) {
                    issue(
                            TitanGraphqlValidationIssueCode.INVALID_BINDING,
                            "Field '" + type.name() + "." + field.name()
                                    + "' declares idStorage but is not an ID field.",
                            path("types", type.name(), "fields", field.name(), "idStorage")
                    );
                }
                if (!field.deprecated() && !field.deprecationReason().isEmpty()) {
                    issue(
                            TitanGraphqlValidationIssueCode.INVALID_BINDING,
                            "Field '" + type.name() + "." + field.name()
                                    + "' has a deprecation reason but is not deprecated.",
                            path("types", type.name(), "fields", field.name(), "deprecationReason")
                    );
                }
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
                if (relation.batchable() && !relation.selectable()) {
                    issue(
                            TitanGraphqlValidationIssueCode.INVALID_BINDING,
                            "Relation '" + type.name() + "." + relation.name()
                                    + "' cannot be batchable when it is not selectable.",
                            path("types", type.name(), "relations", relation.name(), "capabilities", "batchable")
                    );
                }
                TitanGraphqlTypeDocument targetType = types.get(relation.targetType());
                if (targetType == null) {
                    issue(
                            TitanGraphqlValidationIssueCode.UNKNOWN_REFERENCE,
                            "Relation '" + type.name() + "." + relation.name()
                                    + "' references unknown target type '" + relation.targetType() + "'.",
                            path("types", type.name(), "relations", relation.name())
                    );
                } else {
                    validateRelationArguments(type, relation, targetType);
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

        private void validateRelationArguments(
                TitanGraphqlTypeDocument sourceType,
                TitanGraphqlRelationDocument relation,
                TitanGraphqlTypeDocument targetType
        ) {
            TypeIndex targetIndex = new TypeIndex(targetType);
            Set<String> names = new LinkedHashSet<>();
            Set<String> equalityColumns = new LinkedHashSet<>();
            for (TitanGraphqlRelationDocument.RelationDocumentArgument argument : relation.arguments()) {
                TitanGraphqlModelPath argumentPath = path("types", sourceType.name(), "relations",
                        relation.name(), "arguments", argument.name());
                if (!names.add(argument.name())) {
                    issue(TitanGraphqlValidationIssueCode.DUPLICATE_NAME,
                            "Relation '" + sourceType.name() + "." + relation.name()
                                    + "' has duplicate argument name '" + argument.name() + "'.", argumentPath);
                }
                if (argument.kind() != TitanGraphqlRelationDocument.RelationDocumentArgumentKind.EQUALS) {
                    if (!argument.defaultValue().isEmpty()) {
                        issue(TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                                "Relation '" + sourceType.name() + "." + relation.name()
                                        + "' generated Relay argument '" + argument.name()
                                        + "' cannot declare a model default.", argumentPath);
                    }
                    continue;
                }
                if (argument.column().isBlank() || !equalityColumns.add(argument.column())) {
                    issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                            "Relation '" + sourceType.name() + "." + relation.name()
                                    + "' has a missing or duplicate equality column '" + argument.column() + "'.",
                            argumentPath);
                }
                if (argument.hops() != 0) {
                    issue(TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                            "Relation '" + sourceType.name() + "." + relation.name() + "' argument '"
                                    + argument.name() + "' must be a local equality binding.", argumentPath);
                }
                validateConnectionEqualityBinding("Relation '" + sourceType.name() + "." + relation.name() + "'",
                        argument.name(), argument.type(), argument.column(), targetIndex, targetType.name(),
                        argumentPath);
                validateArgumentDefault("Relation '" + sourceType.name() + "." + relation.name()
                                + "' argument '" + argument.name() + "'", argument.type(),
                        argument.defaultValue(), argumentPath);
            }
        }

        private void validateArgumentDefault(
                String owner,
                String typeReference,
                String defaultValue,
                TitanGraphqlModelPath argumentPath
        ) {
            if (defaultValue == null || defaultValue.isEmpty()) {
                return;
            }
            if (defaultValue.contains("$")) {
                issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                        owner + " default must be a constant GraphQL value.", argumentPath);
                return;
            }
            String defaultError = TitanGraphqlInputDefaultValidator.error(
                    document, typeReference, defaultValue);
            if (!defaultError.isEmpty()) {
                issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                        owner + " has an invalid default: " + defaultError + ".", argumentPath);
            }
        }

        private static String requiredInputType(String typeReference) {
            String normalized = typeReference == null ? "" : typeReference.trim();
            return normalized.endsWith("!") ? normalized : normalized + "!";
        }

        private void validateConnectionEqualityBinding(
                String owner,
                String argumentName,
                String argumentTypeReference,
                String column,
                TypeIndex typeIndex,
                String typeName,
                TitanGraphqlModelPath argumentPath
        ) {
            String argumentType = normalizeType(argumentTypeReference);
            boolean declaredEnum = enums.containsKey(argumentType);
            if (!Set.of("Int", "Long").contains(argumentType) && !declaredEnum) {
                issue(TitanGraphqlValidationIssueCode.UNSUPPORTED_CAPABILITY,
                        owner + " argument '" + argumentName
                                + "' must use Int, Long, or a declared enum.", argumentPath);
                return;
            }
            TitanGraphqlFieldDocument field = typeIndex.byColumn(column);
            if (field == null) {
                if (declaredEnum) {
                    issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                            owner + " enum argument '" + argumentName + "' column '" + column
                                    + "' must bind to a modeled stored scalar field on type '" + typeName + "'.",
                            argumentPath);
                }
                return;
            }
            if (field.computed() != null || !normalizeType(field.type()).equals(argumentType)) {
                issue(TitanGraphqlValidationIssueCode.INVALID_BINDING,
                        owner + " argument '" + argumentName + "' type '" + argumentTypeReference
                                + "' must match stored field '" + field.name() + "' on type '" + typeName + "'.",
                        argumentPath);
            }
        }

        private boolean hasUnbrokenInputCycle(
                String origin,
                String current,
                Set<String> active
        ) {
            if (!active.add(current)) {
                return current.equals(origin);
            }
            TitanGraphqlInputObjectDocument inputObject = inputObjects.get(current);
            if (inputObject != null) {
                for (TitanGraphqlInputObjectDocument.InputField field : inputObject.fields()) {
                    String type = validInputTypeReference(field.type());
                    if (!type.isEmpty() && type.endsWith("!") && !type.startsWith("[")) {
                        String target = inputNamedType(type);
                        if (target.equals(origin)
                                || inputObjects.containsKey(target)
                                && hasUnbrokenInputCycle(origin, target, new LinkedHashSet<>(active))) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private String validInputTypeReference(String value) {
            String source = value == null ? "" : value.trim();
            int[] position = {0};
            return parseInputTypeReference(source, position) && position[0] == source.length() ? source : "";
        }

        private String validOutputTypeReference(String value) {
            return validInputTypeReference(value);
        }

        private boolean isOutputType(String name) {
            return OUTPUT_SCALAR_TYPES.contains(name) || enums.containsKey(name) || types.containsKey(name)
                    || interfaces.containsKey(name) || unions.containsKey(name);
        }

        private boolean parseInputTypeReference(String source, int[] position) {
            if (position[0] >= source.length()) {
                return false;
            }
            if (source.charAt(position[0]) == '[') {
                position[0]++;
                if (!parseInputTypeReference(source, position)
                        || position[0] >= source.length() || source.charAt(position[0]) != ']') {
                    return false;
                }
                position[0]++;
            } else {
                int start = position[0];
                while (position[0] < source.length()) {
                    char current = source.charAt(position[0]);
                    if (!(current == '_' || Character.isLetterOrDigit(current))) {
                        break;
                    }
                    position[0]++;
                }
                if (position[0] == start || !graphqlName(source.substring(start, position[0]))) {
                    return false;
                }
            }
            if (position[0] < source.length() && source.charAt(position[0]) == '!') {
                position[0]++;
            }
            return true;
        }

        private String inputNamedType(String typeReference) {
            String value = typeReference == null ? "" : typeReference;
            int start = 0;
            while (start < value.length() && value.charAt(start) == '[') start++;
            int end = start;
            while (end < value.length()
                    && (value.charAt(end) == '_' || Character.isLetterOrDigit(value.charAt(end)))) {
                end++;
            }
            return end > start ? value.substring(start, end) : "";
        }

        private record MutationValidationBinding(
                String name,
                String type,
                String column,
                boolean key,
                boolean inputPath
        ) {
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

        private boolean graphqlName(String value) {
            if (value == null || value.isEmpty()
                    || !(value.charAt(0) == '_' || Character.isLetter(value.charAt(0)))) {
                return false;
            }
            int index = 1;
            while (index < value.length()) {
                char current = value.charAt(index);
                if (!(current == '_' || Character.isLetterOrDigit(current))) {
                    return false;
                }
                index++;
            }
            return true;
        }

        private boolean graphqlTypeName(String value) {
            return graphqlName(value) && Character.isUpperCase(value.charAt(0)) && !value.startsWith("__");
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

        TitanGraphqlFieldDocument byColumn(String column) {
            return fieldsByColumn.get(column);
        }

        TitanGraphqlFieldDocument byName(String name) {
            return fieldsByName.get(name);
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
