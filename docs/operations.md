# Operations

Titan GraphQL is a pre-1.0 project with a persistent local dogfood deployment documented in
`deployment/dogfood/README.md`, not a public production service. Its dedicated PostgreSQL database,
standalone HTTP frontend, and separately supervised control-job worker support an internal
management workflow. The frontend executes no GraphQL semantics on the JVM; it binds one complete
request to the installed PostgreSQL or MySQL package. This guide does not replace an operator's
database, identity, backup, or incident-response controls.

## Deployment unit

Install the reviewed model's application schema and generated dialect package, verify its
manifest and install report, and keep its model binding, runtime/package identity sidecars, and
frontend descriptor together. Deploy the verified `titan-graphql-*-database-http-frontend.zip`
and point it only at that descriptor. The database checks the expected model, runtime, and package
identities before GraphQL execution. Never edit a descriptor to select a different routine.

Install the separate management package and durable management tables before enabling
`/admin/graphql`. Give the frontend a database identity with only the routine execution rights it
needs; do not grant arbitrary clients direct routine access. Caller-permission packages also require
explicit table/sequence privileges; routine execution alone does not suffice. The local grant
definition and denial checks are in [the operations runbook](../deployment/dogfood/OPERATIONS.md).
Run the control-job worker separately
with a durable JDBC store. Model import, validation, artifact, and review requests enqueue work in
the database; the worker commits results after the request transaction. The container setup and
exact install commands are in [deployment/README.md](../deployment/README.md).

## Runtime configuration

| Setting | Operating rule |
| --- | --- |
| `TITAN_GRAPHQL_FRONTEND_DESCRIPTOR` | Required path to the descriptor generated for the installed application package. |
| `TITAN_GRAPHQL_JDBC_URL` | Required PostgreSQL or MySQL JDBC URL matching the descriptor. |
| `TITAN_GRAPHQL_JDBC_USERNAME` / `TITAN_GRAPHQL_JDBC_PASSWORD` | Database credentials supplied outside source control. |
| `TITAN_GRAPHQL_HTTP_PORT` | Listener port; default `8080`. |
| `TITAN_GRAPHQL_HTTP_MAX_BODY_BYTES` | Positive HTTP body limit; default `1048576`. |
| `TITAN_GRAPHQL_DATABASE_STATEMENT_TIMEOUT_SECONDS` | Per-request database ceiling; default `30`, reducible by a trusted deadline. |
| `TITAN_GRAPHQL_HTTP_TRUST_REQUEST_CONTEXT_HEADERS` | Keep `false` unless an authenticated gateway strips caller copies and injects verified context. |
| `TITAN_GRAPHQL_ADMIN_FRONTEND_DESCRIPTOR` / `TITAN_GRAPHQL_ADMIN_ACCESS_TOKEN` | Both required to enable the separately bound management route. |
| `TITAN_GRAPHQL_PREVIEW_FRONTEND_REGISTRY` | Deployment-owned preview ID to sealed-descriptor mapping, loaded at startup. |

Before applying a MySQL package, set `max_allowed_packet` to at least `16M`; Titan install
verification checks the server limit against its largest SQL statement. This is separate from the
frontend HTTP body limit. Keep the listener behind TLS and a suitable identity gateway when
accessed outside a trusted host or container network. See [SECURITY.md](../SECURITY.md).

## State, rollout, and rollback

Application rows, installed routines, management records, control jobs, and published operation
registries are durable database state. Generated Java/SQL, manifests, install reports, descriptors,
and schema artifacts are reproducible release output. `.gradle/` and `build/` are local caches,
not a system of record. The frontend loads preview mappings at startup, so restart it after
publishing or replacing a mapping. Its request-local plan state disappears on restart.

Before admitting traffic, run [verification.md](verification.md), back up source data under the
database operating policy, apply compatible schema migrations, install and verify the package,
then start the frontend and worker with matching descriptors. No caller cutover is required for the
initial deployment. For a failed rollout, stop new requests, reinstall the previous verified
package and matching descriptor, and attest them before restoring traffic. Generated rollback SQL
affects Titan package objects; it is not a backup or reverse migration for application data.

Record deployment fingerprints, model/runtime/package identities, and install reports. Do not log
bearer tokens, credentials, raw trusted-context headers, or sensitive GraphQL variables. Treat
repeated identity mismatches as deployment drift, not retryable user errors. On suspected secret
disclosure, disable the administrative surface, rotate affected secrets and database credentials,
inspect audit evidence, and use the private-reporting process in [SECURITY.md](../SECURITY.md).
