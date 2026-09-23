# Standalone database GraphQL HTTP frontend

The database HTTP frontend is the target serving artifact for a generated whole-request package.
It contains HTTP/JSON transport code, JDBC drivers, and the one-call database client only. It does
not contain Quarkus or a JVM GraphQL parser, validator, planner, resolver, cursor codec, or
response engine.

Build the database package, its verified deployment descriptor, and the distribution:

```sh
./gradlew \
  titanGraphqlGenerateDatabaseEngineFrontendDescriptor \
  titanGraphqlGenerateMySqlDatabaseEngineFrontendDescriptor \
  titanGraphqlVerifyDatabaseHttpFrontendReleaseArtifact
```

The descriptors are emitted beside their matching package:

- `build/generated/proofs/database-engine/package/titan-graphql-database-frontend.postgresql.properties`
- `build/generated/proofs/database-engine-mysql/package/titan-graphql-database-frontend.mysql.properties`

Do not edit a descriptor to select a different routine. It is derived from the verified model/package
binding and manifest, and fixes the dialect, schema-qualified whole-request entry point, expected
model hash, runtime identity, and deployment fingerprint. Rebuild it whenever the package is
rebuilt. The frontend sends that identity on every execution; the generated database routine
checks it before it parses the GraphQL request or accesses data.

`titanGraphqlVerifyDatabaseHttpFrontendReleaseArtifact` is deliberately stricter than merely
building a ZIP: it rejects a changed runtime closure, a launcher that does not start the isolated
frontend, or any local Titan GraphQL execution class in the release JAR.

Extract `build/distributions/titan-graphql-0.1.0-database-http-frontend.zip`, install the matching
Titan package and descriptor, then configure the launcher:

```sh
export TITAN_GRAPHQL_FRONTEND_DESCRIPTOR=/deploy/titan-graphql-database-frontend.postgresql.properties
export TITAN_GRAPHQL_JDBC_URL=jdbc:postgresql://db.example/titan
export TITAN_GRAPHQL_JDBC_USERNAME=titan_graphql
export TITAN_GRAPHQL_JDBC_PASSWORD=replace-with-secret
export TITAN_GRAPHQL_HTTP_PORT=8080
bin/titan-graphql-database-http
```

For MySQL, configure `max_allowed_packet` to at least `16M` before applying the package SQL. The
generated whole-request routine can exceed MySQL's obsolete 1 MiB packet default; this is an
installation prerequisite, not an HTTP request-size setting. Titan install verification now reads
`@@max_allowed_packet` before it runs any package SQL and rejects a target that cannot carry its
largest UTF-8 SQL statement plus protocol reserve. The standalone frontend's
`TITAN_GRAPHQL_HTTP_MAX_BODY_BYTES` setting remains an independent client-envelope limit.

Optional variables:

- `TITAN_GRAPHQL_HTTP_MAX_BODY_BYTES` — positive request-body limit; defaults to `1048576`.
- `TITAN_GRAPHQL_DATABASE_STATEMENT_TIMEOUT_SECONDS` — positive ceiling for the one JDBC
  whole-request invocation; defaults to `30`. An authenticated
  `X-Titan-Deadline-Budget-Millis` can reduce, but never increase, that ceiling.
- `TITAN_GRAPHQL_HTTP_TRUST_REQUEST_CONTEXT_HEADERS` — defaults to `false`. Enable it only
  behind an authenticated gateway that supplies the `X-Titan-*` context headers.

The frontend forwards the entire GraphQL document, operation name, variables, extensions, and
authenticated context in one database call. GET passes `allowMutations=false`; the transpiled
engine parses the request and rejects a selected mutation itself.

When `TITAN_GRAPHQL_HTTP_TRUST_REQUEST_CONTEXT_HEADERS=true`, an authenticated gateway may supply
model-defined scalar policy values in one `X-Titan-Context-Values: <JSON object>` header, for
example `X-Titan-Context-Values: {"region":"eu"}`. Every name must be an identifier and every
value must be a JSON scalar. The frontend forwards these values unchanged in `contextValues`; it
does not recognize or evaluate a name. Use `X-Titan-Context-Filters` to enable reviewed context
filters. These headers are ignored unless the trusted-gateway option is enabled.

For POST, `variables` and `extensions` may be omitted, an object, or JSON `null`; `null` is
forwarded as absent input so the database engine alone applies GraphQL defaults and coercion.
Other values are malformed HTTP envelopes and receive a transport `400` before any database call.
Duplicate JSON member names are rejected as malformed transport rather than being collapsed by an
HTTP JSON tree into a different GraphQL variable value.

## Transaction result protocol

The frontend does not inspect GraphQL JSON to decide whether to commit. The generated PostgreSQL
function returns a fixed `TITAN-GRAPHQL-TRANSPORT/1` frame containing a `COMMIT` or `ROLLBACK`
instruction followed by the completed GraphQL JSON. The generated MySQL procedure returns the
same two values in its final `transaction_outcome` and `response_json` columns. The JDBC client
validates the fixed outcome, applies the connection lifecycle, removes the PostgreSQL frame, and
forwards only the unmodified GraphQL JSON to HTTP. Transaction instructions are never emitted in
the GraphQL `extensions` object.
