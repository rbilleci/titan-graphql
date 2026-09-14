# Titan GraphQL Core Handoff Roadmap

## Purpose

Apply the completed Titan core gap work inside `titan-graphql` without fusing
the two repositories or moving product-specific GraphQL concerns into Titan.

Titan is the reusable library and compiler/runtime contract provider.
`titan-graphql` is a consumer that adapts GraphQL product, admin, artifact, and
preview behavior onto those library contracts. The goal of this roadmap is to
replace GraphQL-owned placeholders and in-memory management boundaries with
adapters that consume Titan-owned contracts from GAP-005 and GAP-006.

This is not a new Titan core gap search. The current evidence says GAP-001
through GAP-006 are closed in Titan core and there is no active
`TITAN-GQL-GAP-007`. If implementation exposes a genuinely reusable missing
Titan contract, record it as a new finding with evidence before changing
Titan. Do not use `titan-graphql` convenience as a reason to push
GraphQL-specific concepts into Titan.

## Source State

Primary `titan-graphql` sources:

- `docs/titan-core-gap-ledger.md`
- `docs/titan-core-handoff-validation.md`
- `docs/developer-experience-roadmap.md`
- `docs/mutation-runtime-lowering-boundary.md`
- `src/main/java/io/titan/graphql/GraphqlAdminHttpResource.java`
- `src/main/java/io/titan/graphql/management/`
- `src/main/java/io/titan/graphql/artifact/`

Primary Titan library sources:

- `vendor/titan/GAP-005.md`
- `vendor/titan/GAP-006.md`
- `vendor/titan/titan-gradle-plugin`
- `vendor/titan/titan-transpiler`

Current handoff facts:

- Titan core GAP-001 through GAP-006 are closed.
- `titan-graphql/docs/titan-core-gap-ledger.md` says there is no current
  `TITAN-GQL-GAP-007`.
- `/admin/graphql` already exists as a separate management endpoint.
- `titan-graphql` still owns Java-mode management records, in-memory
  management store behavior, artifact placeholder records, and management
  mutation execution that must be adapted to Titan-owned contracts before it is
  production-safe.
- One external `titan-graphql` validation run was blocked by a local
  Gradle 9.5.1 / IntelliJ plugin incompatibility. This is the first handoff
  blocker to make explicit and either fix or isolate.

## Architectural Separation Rules

1. Titan remains product-neutral.
   - No GraphQL SDL, GraphQL field names, HTTP headers, admin routes, preview
     URLs, UI state, portal concepts, or consumer workflow names belong in
     Titan core.
2. `titan-graphql` consumes Titan through adapters.
   - GraphQL request, actor, mutation, artifact, preview, and deployment
     language stays in `titan-graphql`.
   - Titan-owned records, manifests, install plans, verification reports,
     management commands, idempotency, audit, transactions, and durable reads
     are consumed through explicit adapter boundaries.
3. Titan changes require reusable evidence.
   - A Titan change is allowed only when the missing behavior is generic and
     useful beyond GraphQL.
   - The roadmap ledger must name the reusable contract, the GraphQL evidence
     that exposed it, the minimal Titan validation case, and why a
     `titan-graphql` adapter is insufficient.
4. No hidden production state in process memory.
   - In-memory stores remain test/reference utilities only.
   - Production management behavior must go through a durable/external store
     adapter backed by Titan GAP-006 semantics.
5. No artifact truth duplication.
   - `titan-graphql` may present GraphQL-specific artifact views, but
     generated SQL package identity, object inventory, install plans, hashes,
     and verification state must come from Titan GAP-005 artifacts.

## Independent Validation And Review Contract

Every milestone must include a separate validation and audit phase before it is
committed, pushed, or marked complete.

Required validation ladder:

1. Focused `titan-graphql` tests for changed adapters, endpoint behavior,
   artifact views, or management mutations.
2. Cross-repo compatibility validation against the sibling `vendor/titan` library
   when consuming GAP-005/GAP-006 APIs or generated artifacts.
