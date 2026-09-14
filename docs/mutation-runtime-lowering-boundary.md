# Titan GraphQL Mutation Runtime Boundary

Status: implemented management boundary plus explicit application-handler extension.

Titan GraphQL keeps reads schema-generated and writes explicit. It supports two mutation surfaces:

- `/admin/graphql` exposes named Titan GraphQL management commands; and
- compiled application `/graphql` may expose named application commands registered by the host.

No application mutation root exists unless the application registers one. Public read types never
imply insert, update, delete, or nested-write operations.

## Shared Runtime Contract

Both surfaces use `GraphqlMutationDescriptor` and `GraphqlMutationExecutor`. The shared runtime:

- parses and selects a mutation operation;
- requires exactly one top-level mutation field;
- validates the descriptor-declared `input` object;
- coerces the supported scalar input types (`String`, `ID`, `Boolean`, and `Int`);
- rejects unknown, missing, incorrectly typed, or null required inputs;
- requires authenticated/authorized roles declared by the descriptor;
- resolves exactly one handler by stable command name;
- validates direct scalar payload selections and aliases;
- returns GraphQL-shaped success and error bodies; and
- delivers descriptor-selected attempt/result audit events.

Mutation descriptors are also the source for mutation SDL and introspection. Duplicate mutation
names, duplicate commands, missing handlers, and handlers without descriptors fail initialization.

POST may carry registered mutations. GET is query-only and rejects mutation or subscription
operations before runtime dispatch.

## Application Commands

Applications register immutable descriptor/handler sets through one or more
`GraphqlApplicationMutationProvider` CDI beans. Direct embedding can pass the same provider to
`GraphqlExecutionEngine` or `TitanCompiledGraphqlRuntime`. Providers are snapshotted during runtime
initialization so schema publication, validation, and dispatch see one consistent registration set.

The handler receives the descriptor, coerced input map, and trusted `GraphqlRequestContext`, then
returns `GraphqlMutationCommandResult`. The application handler owns:

- domain validation and authorization beyond descriptor roles;
- database/service calls;
- transaction boundaries and rollback;
- idempotency and retry semantics;
- concurrency control; and
- correctness-critical audit records or an outbox committed with the domain change.

`TransactionMetadata` and idempotency metadata describe the application contract. They do not cause
Titan GraphQL to wrap an arbitrary handler in a transaction. The optional runtime audit sink is for
event delivery/observability and must not throw. `GraphqlMutationAuditLog` is thread-safe but
in-memory and intended only for tests and development.

See [custom-mutations.md](custom-mutations.md) for the registration API and an example.

## Management Commands

The administrative surface is disabled unless a server-side bearer token is configured. Its actor
and role come from server configuration, not request-context headers.

The default `file` management store is a single-process development boundary. With
`titan.graphql.management.store=jdbc`, `TitanGraphqlDurableManagementStore` uses Titan GAP-006
transaction, idempotency, audit, and deployment records plus a GraphQL-owned product journal. The
JDBC store is proven on PostgreSQL 16 and MySQL 8.4 and survives a new store instance. Deployment
activation additionally requires matching Titan GAP-005 artifact/install-verification evidence.

The durable management path covers `importModelDocument`, validation, artifact generation,
operation review, and deployment readiness using the explicitly documented store contracts. It is
not a general deployment orchestrator.

## Security and Failure Rules

- The default application HTTP context has no actor role.
- Caller-supplied `X-Titan-*` headers are ignored unless the trusted-gateway switch is enabled.
- An unregistered application mutation is rejected.
- A descriptor requiring authentication rejects a blank actor role.
- Required roles are matched exactly.
- Input and payload inclusion in audit events is opt-in metadata and must be reviewed for sensitive
  data.
- Handler failures return deterministic GraphQL errors and emit a failure event when configured.
- A runtime audit sink must not throw; transactional audit belongs inside the handler/store boundary.
- Reads after a successful application write still use only generated carriers.

## Verification

Unit tests cover descriptors, coercion, aliases, authorization, dispatch, handler failures, and audit
statuses. HTTP tests prove GET rejects mutations and anonymous callers receive no implicit role.
The unrelated commerce integration test registers a custom `renameCustomer` command, rejects an
anonymous attempt, performs an authorized live update, checks attempt/success audit events, and
observes the updated row through a fresh compiled engine on PostgreSQL and MySQL.

The management JDBC integration test proves durable state, transaction/idempotency behavior, audit
records, and deployment-evidence gating on both dialects.

## Explicitly Unsupported

- generated table CRUD;
- mutations inferred from object/root exposure;
- nested relation write graphs;
- arbitrary nested payload objects;
- SQL-transpiled application command handlers;
- application mutations in `java`, `jdbc`, or legacy `sql` modes;
- mutations over GET; and
- subscriptions.
