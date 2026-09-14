# Titan GraphQL UI Screen Inventory

Status: accepted; revised with Hasura and Apollo GraphQL product references.

This document completes UI4 from `docs/ui/roadmap.md`. It names required
screens and panels without prescribing visual design, frontend framework, or
component styling.

Update: after Hasura DDN portal research and Apollo GraphOS synthesis, the
first-slice inventory should lead with API portal and build exploration, backed
by graph-control-plane evidence. Operator Workbench and Model Studio screens
remain useful, but the next prototype should prove that users can understand
and test the generated API surface before drilling into deployment or source
evidence.

## First-Slice Screens

### Portal Build Landing

Area: Portal Build Explorer.

Primary persona: API consumer.

Purpose: orient users around the selected model's active, preview, and previous
API surfaces before they inspect internal evidence.

Core data: model id, model name, workspace, active endpoint, active build or
deployment id, preview endpoint, preview build id, previous/rollback build id,
schema hash, artifact hash, role/client lens, documentation coverage,
permission coverage, operation status summary, usage summary, and freshness.

Actions: switch active/preview/previous build, open schema docs, open
relationship graph, open query explorer, copy endpoint, copy client snippet,
switch contract lens, open graph checks, open operation control, and open
evidence drawer.

Empty state: no active or preview API surface; show import/build prerequisites.

Failure state: schema artifact unavailable, build identity missing, endpoint
unavailable, or role/client lens unavailable.

### Build Selector And API Identity

Area: Portal Build Explorer.

Primary persona: developer integrator.

Purpose: make immutable preview/build identity and applied active API identity
obvious, mirroring Hasura's build-specific Console URL and Project API split.

Core data: build id, build label, source ref, artifact set id, endpoint URL,
applied status, created timestamp, expiration, project/environment endpoint,
previous build list, rollback eligibility, and evidence freshness.

Actions: switch build lens, copy endpoint, copy build id, compare with active,
open CI run, open artifact manifest, and open rollback evidence.

Empty state: no builds for selected model.

Failure state: stale build metadata, expired preview, missing endpoint, or
artifact/build mismatch.

### Schema Documentation Explorer

Area: Portal Build Explorer.

Primary persona: API consumer.

Purpose: browse the generated GraphQL API by root, object type, field,
relationship, filter, sort, role visibility, deprecation, and operation status.

Core data: schema version, selected build, endpoint, roots, object types,
fields, descriptions, relationships, filters, sorts, pagination support,
role/client visibility, deprecations, replacement notes, examples, operation
status, and documentation coverage.

Actions: search schema, switch role/client lens, open field detail, open
relationship graph, open sample query, copy schema snippet, and open evidence
for warnings.

Empty state: no schema artifact for selected build.

Failure state: role/client not authorized, description coverage missing,
schema artifact stale, or selected build unavailable.

### Relationship Graph Explorer

Area: Portal Build Explorer.

Primary persona: API producer.

Purpose: show how roots, object types, relationships, joins, filters,
visibility, and policies connect without making graph visualization the only
way to navigate.

Core data: object types, root fields, relationship edges, join fields,
source/target fields, filter support, sort support, policy/visibility
indicators, drift markers, generated artifact links, and usage counts.

Actions: filter by type/root/policy, focus selected field, open docs detail,
open drift/source evidence, copy graph metadata, and open compare.

Empty state: selected schema has no relationships.

Failure state: relation target missing, join drift, policy visibility unknown,
or graph metadata projection unavailable.

### Query Explorer And Trace

Area: Portal Build Explorer.

Primary persona: API consumer.

Purpose: run or prepare sample queries against active or preview endpoints with
operation status, response context, and trace/evidence handoff.

Core data: query text, variables, headers or role/client context, endpoint
target, build id, operation hash, operation registry status, GraphQL
warnings/errors, response sample or response hash, request id, trace id, query
plan link, latency, models/fields used, and copyable client/CI snippets.

Actions: run query if authorized, copy request, copy client snippet, copy CI
snippet, submit operation for review, open trace, open query plan, and open
related schema fields.

Empty state: no samples for selected schema concept; allow custom query if
execution is authorized.

Failure state: validation error, visibility denial, rejected operation, unknown
operation in enforce mode, expired preview, endpoint unavailable, or trace
unavailable.

