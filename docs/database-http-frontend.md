# Standalone database GraphQL HTTP frontend

The database HTTP frontend is the target serving artifact for a generated whole-request package.
It contains HTTP/JSON transport code, JDBC drivers, and the one-call database client only. It does
not contain Quarkus or a JVM GraphQL parser, validator, planner, resolver, cursor codec, or
response engine.

For a first container deployment of this frontend and the control-job worker, use
`deployment/README.md`. No existing deployment or callers require a cutover.

Build the database package, its verified deployment descriptor, and the distribution:

```sh
./gradlew \
  titanGraphqlGenerateDatabaseEngineFrontendDescriptor \
  titanGraphqlGenerateMySqlDatabaseEngineFrontendDescriptor \
  titanGraphqlVerifyDatabaseHttpFrontendReleaseArtifact
```

The descriptors are emitted beside their matching package:

- `build/generated/proofs/database-engine/package/titan-graphql-database-frontend.postgresql.properties`
- `build/generated/proofs/database-engine-mysql/package/titan-graphql-database-frontend.mysql.properties`

Do not edit a descriptor to select a different routine. It is derived from the verified model/package
binding and manifest, and fixes the dialect, schema-qualified whole-request entry point, expected
model hash, runtime identity, and deployment fingerprint. Rebuild it whenever the package is
rebuilt. The frontend sends that identity on every execution; the generated database routine
checks it before it parses the GraphQL request or accesses data.

`titanGraphqlVerifyDatabaseHttpFrontendReleaseArtifact` is deliberately stricter than merely
building a ZIP: it rejects a changed runtime closure, a launcher that does not start the isolated
frontend, or any local Titan GraphQL execution class in the release JAR.

Extract `build/distributions/titan-graphql-0.1.0-database-http-frontend.zip`, install the matching
Titan package and descriptor, then configure the launcher:

```sh
export TITAN_GRAPHQL_FRONTEND_DESCRIPTOR=/deploy/titan-graphql-database-frontend.postgresql.properties
export TITAN_GRAPHQL_JDBC_URL=jdbc:postgresql://db.example/titan
export TITAN_GRAPHQL_JDBC_USERNAME=titan_graphql
export TITAN_GRAPHQL_JDBC_PASSWORD=replace-with-secret
export TITAN_GRAPHQL_HTTP_PORT=8080
bin/titan-graphql-database-http
```

For MySQL, configure `max_allowed_packet` to at least `16M` before applying the package SQL. The
generated whole-request routine can exceed MySQL's obsolete 1 MiB packet default; this is an
installation prerequisite, not an HTTP request-size setting. Titan install verification now reads
`@@max_allowed_packet` before it runs any package SQL and rejects a target that cannot carry its
largest UTF-8 SQL statement plus protocol reserve. The standalone frontend's
`TITAN_GRAPHQL_HTTP_MAX_BODY_BYTES` setting remains an independent client-envelope limit.

Optional variables:

- `TITAN_GRAPHQL_HTTP_MAX_BODY_BYTES` — positive request-body limit; defaults to `1048576`.
- `TITAN_GRAPHQL_DATABASE_STATEMENT_TIMEOUT_SECONDS` — positive ceiling for the one JDBC
  whole-request invocation; defaults to `30`. An authenticated
  `X-Titan-Deadline-Budget-Millis` can reduce, but never increase, that ceiling.
- `TITAN_GRAPHQL_HTTP_TRUST_REQUEST_CONTEXT_HEADERS` — defaults to `false`. Enable it only
  behind an authenticated gateway that supplies the `X-Titan-*` context headers.

The frontend forwards the entire GraphQL document, operation name, variables, extensions, and
authenticated context in one database call. GET passes `allowMutations=false`; the transpiled
engine parses the request and rejects a selected mutation itself.

When `TITAN_GRAPHQL_HTTP_TRUST_REQUEST_CONTEXT_HEADERS=true`, an authenticated gateway may supply
model-defined scalar policy values in one `X-Titan-Context-Values: <JSON object>` header, for
example `X-Titan-Context-Values: {"region":"eu"}`. Every name must be an identifier and every
value must be a JSON scalar. The frontend forwards these values unchanged in `contextValues`; it
does not recognize or evaluate a name. Use `X-Titan-Context-Filters` to enable reviewed context
filters. These headers are ignored unless the trusted-gateway option is enabled.

