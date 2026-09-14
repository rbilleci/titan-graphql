# Titan GraphQL UI Personas

Status: accepted.

This document expands the UI1 persona set from `docs/ui/roadmap.md`. It defines
who the Titan GraphQL UI must serve before workflow mapping, information
architecture, screen inventory, and visual design begin.

The UI is a client of `/admin/graphql`. These personas should be able to trust
the same source truth, generated artifacts, preview builds, operation registry,
usage reports, and deployment records that automation and CI use.

## Persona Summary

| Persona | Primary frame | Main outcome |
| --- | --- | --- |
| Platform operator | Environment and runtime safety | Deploy, observe, and roll back with confidence |
| API producer | Model source and generated API shape | Produce a valid, reviewable, previewable model |
| API consumer | Schema use and client integration | Find safe queries and understand role-visible API behavior |
| Reviewer / governance owner | Compatibility, policy, and operational risk | Approve changes and operations with evidence |
| Developer integrator | Git, CI, and automation parity | Reproduce UI-visible state outside the UI |

## Platform Operator

Owns environments, deployment safety, runtime health, rollback paths, and policy
boundaries. The operator may not author the model, but they are accountable for
whether a candidate can become the active runtime surface.

Jobs-to-be-done:

- See which model version, artifact set, and deployment are active in each
  environment.
- Decide whether a draft or preview is safe to promote.
- Understand validation, drift, preview, contract-test, operation-registry,
  usage, deployment, and runtime-health signals in one operational context.
- Roll back to a known-good deployment when runtime or compatibility signals
  indicate risk.
- Diagnose production mismatch before dropping into raw logs or internal store
  records.

Key decisions:

- Is the candidate deployable now, blocked, or safe only with warnings?
- Which blocker must be fixed before promotion?
- Is the running deployment still aligned with its source model, database
  binding, generated artifacts, and operation policy?
- Is rollback available, recent enough, and safer than continuing forward?
- Does an incident require producer, governance, database, or platform action?

Titan signals needed:

- Active deployment, previous deployment, environment, version, model id,
  artifact set id, schema hash, and deployment timestamp.
- Validation report status and deployment-blocking diagnostics.
- Drift report status for database/model binding mismatches.
- Preview build status, endpoint metadata, expiration, and preview
  contract-test results.
- Operation registry mode and rejected or unknown operation counts.
- Usage report summaries for slow operations, policy rejections, deprecated
  field use, and affected roles or clients.
- Runtime health status, health mismatch details, and rollback metadata.

Success criteria:

- The operator can explain why a deployment is safe, blocked, or rolled back
  without reading source files first.
- Promotion and rollback decisions cite concrete Titan records rather than
  private UI state.
- Blocking diagnostics name the affected model path, artifact, operation,
  environment, or runtime signal.
- The operator can hand a precise fix request to the right owner.

Anxieties and failure modes:

- A schema deploys while drift, validation, or operation policy should have
  blocked it.
- Rollback exists in theory but cannot be trusted in the current environment.
- Preview behavior differs from the active runtime route.
- Warnings hide a deployment-impacting issue.
- The UI presents stale health or usage information as current.

Anti-goals:

- Become the authoring surface for every model detail.
- Force operators to review generated SQL line by line for routine deploys.
- Replace CI, audit logs, or Git review.
- Create a deployment path that bypasses `/admin/graphql`.

Should not need to understand:

- Internal Java projection descriptors.
- Parser, planner, or SQL-lowering implementation details.
- How deterministic artifact hashes are computed.
- The full YAML grammar when only assessing deployment safety.

## API Producer

Owns the model source, schema shape, database bindings, policies, relations,
generated artifacts, and compatibility expectations. The producer turns product
data needs into a Titan GraphQL model that can be reviewed and operated.

Jobs-to-be-done:

- Import, inspect, or revise `titan.graphql.yaml`.
- Validate the model source and fix diagnostics at the source location.
- Confirm that roots, object types, fields, relations, filters, sorts, Relay
  metadata, policies, and context filters expose the intended API.
- Review generated SDL, introspection, conformance, generated SQL metadata, and
  artifact manifests before preview.
- Create preview builds that reviewers and consumers can test.
- Resolve drift and unsupported capability diagnostics without guessing which
  source concept caused them.

Key decisions:

- Does this model accurately represent the database and the intended public API?
- Which validation or drift issues must be fixed now versus deferred?
- Is the generated API shape compatible with existing clients?
- Is the preview ready for contract tests and human review?
- Which generated artifacts should be shared with reviewers, CI, or consumers?

Titan signals needed:

- Source document identity, model metadata, semantic hash, source locations, and
  normalized export metadata.
