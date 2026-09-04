#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import tarfile
from pathlib import Path, PurePosixPath
from typing import Any

DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def sha256_bytes(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def normalized_relative(value: Any) -> str:
    if not isinstance(value, str) or not value or value.startswith("/"):
        fail("bundle paths must be non-empty relative paths")
    if any(ord(char) < 0x20 or char == "\\" for char in value):
        fail(f"unsafe bundle path: {value}")
    path = PurePosixPath(value)
    if "." in path.parts or ".." in path.parts:
        fail(f"unsafe bundle path: {value}")
    normalized = path.as_posix()
    if normalized in {"", "."} or normalized != value:
        fail(f"bundle path is not canonical: {value}")
    return normalized


def reject_symlinked_root(path: Path) -> None:
    current = Path(os.path.abspath(path))
    while True:
        if current.is_symlink():
            fail("bundle repository root may not traverse a symlink")
        if current.parent == current:
            return
        current = current.parent


def require_no_path_overlap(paths: list[str], label: str = "bundle paths") -> None:
    for index, left in enumerate(paths):
        for right in paths[index + 1:]:
            if left.startswith(right + "/") or right.startswith(left + "/"):
                fail(f"{label} contains overlapping paths")


def load_contract(path: Path) -> list[str]:
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"cannot read bundle file contract: {exc}")
    if (
        not isinstance(doc, dict)
        or set(doc) != {"schemaVersion", "paths"}
        or type(doc.get("schemaVersion")) is not int
        or doc["schemaVersion"] != 1
    ):
        fail("invalid deployment bundle file contract")
    paths = doc["paths"]
    if not isinstance(paths, list) or not paths:
        fail("deployment bundle file contract must contain paths")
    clean: list[str] = []
    for value in paths:
        normalized = normalized_relative(value)
        if normalized in clean:
            fail(f"duplicate bundle path: {normalized}")
        clean.append(normalized)
    require_no_path_overlap(clean)
    return clean


def collect_files(repo: Path, contract_paths: list[str]) -> list[Path]:
    files: set[Path] = set()
    for relative in contract_paths:
        path = repo / relative
        if path.is_symlink():
            fail(f"bundle contract path may not be a symlink: {relative}")
        if path.is_file():
            files.add(Path(relative))
            continue
        if path.is_dir():
            for child in path.rglob("*"):
                if child.is_symlink():
                    fail(f"bundle may not contain symlink: {child.relative_to(repo)}")
                if child.is_file():
                    files.add(child.relative_to(repo))
                elif not child.is_dir():
                    fail(f"bundle may not contain special file: {child.relative_to(repo)}")
            continue
        if path.exists():
            fail(f"bundle contract path is a special file: {relative}")
        fail(f"bundle contract path does not exist: {relative}")
    if not files:
        fail("deployment bundle contains no files")
    return sorted(files, key=lambda p: p.as_posix())


def normalized_mode(source: Path) -> int:
    return 0o755 if source.stat().st_mode & 0o111 else 0o644


def build_tar(repo: Path, files: list[Path]) -> bytes:
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w", format=tarfile.GNU_FORMAT) as tar:
        for relative in files:
            source = repo / relative
            data = source.read_bytes()
            info = tarfile.TarInfo(relative.as_posix())
            info.size = len(data)
            info.mtime = 0
            info.uid = 0
            info.gid = 0
            info.uname = ""
            info.gname = ""
            info.mode = normalized_mode(source)
            tar.addfile(info, io.BytesIO(data))
    return buffer.getvalue()


def main() -> None:
    parser = argparse.ArgumentParser(description="Build deterministic Trinyx deployment bundle")
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--contract", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--manifest-out", type=Path, required=True)
    args = parser.parse_args()

    reject_symlinked_root(args.repo)
    repo = args.repo.resolve()
    if not repo.is_dir() or repo.is_symlink():
        fail("--repo must be a directory")
    files = collect_files(repo, load_contract(args.contract))

    entries: list[dict[str, Any]] = []
    for relative in files:
        data = (repo / relative).read_bytes()
        entries.append({
            "path": relative.as_posix(),
            "digest": sha256_bytes(data),
            "sizeBytes": len(data),
            "mode": normalized_mode(repo / relative),
        })

    payload = build_tar(repo, files)
    digest = sha256_bytes(payload)
    if not DIGEST_RE.fullmatch(digest):
        fail("internal deployment bundle digest error")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(payload)
    manifest = {"schemaVersion": 1, "format": "tar", "digest": digest, "sizeBytes": len(payload), "files": entries}
    args.manifest_out.parent.mkdir(parents=True, exist_ok=True)
    args.manifest_out.write_bytes(canonical_json(manifest) + b"\n")
    print(f"DEPLOYMENT_BUNDLE_BUILD_OK digest={digest} files={len(entries)} size={len(payload)}")


if __name__ == "__main__":
    main()
