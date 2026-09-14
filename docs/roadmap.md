# Titan GraphQL Roadmap

This roadmap treats the current demo GraphQL engine as the first adapter over a broader projection metamodel.

The target architecture is:

```text
physical tables
  -> projection metamodel
      -> retrieval operations, relations, capabilities, visibility
      -> generated GraphQL schema
      -> generic planner
      -> Java executor and SQL-lowerable kernels
```

The metamodel, not the hand-written GraphQL runtime, should become the source of truth for exposed data shape, relation capabilities, retrieval strategy, and visibility.

## Current Baseline

Already proven:

- physical table descriptors
- object/type descriptors over tables
- scalar field-to-column projections
- one-to-one and one-to-many relation descriptors
- root point retrievals and Relay-capable root connections
- descriptor-backed root equality filters such as `articles(authorId: Int)`
- recursive relation planning for selected fields
- batched relation reads for supported shapes
- field-level visibility for selected scalar fields
- a generic Java engine plus a constrained SQL-lowerable public kernel
- checked-in generated schema documentation guarded against printer drift

This is enough to move from "GraphQL demo over tables" toward "projection model with a GraphQL adapter."

## Architectural Direction

### 1. Core Projection Layer

Introduce a core projection layer below GraphQL-specific concepts.

The projection layer should define:

- projection types, such as `Article`, `User`, and `Comment`
- scalar projections backed by columns, computed expressions, or efficient retrieval outputs
- relation projections between projection types
- root projections and relation projections as first-class entry points
- retrieval operations that can satisfy a projection efficiently
- visibility rules attached to projections, relations, and retrieval paths

GraphQL should consume this layer rather than own it. A future REST adapter, admin query surface, or internal API should be able to reuse the same projection definitions and policies.

### 2. Retrieval Operations

A projection may be backed by one or more retrieval operations, not just a raw table scan.

Examples:

- point lookup by primary key
- bounded list by stable order
- filtered list by supported predicates
- relation batch by parent keys
- relation connection page by cursor
- pre-joined or denormalized read optimized for a common projection

Each retrieval operation should declare:

- backing table or query shape
- required input keys
- optional filters
- supported sort keys
- pagination mode
- projected output columns
- maximum row bounds
- whether it is safe for direct root queries, relation expansion, or both

The planner can then choose a supported retrieval operation instead of assuming every projection maps directly to `SELECT ... FROM table`.

### 3. Relation Capabilities

Relations should declare which query capabilities are supported.

Per relation, the metamodel should be able to say:

- selectable or hidden
- cardinality: one, many, connection
- nullable or required
- batchable by parent keys
- supports filtering
- supports sorting
- supports Relay-style pagination
- maximum selection depth
- maximum filter depth
- maximum sort depth

This lets `Article.comments` expose pagination/filtering while `Comment.author` remains a simple one-to-one lookup, and it lets unsupported relation capabilities fail during validation instead of drifting into slow or ambiguous execution.

### 4. N-Hop Filtering And Sorting

Filtering and sorting should support configurable traversal budgets.

The validator/planner should distinguish:

- selection depth: how far a query may select nested relation fields
- filter depth: how many relation hops a filter may traverse
- sort depth: how many relation hops a sort may traverse

For example, a model might allow:

- selecting `article.comments.author.name`
- filtering `articles` by `comments.author.id`
- sorting `articles` by `author.name`

But each of those should be allowed only when the metamodel declares the path, the hop budget, and an efficient retrieval operation or join strategy.

### 5. Relay-Style Pagination

Durable pagination should be Relay-style, not offset-only.

The metamodel should describe connection-capable roots and relations with:

- generated `Connection`, `Edge`, and `PageInfo` GraphQL types
- `first`, `after`, `last`, and `before` arguments where supported
- stable cursor fields
- deterministic default ordering
- optional secondary tie-breakers
- whether backward pagination is supported
- maximum page size

Legacy bounded `limit` support remains only as a bootstrap/test shape for non-Relay list roots; durable demo pagination now uses Relay connections and cursors.

### 6. Visibility Rules

Visibility belongs on the projection model and must apply consistently everywhere a projection can be reached.

Rules should be attachable to:

- scalar projections
- relation projections
- root projections
- retrieval operations
- rows or relation edges

