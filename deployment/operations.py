#!/usr/bin/env python3
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

from dogfood import Deployment, ROOT, MODEL, QUERY, now, run, save_json


ROLE_KEYS = {"titan_dogfood": "DOGFOOD_DATABASE_PASSWORD",
             "titan_frontend": "DOGFOOD_FRONTEND_PASSWORD", "titan_worker": "DOGFOOD_WORKER_PASSWORD"}
FRONTEND_READ = ("management_drafts", "graphql_artifact_requests", "graphql_validation_requests",
                 "graphql_import_requests", "graphql_observed_operations", "graphql_review_requests",
                 "graphql_operation_registries", "graphql_registry_operations")
REQUEST_TABLES = ("graphql_artifact_requests", "graphql_validation_requests", "graphql_import_requests",
                  "graphql_review_requests")
WORKER_TABLES = ("management_drafts", "management_artifact_refs", "management_deployments",
                "management_idempotency", "management_audit_outcomes", "graphql_product_state",
                "graphql_observed_operations", "graphql_operation_registries", "graphql_registry_operations",
                "graphql_review_mutex")


def values(deployment):
    return dict(line.split("=", 1) for line in (deployment.directory / ".env").read_text().splitlines())


def save_values(deployment, mapping):
    if not all(re.fullmatch(r"[a-f0-9]{64}", mapping[key]) for key in (*ROLE_KEYS.values(), "DOGFOOD_ADMIN_TOKEN")):
        raise ValueError("credentials must be generated hexadecimal values")
    path = deployment.directory / ".env"
    temporary = path.with_suffix(".new")
    temporary.write_text("".join(key + "=" + value + "\n" for key, value in sorted(mapping.items())))
    temporary.chmod(0o600)
    temporary.replace(path)


def secret_sql(deployment, statement):
    try:
        return deployment.sql(statement)
    except RuntimeError:
        raise RuntimeError("credential SQL failed; private rotation state remains available for retry") from None


