# M7 release evidence

Status: `scripts/release-check.sh --full` passes on `072b6ce`, including fresh Commerce
HTTP/restart/replacement/snapshot execution against the corrected runtime inputs from `64b569f`.
`release-evidence/m7-final-verification.json` records that completed event.
Release publication and final remote synchronization remain open under
`database-engine-execution-plan.md`, Phase 7 — final verification and release.
`RELEASING.md` defines the on-demand publication procedure.

The release preserves the database serving boundary established by M1–M6. The first deployment
target uses a container for the standalone frontend and a separate control worker. The release
does not require caller migration because the project has no deployed service or existing callers.

The acceptance inventory maps each release requirement to the command or assertion that proves it.

| Requirement | Authoritative evidence | Verification status |
| --- | --- | --- |
| M7-SCHEMA-DIALECTS | Demo and Commerce generation, package, installation, and binding tasks in `titanGraphqlDatabaseEngineReleaseCheck` | Both schemas pass direct suites on both dialects |
| M7-REQUEST-SEMANTICS | Installed Commerce suites and standalone fixed expected-result corpus | Direct and ZIP corpus suites pass on both dialects |
| M7-PREVALIDATION | Installed input, policy, and serial-mutation assertions | Direct and ZIP corpus suites pass on both dialects |
| M7-DURABILITY-IDENTITY | Replacement, snapshot, cancellation, HTTP restart, and package attestation tests | Full gate passes on both dialects |
| M7-GENERATED-DOCUMENTS | `GeneratedDatabaseEngineCorpus` and `commerce-generated-v1.json` | Direct and ZIP corpus suites pass on both dialects |
| M7-REPRESENTATIVE-MEASUREMENTS | Gradle profile, artifact inventory, and `DatabaseEngineMeasurements` output | Historical and final events retained in the release-evidence directory |
| M7-CLEAN-CHECKOUT | `scripts/release-check.sh --full` with pinned submodules and no initial build output | Final rerun passes on `072b6ce` |
| M7-PRIVACY-HISTORY | Gitleaks history and package scans, plus the release script's hygiene checks | Independent scans pass |
| M7-NOTICES-DEPENDENCIES | Runtime ZIP notice checks, locked dependencies, and `release-evidence/m7-runtime-advisories.json` | ZIP notice and exact recursive source checks pass |
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

`release-evidence/derive.py` derives the retained summary from JUnit XML, Gradle profile HTML,
package manifests, object inventories, and package bytes. Run it with the checkout's `build`
directory and verified source commit. The summary excludes XML hostnames, absolute paths, and
unrelated standard output. It retains original artifact hashes for comparison with local results.
The direct-suite installation timers include fixture schema creation, package SQL installation,
and fixture seeding; they exclude container startup. Gradle scratch-install task durations also
include the scratch database lifecycle and verification. These measure different populations.

The counters describe application work, not every database statement. The generator's
`emitApplicationStatementReservation` excludes identity checks. Its Relay root reserves a possible
opposite-page carrier before reading the page, so `decodedApplicationRows` conservatively charges
a row even when that probe does not run. The Commerce tests also assert the returned parent and
child populations. The counter definitions live in
`src/main/java/io/titan/graphql/codegen/TitanGraphqlDatabaseEngineSourceGenerator.java`; the installed
`installedPackageMeasuresGrowingParentAndChildCardinality` assertions check the measured populations.

## Final verification event

The completed full gate on `072b6ceafdd8052aed37de97af5d60802d025947` retains its current
suite summaries, measurements, package identities, and artifact hashes in
`release-evidence/m7-final-verification.json` and `release-evidence/m7-final-artifact-inventory.tsv`.
The JSON records the completion observation timestamp and distinguishes the final acceptance
profile from the preceding completed generation/direct-suite profile on
`64b569fcfd1a0e360262dbc3fe49e0f3abe474e7`. The runtime build inputs remain those of `64b569f`;
`072b6ce` changes the release script and evidence profile selection, not the runtime.

The final aggregate profile records executed Commerce HTTP corpus, HTTP restart, replacement,
and snapshot tasks. Their JUnit timestamps confirm fresh execution after the final run began.
The direct suites, demo HTTP suite, and container deployment reports come from the completed
`64b569f` run; their package/distribution inputs did not change before final acceptance.
Ordinary unit and management-store fixtures also remain unchanged. Every retained suite reports
no skipped, failed, or errored cases. The exact source inventory/bytes/permissions verifier
executes in the final aggregate and passes; an independent Python ZIP comparison also passes.

