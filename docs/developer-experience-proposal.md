# Titan GraphQL Developer Experience Proposal

Status: proposal for review.

This document proposes the next product and developer-experience direction after
the completed bounded query-contract roadmap. It assumes:

- Titan GraphQL has a query model that is complete enough for the next phase.
- The projection model remains the source of product semantics.
- Titan should provide its own GraphQL management API for configuring,
  validating, reviewing, deploying, and observing models.
- A future Titan management UI should use that same API.
- Human-authored model import/export should be supported through a readable
  schema file format.
- The system should eat its own dogfood: management workflows should prove the
  same query model, minimal mutation runtime, generated schema, policy model,
  and artifact lifecycle that application users rely on.

The proposal is intentionally broad. It is not yet an implementation roadmap.
It is meant to define the experience, boundaries, and product concepts that the
next roadmap can turn into milestones.

## Executive Summary

The completed GraphQL work proved that Titan can host a credible bounded query
engine:

- The projection metamodel can describe roots, object projections, fields,
  relations, capabilities, visibility, generated SDL, and planning metadata.
- The query contract covers normal client behavior for the bounded demo surface:
  operation selection, GraphQL-shaped responses and errors, variables, aliases,
  fragments, directives, generated filters and sorts, Relay pagination, counts,
  computed fields, context filters, introspection, and Quarkus HTTP transport.
- The implementation has Java-mode reference behavior and SQL-mode parity for
  accepted demo-blog query shapes.

The next risk is not raw query semantics. The next risk is adoption.

Today, configuring a model means writing internal Java descriptors such as
`DemoBlogGraphqlSchema.projectionModel(policy)`. That is explicit and useful for
a proof, but it is not yet a product-facing developer experience. An application
team should not need to understand every internal descriptor class before they
can safely expose a model.

The recommended next direction is:

1. Define a developer-facing model format.
2. Define a management GraphQL API around model drafts, validation, generated
   artifacts, deployments, and observability.
3. Expose that API at `/admin/graphql`, separate from application `/graphql`.
4. Make a future management UI a client of that same API.
5. Keep YAML as the human-authored import/export format, with canonical JSON as
   the normalized internal representation.
6. Add conservative schema inference and drift detection to connect the model to
   real database schemas safely.
7. Add preview API builds so candidate artifact sets can be exercised before
   they become the stable application endpoint.
8. Add an operation registry for observed, reviewed, and approved client
   operations.
9. Treat the future UI as both an operator workbench and an API consumer portal.
10. Use usage analytics as governance metadata for fields, operations, clients,
    roles, and deprecations.
11. Reserve explicit model modules/subgraphs for multi-team ownership.
12. Make editor-grade YAML authoring a first-class part of DX, not a later UI
    afterthought.
13. Keep runtime deployment as compiled/versioned artifacts rather than editable
   ad hoc database metadata.

In short:

```text
developer YAML
  -> import
  -> canonical model IR
  -> validation and preview
  -> generated SDL / introspection / conformance / SQL artifacts
  -> candidate preview endpoint
  -> operation contract tests and portal review
  -> reviewed deployment
  -> runtime GraphQL endpoint

management UI
  -> /admin/graphql management API
  -> the same drafts, validation reports, artifacts, preview builds, deployments,
     operation registry, usage analytics, and runtime health
```

## Current State

The current implementation has two important foundations.

First, it already has a projection metamodel:

- `ProjectionModel`
- `ProjectionRetrieval`
- `ProjectionType`
- `ProjectionField`
- `ProjectionRelation`
- `ProjectionGraphqlAdapter`

Those classes let the demo model describe roots, object types, table bindings,
columns, computed fields, relations, Relay capabilities, filters, sorts, context
filters, and field policies. That model is adapted into `GraphqlSchema`, which
drives validation, generated SDL, introspection, planning, and execution.

Second, it already has a query contract that is complete enough for a
production-shaped bounded surface:

- query operations, including named and selected operations
- explicit mutation/subscription rejection
- request envelopes with `query`, `operationName`, `variables`, and
  `extensions`
- GraphQL-shaped `data` / `errors` output with stable error codes
- scalar and structured variables
- aliases and response-key conflict rules
- fragments and runtime directives
- generated filter and sort input objects
- Relay connections, cursors, and counts
- computed fields
- context filters
- stable introspection when enabled
- Quarkus `POST /graphql` and query-only `GET /graphql`

The current developer-facing weakness is not capability. It is packaging.
Everything important is still too close to internal Java descriptor code.

## Goals

The developer experience should make Titan GraphQL feel like a product that a
team can adopt, review, deploy, and operate.

### Primary Goals

- Let developers define models in a readable, reviewable source format.
- Preserve a typed, validated, compiled representation for runtime.
- Make model changes inspectable before deployment.
- Provide a management GraphQL API for model lifecycle operations.
- Use `/admin/graphql` for the management API and reserve `/graphql` for
  application/runtime schemas.
- Use that management API for the future UI rather than creating a private UI
  backdoor.
- Build a minimal general mutation runtime so management mutations prove the
  same write path that future application models can use.
- Support import/export so teams can choose Git-first, UI-first, or mixed
  workflows.
- Support conservative model scaffolding from an existing database schema.
- Detect drift when the underlying database schema changes without matching
  model updates.
- Let validated artifact candidates be served through preview endpoints before
  they are activated.
- Provide an operation registry for observed, approved, and rejected client
  operations.
- Include a consumer portal experience for GraphiQL/explorer, docs, sample
  queries, relationship views, and changelog review.
- Capture usage analytics as product and governance metadata.
- Support modular ownership boundaries inside larger models.
- Provide editor/LSP-grade authoring support for the YAML model format.
- Make generated artifacts first-class review objects.
- Make policy and context filters understandable and safe by default.
- Make deployment/versioning explicit enough for real operations.
- Keep the runtime query engine stable while the management surface evolves.

### Non-Goals

- Do not attempt full general-purpose application CRUD mutation coverage as part
  of this DX roadmap.
- Do not implement arbitrary nested writes, relation mutation graphs,
  subscriptions, or every CRUD convention in the first mutation slice.
- Do not make the runtime depend on editable ad hoc database metadata.
- Do not create a UI-only configuration path that bypasses the API.
- Do not treat generated SDL as the source of truth.
- Do not hide unsupported model features behind silent fallbacks.
- Do not require the first DX milestone to support every future projection
  feature.

## Product Stance

Titan GraphQL should be projection-first, artifact-oriented, and reviewable.

The projection model should remain the source of semantics:

- what data is exposed
- how it maps to tables and retrieval operations
- which fields are selectable
- which filters and sorts are legal
- which relations are reachable
- how pagination works
- which policies apply
- how context filters compose
- what gets generated for SDL, introspection, and runtime SQL