- Validation issue code, severity, blocking status, source location, model path,
  and suggested owner.
- Drift report entries for missing tables, missing columns, type/nullability
  mismatches, primary-key problems, index gaps, computed binding drift, and
  relation join problems.
- Generated artifact set metadata, artifact manifest hashes, SDL, introspection,
  conformance, generated SQL metadata, and unsupported capability notes.
- Preview build manifest, preview endpoint metadata, expiration, and contract
  test results.
- Operation and usage impact summaries for changed fields and relations.

Success criteria:

- The producer can move from a diagnostic to the source concept that needs work.
- Generated artifacts are inspectable and reproducible before deployment.
- Preview builds clearly reflect the chosen draft and artifact set.
- Unsupported capabilities are explicit enough to drive a model change or a
  roadmap decision.

Anxieties and failure modes:

- The UI hides a source-location problem behind generic validation text.
- Generated artifacts look plausible but do not correspond to the current
  source.
- Drift diagnostics are hard to distinguish from normal model edits.
- Consumers discover a compatibility problem only after deployment.
- UI edits or imports diverge from Git-visible source.

Anti-goals:

- Make generated SDL the source of truth.
- Encourage broad automatic exposure of every database table.
- Hide YAML and artifact review behind an opaque wizard.
- Invent UI-only model semantics that automation cannot reproduce.

Should not need to understand:

- Runtime deployment internals.
- The full management storage implementation.
- SQL transpiler mechanics beyond generated artifact inspection.
- Operation-registry enforcement internals unless a schema change affects them.

## API Consumer

Uses active or preview schemas to build clients, queries, reports, and
integrations. The consumer needs confidence about what can be queried, which
fields are visible for their role, and whether an operation is approved.

Jobs-to-be-done:

- Browse active and preview schema documentation.
- Discover roots, fields, relations, filters, sorts, pagination, deprecations,
  and role-visible behavior.
- Try sample queries or copy approved operation patterns.
- Understand why a field, relation, or operation is unavailable.
- Compare preview schema behavior with the current active schema before client
  changes are committed.

Key decisions:

- Which query should I use for this feature or integration?
- Is this field available to my role and client?
- Is a preview change safe to adopt now?
- Is my operation approved, rejected, unknown, or only allowed with warnings?
- Do I need to update a client because a deprecated field is still in use?

Titan signals needed:

- Active and preview schema docs, root/type/field metadata, relation metadata,
  filter/sort capability metadata, Relay pagination details, and context-filter
  descriptions.
- Role and client visibility for fields, roots, and operations.
- Approved, rejected, observed, unknown, warning, and blocked operation status.
- Usage and deprecation summaries relevant to the consumer's role or client.
- Sample query metadata, preview endpoint, schema version, environment, and
  changelog or comparison notes.
- GraphQL-shaped error and warning details for rejected or unsupported
  operations.

Success criteria:

- The consumer can form a valid query without reading producer-only model
  internals.
- Role and client visibility are clear before a request fails.
- Preview adoption decisions cite schema version, endpoint, and operation
  status.
- Deprecated and unsupported fields are visible at the point of use.

Anxieties and failure modes:

- A query works in preview but fails in the active environment without a clear
  reason.
- Role-specific visibility is discovered only through trial and error.
- Operation approval status is separate from schema docs and easy to miss.
- Samples drift from the deployed schema.
- The portal exposes operator-only controls or private diagnostics.

Anti-goals:

- Give consumers deployment, rollback, or governance controls.
- Require consumers to understand YAML source, artifact manifests, or drift
  reports for normal schema browsing.
- Present every internal diagnostic as consumer-facing documentation.
- Hide operation approval state from query exploration.

Should not need to understand:

- Model import, validation, artifact generation, or deployment workflows.
- Internal operation-registry storage shape.
- Database binding details behind fields and relations.
- Titan's Java-mode and SQL-mode implementation boundary.

## Reviewer / Governance Owner

Reviews operational risk, compatibility impact, policy changes, usage, approved
operations, and deprecation consequences before promotion. This persona may be a
technical lead, platform owner, security reviewer, or API governance owner.

Jobs-to-be-done:

- Compare draft, preview, and deployed versions by semantic change.
- Review policy, context-filter, visibility, and relation changes.
- Inspect operation registry changes and approve or reject observed operations.
- Evaluate usage analytics, deprecated field use, slow operations, and policy
  rejections before deployment.
- Decide whether a change meets governance, compatibility, and operational-risk
  expectations.

Key decisions:

- Does this change preserve compatibility for important consumers?
- Which operations are new, approved, rejected, or unknown?
- Which roles, clients, fields, and environments are affected?
- Does usage evidence support removing, changing, or deprecating a field?
- Are policy and visibility changes intentional and reviewable?

