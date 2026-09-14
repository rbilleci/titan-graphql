# Generated Titan Routine Contract

Status: active compiled-read contract. Opt-in `compiled` mode consumes the supported subset; the
legacy `sql` route still uses the transitional demo whole-request kernel.

## Purpose

`titanGraphqlGenerateRoutines` converts one validated, reviewed `titan.graphql.yaml` document into
static Java carriers that Titan can analyze and transpile. Schema authors do not write read SQL or
Java resolvers. The generator owns physical table/column binding, stable method naming, parameters,
projection aliases, policy guards, pagination order, and model attestation.

The generated Java file lives under `build/generated/sources/titan-graphql/`. It is reproducible
build output and must not be committed. `titanTranspile` consumes it directly; `titanPackage`,
`titanVerifyInstall`, and `titanGraphqlBindPackage` depend on that chain.

## Current Emission

For each supported reviewed model the generator emits:

- `modelSemanticHash()`, returning the canonical lowercase SHA-256 of the normalized model;
- `readRoot<Name>(...)` for point roots;
- `readRoot<Name>Forward(...)` and `readRoot<Name>Backward(...)` for Relay roots;
- direction-specific forward/backward carriers for each reviewed local root sort path, with the
  declared stable tie-breaker included in both ordering and cursor predicates;
- per-predicate forward/backward carriers for every declared local scalar filter operator, plus
  arity-specific `in` carriers for 0, 1, 2, 4, 8, and 16 values;
- `countRoot<Name>(...)` for roots declaring exact visible counts;
- filter-specific count carriers so `totalCount` observes the same local predicate;
- `readRelation<Owner><Name>(...)` for direct relations, including a required allow predicate
  when the relation is protected;
- `readRelation<Owner><Name>Batch<N>(...)` at fixed arities 2, 4, 8, 16, 32, and 64,
  allowing a 100-parent page to batch in at most two static calls. Both forms include optional
  parameters for reviewed local integer equality arguments.

Every query uses prepared-statement parameters. Physical identifiers must satisfy the portable
unquoted identifier subset and unsafe computed templates are rejected during generation. Scalar
fields and relation keys with policies use `CASE WHEN ? = TRUE ... ELSE NULL` projections.
Protected relation reads additionally use `? = TRUE` in their row predicate. A model that cannot
be represented safely fails generation rather than emitting a partial unsafe query.

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
of unsafe identifiers, protected field/relation SQL guards, and reuse for unrelated blog and
commerce models.
`GeneratedTitanGraphqlReadsIT` installs the package and proves both dialect shapes against live
databases, including model attestation, point/page/relation reads, a computed field, protected-field
masking and authorized access, observing a row update, direct collection-relation grouping in a
root-plus-batch two-step plan, and recursive `articles.comments.author` batching with one read per
level. `commerceIntegrationTest` repeats the complete generate-to-serve chain in an
isolated output tree for an unrelated customers/orders model. It asserts that every packaged
entry point belongs to the generated carrier class and that no demo-blog or article routine is
present, then proves integer, string, native UUID, and composite point keys; nested reads;
batching; root and relation counts; root and relation cursor continuation; relation backward
windows; fail-closed context filtering; generated local scalar filtering with exact counts; live
mutations; protected-relation authorization/rejection; and restart visibility on PostgreSQL and
MySQL. Both schemas also prove stable one-hop
root ordering through reviewed non-null to-one relations. Both schemas prove relation connections
below collection roots with one generated
batch read rather than one child read per parent. The demo proof additionally covers bounded
Boolean filter composition, filters combined with custom ordering, and a reviewed one-hop to-one
relation filter on both dialects. The unrelated commerce proof covers composed string filters
combined with ordering.

## Deliberate Remaining Boundary

The generated carriers back `titan.graphql.execution.mode=compiled`. The separate `sql` mode still
invokes `DemoBlogTitanGraphqlFunctions` as a transitional whole-request kernel so its equivalence
corpus remains useful while carrier coverage grows. The default production package contains only
`GeneratedTitanGraphqlReads`; the legacy kernel has a separate package and test task. Compiled mode
has no fallback to that kernel.

Before the carrier route can replace it, generation and generic runtime invocation must cover:

- filter expressions beyond the static 3 OR-group by 3 AND-term DNF budget, multiple simultaneous
  custom order keys, and relation ordering beyond a non-null to-one hop;
- row-value policy expressions beyond the reviewed named role gates and context filters;
- to-many or multi-hop filter paths and SQL-side per-parent connection limiting;
- portable null ordering and scalar/null value preservation;
- generic mutation routing at the application boundary.

No release claim should describe the legacy `sql` mode as schema-portable. Compiled mode may be
described as schema-driven only for its verified plan subset until all items above are closed.
Root policies reject unauthorized operations before I/O and are repeated in root carrier
predicates. Type-level row policies gate root rows, exact counts, and relation targets. Protected
field values and relation keys are guarded in generated SQL by reviewed boolean policy
decisions supplied by the runtime. Protected relation carriers additionally include an allow
predicate, so direct invocation with a denied decision returns no relation rows. GraphQL validation
still rejects unauthorized selections before I/O; the SQL guard is a second boundary rather than
a masking substitute.
