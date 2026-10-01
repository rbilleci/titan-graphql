import argparse
import hashlib
import json
import re
from collections import Counter
from html.parser import HTMLParser
from pathlib import Path
from xml.etree import ElementTree


class ProfileRows(HTMLParser):
    def __init__(self):
        super().__init__()
        self.rows = []
        self.row = None
        self.cell = None

    def handle_starttag(self, tag, attrs):
        if tag == "tr":
            self.row = []
        elif tag == "td" and self.row is not None:
            self.cell = []

    def handle_data(self, data):
        if self.cell is not None:
            self.cell.append(data)

    def handle_endtag(self, tag):
        if tag == "td" and self.cell is not None:
            self.row.append("".join(self.cell).strip())
            self.cell = None
        elif tag == "tr" and self.row is not None:
            if len(self.row) == 3 and self.row[0].startswith(":"):
                self.rows.append(dict(zip(("task", "duration", "outcome"), self.row)))
            self.row = None


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def collect(build, source_commit, profile_name=None):
    suites = []
    measurements = []
    for path in sorted((build / "test-results").glob("*/TEST-*.xml")):
        root = ElementTree.parse(path).getroot()
        suites.append({
            "artifact": path.relative_to(build).as_posix(),
            "sha256": digest(path),
            "name": root.get("name"),
            "timestamp": root.get("timestamp"),
            "timeSeconds": float(root.get("time")),
            **{key: int(root.get(key)) for key in ("tests", "skipped", "failures", "errors")},
            "cases": [case.get("name") for case in root.findall("testcase")]
            if path.parent.name != "test" else [],
        })
        for output in root.findall("system-out"):
            for line in (output.text or "").splitlines():
                if line.startswith(("DATABASE_REQUEST_MEASUREMENT ", "DATABASE_INSTALL_MEASUREMENT ",
                                    "GENERATED_DATABASE_CORPUS ")):
                    values = dict(re.findall(r"(\w+)=([^ ]+)", line))
                    measurements.append({
                        "artifact": path.relative_to(build).as_posix(),
                        "timestamp": root.get("timestamp"),
                        "kind": line.split()[0],
                        **{key: int(value) if value.isdigit() else value for key, value in values.items()},
                    })
    packages = []
    for path in sorted((build / "generated/proofs").glob("*/package/titan-graphql-package.json")):
        directory = path.parent
        manifest = json.loads((directory / "titan-artifact.json").read_text())
        inventory = json.loads((directory / "titan-object-inventory.json").read_text())
        files = sorted(p for p in directory.rglob("*") if p.is_file())
        identity = json.loads(path.read_text())
        packages.append({
            "directory": directory.relative_to(build).as_posix(),
            "identity": identity,
            "dialects": manifest["dialects"],
            "objectKinds": dict(Counter(o["kind"] for o in inventory["objects"])),
            "packageBytes": sum(p.stat().st_size for p in files),
            "sqlBytes": sum(p.stat().st_size for p in files if p.suffix == ".sql"),
            "identitySidecars": {p.name: p.read_text().strip() for p in files if p.suffix == ".sha256"},
            "fileCount": len(files),
            "fileInventorySha256": hashlib.sha256(json.dumps([
                {"path": p.relative_to(directory).as_posix(), "bytes": p.stat().st_size,
                 "sha256": digest(p)} for p in files
            ], sort_keys=True, separators=(",", ":")).encode()).hexdigest(),
        })
    profiles = []
    for path in sorted((build / "reports/profile").glob("*.html")):
        if profile_name is not None and path.name != profile_name:
            continue
        parser = ProfileRows()
        content = path.read_text()
        parser.feed(content)
        started = re.search(r"Started on: ([^<]+)", content)
        profiles.append({
            "artifact": path.relative_to(build).as_posix(),
            "sha256": digest(path),
            "startedLocalTime": started.group(1).strip() if started else None,
            "tasks": [row for row in parser.rows if "DatabaseEngine" in row["task"]],
        })
    if profile_name is not None and not profiles:
        raise ValueError("requested profile report not found: " + profile_name)
    return {"verifiedCodeCommit": source_commit, "suites": suites,
            "measurements": measurements, "packages": packages, "profiles": profiles}


if __name__ == "__main__":
    arguments = argparse.ArgumentParser()
    arguments.add_argument("build", type=Path)
    arguments.add_argument("source_commit")
    arguments.add_argument("--profile")
    options = arguments.parse_args()
    print(json.dumps(collect(options.build, options.source_commit, options.profile), indent=2, sort_keys=True))