def apply_roles(deployment, mapping=None):
    mapping = mapping or values(deployment)
    for key in ROLE_KEYS.values():
        if not re.fullmatch(r"[a-f0-9]{64}", mapping[key]):
            raise ValueError("invalid generated database credential")
    descriptor = dict(line.split("=", 1) for line in (deployment.deploy / "application.properties").read_text().splitlines())
    schema = descriptor["entry-point-schema"]
    if not re.fullmatch(r"preview_[a-f0-9]{24}", schema):
        raise ValueError("unexpected application schema")
    statements = ["BEGIN;", "SET password_encryption = 'scram-sha-256';"]
    for role in ("titan_frontend", "titan_worker"):
        statements += ["DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + role + "') THEN CREATE ROLE " + role + "; END IF; END $$;",
                       "ALTER ROLE " + role + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD '" + mapping[ROLE_KEYS[role]] + "';"]
    statements += ["REVOKE ALL ON DATABASE titan_dogfood FROM PUBLIC;",
                   "GRANT CONNECT ON DATABASE titan_dogfood TO titan_frontend, titan_worker;",
                   "REVOKE CREATE ON SCHEMA public FROM PUBLIC;",
                   "ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;"]
    for namespace in ("public", "management", "titan_runtime", "management_graphql", schema):
        statements += ["REVOKE ALL ON SCHEMA " + namespace + " FROM PUBLIC, titan_frontend, titan_worker;",
                       "REVOKE ALL ON ALL TABLES IN SCHEMA " + namespace + " FROM PUBLIC, titan_frontend, titan_worker;",
                       "REVOKE ALL ON ALL SEQUENCES IN SCHEMA " + namespace + " FROM PUBLIC, titan_frontend, titan_worker;",
                       "REVOKE ALL ON ALL ROUTINES IN SCHEMA " + namespace + " FROM PUBLIC, titan_frontend, titan_worker;"]
    statements += ["GRANT USAGE ON SCHEMA public, management, titan_runtime, management_graphql, " + schema + " TO titan_frontend;",
                   "GRANT USAGE ON SCHEMA public, management TO titan_worker;",
                   "GRANT EXECUTE ON ALL ROUTINES IN SCHEMA management TO titan_worker;",
                   "GRANT EXECUTE ON ALL ROUTINES IN SCHEMA management_graphql, " + schema + " TO titan_frontend;",
                   "GRANT SELECT ON public.titan_graphql_package_identity TO titan_frontend;",
                   "GRANT SELECT, INSERT ON public.titan_graphql_control_jobs, public.titan_graphql_mutation_receipts, public.titan_graphql_mutation_audit, public.titan_graphql_outbox TO titan_frontend;",
                   "GRANT UPDATE ON public.titan_graphql_mutation_receipts TO titan_frontend;",
                   "GRANT SELECT, INSERT, UPDATE ON titan_runtime.telemetry TO titan_frontend;",
                   "GRANT USAGE ON ALL SEQUENCES IN SCHEMA titan_runtime TO titan_frontend;",
                   "GRANT USAGE ON SEQUENCE public.titan_graphql_mutation_audit_audit_id_seq, public.titan_graphql_outbox_event_id_seq TO titan_frontend;",
                   "GRANT SELECT, UPDATE ON public.titan_graphql_control_jobs TO titan_worker;"]
    for table in FRONTEND_READ:
        statements.append("GRANT SELECT ON management." + table + " TO titan_frontend;")
    for table in REQUEST_TABLES:
        statements += ["GRANT INSERT ON management." + table + " TO titan_frontend;",
                       "GRANT SELECT, REFERENCES ON management." + table + " TO titan_worker;"]
    for table in WORKER_TABLES:
        statements.append("GRANT SELECT, INSERT, UPDATE ON management." + table + " TO titan_worker;")
    statements += ["GRANT DELETE ON management.graphql_registry_operations TO titan_worker;",
                   "GRANT USAGE ON ALL SEQUENCES IN SCHEMA management TO titan_worker;", "COMMIT;"]
    secret_sql(deployment, "\n".join(statements))


def harden(deployment):
    mapping = values(deployment)
    for key in ("DOGFOOD_FRONTEND_PASSWORD", "DOGFOOD_WORKER_PASSWORD"):
        mapping.setdefault(key, secrets.token_hex(32))
    save_values(deployment, mapping)
    deployment.compose("stop", "frontend", "worker")
    apply_roles(deployment)
    deployment.settings["hardened"] = True
    save_json(deployment.directory / "settings.json", deployment.settings)
    deployment.compose("up", "-d", "--force-recreate", "frontend", "worker")
    deployment.await_admin()
    deployment.verify()
    privilege_check(deployment)


def privilege_check(deployment):
    denied = {"titan_frontend": ["SELECT * FROM management.graphql_product_state",
                                 "UPDATE public.titan_graphql_control_jobs SET status = status WHERE false",
                                 "INSERT INTO management.graphql_observed_operations (id) VALUES ('forbidden')"],
              "titan_worker": ["SELECT * FROM public.titan_graphql_package_identity",
                               "SELECT * FROM titan_runtime.telemetry",
                               "INSERT INTO management.graphql_import_requests (job_id) VALUES ('forbidden')"]}
    for role, statements in denied.items():
        statements += ["CREATE TABLE public.forbidden_probe (id integer)",
                       "ALTER TABLE public.titan_graphql_control_jobs ADD COLUMN forbidden_probe integer",
                       "SET ROLE titan_dogfood"]
        for statement in statements:
            try:
                role_sql(deployment, role, "BEGIN; " + statement + "; ROLLBACK;")
            except RuntimeError as failure:
                if "permission denied" not in str(failure) and "must be owner" not in str(failure):
                    raise
            else:
                raise RuntimeError("database role accepted a forbidden operation: " + role)
    dangerous = deployment.sql("SELECT count(*) FROM pg_roles WHERE rolname IN ('titan_frontend','titan_worker') AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls);")
    if not re.search(r"\n\s*0\s*\n", dangerous):
        raise RuntimeError("service roles have administrative attributes")
    if scalar(deployment, "SELECT count(*) FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member WHERE r.rolname IN ('titan_frontend','titan_worker')") != "0":
        raise RuntimeError("service roles have unexpected role memberships")
    for service, assignment in (("frontend", "TITAN_GRAPHQL_JDBC_USERNAME=titan_frontend"),
                                ("worker", "TITAN_GRAPHQL_CONTROL_DB_USER=titan_worker")):
        container = deployment.compose("ps", "-q", service, capture=True).strip()
        template = '{{range .Config.Env}}{{if eq . "' + assignment + '"}}{{println .}}{{end}}{{end}}'
        if run(["docker", "inspect", "--format", template, container], capture=True).strip() != assignment:
            raise RuntimeError("long-running service does not use its restricted identity: " + service)
    evidence = {"verifiedAt": now(), "restrictedServiceRoles": True, "forbiddenOperationsRejected": True,
                "longRunningServicesUseRestrictedRoles": True,
                "grantDefinition": "deployment/operations.py:apply_roles"}
    save_json(deployment.directory / "privilege-verification.json", evidence)
    return evidence


def backup(deployment):
    before = deployment.verify()
    target = deployment.directory / "backups" / str(uuid4())
    target.mkdir(parents=True, mode=0o700)
    target.chmod(0o700)
    deployment.compose("stop", "frontend", "worker")
    try:
        contents = deployment.compose("exec", "-T", "database", "pg_dump", "-U", "titan_dogfood", "-d",
                                      "titan_dogfood", "--no-owner", "--no-acl", capture=True)
        dump = target / "database.sql"
        dump.write_text(contents)
        dump.chmod(0o600)
        shutil.copytree(deployment.deploy, target / "deploy")
        for name in (".env", "settings.json", "workflow.json", "installation.json"):
            shutil.copyfile(deployment.directory / name, target / name)
            (target / name).chmod(0o600)
        inventory = {p.relative_to(target).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                     for p in target.rglob("*") if p.is_file()}
        manifest = {"createdAt": now(), "project": deployment.settings["project"],
                    "previewResults": before["previewResults"], "draft": before["draft"], "files": inventory}
        save_json(target / "manifest.json", manifest)
        save_json(deployment.directory / "latest-backup.json", {"directory": target.relative_to(deployment.directory).as_posix(),
                                                               "createdAt": manifest["createdAt"]})
    finally:
        deployment.compose("up", "-d", "frontend", "worker")
        deployment.await_admin()
    deployment.verify()
    print("Private backup retained under the deployment state directory.")
    return target


def verify_backup(source):
    if source.is_symlink() or any(path.is_symlink() for path in source.rglob("*")):
        raise ValueError("backup must not be a symbolic link")
    manifest = json.loads((source / "manifest.json").read_text())
    for name, expected in manifest["files"].items():
        path = Path(name)
        if path.is_absolute() or ".." in path.parts:
            raise ValueError("invalid backup inventory path")
        actual = source / path
        if actual.is_symlink() or hashlib.sha256(actual.read_bytes()).hexdigest() != expected:
            raise ValueError("backup inventory mismatch")
    actual_files = {p.relative_to(source).as_posix() for p in source.rglob("*") if p.is_file()}
    if actual_files != set(manifest["files"]) | {"manifest.json"}:
        raise ValueError("backup inventory has unexpected files")
    return manifest


def restore_drill(deployment):
    latest = json.loads((deployment.directory / "latest-backup.json").read_text())
    source = deployment.directory / latest["directory"]
    if source.resolve().parent != (deployment.directory / "backups").resolve():
        raise ValueError("backup must belong to this deployment")
    manifest = verify_backup(source)
    identifier = str(uuid4())
    project = "titan-dogfood-restore-" + identifier[:8]
    if run(["docker", "ps", "-aq", "--filter", "label=com.docker.compose.project=" + project], capture=True).strip():
        raise RuntimeError("restore project already exists")
    if run(["docker", "volume", "ls", "-q", "--filter", "name=" + project + "_dogfood-database"], capture=True).strip():
        raise RuntimeError("restore volume already exists")
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    restored = Deployment(deployment.directory / "drills" / identifier)
    restored.initialize(port, project)
    shutil.copytree(source / "deploy", restored.deploy, dirs_exist_ok=True)
    for name in (".env", "workflow.json", "installation.json"):
        shutil.copyfile(source / name, restored.directory / name)
        (restored.directory / name).chmod(0o600)
    mapping = values(restored)
    mapping["DOGFOOD_HTTP_PORT"] = str(port)
    save_values(restored, mapping)
    restored.settings["hardened"] = True
    save_json(restored.directory / "settings.json", restored.settings)
    restored.public_mount_permissions()
    restored.compose("build", "frontend", "worker")
    restored.compose("up", "-d", "--wait", "database")
    empty = restored.sql("SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema');")
    if not re.search(r"\n\s*0\s*\n", empty):
        raise RuntimeError("restore requires an empty database")
    restored.sql((source / "database.sql").read_text())
    apply_roles(restored)
    restored.compose("up", "-d", "frontend", "worker")
    restored.await_admin()
    evidence = restored.verify()
    if evidence["previewResults"] != manifest["previewResults"] or evidence["draft"] != manifest["draft"]:
        raise RuntimeError("restored workflow differs from the backup")
    privilege_check(restored)
    restored.workflow(new_run=True)
    save_json(deployment.directory / "restore-verification.json", {
        "verifiedAt": now(), "backupCreatedAt": manifest["createdAt"], "backupDatabaseSha256": manifest["files"]["database.sql"],
        "originalProject": deployment.settings["project"], "restoreProject": project,
        "restoredStateMatchesBackup": True, "freshWorkflowSucceeded": True,
        "restoredVerification": json.loads((restored.directory / "verification.json").read_text()),
        "drillDirectory": restored.directory.relative_to(deployment.directory).as_posix()})
    deployment.verify()
    print("Backup restored into an independent project and volume; the restored workflow passed.")
    return restored


def probe_password(deployment, role, password):
    identifier = deployment.compose("ps", "-q", "database", capture=True).strip()
    environment = dict(os.environ, PGPASSWORD=password)
    try:
        run(["docker", "exec", "-e", "PGPASSWORD", identifier, "psql", "-X", "-h", "127.0.0.1",
             "-U", role, "-d", "titan_dogfood", "-Atc", "SELECT 1"], capture=True, environment=environment)
        return True
    except RuntimeError as failure:
        if "password authentication failed" not in str(failure):
            raise RuntimeError("database authentication probe failed unexpectedly") from None
        return False


def role_sql(deployment, role, statement):
    if role not in ROLE_KEYS:
        raise ValueError("unknown service database role")
    identifier = deployment.compose("ps", "-q", "database", capture=True).strip()
    environment = dict(os.environ, PGPASSWORD=values(deployment)[ROLE_KEYS[role]])
    return run(["docker", "exec", "-i", "-e", "PGPASSWORD", identifier, "psql", "-X", "-h", "127.0.0.1",
                "-U", role, "-d", "titan_dogfood", "-v", "ON_ERROR_STOP=1"], capture=True,
               input_text=statement, environment=environment)


def scalar(deployment, query):
    return deployment.sql("COPY (" + query + ") TO STDOUT;").strip()


def await_condition(predicate, timeout, failure):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.5)
    raise RuntimeError(failure)


