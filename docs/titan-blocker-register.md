# Titan Blocker Register

Status: LIVE DOCUMENT
Purpose: the single source of truth for gaps in core Titan discovered while driving
titan-graphql to completion (`docs/completion-plan.md` §6). titan-graphql never patches core;
it records the gap here, applies a boundary-respecting workaround marked with the blocker ID,
and proceeds. Core consumes this register on its own schedule (the successor to the
TITAN-GQL-GAP-00x ledger process that produced core's GAP-001..006).

## Entry format

```
### TG-BLK-NNN: <title>
- Date / Found during: <date> / <workstream or activity>
- Titan area: <module/class/doc>
- What is blocked here: <the titan-graphql work item affected>
- Workaround applied: <what this repo does instead, with code-comment markers>
- What titan must deliver to close: <concrete, testable>
- Severity for this product: blocking | degrading | cosmetic
- Status: open | picked-up-by-core | closed (titan commit/ref)
```

---

### TG-BLK-001: `strictWraparound` is not exposed through the Gradle plugin
- Date / Found during: 2026-06-12 / core uptake examination
- Titan area: `titan-gradle-plugin` (`TitanExtension`, `TitanTranspileTask`) — the pipeline's
  `strictWraparound` flag (integer-overflow wraparound emulation, core plan Phase 2.4) is
  reachable only via the programmatic 10-arg `transpile` overload
- What is blocked here: opting the demo kernel into Java-exact wraparound semantics from the
  build script, should the GraphQL workload need it
- Workaround applied: none needed yet — default fail-loud overflow parity is acceptable for
  the proof; revisit if W2 equivalence surfaces an overflow-semantics divergence
- What titan must deliver to close: `titan { transpiler { strictWraparound.set(true) } }`
  plumbed to the pipeline, with a TestKit test
- Severity for this product: cosmetic (today)
- Status: open (also listed in core's deferred-items register, plan Execution Summary)

### TG-BLK-002: dead `strictMode` parameter on the transpile API
- Date / Found during: 2026-06-12 / core uptake examination
- Titan area: `titan-transpiler` `TranspilationPipeline.transpile(...)` — the 10-arg overload
  accepts `strictMode` but never reads it (validation became unconditional in core Phase 0.8);
  the plugin's `transpiler.strictMode` extension property is likewise a no-op
- What is blocked here: nothing functionally; it misleads build-script authors (this repo
  carried `strictMode.set(true)` believing it did something)
- Workaround applied: the knob is removed from this repo's `build.gradle.kts` (W0)
- What titan must deliver to close: remove or repurpose the parameter and the extension
  property; deprecation note in the plugin docs
- Severity for this product: cosmetic
- Status: open

### TG-BLK-003: no production-grade management storage (GAP-006 amended to spec-only) — CLOSED
- Date / Found during: 2026-06-12 / gap-ledger reconciliation
- Closed: 2026-06-14 / dogfood campaign Phase C
- Titan area: `titan-management` — core's GAP-006 amendment formerly stated the SQL-backed storage
  contract was specification-only; `FileTransactionalMutationStore` (file-backed, single-process,
  test-grade) was the only implementation
- What was blocked here: any claim that the `/admin/graphql` management plane is production-
  durable; `TitanGraphqlDurableManagementStore` consumed an explicitly non-production boundary
- How core closed it: core dogfooded the management store — it transpiles the management MUTATION
  routines in-tree and ships a JDBC-backed transactional store over them
  (`JdbcTransactionalMutationStore` + `JdbcIdempotencyStore` + `JdbcAuditStore`, plus
  `ManagementSchemaInstaller` for the schema+routines bundle). Core commits:
  - `35dc04d` feat(B0-B2): titan-management-routines — dogfooded store mutations transpiled in-tree
  - `0498692` feat(B3-B5): JDBC management store + dogfood IT — durable, concurrent, on transpiled SQL
  - `e238ae7` chore(B6): retire spike, pin module boundary, amend GAP-006 — Phase B complete
  Proven durable + concurrent by core's `JdbcManagementStoreDogfoodIT` on PostgreSQL 16 + MySQL 8.4.
- How this product uptook it (Phase C): the consumer now RUNS on the JDBC store in an opt-in
  `jdbc` mode. `titan.graphql.management.store=jdbc` builds `TitanGraphqlDurableManagementStore`
  over `JdbcTransactionalMutationStore` from the Quarkus datasource (the same W5a Agroal datasource
  the SQL execution mode serves from), bootstrapping the management schema+routines once at startup
  via `ManagementSchemaInstaller`. `GraphqlJdbcManagementStoreIT` (`@Tag("docker")`) drives the
  `importModelDocument` management mutation path on LIVE PG 16 + MySQL 8.4 and proves the import
  persists THROUGH the JDBC store (a new store instance over the same datasource reads it back),
  idempotency holds (one mutation per key; conflicting input refused), and the deployment-activation
  gating still holds. `file` remains the DEFAULT (Docker-free plain `test` + dev), so this is an
  honest opt-in, not a forced swap.
- Residue: NONE substantive. The two recorded routine-design gaps (RD-1, RD-2) AND the E032
  residual that this entry formerly tracked as open are now ALL CLOSED in core (GAP-006 Status
  Amendment 2; commits `132a7bd` (RD-1/RD-2 — typed-status activate fn + distinct hashes) and
  `6e8e17f` (E032 — activate 7-for-7 server-side)). Concretely: (1) `activate_deployment` is now a
  value-returning `@StoredFunction` that runs ALL 7 activation preconditions
  (`TITAN-MGMT-E030`..`E036`, including the E032 GAP-005 metadata-path suffix match) SERVER-SIDE and
  returns a typed status code, so the JDBC adapter is a PURE PASS-THROUGH (its ~36-line precondition
  duplication is gone); (2) `import_model_document` now carries three DISTINCT input/output/document
  hashes, each in its own column, so the dogfooded store is BYTE-FAITHFUL to the file store. Proven
  end-to-end on live PG 16 + MySQL 8.4.
- Severity for this product: degrading → RESOLVED
- Status: CLOSED (durable JDBC store available, opt-in `jdbc` mode; `file` remains the default;
  RD-1/RD-2/E032 all closed in core)

### TG-BLK-004: enum accessor helpers emitted once per referencing class; `titanPackage` rejects the duplicate object ids
- Date / Found during: 2026-06-12 / W1 wave 2 group B exit verification (first time `titanPackage`
  ever ran over the full kernel output — transpilation previously failed on the GAP-004 cycles)
- Titan area: `titan-transpiler` SQL emission + `titan-gradle-plugin` `titanPackage` object
  inventory. `titanTranspile` succeeds (exit 0, 486 SQL files / 483 entry points), but
  `build/generated/sql/titan/postgresql/TitanGraphqlArtifactKind__TitanGraphqlArtifactKind.sql`
  contains the enum accessor functions `__enum_titan_graphql_artifact_kind_manifest_name` and
  `__enum_titan_graphql_artifact_kind_default_file_name` twice (byte-identical
  `CREATE OR REPLACE FUNCTION` blocks). `titanPackage` then fails:
  `object inventory requires unique generated object ids: postgresql.public.__enum_titan_graphql_artifact_kind_manifest_name.function`
- Minimal shape (CORRECTED 2026-06-12 after workaround investigation — the original
  per-referencing-class diagnosis was disproven by experiment): `PostgreSqlEmitter.emitEnumLookup`
  emits one accessor function per enum FIELD and another per no-arg METHOD whose name resolves
  to a backing field (`enumBackingFieldForMethod`); an enum accessor named exactly after its
  backing field (`manifestName()` over field `manifestName`) therefore emits byte-identical
  duplicate functions within the enum's own SQL file. `describeEnumLookup` mirrors the
  duplication into the object inventory, whose uniqueness gate then rejects it. Referencing
  classes are irrelevant (the enum is emitted once per pipeline run).
- What is blocked here: the packaging leg (`titanPackage`) and therefore W2/W3. The
  transpile-clean W1 milestone itself is reached
- Workaround applied: enum accessors renamed `manifestName()` → `getManifestName()`,
  `defaultFileName()` → `getDefaultFileName()` (distinct ids for the method-derived functions;
  fields/columns unchanged), with all call sites routed through `// TG-BLK-004`-marked static
  helpers on `TitanGraphqlArtifactManifestEntry`. Verified: each `__enum_..._*` function now
  appears exactly once across the generated SQL; 450/450 tests green
- What titan must deliver to close: deduplicate field-derived vs method-derived accessor
  emission in `emitEnumLookup`/`describeEnumLookup` (skip the method loop when the id matches a
  field-derived accessor); regression test: an enum accessor named after its backing field must
  package cleanly
- Severity for this product: was blocking; degraded to cosmetic by the workaround (the get*
  names are unidiomatic for records of this style)
- Status: closed (titan e13b7b6, B-4 — enum accessor emission and inventory description dedupe
  by accessor id in both dialects). Workaround reverted 2026-06-13: `TitanGraphqlArtifactKind`
  accessors restored to record-style `manifestName()`/`defaultFileName()`, the routing helpers
  on `TitanGraphqlArtifactManifestEntry` removed, all call sites direct again. Re-proof
  evidence: each `__enum_titan_graphql_artifact_kind__*` accessor appears exactly once per
  dialect bundle, `titanPackage`'s object-inventory uniqueness gate (the gate that originally
  caught this) passes for both dialects, and the `titanVerifyInstall` report shows both
  accessor functions passing exists/signatureMatches/installedDefinitionObserved on the live
  scratch PostgreSQL (the verification run as a whole fails, but only on TG-BLK-011 objects)

### TG-BLK-005: record SQL type names derive from simple names only — nested records collide
- Date / Found during: 2026-06-12 / TG-BLK-004 workaround verification (the enum duplicate had
  failed packaging fast, masking this second collision behind it)
- Titan area: `titan-transpiler` `PostgreSqlEmitter.emitRecordModel` /
  `TranspilationPipeline` — record/enum SQL object names are `__record_`/`__enum_` +
  snake(simpleName) with no enclosing-type qualification. Workaround analysis showed THREE
  distinct failure shapes behind that one derivation:
  1. Hard gate trip (the observed `titanPackage` failure): a prefix-join ambiguity, not the
     five-way SortPath clash itself — the record TYPE `__record_sort_path` (from any `record
     SortPath`) collides with the component-accessor FUNCTION `__record_sort_path` generated
     for component `path` of `model/TitanGraphqlFieldDocument.Sort` (`__record_` +
     snake("Sort") + "_" + "path"). `TitanInstallPlan.requireNoGeneratedNameCollisions`
     fails fast on the first duplicate identity:
     `install plan detected generated object name collision for postgresql: postgresql.public.__record_sort_path`
  2. Silent last-wins collapse: same-kind declarations sharing a simple name (the five
     `record SortPath`s, plus 21 more colliding names — `Argument` x4, `FilterPath` x3,
     `Status` x9, `Kind` x5, `NullOrdering` x6, `SortDirection` x5, ...) are keyed by simple
     name in the pipeline, so only the LAST-discovered declaration's SQL type and accessors
     are emitted; routines lowered from the losing declarations call accessor functions that
     are never created. No diagnostic anywhere.
  3. Cross-kind artifact-file overwrite: `enum Operation` and `record Operation` both emit
     `Operation__Operation.sql` and the same metadata key, so one kind's DDL and inventory
     rows are silently dropped from the package.
- What is blocked here: packaging (and therefore W2/W3), same leg as TG-BLK-004
- Workaround applied: renamed ALL colliding transpiled record/enum declarations to unique,
  enclosing-class-derived simple names — 24 colliding simple names, 74 declarations across
  21 files (e.g. `ProjectionRetrieval.SortPath` → `RetrievalSortPath`,
  `GraphqlRootField.SortPath` → `RootFieldSortPath`, `ProjectionRelation.SortPath` →
  `ProjectionRelationSortPath`, `TitanGraphqlRelationDocument.SortPath` →
  `RelationDocumentSortPath`, `TitanGraphqlRootDocument.SortPath` → `RootDocumentSortPath`);
  each declaration marked `// TG-BLK-005`. Verified: 450/450 tests green, regenerated
  artifact set has zero duplicate object identities and zero duplicate artifact-file rows,
  `titanPackage` completes (1678 objects: 80 tables / 1421 functions / 177 types — counts at
  the verification run; after W3 added artifact-evidence record types the package is 1700
  objects: 80 tables / 1440 functions / 180 types, still zero duplicate identities), and
  `titanVerifyInstall` passes against a scratch postgres:16 container (1421 routines probed)
- What titan must deliver to close: qualify generated record/enum object names by enclosing
  class (e.g. `__record_projection_retrieval_sort_path`) AND use a join that cannot alias
  type names with accessor names (shape 1), AND replace the silent last-wins keying with a
  transpile-time positioned diagnostic naming both declarations (shapes 2 and 3); regression
  tests: (a) two classes each declaring a nested record of the same simple name, (b) a
  record `Foo` with component `bar` next to a record `FooBar`, (c) an enum and a record
  sharing a simple name — all three must package cleanly or fail with a positioned error
- Severity for this product: was blocking; cleared by the rename workaround (cosmetic cost:
  verbose nested-type names)
- Status: closed (titan 705180d, B-2 — nested record/enum SQL names qualify by enclosing type,
  the emission map keys by qualified name with a positioned diagnostic on residual collisions,
  and artifact-file naming distinguishes kind/qualified name; all three shapes covered by core
  regression tests). The renamed declarations are deliberately KEPT (not reverted): the unique,
  enclosing-class-derived simple names read better in generated SQL than the original
  five-way `SortPath`/`Status`/`Kind` duplicates; the `// TG-BLK-005` code markers were
  rewritten to historical "renamed under TG-BLK-005 (closed)" notes

### TG-BLK-006: control-character char literals are emitted as raw bytes into SQL string literals; the indentation pass corrupts newline literals
- Date / Found during: 2026-06-12 / W2 SQL-mode equivalence IT (`GraphqlSqlModeEquivalenceIT`)
  — the first time the transpiled kernel ever *executed* on a live database
  (`titanVerifyInstall` probes signatures, it does not run the routines)
- Titan area: `titan-transpiler` `PostgreSqlEmitter` literal rendering + statement
  indentation. A Java char literal `'\n'` is rendered as a raw LF byte inside the emitted
  single-quoted SQL literal; the emitter's line indentation then treats that LF as a line
  break and appends the statement indent INSIDE the literal. Observed artifact (hex-verified
  in `R__titan_020_routines.sql`): `p_c = '<LF><4 spaces>'` — a 5-character literal that a
  single character can never equal. `'\r'`/`'\t'` survive only by luck (no LF to indent) and
  are still emitted as fragile raw control bytes
- Minimal shape: transpile `static boolean f(char c) { return c == '\n'; }` for PostgreSQL —
  the generated comparison is against a literal containing LF plus the surrounding statement
  indentation
- What is blocked here: SQL-mode equivalence for every multi-line GraphQL document. Before
  the workaround, 44 of 97 conformance-corpus cases diverged (every corpus case whose query
  contains a newline): `is_whitespace(LF)` returned false, so SQL mode answered
  `unexpected trailing GraphQL input` / `field '' requires a selection set` /
  `operationName ... was not found` where Java mode parsed the document
- Workaround applied: the kernel's single control-char-literal site
  (`DemoBlogTitanGraphqlFunctions.isWhitespace`) rewritten to char-code comparison
  (`int code = c - 0; code == 32 || code == 10 || code == 13 || code == 9`), which lowers to
  `ASCII(p_c)` — byte-exact on both legs; marked `// TG-BLK-006`. Verified: zero
  control-character bytes inside string literals across the regenerated 31k-line routine
  bundle; all 97 corpus cases equivalent on live PostgreSQL
- What titan must deliver to close: emit escaped dialect-correct literals for non-printable
  characters (PostgreSQL `E'\n'` or `CHR(10)`), and make the indentation pass
  literal-aware so it can never rewrite bytes inside a quoted literal; regression test: a
  routine comparing a char parameter against `'\n'`, `'\r'`, `'\t'` must round-trip through
  emit + deploy + execute with Java-equivalent results
- Severity for this product: was blocking (silently — install verification passed while the
  deployed engine was wrong); cleared by the kernel workaround
- Status: closed (titan e13b7b6, B-3 — control characters in literals emit via dialect escapes
  (PG `E'..'`, MySQL named backslash escapes + `CHAR(n)` composition), the indenter is
  literal-aware, golden sanity rule forbids raw control bytes inside literals, and core's
  deployability IT proves `'\n'`/`'\t'`/`'\r'` comparisons execute result-equal on both live
  databases). Workaround reverted 2026-06-13: `isWhitespace` restored to the natural
  `c == '\n'` comparison form. Re-proof evidence: the regenerated bundles emit
  `p_c = E'\n'` (PostgreSQL) and `p_c = CHAR(10 USING utf8mb4)` (MySQL) — no raw control
  bytes, no indent corruption. The 97-case live-corpus re-proof (initially blocked by
  TG-BLK-011) completed 2026-06-13 once TG-BLK-011 closed: 97/97 equivalent on live
  PostgreSQL with the natural `c == '\n'` form

### TG-BLK-007: unsupported String methods are silently emitted as bare SQL calls instead of being rejected
- Date / Found during: 2026-06-12 / W2 SQL-mode equivalence IT (`GraphqlSqlModeEquivalenceIT`),
  second divergence class after TG-BLK-006 was worked around
- Titan area: `titan-transpiler` `ExpressionLowerer.lowerKnownInstanceMethod` — instance
  methods missing from the known-lowering table (here `String.equalsIgnoreCase`) fall through
  to a bare `FunctionCallExpression` carrying the Java method name. The emitted SQL calls
  `equalsIgnoreCase('admin', p_actor_role)`, which does not exist in PostgreSQL. The always-on
  validator raises no diagnostic, and `titanVerifyInstall` passes (it probes routine
  signatures, not execution), so the defect only surfaces when the deployed routine runs:
  `ERROR: function equalsignorecase(unknown, text) does not exist`
- Minimal shape: transpile `static boolean f(String s) { return "x".equalsIgnoreCase(s); }`
  for PostgreSQL — emits a call to a nonexistent function with no transpile-time diagnostic
- What is blocked here: SQL-mode execution of every entry-point path that reaches
  `isAdmin(...)` (2 of 97 conformance-corpus cases diverged: admin email authorization)
- Workaround applied: `DemoBlogTitanGraphqlFunctions.isAdmin` rewritten to
  `actorRole != null && "admin".equals(actorRole.toLowerCase())` — `toLowerCase` is in the
  lowering table (`LOWER(...)`); ASCII-identical behavior; marked `// TG-BLK-007`. Verified:
  zero camelCase bare calls remain in the regenerated bundle; all 97 corpus cases equivalent
  on live PostgreSQL
- What titan must deliver to close: either lower `equalsIgnoreCase` (e.g.
  `LOWER(a) = LOWER(b)` with Java-semantics caveats documented) or — more importantly —
  reject ANY instance method without a lowering as a positioned TITAN-E001-class diagnostic
  instead of emitting a call to a function that cannot exist; regression test: an
  unsupported String method must fail transpilation, not deployment/execution
- Severity for this product: was blocking (silent wrong-SQL emission); cleared by the kernel
  workaround
- Status: closed (titan e13b7b6, B-1 — unknown methods on supported receivers are now rejected
  with a positioned TITAN-E001 naming the method and the receiver's supported alternatives;
  all six `FunctionCallExpression` fall-through classes closed or justified). The core gap was
  the SILENT emission, and that is fixed; `equalsIgnoreCase` itself remains outside the
  lowering table, so `isAdmin` deliberately keeps the supported `toLowerCase` -> `LOWER(...)`
  form (no longer a workaround — a capability-respecting choice; the `// TG-BLK-007` marker
  is rewritten to a closed-status note)

### TG-BLK-008: rollback scripts are not referenced (path or hash) from any package metadata file
- Date / Found during: 2026-06-12 / W3 real-artifact pipeline (surfacing `titan-rollback.<dialect>.sql` in the management plane)
- Titan area: `titan-gradle-plugin` `TitanRollbackScript` / `titanPackage` metadata emission — the
  package writes `titan-rollback.<dialect>.sql` next to the four metadata JSONs, but neither
  `titan-artifact.json` (no manifest entry, no hash), `titan-install-plan.json` (steps carry only
  free-text `rollbackHint`s), the object inventory, nor the install verification references the
  script. Verified against the real package output: zero mentions of `titan-rollback` across all
  four JSONs
- What is blocked here: a consumer cannot discover the rollback surface from the manifest contract,
  and cannot integrity-link the script to the package the way every other artifact is hash-linked
  (`manifestContentSha256`/`inventoryContentSha256`/`planContentSha256` chains)
- Workaround applied: `TitanGraphqlGap005ArtifactMetadata` discovers the script by the filename
  convention (`titan-rollback.<dialect>.sql` per manifest dialect) and computes its own
  existence/statement-count/sha256 summary; marked `// TG-BLK-008`
- What titan must deliver to close: a `rollbackScripts` (or equivalent) section in
  `titan-artifact.json` with per-dialect path and content sha256, hash-linked like the other
  package files; regression test: a packaged artifact set must declare its rollback script and
  fail verification if the file is missing or its hash drifts
- Severity for this product: cosmetic (the convention is stable today; the integrity gap is the
  real issue)
- Status: open (workaround applied in titan-graphql)

### TG-BLK-009: `titan-runtime-jdbc` is not consumable as a production application dependency
- Date / Found during: 2026-06-13 / W5.1 live SQL execution mode (wiring `TitanExecutionListener` +
  `JdbcTelemetrySink` onto the Quarkus application classpath)
- Titan area: `titan-runtime-jdbc/build.gradle.kts` packaging. Two defects for consumers that put
  the artifact on a production classpath: (1) the embedded test harness's dependencies —
  `junit-jupiter-api`, all Testcontainers modules, both database drivers — are declared at
  `implementation` scope, so they ride transitively into any consuming application; (2) the module
  declares an internal `implementation(project(":titan-dsl"))`, and when a composite consumer also
  applies the Quarkus Gradle plugin, the plugin's configuration-time dependency walk
  (`QuarkusPlugin.afterEvaluate → visitProjectDependencies`) recurses into the substituted project,
  meets that `ProjectDependency`, and calls `ProjectDependency.getPath()` — an API added in Gradle
  8.11, while core's wrapper (the only supported build entry point) is Gradle 8.10.2 →
  `NoSuchMethodError` at configuration time. `titan-dsl`/`titan-management` dodge this only because
  they declare no project dependencies
- What is blocked here: declaring `implementation("io.titan:titan-runtime-jdbc")` normally; W5.1
  needs core's listener/sink classes inside the Quarkus application
- Workaround applied: `build.gradle.kts` declares the dependency inside `afterEvaluate` (runs after
  the Quarkus plugin's walk; the dependency remains fully ordinary for compilation, the Quarkus
  application model, dev mode, and tests) with `exclude` rules for the JUnit/Testcontainers/MySQL
  groups; marked `// TG-BLK-009`
- What titan must deliver to close: split `io.titan.runtime.testing` (harness) into its own
  artifact or move its dependencies off `implementation` scope, so the runtime artifact's
  transitive closure is JDBC-only; and/or upgrade core's Gradle wrapper to ≥ 8.11 so plugin
  ecosystems compiled against current Gradle APIs work in composite consumers
- Severity for this product: degrading (configuration-time crash without the workaround)
- Status: open (workaround applied in titan-graphql)

### TG-BLK-010: `JdbcExecutor` has no parameterized raw-SQL path, so deployed `@StoredFunction` routines cannot be called through it
- Date / Found during: 2026-06-13 / W5.1 live SQL execution mode (choosing the dispatch for
  `SELECT public.execute_graphql*(?, ...)`)
- Titan area: `titan-runtime-jdbc` `JdbcExecutor` — the typed execute/fetch paths require DSL
  builders (`SelectBuilder` etc.), and the raw-SQL conveniences (`fetchSql`/`executeSql`) accept no
  bind values. Invoking the routines Titan itself transpiles and deploys therefore cannot go
  through the executor without splicing values into SQL text, which would also violate the
  listener contract's "placeholder SQL only" rule
- What is blocked here: serving GraphQL requests from the deployed stored functions via core's
  executor (and getting its observed-execution telemetry wiring for free)
- Workaround applied: plain prepared-statement dispatch (`GraphqlSqlEntryPointDispatch`, extracted
  from the W2 corpus) plus `GraphqlSqlModeRuntime` mirroring `JdbcExecutor`'s observed-execution
  semantics (placeholder-SQL listener events, propagate-on-success / suppress-on-failure) onto
  core's `TitanExecutionListener`/`JdbcTelemetrySink`; marked `// TG-BLK-010`
- What titan must deliver to close: a parameterized raw-SQL execution path on `JdbcExecutor`
  (e.g. `fetchSql(String sql, List<Object> params, RowMapper<T>)` or a dedicated
  routine-invocation API) that binds via `setObject`, flows through the execution listener, and is
  covered by the executor's tests — the natural client-side call path for transpiled routines
- Severity for this product: degrading (forced re-implementation of executor semantics)
- Status: open (workaround applied in titan-graphql)

### TG-BLK-011: qualified record/enum SQL names ignore `DialectCapabilities.NamingRules` — identifiers overflow both dialects' limits, silently corrupting PostgreSQL installs and hard-failing MySQL installs
- Date / Found during: 2026-06-13 / stage-0 revert verification + W5.2 MySQL target (the first
  `titanVerifyInstall`/corpus run against core HEAD with the B-2 qualified-naming fix, titan
  705180d)
- Titan area: `titan-transpiler` `SqlNames` (`recordSqlBaseName`/`recordMemberName`/
  `enumSqlBaseName`/`enumMemberName`). The B-2 fix builds record/enum object names from the
  source-local qualified name (`__record_` + snake(Enclosing.Nested) [+ `__` + member]), but
  never applies the dialect's `DialectCapabilities.NamingRules.identifierMaxLength` (PostgreSQL
  63, MySQL 64) — `truncateIdentifierPreservingSuffix` exists in `TranspilationPipeline` and is
  applied ONLY to `__titan_internal_*` helper routine names (`toSqlRoutineName`), not to
  record/enum types and member accessors. At consumer scale (this kernel: 1698 inventory
  objects), 380 generated object names exceed 63 chars (longest 96:
  `__record_titan_graphql_durable_management_store_product_state_journal_product_state_entry__*`);
  even TOP-LEVEL records overflow (`__record_titan_graphql_gap005_artifact_metadata__<member>`,
  74), so this is not limited to deeply nested declarations