3. `titanTranspile` or package-generation checks when a slice touches lowered
   artifacts, generated SQL, entrypoint discovery, or install metadata.
4. `git diff --check` and `git diff --cached --check`.
5. Documentation updates to conformance/profile docs whenever behavior moves
   from `JAVA_ONLY` or `PENDING` toward production support.

Required independent review:

- Run a separate handoff audit after tests pass and before commit.
- Use a code-review stance, not a summary stance.
- The audit must answer:
  - Does this keep Titan product-neutral and GraphQL-specific behavior in
    `titan-graphql`?
  - Does this consume Titan GAP-005/GAP-006 contracts instead of duplicating
    them?
  - Are in-memory/test paths kept distinct from durable/production paths?
  - Are idempotency, audit, transaction, and artifact verification claims backed
    by durable evidence?
  - Are conformance rows and non-claims updated truthfully?
  - Did the validation gate cover the changed behavior?

Audit verdicts:

- `pass`: commit/push and advance are allowed.
- `fix-needed`: fix within the same milestone before advancing.
- `blocked`: record the blocker and do not advance.

## Milestones

### TG-HANDOFF-M0.1 Baseline And Validation Gate

Goal: make the handoff measurable before changing product behavior.

Expected work:

- Add or update docs/tests that capture the current boundaries:
  GraphQL-owned generated artifact placeholders, in-memory management store,
  Java-mode mutation executor, `/admin/graphql` context headers, and current
  `JAVA_ONLY`/`PENDING` mutation conformance rows.
- Reproduce the local validation/tooling blocker or isolate it with a narrower
  supported validation command.
- Document the chosen baseline validation ladder for future milestones.
- Confirm both repos are clean and synced before the next implementation slice.

Validation:

- Focused docs/test validation as appropriate.
- At least one successful `titan-graphql` test or a precise documented blocker
  with a minimal failing command.
- `git diff --check`.
- `git diff --cached --check`.
- HANDOFF REVIEW AUDIT.

Done when:

- The roadmap has an executable validation baseline.
- The Gradle/IntelliJ blocker is fixed or narrowed to a concrete known
  incompatibility with a safe alternate gate.
- The next slice can start from a clean repo and an explicit test command.

### TG-HANDOFF-M1.1 GAP-005 Artifact Metadata Adapter

Goal: make `titan-graphql` consume Titan-generated artifact metadata instead
of GraphQL-owned generated SQL placeholders.

Status: complete as an adapter slice. `TitanGraphqlGap005ArtifactMetadata`
parses and validates Titan's four GAP-005 JSON outputs without importing Titan
Gradle internals, and the generated artifact workflow can use those files as
SQL/package truth while keeping GraphQL SDL, introspection, and conformance
artifacts product-owned.

Expected work:

- Introduce a `titan-graphql` adapter that reads or references Titan GAP-005
  artifact outputs:
  - `titan-artifact.json`
  - `titan-object-inventory.json`
  - `titan-install-plan.json`
  - `titan-install-verification.json`
- Preserve GraphQL-specific presentation records while ensuring SQL package
  identity, hashes, object inventory, install order, and verification state
  come from Titan artifact truth.
- Update generated artifact workflow tests to distinguish:
  - GraphQL SDL/introspection/conformance artifacts owned by `titan-graphql`
  - SQL/package/install/verification artifacts owned by Titan
- Keep artifact paths relative and reproducible. Do not import Titan Gradle
  internals into GraphQL product records.

Validation:

- Focused artifact adapter tests.
- Titan package-generation or fixture validation when practical.
- Conformance/profile doc update if generated SQL rows change status.
- `git diff --check`.
- `git diff --cached --check`.
- HANDOFF REVIEW AUDIT.

Done when:

- GraphQL management artifact references point at Titan GAP-005 artifact
  metadata for SQL package truth.
- GraphQL no longer has to invent a parallel generated-SQL manifest contract.

