# Titan GraphQL Developer Experience Execution Roadmap

Status: ready for worker-loop execution after review.

This roadmap turns `docs/developer-experience-proposal.md` into executable
implementation slices. It assumes the bounded query-contract roadmap is closed
for the demo-blog surface and that the next risk is product adoption rather than
more query semantics.

The roadmap is intentionally milestone-sized. Each worker run should pick one
bounded slice, produce one PR, validate it, merge it when safe, and update the
roadmap ledger. Do not start broad refactors, UI work, or runtime mutation
execution until the earlier source-format and validation foundations exist.

## Source Inputs

Primary design input:

- `docs/developer-experience-proposal.md`

Existing implementation inputs:

- `docs/query-contract.md`
- `docs/query-contract-roadmap.md`
- `docs/query-contract-conformance.md`
- `docs/generated-schema.md`
- `src/main/java/io/titan/graphql/DemoBlogGraphqlSchema.java`
- `src/main/java/io/titan/graphql/ProjectionModel.java`
- `src/main/java/io/titan/graphql/ProjectionType.java`
- `src/main/java/io/titan/graphql/ProjectionField.java`
- `src/main/java/io/titan/graphql/ProjectionRelation.java`
- `src/main/java/io/titan/graphql/ProjectionGraphqlAdapter.java`
- `src/test/java/io/titan/graphql/GraphqlMetamodelTest.java`
- `src/test/java/io/titan/graphql/GraphqlSchemaPrinterTest.java`
- `src/test/java/io/titan/graphql/TitanGraphqlFunctionsTest.java`

## Product Target

The execution target is a reviewable developer workflow:

```text
titan.graphql.yaml
  -> parsed source document
  -> canonical JSON model IR
  -> semantic validation report
  -> projection model
  -> generated GraphQL SDL / introspection / conformance / SQL artifacts
  -> optional preview build
  -> reviewed deployment record
  -> runtime /graphql

/admin/graphql
  -> model drafts
  -> validation, drift, artifact, preview, operation, deployment, and usage APIs
  -> management UI and consumer portal
```

The first execution phase should prove the developer source format, validation
model, public projection API, and generated artifact workflow before attempting
the full management API or UI.

## Execution Principles

- Keep each slice independently reviewable.
- Prefer source-format and validation foundations before storage, UI, or
  deployment features.
- Keep Java-mode as the reference behavior for new model/DX features.
- Promote a runtime capability only when the SQL/lowered path and conformance
  expectations are clear.
- Keep generated SDL as an artifact, not source truth.
- Treat YAML import and generated artifacts as deterministic outputs.
- Keep schema inference conservative: generate drafts, never auto-deploy them.
- Keep drift detection fail-closed for breaking binding errors.
- Build a minimal general mutation runtime before management mutations use it.
- Use `/admin/graphql` only for management/control-plane behavior.
- Reserve `/graphql` for application/runtime schemas.
- Do not implement full generated application CRUD in this roadmap.
- Do not enable the old worker loop until the roadmap PR is merged and the
  control file points to this document.

## Worker Run Protocol

Each worker run should:

1. Read this roadmap, `docs/developer-experience-proposal.md`, the loop control
   file, and the ledger.
2. Inspect `git status --short --branch` for `titan-graphql` and related repos.
3. Inspect recent commits and open PRs before choosing work.
4. Expand the selected milestone into concrete tasks in the ledger.
5. Pick exactly one bounded slice.
6. Implement, or precisely block if the slice is not yet feasible.
7. Validate with the smallest meaningful gate:
   - docs-only: `git diff --check`
   - parser/model/test slices: focused tests plus `./gradlew test`
   - runtime/lowered slices: `./gradlew test titanTranspile`
8. Open a PR, review the diff, mark ready, and merge only when validation
   passes and no substantive blocker exists.
9. Update the ledger with analysis, task list, PR, validation, blockers, and
   next suggested slice.
