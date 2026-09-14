# Changelog

All notable public-release changes are recorded here.

## Unreleased

- Added GPLv3 licensing and public-project governance material.
- Made HTTP request-context headers opt-in and protected the management endpoint with a
  server-configured bearer token.
- Added a reproducible submodule-based source layout and local release checklist.
- Added schema-driven compiled point, connection, count, filter, ordering, computed-field,
  direct-relation, relation-connection, and recursively batched nested-relation carriers.
- Added generic policy compilation for reviewed root, type-row, field, and relation gates, with
  authorization predicates enforced in generated SQL on PostgreSQL and MySQL.
- Added a second, unrelated commerce schema proof to detect demo-specific runtime and package
  coupling across both supported database dialects.
- Added model/package attestation, deterministic binding, install verification, rollback output,
  and operator, migration, security, and verification guidance for public releases.
- Added explicit dependency-injected custom application mutation handlers to compiled mode, with
  descriptor validation, authorization, payload shaping, and thread-safe audit delivery; the live
  commerce proof executes the extension on both dialects.
- Removed the implicit HTTP `reader` role, enforced query-only GET requests, exposed compiled
  deployment fingerprints in response headers, packaged both serving JDBC drivers, and added
  nullable-scalar/introspection proof.
- Replaced demo-named filter/order variable coercion with active-schema input metadata and proved
  unrelated commerce filter, order, and custom-mutation input variables on both dialects.
