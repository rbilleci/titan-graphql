# Titan GraphQL UI API Contract Matrix

Status: accepted; revised with Hasura and Apollo GraphQL product references.

This document completes UI6 from `docs/ui/roadmap.md`. It maps first-slice UI
needs to `/admin/graphql` data, mutations, freshness, authorization,
reproducibility paths, and DXR13 metadata gaps.

## Matrix Principles

- Every first-slice screen should read from `/admin/graphql` or explicit
  generated/report artifacts.
- UI state must be reproducible through API records, report JSON, generated
  files, preview endpoints, build identities, trace ids, Git, or CI.
- Missing fields are backend/product requirements for DXR13 or later, not
  frontend-private state.
- Freshness rules should be visible in the UI and enforce action gates.
- Portal-first screens should expose API shape, endpoint/build identity,
  relationship metadata, role/client visibility, query-explorer evidence,
  usage, metrics, traces, and documentation/security coverage before falling
  back to source or deployment details.
- Graph-control screens should expose graph refs/variants, schema lineage,
  checks, contract lenses, proposal/launch evidence, persisted operations,
  safelisting, client awareness, field usage, and CI/Rover parity as backend
  projections, not UI-only status.

## Screen-To-Query Matrix

| Screen | Persona | Workflow | Data needed | Existing source | Missing API / metadata | Freshness | Auth boundary | Reproducibility |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Portal build landing | Consumer | Understand API surface | Model, active/preview/previous build ids, endpoints, schema/artifact hashes, role/client lens, portal health summary | Model/deployment/artifact records | One portal landing projection with build and endpoint identity | Current selected build | Portal read | `/admin/graphql` portal query plus endpoint URL |
| Build selector and API identity | Integrator | Pick active/preview build | Build id, applied status, source ref, artifact set, endpoint, expiration, previous builds | Preview/deployment records | Build list projection and applied-project endpoint identity | Build metadata current | Portal/model read | Build id, endpoint, source ref |
| Schema documentation explorer | Consumer | Browse generated API | Roots, types, fields, descriptions, relationships, filters, sorts, role/client visibility, deprecations | SDL/introspection and registry | Portal docs projection with coverage and visibility flags | Schema artifact matched | Portal read by role/client | Schema artifact, docs JSON |
| Relationship graph explorer | Producer | Understand graph shape | Roots, object types, relationship edges, joins, policies, visibility, drift markers, usage counts | Model doc, validation/drift, usage | Relationship graph projection scoped to selected schema concepts | Semantic hash matched | Model/portal read | Graph metadata JSON |
| Query explorer and trace | Consumer | Run/copy query | Query text, variables, headers, endpoint, operation hash/status, response/errors, trace id, query plan, latency, models/fields used | Preview/app endpoint and traces | Portal-safe execution metadata, trace/query-plan links, copyable snippets | Endpoint live and trace current | Portal execute | Request metadata, operation hash, trace id |
| Contract lens explorer | Consumer | Inspect role/client API surface | Contract id/lens, role/client, included/excluded roots/types/fields, visibility rationale, downstream compatibility, sample applicability | Role/client metadata, schema artifact | Contract lens projection and compatibility state | Contract matched to build | Portal read by role/client | Contract id, schema artifact, policy refs |
| Graph checks and launch summary | Reviewer | Validate change safety | Check run id, schema diff, validation/lint/policy results, operation impact, contract compatibility, proposal, launch, CI status | Validation, compare, contract test, Git/CI | One graph checks/launch projection | Check run matched to build/semantic hash | Review read | Check report JSON, CI URL |
| Operation control detail | Reviewer | Control persisted operations | Operation hash/signature/name, client, owner, status, safelist/PQL state, usage, enforcement, review audit | Operation registry and usage | Persisted operation list/safelist metadata | Schema version and operation manifest matched | Governance read/write | Operation manifest, audit event |
| Portal insights report | Operator | Assess API quality | Auth status, permission coverage, documentation coverage, request rate, latency, error rate, usage, deprecated metadata, access distribution | Usage/metrics/registry/settings partially | Portal insights projection | Report timestamp within policy | Insights read | Report JSON/print export |
| Model list | Operator | Find model state | Models, workspace, active deployment, latest draft/report/artifact/health summary | Management model records and refs | Aggregated health and usage freshness summary | Current environment | Workspace/environment read | `/admin/graphql` model query |
| Model overview | Producer | Coordinate model lifecycle | Model, draft, validation, drift, artifact, preview, deployment, registry, usage, health | Management records through DXR9-DXR12 | One overview projection with gate summaries | Hash and timestamp matched | Model read | Overview query plus report JSON |
| Draft source and validation | Producer | Import/validate | Draft source identity, semantic hash, validation report, issues, source locations | Import/validate mutations, validation reports | Normalized export metadata and source-location completeness flags | Semantic hash matched | Draft read/write | Source file, model path, report JSON |
| Drift report | Producer | Inspect drift | Catalog snapshot, binding, drift entries, freshness | Drift checker/report refs | Catalog snapshot list/query and binding summary | Snapshot and semantic hash matched | Database binding read | Drift report JSON |
| Artifact manifest/detail | Integrator | Inspect artifacts | Artifact set, manifest, entries, hashes, content refs | Generated artifact workflow and refs | Artifact content query or signed/downloadable artifact references | Semantic/report/generation hash matched | Artifact read | Manifest JSON, generated files |
| Preview build detail | Producer | Create/test preview | Preview manifest, endpoint, expiration, candidate artifact set, contract report | Preview build and contract-test records | Preview lifecycle status, rerun contract-test mutation | Not expired and artifact matched | Preview read/write | Preview manifest JSON, endpoint |
| Operation registry review | Reviewer | Approve/reject operations | Observed operations, registry entries, persisted operation state, status, enforcement, usage | Operation registry and mutations | Review queue projection, safelist state, and schema-version compatibility facts | Scope and schema version matched | Governance write | Operation hash, audit event |
| Usage analytics summary | Reviewer | Review impact | Usage report facts by operation/field/role/client/version | Usage report records | Field/operation/schema concept links and report freshness policy | Report timestamp within policy | Usage read | Usage report JSON |
| Consumer schema docs | Consumer | Browse active/preview docs | SDL/introspection, visibility, deprecations, samples, operation status | Artifact SDL/introspection and registry | Portal metadata projection and sample query metadata | Active deployment or preview matched | Portal read by role/client | Schema artifact, endpoint |
| Sample query detail | Consumer | Try queries | Query, variables, endpoint, response, warnings/errors, operation hash/status | Preview route, application route, registry enforcement | Safe query-run mutation/proxy metadata and sample save/submit path | Endpoint live | Portal execute | Request metadata, operation hash |
| Relationship graph metadata | Producer | Inspect relations | Types, roots, relations, joins, policies, drift, visibility | Model document and validation/drift reports | Dedicated relationship graph metadata projection | Semantic hash matched | Model/portal read | Model path, report JSON |
| Compare versions | Reviewer | Compare draft/preview/deployed | Semantic diff, validation, drift, artifacts, preview tests, operations, usage | Records exist separately | Semantic diff projection across draft/preview/deployment | All selected versions current | Review read | Compare export JSON |
| Deployment review | Operator | Deploy candidate | Candidate gates, active deployment, rollback target, approvals | Deployment records exist; deployment mutation not fully shaped | Deployability gate projection and deployment mutation contract | Gate hashes matched | Deployment write | Deployment evidence JSON |
| Runtime health detail | Operator | Observe health | Endpoint, deployment, expected/observed hashes, mismatch, registry mode | Deployment record partially | Runtime health record/query | Check timestamp current | Runtime read | Health report JSON |
| Rollback detail | Operator | Roll back | Current deployment, target, health, audit, usage/operation impact | Rollback metadata placeholder | Rollback mutation and target eligibility query | Target health current | Deployment write | Rollback audit event |

