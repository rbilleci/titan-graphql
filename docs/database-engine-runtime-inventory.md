# Database-engine runtime inventory

Status: M6 deletion audit in progress. M0–M5 established the replacement and parity evidence;
`7893bd9` removes the legacy code, and `07dcad0` records the migration map. The local full release,
container, and clean-clone gates pass. Cleanup of an ignored obsolete output directory awaits
confirmation of the intended local target.
The last completed parity run and measurements are in
[database-engine-m5-parity.md](database-engine-m5-parity.md). The 97 historical SQL-mode corpus
case IDs have individual dispositions in
[database-engine-m6-case-disposition.tsv](database-engine-m6-case-disposition.tsv).

## Serving and build boundaries

| Surface | Current owner | M6 disposition |
| --- | --- | --- |
| `/graphql` | `databaseHttpFrontend` standalone ZIP, one `DatabaseWholeRequestClient` call to a descriptor-bound installed routine | Retain as the sole application serving route. |
| `/admin/graphql` | Same ZIP, separately bound management package and bearer-token guard | Retain the reviewed first-deployment management inventory. |
| `/preview/{previewBuildId}/graphql` | Same ZIP, deployment-owned sealed descriptor registry loaded at startup | Retain; mapping changes require frontend restart. |
| GraphQL semantics | `databaseEngine` transpilable source set and generated model binding | Retain as the sole maintained request engine. |
| Package/model generation | `main` model, validation, inference, projection, codegen, artifact, and control-plane tools | Retain useful build-time and operator APIs, not an alternate serving JAR. |
| Control jobs and publication | Separate JDBC-backed control-plane worker and management store | Retain for container deployment; no process-local GraphQL execution router. |

The release ZIP is limited to frontend/HTTP classes and reviewed JSON/JDBC libraries. It cannot
load `main` or `databaseEngine` output. The `main` JAR remains a build/control-plane artifact,
not a deployable HTTP server. The first target is a container; there are no existing deployments,
callers, or cutovers.

## Deletion and retention ledger

| Family | M6 disposition | Replacement commits and evidence |
| --- | --- | --- |
| Quarkus application, admin, preview routes and runtime selector | Removed in `7893bd9` with the Quarkus plugin and runtime dependencies. | `9611c62` introduced the standalone database path; `775e712` added containerized admin and preview routes. |
| JVM `GraphqlEngine`, validator, read planner, mutation provider/executor/audit, JDBC/compiled models | Removed in `7893bd9`. | `9611c62` installed the shared database engine; `775e712` added mutation and management proofs; `557eb3e` pinned parity. |
| Demo/blog whole-request executor and `TitanGraphqlFunctions` facade | Removed from production in `7893bd9`; the schema remains a test fixture. | The demo package passes installed PostgreSQL and MySQL tests in the database-engine release gate. |
| Legacy SQL mode, entry-point dispatch, invoker, equivalence tasks, comparison corpus | Removed in `7893bd9`; the case disposition file retains the migration map. | `557eb3e` introduced the fixed expected-result corpus through installed routines and the ZIP on both dialects. |
| Carrier-only routine source generator, transpile/package/bind tasks, compiled-schema tests | Removed in `7893bd9`. | `9611c62` introduced the database-engine source generator; `557eb3e` added package inventory attestation. |
| Historical cursor and filter-plan JVM code | Removed from production in `7893bd9`; the cursor encoder remains a test fixture. | Installed cursor, filter, and ordering tests plus the fixed corpus exercise the database implementation. |
| Management GraphQL runtime and process-local preview router | Removed in `7893bd9`; the JDBC management store and publication tools remain. | `775e712` added the management package, worker restart, and sealed preview publication. |
| Build-time input-default syntax validator and fixed-selection introspection artifact projection | `7893bd9` removed the generic JVM request parser, coercer, and introspection executor; only build-time constant parsing and static projection remain. | Model validation and artifact tests pass; the release ZIP excludes the build/control-plane JAR. |

## Required completion evidence

Source and route searches show no alternate serving mode, obsolete package task, or private fixture.
The compiled `main` JAR and runtime dependency graph have no superseded serving closure; the
standalone ZIP retains its reviewed class/dependency set. Installed, HTTP, container,
privacy/history, and clean-clone checks pass. Grep absence alone is insufficient: the container
gate and standalone HTTP tests exercise the reachable endpoints and installed package invocation.
M6 remains open only for confirmation and removal of the obsolete ignored local output directory.
M7 separately handles publication, remote reconciliation, and final push.
