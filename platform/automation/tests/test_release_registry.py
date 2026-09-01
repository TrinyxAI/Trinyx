from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from helpers import make_release, write_json
from invariants import InvariantError
from release_registry import OBJECT_FILES, fetch, register


class MemoryRegistry:
    def __init__(self) -> None:
        self.objects: dict[str, bytes] = {}
        self.denied = False

    def put_if_absent(self, key: str, content: bytes, sha256_hex: str) -> str:
        if self.denied:
            raise InvariantError("S3 access denied or unavailable")
        if key in self.objects:
            import hashlib

            if hashlib.sha256(self.objects[key]).hexdigest() != sha256_hex:
                raise InvariantError("immutable S3 object collision")
            return "EXISTS_MATCHING"
        self.objects[key] = content
        return "CREATED"

    def get_required(self, key: str) -> bytes:
        if self.denied:
            raise InvariantError("S3 access denied or unavailable")
        if key not in self.objects:
            raise InvariantError("required S3 object missing")
        return self.objects[key]


def make_candidate(directory: Path) -> tuple[str, str]:
    host = directory / "host"
    host.mkdir()
    release_id, release_dir, manifest = make_release(host, "paid", 7)
    (directory / "release.json").write_bytes((release_dir / "manifest.json").read_bytes())
    write_json(directory / "release-images.json", {"images": manifest["images"]})
    (directory / "deployment-bundle.json").write_bytes((release_dir / "deployment-bundle.json").read_bytes())
    (directory / "deployment-bundle.tar").write_bytes((release_dir / "deployment-bundle.tar").read_bytes())
    write_json(
        directory / "provenance.json",
        {
            "schemaVersion": 2,
            "repository": "TrinyxAI/Trinyx",
            "signerWorkflow": "build-release-candidate-impl.yml",
            "signerDigest": "114a2613e8090f034925a1bcf148f055653c3a06",
            "compatibility": "pinned-reusable-builder",
            "sourceCommit": manifest["sourceCommit"],
            "artifactId": "9791964215",
            "runId": "33485509832",
            "artifactDigest": "sha256:" + "7" * 64,
            "verifiedAt": "2026-09-01T00:00:00Z",
        },
    )
    return release_id, manifest["deploymentBundle"]["digest"]


class RegistryTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.candidate = Path(self.temp.name) / "candidate"
        self.candidate.mkdir()
        self.release_id, self.bundle_digest = make_candidate(self.candidate)
        self.registry = MemoryRegistry()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_register_fetch_happy_path_and_idempotence(self) -> None:
        first = register(self.registry, self.candidate)
        second = register(self.registry, self.candidate)
        self.assertEqual(first, second)
        destination = Path(self.temp.name) / "downloaded"
        fetch(self.registry, self.release_id, self.bundle_digest, destination)
        self.assertEqual({*OBJECT_FILES}, {path.name for path in destination.iterdir()})

    def test_missing_s3_object(self) -> None:
        registration = register(self.registry, self.candidate)
        del self.registry.objects[registration["objects"][0]["key"]]
        with self.assertRaisesRegex(InvariantError, "missing"):
            fetch(self.registry, self.release_id, self.bundle_digest, Path(self.temp.name) / "downloaded")

    def test_s3_access_denied(self) -> None:
        self.registry.denied = True
        with self.assertRaisesRegex(InvariantError, "access denied"):
            register(self.registry, self.candidate)

    def test_immutable_object_collision(self) -> None:
        registration = register(self.registry, self.candidate)
        self.registry.objects[registration["objects"][0]["key"]] = b"different"
        with self.assertRaisesRegex(InvariantError, "collision"):
            register(self.registry, self.candidate)

    def test_release_id_reservation_rejects_logical_overwrite(self) -> None:
        register(self.registry, self.candidate)
        key = f"staging/release-ids/{self.release_id}.json"
        self.registry.objects[key] = b'{"different":true}\n'
        with self.assertRaisesRegex(InvariantError, "collision"):
            register(self.registry, self.candidate)

    def test_historical_signer_is_rejected_for_any_non_frozen_candidate(self) -> None:
        provenance_path = self.candidate / "provenance.json"
        provenance = json.loads(provenance_path.read_text())
        provenance["signerWorkflow"] = "build-release-candidate.yml"
        provenance["signerDigest"] = provenance["sourceCommit"]
        provenance["compatibility"] = "frozen-historical-builder"
        write_json(provenance_path, provenance)
        with self.assertRaisesRegex(InvariantError, "restricted to the frozen candidate"):
            register(self.registry, self.candidate)

    def test_wrong_sha_tampered_bundle_and_internal_hash(self) -> None:
        bundle = self.candidate / "deployment-bundle.tar"
        bundle.write_bytes(bundle.read_bytes() + b"tamper")
        with self.assertRaisesRegex(InvariantError, "wrong bundle SHA"):
            register(self.registry, self.candidate)
        self.candidate = Path(self.temp.name) / "candidate2"
        self.candidate.mkdir()
        make_candidate(self.candidate)
        bundle_manifest = json.loads((self.candidate / "deployment-bundle.json").read_text())
        bundle_manifest["files"][0]["digest"] = "sha256:" + "0" * 64
        write_json(self.candidate / "deployment-bundle.json", bundle_manifest)
        with self.assertRaisesRegex(InvariantError, "internal file hash"):
            register(self.registry, self.candidate)


if __name__ == "__main__":
    unittest.main()
