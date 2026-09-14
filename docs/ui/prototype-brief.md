# Titan GraphQL UI Prototype Brief

Status: accepted; revised with Hasura and Apollo GraphQL product references.

This document completes the prototype brief portion of UI7 from
`docs/ui/roadmap.md`. It defines prototype directions and shared scenarios for
visual design exploration after the UI planning packet is accepted.

## Prototype Goal

Explore product structure and interaction tradeoffs for the Titan GraphQL UI
before implementing frontend code. Prototypes should use the same workflows,
states, screens, and API contract gaps defined in `docs/ui/`.

The prototypes should not invent UI-only source truth. Every important state
must be traceable to `/admin/graphql`, generated artifacts, report JSON, preview
endpoints, Git, CI, runtime health, usage facts, trace facts, or build identity.

## Accepted Prototype Direction

Proceed with a **Portal Build Explorer + Graph Control Plane** direction.

This direction treats the generated API surface as the operating center:
active/preview build identity, endpoint identity, schema docs, relationship
graph, sample query exploration, role/client visibility, operation status,
documentation coverage, usage, metrics, and trace evidence. Source diagnostics,
drift, generated artifacts, deployment readiness, operation governance,
rollback, Git, and CI remain first-class evidence behind the API surface.

This correction comes from the Hasura DDN portal study in
`docs/ui/hasura-portal-capability-study.md`. Hasura's console centers API
onboarding and build-specific exploration through generated docs, GraphiQL,
Supergraph Explorer, relationship/ERD views, usage, security/documentation
coverage, metrics, and traces. Titan should adapt that model to Titan's
metadata, artifact, operation-registry, drift, and deployment semantics.

The complementary Apollo GraphQL synthesis in
`docs/ui/apollo-graphql-product-synthesis.md` adds the lifecycle model behind
that portal: graph variants/refs, schema registry and lineage, schema checks,
proposals, launches, contracts, persisted query lists, safelisting, client
awareness, field usage, operation metrics, traces, query plans, and Rover/CI
parity. Titan should adapt those ideas without copying federation-specific
vocabulary where Titan has a simpler native object.

First prototype frame:

- portal build landing as the primary entry path
- active, preview, and previous/rollback build selector
- contract lens selector for role/client-specific API surfaces
- schema documentation explorer with role/client lens
- relationship graph explorer tied to selected schema concepts
- query explorer with sample query, operation status, response, trace, and
  copyable client/CI snippets
- graph checks and launch/proposal summary for the selected build
- operation-control detail for persisted operation/safelist state
- portal insights report covering auth, permission, documentation, usage,
  reliability, deprecated metadata, and developer/team access signals
- evidence drawer for source, validation, drift, artifacts, contract tests,
  Git, CI, deployment readiness, and rollback context

## Shared Scenario Set

Each prototype direction must cover these scenarios:

1. Open the `customer360` API portal and identify active, preview, and previous
   build identities.
2. Browse role-visible schema docs and find a preview-only field.
3. Inspect relationships and filter support for a selected type.
4. Run or prepare a sample query against the preview endpoint.
5. Inspect operation status, response/errors, trace id, and query-plan evidence
   for that sample query.
6. Switch to a contract lens and verify which fields/queries are included for a
   role/client surface.
7. Review graph checks, contract compatibility, proposal/launch status, and
   CI evidence for the selected preview.
8. Review documentation coverage, permission coverage, usage, latency, and
   error signals for the selected API surface.
9. Inspect persisted operation/safelist state for a selected query and client.
10. Open the evidence drawer for a warning and trace it to source diagnostics,
   drift, artifact hashes, contract tests, Git, or CI.

Stretch scenarios:

- Compare active, preview, and previous build API shape.
- Inspect drift between model and database snapshot.
- Diagnose runtime health mismatch after deployment.
- Roll back to a known-good deployment.
- Export report JSON and artifact evidence for CI.

## Superseded Direction

The Operator Workbench + Model Studio hybrid remains valid as a supporting
evidence model, but it is no longer the default prototype frame. The clickable
prototype from PR #157 should be treated as negative signal: it over-indexed on
generic operator dashboards and under-indexed on API portal orientation.

## Direction A: Portal Build Explorer

Frame: an API portal centered on active/preview build identity, schema
exploration, relationship understanding, query tryout, and evidence-backed API
health.

