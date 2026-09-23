# Titan GraphQL Model Document Format

Status: v1alpha1 source-format reference.

This document defines the first human-authored Titan GraphQL model document
shape. It is the contract developers should read before authoring
`titan.graphql.yaml`.

The format is an implemented source document, not generated output. The YAML parser, validator,
projection adapter, carrier generator, package binding, and compiled runtime all consume this
contract. Unsupported or unsafe shapes are rejected before generation or execution.

## Design Goals

The model document should be:

- readable in code review
- deterministic when exported
- explicit about data bindings, policies, pagination, filters, and sorts
- compatible with the current Java projection model
- deterministically normalizable into the canonical JSON used for semantic hashing
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
| `enums` | no | Declared GraphQL enum types used by output fields, generated filters, point-root and root/relation connection equality arguments, and explicit custom-mutation arguments. |
| `inputObjects` | no | Authored reusable GraphQL input-object declarations. |
| `interfaces` | no | Reviewed GraphQL interface declarations implemented by object projection types. |
| `unions` | no | Reviewed GraphQL unions whose members are object projection types. |
| `directives` | no | Reviewed executable conditional directives implemented by the transpiled engine. |
| `policies` | no | Named root, row, field, and relation policy declarations. |
| `mutations` | no | Explicit reviewed mutation bindings for the database-engine proof; not yet consumed by the legacy serving runtime. |
| `contextFilters` | no | Named request-context filters that compose into roots. |
| `artifacts` | no | Generated output options for reviewable artifacts. |
| `deployment` | no | Deployment and preview metadata. |

Unknown top-level fields are unsupported in v1alpha1 and are rejected by the
parser. Extension fields should use `x-<owner>-<name>` only after the
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

## Executable Directives

The optional `directives` map registers schema-visible conditional directives that execute inside
the transpiled database engine. v1alpha1 deliberately supports only aliases of GraphQL's bounded
include/skip behavior; it does not expose arbitrary code hooks or HTTP/JVM callbacks.

```yaml
directives:
  visible:
    description: Includes a selection when its required Boolean condition is true.
    behavior: includeIf
    locations: [FIELD, FRAGMENT_SPREAD, INLINE_FRAGMENT]
  hidden:
    description: Skips a field when its required Boolean condition is true.
    behavior: skipIf
    locations: [FIELD]
```

| Field | Required | Description |
| --- | --- | --- |
| `description` | no | Schema description returned by directive introspection. |
| `behavior` | yes | `includeIf` includes the selection only when the condition is true; `skipIf` omits it when true. |
| `locations` | yes | Non-empty, unique subset of `FIELD`, `FRAGMENT_SPREAD`, and `INLINE_FRAGMENT`. |

Each registered directive is non-repeatable and has exactly one generated argument,
`if: Boolean!`. Names must be valid public GraphQL names and cannot replace the built-in `include`
or `skip` directives. Definitions on operation or fragment definitions remain unsupported. The
canonical model identity includes names, descriptions, behaviors, and locations, so changing any
of them requires a newly generated and bound package.

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

For a composite key, use `arguments` and bind every physical key component explicitly:

```yaml
roots:
  inventoryItem:
    type: InventoryItem
    operation: point
    arguments:
      warehouse:
        type: String
        kind: equals
        column: warehouse_code
        defaultValue: '"AMS"'
      sku:
        type: String
        kind: equals
        column: sku
```

Fields:

| Field | Required | Description |
| --- | --- | --- |
| `type` | yes | Concrete projection type used for physical lookup and generated database access. |
| `outputType` | no | Interface or union exposed by a point root or by a Relay connection's `node`. The concrete `type` must implement or belong to it. |
| `operation` | yes | `point` or `connection`. |
| `argument` | yes for a scalar-key `point` | Single required key argument for point lookup. Mutually exclusive with `arguments`. |
| `arguments` | yes for a composite-key `point` | One or more required key arguments. Mutually exclusive with `argument`. |
| `argument.name` | yes | Public GraphQL argument name. |
| `argument.type` | yes | `Int`, `Long`, `String`, `ID`, `UUID`, or a declared enum; it must match the bound scalar field type. |
| `argument.column` | yes | Bound database column. |
| `argument.kind` | no | Defaults to `equals`; point-key arguments must use `equals`. |
| `argument.hops` | no | Must be `0`; point keys cannot traverse a relation. |
| `argument.defaultValue` | no | GraphQL constant-value source used when the argument is omitted. Point arguments are publicly non-null, so their defaults must be non-null. |

