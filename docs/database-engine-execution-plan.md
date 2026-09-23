# Database-resident GraphQL engine: target architecture and execution plan

Status: active implementation contract, stopped at the requested milestone boundary. M0 inventory
and M1 deployable-boundary stabilization completed on 2026-09-17; M2 and M3 completed on
2026-09-23. M4 is the earliest unresolved milestone. Existing mutation, package, cleanup, and
release slices remain partial evidence only and do not satisfy the overall completion contract.

Baseline: `b1722b73553ab1b5c6a20e63692c8b350f964d34`. The previous completion claim did not satisfy
the intended architecture. Passing the existing release gate is baseline evidence only.

This is an executable work plan: execute phases in order, run their gates, record evidence here,
and leave unchecked work visibly incomplete. This document does not itself activate an agent goal
or implement the engine. Proposed task and directory names below do not exist yet unless stated.

## 0. Active-goal execution ledger

### Updated execution goal

Complete the Titan-transpiled GraphQL engine migration by first producing an authoritative inventory
of every remaining requirement, production path, legacy dependency, and missing proof; then execute
that inventory in dependency order. The result is complete only when one thin HTTP adapter forwards
each whole GraphQL envelope to a package-bound PostgreSQL or MySQL Titan routine, all supported
GraphQL work happens in that routine, the former JVM/fallback architecture is physically absent,
and public-release, clean-clone, remote-reconciliation, and dual-dialect evidence are current.

This section is the operational status ledger for that goal. It supplements the phase detail below:
the detailed phases specify acceptance requirements, while the ledger prevents an implemented slice
or a green narrow test from being mistaken for migration completion.

### Required inventory method

Before advancing a milestone, add or refresh its inventory row with: (1) the explicit completion
requirements and dependent milestone; (2) the authoritative source, artifact, runtime, and test
evidence inspected; (3) a `complete`, `partial`, `unstarted`, `contradicted`, or `unverified`
status; (4) the smallest next acceptance test; and (5) the legacy code or dependency that may be
deleted only after the replacement is proven. A status is `complete` only with current PostgreSQL
and MySQL evidence where the requirement is dialect-independent, plus an absence check for any
claimed deletion. Record command, date, result, and any meaningful measurement in the phase evidence
log below; do not replace an unresolved row with prose implying completion.

### Dependency-ordered milestones

| Order | Milestone | Current status | Required exit evidence | Next sequencing rule |
| --- | --- | --- | --- | --- |
| M0 | Build the authoritative remaining-work inventory: production routes, runtime closure, schema/operation semantics, mutation contracts, corpus cases, package contents, privacy/history, temporary files, remote state, and every legacy deletion target. | Complete inventory (2026-09-17) — `database-engine-runtime-inventory.md` reconciles the worktree, reachable history, local artifacts, both submodules, and remote state. Implementation rows remain visibly partial/contradicted/unstarted. | A requirement-to-evidence matrix with no unclassified production path or legacy runtime dependency. | Refresh after each milestone and re-run before M6/M7. |
| M1 | Stabilize the deployable architecture boundary: one whole-envelope database call, fixed transport/transaction framing, package/identity selection, and a frontend artifact unable to load semantic GraphQL code. | Complete (2026-09-17) — the v1 nine-input contract, explicit route/class/dependency inventory, all source-set boundary tasks, exact ZIP closure, one-call client test, and fresh isolated PostgreSQL/MySQL integration passed. Transitional routes are inventoried M6 deletion targets and are absent from the release ZIP. | Fresh dual-dialect standalone artifact tests plus an explicit route/class/dependency inventory. | Preserve the M1 gates while M2–M5 add semantics; no legacy removal claim is allowed until their relevant rows are complete. |
| M2 | Complete the shared database language core: typed AST/plans, operation and field validation, coercion/defaults, fragments/directives/merging, null/error behavior, introspection, and bounded semantic/database cost. | Complete for the v1alpha1 supported surface (2026-09-23) — the database core proves source-preserving operation/fragment handling, schema-wide structural/argument/directive validation, canonical variable/argument carriers, order-independent compatible field collection, duplicate-response-key rejection, introspection self-description, authored enums/input objects/interfaces/unions/directives and metadata, stable coded errors with locations/paths, and the complete current multi-dimensional request ledger. Generated descriptors drive recursive coercion/defaults, abstract overlap and per-runtime-type merge validation, concrete runtime-condition execution, wrapper/introspection relationships, and generated argument materialization. Fresh PostgreSQL/MySQL packages pass the 34-case shared corpus; all 636 JVM tests are green. Topology is 386 source-local helpers/383 emitted entry points per dialect and 387 MySQL whole-request routines. The v1alpha1 root contract binds exactly one physical projection and rejects unknown fields; heterogeneous multi-projection roots are a future versioned model capability requiring an explicit discriminator/order/cursor/policy/count contract, not an M2 fallback. | A shared, source-located conformance corpus run through fresh installed routines on both dialects, plus explicit schema closure for deliberately unsupported shapes. | Preserve this core while M3/M4 expand generic binding coverage; do not add model-specific runtime query branches to bypass a missing semantic. |
| M3 | Complete generic schema-driven reads: reviewed fields, filters/order, point/list/connection reads, relation traversal and batching, policies, cursors, and response assembly. | Complete (2026-09-23) — AST-backed plan partitioning, the reviewed output/root inventory, request-reject policy preflight, fixed 64-slot unpaginated and Relay relation batches, exact ledgers, bounded 3x3 DNF filtering, computed/one-hop filtering and ordering, cursors/count/page flags, the 34-case corpus, repeatable-read request isolation, fail-closed string tenant isolation, and physically renamed-model source analysis are current. Fresh PostgreSQL/MySQL packages contain 470 entry points (473 reachable helpers; 474 MySQL routines), install and bind cleanly, and pass the consolidated live gate. | Corpus and measured query-count/row-budget evidence across unrelated models, relation shapes, and parent cardinalities on both dialects, plus every declared filter/order path. | Preserve the M3 gate while M4 adds explicit custom mutations; do not resume M4 until the requested milestone pause is lifted. |
| M4 | Complete explicit custom mutations: typed input and operation prevalidation, serial atomic execution, policies, locks, audit/idempotency/outbox behavior, output completion, and route replacement. | Partial — constrained scalar/enum and nested authored-input update/rollback proofs exist, including a nested default applied inside the transpiled engine; nullable writes, concurrency, durable idempotency/audit/outbox, arbitrary procedures, and retained management mutations remain open. | Cross-dialect transaction, concurrency, idempotency, audit/outbox, and failure-path evidence. | Register every mutation in the package identity before deleting handler paths. |
| M5 | Prove full package and runtime parity: package/routine/mutation attestation, shared dual-dialect corpus, isolation/performance/budget evidence, replacement-package behavior, and adversarial security/privacy scans. | Partial — source/runtime and package identities plus selected corpus gates exist; final attestation and replacement evidence are open. | Fresh package/install/bind/HTTP proof on both dialects, measured bounds, and reproducible artifact/privacy reports. | Do not interpret package installation alone as semantic parity. |
| M6 | Remove the superseded architecture and release debris: JVM engines, fallback modes, model-specific dispatch, obsolete routes/tests/tasks/dependencies/docs, private history/data, and unnecessary temporary/generated files. | Unstarted as a deletion milestone — transitional sources are intentionally still present. | Search-based absence evidence, dependency/artifact closure checks, migrated test coverage, and clean-clone verification. | Delete only after M1–M5 replacements have evidence; do not retain compatibility paths in production. |
| M7 | Release and publish: public documentation/license/notice/security review, clean check-in, safe remote reconciliation, final verification without CI dependence, and push to `rbilleci/titan-graphql`. | Unstarted. | Clean clone, all required local gates, remote divergence review, and a reviewed atomic release commit. | This is the only milestone that authorizes the final push. |

### Status-update discipline

- At the start of a work increment, select the earliest milestone with an unproven dependency and
  state its target evidence in the relevant phase log.
- At the end, update the matching ledger/phase entry with what changed, exact commands, both-dialect
  outcome, and the next unresolved requirement. Preserve failed or contradictory evidence.
- A passing focused test advances only its explicitly named inventory row. It cannot advance the
  enclosing milestone without its complete exit evidence.
- When an implementation exposes a Titan/compiler gap, record a minimal reproducer, dialect result,
  pinned Titan revision, chosen remedy, and regression before relying on it elsewhere.
- Use tiered validation so correctness evidence does not dominate implementation time: run focused
  JVM/helper/generator tests per edit; bundle coherent source changes before a cold transpilation;
  use one installed dialect for intermediate behavioral proof; and pay the dual-dialect cold
  transpile/install/corpus cost only at a milestone boundary or before release. Reuse unchanged
  generated/package artifacts and select exact live methods rather than whole integration classes.
  A tier may delay an expensive gate, but it may not weaken that milestone's exit criteria.
- Re-run M0's inventory before M6 deletion and again before M7 release; the final audit must prove
  the original completion contract rather than merely finding no obvious failing test.

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

The current proof freezes the v1 nine-input dialect contract:

```text
execute_graphql_request(
  query, operation_name, variables_json, extensions_json,
  trusted_context_json, allow_mutations, expected_model_semantic_hash,
  expected_runtime_identity, expected_package_identity
)
```

`trusted_context_json` must include `contextVersion: "titan.graphql.request-context/v1"`; the
installed entry point rejects a missing or different version. The generator embeds the normalized
model semantic hash and a dialect-specific runtime identity, and rejects either caller mismatch
before it parses or accesses application data. The runtime identity is reproducibly calculated from
the semantic model, dialect, shared engine source, source generator, build options, dependency lock,
and pinned Titan version. It is copied into the bound package and descriptor, then checked by the
installed routine on every call. `expected_package_identity` binds the staged final SQL inventory,
excluding only its self-referential identity migration, to the identity stored by package install.
Phase 5 must still cover the final custom-mutation registry and pooled replacement behavior.

Return completed GraphQL JSON plus a small, separately encoded transaction outcome: HTTP
classification and whether the surrounding transaction may commit. PostgreSQL's text function
returns the fixed `TITAN-GRAPHQL-TRANSPORT/1 <COMMIT|ROLLBACK>\\n<GraphQL JSON>` frame; MySQL's
procedure returns `response_json` and `transaction_outcome` columns. The frontend validates that
fixed protocol and returns only the JSON without semantic rewriting. A routine may return normal
GraphQL errors and still require rollback; the adapter must never decide commit from SQL success or
by inspecting a GraphQL response field.

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

- [x] Record the baseline commit, Titan pins, current repository/dependency state, and current
      feature/test inventory in `database-engine-runtime-inventory.md`.
- [x] Map every production entry point, including `/graphql`, preview, and admin GraphQL routes.
- [~] Create separate dependency boundaries for build/model tooling, transpilable engine sources,
      generated schema code, thin HTTP serving, and tests. `databaseEngine` is now an isolated
      Gradle source set with only the JDK/JDBC and Titan DSL available, plus a source/classpath
      boundary check. It proves the engine closure is distinct; extraction of a deployable thin
      frontend module remains required so the frontend cannot load engine Java classes.
- [x] Add boundary gates that fail if the standalone serving artifact loads parser, validator,
      planner, resolver, cursor, introspection, GraphQL response-rendering, non-frontend Titan, or
      transitional application classes. The current isolated artifact passes; legacy `main` remains
      a separately inventoried M6 deletion target.
- [~] Establish a portable fixed expected-result corpus independent of the demo whole-request
      implementation. `src/test/resources/database-engine-corpus/commerce-v1.json` contains
      concrete expected GraphQL JSON and is executed through the installed PostgreSQL and MySQL
      commerce entry points by `DatabaseEngineExpectedResultCorpus`; it covers named-operation
      variables, aliases, multiple roots, selected-operation fragment scoping,
      fragments/directives, a relation, repeated compatible connection-root children,
      `__typename`, and nullable scalar output. It does not yet
      capture every accepted legacy case; expand it as feature migration continues, and mark legacy quirks separately. A demo implementation
      must not become the authoritative oracle for correctness.

Gate: inventory covers all runtime paths; an intentionally failing architecture test exposes the
present violation; the new module/dependency layout is defined and reviewable.

### Phase 1 — prove a complete request inside the database

- [~] Prove token/AST/plan storage, helper composition, JSON input/output, Unicode, nulls, and bounded
      iterative traversal in a small Titan-transpiled prototype on PostgreSQL and MySQL. The shared
      `DatabaseGraphqlLanguage` now builds a bounded invocation-local lexical token stream plus a
      scalar operation/field selection index, traversed with an explicit bounded stack before
      schema dispatch on both dialects. `DatabaseGraphqlAst` now converts that once-built plan into
      a bounded typed executable-node carrier with node kind, source offset, direct-selection
      parent, argument, executable-variable-reference, directive, and fragment topology. It is
      now used by selected-operation variable declaration/reference and typed input-preflight
      validation in both package closures. `DatabaseGraphqlTypeReference` parses the normalized
      named/list/non-null declaration once into a bounded postfix descriptor; the AST preflight
      uses that descriptor for input-type admission, default-nullability, required/null supplied
      values, and outer-list navigation. Generated serving routines now retain one typed
      AST for the selected operation and pass it through root/child selection planning, fragment
      traversal, field identity, response keys, leaf checks, field-argument shape/value lookup,
      variable type/default lookup, built-in directive evaluation, and generated operation
      dispatch, selected-operation preflight, and default-value semantics. Generated typed input
      descriptors now drive iterative scalar, enum, input-object, and list validation for
      variables/defaults without a runtime schema object graph or recursive routine calls. The
      current closure is 251 helpers plus one public entry point per dialect. Variable uses now
      compare named/list/non-null wrappers structurally through a bounded iterative descriptor
      walk, including the permitted non-null-to-nullable flow and variable-default exception.
      Generated field-selection validation and variable preflight failures tied to one unique
      declaration return line/column locations from retained AST offsets; canonical input
      materialization, error paths, and remaining field-use semantics still require a typed
      AST-driven plan.
- [~] Generate bindings for a minimal model from each existing unrelated schema. Pass a whole GraphQL
      document and JSON variables to the database; parse, validate, authorize, read live rows, and
      return complete aliased GraphQL JSON from the installed routine.
- [~] Include multiple query root fields, a nested relation, a malformed document, and denied access.
- [~] Prove database-internal row consumption without per-field JDBC calls or intermediate result sets.
- [~] Prove one custom mutation, serial execution, a failure after an earlier write, full rollback,
      outcome delivery, and caller-owned commit on both dialects.
- [ ] Freeze and document the public SQL signatures, context version, transaction/outcome contract,
      and concrete internal state representation from these results.
- [ ] Resolve or explicitly track every compiler blocker before proceeding to broad feature migration.

#### Phase 1 evidence log

2026-09-15 — dual-dialect point-read proof (partial):

- Added `src/database-engine/java` for the shared transpilable request scanner/JSON helpers and
  a model-driven `TitanGraphqlDatabaseEngineSourceGenerator`. PostgreSQL exposes
  `public.execute_graphql_request(query, operation_name, variables_json, extensions_json,
  trusted_context_json, allow_mutations, expected_model_semantic_hash, expected_runtime_identity,
  expected_package_identity)` as a text-returning function. MySQL exposes the same nine request
  inputs as a procedure and returns one final
  `response_json` result set; this is the
  tested dialect adapter for the same logical result contract.
- The entry point receives the complete document and envelopes, performs its own request checks,
  operation selection, root/field selection, argument-variable lookup, policy check, direct parameterized point read,
  and GraphQL JSON/error construction. It is neither a carrier invocation nor a call back into
  a JVM GraphQL runtime.
- Before operation selection the engine lexes the entire document into an ephemeral bounded
  token-range stream (`kind/start/end`, 16,384 UTF-16 code units and 8,192 tokens). It rejects illegal
  punctuation, malformed numbers, invalid normal-string escapes, unterminated normal/block strings,
  mismatched delimiters, and more than 128 nested `()`, `[]`, or `{}` pairs before any selection,
  policy, or JDBC work. PostgreSQL and MySQL direct-package tests prove malformed punctuation,
  an unterminated string, mismatched delimiters, and an over-deep carrier fail at this
  database-resident lexical gate. The representation is scalar-encoded because current Titan support
  permits bounded scalar state but not a dynamically grown object graph. `DatabaseGraphqlLanguage`
  owns this representation; its token consumers select one operation, identify its operation kind,
  retain only its transitive named-fragment closure, and derive root/field boundaries across aliases,
  argument containers, and directives before schema dispatch. The engine no longer owns the former
  top-level selected-document/reachable-fragment or root/field-boundary source scanners. Its
  fragment-expansion stack now walks direct typed-AST field, named-spread, and inline-fragment
  nodes in document order. Once the AST is materialized it no longer reads the legacy scalar
  selection-topology records for that traversal; the incomplete merge bridge now compares retained
  lexical tokens, rather than source characters, but still needs schema-aware semantic merge planning.
  Generated PostgreSQL/MySQL entry points first
  build one bounded lexical carrier for the complete request and use that same carrier for
  request-wide lexical/budget preflight and operation/fragment-closure selection. They then
  construct exactly one request-local selected-operation scalar token-and-selection plan.
  The generated AST-aware preflight completes from that retained typed carrier; the compatibility
  `preflightRequest` wrapper is not used by generated serving routines and remains only for
  direct callers.
  Its bounded iterative syntax pass indexes selected-operation kind and variable definitions (name,
  type, and optional default-value source ranges), executable variable references from the selected
  root and retained fragments, operation roots, field headers/child selections, field identity
  (response key and actual schema name), complete field-argument value nodes, and directive nodes
  (including aliases, arguments, directives, nested fields, direct field/named-spread/inline-fragment
  selection items, and named fragment definitions). `DatabaseGraphqlAst` consumes that retained
  plan without re-lexing and materializes typed executable nodes with parent topology, including
  arguments, executable variable references, directives, and fragment nodes. Selected-operation
  duplicate/undefined/unused variable validation and the schema-neutral variable input preflight
  now consume those AST nodes and their typed type-reference descriptors. Named-fragment header
  lookup (name, type condition, directive boundary, and selection range) now consumes retained
  AST fragment nodes rather than returning to the syntax index; one AST is built for each bounded
  fragment-expansion traversal rather than once per named spread. Generated argument/default
  resolution, argument count/name/duplicate validation, and generated variable-type/default
  compatibility checks now consume retained AST source ranges. Built-in field, spread, and
  fragment-definition and operation-definition directive location rejection consumes retained AST
  boundaries. AST-range-backed variable-default null/constant-value checks,
  operation-directive location rejection, query/mutation dispatch, root/child planning,
  fragment/directive traversal, generated root/child dispatch, response-key assembly, duplicate-key
  checks, merge checks, and leaf boundary validation consume that plan rather than independently
  lexing or rediscovering those boundaries. A malformed variable-definition
  header is retained as a plan error state so it keeps the GraphQL variable-definition diagnostic
  rather than being misreported as a lexical error. The language lexer also consumes a block
  string's escaped triple quote as one content unit, avoiding an overlapping false terminator.
  Variable-default semantic checks now scan their retained AST value ranges (so `$` in a string or
  block string is never confused with a variable reference). Generic GraphQL input-object/list
  literal walking now also consumes this lexer core; the separate strict-JSON walker remains only
  for variable-envelope values. The remaining source-location bridge is still not a complete
  token-consumed AST or
  execution-plan representation. Generated serving routines now retain the selected-operation AST
  for all field/selection traversal, generated argument/variable binding, directive evaluation,
  operation dispatch, selected-operation preflight, and default-value semantics. Generated
  execution calls no longer thread the lexical plan after AST materialization: fragment traversal,
  selection planning, response keys, leaf checks, and merge-header equivalence use AST records and
  bounded source-range comparison. The remaining bridge is limited to incomplete schema-aware
  coercion pending typed execution-plan migration.
- `titanGraphqlVerifyDatabaseEngineBoundary` rejects framework/JVM-runtime imports and
  dependencies from that source set. The current commerce PostgreSQL and MySQL transpile/package/
  install tasks live-install 251 source-local helpers plus one public entry point. The generator
  uses compact, digest-qualified local identifiers with an unambiguous `_` delimiter, but this
  does not make package transport size a safe invariant: the current MySQL public procedure is
  1.076 MB. The shared test container uses a 16 MiB packet limit; production packaging must
  preflight routine/package size against the target server rather than rely on the obsolete 1 MiB
  assumption.
- 2026-09-16 observability correction: Titan previously emitted telemetry writes from every
  source-local helper, so one GraphQL request could create an unbounded number of telemetry rows.
  The transpilation pipeline now instruments only public stored entry points. Focused Titan
  regression coverage proves a public entry point retains telemetry while its internal helper does
  not. Current regenerated PostgreSQL and MySQL commerce inventories contain 251 helpers plus
  `execute_graphql_request`, with exactly one routine containing a telemetry write:
  `execute_graphql_request`. This removes
  helper-write amplification but is not, by itself, a performance acceptance result.
- 2026-09-16 MySQL routine-cache correction: the generated graph previously contained 260
  source-local helper functions, exceeding MySQL's default per-connection
  `stored_program_cache` capacity of 256. A long mixed corpus could therefore flush the
  stored-function cache during a request and return SQL-`NULL` transport columns. The generated
  graph first called five `DatabaseGraphqlLanguage` implementations directly instead of retaining
  trivial `DatabaseGraphqlEngine` forwarding wrappers. The subsequent AST-backed variable
  declaration/reference validation and typed input-preflight consolidation removed the duplicate
  language-plan declaration pass. Typed field metadata then temporarily increased the closure to
  253 routines. Four otherwise-unreachable Engine forwarding overloads that accepted already
  tokenized input objects/lists have since been inlined into their public wrappers, retaining the
  same lexical-token and JSON-fallback behavior while restoring the graph to 248 helpers plus the
  public procedure. The subsequent generated-request AST carrier removed the old field-source
  bridge from the reachable production closure, leaving 240 helpers plus the public procedure.
  The subsequent AST-backed argument/value and variable declaration range accessors replaced the
  generated lexical-plan argument bridge; they add eight small helper routines, bringing the
  current closure to 248 helpers plus the public procedure. The subsequent AST-backed directive
  traversal removed now-unreachable lexical directive helpers, reducing the current closure to
  235 helpers plus the public procedure. Generated query/mutation dispatch then moved to the
  selected-operation AST's first node, leaving the current closure at 236 helpers plus the public
  procedure. Selected-operation preflight now accepts that existing AST rather than rebuilding
  it; operation kind, header directives, variable discovery, and type admission use AST records.
  Default-value semantics now scan retained AST ranges, and generated serving routines invoke the
  AST-only preflight entry point. AST-only traversal then replaced plan-threaded fragment
  expansion, field identity, response-key, leaf, and merge-range helpers; its range comparator
  preserves normal/block-string content while ignoring GraphQL trivia. This removed one more
  helper, leaving the current closure at 231 helpers plus the public procedure.
  The complete mixed-corpus and 64-request
  single-connection regressions pass with
  MySQL defaults; no server-global cache override is part of deployment. Routine inventories are
  reported by transpile tasks; there is no project-defined routine-count build ceiling.
  Deployment validation must use the configured server/cache behavior and the
  cross-dialect conformance and connection-reuse suites rather than an arbitrary source limit.
- 2026-09-16 typed-AST selection traversal: `DatabaseGraphqlAst.selectionItemCount` and
  `selectionItemInfo` now expose direct `f`/`p`/`i` children for a selection set. Fragment
  expansion consumes those AST records rather than the legacy syntax index's direct-selection,
  named-spread, or inline-spread lookups. Focused AST/language/selection-plan tests pass; fresh
  installed commerce-package tests pass on PostgreSQL (7 tests) and MySQL (8 tests, including
  repeated whole-request calls on one connection). That increment's regenerated inventories were
  252 routines; the MySQL package has no reachable dynamic SQL or `@titan_*` session variables.
- 2026-09-16 typed-AST field metadata: field nodes now retain their header, child selection,
  response-key, and schema-name source ranges, including alias distinction. Relation selection
  planning consumes the retained child selection where it already owns the request-local AST,
  instead of querying the legacy field-selection index. Focused AST/language/selection-plan tests
  and fresh installed commerce packages pass on PostgreSQL (7 tests) and MySQL (8 tests, including
  repeated requests on one connection). That increment temporarily brought inventories to 253
  routines per dialect. The subsequent input-wrapper consolidation restored them to 249. The
  generated serving routine now retains one selected-operation AST and uses it for selection
  planning, traversal, aliases, schema-name matching, leaf checks, argument lookup, and
  variable/default type compatibility, directive evaluation, operation dispatch, and preflight.
  At that point, later closure reductions left the reachable inventory at 232 routines per
  dialect; subsequent AST and deadline work brought the closure to 249 helpers plus its
  public entry point before the current variable-materialization increment.
  The current request-local variable carrier resolves each supplied/default value once after
  selected-operation typed preflight and generated bindings consume that carrier rather than
  repeatedly rescan the JSON envelope. Generated execution aliases its post-preflight variable
  input to that carrier, so AST selection and built-in directive traversal use the same resolved
  values as argument bindings. It deliberately retains the original GraphQL/JSON spelling
  for the existing coercers; it is not yet a canonical typed input-value tree. An initial use of
  four generic AST node accessors raised the MySQL reachable closure to 258 helpers and reproduced
  the server's 256-entry `stored_program_cache` SQL-`NULL` failure. The final bounded `Nv`
  variable-record scan is local to the materializer, preserves fail-closed range checks, and leaves
  251 helpers plus the public entry point. Fresh installed commerce suites pass 8 PostgreSQL and
  9 MySQL tests, including defaulted, explicit-null, and supplied scalar variables.
  A field-metadata reader bound of four was corrected to support the field carrier's sixth
  schema-name-range slot, with direct AST-path equivalence coverage. The AST argument/variable
  increment transpiles on both dialects and freshly passes the installed commerce package suites
  on PostgreSQL (7 tests) and MySQL (8 tests, including repeated one-connection requests).
- Fixed-shape MySQL `RawSql` and single-row reads now emit static stored-program SQL using
  routine-local identifiers, not `PREPARE`, `EXECUTE`, or connection-scoped `@titan_*` variables.
  Static single-row reads reset their local targets and consume `NOT FOUND`, preserving the prior
  null-on-no-row behavior. The generated commerce public procedure has zero dynamic-SQL or
  `@titan_*` occurrences. The explicitly permissive structural-splice emitter retains a separately
  tested dynamic fallback with statement-qualified, type-stable staging variables; it is not
  reachable from the generated generic GraphQL package.
- The AST-backed relation planner now walks a bounded, generated-type ancestry frontier. It retains
  every merge-compatible occurrence of a relation from `Query` through the current generated
  object, then coalesces their child plans before either dialect renders JSON. It unwraps direct,
  named-fragment, and inline-fragment selection ancestry for the reviewed generated paths. This
  applies to repeated point roots and bounded relation traversal, including Relay connection nodes:
  `{ customer(id: 7) { orders { id } } customer(id: 7) { orders { reference } } }` produces one
  `customer.orders` value containing both fields, and a connection-node selection of
  `orders { customer { name } } orders { customer { id } }` produces one nested customer with both
  `name` and `id`. Fresh PostgreSQL/MySQL installed-package tests cover these shapes without a JVM
  resolver. Incompatible response-key/name/argument/directive combinations still fail closed;
  interfaces, unions, and complete schema-aware merge validation remain Phase 2 work.
- The same request-local plan now coalesces compatible repeated, generated Relay connection roots
  before the connection renderer selects `edges`, `totalCount`, or `pageInfo`. Their nested
  selection plans retain the typed `Query → Connection → Edge/PageInfo` ancestry, so separate
  occurrences such as `customerFeed { edges { node { id } } pageInfo { hasNextPage } }` and
  `customerFeed { edges { node { name } } pageInfo { endCursor } }` produce one connection with
  both node and page-info children. Fresh installed PostgreSQL and MySQL package tests execute
  this shape solely through the public transpiled entry point. Repeated aliases of scalar
  connection wrapper fields (`totalCount` and `__typename`), `PageInfo` scalar children, and Edge
  scalar children (`cursor` and `__typename`) are validated individually and emitted from one
  database count/page-row state. Compatible repeated object-valued wrapper fields (`edges`,
  `pageInfo`, and `node`) collect their child selections. Every specialized Connection, Edge, and
  PageInfo plan now also rejects a response-key collision between different fields before SQL or
  response assembly, closing the wrapper-alias escape. Abstract-type response-shape and mutually
  exclusive selection rules remain Phase 2 work.
- Relation-plan parent discovery now reads the field parent and selection owner from typed AST
  nodes rather than reparsing direct-selection topology records. This removes the former
  field-only record assumption, so a relation selected alongside or through named/inline fragments
  keeps its merged child selection. The fixed expected-result corpus and focused plan regression
  cover aliases, a conditional relation, and named fragments.
- `databaseEngineIntegrationTest` and `databaseEngineMySqlIntegrationTest` deploy their packages
  and fixture DDL, invoke only the public SQL entry point, and verify literal and variable
  arguments, aliases, selected and computed fields, and a live-row change. Both pass without
  instantiating a JVM GraphQL engine.
- The unrelated commerce model now has separately generated source, SQL, package, scratch-install,
  and direct-entry-point tasks for each dialect: `databaseEngineCommerceIntegrationTest` and
  `databaseEngineCommerceMySqlIntegrationTest`. Historical runs cover live tables with nullable values,
  `roleIn` policy denial, string, integer, UUID, composite, and both integral/string-backed GraphQL
  `ID` keys, aliases/variables, and a live row change. ID input coercion and JSON serialization
  happen inside the installed routine: either GraphQL ID literal form is accepted where its reviewed
  backing representation permits it, and both forms return a JSON string. They also prove two
  selected root fields in one invocation, malformed-request errors,
  and a model-generated one-hop collection relation whose internal cursor is fully consumed before
  the final GraphQL JSON response. The current PostgreSQL and MySQL direct-package proofs pass;
  the MySQL package also proves 64 whole requests over one connection. PostgreSQL's UUID comparison is generated from model scalar
  metadata as `CAST(? AS UUID)`; the caller remains unaware of physical key types.
