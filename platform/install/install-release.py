#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import re
import shutil
import tarfile
import tempfile
from pathlib import Path, PurePosixPath
from typing import Any

ENVIRONMENT_RE = re.compile(r"^[a-z][a-z0-9-]{0,31}$")
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
ROLES = {"cloud", "paid"}
FROZEN_CANDIDATE_RELEASE_ID = "rel-v1-b5ba70c23b9f529ac8228a7b00b4faa4"
FROZEN_CANDIDATE_BUNDLE_DIGEST = "sha256:c9df14dcd1dbc24b31b926d3778bef2e208b59824c78f24292608284f3579892"


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


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8") + b"\n"


def sha256_bytes(content: bytes) -> str:
    return "sha256:" + hashlib.sha256(content).hexdigest()


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


def validate_bundle(manifest: dict[str, Any], bundle_manifest_path: Path, bundle_path: Path) -> tuple[dict[str, Any], bytes]:
    bundle_manifest = load_json(bundle_manifest_path)
    required = {"schemaVersion", "format", "digest", "sizeBytes", "files"}
    if not isinstance(bundle_manifest, dict) or set(bundle_manifest) != required:
        fail("deployment bundle manifest keys do not match schema v1")
    if bundle_manifest["schemaVersion"] != 1 or bundle_manifest["format"] != "tar":
        fail("invalid deployment bundle manifest")
    if not isinstance(bundle_manifest["files"], list) or not bundle_manifest["files"]:
        fail("deployment bundle manifest contains no files")

    release_bundle = manifest["deploymentBundle"]
    expected_release_bundle = {
        "format": bundle_manifest["format"],
        "digest": bundle_manifest["digest"],
        "sizeBytes": bundle_manifest["sizeBytes"],
        "fileCount": len(bundle_manifest["files"]),
    }
    if release_bundle != expected_release_bundle:
        fail("deployment bundle manifest does not match release identity")
    frozen_legacy_bundle = (
        manifest["releaseId"] == FROZEN_CANDIDATE_RELEASE_ID
        and release_bundle["digest"] == FROZEN_CANDIDATE_BUNDLE_DIGEST
    )

    try:
        bundle_bytes = bundle_path.read_bytes()
    except OSError as exc:
        fail(f"cannot read deployment bundle {bundle_path}: {exc}")
    if len(bundle_bytes) != bundle_manifest["sizeBytes"]:
        fail("deployment bundle byte size mismatch")
    if sha256_bytes(bundle_bytes) != bundle_manifest["digest"]:
        fail("deployment bundle digest mismatch")

    seen: set[str] = set()
    expected_file_keys = (
        {"path", "digest", "sizeBytes"}
        if frozen_legacy_bundle
        else {"path", "digest", "sizeBytes", "mode"}
    )
    for item in bundle_manifest["files"]:
        if not isinstance(item, dict) or set(item) != expected_file_keys:
            fail("invalid deployment bundle file entry")
        name = item["path"]
        if not isinstance(name, str) or not name or name.startswith("/"):
            fail("unsafe deployment bundle path")
        parts = PurePosixPath(name).parts
        if "." in parts or ".." in parts:
            fail(f"unsafe deployment bundle path: {name}")
        if name in seen:
            fail(f"duplicate deployment bundle path: {name}")
        seen.add(name)
        if not isinstance(item["digest"], str) or not DIGEST_RE.fullmatch(item["digest"]):
            fail(f"invalid deployment bundle file digest: {name}")
        if not isinstance(item["sizeBytes"], int) or isinstance(item["sizeBytes"], bool) or item["sizeBytes"] < 0:
            fail(f"invalid deployment bundle file size: {name}")
        expected_mode = 0o644 if frozen_legacy_bundle else item["mode"]
        if type(expected_mode) is not int or expected_mode not in {0o644, 0o755}:
            fail(f"invalid deployment bundle file mode: {name}")

    try:
        with tarfile.open(bundle_path, "r") as tar:
            members = tar.getmembers()
            if len(members) != len(bundle_manifest["files"]):
                fail("deployment bundle tar member count mismatch")
            for member, expected in zip(members, bundle_manifest["files"]):
                if not member.isfile():
                    fail(f"deployment bundle tar contains non-file member: {member.name}")
                if member.name != expected["path"]:
                    fail(f"deployment bundle tar order/path mismatch: {member.name}")
                expected_mode = 0o644 if frozen_legacy_bundle else expected["mode"]
                if member.mode != expected_mode:
                    fail(f"deployment bundle tar mode mismatch: {member.name}")
                source = tar.extractfile(member)
                if source is None:
                    fail(f"cannot read deployment bundle member: {member.name}")
                content = source.read()
                if len(content) != expected["sizeBytes"]:
                    fail(f"deployment bundle file size mismatch: {member.name}")
                if sha256_bytes(content) != expected["digest"]:
                    fail(f"deployment bundle file digest mismatch: {member.name}")
    except (OSError, tarfile.TarError) as exc:
        fail(f"cannot validate deployment bundle tar: {exc}")

    return bundle_manifest, bundle_bytes


