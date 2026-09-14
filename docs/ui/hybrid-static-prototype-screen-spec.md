# Titan GraphQL Hybrid Static Prototype Screen Spec

Status: superseded by Hasura-informed Portal Build Explorer direction.

This design-only screen spec translates the earlier Operator Workbench + Model
Studio hybrid direction into medium-fidelity static frames for design review and
backend-contract handoff. It uses the `customer360` staging scenario from
`docs/ui/hybrid-prototype-scenario-packet.md` and the flow model from
`docs/ui/hybrid-navigation-screen-flow.md`.

Supersession note: after reviewing Hasura DDN portal capabilities and the
disconnected clickable prototype variants, this spec should not drive the next
prototype. The current direction is the Portal Build Explorer described in
`docs/ui/hasura-portal-capability-study.md` and
`docs/ui/prototype-brief.md`. Keep this document as historical evidence for
source, artifact, deployment, rollback, and governance drill-downs.

This document does not define backend behavior, runtime behavior, production
frontend implementation, generated assets, screenshots, or mutations.

## Shared Frame System

All model-scoped frames use the same chrome:

- Top bar: workspace `workspace-enterprise-apis`, model switcher
  `customer360`, environment `staging`, and version lens where applicable
  (`Draft`, `Preview`, `Active`, `Rollback`).
- Context header: model name, readiness badge, active deployment id
  `deploy-c360-staging-20260528-0915`, preview build id
  `preview-c360-20260603-1042`, source commit `4f72c18`, and CI run
  `ci-customer360-preview-9821`.
- Left navigation: Models, Operations, Usage, Deployments, Consumer Portal.
- Evidence footer: validation, drift, artifact manifest, contract-test, usage,
  active deployment, and rollback references when relevant to the frame.

Badge vocabulary:

| Badge | Meaning | Prototype treatment |
| --- | --- | --- |
| `blocked` | Deployment readiness cannot advance. | Red state, always paired with the blocking evidence id. |
| `warning` | Inspectable risk that does not by itself block preview. | Amber state with owner route. |
| `ready_with_failures` | Preview endpoint is available but has failed or skipped tests. | Amber preview state with test counts. |
| `complete_with_warnings` | Artifact set generated, with at least one warning. | Amber manifest state with reproducible paths. |
| `approved` | Operation is approved for its role/client. | Green explanatory state in portal; actionable only in Operations. |
| `rejected` | Operation is rejected by registry policy. | Red state in Operations and preview failures. |
| `unknown` | Operation needs review. | Neutral pending state; no portal governance control. |
| `eligible` | Rollback target has matching evidence and no open runtime mismatch. | Green evidence state, not a rollback action. |

Primary action rules:

- Model Studio actions inspect diagnostics, artifacts, preview evidence, and
  comparison details.
- Operator Workbench actions review gates, operations, usage impact, deployment
  evidence, and rollback evidence.
- Consumer Portal actions browse, copy, and hand off portal-safe samples only.
- Deployment, rollback, operation approval, and operation rejection are shown as
  review affordances in operator/reviewer frames only; this static spec does
  not introduce executable mutations.

## 1. Models List

Purpose: let operators and producers select a model while seeing deployability
and preview risk before opening detail.

Layout regions:

- Top bar with workspace and environment filter.
- Summary band with counts for blocked models, previews with failures, and
  healthy active deployments.
- Model table with columns for model, environment, readiness, active
  deployment, preview build, blockers, warnings, usage risk, and last evidence.
- Right-side filter panel for readiness, environment, and version lens
  availability.

Key controls:

- Workspace selector, environment selector, readiness filter, model search, and
  row open control.
- Product-area shortcuts for Operations, Usage, Deployments, and Consumer
  Portal remain global, but no version lens is shown on this unscoped list.

Evidence shown:

- `customer360` row shows `blocked`, active deployment
  `deploy-c360-staging-20260528-0915`, preview
  `preview-c360-20260603-1042`, validation report
  `validation-c360-20260603-1042`, and contract-test report
  `contract-preview-c360-20260603-1042`.
- Compact counts: 1 blocker, 2 warnings, 18 passed, 2 failed, 1 skipped.

Primary actions:

