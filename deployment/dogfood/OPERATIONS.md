# Local operations runbook

Use these commands from the repository root, one operator command at a time. Start with the existing
deployment from `deployment/dogfood/README.md`. Use `--state-dir` before the action when operating
another managed deployment. The client never removes database volumes or private state.

## Restrict service identities

```sh
python3 -B deployment/operations.py harden
python3 -B deployment/dogfood.py workflow --new-run
python3 -B deployment/operations.py check
```

`harden` preserves the owner password and existing data, generates separate service credentials,
stops the services, applies explicit grants, and recreates the frontend/worker with the hardened
Compose overlay. Repeating `up` installs as the owner and reapplies those grants before starting
services. Do not edit service credentials manually. A failed role setup leaves the saved credentials
available for retry; inspect the failure and repeat `harden`.

## Back up and prove a restore

```sh
python3 -B deployment/operations.py backup
python3 -B deployment/operations.py restore-drill
```

`backup` quiesces the frontend/worker and saves a database dump, credentials, deployment files,
workflow state, installation record, and checksum inventory under the private `backups/` directory.
It restarts services even when capture fails. `latest-backup.json` selects the completed backup.
Keep backups private; neither their contents nor credential hashes belong in Git or diagnostics.

`restore-drill` verifies the inventory, creates an independent project and volume, restores into an
empty database, checks the backed-up workflow, and runs a fresh workflow. It leaves the restored stack
running for interruption testing. `restore-verification.json` identifies its private `drillDirectory`.
If a drill fails, its diagnostic stack and state remain available; never retry by overwriting that
database. A new restore attempt allocates a fresh target.

## Exercise interruption recovery

Run against the restored deployment's state directory, not the primary deployment:

```sh
python3 -B deployment/operations.py --state-dir deployment/.dogfood/drills/<drill-id> failure-drill
python3 -B deployment/operations.py --state-dir deployment/.dogfood/drills/<drill-id> check
python3 -B deployment/dogfood.py --state-dir deployment/.dogfood/drills/<drill-id> stop
```

The drill locks a management table to hold a real import in progress, kills its worker, releases
the lock, and waits for the original lease to expire and retry. It replays the identical management
request and compares committed job/request/receipt/journal counters. It then stops PostgreSQL,
requires a query failure, restarts the database, verifies old state, and runs another full workflow.
The temporary lock has a bounded lifetime; cleanup targets its uniquely named database session.
Expect the lease retry to wait for expiration rather than changing the lease timestamps manually.
Stopping a completed drill preserves its database and files for inspection.

## Rotate credentials

```sh
python3 -B deployment/operations.py rotate
python3 -B deployment/operations.py check
```

Rotation stops services, changes database passwords transactionally, atomically replaces `.env`,
and recreates services. It checks the new passwords through TCP authentication and rejects the old
passwords and admin token. `rotation-private.json` retains a private before/after journal and data
baseline for retry after interruption. Repeat `rotate` to resume that pending rotation. Completed
private journals retain revoked credentials for diagnosis; protect them like backups. The sanitized
`rotation-verification.json` contains no credentials. This procedure permits downtime and makes no
zero-downtime rotation claim.

## Detect and diagnose failures

`check` returns nonzero when service inspection, a reviewed query, package binding, preview
expiration, pending-job age, expired leases, or permission denials fail. Its `health-verification.json`
contains only selected service identifiers and check results. Historical failed jobs do not make a
healthy stack fail; stale pending jobs and expired running leases do.

The recovery actions distinguish state corruption from unavailable services.

| Failure | Operator action |
| --- | --- |
| Preview expired | Repeat `dogfood.py workflow` to renew the reviewed publication |
| Worker stopped | Use `dogfood.py up`; allow the durable lease to expire before expecting retry |
| Database unavailable | Restore service availability with `dogfood.py up`; never replace its volume to restart it |
| Package identity drift | Stop services, inspect the matching installation/package records, and reinstall with `dogfood.py up` |
| Pending rotation | Resume `operations.py rotate` using the saved private journal |
| Backup corruption | Retain the failed artifact and select a verified backup; never restore it over the primary database |

Do not print Compose configuration, full container environments, credentials, raw management
payloads, or backup SQL into shared logs. The commands require a trusted host operator; they do not
implement an identity gateway or automatic incident remediation.

Run regression tests with `python3 -B -m unittest discover -s deployment -p 'test_*.py' -v`.
