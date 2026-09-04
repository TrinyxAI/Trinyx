#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import shutil
import stat
import subprocess
import re
from pathlib import Path, PurePosixPath
from typing import Any

SOURCE_COMMIT = "aeb2a447ea7ce0436a60549713636225dfe1a2c1"
APPROVED_TRUSTED_OVERLAYS = {"docker/docker-compose.paid.runtime.yml"}
APPROVED_ENVIRONMENT_CONFIGS = {
    "platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/paid.override.yml",
}
SHA_RE = re.compile(r"^[0-9a-f]{40}$")


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


def verified_trusted_repo(path: Path, expected_commit: str | None = None) -> Path:
    reject_symlinked_repository_root(path, "trusted")
    repo = path.resolve()
    if not repo.is_dir():
        fail("trusted repository must be a directory")
    if expected_commit is None:
        return repo
    if not isinstance(expected_commit, str) or not SHA_RE.fullmatch(expected_commit):
        fail("trusted repository commit is invalid")
    try:
        head = subprocess.run(
            ["git", "-C", str(repo), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        for command in (
            ["git", "-C", str(repo), "diff", "--no-ext-diff", "--quiet"],
            ["git", "-C", str(repo), "diff", "--cached", "--no-ext-diff", "--quiet"],
        ):
            result = subprocess.run(command, capture_output=True, text=True)
            if result.returncode not in {0, 1}:
                raise subprocess.CalledProcessError(result.returncode, command, result.stdout, result.stderr)
            if result.returncode:
                fail("trusted repository tracked content is not clean")
    except (OSError, subprocess.CalledProcessError) as exc:
        fail(f"cannot verify trusted repository checkout: {exc}")
    if head != expected_commit:
        fail("trusted repository commit mismatch")
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


def read_regular_file_no_follow(source: Path) -> tuple[bytes, int]:
    try:
        before = os.lstat(source)
    except OSError as exc:
        fail(f"cannot stat historical bundle source: {source}: {exc}")
    if not stat.S_ISREG(before.st_mode):
        fail(f"historical bundle source is missing or special: {source}")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(source, flags)
        try:
            opened = os.fstat(fd)
            if (
                not stat.S_ISREG(opened.st_mode)
                or opened.st_dev != before.st_dev
                or opened.st_ino != before.st_ino
            ):
                fail(f"historical bundle source changed while opening: {source}")
            chunks: list[bytes] = []
            while True:
                chunk = os.read(fd, 1024 * 1024)
                if not chunk:
                    break
                chunks.append(chunk)
            after = os.fstat(fd)
        finally:
            os.close(fd)
    except OSError as exc:
        fail(f"cannot safely read historical bundle source: {source}: {exc}")
    if (
        after.st_dev != opened.st_dev
        or after.st_ino != opened.st_ino
        or after.st_size != opened.st_size
        or after.st_mtime_ns != opened.st_mtime_ns
    ):
        fail(f"historical bundle source changed while reading: {source}")
    return b"".join(chunks), opened.st_mode


def copy_safe(source: Path, destination: Path) -> None:
    try:
        source_mode = os.lstat(source).st_mode
    except OSError as exc:
        fail(f"cannot stat historical bundle source: {source}: {exc}")
    if stat.S_ISLNK(source_mode):
        fail(f"historical bundle source may not be a symlink: {source}")
    if stat.S_ISREG(source_mode):
        content, opened_mode = read_regular_file_no_follow(source)
        destination.parent.mkdir(parents=True, exist_ok=True)
        try:
            flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
            fd = os.open(destination, flags, 0o600)
            with os.fdopen(fd, "wb") as output:
                output.write(content)
        except OSError as exc:
            fail(f"cannot safely write historical bundle destination: {destination}: {exc}")
        os.chmod(destination, 0o755 if opened_mode & 0o111 else 0o644)
        return
    if stat.S_ISDIR(source_mode):
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
    trusted_commit: str | None = None,
    approved_environment_config: str | None = None,
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
    trusted_repo = verified_trusted_repo(trusted_repo, trusted_commit)
    if approved_environment_config is not None:
        relative = normalized_relative(approved_environment_config)
        if relative not in APPROVED_ENVIRONMENT_CONFIGS:
            fail("unapproved historical environment configuration")
        config = source_path(trusted_repo, relative)
        content, _ = read_regular_file_no_follow(config)
        print(
            "HISTORICAL_TRUSTED_ENVIRONMENT_CONFIG_OK "
            f"path={relative} sha256={__import__('hashlib').sha256(content).hexdigest()}"
        )
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

    # Re-validate both authenticated roots after all reads/copies. The historical
    # checkout must still be the exact clean aeb2 tree; the trusted builder must
    # still be the caller-provided immutable workflow commit with no tracked drift.
    verify_historical_checkout(historical_repo)
    verified_trusted_repo(trusted_repo, trusted_commit)

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
    parser.add_argument("--trusted-commit")
    parser.add_argument("--approved-environment-config")
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    prepare(
        args.historical_repo,
        args.trusted_repo,
        args.source_contract,
        args.bundle_contract,
        args.out,
        args.trusted_commit,
        args.approved_environment_config,
    )


if __name__ == "__main__":
    main()
