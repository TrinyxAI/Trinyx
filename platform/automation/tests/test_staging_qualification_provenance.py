from __future__ import annotations

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
QUALIFICATION = ROOT / ".github/workflows/staging-qualification-impl.yml"
REGISTRATION = ROOT / ".github/workflows/staging-release-register-impl.yml"
HISTORICAL_SOURCE = "f3a4c1ddcf6a17bfc837071f9046ac4c38a38b47"
BUILDER_COMMIT = "114a2613e8090f034925a1bcf148f055653c3a06"
HISTORICAL_INTERNAL = {
    "release.json": "ad5a5b702d9659e0af5d5b82a422953ba2390a94396949f897757568c9b59789",
    "release-images.json": "fe1134c3920af0f2f9f0027082f25ec5adb1cbb6d41a1053bada7ef730f66a8a",
    "deployment-bundle.json": "b101918414ee9d113d4ef54d32c9f438005d8ebee7bde2e62f72d58a16cfdd7b",
    "deployment-bundle.tar": "c9df14dcd1dbc24b31b926d3778bef2e208b59824c78f24292608284f3579892",
}


def extract(text: str, marker: str) -> str:
    match = re.search(
        rf"# BEGIN {marker}\n(?P<body>.*?)# END {marker}",
        text,
        re.DOTALL,
    )
    if match is None:
        raise AssertionError(f"workflow marker missing: {marker}")
    return textwrap.dedent(match.group("body"))


class StagingQualificationProvenanceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = QUALIFICATION.read_text(encoding="utf-8")
        cls.registration = REGISTRATION.read_text(encoding="utf-8")
        cls.run_script = extract(cls.workflow, "STAGING_QUALIFICATION_RUN_PROVENANCE_VALIDATION")
        cls.compatibility_script = extract(cls.workflow, "STAGING_QUALIFICATION_ATTESTATION_COMPATIBILITY")
        cls.attestation_script = extract(cls.workflow, "STAGING_QUALIFICATION_INTERNAL_ATTESTATION_VERIFICATION")
        cls.provenance_script = extract(cls.workflow, "STAGING_QUALIFICATION_PROVENANCE_GENERATION")

    def historical_identity(self, prefix: str) -> dict[str, str]:
        return {
            "GITHUB_REPOSITORY": "TrinyxAI/Trinyx",
            "GITHUB_REPOSITORY_ID": "1342032975",
            "GITHUB_REPOSITORY_OWNER_ID": "319253481",
            f"{prefix}_RUN_ID": "33485509832",
            f"{prefix}_ARTIFACT_ID": "9791964215",
            f"{prefix}_ARTIFACT_DIGEST": "sha256:755594078d9da7e19406e01187534132920a31f87804c1b33baa28fa96559152",
            f"{prefix}_SOURCE_COMMIT": HISTORICAL_SOURCE,
            f"{prefix}_RELEASE_ID": "rel-v1-b5ba70c23b9f529ac8228a7b00b4faa4",
            f"{prefix}_BUNDLE_DIGEST": "sha256:c9df14dcd1dbc24b31b926d3778bef2e208b59824c78f24292608284f3579892",
            "BUILDER_WORKFLOW_COMMIT": BUILDER_COMMIT,
        }

    def modern_identity(self, prefix: str) -> dict[str, str]:
        return {
            "GITHUB_REPOSITORY": "TrinyxAI/Trinyx",
            "GITHUB_REPOSITORY_ID": "1342032975",
            "GITHUB_REPOSITORY_OWNER_ID": "319253481",
            f"{prefix}_RUN_ID": "40000000000" if prefix == "BASELINE" else "40000000001",
            f"{prefix}_ARTIFACT_ID": "50000000000" if prefix == "BASELINE" else "50000000001",
            f"{prefix}_ARTIFACT_DIGEST": "sha256:" + ("a" if prefix == "BASELINE" else "b") * 64,
            f"{prefix}_SOURCE_COMMIT": ("c" if prefix == "BASELINE" else "d") * 40,
            f"{prefix}_RELEASE_ID": "rel-v1-" + ("e" if prefix == "BASELINE" else "f") * 32,
            f"{prefix}_BUNDLE_DIGEST": "sha256:" + ("1" if prefix == "BASELINE" else "2") * 64,
            "BUILDER_WORKFLOW_COMMIT": BUILDER_COMMIT,
        }

    def combined_env(self, baseline_historical: bool, candidate_historical: bool) -> dict[str, str]:
        env = os.environ.copy()
        env.update(self.historical_identity("BASELINE") if baseline_historical else self.modern_identity("BASELINE"))
        env.update(self.historical_identity("CANDIDATE") if candidate_historical else self.modern_identity("CANDIDATE"))
        return env

    def artifact_run(self, env: dict[str, str], prefix: str, historical: bool) -> tuple[dict, dict]:
        source = env[f"{prefix}_SOURCE_COMMIT"]
        run_id = int(env[f"{prefix}_RUN_ID"])
        artifact = {
            "id": int(env[f"{prefix}_ARTIFACT_ID"]),
            "name": "trinyx-release-candidate-" + source,
            "expired": False,
            "workflow_run": {"id": run_id, "head_sha": source},
        }
        if historical:
            referenced = [{
                "path": "TrinyxAI/Trinyx/.github/workflows/build-release-candidate.yml@" + HISTORICAL_SOURCE,
                "sha": HISTORICAL_SOURCE,
                "ref": "refs/heads/codex/platform-release-automation",
            }]
        else:
            referenced = [{
                "path": "TrinyxAI/Trinyx/.github/workflows/build-release-candidate-impl.yml@" + BUILDER_COMMIT,
                "sha": BUILDER_COMMIT,
            }]
        run = {
            "id": run_id,
            "head_sha": source,
            "path": ".github/workflows/build-trinyx-backend.yml",
            "conclusion": "success",
            "repository": {"full_name": "TrinyxAI/Trinyx"},
            "referenced_workflows": referenced,
        }
        return artifact, run

    def execute_run_validation(self, env, baseline_historical, candidate_historical, mutate=None):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            artifacts = directory / "artifacts"
            artifacts.mkdir()
            pairs = {}
            for name, historical in (("baseline", baseline_historical), ("candidate", candidate_historical)):
                pairs[name] = self.artifact_run(env, name.upper(), historical)
            if mutate is not None:
                mutate(pairs)
            for name, (artifact, run) in pairs.items():
                (artifacts / f"{name}-metadata.json").write_text(json.dumps(artifact), encoding="utf-8")
                (artifacts / f"{name}-run.json").write_text(json.dumps(run), encoding="utf-8")
            return subprocess.run(
                [sys.executable, "-c", self.run_script], cwd=directory, env=env,
                text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
            )

    def compatibility_namespace(self) -> dict:
        namespace = {"__name__": "qualification_compatibility_test"}
        exec(self.compatibility_script, namespace)
        return namespace

    def policy(self, env, prefix, historical_hashes=None):
        namespace = self.compatibility_namespace()
        if historical_hashes is not None:
            namespace["file_sha256"] = lambda path: historical_hashes[path.name]
        release = {
            "releaseId": env[f"{prefix}_RELEASE_ID"],
            "sourceCommit": env[f"{prefix}_SOURCE_COMMIT"],
            "deploymentBundle": {"digest": env[f"{prefix}_BUNDLE_DIGEST"]},
        }
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw) / prefix.lower()
            directory.mkdir()
            (directory / "release.json").write_text(json.dumps(release), encoding="utf-8")
            return namespace["resolve_attestation_policy"](env, directory, prefix)

    def test_registration_and_qualification_frozen_constants_are_identical(self) -> None:
        for value in (*self.historical_identity("CANDIDATE").values(), *HISTORICAL_INTERNAL.values()):
            if len(value) >= 32:
                self.assertIn(value, self.workflow)
                self.assertIn(value, self.registration)

    def test_exact_historical_and_modern_run_provenance_are_accepted(self) -> None:
        env = self.combined_env(False, True)
        result = self.execute_run_validation(env, False, True)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_historical_run_requires_exact_caller_and_reusable_workflow(self) -> None:
        env = self.combined_env(False, True)
        mutations = (
            lambda pairs: pairs["candidate"][1].__setitem__("path", ".github/workflows/build-release-candidate.yml"),
            lambda pairs: pairs["candidate"][1].__setitem__("referenced_workflows", []),
            lambda pairs: pairs["candidate"][1]["referenced_workflows"][0].__setitem__("sha", "0" * 40),
            lambda pairs: pairs["candidate"][1]["referenced_workflows"][0].__setitem__("ref", "refs/heads/main"),
        )
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                self.assertNotEqual(0, self.execute_run_validation(env, False, True, mutate).returncode)

    def test_modern_run_rejects_missing_attested_builder(self) -> None:
        env = self.combined_env(False, False)
        result = self.execute_run_validation(
            env, False, False,
            lambda pairs: pairs["candidate"][1].__setitem__("referenced_workflows", []),
        )
        self.assertNotEqual(0, result.returncode)

    def test_historical_policy_is_independent_for_baseline_and_candidate(self) -> None:
        for prefix in ("BASELINE", "CANDIDATE"):
            with self.subTest(prefix=prefix):
                env = self.combined_env(prefix == "BASELINE", prefix == "CANDIDATE")
                self.assertEqual(
                    {
                        "SIGNER_WORKFLOW": "build-release-candidate.yml",
                        "SIGNER_DIGEST": HISTORICAL_SOURCE,
                        "COMPATIBILITY": "frozen-historical-builder",
                        "REQUIRE_INTERNAL_ATTESTATIONS": "false",
                    },
                    self.policy(env, prefix, HISTORICAL_INTERNAL),
                )

    def test_every_historical_identity_drift_requires_modern_attestations(self) -> None:
        namespace = self.compatibility_namespace()
        env = self.combined_env(False, True)
        for key in namespace["HISTORICAL_IDENTITY"]:
            with self.subTest(key=key):
                changed = env.copy()
                changed[key if key.startswith("GITHUB_") else "CANDIDATE_" + key] = "wrong"
                policy = self.policy(changed, "CANDIDATE")
                self.assertEqual("true", policy["REQUIRE_INTERNAL_ATTESTATIONS"])
                self.assertEqual("pinned-reusable-builder", policy["COMPATIBILITY"])

    def test_historical_internal_hash_drift_is_rejected(self) -> None:
        env = self.combined_env(False, True)
        for name in HISTORICAL_INTERNAL:
            with self.subTest(name=name):
                hashes = HISTORICAL_INTERNAL.copy()
                hashes[name] = "0" * 64
                with self.assertRaises(SystemExit):
                    self.policy(env, "CANDIDATE", hashes)

    def modern_policy(self):
        return {
            "SIGNER_WORKFLOW": "build-release-candidate-impl.yml",
            "SIGNER_DIGEST": BUILDER_COMMIT,
            "COMPATIBILITY": "pinned-reusable-builder",
            "REQUIRE_INTERNAL_ATTESTATIONS": "true",
        }

    def historical_policy(self):
        return {
            "SIGNER_WORKFLOW": "build-release-candidate.yml",
            "SIGNER_DIGEST": HISTORICAL_SOURCE,
            "COMPATIBILITY": "frozen-historical-builder",
            "REQUIRE_INTERNAL_ATTESTATIONS": "false",
        }

    def run_attestations(self, baseline_policy, candidate_policy, fail_gh=False):
        bash = shutil.which("bash")
        if bash is None:
            self.skipTest("bash is required for workflow shell tests")
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            fake_bin = directory / "bin"
            fake_bin.mkdir()
            log = directory / "gh.log"
            gh = fake_bin / "gh"
            gh.write_text(
                "#!/usr/bin/env bash\nprintf '%s\\n' \"$*\" >> \"$GH_LOG\"\n"
                "[[ \"$FAIL_GH\" != 1 ]] || { echo 'HTTP 404: Not Found' >&2; exit 22; }\n",
                encoding="utf-8",
            )
            gh.chmod(0o755)
            for name, policy in (("baseline", baseline_policy), ("candidate", candidate_policy)):
                (directory / f"{name}-attestation-policy.env").write_text(
                    "".join(f"{key}={value}\n" for key, value in policy.items()), encoding="utf-8"
                )
            env = self.combined_env(False, False)
            env.update({
                "PATH": str(fake_bin) + os.pathsep + env.get("PATH", ""),
                "GH_LOG": str(log), "FAIL_GH": "1" if fail_gh else "0",
            })
            result = subprocess.run(
                [bash, "-c", "set -euo pipefail\n" + self.attestation_script],
                cwd=directory, env=env, text=True,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
            )
            return result, log.read_text(encoding="utf-8") if log.exists() else ""

    def test_modern_baseline_and_candidate_require_four_attestations_each(self) -> None:
        result, log = self.run_attestations(self.modern_policy(), self.modern_policy())
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(8, log.count("attestation verify"))

    def test_historical_entry_skips_only_its_internal_attestations(self) -> None:
        for historical_name in ("baseline", "candidate"):
            with self.subTest(historical_name=historical_name):
                policies = {"baseline": self.modern_policy(), "candidate": self.modern_policy()}
                policies[historical_name] = self.historical_policy()
                result, log = self.run_attestations(policies["baseline"], policies["candidate"])
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(4, log.count("attestation verify"))
                self.assertNotIn(historical_name + "/", log)

    def test_unexpected_policy_and_modern_404_fail_closed(self) -> None:
        bad = self.modern_policy()
        bad["REQUIRE_INTERNAL_ATTESTATIONS"] = "garbage"
        result, _ = self.run_attestations(bad, self.modern_policy())
        self.assertNotEqual(0, result.returncode)
        self.assertIn("ERROR_INVALID_ATTESTATION_POLICY", result.stderr)
        result, _ = self.run_attestations(self.modern_policy(), self.modern_policy(), fail_gh=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("HTTP 404: Not Found", result.stderr)

    def write_release(self, directory, env, prefix):
        directory.mkdir()
        (directory / "release.json").write_text(
            json.dumps({
                "releaseId": env[f"{prefix}_RELEASE_ID"],
                "sourceCommit": env[f"{prefix}_SOURCE_COMMIT"],
                "deploymentBundle": {"digest": env[f"{prefix}_BUNDLE_DIGEST"]},
            }), encoding="utf-8",
        )

    def run_provenance(self, baseline_historical, candidate_historical):
        env = self.combined_env(baseline_historical, candidate_historical)
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            for name, historical in (("baseline", baseline_historical), ("candidate", candidate_historical)):
                self.write_release(directory / name, env, name.upper())
                policy = self.historical_policy() if historical else self.modern_policy()
                (directory / f"{name}-attestation-policy.env").write_text(
                    "".join(f"{key}={value}\n" for key, value in policy.items()), encoding="utf-8"
                )
            result = subprocess.run(
                [sys.executable, "-c", self.provenance_script], cwd=directory, env=env,
                text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            return tuple(
                json.loads((directory / name / "provenance.json").read_text(encoding="utf-8"))
                for name in ("baseline", "candidate")
            )

    def test_historical_and_modern_provenance_are_exact(self) -> None:
        baseline, candidate = self.run_provenance(True, False)
        self.assertEqual(("build-release-candidate.yml", HISTORICAL_SOURCE, "frozen-historical-builder"),
                         (baseline["signerWorkflow"], baseline["signerDigest"], baseline["compatibility"]))
        self.assertEqual(("build-release-candidate-impl.yml", BUILDER_COMMIT, "pinned-reusable-builder"),
                         (candidate["signerWorkflow"], candidate["signerDigest"], candidate["compatibility"]))

    def test_package_safe_validation_reaches_baseline_and_candidate(self) -> None:
        expected = (
            'PYTHONPATH=platform python3 -c "from pathlib import Path; '
            "from automation.release_registry import validate_candidate; "
            "validate_candidate(Path('baseline')); validate_candidate(Path('candidate'))\""
        )
        self.assertIn(expected, self.workflow)
        self.assertNotIn("from platform.automation.release_registry import validate_candidate", self.workflow)
        bash = shutil.which("bash")
        if bash is None:
            self.skipTest("bash is required to execute the exact workflow command")
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            package = directory / "platform" / "automation"
            package.mkdir(parents=True)
            (package / "__init__.py").write_text("", encoding="utf-8")
            (package / "release_registry.py").write_text(
                "def validate_candidate(path):\n"
                "    with open('validation-calls.txt', 'a', encoding='utf-8') as handle:\n"
                "        handle.write(path.as_posix() + '\\n')\n",
                encoding="utf-8",
            )
            result = subprocess.run(
                [bash, "-c", expected], cwd=directory, text=True,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(["baseline", "candidate"],
                             (directory / "validation-calls.txt").read_text(encoding="utf-8").splitlines())

    def test_both_candidates_are_validated_before_aws_credentials(self) -> None:
        validation = "validate_candidate(Path('baseline')); validate_candidate(Path('candidate'))"
        self.assertLess(
            self.workflow.index(validation),
            self.workflow.index("- name: Assume release publisher role only"),
        )


if __name__ == "__main__":
    unittest.main()
