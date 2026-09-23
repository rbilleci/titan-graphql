# Titan GraphQL Getting Started

Status: developer onboarding and local deployment guide.

Titan GraphQL turns a reviewed database projection into a generated, install-verified Titan package
and serves it through a small HTTP/JDBC frontend:

```text
Titan codegen schema.json
  -> conservative projection draft
  -> reviewed titan.graphql.yaml
  -> transpiled whole-request GraphQL engine plus generated schema binding
  -> PostgreSQL/MySQL package and install verification
  -> package/descriptor binding
  -> standalone HTTP/JDBC frontend
```

No schema-specific read resolver or handwritten read query is required for supported model/query
shapes. Unsupported shapes return explicit errors. Custom writes remain application handlers.

## Prerequisites

- JDK 21
- Docker for install and live database verification
- the checked-in Gradle wrapper
- initialized Titan and Titan DSL submodules

```bash
git submodule update --init --recursive
./gradlew --version
```

Plain `./gradlew test` is Docker-free. The database proofs use Testcontainers with PostgreSQL 16 and
MySQL 8.4.

## 1. Start from Database Metadata

Titan's `titanIntrospect` task produces `build/titan/schema.json` from configured DDL or a database.
`TitanGraphqlSchemaInference` converts that metadata into a conservative model draft and review
diagnostics. Inference retains tables, columns, scalar types, primary/composite keys, and foreign-key
candidates, but deliberately does not publish roots, relations, or sensitive fields automatically.

The inference API currently returns an in-memory model rather than canonical YAML. Author the
reviewed result as `titan.graphql.yaml` using [model-document-format.md](model-document-format.md).
Review every root, field, relation, filter, order path, computed field, context filter, and policy.
Remove review-only deny placeholders only after deciding the intended public exposure.

The checked-in models are useful references:

- `src/test/resources/graphql/demo-blog.titan.graphql.yaml`
- `src/test/resources/graphql/commerce.titan.graphql.yaml`
- `src/test/resources/graphql/management.titan.graphql.yaml`

## 2. Generate, Package, Verify, and Bind

Select a reviewed model with the `titanGraphqlModel` Gradle property. The default is the demo model.

```bash
./gradlew titanGraphqlGenerateRoutines \
  -PtitanGraphqlModel=/absolute/path/to/titan.graphql.yaml

./gradlew titanPackage \
  -PtitanGraphqlModel=/absolute/path/to/titan.graphql.yaml

./gradlew titanVerifyInstall \
  -PtitanGraphqlModel=/absolute/path/to/titan.graphql.yaml

./gradlew titanGraphqlBindPackage \
  -PtitanGraphqlModel=/absolute/path/to/titan.graphql.yaml
```

The last task depends on generation, packaging, and scratch install verification, so this shorter
command is equivalent when intermediate inspection is not needed:

```bash
./gradlew titanGraphqlBindPackage \
  -PtitanGraphqlModel=/absolute/path/to/titan.graphql.yaml
```

Review generated carrier source under `build/generated/sources/titan-graphql/`. Package output lives
under `build/generated/migrations/titan/`:

```text
postgresql/R__titan_010_runtime.sql
postgresql/R__titan_020_routines.sql
mysql/R__titan_010_runtime.sql
mysql/R__titan_020_routines.sql
titan-artifact.json
titan-object-inventory.json
titan-install-plan.json
titan-install-verification.json
titan-graphql-package.json
titan-rollback.postgresql.sql
titan-rollback.mysql.sql
```

These are reproducible build/release outputs, not source files to commit. Never hand-edit generated
SQL or binding JSON. The binding covers the normalized model semantic hash, artifact/manifest/source
hashes, and routine inventory.

## 3. Install the Serving Package

Apply compatible application-schema migrations first. Then apply the two generated Titan migrations
for the selected dialect in numeric order. PostgreSQL can consume the files directly. MySQL files
contain client `DELIMITER` blocks and should be applied with a compatible MySQL client or deployment
tool.

For the checked-in PostgreSQL demo:

```bash
docker run -d --name titan-graphql-demo-pg \
  -e POSTGRES_DB=titan_graphql \
  -e POSTGRES_USER=titan \
  -e POSTGRES_PASSWORD=titan \
  -p 5432:5432 postgres:16

docker exec -i titan-graphql-demo-pg psql -q -U titan -d titan_graphql \
  < ddl/postgres/titan_graphql_postgres.sql
docker exec -i titan-graphql-demo-pg psql -q -U titan -d titan_graphql \
  < build/generated/migrations/titan/postgresql/R__titan_010_runtime.sql
docker exec -i titan-graphql-demo-pg psql -q -U titan -d titan_graphql \
  < build/generated/migrations/titan/postgresql/R__titan_020_routines.sql
```

Load application data through the application's normal migration/seed path. The integration tests
own their fixture rows; generated Titan packages contain no fixture data.

## 4. Serve Through the Database Engine

Build the deployable standalone frontend from the verified package. This ZIP—not the root Quarkus
application JAR—is the supported serving artifact. Its deployment descriptor fixes the exact
dialect, schema-qualified whole-request entry point, semantic model hash, runtime identity, and
deployment fingerprint; it does not contain the JVM GraphQL engine.