- Observed failures (all evidence from this repo's build at titan e13b7b6+705180d):
  1. PostgreSQL silently truncates every identifier to 63 bytes (NAMEDATALEN-1). 177 of the
     emitted functions collide after truncation into 38 groups (every member accessor of a
     record whose type name is ~61+ chars truncates to the same name). Where colliding members
     share a return type, `CREATE OR REPLACE` silently REPLACES — last body wins, accessors
     return the wrong component. Where return types differ, deployment errors:
     `titanVerifyInstall` postgresql leg = 36 x `TITAN-GAP005-INSTALL-SQL` ("cannot change
     return type of existing function ... Use DROP FUNCTION
     __record_demo_blog_graphql_json_writer_comment_connection_page_(...) first") + 139 x
     `TITAN-GAP005-SIGNATURE-MISMATCH`. The 213 truncated-but-unique functions "pass" the
     probe only because probe-side identifiers truncate identically — the deployed names do
     not match the inventory ids
  2. MySQL rejects long identifiers outright (`ER_TOO_LONG_IDENT`): `titanVerifyInstall` mysql
     leg = 684 x `TITAN-GAP005-INSTALL-SQL` ("Identifier name '...' is too long"), 328 x
     OBJECT-MISSING, 328 x SIGNATURE-MISMATCH; 342 distinct over-64 object names in the mysql
     bundle
  3. Both SQL-mode equivalence legs die at deployment: the PostgreSQL corpus IT fails applying
     `R__titan_020_routines.sql` on the first return-type collision; the new MySQL corpus IT
     (`GraphqlSqlModeEquivalenceMySqlIT`) fails on the first too-long `DROP FUNCTION IF EXISTS`
- Minimal shape: a record whose qualified name + component accessor exceeds the dialect limit,
  e.g. `class DemoBlogGraphqlJsonWriter { record CommentConnectionPage(String comments,
  boolean hasNextPage, boolean hasPreviousPage) {} }` →
  `__record_demo_blog_graphql_json_writer_comment_connection_page__has_next_page` (72 chars).
  PostgreSQL: all four member functions truncate to the same 63-byte name, deploy errors with
  "cannot change return type" (or silently last-wins if types agree); MySQL: every CREATE
  fails `ER_TOO_LONG_IDENT`. No transpile-time diagnostic on either dialect
- What is blocked here: stage-0 exit verification (integrationTest), `titanVerifyInstall` on
  both dialects, the entire 97-case SQL-mode equivalence corpus on both dialects (PostgreSQL
  was 97/97 green before core 705180d), and W5.2's Java-vs-MySQL corpus. Proven independent of
  this repo's stage-0 reverts: stashing the reverts reproduces the identical verification
  failure (139 + 36 diagnostics)
- Workaround applied: none — no cheap boundary-respecting workaround exists. Shortening names
  in this repo would require renaming ~100 declarations including ~40 top-level product
  classes (`TitanGraphqlRootDocument`, `TitanGraphqlGeneratedArtifactWorkflow`, ...) and
  record components, i.e. redesigning the product's public model API around an emitter defect;
  rejected. The MySQL equivalence leg is wired fail-loud and goes green when this closes
- What titan must deliver to close: apply `NamingRules.identifierMaxLength` to record/enum
  type AND member names in both emitters — suffix-preserving hash truncation exactly like
  `__titan_internal_*` helpers get (collision-free by construction), or a positioned
  TITAN-E00x diagnostic when a generated name cannot fit; regression tests: (a) a nested
  record whose member accessor name exceeds 63/64 chars must package, deploy and execute on
  both live dialects with inventory ids matching deployed names, (b) two records whose
  member names share a >63-byte prefix must not collide after shortening
- Severity for this product: was blocking (took the previously-green PostgreSQL equivalence
  proof down with it; blocked all of W5.2's database-side evidence)
- Status: closed (titan 5649ebb — `SqlNames` applies `NamingRules.identifierMaxLength` with
  suffix-preserving hash truncation to record/enum type and member names in both emitters,
  covered by core's length-limit unit/pipeline/deployability tests). No consumer change was
  needed (no workaround had been applied). Recovery verified 2026-06-13 against core HEAD:
  `titanVerifyInstall` passes BOTH dialects — postgresql 1452 objects verified / mysql 1438,
  zero diagnostics on either leg; the PostgreSQL equivalence corpus is back to **97/97
  equivalent, 0 divergences** on live PG; and the MySQL corpus produced its first-ever full
  per-case run — **91/97 equivalent**, with the 6 divergences forming a single new class
  (MySQL boolean-to-JSON rendering), registered separately as TG-BLK-012

### TG-BLK-012: MySQL renders boolean routine values as `1`/`0` in JSON/text output where Java and PostgreSQL render `true`/`false`
- Date / Found during: 2026-06-13 / W5.2 MySQL equivalence corpus — the first full
  Java-vs-MySQL per-case run, unlocked by the TG-BLK-011 fix (titan 5649ebb)
- Titan area: `titan-transpiler` MySQL emission of boolean-typed expressions reaching
  string/JSON rendering contexts. MySQL's BOOLEAN is TINYINT, so a boolean value
  concatenated into the kernel's JSON output renders `1`/`0`; Java and the PostgreSQL
  emission render `true`/`false`. Core tracks the fix as backlog item B-10
  (`vendor/titan/docs/titan-graphql-blocker-backlog.md`)
- Evidence (one divergence class, 6 of 97 corpus cases, all boolean-only): every diverging
  JSON differs from the Java leg ONLY at boolean positions (`pageInfo.hasNextPage`,
  `pageInfo.hasPreviousPage`, `__Directive.isRepeatable`, ...) where MySQL answers `1`/`0`:
  - `QC4-ALIASES / connection-aliases`
  - `QC6-SORT-CURSOR / generated-order-with-relay-cursor`
  - `QC7-TOTAL-COUNT-RELATION / relation-total-count-with-page-info`
  - `QC7-TOTAL-COUNT-RELATION / relation-after-cursor`
  - `QC9-CONTEXT-FILTER / visibility-cursor-window`
  - `QC10-INTROSPECTION-DIRECTIVES / schema-directives`
- Minimal shape: a boolean routine value embedded in JSON output (e.g. a routine returning
  `{"hasNextPage": <boolean expr>}` via the kernel's JSON writer) renders `1`/`0` on MySQL,
  `true`/`false` in Java mode and on PostgreSQL
- What is blocked here: strict 97/97 Java-vs-MySQL equivalence; every conformance-matrix row
  whose corpus case emits a boolean into the response JSON
- Workaround applied: none in the kernel — the divergence is core's to fix (rewriting the
  product's JSON writer around MySQL's TINYINT booleans would paper over an emitter defect).
  Instead the 6 cases are marked KNOWN-DIVERGENT in `GraphqlSqlModeEquivalenceMySqlIT`
  (`// TG-BLK-012` allowlist): each allowlisted case must STILL diverge in exactly the
  boolean-rendering way (java-leg booleans vs MySQL `1`/`0` at the same JSON positions, and
  nothing else); any other divergence — or the case comparing strictly equal once core fixes
  B-10 — fails the leg loudly so the allowlist cannot rot. The other 91 cases compare
  strictly
- What titan must deliver to close: per core backlog B-10 — boolean-typed expressions
  entering string/JSON rendering contexts on MySQL must emit through a `true`/`false`
  conversion (the emitter knows TIR types; the `__titan_*` string/JSON helpers are the likely
  chokepoints); regression test: a boolean routine result embedded in JSON output compares
  equal across Java/PG/MySQL (deployability-IT execution case)
- Severity for this product: was degrading (MySQL leg's strict equivalence was capped at
  91/97; the 6 cases were tracked, not hidden); cleared entirely by core's B-10 fix
- Status: closed (titan f9e3b43, B-10 — MySQL now renders boolean routine values as
  `true`/`false` in text/JSON output, matching Java and PostgreSQL). All 6 formerly-tracked
  cases (`connection-aliases`, `generated-order-with-relay-cursor`,
  `relation-total-count-with-page-info`, `relation-after-cursor`, `visibility-cursor-window`,
  `schema-directives`) now compare STRICTLY equal and have rejoined the strict corpus, so the
  MySQL leg is **97/97 strictly equivalent, 0 divergent, 0 allowlisted**. The
  `KNOWN_DIVERGENT_TG_BLK_012` allowlist in `GraphqlSqlModeEquivalenceMySqlIT` is retired to
  `Set.of()` — the empty guard is deliberately retained so a returning boolean-parity
  regression fails the strict comparison loudly rather than silently re-diverging. No consumer
  workaround had been applied (the kernel was never patched), so nothing needed reverting
