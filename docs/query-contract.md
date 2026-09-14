# Titan GraphQL Query Contract

Status: reference contract for the bounded demo surface.

This document defines the target query contract for the next Titan GraphQL expansion phase. It intentionally excludes mutations and subscriptions from the supported execution surface for now.

## Contract Principles

- Titan GraphQL is a projection-model-driven query execution adapter, not a general GraphQL server replacement.
- The projection metamodel is the source of truth. GraphQL SDL, validation rules, planner capabilities, and SQL lowerers are derived from it.
- A lightweight Quarkus HTTP frontend should expose the external GraphQL endpoint and bridge requests into Titan-lowered functions/procedures.
- A feature is production-supported only when Java-mode and SQL-mode both support it and equivalence tests prove consistent behavior.
- Parser support and planner support are separate: the parser may recognize more GraphQL syntax than the planner accepts.
- Unsupported features must fail validation with GraphQL-shaped errors instead of being ignored.
- Generated SDL is the primary external contract once the contract is closed.

## Quarkus HTTP Frontend Contract

The first external frontend should be a lightweight Quarkus service exposing one GraphQL endpoint:

- `POST /graphql` for all query execution.
- Optional `GET /graphql` for query operations only, primarily for compatibility, diagnostics, and cache-friendly clients.
- No mutation or subscription execution.

The JSON request envelope should follow the GraphQL-over-HTTP convention:

```json
{
  "query": "query Example($id: ID!) { node(id: $id) { id } }",
  "operationName": "Example",
  "variables": {
    "id": "node-id"
  },
  "extensions": {}
}
```

Request rules:

- `query` is required and contains the complete GraphQL document string.
- `operationName` is optional for single-operation documents and required when the document contains multiple operations.
- `variables` is optional and must be a JSON object when present.
- `extensions` is optional and reserved for Titan-specific request metadata, persisted query metadata, tracing opt-ins, and future transport extensions.
- Additional top-level request fields are rejected in strict mode.
- `Content-Type: application/json` is required for `POST`.
- `Accept: application/graphql-response+json, application/json;q=0.9` is the recommended client header.
- Responses should prefer `application/graphql-response+json` and may fall back to `application/json`.

The Quarkus frontend is responsible for HTTP parsing, content negotiation, authentication token extraction, request-context construction, operation-name selection, variable JSON transport, error mapping, calling the Titan-lowered function/procedure, and preserving GraphQL response shape.

The Titan-lowered query engine is responsible for parsing the GraphQL document, validating against the generated schema and projection capabilities, planning retrievals, enforcing field/row/relation/edge/context/resource policies, and producing the GraphQL `data`/`errors` payload.

Authentication and context transport should use a compact internal context object passed from Quarkus to the lowered entrypoint. The first version should include actor id, actor role, tenant id when available, request id, policy flags, enabled context-filter names, and deadline/timeout budget.

## Out Of Scope

- Mutations.
- Subscriptions.
- Runtime Java execution inside the database.
- Raw dynamic SQL assembled from client query text.
- Arbitrary client-defined resolver plugins.
- Exposing generated SDL features that cannot be lowered, unless clearly marked experimental or Java-only.

Mutation and subscription operation syntax may be parsed, but validation must reject them with clear errors.

## Operations And Documents

Titan GraphQL should support:

- anonymous single query operations.
- named query operations.
- multi-operation documents when the caller supplies an operation name.
- shorthand selection-set query documents and explicit `query` operations.
- GraphQL comments.
- source locations for validation and parse errors.

The explicit-query-versus-shorthand behavior is:

- accept shorthand selection-set documents for single anonymous query operations.
- accept explicit `query` operation documents.
- require `operationName` when a document contains more than one operation.
- reject `mutation` and `subscription` during validation even if the parser recognizes them.
- use the transport field name `query`, matching GraphQL-over-HTTP, Apollo Server, and common Hasura request examples.

Reference alignment:

- GraphQL-over-HTTP defines request parameters `query`, `operationName`, `variables`, and `extensions`.
- Apollo Server accepts POST JSON bodies with `query`, optional `variables`, optional `extensions`, and optional `operationName`; it also accepts query operations over GET.
- Hasura examples use a single GraphQL endpoint with JSON request body fields `operationName`, `query`, and `variables`.

