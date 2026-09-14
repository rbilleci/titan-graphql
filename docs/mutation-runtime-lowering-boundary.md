# Titan GraphQL Mutation Runtime Lowering Boundary

Status: DXR8.4 planning boundary.

This document records the support profile for the minimal named mutation
runtime introduced in DXR8. It does not promote application mutation execution.
It defines the line between Java-mode reference behavior, production management
deployment requirements, and still-reserved application CRUD work.

Durability terminology (`TG-BLK-003`, CLOSED): core dogfooded the management
store — it now ships a durable JDBC-backed transactional store
(`JdbcTransactionalMutationStore` over Titan-transpiled routines, with JDBC
idempotency/audit stores and `ManagementSchemaInstaller`), proven on PG 16 +
MySQL 8.4. The consumer runs on it in the opt-in `jdbc` mode
(`titan.graphql.management.store=jdbc`): there "durable" means a real JDBC store
on transpiled routines, exercised on live PG + MySQL by
`GraphqlJdbcManagementStoreIT`. The DEFAULT `file` mode wraps
`FileTransactionalMutationStore` (file-backed, single-process), and "durable"
there means that file boundary. Either way the GAP-006 transaction / idempotency
/ audit semantics are the same contract; only the persistence substrate differs.
Tracked as `TG-BLK-003` (CLOSED) in `docs/titan-blocker-register.md`.

The current query-contract conformance matrix remains query-only. The rows below
are the developer-experience mutation-runtime profile that future `/admin/graphql`
work must satisfy before management mutations are considered production-ready.

## Current Boundary

DXR8 intentionally built mutation support in three small layers:

- selected mutation operations can be parsed as first-class GraphQL operations
- schemas can declare named command-style mutation descriptors
- an internal Java-mode executor can dispatch one selected mutation to an
  explicit command handler and produce GraphQL-shaped output

Application `/graphql` remains query-only. The existing runtime and lowered
entrypoints still reject mutation execution for application traffic, and schema
introspection still reports no mutation root for the bounded demo application
schema.

The Java-mode executor is a reference boundary for the management API, not a
public application write surface. It proves the shape of typed inputs, payloads,
authorization checks, handler dispatch, error envelopes, and audit events before
DXR9 wires those pieces into `/admin/graphql`.

## Java-Mode Only For Now

These pieces may remain Java-mode-only while DXR9 starts the management API:

- in-process command handler registration for tests and early management
  services
- in-memory audit sinks used by focused unit tests
- Java object maps used as the command input and payload exchange format
- direct payload field rendering for scalar-like payload fields
- descriptor instances built directly in Java for the first management model
- management store behavior backed by an in-memory test store

Those pieces are allowed only behind the management/control-plane boundary.
They must not be exposed through the application `/graphql` endpoint as
production application mutations.

## Must Be Production-Safe Before Management Deployment

Before `/admin/graphql` management mutations can be deployed as production
control-plane behavior, these capabilities need lowerable behavior or an
explicit production-safe service boundary:

- mutation descriptor materialization from generated management model artifacts
- request-envelope parity for mutation operations, `operationName`, variables,
  and reserved extensions
- deterministic input coercion and validation for all descriptor-declared input
  fields used by management mutations
- generated mutation SDL, introspection metadata, and artifact manifest hashes
- actor and role extraction through the management HTTP context boundary
- authorization decisions with stable GraphQL-shaped error output
- transaction handling for state-changing management operations
- idempotency-key handling for retryable management mutations
- durable audit attempt and result records with actor, request id, mutation
  name, timestamp, outcome, and failure code
- handler execution that is safe for the selected deployment mode
- conformance artifact generation that distinguishes accepted, Java-only,
  pending, and out-of-scope mutation-runtime rows

For management writes, "lowerable" does not require every command handler to be
transpiled into SQL. It does require that the product has one explicit execution
contract for the chosen deployment mode. A management mutation may call a
bounded Java service if that service owns transaction, audit, authorization,
idempotency, and artifact consistency. It must not silently depend on a private
controller path that bypasses the shared GraphQL mutation runtime.

## Still Reserved

The following remain outside DXR8 and should stay out of DXR9 unless a later
roadmap explicitly reopens them:

- broad application CRUD generation
- nested relation mutation graphs
- implicit table writes derived from object types
- subscription execution
- application `/graphql` mutation root publication
- SQL/lowered application write execution
- preview endpoint application mutations
- UI-only mutation paths that bypass `/admin/graphql`

## Mutation Runtime Conformance Profile

Classifications match the existing query conformance vocabulary:

- `ACCEPTED`: production-supported for the named management mutation surface.
- `JAVA_ONLY`: implemented in Java-mode, but not production-supported until the
  selected deployment boundary has parity and artifact coverage.
