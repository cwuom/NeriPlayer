#!/usr/bin/env python3
"""Keep Android packages and their published build identity together."""

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid
import zipfile


UUID_PATTERN = re.compile(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
RELEASE_BUILD_CONFIG = "app/build/generated/source/buildConfig/release/moe/ouom/neriplayer/BuildConfig.java"
RELEASE_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
TAIPEI = timezone(timedelta(hours=8))


def required_identity():
    raw_uuid = os.environ.get("NERI_BUILD_UUID", "").strip()
    if not UUID_PATTERN.fullmatch(raw_uuid):
        raise ValueError("NERI_BUILD_UUID must be a complete UUID; unknown identities cannot be published")
    raw_timestamp = os.environ.get("NERI_BUILD_TIMESTAMP", "").strip()
    if not re.fullmatch(r"[0-9]+", raw_timestamp) or not 0 < int(raw_timestamp) <= 9223372036854775807:
        raise ValueError("NERI_BUILD_TIMESTAMP must be positive epoch milliseconds")
    return str(uuid.UUID(raw_uuid)), int(raw_timestamp)


def prepare(arguments):
    timestamp = time.time_ns() // 1_000_000
    outputs = {
        "build_uuid": str(uuid.uuid4()),
        "build_timestamp": str(timestamp),
        "build_version_timestamp": datetime.fromtimestamp(timestamp / 1000, TAIPEI).strftime("%m%d%H%M"),
    }
    lines = "".join(f"{key}={value}\n" for key, value in outputs.items())
    if output := arguments.github_output or os.environ.get("GITHUB_OUTPUT"):
        with Path(output).open("a", encoding="utf-8") as stream:
            stream.write(lines)
    print(lines, end="")


def build_config_identity(path):
    content = path.read_text(encoding="utf-8")
    values = {}
    for name in ("BUILD_UUID", "VERSION_NAME"):
        match = re.search(rf'\b{name}\s*=\s*"([^"\n]*)"\s*;', content)
        if not match:
            raise ValueError(f"BuildConfig is missing {name}; target ref must support buildUuid/buildTimestamp")
        values[name] = match.group(1)
    timestamp = re.search(r"\bBUILD_TIMESTAMP\s*=\s*([0-9]+)L\s*;", content)
    if not timestamp:
        raise ValueError("BuildConfig is missing BUILD_TIMESTAMP")
    values["BUILD_TIMESTAMP"] = int(timestamp.group(1))
    return values


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def stage(arguments):
    build_uuid, timestamp = required_identity()
    config = build_config_identity(arguments.build_config)
    if config["BUILD_UUID"] != build_uuid or config["BUILD_TIMESTAMP"] != timestamp:
        raise ValueError("BuildConfig does not match the requested UUID/timestamp; target ref may not support injection")
    version_timestamp = os.environ.get("NERI_BUILD_VERSION_TIMESTAMP", "").strip()
    if version_timestamp and (not re.fullmatch(r"[0-9]{8}", version_timestamp)
                              or not config["VERSION_NAME"].endswith(f".{version_timestamp}")):
        raise ValueError("NERI_BUILD_VERSION_TIMESTAMP does not match BuildConfig VERSION_NAME")
    apk_files = sorted(arguments.apk_dir.rglob("*.apk"))
    if not apk_files:
        raise ValueError(f"No release APKs found in {arguments.apk_dir}")
    names = [path.name for path in apk_files]
    if len(set(names)) != len(names) or any(not re.fullmatch(r"[A-Za-z0-9._+-]+\.apk", name) for name in names):
        raise ValueError("Release APK filenames must be unique and safe for SHA256SUMS.txt")
    artifacts = []
    for path in apk_files:
        with zipfile.ZipFile(path) as archive:
            dex_files = [name for name in archive.namelist() if re.fullmatch(r"classes(?:[0-9]+)?\.dex", name)]
            if not any(build_uuid.encode("ascii") + b"\x00" in archive.read(name) for name in dex_files):
                raise ValueError(f"APK {path.name} does not contain the requested Build UUID")
            abis = sorted({match.group(1) for name in archive.namelist()
                           if (match := re.fullmatch(r"lib/([^/]+)/[^/]+\.so", name))})
        if not abis or not set(abis).issubset(RELEASE_ABIS):
            raise ValueError(f"APK {path.name} has missing or unknown native ABIs: {abis}")
        filename_abi = next((abi for abi in RELEASE_ABIS if path.name.endswith(f"-{abi}.apk")), None)
        if filename_abi and abis != [filename_abi]:
            raise ValueError(f"APK {path.name} filename ABI {filename_abi} does not match native ABIs {abis}")
        abi = abis[0] if len(abis) == 1 else "universal"
        artifacts.append({"name": path.name, "abi": abi, "abis": abis,
                          "size": path.stat().st_size, "sha256": sha256(path)})
    if arguments.expected_abis:
        expected = set(arguments.expected_abis.split(","))
        actual = {artifact["abi"] for artifact in artifacts}
        if actual != expected or len(artifacts) != len(expected) or any(len(artifact["abis"]) != 1 for artifact in artifacts):
            raise ValueError(f"Release ABI mismatch: expected {sorted(expected)}, produced {sorted(actual)}")
    signing_mode = os.environ.get("NERI_SIGNING_MODE", "unknown")
    if signing_mode not in {"signed", "unsigned", "unknown"}:
        raise ValueError("NERI_SIGNING_MODE must be signed, unsigned, or unknown")
    target_sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    run_id = os.environ.get("GITHUB_RUN_ID", "")
    attempt = os.environ.get("GITHUB_RUN_ATTEMPT", "1")
    workflow_run = f"https://github.com/{repository}/actions/runs/{run_id}/attempts/{attempt}" if repository and run_id else ""
    metadata = {
        "build_uuid": build_uuid,
        "build_timestamp": timestamp,
        "build_time": datetime.fromtimestamp(timestamp / 1000, timezone.utc).isoformat(),
        "build_version": config["VERSION_NAME"],
        "build_version_timestamp": version_timestamp or config["VERSION_NAME"].rsplit(".", 1)[-1],
        "target_sha": target_sha,
        "repository": repository,
        "workflow_run": workflow_run,
        "github_run_id": run_id,
        "github_run_attempt": attempt,
        "workflow_sha": os.environ.get("GITHUB_WORKFLOW_SHA", ""),
        "signing_mode": signing_mode,
        "artifacts": artifacts,
    }
    destination = arguments.output_dir
    if destination.exists() and any(destination.iterdir()):
        raise ValueError(f"Refusing to mix release artifacts with existing files in {destination}")
    destination.mkdir(parents=True, exist_ok=True)
    for path in apk_files:
        shutil.copyfile(path, destination / path.name)
    (destination / "build-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    (destination / "build-metadata.txt").write_text(
        "".join(f"{key}={value}\n" for key, value in metadata.items() if key != "artifacts"), encoding="utf-8",
    )
    checksums = "".join(f"{sha256(path)}  {path.name}\n" for path in sorted(destination.iterdir()))
    (destination / "SHA256SUMS.txt").write_text(checksums, encoding="utf-8")
    print(f"Verified {len(apk_files)} APK(s) for Build UUID {build_uuid}")


def notes(arguments):
    metadata = json.loads(arguments.metadata_file.read_text(encoding="utf-8"))
    if not UUID_PATTERN.fullmatch(metadata["build_uuid"]):
        raise ValueError("Build metadata must contain a complete UUID")
    lines = ["", "", "## Build metadata", "", f"- Build UUID: `{metadata['build_uuid']}`",
             f"- Build version: `{metadata['build_version']}`", f"- Target commit: `{metadata['target_sha']}`",
             f"- Build time: `{metadata['build_time']}`"]
    if metadata["workflow_run"]:
        lines.append(f"- Workflow run: {metadata['workflow_run']}")
    if metadata["signing_mode"] == "unsigned":
        lines.extend(["", "Unsigned test artifacts for workflow verification; these APKs are not installable releases."])
    lines.extend(["", "Download the APKs and metadata together, then verify them with `sha256sum -c SHA256SUMS.txt`.",
                  "The Build UUID in Settings > About must match this release and build-metadata.json.", ""])
    with arguments.notes_file.open("a", encoding="utf-8") as stream:
        stream.write("\n".join(lines))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare_parser = commands.add_parser("prepare")
    prepare_parser.add_argument("--github-output", type=Path)
    prepare_parser.set_defaults(handler=prepare)
    stage_parser = commands.add_parser("stage")
    stage_parser.add_argument("--apk-dir", type=Path, default=Path("app/build/outputs/apk/release"))
    stage_parser.add_argument("--output-dir", type=Path, default=Path("release-assets"))
    stage_parser.add_argument("--build-config", type=Path, default=Path(RELEASE_BUILD_CONFIG))
    stage_parser.add_argument("--expected-abis")
    stage_parser.set_defaults(handler=stage)
    notes_parser = commands.add_parser("notes")
    notes_parser.add_argument("--notes-file", type=Path, required=True)
    notes_parser.add_argument("--metadata-file", type=Path, default=Path("release-assets/build-metadata.json"))
    notes_parser.set_defaults(handler=notes)
    arguments = parser.parse_args()
    try:
        arguments.handler(arguments)
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
