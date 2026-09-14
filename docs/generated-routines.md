# Generated Titan Routine Contract

Status: active compiled-read contract. Opt-in `compiled` mode consumes the supported subset; the
legacy `sql` route still uses the transitional demo whole-request kernel.

## Purpose

`titanGraphqlGenerateRoutines` converts one validated, reviewed `titan.graphql.yaml` document into
static Java carriers that Titan can analyze and transpile. Schema authors do not write read SQL or
Java resolvers. The generator owns physical table/column binding, stable method naming, parameters,
projection aliases, policy omission, pagination order, and model attestation.

The generated Java file lives under `build/generated/sources/titan-graphql/`. It is reproducible
build output and must not be committed. `titanTranspile` consumes it directly; `titanPackage`,
`titanVerifyInstall`, and `titanGraphqlBindPackage` depend on that chain.

## Current Emission

For each supported reviewed model the generator emits:

- `modelSemanticHash()`, returning the canonical lowercase SHA-256 of the normalized model;
- `readRoot<Name>(...)` for point roots;
- `readRoot<Name>Forward(...)` and `readRoot<Name>Backward(...)` for Relay roots;
- `countRoot<Name>(...)` for roots declaring exact visible counts;
- `readRelation<Owner><Name>(...)` for unprotected direct relations.
- `readRelation<Owner><Name>Batch<N>(...)` at fixed arities 2, 4, 8, 16, 32, and 64,
  allowing a 100-parent page to batch in at most two static calls.

Every query uses prepared-statement parameters. Physical identifiers must satisfy the portable
unquoted identifier subset and unsafe computed templates are rejected during generation. Scalar
fields with policies and relations with policies are not placed in an unguarded carrier. A model
that cannot be represented safely fails generation rather than emitting a partial unsafe query.

Titan currently compiles the metadata-driven JDBC carrier shape differently by dialect:

| Target | Read carrier | Invocation result |
| --- | --- | --- |
| PostgreSQL | function | one JSONB array value |
| MySQL | procedure | one open JDBC result set |
| Both | `modelSemanticHash` function | one scalar hash |

Callers must resolve the schema-qualified object from `titan-object-inventory.json` through the
bound package metadata; deriving or hard-coding SQL routine names is not a production contract.

## Verified Properties

Docker-free generator tests prove byte stability, absence of fixture rows, fail-closed rejection
of unsafe identifiers, protected-field omission, and reuse for unrelated blog and commerce models.
`GeneratedTitanGraphqlReadsIT` installs the package and proves both dialect shapes against live
databases, including model attestation, point/page/relation reads, a computed field, policy
omission, observing a row update, and direct collection-relation grouping in a root-plus-batch
two-step plan. `commerceIntegrationTest` repeats the complete generate-to-serve chain in an
isolated output tree for an unrelated customers/orders model. It asserts that every packaged
entry point belongs to the generated carrier class and that no demo-blog or article routine is
present, then proves nested reads, batching, counts, cursor continuation, fail-closed context
filtering, live mutations, and restart visibility on PostgreSQL and MySQL.

## Deliberate Remaining Boundary

The generated carriers back `titan.graphql.execution.mode=compiled`. The separate `sql` mode still
invokes `DemoBlogTitanGraphqlFunctions` as a transitional whole-request kernel so its equivalence
corpus remains useful while carrier coverage grows. Compiled mode has no fallback to that kernel.

Before the carrier route can replace it, generation and generic runtime invocation must cover:

- all generated scalar filters and declared order paths;
- exact visible counts;
- composite and non-integer point keys;
- protected-field and protected-relation policy-specific branches;
- relation arguments, relation connections, cursors, and counts;
- nested multi-level relation batching and relation-connection batching without N+1;
- portable null ordering and scalar/null value preservation;
- generic mutation routing at the application boundary.

No release claim should describe the legacy `sql` mode as schema-portable. Compiled mode may be
described as schema-driven only for its verified plan subset until all items above are closed.
