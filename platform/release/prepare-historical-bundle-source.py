#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import shutil
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
    path = PurePosixPath(value)
    if "." in path.parts or ".." in path.parts:
        fail(f"unsafe historical bundle path: {value}")
    normalized = path.as_posix().rstrip("/")
    if not normalized:
        fail("historical bundle path is empty after normalization")
    return normalized


def path_list(value: Any, label: str) -> list[str]:
    if not isinstance(value, list) or not value:
        fail(f"{label} must be a non-empty list")
    result = [normalized_relative(item) for item in value]
    if len(result) != len(set(result)):
        fail(f"{label} contains duplicate paths")
    return result


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
    if source_doc["schemaVersion"] != 1 or source_doc["historicalSourceCommit"] != SOURCE_COMMIT:
        fail("historical bundle source identity mismatch")

    historical_paths = path_list(source_doc["historicalPaths"], "historicalPaths")
    trusted_overlays = path_list(source_doc["trustedBuilderOverlays"], "trustedBuilderOverlays")
    if set(trusted_overlays) != APPROVED_TRUSTED_OVERLAYS:
        fail("unapproved historical bundle trusted overlay")
    if set(historical_paths) & set(trusted_overlays):
        fail("historical and trusted bundle sources overlap")

    bundle_doc = load_json(bundle_contract)
    if (
        not isinstance(bundle_doc, dict)
        or set(bundle_doc) != {"schemaVersion", "paths"}
        or bundle_doc["schemaVersion"] != 1
    ):
        fail("deployment bundle file contract schema mismatch")
    bundle_paths = path_list(bundle_doc["paths"], "deployment bundle paths")
    if set(bundle_paths) != set(historical_paths) | set(trusted_overlays):
        fail("historical bundle source contract does not cover the deployment bundle contract")

    historical_repo = historical_repo.resolve()
    trusted_repo = trusted_repo.resolve()
    if not historical_repo.is_dir() or not trusted_repo.is_dir():
        fail("historical and trusted repositories must be directories")
    if destination.exists():
        fail("historical bundle destination already exists")
    destination.mkdir(parents=True, mode=0o755)

    for relative in historical_paths:
        copy_safe(historical_repo / relative, destination / relative)

    for relative in trusted_overlays:
        if (historical_repo / relative).exists() or (historical_repo / relative).is_symlink():
            fail(f"trusted overlay would shadow historical source: {relative}")
        source = trusted_repo / relative
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
