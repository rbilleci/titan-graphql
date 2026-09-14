# Titan GraphQL UI Workflow Map

Status: accepted.

This document completes UI2 from `docs/ui/roadmap.md`. It maps the work the
Titan GraphQL UI must support before screen inventory or visual design. The UI
remains a client of `/admin/graphql`; Git, CI, preview endpoints, and generated
artifact files remain first-class handoff surfaces.

## Workflow Groups

Operator workbench workflows cover model source, validation, artifacts,
previews, governance, deployment, health, and rollback.

Consumer portal workflows cover schema browsing, preview inspection, sample
queries, role-visible capabilities, and operation status without exposing
private operator controls.

Git-first, UI-first, and hybrid variants should converge on the same Titan
records:

- Git-first: source changes begin in a repository or CI job; the UI observes,
  validates, previews, compares, and promotes the resulting records.
- UI-first: a user imports source or creates an inference draft in the UI; the
  normalized source and report JSON must still be exportable for Git review.
- Hybrid: the UI identifies a blocker or review action, and the fix continues
  through Git, CI, or `/admin/graphql` automation.

## Common Signals

The workflows reference these Titan signals from DXR1-DXR12:

- model document source, normalized export metadata, semantic hash, and source
  location or model path
- semantic validation report, issue severity, deployment-blocking flag, and
  deterministic report JSON
- catalog snapshot, inference report, and catalog drift report
- generated artifact set, manifest, SDL, introspection JSON, conformance
  matrix, generated SQL metadata, and artifact hashes
- preview build manifest, endpoint, expiration, candidate artifact set, and
  preview contract-test report
- operation registry, observed operations, approval/rejection status,
  enforcement mode, warnings, and blocks
- usage report facts for operations, fields, roles, clients, environments,
  versions, deprecated fields, unused fields, policy rejections, and slow
  operations
- deployment record, active and previous deployment ids, runtime health, and
  rollback metadata

Backend handoff status from `TG-HANDOFF-M6.1`:

- `/admin/graphql` has an accepted named management mutation surface for the
  current handoff scope.
- Durable import, idempotency, audit, transaction, artifact verification, and
  active deployment evidence come from Titan GAP-005/GAP-006 adapters when the
  durable management store is selected.
- Validation summaries, GraphQL artifact refs, observed operation documents,
  preview URLs, registry presentation state, usage presentation, and UI
  workflow state remain GraphQL product records.
- Application `/graphql` writes, broad CRUD, nested writes, rollback
  orchestration, and product UI behavior are not promoted by the handoff.

## Import Existing YAML

Primary actor: API producer. Handoff actors: developer integrator, reviewer.

Trigger: a producer has a `titan.graphql.yaml` source file from Git, local
authoring, or an existing deployment.

Input: YAML source, workspace id, model id or model name, environment target,
and optional Git ref or PR link.

Output: imported draft record, normalized source metadata, semantic hash, audit
event, and a validation-ready draft.

Happy path:

1. The producer selects a workspace and imports the YAML source.
2. Titan parses the document, stores a model draft, records the source identity,
   and exposes the semantic hash.
3. The UI shows the draft summary, source outline, model paths, and next action
   to validate.
4. The developer integrator exports normalized source or report metadata for
   Git/CI parity.

Blocked path:

- Parse or shape errors stop draft creation and must show line/column when
  available, document section, and a reproducible import command or mutation.
- A duplicate model or environment collision requires choosing whether the
  import updates a draft, creates a new draft, or is cancelled.
- Missing source identity is allowed for UI-first imports, but the UI must mark
  Git handoff as incomplete.

Titan signals consumed: import mutation result, draft record, source metadata,
semantic hash, audit event, and parse diagnostics.

Handoffs: UI import can hand normalized source to Git; Git import can hand draft
and validation records back to UI; CI can reproduce import through
`/admin/graphql`.

## Infer Draft From Database Snapshot