For POST, `variables` and `extensions` may be omitted, an object, or JSON `null`; `null` is
forwarded as absent input so the database engine alone applies GraphQL defaults and coercion.
Other values are malformed HTTP envelopes and receive a transport `400` before any database call.
Duplicate JSON member names are rejected as malformed transport rather than being collapsed by an
HTTP JSON tree into a different GraphQL variable value.

## Management package binding

`titanGraphqlGenerateManagementPostgreSqlDatabaseEngineFrontendDescriptor` and
`titanGraphqlGenerateManagementMySqlDatabaseEngineFrontendDescriptor` emit descriptors for the
separately bound `management_graphql.execute_graphql_request` package. Install that package and
its physical management tables on the same database server as the application package. Set
`TITAN_GRAPHQL_ADMIN_FRONTEND_DESCRIPTOR` to the matching management descriptor and
`TITAN_GRAPHQL_ADMIN_ACCESS_TOKEN` to a nonempty secret to enable `/admin/graphql` in the
standalone frontend. `TITAN_GRAPHQL_ADMIN_ROLE` defaults to `operator`, and
`TITAN_GRAPHQL_ADMIN_ACTOR_KEY` defaults to `titan-admin`; the deployment sets both, and HTTP
role headers cannot override them. Requests without the configured bearer token receive HTTP
401 before the frontend decodes a GraphQL envelope or opens a database connection. Keep the
listener behind TLS when clients connect from outside its host.

The management model at `src/main/resources/graphql/management-database.titan.graphql.yaml`
defines its point reads and role-protected request mutations. Each request mutation enqueues its
job in the request transaction and returns the caller-supplied UUID job ID. Clients
reuse the same GraphQL idempotency key for ambiguous retries and poll `controlJob` for completion;
validation, import, and operation-review jobs return their outcomes in `resultJson`. Model import requires
`X-Titan-Management-Request-Id` and `X-Titan-Management-Idempotency-Key`; the admin frontend
supplies actor role and key from deployment configuration, not GraphQL input. The management package creates
its request tables, while installation of the management draft store remains a separate prerequisite.
It does not implement the complete legacy admin mutation and query inventory, so this opt-in route
is a migration seam, not a replacement for the legacy Quarkus `/admin/graphql` endpoint. The
packaged-process proof is `./gradlew databaseEngineManagementHttpIntegrationTest`; it checks
authorization and separate application/management package dispatch on PostgreSQL and MySQL.
The model binds `observedOperation` to the durable management projection. The JDBC store writes that
projection and its product-state journal in one transaction; the management integration gate
checks the installed read and role policy on both supported dialects. The
`requestObservedOperationReview` mutation queues a trusted-reviewer job; its worker commits the
operation status, registry decision, projection update, and job receipt together. The
`operationRegistry` point read exposes the projected registry mode and decisions to authorized
management callers. Add `operation-registry-id` to a deployment-owned copy of a verified frontend
descriptor to bind its serving routine to that registry. The routine checks indexed decisions
before execution and fails when the bound registry is missing; callers cannot select the registry
through GraphQL extensions or trusted-context value headers. The installed management integration
gate exercises WARN, ENFORCE, scoped approval, first-match precedence, and an admin route bound by
that descriptor on PostgreSQL and MySQL. A descriptor without this key remains unguarded.
The package fingerprint does not attest the registry ID; the deployment publisher must bind the
registry for the same model and environment before exposing a candidate package.
`databaseEngineManagementHttpIntegrationTest` stops and restarts the packaged frontend against
the same database, then observes its persisted registry mode change from WARN to ENFORCE on both
supported dialects.

The Quarkus admin resource can use the same installed management package. Set
`titan.graphql.admin.database-descriptor` to the package-generated
`frontend-deployment.properties` path, keep `titan.graphql.admin.access-token` configured, and
use the Quarkus default datasource for the database that contains the package. The resource
reads the descriptor at startup, supplies its configured actor role and key, and invokes the
whole-request routine without a JVM GraphQL fallback. A failed database call returns HTTP 503.
GraphQL GET requests cannot execute mutations in this mode. An authenticated admin HTTP request
returns HTTP 503 when the descriptor is unset; it cannot execute the legacy JVM implementation.
`./gradlew databaseEngineManagementIntegrationTest` exercises the Quarkus resource against the
installed management package on PostgreSQL and MySQL.

