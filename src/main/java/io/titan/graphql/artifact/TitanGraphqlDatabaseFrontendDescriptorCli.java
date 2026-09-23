package io.titan.graphql.artifact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Emits the immutable frontend-selection descriptor for one verified whole-request package.
 *
 * <p>This runs at build/deployment time. The runtime HTTP host only reads this small descriptor;
 * it does not load a GraphQL model, inspect arbitrary routines, or choose an entry point from
 * user configuration.</p>
 */
public final class TitanGraphqlDatabaseFrontendDescriptorCli {

    private static final String SCHEMA_VERSION = "titan.graphql.database-frontend-deployment.v1";
    private static final List<String> WHOLE_REQUEST_PARAMETERS = List.of(
            "java.sql.Connection",
            "java.lang.String",
            "java.lang.String",
            "java.lang.String",
            "java.lang.String",
            "java.lang.String",
            "boolean",
            "java.lang.String",
            "java.lang.String",
            "java.lang.String"
    );

    private TitanGraphqlDatabaseFrontendDescriptorCli() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "usage: TitanGraphqlDatabaseFrontendDescriptorCli <package-directory>"
                            + " <postgresql|mysql> <descriptor-file>");
        }
        Path packageDirectory = Path.of(args[0]);
        String dialect = args[1].trim().toLowerCase(java.util.Locale.ROOT);
        if (dialect.equals("postgres") || dialect.equals("postgresql")) {
            dialect = "postgresql";
        } else if (dialect.equals("mysql") == false) {
            throw new IllegalArgumentException("descriptor dialect must be postgresql or mysql");
        }
        Path descriptor = Path.of(args[2]);
        TitanGraphqlGap005ArtifactMetadata metadata = TitanGraphqlGap005ArtifactMetadata.read(
                packageDirectory, TitanGraphqlArtifactsDirectory.displayRoot(packageDirectory));
        TitanGraphqlPackageBinding binding = TitanGraphqlPackageBinding.read(packageDirectory);
        verifyBindingAgainstMetadata(binding, metadata);
        if (metadata.verificationStatus().equals("passed") == false) {
            throw new IllegalStateException("package install verification is not passed: "
                    + metadata.verificationStatus());
        }
        TitanGraphqlSqlRoutineRef routine = wholeRequestRoutine(metadata, dialect);
        if (descriptor.getParent() != null) {
            Files.createDirectories(descriptor.getParent());
        }
        Files.writeString(descriptor, String.join("\n",
                "schema-version=" + SCHEMA_VERSION,
                "database-dialect=" + dialect,
                "entry-point-schema=" + routine.schemaName(),
                "entry-point-routine=" + routine.routineName(),
                "model-semantic-sha256=" + binding.modelSemanticSha256(),
                "runtime-identity-sha256=" + TitanGraphqlDatabaseRuntimeIdentity.read(packageDirectory),
                "package-identity-sha256=" + TitanGraphqlDatabasePackageIdentity.read(packageDirectory),
                "deployment-fingerprint=" + binding.deploymentFingerprint(),
                "package-artifact-id=" + binding.artifactId(),
                "") , StandardCharsets.UTF_8);
        System.out.println("Wrote database frontend descriptor " + descriptor.toAbsolutePath()
                + " for " + routine.qualifiedName() + " (" + dialect + ")");
    }

    private static void verifyBindingAgainstMetadata(
            TitanGraphqlPackageBinding binding,
            TitanGraphqlGap005ArtifactMetadata metadata
    ) {
        if (binding.artifactId().equals(metadata.artifactId()) == false
                || binding.manifestContentSha256().equals(metadata.manifestContentHash()) == false
                || binding.sourceInputsSha256().equals(metadata.sourceInputsHash()) == false) {
            throw new IllegalStateException(
                    "package binding does not match the installed package metadata; bind the reviewed model again");
        }
    }

    private static TitanGraphqlSqlRoutineRef wholeRequestRoutine(
            TitanGraphqlGap005ArtifactMetadata metadata,
            String dialect
    ) {
        String objectKind = dialect.equals("postgresql") ? "function" : "procedure";
        TitanGraphqlSqlRoutineRef selected = null;
        for (TitanGraphqlEntryPointRef entryPoint : metadata.entryPoints()) {
            if (entryPoint.className().startsWith("io.titan.graphql.database.generated.") == false
                    || entryPoint.methodName().equals("executeGraphqlRequest") == false
                    || entryPoint.parameterTypes().equals(WHOLE_REQUEST_PARAMETERS) == false) {
                continue;
            }
            for (TitanGraphqlSqlRoutineRef routine : entryPoint.routines()) {
                if (routine.dialect().equals(dialect)
                        && routine.objectKind().equals(objectKind)
                        && routine.routineName().equals("execute_graphql_request")) {
                    if (selected != null) {
                        throw new IllegalStateException("package has multiple generated whole-request "
                                + dialect + " entry points");
                    }
                    selected = routine;
                }
            }
        }
        if (selected == null) {
            throw new IllegalStateException("package does not publish the generated "
                    + dialect + " whole-request entry point");
        }
        return selected;
    }
}
