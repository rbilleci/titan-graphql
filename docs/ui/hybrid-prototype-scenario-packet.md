# Titan GraphQL Hybrid Prototype Scenario Packet

Status: historical input; superseded as the primary prototype frame by the
Portal Build Explorer direction.

This packet gave the Operator Workbench + Model Studio hybrid prototype a
single concrete scenario and data set. It is design-only source material for
screen flow, layout, and medium-fidelity prototype work; it does not define new
backend behavior, runtime behavior, or frontend implementation.

Supersession note: keep the `customer360` data as useful scenario material, but
drive the next prototype from `docs/ui/hasura-portal-capability-study.md` and
the revised `docs/ui/prototype-brief.md`.

## Prototype Frame

Primary product frame: model overview as the operating center.

Required surfaces:

- Operator Workbench: deployment readiness, runtime alignment, operation risk,
  usage impact, active deployment, rollback evidence.
- Model Studio: source diagnostics, drift, generated artifacts, preview build,
  schema comparison, reproducibility evidence.
- Consumer Portal: portal-safe active and preview schema docs, sample query
  handoff, operation-status context without governance controls.

The prototype should make every state traceable to `/admin/graphql`, generated
artifacts, report JSON, preview endpoints, Git, CI, or runtime health.

## Scenario

Model: `customer360`

Environment: `staging`

Draft: `draft-2026-06-03-customer360-yaml`

Preview build: `preview-c360-20260603-1042`

Artifact set: `artifact-set-c360-20260603-1042`

Active deployment: `deploy-c360-staging-20260528-0915`

Rollback target: `deploy-c360-staging-20260521-1430`

Actors:

- API producer imports a YAML update that adds `Customer.lifetimeValue`,
  changes `Customer.orders` relationship metadata, and marks
  `Customer.legacyTier` deprecated.
- Platform operator checks whether the draft can move from preview to staging.
- Reviewer inspects operation registry and deprecated-field usage.
- API consumer browses the preview schema and copies a sample query.

Source truth references:

- Source file: `models/customer360/titan.graphql.yaml`
- Git commit: `4f72c18`
- CI run: `ci-customer360-preview-9821`
- Management API workspace: `workspace-enterprise-apis`

## Evidence Summary

| Signal | State | Prototype implication |
| --- | --- | --- |
| Validation warning | Present | Route producer to source diagnostics without blocking preview. |
| Validation blocker | Present | Block deployment readiness until fixed or explicitly deferred. |
| Drift finding | Present | Show database mismatch beside source and deployment gates. |
| Artifact manifest | Complete with warnings | Let producer inspect generated SDL, introspection, conformance, and SQL metadata. |
| Preview contract tests | Mixed | Show pass/fail/skip counts and failed operation link. |
| Operation registry | Approved, rejected, unknown | Keep governance actions in Operations; expose safe status summaries elsewhere. |
| Deprecated-field usage | Present | Put usage impact beside deployment review and compare surfaces. |
| Active deployment | Present | Separate deployed state from draft and preview. |
| Rollback target | Eligible | Make rollback target visible but keep mutation out of this prototype packet. |

## Validation Report

Report id: `validation-c360-20260603-1042`

Overall state: `blocked`

Warnings:

- `warning: relationship_filter_partial`
  - Source: `models/customer360/titan.graphql.yaml:88`
  - Object: `Customer.orders`
  - Message: `orders` supports equality filters in preview, but range filters
    are not generated for the current database binding.
  - Owner: API producer.
  - Prototype route: Model Studio > Source Diagnostics > Relationship details.

Blockers:

- `blocker: policy_role_missing`
  - Source: `models/customer360/titan.graphql.yaml:142`
  - Object: `Customer.lifetimeValue`
  - Message: field exposes financial data without an explicit `finance_reader`
    role policy.
  - Owner: API producer with reviewer approval.
  - Prototype route: Model Overview gate summary > Source Diagnostics >
    Policy field detail.

Design rule:

- The model overview must show the warning count and blocker count separately.
- Preview can remain inspectable while deployment readiness is blocked.

## Drift Finding

Report id: `drift-c360-snapshot-20260603-1000`

Finding:

- `drift: column_type_mismatch`
  - Model path: `Customer.lifetimeValue`
  - Database binding: `crm.customer_lifetime_value.amount`
  - Expected: `decimal(12,2)`
  - Snapshot: `decimal(10,2)`
  - Severity: `warning`
  - Runtime risk: values above `99999999.99` may truncate in generated SQL.
  - Prototype route: Model Studio > Drift Report, also linked from deployment
    readiness.

Design rule:

- Drift belongs beside validation and artifact evidence, not inside the
  consumer portal.

## Generated Artifact Manifest

Manifest id: `manifest-c360-20260603-1042`

Status: `complete_with_warnings`

Entries:

| Type | Path | Hash | Status |
| --- | --- | --- | --- |
| SDL | `artifacts/customer360/staging/schema.graphql` | `sha256:3f0c...7b22` | generated |
| Introspection | `artifacts/customer360/staging/introspection.json` | `sha256:91aa...540e` | generated |
| Conformance | `artifacts/customer360/staging/conformance.json` | `sha256:67de...2104` | generated |
| Generated SQL | `artifacts/customer360/staging/sql/customer360.sql.json` | `sha256:a410...be12` | generated_warning |

