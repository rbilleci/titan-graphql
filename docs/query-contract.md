# Titan GraphQL Query Contract

Status: active database-engine contract. The legacy compiled-query contract is retained only while
its behavior is migrated or explicitly retired.

This document states the behavior exposed by the schema-driven runtime. It is deliberately narrower
than the full GraphQL specification. Parser support does not imply that every parsed shape can be
lowered; unsupported plans return GraphQL errors and never fall back to handwritten or demo reads.

## Contract Principles

- The reviewed projection model is the source of schema, policy, and lowering semantics.
- Generated SDL and introspection expose only modeled capabilities.
- Production requests use the bound Titan-transpiled whole-request package and typed database values.
- A feature is part of the database-engine contract only after live PostgreSQL and MySQL proof.
- Policy is applied before protected data access and repeated in generated carriers where specified.
- Unsupported shapes fail closed without switching execution mode.
- Application writes are explicit registered commands, never inferred table CRUD.

## HTTP Transport

`POST /graphql` accepts queries and registered custom mutations with
`Content-Type: application/json`. The request envelope is:

```json
{
  "query": "query Example($id: ID!) { node(id: $id) { id } }",
  "operationName": "Example",
  "variables": { "id": "node-id" },
  "extensions": {}
}
```

`query` is required. `operationName` is required for a document containing multiple operations.
`variables` and `extensions`, when present, must be JSON objects. Unknown envelope fields and an
unsupported POST media type are rejected. Responses use
`application/graphql-response+json` when accepted, with `application/json` fallback.

`GET /graphql` accepts the same query document plus optional `operationName`, `variables`, and
`extensions` query parameters, but only for query operations. The standard serving configuration
allows only `database` mode and forwards the untouched document with
`allowMutations=false`; the transpiled engine selects and rejects a mutation before effects.
The historical transport parser is reachable only when an explicit test/reference configuration
enables a legacy mode.

Every response names the selected execution mode in `X-Titan-Execution-Mode`. Database and legacy
reference-mode responses also include `X-Titan-Deployment-Fingerprint`; unreadable binding metadata
surfaces as `unavailable`, while request execution still fails closed.

Caller-supplied `X-Titan-*` context headers are ignored by default. With no trusted gateway context,
the actor role is blank. See [../SECURITY.md](../SECURITY.md).

## Operations and Documents

The parser and validator support:

- shorthand and explicit query operations;
- named and anonymous operations;
- selected operations in multi-operation documents;
- variables and default values;
- field and relation aliases;
- named and inline fragments with cycle/type-condition validation;
- `@include(if:)`, `@skip(if:)`, and model-registered conditional directives; and
- parse locations and stable GraphQL-shaped error extensions.

Subscriptions and unknown executable directives are rejected. Registered conditional directives
are declared in the model, have exactly one required `if: Boolean!` argument, are non-repeatable,
and may be used only at their reviewed `FIELD`, `FRAGMENT_SPREAD`, or `INLINE_FRAGMENT` locations.
Their `includeIf` or `skipIf` behavior is validated and evaluated by the transpiled engine before
database dispatch; disabled selections are still structurally validated. Directive definitions on
operations or fragment definitions remain unsupported. A registered descriptor-backed mutation may
execute only over POST and must select exactly one top-level mutation field.

Duplicate response keys must be merge-compatible. Scalars reject selection sets; object and relation
fields require them. Output uses response aliases without changing schema identity or policy lookup.

Reviewed interfaces and unions are part of the installed database-engine contract. A point root may
declare an abstract public return type while retaining one concrete physical projection. The engine
validates direct interface fields, requires union-specific fields to be selected through fragments,
rejects fragment conditions that cannot overlap the declared parent, and checks response-key
compatibility independently for each possible runtime object. Mutually exclusive concrete branches
may therefore reuse an alias for different fields, while overlapping interface/object branches may
not. Execution evaluates named and inline conditions against the concrete object's complete set of
interface and union memberships at point roots, nested relations, and Relay nodes; `__typename`
always returns the concrete object name. A Relay root backed by one reviewed concrete projection may
likewise expose an interface or union node type; its wrapper schema is abstract while each returned
node is completed with the known concrete runtime condition set. Connections that merge
heterogeneous physical projections remain unsupported pending an explicit cursor, ordering, policy,
and runtime-type-discriminator contract.

