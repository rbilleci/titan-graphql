# Verification

The project intentionally does not use GitHub Actions. Maintainers run the checked-in local gate and
attach its result to the release or pull request. JDK 21 is required; full verification also requires
Docker capable of running the PostgreSQL and MySQL Testcontainers fixtures.

## Release gates

From a clean checkout with initialized submodules, run:

```bash
scripts/release-check.sh
scripts/release-check.sh --full
```

The fast gate checks the repository origin, clean worktree, submodule pins, tracked temporary files,
high-confidence credential patterns, machine-local absolute paths, author email privacy, GPL license,
and the Docker-free unit suite. The full gate approves only the deployable standalone HTTP ZIP: it
verifies the transpilable-engine and thin-frontend boundaries, rejects an unreviewed runtime closure,
directly installs and invokes independently packaged demo and commerce whole-request engines on
PostgreSQL and MySQL, and launches the ZIP against both dialects. Quarkus, compiled-schema, and
historical SQL checks remain migration-oracle tasks; they cannot approve a database-serving release.
The gate is on-demand and local, not hosted CI.

The built-in credential checks are intentionally high-confidence and bounded. Before a public
release, also run an independent history-aware scanner such as gitleaks and review GitHub's secret
and dependency alerts as described in [../RELEASING.md](../RELEASING.md).

## Task matrix

| Task | Purpose | Docker |
| --- | --- | --- |
| `./gradlew test` | Parser, validation, planning, generation, runtime, policy, artifact, and documentation tests | No |
| `./gradlew titanGraphqlGenerateRoutines` | Deterministically generate model-bound Titan carrier source | No |
| `./gradlew titanTranspile titanPackage` | Lower and package PostgreSQL/MySQL migrations and metadata | No |
| `./gradlew titanVerifyInstall` | Install and verify both dialect packages in scratch databases | Yes |
| `./gradlew titanGraphqlBindPackage` | Verify and bind the reviewed model to the generated package | Yes |
| `./gradlew titanGraphqlVerifyDatabaseEngineBoundary` | Reject non-transpilable/framework dependencies from the database engine source set | No |
| `./gradlew titanGraphqlVerifyDatabaseFrontendBoundary titanGraphqlVerifyDatabaseHttpFrontendBoundary` | Prove the package-bound client and standalone HTTP distribution contain no JVM GraphQL runtime | No |
| `./gradlew titanGraphqlVerifyDatabaseHttpFrontendReleaseArtifact` | Verify the deployable ZIP's launcher, exact reviewed runtime closure, and absence of local GraphQL classes | No |
| `./gradlew titanGraphqlDatabaseEngineReleaseCheck` | Full deployable database-engine gate; this is the `release-check.sh --full` execution suite | Yes |
| `./gradlew databaseEngineIntegrationTest databaseEngineMySqlIntegrationTest` | Install and directly invoke the demo whole-request package on PostgreSQL/MySQL | Yes |
| `./gradlew databaseEngineCommerceIntegrationTest databaseEngineCommerceMySqlIntegrationTest` | Install and directly invoke an unrelated commerce whole-request package on PostgreSQL/MySQL | Yes |
| `./gradlew databaseEngineHttpIntegrationTest` | Exercise the temporary package-bound Quarkus migration seam | Yes |
| `./gradlew databaseHttpFrontendIntegrationTest` | Exercise the isolated standalone HTTP distribution | Yes |
| `./gradlew compiledSchemaIntegrationTest` | Prove unrelated demo and commerce packages through the generic compiled runtime on both dialects | Yes |
| `./gradlew legacySqlIntegrationTest` | Preserve the isolated Java-versus-whole-request SQL equivalence proof | Yes |

Use `./gradlew tasks --group verification` to inspect the current verification entry points. Build
artifacts live under `build/`, are ignored by Git, and may be removed after retaining release
evidence.

## What constitutes a pass

A release candidate passes only when commands exit successfully with no skipped required dialect,
installation, attestation, or standalone-distribution leg. A generated artifact is not deployment
evidence until its install verification passes. An endpoint response from `java`, `jdbc`, `compiled`,
or transitional Quarkus `database` mode is not evidence that the shipping ZIP works.

When a gate fails, keep the first actionable failure and its generated diagnostics, fix the source or
model, rebuild all dependent artifacts, and rerun the entire affected gate from a clean worktree.
Never edit a generated report merely to change its status.
