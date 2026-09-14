# Titan Core Uptake Report

Status: FINDINGS — EXECUTED via `docs/completion-plan.md` W0–W4 (W0 `253dd78`,
W1 `f02fb22`/`19b1baa`/`8d70412`, W2 `506f86c`, W3 `c9dda68`). This report is a
point-in-time record of the pre-uptake state; present-tense findings below
describe that state, not the current repo. Current truth lives in
`docs/titan-core-gap-ledger.md` ("Status After Core Uptake") and
`docs/titan-blocker-register.md`.
Date: 2026-06-12
Scope: what must change in `titan-graphql` to build against and exploit core Titan `main`
after the executed architecture improvement plan (Phases 0–6, ~36 commits, see
`vendor/titan/docs/architecture-audit-and-improvement-plan.md`, header `Status: EXECUTED`)
Method: composite-build probe against core HEAD (read-only on both repos; breakage reproduced
and fixes verified on temporary copies), full import/call-site survey, gap-ledger reconciliation

---

## 1. Executive Summary

titan-graphql consumes core through a Gradle **composite build** (`includeBuild("vendor/titan")`),
so it always compiles against core HEAD — there is no version pin to bump. Uptake therefore
reduces to four workstreams:

| # | Workstream | Effort | Nature |
|---|-----------|--------|--------|
| 1 | Replace the raw transpiler-jar file dependency with the new `titan-management` module | **S** | One-line build fix; everything else compiles and all 450 tests pass |
| 2 | Build-script touch-ups (`titanJdbc` driver, dead `strictMode` knob, redundant wiring) | **S** | Mechanical |
| 3 | Documentation/runbook refresh (migration names, quoting, verify workflow) | **M** | Mechanical |
| 4 | **Rewrite the demo kernel's scanner sites and recursion families** | **L** | The real cost: core's always-on validator now rejects what it previously only warned about — exactly the fail-branch the GAP-004 contract promised |

Nothing else breaks: titan-graphql has **no SQL snapshots, no JDBC, no direct
`TranspilationPipeline`/DSL-builder/test-harness usage**, so the identifier-quoting change,
DSL API churn, runtime executor rework, and harness rebuild are all invisible to it.

Beyond survival, core now offers seven concrete upgrades this project was built to want —
most notably: real artifact metadata to replace the GAP-005 placeholder branch, an automated
SQL-mode equivalence leg to replace the manual psql runbook, and `titanVerifyInstall` +
rollback scripts to feed the deployment-gating logic that `TitanGraphqlDurableManagementStore`
already implements against fixture strings.

One strategic warning: core amended GAP-006 — the SQL-backed management storage contract is
**specification-only** and the file-backed stores are explicitly test-grade. titan-graphql's
ledger language ("durable importModelDocument path") overstates what core now claims to
provide. See §6.

---

## 2. How titan-graphql Depends on Core (and the one defect in it)

- `settings.gradle.kts`: `includeBuild("vendor/titan")` for both plugins and libraries;
  `io.titan:titan-dsl:0.1.0` and the `io.titan.gradle` plugin resolve to live core projects.
- **Defect:** `build.gradle.kts:20` —
  `implementation(files("vendor/titan/titan-transpiler/build/libs/titan-transpiler-0.1.0.jar"))`.
  A raw file dependency that bypasses composite substitution, exists solely to reach
  `io.titan.management`, depends on a stale locally-built jar, and is a version-skew trap.
- No gradle wrapper of its own; the documented invocation is `./gradlew` from this repo.

## 3. Breakage Layers (reproduced, in build-execution order)

### 3.1 `titanIntrospect` / `titanGenerate` — missing `titanJdbc` driver (S)

Core's DDL introspection now defaults to **scratch-container mode** (`ddlMode="container"`):
the DDL is applied to a disposable Postgres container and introspected over JDBC, which
requires a driver on the new `titanJdbc` configuration:

```
No JDBC driver accepts URL 'jdbc:postgresql://localhost:51302/titan'.
The 'titanJdbc' configuration is empty.
```

Fix (preferred — keeps the higher-fidelity container mode):

```kotlin
dependencies {
    titanJdbc("org.postgresql:postgresql:42.7.4")
}
```

Alternative: `database.ddlMode.set("parser")` (no Docker needed; the fallback parser is now
strict and errors on unrecognized statements instead of silently dropping them — verified to
handle `ddl/postgres/titan_graphql_postgres.sql`). With the driver and Docker, container introspection
of the project's DDL **passes**.

### 3.2 `compileJava` — the management extraction (S)

All 61 compile errors share one root cause: `package io.titan.management does not exist`.
Core extracted the management layer out of `titan-transpiler` into a new **`titan-management`**
module (marked experimental). Affected files:

