# Titan GraphQL Model Document Format

Status: v1alpha1 source-format reference.

This document defines the first human-authored Titan GraphQL model document
shape. It is the contract developers should read before authoring
`titan.graphql.yaml`.

The format is intentionally a source document, not runtime behavior. DXR1.1 only
documents the YAML shape and examples. It does not add a YAML parser, runtime
loading, SQL lowering, or new dependencies.

## Design Goals

The model document should be:

- readable in code review
- deterministic when exported
- explicit about data bindings, policies, pagination, filters, and sorts
- compatible with the current Java projection model
- compilable later into a canonical JSON IR
- separate from generated SDL, introspection, conformance, and SQL artifacts

Generated GraphQL SDL remains an output artifact. The model document is the
source of truth.

## Top-Level Envelope

Every document uses this envelope:

```yaml
apiVersion: titan.graphql/v1alpha1
kind: ProjectionModel
metadata:
  name: demo-blog
roots: {}
types: {}
```

Fields:

| Field | Required | Description |
| --- | --- | --- |
| `apiVersion` | yes | Must be `titan.graphql/v1alpha1` for this version. |
| `kind` | yes | Must be `ProjectionModel` for application/runtime models. |
| `metadata` | yes | Human, ownership, and version metadata. |
| `modules` | no | Source ownership boundaries for larger models. |
| `database` | no | Database catalog, schema, and table binding hints. |
| `roots` | yes | Public root fields on the generated `Query` type. |
| `types` | yes | Object projection types exposed by roots and relations. |
| `policies` | no | Named field and root policy declarations. |
| `contextFilters` | no | Named request-context filters that compose into roots. |
| `artifacts` | no | Generated output options for reviewable artifacts. |
| `deployment` | no | Deployment and preview metadata. |

Unknown top-level fields are unsupported in v1alpha1 and should be rejected by
future parsers. Extension fields should use `x-<owner>-<name>` only after the
canonical IR can preserve and validate them.

## Metadata

```yaml
metadata:
  name: demo-blog
  version: 2026.05.31
  owner: platform
  description: Bounded demo blog model used to prove Titan GraphQL.
  tags:
    - demo
    - query-only
```

Fields:

| Field | Required | Description |
| --- | --- | --- |
| `name` | yes | Stable model name. Use lower-kebab-case. |
| `version` | no | Source version string. This is not the semantic hash. |
| `owner` | no | Owning team or person. |
| `description` | no | Human description. |
| `tags` | no | Review, lifecycle, or product tags. |

## Modules

Modules are source and ownership boundaries. In v1alpha1 they do not imply
independent runtime deployment or independent SQL artifact generation.

```yaml
modules:
  content:
    owner: content-platform
    roots: [article, articles]
    types: [Article, Comment]
    policies: [canReadUserEmail]
```

Fields:

| Field | Required | Description |
| --- | --- | --- |
| `owner` | no | Team responsible for the module. |
| `roots` | no | Root names owned by this module. |
| `types` | no | Type names owned by this module. |
| `fields` | no | Fully qualified field names, such as `Article.title`. |
| `relations` | no | Fully qualified relation names, such as `Article.author`. |
| `policies` | no | Policy names owned by this module. |

## Database Bindings

The `database` section gives source-level binding hints. Future validation and
drift detection use these names to compare the model with database schemas.

```yaml
database:
  catalog: demo_blog
  defaultSchema: public
  tables:
    articles:
      physicalName: articles
      schema: public
      primaryKey: id
```

Fields:

| Field | Required | Description |
| --- | --- | --- |
| `catalog` | no | Logical or physical database catalog name. |
| `defaultSchema` | no | Default schema for table bindings. |
| `tables` | no | Named table binding map used by `types[].table`. |
| `tables.*.physicalName` | yes | Physical table name. |
| `tables.*.schema` | no | Physical schema override. |
| `tables.*.primaryKey` | no | Primary key column used by point roots and cursors. |

## Roots

Roots declare public fields on the generated `Query` type. A root must reference
a declared projection type.

### Point Root

