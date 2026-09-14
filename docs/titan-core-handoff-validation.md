# Titan Core Handoff Validation Baseline

Status: `TG-HANDOFF-M0.1` baseline.

This file is the executable validation contract for the Titan GraphQL core
handoff loop. It records what can be trusted today, what is deliberately not a
production claim yet, and which commands future slices should run as they start
consuming Titan GAP-005 and GAP-006 contracts.

## Current Boundary Snapshot

- `/admin/graphql` is the GraphQL-owned transport boundary. HTTP headers,
  GraphQL request fields, response media negotiation, and management endpoint
  names stay in `titan-graphql`.
- `GraphqlAdminHttpResource` currently maps
  `X-Titan-Management-Actor-Id`,
  `X-Titan-Management-Actor-Role`,
  `X-Titan-Management-Request-Id`, and
  `X-Titan-Management-Idempotency-Key`,
  `X-Titan-Management-Introspection` into `GraphqlRequestContext`.
- `TitanGraphqlGap006CommandContext` adapts the GraphQL-owned
  `importModelDocument` field/input names and admin headers into Titan
  GAP-006-neutral `ManagementCommands.CommandInvocation` records. The adapter
  maps actor, request, idempotency, workspace scope, source format, and source
  text into Titan's command descriptor.
- `GraphqlManagementMutationSupport` executes management mutations through an
  explicit `TitanGraphqlManagementStore` boundary. The default handler path
  remains the Java-mode `TitanGraphqlInMemoryManagementStore` test/reference
  utility unless `titan.graphql.management.transactionLog` or
  `TITAN_GRAPHQL_MANAGEMENT_TRANSACTION_LOG` selects the durable adapter.
  `TitanGraphqlDurableManagementStore` provides a GraphQL-side adapter over
  Titan GAP-006 durable draft, artifact-ref, deployment, idempotency, audit,
  transaction, and deterministic read contracts.
- `importModelDocument` can now execute through Titan GAP-006 transaction,
  idempotency, and audit semantics when the durable store is selected. Same-key
  same-input retries replay through Titan, same-key different-input retries
  fail without adding a new draft, and missing idempotency is audited by Titan
  without writing draft state.
- `validateModelDraft`, `generateModelArtifacts`,
  `approveObservedOperation`, and `rejectObservedOperation` now persist their
  GraphQL-owned wrapper state through a sibling durable product-state journal
  when `TitanGraphqlDurableManagementStore` is selected. These mutations still
  are not claimed as Titan command/idempotency/transaction-backed mutations.
- `TitanGraphqlDurableManagementStore` now requires Titan GAP-005 artifact
  verification evidence before verified preview manifests or active deployment
  records are accepted. Preview builds are checked against the Titan manifest
  path/content hash and GraphQL SDL hash, and active deployments must go
  through Titan GAP-006 `activateDeployment` instead of GraphQL-local state.
- `TitanGraphqlDurableManagementStore` does not put GraphQL wrapper fields,
  observed operation documents, preview URLs, registry presentation records, or
  usage-report presentation state into Titan. Product-only state remains in
  `titan-graphql` and is reloaded from the GraphQL-owned journal; only
  Titan-neutral management records are written to the Titan GAP-006 store.
- `TitanGraphqlGeneratedArtifactWorkflow` now has a GAP-005 metadata adapter
  for Titan-owned SQL/package truth. GraphQL still owns SDL, introspection, and
  conformance artifacts; Titan owns `titan-artifact.json`,
  `titan-object-inventory.json`, `titan-install-plan.json`, and
  `titan-install-verification.json` when those outputs are supplied.
- `docs/mutation-runtime-lowering-boundary.md` is the production mutation
  truth source. The bounded `/admin/graphql` management mutation rows are now
  `ACCEPTED` only for the named management surface with durable selected-store
  evidence. This does not promote application `/graphql` mutations, broad CRUD,
  nested writes, SQL-lowered application writes, product UI behavior, or broad
  deployment orchestration.