`defaultValue` is parsed and type-checked against the public GraphQL argument type during the build.
It is GraphQL source rather than JSON: quote string values in the GraphQL value and, when needed,
quote that source for YAML as shown above; enum values remain unquoted GraphQL names. Defaults may
not contain variable references. An omitted argument receives the default inside the transpiled
database engine, while an explicitly supplied `null` remains distinct and is rejected for a non-null
point argument. A non-null argument with a default is optional at the call site, as required by
GraphQL variable-location compatibility rules.

For a field declared as GraphQL `ID`, `idStorage` selects its reviewed physical representation:
`integral` (the compatibility default) or `string`. The public GraphQL value remains an ID in
both cases: the engine accepts an integer or string input literal as GraphQL permits and always
serializes the result as a JSON string. `idStorage` belongs to the field, never to a client
argument, so generated JDBC binding cannot silently guess a column type.

### Relay Connection Root

```yaml
roots:
  articles:
    type: Article
    operation: connection
    policies: [canReadArticles]
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
        defaultValue: '7'
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
| `arguments` | no | Additional local equality arguments. The database engine accepts `Int`, `Long`, or a declared enum; an enum must exactly match a modeled stored field on the root type. Each equality argument may declare a typed GraphQL-source `defaultValue`. |
| `filterPaths` | no | Generated filter input paths exposed on the root. |
| `sortPaths` | no | Generated order input paths exposed on the root. |
| `contextFilters` | no | Context filter names applied before client filters unless the filter says otherwise. |
| `policies` | no | Named root policies. A denied decision rejects the operation before database I/O and is repeated as a generated carrier predicate. |

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

Compiled carriers support `eq`, `neq`, `isNull`, `lt`, `lte`, `gt`, `gte`, `contains`,
`startsWith`, `endsWith`, and `in`. A single local `in` predicate uses static arities through 16
values. Boolean `and`/`or`/`not` composition, combinations with one custom order, and reviewed
one-hop to-one relation paths compile to a static 3 OR-group by 3 AND-term DNF carrier. Larger
expressions and to-many or deeper paths fail closed with a carrier-budget error. Wildcards in
string values are escaped and treated literally. Filter authorization is checked for every field
and every segment of a relation path before execution; protected paths have no selector branch in
the generated carrier.

Sort path fields:

| Field | Required | Description |
| --- | --- | --- |
| `column` | yes | Local bound column or computed field name. |
| `path` | yes | Model path used in the generated order input. |
| `hops` | no | Relation hop count. Defaults to `0`. |
| `direction` | no | Default direction, `asc` or `desc`. |
| `nulls` | no | `last` in the compiled profile; `first` is reserved and rejected. |
| `tieBreaker` | yes | Stable tie-breaker column, usually `id`. |

The compiled carrier path supports local stored or reviewed computed sort values and one-hop
`relation.field` paths. A one-hop path must name a non-null to-one relation and a stored, non-null
target scalar; `column` must equal that relation's `localColumn`. The generator emits a static,
qualified join and uses the root scalar `tieBreaker` for deterministic cursors. Nullable, to-many,
and multi-hop sort paths are rejected before deployment.

## Database-engine custom mutation proof

`mutations` is an intentionally narrow, explicit source binding used by the in-progress
database-resident engine. It is not generated CRUD and it is not the existing JVM application
mutation registration API. The legacy serving runtime does not consume it.

```yaml
mutations:
  renameCustomer:
    operation: update
    type: Customer
    policies: [canRenameCustomer]
    arguments:
      id:
        type: Int
        column: id
        key: true
      name:
        type: String
        column: name
        defaultValue: '"Defaulted name"'
    payload:
      id:
        argument: id
      name:
        argument: name
```

An explicit mutation may instead publish one authored input-object argument and bind reviewed leaf
paths to columns:

```yaml
inputObjects:
  RenameCustomerInput:
    description: Reviewed customer rename request.
    fields:
      id:
        type: Int!
      patch:
        type: RenameCustomerPatch!
  RenameCustomerPatch:
    description: Mutable customer values.
    fields:
      name:
        type: String!
        description: Replacement customer name.
        defaultValue: '"Defaulted name"'

mutations:
  renameCustomerWithInput:
    operation: update
    type: Customer
    input:
      name: input
      type: RenameCustomerInput
      defaultValue: '{id: 7, patch: {}}'
    inputBindings:
      id:
        path: id
        type: Int
        column: id
        key: true
      name:
        path: patch.name
        type: String
        column: name
    policies: [canRenameCustomer]
    payload:
      id:
        argument: id
      name:
        argument: name