```yaml
roots:
  article:
    type: Article
    operation: point
    argument:
      name: id
      type: Int
      column: id
```

Fields:

| Field | Required | Description |
| --- | --- | --- |
| `type` | yes | Projection type returned by the root. |
| `operation` | yes | `point` or `connection`. |
| `argument` | yes for `point` | Required key argument for point lookup. |
| `argument.name` | yes | Public GraphQL argument name. |
| `argument.type` | yes | GraphQL scalar type. |
| `argument.column` | yes | Bound database column. |

### Relay Connection Root

```yaml
roots:
  articles:
    type: Article
    operation: connection
    pagination:
      mode: relay
      defaultPageSize: 10
      maxPageSize: 100
      totalCount: exact
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
      title:
        column: title
        path: title
        direction: asc
        nulls: last
        tieBreaker: id
    contextFilters:
      - publishedVisibility
```

Fields:

| Field | Required | Description |
| --- | --- | --- |
| `pagination.mode` | yes for `connection` | Must be `relay` in v1alpha1. |
| `pagination.defaultPageSize` | no | Default connection page size. |
| `pagination.maxPageSize` | yes | Maximum requested page size. |
| `pagination.totalCount` | no | `exact`, `estimated`, or `none`. Current behavior supports exact counts for promoted demo paths. |
| `pagination.cursor` | yes | Stable cursor ordering. |
| `arguments` | no | Additional scalar root arguments. |
| `filterPaths` | no | Generated filter input paths exposed on the root. |
| `sortPaths` | no | Generated order input paths exposed on the root. |
| `contextFilters` | no | Context filter names applied before client filters unless the filter says otherwise. |

Root argument kinds:

| Kind | Description |
| --- | --- |
| `equals` | Scalar equality predicate against a column or path. |

Filter path fields:

| Field | Required | Description |
| --- | --- | --- |
| `type` | yes | GraphQL scalar type used by generated filters. |
| `column` | yes | Local bound column or computed field name. |
| `path` | yes | Model path, such as `title` or `author.name`. |
| `hops` | no | Relation hop count. Defaults to `0`. |
| `operators` | yes | Allowed generated filter operators. |

Sort path fields:

| Field | Required | Description |
| --- | --- | --- |
| `column` | yes | Local bound column or computed field name. |
| `path` | yes | Model path used in the generated order input. |
| `hops` | no | Relation hop count. Defaults to `0`. |
| `direction` | no | Default direction, `asc` or `desc`. |
| `nulls` | no | `first` or `last`. |
| `tieBreaker` | yes | Stable tie-breaker column, usually `id`. |

## Types

Types map GraphQL object types to table bindings, fields, and relations.

```yaml
types:
  Article:
    table: articles
    description: Published and draft blog article.
    fields:
      id:
        column: id
        type: Int
      title:
        column: title
        type: String
    relations: {}
```

Fields:

| Field | Required | Description |
| --- | --- | --- |
| `table` | yes | Table binding key from `database.tables` or a physical table name. |
| `description` | no | Generated type description. |
| `fields` | yes | Field map for scalar and computed fields. |
| `relations` | no | Relation map for object or connection fields. |

## Fields

### Column Field

```yaml
fields:
  title:
    column: title
    type: String
    description: Article title.
    nullable: false
    filter:
      operators: [eq, neq, in, isNull, contains, startsWith, endsWith]
    sort:
      default: asc
      nulls: last
      tieBreaker: id
```

Field keys:

| Field | Required | Description |
| --- | --- | --- |
| `column` | yes for column fields | Bound database column. |
| `type` | yes | GraphQL scalar type. |
| `description` | no | Generated field description. |
| `nullable` | no | Whether the GraphQL field may be null. |
| `policy` | no | Named field policy. |
| `deprecated` | no | Deprecation metadata. |
| `filter` | no | Generated filter capabilities. |
| `sort` | no | Generated sort capabilities. |

Filter operators:

