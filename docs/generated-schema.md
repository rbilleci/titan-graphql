# Generated Demo Schema

This is the current GraphQL schema surface generated from the demo projection model:

```text
DemoBlogGraphqlSchema.projectionModel(policy)
  -> ProjectionGraphqlAdapter.adapt(...)
  -> GraphqlSchemaPrinter.print(...)
```

The SDL below is an inspectable snapshot of the generated surface, not a second schema source.
Projection definitions in `DemoBlogGraphqlSchema` remain the source of truth.
Executable introspection is policy-disabled by default for the current runtime; root `__schema` and `__type`
return GraphQL-shaped validation errors unless the internal request policy enables introspection.
The enabled Java-mode and public SQL-mode smoke subset covers
`__schema { description mutationType { name } subscriptionType { name } queryType { name } types { name kind } }` and
`__schema { directives { name description isRepeatable locations args { name type { name kind ofType { name kind } } } } }`,
`__type(name:) { name kind description fields(includeDeprecated: true|false) { name description isDeprecated deprecationReason type { name kind description ofType { name kind description } } args { name description defaultValue isDeprecated deprecationReason type { name kind description ofType { name kind description ofType { name kind description } } } } } inputFields(includeDeprecated: true|false) { name description defaultValue isDeprecated deprecationReason type { name kind description ofType { name kind description } } } enumValues(includeDeprecated: true|false) { name description isDeprecated deprecationReason } }`.
Argument introspection is bounded to generated root and relation argument names. Type-reference introspection
now includes the bounded `ofType` wrapper chain for exposed field and argument references, including non-null
root/relation fields, required point-id arguments, and list-valued generated `orderBy` arguments. Input-field
introspection is bounded to generated filter and order input objects. Enum-value introspection is bounded to
the generated `SortDirection` value names. Literal `includeDeprecated` is accepted on field and enum-value
lists, and on input-field lists, though the generated demo schema does not expose deprecated members yet.
`__InputValue.defaultValue`
is exposed for the bounded argument and input-field surface and currently returns `null` because the generated
demo schema has no declared argument defaults. `description` is exposed across the bounded type, field,
input-value, enum-value, and type-reference surface and currently returns `null` because the generated demo
schema has no declared description metadata. Field, enum-value, and input-value deprecation metadata is
exposed for the bounded generated surface and currently returns `isDeprecated: false` and
`deprecationReason: null` because the generated demo schema has no deprecated members. Broader
introspection fields such as real generated descriptions and real generated deprecations remain pending.
Directive introspection is bounded to names, null descriptions, repeatability, locations, and argument type
references for the runtime `include`/`skip` directives and generated `relationSortPath` schema directive.
Richer directive metadata such as non-null default literals and real generated descriptions remains pending.
Schema description, mutation root, and subscription root introspection are exposed as nullable metadata and
currently return `null` because the generated demo schema has no schema description and the accepted
contract excludes mutation and subscription execution.

```graphql
directive @relationSortPath(name: String!, column: String!, path: String!, hops: Int!, direction: String!, tieBreaker: String!) repeatable on FIELD_DEFINITION

type Query {
  article(id: Int!): Article
  articles(first: Int, after: String, last: Int, before: String, authorId: Int, filter: ArticleFilter, orderBy: [ArticleOrderBy!]): ArticleConnection!
}

input IntFilter {
  eq: Int
  neq: Int
  in: [Int!]
  isNull: Boolean
  lt: Int
  lte: Int
  gt: Int
  gte: Int
}

input StringFilter {
  eq: String
  neq: String
  in: [String!]
  isNull: Boolean
  contains: String
  startsWith: String
  endsWith: String
}

input ArticleFilter {
  id: IntFilter
  title: StringFilter
  titleLength: IntFilter
  authorId: IntFilter
  authorName: StringFilter
  and: [ArticleFilter!]
  or: [ArticleFilter!]
  not: ArticleFilter
}

enum SortDirection {
  ASC
  DESC
}

input ArticleOrderBy {
  id: SortDirection
  title: SortDirection
  titleLength: SortDirection
  authorName: SortDirection
}

type Article {
  id: Int
  title: String
  titleLength: Int
  author: User!
  comments(first: Int, after: String, last: Int, before: String): CommentConnection! @relationSortPath(name: "id", column: "id", path: "id", hops: 0, direction: "ASC", tieBreaker: "id")
}

type User {
  id: Int
  name: String
  email: String
}

type Comment {
  id: Int
  body: String
  author: User!
}

type ArticleConnection {
  edges: [ArticleEdge!]!
  pageInfo: PageInfo!
  totalCount: Int!
}

type ArticleEdge {
  cursor: String!
  node: Article!
}

type CommentConnection {
  edges: [CommentEdge!]!
  pageInfo: PageInfo!
  totalCount: Int!
}

type CommentEdge {
  cursor: String!
  node: Comment!
}

type PageInfo {
  hasNextPage: Boolean
  hasPreviousPage: Boolean
  startCursor: String
  endCursor: String
}
```

## Projection Mapping

- `ProjectionRetrieval.point("article", "Article", "id")` becomes `article(id: Int!): Article`.
- `ProjectionRetrieval.relayConnection("articles", ...)` becomes the `articles` root connection with Relay `first`, `after`, `last`, `before`, the declared `authorId` equality filter, and generated filter paths such as `authorName`.
- `ProjectionType` entries become object types. Column-backed `ProjectionField` entries become scalar fields, and promoted computed-expression fields such as `Article.titleLength` become scalar fields backed by declared expression metadata and required source columns.
- `ProjectionRelation.one(...)` entries become object relation fields such as `Article.author: User!`.
- Relay-capable `ProjectionRelation.many(...)` entries become connection fields such as `Article.comments: CommentConnection!`; relation connections expose `totalCount` only when the relation declares safe exact count support.
- Scalar and relation-hop filter capability metadata is emitted as generated input types such as `ArticleFilter`, `IntFilter`, and `StringFilter`.
- Root sort-path metadata is emitted as generated order input types such as `ArticleOrderBy` plus `SortDirection`.
- Root context-filter metadata, such as the bounded `articles.publishedVisibility` filter over the `published` column, is carried into read plans, can be activated from internal Java-mode request context and the bounded public SQL-mode context entrypoint, and is not exposed as a client argument or SDL field.
- Relation sort-path metadata is emitted as `@relationSortPath(...)` so generated SDL exposes the stable cursor/sort contract.
- Field visibility policies, such as `User.email`, are enforced during validation and execution. They do not currently alter this static SDL snapshot.
