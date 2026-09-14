package io.titan.graphql.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.titan.graphql.artifact.TitanGraphqlRollbackScriptRef.IntegrityStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TitanGraphqlGap005ArtifactMetadata(
        String artifactRoot,
        String artifactId,
        String packageMode,
        String titanVersion,
        List<String> dialects,
        String manifestContentHash,
        String sourceInputsHash,
        String inventoryContentHash,
        String installPlanContentHash,
        String verificationStatus,
        List<TitanGraphqlVerificationDiagnostic> verificationDiagnostics,
        List<TitanGraphqlEntryPointRef> entryPoints,
        List<TitanGraphqlRollbackScriptRef> rollbackScripts,
        List<TitanGraphqlGeneratedArtifact> artifacts
) {
    private static final JsonMapper JSON = new JsonMapper();

    public TitanGraphqlGap005ArtifactMetadata {
        artifactRoot = normalizeArtifactRoot(artifactRoot);
        artifactId = requireText(artifactId, "artifactId");
        packageMode = requireText(packageMode, "packageMode");
        titanVersion = requireText(titanVersion, "titanVersion");
        dialects = dialects == null ? List.of() : List.copyOf(dialects);
        if (dialects.isEmpty()) {
            throw new IllegalArgumentException("Titan GAP-005 metadata requires at least one dialect");
        }
        manifestContentHash = requireSha256(manifestContentHash, "manifestContentHash");
        sourceInputsHash = requireSha256(sourceInputsHash, "sourceInputsHash");
        inventoryContentHash = requireSha256(inventoryContentHash, "inventoryContentHash");
        installPlanContentHash = requireSha256(installPlanContentHash, "installPlanContentHash");
        verificationStatus = requireText(verificationStatus, "verificationStatus");
        verificationDiagnostics = verificationDiagnostics == null ? List.of() : List.copyOf(verificationDiagnostics);
        entryPoints = entryPoints == null ? List.of() : List.copyOf(entryPoints);
        rollbackScripts = rollbackScripts == null ? List.of() : List.copyOf(rollbackScripts);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        if (artifacts.size() != 4) {
            throw new IllegalArgumentException("Titan GAP-005 metadata requires all four metadata artifacts");
        }
    }

    public static TitanGraphqlGap005ArtifactMetadata read(Path artifactRoot) {
        if (artifactRoot == null) {
            throw new IllegalArgumentException("artifact root is required");
        }
        return read(artifactRoot, artifactRoot.toString());
    }

    public static TitanGraphqlGap005ArtifactMetadata read(Path artifactRoot, String displayArtifactRoot) {
        if (artifactRoot == null) {
            throw new IllegalArgumentException("artifact root is required");
        }
        try {
            String manifestJson = readArtifact(artifactRoot, TitanGraphqlArtifactKind.TITAN_ARTIFACT_METADATA);
            return fromContentsAndRollbackBytes(
                    displayArtifactRoot,
                    manifestJson,
                    readArtifact(artifactRoot, TitanGraphqlArtifactKind.TITAN_OBJECT_INVENTORY),
                    readArtifact(artifactRoot, TitanGraphqlArtifactKind.TITAN_INSTALL_PLAN),
                    readArtifact(artifactRoot, TitanGraphqlArtifactKind.TITAN_INSTALL_VERIFICATION),
                    readRollbackScripts(artifactRoot, manifestJson)
            );
        } catch (IOException ex) {
            throw new IllegalArgumentException("Titan GAP-005 metadata could not be read from " + artifactRoot, ex);
        }
    }

    // TG-BLK-008 (resolved by core's B-5 fix, titan 0933913): the titan-artifact.json manifest now
    // integrity-links each rollback script via the additive rollbackScripts[] array
    // ({dialect, path, statementCount, sha256-over-raw-bytes}). We read the script the manifest
    // points at — its relative path when linked, else the titan-rollback.<dialect>.sql filename
    // convention for older packages — as RAW BYTES, so the on-disk hash can be compared to the
    // manifest's authority byte-for-byte (Files.readAllBytes, not readString: re-encoding a
    // decoded String can diverge from core's raw-byte hash for non-ASCII content).
    private static Map<String, byte[]> readRollbackScripts(Path artifactRoot, String manifestJson) throws IOException {
        JsonNode manifest = manifestNodeOrNull(manifestJson);
        Map<String, ManifestRollbackEntry> manifestEntries = manifestRollbackEntries(manifest);
        Map<String, byte[]> rollbackBytesByDialect = new LinkedHashMap<>();
        for (String dialect : manifestDialectsOrEmpty(manifestJson)) {
            ManifestRollbackEntry entry = manifestEntries.get(dialect);
            String relativePath = entry != null ? entry.path() : rollbackScriptFileName(dialect);
            Path script = artifactRoot.resolve(relativePath);
            if (Files.isRegularFile(script)) {
                rollbackBytesByDialect.put(dialect, Files.readAllBytes(script));
            }
        }
        return rollbackBytesByDialect;
    }

    private static List<String> manifestDialectsOrEmpty(String manifestJson) {
        try {
            return dialects(JSON.readTree(manifestJson));
        } catch (IOException | IllegalArgumentException malformedManifest) {
            // fromContents reports the manifest defect with full context.
            return List.of();
        }
    }

    private static JsonNode manifestNodeOrNull(String manifestJson) {
        try {
            return JSON.readTree(manifestJson);
        } catch (IOException malformedManifest) {
            // fromContents reports the manifest defect with full context.
            return null;
        }
    }

    /**
     * The manifest's integrity authority for the rollback script of one dialect (core B-5,
     * titan 0933913): the relative {@code path} the script was written to, the {@code statementCount}
     * and the {@code sha256} of the script's RAW UTF-8 BYTES. These are read verbatim and made
     * authoritative — never recomputed when the manifest links the script.
     */
    private record ManifestRollbackEntry(String dialect, String path, int statementCount, String sha256) {
    }

    /**
     * Parses the manifest's additive {@code rollbackScripts[]} array (absent in older packages /
     * fixtures, which is fine — those dialects fall back to filename discovery + recompute). Only
     * well-formed entries are linked; a malformed entry is ignored so its dialect degrades to the
     * legacy path rather than failing the whole read.
     */
    private static Map<String, ManifestRollbackEntry> manifestRollbackEntries(JsonNode manifest) {
        Map<String, ManifestRollbackEntry> entriesByDialect = new LinkedHashMap<>();
        if (manifest == null) {
            return entriesByDialect;
        }
        for (JsonNode entry : manifest.path("rollbackScripts")) {
            JsonNode dialect = entry.get("dialect");
            JsonNode path = entry.get("path");
            JsonNode sha256 = entry.get("sha256");
            JsonNode statementCount = entry.get("statementCount");
            if (isText(dialect) && isText(path) && isSha256(sha256)
                    && statementCount != null && statementCount.canConvertToInt() && statementCount.asInt() >= 0) {
                entriesByDialect.put(dialect.textValue(), new ManifestRollbackEntry(
                        dialect.textValue(),
                        path.textValue(),
                        statementCount.asInt(),
                        sha256.textValue()));
            }
        }
        return entriesByDialect;
    }

    private static boolean isText(JsonNode node) {
        return node != null && node.isTextual() && node.textValue().isBlank() == false;
    }

    private static boolean isSha256(JsonNode node) {
        return isText(node) && node.textValue().matches("[0-9a-f]{64}");
    }

    public static String rollbackScriptFileName(String dialect) {
        return "titan-rollback." + requireText(dialect, "dialect") + ".sql";
    }

    private static String readArtifact(Path artifactRoot, TitanGraphqlArtifactKind kind) throws IOException {
        return Files.readString(
                artifactRoot.resolve(kind.defaultFileName()),
                StandardCharsets.UTF_8
        );
    }

    public static TitanGraphqlGap005ArtifactMetadata fromContents(
            String artifactRoot,
            String manifestJson,
            String objectInventoryJson,
            String installPlanJson,
            String installVerificationJson
    ) {
        return fromContents(
                artifactRoot,
                manifestJson,
                objectInventoryJson,
                installPlanJson,
                installVerificationJson,
                Map.of()
        );
    }

    public static TitanGraphqlGap005ArtifactMetadata fromContents(
            String artifactRoot,
            String manifestJson,
            String objectInventoryJson,
            String installPlanJson,
            String installVerificationJson,
            Map<String, String> rollbackSqlByDialect
    ) {
        // The String-keyed overload feeds the bytes path the script's RAW UTF-8 BYTES so that
        // legacy fixtures (and any in-memory caller) hash identically to a real on-disk read.
        Map<String, byte[]> rollbackBytesByDialect = new LinkedHashMap<>();
        if (rollbackSqlByDialect != null) {
            rollbackSqlByDialect.forEach((dialect, sql) ->
                    rollbackBytesByDialect.put(dialect, sql.getBytes(StandardCharsets.UTF_8)));
        }
        return fromContentsAndRollbackBytes(
                artifactRoot,
                manifestJson,
                objectInventoryJson,
                installPlanJson,
                installVerificationJson,
                rollbackBytesByDialect);
    }

    private static TitanGraphqlGap005ArtifactMetadata fromContentsAndRollbackBytes(
            String artifactRoot,
            String manifestJson,
            String objectInventoryJson,
            String installPlanJson,
            String installVerificationJson,
            Map<String, byte[]> rollbackBytesByDialect
    ) {
        JsonNode manifest = parse(manifestJson, "titan-artifact.json");
        JsonNode inventory = parse(objectInventoryJson, "titan-object-inventory.json");
        JsonNode installPlan = parse(installPlanJson, "titan-install-plan.json");
        JsonNode verification = parse(installVerificationJson, "titan-install-verification.json");

        requireSchema(manifest, "titan.artifact.v1", "titan-artifact.json");
        requireSchema(inventory, "titan.object-inventory.v1", "titan-object-inventory.json");
        requireSchema(installPlan, "titan.install-plan.v1", "titan-install-plan.json");
        requireSchema(verification, "titan.install-verification.v1", "titan-install-verification.json");

        String artifactId = text(manifest, "artifactId", "titan-artifact.json");
        requireSameArtifactId(artifactId, inventory, "titan-object-inventory.json");
        requireSameArtifactId(artifactId, installPlan, "titan-install-plan.json");
        requireSameArtifactId(artifactId, verification, "titan-install-verification.json");

        String manifestContentHash = text(manifest.path("hashes"), "manifestContentSha256", "titan-artifact.json hashes");
        String sourceInputsHash = text(manifest.path("hashes"), "sourceInputsSha256", "titan-artifact.json hashes");
        String inventoryContentHash =
                text(inventory.path("hashes"), "inventoryContentSha256", "titan-object-inventory.json hashes");
        String installPlanContentHash =
                text(installPlan.path("hashes"), "planContentSha256", "titan-install-plan.json hashes");
        requireHashLink(
                manifestContentHash,
                text(installPlan, "manifestContentSha256", "titan-install-plan.json"),
                "titan-install-plan.json manifestContentSha256"
        );
        requireHashLink(
                inventoryContentHash,
                text(installPlan, "inventoryContentSha256", "titan-install-plan.json"),
                "titan-install-plan.json inventoryContentSha256"
        );
        requireHashLink(
                manifestContentHash,
                text(verification, "manifestContentSha256", "titan-install-verification.json"),
                "titan-install-verification.json manifestContentSha256"
        );
        requireHashLink(
                installPlanContentHash,
                text(verification, "installPlanContentSha256", "titan-install-verification.json"),
                "titan-install-verification.json installPlanContentSha256"
        );

        String normalizedRoot = normalizeArtifactRoot(artifactRoot);
        List<String> manifestDialects = dialects(manifest);
        return new TitanGraphqlGap005ArtifactMetadata(
                normalizedRoot,
                artifactId,
                text(manifest, "packageMode", "titan-artifact.json"),
                text(manifest, "titanVersion", "titan-artifact.json"),
                manifestDialects,
                manifestContentHash,
                sourceInputsHash,
                inventoryContentHash,
                installPlanContentHash,
                text(verification, "status", "titan-install-verification.json"),
                verificationDiagnostics(verification),
                entryPoints(manifest, inventory),
                rollbackScripts(normalizedRoot, manifestDialects,
                        manifestRollbackEntries(manifest), rollbackBytesByDialect),
                List.of(
                        artifact(normalizedRoot, TitanGraphqlArtifactKind.TITAN_ARTIFACT_METADATA, manifestJson),
                        artifact(normalizedRoot, TitanGraphqlArtifactKind.TITAN_OBJECT_INVENTORY, objectInventoryJson),
                        artifact(normalizedRoot, TitanGraphqlArtifactKind.TITAN_INSTALL_PLAN, installPlanJson),
                        artifact(normalizedRoot, TitanGraphqlArtifactKind.TITAN_INSTALL_VERIFICATION, installVerificationJson)
                )
        );
    }

    /**
     * Entry points come from the real manifest's {@code entryPoints[]} (Java identity plus
     * dialect routines) joined against the object inventory by {@code objectId} for the
     * authoritative kind, schema-qualified name and signature. This is the structured
     * replacement for the deleted runtime-reflection scan of the kernel class.
     */
    private static List<TitanGraphqlEntryPointRef> entryPoints(JsonNode manifest, JsonNode inventory) {
        Map<String, JsonNode> inventoryObjectsById = new LinkedHashMap<>();
        for (JsonNode inventoryObject : inventory.path("objects")) {
            JsonNode id = inventoryObject.get("id");
            if (id != null && id.isTextual()) {
                inventoryObjectsById.put(id.textValue(), inventoryObject);
            }
        }
        List<TitanGraphqlEntryPointRef> entryPoints = new ArrayList<>();
        for (JsonNode entryPoint : manifest.path("entryPoints")) {
            JsonNode java = entryPoint.path("java");
            List<String> parameterTypes = new ArrayList<>();
            for (JsonNode parameterType : java.path("parameterTypes")) {
                parameterTypes.add(parameterType.asText());
            }
            List<TitanGraphqlSqlRoutineRef> routines = new ArrayList<>();
            for (JsonNode sql : entryPoint.path("sql")) {
                routines.add(sqlRoutine(sql, inventoryObjectsById));
            }
            entryPoints.add(new TitanGraphqlEntryPointRef(
                    text(entryPoint, "id", "titan-artifact.json entryPoints"),
                    text(java, "className", "titan-artifact.json entryPoints java"),
                    text(java, "methodName", "titan-artifact.json entryPoints java"),
                    parameterTypes,
                    entryPoint.path("securityMode").asText(""),
                    routines
            ));
        }
        return entryPoints;
    }

    private static TitanGraphqlSqlRoutineRef sqlRoutine(JsonNode sql, Map<String, JsonNode> inventoryObjectsById) {
        String objectId = text(sql, "objectId", "titan-artifact.json entryPoints sql");
        JsonNode inventoryObject = inventoryObjectsById.get(objectId);
        if (inventoryObject == null) {
            throw new IllegalArgumentException(
                    "titan-artifact.json entry point references object '" + objectId
                            + "' that is missing from titan-object-inventory.json");
        }
        String schemaName = text(inventoryObject, "schema", "titan-object-inventory.json objects");
        String routineName = text(inventoryObject, "name", "titan-object-inventory.json objects");
        return new TitanGraphqlSqlRoutineRef(
                text(sql, "dialect", "titan-artifact.json entryPoints sql"),
                objectId,
                text(inventoryObject, "kind", "titan-object-inventory.json objects"),
                schemaName,
                routineName,
                schemaName + "." + routineName,
                inventoryObject.path("signature").asText(""),
                sql.path("returnType").asText("")
        );
    }

    private static List<TitanGraphqlVerificationDiagnostic> verificationDiagnostics(JsonNode verification) {
        List<TitanGraphqlVerificationDiagnostic> diagnostics = new ArrayList<>();
        for (JsonNode diagnostic : verification.path("diagnostics")) {
            diagnostics.add(new TitanGraphqlVerificationDiagnostic(
                    diagnostic.path("dialect").asText(""),
                    diagnostic.path("code").asText(""),
                    diagnostic.path("message").asText("")
            ));
        }
        return diagnostics;
    }

    /**
     * Builds the per-dialect rollback refs. When the manifest links the script (core B-5,
     * titan 0933913: {@code rollbackScripts[]}), the manifest's {@code statementCount} and
     * raw-byte {@code sha256} are AUTHORITATIVE — copied verbatim, never recomputed — and the
     * on-disk bytes are verified against that authority, yielding VERIFIED / DRIFTED / ABSENT.
     * When the manifest has no entry for a dialect (older package / fixture), the script is
     * discovered by filename convention and summarized locally (LEGACY_UNLINKED), with its hash
     * taken over the RAW bytes so it still matches core's convention.
     */
    private static List<TitanGraphqlRollbackScriptRef> rollbackScripts(
            String artifactRoot,
            List<String> dialects,
            Map<String, ManifestRollbackEntry> manifestEntries,
            Map<String, byte[]> rollbackBytesByDialect
    ) {
        Map<String, byte[]> bytesByDialect = rollbackBytesByDialect == null ? Map.of() : rollbackBytesByDialect;
        List<TitanGraphqlRollbackScriptRef> rollbackScripts = new ArrayList<>();
        for (String dialect : dialects) {
            ManifestRollbackEntry manifestEntry = manifestEntries.get(dialect);
            byte[] bytes = bytesByDialect.get(dialect);
            if (manifestEntry != null) {
                rollbackScripts.add(manifestLinkedRef(artifactRoot, manifestEntry, bytes));
            } else {
                rollbackScripts.add(legacyUnlinkedRef(artifactRoot, dialect, bytes));
            }
        }
        return rollbackScripts;
    }

    /**
     * The manifest is the integrity authority: {@code statementCount} and {@code contentSha256}
     * are the manifest's values regardless of the on-disk file. The on-disk raw bytes only decide
     * the integrity STATUS — a present file whose raw-byte hash diverges is DRIFTED (not a clean
     * present), a missing file is ABSENT, a matching file is VERIFIED.
     */
    private static TitanGraphqlRollbackScriptRef manifestLinkedRef(
            String artifactRoot,
            ManifestRollbackEntry entry,
            byte[] onDiskBytes
    ) {
        String path = outputPath(artifactRoot, entry.path());
        IntegrityStatus status;
        boolean present;
        if (onDiskBytes == null) {
            status = IntegrityStatus.ABSENT;
            present = false;
        } else if (sha256(onDiskBytes).equals(entry.sha256())) {
            status = IntegrityStatus.VERIFIED;
            present = true;
        } else {
            status = IntegrityStatus.DRIFTED;
            present = true;
        }
        return new TitanGraphqlRollbackScriptRef(
                entry.dialect(),
                path,
                present,
                entry.statementCount(),
                entry.sha256(),
                status);
    }

    /**
     * Backward-compat: no manifest entry, so discover by filename convention and recompute the
     * summary from the script's RAW bytes (LEGACY_UNLINKED — there is no manifest hash to verify
     * against).
     */
    private static TitanGraphqlRollbackScriptRef legacyUnlinkedRef(
            String artifactRoot,
            String dialect,
            byte[] onDiskBytes
    ) {
        String path = outputPath(artifactRoot, rollbackScriptFileName(dialect));
        if (onDiskBytes == null) {
            return new TitanGraphqlRollbackScriptRef(
                    dialect, path, false, 0, "", IntegrityStatus.LEGACY_UNLINKED);
        }
        return new TitanGraphqlRollbackScriptRef(
                dialect,
                path,
                true,
                countSqlStatements(new String(onDiskBytes, StandardCharsets.UTF_8)),
                sha256(onDiskBytes),
                IntegrityStatus.LEGACY_UNLINKED);
    }

    /**
     * Statement count for the rollback summary: core emits one {@code DROP ...;} statement
     * per line, so non-comment lines terminated by {@code ;} are the statement boundaries.
     */
    private static int countSqlStatements(String sql) {
        int count = 0;
        for (String line : sql.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") == false && trimmed.endsWith(";")) {
                count = count + 1;
            }
        }
        return count;
    }

    public List<TitanGraphqlArtifactManifestEntry> manifestEntries() {
        return artifacts.stream()
                .map(artifact -> new TitanGraphqlArtifactManifestEntry(
                        artifact.kind(),
                        artifact.kind().manifestName(),
                        true,
                        artifact.path(),
                        artifact.hash()
                ))
                .toList();
    }

    private static TitanGraphqlGeneratedArtifact artifact(
            String artifactRoot,
            TitanGraphqlArtifactKind kind,
            String content
    ) {
        return new TitanGraphqlGeneratedArtifact(
                kind,
                outputPath(artifactRoot, kind.defaultFileName()),
                content,
                sha256(content)
        );
    }

    private static JsonNode parse(String json, String fileName) {
        try {
            return JSON.readTree(requireText(json, fileName));
        } catch (IOException ex) {
            throw new IllegalArgumentException(fileName + " is not valid JSON", ex);
        }
    }

    private static void requireHashLink(String expected, String actual, String fieldName) {
        requireSha256(actual, fieldName);
        if (expected.equals(actual) == false) {
            throw new IllegalArgumentException(fieldName + " does not match referenced Titan GAP-005 metadata");
        }
    }

    private static void requireSchema(JsonNode node, String schemaVersion, String fileName) {
        String actual = text(node, "schemaVersion", fileName);
        if (schemaVersion.equals(actual) == false) {
            throw new IllegalArgumentException(fileName + " must use " + schemaVersion + " but was " + actual);
        }
    }

    private static void requireSameArtifactId(String expected, JsonNode node, String fileName) {
        String actual = text(node, "artifactId", fileName);
        if (expected.equals(actual) == false) {
            throw new IllegalArgumentException(fileName + " artifactId does not match Titan artifact manifest");
        }
    }

    private static List<String> dialects(JsonNode manifest) {
        JsonNode dialects = manifest.path("dialects");
        if (dialects.isArray() == false) {
            throw new IllegalArgumentException("titan-artifact.json dialects must be an array");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode dialect : dialects) {
            if (dialect.isTextual() == false || dialect.textValue().isBlank()) {
                throw new IllegalArgumentException("titan-artifact.json dialects must contain text values");
            }
            values.add(dialect.textValue());
        }
        return values;
    }

    private static String text(JsonNode node, String fieldName, String fileName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isTextual() == false || value.textValue().isBlank()) {
            throw new IllegalArgumentException(fileName + " requires " + fieldName);
        }
        return value.textValue();
    }

    private static String normalizeArtifactRoot(String artifactRoot) {
        String value = requireText(artifactRoot, "artifactRoot").replace('\\', '/');
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.startsWith("/") || value.equals(".") || value.contains("..")) {
            throw new IllegalArgumentException("artifactRoot must be a relative reproducible path");
        }
        return value;
    }

    private static String outputPath(String artifactRoot, String fileName) {
        return artifactRoot + "/" + fileName;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    private static String requireSha256(String value, String fieldName) {
        String text = requireText(value, fieldName);
        if (text.matches("[0-9a-f]{64}") == false) {
            throw new IllegalArgumentException(fieldName + " must be a lowercase SHA-256 hex value");
        }
        return text;
    }

    private static String sha256(String content) {
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Hashes the RAW bytes — the integrity primitive the rollback verification relies on: core's
     * manifest sha256 is over the rollback script's raw UTF-8 bytes, so the on-disk file must be
     * hashed as {@code Files.readAllBytes}, not a re-encoded {@code Files.readString}.
     */
    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