The management API should own the lifecycle of that model:

- create draft
- import YAML
- infer a draft from an existing database schema
- validate
- check database/model drift
- preview generated artifacts
- create a preview API build
- expose that candidate through a temporary preview endpoint
- test approved client operations against the candidate
- compare with deployed versions
- promote to candidate
- deploy
- roll back
- inspect health
- inspect operation usage and registry state
- export current state

The future UI should be a management API client. This matters because the API
will become the durable contract for automation, CI, and operators. A UI that
uses private endpoints would weaken the product.

The management API should not use a proprietary write path that application
models can never use. Its named mutations should be the first consumer of a
minimal general mutation runtime: typed inputs, typed payloads, validation,
authorization, transaction boundaries, error shaping, audit events, and
generated schema metadata. That runtime can begin with explicit command-style
mutations and still avoid broad application CRUD coverage until it has been
proven through Titan's own management model.

Titan should stay conservative where Hasura and similar products often optimize
for immediate breadth. Schema inference should produce reviewed scaffolding, not
automatic public exposure of every table. Mutation support should start with
named command-style mutations, not broad nested CRUD. Preview builds, operation
registries, and usage analytics should make the product safer before it becomes
more expansive.

## Recommended Human-Readable Format

Use YAML as the primary human-authored format.

Use canonical JSON as the normalized internal representation and API payload
shape.

Do not choose TOML as the primary format. TOML is pleasant for flat config, but
the projection model is deeply nested and graph-shaped. YAML is more natural for
types, fields, relations, roots, policies, and generated artifact options.

Do not choose raw JSON as the primary human-authored format. JSON is excellent
for API transport and canonical IR, but it is noisy for humans and weak for
review comments.

Recommendation:

- Human source: `titan.graphql.yaml`
- Canonical IR: `TitanGraphqlModelDocument` JSON
- API transport: GraphQL input objects plus JSON scalar payloads where needed
- Export formats: YAML and canonical JSON
- Optional later support: TOML export for small examples, not the primary
  format

## YAML Format Principles

The YAML model should be:

- explicit, but not verbose for common cases
- stable across exports
- friendly to code review
- ordered deterministically
- validated with precise source locations
- safely round-trippable through canonical JSON
- capable of carrying descriptions, deprecations, tags, and docs metadata
- separate from generated SDL

It should avoid:

- arbitrary executable code
- stringly typed policy expressions where safer named policies exist
- hidden defaults that change runtime behavior materially
- implicit relation traversal without declared budgets
- automatic root exposure for every table
- dynamic resolver plugins in the first version

## Example YAML Model

The following example is not a final schema. It is a concrete target for the DX
discussion.

```yaml
apiVersion: titan.graphql/v1alpha1
kind: ProjectionModel
metadata:
  name: demo-blog
  version: 2026.05.31
  owner: platform
  description: Bounded demo blog model used to prove Titan GraphQL.
  tags:
    - demo
    - query-only

database:
  catalog: demo_blog
  defaultSchema: public
  tables:
    articles:
      physicalName: articles
      primaryKey: id
    users:
      physicalName: users
      primaryKey: id
    comments:
      physicalName: comments
      primaryKey: id

policies:
  fieldPolicies:
    canReadUserEmail:
      description: User email is visible only to admins.
      input:
        actorRole: String
      mode: reject
      expression:
        kind: named
        name: adminOnly

  contextFilters:
    publishedVisibility:
      description: Restrict articles by the published flag when enabled.
      contextKey: articleVisibility
      valueType: Boolean
      failClosed: true
      phase: beforeClientFilters

types:
  Article:
    table: articles
    description: Published and draft blog article.
    fields:
      id:
        column: id
        type: Int
        filter:
          operators: [eq, neq, in, isNull, lt, lte, gt, gte]
        sort:
          default: asc
      title:
        column: title
        type: String
        filter:
          operators: [eq, neq, in, isNull, contains, startsWith, endsWith]
        sort:
          default: asc
      titleLength:
        type: Int
        computed:
          kind: sqlTemplate
          template: "length({title})"
          requiredColumns: [title]
          deterministic: true
          nullable: false
          sensitive: false
          costClass: rowLocal
        selectable: true
        filter:
          operators: [eq, neq, in, isNull, lt, lte, gt, gte]
        sort:
          default: asc

    relations:
      author:
        target: User
        cardinality: one
        nullable: false
        localColumn: author_id
        targetColumn: id
        capabilities:
          selectable: true
          batchable: true
          selectionHopBudget: 2

      comments:
        target: Comment
        cardinality: many
        nullable: false
        localColumn: id
        targetColumn: article_id
        capabilities:
          selectable: true
          batchable: true
          pagination: relay
          totalCount: exact
          selectionHopBudget: 2
          filterHopBudget: 0
          sortHopBudget: 0
          defaultPageSize: 10
          maxPageSize: 100
        sortPaths:
          id:
            column: id
            path: id
            direction: asc
            tieBreaker: id

  User:
    table: users
    fields:
      id:
        column: id
        type: Int
      name:
        column: name
        type: String
      email:
        column: email
        type: String
        policy: canReadUserEmail

  Comment:
    table: comments
    fields:
      id:
        column: id
        type: Int
      body:
        column: body
        type: String
    relations:
      author:
        target: User
        cardinality: one
        nullable: false
        localColumn: author_id
        targetColumn: id

roots:
  article:
    type: Article
    operation: point
    argument:
      name: id
      type: Int
      column: id

  articles:
    type: Article
    operation: connection
    pagination:
      mode: relay
      defaultPageSize: 10
      maxPageSize: 100
      cursor:
        path: id
        column: id
        direction: asc
        tieBreaker: id
    arguments:
      authorId:
        type: Int
        kind: equals
        column: author_id
    filterPaths:
      authorName:
        type: String
        column: author_id
        path: author.name
        hops: 1
        operators: [eq, neq, in, isNull, contains, startsWith, endsWith]
    sortPaths:
      id:
        column: id
        path: id
        direction: asc
      title:
        column: title
        path: title
        direction: asc
      titleLength:
        column: titleLength
        path: titleLength
        direction: asc
      authorName:
        column: author_id
        path: author.name
        hops: 1
        direction: asc
    contextFilters:
      - publishedVisibility

artifacts:
  generatedSchema:
    enabled: true
    path: build/generated/titan-graphql/schema.graphql
  conformance:
    enabled: true
  sql:
    dialects: [postgres]
```

## Canonical Model IR

YAML should not be the runtime model. It should compile into a canonical model
IR.

The IR should be:

- structurally equivalent to the projection metamodel
- independent of comments and YAML formatting
- deterministic
- hashable
- serializable as JSON
- suitable for API transport and artifact signing
- versioned independently from the import format

The management API should expose the canonical model document so automation can
reason about exact semantic state.

Example identity fields:

```json
{
  "apiVersion": "titan.graphql/v1alpha1",
  "kind": "ProjectionModel",
  "metadata": {
    "name": "demo-blog",
    "version": "2026.05.31"
  },
  "fingerprint": {
    "algorithm": "sha256",
    "semanticHash": "..."
  }
}
```

The semantic hash should exclude whitespace, YAML comments, and ordering where
ordering is not meaningful. It should include all behavior-affecting defaults.

## Management GraphQL API

Titan should expose a management GraphQL API for its own model lifecycle.

This API is not the same endpoint as an application's generated runtime
`/graphql` API. It is an administrative API for model authoring and operation.
It can use the same parser, validation philosophy, GraphQL response shape,
authorization concepts, generated schema mechanics, and minimal mutation
runtime.

Use explicit endpoint separation:

- `/graphql`: application/runtime GraphQL generated from the active model
- `/admin/graphql`: Titan management/control-plane GraphQL

The management API should support:

- model import
- model scaffolding from database schema inference
- draft editing
- validation
- drift checks against database schema or migration snapshots
- artifact generation
- artifact preview
- preview API builds and candidate endpoints
- diffs
- operation observation, review, approval, and rejection
- operation cost/depth/field-usage reporting
- usage analytics by model, field, operation, role, client, and environment
- deployment
- rollback
- runtime health
- consumer portal metadata
- model module/subgraph ownership metadata
- export
- audit history

`/admin/graphql` should have separate authentication, authorization,
introspection policy, rate limits, and audit requirements from `/graphql`.

## Minimal Mutation Runtime

The DX roadmap should include the smallest useful general mutation runtime, with
Titan management mutations as its first production-shaped consumer.

This is different from shipping full application CRUD. The first runtime should
support named command-style mutations with:

- explicit mutation fields in generated GraphQL schema
- typed input objects
- typed payload objects
- input coercion and semantic validation
- actor/context-aware authorization
- transaction boundaries
- stable GraphQL error shapes
- idempotency and conflict hooks where operations need them
- audit/event output for successful and failed attempts
- Java-mode reference behavior and SQL/lowered behavior where applicable

Examples:

- `importModelDocument`
- `inferModelFromDatabase`
- `validateModelDraft`
- `generateModelArtifacts`
- `deployModelDraft`
- `rollbackDeployment`
- `runDriftCheck`

This lets Titan dogfood the same write-path concepts that future application
models can use, while still deferring broad application mutation coverage.

The first mutation runtime should explicitly not attempt:

- arbitrary nested writes
- implicit relation mutation graphs
- generated CRUD for every table
- subscriptions
- bulk data import/export APIs
- application-specific domain workflow modeling

### Management API Domains

The management schema should be organized around these domain objects.

#### Workspace

A workspace groups model drafts and deployments.

Fields:

- `id`
- `name`
- `description`
- `createdAt`
- `updatedAt`
- `defaultEnvironment`
- `modelCount`
- `deploymentCount`

#### Model

A model is the long-lived logical product.

Fields:

- `id`
- `name`
- `displayName`
- `description`
- `currentDraft`
- `activeDeployment`
- `latestValidation`
- `latestArtifacts`
- `createdAt`
- `updatedAt`

#### Model Draft

A draft is an editable candidate.

Fields:

- `id`
- `modelId`
- `status`: `EMPTY`, `IMPORTED`, `VALIDATED`, `FAILED_VALIDATION`,
  `READY_FOR_REVIEW`, `DEPLOYED`, `ARCHIVED`
- `sourceFormat`: `YAML`, `JSON`
- `sourceText`
- `canonicalJson`
- `semanticHash`
- `validationReport`
- `artifactSet`
- `createdBy`
- `createdAt`
- `updatedAt`

#### Validation Report

A validation report explains whether a model is safe and complete.

Fields:

- `id`
- `draftId`
- `status`: `PASS`, `WARN`, `FAIL`
- `errors`
- `warnings`
- `infos`
- `summary`
- `sourceLocations`
- `createdAt`

Each issue should include:

- `code`
- `severity`
- `message`
- `path`
- `sourceLocation`
- `suggestedFix`
- `blocksDeployment`

#### Artifact Set

An artifact set captures generated outputs for a draft.

Fields:

- `id`
- `draftId`
- `semanticHash`
- `generatedSchemaSdl`
- `introspectionJson`
- `conformanceMatrix`
- `generatedSql`
- `compatibilityReport`
- `planPreview`
- `createdAt`

#### Preview Build

A preview build serves an artifact candidate before it is activated.

Fields:

- `id`
- `modelId`
- `draftId`
- `artifactSet`
- `environment`
- `status`: `CREATING`, `READY`, `FAILED`, `EXPIRED`, `PROMOTED`
- `previewEndpoint`
- `previewConsoleUrl`
- `schemaHash`
- `operationRegistryStatus`
- `expiresAt`
- `createdBy`
- `createdAt`

Preview builds let CI, UI, and client teams exercise a candidate API without
changing the stable `/graphql` endpoint.

#### Operation Registry

An operation registry records observed and approved client operations.

Fields:

- `id`
- `modelId`
- `environment`
- `operationName`
- `operationHash`
- `document`
- `status`: `OBSERVED`, `APPROVED`, `REJECTED`, `DEPRECATED`
- `roles`
- `clients`
- `depth`
- `estimatedCost`
- `fieldUsage`
- `lastSeenAt`
- `approvedBy`
- `approvedAt`

The registry should support learning mode in development and staging, explicit
review, and enforcement in production. Enforcement can start as warn-only and
become fail-closed per environment.

#### Usage Report

Usage reports summarize how the generated API is actually consumed.

Fields:

- `id`
- `modelId`
- `environment`
- `window`
- `operationsByClient`
- `operationsByRole`
- `fieldsByOperation`
- `unusedFields`
- `deprecatedFieldsInUse`
- `slowOperations`
- `policyRejections`
- `generatedAt`

Usage is not just telemetry. It is governance metadata that helps reviewers
decide whether a model change is safe.

#### Model Module

A model module represents an ownership boundary inside a larger model.

Fields:

- `id`
- `modelId`
- `name`
- `owner`
- `sourcePath`
- `types`
- `roots`
- `policies`
- `validationStatus`
- `compositionIssues`

Modules do not need to imply federation or distributed execution at first. They
can begin as source and ownership boundaries that compose into one deployed
model.

#### Deployment

A deployment records what model version is active in an environment.

Fields:

