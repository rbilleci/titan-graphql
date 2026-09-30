# Custom Mutations

Titan GraphQL generates read paths from the reviewed model. The database-serving path also registers
each write explicitly in that model; it does not infer table CRUD from exposed object types. A
mutation may use a generated `update` binding or a reviewed, Titan-transpiled `procedure` handler.
Use `createProcedure` when the reviewed handler creates the modeled target row. The engine
prevalidates typed arguments and policies before calling that handler, then reads the created row
by its reviewed key for payload completion. A missing created row or a handler failure rolls the
request back, including any earlier serial mutation root.

The `procedure` binding names a source-local class and method and declares statement and row budgets.
The build verifies its method signature and includes its generated routine in the package. The handler
receives a `Connection`, followed by the modeled binding values. Each nullable binding additionally
passes `present` and `explicitNull` booleans, so omission, a supplied value, and explicit GraphQL
`null` remain distinct. The handler must use those flags when deciding whether to write SQL `NULL`.
Bindings may come from flat mutation arguments or paths inside an authored input object. The
Commerce package exercises `patch.nickname` from a nested input for omission, a value, and explicit
null on PostgreSQL and MySQL.
Titan rejects a conditional JDBC setter for an update executed outside that branch; keep
prepare/bind/execute in one branch or express the null choice in a bound SQL expression.
The database engine performs operation prevalidation, policy checks, serial root dispatch, and
transactional completion around the handler. Reviewed handlers cannot rely on a JVM callback during
database serving.

The descriptor/provider API below belongs to the retained compiled-runtime migration path, not the
standalone database-serving package.

## Legacy compiled-runtime registration

A provider returns:

- `GraphqlMutationDescriptor` values defining the public name, command identity, typed input,
  scalar payload, role requirements, transaction metadata, and audit metadata;
- one `GraphqlMutationCommandHandler` for each descriptor command identity; and
- an optional `GraphqlMutationAuditSink`.

In Quarkus, expose one or more provider beans. `GraphqlExecutionEngine` combines them at compiled
runtime initialization and fails fast on duplicate names/commands, missing handlers, or orphaned
handlers. Non-CDI embedding can pass a provider to the public `GraphqlExecutionEngine` or
`TitanCompiledGraphqlRuntime` constructor.

```java
GraphqlMutationDescriptor rename = new GraphqlMutationDescriptor(
    "renameCustomer",
    "commerce.renameCustomer",
    "Rename one customer.",
    new GraphqlMutationDescriptor.InputObject("RenameCustomerInput", List.of(
        new GraphqlMutationDescriptor.InputField("customerId", "Int", true, "Customer id."),
        new GraphqlMutationDescriptor.InputField("name", "String", true, "New name."),
        new GraphqlMutationDescriptor.InputField("requestKey", "ID", true, "Idempotency key.")
    )),
    new GraphqlMutationDescriptor.PayloadObject("RenameCustomerPayload", List.of(
        new GraphqlMutationDescriptor.PayloadField("customerId", "Int", true, "Customer id."),
        new GraphqlMutationDescriptor.PayloadField("name", "String", true, "Stored name.")
    )),
    new GraphqlMutationDescriptor.AuthorizationMetadata(
        true, "canRenameCustomer", List.of("operator")),
    new GraphqlMutationDescriptor.TransactionMetadata(
        GraphqlMutationDescriptor.TransactionMode.REQUIRED, "requestKey", "reject"),
    new GraphqlMutationDescriptor.AuditMetadata(
        GraphqlMutationDescriptor.AuditMode.ATTEMPT_AND_RESULT,
        "customer_renamed", false, false)
);

GraphqlApplicationMutationProvider provider = GraphqlApplicationMutationProvider.of(
    List.of(rename),
    Map.of("commerce.renameCustomer", request -> {
        // Call an application service that owns its database transaction and idempotency.
        return GraphqlMutationCommandResult.of(Map.of(
            "customerId", request.input().get("customerId"),
            "name", request.input().get("name")
        ));
    }),
    durableAuditSink
);
```

The shared executor owns operation selection, input validation/coercion, role checks, exact command
dispatch, scalar payload selection/aliases, GraphQL-shaped errors, and redaction-aware audit event
delivery. A handler owns domain validation, transaction boundaries, idempotency, persistence, and
rollback. Declaring `TransactionMode.REQUIRED` is metadata and does not cause Titan GraphQL to wrap
an arbitrary handler automatically.

The `input` argument may be an inline object or a JSON variable whose declared GraphQL type exactly
matches the descriptor input-object name.

## Security and transport

Custom mutations are accepted only when registered and only over POST. GET remains query-only.
Authorization uses the trusted `GraphqlRequestContext`; the default HTTP context has no actor role,
and caller context headers are ignored unless the trusted-gateway switch is enabled.

Set `includeInput` or `includePayload` only after classifying every field. The built-in
`GraphqlMutationAuditLog` is a thread-safe in-process collector for tests/development, not durable
production evidence. Audit sinks must not throw. When audit is correctness- or compliance-critical,
the handler must write it atomically with the domain change (for example through a transactional
outbox); the runtime hook alone is not a transaction boundary.

The compiled-runtime commerce proof registers `renameCustomer`, executes the write, rejects an
unauthorized caller, checks audit attempt/success events, and observes the changed row through
generated reads. This path is a migration oracle and does not establish database-serving parity.
