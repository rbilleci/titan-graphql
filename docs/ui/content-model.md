# Titan GraphQL UI Content Model

Status: accepted.

This document completes the content and diagnostics portion of UI5 from
`docs/ui/roadmap.md`. It defines vocabulary and message principles for planning
before interface copy or visual design.

## Vocabulary

Use these terms consistently:

- Model: a named Titan GraphQL API model.
- Draft: a source candidate before deployment.
- Source: `titan.graphql.yaml` or normalized model document input.
- Semantic hash: stable identity of the parsed model meaning.
- Validation report: semantic source diagnostics for a draft.
- Drift report: catalog/database mismatch diagnostics.
- Artifact set: generated material tied to a draft.
- Manifest: deterministic artifact set index with hashes.
- Preview build: temporary candidate endpoint for review and tests.
- Active deployment: the currently promoted runtime model in an environment.
- Operation registry: scoped approval/rejection policy for GraphQL operations.
- Observed operation: operation fact learned from traffic or preview tests.
- Usage report: analytics facts for operations, fields, roles, clients,
  environments, versions, deprecations, policy rejections, and slow operations.
- Runtime health: live alignment between deployment record and endpoint state.
- Rollback target: known-good deployment eligible for restoration.

Avoid overloaded terms:

- Use "draft" for source candidates, not "version" by itself.
- Use "artifact set" for generated outputs, not "build" unless it is a preview.
- Use "preview build" for candidate endpoint state, not "deployment".
- Use "active deployment" for running runtime state, not "current schema" alone.
- Use "consumer portal" for schema docs and query tryout, not operator
  workbench.

## Diagnostic Message Shape

Every diagnostic should answer:

- What happened?
- What object is affected?
- Is this blocking, warning, informational, critical, or unknown?
- Which actor likely owns the next action?
- Which Titan record produced this signal?
- How fresh is the signal?
- What can the user do next?
- How can the result be reproduced outside the UI?

Recommended structure:

```text
Blocked: relation target type is missing
Draft demo-blog@sha256:... references Article.author -> Author, but Author is
not declared. Fix model path types.Article or remove the relation, then run
validation again.
```

Do not hide issue codes. Show human text first, then issue code, model path,
source location, report id, and hash where useful.

## State Copy Principles

Empty states should name the missing source truth:

- "No draft exists for this model yet."
- "No usage report exists for this environment."
- "No active deployment exists for this environment."

Loading states should name the record being fetched or action being executed:

- "Loading validation report..."
- "Creating preview build..."
- "Running preview contract tests..."

Blocked states should name the gate:

- "Deployment blocked by validation."
- "Preview blocked by stale artifacts."
- "Query blocked by operation registry enforcement."

Warning states should preserve actionability:

- "Usage report is stale; review can continue, but it is not evidence of no
  impact."
- "Preview expires soon; copy endpoint metadata before sharing."

Success states should cite evidence:

- "Artifacts generated for semantic hash sha256:..."
- "Preview ready for artifact set art_123 until 2026-06-03T12:00:00Z."

Rollback language must be explicit:

- "Rollback available to deployment dep_42."
- "Rollback blocked: no previous healthy deployment is recorded."
- "Rolled back to dep_42; active deployment is now dep_42."

## Persona-Specific Content

Platform operators need deployment, health, rollback, and gate status first.
Source and artifact detail should be available but not lead routine operational
copy.

API producers need source path, model path, validation, drift, generated
artifact, and preview readiness copy.

API consumers need role-visible schema, endpoint, sample query, deprecation,
and operation approval copy. They should not see private deployment controls or
raw governance internals.

Reviewers need compatibility, policy, operation, usage, preview-test, and
deployment-readiness copy with evidence links.

Developer integrators need file, hash, report JSON, command/API, endpoint, and
CI reproduction copy.

## Diagnostics By Domain

Validation diagnostics should include issue code, severity, blocking flag,
model path, source location when available, suggested owner, and next action.

Drift diagnostics should include database binding, table, column, relation,
index, catalog snapshot, model path, severity, freshness, and owner routing.

Artifact diagnostics should include artifact set id, generation profile,
manifest entry, hash, stale dependency, unsupported artifact kind, and export
path.

Preview diagnostics should include preview id, endpoint, expiration, candidate
artifact set, contract-test operation, pass/fail/skip count, response hash, and
operation enforcement outcome.

Operation diagnostics should include operation hash, operation name, role,
client, environment, status, enforcement mode, warning/rejection reason, and
audit event.

Usage diagnostics should include report id, freshness, affected operation,
field, role, client, environment, version, deprecation, rejection, or latency
bucket.

Deployment diagnostics should include environment, candidate artifact set,
active deployment, previous deployment, gate failure, audit event, and rollback
target.

Runtime health diagnostics should include endpoint, active deployment, expected
schema/artifact hash, observed schema/artifact hash, registry mode, freshness,
and mismatch detail.

## Consumer-Facing Boundaries

Consumer portal copy may say:

- "This field is not visible for your role."
- "This operation is not approved for this client."
- "This preview has expired."
- "This field is deprecated and still used by active clients."

Consumer portal copy should not expose:

- private policy expressions
- raw drift internals
- deployment mutation details
- reviewer audit metadata unrelated to the consumer
- operator-only rollback or settings actions

## Reproducibility Language

When a UI result is important, include at least one reproducibility path:

- report JSON id or export action
- `/admin/graphql` query or mutation name
- source file and model path
- artifact manifest path and hash
- preview endpoint and request metadata
- operation hash and role/client/environment scope
- CI command or fixture reference when available

## Glossary Decisions

Use "preview" as a noun and adjective for temporary candidate endpoints.

Use "active" for deployed runtime state.

Use "current" only with a visible scope, such as current draft, current report,
or current environment.

Use "blocked" when an action cannot proceed.

Use "warning" when an action can proceed but risk or review remains.

Use "unknown" when evidence is missing or stale.

Use "freshness" for data age and source matching, not "recency".
