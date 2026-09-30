package io.titan.graphql.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlMutationDocument;
import io.titan.graphql.model.TitanGraphqlTypeDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TitanGraphqlMutationPackageAttestor {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String HANDLER_PACKAGE = "io.titan.graphql.database.handlers.";
    private static final String ENGINE_PACKAGE = "io.titan.graphql.database.generated.";
    private static final Pattern DISPATCH = Pattern.compile(
            "__titan_internal_database_graphql_engine_root_field_ma[a-zA-Z0-9_]*"
                    + "\\([^\\n)]*\\bv_mutation_root_start, '([a-zA-Z_][a-zA-Z_0-9]*)'\\)");
    private static final Pattern PREFLIGHT_DISPATCH = Pattern.compile(
            "__titan_internal_database_graphql_engine_root_field_ma[a-zA-Z0-9_]*"
                    + "\\([^\\n)]*\\bv_mutation_validation_root_start, '([a-zA-Z_][a-zA-Z_0-9]*)'\\)");

    private TitanGraphqlMutationPackageAttestor() {
    }

    public static void verify(
            TitanGraphqlModelDocument document,
            Path packageDirectory,
            TitanGraphqlGap005ArtifactMetadata metadata
    ) {
        Objects.requireNonNull(document, "reviewed model");
        Objects.requireNonNull(packageDirectory, "package directory");
        Objects.requireNonNull(metadata, "package metadata");
        TitanGraphqlGap005ArtifactMetadata verified = TitanGraphqlArtifactsDirectory.readGap005Metadata(
                packageDirectory, TitanGraphqlArtifactsDirectory.PORTABLE_DISPLAY_ROOT);
        if (!verified.artifactId().equals(metadata.artifactId())
                || !verified.manifestContentHash().equals(metadata.manifestContentHash())
                || !verified.sourceInputsHash().equals(metadata.sourceInputsHash())
                || !verified.inventoryContentHash().equals(metadata.inventoryContentHash())
                || !verified.installPlanContentHash().equals(metadata.installPlanContentHash())
                || !verified.verificationStatus().equals(metadata.verificationStatus())
                || !verified.dialects().equals(metadata.dialects())) {
            throw new IllegalStateException("mutation package metadata changed before attestation");
        }
        if (!verified.verificationStatus().equals("passed")) {
            throw new IllegalStateException("mutation package install verification did not pass");
        }
        JsonNode inventory;
        JsonNode manifest;
        try {
            String inventoryText = Files.readString(packageDirectory.resolve(
                    TitanGraphqlArtifactKind.TITAN_OBJECT_INVENTORY.defaultFileName()), StandardCharsets.UTF_8);
            verifyInventoryContentHash(inventoryText, metadata.inventoryContentHash());
            inventory = JSON.readTree(inventoryText);
            manifest = JSON.readTree(Files.readString(packageDirectory.resolve(
                    TitanGraphqlArtifactKind.TITAN_ARTIFACT_METADATA.defaultFileName()), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("mutation package inventory could not be read", failure);
        }
        for (String dialect : metadata.dialects()) {
            verifyInventory(document, inventory, dialect);
            try {
                verifyPackagedSql(document, packageDirectory, inventory, manifest, dialect);
            } catch (IOException failure) {
                throw new IllegalStateException("mutation package SQL could not be read for " + dialect, failure);
            }
        }
    }

    static void verifyInventoryContentHash(String inventoryText, String expectedHash) {
        String marker = "\"inventoryContentSha256\": \"" + expectedHash + "\"";
        int location = inventoryText.indexOf(marker);
        if (location < 0 || inventoryText.lastIndexOf(marker) != location) {
            throw new IllegalStateException("mutation package inventory content hash is missing or duplicated");
        }
        String placeholder = "\"inventoryContentSha256\": \""
                + "0000000000000000000000000000000000000000000000000000000000000000\"";
        String normalized = inventoryText.substring(0, location) + placeholder
                + inventoryText.substring(location + marker.length());
        if (!sha256(normalized).equals(expectedHash)) {
            throw new IllegalStateException("mutation package inventory content hash does not match package bytes");
        }
    }

    static void verifyInventory(TitanGraphqlModelDocument document, JsonNode inventory, String dialect) {
        Objects.requireNonNull(document, "reviewed model");
        if (inventory == null || !inventory.path("objects").isArray()) {
            throw new IllegalArgumentException("mutation package inventory has no objects");
        }
        if (!dialect.equals("postgresql") && !dialect.equals("mysql")) {
            throw new IllegalArgumentException("mutation package dialect must be postgresql or mysql");
        }
        Set<String> expected = new HashSet<>();
        for (TitanGraphqlMutationDocument mutation : document.mutations()) {
            if (mutation.operation() == TitanGraphqlMutationDocument.MutationDocumentOperation.UPDATE) {
                continue;
            }
            TitanGraphqlMutationDocument.MutationDocumentHandler handler = mutation.handler();
            if (handler == null || !handler.className().startsWith(HANDLER_PACKAGE)) {
                throw new IllegalStateException("reviewed mutation has no source-local handler: "
                        + mutation.name());
            }
            expected.add(handler.className() + "." + handler.methodName() + "()");
        }
        Map<String, JsonNode> objects = new HashMap<>();
        Map<String, String> handlers = new HashMap<>();
        String rootId = null;
        for (JsonNode object : inventory.path("objects")) {
            if (!dialect.equals(object.path("dialect").asText())) {
                continue;
            }
            String id = object.path("id").asText();
            if (id.isBlank() || objects.putIfAbsent(id, object) != null) {
                throw new IllegalStateException("mutation package inventory has a missing or duplicate object ID");
            }
            String source = object.path("sourceEntryPoint").asText();
            if (source.startsWith(HANDLER_PACKAGE)) {
                if (!object.path("kind").asText().equals("procedure")
                        || handlers.putIfAbsent(source, id) != null) {
                    throw new IllegalStateException("mutation handler is not a unique procedure: " + source);
                }
            }
            if (object.path("name").asText().equals("execute_graphql_request")
                    && source.startsWith(ENGINE_PACKAGE)) {
                if (!object.path("kind").asText().equals(dialect.equals("postgresql")
                        ? "function" : "procedure") || rootId != null) {
                    throw new IllegalStateException("mutation package has no unique whole-request routine");
                }
                rootId = id;
            }
        }
        if (rootId == null) {
            throw new IllegalStateException("mutation package has no whole-request routine for " + dialect);
        }
        if (!handlers.keySet().equals(expected)) {
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(handlers.keySet());
            Set<String> extra = new HashSet<>(handlers.keySet());
            extra.removeAll(expected);
            throw new IllegalStateException("mutation package handler inventory differs from reviewed model: "
                    + "missing=" + missing + ", extra=" + extra);
        }
        Set<String> reachable = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(rootId);
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!reachable.add(id)) {
                continue;
            }
            JsonNode object = objects.get(id);
            if (object == null) {
                throw new IllegalStateException("mutation package dependency is absent from inventory: " + id);
            }
            JsonNode dependencies = object.path("dependsOn");
            if (!dependencies.isArray()) {
                throw new IllegalStateException("mutation package object has no dependency list: " + id);
            }
            for (JsonNode dependency : dependencies) {
                if (!dependency.isTextual() || dependency.asText().isBlank()) {
                    throw new IllegalStateException("mutation package object has an invalid dependency: " + id);
                }
                pending.add(dependency.asText());
            }
        }
        for (Map.Entry<String, String> handler : handlers.entrySet()) {
            if (!reachable.contains(handler.getValue())) {
                throw new IllegalStateException("mutation handler is not reachable from GraphQL entry point: "
                        + handler.getKey());
            }
        }
    }

    private static void verifyPackagedSql(
            TitanGraphqlModelDocument document,
            Path packageDirectory,
            JsonNode inventory,
            JsonNode manifest,
            String dialect
    ) throws IOException {
        JsonNode root = null;
        JsonNode identity = null;
        List<JsonNode> handlers = new ArrayList<>();
        for (JsonNode object : inventory.path("objects")) {
            if (!dialect.equals(object.path("dialect").asText())) {
                continue;
            }
            String source = object.path("sourceEntryPoint").asText();
            if (object.path("name").asText().equals("execute_graphql_request")
                    && source.startsWith(ENGINE_PACKAGE)) {
                if (root != null) {
                    throw new IllegalStateException("mutation package has duplicate whole-request SQL");
                }
                root = object;
            } else if (object.path("name").asText().equals("mutation_registry_identity")
                    && source.startsWith(ENGINE_PACKAGE)) {
                if (identity != null || !object.path("kind").asText().equals("function")) {
                    throw new IllegalStateException("mutation package has no unique registry identity function");
                }
                identity = object;
            } else if (source.startsWith(HANDLER_PACKAGE)) {
                handlers.add(object);
            }
        }
        if (root == null || identity == null) {
            throw new IllegalStateException("mutation package lacks dispatch or registry identity SQL for " + dialect);
        }
        String script = Files.readString(packageDirectory.resolve(dialect)
                .resolve("R__titan_020_routines.sql"), StandardCharsets.UTF_8);
        String rootSql = verifySourceSql(root, manifest, script, dialect);
        String identitySql = verifySourceSql(identity, manifest, script, dialect);
        for (JsonNode handler : handlers) {
            verifySourceSql(handler, manifest, script, dialect);
        }
        String registryHash = TitanGraphqlModelDocumentJson.mutationRegistryHash(document);
        String identityAssignment = dialect.equals("postgresql")
                ? "RETURN '" + registryHash + "';"
                : "SET __titan_return_value = '" + registryHash + "';";
        if (!identitySql.contains(identityAssignment)) {
            throw new IllegalStateException("mutation package registry identity differs from reviewed model");
        }
        verifyDispatchSql(document, rootSql);
    }

    static String verifySourceSql(JsonNode object, JsonNode manifest, String script, String dialect) {
        String path = object.path("sourceInputPath").asText();
        String hash = object.path("sqlHash").asText();
        String prefix = dialect + "/";
        if (!path.startsWith(prefix) || path.indexOf('/', prefix.length()) >= 0
                || !path.endsWith(".sql") || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("mutation package has an invalid SQL source reference");
        }
        int manifestMatches = 0;
        for (JsonNode input : manifest.path("sourceInputs")) {
            if (path.equals(input.path("path").asText())) {
                if (!dialect.equals(input.path("dialect").asText())
                        || !hash.equals(input.path("sha256").asText())) {
                    throw new IllegalStateException("mutation package SQL source hash differs from manifest");
                }
                manifestMatches++;
            }
        }
        if (manifestMatches != 1) {
            throw new IllegalStateException("mutation package SQL source is not unique in manifest: " + path);
        }
        String marker = "-- titan:source-file:" + path.substring(prefix.length()) + "\n";
        int location = script.indexOf(marker);
        if (location < 0 || script.lastIndexOf(marker) != location) {
            throw new IllegalStateException("mutation package SQL source is missing or duplicated: " + path);
        }
        int start = location + marker.length();
        int next = script.indexOf("\n-- titan:source-file:", start);
        String sql = next < 0 ? script.substring(start) : script.substring(start, next);
        if (next < 0 && sql.endsWith("\n")) {
            sql = sql.substring(0, sql.length() - 1);
        }
        if (!sha256(sql).equals(hash)) {
            throw new IllegalStateException("mutation package SQL bytes differ from object inventory: " + path);
        }
        return sql;
    }

    static void verifyDispatchSql(TitanGraphqlModelDocument document, String rootSql) {
        Map<String, Integer> branches = dispatchBranches(rootSql, DISPATCH, "execution");
        Map<String, Integer> preflight = dispatchBranches(rootSql, PREFLIGHT_DISPATCH, "prevalidation");
        Set<String> expected = new HashSet<>();
        for (TitanGraphqlMutationDocument mutation : document.mutations()) {
            if (!expected.add(mutation.name())) {
                throw new IllegalStateException("reviewed model has duplicate mutation: " + mutation.name());
            }
        }
        if (!branches.keySet().equals(expected)) {
            throw new IllegalStateException("mutation package dispatch differs from reviewed model: "
                    + "expected=" + expected + ", actual=" + branches.keySet());
        }
        if (!preflight.keySet().equals(expected)) {
            throw new IllegalStateException("mutation package prevalidation differs from reviewed model: "
                    + "expected=" + expected + ", actual=" + preflight.keySet());
        }
        List<Map.Entry<String, Integer>> ordered = new ArrayList<>(branches.entrySet());
        for (TitanGraphqlMutationDocument mutation : document.mutations()) {
            if (mutation.operation() != TitanGraphqlMutationDocument.MutationDocumentOperation.UPDATE) {
                continue;
            }
            TitanGraphqlTypeDocument type = document.types().stream()
                    .filter(candidate -> candidate.name().equals(mutation.type()))
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                            "reviewed update has no target type: " + mutation.name()));
            int branchIndex = 0;
            while (!ordered.get(branchIndex).getKey().equals(mutation.name())) {
                branchIndex++;
            }
            int start = ordered.get(branchIndex).getValue();
            int end = branchIndex + 1 < ordered.size() ? ordered.get(branchIndex + 1).getValue()
                    : rootSql.length();
            String branch = rootSql.substring(start, end);
            String table = type.schema() + "."
                    + (type.physicalTable().isBlank() ? type.table() : type.physicalTable());
            String prefix = "UPDATE " + table + " SET ";
            int updateStart = branch.indexOf(prefix);
            if (updateStart < 0) {
                throw new IllegalStateException("mutation package update has no modeled table effect: "
                        + mutation.name());
            }
            int updateEnd = branch.indexOf(" WHERE ", updateStart + prefix.length());
            if (updateEnd < 0) {
                throw new IllegalStateException("mutation package update has no target predicate: "
                        + mutation.name());
            }
            int predicateEnd = branch.indexOf(';', updateEnd);
            if (predicateEnd < 0) {
                throw new IllegalStateException("mutation package update has no complete target predicate: "
                        + mutation.name());
            }
            String assignments = branch.substring(updateStart + prefix.length(), updateEnd);
            String predicate = branch.substring(updateEnd + " WHERE ".length(), predicateEnd);
            if (mutation.input() == null) {
                for (TitanGraphqlMutationDocument.MutationDocumentArgument argument : mutation.arguments()) {
                    if (argument.key()) {
                        verifyPredicate(predicate, argument.column(), mutation.name());
                    } else {
                        verifyAssignment(assignments, argument.column(), mutation.name());
                    }
                }
            } else {
                for (TitanGraphqlMutationDocument.MutationDocumentInputBinding binding : mutation.inputBindings()) {
                    if (binding.key()) {
                        verifyPredicate(predicate, binding.column(), mutation.name());
                    } else {
                        verifyAssignment(assignments, binding.column(), mutation.name());
                    }
                }
            }
        }
    }

    private static Map<String, Integer> dispatchBranches(String sql, Pattern pattern, String phase) {
        Map<String, Integer> branches = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(sql);
        while (matcher.find()) {
            if (branches.putIfAbsent(matcher.group(1), matcher.start()) != null) {
                throw new IllegalStateException("mutation package has duplicate " + phase + " dispatch: "
                        + matcher.group(1));
            }
        }
        return branches;
    }

    private static void verifyAssignment(String assignments, String column, String mutation) {
        if (!Pattern.compile("(?<![a-zA-Z0-9_])" + Pattern.quote(column) + "\\s*=")
                .matcher(assignments).find()) {
            throw new IllegalStateException("mutation package update lacks modeled column '"
                    + column + "': " + mutation);
        }
    }

    private static void verifyPredicate(String predicate, String column, String mutation) {
        if (!Pattern.compile("(?<![a-zA-Z0-9_])" + Pattern.quote(column) + "\\s*=")
                .matcher(predicate).find()) {
            throw new IllegalStateException("mutation package update lacks modeled key '"
                    + column + "': " + mutation);
        }
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is not available", unavailable);
        }
    }
}
