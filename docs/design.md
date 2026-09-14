# Titan GraphQL Design

Status: implemented pre-1.0 architecture and verified support boundary.

## Purpose

Titan GraphQL exposes reviewed database projections through GraphQL without handwritten read
resolvers or schema-specific query dispatch. Titan codegen supplies physical metadata, a reviewed
model controls public exposure and policy, Titan GraphQL generates static read carriers, and Titan
compiles those carriers into PostgreSQL and MySQL routines.

The primary path is:

```text
Titan codegen schema.json
  -> conservative projection draft
  -> reviewed titan.graphql.yaml
  -> generic parser, validator, and read planner
  -> deterministic generated Titan DSL carriers
  -> Titan transpilation and package/install verification
  -> model/package semantic binding
  -> one generic compiled runtime
```

Custom application mutations are the intentional code extension point. Table CRUD is never inferred
from read exposure.

## Sources of Truth

- The database catalog is the source of physical tables, columns, keys, and foreign keys.
- The reviewed model document is the source of public roots, fields, relations, computed fields,
  filters, ordering, pagination, context filters, and named policies.
- Generated Java, SQL, SDL, introspection JSON, inventories, and bindings are reproducible artifacts.
  They are not edited as semantic sources.
- The package binding identifies the exact normalized model and Titan package that may run together.

Inference is deliberately conservative. It preserves scalar and key metadata but does not
automatically publish roots, relations, or sensitive columns.

## Runtime Components

The application boundary is split into schema-independent components:

- `GraphqlLexer` and `GraphqlParser` parse the supported document language.
- `GraphqlValidator` validates operation shape, arguments, fields, relations, policies, limits, and
  introspection against the adapted model.
- `GraphqlReadPlanner` creates logical root, count, relation, and batch reads.
- `TitanGraphqlRoutineSourceGenerator` deterministically generates static, model-bound Titan DSL
  carriers. It contains no application fixture registry.
- `TitanCompiledGraphqlDataModel` maps validated plans to inventory-resolved carrier entry points,
  invokes them, normalizes dialect result shapes, batches nested relations, and renders GraphQL data.
- `TitanGraphqlRoutineInvoker` verifies installed model attestation and invokes only routines from
  the bound object inventory.
- `GraphqlExecutionEngine` selects the configured mode and initializes it once with fail-closed
  configuration behavior.

The generic production classes and carrier generator contain no demo type, root, or table dispatch.
A source guard and generated-only package inventory tests enforce that boundary.

## Generated Carrier Contract

Generation uses only reviewed identifiers and parameterized values. For supported models it emits:

- typed and composite point-root carriers;
- forward and backward root page carriers with stable value/tie-breaker cursors;
- exact visible-row count carriers;
- static filter and reviewed ordering variants;
- direct and fixed-arity batch relation carriers;
- reviewed row-local computed expressions;
- root, row, field, and relation policy guards; and
- a semantic-hash attestation routine.

PostgreSQL carriers return JSONB arrays from functions. MySQL carriers return open result sets from
procedures. The invoker hides that transport difference from the planner and renderer.

No client input becomes a SQL identifier or SQL fragment. Client values are bound parameters, and
the validated plan selects among generated inventory entries. Unsafe identifiers, templates,
policies, and unsupported plan shapes fail before database access.

## Artifact and Deployment Integrity

`titanGraphqlGenerateRoutines` validates the model and emits deterministic Java. `titanPackage`
produces dialect migrations, rollback SQL, an artifact manifest, an object inventory, and an install
plan. `titanVerifyInstall` installs the package into scratch PostgreSQL and MySQL databases and
checks object/signature drift. `titanGraphqlBindPackage` records a deterministic binding over:

- the canonical model semantic hash;
- the Titan artifact id and manifest hash;
- the source-input hash; and
- the reviewed routine inventory.

Compiled startup verifies the local binding before opening the serving path. Every read attests the
installed model hash before calling a carrier. Missing, stale, mismatched, or wrongly installed
artifacts are rejected; no alternate runtime is selected as fallback. Compiled and legacy SQL HTTP
responses expose the deployment fingerprint.

## Supported Compiled Read Shapes

The verified compiled boundary includes:

- `Int`, `Long`, `String`, `ID`, UUID, and explicit composite point keys;
- scalar output with declared GraphQL type and nullability preserved;
- Relay root connections with forward continuation, backward windows, stable opaque cursors,
  page-info fields, limits, and exact visible counts;
- declared scalar filters, bounded Boolean filter composition, and a reviewed non-null to-one
  relation filter hop;
- reviewed local scalar/computed ordering and a reviewed non-null to-one relation order hop;
- direct to-one/to-many relations and Relay relation connections below point or collection roots;
- fixed-arity nested relation batching, recursively once per selected relation level;
- reviewed row-local computed SQL templates; and
- aliases, variables, fragments, supported directives, and bounded introspection.

The generator uses fixed `in` and batch arities and a static filter-plan budget so the set of SQL
entry points remains finite and reviewable. Exceeding a declared budget returns an explicit GraphQL
error.