Best for:

- API consumer onboarding
- producer and integrator review of the generated API surface
- build-specific preview testing
- operation status and usage evidence at point of use

Core screens:

- Portal build landing
- Build selector and API identity
- Contract lens selector
- Schema documentation explorer
- Relationship graph explorer
- Query explorer and trace
- Graph checks and launch summary
- Operation control detail
- Portal insights report
- Evidence drawer

Interaction emphasis:

- active vs preview vs previous build clarity
- role/client-visible schema and sample queries
- query run/copy handoff with operation status and trace evidence
- evidence links from warnings to source, artifacts, usage, and CI

Risks:

- deployment controls may feel secondary
- producer source workflow needs careful evidence drawer design
- relationship graph can become visual noise if not anchored to schema tasks

Prototype must answer:

- Can a consumer or integrator understand and test the API before knowing
  Titan internals?
- Can build identity, endpoint identity, and role/client visibility stay clear?
- Can warnings feel native to API exploration instead of like admin-console
  chores?

## Direction B: Evidence Workbench

Frame: a supporting workbench for source, drift, artifact, contract-test,
deployment, rollback, and governance evidence behind the portal.

Best for:

- import/inference/validation workflow
- artifact inspection
- preview creation
- producer and integrator handoffs

Core screens:

- Model overview
- Draft source and validation
- Drift report
- Artifact manifest/detail
- Preview build detail
- Compare draft / preview / deployed
- Relationship graph metadata view

Interaction emphasis:

- source outline and diagnostic navigation
- artifact generation and review as a normal step
- preview build handoff to consumers and reviewers
- reproducibility metadata for Git and CI

Risks:

- operator deployment safety may require too many cross-screen hops
- graph checks, operation control, and usage impact may be treated as
  afterthoughts
- consumer docs may be only a preview side effect

Prototype must answer:

- Can a producer move from imported or inferred source to a reviewable preview?
- Can generated artifacts be inspectable without overwhelming routine users?
- Can Git-first and UI-first workflows converge cleanly?

## Direction C: Review And Governance Split

Frame: a compact governance/review console paired with the Portal Build
Explorer.

Best for:

- operation status and policy decisions
- deployment-readiness evidence
- usage/deprecation review
- consumer-safe status copy at point of use

Core screens:

- Contract lens explorer
- Schema docs and sample query detail
- Preview build detail
- Operation control review
- Graph checks and launch summary
- Usage analytics summary
- Compare draft / preview / deployed
- Model overview

Interaction emphasis:

- active vs preview schema clarity
- role/client visibility and deprecation copy
- operation approval status beside query examples
- preview feedback loop between consumers and reviewers

Risks:

- producer source and artifact workflows may be underpowered
- deployment and runtime health may feel bolted on
- governance actions need careful separation from consumer portal visibility

Prototype must answer:

- Can consumers safely adopt preview schema changes before deployment?
- Can operation approval be reviewed without exposing private controls?
- Can portal metadata shape DXR13 without overbuilding UI-specific backend?

## Prototype Fidelity

Use medium-fidelity product screens, not final visual styling. Prototypes should
demonstrate information hierarchy, navigation, state, copy tone, and workflow
handoffs.

Required evidence:

- at least one validation warning and one validation blocker
- at least one drift finding
- one generated artifact manifest with SDL/introspection/conformance/generated
  SQL entries
- one preview endpoint with contract-test pass/fail/skip counts
- one approved, one rejected, and one unknown operation
- one usage report with deprecated-field use
- one active deployment and one rollback target

## Evaluation Criteria

Choose or combine directions based on:

- speed to understand deployability
- clarity of draft, preview, and deployed separation
- quality of diagnostic owner routing
- consumer portal safety and usefulness
- reproducibility through Git, CI, report JSON, artifacts, and endpoints
- low risk of UI-only source truth
- fit with DXR13 metadata requirements
- ability to scale into first functional UI slice

## Prototype Handoff

After prototype review, update:

- `docs/ui/design-readiness-checklist.md` with accepted direction and remaining
  open questions
- `docs/developer-experience-roadmap.md` DXR13 with metadata and API work
  required by the accepted direction
- `docs/ui/api-contract-matrix.md` if prototype evidence changes first-slice
  contract needs

Visual design may start only after the design-readiness checklist is accepted.