Primary actor: API producer. Handoff actors: platform operator, reviewer.

Trigger: a producer wants a conservative starting model from a known database
catalog snapshot.

Input: workspace, database binding, schema/table selection, catalog snapshot id,
environment, and inference policy defaults.

Output: inferred model draft, inference report, skipped object list,
review-required notes, and source export blocker if canonical YAML export is
not available.

Happy path:

1. The producer selects a catalog snapshot and starts inference.
2. Titan maps tables, columns, primary keys, foreign keys, comments, indexes,
   and nullability into a conservative model draft.
3. The UI shows inferred objects, skipped objects, relation candidates,
   disabled public roots, and review-required policy placeholders.
4. The producer validates, edits, or exports the draft for Git review.

Blocked path:

- Missing snapshot, unsupported database metadata, or unknown key structure
  creates an inference report rather than silently omitting objects.
- If canonical YAML export is unavailable, the UI must label the draft as
  reviewable but not source-roundtrippable.
- Objects inferred without safe policy or root exposure remain private until the
  producer reviews them.

Titan signals consumed: catalog snapshot, inference result, inference report,
draft metadata, skipped object warnings, and export capability status.

Handoffs: database snapshot comes from platform or CI; inferred draft moves to
producer review; normalized source or report JSON moves to Git when available.

## Validate Model And Source Locations

Primary actor: API producer. Handoff actors: developer integrator, reviewer.

Trigger: a draft is imported, inferred, edited, or updated by CI.

Input: draft id, source identity, semantic hash, validation profile, and
environment context.

Output: validation report with pass, warning, or blocking status.

Happy path:

1. The producer runs validation from the draft.
2. Titan checks model shape, references, policies, context filters, relation
   targets, computed required columns, filter/order definitions, and deployment
   blocking severity.
3. The UI groups issues by severity, owner, model path, source location, and
   deployment impact.
4. The producer fixes source or proceeds to drift and artifact review.

Blocked path:

- Blocking issues stop preview and deployment actions.
- Missing source locations must still show model paths and report JSON.
- Unknown issue codes must be visible as unsupported diagnostics rather than
  hidden behind generic copy.

Titan signals consumed: validation report, issue code, severity, source
location, model path, deployment-blocking flag, report hash, and audit metadata.

Handoffs: UI issue links to file/model path; CI gates read the same report JSON;
reviewers use blocking status before approving preview or deployment.

## Inspect Drift

Primary actor: API producer. Handoff actors: platform operator, database owner.

Trigger: a draft or deployed model must be checked against a current database
catalog snapshot.

Input: model draft or deployment id, catalog snapshot id, environment, database
binding, and drift profile.

Output: drift report with missing table, missing column, type/nullability,
primary-key, index, computed-column, and relation-join findings.

Happy path:

1. The producer or operator runs drift inspection.
2. Titan compares the model against the snapshot and renders deterministic
   drift diagnostics.
3. The UI separates deploy-blocking drift from warnings and expected database
   changes.
4. The producer updates model source, or the database owner fixes schema drift.

Blocked path:

- Missing or stale catalog snapshot blocks trustworthy drift conclusions.
- A deployed model with drift must show deployment risk and whether rollback or
  database correction is the safer handoff.
- Drift diagnostics without source location must still name database binding,
  table, column, relation, and model path.

Titan signals consumed: drift report, catalog snapshot metadata, validation
issue severity, affected binding metadata, report hash, and freshness timestamp.

Handoffs: database snapshot comes from platform/CI; drift fixes move to Git or
database change management; deployment decisions consume drift status.

## Generate And Inspect Artifacts

Primary actor: API producer. Handoff actors: developer integrator, reviewer.

Trigger: a draft has acceptable validation and needs generated review material.

Input: draft id, artifact generation profile, validation report hash, drift
report hash, and output options.

Output: artifact set, manifest, artifact hashes, generated SDL, introspection
JSON, conformance matrix, generated SQL metadata, and unsupported capability
notes.

