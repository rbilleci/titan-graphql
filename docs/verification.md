# Verification

Use JDK 21 and initialized Titan/Titan DSL submodules. Installed-package, standalone HTTP, and
container-deployment checks require local Docker with PostgreSQL and MySQL fixtures. This project
does not use hosted GitHub Actions; maintainers run the local gates on demand.

## Local release gates

From a clean checkout, run `scripts/release-check.sh` for repository hygiene and Docker-free
tests, then `scripts/release-check.sh --full` for the deployable database-engine suite. The full
gate approves the standalone HTTP ZIP only. It checks the engine/frontend source boundaries,
dependency and ZIP closure, generated SQL package privacy, complete package attestation,
PostgreSQL/MySQL installed execution, fixed expected-result corpus, package replacement,
snapshot/deadline behavior, and extracted ZIP HTTP behavior. The separate
`databaseEngineContainerDeploymentIntegrationTest` proves unprivileged frontend and worker
containers, worker restart, reviewed preview publication, and frontend recreation on both
dialects. No command deploys a persistent service.

| Task | Evidence | Docker |
| --- | --- | --- |
| `./gradlew test` | Docker-free model, generator, engine-source, artifact, policy, and control-plane unit tests | No |
| `./gradlew titanGraphqlVerifyDatabaseEngineBoundary titanGraphqlVerifyDatabaseFrontendBoundary titanGraphqlVerifyDatabaseHttpFrontendBoundary` | Source-set isolation and absence of a JVM GraphQL engine in the frontend | No |
| `./gradlew titanGraphqlVerifyDatabaseHttpFrontendReleaseArtifact` | Exact standalone ZIP launcher, class, and runtime dependency closure | No |
| `./gradlew databaseEngineIntegrationTest databaseEngineMySqlIntegrationTest` | Direct installed blog package on PostgreSQL/MySQL | Yes |
| `./gradlew databaseEngineCommerceIntegrationTest databaseEngineCommerceMySqlIntegrationTest` | Direct installed unrelated commerce package on PostgreSQL/MySQL | Yes |
| `./gradlew databaseManagementStoreIntegrationTest` | Durable JDBC management-store and activation tests on both dialects | Yes |
| `./gradlew databaseHttpFrontendIntegrationTest` | Extracted standalone ZIP and HTTP transport on both dialects | Yes |
| `./gradlew titanGraphqlDatabaseEngineReleaseCheck` | Combined deployable package/runtime parity and artifact gate | Yes |
| `./gradlew databaseEngineContainerDeploymentIntegrationTest` | First-deployment container path, management worker, restart, and published preview | Yes |

The [M5 parity report](database-engine-m5-parity.md) records the fixed corpus, identity checks,
measurements, and last complete parity run. A package is not deployable just because its Java
source transpiles; scratch installation, binding, and runtime attestation must pass. A test of an
obsolete JVM or carrier path cannot substitute for a standalone-ZIP result.

The built-in privacy checks are deliberately bounded. Before public publication, also use an
independent history-aware secret scanner and review repository history, tracked temporary files,
dependency pins, notices, and GPL consistency as directed by [RELEASING.md](../RELEASING.md).
Keep the first actionable failure and generated diagnostics, fix source or model, then rerun the
affected gate. Do not edit generated reports to alter their status.