That restriction is a versioned `v1alpha1` model boundary, not a runtime fallback. A root has exactly
one required concrete `type`; the public JSON schema rejects unknown root members (including an
invented `projections` member), model validation requires any `outputType` to contain that concrete
type, and the generated package dispatches only the reviewed static projection. Introducing a
heterogeneous root therefore requires a later model version that defines source discrimination,
cross-source ordering and cursor stability, policy composition, count semantics, and batching. Until
then, such a schema cannot be packaged and no HTTP/JVM resolver may emulate it.

The same closure rule applies to schema metadata. The current model and transpiled introspection
cover every authorable v1alpha1 detail: object/interface descriptions and field deprecations, enum
value descriptions/deprecations, input-object field descriptions/defaults/deprecations, registered
directive descriptions/locations, generated argument defaults, abstract possible types, and the
standard introspection meta-schema fields. Metadata the model cannot author—root/argument
descriptions, custom-scalar `specifiedByURL`, repeatable custom directives, and one-of input
objects—is outside v1alpha1 rather than silently synthesized by the frontend. Those additions must
extend the model identity, validator, generator, transpiled introspection, and dual-dialect corpus
together.

Output-field metadata is schema-driven as well: authored object and interface descriptions and
deprecations are preserved in the model identity and rendered by the transpiled engine. As required
by GraphQL introspection, `__Type.fields` excludes deprecated fields by default and returns them,
with their reason, when `includeDeprecated: true` is supplied.

## Scalars and Nullability

The model/runtime scalar boundary includes `Int`, `Long`, `Float`, `Boolean`, `String`, `ID`, `UUID`,
`Date`, `DateTime`, and `Timestamp` where the backing schema and model declare them. Literal/variable
coercion is type checked before planning. `ID` remains a GraphQL identity even when a numeric column
backs it. The database model declares the physical `ID` representation per field: `integral`
(the compatibility default) or `string`. Both accept GraphQL's legal integer and string input
forms; a numeric backing rejects a non-numeric string before JDBC binding, while a string backing
canonicalizes an integer literal to text. Both representations always return a JSON string.

For the transpiled database engine, `Float` is a finite IEEE-754 binary64 value: PostgreSQL routines
use `DOUBLE PRECISION` and MySQL routines use `DOUBLE`. The database-side coercer accepts numeric
literals and JSON variables (including exponent notation), preserves normal binary floating-point
rounding, and turns an out-of-range value into a GraphQL coercion error rather than a database error.
`Decimal` currently uses the same binary64 binding and is therefore not an exact-decimal contract.

Field nullability in SDL, execution, and introspection follows the reviewed model. A SQL `NULL` for a
nullable field is rendered as JSON `null`; the runtime does not replace it with a scalar default.
Nullable cursor and custom sort keys are not in the portable compiled contract and are rejected by
model validation.

Normal quoted strings and triple-quoted GraphQL block strings are decoded by the installed database
engine. Block strings normalize line endings and common indentation, trim outer blank lines, and may
contain an escaped triple quote. This applies equally to inline scalar arguments and scalar variable
defaults; it is not delegated to the HTTP frontend.

Generated filter/order variables are resolved from the active schema rather than fixed model names.
Authored input objects are recursively coerced from inline GraphQL literals or JSON variables, with
omission-sensitive input-field defaults applied in the database routine. Registered custom mutations
may expose one required authored input object and map reviewed nested leaf paths to typed database
columns. The unrelated Commerce proof exercises generated `CustomerFilter`/`CustomerOrderBy` inputs
and a nested `RenameCustomerInput` variable/default path on both dialects.

