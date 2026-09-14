# Titan GraphQL Getting Started

Status: developer onboarding guide.

The application path for a reviewed database schema is:

```text
Titan codegen schema.json
  -> fail-closed projection draft
  -> reviewed titan.graphql.yaml
  -> generic planner + Titan DSL + Titan JDBC (current schema-portable serving path)
  -> generated static carriers + Titan transpiler (database-resident read artifacts)
  -> PostgreSQL or MySQL
```

This is the schema-portable route and does not require handwritten read resolvers. The built-in
demo blog separately proves that a bounded whole-request GraphQL kernel can be lowered while
preserving behavior. Model-generated database read carriers now exist, but the SQL HTTP runtime
has not yet been moved from that demo kernel to the generated carrier set.

## Current State

The proof path is fully automated:

- `titanGraphqlGenerateRoutines` validates the selected reviewed model and emits deterministic
  static point, page, relation, computed-field, and model-attestation carriers under
  `build/generated/sources/titan-graphql/`.
- `titanTranspile` compiles those generated carriers and the transitional demo whole-request
  kernel for BOTH configured dialects —
  PostgreSQL and MySQL (completion plan W5.2) — with zero validator errors.
- `titanPackage` packages them into deterministic per-dialect migration artifacts
  (`build/generated/migrations/titan/<dialect>/R__titan_010_runtime.sql` and
  `R__titan_020_routines.sql`, plus manifest/inventory/plan/rollback JSON and SQL).
- `titanVerifyInstall` installs the packaged SQL into scratch containers (postgres:16
  and mysql:8.4) and verifies objects, signatures, and drift.
- `titanGraphqlBindPackage` validates the reviewed model and writes a reproducible sidecar that
  binds its normalized semantic hash to the verified Titan package hashes.
- `integrationTest` deploys the same packaged migrations onto a Testcontainers
  PostgreSQL, applies the demo DDL (`ddl/postgres/titan_graphql_postgres.sql`) and fixture rows,
  executes the conformance corpus through the deployed `public.execute_graphql*` stored
  functions, and proves the responses equivalent to the Java engine through core's
  equivalence oracle (`GraphqlSqlModeEquivalenceIT`, 97 corpus cases). The same corpus
  has a Java-vs-MySQL leg (`GraphqlSqlModeEquivalenceMySqlIT`) deploying the MySQL
  bundle and `ddl/mysql/titan_graphql_mysql.sql`.
- `GeneratedTitanGraphqlReadsIT` calls the generated PostgreSQL functions and MySQL procedures
  directly, verifies the model hash, computed projection, policy omission, relation/page reads,
  and confirms that changing a database row changes the carrier result on both dialects.
- `titan.graphql.execution.mode=sql` turns the proof into a live runtime: the Quarkus
  `/graphql` endpoint answers from the deployed stored functions (section 4;
  automated by `GraphqlSqlModeHttpIT` under `integrationTest`).

> **Current verified status:** all four legs are green on BOTH dialects. Core
> closed `TG-BLK-011` (identifier overflow) and `TG-BLK-012` (MySQL
> boolean→JSON rendering, titan f9e3b43), so `titanVerifyInstall` passes both dialects
> and the equivalence legs are **97/97 strictly equivalent each** — PostgreSQL
> (`GraphqlSqlModeEquivalenceIT`) and MySQL (`GraphqlSqlModeEquivalenceMySqlIT`), zero
> divergences. The dual-dialect status is fully clean.

In its default Java mode the Quarkus runtime is a secondary smoke test; in SQL mode
(section 4) it is the live demonstration of the proof.

## Serve A Reviewed Schema Without Read Resolvers

Run Titan codegen against your DDL or database to produce `build/titan/schema.json`. The public
`TitanGraphqlSchemaInference` API converts that document into a conservative projection draft:
tables and columns are included, foreign keys become review-required relation candidates,
sensitive-looking fields remain denied, and no public root is enabled automatically.

The API currently returns the model document plus review diagnostics; canonical YAML export is not
implemented yet. Author the reviewed result in the documented `titan.graphql.yaml` format, remove
or replace review-only deny placeholders only after deciding the intended exposure, and add the
roots and policies you intend to serve. Then configure the datasource and run:

```bash
./gradlew quarkusDev \
  -Dtitan.graphql.execution.mode=jdbc \
  -Dtitan.graphql.model.path=/absolute/path/to/titan.graphql.yaml
```

