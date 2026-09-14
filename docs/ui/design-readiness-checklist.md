# Titan GraphQL UI Design Readiness Checklist

Status: accepted; revised with Hasura and Apollo GraphQL product references.

This document completes the readiness checklist portion of UI7 from
`docs/ui/roadmap.md`. It defines the gate before visual design and the handoff
into DXR13.

## Artifact Checklist

- [x] UI0 roadmap exists: `docs/ui/roadmap.md`.
- [x] UI1 personas exist: `docs/ui/personas.md`.
- [x] UI2 workflows exist: `docs/ui/workflows.md`.
- [x] UI3 information architecture exists:
  `docs/ui/information-architecture.md`.
- [x] UI4 screen inventory exists: `docs/ui/screen-inventory.md`.
- [x] UI5 state model exists: `docs/ui/state-model.md`.
- [x] UI5 content model exists: `docs/ui/content-model.md`.
- [x] UI6 API contract matrix exists: `docs/ui/api-contract-matrix.md`.
- [x] UI7 prototype brief exists: `docs/ui/prototype-brief.md`.
- [x] UI7 readiness checklist exists:
  `docs/ui/design-readiness-checklist.md`.
- [x] UI-PROTOTYPE.1 scenario packet exists:
  `docs/ui/hybrid-prototype-scenario-packet.md`.
- [x] UI-PROTOTYPE.2 navigation and screen flow exists:
  `docs/ui/hybrid-navigation-screen-flow.md`.
- [x] UI-PROTOTYPE.3 static prototype screen spec exists:
  `docs/ui/hybrid-static-prototype-screen-spec.md`.
- [x] UI-PROTOTYPE.4 prototype review handoff exists in this checklist.
- [x] Hasura portal capability study exists:
  `docs/ui/hasura-portal-capability-study.md`.
- [x] Apollo GraphQL product synthesis exists:
  `docs/ui/apollo-graphql-product-synthesis.md`.

## Acceptance Checklist

- [x] Personas are accepted.
- [x] Workflows are accepted.
- [x] Information architecture is accepted.
- [x] First-slice screen inventory is accepted.
- [x] Lifecycle state vocabulary is accepted.
- [x] Content and diagnostics language is accepted.
- [x] API/data contract matrix is accepted.
- [x] Prototype brief is accepted.
- [x] Shared prototype scenarios are accepted.
- [x] Prototype direction is selected or combined.
- [x] Hybrid scenario packet is accepted for design review.
- [x] Hybrid navigation and screen flow is accepted for design review.
- [x] Hybrid static prototype screen spec is accepted for backend-contract
  handoff.
- [x] Prototype review has evaluated layout clarity, navigation continuity,
  evidence visibility, lifecycle separation, portal safety, and backend
  contract completeness.
- [x] Unresolved product questions have owners or decisions.
- [x] DXR13 is updated to reflect the selected UI direction.

## Accepted Direction

Use a Portal Build Explorer with a Graph Control Plane behind it for the next
prototype and DXR13 metadata lane.

The first-slice UI should make active/preview/previous API surfaces obvious
from a portal landing page while keeping producer source, diagnostic, drift,
artifact, deployment, rollback, operation-governance, Git, and CI evidence
available as drill-down context. The portal is no longer secondary. It is the
primary frame for understanding what Titan generated and whether consumers,
producers, integrators, reviewers, and operators can trust it.

This adapts Hasura DDN console and portal capabilities:

- metadata-backed API documentation and semantic schema exploration
- build-specific API identity with preview and applied active endpoints
- GraphiQL/Supergraph Explorer-style query tryout and sample handoff
- relationship/ERD graph views
- usage analytics, reliability metrics, trace/query-plan evidence, and platform
  health reports
- security, permission, and documentation coverage as API-quality signals
- connector/data-source capability support as API-shape evidence

It also adapts Apollo GraphOS product concepts:

- graph variants/graph refs for active, preview, staging, production, previous,
  and contract-specific API surfaces
- schema registry, schema lineage, and schema checks as first-class evidence
- proposals and launches for safe schema-change delivery
- contracts as role/client-specific schema surfaces, not only UI filters
- persisted query lists and safelisting as the stronger model for operation
  control
- client awareness, field usage, operation metrics, traces, and query plans as
  change-safety evidence
- Rover/CI parity for every UI-visible state

Combined verdict: Hasura provides the portal/build-explorer shape; Apollo
provides the graph-control-plane lifecycle. Titan should synthesize both:
start from the generated API surface, then expose checks, contracts, operation
control, usage, traces, launches, and reproducible CI handoff where they help
the user decide whether the API is understandable, safe, and adoptable.

The previous Operator Workbench + Model Studio hybrid remains a supporting
evidence layer, not the default frame.

## Hasura Portal Review

Review date: 2026-06-03.

Reviewed sources:

- Hasura DDN project tutorial and build-specific Console flow.
- Hasura DDN launch overview.
- Hasura DDN preview-deployment article.
- Hasura DDN platform dashboard documentation.
- Hasura DDN traces and metrics documentation.
- Hasura Connector Hub.
- PromptQL product and billing pages.

Verdict: the previous accepted prototype direction should be reopened. The
clickable prototype branch produced useful negative signal, but it looked too
much like a generic operator/admin dashboard. Hasura's portal suggests a better
center: build-specific API exploration with evidence-backed health, governance,
and reproducibility.

Product/design consequences:

- Lead with generated API comprehension, not deployment readiness.
- Treat active, preview, and previous/rollback build identity as the main
  lifecycle selector.
- Replace "Consumer Schema Docs" and "Sample Query Detail" as secondary screens
  with a first-class Portal Build Landing, Schema Documentation Explorer,
  Relationship Graph Explorer, and Query Explorer And Trace.
- Keep deployment readiness, rollback, operation approval/rejection, source
  diagnostics, drift, artifact hashes, Git, and CI in an evidence drawer or
  review lane.
- Add portal health signals: authentication, permissions, documentation
  coverage, deprecated metadata, request rate, latency, error rate, usage,
  traces, and query plans.
- Preserve portal safety: consumers can browse, run/copy samples, see operation
  status, and inspect allowed evidence, but cannot access private governance or
  deployment controls.

## Apollo GraphOS Review

Review date: 2026-06-03.

Reviewed sources:

- Apollo GraphOS schema management, variants, checks, proposals, and launches.
- Apollo GraphOS contracts.
- Apollo persisted query lists and safelisting.
- Apollo Insights: operation metrics, field usage, traces, and query plans.
- Apollo Rover CLI and CI-oriented schema workflows.

Verdict: keep the Hasura-inspired Portal Build Explorer, but give it
Apollo-inspired lifecycle objects. The old "operation registry review" framing
is too narrow; Titan needs an operation-control lifecycle that can show
observed, proposed, approved, rejected, unknown, warning, blocked, safelisted,
and client-owned operations at the point of schema/query exploration.

Product/design consequences:

- Treat active, preview, previous, staging, production, and contract lenses as
  graph variants or graph refs.
- Add Graph Checks as a first-slice evidence frame: validation, semantic diff,
  operation impact, lint/policy checks, contract compatibility, and CI status.
- Treat role/client filtering as a Contract Lens that describes the exact API
  surface exposed to a consumer group.
- Add Proposal/Launch evidence for change delivery; deployment readiness is a
  supporting gate inside a launch, not always the default screen.
- Replace a generic operation queue with persisted operation control:
  operation hash, operation signature, client ownership, usage, safelist/PQL
  status, enforcement mode, and review decision.
- Put client awareness, field usage, operation metrics, traces, and query plans
  beside schema docs and sample queries instead of hiding them in a separate
  analytics area.
- Require Rover/CI parity affordances: copy reproduce command, open CI run,
  download report JSON, copy evidence bundle, and copy operation manifest.

## Historical Prototype Review

Review date: 2026-06-03.

Reviewed artifacts:

- `docs/ui/prototype-brief.md`
- `docs/ui/hybrid-prototype-scenario-packet.md`
- `docs/ui/hybrid-navigation-screen-flow.md`
- `docs/ui/hybrid-static-prototype-screen-spec.md`
- `docs/ui/api-contract-matrix.md`

Verdict at the time: accepted as a medium-fidelity static prototype
specification and backend-contract handoff for the Operator Workbench + Model
Studio hybrid.

Supersession: this review is now historical. The later clickable prototype
review and Hasura portal study reopened the direction. Use this section only for
evidence patterns that still apply behind the Portal Build Explorer.

Product/design evaluation:

- Layout clarity: accepted. The static spec keeps the model overview as the
  operating center and gives each detail frame a clear purpose, context header,
  evidence footer, primary actions, and empty/error/blocked states. The three
  overview columns map cleanly to readiness, lifecycle, and risk without
  forcing users to read source before deciding where to go.
- Navigation continuity: accepted. The persistent navigation, model context,
  environment context, and version lens preserve continuity between Models,
  Operations, Usage, Deployments, Model Studio details, and Consumer Portal
  frames. Cross-links route blockers and warnings to owner workflows rather
  than isolating evidence in disconnected report views.
- Evidence visibility: accepted. Validation, drift, artifact, contract-test,
  usage, active deployment, rollback, Git, and CI references stay visible as
  named evidence instead of presentation-only state. The spec also preserves
  partial evidence behavior so loaded cards remain usable when another report
  source is unavailable.
- Draft/preview/active/rollback separation: accepted. The shared chrome,
  lifecycle summary, Compare lanes, Deployment Review, and Rollback Evidence
  frames keep draft source, preview endpoint/artifact state, active deployment,
  and rollback target as separate identities. Promotion and rollback remain
  evidence reviews, not executable actions in this slice.
