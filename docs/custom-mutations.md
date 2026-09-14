# Custom Mutations

Titan GraphQL generates read paths from the reviewed model. Application writes remain explicit code:
the application publishes mutation descriptors and dependency-injected command handlers through
`GraphqlApplicationMutationProvider`. No table CRUD is inferred from exposed GraphQL object types.

## Registration

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

The dual-dialect commerce integration proof registers `renameCustomer`, executes the write through
the compiled runtime, rejects an unauthorized caller, checks audit attempt/success events, and then
observes the changed row through generated reads. Broad generated CRUD, nested writes,
subscriptions, and SQL-transpiled application handlers remain unsupported.