Titan signals needed:

- Semantic diff between draft, preview, and deployed versions.
- Validation and drift diagnostics with blocking severity.
- Operation registry entries by model, environment, role, client, operation
  hash, operation name, status, and enforcement mode.
- Usage report summaries for operations, fields, roles, clients, environments,
  versions, deprecated fields, unused fields, policy rejections, and slow
  operations.
- Preview contract-test report status, pass/fail/skip counts, response hashes,
  and rejected or unknown operation results.
- Policy, context-filter, field-visibility, deprecation, and relation-change
  metadata.

Success criteria:

- Review decisions are grounded in Titan records that can be audited later.
- Compatibility impact is visible before promotion.
- Operation approval and rejection actions are deliberate and scoped.
- Policy and visibility changes are understandable without reconstructing the
  whole model by hand.

Anxieties and failure modes:

- A breaking schema or policy change appears as a harmless text diff.
- Unknown operations become blocked in enforce mode without review notice.
- Deprecated fields are removed while active clients still depend on them.
- Usage data is stale or missing but appears authoritative.
- Review actions cannot be reproduced through API or CI.

Anti-goals:

- Become the only path for routine producer iteration.
- Replace source review or automated contract tests.
- Require governance owners to inspect raw runtime logs for every decision.
- Approve changes based on UI-only state.

Should not need to understand:

- Low-level parser/planner/executor code.
- Generated SQL internals unless a review explicitly concerns SQL artifacts.
- Storage implementation details for management records.
- Frontend component or layout implementation.

## Developer Integrator

Works Git-first or CI-first and needs the UI to agree with CLI, API, and test
behavior. The integrator connects Titan GraphQL to repository review, local
tooling, CI gates, preview endpoint tests, and automation.

Jobs-to-be-done:

- Correlate UI-visible diagnostics with files, model paths, PRs, and CI output.
- Reproduce validation, drift, artifact generation, preview, and operation
  contract-test results locally or in CI.
- Export normalized source, generated artifacts, and report JSON for review.
- Use preview endpoints from tests and client tooling.
- Confirm that UI actions produce `/admin/graphql` records that automation can
  inspect.

Key decisions:

- Which command, source file, or API call reproduces this UI-visible result?
- What needs to be committed, regenerated, or reviewed in a PR?
- Which artifacts and report JSON belong in CI evidence?
- Is the preview endpoint stable enough for client tests?
- Is a UI workflow compatible with Git-first ownership?

Titan signals needed:

- Source file path, model path, source location, semantic hash, artifact ids,
  artifact hashes, and normalized export metadata.
- Deterministic validation, drift, artifact, preview contract-test,
  operation-registry, and usage report JSON.
- Preview endpoint, preview build id, environment, expiration, and candidate
  artifact set metadata.
- `/admin/graphql` query and mutation contract names for the records and
  actions behind UI state.
- Audit metadata for import, validation, artifact generation, preview creation,
  operation approval/rejection, deployment, and rollback actions.

Success criteria:

- A CI failure and a UI diagnostic point to the same source concept.
- The integrator can export or query enough evidence to reproduce a UI state.
- Preview tests can run without manual UI steps after the preview exists.
- Git-first, UI-first, and hybrid workflows converge on the same model records.

Anxieties and failure modes:

- The UI shows diagnostics that cannot be reproduced from checked-in source.
- Normalized export changes more than intended.
- Artifact hashes change without an explainable source or configuration change.
- Preview endpoint metadata is missing from CI handoff.
- UI-only mutations make automation second-class.

Anti-goals:

- Force all model changes through interactive UI editing.
- Treat CI as less authoritative than a manually inspected screen.
- Hide `/admin/graphql` contracts behind private frontend behavior.
- Require integrators to know frontend implementation details.

Should not need to understand:

- Visual design system decisions.
- Internal frontend state management.
- Management store implementation beyond public API records.
- Titan compiler internals beyond command and artifact outputs.

## Cross-Persona Design Implications

- Every workflow must declare the source of truth it uses: YAML source,
  canonical model record, generated artifact, preview build, deployment,
  operation registry, usage report, or runtime health signal.
- Diagnostics need owner routing. The same blocker may be producer-owned,
  operator-owned, governance-owned, or integrator-owned depending on the signal.
- The consumer portal must share schema and operation facts with the operator
  workbench while hiding private deployment and governance controls.
- Generated artifacts should be inspectable, but routine decisions should lead
  with semantic summaries and precise blockers.
- UI-visible state must be reproducible through `/admin/graphql`, files, report
  JSON, preview endpoints, or CI commands.
- Later workflow mapping should explicitly connect each workflow to at least one
  primary persona and one handoff persona.