Set `TITAN_GRAPHQL_PREVIEW_FRONTEND_REGISTRY` to a Java properties file to bind verified preview
packages. Each property name is a preview-build ID that starts with a letter or digit and
otherwise contains only letters, digits, periods, underscores, or hyphens. Each value is the
path to that build's generated frontend deployment descriptor. The standalone frontend registers
exactly `/preview/{previewBuildId}/graphql` for
each entry at startup and sends requests only to the routine and model identity in its descriptor.
An unregistered build ID returns HTTP 404. Preview requests use the deployment's trusted-context
header setting and the selected model's database policies; an untrusted request cannot choose a
package, routine, or actor role. Keep preview access behind a trusted gateway when enabling
`TITAN_GRAPHQL_HTTP_TRUST_REQUEST_CONTEXT_HEADERS`.
The standalone frontend requires `preview-build-id` to match the registered route and
`preview-expires-at`, `operation-registry-id`, and `preview-deployment-sha256` to be present.
It returns HTTP 410 after expiration, before it opens a database connection. Missing or malformed
metadata fails startup. Its registry remains fixed for
the process lifetime, so replacing or removing a preview mapping requires a frontend restart.

The Quarkus preview resource can use a descriptor registry too. Set
`titan.graphql.preview.database-descriptor-registry` to a Java properties file whose keys are
preview-build IDs and whose values are generated frontend descriptor paths. Relative descriptor
paths resolve from the registry file's directory. The resource validates the registry at startup,
returns HTTP 404 for unknown IDs, and returns HTTP 503 if a selected database package cannot
serve the request; it does not fall back to the process-local preview runtime. In database mode,
the resource ignores caller-supplied actor and policy headers unless
`titan.graphql.preview.trust-request-context-headers=true` is configured behind a trusted gateway.
The preview HTTP route returns HTTP 503 when the descriptor registry is unset, even if the
process-local preview runtime has a candidate with the requested ID.

The Quarkus resource re-reads the registry and selected descriptor for each request, so an
atomic replacement or removal takes effect without a process restart. Every preview descriptor
must set `preview-build-id` to match the registry key, `preview-expires-at` to an
ISO-8601 instant, and `operation-registry-id` and `preview-deployment-sha256` to a sealed
ENFORCE registry. The standalone preview route enforces the same binding at startup. At or after
that instant, the route returns HTTP 410 without invoking the
database package. A missing or malformed replacement returns HTTP 503 rather than serving the
previous mapping. The durable `saveVerifiedPreviewBuild` publication boundary requires a READY
build, a future expiration, and an ENFORCE operation registry for the same model and environment;
the deployment publisher supplies these values in its descriptor.

The packaged control-plane command
`titan-graphql-control export-preview-draft <postgresql|mysql> <jdbc-url> <draft-id> <output.yaml>`
exports a validated, passing YAML draft from JDBC management state. It checks the stored semantic
hash and refuses to replace a different export at the same path. Build the exported source with
`./gradlew titanGraphqlBuildPreviewPostgreSql` or `titanGraphqlBuildPreviewMySql`, supplying
`-PtitanGraphqlPreviewModel=<output.yaml>` and `-PtitanGraphqlPreviewId=<preview-id>`. The build
transpiles the reviewed model into an isolated `preview_<digest>` routine schema, packages it,
scratch-installs it, and writes a bound frontend descriptor under
`build/generated/preview-candidates/<preview-id>-<digest>/<dialect>/package`. The digest includes
the preview ID and source bytes, so a changed source gets a different candidate schema and
directory. The MySQL package installs Titan's runtime helpers in that candidate schema as well
as its default runtime schema; the package inventory verifies both locations. Build output alone
does not install the candidate into the serving database. The
`databaseEnginePreviewCandidateIntegrationTest` Gradle task imports and validates a draft through
the authenticated database-backed management GraphQL route, polls those jobs and an artifact job,
approves a registered operation through the review job, then stages, publishes, and requests the
candidate through the database-backed preview route on
PostgreSQL and MySQL. The task also publishes a generated PostgreSQL candidate in a Quarkus test
database and exercises POST and GET at the public `/preview/{previewBuildId}/graphql` URL with
caller-supplied role headers disabled.