def failure_drill(deployment):
    if not deployment.settings["project"].startswith("titan-dogfood-restore-"):
        raise ValueError("failure drills require an isolated restore project")
    before = deployment.verify()
    identifier = str(uuid4())
    label = "dogfood-drill-" + identifier
    database = deployment.compose("ps", "-q", "database", capture=True).strip()
    command = ["docker", "exec", "-i", database, "psql", "-X", "-q", "-U", "titan_dogfood",
               "-d", "titan_dogfood", "-v", "ON_ERROR_STOP=1", "-c",
               "SET application_name='" + label + "'; BEGIN; LOCK TABLE management.management_drafts IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(180); ROLLBACK;"]
    blocker = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    query = "mutation Import($id: ID!, $yaml: String!) { requestModelImport(id: $id, workspaceId: \"workspace-local-dogfood\", yaml: $yaml) { id workspaceId } }"
    arguments = {"id": identifier, "yaml": MODEL.read_text()}
    try:
        await_condition(lambda: scalar(deployment, "SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid WHERE a.application_name='" + label + "' AND l.relation='management.management_drafts'::regclass AND l.mode='AccessExclusiveLock' AND l.granted") == "1", 15, "worker interruption lock did not become ready")
        deployment.request(query, arguments, key=identifier)
        await_condition(lambda: scalar(deployment, "SELECT status FROM public.titan_graphql_control_jobs WHERE job_id='" + identifier + "'") == "running", 20, "worker did not claim the locked import")
        deployment.compose("kill", "-s", "SIGKILL", "worker")
    finally:
        deployment.sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='" + label + "';")
        blocker.communicate(timeout=10)
        deployment.compose("up", "-d", "worker")
    imported = deployment.await_job(identifier)
    if imported["draftId"] != before["workflow"]["draftId"]:
        raise RuntimeError("recovered import selected an unexpected draft")
    job = json.loads(scalar(deployment, "SELECT json_build_object('id',job_id,'status',status,'attemptCount',attempt_count) FROM public.titan_graphql_control_jobs WHERE job_id='" + identifier + "'"))
    if job["status"] != "succeeded" or job["attemptCount"] < 2:
        raise RuntimeError("the interrupted job did not recover through a lease retry")
    counters = "SELECT json_build_object('jobs',(SELECT count(*) FROM public.titan_graphql_control_jobs WHERE job_id='" + identifier + "'),'requests',(SELECT count(*) FROM management.graphql_import_requests WHERE job_id='" + identifier + "'),'receipts',(SELECT count(*) FROM public.titan_graphql_mutation_receipts WHERE idempotency_key='dogfood-" + identifier + "'),'journalEntries',(SELECT count(*) FROM management.graphql_product_state))"
    committed = json.loads(scalar(deployment, counters))
    deployment.request(query, arguments, key=identifier)
    if committed != json.loads(scalar(deployment, counters)) or any(committed[key] != 1 for key in ("jobs", "requests", "receipts")):
        raise RuntimeError("idempotent request replay duplicated committed effects")
    deployment.compose("stop", "database")
    try:
        try:
            deployment.request(QUERY.read_text(), {"id": identifier}, admin=False, preview=True)
        except (RuntimeError, OSError):
            pass
        else:
            raise RuntimeError("preview unexpectedly served while PostgreSQL was stopped")
    finally:
        deployment.compose("up", "-d", "--wait", "database")
        deployment.await_admin()
    after = deployment.verify()
    if before["previewResults"] != after["previewResults"] or before["draft"] != after["draft"]:
        raise RuntimeError("database outage changed durable workflow data")
    deployment.workflow(new_run=True)
    record = {"verifiedAt": now(), "project": deployment.settings["project"], "interruptedJob": job,
              "committedCountersAfterReplay": committed, "workerKilledDuringRunningImport": True,
              "leaseRetrySucceeded": True, "idempotentReplayPreservedCommittedEffects": True,
              "databaseOutageRejectedRequests": True, "previousWorkflowPreserved": True,
              "freshWorkflowAfterOutageSucceeded": True}
    save_json(deployment.directory / "failure-verification.json", record)
    print("Worker interruption and database outage recovered without duplicate committed effects.")