Derivation: `python3 -B docs/release-evidence/derive.py build
072b6ceafdd8052aed37de97af5d60802d025947 --profile profile-2026-10-01-17-21-28.html`.
The retained summary also identifies `profile-2026-10-01-16-23-50.html` as the preceding
completed generation event, rather than treating cached final generation tasks as fresh timings.
`release-evidence/m7-final-security-review.json` records the independent history, generated-proof,
and distribution scan event and the GitHub workflow inventory. Publication requires another
source/history review after these evidence files enter the source asset.

## Earlier completed verification event

The 2026-10-01 run of `scripts/release-check.sh --full` exited successfully on code commit
`687203ec31ebb7527b6665dea93241d66405c07e`. It included the ordinary test gate, database-engine
release aggregate, and container deployment gate. `release-evidence/m7-verification.json` retains
computed suite summaries, case names, measurements, package identities, and source-artifact hashes.
The pinned dependency revisions were Titan `f503c078ff008e03ef2791d254faa3ead98c643a` and
Titan DSL `906f013f34a1a505dac234a7e4976f4bf071e0ab`.

The checkout began without build output at `e206d2e`, initialized the published submodules,
and fetched the real origin. It then fast-forwarded the measured request syntax correction
`137c9f4` and source-ZIP launcher permission correction `687203e`. No ignored output from the
maintainer checkout entered that clone. Gradle recompiled or regenerated changed inputs and
reused unchanged outputs; the retained profile distinguishes executed and up-to-date tasks.
The earlier interrupted runs supplied diagnostics, not acceptance evidence.

The suite summaries prove that the completed dialect tests did not skip or fail cases.
Derivation: `python3 docs/release-evidence/derive.py build 687203ec31ebb7527b6665dea93241d66405c07e`;
retained result: `release-evidence/m7-verification.json`.

| Task | Tests | Skipped | Failures | Errors |
| --- | ---: | ---: | ---: | ---: |
| `databaseEngineCommerceHttpCorpusIntegrationTest` | 2 | 0 | 0 | 0 |
| `databaseEngineCommerceHttpRestartIntegrationTest` | 2 | 0 | 0 | 0 |
| `databaseEngineCommerceIntegrationTest` | 42 | 0 | 0 | 0 |
| `databaseEngineCommerceMySqlIntegrationTest` | 45 | 0 | 0 | 0 |
| `databaseEngineContainerDeploymentIntegrationTest` | 2 | 0 | 0 | 0 |
| `databaseEngineIntegrationTest` | 1 | 0 | 0 | 0 |
| `databaseEngineMySqlIntegrationTest` | 1 | 0 | 0 | 0 |
| `databaseEnginePackageReplacementIntegrationTest` | 2 | 0 | 0 | 0 |
| `databaseEngineSnapshotIntegrationTest` | 2 | 0 | 0 | 0 |
| `databaseHttpFrontendIntegrationTest` | 9 | 0 | 0 | 0 |
| `databaseManagementStoreIntegrationTest` | 28 | 0 | 0 | 0 |
| `test` | 312 | 0 | 0 | 0 |

The fixture request measurements below describe the completed 2026-10-01 run. Each row covers
the sorted measured requests after warmup; `DatabaseEngineMeasurements.java` defines the sample
population and nearest-rank calculation. Fixture cardinalities and the budget counters have exact
installed assertions. These timings include the client database call and JSON parsing on this
shared local Docker host, not deployment startup or a production workload.

| Model | Dialect | Parents | Children per parent | p50 (ms) | p95 (ms) | Application SQL statements | Budget rows |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| commerce | postgresql | 1 | 1 | 313.803 | 326.449 | 2 | 3 |
| commerce | postgresql | 2 | 1 | 323.632 | 349.916 | 2 | 5 |
| commerce | postgresql | 64 | 1 | 1613.393 | 1753.474 | 2 | 129 |
| commerce | postgresql | 65 | 1 | 1590.095 | 1747.212 | 3 | 131 |
| commerce | postgresql | 65 | 2 | 2744.556 | 2871.075 | 3 | 196 |
| commerce | postgresql | 65 | 4 | 6697.112 | 7418.731 | 3 | 326 |
| commerce | mysql | 1 | 1 | 1649.119 | 1744.632 | 2 | 3 |
| commerce | mysql | 2 | 1 | 1644.407 | 1724.322 | 2 | 5 |
| commerce | mysql | 64 | 1 | 4145.988 | 4561.588 | 2 | 129 |
| commerce | mysql | 65 | 1 | 4414.536 | 4694.896 | 3 | 131 |
| commerce | mysql | 65 | 2 | 5382.463 | 5852.311 | 3 | 196 |
| commerce | mysql | 65 | 4 | 6840.691 | 7131.143 | 3 | 326 |
| blog | postgresql | 1 | 0 | 40.585 | 46.638 | 1 | 1 |
| blog | mysql | 1 | 0 | 281.627 | 306.294 | 1 | 1 |

