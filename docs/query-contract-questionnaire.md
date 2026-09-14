# Titan GraphQL Query Contract Questionnaire

This document is the decision worksheet for point 1 of the next Titan GraphQL expansion plan: define the target GraphQL query contract, excluding mutations for now.

Each question includes a recommended default. The defaults bias toward a complete, production-useful query engine while keeping Titan's SQL-lowerable execution model explicit and safe.

## A. Scope And Product Boundary

1. Should Titan GraphQL target GraphQL query operations only for the next phase? Recommendation: yes; exclude mutations and subscriptions until query planning, security, and SQL lowering are mature. Decision: Yes, exclude for now.
2. Should subscriptions be considered part of "complete" for this roadmap? Recommendation: no; document subscriptions as out of scope alongside mutations. Decision: no.
3. Should the project aim for full GraphQL spec compatibility or a declared compatible subset? Recommendation: declare a compatible subset first, then track spec gaps explicitly. Decision: I agree with the recommendation.
4. Should unsupported spec features fail validation or be ignored? Recommendation: fail validation with GraphQL-shaped errors.  Decision: I agree with the recommendation.
5. Should Titan GraphQL be positioned as a general server replacement? Recommendation: no; position it as a database-resident query execution adapter over a projection metamodel. Decision: It should have a lightweight quarkus frontend that uses Titan to call into the query execution engine that has been transpiled/lowered into functions/procedures. There should be a simple server engine in front that speaks HTTP and bridges external access.
6. Should the query contract be projection-model first or GraphQL-schema first? Recommendation: projection-model first; GraphQL SDL should be generated from it. Decision: I agree with the recommendation.
7. Should the public contract be stable enough for external clients? Recommendation: not yet; mark the next phase as contract-forming until the questionnaire decisions are closed. Decision: I agree with the recommendation, but generally, external clients are speaking GraphQL, so I'm not sure how relevant this question is.
8. Should each supported feature require both Java-mode and SQL-mode support? Recommendation: yes for "production supported"; allow Java-only experiments behind explicit labels. Decision: yes.
9. Should a query feature be exposed in generated SDL before SQL lowering supports it? Recommendation: only if the schema marks it experimental or Java-only; otherwise no. Decision: I agree with the recommendation.
10. Should the roadmap separate "accepted by parser" from "supported by planner"? Recommendation: yes; parsing can be broader, validation/planning must enforce supported capability boundaries. Decision: I agree with the recommendation.

## B. Operations And Documents

11. Should anonymous single query operations remain supported? Recommendation: yes. Decision: I agree with the recommendation.
12. Should named query operations be supported? Recommendation: yes; useful for observability and client debugging. Decision: I agree with the recommendation.
13. Should multi-operation documents be supported? Recommendation: yes, but require an operation name when more than one operation exists. Decision: I agree with the recommendation.
14. Should operation type `query` be required explicitly? Recommendation: no; support both shorthand selection sets and explicit `query`. Decision: see how it is done in Apollo and Hasura as references.
15. Should mutation operation syntax parse but fail validation? Recommendation: yes; return a clear "mutations are not supported" error. Decision: I agree with the recommendation.
16. Should subscription operation syntax parse but fail validation? Recommendation: yes; return a clear "subscriptions are not supported" error. Decision: I agree with the recommendation.
17. Should operation-level directives be supported? Recommendation: support only `@include` and `@skip` initially. Decision: I agree with the recommendation.
18. Should operation descriptions be relevant at runtime? Recommendation: no; descriptions belong in schema generation, not executable query documents. Decision: I agree with the recommendation.
19. Should query documents allow comments? Recommendation: yes; GraphQL comments are harmless and useful. Decision: I agree with the recommendation.
20. Should the parser preserve source locations? Recommendation: yes; needed for useful GraphQL-shaped errors. Decision: I agree with the recommendation.

## C. Selection Sets, Aliases, And Response Shape

