import argparse
import hashlib
import io
import json
from pathlib import Path
import unittest
from datetime import datetime, timezone


ROOT = Path(__file__).resolve().parents[2]


def collect(directory):
    records = {name: json.loads((directory / (name + ".json")).read_text())
               for name in ("installation", "verification", "recovery", "startup-verification")}
    verification = records["verification"]
    recovery = records["recovery"]
    startup = records["startup-verification"]
    assert all(verification["checks"].values())
    assert recovery["allServiceContainersReplaced"] and recovery["databaseVolumeRetained"]
    assert all(startup[key] for key in ("databaseVolumePreserved", "draftPreserved",
                                      "previousJobsPreserved", "secretValuesPreserved"))
    assert verification["workflow"] == recovery["newWorkflowAfterRecovery"]
    assert verification["sourceCommit"] == startup["sourceCommit"] == records["installation"]["sourceCommit"]
    assert records["installation"] == startup["packageInstallation"]
    before, after = recovery["servicesBefore"], recovery["servicesAfter"]
    assert {v["containerId"] for v in before.values()}.isdisjoint(v["containerId"] for v in after.values())
    assert before["database"]["namedVolumes"] == after["database"]["namedVolumes"]
    assert set(recovery["previousWorkflow"]["jobs"].values()).isdisjoint(verification["workflow"]["jobs"].values())
    types = {"artifact": "artifact.generate", "import": "model.import",
             "review": "operation.review", "validation": "model.validate"}
    for results, workflow in ((verification["previewResults"], verification["workflow"]),
                              (recovery["previousJobsAfterRecreation"], recovery["previousWorkflow"])):
        assert set(results) == set(types)
        for kind, result in results.items():
            assert result["id"] == workflow["jobs"][kind]
            assert result["jobType"] == types[kind] and result["status"] == "succeeded"
            assert result["attemptCount"] >= 1
    publication = verification["workflow"]["publication"]
    assert publication["status"] == "READY"
    assert publication["artifactManifestHash"] == records["installation"]["packageIdentity"]["manifestContentSha256"]
    paths = ["deployment/dogfood.py", "deployment/test_dogfood.py", "deployment/compose.dogfood.yaml",
             "deployment/dogfood/LocalDogfoodControl.java", "deployment/dogfood/jobs.titan.graphql.yaml",
             "deployment/dogfood/job.graphql"]
    sources = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in paths}
    suite = unittest.defaultTestLoader.discover(str(ROOT / "deployment"), pattern="test_dogfood.py")
    result = unittest.TextTestRunner(stream=io.StringIO(), verbosity=0).run(suite)
    assert result.wasSuccessful()
    released = json.loads((ROOT / "docs/release-evidence/m7-publication.json").read_text())
    artifacts = []
    for asset in released["downloadedAssets"]:
        if not asset["name"].endswith(("-database-http-frontend.zip", "-control-job-worker.zip")):
            continue
        archive = ROOT / "build/distributions" / asset["name"]
        digest = "sha256:" + hashlib.sha256(archive.read_bytes()).hexdigest()
        assert digest == asset["digest"]
        artifacts.append({"name": asset["name"], "digest": digest,
                          "matchesPublishedM7": True, "bytes": archive.stat().st_size})
    assert artifacts
    evidence = {"schemaVersion": "titan.graphql.dogfood-evidence.v1",
                "derivedAt": datetime.now(timezone.utc).isoformat(),
                "derivation": "python3 -B docs/release-evidence/derive-dogfood.py deployment/.dogfood",
                "sourceSha256": sources, "execution": records, "runtimeArtifacts": artifacts,
                "clientTests": {"command": "python3 -B -m unittest discover -s deployment -p 'test_dogfood.py' -v",
                                "testsRun": result.testsRun, "failures": len(result.failures),
                                "errors": len(result.errors), "skipped": len(result.skipped)}}
    encoded = json.dumps(evidence, indent=2, sort_keys=True)
    secrets = dict(line.split("=", 1) for line in (directory / ".env").read_text().splitlines())
    assert all(secrets[name] not in encoded for name in ("DOGFOOD_DATABASE_PASSWORD", "DOGFOOD_ADMIN_TOKEN"))
    assert "/home/" not in encoded and "/Users/" not in encoded
    return encoded


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("state_directory", type=Path)
    print(collect(parser.parse_args().state_directory))
