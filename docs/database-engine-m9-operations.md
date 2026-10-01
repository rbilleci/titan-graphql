# M9 local operational hardening

Status: complete locally. `release-evidence/m9-operations-verification.json` retains the completed
acceptance execution for implementation commit `a15447270180f1af1749d20c831095a44dff5f5f`.

M9 hardens the persistent internal deployment documented in `deployment/dogfood/README.md`.
It does not add public hosting, multi-user login, high availability, off-site backup infrastructure,
production performance guarantees, new UI, language features, or caller cutovers.

The acceptance matrix defines the local milestone boundary.

| Requirement | Implementation | Acceptance proof |
| --- | --- | --- |
| M9-RESTRICTED-IDENTITIES | Explicit PostgreSQL grants and separate frontend/worker passwords | Full workflow succeeds; authenticated service sessions reject forbidden reads, writes, ownership escalation, and schema changes |
| M9-ISOLATED-RESTORE | Private consistent backup and fresh Compose project/volume | Restored jobs/draft match the backup; restored worker completes a fresh workflow; original volume remains intact |
| M9-CREDENTIAL-ROTATION | Private resumable journal and coordinated credential replacement | New database credentials/token work; old credentials fail; workflow state remains unchanged |
| M9-FAILURE-RECOVERY | Worker interruption during an active import and temporary database outage in the restored stack | Expired lease retries successfully; request replay preserves committed counters; a fresh workflow succeeds after the outage |
| M9-OPERATOR-CHECKS | Service, preview, package, job-lease, and role checks | Nonzero exit on an unhealthy condition; sanitized evidence and a recovery runbook |

## Retained acceptance evidence

`release-evidence/m9-operations-verification.json` retains the primary service inspection,
restricted-role denials, independent restore, interrupted job and committed replay counters,
database-outage recovery, password/token rejection checks, installation file inventory, and
successful workflows. Its acceptance record also compares the original M8 job responses and
database volume with `release-evidence/m8-dogfood-verification.json`.

Run `python3 -B docs/release-evidence/derive-operations.py deployment/.dogfood` to derive the
sanitized record from private execution artifacts and rerun the client tests. The derivation
checks preservation flags, independent volume identities, job retry attempts, source hashes,
matching implementation commits, and absence of saved credentials. It also compares the frontend
and worker ZIP digests with the published M7 assets in `release-evidence/m7-publication.json`.
These records describe completed local tests, not a production availability guarantee.

The operating procedures and failure actions are in `deployment/dogfood/OPERATIONS.md`. The
acceptance run leaves the primary stack running and stops drill stacks without removing their
volumes, backups, or diagnostic state.

## Permission boundary

The generated routines use caller permissions. The frontend therefore needs explicitly named table
rights as well as routine execution; an execute-only account cannot serve this management model.
The worker needs management state writes and control-job lease updates. It reads request tables
without inserting requests. Its `REFERENCES` grant exposes constraint metadata to the existing
schema verifier; neither service has schema creation rights to construct foreign keys.
`deployment/operations.py` defines the grants in `apply_roles` and checks denials using independently
authenticated TCP sessions, not a superuser session temporarily changing roles.

The installer retains the dedicated database owner account. Only installation and operator
publication use that account in one-shot containers. Long-running services use the restricted
accounts from `deployment/compose.hardened.yaml`. The operator adapter uses worker rights.
The shared frontend account must serve both application and authenticated management requests;
this milestone does not claim database isolation between those routes or hide every management
table from a compromised frontend. HTTP authentication and generated model policy remain required.

PostgreSQL grants default routine execution to `PUBLIC`, so role setup revokes that default and
reapplies explicit rights after package installation. The privilege and password mechanisms follow
the [PostgreSQL privilege documentation](https://www.postgresql.org/docs/16/ddl-priv.html) and
[ALTER ROLE documentation](https://www.postgresql.org/docs/16/sql-alterrole.html).

## Backup boundary

The operator stops the frontend and worker while capturing a database dump and matching deployment
files. PostgreSQL supplies the database snapshot through `pg_dump`; quiescing the local services
also keeps the file-backed preview registry consistent with that snapshot. The backup includes
private credentials and management data. It stays inside the ignored, private deployment state
directory. Its checksums detect corruption, not malicious replacement by a trusted host operator.
Database dumps omit ownership and grants; restore creates service roles and reapplies the checked-in
grant definition. See the [pg_dump documentation](https://www.postgresql.org/docs/16/app-pgdump.html).

Restore testing uses a fresh database with an independently named volume and localhost listener.
It rejects nonempty databases and existing project/volume identities. It never overwrites the
original database. This proves a local logical restore, not recovery from loss of the host or a
point-in-time recovery guarantee.

## Diagnostic history

The worker's installed-schema verifier requires visible management routines and constraint
metadata. Explicit routine execution and read-only request-table metadata grants satisfy those
checks without granting request writes or schema creation.

Reinstallation exposed an obsolete-container image lookup after rebuilding the worker tag.
Operator one-shots now resolve the newly built image independently of existing containers.
The package check initially compared serialized manifest bytes with Titan's logical manifest hash;
it now checks file bytes against the installation inventory rather than changing package semantics.

Interruption testing confirmed that a successful re-import returns the draft to its imported state.
The drill completes the normal workflow before comparing database-outage durability. Authentication
testing also found that PostgreSQL trusts loopback inside this container; service-hostname probes
now check the password-authenticated path. The pending private journal successfully resumed the
interrupted rotation. The retained acceptance execution follows these corrections.
