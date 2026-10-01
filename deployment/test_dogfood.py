import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("dogfood", Path(__file__).with_name("dogfood.py"))
dogfood = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dogfood)


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "state"
        self.deployment = dogfood.Deployment(self.root)

    def initialize(self):
        self.deployment.initialize(18080, "titan-graphql-dogfood")

    def test_secret_permissions_and_public_mount_survive_restrictive_umask(self):
        previous = os.umask(0o077)
        try:
            self.initialize()
        finally:
            os.umask(previous)
        self.assertEqual(0o700, self.root.stat().st_mode & 0o777)
        self.assertEqual(0o600, (self.root / ".env").stat().st_mode & 0o777)
        self.assertEqual(0o755, self.deployment.deploy.stat().st_mode & 0o777)
        self.assertEqual(0o644, (self.deployment.deploy / "artifacts.properties").stat().st_mode & 0o777)
        values = dict(line.split("=", 1) for line in (self.root / ".env").read_text().splitlines())
        self.assertNotEqual(values["DOGFOOD_DATABASE_PASSWORD"], values["DOGFOOD_ADMIN_TOKEN"])
        for name in ["DOGFOOD_DATABASE_PASSWORD", "DOGFOOD_ADMIN_TOKEN"]:
            self.assertRegex(values[name], r"^[a-f0-9]{64}$")

    def test_reinitialize_preserves_database_credentials(self):
        self.initialize()
        previous = (self.root / ".env").read_bytes()
        self.initialize()
        self.assertEqual(previous, (self.root / ".env").read_bytes())

    def test_reinitialize_rejects_identity_change(self):
        self.initialize()
        with self.assertRaisesRegex(ValueError, "settings differ"):
            self.deployment.initialize(18081, "another-project")

    def test_initialize_rejects_existing_unmanaged_data(self):
        self.root.mkdir()
        marker = self.root / "existing-data"
        marker.write_text("keep")
        with self.assertRaisesRegex(ValueError, "empty directory"):
            self.initialize()
        self.assertEqual("keep", marker.read_text())

    def test_initialize_rejects_invalid_project_and_port(self):
        with self.assertRaises(ValueError):
            self.deployment.initialize(18080, "../other")
        with self.assertRaises(ValueError):
            self.deployment.initialize(80, "dogfood")
        self.assertFalse(self.root.exists())

    def test_load_rejects_public_secret_file(self):
        self.initialize()
        (self.root / ".env").chmod(0o644)
        with self.assertRaisesRegex(ValueError, "0600"):
            self.deployment.load()

    def test_public_mount_rejects_secret_symlinks(self):
        self.initialize()
        (self.deployment.deploy / "secret-link").symlink_to(self.root / ".env")
        with self.assertRaisesRegex(ValueError, "symbolic links"):
            self.deployment.public_mount_permissions()
        self.assertEqual(0o600, (self.root / ".env").stat().st_mode & 0o777)

    def test_compose_scopes_project_and_ignores_ambient_secret_overrides(self):
        self.initialize()
        with patch.dict(os.environ, {"DOGFOOD_ADMIN_TOKEN": "ambient-override",
                                     "DOGFOOD_DATABASE_PASSWORD": "ambient-override", "DOGFOOD_HTTP_PORT": "1"}), \
                patch.object(dogfood, "run", return_value="") as command:
            self.deployment.compose("ps", capture=True)
        arguments, keywords = command.call_args
        self.assertIn("titan-graphql-dogfood", arguments[0])
        self.assertIn(str(self.root / ".env"), arguments[0])
        self.assertIn("--project-name", arguments[0])
        self.assertEqual(str(self.root.resolve()), keywords["environment"]["DOGFOOD_STATE_DIR"])
        for name in ["DOGFOOD_ADMIN_TOKEN", "DOGFOOD_DATABASE_PASSWORD", "DOGFOOD_HTTP_PORT"]:
            self.assertNotIn(name, keywords["environment"])

    def test_authenticated_request_uses_stable_management_keys(self):
        self.initialize()
        response = io.BytesIO(b'{"data":{"ok":true}}')
        request_id = "8def8a07-42a2-491e-9af1-fa21cdc46b20"
        with patch.object(dogfood, "urlopen", return_value=response) as send:
            self.assertEqual({"ok": True}, self.deployment.request("{ ok }", key=request_id))
        request = send.call_args.args[0]
        headers = dict((key.lower(), value) for key, value in request.header_items())
        self.assertTrue(headers["x-titan-management-request-id"].startswith("dogfood-8"))
        self.assertEqual(headers["x-titan-management-request-id"], headers["x-titan-management-idempotency-key"])
        self.assertTrue(headers["authorization"].startswith("Bearer "))
        self.assertNotIn("x-titan-actor-role", headers)

    def test_preview_request_has_no_token_or_trusted_actor_headers(self):
        self.initialize()
        response = io.BytesIO(b'{"data":{"job":null}}')
        with patch.object(dogfood, "urlopen", return_value=response) as send:
            self.deployment.request(dogfood.QUERY.read_text(), {"id": "job"}, admin=False, preview=True)
        request = send.call_args.args[0]
        headers = dict((key.lower(), value) for key, value in request.header_items())
        self.assertNotIn("authorization", headers)
        self.assertNotIn("x-titan-actor-role", headers)
        self.assertIn("/preview/dogfood-jobs/graphql", request.full_url)
        self.assertEqual("local-dogfood", json.loads(request.data)["extensions"]["client"])

    def test_graphql_errors_fail_verification(self):
        self.initialize()
        response = io.BytesIO(b'{"errors":[{"extensions":{"code":"OPERATION_REGISTRY_REJECTED"}}]}')
        with patch.object(dogfood, "urlopen", return_value=response), self.assertRaisesRegex(RuntimeError, "OPERATION_REGISTRY_REJECTED"):
            self.deployment.request("{ __typename }", admin=False, preview=True)

    def test_readiness_retries_connection_reset(self):
        with patch.object(self.deployment, "request", side_effect=[ConnectionResetError(), {"modelDraft": None}]) as request, \
                patch.object(dogfood.time, "sleep"):
            self.deployment.await_admin()
        self.assertEqual(2, request.call_count)

    def test_import_uses_workspace_scoped_identity(self):
        self.initialize()
        with patch.object(self.deployment, "request") as request, \
                patch.object(self.deployment, "await_job", side_effect=RuntimeError("stop after import")), \
                self.assertRaisesRegex(RuntimeError, "stop after import"):
            self.deployment.workflow()
        self.assertIn('workspaceId: "workspace-local-dogfood"', request.call_args.args[0])

    def test_only_one_shot_publisher_receives_writable_mount_and_secret_environment(self):
        self.initialize()
        with patch.object(self.deployment, "worker_image", return_value="a" * 64), \
                patch.object(dogfood, "run", return_value='{"manifest":{}}') as command:
            self.deployment.control("publish-preview", "postgresql", dogfood.JDBC)
        arguments, keywords = command.call_args
        self.assertIn(str(self.deployment.deploy) + ":/deploy:rw", arguments[0])
        self.assertIn("titan-graphql-dogfood_default", arguments[0])
        password = keywords["environment"]["TITAN_GRAPHQL_CONTROL_DB_PASSWORD"]
        self.assertNotIn(password, " ".join(arguments[0]))

    def test_installer_does_not_override_service_mount(self):
        self.initialize()
        with patch.object(self.deployment, "compose", return_value="READY") as command:
            self.deployment.control("install-management", "postgresql", dogfood.JDBC)
        self.assertNotIn("--volume", command.call_args.args)

    def test_artifact_registration_is_unique_across_workflow_retries(self):
        self.initialize()
        key = "draft-" + "a" * 12
        target = "/deploy/packages/" + "b" * 64
        path = self.deployment.deploy / "artifacts.properties"
        path.write_text((key + "=" + target + "\n") * 3)
        self.deployment.register_package(key, target)
        self.deployment.register_package(key, target)
        self.assertEqual(key + "=" + target + "\n", path.read_text())
        self.assertEqual(0o644, path.stat().st_mode & 0o777)

    def test_artifact_registration_rejects_contradictory_existing_entries(self):
        self.initialize()
        key = "draft-" + "a" * 12
        path = self.deployment.deploy / "artifacts.properties"
        original = key + "=/deploy/packages/" + "b" * 64 + "\n" + key + "=/deploy/packages/" + "c" * 64 + "\n"
        path.write_text(original)
        with self.assertRaisesRegex(ValueError, "contradictory"):
            self.deployment.register_package(key, "/deploy/packages/" + "b" * 64)
        self.assertEqual(original, path.read_text())


if __name__ == "__main__":
    unittest.main()
