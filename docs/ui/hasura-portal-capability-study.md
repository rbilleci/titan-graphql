# Hasura Portal Capability Study

Status: draft.

This study resets the Titan GraphQL UI direction after the first clickable
prototype review. The prototype variants were useful as negative signal: they
looked like generic admin dashboards instead of a native API portal. This
document summarizes Hasura DDN portal and console capabilities and maps the
lessons back into Titan UI planning.

## Sources Reviewed

- Hasura DDN project tutorial:
  `https://hasura.io/learn/graphql/hasura-v3/create/`
- Hasura DDN launch overview:
  `https://hasura.io/blog/launching-hasura-ddn`
- Hasura DDN preview deployments:
  `https://hasura.io/blog/preview-deployments-on-ddn`
- Hasura DDN platform dashboard docs:
  `https://hasura.io/docs/3.0/observability/built-in/platform-dashboard/`
- Hasura DDN traces docs:
  `https://hasura.io/docs/3.0/observability/built-in/traces/`
- Hasura DDN metrics docs:
  `https://hasura.io/docs/3.0/observability/built-in/metrics/`
- Hasura DDN Connector Hub:
  `https://hasura.io/connectors`
- PromptQL product and billing pages:
  `https://hasura.io/promptql`
  and `https://hasura.io/promptql-billing-terms`

## Capability Inventory

### Metadata-First Supergraph

Hasura positions DDN around metadata as the product object, not around a
deployment checklist. A project contains metadata files that define API
behavior, relationships, permissions, and subgraphs. The console is a viewer and
client for this metadata-backed API shape.

Design implication for Titan: the first screen should help users understand the
API model and its generated API surface. Deployment readiness is supporting
evidence, not the default center of gravity.

### API Portal And Documentation

Hasura DDN uses metadata to generate API onboarding surfaces: documentation,
ERD diagrams, relationship graphs, an API testing portal, and semantic
information for consumers. This is the strongest signal for Titan's portal
direction.

Design implication for Titan: Portal Schema Docs should move from a secondary
surface to the primary review target. The portal must show model shape,
relationships, role-visible fields, filters, samples, endpoint/build identity,
and operation status as one coherent API exploration experience.

### GraphiQL And Supergraph Explorer

Hasura's DDN tutorial and preview-deployment flow route users into a Console URL
for a specific build, where GraphiQL and the Supergraph Explorer let users try
that version of the API. The explorer is not only a query box; it anchors the
user in a build-specific API.

Design implication for Titan: the sample-query surface should become an
exploration workspace with endpoint/build context, role/client headers,
operation status, response preview, trace/report links, and copyable client/CI
snippets.

### Builds, Preview URLs, And Applied Project API

Hasura DDN treats a build as an immutable API state. Each build has a unique API
URL, while a project API can point at the currently applied build. Builds can be
used for pull-request previews and then applied after merge.

Design implication for Titan: the first-slice lifecycle should be expressed as
`draft source -> immutable build/preview -> applied active API`, rather than as
a generic deployability board. Rollback should be framed as choosing a previous
known build or deployment identity.

### Connector Hub And Data Source Setup

Hasura exposes connectors through a hub and standardizes connector setup,
configuration, capabilities, deployment, and observability. Connector capability
support shapes the API that can be generated.

Design implication for Titan: database binding, capability support, unsupported
features, drift, and generated artifacts should appear as API-shape constraints
inside the portal and model workspace, not only as hidden diagnostics.

### Security, Permissions, And Documentation Coverage

Hasura's platform dashboard reports authentication status, model and field
permission coverage, command restriction coverage, and documentation coverage.
These are product-health metrics for the API, not merely operational metrics.

Design implication for Titan: the overview should include portal-readiness
coverage: auth/role visibility, policy coverage, documented fields, documented
operations, undocumented or unrestricted surfaces, and consumer-facing
description gaps.

### Usage, Reliability, Metrics, And Traces

Hasura exposes built-in metrics for request rate, latency, and error rate, and
query traces with topology, spans, models used, commands accessed, query plans,
and connector spans. The platform dashboard also summarizes requests per day,
deprecated metadata, and project/subgraph access distribution.

Design implication for Titan: usage and tracing should be connected to schema
concepts and sample queries. The UI should answer "who uses this field or
operation, how healthy is it, and what happened when I ran it?" rather than only
"is deployment blocked?"

### PromptQL Playground

PromptQL adds a natural-language playground and agent/program APIs for querying
connected data through a semantic layer. It is not necessarily first-slice for
Titan, but it shows that modern API portals are moving toward guided data
interaction, not just static docs.

Design implication for Titan: reserve space for guided query assistance or
semantic examples, but do not make AI a first-slice dependency.

## Reframed Titan Product Center

The Titan UI should be an **API portal and build explorer with evidence-backed
governance**, not an operator console with a portal tab.

The primary first user moment should be:

> "I have a generated or preview GraphQL API. Show me what it exposes, how I can
> use it, which build/version I am looking at, what is safe for my role/client,
> and what evidence explains warnings or blockers."

## Capability Mapping To Titan

- Hasura project -> Titan workspace/model.
- Hasura subgraph/data domain -> Titan model/domain boundary.
- Hasura metadata files -> Titan YAML source plus normalized model record.
- Hasura build -> Titan preview build or immutable generated artifact state.
- Hasura applied Project API -> Titan active deployment endpoint.
- Hasura GraphiQL/Supergraph Explorer -> Titan Portal Explorer.
- Hasura ERD/relationship graph -> Titan relationship graph metadata view.
- Hasura platform dashboard -> Titan portal health and evidence coverage.
- Hasura traces/query plan -> Titan sample query trace and execution evidence.
- Hasura Connector Hub/capabilities -> Titan database binding and capability
  coverage.

## Design Corrections

- Lead with portal exploration, not deployment readiness.
- Treat builds/previews as the central lifecycle identity.
- Make relationship graph, docs coverage, role visibility, and samples
  first-class.
- Move operator gates into an evidence sidebar or review lane.
- Connect every warning to an API concept visible in the portal.
- Make "run query -> inspect response/errors/trace -> copy client/CI handoff"
  the most important interaction.
- Keep Git/CI reproducibility visible, but as a developer affordance layered
  into build and evidence surfaces.

## Revised Prototype Requirement

The next prototype should be called **Portal Build Explorer**. It should cover:

1. Model/API landing page centered on active and preview API surfaces.
2. Build selector with active, preview, and rollback/previous build identity.
3. Schema docs with role/client lens, descriptions, deprecations, and filters.
4. Relationship graph or metadata graph anchored to selected schema concepts.
5. Query explorer with sample query, variables, headers, response preview,
   operation status, and trace/evidence links.
6. Portal health panel with documentation coverage, permission coverage,
   deprecated metadata, usage, latency, and error signals.
7. Evidence drawer for source diagnostics, drift, artifact hashes, contract
   tests, Git/CI links, and backend-owned governance status.

Deployment, rollback, and operation approval controls should remain secondary
review actions until the portal exploration loop is right.