Reviewed point-root, connection-root equality, relation equality, flat mutation, and authored
mutation-input arguments may declare GraphQL constant defaults in the model. The build validates
those defaults against their generated public types and rejects variable references. For an entirely
omitted field argument, the transpiled engine materializes the location default into the same typed
argument carrier used for supplied literals and variables. An omitted nullable variable used at that
location also receives the location default; explicit `null` remains distinct and is rejected when
the public argument type is non-null. Introspection returns the exact authored GraphQL source in
`__InputValue.defaultValue`. The HTTP frontend does not inspect or apply any of these rules.

Custom update bindings currently terminate at reviewed scalar or declared-enum leaves reached
through nested input objects. The language core also coerces list input fields, but mutation binding
paths do not address individual list elements. Mutation payloads remain direct scalar-like fields
declared by the binding; general nested output payloads and write-null bindings are not yet part of
the mutation contract.

## Roots and Keys

Only declared roots appear on `Query`. A relation does not automatically become a root.

Point roots support reviewed `Int`, `Long`, `String`, `ID`, and `UUID` keys and explicit
multi-argument composite keys. Each key argument binds to a reviewed column and is passed as a typed
carrier parameter. Inference preserves composite key candidates but never silently publishes one
component as the public key.

## Relay Connections

Declared collection roots and collection relations use Relay-style connections:

```graphql
type ExampleConnection {
  edges: [ExampleEdge!]!
  pageInfo: PageInfo!
  totalCount: Int!
}
```

Supported arguments are `first`, `after`, `last`, and `before`, subject to the model's maximum page
size. Cursors are opaque and bind projection identity, ordering, direction, value, and tie breaker.
Malformed or mismatched cursors are rejected. Forward continuation, backward windows,
`hasNextPage`, `hasPreviousPage`, start/end cursors, and exact visible-row `totalCount` are live-proven
on both dialects.

Counts observe the same row/context/client filters and relation policy as edges. Hidden rows do not
inflate counts. A model that declares no safe count strategy does not expose `totalCount` for that
shape.

## Filtering

Only reviewed filter paths and operators appear in generated input objects. The compiled path
supports declared equality/inequality, `in`, null checks, numeric comparison, and escaped string
matching operators. Client predicates compose with declared context filters.

The database-resident engine currently implements a narrower, explicitly tested subset: reviewed
local scalar conjunctions using `eq`, `neq`, comparisons, `isNull`, `contains`, `startsWith`, and
`endsWith`, plus reviewed `String`, `Int`/`Long`, `Float`/`Decimal`, `Boolean`, `UUID`, and
integral-/string-backed `ID` `in` through a static carrier of at most 8 values. Each item is
coerced and JDBC-bound from its declared scalar; an ID follows its reviewed `idStorage`, so an
integral ID uses a numeric binder while a string-backed ID preserves GraphQL's text identity. Inputs may be
GraphQL literals or JSON variables. The routine rejects duplicate input fields and applies the
same generated predicates to page rows, counts, and cursor-boundary checks. `and`/`or`/`not`,
relation/computed paths, and client ordering outside the narrow database tuple-order slice below
remain pending there; the broader compiled/JVM
contract described in this section must not be treated as database-engine feature parity.

A single local `in` predicate uses static arities through 16. Boolean `and`, `or`, and `not`,
filter-plus-order, and a reviewed non-null to-one relation filter hop use a static three-OR-group by
three-AND-term DNF carrier plan. Larger plans, to-many paths, and deeper relation paths fail closed.

## Ordering

Only reviewed order paths appear in generated input objects. The compiled path supports a single
custom key at a time over:

- a local non-null scalar;
- a reviewed deterministic row-local computed scalar; or
- one non-null to-one relation hop.

Every ordering includes a non-null stable tie breaker in both `ORDER BY` and cursor predicates.
Ascending and descending carrier variants are generated statically; client direction values never
become SQL fragments. Multiple simultaneous custom keys, nullable order keys, to-many paths, and
deeper hops are rejected.