- Open Model Overview.
- Open Deployment Review from the `blocked` readiness cell.
- Open Preview Build from the preview state cell.

Empty, error, and blocked states:

- Empty: show no models for the selected workspace/environment with a link to
  clear filters.
- Error: show unavailable model inventory with the failed admin/report source.
- Blocked: if model inventory loads but readiness evidence does not, keep rows
  selectable and show `evidence unavailable` in readiness-dependent columns.

Backend-contract notes:

- Needs model inventory with active deployment, preview build, readiness
  summary, evidence ids, and report freshness. The UI must not infer readiness
  from presentation-only counts.

## 2. Model Overview

Purpose: act as the operating center for draft, preview, active deployment,
usage risk, operations risk, and rollback evidence.

Layout regions:

- Context header with `customer360`, `staging`, `blocked`, active deployment,
  preview build, source commit, and CI run.
- Three primary columns: Readiness, Lifecycle, and Risk.
- Evidence strip with validation, drift, artifact manifest, contract-test,
  usage, active deployment, and rollback references.
- Activity note area for owner routing: API producer, platform operator,
  reviewer, and API consumer.

Key controls:

- Version lens: Draft, Preview, Active, Rollback.
- Primary actions: Open blocking diagnostic, Open compare, Open deployment
  review, Open portal preview docs.
- Secondary actions: Open artifact manifest, preview build, usage impact,
  operations review, rollback evidence.

Evidence shown:

- Readiness: `policy_role_missing` blocker for `Customer.lifetimeValue`,
  `relationship_filter_partial` warning for `Customer.orders`, drift warning
  `column_type_mismatch`, and preview counts 18/2/1.
- Lifecycle: draft `draft-2026-06-03-customer360-yaml`, preview
  `preview-c360-20260603-1042`, active
  `deploy-c360-staging-20260528-0915`, rollback
  `deploy-c360-staging-20260521-1430`.
- Risk: `CustomerRevenueExport` rejected, `CustomerLifetimeValueRead` unknown,
  and `Customer.legacyTier` with 1,284 calls from 3 clients.

Primary actions:

- Open Source Diagnostics focused on `Customer.lifetimeValue`.
- Open Compare with draft/preview/active/rollback lanes.
- Open Deployment Review with readiness gates.

Empty, error, and blocked states:

- Empty: show model context without evidence cards and identify which report
  families are absent.
- Error: pin the failing evidence source in the evidence strip and keep other
  cards usable.
- Blocked: deployment readiness remains blocked while the preview and portal
  links stay inspectable.

Backend-contract notes:

- Overview requires a joined model summary but must retain source evidence ids
  for every card. Draft, preview, active, and rollback state must be separate
  fields rather than a collapsed version string.

## 3. Source Diagnostics

Purpose: route API producers from readiness problems to exact source concepts.

Layout regions:

- Context header with back link to Model Overview and Compare.
- Source outline for `models/customer360/titan.graphql.yaml`.
- Diagnostic list grouped by blockers and warnings.
- Selected field detail panel.
- Related evidence links to Drift Report, Artifact Manifest, and Deployment
  Review.

Key controls:

- Filter by severity, owner, object, and status.
- Select diagnostic row.
- Open Compare.
- Export/copy evidence reference for Git or CI discussion.

Evidence shown:

- Selected blocker: `policy_role_missing`,
  `models/customer360/titan.graphql.yaml:142`,
  `Customer.lifetimeValue`, missing `finance_reader` role policy, owner API
  producer with reviewer approval.
- Warning: `relationship_filter_partial`,
  `models/customer360/titan.graphql.yaml:88`, `Customer.orders`, equality
  filters only.

Primary actions:

- Open Compare focused on `Customer.lifetimeValue`.
- Open Deployment Review gate for the blocker.
- Open Artifact Manifest SQL warning for `Customer.orders`.

Empty, error, and blocked states:

- Empty: no diagnostics for the selected lens; show validation report id and
  generated time.
- Error: source outline unavailable; keep diagnostic rows visible if report
  JSON loaded.
- Blocked: display blocker detail first and make clear preview remains
  inspectable.

Backend-contract notes:

- Diagnostics need stable ids, severity, source location, object path, owner,
  message, and related report links. The UI must not parse source text to
  derive diagnostic placement.