- The generator now also recognizes deliberately narrow, model-defined Relay connection plans:
  the original non-null local `Int` cursor whose tie breaker is the same column, and one reviewed
  non-null local `String`, `UUID`, `Int`, `Long`, or integral `ID` custom order key with a non-null
  integral tie breaker. Both use exact counts, reviewed local equality arguments, and Boolean
  context predicates. The generated routine
  executes parameterized `LIMIT pageSize + 1` reads, a model-derived `COUNT(*)`, and emits selected
  `edges.node`, `edges.cursor`, `totalCount`, and Boolean `pageInfo` with aliases entirely inside
  PostgreSQL/MySQL. It emits and validates the established opaque `tgqlc1` cursor format in the
  transpiled engine—not through the JVM `GraphqlCursorCodec`—and supports both `first`/`after` and
  `last`/`before`, including literal and JSON-variable String and integral `orderBy`, plus a
  literal UUID `orderBy`, in either
  direction.
  Backward reads are bounded in reverse SQL order, then their edges are restored to the model's
  declared order in the transpiled routine. The opposite page flag is an exact database check over
  the same model/authorization scope, not a proxy for whether an opaque cursor token was supplied.
  The commerce direct-package tests compare database-produced cursor text against the public Java
  codec, then pass String, UUID, and integral tuple cursors back for forward, backward, and descending
  continuation on both dialects. UUID cursors are validated before PostgreSQL's static `CAST(? AS UUID)`
  predicate or MySQL's reviewed `CHAR(36)` binding. The integral proof includes equal `sortRank` values with a distinct
  `id` tie breaker, so it exercises the complete lexicographic tuple predicate rather than only a
  single-value cursor. The generated access uses one constant parameterized SQL statement with static
  model branches; no request value becomes SQL text. Supplying both `first` and `last`, or `before`
  without `last`, fails in the database. Composite or non-integral/non-String/non-UUID tuple cursors,
  nullable sort values, computed/relation ordering, multiple custom keys, and relation connections
  remain unsupported.
- Deliberately unresolved scalar gap: `Float`/`Decimal` custom ordering is not enabled. A direct
  probe found PostgreSQL's database text conversion renders a stored `500.0` as `500`, whereas the
  existing JVM cursor contract uses Java `String.valueOf(Double)` (`500.0`); exponent formatting
  has further dialect variation. Enabling that scalar requires one portable, Java-compatible
  finite-double cursor-text primitive in Titan (with normal, integral, signed-zero, and exponent
  regression cases), not a database-specific cast or a weakened public cursor contract.
  This is a constrained Relay increment, not general connection execution.
- Resolved Titan portability gaps: ordinary database text comparisons/search observe their
  configured collation, which is case-insensitive by default on MySQL and may be
  nondeterministic in PostgreSQL. That differed from Java's exact `String.equals` semantics and
  could dispatch `Customer` as `customer`, confuse operation/cursor metadata, or misdecode opaque
  String tuple cursors. Titan now lowers `String.equals` to UTF-8 byte equality on PostgreSQL and
  binary equality on MySQL. `titan.dsl.Text.base64UrlAlphabetIndex(char)` likewise lowers to a
  bytea `POSITION` lookup on PostgreSQL and binary `LOCATE` on MySQL. Lowerer and dialect-emitter
  tests, plus regenerated PostgreSQL/MySQL commerce packages, prove wrong-case GraphQL root names
  fail rather than dispatch. The shared engine validates malformed UTF-8 Base64 input before native
  decode, so it remains a GraphQL validation failure rather than a database exception.
- Resolved dialect probe: MySQL's safe single-row JDBC lowering rejects a `SELECT EXISTS (SELECT
  ...)` projection because it deliberately does not parse nested SQL. The generated predecessor
  check therefore uses the equivalent plain, bounded `SELECT TRUE ... WHERE <same scope> LIMIT 1`
  and interprets row presence in the transpiled routine. `titanGraphqlTranspileCommerceMySqlDatabaseEngine`
  and both direct-package suites verify that supported-subset rewrite; no Titan dependency change
  or hidden JVM fallback was introduced.
- The common database source now selects a single named operation before any root dispatch while
  retaining its operation header and variable definitions. A two-operation commerce document proves
  that the requested second operation is the only one executed; an omitted name fails in the
  database rather than falling back to the first operation. The bounded selector skips comments,
  normal and block strings, variable/default argument blocks, directives, and fragment definitions
  while locating the selected operation.
- The shared preflight now rejects a duplicate variable declaration, an undefined variable reference
  (including one from a directive), and an unused variable declaration before model-root dispatch.
  The latter closes a semantic difference from the established JVM conformance corpus without
  moving validation to the HTTP process. PostgreSQL and MySQL commerce-package tests exercise the
  unused-variable error through the installed public routine.
- Variables, extensions, and trusted context are now independently parsed as bounded, strict JSON
  object envelopes inside the common engine. The scalar parser rejects malformed strings/escapes,
  numbers, nesting, separators, and trailing input instead of reusing GraphQL's comment/comma
  rules. It also rejects an unknown supplied variable before schema dispatch. PostgreSQL/MySQL
  package tests prove malformed variables JSON and an extra supplied variable. Escaped JSON Unicode
  code units are syntax-validated but deliberately preserved rather than decoded until Titan gains
  a portable code-point-to-text primitive; direct Unicode text remains supported. This limitation
  is fail-closed for trusted string comparisons and remains a Phase 2 compatibility gap.
- Selected-operation variable validation distinguishes a missing or supplied-`null` non-null
  declaration from an omitted nullable declaration. Omitted and explicit-`null` nullable values
  now reach the schema-derived binding, where optional connection arguments retain their generated
  defaults; a nullable variable used for a non-null argument is still rejected by that binding.
  PostgreSQL and MySQL commerce direct-package tests cover each state. Full typed coercion remains
  Phase 2 work.
- The shared language-plan parser validates nested list/non-null variable type syntax rather than
  treating a bracket-matched range as a type. It retains each parsed declaration and optional
  default range in the request-local scalar plan, normalizes legal ignored tokens while comparing
  scalar declarations, accepts `[[Int!]!]!`, and rejects malformed forms such as `[[]]` before
  schema dispatch. PostgreSQL and MySQL installed commerce packages exercise both cases; list
  value coercion itself remains Phase 2 work.
- Generated entry points now pass a compact typed input descriptor rather than a comma-delimited
  name list. It records scalar kinds, enum values, input-object fields, nested scalar-filter
  objects, and list wrappers for the reviewed root and mutation surface. Shared preflight
  iteratively applies it to declared variable types, JSON values, and GraphQL defaults; it rejects
  output-only or unknown types, wrong scalar or enum values, malformed objects, unknown or
  duplicate object fields, and invalid nested list items before root dispatch. The walker applies
  singleton-to-list coercion with a bounded length-coded work queue rather than recursive calls.
  The current safeguard allows at most 128 input values to be visited per declared variable;
  it is a request resource budget, not a model, routine-inventory, or project-size limit. The
  same singleton carrier reaches generated list bindings, so validation and execution agree.
  PostgreSQL/MySQL installed commerce packages cover accepted filter/order variables, singleton
  filter lists, and rejected malformed object/list values, while focused language-core tests cover
  defaults, nested shape errors, and the input-work budget.
- The same transpiled preflight retains the operation-level default rules: a non-null variable may
  not default to null, and a default may not reference another variable. The descriptor validates
  scalar, enum, list, and input-object defaults by the same path as supplied values. Canonical
  value materialization, field-use compatibility, custom mutation input objects, and nullable
  input-field semantics remain Phase 2 work.
- Required generated scalar bindings now also honor the GraphQL variable-use exception for a
  nullable declaration with a non-null default. An explicitly supplied JSON `null` remains an
  in-database required-argument error rather than being sent to JDBC; direct package tests cover
  the defaulted and explicit-null paths on both dialects.
- Selecting a named operation now retains only its transitively reachable fragment definitions.
  This prevents a variable used by a fragment of a different operation from being misread as a
  variable reference of the selected operation. The closure remains bounded and preserves cycle
  detection in the engine traversal; PostgreSQL/MySQL commerce packages prove a selected operation
  succeeds when a different operation alone spreads a fragment with its own variable.
- The common decimal scanner now applies GraphQL's signed 32-bit range to every generated `Int`
  argument binding, including point roots, connection equality arguments, `first`/`last`, and
  reviewed mutation arguments. `Long` and integral `ID` bindings retain their model-declared
  64-bit range; a string-backed `ID` is decoded and bound as text without changing GraphQL's
  string-valued output contract. PostgreSQL/MySQL commerce packages reject `Int` literal and JSON-variable values
  of `2147483648` inside the transpiled entry point before a database read; full
  list/input-object/numeric scalar coercion is still Phase 2 work.
- The shared integer scanner now detects its own 64-bit accumulator overflow before multiplying.
  This prevents an enormous syntactically valid GraphQL integer such as `18446744073709551623`
  (`2^64 + 7`) from wrapping to the existing key `7` and passing the later GraphQL `Int` range
  check. PostgreSQL and MySQL commerce packages exercise literal and JSON-variable forms and
  reject both inside the installed routine before a lookup. The MySQL deployment fixture also
  explicitly selects the generated-routine database (`public`) before applying the unqualified
  Titan runtime migration, so its runtime helpers and generated routine closure share the same
  database just as a package install does.
- The generated Relay connection path now accepts reviewed local `Int` equality arguments and
  model-defined `booleanEquals` context predicates as JDBC parameters. Its page read, count, and
  selected opposite-boundary check share the same parameterized predicates; an enabled missing context value produces zero rows/count
  for a fail-closed filter. The unrelated demo-blog package proves `articles(authorId:)`, a false
  `publishedVisibility` context predicate, and its missing-value fail-closed case through direct
  PostgreSQL/MySQL installed routines. The thin-adapter feasibility layer now forwards named
  authenticated scalar context values under `contextValues`; its unit test verifies Boolean,
  numeric, and escaped string transport. Client ordering arguments remain rejected in the database
  engine until their complete generated cursor plans are implemented.
- The database engine now has its first generated-input-object read slice. A connection whose
  model exposes reviewed local scalar filters accepts a `TypeFilter` literal or JSON variable,
  walks that object inside the transpiled common runtime, rejects unknown or duplicate fields at
  every supported level, and generates a fixed conjunction of local `eq`, `neq`, comparison,
  `isNull`, `contains`, `startsWith`, and `endsWith` predicates. Reviewed `String`, `Int`/`Long`,
  `Float`/`Decimal`, `Boolean`, `UUID`, and field-declared integral-/string-backed `ID` `in`
  predicates use a fixed 8-value static carrier: an empty list yields no rows and an over-limit
  list fails before SQL. Eight slots bound generated request work; every item is scalar-checked
  and JDBC-bound by model type; ID values use
  either `long` or text from `idStorage`, rather than allowing the client to select a JDBC type.
  Presence flags and typed values bind the same static SQL scope for edge rows, exact
  count, and opposite-page checks; no client text is interpolated. The SQL uses
  `POSITION`/`LEFT`/`RIGHT` rather than `LIKE`, preserving literal `%` and `_` input.
  PostgreSQL/MySQL commerce packages prove inline conjunctions, literal and JSON-variable `in`,
  `CustomerFilter` JSON variables, and duplicate literal/JSON-object rejection. This is not the
  existing broad Java-mode filter contract: boolean composition, relation hops, computed paths,
  filter-plus-custom-order cursors, list/default coercion, and schema-wide input validation remain
  deliberately fail-closed in the database engine.
- Query-root dispatch now walks the enabled, fragment-expanded root selections in request order,
  rather than looking up each generated root only once. The common engine first emits a bounded
  scalar `v1;source-offset;...` root selection plan; dispatch consumes that plan without
  re-expanding the fragment graph for each ordinal. Distinct aliases of the same model root
  therefore execute as distinct database plan instances and retain their response keys; commerce
  package tests prove two aliased `customer` roots with independent arguments on PostgreSQL and
  MySQL. The plan is an invocation-local carrier, not yet the final token-consumed AST. Point-object
  bindings now likewise validate and emit each planned scalar field in request order, proving two
  distinct aliases of `Customer.name` plus an aliased ID on both dialects. Nested relation-object
  bindings now use the same scalar selection-plan traversal, proving distinct aliases of an
  `Order.reference` field under `Customer.orders`. The shared plan now collapses exact duplicate
  selections (including those expanded from fragments) after alias removal and ignored-token
  normalization, so valid repeated scalar/relation selections execute once and emit one JSON
  member on both dialects. The bounded typed-ancestry planner also combines compatible nested
  child selections. A response-key collision whose actual field, arguments, directives, or
  unimplemented type-condition semantics differ remains fail-closed rather than producing
  duplicate JSON members. Separate aliases of the supported one-hop `Customer.orders` relation
  still execute as distinct database reads with independent child selections and response keys.
  Commerce PostgreSQL/MySQL tests cover exact scalar and relation merges as well as incompatible
  root, point, relation, and mutation collisions. The mutation regression proves the incompatible
  key error reports `ROLLBACK` and neither serial write is staged before the caller's transaction
  boundary.
- The selected-operation representation retains fragment definitions as ordinary GraphQL source
  after the selected operation; it no longer injects private marker/control characters into a
  document. Generated bindings pass their concrete parent type (`Query`, `Mutation`, model object,
  or connection wrapper) to common selection helpers. Those helpers structurally locate the
  retained definitions and use a bounded scalar range/ancestry stack—rather than a frontend
  expansion, mutable AST graph, or per-schema resolver—to expand named and inline fragments in
  source order. They evaluate spread and definition `@skip`/`@include`, enforce concrete type
  conditions, reject cycles/unknown fragments/duplicate referenced definitions, and cap fragment
  work (512 expansions, 512 expanded fields, 256 KiB stack text) before SQL. PostgreSQL and MySQL
  commerce-package tests prove a root named fragment, nested named fragment, inline fragment,
  definition and spread directives, aliases, wrong type-condition rejection, and a cycle rejection.
  This is a bounded selection expansion pass, not the final token-consumed AST, field-merging,
  interface/union, or full validation implementation.
- The generated engine now handles built-in `__typename` at the `Query` root, model objects,
  one-hop relation objects, and the supported Relay `Connection`, `Edge`, and `PageInfo` wrapper
  types, including a custom mutation's model-object payload. It rejects arguments and nested
  selections, preserves response aliases, and constructs the value inside PostgreSQL/MySQL
  routines; the commerce package tests assert every supported composite level on both dialects.
  Generated scalar fields also reject nested selections, and generated composite values reject
  empty selections. The generated PostgreSQL/MySQL public routines now also dispatch `__schema`
  and `__type` only when the authenticated top-level `introspectionEnabled` capability is exactly
  `true`; absent or malformed context fails closed inside the database. The first metadata slice
  serves `__Schema.queryType`, `mutationType`, `subscriptionType`, `types`, `description`, and
  `__typename`, plus `__Type.name`, `kind`, `description`, `ofType`, and `__typename` for Query,
  Mutation when present, reviewed object types, and scalar types actually referenced by the model.
  It resolves `__type(name: $variable)` from the materialized variable carrier and returns null for
  an unknown type. Focused installed commerce-package proofs cover disabled access, aliases, a
  `String!` variable, object/scalar identity, model-derived `__Schema.types`, and unknown-type
  null on both dialects. `__Type.fields` now renders reviewed model scalar/relation fields and
  executable Query/Mutation root fields through one compact generated descriptor and a shared
  transpilable walker: `__Field.name`, description, deprecation fields, `__typename`, and
  scalar/object type references, including bounded `NON_NULL -> SCALAR|OBJECT` and Relay
  `NON_NULL -> LIST -> NON_NULL -> OBJECT` `ofType` chains. Its standard optional
  `includeDeprecated` argument is accepted for `__Type.fields`, validated as a materialized
  nullable `Boolean`/`Boolean!` variable or Boolean literal in the database, and currently does
  not alter the result because generated field metadata has no deprecated members.
  Query descriptors are derived from the same support predicate as generated dispatch, so they
  do not advertise an unsupported connection; point roots stay nullable while executable
  connections and mutations are non-null. A first fully unrolled version exceeded Java's 64 KiB
  public-method limit; the descriptor interpreter keeps the public routine bounded and remains
  entirely database-side. The superseded scalar-only renderer has been removed rather than
  retained as a dormant runtime path. Generated connection, edge, and `PageInfo` objects now use
  the same descriptor path and are included in `__schema.types` and `__type`; a connection's
  `edges` field renders its real bounded Relay wrapper chain. Transpilation reports 255 PostgreSQL
  and 256 MySQL entry points, including the public routine (the source-local output inventories
  contain 254 and 255 entries respectively). This is inventory, not a project routine-count
  ceiling: database operators must size MySQL's `stored_program_cache` for their deployment
  workload.
  Focused installed tests cover nullable `String`, non-null `Int`, a model `MANY` relation's
  `NON_NULL -> LIST -> NON_NULL -> OBJECT` shape, point-root, connection-root, mutation-root,
  connection/edge/PageInfo lookup, and the nested list wrapper on both dialects. This is
  deliberately not complete introspection: actual interface/union type metadata and non-empty
  possible-type lists, custom directive metadata beyond the executable standard `include`/`skip`
  pair, enum input locations beyond the later generated-filter/custom-mutation slices plus
  input-default metadata, and complete model-derived field/input deprecation/default/description
  metadata remain Phase 2 work; no request is routed to the JVM to fill them. Declared output enums
  and their later value-description/deprecation metadata are covered by evidence below. The standard introspection
  schema itself is now included in `__schema.types` and addressable through `__type`: its six object
  types and two enums use the same descriptor interpreter, structural type-reference renderer, and
  input-value renderer as generated model metadata. This includes the real list/non-null wrapper
  chains and the `includeDeprecated: Boolean! = false` defaults.
- Generated read projections now carry an explicit model-derived SQL `IS NULL` marker beside every
  model field. That marker flows through the transpiled JDBC lowering into JSON assembly, so a
  nullable `Int`, `Boolean`, decimal, or text value cannot be silently rendered as the primitive
  getter default (`0` or `false`), and a corrupt value cannot silently satisfy a reviewed non-null
  contract. The unrelated commerce model and installed PostgreSQL/MySQL packages prove present and
  absent nullable integer/Boolean values in both point reads and a first-page connection row.
  They also alter the physical non-null `Customer.name` column to `NULL` after deployment: a
  nullable point root becomes `null`, a separately aliased root remains present, and the
  database-produced response contains the aliased `[root, field]` error path. This is the first
  execution-time non-null propagation slice. The later one-hop relation/list and connection-node
  traversal cases are recorded in the latest evidence below; supported connection-node chains now
  bubble through the non-null Relay wrappers, while mutations and general multi-level completion
  remain Phase 2 work.
- Generated point-root and custom-mutation bindings now use the selected operation's variable
  definitions inside the installed routine. They accept only a declared non-null scalar variable
  of the model field's scalar type, take an omitted JSON value from a GraphQL variable default,
  and reject missing required values, undeclared `$` references, or declared scalar mismatches
  before SQL execution. PostgreSQL and MySQL commerce-package tests cover all four outcomes,
  including `Int!`/`String!` and `Boolean!` named-variable custom mutations. Boolean values are
  parsed in the shared transpiled engine, bound with JDBC `setBoolean`, and rendered as JSON
  booleans rather than strings. This is a bounded scalar proof, not full GraphQL variable
  coercion.
- The reusable database binding now supports finite `Float`/`Decimal` inputs and output using a
  transpilable numeric scanner, JDBC `setDouble`/`getDouble`, and JSON numeric assembly. It accepts
  exponent-form values such as `1.0e308`, rejects an out-of-range `1.8e308` as a GraphQL coercion
  error before any write, and is safe even when a MySQL generated branch continues local coercion
  after setting an error response. The underlying Titan mapper has a local focused correction:
  Java `float`/`double` are represented by a new floating TIR type and emit `DOUBLE PRECISION` on
  PostgreSQL and `DOUBLE` on MySQL rather than fixed `NUMERIC(38,10)`. Titan's full transpiler
  suite and Docker-tagged emitter deployability test, plus the PostgreSQL/MySQL direct commerce
  package tests, pass. The Titan checkout also has unrelated
  uncommitted work, so this correction must be committed and pinned through Titan's normal process
  before a release claim.
- Before any generated schema branch runs, the shared engine now validates the selected operation's
  variable-definition grammar, rejects duplicate declarations, and scans executable operation and
  retained-fragment selections for undefined `$name` references while skipping GraphQL strings and
  comments. This is deliberately schema-neutral validation; descriptor-based type compatibility
  remains generated per argument. PostgreSQL/MySQL commerce tests cover an undefined root-argument
  variable, duplicate declaration, and an undefined directive variable. Unknown supplied JSON
  keys and unused variables are rejected at this boundary; descriptor-backed list/input-object
  shape validation is now also performed there. A generator-owned schema descriptor additionally
  drives a shared structural pass over every selected field before directive evaluation. That pass
  rejects unknown root and child fields, scalar/composite selection-shape mismatches, unknown or
  duplicate arguments, and missing required arguments even when a field, ancestor, or fragment
  would later be omitted by `@skip`/`@include`. An independent typed-AST pass validates every
  reachable field, named-spread, and inline-fragment directive before any ancestor inclusion
  result is applied, so invalid directives cannot hide below a disabled branch. Canonical
  argument-value materialization, complete variable-location compatibility, nullable input-field
  semantics, and the remaining schema validation rules remain Phase 2 work.
- Generated point-root and custom-mutation bindings now count the complete field argument list
  before coercing their reviewed scalar bindings. An extra, duplicate, or malformed argument is
  rejected in the installed database package instead of being silently ignored. PostgreSQL and
  MySQL tests cover read and mutation cases. Input-object validation, nullable/defaulted argument
  semantics, and general schema validation remain outside this narrow scalar path.
- The common engine now evaluates built-in `@skip(if:)` and `@include(if:)` on enabled root,
  payload, scalar, and one-hop-relation fields. Conditions are Boolean literals or declarations
  structurally valid at the required `Boolean!` input location, including a nullable `Boolean`
  variable with its own non-null default. An explicitly supplied JSON `null` still fails inside
  the database routine. Skipped fields are removed from both execution and JSON
  assembly. It rejects malformed or unsupported field directives in the database rather than
  silently applying frontend behavior. Because the standard directives are not declared for the
  operation-definition location, any operation-level directive is rejected from the selected AST
  before variable materialization or any mutation/root JDBC work. It rejects a
  `subscription` selected operation rather than executing it through the query path; subscriptions
  need a separate durable event transport and are out of scope. Dual-dialect commerce tests cover
  field/root omission, Boolean defaults and request variables, and those fail-closed cases.
- The Phase 1 entry contract now has nine inputs: the original document/envelopes and mutation
  transport flag, plus a versioned trusted context, expected model semantic hash, expected
  source-runtime identity, and expected final SQL package-inventory identity. The generator embeds
  the first two identities and checks the installed package identity table before parsing or
  application-data access. Direct package tests pass the exact values, and Commerce tests prove
  stale identities are rejected on PostgreSQL and MySQL before a mutation can execute. Final
  custom-mutation registry attestation and pooled replacement remain later migration gates.
- Before lexical parsing or scalar JSON walking, the common entry gate now limits the query and
  each variables/extensions/trusted-context envelope to 16,384 UTF-16 code units, below MySQL's
  generated `TEXT` procedure-parameter limit even for non-ASCII input. The direct commerce
  packages prove that an oversized variables envelope is rejected inside PostgreSQL and MySQL.
  The standalone frontend also applies a bounded JDBC statement timeout to its single
  whole-request call, reduced when an authenticated request deadline is shorter. The transpiled
  engine now validates an optional non-negative absolute `deadlineEpochMillis` and rejects an
  already-expired request against the database clock before generated schema work. It rejects a
  timestamp beyond `9999-12-31T23:59:59.999Z` rather than allowing a dialect timestamp conversion
  to throw. The generated PostgreSQL/MySQL routines recheck the trusted deadline before beginning
  every query or mutation root, so an elapsed request cannot advance to a later root (including a
  later serial mutation). It rechecks after root validation and before every currently generated
  JDBC statement: point and relation reads, connection page/boundary/count reads, and mutation
  lock/existence/update statements. It also rechecks while materializing each connection-page row
  and each nested relation result row (including a singular relation result), and before every
  per-child relation selection-rendering walk, so a valid but large fetched page cannot assemble
  unchecked past the trusted deadline. The MySQL procedure guards the post-check body, preventing
  a stored terminal response from proceeding to nested SQL or further row decoding. A deadline in
  a serial mutation has an explicit rollback outcome,
  including after an earlier mutation root has run. Fresh direct commerce packages prove both
  dialects install and execute the updated routines; focused source-generation coverage proves
  the pre-statement and materialization-loop checkpoint shapes. This is not interruption of a
  currently executing SQL statement or a complete request-cost model: frontend statement
  cancellation, database plan-cost limits, and broader static loop/response-cost budgeting remain
  Phase 2 work.
- Completed query JSON and the framed transport outcome now pass through the same 16,384-code-unit
  database boundary before the public function/procedure returns. This limits assembly memory and
  remains safely inside the current MySQL `TEXT` response contract. PostgreSQL/MySQL commerce
  tests widen the fixture column, read a 16,384-character value through the installed package, and
  prove the routine returns a database-engine response-budget error rather than an oversized
  payload. Statement-time and plan-cost budgets still remain Phase 2 work.
- The proof now rejects a quoted literal for a model `Int` argument and a bare name/JSON `null`
  for a model `String` argument, on both dialects. This prevents the prior accidental coercions;
  it is only scalar input hardening, not full variable-definition or input-object coercion.
- Reviewed `UUID` point and mutation inputs now have an explicit transpiled canonical-text check
  before their JDBC binding. This prevents PostgreSQL's native UUID cast from becoming the
  validation authority and makes malformed UUIDs return the same GraphQL error on PostgreSQL and
  MySQL; the commerce package tests cover a malformed literal on both dialects.
- Reviewed `String` bindings now distinguish a non-string token from the legal empty GraphQL
  string. An empty key reaches the generated lookup and returns its normal null result rather than
  an accidental type error; PostgreSQL/MySQL commerce package tests cover this behavior.
- The shared preflight now applies generated scalar, enum, list, and input-object descriptors
  independently of the eventual field. Boolean, Int, Float/Decimal, String, ID, Long, and UUID
  values fail before a directive or root binding can reinterpret them; nested objects and list
  items follow the same bounded iterative path. This moves 32-bit Int range and malformed
  filter-variable errors to the operation boundary with the same cross-dialect contract.
  Field-use compatibility and canonical value materialization remain Phase 2 work.
- Operation defaults use that same pre-dispatch descriptor path, so an invalid scalar, enum,
  list, or input-object default cannot become an argument-specific database binding error.
- GraphQL triple-quoted block-string literals now retain their full token range through default
  scanning and are decoded inside the transpiled scalar path: CR/LF forms normalize to LF, common
  indentation after the first line is removed, leading/trailing blank lines are trimmed, and an
  escaped triple quote is preserved without being mistaken for a closing delimiter. The PostgreSQL
  and MySQL commerce packages prove a non-null `String` variable default selects a live row and an
  escaped-triple-quote literal remains one valid database-scanned token; no HTTP/JVM string decoder
  participates.
- Titan initially delegated an ordinary `if` containing JDBC handles to the generic Java lowerer,
  producing a `PreparedStatement` mapping failure. The local Titan worktree now recursively
  lowers those branches and has a focused `JdbcLoweringTest` regression. This must be landed and
  pinned through Titan's normal review before a release claim.
- Titan also failed to bind a prior local from a local `@SQL` annotation, blocking MySQL's final
  procedure result set. The local source-scope fix has a `SqlAnnotationProcessorTest` regression.
  This is likewise pending normal Titan review/pinning.
- Titan's routine-reference remapper also skipped calls nested below logical negation, which made a
  generated `roleIn` policy install as invalid Java-signature text. The local remapper now walks
  negation and membership expressions, with a `TranspilationPipelineUnresolvedHelperTest`
  regression. It too requires normal Titan review/pinning.
- Titan's MySQL emitter previously generated an empty `ELSEIF` body for a legal no-op Java branch,
  which MySQL rejected at package install. It now emits dialect-valid no-ops (`DO 0;` on MySQL and
  `NULL;` on PostgreSQL), covered by `TranspilationPipelineRawSqlTest`. Its dynamic single-row-read
  emitter also reused connection-scoped `@titan_rN` variables across unrelated projections; MySQL
  retained a numeric type and rejected a later text value such as `Netherlands`. Result staging is
  now statement-scoped (`@titan_r<statement>_<column>`), with a `MySqlEmitterTest` regression and
  the seven-test generated commerce MySQL package suite passing. Both fixes require the normal
  Titan review/commit/pin before a release claim.
- The commerce model now declares an explicit `mutations.renameCustomer` model binding. The
  database generator validates its target type, scalar/key/assignment column bindings, payload
  fields, and reject policy, then emits parameterized `UPDATE` dispatch. It is intentionally not a
  JVM resolver or CDI handler. Query and mutation root dispatch both consume the bounded scalar
  selection plan; mutation roots are walked in document order, so aliases of the same or different
  registered mutation execute serially. The response is paired with a dedicated `COMMIT` or
  `ROLLBACK` transport instruction. PostgreSQL frames the text result and MySQL returns a dedicated
  result-set column; GraphQL JSON contains no `titanTransactionOutcome` extension. The
  procedure/function does not issue transaction control; the connection owner applies that outcome.
- `databaseEngineCommerceIntegrationTest` and
  `databaseEngineCommerceMySqlIntegrationTest` now each invoke two aliased mutation roots in one
  request. The first update is observable within the caller transaction; the second names a missing
  target and returns `ROLLBACK`; caller rollback restores the first row. A succeeding request returns
  `COMMIT`, and caller commit persists it. Both packages are regenerated, scratch-installed, and
  executed on their respective dialects. The focused Titan regression
  `JdbcLoweringTest.ordinaryLoopContainingJdbcUpdateLowersThroughJdbcPath` covers the ordinary
  request loop that contains a prepared update.
- Before that serial write pass, generated mutation bindings now make a separate no-SQL,
  database-resident pass over every selected mutation root. It checks supported root dispatch,
  policy, payload selection, argument shape, scalar variable declarations, and scalar coercion for
  the complete selected mutation operation. PostgreSQL/MySQL commerce tests place a valid update
  before an invalid later root and prove the error has a `ROLLBACK` outcome while the earlier row is
  unchanged. This prevents document-level validation failures from staging partial writes; it is
  still a scalar source-location bridge rather than the final typed AST validator.
- A separate `DatabaseGraphqlWholeRequestRuntime` now proves the intended thin JDBC boundary
  against the generated commerce package on PostgreSQL and MySQL. It binds exactly the complete
  document, selected-operation name, variables JSON, extensions JSON, serialized trusted context,
  transport `allowMutations` flag, and expected model semantic hash to one public database call;
  it performs no GraphQL parsing, validation, planning, resolver invocation, or response assembly.
  It owns a connection-local transaction, committing only the engine's explicit `COMMIT` outcome
  and otherwise rolling back. Tests prove query forwarding, database-side GET-style mutation
  rejection, an error outcome that rolls back a prior staged mutation, and a successful outcome
  that commits on both dialects. `GraphqlRuntimeRequest` and the HTTP resource now carry the
  transport flag explicitly. This is a Phase 5 feasibility adapter only: it is not yet selectable
  by the production execution engine, its package identity is still only the semantic-hash proof,
  and the legacy HTTP GET parser/modes remain until the complete serving migration.

Open blockers and intentional exclusions:

- The procedure/result-set contract now proves MySQL dynamic JDBC reads and the narrow mutation
  outcome contract without a JVM fallback. The standalone thin HTTP distribution invokes both
  PostgreSQL and MySQL packages from an independent process.
- The proof covers two unrelated models, model-generated point roots, scalar/computed fields, and
  bounded model-declared relation traversal without relation arguments or pagination. A relation
  currently needs a modeled source scalar or reviewed private join carrier for its local join
  column; deeper traversal beyond its selection-hop budget, relation arguments, and pagination
  fail closed. The mutation proof currently supports only reviewed direct-argument
  scalar `update` bindings and payloads sourced from those arguments. It locks a target row and
  performs a definitive existence check before the serial write pass, but does not yet provide
  mutation input objects/defaults/null coercion, a DML row-count concurrency contract, durable
  idempotency/audit/outbox records, or arbitrary custom procedure bodies. The selected-operation AST now retains typed topology, operation kind,
  variable declarations/default ranges, executable references, directives, fragments, field
  identities, and selection boundaries; generated execution uses only that AST after
  materialization. Its scalar selection carrier is not yet a complete schema-aware execution plan.
  Apart from the constrained Relay increment, including compatible repeated generated connection
  roots described above, and bounded concrete-object fragment expansion, it does not yet support
  general connection-root semantics, composite or non-integral/non-String/non-UUID tuple cursors, client
  ordering beyond the reviewed local scalar/integral-tie slice, relation
  connections, schema-aware nested fragment field merging, interface/union conditions, custom directives, or full
  schema validation,
  list/input-object/nullable variable coercion, null propagation,
  introspection, subscriptions, package identity, or the serving HTTP adapter. Every one remains
  unchecked above.
- Direct installation and the semantic test are evidence of feasibility only. The production
  HTTP adapter and all old runtime modes remain unchanged.

Gate: a direct SQL client sends whole documents against both models on both dialects and receives
correct completed responses. This must work without running any JVM GraphQL parser or executor.

### Phase 2 — complete the shared language engine

- [x] Implement reusable lexing/parsing, source locations, selected operations, fragments and cycle
      checks, directives, field merging, aliases, and full-operation validation before execution.
      `DatabaseGraphqlLanguage` owns bounded lexing, selected-operation kind/indexing, the
      token-derived reachable-fragment document closure, and a bounded scalar syntax index.
      `DatabaseGraphqlAst` materializes the selected operation from that index and generated
      execution uses its typed nodes for traversal, directives, field identity, and bounded
      merge-range comparison. Relation Relay selections reuse the complete generated selection and
      argument validator in a validation-only pass before their parent query; the dual-dialect
      absent-parent regression proves an invalid nested node field is not skipped. A compact
      generator-owned schema descriptor now drives one shared structural validation walk before
      directive execution; disabled root, child, and fragment selections are checked for field
      membership, selection shape, argument names/duplicates, and required arguments. A separate
      AST-wide validation scan checks every reachable field/spread directive independent of
      ancestor inclusion. A second shared descriptor-driven walk validates every supplied field
      argument against its generated type, including scalar/enum/list/input-object coercion and
      variable-location compatibility at nested positions before directive inclusion. Compatible
      fields are collected independent of document order and conflicts are checked per possible
      runtime object before execution.
- [x] Generate scalar/input/type descriptors and implement schema-aware variables/defaults with
      correct missing/null distinctions, numeric range checks, and unknown/unused input validation.
      The current descriptor covers reviewed scalars, `SortDirection`, generated filter/order
      input objects, their nested list wrappers, and mutation/root scalar inputs. Schema-wide
      field-use compatibility is now validated from the same `sd1`/`id1` metadata before dispatch.
      One bounded `ma1` carrier now owns materialized arguments for every selected field, while its
      nested `cv1` values preserve explicit null versus omitted variable-backed input-object fields;
      generated roots, reads, and mutations consume that carrier rather than re-reading raw JSON or
      reparsing argument source. Authored input-object fields and generated point, root-connection,
      relation, flat-mutation, and mutation-input arguments now carry validated omission-sensitive
      GraphQL constant defaults through the same descriptor and carrier path. New unsupported schema
      shapes must still be added explicitly rather than falling back to an HTTP/JVM coercer.
- [x] Implement bounded plans, error codes/paths/locations, JSON escaping, and non-null propagation.
- [x] Generate SDL and execute introspection from the same metadata, including mutation input/output
      types and trusted introspection policy. The capability-gated transpiled `__schema`/`__type`
      path now self-describes the standard introspection schema as well as current generated model,
      wrapper, input, scalar, and enum types. Declared output enums now share that metadata and
      execution path, including value descriptions, deprecations, and `includeDeprecated` filtering.
      Model-declared interface/union relationships, abstract point/Relay-root return types, concrete
      runtime-condition selection, and possible-type/wrapper introspection are now proven for a
      reviewed single physical projection. Generated argument and authored input-field defaults are
      rendered from the same metadata used by execution. Every metadata detail authorable in
      v1alpha1 is classified; heterogeneous roots and metadata absent from that model version require
      a versioned schema extension rather than a frontend/JVM substitute.
- [x] Add adversarial depth/token/fragment/variable/response-size tests; enforce both semantic work
      budgets and database statement timeouts. Query/envelope, lexical, AST, fragment, typed-input,
      relation-row, response, and deadline guards have focused coverage. Known typed-list fan-out is
      now reserved before queue expansion and proven through the shared dual-dialect corpus. Complete
      Request-wide typed-input and introspection-item reservations now have dual-dialect corpus
      proof. Generated statements, decoded application rows, deadlines, and incremental/final
      response assembly are charged request-wide; every known fan-out is charged before allocation,
      statement execution, cursor traversal, or response expansion.

2026-09-22 — abstract output, overlap, and concrete-position fragment proof (partial M2):

- Added authored interface and union model records, YAML/canonical-JSON/public-schema support,
  object `interfaces`, and point-root `outputType`. Build validation rejects namespace collisions,
  unknown/duplicate union members, missing or type-incompatible interface fields, a physical root
  object outside the declared abstract type, and abstract connection outputs. The concrete
  `root.type` remains the static database binding; no resolver or query-specific dispatch was added.
- Generated schema metadata and database-resident introspection now expose `INTERFACE`/`UNION`,
  authored type descriptions, object interfaces, possible types, and abstract Query field wrappers.
  Static request validation checks fragment-condition overlap and validates abstract selections once
  against the declared type and once per possible concrete object. Response-key conflicts are
  evaluated per runtime object, permitting aliases reused only by mutually exclusive branches.
- Execution carries each concrete object's complete object/interface/union condition set through
  point, nested-relation, and Relay-node planners. Applicable abstract fragments execute and sibling
  concrete branches under an abstract field are skipped; `__typename` remains concrete. A first
  live corpus attempt intentionally retained an impossible `Customer` fragment below a concrete
  `Order` relation and was correctly rejected, after which the acceptance case was corrected to
  distinguish static overlap from runtime branch selection.
- `./gradlew test --tests '*DatabaseGraphqlSelectionPlanTest' --tests
  '*DatabaseGraphqlInputDescriptorTest' --tests
  '*TitanGraphqlDatabaseEngineSourceGeneratorTest' --no-daemon --console=plain` passed 42 focused
  tests after the generalized concrete-position planner change. `./gradlew
  titanGraphqlTranspileCommerceDatabaseEngine titanGraphqlTranspileCommerceMySqlDatabaseEngine
  --no-daemon --console=plain` passed in 5m58s: each dialect reported 358 source-local helpers and
  355 emitted entry points; MySQL reported 359 whole-request routines.
- The 18-case fixed corpus then passed through freshly staged, scratch-install-verified, identity-
  bound packages using `CommerceDatabaseGraphqlEngineIT.
  installedEntryPointMatchesPortableExpectedResultCorpus` (PostgreSQL, 54s) and
  `CommerceDatabaseGraphqlMySqlEngineIT.
  installedProcedureMatchesPortableExpectedResultCorpus` (MySQL, 3m59s). It proves interface and
  union point roots, interface fragments at concrete relation and Relay-node positions, concrete
  `__typename`, mutually exclusive alias reuse, overlapping-branch conflict rejection, and an exact
  source-located impossible-condition error. This closes the current abstract point-output slice,
  not M2 or heterogeneous/abstract connection execution.

Gate: language corpus executes directly through transpiled routines on both dialects; independent
expected responses catch errors even when a JVM reference and SQL implementation would agree.

### Phase 3 — migrate generic reads and authorization

- [x] Generate static lookup/execution code for integer, string, UUID, and composite point keys.
- [x] Migrate nullable scalar/temporal/numeric output and reviewed computed expressions. Commerce
      and demo packages prove the complete reviewed v1alpha1 output inventory, including nullable
      values and row-local computed selection/filter/order slices.
- [x] Migrate collection limits, stable forward/backward cursors, ordering, filters, and exact
      counts, including bounded composed filters and every declared computed/one-hop order path.
- [x] Migrate modeled relation-hop filters/order and all currently accepted composed-filter cases.
- [x] Execute point/collection relations and relation connections entirely within the database.
- [x] Batch nested collections without per-parent queries; bound scanned/materialized child rows.
      Eligible unpaginated root-collection `MANY` relations now use constant 64-key chunks, a
      SQL-partitioned 101st-row sentinel, the shared statement ledger, and the shared 1,000-row
      allowance. A 65-parent fixture passes in two chunks on both dialects, while a 1,001-row
      aggregate fixture still fails. Authenticated opt-in metrics prove the request is exactly
      three application statements and 131 row-ledger units on both dialects. Direct to-one now
      uses the same bound with duplicate-safe parent slots. Relay, argument-bearing, deeper-level,
      and independent-alias plans are partitioned by the AST-backed plan identity.
- [x] Compile policy evaluation into the engine and enforce it through the full plan, including
      derived values, filtering, ordering, counts, aliases, batching, and introspection.
- [x] Verify snapshot consistency, changed live rows, deterministic fresh-process behavior,
      malformed cursors, missing context, and cross-tenant isolation.
- [x] Rename roots, fields, tables, and schema names in a generated test model to detect accidental
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

Current serving audit (2026-09-15): `GraphqlExecutionEngine` now defaults to the package-bound
`database` mode, while retaining `java`, `jdbc`, `compiled`, and historical `sql` migration paths
as explicit temporary reference choices. Standard serving wiring rejects each legacy selection
unless the test/reference-only `titan.graphql.allow-legacy-execution-modes=true` setting is
present, and performs that check before the historical GET parser can inspect a document. Database
mode requires an exact reviewed-model/package
binding, a runtime-identity sidecar, an explicit `postgresql` or `mysql` dialect, and a manifest entry point published by a
generated `io.titan.graphql.database.generated.*.executeGraphqlRequest` binding. It resolves the
manifest's schema/routine identity rather than treating a configured or carrier routine as an
engine entry point. The generated entry point compares the descriptor/package runtime identity with
its embedded identity on every call before parsing or data access. `GraphqlHttpResource` forwards GET documents unchanged in `database` mode and
sets only `allowMutations=false`; its legacy GET parser remains isolated behind the explicit
reference-only setting. The PostgreSQL
and MySQL commerce package gates bind the generated engine package and cover the JAX-RS resource,
one GET query, and one POST mutation on each dialect. This makes the default fail closed at the
database boundary, but it is not final cleanup: the legacy routes and incomplete final package
identity must still be deleted/replaced after feature parity is proved.
`databaseEngineHttpIntegrationTest` additionally boots the existing Quarkus HTTP transport in
`database` mode against a fresh PostgreSQL database containing only the generated package.
`databaseHttpFrontendIntegrationTest` boots a separate JDK HTTP host from a source-set classpath
that excludes application `main` output, forwards POST and GET envelopes to that same kind of
generated package, and is paired with a JAR/classpath boundary check. That isolated host is the
target serving seam. It now has a generated deployment descriptor and a runnable distribution;
the same test extracts that distribution and launches it as a separate process against both
PostgreSQL and MySQL. The transitional Quarkus application still cannot be removed until feature
parity and the remaining Phase 5/6 gates are complete.

The deployable artifact is now explicitly the standalone
`titan-graphql-*-database-http-frontend.zip`, not the root Quarkus application archive.
`titanGraphqlVerifyDatabaseHttpFrontendReleaseArtifact` checks the ZIP's launch target, complete
reviewed runtime closure, and frontend-only Titan classes. `titanGraphqlDatabaseEngineReleaseCheck`
is the local database-serving release suite and intentionally excludes Quarkus, compiled-schema,
and historical SQL reference tasks: those remain useful migration oracles, but cannot approve a
shipping artifact.

- [x] Introduce a fail-closed package-bound database mode with explicit PostgreSQL/MySQL selection.
- [x] Resolve the public whole-request routine from the verified package inventory and reject
      non-engine or wrong-dialect packages before datasource use.
- [x] Prove the JAX-RS resource invokes the installed PostgreSQL and MySQL generated packages.
- [x] Move the database-mode GET operation check into the database; legacy-mode requests are
      default-denied before the historical parser, which remains only for explicit reference tests.

- [ ] Replace runtime-mode selection with one database invocation adapter and dialect-specific JDBC
      bindings. Preserve transport negotiation and authenticated context extraction.
- [~] Move GET operation checks into the database and delete frontend GraphQL parsing. The database
      now rejects GET mutations, and standard serving reaches no JVM parser; deletion of the
      reference-only parser remains a Phase 6 cleanup item.
- [x] Implement outcome-driven transactions and error mapping without GraphQL response inspection.
      The frontend validates a fixed PostgreSQL frame or MySQL outcome column, then returns the
      untouched GraphQL JSON. Unit evidence deliberately conflicts the legacy extension with the
      dedicated outcome and proves the dedicated value alone controls commit.
- [~] Bind model, shared engine source/version, generated bindings, custom mutation source/registry,
      compiler/submodule versions, routine inventory, and relevant build options into package identity.
      The current v1 runtime identity binds the semantic model, dialect, shared engine source,
      generator source, build configuration, dependency lock, and pinned Titan version. It does
      not yet directly attest the final routine inventory or a general custom-mutation registry.
- [~] Verify identity inside each call, including replacement packages and pooled connections.
      The generated public entry point compares the supplied runtime identity with its embedded
      identity before request parsing, and PostgreSQL/MySQL commerce tests prove a stale identity
      cannot execute a mutation. Add a pooled-connection replacement test after the final package
      identity is available.
- [x] Add deployed HTTP tests whose serving source-set classpath physically lacks application
      GraphQL runtime classes, and assert the corresponding frontend JAR contains only frontend
      classes.
- [x] Make the standalone HTTP ZIP the explicit release artifact and reject unreviewed library
      closure, launch target, or local GraphQL classes before it may pass the local release gate.
- [x] Assert complete request forwarding and one execution invocation per envelope, including
      multi-root queries and errors. `DatabaseWholeRequestClientTest` proves one nine-input
      JDBC call with untouched envelope values; the isolated PostgreSQL/MySQL frontend process
      now forwards a variable-backed two-root operation and an unknown-root error without local
      GraphQL interpretation. Transaction protocol calls are allowed; per-field calls are not.

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

Make `scripts/release-check.sh --full` run the implemented local required suite. The names below
are executable Gradle tasks; replace them only when a successor supplies at least the same evidence.

- [x] Make the full local release gate approve the standalone database HTTP ZIP only. It runs
      `titanGraphqlDatabaseEngineReleaseCheck`, which regenerates, transpiles, package-verifies,
      and directly invokes the demo and commerce whole-request engines on PostgreSQL/MySQL, then
      starts the isolated ZIP on both dialects. Transitional Quarkus, compiled-schema, and legacy
      SQL suites remain available as migration oracles but cannot approve the shipping artifact.

```bash
./gradlew test
./gradlew titanGraphqlVerifyDatabaseEngineBoundary titanGraphqlVerifyDatabaseFrontendBoundary titanGraphqlVerifyDatabaseHttpFrontendBoundary
./gradlew databaseEngineIntegrationTest databaseEngineMySqlIntegrationTest databaseEngineCommerceIntegrationTest databaseEngineCommerceMySqlIntegrationTest
./gradlew titanGraphqlVerifyDatabaseHttpFrontendReleaseArtifact databaseHttpFrontendIntegrationTest
./gradlew titanGraphqlDatabaseEngineReleaseCheck
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
| 0 Boundary/inventory | In progress | Uncommitted | `docs/database-engine-runtime-inventory.md`; `./gradlew titanGraphqlVerifyDatabaseFrontendBoundary titanGraphqlVerifyDatabaseHttpFrontendBoundary databaseHttpFrontendIntegrationTest` builds the descriptor-backed standalone HTTP distribution, rejects JVM GraphQL and `main`-output leakage, then extracts and runs it against both PostgreSQL and MySQL. `database-engine-corpus/commerce-v1.json` is a fixed, runtime-independent expected-result corpus exercised on both databases. | Expand the corpus with the remaining language, read, mutation, and adversarial cases. |
| 1 Database feasibility | In progress | Uncommitted | `./gradlew databaseEngineIntegrationTest databaseEngineMySqlIntegrationTest databaseEngineCommerceIntegrationTest databaseEngineCommerceMySqlIntegrationTest`: independently generated demo and commerce packages are transpiled, packaged, install-verified, model-bound, and JAX-RS-served on PostgreSQL/MySQL. Fresh direct commerce-package evidence is 8 PostgreSQL tests and 9 MySQL tests; both include an expired trusted deadline rejected by the installed routine before schema work. Focused package/bind/install/execution proofs additionally cover `Customer.orders.customer.name` from both a point root and a Relay connection node within its declared two-hop budget, merge repeated compatible nested connection-node selections, merge the same point-root path through named and inline fragments, stop a corrupt non-null leaf at a nullable to-one boundary, and reject a third hop on both dialects. The fixed, runtime-independent expected-result corpus remains part of each suite. The current MySQL public routine is a 1.076 MB `CREATE PROCEDURE`; Titan's shared MySQL test container now sets `max_allowed_packet=16M` so package deployment proves database behavior rather than failing in the JDBC packet transport. Production packaging must retain an explicit package-size/packet preflight rather than rely on an obsolete 1 MiB assumption. | AST/plan and general connection semantics remain. |
| 2 Language engine | Complete for v1alpha1 (2026-09-23) | Uncommitted | The bounded Titan-transpiled lexical/typed-AST core owns operation selection, fragment closure, built-in and model-registered conditional directives, schema-wide structural and argument validation, variable/default materialization, canonical `ma1`/`cv1` arguments, field collection, duplicate-response-key checks, coded null/error completion, introspection, and request/deadline bounds for the supported schema surface. Generated metadata drives Query/Mutation, Relay wrappers, scalars, model-declared enums, generated/authored input objects, model-declared interface/union point and static-projection Relay outputs, registered-directive behavior/location/introspection, and general point/root-connection/relation/flat-mutation/authored-mutation-input argument defaults without an HTTP/JVM semantic path. Every database GraphQL error carries a source-selected stable code; lexical failures retain locations and execution failures retain paths. The complete current request ledger bounds typed-input fan-out, introspection expansion, application statements, decoded rows, deadlines, and every shared JSON append plus the final response. Fresh packages pass the 34-case corpus on both dialects and all 636 JVM tests pass. Current topology is 386 source-local helpers and 383 emitted entry points per dialect and 387 MySQL whole-request routines; these are observed inventories, not project limits. The v1alpha1 JSON schema proves one physical root projection and rejects undeclared root members. | Preserve the language core while M3/M4 extend binding capabilities. A heterogeneous root is a future versioned feature and must first define cross-source discriminator, ordering, cursor, policy, count, and batching semantics. |
| 3 Reads/policies | Complete for v1alpha1 (2026-09-23) | Uncommitted | PostgreSQL/MySQL packages prove the complete reviewed scalar/computed output and root inventory; typed point keys; point/list/Relay reads; stable forward/backward cursors and tuple ordering; bounded DNF local/computed/one-hop filters; computed/one-hop orders; exact counts/page flags; AST-identity-partitioned relation traversal; fixed 64-parent batching; request-reject policies; bounded statements/rows; repeatable-read request transactions; changed live rows; malformed cursors and context; fail-closed cross-tenant isolation; a physically renamed generator model; and the independent 34-case corpus. The final topology is 473 reachable helpers, 470 entry points per dialect, and 474 MySQL routines. Each dialect transpile action is below the one-minute ceiling. All 654 project tests and focused Titan lowering regressions pass. | None for M3. Preserve this gate while M4 adds custom mutations; broader legacy deletion remains M6 work. |
| 4 Mutations/auxiliary routes | In progress | Uncommitted | Direct scalar/enum `update` mutation bindings are generated, policy-checked, prevalidated before any serial write, and lock their target row before updating. A mutation may now expose one required authored input object and map reviewed dotted leaf paths to database columns. The installed Commerce proof executes a nested JSON variable and an inline object whose omitted nested name receives its schema default entirely inside PostgreSQL/MySQL routines, then verifies caller rollback. Existing focused transaction tests prove serial execution, pre-execution rejection, staged-effect inspection, atomic rollback after a later-root failure, caller commit, and missing-target behavior. MySQL applies the procedure outcome and `COM_RESET_CONNECTION` only after each request transaction ends. | Nullable/write-null bindings, arbitrary procedure bindings, durable audit/idempotency/outbox, retained auxiliary routes, mutation-registry attestation, and the remaining concurrency contract remain. |
| 5 Frontend/artifacts | In progress | Uncommitted | The bound `database` mode resolves the generated nine-input manifest entry point, forwards the complete request once, and has JAX-RS route evidence for both models and dialects. `DatabaseGraphqlWholeRequestRuntimeTest` asserts exactly one manifest-selected JDBC call and all nine untouched envelope/transport/identity bindings. Reproducible runtime- and package-identity sidecars bind the model, dialect, transpilable engine/generator sources, build config, dependency lock, pinned Titan version, and staged SQL inventory; the public routine checks them before application-data access. PostgreSQL/MySQL Commerce tests prove a stale identity prevents a mutation. The transport validates an explicit PostgreSQL fixed frame or MySQL outcome column without examining GraphQL JSON. Serving configuration now default-denies Java/JDBC/compiled/SQL modes before the historical GET parser; only explicit test/reference wiring permits them. `databaseEngineHttpIntegrationTest` boots Quarkus in database mode against only the generated PostgreSQL package. `databaseHttpFrontendIntegrationTest` extracts the descriptor-backed distribution and runs it as an independent process against both PostgreSQL and MySQL; it proves nullable `variables`/`extensions` envelope members are forwarded as absent input rather than locally coerced, while duplicate JSON members are rejected before an HTTP JSON tree can collapse a variable value. `titanGraphqlVerifyDatabaseHttpFrontendBoundary` verifies its runtime closure. `titanGraphqlVerifyDatabaseHttpFrontendReleaseArtifact` also checks the ZIP's exact reviewed library closure, launcher, and frontend-only class inventory; `titanGraphqlDatabaseEngineReleaseCheck` is the standalone serving release gate. | Legacy-code deletion; custom-mutation registry identity; pooled replacement test. |
| 6 Architecture removal | In progress | Uncommitted | Standard serving now denies every legacy execution mode before the historical GET parser; the remaining legacy classes are documented as deletion targets. | Delete the legacy engines, modes, parser, and runtime dependencies after database feature parity. |
| 7 Release | In progress | Uncommitted | `./gradlew titanGraphqlDatabaseEngineReleaseCheck --no-daemon` passed after the token-derived selected-document, fragment-closure, operation-kind, root/field-boundary, and bounded scalar selection-index migration, regenerating, package-verifying, and directly invoking the demo and commerce packages: 2 PostgreSQL demo tests, 2 MySQL demo tests, 7 PostgreSQL commerce tests (including the fixed expected-result corpus), 7 MySQL commerce tests, and 3 isolated standalone-frontend tests all passed. The same gate previously passed after the point-root merge and deadline increment. The MySQL fixture paths now select `public` before the package runtime migration, placing its unqualified helpers beside the generated routines. `./gradlew test --no-daemon` also passed before this corpus/fixture increment. The release task's former package-directory/descriptor input overlap and Gradle-10 task-project deprecation were corrected. | Clean-checkout, independent history scan, all language/read/mutation coverage, architecture deletion, and final commit/push remain. |