The database-resident engine currently proves the first narrow custom-order slice on PostgreSQL and
MySQL: one reviewed, local, non-null `String`, `UUID`, `Int`, `Long`, or integral `ID` order key with a
reviewed non-null integral tie breaker. It accepts a literal or JSON-variable `orderBy`,
emits/validates the established opaque tuple cursor, and supports `first`/`after` and
`last`/`before` in both directions. The generated routine chooses among model-known branches
inside one constant parameterized SQL statement; it never turns a client enum or cursor into SQL
text. Other scalar tuple shapes, nullable values, computed/relation paths, and more than one
custom key still fail closed in the database engine.

`Float` and `Decimal` are deliberately among those excluded shapes: their cursor value needs a
single Java-compatible finite-double text format across PostgreSQL and MySQL. Database casts do not
meet that contract, so the engine rejects those order paths until Titan supplies that primitive.

## Relations and Batching

Declared to-one and to-many relations can appear beneath point or collection roots. A to-many
relation may expose either a direct collection or a Relay connection with pagination and exact
counts according to its capabilities.

The current database engine batches selectable/batchable unpaginated `MANY` and `ONE` relations and
Relay relation connections immediately beneath root collections, then recursively schedules
compatible deeper selections within the declared hop budget. Each selected relation occurrence is
assigned an AST-backed operation-plan identity. That identity keeps response alias, materialized
arguments and defaults, merged child selection, source location, and static policy context isolated.
The transpiled executor partitions all parent work by that identity and executes each partition in
constant 64-parent chunks; distinct plans never share arguments, rows, placeholders, or response
paths.

Unpaginated `MANY` joins may use any reviewed scalar local join, including duplicate non-primary
keys. `ONE` joins may likewise use reviewed public or private local carriers. PostgreSQL uses a
static `VALUES` slot table and MySQL a static `UNION ALL` slot table, so duplicate keys retain their
distinct parent indexes and error paths rather than collapsing to the first match. SQL
`ROW_NUMBER()` partitioning retains either the 100-row to-many sentinel or the second-row to-one
cardinality sentinel. Every decoded child still charges the request-wide 1,000-row allowance.

Relation Relay partitions share fixed-slot page, exact-count, and opposite-boundary statements.
They preserve forward/backward windows, per-parent cursor and page flags, zero-child parents,
equality arguments/defaults, nested node relations, and count-only selections. Live 65-parent
fixtures cross the 64-slot boundary while exact execution metrics prove constant statement counts;
separate aliases with different child selections or argument sets execute one bounded plan per
identity rather than one read per parent. No request value becomes SQL text.

The public database routine owns operation validation, plan partitioning, root dispatch, counters,
SQL scheduling, error/null completion, and response assembly. The HTTP/JDBC frontend sends the
entire GraphQL envelope in one call and does not parse, split, schedule, or merge operations or
fields. The batching contract remains limited by reviewed model capabilities, hop budgets, request
budgets, and the static SQL shapes generated for the package; an unsupported relation shape fails
closed rather than falling back to a JVM resolver.

## Computed Fields

The compiled path supports reviewed deterministic row-local SQL templates whose placeholders name
declared required columns. A computed field declares GraphQL type, nullability, select/filter/sort
capabilities, sensitivity, determinism, and cost class. Unsafe templates, relation-dependent
expressions, and Java-only expressions fail validation/generation for compiled use.

## Policies and Context Filters

The named policy language supports `allowAll`, `denyAll`, `adminOnly`, `authenticated`,
`roleEquals:<role>`, and `roleIn:<role,...>`. Multiple attached policies are conjoined.

- Root policies reject before I/O and gate root carriers.
- Type policies filter root rows, exact counts, and relation targets.
- Field policies reject selections and guard protected projections.
- Relation policies reject selections, guard join keys, and gate direct/batch relation carriers.
- Context filters bind named request-context values and fail closed when required values are absent.

