# Releasing Titan GraphQL

This project uses a maintainer-run validation process rather than GitHub Actions.

1. Start from a clean checkout with initialized submodules.
2. Review `git status`, repository visibility, and GitHub's secret/dependency alerts.
3. Run `scripts/release-check.sh --full` with JDK 21 and Docker. This performs the local static,
   unit, two-schema/two-dialect database-engine, standalone-HTTP-ZIP, and container deployment checks.
   Retain the Gradle profile, JUnit XML, artifact inventory, and reported
   deployment fingerprints with the release evidence.
4. Run an independent credential scanner such as gitleaks across all reachable Git history and
   review its findings; the script's high-confidence scanner is a baseline, not a substitute.
5. Confirm that no release notes, examples, or artifacts contain real credentials, customer
   data, internal hostnames, or unapproved personal information.
6. Update `CHANGELOG.md`, tag the commit, and create the GitHub release with the command
   results and supported environment stated explicitly.

Use `gitleaks git --log-opts=--all --redact` in this repository and in each pinned submodule.
Scan generated packages with `gitleaks dir --redact build/generated/proofs`. The tracked
`.gitleaksignore` suppresses only the finding at
`bbf856df24f7604afee07f4c41787ae170c0046a:docs/ui/roadmap.md:49`: the scanner interpreted
the sentence describing generated API documentation and relationship diagrams as an API key. Review new findings;
do not extend that exception to a rule or file.

Review `THIRD_PARTY_NOTICES.md` and inspect license entries in both ZIPs before attaching binaries.
Publish the matching recursive source checkout, including pinned submodule sources, and the
corresponding MySQL Connector/J source with bundled-driver distributions. Record the source
revisions and artifact hashes in the release evidence.
The aggregate runs `titanGraphqlVerifyReleaseSourceDistribution`, which compares the source ZIP's
file inventory, entry bytes, and required executable permissions with the recursive tracked
checkout, including tracked Git metadata.

The script intentionally requires a clean worktree and the `rbilleci/titan-graphql` origin. Run
`scripts/release-check.sh` without `--full` for the static and ordinary unit gate while iterating.
A fresh checkout uses Docker-backed catalog generation before compilation.

Do not publish a deployment configuration with
`TITAN_GRAPHQL_HTTP_TRUST_REQUEST_CONTEXT_HEADERS=true` unless an authenticated gateway owns
those headers. Do not expose `/admin/graphql` without a secret-managed admin token and a
documented operational owner.