Operation-level directives are limited initially to `@include(if:)` and `@skip(if:)`.

## Selection And Response Shape

Titan GraphQL should support:

- field aliases.
- relation aliases independent of relation names.
- duplicate response-key validation according to GraphQL merge/conflict rules.
- query-order-preserving response output where practical.
- `__typename`.
- root meta fields `__schema`, `__type`, and `__typename` once introspection is enabled.
- scalar-field rejection when a scalar has a selection set.
- object/relation-field rejection when a required selection set is missing.

Responses should use the standard GraphQL top-level shape:

```json
{
  "data": {},
  "errors": []
}
```

Validation errors should normally fail the query before execution. Runtime field errors can later support partial data once error semantics are mature.

## Arguments, Variables, And Input Coercion

Titan GraphQL should support:

- variables.
- default variable values.
- required-variable validation before planning.
- unused-variable validation.
- variables supplied as JSON values.
- input object values.
- list input values.
- enum input values.
- custom scalar input coercion.
- rejection of unknown arguments.

Initial scalar coercion should cover:

- `ID`
- `String`
- `Int`
- `Float`
- `Boolean`
- timestamp-like custom scalars where declared by the projection model

`ID` must be distinct from `Int` at the GraphQL level even when a numeric database column backs it.

## Fragments And Directives

Titan GraphQL should support:

- named fragments.
- inline fragments.
- fragment-cycle rejection.
- unused-fragment validation.
- fragment type-condition validation against the generated schema.
- `@include(if:)`.
- `@skip(if:)`.
- directive arguments backed by variables.

Unknown executable directives should be rejected initially.

Schema directives should be generated for metadata such as:

- relation sort paths.
- deprecations.
- policy-visible annotations.
- future capability metadata that helps clients understand supported query shapes.

## Type System And Schema Surface

Generated SDL should include:

- object types.
- root query fields.
- scalar fields.
- relation fields.
- connection, edge, and pageInfo types.
- enum types.
- custom scalars.
- input object types.
- descriptions.
- deprecation metadata.
- interfaces.
- unions.
- supported directives.
- introspection metadata.

Interfaces and unions are accepted target features, but they should land after the object/input/scalar foundation is stable unless a specific schema requires them earlier.

Introspection should be supported but policy-disableable in production.

The first introspection policy is stable-schema introspection:

- generated SDL is globally stable for a deployment.
- introspection can be enabled or disabled by environment/policy.
- authorization is enforced at validation/execution time rather than by shaping SDL per actor.
- actor-shaped schemas are deferred until there is a concrete product need.
- disabled introspection returns GraphQL-shaped errors for `__schema` and `__type` selections.

## Roots, Relations, Pagination, Filtering, And Sorting

Root fields are generated only from declared root projections. Relations do not automatically become direct root queries.

Relay connections are the durable list contract. Offset pagination is allowed only as an explicitly declared non-durable compatibility capability.

Relay behavior should follow the Relay connection specification:

- `first`
- `after`
- `last`
- `before`
- opaque cursors
- cursor validation against type, field, direction, projection identity, and pagination mode
- stable deterministic ordering
- declared support for forward and/or backward pagination

Total counts should be available on connections through a field named `totalCount`.

Count rules:

- `totalCount` is exact by default.
- estimated counts may be introduced later only as a separate field or explicitly marked metadata, not as a silent substitute for `totalCount`.
- count visibility must match edge visibility. Hidden rows must not inflate visible counts.
- a retrieval operation must declare whether it can produce `totalCount`.
- if a retrieval cannot produce a count safely, the schema should not expose `totalCount` for that connection shape.
- count planning must record the count strategy and estimated cost.
- count execution may use a separate count retrieval or a combined window/count retrieval, depending on what the SQL target can lower efficiently.

The first generated connection shape should be:

```graphql
type ExampleConnection {
  edges: [ExampleEdge!]!
  pageInfo: PageInfo!
  totalCount: Int!
}
```

