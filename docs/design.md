# Titan GraphQL Design

## Purpose

Titan GraphQL is a schema-driven GraphQL layer and a proof project for pushing Titan beyond
normal stored-procedure authoring.

The primary product path is:

```text
Titan codegen schema.json
  -> conservative GraphQL projection draft
  -> reviewed exposure and policy model
  -> generic parser, validator, and read planner
  -> parameterized Titan DSL + Titan JDBC (portable in-process route)
  -> generated static database carriers + Titan transpiler (compiled read route)
```

This path must not require handwritten read queries or resolvers. Custom mutations are the
intentional code extension point.

The database-resident experiment is:

> Write Java code that accepts a GraphQL query string and actor context, parses the query, validates the requested shape, executes the necessary database reads through Titan DSL calls, applies authorization rules before data access, and returns a serialized result. Annotate the top-level Java function so Titan transpiles the complete logic into a stored procedure or stored function.

This is intentionally more ambitious than the conservative Titan adoption path. It is a stress test of the Java-to-TIR-to-SQL pipeline, not just a product feature.

## Non-Goals

- Full GraphQL specification support in the first proof.
- Replacing a general GraphQL server.
- Runtime Java execution inside the database.
- Raw dynamic SQL string construction.
- Post-filter authorization after sensitive rows have already been read.
- Hiding unsupported query shapes behind silent fallback.

## Success Criteria

The first proof is successful when:

- A single annotated Java entrypoint transpiles to deployable PostgreSQL and/or MySQL SQL.
- The procedure accepts a query string plus actor context.
- The transpiled logic parses a bounded GraphQL subset without relying on an external GraphQL runtime.
- The executor performs data reads through Titan DSL-supported operations.
- Authorization predicates are applied before protected data is read or returned.
- Nested relation reads avoid obvious per-parent N+1 behavior where the bounded query shape permits batching.
- Unsupported syntax and unsupported query shapes fail with explicit errors.
- Java-mode and SQL-mode tests prove equivalent results for representative queries.
- Generated SQL remains inspectable enough to diagnose failures.

## Architecture

The project uses Titan in its existing intended shape:

1. Developers write Java.
2. Titan discovers annotated entrypoints.
3. Titan validates supported Java and DSL usage.
4. Titan lowers Java into Titan IR.
5. Titan emits stored procedure/function SQL.
6. The database runs the generated SQL artifact.

Unlike a conventional GraphQL integration, the GraphQL parser and executor are not external runtime services. They are ordinary Java code inside the transpiled entrypoint closure.

```text
GraphQL query string + actor context
  -> annotated Java entrypoint
      -> lexer/parser
      -> bounded AST
      -> schema-driven validation
      -> authorization planning
      -> Titan DSL reads
      -> JSON/text result builder
  -> Titan transpiler
  -> stored procedure/function SQL
  -> database execution
```

## Top-Level Entrypoint

The initial API should be deliberately small:

```java
@StoredFunction
public static String executeGraphql(String query, long actorId, String actorRole) {
    // parse, validate, execute, serialize
}
```

The exact return type can evolve. A JSON/text string is the simplest first target because it avoids requiring Titan to model a broad GraphQL response object graph immediately.

## Supported GraphQL Subset

The implemented demo subset is powerful enough to be credible while still small
enough to isolate Titan gaps.

Include:

- one root `query` operation
- a schema described by Java model descriptors, with the demo model exposing `article` and `articles`
- scalar fields
- one-to-one and one-to-many nested relations
- simple arguments such as `id`, equality filters, and Relay-style cursor pagination through `first`, `after`, `last`, and `before`
- stable default ordering for list fields
- nullable fields
- explicit error objects or error strings for rejected queries

Exclude initially:

- mutations
- subscriptions
- fragments
- aliases
- variables
- directives
- unions/interfaces
- custom scalars beyond strings, numbers, booleans, and timestamps
- arbitrary boolean filter expressions
- deep unbounded recursion
- user-defined resolver plugins

## Java Components

Keep the implementation split into small Java classes so Titan's failure mode is readable.

- `GraphqlLexer`: scans the query string into tokens.
- `GraphqlParser`: builds a bounded AST.
- `GraphqlAst`: records for operation, field, argument, and selection set.
- `GraphqlSchema`, `GraphqlTableDescriptor`, `GraphqlObjectType`, `GraphqlFieldDescriptor`, and `GraphqlRootField`: describe the GraphQL model and its table-backed projections independently from the parser and validator.
- `GraphqlValidator`: checks field names, argument types, nesting limits, field policies, and unsupported syntax against the active schema.
- `GraphqlSelection`: stores the validated root and selection tree without hard-coding a concrete data model.
- `GraphqlReadPlanner` and `GraphqlReadPlan`: compile a validated selection plus schema descriptors into logical root and relation reads, including projected columns, backing tables, join keys, cardinality, and limits.
- `GenericJdbcGraphqlDataModel`: executes supported read plans for any reviewed projection
  through Titan DSL and Titan JDBC, without model-specific query code.
