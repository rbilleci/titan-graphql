# Titan GraphQL Hybrid Navigation And Screen Flow

Status: superseded by Hasura-informed Portal Build Explorer direction.

This design slice captured the earlier Operator Workbench + Model Studio hybrid
direction and the `customer360` scenario packet into a concrete navigation,
layout, and screen-flow model. It is design-only source material for the next
medium-fidelity static prototype; it does not define backend behavior, runtime
behavior, frontend implementation, screenshots, or generated assets.

Supersession note: after reviewing Hasura DDN portal capabilities and the
disconnected clickable prototype variants, the next design iteration should use
`docs/ui/hasura-portal-capability-study.md` and the revised
`docs/ui/prototype-brief.md` as the current direction. This flow remains useful
only as evidence for source/artifact/deployment drill-downs behind the Portal
Build Explorer.

## Source Scenario

Source packet: `docs/ui/hybrid-prototype-scenario-packet.md`.

Primary model: `customer360`.

Environment: `staging`.

Primary states:

- draft `draft-2026-06-03-customer360-yaml`
- preview build `preview-c360-20260603-1042`
- active deployment `deploy-c360-staging-20260528-0915`
- rollback target `deploy-c360-staging-20260521-1430`

Primary frame: model overview as the operating center, with producer,
operator, reviewer, and consumer paths that preserve draft, preview, active
deployment, and rollback separation.

## Navigation Model

The prototype uses three product areas with shared model context.

| Area | Primary users | Purpose | Controls allowed |
| --- | --- | --- | --- |
| Operator Workbench | Platform operator, reviewer | Deployability, runtime alignment, usage risk, operation risk, rollback evidence | Review and decision affordances only in operator/reviewer surfaces |
| Model Studio | API producer, developer integrator | Source diagnostics, drift, generated artifacts, preview build, comparison, reproducibility | Producer diagnostics and evidence export affordances |
| Consumer Portal | API consumer | Portal-safe active/preview schema docs and sample query handoff | Browse, copy, and portal-safe execution handoff only |

Persistent navigation:

- Models
- Operations
- Usage
- Deployments
- Consumer Portal

Context switchers:

- Workspace: `workspace-enterprise-apis`
- Model: `customer360`
- Environment: `staging`
- Version lens: Draft, Preview, Active, Rollback

Design rules:

- The model switcher keeps users in the same product area when possible.
- The version lens is visible on model-scoped screens, but unavailable on
  unscoped lists.
- Consumer Portal never exposes approval, rejection, deployment, rollback, or
  source-editing controls.

## Layout Model

### Model Overview

The overview is the first screen after selecting `customer360`.

Header:

- model name, environment, active deployment id, preview build id
- readiness state `blocked`
- source commit `4f72c18` and CI run `ci-customer360-preview-9821`

Primary columns:

| Column | Content | First action |
| --- | --- | --- |
| Readiness | validation warnings/blockers, drift warning, preview test counts | Open blocking diagnostic |
| Lifecycle | draft, preview, active deployment, rollback target | Open compare |
| Risk | operations queue, deprecated-field usage, affected clients | Open deployment review |

Evidence strip:

- validation report `validation-c360-20260603-1042`
- drift report `drift-c360-snapshot-20260603-1000`
- artifact manifest `manifest-c360-20260603-1042`
- contract-test report `contract-preview-c360-20260603-1042`
- usage report `usage-c360-staging-20260603`

The overview must show warning count, blocker count, pass/fail/skip counts, and
rollback eligibility without requiring users to open source first.

### Detail Screens

Each detail screen keeps a compact model context header with links back to the
overview, compare, and relevant evidence.

| Screen | Product area | Layout emphasis | Required scenario data |
| --- | --- | --- | --- |
| Source Diagnostics | Model Studio | source outline, diagnostics list, selected field detail | `policy_role_missing`, `relationship_filter_partial` |
| Drift Report | Model Studio | finding list and database binding detail | decimal precision mismatch |
| Artifact Manifest | Model Studio | artifact table, hashes, reproducible paths | SDL, introspection, conformance, generated SQL |
| Preview Build | Model Studio | endpoint, contract-test counts, failed/skipped operations | `/preview/preview-c360-20260603-1042/graphql`, 18/2/1 |
| Compare | Shared | draft/preview/active lanes with semantic changes | `lifetimeValue`, `legacyTier`, `orders` |
| Operations Review | Operator Workbench | operation queue and decision state | approved, rejected, unknown operations |
| Usage Impact | Operator Workbench | deprecated-field use and affected clients | `Customer.legacyTier`, 1,284 calls, 3 clients |
| Deployment Review | Operator Workbench | readiness gates and evidence checklist | blocker, drift warning, usage impact, contract failures |
| Rollback Evidence | Operator Workbench | active vs rollback target evidence | eligible rollback target |
| Portal Schema Docs | Consumer Portal | schema browsing and field status | active and preview `Customer` fields |
| Portal Sample Query | Consumer Portal | sample query, operation status, copy handoff | `CustomerProfileRead`, preview-only alternative |

