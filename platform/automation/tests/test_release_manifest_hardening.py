from __future__ import annotations

import copy
import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
RELEASE = ROOT / "platform" / "release" / "release.py"


def load_release():
    spec = importlib.util.spec_from_file_location("trinyx_release_manifest", RELEASE)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def image() -> dict[str, str]:
    digest = "sha256:" + "a" * 64
    return {
        "name": "cloud-test",
        "role": "cloud",
        "service": "test",
        "package": "ghcr.io/trinyxai/test",
        "environment": "TRINYX_CLOUD_TEST_IMAGE",
        "digest": digest,
        "immutableRef": "ghcr.io/trinyxai/test@" + digest,
    }


def bundle() -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "format": "tar",
        "digest": "sha256:" + "b" * 64,
        "sizeBytes": 1,
        "files": [
            {
                "path": "docker-compose.yml",
                "digest": "sha256:" + "c" * 64,
                "sizeBytes": 0,
                "mode": 0o644,
            }
        ],
    }


def manifest(release):
    images = release.normalize_images({"images": [image()]})
    deployment_bundle = release.normalize_bundle(bundle())
    value = {
        "schemaVersion": 1,
        "releaseId": "",
        "sourceCommit": "d" * 40,
        "sourceRef": "refs/heads/main",
        "platformCommit": "e" * 40,
        "createdAt": "2026-09-04T00:00:00Z",
        "deploymentBundle": deployment_bundle,
        "images": images,
    }
    value["releaseId"] = release.calculate_release_id(value)
    return value


class ReleaseManifestHardeningTests(unittest.TestCase):
    def test_modern_bundle_and_manifest_are_valid(self) -> None:
        release = load_release()
        self.assertEqual(manifest(release), release.validate_manifest(manifest(release)))

    def test_rejects_boolean_schema_versions_and_non_utc_timestamp(self) -> None:
        release = load_release()
        value = manifest(release)
        value["schemaVersion"] = True
        with self.assertRaisesRegex(SystemExit, "unsupported schemaVersion"):
            release.validate_manifest(value)
        bad_bundle = bundle()
        bad_bundle["schemaVersion"] = True
        with self.assertRaisesRegex(SystemExit, "schemaVersion"):
            release.normalize_bundle(bad_bundle)
        value = manifest(release)
        value["createdAt"] = "2026-09-04T00:00:00+00:00"
        with self.assertRaisesRegex(SystemExit, "UTC RFC3339"):
            release.validate_manifest(value)

    def test_rejects_coercive_image_and_bundle_file_shapes(self) -> None:
        release = load_release()
        invalid_image = image()
        invalid_image["name"] = 1  # type: ignore[assignment]
        with self.assertRaisesRegex(SystemExit, "values must be strings"):
            release.normalize_images({"images": [invalid_image]})

        for mutate in (
            lambda doc: doc["files"][0].pop("mode"),
            lambda doc: doc["files"][0].__setitem__("mode", True),
            lambda doc: doc["files"][0].__setitem__("path", "../escape"),
            lambda doc: doc["files"][0].__setitem__("path", "."),
            lambda doc: doc["files"].append(
                {
                    "path": "docker-compose.yml/child",
                    "digest": "sha256:" + "f" * 64,
                    "sizeBytes": 0,
                    "mode": 0o644,
                }
            ),
        ):
            document = copy.deepcopy(bundle())
            mutate(document)
            with self.assertRaises(SystemExit):
                release.normalize_bundle(document)


if __name__ == "__main__":
    unittest.main()
