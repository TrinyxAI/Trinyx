#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

SHA_RE = re.compile(r"^[0-9a-f]{40}$")
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
NAME_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,127}$")
ENV_RE = re.compile(r"^[A-Z][A-Z0-9_]*$")
RELEASE_RE = re.compile(r"^rel-v1-[0-9a-f]{32}$")
ROLES = {"cloud", "paid", "shared"}
IMAGE_FIELDS = (
    "name",
    "role",
    "service",
    "package",
    "environment",
    "digest",
    "immutableRef",
)


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"cannot read JSON {path}: {exc}")


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def normalize_image(item: dict[str, Any]) -> dict[str, str]:
    required = set(IMAGE_FIELDS)
    if set(item) != required:
        fail("image entry keys must be exactly: " + ",".join(sorted(required)))

    result = {key: str(item[key]) for key in IMAGE_FIELDS}
    if not NAME_RE.fullmatch(result["name"]):
        fail(f"invalid image name: {result['name']}")
    if result["role"] not in ROLES:
        fail(f"invalid image role for {result['name']}")
    if not NAME_RE.fullmatch(result["service"]):
        fail(f"invalid service for {result['name']}")
    if not result["package"] or "@" in result["package"] or any(ch.isspace() for ch in result["package"]):
        fail(f"invalid package for {result['name']}")
    if ":latest" in result["package"] or result["package"].endswith(":latest"):
        fail(f"mutable latest package is forbidden for {result['name']}")
    if not ENV_RE.fullmatch(result["environment"]):
        fail(f"invalid environment variable for {result['name']}")
    if not DIGEST_RE.fullmatch(result["digest"]):
        fail(f"invalid digest for {result['name']}")
    expected = result["package"] + "@" + result["digest"]
    if result["immutableRef"] != expected:
        fail(f"immutableRef mismatch for {result['name']}")
    return result


def normalize_images(document: Any) -> list[dict[str, str]]:
    if isinstance(document, dict) and isinstance(document.get("images"), list):
        raw_images = document["images"]
    elif isinstance(document, list):
        raw_images = document
    else:
        fail("image input must be an array or an object containing images[]")

    images = [normalize_image(dict(item)) for item in raw_images]
    if not images:
        fail("release must contain at least one image")

    names = [item["name"] for item in images]
    if len(names) != len(set(names)):
        fail("duplicate image name")

    env_keys = [(item["role"], item["environment"]) for item in images]
    if len(env_keys) != len(set(env_keys)):
        fail("duplicate role/environment image binding")

    return sorted(images, key=lambda item: item["name"])


def identity_payload(manifest: dict[str, Any]) -> dict[str, Any]:
    return {
        "schemaVersion": manifest["schemaVersion"],
        "sourceCommit": manifest["sourceCommit"],
        "platformCommit": manifest["platformCommit"],
        "configRevision": manifest["configRevision"],
        "images": manifest["images"],
    }


def calculate_release_id(manifest: dict[str, Any]) -> str:
    digest = hashlib.sha256(canonical_json(identity_payload(manifest))).hexdigest()
    return "rel-v1-" + digest[:32]


def validate_manifest(manifest: Any) -> dict[str, Any]:
    if not isinstance(manifest, dict):
        fail("release manifest must be a JSON object")

    required = {
        "schemaVersion",
        "releaseId",
        "sourceCommit",
        "sourceRef",
        "platformCommit",
        "configRevision",
        "createdAt",
        "images",
    }
    if set(manifest) != required:
        fail("release manifest keys do not match schema v1")
    if manifest["schemaVersion"] != 1:
        fail("unsupported schemaVersion")

    for key in ("sourceCommit", "platformCommit", "configRevision"):
        if not isinstance(manifest[key], str) or not SHA_RE.fullmatch(manifest[key]):
            fail(f"invalid {key}")

    source_ref = manifest["sourceRef"]
    if not isinstance(source_ref, str) or not source_ref or len(source_ref) > 255 or "\n" in source_ref:
        fail("invalid sourceRef")

    created = manifest["createdAt"]
    if not isinstance(created, str):
        fail("invalid createdAt")
    try:
        datetime.fromisoformat(created.replace("Z", "+00:00"))
    except ValueError:
        fail("createdAt must be RFC3339/ISO-8601")

    manifest = dict(manifest)
    manifest["images"] = normalize_images(manifest["images"])

    release_id = manifest["releaseId"]
    if not isinstance(release_id, str) or not RELEASE_RE.fullmatch(release_id):
        fail("invalid releaseId")
    expected = calculate_release_id(manifest)
    if release_id != expected:
        fail(f"releaseId content hash mismatch: expected {expected}")

    return manifest


def create_manifest(args: argparse.Namespace) -> dict[str, Any]:
    for label, value in (
        ("sourceCommit", args.source_commit),
        ("platformCommit", args.platform_commit),
        ("configRevision", args.config_revision),
    ):
        if not SHA_RE.fullmatch(value):
            fail(f"invalid {label}")

    images = normalize_images(load_json(args.images))
    created_at = args.created_at or datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    manifest: dict[str, Any] = {
        "schemaVersion": 1,
        "releaseId": "",
        "sourceCommit": args.source_commit,
        "sourceRef": args.source_ref,
        "platformCommit": args.platform_commit,
        "configRevision": args.config_revision,
        "createdAt": created_at,
        "images": images,
    }
    manifest["releaseId"] = calculate_release_id(manifest)
    return validate_manifest(manifest)


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(canonical_json(value) + b"\n")


def command_create(args: argparse.Namespace) -> None:
    manifest = create_manifest(args)
    write_json(args.out, manifest)
    print(f"RELEASE_CREATE_OK release_id={manifest['releaseId']} images={len(manifest['images'])}")


def command_validate(args: argparse.Namespace) -> None:
    manifest = validate_manifest(load_json(args.manifest))
    print(f"RELEASE_VALIDATE_OK release_id={manifest['releaseId']} images={len(manifest['images'])}")


def command_render_env(args: argparse.Namespace) -> None:
    manifest = validate_manifest(load_json(args.manifest))
    selected = [item for item in manifest["images"] if item["role"] in {args.role, "shared"}]
    if not selected:
        fail(f"release has no images for role {args.role}")
    bindings: dict[str, str] = {}
    for item in selected:
        env = item["environment"]
        if env in bindings:
            fail(f"duplicate rendered environment binding: {env}")
        bindings[env] = item["immutableRef"]
    content = "".join(f"{key}={bindings[key]}\n" for key in sorted(bindings))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(content, encoding="utf-8", newline="\n")
    print(f"RELEASE_RENDER_ENV_OK release_id={manifest['releaseId']} role={args.role} images={len(bindings)}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Create and validate immutable Trinyx release manifests")
    sub = parser.add_subparsers(dest="command", required=True)

    create = sub.add_parser("create")
    create.add_argument("--source-commit", required=True)
    create.add_argument("--source-ref", required=True)
    create.add_argument("--platform-commit", required=True)
    create.add_argument("--config-revision", required=True)
    create.add_argument("--images", required=True, type=Path)
    create.add_argument("--created-at")
    create.add_argument("--out", required=True, type=Path)
    create.set_defaults(func=command_create)

    validate = sub.add_parser("validate")
    validate.add_argument("--manifest", required=True, type=Path)
    validate.set_defaults(func=command_validate)

    render = sub.add_parser("render-env")
    render.add_argument("--manifest", required=True, type=Path)
    render.add_argument("--role", required=True, choices=("cloud", "paid"))
    render.add_argument("--out", required=True, type=Path)
    render.set_defaults(func=command_render_env)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
