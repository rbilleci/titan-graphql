# Database-resident GraphQL engine: target architecture and execution plan

Status: draft implementation contract; implementation has not started under this plan.

Baseline: `b1722b73553ab1b5c6a20e63692c8b350f964d34`. The previous completion claim did not satisfy
the intended architecture. Passing the existing release gate is baseline evidence only.

This is an executable work plan: execute phases in order, run their gates, record evidence here,
and leave unchecked work visibly incomplete. This document does not itself activate an agent goal
or implement the engine. Proposed task and directory names below do not exist yet unless stated.

## 1. Goal and non-negotiable completion contract

Deliver a schema-driven GraphQL engine whose entire query processing implementation is written in
Titan-supported source and transpiled into database routines. A small HTTP frontend passes the
complete GraphQL document, operation name, variables, extensions, and trusted request context to
the installed engine and returns its completed GraphQL response.

- [ ] Parsing, operation selection, variable coercion, fragment/directive handling, validation,
      policy evaluation, planning, execution, batching, cursor handling, introspection, error paths,
      null propagation, and GraphQL JSON assembly all execute in transpiled database routines.
- [ ] The same maintained engine source serves unrelated reviewed schemas through generated
      metadata and database access code. Adding a supported schema requires no handwritten read
      resolver, query dispatch, or edit to the engine.
- [ ] Custom mutations are explicit, registered at build time, and executed through transpiled
      dispatch and database implementations. No JVM handler callback completes a GraphQL request.
- [ ] PostgreSQL and MySQL both pass direct whole-request and thin-HTTP integration tests.
- [ ] The serving distribution cannot execute a JVM GraphQL engine or select a legacy fallback.
- [ ] Obsolete runtime implementations, public modes, handlers, tests, tasks, and misleading docs
      are removed after their useful coverage has been migrated.

“Full query processing” specifies where all supported GraphQL semantics execute. It does not silently
promise subscriptions, federation, arbitrary database types, or every optional GraphQL feature.
Existing tested read capabilities must migrate; any proposed feature removal must be identified and
agreed explicitly. An unsupported request must fail inside the database engine. Compiler limitations
must never cause query processing to move back into the HTTP process.

Completion requires every phase gate below, a clean worktree, reviewed commits, and a synchronized
`rbilleci/titan-graphql` remote. A passing carrier test or renamed execution mode is insufficient.

## 2. Target architecture

```text
BUILD / DEPLOY
Database metadata + reviewed model + custom mutation source
  -> model validator and generator
  -> shared transpilable engine + generated schema bindings/access routines
  -> Titan transpilation
  -> versioned PostgreSQL/MySQL package + manifest + binding + install verification

REQUEST
Client -> thin HTTP frontend -> one public database execution call
                                |
                                +-> verify installed identity and request limits
                                +-> parse/select/coerce/validate the complete operation
                                +-> evaluate trusted policies and build execution plan
                                +-> execute reads or serial custom mutations
                                +-> assemble complete data/errors/extensions JSON
       <- return completed JSON <- response + transport/transaction outcome
```

The build tool may use ordinary Java, Jackson, collections, codegen, and schema introspection.
Those build-time conveniences must not enter the database engine's runtime dependency closure.

### Frontend responsibility

The frontend owns HTTP routing, media negotiation, body-size limits, envelope decoding, authentication,
trusted context construction, connection pooling, invocation deadlines/cancellation, transaction
lifecycle, package selection, and availability-error mapping. It may reject malformed transport JSON.

It must forward the GraphQL document unchanged and preserve variable values, nulls, numeric precision,
and operation name. It must not select operations, split a document, coerce GraphQL inputs, evaluate
field policies, plan reads, call per-field routines, decode cursors, or assemble GraphQL data/errors.
Authentication verifies identity; authorization against schema policies belongs in the engine.

GET supplies a trusted `allowMutations=false` transport flag. The database parses the operation and
rejects a selected mutation before effects. This removes the current frontend parser dependency.
Any HTTP status derived from GraphQL semantics comes from the database's transport outcome.

