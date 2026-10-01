# Changelog

## 0.1.0

The database engine migration replaces the JVM and Quarkus serving paths with a standalone HTTP
frontend that forwards each whole request to an installed PostgreSQL function or MySQL procedure.
The reviewed model drives database parsing, validation, coercion, policies, reads, introspection,
and serial custom mutations. Durable audit, idempotency, and transactional outbox behavior accompany
domain writes. Management jobs use the same database contract and a separate control worker;
container tests prove worker restart and reviewed preview publication.

The local release gate exercises unrelated schemas on both databases, fixed expected results,
grammar-generated equivalent documents, transaction and replacement failures, artifact closure,
privacy, and the container path. Runtime ZIPs include project and dependency license notices.
The project requires JDK 21 and local Docker for the complete build and release checks.

This pre-1.0 release supports the reviewed `titan.graphql/v1alpha1` model surface documented in
`docs/query-contract.md`. It has no existing deployed service or caller migration. The removed
execution modes, legacy entry shapes, and Quarkus commands have no compatibility fallback;
`docs/migration.md` describes the replacement workflow.