The JDBC runtime validates that document once, builds the generic projection schema, plans each
request, renders parameterized SQL through Titan DSL, and executes it with Titan's JDBC runtime.
The verified portable slice currently includes integer point roots, direct relations, scalar and
fail-closed context filters, and first-page root connections on PostgreSQL and MySQL. Unsupported
computed fields, cursor continuation, backward pages, relation connections, and collection
relation batching fail with an explicit GraphQL error.

## What The Compiled Demo Proves

The target hello-world query is:

```graphql
{
  article(id: 1) {
    id
    title
    author {
      id
      name
    }
  }
}
```

Expected database-resident response:

```json
{"data":{"article":{"id":1,"title":"Titan GraphQL proof","author":{"id":10,"name":"Ada Lovelace"}}}}
```

In `sql` mode this response comes from the fixed demo-blog stored function generated by Titan.
In `jdbc` mode, the same GraphQL transport instead plans reads from the reviewed model and executes
parameterized Titan DSL SQL against current database rows.

## Prerequisites

- JDK 21.
- Docker (Testcontainers provisions the PostgreSQL 16 instances; no local Postgres or
  `psql` needed).
- Initialize the tracked Titan and Titan DSL submodules:

  ```bash
  git submodule update --init --recursive
  ```

> **Management store note.** The management plane (`/admin/graphql`) uses a
> **file-backed store by default**, so the default `test` and dev paths are
> Docker-free. Core's durable JDBC-backed management store (the dogfooded store
> running on Titan-transpiled routines) is **opt-in** via
> `titan.graphql.management.store=jdbc`; in `jdbc` mode it runs against the same
> live datasource (PostgreSQL 16 / MySQL 8.4) and is exercised by the
> `@Tag("docker")` `GraphqlJdbcManagementStoreIT`. Leave it on the default
> `file` setting unless you specifically want the durable JDBC path.

The submodules matter because `settings.gradle.kts` includes them as composite Gradle builds:

```kotlin
includeBuild("vendor/titan")
```

Use this repository's checked-in Gradle wrapper:

```bash
./gradlew --version
```

If the build cannot find `vendor/titan` or `vendor/titan-dsl`, initialize the submodules above.

## 1. Transpile, Verify, And Bind The GraphQL Engine

From the `titan-graphql` repository:

```bash
./gradlew titanGraphqlBindPackage
```

`titanGraphqlBindPackage` runs compilation, `titanPackage`, and `titanVerifyInstall`, then binds
the package to `src/test/resources/graphql/demo-blog.titan.graphql.yaml`. Select another reviewed
model with `-PtitanGraphqlModel=/path/to/titan.graphql.yaml`. The package artifacts are written to
deterministic migration artifacts under:

```text
build/generated/migrations/titan/
  postgresql/R__titan_010_runtime.sql    # titan_runtime support schema
  postgresql/R__titan_020_routines.sql   # the transpiled GraphQL engine
  titan-artifact.json                    # artifact manifest
  titan-object-inventory.json            # generated object inventory
  titan-install-plan.json                # install plan
  titan-install-verification.json        # live install verification
  titan-graphql-package.json             # exact reviewed-model/package binding
  titan-rollback.postgresql.sql          # rollback script
```

The generated source is an intermediate reproducible build output, not a checked-in source file.
Its method names and dialect-specific invocation shapes are documented in
[generated-routines.md](generated-routines.md).

The public entrypoint generated from the demo-blog SQL kernel,
`DemoBlogTitanGraphqlFunctions.executeGraphqlRequestWithCompactContext(...)`, is:

```sql
public.execute_graphql_request_with_compact_context(
  p_query TEXT,
  p_operation_name TEXT,
  p_variables_json TEXT,
  p_extensions_json TEXT,
  p_actor_id BIGINT,
  p_actor_role TEXT,
  p_enable_published_visibility BOOLEAN,
  p_has_article_visibility BOOLEAN,
  p_article_visibility BOOLEAN,
  p_enable_introspection BOOLEAN,
  p_tenant_id TEXT,
  p_request_id TEXT,
  p_policy_flags TEXT,
  p_enabled_context_filters TEXT,
  p_deadline_budget_millis BIGINT
)
```

## 2. Verify The Install Separately