Artifact warning:

- Generated SQL includes no range-filter lowering for `Customer.orders`.

Design rule:

- Routine artifact review should surface type, status, hash, and reproducible
  path first. SQL content remains a drill-down.

## Preview Build

Preview endpoint: `/preview/preview-c360-20260603-1042/graphql`

Preview state: `ready_with_failures`

Contract-test report id: `contract-preview-c360-20260603-1042`

Counts:

- Passed: 18
- Failed: 2
- Skipped: 1

Failed operation examples:

- `CustomerRevenueExport`: rejected by operation registry.
- `CustomerLifetimeValueRead`: blocked by missing `finance_reader` policy.

Skipped operation:

- `CustomerOrdersRangeSearch`: skipped because generated SQL range lowering is
  unavailable for the current binding.

Design rule:

- Preview docs and sample query handoff can be available while readiness shows
  the failed and skipped test counts.

## Operation Registry Snapshot

Registry id: `operations-c360-staging-20260603`

| Operation | Role | Client | Status | Prototype placement |
| --- | --- | --- | --- | --- |
| `CustomerProfileRead` | `support_reader` | `helpdesk-web` | approved | Safe summary in portal sample detail; action in Operations. |
| `CustomerRevenueExport` | `support_reader` | `csv-exporter` | rejected | Blocker in Operations and preview contract failures. |
| `CustomerLifetimeValueRead` | `finance_reader` | `finance-dashboard` | unknown | Review queue and compare warning. |

Design rule:

- The consumer portal may show operation status and explanation, but not approve
  or reject actions.

## Usage Report

Report id: `usage-c360-staging-20260603`

Freshness: generated from the last 24 hours of staging traffic.

Deprecated-field usage:

- Field: `Customer.legacyTier`
- Deprecation note: replace with `Customer.segment`.
- Calls: 1,284
- Distinct clients: 3
- Top client: `helpdesk-web`
- Affected approved operation: `CustomerProfileRead`

Additional usage notes:

- `Customer.lifetimeValue` has no active deployed usage because it is preview
  only.
- `Customer.orders` is used by 11 approved operations.

Design rule:

- Deprecated-field usage must appear in deployment review and schema compare.
  Portal docs may show the deprecation note and sample replacement field.

## Deployment And Rollback

Active deployment:

- Deployment id: `deploy-c360-staging-20260528-0915`
- Schema hash: `sha256:d5f0...a901`
- Artifact set: `artifact-set-c360-20260528-0915`
- Health: `healthy`
- Runtime alignment: `matches_active_artifacts`

Rollback target:

- Deployment id: `deploy-c360-staging-20260521-1430`
- Schema hash: `sha256:af23...9bc1`
- Artifact set: `artifact-set-c360-20260521-1430`
- Eligibility: `eligible`
- Reason: last known-good deployment with matching artifact manifest and no
  open runtime-health mismatch.

Design rule:

- Rollback evidence should be visible from the Operator Workbench overview, but
  this packet does not define a rollback mutation or production UI action.

## Screen Scenario Beats

1. Model list shows `customer360` with `blocked` deployment readiness, one
   active staging deployment, and one ready preview with failures.
2. Model overview separates draft, preview, active deployment, and rollback
   target. Validation blocker, drift warning, artifact manifest, contract-test
   counts, operations, and usage impact are visible as linked evidence.
3. Source diagnostics opens to `Customer.lifetimeValue` and explains the
   missing role policy blocker.
4. Drift report shows the decimal precision mismatch and links to artifact SQL
   warning.
5. Artifact manifest shows generated SDL, introspection, conformance, and SQL
   entries with hashes and reproducibility paths.
6. Preview build detail shows endpoint, pass/fail/skip counts, and failed
   operation links.
7. Operations review shows approved, rejected, and unknown operations with
   governance actions isolated from portal views.
8. Usage impact shows deprecated `Customer.legacyTier` use and affected clients.
9. Compare surface highlights draft, preview, and deployed differences:
   `lifetimeValue` added, `legacyTier` deprecated, `orders` filter support
   limited.
10. Consumer portal preview docs show `Customer.lifetimeValue` as preview-only,
    show `legacyTier` deprecation copy, and provide the approved
    `CustomerProfileRead` sample query handoff without governance controls.

## Consumer Portal Sample Query

Sample id: `sample-customer-profile-read-preview`

Operation status: `approved`

Portal visibility: active and preview, with preview-only field callout.

```graphql
query CustomerProfileRead($customerId: ID!) {
  customer(id: $customerId) {
    id
    name
    segment
    legacyTier
    orders {
      id
      total
    }
  }
}
```

Preview-only alternative:

```graphql
query CustomerLifetimeValueRead($customerId: ID!) {
  customer(id: $customerId) {
    id
    lifetimeValue
  }
}
```

Design rule:

- The approved sample can be copied or handed to a portal-safe execution path.
  The unknown preview-only operation should show review status, not a governance
  control.

## Prototype Acceptance Notes

The next design slice can use this packet if it demonstrates:

- clear separation of draft, preview, active deployment, and rollback target
- direct routing from blocker and warning summaries to owner workflows
- generated artifact evidence without treating generated SQL as routine content
- operation and usage risk beside schema change review
- portal-safe schema and sample query browsing
- no UI-only state that cannot be traced to the evidence in this packet