def rotate(deployment):
    journal = deployment.directory / "rotation-private.json"
    if journal.exists():
        state = json.loads(journal.read_text())
    else:
        previous_verification = deployment.verify()
        previous = values(deployment)
        replacement = dict(previous)
        for key in (*ROLE_KEYS.values(), "DOGFOOD_ADMIN_TOKEN"):
            replacement[key] = secrets.token_hex(32)
        state = {"old": previous, "new": replacement, "startedAt": now(),
                 "previewResults": previous_verification["previewResults"], "draft": previous_verification["draft"]}
        save_json(journal, state)
    if values(deployment) not in (state["old"], state["new"]):
        raise ValueError("saved credentials do not match the pending rotation")
    deployment.compose("stop", "frontend", "worker")
    statement = "BEGIN; SET password_encryption = 'scram-sha-256';\n"
    for role, key in ROLE_KEYS.items():
        credential = state["new"][key]
        if not re.fullmatch(r"[a-f0-9]{64}", credential):
            raise ValueError("invalid rotation credential")
        statement += "ALTER ROLE " + role + " PASSWORD '" + credential + "';\n"
    secret_sql(deployment, statement + "COMMIT;")
    save_values(deployment, state["new"])
    deployment.compose("up", "-d", "--force-recreate", "database", "frontend", "worker")
    deployment.await_admin()
    after = deployment.verify()
    if state["previewResults"] != after["previewResults"] or state["draft"] != after["draft"]:
        raise RuntimeError("rotation changed workflow data")
    for role, key in ROLE_KEYS.items():
        if not probe_password(deployment, role, state["new"][key]) or probe_password(deployment, role, state["old"][key]):
            raise RuntimeError("rotation authentication checks failed")
    request = Request("http://127.0.0.1:" + str(deployment.settings["port"]) + "/admin/graphql",
                      data=b'{"query":"{ __typename }"}', headers={"Content-Type": "application/json",
                      "Authorization": "Bearer " + state["old"]["DOGFOOD_ADMIN_TOKEN"]})
    try:
        urlopen(request, timeout=10)
    except HTTPError as failure:
        if failure.code != 401:
            raise
    else:
        raise RuntimeError("old admin token still works")
    save_json(deployment.directory / "rotation-verification.json", {
        "verifiedAt": now(), "startedAt": state["startedAt"], "newCredentialsAccepted": True,
        "oldCredentialsRejected": True, "workflowDataPreserved": True})
    journal.rename(deployment.directory / ("rotation-private-" + str(uuid4()) + ".json"))
    print("Database credentials and admin token rotated; old credentials no longer authenticate.")