## Screen-To-Mutation Matrix

| Mutation need | Screen | Existing source | Missing API / metadata | Authorization |
| --- | --- | --- | --- | --- |
| Import model source | Draft source and validation | `importModelDocument` | Source identity and normalized export fields may need expansion | Producer write |
| Validate draft | Draft source and validation | `validateModelDraft` | Source-location completeness and owner routing | Producer write |
| Generate artifacts | Artifact manifest/detail | `generateModelArtifacts` | Artifact content/reference query for UI drill-down | Producer write |
| Create preview build | Preview build detail | Preview build records exist | Create-preview mutation and lifecycle status if not public yet | Producer write |
| Run preview contract tests | Preview build detail | Contract-test runner records exist | Rerun mutation and operation-set selection | Producer/reviewer write |
| Approve observed operation | Operation registry review | `approveObservedOperation` | Schema-version compatibility facts | Reviewer write |
| Reject observed operation | Operation registry review | `rejectObservedOperation` | Rejection reason taxonomy and consumer-safe message | Reviewer write |
| Run sample query | Sample query detail | Preview/application routes | Portal-safe query execution/proxy metadata and save/submit action | Consumer execute |
| Deploy candidate | Deployment review | Deployment records exist | Promotion mutation, gate evidence envelope, audit result | Operator write |
| Roll back deployment | Rollback detail | Rollback metadata placeholder | Rollback mutation, eligibility, audit result | Operator write |

## DXR13 Metadata Requirements

DXR13 should be reshaped around these UI requirements:

1. Portal metadata projection
   - Supports portal build landing, schema docs, and preview schema docs.
   - Needs role/client visibility, root/type/field/relation/filter/sort
     metadata, descriptions, documentation coverage, deprecation metadata,
     sample links, endpoint identity, build identity, and operation status.

