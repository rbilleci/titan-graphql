package io.titan.graphql.codegen;

import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Local build entry point for reproducible model-derived Titan routine source generation. */
public final class TitanGraphqlRoutineSourceGeneratorCli {
    private TitanGraphqlRoutineSourceGeneratorCli() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "usage: TitanGraphqlRoutineSourceGeneratorCli <model-yaml> <generated-java>");
        }
        Path modelPath = Path.of(args[0]);
        Path outputPath = Path.of(args[1]);
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(Files.readString(modelPath));
        String source = TitanGraphqlRoutineSourceGenerator.generate(document);
        Files.createDirectories(outputPath.getParent());
        Path temporary = Files.createTempFile(outputPath.getParent(), outputPath.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temporary, source, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, outputPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, outputPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        System.out.println("Generated Titan GraphQL routines for " + document.metadata().name()
                + " at " + outputPath.toAbsolutePath());
    }
}
