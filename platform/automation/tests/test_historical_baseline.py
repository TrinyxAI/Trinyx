from __future__ import annotations

import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "platform" / "release"))

import historical_baseline as hb


def run_fixture(run_id: int, path: str, *, cloud: bool) -> dict:
    return {
        "id": run_id,
        "head_sha": hb.SOURCE_COMMIT,
        "conclusion": "success",
        "path": path,
        "repository": {"id": hb.REPOSITORY_ID, "full_name": hb.REPOSITORY,
                       "owner": {"id": hb.OWNER_ID}},
        "head_repository": {"id": hb.REPOSITORY_ID, "full_name": hb.REPOSITORY,
                            "owner": {"id": hb.OWNER_ID}},
        "referenced_workflows": ([{
            "path": hb.CLOUD_WORKFLOW,
            "sha": hb.SOURCE_COMMIT,
        }] if cloud else []),
    }


def artifact_fixture() -> dict:
    return {
        "id": hb.CLOUD_ARTIFACT_ID,
        "name": hb.CLOUD_ARTIFACT_NAME,
        "expired": False,
        "digest": hb.CLOUD_ARTIFACT_DIGEST,
        "workflow_run": {
            "id": hb.BACKEND_RUN_ID,
            "repository_id": hb.REPOSITORY_ID,
            "head_repository_id": hb.REPOSITORY_ID,
            "head_sha": hb.SOURCE_COMMIT,
        },
    }


def paid_fixture(package: str) -> dict:
    return {
        "package": package,
        "tag": f"{package}:{hb.SOURCE_COMMIT}",
        "digest": "sha256:" + "b" * 64,
        "labels": {
            "org.opencontainers.image.source": f"https://github.com/{hb.REPOSITORY}",
            "org.opencontainers.image.revision": hb.SOURCE_COMMIT,
        },
    }


