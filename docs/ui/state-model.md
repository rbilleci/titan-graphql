# Titan GraphQL UI State Model

Status: accepted.

This document completes the state portion of UI5 from `docs/ui/roadmap.md`. It
defines lifecycle states and diagnostic severity for UI planning. State names
should map back to `/admin/graphql` records, generated reports, preview
endpoints, or runtime health signals.

## State Principles

- A state is only trustworthy when its source record and freshness are visible.
- Blocking, warning, and success states should cite the Titan signal that
  produced them.
- UI-only optimistic state must never replace draft, artifact, preview,
  operation, usage, deployment, or health records.
- Historical states remain useful, but actions should depend on current
  freshness and matching hashes.

## Model And Draft Lifecycle

Draft created: a model draft exists but may not yet have valid imported or
inferred source.

Source imported: YAML source parsed into a draft with source identity,
semantic hash, and audit metadata.

Inferred from database: a conservative draft was created from a catalog
snapshot and inference report.

Validation pending: the draft changed, or no validation report exists for the
current semantic hash.

Validation passed: the current draft has no deployment-blocking validation
issues.

Validation warning: the current draft has non-blocking issues that should be
reviewed before preview or deployment.

Validation blocked: the current draft has deployment-blocking validation issues.

Drift unknown: no current drift report exists for the selected database/catalog
snapshot.

Drift clear: the current model and selected catalog snapshot have no blocking
drift findings.

Drift detected: the model and catalog snapshot have mismatch findings; severity
decides whether preview or deployment is blocked.

Source export incomplete: the draft is reviewable, but normalized YAML export or
source identity is missing.

## Artifact Lifecycle

Artifacts not generated: no artifact set exists for the current draft.

Artifacts stale: an artifact set exists but does not match the current source,
validation hash, drift hash, or generation profile.

Artifacts generated: a manifest and generated artifact set exist for the
current draft and expected hashes.

Artifact warning: optional artifacts are disabled, pending, unsupported, or
contain non-blocking conformance rows.

Artifact blocked: required generation failed, required hashes mismatch, or a
production guard blocks use of the artifact set.

## Preview Lifecycle

Preview not created: no preview build exists for the artifact set.

Preview creating: a create-preview mutation or build process is in progress.

Preview ready: the preview build has a live endpoint, candidate artifact set,
expiration metadata, current manifest, and Titan GAP-005 artifact verification
evidence for deployment-ready claims.

Preview testing: contract tests or sample query tests are running against the
preview endpoint.

Preview failed: preview creation or contract tests failed.

Preview expired: the preview is no longer live; historical details remain
visible, but query execution and deployment use are disabled.

Preview blocked: validation, drift, stale artifacts, missing candidate routing,
or operation enforcement prevents trustworthy preview.

## Operation Lifecycle

Operation observed: traffic or preview tests created an observed operation fact.

Operation unknown: the operation has been observed but has no approval or
rejection decision in the relevant scope.

Operation approved: a reviewer approved the operation for model, environment,
role, and client scope.

Operation rejected: a reviewer rejected the operation for scope and reason.

Enforcement observing: unknown operations are recorded but not warned or
blocked.

Enforcement warning: unknown or risky operations produce warnings but continue.

Enforcement blocked: rejected or unknown operations block execution in enforce
mode.

Operation stale: operation status was evaluated against a different schema,
environment, role, client, or version than the current context.

## Usage Lifecycle

Usage unavailable: no usage report exists for the scope.

Usage current: usage report freshness meets the workflow requirement.

Usage stale: usage exists but is older than the workflow allows.

Usage warning: active use of deprecated fields, slow operations, or policy
rejections require review.

Usage blocked: usage impact creates a release blocker, such as removing a field
still used by active clients without accepted risk.

## Deployment And Health Lifecycle

Deployable: current validation, drift, artifact, preview, operation, usage, and
review gates satisfy environment policy.

Deployment blocked: at least one required gate blocks promotion.

Deploying: deployment mutation or promotion process is in progress.

Deployed: deployment record is active for the environment through Titan GAP-006
deployment activation evidence.

Health unknown: no current runtime health signal exists.

Health healthy: runtime endpoint aligns with active deployment, schema hash,
artifact hash, and registry mode.

Health warning: runtime is reachable but has stale, partial, or non-blocking
mismatch signals.

Health mismatch: runtime state does not align with the deployment record,
artifact set, schema hash, registry mode, or endpoint expectation.

Rollback available: a known-good previous deployment exists and is eligible.

Rollback blocked: no trustworthy target exists, or the target violates current
environment constraints.

Rolled back: active deployment changed to the rollback target with audit
metadata.

## Severity Model

Info: useful context that does not require action.

Warning: action may be needed before preview, review, deployment, or consumer
adoption; workflow can continue when policy allows.

Blocked: the current workflow action cannot proceed until the issue is fixed or
an explicit authorized risk decision is recorded.

Critical: runtime or deployment state is actively unsafe, inconsistent, or
unavailable; operator action is required.

Unknown: the UI lacks enough current evidence to label the state pass, warning,
or blocked.

Severity applies consistently across validation, drift, preview, operation
registry, usage, deployment, and runtime health:

- Validation blocked: semantic issue prevents preview or deployment.
- Drift blocked: database/catalog mismatch makes generated behavior unsafe.
- Preview blocked: candidate endpoint or tests are not trustworthy.
- Operation blocked: registry enforcement rejects or blocks operation.
- Usage blocked: impact evidence blocks removal or policy change.
- Deployment blocked: promotion gates are incomplete or failed.
- Health critical: active runtime mismatch or endpoint failure affects running
  service.

## Freshness Rules

Validation freshness is tied to draft semantic hash.

Drift freshness is tied to draft semantic hash, database binding, environment,
and catalog snapshot.

Artifact freshness is tied to draft semantic hash, validation hash, drift hash,
and generation profile.

Preview freshness is tied to artifact set, candidate registration, endpoint
status, contract-test report, and expiration.

Operation freshness is tied to model, environment, role, client, schema version,
and operation hash.

Usage freshness is tied to model, environment, version, role/client filters, and
report timestamp.

Deployment health freshness is tied to active deployment, endpoint, runtime
check timestamp, schema hash, artifact hash, and registry mode.

## Action Gating

- Generate artifacts requires validation not blocked.
- Create preview requires current artifact set and no preview-blocking
  validation/drift/artifact issue.
- Run preview contract tests requires preview ready and not expired.
- Approve deployment requires current validation, drift, artifacts, preview,
  operations, usage, and review policy.
- Rollback requires rollback available and target health/evidence.
- Consumer query execution requires active or preview endpoint available,
  role/client context, and operation enforcement result.
