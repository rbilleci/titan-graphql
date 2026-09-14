package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.artifact.TitanGraphqlArtifactKind;
import io.titan.graphql.artifact.TitanGraphqlGeneratedArtifact;
import io.titan.graphql.artifact.TitanGraphqlGeneratedArtifactSet;
import io.titan.graphql.artifact.TitanGraphqlIntrospectionArtifactPolicy;
import io.titan.graphql.management.TitanGraphqlManagementProjection;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import io.titan.graphql.validation.TitanGraphqlModelDocumentValidator;
import io.titan.graphql.validation.TitanGraphqlValidationReport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TitanGraphqlManagementModelDogfoodTest {

    @Test
    void parsesManagementModelFixtureIntoCanonicalIr() throws IOException {
        TitanGraphqlModelDocument document = managementDocument();

        assertEquals("titan-graphql-management", document.metadata().name());
        assertEquals("2026.06.01", document.metadata().version());
        assertEquals(1, document.modules().size());
        assertEquals(13, document.roots().size());
        assertEquals(11, document.types().size());
        assertEquals("titan_graphql_management", document.database().defaultSchema());
        assertTrue(document.artifacts().generateSdl());
        assertTrue(document.artifacts().generateIntrospection());
        assertTrue(document.artifacts().generateConformance());
        assertFalse(document.artifacts().generateSql());
        assertEquals("build/generated/titan-graphql/management", document.artifacts().outputDirectory());
        assertEquals("/admin/graphql", document.deployment().runtimeEndpoint());
        assertFalse(TitanGraphqlModelDocumentJson.semanticHash(document).isBlank());

        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);
        assertTrue(report.valid());
        assertTrue(report.issues().isEmpty());
    }

    @Test
    void adaptsManagementModelFixtureToCurrentManagementSchemaSurface() throws IOException {
        TitanGraphqlModelDocument document = managementDocument();
        ProjectionModel yamlBackedModel = TitanGraphqlProjectionModelAdapter.adapt(document, new GraphqlPolicy());
        TitanGraphqlProjectionModel javaBackedModel = TitanGraphqlManagementProjection.projectionModel();
        ProjectionModel javaProjectionModel = javaBackedModel.toProjectionModel();
        String yamlBackedSchema = GraphqlSchemaPrinter.print(ProjectionGraphqlAdapter.adapt(yamlBackedModel));

        assertEquals(javaBackedModel.rootNames(), yamlBackedModel.retrievals().stream()
                .map(ProjectionRetrieval::name)
                .toList());
        assertEquals(javaBackedModel.typeNames(), yamlBackedModel.types().stream()
                .map(ProjectionType::name)
                .toList());
        assertEquals(typeShapes(javaProjectionModel), typeShapes(yamlBackedModel));
        assertTrue(yamlBackedSchema.contains("workspaces(first: Int, after: String, last: Int, before: String"));
        assertTrue(yamlBackedSchema.contains("modelDraft(id: Int!): ModelDraft"));
        assertTrue(yamlBackedSchema.contains("type ArtifactSet {"));
        assertTrue(yamlBackedSchema.contains("  conformanceHash: String"));
        assertTrue(yamlBackedSchema.contains("type ObservedOperation {"));
        assertTrue(yamlBackedSchema.contains("type UsageReport {"));
    }

    @Test
    void generatesManagementSdlAndConformanceArtifactsFromModelDocument() throws IOException {
        TitanGraphqlGeneratedArtifactSet generated = TitanGraphqlGeneratedArtifactWorkflow.generateFromModelDocument(
                "artifact-set-management-001",
                "draft-management-001",
                managementDocument(),
                TitanGraphqlIntrospectionArtifactPolicy.ENABLED,
                "management-dogfood",
                "2026-06-01T17:47:00Z"
        );

        TitanGraphqlGeneratedArtifact sdl = generated.artifact(TitanGraphqlArtifactKind.GENERATED_SCHEMA_SDL);
        TitanGraphqlGeneratedArtifact introspection = generated.artifact(TitanGraphqlArtifactKind.INTROSPECTION_JSON);
        TitanGraphqlGeneratedArtifact conformance = generated.artifact(TitanGraphqlArtifactKind.CONFORMANCE_MATRIX);
        TitanGraphqlGeneratedArtifact generatedSql = generated.artifact(TitanGraphqlArtifactKind.GENERATED_SQL);

        assertNotNull(sdl);
        assertNotNull(introspection);
        assertNotNull(conformance);
        assertNull(generatedSql);
        assertEquals("build/generated/titan-graphql/management/schema.graphql", sdl.path());
        assertEquals("build/generated/titan-graphql/management/introspection.json", introspection.path());
        assertEquals("build/generated/titan-graphql/management/conformance.json", conformance.path());
        assertTrue(sdl.content().contains("type Query {"));
        assertTrue(sdl.content().contains("  workspaces(first: Int, after: String, last: Int, before: String"));
        assertTrue(sdl.content().contains("type ManagedGraphqlModel {"));
        assertTrue(introspection.content().contains("\"name\":\"ManagedWorkspace\""));
        assertTrue(conformance.content().contains("\"generationProfile\":\"management-dogfood\""));
        assertTrue(conformance.content().contains("\"profileId\":\"query-contract\""));
        assertEquals("titan-graphql-management", generated.manifest().modelName());
        assertEquals(sdl.hash(), generated.manifest().sdlHash());
        assertEquals(introspection.hash(), generated.manifest().introspectionHash());
        assertEquals(conformance.hash(), generated.manifest().conformanceHash());
        assertEquals("", generated.manifest().generatedSqlHash());
        assertFalse(generated.manifest().artifacts().get(3).enabled());
    }

    private static TitanGraphqlModelDocument managementDocument() throws IOException {
        return TitanGraphqlModelDocumentYaml.parse(readManagementFixture());
    }

    private static String readManagementFixture() throws IOException {
        try (InputStream stream = TitanGraphqlManagementModelDogfoodTest.class
                .getResourceAsStream("/graphql/management.titan.graphql.yaml")) {
            if (stream == null) {
                throw new IllegalStateException("management model fixture is missing");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> typeShapes(ProjectionModel model) {
        return model.types().stream()
                .map(type -> type.name() + ":" + type.fields().stream()
                        .map(field -> field.name() + "=" + field.graphqlType())
                        .toList())
                .toList();
    }
}