| Operator | Description |
| --- | --- |
| `eq` | Equal. |
| `neq` | Not equal. |
| `in` | Value is in a list. |
| `isNull` | Null predicate. |
| `lt` | Less than. |
| `lte` | Less than or equal. |
| `gt` | Greater than. |
| `gte` | Greater than or equal. |
| `contains` | String contains. |
| `startsWith` | String prefix match. |
| `endsWith` | String suffix match. |

### Computed Field

```yaml
fields:
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
      tieBreaker: id
```

Computed fields are allowed only when their lowering target is explicit. In
v1alpha1, `sqlTemplate` is the documented computed-field kind. Future versions
may add generated SQL helpers, materialized columns, or Java-only experiments,
but those are reserved until validation and lowering rules exist.

Computed fields:

| Field | Required | Description |
| --- | --- | --- |
| `kind` | yes | `sqlTemplate` in v1alpha1. |
| `template` | yes for `sqlTemplate` | SQL expression template using field references like `{title}`. |
| `requiredColumns` | yes | Columns needed to evaluate the expression. |
| `deterministic` | yes | Whether repeated evaluation with the same row is stable. |
| `nullable` | yes | Whether the expression may return null. |
| `sensitive` | no | Whether extra governance review is needed. |
| `costClass` | yes | `constant`, `rowLocal`, or `relationDependent`. |

## Relations

Relations expose object or connection fields from a parent type.

```yaml
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
```

```yaml
relations:
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
    arguments:
      first:
        kind: relayFirst
      after:
        kind: relayAfter
      last:
        kind: relayLast
      before:
        kind: relayBefore
    sortPaths:
      id:
        column: id
        path: id
        direction: asc
        tieBreaker: id
```

Fields:

| Field | Required | Description |
| --- | --- | --- |
| `target` | yes | Target projection type. |
| `cardinality` | yes | `one` or `many`. |
| `nullable` | no | Whether the relation field may be null. |
| `localColumn` | yes | Parent-side join column. |
| `targetColumn` | yes | Target-side join column. |
| `capabilities` | yes | Selection, batching, pagination, and hop budget settings. |
| `arguments` | no | Relation arguments. |
| `filterPaths` | no | Generated relation filters. Reserved until promoted. |
| `sortPaths` | no | Generated relation order paths. |
| `policy` | no | Named relation policy. |

Relation argument kinds:

| Kind | Description |
| --- | --- |
| `relayFirst` | Relay `first` page size. |
| `relayAfter` | Relay `after` cursor. |
| `relayLast` | Relay `last` page size. |
| `relayBefore` | Relay `before` cursor. |
| `equals` | Scalar equality predicate. |

## Policies

Policies name reusable authorization or visibility behavior. The source document
names policy contracts; it does not embed executable policy code.

```yaml
policies:
  canReadUserEmail:
    description: User email is visible only to admins.
    appliesTo:
      - User.email
    input:
      actorRole: String
    mode: reject
    expression:
      kind: named
      name: adminOnly
```

Fields:

| Field | Required | Description |
| --- | --- | --- |
| `description` | no | Human policy description. |
| `appliesTo` | no | Model paths guarded by the policy. |
| `input` | no | Required request-context keys and types. |
| `mode` | yes | `reject`, `mask`, or `filter`. Current field-policy behavior uses `reject`. |
| `expression.kind` | yes | `named` in v1alpha1. |
| `expression.name` | yes | Runtime policy hook name. |

Reserved for later: arbitrary expression languages, user-defined Java snippets,
nested write policies, and actor-shaped schema generation.

## Context Filters

Context filters apply request-context predicates before client filters by
default. They are fail-closed unless explicitly marked otherwise.

```yaml
contextFilters:
  publishedVisibility:
    description: Restrict articles by the published flag when enabled.
    type: booleanEquals
    column: published
    contextKey: articleVisibility
    valueType: Boolean
    failClosed: true
    phase: beforeClientFilters
```

Fields:

| Field | Required | Description |
| --- | --- | --- |
| `type` | yes | `booleanEquals` in v1alpha1. |
| `column` | yes | Column constrained by the filter. |
| `contextKey` | yes | Request-context key that supplies the value. |
| `valueType` | yes | `Boolean`, `ID`, or `String`. |
| `failClosed` | no | Reject or hide data when required context is missing. Defaults to `true`. |
| `phase` | yes | `beforeClientFilters` in v1alpha1. |