10. Post to Discord only for merged PRs, meaningful progress, or real blockers.

## Milestone Overview

Recommended order:

1. DXR0: Roadmap and loop bootstrap
2. DXR1: Model document format and canonical IR
3. DXR2: Demo-blog YAML equivalence
4. DXR3: Public projection API
5. DXR4: Validation engine and source locations
6. DXR5: Schema inference scaffolding
7. DXR6: Drift detection
8. DXR7: Generated artifact workflow
9. DXR8: Minimal mutation runtime
10. DXR9: `/admin/graphql` management API foundation
11. DXR10: Dogfood management model
12. DXR11: Preview builds and candidate endpoints
13. DXR12: Operation registry and usage analytics
14. DXR13: UI and consumer portal foundation
15. DXR14: Deployment, rollback, and runtime health

The worker should not jump to DXR8+ until DXR1-DXR4 establish a stable model
document and validation foundation.

## DXR0: Roadmap And Loop Bootstrap

Goal: make the roadmap executable by the existing disabled worker loop.

Deliverables:

- this roadmap document
- README link from the project front door
- loop control file pointed at this roadmap
- ledger entry recording that the old query-contract roadmap is closed
- clear first executable slice

Exit criteria:

- `docs/developer-experience-roadmap.md` is merged
- local loop control status is `ready` or `active` for this roadmap, depending
  on whether the maintainer has asked to start execution
- the cron job remains disabled unless explicitly re-enabled

Validation:

```bash
git diff --check
```

## DXR1: Model Document Format And Canonical IR

Goal: define the source document that developers can author and the canonical IR
that APIs and validation can use.

### DXR1.1 Source Document Schema Reference

Deliverables:

- `docs/model-document-format.md`
- documented `apiVersion`, `kind`, `metadata`, `roots`, `types`, `fields`,
  `relations`, `policies`, `contextFilters`, `artifacts`, and `deployment`
  sections
- example source snippets for root, type, field, relation, policy, filter, sort,
  Relay, computed field, module, and artifact output settings
- explicit unsupported fields for v1alpha1

Exit criteria:

- a reader can author a minimal model document without reading Java descriptor
  code
- the document names which proposal features are first-roadmap and which are
  reserved

Validation:

```bash
git diff --check
```

### DXR1.2 Canonical IR Records

Deliverables:

- Java records/classes for canonical model document IR
- no runtime behavior change
- tests for deterministic equality and required field defaults

Suggested package:

- `io.titan.graphql.model`

Candidate types:

- `TitanGraphqlModelDocument`
- `TitanGraphqlModelMetadata`
- `TitanGraphqlRootDocument`
- `TitanGraphqlTypeDocument`
- `TitanGraphqlFieldDocument`
- `TitanGraphqlRelationDocument`
- `TitanGraphqlPolicyDocument`
- `TitanGraphqlContextFilterDocument`
- `TitanGraphqlArtifactOptions`
- `TitanGraphqlModuleDocument`

Exit criteria:

- Java code can represent the demo-blog model as a canonical object graph
- defaults are explicit and deterministic

Validation:

```bash
./gradlew test --tests io.titan.graphql.*ModelDocument*
./gradlew test
git diff --check
```

### DXR1.3 JSON Serialization And Deterministic Hashing

Deliverables:

- canonical JSON serializer for the IR
- semantic hash calculation that excludes whitespace and comments
- tests for stable key ordering and hash stability

Exit criteria:

- equivalent IR instances serialize identically
- behavior-affecting changes alter the semantic hash
- non-semantic ordering changes do not alter the hash where order is declared
  insignificant

Validation:

```bash
./gradlew test --tests io.titan.graphql.*ModelDocument*
./gradlew test
git diff --check
```

### DXR1.4 YAML Parser Boundary

Deliverables:

- YAML parser entrypoint for `titan.graphql.yaml`
- dependency decision documented in `docs/model-document-format.md`
- parse error shape with source line/column where available
- fixture tests for valid and invalid YAML

