from __future__ import annotations

import io
import json
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
from typing import Any
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from helpers import make_release, write_json
from invariants import InvariantError, calculated_release_id, sha256_bytes
from release_registry import FROZEN_CANDIDATE, OBJECT_FILES, fetch, register


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


def make_legacy_candidate(
    directory: Path,
    *,
    tar_mode: int = 0o644,
) -> tuple[str, str, dict[str, str]]:
    make_candidate(directory)
    source_tar = directory / "deployment-bundle.tar"
    rebuilt = io.BytesIO()
    with (
        tarfile.open(source_tar, mode="r") as source,
        tarfile.open(fileobj=rebuilt, mode="w", format=tarfile.USTAR_FORMAT) as target,
    ):
        for member in source.getmembers():
            content = source.extractfile(member)
            if content is None:
                raise AssertionError("fixture bundle member is not a regular file")
            payload = content.read()
            info = tarfile.TarInfo(member.name)
            info.size = len(payload)
            info.mode = tar_mode
            info.mtime = 0
            target.addfile(info, io.BytesIO(payload))
    tar_bytes = rebuilt.getvalue()

    bundle_manifest_path = directory / "deployment-bundle.json"
    bundle_manifest = json.loads(bundle_manifest_path.read_text())
    for entry in bundle_manifest["files"]:
        del entry["mode"]
    bundle_manifest["digest"] = sha256_bytes(tar_bytes)
    bundle_manifest["sizeBytes"] = len(tar_bytes)
    write_json(bundle_manifest_path, bundle_manifest)
    source_tar.write_bytes(tar_bytes)

    release_path = directory / "release.json"
    release = json.loads(release_path.read_text())
    release["deploymentBundle"]["digest"] = bundle_manifest["digest"]
    release["deploymentBundle"]["sizeBytes"] = bundle_manifest["sizeBytes"]
    release["releaseId"] = ""
    release["releaseId"] = calculated_release_id(release)
    write_json(release_path, release)

    provenance_path = directory / "provenance.json"
    provenance = json.loads(provenance_path.read_text())
    provenance.update(
        {
            "signerWorkflow": "build-release-candidate.yml",
            "signerDigest": release["sourceCommit"],
            "compatibility": "frozen-historical-builder",
            "sourceCommit": release["sourceCommit"],
        }
    )
    write_json(provenance_path, provenance)

    frozen = {
        "sourceCommit": release["sourceCommit"],
        "releaseId": release["releaseId"],
        "bundleDigest": bundle_manifest["digest"],
        "artifactId": str(provenance["artifactId"]),
        "runId": str(provenance["runId"]),
        "artifactDigest": provenance["artifactDigest"],
        "releaseManifestDigest": sha256_bytes(release_path.read_bytes()),
        "imageInventoryDigest": sha256_bytes(
            (directory / "release-images.json").read_bytes()
        ),
        "bundleManifestDigest": sha256_bytes(bundle_manifest_path.read_bytes()),
    }
    return release["releaseId"], bundle_manifest["digest"], frozen


class RegistryTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.candidate = Path(self.temp.name) / "candidate"
        self.candidate.mkdir()
        self.release_id, self.bundle_digest = make_candidate(self.candidate)
        self.registry = MemoryRegistry()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _registered_marker(self) -> tuple[MemoryRegistry, str, dict[str, Any]]:
        registry = MemoryRegistry()
        registration = register(registry, self.candidate)
        marker_key = (
            f"staging/releases/{self.release_id}/"
            f"{self.bundle_digest.removeprefix('sha256:')}/registration.json"
        )
        marker = json.loads(registry.objects[marker_key])
        self.assertEqual(registration, marker)
        return registry, marker_key, marker

    @staticmethod
    def _replace_marker(
        registry: MemoryRegistry,
        marker_key: str,
        marker: dict[str, Any],
    ) -> None:
        registry.objects[marker_key] = (
            json.dumps(marker, sort_keys=True, separators=(",", ":")).encode() + b"\n"
        )

    def _fetch_destination(self, suffix: str) -> Path:
        return Path(self.temp.name) / f"downloaded-{suffix}"

    def test_register_fetch_happy_path_and_idempotence(self) -> None:
        first = register(self.registry, self.candidate)
        second = register(self.registry, self.candidate)
        self.assertEqual(first, second)
        destination = Path(self.temp.name) / "downloaded"
        fetch(self.registry, self.release_id, self.bundle_digest, destination)
        self.assertEqual({*OBJECT_FILES}, {path.name for path in destination.iterdir()})

    def test_fetch_registration_marker_schema_is_closed(self) -> None:
        expected_keys = {
            "schemaVersion",
            "environment",
            "releaseId",
            "bundleDigest",
            "objects",
        }
        registry, marker_key, marker = self._registered_marker()
        self.assertEqual(expected_keys, set(marker))
        fetch(
            registry,
            self.release_id,
            self.bundle_digest,
            self._fetch_destination("valid-marker"),
        )

        for missing_key in sorted(expected_keys):
            with self.subTest(missing_key=missing_key):
                registry, marker_key, marker = self._registered_marker()
                del marker[missing_key]
                self._replace_marker(registry, marker_key, marker)
                with self.assertRaisesRegex(
                    InvariantError,
                    "registration marker schema mismatch",
                ):
                    fetch(
                        registry,
                        self.release_id,
                        self.bundle_digest,
                        self._fetch_destination(f"missing-{missing_key}"),
                    )

        registry, marker_key, marker = self._registered_marker()
        marker["unexpected"] = "not-allowed"
        self._replace_marker(registry, marker_key, marker)
        with self.assertRaisesRegex(
            InvariantError,
            "registration marker schema mismatch",
        ):
            fetch(
                registry,
                self.release_id,
                self.bundle_digest,
                self._fetch_destination("extra-key"),
            )

    def test_fetch_registration_marker_root_must_be_object(self) -> None:
        for index, root in enumerate((None, [], "registration", 1, True)):
            with self.subTest(root=root):
                registry, marker_key, _ = self._registered_marker()
                registry.objects[marker_key] = json.dumps(root).encode() + b"\n"
                with self.assertRaisesRegex(
                    InvariantError,
                    "registration marker schema mismatch",
                ):
                    fetch(
                        registry,
                        self.release_id,
                        self.bundle_digest,
                        self._fetch_destination(f"non-object-root-{index}"),
                    )

    def test_fetch_registration_marker_version_and_environment_are_exact(self) -> None:
        for version in (2, 0, "1", True, None):
            with self.subTest(schemaVersion=version):
                registry, marker_key, marker = self._registered_marker()
                marker["schemaVersion"] = version
                self._replace_marker(registry, marker_key, marker)
                with self.assertRaisesRegex(
                    InvariantError,
                    "registration marker version mismatch",
                ):
                    fetch(
                        registry,
                        self.release_id,
                        self.bundle_digest,
                        self._fetch_destination(
                            f"version-{type(version).__name__}-{version}"
                        ),
                    )

        for environment in ("prod", "STAGING", "", None):
            with self.subTest(environment=environment):
                registry, marker_key, marker = self._registered_marker()
                marker["environment"] = environment
                self._replace_marker(registry, marker_key, marker)
                with self.assertRaisesRegex(
                    InvariantError,
                    "registration marker environment mismatch",
                ):
                    fetch(
                        registry,
                        self.release_id,
                        self.bundle_digest,
                        self._fetch_destination(
                            f"environment-{type(environment).__name__}-{environment}"
                        ),
                    )

    def test_fetch_retains_registration_identity_and_object_guards(self) -> None:
        for field, value in (
            ("releaseId", "rel-v1-" + "0" * 32),
            ("bundleDigest", "sha256:" + "0" * 64),
        ):
            with self.subTest(identity=field):
                registry, marker_key, marker = self._registered_marker()
                marker[field] = value
                self._replace_marker(registry, marker_key, marker)
                with self.assertRaisesRegex(
                    InvariantError,
                    "registration identity mismatch",
                ):
                    fetch(
                        registry,
                        self.release_id,
                        self.bundle_digest,
                        self._fetch_destination(f"identity-{field}"),
                    )

        for objects in ({}, [], [None] * len(OBJECT_FILES)):
            with self.subTest(objects=type(objects).__name__):
                registry, marker_key, marker = self._registered_marker()
                marker["objects"] = objects
                self._replace_marker(registry, marker_key, marker)
                expected_error = (
                    "bad registration object"
                    if isinstance(objects, list) and len(objects) == len(OBJECT_FILES)
                    else "registration object inventory mismatch"
                )
                with self.assertRaisesRegex(InvariantError, expected_error):
                    fetch(
                        registry,
                        self.release_id,
                        self.bundle_digest,
                        self._fetch_destination(
                            f"objects-{type(objects).__name__}-{len(objects)}"
                        ),
                    )

        for field, value, error in (
            ("sha256", "0" * 64, "downloaded object checksum mismatch"),
            ("sizeBytes", 0, "downloaded object checksum mismatch"),
            ("key", "staging/releases/escape/release.json", "registration key escapes release prefix"),
        ):
            with self.subTest(object_field=field):
                registry, marker_key, marker = self._registered_marker()
                marker["objects"][0][field] = value
                self._replace_marker(registry, marker_key, marker)
                with self.assertRaisesRegex(InvariantError, error):
                    fetch(
                        registry,
                        self.release_id,
                        self.bundle_digest,
                        self._fetch_destination(f"object-{field}"),
                    )

    def test_fetch_rejects_candidate_that_is_invalid_after_verified_download(self) -> None:
        registry, marker_key, marker = self._registered_marker()
        release_item = next(
            item for item in marker["objects"] if item["key"].endswith("/release.json")
        )
        tampered_release = json.loads(registry.objects[release_item["key"]])
        tampered_release["environment"] = "production"
        tampered_bytes = (
            json.dumps(tampered_release, sort_keys=True, separators=(",", ":")).encode()
            + b"\n"
        )
        registry.objects[release_item["key"]] = tampered_bytes
        release_item["sizeBytes"] = len(tampered_bytes)
        release_item["sha256"] = sha256_bytes(tampered_bytes).removeprefix("sha256:")
        self._replace_marker(registry, marker_key, marker)
        with self.assertRaises(InvariantError):
            fetch(
                registry,
                self.release_id,
                self.bundle_digest,
                self._fetch_destination("invalid-candidate"),
            )

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

    def test_modern_bundle_manifest_requires_mode(self) -> None:
        bundle_manifest_path = self.candidate / "deployment-bundle.json"
        bundle_manifest = json.loads(bundle_manifest_path.read_text())
        del bundle_manifest["files"][0]["mode"]
        write_json(bundle_manifest_path, bundle_manifest)
        with self.assertRaisesRegex(InvariantError, "bad bundle file contract"):
            register(self.registry, self.candidate)

    def test_historical_legacy_schema_round_trip_preserves_original_bytes(self) -> None:
        self.assertEqual(
            "sha256:ad5a5b702d9659e0af5d5b82a422953ba2390a94396949f897757568c9b59789",
            FROZEN_CANDIDATE["releaseManifestDigest"],
        )
        self.assertEqual(
            "sha256:fe1134c3920af0f2f9f0027082f25ec5adb1cbb6d41a1053bada7ef730f66a8a",
            FROZEN_CANDIDATE["imageInventoryDigest"],
        )
        self.assertEqual(
            "sha256:b101918414ee9d113d4ef54d32c9f438005d8ebee7bde2e62f72d58a16cfdd7b",
            FROZEN_CANDIDATE["bundleManifestDigest"],
        )
        self.assertEqual(
            "sha256:c9df14dcd1dbc24b31b926d3778bef2e208b59824c78f24292608284f3579892",
            FROZEN_CANDIDATE["bundleDigest"],
        )

        candidate = Path(self.temp.name) / "legacy"
        candidate.mkdir()
        release_id, bundle_digest, frozen = make_legacy_candidate(candidate)
        original_manifest = (candidate / "deployment-bundle.json").read_bytes()
        original_tar = (candidate / "deployment-bundle.tar").read_bytes()
        registry = MemoryRegistry()
        with patch.dict(FROZEN_CANDIDATE, frozen, clear=True):
            first = register(registry, candidate)
            second = register(registry, candidate)
            self.assertEqual(first, second)
            destination = Path(self.temp.name) / "legacy-downloaded"
            fetch(registry, release_id, bundle_digest, destination)

        self.assertEqual(
            frozen["bundleManifestDigest"],
            sha256_bytes(original_manifest),
        )
        self.assertEqual(
            original_manifest,
            (destination / "deployment-bundle.json").read_bytes(),
        )
        self.assertEqual(original_tar, (destination / "deployment-bundle.tar").read_bytes())

    def test_historical_legacy_schema_requires_tar_mode_0644(self) -> None:
        candidate = Path(self.temp.name) / "legacy-wrong-mode"
        candidate.mkdir()
        _, _, frozen = make_legacy_candidate(candidate, tar_mode=0o600)
        with (
            patch.dict(FROZEN_CANDIDATE, frozen, clear=True),
            self.assertRaisesRegex(InvariantError, "size/mode mismatch"),
        ):
            register(MemoryRegistry(), candidate)

    def test_legacy_schema_is_rejected_for_non_historical_candidate(self) -> None:
        candidate = Path(self.temp.name) / "legacy-non-frozen"
        candidate.mkdir()
        make_legacy_candidate(candidate)
        with self.assertRaisesRegex(
            InvariantError,
            "restricted to the frozen candidate",
        ):
            register(MemoryRegistry(), candidate)

    def test_historical_legacy_schema_still_checks_path_size_and_digest(self) -> None:
        cases = (
            ("path", "unexpected.txt", "unsafe/unexpected bundle member"),
            ("sizeBytes", 999999, "size/mode mismatch"),
            ("digest", "sha256:" + "0" * 64, "internal file hash"),
        )
        for field, value, error in cases:
            with self.subTest(field=field):
                candidate = Path(self.temp.name) / f"legacy-bad-{field}"
                candidate.mkdir()
                _, _, frozen = make_legacy_candidate(candidate)
                bundle_manifest_path = candidate / "deployment-bundle.json"
                bundle_manifest = json.loads(bundle_manifest_path.read_text())
                bundle_manifest["files"][0][field] = value
                write_json(bundle_manifest_path, bundle_manifest)
                frozen["bundleManifestDigest"] = sha256_bytes(
                    bundle_manifest_path.read_bytes()
                )
                with (
                    patch.dict(FROZEN_CANDIDATE, frozen, clear=True),
                    self.assertRaisesRegex(InvariantError, error),
                ):
                    register(MemoryRegistry(), candidate)

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