2. Sample query metadata
   - Supports sample query detail and preview tryout.
   - Needs sample id, title, schema concept links, operation text, variables,
     role/client applicability, active/preview compatibility, and approval
     status.

3. Relationship graph metadata
   - Supports relationship graph explorer and prototype exploration.
   - Needs type/root/relation/join/policy/context-filter nodes, edge metadata,
     drift flags, visibility, usage counts, artifact links, and source model
     paths.

4. Gate summary projection
   - Supports model overview and deployment review.
   - Needs validation, drift, artifact, preview, registry, usage, review, and
     runtime-health gate summaries with hashes and freshness.

5. Semantic comparison projection
   - Supports compare draft/preview/deployed.
   - Needs added/removed/changed/deprecated/visibility/policy/relation
     semantics, plus links to usage and operation impact.

6. Runtime health projection
   - Supports runtime health detail and rollback.
   - Needs expected/observed schema hash, artifact hash, endpoint status,
     registry mode, freshness, mismatch reason, and rollback eligibility.

7. Artifact content/reference access
   - Supports artifact manifest/detail.
   - Needs safe access to generated SDL, introspection, conformance,
     generated-SQL metadata, manifest content, and hashes.

8. Usage-to-schema linking
   - Supports usage analytics summary and compare view.
   - Needs mapping from usage facts to fields, operations, roles, clients,
     versions, deprecated fields, and candidate changes.

9. Query explorer evidence
   - Supports query explorer and trace.
   - Needs operation hash/status, endpoint target, request metadata, response
     hash or sample response, warnings/errors, trace id, query-plan link,
     latency, models/fields used, and copyable client/CI snippets.

10. Portal insights projection
    - Supports portal insights report and portal build landing.
    - Needs authentication status, permission coverage, documentation coverage,
      deprecated metadata, request rate, p95/p99 latency, error rate, usage
      trends, trace availability, and project/subgraph access distribution when
      available.

11. Graph checks and launch projection
    - Supports graph checks and launch summary.
    - Needs check run id, selected graph ref/variant, semantic diff, validation
      status, lint/policy status, operation impact, contract compatibility,
      proposal status, launch status, CI status, freshness, owners, and links to
      report JSON.

12. Contract lens projection
    - Supports schema docs, query explorer, and contract lens explorer.
    - Needs role/client surface id, included/excluded types and fields,
      visibility rationale, policy source, compatibility state, sample
      applicability, and downstream consumer impact.

13. Persisted operation control projection
    - Supports query explorer, operation control detail, and governance review.
    - Needs operation signature/hash, client name/version, owner, usage,
      safelist/PQL membership, enforcement mode, approval status, rejection
      reason, manifest reference, and audit links.

14. CI/Rover parity metadata
    - Supports developer integrator workflows.
    - Needs copyable reproduce commands, CI run links, schema/check/report ids,
      artifact references, operation manifest references, and evidence bundle
      export metadata.

## Required Freshness By Workflow

Import: draft source and semantic hash from the latest import mutation.

Validation: validation report hash must match draft semantic hash.

Drift: drift report must match semantic hash, catalog snapshot, database
binding, and environment.

Artifacts: artifact set must match semantic hash, validation hash, drift hash,
and generation profile.

Preview: preview must be not expired and match artifact set and candidate
registration.

Operations: registry decision must match model, environment, role, client,
schema version, and operation hash.

Graph checks: check result must match graph ref, selected build, semantic hash,
schema artifact hash, contract lens, operation manifest, and CI run.

Contract lenses: role/client surface must match selected build, schema artifact
hash, policy metadata, and compatibility check.

Usage: usage report must meet the review freshness policy for model,
environment, version, role/client filters, and timestamp.

Deployment: promotion gate must match current draft/artifact/preview/report
hashes and approval state.

Health: health check must match active deployment and be within operator-defined
freshness.

## Authorization Boundaries

Producer write: import, validate, generate artifacts, create preview.

Reviewer write: approve/reject operations, attach review decisions.

Operator write: deploy and rollback.

Consumer portal read/execute: browse active/preview docs and run allowed sample
queries for role/client scope.

Integrator read/export: access reproducibility metadata, report JSON, generated
artifact refs, preview endpoints, and CI-safe evidence.

No first-slice screen should require private frontend permissions outside these
boundaries.

## Open Contract Questions

- Should deployment and rollback mutations be part of DXR13 or remain a later
  backend lane after portal metadata?
- Should sample query execution be proxied through `/admin/graphql` or remain a
  direct active/preview endpoint call with UI-captured metadata?
- Which role/client visibility fields are safe for consumer portal exposure?
- What is the accepted freshness policy for usage reports during deployment
  review?
- Should artifact content be returned inline, by reference, or through
  generated-file export only?
- Which semantic diff fields are required for first design prototypes versus
  later implementation?
