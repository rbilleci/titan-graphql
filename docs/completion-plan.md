# titan-graphql Completion Plan

**OVERALL STATUS: W0–W5 complete.** Both the PostgreSQL and MySQL legs are
**97/97 strictly equivalent (zero divergences)** after core closed
`TG-BLK-011`/`TG-BLK-012`. The management plane runs on core's dogfooded
JDBC-backed management store in opt-in `jdbc` mode
(`titan.graphql.management.store=jdbc`); the file-backed store remains the
default. `TG-BLK-003` is CLOSED — RD-1, RD-2, and the E032 residual are all
closed in core (GAP-006 Status Amendment 2), so the JDBC adapter is a pure
pass-through and the dogfooded store is byte-faithful to the file store.

Status: EXECUTED — W0–W4 done (W0 `253dd78`; W1 `f02fb22`, `19b1baa`, `8d70412`,
plus packaging workarounds `69172a0`/`ec96b25`; W2 `506f86c`; W3 `c9dda68`;
W4 this truthfulness pass). W5.1 (live SQL execution mode) executed 2026-06-13 after an
explicit approval; W5.2 (MySQL) executed 2026-06-13 after its own approval — transpile,
package, verify, and equivalence now green on BOTH dialects (97/97 strict each) after core
closed TG-BLK-011 and TG-BLK-012 (see §W5.2).
Date: 2026-06-12 (W5.1 addendum 2026-06-13; W5.2 addendum 2026-06-13)
Consolidates: `docs/titan-core-uptake-report.md` (build/uptake findings),
`docs/titan-core-gap-ledger.md` (contract state), the product/architecture survey of this
repo, and the executed core plan (`vendor/titan/docs/architecture-audit-and-improvement-plan.md`,
Status: EXECUTED).
Companion artifact: `docs/titan-blocker-register.md` (the boundary-keeping mechanism this
plan mandates — created with this plan).

---

## 1. What "Complete" Means for This Product

titan-graphql is, by its own definition, a **stress-test proof project**: demonstrate that
Titan can transpile a bounded-but-real GraphQL query engine (parser, validator, planner,
execution) into deployable SQL stored functions whose behavior is provably equivalent to the
Java original. It is not, and should not drift toward, a production GraphQL server.

**Definition of Done:**

1. The demo kernel transpiles cleanly under core's always-on validator (zero TITAN-E001s).
2. The transpiled artifacts deploy to a live PostgreSQL via the core migration/packaging
   pipeline, verified by `titanVerifyInstall`.
3. **SQL-mode conformance evidence is automated**: the 42-row conformance matrix re-evidenced
   with the SQL leg executing on a live database and compared to the Java leg through core's
   equivalence oracle — no manual psql runbook.
4. The management plane consumes **real** core artifacts (manifest/inventory/plan/verification
   from `titanPackage`/`titanVerifyInstall`), with its durability claims requalified to match
   what core actually provides.
5. The gap ledger and docs tell the truth about all of the above.
6. Stretch (explicitly optional): MySQL as a second transpilation target; a live SQL
   execution mode behind the HTTP layer.

## 2. The Product Boundary (non-negotiable rules)

titan is the platform; titan-graphql is a consumer. To keep the boundary clean:

