import argparse
from contextlib import redirect_stdout
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


def read(directory, name):
    return json.loads((directory / (name + ".json")).read_text())


def collect(directory):
    records = {name: read(directory, name) for name in ("acceptance-verification", "privilege-verification",
               "rotation-verification", "restore-verification", "health-verification", "verification", "installation",
               "worker-commit-audit")}
    restore = records["restore-verification"]
    drill = directory / restore["drillDirectory"]
    if drill.resolve().parent != (directory / "drills").resolve():
        raise ValueError("invalid restore evidence directory")
    records["failure-verification"] = read(drill, "failure-verification")
    records["restored-health-verification"] = read(drill, "health-verification")
    acceptance = records["acceptance-verification"]
    assert acceptance["originalM8JobsUnchanged"] and acceptance["reinstallationSucceeded"]
    assert acceptance["freshWorkflowWithRotatedCredentialsSucceeded"]
    assert acceptance["sourceCommit"] == records["verification"]["sourceCommit"] == records["installation"]["sourceCommit"]
    assert restore["restoredStateMatchesBackup"] and restore["freshWorkflowSucceeded"]
    assert restore["originalProject"] != restore["restoreProject"]
    assert records["failure-verification"]["project"] == restore["restoreProject"]
    for key in ("workerKilledDuringRunningImport", "leaseRetrySucceeded", "idempotentReplayPreservedCommittedEffects",
                "databaseOutageRejectedRequests", "previousWorkflowPreserved", "freshWorkflowAfterOutageSucceeded"):
        assert records["failure-verification"][key]
    assert records["failure-verification"]["interruptedJob"]["attemptCount"] >= 2
    audit = records["worker-commit-audit"]
    assert audit["interruptedJobId"] == records["failure-verification"]["interruptedJob"]["id"]
    assert audit["singleWorkerCommandCommit"] and audit["idempotencyRecords"] == 1
    assert audit["auditByStatus"] == {"attempt": 1, "success": 1}
    assert all(records["rotation-verification"][key] for key in
               ("newCredentialsAccepted", "oldCredentialsRejected", "workflowDataPreserved"))
    assert all(records["privilege-verification"][key] for key in
               ("restrictedServiceRoles", "forbiddenOperationsRejected", "longRunningServicesUseRestrictedRoles"))
    for name in ("health-verification", "restored-health-verification"):
        assert all(records[name][key] for key in ("previewValid", "packageBindingMatches", "noStalledJobs"))
    original = json.loads((ROOT / "docs/release-evidence/m8-dogfood-verification.json").read_text())
    assert acceptance["originalM8Jobs"] == original["execution"]["verification"]["previewResults"]
    primary_volume = records["health-verification"]["services"]["database"]["namedVolumes"]
    assert primary_volume == original["execution"]["verification"]["services"]["database"]["namedVolumes"]
    assert primary_volume != records["restored-health-verification"]["services"]["database"]["namedVolumes"]
    sources = ["deployment/operations.py", "deployment/dogfood.py", "deployment/compose.dogfood.yaml",
               "deployment/compose.hardened.yaml", "deployment/test_operations.py", "deployment/test_dogfood.py"]
    released = json.loads((ROOT / "docs/release-evidence/m7-publication.json").read_text())
    artifacts = []
    for asset in released["downloadedAssets"]:
        if asset["name"].endswith(("-database-http-frontend.zip", "-control-job-worker.zip")):
            digest = "sha256:" + hashlib.sha256((ROOT / "build/distributions" / asset["name"]).read_bytes()).hexdigest()
            assert digest == asset["digest"]
            artifacts.append({"name": asset["name"], "digest": digest, "matchesPublishedM7": True})
    assert artifacts
    suite = unittest.defaultTestLoader.discover(str(ROOT / "deployment"), pattern="test_*.py")
    with redirect_stdout(io.StringIO()):
        result = unittest.TextTestRunner(stream=io.StringIO(), verbosity=0).run(suite)
    assert result.wasSuccessful()
    evidence = {"schemaVersion": "titan.graphql.operations-evidence.v1", "derivedAt": datetime.now(timezone.utc).isoformat(),
                "derivation": "python3 -B docs/release-evidence/derive-operations.py deployment/.dogfood",
                "verifiedCodeCommit": acceptance["sourceCommit"], "execution": records, "runtimeArtifacts": artifacts,
                "sourceSha256": {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in sources},
                "clientTests": {"command": "python3 -B -m unittest discover -s deployment -p 'test_*.py' -v",
                                "testsRun": result.testsRun, "failures": len(result.failures), "errors": len(result.errors)}}
    encoded = json.dumps(evidence, indent=2, sort_keys=True)
    for path in directory.rglob(".env"):
        mapping = dict(line.split("=", 1) for line in path.read_text().splitlines())
        assert all(value not in encoded for key, value in mapping.items() if "PASSWORD" in key or "TOKEN" in key)
    assert "/home/" not in encoded and "/Users/" not in encoded
    return encoded


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("state_directory", type=Path)
    print(collect(parser.parse_args().state_directory))
