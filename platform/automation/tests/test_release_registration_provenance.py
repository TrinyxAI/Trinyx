from __future__ import annotations

import copy
import json
import os
import re
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

    def test_future_release_requires_exact_pinned_reusable_builder(self) -> None:
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
                "ref": "refs/heads/codex/platform-release-automation",
            }
        ]
        accepted = self.execute(artifact, run, env)
        self.assertEqual(0, accepted.returncode, accepted.stderr)

        wrong_sha_run = copy.deepcopy(run)
        wrong_sha_run["referenced_workflows"][0]["sha"] = "0" * 40
        self.assert_rejected(artifact, wrong_sha_run, env)
        self.assertIn(f"BUILDER_WORKFLOW_COMMIT: {BUILDER_COMMIT}", self.workflow)


if __name__ == "__main__":
    unittest.main()