## Artifacts

Artifacts are review outputs generated from a validated model. They are not
source truth.

```yaml
artifacts:
  generatedSchema:
    enabled: true
    path: build/generated/titan-graphql/schema.graphql
  introspection:
    enabled: true
    path: build/generated/titan-graphql/introspection.json
  conformance:
    enabled: true
    path: build/generated/titan-graphql/conformance.md
  sql:
    enabled: true
    dialects: [postgres]
    path: build/generated/titan-graphql/sql
```

Fields:

| Field | Required | Description |
| --- | --- | --- |
| `generatedSchema` | no | SDL artifact settings. |
| `introspection` | no | Introspection JSON artifact settings. |
| `conformance` | no | Conformance matrix/profile artifact settings. |
| `sql` | no | Lowered SQL artifact settings. |
| `*.enabled` | no | Whether to generate the artifact. |
| `*.path` | no | Review output path. |
| `sql.dialects` | no | Target SQL dialects. |

## Deployment

Deployment metadata describes intent for preview and activation. It does not
deploy anything by itself.

```yaml
deployment:
  environment: staging
  preview:
    enabled: true
    route: /preview/demo-blog/graphql
    expiresAfter: P7D
  runtime:
    endpoint: /graphql
    managementEndpoint: /admin/graphql
```

Fields:

| Field | Required | Description |
| --- | --- | --- |
| `environment` | no | Target environment profile. |
| `preview.enabled` | no | Whether preview builds may be created. |
| `preview.route` | no | Requested preview route. |
| `preview.expiresAfter` | no | ISO-8601 duration for preview expiry. |
| `runtime.endpoint` | no | Application GraphQL endpoint. Defaults to `/graphql` by convention. |
| `runtime.managementEndpoint` | no | Management API endpoint. Defaults to `/admin/graphql` by convention. |

## Complete Demo-Blog Example

```yaml
apiVersion: titan.graphql/v1alpha1
kind: ProjectionModel
metadata:
  name: demo-blog
  version: 2026.05.31
  owner: platform
  description: Bounded demo blog model used to prove Titan GraphQL.
  tags: [demo, query-only]

modules:
  content:
    owner: content-platform
    roots: [article, articles]
    types: [Article, Comment]
  identity:
    owner: identity-platform
    types: [User]
    policies: [canReadUserEmail]

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
  canReadUserEmail:
    description: User email is visible only to admins.
    appliesTo: [User.email]
    input:
      actorRole: String
    mode: reject
    expression:
      kind: named
      name: adminOnly

contextFilters:
  publishedVisibility:
    description: Restrict articles by the published flag when enabled.
    type: booleanEquals
    column: published
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
          tieBreaker: id
      title:
        column: title
        type: String
        filter:
          operators: [eq, neq, in, isNull, contains, startsWith, endsWith]
        sort:
          default: asc
          tieBreaker: id
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
          tieBreaker: id
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
        arguments:
          first:
            kind: relayFirst
          after:
            kind: relayAfter
          last:
            kind: relayLast
          before:
            kind: relayBefore
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
      totalCount: exact
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
        tieBreaker: id
      title:
        column: title
        path: title
        direction: asc
        tieBreaker: id
      titleLength:
        column: titleLength
        path: titleLength
        direction: asc
        tieBreaker: id
      authorName:
        column: author_id
        path: author.name
        hops: 1
        direction: asc
        tieBreaker: id
    contextFilters:
      - publishedVisibility

artifacts:
  generatedSchema:
    enabled: true
    path: build/generated/titan-graphql/schema.graphql
  introspection:
    enabled: true
    path: build/generated/titan-graphql/introspection.json
  conformance:
    enabled: true
    path: build/generated/titan-graphql/conformance.md
  sql:
    enabled: true
    dialects: [postgres]
    path: build/generated/titan-graphql/sql

deployment:
  environment: staging
  preview:
    enabled: true
    route: /preview/demo-blog/graphql
    expiresAfter: P7D
  runtime:
    endpoint: /graphql
    managementEndpoint: /admin/graphql
```

