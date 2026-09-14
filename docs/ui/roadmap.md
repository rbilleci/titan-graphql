# Titan GraphQL UI Planning Roadmap

Status: accepted; revised with Hasura and Apollo GraphQL product references.

This roadmap defines the non-visual product, workflow, and API-contract
artifacts required before Titan GraphQL resumes DXR13 or starts visual UI
design. All UI planning documents should live under `docs/ui/`.

The goal is to make design work deliberate: we should know who the UI serves,
which workflows matter, what screens are needed, and which `/admin/graphql`
data contracts the UI requires before choosing layout, styling, or interaction
patterns.

## Scope

This roadmap covers the preparation phase before visual design.

In scope:

- personas and user goals
- operator and consumer workflows
- information architecture
- screen inventory
- lifecycle and state model
- content and diagnostics model
- `/admin/graphql` data-contract needs
- prototype briefs and acceptance criteria
- DXR13 reshaping guidance

Out of scope until this roadmap is complete:

- visual design
- component styling
- layout explorations
- frontend framework selection
- frontend implementation
- UI-only model semantics that bypass `/admin/graphql`

## Product Position

The Titan GraphQL UI should be an API portal and build explorer first, with a
graph-control-plane evidence layer behind that portal. It should not become a
private configuration system or a generic admin dashboard. The UI is a client
of `/admin/graphql`, and every important operation should remain available to
automation, CI, and Git-first workflows.

Hasura DDN is the closest product reference. Its console centers metadata-backed
API understanding: projects/subgraphs, immutable builds, build-specific
GraphiQL/Supergraph Explorer, generated API documentation, relationship/ERD
views, usage analytics, security/documentation coverage, query traces, metrics,
and connector capabilities. Titan should adapt those lessons around Titan's own
YAML source, generated artifacts, operation registry, preview endpoints, drift,
and deployment evidence.

Apollo GraphOS adds the missing lifecycle language behind that portal: graph
variants/refs, schema registry and lineage, schema checks, proposals, launches,
contracts, persisted query lists, safelisting, client awareness, field usage,
operation metrics, traces, query plans, and Rover/CI parity. Titan should
synthesize the ideas: Hasura for the portal/build-explorer shape, Apollo for
safe graph evolution and operation control.

Design principles carried from the DX proposal:

- lead with API exploration: schema docs, relationship graph, sample queries,
  build identity, endpoint identity, and role/client visibility
- use contract lenses for role/client-specific API surfaces
- expose schema checks, proposals, launches, persisted operations, and
  safelisting as graph-control evidence
- keep YAML source visible and reviewable as evidence behind API shape
- make generated artifacts inspectable before deployment
- treat warnings and blockers as first-class objects
- explain unsupported capabilities precisely
- show semantic changes, not only text diffs
- make preview endpoint testing the primary portal review loop
- show operation-control state, client awareness, field usage, and traces
  beside schema changes
- keep applied active API, preview build, and rollback target identities obvious
- avoid opaque wizard-only model generation
- do not create UI-only write paths

## Personas

The first planning artifact should be `docs/ui/personas.md`.

### Platform Operator

Owns environments, deployment safety, runtime health, rollback, and policy
boundaries.

Primary jobs:

- understand whether a model is safe to deploy
- inspect validation, drift, preview, artifact, and runtime health signals
- approve deployment or rollback with confidence
- diagnose production mismatch without reading raw logs first

Questions the UI must answer:

- What changed?
- Is it deployable?
- What blocks deployment?
- What is running now?
- Can I roll back safely?

### API Producer

Owns the model source, schema shape, policies, relations, generated artifacts,
and compatibility expectations.

Primary jobs:

- import or edit `titan.graphql.yaml`
- validate model structure and database bindings
- inspect generated SDL, introspection, conformance, and SQL metadata
- create preview builds for review
- fix drift or unsupported capability issues

Questions the UI must answer:

- Does this model represent my database and intended API?
- Which fields, relations, filters, and policies are exposed?
- What did Titan generate from my source?
- What needs review before deployment?

### API Consumer

Uses active or preview schemas to build clients, queries, and integrations.

Primary jobs:

- browse schema docs
- try active and preview APIs
- find sample queries and fragments
- understand field visibility by role
- see deprecations, changelog notes, and approved operation status

Questions the UI must answer:

- What can I query?
- Which fields are available for my role?
- What examples should I start from?
- Is this operation approved in my environment?

### Reviewer / Governance Owner

Reviews operational risk, compatibility impact, usage, approved operations, and
policy changes before promotion.

Primary jobs:

- compare draft, preview, and deployed versions
- inspect operation registry changes
- review usage analytics and deprecation impact
- approve or reject observed operations
- verify policy and visibility changes

Questions the UI must answer:

- Who or what depends on this schema?
- Which operations are new, approved, rejected, or unknown?
- Which deprecated fields are still used?
- Which policy changes affect consumers?

### Developer Integrator

Works Git-first or CI-first and needs the UI to agree with CLI/API behavior.

Primary jobs:

- correlate UI reports with files, PRs, and CI output
- export normalized source and generated artifacts
- reproduce UI-visible validation or drift results locally
- use preview endpoints from tests and client tooling

Questions the UI must answer:

- Can I reproduce this outside the UI?
- Which source file or model path caused this issue?
- What should I commit?
- Which endpoint and artifacts should CI use?

## Roadmap Overview

The preparation roadmap has eight planning milestones. Each milestone produces
one or more files under `docs/ui/`.

1. UI0: Roadmap and doc structure
2. UI1: Personas and jobs
3. UI2: Workflow map
4. UI3: Information architecture
5. UI4: Screen inventory
6. UI5: State, content, and diagnostics model
7. UI6: API/data contract matrix
8. UI7: Prototype briefs and design-readiness gate

DXR13 should not resume until UI7 is complete and accepted.

## UI0: Roadmap And Doc Structure

Goal: create the planning structure and define what must exist before visual
design begins.

Deliverables:

- `docs/ui/roadmap.md`
- list of planned UI docs and ownership expectations
- explicit design-readiness gate

Exit criteria:

- the team agrees that UI planning artifacts live under `docs/ui/`
- visual design remains paused until UI7 is accepted
- DXR13 metadata work is treated as downstream of this roadmap

Validation:

```bash
git diff --check
```

## UI1: Personas And Jobs

Goal: define the users, jobs, goals, anxieties, and success criteria that the UI
must serve.

Deliverables:

- `docs/ui/personas.md`
- primary and secondary personas
- jobs-to-be-done for each persona
- decisions each persona must make
- signals each persona needs from Titan
- anti-goals for each persona

Required persona set:

- platform operator
- API producer
- API consumer
- reviewer / governance owner
- developer integrator

Exit criteria:

- every later workflow maps to at least one persona
- each persona has explicit success criteria
- each persona names what they should not need to understand

Validation:

```bash
git diff --check
```

## UI2: Workflow Map

Goal: describe the end-to-end work the UI must support before naming screens.

Deliverables:

- `docs/ui/workflows.md`
- operator workbench workflows
- consumer portal workflows
- Git-first, UI-first, and hybrid workflow variants
- happy paths and blocked paths
- handoffs between UI, Git, CI, preview builds, and runtime deployment

Required workflows:

- import existing YAML
- infer draft model from database snapshot
- validate model and source locations
- inspect drift
- generate and inspect artifacts
- create and test preview build
- review operation registry changes
- review usage analytics and deprecations
- browse active schema docs
- browse preview schema docs
- try sample queries
- compare draft, preview, and deployed versions
- deploy, observe health, and roll back

Exit criteria:

- every workflow has a trigger, actor, input, output, and failure path
- workflows clearly separate operator workbench and consumer portal needs
- workflows identify which existing DXR1-DXR12 capabilities they consume

Validation:

```bash
git diff --check
```

## UI3: Information Architecture

Goal: define the product structure before designing pages.

Deliverables:

- `docs/ui/information-architecture.md`
- top-level navigation model
- object model and naming conventions
- cross-links between models, drafts, previews, deployments, operations, usage,
  artifacts, and portal views
- rough permission/role visibility expectations

Candidate top-level areas:

- Models
- Drafts
- Previews
- Deployments
- Artifacts
- Operations
- Usage
- Portal
- Runtime Health
- Settings

Exit criteria:

- every workflow from UI2 can be placed in the IA
- the IA distinguishes source truth, generated artifacts, and runtime state
- consumer portal navigation does not expose private operator controls

Validation:

```bash
git diff --check
```

## UI4: Screen Inventory

Goal: name the screens and panels the UI needs, without designing them visually.

Deliverables:

- `docs/ui/screen-inventory.md`
- screen list grouped by IA area
- purpose, primary persona, core data, actions, empty states, and failure states
  for each screen
- first-slice screen set and deferred screen set

Candidate first-slice screens:

- model list
- model overview
- draft source and validation
- drift report
- artifact manifest and artifact detail
- preview build detail
- operation registry review
- usage analytics summary
- consumer schema docs
- sample query detail
- relationship graph metadata view

Exit criteria:

- each screen has a clear reason to exist
- no screen requires private backend access outside `/admin/graphql`
- first-slice screens prove browsing, importing, validating, previewing, and
  consuming

Validation:

```bash
git diff --check
```

## UI5: State, Content, And Diagnostics Model

Goal: define what states the UI must show and how messages should read before
writing interface copy or designing components.

