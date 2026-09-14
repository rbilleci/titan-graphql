# Titan GraphQL Query Contract

Status: implemented compiled-query contract plus explicit custom-mutation boundary.

This document states the behavior exposed by the schema-driven runtime. It is deliberately narrower
than the full GraphQL specification. Parser support does not imply that every parsed shape can be
lowered; unsupported plans return GraphQL errors and never fall back to handwritten or demo reads.

## Contract Principles

- The reviewed projection model is the source of schema, policy, and lowering semantics.
- Generated SDL and introspection expose only modeled capabilities.
- Production reads use deterministic Titan-generated carriers and bound values.
- A feature is part of the compiled contract only after live PostgreSQL and MySQL proof.
- Policy is applied before protected data access and repeated in generated carriers where specified.
- Unsupported shapes fail closed without switching execution mode.
- Application writes are explicit registered commands, never inferred table CRUD.

## HTTP Transport

`POST /graphql` accepts queries and registered custom mutations with
`Content-Type: application/json`. The request envelope is:

```json
{
  "query": "query Example($id: ID!) { node(id: $id) { id } }",
  "operationName": "Example",
  "variables": { "id": "node-id" },
  "extensions": {}
}
```

`query` is required. `operationName` is required for a document containing multiple operations.
`variables` and `extensions`, when present, must be JSON objects. Unknown envelope fields and an
unsupported POST media type are rejected. Responses use
`application/graphql-response+json` when accepted, with `application/json` fallback.

`GET /graphql` accepts the same query document plus optional `operationName`, `variables`, and
`extensions` query parameters, but only for query operations. Mutation and subscription operations
are rejected at the transport boundary with `UNSUPPORTED_OPERATION`.

Every response names the selected execution mode in `X-Titan-Execution-Mode`. Compiled and legacy
SQL responses also include `X-Titan-Deployment-Fingerprint`; unreadable binding metadata surfaces as
`unavailable`, while request execution still fails closed.

Caller-supplied `X-Titan-*` context headers are ignored by default. With no trusted gateway context,
the actor role is blank. See [../SECURITY.md](../SECURITY.md).

## Operations and Documents

The parser and validator support:

- shorthand and explicit query operations;
- named and anonymous operations;
- selected operations in multi-operation documents;
- variables and default values;
- field and relation aliases;
- named and inline fragments with cycle/type-condition validation;
- `@include(if:)` and `@skip(if:)`; and
- parse locations and stable GraphQL-shaped error extensions.

Subscriptions and unknown executable directives are rejected. A registered descriptor-backed
mutation may execute only over POST and must select exactly one top-level mutation field.

Duplicate response keys must be merge-compatible. Scalars reject selection sets; object and relation
fields require them. Output uses response aliases without changing schema identity or policy lookup.

## Scalars and Nullability

The model/runtime scalar boundary includes `Int`, `Long`, `Float`, `Boolean`, `String`, `ID`, `UUID`,
`Date`, `DateTime`, and `Timestamp` where the backing schema and model declare them. Literal/variable
coercion is type checked before planning. `ID` remains a GraphQL identity even when a numeric column
backs it.

Field nullability in SDL, execution, and introspection follows the reviewed model. A SQL `NULL` for a
nullable field is rendered as JSON `null`; the runtime does not replace it with a scalar default.
Nullable cursor and custom sort keys are not in the portable compiled contract and are rejected by
model validation.

Generated filter/order variables are resolved from the active schema rather than fixed model names.
Registered mutation input-object variables are resolved from their descriptor input type. The unrelated
commerce proof exercises `CustomerFilter`, `CustomerOrderBy`, and `RenameCustomerInput` JSON variables on
both dialects.

Custom mutation inputs currently support `String`, `ID`, `Boolean`, and `Int`. Mutation payloads are
direct scalar-like fields declared by the descriptor.

## Roots and Keys

Only declared roots appear on `Query`. A relation does not automatically become a root.

Point roots support reviewed `Int`, `Long`, `String`, `ID`, and `UUID` keys and explicit
multi-argument composite keys. Each key argument binds to a reviewed column and is passed as a typed
carrier parameter. Inference preserves composite key candidates but never silently publishes one
component as the public key.

## Relay Connections

Declared collection roots and collection relations use Relay-style connections:

```graphql
type ExampleConnection {
  edges: [ExampleEdge!]!
  pageInfo: PageInfo!
  totalCount: Int!
}
```

Supported arguments are `first`, `after`, `last`, and `before`, subject to the model's maximum page
size. Cursors are opaque and bind projection identity, ordering, direction, value, and tie breaker.
Malformed or mismatched cursors are rejected. Forward continuation, backward windows,
`hasNextPage`, `hasPreviousPage`, start/end cursors, and exact visible-row `totalCount` are live-proven
on both dialects.

Counts observe the same row/context/client filters and relation policy as edges. Hidden rows do not
inflate counts. A model that declares no safe count strategy does not expose `totalCount` for that
shape.

## Filtering

