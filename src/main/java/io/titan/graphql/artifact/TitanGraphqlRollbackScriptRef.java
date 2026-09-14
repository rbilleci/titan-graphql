package io.titan.graphql.artifact;

/**
 * The packaged rollback script for one dialect ({@code titan-rollback.<dialect>.sql} emitted
 * by {@code titanPackage} next to the metadata JSON files).
 *
 * <p>Since core's B-5 fix (titan 0933913, TG-BLK-008) the {@code titan-artifact.json} manifest
 * carries an additive {@code rollbackScripts[]} array — {@code {dialect, path, statementCount,
 * sha256}} where {@code sha256} is over the script's RAW UTF-8 BYTES — so the manifest is the
 * integrity authority for the script, not the filename convention. When this ref is built from
 * a manifest entry, {@code statementCount} and {@code contentSha256} are the manifest's
 * authoritative values (copied verbatim, not recomputed), and {@link #status} reports whether
 * the on-disk file's raw-byte hash actually matches that authority.</p>
 *
 * <p>{@code present} reports only whether the script file existed on disk; it is intentionally
 * NOT enough to trust the script — a file that exists but whose bytes drifted from the manifest
 * hash is {@link IntegrityStatus#DRIFTED}, not a clean present. Always consult {@link #status}
 * (or {@link #integrityVerified()}) before treating a rollback script as usable.</p>
 */
public record TitanGraphqlRollbackScriptRef(
        String dialect,
        String path,
        boolean present,
        int statementCount,
        String contentSha256,
        IntegrityStatus status
) {

    /**
     * How the on-disk rollback script relates to the manifest's integrity authority. The first
     * three states describe a script the manifest's {@code rollbackScripts[]} array links and
     * pins a raw-byte {@code sha256} for; the last covers older packages / fixtures whose
     * manifest predates the array, where the script is discovered by filename convention and its
     * summary recomputed (no manifest hash to verify against).
     */
    public enum IntegrityStatus {
        /** Manifest-linked, on-disk file present, and its raw-byte sha256 matches the manifest. */
        VERIFIED,
        /** Manifest-linked and the file is present, but its raw-byte sha256 DIVERGES from the manifest. */
        DRIFTED,
        /** Manifest-linked but no file exists on disk at the manifest's path. */
        ABSENT,
        /**
         * No manifest {@code rollbackScripts[]} entry for this dialect (older package / fixture):
         * discovered by the {@code titan-rollback.<dialect>.sql} filename convention with its
         * summary recomputed locally; there is no manifest hash to verify the file against.
         */
        LEGACY_UNLINKED
    }

    public TitanGraphqlRollbackScriptRef {
        dialect = requireText(dialect, "rollbackScript.dialect");
        path = requireText(path, "rollbackScript.path");
        if (statementCount < 0) {
            throw new IllegalArgumentException("rollbackScript.statementCount cannot be negative");
        }
        contentSha256 = contentSha256 == null ? "" : contentSha256;
        status = status == null ? IntegrityStatus.LEGACY_UNLINKED : status;
    }

    /**
     * A manifest-linked script whose on-disk raw bytes hash exactly to the manifest's authority
     * ({@link IntegrityStatus#VERIFIED}). Drifted, absent and legacy-unlinked scripts all read
     * {@code false} — only a verified script may be trusted as the integrity-checked rollback.
     */
    public boolean integrityVerified() {
        return status == IntegrityStatus.VERIFIED;
    }

    /** A manifest-linked script present on disk whose raw bytes DIVERGE from the manifest hash. */
    public boolean drifted() {
        return status == IntegrityStatus.DRIFTED;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
