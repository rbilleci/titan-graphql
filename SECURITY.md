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