```bash
./gradlew \
  titanGraphqlGenerateDatabaseEngineFrontendDescriptor \
  titanGraphqlGenerateMySqlDatabaseEngineFrontendDescriptor \
  titanGraphqlVerifyDatabaseHttpFrontendReleaseArtifact

unzip build/distributions/titan-graphql-0.1.0-database-http-frontend.zip
export TITAN_GRAPHQL_FRONTEND_DESCRIPTOR="$PWD/build/generated/proofs/database-engine/package/titan-graphql-database-frontend.postgresql.properties"
export TITAN_GRAPHQL_JDBC_URL=jdbc:postgresql://localhost:5432/titan_graphql
export TITAN_GRAPHQL_JDBC_USERNAME=titan
export TITAN_GRAPHQL_JDBC_PASSWORD=replace-with-deployment-secret
export TITAN_GRAPHQL_HTTP_PORT=8080
export TITAN_GRAPHQL_DATABASE_STATEMENT_TIMEOUT_SECONDS=30
./titan-graphql-0.1.0-database-http-frontend/bin/titan-graphql-database-http
```

For MySQL, set the MySQL descriptor and JDBC URL instead. The ZIP includes only the HTTP transport,
JDBC invocation client, JSON-envelope library, and the PostgreSQL/MySQL drivers. Do not put real
credentials in source or shell history; inject them through the deployment secret mechanism. See
[database-http-frontend.md](database-http-frontend.md) for the exact environment contract.

The descriptor generator verifies the local model/package binding and runtime-identity sidecar. The
installed public routine verifies both identities before parsing or accessing application data. A
missing, stale, mismatched, or wrongly installed package fails closed with no fallback to another
engine.

Send a query:

```bash
curl -si -X POST http://localhost:8080/graphql \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/graphql-response+json' \
  -d '{"query":"{ article(id: 1) { id title author { id name } } }"}'
```

Every response includes `X-Titan-Execution-Mode`. Database responses also include
`X-Titan-Deployment-Fingerprint`, which identifies the exact model/package binding.

The default HTTP context has no actor role and ignores caller-supplied `X-Titan-*` context headers.
Configure an authenticated gateway before enabling trusted request-context headers. See
[../SECURITY.md](../SECURITY.md).

## 5. Application Mutations

No mutation root is published by default. Declare a reviewed `mutations` binding in the model to
generate a database-resident custom mutation. Mutations execute over POST; GET remains query-only.
The current proof supports direct scalar `update` bindings; durable idempotency/audit contracts are
still migration work. See [custom-mutations.md](custom-mutations.md).

## 6. Verify the Whole Project

Use the focused tasks while iterating:

```bash
./gradlew test
./gradlew titanGraphqlDatabaseEngineReleaseCheck
```

`titanGraphqlDatabaseEngineReleaseCheck` independently generates, packages, installs, binds, and
directly invokes demo and commerce whole-request engines on PostgreSQL and MySQL, then launches the
standalone ZIP on both dialects. `compiledSchemaIntegrationTest` and `legacySqlIntegrationTest`
remain useful migration oracles but are not release approval paths.

The release gate also checks origin, submodule pins, temporary files, common credential patterns,
machine paths, author-email privacy, and GPL licensing:

```bash
scripts/release-check.sh
scripts/release-check.sh --full
```

There is intentionally no hosted GitHub Actions workflow. Maintainers run the local full gate and
retain its output with release evidence. See [verification.md](verification.md).

## Transitional Reference Modes

| Mode | Use |
| --- | --- |
| `database` | Bound PostgreSQL/MySQL generated whole-request package; Quarkus compatibility seam only |
| `compiled` | Model-generated JVM reference runtime while migrating tests and features |
| `jdbc` | Smaller direct Titan DSL/JDBC diagnostic/reference path |
| `java` | In-memory demo reference kernel |
| `sql` | Isolated historical whole-request demo kernel |

There is no cross-mode fallback. The standalone frontend ZIP is the only supported serving artifact;
these modes remain solely until their tests and retained capabilities are migrated into the
database-resident engine.

## Management Plane

`/admin/graphql` is disabled unless `titan.graphql.admin.access-token` is configured. Its actor role
and key come from server configuration, never request context headers. The default file store is for
single-process development. Select `titan.graphql.management.store=jdbc` for Titan GAP-006-backed
durable management state. See [operations.md](operations.md) and
[mutation-runtime-lowering-boundary.md](mutation-runtime-lowering-boundary.md).

## Common Failures

`vendor/titan` or `vendor/titan-dsl` is missing

: Run `git submodule update --init --recursive`.

`Could not find a valid Docker environment`

: Start Docker. `./gradlew test` remains Docker-free; install and dialect proofs require it.

`EXECUTION_MODE_UNAVAILABLE`

: Verify the selected model path, artifact directory, database kind/URL, installed migrations, and
  attestation hash. The runtime error names the failed boundary; it will not fall back.

Package binding mismatch

: Regenerate, package, install-verify, and bind from the same reviewed model and submodule pins. Do
  not reuse a sidecar from another model or build.

Unsupported plan shape

: Compare the model and query with [query-contract.md](query-contract.md) and
  [generated-routines.md](generated-routines.md). Narrow the public capability or implement and prove
  a generic carrier shape; do not add schema-specific read dispatch.
