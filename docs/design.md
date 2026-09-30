# Design

Titan GraphQL separates model review, database-engine generation, and HTTP transport. The only
supported serving artifact is the standalone database HTTP ZIP. It contains transport and JDBC
code, not a JVM GraphQL parser, validator, planner, resolver, cursor codec, or response engine.
The installed PostgreSQL function or MySQL procedure receives one whole-envelope call and owns
GraphQL semantics and the transaction result. A separate control-job worker handles build and
file work outside request transactions.

```text
database DDL / Titan schema metadata
        -> conservative inference -> reviewed titan.graphql.yaml
        -> generated engine binding -> Titan transpilation -> verified dialect package
        -> model/runtime/package identity binding -> deployment descriptor
        -> standalone HTTP/JDBC frontend -> one installed routine call
```

## Reviewed model and package

Inference preserves physical tables, columns, keys, and foreign-key candidates but publishes no
root or sensitive field without review. The model declares GraphQL types, roots, relations,
filters, ordering, context predicates, policies, metadata, and explicit mutation bindings.
Validation rejects unsupported identifiers, unsafe computed expressions, ambiguous exposure, and
paths the engine cannot lower. Generated SDL and introspection derive from that same reviewed
model. See [model-document-format.md](model-document-format.md) and
[query-contract.md](query-contract.md).

The build emits one transpilable engine source closure and model-specific bindings. Titan produces
dialect-specific SQL, a manifest and object inventory, install plan and verification report,
rollback scripts, and identity sidecars. The binder attests every packaged SQL helper against its
source and inventory, then writes a model/package binding. The frontend descriptor fixes dialect,
schema-qualified entry point, and model/runtime/package identities; callers cannot select these
through GraphQL or headers. The installed routine checks them before parsing or accessing data.

## Request and transaction path

The frontend validates HTTP envelope shape and authenticated transport context, then binds the
document, operation name, variables, extensions, trusted context, mutation permission, and three
package identities to one database call. It never interprets GraphQL for routing or commit.
The installed engine selects and validates the operation, applies model policies, plans bounded
database reads or serial writes, and completes JSON. PostgreSQL returns a framed response with an
explicit COMMIT/ROLLBACK instruction; MySQL returns the same outcome and JSON in the final result
set. The JDBC client applies that instruction to the request transaction and sends only GraphQL
JSON to HTTP. A failed later mutation root rolls back earlier writes, audit, idempotency, and
outbox effects.

Read plans group compatible relation selections by AST-backed identity and use fixed 64-parent
batches. Request-wide limits cover source, lexical and semantic work, input coercion, generated
statements, decoded rows, response assembly, and deadlines. Unsupported plans fail closed rather
than switching to an alternate engine. [database-engine-m5-parity.md](database-engine-m5-parity.md)
records cross-dialect installed and standalone-HTTP evidence.

## Management and previews

The management package is distinct from the application package. The bearer-protected
`/admin/graphql` route enqueues database-backed import, validation, artifact, and review jobs and
exposes reviewed management projections. The separate worker claims jobs and commits their result
and durable management state. Preview publication requires a READY reviewed build, an installed
package, an ENFORCE operation registry for the same model/environment, and a sealed descriptor.
The frontend loads a deployment-owned preview registry at startup and dispatches
`/preview/{previewBuildId}/graphql` only to its bound package. A changed mapping requires a
frontend restart. See [database-http-frontend.md](database-http-frontend.md) and
[operations.md](operations.md).

## Verification boundary

The `databaseEngine` source set is the maintained GraphQL semantics implementation. The
`databaseFrontend` and `databaseHttpFrontend` source sets cannot depend on it at runtime. Unit
tests may run the transpilable source on the JVM, but the release ZIP cannot load those classes.
The local release gate installs and invokes independently generated blog and commerce packages on
PostgreSQL and MySQL, runs the fixed corpus through installed routines and the extracted ZIP, and
checks class/dependency/artifact closure. The container gate separately proves frontend and worker
restart plus published preview reachability. See [verification.md](verification.md).
