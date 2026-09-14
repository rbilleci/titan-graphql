# Contributing

Thank you for improving Titan GraphQL. Please discuss substantial changes in an issue before
opening a pull request.

## Local setup

Clone with submodules, then use the checked-in wrapper:

```bash
git clone --recurse-submodules https://github.com/rbilleci/titan-graphql.git
cd titan-graphql
./gradlew test
```

Run the Docker-backed proof before proposing runtime, SQL, or dependency changes:

```bash
./gradlew compiledSchemaIntegrationTest
```

Before a release-oriented change, run `scripts/release-check.sh`; maintainers run
`scripts/release-check.sh --full` for the complete local gate.

## Change expectations

- Keep `main` buildable with JDK 21.
- Add focused tests and update user-facing documentation with behavior changes.
- Do not commit credentials, `.env` files, database exports, generated build output, or
  personal data that is not needed for the project.
- Preserve the GPLv3 license and include appropriate notices for copied material.

This project intentionally does not use GitHub Actions. Maintainers run the commands above
locally and attach their results to pull requests or release notes.
