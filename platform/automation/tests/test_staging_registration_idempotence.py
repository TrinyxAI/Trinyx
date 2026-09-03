from __future__ import annotations

import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / ".github/workflows/staging-release-register-impl.yml"
REGISTRY = ROOT / "platform/automation/release_registry.py"

FROZEN_ENV = {
    "GITHUB_REPOSITORY": "TrinyxAI/Trinyx",
    "GITHUB_REPOSITORY_ID": "1342032975",
    "GITHUB_REPOSITORY_OWNER_ID": "319253481",
    "RUN_ID": "33485509832",
    "ARTIFACT_ID": "9791964215",
    "ARTIFACT_DIGEST": "sha256:755594078d9da7e19406e01187534132920a31f87804c1b33baa28fa96559152",
    "SOURCE_COMMIT": "f3a4c1ddcf6a17bfc837071f9046ac4c38a38b47",
    "RELEASE_ID": "rel-v1-b5ba70c23b9f529ac8228a7b00b4faa4",
    "BUNDLE_DIGEST": "sha256:c9df14dcd1dbc24b31b926d3778bef2e208b59824c78f24292608284f3579892",
}
FROZEN_INTERNAL_SHA256 = {
    "release.json": "ad5a5b702d9659e0af5d5b82a422953ba2390a94396949f897757568c9b59789",
    "release-images.json": "fe1134c3920af0f2f9f0027082f25ec5adb1cbb6d41a1053bada7ef730f66a8a",
    "deployment-bundle.json": "b101918414ee9d113d4ef54d32c9f438005d8ebee7bde2e62f72d58a16cfdd7b",
    "deployment-bundle.tar": "c9df14dcd1dbc24b31b926d3778bef2e208b59824c78f24292608284f3579892",
}
FROZEN_PROVENANCE = {
    "schemaVersion": 2,
    "repository": "TrinyxAI/Trinyx",
    "signerWorkflow": "build-release-candidate.yml",
    "signerDigest": FROZEN_ENV["SOURCE_COMMIT"],
    "compatibility": "frozen-historical-builder",
    "sourceCommit": FROZEN_ENV["SOURCE_COMMIT"],
    "artifactId": FROZEN_ENV["ARTIFACT_ID"],
    "runId": FROZEN_ENV["RUN_ID"],
    "artifactDigest": FROZEN_ENV["ARTIFACT_DIGEST"],
}


class StagingRegistrationIdempotenceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.provenance_script = cls.extract(
            "STAGING_RELEASE_DETERMINISTIC_PROVENANCE"
        )
        cls.equivalence_script = cls.extract(
            "STAGING_RELEASE_EXISTING_FROZEN_EQUIVALENCE"
        )
        cls.registry_script = cls.extract(
            "STAGING_RELEASE_REGISTRY_IDEMPOTENCE"
        )

    @classmethod
    def extract(cls, name: str) -> str:
        match = re.search(
            rf"# BEGIN {name}\n(?P<body>.*?)# END {name}",
            cls.workflow,
            re.DOTALL,
        )
        if match is None:
            raise AssertionError(f"workflow block is missing: {name}")
        return textwrap.dedent(match.group("body"))

    def provenance_env(self) -> dict[str, str]:
        env = os.environ.copy()
        env.update(
            {
                "SIGNER_WORKFLOW": "build-release-candidate.yml",
                "SIGNER_DIGEST": FROZEN_ENV["SOURCE_COMMIT"],
                "COMPATIBILITY": "frozen-historical-builder",
                "ARTIFACT_ID": FROZEN_ENV["ARTIFACT_ID"],
                "RUN_ID": FROZEN_ENV["RUN_ID"],
                "ARTIFACT_DIGEST": FROZEN_ENV["ARTIFACT_DIGEST"],
                "RELEASE_ID": FROZEN_ENV["RELEASE_ID"],
                "SOURCE_COMMIT": FROZEN_ENV["SOURCE_COMMIT"],
                "BUNDLE_DIGEST": FROZEN_ENV["BUNDLE_DIGEST"],
            }
        )
        return env

    def generate_provenance(
        self, root: Path, created_at: str = "2026-08-31T16:51:21Z"
    ) -> subprocess.CompletedProcess[str]:
        candidate = root / "candidate"
        candidate.mkdir(parents=True)
        (candidate / "release.json").write_text(
            json.dumps(
                {
                    "releaseId": FROZEN_ENV["RELEASE_ID"],
                    "sourceCommit": FROZEN_ENV["SOURCE_COMMIT"],
                    "deploymentBundle": {
                        "digest": FROZEN_ENV["BUNDLE_DIGEST"],
                        "fileCount": 13,
                    },
                }
            ),
            encoding="utf-8",
        )
        (root / "verified-build-created-at.txt").write_text(
            created_at + "\n", encoding="utf-8"
        )
        return subprocess.run(
            [sys.executable, "-c", self.provenance_script],
            cwd=root,
            env=self.provenance_env(),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def test_two_independent_generations_from_same_build_are_byte_identical(self) -> None:
        with tempfile.TemporaryDirectory() as first_raw, tempfile.TemporaryDirectory() as second_raw:
            first = Path(first_raw)
            second = Path(second_raw)
            first_result = self.generate_provenance(first)
            second_result = self.generate_provenance(second)
            self.assertEqual(0, first_result.returncode, first_result.stderr)
            self.assertEqual(0, second_result.returncode, second_result.stderr)
            first_bytes = (first / "candidate/provenance.json").read_bytes()
            second_bytes = (second / "candidate/provenance.json").read_bytes()

        self.assertEqual(first_bytes, second_bytes)
        provenance = json.loads(first_bytes)
        self.assertEqual("2026-08-31T16:51:21Z", provenance["verifiedAt"])
        self.assertNotIn("datetime.now", self.provenance_script)
        self.assertNotIn("datetime.datetime.now", self.workflow)

    def test_verified_build_timestamp_must_be_strict_utc_rfc3339(self) -> None:
        for value in (
            "2026-08-31 16:51:21Z",
            "2026-08-31T16:51:21+00:00",
            "2026-08-31T16:51:21",
            "not-a-time",
        ):
            with self.subTest(value=value), tempfile.TemporaryDirectory() as raw:
                result = self.generate_provenance(Path(raw), value)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("invalid verified build created_at", result.stderr)

    def equivalence_namespace(self) -> dict[str, object]:
        namespace: dict[str, object] = {"__name__": "workflow_test"}
        exec(self.equivalence_script, namespace)
        return namespace

    def write_equivalence_candidates(
        self, root: Path, *, stored_verified_at: str = "2026-09-03T10:00:00Z"
    ) -> tuple[Path, Path, dict[str, bytes]]:
        candidate = root / "candidate"
        stored = root / "stored"
        candidate.mkdir()
        stored.mkdir()
        payloads = {
            "release.json": b"release-bytes\n",
            "release-images.json": b"images-bytes\n",
            "deployment-bundle.json": b"bundle-manifest-bytes\n",
            "deployment-bundle.tar": b"bundle-tar-bytes\n",
        }
        for name, content in payloads.items():
            (candidate / name).write_bytes(content)
            (stored / name).write_bytes(content)

        local_provenance = dict(FROZEN_PROVENANCE)
        local_provenance["verifiedAt"] = "2026-08-31T16:51:21Z"
        stored_provenance = dict(FROZEN_PROVENANCE)
        stored_provenance["verifiedAt"] = stored_verified_at
        for directory, provenance in (
            (candidate, local_provenance),
            (stored, stored_provenance),
        ):
            (directory / "provenance.json").write_text(
                json.dumps(provenance), encoding="utf-8"
            )
        return candidate, stored, payloads

    def run_equivalence(
        self,
        root: Path,
        *,
        env: dict[str, str] | None = None,
        stored_verified_at: str = "2026-09-03T10:00:00Z",
        mutate=None,
    ) -> None:
        candidate, stored, payloads = self.write_equivalence_candidates(
            root, stored_verified_at=stored_verified_at
        )
        if mutate is not None:
            mutate(candidate, stored)
        namespace = self.equivalence_namespace()
        namespace["FROZEN_INTERNAL_SHA256"] = {
            name: hashlib.sha256(content).hexdigest()
            for name, content in payloads.items()
        }
        verifier = namespace["verify_existing_frozen_registration"]
        verifier(env or dict(FROZEN_ENV), candidate, stored)

    def test_frozen_constants_are_exact_and_only_verified_at_may_differ(self) -> None:
        namespace = self.equivalence_namespace()
        self.assertEqual(FROZEN_ENV, namespace["FROZEN_ENVIRONMENT"])
        self.assertEqual(FROZEN_INTERNAL_SHA256, namespace["FROZEN_INTERNAL_SHA256"])
        self.assertEqual(FROZEN_PROVENANCE, namespace["FROZEN_PROVENANCE"])
        with tempfile.TemporaryDirectory() as raw:
            self.run_equivalence(Path(raw))

    def test_non_provenance_objects_must_be_byte_identical(self) -> None:
        for name in FROZEN_INTERNAL_SHA256:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as raw:
                def mutate(_candidate: Path, stored: Path, target=name) -> None:
                    (stored / target).write_bytes(b"different")

                with self.assertRaisesRegex(
                    SystemExit, "stored frozen object differs:" + re.escape(name)
                ):
                    self.run_equivalence(Path(raw), mutate=mutate)

    def test_every_frozen_identity_field_is_exact(self) -> None:
        for name in FROZEN_ENV:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as raw:
                env = dict(FROZEN_ENV)
                env[name] += "-drift"
                with self.assertRaisesRegex(
                    SystemExit,
                    "existing frozen compatibility identity mismatch:" + name,
                ):
                    self.run_equivalence(Path(raw), env=env)

    def test_every_provenance_field_except_verified_at_is_exact(self) -> None:
        for name in FROZEN_PROVENANCE:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as raw:
                def mutate(_candidate: Path, stored: Path, target=name) -> None:
                    provenance = json.loads(
                        (stored / "provenance.json").read_text(encoding="utf-8")
                    )
                    provenance[target] = (
                        provenance[target] + "-drift"
                        if isinstance(provenance[target], str)
                        else 99
                    )
                    (stored / "provenance.json").write_text(
                        json.dumps(provenance), encoding="utf-8"
                    )

                with self.assertRaisesRegex(
                    SystemExit, "frozen provenance mismatch:" + name
                ):
                    self.run_equivalence(Path(raw), mutate=mutate)

    def test_provenance_schema_and_both_timestamps_are_strict(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            def mutate(_candidate: Path, stored: Path) -> None:
                provenance = json.loads(
                    (stored / "provenance.json").read_text(encoding="utf-8")
                )
                provenance["unexpected"] = True
                (stored / "provenance.json").write_text(
                    json.dumps(provenance), encoding="utf-8"
                )

            with self.assertRaisesRegex(SystemExit, "frozen provenance schema mismatch"):
                self.run_equivalence(Path(raw), mutate=mutate)

        for stored_time in ("invalid", "2026-09-03T10:00:00+00:00"):
            with self.subTest(stored_time=stored_time), tempfile.TemporaryDirectory() as raw:
                with self.assertRaisesRegex(
                    SystemExit, "invalid frozen provenance verifiedAt"
                ):
                    self.run_equivalence(
                        Path(raw), stored_verified_at=stored_time
                    )

    def fake_tooling(self, root: Path) -> Path:
        bin_dir = root / "bin"
        bin_dir.mkdir()
        python_wrapper = bin_dir / "python3"
        python_wrapper.write_text(
            """#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "-" ]]; then
  script="$(cat)"
  if grep -q "STAGING_RELEASE_EXISTING_FROZEN_EQUIVALENCE" <<<"$script"; then
    [[ "${FAKE_EQUIVALENCE_FAIL:-0}" != "1" ]]
    exit
  fi
  exec "$REAL_PYTHON" -c "$script"
fi
if [[ "${1:-}" == "-c" && "${2:-}" == *"validate_candidate"* ]]; then
  [[ "${FAKE_VALIDATE_FAIL:-0}" != "1" ]]
  exit
fi
exec "$REAL_PYTHON" "$@"
""",
            encoding="utf-8",
        )
        aws = bin_dir / "aws"
        aws.write_text(
            """#!/usr/bin/env bash
set -euo pipefail
printf 'called\n' > aws.called
case "${FAKE_AWS_MODE:-exists}" in
  exists) exit 0 ;;
  missing)
    echo "An error occurred (404) when calling the HeadObject operation: Not Found" >&2
    exit 255
    ;;
  denied)
    echo "An error occurred (AccessDenied) when calling the HeadObject operation: Access Denied" >&2
    exit 254
    ;;
esac
exit 253
""",
            encoding="utf-8",
        )
        for executable in (python_wrapper, aws):
            executable.chmod(
                executable.stat().st_mode
                | stat.S_IXUSR
                | stat.S_IXGRP
                | stat.S_IXOTH
            )

        registry = root / "platform/automation/release_registry.py"
        registry.parent.mkdir(parents=True)
        registry.write_text(
            """from pathlib import Path
import os
import sys

command = sys.argv[1]
if command == "fetch":
    Path("fetch.called").write_text("called\n", encoding="utf-8")
    if os.environ.get("FAKE_FETCH_FAIL") == "1":
        raise SystemExit(71)
    destination = Path(sys.argv[sys.argv.index("--destination") + 1])
    destination.mkdir()
elif command == "register":
    Path("register.called").write_text("called\n", encoding="utf-8")
    if os.environ.get("FAKE_REGISTER_FAIL") == "1":
        raise SystemExit(72)
else:
    raise SystemExit(73)
""",
            encoding="utf-8",
        )
        return bin_dir

    def run_registry_protocol(
        self,
        *,
        compatibility: str,
        aws_mode: str = "exists",
        extra_env: dict[str, str] | None = None,
    ) -> tuple[subprocess.CompletedProcess[str], set[str]]:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            bin_dir = self.fake_tooling(root)
            candidate = root / "candidate"
            candidate.mkdir()
            provenance = dict(FROZEN_PROVENANCE)
            provenance["compatibility"] = compatibility
            provenance["verifiedAt"] = "2026-08-31T16:51:21Z"
            (candidate / "provenance.json").write_text(
                json.dumps(provenance), encoding="utf-8"
            )
            env = os.environ.copy()
            env.update(FROZEN_ENV)
            env.update(
                {
                    "REGISTRY_BUCKET": "test-registry",
                    "REAL_PYTHON": sys.executable,
                    "FAKE_AWS_MODE": aws_mode,
                    "PATH": str(bin_dir) + os.pathsep + env.get("PATH", ""),
                }
            )
            if extra_env:
                env.update(extra_env)
            result = subprocess.run(
                ["bash", "-euo", "pipefail", "-c", self.registry_script],
                cwd=root,
                env=env,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
            calls = {
                path.name
                for path in root.glob("*.called")
                if path.is_file()
            }
            return result, calls

    def test_existing_exact_frozen_registration_is_read_only_success(self) -> None:
        result, calls = self.run_registry_protocol(
            compatibility="frozen-historical-builder"
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"aws.called", "fetch.called"}, calls)
        self.assertIn("existing_frozen_registration=true", result.stdout)
        self.assertNotIn("register.called", calls)

    def test_missing_frozen_marker_uses_normal_immutable_registration(self) -> None:
        result, calls = self.run_registry_protocol(
            compatibility="frozen-historical-builder", aws_mode="missing"
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            {"aws.called", "register.called"},
            calls,
        )

    def test_non_not_found_s3_errors_fail_closed_without_registration(self) -> None:
        result, calls = self.run_registry_protocol(
            compatibility="frozen-historical-builder", aws_mode="denied"
        )
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({"aws.called"}, calls)
        self.assertIn("AccessDenied", result.stderr)

    def test_invalid_stored_candidate_or_equivalence_fails_without_registration(self) -> None:
        for variable in ("FAKE_FETCH_FAIL", "FAKE_VALIDATE_FAIL", "FAKE_EQUIVALENCE_FAIL"):
            with self.subTest(variable=variable):
                result, calls = self.run_registry_protocol(
                    compatibility="frozen-historical-builder",
                    extra_env={variable: "1"},
                )
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("register.called", calls)

    def test_modern_path_never_uses_historical_semantic_equivalence(self) -> None:
        result, calls = self.run_registry_protocol(
            compatibility="pinned-reusable-builder",
            aws_mode="denied",
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"register.called"}, calls)
        self.assertNotIn("aws.called", calls)

    def test_unknown_compatibility_is_rejected(self) -> None:
        result, calls = self.run_registry_protocol(compatibility="unknown")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(set(), calls)

    def test_security_order_and_registry_commit_marker_contract_are_preserved(self) -> None:
        validate = self.workflow.index(
            "validate_candidate(Path('candidate'))"
        )
        credentials = self.workflow.index(
            "- name: Assume publisher-only AWS role"
        )
        registry = self.workflow.index(
            "# BEGIN STAGING_RELEASE_REGISTRY_IDEMPOTENCE"
        )
        self.assertLess(validate, credentials)
        self.assertLess(credentials, registry)

        registry_source = REGISTRY.read_text(encoding="utf-8")
        object_loop = registry_source.index("for name in OBJECT_FILES:")
        marker_write = registry_source.index(
            'registry.put_if_absent(f"{prefix}/registration.json"'
        )
        self.assertLess(object_loop, marker_write)
        self.assertIn("--if-none-match", registry_source)
        self.assertNotIn("delete-object", self.registry_script)
        self.assertNotIn("put-object", self.registry_script)


if __name__ == "__main__":
    unittest.main()