Happy path:

1. The producer generates artifacts for the draft.
2. Titan records an artifact set with stable manifest entries and hashes.
3. The UI shows semantic artifact summary first, then drill-downs for SDL,
   introspection, conformance, and generated SQL metadata.
4. The integrator exports artifacts and report JSON for PR or CI evidence.

Blocked path:

- Generation fails when validation or drift blockers exist.
- Missing optional artifacts must be labeled as disabled, pending, or
  unsupported rather than absent.
- Hash mismatch or stale generation profile blocks preview creation until the
  artifact set is regenerated.

Titan signals consumed: artifact set record, manifest JSON, artifact hashes,
generation profile, validation/drift hash links, unsupported capability rows,
and audit metadata.

Handoffs: generated artifacts move to reviewers, CI, and preview build
creation; Git stores source and may store selected generated evidence.

## Create And Test Preview Build

Primary actor: API producer. Handoff actors: API consumer, reviewer, developer
integrator.

Trigger: an artifact set is ready for behavior review before deployment.

Input: artifact set id, draft id, model id, environment, expiration policy,
operation registry mode, and contract-test operation set.

Output: preview build manifest, preview endpoint, candidate artifact set link,
expiration metadata, and preview contract-test report.

Happy path:

1. The producer creates a preview build from the artifact set.
2. Titan exposes a preview endpoint for the candidate model while keeping stable
   application `/graphql` unchanged.
3. Contract tests run approved operations through the preview route.
4. The UI shows endpoint metadata, expiration, pass/fail/skip counts, response
   hashes, and next review actions.

Blocked path:

- Preview creation is blocked by stale artifacts, validation blockers, missing
  endpoint capability, or unsupported candidate routing.
- Contract-test failures must show operation, role, client, enforcement
  decision, response hash when available, and reproducible request metadata.
- Expired previews must disable test and deployment actions until recreated.

Titan signals consumed: preview manifest, preview endpoint, candidate registry,
expiration, operation registry enforcement decision, contract-test report, and
audit event.

Handoffs: preview endpoint goes to consumers and CI; contract-test report goes
to reviewers; preview candidate feeds draft/preview/deployed comparison.

## Review Operation Registry Changes

Primary actor: reviewer / governance owner. Handoff actors: API consumer,
platform operator.

Trigger: preview or active traffic observes operations, or a schema change
alters approved operation behavior.

Input: model id, environment, role, client, operation hash, observed operation
record, current registry status, and enforcement mode.

Output: approved, rejected, warning, unknown, or blocked operation status with
audit metadata.

Happy path:

1. The reviewer opens the registry review queue scoped by model, environment,
   role, client, and status.
2. The UI shows operation text, hash, first/last seen metadata, schema version,
   preview/deployed compatibility, and enforcement outcome.
3. The reviewer approves or rejects observed operations through
   `/admin/graphql`.
4. Consumers and preview tests see updated operation status.

Blocked path:

- Unknown operations in enforce mode block preview or runtime requests.
- Rejected operations must show GraphQL-shaped rejection details and owner
  routing.
- Registry actions without role/client/environment scope are unsafe and should
  be blocked.

Titan signals consumed: observed operation, operation registry entry, approval
or rejection mutation result, enforcement mode, warning/block decision, usage
counts, and audit metadata.

Handoffs: consumers update operations; reviewers update registry; operators use
registry risk in deployment decisions; CI uses registry records in preview
contract tests.

## Review Usage Analytics And Deprecations

Primary actor: reviewer / governance owner. Handoff actors: API producer, API
consumer, platform operator.

Trigger: a candidate changes fields, relations, policies, operations, or
deprecations, or an operator reviews runtime risk.

Input: model, environment, version, usage report id, changed schema elements,
role/client filters, and freshness expectation.

Output: usage impact summary, deprecated-field usage, unused-field signals,
policy rejections, slow operations, affected roles/clients, and review decision
notes.