### Contract Lens Explorer

Area: Portal Build Explorer.

Primary persona: API consumer.

Purpose: make role/client-specific API surfaces explicit instead of treating
visibility as a hidden filter.

Core data: contract lens id, role/client, selected build, included roots,
included object types, included fields, excluded fields, visibility rationale,
policy references, sample query applicability, downstream compatibility, and
freshness.

Actions: switch lens, compare with full preview or active build, open schema
docs, open sample query, copy contract summary, and open compatibility evidence.

Empty state: no contract lens for selected role/client.

Failure state: policy metadata unavailable, lens stale against schema artifact,
or downstream compatibility unknown.

### Graph Checks And Launch Summary

Area: Graph Control.

Primary persona: reviewer.

Purpose: show whether the selected API change is safe according to validation,
semantic diff, usage, operation impact, contract compatibility, proposal,
launch, and CI evidence.

Core data: graph ref, check run id, schema/artifact hash, semantic diff,
validation status, lint/policy status, operation impact, contract compatibility,
proposal status, launch status, CI run, owners, timestamps, and report links.

Actions: open changed schema concepts, open impacted operations, open contract
compatibility, open CI run, copy reproduce command, download report JSON, and
open launch evidence.

Empty state: no check run for selected build.

Failure state: check stale, CI unavailable, proposal missing, launch blocked,
or contract compatibility unavailable.

### Operation Control Detail

Area: Graph Control.

Primary persona: reviewer.

Purpose: manage observed and persisted operations as client-owned API contracts,
not only as an approval queue.

Core data: operation hash, operation signature, operation name, client name and
version, owner, selected build, contract lens, registry status, safelist/PQL
membership, enforcement mode, usage, field impact, trace links, review reason,
and audit event.

Actions: approve, reject, request owner follow-up, add/remove from safelist,
copy operation manifest entry, open usage, open trace, and open schema fields.

Empty state: no operations for selected schema concept or client.

Failure state: operation manifest missing, safelist state stale, usage
unavailable, or review authorization missing.

### Portal Insights Report

Area: Insights.

Primary persona: platform operator.

Purpose: summarize API health, security/readiness, documentation quality, usage
trends, reliability, and developer/team access patterns.

Core data: authentication status, permission coverage, field-level visibility,
documentation coverage, undocumented fields/commands, unrestricted surfaces,
request rate, p95/p99 latency, error rate, requests per day, deprecated
metadata, deprecated-field usage, project access distribution, subgraph access
distribution if available, and evidence freshness.

Actions: print/export report, filter by build/role/client/environment, open
affected schema concept, open usage detail, open trace list, and open settings
handoff.

Empty state: no insights available for selected build/environment.

Failure state: analytics unavailable, metrics stale, incomplete role/client
coverage, or report permissions insufficient.

### Model List

Area: Models.

Primary persona: platform operator.

Purpose: find models by workspace, environment, owner, active deployment, and
health state.

Core data: model id, model name, workspace, environments, active deployment,
latest draft, latest validation status, latest artifact set, latest drift
status, operation registry mode, usage freshness, and health summary.

Actions: open model overview, import YAML, infer draft, open active portal,
filter by environment, and export list metadata.

Empty state: no models in workspace; offer import or inference if authorized.

Failure state: `/admin/graphql` model list unavailable; show freshness and
reproducible query details.

### Model Overview

Area: Models.

Primary persona: API producer.

Purpose: summarize source, generated, preview, deployment, operation, usage, and
health state for one model.

Core data: model record, active deployment, latest draft, validation report,
drift report, latest artifact set, preview builds, registry summary, usage
summary, and runtime health.

Actions: validate draft, inspect drift, generate artifacts, create preview,
compare versions, open portal, open deployment review, and export evidence.

Empty state: model exists with no draft; offer import or inference.

Failure state: related records missing or stale; show which state is partial.

### Draft Source And Validation

Area: Drafts.

Primary persona: API producer.

Purpose: inspect YAML source, source outline, normalized metadata, semantic
hash, and validation diagnostics.

Core data: draft id, source identity, normalized export state, semantic hash,
validation report, issue list, source locations, model paths, owner routing, and
deployment-blocking flags.

