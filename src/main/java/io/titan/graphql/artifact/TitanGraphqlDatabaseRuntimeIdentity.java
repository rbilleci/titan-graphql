package io.titan.graphql.artifact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Exact build identity embedded in a generated whole-request database routine.
 *
 * <p>This sidecar is intentionally distinct from {@link TitanGraphqlPackageBinding}: the binding
 * attests the reviewed model and Titan package metadata, while this value is calculated before
 * transpilation and supplied to the generated routine as a per-call expectation. A connection
 * pool therefore cannot keep serving a replaced routine merely because it was healthy when the
 * pool was initialized.</p>
 */
public final class TitanGraphqlDatabaseRuntimeIdentity {

    public static final String FILE_NAME = "titan-graphql-database-runtime-identity.sha256";

    private TitanGraphqlDatabaseRuntimeIdentity() {
    }

    public static String read(Path packageDirectory) {
        if (packageDirectory == null) {
            throw new IllegalArgumentException("package directory is required");
        }
        Path identityFile = packageDirectory.resolve(FILE_NAME);
        try {
            return require(Files.readString(identityFile, StandardCharsets.UTF_8).trim());
        } catch (IOException failure) {
            throw new IllegalStateException("database runtime identity could not be read from "
                    + identityFile.toAbsolutePath() + " — bind the whole-request package before serving", failure);
        }
    }

    public static void write(Path packageDirectory, String identity) {
        if (packageDirectory == null) {
            throw new IllegalArgumentException("package directory is required");
        }
        String verified = require(identity);
        try {
            Files.createDirectories(packageDirectory);
            Files.writeString(packageDirectory.resolve(FILE_NAME), verified + "\n", StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("database runtime identity could not be written to "
                    + packageDirectory.toAbsolutePath(), failure);
        }
    }

    public static String require(String identity) {
        if (identity == null || identity.matches("[0-9a-f]{64}") == false) {
            throw new IllegalArgumentException("database runtime identity must be a lowercase SHA-256 value");
        }
        return identity;
    }
}
