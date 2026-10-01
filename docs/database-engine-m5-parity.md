# M5 legacy-capability parity map

This map classifies every contract row in `query-contract-conformance.md` against the database-resident package. M6 removed the old Java/SQL implementation after mapping its useful assertions to installed-package and standalone HTTP tests. The fixed corpus pins representative portable results; installed-package tests retain the broader reviewed surface. The standalone HTTP tests replace the former Quarkus transport assertions.

| Legacy contract IDs | Current replacement evidence | Disposition |
| --- | --- | --- |
| `QC1-OPERATIONS-QUERY`, `QC1-OPERATIONS-NAMED`, `QC1-OPERATIONS-SELECTED`, `QC1-OPERATIONS-MULTI-MISSING`, `QC1-OPERATIONS-MUTATION`, `QC1-OPERATIONS-SUBSCRIPTION` | Named/selected operation, mutation, unsupported subscription, and ambiguous-operation cases run on both installed dialects and through the standalone ZIP. | HTTP GET mutation denial is separately asserted with no database write. |
| `QC1-REQUEST-ENVELOPE`, `QC11-QUARKUS-POST`, `QC11-QUARKUS-GET` | The standalone ZIP corpus forwards query, operation name, variables, role, and introspection context; isolated HTTP tests exercise GET and POST. | Standalone GET/POST assertions replace the removed Quarkus routes. |
| `QC2-ERROR-SHAPE` | The corpus pins exact lexical, validation, authorization, resource-limit, and execution error JSON, including source locations where promised. | Root, field, and relation policy locations and a later-field mutation failure are fixed cases; HTTP also checks row and audit rollback. |
| `QC3-VARIABLES-SCALAR`, `QC3-INPUT-OBJECTS` | Named variables, nullable/defaulted inputs, authored nested input objects, generated argument defaults, and typed-input budgets are in the corpus. Both installed suites retain broader coercion failures. | Preserve direct-only negative coercion assertions during M6 test migration. |
| `QC4-ALIASES`, `QC4-TYPENAME` | Multiple roots, aliases, merged connection fields, wrapper aliases, abstract branches, and `__typename` are in the corpus. | No known portable gap for the reviewed v1alpha1 surface. |
| `QC5-FRAGMENTS`, `QC5-DIRECTIVES-LITERAL`, `QC5-DIRECTIVES-VARIABLE` | Reachable fragments, abstract fragments, built-in directives, and registered conditional directives are in the corpus; both installed suites retain invalid directive argument/location cases. | Preserve direct-only negative assertions during M6 test migration. |
| `QC6-FILTER-SCALAR`, `QC6-FILTER-RELATION`, `QC6-SORT-LOCAL`, `QC6-SORT-RELATION`, `QC6-SORT-CURSOR` | Scalar/enum filtering, local/relation ordering, and ordered cursor-window cases are in the corpus. Relation filtering and additional windows are exercised by direct installed-package tests on both dialects. | Preserve direct relation-filter assertions during M6 test migration. |
| `QC7-TOTAL-COUNT-ROOT`, `QC7-TOTAL-COUNT-RELATION` | Root and relation Relay counts/pages are exercised by both installed-package suites; root counts also occur in the corpus. | Preserve direct relation page-window assertions during M6 test migration. |
| `QC8-COMPUTED-SELECT`, `QC8-COMPUTED-FILTER-SORT` | Both installed-package suites exercise reviewed computed projections, filtering, and ordering. | Preserve direct computed-field assertions during M6 test migration. |
| `QC9-CONTEXT-FILTER` | Both installed-package suites and the fixed corpus prove tenant-A/tenant-B isolation and fail-closed missing context; the same cases pass through standalone HTTP. | Preserve direct cursor-window tenant-filter assertions during M6 test migration. |
| `QC10-INTROSPECTION-STABLE`, `QC10-INTROSPECTION-SCHEMA-NULLABLES`, `QC10-INTROSPECTION-FIELD-ARGS`, `QC10-INTROSPECTION-TYPE-WRAPPERS`, `QC10-INTROSPECTION-INPUT-FIELDS`, `QC10-INTROSPECTION-ENUM-VALUES`, `QC10-INTROSPECTION-INCLUDE-DEPRECATED`, `QC10-INTROSPECTION-INPUT-INCLUDE-DEPRECATED`, `QC10-INTROSPECTION-DEFAULT-VALUES`, `QC10-INTROSPECTION-DESCRIPTIONS`, `QC10-INTROSPECTION-DEPRECATIONS`, `QC10-INTROSPECTION-INPUT-DEPRECATIONS`, `QC10-INTROSPECTION-DIRECTIVES`, `QC10-INTROSPECTION-DISABLE` | The corpus pins enum/directive metadata, generated argument defaults, abstract Relay type metadata, authorization, and introspection budget behavior. Both installed-package suites cover the broader reviewed introspection surface. | Preserve direct wrapper/deprecation/input-field metadata assertions during M6 test migration. |
| `QC10-MATRIX` | Documentation inventory, not a request behavior. | Retain the archived contract inventory and its current assertion map. |
| `APP-MUTATION-EXECUTION` | Corpus mutation cases and M4 installed-package, HTTP replay, container, audit, idempotency, outbox, and rollback gates pass on both dialects. | The corpus pins later-field failure; the standalone HTTP test verifies the prior row and audit write are rolled back. |
| `OOS-SUBSCRIPTION-EXECUTION`, `OOS-ACTOR-SHAPED-SCHEMA` | Deliberately excluded from the reviewed v1alpha1 runtime. Subscription requests fail inside the database engine; actor-shaped schema redaction is not claimed. | No parity case is required beyond explicit rejection and the documented exclusion. |

## M6 case disposition

`database-engine-m6-case-disposition.tsv` records a disposition for each case in the immutable
`557eb3e:src/test/java/io/titan/graphql/conformance/GraphqlSqlModeConformanceCorpus.java`
inventory. The historical corpus compared the old Java and SQL implementations with each other;
it did not pin independent expected results. `portable-semantics` means the behavior survives in the
reviewed database package, not that the old demo schema's response bytes are retained.

| Evidence key | Current evidence |
| --- | --- |
| `portable-corpus` | `src/test/resources/database-engine-corpus/commerce-v1.json`, exercised by the installed PostgreSQL/MySQL package tests and standalone HTTP corpus test. |
| `installed-read` | Read, filter, order, cursor, relation, count, and computed-field assertions in `CommerceDatabaseGraphqlEngineIT` and `CommerceDatabaseGraphqlMySqlEngineIT`. |
| `installed-coercion` | Variable, typed-input, and argument validation assertions in the installed Commerce package tests and fixed corpus. |
| `installed-introspection` | Schema/type/field/argument metadata and disabled-introspection assertions in the installed Commerce package tests and fixed corpus. |
| `tenant-corpus` | The fixed corpus's tenant-A, tenant-B, and missing-context cases replace the demo's published-visibility policy with the reviewed tenant policy. |
| `installed-mutations` | Installed Commerce mutation, rollback, audit, and idempotency assertions plus the container deployment gate replace the old read-only-mode rejection. |
| `standalone-http` | `DatabaseGraphqlHttpFrontendIT` and `DatabaseGraphqlHttpServerPreviewTest` replace Quarkus GET/POST and preview transport assertions. |

The `old-read-only-mode-obsolete`, `old-entry-shape-obsolete`, `context-policy-replaced`, and
`transport-replaced` dispositions identify behavior that should not survive as a compatibility
path. M6 completed source, dependency, artifact, and clean-checkout verification; the migration
map supports the deletion gate recorded in `database-engine-execution-plan.md`.