- `id`
- `modelId`
- `draftId`
- `environment`
- `status`: `PENDING`, `DEPLOYING`, `ACTIVE`, `FAILED`, `ROLLED_BACK`
- `artifactSet`
- `deployedBy`
- `deployedAt`
- `rollbackTarget`
- `runtimeHealth`

#### Runtime Health

Runtime health reports whether the deployed GraphQL model is behaving.

Fields:

- `endpoint`
- `schemaHash`
- `artifactHash`
- `lastValidationAt`
- `lastQueryAt`
- `errorRate`
- `p50LatencyMs`
- `p95LatencyMs`
- `activeDeployments`
- `warnings`

## Management GraphQL Schema Sketch

This is illustrative, not final SDL.

```graphql
type Query {
  workspaces(first: Int, after: String): WorkspaceConnection!
  workspace(id: ID!): Workspace
  model(id: ID!): ManagedGraphqlModel
  modelByName(workspaceId: ID!, name: String!): ManagedGraphqlModel
  modelDraft(id: ID!): ModelDraft
  validationReport(id: ID!): ValidationReport
  artifactSet(id: ID!): ArtifactSet
  schemaInferenceReport(id: ID!): SchemaInferenceReport
  driftReport(id: ID!): DriftReport
  deployment(id: ID!): Deployment
  previewBuild(id: ID!): PreviewBuild
  operationRegistry(modelId: ID!, environment: String!): OperationRegistry!
  usageReport(modelId: ID!, environment: String!, window: UsageWindowInput!): UsageReport!
}

type Mutation {
  createWorkspace(input: CreateWorkspaceInput!): CreateWorkspacePayload!
  createModel(input: CreateModelInput!): CreateModelPayload!
  createModelDraft(input: CreateModelDraftInput!): CreateModelDraftPayload!
  importModelDocument(input: ImportModelDocumentInput!): ImportModelDocumentPayload!
  inferModelFromDatabase(input: InferModelFromDatabaseInput!): InferModelFromDatabasePayload!
  validateModelDraft(input: ValidateModelDraftInput!): ValidateModelDraftPayload!
  runDriftCheck(input: RunDriftCheckInput!): RunDriftCheckPayload!
  generateModelArtifacts(input: GenerateModelArtifactsInput!): GenerateModelArtifactsPayload!
  createPreviewBuild(input: CreatePreviewBuildInput!): CreatePreviewBuildPayload!
  expirePreviewBuild(input: ExpirePreviewBuildInput!): ExpirePreviewBuildPayload!
  approveObservedOperation(input: ApproveObservedOperationInput!): ApproveObservedOperationPayload!
  rejectObservedOperation(input: RejectObservedOperationInput!): RejectObservedOperationPayload!
  markModelDraftReady(input: MarkModelDraftReadyInput!): MarkModelDraftReadyPayload!
  deployModelDraft(input: DeployModelDraftInput!): DeployModelDraftPayload!
  rollbackDeployment(input: RollbackDeploymentInput!): RollbackDeploymentPayload!
  archiveModelDraft(input: ArchiveModelDraftInput!): ArchiveModelDraftPayload!
}

type ManagedGraphqlModel {
  id: ID!
  name: String!
  displayName: String
  description: String
  currentDraft: ModelDraft
  activeDeployment(environment: String!): Deployment
  drafts(first: Int, after: String): ModelDraftConnection!
  deployments(first: Int, after: String): DeploymentConnection!
}

type ModelDraft {
  id: ID!
  model: ManagedGraphqlModel!
  status: ModelDraftStatus!
  sourceFormat: ModelSourceFormat!
  sourceText: String!
  canonicalJson: JSON
  semanticHash: String
  validationReport: ValidationReport
  driftReport: DriftReport
  artifactSet: ArtifactSet
  createdAt: DateTime!
  updatedAt: DateTime!
}

type ValidationReport {
  id: ID!
  status: ValidationStatus!
  summary: String!
  issues: [ValidationIssue!]!
}

type ValidationIssue {
  code: String!
  severity: ValidationSeverity!
  message: String!
  path: String
  line: Int
  column: Int
  suggestedFix: String
  blocksDeployment: Boolean!
}

type ArtifactSet {
  id: ID!
  semanticHash: String!
  generatedSchemaSdl: String!
  introspectionJson: JSON
  conformanceMatrix: String
  generatedSql: [GeneratedSqlArtifact!]!
  compatibilityReport: CompatibilityReport
  driftReport: DriftReport
  planPreview: JSON
  createdAt: DateTime!
}

type PreviewBuild {
  id: ID!
  status: PreviewBuildStatus!
  artifactSet: ArtifactSet!
  previewEndpoint: String!
  previewConsoleUrl: String
  schemaHash: String!
  expiresAt: DateTime
  createdAt: DateTime!
}

type OperationRegistry {
  model: ManagedGraphqlModel!
  environment: String!
  mode: OperationRegistryMode!
  operations(first: Int, after: String, status: OperationStatus): OperationConnection!
}

type RegisteredOperation {
  id: ID!
  operationName: String
  operationHash: String!
  document: String!
  status: OperationStatus!
  depth: Int
  estimatedCost: Int
  fieldUsage: [String!]!
  lastSeenAt: DateTime
}

type UsageReport {
  model: ManagedGraphqlModel!
  environment: String!
  window: String!
  summary: String!
  unusedFields: [String!]!
  deprecatedFieldsInUse: [String!]!
  slowOperations: [RegisteredOperation!]!
}

type SchemaInferenceReport {
  id: ID!
  status: InferenceStatus!
  source: SchemaInferenceSource!
  generatedDraft: ModelDraft
  summary: String!
  issues: [ValidationIssue!]!
}

type DriftReport {
  id: ID!
  status: DriftStatus!
  checkedAgainst: String!
  summary: String!
  issues: [ValidationIssue!]!
  createdAt: DateTime!
}

type Deployment {
  id: ID!
  environment: String!
  status: DeploymentStatus!
  draft: ModelDraft!
  artifactSet: ArtifactSet!
  deployedAt: DateTime
  runtimeHealth: RuntimeHealth
}
```

## Eating Our Own Dogfood

The management API should be built with the same principles as the application
query engine:

- It should expose a generated GraphQL schema.
- It should use Relay connections for lists.
- It should use generated filters and sorts for management objects.
- It should use context filters for tenant/workspace scoping.
- It should use field policies for sensitive fields and operations.
- It should produce GraphQL-shaped errors with stable codes.
- It should expose introspection only under an explicit policy.
- It should produce generated SDL and conformance artifacts.
- It should be tested in Java-mode and SQL-mode where applicable.
- Its named mutations should use the minimal shared mutation runtime rather than
  a private command bypass.