- **Consume only titan's published surface**: the Gradle plugin tasks and `TitanExtension`,
  `titan.dsl` annotations/DSL, `titan-management`'s public API, packaged artifacts
  (migrations, manifest/inventory/plan/verification JSON, rollback scripts), and
  `titan-runtime-jdbc` (executor, equivalence oracle, test harness). No imports from
  `io.titan.transpiler` internals, no reaching into core build outputs by file path
  (the uptake report's file-jar dependency was exactly this defect — eliminated in W0).
- **Never patch titan from this repo's workstreams.** When titan-graphql needs something
  titan doesn't provide — or titan misbehaves — the work item does NOT mutate core.
  Instead: (1) record an entry in `docs/titan-blocker-register.md` (format defined there),
  (2) apply the cheapest boundary-respecting workaround in titan-graphql, marked with the
  blocker ID in a code comment, (3) proceed. Core picks blockers up on its own schedule —
  this is the same contract process that produced TITAN-GQL-GAP-001..006, formalized.
- **Conformance to core contracts over convenience**: where core's validator rejects kernel
  code (GAP-004 fail branch), the kernel is rewritten to the contract — the rejection is the
  product working as designed, not a blocker.

## 3. Consolidated Current State (at plan time — superseded by execution; see §4 statuses)

| Layer | State | Source |
|-------|-------|--------|
| Build vs new core | Broken in 3 layers; layer 1+2 are Small fixes (titan-management dep, titanJdbc driver); verified: with them, compile + all 450 tests green | Uptake report §3 |
| GraphQL engine (parser/validator/planner/introspection, ~28K LOC) | Works end-to-end in **Java mode** over an HTTP server — against a 5-row in-memory fixture dataset | Product survey |
| SQL mode (the product's reason to exist) | **Blocked**: kernel fails core's validator (10 scanner sites + 9 recursion families); no automated SQL-mode tests have ever existed; conformance matrix evidence is Java-vs-Java | Uptake report §3.3, survey |
| Management plane (GAP-006 consumer) | Functional on file-backed stores; gates deployments on verification status — fed by **fixture strings**, not real artifacts | Survey, uptake §C3 |
| GAP-005 artifact adapter | Forward-compatible with core's real outputs (verified field-by-field) but **fixture-only**; production path emits a `metadataOnly` placeholder | Uptake §C1 |
| Gap ledger | GAP-003/004/005 satisfied by core (narratives stale); GAP-006 **contradicted** by core's spec-only durability amendment | Uptake §6 |
| Docs/runbook | Stale: counts, unquoted SQL examples, hand-copied runtime SQL, manual install loop | Uptake §4 |

## 4. Workstreams

### W0 — Uptake foundation (Small; unblocks everything) — DONE (`253dd78`)
Replace the transpiler file-jar with `io.titan:titan-management` via composite substitution;
add `titanJdbc` driver; delete the dead `strictMode` knob and redundant `compileJava`
wiring; regenerate the catalog. Exit: compile green, 450 tests green, introspect/generate
green. (= Uptake report PR 1.)

### W1 — Kernel rewrite to the GAP-004 contract (Large; the mountain) — DONE
(wave 1 `f02fb22`; wave 2 `19b1baa` + `8d70412`, transpile clean; packaging
green after TG-BLK-004/005 workarounds `69172a0`/`ec96b25`)
Two waves against `demo/blog/DemoBlogTitanGraphqlFunctions.java` (6,511 lines):
- **Wave 1 (Medium, mechanical)**: the 10 scanner sites — substring-segment scanning,
  literal-0 cursors, single increment, escape flags. Recipe already probe-verified.
- **Wave 2 (Large)**: the 9 recursion families (fragment expansion, directive pruning,
  filter validation, JSON literal rendering, type-reference rendering) become bounded
  iterative traversals per core's documented state rules (`GAP-004.md`,
  `GAP004BoundedDelimiterScannerSqlModeIT` as the canonical accepted shape). Work
  family-by-family; `titanTranspile` gives exact positions/cycle paths as the progress gauge.
  Java-mode tests are the behavioral safety net for each rewrite (450 tests must stay green
  throughout — the rewrite must not change observable Java behavior).
Exit: `titanTranspile` green for PostgreSQL; full `build` green.

### W2 — SQL-mode proof (Medium; the product's actual finish line) — DONE (`506f86c`)
(97-case corpus, 0 divergences on live PG; 41/42 matrix rows SQL-evidenced;
manual psql runbook deleted; surfaced TG-BLK-006/007, worked around in-kernel)
- Deploy transpiled artifacts via core migrations to a live PG container.
- Build the automated equivalence leg on core's `TitanTestExtension` + `EquivalenceOracle`
  (`@Tag("docker")`/`integrationTest` convention): the same conformance corpus that drives
  the Java-mode matrix executes the deployed SQL functions and compares structured results
  (use the oracle's unordered mode + JSON-aware normalization; GAP-003's semantic-JSON
  machinery already exists here).
- Re-evidence the 42-row conformance matrix in SQL mode; rows that cannot be SQL-evidenced
  get demoted honestly (or register a blocker if the cause is core).
Exit: `integrationTest` runs Java-vs-SQL equivalence green on live PG; conformance matrix
cites SQL-mode evidence; the manual psql runbook is deleted.

### W3 — Real artifact pipeline (Medium) — DONE (`c9dda68`)
(placeholder + reflection paths deleted; activation gated on passed
verification; rollback surfaced; registered TG-BLK-008)
- GAP-005: point `TitanGraphqlGap005ArtifactMetadata.read()` at `titanPackage` output;
  delete the `metadataOnly` placeholder branch.
- Deployment evidence: feed real `titan-install-verification.json` (from `titanVerifyInstall`)
  into `requirePassedArtifactRef`; surface `titan-rollback.postgresql.sql` in
  `TitanGraphqlDeployment`.
- Entry-point metadata from the structured inventory (`artifactKind/sqlObjects/dependsOn`)
  instead of runtime reflection over the kernel class.
Exit: no fixture-string artifact paths remain in production code; management plane decisions
trace to real core outputs.

### W4 — Truthfulness pass (Small/Medium) — DONE
(gap ledger narratives rewritten with W0–W3 commit refs; durability claims
requalified to file-backed/test-grade with the TG-BLK-003 marker in code;
hand-copied runtime SQL deleted; README/uptake-report/validation docs aligned
with observable behavior; register cross-checked against code markers)
- Gap ledger: GAP-004 narrative ("tolerated warnings" → enforced contract, kernel rewritten);
  GAP-006 durability language requalified to file-backed/test-grade per core's amendment
  (decision recorded: acceptable for a proof project — the production store remains titan's
  gap, see register TG-BLK-003).
- Docs/runbook refresh: migration-based install + verify workflow, quoted-SQL examples,
  delete the hand-copied runtime SQL. (= Uptake report PR 3.)
Exit: every claim in docs/ledger matches observable behavior.

### W5 — Stretch (explicitly optional, in priority order) — W5.1 DONE (2026-06-13); W5.2 DONE (2026-06-13, both dialects 97/97 strict after TG-BLK-011 + TG-BLK-012 closed)
1. **Live SQL execution mode** — DONE: opt-in `titan.graphql.execution.mode=sql` routes the
   Quarkus `/graphql` endpoint to the deployed SQL functions over the Agroal datasource
   (`GraphqlSqlModeRuntime` + `GraphqlSqlEntryPointDispatch`, the W2 corpus dispatch
   extracted to main), with `TitanExecutionListener`/`JdbcTelemetrySink` telemetry into the
   serving database, a descriptive 503 failure path (no silent fallback), and a mode surface
   (`X-Titan-Execution-Mode` / `X-Titan-Deployment-Fingerprint`). `JdbcExecutor` itself is
   not usable for routine invocation (no parameterized raw-SQL path — TG-BLK-010); plain
   prepared dispatch mirrors its observed-execution contract. Surfaced TG-BLK-009/010.
   Bounded scope held: same 5-entity demo schema, same entry points, no new GraphQL features.
   Evidence: `GraphqlExecutionModeTest` (Docker-free), `GraphqlSqlModeHttpIT` (live HTTP
   serving, telemetry, fingerprint), getting-started §4 demo flow.
2. **MySQL second target** — DONE 2026-06-13; both dialects fully proven on live
   databases. What landed: `"mysql"` added to `transpiler.targets`; `titanTranspile`
   green for BOTH dialects with ZERO `DialectCapabilities` compile-time rejections
   (the consumer-scale kernel needed no MySQL-specific rewrites); `titanPackage` green
   for both dialects (~1,700 objects each, zero duplicate identities); MySQL demo DDL
   (`ddl/mysql/titan_graphql_mysql.sql`; ddl/ restructured per-dialect so postgres-only
   introspection keeps its file set); the corpus deployment helper gained a
   DELIMITER-aware MySQL leg and the 97-case corpus a Java-vs-MySQL IT
   (`GraphqlSqlModeEquivalenceMySqlIT`); conformance matrix gained a MySQL Evidence
   column. The pre-registered risk fired, but not where expected: the string-processing
   kernel transpiled cleanly, and instead core's qualified record/enum naming
   (titan 705180d) overflowed BOTH dialects' identifier limits (TG-BLK-011), and the
   first full MySQL per-case run then exposed boolean→JSON rendering (`1`/`0` vs
   `true`/`false`, TG-BLK-012). Core closed both — TG-BLK-011 (titan 5649ebb,
   identifier-length truncation) and TG-BLK-012 (titan f9e3b43, B-10 boolean rendering).
   Result: `titanVerifyInstall` passes both dialects (postgresql 1452 objects / mysql
   1438, zero diagnostics), and BOTH equivalence legs are **97/97 strictly equivalent,
   zero divergences** against core HEAD. No boundary-respecting consumer workaround was
   ever needed for either entry; every leg stays wired fail-loud.

## 5. Sequencing and Effort

```
W0 (S) ──► W1 wave 1 (M) ──► W1 wave 2 (L) ──► W2 (M) ──► W3 (M) ──► W4 (S/M) ──► W5 (opt)
                                                   └── W3 can start in parallel with W2
                                                       (different files; both need W1)
```

W0 lands first and alone (it is also independently valuable: it un-breaks the build today).
W1 is the critical path; everything that matters waits on it. W4 trails because its content
depends on what W2/W3 actually prove.

## 6. Blocker Protocol (summary; full format in the register)

A **titan blocker** is any item where progress on this plan requires a change in titan —
missing API, defect, undelivered contract. Protocol: register entry (ID `TG-BLK-NNN`,
what/where/workaround/what-titan-must-deliver/evidence) → boundary-respecting workaround
marked with the ID → proceed. Entries are mirrored to titan's planning docs when core picks
them up. Three entries are pre-seeded from the uptake examination (strictWraparound plugin
exposure; dead strictMode parameter; GAP-006 production store). The register, not chat or
memory, is the source of truth for the titan↔titan-graphql gap.

## 7. Risks

| Risk | Mitigation |
|------|-----------|
| W1 wave-2 rewrites change Java-mode behavior subtly | 450-test suite green after every family; equivalence leg (W2) later re-proves vs SQL |
| Validator rules force kernel shapes that hurt readability badly | Acceptable for a proof project; document the patterns; if a rule is genuinely unreasonable, that's a register entry with the concrete case |
| SQL-mode equivalence surfaces real transpiler bugs under the GraphQL workload | That is the product working — exactly what a stress-test exists to find. Register entries with minimal reproductions; core's generative/deployability gates make fixes fast to verify |
| Scope creep toward "production GraphQL server" | §1 Definition of Done + §2 boundary rules; W5 is explicitly optional and bounded |
| Core API drift during execution (composite build tracks HEAD) | Acceptable while both repos co-evolve; if it bites, pin core to a tag and register the breakage |