Latest focused metadata evidence (2026-09-16):
`./gradlew databaseEngineCommerceIntegrationTest databaseEngineCommerceMySqlIntegrationTest --tests
'*CommerceDatabaseGraphqlEngineIT.installedEntryPointServesTrustedDatabaseResidentIntrospectionIdentity'
--tests '*CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureServesTrustedDatabaseResidentIntrospectionIdentity'
--no-daemon --console=plain` passed after fresh transpilation, scratch installation, and package binding:
After scalar AST-plan reuse, PostgreSQL is 9/9 in 768 seconds (the focused introspection request
itself: 737 seconds) and MySQL is 1/1 in 320 seconds. The combined Gradle invocation completed in
19 minutes 1 second, down from 27 minutes 8 seconds for the pre-reuse equivalent proof.
The installed routines prove the
descriptor-rendered Query/Mutation fields, generated connection/edge/PageInfo type lookup and
fields, a model `MANY` relation, and the `NON_NULL -> LIST -> NON_NULL -> OBJECT` wrapper—alongside
aliases, variable lookup, fail-closed nested selection validation, and database-side materialization
and Boolean validation of `__Type.fields(includeDeprecated:)` (including rejection of a non-Boolean
literal). It now also proves descriptor-rendered `__Field.args`/`__InputValue` metadata for required
point and mutation arguments, optional Relay/filter/order inputs, input-object and list wrappers,
and null default/deprecation metadata. The plan reuse is a measured improvement, but the duration
is still correctness evidence rather than acceptable final performance evidence: Phase 2 now has a
field-count semantic gate and must extend it into a cost model while optimizing broad metadata
response assembly.
The superseded unreferenced generator renderer was removed;
the shared descriptor walker is the only emitted object-field path. The later execution-error/path,
universal-null-marker, one-hop relation-element propagation, and execution-location increments
bring separate transpilation to 274 PostgreSQL and 275 MySQL source-local helpers (276 MySQL
whole-request routines). It is a Phase 2 increment, not complete input/argument or arbitrary-wrapper
metadata.

Latest generated-input metadata evidence (2026-09-16): separate fresh package/bind proofs now run
each generated input type as its own complete GraphQL request, rather than adding it to the already
expensive broad identity request. `CommerceDatabaseGraphqlEngineIT.
installedEntryPointServesGeneratedInputTypeIntrospectionInBoundedRequests` passed against the
installed PostgreSQL package in 27 seconds; its MySQL counterpart passed against the installed MySQL
package in 55 seconds. They prove `__type` resolution and `__Type.inputFields(includeDeprecated: $var)`
with a materialized `Boolean!` variable for `CustomerFilter`, plus `__Type.inputFields` for
`StringFilter`, and `CustomerOrderBy`, including the `[String!]` wrapper and `SortDirection` enum
reference, plus `__type(name: "SortDirection")`. The subsequent fresh installed-package rerun also
proves `__type(name: "SortDirection") { enumValues(includeDeprecated: $var) { name description
isDeprecated deprecationReason __typename } }`, including database-side Boolean-variable validation
and the generated `ASC`/`DESC` values, on PostgreSQL and MySQL. It also passed with a database-side
144-field request-global semantic-work gate: the healthy broad identity document has 120 AST field nodes, while the former
159-field pathological shape is rejected before schema dispatch. The gate is a conservative
field-count bound, not yet a complete cost model or response-assembly optimization.

Superseded operation-directive evidence (2026-09-16): fresh installed commerce packages passed
`installedEntryPointEvaluatesBuiltInFieldDirectivesInsideTheDatabase` on PostgreSQL in 25 seconds
and `installedProcedureEvaluatesBuiltInFieldDirectivesInsideTheDatabase` on MySQL in 52 seconds.
Those tests had treated a `Boolean!` variable on an operation-level `@include` as executable and
allowed it to omit the complete root set. That behavior contradicted the same package's accurate
directive introspection: standard `include`/`skip` are valid at `FIELD`, `FRAGMENT_SPREAD`, and
`INLINE_FRAGMENT`, not at an operation definition. This result is retained as evidence of the
superseded behavior, not as a passing language requirement.

