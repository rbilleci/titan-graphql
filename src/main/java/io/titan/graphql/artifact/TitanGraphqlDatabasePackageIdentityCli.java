package io.titan.graphql.artifact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Produces the identity migration for a whole-request package's complete generated SQL inventory.
 *
 * <p>The migration is deliberately excluded from its own digest, avoiding a hash cycle. Every
 * other SQL file and its normalized relative path are included, so the identity covers all
 * emitted routines, helpers, and generated custom-mutation implementations.</p>
 */
public final class TitanGraphqlDatabasePackageIdentityCli {
    public static final String IDENTITY_SQL_FILE = "R__titan_005_graphql_package_identity.sql";
    public static final String ENTRY_POINT = "public.execute_graphql_request";

    private TitanGraphqlDatabasePackageIdentityCli() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException("usage: <postgresql|mysql> <generated-sql-directory> "
                    + "<identity-output.sha256> <identity-migration.sql>");
        }
        String dialect = dialect(args[0]);
        Path sqlDirectory = Path.of(args[1]).toAbsolutePath().normalize();
        Path identityOutput = Path.of(args[2]);
        Path identityMigration = Path.of(args[3]).toAbsolutePath().normalize();
        if (Files.isDirectory(sqlDirectory) == false) {
            throw new IllegalArgumentException("generated SQL directory does not exist: " + sqlDirectory);
        }

        String identity = inventoryIdentity(dialect, sqlDirectory, identityMigration);
        if (identityOutput.getParent() != null) {
            Files.createDirectories(identityOutput.getParent());
        }
        if (identityMigration.getParent() != null) {
            Files.createDirectories(identityMigration.getParent());
        }
        Files.writeString(identityOutput, identity + "\n", StandardCharsets.UTF_8);
        Files.writeString(identityMigration, identityMigrationSql(dialect, identity), StandardCharsets.UTF_8);
        System.out.println("Wrote database package identity " + identityOutput.toAbsolutePath());
    }

    static String inventoryIdentity(String dialect, Path sqlDirectory, Path identityMigration) throws IOException {
        String normalizedDialect = dialect(dialect);
        Path normalizedDirectory = sqlDirectory.toAbsolutePath().normalize();
        Path normalizedMigration = identityMigration.toAbsolutePath().normalize();
        List<Path> sqlFiles;
        try (var paths = Files.walk(normalizedDirectory)) {
            sqlFiles = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .filter(path -> path.toAbsolutePath().normalize().equals(normalizedMigration) == false)
                    .sorted(Comparator.comparing(path -> normalizedDirectory.relativize(path)
                            .toString().replace('\\', '/')))
                    .toList();
        }
        if (sqlFiles.isEmpty()) {
            throw new IllegalArgumentException("generated SQL inventory is empty: " + normalizedDirectory);
        }

        MessageDigest digest = sha256();
        update(digest, "schema", "titan.graphql.database-package-identity.v1");
        update(digest, "dialect", normalizedDialect);
        update(digest, "entry-point", ENTRY_POINT);
        for (Path sqlFile : sqlFiles) {
            update(digest, "sql-path", normalizedDirectory.relativize(sqlFile).toString().replace('\\', '/'));
            digest.update(Files.readAllBytes(sqlFile));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String identityMigrationSql(String dialect, String identity) {
        String normalizedDialect = dialect(dialect);
        String verifiedIdentity = TitanGraphqlDatabasePackageIdentity.require(identity);
        if (normalizedDialect.equals("postgresql")) {
            return """
                    -- titan:database-package-identity:v1
                    CREATE TABLE IF NOT EXISTS public.titan_graphql_package_identity (
                        entry_point TEXT PRIMARY KEY,
                        package_identity CHAR(64) NOT NULL
                    );
                    INSERT INTO public.titan_graphql_package_identity (entry_point, package_identity)
                    VALUES ('public.execute_graphql_request', '%s')
                    ON CONFLICT (entry_point) DO UPDATE SET package_identity = EXCLUDED.package_identity;
                    """.formatted(verifiedIdentity);
        }
        return """
                -- titan:database-package-identity:v1
                CREATE TABLE IF NOT EXISTS titan_graphql_package_identity (
                    entry_point VARCHAR(191) NOT NULL,
                    package_identity CHAR(64) NOT NULL,
                    PRIMARY KEY (entry_point)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                INSERT INTO titan_graphql_package_identity (entry_point, package_identity)
                VALUES ('public.execute_graphql_request', '%s')
                ON DUPLICATE KEY UPDATE package_identity = '%s';
                """.formatted(verifiedIdentity, verifiedIdentity);
    }

    private static String dialect(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("postgres")) {
            normalized = "postgresql";
        }
        if (normalized.equals("postgresql") == false && normalized.equals("mysql") == false) {
            throw new IllegalArgumentException("database package identity dialect must be postgresql or mysql");
        }
        return normalized;
    }

    private static void update(MessageDigest digest, String label, String value) {
        digest.update(label.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 digest is not available", unavailable);
        }
    }
}
