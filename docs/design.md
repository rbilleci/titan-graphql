# Titan GraphQL Design

## Purpose

Titan GraphQL is a proof project for pushing Titan beyond normal stored-procedure authoring.

The central experiment:

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
- `GraphqlDataModel`: binds a schema descriptor to model-specific execution.
- `GraphqlPolicy`: maps actor context to allowed fields and row predicates.
- `GraphqlEngine`: parses and validates a query, then delegates execution to the active data model.
- `DemoBlogGraphqlExecutor`: executes the demo blog model using Titan DSL reads.
- `DemoBlogGraphqlJsonWriter`: serializes demo-blog response rows.
- `GraphqlJsonWriter`: serializes generic GraphQL errors and shared JSON escaping.

The proof should start with simple arrays/records/strings if Titan supports them well enough. If the current Java subset rejects a structure, document that as the next Titan capability gap rather than weakening the proof silently.

## Projection Metamodel

GraphQL type definitions should initially live in Java code, because the proof is about transpiling Java logic.

The DB schema remains sourced through Titan's normal schema introspection or DDL input, producing typed catalog descriptors.

The GraphQL schema model is evolving into a projection metamodel over those raw tables, not an ORM. It maps:

- GraphQL object type -> projected DB-backed view of one or more tables
- scalar field -> DB column or computed expression
- relation field -> foreign-key or explicit join definition
- field arguments -> bounded filter/pagination inputs
- actor policy -> pre-read predicate or field rejection

The roadmap target is to keep this projection layer below the GraphQL runtime. GraphQL schema, validation arguments, relation pagination/filtering/sorting, and visibility rules should be generated or derived from the projection model where possible. See [roadmap.md](roadmap.md) for the milestone plan.

The current metamodel layer stores:

- physical table descriptors: logical table name, SQL schema/table name, primary key column
- object bindings: GraphQL type name to backing table descriptor
- scalar projections: GraphQL field name to backing column name
- relation projections: GraphQL field name to target type, local column, target column, cardinality, and nullability
- root descriptors: field name, result type, result cardinality, supported key/filter arguments, pagination mode, cursor ordering, and default/max page sizes

This keeps the API schema decoupled from physical table names while remaining concrete enough for Titan to lower. The constrained SQL kernel may still use static helper code, but it should consume or mirror compiled constants from this metamodel rather than becoming a second hand-written schema.

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

For the current demo proof:

- scalar root lookups can use direct point queries
- list root lookups use a single capability-bounded retrieval; the generic Java engine now models the demo `articles` root as a Relay connection with descriptor-backed equality filters, and the SQL kernel exposes the same public connection shape
- nested relations are planned from relation descriptors, including local and target join columns, so supported list roots can batch relation reads instead of rediscovering joins in model-specific code
- nested one-to-many relations batch by parent IDs where the declared relation capability supports it
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

## First Milestone

Milestone 1 should prove the smallest end-to-end loop:

1. Create a tiny DB schema: users, articles, comments.
2. Generate Titan catalog descriptors.
3. Write `executeGraphql(query, actorId, actorRole)`.
4. Support one root point query and one nested relation.
5. Enforce one field-level policy.
6. Return JSON text.
7. Validate Java-mode and SQL-mode equivalence.

## Key Open Questions

- Which database dialect is the first target: PostgreSQL only, or dual PostgreSQL/MySQL from day one?
- What Java data structures are acceptable for the first parser AST under current Titan support?
- Should unauthorized fields reject the whole query or produce partial GraphQL-style errors?
- How much JSON construction should happen inside transpiled code versus SQL JSON functions?
- Should GraphQL schema definitions be plain Java classes, annotations, or a Titan DSL extension?
- What is the maximum supported nesting depth for the first proof?
- What observable metric best proves N+1 avoidance in generated SQL?
- Should the first proof live as an independent repo or a module/example under the main Titan repo?
