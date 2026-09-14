# Titan Core Gap Ledger

This ledger records Titan core work exposed by the Titan GraphQL demo/core
split. It is intentionally not a GraphQL workaround list. If a bridge exists
only because Titan cannot yet lower or package the generic shape, the desired
end state belongs in `vendor/titan`.

## Gap Format

Each entry records:

- where GraphQL exposed it
- why it belongs in Titan core
- current bridge or workaround
- desired Titan behavior
- minimal validation case

## Closure Status

As of 2026-06-09, the evidence-backed Titan core gap series exposed by the
Titan GraphQL demo/core split is closed in Titan core.

- `TITAN-GQL-GAP-001` maps to `vendor/titan/GAP-001.md`, which is
  `Status: final-gate readiness audited`.
- `TITAN-GQL-GAP-002` maps to `vendor/titan/GAP-002.md`, which is
  `Status: complete`.
- `TITAN-GQL-GAP-003` maps to `vendor/titan/GAP-003.md`, which is
  `Status: complete`.
- `TITAN-GQL-GAP-004` maps to `vendor/titan/GAP-004.md`, which is
  `Status: complete`.
- `TITAN-GQL-GAP-005` maps to `vendor/titan/GAP-005.md`, which is
  `Status: complete`.
- `TITAN-GQL-GAP-006` maps to `vendor/titan/GAP-006.md`, `Status: complete`. Core
  subsequently DOGFOODED the management store (Phase B): it transpiles the
  management mutation routines in-tree and ships a durable JDBC-backed
  transactional store over them (`JdbcTransactionalMutationStore` +
  `JdbcIdempotencyStore` + `JdbcAuditStore` + `ManagementSchemaInstaller`),
  proven on PostgreSQL 16 + MySQL 8.4. The consumer now runs on it in an opt-in
  `jdbc` mode; the file-backed store remains the default. See the durability
  decision in "Status After Core Uptake" below — `TG-BLK-003` is CLOSED.

There is no current `TITAN-GQL-GAP-007`. Remaining known work is consumer
handoff in Titan GraphQL unless implementation exposes a new reusable Titan
core contract gap. New core gaps found after this ledger closed are recorded
in `docs/titan-blocker-register.md` (`TG-BLK-001`..`TG-BLK-008`), the live
successor to this ledger's gap mechanism.

## Status After Core Uptake (2026-06-12)

The completion plan (`docs/completion-plan.md`) drove consumption of the closed
gap series against core `main`. Outcomes, with titan-graphql commits:

- **W0** (`253dd78`): the build consumes the extracted `titan-management`
  module through the composite build (the raw transpiler-jar file dependency
  is gone), `titanJdbc` carries the PostgreSQL driver, and the dead
  `strictMode` knob is removed (`TG-BLK-002`).
- **W1** (`f02fb22`, `19b1baa`, `8d70412`): the demo-blog kernel was rewritten
  to the GAP-004 contract — all 10 validator-rejected scanner sites and all 9
  recursion families — and `titanTranspile` is clean (zero `TITAN-E001`
  diagnostics; 539 generated SQL files). Packaging then exposed two new core
  defects, worked around in `69172a0` (`TG-BLK-004`) and `ec96b25`
  (`TG-BLK-005`); `titanPackage` (currently 1,700 generated objects: 80
  tables / 1,440 functions / 180 types) and `titanVerifyInstall` are green.
- **W2** (`506f86c`): SQL-mode equivalence is automated on the GAP-003
  machinery — `GraphqlSqlModeEquivalenceIT` deploys the packaged migrations
  onto a live PostgreSQL and runs a 97-case conformance corpus through the
  deployed stored functions with zero divergences against the Java engine via
  core's `EquivalenceOracle`; 41 of 42 conformance-matrix rows cite live
  SQL-mode evidence. First live execution surfaced `TG-BLK-006` and
  `TG-BLK-007` (worked around in the kernel).