```

The database-engine generator currently accepts only `operation: update`. A mutation declares
either flat `arguments` or one `input` plus `inputBindings`, never both. The public input argument
is non-null in the generated schema. Each binding names a leaf `path` in the authored input-object
graph; its declared `type` and bound column must agree with that leaf and the stored model field.
`type` names the reviewed projection type and supplies its physical table binding. Every argument
must bind a stored scalar or declared-enum field on that type with the same GraphQL type; at least one `key: true`
argument selects the row and at least one non-key argument is an assignment. Supported proof
scalars are `Int`, `Long`, `String`, `ID`, `UUID`, `Boolean`, `Float`, and `Decimal`. `Float` and
`Decimal` currently use the database engine's binary64 binding, so `Decimal` is not an exact-decimal
contract. A type declared under `enums` is also supported for an explicit mutation argument: inline
input must use an unquoted GraphQL enum value, JSON variables carry the value as a string, and both
forms are checked exactly against the generated enum value set before SQL. `payload` is an explicit map from selected
response field to an argument value. Its fields are validated and shaped by the transpiled engine.

Flat mutation arguments and the single authored input-object argument may declare `defaultValue` as
GraphQL constant-value source. Their generated public types are non-null, so `null` is not a valid
default. Omission materializes the validated default into the same canonical argument carrier used
for supplied literals and variables before mutation prevalidation or SQL; explicit `null` does not
select the default and fails coercion. A whole input-object default is applied before its own omitted
fields receive any authored input-field defaults.

An installed proof routine processes selected mutation roots in document order. It requires the
transport's `allowMutations` flag, evaluates the attached reject policies in the database, and
returns an internal `extensions.titanTransactionOutcome` value. `COMMIT` permits the connection
owner to commit; an error after a prior root returns `ROLLBACK` and the caller must roll back the
whole operation. The database routine never invokes Java/CDI handlers or commits itself.

This is a constrained mutation surface, not the final mutation language. The installed engine
prevalidates the whole selected operation before writes, locks the target row, and verifies target
existence. Authored nested input objects, input-field defaults, and flat or input-object argument
defaults are supported for explicit update bindings, but nullable/write-null bindings, arbitrary command procedures,
durable idempotency, outbox/audit semantics, and the broader concurrency contract remain incomplete.
Broad CRUD and nested mutation graphs remain unsupported.

## Input Objects

`inputObjects` declares reusable GraphQL input types for the transpiled database engine. Each object
has an optional `description` and a non-empty `fields` map. Each field requires a GraphQL input type
reference and may declare `description`, `defaultValue`, `deprecated`, and `deprecationReason`.
`defaultValue` is GraphQL constant-value source—not JSON—and therefore preserves the distinction
between enum names and JSON strings. The build validator parses and recursively coerces every
default, rejects unknown types or fields, enforces required nested fields, and rejects an unbroken
cycle of singular non-null input-object references.

At runtime, the database-resident coercer applies the same nested object/list/scalar/enum rules to
inline literals, JSON variables, variable defaults, and omitted fields. It materializes one canonical
value carrier before execution, applies input-field defaults only when a field is omitted, and keeps
an explicit `null` distinct from omission. `__schema` and `__type` expose the authored input types,
type and field descriptions, exact wrappers, defaults, and field deprecations. Deprecated input fields
are omitted unless `inputFields(includeDeprecated: true)` is requested. All of this processing occurs
inside the generated database routines; the HTTP frontend forwards the original request envelope.

## Types

Types map GraphQL object types to table bindings, fields, and relations.

```yaml
types:
  Article:
    table: articles
    description: Published and draft blog article.
    policies: [canReadArticleRows]
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
| `policies` | no | Named row policies. Denied decisions filter this type from roots, exact counts, and relation reads in generated SQL. |
| `interfaces` | no | Interface names implemented by this object type. Each required interface field must exist with the exact output type and nullability. |

## Abstract Output Types

The model may declare reviewed interfaces and unions without adding handwritten runtime resolvers:

```yaml
interfaces:
  Node:
    description: An identifiable object.
    fields:
      id:
        type: Int!

unions:
  SearchResult:
    description: A searchable object.
    members: [Customer, Order]

types:
  Customer:
    table: customers
    interfaces: [Node]
    fields:
      id: { type: Int, column: id }
      name: { type: String, column: name }
  Order:
    table: orders
    interfaces: [Node]
    fields:
      id: { type: Int, column: id }
      reference: { type: String, column: reference }

roots:
  nodeCustomer:
    type: Customer
    outputType: Node
    operation: point
    argument: { name: id, type: Int, column: id }
  searchCustomer:
    type: Customer
    outputType: SearchResult
    operation: point
    argument: { name: id, type: Int, column: id }
```

