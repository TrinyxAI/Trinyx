#!/usr/bin/env python3
"""Reusable O6-O12 release, runtime and platform invariants.

The module intentionally uses only the Python standard library so the same
checks can run in CI, on an EC2 host, after activation and during rollback.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import tarfile
from datetime import datetime
from pathlib import Path, PurePosixPath
from typing import Any, Iterable

RELEASE_RE = re.compile(r"^rel-v1-[0-9a-f]{32}$")
SHA_RE = re.compile(r"^[0-9a-f]{40}$")
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
RFC3339_UTC_RE = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
ENV_RE = re.compile(r"^[A-Z][A-Z0-9_]*$")
SERVICE_RE = re.compile(r"^[a-z0-9][a-z0-9_.-]{0,127}$")
FROZEN_CANDIDATE_RELEASE_ID = "rel-v1-b5ba70c23b9f529ac8228a7b00b4faa4"
FROZEN_CANDIDATE_BUNDLE_DIGEST = "sha256:c9df14dcd1dbc24b31b926d3778bef2e208b59824c78f24292608284f3579892"
SECRET_VALUE_PATTERNS = (
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(r"AKIA[0-9A-Z]{16}"),
    re.compile(r"gh[opsu]_[A-Za-z0-9_]{20,}"),
    re.compile(r"(?i)(?:password|secret|token|credential)\s*[=:]\s*\S+"),
)
DEPLOYMENT_KEYS = {
    "schemaVersion",
    "deploymentId",
    "environment",
    "releaseId",
    "environmentConfigRevision",
    "controlPlaneCommit",
    "previousCloudRelease",
    "previousPaidRelease",
    "state",
    "createdAt",
    "startedAt",
    "completedAt",
    "failure",
    "rollbackResult",
    "history",
}
DEPLOYMENT_STATES = {
    "CREATED",
    "PREFLIGHT",
    "READY",
    "MIGRATING",
    "ACTIVATING",
    "HEALTH_CHECKING",
    "SUCCESS",
    "ROLLING_BACK",
    "ROLLED_BACK",
    "FAILED",
    "ROLLBACK_FAILED",
    "MANUAL_RECOVERY_REQUIRED",
}


class InvariantError(RuntimeError):
    """An invariant failed before or after a runtime mutation."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise InvariantError(message)


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def sha256_bytes(content: bytes) -> str:
    return "sha256:" + hashlib.sha256(content).hexdigest()


def require_utc_timestamp(value: Any, label: str) -> None:
    require(
        isinstance(value, str) and RFC3339_UTC_RE.fullmatch(value) is not None,
        f"{label} is not strict UTC RFC3339",
    )
    try:
        datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as exc:
        raise InvariantError(f"{label} is invalid") from exc


def _no_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_no_duplicate_keys)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        raise InvariantError(f"invalid JSON {path}: {exc}") from exc


def environment_config_digest(base: Path, role: str) -> str:
    """Hash only reviewed, non-secret materialized config inputs used by the host plan."""
    require(role in {"cloud", "paid"}, "invalid role for environment config digest")
    plan_path = base / "config" / "deployment-plan.json"
    plan = read_json(plan_path)
    require(
        isinstance(plan, dict)
        and type(plan.get("schemaVersion")) is int
        and plan["schemaVersion"] == 1
        and plan.get("role") == role
        and isinstance(plan.get("requiredFiles"), list),
        "invalid deployment plan for environment config digest",
    )
    relative_paths = ["config/deployment-plan.json", *plan["requiredFiles"]]
    require(len(relative_paths) == len(set(relative_paths)), "duplicate non-secret config input")
    entries: list[dict[str, str]] = []
    for relative in sorted(relative_paths):
        require(
            isinstance(relative, str)
            and relative.startswith("config/")
            and not Path(relative).is_absolute()
            and ".." not in Path(relative).parts,
            "unsafe non-secret config input",
        )
        path = base / relative
        require(path.is_file() and not path.is_symlink(), f"missing/unsafe non-secret config input: {relative}")
        entries.append({"path": relative, "digest": sha256_bytes(path.read_bytes())})
    return sha256_bytes(canonical_json({"schemaVersion": 1, "role": role, "files": entries}))