## 4. Drift Report

Purpose: show model-to-database mismatch as inspectable deployment risk.

Layout regions:

- Context header with Drift Report title and link back to overview.
- Finding list with severity, model path, database binding, and runtime risk.
- Detail panel comparing expected and snapshot values.
- Related evidence rail to Artifact Manifest SQL warning and Deployment Review.

Key controls:

- Severity filter, binding search, open related artifact, open deployment gate.

Evidence shown:

- Report `drift-c360-snapshot-20260603-1000`.
- Finding `column_type_mismatch` for `Customer.lifetimeValue`.
- Expected `decimal(12,2)`, snapshot `decimal(10,2)`.
- Runtime risk: values above `99999999.99` may truncate in generated SQL.

Primary actions:

- Open Deployment Review warning gate.
- Open Artifact Manifest SQL entry.
- Open Compare focused on `Customer.lifetimeValue`.

Empty, error, and blocked states:

- Empty: no drift findings for selected model/environment.
- Error: database snapshot unavailable; show last known report id if present.
- Blocked: if drift report is stale, mark freshness as blocked for deployment
  evidence but do not hide validation or preview evidence.

Backend-contract notes:

- Requires drift report id, snapshot timestamp, model path, database binding,
  expected value, observed value, severity, and runtime risk.

## 5. Artifact Manifest

Purpose: make generated artifacts reviewable without forcing routine users into
generated SQL contents.

Layout regions:

- Context header with manifest state `complete_with_warnings`.
- Manifest summary with artifact set id
  `artifact-set-c360-20260603-1042`.
- Artifact table with type, path, hash, and status.
- Selected artifact detail panel for warning context.
- Reproducibility footer with source commit and CI run.

Key controls:

- Artifact type filter.
- Open artifact metadata.
- Open related source diagnostic.
- Copy manifest path/hash reference.

Evidence shown:

- Manifest `manifest-c360-20260603-1042`.
- SDL `artifacts/customer360/staging/schema.graphql`,
  hash `sha256:3f0c...7b22`, generated.
- Introspection `artifacts/customer360/staging/introspection.json`,
  hash `sha256:91aa...540e`, generated.
- Conformance `artifacts/customer360/staging/conformance.json`,
  hash `sha256:67de...2104`, generated.
- Generated SQL `artifacts/customer360/staging/sql/customer360.sql.json`,
  hash `sha256:a410...be12`, `generated_warning`.

Primary actions:

- Open SQL warning metadata.
- Open Preview Build.
- Open Source Diagnostics focused on `Customer.orders`.

Empty, error, and blocked states:

- Empty: no artifact manifest for selected preview; show preview build id and
  source commit for traceability.
- Error: manifest cannot load; preserve source and preview links.
- Blocked: generated SQL warning is highlighted but does not block manifest
  completion by itself.

Backend-contract notes:

- The manifest contract needs typed entries, reproducible paths, hashes,
  statuses, artifact set id, and warning linkage. Artifact content retrieval is
  a separate contract from manifest review.

## 6. Preview Build

Purpose: show preview endpoint readiness and contract-test results.

Layout regions:

- Context header with preview state `ready_with_failures`.
- Endpoint panel for `/preview/preview-c360-20260603-1042/graphql`.
- Contract-test summary with pass/fail/skip counts.
- Failed/skipped operation list.
- Related links to Operations Review, Artifact Manifest, Source Diagnostics,
  and Portal Schema Docs.

Key controls:

- Copy preview endpoint.
- Filter tests by result.
- Open failed operation.
- Open portal preview docs.

Evidence shown:

- Contract-test report `contract-preview-c360-20260603-1042`.
- Counts: 18 passed, 2 failed, 1 skipped.
- Failed: `CustomerRevenueExport` rejected by operation registry and
  `CustomerLifetimeValueRead` blocked by missing `finance_reader` policy.
- Skipped: `CustomerOrdersRangeSearch` due to unavailable generated SQL range
  lowering.

Primary actions:

- Open Operations Review for failed operations.
- Open Source Diagnostics for policy blocker.
- Open Portal Schema Docs in preview lens.

Empty, error, and blocked states:

- Empty: preview exists but no contract-test report yet; show endpoint and
  pending evidence.