Latest operation-location correction evidence (2026-09-17): selected-operation preflight now
rejects the first operation-definition directive at its retained AST source location before
variable materialization or generated query/mutation dispatch. The PostgreSQL/MySQL entry-point
generators no longer emit the former whole-operation inclusion/empty-data branch, and the now
unreachable runtime helper was removed. Focused database-core/generator tests passed in 18s. Fresh
Commerce generation, Titan transpilation, scratch installation, package binding, and public-routine
execution then passed in 7m35s. PostgreSQL ran all 21 Commerce methods with zero failures in
152.563s; its directive method took 3.365s. MySQL's selected directive method passed in 43.598s.
Both tests reject operation-level `@include`/`@skip`, report the directive column, and prove the
rejected mutation leaves its row unchanged, while standard field/root omission continues to pass.
The smaller closure contains 291 source-local helpers and 290 entry points per dialect, 292 MySQL
whole-request routines, and 317 PostgreSQL/331 MySQL package objects.

```sh
./gradlew test \
  --tests io.titan.graphql.database.DatabaseGraphqlInputDescriptorTest \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest databaseEngineCommerceMySqlIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointEvaluatesBuiltInFieldDirectivesInsideTheDatabase \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureEvaluatesBuiltInFieldDirectivesInsideTheDatabase \
  --no-daemon --console=plain
```

Latest definition-directive and source-location correction evidence (2026-09-17): the same
preflight now rejects a standard directive on a named fragment definition as well as on an
operation definition. This removes the remaining executable behavior at locations omitted from
the engine's accurate `__Schema.directives` metadata. The first PostgreSQL probe returned the
correct rejection but exposed reconstructed line 2/column 37 coordinates, while the MySQL
single-operation fast path returned original line 1/column 85 coordinates. The selected-document
builder now masks unselected definitions with spaces while preserving every original CR/LF and
retains reachable definition ranges in place; the MySQL direct-source shortcut applies only when
the document contains one operation and no fragment definitions. Focused language/AST/input/
generator tests prove that retained operation, fragment, and directive offsets match the complete
document and passed in 17s.

Fresh generated PostgreSQL and MySQL packages then transpiled 294 reachable source-local helpers
and 293 entry points per dialect. PostgreSQL scratch installation and the selected installed
runtime test passed in 2m20s (JUnit 6.408s). MySQL reported 295 whole-request routines, passed
scratch installation, and passed the selected installed runtime test in 1m29s (JUnit 44.797s).
Both dialects reject the definition directive at original line 1/column 85. Their package
inventories contain 320 PostgreSQL and 334 MySQL objects.

Five preceding attempts failed before relevant SQL because Docker's random published-port
allocator selected host ports simultaneously used as ephemeral client ports (37455, 37465, 37475,
37493, and 37503). Inspection showed sustained PostgreSQL connection churn and matching
`TIME_WAIT` sockets across that range. Titan's shared JUnit containers and package-install scratch
containers now retain random mappings by default but accept explicit, validated loopback host-port
environment overrides. The authoritative retries used free ports above the host ephemeral range;
no unrelated container or system network setting was changed.

```sh
./gradlew test \
  --tests io.titan.graphql.database.DatabaseGraphqlLanguageTest \
  --tests io.titan.graphql.database.DatabaseGraphqlAstTest \
  --tests io.titan.graphql.database.DatabaseGraphqlInputDescriptorTest \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  --no-daemon --console=plain
TITAN_SCRATCH_POSTGRES_HOST_PORT=62000 TITAN_TEST_POSTGRES_HOST_PORT=62001 \
  TESTCONTAINERS_RYUK_DISABLED=true ./gradlew databaseEngineCommerceIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointEvaluatesBuiltInFieldDirectivesInsideTheDatabase \
  --no-daemon --console=plain
TITAN_SCRATCH_MYSQL_HOST_PORT=62002 TITAN_TEST_MYSQL_HOST_PORT=62003 \
  TESTCONTAINERS_RYUK_DISABLED=true ./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureEvaluatesBuiltInFieldDirectivesInsideTheDatabase \
  --no-daemon --console=plain
```

This closes only definition-location enforcement and cross-dialect preservation of source
coordinates. M2 remains partial; the next dependency is schema-wide validation of every selected
field use, including selections whose runtime directive result would omit them.

Latest schema-wide structural field-use evidence (2026-09-17): the generator now serializes the
reviewed Query, Mutation, model, Relay wrapper, and introspection field surface into a compact
length-prefixed `sd1` descriptor. Before variable materialization, directive evaluation, schema
dispatch, or JDBC work, one shared Titan-transpilable engine routine walks the selected operation
and reachable fragments against that descriptor. It validates field membership, scalar versus
composite selection shape, argument names and uniqueness, and required-argument presence. The
walk deliberately does not evaluate `@skip` or `@include`, so an unknown root, unknown child,
unknown field in a fragment, scalar sub-selection, missing required argument, or unknown argument
cannot evade validation because its own or an ancestor's runtime condition is false. Errors retain
the original document line and column. The generator reuses its introspection field descriptors
rather than introducing handwritten runtime query branches.

The initial PostgreSQL run proved the new checks but exposed five expected-message differences and
pushed the existing 120-field broad introspection request over its unchanged statement deadline.
Consolidating each field's AST lookup into one scan and skipping argument parsing for fields whose
source header has no arguments removed repeated work. A no-directive introspection-only operation
now delegates to the existing complete generated introspection validator instead of walking the
same broad metadata tree twice; this does not skip a disabled branch because such a branch requires
a directive. No timeout was increased, request split, JVM validator, or model-specific execution
fallback was introduced.

The focused input-descriptor and generator suites passed 21 tests with zero failures. Fresh
PostgreSQL generation, transpilation, scratch installation, binding, and all 21 Commerce runtime
methods then passed in 4m36s (JUnit 151.331s), including the formerly timing-out broad request.
The equivalent full MySQL gate passed all 23 methods in 17m27s (JUnit 894.830s); live stack samples
during the quiet run confirmed forward progress through the intentional 64-call durability test
and repeated per-test package deployment. Both dialects emit 306 reachable source-local helpers
and 305 entry points. MySQL reports 307 whole-request routines, and the installed package
inventories contain 332 PostgreSQL and 346 MySQL objects. These remain observed inventories, not
project limits.

```sh
./gradlew test \
  --tests io.titan.graphql.database.DatabaseGraphqlInputDescriptorTest \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  --no-daemon --console=plain
TITAN_SCRATCH_POSTGRES_HOST_PORT=62000 TITAN_TEST_POSTGRES_HOST_PORT=62001 \
  TESTCONTAINERS_RYUK_DISABLED=true ./gradlew \
  databaseEngineCommerceIntegrationTest --no-daemon --console=plain
TITAN_SCRATCH_MYSQL_HOST_PORT=62002 TITAN_TEST_MYSQL_HOST_PORT=62003 \
  TESTCONTAINERS_RYUK_DISABLED=true ./gradlew \
  databaseEngineCommerceMySqlIntegrationTest --no-daemon --console=plain
```

This closes structural schema field-use validation for the currently installed schema, including
directive-disabled selections. M2 remains partial: argument values still require canonical
schema-aware coercion and variable-location validation in every field use; malformed directives
below a disabled ancestor must be validated independently of execution; merge/type-condition,
complete error/path/null, interface/union/custom-directive metadata, and unified cost requirements
also remain open.

Latest disabled-ancestor directive-validation evidence (2026-09-17): selected-operation preflight
now scans every retained field, named-spread, and inline-fragment AST node before execution planning
applies any inclusion result. It validates built-in directive identity, non-repeatability, the exact
`if` argument, Boolean literal or variable-location compatibility, and supplied/default value even
when an ancestor is omitted. An unsupported child directive, malformed named-spread directive, or
duplicate inline-fragment directive therefore fails at its own original source location instead of
being hidden by `@skip(if: true)`. The scan operates on the selected operation's reachable fragment
closure and performs no schema or application-data query.

The focused input-descriptor and generator suites passed 22 tests in 17s. Fresh current-worktree
PostgreSQL generation, transpilation, scratch installation, binding, and the directive-heavy
installed-package method passed in 2m09s (JUnit 6.562s). The equivalent MySQL proof passed in
3m26s (JUnit 52.490s). Both dialects emit 312 reachable source-local helpers and 311 entry points;
MySQL reports 313 whole-request routines. Current package inventories contain 338 PostgreSQL and
352 MySQL objects. The immediately preceding package revision's complete 21-method PostgreSQL and
23-method MySQL suites remain the broad regression baseline; this increment's current package was
proven with the focused affected method on each dialect, not mislabeled as another full-suite run.

```sh
./gradlew test \
  --tests io.titan.graphql.database.DatabaseGraphqlInputDescriptorTest \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  --no-daemon --console=plain
TITAN_SCRATCH_POSTGRES_HOST_PORT=62000 TITAN_TEST_POSTGRES_HOST_PORT=62001 \
  TESTCONTAINERS_RYUK_DISABLED=true ./gradlew databaseEngineCommerceIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointEvaluatesBuiltInFieldDirectivesInsideTheDatabase \
  --no-daemon --console=plain
TITAN_SCRATCH_MYSQL_HOST_PORT=62002 TITAN_TEST_MYSQL_HOST_PORT=62003 \
  TESTCONTAINERS_RYUK_DISABLED=true ./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureEvaluatesBuiltInFieldDirectivesInsideTheDatabase \
  --no-daemon --console=plain
```

This closes the disabled-ancestor directive-validation gap for the executable built-ins. M2 remains
partial: arbitrary registered custom directives are not yet part of the model metadata, field
argument values still need canonical schema-wide coercion/materialization, and merge/type-condition,
complete error/path/null, interface/union metadata, and unified cost requirements remain open.

Latest schema-wide argument-value evidence (2026-09-17): after structural field-use validation
and one-time variable materialization, a shared Titan-transpilable walk now applies the generated
`sd1` field/argument descriptor and `id1` input descriptor to every supplied argument in the
selected operation. The bounded iterative coercer validates scalar, enum, list, and input-object
values, GraphQL wrapper compatibility, location defaults, and variables nested inside list or
input-object literals. It runs before directive inclusion and database dispatch, so an invalid
literal or incompatible nested variable below a disabled ancestor fails at the argument value's
original source location. The generated PostgreSQL and MySQL entry paths both invoke this generic
pass after producing the request-local variable carrier and before any query or mutation binding.

The first full PostgreSQL probe produced four expected-authority changes from older generated
binding diagnostics and also pushed the broad introspection request over its unchanged 30-second
statement deadline because directive preflight scanned the whole AST even when the document had no
directive. The implementation retained the deadline and added a safe no-`@` fast path; stale test
messages were updated only after confirming the generic descriptor pass was the source of each
failure. The final current-package PostgreSQL suite passed all 21 methods with zero failures in
3m00s (JUnit 153.680s); its broad introspection method completed in 30.801s, which remains a narrow
M5 performance margin. The equivalent current-package MySQL suite then passed all 23 methods with
zero failures in 17m53s (JUnit 922.201s). Focused database-core/type/generator tests passed 27 tests
with zero failures. Both dialects emit 320 reachable source-local helpers and 319 entry points;
MySQL reports 321 whole-request routines. The installed package inventories contain 346 PostgreSQL
and 360 MySQL objects. These are observed inventories, not project limits.

```sh
./gradlew test \
  --tests io.titan.graphql.database.DatabaseGraphqlInputDescriptorTest \
  --tests io.titan.graphql.database.DatabaseGraphqlTypeReferenceTest \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  --no-daemon --console=plain
TITAN_SCRATCH_POSTGRES_HOST_PORT=62000 TITAN_TEST_POSTGRES_HOST_PORT=62001 \
  TESTCONTAINERS_RYUK_DISABLED=true ./gradlew \
  databaseEngineCommerceIntegrationTest --no-daemon --console=plain
TITAN_SCRATCH_MYSQL_HOST_PORT=62002 TITAN_TEST_MYSQL_HOST_PORT=62003 \
  TESTCONTAINERS_RYUK_DISABLED=true ./gradlew \
  databaseEngineCommerceMySqlIntegrationTest --no-daemon --console=plain
```

This closes generic schema-wide argument validation and variable-location compatibility for the
currently generated schema. It does not close canonical argument materialization: generated
bindings still read argument source slices and directly resolve only a top-level variable. An
enabled input-object literal containing a nested variable can therefore validate correctly while
the schema-specific binding still receives unresolved `$name` text. The next dependency-ordered
M2 increment is one shared, fully resolved operation-input carrier with specification-correct
omitted/null/default behavior, consumed by every generated binding; merge/type-condition,
error/path/null, metadata, and unified cost work remain after it.

Latest result-materialization deadline evidence (2026-09-16): focused generator coverage now
asserts a trusted-deadline checkpoint immediately inside generated connection-page, nested
relation-result, and per-child relation-selection rendering loops for PostgreSQL and MySQL, plus
MySQL's terminal-response guard after the checkpoint. Fresh package/bind/install proofs then ran
the PostgreSQL installedEntryPointServesUnrelatedSchemaKeysPoliciesAndLiveRows scenario (42
seconds) and its MySQL installedProcedureServesUnrelatedSchemaKeysPoliciesAndLiveRows counterpart
(1 minute 34 seconds). Those installed entry points exercise paged connections and a nested
Customer.orders result while the new transpiled loop bodies are present. This is structural and
end-to-end artifact evidence for deadline checking between materialized rows; it does not claim
cancellation of an already-running SQL statement or a complete database cost model.

Latest point-root non-null evidence (2026-09-16): the generated projection now retains an
explicit null marker for every reviewed field, including GraphQL non-null scalars. Fresh installed
commerce packages ran installedEntryPointPropagatesDatabaseNullForNonNullPointLeaf on PostgreSQL
(2 minutes 4 seconds) and installedProcedurePropagatesDatabaseNullForNonNullPointLeaf on MySQL
(3 minutes 42 seconds). Each test deliberately relaxed the physical Customer.name constraint and
stored NULL, then proved database-resident completion returns data.brokenCustomer as null,
preserves an independent healthyCountry root, and emits the aliased path
[brokenCustomer, name]. The subsequent fresh package/bind/install proofs ran
installedEntryPointBubblesNonNullRelationElementToNullablePointRoot on PostgreSQL (2 minutes
4 seconds) and installedProcedureBubblesNonNullRelationElementToNullablePointRoot on MySQL
(4 minutes). They relaxed Order.reference and stored NULL, then proved that a
non-null Customer.orders list element bubbles through the non-null list and relation to the
nullable point root, preserves healthyCountry, and emits
[brokenCustomer, orders, 0, reference]. The execution errors also retain the source `locations`
array: the location is the selected `reference` field, rather than a transport-generated
position. This is partial-data/error assembly for a non-null scalar failure under a nullable
point root and its first non-null one-hop relation/list case only;
broader nullable wrappers, deeper nesting, connections, mutations, and root-non-null bubbling
remain Phase 2 work.

Latest bounded two-hop relation evidence (2026-09-16): relation capabilities now preserve their
declared `selectionHopBudget` from YAML through the normalized model and projection adapter. The
generated executor carries the active minimum budget through nested relation validation and output,
and projects a typed private local-key carrier when a relation's physical join column is not a
public GraphQL field. A to-one relation is emitted as a model-bounded cursor rather than a JDBC
single-row transfer, so its complete nested GraphQL executor remains in the transpiled body. Titan
now lowers a lexical constant-SQL inner cursor as a nested cursor block, with a focused lowerer
regression. Fresh PostgreSQL (2 minutes 10 seconds) and MySQL (3 minutes 53 seconds) package,
scratch-install, bind, and public-entry-point tests proved
`{ customer(id: 7) { orders { customer { name } } } }` returns `Northwind` and a third relation
hop fails with `exceeds selection hop budget of 2`. The artifacts contain 274 PostgreSQL and 275
MySQL source-local helpers; MySQL's whole-request inventory is 276. This is a bounded execution
  increment only: general schema-aware nested merge trees, relation arguments/pagination,
  nullable-relation and deeper non-null propagation, batching, and general relation cost controls
  remain Phase 2 work.

Latest nullable relation-boundary evidence (2026-09-16): generated relation completion now owns a
separate local propagation flag for each selected relation. A failing non-null child leaf first
nulls that child object; the enclosing relation converts it to `null` and only bubbles into its
parent when the model declares the relation non-null (all current `MANY` elements are non-null).
The source generator also reports and propagates an absent non-null to-one target rather than
silently producing an invalid value. Fresh PostgreSQL (2 minutes 16 seconds) and MySQL (4 minutes
10 seconds) package, scratch-install, bind, and public-entry-point tests relaxed
`commerce.customers.name`, then proved
`customer.orders[0].nullableCustomer` becomes null while `customer.id`, `orders[0].id`, and an
independent root remain present. Both return the database-created error path
`[customer, orders, 0, nullableCustomer, name]` and the selected leaf source location. This is a
two-hop nullable-to-one boundary proof, not yet general nullable list, connection, mutation, or
arbitrary-depth completion parity.

Latest connection-node relation evidence (2026-09-16): Relay page SQL now projects every reviewed
private relation local key, and the generated page-row decoder consumes those values before it
invokes the same bounded static relation executor used by point objects. Fresh installed-package
proofs ran `customerFeed(first: 1) { edges { node { customerId: id purchases: orders { customer {
id name } } } } } }` through the public PostgreSQL entry point and public MySQL procedure; both
returned the aliased scalar and nested customer values with no JVM resolver call. The PostgreSQL
package/install/bind/execution command completed in about four minutes after regeneration; the
MySQL command re-transpiled the expanded procedure and completed in about six minutes. This proves
ordinary bounded relation traversal from a connection node, not relation pagination/batching.
Connection-node selection now uses the same request-order plan as point objects, preserving
aliases. Its database-resident typed-ancestry frontier follows every compatible `Query`-to-node
field occurrence and merges their child plans before static relation reads begin. The same
iterative AST helper unwraps named- and inline-fragment relation ancestry on the reviewed point
path; fresh PostgreSQL/MySQL installed-package proofs cover those forms too. Fresh focused
PostgreSQL/MySQL installed-package proofs now require
`orders { customer { name } } orders { customer { id } }` to return one nested customer containing
both `name` and `id`, rather than a truncated response or a JVM-completed merge. Incompatible
response-key/name/argument/directive combinations still fail closed while general schema-aware
merge validation is unfinished. A separate fresh PostgreSQL/MySQL installed-package proof makes
`Customer.name` physically null and executes `customerFeed { edges { node { name } } }`: its
database-created error path is `[customerFeed, edges, 0, node, name]`, and the non-null `node`,
edge item, `edges`, and connection-root wrappers correctly produce `data: null`. This completion
slice applies to the currently supported bounded connection-node selections; general merge trees,
relation pagination/batching, and broader completion parity remain unfinished.

Latest integrated evidence (2026-09-16): after request-local variable materialization was extended
to generated directive/selection traversal, `./gradlew titanGraphqlDatabaseEngineReleaseCheck
--no-daemon` passed. It rebuilt and boundary-verified the standalone HTTP distribution, transpiled,
packaged, and scratch-installed the demo and commerce routines on PostgreSQL and MySQL, invoked the
direct suites, and ran the extracted standalone HTTP artifact against both dialects. This is an
integration checkpoint only; it does not close the remaining language, read, mutation, legacy
removal, or public-release gates.

Latest transport-stabilization evidence (2026-09-17): MySQL whole-request transport now starts an
explicit request transaction and, after its database-provided commit/rollback outcome, invokes
Connector/J's `resetServerState` before the connection can return to a pool. This is cleanup of
stored-program session state, not another GraphQL call. Because the reset itself rolls back any
active transaction, the MySQL serving contract is exactly one whole-request procedure call, apply
its database-provided transaction outcome, and only then reset before reuse. The direct MySQL
harness mirrors that boundary for normally managed pooled connections. Its caller-managed proof
inspects staged effects after the call, chooses commit or rollback, resets, and begins the next
request transaction; it does not claim that multiple public engine calls are supported inside one
MySQL transaction. Serial root-mutation semantics remain inside one complete GraphQL request and
therefore one procedure call. A cursor-bearing forward/backward sequence that formerly yielded `{data:{}}`
now passes through the thin adapter. The MySQL public routine also validates its locally decoded
fixed transport frame and completed JSON character budget after dynamic result materialization,
rather than skipping that bound to avoid an unsafe helper call. Focused source tests passed, then:

```sh
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests 'io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.thinAdapterForwardsTheWholeEnvelopeAndOwnsTheDatabaseTransaction' \
  --tests 'io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureServesUnrelatedSchemaKeysPoliciesAndLiveRows' \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests 'io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.thinAdapterForwardsTheWholeEnvelopeAndOwnsTheDatabaseTransaction' \
  --tests 'io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointServesUnrelatedSchemaKeysPoliciesAndLiveRows' \
  --no-daemon --console=plain
./gradlew databaseHttpFrontendIntegrationTest \
  titanGraphqlVerifyDatabaseHttpFrontendReleaseArtifact --no-daemon --console=plain
```

passed against fresh installed PostgreSQL/MySQL packages. The last command launches the isolated
distribution against both dialects and verifies its exact frontend-only runtime closure. Its MySQL
fixture and Titan's generic scratch installer now set `max_allowed_packet=16M`, allowing the
approximately 1.1 MiB generated procedure to be installed and tested. This proves session-state
cleanup only; the final pooled package-replacement test remains a Phase 5 requirement. It does not
close language parity, generic relation batching, custom-mutation durability, legacy-source
deletion, or release gates.

Latest frontend-context and package-install evidence (2026-09-17): the standalone frontend now
accepts gateway-defined policy values only through one
`X-Titan-Context-Values: {"name": <JSON scalar>, ...}` header. It validates the object, identifier
keys, scalar-only values, and duplicate header occurrence, then forwards the values only under the
generic `contextValues` member. This replaces an unsafe per-value-header prefix whose model key
could be lowercased by HTTP infrastructure; the isolated PostgreSQL/MySQL frontend-distribution
gate proves the commerce `articleVisibility` policy value remains database-owned and returns the
expected live row. Titan's generic install verifier now reads `@@max_allowed_packet` before it
executes any MySQL package SQL. It calculates the largest UTF-8 SQL statement from the actual
package install plan and requires that size plus a 64 KiB protocol reserve; an insufficient or
unreadable setting emits an actionable failed verification report without beginning package SQL.
The pure calculation regression and scratch installer passed, followed by
`./gradlew titanGraphqlVerifyCommerceMySqlDatabaseEngineInstall --no-daemon --console=plain`, whose
fresh `titan-install-verification.json` reports `passed` for the generated commerce package. The
16 MiB deployment recommendation remains conservative headroom, while the verifier supplies an
artifact-specific fail-closed minimum.

The database whole-request adapter now serializes only the generic `contextValues` map and has a
regression proving it does not infer the legacy demo `articleVisibility` field. The transitional
Quarkus route normalizes that compatibility header into the generic map before it can reach a
database runtime; the standalone release artifact has no model-specific context header handling.
The Quarkus route and legacy context shape remain Phase 6 deletion work, not a second production
contract.

Latest package-privacy and compiler-throughput evidence (2026-09-17): Titan now canonicalizes
compiler source locations before it renders either a `-- titan:source:` SQL comment or a
null-guard exception. A path under the build root becomes a relative artifact path; any other
absolute Unix or Windows path becomes only its filename. The new
`titanGraphqlVerifyDatabaseEnginePackagePrivacy` gate regenerates and scans all four whole-request
proof packages (demo and commerce on PostgreSQL and MySQL), rejecting workstation locations in SQL
or package metadata; the release gate depends on it. It passed with zero path leaks, and fresh
commerce package-install verification reports `passed` for both dialects. The validator also now
indexes each method's compiler tree paths once instead of repeatedly searching the complete
compilation unit for every type/element lookup. `FeatureValidatorTest` passed, followed by a fresh
two-dialect commerce transpile/package run that completed in two minutes, changed zero SQL files,
and remained path-clean. These release and throughput improvements do not constitute final routine
inventory/custom-mutation attestation or parity evidence.

Latest package-inventory attestation evidence (2026-09-17): each whole-request pipeline now stages
the transpiled SQL, hashes every SQL file's normalized relative path and contents, then adds a
self-excluded `R__titan_005_graphql_package_identity.sql` migration. The identity therefore binds
the final routine/helper inventory and any generated custom-mutation SQL without a circular hash;
the migration stores it under the fixed public whole-request entry-point key. The package binding
copies the resulting `titan-graphql-database-package-identity.sha256` sidecar alongside the older
source-runtime identity. Fresh commerce PostgreSQL and MySQL package/bind runs each produced two
package artifacts, scratch-installed successfully, and wrote different dialect-specific inventory
identities. This is durable package inventory evidence, but it is not yet the final Phase 5
contract: the generated public routines and thin frontend must pass and compare that database-held
identity on every request, followed by a same-pooled-connection package-replacement proof.

Current local baseline: `./gradlew test --no-daemon --console=plain` passed all 583 Docker-free
tests after the checked-in demo package fixture was rebound to the current normalized model hash.
The reference-model adapter now also preserves a field's reviewed filter-operator subset (rather
than silently widening it to the scalar default); `TypedPointKeyTest` proves the commerce model's
`nodeId` field exposes only `eq`/`in` and rejects `neq` before execution. This keeps the legacy
reference oracle aligned during migration; it does not authorize retaining it in production.

For each compiler gap record: smallest failing source, pinned compiler commit, generated diagnostic,
affected dialect, required behavior, chosen fix, regression test, and resolved dependency commit.
For each API break record: old entry point/configuration, replacement, migration action, and test.

The final review must answer: can a SQL client submit a previously unseen supported GraphQL document
with variables and trusted context, and receive its complete correct response on both dialects while
the HTTP distribution contains no Java GraphQL processing implementation? If not, the work is unfinished.

Latest bounded-runtime and parity evidence (2026-09-17): request-local introspection plans are now
reused at every repeated descriptor boundary. In particular, `__schema.types` compiles its
`__Type` selection once for the whole generated type list; object-field and bounded Relay-wrapper
renderers compile one selection per object/wrapper level instead of one per recognised member. The
typed AST additionally supplies a parent-marker direct-field plan for selections without spreads,
so each nested selection seeks only its own direct items rather than reinterpreting the complete
request AST. The engine uses that fast path while retaining the existing fragment-aware walker
whenever an inline or named spread is present; focused plan tests cover the root-selection marker,
directives, and the fallback. This is shared,
schema-independent transpiled runtime work, not a model-specific query shortcut.

The direct installed-package contracts now set the same 30-second JDBC statement limit as the
standalone HTTP adapter. Fresh PostgreSQL and MySQL packages passed the broad, trusted
database-resident `__schema`/`__type` identity request under that limit. The measured test-suite
durations (35 seconds PostgreSQL and 39 seconds MySQL) include container startup, schema creation,
and package installation; the bounded public routine call itself completed before the 30-second
limit. The same fresh packages also passed the transport-neutral commerce expected-result corpus
on both dialects:

```sh
./gradlew databaseEngineCommerceIntegrationTest \
  --tests 'io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointServesTrustedDatabaseResidentIntrospectionIdentity' \
  --tests 'io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointMatchesPortableExpectedResultCorpus' \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests 'io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureServesTrustedDatabaseResidentIntrospectionIdentity' \
  --tests 'io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureMatchesPortableExpectedResultCorpus' \
  --no-daemon --console=plain
```

These are current performance and shared-corpus gates for the standalone path. They do not close
the remaining schema/language coverage, generic relation batching, durable custom-mutation work,
legacy source deletion, final package/mutation attestation, or release-hygiene gates.

Latest nested-relation row-budget evidence (2026-09-17): generated SQL for every currently
supported unpaginated `MANY` relation now carries a constant `LIMIT 101`. The first 100 rows are
the per-parent materialization allowance; the 101st is a sentinel. It is never decoded or passed
to another generated resolver: the routine records an execution error at the selected relation
path and location, then uses the existing GraphQL non-null completion path instead of silently
returning a truncated list. Fresh generated-and-installed commerce packages proved this with one
seeded order plus 100 injected rows for `Customer.orders` on both dialects:

```sh
./gradlew test --tests 'io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest' --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests 'io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointRejectsUnpaginatedRelationAboveDatabaseRowBudget' \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests 'io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureRejectsUnpaginatedRelationAboveDatabaseRowBudget' \
  --no-daemon --console=plain
```

The same shared counter now rejects the 1,001st decoded relation row in the request. Fresh
PostgreSQL and MySQL packages were given 100 children per aliased point root (below the per-parent
limit) and an 11-root document; the eleventh relation returned a path-bearing execution error
while the first ten retained their complete 100-row lists:

```sh
./gradlew databaseEngineCommerceIntegrationTest \
  --tests 'io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointRejectsAggregateNestedRelationWorkAboveDatabaseRowBudget' \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests 'io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureRejectsAggregateNestedRelationWorkAboveDatabaseRowBudget' \
  --no-daemon --console=plain
```

This is still not a complete cost model: a request can invoke multiple relation statements up to
the shared row allowance, and those reads are not yet batched or bounded by an aggregate statement
or database cost budget. Those requirements remain open in Phase 3.