### TG-HANDOFF-M2.1 GAP-006 Command Context Adapter

Goal: map `/admin/graphql` management mutation context to Titan GAP-006 command
context without moving GraphQL request semantics into Titan.

Status: complete as a command-context adapter slice. `titan-graphql` now maps
the GraphQL-owned `importModelDocument` mutation and admin HTTP headers into
Titan GAP-006-neutral `ManagementCommands.CommandInvocation` records through
`TitanGraphqlGap006CommandContext`, and validates the Titan descriptor before
the current Java-mode handler writes to the in-memory test store. Durable
store, idempotency replay, audit persistence, transaction execution, and
deployment promotion remain later slices.

Expected work:

- Map GraphQL admin request context to Titan management actor/request context:
  actor role, authenticated actor evidence, request id, idempotency key,
  workspace scope, and operation metadata.
- Add or update `/admin/graphql` headers only as GraphQL transport concerns.
  Titan sees neutral actor/request/idempotency records, not HTTP names.
- Adapt existing management mutations to Titan command descriptors where
  available, starting with `importModelDocument`.
- Preserve GraphQL-shaped success/error output at the endpoint.

Validation:

- Focused admin endpoint/context tests.
- Command descriptor invocation tests for success, missing actor/request,
  unauthorized actor, missing idempotency, and deterministic errors.
- Adjacent mutation executor tests.
- `git diff --check`.
- `git diff --cached --check`.
- HANDOFF REVIEW AUDIT.

Done when:

- `/admin/graphql` can construct Titan GAP-006-neutral command context for at
  least one management mutation.
- HTTP/GraphQL field names remain outside Titan core identity.

### TG-HANDOFF-M3.1 Durable Management Store Adapter

Goal: replace production management state with a durable/external adapter while
keeping the in-memory store as a test utility.

Status: complete as a durable adapter slice. `TitanGraphqlManagementStore` is
now the explicit management-store boundary; `TitanGraphqlInMemoryManagementStore`
remains the Java-mode test/reference implementation, and
`TitanGraphqlDurableManagementStore` consumes Titan GAP-006
`TransactionalMutationStore` records for durable drafts, GAP-005 artifact refs,
deployments, idempotency, audit, transaction framing, and deterministic core
reads. GraphQL-only wrapper state such as model display fields, validation
report summaries, previews, observed operation documents, registry
presentation, and usage reports remains in `titan-graphql` and is not claimed
as Titan durable evidence.

Expected work:

- Introduce a management store interface boundary if the current one is not
  explicit enough.
- Implement an adapter backed by Titan GAP-006 durable records, idempotency,
  audit, transaction, and read contracts.
- Keep `TitanGraphqlInMemoryManagementStore` for tests/reference behavior.
- Ensure draft, validation report ref, artifact ref, deployment, observed
  operation, operation registry, and usage report reads are deterministic.
- Preserve GraphQL product fields only in `titan-graphql` wrapper records.

Validation:

- Store conformance tests that run the same behavior against in-memory and
  durable adapters where practical.
- Forced-failure tests for rollback/idempotency/audit evidence.
- Deterministic read ordering tests.
- `git diff --check`.
- `git diff --cached --check`.
- HANDOFF REVIEW AUDIT.

Done when:

- Production-bound core management rows do not depend on process-local state
  when the durable adapter is selected.
- Durable draft, artifact-ref, deployment, idempotency, audit, transaction, and
  read claims are backed by tests.
- GraphQL-only wrapper records remain product-owned and unpromoted until the
  later mutation migration and deployment-consistency slices decide their
  consumer-side persistence path.

### TG-HANDOFF-M4.1 Durable Import Mutation Migration

Goal: migrate the existing `/admin/graphql` `importModelDocument` mutation onto
the Titan-backed command/store boundary.

