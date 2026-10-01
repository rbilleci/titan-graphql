import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import dogfood
import operations


class OperationsTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.deployment = dogfood.Deployment(Path(self.temporary.name) / "state")
        self.deployment.initialize(18080, "dogfood-test")
        self.mapping = operations.values(self.deployment)
        self.mapping.update(DOGFOOD_FRONTEND_PASSWORD="a" * 64, DOGFOOD_WORKER_PASSWORD="b" * 64)
        operations.save_values(self.deployment, self.mapping)
        (self.deployment.deploy / "application.properties").write_text("entry-point-schema=preview_" + "c" * 24 + "\n")

    def test_roles_keep_ownership_and_ddl_out_of_services(self):
        with patch.object(self.deployment, "sql", return_value="") as sql:
            operations.apply_roles(self.deployment)
        statement = sql.call_args.args[0]
        self.assertIn("NOSUPERUSER NOCREATEDB NOCREATEROLE", statement)
        self.assertIn("REVOKE ALL ON DATABASE titan_dogfood FROM PUBLIC", statement)
        self.assertIn("GRANT SELECT, UPDATE ON public.titan_graphql_control_jobs TO titan_worker", statement)
        self.assertIn("GRANT SELECT, REFERENCES ON management.graphql_import_requests TO titan_worker", statement)
        self.assertNotIn("GRANT CREATE", statement)
        self.assertNotIn("GRANT ALL", statement)
        self.assertNotIn("OWNER TO", statement)
        self.assertNotIn("SECURITY DEFINER", statement)

    def test_role_setup_rejects_schema_and_credential_injection(self):
        (self.deployment.deploy / "application.properties").write_text("entry-point-schema=public; DROP SCHEMA management\n")
        with patch.object(self.deployment, "sql") as sql, self.assertRaises(ValueError):
            operations.apply_roles(self.deployment)
        sql.assert_not_called()
        self.mapping["DOGFOOD_FRONTEND_PASSWORD"] = "'invalid"
        with self.assertRaises(ValueError):
            operations.save_values(self.deployment, self.mapping)

    def test_saved_credentials_are_atomic_and_private(self):
        self.assertEqual(self.mapping, operations.values(self.deployment))
        self.assertEqual(0o600, (self.deployment.directory / ".env").stat().st_mode & 0o777)
        self.assertFalse((self.deployment.directory / ".env.new").exists())

    def test_secret_sql_does_not_echo_failure_context(self):
        with patch.object(self.deployment, "sql", side_effect=RuntimeError("SQL with private credential")):
            with self.assertRaisesRegex(RuntimeError, "credential SQL failed") as failure:
                operations.secret_sql(self.deployment, "private credential")
        self.assertNotIn("SQL with private credential", str(failure.exception))

    def test_hardened_compose_ignores_ambient_role_credentials(self):
        self.deployment.settings["hardened"] = True
        with patch.dict(os.environ, {"DOGFOOD_FRONTEND_PASSWORD": "ambient", "DOGFOOD_WORKER_PASSWORD": "ambient"}), \
                patch.object(dogfood, "run", return_value="") as command:
            self.deployment.compose("ps")
        args, kwargs = command.call_args
        self.assertIn(str(dogfood.ROOT / "deployment/compose.hardened.yaml"), args[0])
        self.assertNotIn("DOGFOOD_FRONTEND_PASSWORD", kwargs["environment"])
        self.assertNotIn("DOGFOOD_WORKER_PASSWORD", kwargs["environment"])

    def test_hardened_installer_uses_owner_only_in_read_only_one_shot(self):
        self.deployment.settings["hardened"] = True
        with patch.object(self.deployment, "worker_image", return_value="a" * 64), \
                patch.object(dogfood, "run", return_value="") as command:
            self.deployment.control("install-management", "postgresql", dogfood.JDBC)
        args, kwargs = command.call_args
        self.assertIn(str(self.deployment.deploy) + ":/deploy:ro", args[0])
        self.assertEqual("titan_dogfood", kwargs["environment"]["TITAN_GRAPHQL_CONTROL_DB_USER"])
        self.assertNotIn(self.mapping["DOGFOOD_DATABASE_PASSWORD"], " ".join(args[0]))

    def test_operator_uses_built_tag_not_obsolete_running_container_image(self):
        with patch.object(dogfood, "run", return_value="sha256:" + "a" * 64) as command:
            self.assertEqual("sha256:" + "a" * 64, self.deployment.worker_image())
        self.assertEqual("dogfood-test-worker:latest", command.call_args.args[0][-1])

    def test_backup_verification_rejects_tampering_and_traversal(self):
        source = self.deployment.directory / "backup-test"
        source.mkdir()
        data = source / "database.sql"
        data.write_text("SELECT 1;\n")
        digest = operations.hashlib.sha256(data.read_bytes()).hexdigest()
        (source / "manifest.json").write_text(json.dumps({"files": {"database.sql": digest}}))
        operations.verify_backup(source)
        data.write_text("modified")
        with self.assertRaisesRegex(ValueError, "mismatch"):
            operations.verify_backup(source)
        (source / "manifest.json").write_text(json.dumps({"files": {"../.env": digest}}))
        with self.assertRaisesRegex(ValueError, "path"):
            operations.verify_backup(source)

    def test_backup_verification_rejects_uninventoried_files_and_symlinks(self):
        source = self.deployment.directory / "backup-test"
        source.mkdir()
        (source / "manifest.json").write_text(json.dumps({"files": {}}))
        (source / "extra").write_text("unexpected")
        with self.assertRaisesRegex(ValueError, "unexpected"):
            operations.verify_backup(source)
        link = self.deployment.directory / "backup-link"
        link.symlink_to(source, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symbolic"):
            operations.verify_backup(link)

    def test_failure_drill_refuses_primary_deployment(self):
        with patch.object(self.deployment, "compose") as command, self.assertRaisesRegex(ValueError, "isolated"):
            operations.failure_drill(self.deployment)
        command.assert_not_called()

    def test_authentication_probe_keeps_password_out_of_arguments(self):
        with patch.object(self.deployment, "compose", return_value="c" * 64), \
                patch.object(operations, "run", return_value="1") as command:
            self.assertTrue(operations.probe_password(self.deployment, "titan_worker", "b" * 64))
        args, kwargs = command.call_args
        self.assertNotIn("b" * 64, " ".join(args[0]))
        self.assertEqual("b" * 64, kwargs["environment"]["PGPASSWORD"])

    def test_privilege_probes_authenticate_as_service_not_superuser_session(self):
        with patch.object(self.deployment, "compose", return_value="c" * 64), \
                patch.object(operations, "run", return_value="") as command:
            operations.role_sql(self.deployment, "titan_frontend", "SELECT 1")
        args, kwargs = command.call_args
        self.assertIn("127.0.0.1", args[0])
        self.assertIn("titan_frontend", args[0])
        self.assertEqual("SELECT 1", kwargs["input_text"])
        self.assertEqual("a" * 64, kwargs["environment"]["PGPASSWORD"])
        self.assertNotIn("SET ROLE", kwargs["input_text"])

    def test_rotation_resume_uses_retained_baseline_without_old_authentication(self):
        self.deployment.settings["hardened"] = True
        updated = {**self.mapping, **{key: "d" * 64 for key in (*operations.ROLE_KEYS.values(), "DOGFOOD_ADMIN_TOKEN")}}
        state = {"old": self.mapping, "new": updated, "startedAt": "2026-10-01T00:00:00+00:00",
                 "previewResults": {"job": "preserved"}, "draft": {"id": "preserved"}}
        dogfood.save_json(self.deployment.directory / "rotation-private.json", state)
        operations.save_values(self.deployment, updated)
        with patch.object(self.deployment, "compose"), patch.object(self.deployment, "await_admin"), \
                patch.object(self.deployment, "sql", return_value=""), \
                patch.object(self.deployment, "verify", return_value={"previewResults": state["previewResults"], "draft": state["draft"]}) as verify, \
                patch.object(operations, "probe_password", side_effect=[True, False] * 3), \
                patch.object(operations, "urlopen", side_effect=operations.HTTPError("local", 401, "denied", {}, None)):
            operations.rotate(self.deployment)
        self.assertEqual(1, verify.call_count)
        self.assertFalse((self.deployment.directory / "rotation-private.json").exists())


if __name__ == "__main__":
    unittest.main()