Nullable scalar selection and introspection are supported. Nullable cursor or custom sort keys are
rejected during model validation because portable stable null ordering is not yet part of the
cross-dialect contract.

## Authorization

Authorization is applied before protected data is read and repeated at the database carrier
boundary where appropriate:

- root policies reject the operation before I/O and gate root carriers;
- type policies filter roots, exact counts, and relation targets;
- field policies reject unauthorized selections and guard protected SQL projections;
- relation policies reject unauthorized selections, guard projected relation keys, and add an allow
  predicate to direct and batch carriers; and
- context filters fail closed when required context is absent and compose with client predicates.

The reviewed named-policy language is intentionally small: `allowAll`, `denyAll`, `adminOnly`,
`authenticated`, `roleEquals:<role>`, and `roleIn:<role,...>`. Multiple rules are conjoined. The
application database identity must be the only caller allowed to execute generated routines because
their boolean policy parameters are decisions from the trusted application boundary, not a database
authentication protocol.

Aliases never change authorization lookup: policy checks use schema field identities, while response
keys affect output only. Tests cover protected aliases, relation batching, exact counts, root gates,
row gates, and introspection behavior.

## N+1 Boundary

Relations under collection roots are grouped into fixed-size carrier calls. A page larger than one
carrier arity is chunked, so read count grows with the number of chunks, not the number of parents.
Nested selected relations repeat this once per level. Relation connection windows are currently
assembled in memory from ordered, policy-filtered batch rows; reviewed local integer equality
arguments are applied in SQL before counts and windows. SQL-side per-parent limiting is an
optimization boundary, not a correctness dependency.

## Mutations

Compiled application schemas publish no mutation root by default. Applications may register explicit
`GraphqlMutationDescriptor` and `GraphqlMutationCommandHandler` pairs through
`GraphqlApplicationMutationProvider`. The shared executor owns operation selection, input coercion,
authorization, exact command dispatch, scalar payload selection, GraphQL error shape, and audit-event
delivery. The handler owns domain validation, persistence, transaction, idempotency, rollback, and
any correctness-critical transactional audit/outbox work.

`/admin/graphql` is a separate management plane with file-backed development state or the durable
Titan GAP-006 JDBC store. Application mutations execute only over POST; GET is query-only. See
[custom-mutations.md](custom-mutations.md) and
[mutation-runtime-lowering-boundary.md](mutation-runtime-lowering-boundary.md).

## Execution Modes

| Mode | Purpose | Production role |
| --- | --- | --- |
| `compiled` | Reviewed model plus installed generated Titan carriers | Default application read path |
| `jdbc` | Direct generic Titan DSL/JDBC adapter for a smaller plan subset | Diagnostic/reference path |
| `java` | In-memory demo reference runtime | Tests and compiler comparison |
| `sql` | Historical whole-request demo kernel | Isolated equivalence proof only |

The production `titanPackage` output contains generated carriers only. The fixed
`DemoBlogTitanGraphqlFunctions` kernel is built into a separate legacy package solely by
`legacySqlIntegrationTest`; compiled mode cannot dispatch to it.

## State and Lifecycle

Application rows and installed routines live in the database. The reviewed model and bound package
are immutable release inputs. Generated source, SQL, inventories, verification output, SDL, and
introspection are reproducible build output. Parsed plans and relation batch caches are request or
process-local and are never correctness state.

Configuration and provider registrations are snapshotted at runtime initialization. Duplicate
mutation names/commands, missing handlers, orphan handlers, invalid models, missing bindings, and
attestation mismatches fail initialization or the request explicitly. The compiled runtime holds no
mutable schema-specific registry.

Management state is file-backed and single-process by default. The JDBC management mode persists
transaction, idempotency, audit, and product journal state and is required for multi-process or
restart-durable administrative workflows. See [operations.md](operations.md).

## Verification Boundary

The demo blog and unrelated commerce models are generated, transpiled, packaged, installed, bound,
and served independently on PostgreSQL 16 and MySQL 8.4. Live tests mutate source data and verify
point roots, connections, counts, cursors, relations, batching, filters, ordering, policies,
computed fields, nullable output, custom mutations, artifact mismatch failures, and fresh-engine
visibility. The legacy 97-case Java/SQL corpus remains a separate Titan transpiler equivalence test.

The checked-in local release gate is authoritative because the project intentionally has no hosted
GitHub Actions workflow. See [verification.md](verification.md).

## Explicitly Unsupported

The following fail closed rather than use handwritten or dynamic read SQL:

- multiple simultaneous custom order keys;
- nullable cursor or custom sort keys;
- to-many or multi-hop filter/order paths;
- filter expressions beyond the static carrier budget;
- row-value policy expressions beyond named gates and declared context filters;
- arbitrary computed SQL or client-defined resolvers;
- generated CRUD, nested write graphs, SQL-transpiled application handlers; and
- subscriptions.