def identity_payload(manifest: dict[str, Any]) -> dict[str, Any]:
    return {
        "schemaVersion": manifest["schemaVersion"],
        "sourceCommit": manifest["sourceCommit"],
        "platformCommit": manifest["platformCommit"],
        "deploymentBundle": manifest["deploymentBundle"],
        "images": manifest["images"],
    }


def calculated_release_id(manifest: dict[str, Any]) -> str:
    return "rel-v1-" + hashlib.sha256(canonical_json(identity_payload(manifest))).hexdigest()[:32]


def assert_no_secret_material(value: Any, label: str = "metadata") -> None:
    serialized = json.dumps(value, sort_keys=True, ensure_ascii=False)
    for pattern in SECRET_VALUE_PATTERNS:
        require(not pattern.search(serialized), f"secret-like material is forbidden in {label}")


def validate_release_manifest(manifest: Any) -> dict[str, Any]:
    require(isinstance(manifest, dict), "release manifest must be an object")
    required = {
        "schemaVersion",
        "releaseId",
        "sourceCommit",
        "sourceRef",
        "platformCommit",
        "createdAt",
        "deploymentBundle",
        "images",
    }
    require(set(manifest) == required, "release manifest keys do not match schema v1")
    require(type(manifest["schemaVersion"]) is int and manifest["schemaVersion"] == 1, "unsupported release schema")
    require(isinstance(manifest["releaseId"], str) and RELEASE_RE.fullmatch(manifest["releaseId"]), "bad release ID")
    require(isinstance(manifest["sourceCommit"], str) and SHA_RE.fullmatch(manifest["sourceCommit"]), "bad source commit")
    require(isinstance(manifest["platformCommit"], str) and SHA_RE.fullmatch(manifest["platformCommit"]), "bad platform commit")
    require(isinstance(manifest["sourceRef"], str) and 0 < len(manifest["sourceRef"]) <= 255, "bad source ref")
    require_utc_timestamp(manifest["createdAt"], "release createdAt")
    bundle = manifest["deploymentBundle"]
    require(isinstance(bundle, dict) and set(bundle) == {"format", "digest", "sizeBytes", "fileCount"}, "bad bundle identity")
    require(bundle["format"] == "tar", "bundle must be tar")
    require(isinstance(bundle["digest"], str) and DIGEST_RE.fullmatch(bundle["digest"]), "bad bundle digest")
    require(type(bundle["sizeBytes"]) is int and bundle["sizeBytes"] > 0, "bad bundle size")
    require(type(bundle["fileCount"]) is int and bundle["fileCount"] > 0, "bad bundle file count")
    images = manifest["images"]
    require(isinstance(images, list) and len(images) == 28, "runtime inventory must contain exactly 28 images")
    seen_names: set[str] = set()
    seen_bindings: set[tuple[str, str]] = set()
    counts = {"cloud": 0, "paid": 0}
    exact_fields = {"name", "role", "service", "package", "environment", "digest", "immutableRef"}
    for image in images:
        require(isinstance(image, dict) and set(image) == exact_fields, "bad image entry schema")
        require(all(isinstance(image[key], str) for key in exact_fields), "runtime image fields must be strings")
        role = image["role"]
        require(role in counts, f"unsupported runtime role: {role}")
        require(SERVICE_RE.fullmatch(image["name"]) is not None, "bad image name")
        require(SERVICE_RE.fullmatch(image["service"]) is not None, "bad service name")
        require(ENV_RE.fullmatch(image["environment"]) is not None, "bad image environment binding")
        require(DIGEST_RE.fullmatch(image["digest"]) is not None, "runtime image is not digest pinned")
        expected = f"{image['package']}@{image['digest']}"
        require(image["immutableRef"] == expected, "image binding/digest mismatch")
        require(":latest" not in expected, "mutable latest image is forbidden")
        require(image["name"] not in seen_names, "duplicate image name")
        binding = (role, image["environment"])
        require(binding not in seen_bindings, "duplicate image binding")
        seen_names.add(image["name"])
        seen_bindings.add(binding)
        counts[role] += 1
    require(counts == {"cloud": 20, "paid": 8}, f"runtime inventory must be 20 Cloud/8 Paid, got {counts}")
    require(calculated_release_id(manifest) == manifest["releaseId"], "release ID content hash mismatch")
    assert_no_secret_material(manifest, "release manifest")
    return manifest