- **W3** (`c9dda68`): the management plane consumes real
  `titanPackage`/`titanVerifyInstall` artifacts; the `metadataOnly`
  placeholder branch and the kernel-class reflection path are deleted;
  deployment activation is gated on passed install verification; rollback
  scripts are discovered and surfaced (`TG-BLK-008`).
- **GAP-006 durability decision (recorded, then RESOLVED)**: core amended
  GAP-006 (2026-06-11) to make the SQL-backed storage contract
  specification-only, with `FileTransactionalMutationStore` the only,
  file-backed/test-grade implementation. titan-graphql accepted that boundary
  for the proof-project scope, marked `// TG-BLK-003`. **Core then closed the
  gap** (Phase B, commits `35dc04d` / `0498692` / `e238ae7`): it dogfooded the
  store — transpiling the management mutation routines in-tree and shipping a
  durable JDBC-backed transactional store (`JdbcTransactionalMutationStore` +
  JDBC idempotency/audit stores + `ManagementSchemaInstaller`) over those
  Titan-transpiled routines, proven durable + concurrent by
  `JdbcManagementStoreDogfoodIT` on PG 16 + MySQL 8.4.
- **Consumer uptake (dogfood Phase C, 2026-06-14)**: titan-graphql now runs on
  the JDBC store in an opt-in `jdbc` mode (`titan.graphql.management.store=jdbc`):
  `TitanGraphqlDurableManagementStore` is built over `JdbcTransactionalMutationStore`
  from the Quarkus datasource, with the schema+routines bootstrapped at startup
  via `ManagementSchemaInstaller`. `GraphqlJdbcManagementStoreIT` proves the
  management `importModelDocument` path persists THROUGH the JDBC store (durable
  across a new store instance), is idempotent, and keeps deployment-activation
  gating — on live PG 16 + MySQL 8.4. **"Durable" in jdbc mode now means a real
  JDBC store on Titan-transpiled routines**, not the file boundary. `file`
  remains the DEFAULT (Docker-free plain `test` + dev), so durable-JDBC claims
  are scoped to jdbc mode. The two recorded routine-design gaps (RD-1, RD-2) AND
  the E032 residual are now **ALL CLOSED** (core GAP-006 Status Amendment 2,
  commits `132a7bd` / `6e8e17f` — pure routine redesign, no core
  transpiler/DSL change): `activate_deployment` is a value-returning
  `@StoredFunction` that runs all **7** activation preconditions
  (`TITAN-MGMT-E030`..`E036`) SERVER-SIDE and returns a typed status code — the
  E032 GAP-005 metadata-path suffix match was the final residual and is now in
  the routine too, so the JDBC adapter is a **pure pass-through** with its
  ~36-line precondition duplication gone. `import_model_document` now carries
  **three DISTINCT hashes** (input/output/document), each in its own column, so
  the dogfooded store is **byte-faithful to the file store**. Both closures are
  proven end-to-end on live **PostgreSQL 16 + MySQL 8.4**. `TG-BLK-003` is
  **CLOSED**.

The Titan GraphQL handoff work consumed the completed Titan contracts:

- replace GraphQL-owned generated SQL placeholders with GAP-005 artifact
  metadata references
- map admin mutations to GAP-006 management commands
- wire a durable/external management store adapter
- map GraphQL request context to Titan actor/request/idempotency context
- resolve the local Gradle/IntelliJ validation blocker that prevented one
  external targeted handoff test run

## Titan GraphQL Handoff Consumption Status

As of `TG-HANDOFF-M6.1`, Titan GraphQL has consumed the completed Titan core
contracts needed for the current management/artifact handoff:

- GAP-005 artifact metadata is consumed through a GraphQL-owned adapter for
  Titan `titan-artifact.json`, `titan-object-inventory.json`,
  `titan-install-plan.json`, and `titan-install-verification.json` outputs.
