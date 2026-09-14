# titan-graphql

Titan GraphQL is a schema-driven GraphQL layer built around Titan. Titan codegen discovers
database structure, a reviewed projection document controls exposure and policy, and a generic
executor turns validated selections into parameterized Titan DSL reads. The project also
stress-tests Titan by transpiling its bounded GraphQL kernel into database-resident functions.

This project is licensed under [GPL-3.0-or-later](LICENSE). It is a bounded proof project;
read [SECURITY.md](SECURITY.md) before exposing either HTTP endpoint.

The product goal is to put GraphQL over supported schemas without handwritten read resolvers or
queries. Custom mutations remain explicit application code. Model-driven database read carriers
now compile and install beside the fixed demo kernel; routing validated GraphQL plans through those
carriers is available as the opt-in `compiled` runtime. Two independently generated packages now
prove that route against unrelated blog and commerce models. Expanding its supported plan shapes
and promoting it over the legacy `sql` mode is the remaining migration.

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
- **Generate model-bound database reads:** `titanGraphqlGenerateRoutines` validates the selected
  reviewed model and deterministically emits static point, connection-page, and direct-relation
  carriers plus a model-hash attestation routine. The same generator produces unrelated
  customers/orders routines without blog names or fixture rows. Titan transpiles these carriers
  for PostgreSQL and MySQL; live tests prove that they read current rows, expose a reviewed
  computed expression, enforce the first fail-closed context predicate, and omit an unguarded
  protected scalar. See [docs/generated-routines.md](docs/generated-routines.md).
- **Execute GraphQL through generated routines:** `titan.graphql.execution.mode=compiled` uses the
  generic parser, validator, and planner, resolves generated entry points from the verified package
  inventory, and normalizes PostgreSQL JSONB functions and MySQL result-set procedures behind one
  data model. The dual-dialect live proof covers aliases, a point root, a direct relation, computed
  output, forward/backward cursors, page info, exact count, fail-closed row visibility, and a
  direct relation beneath a collection in one bounded batch rather than N+1 reads.
- **Prove schema independence with an isolated package:** `commerceIntegrationTest` generates,
  transpiles, packages, install-verifies, binds, deploys, and serves a customers/orders model from
  a separate build tree. Its package inventory contains only generated carriers—no demo-blog class
  or article routine—and the same runtime observes live mutations and a fresh-engine restart on
  PostgreSQL and MySQL.
- **Transpile (both dialects):** `titanTranspile` lowers the generated carriers and the transitional
  demo-blog whole-request kernel (`DemoBlogTitanGraphqlFunctions`) into PostgreSQL **and MySQL**
  routines with zero validator diagnostics. Its inputs are an explicit allowlist; unrelated
  application, HTTP, inference, artifact, and management records do not enter the SQL package.
- **Package (both dialects)**: `titanPackage` produces deterministic migration artifacts
  per dialect (`R__titan_010_runtime.sql` + `R__titan_020_routines.sql`) plus
  manifest/inventory/install-plan/verification JSON and rollback scripts, with zero
  duplicate identities.
- **Bind the reviewed model exactly**: `titanGraphqlBindPackage` validates the selected model,
  runs package/install verification, and writes the deterministic
  `titan-graphql-package.json` sidecar. It binds the normalized model semantic hash to Titan's
  artifact id, manifest hash, and source-input hash. SQL serving rejects a missing or stale local
  binding before opening the datasource and calls the generated attestation routine before its
  first request, so deploying the otherwise-valid package to the wrong database also fails closed.
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
./gradlew titanGraphqlGenerateRoutines # reviewed model -> deterministic Titan carrier source
./gradlew titanPackage       # transpile + package migration artifacts (postgresql + mysql)
./gradlew titanVerifyInstall # install + verify against scratch PG + MySQL containers (Docker)
./gradlew titanGraphqlBindPackage # verify and bind package to the reviewed model (Docker)
./gradlew integrationTest    # Java-vs-SQL equivalence on both dialects + live SQL-mode HTTP serving (Docker)
./gradlew commerceIntegrationTest # isolated generated-only commerce package on both dialects (Docker)
./gradlew compiledSchemaIntegrationTest # both independently packaged schema proofs (Docker)
```

Plain `test` stays Docker-free; the SQL-mode legs are tagged `docker` and run under
`integrationTest`, mirroring core's convention.

## Honest Boundaries

- **Generic JDBC execution is the schema-portable path.** It currently covers integer-key point
  roots, direct one/many relations from point results, scalar and context filters, and the first
  forward page of root Relay connections. Cursor continuation, backward pagination, relation
  connections, computed SQL expressions, and batched relations beneath collection roots fail explicitly.
  Those are the next generic executor increments.
- **Compiled mode is generic but still bounded; legacy SQL mode is demo-specific.** A reviewed model
  now becomes Titan-compiled point, page, relation, computed-field, and attestation routines, and
  `compiled` mode executes supported GraphQL plans through those inventory-resolved routines. The
  older `sql` route still calls the bounded `DemoBlogTitanGraphqlFunctions` whole-request entry
  point and remains only an equivalence/compiler proof. Generated carriers do not yet cover
  arbitrary generated filters/order,
  counts for relation connections, protected-field policy branches, relation connections, or
  nested multi-level batching. Direct relations immediately beneath collection roots use fixed
  generated batch arities and do not issue one query per parent. A separately packaged unrelated
  commerce model now proves the generic runtime and generated-only inventory independently of the
  demo package. The next increments expand compiled-plan coverage, then retire the demo
  whole-request kernel from production dispatch.
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
the compiled carrier contract in [docs/generated-routines.md](docs/generated-routines.md),
and validation report text and JSON renderings in
[docs/validation-diagnostics.md](docs/validation-diagnostics.md).
The minimal mutation runtime lowering boundary is documented in
[docs/mutation-runtime-lowering-boundary.md](docs/mutation-runtime-lowering-boundary.md).

This repo vendors pinned Titan sources as Git submodules. Initialize them with
`git submodule update --init --recursive` before building.