def parse_images_env(path: Path) -> dict[str, str]:
    bindings: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise InvariantError(f"cannot read images file: {exc}") from exc
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        require("=" in line, "invalid images.env line")
        name, value = line.split("=", 1)
        require(ENV_RE.fullmatch(name) is not None and name not in bindings, "invalid/duplicate images.env binding")
        require("@sha256:" in value and DIGEST_RE.fullmatch("sha256:" + value.rsplit("@sha256:", 1)[1]) is not None, "mutable image in images.env")
        require(":latest" not in value, "latest image in images.env")
        bindings[name] = value
    return bindings


def _bundle_path_is_canonical(value: Any) -> bool:
    if not isinstance(value, str) or not value or value == "." or value.startswith("/") or "\\" in value:
        return False
    if any(ord(char) < 0x20 for char in value):
        return False
    path = PurePosixPath(value)
    return "." not in path.parts and ".." not in path.parts and path.as_posix() == value


def _bundle_directories(paths: set[str]) -> set[str]:
    return {
        parent.as_posix()
        for name in paths
        for parent in PurePosixPath(name).parents
        if parent.as_posix() != "."
    }


def _validate_installed_bundle_tree(root: Path, expected_paths: set[str]) -> None:
    require(root.is_dir() and not root.is_symlink(), "installed bundle root missing or unsafe")
    files: set[str] = set()
    directories: set[str] = set()
    try:
        for path in root.rglob("*"):
            relative = path.relative_to(root).as_posix()
            info = os.lstat(path)
            require(not stat.S_ISLNK(info.st_mode), "installed bundle contains symlink")
            if stat.S_ISREG(info.st_mode):
                files.add(relative)
            elif stat.S_ISDIR(info.st_mode):
                directories.add(relative)
            else:
                require(False, "installed bundle contains special file")
    except OSError as exc:
        raise InvariantError(f"cannot inspect installed bundle tree: {exc}") from exc
    require(files == expected_paths, "installed bundle file tree mismatch")
    require(directories == _bundle_directories(expected_paths), "installed bundle directory tree mismatch")


