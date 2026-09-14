# Titan Core Feedback Goal

## Purpose

The next project goal is to separate Titan GraphQL core code from the demo-blog
model while using every exposed GraphQL runtime limitation as feedback for
improving Titan itself.

Titan GraphQL is not only an application framework experiment. It is a stress
test for Titan's ability to lower nontrivial Java control flow, planning,
validation, storage access, and response construction into database-resident
SQL artifacts. When the GraphQL project finds a limitation in that path, the
default product instinct should be to improve Titan core rather than permanently
work around the limitation in GraphQL-specific code.

## Goal

Create a clean boundary between:

- GraphQL core infrastructure: parsing, validation, descriptors, projection
  model, planning, shared response semantics, model document handling, and
  artifact generation.
- Application adapters: demo-blog, management, and future generated models.
- Titan lowerable kernels: target-specific Java or SQL artifacts that preserve
  core semantics while staying inside Titan's currently supported subset.

The boundary should make demo-specific assumptions visible, removable from
core, and useful as input to Titan's own roadmap.

## Strategic Rule

Do not treat generated or model-specific GraphQL kernels as the final answer
when they exist only because Titan cannot yet lower the more general form.

Generated kernels are allowed as an incremental bridge. They keep the product
moving and provide concrete lowerable shapes. But each bridge must leave behind
a clear Titan gap when the missing capability belongs in Titan core.

## First Milestone

Extract the demo-blog runtime behind an explicit adapter boundary without
changing public behavior.

Initial deliverables:

- Move demo-blog concepts such as `Article`, `User`, `Comment`, demo fixture
  storage, demo execution, demo response writing, and demo static stored-function
  kernels into a demo-blog adapter package.
- Keep core packages free of demo-blog type names, demo field names, demo table
  names, and demo seed fixtures.
- Add an architecture test that prevents core code from depending on demo-blog
  packages or symbols.
- Route the main GraphQL request path through a runtime/model adapter rather
  than directly through demo-blog static functions.
- Preserve the current Java-mode and SQL-mode proof behavior.

## Titan Feedback Loop

During the extraction, classify each demo-specific execution dependency as one
of:

- Legitimate application concern: model policy, field names, seed data, or
  domain-specific fixture behavior.
- Temporary generated-kernel bridge: model-specific code needed to keep the
  SQL-lowered path explicit and inspectable while the generic path matures.
- Titan core gap: a repeated lowering, runtime, artifact, or deployment
  capability that should be improved in `vendor/titan`.

Titan core gaps should be recorded as follow-up work, not buried inside the
GraphQL adapter.

The active gap list is maintained in
[titan-core-gap-ledger.md](titan-core-gap-ledger.md).

Likely Titan gaps exposed by this work include:

- Lowering descriptor-driven execution without relying on reflection or dynamic
  object graphs.
- Result-set iteration and row materialization patterns that are reusable across
  generated kernels.
- JSON or structured response assembly primitives suitable for SQL emission.
- Collection, loop, string, and helper intrinsic coverage required by realistic
  request runtimes.
- Generated SQL artifact installation and deployment metadata.
- Transaction, audit, and idempotency semantics for management mutations.

## Non-Goals

- Do not make Titan GraphQL a permanent workaround layer for Titan limitations.
- Do not rewrite the whole executor as a dynamic descriptor interpreter if that
  makes Titan lowering less credible.
- Do not broaden GraphQL product scope into full application CRUD, subscriptions,
  or arbitrary resolver plugins as part of this boundary work.
- Do not let the demo-blog model remain the implicit semantic source for core
  behavior.

## Success Criteria

The project is on track when:

- Core code can be inspected without seeing demo-blog concepts.
- Demo-blog is clearly one adapter among others.
- The management model can evolve as a sibling adapter rather than a special
  case inside demo code.
- Generated model adapters have a clear hand-written template to replace.
- Every meaningful Titan limitation discovered during the split is captured as
  Titan work.
- The SQL-lowered proof remains green or fails with documented Titan gaps rather
  than hidden GraphQL-side fallback behavior.
