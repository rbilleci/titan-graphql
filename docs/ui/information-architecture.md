# Titan GraphQL UI Information Architecture

Status: accepted; revised with Hasura and Apollo GraphQL product references.

This document completes UI3 from `docs/ui/roadmap.md`. It defines the product
structure for the Titan GraphQL UI before visual design. The IA separates source
truth, generated artifacts, preview/runtime state, governance, usage, and
portal surfaces.

Update: after reviewing Hasura DDN console/portal capabilities and Apollo
GraphOS product concepts, the UI center of gravity should shift from Operator
Workbench + Model Studio to a Portal Build Explorer backed by a Graph Control
Plane. Operators, producers, reviewers, and integrators still need evidence,
but the first-slice product should begin with the generated API surface:
schema docs, relationship graph, sample query execution handoff, build
identity, endpoint identity, contract lenses, role/client visibility, usage,
checks, launches, and trace evidence.

## Top-Level Areas

### Portal Build Explorer

Purpose: the primary home for understanding the active, preview, and previous
Titan GraphQL API surfaces.

Primary objects: model, active deployment endpoint, preview endpoint, build or
artifact identity, schema docs, roots, object types, fields, relationships,
filters, sorts, descriptions, deprecations, operation status, sample queries,
contract lenses, role/client visibility, usage facts, trace facts, and evidence
links.

Primary personas: API consumer, API producer, developer integrator.

Cross-links:

- selected model and workspace
- active, preview, and rollback/previous build identities
- relationship graph metadata
- sample query explorer
- contract lens explorer
- graph checks, proposal, and launch evidence
- source diagnostics, drift, artifact, contract-test, Git, and CI evidence
- usage, metrics, and trace details scoped to selected schema concepts

### Graph Control Plane

Purpose: the lifecycle and governance layer behind the portal, adapting Apollo
GraphOS ideas to Titan-native metadata.

Primary objects: graph ref or variant, schema registry entry, schema lineage,
schema check run, semantic diff, contract lens, proposal, launch, persisted
operation list, safelist state, client awareness, operation metrics, field
usage, trace, query plan, CI run, and evidence bundle.

Primary personas: reviewer, API producer, developer integrator, platform
operator.

Cross-links:

- portal build explorer and selected schema concept
- active, preview, previous, staging, production, and contract-specific API
  surfaces
- operation control detail and persisted operation manifests
- proposal/launch evidence and CI run
- model source, generated artifacts, runtime health, and usage reports

### Models

Purpose: the stable home for Titan GraphQL models across workspaces and
environments.

Primary objects: workspace, model, active deployment, latest draft, latest
validation report, latest artifact set, latest drift report, owner metadata,
and environment bindings.

Primary personas: API producer, platform operator.

Cross-links:

- latest draft and source
- active and previous deployments
- latest artifacts and reports
- operation registry and usage scoped to the model
- portal build explorer for active, preview, and previous schema surfaces

### Drafts

Purpose: source authoring and review before generated artifacts or preview.

Primary objects: model draft, source document, semantic hash, source identity,
normalized export metadata, validation report, inference report, and source
locations.

Primary personas: API producer, developer integrator.

Cross-links:

- parent model and workspace
- validation and drift reports
- generated artifact sets
- Git ref, PR, or exported normalized source
- compare view against preview and deployed versions

### Previews

Purpose: candidate runtime behavior before deployment.

Primary objects: preview build, preview manifest, preview endpoint, expiration,
candidate artifact set, candidate model, contract-test report, and operation
registry mode.

Primary personas: API producer, API consumer, reviewer.

Cross-links:

- source draft and artifact set
- preview portal schema docs
- sample query runner
- contract-test report
- operation registry review
- comparison against active deployment

### Deployments

Purpose: promotion, active runtime state, deployment history, and rollback.

Primary objects: deployment, active deployment, previous deployment, environment,
artifact set, schema hash, deployed-at timestamp, health state, rollback target,
and audit records.

Primary personas: platform operator, reviewer.

Cross-links:

- model, draft, artifact set, and preview build used for deployment
- runtime health
- active schema portal
- operation registry enforcement
- usage report for deployed version
- rollback target detail

### Artifacts

Purpose: inspect generated material before preview or deployment.

Primary objects: artifact set, manifest, artifact entry, generated SDL,
introspection JSON, conformance matrix, generated SQL metadata, hashes, and
generation profile.

Primary personas: API producer, developer integrator, reviewer.

Cross-links:

- source draft and validation/drift reports
- preview builds created from the artifact set
- compare view
- export/reproducibility paths for Git and CI

### Operations

Purpose: governance for observed, persisted, safelisted, approved, rejected,
unknown, warning, and blocked operations.

Primary objects: operation registry, observed operation, persisted operation
list, safelist membership, operation hash, operation name, role, client,
environment, status, enforcement mode, warning or rejection reason, and audit
event.

Primary personas: reviewer, API consumer, platform operator.

Cross-links:

- schema docs and sample query detail
- preview contract-test report
- usage analytics
- deployment readiness

### Usage

Purpose: review runtime impact, deprecation risk, policy rejections, unused
fields, slow operations, and version adoption.

Primary objects: usage report, operation facts, field facts, role facts, client
facts, environment facts, version facts, deprecated-field facts, unused-field
facts, policy-rejection facts, and slow-operation facts.