Filtering should use generated input objects rather than ad hoc flat arguments. The first generated filter input shape should follow this pattern:

```graphql
input ArticleFilter {
  id: IDFilter
  title: StringFilter
  authorId: IDFilter
  and: [ArticleFilter!]
  or: [ArticleFilter!]
  not: ArticleFilter
}

input StringFilter {
  eq: String
  neq: String
  in: [String!]
  isNull: Boolean
  contains: String
  startsWith: String
  endsWith: String
}
```

Filters should support:

- declared operators.
- relation-hop paths.
- explicit hop budgets.
- efficient lowering requirements.
- capability-bounded arbitrary boolean composition over declared operators.

The first implementation should support `eq`, `neq`, `in`, and `isNull` for scalar fields, then add string-specific and numeric comparison operators where declared by field capability metadata.

Sorting should use generated input objects. The first generated sort input shape should follow this pattern:

```graphql
input ArticleOrderBy {
  id: SortDirection
  title: SortDirection
  authorName: SortDirection
}

enum SortDirection {
  ASC
  DESC
}
```

Sorting is limited to declared sortable paths and should include:

- direction.
- null ordering.
- stable tie-breakers.
- relation-hop sort budgets.
- efficient lowering requirements.

Null ordering should be declared per sort path, with `NULLS_LAST` as the recommended default for ascending user-facing sorts unless the backing database semantics or product model require otherwise.

## Projection Computed Expressions

The projection runtime must support fields backed by computed expressions, not only physical columns.

Computed expressions may be used for:

- scalar projection fields.
- generated SDL fields.
- filtering, when the expression has a declared lowering strategy.
- sorting, when the expression has a declared lowering strategy and stable ordering contract.
- retrieval output columns, when the SQL target can lower or materialize the expression safely.

Every computed expression should declare:

- output GraphQL type.
- SQL/data-source lowering strategy.
- whether it can be selected.
- whether it can be filtered.
- whether it can be sorted.
- nullability.
- sensitivity/security classification.
- deterministic or non-deterministic behavior.

The metadata shape should be explicit enough for planning:

```text
ComputedExpression(
  name,
  graphqlType,
  expressionKind,
  sqlTemplateOrFunction,
  selectable,
  filterable,
  sortable,
  nullable,
  deterministic,
  sensitive,
  requiredColumns,
  costClass
)
```

Recommended expression kinds:

- SQL expression template.
- generated SQL helper function.
- precomputed/materialized column.
- Java-only experimental expression.

Production SQL-mode support requires a non-Java-only lowering strategy.

## Context Filters

The projection model needs a context-aware filtering mechanism similar in spirit to Hibernate filters.

Context filters are projection-level or retrieval-level filters that activate based on request context, actor context, tenant context, feature flags, or other policy inputs.

Context filters should:

- apply before rows are returned.
- compose with client filters.
- be visible in read plans.
- be testable independently from field authorization.
- avoid leaking hidden rows through counts, cursors, relation edges, or timing where practical.
- be declared in projection metadata instead of scattered through executors.

The metadata shape should be explicit:

```text
ContextFilter(
  name,
  activationRule,
  targetProjectionOrRetrieval,
  predicateTemplate,
  requiredContextKeys,
  compositionMode,
  affectsCounts,
  affectsCursors,
  failClosed
)
```

Recommended defaults:

- filters are opt-in by metadata but fail closed when required context is missing.
- context filters compose with client filters using `AND`.
- filters affect counts and cursors by default.
- filters must appear in read plans.
- filters must be testable without requiring the full HTTP frontend.

Examples include:

- tenant isolation.
- soft-delete exclusion.
- publication status.
- row ownership.
- data-region constraints.
- actor-role predicates.

## Security, Limits, And Resource Controls

Security defaults:

- field-level authorization rejects the whole query by default.
- row visibility is pushed into retrieval predicates where possible.
- relation edge visibility is modeled separately from node visibility.
- hidden fields remain in a stable generated schema initially, with validation-time rejection.
- introspection respects authorization if actor-shaped schemas are introduced.

Resource controls:

