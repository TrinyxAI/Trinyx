#!/usr/bin/env python3
"""Immutable, content-addressed staging release registry client.

The S3 implementation uses conditional puts. `registration.json` is written
last and is the only commit marker. Matching partial objects are retryable;
different bytes at an existing key are an immutable collision.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

if __package__:
    from .invariants import (
        InvariantError,
        assert_no_secret_material,
        canonical_json,
        read_json,
        require,
        sha256_bytes,
        validate_release_manifest,
    )
else:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from invariants import (  # type: ignore
        InvariantError,
        assert_no_secret_material,
        canonical_json,
        read_json,
        require,
        sha256_bytes,
        validate_release_manifest,
    )

OBJECT_FILES = ("release.json", "release-images.json", "deployment-bundle.json", "deployment-bundle.tar", "provenance.json")
REPOSITORY = "TrinyxAI/Trinyx"
APPROVED_BUILDER_WORKFLOW_COMMIT = "114a2613e8090f034925a1bcf148f055653c3a06"
FROZEN_CANDIDATE = {
    "sourceCommit": "f3a4c1ddcf6a17bfc837071f9046ac4c38a38b47",
    "releaseId": "rel-v1-b5ba70c23b9f529ac8228a7b00b4faa4",
    "bundleDigest": "sha256:c9df14dcd1dbc24b31b926d3778bef2e208b59824c78f24292608284f3579892",
    "artifactId": "9791964215",
    "runId": "33485509832",
    "artifactDigest": "sha256:755594078d9da7e19406e01187534132920a31f87804c1b33baa28fa96559152",
    "releaseManifestDigest": "sha256:ad5a5b702d9659e0af5d5b82a422953ba2390a94396949f897757568c9b59789",
    "imageInventoryDigest": "sha256:fe1134c3920af0f2f9f0027082f25ec5adb1cbb6d41a1053bada7ef730f66a8a",
    "bundleManifestDigest": "sha256:b101918414ee9d113d4ef54d32c9f438005d8ebee7bde2e62f72d58a16cfdd7b",
}
APPROVED_HISTORICAL_BASELINE = {
    "sourceCommit": "aeb2a447ea7ce0436a60549713636225dfe1a2c1",
    "releaseId": "rel-v1-61d902b8c3f36f7b23873cab31427243",
    "bundleDigest": "sha256:178805ec9d47a8624d1476ec3859959b9033f2893f0473051d9c9c3d2b9c0047",
    "artifactId": "9931132603",
    "runId": "33858423626",
    "artifactDigest": "sha256:76fa8c2765f08f2f502d43e497e7da4a104e134e9d35ad7be661224aa8adde2a",
    "builderCommit": "22f1e593c36eaf1d70197db91bd54e31844a7eef",
    "releaseManifestDigest": "sha256:b8bc11965c29e5cdc85389fd9f5d232abe359c4d85ecaf5caad381272fdbbc12",
    "imageInventoryDigest": "sha256:b8ec0fa73f5e1b5b0cab04d729f7c21618c5bcb2f805fa908963c2a2c31320d0",
    "bundleManifestDigest": "sha256:16321b2ed8876fed4bd6a57c69d42c39199c36bf81432e36b34f291c31d8cf03",
}



@dataclass(frozen=True)
class ObjectInfo:
    key: str
    sha256: str
    size: int


class Registry(Protocol):
    def put_if_absent(self, key: str, content: bytes, sha256_hex: str) -> str: ...

    def get_required(self, key: str) -> bytes: ...


class AwsCliRegistry:
    def __init__(self, bucket: str, region: str):
        require(re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", bucket) is not None, "invalid bucket name")
        require(re.fullmatch(r"[a-z]{2}-[a-z]+-[0-9]", region) is not None, "invalid AWS region")
        self.bucket = bucket
        self.region = region

    def _run(self, argv: list[str], capture: bool = True) -> subprocess.CompletedProcess[bytes]:
        try:
            return subprocess.run(
                argv,
                check=False,
                stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                stderr=subprocess.PIPE,
                timeout=60,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise InvariantError("bounded AWS CLI operation failed") from exc

    def _head(self, key: str) -> dict[str, Any] | None:
        result = self._run(
            [
                "aws",
                "s3api",
                "head-object",
                "--bucket",
                self.bucket,
                "--key",
                key,
                "--region",
                self.region,
                "--output",
                "json",
            ]
        )
        if result.returncode == 0:
            try:
                return json.loads(result.stdout)
            except json.JSONDecodeError as exc:
                raise InvariantError("invalid S3 head-object response") from exc
        error = result.stderr.decode("utf-8", "replace")
        if any(marker in error for marker in ("404", "Not Found", "NoSuchKey")):
            return None
        raise InvariantError("S3 access denied or unavailable")

    def put_if_absent(self, key: str, content: bytes, sha256_hex: str) -> str:
        existing = self._head(key)
        if existing is not None:
            metadata = existing.get("Metadata", {})
            require(metadata.get("sha256") == sha256_hex and existing.get("ContentLength") == len(content), "immutable S3 object collision")
            return "EXISTS_MATCHING"
        with tempfile.NamedTemporaryFile(delete=False) as handle:
            temp_name = handle.name
            handle.write(content)
        try:
            checksum = base64.b64encode(bytes.fromhex(sha256_hex)).decode("ascii")
            result = self._run(
                [
                    "aws",
                    "s3api",
                    "put-object",
                    "--bucket",
                    self.bucket,
                    "--key",
                    key,
                    "--body",
                    temp_name,
                    "--if-none-match",
                    "*",
                    "--server-side-encryption",
                    "AES256",
                    "--checksum-algorithm",
                    "SHA256",
                    "--checksum-sha256",
                    checksum,
                    "--metadata",
                    f"sha256={sha256_hex}",
                    "--region",
                    self.region,
                ]
            )
            if result.returncode != 0:
                raise InvariantError("conditional S3 put failed")
            return "CREATED"
        finally:
            with contextlib_suppress(FileNotFoundError):
                os.unlink(temp_name)

    def get_required(self, key: str) -> bytes:
        with tempfile.NamedTemporaryFile(delete=False) as handle:
            temp_name = handle.name
        try:
            result = self._run(
                [
                    "aws",
                    "s3api",
                    "get-object",
                    "--bucket",
                    self.bucket,
                    "--key",
                    key,
                    "--checksum-mode",
                    "ENABLED",
                    "--region",
                    self.region,
                    temp_name,
                ]
            )
            if result.returncode != 0:
                error = result.stderr.decode("utf-8", "replace")
                if any(marker in error for marker in ("404", "Not Found", "NoSuchKey")):
                    raise InvariantError("required S3 object missing")
                raise InvariantError("S3 access denied or unavailable")
            return Path(temp_name).read_bytes()
        finally:
            with contextlib_suppress(FileNotFoundError):
                os.unlink(temp_name)


class contextlib_suppress:
    """Tiny local equivalent avoids hiding broad exceptions in registry logic."""

    def __init__(self, exception: type[BaseException]):
        self.exception = exception

    def __enter__(self) -> None:
        return None

    def __exit__(self, exc_type: Any, exc: Any, traceback: Any) -> bool:
        return exc_type is not None and issubclass(exc_type, self.exception)


def validate_candidate(directory: Path) -> tuple[dict[str, Any], dict[str, bytes]]:
    files: dict[str, bytes] = {}
    for name in OBJECT_FILES:
        path = directory / name
        require(path.is_file() and not path.is_symlink(), f"candidate file missing/unsafe: {name}")
        files[name] = path.read_bytes()
    manifest = validate_release_manifest(json.loads(files["release.json"]))
    images = json.loads(files["release-images.json"])
    require(isinstance(images, dict) and images.get("images") == manifest["images"], "release image inventory differs from manifest")
    provenance = json.loads(files["provenance.json"])
    required_provenance = {
        "schemaVersion",
        "repository",
        "signerWorkflow",
        "signerDigest",
        "compatibility",
        "sourceCommit",
        "artifactId",
        "runId",
        "artifactDigest",
        "verifiedAt",
    }
    require(isinstance(provenance, dict) and set(provenance) == required_provenance, "provenance schema mismatch")
    require(provenance["schemaVersion"] == 2 and provenance["repository"] == REPOSITORY, "wrong provenance repository")
    require(re.fullmatch(r"[0-9]+", str(provenance["runId"])) is not None, "bad provenance run ID")
    require(provenance["sourceCommit"] == manifest["sourceCommit"], "provenance/source commit mismatch")
    require(re.fullmatch(r"sha256:[0-9a-f]{64}", str(provenance["artifactDigest"])) is not None, "bad artifact provenance digest")
    signer = (provenance["signerWorkflow"], provenance["signerDigest"], provenance["compatibility"])
    historical_candidate = False
    approved_historical_baseline = (
        signer
        == (
            "build-historical-staging-baseline-impl.yml",
            APPROVED_HISTORICAL_BASELINE["builderCommit"],
            "pinned-reusable-builder",
        )
        and provenance["sourceCommit"] == APPROVED_HISTORICAL_BASELINE["sourceCommit"]
        and manifest["releaseId"] == APPROVED_HISTORICAL_BASELINE["releaseId"]
        and manifest["deploymentBundle"]["digest"]
        == APPROVED_HISTORICAL_BASELINE["bundleDigest"]
        and str(provenance["artifactId"])
        == APPROVED_HISTORICAL_BASELINE["artifactId"]
        and str(provenance["runId"]) == APPROVED_HISTORICAL_BASELINE["runId"]
        and provenance["artifactDigest"]
        == APPROVED_HISTORICAL_BASELINE["artifactDigest"]
        and sha256_bytes(files["release.json"])
        == APPROVED_HISTORICAL_BASELINE["releaseManifestDigest"]
        and sha256_bytes(files["release-images.json"])
        == APPROVED_HISTORICAL_BASELINE["imageInventoryDigest"]
        and sha256_bytes(files["deployment-bundle.json"])
        == APPROVED_HISTORICAL_BASELINE["bundleManifestDigest"]
    )
    if signer == (
        "build-release-candidate-impl.yml",
        APPROVED_BUILDER_WORKFLOW_COMMIT,
        "pinned-reusable-builder",
    ):
        pass
    elif approved_historical_baseline:
        pass
    else:
        historical_candidate = (
            signer == (
                "build-release-candidate.yml",
                FROZEN_CANDIDATE["sourceCommit"],
                "frozen-historical-builder",
            )
            and provenance["sourceCommit"] == FROZEN_CANDIDATE["sourceCommit"]
            and manifest["releaseId"] == FROZEN_CANDIDATE["releaseId"]
            and manifest["deploymentBundle"]["digest"] == FROZEN_CANDIDATE["bundleDigest"]
            and str(provenance["artifactId"]) == FROZEN_CANDIDATE["artifactId"]
            and str(provenance["runId"]) == FROZEN_CANDIDATE["runId"]
            and provenance["artifactDigest"] == FROZEN_CANDIDATE["artifactDigest"]
            and sha256_bytes(files["release.json"]) == FROZEN_CANDIDATE["releaseManifestDigest"]
            and sha256_bytes(files["release-images.json"]) == FROZEN_CANDIDATE["imageInventoryDigest"]
            and sha256_bytes(files["deployment-bundle.json"]) == FROZEN_CANDIDATE["bundleManifestDigest"]
        )
        require(
            historical_candidate,
            "historical builder compatibility is restricted to the frozen candidate",
        )
    assert_no_secret_material(provenance, "provenance")
    bundle_manifest = json.loads(files["deployment-bundle.json"])
    require(isinstance(bundle_manifest, dict)
            and set(bundle_manifest) == {"schemaVersion", "format", "digest", "sizeBytes", "files"}
            and bundle_manifest.get("schemaVersion") == 1 and bundle_manifest.get("format") == "tar",
            "bad bundle manifest")
    entries = bundle_manifest.get("files")
    require(isinstance(entries, list) and len(entries) == manifest["deploymentBundle"]["fileCount"], "bundle file count mismatch")
    tar_bytes = files["deployment-bundle.tar"]
    require(sha256_bytes(tar_bytes) == manifest["deploymentBundle"]["digest"], "wrong bundle SHA")
    require(len(tar_bytes) == manifest["deploymentBundle"]["sizeBytes"], "wrong bundle size")
    try:
        with tarfile.open(fileobj=__import__("io").BytesIO(tar_bytes), mode="r") as archive:
            members = archive.getmembers()
            require(len(members) == len(entries), "bundle member count mismatch")
            names: set[str] = set()
            expected_entry_keys = (
                {"path", "digest", "sizeBytes"}
                if historical_candidate
                else {"path", "digest", "sizeBytes", "mode"}
            )
            for member, expected in zip(members, entries):
                require(isinstance(expected, dict) and set(expected) == expected_entry_keys,
                        "bad bundle file contract")
                name = expected.get("path")
                require(isinstance(name, str) and name and not name.startswith("/") and ".." not in Path(name).parts,
                        "unsafe bundle member")
                require(name not in names and member.isfile() and member.name == name, "unsafe/unexpected bundle member")
                expected_mode = 0o644 if historical_candidate else expected.get("mode")
                require(member.size == expected.get("sizeBytes") and stat.S_IMODE(member.mode) == expected_mode,
                        "bundle member size/mode mismatch")
                content = archive.extractfile(member)
                require(content is not None and sha256_bytes(content.read()) == expected.get("digest"), "bad internal file hash")
                names.add(name)
    except tarfile.TarError as exc:
        raise InvariantError("tampered bundle") from exc
    return manifest, files


def release_prefix(manifest: dict[str, Any]) -> str:
    bundle_hex = manifest["deploymentBundle"]["digest"].removeprefix("sha256:")
    return f"staging/releases/{manifest['releaseId']}/{bundle_hex}"


def register(registry: Registry, candidate_dir: Path) -> dict[str, Any]:
    manifest, files = validate_candidate(candidate_dir)
    prefix = release_prefix(manifest)
    reservation = {
        "schemaVersion": 1,
        "environment": "staging",
        "releaseId": manifest["releaseId"],
        "bundleDigest": manifest["deploymentBundle"]["digest"],
        "prefix": prefix,
    }
    reservation_content = canonical_json(reservation) + b"\n"
    registry.put_if_absent(
        f"staging/release-ids/{manifest['releaseId']}.json",
        reservation_content,
        hashlib.sha256(reservation_content).hexdigest(),
    )
    objects: list[dict[str, Any]] = []
    for name in OBJECT_FILES:
        content = files[name]
        digest = hashlib.sha256(content).hexdigest()
        registry.put_if_absent(f"{prefix}/{name}", content, digest)
        objects.append({"key": f"{prefix}/{name}", "sha256": digest, "sizeBytes": len(content)})
    registration = {
        "schemaVersion": 1,
        "environment": "staging",
        "releaseId": manifest["releaseId"],
        "bundleDigest": manifest["deploymentBundle"]["digest"],
        "objects": objects,
    }
    assert_no_secret_material(registration, "release registration")
    content = canonical_json(registration) + b"\n"
    registry.put_if_absent(f"{prefix}/registration.json", content, hashlib.sha256(content).hexdigest())
    return registration


def fetch(registry: Registry, release_id: str, bundle_digest: str, destination: Path) -> Path:
    require(re.fullmatch(r"rel-v1-[0-9a-f]{32}", release_id) is not None, "bad release ID")
    require(re.fullmatch(r"sha256:[0-9a-f]{64}", bundle_digest) is not None, "bad bundle digest")
    if destination.exists():
        require(destination.is_dir() and not destination.is_symlink(), "existing candidate destination is unsafe")
        existing, _ = validate_candidate(destination)
        require(
            existing["releaseId"] == release_id and existing["deploymentBundle"]["digest"] == bundle_digest,
            "immutable candidate destination collision",
        )
        return destination
    prefix = f"staging/releases/{release_id}/{bundle_digest.removeprefix('sha256:')}"
    marker = registry.get_required(f"{prefix}/registration.json")
    try:
        registration = json.loads(marker)
    except json.JSONDecodeError as exc:
        raise InvariantError("invalid registration marker") from exc
    registration_keys = {
        "schemaVersion",
        "environment",
        "releaseId",
        "bundleDigest",
        "objects",
    }
    require(
        isinstance(registration, dict) and set(registration) == registration_keys,
        "registration marker schema mismatch",
    )
    require(
        type(registration["schemaVersion"]) is int and registration["schemaVersion"] == 1,
        "registration marker version mismatch",
    )
    require(
        registration["environment"] == "staging",
        "registration marker environment mismatch",
    )
    require(registration.get("releaseId") == release_id and registration.get("bundleDigest") == bundle_digest, "registration identity mismatch")
    objects = registration.get("objects")
    require(isinstance(objects, list) and len(objects) == len(OBJECT_FILES), "registration object inventory mismatch")
    staging = destination.parent / f".{destination.name}.{os.getpid()}.staging"
    require(not staging.exists(), "staging download path already exists")
    staging.mkdir(parents=True, mode=0o700)
    try:
        for item in objects:
            require(isinstance(item, dict) and set(item) == {"key", "sha256", "sizeBytes"}, "bad registration object")
            require(str(item["key"]).startswith(prefix + "/"), "registration key escapes release prefix")
            name = str(item["key"]).rsplit("/", 1)[-1]
            require(name in OBJECT_FILES, "unexpected release object")
            content = registry.get_required(item["key"])
            require(len(content) == item["sizeBytes"] and hashlib.sha256(content).hexdigest() == item["sha256"], "downloaded object checksum mismatch")
            (staging / name).write_bytes(content)
        manifest, _ = validate_candidate(staging)
        require(manifest["releaseId"] == release_id, "downloaded release ID mismatch")
        os.replace(staging, destination)
        return destination
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("register", "fetch"))
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--region", default="us-east-1")
    parser.add_argument("--candidate-dir", type=Path)
    parser.add_argument("--release-id")
    parser.add_argument("--bundle-digest")
    parser.add_argument("--destination", type=Path)
    args = parser.parse_args()
    registry = AwsCliRegistry(args.bucket, args.region)
    if args.command == "register":
        require(args.candidate_dir is not None, "candidate directory required")
        result = register(registry, args.candidate_dir)
        print(f"RELEASE_REGISTRATION_OK release_id={result['releaseId']} bundle={result['bundleDigest']}")
    else:
        require(args.release_id and args.bundle_digest and args.destination, "fetch arguments required")
        fetch(registry, args.release_id, args.bundle_digest, args.destination)
        print(f"RELEASE_FETCH_OK release_id={args.release_id}")


if __name__ == "__main__":
    try:
        main()
    except (InvariantError, OSError, json.JSONDecodeError) as exc:
        print(f"ERROR_RELEASE_REGISTRY={type(exc).__name__}", file=sys.stderr)
        raise SystemExit(1)
