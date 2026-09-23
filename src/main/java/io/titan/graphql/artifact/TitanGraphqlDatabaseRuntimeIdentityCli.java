package io.titan.graphql.artifact;

import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Emits a reproducible identity for one dialect-specific transpiled whole-request closure. */
public final class TitanGraphqlDatabaseRuntimeIdentityCli {

    private static final String SCHEMA_VERSION = "titan.graphql.database-runtime-identity.v1";

    private TitanGraphqlDatabaseRuntimeIdentityCli() {
    }

    /**
     * Usage: {@code <model.yaml> <postgresql|mysql> <output.sha256> (<label> <source-file>)+}.
     * Labels are part of the canonical hash so a new source/build input cannot be silently added
     * under a different name.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 5 || (args.length - 3) % 2 != 0) {
            throw new IllegalArgumentException("usage: TitanGraphqlDatabaseRuntimeIdentityCli <model.yaml> "
                    + "<postgresql|mysql> <output.sha256> (<label> <source-file>)+");
        }
        Path modelPath = Path.of(args[0]);
        String dialect = normalizedDialect(args[1]);
        Path output = Path.of(args[2]);
        TitanGraphqlModelDocument model = TitanGraphqlModelDocumentYaml.parse(
                Files.readString(modelPath, StandardCharsets.UTF_8));

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        update(digest, "schema-version", SCHEMA_VERSION);
        update(digest, "dialect", dialect);
        update(digest, "model-semantic-sha256", TitanGraphqlModelDocumentJson.semanticHash(model));
        for (int index = 3; index < args.length; index += 2) {
            String label = args[index];
            if (label.matches("[a-z0-9][a-z0-9._-]*") == false) {
                throw new IllegalArgumentException("runtime identity input label is invalid: " + label);
            }
            Path source = Path.of(args[index + 1]);
            if (Files.isRegularFile(source) == false) {
                throw new IllegalArgumentException("runtime identity input does not exist: " + source);
            }
            update(digest, "input-label", label);
            digest.update(Files.readAllBytes(source));
            digest.update((byte) 0);
        }
        String identity = HexFormat.of().formatHex(digest.digest());
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, identity + "\n", StandardCharsets.UTF_8);
        System.out.println("Wrote database runtime identity " + output.toAbsolutePath());
    }

    private static String normalizedDialect(String value) {
        String dialect = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (dialect.equals("postgres")) {
            dialect = "postgresql";
        }
        if (dialect.equals("postgresql") == false && dialect.equals("mysql") == false) {
            throw new IllegalArgumentException("runtime identity dialect must be postgresql or mysql");
        }
        return dialect;
    }

    private static void update(MessageDigest digest, String label, String value) {
        digest.update(label.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '=');
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
