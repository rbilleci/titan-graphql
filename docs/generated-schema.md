# Generated Demo Schema

This is a test-fixture SDL snapshot for the demo projection model:

```text
DemoBlogGraphqlSchema.projectionModel(policy)
  -> ProjectionGraphqlAdapter.adapt(...)
  -> GraphqlSchemaPrinter.print(...)
```

The SDL block is checked against the test-only `DemoBlogGraphqlSchema` fixture. The reviewed YAML
model and installed database package, not this file, define the deployed schema. Installed
PostgreSQL/MySQL introspection and policy behavior is specified in
[query-contract.md](query-contract.md) and tested by the release gate.

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
- Root context-filter metadata, such as `articles.publishedVisibility`, is not exposed as a client argument or SDL field; the installed engine applies it from trusted request context.
- Relation sort-path metadata is emitted as `@relationSortPath(...)` so generated SDL exposes the stable cursor/sort contract.
- Field visibility policies, such as `User.email`, are enforced during validation and execution. They do not currently alter this static SDL snapshot.