21. Should field aliases be supported? Recommendation: yes; aliases are essential for real GraphQL clients. Decision: I agree with the recommendation.
22. Should aliases affect authorization or planning semantics? Recommendation: no; resolve policy and planning against the underlying field name. Decision: I agree with the recommendation.
23. Should duplicate response keys be merged according to GraphQL validation rules? Recommendation: yes; reject conflicting field selections. Decision: I agree with the recommendation.
24. Should selection ordering be preserved in JSON output? Recommendation: yes where practical, matching query order for predictable snapshots. Decision: I agree with the recommendation.
25. Should `__typename` be supported? Recommendation: yes; clients rely on it, especially once interfaces/unions arrive. Decision: I agree with the recommendation.
26. Should meta fields be supported on root query type? Recommendation: support `__schema`, `__type`, and `__typename` once introspection is enabled. Decision: I agree with the recommendation.
27. Should scalar fields require no selection set? Recommendation: yes; reject scalar fields with nested selections. Decision: I agree with the recommendation.
28. Should object/relation fields require selection sets? Recommendation: yes; reject empty object selections. Decision: I agree with the recommendation.
29. Should relation fields support aliases independently of relation names? Recommendation: yes; aliases should only shape the response key. Decision: I agree with the recommendation.
30. Should response JSON include `data` and `errors` top-level fields? Recommendation: yes; move from raw payload/error strings toward GraphQL response shape. Decision: I agree with the recommendation.

## D. Arguments, Variables, And Input Coercion

31. Should variables be supported? Recommendation: yes; variables are required for normal client operation. Decision: I agree with the recommendation.
32. Should default variable values be supported? Recommendation: yes, with spec-compatible coercion. Decision: I agree with the recommendation.
33. Should unused variables fail validation? Recommendation: yes. Decision: I agree with the recommendation.
34. Should required variables be enforced before planning? Recommendation: yes. Decision: I agree with the recommendation.
35. Should variable values be accepted as JSON? Recommendation: yes; this matches standard GraphQL execution APIs. Decision: I agree with the recommendation.
36. Should input object values be supported? Recommendation: yes, because filters and complex arguments will need them. Decision: I agree with the recommendation.
37. Should list input values be supported? Recommendation: yes, especially for `in` filters and batch root lookups. Decision: I agree with the recommendation.
38. Should enum input values be supported? Recommendation: yes; map them through generated schema metadata. Decision: I agree with the recommendation.
39. Should custom scalar input coercion be pluggable? Recommendation: yes, but begin with built-in coercers for ID, String, Int, Float, Boolean, and timestamp-like scalars. Decision: I agree with the recommendation.
40. Should unknown arguments fail validation? Recommendation: yes; never ignore unknown client input. Decision: I agree with the recommendation.

## E. Fragments And Directives

41. Should named fragments be supported? Recommendation: yes; most generated clients depend on them. Decision: I agree with the recommendation.
42. Should inline fragments be supported? Recommendation: yes; required for future interfaces/unions and conditional type selections. Decision: I agree with the recommendation.
43. Should fragment cycles be rejected? Recommendation: yes. Decision: I agree with the recommendation.
44. Should unused fragments fail validation? Recommendation: yes, unless a compatibility mode says otherwise. Decision: I agree with the recommendation.
45. Should fragment type conditions be validated against generated schema types? Recommendation: yes. Decision: I agree with the recommendation.
46. Should `@include(if:)` be supported? Recommendation: yes; execute it during selection normalization. Decision: I agree with the recommendation.
47. Should `@skip(if:)` be supported? Recommendation: yes; execute it during selection normalization. Decision: I agree with the recommendation.
48. Should custom directives be supported in executable queries? Recommendation: parse and validate known directives only; reject unknown executable directives initially. Decision: I agree with the recommendation.
49. Should schema directives be generated? Recommendation: yes for metadata such as relation sort paths, deprecations, and policy-visible annotations. Decision: I agree with the recommendation.
50. Should directive conditions be allowed to reference variables? Recommendation: yes, with Boolean coercion before planning. Decision: I agree with the recommendation.