Only reviewed filter paths and operators appear in generated input objects. The compiled path
supports declared equality/inequality, `in`, null checks, numeric comparison, and escaped string
matching operators. Client predicates compose with declared context filters.

A single local `in` predicate uses static arities through 16. Boolean `and`, `or`, and `not`,
filter-plus-order, and a reviewed non-null to-one relation filter hop use a static three-OR-group by
three-AND-term DNF carrier plan. Larger plans, to-many paths, and deeper relation paths fail closed.

## Ordering

Only reviewed order paths appear in generated input objects. The compiled path supports a single
custom key at a time over:

- a local non-null scalar;
- a reviewed deterministic row-local computed scalar; or
- one non-null to-one relation hop.

Every ordering includes a non-null stable tie breaker in both `ORDER BY` and cursor predicates.
Ascending and descending carrier variants are generated statically; client direction values never
become SQL fragments. Multiple simultaneous custom keys, nullable order keys, to-many paths, and
deeper hops are rejected.

## Relations and Batching

Declared to-one and to-many relations can appear beneath point or collection roots. A to-many
relation may expose either a direct collection or a Relay connection with pagination and exact
counts according to its capabilities.

Relations immediately beneath collection roots use fixed generated batch arities through 64 rather
than one read per parent. Larger parent sets are chunked. Nested relations repeat one batch per
selected level within the model's selection-hop budget. Policies and context filters apply to each
target set.

Relation connection windows are assembled from ordered batch rows after reviewed local integer
equality arguments are applied inside the carrier. SQL-side per-parent limiting is an optimization
boundary; the current behavior remains bounded by parent and relation page limits and preserves
connection semantics.

## Computed Fields

The compiled path supports reviewed deterministic row-local SQL templates whose placeholders name
declared required columns. A computed field declares GraphQL type, nullability, select/filter/sort
capabilities, sensitivity, determinism, and cost class. Unsafe templates, relation-dependent
expressions, and Java-only expressions fail validation/generation for compiled use.

## Policies and Context Filters

The named policy language supports `allowAll`, `denyAll`, `adminOnly`, `authenticated`,
`roleEquals:<role>`, and `roleIn:<role,...>`. Multiple attached policies are conjoined.

- Root policies reject before I/O and gate root carriers.
- Type policies filter root rows, exact counts, and relation targets.
- Field policies reject selections and guard protected projections.
- Relation policies reject selections, guard join keys, and gate direct/batch relation carriers.
- Context filters bind named request-context values and fail closed when required values are absent.

Policy evaluation uses schema field names, never aliases. Introspection is enabled only through
trusted request context and exposes the stable deployed schema rather than actor-shaped variants.
Unauthorized data selection is rejected rather than returned as partial masked GraphQL data; SQL
guards are defense in depth for direct carrier access.

## Errors and Limits

Errors use the GraphQL top-level shape and stable extension codes including `PARSE_ERROR`,
`VALIDATION_ERROR`, `AUTHORIZATION_ERROR`, and `UNSUPPORTED_OPERATION`. Parser errors include source
locations. Database-mode initialization/deployment failures become descriptive 503 GraphQL bodies at
the HTTP boundary.

Model page sizes, relation page sizes, static `in`/filter/batch arities, declared hop budgets, and
parser/validator constraints bound supported work. No raw query text, credentials, trusted headers,
or sensitive variables should be logged.

## Introspection

When trusted context enables introspection, the runtime supports the bounded schema/type/field/input
metadata represented in the conformance matrix, including wrapper types, arguments, input fields,
enum values, default-value absence, and scalar nullability. When disabled, `__schema` and `__type`
are rejected. `__typename` remains part of ordinary execution where supported.

Generated SDL and introspection are derived from the same adapted model and registered mutation
descriptors. They are artifacts, not separate sources of semantics.

## Custom Mutations

Compiled application schemas have no mutation root by default. Explicit providers may add reviewed
command descriptors and injected handlers. The runtime owns input validation, descriptor role checks,
dispatch, payload selection/aliases, error shape, and audit-event delivery; the handler owns domain
transactions, idempotency, persistence, rollback, and correctness-critical audit.

See [custom-mutations.md](custom-mutations.md). Generated CRUD, nested writes, SQL-transpiled
application handlers, and subscriptions are out of scope.

## Execution Modes

`compiled` is the default production application path. `jdbc` exposes a smaller generic
diagnostic/reference subset. `java` is the in-memory demo/reference kernel. `sql` is the isolated
historical whole-request demo proof. There is no cross-mode fallback.

Production package generation includes only model-generated carriers. The legacy kernel has its own
package and test task and is not part of compiled serving.

## Conformance

[query-contract-conformance.md](query-contract-conformance.md) records parser and legacy equivalence
coverage. The authoritative production proof is `compiledSchemaIntegrationTest`, which independently
generates, packages, installs, binds, and serves the demo and commerce models on PostgreSQL and
MySQL. Unsupported shapes listed above are acceptance boundaries, not silent roadmap promises.
