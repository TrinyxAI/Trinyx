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
IMAGE_KEYS = {"name", "role", "service", "package", "environment", "digest", "immutableRef"}
IMAGE_DOCUMENT_KEYS = {"schemaVersion", "sourceCommit", "images"}
RELEASE_MANIFEST_KEYS = {
    "schemaVersion",
    "releaseId",
    "sourceCommit",
    "sourceRef",
    "platformCommit",
    "createdAt",
    "deploymentBundle",
    "images",
}


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
        fail(f"cannot read {path}: {exc}")


def validate_inventory(contract: Any) -> dict[str, tuple[str, str, str]]:
    if (
        not isinstance(contract, dict)
        or set(contract) != {"schemaVersion", "images"}
        or type(contract["schemaVersion"]) is not int
        or contract["schemaVersion"] != 1
        or not isinstance(contract["images"], list)
        or len(contract["images"]) != 28
    ):
        fail("invalid runtime inventory contract")
    expected: dict[str, tuple[str, str, str]] = {}
    bindings: set[tuple[str, str]] = set()
    for item in contract["images"]:
        keys = {"name", "role", "service", "environment"}
        if (
            not isinstance(item, dict)
            or set(item) != keys
            or any(not isinstance(item[key], str) for key in keys)
            or item["role"] not in {"cloud", "paid"}
            or not NAME_RE.fullmatch(item["name"])
            or not NAME_RE.fullmatch(item["service"])
            or not ENV_RE.fullmatch(item["environment"])
        ):
            fail("invalid runtime inventory image entry")
        if item["name"] in expected:
            fail("duplicate/empty runtime contract image name")
        binding = (item["role"], item["environment"])
        if binding in bindings:
            fail("duplicate runtime contract image binding")
        expected[item["name"]] = (item["role"], item["service"], item["environment"])
        bindings.add(binding)
    return expected


def validate_images(document: Any) -> dict[str, tuple[str, str, str]]:
    # This validator is deliberately usable for both canonical release-images.json
    # and the closed release.json schema emitted by release.py.  Do not accept an
    # arbitrary wrapper merely because it happens to contain an images list.
    if not isinstance(document, dict):
        fail("image document schema mismatch")
    keys = set(document)
    if keys != IMAGE_DOCUMENT_KEYS and keys != RELEASE_MANIFEST_KEYS:
        fail("image document schema mismatch")
    if (
        type(document["schemaVersion"]) is not int
        or document["schemaVersion"] != 1
        or not isinstance(document["sourceCommit"], str)
        or not SHA_RE.fullmatch(document["sourceCommit"])
        or not isinstance(document["images"], list)
        or len(document["images"]) != 28
    ):
        fail("image document schema mismatch")
    actual: dict[str, tuple[str, str, str]] = {}
    bindings: set[tuple[str, str]] = set()
    for item in document["images"]:
        if (
            not isinstance(item, dict)
            or set(item) != IMAGE_KEYS
            or any(not isinstance(item[key], str) for key in IMAGE_KEYS)
            or not NAME_RE.fullmatch(item["name"])
            or not NAME_RE.fullmatch(item["service"])
            or not ENV_RE.fullmatch(item["environment"])
        ):
            fail("invalid runtime image entry")
        name = item["name"]
        role = item["role"]
        package = item["package"]
        digest = item["digest"]
        if role not in {"cloud", "paid"}:
            fail(f"invalid image role for {name}")
        if name in actual:
            fail("duplicate/empty image name")
        if not DIGEST_RE.fullmatch(digest):
            fail(f"non-immutable digest for {name}")
        if not PACKAGE_RE.fullmatch(package):
            fail(f"image repository must be canonical and tagless for {name}")
        if item["immutableRef"] != package + "@" + digest:
            fail(f"immutableRef mismatch for {name}")
        binding = (role, item["environment"])
        if binding in bindings:
            fail("duplicate runtime image binding")
        actual[name] = (role, item["service"], item["environment"])
        bindings.add(binding)
    return actual


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--contract", required=True, type=Path)
    parser.add_argument("--images", required=True, type=Path)
    args = parser.parse_args()

    expected = validate_inventory(load(args.contract))
    actual = validate_images(load(args.images))
    missing = sorted(set(expected) - set(actual))
    extra = sorted(set(actual) - set(expected))
    mismatch = sorted(name for name in expected.keys() & actual.keys() if expected[name] != actual[name])
    if missing:
        fail("missing runtime images: " + ",".join(missing))
    if extra:
        fail("extra runtime images: " + ",".join(extra))
    if mismatch:
        fail("runtime binding mismatch: " + ",".join(mismatch))
    print(f"RUNTIME_IMAGE_CONTRACT_OK images={len(actual)}")


if __name__ == "__main__":
    main()