- `docs/titan-core-gap-ledger.md` is closed for GAP-001 through GAP-006 and
  records no current `TITAN-GQL-GAP-007`.

## Baseline Commands

Use these commands before the first adapter slice and as a minimum gate for
documentation-only or boundary-only handoff changes:

```sh
./gradlew test --tests io.titan.graphql.GraphqlRuntimeBoundaryTest --stacktrace
./gradlew test --tests io.titan.graphql.TitanCoreHandoffValidationBaselineTest --stacktrace
git diff --check
git diff --cached --check
```

For Java behavior changes, add the nearest focused test suite for the touched
adapter or endpoint. For GAP-005 or GAP-006 consumption, add a Titan-side or
cross-repo compatibility check that exercises the consumed Titan records,
commands, artifact metadata, idempotency, audit, or transaction behavior.

The GAP-006 command-context adapter slice additionally validates:

```sh
./gradlew test --tests io.titan.graphql.TitanGraphqlGap006CommandContextTest --tests io.titan.graphql.TitanGraphqlFunctionsTest --tests io.titan.graphql.GraphqlMutationExecutorTest --stacktrace
./gradlew :titan-transpiler:test --tests io.titan.management.ManagementCommandsTest --stacktrace
```

The durable management store adapter slice additionally validates:

```sh
./gradlew test --tests io.titan.graphql.TitanGraphqlDurableManagementStoreTest --tests io.titan.graphql.ManagementDomainTest --tests io.titan.graphql.TitanGraphqlFunctionsTest --tests io.titan.graphql.TitanGraphqlGap006CommandContextTest --tests io.titan.graphql.TitanCoreHandoffValidationBaselineTest --stacktrace
./gradlew :titan-transpiler:test --tests io.titan.management.ManagementTransactionsTest --tests io.titan.management.ManagementIdempotencyTest --tests io.titan.management.ManagementAuditTest --stacktrace
```

The durable import mutation migration slice additionally validates:

```sh
./gradlew test --tests io.titan.graphql.TitanGraphqlManagementMutationMigrationTest --tests io.titan.graphql.TitanGraphqlFunctionsTest --tests io.titan.graphql.TitanGraphqlDurableManagementStoreTest --tests io.titan.graphql.TitanGraphqlGap006CommandContextTest --tests io.titan.graphql.TitanCoreHandoffValidationBaselineTest --stacktrace
./gradlew :titan-transpiler:test --tests io.titan.management.ManagementTransactionsTest --tests io.titan.management.ManagementIdempotencyTest --tests io.titan.management.ManagementAuditTest --stacktrace
```

The remaining management mutation persistence slice additionally validates:

```sh
./gradlew test --tests io.titan.graphql.TitanGraphqlManagementMutationMigrationTest --tests io.titan.graphql.TitanGraphqlFunctionsTest --tests io.titan.graphql.TitanGraphqlDurableManagementStoreTest --tests io.titan.graphql.TitanGraphqlGap006CommandContextTest --tests io.titan.graphql.TitanCoreHandoffValidationBaselineTest --stacktrace
./gradlew :titan-transpiler:test --tests io.titan.management.ManagementTransactionsTest --tests io.titan.management.ManagementIdempotencyTest --tests io.titan.management.ManagementAuditTest --stacktrace
```

The artifact verification and deployment consistency slice additionally
validates:

```sh
./gradlew test --tests io.titan.graphql.TitanGraphqlDurableManagementStoreTest --tests io.titan.graphql.TitanGraphqlManagementMutationMigrationTest --tests io.titan.graphql.TitanGraphqlGeneratedArtifactWorkflowTest --tests io.titan.graphql.TitanCoreHandoffValidationBaselineTest --stacktrace
./gradlew :titan-transpiler:test --tests io.titan.management.ManagementTransactionsTest --stacktrace
```

The conformance promotion and handoff closeout slice additionally validates:

