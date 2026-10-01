# Persistent local dogfood deployment

Local identity hardening, backup/restore, credential rotation, interruption drills, and health checks
are documented in [OPERATIONS.md](OPERATIONS.md).

Run Titan GraphQL's management workflow against its own persistent control-job database.
The application model at `jobs.titan.graphql.yaml` exposes job identity, type, status, and attempt
count, not request payloads, model source, result JSON, credentials, or application writes.
The host client `deployment/dogfood.py` imports that model through `/admin/graphql`, polls the
durable worker, validates the draft, requests package-backed artifacts, records and reviews an
operation, publishes a sealed preview, and reads the jobs it actually created. It does not seed
demo application rows or execute GraphQL semantics outside the installed Titan routines.

## Prerequisites and boundary

Use a Linux Docker host with Compose, Python 3.10 or later, and JDK 21 (`java` and `javac`).
Initialize the repository's pinned submodules. The existing Gradle pipeline scratch-verifies the
application and management packages before the client installs them in a dedicated PostgreSQL
database. The build uses Docker even before the persistent database starts.

`deployment/compose.dogfood.yaml` keeps PostgreSQL on its own named volume without a host database
port. The frontend binds only `127.0.0.1`, and the worker runs separately. Services use Docker's
`unless-stopped` restart policy. This is an internal localhost deployment, not public hosting or
a production rollout. The dedicated database owner performs installation and serves this local
stack; separate least-privilege identities and backup/restore drills belong to operational hardening.

The administrator route requires a generated bearer token and supplies its actor role from server
configuration. The frontend keeps trusted actor headers disabled. The application model deliberately
permits local anonymous metadata reads. The published preview adds an enforced exact-document
allowlist: its reviewed read has empty role/client scopes because it exposes only this metadata.
`LocalDogfoodControl.java` refuses a different query or model source and requires management approval
before creating that public-read registry. Do not reuse this public scope for another model.

## Start and use

Run these commands from the repository root, one operator command at a time:

```sh
git submodule update --init --recursive
python3 -B deployment/dogfood.py up
python3 -B deployment/dogfood.py workflow
python3 -B deployment/dogfood.py verify
python3 -B deployment/dogfood.py status
```

The default listener is `http://127.0.0.1:18080`; its management route is `/admin/graphql` and
published preview route is `/preview/dogfood-jobs/graphql`. `workflow` resumes its recorded request
IDs, including ambiguous retries. Use `workflow --new-run` to create new jobs and perform another
complete workflow. Job IDs are UUIDs; management request/idempotency keys use a `dogfood-` prefix,
and import requests use `workspace-local-dogfood` to satisfy Titan's workspace scope contract.

The initial application descriptor exposes the checked-in job-metadata model at `/graphql`.
The preview serves the exact document in `job.graphql`, with an `id` variable naming a durable job.
`workflow.json` records those IDs. Unreviewed preview documents fail inside the database engine.
Preview descriptors expire; running `workflow` republishes the existing reviewed workflow with
a renewed expiration and recreates the frontend to load its registry. Registry changes can briefly
invalidate an old preview while the operator publishes its replacement; this is not a live-caller cutover.

`up` initializes an empty state directory, creates secrets, builds the ZIPs/images, installs package
SQL, compiles the small operator adapter against the worker libraries, and starts the services.
Repeated `up` preserves credentials and database rows. It does not reset the volume. Generated
packages have identity-keyed deployment directories rather than borrowing obsolete ignored output.

## State and secrets

The default state directory is `deployment/.dogfood/`, excluded by `.gitignore`. Its `.env` holds
generated database and admin secrets with mode `0600`; the enclosing directory uses `0700`.
Do not paste those values into chat, logs, committed examples, or release evidence. The client rejects
a public secret file and ignores ambient overrides of its saved password, token, and port.

The `deploy/` subdirectory contains only non-secret packages, descriptors, registries, the checked-in
model/query, and compiled operator classes. Those files must be readable by the unprivileged
container user. Both long-running services mount that directory read-only. Only the one-shot
publisher receives a writable mount, running as the host operator's UID/GID. Never put secrets
inside `deploy/`; the client sets its files to `0644` and directories to `0755`, rejecting symlinks.

Keep the private state directory together with the PostgreSQL volume. Losing `.env` is not a
reason to generate a different password against the existing volume. Restore the saved secrets
instead. Do not run `docker compose down --volumes`, Docker volume pruning, or delete the private
state directory as a restart procedure. None of the provided commands removes the database volume.

Use `--state-dir`, `--project`, and `--port` before the action for a separate deployment. Use the
same options for every command. Existing initialization settings cannot change project or port.
Do not reuse a project name belonging to another stack.

## Recovery and verification

```sh
python3 -B deployment/dogfood.py recover
```

`recover` verifies the active workflow, removes this Compose project's service containers/network
without deleting its named volume, recreates all services, and checks the previous preview results
and validated draft. It compares actual container IDs and volume names, then creates another complete
workflow to prove that the recovered worker can process new work. It leaves the stack running.

For a non-destructive stop, run `python3 -B deployment/dogfood.py stop`; use `up` to start it again.
Inspect service logs with Compose using the private env file and matching project/state settings.
Do not print `docker compose config` or container environment inventories: they contain credentials.

`verification.json` and `recovery.json` retain local execution timestamps, workflow IDs, preview
results, package identities, container/image IDs, and durability checks. They contain no admin token
or database password. Retain only sanitized evidence in the repository; keep deployment secrets and
machine paths local. Run the deployment client tests with:

```sh
python3 -B -m unittest discover -s deployment -p 'test_dogfood.py' -v
```