class HistoricalBaselineTests(unittest.TestCase):
    def test_exact_historical_runs_are_accepted(self) -> None:
        hb.validate_run(run_fixture(hb.BACKEND_RUN_ID, hb.BACKEND_WORKFLOW, cloud=True),
                        run_id=hb.BACKEND_RUN_ID, workflow=hb.BACKEND_WORKFLOW,
                        cloud_reusable=True)
        hb.validate_run(run_fixture(hb.FRONTEND_RUN_ID, hb.FRONTEND_WORKFLOW, cloud=False),
                        run_id=hb.FRONTEND_RUN_ID, workflow=hb.FRONTEND_WORKFLOW,
                        cloud_reusable=False)

    def test_historical_run_identity_drift_fails_closed(self) -> None:
        base = run_fixture(hb.BACKEND_RUN_ID, hb.BACKEND_WORKFLOW, cloud=True)
        mutations = [
            ("id", hb.BACKEND_RUN_ID + 1),
            ("head_sha", "f" * 40),
            ("conclusion", "failure"),
            ("path", ".github/workflows/other.yml"),
        ]
        for key, value in mutations:
            with self.subTest(key=key):
                item = copy.deepcopy(base)
                item[key] = value
                with self.assertRaises(ValueError):
                    hb.validate_run(item, run_id=hb.BACKEND_RUN_ID,
                                    workflow=hb.BACKEND_WORKFLOW, cloud_reusable=True)
        for repository_key in ("repository", "head_repository"):
            item = copy.deepcopy(base)
            item[repository_key]["id"] += 1
            with self.assertRaises(ValueError):
                hb.validate_run(item, run_id=hb.BACKEND_RUN_ID,
                                workflow=hb.BACKEND_WORKFLOW, cloud_reusable=True)
            item = copy.deepcopy(base)
            item[repository_key]["owner"]["id"] += 1
            with self.assertRaises(ValueError):
                hb.validate_run(item, run_id=hb.BACKEND_RUN_ID,
                                workflow=hb.BACKEND_WORKFLOW, cloud_reusable=True)

    def test_artifact_identity_and_binding_are_exact(self) -> None:
        hb.validate_artifact(artifact_fixture())
        for path, value in (
            (("id",), hb.CLOUD_ARTIFACT_ID + 1),
            (("name",), "similar-latest"),
            (("digest",), "sha256:" + "a" * 64),
            (("workflow_run", "id"), hb.BACKEND_RUN_ID + 1),
            (("workflow_run", "head_sha"), "f" * 40),
        ):
            item = copy.deepcopy(artifact_fixture())
            target = item
            for key in path[:-1]:
                target = target[key]
            target[path[-1]] = value
            with self.assertRaises(ValueError):
                hb.validate_artifact(item)

    def test_cloud_manifest_requires_exact_commit_and_14_unique_services(self) -> None:
        inventory = json.loads((ROOT / "platform/release/runtime-inventory.json").read_text())
        app_names = [
            item["name"] for item in inventory["images"]
            if item["role"] == "cloud" and item["name"] not in {
                "cloud-postgres", "cloud-redis", "cloud-minio", "cloud-minio-init",
                "cloud-searxng", "cloud-edge",
            }
        ]
        manifest = {"schemaVersion": 1, "commit": hb.SOURCE_COMMIT,
                    "images": [{"name": name} for name in app_names]}
        hb.validate_cloud_manifest(manifest, inventory)
        wrong = copy.deepcopy(manifest)
        wrong["commit"] = "f" * 40
        with self.assertRaises(ValueError):
            hb.validate_cloud_manifest(wrong, inventory)
        duplicate = copy.deepcopy(manifest)
        duplicate["images"][-1] = duplicate["images"][0]
        with self.assertRaises(ValueError):
            hb.validate_cloud_manifest(duplicate, inventory)

    def test_paid_tag_digest_and_oci_revision_are_strict(self) -> None:
        package = "ghcr.io/trinyxai/trinyx-backend"
        result = hb.paid_manifest(paid_fixture(package), name="paid-backend",
                                  service="livecontext", environment="BACKEND_IMAGE",
                                  package=package)
        self.assertEqual(hb.SOURCE_COMMIT, result["commit"])
        for key, value in (("tag", package + ":latest"), ("digest", "mutable")):
            item = paid_fixture(package)
            item[key] = value
            with self.assertRaises(ValueError):
                hb.paid_manifest(item, name="paid-backend", service="livecontext",
                                 environment="BACKEND_IMAGE", package=package)
        item = paid_fixture(package)
        item["labels"]["org.opencontainers.image.revision"] = "f" * 40
        with self.assertRaises(ValueError):
            hb.paid_manifest(item, name="paid-backend", service="livecontext",
                             environment="BACKEND_IMAGE", package=package)

    def test_workflow_is_metadata_only_and_release_id_is_not_supplied(self) -> None:
        wrapper = (ROOT / ".github/workflows/build-historical-staging-baseline.yml").read_text()
        workflow = (ROOT / ".github/workflows/build-historical-staging-baseline-impl.yml").read_text()
        self.assertIn("uses: ./.github/workflows/build-historical-staging-baseline-impl.yml", wrapper)
        forbidden = (
            "docker build ", "docker buildx build", "docker/build-push-action",
            "imagetools create", "packages: write", "docker push", "--release-id",
        )
        for token in forbidden:
            self.assertNotIn(token, workflow)
        self.assertIn("docker buildx imagetools inspect", workflow)
        self.assertIn("platform/release/release.py create", workflow)
        self.assertIn("--repo historical-source", workflow)
        self.assertIn("actions/attest-build-provenance@", workflow)

    def test_release_id_is_generated_and_validated_by_canonical_tool(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            images = {"images": [{
                "name": "paid-backend", "role": "paid", "service": "livecontext",
                "package": "ghcr.io/trinyxai/trinyx-backend", "environment": "BACKEND_IMAGE",
                "digest": "sha256:" + "b" * 64,
                "immutableRef": "ghcr.io/trinyxai/trinyx-backend@sha256:" + "b" * 64,
            }]}
            bundle = {
                "schemaVersion": 1, "format": "tar", "digest": "sha256:" + "c" * 64,
                "sizeBytes": 1,
                "files": [{"path": "x", "digest": "sha256:" + "d" * 64,
                           "sizeBytes": 1, "mode": 420}],
            }
            (root / "images.json").write_text(json.dumps(images))
            (root / "bundle.json").write_text(json.dumps(bundle))
            release = root / "release.json"
            script = ROOT / "platform/release/release.py"
            subprocess.run([
                sys.executable, str(script), "create", "--source-commit", hb.SOURCE_COMMIT,
                "--source-ref", "refs/heads/codex/trinyx-cloud-gateway-v2",
                "--platform-commit", "e" * 40, "--images", str(root / "images.json"),
                "--bundle-manifest", str(root / "bundle.json"), "--out", str(release),
            ], check=True, capture_output=True, text=True)
            document = json.loads(release.read_text())
            self.assertRegex(document["releaseId"], r"^rel-v1-[0-9a-f]{32}$")
            subprocess.run([sys.executable, str(script), "validate", "--manifest", str(release)],
                           check=True, capture_output=True, text=True)


if __name__ == "__main__":
    unittest.main()