Policy evaluation uses schema field names, never aliases. Introspection is enabled only through
trusted request context and exposes the stable deployed schema rather than actor-shaped variants.
Unauthorized data selection is rejected rather than returned as partial masked GraphQL data; SQL
guards are defense in depth for direct carrier access. Because v1alpha1 policies reject the request
before row execution rather than masking individual row fields, authorization errors carry the
selected source location but do not invent parent-list indexes in an execution path.

## Errors and Limits

Every database-engine GraphQL error has a stable `extensions.code`. The complete current taxonomy is:

- `PARSE_ERROR` for rejected GraphQL lexical syntax; a bounded source offset is reported as a
  GraphQL location when one exists.
- `VALIDATION_ERROR` for operation selection, schema use, variables, arguments, directives, field
  merging, and mutation prevalidation.
- `AUTHORIZATION_ERROR` for model policy or trusted introspection-permission rejection.
- `UNSUPPORTED_OPERATION` for deliberately unsupported operation/transport/schema capabilities,
  including subscriptions and a mutation selected through a read-only transport.
- `EXECUTION_ERROR` for resolver/data-completion and mutation-domain failures. Field completion
  errors retain their source location and response path; mutation failures return `data: null` and
  a rollback transport outcome.
- `DEADLINE_EXCEEDED` for a trusted deadline crossed before or during database execution.
- `RESOURCE_LIMIT_ERROR` for a bounded request/work rejection.
- `INTERNAL_ERROR` for installed package metadata, generated descriptor, or transport invariants.

Codes are selected at the transpilable failure site and are never inferred from message text. The
thin HTTP frontend returns completed database GraphQL errors without rewriting their category,
location, path, or data. Database-mode initialization/deployment failures that prevent a routine
call become descriptive 503 GraphQL bodies at the HTTP boundary.

Model page sizes, relation page sizes, static `in`/filter/batch arities, declared hop budgets, and
parser/validator constraints bound supported work. The database lexer also caps one request at
16,384 characters, 8,192 tokens, and 128 nested GraphQL delimiters before any data access. No raw
query text, credentials, trusted headers, or sensitive variables should be logged.

### Database request cost model

The engine uses a multi-dimensional request ledger. Characters, semantic nodes, SQL statements,
decoded rows, and response bytes are not interchangeable, so converting them into one arbitrary
score would hide the resource that is actually exhausted. Every dimension is request-local and
fail-closed. If a collection's fan-out is known, the engine reserves that work before appending
queue entries, opening a cursor, or decoding rows. A failed reservation returns
`RESOURCE_LIMIT_ERROR`; it is never treated as a malformed input or allowed to run until the
statement deadline.

| Dimension | Current/target ceiling | Charge point | Status |
| --- | ---: | --- | --- |
| Query source | 16,384 characters | Before lexing | Enforced |
| Variables, extensions, and trusted-context envelopes | 16,384 characters each | Before JSON traversal | Enforced |
| Lexical work | 8,192 tokens and 128 delimiter levels | While scanning the document | Enforced |
| Selected semantic work | 144 executable AST field nodes per request; 512 bounded validation/selection work items | Before schema dispatch and while expanding bounded work queues | Enforced |
| Fragment work | 512 expansions and 262,144 scalar-carrier characters | Before adding an expansion | Enforced |
| Typed input work | 128 descriptor/value nodes across a request | Before adding known list/object fan-out | Collection fan-out is precharged; variable/default and literal/default validation plus repeated-variable materialization aggregate across the selected operation, with exact-boundary JVM and installed PostgreSQL/MySQL proof |
| Introspection expansion | 512 emitted metadata objects across all introspection roots | Whole selected-operation reservation from generated inventory counts before rendering any schema inventory | Enforced with exact dual-dialect corpus proof |
| Application SQL | 64 prepared-statement executions, excluding the mandatory package-identity lookup | Before each generated execution; fixed three-statement generic mutations are also rejected from their root plan before per-root validation or effects | Enforced with exact installed PostgreSQL/MySQL overflow and rollback proof |
| Decoded application rows | 1,000 ledger units per request, with a 100-row sentinel limit for an unpaginated relation | Before row materialization; statically zero-or-one point/count/boundary/mutation carriers reserve one before opening the cursor, a connection reserves one possible page-sentinel unit and charges each materialized output row, and relation cursors charge each decoded row | Enforced request-wide across point, connection, boundary, count, mutation, and relation reads with exact installed PostgreSQL/MySQL overflow proof |
| Static `in` filter | 8 values per generated predicate | Before binding generated slots | Enforced |
| Completed response | 16,384 characters | At every shared member/item/prepend append and again at the public return boundary | Enforced incrementally with a private non-JSON overflow carrier, exact JVM boundary tests, and installed PostgreSQL/MySQL proof |
| Wall-clock work | Caller/database statement deadline plus authenticated `deadlineEpochMillis` | Before database work and at generated cursor/checkpoint boundaries | Enforced for existing paths; cancellation evidence remains an M5 item |

