#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
from pathlib import Path, PurePosixPath
from typing import Any

SOURCE_COMMIT = "aeb2a447ea7ce0436a60549713636225dfe1a2c1"
APPROVED_TRUSTED_OVERLAYS = {"docker/docker-compose.paid.runtime.yml"}


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"cannot read JSON {path}: {exc}")


def normalized_relative(value: Any) -> str:
    if not isinstance(value, str) or not value or value.startswith("/"):
        fail("historical bundle paths must be non-empty relative paths")
    if any(ord(char) < 0x20 or char == "\\" for char in value):
        fail(f"unsafe historical bundle path: {value}")
    path = PurePosixPath(value)
    if "." in path.parts or ".." in path.parts:
        fail(f"unsafe historical bundle path: {value}")
    normalized = path.as_posix()
    if normalized in {"", "."} or normalized != value:
        fail(f"historical bundle path is not canonical: {value}")
    return normalized


def path_list(value: Any, label: str) -> list[str]:
    if not isinstance(value, list) or not value:
        fail(f"{label} must be a non-empty list")
    result = [normalized_relative(item) for item in value]
    if len(result) != len(set(result)):
        fail(f"{label} contains duplicate paths")
    return result


def require_no_path_overlap(paths: list[str], label: str) -> None:
    for index, left in enumerate(paths):
        for right in paths[index + 1:]:
            if left.startswith(right + "/") or right.startswith(left + "/"):
                fail(f"{label} contains overlapping paths")


def reject_symlinked_repository_root(path: Path, label: str) -> None:
    current = Path(os.path.abspath(path))
    while True:
        if current.is_symlink():
            fail(f"{label} repository root may not traverse a symlink")
        if current.parent == current:
            return
        current = current.parent


def verify_historical_checkout(path: Path) -> Path:
    reject_symlinked_repository_root(path, "historical")
    repo = path.resolve()
    if not repo.is_dir():
        fail("historical repository must be a directory")
    try:
        head = subprocess.run(
            ["git", "-C", str(repo), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        status = subprocess.run(
            ["git", "-C", str(repo), "status", "--porcelain=v1", "--untracked-files=all", "--ignored"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError) as exc:
        fail(f"cannot verify historical repository checkout: {exc}")
    if head != SOURCE_COMMIT:
        fail("historical repository commit mismatch")
    if status:
        fail("historical repository is not clean")
    return repo


def verified_trusted_repo(path: Path) -> Path:
    reject_symlinked_repository_root(path, "trusted")
    repo = path.resolve()
    if not repo.is_dir():
        fail("trusted repository must be a directory")
    return repo


def source_path(root: Path, relative: str) -> Path:
    current = root
    for part in PurePosixPath(relative).parts:
        current = current / part
        if current.is_symlink():
            fail(f"historical bundle source path traverses a symlink: {relative}")
    return current


def safe_destination(destination: Path, historical_repo: Path) -> Path:
    destination = Path(os.path.abspath(destination))
    reject_symlinked_repository_root(destination.parent, "historical bundle destination")
    if destination.is_symlink() or destination.exists():
        fail("historical bundle destination already exists or is a symlink")
    parent = destination.parent.resolve()
    resolved = parent / destination.name
    try:
        resolved.relative_to(historical_repo)
    except ValueError:
        return resolved
    fail("historical bundle destination may not be inside the historical repository")


def copy_safe(source: Path, destination: Path) -> None:
    if source.is_symlink():
        fail(f"historical bundle source may not be a symlink: {source}")
    if source.is_file():
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        mode = source.stat().st_mode
        os.chmod(destination, 0o755 if mode & 0o111 else 0o644)
        return
    if source.is_dir():
        destination.mkdir(parents=True, exist_ok=False)
        for child in sorted(source.iterdir(), key=lambda item: item.name):
            copy_safe(child, destination / child.name)
        return
    fail(f"historical bundle source is missing or special: {source}")


def prepare(
    historical_repo: Path,
    trusted_repo: Path,
    source_contract: Path,
    bundle_contract: Path,
    destination: Path,
) -> None:
    source_doc = load_json(source_contract)
    required = {
        "schemaVersion",
        "historicalSourceCommit",
        "historicalPaths",
        "trustedBuilderOverlays",
    }
    if not isinstance(source_doc, dict) or set(source_doc) != required:
        fail("historical bundle source contract schema mismatch")
    if (
        type(source_doc["schemaVersion"]) is not int
        or source_doc["schemaVersion"] != 1
        or source_doc["historicalSourceCommit"] != SOURCE_COMMIT
    ):
        fail("historical bundle source identity mismatch")

    historical_paths = path_list(source_doc["historicalPaths"], "historicalPaths")
    trusted_overlays = path_list(source_doc["trustedBuilderOverlays"], "trustedBuilderOverlays")
    require_no_path_overlap(historical_paths, "historicalPaths")
    require_no_path_overlap(trusted_overlays, "trustedBuilderOverlays")
    if set(trusted_overlays) != APPROVED_TRUSTED_OVERLAYS:
        fail("unapproved historical bundle trusted overlay")
    require_no_path_overlap(historical_paths + trusted_overlays, "historical/trusted bundle sources")

    bundle_doc = load_json(bundle_contract)
    if (
        not isinstance(bundle_doc, dict)
        or set(bundle_doc) != {"schemaVersion", "paths"}
        or type(bundle_doc["schemaVersion"]) is not int
        or bundle_doc["schemaVersion"] != 1
    ):
        fail("deployment bundle file contract schema mismatch")
    bundle_paths = path_list(bundle_doc["paths"], "deployment bundle paths")
    if set(bundle_paths) != set(historical_paths) | set(trusted_overlays):
        fail("historical bundle source contract does not cover the deployment bundle contract")

    historical_repo = verify_historical_checkout(historical_repo)
    trusted_repo = verified_trusted_repo(trusted_repo)
    destination = safe_destination(destination, historical_repo)
    destination.mkdir(parents=True, mode=0o755)

    for relative in historical_paths:
        copy_safe(source_path(historical_repo, relative), destination / relative)

    for relative in trusted_overlays:
        historical_source = source_path(historical_repo, relative)
        if historical_source.exists() or historical_source.is_symlink():
            fail(f"trusted overlay would shadow historical source: {relative}")
        source = source_path(trusted_repo, relative)
        if source.is_symlink() or not source.is_file():
            fail(f"trusted overlay is missing or unsafe: {relative}")
        copy_safe(source, destination / relative)

    print(
        "HISTORICAL_BUNDLE_SOURCE_OK "
        f"source={SOURCE_COMMIT} historical_paths={len(historical_paths)} "
        f"trusted_overlays={len(trusted_overlays)}"
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Prepare the exact historical deployment inputs plus explicitly trusted platform overlays"
    )
    parser.add_argument("--historical-repo", required=True, type=Path)
    parser.add_argument("--trusted-repo", required=True, type=Path)
    parser.add_argument("--source-contract", required=True, type=Path)
    parser.add_argument("--bundle-contract", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    prepare(
        args.historical_repo,
        args.trusted_repo,
        args.source_contract,
        args.bundle_contract,
        args.out,
    )


if __name__ == "__main__":
    main()