- Portal safety: accepted. Portal Schema Docs and Portal Sample Query expose
  active/preview browsing, deprecation copy, filter support, sample query
  handoff, and operation-status context without source diagnostics,
  approval/rejection controls, deployment controls, or rollback controls.
- Backend-contract handoff completeness: accepted with open API-shape
  questions. The screen spec names per-frame contract needs and a cross-screen
  handoff; DXR13 can now implement read/projection contracts without guessing
  which UI evidence is authoritative.

Review conclusion:

- The design packet is complete enough to pause the UI prototype loop at
  UI-PROTOTYPE.4.
- Production frontend implementation should remain blocked until DXR13/DXR14
  contract slices provide fixtures or explicitly scoped prototype data.
- Backend behavior, runtime behavior, deployment mutation, rollback mutation,
  generated assets, screenshots, and external dependencies remain out of scope
  for this design review.

## First-Slice Readiness

The first functional UI slice is ready for design when it can cover:

- browse models and open a portal build landing
- switch active, preview, previous/rollback, and contract build lenses
- understand endpoint identity and build identity
- browse role/client-visible schema docs
- inspect relationship graph metadata
- run or copy sample queries with role/client context
- view operation-control status beside sample queries
- inspect graph checks, contract compatibility, and launch/proposal evidence
- inspect persisted operation/safelist state for a query or client
- inspect query response/errors, trace id, query-plan link, latency, and
  fields/models used
- review auth, permission, documentation, usage, reliability, deprecated
  metadata, and team/access coverage
- import YAML
- infer draft from database snapshot
- validate source and inspect diagnostics
- inspect drift
- generate and inspect artifacts
- create and inspect preview build
- browse active schema docs
- browse preview schema docs
- run or copy sample queries
- review operation registry changes
- review usage analytics and deprecations
- compare draft, preview, and deployed versions
- inspect deployment readiness and runtime health
- understand rollback availability

## Backend Contract Readiness

Before frontend implementation, DXR13 or a revised backend lane should provide
or explicitly defer:

- portal metadata projection
- graph/variant metadata projection
- contract lens metadata projection
- graph checks and launch/proposal evidence projections
- persisted operation list / safelist metadata
- sample query metadata
- relationship graph metadata
- gate summary projection
- semantic comparison projection
- runtime health projection
- artifact content/reference access
- usage-to-schema linking
- deployment promotion mutation
- rollback mutation and eligibility query
- preview lifecycle and rerun contract-test mutation if not already public
- portal-safe query execution or direct endpoint execution metadata
- CI/Rover-style reproduce command and evidence bundle metadata

The accepted static prototype narrows those needs into two backend handoff
lanes:

DXR13 read/projection contracts:

- Provide a portal build landing projection with model, active build, preview
  build, previous/rollback build, endpoints, schema/artifact hashes, freshness,
  role/client lens, and API health summary.
- Provide graph-control metadata with graph refs/variants for active, preview,
  staging, production, previous, rollback, and contract-specific surfaces.
- Provide a model inventory summary and model overview projection with
  separate draft, preview, active deployment, and rollback identities.
- Provide graph checks, contract compatibility, proposal, and launch evidence
  with ids, status, freshness, changed schema concepts, operation impact, and
  CI links.
- Provide typed validation, drift, artifact manifest, preview contract-test,
  operation registry, usage impact, deployment-readiness, runtime-health, and
  rollback-eligibility evidence envelopes with ids, hashes, freshness, owners,
  and related routes.
- Provide semantic comparison output across draft, preview, active, and
  rollback lanes. The UI should not compute lifecycle semantics from raw SDL.
- Provide portal-safe schema docs and sample query metadata for active and
  preview lenses, including deprecation text, filter support, sample links, and
  operation-status summaries.
- Provide contract-lens metadata for role/client-specific schema surfaces:
  included/excluded types and fields, policy rationale, ownership, and
  downstream compatibility state.
- Provide persisted operation list/safelist metadata with operation hashes,
  signatures, clients, owners, enforcement mode, usage, status, and manifest
  references.
- Provide relationship graph metadata suitable for selected type/field/root
  exploration, not only a global graph blob.
- Provide query-explorer evidence envelopes: operation hash/status, response
  hash or sample response, trace id, query-plan link, latency, models/fields
  used, warnings/errors, safelist status, and copyable client/CI snippets.
- Provide portal insights projections for auth status, permission coverage,
  documentation coverage, usage trends, reliability metrics, deprecated
  metadata, trace availability, and access distribution where available.
- Provide conformance fixtures for the accepted `customer360` scenario so
  frontend implementation can verify partial evidence, blocked readiness,
  preview availability, portal safety, and lifecycle separation.