The target ceilings above are compatibility limits, not tuning suggestions. Raising one requires
dual-dialect latency, package-size, and resource evidence. Lowering one is an API compatibility
change. The execution plan may keep an item partial until both dialects prove that rejection occurs
before the expensive work, even when a final response-size or statement-time guard already exists.

An authenticated transport or test harness may set trusted-context
`includeExecutionMetrics: true`. A completed selected query or successful mutation then adds
`extensions.titanExecution.applicationSqlStatements` and `decodedApplicationRows`; ordinary
responses are unchanged, and client request `extensions` cannot enable the diagnostic. The SQL
value is the exact generated application-statement ledger and excludes package-identity/transport
work. The row value is the exact fail-closed ledger charge, including conservative pre-reservations
such as a possible Relay page sentinel, rather than database telemetry claiming how many physical
rows existed. Preflight failures do not receive execution metrics because application execution has
not begun.

## Introspection

When trusted context enables introspection, the runtime supports the bounded schema/type/field/input
metadata represented in the conformance matrix, including wrapper types, arguments, input fields,
enum values, interfaces, unions, object-interface relationships, abstract possible types,
authored argument/input-field default values, default-value absence, and scalar nullability. When disabled, `__schema` and `__type` are rejected.
`__typename` remains part of ordinary execution where supported.

Generated SDL and introspection are derived from the same adapted model and registered mutation
descriptors. They are artifacts, not separate sources of semantics.

## Custom Mutations

Compiled application schemas have no mutation root by default. Explicit providers may add reviewed
command descriptors and injected handlers. The runtime owns input validation, descriptor role checks,
dispatch, payload selection/aliases, error shape, and audit-event delivery; the handler owns domain
transactions, idempotency, persistence, rollback, and correctness-critical audit.

See [custom-mutations.md](custom-mutations.md). Generated CRUD, nested writes, SQL-transpiled
application handlers, and subscriptions are out of scope.

## Execution Modes

The standalone database HTTP ZIP is the supported serving artifact. Its package-bound,
explicit-dialect whole-request invocation never falls back to JVM GraphQL processing. The root
application's `database`, `compiled`, `jdbc`, `java`, and `sql` modes are transitional reference
paths while the remaining capability and deletion work is completed; there is no cross-mode
fallback.

Production package generation includes only model-generated carriers. The legacy kernel has its own
package and test task and is not part of compiled serving.

## Conformance

[query-contract-conformance.md](query-contract-conformance.md) records parser and legacy equivalence
coverage. The database-serving proof is `titanGraphqlDatabaseEngineReleaseCheck`, which independently
generates, packages, installs, binds, and invokes demo and commerce whole-request engines on
PostgreSQL and MySQL, then starts the standalone ZIP on both dialects. Unsupported shapes listed
above are acceptance boundaries, not silent roadmap promises.
