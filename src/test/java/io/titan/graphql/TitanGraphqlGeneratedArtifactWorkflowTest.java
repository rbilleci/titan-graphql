package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.artifact.TitanGraphqlArtifactKind;
import io.titan.graphql.artifact.TitanGraphqlEntryPointRef;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.graphql.artifact.TitanGraphqlGeneratedArtifact;
import io.titan.graphql.artifact.TitanGraphqlGeneratedArtifactSet;
import io.titan.graphql.artifact.TitanGraphqlIntrospectionArtifactPolicy;
import io.titan.graphql.artifact.TitanGraphqlRollbackScriptRef;
import io.titan.graphql.artifact.TitanGraphqlSqlRoutineRef;
import io.titan.graphql.model.TitanGraphqlArtifactOptions;
import io.titan.graphql.model.TitanGraphqlFieldDocument;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelMetadata;
import io.titan.graphql.model.TitanGraphqlRootDocument;
import io.titan.graphql.model.TitanGraphqlTypeDocument;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class TitanGraphqlGeneratedArtifactWorkflowTest {

    @Test
    void generatesSdlAndPolicyEnabledIntrospectionArtifactsFromModelDocument() {
        TitanGraphqlModelDocument document = modelDocument(List.of(
                TitanGraphqlFieldDocument.column("id", "Int", "id", List.of(), null),
                TitanGraphqlFieldDocument.column("title", "String", "title", List.of(), null)
        ), sqlFreeArtifactOptions());

        TitanGraphqlGeneratedArtifactSet generated = TitanGraphqlGeneratedArtifactWorkflow.generateFromModelDocument(
                "artifact-set-001",
                "draft-001",
                document,
                TitanGraphqlIntrospectionArtifactPolicy.ENABLED,
                "postgres-demo",
                "2026-06-01T11:31:00Z"
        );

        TitanGraphqlGeneratedArtifact sdl = generated.artifact(TitanGraphqlArtifactKind.GENERATED_SCHEMA_SDL);
        TitanGraphqlGeneratedArtifact introspection = generated.artifact(TitanGraphqlArtifactKind.INTROSPECTION_JSON);
        TitanGraphqlGeneratedArtifact conformance = generated.artifact(TitanGraphqlArtifactKind.CONFORMANCE_MATRIX);

        assertNotNull(sdl);
        assertNotNull(introspection);
        assertNotNull(conformance);
        assertNull(generated.artifact(TitanGraphqlArtifactKind.GENERATED_SQL));
        assertEquals("build/generated/titan-graphql/schema.graphql", sdl.path());
        assertEquals("build/generated/titan-graphql/introspection.json", introspection.path());
        assertEquals("build/generated/titan-graphql/conformance.json", conformance.path());
        assertTrue(sdl.content().contains("type Query {\n  article(id: Int!): Article\n}"));
        assertTrue(sdl.content().contains("  title: String\n"));
        assertTrue(introspection.content().contains("\"name\":\"Article\""));
        assertTrue(introspection.content().contains("\"name\":\"title\""));
        assertTrue(conformance.content().contains("\"profileId\":\"query-contract\""));
        assertTrue(conformance.content().contains("\"QC1-OPERATIONS-QUERY\""));
        assertTrue(conformance.content().contains("\"ACCEPTED\""));
        assertEquals(sdl.hash(), generated.manifest().sdlHash());
        assertEquals(introspection.hash(), generated.manifest().introspectionHash());
        assertEquals(conformance.hash(), generated.manifest().conformanceHash());
        assertEquals("", generated.manifest().generatedSqlHash());
        assertFalse(generated.manifest().artifacts().get(3).enabled());
    }

    @Test
    void rejectsSqlArtifactRequestWithoutGap005PackageMetadata() {
        // The former 'metadataOnly' placeholder branch is deleted: requesting SQL artifacts
        // without the real titanPackage metadata is an explicit, descriptive error state.
        TitanGraphqlModelDocument document = modelDocument(List.of(
                TitanGraphqlFieldDocument.column("id", "Int", "id", List.of(), null)
        ), TitanGraphqlArtifactOptions.defaults());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> TitanGraphqlGeneratedArtifactWorkflow.generateFromModelDocument(
                        "artifact-set-no-package",
                        "draft-no-package",
                        document,
                        TitanGraphqlIntrospectionArtifactPolicy.DISABLED,
                        "postgres-demo",
                        "2026-06-01T11:31:00Z"
                )
        );

        assertTrue(error.getMessage().contains("titanPackage"));
        assertTrue(error.getMessage().contains("titan.graphql.artifacts.dir"));
    }

    @Test
    void consumesGap005MetadataForGeneratedSqlPackageTruth() {
        TitanGraphqlModelDocument document = modelDocument(List.of(
                TitanGraphqlFieldDocument.column("id", "Int", "id", List.of(), null),
                TitanGraphqlFieldDocument.column("title", "String", "title", List.of(), null)
        ), TitanGraphqlArtifactOptions.defaults());
        TitanGraphqlGap005ArtifactMetadata gap005 = gap005Metadata();

        TitanGraphqlGeneratedArtifactSet generated = TitanGraphqlGeneratedArtifactWorkflow.generateFromModelDocument(
                "artifact-set-gap005",
                "draft-gap005",
                document,
                TitanGraphqlIntrospectionArtifactPolicy.ENABLED,
                "postgres-demo",
                "2026-06-01T11:31:00Z",
                gap005
        );

        assertNull(generated.artifact(TitanGraphqlArtifactKind.GENERATED_SQL));
        assertNotNull(generated.artifact(TitanGraphqlArtifactKind.GENERATED_SCHEMA_SDL));
        assertNotNull(generated.artifact(TitanGraphqlArtifactKind.INTROSPECTION_JSON));
        assertNotNull(generated.artifact(TitanGraphqlArtifactKind.CONFORMANCE_MATRIX));
        assertEquals(manifestContentHash(), generated.manifest().generatedSqlHash());
        assertEquals("titan.generated-sql.demo", gap005.artifactId());
        assertEquals(List.of("postgresql"), gap005.dialects());
        assertEquals("pending", gap005.verificationStatus());
        assertEquals("TITAN-GAP005-VERIFY-PENDING", gap005.verificationDiagnostics().getFirst().code());

        // Entry-point metadata comes from the packaged manifest joined with the object
        // inventory (kind, schema-qualified name, signature) — not from runtime reflection.
        assertEquals(1, gap005.entryPoints().size());
        TitanGraphqlEntryPointRef entryPoint = gap005.entryPoints().getFirst();
        assertEquals("io.titan.graphql.demo.blog.DemoBlogTitanGraphqlFunctions", entryPoint.className());
        assertEquals("executeGraphqlRequestWithCompactContext", entryPoint.methodName());
        assertEquals(List.of("java.lang.String"), entryPoint.parameterTypes());
        assertEquals("definer", entryPoint.securityMode());
        TitanGraphqlSqlRoutineRef routine = entryPoint.routines().getFirst();
        assertEquals("postgresql", routine.dialect());
        assertEquals("function", routine.objectKind());
        assertEquals("public.execute_graphql_request_with_compact_context", routine.qualifiedName());
        assertEquals("text", routine.signature());
        assertEquals("text", routine.returnType());

        // Rollback surface from the packaged titan-rollback.postgresql.sql. This fixture manifest
        // predates core's rollbackScripts[] (titan 0933913), so the script is discovered by the
        // filename convention and summarized locally (LEGACY_UNLINKED) — backward compat holds.
        assertEquals(1, gap005.rollbackScripts().size());
        TitanGraphqlRollbackScriptRef rollback = gap005.rollbackScripts().getFirst();
        assertEquals("postgresql", rollback.dialect());
        assertTrue(rollback.present());
        assertEquals("build/generated/migrations/titan/titan-rollback.postgresql.sql", rollback.path());
        assertEquals(2, rollback.statementCount());
        assertFalse(rollback.contentSha256().isBlank());
        assertEquals(TitanGraphqlRollbackScriptRef.IntegrityStatus.LEGACY_UNLINKED, rollback.status());
        assertFalse(rollback.integrityVerified());

        TitanGraphqlGeneratedArtifact titanArtifact =
                generated.artifact(TitanGraphqlArtifactKind.TITAN_ARTIFACT_METADATA);
        TitanGraphqlGeneratedArtifact objectInventory =
                generated.artifact(TitanGraphqlArtifactKind.TITAN_OBJECT_INVENTORY);
        TitanGraphqlGeneratedArtifact installPlan =
                generated.artifact(TitanGraphqlArtifactKind.TITAN_INSTALL_PLAN);
        TitanGraphqlGeneratedArtifact verification =
                generated.artifact(TitanGraphqlArtifactKind.TITAN_INSTALL_VERIFICATION);

        assertNotNull(titanArtifact);
        assertNotNull(objectInventory);
        assertNotNull(installPlan);
        assertNotNull(verification);
        assertEquals("build/generated/migrations/titan/titan-artifact.json", titanArtifact.path());
        assertEquals("build/generated/migrations/titan/titan-object-inventory.json", objectInventory.path());
        assertEquals("build/generated/migrations/titan/titan-install-plan.json", installPlan.path());
        assertEquals("build/generated/migrations/titan/titan-install-verification.json", verification.path());
        assertTrue(titanArtifact.content().contains("\"schemaVersion\":\"titan.artifact.v1\""));
        assertTrue(objectInventory.content().contains("\"schemaVersion\":\"titan.object-inventory.v1\""));
        assertTrue(installPlan.content().contains("\"schemaVersion\":\"titan.install-plan.v1\""));
        assertTrue(verification.content().contains("\"schemaVersion\":\"titan.install-verification.v1\""));
        assertEquals(7, generated.artifacts().size());
        assertEquals(7, generated.manifest().artifacts().size());
    }

    @Test
    void rejectsIncompleteGap005MetadataBeforeTreatingSqlAsPackageTruth() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TitanGraphqlGap005ArtifactMetadata.fromContents(
                        "build/generated/migrations/titan",
                        gap005Manifest("titan.generated-sql.demo"),
                        gap005Inventory("titan.generated-sql.other"),
                        gap005InstallPlan("titan.generated-sql.demo"),
                        gap005InstallVerification("titan.generated-sql.demo")
                )
        );

        assertTrue(error.getMessage().contains("artifactId does not match"));
    }

    @Test
    void rejectsGap005MetadataWithBrokenHashLinks() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TitanGraphqlGap005ArtifactMetadata.fromContents(
                        "build/generated/migrations/titan",
                        gap005Manifest("titan.generated-sql.demo"),
                        gap005Inventory("titan.generated-sql.demo"),
                        gap005InstallPlan("titan.generated-sql.demo")
                                .replace(manifestContentHash(), sourceInputsHash()),
                        gap005InstallVerification("titan.generated-sql.demo")
                )
        );

        assertTrue(error.getMessage().contains("manifestContentSha256 does not match"));
    }

    @Test
    void readsManifestRollbackHashAsAuthorityAndIntegrityVerifiesAgainstTheScript() {
        // Manifest WITH rollbackScripts[] (core B-5, titan 0933913) whose sha256 matches the
        // script bytes: the ref reads the manifest's authoritative count/hash and verifies.
        String rollbackSql = gap005RollbackScript();
        String manifest = gap005ManifestWithRollbackEntry(
                "titan.generated-sql.demo", 2, sha256OfUtf8(rollbackSql));

        TitanGraphqlGap005ArtifactMetadata metadata = TitanGraphqlGap005ArtifactMetadata.fromContents(
                "build/generated/migrations/titan",
                manifest,
                gap005Inventory("titan.generated-sql.demo"),
                gap005InstallPlan("titan.generated-sql.demo"),
                gap005InstallVerification("titan.generated-sql.demo"),
                Map.of("postgresql", rollbackSql));

        TitanGraphqlRollbackScriptRef rollback = metadata.rollbackScripts().getFirst();
        assertTrue(rollback.present());
        assertEquals(TitanGraphqlRollbackScriptRef.IntegrityStatus.VERIFIED, rollback.status());
        assertTrue(rollback.integrityVerified());
        assertEquals(2, rollback.statementCount());
        assertEquals(sha256OfUtf8(rollbackSql), rollback.contentSha256());
    }

    @Test
    void surfacesDriftWhenTheScriptBytesDivergeFromTheManifestRollbackHash() {
        // Manifest WITH rollbackScripts[] but the script bytes differ from the pinned sha256:
        // drift must surface, never read as a clean present, and the manifest stays authoritative.
        String manifestSql = gap005RollbackScript();
        String tamperedSql = manifestSql + "DROP TABLE IF EXISTS \"public\".\"injected\";\n";
        String manifest = gap005ManifestWithRollbackEntry(
                "titan.generated-sql.demo", 2, sha256OfUtf8(manifestSql));

        TitanGraphqlGap005ArtifactMetadata metadata = TitanGraphqlGap005ArtifactMetadata.fromContents(
                "build/generated/migrations/titan",
                manifest,
                gap005Inventory("titan.generated-sql.demo"),
                gap005InstallPlan("titan.generated-sql.demo"),
                gap005InstallVerification("titan.generated-sql.demo"),
                Map.of("postgresql", tamperedSql));

        TitanGraphqlRollbackScriptRef rollback = metadata.rollbackScripts().getFirst();
        assertTrue(rollback.present(), "the script bytes were supplied, so the file is present");
        assertEquals(TitanGraphqlRollbackScriptRef.IntegrityStatus.DRIFTED, rollback.status());
        assertFalse(rollback.integrityVerified(), "drifted bytes must not read as integrity-verified");
        assertTrue(rollback.drifted());
        // Authoritative manifest values, not the tampered script's.
        assertEquals(2, rollback.statementCount());
        assertEquals(sha256OfUtf8(manifestSql), rollback.contentSha256());
    }

    @Test
    void marksManifestLinkedRollbackAbsentWhenNoScriptBytesExist() {
        // Manifest links the script but no bytes exist on disk: ABSENT, not present.
        String manifest = gap005ManifestWithRollbackEntry(
                "titan.generated-sql.demo", 2, sha256OfUtf8(gap005RollbackScript()));

        TitanGraphqlGap005ArtifactMetadata metadata = TitanGraphqlGap005ArtifactMetadata.fromContents(
                "build/generated/migrations/titan",
                manifest,
                gap005Inventory("titan.generated-sql.demo"),
                gap005InstallPlan("titan.generated-sql.demo"),
                gap005InstallVerification("titan.generated-sql.demo"),
                Map.of());

        TitanGraphqlRollbackScriptRef rollback = metadata.rollbackScripts().getFirst();
        assertFalse(rollback.present());
        assertEquals(TitanGraphqlRollbackScriptRef.IntegrityStatus.ABSENT, rollback.status());
        assertFalse(rollback.integrityVerified());
        assertEquals(2, rollback.statementCount());
    }

    @Test
    void keepsIntrospectionArtifactDisabledWhenPolicyDisablesIt() {
        TitanGraphqlModelDocument document = modelDocument(List.of(
                TitanGraphqlFieldDocument.column("id", "Int", "id", List.of(), null)
        ), sqlFreeArtifactOptions());

        TitanGraphqlGeneratedArtifactSet generated = TitanGraphqlGeneratedArtifactWorkflow.generateFromModelDocument(
                "artifact-set-002",
                "draft-002",
                document,
                TitanGraphqlIntrospectionArtifactPolicy.DISABLED,
                "postgres-demo",
                "2026-06-01T11:32:00Z"
        );

        assertNotNull(generated.artifact(TitanGraphqlArtifactKind.GENERATED_SCHEMA_SDL));
        assertNull(generated.artifact(TitanGraphqlArtifactKind.INTROSPECTION_JSON));
        assertEquals("", generated.manifest().introspectionHash());
        assertFalse(generated.manifest().artifacts().get(1).enabled());
    }

    @Test
    void boundedModelShapeChangesAffectGeneratedSdlAndIntrospectionHashes() {
        TitanGraphqlModelDocument idOnly = modelDocument(List.of(
                TitanGraphqlFieldDocument.column("id", "Int", "id", List.of(), null)
        ), sqlFreeArtifactOptions());
        TitanGraphqlModelDocument withTitle = modelDocument(List.of(
                TitanGraphqlFieldDocument.column("id", "Int", "id", List.of(), null),
                TitanGraphqlFieldDocument.column("title", "String", "title", List.of(), null)
        ), sqlFreeArtifactOptions());

        TitanGraphqlGeneratedArtifactSet first = TitanGraphqlGeneratedArtifactWorkflow.generateFromModelDocument(
                "artifact-set-003",
                "draft-003",
                idOnly,
                TitanGraphqlIntrospectionArtifactPolicy.ENABLED,
                "postgres-demo",
                "2026-06-01T11:33:00Z"
        );
        TitanGraphqlGeneratedArtifactSet second = TitanGraphqlGeneratedArtifactWorkflow.generateFromModelDocument(
                "artifact-set-004",
                "draft-004",
                withTitle,
                TitanGraphqlIntrospectionArtifactPolicy.ENABLED,
                "postgres-demo",
                "2026-06-01T11:34:00Z"
        );

        assertNotEquals(first.manifest().sdlHash(), second.manifest().sdlHash());
        assertNotEquals(first.manifest().introspectionHash(), second.manifest().introspectionHash());
        assertFalse(first.artifact(TitanGraphqlArtifactKind.INTROSPECTION_JSON).content().contains("\"name\":\"title\""));
        assertTrue(second.artifact(TitanGraphqlArtifactKind.INTROSPECTION_JSON).content().contains("\"name\":\"title\""));
    }

    @Test
    void productionConformanceArtifactRejectsPendingOrJavaOnlyRows() {
        String markdown = """
                | ID | Area | Behavior | Classification | Current Evidence | Next Work |
                | --- | --- | --- | --- | --- | --- |
                | QC-ACCEPTED | Runtime | Supported behavior | ACCEPTED | covered | keep covered |
                | QC-PENDING | Runtime | Not ready behavior | PENDING | not covered | implement |
                | QC-JAVA | Runtime | Java-only behavior | JAVA_ONLY | Java tests | lower to SQL |
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TitanGraphqlGeneratedArtifactWorkflow.conformanceArtifactJson("production", markdown)
        );

        assertTrue(error.getMessage().contains("QC-PENDING=PENDING"));
        assertTrue(error.getMessage().contains("QC-JAVA=JAVA_ONLY"));
    }

    private static TitanGraphqlArtifactOptions sqlFreeArtifactOptions() {
        TitanGraphqlArtifactOptions defaults = TitanGraphqlArtifactOptions.defaults();
        return new TitanGraphqlArtifactOptions(
                defaults.generateSdl(),
                defaults.generateIntrospection(),
                defaults.generateConformance(),
                false,
                defaults.outputDirectory()
        );
    }

    private static TitanGraphqlModelDocument modelDocument(
            List<TitanGraphqlFieldDocument> fields,
            TitanGraphqlArtifactOptions artifactOptions
    ) {
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
                        fields,
                        List.of()
                )),
                List.of(),
                List.of(),
                artifactOptions,
                null
        );
    }

    private static TitanGraphqlGap005ArtifactMetadata gap005Metadata() {
        return TitanGraphqlGap005ArtifactMetadata.fromContents(
                "build/generated/migrations/titan",
                gap005Manifest("titan.generated-sql.demo"),
                gap005Inventory("titan.generated-sql.demo"),
                gap005InstallPlan("titan.generated-sql.demo"),
                gap005InstallVerification("titan.generated-sql.demo"),
                Map.of("postgresql", gap005RollbackScript())
        );
    }

    private static String gap005RollbackScript() {
        return """
                -- titan-rollback for titan.generated-sql.demo (postgresql)
                DROP FUNCTION IF EXISTS "public"."execute_graphql_request_with_compact_context"(TEXT);
                DROP TABLE IF EXISTS "titan_runtime"."telemetry";
                """;
    }

    private static String gap005Manifest(String artifactId) {
        return """
                {"schemaVersion":"titan.artifact.v1","artifactId":"%s","titanVersion":"gap005-complete",
                "packageMode":"migration","dialects":["postgresql"],"sourceInputs":[],
                "entryPoints":[{"id":"io.titan.graphql.demo.blog.DemoBlogTitanGraphqlFunctions.executeGraphqlRequestWithCompactContext()",
                "java":{"className":"io.titan.graphql.demo.blog.DemoBlogTitanGraphqlFunctions",
                "methodName":"executeGraphqlRequestWithCompactContext","parameterTypes":["java.lang.String"],
                "annotation":"StoredFunction","sourceLocation":{"path":"src/main/java/io/titan/graphql/demo/blog/DemoBlogTitanGraphqlFunctions.java","line":42}},
                "sql":[{"dialect":"postgresql","objectId":"postgresql.public.execute_graphql_request_with_compact_context.function",
                "routineName":"execute_graphql_request_with_compact_context","routineKind":"function","parameters":[],
                "returnType":"text","sourceInputPath":"postgresql/demo.sql"}],"securityMode":"definer"}],
                "generatedObjects":[{"id":"postgresql.public.execute_graphql_request_with_compact_context.function","status":"inventoried"}],
                "hashes":{"sourceInputsSha256":"%s","manifestContentSha256":"%s"},"validation":{"status":"generated","warnings":[]}}
                """.formatted(artifactId, sourceInputsHash(), manifestContentHash());
    }

    /**
     * The demo manifest with an additive {@code rollbackScripts[]} entry (core B-5,
     * titan 0933913) linking the postgresql rollback script by path, statement count and
     * raw-byte sha256. Schema string stays {@code titan.artifact.v1} (additive).
     */
    private static String gap005ManifestWithRollbackEntry(String artifactId, int statementCount, String sha256) {
        return gap005Manifest(artifactId).replaceFirst(
                "(\"generatedObjects\":\\[\\{[^}]*}],)",
                "$1\n\"rollbackScripts\":[{\"dialect\":\"postgresql\",\"path\":\"titan-rollback.postgresql.sql\","
                        + "\"statementCount\":" + statementCount + ",\"sha256\":\"" + sha256 + "\"}],");
    }

    private static String sha256OfUtf8(String content) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String gap005Inventory(String artifactId) {
        return """
                {"schemaVersion":"titan.object-inventory.v1","artifactId":"%s","objects":[
                {"id":"postgresql.public.execute_graphql_request_with_compact_context.function","dialect":"postgresql",
                "kind":"function","schema":"public","name":"execute_graphql_request_with_compact_context","signature":"text",
                "sourceInputPath":"postgresql/demo.sql","sourceEntryPoint":"io.titan.graphql.demo.blog.DemoBlogTitanGraphqlFunctions.executeGraphqlRequestWithCompactContext()",
                "securityMode":"definer","createOrder":1,"dependsOn":[],"sqlHash":"%s"}],
                "hashes":{"inventoryContentSha256":"%s"}}
                """.formatted(artifactId, sqlHash(), inventoryContentHash());
    }

    private static String gap005InstallPlan(String artifactId) {
        return """
                {"schemaVersion":"titan.install-plan.v1","artifactId":"%s","manifestContentSha256":"%s",
                "inventoryContentSha256":"%s","dialectPlans":[{"dialect":"postgresql","transactionMode":"singleTransaction",
                "transactionNote":"review as one transaction","packageSqlLocation":"postgresql/",
                "steps":[{"id":"create.postgresql.public.execute_graphql_request_with_compact_context.function","kind":"createOrReplace",
                "check":"objectExists","schema":"public","objectId":"postgresql.public.execute_graphql_request_with_compact_context.function",
                "onFailure":"fail","rollbackHint":"drop generated object"}]}],
                "hashes":{"planContentSha256":"%s"}}
                """.formatted(artifactId, manifestContentHash(), inventoryContentHash(), installPlanContentHash());
    }

    private static String gap005InstallVerification(String artifactId) {
        return """
                {"schemaVersion":"titan.install-verification.v1","artifactId":"%s",
                "manifestContentSha256":"%s","installPlanContentSha256":"%s","status":"pending",
                "database":{"kind":"scratch-required","version":"not-run"},
                "dialectReports":[{"dialect":"postgresql","status":"pending","verifiedObjects":[]}],
                "drift":[],"diagnostics":[{"dialect":"all","stepId":"verification.pending","objectId":"",
                "code":"TITAN-GAP005-VERIFY-PENDING","message":"Scratch database verification has not been executed."}]}
                """.formatted(artifactId, manifestContentHash(), installPlanContentHash());
    }

    private static String manifestContentHash() {
        return "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    }

    private static String sourceInputsHash() {
        return "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    }

    private static String inventoryContentHash() {
        return "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    }

    private static String installPlanContentHash() {
        return "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    }

    private static String sqlHash() {
        return "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    }
}