- selection depth limit.
- filter depth limit.
- sort depth limit.
- query complexity scoring.
- global maximum page size.
- per-retrieval maximum page size.
- execution timeout with predictable errors.
- no raw query text in logs by default.
- normalized query shape, hashes, and safe plan summaries for observability.

## Execution And SQL Lowering

Java-mode is the reference implementation. SQL-mode is the deployable implementation.

SQL-mode should be generated from the projection model and shared planning contract. Hand-maintained SQL kernels must not become a second semantic schema.

Every accepted production query shape should have:

- Java-mode validation.
- Java-mode execution.
- SQL-mode lowering.
- Java/SQL equivalence tests.
- inspectable read plan.
- selected retrieval operation metadata.
- estimated row bounds.
- complexity metadata.
- security policy metadata.

Unsupported Java constructs can exist only in explicitly Java-only experimental paths.

N+1 avoidance is not a GraphQL validation rule, but plan tests must reject N+1 regressions for supported relation shapes.

Batching is required when a relation is declared batchable. Shapes that cannot batch efficiently must be rejected, capped, or marked experimental.

SQL generated artifacts should be snapshot-tested for important query classes and lowering boundaries.

## Errors And Observability

Errors should use GraphQL shape:

- `message`
- `path`
- `locations`
- `extensions`

Validation errors and execution errors should use different stable extension codes.

Telemetry should expose:

- accepted or rejected query shape.
- selected operation name.
- root field.
- selected field count.
- relation expansion count.
- planned retrieval operations.
- row/page bounds.
- actor policy branch.
- rejection reason.
- execution duration.

Telemetry must not include sensitive raw values by default.

## Conformance And Test Closure

The implementation should grow a conformance matrix that classifies selected GraphQL spec tests as:

- expected pass.
- expected fail with reason.
- out of scope.
- future phase.

The current working matrix is tracked in [query-contract-conformance.md](query-contract-conformance.md).

The first conformance subset should focus on:

- operation parsing and selection sets.
- anonymous and named query operations.
- multi-operation documents with required operation name.
- variables and default values.
- aliases and field conflict validation.
- fragments and fragment cycles.
- `@include` and `@skip`.
- scalar/object selection validation.
- GraphQL response error shape.
- introspection smoke tests when enabled.

Mutations and subscriptions should be classified out of scope for this phase.

The contract is closed only when:

- accepted decisions are represented in this document.
- deferred decisions are listed explicitly.
- generated SDL tests reflect the contract.
- parser and validator tests cover accepted language features.
- Java/SQL equivalence tests cover accepted production features.
- security/resource-limit tests cover accepted policy rules.
- the next implementation roadmap references this document as source truth.

## Formalization Decisions Applied

The contract defaults are:

1. Quarkus frontend scope is `POST /graphql`, optional query-only `GET /graphql`, standard GraphQL-over-HTTP request fields, compact auth/context transport, and GraphQL-shaped response/error mapping.
2. Shorthand and explicit `query` documents are both accepted; multi-operation documents require `operationName`; mutation/subscription syntax is parsed but rejected.
3. Filters and sorts use generated input objects with declared operators and sortable paths.
4. Connection counts use `totalCount: Int!`, exact by default, visible-row scoped, and only exposed when the retrieval declares a safe count strategy.
5. Computed expressions have explicit selectable/filterable/sortable/lowering/security metadata.
6. Context filters have explicit activation, predicate, context-key, composition, count, cursor, and fail-closed metadata.
7. Introspection starts as stable-schema introspection with policy disablement; actor-shaped schemas are deferred.
8. The first conformance subset targets query parsing, operations, variables, aliases, fragments, directives, selection validation, response errors, and introspection smoke tests.

## Source Notes

- GraphQL-over-HTTP draft request parameters: `query`, `operationName`, `variables`, and `extensions`: <https://graphql.github.io/graphql-over-http/draft/>
- Apollo Server request format: <https://www.apollographql.com/docs/apollo-server/workflow/requests>
- Hasura GraphQL request examples: <https://hasura.io/blog/how-to-request-a-graphql-api-with-fetch-or-axios>