The package totals include metadata and all packaged files; SQL totals include runtime, engine,
and rollback SQL. Routine objects count functions and procedures in the object inventory, not
only generated entry points. The same retained JSON derives the completed-run totals below;
`release-evidence/m7-artifact-inventory.tsv` retains the per-file hashes for the application proofs.

| Package | Dialect | SQL bytes | Package bytes | Routine objects |
| --- | --- | ---: | ---: | ---: |
| demo-blog | postgresql | 3044893 | 4532123 | 473 |
| commerce | postgresql | 29690188 | 31347394 | 506 |
| commerce | mysql | 28307431 | 29957822 | 522 |
| titan-graphql-management-database | mysql | 2153182 | 3455506 | 403 |
| titan-graphql-management-database | postgresql | 1700769 | 2997472 | 387 |
| demo-blog | mysql | 3544066 | 5024438 | 487 |

The final profile's executed task durations measure the actual 2026-10-01 build. They are not a
claim that every generation action began with an empty output directory: unchanged SQL files
can remain after the compiler checks their content. Scratch installation includes verification.
The earlier interrupted cold profile remains separately identified in the retained JSON.

| Model | Dialect | Generation (s) | Transpilation (s) | Scratch installation (s) |
| --- | --- | ---: | ---: | ---: |
| blog | postgresql | 0.700 | 4.202 | 12.073 |
| blog | mysql | 0.715 | 3.263 | 21.151 |
| commerce | postgresql | 1.203 | 104.950 | 47.176 |
| commerce | mysql | 1.238 | 101.750 | 79.890 |

Request work stays batch-bounded as populations grow; the fixed response assertions still pass.
The larger response takes seconds on this fixture. The run does not establish a production latency
target or a before/after speedup, and this release does not trade validation or identity checks for
latency. Performance tuning beyond a demonstrated release regression remains outside M7.

## Public files, history, and dependencies

`release-evidence/m7-source-archive-diagnostic.json` records the final inspection of the first
candidate source archive. Gradle's directory-tree defaults excluded tracked `.gitignore` and
`.gitmodules` files despite their explicit include list. Explicit individual-file copy sources
still applied those defaults. Gradle documents these
[default file-tree exclusions](https://docs.gradle.org/current/userguide/working_with_files.html).
A settings-time exception passed the isolated archive check, but the full composite build rejected
conflicting default-exclusion snapshots before the database tests. The final archive task writes
sorted tracked files directly, normalizes timestamps, and preserves executable permissions without
changing global scanner defaults. The focused archive and database-package staging checks pass.
`titanGraphqlVerifyReleaseSourceDistribution` now requires the exact
recursive tracked-file set and compares each entry's bytes with its source. The aggregate release
gate depends on this verifier; the incomplete candidate is not a publication asset.

The full run on `64b569f` passed the corrected source verifier, direct database suites, standalone
demo HTTP tests, and container deployment. It reused Commerce HTTP/restart/replacement/snapshot
results because those tasks' ordinary cache keys omitted package bytes. The release script now
selects those tasks with Gradle's task-specific `--rerun` option. The completed `072b6ce` run
executes them against the current package identities; its retained profile and fresh XML supply
the final evidence instead of the earlier cached results.

Gitleaks `v8.30.1`, downloaded from its official release and verified against its published checksum,
scanned all reachable parent and pinned-submodule history. The parent scan found a generic API-key
match in historical documentation at
`bbf856df24f7604afee07f4c41787ae170c0046a:docs/ui/roadmap.md:49`. Inspection of the original Git blob
proved that the match describes API documentation and relationship diagrams. `.gitleaksignore`
records only that exact finding. The reviewed parent scan and both unmodified submodule scans
report no credentials. The generated-proof directory scan also reports no credentials.

The pinned dependencies retain historical author addresses in their already-public Git metadata.
The release does not copy those addresses into its evidence or rewrite published dependency
history. The parent repository's author-hygiene gate requires GitHub noreply addresses; the source
distribution contains tracked source files rather than Git metadata.

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
