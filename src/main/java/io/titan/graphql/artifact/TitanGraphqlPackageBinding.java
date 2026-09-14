package io.titan.graphql.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Exact, reproducible binding between one reviewed GraphQL model and one Titan SQL package.
 *
 * <p>Core's GAP-005 metadata proves the integrity of the generated SQL package, while the
 * model semantic hash proves the normalized GraphQL contract. This sidecar links those two
 * authorities. It deliberately contains no timestamp or machine path, so identical inputs
 * produce identical bytes and the binding itself has a stable deployment fingerprint.</p>
 */
public record TitanGraphqlPackageBinding(
        String schemaVersion,
        String modelName,
        String modelVersion,
        String modelSemanticSha256,
        String artifactId,
        String manifestContentSha256,
        String sourceInputsSha256
) {
    public static final String SCHEMA_VERSION = "titan.graphql.package-binding.v1";
    public static final String FILE_NAME = "titan-graphql-package.json";

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public TitanGraphqlPackageBinding {
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported Titan GraphQL package binding schema '"
                    + schemaVersion + "'");
        }
        modelName = requireText(modelName, "modelName");
        modelVersion = requireText(modelVersion, "modelVersion");
        modelSemanticSha256 = requireSha256(modelSemanticSha256, "modelSemanticSha256");
        artifactId = requireText(artifactId, "artifactId");
        manifestContentSha256 = requireSha256(manifestContentSha256, "manifestContentSha256");
        sourceInputsSha256 = requireSha256(sourceInputsSha256, "sourceInputsSha256");
    }

    public static TitanGraphqlPackageBinding create(
            TitanGraphqlModelDocument document,
            TitanGraphqlGap005ArtifactMetadata metadata
    ) {
        if (document == null) {
            throw new IllegalArgumentException("model document is required");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("Titan package metadata is required");
        }
        return new TitanGraphqlPackageBinding(
                SCHEMA_VERSION,
                document.metadata().name(),
                document.metadata().version(),
                TitanGraphqlModelDocumentJson.semanticHash(document),
                metadata.artifactId(),
                metadata.manifestContentHash(),
                metadata.sourceInputsHash());
    }

    public static TitanGraphqlPackageBinding read(Path packageDirectory) {
        if (packageDirectory == null) {
            throw new IllegalArgumentException("package directory is required");
        }
        Path bindingPath = packageDirectory.resolve(FILE_NAME);
        try {
            return JSON.readValue(Files.readString(bindingPath, StandardCharsets.UTF_8),
                    TitanGraphqlPackageBinding.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Titan GraphQL package binding could not be read from "
                    + bindingPath.toAbsolutePath() + " — run titanGraphqlBindPackage for the reviewed model", ex);
        }
    }

    public void write(Path packageDirectory) {
        if (packageDirectory == null) {
            throw new IllegalArgumentException("package directory is required");
        }
        try {
            Files.createDirectories(packageDirectory);
            Path target = packageDirectory.resolve(FILE_NAME);
            Path temporary = Files.createTempFile(packageDirectory, FILE_NAME + ".", ".tmp");
            try {
                Files.writeString(temporary, canonicalJson() + "\n", StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Titan GraphQL package binding could not be written to "
                    + packageDirectory.toAbsolutePath(), ex);
        }
    }

    /** Verifies both sides of the binding and fails before a database connection is opened. */
    public void verify(TitanGraphqlModelDocument document, TitanGraphqlGap005ArtifactMetadata metadata) {
        TitanGraphqlPackageBinding expected = create(document, metadata);
        requireMatch(modelName, expected.modelName, "model name");
        requireMatch(modelVersion, expected.modelVersion, "model version");
        requireMatch(modelSemanticSha256, expected.modelSemanticSha256, "model semantic hash");
        requireMatch(artifactId, expected.artifactId, "Titan artifact id");
        requireMatch(manifestContentSha256, expected.manifestContentSha256, "manifest content hash");
        requireMatch(sourceInputsSha256, expected.sourceInputsSha256, "source inputs hash");
    }

    /** Stable identity for the combined reviewed-model and compiled-package deployment. */
    public String deploymentFingerprint() {
        return sha256(canonicalJson());
    }

    public String canonicalJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Titan GraphQL package binding could not be serialized", ex);
        }
    }

    private static void requireMatch(String actual, String expected, String label) {
        if (!actual.equals(expected)) {
            throw new IllegalStateException("Titan GraphQL package binding " + label + " mismatch: bound '"
                    + actual + "' but current input is '" + expected + "'");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Titan GraphQL package binding " + field + " is required");
        }
        return value;
    }

    private static String requireSha256(String value, String field) {
        String required = requireText(value, field);
        if (!required.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Titan GraphQL package binding " + field
                    + " must be a lowercase SHA-256 value");
        }
        return required;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is not available", ex);
        }
    }
}
