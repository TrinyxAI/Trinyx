from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from copy import deepcopy
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from invariants import (
    InvariantError,
    _validate_installed_bundle_tree,
    calculated_release_id,
    forbid_global_compose_apply,
    validate_active_pointer,
    validate_compose_model,
    validate_deployment_record,
    validate_release_directory,
)
from helpers import make_compose_model, make_host, write_json


class InvariantTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name) / "etc" / "trinyx" / "staging" / "paid"
        self.base.mkdir(parents=True)
        self.releases, self.models = make_host(self.base, "paid", create_active=False)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_happy_path(self) -> None:
        validate_release_directory(self.base / "releases" / self.releases[0], "paid")

    def test_validate_release_rejects_owner_writable_bundle_root(self) -> None:
        release = self.base / "releases" / self.releases[0]
        bundle_root = release / "bundle"
        os.chmod(bundle_root, 0o755)
        with self.assertRaisesRegex(
            InvariantError, "installed bundle directory mode mismatch"
        ):
            validate_release_directory(release, "paid")

    def test_installed_bundle_tree_rejects_owner_writable_nested_directory(self) -> None:
        bundle_root = Path(self.temp.name) / "nested-bundle"
        nested = bundle_root / "nested"
        nested.mkdir(parents=True)
        member = nested / "member.txt"
        member.write_text("immutable\n", encoding="utf-8")
        os.chmod(member, 0o444)
        os.chmod(nested, 0o555)
        os.chmod(bundle_root, 0o555)
        _validate_installed_bundle_tree(bundle_root, {"nested/member.txt"})
        os.chmod(nested, 0o755)
        with self.assertRaisesRegex(
            InvariantError, "installed bundle directory mode mismatch"
        ):
            _validate_installed_bundle_tree(bundle_root, {"nested/member.txt"})

    @unittest.skipIf(os.name == "nt", "Windows runner lacks unprivileged symlink support; Linux CI executes this")
    def test_active_pointer(self) -> None:
        os.symlink(f"releases/{self.releases[0]}", self.base / "active", target_is_directory=True)
        self.assertEqual(self.releases[0], validate_active_pointer(self.base, "paid"))

    def test_bad_release_id(self) -> None:
        release = self.base / "releases" / self.releases[0]
        manifest = json.loads((release / "manifest.json").read_text())
        manifest["releaseId"] = "rel-v1-" + "0" * 32
        write_json(release / "manifest.json", manifest)
        with self.assertRaisesRegex(InvariantError, "release ID content hash"):
            validate_release_directory(release, "paid")

    def test_wrong_sha_and_tampered_bundle(self) -> None:
        release = self.base / "releases" / self.releases[0]
        bundle = release / "deployment-bundle.tar"
        bundle.write_bytes(bundle.read_bytes() + b"tampered")
        with self.assertRaisesRegex(InvariantError, "bundle size mismatch"):
            validate_release_directory(release, "paid")

    def test_bad_internal_file_hash(self) -> None:
        release = self.base / "releases" / self.releases[0]
        manifest_path = release / "deployment-bundle.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["files"][0]["digest"] = "sha256:" + "0" * 64
        write_json(manifest_path, manifest)
        with self.assertRaisesRegex(InvariantError, "internal bundle file hash"):
            validate_release_directory(release, "paid")

    def test_rejects_boolean_schema_and_extra_immutable_tree_entries(self) -> None:
        release = self.base / "releases" / self.releases[0]
        manifest_path = release / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["schemaVersion"] = True
        write_json(manifest_path, manifest)
        with self.assertRaisesRegex(InvariantError, "unsupported release schema"):
            validate_release_directory(release, "paid")

        manifest["schemaVersion"] = 1
        manifest["createdAt"] = "2026-99-01T00:00:00Z"
        write_json(manifest_path, manifest)
        with self.assertRaisesRegex(InvariantError, "createdAt is invalid"):
            validate_release_directory(release, "paid")
        manifest["createdAt"] = "2026-09-01T00:00:00Z"
        write_json(manifest_path, manifest)
        bundle_manifest_path = release / "deployment-bundle.json"
        bundle_manifest = json.loads(bundle_manifest_path.read_text())
        bundle_manifest["schemaVersion"] = True
        write_json(bundle_manifest_path, bundle_manifest)
        with self.assertRaisesRegex(InvariantError, "bad deployment bundle manifest"):
            validate_release_directory(release, "paid")

        bundle_manifest["schemaVersion"] = 1
        original_path = bundle_manifest["files"][0]["path"]
        bundle_manifest["files"][0]["path"] = "."
        write_json(bundle_manifest_path, bundle_manifest)
        with self.assertRaisesRegex(InvariantError, "unsafe bundle path"):
            validate_release_directory(release, "paid")
        bundle_manifest["files"][0]["path"] = original_path
        write_json(bundle_manifest_path, bundle_manifest)
        bundle_root = release / "bundle"
        os.chmod(bundle_root, 0o755)
        (bundle_root / "unexpected-empty-directory").mkdir()
        os.chmod(bundle_root, 0o555)
        with self.assertRaisesRegex(InvariantError, "directory tree mismatch"):
            validate_release_directory(release, "paid")

    def test_no_checkout_dependency_and_no_mutable_image(self) -> None:
        model = deepcopy(self.models[self.releases[0]])
        expected = {name: service["image"] for name, service in model["services"].items() if service.get("image")}
        model["services"]["livecontext"]["volumes"] = ["/srv/trinyx/pr25-old:/app"]
        with self.assertRaisesRegex(InvariantError, "checkout"):
            validate_compose_model(model, "paid", expected)
        model["services"]["livecontext"].pop("volumes")
        model["services"]["livecontext"]["image"] = "ghcr.io/trinyxai/livecontext:latest"
        with self.assertRaisesRegex(InvariantError, "mutable image"):
            validate_compose_model(model, "paid", expected)

    def test_paid_three_gib_liveness_and_caddy_invariants(self) -> None:
        model = deepcopy(self.models[self.releases[0]])
        expected = {name: service["image"] for name, service in model["services"].items() if service.get("image")}
        validate_compose_model(model, "paid", expected)
        model["services"]["livecontext"]["mem_limit"] = "2G"
        with self.assertRaisesRegex(InvariantError, "3 GiB"):
            validate_compose_model(model, "paid", expected)
        model = deepcopy(self.models[self.releases[0]])
        expected = {name: service["image"] for name, service in model["services"].items() if service.get("image")}
        model["services"]["livecontext"].pop("healthcheck")
        with self.assertRaisesRegex(InvariantError, "liveness"):
            validate_compose_model(model, "paid", expected)
        model = deepcopy(self.models[self.releases[0]])
        expected = {name: service["image"] for name, service in model["services"].items() if service.get("image")}
        model["services"]["paid-edge"]["image"] = "caddy:latest"
        with self.assertRaisesRegex(InvariantError, "mutable image"):
            validate_compose_model(model, "paid", expected)

    def test_no_global_compose_apply(self) -> None:
        path = Path(self.temp.name) / "bad.sh"
        path.write_text("docker compose -f compose.yml up -d\n", encoding="utf-8")
        with self.assertRaisesRegex(InvariantError, "global Compose"):
            forbid_global_compose_apply([path])

    def test_deployment_record_rejects_secret_logging(self) -> None:
        record = {
            "schemaVersion": 2,
            "deploymentId": "dep-" + "1" * 32,
            "environment": "staging",
            "releaseId": self.releases[0],
            "environmentConfigRevision": "rev-1",
            "controlPlaneCommit": "1" * 40,
            "previousCloudRelease": None,
            "previousPaidRelease": self.releases[0],
            "state": "FAILED",
            "createdAt": "2026-09-01T00:00:00Z",
            "startedAt": "2026-09-01T00:00:01Z",
            "completedAt": "2026-09-01T00:00:02Z",
            "failure": "password=do-not-log",
            "rollbackResult": None,
            "history": [{"state": "CREATED", "at": "2026-09-01T00:00:00Z"}],
        }
        with self.assertRaisesRegex(InvariantError, "secret-like"):
            validate_deployment_record(record)


if __name__ == "__main__":
    unittest.main()