Latest generated-routine topology evidence (2026-09-17): the generic relation Relay slice now
uses the same static connection executor as a root connection, with the relation's reviewed parent
join key as its first bound predicate. Fresh installed PostgreSQL and MySQL packages prove aliases,
`__typename`, exact `totalCount`, all page-info fields, opaque cursor continuation, a reviewed local
equality argument, and the same relation connection below a root connection node. The PostgreSQL
public entry remains the only whole-request function; schema introspection is a reachable,
source-local Titan helper routine, not JVM work. On MySQL that helper is deliberately pure because
dynamic JDBC remains procedure-only. The bounded typed ancestry also now scopes generated connection
locals by the complete static relation path, so a cyclic schema can reuse a relation field name at
a later reviewed hop without redeclaring page/boundary/count locals. A three-hop cyclic-source
regression is parsed and attributed as Titan input on both dialect shapes. It deliberately does
not require javac to emit a JVM class: generated engine bindings are transpile-only sources, while
the shared database runtime remains normally compiled. This removes an irrelevant JVM 64 KiB
method-file ceiling before the real Titan lowering/package/install gates. It is not a claim that
arbitrary-depth relations or the remaining generic language/read requirements are complete. Fresh
default-model PostgreSQL and MySQL transpilations passed through that same boundary (283 entry
points each; MySQL inventory 285 including its public procedure), and fresh commerce packages
installed and executed the relation-Relay proof on both dialects.

```sh
./gradlew titanGraphqlTranspileDatabaseEngine titanGraphqlTranspileMySqlDatabaseEngine --no-daemon --console=plain
./gradlew test --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests 'io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointServesGenericRelationRelayConnectionInsideTheRoutine' \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests 'io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureServesGenericRelationRelayConnectionInsideTheRoutine' \
  --no-daemon --console=plain
```

Latest directive variable-use evidence (2026-09-17): built-in `@skip`/`@include` now use the
same structural variable-location compatibility rule as generated argument bindings. Thus a
nullable `Boolean` declaration with a non-null default is valid at the directive's required
`if: Boolean!` location, while an explicit JSON `null` is still rejected by the transpiled
routine. Focused language-core tests passed, followed by fresh PostgreSQL and MySQL transpilation,
scratch installation, package binding, and direct routine calls (289 source-local helpers per
dialect; MySQL whole-request inventory 290):

```sh
./gradlew test \
  --tests io.titan.graphql.database.DatabaseGraphqlInputDescriptorTest \
  --tests io.titan.graphql.database.DatabaseGraphqlTypeReferenceTest \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest databaseEngineCommerceMySqlIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointEvaluatesBuiltInFieldDirectivesInsideTheDatabase \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureEvaluatesBuiltInFieldDirectivesInsideTheDatabase \
  --no-daemon --console=plain
```

This closes that standard-directive variable-use exception only; complete schema-wide field-use
validation, canonical typed input materialization, and the other Phase 2 requirements remain open.

Latest relation-connection variable-use evidence (2026-09-17): the generated relation Relay
preflight now owns a static argument-name/type contract before it considers whether its parent
object exists. The shared typed-AST helper checks variables used at `first`, `after`, `last`,
`before`, and reviewed equality/filter/order arguments against the generated GraphQL type
reference; literals still proceed to the existing generated coercion/cursor checks. Consequently,
`query ($first: String) { customer(id: 999) { orderConnection(first: $first) { ... } } }`
returns the field-use error even though the point root has no row, rather than silently returning
`customer: null`. This is database-routine validation, not an HTTP/JVM preflight. Focused
language-core and source-generation tests passed, followed by one fresh PostgreSQL and MySQL
package generation, scratch install, package bind, and direct public-routine run (290 reachable
source-local helpers on each dialect; MySQL whole-request inventory 291):

```sh
./gradlew test \
  --tests io.titan.graphql.database.DatabaseGraphqlInputDescriptorTest \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest databaseEngineCommerceMySqlIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointServesGenericRelationRelayConnectionInsideTheRoutine \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureServesGenericRelationRelayConnectionInsideTheRoutine \
  --no-daemon --console=plain
```

This closes only the relation-connection optional-argument declaration check. General field-use
validation, literal/input coercion parity, selection validation independent of parent rows, and
the remaining Phase 2/3 requirements are still open.

Latest M0/M1 contract and deployable-boundary evidence (2026-09-17): the reconciled v1 public
contract has one generated `execute_graphql_request` entry point per dialect and exactly nine SQL
inputs in the PostgreSQL and MySQL manifests: query, operation name, variables, extensions, trusted
context, mutation permission, expected model hash, expected runtime identity, and expected package
identity. Focused mode/runtime/source-generation tests passed together with all three source-set
boundary checks and the exact standalone ZIP closure. A fresh isolated-process integration then
regenerated, scratch-installed, bound, and served both default dialect packages; the complete run
passed in 1m32s. The one-call client test and closure checks passed again through their owning
source-set task in 36s:

```sh
./gradlew test \
  --tests io.titan.graphql.GraphqlExecutionModeTest \
  --tests io.titan.graphql.database.DatabaseGraphqlWholeRequestRuntimeTest \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  titanGraphqlVerifyDatabaseEngineBoundary \
  titanGraphqlVerifyDatabaseFrontendBoundary \
  titanGraphqlVerifyDatabaseHttpFrontendBoundary \
  titanGraphqlVerifyDatabaseHttpFrontendReleaseArtifact \
  --no-daemon --console=plain
./gradlew databaseHttpFrontendIntegrationTest --no-daemon --console=plain
./gradlew databaseHttpFrontendIntegrationTest \
  --tests io.titan.graphql.frontend.DatabaseWholeRequestClientTest \
  titanGraphqlVerifyDatabaseEngineBoundary \
  titanGraphqlVerifyDatabaseFrontendBoundary \
  titanGraphqlVerifyDatabaseHttpFrontendBoundary \
  titanGraphqlVerifyDatabaseHttpFrontendReleaseArtifact \
  --no-daemon --console=plain
```

One intervening closure command incorrectly selected `DatabaseWholeRequestClientTest` through the
main `test` task and failed with `No tests found`; the test lives in the
`databaseHttpFrontendTest` source set. The corrected command above passed and is the authoritative
result. M1 is complete, but this is not a full-engine or release claim: M2 language semantics and
cost limits are now the earliest unresolved requirements, and all M3–M7 gates remain open.

Latest data-independent relation-selection evidence (2026-09-17): relation Relay fields now invoke
the same generated connection, edge, page-info, node, nested-relation, and argument validator in a
validation-only pass before the parent query. That pass returns before declaring result state or
opening a JDBC statement; the existing per-row invocation remains the only execution path. The
pagination temporaries were also made statically scoped so the preflight and execution passes do
not depend on coincidental lexical names. A new regression sends an invalid `Order` node field
under `customer(id: 999).orderConnection`; PostgreSQL and MySQL both reject it instead of returning
the absent parent and skipping validation. Source generation passed first, followed by fresh
transpilation, scratch installation, binding, and direct public-routine invocation on both
dialects. Each dialect retained 290 reachable source-local helpers (289 entry points; MySQL
whole-request inventory 291), and the installed-package run passed in 7m23s:

```sh
./gradlew test \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest databaseEngineCommerceMySqlIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointServesGenericRelationRelayConnectionInsideTheRoutine \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureServesGenericRelationRelayConnectionInsideTheRoutine \
  --no-daemon --console=plain
```

This closes only the data-dependent validation hole for the currently generated relation Relay
shape. M2 remains partial: schema-wide field validation, canonical input coercion, merge and type
condition rules, complete error/path/location behavior, complete introspection, and the unified
semantic/database cost model remain unresolved.

The same absent-parent case was then promoted into the transport-neutral fixed expected-result
corpus with an independently authored error message and exact line/column. The corpus now contains
eight unique cases. Both installed dialect packages returned the exact eight expected JSON values
in a fresh 3m45s run:

```sh
./gradlew databaseEngineCommerceIntegrationTest databaseEngineCommerceMySqlIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointMatchesPortableExpectedResultCorpus \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureMatchesPortableExpectedResultCorpus \
  --no-daemon --console=plain
```

This advances the shared corpus but does not satisfy its exit gate: the remaining language,
read/policy, mutation/transaction, authorization, adversarial-budget, and standalone-HTTP cases
still need independent expected results and dual-dialect execution.

Latest standard-directive introspection evidence (2026-09-17): the generated `__Schema` path now
accepts `directives` and renders exactly the executable database language core's standard
`include` and `skip` definitions. The transpiled helper validates the selected `__Directive` and
`__InputValue` shapes, preserves aliases, reports non-repeatability and the supported field/spread
locations, and describes the required `if: Boolean!` argument without consulting a JVM schema.
The first installed PostgreSQL probe exposed an incorrect long-form scalar-kind marker and failed
closed with `generated introspection input metadata is malformed`; changing the generated compact
descriptor from `SCALAR` to the established `S` encoding fixed that contract mismatch. The next
two PostgreSQL probes hit the database statement deadline at roughly 33 seconds, revealing that
the broader schema renderer still repeated response-key lookup, nested `__Type` selection
validation, and empty argument-list validation for every descriptor row. Those request-invariant
operations now run once outside the metadata loops; this is a shared database-engine optimization,
not a split request, timeout increase, model-specific branch, or frontend fallback.

Focused JVM tests passed after the refactor. Fresh PostgreSQL generation, transpilation, scratch
installation, binding, and the selected installed-package test then passed in 2m47s overall; its
JUnit XML records one test, zero failures, and 34.721s including deployment and the additional
negative calls. Fresh MySQL generation, transpilation, scratch installation, and binding passed;
the first execution command named the PostgreSQL-style test method and therefore failed with
`No tests found`. The corrected MySQL method passed in 1m28s overall, with one test, zero failures,
and 42.509s including deployment and its companion calls. Both dialects emit 291 reachable
source-local helpers and 290 entry points; MySQL reports 292 whole-request routines. Current
package inventories contain 317 PostgreSQL and 331 MySQL objects. These are observed inventories,
not limits.

```sh
./gradlew test \
  --tests io.titan.graphql.database.DatabaseGraphqlInputDescriptorTest \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointServesTrustedDatabaseResidentIntrospectionIdentity \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureServesTrustedDatabaseResidentIntrospectionIdentity \
  --no-daemon --console=plain
```

This closes only the metadata for the directives the engine actually executes. M2 remains partial:
interfaces/unions and possible types, custom directive metadata, introspection meta-types, complete
deprecation/default/description metadata, schema-wide validation/coercion/merge rules, complete
locations and paths, and the unified semantic/database cost model remain unresolved.

Latest generic named-type introspection evidence (2026-09-17): generated schema code no longer
unrolls a separate `__Type` validator/renderer for every model type. It passes only generated
descriptors to one shared Titan-transpiled `introspectionNamedTypeJson` routine; the superseded
unrolled generator method was deleted. The routine recognizes the standard named-type fields for
object, scalar, input-object, and enum values independent of runtime kind. It validates selections,
arguments, aliases, and fragment-expanded shapes before rendering `fields`, `inputFields`,
`interfaces`, `enumValues`, `possibleTypes`, `specifiedByURL`, `isOneOf`, and `__typename`, using
the standard null/empty result for fields that do not apply. Because the current model format has
no interface or union declarations, an object reports an empty `interfaces` list and all current
types report null `possibleTypes`; this is honest current-schema metadata, not a claim that actual
interface/union support is complete.

The first test edit duplicated enough selections in the already broad identity request to cross
the existing 144-field semantic-work budget, so it correctly failed before schema dispatch. The
coverage was rewritten as a separate whole GraphQL operation with one reusable `NamedType`
fragment over four concrete kinds, rather than increasing the budget. That standard-surface
operation passed immediately on PostgreSQL. It also exposed that the old generated renderer had
accepted the invalid scalar-form selection `ofType` with no child selection; correcting the broad
request to `ofType { kind name }` retained its expected null result and kept the stricter validation.
The generic implementation then pushed the broad request over PostgreSQL's statement deadline.
Single-pass field discovery replaced repeated plan/AST scans in the named-type, object-field,
input-value, and wrapper renderers; no timeout was raised and the operation was not split.

After those changes, focused unit/generator tests passed in 16s. Freshly generated, transpiled,
scratch-installed, and bound PostgreSQL and MySQL packages each passed the original broad request
and the new four-kind standard-surface request in the same selected run. PostgreSQL's two JUnit
tests completed in 41.205s (Gradle 1m05s); MySQL's completed in 56.894s (Gradle 1m42s). Three
intervening scratch-install attempts failed before SQL execution because Docker chose host ports
already owned by concurrent processes; retries with the identical cached package passed and are
the authoritative results. The current closure is 292 source-local helpers and 291 entry points
per dialect; MySQL reports 293 whole-request routines, and package inventories contain 318
PostgreSQL and 332 MySQL objects. These remain observed inventories, not project limits.
After deleting the obsolete generator implementation, a final combined current-worktree run
regenerated both runtime identities and packages, scratch-installed and bound both dialects, and
passed in 7m28s. Its final XML contains all 20 PostgreSQL Commerce methods with zero failures
(132.313s) and the two selected MySQL introspection methods with zero failures (56.417s); this
combined run supersedes the earlier pre-cleanup package identities.

```sh
./gradlew test \
  --tests io.titan.graphql.database.DatabaseGraphqlInputDescriptorTest \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointServesTrustedDatabaseResidentIntrospectionIdentity \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointServesTheStandardNamedTypeSurfaceFromOneGenericRoutine \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureServesTrustedDatabaseResidentIntrospectionIdentity \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureServesTheStandardNamedTypeSurfaceFromOneGenericRoutine \
  --no-daemon --console=plain
```

This closes the standard field surface only for the named kinds the model can currently generate.
M2 still requires actual interfaces/unions and non-empty possible-type relationships, custom
directive and arbitrary model-enum metadata, complete model-derived schema descriptions/defaults,
and the remaining validation/coercion/merge/error/cost requirements.

Latest introspection self-description evidence (2026-09-17): the generator now includes the six
standard introspection objects (`__Schema`, `__Type`, `__Field`, `__InputValue`, `__EnumValue`, and
`__Directive`) and the two standard introspection enums (`__TypeKind` and
`__DirectiveLocation`) in both `__schema.types` and `__type(name:)`. They do not use a new
meta-schema execution branch: generator-owned field descriptors call the existing shared
Titan-transpiled named-type, object-field, input-value, enum-value, and structural type-reference
interpreters. The field descriptor now accepts a complete bounded GraphQL type reference, so
introspection reports the real nullable/list/non-null chain and named kind. The input-value
descriptor also carries optional GraphQL-source defaults; this exposes each standard
`includeDeprecated: Boolean! = false` argument as a non-null Boolean with
`defaultValue: "false"`. The 2025 introspection contract is executable as well as descriptive:
`__Type.fields`, `__Type.inputFields`, `__Type.enumValues`, `__Field.args`, and
`__Directive.args` accept the argument in the transpiled runtime, while an explicitly supplied
null fails its non-null contract. A nullable Boolean variable remains valid at the location because
the location supplies a non-null default.

Focused database-core and source-generator tests passed in 16s. A fresh combined dialect run then
regenerated runtime identities and packages, transpiled, scratch-installed, and bound both
dialects before executing the same request against their public whole-request routines. The query
asserts all eight type names in `__schema.types`, retrieves every type through `__type`, checks
representative object fields and both enum inventories, and follows the structural wrapper chains
for `__Type.kind`, `__Type.fields`, and `__Directive.locations`. An initial run passed in 7m14s,
but a check against the official September 2025 introspection schema found that the first version
had used the older nullable `Boolean = false` shape and had not accepted `includeDeprecated` on
`__Field.args` or `__Directive.args`. The final request exercises those argument locations and the
non-null wrapper/default metadata directly.

The first corrected implementation factored the argument check into another stored helper. It
transpiled successfully (293 source-local helpers and 292 entry points), but PostgreSQL's existing
broad introspection request exceeded the unchanged statement deadline at 33.188s. Removing that
routine call was insufficient while `__Field.args` still performed variable-compatibility work
for every no-argument selection. The final implementation keeps the bounded check in its owning
renderer and takes a no-argument fast path before type/value work. Its first unit probe exposed an
existing empty-whitelist indexing bug in `commaSeparatedNameContains`; the helper now returns false
for an empty name set. Focused unit/generator tests then passed in 15s. No timeout, response budget,
or request split was introduced.

Three environment-only probes failed before relevant SQL evidence: one scratch install lost the
Testcontainers Ryuk port race on host port 37313, and two test-container starts lost port 37359.
The latter recovered for the remaining 20 PostgreSQL methods and demonstrated the broad request at
29.694s and the new request at 15.002s, but that invocation was retained only as diagnostic
evidence. The authoritative retry reused the unchanged generated package and passed in 5m43s.
PostgreSQL executed all 21 Commerce methods with zero failures in 148.654s; the broad request took
29.883s and the new request 15.468s. MySQL's selected new method passed in 30.872s. Both dialects
retain 292 source-local helpers and 291 entry points; MySQL retains 293 whole-request routines.
Package inventories remain 318 PostgreSQL and 332 MySQL objects, so self-description adds no
parallel routine family.

```sh
./gradlew test \
  --tests io.titan.graphql.database.DatabaseGraphqlInputDescriptorTest \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest databaseEngineCommerceMySqlIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointDescribesTheIntrospectionSchemaThroughTheGenericRuntime \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureDescribesTheIntrospectionSchemaThroughTheGenericRuntime \
  --no-daemon --console=plain
```

This closes introspection self-description for the then-supported schema. The later declared-output-
enum and enum-value-metadata increments extend the same interpreter. At this checkpoint the model
format could not declare actual interfaces/unions or custom directives and enum input/default/value
metadata was absent; the later evidence records the enum progress. Schema-wide merge/type-condition
behavior and the unified semantic/database cost model remain unresolved.

Latest canonical-argument and MySQL request-boundary evidence (2026-09-17): after schema-wide
argument validation, the shared Titan-transpiled engine now materializes every selected field
argument into one bounded `ma1` carrier. Nested list/input-object values use canonical `cv1`
records, so generated root, read, introspection, and custom-mutation bindings no longer reopen raw
variables JSON or reparse argument source. Focused unit coverage distinguishes an omitted nullable
variable-backed input-object field from an explicitly supplied null and proves nested variables in
lists and input objects. The typed AST's `Nf` field record now stores its direct argument count,
allowing keyed lookup without rescanning the complete AST for every field. The current PostgreSQL
Commerce report has 21 tests, zero failures, and 141.885s total runtime; its broad introspection
request completes in 21.286s under the unchanged deadline.