- `src/main/java/io/titan/graphql/management/TitanGraphqlDurableManagementStore.java` (imports
  `ManagementAudit/Commands/Idempotency/Records/Transactions`)
- `GraphqlManagementMutationSupport.java`
- `TitanGraphqlGap006CommandContext.java`
- 4 test files

Fix: delete the file-jar dependency and add `implementation("io.titan:titan-management:0.1.0")`
(composite-substituted). **Verified: with that single change, `compileJava`,
`compileTestJava`, and all 450/450 tests pass.** Core's management defect fixes are absorbed
silently — this consumer already uses `ManagementAudit.AuditRecord` (the surviving model, not
the deleted `ManagementRecords.AuditEvent`), and benefits transparently from `sortedErrors`
actually sorting, draft-metadata replay round-tripping, and exact-token `rejectTransportOwned`
matching (no more `"curl-team"`-contains-`"url"` false positives).

### 3.3 `titanTranspile` — the demo kernel no longer validates (L; the uptake mountain)

Core's `FeatureValidator` is now **always on** (`strictMode` no longer gates validation) with
positioned, aggregated diagnostics. The 6,511-line `demo/blog/DemoBlogTitanGraphqlFunctions.java`
fails in two waves:

**Wave 1 — 10 positioned TITAN-E001s** (lines 588, 923×2, 941×2, 969, 6408, 6426, 6444, 6462):
nested traversal scanners without non-negative cursor initialization / deterministic cursor
increment. The rewrite recipe was **probe-verified on a copy**: substring-segment scanning,
literal-`0` cursor initialization, exactly one increment per iteration, escape flags instead of
`charAt(position - 1)` lookback. All 10 clear with mechanical rewrites.

**Wave 2 — recursive helper cycles** (`TITAN-E001 Unsupported recursive helper call cycle`,
with exact cycle paths): **11 methods across 9 recursion families** —
`jsonInputObjectLiteral ↔ jsonInputValueLiteral`,
`validateGeneratedArticleFilterObject ↔ validateGeneratedArticleFilterList`, and the
self-recursive `fragmentCycleDetected`, `fragmentReachableFromSelection`,
`expandNamedFragmentsInSelection`, `pruneRuntimeDirectivesInSelection`,
`expandInlineFragmentsInSelection`, `typeReferenceSelectionValidationError`,
`renderTypeReferenceJson`. Each must become bounded iterative traversal under the validator's
state rules (no aliased traversal state, `charAt(cursor)` receiver matching, exactly-one
increment). The canonical accepted shape is documented in core: `GAP-004.md` and
`GAP004BoundedDelimiterScannerSqlModeIT`.

This is not a regression — it is the **fail branch GAP-004 always specified**. The old core
"tolerated" these as warnings while the ledger itself recorded them as out-of-contract; the
new core enforces the contract with exact positions and cycle paths. Until the kernel is
rewritten, `titanTranspile` → `titanPackage` → `assemble`/`build` are all blocked (core's
plugin now wires `assemble` through packaging).

## 4. Per-Area Verdicts