```sh
./gradlew test --tests io.titan.graphql.TitanCoreHandoffValidationBaselineTest --tests io.titan.graphql.TitanGraphqlManagementMutationMigrationTest --tests io.titan.graphql.TitanGraphqlDurableManagementStoreTest --tests io.titan.graphql.TitanGraphqlGeneratedArtifactWorkflowTest --tests io.titan.graphql.TitanGraphqlFunctionsTest --tests io.titan.graphql.GraphqlMutationExecutorTest --stacktrace
./gradlew :titan-transpiler:test --tests io.titan.management.ManagementTransactionsTest --tests io.titan.management.ManagementCommandsTest --stacktrace
```

## Known Narrowed Blocker (RESOLVED)

At the time this baseline was recorded, the broad lowered-artifact gate was
narrowed to a Titan feature diagnostic:

```sh
./gradlew titanTranspile --stacktrace
```

- `:titanTranspile` started successfully against the sibling Titan build but
  failed with `TITAN-E001` diagnostics in
  `src/main/java/io/titan/graphql/demo/blog/DemoBlogTitanGraphqlFunctions.java`
  for nested traversal scanner cursor initialization and deterministic cursor
  increment checks.

Resolved by completion-plan W1 (commits `f02fb22`, `19b1baa`, `8d70412`): the
kernel was rewritten to the GAP-004 contract and `titanTranspile` is clean
(zero diagnostics). The GAP-005 adapter slice originally validated package
metadata consumption with focused fixture JSON; since completion-plan W3
(`c9dda68`) the production path consumes the real `titanPackage` /
`titanVerifyInstall` outputs, and the live SQL-mode equivalence gate is
`./gradlew integrationTest` (completion-plan W2, `506f86c`).

## Durability Terminology (TG-BLK-003, CLOSED)

Core dogfooded the management store (Phase B, commits `35dc04d` / `0498692` /
`e238ae7`): it now ships a durable JDBC-backed transactional store
(`JdbcTransactionalMutationStore` over Titan-transpiled routines, plus JDBC
idempotency/audit stores and `ManagementSchemaInstaller`), proven durable +
concurrent on PG 16 + MySQL 8.4. The consumer runs on it in the opt-in `jdbc`
mode (`titan.graphql.management.store=jdbc`) — there "durable" means a real JDBC
store on transpiled routines, proven on live PG + MySQL by
`GraphqlJdbcManagementStoreIT`. The DEFAULT `file` mode wraps
`FileTransactionalMutationStore` (file-backed, single-process), where "durable"
means that file boundary. `TG-BLK-003` is CLOSED in
`docs/titan-blocker-register.md`. Two recorded routine-design gaps are known and
non-blocking (adapter-side `activate_deployment` typed preconditions; the import
routine's collapsed hash column).

## Promotion Rules

- Mutation runtime rows marked `ACCEPTED` are scoped to `/admin/graphql`.
  They require endpoint evidence, durable state, artifact coverage,
  idempotency, audit, transaction behavior, and conformance/profile evidence.
  They are not evidence for application `/graphql` mutation execution.
- Do not use `TitanGraphqlInMemoryManagementStore` as production evidence.
  It remains a test/reference utility. The durable adapter provides Titan
  GAP-006 core-row evidence, but GraphQL-only wrapper records still require
  consumer-side persistence before broad production claims are promoted.
- Do not treat legacy GraphQL-generated SQL metadata placeholders as
  Titan GAP-005 artifact truth. Package identity, object inventory, install
  plan, and verification state must come from Titan-owned metadata before
  production deployment claims.
- Do not mark preview builds ready or deployments active from durable
  GraphQL-local state alone. Verified preview manifests must match Titan
  GAP-005 hashes, and active deployment records must be created through Titan
  GAP-006 deployment activation evidence.
- Do not add GraphQL route names, HTTP headers, SDL, preview URLs, portal
  concepts, or product workflow names to Titan core.
