#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent.parent
MODEL = ROOT / "deployment/dogfood/jobs.titan.graphql.yaml"
QUERY = ROOT / "deployment/dogfood/job.graphql"
JDBC = "jdbc:postgresql://database:5432/titan_dogfood"
PREVIEW = "dogfood-jobs"


def now():
    return datetime.now(timezone.utc).isoformat()


def save_json(path, value):
    temporary = path.with_suffix(path.suffix + ".new")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
    temporary.chmod(0o600)
    temporary.replace(path)


def run(command, *, capture=False, input_text=None, environment=None):
    result = subprocess.run(command, cwd=ROOT, input=input_text, text=True,
                            env=environment, stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE if capture else None)
    if result.returncode:
        raise RuntimeError("command failed: " + str(command[0]) +
                           ("\n" + result.stderr[-4000:] if capture else ""))
    return result.stdout if capture else None


class Deployment:
    def __init__(self, directory):
        self.directory = directory.resolve()
        self.deploy = self.directory / "deploy"
        self.settings = None

    def public_mount_permissions(self):
        paths = [self.deploy, *self.deploy.rglob("*")]
        if any(path.is_symlink() for path in paths):
            raise ValueError("deployment mount must not contain symbolic links")
        for path in paths:
            path.chmod(0o755 if path.is_dir() else 0o644)

    def initialize(self, port, project):
        if not re.fullmatch(r"[a-z][a-z0-9-]{0,48}", project):
            raise ValueError("Compose project must use lowercase letters, digits, and hyphens")
        if not 1024 <= port <= 65535:
            raise ValueError("HTTP port must be between 1024 and 65535")
        settings = self.directory / "settings.json"
        if settings.exists():
            self.load()
            if self.settings["port"] != port or self.settings["project"] != project:
                raise ValueError("existing deployment settings differ; do not replace its database identity")
            return
        if self.directory.exists() and any(self.directory.iterdir()):
            raise ValueError("initialize requires an empty directory or an existing managed deployment")
        self.directory.mkdir(parents=True, exist_ok=True)
        self.directory.chmod(0o700)
        self.deploy.mkdir(mode=0o755)
        (self.deploy / "previews").mkdir(mode=0o755)
        (self.deploy / "previews/registry.properties").write_text("")
        (self.deploy / "artifacts.properties").write_text("")
        env = self.directory / ".env"
        env.write_text("DOGFOOD_DATABASE_PASSWORD=" + secrets.token_hex(32) + "\n"
                       + "DOGFOOD_ADMIN_TOKEN=" + secrets.token_hex(32) + "\n"
                       + "DOGFOOD_HTTP_PORT=" + str(port) + "\n")
        env.chmod(0o600)
        self.settings = {"managedBy": "deployment/dogfood.py", "project": project,
                         "port": port, "createdAt": now()}
        save_json(settings, self.settings)
        self.public_mount_permissions()

    def load(self):
        self.settings = json.loads((self.directory / "settings.json").read_text())
        if self.settings.get("managedBy") != "deployment/dogfood.py":
            raise ValueError("not a managed dogfood deployment")
        if (self.directory / ".env").stat().st_mode & 0o077:
            raise ValueError("deployment secret file must have mode 0600")

    def compose(self, *arguments, capture=False, input_text=None):
        environment = dict(os.environ)
        for name in ["DOGFOOD_DATABASE_PASSWORD", "DOGFOOD_ADMIN_TOKEN", "DOGFOOD_HTTP_PORT",
                     "DOGFOOD_FRONTEND_PASSWORD", "DOGFOOD_WORKER_PASSWORD"]:
            environment.pop(name, None)
        environment["DOGFOOD_STATE_DIR"] = str(self.directory)
        configuration = ["-f", str(ROOT / "deployment/compose.dogfood.yaml")]
        if self.settings.get("hardened"):
            configuration += ["-f", str(ROOT / "deployment/compose.hardened.yaml")]
        return run(["docker", "compose", "--project-name", self.settings["project"],
                    "--env-file", str(self.directory / ".env"), *configuration, *arguments],
                   capture=capture, input_text=input_text, environment=environment)

    def control(self, *arguments):
        if arguments[0] == "publish-preview" or (arguments[0] == "install-management" and self.settings.get("hardened")):
            image = self.worker_image()
            if not re.fullmatch(r"(?:sha256:)?[a-f0-9]{64}", image):
                raise RuntimeError("the built worker image is required for publication")
            environment = dict(os.environ)
            values = dict(line.split("=", 1) for line in (self.directory / ".env").read_text().splitlines())
            environment["TITAN_GRAPHQL_CONTROL_DB_USER"] = "titan_dogfood"
            environment["TITAN_GRAPHQL_CONTROL_DB_PASSWORD"] = values["DOGFOOD_DATABASE_PASSWORD"]
            output = run(["docker", "run", "--rm", "--network", self.settings["project"] + "_default",
                          "--user", str(os.getuid()) + ":" + str(os.getgid()), "--volume",
                          str(self.deploy) + (":/deploy:rw" if arguments[0] == "publish-preview" else ":/deploy:ro"), "--env", "TITAN_GRAPHQL_CONTROL_DB_USER",
                          "--env", "TITAN_GRAPHQL_CONTROL_DB_PASSWORD", "--entrypoint",
                          "/opt/titan/bin/titan-graphql-control", image, *arguments],
                         capture=True, environment=environment)
        else:
            output = self.compose("run", "--rm", "--no-deps", "--user",
                                  str(os.getuid()) + ":" + str(os.getgid()), "--entrypoint",
                                  "/opt/titan/bin/titan-graphql-control", "worker", *arguments, capture=True)
        lines = [line for line in output.splitlines() if line.startswith("{")]
        return json.loads(lines[-1]) if lines else None

    def worker_image(self):
        return run(["docker", "image", "inspect", "--format", "{{.Id}}",
                    self.settings["project"] + "-worker:latest"], capture=True).strip()

    def operator(self, action, model_id, run_id):
        output = self.compose("run", "--rm", "--no-deps", "--entrypoint", "java", "worker",
                              "-cp", "/opt/titan/lib/*:/deploy/tools", "LocalDogfoodControl",
                              action, JDBC, model_id, "/deploy/job.graphql", run_id, capture=True)
        return json.loads(output.strip())

    def sql(self, text):
        return self.compose("exec", "-T", "database", "psql", "-X", "-q",
                            "-v", "ON_ERROR_STOP=1", "-U", "titan_dogfood", "-d",
                            "titan_dogfood", capture=True, input_text=text)

    def snapshot(self):
        services = {}
        for service in ["database", "frontend", "worker"]:
            identifier = self.compose("ps", "-q", service, capture=True).strip()
            if not re.fullmatch(r"[a-f0-9]{64}", identifier):
                raise RuntimeError("service is not running: " + service)
            def inspect(field):
                return run(["docker", "inspect", "--format", "{{json " + field + "}}", identifier], capture=True).strip()
            status = json.loads(inspect(".State"))
            if not status["Running"] or status["Restarting"]:
                raise RuntimeError("service is not stable: " + service)
            ports = json.loads(inspect(".HostConfig.PortBindings")) or {}
            expected_ports = {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": str(self.settings["port"])}]} if service == "frontend" else {}
            if ports != expected_ports:
                raise RuntimeError("service has unexpected published ports: " + service)
            restart = json.loads(inspect(".HostConfig.RestartPolicy"))
            if restart["Name"] != "unless-stopped":
                raise RuntimeError("service restart policy is not persistent: " + service)
            mounts = json.loads(inspect(".Mounts"))
            if service != "database" and not any(m["Destination"] == "/deploy" and not m["RW"] for m in mounts):
                raise RuntimeError("deployment mount must be read-only: " + service)
            services[service] = {"containerId": identifier, "imageId": json.loads(inspect(".Image")),
                                 "restartPolicy": restart["Name"], "publishedPorts": ports,
                                 "namedVolumes": [m["Name"] for m in mounts if m["Type"] == "volume"]}
        expected_volume = self.settings["project"] + "_dogfood-database"
        if services["database"]["namedVolumes"] != [expected_volume]:
            raise RuntimeError("database is not using the dedicated persistent volume")
        return services

    def stage_package(self, source):
        identity = json.loads((source / "titan-graphql-package.json").read_text())
        key = (source / "titan-graphql-database-package-identity.sha256").read_text().strip()
        if not re.fullmatch(r"[a-f0-9]{64}", key):
            raise ValueError("invalid package identity")
        target = self.deploy / "packages" / key
        shutil.copytree(source, target, dirs_exist_ok=True)
        for path in [target, *target.rglob("*")]:
            path.chmod(0o755 if path.is_dir() else 0o644)
        for migration in sorted((target / "postgresql").glob("*.sql")):
            self.sql(migration.read_text())
        return target, identity

    def register_package(self, draft_id, package_path):
        if not re.fullmatch(r"draft-[a-f0-9]{12}", draft_id) or not re.fullmatch(r"/deploy/packages/[a-f0-9]{64}", package_path):
            raise ValueError("invalid dogfood artifact registry identity")
        path = self.deploy / "artifacts.properties"
        entries = {}
        for line in path.read_text().splitlines():
            if not line.strip() or line.startswith("#"):
                continue
            key, value = line.split("=", 1)
            if key in entries and entries[key] != value:
                raise ValueError("contradictory artifact registry entries")
            entries[key] = value
        entries[draft_id] = package_path
        temporary = path.with_suffix(".properties.new")
        temporary.write_text("".join(key + "=" + value + "\n" for key, value in sorted(entries.items())))
        temporary.chmod(0o644)
        temporary.replace(path)

    def up(self):
        key = hashlib.sha256(PREVIEW.encode() + b"\0" + MODEL.read_bytes()).hexdigest()[:24]
        run([str(ROOT / "gradlew"), "--console=plain",
             "-PtitanGraphqlPreviewModel=" + str(MODEL), "-PtitanGraphqlPreviewId=" + PREVIEW,
             "titanGraphqlBuildPreviewPostgreSql",
             "titanGraphqlGenerateManagementPostgreSqlDatabaseEngineFrontendDescriptor",
             "titanGraphqlDatabaseHttpFrontendReleaseArtifact", "controlJobWorkerDistribution"])
        self.compose("build", "frontend", "worker")
        self.compose("up", "-d", "--wait", "database")
        self.control("install-management", "postgresql", JDBC)
        for name in ["R__titan_004_graphql_mutation_state.sql", "R__titan_006_graphql_outbox.sql",
                     "R__titan_007_graphql_control_jobs.sql"]:
            self.sql((ROOT / "src/database-engine-migrations/postgresql" / name).read_text())
        management_root = ROOT / "build/generated/proofs/database-engine-management-postgresql"
        self.stage_package(management_root / "package")
        shutil.copyfile(management_root / "frontend-deployment.properties",
                        self.deploy / "management.properties")
        package = ROOT / ("build/generated/preview-candidates/" + PREVIEW + "-" + key + "/postgresql/package")
        staged, identity = self.stage_package(package)
        shutil.copyfile(package / "frontend-deployment.properties", self.deploy / "application.properties")
        shutil.copyfile(QUERY, self.deploy / "job.graphql")
        shutil.copyfile(MODEL, self.deploy / "jobs.titan.graphql.yaml")
        toolchain = self.directory / "worker-distribution"
        with ZipFile(ROOT / "build/distributions/titan-graphql-0.1.0-control-job-worker.zip") as archive:
            archive.extractall(toolchain)
        tools = self.deploy / "tools"
        tools.mkdir(exist_ok=True)
        run(["javac", "-cp", str(toolchain / "lib/*"), "-d", str(tools),
             str(ROOT / "deployment/dogfood/LocalDogfoodControl.java")])
        save_json(self.directory / "installation.json", {
            "installedAt": now(), "sourceCommit": run(["git", "rev-parse", "HEAD"], capture=True).strip(),
            "packageDirectory": staged.relative_to(self.deploy).as_posix(), "packageIdentity": identity})
        self.public_mount_permissions()
        if self.settings.get("hardened"):
            from operations import apply_roles
            apply_roles(self)
        self.compose("up", "-d", "frontend", "worker")
        self.await_admin()

    def token(self):
        values = dict(line.split("=", 1) for line in (self.directory / ".env").read_text().splitlines())
        return values["DOGFOOD_ADMIN_TOKEN"]

    def request(self, query, variables=None, *, admin=True, key=None, preview=False):
        path = "/preview/" + PREVIEW + "/graphql" if preview else "/admin/graphql" if admin else "/graphql"
        headers = {"Content-Type": "application/json"}
        if admin:
            headers["Authorization"] = "Bearer " + self.token()
        if key:
            headers["X-Titan-Management-Request-Id"] = "dogfood-" + key
            headers["X-Titan-Management-Idempotency-Key"] = "dogfood-" + key
        body = json.dumps({"query": query, "variables": variables or {},
                           "extensions": {"client": "local-dogfood"}}).encode()
        request = Request("http://127.0.0.1:" + str(self.settings["port"]) + path,
                          data=body, headers=headers)
        with urlopen(request, timeout=35) as response:
            result = json.load(response)
        if result.get("errors"):
            raise RuntimeError("GraphQL request failed: " + json.dumps(result["errors"]))
        return result["data"]

    def await_admin(self):
        for _ in range(60):
            try:
                self.request('{ modelDraft(id: "missing-dogfood-draft") { id } }')
                return
            except (OSError, RuntimeError):
                time.sleep(1)
        raise RuntimeError("management frontend did not become ready")

    def await_job(self, identifier):
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            job = self.request("query Job($id: ID!) { controlJob(id: $id) { id status resultJson failureCode } }",
                               {"id": identifier})["controlJob"]
            if job and job["status"].lower() == "succeeded":
                return json.loads(job["resultJson"])
            if job and job["status"].lower() == "failed":
                raise RuntimeError("control job failed: " + str(job["failureCode"]))
            time.sleep(0.5)
        raise RuntimeError("control job timed out: " + identifier)

    def workflow(self, new_run=False):
        path = self.directory / "workflow.json"
        if new_run and path.exists():
            previous = json.loads(path.read_text())
            save_json(self.directory / ("workflow-" + previous["runId"] + ".json"), previous)
        if new_run or not path.exists():
            state = {"runId": str(uuid4()), "startedAt": now(),
                     "modelSourceSha256": hashlib.sha256(MODEL.read_bytes()).hexdigest(),
                     "jobs": {kind: str(uuid4()) for kind in ["import", "validation", "artifact", "review"]}}
            save_json(path, state)
        state = json.loads(path.read_text())
        if state["modelSourceSha256"] != hashlib.sha256(MODEL.read_bytes()).hexdigest():
            raise ValueError("model changed; use workflow --new-run after rebuilding its package")
        jobs = state["jobs"]
        self.request("mutation Import($id: ID!, $yaml: String!) { requestModelImport(id: $id, "
                     'workspaceId: "workspace-local-dogfood", yaml: $yaml) { id workspaceId } }',
                     {"id": jobs["import"], "yaml": MODEL.read_text()}, key=jobs["import"])
        imported = self.await_job(jobs["import"])
        state.update({"draftId": imported["draftId"], "modelId": imported["modelId"]})
        save_json(path, state)
        self.request("mutation Validate($id: ID!, $draft: String!) { requestModelValidation(id: $id, "
                     "draftId: $draft) { id draftId } }", {"id": jobs["validation"], "draft": state["draftId"]},
                     key=jobs["validation"])
        if self.await_job(jobs["validation"])["status"] != "VALIDATED":
            raise RuntimeError("model validation did not succeed")
        installation = json.loads((self.directory / "installation.json").read_text())
        package_path = "/deploy/" + installation["packageDirectory"]
        self.register_package(state["draftId"], package_path)
        self.request("mutation Artifact($id: ID!, $draft: String!) { requestArtifactGeneration(id: $id, "
                     'draftId: $draft, generationProfile: "dogfood", enableIntrospection: true) { id draftId } }',
                     {"id": jobs["artifact"], "draft": state["draftId"]}, key=jobs["artifact"])
        state["artifactSetId"] = self.await_job(jobs["artifact"])["artifactSetId"]
        if "observedOperationId" not in state:
            state.update(self.operator("observe", state["modelId"], state["runId"]))
            save_json(path, state)
        self.request("mutation Review($id: ID!, $observed: String!) { requestObservedOperationReview(id: $id, "
                     'observedOperationId: $observed, decision: "approve") { id observedOperationId } }',
                     {"id": jobs["review"], "observed": state["observedOperationId"]}, key=jobs["review"])
        self.await_job(jobs["review"])
        if not state.get("publicScopeApproved"):
            self.operator("enforce-public-read", state["modelId"], state["runId"])
            state["publicScopeApproved"] = True
            save_json(path, state)
        expiration = (datetime.now(timezone.utc) + timedelta(days=7)).isoformat()
        publication = self.control("publish-preview", "postgresql", JDBC, package_path,
                                   "/deploy/previews/registry.properties", PREVIEW, state["draftId"],
                                   "dogfood", state["registryId"], expiration, "local-dogfood-operator")
        state["previewExpiresAt"] = expiration
        state["publication"] = publication["manifest"]
        state["completedAt"] = now()
        save_json(path, state)
        self.public_mount_permissions()
        self.compose("up", "-d", "--no-deps", "--force-recreate", "frontend")
        self.await_admin()
        self.verify()

    def verify(self):
        state = json.loads((self.directory / "workflow.json").read_text())
        query = QUERY.read_text()
        results = {}
        for kind, identifier in state["jobs"].items():
            result = self.request(query, {"id": identifier}, admin=False, preview=True)["job"]
            if not result or result["id"] != identifier or result["status"] != "succeeded" or result["attemptCount"] < 1:
                raise RuntimeError("published preview did not return the durable job: " + identifier)
            results[kind] = result
        draft = self.request("query Draft($id: ID!) { modelDraft(id: $id) { id status } }",
                             {"id": state["draftId"]})["modelDraft"]
        if draft["status"] != "validated":
            raise RuntimeError("artifact-backed draft has an invalid physical status: " + draft["status"])
        try:
            self.request("{ modelDraft(id: \"missing\") { id } }", admin=False)
        except RuntimeError:
            pass
        else:
            raise RuntimeError("application route unexpectedly exposed management fields")
        try:
            self.request("{ __typename }", admin=False, preview=True)
        except RuntimeError as failure:
            if "OPERATION_REGISTRY_REJECTED" not in str(failure):
                raise
        else:
            raise RuntimeError("preview accepted an unreviewed operation")
        unauthorized = Request("http://127.0.0.1:" + str(self.settings["port"]) + "/admin/graphql",
                               data=b'{"query":"{ __typename }"}', headers={"Content-Type": "application/json"})
        try:
            urlopen(unauthorized, timeout=10)
        except HTTPError as failure:
            if failure.code != 401:
                raise
        else:
            raise RuntimeError("admin route accepted an unauthenticated request")
        evidence = {"verifiedAt": now(), "sourceCommit": run(["git", "rev-parse", "HEAD"], capture=True).strip(),
                    "workflow": state, "previewResults": results, "draft": draft,
                    "services": self.snapshot(),
                    "checks": {"reviewedPreviewReadsActualJobs": True, "unreviewedOperationRejected": True,
                               "adminAuthenticationRequired": True, "managementNotExposedOnApplicationRoute": True}}
        save_json(self.directory / "verification.json", evidence)
        print("Dogfood workflow verified at http://127.0.0.1:" + str(self.settings["port"]))
        return evidence

    def recover(self):
        before = self.verify()
        ids_before = [s["containerId"] for s in before["services"].values()]
        self.compose("down")
        self.compose("up", "-d", "--wait", "database")
        self.compose("up", "-d", "frontend", "worker")
        self.await_admin()
        after = self.verify()
        ids_after = [s["containerId"] for s in after["services"].values()]
        if len(ids_before) != 3 or len(ids_after) != 3 or set(ids_before) & set(ids_after):
            raise RuntimeError("recovery must replace all service containers")
        if before["previewResults"] != after["previewResults"] or before["draft"] != after["draft"]:
            raise RuntimeError("durable workflow state changed during recovery")
        if before["services"]["database"]["namedVolumes"] != after["services"]["database"]["namedVolumes"]:
            raise RuntimeError("recovery replaced the persistent database volume")
        self.workflow(new_run=True)
        save_json(self.directory / "recovery.json", {
            "verifiedAt": now(), "previousWorkflow": before["workflow"], "previousJobsAfterRecreation": after["previewResults"],
            "allServiceContainersReplaced": True, "databaseVolumeRetained": True,
            "servicesBefore": before["services"], "servicesAfter": after["services"],
            "newWorkflowAfterRecovery": json.loads((self.directory / "workflow.json").read_text())})
        print("Container recreation preserves state and the worker processes a new workflow.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--state-dir", type=Path, default=ROOT / "deployment/.dogfood")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--project", default="titan-graphql-dogfood")
    parser.add_argument("action", choices=["init", "up", "workflow", "verify", "recover", "status", "stop"])
    parser.add_argument("--new-run", action="store_true")
    options = parser.parse_args()
    deployment = Deployment(options.state_dir)
    if options.action in ["init", "up"]:
        deployment.initialize(options.port, options.project)
    else:
        deployment.load()
    if options.action == "init":
        print("Deployment initialized; generated secrets remain in its private .env file.")
    elif options.action == "up":
        deployment.up()
    elif options.action == "workflow":
        deployment.workflow(options.new_run)
    elif options.action == "verify":
        deployment.verify()
    elif options.action == "recover":
        deployment.recover()
    elif options.action == "status":
        deployment.compose("ps")
    elif options.action == "stop":
        deployment.compose("stop")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, KeyError) as failure:
        print("dogfood failed: " + str(failure), file=sys.stderr)
        sys.exit(1)
