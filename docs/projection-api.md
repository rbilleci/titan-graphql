# Titan GraphQL Projection API

Status: v1 public builder facade.

`TitanGraphqlProjection` is the Java API for declaring a GraphQL projection
model without constructing the package-private descriptor classes directly. It
is the Java source-of-truth path for applications that do not want to author a
`titan.graphql.yaml` document yet, and it builds the same internal
`ProjectionModel` contract used by the demo schema and YAML adapter.

The facade is intentionally behavior-neutral. It does not load YAML at runtime,
does not change SQL lowering, does not add management API behavior, and does not
turn generated SDL into source truth.

## Entry Points

Use `TitanGraphqlProjection.model()` to start a model, add roots and object
types, then call `build()` to get a `TitanGraphqlProjectionModel`.

```java
TitanGraphqlProjectionModel model = TitanGraphqlProjection.model()
        .pointRoot("product", "Product", "id")
        .relayConnectionRoot("products", "Product")
        .pageSize(20, 200)
        .cursorOrderingAscending("id", "id", "id")
        .sortPathAscending("name", "name", "name", 0)
        .addRoot()
        .type("Product")
        .table("catalog", "products", "products", "id")
        .scalarField("id", "id")
        .scalarField("name", "name")
        .addType()
        .build();
```

The returned model is an opaque public wrapper. It exposes stable inspection
helpers for generated artifacts:

```java
List<String> roots = model.rootNames();
List<String> types = model.typeNames();
String sdl = model.schemaDefinition();
```

Generated SDL is an artifact for review, tests, and documentation. The
projection builder or model document remains the source of semantics.

## Roots

A point root exposes a required key argument and returns one object.

```java
.pointRoot("product", "Product", "id")
```

A Relay connection root exposes a list-style root with generated connection,
edge, cursor, pagination, filter, sort, and count metadata according to the
builder calls applied before `addRoot()`.

```java
.relayConnectionRoot("products", "Product")
.pageSize(20, 200)
.argumentIntEquals("vendorId", "vendor_id")
.cursorOrderingAscending("id", "id", "id")
.filterPathString("vendorName", "vendor_id", "vendor.name", 1)
.sortPathAscending("name", "name", "name", 0)
.sortPathDescending("vendorName", "vendor_id", "vendor.name", 1)
.contextFilterBooleanEquals("activeCatalog", "active", "catalogActive")
.addRoot()
```

Root filter and sort path metadata is generated into the GraphQL schema. The
hop count records whether the path is local (`0`) or crosses a relation path
(`1` or more). Context filters are not client arguments; they are activated
from request context and fail closed when required context is missing.

## Object Types

Object types bind a GraphQL type to a database table shape, then declare fields
and relations.

```java
.type("Product")
.table("catalog", "products", "products", "id")
.scalarField("id", "id")
.scalarField("name", "name")
.addType()
```

The shorter table overload uses the `public` schema, the same logical and
physical table name, and `id` as the primary key column:

```java
.type("Vendor")
.table("vendors")
.scalarField("id", "id")
.scalarField("name", "name")
.addType()
```

## Field Authorization

Scalar and computed fields can receive a `GraphqlFieldAuthorization` callback.
The callback is given the actor role from the request context and returns
whether the field can be read.

```java
.scalarField("costCents", "cost_cents", actorRole -> "admin".equals(actorRole))
```

Reusable policies can be named in ordinary Java:

```java
GraphqlFieldAuthorization adminOnly = actorRole -> "admin".equals(actorRole);

TitanGraphqlProjection.model()
        .type("Product")
        .table("products")
        .scalarField("costCents", "cost_cents", adminOnly)
        .addType();
```

Authorization is field-local in this builder API. Broader root, row, field, and relation policy
authoring is available in the reviewed YAML model rather than inferred by this facade.

## Computed Fields

Computed fields describe deterministic row-local SQL-template expressions and
their required source columns. They can be marked filterable and sortable when
the lowered path supports the generated metadata.

```java
.computedSqlTemplateField(
        "nameLength",
        "Int",
        "length({name})",
        List.of("name")
)
.filterable()
.sortable()
.addField()
```