Happy path:

1. The reviewer opens usage impact beside schema or deployment review.
2. Titan groups usage by operation, field, role, client, environment, version,
   deprecation, rejection, and latency.
3. The UI highlights active clients affected by removals or policy changes.
4. The producer updates the model, reviewer approves, or consumers receive a
   migration handoff.

Blocked path:

- Stale or missing usage reports must be clearly labeled and cannot be treated
  as proof of no impact.
- Deprecated fields still in active use should block removal unless the review
  owner explicitly accepts the risk.
- Slow or rejected operations must point to operation status and runtime
  environment.

Titan signals consumed: usage report, deprecated-field facts, unused-field
facts, slow-operation facts, policy rejection facts, operation registry status,
schema change summary, and freshness timestamp.

Handoffs: consumers receive deprecation migration notes; producers adjust
schema; operators use usage risk before deployment; CI can attach usage report
JSON to review.

## Browse Active Schema Docs

Primary actor: API consumer. Handoff actors: API producer, developer
integrator.

Trigger: a consumer needs to build against the active deployed API.

Input: model, environment, active deployment, role, client, and optional search
or type filter.

Output: active schema documentation with roots, types, fields, relations,
filters, sorts, pagination, deprecations, visibility, samples, and operation
status.

Happy path:

1. The consumer opens the portal for the active environment.
2. The UI shows role-visible schema docs sourced from active deployment
   metadata and generated artifacts.
3. The consumer searches fields, inspects examples, and copies approved query
   patterns.
4. The integrator uses schema version and operation status in client tooling.

Blocked path:

- No active deployment means the portal must show an explicit unavailable
  state, not stale docs.
- Role-hidden fields should explain visibility without leaking private policy
  internals.
- Samples tied to missing or rejected operations must be marked unusable.

Titan signals consumed: active deployment, artifact set, SDL/introspection,
role/client visibility, operation registry status, usage/deprecation metadata,
and sample query metadata.

Handoffs: consumers copy queries; producers receive schema questions; CI checks
approved operation status.

## Browse Preview Schema Docs

Primary actor: API consumer. Handoff actors: API producer, reviewer.

Trigger: a consumer or reviewer needs to assess a candidate schema before
deployment.

Input: preview build id, role, client, environment, active deployment for
comparison, and optional sample scenario.

Output: preview schema docs, preview endpoint metadata, differences from active
schema, expiration status, and operation compatibility.

Happy path:

1. The consumer opens a preview portal link.
2. The UI shows the candidate schema, preview endpoint, expiration, and
   differences from the active deployment.
3. The consumer tests sample queries and flags adoption concerns.
4. The reviewer uses feedback and contract-test results before approval.

Blocked path:

- Expired previews should still show historical metadata but disable live query
  testing.
- Missing active comparison falls back to standalone preview docs with clear
  caveat text.
- Preview-only fields must not be presented as deployed.

Titan signals consumed: preview manifest, preview endpoint, candidate artifact
set, active deployment, comparison metadata, contract-test report, and registry
status.

Handoffs: preview links move to consumers; feedback moves to producer/reviewer;
CI uses endpoint metadata for candidate tests.

## Try Sample Queries

Primary actor: API consumer. Handoff actors: developer integrator, reviewer.

Trigger: a consumer wants to validate a query against active or preview schema
behavior.

Input: schema version or preview build, role, client, operation text,
variables, selected sample, and endpoint target.

Output: query response, GraphQL-shaped warnings/errors, operation hash,
approval status, and reproducible request metadata.

Happy path:

1. The consumer selects or writes a sample query.
2. The UI runs the query against active or preview endpoint with explicit role,
   client, and environment context.
3. Titan returns data, warnings, policy messages, operation status, and response
   hash when used in preview contract testing.
4. The consumer saves or copies the operation for review and CI.

Blocked path:

- Unknown or rejected operations show registry status and next action.
- Unsupported fields, visibility denial, policy rejection, and validation
  errors use GraphQL-shaped messages and source locations when available.
- Missing preview endpoint or expired preview disables execution while keeping
  query text copyable.

Titan signals consumed: endpoint metadata, operation registry enforcement,
GraphQL errors/warnings, response hash, role/client context, and sample query
metadata.

Handoffs: operations can be submitted for approval; integrators copy request
metadata to CI; reviewers inspect observed operations.

## Compare Draft, Preview, And Deployed Versions

Primary actor: reviewer / governance owner. Handoff actors: API producer,
platform operator, API consumer.

Trigger: a candidate is ready for review, or an incident requires comparison
against the deployed surface.

Input: draft id, preview build id, deployed version, artifact set ids,
operation registry scope, and usage report scope.

Output: semantic comparison of source, generated artifacts, schema, policies,
operations, usage impact, preview behavior, and deployment readiness.

Happy path:

1. The reviewer selects draft, preview, and deployed versions.
2. The UI summarizes added, removed, changed, deprecated, visibility-changed,
   policy-changed, and relation-changed API elements.
3. The UI aligns validation, drift, artifacts, preview tests, operation status,
   and usage impact by affected schema concept.
4. The reviewer approves, requests changes, or blocks deployment.

Blocked path:

- Missing artifact or preview records make comparison partial and must disable
  deployment approval.
- Text-only diffs are insufficient for policy, visibility, relation, and usage
  decisions.
- Stale usage or preview tests must be called out as review gaps.

Titan signals consumed: draft source metadata, artifact sets, preview build,
active deployment, semantic schema diff, validation/drift reports, registry
status, usage report, and health metadata.

Handoffs: reviewer decision goes to producer; accepted candidate goes to
operator; consumer-facing change notes come from comparison outputs.

## Deploy, Observe Health, And Roll Back

Primary actor: platform operator. Handoff actors: reviewer, API producer,
developer integrator.

Trigger: a candidate passes review and needs promotion, or active runtime health
requires intervention.

Input: approved draft or artifact set, preview build, environment, deployment
policy, active deployment, previous deployment, registry enforcement mode, and
rollback target.

Output: deployment record, active runtime state, health report, audit event,
rollback availability, and post-deploy observation summary.

Happy path:

1. The operator verifies validation, drift, artifact, preview, registry, usage,
   and review signals.
2. Titan promotes the approved artifact set or model candidate through a
   deployment mutation.
3. The UI shows active deployment, runtime health, endpoint metadata, and
   post-deploy observation signals.
4. If needed, rollback promotes a known-good deployment with explicit target and
   audit metadata.

Blocked path:

- Deployment is blocked by validation blockers, drift, stale artifacts, failed
  preview tests, rejected operations, missing approval, or unhealthy runtime
  mismatch.
- Rollback is blocked when no trustworthy target exists or the target conflicts
  with current environment constraints.
- Health mismatch must show whether the source, artifacts, deployment record,
  endpoint, or runtime route is out of alignment.

Titan signals consumed: deployment record, previous deployment,
validation/drift/artifact/preview/registry/usage signals, runtime health,
rollback metadata, and audit events.

Handoffs: operator action is auditable through `/admin/graphql`; producer fixes
blocked candidates; integrator reproduces deployment evidence in CI; consumers
see active schema and deprecation effects in the portal.

## Workflow Coverage

- Import, inference, validation, drift, artifacts, preview, operation registry,
  usage, comparison, deployment, health, and rollback belong to the operator
  workbench.
- Active schema docs, preview schema docs, and sample queries belong to the
  consumer portal, with operation status shared from the workbench.
- Every workflow names at least one existing DXR1-DXR12 capability and one
  handoff to Git, CI, preview endpoints, runtime deployment, or report JSON.
- Gaps discovered here should feed `docs/ui/api-contract-matrix.md`, not private
  frontend behavior.