This gives us a real internal customer. If the management UI cannot be built
comfortably on Titan GraphQL, that is a signal that application developers will
also struggle.

Dogfooding should be progressive. The first management API does not need every
admin operation. It should start with model import, validation, artifact preview,
and read-only browsing. Then it should add controlled named mutations for drafts
and deployments through the same minimal runtime future demo applications can
use.

## Future Management UI And Consumer Portal

The UI should be both an operator/developer workbench and an API consumer
portal, not only a visual schema editor.

Expected operator views:

- dashboard of models, drafts, environments, deployments, and health
- model source editor with YAML validation
- visual projection explorer
- generated GraphQL schema preview
- introspection preview
- validation report with source locations
- diff viewer between drafts and deployments
- artifact browser for generated SQL and conformance
- policy/context filter explorer
- deployment review screen
- rollback screen
- runtime health screen

Expected consumer portal views:

- GraphiQL or explorer for active deployments
- GraphiQL or explorer for preview builds
- generated API docs from model metadata
- relationship graph or ERD-style projection view
- sample queries and fragments
- approved operation collections
- field visibility by role
- deprecation and changelog notes
- usage and compatibility notes for a selected model version

The UI should not own model semantics. It should be a client of the management
GraphQL API and should persist changes by calling API mutations.

### UI Design Principles

- Keep the YAML source visible and reviewable.
- Make generated artifacts easy to inspect before deployment.
- Treat warnings and blockers as first-class objects.
- Explain unsupported capabilities precisely.
- Show what changed semantically, not only text diff.
- Show what will be deployed and where.
- Make preview endpoint testing a normal review step.
- Make approved operations and usage visible beside schema changes.
- Make rollback obvious.
- Avoid hiding generated SQL from advanced users.
- Avoid wizard flows that produce opaque models.

## Git-First, UI-First, and Hybrid Workflows

Titan should support three workflows.

### Git-First

Developers edit `titan.graphql.yaml` in a repository.

Flow:

1. Edit YAML.
2. Run local validation.
3. Commit YAML and generated artifacts.
4. CI imports and validates.
5. CI produces artifact preview and an optional preview endpoint.
6. Contract tests run against the preview endpoint.
7. Reviewers approve.
8. Deployment applies the compiled artifact to the stable endpoint.

This is best for platform teams and production models.

### UI-First

Developers or operators use the management UI.

Flow:

1. Create or clone model draft.
2. Edit YAML or guided fields.
3. Run validation.
4. Preview artifacts.
5. Create a preview build.
6. Explore and test the preview endpoint.
7. Mark draft ready.
8. Deploy through approval flow.
9. Export YAML to Git if desired.

This is best for exploration, onboarding, and operational changes.

### Hybrid

Teams import from Git, inspect and validate in UI, then export normalized YAML
or deployment artifacts back into Git.

This is likely the most useful long-term mode.

## Import Pipeline

The import pipeline should be deterministic and explainable.

```text
source text
  -> parse YAML
  -> syntactic validation
  -> canonical JSON IR
  -> semantic validation
  -> projection metamodel
  -> GraphQL schema descriptors
  -> generated artifacts
  -> deployment candidate
```

### Parse Validation

Parse validation checks:

- valid YAML
- known `apiVersion`
- known `kind`
- required sections
- duplicate keys
- scalar types
- enum values
- source locations

### Semantic Validation

Semantic validation checks:

- every root type exists
- every relation target exists
- every table exists in catalog descriptors
- every column exists
- primary keys are valid
- relation join columns type-check
- filters reference selectable/filterable fields
- sort paths reference sortable fields
- hop budgets are respected
- computed expressions are lowerable
- context filters have required context keys
- field policies exist
- visibility rules do not conflict
- Relay cursor paths are stable and deterministic
- total count support is declared only where safe
- generated schema names do not collide
- unsupported features fail explicitly

### Artifact Validation

Artifact validation checks:

- generated SDL is stable
- introspection output matches generated schema policy
- conformance matrix has no unexpected `PENDING` or `JAVA_ONLY` rows for the
  selected support profile
- generated SQL compiles for selected dialects
- Java-mode and SQL-mode equivalence tests exist for accepted features
- deployment compatibility is known

## Schema Inference And Scaffolding

Titan should be able to generate an initial model draft from an existing
database schema. This should be a scaffolding path, not the source of truth.

Useful inference inputs:

- tables and views
- columns and database types
- primary keys
- foreign keys
- nullability
- unique constraints and indexes
- database comments
- schemas/catalogs
- naming conventions
- migration snapshots, when available

Inference output should be a normal model draft, preferably YAML, that can be
reviewed, edited, validated, committed, and deployed like any hand-written
model. The inferred draft should include comments or metadata explaining what
was inferred and what still needs a human decision.

Inference should be conservative by default:

- do not expose every table automatically as a public root
- do not make sensitive-looking columns public without review
- do not infer authorization policy beyond placeholders
- do not infer unlimited relation traversal
- do not assume every foreign key should become a public GraphQL relation
- do not turn database names into final GraphQL names without review
- do not deploy inferred output without normal validation and approval

The management API should expose inference as a named mutation such as
`inferModelFromDatabase`. The UI can then offer a guided first-draft workflow
without owning private schema-generation behavior.

## Drift Detection

Drift detection should be a first-class validation and operations feature.

Drift happens when the database schema, migration source, or deployed runtime
artifacts no longer match the model's assumptions. This is different from a
model syntax error: the model may still parse, but its bindings may no longer be
true.

The drift checker should compare the model against a selected source:

- live database schema
- migration snapshot
- generated artifact manifest
- deployed runtime schema/artifact hash

Drift examples:

- table missing or renamed
- column missing or renamed
- database type changed
- nullability changed
- primary key changed
- foreign key changed or removed
- index needed for cursor/sort support disappeared
- computed SQL expression no longer compiles
- relation join no longer type-checks
- generated function/procedure hash differs from deployment metadata

Each drift issue should include:

- severity: `BREAKING`, `WARNING`, or `INFO`
- model path
- database object path
- source location when available
- suggested fix when possible
- whether deployment is blocked

Drift should be visible in:

- local CLI validation
- CI checks
- `/admin/graphql`
- management UI
- deployment review screens
- runtime health summaries

Breaking drift should fail deployment by default. Non-breaking drift should be
reviewable and suppressible only through explicit policy.

## Preview Builds And Candidate Endpoints

Preview builds should be a first-class artifact lifecycle state.

A preview build takes a validated artifact set and serves it through a temporary
candidate endpoint before it is applied to the stable application `/graphql`
endpoint. This closes the gap between "the SDL looks right" and "clients can
actually execute their operations against the candidate."

Recommended behavior:

- a preview build references one artifact set and one environment profile
- the preview endpoint has a unique URL or route
- preview endpoints are clearly marked as non-stable
- preview endpoints can expire automatically
- preview endpoints use the same runtime schema and execution path as activation
- CI can run contract tests against preview endpoints
- the UI can open the consumer portal against active or preview builds
- deployment means applying a reviewed preview candidate to the stable endpoint

Open endpoint shape:

```text
/graphql                         stable active application endpoint
/preview/{previewBuildId}/graphql candidate application endpoint
/admin/graphql                   management/control-plane endpoint
```

The exact route shape can change. The important product capability is that a
candidate build can be exercised as a real GraphQL API before activation.

## Operation Registry And Allow Lists

Titan should provide an operation registry for production hardening.

The registry should track client operation documents by hash, name, role,
client, environment, status, depth, cost, and field usage. It should be able to
collect observed operations in development or staging, then let reviewers
approve those operations for production.

Suggested modes:

- `OBSERVE`: record new operations, but do not block them
- `WARN`: allow unknown operations and emit diagnostics
- `ENFORCE`: reject operations not approved for the current environment/role

The operation registry should integrate with:

- validation reports
- preview build contract tests
- runtime request rejection
- usage analytics
- compatibility checks
- policy review

This is especially useful once schemas become broad. A generated API can expose
many legal query shapes; production clients often need a much smaller approved
contract.

## Usage Analytics As Governance Metadata

Observability should include model-aware usage analytics, not only runtime
latency and error counts.

Useful analytics:

- operations by environment, role, client, and version
- fields and roots used by operation
- fields never used in a selected window
- deprecated fields still in use
- slow operations grouped by model path
- policy rejections grouped by field, role, and context filter
- operation depth and cost distribution
- preview-build contract-test outcomes

Usage analytics should feed back into product governance:

- deprecation decisions
- compatibility classification
- field policy review
- operation allow-list decisions
- schema simplification
- model ownership discussions

This makes Titan more than a schema generator. It becomes a governed API product
surface.

## Modular Metadata Ownership

Large models need ownership boundaries.

The first version does not need federation or distributed execution, but it
should reserve model-level structure for modules, packages, or subgraphs. A
module can own a set of roots, types, fields, relations, policies, and source
files while still composing into one deployed model.

Useful module metadata:

- module name
- owner team
- source path or package
- declared types and roots
- exported types and relations
- consumed types and relations
- policies owned by the module
- validation status
- composition issues

Composition should detect:

- duplicate names
- conflicting field definitions
- incompatible relation assumptions
- policy name collisions
- cross-module dependency cycles
- ownership ambiguity

This should start as a source-organization and review feature. Multi-repo import
and distributed graph composition can come later.

## Editor And LSP Support

If YAML is the source of truth, the editor is the first UI.

The first roadmap should include enough machine-readable schema and diagnostics
for good local authoring:

- JSON Schema for the YAML document shape
- source-location-rich CLI diagnostics
- diagnostics formatted for editor integration
- completion candidates for model-local names
- completion candidates for policies and context filters
- completion candidates for database tables and columns when a catalog snapshot
  is available
- hover text for generated defaults and inherited policy behavior

Later editor features can include:

- go to generated SDL field
- go to database binding
- find references for a policy or relation
- preview compatibility impact inline
- quick fixes for common validation issues

This reduces dependence on the future UI and makes Git-first workflows pleasant
from the start.

## Validation Issue Examples

Examples of useful errors:

```text
TGM-REL-UNKNOWN-TARGET
Article.comments.target references type Comment, but Comment is not declared.
Path: types.Article.relations.comments.target
Line: 84, column: 17
Blocks deployment: true
```

```text
TGM-CURSOR-NON-DETERMINISTIC
Root articles declares Relay pagination, but cursor path title has no unique
tie breaker. Add tieBreaker: id or choose a unique cursor path.
Path: roots.articles.pagination.cursor
Blocks deployment: true
```

```text
TGM-POLICY-MISSING
Field User.email references policy canReadUserEmail, but no such policy exists.
Path: types.User.fields.email.policy
Blocks deployment: true
```

```text
TGM-COMPUTED-SQL-UNLOWERABLE
Field Article.score uses computed expression kind javaOnlyExperimental, but the
target support profile requires SQL lowering.
Path: types.Article.fields.score.computed.kind
Blocks deployment: true
```

## Policy And Context DX

Policies should be named and reusable.

Field policies should support a small set of initial modes:

- `allow`
- `reject`
- `omit`

Recommendation for the first productized experience: default to `reject` for
unauthorized fields. This matches the current safety posture and avoids partial
data complexity.

Context filters should be explicit:

- name
- context key
- value type
- activation mode
- fail-closed behavior
- phase
- affected roots/retrievals
- count/cursor semantics

Examples:

```yaml
policies:
  contextFilters:
    tenantIsolation:
      contextKey: tenantId
      valueType: String
      failClosed: true
      phase: beforeClientFilters
      predicate:
        kind: equals
        column: tenant_id
```

```yaml
roots:
  accounts:
    type: Account
    operation: connection
    contextFilters:
      - tenantIsolation
```

The management API should expose policy reports:

- which fields are policy-gated
- which roots require context
- which filters fail closed
- which queries would be rejected for a sample actor
- which sensitive columns are never fetched for unauthorized actors

## Generated Artifacts

Generated artifacts should be reviewable before deployment.

Artifacts should include:

- generated SDL
- introspection JSON
- conformance matrix
- generated SQL
- plan previews
- model diff
- compatibility report
- policy report
- operation registry report
- usage analytics snapshot
- preview build manifest
- operational runbook hints

### Generated SDL

SDL remains an output, not source of truth.

The system should let users:

- preview SDL for a draft
- diff SDL between versions
- export SDL for client tooling
- run introspection against the same schema
- confirm policy-disabled introspection behavior

### Generated SQL

Generated SQL should be inspectable.

The system should show:

- function/procedure names
- dialect
- schema
- semantic hash
- dependencies
- deployment status
- compile result
- warnings

### Conformance Matrix

Every model should have a model-specific conformance profile:

- accepted features
- intentionally unsupported features
- SQL-lowerable features
- Java-only internal features, if any
- blocked features

For production deployment, the selected support profile should be strict about
unexpected `JAVA_ONLY` or `PENDING` behavior.

## Deployment Model

Runtime should deploy versioned compiled artifacts, not mutable interpreted
metadata.

The management database can store drafts, documents, reports, artifacts, and
deployment records. The runtime application endpoint should execute a selected
artifact version.

Recommended states:

```text
DRAFT
  -> VALIDATED
  -> ARTIFACTS_GENERATED
  -> PREVIEW_BUILD_READY
  -> READY_FOR_REVIEW
  -> DEPLOYING
  -> ACTIVE
  -> SUPERSEDED
```

