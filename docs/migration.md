# Model and package changes

A reviewed model, compatible source schema, generated dialect package, binding, identity
sidecars, and frontend descriptor form one versioned deployment unit. Titan GraphQL rejects
mismatched identities instead of guessing compatibility. There is no running service or caller
today, so the first container deployment requires no cutover or client migration.

For a model change, review every newly exposed root, field, relation, filter/order path, context
predicate, policy, and mutation handler. Run the Docker-free tests, generate and inspect the new
engine binding and SQL, install-verify it on PostgreSQL and MySQL, and run the standalone-HTTP
gate. Generated files under `build/` are review evidence, not source files to commit. Do not
hand-edit SQL, package metadata, or a deployment descriptor.

For an eventual live upgrade, first apply compatible source-schema changes, then install and
verify the new package, publish its matching descriptor and frontend distribution, and attest
authorized, denied, read, and write requests before admitting traffic. Keep the previous complete
release set until rollback is no longer needed. Quiesce traffic before restoring the previous
installed package and descriptor. Generated rollback SQL affects package objects, not source rows;
use a separate backup/data plan for destructive schema changes.

Durable management records, control jobs, and operation registries live in the JDBC store and
must be included in database backup, upgrade, and restore procedures. A published preview also
depends on its installed package and sealed descriptor. The standalone frontend reads the preview
registry at startup, so restart it after switching a mapping.

Record source and submodule commits, normalized model hash, runtime/package identities, dialect,
database version, manifest and object inventory, install-verification result, and the local
commands from [verification.md](verification.md). Never retain credentials in release evidence.