def check(deployment):
    evidence = deployment.verify()
    workflow = evidence["workflow"]
    expiration = datetime.fromisoformat(workflow["previewExpiresAt"])
    if expiration <= datetime.now(timezone.utc):
        raise RuntimeError("preview has expired; renew its reviewed workflow")
    installation = json.loads((deployment.directory / "installation.json").read_text())
    if workflow["publication"]["artifactManifestHash"] != installation["packageIdentity"]["manifestContentSha256"]:
        raise RuntimeError("published package identity differs from installation")
    manifest_path = deployment.deploy / installation["packageDirectory"] / "titan-artifact.json"
    if hashlib.sha256(manifest_path.read_bytes()).hexdigest() != installation["packageIdentity"]["manifestContentSha256"]:
        raise RuntimeError("deployed package manifest differs from installation")
    stalled = deployment.sql("SELECT count(*) FROM public.titan_graphql_control_jobs WHERE (status='pending' AND created_at < now()-interval '180 seconds') OR (status='running' AND lease_until < now());")
    if not re.search(r"\n\s*0\s*\n", stalled):
        raise RuntimeError("pending jobs exceed the local age limit or a running lease has expired")
    privilege_check(deployment)
    save_json(deployment.directory / "health-verification.json", {
        "verifiedAt": now(), "previewValid": True, "packageBindingMatches": True, "noStalledJobs": True,
        "services": evidence["services"]})
    print("Services, reviewed preview, package binding, job leases, and role boundaries pass.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--state-dir", type=Path, default=ROOT / "deployment/.dogfood")
    parser.add_argument("action", choices=("harden", "backup", "restore-drill", "rotate", "check", "failure-drill"))
    options = parser.parse_args()
    deployment = Deployment(options.state_dir)
    deployment.load()
    if options.action != "harden" and not deployment.settings.get("hardened"):
        raise ValueError("harden the deployment before running operational commands")
    {"harden": harden, "backup": backup, "restore-drill": restore_drill,
     "rotate": rotate, "check": check, "failure-drill": failure_drill}[options.action](deployment)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, KeyError) as failure:
        print("operations failed: " + str(failure), file=sys.stderr)
        sys.exit(1)