Exit criteria:

- a simple YAML document parses into canonical IR
- parse errors do not throw raw library exceptions to callers
- unsupported top-level sections fail with precise errors

Validation:

```bash
./gradlew test --tests io.titan.graphql.*ModelDocument*
./gradlew test
git diff --check
```

### DXR1.5 JSON Schema For Editor Support

Deliverables:

- generated or hand-authored JSON Schema for `titan.graphql.yaml`
- schema examples in docs
- tests or fixture check that the schema file is present and valid JSON

Exit criteria:

- editors can attach a schema to the YAML model file
- required fields, enums, and common nested structures are represented

Validation:

```bash
./gradlew test
git diff --check
```

## DXR2: Demo-Blog YAML Equivalence

Goal: prove the source format can represent the current demo-blog projection
model without losing query-contract behavior.

### DXR2.1 Demo-Blog YAML Fixture

Deliverables:

- `src/test/resources/graphql/demo-blog.titan.graphql.yaml`
- fixture mirrors `DemoBlogGraphqlSchema.projectionModel(policy)`
- comments explain non-obvious generated filter/sort/context behavior

Exit criteria:

- every current root, type, field, relation, computed field, filter, sort,
  context filter, policy, and Relay capability appears in the fixture

Validation:

```bash
git diff --check
```

### DXR2.2 IR To Projection Model Adapter

Deliverables:

- adapter from canonical model document IR to `ProjectionModel`
- tests comparing Java descriptor and YAML descriptor generated schema
- initial limitations documented as explicit validation errors

Exit criteria:

- demo-blog YAML adapts into a `ProjectionModel`
- generated SDL matches the existing Java descriptor path

Validation:

```bash
./gradlew test --tests io.titan.graphql.GraphqlMetamodelTest
./gradlew test --tests io.titan.graphql.GraphqlSchemaPrinterTest
./gradlew test
git diff --check
```

### DXR2.3 Query Contract Equivalence Smoke

Deliverables:

- tests that execute representative query-contract examples against YAML-backed
  demo-blog schema
- Java descriptor path remains intact

Representative coverage:

- point lookup
- Relay connection with count
- filter/orderBy
- computed field
- relation traversal
- context filter fail-closed behavior
- introspection smoke

Exit criteria:

- YAML-backed schema produces the same responses for bounded demo-blog examples
- no conformance rows regress

Validation:

```bash
./gradlew test --tests io.titan.graphql.TitanGraphqlFunctionsTest
./gradlew test --tests io.titan.graphql.GraphqlConformanceMatrixTest
./gradlew test titanTranspile
git diff --check
```

## DXR3: Public Projection API

Goal: stop forcing application developers to construct package-private internal
descriptor objects directly.

### DXR3.1 Public Builder Facade

Deliverables:

- public builder/facade API over projection roots, types, fields, relations,
  policies, computed fields, and context filters
- non-demo sample model using only public API
- internal descriptor constructors remain compatible

Exit criteria:

- a new model can be declared without importing package-private implementation
  details

Validation:

```bash
./gradlew test --tests io.titan.graphql.GraphqlMetamodelTest
./gradlew test
git diff --check
```

### DXR3.2 Public API Documentation

Deliverables:

- `docs/projection-api.md`
- examples for roots, object types, relations, policies, context filters,
  filters, sorts, computed fields, and artifacts

Exit criteria:

- the public API docs can replace reading `DemoBlogGraphqlSchema` as the only
  onboarding path

Validation:

```bash
git diff --check
```

## DXR4: Validation Engine And Source Locations

Goal: make model errors precise, stable, and usable in CLI, API, UI, CI, and
editors.

### DXR4.1 Validation Issue Model

Deliverables:

- `ValidationReport`
- `ValidationIssue`
- stable issue codes
- severity: `ERROR`, `WARNING`, `INFO`
- source location and model path
- `blocksDeployment`