Failure states:

```text
FAILED_VALIDATION
FAILED_ARTIFACT_GENERATION
FAILED_PREVIEW_BUILD
FAILED_DEPLOYMENT
ROLLED_BACK
ARCHIVED
```

Deployment should record:

- model id
- draft id
- semantic hash
- artifact hash
- environment
- preview build id, when promoted from preview
- actor
- timestamp
- generated SQL hash
- SDL hash
- previous deployment
- rollback target

## Versioning And Compatibility

Model changes need compatibility classification.

Examples:

- additive object field: usually backward compatible
- removing field: breaking
- changing field type: breaking
- tightening max page size: possibly breaking
- adding context filter fail-closed to a root: breaking or security-critical
- changing policy mode from omit to reject: possibly breaking
- changing cursor path: breaking for clients with stored cursors
- changing generated enum values: potentially breaking
- changing computed field semantics: behavior change

The management API should provide:

- text diff
- semantic diff
- SDL diff
- artifact diff
- compatibility classification
- suggested version bump

Suggested classifications:

- `SAFE_ADDITIVE`
- `BEHAVIOR_CHANGE`
- `SECURITY_CHANGE`
- `CLIENT_BREAKING`
- `RUNTIME_BREAKING`
- `UNKNOWN`

## Observability And Operations

The DX should include operational visibility from the start.

Management API should expose:

- active deployment by environment
- schema hash served by endpoint
- generated artifact hash
- preview build status
- operation registry mode and unknown-operation counts
- usage analytics by field, operation, client, role, and environment
- recent validation failures
- recent runtime query errors by code
- slow query shapes
- rejected unsupported query shapes
- context-filter fail-closed counts
- authorization rejection counts
- introspection disabled/enabled status

The UI should make these visible without requiring operators to inspect raw logs.

## Security Model

Management API operations are sensitive.

Minimum roles:

- viewer: read models, drafts, artifacts, and deployments
- editor: create and edit drafts
- validator: run validation and artifact generation
- deployer: deploy validated drafts
- admin: manage workspaces, environments, and policies

Security requirements:

- every mutation records actor and timestamp
- deployment requires explicit role
- sensitive source values are not logged
- model import should not execute arbitrary code
- policy expressions should be constrained or named
- generated SQL should never concatenate user query text
- management introspection should be policy controlled

## Storage Model

The management system may store model lifecycle data in database tables. This is
different from making the runtime query model editable ad hoc inside the DB.

Recommended management storage:

- workspaces
- models
- model drafts
- source documents
- canonical IR documents
- validation reports
- artifact sets
- deployments
- audit events

Runtime should consume:

- selected artifact version
- generated SQL/functions
- schema/artifact hashes
- deployment metadata

This keeps management flexible while preserving compiled runtime behavior.

## Local Developer Tooling

The management API and UI should not be the only path. Developers need local
tools too.

Possible commands:

```bash
titan graphql model validate titan.graphql.yaml
titan graphql model compile titan.graphql.yaml
titan graphql model print-sdl titan.graphql.yaml
titan graphql model diff old.yaml new.yaml
titan graphql model infer --database-url "$DATABASE_URL" --out titan.graphql.yaml
titan graphql model drift-check titan.graphql.yaml --database-url "$DATABASE_URL"
titan graphql model generate-artifacts titan.graphql.yaml --dialect postgres
titan graphql model import titan.graphql.yaml --workspace demo
titan graphql preview create titan.graphql.yaml --environment staging
titan graphql operations list --environment staging
titan graphql operations approve --hash "$OPERATION_HASH" --role user
titan graphql usage report --model demo-blog --window 30d
```

Local tooling should use the same parser and canonical IR as the management API.
It should also emit JSON or structured diagnostics suitable for editors and CI.

## API/UI Dogfood Milestones

The first dogfood target should be intentionally small:

1. Define the management model for managing Titan GraphQL models.
2. Express that management model through the same YAML format.
3. Generate SDL for the management API.
4. Implement read-only management queries.
5. Build the minimal named mutation runtime.
6. Build import/infer/validate/drift-check/artifact-preview mutations.
7. Build a basic UI that uses only management GraphQL.

The UI does not need to deploy production artifacts in the first slice. It
should prove that browsing, importing, validating, and previewing are pleasant.

## Reserved Future Extension Points

The first DX roadmap should not attempt to build a full plugin or connector
ecosystem, but the model should avoid closing the door on a few likely extension
points.

### Connector Capability Manifests

Future connectors may not all support the same filtering, sorting, pagination,
transaction, mutation, or SQL-lowering behavior. The management model should
eventually represent connector capabilities explicitly so validation can explain
why a model is or is not portable across a runtime target.

This is a later concern for Titan GraphQL. The first roadmap can assume the
current supported database/runtime profile, while keeping generated artifact and
validation reports shaped so connector capability checks fit later.

### Typed Custom Commands And Functions

Future models may need named commands backed by code, stored procedures, or
external functions. This is adjacent to the minimal mutation runtime, but should
not be bundled into the first roadmap.

The useful reservation is conceptual: named command-style mutations should have
typed inputs, typed payloads, authorization, validation, transaction semantics
where applicable, and audit output. Whether a command is implemented by Titan
management storage, generated SQL, or an external function can be decided later.

### Lifecycle Hooks

Hasura-style lifecycle hooks suggest useful future extension points such as
pre-parse request policy, operation registry enforcement, caching decisions,
response shaping, and observability enrichment.

Titan should not expose arbitrary hooks until validation and policy boundaries
are mature. When hooks arrive, they should be typed, declared in metadata,
auditable, and unable to bypass model validation or authorization policy.

## Proposed Roadmap Shape

This proposal can become a roadmap with these milestones.

### DX1: Model Document Format

Deliverables:

- `docs/developer-experience-proposal.md`
- initial YAML schema reference
- JSON Schema for editor integration
- example `titan.graphql.yaml`
- canonical JSON IR shape
- parser/validator spike or test fixtures

Exit criteria:

- demo-blog model can be represented as YAML
- YAML can round-trip to canonical JSON
- validation reports source locations
- editors can consume a schema and structured diagnostics for the document

### DX2: Public Projection API

Deliverables:

- public builder or API facade over internal projection descriptors
- stable names for types, fields, roots, relations, policies, and artifacts
- removal of direct package-private descriptor dependency from examples

Exit criteria:

- a non-demo model can be declared without importing internal descriptor classes

### DX3: Import And Validation Engine

Deliverables:

- YAML import pipeline
- conservative schema inference/scaffolding pipeline
- semantic validation
- drift detection against database schema or migration snapshots
- source-location-rich errors
- compatibility with current projection metamodel

