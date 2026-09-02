from __future__ import annotations

import copy
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / ".github/workflows/staging-release-register-impl.yml"
HISTORICAL_SOURCE = "f3a4c1ddcf6a17bfc837071f9046ac4c38a38b47"
BUILDER_COMMIT = "114a2613e8090f034925a1bcf148f055653c3a06"


class ReleaseRegistrationProvenanceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        match = re.search(
            r"# BEGIN STAGING_RELEASE_RUN_PROVENANCE_VALIDATION\n"
            r"(?P<body>.*?)"
            r"# END STAGING_RELEASE_RUN_PROVENANCE_VALIDATION",
            cls.workflow,
            re.DOTALL,
        )
        if match is None:
            raise AssertionError("workflow provenance validator markers are missing")
        cls.script = textwrap.dedent(match.group("body"))

        compatibility = re.search(
            r"# BEGIN STAGING_RELEASE_ATTESTATION_COMPATIBILITY\n"
            r"(?P<body>.*?)"
            r"# END STAGING_RELEASE_ATTESTATION_COMPATIBILITY",
            cls.workflow,
            re.DOTALL,
        )
        if compatibility is None:
            raise AssertionError("workflow attestation compatibility markers are missing")
        cls.compatibility_script = textwrap.dedent(compatibility.group("body"))
        attestation = re.search(
            r"# BEGIN STAGING_RELEASE_INTERNAL_ATTESTATION_VERIFICATION\n"
            r"(?P<body>.*?)"
            r"# END STAGING_RELEASE_INTERNAL_ATTESTATION_VERIFICATION",
            cls.workflow,
            re.DOTALL,
        )
        if attestation is None:
            raise AssertionError("workflow internal attestation markers are missing")
        cls.attestation_script = textwrap.dedent(attestation.group("body"))

    def historical_fixture(self) -> tuple[dict, dict, dict[str, str]]:
        artifact = {
            "id": 9791964215,
            "name": "trinyx-release-candidate-" + HISTORICAL_SOURCE,
            "expired": False,
            "workflow_run": {"id": 33485509832, "head_sha": HISTORICAL_SOURCE},
        }
        run = {
            "id": 33485509832,
            "head_sha": HISTORICAL_SOURCE,
            "path": ".github/workflows/build-trinyx-backend.yml",
            "conclusion": "success",
            "repository": {"full_name": "TrinyxAI/Trinyx"},
            "referenced_workflows": [
                {
                    "path": (
                        "TrinyxAI/Trinyx/.github/workflows/"
                        "build-release-candidate.yml@" + HISTORICAL_SOURCE
                    ),
                    "sha": HISTORICAL_SOURCE,
                    "ref": "refs/heads/codex/platform-release-automation",
                },
                {
                    "path": (
                        "TrinyxAI/Trinyx/.github/workflows/"
                        "build-trinyx-cloud-images.yml@" + HISTORICAL_SOURCE
                    ),
                    "sha": HISTORICAL_SOURCE,
                    "ref": "refs/heads/codex/platform-release-automation",
                },
            ],
        }
        env = {
            "GITHUB_REPOSITORY": "TrinyxAI/Trinyx",
            "GITHUB_REPOSITORY_ID": "1342032975",
            "GITHUB_REPOSITORY_OWNER_ID": "319253481",
            "ARTIFACT_ID": "9791964215",
            "RUN_ID": "33485509832",
            "ARTIFACT_DIGEST": (
                "sha256:755594078d9da7e19406e01187534132920a31f87804c1b33baa28fa96559152"
            ),
            "SOURCE_COMMIT": HISTORICAL_SOURCE,
            "RELEASE_ID": "rel-v1-b5ba70c23b9f529ac8228a7b00b4faa4",
            "BUNDLE_DIGEST": (
                "sha256:c9df14dcd1dbc24b31b926d3778bef2e208b59824c78f24292608284f3579892"
            ),
            "BUILDER_WORKFLOW_COMMIT": BUILDER_COMMIT,
        }
        return artifact, run, env

    def execute(self, artifact: dict, run: dict, env: dict[str, str]) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            (directory / "artifact-metadata.json").write_text(
                json.dumps(artifact), encoding="utf-8"
            )
            (directory / "run-metadata.json").write_text(
                json.dumps(run), encoding="utf-8"
            )
            process_env = os.environ.copy()
            process_env.update(env)
            return subprocess.run(
                [sys.executable, "-c", self.script],
                cwd=directory,
                env=process_env,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )

    def assert_rejected(self, artifact: dict, run: dict, env: dict[str, str]) -> None:
        result = self.execute(artifact, run, env)
        self.assertNotEqual(0, result.returncode)

    def test_exact_historical_run_metadata_is_accepted(self) -> None:
        artifact, run, env = self.historical_fixture()
        result = self.execute(artifact, run, env)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_historical_wrong_caller_path_is_rejected(self) -> None:
        artifact, run, env = self.historical_fixture()
        run["path"] = ".github/workflows/build-release-candidate.yml"
        self.assert_rejected(artifact, run, env)

    def test_historical_missing_referenced_workflows_is_rejected(self) -> None:
        artifact, run, env = self.historical_fixture()
        del run["referenced_workflows"]
        self.assert_rejected(artifact, run, env)

    def test_historical_wrong_referenced_workflow_is_rejected(self) -> None:
        artifact, run, env = self.historical_fixture()
        run["referenced_workflows"][0]["path"] = (
            "TrinyxAI/Trinyx/.github/workflows/build-release-candidate-impl.yml@"
            + HISTORICAL_SOURCE
        )
        self.assert_rejected(artifact, run, env)

    def test_historical_wrong_referenced_workflow_sha_is_rejected(self) -> None:
        artifact, run, env = self.historical_fixture()
        run["referenced_workflows"][0]["sha"] = "0" * 40
        self.assert_rejected(artifact, run, env)

    def test_historical_wrong_referenced_workflow_ref_is_rejected(self) -> None:
        artifact, run, env = self.historical_fixture()
        run["referenced_workflows"][0]["ref"] = "refs/heads/main"
        self.assert_rejected(artifact, run, env)

    def test_duplicate_expected_builder_identity_is_rejected(self) -> None:
        artifact, run, env = self.historical_fixture()
        run["referenced_workflows"].append(
            copy.deepcopy(run["referenced_workflows"][0])
        )
        self.assert_rejected(artifact, run, env)

    def test_other_identity_does_not_receive_historical_exception(self) -> None:
        for changed in ("run", "artifact", "source"):
            with self.subTest(changed=changed):
                artifact, run, env = self.historical_fixture()
                if changed == "run":
                    env["RUN_ID"] = "33485509833"
                    artifact["workflow_run"]["id"] = 33485509833
                    run["id"] = 33485509833
                elif changed == "artifact":
                    env["ARTIFACT_ID"] = "9791964216"
                    artifact["id"] = 9791964216
                else:
                    source = "a" * 40
                    env["SOURCE_COMMIT"] = source
                    artifact["name"] = "trinyx-release-candidate-" + source
                    artifact["workflow_run"]["head_sha"] = source
                    run["head_sha"] = source
                self.assert_rejected(artifact, run, env)

    def test_future_sha_pinned_builder_without_ref_is_accepted(self) -> None:
        artifact, run, env = self.historical_fixture()
        env["RUN_ID"] = "40000000000"
        artifact["workflow_run"]["id"] = 40000000000
        run["id"] = 40000000000
        run["referenced_workflows"] = [
            {
                "path": (
                    "TrinyxAI/Trinyx/.github/workflows/"
                    "build-release-candidate-impl.yml@" + BUILDER_COMMIT
                ),
                "sha": BUILDER_COMMIT,
            }
        ]
        accepted = self.execute(artifact, run, env)
        self.assertEqual(0, accepted.returncode, accepted.stderr)

        wrong_sha_run = copy.deepcopy(run)
        wrong_sha_run["referenced_workflows"][0]["sha"] = "0" * 40
        self.assert_rejected(artifact, wrong_sha_run, env)

        wrong_path_run = copy.deepcopy(run)
        wrong_path_run["referenced_workflows"][0]["path"] = (
            "TrinyxAI/Trinyx/.github/workflows/build-release-candidate.yml@"
            + BUILDER_COMMIT
        )
        self.assert_rejected(artifact, wrong_path_run, env)

        contradictory_ref_run = copy.deepcopy(run)
        contradictory_ref_run["referenced_workflows"][0]["ref"] = (
            "refs/heads/codex/platform-release-automation"
        )
        self.assert_rejected(artifact, contradictory_ref_run, env)
        self.assertIn(f"BUILDER_WORKFLOW_COMMIT: {BUILDER_COMMIT}", self.workflow)


    def compatibility_namespace(self) -> dict:
        namespace = {"__name__": "release_registration_compatibility_test"}
        exec(self.compatibility_script, namespace)
        return namespace

    def compatibility_policy(
        self,
        env: dict[str, str],
        digests: dict[str, str],
    ) -> tuple[dict, dict]:
        namespace = self.compatibility_namespace()
        namespace["file_sha256"] = lambda path: digests[path.name]
        policy = namespace["resolve_attestation_policy"](env, Path("candidate"))
        return policy, namespace

    def test_exact_historical_tuple_accepts_only_exact_internal_hashes(self) -> None:
        _, _, env = self.historical_fixture()
        namespace = self.compatibility_namespace()
        expected = {
            "release.json": "ad5a5b702d9659e0af5d5b82a422953ba2390a94396949f897757568c9b59789",
            "release-images.json": "fe1134c3920af0f2f9f0027082f25ec5adb1cbb6d41a1053bada7ef730f66a8a",
            "deployment-bundle.json": "b101918414ee9d113d4ef54d32c9f438005d8ebee7bde2e62f72d58a16cfdd7b",
            "deployment-bundle.tar": "c9df14dcd1dbc24b31b926d3778bef2e208b59824c78f24292608284f3579892",
        }
        self.assertEqual(expected, namespace["HISTORICAL_INTERNAL_SHA256"])
        policy, _ = self.compatibility_policy(env, expected)
        self.assertEqual("false", policy["REQUIRE_INTERNAL_ATTESTATIONS"])
        self.assertEqual("frozen-historical-builder", policy["COMPATIBILITY"])
        self.assertEqual("build-release-candidate.yml", policy["SIGNER_WORKFLOW"])
        self.assertEqual(HISTORICAL_SOURCE, policy["SIGNER_DIGEST"])

        for name in expected:
            with self.subTest(name=name):
                changed = expected.copy()
                changed[name] = "0" * 64
                with self.assertRaises(AssertionError):
                    self.compatibility_policy(env, changed)

        missing = expected.copy()
        del missing["release.json"]
        with self.assertRaises(KeyError):
            self.compatibility_policy(env, missing)

    def test_each_historical_identity_drift_requires_modern_attestations(self) -> None:
        _, _, exact_env = self.historical_fixture()
        critical = (
            "GITHUB_REPOSITORY",
            "GITHUB_REPOSITORY_ID",
            "GITHUB_REPOSITORY_OWNER_ID",
            "RUN_ID",
            "ARTIFACT_ID",
            "ARTIFACT_DIGEST",
            "SOURCE_COMMIT",
            "RELEASE_ID",
            "BUNDLE_DIGEST",
        )
        namespace = self.compatibility_namespace()
        digests = namespace["HISTORICAL_INTERNAL_SHA256"]
        for key in critical:
            with self.subTest(key=key):
                env = exact_env.copy()
                env[key] = "not-the-approved-value"
                policy, _ = self.compatibility_policy(env, digests)
                self.assertEqual("true", policy["REQUIRE_INTERNAL_ATTESTATIONS"])
                self.assertEqual("pinned-reusable-builder", policy["COMPATIBILITY"])
                self.assertEqual(
                    "build-release-candidate-impl.yml",
                    policy["SIGNER_WORKFLOW"],
                )
                self.assertEqual(BUILDER_COMMIT, policy["SIGNER_DIGEST"])

    def run_attestation_block(self, require_attestations: str) -> subprocess.CompletedProcess[str]:
        bash = shutil.which("bash")
        if bash is None:
            self.skipTest("bash is required to exercise the workflow shell block")
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            fake_bin = directory / "bin"
            fake_bin.mkdir()
            fake_gh = fake_bin / "gh"
            fake_gh.write_text(
                "#!/usr/bin/env bash\necho 'HTTP 404: Not Found' >&2\nexit 22\n",
                encoding="utf-8",
            )
            fake_gh.chmod(0o755)
            env = os.environ.copy()
            env.update(
                {
                    "PATH": str(fake_bin) + os.pathsep + env.get("PATH", ""),
                    "REQUIRE_INTERNAL_ATTESTATIONS": require_attestations,
                    "GITHUB_REPOSITORY": "TrinyxAI/Trinyx",
                    "SIGNER_WORKFLOW": "build-release-candidate-impl.yml",
                    "SIGNER_DIGEST": BUILDER_COMMIT,
                    "SOURCE_COMMIT": "a" * 40,
                }
            )
            return subprocess.run(
                [bash, "-c", "set -euo pipefail\n" + self.attestation_script],
                cwd=directory,
                env=env,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )

    def test_non_historical_attestation_404_remains_fail_closed(self) -> None:
        result = self.run_attestation_block("true")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("HTTP 404: Not Found", result.stderr)

    def test_historical_path_does_not_request_nonexistent_internal_attestations(self) -> None:
        result = self.run_attestation_block("false")
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
