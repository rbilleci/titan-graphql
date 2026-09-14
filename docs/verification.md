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
and the Docker-free unit suite. The full gate additionally runs the independently packaged demo and
commerce schemas on PostgreSQL and MySQL, followed by the isolated legacy SQL equivalence proof.

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
| `./gradlew compiledSchemaIntegrationTest` | Prove unrelated demo and commerce packages through the generic compiled runtime on both dialects | Yes |
| `./gradlew legacySqlIntegrationTest` | Preserve the isolated Java-versus-whole-request SQL equivalence proof | Yes |

Use `./gradlew tasks --group verification` to inspect the current verification entry points. Build
artifacts live under `build/`, are ignored by Git, and may be removed after retaining release
evidence.

## What constitutes a pass

A release candidate passes only when commands exit successfully with no skipped required dialect,
installation, attestation, or strict-equivalence leg. A generated artifact is not deployment evidence
until its install verification passes. An endpoint response from `java` or `jdbc` mode is not evidence
that the compiled package works.

When a gate fails, keep the first actionable failure and its generated diagnostics, fix the source or
model, rebuild all dependent artifacts, and rerun the entire affected gate from a clean worktree.
Never edit a generated report merely to change its status.
