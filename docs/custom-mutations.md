# Custom mutations

Application mutations are explicit reviewed database operations. Titan GraphQL does not infer
table CRUD from schema metadata and does not dispatch writes to JVM provider beans. A model
registers a GraphQL field, typed input and payload, role/policy requirements, and a source-local
procedure handler that the generated package can attest. See
[model-document-format.md](model-document-format.md) for the document shape.

The installed engine selects a mutation only when the HTTP transport permits writes. It
prevalidates the operation, input object, nested scalar/enum bindings, policy, and handler
identity before effects. Mutation roots execute serially in one request transaction. A later
root failure rolls back earlier domain writes and their receipt, audit, idempotency, and outbox
records. Reviewed handlers must apply domain-specific authorization and locking that model
roles alone cannot express. They should not record secrets or unredacted sensitive input in
audit or outbox payloads.

The first-deployment Commerce package proves nullable value/omission/null writes, nested input,
prevalidation, reviewed procedure policy, row locks, rollback, durable audit and idempotency,
transactional outbox delivery, and replay after a frontend restart on PostgreSQL and MySQL. The
management package uses the same database request boundary for job-enqueuing mutations; a
separate worker performs file/build work after commit. The container gate proves import,
validation, artifact, and review job polling across a worker restart.

Generated CRUD, arbitrary application-supplied Java handlers, general nested output payloads,
and subscriptions are outside this versioned contract. Review the handler source and package
inventory together, then run the installed and standalone-HTTP mutation gates described in
[verification.md](verification.md). A transpilable method alone does not authorize publication.