- GAP-006 command context is consumed for `/admin/graphql`
  `importModelDocument` without moving HTTP headers, GraphQL field names, or
  admin route names into Titan.
- GAP-006 transactions, idempotency, audit, and draft records are consumed
  through `TitanGraphqlDurableManagementStore` for the `importModelDocument`
  path. Per core's GAP-006 amendment this storage is file-backed and
  test-grade, not production-durable (`TG-BLK-003`); "durable" in the
  management docs means exactly that boundary.
- GAP-006 deployment activation and GAP-005 verification evidence are consumed
  before durable preview/deployment readiness claims are accepted.
- GraphQL wrapper records, observed operation documents, preview URLs, registry
  presentation state, usage presentation, and UI workflow concepts remain in
  `titan-graphql`.

Remaining work is consumer/product expansion, not current Titan core gap debt:

- generating management mutation descriptors from dogfood management artifacts
- additional product mutations with product-neutral Titan command contracts only
  if reusable evidence appears
- broad deployment orchestration, rollback workflow, and runtime health policy
- product UI and portal workflows
- application CRUD writes, nested writes, subscriptions, and SQL-lowered
  application mutation execution

There is still no current `TITAN-GQL-GAP-007`.

## TITAN-GQL-GAP-001: Descriptor-Driven Runtime Lowering

Where GraphQL exposed it:

`GraphqlHttpResource` and `TitanGraphqlFunctions.executeGraphqlWithPlan(...)`
now route through `GraphqlModelRuntime`, `GraphqlRuntimeRequest`,
`GraphqlEngine`, `GraphqlReadPlanner`, and a model adapter. That Java path is
generic enough to execute against descriptors, but the SQL-lowerable public
kernel is still the static demo-blog
`io.titan.graphql.demo.blog.DemoBlogTitanGraphqlFunctions`.

Why it belongs in Titan core:

The generic path uses ordinary Java objects, interfaces, lists, maps,
selection trees, descriptor graphs, and adapter dispatch. Those are the shapes
Titan needs to lower for realistic application runtimes. Keeping a separate
hand-written kernel per model would prove only that static Java can be lowered,
not that Titan can carry the product semantics.

Current bridge or workaround:

The demo-blog adapter owns a scalar, static `@StoredFunction` kernel that
mirrors the supported public behavior for the proof model. The richer Java
path remains the semantic reference for descriptors, validation, planning, and
adapter execution.

Desired Titan behavior:

Titan can lower a descriptor-driven runtime or a generated adapter produced
from descriptors without requiring a manually duplicated model-specific parser,
planner, or response writer.

Minimal validation case:

Generate a second tiny model adapter from a model document, lower its request
entrypoint through Titan, install it in Postgres, and compare Java-mode versus
SQL-mode responses for root lookup, relation selection, aliases, context
policy, and deterministic validation errors.

## TITAN-GQL-GAP-002: Reusable Row Materialization And Result Iteration

Where GraphQL exposed it:

`DemoBlogGraphqlExecutor` materializes demo rows and nested relation reads in
Java mode. `DemoBlogTitanGraphqlFunctions` carries a separate static version of
the supported SQL path. The split made those adapter responsibilities visible,
but the reusable lowerable row-iteration shape is still missing.

Why it belongs in Titan core:

GraphQL is only one consumer of row materialization. Titan needs reusable
patterns for result-set iteration, relation batching, cursor windows, and
projecting typed rows into structured results across generated kernels.

Current bridge or workaround:

The demo-blog Java executor remains the reference behavior, while the SQL
kernel supports the bounded demo smoke path with static helper logic.

Desired Titan behavior:

Titan provides lowerable runtime patterns or generated code conventions for
iterating rows, materializing typed records, applying relation cardinality, and
preserving stable ordering/cursor behavior.

Minimal validation case:

Lower a generated query kernel that reads a parent root and one nested
one-to-many relation, then compare edge order, cursor page info, nullability,
and empty-relation behavior against the Java executor.