- `TitanGraphqlRoutineSourceGenerator`: turns reviewed physical/model bindings into deterministic,
  static Titan-transpilable read carriers and a semantic-hash attestation routine. It emits no
  fixture data and contains no application schema registry.
- `TitanCompiledGraphqlDataModel`: executes the shared validated read plan by invoking generated
  carriers resolved from Titan's verified object inventory, with no model-name dispatch. It
  normalizes the two dialect carrier shapes and rejects unsupported plan shapes before I/O.
- `GraphqlDataModel`: binds a schema descriptor to execution and leaves mutations as explicit
  extension points.
- `GraphqlPolicy`: maps actor context to allowed fields and row predicates.
- `GraphqlEngine`: parses and validates a query, then delegates execution to the active data model.
- `DemoBlogGraphqlExecutor`: the legacy fixture-backed reference implementation used by the
  bounded compiler corpus.
- `DemoBlogGraphqlJsonWriter`: serializes demo-blog response rows.
- `GraphqlJsonWriter`: serializes generic GraphQL errors and shared JSON escaping.

The proof should start with simple arrays/records/strings if Titan supports them well enough. If the current Java subset rejects a structure, document that as the next Titan capability gap rather than weakening the proof silently.

## Projection Metamodel

GraphQL type definitions should initially live in Java code, because the proof is about transpiling Java logic.

The DB schema remains sourced through Titan's normal schema introspection or DDL input.
`TitanGraphqlSchemaInference` consumes codegen's `schema.json` directly, so GraphQL inference
and catalog generation share one discovered schema rather than parallel fixtures.

The GraphQL schema model is a projection metamodel over those raw tables, not an ORM. It maps:

- GraphQL object type -> projected DB-backed view of one or more tables
- scalar field -> DB column or computed expression
- relation field -> foreign-key or explicit join definition
- field arguments -> bounded filter/pagination inputs
- actor policy -> pre-read predicate or field rejection

The projection layer stays below the GraphQL runtime. GraphQL schema, validation arguments,
relation pagination/filtering/sorting, and visibility rules are generated or derived from the
projection model where supported.

The current metamodel layer stores:

- physical table descriptors: logical table name, SQL schema/table name, primary key column
- object bindings: GraphQL type name to backing table descriptor
- scalar projections: GraphQL field name to backing column name
- relation projections: GraphQL field name to target type, local column, target column, cardinality, and nullability
- root descriptors: field name, result type, result cardinality, supported key/filter arguments, pagination mode, cursor ordering, and default/max page sizes

This keeps the API schema decoupled from physical table names. The generic JDBC path consumes the
model now. `titanGraphqlGenerateRoutines` also generates a static database read boundary from that
same model: typed and composite point roots, forward/backward page carriers, direct and batched
relations, safe row-local computed expressions, and a semantic-hash attestation routine. The
compiled runtime also assembles reviewed Relay relation connections from those ordered carrier
rows without issuing one query per collection parent. Titan compiles those carriers to JSONB
functions on PostgreSQL and open-result-set procedures on MySQL. The generated carriers are now
packaged and live-tested. The opt-in `compiled` runtime executes the supported generic plan subset
through them. The older `sql` runtime still dispatches a whole request to the fixed demo kernel.

`titanGraphqlBindPackage` links the canonical model semantic hash to Titan's artifact, manifest,
source-input hashes, and routine inventory. SQL startup verifies that sidecar, resolves routine
identities from the inventory, and calls the database-resident model attestation routine. This
prevents serving a stale, unrelated, or wrongly deployed package. The isolated commerce proof now
builds and serves a second generated-only package without the demo kernel. The remaining
architectural step is to complete carrier/plan coverage, promote compiled mode, then delete the
demo kernel from production dispatch.

For reviewed local root sort paths, code generation emits separate ascending and descending page
carriers. Cursor predicates compare the declared value and tie-breaker as a tuple-equivalent
boolean expression, so continuation remains stable when sort values repeat. Runtime SQL never
substitutes a client-provided identifier or direction; the validated plan only chooses among
inventory-resolved generated entry points. Multiple custom order keys and relation-hop ordering
remain explicit unsupported shapes.

The engine must stay model-agnostic: parsing, validation, policy application, and selection-tree construction cannot know about `Article`, `User`, or any future application type. Concrete data models provide descriptors and execution adapters. The current `DemoBlogGraphqlSchema` and demo executor are only the first adapter.

The demo Java engine now exercises both relation cardinalities: `Article.author` as a one-to-one relation and `Article.comments` as a one-to-many relation. The public SQL kernel may still expose a narrower subset while lowering catches up, but the generic planner records enough join metadata to batch supported nested relation reads from descriptors rather than hard-coded model names.