| Area | Verdict |
|------|---------|
| Management consumers (GAP-006) | Swap dependency → green (S). 450/450 tests pass |
| Build script | `titanJdbc` driver; `transpiler.strictMode.set(true)` is a dead knob (validation is unconditional; the new `strictWraparound` flag is not yet plugin-exposed — note for core); `compileJava dependsOn titanGenerate` is redundant (core wires generated sources into `sourceSets` now) (S) |
| Generated catalog sources | Regenerate; `ForeignKey` arrays→Lists and `Column` value-equality absorbed automatically. Zero action |
| DSL / runtime / harness APIs | Only `titan.dsl.StoredFunction` is imported (3 files). All DSL/executor/harness churn invisible. Zero action |
| SQL snapshots | None exist in this repo → universal identifier quoting has zero test impact; docs only |
| Docs/runbook (`docs/getting-started.md`) | Stale: "477 generated SQL files" count, unquoted SQL example, hand-copied `docs/sql/titan_runtime_postgres.sql` (core's runtime prelude grew `java_*`/`list_*`/`map_*`/`set_*` helpers and ships as `R__titan_010_runtime.sql`/`R__titan_020_routines.sql`); replace the manual install loop with the migration + `titanVerifyInstall` workflow (M) |

## 5. Opportunities (ranked by value)

1. **Wire GAP-005 consumption to real artifacts.** `TitanGraphqlGap005ArtifactMetadata.read(Path)`
   has no production caller — the adapter is exercised only from hand-built fixture JSON, and
   `TitanGraphqlGeneratedArtifactWorkflow` still emits a `metadataOnly` placeholder. Core's
   `titanPackage` now emits exactly the four files the adapter validates (schema versions and
   every hash field verified matching field-by-field). Point `read()` at the
   `deployment.migrationsDir` output and delete the placeholder branch.
2. **Automated SQL-mode equivalence leg.** The GAP-003 "equivalence" tests run the kernel's
   Java methods on *both* sides; the SQL leg is a manual psql runbook. Core now ships
   `EquivalenceOracle`/`ComparisonMode` (multiset comparison, type normalization, divergence
   diffs) and the rebuilt `TitanTestExtension` (per-test databases, singleton containers,
   `@Tag("docker")`/`integrationTest` convention). This automates the project's central
   product proof end-to-end.
3. **`titanVerifyInstall` + `titan-rollback.<dialect>.sql` as deployment evidence.**
   `TitanGraphqlDurableManagementStore.requirePassedArtifactRef` already gates deployment
   activation on verification status — feed it the real `titan-install-verification.json`
   (scratch-container verified, canonical-form drift detection) instead of fixture strings,
   and surface the rollback script in `TitanGraphqlDeployment`.
4. **Structured artifact metadata replaces runtime reflection.**
   `storedFunctionEntrypoints()` reflects over the kernel class; `GeneratedSql` now carries
   `artifactKind/schemaName/sqlName/sqlObjects/dependsOn` and the packaged inventory is
   structured — entry-point metadata can come from the artifact.
5. **A live SQL execution mode via `JdbcExecutor` + `ParameterizedSql`.** The repo currently
   has no JDBC at all. Core's executor (dialect-resolved construction, bind parameters,
   `executeReturning`, `inTransaction`, `TitanExecutionListener` telemetry) maps directly onto
   what a GraphQL runtime needs for live execution and usage reporting.
6. **MySQL as a second target.** Once the kernel transpiles, add `"mysql"` to
   `transpiler.targets`: `DialectCapabilities` turns infeasible constructs into compile-time
   diagnostics (TITAN-W005 for version floors), and core's MySQL differential leg pattern is
   available for conformance evidence.
7. **Possible simplification of `ProductStateJournal`** — it partially duplicates persistence
   because old core dropped draft metadata on replay; that defect is fixed. Weigh against §6
   before deepening reliance on the file stores.

## 6. Gap-Ledger Reconciliation (action: update `docs/titan-core-gap-ledger.md`)

- **GAP-006 — contradicted.** Core's amendment (2026-06-11) states the SQL-backed storage
  contract is **specification-only**; the file-backed stores are test-grade and the module is
  experimental with zero production callers. The ledger's "durable transactions … consumed for
  the durable `importModelDocument` path" must be requalified to *file-backed/test-grade*;
  `TitanGraphqlDurableManagementStore` consumes an explicitly non-production durability
  boundary. Decision needed: accept that posture for the demo, or raise a core requirement
  for the JDBC-backed store.
- **GAP-004 — contract satisfied; ledger narrative falsified.** The ledger records the
  recursive-helper warnings as "tolerated"; they are now hard errors with exact positions and
  cycle paths (the contract's fail branch). Update the narrative and rewrite the kernel (§3.3).
- **GAP-005 — satisfied and strengthened**; adapter verified forward-compatible field-by-field;
  consumption is fixture-only (see Opportunity 1).
- **GAP-003 — satisfied**; the equivalence oracle the ledger wanted now exists and is
  unexploited.
- Conformance profile: all 42 rows `ACCEPTED` and `assertProductionReady` passes, but the
  SQL-mode *evidence* behind them is stale until the kernel transpiles again.

## 7. Recommended Sequencing

1. **PR 1 (hours):** dependency fix (`titan-management`), `titanJdbc` driver, remove dead
   `strictMode` knob and redundant task wiring. Result: compiles, all tests green,
   introspect/generate green.
2. **PR 2 (the mountain, plan ~days):** kernel rewrite — Wave 1 scanner sites (mechanical,
   recipe proven), then the 9 recursion families to bounded iterative form, validating each
   against `titanTranspile` incrementally (diagnostics give exact positions/cycles). Result:
   full `build` green, conformance evidence fresh.
3. **PR 3 (M):** docs/runbook refresh + ledger requalification (GAP-004 narrative, GAP-006
   durability language).
4. **PR 4+ (value capture):** Opportunities 1–3 first — they convert existing
   placeholder/fixture/manual paths into the real artifacts core now produces.

## 8. Core Follow-Ups Surfaced by This Examination

- `strictWraparound` is not exposed through `TitanExtension`/`TitanTranspileTask` — plugin
  knob missing (core deferred-items register candidate).
- The pipeline's 10-arg `transpile` overload still accepts a `strictMode` parameter it never
  reads — should be removed or repurposed in a future API cleanup.