Interface and union names share the GraphQL type namespace with objects, enums, input objects, and
built-in scalars. An interface must declare at least one field. Every implementing object must
provide every declared interface field with the exact type wrapper; the current projection binding
requires those interface fields to be ordinary modeled object fields rather than relation fields.
A union must contain at least one unique declared object type. Descriptions are optional.

`root.type` always remains the concrete physical projection used to generate static SQL. A point or
Relay connection root may set `outputType` to an interface implemented by that object or a union
containing it. For a connection this produces abstract `<OutputType>Connection` and
`<OutputType>Edge` wrappers whose `node` has the declared abstract type. This changes the public
GraphQL return type without model-specific query dispatch: the installed package already knows the
concrete row shape, validates abstract selections per possible type, and emits the concrete object's
`__typename`. A heterogeneous connection that combines multiple physical projections is not yet
expressible; it needs an explicit cross-source cursor, policy, ordering, and runtime-type contract.

The transpiled engine validates selections against the declared abstract type and every possible
concrete runtime type. Interface fields may be selected directly; union fields require a fragment
apart from `__typename`. Named and inline fragments are checked for possible overlap, conflicting
response keys are evaluated per possible runtime object, and mutually exclusive concrete branches
may reuse an alias. The generator also carries each concrete object's interface/union memberships
through point, relation, and Relay-node selection planning, so applicable abstract fragments are not
limited to roots authored with `outputType`.

`__schema.types` and `__type` expose `INTERFACE`/`UNION`, authored type descriptions, object
`interfaces`, and abstract `possibleTypes`. Object- and interface-field descriptions and
deprecations are part of the canonical model identity and are compiled into delimiter-safe
metadata consumed by the transpiled introspection renderer. `fields` omits deprecated fields by
default and includes their authored reason when `includeDeprecated: true` is requested.

## Enums

The model may declare enum types independently of its object types:

```yaml
enums:
  CustomerStatus:
    values: [ACTIVE, INACTIVE, LEGACY]
    valueMetadata:
      ACTIVE:
        description: Customer can place orders.
      LEGACY:
        description: Historic status retained for compatibility.
        deprecated: true
        deprecationReason: Use INACTIVE.

types:
  Customer:
    table: customers
    fields:
      status:
        column: status
        type: CustomerStatus
        nullable: false
```

Enum type names must be valid upper-initial GraphQL names and must not collide with object types,
built-in scalars, or introspection names. Each enum needs at least one unique GraphQL-name value;
`true`, `false`, `null`, and names beginning with `__` are invalid enum values. Value spelling is
preserved and database values must match it exactly. Canonical generation sorts enum types and
values, and sorts optional `valueMetadata` entries by value name, so authoring order does not
change semantic identity or introspection order. Metadata keys must name declared values. A
`deprecationReason` is valid only when `deprecated: true`; a deprecated value without an authored
reason exposes the standard `No longer supported` reason.

The database-resident engine exposes declared enums through `__schema.types`, `__type`, and object
field type metadata. It serializes valid stored values as GraphQL enum strings. An unrecognized
stored value produces a source- and path-located execution error; a nullable field becomes `null`,
while a non-null field follows the normal GraphQL propagation rules. The current v1alpha1 database
engine also accepts declared enums in reviewed point-root equality arguments, root/relation Relay
equality arguments, explicit custom-mutation arguments, and generated Relay filter input fields with `eq`, `neq`, `in`, and Boolean
`isNull` operators. Enum argument coercion, including the distinction between unquoted GraphQL
literals and JSON string variables, runs in the transpiled database language core; root, mutation,
relation, and filter introspection expose the declared enum type and its exact wrappers. Enum sorts
and enum ordering remain unsupported rather than delegated to the
HTTP/JVM layer. Authored input-object fields may reference declared enums and may supply validated
GraphQL constant defaults. Enum descriptions and deprecations are compiled into a delimiter-safe descriptor
consumed by the transpiled introspection renderer. `enumValues` omits deprecated values by default
and returns their description, `isDeprecated`, and reason when `includeDeprecated: true` is used.

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
| `type` | yes | Supported GraphQL scalar type or a type declared in `enums`. |
| `description` | no | Generated field description. |
| `nullable` | no | Whether the GraphQL field may be null. |
| `policy` | no | Named field policy. |
| `deprecated` | no | Marks the output field deprecated; defaults to `false`. |
| `deprecationReason` | no | Authored reason returned by introspection when `deprecated: true`. |
| `filter` | no | Generated filter capabilities. |
| `sort` | no | Generated sort capabilities. |

