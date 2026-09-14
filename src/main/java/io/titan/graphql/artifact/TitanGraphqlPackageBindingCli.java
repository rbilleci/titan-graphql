package io.titan.graphql.artifact;

import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import io.titan.graphql.validation.TitanGraphqlModelDocumentValidator;
import io.titan.graphql.validation.TitanGraphqlValidationReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Command-line entry point used by the local Gradle model-to-package pipeline. */
public final class TitanGraphqlPackageBindingCli {
    private TitanGraphqlPackageBindingCli() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: TitanGraphqlPackageBindingCli <model-yaml> <package-directory>");
        }
        Path modelPath = Path.of(args[0]);
        Path packageDirectory = Path.of(args[1]);
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(Files.readString(modelPath));
        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);
        if (report.blocksDeployment()) {
            throw new IllegalStateException("reviewed model " + modelPath.toAbsolutePath() + " has "
                    + report.errorCount() + " deployment-blocking validation error(s)");
        }
        TitanGraphqlGap005ArtifactMetadata metadata = TitanGraphqlGap005ArtifactMetadata.read(
                packageDirectory, TitanGraphqlArtifactsDirectory.displayRoot(packageDirectory));
        TitanGraphqlPackageBinding binding = TitanGraphqlPackageBinding.create(document, metadata);
        binding.write(packageDirectory);
        System.out.println("Bound model " + binding.modelName() + "@" + binding.modelVersion()
                + " to Titan package " + binding.artifactId()
                + " (deployment fingerprint " + binding.deploymentFingerprint() + ")");
    }
}
