# Milestone 1 Foundation

This milestone starts the smallest credible Titan GraphQL loop:

- DDL-backed catalog generation for `users`, `articles`, and `comments`.
- A bounded GraphQL lexer/parser for one `query` operation.
- Schema-driven validation for the first supported root shape: `article(id: ...)`.
- One nested relation: `article.author`.
- One explicit field policy: only `admin` actors may request `User.email`.
- A planner that records Titan DSL read steps before execution.
- JSON text output for Java-mode tests.

The executor currently uses an in-memory fixture store so the parser, policy, plan shape, and JSON contract can be tested without requiring a database service. Each read step is still expressed through the Titan DSL and captured in the execution plan.

## Generic Engine Direction

The Java compatibility entrypoint remains `TitanGraphqlFunctions.executeGraphql(query, actorId, actorRole)`, but the annotated SQL-lowerable entrypoint now lives on the demo-blog kernel. The engine below the Java path is intentionally model-agnostic.

- `GraphqlSchema`, `GraphqlObjectType`, `GraphqlFieldDescriptor`, and `GraphqlRootField` describe the active data model.
- `GraphqlValidator` uses those descriptors instead of hard-coding `Article` and `User`.
- `GraphqlSelection` stores a generic root and selection tree.
- `GraphqlDataModel` binds descriptors to model-specific execution.
- `DemoBlogGraphqlSchema` is the current article/user adapter, not the engine contract.

## Current Transpilation Finding

The proof intentionally keeps `DemoBlogTitanGraphqlFunctions.executeGraphql(query, actorId, actorRole)` annotated with `@StoredFunction`, with `TitanGraphqlFunctions` left as a Java compatibility facade.

Titan now lowers source-local static helper routines reachable from annotated entrypoints, and rejects unresolved Java helper calls before SQL emission. The public stored-function path therefore uses a constrained static SQL kernel for the current demo-blog subset, while Java-mode tests keep exercising the generic parser, validator, selection tree, and `GraphqlDataModel` adapter architecture.

This split is intentional:

- `executeGraphql(...)` is the database API and must stay inside Titan's SQL-lowerable subset.
- `executeGraphqlWithPlan(...)` is the richer Java proof path for validating the generic engine and plan shape.
- Future data models should plug into the descriptor/adapter layer first, then grow their own SQL-lowerable kernel only where the database target needs one.