MySQL's generated public procedure now copies its immutable `requestQuery` input to request-local
`query`, derives the selected operation kind once from the typed AST, and fails closed on an invalid
kind. A repeat-CALL diagnostic showed that MySQL can retain stored-program cache/error state across
calls even after a transaction rollback: nested helpers returned SQL `NULL` on a later call while
the same helper invoked directly remained valid. MySQL's
[`mysql_reset_connection`](https://dev.mysql.com/doc/c-api/26.7/en/mysql-reset-connection.html)
contract and [WL#6797](https://dev.mysql.com/worklog/task/?id=6797) document that
`COM_RESET_CONNECTION` cleans stored procedure/function cache and error state and also rolls back
an active transaction. The supported transport boundary is therefore one whole-request CALL, apply its
database-provided commit/rollback outcome, then reset before reuse. This does not split an operation:
multiple mutation roots remain serial and atomic inside the single GraphQL request/CALL.

The focused MySQL transaction proof now performs each request in its own caller-controlled
transaction, inspects staged effects before applying the outcome, and resets only after commit or
rollback. One successful request contains four serial mutation roots and variable-backed Boolean,
Float, and large finite Float inputs; a separate two-root request stages its first update and then
fails on a missing second target, after which caller rollback restores the original row. The
canonical variable-backed mutation carrier has its own installed-package test. Both focused tests
passed, followed by the complete freshly installed Commerce MySQL suite: its JUnit XML records
24 tests, zero skipped, zero failures/errors, and 1148.203s test time; Gradle completed in 19m57s.
The current closure is 334 source-local helpers and 333 entry points per dialect, with 335 MySQL
whole-request routines and 360/374 PostgreSQL/MySQL package objects. These remain observed
inventories, not project limits.

```sh
./gradlew test \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedMutationIsSerialAndLetsTheCallerCommitOrRollBack \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedVariableMutationUsesTheCanonicalArgumentCarrier \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest --no-daemon --console=plain
```

One initial command targeted the commerce class through the base-MySQL tagged task and correctly
reported `No tests found`; it is not semantic evidence. The first correctly routed retry stopped
before SQL when Docker chose an already occupied scratch-container host port. The identical cached
package passed after the transient listener disappeared. At this checkpoint M2 remained partial for
arbitrary generated input types/defaults, general merge and type-condition rules, complete error
path/location behavior, actual interface/union/custom-directive metadata, enum input/default/value
metadata, and the unified semantic and database-work cost model. Later evidence records the enum
input and enum-value-metadata increments without changing the other open requirements.

Latest unordered-argument field-collection evidence (2026-09-17): the typed-AST merge comparator
now treats a field's arguments as a name-keyed set rather than comparing their source order. Each
name must match exactly once. A bounded scalar work queue compares nested values without recursive
stored-routine calls: input-object fields are unordered, list items remain positional, and scalar,
enum, and variable tokens retain identity. Thus reordering `first`/`after` or nested filter fields
is merge-compatible while changing `first: 1` to `first: 2` remains a field conflict. Values are
deliberately compared before runtime coercion: two distinct variable references cannot become
compatible merely because one request supplies them equal JSON values. The rule is shared
for root, nested, fragment-expanded, read, introspection, and mutation field collection; it does
not add a schema/model branch.

The focused JVM AST/input/language/generator suite passed in 17s. Titan then emitted 336
source-local helpers and 335 entry points per dialect; MySQL reports 337 whole-request routines.
Fresh package inventories contain 362 PostgreSQL and 376 MySQL objects. A generated
`customerFeed` request with equal `first`/`after` arguments in opposite orders and the same nested
filter fields in opposite orders now collects both child selections into one response, while the
same request with unequal `first` values returns the existing field-merging error. A unit case
also proves that reversing a list remains a conflict. The freshly installed/bound PostgreSQL
method passed in 8.660s and the equivalent MySQL method passed in 58.510s.

```sh
./gradlew test \
  --tests io.titan.graphql.database.DatabaseGraphqlInputDescriptorTest \
  --tests io.titan.graphql.database.DatabaseGraphqlLanguageTest \
  --tests io.titan.graphql.database.DatabaseGraphqlAstTest \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  --no-daemon --console=plain
TESTCONTAINERS_RYUK_DISABLED=true \
  TITAN_SCRATCH_POSTGRES_HOST_PORT=61001 TITAN_TEST_POSTGRES_HOST_PORT=61002 \
  ./gradlew databaseEngineCommerceIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointServesBoundedRelationTreeFromConnectionNode \
  --no-daemon --console=plain
TESTCONTAINERS_RYUK_DISABLED=true \
  TITAN_SCRATCH_MYSQL_HOST_PORT=61003 TITAN_TEST_MYSQL_HOST_PORT=61004 \
  ./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureServesBoundedRelationTreeFromConnectionNode \
  --no-daemon --console=plain
```

The first live fixture incorrectly used undeclared `customerFeed(orderBy:)`; database schema
preflight rejected it before merge planning, as required. The corrected fixture uses the declared
`first`/`after` pair. Two PostgreSQL scratch attempts stopped before SQL because Docker selected
occupied ports inside this host's ephemeral range, once for the database and once for Ryuk. Titan
already uses Docker-assigned ports with explicit lifecycle cleanup and supplies opt-in fixed-port
overrides, so no compiler/runtime change was justified. The authoritative focused runs used free
ports above the host ephemeral range and disabled Ryuk only for those supervised processes; both
installer and JUnit containers closed normally. General field merging remains partial:
schema-aware response-shape compatibility, mutually exclusive type conditions, and actual
interface/union execution still require implementation and dual-dialect proof.

Latest generated-wrapper merge evidence (2026-09-17): the shared duplicate-response-key check is
now applied not only to ordinary generated object plans but also to the specialized Relay
Connection, Edge, and PageInfo plans before their database reads or response assembly. This closes
a validation escape where two different wrapper fields could use the same alias, satisfy the
allowed-field count, and otherwise produce duplicate JSON members. The merge-heavy Commerce method
now rejects `value: edges` with `value: pageInfo`, `value: cursor` with `value: node`, and
`value: hasNextPage` with `value: endCursor`, while its existing compatible connection, edge/node,
PageInfo, fragment, nested-relation, and reordered-input selections still execute.

The focused generator/input suite passed in 17s. Fresh PostgreSQL transpile/package/install/bind
and the focused live method passed in 2m13s; the equivalent MySQL run passed in 3m42s. Both emitted
336 shared helpers and 335 entry points; MySQL retained 337 routines, and the package inventories
remain 362 PostgreSQL and 376 MySQL objects.

```sh
./gradlew test \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  --tests io.titan.graphql.database.DatabaseGraphqlInputDescriptorTest
TESTCONTAINERS_RYUK_DISABLED=true \
  TITAN_SCRATCH_POSTGRES_HOST_PORT=61001 TITAN_TEST_POSTGRES_HOST_PORT=61002 \
  ./gradlew databaseEngineCommerceIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointServesBoundedRelationTreeFromConnectionNode \
  --no-daemon --console=plain
TESTCONTAINERS_RYUK_DISABLED=true \
  TITAN_SCRATCH_MYSQL_HOST_PORT=61003 TITAN_TEST_MYSQL_HOST_PORT=61004 \
  ./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureServesBoundedRelationTreeFromConnectionNode \
  --no-daemon --console=plain
```

M2 remains partial. Concrete generated object and wrapper collection no longer has a known
duplicate-response-key escape in the supported schema surface, but response-shape compatibility
and mutually exclusive selections cannot be claimed until the model, schema descriptor,
introspection, validation, and execution paths support actual interfaces/unions and prove those
rules on both dialects.

Complete current-package regression evidence (2026-09-17): after the wrapper-merge increment, the
freshly install-verified Commerce package passed all 21 PostgreSQL methods with zero failures or
errors (JUnit 142.473s; Gradle 2m50s) and all 24 MySQL methods with zero failures or errors (JUnit
1040.947s; Gradle 18m10s). This supersedes the earlier phase-table wording that called the complete
suites “pre-merge” and the latest changes “focused” only. It establishes a current dual-dialect
baseline for the supported schema surface, not M2 semantic completion.

```sh
TESTCONTAINERS_RYUK_DISABLED=true \
  TITAN_SCRATCH_POSTGRES_HOST_PORT=61001 TITAN_TEST_POSTGRES_HOST_PORT=61002 \
  ./gradlew databaseEngineCommerceIntegrationTest --no-daemon --console=plain
TESTCONTAINERS_RYUK_DISABLED=true \
  TITAN_SCRATCH_MYSQL_HOST_PORT=61003 TITAN_TEST_MYSQL_HOST_PORT=61004 \
  ./gradlew databaseEngineCommerceMySqlIntegrationTest --no-daemon --console=plain
```

Latest declared-output-enum evidence (PostgreSQL 2026-09-17; MySQL completion 2026-09-21):
the v1alpha1 model can now declare schema enums and use them as nullable or non-null output field
types. Parser, editor schema, semantic validation, canonical JSON/hash, generated database metadata,
and documentation agree on that boundary. Enum definitions reject invalid names, duplicates,
reserved literals, type-name collisions, unknown field references, and the currently unsupported
filter/sort capabilities. Enum types and values are sorted for both semantic hashing and source
generation; reversing their YAML order produces byte-identical PostgreSQL and MySQL generated Java,
so an unchanged semantic/runtime identity cannot hide a behavioral introspection-order change.

The shared transpiled runtime checks each stored output value against generator-owned enum metadata.
A valid value serializes through point and connection-node reads. An invalid nullable value contributes
a source/path-located execution error and becomes `null`; an invalid non-null value bubbles through a
nullable point root or through the non-null node/edge/connection chain as GraphQL completion requires.
The same generated metadata exposes `CustomerStatus`, its values, and nullable/non-null field wrapper
kinds through `__schema.types` and `__type`. The initial attempt to add another root to the existing
broad introspection request was correctly rejected by the semantic-work budget; the enum inventory was
moved to a separate bounded request instead of raising or bypassing the budget.

Focused model/generator/input tests passed in 17s. The final packages transpile to 337 source-local
helpers and 336 entry points per dialect; MySQL contains 338 whole-request routines, and installed
package inventories contain 363 PostgreSQL and 377 MySQL objects. Fresh identity-matched package
installation and binding preceded both complete suites. PostgreSQL passed all 22 methods with zero
failures/errors (JUnit 153.379s). MySQL passed all 25 with zero failures/errors (JUnit 1038.874s;
Gradle 20m03s). No fixed-port test containers remained afterward.

```sh
./gradlew test \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  --tests io.titan.graphql.database.DatabaseGraphqlInputDescriptorTest \
  --tests io.titan.graphql.model.TitanGraphqlModelDocumentYamlTest \
  --tests io.titan.graphql.model.TitanGraphqlModelDocumentTest \
  --tests io.titan.graphql.model.TitanGraphqlModelDocumentSchemaTest \
  --tests io.titan.graphql.validation.TitanGraphqlModelDocumentValidatorTest \
  --no-daemon --console=plain
TESTCONTAINERS_RYUK_DISABLED=true \
  TITAN_SCRATCH_POSTGRES_HOST_PORT=61001 TITAN_TEST_POSTGRES_HOST_PORT=61002 \
  ./gradlew databaseEngineCommerceIntegrationTest --no-daemon --console=plain
TESTCONTAINERS_RYUK_DISABLED=true \
  TITAN_SCRATCH_MYSQL_HOST_PORT=61003 TITAN_TEST_MYSQL_HOST_PORT=61004 \
  ./gradlew databaseEngineCommerceMySqlIntegrationTest --no-daemon --console=plain
```

M2 remained partial after that increment. At that evidence point enums were deliberately output-only;
the following declared-enum mutation-input increment supersedes that limitation for explicit reviewed
custom mutations. Enum arguments on generic reads, enum input objects/field defaults, enum value
descriptions/deprecations, interfaces/unions and their merge rules, registered custom directives,
remaining error detail, and the unified semantic/database cost model remain sequenced work.

The portable expected-result corpus now has a ninth, runtime-independent declared-enum case. The
same `{ customer(id: 7) { status nullableStatus } }` response passed through freshly install-verified
PostgreSQL and MySQL packages on 2026-09-21. PostgreSQL's custom task executed its complete 22-method
tagged class again (zero failures/errors; JUnit 142.758s); MySQL executed the focused corpus method
(zero failures/errors; JUnit 44.998s). The combined Gradle invocation completed in 4m12s. This moves
the positive enum response into M2's shared exit mechanism rather than leaving it only in
dialect-specific assertions.

Latest declared-enum mutation-input evidence (2026-09-21): explicit reviewed custom mutations can
now bind a model-declared enum to a stored field without a query-specific runtime branch. The
validator accepts the declared input type; deterministic `id1` metadata carries the enum value set;
the schema/introspection descriptor exposes a non-null `ENUM` argument; and the generated
whole-operation prevalidation and serial execution passes both call shared transpiled enum coercion
before JDBC. The Commerce model's `setCustomerStatus` binding proves literal and variable success,
invalid-value rejection before SQL, response shaping, and caller-controlled commit/rollback.

The first freshly installed PostgreSQL probe caught a real language-core error: the common scalar
descriptor validator treated a quoted GraphQL enum literal as though it were a JSON variable string,
so `status: "ACTIVE"` was accepted. The corrected core retains input provenance: GraphQL literals and
variable defaults accept only enum names, while supplied JSON variables accept only strings. Direct
regressions cover valid literal/default/JSON-variable forms plus quoted-default, quoted-literal, and
unknown-value failures. Focused Java validator/generator/input tests passed in 16s. Fresh PostgreSQL
and MySQL packages then transpiled 337 source-local helpers and 336 entry points per dialect; MySQL
reported 338 whole-request routines. Both scratch installations and model/package bindings passed.
The two focused PostgreSQL installed methods passed with zero failures/errors (JUnit 15.882s; Gradle
2m29s), followed by the equivalent two MySQL methods (JUnit 63.785s; Gradle 3m50s).

```sh
./gradlew test \
  --tests io.titan.graphql.database.DatabaseGraphqlInputDescriptorTest \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  --tests io.titan.graphql.validation.TitanGraphqlModelDocumentValidatorTest \
  --no-daemon --console=plain
TESTCONTAINERS_RYUK_DISABLED=true \
  TITAN_SCRATCH_POSTGRES_HOST_PORT=61001 TITAN_TEST_POSTGRES_HOST_PORT=61002 \
  ./gradlew databaseEngineCommerceIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointDescribesTheIntrospectionSchemaThroughTheGenericRuntime \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedMutationIsSerialAndLetsTheCallerCommitOrRollBack \
  --no-daemon --console=plain
TESTCONTAINERS_RYUK_DISABLED=true \
  TITAN_SCRATCH_MYSQL_HOST_PORT=61003 TITAN_TEST_MYSQL_HOST_PORT=61004 \
  ./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureDescribesTheIntrospectionSchemaThroughTheGenericRuntime \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedMutationIsSerialAndLetsTheCallerCommitOrRollBack \
  --no-daemon --console=plain
```

The portable corpus now has a tenth case: a complete `SetStatus` mutation with a declared enum
variable, editor policy context, and explicit mutation transport permission. The corpus executor
passes that permission as part of each transport-neutral case rather than giving the test harness a
special mutation path. The same expected response passed through the installed PostgreSQL function
(JUnit 7.712s; Gradle 31s) and MySQL procedure with transaction outcome/reset handling (JUnit
46.075s; Gradle 1m33s). This is positive portable mutation evidence; multi-root rollback and other
negative transaction cases still belong in the shared corpus.

```sh
TESTCONTAINERS_RYUK_DISABLED=true \
  TITAN_SCRATCH_POSTGRES_HOST_PORT=61001 TITAN_TEST_POSTGRES_HOST_PORT=61002 \
  ./gradlew databaseEngineCommerceIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointMatchesPortableExpectedResultCorpus \
  --no-daemon --console=plain
TESTCONTAINERS_RYUK_DISABLED=true \
  TITAN_SCRATCH_MYSQL_HOST_PORT=61003 TITAN_TEST_MYSQL_HOST_PORT=61004 \
  ./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureMatchesPortableExpectedResultCorpus \
  --no-daemon --console=plain
```

M2 was still partial at that evidence point. The following enum-filter increment supersedes its
general-read statement for generated Relay filters; arbitrary input-object declarations/default
metadata, other enum input locations, abstract-type semantics, custom directives, remaining error
detail, and the unified semantic/database cost model remain open.

Latest declared-enum generated-filter evidence (2026-09-22): a reviewed enum field now contributes
generic `eq`, `neq`, `in`, and `isNull` operators to generated Relay connection reads. The model
validator rejects enum sorting and non-GraphQL-sound string/relational operators. Deterministic
`id1` metadata reuses the declared enum value set, `__type` exposes `CustomerStatusFilter` with
correct enum/list/Boolean wrappers, and the generated static SQL path binds only validated values;
there is no model-named query implementation or HTTP/JVM semantic branch. Literal `ACTIVE`, a JSON
variable list containing `INACTIVE`, and Boolean `isNull: false` execute successfully. A quoted
inline `"ACTIVE"` and an unknown JSON variable value are rejected during typed input coercion before
the generated database read.

The first generated-source check exposed an `isNull` typing bug: enum coercion had produced a
`String` local for an operator whose SQL placeholder is Boolean. `isNull` now remains on the shared
Boolean coercion/binding path while value-bearing operators use exact declared-enum validation. The
focused validator/generator/input suite then passed. Fresh PostgreSQL and MySQL packages each
transpiled 337 shared helpers and 336 entry points; MySQL retained 338 whole-request routines. Both
scratch installs and model/package bindings passed. PostgreSQL's focused introspection and broad
read methods passed with zero failures/errors (2 tests; JUnit 27.835s; Gradle 2m49s), followed by
MySQL (2 tests; JUnit 274.852s; Gradle 7m28s).

```sh
./gradlew test \
  --tests io.titan.graphql.validation.TitanGraphqlModelDocumentValidatorTest \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  --tests io.titan.graphql.database.DatabaseGraphqlInputDescriptorTest
TESTCONTAINERS_RYUK_DISABLED=true \
  TITAN_SCRATCH_POSTGRES_HOST_PORT=61001 TITAN_TEST_POSTGRES_HOST_PORT=61002 \
  ./gradlew databaseEngineCommerceIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointServesGeneratedInputTypeIntrospectionInBoundedRequests \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointServesUnrelatedSchemaKeysPoliciesAndLiveRows \
  --no-daemon --console=plain
TESTCONTAINERS_RYUK_DISABLED=true \
  TITAN_SCRATCH_MYSQL_HOST_PORT=61003 TITAN_TEST_MYSQL_HOST_PORT=61004 \
  ./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureServesGeneratedInputTypeIntrospectionInBoundedRequests \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureServesUnrelatedSchemaKeysPoliciesAndLiveRows \
  --no-daemon --console=plain
```

At that checkpoint M2 remained partial. The generated enum-filter location was proven, while later
increments were still required for point-root enum arguments and enum-value metadata. Those two
increments are recorded below; arbitrary input-object declarations/defaults, enum connection and
relation arguments, abstract types and their merge rules, registered custom directives, remaining
error detail, and the unified semantic/database cost model remain sequenced work.

Superseding full-regression and shared-corpus evidence (2026-09-22): the portable Commerce corpus
now has an eleventh case that sends a complete `InactiveCustomers` operation with a variable-backed
`CustomerStatus.in` filter and fixed expected connection JSON through the same transport-neutral
executor on both dialects. The first attempt to run the updated corpus unintentionally exercised
the complete PostgreSQL tagged class and exposed a genuine regression from the earlier enum
provenance fix: after variable-level validation, an explicitly supplied JSON `null` for a nullable
variable was trusted even when the variable was used at a non-null argument location. Generated
execution still rejected it, but too late and with a less precise root-field error/location.

The shared argument-input walker now preserves the variable pass's JSON/GraphQL syntax provenance
for non-null values while separately enforcing the location wrapper on a resolved null. A direct
unit regression requires `argument 'Query.customer.id' cannot be coerced to 'Int'` before generated
execution. The complete 603-test JVM suite passed after the current semantic-hash fixture and
compatibility enum carrier were reconciled (21s). Fresh database packages then transpiled twelve
changed SQL routines, passed scratch installation and binding, and ran every Commerce method:
PostgreSQL passed 22/22 (JUnit 139.542s; Gradle 4m44s) and MySQL passed 25/25 (JUnit 1075.179s;
Gradle 20m51s). The current full baseline therefore includes generated enum filters, the eleven-case
corpus, and the null-at-non-null-location regression on both dialects.

```sh
./gradlew test --no-daemon --console=plain
TESTCONTAINERS_RYUK_DISABLED=true \
  TITAN_SCRATCH_POSTGRES_HOST_PORT=61001 TITAN_TEST_POSTGRES_HOST_PORT=61002 \
  ./gradlew databaseEngineCommerceIntegrationTest --no-daemon --console=plain
TESTCONTAINERS_RYUK_DISABLED=true \
  TITAN_SCRATCH_MYSQL_HOST_PORT=61003 TITAN_TEST_MYSQL_HOST_PORT=61004 \
  ./gradlew databaseEngineCommerceMySqlIntegrationTest --no-daemon --console=plain
```

This supersedes the focused-only current-package wording above. M2 and M3 remain partial for the
explicit blockers in the ledger; a green current suite does not imply coverage of unimplemented
input-object declarations/defaults, abstract types, custom directives, batching, or unified costs.

Latest declared-enum point-root evidence (2026-09-22): point-root equality arguments now share the
same generated enum descriptor and transpiled coercion path as the already-proven mutation and
filter locations. The model validator and transitional projection adapter accept a declared enum
only when it exactly matches the stored field type. Generated `id1` metadata does not misclassify
that enum as a scalar; `sd1`/introspection advertise the required `CustomerStatus!` argument as an
enum; literal and JSON-variable values are checked against the deterministic declared value set;
and JDBC receives only the validated string through static composite-key SQL. No model-named
runtime query or frontend semantic branch was added.

The Commerce model now exposes generic `customerByIdAndStatus(id: Int!, status:
CustomerStatus!)`. Live tests prove an `ACTIVE` literal, an `INACTIVE` JSON variable, a composite
key mismatch returning nullable root data, rejection of a quoted inline enum, rejection of an
unknown variable value, and the exact introspection wrapper/kind. The portable corpus adds a
twelfth complete-operation case for the variable-backed point root. The complete JVM suite passed
604/604 in 24s. Fresh packages each emitted 337 helpers and 336 entry points; MySQL retained 338
whole-request routines. Scratch install and package binding passed before PostgreSQL's three focused
methods passed (JUnit 51.428s; Gradle 3m28s) and MySQL's three passed (JUnit 422.410s; Gradle
10m16s).

```sh
./gradlew test --no-daemon
TESTCONTAINERS_RYUK_DISABLED=true \
  TITAN_SCRATCH_POSTGRES_HOST_PORT=61001 TITAN_TEST_POSTGRES_HOST_PORT=61002 \
  ./gradlew databaseEngineCommerceIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointServesTrustedDatabaseResidentIntrospectionIdentity \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointServesUnrelatedSchemaKeysPoliciesAndLiveRows \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointMatchesPortableExpectedResultCorpus \
  --no-daemon --console=plain
TESTCONTAINERS_RYUK_DISABLED=true \
  TITAN_SCRATCH_MYSQL_HOST_PORT=61003 TITAN_TEST_MYSQL_HOST_PORT=61004 \
  ./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureServesTrustedDatabaseResidentIntrospectionIdentity \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureServesUnrelatedSchemaKeysPoliciesAndLiveRows \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureMatchesPortableExpectedResultCorpus \
  --no-daemon --console=plain
```

M2 and M3 remain partial. This closes the point-root enum location, not enum connection/relation
arguments, arbitrary input-object declarations/defaults, abstract types, registered custom
directives, batching, or the unified semantic/database cost model.

Latest enum-value metadata evidence (2026-09-22): reviewed enum values may now carry descriptions,
deprecation state, and an optional deprecation reason in the model document. Validation rejects
metadata for undeclared values, duplicate metadata entries, and a reason on a non-deprecated value.
Canonical model identity sorts the metadata by value name and changes when its semantics change.
The generator compiles all model and standard enum values into the versioned, length-prefixed
`ev1` descriptor; arbitrary punctuation in descriptions and reasons cannot alter record boundaries.
The shared Titan-transpiled `__EnumValue` renderer consumes that descriptor, returns descriptions,
`isDeprecated`, and reasons, excludes deprecated values by default, and includes them only when the
database language core materializes `includeDeprecated: true`. It uses no runtime model parser,
reflection, frontend branch, or model-named resolver.

The Commerce proof enum contains documented `ACTIVE` and deprecated `LEGACY` values. The complete
JVM suite passed 606 tests in 22s, including authoring-schema, YAML, validation, canonical-identity,
descriptor, escaping, filtering, and generated-source regressions. Fresh PostgreSQL generation
changed 57 SQL files, emitted 337 helpers/336 entry points, passed scratch installation and binding,
and passed the focused live introspection method in 23.826s (Gradle 3m). Fresh MySQL generation
changed the same 57 SQL files, emitted the same helper/entry-point closure plus 338 whole-request
routines, passed scratch installation and binding, and passed its focused live method in 50.688s
(Gradle 4m05s). Both live requests prove default omission and `includeDeprecated: true` metadata.
The transport-neutral Commerce corpus now has thirteen cases: its added `EnumMetadata` operation
uses an explicit trusted-introspection flag and verifies both views in fixed expected JSON. After an
initial fail-closed rejection exposed that the corpus had no such trusted-context capability, the
corpus contract was extended with `allowIntrospection` defaulting to false. The corrected corpus
passed through PostgreSQL in 9.389s (Gradle 37s) and MySQL in 65.561s (Gradle 2m).

```sh
./gradlew test --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointServesTrustedDatabaseResidentIntrospectionIdentity \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureServesTrustedDatabaseResidentIntrospectionIdentity \
  --no-daemon --console=plain
```

M2 remains partial. The next dependency-ordered language gaps are arbitrary authored input-object
declarations/defaults, abstract types and their merge rules, remaining merge/error semantics,
custom directives, and the unified semantic/database cost model.

Latest declared-enum Relay equality evidence (2026-09-22): the single generated connection executor
shared by query roots and relation fields now accepts a reviewed local equality binding whose type
is `Int`, `Long`, or a declared enum. Validation requires enum arguments to bind a modeled stored
field of the same declared type. Generated `id1` and `sd1` metadata retain enum kind and wrappers;
the transpiled input core distinguishes unquoted literals from JSON-string variables and checks the
value against the deterministic enum descriptor before any page, opposite-boundary, or count SQL.
The same statically generated predicates and prepared statements serve both entry shapes; JDBC uses
the reviewed field setter (`setString` for an enum). No model-named runtime dispatch or frontend
semantic branch was added.

Commerce now proves `customerFeed(status: CustomerStatus)` and
`Customer.orderConnection(status: OrderStatus)` against modeled `status` columns. Live requests
cover enum literals, variables, an empty relation match, quoted-literal rejection, unknown-variable
rejection, result completion, and exact root/relation introspection kinds. The portable corpus adds
a fourteenth case that executes both entry shapes in one complete operation. The migration-only JVM
projection adapter, carrier generator, and invoker deliberately omit these optional enum arguments
because that runtime's descriptor supports only integer equality. This is a documented M6 deletion
seam, not a second implementation of the new semantics.

The complete JVM suite passed 607/607 in 23s. Fresh PostgreSQL and MySQL packages each emitted 337
helpers and 336 entry points; MySQL retained 338 whole-request routines. Each dialect changed two
SQL artifacts, passed scratch installation and package binding, then passed the three focused
introspection, broad execution, and shared-corpus methods. PostgreSQL took 56.546s of JUnit time
(3m48s Gradle); MySQL took 520.796s of JUnit time (12m11s Gradle).

```sh
./gradlew test --no-daemon --console=plain
TESTCONTAINERS_RYUK_DISABLED=true \
  TITAN_SCRATCH_POSTGRES_HOST_PORT=61001 TITAN_TEST_POSTGRES_HOST_PORT=61002 \
  ./gradlew databaseEngineCommerceIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointServesTrustedDatabaseResidentIntrospectionIdentity \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointServesUnrelatedSchemaKeysPoliciesAndLiveRows \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointMatchesPortableExpectedResultCorpus \
  --no-daemon --console=plain
TESTCONTAINERS_RYUK_DISABLED=true \
  TITAN_SCRATCH_MYSQL_HOST_PORT=61003 TITAN_TEST_MYSQL_HOST_PORT=61004 \
  ./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureServesTrustedDatabaseResidentIntrospectionIdentity \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureServesUnrelatedSchemaKeysPoliciesAndLiveRows \
  --tests io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureMatchesPortableExpectedResultCorpus \
  --no-daemon --console=plain
```

M2 and M3 remain partial. This closes the declared-enum connection/relation equality location, not
authored input-object declarations/defaults, abstract types, registered directives, general
relation batching, remaining error semantics, or the unified semantic/database cost model.

Latest authored-input-object/default evidence (2026-09-22): the model document and public JSON
schema now accept arbitrary named input objects with descriptions, recursively typed fields,
GraphQL constant defaults, and input-field deprecation metadata. Build validation resolves every
input type, rejects namespace collisions and unbroken singular non-null cycles, parses and coerces
defaults, and validates dotted custom-mutation bindings against stored model fields. Canonical model
JSON includes all new behavior, so the model semantic hash and package binding change when an input
contract changes.

The generator compiles input types/defaults into length-counted `id1` metadata and delimiter-safe
`iv1` introspection descriptors. The transpiled language core applies authored defaults only to
omitted fields while preserving explicit null, distinguishes GraphQL literal/default provenance
from supplied JSON variables, materializes nested canonical `cv1` values, and exposes a generic
dotted-path reader to generated mutation bindings. `__schema`/`__type` now expose authored input
types, type/field descriptions, exact wrappers, defaults, and deprecations with database-side
`includeDeprecated` filtering. The Commerce package proves `renameCustomerWithInput(input:
RenameCustomerInput!)`; both a nested variable and an inline object with an omitted nested field
execute through the same generated update path, and caller rollback restores the row. No maintained
runtime branch names this mutation or its input types.

Two failed transpilation attempts are retained as negative evidence. An initial default encoding
relied on UTF-16 character conversion/loop shapes outside the proven Titan subset; replacing it with
raw length-counted GraphQL source removed that dependency. A second attempt used a scanner-sensitive
`charAt` shape; substring-based single-character checks transpiled portably. The first installed
introspection probes then correctly rejected an ordinary trusted context because introspection was
disabled; the tests were corrected to request the existing trusted introspection capability rather
than weakening policy. Finally, the first complete JVM run failed six legacy/management fixture tests
because adding canonical `inputObjects` intentionally changed the demo model hash. Refreshing the
checked-in schema-faithful binding fixture resolved all six, and the full suite then passed.

Focused JVM descriptor/generator tests and both fresh transpilation tasks passed in 5m22s. Each
dialect emitted 340 source-local helpers and 338 entry points; PostgreSQL updated 61 SQL files and
MySQL updated 61, with 341 MySQL whole-request routines. Fresh package staging, identity generation,
scratch installation, and model/package binding preceded the final live tests. PostgreSQL passed the
authored-input and fifteen-case corpus methods 2/2 (JUnit 16.714s; Gradle 48s). MySQL passed the same
two methods 2/2 (JUnit 117.546s; Gradle 2m59s). The complete Docker-free JVM suite passed 611/611 in
22s; `git diff --check`, the public JSON schema parse, and the corpus parse also passed.

```sh
./gradlew test \
  --tests '*DatabaseGraphqlInputDescriptorTest' \
  --tests '*TitanGraphqlDatabaseEngineSourceGeneratorTest' \
  titanGraphqlTranspileCommerceDatabaseEngine \
  titanGraphqlTranspileCommerceMySqlDatabaseEngine \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests '*CommerceDatabaseGraphqlEngineIT.installedEntryPointExecutesAuthoredNestedInputObjectsAndDefaults' \
  --tests '*CommerceDatabaseGraphqlEngineIT.installedEntryPointMatchesPortableExpectedResultCorpus' \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests '*CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureExecutesAuthoredNestedInputObjectsAndDefaults' \
  --tests '*CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureMatchesPortableExpectedResultCorpus' \
  --no-daemon --console=plain
./gradlew test --no-daemon --console=plain
jq empty docs/schema/titan.graphql.schema.json
jq '.cases | length' src/test/resources/database-engine-corpus/commerce-v1.json
git diff --check
```

M2 and M4 remain partial. This increment closes authored input-object declarations, nested coercion,
input-field defaults, their introspection metadata, and one generic explicit-update consumer. The
next M2 dependencies at this historical checkpoint were abstract/interface/union metadata and
type-condition merge semantics, registered custom directives, remaining schema/error detail, general argument defaults, and the
unified semantic/database cost model. M4 still requires nullable writes, concurrency, durable
idempotency/audit/outbox behavior, arbitrary reviewed procedures, management-route disposition,
registry attestation, and the complete failure-path matrix.

Latest abstract-output evidence (2026-09-22): the build model, YAML/canonical JSON, and public JSON
schema now define interfaces, unions, object implementations, and an optional abstract `outputType`
for point roots while retaining a concrete physical `type`. Validation checks the shared type
namespace, members, exact interface-field contracts, and root membership; it rejects abstract
connection output until heterogeneous connection execution has a defined cursor and policy model.
Generated schema descriptors and transpiled introspection expose interface/union kinds, descriptions,
object interfaces, possible types, and abstract Query return types.

The database core now validates fragment overlap and response-key compatibility per possible runtime
object. The generator passes each concrete object's full object/interface/union condition set into
point, nested-relation, and Relay-node selection planning. The 18-case portable corpus proves direct
interface fields, interface/union fragments, concrete `__typename`, applicable interface fragments
inside concrete relation and Relay-node positions, mutually exclusive alias reuse, overlapping alias
conflict rejection, and an exact source-located impossible-condition failure. A first expanded live
case put a `Customer` fragment beneath a concrete `Order` relation; PostgreSQL correctly rejected it,
and the case was corrected rather than weakening static overlap validation.

```sh
./gradlew test \
  --tests '*DatabaseGraphqlSelectionPlanTest' \
  --tests '*DatabaseGraphqlInputDescriptorTest' \
  --tests '*TitanGraphqlDatabaseEngineSourceGeneratorTest' \
  --no-daemon --console=plain
./gradlew titanGraphqlTranspileCommerceDatabaseEngine \
  titanGraphqlTranspileCommerceMySqlDatabaseEngine --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests '*CommerceDatabaseGraphqlEngineIT.installedEntryPointMatchesPortableExpectedResultCorpus' \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests '*CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureMatchesPortableExpectedResultCorpus' \
  --no-daemon --console=plain
./gradlew test --no-daemon --console=plain
jq empty docs/schema/titan.graphql.schema.json
jq -e '.cases | length == 18' src/test/resources/database-engine-corpus/commerce-v1.json
git diff --check
```

The focused 42-test suite passed. Both transpilation tasks passed in 5m58s with 358 source-local
helpers and 355 emitted entry points per dialect and 359 MySQL whole-request routines. Fresh
scratch-installed, identity-bound packages passed the corpus method on PostgreSQL in 54s and MySQL
in 3m59s. The complete JVM suite initially exposed the expected stale model-hash fixture and one
canonical-shape assertion; after reviewing and updating those fixtures, all 620 tests passed in 24s.
The public schema, 18-case corpus, and diff checks are clean. M2 remains partial: abstract connection
output/runtime discovery, registered directives, remaining schema/error detail, general argument
defaults, and the unified semantic/database cost model are next.

Latest output-field metadata evidence (2026-09-22): object fields now retain authored `description`,
`deprecated`, and `deprecationReason` values through YAML parsing and canonical JSON identity; the
public JSON Schema documents the same contract, and validation rejects a reason on a non-deprecated
field. The generator emits one length-counted `fm1` record aligned to every object/interface output
field descriptor. The shared transpiled renderer consumes that record, returns descriptions and
deprecation state, omits deprecated fields by default, and includes them with their reason for
`fields(includeDeprecated: true)`. Generated roots, relations, wrappers, and meta-types receive
explicit empty metadata rather than model-specific branches.

```sh
./gradlew test \
  --tests '*TitanGraphqlModelDocumentYamlTest' \
  --tests '*TitanGraphqlModelDocumentSchemaTest' \
  --tests '*TitanGraphqlModelDocumentValidatorTest' \
  --tests '*DatabaseGraphqlInputDescriptorTest' \
  --tests '*TitanGraphqlDatabaseEngineSourceGeneratorTest' \
  --no-daemon --console=plain
./gradlew titanGraphqlTranspileCommerceDatabaseEngine \
  titanGraphqlTranspileCommerceMySqlDatabaseEngine --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests '*CommerceDatabaseGraphqlEngineIT.installedEntryPointServesTrustedDatabaseResidentIntrospectionIdentity' \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests '*CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureServesTrustedDatabaseResidentIntrospectionIdentity' \
  --no-daemon --console=plain
./gradlew test --no-daemon --console=plain
jq empty docs/schema/titan.graphql.schema.json
git diff --check
```

The focused 65-test model/language/generator suite passed. Both transpilation tasks passed in 5m59s
with unchanged topology: 358 source-local helpers, 355 emitted entry points per dialect, and 359
MySQL whole-request routines. Fresh package staging, scratch-install verification, identity binding,
and the bounded live metadata request passed on PostgreSQL in 1m10s and MySQL in 3m13s. An initial
attempt to enlarge the already broad schema-identity request was correctly rejected by the existing
response-character budget; the proof was moved to an independent bounded GraphQL request instead of
weakening the limit. Canonical metadata changed the demo semantic identity as intended; after the
fixture was reconciled, all 622 JVM tests passed in 24s. At that checkpoint M2 remained partial,
with abstract connection output/runtime discovery, registered directives, remaining schema/error
detail, general argument defaults, and the unified semantic/database cost model still open.

Latest abstract Relay-output evidence (2026-09-22): a Relay root may now retain one reviewed concrete
`type` for generated static SQL while exposing an interface or union `outputType`. The generator
derives public `<OutputType>Connection`/`<OutputType>Edge` wrappers, declares the edge `node` with
the correct abstract introspection kind, validates node selections against the abstract type and
possible concrete types, executes them with the known row's complete runtime-condition set, and
returns concrete node `__typename`. Wrapper names and typed ancestry are schema-derived; no query-
specific branch, HTTP semantic, resolver callback, or dynamic SQL was added.

```sh
./gradlew test \
  --tests '*TitanGraphqlModelDocumentValidatorTest' \
  --tests '*TitanGraphqlDatabaseEngineSourceGeneratorTest' \
  --tests '*DatabaseGraphqlInputDescriptorTest' \
  --tests '*DatabaseGraphqlSelectionPlanTest' \
  --no-daemon --console=plain
./gradlew titanGraphqlTranspileCommerceDatabaseEngine \
  titanGraphqlTranspileCommerceMySqlDatabaseEngine --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests '*CommerceDatabaseGraphqlEngineIT.installedEntryPointMatchesPortableExpectedResultCorpus' \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests '*CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureMatchesPortableExpectedResultCorpus' \
  --no-daemon --console=plain
./gradlew test --no-daemon --console=plain
jq -e '.cases | length == 19' src/test/resources/database-engine-corpus/commerce-v1.json
git diff --check
```

The focused 62-test language/generator suite passed. Titan transpiled both dialects in 6m49s with
unchanged topology (358 source-local helpers, 355 entry points per dialect, and 359 MySQL whole-
request routines), updating only two SQL files per dialect. Fresh staging, scratch-install
verification, model/package binding, and all 19 fixed corpus cases passed on PostgreSQL in 1m02s and
MySQL in 5m04s. The new case proves a two-row `NodeConnection`, abstract direct fields and concrete
fragments, concrete `Customer` node names, wrapper names, and `NodeEdge.node` interface
introspection. All 622 JVM tests then passed in 24s. M2 remains partial: the model still cannot
express a connection spanning heterogeneous physical projections; registered directives, remaining
schema/error detail, general argument defaults, and the unified semantic/database cost model also
remain.

Latest registered-conditional-directive evidence (2026-09-22): the v1alpha1 model now declares
reviewed executable directives with a description, non-repeatable `if: Boolean!` contract,
`includeIf` or `skipIf` behavior, and an explicit subset of `FIELD`, `FRAGMENT_SPREAD`, and
`INLINE_FRAGMENT`. Names, behavior, locations, and descriptions participate in canonical model
identity. The generator emits compact execution and introspection descriptors; the typed request
AST carries the execution descriptor so every transpiled selection walker validates identity,
location, repetition, argument shape, and Boolean coercion before applying inclusion. The
database-resident introspection path renders the same registered definitions. No query-specific
branch, dynamic SQL, frontend interpretation, or JVM callback was added.

```sh
./gradlew test \
  --tests '*TitanGraphqlModelDocumentYamlTest' \
  --tests '*TitanGraphqlModelDocumentSchemaTest' \
  --tests '*TitanGraphqlModelDocumentValidatorTest' \
  --tests '*DatabaseGraphqlInputDescriptorTest' \
  --tests '*TitanGraphqlDatabaseEngineSourceGeneratorTest' \
  --no-daemon --console=plain
./gradlew titanGraphqlTranspileCommerceDatabaseEngine \
  titanGraphqlTranspileCommerceMySqlDatabaseEngine --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests '*installedEntryPointMatchesPortableExpectedResultCorpus*' \
  --tests '*installedEntryPointServesTrustedDatabaseResidentIntrospectionIdentity*' \
  --tests '*installedEntryPointServesTheStandardNamedTypeSurfaceFromOneGenericRoutine*' \
  --tests '*installedEntryPointServesBoundedRelationTreeFromConnectionNode*' \
  --tests '*installedMutationIsSerialAndLetsTheCallerCommitOrRollBack*' \
  --tests '*installedEntryPointServesUnrelatedSchemaKeysPoliciesAndLiveRows*' \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests '*installedProcedureMatchesPortableExpectedResultCorpus*' \
  --tests '*installedProcedureServesTrustedDatabaseResidentIntrospectionIdentity*' \
  --tests '*installedProcedureServesTheStandardNamedTypeSurfaceFromOneGenericRoutine*' \
  --tests '*installedProcedureServesBoundedRelationTreeFromConnectionNode*' \
  --tests '*installedMutationIsSerialAndLetsTheCallerCommitOrRollBack*' \
  --tests '*installedProcedureServesUnrelatedSchemaKeysPoliciesAndLiveRows*' \
  --no-daemon --console=plain
./gradlew test --no-daemon --console=plain
jq -e '.cases | length == 21' src/test/resources/database-engine-corpus/commerce-v1.json
jq empty docs/schema/titan.graphql.schema.json
git diff --check
```

The focused 69-test model/language/generator suite passed. Both dialects transpiled in 7m55s,
reporting 361 source-local helpers and 358 emitted entry points; MySQL reported 362 whole-request
routines. Fresh scratch installation and identity binding passed. The affected PostgreSQL package
methods passed across the reconciliation reruns, including all 21 fixed corpus cases. The clean
six-method MySQL gate passed in 27m13s. The initial broad introspection request exceeded the existing
response-character budget after adding directive metadata; the proof was split into independent
bounded schema-types and directive-metadata operations instead of raising the budget. The first full
JVM run correctly exposed the demo package fixture's stale semantic hash; after updating that
identity fixture, all 626 tests passed in 24s. The public JSON Schema, 21-case corpus count, and diff
checks are clean. M2 remains partial: heterogeneous multi-projection connections, remaining
schema/error detail, general argument defaults, and the unified semantic/database cost model are
the next language dependencies.

Latest general field-argument-default evidence (2026-09-22): root, relation, flat-mutation, and
authored mutation-input argument records now retain optional GraphQL constant-value source through
YAML parsing, canonical model identity, and the public JSON Schema. Build validation resolves each
default against the generated public input type, rejects variables and invalid/null values at
non-null locations, and prevents model defaults on generated Relay controls. The generator encodes
defaults delimiter-safely in the schema descriptor and length-prefixes them in typed input metadata;
the transpiled runtime decodes that metadata for both introspection and execution.

The shared database language core now materializes a location default when a field argument is
entirely omitted, applies it when an argument references an omitted nullable variable, and preserves
explicit null as distinct. Generated point, connection-root equality, relation equality, flat
mutation, and authored mutation-input bindings all consume the same `ma1`/`cv1` carrier. The final
25-case Commerce corpus proves point/root-connection/relation defaults, a flat enum mutation default,
an omitted whole input-object mutation default composed with an omitted nested input-field default,
and exact introspection source. No query-specific dispatch or HTTP/JVM coercion was added.

An initial PostgreSQL corpus run exposed that four specialized generated guards still compared the
syntactically supplied argument count with the schema's total count. The shared validator already
owns unknown/duplicate/required checks, so those guards now reject only an over-count; missing
non-defaulted values still fail at their typed binding. A generator regression forbids restoring the
exact-count form. A first MySQL install then correctly failed its package-sized preflight because the
17,758,979-byte public procedure exceeded Titan's former 16 MiB scratch setting. Both Titan scratch
container paths now use 64 MiB while real targets retain the preflight-before-SQL behavior; Titan's
focused preflight regression passed. The final routine bundle is 19,447,090 bytes.

```sh
./gradlew test \
  --tests '*TitanGraphqlModelDocumentYamlTest' \
  --tests '*TitanGraphqlModelDocumentSchemaTest' \
  --tests '*TitanGraphqlModelDocumentValidatorTest' \
  --tests '*DatabaseGraphqlInputDescriptorTest' \
  --tests '*TitanGraphqlDatabaseEngineSourceGeneratorTest' \
  --no-daemon --console=plain
./gradlew titanGraphqlTranspileCommerceDatabaseEngine \
  titanGraphqlTranspileCommerceMySqlDatabaseEngine --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests '*installedEntryPointMatchesPortableExpectedResultCorpus*' \
  --no-daemon --console=plain
TITAN_SCRATCH_MYSQL_HOST_PORT=62002 TITAN_TEST_MYSQL_HOST_PORT=62003 \
  TESTCONTAINERS_RYUK_DISABLED=true \
  ./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests '*installedProcedureMatchesPortableExpectedResultCorpus*' \
  --no-daemon --console=plain
./gradlew -p vendor/titan :titan-gradle-plugin:test \
  --tests 'io.titan.gradle.TitanArtifactInstallVerifierPacketPreflightTest' \
  --no-daemon --console=plain
./gradlew test --no-daemon --console=plain
jq -e '.cases | length == 25' src/test/resources/database-engine-corpus/commerce-v1.json
jq empty docs/schema/titan.graphql.schema.json
git diff --check
```

The focused 73-test suite passed. Final dual-dialect transpilation passed in 14m27s with 362
source-local helpers and 359 emitted entry points per dialect and 363 MySQL whole-request routines;
only two SQL files changed per dialect for the composed input default. Fresh scratch installation,
package identity binding, and all 25 corpus cases passed on PostgreSQL in 1m45s (JUnit 46.999s) and
MySQL in 14m16s (JUnit 652.640s). The initial complete JVM run exposed the expected stale demo model
hash plus a generator-test heap peak; after updating the reviewed fixture and making the test
generate/analyze one recursive dialect source at a time, the unchanged default heap passed all
630 tests in 32s. M2 remains partial: heterogeneous multi-projection connection discovery,
remaining schema/error detail, and the unified semantic/database cost model are next. The measured
MySQL package size and 13.9x corpus-time ratio are explicit inputs to that cost work, not a claim of
acceptable final performance.

Latest database-wide error-contract evidence (2026-09-22): every GraphQL error assembled by the
shared transpiled engine now includes a stable `extensions.code`. The failure site selects one of
`PARSE_ERROR`, `VALIDATION_ERROR`, `AUTHORIZATION_ERROR`, `UNSUPPORTED_OPERATION`,
`EXECUTION_ERROR`, `DEADLINE_EXCEEDED`, `RESOURCE_LIMIT_ERROR`, or `INTERNAL_ERROR`; no helper
classifies an error by inspecting its message. The lexer exposes a bounded failure offset only on
its rejected-document path, so valid requests retain their existing one-pass token carrier while
lexical errors gain GraphQL line/column locations. Execution completion keeps its response path and
location while adding `EXECUTION_ERROR`; mutation prevalidation, policy, domain, unsupported, and
deadline failures retain the required rollback outcome with their distinct codes. Transport and
generated-descriptor invariant failures no longer masquerade as client validation.

The portable Commerce corpus now has 29 cases. The three existing source-located validation cases
assert `VALIDATION_ERROR`; four independent cases prove an invalid lexical character and exact
location, disabled-introspection authorization, an unsupported subscription, and a missing mutation
target reported as an execution failure with `data: null`. Generator-shape regressions assert that
identity, query deadline, introspection/root policy, mutation policy, and mutation-domain branches
call their explicit category helpers. Raw generated SQL inspection confirms the code literals and
path-bearing execution envelope in both dialects.

```sh
./gradlew test \
  --tests 'io.titan.graphql.database.*' \
  --tests '*TitanGraphqlDatabaseEngineSourceGeneratorTest' \
  --no-daemon --console=plain
./gradlew test --no-daemon --console=plain
./gradlew titanGraphqlTranspileCommerceDatabaseEngine \
  titanGraphqlTranspileCommerceMySqlDatabaseEngine --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests '*CommerceDatabaseGraphqlEngineIT.installedEntryPointMatchesPortableExpectedResultCorpus' \
  --no-daemon --console=plain
TITAN_SCRATCH_MYSQL_HOST_PORT=62002 TITAN_TEST_MYSQL_HOST_PORT=62003 \
  TESTCONTAINERS_RYUK_DISABLED=true \
  ./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests '*CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureMatchesPortableExpectedResultCorpus' \
  --no-daemon --console=plain
jq -e '.cases | length == 29' src/test/resources/database-engine-corpus/commerce-v1.json
git diff --check
```

The database-core/generator focused gates and all 632 JVM tests passed. Dual transpilation passed in
14m24s with 378 source-local helpers and 375 emitted entry points per dialect; MySQL reports 379
whole-request routines. Fresh staging, scratch installation, package binding, and all 29 cases
passed on PostgreSQL in 1m48s (JUnit 47.509s) and MySQL in 15m38s (JUnit 736.069s). The current
MySQL routine bundle is 19,478,246 bytes and its public procedure source is 17,760,807 bytes. This
increment closes the previously contradicted stable-error-code contract, but does not close M2:
heterogeneous multi-projection connection discovery, remaining schema detail, and the unified cost
model remain. The 16 additional helper/entry-point routines, 16 additional MySQL routines, larger
bundle, and slower corpus are explicit optimization inputs for that cost milestone.

Latest terminal-error and first cost-ledger evidence (2026-09-22): inspection of the staged SQL
after the first coded-error pass found two raw MySQL terminal fallbacks that bypassed the shared
taxonomy: invalid transport framing and final response-size overflow. Both were corrected and
covered by generator-shape and live final-response-budget tests. The corrected error package
transpiled in 14m42s with 379 source-local helpers, 376 emitted entry points per dialect, and 380
MySQL whole-request routines. Isolated final-response-budget tests passed through installed
PostgreSQL and MySQL packages in 38.090s and 194.208s JUnit time respectively. The corrected
29-case packages then passed PostgreSQL in 46.993s and MySQL in 727.102s.

The subsequent broad MySQL integration test deliberately exposed a real cost-model contradiction.
A typed filter variable containing 129 list values was guaranteed to exceed the 128-node coercion
budget, but the coercer appended and revisited every child before rejecting it. The request reached
the unchanged 30-second database statement timeout; the complete test failed after 28m48s (JUnit
1518.099s). Raising the timeout was rejected as a remedy. The request-cost model is now documented
in `query-contract.md` as a multi-dimensional ledger with explicit precharge rules and visible
remaining dimensions.

The transpilable coercer now reserves a known list cardinality against its remaining node allowance
before appending child queue entries. Budget failures travel through a private scalar status carrier,
so variable and literal argument callers select `RESOURCE_LIMIT_ERROR` without examining diagnostic
wording; the carrier is removed before the GraphQL response. Exact JVM tests cover variable and
literal list paths, location, transport outcome, public code, and carrier absence. PostgreSQL and
MySQL SQL inspection confirms the cardinality comparison precedes the child-append loop. The change
adds no helper routines: dual transpilation passed in 14m57s with the same 379/376 per-dialect
closure and 380 MySQL whole-request routines.

An isolated installed-package regression passed on PostgreSQL in 38.586s and MySQL in 200.482s
including deployment, with the MySQL request returning inside its unchanged 30-second statement
limit. A thirtieth portable corpus case uses a nested 128-item variable list and asserts the exact
source-located `RESOURCE_LIMIT_ERROR`. Fresh package staging, scratch installation, identity binding,
and all 30 cases passed on PostgreSQL in 47.908s and MySQL in 740.851s. The complete JVM suite passed
633 tests in 31s. The staged MySQL routine bundle is 19,483,353 bytes; the public procedure remains
17,760,891 bytes.

```sh
./gradlew test \
  --tests 'io.titan.graphql.database.DatabaseGraphqlInputDescriptorTest' \
  --tests 'io.titan.graphql.database.DatabaseGraphqlErrorTest' \
  --no-daemon --console=plain
./gradlew test --tests '*TitanGraphqlDatabaseEngineSourceGeneratorTest' \
  --no-daemon --console=plain
./gradlew titanGraphqlTranspileCommerceDatabaseEngine \
  titanGraphqlTranspileCommerceMySqlDatabaseEngine --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests '*CommerceDatabaseGraphqlEngineIT.installedEntryPointRejectsKnownInputFanOutBeforeQueueExpansion' \
  --no-daemon --console=plain
TITAN_SCRATCH_MYSQL_HOST_PORT=62002 TITAN_TEST_MYSQL_HOST_PORT=62003 \
  TESTCONTAINERS_RYUK_DISABLED=true \
  ./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests '*CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureRejectsKnownInputFanOutBeforeQueueExpansion' \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests '*CommerceDatabaseGraphqlEngineIT.installedEntryPointMatchesPortableExpectedResultCorpus' \
  --no-daemon --console=plain
TITAN_SCRATCH_MYSQL_HOST_PORT=62002 TITAN_TEST_MYSQL_HOST_PORT=62003 \
  TESTCONTAINERS_RYUK_DISABLED=true \
  ./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests '*CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureMatchesPortableExpectedResultCorpus' \
  --no-daemon --console=plain
./gradlew test --no-daemon --console=plain
jq -e '.cases | length == 30' src/test/resources/database-engine-corpus/commerce-v1.json
jq empty docs/schema/titan.graphql.schema.json
git diff --check
```

This closes the known-input-fan-out slice, not `M2-BUDGETS` or M2. Request-wide input aggregation,
introspection-item reservation, generated-statement reservation, all-row aggregation, incremental
response charging, heterogeneous multi-projection connections, and remaining schema detail are the
next unresolved M2 work.

### Latest request-wide typed-input accounting evidence (2026-09-23)

The next cost-ledger batch makes the 128-node typed-input allowance genuinely request-wide rather
than restarting it for every variable or argument. Variable values/defaults share one validation
counter; selected literal/default arguments share a second validation counter. Argument
materialization has its own request-wide counter so repeated use of one valid large variable cannot
force an unbounded repeated walk. Each transpilable helper returns a private scalar success carrier
containing the charged count or an explicit resource-limit carrier; callers consume either carrier
before producing public GraphQL JSON. Known list cardinality is reserved before child queue growth
in both validation and materialization.

Focused JVM tests prove overflow and the exact 128-node boundary for two variables, two literal
input objects, and one large variable reused by two selected fields. The portable corpus gained an
aggregate-validation case and an aggregate-materialization case, bringing it to 32. Dual
transpilation completed in 14m19s without topology growth: 379 source-local helpers and 376 emitted
entry points per dialect, with 380 MySQL whole-request routines. Generated PostgreSQL/MySQL SQL
inspection confirms the private count carriers and early materialization fan-out rejection were
lowered. The MySQL bundle remains 19,483,353 bytes and the public procedure remains 17,760,891
bytes.

The first PostgreSQL corpus execution correctly located the materialization failure at the owning
second `filter` argument (column 162); the newly written fixture had incorrectly expected the nested
variable token (column 176). Correcting only that expected location required no retranspilation.
Fresh package staging, scratch installation, identity binding, and all 32 cases then passed on
PostgreSQL in 50.522s JUnit time and MySQL in 802.629s, with the unchanged MySQL 30-second statement
timeout. The complete JVM suite passed 634 tests in 31s. JSON/schema validation and
`git diff --check` pass.

```sh
./gradlew test \
  --tests 'io.titan.graphql.database.*' \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest
./gradlew titanGraphqlTranspileCommerceDatabaseEngine \
  titanGraphqlTranspileCommerceMySqlDatabaseEngine --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests '*CommerceDatabaseGraphqlEngineIT.installedEntryPointMatchesPortableExpectedResultCorpus' \
  --no-daemon --console=plain
TITAN_SCRATCH_MYSQL_HOST_PORT=62002 TITAN_TEST_MYSQL_HOST_PORT=62003 \
  TESTCONTAINERS_RYUK_DISABLED=true \
  ./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests '*CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureMatchesPortableExpectedResultCorpus' \
  --no-daemon --console=plain
./gradlew test --no-daemon --console=plain
jq -e '.cases | length == 32' src/test/resources/database-engine-corpus/commerce-v1.json
jq empty docs/schema/titan.graphql.schema.json
git diff --check
```

This closes request-wide typed-input validation and materialization accounting, not `M2-BUDGETS`
or M2. The next cost-ledger sequence is introspection-item reservation, generated-statement
reservation, all-row aggregation, and incremental response charging. Heterogeneous
multi-projection connections and remaining schema detail also remain before M2 can close.

### Latest pre-render introspection reservation evidence (2026-09-23)

Introspection now reserves one 512-item allowance across the complete selected operation before
rendering any generated schema inventory. The generator derives constant counts from the same
reviewed metadata used by execution and introspection: named type objects, output fields, field
arguments, input fields, enum values, interface memberships, possible-type memberships,
directives, directive arguments, and directive locations. A generated source-local helper combines
those constants with the fragment-expanded request AST. The public database routine first walks
the already bounded root selection plan to reserve every introspection root, then begins execution
only if the aggregate fits. No HTTP/JVM schema or semantic preflight was added.

Commerce's complete `__Schema.types` shape costs 329 items from 52 types, 117 fields, 70 field
arguments, 50 input fields, 34 enum values, two interface memberships, and four possible-type
memberships. A new portable case aliases that full shape twice; it rejects at the second root (658
items) with an exact source-located `RESOURCE_LIMIT_ERROR` before the first inventory is rendered.
Focused engine/generator tests validate cost selection, saturation at 513, generated preflight
placement, and generated Java compilation.

Dual transpilation passed in 15m08s with 383 source-local helpers, 380 emitted entry points per
dialect, and 384 MySQL whole-request routines. Generated SQL inspection places the reservation loop
before introspection dispatch in PostgreSQL and MySQL. Fresh package staging, scratch installation,
identity binding, and all 33 corpus cases passed on PostgreSQL in 52.533s JUnit time and MySQL in
818.973s. The full JVM suite passed 635 tests in 32s. The staged MySQL routine bundle is 19,555,799
bytes and its public procedure is 17,763,922 bytes. JSON/schema validation and `git diff --check`
pass.

```sh
./gradlew test --tests 'io.titan.graphql.database.*' \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  --no-daemon --console=plain
./gradlew titanGraphqlTranspileCommerceDatabaseEngine \
  titanGraphqlTranspileCommerceMySqlDatabaseEngine --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests '*CommerceDatabaseGraphqlEngineIT.installedEntryPointMatchesPortableExpectedResultCorpus' \
  --no-daemon --console=plain
TITAN_SCRATCH_MYSQL_HOST_PORT=62002 TITAN_TEST_MYSQL_HOST_PORT=62003 \
  TESTCONTAINERS_RYUK_DISABLED=true \
  ./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests '*CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureMatchesPortableExpectedResultCorpus' \
  --no-daemon --console=plain
./gradlew test --no-daemon --console=plain
jq -e '.cases | length == 33' src/test/resources/database-engine-corpus/commerce-v1.json
jq empty docs/schema/titan.graphql.schema.json
git diff --check
```

This closes introspection-item reservation, not `M2-BUDGETS` or M2. The next cost batch should wire
the 64-statement and 1,000-row ledgers through every generated JDBC branch together, because they
share the same root/relation/mutation execution sites. Incremental response charging follows that
batch; heterogeneous multi-projection connections and remaining schema detail also remain.

### Latest application-statement and decoded-row ledger evidence (2026-09-23)

The generated executor now owns one 64-statement allowance for all application JDBC work while
excluding only the mandatory package-identity lookup. Point reads, connection page/boundary/count
reads, mutation lock/existence/update work, and nested relation reads reserve before their generated
statement. Every generic mutation has a fixed three-statement footprint, so a selected mutation
plan containing more than 21 roots is rejected before repeated per-root payload validation and
before any effect; the individual reservations remain as defense in depth. Mutation overflow emits
a rollback-framed `RESOURCE_LIMIT_ERROR` rather than allowing earlier serial roots to commit.

The previous nested-relation-only counter is now one 1,000-row request ledger. Statically bounded
point, count, boundary, lock, and existence carriers reserve their maximum single row before the
cursor opens, preserving Titan's portable single-row JDBC transfer. Connection-page and nested
relation cursors charge immediately before projection getters or descendant statements. A focused
fixture uses ten page rows with exactly 100 relation rows apiece; it reaches the 1,001st aggregate
row through one GraphQL root and only eleven statements, isolating the row dimension from both the
per-relation 101st-row sentinel and the statement dimension. The focused statement fixture uses 22
serial update roots; both dialects reject it before effects, and the stored customer value remains
unchanged. Neither proof widens the 30-second JDBC timeout.

An initial PostgreSQL lowering attempt exposed that inserting counter logic between a single-row
`ResultSet.next()` guard and its getter breaks Titan's deliberate portable transfer shape. The
generator was corrected to pre-reserve known singular capacity instead of weakening Titan or adding
a runtime fallback. An initial 65-point-root fixture also exceeded the independent 32-direct-field
selection-plan bound, and the older 11-root relation fixture spent its deadline in repeated root
validation. Those fixtures were replaced by the dimension-isolating mutation and single-root row
probes above rather than relaxing either existing limit.

Fresh PostgreSQL and MySQL transpilation, package staging, scratch installation, identity binding,
and both focused installed gates pass. Current topology is 384 source-local helpers and 381 emitted
entry points per dialect, with 385 MySQL whole-request routines. The staged MySQL routine bundle is
19,982,695 bytes and the public procedure source is 18,189,614 bytes. A fixed-cost statement case
brings the portable corpus to 34; the full corpus passes in 60.902s on PostgreSQL and 782.363s on
MySQL. All 635 JVM tests pass in 32s. Corpus/schema JSON validation and `git diff --check` pass.

```sh
./gradlew test --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests '*CommerceDatabaseGraphqlEngineIT.installedEntryPointRejectsAggregateNestedRelationWorkAboveDatabaseRowBudget' \
  --tests '*CommerceDatabaseGraphqlEngineIT.installedEntryPointRejectsTheSixtyFifthApplicationStatement' \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests '*CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureRejectsAggregateNestedRelationWorkAboveDatabaseRowBudget' \
  --tests '*CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureRejectsTheSixtyFifthApplicationStatement' \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests '*CommerceDatabaseGraphqlEngineIT.installedEntryPointMatchesPortableExpectedResultCorpus' \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests '*CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureMatchesPortableExpectedResultCorpus' \
  --no-daemon --console=plain
./gradlew test --no-daemon --console=plain
jq -e '.cases | length == 34' src/test/resources/database-engine-corpus/commerce-v1.json
jq empty docs/schema/titan.graphql.schema.json
git diff --check
```

This closes generated-statement reservation and all-current-path row aggregation, not
`M2-BUDGETS` or M2. Incremental response charging is the remaining M2 cost-ledger dimension;
heterogeneous multi-projection connections and remaining schema detail also remain before M2 can
close.

### Latest incremental response-assembly evidence (2026-09-23)

The 16,384-character completed-response allowance is now charged by the shared transpiled JSON
assembly primitives instead of only after the complete response exists. Member append, forward item
append, reverse/prepend Relay ordering, nested relation arrays, introspection arrays, execution-error
arrays, and query/mutation root accumulation all use the bounded path. The helper returns a private
record-separator-prefixed scalar carrier as soon as the next append would exceed the allowance;
subsequent nested wrappers and appends propagate that carrier without growing their strings. Raw
GraphQL values cannot manufacture it because `jsonEscape` encodes control characters. Generated
query and mutation paths convert it immediately to the existing rollback/resource response before
later roots or descendant statements, and both public transport boundaries retain a final check as
defense in depth.

Exact JVM tests prove a 16,384-character member fragment is accepted, the next character produces
the carrier, an escaped user value containing the marker text is not misclassified, reverse item
ordering remains correct, and transport conversion exposes only `RESOURCE_LIMIT_ERROR`. Generator
shape and generated-Java attribution tests prove carrier checks in both dialects. The pre-existing
installed oversized-scalar tests pass on PostgreSQL and MySQL under the unchanged 30-second request
timeout, demonstrating that neither the private carrier nor an invalid transport frame reaches the
client.

Fresh packages transpile with 386 source-local helpers and 383 emitted entry points per dialect;
MySQL reports 387 whole-request routines. Scratch installation and identity binding pass on both
dialects. The complete 34-case corpus passes in 64.794s on PostgreSQL and 847.465s on MySQL, and all
636 JVM tests pass in 32s. The staged MySQL bundle is 20,725,341 bytes and its public procedure is
18,926,581 bytes. Corpus/schema JSON validation and `git diff --check` pass.

```sh
./gradlew test \
  --tests io.titan.graphql.database.DatabaseGraphqlErrorTest \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests '*CommerceDatabaseGraphqlEngineIT.installedEntryPointClassifiesTheFinalResponseBudget' \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests '*CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureClassifiesTheFinalResponseBudget' \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests '*CommerceDatabaseGraphqlEngineIT.installedEntryPointMatchesPortableExpectedResultCorpus' \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests '*CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureMatchesPortableExpectedResultCorpus' \
  --no-daemon --console=plain
./gradlew test --no-daemon --console=plain
jq -e '.cases | length == 34' src/test/resources/database-engine-corpus/commerce-v1.json
jq empty docs/schema/titan.graphql.schema.json
git diff --check
```

This closes `M2-BUDGETS` for the current engine surface. M5 still owns representative
performance/isolation and in-flight cancellation evidence.

### M2 v1alpha1 schema-closure decision (2026-09-23)

The final M2 inventory separated a future schema feature from a defect in the shared language core.
The v1alpha1 root record has one required concrete `type` and an optional abstract `outputType`; it
has no multiple-projection carrier. Its public JSON schema sets `additionalProperties: false`, and a
unit assertion now locks the absence of an undeclared `projections` member. Model validation already
requires the one physical type to implement or belong to the abstract output. Consequently a
heterogeneous connection cannot enter generation, cannot select a JVM fallback, and cannot have an
ambiguous database execution. Adding one later requires a versioned model contract for runtime type
discrimination, cross-source ordering and cursor stability, policy composition, total counts, and
batching; guessing those semantics inside M2 would create the model-specific architecture this plan
forbids.

The schema-detail inventory likewise found that transpiled introspection covers every metadata field
authorable by v1alpha1: object/interface descriptions and field deprecations, enum value metadata,
input-object field descriptions/defaults/deprecations, registered directive descriptions/locations,
generated argument defaults, abstract possible types, and the standard meta-schema. Root/argument
descriptions, custom-scalar URLs, repeatable custom directives, and one-of input objects are not
authorable model data and are now documented as future versioned extensions rather than vague M2
work or frontend synthesis.

With the preceding 34-case fresh dual-dialect corpus, 636-test JVM suite, complete request ledger,
and explicit schema-closure test, M2 is complete for the supported v1alpha1 surface. The next
dependency is M3: inventory every read binding, implement parent-cardinality-independent relation
batching, and prove policy/query-count behavior on both databases. Any M3/M4 extension that exposes
new language or metadata semantics must extend the shared core and reopen the applicable M2 gate
with fresh dialect evidence.

### M3 root-collection relation-batch increment (2026-09-23)

The model parser had documented `capabilities.selectable` and `capabilities.batchable` but discarded
both values. They are now retained in `TitanGraphqlRelationDocument`, included in canonical model
identity, and used by schema generation, validation, introspection, projection, and execution. A
batchable/nonselectable relation is invalid, while a nonselectable relation is absent from generated
schema and execution source. This closes a model-identity defect that otherwise made generic batch
planning impossible.

The generated executor now batches the first explicit matrix row: an argument-free, unpaginated,
selectable/batchable `MANY` relation immediately below a root collection whose local column is the
source scalar primary key. Root-page rendering writes length-prefixed parent keys and private
control-character placeholders to bounded scalar carriers. After the page cursor closes, constant
64-key prepared statements fetch children with `ROW_NUMBER() OVER (PARTITION BY join-key ORDER BY
target-primary-key)`, retaining the 101st-row per-parent sentinel. The response carrier completes
empty, populated, or propagated-null child lists by parent index, and placeholder replacement
continues to enforce the 16,384-character response limit. The target join column may also be a
public field. A second alias with a distinct selection deliberately retains the bounded independent
read until alias-plan identity can be represented without merging incompatible child plans.

The first PostgreSQL live attempt caught Titan's positional cursor-fetch invariant: labeled Java
`ResultSet` reads are lowered in read order, so the batch parent key and row ordinal must lead the
SQL projection. The generator now locks that order. MySQL transpilation then caught its cursor-loop
shape constraint; a bare `ResultSet.next()` predicate plus an immediate body guard is now emitted.
Neither correction added a JVM or HTTP scheduling path.

Fresh PostgreSQL and MySQL transpilation succeeds with 397 source-local helpers, 394 entry points,
and 398 transpiler SQL records per dialect; staged package sources contain 399 SQL files including
the identity migration. MySQL reports 398 whole-request routines. The staged MySQL SQL directory is
21,804,948 bytes and its public procedure source is 20,016,899 bytes. Scratch install verification
and binding pass for both dialects. A 65-parent fixture forces two chunks and returns one child for
every parent on both databases; the former per-parent implementation would exceed the unchanged
64-statement request allowance. The adjacent 1,001-decoded-row fixture still returns
`RESOURCE_LIMIT_ERROR` on both databases. All 639 Docker-free JVM tests pass. The checked-in demo
fixture binding was refreshed because retaining the two relation capability flags correctly changed
the normalized model identity. The large generated-source attribution test now uses a focused cyclic
model, eliminating suite-order-dependent heap exhaustion without weakening Java parse/attribution.

```sh
./gradlew test --no-daemon --console=plain
./gradlew titanGraphqlTranspileCommerceDatabaseEngine --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests '*CommerceDatabaseGraphqlEngineIT.installedEntryPointBatchesSixtyFiveParentRelationsInTwoFixedChunks' \
  --tests '*CommerceDatabaseGraphqlEngineIT.installedEntryPointRejectsAggregateNestedRelationWorkAboveDatabaseRowBudget' \
  --no-daemon --console=plain
./gradlew titanGraphqlTranspileCommerceMySqlDatabaseEngine --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests '*CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureBatchesSixtyFiveParentRelationsInTwoFixedChunks' \
  --tests '*CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureRejectsAggregateNestedRelationWorkAboveDatabaseRowBudget' \
  --no-daemon --console=plain
```

This completes only the root-collection/unpaginated-to-many batching row. M3 remains active. The
following increment completes its matrix and exact-observability prerequisites before to-one and
deeper-level batching.

### M3 capability-matrix and exact-ledger increment (2026-09-23)

`database-engine-runtime-inventory.md` now contains the executable row-by-row sequencing authority
for point keys, scalar/computed output, root Relay behavior, every relation/batch shape, policy
locations, and work observability. It orders unpaginated to-one and deeper-level work ahead of
Relay/argument-bearing/independent-alias batching because the latter must first define compatible
cursor, count, argument, policy, and error-path identities.

The generated whole-request routines retain their existing request-local statement and row ledgers.
When authenticated trusted context sets `includeExecutionMetrics: true`, completed query responses
and successful mutations add `extensions.titanExecution`; ordinary responses and preflight failures
are unchanged, and client request extensions cannot enable it. The statement value excludes the
mandatory package-identity lookup and transport SQL. The row value deliberately reports fail-closed
ledger use—including conservative carrier/sentinel reservations—not physical database telemetry.
The bounded helper rejects invalid inputs and any extension that would cross the unchanged 16,384-
character response ceiling.

Fresh PostgreSQL/MySQL transpilation passes with 398 reachable source-local helpers, 395 emitted
entry points, and 399 transpiler SQL records per dialect. Package sources contain 400 SQL files with
the identity migration; MySQL reports 399 whole-request routines. Its package-source directory is
21,821,049 bytes and the public procedure source is 20,030,231 bytes. Scratch install verification,
identity binding, and the focused 65-parent request pass on both databases. The request reports
exactly three generated application statements—one root page plus two 64-key relation chunks—and
131 row-ledger units: 65 page rows, 65 relation rows, and one conservative Relay sentinel
reservation. The initial 130-row expectation failed on PostgreSQL and was corrected rather than
weakening the existing pre-reservation.

```sh
./gradlew test \
  --tests io.titan.graphql.database.DatabaseGraphqlErrorTest \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest
./gradlew titanGraphqlTranspileCommerceDatabaseEngine \
  titanGraphqlTranspileCommerceMySqlDatabaseEngine --parallel --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests 'io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointBatchesSixtyFiveParentRelationsInTwoFixedChunks' \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests 'io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureBatchesSixtyFiveParentRelationsInTwoFixedChunks' \
  --no-daemon --console=plain
```

M3 remains active. The next implementation row was duplicate-safe unpaginated to-one batching; the
following increment completes that direct-root-collection row. Deeper selected levels follow it. A
fresh complete 640-test JVM run and 34-case dual-dialect corpus remain required after the next
bundled source increment rather than paying their installation cost after every local edit.

### M3 duplicate-safe direct to-one batch increment (2026-09-23)

The root-collection scheduler now batches eligible unpaginated, argument-free `ONE` relations as
well as the prior `MANY` subset. It accepts a reviewed public or private local join carrier and
records a parallel bounded activity carrier, so a SQL-null local key disables only its slot and is
never coerced into an invalid UUID/numeric binding. The old first-key lookup helper was deleted.

Each dialect instead joins the target table to a constant 64-row slot table—PostgreSQL `VALUES`,
MySQL `UNION ALL`—whose generated parent index is the first cursor projection. Duplicate keys
therefore produce one target row for every requesting parent with the correct edge index and error
path. To-one SQL retains at most a second row per slot; the scalar object carrier completes zero
rows as null, one row as an object, and a second row as a path-located cardinality execution error.
Missing non-null targets add the ordinary GraphQL error and bubble through the existing connection
node wrapper; nullable missing/failed targets remain local. All statement, deadline, decoded-row,
and response-assembly ledgers remain shared with the full request.

The existing 65-customer to-many fixture now also inserts 64 additional orders for customer 7 and
executes 65 order parents whose private `customer_id` values are identical. PostgreSQL and MySQL
both return customer 7 for every parent. Each query independently reports exactly three application
statements and 131 row-ledger units, proving two chunks without duplicate collapse or per-parent
SQL. All 640 Docker-free JVM tests, both transpilers, scratch installation, binding, and both installed
fixtures pass. Current topology is 402 reachable helpers, 399 entry points, and 403 transpiler SQL
records per dialect; package sources contain 404 SQL files including identity, and MySQL reports 403
routines. Its package-source directory is 23,336,440 bytes and its public procedure source is
21,532,703 bytes. The size increase is recorded rather than hidden and remains an M5 optimization
target.

```sh
./gradlew test \
  --tests io.titan.graphql.database.DatabaseGraphqlErrorTest \
  --tests io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest \
  --no-daemon --console=plain
./gradlew titanGraphqlTranspileCommerceDatabaseEngine \
  titanGraphqlTranspileCommerceMySqlDatabaseEngine --parallel --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests 'io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointBatchesSixtyFiveParentRelationsInTwoFixedChunks' \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests 'io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureBatchesSixtyFiveParentRelationsInTwoFixedChunks' \
  --no-daemon --console=plain
```

M3 remains active. Next, design the per-level work carrier for deeper selected relations, preserving
compatible-plan identity and indexed paths before extending the same batching primitive below the
root collection. Relay relation connections, argument-bearing relations, and distinct aliases stay
later because their cursor/count/argument/selection identities are not interchangeable.

### M3 deeper-level batch carrier increment (2026-09-23)

The generated executor now collects an exact execution path, private join key, activity bit, and
immediate owner index for each eligible selected relation while rendering a batched parent row. It
emits the child batch only after the parent cursor closes, then replaces a private placeholder in
the parent row before outer completion. Recursion remains bounded by the model's selection-hop
budget; a distinct alias/selection falls back to the existing bounded independent execution. The
HTTP frontend does not schedule or split any of this work.

The first PostgreSQL live run exposed a real framing defect: nested replacement changed JSON inside
the length-prefixed `br1` carrier without rewriting the encoded record length. A new transpiled
carrier-aware replacement reparses every record, requires exactly one private placeholder, rewrites
the affected length, preserves neighboring rows, and fails closed on malformed input. Focused JVM
tests cover that repair. The fresh PostgreSQL package transpiles, scratch-installs, binds, and passes
the 65-parent `customers -> orders -> customer` proof in exactly five application statements and
196 row-ledger units; the inverse duplicate-key `orders -> customer` proof remains three statements
and 131 units.

The first equivalent MySQL package also transpiled, scratch-installed, and bound, but its focused
live query exceeded the serving path's deliberate 30-second JDBC statement budget. Increasing the
timeout was rejected as acceptance evidence. That monolithic package contained 404 reachable
helpers, 401 entry points, and 405 transpiler SQL records per dialect; its generated SQL trees were
26,587,447 bytes for PostgreSQL and 25,405,621 bytes for MySQL, and their public entry points alone
were 24,776,269 and 23,120,975 bytes. Cold transpilation measured 14m41s for PostgreSQL and 14m21s
for MySQL, and MySQL required a 2 GiB Gradle heap after the default 512 MiB run failed.

The next coherent batch moved every supported query-root execution branch into a source-local
transpiled helper. The one public routine still owns the whole request: document parsing and
validation, selected-root matching, serial dispatch in one transaction, error/null propagation,
request-wide statement and row ledgers, response assembly, and transport framing remain inside the
database package. A bounded private `qr1` carrier returns only the root value, execution-error
fragment, propagated-null bit, and updated counters to that public routine. No query is split by the
HTTP/JDBC frontend and no GraphQL semantics moved out of the transpiled engine.

This split exposed two Titan compiler gaps and fixed them in Titan with focused regressions. MySQL
stored functions may perform a constant/bind-only single-row `SELECT ... INTO`; only structurally
spliced dynamic SQL remains rejected. Calls between emitted routines now omit Java
`Connection`/`DataSource` infrastructure arguments exactly as their emitted SQL signatures do,
covering static, unqualified, and supported concrete-instance helper calls on both dialects. The
adjacent helper and JDBC-lowering suites pass.

A fresh MySQL cold transpilation then succeeds with 428 source-local helpers, 425 emitted entry
points, and 429 SQL/routine records. The public procedure is 320,883 bytes (down 98.6% from
23,120,975); all 429 generated SQL files total 18,642,769 bytes, and the packaged routine migration
is 18,686,227 bytes. The cold run took 25m25s with a 2 GiB heap and updated only the one SQL caller
affected by the infrastructure-argument fix. Scratch installation, package binding, and the exact
65-parent `customers -> orders -> customer` MySQL test pass in 1m45s under the unchanged 30-second
statement limit. This closes the MySQL runtime timeout/arity defects without widening a budget.

The current-source PostgreSQL cold transpilation also succeeds with 428 helpers, 425 entry points,
and 429 SQL records. Its public entry function is 293,203 bytes, all generated SQL is 19,303,365
bytes, and the packaged routine migration is 19,346,577 bytes. Scratch installation, binding, and
the exact deeper fixture pass in 55s. All 640 Docker-free JVM tests pass in 22s. The root-helper
architecture and deeper-level behavior are therefore same-revision live-proven on both dialects.

M3 remains active for the later read-capability rows. The complete 34-case dual-dialect corpus is
still the bundled milestone gate after relation-Relay batching; do not pay another dual cold cycle
for individual edits.

### M3 relation-Relay batching target (2026-09-23)

The next batch keeps one whole GraphQL request and transaction in the public transpiled routine.
For each selected, batchable relation connection with one identical selection/argument/policy plan,
the generated root helper records parent key, activity, execution path, parent index, and one
private placeholder. It then executes fixed 64-parent slot statements after the parent cursor
closes: a partitioned page statement, an opposite-boundary statement only when the requested
`pageInfo` needs it, and an exact-count statement only when `totalCount` is selected. All SQL text,
join columns, predicates, ordering, and cursor branches remain model-generated constants; client
values are binds.

The page carrier records parent index, database row order, completed edge JSON, and opaque cursor.
It retains at most the declared page size plus one sentinel per parent, reverses backward pages
inside the transpiled engine, and derives start/end cursors and page flags per parent. Counts and
boundary flags use parallel bounded scalar carriers. The executor assembles each aliased connection
selection in request order, applies ordinary null/error propagation, and replaces exactly one
parent placeholder. Distinct aliases, selections, argument sets, or policy identities are not
coalesced; they remain on the existing bounded independent path until an explicit compatible-plan
identity represents them.

Implementation sequence: (1) bounded carrier slicing/reversal and malformed-input tests; (2) fixed
slot page SQL and bind-order generator tests on both dialects; (3) per-parent count/boundary carriers
and response assembly; (4) a 65-parent fixture proving two chunks, stable duplicate keys, forward
and backward cursors, zero-row parents, exact counts, aliases, and unchanged request ledgers; (5)
fresh dual-dialect packages and the complete corpus once for the bundled gate. HTTP code must not
parse, split, schedule, or merge these operations.

### M3 relation-Relay batch increment (2026-09-23)

The target above is now implemented for one compatible selected plan per relation field. The
generated root helper records each active parent's key, path, owner index, placeholder, and selected
field start. Count-only selections execute zero-preserving exact-count chunks. Page selections use
the fixed 64-slot partitioned page statement, retain one per-parent sentinel, reverse backward
windows inside the transpiled engine, and run the opposite-boundary statement only when the
requested `pageInfo` requires it. Equality arguments are materialized once from the validated field
plan and bound in the same static position in page, count, and boundary SQL. Returned Relay nodes
feed the existing deeper unpaginated relation carrier after the page cursor closes. The HTTP/JDBC
frontend still sends one complete envelope and performs no scheduling or response merge.

Fresh PostgreSQL and MySQL packages each contain 432 reachable source-local helpers, 429 emitted
entry points, and 433 transpiler SQL files; staged package sources contain 434 SQL files including
identity. The public whole-request entries remain 293,203 bytes and 320,883 bytes. PostgreSQL
package-source SQL is 25,075,837 bytes and its routine migration is 25,119,016 bytes; MySQL is
24,285,922 and 24,329,262 bytes. Scratch installation and binding pass on both dialects.

The installed 65-parent fixture proves the following on both databases without changing the
64-statement, 1,000-row, 16,384-character, or 30-second limits:

- count-only `orderConnection(status: OPEN)` preserves aliases and `__typename`, returns an exact
  zero for one active parent with no child, and uses three statements/131 ledger rows;
- a compact forward `first: 1` page preserves all 65 parents, returns an empty edge list for that
  zero-child parent, batches a nested `edge.node.customer`, and uses four statements/194 rows (one
  root page, two relation-page chunks, and one nested chunk because the second page chunk has no
  returned node);
- after adding a second page, backward `last: 1` returns the correct latest child and nested
  customer in five statements/260 rows;
- separate bounded one-parent windows prove edge/start/end cursor identity, forward/backward page
  flags, and the `before` opposite-boundary probe in three statements/four rows;
- the prior deeper unpaginated and duplicate-key to-one batch cases remain in the same method.

The first 65-parent page requested every cursor plus full `pageInfo` and correctly received
`RESOURCE_LIMIT_ERROR` at the fixed response budget. The proof was split into a compact
cardinality-scaling request plus small cursor/page-info requests rather than weakening the budget.
PostgreSQL then exposed the zero-parent ledger optimization (four statements, not five), and the
assertion was corrected to the observed fail-closed ledger. Current focused installed runs pass in
1m26s and 2m53s with the packages reused. All 645 Docker-free JVM tests pass in 23s.

This batch also found a Titan compiler performance defect. `ExpressionLowerer.resolveTreeType`
called `TreePath.getPath` over the complete compilation unit for every arithmetic/string type
probe, making this generated source quadratic. It now uses the existing per-run
`LoweringContext` identity path index, with `TreePathLookupDisciplineTest` preventing the hot-path
scan from returning. The focused Titan regression suite passes in 24s. An old-path PostgreSQL run
spent about 61 minutes before package installation; the indexed run produced 433 byte-for-byte
unchanged SQL files and completed transpilation, scratch install, binding, and the live test in
26m09s. The corresponding MySQL cold gate completed in 29m08s.

```sh
./gradlew :titan:titan-transpiler:test \
  --tests io.titan.transpiler.tir.TreePathLookupDisciplineTest \
  --tests io.titan.transpiler.tir.JavaToTirLowererTest --no-daemon --console=plain
./gradlew databaseEngineCommerceIntegrationTest \
  --tests 'io.titan.graphql.codegen.CommerceDatabaseGraphqlEngineIT.installedEntryPointBatchesSixtyFiveParentRelationsInTwoFixedChunks' \
  --no-daemon --console=plain
./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests 'io.titan.graphql.codegen.CommerceDatabaseGraphqlMySqlEngineIT.installedProcedureBatchesSixtyFiveParentRelationsInTwoFixedChunks' \
  --no-daemon --console=plain
```

M3 remains active. This closes the compatible relation-Relay row and proves one identical reviewed
equality-argument plan; it does not close distinct argument/selection/policy identities, the
row-level scalar/root-Relay inventory, or policy rows 9–12. Those remaining requirements are the
next cohesive implementation/evidence batch. The complete dual-dialect corpus remains the bundled
M3 exit gate rather than an inner-loop check.

### M3 explicit-plan and policy completion increment (in progress, 2026-09-23)

The current candidate replaces the one-plan relation shortcut with an AST-backed operation-plan
partition. Every selected relation occurrence contributes its selected-field start to a bounded
carrier; exact plan equality therefore includes the response key, materialized arguments, merged
child selection, source location, and static generated policy context. The transpiled executor
selects the first unprocessed occurrence, gathers only matching parent work, executes fixed
64-parent chunks, maps plan-local owners back to the global response placeholders, and repeats
until the carrier is exhausted. The same partition is used for unpaginated `MANY`/`ONE` and Relay
page/count/boundary work. No HTTP/JDBC code inspects, splits, schedules, or merges GraphQL fields.

Focused PostgreSQL package evidence already proves two distinct unpaginated aliases/selections,
two relation-Relay aliases with distinct literal/variable argument sets and child selections, and
duplicate non-primary `MANY` join keys. The exact ledgers remain parent-cardinality independent:
the two unpaginated plans use five statements/196 row-ledger units, the two Relay plans use five
statements/195 units, and the duplicate non-primary join uses two statements/nine units for three
matching children per parent. The equivalent MySQL package proof is still pending, so rows 3, 7,
and 8 remain open in the milestone ledger.

Generated authorization preflight now evaluates root, type, selected field, relation, and relation
target-type reject policies before the first application statement, including the Relay relation
validation path that formerly returned before its policy guard. Authorization errors retain the
selected field's source location and stable `AUTHORIZATION_ERROR` code. Because v1alpha1 policies
are request-reject policies, not per-row masking policies, this pre-I/O failure deliberately has no
fabricated row-index execution path. The protected-output fixture is separate from the reviewed
Float-filter fixture, so field-selection authorization and typed filter coverage are each tested
without silently changing the declared public filter schema. Introspection remains one trusted
top-level gate: v1alpha1 does not claim actor-shaped schema redaction.

The refreshed read inventory is explicit:

| Model/type | Scalar/computed output inventory | Read roots and relation inventory | Current proof state |
| --- | --- | --- | --- |
| Demo `Article` | `id`, `title`, row-local computed `titleLength` | point `article`, Relay `articles`; `author` to-one and `comments` Relay | Existing PostgreSQL/MySQL installed tests select all outputs and traverse both relation shapes. |
| Demo `User`, `Comment` | `User.id/name/email`; `Comment.id/body` | nested `Article.author`, `Article.comments`, and `Comment.author` | Existing dual-dialect installed tests cover the unprotected and protected selections and nested traversal. |
| Commerce `Customer` | `id`, integral/string-backed IDs, `sortRank`, strings, Boolean, nullable values, declared enums, nullable enum, Int, Float, and nullable protected Boolean | integral/composite/ID/abstract point roots; four Customer Relay roots; `rankPeers`, `orders`, `orderConnection`, `openOrderConnection` | One installed PostgreSQL method now selects every field. Root Relay tests cover first/last, after/before, null/empty and malformed cursors, exact count, page flags, integral/string/UUID/tuple orderings, filters, defaults, aliases, and abstract nodes. Refreshed MySQL proof is pending. |
| Commerce `Order` | `id`, `reference`, `status` | Relay `orders`; `customer` and `nullableCustomer` to-one | Existing dual-dialect point/nested/Relay tests cover every output and both nullability contracts. |
| Commerce alternate projections | `CustomerByNodeId.nodeId/externalId/name`, `CustomerByExternalId.nodeId/externalId/name`, `Country.code/name`, `ApiClient.id/sortRank/label`, `InventoryItem.warehouse/sku/quantity` | string-backed and integral IDs, UUID point/Relay, string point, and composite string key | The expanded installed method selects each projection field; refreshed PostgreSQL/MySQL proof is pending. |

The planned evidence bundle completed on the current source snapshot. `./gradlew test` passed
646/646 tests in 23 seconds. The unrelated demo package passed both PostgreSQL and MySQL direct and
HTTP-adapter tests; the MySQL direct harness was corrected to honor the public transport contract
(`START TRANSACTION`, drain every procedure result, apply the returned outcome, then
`COM_RESET_CONNECTION`) before reusing a session. The final filtered Commerce gates ran in parallel:

```text
./gradlew databaseEngineCommerceIntegrationTest \
  --tests '*installedEntryPointServesUnrelatedSchemaKeysPoliciesAndLiveRows' \
  --tests '*installedEntryPointBatchesSixtyFiveParentRelationsInTwoFixedChunks' \
  --tests '*installedEntryPointMatchesPortableExpectedResultCorpus'
BUILD SUCCESSFUL in 27m 5s

./gradlew databaseEngineCommerceMySqlIntegrationTest \
  --tests '*installedProcedureServesUnrelatedSchemaKeysPoliciesAndLiveRows' \
  --tests '*installedProcedureBatchesSixtyFiveParentRelationsInTwoFixedChunks' \
  --tests '*installedProcedureMatchesPortableExpectedResultCorpus'
BUILD SUCCESSFUL in 36m 12s
```

Both final packages contain 436 reachable source-local helpers and 433 entry points; MySQL reports
437 whole-request routines. Each transpiler updated one SQL file with 436 unchanged, and each fresh
scratch install and binding passed before the live methods. These methods prove the explicit plan
partitions and exact ledgers above, the refreshed output/root and policy inventory, and all 34
portable corpus cases on both dialects.

This closes the explicit-plan/policy increment, not M3. A post-gate contract reconciliation found
that `articles.filter` in the database package still omits the model-declared computed
`titleLength` and one-hop `authorName` fields and does not expose `and`/`or`/`not`; `ArticleOrderBy`
does expose `titleLength` and `authorName`, but those branches currently reject execution. That is
retained baseline behavior in the compiled carrier contract and an explicit Phase 3 requirement,
so it cannot be reclassified away after a green narrower corpus. Dual-dialect acceptance requests
for DNF composition, computed/relation predicates, filter-plus-order, and computed/relation order
were added before the next implementation batch. Snapshot/cross-tenant behavior and a physically
renamed model remain the other M3 exit rows. M3 therefore remains active.

### M3 transpiled filter and computed/relation order increment (2026-09-23)

The database engine now compiles generated filter input into a bounded three-group-by-three-term DNF
carrier entirely inside the transpiled runtime. `and`, `or`, and `not` lower through an iterative scalar
task stack with De Morgan normalization; `in` expands within the same fixed bound. Generated static SQL
selects each reviewed predicate by numeric metadata selector and supports local, computed, and one-hop
relation expressions without request-derived SQL text. Explicit null is limited to equality/inequality
semantics, and over-budget or malformed plans fail before application SQL.

Generated tuple ordering now covers the demo model's local `id`/`title`, computed `titleLength`, and
one-hop `authorName` paths in page, total-count, opposite-boundary, and cursor continuation statements.
One-hop expressions are correlated static scalar subqueries. The relation cursor path defect found by
the boundary test was corrected by encoding reviewed dotted paths with the existing UTF-8 Base64url
codec; local path encodings remain byte-identical.

The larger generated method crossed the JVM bytecode ceiling, so schema-specific cursor validation was
split into a bounded scalar helper. Generated JDBC binder helpers remain ordinary Java helpers for JVM
execution but are compile-time-inlined by Titan into the owning prepared-statement state: no JDBC handle
is emitted as an SQL routine parameter. A Titan regression test proves the binder is not emitted, its
locals are collision-free, its scalar dependencies remain reachable, and its values become bound SQL
parameters.

Focused evidence on the resulting source snapshot:

```text
./gradlew compileDatabaseEngineJava
BUILD SUCCESSFUL

./gradlew titanGraphqlTranspileDatabaseEngine titanGraphqlTranspileMySqlDatabaseEngine --parallel
BUILD SUCCESSFUL (444 reachable helpers, 441 entry points per dialect; 445 MySQL whole-request routines)

./gradlew titanGraphqlVerifyDatabaseEngineInstall titanGraphqlVerifyMySqlDatabaseEngineInstall --parallel
BUILD SUCCESSFUL in 59s

./gradlew test --tests 'io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorTest' \
  --tests 'io.titan.graphql.database.DatabaseGraphqlFilterPlanTest' \
  :titan:titan-transpiler:test --tests 'io.titan.transpiler.tir.JdbcInlineBinderHelperTest' --parallel
BUILD SUCCESSFUL in 10s

./gradlew databaseEngineIntegrationTest \
  --tests 'io.titan.graphql.codegen.DatabaseGraphqlEngineIT.installedEntryPointExecutesPointReadVariablesAliasesAndLiveData'
BUILD SUCCESSFUL in 33s

./gradlew databaseEngineMySqlIntegrationTest \
  --tests 'io.titan.graphql.codegen.DatabaseGraphqlMySqlEngineIT.installedProcedureReturnsOneCompletedGraphqlResponseResultSet'
BUILD SUCCESSFUL in 1m 10s
```

The live methods prove Boolean composition, computed/relation predicates, computed/relation ordering,
filter-plus-order, exact total count, and `after` continuation with opposite-boundary page information.
This closes the filter/order compatibility family. M3 remains active only for snapshot/cross-tenant
isolation, physically renamed-model genericity, and the final consolidated dual-dialect gate.

### M3 isolation, genericity, and performance exit gate (complete, 2026-09-23)

The thin database client now establishes `TRANSACTION_REPEATABLE_READ` before the one whole-request
entry-point call and restores the connection's original isolation afterward. The focused boundary
test proves the isolation and autocommit transition order, exact one-call envelope forwarding, and
commit framing. The installed PostgreSQL and MySQL thin-adapter methods prove the real drivers accept
that boundary. Together with page, count, boundary, and nested relation work executing inside the same
database-owned transaction, this closes the request-snapshot row without moving query classification
to HTTP.

The Commerce fixture now has a fail-closed string `tenantIsolation` context predicate over a physical
`tenant_key`. Installed tests on both dialects prove tenant A sees only row 7, tenant B sees only row 8,
and an enabled filter with no trusted tenant value returns no rows or count. The compatibility model
adapter already carried a string filter type but rejected it at document adaptation; that false gate
was corrected without adding schema-specific dispatch. The generated connection filter's separate SQL
value selection was also corrected to honor reviewed integral `ID` storage, matching its existing
decoder and JDBC binder and preventing PostgreSQL `BIGINT = TEXT` predicates.

`renamed-vault.titan.graphql.yaml` deliberately renames the root, type, schema, table, fields, and
columns. Its generator regression produces and javac-analyzes both dialect sources, requires the
renamed identifiers, and rejects leakage of the maintained Article/Customer/Commerce fixture names.
This is the genericity inspection requested by the Phase 3 row; it does not claim the broader legacy
deletion work assigned to M6.

The first Commerce transpilation exposed two Titan complexity defects rather than acceptable build
cost: repeated javac declaration lookup and full null-state map copying at every lexical block. Titan
now uses its per-run declaration index in `DslQueryLowerer`, while `NullAnalysisPass` saves/restores
only shadowed declarations and lets outer assignments propagate in place. With a finite 2 GiB Gradle
heap, the 473-helper/470-entry-point Commerce source transpiles in approximately 55 seconds for
PostgreSQL and 52 seconds for MySQL; MySQL emits 474 routines. One minute per dialect action is the
performance ceiling for this scale. The 192.43-second combined package command includes Gradle startup,
two generations, two sequential transpiles, staging, identity hashing, and packaging and therefore is
not the transpilation measurement.

Exit evidence on the final source snapshot:

```text
./gradlew test --no-daemon --console=plain
BUILD SUCCESSFUL in 26s (654 tests)

./gradlew :titan:titan-transpiler:test \
  --tests 'io.titan.transpiler.tir.NullAnalysisPassTest' \
  --tests 'io.titan.transpiler.tir.TreePathLookupDisciplineTest' --no-daemon --console=plain
BUILD SUCCESSFUL in 13s

./gradlew titanGraphqlPackageCommerceDatabaseEngine \
  titanGraphqlPackageCommerceMySqlDatabaseEngine --parallel --no-daemon --console=plain
BUILD SUCCESSFUL (PostgreSQL transpile ~55s; MySQL transpile ~52s)

./gradlew titanGraphqlBindCommerceDatabaseEnginePackage --no-daemon --console=plain
BUILD SUCCESSFUL in 1m11s (fresh PostgreSQL scratch install and bind)

./gradlew titanGraphqlBindCommerceMySqlDatabaseEnginePackage --no-daemon --console=plain
BUILD SUCCESSFUL in 1m34s (fresh MySQL scratch install and bind)
```

The final live gate selected five exact methods per dialect: the thin adapter/transaction boundary,
the full unrelated-schema read/policy/filter/order inventory, the 65-parent fixed-chunk batching proof,
the independent 34-case corpus, and tenant isolation. Four methods per dialect passed in the bundled
run. That run found two stale expectations in the inventory method: one-hop relation ordering now
succeeds, and a nine-value `in` filter reaches the newer 3x3 DNF carrier bound before the older list
limit. After aligning those assertions with the implemented contract, the complete PostgreSQL method
passed in 45 seconds and the complete MySQL method passed in 2m24s; production sources and installed
packages were unchanged. M3 is complete. Execution stops here at the requested milestone boundary;
M4 is the next unresolved milestone.

### Clean M3 checkpoint preparation (complete, 2026-09-23)

The M3 implementation is now checkpoint-ready rather than an uncommitted worktree. Its required
dependency extensions were independently reviewed, rebased onto current upstream `main`, validated,
and published as Titan `aa068aa` (`feat: expand transpiler support for GraphQL engines`) and Titan-DSL
`906f013` (`feat: add portable text intrinsics`). Titan's complete multi-project `test` graph passed
after the rebase (1,576 test cases; `BUILD SUCCESSFUL in 2m5s`), and Titan-DSL's module test gate passed
after its rebase (`BUILD SUCCESSFUL in 18s`). The root project then passed all 654 JVM tests against
those published dependency commits (`BUILD SUCCESSFUL in 41s`).

The checkpoint hygiene review found no untracked editor backups, patch rejects, logs, or generated
build outputs eligible for commit. Every new root file is part of the database engine, thin frontend,
test corpus, artifact identity, configuration, or architecture documentation. Credential/path matches
are limited to release-check regexes, deployment placeholders, empty production configuration, and
explicit test fixtures; no private credential or workstation path is included. The checkpoint is
rebased onto the current root `origin/main` agent-standard synchronization and remains intentionally
unpublished at the root level.

The history portion of the release gate identified a personal author email in the agent-standard
synchronization that had entered `origin/main` after the prior public-history cleanup. The authorized
cleanup replaced that merge segment with equivalent commit `5c92fd6`, whose author and committer use
`rbilleci@users.noreply.github.com`, and removed the obsolete remote synchronization branch. Tree-hash
comparison proved that the rewrite changed history metadata and topology without changing the
checkpoint contents. The release gate then validated the rewritten reachable history and the exact
dependency pins. M4 through M7 remain unresolved; resumption starts with explicit custom mutation
migration, not further expansion of the completed M3 read surface.