- Error: endpoint unavailable; show preview build id and failed health source.
- Blocked: failed tests block promotion but do not hide the preview endpoint or
  portal docs.

Backend-contract notes:

- Needs endpoint, preview state, report id, test counts, failed/skipped
  operation ids, reasons, and related evidence links.

## 7. Compare

Purpose: compare semantic draft, preview, active, and rollback state in one
shared surface.

Layout regions:

- Context header with version lens and quick links to overview, diagnostics,
  preview, deployment review, and rollback evidence.
- Four comparison lanes: Draft, Preview, Active, Rollback.
- Change rows for field additions, deprecations, relationship/filter behavior,
  operation status, usage, and rollback evidence.
- Detail drawer for selected semantic change.

Key controls:

- Lane visibility toggles.
- Change type filter.
- Open related evidence.
- Copy comparison reference.

Evidence shown:

- `Customer.lifetimeValue`: added in draft, preview-only with contract failure,
  absent in active, absent in rollback.
- `Customer.legacyTier`: deprecated in draft/preview, used by approved active
  operation, present in rollback without new preview changes.
- `Customer.orders`: relationship metadata changed, equality filters only in
  preview, used by 11 approved operations, prior relationship metadata in
  rollback.

Primary actions:

- Open Source Diagnostics for policy blocker.
- Open Preview Build and Operations Review for preview failure.
- Open Usage Impact for deprecated-field usage.
- Open Rollback Evidence for rollback lane.

Empty, error, and blocked states:

- Empty: no semantic differences between selected lanes.
- Error: one lane unavailable; keep other lanes visible and mark missing lane.
- Blocked: compare can show incomplete lanes, but promotion-related actions
  remain blocked until required evidence loads.

Backend-contract notes:

- Requires semantic compare output across draft, preview, active, and rollback
  states. The UI should not compute schema semantics from raw SDL alone.

## 8. Operations Review

Purpose: isolate operation governance and review state from consumer portal
views.

Layout regions:

- Context header with links back to overview, preview build, and deployment
  review.
- Operation queue table with operation, role, client, status, evidence, and
  affected surface.
- Selected operation detail panel with registry reason and related test/usage
  context.
- Reviewer notes area for design review only.

Key controls:

- Filter by approved, rejected, unknown.
- Select operation.
- Review affordance placeholders for approve/reject/defer, clearly marked as
  governance controls in Operator Workbench.
- Open preview failure or portal-safe sample detail.

Evidence shown:

- Registry `operations-c360-staging-20260603`.
- `CustomerProfileRead`, role `support_reader`, client `helpdesk-web`,
  `approved`.
- `CustomerRevenueExport`, role `support_reader`, client `csv-exporter`,
  `rejected`.
- `CustomerLifetimeValueRead`, role `finance_reader`, client
  `finance-dashboard`, `unknown`.

Primary actions:

- Open Preview Build failed operation.
- Open Deployment Review operation gate.
- Open Portal Sample Query as portal-safe explanation.

Empty, error, and blocked states:

- Empty: no operations match selected filter.
- Error: operation registry unavailable; deployment gate shows evidence missing.
- Blocked: unknown/rejected operations block promotion review but are still
  visible for reviewer routing.

Backend-contract notes:

- Needs registry id, operation id/name, role, client, status, review reason,
  and relation to preview test failures. Governance mutations are outside this
  spec.

## 9. Usage Impact

Purpose: show deployment reviewers the user and client impact of schema change.

Layout regions:

- Context header with usage report freshness.
- Deprecated-field impact summary.
- Affected client and operation table.
- Replacement guidance panel.
- Links to Compare, Deployment Review, and Portal Schema Docs.

Key controls:

- Filter by deprecated, unused, active, client, and operation.
- Open affected operation.
- Open compare row.
- Copy usage report reference.

Evidence shown:

- Usage report `usage-c360-staging-20260603`, generated from last 24 hours of
  staging traffic.
- `Customer.legacyTier`, replacement `Customer.segment`, 1,284 calls,
  3 distinct clients, top client `helpdesk-web`.
- Affected approved operation `CustomerProfileRead`.
- `Customer.lifetimeValue` has no active deployed usage because it is
  preview-only.
