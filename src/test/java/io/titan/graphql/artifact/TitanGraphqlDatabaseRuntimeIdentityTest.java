package io.titan.graphql.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TitanGraphqlDatabaseRuntimeIdentityTest {

    private static final String IDENTITY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @TempDir
    Path temporaryDirectory;

    @Test
    void sidecarRoundTripsOnlyLowercaseSha256Values() {
        TitanGraphqlDatabaseRuntimeIdentity.write(temporaryDirectory, IDENTITY);

        assertEquals(IDENTITY, TitanGraphqlDatabaseRuntimeIdentity.read(temporaryDirectory));
        assertThrows(IllegalArgumentException.class,
                () -> TitanGraphqlDatabaseRuntimeIdentity.write(temporaryDirectory, IDENTITY.toUpperCase()));
        assertThrows(IllegalArgumentException.class,
                () -> TitanGraphqlDatabaseRuntimeIdentity.require("not-an-identity"));
    }

    @Test
    void canonicalBuildIdentityChangesForDialectInputBytesAndInputRole() throws Exception {
        Path model = Path.of("src/test/resources/graphql/demo-blog.titan.graphql.yaml");
        Path source = temporaryDirectory.resolve("engine.java");
        Files.writeString(source, "class Engine { }\n");

        Path first = generate(model, "postgresql", "common-engine", source, "first.sha256");
        Path repeat = generate(model, "postgresql", "common-engine", source, "repeat.sha256");
        Path otherDialect = generate(model, "mysql", "common-engine", source, "mysql.sha256");
        Path otherRole = generate(model, "postgresql", "schema-generator", source, "role.sha256");
        Files.writeString(source, "class Engine { int version = 2; }\n");
        Path changedInput = generate(model, "postgresql", "common-engine", source, "changed.sha256");

        assertEquals(Files.readString(first), Files.readString(repeat));
        assertNotEquals(Files.readString(first), Files.readString(otherDialect));
        assertNotEquals(Files.readString(first), Files.readString(otherRole));
        assertNotEquals(Files.readString(first), Files.readString(changedInput));
    }

    private Path generate(Path model, String dialect, String label, Path source, String outputName)
            throws Exception {
        Path output = temporaryDirectory.resolve(outputName);
        TitanGraphqlDatabaseRuntimeIdentityCli.main(new String[] {
                model.toString(), dialect, output.toString(), label, source.toString()
        });
        return output;
    }
}