```bash
./gradlew titanVerifyInstall
```

This installs the packaged SQL into a scratch PostgreSQL container and verifies the
generated objects, routine signatures, and drift on all configured dialects, writing
`build/generated/migrations/titan/titan-install-verification.json`.

SQL serving additionally requires `titan.graphql.model.path`. At startup the runtime parses and
validates that model, compares it with `titan-graphql-package.json` and Titan's GAP-005 metadata,
and only then resolves dialect-specific schema-qualified identities from the package inventory.
Before serving its first request it calls the generated `modelSemanticHash` database routine and
requires an exact match, preventing a correct local sidecar from masking deployment to the wrong
database. The `X-Titan-Deployment-Fingerprint` header identifies the combined binding.

## 3. Run The Automated SQL-Mode Proof

```bash
./gradlew integrationTest
```

This is the product proof, fully automated (it replaces the manual psql runbook this
guide used to carry):

1. core's test harness (`TitanTestExtension`) boots a Testcontainers PostgreSQL and
   provisions a fresh database;
2. the packaged migrations from step 1 deploy onto it, along with the demo blog DDL
   (`ddl/postgres/titan_graphql_postgres.sql`) and the same fixture rows the Java engine uses
   (`DemoBlogFixtureStore`);
3. `GraphqlSqlModeEquivalenceIT` first answers the hello-world query above from inside
   Postgres, then executes the full conformance corpus
   (`GraphqlSqlModeConformanceCorpus`, 97 cases covering every ACCEPTED row of
   `docs/query-contract-conformance.md`) through the deployed
   `public.execute_graphql*` stored functions;
4. every response is compared against the Java engine's response for the identical
   invocation, as canonical JSON, through core's `EquivalenceOracle`. Any divergence
   fails the build and is reported with the corpus case, the JSON path, and both legs.

Plain `./gradlew test` stays Docker-free: the equivalence IT is tagged
`@Tag("docker")` and runs only under `integrationTest` (the same convention core's
modules use). The Docker-free guard `GraphqlSqlModeConformanceCoverageTest` keeps the
conformance matrix and the corpus in lockstep on every plain `test` run.

### The MySQL leg (W5.2)

The same corpus also runs Java-vs-MySQL: `GraphqlSqlModeEquivalenceMySqlIT` provisions a
`mysql:8.4` Testcontainers database through core's harness, deploys
`ddl/mysql/titan_graphql_mysql.sql` plus the packaged MySQL migrations
(`build/generated/migrations/titan/mysql/`, applied with the mysql-client `DELIMITER`
convention the emitted bundle uses), seeds the same fixture rows, and compares every
case by the identical canonical-JSON method. Two MySQL-specific mechanics to know:

- MySQL schemas are databases: the kernel's objects install into a server-global
  `public` database (mirroring the configured Titan schema), and the demo DDL is
  written re-runnable (`DROP TABLE IF EXISTS` first) because the shared container hosts
  it across tests.
- Deployment is fail-loud by design — a partially deployed kernel never masquerades as
  a per-case comparison result.

This leg is **97/97 strictly equivalent, zero divergences**. The 6 cases
that were tracked known-divergent under `TG-BLK-012` (MySQL rendering booleans `1`/`0` in
JSON) now compare strictly equal after core's B-10 fix (titan f9e3b43) renders them
`true`/`false`; the `KNOWN_DIVERGENT_TG_BLK_012` allowlist is retired to `Set.of()`, kept
as an empty guard so a returning boolean-parity regression fails strict comparison loudly.

