# M7 release evidence

Status: verification in progress. Publication requires the clean-checkout full gate and the
remote synchronization checks in `database-engine-execution-plan.md`, Phase 7 — final verification
and release. `RELEASING.md` defines the on-demand publication procedure.

The release preserves the database serving boundary established by M1–M6. The first deployment
target uses a container for the standalone frontend and a separate control worker. The release
does not require caller migration because the project has no deployed service or existing callers.

The acceptance inventory maps each release requirement to the command or assertion that proves it.

| Requirement | Authoritative evidence | Verification status |
| --- | --- | --- |
| M7-SCHEMA-DIALECTS | Demo and Commerce generation, package, installation, and binding tasks in `titanGraphqlDatabaseEngineReleaseCheck` | Clean-checkout run pending |
| M7-REQUEST-SEMANTICS | Installed Commerce suites and standalone fixed expected-result corpus | Clean-checkout run pending |
| M7-PREVALIDATION | Installed input, policy, and serial-mutation assertions | Clean-checkout run pending |
| M7-DURABILITY-IDENTITY | Replacement, snapshot, cancellation, HTTP restart, and package attestation tests | Clean-checkout run pending |
| M7-GENERATED-DOCUMENTS | `GeneratedDatabaseEngineCorpus` and `commerce-generated-v1.json` | Focused run pending |
| M7-REPRESENTATIVE-MEASUREMENTS | Gradle profile, artifact inventory, and `DatabaseEngineMeasurements` output | Clean-checkout run pending |
| M7-CLEAN-CHECKOUT | `scripts/release-check.sh --full` with pinned submodules and no initial build output | Pending |
| M7-PRIVACY-HISTORY | Gitleaks history and package scans, plus the release script's hygiene checks | Independent scans pass |
| M7-NOTICES-DEPENDENCIES | Runtime ZIP notice checks, locked dependencies, and `release-evidence/m7-runtime-advisories.json` | Final ZIP checks pending |
| M7-LOCAL-VERIFICATION | Tracked workflow inventory and GitHub workflow API | No hosted workflow |
| M7-PUBLICATION | Fresh remote review, push, release tag/assets, and synchronized checkout | Pending |

## Request generation and measurements

`GeneratedDatabaseEngineCorpus` combines literal and variable arguments, direct/inline/named/merged
selections, conditional directives, and ignored commas/comments. Each generated document contains
an explicitly selected operation and another valid operation. The independent JSON resource fixes
the expected customer/country result. Both installed dialects and the extracted HTTP ZIP execute
the same generated documents through the corpus adapter.

The blog fixture supplies the smaller schema and Commerce supplies the larger schema. The full
Gradle profile records generation, transpilation, and scratch installation tasks. The artifact
inventory records SQL/package bytes and hashes; each package's object inventory records its routines.
Installed request measurements report nearest-rank p50/p95 after warmup. The Commerce measurement
asserts the complete response, statement ledger, and decoded-row ledger as parent and child
populations grow across the fixed relation-batch boundary. These local fixture observations do
not define a production latency service-level objective.

## Public files, history, and dependencies

Gitleaks `v8.30.1`, downloaded from its official release and verified against its published checksum,
scanned all reachable parent and pinned-submodule history. The parent scan found a generic API-key
match in historical documentation at
`bbf856df24f7604afee07f4c41787ae170c0046a:docs/ui/roadmap.md:49`. Inspection of the original Git blob
proved that the match describes API documentation and relationship diagrams. `.gitleaksignore`
records only that exact finding. The reviewed parent scan and both unmodified submodule scans
report no credentials. The generated-proof directory scan also reports no credentials.

The public repository uses `main`, and the GitHub workflow API reports no workflows. GitHub secret
scanning and Dependabot alerts are disabled; their APIs provide no alert inventory. The local
history scan and runtime advisory query supply independent evidence without changing those settings.

The runtime dependency review found patched-version requirements in the published Jackson,
PostgreSQL JDBC, and Protocol Buffers advisories. The build updates its direct pins and regenerated
locks/checksums. `release-evidence/m7-runtime-advisories.json` retains the exact query, timestamp,
and response for the bundled external Maven artifacts. Its result applies to that query's package
versions and timestamp rather than future dependency releases.

`THIRD_PARTY_NOTICES.md` records the project/submodule license terms and dependency notice locations.
The ZIPs include the project license, notices, and additional Apache/Protocol Buffers license texts;
dependency JARs retain their embedded notices. `releaseSourceDistribution` packages the tracked
project and pinned submodule sources. A bundled-driver release also supplies the MySQL Connector/J
source for the upstream commit recorded in the release asset manifest.
