package io.titan.graphql.management;

import io.titan.graphql.artifact.TitanGraphqlEntryPointRef;
import io.titan.graphql.artifact.TitanGraphqlRollbackScriptRef;
import io.titan.graphql.artifact.TitanGraphqlVerificationDiagnostic;
import java.util.List;

/**
 * The management plane's durable view of the real core package evidence behind one artifact
 * set: the raw {@code titan-install-verification.json} status and diagnostics, the packaged
 * entry points (from manifest + object inventory), and the rollback script refs (from the
 * manifest's integrity-linked {@code rollbackScripts[]}, titan 0933913: each ref carries the
 * manifest's authoritative statement count and raw-byte sha256 plus an integrity status that
 * surfaces drift between the on-disk script and that manifest hash). Captured when GAP-005
 * metadata is recorded so deployment decisions and refusals trace to core's actual outputs.
 */
public record TitanGraphqlArtifactEvidenceRef(
        String artifactSetId,
        String artifactId,
        String verificationStatus,
        List<TitanGraphqlVerificationDiagnostic> verificationDiagnostics,
        List<TitanGraphqlEntryPointRef> entryPoints,
        List<TitanGraphqlRollbackScriptRef> rollbackScripts
) {
    public TitanGraphqlArtifactEvidenceRef {
        artifactSetId = ManagementSupport.requireText(artifactSetId, "artifactEvidence.artifactSetId");
        artifactId = ManagementSupport.requireText(artifactId, "artifactEvidence.artifactId");
        verificationStatus = ManagementSupport.requireText(verificationStatus, "artifactEvidence.verificationStatus");
        verificationDiagnostics = verificationDiagnostics == null ? List.of() : List.copyOf(verificationDiagnostics);
        entryPoints = entryPoints == null ? List.of() : List.copyOf(entryPoints);
        rollbackScripts = rollbackScripts == null ? List.of() : List.copyOf(rollbackScripts);
    }
}