## Screen Flow

### Entry Flow

1. Models list shows `customer360` with `blocked` readiness, one active staging
   deployment, and preview `ready_with_failures`.
2. Selecting `customer360` opens Model Overview.
3. The overview exposes three primary routes:
   - fix or inspect producer diagnostics in Model Studio
   - review deployment, usage, operations, and rollback evidence in Operator
     Workbench
   - open portal-safe preview docs in Consumer Portal

### Producer Diagnostic Flow

1. Model Overview highlights one validation blocker and one warning.
2. Selecting the blocker opens Source Diagnostics focused on
   `Customer.lifetimeValue`.
3. Source Diagnostics explains the missing `finance_reader` policy and links to
   the Compare surface.
4. The warning route opens `Customer.orders` relationship details and links to
   Artifact Manifest generated SQL warning.
5. Drift Report shows the `decimal(12,2)` vs `decimal(10,2)` mismatch and links
   back to Deployment Review as a warning, not a deployment blocker.

### Artifact And Preview Flow

1. Model Overview shows the artifact manifest as `complete_with_warnings`.
2. Artifact Manifest lists SDL, introspection, conformance, and generated SQL
   entries with status, hash, and path.
3. Selecting the SQL warning opens a generated SQL metadata detail view.
4. Preview Build shows endpoint, 18 passed, 2 failed, and 1 skipped contract
   test.
5. Failed operations link to Operations Review; skipped range search links to
   Artifact Manifest and Source Diagnostics.

### Review And Deployment Flow

1. Deployment Review opens from Model Overview or Compare.
2. It presents readiness gates in this order:
   - validation blocker: blocking
   - preview contract failures: blocking for promotion
   - drift finding: warning
   - deprecated-field usage: warning with affected clients
   - rollback target: eligible
3. Operations Review shows `CustomerProfileRead` approved,
   `CustomerRevenueExport` rejected, and `CustomerLifetimeValueRead` unknown.
4. Usage Impact shows `Customer.legacyTier` deprecated-field usage, replacement
   copy, affected client count, and the approved operation that still uses it.
5. Rollback Evidence compares the active deployment and eligible rollback
   target without introducing a rollback mutation in this design slice.

### Consumer Portal Flow

1. Consumer Portal opens to active schema docs by default.
2. The preview lens shows preview-only `Customer.lifetimeValue`, deprecation
   copy for `Customer.legacyTier`, and limited filter support for
   `Customer.orders`.
3. Sample Query detail shows `CustomerProfileRead` as approved and copyable.
4. The preview-only `CustomerLifetimeValueRead` query shows `unknown` review
   status and explains that it is not approved for production use.
5. No governance controls are visible in the portal; operation state is
   explanatory context only.

## Compare Surface Contract

The Compare surface is shared by Operator Workbench and Model Studio. It
compares semantic state, not text-only source.

Lanes:

- Draft: source state from `models/customer360/titan.graphql.yaml`
- Preview: generated artifact and preview endpoint state
- Active: deployed schema and operation usage state
- Rollback: known-good deployment evidence

Required rows:

| Change | Draft | Preview | Active | Rollback |
| --- | --- | --- | --- | --- |
| `Customer.lifetimeValue` | added, policy missing | preview-only, contract failure | absent | absent |
| `Customer.legacyTier` | deprecated | deprecated with replacement note | used by approved operation | present without new preview changes |
| `Customer.orders` | relationship metadata changed | equality filters only | used by 11 approved operations | prior relationship metadata |

Compare links:

- policy blocker to Source Diagnostics
- preview failure to Preview Build and Operations Review
- deprecated-field usage to Usage Impact
- rollback lane to Rollback Evidence

## Prototype Screen List

The UI-PROTOTYPE.3 static screen-spec artifact should produce these frames:

1. Models List
2. Model Overview
3. Source Diagnostics
4. Drift Report
5. Artifact Manifest
6. Preview Build
7. Compare
8. Operations Review
9. Usage Impact
10. Deployment Review
11. Rollback Evidence
12. Portal Schema Docs
13. Portal Sample Query

Optional condensed frame:

- A single Evidence Export panel may be represented inside Artifact Manifest or
  Deployment Review if the prototype needs to show Git/CI/report JSON
  reproducibility without adding another screen.

## Acceptance Notes

This slice is ready for medium-fidelity static prototype work when the next
artifact can show:

- model overview as the first operating center
- direct paths from readiness blockers to owner workflows
- artifact, preview, operation, usage, deployment, and rollback evidence
  without UI-only source truth
- compare as a shared surface for draft, preview, active, and rollback states
- portal-safe schema and sample-query browsing with governance controls absent