## F. Type System And Schema Surface

51. Should generated SDL become the primary external contract? Recommendation: yes; generate and snapshot it from the projection metamodel. Decision: I agree with the recommendation.
52. Should introspection be supported? Recommendation: yes, but allow it to be disabled by policy in production. Decision: I agree with the recommendation.
53. Should descriptions be generated into SDL? Recommendation: yes; useful for client tooling. Decision: I agree with the recommendation.
54. Should deprecation metadata be supported? Recommendation: yes for fields, enum values, and eventually arguments. Decision: I agree with the recommendation.
55. Should `ID` be distinct from `Int`? Recommendation: yes; support GraphQL `ID` as a scalar even if backed by numeric columns. Decision: I agree with the recommendation.
56. Should enum types be supported? Recommendation: yes; projection metadata should define enum mappings. Decision: I agree with the recommendation.
57. Should custom scalars be supported? Recommendation: yes, with explicit coercion and serialization hooks. Decision: I agree with the recommendation.
58. Should interfaces be supported? Recommendation: yes, after object basics are stable. Decision: I agree with the recommendation.
59. Should unions be supported? Recommendation: yes, after interfaces or alongside them if the metamodel can model type resolution. Decision: I agree with the recommendation.
60. Should input object types be generated? Recommendation: yes; filters, sorting, and complex arguments should use input objects instead of ad hoc flat arguments. Decision: I agree with the recommendation.

## G. Roots, Relations, Pagination, Filtering, And Sorting

61. Should root fields be generated only from declared root projections? Recommendation: yes. Decision: I agree with the recommendation.
62. Should every relation be queryable as a root automatically? Recommendation: no; direct root access must be explicitly declared. Decision: I agree with the recommendation.
63. Should Relay connections remain the durable list contract? Recommendation: yes. Decision: I agree with the recommendation.
64. Should offset pagination be supported at all? Recommendation: only as an explicit non-durable compatibility capability, not the default. Decision: I agree with the recommendation.
65. Should backward Relay pagination be supported wherever forward pagination is supported? Recommendation: support only when declared by the retrieval operation. Decision: follow the relay spec.
66. Should cursor format be opaque to clients? Recommendation: yes; clients should not depend on encoded fields. Decision: I agree with the recommendation.
67. Should cursor decoding validate type, field, direction, and projection identity? Recommendation: yes; reject mismatched cursors. Decision: I agree with the recommendation.
68. Should filters be represented as generated input objects? Recommendation: yes; prefer structured filter inputs over many flat arguments. Decision: I agree with the recommendation.
69. Should arbitrary boolean filter expressions be supported? Recommendation: eventually, but only through declared capability-bounded filter operators. Decision: I agree with the recommendation.
70. Should relation-hop filters be supported? Recommendation: yes, with explicit hop budgets and efficient lowering requirements. Decision: I agree with the recommendation.
71. Should sorting be represented as generated input objects? Recommendation: yes; include direction and declared sortable paths. Decision: I agree with the recommendation.
72. Should clients sort by arbitrary selected fields? Recommendation: no; only declared sort paths. Decision: I agree with the recommendation.
73. Should null ordering be configurable? Recommendation: yes; make it part of sort capability metadata. Decision: I agree with the recommendation.
74. Should total counts be exposed on connections? Recommendation: not by default; support only when a retrieval declares an efficient count strategy. Decision: Yes, total counts should be available.
75. Should node lookup by global ID be supported? Recommendation: eventually yes, after ID scalar and type registry decisions are closed. Decision: I agree with the recommendation.

## H. Security, Limits, And Resource Controls