Deliverables:

- `docs/ui/state-model.md`
- `docs/ui/content-model.md`
- lifecycle states for models, drafts, previews, deployments, operations, and
  runtime health
- severity model for validation, drift, preview, operation registry, usage, and
  deployment issues
- terminology glossary
- empty, loading, blocked, warning, success, and rollback language principles

Required lifecycle coverage:

- draft created
- source imported
- inferred from database
- validation passed
- validation blocked
- drift detected
- artifacts generated
- preview ready
- preview failed
- operation observed
- operation approved
- operation rejected
- enforcement warning
- enforcement blocked
- deployed
- health mismatch
- rollback available
- rolled back

Exit criteria:

- design work can use stable vocabulary
- diagnostics are understandable without raw logs
- state names map back to `/admin/graphql` concepts

Validation:

```bash
git diff --check
```

## UI6: API And Data Contract Matrix

Goal: convert workflow and screen needs into backend metadata requirements.

Deliverables:

- `docs/ui/api-contract-matrix.md`
- screen-to-query matrix
- screen-to-mutation matrix
- fields needed from `/admin/graphql`
- gaps in existing DXR1-DXR12 management APIs
- DXR13 metadata requirements shaped by real UI needs

Required mapping:

- persona
- workflow
- screen
- data needed
- existing API source
- missing API/metadata
- required freshness
- authorization boundary
- export/reproducibility path

Exit criteria:

- every first-slice screen has a declared data source
- every DXR13 metadata task maps to at least one screen or workflow
- missing backend fields are named before implementation resumes

Validation:

```bash
git diff --check
```

## UI7: Prototype Briefs And Design Readiness

Goal: define what visual design prototypes should explore and how to judge them.

Deliverables:

- `docs/ui/prototype-brief.md`
- `docs/ui/design-readiness-checklist.md`
- two or three prototype directions
- shared scenario set used to compare directions
- acceptance criteria for choosing or combining directions
- explicit handoff into DXR13 or revised DXR13

Prototype directions should cover the same core scenarios:

- inspect a model with validation warnings
- compare draft vs deployed schema
- review generated artifacts before preview
- open a preview and run a sample query
- approve or reject observed operations
- review usage impact before deployment
- browse the consumer portal for a preview schema

Exit criteria:

- all earlier artifacts are complete enough to inform design
- design can evaluate concrete product tradeoffs, not generic aesthetics
- DXR13 is rewritten or confirmed based on the selected prototype direction

Validation:

```bash
git diff --check
```

## Handoff Into DXR13

After UI7, resume implementation planning by updating the DXR13 section of
`docs/developer-experience-roadmap.md`.

DXR13 should then be reframed from generic portal metadata into the backend
support required by the selected UI direction.

Likely DXR13 shape after this roadmap:

- DXR13.0: accepted UI planning packet and prototype decision
- DXR13.1: portal metadata API required by first-slice screens
- DXR13.2: sample query generation required by consumer workflows
- DXR13.3: relationship graph metadata required by selected graph view
- DXR13.4: UI data-contract conformance fixtures
- DXR13.5: first functional UI slice, if approved as part of DXR13 rather than
  a separate DXR14 UI implementation lane

Do not build UI surfaces from metadata just because metadata exists. Build
metadata because a chosen workflow and screen require it.

## Design-Readiness Checklist

Visual design may start only when these are true:

- personas are accepted
- workflows are accepted
- information architecture is accepted
- first-slice screen inventory is accepted
- lifecycle and content language are accepted
- API/data contract matrix is accepted
- prototype brief is accepted
- first-slice scenarios are accepted
- unresolved product questions are listed with owners or decisions
- DXR13 has been updated to reflect the chosen direction

## Open Questions

- Should the first functional UI slice prioritize the operator workbench or the
  consumer portal?
- Should the UI support editing YAML directly in the first slice, or only import,
  inspect, validate, and preview?
- Should UI edits preserve YAML comments, or should normalized export be the
  only supported round trip?
- How much generated SQL should be visible by default versus behind an advanced
  view?
- Should operation registry review live beside schema review, usage analytics,
  or both?
- Should relationship graph metadata be a primary navigation surface or a detail
  view inside model/schema pages?
- Should first-slice preview query execution embed GraphiQL-like tooling or link
  to a separate explorer?

## Document Index

Planned UI documents:

- `docs/ui/roadmap.md`
- `docs/ui/personas.md`
- `docs/ui/workflows.md`
- `docs/ui/information-architecture.md`
- `docs/ui/screen-inventory.md`
- `docs/ui/state-model.md`
- `docs/ui/content-model.md`
- `docs/ui/api-contract-matrix.md`
- `docs/ui/prototype-brief.md`
- `docs/ui/design-readiness-checklist.md`