Exit criteria:

- invalid model fixtures produce precise errors
- existing database schema can produce a reviewed draft YAML scaffold
- schema/model drift produces precise breaking/warning/info issues
- valid demo-blog YAML compiles into the same generated schema as current Java
  descriptors

### DX4: Generated Artifact Workflow

Deliverables:

- generated SDL artifact
- introspection artifact
- conformance artifact
- generated SQL artifact metadata
- artifact hashing
- artifact diff
- preview build manifest

Exit criteria:

- reviewers can inspect all behavior-affecting outputs before deployment
- a candidate artifact set can be addressed as a preview build

### DX5: Minimal Mutation Runtime And Management API Foundation

Deliverables:

- `/admin/graphql` management schema
- model/draft/validation/artifact queries
- minimal named mutation runtime
- import/infer/validate/drift-check/generate mutations
- preview build create/expire mutations
- operation registry query/mutation foundation
- authorization boundaries
- audit records

Exit criteria:

- model import, inference, validation, drift check, and artifact preview can be
  done through `/admin/graphql`
- preview builds and observed operation review can be initiated through
  `/admin/graphql`
- management mutations use the shared mutation runtime rather than a private
  command bypass

### DX6: Dogfood Management Model

Deliverables:

- represent management API's own model in the projection model format
- generated management SDL
- conformance profile for management API
- read-only management API backed by the same query engine concepts
- named management mutations backed by the minimal mutation runtime

Exit criteria:

- the management API proves the projection and mutation model are useful outside
  demo-blog

### DX7: UI And Consumer Portal Preview

Deliverables:

- basic UI for model list, draft source, validation report, generated SDL, and
  artifact preview
- UI surfaces schema inference and drift reports
- consumer portal view for active and preview builds
- GraphiQL/explorer entrypoint for preview builds
- generated docs, sample queries, and relationship view
- approved operation list and basic usage summary
- UI calls only management GraphQL API

Exit criteria:

- a user can import or infer YAML, validate it, check drift, and inspect
  generated artifacts in the UI
- a consumer can explore active and preview schemas without using private
  endpoints

### DX8: Preview Deployment, Versioning, And Rollback

Deliverables:

- deployment records
- preview API endpoints for artifact candidates
- contract-test hooks against preview endpoints
- compatibility classification
- pre-deployment drift classification
- rollback metadata
- environment status
- runtime health summary

Exit criteria:

- a validated, drift-clean preview build can be contract-tested, promoted, and
  tracked as active for an environment

### DX9: Governance And Modular Ownership

Deliverables:

- operation registry enforcement modes
- allow-list collections by environment, role, and client
- usage analytics reports
- deprecation and field-usage reports
- module/subgraph ownership metadata
- composition conflict detection

Exit criteria:

- production environments can require approved operations
- reviewers can see whether a change affects used fields or approved operations
- larger models can declare ownership boundaries before multi-team edits become
  painful

## Open Questions

- Should YAML support references/includes, or should the first version require a
  single file?
- Should policy expressions be named only at first, or should a small declarative
  predicate language be allowed?
- How small can the first shared mutation runtime be while still proving the
  write path for both `/admin/graphql` and future application schemas?
- Should generated artifacts be committed to Git by default?
- Should UI edits preserve YAML comments, or should comments be treated as
  source-only and lost on canonical export?
- Should the management API and application API share one server deployment or
  be separated operationally?
- Should model drafts be global, workspace-scoped, or environment-scoped?
- How strict should compatibility classification be before deployment?
- Should drift checks use live database introspection, migration snapshots, or
  both for the first implementation?
- Which database schema hints should inference trust, and which should always
  require human review?
- How much generated SQL should be exposed in UI by default?
- What should the first preview endpoint route shape be?
- Should operation registry enforcement start as warn-only, or should production
  default to fail-closed once an allow list exists?
- Which operation cost model is good enough for the first roadmap?
- Are modules only source/ownership boundaries at first, or should they imply
  independent artifact generation?
- How much consumer portal functionality should ship before the operator UI is
  complete?
- Should usage analytics be stored in Titan management tables, exported to an
  observability backend, or both?

## Recommended Decisions For First Roadmap

This proposal recommends these starting decisions:

- Use YAML as the human-authored format.
- Use canonical JSON as internal IR and API interchange where raw model payloads
  are needed.
- Keep generated SDL as an artifact, not a source.
- Build the management API as GraphQL from the beginning at `/admin/graphql`.
- Let the future UI consume only the management GraphQL API.
- Reserve `/graphql` for application/runtime schemas.
- Keep full application CRUD mutations and subscriptions out of scope.
- Build minimal general named mutation runtime support, and use Titan management
  mutations as its first dogfood consumer.
- Add conservative schema inference as a scaffolding path, not source truth.
- Add drift detection as a required validation/deployment capability.
- Add preview builds with candidate endpoints before stable deployment.
- Add an operation registry with observed-operation review and future
  allow-list enforcement.
- Treat the UI as both an operator workbench and an API consumer portal.
- Capture usage analytics as management/governance metadata.
- Add module/subgraph ownership metadata as a source-organization boundary
  before attempting distributed graph composition.
- Ship JSON Schema and structured diagnostics for YAML authoring early.
- Keep runtime artifacts compiled/versioned.
- Store drafts, validation reports, artifacts, and deployments in management
  storage.
- Make source-location-rich validation a first-class feature.
- Start with demo-blog YAML equivalence, then one non-demo model, then
  management API dogfood.

## Success Criteria

The DX roadmap should be considered successful when:

- A developer can author a readable YAML model without touching internal
  descriptor classes.
- The same model can be imported through the management API.
- A starting model can be scaffolded from an existing database schema and then
  reviewed as normal YAML.
- The system can validate it with precise source errors.
- The system can detect database/model drift before deployment.
- The system can generate SDL, introspection, conformance, and SQL artifacts.
- The UI can preview those artifacts through the management API.
- A validated artifact candidate can be served through a preview endpoint before
  activation.
- Approved operations can be registered and reviewed before production
  enforcement.
- Usage analytics can tell reviewers which fields, operations, roles, and
  clients are affected by a change.
- The consumer portal can expose docs, exploration, samples, and relationship
  views for active and preview schemas.
- YAML authoring has schema support and editor-friendly diagnostics.
- Large models can declare source and ownership modules before multi-team work
  requires federation or distributed execution.
- A model can be versioned, compared, deployed, and rolled back.
- The management API itself uses `/admin/graphql`, the same GraphQL principles,
  and the minimal shared mutation runtime.
- The completed workflow makes the next real application integration feel
  boring rather than bespoke.