## TITAN-GQL-GAP-003: Structured JSON Response Assembly

Where GraphQL exposed it:

The generic `GraphqlJsonWriter` is now only shared error and escaping support,
while `DemoBlogGraphqlJsonWriter` owns demo result materialization. The
lowered demo kernel still assembles GraphQL JSON through static string helper
code.

Why it belongs in Titan core:

Database-resident runtimes need predictable JSON or structured response
assembly that does not force every generated kernel to hand-roll escaping,
object nesting, error envelopes, aliases, and Relay connection shapes.

Current bridge or workaround:

GraphQL keeps Java-mode response writing in adapter code and a separate
static string-building path in the demo SQL kernel. The semantic-JSON
comparison machinery core delivered for this gap is now exploited (W2,
`506f86c`): `GraphqlSqlModeEquivalenceIT` compares the deployed SQL kernel's
responses against the Java engine as canonical JSON through core's
`EquivalenceOracle` on live PostgreSQL.

Desired Titan behavior:

Titan exposes lowerable JSON/object assembly primitives, or a blessed generated
pattern, that produces deterministic GraphQL-shaped data and error envelopes
without duplicating low-level string builders per model.

Minimal validation case:

Lower and execute a query that returns aliased scalar fields, a nested
connection, `pageInfo`, and a validation error, then compare byte-stable JSON
or semantically equivalent parsed JSON with the Java-mode response. Realized
by the W2 equivalence leg: the 97-case conformance corpus (aliases, nested
connections, `pageInfo`, validation errors, and the rest of the accepted
contract) runs with zero divergences.

## TITAN-GQL-GAP-004: Recursive Helper And Nested Structure Diagnostics

Where GraphQL exposed it:

`titanTranspile` originally reported recursive-helper warnings for the
`DemoBlogTitanGraphqlFunctions` kernel. Core's delivered GAP-004 contract took
the fail branch: the always-on validator now rejects those scanner and
recursion shapes with positioned `TITAN-E001` diagnostics instead of warning —
10 scanner sites and 9 recursion families in the kernel were rejected when
this repo first built against the new core.

Why it belongs in Titan core:

Nested request languages naturally need balanced-delimiter scanning, nested
filter validation, nested selection traversal, and recursive or stack-like
helper shapes. Titan should either lower the accepted shape or report a
precise, actionable unsupported-subset diagnostic.

Current bridge or workaround:

None — no warnings are tolerated, because none exist anymore. The contract is
enforced, and the kernel was rewritten to it in completion-plan W1
(`f02fb22` scanner sites, `19b1baa` JSON-literal/fragment recursion,
`8d70412` type-reference/filter recursion): bounded iterative traversals per
core's documented state rules, with the 450-test Java-mode suite green after
every family to pin behavior. `titanTranspile` is clean for PostgreSQL (zero
`TITAN-E001` diagnostics; 539 generated SQL files). The rejection-then-rewrite
was the delivered contract working as designed, not a gap.

Desired Titan behavior:

Recursive or nested traversal helpers used by bounded parsers either lower
cleanly or fail with diagnostics that identify the method, call path, and
preferred lowerable rewrite. Delivered: the positioned `TITAN-E001`
diagnostics (exact method, position, and cycle path) were precise enough to
drive the whole W1 rewrite family-by-family.

Minimal validation case:

Add a Titan transpiler fixture with nested GraphQL-style delimiter matching and
filter-object traversal. The fixture should either emit no recursive-helper
warnings or assert the exact diagnostic and documented rewrite path. Core
ships `GAP004BoundedDelimiterScannerSqlModeIT` as the canonical accepted
shape; the rewritten kernel follows it.

## TITAN-GQL-GAP-005: Generated SQL Artifact Installation Metadata

Where GraphQL exposed it:

