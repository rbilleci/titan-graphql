# M8 persistent internal deployment

Status: implementation and first workflow verification pass; final committed-source recovery
verification and evidence retention remain open. M7 remains the completed engine-release milestone.
M8 adds a persistent local consumer of that released serving boundary, not new GraphQL semantics.

The approved target uses local Docker Compose, a dedicated PostgreSQL database, and localhost-only
HTTP access. `deployment/dogfood/jobs.titan.graphql.yaml` reads metadata from actual control jobs
created through the management API. `deployment/dogfood.py` consumes that API and its published
preview instead of seeding a demo dataset. The container setup and recovery procedures are in
`deployment/dogfood/README.md`.

The acceptance matrix keeps deployment proof separate from the completed M7 release proof.

| Requirement | Implementation | Required evidence |
| --- | --- | --- |
| M8-PERSISTENT-LOCAL-STACK | Dedicated Compose project/database volume, localhost HTTP port, independently supervised frontend/worker | Running service inspection, exact port bindings, image identities, and volume name |
| M8-PRIVATE-SECRETS | Generated private `.env`, ignored state directory, no secrets in deployment mounts | Permission tests, credential-preservation tests, and tracked-file review |
| M8-REAL-INTERNAL-CONSUMER | Job-metadata projection and host API client | Preview responses identify jobs created by actual import/validation/artifact/review requests |
| M8-MANAGEMENT-WORKFLOW | Existing authenticated management mutations and durable worker | Succeeded jobs, validated physical draft, generated artifact set, approved operation, sealed publication |
| M8-SERVING-BOUNDARY | Existing isolated frontend ZIP and installed Titan packages | Package/descriptor identities and rejection of unreviewed preview documents |
| M8-RECREATION-DURABILITY | Recovery removes service containers but retains database volume and deployment state | Changed container IDs, unchanged prior job/draft results, identical volume, and a new successful workflow |
| M8-REPRODUCIBLE-OPERATIONS | Scoped init/up/workflow/verify/recover/status/stop commands | Client tests, live deployment commands, and setup/recovery instructions |

## Scope and trust

The local application model exposes only job IDs, types, statuses, and attempt counts. Its public
metadata read needs no actor-header trust; `/admin/graphql` still requires its generated bearer
token. A reviewed preview enforces the exact document in `deployment/dogfood/job.graphql`.
The operator adapter verifies the checked-in model source and approved operation before publishing
that public scope. It never adds a JVM GraphQL resolver or a frontend policy implementation.

The default database owner serves this dedicated local stack. Multi-user authentication, separate
least-privilege identities, backup/restore drills, production performance targets, public hosting,
new UI, additional language/model features, and caller cutovers remain outside M8.

## Diagnostic history

The first startup exposed a restrictive host umask: descriptors and their parent directories were
not readable by the unprivileged frontend. The client now explicitly sets non-secret mount permissions
while keeping the state root and secrets private; its regression tests use a restrictive umask.
Readiness polling now retries a connection reset during startup.

Import diagnostics found the stable management request-ID and workspace-scope requirements.
The client now prefixes request/idempotency IDs and uses a `workspace-` identity. Titan's transport-neutral
management records also reject transport-specific identity tokens; the internal model uses the
neutral name `local-control-jobs` rather than the initially chosen transport-specific name.
The failed jobs remain in the local database. The repeatedly retried job for the rejected identifier
was marked failed explicitly after stopping this stack's worker; no data was deleted.

The first publication attempt inherited the worker's read-only deployment mount. The client now
uses the built worker image in a separate one-shot container with a writable operator mount.
The long-running frontend and worker retain read-only mounts. Runtime language code, dependency
pins, and the original deployment Compose file remain unchanged.

The first recovery preserved existing state but exposed duplicate artifact-registry entries when
the client retried the same draft. Titan rejected the duplicates rather than choosing a package
implicitly. Registration now atomically replaces a unique mapping and rejects conflicting existing
entries; client regression tests cover retries and contradictions. Final acceptance requires a
fresh recovery run after that correction.