Exit criteria:

- parser and semantic validation both produce a common report shape

Validation:

```bash
./gradlew test --tests io.titan.graphql.*Validation*
./gradlew test
git diff --check
```

### DXR4.2 Semantic Validation Foundation

Deliverables:

- validate root references
- validate type references
- validate field names and duplicate names
- validate relation targets
- validate policy references
- validate computed field references
- validate filter/order declarations against field capabilities

Exit criteria:

- invalid fixtures produce precise issue codes and source paths
- valid demo-blog fixture passes

Validation:

```bash
./gradlew test --tests io.titan.graphql.*Validation*
./gradlew test
git diff --check
```

### DXR4.3 CLI-Friendly Diagnostics

Deliverables:

- report rendering for terminal/CI
- JSON report output suitable for editor integration
- docs for expected diagnostic format

Exit criteria:

- a validation report can be consumed by humans and tools

Validation:

```bash
./gradlew test
git diff --check
```

## DXR5: Schema Inference Scaffolding

Goal: produce a reviewable model draft from an existing database/catalog without
auto-exposing the database.

### DXR5.1 Catalog Snapshot Model

Deliverables:

- internal catalog snapshot records for schemas, tables, columns, primary keys,
  foreign keys, indexes, nullability, and comments
- test fixture catalog for demo-blog-like tables

Exit criteria:

- inference can run against a deterministic test catalog without a live DB

Validation:

```bash
./gradlew test --tests io.titan.graphql.*Inference*
./gradlew test
git diff --check
```

### DXR5.2 Conservative Draft Generator

Deliverables:

- inference from catalog snapshot to canonical IR draft
- public roots disabled or marked review-required by default
- relation candidates marked as suggestions
- policy placeholders for sensitive-looking fields

Exit criteria:

- inferred output is useful scaffolding but cannot deploy without normal review
  and validation

Validation:

```bash
./gradlew test --tests io.titan.graphql.*Inference*
./gradlew test
git diff --check
```

### DXR5.3 Inference Report

Deliverables:

- inference report with inferred objects, skipped objects, warnings, and required
  human decisions
- YAML export of inferred draft

Exit criteria:

- users can see exactly what was inferred and what was not

Validation:

```bash
./gradlew test
git diff --check
```

## DXR6: Drift Detection

Goal: detect when the model no longer matches the database, migration snapshot,
or deployed artifact manifest.

### DXR6.1 Binding Check Against Catalog Snapshot

Deliverables:

- compare model roots/types/fields/relations/computed SQL bindings against a
  catalog snapshot
- issue codes for missing table, missing column, type mismatch, nullability
  mismatch, missing key, missing index, and broken relation join

Exit criteria:

- breaking database/model mismatches block deployment

Validation:

```bash
./gradlew test --tests io.titan.graphql.*Drift*
./gradlew test
git diff --check
```

### DXR6.2 Drift Report Integration

Deliverables:

- drift report attached to validation report and artifact set
- drift summary docs
- fixture tests for breaking, warning, and info drift

Exit criteria:

- drift is visible in the same report surfaces as semantic validation

Validation:

```bash
./gradlew test
git diff --check
```

## DXR7: Generated Artifact Workflow

Goal: make generated outputs reviewable before deployment.

### DXR7.1 Artifact Set Model

Deliverables:

- artifact set record with semantic hash, SDL hash, introspection hash,
  conformance hash, generated SQL hash, validation hash, and drift hash
- deterministic artifact manifest JSON

Exit criteria:

- a model draft can produce a stable artifact manifest

Validation:

```bash
./gradlew test --tests io.titan.graphql.*Artifact*
./gradlew test
git diff --check
```

### DXR7.2 SDL And Introspection Artifacts

Deliverables:

- generated SDL artifact from IR-backed projection model
- introspection artifact when policy allows it
- artifact diff tests

Exit criteria:

- reviewers can inspect generated API shape without executing a deployment

