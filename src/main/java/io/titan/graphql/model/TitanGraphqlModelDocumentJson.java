package io.titan.graphql.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public final class TitanGraphqlModelDocumentJson {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private TitanGraphqlModelDocumentJson() {
    }

    public static String canonicalJson(TitanGraphqlModelDocument document) {
        try {
            return JSON.writeValueAsString(normalize(document));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("model document could not be serialized", ex);
        }
    }

    public static String semanticHash(TitanGraphqlModelDocument document) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson(document).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is not available", ex);
        }
    }

    private static TitanGraphqlModelDocument normalize(TitanGraphqlModelDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("document is required");
        }
        return new TitanGraphqlModelDocument(
                document.apiVersion(),
                document.kind(),
                normalizeMetadata(document.metadata()),
                normalizeDatabase(document.database()),
                sorted(document.modules(), Comparator.comparing(TitanGraphqlModuleDocument::name))
                        .stream()
                        .map(TitanGraphqlModelDocumentJson::normalizeModule)
                        .toList(),
                sorted(document.roots(), Comparator.comparing(TitanGraphqlRootDocument::name))
                        .stream()
                        .map(TitanGraphqlModelDocumentJson::normalizeRoot)
                        .toList(),
                sorted(document.types(), Comparator.comparing(TitanGraphqlTypeDocument::name))
                        .stream()
                        .map(TitanGraphqlModelDocumentJson::normalizeType)
                        .toList(),
                sorted(document.interfaces(), Comparator.comparing(TitanGraphqlInterfaceDocument::name))
                        .stream()
                        .map(TitanGraphqlModelDocumentJson::normalizeInterface)
                        .toList(),
                sorted(document.unions(), Comparator.comparing(TitanGraphqlUnionDocument::name))
                        .stream()
                        .map(TitanGraphqlModelDocumentJson::normalizeUnion)
                        .toList(),
                sorted(document.enums(), Comparator.comparing(TitanGraphqlEnumDocument::name))
                        .stream()
                        .map(TitanGraphqlModelDocumentJson::normalizeEnum)
                        .toList(),
                sorted(document.inputObjects(), Comparator.comparing(TitanGraphqlInputObjectDocument::name))
                        .stream()
                        .map(TitanGraphqlModelDocumentJson::normalizeInputObject)
                        .toList(),
                sorted(document.directives(), Comparator.comparing(TitanGraphqlDirectiveDocument::name))
                        .stream()
                        .map(TitanGraphqlModelDocumentJson::normalizeDirective)
                        .toList(),
                sorted(document.policies(), Comparator.comparing(TitanGraphqlPolicyDocument::name))
                        .stream()
                        .map(TitanGraphqlModelDocumentJson::normalizePolicy)
                        .toList(),
                sorted(document.mutations(), Comparator.comparing(TitanGraphqlMutationDocument::name))
                        .stream()
                        .map(TitanGraphqlModelDocumentJson::normalizeMutation)
                        .toList(),
                sorted(document.contextFilters(), Comparator.comparing(TitanGraphqlContextFilterDocument::name)),
                document.artifacts(),
                normalizeDeployment(document.deployment())
        );
    }

    private static TitanGraphqlDirectiveDocument normalizeDirective(
            TitanGraphqlDirectiveDocument directive
    ) {
        return new TitanGraphqlDirectiveDocument(
                directive.name(),
                directive.description(),
                sorted(directive.locations(), Comparator.comparing(Enum::name)),
                directive.behavior()
        );
    }

    private static TitanGraphqlInputObjectDocument normalizeInputObject(
            TitanGraphqlInputObjectDocument inputObject
    ) {
        return new TitanGraphqlInputObjectDocument(
                inputObject.name(),
                inputObject.description(),
                sorted(inputObject.fields(), Comparator.comparing(
                        TitanGraphqlInputObjectDocument.InputField::name))
        );
    }

    private static TitanGraphqlEnumDocument normalizeEnum(TitanGraphqlEnumDocument enumType) {
        return new TitanGraphqlEnumDocument(
                enumType.name(),
                sortedStrings(enumType.values()),
                sorted(enumType.valueMetadata(), Comparator.comparing(
                        TitanGraphqlEnumDocument.EnumValueMetadata::name))
        );
    }

    private static TitanGraphqlMutationDocument normalizeMutation(TitanGraphqlMutationDocument mutation) {
        return new TitanGraphqlMutationDocument(
                mutation.name(),
                mutation.operation(),
                mutation.type(),
                sorted(mutation.arguments(), Comparator.comparing(
                        TitanGraphqlMutationDocument.MutationDocumentArgument::name)),
                mutation.input(),
                sorted(mutation.inputBindings(), Comparator.comparing(
                        TitanGraphqlMutationDocument.MutationDocumentInputBinding::name)),
                sortedStrings(mutation.policies()),
                sorted(mutation.payload(), Comparator.comparing(
                        TitanGraphqlMutationDocument.MutationDocumentPayloadField::name))
        );
    }

    private static TitanGraphqlModelMetadata normalizeMetadata(TitanGraphqlModelMetadata metadata) {
        return new TitanGraphqlModelMetadata(
                metadata.name(),
                metadata.version(),
                metadata.owner(),
                metadata.description(),
                sortedStrings(metadata.tags())
        );
    }

    private static TitanGraphqlDatabaseDocument normalizeDatabase(TitanGraphqlDatabaseDocument database) {
        return new TitanGraphqlDatabaseDocument(
                database.catalog(),
                database.defaultSchema(),
                sorted(database.tables(), Comparator.comparing(TitanGraphqlDatabaseDocument.TableBinding::name))
        );
    }

    private static TitanGraphqlModuleDocument normalizeModule(TitanGraphqlModuleDocument module) {
        return new TitanGraphqlModuleDocument(
                module.name(),
                module.owner(),
                sortedStrings(module.roots()),
                sortedStrings(module.types()),
                sortedStrings(module.fields()),
                sortedStrings(module.relations()),
                sortedStrings(module.policies())
        );
    }

    private static TitanGraphqlRootDocument normalizeRoot(TitanGraphqlRootDocument root) {
        return new TitanGraphqlRootDocument(
                root.name(),
                root.type(),
                root.operation(),
                root.argument(),
                root.pagination(),
                sorted(root.arguments(), Comparator.comparing(TitanGraphqlRootDocument.RootDocumentArgument::name)),
                sorted(root.filterPaths(), Comparator.comparing(TitanGraphqlRootDocument.RootDocumentFilterPath::name))
                        .stream()
                        .map(TitanGraphqlModelDocumentJson::normalizeRootFilterPath)
                        .toList(),
                sorted(root.sortPaths(), Comparator.comparing(TitanGraphqlRootDocument.RootDocumentSortPath::name)),
                sortedStrings(root.contextFilters()),
                sortedStrings(root.policies()),
                root.outputType()
        );
    }

    private static TitanGraphqlRootDocument.RootDocumentFilterPath normalizeRootFilterPath(
            TitanGraphqlRootDocument.RootDocumentFilterPath filterPath
    ) {
        return new TitanGraphqlRootDocument.RootDocumentFilterPath(
                filterPath.name(),
                filterPath.type(),
                filterPath.column(),
                filterPath.path(),
                filterPath.hops(),
                sortedStrings(filterPath.operators())
        );
    }

    private static TitanGraphqlTypeDocument normalizeType(TitanGraphqlTypeDocument type) {
        return new TitanGraphqlTypeDocument(
                type.name(),
                type.table(),
                type.schema(),
                type.physicalTable(),
                type.primaryKey(),
                sorted(type.fields(), Comparator.comparing(TitanGraphqlFieldDocument::name))
                        .stream()
                        .map(TitanGraphqlModelDocumentJson::normalizeField)
                        .toList(),
                sorted(type.relations(), Comparator.comparing(TitanGraphqlRelationDocument::name))
                        .stream()
                        .map(TitanGraphqlModelDocumentJson::normalizeRelation)
                        .toList(),
                sortedStrings(type.policies()),
                type.description(),
                sortedStrings(type.interfaces())
        );
    }

    private static TitanGraphqlInterfaceDocument normalizeInterface(TitanGraphqlInterfaceDocument type) {
        return new TitanGraphqlInterfaceDocument(
                type.name(), type.description(),
                sorted(type.fields(), Comparator.comparing(TitanGraphqlInterfaceDocument.InterfaceField::name)));
    }

    private static TitanGraphqlUnionDocument normalizeUnion(TitanGraphqlUnionDocument type) {
        return new TitanGraphqlUnionDocument(type.name(), type.description(), sortedStrings(type.members()));
    }

    private static TitanGraphqlFieldDocument normalizeField(TitanGraphqlFieldDocument field) {
        return new TitanGraphqlFieldDocument(
                field.name(),
                field.type(),
                field.column(),
                field.nullable(),
                sortedStrings(field.policies()),
                sortedStrings(field.filterOperators()),
                field.sort(),
                normalizeComputed(field.computed()),
                field.idStorage(),
                field.description(),
                field.deprecated(),
                field.deprecationReason()
        );
    }

    private static TitanGraphqlFieldDocument.Computed normalizeComputed(TitanGraphqlFieldDocument.Computed computed) {
        if (computed == null) {
            return null;
        }
        return new TitanGraphqlFieldDocument.Computed(
                computed.expressionKind(),
                computed.sqlTemplate(),
                computed.selectable(),
                computed.filterable(),
                computed.sortable(),
                computed.deterministic(),
                computed.sensitive(),
                sortedStrings(computed.requiredColumns()),
                computed.costClass()
        );
    }

    private static TitanGraphqlRelationDocument normalizeRelation(TitanGraphqlRelationDocument relation) {
        return new TitanGraphqlRelationDocument(
                relation.name(),
                relation.targetType(),
                relation.localColumn(),
                relation.targetColumn(),
                relation.cardinality(),
                relation.nullable(),
                relation.pagination(),
                sorted(relation.arguments(), Comparator.comparing(TitanGraphqlRelationDocument.RelationDocumentArgument::name)),
                sorted(relation.sortPaths(), Comparator.comparing(TitanGraphqlRelationDocument.RelationDocumentSortPath::name)),
                sortedStrings(relation.policies()),
                relation.selectionHopBudget(),
                relation.selectable(),
                relation.batchable()
        );
    }

    private static TitanGraphqlPolicyDocument normalizePolicy(TitanGraphqlPolicyDocument policy) {
        return new TitanGraphqlPolicyDocument(
                policy.name(),
                policy.description(),
                policy.effect(),
                sortedStrings(policy.appliesTo()),
                policy.expression()
        );
    }

    private static TitanGraphqlDeploymentDocument normalizeDeployment(TitanGraphqlDeploymentDocument deployment) {
        return new TitanGraphqlDeploymentDocument(
                deployment.environment(),
                deployment.previewEndpoint(),
                deployment.runtimeEndpoint(),
                sortedStrings(deployment.requiredApprovals())
        );
    }

    private static List<String> sortedStrings(List<String> values) {
        return sorted(values, Comparator.naturalOrder());
    }

    private static <T> List<T> sorted(List<T> values, Comparator<T> comparator) {
        return values.stream().sorted(comparator).toList();
    }
}
