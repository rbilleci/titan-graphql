# titan-graphql

Titan GraphQL generates a database-resident GraphQL engine from a reviewed model. The standalone
HTTP frontend forwards a complete request to one installed PostgreSQL function or MySQL procedure.
It does not parse, plan, authorize, or assemble GraphQL on the JVM. Model inference, projection,
schema generation, artifact binding, and control-plane jobs remain build-time or operator tools.

This is a pre-1.0 project licensed under [GPL-3.0-or-later](LICENSE). It is not deployed today and
has no existing callers. The first deployment target is a container running the standalone frontend
beside a separately supervised control-job worker. There is no compatibility cutover to perform.

## What is implemented

The reviewed model defines public roots, fields, relationships, policies, context filters, and
explicit custom mutations. Generated packages for unrelated blog and commerce schemas run on
PostgreSQL and MySQL. Installed-package and standalone-HTTP tests cover document selection,
variables, fragments, typed keys, Relay windows, bounded relation batching, introspection,
tenant and policy isolation, serial atomic mutations, idempotency, audit, and transactional outbox
delivery. A separate management package handles database-backed job requests; a worker performs
model import, validation, artifact generation, and operation review. Preview publication binds a
reviewed package and operation registry to a deployment-owned descriptor.

The [M5 parity report](docs/database-engine-m5-parity.md) maps the fixed corpus and installed
assertions to the reviewed contract. The [M7 release report](docs/database-engine-m7-release.md)
records release verification and local measurements. The
[execution plan](docs/database-engine-execution-plan.md) tracks remaining milestone work. A passing
test of an old JVM or carrier path is not release evidence for the standalone service.

## Build and verify

Initialize the pinned Titan submodules and use JDK 21. Docker is required for installed-package,
HTTP, and container-deployment tests. Ordinary tests exclude Docker-tagged cases, but a fresh
checkout also uses Docker to generate the database catalog before compilation.

```bash
git submodule update --init --recursive
./gradlew test
./gradlew titanGraphqlVerifyDatabaseEngineBoundary titanGraphqlVerifyDatabaseFrontendBoundary titanGraphqlVerifyDatabaseHttpFrontendBoundary
./gradlew titanGraphqlDatabaseEngineReleaseCheck
./gradlew databaseEngineContainerDeploymentIntegrationTest
```

The release check generates, transpiles, packages, install-verifies, binds, and exercises the
whole-request engine on both dialects. It also extracts and starts the standalone HTTP ZIP. The
container gate runs the frontend and worker separately, restarts them, and serves a published
preview. These tasks do not deploy a persistent service or alter a live caller.

To build just the isolated frontend artifact and its verified descriptors, run
`./gradlew titanGraphqlVerifyDatabaseHttpFrontendReleaseArtifact`. See the
[frontend deployment guide](docs/database-http-frontend.md) and
[container walkthrough](deployment/README.md) for package installation and runtime configuration.

## Trust boundary

Install a package only with its matching reviewed model, manifest, identity sidecars, and frontend
descriptor. The frontend sends the expected model, runtime, and package identities on every call;
the database rejects mismatches before executing GraphQL. Request-context headers are ignored by
default. Enable them only behind an authenticated gateway that removes caller-supplied copies.
The management route requires its own database package and bearer token. Read
[SECURITY.md](SECURITY.md) before exposing either route.

The source-model format is in [docs/model-document-format.md](docs/model-document-format.md), the
active request contract in [docs/query-contract.md](docs/query-contract.md), and operator procedures
in [docs/operations.md](docs/operations.md). [docs/verification.md](docs/verification.md) maps
local checks to their evidence.

[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) records dependency licenses and corresponding
source locations. The runtime ZIPs include the project license and dependency notices.
