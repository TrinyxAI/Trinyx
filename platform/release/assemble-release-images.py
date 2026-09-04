#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

SHA_RE = re.compile(r"^[0-9a-f]{40}$")
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
NAME_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,127}$")
ENV_RE = re.compile(r"^[A-Z][A-Z0-9_]*$")
PACKAGE_RE = re.compile(
    r"^(?:(?:[a-z0-9]+(?:[._-][a-z0-9]+)*(?::[0-9]{1,5})?)/)?"
    r"[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*$"
)
UTC_RE = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
IMAGE_KEYS = {"name", "service", "package", "environment", "digest", "immutableRef"}
IMAGE_KEYS_WITH_ROLE = IMAGE_KEYS | {"role"}


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def _no_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_no_duplicate_keys)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        fail(f"cannot read JSON {path}: {exc}")


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def require_image_identity(item: dict[str, Any], label: str) -> None:
    keys = set(item)
    if keys != IMAGE_KEYS and keys != IMAGE_KEYS_WITH_ROLE:
        fail(f"invalid image entry schema in {label}")
    if any(not isinstance(item[key], str) for key in keys):
        fail(f"invalid image entry types in {label}")
    if not NAME_RE.fullmatch(item["name"]) or not NAME_RE.fullmatch(item["service"]):
        fail(f"invalid image name/service in {label}")
    if not ENV_RE.fullmatch(item["environment"]):
        fail(f"invalid image environment in {label}")
    package = item["package"]
    digest = item["digest"]
    if not PACKAGE_RE.fullmatch(package):
        fail(f"image repository must be canonical and tagless in {label}")
    if not DIGEST_RE.fullmatch(digest):
        fail(f"invalid digest in {label}")
    if item["immutableRef"] != package + "@" + digest:
        fail(f"immutableRef mismatch in {label}")


def load_inventory(path: Path) -> dict[str, dict[str, str]]:
    inventory = load(path)
    if (
        not isinstance(inventory, dict)
        or set(inventory) != {"schemaVersion", "images"}
        or type(inventory["schemaVersion"]) is not int
        or inventory["schemaVersion"] != 1
        or not isinstance(inventory["images"], list)
        or len(inventory["images"]) != 28
    ):
        fail("invalid runtime inventory")
    expected: dict[str, dict[str, str]] = {}
    bindings: set[tuple[str, str]] = set()
    for raw in inventory["images"]:
        required = {"name", "role", "service", "environment"}
        if (
            not isinstance(raw, dict)
            or set(raw) != required
            or any(not isinstance(raw[key], str) for key in required)
            or raw["role"] not in {"cloud", "paid"}
            or not NAME_RE.fullmatch(raw["name"])
            or not NAME_RE.fullmatch(raw["service"])
            or not ENV_RE.fullmatch(raw["environment"])
        ):
            fail("invalid runtime inventory image entry")
        if raw["name"] in expected:
            fail(f"duplicate runtime inventory name: {raw['name']}")
        binding = (raw["role"], raw["environment"])
        if binding in bindings:
            fail(f"duplicate runtime inventory binding: {raw['name']}")
        expected[raw["name"]] = {key: raw[key] for key in required}
        bindings.add(binding)
    return expected


def load_input_manifest(path: Path, source_commit: str) -> list[dict[str, str]]:
    document = load(path)
    if (
        not isinstance(document, dict)
        or type(document.get("schemaVersion")) is not int
        or document["schemaVersion"] != 1
        or not isinstance(document.get("images"), list)
    ):
        fail(f"invalid image manifest schema: {path}")
    keys = set(document)
    allowed = (
        {"schemaVersion", "commit", "images"},
        {"schemaVersion", "commit", "generatedAt", "images"},
        {"schemaVersion", "sourceCommit", "images"},
        {"schemaVersion", "kind", "images"},
    )
    if keys not in allowed:
        fail(f"invalid image manifest schema: {path}")
    if "commit" in document:
        if not isinstance(document["commit"], str) or document["commit"] != source_commit:
            fail(f"manifest commit mismatch: {path}")
    if "sourceCommit" in document:
        if not isinstance(document["sourceCommit"], str) or document["sourceCommit"] != source_commit:
            fail(f"manifest sourceCommit mismatch: {path}")
    if "generatedAt" in document and (
        not isinstance(document["generatedAt"], str) or not UTC_RE.fullmatch(document["generatedAt"])
    ):
        fail(f"manifest generatedAt is invalid: {path}")
    if "kind" in document and document["kind"] != "static-third-party":
        fail(f"invalid static image manifest kind: {path}")
    result: list[dict[str, str]] = []
    for raw in document["images"]:
        if not isinstance(raw, dict):
            fail(f"invalid image entry in {path}")
        require_image_identity(raw, str(path))
        result.append({key: raw[key] for key in raw})
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Assemble complete Trinyx runtime image set")
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--inventory", required=True, type=Path)
    parser.add_argument("--manifest", action="append", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()

    if not SHA_RE.fullmatch(args.source_commit):
        fail("invalid source commit")
    expected = load_inventory(args.inventory)

    assembled: dict[str, dict[str, str]] = {}
    for manifest_path in args.manifest:
        for raw in load_input_manifest(manifest_path, args.source_commit):
            name = raw["name"]
            if name not in expected:
                fail(f"unexpected runtime image: {name}")
            if name in assembled:
                fail(f"duplicate assembled image: {name}")
            binding = expected[name]
            if raw["service"] != binding["service"] or raw["environment"] != binding["environment"]:
                fail(f"runtime binding mismatch for {name}")
            if "role" in raw and raw["role"] != binding["role"]:
                fail(f"runtime binding mismatch for {name}: role")
            assembled[name] = {
                "name": name,
                "role": binding["role"],
                "service": binding["service"],
                "package": raw["package"],
                "environment": binding["environment"],
                "digest": raw["digest"],
                "immutableRef": raw["immutableRef"],
            }

    missing = sorted(set(expected) - set(assembled))
    if missing:
        fail("missing runtime images: " + ",".join(missing))
    if len(assembled) != 28:
        fail("assembled runtime image count mismatch")

    result = {
        "schemaVersion": 1,
        "sourceCommit": args.source_commit,
        "images": [assembled[name] for name in sorted(assembled)],
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(canonical_json(result) + b"\n")
    print(f"RELEASE_IMAGE_ASSEMBLY_OK images={len(assembled)} source_commit={args.source_commit}")


if __name__ == "__main__":
    main()