- `Customer.orders` is used by 11 approved operations.

Primary actions:

- Open Deployment Review usage gate.
- Open Compare focused on `Customer.legacyTier`.
- Open Portal Schema Docs deprecation note.

Empty, error, and blocked states:

- Empty: no usage facts for selected model/environment and time window.
- Error: usage report unavailable; mark deployment evidence incomplete.
- Blocked: stale usage report blocks deployment evidence freshness, but schema
  compare remains available.

Backend-contract notes:

- Requires typed usage facts with report id, freshness, field path, call counts,
  distinct client counts, top client, operation linkage, and replacement text.

## 10. Deployment Review

Purpose: collect promotion readiness gates without hiding owner workflows.

Layout regions:

- Context header with `blocked` readiness and active/preview ids.
- Ordered readiness gate list.
- Evidence checklist with report ids and freshness.
- Decision summary panel that states why promotion is blocked.
- Related workflow links to diagnostics, preview, usage, operations, compare,
  and rollback evidence.

Key controls:

- Gate filter by blocking, warning, passed, and evidence missing.
- Open gate evidence.
- Copy deployment review packet.
- Review affordance placeholders; no executable deployment action in this spec.

Evidence shown:

- Blocking gate: validation blocker `policy_role_missing`.
- Blocking gate: preview contract failures 2 failed, 1 skipped.
- Warning gate: drift `column_type_mismatch`.
- Warning gate: deprecated-field usage of `Customer.legacyTier`.
- Passed evidence: active deployment healthy and runtime alignment
  `matches_active_artifacts`.
- Rollback evidence: target `deploy-c360-staging-20260521-1430` is `eligible`.

Primary actions:

- Open Source Diagnostics for blocker.
- Open Preview Build for failed/skipped tests.
- Open Usage Impact.
- Open Rollback Evidence.

Empty, error, and blocked states:

- Empty: no deployment review evidence for selected model/environment.
- Error: gate source unavailable; identify report source and keep loaded gates.
- Blocked: promotion is blocked while preview, compare, portal docs, and
  rollback evidence remain inspectable.

Backend-contract notes:

- Needs ordered gates with severity, blocking semantics, evidence ids,
  freshness, owner, and related routes. Gate state must come from backend/report
  contracts, not UI recomposition.

## 11. Rollback Evidence

Purpose: show rollback target evidence without adding rollback mutation.

Layout regions:

- Context header with active deployment and rollback target.
- Two-column comparison: Active deployment and Rollback target.
- Eligibility detail panel.
- Evidence checklist for artifact sets, schema hashes, runtime health, and
  mismatch status.

Key controls:

- Toggle active vs rollback fields.
- Open Compare rollback lane.
- Copy rollback evidence reference.
- Open Deployment Review.

Evidence shown:

- Active: `deploy-c360-staging-20260528-0915`, schema hash
  `sha256:d5f0...a901`, artifact set `artifact-set-c360-20260528-0915`,
  health `healthy`, runtime alignment `matches_active_artifacts`.
- Rollback: `deploy-c360-staging-20260521-1430`, schema hash
  `sha256:af23...9bc1`, artifact set `artifact-set-c360-20260521-1430`,
  eligibility `eligible`.
- Reason: last known-good deployment with matching artifact manifest and no
  open runtime-health mismatch.

Primary actions:

- Open Compare rollback lane.
- Open Deployment Review rollback gate.
- Copy evidence packet.

Empty, error, and blocked states:

- Empty: no rollback target available; explain that no eligible evidence set is
  present.
- Error: rollback evidence unavailable; keep active deployment evidence shown.
- Blocked: if runtime alignment is missing, rollback eligibility becomes
  evidence-blocked rather than assumed.

Backend-contract notes:

- Needs active and rollback deployment ids, schema hashes, artifact set ids,
  health, runtime alignment, eligibility, reason, and report freshness.
  Rollback execution is outside this design slice.

## 12. Portal Schema Docs

Purpose: give consumers portal-safe active and preview schema browsing.

Layout regions:

- Consumer Portal header with workspace, model, environment, and active/preview
  lens only.
- Schema navigation tree focused on `Customer`.
- Field detail panel with availability, deprecation, filter support, and sample
  links.
- Operation-status explanation strip.

