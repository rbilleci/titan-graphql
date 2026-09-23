package io.titan.graphql.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TitanGraphqlDatabasePackageIdentityCliTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void hashesTheCompleteInventoryButExcludesItsOwnMigration() throws Exception {
        Path sqlDirectory = temporaryDirectory.resolve("sql/postgresql");
        Files.createDirectories(sqlDirectory.resolve("nested"));
        Files.writeString(sqlDirectory.resolve("R__titan_020_routines.sql"), "SELECT 1;\n");
        Files.writeString(sqlDirectory.resolve("nested/R__titan_021_mutation.sql"), "SELECT 2;\n");
        Path identityFile = temporaryDirectory.resolve("identity.sha256");
        Path migration = sqlDirectory.resolve(TitanGraphqlDatabasePackageIdentityCli.IDENTITY_SQL_FILE);

        TitanGraphqlDatabasePackageIdentityCli.main(new String[] {
                "postgresql", sqlDirectory.toString(), identityFile.toString(), migration.toString()
        });
        String original = Files.readString(identityFile).trim();
        assertTrue(original.matches("[0-9a-f]{64}"));
        assertTrue(Files.readString(migration).contains(original));
        assertFalse(Files.readString(migration).contains(temporaryDirectory.toString()));

        TitanGraphqlDatabasePackageIdentityCli.main(new String[] {
                "postgresql", sqlDirectory.toString(), identityFile.toString(), migration.toString()
        });
        assertEquals(original, Files.readString(identityFile).trim(), "the prior generated migration is excluded");

        Files.writeString(sqlDirectory.resolve("nested/R__titan_021_mutation.sql"), "SELECT 3;\n");
        TitanGraphqlDatabasePackageIdentityCli.main(new String[] {
                "postgresql", sqlDirectory.toString(), identityFile.toString(), migration.toString()
        });
        assertNotEquals(original, Files.readString(identityFile).trim(), "all other SQL changes must alter identity");
    }

    @Test
    void emitsPortableDialectSpecificMetadataMigrations() {
        String identity = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        String postgres = TitanGraphqlDatabasePackageIdentityCli.identityMigrationSql("postgresql", identity);
        String mysql = TitanGraphqlDatabasePackageIdentityCli.identityMigrationSql("mysql", identity);

        assertTrue(postgres.contains("public.titan_graphql_package_identity"));
        assertTrue(postgres.contains("ON CONFLICT"));
        assertTrue(mysql.contains("titan_graphql_package_identity"));
        assertTrue(mysql.contains("ON DUPLICATE KEY"));
        assertTrue(postgres.contains(identity));
        assertTrue(mysql.contains(identity));
    }
}
