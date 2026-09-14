package io.titan.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.titan.graphql.artifact.TitanGraphqlArtifactKind;
import io.titan.graphql.artifact.TitanGraphqlArtifactManifestEntry;
import io.titan.graphql.artifact.TitanGraphqlArtifactSet;
import io.titan.graphql.artifact.TitanGraphqlArtifactsDirectory;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.graphql.artifact.TitanGraphqlGeneratedArtifact;
import io.titan.graphql.artifact.TitanGraphqlGeneratedArtifactSet;
import io.titan.graphql.artifact.TitanGraphqlIntrospectionArtifactPolicy;
import io.titan.graphql.artifact.TitanGraphqlPackageBinding;
import io.titan.graphql.model.TitanGraphqlArtifactOptions;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class TitanGraphqlGeneratedArtifactWorkflow {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private TitanGraphqlGeneratedArtifactWorkflow() {
    }

    /**
     * Overload without GAP-005 package metadata — valid only for documents that do not
     * request generated SQL artifacts. Callers of SQL-producing documents must supply the
     * metadata read from the real {@code titanPackage} output (see
     * {@link TitanGraphqlArtifactsDirectory#readGap005Metadata()}); there is no placeholder.
     */
    public static TitanGraphqlGeneratedArtifactSet generateFromModelDocument(
            String artifactSetId,
            String draftId,
            TitanGraphqlModelDocument document,
            TitanGraphqlIntrospectionArtifactPolicy introspectionPolicy,
            String generationProfile,
            String createdAt
    ) {
        return generateFromModelDocument(
                artifactSetId,
                draftId,
                document,
                introspectionPolicy,
                generationProfile,
                createdAt,
                null
        );
    }

    public static TitanGraphqlGeneratedArtifactSet generateFromModelDocument(
            String artifactSetId,
            String draftId,
            TitanGraphqlModelDocument document,
            TitanGraphqlIntrospectionArtifactPolicy introspectionPolicy,
            String generationProfile,
            String createdAt,
            TitanGraphqlGap005ArtifactMetadata gap005Metadata
    ) {
        return generateFromModelDocument(
                artifactSetId,
                draftId,
                document,
                introspectionPolicy,
                generationProfile,
                createdAt,
                gap005Metadata,
                null
        );
    }

    public static TitanGraphqlGeneratedArtifactSet generateFromModelDocument(
            String artifactSetId,
            String draftId,
            TitanGraphqlModelDocument document,
            TitanGraphqlIntrospectionArtifactPolicy introspectionPolicy,
            String generationProfile,
            String createdAt,
            TitanGraphqlGap005ArtifactMetadata gap005Metadata,
            TitanGraphqlPackageBinding packageBinding
    ) {
        if (document == null) {
            throw new IllegalArgumentException("model document is required");
        }
        GraphqlSchema schema = ProjectionGraphqlAdapter.adapt(
                TitanGraphqlProjectionModelAdapter.adapt(document, new GraphqlPolicy())
        );
        TitanGraphqlArtifactOptions options = document.artifacts();
        List<TitanGraphqlGeneratedArtifact> artifacts = new ArrayList<>();
        List<TitanGraphqlArtifactManifestEntry> manifestEntries = new ArrayList<>();
        String sdlHash = "";
        String introspectionHash = "";
        String conformanceHash = "";
        String generatedSqlHash = "";

        if (options.generateSdl()) {
            String sdl = GraphqlSchemaPrinter.print(schema);
            sdlHash = sha256(sdl);
            artifacts.add(new TitanGraphqlGeneratedArtifact(
                    TitanGraphqlArtifactKind.GENERATED_SCHEMA_SDL,
                    artifactPath(options.outputDirectory(), TitanGraphqlArtifactKind.GENERATED_SCHEMA_SDL),
                    sdl,
                    sdlHash
            ));
        }
        manifestEntries.add(TitanGraphqlArtifactManifestEntry.of(
                TitanGraphqlArtifactKind.GENERATED_SCHEMA_SDL,
                options.generateSdl(),
                options.outputDirectory(),
                sdlHash
        ));

        boolean generateIntrospection = options.generateIntrospection()
                && introspectionPolicy == TitanGraphqlIntrospectionArtifactPolicy.ENABLED;
        if (generateIntrospection) {
            String introspection = introspectionArtifactJson(schema);
            introspectionHash = sha256(introspection);
            artifacts.add(new TitanGraphqlGeneratedArtifact(
                    TitanGraphqlArtifactKind.INTROSPECTION_JSON,
                    artifactPath(options.outputDirectory(), TitanGraphqlArtifactKind.INTROSPECTION_JSON),
                    introspection,
                    introspectionHash
            ));
        }
        manifestEntries.add(TitanGraphqlArtifactManifestEntry.of(
                TitanGraphqlArtifactKind.INTROSPECTION_JSON,
                generateIntrospection,
                options.outputDirectory(),
                introspectionHash
        ));

        if (options.generateConformance()) {
            String conformance = conformanceArtifactJson(generationProfile);
            conformanceHash = sha256(conformance);
            artifacts.add(new TitanGraphqlGeneratedArtifact(
                    TitanGraphqlArtifactKind.CONFORMANCE_MATRIX,
                    artifactPath(options.outputDirectory(), TitanGraphqlArtifactKind.CONFORMANCE_MATRIX),
                    conformance,
                    conformanceHash
            ));
        }
        manifestEntries.add(TitanGraphqlArtifactManifestEntry.of(
                TitanGraphqlArtifactKind.CONFORMANCE_MATRIX,
                options.generateConformance(),
                options.outputDirectory(),
                conformanceHash
        ));

        if (options.generateSql()) {
            if (gap005Metadata == null) {
                // The former 'metadataOnly' placeholder branch is deleted: SQL artifact truth
                // comes only from the real titanPackage output. A missing package is an
                // explicit error state, never a silent placeholder.
                throw new IllegalStateException(
                        "model '" + document.metadata().name() + "' requests generated SQL artifacts but no Titan"
                                + " GAP-005 package metadata is available — run titanPackage (and titanVerifyInstall)"
                                + " first, or point -D" + TitanGraphqlArtifactsDirectory.SYSTEM_PROPERTY
                                + " / " + TitanGraphqlArtifactsDirectory.ENVIRONMENT_VARIABLE
                                + " at a Titan package directory");
            }
            if (packageBinding == null) {
                throw new IllegalStateException(
                        "model '" + document.metadata().name() + "' requests generated SQL artifacts but the Titan"
                                + " package has no exact model binding — run titanGraphqlBindPackage for this reviewed"
                                + " model before attaching SQL artifacts");
            }
            packageBinding.verify(document, gap005Metadata);
            generatedSqlHash = gap005Metadata.manifestContentHash();
            artifacts.addAll(gap005Metadata.artifacts());
            manifestEntries.addAll(gap005Metadata.manifestEntries());
        } else {
            manifestEntries.add(TitanGraphqlArtifactManifestEntry.of(
                    TitanGraphqlArtifactKind.GENERATED_SQL,
                    false,
                    options.outputDirectory(),
                    ""
            ));
        }

        TitanGraphqlArtifactSet manifest = new TitanGraphqlArtifactSet(
                artifactSetId,
                draftId,
                document.metadata().name(),
                document.metadata().version(),
                TitanGraphqlModelDocumentJson.semanticHash(document),
                "",
                "",
                sdlHash,
                introspectionHash,
                conformanceHash,
                generatedSqlHash,
                options.outputDirectory(),
                generationProfile,
                createdAt,
                manifestEntries
        );
        return new TitanGraphqlGeneratedArtifactSet(manifest, artifacts);
    }

    private static String conformanceArtifactJson(String generationProfile) {
        return conformanceArtifactJson(generationProfile, readConformanceMatrixMarkdown());
    }

    static String conformanceArtifactJson(String generationProfile, String markdown) {
        ConformanceProfile profile = ConformanceProfile.fromMarkdown(markdown);
        if (isProductionProfile(generationProfile)) {
            profile.assertProductionReady();
        }
        try {
            return JSON.writeValueAsString(new ConformanceArtifact(
                    "query-contract",
                    generationProfile,
                    "docs/query-contract-conformance.md",
                    profile.classificationCounts(),
                    profile.rows()
            ));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("conformance artifact could not be serialized", ex);
        }
    }

    private static String readConformanceMatrixMarkdown() {
        try {
            return Files.readString(Path.of("docs/query-contract-conformance.md"), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalArgumentException("conformance matrix could not be read", ex);
        }
    }

    private static boolean isProductionProfile(String generationProfile) {
        String profile = generationProfile == null ? "" : generationProfile.trim().toLowerCase();
        return profile.equals("production") || profile.equals("prod");
    }

    private static String introspectionArtifactJson(GraphqlSchema schema) {
        try {
            return JSON.writeValueAsString(new IntrospectionArtifact(
                    responseData(schema, schemaIntrospectionQuery()).get("__schema"),
                    typeNames(schema).stream()
                            .map(typeName -> new TypeIntrospectionArtifact(
                                    typeName,
                                    responseData(schema, typeIntrospectionQuery(typeName)).get("__type")
                            ))
                            .toList()
            ));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("introspection artifact could not be serialized", ex);
        }
    }

    private static JsonNode responseData(GraphqlSchema schema, String query) {
        try {
            GraphqlAst.AstOperation operation = GraphqlParser.parse(GraphqlRequest.query(query));
            String json = GraphqlIntrospection.execute(schema, operation).json();
            JsonNode data = JSON.readTree(json).get("data");
            if (data == null) {
                throw new IllegalArgumentException("introspection query did not return data");
            }
            return data;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("introspection artifact response could not be parsed", ex);
        }
    }

    private static String schemaIntrospectionQuery() {
        return """
                {
                  __schema {
                    queryType { name kind }
                    types { name kind }
                    directives {
                      name
                      isRepeatable
                      locations
                      args { name type { name kind ofType { name kind ofType { name kind } } } }
                    }
                  }
                }
                """;
    }

    private static String typeIntrospectionQuery(String typeName) {
        String escapedTypeName = typeName.replace("\\", "\\\\").replace("\"", "\\\"");
        return """
                {
                  __type(name: "%s") {
                    name
                    kind
                    description
                    fields {
                      name
                      description
                      isDeprecated
                      deprecationReason
                      type { name kind ofType { name kind ofType { name kind } } }
                      args {
                        name
                        description
                        defaultValue
                        isDeprecated
                        deprecationReason
                        type { name kind ofType { name kind ofType { name kind } } }
                      }
                    }
                    inputFields {
                      name
                      description
                      defaultValue
                      isDeprecated
                      deprecationReason
                      type { name kind ofType { name kind ofType { name kind } } }
                    }
                    enumValues {
                      name
                      description
                      isDeprecated
                      deprecationReason
                    }
                  }
                }
                """.formatted(escapedTypeName);
    }

    private static List<String> typeNames(GraphqlSchema schema) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add("Query");
        for (GraphqlObjectType type : schema.types()) {
            names.add(type.name());
        }
        names.add("Boolean");
        for (GraphqlObjectType type : schema.types()) {
            for (GraphqlFieldDescriptor field : type.fields()) {
                if (field.kind() == GraphqlFieldDescriptor.FieldKind.SCALAR) {
                    names.add(field.graphqlType());
                }
            }
        }
        for (GraphqlRootField rootField : schema.rootFields()) {
            if (rootField.rootPaginationMode() == GraphqlRootField.RootPaginationMode.RELAY_CONNECTION) {
                names.add(rootField.typeName() + "Connection");
                names.add(rootField.typeName() + "Edge");
            }
        }
        boolean hasConnection = false;
        for (GraphqlObjectType type : schema.types()) {
            for (GraphqlFieldDescriptor field : type.fields()) {
                if (field.kind() == GraphqlFieldDescriptor.FieldKind.RELATION
                        && field.relationCapabilities().paginationMode()
                        == GraphqlFieldDescriptor.RelationPaginationMode.RELAY_CONNECTION) {
                    names.add(field.targetTypeName() + "Connection");
                    names.add(field.targetTypeName() + "Edge");
                    hasConnection = true;
                }
            }
        }
        if (hasConnection || hasRelayRoot(schema)) {
            names.add("PageInfo");
        }
        for (GraphqlRootField rootField : schema.rootFields()) {
            if (rootField.retrievalCapabilities().supportsFilterArguments()) {
                names.add(rootField.typeName() + "Filter");
                for (GraphqlRootArgumentDescriptor argument : rootField.filterArguments()) {
                    names.add(scalarFilterType(argument.name(), argument.columnName()));
                }
                for (GraphqlObjectType type : schema.types()) {
                    if (type.name().equals(rootField.typeName())) {
                        for (GraphqlFieldDescriptor field : type.fields()) {
                            if (field.kind() == GraphqlFieldDescriptor.FieldKind.SCALAR
                                    && field.scalarFilterCapabilities().operators().isEmpty() == false) {
                                names.add(field.graphqlType() + "Filter");
                            }
                        }
                    }
                }
                for (GraphqlRootField.RootFieldFilterPath filterPath : rootField.filterPaths()) {
                    names.add(filterPath.scalarType() + "Filter");
                }
            }
            if (rootField.sortPaths().isEmpty() == false) {
                names.add("SortDirection");
                names.add(rootField.typeName() + "OrderBy");
            }
        }
        return List.copyOf(names);
    }

    private static boolean hasRelayRoot(GraphqlSchema schema) {
        for (GraphqlRootField rootField : schema.rootFields()) {
            if (rootField.rootPaginationMode() == GraphqlRootField.RootPaginationMode.RELAY_CONNECTION) {
                return true;
            }
        }
        return false;
    }

    private static String scalarFilterType(String fieldName, String columnName) {
        String normalizedField = fieldName.toLowerCase();
        String normalizedColumn = columnName.toLowerCase();
        if (normalizedField.equals("id")
                || normalizedField.endsWith("id")
                || normalizedColumn.equals("id")
                || normalizedColumn.endsWith("_id")) {
            return "IntFilter";
        }
        return "StringFilter";
    }

    private static String artifactPath(String outputDirectory, TitanGraphqlArtifactKind kind) {
        return TitanGraphqlArtifactManifestEntry.of(kind, true, outputDirectory, "").path();
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private record IntrospectionArtifact(JsonNode schema, List<TypeIntrospectionArtifact> types) {
    }

    private record TypeIntrospectionArtifact(String name, JsonNode introspection) {
    }

    private record ConformanceArtifact(
            String profileId,
            String generationProfile,
            String source,
            Map<String, Integer> classificationCounts,
            List<ConformanceRow> rows
    ) {
    }

    private record ConformanceProfile(List<ConformanceRow> rows) {

        static ConformanceProfile fromMarkdown(String markdown) {
            List<ConformanceRow> rows = new ArrayList<>();
            for (String line : markdown.split("\\R")) {
                if (line.startsWith("| QC") || line.startsWith("| OOS")) {
                    String[] cells = line.split("\\|", -1);
                    if (cells.length >= 6) {
                        rows.add(new ConformanceRow(
                                cells[1].trim(),
                                cells[2].trim(),
                                cells[3].trim(),
                                cells[4].trim(),
                                cells[5].trim()
                        ));
                    }
                }
            }
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("conformance matrix has no rows");
            }
            return new ConformanceProfile(rows);
        }

        Map<String, Integer> classificationCounts() {
            Map<String, Integer> counts = new TreeMap<>();
            for (ConformanceRow row : rows) {
                counts.merge(row.classification(), 1, Integer::sum);
            }
            return counts;
        }

        void assertProductionReady() {
            List<ConformanceRow> unexpected = rows.stream()
                    .filter(row -> row.classification().equals("PENDING") || row.classification().equals("JAVA_ONLY"))
                    .toList();
            if (unexpected.isEmpty() == false) {
                throw new IllegalArgumentException(
                        "production conformance profile contains unexpected rows: "
                                + unexpected.stream()
                                .map(row -> row.id() + "=" + row.classification())
                                .toList()
                );
            }
        }
    }

    private record ConformanceRow(
            String id,
            String area,
            String behavior,
            String classification,
            String currentEvidence
    ) {
    }

}
