# Security policy

Security fixes target `main` until a versioned release policy exists. Do not disclose a suspected
vulnerability in a public issue. Private vulnerability reporting is not enabled for this repository;
contact the owner through GitHub to arrange a private channel.

## Deployment boundary

The supported serving artifact is the standalone database HTTP ZIP, not the build-tool JAR. It
contains transport and JDBC code, while the installed package owns GraphQL parsing, validation,
policy decisions, execution, and response assembly. Install the reviewed model, package, binding,
identity sidecars, and deployment descriptor together. The frontend supplies expected model,
runtime, and package identities on every call. Do not edit a descriptor to point at another
routine, and do not grant application clients direct execution rights on generated routines.

`/graphql` does not trust caller-supplied `X-Titan-*` context headers by default and assigns no
implicit role. Set `TITAN_GRAPHQL_HTTP_TRUST_REQUEST_CONTEXT_HEADERS=true` only behind an
authenticated gateway that strips client copies and injects verified identity, tenant, policy,
and deadline values. The database package rejects missing or invalid reviewed context values; it
does not fall back to another execution mode.

`/admin/graphql` is unavailable without a separately bound management descriptor and
`TITAN_GRAPHQL_ADMIN_ACCESS_TOKEN`. Enabled requests require that bearer token before GraphQL
decoding or database access. The configured admin role and actor key, not HTTP headers, determine
its identity. Keep tokens and database credentials outside source control. Put TLS and a suitable
identity gateway in front of any listener exposed beyond a trusted host or container network.
The built-in bearer-token guard does not provide multi-user identity, rotation, or authorization
policy by itself.

## Model and mutation review

The reviewed model controls public fields, roots, relations, policies, and named context filters.
Reject unknown policy expressions rather than inventing SQL or Java fallback behavior. Generated
filter paths and protected relation keys must remain guarded in the installed database package.
The first-deployment mutation surface uses explicit reviewed procedure handlers. The database
prevalidates inputs and policies, executes mutation roots serially in one transaction, and records
durable audit, idempotency, and outbox effects with the domain write. A failed later root rolls back
earlier writes and their audit records. Review any new handler for authorization, locking,
idempotency, and sensitive-data handling before publishing it.

Do not log access tokens, credentials, personal data, or unredacted GraphQL variables. Keep model
artifacts and temporary package output free of private fixtures. The local release/privacy gates
and history review are described in [docs/verification.md](docs/verification.md). Deployment
configuration and incident procedures are in [docs/operations.md](docs/operations.md).