Status: complete as the first mutation migration batch.
`GraphqlManagementMutationSupport` can select
`TitanGraphqlDurableManagementStore` through the GraphQL-owned
`titan.graphql.management.transactionLog` system property or
`TITAN_GRAPHQL_MANAGEMENT_TRANSACTION_LOG` environment variable. When selected,
`importModelDocument` executes through Titan GAP-006 transaction, idempotency,
and audit semantics while preserving GraphQL-shaped output. Same-key
same-input retries replay through Titan, same-key different-input retries fail
without adding a new draft, and missing idempotency is audited by Titan without
writing draft state.

Expected work:

- Migrate `importModelDocument`.
- Preserve GraphQL-shaped output while using Titan-backed
  command, idempotency, audit, transaction, and store semantics.
- Keep runtime application `/graphql` writes out of scope.
- Update `docs/mutation-runtime-lowering-boundary.md` only for rows that have
  evidence.

Validation:

- Mutation success/error tests.
- Same-key same-input idempotency replay tests.
- Same-key different-input conflict tests.
- Audit attempt/outcome tests.
- Transaction write rejection for failed Titan command validation.
- `git diff --check`.
- `git diff --cached --check`.
- HANDOFF REVIEW AUDIT.

Done when:

- `importModelDocument` is no longer merely a Java-mode/in-memory product
  proof when the durable adapter is selected.
- Mutation conformance classifications are truthful and evidence-backed.

### TG-HANDOFF-M4.2 Remaining Management Mutation Persistence

Goal: decide and implement the product-side persistence boundary for the
remaining existing management mutations without pushing GraphQL-specific
workflow semantics into Titan.

Status: complete as the product-side persistence batch.
`TitanGraphqlDurableManagementStore` now keeps Titan GAP-006 records in the
Titan transaction log and GraphQL-owned wrapper records in a sibling
`*.graphql-state.jsonl` product-state journal. `validateModelDraft`,
`generateModelArtifacts`, `approveObservedOperation`, and
`rejectObservedOperation` can therefore reload their GraphQL-specific
validation summaries, generated GraphQL artifact refs, observed operation
documents, and registry presentation state when the durable store is selected.
Those product records remain in `titan-graphql`; Titan still stores only
product-neutral drafts, artifact refs supplied with GAP-005 metadata,
deployments, audit, idempotency, and transaction evidence.

Expected work:

- Migrate these mutations in bounded batches:
  - `validateModelDraft`
  - `generateModelArtifacts`
  - `approveObservedOperation`
  - `rejectObservedOperation`
- Preserve GraphQL-shaped output while keeping validation summaries,
  generated GraphQL artifacts, observed operation documents, registry
  presentation, and review workflow fields in `titan-graphql`.
- Consume Titan GAP-005 metadata for artifact consistency when
  `generateModelArtifacts` promotes SQL/package evidence.
- Use Titan GAP-006 only for product-neutral records that already have a
  reusable contract. Record a new gap only if a genuinely reusable missing
  Titan contract is proven.
- Keep runtime application `/graphql` writes out of scope.
- Update `docs/mutation-runtime-lowering-boundary.md` only for rows that have
  evidence.

Validation:

- Mutation success/error tests.
- Product-side persistence/reload tests for the migrated mutations.
- Audit/idempotency/transaction tests where a Titan GAP-006 contract is
  consumed.
- Artifact consistency tests for `generateModelArtifacts`.
- `git diff --check`.
- `git diff --cached --check`.
- HANDOFF REVIEW AUDIT.

Done when:

- Existing admin mutations are either durable through a product-side
  persistence boundary or explicitly documented as unpromoted product-only
  behavior.
- Mutation conformance classifications are truthful and evidence-backed.

### TG-HANDOFF-M5.1 Artifact Verification And Deployment Consistency

Goal: make preview/deployment management consume Titan GAP-005 verification and
GAP-006 deployment consistency rather than trusting GraphQL-local state.

