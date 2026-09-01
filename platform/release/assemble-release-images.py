#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

SHA_RE = re.compile(r"^[0-9a-f]{40}$")
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def load(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"cannot read JSON {path}: {exc}")


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Assemble complete Trinyx runtime image set")
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--inventory", required=True, type=Path)
    parser.add_argument("--manifest", action="append", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()

    if not SHA_RE.fullmatch(args.source_commit):
        fail("invalid source commit")

    inventory = load(args.inventory)
    if set(inventory) != {"schemaVersion", "images"} or inventory["schemaVersion"] != 1:
        fail("invalid runtime inventory")
    if not isinstance(inventory["images"], list) or not inventory["images"]:
        fail("runtime inventory must contain images")

    expected: dict[str, dict[str, str]] = {}
    for raw in inventory["images"]:
        required = {"name", "role", "service", "environment"}
        if not isinstance(raw, dict) or set(raw) != required:
            fail("invalid runtime inventory image entry")
        item = {key: str(raw[key]) for key in required}
        if item["name"] in expected:
            fail(f"duplicate runtime inventory name: {item['name']}")
        expected[item["name"]] = item

    assembled: dict[str, dict[str, str]] = {}
    for manifest_path in args.manifest:
        document = load(manifest_path)
        if not isinstance(document, dict) or not isinstance(document.get("images"), list):
            fail(f"manifest has no images[]: {manifest_path}")
        commit = document.get("commit") or document.get("sourceCommit")
        if commit is not None:
            if not isinstance(commit, str) or not SHA_RE.fullmatch(commit):
                fail(f"invalid manifest commit: {manifest_path}")
            if commit != args.source_commit:
                fail(f"manifest commit mismatch: {manifest_path}")

        for raw in document["images"]:
            if not isinstance(raw, dict):
                fail(f"invalid image entry in {manifest_path}")
            name = str(raw.get("name", ""))
            if name not in expected:
                fail(f"unexpected runtime image: {name}")
            if name in assembled:
                fail(f"duplicate assembled image: {name}")
            binding = expected[name]

            for key in ("service", "environment"):
                if str(raw.get(key, "")) != binding[key]:
                    fail(f"runtime binding mismatch for {name}: {key}")
            if "role" in raw and str(raw["role"]) != binding["role"]:
                fail(f"runtime binding mismatch for {name}: role")

            package = str(raw.get("package", ""))
            digest = str(raw.get("digest", ""))
            immutable_ref = str(raw.get("immutableRef", ""))
            if not package or "@" in package or any(ch.isspace() for ch in package):
                fail(f"invalid package for {name}")
            if not DIGEST_RE.fullmatch(digest):
                fail(f"invalid digest for {name}")
            if immutable_ref != package + "@" + digest:
                fail(f"immutableRef mismatch for {name}")

            assembled[name] = {
                "name": name,
                "role": binding["role"],
                "service": binding["service"],
                "package": package,
                "environment": binding["environment"],
                "digest": digest,
                "immutableRef": immutable_ref,
            }

    missing = sorted(set(expected) - set(assembled))
    if missing:
        fail("missing runtime images: " + ",".join(missing))
    if len(assembled) != len(expected):
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
