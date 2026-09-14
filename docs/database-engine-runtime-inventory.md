# Database-engine migration inventory

Status: Phase 0 inventory for [database-engine-execution-plan.md](database-engine-execution-plan.md).

This records the serving paths at baseline `b1722b7`. It is an implementation and deletion checklist,
not a statement that the current architecture satisfies the database-engine target.

| Boundary | Current implementation | Current execution location | Target disposition |
| --- | --- | --- | --- |
| `/graphql` POST/GET | `GraphqlHttpResource` -> `GraphqlExecutionEngine` | HTTP process chooses `compiled`, `jdbc`, `java`, or `sql` mode; GET parses the document to reject mutations | Keep HTTP transport/authenticated context only; call the package-bound whole-request routine once. |
| `compiled` mode | `TitanCompiledGraphqlRuntime` -> `GraphqlEngine` -> `TitanCompiledGraphqlDataModel` | JVM parses, validates, plans, evaluates policies, schedules reads/batches, and assembles JSON; database runs generated carriers | Replace with database invocation adapter. Delete JVM GraphQL semantics from serving artifact. |
| `jdbc` mode | `GenericJdbcGraphqlRuntime` -> `GenericJdbcGraphqlDataModel` | JVM GraphQL engine and direct JDBC reads | Delete after portable whole-request tests migrate. |
| `java` mode | `GraphqlRuntimeRegistry` -> `DemoBlogGraphqlRuntime` / fixture model | JVM demo parser/executor and fixture data | Retain only temporary build/test oracle material while migrating; delete serving mode and fixture runtime. |
| `sql` mode | `GraphqlSqlModeRuntime` -> `GraphqlSqlEntryPointDispatch` | Thin JDBC call to a database routine, but routine is hard-coded `DemoBlogTitanGraphqlFunctions` | Reuse the package-inventory invocation pattern; remove demo method identities, fixed context tuple, and legacy mode. |
| Whole-request stored function | `DemoBlogTitanGraphqlFunctions` | Database after transpilation, but hard-coded blog parsing, schema, data, policies, and JSON shapes | Migrate proven parser/rendering tests; delete class. The replacement is generated-schema bindings plus shared transpilable engine source. |
| Generated carrier source | `TitanGraphqlRoutineSourceGenerator` -> `GeneratedTitanGraphqlReads` | Database executes point/page/count/relation carriers; JVM orchestrates them | Retain/adapt as database-internal access helpers. Remove carrier-only public serving contract. |
| Application mutations | `GraphqlApplicationMutationProvider`, `GraphqlMutationExecutor`, CDI handlers/audit hooks | JVM validates/dispatches; handler writes through application connection | Replace with build-time registry and transpilable or installed routine implementations. |
| Preview endpoint | `GraphqlPreviewHttpResource` -> `GraphqlPreviewRuntimeRouter` | JVM GraphQL engine over preview candidates | Move retained preview query processing to separately bound database packages; move build/file work to control-plane jobs. |
| Admin endpoint | `GraphqlAdminHttpResource` -> management runtime/data model | JVM GraphQL management mutations and stores | Move retained GraphQL semantics to database engine; preserve management orchestration as explicit control-plane operations. |
| Shared GraphQL semantics | `GraphqlLexer`, `GraphqlParser`, `GraphqlVariableCoercer`, `GraphqlValidator`, `GraphqlReadPlanner`, `GraphqlCursorCodec`, `GraphqlIntrospection`, `GraphqlJsonWriter` | JVM process | Port or replace in the transpilable engine source boundary; remove production JVM copies after migration. |
| Artifact binding | `TitanGraphqlPackageBinding`, metadata/inventory classes | JVM verifies package before dispatch; compiled data model attests per read | Keep and extend identity to engine/generator/mutation sources. Verify inside whole-request execution before data access. |

## Boundaries to preserve

- HTTP authentication and construction of trusted request context.
- JDBC connection acquisition, explicit transaction commit/rollback, deadline/cancellation, media
  negotiation, response headers, and database-availability mapping.
- Build-time model parsing, validation, schema inference, code generation, artifact inspection, and
  package installation verification.
- Test-only reference/oracle code until independent expected-result tests replace the useful coverage.

## Phase 0 completion evidence

- [x] Baseline commit and pinned Titan/Titan DSL revisions recorded.
- [x] `/graphql`, preview, and admin serving paths mapped.
- [x] Existing database whole-request path distinguished from carrier-backed compiled mode.
- [x] Old production/runtime deletion candidates listed with replacement intent.
- [ ] Create separate build-time, transpilable-engine, generated-schema, thin-frontend, and test
      dependency boundaries.
- [ ] Add and satisfy a production artifact boundary test: no JVM GraphQL execution implementation
      is loadable by the final frontend artifact.
- [ ] Capture portable fixed expected-result corpus independent of the demo whole-request implementation.