def verify_bundle_tree(bundle_dir: Path, bundle_manifest: dict[str, Any]) -> bool:
    expected_paths = {item["path"] for item in bundle_manifest["files"]}
    try:
        actual_files = {
            path.relative_to(bundle_dir).as_posix()
            for path in bundle_dir.rglob("*")
            if path.is_file()
        }
        if actual_files != expected_paths:
            return False
        if any(path.is_symlink() for path in bundle_dir.rglob("*")):
            return False
        for item in bundle_manifest["files"]:
            path = bundle_dir / item["path"]
            content = path.read_bytes()
            if len(content) != item["sizeBytes"] or sha256_bytes(content) != item["digest"]:
                return False
            declared_mode = item.get("mode", 0o644)
            expected_mode = 0o555 if declared_mode & 0o111 else 0o444
            if (path.stat().st_mode & 0o777) != expected_mode:
                return False
        for path in [bundle_dir, *[p for p in bundle_dir.rglob("*") if p.is_dir()]]:
            if (path.stat().st_mode & 0o777) != 0o555:
                return False
    except OSError:
        return False
    return True


def existing_matches(
    target: Path,
    manifest_bytes: bytes,
    images_bytes: bytes,
    bundle_manifest_bytes: bytes,
    bundle_bytes: bytes,
    bundle_manifest: dict[str, Any],
) -> bool:
    expected = {"manifest.json", "images.env", "deployment-bundle.json", "deployment-bundle.tar", "bundle"}
    try:
        actual = {entry.name for entry in target.iterdir()}
    except OSError as exc:
        fail(f"cannot inspect installed release {target}: {exc}")
    if actual != expected:
        return False
    try:
        return (
            target.joinpath("manifest.json").read_bytes() == manifest_bytes
            and target.joinpath("images.env").read_bytes() == images_bytes
            and target.joinpath("deployment-bundle.json").read_bytes() == bundle_manifest_bytes
            and target.joinpath("deployment-bundle.tar").read_bytes() == bundle_bytes
            and verify_bundle_tree(target / "bundle", bundle_manifest)
        )
    except OSError:
        return False


def install_bundle_tree(tmp: Path, bundle_path: Path, bundle_manifest: dict[str, Any]) -> None:
    bundle_dir = tmp / "bundle"
    bundle_dir.mkdir(mode=0o700)
    with tarfile.open(bundle_path, "r") as tar:
        for member, expected in zip(tar.getmembers(), bundle_manifest["files"]):
            relative = PurePosixPath(expected["path"])
            target = bundle_dir.joinpath(*relative.parts)
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
            source = tar.extractfile(member)
            if source is None:
                fail(f"cannot extract deployment bundle member: {member.name}")
            declared_mode = expected.get("mode", member.mode)
            mode = 0o555 if declared_mode & 0o111 else 0o444
            write_file(target, source.read(), mode)

    directories = sorted(
        [path for path in bundle_dir.rglob("*") if path.is_dir()],
        key=lambda path: len(path.parts),
        reverse=True,
    )
    for directory in directories:
        os.chmod(directory, 0o555)
        fsync_dir(directory)
    os.chmod(bundle_dir, 0o555)
    fsync_dir(bundle_dir)


def main() -> None:
    parser = argparse.ArgumentParser(description="Install an immutable autonomous Trinyx release without activating it")
    parser.add_argument("--role", required=True, choices=sorted(ROLES))
    parser.add_argument("--environment", required=True)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--bundle-manifest", required=True, type=Path)
    parser.add_argument("--bundle", required=True, type=Path)
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
    bundle_manifest, bundle_bytes = validate_bundle(manifest, args.bundle_manifest, args.bundle)

    release_id = manifest["releaseId"]
    manifest_bytes = canonical_manifest(module, manifest)
    images_bytes = role_env(manifest, args.role)
    bundle_manifest_bytes = canonical_json(bundle_manifest)

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
        if not target.is_dir() or not existing_matches(
            target, manifest_bytes, images_bytes, bundle_manifest_bytes, bundle_bytes, bundle_manifest
        ):
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
        write_file(tmp / "deployment-bundle.json", bundle_manifest_bytes, 0o444)
        write_file(tmp / "deployment-bundle.tar", bundle_bytes, 0o444)
        install_bundle_tree(tmp, args.bundle, bundle_manifest)
        fsync_dir(tmp)
        os.chmod(tmp, 0o555)
        os.rename(tmp, target)
        fsync_dir(releases)
    except Exception:
        if tmp.exists():
            os.chmod(tmp, 0o700)
            for path in tmp.rglob("*"):
                try:
                    os.chmod(path, 0o700 if path.is_dir() else 0o600)
                except OSError:
                    pass
            shutil.rmtree(tmp, ignore_errors=True)
        raise

    if not existing_matches(target, manifest_bytes, images_bytes, bundle_manifest_bytes, bundle_bytes, bundle_manifest):
        fail("post-install release verification failed")
    if (target.stat().st_mode & 0o777) != 0o555:
        fail("installed release directory mode mismatch")
    for name in ("manifest.json", "images.env", "deployment-bundle.json", "deployment-bundle.tar"):
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
    print(f"RELEASE_BUNDLE_INSTALLED_OK digest={bundle_manifest['digest']} files={len(bundle_manifest['files'])}")
    print("RELEASE_ACTIVE_UNCHANGED=yes")


if __name__ == "__main__":
    main()