DXR14 mutation and operational contracts:

- Keep promotion, rollback, operation approval/rejection, preview rerun, and
  portal-safe query execution as explicit backend-owned contracts with audit
  and authorization envelopes.
- Deployment promotion needs ordered gate evidence, freshness policy results,
  blocking semantics, owner routing, and an audit-ready evidence packet.
- Rollback needs eligibility, target selection, runtime alignment, freshness,
  mismatch reasons, and audit result fields before a UI action can be enabled.
- Operation governance mutations need reviewer authorization, schema-version
  compatibility facts, persisted-operation/safelist state, rejection reason
  taxonomy, client ownership, and consumer-safe status messages.
- Portal query execution needs a decision between `/admin/graphql` proxy
  metadata and direct preview/active endpoint handoff, including request
  evidence and operation hash capture.

## Design Evaluation Rubric

Use the prototype scenarios to score each direction:

- Can the user identify source truth for each state?
- Can the user tell draft, preview, and deployed versions apart?
- Can warnings and blockers be routed to the right owner?
- Can consumers browse and test without seeing private controls?
- Can reviewers see operations and usage beside schema changes?
- Can operators decide deploy or rollback with evidence?
- Can integrators reproduce UI-visible state in Git or CI?
- Does the design avoid wizard-only or UI-only model semantics?

## Open Product Questions

Resolved first-slice decisions:

- Prioritize the Portal Build Explorer. Deployment confidence and producer
  review flow should be useful and safe, but secondary to API comprehension,
  active/preview build identity, schema exploration, query handoff, and portal
  insights.
- Start with import, inspect, validate, preview, and export. Full in-browser
  YAML editing can wait until source-location, diff, and validation loops are
  stable.
- Treat sample query execution as copy/run metadata plus a portal-safe execution
  handoff first. Embedded execution can follow if `/admin/graphql` exposes the
  required endpoint and policy metadata cleanly.
- Show operation-control status at point of use in schema docs and query
  explorer, with a dedicated operation-control detail surface for reviewer
  decisions, safelisting, client ownership, and audit.
- Require usage freshness to be visible on deployment review, but make strict
  freshness gates configurable by environment or policy mode.
- Keep deployment promotion and rollback mutations as backend-first DXR14
  behavior unless DXR13 exposes eligibility and evidence envelopes cleanly
  enough for a read-only first UI slice.
- Keep generated SQL metadata behind artifact detail drill-downs. Routine
  artifact review should focus on manifest status, SDL, introspection,
  conformance, hashes, and reproducibility.
- Treat relationship graph metadata as a detail/prototype surface, not primary
  navigation, until real model complexity proves it should move up.

Remaining backend-contract questions:

- Should the joined model overview be one first-class projection, or should the
  client compose it from individual typed report payloads plus a small summary?
- Should deployment readiness gates be returned as an ordered backend report,
  or as typed facts that include enough ordering and blocking metadata for the
  UI to render consistently?
- Which freshness fields are required for partial evidence so one stale report
  blocks only the relevant gate rather than the whole model view?
- Which role/client and operation-status fields are safe to expose in Consumer
  Portal while still helping consumers understand active vs preview readiness?
- Should artifact content be available inline, by signed/reference access, or
  only through reproducible generated-file exports?
- Which semantic compare fields are required for DXR13 fixtures, and which can
  wait for broader production implementation?

## DXR13 Handoff

DXR13 should be reframed as UI-selected backend support, not generic portal
metadata. Recommended structure:

- DXR13.0: accept UI planning packet and prototype decision.
- DXR13.1: add portal metadata projection for first-slice schema docs.
- DXR13.2: add sample query metadata and operation-status links.
- DXR13.3: add relationship graph metadata projection.
- DXR13.4: add gate summary and semantic comparison projections.
- DXR13.5: add graph checks, contract lens, persisted operation, proposal, and
  launch evidence projections.
- DXR13.6: add runtime health, deployment readiness, and rollback eligibility
  metadata.
- DXR13.7: add UI data-contract conformance fixtures.

If visual design chooses a narrower first slice, collapse DXR13 to only the
metadata required by that slice and defer the rest.

## Current Readiness Verdict

Planning packet: accepted.

Acceptance state: reopened by the maintainers on 2026-06-03 after reviewing the
clickable prototype variants.

Visual design state: paused. The previous static prototype review is retained
as historical evidence, but the next prototype should be a Hasura-informed
Portal Build Explorer with Apollo-informed graph-control-plane evidence rather
than an Operator Workbench + Model Studio hybrid.

Implementation state: backend metadata work should not resume from the old
DXR13 framing. DXR13 should be revised around portal build identity, schema
exploration, relationship graph metadata, graph checks, contract lenses,
persisted operation control, query explorer evidence, portal insights, and then
supporting source/artifact/deployment evidence.