- `PENDING`: required by the accepted DX roadmap, but not implemented yet.
- `OUT_OF_SCOPE`: intentionally excluded from the minimal mutation runtime.

| ID | Area | Behavior | Classification | Current Evidence | Next Work |
| --- | --- | --- | --- | --- | --- |
| DXR8-MUTATION-PARSE | Operations | Selected named mutation operations can be parsed as first-class AST operations | ACCEPTED | `/admin/graphql` selects named mutation operations through `GraphqlParser.parseSelectedOperation`; application/runtime query paths still reject mutations. | Keep application `/graphql` writes rejected unless a later application-write roadmap reopens them. |
| DXR8-MUTATION-DESCRIPTOR | Schema | Named command-style mutation descriptors declare typed inputs, typed payloads, authorization, transaction, idempotency, and audit metadata | ACCEPTED | `GraphqlManagementMutationSupport` attaches management mutation descriptors to the admin schema, with descriptor metadata guarded by mutation descriptor and admin introspection tests. | Generate descriptors from the dogfood management model in a later backend expansion, without changing the accepted descriptor contract. |
| DXR8-MUTATION-EXECUTOR | Execution | Java-mode executor dispatches one selected mutation to a registered command handler | ACCEPTED | `GraphqlMutationExecutor` is the shared management executor behind `/admin/graphql`; `importModelDocument` executes through the durable selected-store path while preserving GraphQL-shaped responses. | Keep broad application mutation execution out of scope. |
| DXR8-MUTATION-INPUTS | Inputs | Descriptor-declared input object fields are coerced and validated | ACCEPTED | Admin endpoint and mutation executor tests cover descriptor input coercion, variable-backed YAML input, deterministic invalid-input errors, and GAP-006 command validation for `importModelDocument`. | Broaden only as management descriptors require new scalar or object shapes. |
| DXR8-MUTATION-PAYLOADS | Payloads | Descriptor-declared payload fields render with GraphQL response keys and aliases | ACCEPTED | Management mutation tests cover GraphQL-shaped payloads for import, validation, artifact generation, and operation review; unsupported nested payload selection remains rejected by the shared executor. | Define nested payload rules only when management payload descriptors need them. |
| DXR8-MUTATION-AUTHZ | Authorization | Actor and role metadata can reject unauthorized mutation attempts | ACCEPTED | `/admin/graphql` extracts actor role/id and request context into `GraphqlRequestContext`; shared executor and GAP-006 command-context tests reject unauthorized or incomplete management attempts. | Add richer policy metadata only when management roles outgrow the current operator/admin/platform shape. |
| DXR8-MUTATION-AUDIT | Audit | Mutation attempt/result records are emitted | ACCEPTED | Durable `importModelDocument` records Titan GAP-006 audit attempts/outcomes, including idempotency replay, conflict, and validation failure paths; Java audit events remain test/reference evidence for product-only mutations. | Move additional management mutations to Titan command audit only when Titan has product-neutral commands for them. |
| DXR8-MUTATION-TX | Transaction | Transaction and idempotency metadata influence execution | ACCEPTED | Durable `importModelDocument` uses Titan GAP-006 transactions and idempotency records: same-key retries replay, mismatched input conflicts, failed validation writes no draft state, and audit evidence remains durable. | Do not claim Titan transaction semantics for product-only wrapper journal mutations. |
| DXR8-MUTATION-GENERATED-SCHEMA | Generated schema | Mutation root, input objects, payload objects, and introspection metadata are generated from descriptors | ACCEPTED | `GraphqlSchemaPrinter` and admin introspection expose the management `Mutation` root, input objects, payload objects, and mutation fields only on `/admin/graphql`; application introspection still reports no mutation root. | Move descriptor source from Java bootstrap to generated management artifacts later. |
| DXR8-MUTATION-LOWERED | Lowering | Mutation request execution has a production-safe lowered or service-backed deployment contract | ACCEPTED | The selected production management boundary is service-backed: `TitanGraphqlDurableManagementStore` consumes Titan GAP-006 transaction, idempotency, audit, deployment activation, and durable records, plus GAP-005 artifact verification for deployment-ready claims. | Keep SQL/lowered application writes and broad deployment orchestration reserved. |
| DXR8-APP-CRUD | Application CRUD | Broad generated application CRUD mutations | OUT_OF_SCOPE | The DX proposal explicitly excludes full CRUD from the first mutation slice. | Revisit only in a later application mutation roadmap. |
| DXR8-NESTED-WRITES | Application CRUD | Nested relation mutation graphs | OUT_OF_SCOPE | No descriptor or executor support exists by design. | Revisit only after named command-style management mutations are proven. |

