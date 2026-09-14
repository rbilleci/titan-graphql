# Apollo GraphQL Product Synthesis

Status: accepted reference for UI planning.

This study complements `docs/ui/hasura-portal-capability-study.md`. Hasura
helps Titan find the portal and build-explorer shape. Apollo GraphOS helps
Titan name the graph control-plane concepts around that portal: schema history,
checks, proposals, launches, contracts, operation safelisting, usage insights,
traces, and CLI/CI parity.

## Sources Reviewed

- GraphOS schema management:
  `https://www.apollographql.com/docs/graphos/platform/schema-management`
- GraphOS persisted queries and safelisting:
  `https://www.apollographql.com/docs/graphos/operations/persisted-queries`
- GraphOS operation metrics:
  `https://www.apollographql.com/docs/graphos/platform/insights/operation-metrics`
- GraphOS field usage:
  `https://www.apollographql.com/docs/graphos/platform/insights/field-usage`
- GraphOS reporting:
  `https://www.apollographql.com/docs/graphos/routing/observability/graphos/graphos-reporting`
- GraphOS platform overview:
  `https://www.apollographql.com/docs/graphos/platform`

## Product Concepts To Borrow

### Graph Variants And Graph Refs

Apollo treats a graph as a long-lived product with variants such as staging,
production, preview, and contract-specific variants. The graph reference is the
stable handle used by Studio, Router, Rover, and CI.

Titan implication: active, preview, staging, production, and previous/rollback
surfaces should be explicit lenses on the same model/API. The UI should avoid
showing these as unrelated deployments.

### Schema Registry And Lineage

GraphOS makes schema history a central product object. Schema publications are
tracked, checked, launched, and inspected over time.

Titan implication: the portal should show schema lineage, not just the current
schema. A user should be able to trace `draft source -> generated artifact ->
preview build -> active API -> previous build` as one lineage.

### Schema Checks, Linting, And Proposals

Apollo's governance model separates automated checks, schema linting, and
human-review proposals. These give graph changes a clearer lifecycle than a
generic review queue.

Titan implication: validation, drift, semantic compare, usage impact, and
operation compatibility should appear as graph checks. Human review should be a
proposal/approval flow attached to the API change, not a disconnected dashboard.

### Launches

Apollo launches provide a monitored event around publishing schema changes and
related configuration.

Titan implication: replace generic "deployment review" language with launch
language where appropriate. A Titan launch can represent applying a preview
build or promoting generated artifacts to an active API, with evidence attached.

### Contracts

GraphOS contracts let teams expose subsets of a supergraph to different
consumers. Contracts are not merely UI filters; they are consumer-facing API
products.

Titan implication: role/client schema views should become contracts. A contract
lens should explain which fields, operations, samples, and policies are visible
for a consumer group and why.

### Persisted Query Lists And Safelisting

Apollo's persisted query list model gives trusted operations a lifecycle:
registered operations can be audited, safelisted, and eventually required by id
only.

Titan implication: the operation registry should evolve from
approved/rejected/unknown into an operation-control lifecycle:
observed -> registered -> audit mode -> safelisted -> id-only. This maps better
to client release, production safety, and policy enforcement.

### Insights, Client Awareness, Field Usage, And Traces

GraphOS connects schema safety to real usage. Operation metrics, field usage,
client/version awareness, traces, errors, latency, and query plans explain
whether a schema change is safe.

Titan implication: schema docs and compare views should show clients,
operations, field usage, error/latency behavior, and trace/query-plan evidence
next to the schema concept being reviewed.

### Rover CLI And CI Parity

Apollo's Rover CLI keeps Studio and CI aligned: publishing schemas, running
checks, fetching graph artifacts, and publishing persisted query manifests can
be automated.

Titan implication: every UI-visible state should have a reproducible command,
API query, report JSON, or generated artifact reference. Developer integrators
should never have to reverse-engineer a UI state.

### Explorer And Operation Collections

Apollo Explorer is a graph work surface, not only an ad-hoc query input. Saved
operations, collections, headers, variables, and sharing make operations into
product assets.

Titan implication: the Query Explorer should support curated samples,
operation status, collection/group ownership, copyable client snippets,
copyable CI snippets, and operation registration or review handoff.

## Combined Hasura + Apollo Synthesis

Hasura answers the shape question:

- Lead with an API portal.
- Make build-specific API identity obvious.
- Put docs, relationship graph, query tryout, usage, traces, metrics, and
  connector capability evidence in one exploration flow.

Apollo answers the governance and lifecycle question:

- Treat the graph as a governed product with variants, schema lineage, checks,
  proposals, launches, contracts, persisted operations, client-aware usage, and
  CI parity.

Together, they suggest this Titan product model:

> Titan is a Portal Build Explorer with a Graph Control Plane behind it.

The next prototype should therefore show:

1. Portal build landing for the `customer360` API.
2. Active, preview, previous, and contract lenses.
3. Schema docs and relationship graph as the primary comprehension surfaces.
4. Query Explorer with operation status, collection/sample ownership, response
   evidence, trace/query-plan links, and copyable client/CI snippets.
5. Graph checks for validation, drift, semantic compare, contract compatibility,
   operation safety, and usage impact.
6. Operation-control lifecycle inspired by persisted-query safelisting.
7. Launch/proposal evidence for promoting a preview build to active.
8. Evidence drawer for Titan-specific source, artifact, report, Git, CI,
   runtime health, and rollback records.

## Design Corrections

- Replace "operation review queue" with "operation control lifecycle".
- Replace "role/client filter" with "contract lens" where the UI shows a
  stable consumer-facing API subset.
- Replace "deployment review" with "launch evidence" when reviewing promotion
  of a build.
- Make field usage, client usage, traces, latency, errors, and query plans
  local to schema docs and query explorer, not only global analytics.
- Add "copy reproduce command" and "copy CI check" affordances anywhere the UI
  shows checks, schema diffs, operation status, or build identity.
- Keep Apollo federation terms out of Titan unless Titan implements them; borrow
  the product semantics, not the federation-specific vocabulary.