76. Should field-level authorization reject the whole query or return partial data? Recommendation: reject by default; allow partial mode only after error semantics are mature. Decision: I agree with the recommendation.
77. Should row visibility be applied in retrieval predicates? Recommendation: yes wherever possible. Decision: I agree with the recommendation.
78. Should relation edge visibility be distinct from node visibility? Recommendation: yes; model edge policy explicitly. Decision: I agree with the recommendation.
79. Should hidden fields be omitted from SDL for unauthorized actors? Recommendation: not initially; use a stable schema plus validation-time rejection unless product needs actor-shaped schemas. Decision: I agree with the recommendation.
80. Should introspection respect authorization? Recommendation: yes, especially if actor-shaped schemas are introduced. Decision: I agree with the recommendation.
81. Should query depth limits be enforced? Recommendation: yes, with separate selection, filter, and sort depth budgets. Decision: I agree with the recommendation.
82. Should query complexity scoring be enforced? Recommendation: yes; count fields, relations, page sizes, filter costs, and planned row bounds. Decision: I agree with the recommendation.
83. Should maximum page size be global or per field? Recommendation: both; global ceiling plus per-retrieval limits. Decision: I agree with the recommendation.
84. Should execution timeout be part of the query contract? Recommendation: yes; expose predictable timeout errors. Decision: I agree with the recommendation.
85. Should raw query text be logged? Recommendation: no by default; log normalized shape and hashes unless explicitly enabled. Decision: I agree with the recommendation.

## I. Execution And SQL Lowering Contract

86. Should Java-mode be the reference implementation? Recommendation: yes; SQL-mode should prove equivalence against it. Decision: I agree with the recommendation.
87. Should SQL-mode be generated from the projection model? Recommendation: yes; avoid hand-maintained semantic kernels. Decision: I agree with the recommendation.
88. Should unsupported Java constructs be allowed in Java-mode if SQL-mode cannot lower them? Recommendation: only in explicitly Java-only experimental paths. Decision: I agree with the recommendation.
89. Should response serialization happen in Java-transpiled logic or SQL JSON functions? Recommendation: generate target-specific lowerers, but keep response semantics in shared planning/tests. Decision: I agree with the recommendation.
90. Should read plans be inspectable for every accepted query? Recommendation: yes. Decision: I agree with the recommendation.
91. Should plan output include chosen retrieval operations? Recommendation: yes. Decision: I agree with the recommendation.
92. Should plan output include estimated row bounds and complexity? Recommendation: yes. Decision: I agree with the recommendation.
93. Should N+1 avoidance be a validation requirement? Recommendation: not validation, but plan tests should reject regressions for supported relation shapes. Decision: I agree with the recommendation.
94. Should batching be required for all one-to-many relations? Recommendation: require it when declared batchable; reject or cap shapes that cannot batch efficiently. Decision: I agree with the recommendation.
95. Should SQL generated artifacts be snapshot-tested? Recommendation: yes for important query classes and helper lowering boundaries. Decision: I agree with the recommendation.

## J. Errors, Observability, And Test Closure

96. Should errors follow GraphQL `errors[].message/path/locations/extensions` shape? Recommendation: yes. Decision: I agree with the recommendation.
97. Should validation errors and execution errors use different extension codes? Recommendation: yes; stable codes help clients and tests. Decision: I agree with the recommendation.
98. Should partial data be allowed on execution errors? Recommendation: eventually yes for runtime field errors; start with fail-closed validation errors. Decision: I agree with the recommendation.
99. Should selected GraphQL spec tests be imported? Recommendation: yes, after the contract document maps which tests are expected to pass, fail, or be out of scope. Decision: I agree with the recommendation.
100. Should this questionnaire close into a formal contract document? Recommendation: yes; convert accepted answers into `docs/query-contract.md` and make future roadmap items refer to it. Decision: I agree with the recommendation.

## Recommended Closure Path

1. the maintainer reviews each recommendation and marks accept/change/defer.
2. Accepted answers become `docs/query-contract.md`.
3. Deferred answers become explicit out-of-scope or later-phase items.
4. The next implementation roadmap should only include features whose contract answers are closed.

## Additional requirements
1. The projection runtime must allow for fields with computed expressions. When possible, sorting, filtering should be possible with computed expressions
2. The projection model needs a "filtering mechanism" (think of hibernate-style filters), that - if enabled for a query based on the context - apply filters to returned results.