The stored-function SQL target is allowed to be narrower than the Java engine while Titan's P0 subset is still growing. For now, `DemoBlogTitanGraphqlFunctions.executeGraphql(...)` uses a scalar, static demo-blog kernel that preserves the public API and security behavior for the supported smoke path. `TitanGraphqlFunctions` remains a Java compatibility facade, but it is not the lowerable kernel. The richer Java path remains `executeGraphqlWithPlan(...)`, which exercises the generic descriptors, parser, validator, and model adapter. The architectural rule is that the projection model remains the source of product semantics; GraphQL runtimes and SQL kernels are target-specific adapters, not places to hard-code the overall data model.

Generated schema snapshots expose Relay-capable roots and relations as `Connection`,
`Edge`, and `PageInfo` types with `first`, `after`, `last`, and `before`
arguments. The validator, cursor planner, Java executor, and constrained public
SQL kernel now lower the same connection shape for the demo `articles` root and
`Article.comments` relation. The snapshot path must keep using Relay-style cursor
semantics rather than adding offset arguments as a temporary durable API.

The current generated demo schema surface is checked in at [generated-schema.md](generated-schema.md).
It is produced from the projection model through `ProjectionGraphqlAdapter` and
`GraphqlSchemaPrinter`; the snapshot is for inspection and tests, not a second
semantic source.

Longer term, the GraphQL type definitions could be generated from annotations or a small DSL, but the first proof should keep them explicit and inspectable.

## Security Model

Security must be part of planning, not response cleanup.

Required rules:

- Actor context is an explicit input to the stored procedure/function.
- Field access is checked during validation/planning.
- Row access is compiled into query predicates wherever possible.
- Unauthorized fields are rejected or omitted according to a declared mode.
- Sensitive fields cannot be logged in debug/telemetry output.
- The implementation never builds SQL by concatenating user query text.

Open question: whether the first proof should reject unauthorized fields or return partial data with GraphQL-style errors. Rejection is simpler and safer for the first proof.

## N+1 Strategy

The proof should make N+1 behavior visible.

For the current implementations:

- scalar root lookups can use direct point queries
- list root lookups use a single capability-bounded retrieval
- direct relations beneath a point root are planned from declared local and target columns
- compiled mode batches direct relations beneath collection roots through fixed arity 2–64
  carriers; a 100-parent page therefore needs at most two child calls, never one call per parent
- deeper nested batching and relation-connection batching remain explicit rejections
- the fixed demo Java/SQL equivalence kernel retains its existing bounded connection behavior
- unsupported deep nesting should fail with an explicit max-depth error
- tests should compare the number of planned read steps for representative nested queries

This does not require a perfect general GraphQL optimizer. It does require avoiding the most obvious per-parent child query pattern for the supported shape.

## Observability

Each execution should expose enough information to understand what happened:

- accepted or rejected query shape
- selected root field
- selected field count
- relation expansion count
- actor policy branch
- planned read steps
- fallback/rejection reason

Do not log raw query values or sensitive result values by default.

## Testing Plan

Use Titan's existing validation philosophy:

- Java-mode tests for lexer/parser/validator behavior.
- Java-mode executor tests against a fixture DB through Titan runtime/JDBC.
- SQL-mode tests calling the transpiled procedure/function.
- Equivalence tests comparing Java-mode and SQL-mode outputs.
- Negative tests for unsupported GraphQL syntax and unauthorized fields.
- Plan-shape tests for nested relation batching.

Representative first queries:

```graphql
query {
  article(id: 1) {
    id
    title
    author {
      id
      name
    }
  }
}
```

```graphql
query {
  articles(authorId: 10, first: 10) {
    edges {
      cursor
      node {
        id
        title
        comments(first: 3) {
          edges {
            node {
              id
              body
            }
          }
          pageInfo {
            hasNextPage
            endCursor
          }
        }
      }
    }
    pageInfo {
      hasNextPage
      endCursor
    }
  }
}
```

## Verified Milestones

The project has proven the initial end-to-end loop:

1. Create a tiny DB schema: users, articles, comments.
2. Generate Titan catalog descriptors.
3. `executeGraphql(query, actorId, actorRole)` runs as Java and transpiled SQL.
4. Point queries, connections, and nested demo relations are covered by the conformance corpus.
5. Field-level policy is enforced.
6. Both paths return GraphQL JSON.
7. Java and SQL behavior is equivalent on PostgreSQL and MySQL.
8. A second customers/orders schema is served from current database rows by the generic JDBC
   executor on both dialects without schema-specific read code.

## Remaining Design Decisions

- How should a reviewed projection's semantic hash be embedded into Titan package metadata so an
  SQL package can be cryptographically bound to its model?
- What generated static accessor shape best turns arbitrary reviewed models into a transpilable
  kernel without dynamic SQL?
- What batching strategy should execute relations beneath generic collection roots?
- Which computed-expression and policy expression subsets can be safely lowered across both
  PostgreSQL and MySQL?
