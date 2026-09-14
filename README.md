# titan-graphql

Titan GraphQL is a schema-driven GraphQL layer built around Titan. Titan codegen discovers
database structure, a reviewed projection document controls exposure and policy, and a generic
executor turns validated selections into parameterized Titan DSL reads. The project also
stress-tests Titan by transpiling its bounded GraphQL kernel into database-resident functions.

This project is licensed under [GPL-3.0-or-later](LICENSE). It is a bounded proof project;
read [SECURITY.md](SECURITY.md) before exposing either HTTP endpoint.

The product goal is to put GraphQL over supported schemas without handwritten read resolvers or
queries. Custom mutations remain explicit application code. The database-resident kernel is a
second, narrower proof until model-driven kernel generation replaces the fixed demo package.

New developers should start with [docs/getting-started.md](docs/getting-started.md).

## What The Proof Demonstrates Today

The full pipeline is automated and green on both supported dialects:

- **Serve a reviewed model generically:** `titan.graphql.execution.mode=jdbc` loads a
  `titan.graphql.yaml` projection and executes supported point reads, direct relations, and
  forward collection pages against live data. Declared fail-closed context predicates are
  applied in SQL before client filters. Titan DSL renders bound SQL and Titan's JDBC
  runtime executes it. A customers/orders integration fixture proves the same runtime on
  PostgreSQL and MySQL and verifies that database changes appear immediately without generated
  or handwritten schema-specific execution code.
- **Start from Titan codegen metadata:** `TitanGraphqlSchemaInference` consumes the
  `build/titan/schema.json` emitted by `titanIntrospect`, preserving tables, scalar columns,
  keys, and foreign-key relation candidates in a fail-closed review draft. Public roots and
  sensitive or relational exposure still require deliberate approval. Composite keys are
  retained as diagnostics and are never silently reduced to their first column.
- **Transpile (both dialects)**: `titanTranspile` lowers the demo-blog GraphQL kernel
  (`DemoBlogTitanGraphqlFunctions`) into PostgreSQL **and MySQL** stored functions with zero
  validator diagnostics. The transpiler input is an explicit one-file kernel allowlist, so
  application, HTTP, inference, artifact, and management records do not leak into the SQL
  package. The kernel follows core's GAP-004 bounded-traversal contract.
- **Package (both dialects)**: `titanPackage` produces deterministic migration artifacts
  per dialect (`R__titan_010_runtime.sql` + `R__titan_020_routines.sql`) plus
  manifest/inventory/install-plan/verification JSON and rollback scripts, with zero
  duplicate identities.
- **Verify (both dialects)**: `titanVerifyInstall` installs the package into scratch
  PostgreSQL and MySQL containers and verifies objects, routine signatures, and drift with
  zero diagnostics.
- **Prove equivalence (the finish line, both dialects)**: `integrationTest` deploys the
  packaged migrations onto Testcontainers databases and runs a 97-case conformance corpus
  through the deployed `public.execute_graphql*` stored functions, comparing every
  response against the Java engine as canonical JSON via core's `EquivalenceOracle`.
  The PostgreSQL leg (`GraphqlSqlModeEquivalenceIT`) is **97/97 equivalent, zero
  divergences** against core HEAD; 41 of 42 conformance-matrix rows cite live SQL-mode
  evidence ([docs/query-contract-conformance.md](docs/query-contract-conformance.md)).
  The Java-vs-MySQL leg (`GraphqlSqlModeEquivalenceMySqlIT`) is now **97/97 strictly
  equivalent, zero divergences** as well: core's B-10 fix (`TG-BLK-012`, titan f9e3b43)
  makes MySQL render booleans `true`/`false` in JSON output, so the 6 formerly-tracked
  boolean cases rejoined the strict corpus. The dual-dialect status is fully clean; the
  `KNOWN_DIVERGENT_TG_BLK_012` allowlist is retired to `Set.of()`, kept as an empty guard
  so any returning boolean-parity regression fails strict comparison loudly.
- **Manage with real artifacts**: the `/admin/graphql` management plane consumes the
  real `titanPackage`/`titanVerifyInstall` outputs (no fixture strings, no placeholder
  metadata, no kernel reflection); deployment activation is gated on a passed install
  verification, and rollback scripts are discovered and surfaced.
- **Serve the compiled demo from the database (opt-in)**: with `titan.graphql.execution.mode=sql`
  (default `java`), the Quarkus `/graphql` endpoint answers every request by calling the
  DEPLOYED stored functions over the configured datasource instead of the Java kernel —
  the proof as a demonstrable runtime. Every response names its engine
  (`X-Titan-Execution-Mode`, plus the package fingerprint in SQL mode), execution
  telemetry lands in the serving database's `titan_runtime.telemetry`, and an
  unreachable/undeployed database answers a descriptive 503 GraphQL error — never a
  silent fallback to Java mode. Demo walkthrough: see
  [docs/getting-started.md](docs/getting-started.md), "Serve GraphQL From The Database".

