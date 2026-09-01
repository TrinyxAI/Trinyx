#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def load(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"cannot read {path}: {exc}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--contract", required=True, type=Path)
    parser.add_argument("--images", required=True, type=Path)
    args = parser.parse_args()

    contract = load(args.contract)
    document = load(args.images)
    if contract.get("schemaVersion") != 1 or not isinstance(contract.get("images"), list):
        fail("invalid runtime inventory contract")
    if not isinstance(document, dict) or not isinstance(document.get("images"), list):
        fail("image document must contain images[]")

    expected: dict[str, tuple[str, str, str]] = {}
    for item in contract["images"]:
        key = str(item.get("name", ""))
        value = (str(item.get("role", "")), str(item.get("service", "")), str(item.get("environment", "")))
        if not key or key in expected:
            fail("duplicate/empty runtime contract image name")
        expected[key] = value

    actual: dict[str, tuple[str, str, str]] = {}
    for item in document["images"]:
        name = str(item.get("name", ""))
        role = str(item.get("role", ""))
        service = str(item.get("service", ""))
        environment = str(item.get("environment", ""))
        package = str(item.get("package", ""))
        digest = str(item.get("digest", ""))
        immutable = str(item.get("immutableRef", ""))
        if not name or name in actual:
            fail("duplicate/empty image name")
        if not DIGEST_RE.fullmatch(digest):
            fail(f"non-immutable digest for {name}")
        if not package or "@" in package or any(ch.isspace() for ch in package):
            fail(f"invalid package for {name}")
        if immutable != package + "@" + digest:
            fail(f"immutableRef mismatch for {name}")
        actual[name] = (role, service, environment)

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