### Public database execution contract

Define one logical entry point, initially named `executeGraphqlRequest`, with these inputs:

- Entire query document, optional operation name, variables JSON, and extensions JSON.
- Versioned trusted context: actor identity/roles, tenant, named context values, introspection
  permission, request identity, and execution budget. No blog-specific context parameters.
- Expected package/model identity and transport flags such as `allowMutations`.

Return completed GraphQL JSON plus a small internal outcome: HTTP classification and whether the
surrounding transaction may commit. The frontend strips internal fields and returns the JSON without
semantic rewriting. A routine may return normal GraphQL errors and still require rollback; the adapter
must never decide commit from SQL success alone.

Prefer a procedure-capable contract on both dialects so custom writes do not depend on stored-function
restrictions. Exact SQL OUT/result signatures must be fixed by Phase 1 live proof; the logical contract
is stable across dialect adapters. Internal helpers may be functions or procedures. They must consume
their own rows and must not leak intermediate MySQL result sets to the HTTP driver.

Check installed identity within the execution call before data access; pool initialization checks alone
cannot detect replacement packages. Never accept a client-supplied routine/schema name. Resolve the
public entry point from verified deployment metadata.

### Engine implementation and schema specialization

Maintain one reusable engine implementation in the proven Titan subset: static helpers, supported
scalars and records/collections where demonstrated, and bounded iterative parsing/traversal state.
Use an explicit token/AST/plan representation; do not rewrite GraphQL source by substring substitution.
Phase 1 determines the concrete representation from live lowering evidence, including nested state,
nulls, errors, and Unicode. Avoid relying on recursive database calls or arbitrary Java object graphs.

The generator specializes reviewed schema metadata into stable identifiers, type/input descriptors,
field and policy lookup, physical bindings, static query templates, and custom mutation dispatch.
Generated branches on model identifiers are acceptable; maintained branches naming blog/commerce
entities are not. Schema specialization happens at build time; arbitrary valid documents over the
installed schema are processed at request time. Persisted queries are optional and cannot become a
requirement for execution.

Prefer static, parameterized database access generated from the model. Start with supported JDBC
lowering for constant SQL and consumed cursor loops. Use Titan DSL when its structured operations or
typed catalog improve portability or correctness. Do not introduce DSL objects merely for compliance.
Dialect-specific generated access source is acceptable when sourced from the same model and covered
by shared tests. Hand-editing generated SQL is not an implementation strategy.

Existing result-set carriers cannot be assumed composable inside the engine: PostgreSQL returns JSONB
while MySQL exposes result sets to a client. Replace or adapt these as database-internal access helpers
whose output the transpiled executor consumes. Prove this before porting the complete engine.

### Transactions, policy, and state

Use one connection and explicit transaction per selected operation. Initially use a documented,
tested repeatable-read snapshot for reads so page rows, relations, and counts agree. This adds database
snapshot cost but avoids frontend query classification. Revisit only with measured evidence and an
explicit consistency contract. Custom write operations execute serially in that transaction.

For this release, a mutation operation is atomic: validate the whole operation first, execute its root
fields in order, and roll back all its effects on failure. This atomicity is an explicit project contract
beyond GraphQL's serial mutation requirement. Never emit a successful partial write payload when the
transaction rolled back. Commit/rollback through JDBC is transport lifecycle, not GraphQL execution.
Use transactional MySQL tables. Do not automatically retry mutations after an ambiguous connection loss.

Custom mutation source must be Titan-transpilable or bind to an explicitly reviewed installed database
routine. Policy evaluation, input validation, dispatch, and output/error shaping remain transpiled.
Replace CDI handler registration with a build-time registry that contributes to the package identity.
External effects must use a transactional outbox or a separate application workflow; synchronous JVM
callbacks are incompatible. Durable idempotency and audit records must follow the domain transaction.

Evaluate root/row/field/relation policies inside the engine from authenticated claims. Do not accept
frontend `allowField`/`allowRows` decisions as authority. Apply policies equally to aliases, filters,
ordering, relation joins, counts, pagination, computed fields, and introspection according to its policy.