## Promotion Rule

No mutation row may be promoted to `ACCEPTED` for production management use
until it has:

- descriptor metadata and generated artifact coverage
- request-envelope and variable behavior covered at the management endpoint
- deterministic GraphQL-shaped success and error output
- authorization, audit, and transaction/idempotency behavior appropriate for
  the selected deployment mode
- explicit conformance artifact coverage

Application mutation execution remains `OUT_OF_SCOPE` until a later roadmap
creates a separate application write contract.

## Handoff Evidence

`TG-HANDOFF-M2.1` wires the first GAP-006 command-context adapter for
`/admin/graphql` `importModelDocument`: `TitanGraphqlGap006CommandContext`
maps the GraphQL-owned field/input/header names to Titan-neutral
`ManagementCommands.CommandInvocation` data and validates actor, request id,
idempotency key, workspace scope, source format, and source text against the
Titan `management.importModelDocument` descriptor before the current Java-mode
handler runs. This is endpoint context evidence only; it does not promote the
transaction, durable audit, durable idempotency, generated schema, or
lowered/service rows.

`TG-HANDOFF-M3.1` adds the first durable management store adapter:
`TitanGraphqlDurableManagementStore` wraps Titan GAP-006
`ManagementTransactions.TransactionalMutationStore` records for durable
drafts, GAP-005 artifact refs, deployments, idempotency, audit, transaction
framing, and deterministic Titan-neutral reads. This is durable core-store
evidence, but it does not yet promote mutation runtime rows to `ACCEPTED`:
the default Java-mode endpoint handler is still not migrated through the
transactional execution path, generated mutation schema/conformance artifacts
are still pending, and GraphQL-only wrapper records still need a consumer-side
persistence decision.

`TG-HANDOFF-M4.1` migrates the `importModelDocument` mutation to the Titan
GAP-006 transactional execution path when the GraphQL-owned durable store
setting selects `TitanGraphqlDurableManagementStore`. The mutation preserves
GraphQL-shaped output while Titan records command validation, audit
attempt/outcome rows, idempotency replay/conflict state, and the durable draft
row in a transaction-framed store. Same-key same-input retries replay through
Titan, same-key different-input retries fail without adding another draft, and
missing idempotency is audited without writing draft state. This is durable
mutation evidence for `importModelDocument` only. The mutation-runtime rows
remain unpromoted overall because generated mutation schema/conformance
artifacts are still pending and `validateModelDraft`, `generateModelArtifacts`,
`approveObservedOperation`, and `rejectObservedOperation` still need a
truthful product-side persistence decision.

`TG-HANDOFF-M4.2` adds that product-side persistence decision for the remaining
existing management mutations. The durable store keeps GraphQL wrapper state in
a GraphQL-owned product-state journal next to the Titan transaction log, so
`validateModelDraft`, `generateModelArtifacts`, `approveObservedOperation`,
and `rejectObservedOperation` can reload validation report refs, GraphQL
artifact refs, observed operation documents, and registry presentation records.
This does not promote those mutations to Titan command/idempotency/transaction
evidence: the journal is a `titan-graphql` adapter concern, and Titan remains
limited to product-neutral GAP-006 records plus GAP-005 artifact refs when
GAP-005 metadata is explicitly supplied.

`TG-HANDOFF-M5.1` ties durable preview and deployment readiness to Titan
artifact evidence. Verified preview-build manifests are accepted only when the
durable store has a Titan GAP-005 artifact ref with passed install
verification, matching manifest path/content hash, matching generated SQL hash,
and matching GraphQL SDL hash. Durable active deployment records must be
created through Titan GAP-006 `activateDeployment`, which records audit,
idempotency, artifact hash, GAP-005 evidence, verification status, environment,
and activation state in Titan-neutral records. This is deployment-consistency
evidence, not broad deployment orchestration and not generated mutation schema
or conformance promotion.

`TG-HANDOFF-M6.1` closes the current handoff by promoting the bounded
management mutation runtime rows to `ACCEPTED` for `/admin/graphql`. The
promotion is intentionally scoped: the accepted surface is the named management
mutation runtime with a durable service-backed boundary selected through
`TitanGraphqlDurableManagementStore`, Titan GAP-006 transaction/idempotency/audit
evidence for `importModelDocument`, Titan GAP-005 artifact verification evidence
for deployment-ready claims, and GraphQL-owned generated schema/introspection
for the admin endpoint. This does not promote application `/graphql` mutation
execution, broad CRUD, nested writes, SQL-lowered application writes, product
UI behavior, or broad deployment orchestration.