`TitanGraphqlGeneratedArtifactWorkflow` originally discovered stored-function
entrypoints by reflecting over the demo-owned kernel class, and the generated
SQL artifact was a `metadataOnly` placeholder because core had no packaging
contract to consume.

Why it belongs in Titan core:

Applications need a Titan-owned path from annotated Java entrypoints to SQL
bundle, manifest, hashes, install plan, and deployment metadata. GraphQL should
consume that capability, not define its own durable packaging contract.

Current bridge or workaround:

None needed — consumption is no longer fixture-only (W3, `c9dda68`).
`TitanGraphqlGap005ArtifactMetadata.read()` consumes the real `titanPackage`
output (`titan-artifact.json`, `titan-object-inventory.json`,
`titan-install-plan.json`, `titan-install-verification.json`) resolved through
`TitanGraphqlArtifactsDirectory`; the `metadataOnly` placeholder branch and
the kernel-class reflection path are deleted, and entry-point metadata comes
from the manifest/inventory. Rollback scripts are discovered by filename
convention because core's metadata does not reference them — that residual gap
is `TG-BLK-008` in the blocker register.

Desired Titan behavior:

Titan can produce installable SQL artifacts with entrypoint metadata, semantic
hashes, dialect/version information, and a deployment or verification contract
that GraphQL management can reference. Delivered and consumed.

Minimal validation case:

Run artifact generation for the demo-blog model, assert the artifact contains
the concrete SQL bundle plus entrypoint metadata, install it into a scratch
Postgres database, and verify the manifest hash against the installed
functions. Realized by `titanPackage` + `titanVerifyInstall` and
`TitanGraphqlRealArtifactPipelineIT` (real package → adapter → passed
verification → activation).

## TITAN-GQL-GAP-006: Management Runtime Storage And Mutation Contract

Where GraphQL exposed it:

`/admin/graphql` routes through the sibling `TitanGraphqlManagementRuntime`.
Before core delivered GAP-006, management reads returned an
unsupported-storage error and management mutations were Java-mode/in-memory
only. The handoff wired `TitanGraphqlDurableManagementStore` over core's
GAP-006 transaction/idempotency/audit contracts.

Why it belongs in Titan core:

Control-plane behavior needs a production-safe execution contract. That
contract can be lowered SQL or an explicit service boundary, but it must own
transaction, idempotency, durable audit, authorization, and artifact
consistency instead of depending on an in-memory Java-only path.

Current bridge or workaround:

`TitanGraphqlDurableManagementStore` consumes core's GAP-006 transaction,
idempotency, audit, draft, artifact-ref, and deployment-activation contracts
over `FileTransactionalMutationStore`. Per core's GAP-006 amendment
(2026-06-11) that storage is **file-backed, single-process, test-grade** —
the SQL-backed storage contract is specification-only. Decision recorded
(completion plan W4): acceptable for the proof-project scope; the
production-grade store remains core's gap, tracked as `TG-BLK-003` and marked
`// TG-BLK-003` in the store class. Management-plane claims in this repo are
requalified accordingly.

Desired Titan behavior:

Titan defines and validates the management execution boundary for reads and
state-changing mutations, including transaction/idempotency behavior and
durable audit records. The contract and file-backed reference implementation
are delivered; the production-grade (JDBC/row-locking) store is the open
remainder (`TG-BLK-003`).

Minimal validation case:

Execute a management mutation twice with the same idempotency key through the
chosen production boundary, assert one durable state transition, two audit
attempt records with stable outcomes, and the same GraphQL-shaped response
contract as Java mode.

## Non-Gaps Captured During The Split

The demo/core package move required several Java APIs to become public so
adapters can live outside the core package: runtime/model contracts,
schema/projection descriptors, selection and read-plan values, cursor helpers,
planner/engine entrypoints, and shared JSON helpers. That is adapter surface,
not a Titan lowerer/runtime gap by itself. It becomes Titan work only if a
generated or lowered adapter cannot use the same surface without another
model-specific bridge.