If you want to poke the deployed engine by hand, the migration files under
`build/generated/migrations/titan/postgresql/` apply to any scratch PostgreSQL in
numeric order, after `ddl/postgres/titan_graphql_postgres.sql`; the equivalence IT is the
authoritative, repeatable version of that exercise. (The MySQL equivalents live under
`build/generated/migrations/titan/mysql/` and apply with the mysql client, which
understands the bundle's `DELIMITER` blocks natively.)

## 4. Serve GraphQL From The Database (Opt-In SQL Mode)

The proof can also run as a live demo: the Quarkus `/graphql` endpoint answers
every request by calling the DEPLOYED stored functions instead of the Java kernel.
The switch is one config property:

```properties
titan.graphql.execution.mode=java   # default: the in-JVM kernel
titan.graphql.execution.mode=jdbc   # reviewed model + generic Titan DSL/JDBC reads
titan.graphql.execution.mode=sql    # transitional deployed demo whole-request function
```

It is ordinary Quarkus/MicroProfile config, so `-Dtitan.graphql.execution.mode=sql`
and the `TITAN_GRAPHQL_EXECUTION_MODE` environment variable work too. The automated
version of everything below is `GraphqlSqlModeHttpIT` (runs under `integrationTest`).

### 4.1 Deploy the package onto a PostgreSQL

After step 1 (`titanPackage`) and step 2 (`titanVerifyInstall`), deploy onto any
reachable PostgreSQL — for a local demo:

```bash
docker run -d --name titan-graphql-demo-pg \
  -e POSTGRES_DB=titan_graphql -e POSTGRES_USER=titan -e POSTGRES_PASSWORD=titan \
  -p 5432:5432 postgres:16

docker exec -i titan-graphql-demo-pg psql -q -U titan -d titan_graphql < ddl/postgres/titan_graphql_postgres.sql
docker exec -i titan-graphql-demo-pg psql -q -U titan -d titan_graphql < build/generated/migrations/titan/postgresql/R__titan_010_runtime.sql
docker exec -i titan-graphql-demo-pg psql -q -U titan -d titan_graphql < build/generated/migrations/titan/postgresql/R__titan_020_routines.sql

# The demo fixture rows (the same dataset the Java engine serves):
docker exec -i titan-graphql-demo-pg psql -q -U titan -d titan_graphql <<'SQL'
INSERT INTO users (id, name, email, role) VALUES
  (10, 'Ada Lovelace', 'ada@example.test', 'author'),
  (11, 'Grace Hopper', 'grace@example.test', 'author');
INSERT INTO articles (id, author_id, title, body, published) VALUES
  (1, 10, 'Titan GraphQL proof', 'First proof body', true),
  (2, 11, 'Stored functions as APIs', 'Second proof body', false);
INSERT INTO comments (id, article_id, author_id, body) VALUES
  (100, 1, 11, 'First comment'),
  (101, 1, 10, 'Second comment'),
  (102, 2, 10, 'API comment');
SQL
```

### 4.2 Run the HTTP layer in SQL mode

```bash
TITAN_GRAPHQL_EXECUTION_MODE=sql \
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/titan_graphql \
QUARKUS_DATASOURCE_USERNAME=titan \
QUARKUS_DATASOURCE_PASSWORD=titan \
./gradlew quarkusDev
```

The default HTTP configuration ignores caller-supplied `X-Titan-*` policy headers and keeps
`/admin/graphql` disabled. See [SECURITY.md](../SECURITY.md) before enabling either trusted
context headers or the administrative bearer token.

### 4.3 Watch the database answer GraphQL

```bash
curl -si -X POST http://localhost:8080/graphql \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/graphql-response+json' \
  -d '{"query":"{ article(id: 1) { id title author { id name } } }"}'
```

The mode surface proves which engine answered: every response carries
`X-Titan-Execution-Mode`, and SQL-mode responses add the deployment fingerprint
(the SHA-256 identity of `titan-graphql-package.json`, which binds model and package):

```text
HTTP/1.1 200 OK
X-Titan-Execution-Mode: sql
X-Titan-Deployment-Fingerprint: 4663407dfa99f9541be1cced40e3556af180bf920057f07cb6e33cc5357398a1
Content-Type: application/graphql-response+json

{"data":{"article":{"id":1,"title":"Titan GraphQL proof","author":{"id":10,"name":"Ada Lovelace"}}}}
```

(In the default Java mode the same request answers with
`X-Titan-Execution-Mode: java` and no fingerprint header.)

Execution telemetry lands in the serving database — the `titan_runtime.telemetry`
table created by the deployed `R__titan_010_runtime.sql`, written through core's
`JdbcTelemetrySink` on the same datasource:

```bash
docker exec -i titan-graphql-demo-pg psql -U titan -d titan_graphql \
  -c "SELECT procedure_name, status, duration_ms FROM titan_runtime.telemetry ORDER BY id DESC LIMIT 3;"
```

### 4.4 Failure honesty

SQL mode never falls back to the Java kernel. Stop the database
(`docker stop titan-graphql-demo-pg`) and repeat the curl: the answer is a
descriptive `503` GraphQL error naming the mode, the datasource, and the remedy
(deploy the migrations / fix the datasource / switch the mode back to `java`),
with `extensions.code = "EXECUTION_MODE_UNAVAILABLE"`. The same happens when the
database is reachable but the Titan migrations are not deployed on it.

## Optional Java Runtime Smoke Test

Use this only as a secondary sanity check after the stored-procedure path works.

```bash
./gradlew test --tests io.titan.graphql.GraphqlHttpResourceQuarkusSmokeTest
./gradlew quarkusDev
```

Then:

```bash
curl -s \
  -X POST http://localhost:8080/graphql \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/graphql-response+json' \
  -d '{"query":"{ article(id: 1) { id title } }"}'
```

Expected Java-runtime response:

```json
{"data":{"article":{"id":1,"title":"Titan GraphQL proof"}}}
```

This confirms the Java engine behavior, but it does not prove the Titan
transpiler or PostgreSQL stored-function runtime.

## Read The Model Source

The first human-authored model document lives at:

```text
src/test/resources/graphql/demo-blog.titan.graphql.yaml
```

Read it after the first stored-procedure query succeeds:

```bash
sed -n '1,160p' src/test/resources/graphql/demo-blog.titan.graphql.yaml
```

This YAML document describes the demo blog model used by the runtime tests:

- root fields such as `article` and `articles`
- object types such as `Article`, `User`, and `Comment`
- table bindings
- field policies
- context filters
- relation capabilities
- artifact and deployment metadata

Use `docs/model-document-format.md` as the reference for the YAML format.

## Where To Go Next

If you want to work on the stored-procedure proof:

- inspect `src/main/java/io/titan/graphql/demo/blog/DemoBlogTitanGraphqlFunctions.java`
- inspect generated SQL under `build/generated/sql/titan/postgresql`
- read `docs/design.md`
- run `./gradlew titanTranspile`

If you want to work on the runtime GraphQL contract:

- read `docs/query-contract.md`
- read `docs/query-contract-conformance.md`
- inspect `src/test/java/io/titan/graphql/TitanGraphqlFunctionsTest.java`
- inspect `src/test/java/io/titan/graphql/conformance/GraphqlSqlModeConformanceCorpus.java`
  and `GraphqlSqlModeEquivalenceIT.java` (the live SQL-mode equivalence leg)
- inspect `src/main/java/io/titan/graphql/sqlmode/` and
  `src/test/java/io/titan/graphql/GraphqlSqlModeHttpIT.java` (the live SQL serving mode)

If you want to author a model:

- read `docs/model-document-format.md`
- inspect `src/test/resources/graphql/demo-blog.titan.graphql.yaml`
- read `docs/projection-api.md`

If you want to work on management/productization:

- inspect `src/main/java/io/titan/graphql/GraphqlAdminHttpResource.java`
- read `docs/mutation-runtime-lowering-boundary.md`

## Common Failures

`./gradlew: No such file or directory`

: Restore the checked-in wrapper or run `git submodule update --init --recursive` if the
  failure concerns `vendor/titan` or `vendor/titan-dsl`.

`Unsupported Java runtime`

: Install or select JDK 21.

`integrationTest` fails with `Could not find a valid Docker environment`

: Start Docker. Plain `test` stays green without it; the SQL-mode equivalence
  proof needs a Docker daemon for Testcontainers.

`missing SQL script (run titanPackage first?)`

: The equivalence IT deploys the packaged migrations. Running
  `./gradlew integrationTest` wires `titanPackage` automatically; if you run
  the test class from an IDE, run `titanPackage` once first.

`/graphql` answers `503` with `EXECUTION_MODE_UNAVAILABLE`

: You are in SQL execution mode and the configured datasource is unreachable, has no
  Titan migrations deployed, or no `quarkus.datasource.jdbc.url` is set. The error
  message names the datasource and the remedy; there is deliberately no silent
  fallback to Java mode. Follow section 4.1, or switch
  `titan.graphql.execution.mode` back to `java`.

`/graphql` returns the expected response but `integrationTest` fails

: The Java HTTP path is not the proof path. A divergence reported by
  `GraphqlSqlModeEquivalenceIT` means the deployed stored-function engine disagrees
  with the Java engine — fix that first. Each divergence report names the corpus
  case and both responses.