Validation:

```bash
./gradlew test --tests io.titan.graphql.GraphqlSchemaPrinterTest
./gradlew test --tests io.titan.graphql.*Introspection*
./gradlew test
git diff --check
```

### DXR7.3 Conformance And SQL Artifact Metadata

Deliverables:

- conformance matrix artifact per model/support profile
- generated SQL metadata artifact
- strict guard against unexpected `PENDING` or `JAVA_ONLY` rows for production
  profile

Exit criteria:

- artifact generation can fail before deployment if support profile is not met

Validation:

```bash
./gradlew test --tests io.titan.graphql.GraphqlConformanceMatrixTest
./gradlew test titanTranspile
git diff --check
```

## DXR8: Minimal Mutation Runtime

Goal: build the smallest general mutation runtime needed for Titan management
mutations, without implementing full application CRUD.

### DXR8.1 Mutation Operation Parsing Boundary

Deliverables:

- parse mutation operations as first-class GraphQL operations
- keep application/runtime `/graphql` mutation execution rejected by default
- tests for named mutation operation selection and explicit rejection where
  no mutation schema exists

Exit criteria:

- mutation syntax can be represented without accidentally enabling application
  writes

Validation:

```bash
./gradlew test --tests io.titan.graphql.*Parser*
./gradlew test
git diff --check
```

### DXR8.2 Named Mutation Descriptor Model

Deliverables:

- mutation descriptor for command-style mutations
- typed input object metadata
- typed payload object metadata
- authorization metadata
- transaction/audit metadata placeholders

Exit criteria:

- the management schema can declare named mutations through metadata, not
  private controller methods

Validation:

```bash
./gradlew test
git diff --check
```

### DXR8.3 Java-Mode Mutation Executor

Deliverables:

- command-handler interface for named mutations
- input coercion and validation path
- GraphQL-shaped mutation payload and error output
- audit event record emitted to in-memory test sink

Exit criteria:

- a trivial test mutation uses the shared runtime path end to end

Validation:

```bash
./gradlew test --tests io.titan.graphql.*Mutation*
./gradlew test
git diff --check
```

### DXR8.4 Lowering Boundary Decision

Deliverables:

- document which mutation runtime pieces are Java-mode only initially and which
  must be lowerable before production management deployment
- explicit conformance rows for mutation runtime support profile
- `docs/mutation-runtime-lowering-boundary.md`

Exit criteria:

- the roadmap has a precise boundary before management mutations depend on it
- application `/graphql` remains query-only and broad application CRUD remains
  out of scope

Validation:

```bash
git diff --check
```

## DXR9: `/admin/graphql` Management API Foundation

Goal: expose model lifecycle objects through Titan's own GraphQL management
endpoint.

### DXR9.1 Management Domain Model

Deliverables:

- managed workspace, model, draft, validation report, artifact set, deployment,
  drift report, preview build, operation registry, and usage report descriptors
- initial in-memory management store for tests

Exit criteria:

- management objects can be queried through the same schema machinery used by
  application models

Validation:

```bash
./gradlew test --tests io.titan.graphql.*Management*
./gradlew test
git diff --check
```

### DXR9.2 `/admin/graphql` Resource Boundary

Deliverables:

- separate `/admin/graphql` endpoint
- separate auth/context extraction boundary
- separate introspection policy
- no overlap with application `/graphql`

Exit criteria:

- `/graphql` still serves only the active application model
- `/admin/graphql` serves management schema in tests

Validation:

```bash
./gradlew test --tests io.titan.graphql.*Http*
./gradlew test titanTranspile
git diff --check
```

### DXR9.3 Import, Validate, Generate Mutations

Deliverables:

- `importModelDocument`
- `validateModelDraft`
- `generateModelArtifacts`
- mutations use the minimal shared mutation runtime
- audit records for each mutation

Exit criteria:

- a user can import YAML and generate artifacts through `/admin/graphql`