The same visibility rules must be respected for direct root queries and nested relation reads. If `User.email` is restricted, it must be restricted whether selected via `article.author.email`, `article.comments.author.email`, or any future direct user root.

The planner should apply visibility as early as possible:

- reject unauthorized fields during validation when field rejection is the declared mode
- compile row visibility into retrieval predicates where possible
- avoid fetching sensitive columns when they are not visible
- keep raw policy-sensitive values out of telemetry

### 7. GraphQL Schema Generation

The GraphQL schema should be generated from the projection metamodel.

Generation should cover:

- object types from projection types
- scalar fields from scalar projections
- relation fields from relation projections
- root query fields from root projections
- connection/edge/pageInfo types for Relay-capable list shapes
- supported arguments from filter, sort, and pagination capabilities
- field visibility modes where they affect exposed schema shape

The hand-written schema descriptors can remain the bootstrap path while the generated schema contract is introduced in small slices.

## Implementation Status

As of 2026-05-30, the original M1-M6 roadmap is implemented for the bounded demo-blog proof:

- M1 introduced the projection-core descriptors and made `DemoBlogGraphqlSchema` adapt from `ProjectionModel`.
- M2 moved validation and planning onto declared root/relation capabilities, hop budgets, retrieval metadata, and relation argument descriptors.
- M3 attached scalar visibility policy to projection fields and preserved `User.email` enforcement through direct and nested relation paths.
- M4 added descriptor-backed relation filter/sort metadata, including declared sort paths surfaced by generated schema output.
- M5 promoted the demo `articles` root and `Article.comments` relation to Relay-style connections with cursor windows in the Java path and constrained public SQL kernel.
- M6 added an inspectable generated SDL snapshot plus tests that compare printer output and documentation exactly.

Future work should start from a new roadmap section or control file rather than treating the milestones below as still pending.

## Milestones

### M1: Projection-Core Extraction

Separate projection concepts from GraphQL runtime concepts without changing external behavior.

Deliverables:

- `ProjectionModel`, `ProjectionType`, `ProjectionField`, `ProjectionRelation`, and `ProjectionRetrieval`
- adapter from projection model to the existing `GraphqlSchema`
- demo blog schema expressed once through projection definitions
- tests proving the generated GraphQL schema matches the current manual schema

### M2: Capability-Aware Planner

Teach the planner to read declared capabilities instead of relying on demo-specific assumptions.

Deliverables:

- relation capability metadata for filtering, sorting, batching, and pagination
- configurable selection/filter/sort hop budgets
- validation errors when a query asks for unsupported relation capabilities
- plan output that records chosen retrieval operations

### M3: Visibility Model

Move field policy toward a projection-level visibility model.

Deliverables:

- field visibility rules on scalar projections
- relation visibility rules on relation projections
- row visibility hooks on retrieval operations
- tests proving direct and relation paths enforce the same visibility

### M4: Descriptor-Backed Relation Filters And Sorts

Extend filtering and sorting beyond root equality filters.

Deliverables:

- filter path descriptors with a configurable max hop count
- sort path descriptors with stable ordering and tie-breakers
- planner support for supported relation-filter and relation-sort paths
- explicit validation failure for unsupported paths

### M5: Relay Pagination

Replace durable list pagination semantics with Relay-style connection support.

Deliverables:

- connection/edge/pageInfo generation from projection metadata
- `first`/`after` support for at least one root list and one relation list
- cursor encoding/decoding with stable ordering
- SQL-kernel support for the same connection shape where Titan can lower it

### M6: Generated GraphQL Schema Surface

Make generated GraphQL schema output inspectable.

Deliverables:

- generated SDL or equivalent schema snapshot from the metamodel
- tests for generated roots, fields, arguments, relation capabilities, and connection types
- documentation showing how a projection definition maps to GraphQL schema

## Design Rules

- The projection metamodel is the source of truth.
- GraphQL is an adapter over projections, not the owner of projections.
- Retrieval operations are explicit and capability-bounded.
- Visibility is enforced during validation and planning, not cleaned up after response construction.
- Filtering, sorting, and pagination must declare hop budgets and efficient lowering strategies.
- Offset pagination is not the target; Relay-style cursor pagination is.
- SQL kernels may remain constrained target-specific lowerers, but they must not become a second semantic schema.
