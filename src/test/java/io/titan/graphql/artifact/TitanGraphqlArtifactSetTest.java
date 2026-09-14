package io.titan.graphql.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.model.TitanGraphqlArtifactOptions;
import io.titan.graphql.model.TitanGraphqlFieldDocument;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelMetadata;
import io.titan.graphql.model.TitanGraphqlRootDocument;
import io.titan.graphql.model.TitanGraphqlTypeDocument;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TitanGraphqlArtifactSetTest {

    @Test
    void createsManifestFromModelDocumentWithRequiredIdentityAndHashes() {
        TitanGraphqlModelDocument document = modelDocument(new TitanGraphqlArtifactOptions(
                true,
                true,
                true,
                true,
                "build/review/graphql"
        ));
        TitanGraphqlArtifactHashes hashes = new TitanGraphqlArtifactHashes(
                "validation-sha",
                "drift-sha",
                "sdl-sha",
                "introspection-sha",
                "conformance-sha",
                "sql-sha"
        );

        TitanGraphqlArtifactSet artifactSet = TitanGraphqlArtifactSet.fromModelDocument(
                "artifact-set-001",
                "draft-001",
                document,
                hashes,
                "postgres-demo",
                "2026-06-01T11:18:00Z"
        );

        assertEquals("artifact-set-001", artifactSet.id());
        assertEquals("draft-001", artifactSet.draftId());
        assertEquals("demo-blog", artifactSet.modelName());
        assertEquals("2026.06.01", artifactSet.modelVersion());
        assertEquals(TitanGraphqlModelDocumentJson.semanticHash(document), artifactSet.semanticHash());
        assertEquals("validation-sha", artifactSet.validationHash());
        assertEquals("drift-sha", artifactSet.driftHash());
        assertEquals("sdl-sha", artifactSet.sdlHash());
        assertEquals("introspection-sha", artifactSet.introspectionHash());
        assertEquals("conformance-sha", artifactSet.conformanceHash());
        assertEquals("sql-sha", artifactSet.generatedSqlHash());
        assertEquals("build/review/graphql", artifactSet.outputDirectory());
        assertEquals("postgres-demo", artifactSet.generationProfile());
        assertEquals("2026-06-01T11:18:00Z", artifactSet.createdAt());

        assertEquals(4, artifactSet.artifacts().size());
        assertEquals(TitanGraphqlArtifactKind.GENERATED_SCHEMA_SDL, artifactSet.artifacts().get(0).kind());
        assertEquals("build/review/graphql/schema.graphql", artifactSet.artifacts().get(0).path());
        assertEquals("sdl-sha", artifactSet.artifacts().get(0).hash());
    }

    @Test
    void rendersDeterministicManifestJsonWithStableArtifactOrdering() {
        TitanGraphqlArtifactSet artifactSet = new TitanGraphqlArtifactSet(
                "artifact-set-002",
                "draft-002",
                "demo-blog",
                "2026.06.01",
                "semantic-sha",
                "validation-sha",
                "drift-sha",
                "sdl-sha",
                "introspection-sha",
                "conformance-sha",
                "sql-sha",
                "build/review/graphql",
                "postgres-demo",
                "2026-06-01T11:19:00Z",
                List.of(
                        new TitanGraphqlArtifactManifestEntry(
                                TitanGraphqlArtifactKind.GENERATED_SQL,
                                "generatedSql",
                                true,
                                "build/review/graphql/generated.sql",
                                "sql-sha"
                        ),
                        new TitanGraphqlArtifactManifestEntry(
                                TitanGraphqlArtifactKind.GENERATED_SCHEMA_SDL,
                                "generatedSchemaSdl",
                                true,
                                "build/review/graphql/schema.graphql",
                                "sdl-sha"
                        ),
                        new TitanGraphqlArtifactManifestEntry(
                                TitanGraphqlArtifactKind.CONFORMANCE_MATRIX,
                                "conformanceMatrix",
                                true,
                                "build/review/graphql/conformance.json",
                                "conformance-sha"
                        )
                )
        );

        String json = TitanGraphqlArtifactManifestJson.manifestJson(artifactSet);

        assertEquals(json, TitanGraphqlArtifactManifestJson.manifestJson(artifactSet));
        assertTrue(json.startsWith("{\"artifacts\":[{\"enabled\":true,\"hash\":\"conformance-sha\""));
        assertTrue(json.contains("\"draftId\":\"draft-002\""));
        assertTrue(json.contains("\"modelName\":\"demo-blog\""));
        assertTrue(json.contains("\"semanticHash\":\"semantic-sha\""));
        assertTrue(json.indexOf("\"name\":\"conformanceMatrix\"")
                < json.indexOf("\"name\":\"generatedSchemaSdl\""));
        assertTrue(json.indexOf("\"name\":\"generatedSchemaSdl\"")
                < json.indexOf("\"name\":\"generatedSql\""));
        assertTrue(json.contains("\"validationHash\":\"validation-sha\""));
        assertTrue(json.contains("\"driftHash\":\"drift-sha\""));
    }

    @Test
    void preservesDisabledArtifactSlotsWithoutGeneratingBodies() {
        TitanGraphqlModelDocument document = modelDocument(new TitanGraphqlArtifactOptions(
                true,
                false,
                true,
                false,
                "build/generated/titan-graphql"
        ));

        TitanGraphqlArtifactSet artifactSet = TitanGraphqlArtifactSet.fromModelDocument(
                "artifact-set-003",
                "draft-003",
                document,
                new TitanGraphqlArtifactHashes("validation-sha", "drift-sha", "sdl-sha", "", "conformance-sha", ""),
                "postgres-demo",
                ""
        );

        assertEquals(4, artifactSet.artifacts().size());
        TitanGraphqlArtifactManifestEntry introspection = artifactSet.artifacts().get(1);
        TitanGraphqlArtifactManifestEntry generatedSql = artifactSet.artifacts().get(3);
        assertEquals(TitanGraphqlArtifactKind.INTROSPECTION_JSON, introspection.kind());
        assertEquals("", introspection.hash());
        assertFalse(introspection.enabled());
        assertEquals(TitanGraphqlArtifactKind.GENERATED_SQL, generatedSql.kind());
        assertEquals("", generatedSql.hash());
        assertFalse(generatedSql.enabled());
    }

    @Test
    void rejectsMissingRequiredManifestIdentity() {
        TitanGraphqlModelDocument document = modelDocument(TitanGraphqlArtifactOptions.defaults());

        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlArtifactSet.fromModelDocument(
                "",
                "draft-004",
                document,
                TitanGraphqlArtifactHashes.empty(),
                "",
                ""
        ));
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlArtifactSet.fromModelDocument(
                "artifact-set-004",
                "",
                document,
                TitanGraphqlArtifactHashes.empty(),
                "",
                ""
        ));
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlArtifactSet.fromModelDocument(
                "artifact-set-004",
                "draft-004",
                null,
                TitanGraphqlArtifactHashes.empty(),
                "",
                ""
        ));
    }

    private static TitanGraphqlModelDocument modelDocument(TitanGraphqlArtifactOptions artifactOptions) {
        return new TitanGraphqlModelDocument(
                TitanGraphqlModelDocument.CURRENT_API_VERSION,
                TitanGraphqlModelDocument.PROJECTION_MODEL_KIND,
                new TitanGraphqlModelMetadata(
                        "demo-blog",
                        "2026.06.01",
                        "platform",
                        "Demo blog model.",
                        List.of("demo")
                ),
                null,
                List.of(),
                List.of(TitanGraphqlRootDocument.point(
                        "article",
                        "Article",
                        TitanGraphqlRootDocument.RootDocumentArgument.equals("id", "Int", "id")
                )),
                List.of(new TitanGraphqlTypeDocument(
                        "Article",
                        "articles",
                        "public",
                        "articles",
                        "id",
                        List.of(TitanGraphqlFieldDocument.column("id", "Int", "id", List.of(), null)),
                        List.of()
                )),
                List.of(),
                List.of(),
                artifactOptions,
                null
        );
    }
}
