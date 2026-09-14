package io.titan.graphql.artifact;

/**
 * One diagnostic from the real {@code titan-install-verification.json} written by
 * {@code titanPackage}/{@code titanVerifyInstall}. Carried so that deployment refusals can
 * cite core's actual reason (for example {@code TITAN-GAP005-VERIFY-PENDING}) instead of a
 * generic status name.
 */
public record TitanGraphqlVerificationDiagnostic(
        String dialect,
        String code,
        String message
) {
    public TitanGraphqlVerificationDiagnostic {
        dialect = dialect == null ? "" : dialect;
        code = code == null ? "" : code;
        message = message == null ? "" : message;
    }
}