Computed fields may also carry field authorization:

```java
.computedSqlTemplateField("marginCents", "Int", "{price_cents} - {cost_cents}", List.of("price_cents", "cost_cents"))
.authorization(actorRole -> "admin".equals(actorRole))
.addField()
```

Keep Java-mode as the reference behavior. Only promote generated runtime
behavior for a computed field shape when the SQL/lowered path has matching
coverage.

## Relations

A one relation exposes a single related object:

```java
.oneRelation("vendor", "Vendor", "vendor_id", "id")
.addRelation()
```

Call `nullable()` before `addRelation()` when the relationship may be absent:

```java
.oneRelation("vendor", "Vendor", "vendor_id", "id")
.nullable()
.addRelation()
```

A many relation exposes a collection. Use Relay capabilities and pagination
arguments when the relation should generate connection fields.

```java
.manyRelation("reviews", "Review", "id", "product_id")
.relayConnectionWithTotalCount(false, true, 2, 0, 0, 10, 50)
.relayPaginationArguments()
.sortPathAscending("id", "id", "id", 0)
.addRelation()
```

The `relayConnectionWithTotalCount` arguments are, in order:

- whether the relation supports generated filtering
- whether the relation supports generated sorting
- selection hop budget
- filter hop budget
- sort hop budget
- default page size
- max page size

Relation filter and sort capabilities should stay conservative. Generated
metadata is part of the public GraphQL contract, so do not advertise a path that
Java-mode and SQL-mode cannot both honor.

## Complete Example

This example mirrors the non-demo coverage in `GraphqlMetamodelTest`.

```java
GraphqlFieldAuthorization adminOnly = actorRole -> "admin".equals(actorRole);

TitanGraphqlProjectionModel model = TitanGraphqlProjection.model()
        .pointRoot("product", "Product", "id")
        .relayConnectionRoot("products", "Product")
        .pageSize(20, 200)
        .argumentIntEquals("vendorId", "vendor_id")
        .cursorOrderingAscending("id", "id", "id")
        .filterPathString("vendorName", "vendor_id", "vendor.name", 1)
        .sortPathAscending("name", "name", "name", 0)
        .sortPathDescending("vendorName", "vendor_id", "vendor.name", 1)
        .contextFilterBooleanEquals("activeCatalog", "active", "catalogActive")
        .addRoot()
        .type("Product")
        .table("catalog", "products", "products", "id")
        .scalarField("id", "id")
        .scalarField("name", "name")
        .scalarField("costCents", "cost_cents", adminOnly)
        .computedSqlTemplateField("nameLength", "Int", "length({name})", List.of("name"))
        .filterable()
        .sortable()
        .addField()
        .oneRelation("vendor", "Vendor", "vendor_id", "id")
        .addRelation()
        .manyRelation("reviews", "Review", "id", "product_id")
        .relayConnectionWithTotalCount(false, true, 2, 0, 0, 10, 50)
        .relayPaginationArguments()
        .sortPathAscending("id", "id", "id", 0)
        .addRelation()
        .addType()
        .type("Vendor")
        .table("catalog", "vendors", "vendors", "id")
        .scalarField("id", "id")
        .scalarField("name", "name")
        .addType()
        .type("Review")
        .table("catalog", "reviews", "reviews", "id")
        .scalarField("id", "id")
        .scalarField("body", "body")
        .addType()
        .build();

String sdl = model.schemaDefinition();
```

Expected generated artifacts include a `Product` type, `product` point root,
`products` Relay connection root, generated root arguments such as `vendorId`,
generated sort/filter input metadata, `ReviewConnection`, and the Relay
arguments on `Product.reviews`.

## Current Boundaries

- The public builder creates projection descriptors; it is not a runtime
  registry or deployment mechanism.
- YAML parsing remains explicit through the model document parser and adapter.
- Management workflows are separate under `/admin/graphql`; the builder does not deploy them.
- SDL is generated output and should not be edited as source truth.
- Runtime/lowered support still follows the existing Java/SQL equivalence rule.
- The API is additive over the current descriptor surface; unsupported future
  model features should be added only with validation and test coverage.
