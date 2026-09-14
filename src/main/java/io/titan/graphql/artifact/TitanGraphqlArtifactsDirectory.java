package io.titan.graphql.artifact;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the directory holding the real core package artifacts (the {@code titanPackage}
 * output: {@code titan-artifact.json}, {@code titan-object-inventory.json},
 * {@code titan-install-plan.json}, {@code titan-install-verification.json} and the
 * {@code titan-rollback.<dialect>.sql} scripts).
 *
 * <p>Configuration mirrors the management transaction-log plumbing: the
 * {@code titan.graphql.artifacts.dir} system property wins, then the
 * {@code TITAN_GRAPHQL_ARTIFACTS_DIR} environment variable, then the build's default
 * {@code titanPackage} output directory ({@code build/generated/migrations/titan}).</p>
 *
 * <p>A missing or incomplete directory is an explicit, descriptive error state — never a
 * silent placeholder. Run {@code titanPackage} (and {@code titanVerifyInstall}) or point the
 * property at a package directory.</p>
 */
public final class TitanGraphqlArtifactsDirectory {

    public static final String SYSTEM_PROPERTY = "titan.graphql.artifacts.dir";
    public static final String ENVIRONMENT_VARIABLE = "TITAN_GRAPHQL_ARTIFACTS_DIR";
    public static final String DEFAULT_DIRECTORY = "build/generated/migrations/titan";

    /** Reproducible display root used when an absolute directory cannot be relativized. */
    static final String FALLBACK_DISPLAY_ROOT = "titan-package";

    private TitanGraphqlArtifactsDirectory() {
    }

    public static Path configuredDirectory() {
        String value = System.getProperty(SYSTEM_PROPERTY, "");
        if (value.isBlank()) {
            String fromEnvironment = System.getenv(ENVIRONMENT_VARIABLE);
            value = fromEnvironment == null ? "" : fromEnvironment;
        }
        if (value.isBlank()) {
            value = DEFAULT_DIRECTORY;
        }
        return Path.of(value);
    }

    /**
     * Reads the real GAP-005 package metadata from the configured artifacts directory.
     *
     * @throws IllegalStateException when the directory or any required metadata file is
     *         missing — the descriptive replacement for the deleted {@code metadataOnly}
     *         placeholder branch
     */
    public static TitanGraphqlGap005ArtifactMetadata readGap005Metadata() {
        Path directory = configuredDirectory();
        if (Files.isDirectory(directory) == false) {
            throw new IllegalStateException(
                    "Titan artifacts directory does not exist: " + directory.toAbsolutePath()
                            + " — run titanPackage (and titanVerifyInstall) first, or point -D"
                            + SYSTEM_PROPERTY + " / " + ENVIRONMENT_VARIABLE
                            + " at a Titan package directory");
        }
        List<String> missing = new ArrayList<>();
        for (TitanGraphqlArtifactKind kind : List.of(
                TitanGraphqlArtifactKind.TITAN_ARTIFACT_METADATA,
                TitanGraphqlArtifactKind.TITAN_OBJECT_INVENTORY,
                TitanGraphqlArtifactKind.TITAN_INSTALL_PLAN,
                TitanGraphqlArtifactKind.TITAN_INSTALL_VERIFICATION)) {
            String fileName = kind.defaultFileName();
            if (Files.isRegularFile(directory.resolve(fileName)) == false) {
                missing.add(fileName);
            }
        }
        if (missing.isEmpty() == false) {
            throw new IllegalStateException(
                    "Titan artifacts directory " + directory.toAbsolutePath()
                            + " is missing required package metadata files " + missing
                            + " — run titanPackage (and titanVerifyInstall) first, or point -D"
                            + SYSTEM_PROPERTY + " / " + ENVIRONMENT_VARIABLE
                            + " at a complete Titan package directory");
        }
        return TitanGraphqlGap005ArtifactMetadata.read(directory, displayRoot(directory));
    }

    /**
     * GAP-005 metadata records reproducible relative artifact roots. Relative configurations
     * are kept verbatim; absolute ones are relativized against the working directory when
     * possible, otherwise the stable {@link #FALLBACK_DISPLAY_ROOT} label is used.
     */
    public static String displayRoot(Path directory) {
        if (directory.isAbsolute() == false) {
            return normalizeSlashes(directory.toString());
        }
        Path workingDirectory = Path.of("").toAbsolutePath();
        if (directory.normalize().startsWith(workingDirectory)) {
            return normalizeSlashes(workingDirectory.relativize(directory.normalize()).toString());
        }
        return FALLBACK_DISPLAY_ROOT;
    }

    private static String normalizeSlashes(String path) {
        return path.replace('\\', '/');
    }
}
