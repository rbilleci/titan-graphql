# titan-graphql

Titan GraphQL is a stress-test project for Titan: write a bounded GraphQL query engine in Java, annotate the top-level entrypoints, and transpile the complete parser/validator/planner/executor logic into database-resident stored function SQL.

This project is licensed under [GPL-3.0-or-later](LICENSE). It is a bounded proof project;
read [SECURITY.md](SECURITY.md) before exposing either HTTP endpoint.

The goal is not to build a full GraphQL server first. The goal is to create a credible, falsifiable proof that Titan can move nontrivial interpreter-style Java logic into the database while preserving correctness, security boundaries, and observable plan shape.

New developers should start with [docs/getting-started.md](docs/getting-started.md).

## What The Proof Demonstrates Today

The full pipeline is automated and green on BOTH dialects (completion plan W0–W5, see
[docs/completion-plan.md](docs/completion-plan.md)):

- **Transpile (both dialects)**: `titanTranspile` lowers the demo-blog GraphQL kernel
  (`DemoBlogTitanGraphqlFunctions`, ~6,900 lines, plus its supporting types) into
  PostgreSQL **and MySQL** stored functions with zero validator diagnostics — the kernel
  was rewritten to core's GAP-004 bounded-traversal contract (539 generated SQL files per
  dialect; a 32K-line routine bundle each). The MySQL target (completion plan W5.2) sailed
  through `DialectCapabilities` with zero compile-time rejections.
- **Package (both dialects)**: `titanPackage` produces deterministic migration artifacts
  per dialect (`R__titan_010_runtime.sql` + `R__titan_020_routines.sql`) plus
  manifest/inventory/install-plan/verification JSON and rollback scripts —
  currently ~1,700 generated objects per dialect, zero duplicate identities.
- **Verify (both dialects)**: `titanVerifyInstall` installs the package into scratch
  PostgreSQL and MySQL containers and verifies objects, routine signatures, and drift —
  passing on BOTH dialects at core HEAD (postgresql 1452 objects verified / mysql 1438,
  zero diagnostics) since core's `TG-BLK-011` identifier-limit fix (titan 5649ebb).
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
- **Serve live from the database (opt-in)**: with `titan.graphql.execution.mode=sql`
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

## The Blocker Register

titan-graphql never patches core. Every gap or defect in Titan discovered while driving
this proof is recorded in [docs/titan-blocker-register.md](docs/titan-blocker-register.md)
(`TG-BLK-001`..`TG-BLK-012`) with a boundary-respecting workaround marked by the blocker ID
in code comments where one exists. The register — not chat or memory — is the source of
truth for the titan↔titan-graphql gap; core picks entries up on its own schedule. The live
database legs alone surfaced four transpiler defects that signature-level verification
could not catch (`TG-BLK-006`, `TG-BLK-007` — fixed by core, workarounds reverted —
`TG-BLK-011` identifier overflow — fixed by core, recovery verified on both dialects —
and `TG-BLK-012` MySQL boolean→JSON rendering — fixed by core in titan f9e3b43, MySQL leg
now 97/97 strict) — exactly what a stress test exists to find. All four are now closed.

## Honest Boundaries

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
- The engine remains a bounded demo surface (query-only application contract, 5-entity
  demo-blog model), by design.

## Documentation Map

For architecture context, continue with [docs/design.md](docs/design.md).

The original M1-M6 projection-metamodel roadmap is complete for the bounded demo-blog proof;
see [docs/roadmap.md](docs/roadmap.md) and
[docs/generated-schema.md](docs/generated-schema.md) for the generated demo schema surface.
The query-contract planning pass starts with
[docs/query-contract-questionnaire.md](docs/query-contract-questionnaire.md)
and is materialized in [docs/query-contract.md](docs/query-contract.md), implemented through
[docs/query-contract-roadmap.md](docs/query-contract-roadmap.md), and tracked in
[docs/query-contract-conformance.md](docs/query-contract-conformance.md).
The productization proposal is [docs/developer-experience-proposal.md](docs/developer-experience-proposal.md),
with execution planning in [docs/developer-experience-roadmap.md](docs/developer-experience-roadmap.md).
The demo/core split goal is [docs/titan-core-feedback-goal.md](docs/titan-core-feedback-goal.md);
the resulting (closed) core gap series is tracked in
[docs/titan-core-gap-ledger.md](docs/titan-core-gap-ledger.md), consumed via
[docs/titan-core-handoff-roadmap.md](docs/titan-core-handoff-roadmap.md), and succeeded by
the live [docs/titan-blocker-register.md](docs/titan-blocker-register.md).
The first source-format reference is [docs/model-document-format.md](docs/model-document-format.md).
The public Java projection builder is documented in [docs/projection-api.md](docs/projection-api.md).
Validation report text and JSON renderings are documented in [docs/validation-diagnostics.md](docs/validation-diagnostics.md).
The minimal mutation runtime lowering boundary is documented in [docs/mutation-runtime-lowering-boundary.md](docs/mutation-runtime-lowering-boundary.md).

This repo vendors pinned Titan sources as Git submodules. Initialize them with
`git submodule update --init --recursive` before building. Supporting core documentation is in
`vendor/titan`:
the core execution record is [vendor/titan/docs/architecture-audit-and-improvement-plan.md](vendor/titan/docs/architecture-audit-and-improvement-plan.md);
the core security posture for SQL-text safety is [vendor/titan/docs/security-review-sql-text-safety.md](vendor/titan/docs/security-review-sql-text-safety.md);
the durable JDBC management store this repo runs on in `jdbc` mode is specified in
[vendor/titan/docs/management-store-dogfood-plan.md](vendor/titan/docs/management-store-dogfood-plan.md)
and its [vendor/titan/docs/management-store-dogfood-spike.md](vendor/titan/docs/management-store-dogfood-spike.md);
and the dialect-onboarding contract behind dual-dialect support is [vendor/titan/docs/adding-a-dialect.md](vendor/titan/docs/adding-a-dialect.md).