An output-field `deprecationReason` is invalid unless `deprecated: true`. A deprecated field with
no authored reason exposes GraphQL's standard `No longer supported` reason. The transpiled
introspection engine returns authored descriptions, `isDeprecated`, and `deprecationReason`, and
filters deprecated object and interface fields unless `fields(includeDeprecated: true)` is used.

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
      authorId:
        type: Int
        kind: equals
        column: author_id
        path: author_id
        defaultValue: '7'
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
| `arguments` | no | Relation arguments. Local `equals` arguments may declare a typed GraphQL-source `defaultValue`; generated Relay controls may not be overridden. |
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
| `equals` | Local `Int`, `Long`, or declared-enum equality predicate. Declare `type`, `column`, and `path`; `hops` must be `0`. A declared enum must exactly match a modeled stored field on the target type. |

An omitted local equality argument receives its validated model default inside the transpiled engine
before the relation carrier executes. An explicit `null` remains null for a nullable equality
argument and does not select the default. The standard `first`, `after`, `last`, and `before`
arguments retain their generated Relay behavior and cannot declare model defaults.

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
| `mode` | yes | Must be `reject` for the current compiled named-policy language. |
| `expression.kind` | yes | `named` in v1alpha1. |
| `expression.name` | yes | Reviewed named expression: `adminOnly`, `authenticated`, `allowAll`, `denyAll`, `roleEquals:<role>`, or `roleIn:<role,...>`. Multiple attached policies are ANDed. |

The same compiler is used for root, type, field, and relation authorization, and unknown
expressions fail closed while adapting or generating the model. The database package evaluates
every policy reached by the selected operation before application I/O; generated SQL predicates
remain defense in depth for direct carrier access. Type policies gate root reads, exact counts, and
relation targets. Field and relation policies are request-reject policies in v1alpha1: they do not
produce actor-specific schema variants or partial masked rows. Authorization errors retain the
selected source location, while the pre-execution policy phase deliberately does not fabricate
row-index paths. Reserved for later: row-value expression predicates beyond the existing reviewed
context filters, arbitrary expression languages, user-defined Java snippets, nested write
policies, partial field masking, and actor-shaped schema generation.

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

The canonical JSON IR preserves behavior, not source formatting.
The canonical form:

- keep `apiVersion`, `kind`, `metadata`, `roots`, `types`, policies, context
  filters, artifacts, modules, and deployment metadata as structured records
- sort map keys deterministically unless declared order is semantically
  meaningful
- treat YAML comments as source-only
- exclude comments and whitespace from semantic hashes
- preserve source locations separately for diagnostics
- preserve declared GraphQL enum spelling while sorting enum types and values canonically
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

The adapter accepts the currently compiled subset:

- point roots with local equality arguments typed as `Int`, `Long`, `String`, `ID`, `UUID`, or a
  declared enum, including explicitly declared composite keys
- Relay connection roots with exact `totalCount`, cursor metadata, equality
  `Int` arguments, declared filter paths, sort paths, and boolean fail-closed
  context filters
- scalar column fields whose GraphQL type, filter operators, and sort metadata
  match the existing inferred projection behavior
- SQL-template computed fields with explicit required columns and existing
  filter/sort capability shapes
- `one` and `many` relations, Relay relation pagination with exact
  `totalCount`, Relay arguments, and declared relation sort paths
- reviewed named root, type-row, field, and relation policies: `adminOnly`, `authenticated`,
  `allowAll`, `denyAll`, `roleEquals:<role>`, and `roleIn:<role,...>`; multiple attached policies
  are ANDed

Unsupported adapter input fails explicitly with adapter diagnostics such as
`UNSUPPORTED_POLICY`, `UNSUPPORTED_FIELD_TYPE`,
`UNSUPPORTED_FILTER_OPERATORS`, `UNSUPPORTED_CONTEXT_FILTER`,
`UNSUPPORTED_RELATION_PAGINATION`, or `UNKNOWN_CONTEXT_FILTER`. Future roadmap
slices should promote additional IR features only when the Java reference and
SQL/lowered behavior have matching validation and equivalence coverage.

The adapter bullets above describe the migration-only JVM oracle, not the database-engine schema.
It and the matching legacy carrier/invoker path deliberately omit optional declared-enum equality
arguments from Relay roots and relations because its projection descriptor has only `INT_EQUALS`.
The generated database engine exposes and executes those enum arguments; the adapter and all legacy
carrier/runtime code are M6 deletion targets rather than a second semantic implementation.

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