Key controls:

- Active/Preview lens toggle.
- Schema search.
- Field select.
- Open sample query.
- Copy field reference or sample handoff.

Evidence shown:

- Active lens shows active `Customer` fields and approved
  `CustomerProfileRead` context.
- Preview lens shows `Customer.lifetimeValue` as preview-only and
  `Customer.legacyTier` as deprecated with replacement `Customer.segment`.
- `Customer.orders` detail shows equality filters only and range filters not
  generated for the current binding.
- Operation status appears as explanation only.

Primary actions:

- Open Portal Sample Query.
- Copy schema field reference.
- Switch between active and preview docs.

Empty, error, and blocked states:

- Empty: no schema docs for selected lens; show model/environment and clear
  lens switch.
- Error: portal schema docs unavailable; no operator controls appear.
- Blocked: if preview docs are unavailable, active docs remain available and
  the preview lens shows endpoint/evidence unavailable.

Backend-contract notes:

- Needs portal-safe schema metadata for active and preview lenses, field
  availability, deprecation text, filter support, sample links, and operation
  status summaries. It must exclude governance controls and source diagnostics.

## 13. Portal Sample Query

Purpose: provide copyable, portal-safe sample query handoff with operation
status context.

Layout regions:

- Consumer Portal context header with active/preview lens.
- Sample metadata panel with sample id, operation name, role/client, and
  status.
- Query text panel.
- Preview-only alternative panel.
- Explanation panel for approval status and production-readiness limitations.

Key controls:

- Copy query.
- Copy variables placeholder.
- Switch active/preview sample.
- Return to Portal Schema Docs.

Evidence shown:

- Sample id `sample-customer-profile-read-preview`.
- `CustomerProfileRead` is `approved`, visible in active and preview.
- Query returns `id`, `name`, `segment`, `legacyTier`, and `orders { id total }`.
- Preview-only alternative `CustomerLifetimeValueRead` shows
  `Customer.lifetimeValue` and `unknown` review status.
- The unknown preview-only operation explains it is not approved for production
  use and provides no approve/reject control.

Primary actions:

- Copy approved sample query.
- Open Portal Schema Docs.
- Copy preview-only alternative with status warning.

Empty, error, and blocked states:

- Empty: no sample query exists for selected field or operation.
- Error: sample metadata unavailable; hide copy controls until query and status
  are loaded together.
- Blocked: unknown/rejected operations can be viewed with explanation, but only
  approved samples are presented as production-safe handoff.

Backend-contract notes:

- Requires sample id, operation name, query text, lens availability,
  operation-status summary, role/client metadata where portal-safe, and
  production-readiness explanation. The portal must not own governance truth.

## Cross-Screen Backend Contract Handoff

The static prototype depends on these backend/report contracts:

- Model inventory summary with readiness, active deployment, preview build,
  report ids, and freshness.
- Model overview summary that preserves separate draft, preview, active, and
  rollback identities.
- Validation diagnostics with ids, severity, source locations, object paths,
  owners, and related evidence.
- Drift reports with snapshot metadata, model/database bindings, expected and
  observed values, severity, and runtime risk.
- Artifact manifests with typed entries, reproducible paths, hashes, statuses,
  artifact set id, and warning links.
- Preview build metadata with endpoint, state, contract-test counts,
  failed/skipped operation ids, reasons, and related evidence.
- Semantic compare output across draft, preview, active, and rollback states.
- Operation registry snapshots with operation, role, client, status, reason,
  and preview/usage linkage.
- Usage analytics facts with typed field/client/operation impact and report
  freshness.
- Deployment review gates with blocking semantics, evidence ids, owner routing,
  and freshness.
- Rollback evidence with active/rollback deployment ids, schema hashes,
  artifact sets, runtime alignment, eligibility, and reason.
- Portal-safe schema and sample-query metadata that exposes operation status as
  context while excluding governance controls.

Open backend-contract questions for UI-PROTOTYPE.4:

- Which API shape should own the joined model overview summary versus the
  individual evidence report payloads?
- Should deployment review gates be returned as a first-class ordered report or
  assembled from typed readiness facts by the UI client?
- What freshness and partial-failure fields are required so the UI can show
  usable partial evidence without inventing readiness state?
