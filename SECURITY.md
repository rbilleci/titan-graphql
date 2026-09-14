# Security Policy

## Supported version

Security fixes are applied to `main` until a versioned release policy is established.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability. Private vulnerability reporting is
not currently enabled for this repository; contact the repository owner through GitHub and ask
for a private reporting channel.

## Deployment boundary

This repository is a proof project, not a turnkey public service. Its secure defaults are:

- `/graphql` ignores caller-supplied `X-Titan-*` context headers.
- `/admin/graphql` returns `404` unless the server configures
  `titan.graphql.admin.access-token`; enabled requests require that value as a bearer token.
- The admin role and actor key come from server configuration, not HTTP headers.

Only set `titan.graphql.http.trust-request-context-headers=true` behind an authenticated
gateway that removes client-supplied copies of those headers and injects verified values.
Keep the admin token in a secret manager or environment variable; never commit it.

The bearer-token guard is deliberately small and appropriate only for a tightly controlled
administrative surface. Production deployments needing multiple users, roles, rotation, or
audit identities must integrate an external identity provider or an authenticated gateway.

## Model and compiled-query boundary

Treat the reviewed `titan.graphql.yaml`, its generated package, and the package binding as one
deployment unit. Compiled mode verifies the normalized model hash against both the sidecar and the
installed attestation routine before serving reads. Never deploy generated SQL with a different
model document or bypass `titanGraphqlBindPackage`.

Generated SQL uses model-approved identifiers and prepared values. Generated filters perform the
same field authorization checks as selected output fields, including every segment of a relation
path. Protected fields are omitted from unguarded read and filter carriers. Unsupported filter
composition, policy expressions, relation policies, or missing fail-closed context values reject
the request before database I/O; compiled mode does not fall back to a less restrictive runtime.

The only currently executable named field policy is `adminOnly`. It relies on the authenticated
`actorRole` supplied by the application boundary. Do not treat user-provided role or policy headers
as trusted identity. Root, row, field, and relation policy compilation is not complete, so models
requiring broader authorization semantics must not be promoted to compiled production serving yet.