Status: complete as the artifact/deployment consistency adapter slice.
`TitanGraphqlDurableManagementStore` now exposes a verified preview-build path
that requires Titan GAP-005 artifact refs with passed install verification,
matches preview manifest path/content hash to Titan metadata, and rejects stale
GraphQL SDL hashes. Durable active deployments must now be created through
Titan GAP-006 `activateDeployment`; direct durable seeding of active deployment
rows is rejected so GraphQL-local state cannot become a second source of SQL
install truth. Broad deployment orchestration remains out of scope.

Expected work:

- Require Titan install verification state before deployment-ready claims.
- Connect preview build manifests and deployment records to Titan artifact
  hashes and verification status.
- Reject stale, mismatched, missing, or failed artifact verification states with
  GraphQL-shaped errors.
- Keep deployment orchestration out of scope unless it is already represented
  by a Titan-neutral deployment record.

Validation:

- Preview/deployment consistency tests.
- Stale artifact hash and missing verification negative tests.
- Successful verified artifact reference path.
- `git diff --check`.
- `git diff --cached --check`.
- HANDOFF REVIEW AUDIT.

Done when:

- GraphQL deployment readiness is tied to Titan artifact verification evidence.
- GraphQL does not carry a second source of truth for SQL install state.

### TG-HANDOFF-M6.1 Conformance Promotion And Handoff Closeout

Goal: close the handoff track with honest conformance, docs, and next product
direction.

Status: complete. The mutation runtime profile now promotes the bounded
`/admin/graphql` management mutation surface to `ACCEPTED` only where endpoint,
durable selected-store, GAP-005 artifact verification, GAP-006
transaction/idempotency/audit/deployment, and GraphQL-owned admin schema
evidence exists. Application `/graphql` writes, broad CRUD, nested writes,
SQL-lowered application mutation execution, product UI behavior, and broad
deployment orchestration remain explicit non-claims. The handoff is now closed
as consumer/product expansion rather than active Titan core gap debt.

Expected work:

- Update `docs/mutation-runtime-lowering-boundary.md` classifications:
  promote only rows with durable endpoint evidence.
- Update `docs/titan-core-gap-ledger.md` to record consumed GAP-005/GAP-006
  contracts and any remaining consumer-only work.
- Update relevant UI/backend handoff docs so DXR13/UI work knows which backend
  surfaces are real.
- Record remaining non-claims:
  application CRUD writes, nested writes, broad deployment orchestration,
  product UI, and any unsupported runtime lowering.
- Mark this roadmap complete only when validation and audit pass.

Validation:

- Focused conformance/profile tests.
- Representative `/admin/graphql` mutation suite.
- Artifact adapter tests.
- Store adapter tests.
- `git diff --check`.
- `git diff --cached --check`.
- HANDOFF REVIEW AUDIT.

Done when:

- Titan GraphQL has consumed the completed Titan core contracts for the current
  management/artifact handoff.
- Remaining work is clearly product/UI/backend expansion, not ambiguous core
  gap debt.

## Loop Operating Contract

Each loop run must:

1. Read this roadmap, `docs/titan-core-gap-ledger.md`,
   `docs/mutation-runtime-lowering-boundary.md`, the control file, ledger,
   today's and yesterday's daily memory files if present, and both repo states.
2. Stop with `NO_REPLY` if the control status is not active.
3. Complete at most one bounded milestone slice per run.
4. Keep `titan-graphql` changes in `titan-graphql` unless a reusable Titan
   contract gap is proven and recorded.
5. Validate and run HANDOFF REVIEW AUDIT before commit/push/advance.
6. Commit and push focused changes when validation and audit pass.
7. Update the loop ledger and control file after each successful slice.
8. Report to Discord only for committed milestones, completed plan points,
   real blockers, or loop completion.

Do not:

- Put GraphQL-specific concepts into Titan core.
- Make `titan` depend on `titan-graphql`.
- Use in-memory management state for production claims.
- Promote conformance rows without endpoint/store/artifact evidence.
- Resume UI implementation before backend handoff surfaces are real.