Actions: import/update source, run validation, export normalized source, copy
report JSON, open issue target, and continue to drift or artifacts.

Empty state: draft has no source; show import options.

Failure state: parse failure, missing source locations, unknown issue code, or
stale validation hash.

### Drift Report

Area: Drafts.

Primary persona: API producer.

Purpose: show database/catalog mismatch diagnostics for a draft or deployment.

Core data: drift report id, catalog snapshot metadata, binding, issue severity,
affected table/column/relation/index, source model path, blocking status, and
freshness.

Actions: run drift check, filter by owner, copy report JSON, open model path,
and mark expected database handoff.

Empty state: no drift report; show required snapshot input.

Failure state: missing snapshot, stale snapshot, unsupported catalog metadata,
or drift checker unavailable.

### Artifact Manifest And Artifact Detail

Area: Artifacts.

Primary persona: developer integrator.

Purpose: inspect generated artifact sets and individual artifact content before
preview or deployment.

Core data: artifact set id, draft id, generation profile, manifest entries,
hashes, validation/drift hash links, SDL, introspection JSON, conformance
matrix, generated SQL metadata, unsupported capability rows, and output path.

Actions: generate artifacts, open artifact detail, compare hashes, export
manifest, copy artifact content, and create preview build.

Empty state: no artifact set for draft; show generation preconditions.

Failure state: stale source/report hash, generation failure, missing optional
artifact, unsupported artifact kind, or hash mismatch.

### Preview Build Detail

Area: Previews.

Primary persona: API producer.

Purpose: inspect preview candidate endpoint, manifest, expiration, contract
tests, and preview portal links.

Core data: preview id, draft id, artifact set id, endpoint, environment,
expiration, candidate registration, status, contract-test report, pass/fail/skip
counts, response hashes, and operation enforcement outcome.

Actions: create preview, rerun contract tests, open preview portal, open query
runner, compare to deployed, copy endpoint, and expire preview.

Empty state: no preview; show artifact set preconditions.

Failure state: expired preview, candidate route unavailable, failed contract
test, rejected operation, unknown operation in enforce mode, or missing
endpoint metadata.

### Operation Registry Review

Area: Operations.

Primary persona: reviewer / governance owner.

Purpose: review observed, unknown, approved, rejected, warning, and blocked
operations by model, environment, role, and client.

Core data: operation hash, operation name, operation text, status, role, client,
environment, first/last seen, enforcement mode, warning/block reason, usage
counts, preview/deployed compatibility, and audit history.

Actions: approve observed operation, reject observed operation, filter queue,
open sample query, compare operation against schema version, and export review
evidence.

Empty state: no observed or reviewable operations in selected scope.

Failure state: missing role/client scope, unavailable mutation, conflicting
status, stale usage, or enforcement decision mismatch.

### Usage Analytics Summary

Area: Usage.

Primary persona: reviewer / governance owner.

Purpose: summarize runtime usage impact for schema changes, deprecations,
policies, clients, and operations.

Core data: usage report id, freshness, operation facts, field facts, role facts,
client facts, environment/version facts, deprecated-field use, unused fields,
policy rejections, slow operations, and affected schema concepts.

Actions: filter by client/role/version, open affected field or operation, export
report JSON, attach to review, and create producer/consumer handoff notes.

Empty state: no usage report; explain that absence is not proof of no usage.

Failure state: stale report, incomplete environment coverage, missing
operation-field link, or unavailable analytics source.

### Consumer Schema Docs

Area: Portal.

Primary persona: API consumer.

Purpose: legacy name for the Schema Documentation Explorer. New work should use
Schema Documentation Explorer as the first-slice screen name.

Core data: schema version, active deployment or preview build, roots, types,
fields, relations, filters, sorts, pagination, role/client visibility,
deprecations, operation status, sample links, and endpoint metadata.

Actions: search schema, open type/field, copy sample, try query, switch active
or preview version, and copy endpoint metadata.

Empty state: no active deployment or no shared preview.

Failure state: schema artifact unavailable, role/client not authorized, preview
expired, or stale docs.

### Sample Query Detail

Area: Portal.

Primary persona: API consumer.

