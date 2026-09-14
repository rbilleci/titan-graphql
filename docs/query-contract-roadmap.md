# Titan GraphQL Query Contract Roadmap

This roadmap starts from `docs/query-contract.md` and turns the accepted query contract into implementation slices. Mutations and subscriptions remain out of scope.

## Execution Principles

- Keep Java-mode as the reference behavior.
- Promote a feature to production-supported only when SQL-mode can lower it and Java/SQL equivalence tests pass.
- Keep generated SDL, validation, planning, execution, and SQL lowering aligned with the projection metamodel.
- Prefer small PRs that each close one contract-visible behavior or one metamodel capability.

## Milestones

### QC1: Transport And Operation Semantics

Establish the GraphQL-over-HTTP-facing request contract and operation selection semantics before adding the Quarkus runtime.

Deliverables:

- named single query operations
- operation-name metadata in the AST
- clear validation rejection for mutation and subscription operations
- request-envelope model for `query`, `operationName`, `variables`, and `extensions`
- multi-operation document rejection until operation selection is implemented
- tests for shorthand, explicit `query`, named query, and rejected operation types

### QC2: GraphQL Response And Error Shape

Move runtime errors toward the standard GraphQL `data`/`errors` envelope.

Deliverables:

- error object with `message`, `path`, `locations`, and `extensions`
- stable validation and execution error codes
- top-level `data` and `errors` output
- tests for parse, validation, authorization, and execution errors

### QC3: Variables And Input Coercion

Support normal GraphQL client request behavior.

Deliverables:

- variable definitions in operation parsing
- JSON variable transport model
- default variable values
- required-variable validation
- scalar coercion for `ID`, `String`, `Int`, `Float`, `Boolean`, and declared timestamp-like scalars
- unknown and unused variable validation

### QC4: Aliases, Field Merging, And `__typename`

Support common client query shaping.

Deliverables:

- field aliases
- relation aliases
- response-key merge/conflict validation
- query-order-preserving output
- `__typename` support

### QC5: Fragments And Runtime Directives

Support reusable selections and conditional execution.

Deliverables:

- named fragments
- inline fragments
- fragment cycle detection
- unused fragment validation
- `@include(if:)` and `@skip(if:)`
- directive variable coercion

### QC6: Generated Filter And Sort Inputs

Replace ad hoc flat arguments with generated input-object capability metadata.

Deliverables:

- generated filter input types
- generated sort input types
- scalar operators `eq`, `neq`, `in`, and `isNull`
- declared string/numeric operators
- relation-hop filter and sort paths with budgets
- null ordering metadata

### QC7: Relay Connection Counts

Add the accepted connection count contract.

Deliverables:

- generated `totalCount: Int!` where retrievals declare safe count support
- exact visible-row counts by default
- count strategy metadata in read plans
- visibility-safe count behavior
- SQL lowering for supported count strategies

### QC8: Computed Expressions

Allow projection fields backed by declared computed expressions.

Deliverables:

- computed expression metadata
- selectable computed fields
- filterable and sortable computed fields where lowering exists
- sensitivity/nullability/determinism metadata
- Java and SQL tests for at least one computed expression

### QC9: Context Filters

Add projection-level policy filters that activate from request context.

Deliverables:

- context-filter metadata
- fail-closed activation rules
- composition with client filters
- count and cursor interaction rules
- read-plan visibility
- tests for tenant/soft-delete style filters

### QC10: Introspection And Conformance Matrix

Make the contract externally inspectable and test-backed.

Deliverables:

- stable-schema introspection mode
- policy disablement for introspection
- generated SDL tests for accepted contract features
- selected GraphQL conformance matrix
- expected pass/fail/out-of-scope classifications

### QC11: Quarkus Frontend

Add the lightweight HTTP bridge once the request/response and operation semantics are stable.

Deliverables:

- `POST /graphql`
- optional query-only `GET /graphql`
- content negotiation
- auth/context extraction
- variable and operation-name transport
- call path into Titan-lowered functions/procedures
- HTTP-to-GraphQL error mapping

## First Slice

Start with QC1 by accepting named single query operations in the parser while keeping multi-operation documents, variables, aliases, fragments, mutations, and subscriptions rejected. This creates a small executable contract step without forcing the Quarkus frontend into the same PR.
