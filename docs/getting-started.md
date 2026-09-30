# Getting started

Titan GraphQL turns a reviewed database projection into an installed whole-request package. A
standalone HTTP/JDBC process forwards each GraphQL request to that package. It has no JVM GraphQL
execution mode. No service or callers currently run; the first deployment target is a container.

Use JDK 21, the checked-in Gradle wrapper, and initialized Titan/Titan DSL submodules. Docker is
needed for scratch install and live PostgreSQL/MySQL verification.

```bash
git submodule update --init --recursive
./gradlew test
./gradlew titanGraphqlDatabaseEngineReleaseCheck
```

The release check builds and binds the blog and commerce proof packages independently, verifies
their installation on both dialects, runs direct and standalone-HTTP corpus tests, and checks the
isolated frontend ZIP. Run `./gradlew databaseEngineContainerDeploymentIntegrationTest` to prove
separate frontend and worker containers, a worker restart, preview publication, and frontend
recreation. These commands do not create a persistent deployment.

## Review a model

Titan's `titanIntrospect` task can produce `build/titan/schema.json` from configured DDL or a
database. `TitanGraphqlSchemaInference` converts metadata into a conservative review draft. It
retains tables, scalar columns, keys, and foreign-key candidates without automatically publishing
roots or sensitive fields. Author the approved projection in `titan.graphql.yaml` according to
[model-document-format.md](model-document-format.md). The blog and commerce proof models under
`src/test/resources/graphql/` show two unrelated schemas. Review every public root, field,
relation, filter, order path, computed field, context filter, policy, and mutation descriptor.

The checked-in proof tasks select their own model and produce separate generated sources, SQL,
packages, and descriptors. Do not hand-edit these outputs or reuse a descriptor with another
package. The binding and installed routine check model, runtime, and package identities before
executing GraphQL. [database-http-frontend.md](database-http-frontend.md) lists the package and
descriptor build tasks, environment variables, request context, and transport behavior.

## Run a container deployment

Use [deployment/README.md](../deployment/README.md) for the first container setup. Install the
application and management packages on a supported database, then run the unprivileged frontend
and the separately supervised control-job worker with their matching descriptors and secrets.
The management package enqueues import, validation, artifact, and review jobs; the worker processes
them after the request commits. Published previews require a reviewed package, an ENFORCE operation
registry, and a sealed deployment descriptor. The standalone frontend loads its preview registry
at startup, so restart it after publishing or replacing a mapping.

The [query contract](query-contract.md) defines the supported GraphQL surface and fail-closed
limits. [SECURITY.md](../SECURITY.md) describes the trust boundary. Local verification evidence
is recorded in [database-engine-m5-parity.md](database-engine-m5-parity.md) and
[verification.md](verification.md).