## Workflow

```bash
./gradlew test               # Docker-free tests (Java-mode reference + doc guards)
./gradlew titanPackage       # transpile + package migration artifacts (postgresql + mysql)
./gradlew titanVerifyInstall # install + verify against scratch PG + MySQL containers (Docker)
./gradlew integrationTest    # Java-vs-SQL equivalence on both dialects + live SQL-mode HTTP serving (Docker)
```

Plain `test` stays Docker-free; the SQL-mode legs are tagged `docker` and run under
`integrationTest`, mirroring core's convention.

## Honest Boundaries

- **Generic JDBC execution is the schema-portable path.** It currently covers integer-key point
  roots, direct one/many relations from point results, scalar and context filters, and the first
  forward page of root Relay connections. Cursor continuation, backward pagination, relation
  connections, computed SQL expressions, and batched relations beneath collection roots fail explicitly.
  Those are the next generic executor increments.
- **The transpiled SQL kernel remains demo-specific.** The 97-case SQL equivalence corpus is a
  strong Titan compiler proof, but its application rows are embedded in the bounded blog kernel.
  It does not yet prove that an arbitrary projection document becomes a database-resident GraphQL
  routine. Model-driven kernel generation remains required before `sql` mode is schema-portable.
  Artifact generation refuses to attach that demo package to a differently named/shaped model;
  a future package contract must bind the reviewed model's semantic hash directly.
- **Management storage: durable JDBC store available (opt-in `jdbc` mode); file-backed by
  default.** Core dogfooded the management store — it transpiles the management mutation
  routines in-tree and ships a durable JDBC-backed transactional store over them
  (`JdbcTransactionalMutationStore` + JDBC idempotency/audit stores + `ManagementSchemaInstaller`),
  proven on PG 16 + MySQL 8.4 — closing `TG-BLK-003`. This repo now runs on it: set
  `titan.graphql.management.store=jdbc` and the `/admin/graphql` plane persists through core's
  JDBC store on Titan-transpiled routines over the Quarkus datasource, with the schema bootstrapped
  at startup. `GraphqlJdbcManagementStoreIT` proves the `importModelDocument` path is durable
  (survives a new store instance), idempotent, and keeps deployment-activation gating on live
  PG 16 + MySQL 8.4. The DEFAULT remains `file` (file-backed/in-memory, Docker-free for plain
  `test` and dev), so durable-JDBC claims are scoped to jdbc mode. Two recorded routine-design
  gaps are known and non-blocking (adapter-side `activate_deployment` typed preconditions; the
  import routine's collapsed hash column).
- **SQL serving mode is opt-in and bounded.** The default `/graphql` engine remains the Java
  kernel; `titan.graphql.execution.mode=sql` (completion plan W5.1) serves the same demo
  schema and entry points from the deployed stored functions — no new GraphQL features, and
  the `/admin/graphql` management plane always executes in Java (only the application kernel
  is transpiled). Plan-level execution (`executeWithPlan`) stays a Java-mode surface.
- **Dual transpilation targets, both proven.** The kernel transpiles, packages, verifies,
  and proves equivalence cleanly for PostgreSQL and MySQL (W5.2). Both live database legs
  are green against core HEAD — **97/97 strictly equivalent on each dialect, zero
  divergences** — after core closed `TG-BLK-011` (identifier overflow, titan 5649ebb) and
  `TG-BLK-012` (MySQL boolean→JSON rendering, titan f9e3b43). The dual-dialect status is
  fully clean; no consumer-side workaround remains for either entry.
- Point roots currently require a single integer key. Inference preserves composite-key discovery
  but emits explicit primary- and foreign-key diagnostics rather than pretending the first
  component is sufficient. The projection adapter currently enforces the named `adminOnly`
  field policy and rejects relation policies it cannot enforce before reading.

## Documentation Map

For architecture context, continue with [docs/design.md](docs/design.md).

The bounded demo schema is described in [docs/generated-schema.md](docs/generated-schema.md).
The supported query surface is specified in [docs/query-contract.md](docs/query-contract.md)
and verified by [docs/query-contract-conformance.md](docs/query-contract-conformance.md).
The source-model format is documented in [docs/model-document-format.md](docs/model-document-format.md),
the public Java projection builder in [docs/projection-api.md](docs/projection-api.md),
and validation report text and JSON renderings in
[docs/validation-diagnostics.md](docs/validation-diagnostics.md).
The minimal mutation runtime lowering boundary is documented in
[docs/mutation-runtime-lowering-boundary.md](docs/mutation-runtime-lowering-boundary.md).

This repo vendors pinned Titan sources as Git submodules. Initialize them with
`git submodule update --init --recursive` before building.