Request parser/planner state is ephemeral and invocation-local. No permanent AST tables, shared mutable
session state, or request leakage through pooled connections. Installed schema metadata is immutable per
package. Domain data and correctness-critical mutation state are durable. Cache only after establishing
correctness and measurable benefit; any cache key includes relevant schema and authorization context.

### Splitting and other optimizations

| Decision | Benefit/cost | Chosen behavior |
| --- | --- | --- |
| Split named operations in one document | Would change operation-selection semantics | Execute only the selected operation; other named operations are alternatives, not a batch. |
| Split root fields into HTTP/database calls | May parallelize independent reads, but adds parsing, round trips, snapshot differences, duplicate work, and error merging | Keep one selected operation in one database call. Plan shared/batched work inside the engine. |
| Client sends independent requests | Allows explicit concurrency and independent transactions | Supported naturally with bounded connection concurrency; callers accept independent snapshots. |
| HTTP array batching | Saves HTTP overhead but adds extension semantics and transaction rules | Do not add it as part of this correction. If introduced later, each envelope still enters the full transpiled engine. |
| Batch relation reads | Reduces database N+1 work as well as network calls | Required for collections; verify database query counts as parent cardinality grows. |
| Per-parent relation limits | Avoids loading all children before slicing | Push bounds into generated database plans where possible; otherwise enforce a measured work budget and reject excess work. |
| Static specialization | Predictable SQL and safer identifiers; more generated routines | Use it, with routine-count/parameter/code-size budgets and generation benchmarks. |

No request-splitting optimization is needed for initial completion. A future optimization must preserve
the full transpilation boundary and demonstrate improved latency/work on representative workloads.

## 3. Evidence and constraints at the baseline

- [Production transpile inputs](../build.gradle.kts) currently contain only generated read carriers.
- [TitanCompiledGraphqlRuntime](../src/main/java/io/titan/graphql/TitanCompiledGraphqlRuntime.java)
  calls the JVM engine; the compiled data model performs planning, orchestration, and JSON assembly.
- [Legacy whole-request kernel](../src/main/java/io/titan/graphql/demo/blog/DemoBlogTitanGraphqlFunctions.java)
  proves some parsing/rendering shapes transpile, but contains schema-specific behavior. It is a source
  of tests and proven primitives, not the target engine or a template to rename for each schema.