Purpose: legacy name for Query Explorer And Trace. New work should use Query
Explorer And Trace as the first-slice screen name.

Core data: query text, variables, endpoint target, role/client context,
operation hash, approval status, GraphQL warnings/errors, response hash,
sample owner, and schema version.

Actions: run query, copy request, submit for operation review, open related
schema fields, switch endpoint, and export CI snippet.

Empty state: no samples for selected schema concept; allow custom query when
execution is authorized.

Failure state: validation error, visibility denial, rejected operation, unknown
operation in enforce mode, expired preview, or endpoint unavailable.

### Relationship Graph Metadata View

Area: Models / Portal.

Primary persona: API producer.

Purpose: legacy name for Relationship Graph Explorer. New work should use
Relationship Graph Explorer as the first-slice screen name.

Core data: object types, roots, relations, join fields, source/target types,
filters, sorts, policies, context filters, visibility, drift indicators, and
artifact links.

Actions: filter by type/root/policy, open source field, open relation
diagnostic, copy metadata, and include in prototype scenarios.

Empty state: no relations or graph metadata for selected model.

Failure state: relation target missing, drift in join field, policy visibility
unknown, or graph metadata API gap.

## Additional Workbench Screens

### Compare Draft / Preview / Deployed

Area: Models / Previews / Deployments.

Primary persona: reviewer / governance owner.

Purpose: review semantic differences across source, artifact, preview, and
runtime deployment state.

Core data: selected draft, preview, deployment, schema diff, validation/drift
status, artifact hashes, contract-test report, operation status, usage impact,
policy/visibility changes, and deployment readiness.

Actions: approve review, request changes, open affected artifact, open usage
impact, open registry queue, and continue to deployment review.

Empty state: fewer than two comparable versions.

Failure state: missing artifact set, stale preview, unavailable active
deployment, stale usage report, or partial diff metadata.

### Deployment Review

Area: Deployments.

Primary persona: platform operator.

Purpose: decide whether a candidate can be promoted.

Core data: candidate artifact set, draft, preview, validation/drift gates,
contract tests, registry status, usage impact, active deployment, rollback
target, runtime health, and audit requirements.

Actions: deploy candidate, block with reason, open compare, open rollback
target, and export deployment evidence.

Empty state: no deployable candidate.

Failure state: failed gate, stale candidate, missing approval, runtime mismatch,
or deployment mutation unavailable.

### Runtime Health Detail

Area: Runtime Health.

Primary persona: platform operator.

Purpose: inspect live alignment between active deployment, endpoint, artifacts,
schema hash, registry mode, and runtime health.

Core data: environment, endpoint, active deployment, schema hash, artifact hash,
health status, mismatch details, last checked timestamp, and rollback
availability.

Actions: refresh health, open deployment, open rollback, copy incident summary,
and hand off to producer or platform owner.

Empty state: no deployed runtime in environment.

Failure state: stale health, endpoint unreachable, schema mismatch, artifact
mismatch, registry mismatch, or rollback unavailable.

### Rollback Detail

Area: Deployments.

Primary persona: platform operator.

Purpose: choose and execute rollback to a known-good deployment.

Core data: current deployment, rollback target, target artifact set, target
schema hash, target health, usage/operation implications, and audit metadata.

Actions: roll back, compare target, inspect target portal, and export rollback
record.

Empty state: no rollback target.

Failure state: target unavailable, target unhealthy, incompatible environment,
or missing authorization.

## Deferred Screens

- Rich YAML editing with comment preservation.
- Visual relationship graph layout.
- Full governance policy editor.
- Multi-environment release calendar.
- Historical incident timeline.
- Advanced generated SQL explorer.
- Bulk operation registry import/export.
- Usage anomaly investigation workspace.

These are deferred because the first slice should prove the core lifecycle and
data contracts before expanding into specialized editing or visualization.

## First-Slice Acceptance

The first-slice set proves:

- model browsing and source import
- validation and drift diagnosis
- artifact inspection and preview creation
- operation and usage review
- active and preview schema consumption
- query trial and operation status
- deployment readiness and runtime health visibility

No listed screen requires private backend access outside `/admin/graphql`.
Screens may link to Git, CI, report JSON, generated files, or preview endpoints,
but they must not depend on frontend-private state as source truth.
