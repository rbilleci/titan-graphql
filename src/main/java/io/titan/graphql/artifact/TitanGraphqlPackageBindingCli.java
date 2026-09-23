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
        if (args.length < 2 || args.length > 4) {
            throw new IllegalArgumentException("usage: TitanGraphqlPackageBindingCli <model-yaml> <package-directory> "
                    + "[database-runtime-identity.sha256] [database-package-identity.sha256]");
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
        if (args.length >= 3) {
            Path runtimeIdentityFile = Path.of(args[2]);
            TitanGraphqlDatabaseRuntimeIdentity.write(packageDirectory,
                    Files.readString(runtimeIdentityFile, java.nio.charset.StandardCharsets.UTF_8).trim());
        }
        if (args.length == 4) {
            Path packageIdentityFile = Path.of(args[3]);
            TitanGraphqlDatabasePackageIdentity.write(packageDirectory,
                    Files.readString(packageIdentityFile, java.nio.charset.StandardCharsets.UTF_8).trim());
        }
        System.out.println("Bound model " + binding.modelName() + "@" + binding.modelVersion()
                + " to Titan package " + binding.artifactId()
                + " (deployment fingerprint " + binding.deploymentFingerprint() + ")");
    }
}
