package io.titan.graphql.codegen;

import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import java.nio.file.Files;
import java.nio.file.Path;

/** Command-line entry point used by Gradle to materialize the isolated database-engine binding. */
public final class TitanGraphqlDatabaseEngineSourceGeneratorCli {

    private TitanGraphqlDatabaseEngineSourceGeneratorCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: <model.yaml> <output.java> <runtime-identity.sha256>");
        }
        Path modelFile = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Path runtimeIdentityFile = Path.of(args[2]);
        TitanGraphqlModelDocument model = TitanGraphqlModelDocumentYaml.parse(Files.readString(modelFile));
        String generated = TitanGraphqlDatabaseEngineSourceGenerator.generate(
                model, Files.readString(runtimeIdentityFile).trim());
        Files.createDirectories(output.getParent());
        Files.writeString(output, generated);
    }
}