Validation:

```bash
./gradlew test --tests io.titan.graphql.*Management*
./gradlew test
git diff --check
```

## DXR10: Dogfood Management Model

Goal: express the management API model through Titan's own model format.

Deliverables:

- management model YAML or canonical IR fixture
- generated management SDL
- conformance profile for management API
- tests proving management queries use projection/query machinery
- tests proving management mutations use the minimal mutation runtime

Exit criteria:

- the management API is a real internal customer of Titan GraphQL

Validation:

```bash
./gradlew test --tests io.titan.graphql.*Management*
./gradlew test titanTranspile
git diff --check
```

## DXR11: Preview Builds And Candidate Endpoints

Goal: serve a generated candidate artifact before activating it.

### DXR11.1 Preview Build Record And Manifest

Deliverables:

- preview build lifecycle states
- preview endpoint metadata
- expiration metadata
- manifest references artifact set, model, environment, and schema hash

Exit criteria:

- artifact candidates can be represented as preview builds

Validation:

```bash
./gradlew test
git diff --check
```

### DXR11.2 Preview Runtime Routing

Deliverables:

- test-only candidate endpoint route
- route selects preview artifact instead of active deployment
- stable `/graphql` remains unchanged

Exit criteria:

- tests can query active and preview schemas independently

Validation:

```bash
./gradlew test --tests io.titan.graphql.*Preview*
./gradlew test
git diff --check
```

### DXR11.3 Contract Test Hook

Deliverables:

- operation contract test runner against preview endpoint
- integration with operation registry where available
- report attached to preview build

Exit criteria:

- a preview build can be tested before promotion

Validation:

```bash
./gradlew test
git diff --check
```

## DXR12: Operation Registry And Usage Analytics

Goal: make production API usage reviewable and enforceable.

### DXR12.1 Observed Operation Store

Deliverables:

- operation hash, name, document, role, client, environment, status, depth, cost,
  and field usage
- learning mode for tests
- management queries for observed operations

Exit criteria:

- new operations can be collected without enforcement

Validation:

```bash
./gradlew test --tests io.titan.graphql.*Operation*
./gradlew test
git diff --check
```

### DXR12.2 Approve/Reject Workflow

Deliverables:

- `approveObservedOperation`
- `rejectObservedOperation`
- operation collections by environment and role
- audit records

Exit criteria:

- reviewers can curate operation allow lists through `/admin/graphql`

Validation:

```bash
./gradlew test
git diff --check
```

### DXR12.3 Runtime Enforcement Modes

Deliverables:

- `OBSERVE`, `WARN`, and `ENFORCE`
- clear rejection errors for unknown operations in enforcement mode
- preview-build contract-test integration

Exit criteria:

- production can fail closed on unapproved operations when configured

Validation:

```bash
./gradlew test titanTranspile
git diff --check
```

### DXR12.4 Usage Analytics Reports

Deliverables:

- usage by operation, field, role, client, environment, and version
- deprecated fields still in use
- unused fields in a selected window
- policy rejection counts
- slow operation summaries

Exit criteria:

- compatibility review can consult real usage signals

Validation:

```bash
./gradlew test
git diff --check
```

## DXR13: UI And Consumer Portal Foundation

Goal: provide the backend metadata and `/admin/graphql` contracts required by
the accepted UI planning packet in `docs/ui/`, without building a large frontend
before the management API stabilizes.

Selected direction: Operator Workbench + Model Studio hybrid. DXR13 should make
the model overview, validation/drift/source workflow, artifact review, preview
workflow, semantic comparison, operation/usage review, runtime alignment, and
rollback eligibility possible through explicit management metadata. Consumer
portal support remains required for active/preview schema browsing and sample
query handoff, but it should not drive private governance or producer controls
into portal-facing contracts.

DXR13 must stay anchored to:

- `docs/ui/workflows.md`
- `docs/ui/information-architecture.md`
- `docs/ui/screen-inventory.md`
- `docs/ui/state-model.md`
- `docs/ui/content-model.md`
- `docs/ui/api-contract-matrix.md`
- `docs/ui/prototype-brief.md`
- `docs/ui/design-readiness-checklist.md`

Do not build metadata just because it is possible. Build metadata because a
selected first-slice workflow, screen, or prototype direction requires it.

### DXR13.0 UI Planning Acceptance

Deliverables:

- accepted UI planning packet under `docs/ui/`
- selected Operator Workbench + Model Studio hybrid prototype direction
- first-slice screen set confirmed around model overview, source diagnostics,
  drift, artifacts, preview, compare, operation review, usage impact, runtime
  health, rollback eligibility, and portal-safe schema docs
- product decisions recorded for YAML import-first scope, sample query handoff,
  operation registry placement, usage freshness, generated SQL drill-downs, and
  relationship graph priority

Exit criteria:

- DXR13 implementation scope maps to accepted workflows and screens
- visual design and backend metadata work agree on first-slice priorities

Validation:

```bash
git diff --check
```

### DXR13.1 Portal Metadata Projection

Deliverables:

- management query for active and preview schema portal data
- docs metadata for roots, types, fields, relations, filters, sorts,
  pagination, deprecations, policies, role/client visibility, and operation
  status
- portal-safe separation between consumer-facing explanations and private
  operator/governance controls
- model overview links back to the source, artifact, preview, compare, and
  operation review surfaces that own the underlying state

Exit criteria:

- consumer schema docs can be built for active and preview schemas without
  private endpoints or frontend-only source truth

Validation:

```bash
./gradlew test
git diff --check
```

### DXR13.2 Sample Query Metadata

Deliverables:

- sample query records for roots, relations, and common preview scenarios
- variables, role/client applicability, schema concept links, active/preview
  compatibility, operation hash, and registry status
- reproducible request metadata for CI or external client tooling

Exit criteria:

- the consumer portal can show useful starting queries and operation status at
  the point of use

Validation:

```bash
./gradlew test
git diff --check
```

### DXR13.3 Relationship Graph Metadata

Deliverables:

- graph metadata for model types, roots, relations, joins, policies,
  context-filters, and visibility
- drift and validation links for relation/join problems
- generated JSON artifact or management projection for UI consumption

Exit criteria:

- the UI can render an ERD-like or relationship-inspection view without
  introspecting private Java classes

Validation:

```bash
./gradlew test
git diff --check
```

### DXR13.4 Gate Summary And Semantic Comparison

Deliverables:

- model overview gate projection for validation, drift, artifacts, preview,
  operations, usage, review, deployment, and runtime health
- source/diagnostic owner routing so gate entries can link to the responsible
  producer, reviewer, operator, or consumer workflow
- semantic comparison projection for draft, preview, and deployed versions
- added, removed, changed, deprecated, visibility-changed, policy-changed, and
  relation-changed schema concepts

Exit criteria:

- model overview, deployment review, and compare screens can explain
  deployability and source truth without private frontend aggregation

Validation:

```bash
./gradlew test
git diff --check
```

### DXR13.5 Runtime Health And Rollback Metadata

Deliverables:

- runtime health projection with expected/observed schema hash, artifact hash,
  endpoint status, registry mode, freshness, and mismatch reason
- rollback eligibility metadata for current and previous deployments
- deployment-readiness evidence envelope for operator review, including usage
  freshness and links to operation/usage risk summaries
- read-only first-slice metadata by default; promotion and rollback mutations
  remain DXR14 unless a later DXR13 slice explicitly scopes them

Exit criteria:

- operators can inspect active runtime alignment and rollback readiness through
  `/admin/graphql`

Validation:

```bash
./gradlew test
git diff --check
```

### DXR13.6 UI Data-Contract Fixtures

Deliverables:

- fixture records covering the first-slice screens in
  `docs/ui/screen-inventory.md`
