# Releasing Titan GraphQL

This project uses a maintainer-run validation process rather than GitHub Actions.

1. Start from a clean checkout with initialized submodules.
2. Review `git status`, repository visibility, and GitHub's secret/dependency alerts.
3. Run `./gradlew test`, `./gradlew titanGraphqlBindPackage`, and
   `./gradlew integrationTest` with JDK 21 and Docker. Retain the reported deployment
   fingerprint with the release evidence.
4. Run a credential scanner across all reachable Git history and review its findings.
5. Confirm that no release notes, examples, or artifacts contain real credentials, customer
   data, internal hostnames, or unapproved personal information.
6. Update `CHANGELOG.md`, tag the commit, and create the GitHub release with the command
   results and supported environment stated explicitly.

Do not publish a deployment configuration with
`titan.graphql.http.trust-request-context-headers=true` unless an authenticated gateway owns
those headers. Do not expose `/admin/graphql` without a secret-managed admin token and a
documented operational owner.
