# Releasing Titan GraphQL

This project uses a maintainer-run validation process rather than GitHub Actions.

1. Start from a clean checkout with initialized submodules.
2. Review `git status`, repository visibility, and GitHub's secret/dependency alerts.
3. Run `scripts/release-check.sh --full` with JDK 21 and Docker. This performs the local static,
   unit, two-schema/two-dialect database-engine, and standalone-HTTP-ZIP checks without creating
   or enabling a GitHub Actions workflow. The transitional compiled and legacy-equivalence suites
   remain useful migration oracles but cannot approve the shipping artifact. Retain the reported
   deployment fingerprints with the release evidence.
4. Run an independent credential scanner such as gitleaks across all reachable Git history and
   review its findings; the script's high-confidence scanner is a baseline, not a substitute.
5. Confirm that no release notes, examples, or artifacts contain real credentials, customer
   data, internal hostnames, or unapproved personal information.
6. Update `CHANGELOG.md`, tag the commit, and create the GitHub release with the command
   results and supported environment stated explicitly.

The script intentionally requires a clean worktree and the `rbilleci/titan-graphql` origin. Run
`scripts/release-check.sh` without `--full` for the Docker-free static and unit gate while iterating.

Do not publish a deployment configuration with
`titan.graphql.http.trust-request-context-headers=true` unless an authenticated gateway owns
those headers. Do not expose `/admin/graphql` without a secret-managed admin token and a
documented operational owner.
