# Model and Package Migration

A reviewed model, generated package, package binding, and compatible source schema form one versioned
contract. Titan GraphQL deliberately rejects mixed versions instead of guessing whether they are
compatible.

## Prepare a change

1. Update the reviewed model. Start from inference only when adopting an existing schema; inference
   is a conservative draft and does not publish roots, relations, or sensitive fields automatically.
2. Review every exposed root, field, filter, order path, relation, context filter, and named policy.
   Model root and type policies contribute to the semantic hash even when their lists are empty.
3. Validate and run the Docker-free suite with `./gradlew test`.
4. Generate and inspect the carrier source and model-facing artifacts. Generated files under
   `build/` are review evidence, not source files to commit.
5. Transpile, package, install-verify, and bind with the Gradle tasks described in
   [verification.md](verification.md). Do not hand-edit generated SQL or binding JSON.

## Roll forward

Use this order for a compatible release:

1. Apply reviewed application-schema migrations that the new model requires. Prefer additive
   changes while old application instances can still receive traffic.
2. Install the new Titan package and run install verification against the target dialect.
3. Deploy the application with the new model and exact `titan-graphql-package.json` binding.
4. Confirm database attestation and execute representative authorized, unauthorized, pagination,
   filtering, and relation reads.
5. Remove obsolete source columns or tables only after every old application/package version has
   left service and rollback no longer depends on them.

For a breaking GraphQL change, publish release notes and migrate clients before removing the old
surface. Renaming a GraphQL field while retaining the same physical column can provide an overlap
period without a destructive database migration.

## Roll back

Retain the previous application, reviewed model, binding, generated migrations, and rollback script
as a single release set. Quiesce traffic, restore the previous installed routines (using generated
rollback/install artifacts as reviewed for that release), deploy the matching previous application
set, and verify its attestation before reopening traffic.

Do not reuse the new binding with an old model or package. Do not assume generated rollback SQL
restores application rows or reverses source-schema changes. If a source migration removed or
rewrote data, recovery must follow the database backup and migration plan prepared for that change.

## Management-state compatibility

The default file management store is a development/single-process boundary. Copying build output or
preview registrations does not migrate management state. For durable administration, use the JDBC
store and include its schema/data in database backup, upgrade, and restore procedures. Test a fresh
process against the migrated store before promoting it.

## Release evidence

Record the source commit, submodule commits, normalized model semantic hash, Titan artifact and
manifest hashes, dialect, database version, package inventory, install-verification result, and the
commands from [verification.md](verification.md). This is enough to identify which model/package was
served without retaining local build caches or credentials.
