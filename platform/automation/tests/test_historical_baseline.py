from __future__ import annotations

import copy
import importlib.util
import os
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
FIXTURES = Path(__file__).resolve().parent / "fixtures"
sys.path.insert(0, str(ROOT / "platform" / "release"))

import historical_baseline as hb


BACKEND_HISTORICAL_DIGEST = hb.BACKEND_HISTORICAL_DIGEST
FRONTEND_HISTORICAL_DIGEST = hb.FRONTEND_HISTORICAL_DIGEST


def run_fixture(run_id: int, path: str, *, cloud: bool) -> dict:
    return {
        "id": run_id,
        "head_sha": hb.SOURCE_COMMIT,
        "head_branch": hb.SOURCE_BRANCH,
        "event": hb.HISTORICAL_EVENT,
        "created_at": "2026-08-31T22:06:00Z",
        "run_attempt": hb.HISTORICAL_RUN_ATTEMPT,
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


def job_fixture(job_id: int, run_id: int, name: str) -> dict:
    return {
        "id": job_id,
        "run_id": run_id,
        "head_sha": hb.SOURCE_COMMIT,
        "conclusion": "success",
        "name": name,
        "run_url": f"https://api.github.com/repos/{hb.REPOSITORY}/actions/runs/{run_id}",
    }


def publication_log(package: str, digest: str) -> str:
    if package == hb.BACKEND_PACKAGE:
        return (
            "2026-08-31T22:10:08.8525199Z #1 0.723 pushing "
            f"{digest} to {package}:{hb.SOURCE_COMMIT}\n"
        )
    if package == hb.FRONTEND_PACKAGE:
        return (
            "2026-08-31T22:05:30.1700278Z #20 pushing manifest for "
            f"{package}:{hb.SOURCE_COMMIT}@{digest} 1.4s done\n"
        )
    raise AssertionError(f"unsupported test package: {package}")


def paid_fixture(package: str, digest: str) -> dict:
    return {
        "package": package,
        "tag": f"{package}:{hb.SOURCE_COMMIT}",
        "digest": digest,
        "platform": {"os": "linux", "architecture": "amd64"},
        "labels": {
            "org.opencontainers.image.source": f"https://github.com/{hb.REPOSITORY}",
            "org.opencontainers.image.revision": hb.SOURCE_COMMIT,
        },
    }



def load_historical_bundle_source_helper():
    path = ROOT / "platform/release/prepare-historical-bundle-source.py"
    spec = importlib.util.spec_from_file_location("trinyx_historical_bundle_source", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def clean_git_checkout(path: Path) -> str:
    subprocess.run(["git", "init", "-q", str(path)], check=True, capture_output=True, text=True)
    subprocess.run(
        ["git", "-C", str(path), "config", "user.email", "tests@example.invalid"],
        check=True, capture_output=True, text=True,
    )
    subprocess.run(
        ["git", "-C", str(path), "config", "user.name", "Trinyx Tests"],
        check=True, capture_output=True, text=True,
    )
    subprocess.run(["git", "-C", str(path), "add", "."], check=True, capture_output=True, text=True)
    subprocess.run(
        ["git", "-C", str(path), "commit", "-qm", "historical fixture"],
        check=True, capture_output=True, text=True,
    )
    return subprocess.run(
        ["git", "-C", str(path), "rev-parse", "HEAD"],
        check=True, capture_output=True, text=True,
    ).stdout.strip()


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
            ("head_branch", "untrusted-branch"),
            ("event", "push"),
            ("created_at", "2026-99-31T22:06:00Z"),
            ("run_attempt", 2),
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

    def test_real_legacy_cloud_manifest_is_mapped_by_exact_structural_binding(self) -> None:
        inventory = json.loads((ROOT / "platform/release/runtime-inventory.json").read_text())
        historical_inventory = json.loads(
            (FIXTURES / "historical-cloud-images-aeb2.json").read_text()
        )
        manifest = json.loads(
            (FIXTURES / "historical-cloud-manifest-aeb2.json").read_text()
        )
        canonical = hb.canonical_cloud_manifest(manifest, inventory, historical_inventory)
        self.assertEqual(14, len(canonical["images"]))
        self.assertEqual(
            {"cloud-" + name for name in {
                "agent", "auth", "catalog", "conversation", "datasource", "gateway",
                "interface", "keycloak", "migration", "orchestrator", "publication",
                "storage", "trigger", "websearch",
            }},
            {item["name"] for item in canonical["images"]},
        )
        source = {item["service"]: item for item in manifest["images"]}
        for item in canonical["images"]:
            original = source[item["service"]]
            self.assertEqual(original["environment"], item["environment"])
            self.assertEqual(original["package"], item["package"])
            self.assertEqual(original["digest"], item["digest"])
            self.assertEqual(original["immutableRef"], item["immutableRef"])

        wrong = copy.deepcopy(manifest)
        wrong["commit"] = "f" * 40
        with self.assertRaises(ValueError):
            hb.canonical_cloud_manifest(wrong, inventory, historical_inventory)
        duplicate = copy.deepcopy(manifest)
        duplicate["images"][-1] = duplicate["images"][0]
        with self.assertRaises(ValueError):
            hb.canonical_cloud_manifest(duplicate, inventory, historical_inventory)

        for key in ("service", "environment", "package"):
            with self.subTest(binding=key):
                drift = copy.deepcopy(manifest)
                drift["images"][0][key] += "-wrong"
                with self.assertRaises(ValueError):
                    hb.canonical_cloud_manifest(drift, inventory, historical_inventory)
        for key, value in (
            ("digest", "mutable"),
            ("immutableRef", "ghcr.io/trinyxai/trinyx-cloud-agent:latest"),
        ):
            with self.subTest(image_identity=key):
                drift = copy.deepcopy(manifest)
                drift["images"][0][key] = value
                with self.assertRaises(ValueError):
                    hb.canonical_cloud_manifest(drift, inventory, historical_inventory)

    def test_artifact_digest_is_the_exact_real_github_value(self) -> None:
        self.assertEqual(
            "sha256:8cb6a3b52b7deff90bebcceb6435a5c66d6d1a06e45c32b8350427efe4059ac0",
            hb.CLOUD_ARTIFACT_DIGEST,
        )
        hb.validate_artifact(artifact_fixture())

    def test_paid_tag_digest_and_oci_revision_are_strict(self) -> None:
        package = "ghcr.io/trinyxai/trinyx-backend"
        result = hb.paid_manifest(
                                  paid_fixture(package, BACKEND_HISTORICAL_DIGEST),
                                  historical_digest=BACKEND_HISTORICAL_DIGEST,
                                  name="paid-backend",
                                  service="livecontext", environment="BACKEND_IMAGE",
                                  package=package)
        self.assertEqual(hb.SOURCE_COMMIT, result["commit"])
        for key, value in (("tag", package + ":latest"), ("digest", "mutable")):
            item = paid_fixture(package, BACKEND_HISTORICAL_DIGEST)
            item[key] = value
            with self.assertRaises(ValueError):
                hb.paid_manifest(item, historical_digest=BACKEND_HISTORICAL_DIGEST,
                                 name="paid-backend", service="livecontext",
                                 environment="BACKEND_IMAGE", package=package)
        item = paid_fixture(package, BACKEND_HISTORICAL_DIGEST)
        item["labels"]["org.opencontainers.image.revision"] = "f" * 40
        with self.assertRaises(ValueError):
            hb.paid_manifest(item, historical_digest=BACKEND_HISTORICAL_DIGEST,
                             name="paid-backend", service="livecontext",
                             environment="BACKEND_IMAGE", package=package)

    def test_paid_platform_and_per_image_label_policy_are_fail_closed(self) -> None:
        backend_package = "ghcr.io/trinyxai/trinyx-backend"
        frontend_package = "ghcr.io/trinyxai/trinyx-frontend"

        backend = hb.paid_manifest(
            paid_fixture(backend_package, BACKEND_HISTORICAL_DIGEST),
            historical_digest=BACKEND_HISTORICAL_DIGEST,
            name="paid-backend", service="livecontext",
            environment="BACKEND_IMAGE", package=backend_package,
            require_oci_labels=True,
        )
        self.assertEqual(BACKEND_HISTORICAL_DIGEST, backend["images"][0]["digest"])

        for name, labels in (
            ("absent", {}),
            ("wrong-source", {
                "org.opencontainers.image.source": "https://github.com/other/repo",
                "org.opencontainers.image.revision": hb.SOURCE_COMMIT,
            }),
            ("wrong-revision", {
                "org.opencontainers.image.source": f"https://github.com/{hb.REPOSITORY}",
                "org.opencontainers.image.revision": "f" * 40,
            }),
        ):
            with self.subTest(backend_labels=name):
                item = paid_fixture(backend_package, BACKEND_HISTORICAL_DIGEST)
                item["labels"] = labels
                with self.assertRaises(ValueError):
                    hb.paid_manifest(
                        item, historical_digest=BACKEND_HISTORICAL_DIGEST,
                        name="paid-backend", service="livecontext",
                        environment="BACKEND_IMAGE", package=backend_package,
                        require_oci_labels=True,
                    )

        frontend_item = paid_fixture(frontend_package, FRONTEND_HISTORICAL_DIGEST)
        frontend_item["labels"] = {}
        frontend = hb.paid_manifest(
            frontend_item, historical_digest=FRONTEND_HISTORICAL_DIGEST,
            name="paid-frontend", service="frontend",
            environment="FRONTEND_IMAGE", package=frontend_package,
            require_oci_labels=False,
        )
        self.assertEqual(FRONTEND_HISTORICAL_DIGEST, frontend["images"][0]["digest"])

        moved_frontend = copy.deepcopy(frontend_item)
        moved_frontend["digest"] = "sha256:" + "f" * 64
        with self.assertRaisesRegex(ValueError, "tag moved"):
            hb.paid_manifest(
                moved_frontend, historical_digest=FRONTEND_HISTORICAL_DIGEST,
                name="paid-frontend", service="frontend",
                environment="FRONTEND_IMAGE", package=frontend_package,
                require_oci_labels=False,
            )

        for field, value in (("architecture", "arm64"), ("os", "windows")):
            with self.subTest(platform_field=field):
                item = copy.deepcopy(frontend_item)
                item["platform"][field] = value
                with self.assertRaisesRegex(ValueError, "platform"):
                    hb.paid_manifest(
                        item, historical_digest=FRONTEND_HISTORICAL_DIGEST,
                        name="paid-frontend", service="frontend",
                        environment="FRONTEND_IMAGE", package=frontend_package,
                        require_oci_labels=False,
                    )

        missing_platform = copy.deepcopy(frontend_item)
        del missing_platform["platform"]
        with self.assertRaisesRegex(ValueError, "schema"):
            hb.paid_manifest(
                missing_platform, historical_digest=FRONTEND_HISTORICAL_DIGEST,
                name="paid-frontend", service="frontend",
                environment="FRONTEND_IMAGE", package=frontend_package,
                require_oci_labels=False,
            )

        malformed_labels = copy.deepcopy(frontend_item)
        malformed_labels["labels"] = []
        with self.assertRaisesRegex(ValueError, "labels"):
            hb.paid_manifest(
                malformed_labels, historical_digest=FRONTEND_HISTORICAL_DIGEST,
                name="paid-frontend", service="frontend",
                environment="FRONTEND_IMAGE", package=frontend_package,
                require_oci_labels=False,
            )

    def test_paid_digest_is_rooted_in_exact_authenticated_historical_job_logs(self) -> None:
        backend_job = job_fixture(hb.BACKEND_PUBLISH_JOB_ID, hb.BACKEND_RUN_ID, "publish")
        frontend_job = job_fixture(
            hb.FRONTEND_PUBLISH_JOB_ID, hb.FRONTEND_RUN_ID, "build-and-push"
        )
        hb.validate_job(
            backend_job, job_id=hb.BACKEND_PUBLISH_JOB_ID,
            run_id=hb.BACKEND_RUN_ID, name="publish",
        )
        hb.validate_job(
            frontend_job, job_id=hb.FRONTEND_PUBLISH_JOB_ID,
            run_id=hb.FRONTEND_RUN_ID, name="build-and-push",
        )
        backend = hb.historical_paid_digest(
            publication_log(hb.BACKEND_PACKAGE, BACKEND_HISTORICAL_DIGEST),
            package=hb.BACKEND_PACKAGE,
        )
        frontend = hb.historical_paid_digest(
            publication_log(hb.FRONTEND_PACKAGE, FRONTEND_HISTORICAL_DIGEST),
            package=hb.FRONTEND_PACKAGE,
        )
        self.assertEqual(BACKEND_HISTORICAL_DIGEST, backend)
        self.assertEqual(FRONTEND_HISTORICAL_DIGEST, frontend)

        moved = paid_fixture(hb.BACKEND_PACKAGE, "sha256:" + "f" * 64)
        with self.assertRaisesRegex(ValueError, "tag moved"):
            hb.paid_manifest(
                moved, historical_digest=backend, name="paid-backend",
                service="livecontext", environment="BACKEND_IMAGE",
                package=hb.BACKEND_PACKAGE,
            )
        spoofed = paid_fixture(hb.BACKEND_PACKAGE, "sha256:" + "f" * 64)
        spoofed["labels"]["org.opencontainers.image.revision"] = hb.SOURCE_COMMIT
        with self.assertRaises(ValueError):
            hb.paid_manifest(
                spoofed, historical_digest=backend, name="paid-backend",
                service="livecontext", environment="BACKEND_IMAGE",
                package=hb.BACKEND_PACKAGE,
            )

        for key, value in (
            ("id", hb.BACKEND_PUBLISH_JOB_ID + 1),
            ("run_id", hb.BACKEND_RUN_ID + 1),
            ("head_sha", "f" * 40),
            ("conclusion", "failure"),
            ("run_url", "https://api.github.com/repos/other/repo/actions/runs/1"),
        ):
            drift = copy.deepcopy(backend_job)
            drift[key] = value
            with self.assertRaises(ValueError):
                hb.validate_job(
                    drift, job_id=hb.BACKEND_PUBLISH_JOB_ID,
                    run_id=hb.BACKEND_RUN_ID, name="publish",
                )

    def test_historical_paid_digest_uses_exact_package_specific_log_shapes(self) -> None:
        backend_log = publication_log(hb.BACKEND_PACKAGE, BACKEND_HISTORICAL_DIGEST)
        frontend_log = publication_log(hb.FRONTEND_PACKAGE, FRONTEND_HISTORICAL_DIGEST)

        self.assertIn(
            f"pushing {BACKEND_HISTORICAL_DIGEST} to "
            f"{hb.BACKEND_PACKAGE}:{hb.SOURCE_COMMIT}",
            backend_log,
        )
        self.assertIn(
            f"pushing manifest for {hb.FRONTEND_PACKAGE}:{hb.SOURCE_COMMIT}"
            f"@{FRONTEND_HISTORICAL_DIGEST}",
            frontend_log,
        )

        backend_in_frontend_shape = (
            "2026-08-31T22:10:08.8525199Z #1 pushing manifest for "
            f"{hb.BACKEND_PACKAGE}:{hb.SOURCE_COMMIT}"
            f"@{BACKEND_HISTORICAL_DIGEST} 1.2s done\n"
        )
        frontend_in_backend_shape = (
            "2026-08-31T22:05:30.1700278Z #20 1.400 pushing "
            f"{FRONTEND_HISTORICAL_DIGEST} to "
            f"{hb.FRONTEND_PACKAGE}:{hb.SOURCE_COMMIT}\n"
        )
        with self.assertRaisesRegex(ValueError, "missing"):
            hb.historical_paid_digest(
                backend_in_frontend_shape, package=hb.BACKEND_PACKAGE
            )
        with self.assertRaisesRegex(ValueError, "missing"):
            hb.historical_paid_digest(
                frontend_in_backend_shape, package=hb.FRONTEND_PACKAGE
            )

    def test_historical_paid_digest_normalizes_only_ansi_color_formatting(self) -> None:
        for package, digest in (
            (hb.BACKEND_PACKAGE, BACKEND_HISTORICAL_DIGEST),
            (hb.FRONTEND_PACKAGE, FRONTEND_HISTORICAL_DIGEST),
        ):
            plain = publication_log(package, digest)
            variants = (
                plain,
                "\x1b[36m" + plain.rstrip("\n") + "\x1b[0m\n",
                "\x1b[1;34m" + plain + "\x1b[0m",
                plain.replace("pushing ", "\x1b[32mpushing\x1b[0m "),
            )
            for log_text in variants:
                with self.subTest(package=package, log_text=repr(log_text)):
                    self.assertEqual(
                        digest,
                        hb.historical_paid_digest(log_text, package=package),
                    )

    def test_historical_paid_digest_rejects_invalid_or_ambiguous_evidence(self) -> None:
        package = hb.BACKEND_PACKAGE
        valid = publication_log(package, BACKEND_HISTORICAL_DIGEST)
        cases = {
            "empty": "",
            "wrong-package": valid.replace(package, "ghcr.io/trinyxai/other-backend"),
            "wrong-source": valid.replace(hb.SOURCE_COMMIT, "f" * 40),
            "short-digest": publication_log(package, "sha256:" + "a" * 63),
            "uppercase-digest": publication_log(package, "sha256:" + "A" * 64),
        }
        for name, log_text in cases.items():
            with self.subTest(name=name):
                with self.assertRaises(ValueError):
                    hb.historical_paid_digest(log_text, package=package)

        ambiguous = valid + publication_log(package, "sha256:" + "e" * 64)
        with self.assertRaisesRegex(ValueError, "mismatch"):
            hb.historical_paid_digest(ambiguous, package=package)

        unrelated_digest = (
            "2026-08-31T22:08:48Z #86 exporting manifest list "
            + "sha256:" + "e" * 64 + " done\n"
            + valid
        )
        self.assertEqual(
            BACKEND_HISTORICAL_DIGEST,
            hb.historical_paid_digest(unrelated_digest, package=package),
        )

        with self.assertRaisesRegex(ValueError, "unsupported"):
            hb.historical_paid_digest(valid, package="ghcr.io/trinyxai/unknown")

    def test_terminal_controls_cannot_create_silent_provenance(self) -> None:
        package = hb.BACKEND_PACKAGE
        non_semantic_controls = (
            "\x1b[2J" + publication_log(package, BACKEND_HISTORICAL_DIGEST),
            "\x1b]0;pushing "
            + f"{BACKEND_HISTORICAL_DIGEST} to {package}:{hb.SOURCE_COMMIT}"
            + "\x07\n",
            publication_log(package, BACKEND_HISTORICAL_DIGEST).replace(
                "trinyx-backend", "trinyx-\x08backend"
            ),
        )
        for log_text in non_semantic_controls:
            with self.subTest(log_text=repr(log_text)):
                with self.assertRaisesRegex(ValueError, "terminal"):
                    hb.historical_paid_digest(log_text, package=package)

    def test_buildx_single_snapshot_digest_and_image_extraction_fail_closed(self) -> None:
        digest_filter = (
            '.manifest.digest | select(type == "string" '
            'and test("^sha256:[0-9a-f]{64}$"))'
        )
        image_filter = '.image | select(type == "object")'
        valid = {
            "manifest": {"digest": BACKEND_HISTORICAL_DIGEST},
            "image": {
                "os": "linux",
                "architecture": "amd64",
                "config": {"Labels": {}},
            },
        }

        def jq(filter_text: str, document: object) -> subprocess.CompletedProcess[str]:
            return subprocess.run(
                ["jq", "-er", filter_text],
                input=json.dumps(document),
                capture_output=True,
                text=True,
                check=False,
            )

        self.assertEqual(0, jq(digest_filter, valid).returncode)
        self.assertEqual(0, jq(image_filter, valid).returncode)

        invalid_snapshots = (
            {"manifest": {}, "image": valid["image"]},
            {"manifest": {"digest": "mutable"}, "image": valid["image"]},
            {"manifest": {"digest": "sha256:" + "A" * 64}, "image": valid["image"]},
            {"manifest": {"digest": BACKEND_HISTORICAL_DIGEST}, "image": []},
        )
        for document in invalid_snapshots:
            with self.subTest(document=document):
                digest_result = jq(digest_filter, document)
                image_result = jq(image_filter, document)
                self.assertTrue(
                    digest_result.returncode != 0 or image_result.returncode != 0
                )

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
        self.assertEqual(1, workflow.count('docker buildx imagetools inspect "$tag"'))
        self.assertIn("--format '{{json .}}'", workflow)
        self.assertNotIn("{{json .Image}}", workflow)
        self.assertNotIn("index .Image", workflow)
        self.assertIn(".manifest.digest", workflow)
        self.assertIn('.image | select(type == "object")', workflow)
        self.assertIn("HISTORICAL_PAID_INSPECT package=%s", workflow)
        self.assertIn("(.config.Labels // {})", workflow)
        self.assertIn("--argjson platform", workflow)
        self.assertIn("docker/login-action@c94ce9fb468520275223c153574b00df6fe4bcc9", workflow)
        self.assertIn("sha256sum --check --strict", workflow)
        backend_log_download = (
            f"gh api --allow-escape-sequences /repos/{hb.REPOSITORY}/actions/jobs/"
            f"{hb.BACKEND_PUBLISH_JOB_ID}/logs > historical-input/backend-publish.log"
        )
        frontend_log_download = (
            f"gh api --allow-escape-sequences /repos/{hb.REPOSITORY}/actions/jobs/"
            f"{hb.FRONTEND_PUBLISH_JOB_ID}/logs > historical-input/frontend-publish.log"
        )
        self.assertIn(backend_log_download, workflow)
        self.assertIn(frontend_log_download, workflow)
        self.assertNotIn(
            f"gh api /repos/{hb.REPOSITORY}/actions/jobs/"
            f"{hb.BACKEND_PUBLISH_JOB_ID}/logs > historical-input/backend-publish.log",
            workflow,
        )
        self.assertNotIn(
            f"gh api /repos/{hb.REPOSITORY}/actions/jobs/"
            f"{hb.FRONTEND_PUBLISH_JOB_ID}/logs > historical-input/frontend-publish.log",
            workflow,
        )
        self.assertIn("--backend-log historical-input/backend-publish.log", workflow)
        self.assertIn("--frontend-log historical-input/frontend-publish.log", workflow)
        self.assertIn(hb.CLOUD_ARTIFACT_DIGEST.removeprefix("sha256:"), workflow)
        self.assertIn("actions: read", wrapper)
        self.assertIn("actions: read", workflow)
        self.assertIn("ref: ${{ job.workflow_sha }}", workflow)
        self.assertIn('--platform-commit "$TRUSTED_BUILDER_COMMIT"', workflow)
        self.assertNotIn('--platform-commit "$GITHUB_SHA"', workflow)
        self.assertIn(f"SOURCE_REF: {hb.SOURCE_REF}", workflow)
        self.assertIn('--source-ref "$SOURCE_REF"', workflow)
        self.assertIn("platform/release/release.py create", workflow)
        self.assertIn("created_at=$(jq -er", workflow)
        self.assertIn("--created-at \"$created_at\"", workflow)
        self.assertIn("platform/release/prepare-historical-bundle-source.py", workflow)
        self.assertIn("--historical-repo historical-source", workflow)
        self.assertIn("--trusted-repo .", workflow)
        self.assertIn("--repo historical-bundle-source", workflow)
        self.assertNotIn("--repo historical-source \\", workflow)
        self.assertIn("historical-deployment-bundle-sources.json", workflow)
        helper_source = (ROOT / "platform/release/prepare-historical-bundle-source.py").read_text()
        self.assertIn("git", "-C", str(repo), "rev-parse", "HEAD"", helper_source)
        self.assertIn("git", "-C", str(repo), "status", "--porcelain=v1"", helper_source)
        self.assertIn("repository root may not traverse a symlink", helper_source)
        self.assertIn("destination may not be inside the historical repository", helper_source)
        self.assertIn("Prepare authenticated historical bundle source", workflow)
        self.assertIn("git -C historical-source rev-parse HEAD", workflow)
        self.assertIn("git -C historical-source status --porcelain=v1 --untracked-files=all", workflow)
        self.assertIn("Verify Paid runtime render from authenticated bundle origins", workflow)
        self.assertIn("docker compose --env-file historical-input/paid/images.env", workflow)
        self.assertIn("HISTORICAL_PAID_RUNTIME_RENDER_OK services=8", workflow)
        self.assertLess(
            workflow.index("Prepare authenticated historical bundle source"),
            workflow.index("Verify Paid runtime render from authenticated bundle origins"),
        )
        self.assertLess(
            workflow.index("Verify Paid runtime render from authenticated bundle origins"),
            workflow.index("Build deterministic bundle from authenticated source origins"),
        )
        self.assertNotIn("docker compose up", workflow)
        self.assertIn("actions/attest-build-provenance@", workflow)

    def test_historical_bundle_source_is_explicit_and_complete(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            historical = root / "historical"
            trusted = root / "trusted"
            output = root / "output"
            (historical / "catalog-seeds").mkdir(parents=True)
            (historical / "docker-compose.yml").write_text("services: {}\n")
            (historical / "catalog-seeds" / "seed.json").write_text("{}\n")
            overlay = trusted / "docker" / "docker-compose.paid.runtime.yml"
            overlay.parent.mkdir(parents=True)
            overlay.write_text("services: {}\n")
            commit = clean_git_checkout(historical)
            helper = load_historical_bundle_source_helper()
            helper.SOURCE_COMMIT = commit
            source_contract = root / "sources.json"
            source_contract.write_text(json.dumps({
                "schemaVersion": 1,
                "historicalSourceCommit": commit,
                "historicalPaths": ["docker-compose.yml", "catalog-seeds"],
                "trustedBuilderOverlays": ["docker/docker-compose.paid.runtime.yml"],
            }))
            bundle_contract = root / "bundle.json"
            bundle_contract.write_text(json.dumps({
                "schemaVersion": 1,
                "paths": [
                    "docker-compose.yml",
                    "catalog-seeds",
                    "docker/docker-compose.paid.runtime.yml",
                ],
            }))
            helper.prepare(historical, trusted, source_contract, bundle_contract, output)
            self.assertEqual(
                (output / "docker-compose.yml").read_bytes(),
                (historical / "docker-compose.yml").read_bytes(),
            )
            self.assertEqual(
                (output / "catalog-seeds" / "seed.json").read_bytes(),
                (historical / "catalog-seeds" / "seed.json").read_bytes(),
            )
            self.assertEqual(
                (output / "docker" / "docker-compose.paid.runtime.yml").read_bytes(),
                overlay.read_bytes(),
            )

    def test_historical_bundle_source_rejects_shadow_and_contract_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def case(name: str, *, shadow: bool = False, child_symlink: bool = False):
                case_root = root / name
                historical = case_root / "historical"
                trusted = case_root / "trusted"
                (historical / "catalog-seeds").mkdir(parents=True)
                (historical / "docker-compose.yml").write_text("services: {}\n")
                (historical / "catalog-seeds" / "seed.json").write_text("{}\n")
                overlay = trusted / "docker" / "docker-compose.paid.runtime.yml"
                overlay.parent.mkdir(parents=True)
                overlay.write_text("services: {}\n")
                if shadow:
                    historical_overlay = historical / "docker" / "docker-compose.paid.runtime.yml"
                    historical_overlay.parent.mkdir(parents=True)
                    historical_overlay.write_text("shadow\n")
                if child_symlink:
                    outside = case_root / "outside"
                    outside.mkdir()
                    os.symlink(outside, historical / "docker")
                commit = clean_git_checkout(historical)
                helper = load_historical_bundle_source_helper()
                helper.SOURCE_COMMIT = commit
                source_contract = case_root / "sources.json"
                source_contract.write_text(json.dumps({
                    "schemaVersion": 1,
                    "historicalSourceCommit": commit,
                    "historicalPaths": ["docker-compose.yml", "catalog-seeds"],
                    "trustedBuilderOverlays": ["docker/docker-compose.paid.runtime.yml"],
                }))
                bundle_contract = case_root / "bundle.json"
                bundle_contract.write_text(json.dumps({
                    "schemaVersion": 1,
                    "paths": [
                        "docker-compose.yml",
                        "catalog-seeds",
                        "docker/docker-compose.paid.runtime.yml",
                    ],
                }))
                return helper, historical, trusted, source_contract, bundle_contract, case_root

            helper, historical, trusted, source, bundle, case_root = case("unapproved")
            document = json.loads(source.read_text())
            document["trustedBuilderOverlays"] = ["docker/unapproved.yml"]
            source.write_text(json.dumps(document))
            with self.assertRaisesRegex(SystemExit, "unapproved historical bundle trusted overlay"):
                helper.prepare(historical, trusted, source, bundle, case_root / "out")

            helper, historical, trusted, source, bundle, case_root = case("shadow", shadow=True)
            with self.assertRaisesRegex(SystemExit, "trusted overlay would shadow historical source"):
                helper.prepare(historical, trusted, source, bundle, case_root / "out")

            helper, historical, trusted, source, bundle, case_root = case("child-symlink", child_symlink=True)
            with self.assertRaisesRegex(SystemExit, "path traverses a symlink"):
                helper.prepare(historical, trusted, source, bundle, case_root / "out")

            helper, historical, trusted, source, bundle, case_root = case("root-symlink")
            linked = case_root / "historical-link"
            os.symlink(historical, linked)
            with self.assertRaisesRegex(SystemExit, "root may not traverse a symlink"):
                helper.prepare(linked, trusted, source, bundle, case_root / "out")
            parent_link = root / "historical-parent-link"
            os.symlink(case_root, parent_link)
            with self.assertRaisesRegex(SystemExit, "root may not traverse a symlink"):
                helper.prepare(
                    parent_link / "historical", trusted, source, bundle, case_root / "out-parent"
                )
            trusted_link = case_root / "trusted-link"
            os.symlink(trusted, trusted_link)
            with self.assertRaisesRegex(SystemExit, "root may not traverse a symlink"):
                helper.prepare(historical, trusted_link, source, bundle, case_root / "out-trusted")

    def test_historical_bundle_source_rejects_bool_versions_overlaps_and_dirty_checkout(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            historical = root / "historical"
            trusted = root / "trusted"
            (historical / "catalog-seeds").mkdir(parents=True)
            (historical / "catalog-seeds" / "seed.json").write_text("{}\n")
            (historical / "docker-compose.yml").write_text("services: {}\n")
            overlay = trusted / "docker" / "docker-compose.paid.runtime.yml"
            overlay.parent.mkdir(parents=True)
            overlay.write_text("services: {}\n")
            commit = clean_git_checkout(historical)
            helper = load_historical_bundle_source_helper()
            helper.SOURCE_COMMIT = commit
            source = root / "sources.json"
            bundle = root / "bundle.json"
            source_document = {
                "schemaVersion": 1,
                "historicalSourceCommit": commit,
                "historicalPaths": ["docker-compose.yml", "catalog-seeds"],
                "trustedBuilderOverlays": ["docker/docker-compose.paid.runtime.yml"],
            }
            bundle_document = {
                "schemaVersion": 1,
                "paths": [
                    "docker-compose.yml",
                    "catalog-seeds",
                    "docker/docker-compose.paid.runtime.yml",
                ],
            }

            def prepare(name: str) -> None:
                source.write_text(json.dumps(source_document))
                bundle.write_text(json.dumps(bundle_document))
                helper.prepare(historical, trusted, source, bundle, root / name)

            source_document["schemaVersion"] = True
            with self.assertRaisesRegex(SystemExit, "identity mismatch"):
                prepare("bool-source")
            source_document["schemaVersion"] = 1

            bundle_document["schemaVersion"] = True
            with self.assertRaisesRegex(SystemExit, "file contract schema mismatch"):
                prepare("bool-bundle")
            bundle_document["schemaVersion"] = 1

            source_document["historicalPaths"] = [
                "docker-compose.yml", "catalog-seeds", "catalog-seeds/seed.json",
            ]
            with self.assertRaisesRegex(SystemExit, "overlapping paths"):
                prepare("overlapping-source")
            source_document["historicalPaths"] = ["docker-compose.yml", "catalog-seeds"]

            source.write_text(json.dumps(source_document))
            bundle.write_text(json.dumps(bundle_document))
            with self.assertRaisesRegex(
                SystemExit, "destination may not be inside the historical repository"
            ):
                helper.prepare(historical, trusted, source, bundle, historical / "out")

            (historical / "dirty.txt").write_text("not tracked\n")
            with self.assertRaisesRegex(SystemExit, "historical repository is not clean"):
                prepare("dirty")
            (historical / "dirty.txt").unlink()

            source_document["historicalSourceCommit"] = "f" * 40
            helper.SOURCE_COMMIT = "f" * 40
            with self.assertRaisesRegex(SystemExit, "historical repository commit mismatch"):
                prepare("wrong-commit")

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
                "--source-ref", hb.SOURCE_REF,
                "--platform-commit", "e" * 40, "--images", str(root / "images.json"),
                "--bundle-manifest", str(root / "bundle.json"), "--out", str(release),
            ], check=True, capture_output=True, text=True)
            document = json.loads(release.read_text())
            self.assertRegex(document["releaseId"], r"^rel-v1-[0-9a-f]{32}$")
            subprocess.run([sys.executable, str(script), "validate", "--manifest", str(release)],
                           check=True, capture_output=True, text=True)


if __name__ == "__main__":
    unittest.main()