- [Titan's supported Java guidance](../vendor/titan/docs/developer-guide.md) explicitly limits arbitrary
  object graphs/framework code. [JDBC lowering guidance](../vendor/titan/docs/transpilable-jdbc-subset.md)
  covers constant SQL and cursor loops and warns about MySQL dynamic SQL in functions.

These constraints require an early compiler feasibility gate. If required lowering is missing, produce
a minimal source reproducer and dialect evidence, then deliver a focused Titan fix through its normal
review and pinned dependency update. Record cross-repository work explicitly. Do not relax SQL safety,
hand-maintain backend SQL, or retain JVM processing to bypass a compiler gap.

## 4. Execution process

Each phase uses: failing acceptance test -> implementation -> generated SQL review -> affected tests
on both databases -> evidence entry -> removal of superseded code when its replacement is proven.
Commit coherent increments; do not present a phase as overall completion.

Before implementation, fetch the remote, inspect incoming changes and the worktree, initialize pinned
submodules, and capture baseline tests. Preserve unrelated work and do not rewrite published history.

### Phase 0 — establish boundaries and migration inventory

- [ ] Record the baseline commit, Titan pins, and current feature/test inventory.
- [ ] Map every production entry point, including `/graphql`, preview, and admin GraphQL routes.
- [ ] Create separate dependency boundaries for build/model tooling, transpilable engine sources,
      generated schema code, thin HTTP serving, and tests. Prefer separate Gradle modules so the
      frontend can be built and deployed without engine Java classes or build-time dependencies.
- [ ] Add a boundary test that initially fails: the serving artifact must not load parser, validator,
      planner, resolver, cursor, introspection, or GraphQL response-rendering implementations.
- [ ] Capture all existing accepted cases as portable expected-result tests. Mark legacy quirks
      separately; a demo implementation must not become the authoritative oracle for correctness.

Gate: inventory covers all runtime paths; an intentionally failing architecture test exposes the
present violation; the new module/dependency layout is defined and reviewable.

### Phase 1 — prove a complete request inside the database

- [ ] Prove token/AST/plan storage, helper composition, JSON input/output, Unicode, nulls, and bounded
      iterative traversal in a small Titan-transpiled prototype on PostgreSQL and MySQL.
- [ ] Generate bindings for a minimal model from each existing unrelated schema. Pass a whole GraphQL
      document and JSON variables to the database; parse, validate, authorize, read live rows, and
      return complete aliased GraphQL JSON from the installed routine.
- [ ] Include multiple query root fields, a nested relation, a malformed document, and denied access.
- [ ] Prove database-internal row consumption without per-field JDBC calls or intermediate result sets.
- [ ] Prove one custom mutation, serial execution, a failure after an earlier write, full rollback,
      outcome delivery, and caller-owned commit on both dialects.
- [ ] Freeze and document the public SQL signatures, context version, transaction/outcome contract,
      and concrete internal state representation from these results.
- [ ] Resolve or explicitly track every compiler blocker before proceeding to broad feature migration.

Gate: a direct SQL client sends whole documents against both models on both dialects and receives
correct completed responses. This must work without running any JVM GraphQL parser or executor.

### Phase 2 — complete the shared language engine

- [ ] Implement reusable lexing/parsing, source locations, selected operations, fragments and cycle
      checks, directives, field merging, aliases, and full-operation validation before execution.
- [ ] Generate scalar/input/type descriptors and implement schema-aware variables/defaults with
      correct missing/null distinctions, numeric range checks, and unknown/unused input validation.
- [ ] Implement bounded plans, error codes/paths/locations, JSON escaping, and non-null propagation.
- [ ] Generate SDL and execute introspection from the same metadata, including mutation input/output
      types and trusted introspection policy.
- [ ] Add adversarial depth/token/fragment/variable/response-size tests; enforce both semantic work
      budgets and database statement timeouts. Bound loops before allocation or execution expands.

Gate: language corpus executes directly through transpiled routines on both dialects; independent
expected responses catch errors even when a JVM reference and SQL implementation would agree.

### Phase 3 — migrate generic reads and authorization

- [ ] Generate static lookup/execution code for integer, string, UUID, and composite point keys.
- [ ] Migrate nullable scalar/temporal/numeric output and reviewed computed expressions.
- [ ] Migrate collection limits, stable forward/backward cursors, ordering, filters, and exact counts.
- [ ] Migrate modeled relation-hop filters/order and all currently accepted composed-filter cases.
- [ ] Execute point/collection relations and relation connections entirely within the database.
- [ ] Batch nested collections without per-parent queries; bound scanned/materialized child rows.
- [ ] Compile policy evaluation into the engine and enforce it through the full plan, including
      derived values, filtering, ordering, counts, aliases, batching, and introspection.
- [ ] Verify snapshot consistency, changed live rows, deterministic fresh-process behavior,
      malformed cursors, missing context, and cross-tenant isolation.
- [ ] Rename roots, fields, tables, and schema names in a generated test model to detect accidental
      maintained schema-name dispatch. Inspect generated artifacts for fixture values.

Gate: both schemas retain their baseline supported read behavior through one whole-request entry
point. Query counts and row/work budgets are measured inside the database, not inferred from one
HTTP-to-database call.

### Phase 4 — migrate custom mutations and auxiliary routes

- [ ] Implement build-time mutation registration, signature validation, generated dispatch, typed
      inputs/payloads, role/domain policies, serial execution, and durable audit/idempotency contracts.
- [ ] Prove successful writes, rejected inputs before writes, handler errors, transaction rollback,
      duplicate/conflicting idempotency keys, retry ambiguity, and restart behavior on both dialects.
- [ ] Port useful management domain routines to explicit database mutation bindings where applicable.
- [ ] Route any retained preview/admin GraphQL endpoint through the same transpiled query engine with
      a separately bound model and permissions. There must be no residual JVM GraphQL exception.
- [ ] Move filesystem/build/deployment work to explicit control-plane commands/jobs outside serving.
      If a management mutation requests such work, persist a job transactionally; test its lifecycle.
      Record API migration details before deleting the old synchronous workflow.

Gate: every retained GraphQL endpoint satisfies the same execution boundary; custom mutation code
does not depend on CDI callbacks, frontend state, or remote calls during database query processing.

### Phase 5 — switch the frontend and strengthen artifact identity

- [ ] Replace runtime-mode selection with one database invocation adapter and dialect-specific JDBC
      bindings. Preserve transport negotiation and authenticated context extraction.
- [ ] Move GET operation checks into the database and delete frontend GraphQL parsing.
- [ ] Implement outcome-driven transactions and error mapping without GraphQL response inspection.
- [ ] Bind model, shared engine source/version, generated bindings, custom mutation source/registry,
      compiler/submodule versions, routine inventory, and relevant build options into package identity.
- [ ] Verify identity inside each call, including replacement packages and pooled connections.
- [ ] Add deployed HTTP tests that run a frontend artifact physically lacking engine Java classes.
- [ ] Assert complete request forwarding and one execution invocation per envelope, including
      multi-root queries and errors. Transaction protocol calls are allowed; per-field calls are not.

Gate: the production HTTP path works solely through installed transpiled routines; a missing or stale
package fails closed and no configuration value can activate local GraphQL evaluation.

### Phase 6 — remove the superseded architecture

Use this as the deletion inventory; update exact destinations after the dependency audit. Preserve
test fixtures and useful behavior assertions, not an indefinite second execution engine.

| Existing component | Required final disposition |
| --- | --- |
| `TitanCompiledGraphqlRuntime`, `TitanCompiledGraphqlDataModel` | Remove JVM orchestration; replace with thin database invocation. |
| `GenericJdbcGraphqlRuntime`, `GenericJdbcGraphqlDataModel` | Remove production classes and mode/configuration. |
| `demo/blog/*`, `DemoBlogTitanGraphqlFunctions`, `TitanGraphqlFunctions` facade | Migrate useful tests/data, then delete maintained demo execution code and facade. |
| `GraphqlEngine`, parser, coercer, validator, planner, cursor, introspection, JSON execution code | Port into transpilation source module or replace; remove old JVM implementations from production. |
| `GraphqlExecutionEngine`, `GraphqlRuntimeRegistry`, mode-specific runtime interfaces | Replace with a single package-bound database contract; delete fallback/mode branches. |
| `GraphqlApplicationMutationProvider`, Java handler/executor/audit wiring | Replace with build-time database mutation contracts; remove CDI runtime dispatch. |
| `GraphqlSqlModeRuntime`, old SQL entry-point dispatch | Reuse only transport/JDBC ideas; remove demo identities, context tuples, and multiple legacy entry shapes. |
| `TitanGraphqlRoutineSourceGenerator` and carrier invoker | Retain/adapt generation logic useful for internal reads; remove carrier-only serving/package APIs. |
| Model, inference, projection, schema printer, artifact tooling | Retain useful build-time APIs; remove dead duplicates and isolate from frontend runtime. |
| Admin/preview runtimes and management stores | Migrate retained GraphQL processing to database; move build/file orchestration into control-plane tools; remove obsolete paths. |
| `legacySqlIntegrationTest`, legacy transpile/package tasks, mode tests | Migrate useful corpus to the new engine, then remove obsolete tasks/tests and generated-output roots. |

- [ ] Complete every inventory row with deletion/replacement commit references.
- [ ] Remove obsolete properties, sample commands, mode headers, compatibility facades, and dead deps.
- [ ] Update README, design, query contract, model/codegen, custom mutation, security, operations,
      migration, verification, and release docs with the actual final behavior and migration breaks.
- [ ] Remove misleading tests that assert only carrier compilation or fixture equivalence as completion.
- [ ] Verify production JARs, dependency graphs, source inventories, and endpoint reachability after
      cleanup. Grep-only forbidden-name tests are supplementary, not sufficient evidence.

Gate: one maintained engine semantics implementation and one production serving path remain. Build
tools and tests may execute the same engine source on the JVM for verification but cannot provide
an alternate production runtime.

### Phase 7 — final verification and release

Implement the following proposed tasks and make `scripts/release-check.sh --full` run the required
suite. Commands become executable only after their corresponding tasks have been added.

```bash
./gradlew test
./gradlew titanGraphqlTranspilationBoundaryTest
./gradlew titanGraphqlWholeRequestIntegrationTest
./gradlew titanGraphqlThinHttpIntegrationTest
./gradlew titanGraphqlMutationTransactionTest
./gradlew titanGraphqlWorkBudgetTest
scripts/release-check.sh --full
```

- [ ] Generate/package/install/bind both unrelated schemas independently on both databases.
- [ ] Exercise whole documents, variables, aliases, fragments, multiple roots, policies, typed keys,
      nullability, computed fields, cursors/counts, batched relations, introspection, and custom writes.
- [ ] Prove invalid requests and authorization failures occur inside the database before effects.
- [ ] Prove mutation transaction failure, durable state, fresh-engine restarts, stale/missing/tampered
      artifacts, changed engine/handler identities, pool isolation, and deadline cancellation.
- [ ] Use deterministic grammar-based request generation plus fixed expected outputs to expand
      coverage beyond the legacy corpus, including structurally equivalent document variants.
- [ ] Measure representative small/large schemas and growing parent/child cardinalities: generation
      time, SQL/package size, install time, routine count, query counts, rows processed, and p50/p95
      request latency. Fix severe regressions; do not exchange correctness for a benchmark score.
- [ ] Run a clean-checkout build so stale classes/generated files cannot hide missing dependencies.
- [ ] Run public-file/history privacy checks and an independent history-aware secret scanner; inspect
      temporary files, dependency pins, notices, and GPL-3.0 consistency. Do not rewrite published history.
- [ ] Keep all verification local/on-demand; add no recurring hosted Actions spending.
- [ ] Commit, fetch/reconcile remote changes safely, push, and verify a clean synchronized checkout.

Gate: all required checks pass without skipped dialects, semantic fallbacks, or remaining deletion
items. Record exact source/dependency commits and package identities. Only then mark the goal complete.

## 5. Tracking and handoff

Update this table after each phase. A checkbox requires evidence; prose asserting completion is not
evidence. Record failed probes and unresolved compiler gaps separately from passing implementation.

| Phase | Status | Commit(s) | Commands and evidence | Remaining blockers |
| --- | --- | --- | --- | --- |
| 0 Boundary/inventory | In progress | Uncommitted | `docs/database-engine-runtime-inventory.md` | Separate dependency boundaries and portable expected-result corpus remain. |
| 1 Database feasibility | Not started | — | — | — |
| 2 Language engine | Not started | — | — | — |
| 3 Reads/policies | Not started | — | — | — |
| 4 Mutations/auxiliary routes | Not started | — | — | — |
| 5 Frontend/artifacts | Not started | — | — | — |
| 6 Architecture removal | Not started | — | — | — |
| 7 Release | Not started | — | — | — |

For each compiler gap record: smallest failing source, pinned compiler commit, generated diagnostic,
affected dialect, required behavior, chosen fix, regression test, and resolved dependency commit.
For each API break record: old entry point/configuration, replacement, migration action, and test.

The final review must answer: can a SQL client submit a previously unseen supported GraphQL document
with variables and trusted context, and receive its complete correct response on both dialects while
the HTTP distribution contains no Java GraphQL processing implementation? If not, the work is unfinished.