Primary personas: reviewer, platform operator, API producer.

Cross-links:

- model, deployment, operations, schema fields, deprecations, and comparison
  view
- exportable usage report JSON for review evidence

### Portal

Purpose: consumer-facing schema documentation, preview docs, samples, query
tryout, build identity, and API onboarding.

Primary objects: active schema docs, preview schema docs, type, field, root,
relation, filter, sort, sample query, role/client visibility, deprecation, and
operation status.

Primary personas: API consumer, developer integrator.

Cross-links:

- active deployment, preview build, or rollback/previous build
- operation registry status
- sample query detail
- change notes from draft/preview/deployed comparison
- query trace and performance evidence where available

### Runtime Health

Purpose: operational readout for active endpoint health, mismatch, drift from
deployment record, and rollback readiness.

Primary objects: runtime health record, endpoint, active deployment, schema
hash, artifact hash, registry mode, freshness, mismatch detail, and rollback
availability.

Primary personas: platform operator.

Cross-links:

- active deployment
- deployment history and rollback target
- drift and validation reports
- operations and usage impact

### Insights

Purpose: summarize portal health and evidence coverage using the same product
health frame Hasura applies to platform reports.

Primary objects: authentication status, role/client visibility coverage,
permission coverage, documentation coverage, deprecated metadata, request rate,
request latency, error rate, usage by operation/field/client, query traces,
query plans, connector or database binding health, and subgraph/team access
metadata when applicable.

Primary personas: platform operator, reviewer, developer integrator.

Cross-links:

- portal build explorer and selected schema concept
- operation registry, usage reports, and trace details
- model source, artifact, and deployment evidence
- settings and role/client catalog

### Settings

Purpose: workspace/environment configuration that scopes models, roles, clients,
database bindings, authorization, and integration handoffs.

Primary objects: workspace, environment, database binding, role/client catalog,
CI integration metadata, Git repository metadata, and authorization policy.

Primary personas: platform operator, developer integrator.

Cross-links:

- models scoped by workspace/environment
- import/inference defaults
- role/client visibility in portal and operations

## Object Naming

Use stable product names that match `/admin/graphql` concepts where possible:

- Workspace: administrative boundary for models and environments.
- Model: named Titan GraphQL API model.
- Draft: source candidate before deployment.
- Validation Report: semantic source diagnostics.
- Drift Report: database/catalog mismatch diagnostics.
- Artifact Set: generated material tied to a draft and hashes.
- Preview Build: temporary candidate endpoint for review and testing.
- Deployment: active or historical runtime promotion record.
- Operation Registry: approved/rejected/unknown operation policy.
- Observed Operation: learned operation fact from traffic or preview tests.
- Usage Report: analytics fact set for runtime behavior and impact review.
- Portal Schema: consumer-visible schema documentation for active or preview
  versions.
- Runtime Health: live alignment signal for deployed state.

Avoid naming UI-only containers as source truth. For example, a "release
review" screen can assemble records, but the source truth remains drafts,
artifacts, previews, operations, usage, deployments, and health.

## Cross-Link Rules

- Every draft links to parent model, validation report, drift report, generated
  artifacts, and compare view.
- Every artifact set links to source draft, report hashes, generated artifact
  details, preview builds, and export paths.
- Every preview links to artifact set, endpoint, contract-test report,
  operation status, preview portal, and active comparison.
- Every deployment links to artifact set, preview evidence when present,
  active portal, runtime health, usage, operations, and rollback target.
- Every operation links to schema docs, sample query detail, registry action,
  usage facts, and preview/runtime enforcement result.
- Every usage fact links back to operation, field, role, client, environment,
  version, and candidate comparison when applicable.

## Permission Expectations

Platform operators can view workbench state, deploy, observe health, and roll
back within authorized environments.

API producers can import, infer, validate, generate artifacts, create previews,
and inspect producer-owned diagnostics.

API consumers can view portal docs, preview docs shared with them, sample
queries, and operation status for their role/client scope.

Reviewers can inspect comparisons, operation registry, usage impact, policy
changes, and approve/reject observed operations.

Developer integrators can view reproducibility metadata, export artifacts and
report JSON, and correlate UI state with Git/CI.

The consumer portal must not expose deployment, rollback, private validation
details, raw drift reports, governance controls, or settings. It can surface
role-safe explanations for hidden fields, rejected operations, and deprecations.

## Workflow Placement

- Import YAML, infer from database, validate, inspect drift, and generate
  artifacts live primarily in Drafts, with entry points from Models.
- Create/test preview, browse preview docs, and run preview sample queries live
  in Previews and Portal.
- Review operations and usage live in Operations and Usage, with contextual
  links from schema comparison and sample query detail.
- Compare draft, preview, and deployed versions lives as a cross-area review
  view under Models and Previews.
- Deploy, observe health, and rollback live in Deployments and Runtime Health.

## First-Slice IA

The first functional UI slice should include Models, Drafts, Artifacts,
Previews, Operations, Usage, Portal, and Runtime Health in a constrained form.
Settings can be mostly read-only if workspace/environment/role/client metadata
is seeded by `/admin/graphql`.

This first slice proves browsing, importing, validating, previewing,
inspecting artifacts, reviewing operations and usage, consuming active/preview
docs, and understanding deployment readiness without adding visual design or
frontend implementation in this planning phase.