For artifact workers handling more than one candidate, set
`TITAN_GRAPHQL_ARTIFACT_PACKAGE_REGISTRY` to a deployment-owned Java properties file with
`<draft-id>=<package-directory>` entries. A relative directory resolves from the properties
file's parent. After the candidate build, the packaged command
`titan-graphql-control stage-preview-package <postgresql|mysql> <jdbc-url> <draft-id> <package-directory> <package-registry>`
checks the current validated draft, package binding, and scratch-verified frontend descriptor,
attests that the reviewed source-local mutation handlers occur in the package inventory and are
reachable from its GraphQL entry point, then atomically records its absolute package path.
Repeating the same selection is safe; selecting
a different package for that draft fails. It uses the control database credentials described below.
Staging does not install the candidate or activate a preview route. The worker re-reads the mapping
for each claimed draft and refuses a missing or duplicate draft entry or an absent directory.
These deployment-configuration errors leave a
claimed artifact job pending for a later retry; the worker reports the error to its supervisor.
The GraphQL request cannot select a filesystem
path. Without this registry, the worker retains its single-package
`TITAN_GRAPHQL_ARTIFACTS_DIR` setting. The registry selects package bytes for artifact
generation; it does not install the package or publish the preview route.

`./gradlew titanGraphqlInstallPreviewPostgreSql` or `titanGraphqlInstallPreviewMySql` installs
that same candidate into an explicitly selected database. Supply the same model and preview ID,
`-PtitanGraphqlPreviewJdbcUrl=<serving-jdbc-url>`, and the credentials in
`TITAN_GRAPHQL_CONTROL_DB_USER` and `TITAN_GRAPHQL_CONTROL_DB_PASSWORD`. The target database must
already satisfy Titan's routine-install prerequisites. For MySQL this includes the
`titan_runtime` database and permission to create stored functions; the fresh-server proof used
`log_bin_trust_function_creators=1`. The install task verifies the resulting package but does
not publish a preview route or registry.

The packaged control-plane command
`titan-graphql-control publish-preview <postgresql|mysql> <jdbc-url> <package-directory> <registry-file> <preview-id> <draft-id> <environment> <registry-id> <expires-at> <actor-key>`
loads a generated READY_FOR_REVIEW draft from JDBC management state, verifies its source and
artifact-set hashes, checks the supplied package binding and the installed package and ENFORCE
registry, repeats source-local mutation handler attestation, and atomically switches the preview
ID mapping after writing an immutable descriptor.
The command reads database credentials from `TITAN_GRAPHQL_CONTROL_DB_USER` and
`TITAN_GRAPHQL_CONTROL_DB_PASSWORD`. The candidate package and matching registry must already be
installed in that database. Artifact generation for the exported draft must also have produced a
matching READY_FOR_REVIEW artifact set before publication. These export, build, install, artifact
generation, and publication steps are not yet one coordinated deployment transaction. The command
does not configure either HTTP host or coordinate a live package replacement. A standalone frontend
must restart to read
the new registry mapping; the Quarkus preview resource re-reads it per request. Preview routes
reject unsealed descriptors. Published descriptors include
`preview-deployment-sha256`, which binds the installed package identity and ENFORCE registry
record and operation projection. Both HTTP hosts recompute that snapshot in the request's
repeatable-read database transaction before invoking the GraphQL routine; a mismatch returns
HTTP 503. A hand-authored sealed descriptor can match the installed database state, but the seal
alone does not prove that the durable preview-publication workflow approved it; use
`publish-preview` to establish that record.
The management-package fixture in
`databaseEngineManagementIntegrationTest` proves Quarkus preview routing on PostgreSQL and MySQL;
it does not establish that a real candidate package has been published.

## Transaction result protocol

The frontend does not inspect GraphQL JSON to decide whether to commit. The generated PostgreSQL
function returns a fixed `TITAN-GRAPHQL-TRANSPORT/1` frame containing a `COMMIT` or `ROLLBACK`
instruction followed by the completed GraphQL JSON. The generated MySQL procedure returns the
same two values in its final `transaction_outcome` and `response_json` columns. The JDBC client
validates the fixed outcome, applies the connection lifecycle, removes the PostgreSQL frame, and
forwards only the unmodified GraphQL JSON to HTTP. Transaction instructions are never emitted in
the GraphQL `extensions` object.