## Canonicalization Notes

The future canonical JSON IR should preserve behavior, not source formatting.
The canonical form should:

- keep `apiVersion`, `kind`, `metadata`, `roots`, `types`, policies, context
  filters, artifacts, modules, and deployment metadata as structured records
- sort map keys deterministically unless declared order is semantically
  meaningful
- treat YAML comments as source-only
- exclude comments and whitespace from semantic hashes
- preserve source locations separately for diagnostics
- normalize enum casing to canonical lower camel case in JSON
- make defaults explicit before hashing

Behavior-affecting changes must alter the semantic hash. Source-only comments
and formatting must not.

## Parser Boundary

The v1alpha1 parser boundary uses Jackson's YAML dataformat module to read
`titan.graphql.yaml` into a YAML/JSON tree, then maps that source tree into the
canonical Java IR and canonical JSON/hash layer. The parser is intentionally
behavior-neutral: it does not load runtime schemas, adapt projection models,
generate SQL, or deploy artifacts.

Parser diagnostics are reported through Titan model-document errors rather than
raw YAML library exceptions. Malformed YAML includes source line and column when
the parser can provide them. Unsupported top-level sections, missing required
fields, and unknown enum values are explicit model-document errors.

## Editor Schema

The editor-facing JSON Schema for `titan.graphql.yaml` lives at
[`docs/schema/titan.graphql.schema.json`](schema/titan.graphql.schema.json).
It covers the v1alpha1 envelope, required fields, documented enums, and common
nested authoring structures so YAML-aware editors can provide completion and
basic shape checks.

The schema is intentionally not the semantic validator. Model validation still
must check cross-references, database bindings, policy references, drift, and
runtime/lowered support before deployment.

## Projection Adapter Boundary

The first IR-to-projection adapter is deliberately narrower than the full
v1alpha1 source vocabulary. It exists to prove that the demo-blog fixture can be
adapted into the existing Java `ProjectionModel` without changing runtime model
loading or application endpoint behavior.

The adapter accepts the current demo-blog-shaped subset:

- point roots with an `Int` equality argument
- Relay connection roots with exact `totalCount`, cursor metadata, equality
  `Int` arguments, declared filter paths, sort paths, and boolean fail-closed
  context filters
- scalar column fields whose GraphQL type, filter operators, and sort metadata
  match the existing inferred projection behavior
- SQL-template computed fields with explicit required columns and existing
  filter/sort capability shapes
- `one` and `many` relations, Relay relation pagination with exact
  `totalCount`, Relay arguments, and declared relation sort paths
- the current named `adminOnly` field-policy predicate used by
  `canReadUserEmail`

Unsupported adapter input fails explicitly with adapter diagnostics such as
`UNSUPPORTED_POLICY`, `UNSUPPORTED_FIELD_TYPE`,
`UNSUPPORTED_FILTER_OPERATORS`, `UNSUPPORTED_CONTEXT_FILTER`,
`UNSUPPORTED_RELATION_PAGINATION`, or `UNKNOWN_CONTEXT_FILTER`. Future roadmap
slices should promote additional IR features only when the Java reference and
SQL/lowered behavior have matching validation and equivalence coverage.

## v1alpha1 Reserved Or Unsupported Fields

The following concepts are intentionally reserved:

- YAML includes, imports, anchors as semantic references, or multi-file module
  composition
- arbitrary policy expression languages
- executable Java, JavaScript, SQL procedure, or plugin snippets in the source
  document
- broad application CRUD mutations
- nested mutation graphs
- subscriptions
- dynamic resolver plugins
- actor-specific schema generation
- automatic public exposure of every database table
- relation filter/sort promotion beyond explicitly declared and validated paths
- independent per-module runtime deployment
- ad hoc runtime editing of compiled database artifacts

Future parsers should reject unsupported fields with precise source locations
instead of silently ignoring them.
