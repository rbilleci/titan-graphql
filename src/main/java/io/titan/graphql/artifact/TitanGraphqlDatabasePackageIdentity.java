package io.titan.graphql.artifact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Identity of the generated SQL inventory installed by a whole-request database package. */
public final class TitanGraphqlDatabasePackageIdentity {
    public static final String FILE_NAME = "titan-graphql-database-package-identity.sha256";

    private TitanGraphqlDatabasePackageIdentity() {
    }

    public static String read(Path packageDirectory) {
        if (packageDirectory == null) {
            throw new IllegalArgumentException("package directory is required");
        }
        Path identityFile = packageDirectory.resolve(FILE_NAME);
        try {
            return require(Files.readString(identityFile, StandardCharsets.UTF_8).trim());
        } catch (IOException failure) {
            throw new IllegalStateException("database package identity could not be read from "
                    + identityFile.toAbsolutePath() + " — package the whole-request engine before serving", failure);
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
            throw new IllegalStateException("database package identity could not be written to "
                    + packageDirectory.toAbsolutePath(), failure);
        }
    }

    public static String require(String identity) {
        if (identity == null || identity.matches("[0-9a-f]{64}") == false) {
            throw new IllegalArgumentException("database package identity must be a lowercase SHA-256 value");
        }
        return identity;
    }
}
