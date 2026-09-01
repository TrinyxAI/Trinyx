#!/usr/bin/env python3
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import re
import shutil
import tempfile
from pathlib import Path
from typing import Any

ENVIRONMENT_RE = re.compile(r"^[a-z][a-z0-9-]{0,31}$")
ROLES = {"cloud", "paid"}


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"cannot read JSON {path}: {exc}")


def load_release_module(path: Path):
    spec = importlib.util.spec_from_file_location("trinyx_release", path)
    if spec is None or spec.loader is None:
        fail("cannot load release module")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate_complete_runtime(contract_path: Path, manifest: dict[str, Any]) -> None:
    contract = load_json(contract_path)
    if contract.get("schemaVersion") != 1 or not isinstance(contract.get("images"), list):
        fail("invalid runtime inventory contract")
    expected: dict[str, tuple[str, str, str]] = {}
    for item in contract["images"]:
        name = str(item.get("name", ""))
        binding = (str(item.get("role", "")), str(item.get("service", "")), str(item.get("environment", "")))
        if not name or name in expected:
            fail("duplicate/empty runtime contract image name")
        expected[name] = binding
    actual: dict[str, tuple[str, str, str]] = {}
    for item in manifest["images"]:
        name = item["name"]
        if name in actual:
            fail("duplicate image name")
        actual[name] = (item["role"], item["service"], item["environment"])
    missing = sorted(set(expected) - set(actual))
    extra = sorted(set(actual) - set(expected))
    mismatch = sorted(name for name in expected.keys() & actual.keys() if expected[name] != actual[name])
    if missing:
        fail("missing runtime images: " + ",".join(missing))
    if extra:
        fail("extra runtime images: " + ",".join(extra))
    if mismatch:
        fail("runtime binding mismatch: " + ",".join(mismatch))


def role_env(manifest: dict[str, Any], role: str) -> bytes:
    bindings: dict[str, str] = {}
    for item in manifest["images"]:
        if item["role"] not in {role, "shared"}:
            continue
        key = item["environment"]
        if key in bindings:
            fail(f"duplicate environment binding for role {role}: {key}")
        bindings[key] = item["immutableRef"]
    if not bindings:
        fail(f"release has no images for role {role}")
    return "".join(f"{key}={bindings[key]}\n" for key in sorted(bindings)).encode("utf-8")


def canonical_manifest(module, manifest: dict[str, Any]) -> bytes:
    return module.canonical_json(manifest) + b"\n"


def fsync_dir(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def write_file(path: Path, content: bytes, mode: int) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    fd = os.open(path, flags, mode)
    try:
        with os.fdopen(fd, "wb", closefd=False) as fh:
            fh.write(content)
            fh.flush()
            os.fsync(fh.fileno())
    finally:
        os.close(fd)
    os.chmod(path, mode)


def existing_matches(target: Path, manifest_bytes: bytes, images_bytes: bytes) -> bool:
    expected = {"manifest.json", "images.env"}
    try:
        actual = {entry.name for entry in target.iterdir()}
    except OSError as exc:
        fail(f"cannot inspect installed release {target}: {exc}")
    if actual != expected:
        return False
    try:
        return target.joinpath("manifest.json").read_bytes() == manifest_bytes and target.joinpath("images.env").read_bytes() == images_bytes
    except OSError:
        return False


def main() -> None:
    parser = argparse.ArgumentParser(description="Install an immutable Trinyx release without activating it")
    parser.add_argument("--role", required=True, choices=sorted(ROLES))
    parser.add_argument("--environment", required=True)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--contract", required=True, type=Path)
    parser.add_argument("--release-tool", required=True, type=Path)
    parser.add_argument("--root", type=Path, default=Path("/"))
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    if not ENVIRONMENT_RE.fullmatch(args.environment):
        fail("invalid environment")

    module = load_release_module(args.release_tool)
    manifest = module.validate_manifest(load_json(args.manifest))
    validate_complete_runtime(args.contract, manifest)

    release_id = manifest["releaseId"]
    manifest_bytes = canonical_manifest(module, manifest)
    images_bytes = role_env(manifest, args.role)

    base = args.root / "etc" / "trinyx" / args.environment / args.role
    releases = base / "releases"
    active = base / "active"
    target = releases / release_id

    active_before = None
    if active.is_symlink():
        active_before = os.readlink(active)
    elif active.exists():
        active_before = "<non-symlink>"

    if target.exists():
        if not target.is_dir() or not existing_matches(target, manifest_bytes, images_bytes):
            fail(f"immutable release collision: {target}")
        print(f"RELEASE_INSTALL_PLAN_OK role={args.role} environment={args.environment} release_id={release_id} changes=0")
        if args.apply:
            print(f"RELEASE_INSTALL_APPLY_OK role={args.role} environment={args.environment} release_id={release_id} changes=0")
        return

    print(f"PLAN action=INSTALL_RELEASE target={target}")
    print(f"RELEASE_INSTALL_PLAN_OK role={args.role} environment={args.environment} release_id={release_id} changes=1")
    if not args.apply:
        return

    releases.mkdir(parents=True, exist_ok=True, mode=0o755)
    os.chmod(releases, 0o755)
    tmp = Path(tempfile.mkdtemp(prefix=f".{release_id}.staging.", dir=releases))
    try:
        os.chmod(tmp, 0o700)
        write_file(tmp / "manifest.json", manifest_bytes, 0o444)
        write_file(tmp / "images.env", images_bytes, 0o444)
        fsync_dir(tmp)
        os.chmod(tmp, 0o555)
        os.rename(tmp, target)
        fsync_dir(releases)
    except Exception:
        if tmp.exists():
            os.chmod(tmp, 0o700)
            shutil.rmtree(tmp, ignore_errors=True)
        raise

    if not existing_matches(target, manifest_bytes, images_bytes):
        fail("post-install release verification failed")
    if (target.stat().st_mode & 0o777) != 0o555:
        fail("installed release directory mode mismatch")
    for name in ("manifest.json", "images.env"):
        if (target.joinpath(name).stat().st_mode & 0o777) != 0o444:
            fail(f"installed release file mode mismatch: {name}")

    active_after = None
    if active.is_symlink():
        active_after = os.readlink(active)
    elif active.exists():
        active_after = "<non-symlink>"
    if active_after != active_before:
        fail("release installation changed active deployment")

    print(f"RELEASE_INSTALL_APPLY_OK role={args.role} environment={args.environment} release_id={release_id} changes=1")
    print("RELEASE_ACTIVE_UNCHANGED=yes")


if __name__ == "__main__":
    main()