def validate_bundle(release_dir: Path, manifest: dict[str, Any]) -> None:
    bundle_manifest = read_json(release_dir / "deployment-bundle.json")
    require(
        isinstance(bundle_manifest, dict)
        and set(bundle_manifest) == {"schemaVersion", "format", "digest", "sizeBytes", "files"},
        "bad deployment bundle manifest schema",
    )
    require(
        type(bundle_manifest["schemaVersion"]) is int
        and bundle_manifest["schemaVersion"] == 1
        and bundle_manifest["format"] == "tar",
        "bad deployment bundle manifest",
    )
    files = bundle_manifest["files"]
    require(isinstance(files, list) and len(files) == manifest["deploymentBundle"]["fileCount"], "bundle file count mismatch")
    frozen_legacy_bundle = (
        manifest["releaseId"] == FROZEN_CANDIDATE_RELEASE_ID
        and manifest["deploymentBundle"]["digest"] == FROZEN_CANDIDATE_BUNDLE_DIGEST
    )
    expected_fields = {"path", "digest", "sizeBytes"} if frozen_legacy_bundle else {"path", "digest", "sizeBytes", "mode"}
    names: set[str] = set()
    for expected in files:
        require(isinstance(expected, dict) and set(expected) == expected_fields, "bad bundle file entry schema")
        name = expected["path"]
        require(_bundle_path_is_canonical(name), "unsafe bundle path")
        require(name not in names and not any(name.startswith(old + "/") or old.startswith(name + "/") for old in names), "duplicate or overlapping bundle path")
        require(isinstance(expected["digest"], str) and DIGEST_RE.fullmatch(expected["digest"]), "bad bundle file digest")
        require(type(expected["sizeBytes"]) is int and expected["sizeBytes"] >= 0, "bad bundle file size")
        mode = 0o644 if frozen_legacy_bundle else expected["mode"]
        require(type(mode) is int and mode in {0o644, 0o755}, "bad bundle file mode")
        names.add(name)

    tar_path = release_dir / "deployment-bundle.tar"
    try:
        tar_bytes = tar_path.read_bytes()
    except OSError as exc:
        raise InvariantError(f"missing deployment bundle: {exc}") from exc
    require(type(bundle_manifest["sizeBytes"]) is int and bundle_manifest["sizeBytes"] > 0, "bad bundle size")
    require(isinstance(bundle_manifest["digest"], str) and DIGEST_RE.fullmatch(bundle_manifest["digest"]), "bad bundle digest")
    require(len(tar_bytes) == bundle_manifest["sizeBytes"], "bundle size mismatch")
    require(sha256_bytes(tar_bytes) == bundle_manifest["digest"], "bundle SHA mismatch")
    expected_identity = {
        "format": bundle_manifest["format"],
        "digest": bundle_manifest["digest"],
        "sizeBytes": bundle_manifest["sizeBytes"],
        "fileCount": len(files),
    }
    require(manifest["deploymentBundle"] == expected_identity, "bundle identity differs from release")
    _validate_installed_bundle_tree(release_dir / "bundle", names)
    try:
        with tarfile.open(tar_path, "r") as archive:
            members = archive.getmembers()
            require(len(members) == len(files), "bundle member count mismatch")
            for member, expected in zip(members, files):
                name = expected["path"]
                require(member.isfile() and member.name == name, "bundle member type/order/path mismatch")
                mode = 0o644 if frozen_legacy_bundle else expected["mode"]
                require(stat.S_IMODE(member.mode) == mode, "bundle member mode mismatch")
                extracted = archive.extractfile(member)
                require(extracted is not None, "cannot read bundle member")
                content = extracted.read()
                require(len(content) == expected["sizeBytes"], "internal bundle size mismatch")
                require(sha256_bytes(content) == expected["digest"], "internal bundle file hash mismatch")
                installed = release_dir / "bundle" / name
                info = os.lstat(installed)
                require(stat.S_ISREG(info.st_mode) and not stat.S_ISLNK(info.st_mode), "installed bundle member missing/unsafe")
                require(installed.read_bytes() == content, "installed bundle member differs")
                expected_installed_mode = 0o555 if mode & 0o111 else 0o444
                require(stat.S_IMODE(info.st_mode) == expected_installed_mode, "installed bundle member mode mismatch")
    except (OSError, tarfile.TarError) as exc:
        raise InvariantError(f"invalid deployment bundle tar: {exc}") from exc


def validate_release_directory(release_dir: Path, role: str) -> dict[str, Any]:
    require(role in {"cloud", "paid"}, "invalid role")
    require(release_dir.is_dir() and not release_dir.is_symlink(), "release directory missing or mutable")
    manifest = validate_release_manifest(read_json(release_dir / "manifest.json"))
    require(release_dir.name == manifest["releaseId"], "installed directory/release ID mismatch")
    expected = {
        image["environment"]: image["immutableRef"]
        for image in manifest["images"]
        if image["role"] == role
    }
    actual = parse_images_env(release_dir / "images.env")
    require(actual == expected, f"{role} images.env does not match exact release bindings")
    require(len(actual) == (20 if role == "cloud" else 8), f"wrong {role} image count")
    validate_bundle(release_dir, manifest)
    return manifest


def validate_active_pointer(base: Path, role: str) -> str:
    active = base / "active"
    require(active.is_symlink(), "active pointer is not a symlink")
    raw = os.readlink(active)
    resolved = active.resolve(strict=True)
    releases = (base / "releases").resolve(strict=True)
    require(resolved.parent == releases, "active pointer escapes immutable releases directory")
    validate_release_directory(resolved, role)
    require(raw in {f"releases/{resolved.name}", str(resolved)}, "active pointer target is not canonical")
    return resolved.name


def _memory_is_three_gib(value: Any) -> bool:
    if isinstance(value, int):
        return value == 3 * 1024**3
    if isinstance(value, str):
        return value.strip().lower() in {"3g", "3gb", "3gi", "3gib"}
    return False