- conformance tests proving management queries expose the fields named in
  `docs/ui/api-contract-matrix.md`
- report/artifact JSON fixtures for validation, drift, artifacts, preview
  contract tests, operations, usage, deployment, and health states
- fixture scenarios matching the accepted hybrid prototype: one deployability
  overview, one producer diagnostic/artifact flow, one operation/usage review,
  one runtime/rollback review, and one portal-safe preview schema path

Exit criteria:

- frontend prototypes and first implementation slice can develop against stable
  documented data contracts

Validation:

```bash
./gradlew test
git diff --check
```

## DXR14: Deployment, Rollback, And Runtime Health

Goal: complete the operational loop from reviewed artifact to active runtime
version.

### DXR14.1 Deployment Records

Deliverables:

- deployment record model
- environment status
- previous deployment / rollback target
- actor and timestamp
- artifact hashes

Exit criteria:

- activation is recorded and auditable

Validation:

```bash
./gradlew test
git diff --check
```

### DXR14.2 Promote Preview To Active

Deliverables:

- promote preview build mutation
- stable endpoint points at promoted artifact
- compatibility and drift checks required before promotion

Exit criteria:

- a preview build can become the active deployment only after validation gates
  pass

Validation:

```bash
./gradlew test titanTranspile
git diff --check
```

### DXR14.3 Rollback

Deliverables:

- rollback deployment mutation
- rollback target validation
- audit record
- runtime health update

Exit criteria:

- a prior artifact can be reactivated safely

Validation:

```bash
./gradlew test
git diff --check
```

### DXR14.4 Runtime Health Summary

Deliverables:

- active schema hash
- artifact hash
- last validation/drift status
- recent query error codes
- operation registry enforcement mode
- preview build status summary

Exit criteria:

- operators can see whether runtime matches the reviewed model/artifact

Validation:

```bash
./gradlew test
git diff --check
```

## First Worker Slice

The first implementation worker slice after this roadmap should be DXR1.1:
create `docs/model-document-format.md` with the v1alpha1 YAML schema reference
and examples.

Keep the first slice docs-only. It should not add a YAML parser, dependencies,
or runtime behavior. That gives the loop a small, low-risk restart after the
completed query-contract roadmap and creates the source-format contract that
DXR1.2-DXR1.5 can implement.

Suggested first-slice validation:

```bash
git diff --check
```

Suggested first-slice PR title:

```text
[codex] Define Titan GraphQL model document format
```

## Blocker Definitions

The worker should report a blocker only when it cannot safely make meaningful
progress. Useful blocker identifiers:

- `model_format_decision_required`
- `yaml_parser_dependency_decision_required`
- `canonical_ir_hash_semantics_unclear`
- `projection_adapter_gap`
- `source_location_support_missing`
- `catalog_snapshot_source_unclear`
- `mutation_lowering_boundary_unclear`
- `admin_runtime_storage_decision_required`
- `preview_endpoint_route_decision_required`
- `operation_registry_enforcement_policy_required`
- `ui_scope_decision_required`

Do not use a blocker for ordinary implementation work, missing tests, or local
refactoring effort.

## Completion Criteria

This roadmap is complete when:

- developers can author and validate a YAML model without internal descriptors
- demo-blog YAML is equivalent to the current Java descriptor model
- a public projection API exists for non-YAML Java users
- generated artifacts are deterministic and reviewable
- schema inference and drift detection are available as safe scaffolding and
  validation tools
- `/admin/graphql` can import, validate, generate, preview, deploy, and roll
  back models through the shared query/mutation runtime
- management model dogfooding proves Titan's own API can be built on Titan
  GraphQL
- preview builds, operation registry, usage analytics, and portal metadata are
  available enough to support production review workflows

Anything beyond that, including full generated application CRUD, subscriptions,
federated composition, external connector marketplaces, and arbitrary lifecycle
plugins, should become a later roadmap.
