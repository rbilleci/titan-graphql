# Titan GraphQL Validation Diagnostics

Status: implemented diagnostic format reference.

Titan GraphQL validation reports have two stable renderings:

- terminal text for local CLI and CI logs
- JSON for editors, local automation, management API responses, and other consumers

Both renderings are produced from the shared validation report model. They do
not change parser behavior, semantic validation rules, runtime YAML loading, or
application `/graphql` behavior.

## Terminal Text

The first line is always a summary:

```text
Validation report: invalid (1 error, 1 warning, 0 info, blocks deployment)
```

Valid reports use the same shape:

```text
Validation report: valid (0 errors, 0 warnings, 0 info, deployable)
```

Each issue is rendered on one line after the summary:

```text
warning UNKNOWN_REFERENCE at titan.graphql.yaml:42:9 $.types.Article.relations.author - Relation target type User is not declared.
error INVALID_BINDING $.roots.article - Root article binds to a missing table.
```

The issue line contains:

- severity in lowercase
- stable issue code
- optional source file, line, and column when available
- model path in `$.section.name` form
- human-readable message

Issue metadata is rendered on following indented lines with deterministic key
ordering:

```text
  hint: Use "User" type
  target: Article.author
```

## JSON

JSON output is compact and deterministic. Object keys and metadata keys are
sorted so tools can compare output directly.

```json
{
  "blocksDeployment": true,
  "errorCount": 1,
  "infoCount": 0,
  "issues": [
    {
      "blocksDeployment": false,
      "code": "UNKNOWN_REFERENCE",
      "message": "Relation target type User is not declared.",
      "metadata": {
        "target": "Article.author"
      },
      "modelPath": "$.types.Article.relations.author",
      "severity": "WARNING",
      "sourceLocation": {
        "column": 9,
        "line": 42,
        "source": "titan.graphql.yaml"
      }
    }
  ],
  "valid": false,
  "warningCount": 1
}
```

The JSON fields are:

- `valid`: true when there are no `ERROR` issues
- `blocksDeployment`: true when any issue blocks deployment
- `errorCount`, `warningCount`, `infoCount`: severity counts
- `issues`: ordered validation issues
- `code`: stable `TitanGraphqlValidationIssueCode`
- `severity`: `ERROR`, `WARNING`, or `INFO`
- `message`: human-readable diagnostic text
- `modelPath`: canonical path to the model element, or `$` for the root
- `sourceLocation`: source filename plus line and column; absent line/column are
  represented as zero values
- `metadata`: deterministic string map for tool-specific context

## Drift Diagnostics

Catalog drift checks use the same validation report model and render through
the same terminal text and JSON surfaces. A drift report does not connect to a
live database by itself; it compares an already loaded canonical model document
with an already captured catalog snapshot.

All current drift findings use:

- `code`: `DRIFT_DETECTED`
- `severity`: `ERROR`
- `blocksDeployment`: `true`
- `sourceLocation`: empty unless a later caller attaches YAML source locations
- `metadata.driftKind`: a stable machine-readable drift classification

Example terminal output:

```text
Validation report: invalid (1 error, 0 warnings, 0 info, blocks deployment)
error DRIFT_DETECTED $.types.Article - Type 'Article' binds missing table 'public.articles'.
  driftKind: MISSING_TABLE
  schema: public
  table: articles
```

Example JSON output:

```json
{
  "blocksDeployment": true,
  "errorCount": 1,
  "infoCount": 0,
  "issues": [
    {
      "blocksDeployment": true,
      "code": "DRIFT_DETECTED",
      "message": "Type 'Article' binds missing table 'public.articles'.",
      "metadata": {
        "driftKind": "MISSING_TABLE",
        "schema": "public",
        "table": "articles"
      },
      "modelPath": "$.types.Article",
      "severity": "ERROR",
      "sourceLocation": {
        "column": 0,
        "line": 0,
        "source": ""
      }
    }
  ],
  "valid": false,
  "warningCount": 0
}
```

Current drift kinds include:

- `MISSING_TABLE`: a model type binds to a table that is absent from the
  catalog snapshot.
- `MISSING_COLUMN`: a field, root argument, filter path, or sort path binds to
  a missing column.
- `SCALAR_TYPE_MISMATCH`: the catalog column database type maps to a different
  GraphQL scalar than the model declares.
- `NULLABILITY_MISMATCH`: the model and catalog disagree on nullable column
  shape.
- `MISSING_KEY`: the declared type primary key is absent from catalog primary
  key metadata.
- `MISSING_INDEX`: a root binding expects an indexed leading column and the
  catalog snapshot does not expose one.
- `MISSING_COMPUTED_REQUIRED_COLUMN`: a computed field references a required
  source column that is absent.
- `BROKEN_RELATION_JOIN`: a relation join no longer matches local column,
  target column, or foreign-key metadata.

Operator interpretation:

- Treat drift reports as deployment review blockers until the model or database
  schema is reconciled.
- Use `modelPath` to find the model element and metadata such as `schema`,
  `table`, `column`, `targetTable`, or `foreignKeyExists` to find the catalog
  side of the mismatch.
- Warning and info drift severities are not emitted by the current checker.

## Current Boundary

Model validation and catalog drift use the same renderer surface. Generation, binding, and runtime
initialization invoke model validation directly. There is no standalone validation CLI; YAML syntax
errors remain parser exceptions, while semantic and drift findings use this report format.