def validate_compose_model(model: dict[str, Any], role: str, expected_images: dict[str, str]) -> None:
    require(isinstance(model, dict) and isinstance(model.get("services"), dict), "invalid rendered Compose model")
    serialized = json.dumps(model, sort_keys=True)
    require(("/srv/trinyx/" + "pr25-") not in serialized, "mutable PR25 checkout path in rendered Compose")
    services = model["services"]
    rendered_images: set[str] = set()
    for name, service in services.items():
        require(SERVICE_RE.fullmatch(name) is not None and isinstance(service, dict), "invalid Compose service")
        image = service.get("image")
        if image:
            require(isinstance(image, str) and "@sha256:" in image and ":latest" not in image, f"mutable image for {name}")
            rendered_images.add(image)
    require(rendered_images == set(expected_images.values()),
            "rendered Compose image inventory differs from exact release bindings")
    caddy = [image for image in rendered_images if image.startswith("caddy") or "/caddy@" in image]
    require(caddy and all("@sha256:" in image for image in caddy), "staging Caddy image is not digest pinned")
    if role == "paid":
        livecontext = services.get("livecontext")
        require(isinstance(livecontext, dict), "Paid livecontext service missing")
        deploy_memory = (
            livecontext.get("deploy", {})
            .get("resources", {})
            .get("limits", {})
            .get("memory")
        )
        memory = livecontext.get("mem_limit", deploy_memory)
        require(_memory_is_three_gib(memory), "Paid LiveContext memory limit is not 3 GiB")
        health = livecontext.get("healthcheck")
        require(isinstance(health, dict) and health.get("test"), "Paid LiveContext liveness missing")
        require("liveness" in json.dumps(health).lower(), "Paid healthcheck is not the liveness contract")


def validate_deployment_record(record: Any) -> dict[str, Any]:
    require(isinstance(record, dict) and set(record) == DEPLOYMENT_KEYS, "deployment record schema mismatch")
    require(type(record["schemaVersion"]) is int and record["schemaVersion"] == 2, "unsupported deployment record schema")
    require(re.fullmatch(r"^dep-[0-9a-f]{32}$", str(record["deploymentId"])) is not None, "bad deployment ID")
    require(record["environment"] == "staging", "deployment environment must be staging")
    require(RELEASE_RE.fullmatch(str(record["releaseId"])) is not None, "bad deployment release ID")
    require(re.fullmatch(r"^[A-Za-z0-9._-]{1,128}$", str(record["environmentConfigRevision"])) is not None, "bad environment config revision")
    require(SHA_RE.fullmatch(str(record["controlPlaneCommit"])) is not None, "bad control-plane commit")
    for key in ("previousCloudRelease", "previousPaidRelease"):
        require(record[key] is None or RELEASE_RE.fullmatch(str(record[key])) is not None, f"bad {key}")
    require(record["state"] in DEPLOYMENT_STATES, "bad deployment state")
    require(isinstance(record["history"], list) and record["history"], "deployment state history missing")
    assert_no_secret_material(record, "deployment record")
    return record


def forbid_global_compose_apply(paths: Iterable[Path]) -> None:
    pattern = re.compile(r"docker\s+compose(?:\s+[^\n;]+)?\s+up\s+-d(?:\s*(?:$|[;&]))")
    for path in paths:
        text = path.read_text(encoding="utf-8")
        require(not pattern.search(text), f"unscoped global Compose apply in {path}")
        require("docker system prune" not in text, f"docker system prune forbidden in {path}")


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    release = sub.add_parser("release")
    release.add_argument("--release-dir", required=True, type=Path)
    release.add_argument("--role", required=True, choices=("cloud", "paid"))
    active = sub.add_parser("active")
    active.add_argument("--base", required=True, type=Path)
    active.add_argument("--role", required=True, choices=("cloud", "paid"))
    record = sub.add_parser("record")
    record.add_argument("--path", required=True, type=Path)
    args = parser.parse_args()
    if args.command == "release":
        manifest = validate_release_directory(args.release_dir, args.role)
        print(f"INVARIANTS_RELEASE_OK role={args.role} release_id={manifest['releaseId']}")
    elif args.command == "active":
        print(f"INVARIANTS_ACTIVE_OK role={args.role} release_id={validate_active_pointer(args.base, args.role)}")
    else:
        validated = validate_deployment_record(read_json(args.path))
        print(f"INVARIANTS_RECORD_OK deployment_id={validated['deploymentId']} state={validated['state']}")


if __name__ == "__main__":
    main()
