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
QUALIFICATION = ROOT / ".github/workflows/staging-qualification-impl.yml"
REGISTRATION = ROOT / ".github/workflows/staging-release-register-impl.yml"
REGISTRY = ROOT / "platform/automation/release_registry.py"

FROZEN = {
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
FROZEN_HASHES = {
    "release.json": "ad5a5b702d9659e0af5d5b82a422953ba2390a94396949f897757568c9b59789",
    "release-images.json": "fe1134c3920af0f2f9f0027082f25ec5adb1cbb6d41a1053bada7ef730f66a8a",
    "deployment-bundle.json": "b101918414ee9d113d4ef54d32c9f438005d8ebee7bde2e62f72d58a16cfdd7b",
    "deployment-bundle.tar": "c9df14dcd1dbc24b31b926d3778bef2e208b59824c78f24292608284f3579892",
}
FROZEN_PROVENANCE = {
    "schemaVersion": 2,
    "repository": "TrinyxAI/Trinyx",
    "signerWorkflow": "build-release-candidate.yml",
    "signerDigest": FROZEN["SOURCE_COMMIT"],
    "compatibility": "frozen-historical-builder",
    "sourceCommit": FROZEN["SOURCE_COMMIT"],
    "artifactId": FROZEN["ARTIFACT_ID"],
    "runId": FROZEN["RUN_ID"],
    "artifactDigest": FROZEN["ARTIFACT_DIGEST"],
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


class StagingQualificationIdempotenceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = QUALIFICATION.read_text(encoding="utf-8")
        cls.registration = REGISTRATION.read_text(encoding="utf-8")
        cls.run_script = extract(
            cls.workflow, "STAGING_QUALIFICATION_RUN_PROVENANCE_VALIDATION"
        )
        cls.provenance_script = extract(
            cls.workflow, "STAGING_QUALIFICATION_PROVENANCE_GENERATION"
        )
        cls.equivalence_script = extract(
            cls.workflow, "STAGING_QUALIFICATION_EXISTING_FROZEN_EQUIVALENCE"
        )
        cls.registry_script = extract(
            cls.workflow, "STAGING_QUALIFICATION_REGISTRY_IDEMPOTENCE"
        )
        cls.registration_equivalence = extract(
            cls.registration, "STAGING_RELEASE_EXISTING_FROZEN_EQUIVALENCE"
        )

    def identity(self, prefix: str, historical: bool) -> dict[str, str]:
        if historical:
            values = FROZEN
        else:
            baseline = prefix == "BASELINE"
            values = {
                "GITHUB_REPOSITORY": "TrinyxAI/Trinyx",
                "GITHUB_REPOSITORY_ID": "1342032975",
                "GITHUB_REPOSITORY_OWNER_ID": "319253481",
                "RUN_ID": "40000000000" if baseline else "40000000001",
                "ARTIFACT_ID": "50000000000" if baseline else "50000000001",
                "ARTIFACT_DIGEST": "sha256:" + ("a" if baseline else "b") * 64,
                "SOURCE_COMMIT": ("c" if baseline else "d") * 40,
                "RELEASE_ID": "rel-v1-" + ("e" if baseline else "f") * 32,
                "BUNDLE_DIGEST": "sha256:" + ("1" if baseline else "2") * 64,
            }
        result = {
            "GITHUB_REPOSITORY": values["GITHUB_REPOSITORY"],
            "GITHUB_REPOSITORY_ID": values["GITHUB_REPOSITORY_ID"],
            "GITHUB_REPOSITORY_OWNER_ID": values["GITHUB_REPOSITORY_OWNER_ID"],
        }
        for key in (
            "RUN_ID",
            "ARTIFACT_ID",
            "ARTIFACT_DIGEST",
            "SOURCE_COMMIT",
            "RELEASE_ID",
            "BUNDLE_DIGEST",
        ):
            result[f"{prefix}_{key}"] = values[key]
        return result

    def environment(
        self, baseline_historical: bool, candidate_historical: bool
    ) -> dict[str, str]:
        env = os.environ.copy()
        env.update(self.identity("BASELINE", baseline_historical))
        env.update(self.identity("CANDIDATE", candidate_historical))
        env.update(
            {
                "BUILDER_WORKFLOW_COMMIT": "114a2613e8090f034925a1bcf148f055653c3a06",
                "REGISTRY_BUCKET": "test-registry",
                "REAL_PYTHON": sys.executable,
            }
        )
        return env

    def artifact_run(
        self,
        env: dict[str, str],
        prefix: str,
        historical: bool,
        created_at: str,
    ) -> tuple[dict, dict]:
        source = env[f"{prefix}_SOURCE_COMMIT"]
        run_id = int(env[f"{prefix}_RUN_ID"])
        artifact = {
            "id": int(env[f"{prefix}_ARTIFACT_ID"]),
            "name": "trinyx-release-candidate-" + source,
            "expired": False,
            "workflow_run": {"id": run_id, "head_sha": source},
        }
        if historical:
            referenced = [
                {
                    "path": (
                        "TrinyxAI/Trinyx/.github/workflows/"
                        "build-release-candidate.yml@" + FROZEN["SOURCE_COMMIT"]
                    ),
                    "sha": FROZEN["SOURCE_COMMIT"],
                    "ref": "refs/heads/codex/platform-release-automation",
                }
            ]
        else:
            builder = env["BUILDER_WORKFLOW_COMMIT"]
            referenced = [
                {
                    "path": (
                        "TrinyxAI/Trinyx/.github/workflows/"
                        "build-release-candidate-impl.yml@" + builder
                    ),
                    "sha": builder,
                }
            ]
        run = {
            "id": run_id,
            "head_sha": source,
            "path": ".github/workflows/build-trinyx-backend.yml",
            "conclusion": "success",
            "created_at": created_at,
            "repository": {"full_name": "TrinyxAI/Trinyx"},
            "referenced_workflows": referenced,
        }
        return artifact, run

    def prepare_run_metadata(
        self,
        root: Path,
        env: dict[str, str],
        baseline_historical: bool,
        candidate_historical: bool,
        baseline_time: str,
        candidate_time: str,
    ) -> None:
        artifacts = root / "artifacts"
        artifacts.mkdir()
        for name, historical, created_at in (
            ("baseline", baseline_historical, baseline_time),
            ("candidate", candidate_historical, candidate_time),
        ):
            artifact, run = self.artifact_run(
                env, name.upper(), historical, created_at
            )
            (artifacts / f"{name}-metadata.json").write_text(
                json.dumps(artifact), encoding="utf-8"
            )
            (artifacts / f"{name}-run.json").write_text(
                json.dumps(run), encoding="utf-8"
            )

    def test_verified_build_timestamps_are_independent_and_strict(self) -> None:
        env = self.environment(False, True)
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            self.prepare_run_metadata(
                root,
                env,
                False,
                True,
                "2026-08-30T10:00:00Z",
                "2026-08-31T11:22:33.123456Z",
            )
            result = subprocess.run(
                [sys.executable, "-c", self.run_script],
                cwd=root,
                env=env,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                "2026-08-30T10:00:00Z",
                (root / "artifacts/baseline-verified-build-created-at.txt")
                .read_text(encoding="utf-8")
                .strip(),
            )
            self.assertEqual(
                "2026-08-31T11:22:33.123456Z",
                (root / "artifacts/candidate-verified-build-created-at.txt")
                .read_text(encoding="utf-8")
                .strip(),
            )

        for bad in ("not-a-time", "2026-08-30T10:00:00+00:00"):
            with self.subTest(bad=bad), tempfile.TemporaryDirectory() as raw:
                root = Path(raw)
                self.prepare_run_metadata(
                    root, env, False, True, bad, "2026-08-31T11:22:33Z"
                )
                result = subprocess.run(
                    [sys.executable, "-c", self.run_script],
                    cwd=root,
                    env=env,
                    text=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    check=False,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn(
                    "invalid verified build run created_at:baseline",
                    result.stderr,
                )

    def write_provenance_inputs(
        self,
        root: Path,
        env: dict[str, str],
        baseline_time: str,
        candidate_time: str,
        baseline_historical: bool,
        candidate_historical: bool,
    ) -> None:
        artifacts = root / "artifacts"
        artifacts.mkdir()
        for name, historical, created_at in (
            ("baseline", baseline_historical, baseline_time),
            ("candidate", candidate_historical, candidate_time),
        ):
            prefix = name.upper()
            directory = root / name
            directory.mkdir()
            release = {
                "releaseId": env[f"{prefix}_RELEASE_ID"],
                "sourceCommit": env[f"{prefix}_SOURCE_COMMIT"],
                "deploymentBundle": {
                    "digest": env[f"{prefix}_BUNDLE_DIGEST"],
                },
            }
            (directory / "release.json").write_text(
                json.dumps(release), encoding="utf-8"
            )
            policy = (
                {
                    "SIGNER_WORKFLOW": "build-release-candidate.yml",
                    "SIGNER_DIGEST": FROZEN["SOURCE_COMMIT"],
                    "ATTESTATION_SOURCE_DIGEST": FROZEN["SOURCE_COMMIT"],
                    "COMPATIBILITY": "frozen-historical-builder",
                    "REQUIRE_INTERNAL_ATTESTATIONS": "false",
                }
                if historical
                else {
                    "SIGNER_WORKFLOW": "build-release-candidate-impl.yml",
                    "SIGNER_DIGEST": env["BUILDER_WORKFLOW_COMMIT"],
                    "ATTESTATION_SOURCE_DIGEST": env[f"{prefix}_SOURCE_COMMIT"],
                    "COMPATIBILITY": "pinned-reusable-builder",
                    "REQUIRE_INTERNAL_ATTESTATIONS": "true",
                }
            )
            (root / f"{name}-attestation-policy.env").write_text(
                "".join(f"{key}={value}\n" for key, value in policy.items()),
                encoding="utf-8",
            )
            (artifacts / f"{name}-verified-build-created-at.txt").write_text(
                created_at + "\n", encoding="utf-8"
            )

    def generate_pair(
        self,
        root: Path,
        env: dict[str, str],
        baseline_time: str = "2026-08-30T10:00:00Z",
        candidate_time: str = "2026-08-31T11:22:33Z",
        baseline_historical: bool = False,
        candidate_historical: bool = True,
    ) -> subprocess.CompletedProcess[str]:
        self.write_provenance_inputs(
            root,
            env,
            baseline_time,
            candidate_time,
            baseline_historical,
            candidate_historical,
        )
        return subprocess.run(
            [sys.executable, "-c", self.provenance_script],
            cwd=root,
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def test_baseline_and_candidate_provenance_are_deterministic(self) -> None:
        env = self.environment(False, True)
        outputs: list[tuple[bytes, bytes]] = []
        for _ in range(2):
            with tempfile.TemporaryDirectory() as raw:
                root = Path(raw)
                result = self.generate_pair(root, env)
                self.assertEqual(0, result.returncode, result.stderr)
                outputs.append(
                    (
                        (root / "baseline/provenance.json").read_bytes(),
                        (root / "candidate/provenance.json").read_bytes(),
                    )
                )
        self.assertEqual(outputs[0], outputs[1])
        baseline = json.loads(outputs[0][0])
        candidate = json.loads(outputs[0][1])
        self.assertEqual("2026-08-30T10:00:00Z", baseline["verifiedAt"])
        self.assertEqual("2026-08-31T11:22:33Z", candidate["verifiedAt"])
        self.assertEqual("pinned-reusable-builder", baseline["compatibility"])
        self.assertEqual("frozen-historical-builder", candidate["compatibility"])
        self.assertNotIn("datetime.now", self.provenance_script)
        self.assertNotIn("datetime.datetime.now", self.workflow)

    def equivalence_namespace(self) -> dict[str, object]:
        namespace: dict[str, object] = {"__name__": "qualification_test"}
        exec(self.equivalence_script, namespace)
        return namespace

    def write_equivalence_pair(
        self, root: Path, name: str, stored_time: str = "2026-09-03T10:00:00Z"
    ) -> dict[str, bytes]:
        candidate = root / name
        stored = root / f"existing-{name}-registration"
        candidate.mkdir()
        stored.mkdir()
        payloads = {
            "release.json": b"release-bytes\n",
            "release-images.json": b"images-bytes\n",
            "deployment-bundle.json": b"bundle-manifest-bytes\n",
            "deployment-bundle.tar": b"bundle-tar-bytes\n",
        }
        for filename, content in payloads.items():
            (candidate / filename).write_bytes(content)
            (stored / filename).write_bytes(content)
        local = dict(FROZEN_PROVENANCE)
        local["verifiedAt"] = "2026-08-31T11:22:33Z"
        remote = dict(FROZEN_PROVENANCE)
        remote["verifiedAt"] = stored_time
        (candidate / "provenance.json").write_text(
            json.dumps(local), encoding="utf-8"
        )
        (stored / "provenance.json").write_text(
            json.dumps(remote), encoding="utf-8"
        )
        return payloads

    def exact_frozen_environment(self, prefix: str) -> dict[str, str]:
        env = os.environ.copy()
        env.update(self.identity(prefix, True))
        return env

    def verify_equivalence(
        self,
        root: Path,
        name: str,
        *,
        env: dict[str, str] | None = None,
        stored_time: str = "2026-09-03T10:00:00Z",
        mutate=None,
    ) -> None:
        payloads = self.write_equivalence_pair(root, name, stored_time)
        if mutate:
            mutate(root / name, root / f"existing-{name}-registration")
        namespace = self.equivalence_namespace()
        namespace["FROZEN_INTERNAL_SHA256"] = {
            filename: hashlib.sha256(content).hexdigest()
            for filename, content in payloads.items()
        }
        namespace["verify_existing_frozen_registration"](
            env or self.exact_frozen_environment(name.upper()),
            name,
            name.upper(),
            root,
        )

    def test_registration_and_qualification_frozen_constants_match(self) -> None:
        qualification = self.equivalence_namespace()
        registration: dict[str, object] = {"__name__": "registration_test"}
        exec(self.registration_equivalence, registration)
        self.assertEqual(
            registration["FROZEN_ENVIRONMENT"],
            qualification["FROZEN_IDENTITY"],
        )
        self.assertEqual(
            registration["FROZEN_INTERNAL_SHA256"],
            qualification["FROZEN_INTERNAL_SHA256"],
        )
        self.assertEqual(
            registration["FROZEN_PROVENANCE"],
            qualification["FROZEN_PROVENANCE"],
        )

    def test_exact_existing_frozen_baseline_and_candidate_are_accepted(self) -> None:
        for name in ("baseline", "candidate"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as raw:
                self.verify_equivalence(Path(raw), name)

    def test_every_frozen_identity_field_is_exact(self) -> None:
        for name in ("baseline", "candidate"):
            prefix = name.upper()
            for field in FROZEN:
                with self.subTest(name=name, field=field), tempfile.TemporaryDirectory() as raw:
                    env = self.exact_frozen_environment(prefix)
                    key = field if field.startswith("GITHUB_") else f"{prefix}_{field}"
                    env[key] += "-drift"
                    with self.assertRaisesRegex(
                        SystemExit,
                        f"existing frozen compatibility identity mismatch:{name}:{field}",
                    ):
                        self.verify_equivalence(Path(raw), name, env=env)

    def test_four_objects_and_provenance_are_strict(self) -> None:
        for filename in FROZEN_HASHES:
            with self.subTest(filename=filename), tempfile.TemporaryDirectory() as raw:
                def mutate(_local: Path, stored: Path, target=filename) -> None:
                    (stored / target).write_bytes(b"drift")

                with self.assertRaisesRegex(
                    SystemExit, f"stored frozen object differs:baseline:{re.escape(filename)}"
                ):
                    self.verify_equivalence(Path(raw), "baseline", mutate=mutate)

        for field in FROZEN_PROVENANCE:
            with self.subTest(field=field), tempfile.TemporaryDirectory() as raw:
                def mutate(_local: Path, stored: Path, target=field) -> None:
                    path = stored / "provenance.json"
                    value = json.loads(path.read_text(encoding="utf-8"))
                    value[target] = (
                        value[target] + "-drift"
                        if isinstance(value[target], str)
                        else 99
                    )
                    path.write_text(json.dumps(value), encoding="utf-8")

                with self.assertRaisesRegex(
                    SystemExit, f"frozen provenance mismatch:candidate:{field}"
                ):
                    self.verify_equivalence(Path(raw), "candidate", mutate=mutate)

    def test_invalid_stored_timestamp_and_schema_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            with self.assertRaisesRegex(
                SystemExit, "invalid frozen provenance verifiedAt"
            ):
                self.verify_equivalence(Path(raw), "baseline", stored_time="invalid")
        with tempfile.TemporaryDirectory() as raw:
            def mutate(_local: Path, stored: Path) -> None:
                path = stored / "provenance.json"
                value = json.loads(path.read_text(encoding="utf-8"))
                value["unexpected"] = True
                path.write_text(json.dumps(value), encoding="utf-8")

            with self.assertRaisesRegex(
                SystemExit, "frozen provenance schema mismatch:candidate"
            ):
                self.verify_equivalence(Path(raw), "candidate", mutate=mutate)

    def fake_tooling(self, root: Path) -> Path:
        bin_dir = root / "bin"
        bin_dir.mkdir()
        python_wrapper = bin_dir / "python3"
        python_wrapper.write_text(
            """#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "-" ]]; then
  script="$(cat)"
  if grep -q "STAGING_QUALIFICATION_EXISTING_FROZEN_EQUIVALENCE" <<<"$script"; then
    [[ "${FAKE_EQUIVALENCE_FAIL:-}" != "${ENTRY_NAME:-}" ]]
    exit
  fi
  exec "$REAL_PYTHON" -c "$script" "${@:2}"
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
key=""
while (( $# )); do
  if [[ "$1" == "--key" ]]; then key="$2"; shift 2; else shift; fi
done
if [[ "$key" == *"${BASELINE_RELEASE_ID}"* ]]; then
  name=baseline
else
  name=candidate
fi
printf 'called\\n' > "aws.$name.called"
mode_var="FAKE_AWS_${name^^}_MODE"
mode="${!mode_var:-exists}"
case "$mode" in
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
    destination = Path(sys.argv[sys.argv.index("--destination") + 1])
    name = destination.name.removeprefix("existing-").removesuffix("-registration")
    Path(f"fetch.{name}.called").write_text("called\\n", encoding="utf-8")
    if os.environ.get("FAKE_FETCH_FAIL") in {"1", name}:
        raise SystemExit(71)
    destination.mkdir()
elif command == "register":
    name = Path(sys.argv[sys.argv.index("--candidate-dir") + 1]).name
    Path(f"register.{name}.called").write_text("called\\n", encoding="utf-8")
else:
    raise SystemExit(73)
""",
            encoding="utf-8",
        )
        return bin_dir

    def run_registry_protocol(
        self,
        baseline_historical: bool,
        candidate_historical: bool,
        *,
        baseline_mode: str = "exists",
        candidate_mode: str = "exists",
        extra_env: dict[str, str] | None = None,
    ) -> tuple[subprocess.CompletedProcess[str], set[str]]:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            bin_dir = self.fake_tooling(root)
            env = self.environment(baseline_historical, candidate_historical)
            env.update(
                {
                    "PATH": str(bin_dir) + os.pathsep + env.get("PATH", ""),
                    "FAKE_AWS_BASELINE_MODE": baseline_mode,
                    "FAKE_AWS_CANDIDATE_MODE": candidate_mode,
                }
            )
            if extra_env:
                env.update(extra_env)
            for name, historical in (
                ("baseline", baseline_historical),
                ("candidate", candidate_historical),
            ):
                directory = root / name
                directory.mkdir()
                provenance = dict(FROZEN_PROVENANCE)
                provenance["compatibility"] = (
                    "frozen-historical-builder"
                    if historical
                    else "pinned-reusable-builder"
                )
                provenance["verifiedAt"] = "2026-08-31T11:22:33Z"
                (directory / "provenance.json").write_text(
                    json.dumps(provenance), encoding="utf-8"
                )
            result = subprocess.run(
                ["bash", "-euo", "pipefail", "-c", self.registry_script],
                cwd=root,
                env=env,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
            calls = {path.name for path in root.glob("*.called")}
            return result, calls

    def test_historical_and_modern_registration_paths_are_independent(self) -> None:
        result, calls = self.run_registry_protocol(True, False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            {"aws.baseline.called", "fetch.baseline.called", "register.candidate.called"},
            calls,
        )
        self.assertNotIn("register.baseline.called", calls)

        result, calls = self.run_registry_protocol(False, True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            {"register.baseline.called", "aws.candidate.called", "fetch.candidate.called"},
            calls,
        )
        self.assertNotIn("register.candidate.called", calls)

    def test_exact_not_found_uses_normal_registration(self) -> None:
        result, calls = self.run_registry_protocol(
            True, False, baseline_mode="missing"
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("register.baseline.called", calls)
        self.assertIn("register.candidate.called", calls)

    def test_non_not_found_and_invalid_stored_candidate_fail_closed(self) -> None:
        result, calls = self.run_registry_protocol(
            True, False, baseline_mode="denied"
        )
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("register.baseline.called", calls)
        self.assertNotIn("register.candidate.called", calls)
        self.assertIn("AccessDenied", result.stderr)

        for variable in ("FAKE_FETCH_FAIL", "FAKE_VALIDATE_FAIL", "FAKE_EQUIVALENCE_FAIL"):
            with self.subTest(variable=variable):
                value = "1" if variable == "FAKE_VALIDATE_FAIL" else "baseline"
                result, calls = self.run_registry_protocol(
                    True, False, extra_env={variable: value}
                )
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("register.baseline.called", calls)
                self.assertNotIn("register.candidate.called", calls)

    def test_modern_pair_never_uses_historical_equivalence(self) -> None:
        result, calls = self.run_registry_protocol(
            False, False, baseline_mode="denied", candidate_mode="denied"
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            {"register.baseline.called", "register.candidate.called"}, calls
        )

    def test_pre_aws_order_and_immutable_registry_contract_remain_intact(self) -> None:
        validate = self.workflow.index(
            "validate_candidate(Path('baseline')); validate_candidate(Path('candidate'))"
        )
        credentials = self.workflow.index("- name: Assume release publisher role only")
        registry = self.workflow.index(
            "# BEGIN STAGING_QUALIFICATION_REGISTRY_IDEMPOTENCE"
        )
        self.assertLess(validate, credentials)
        self.assertLess(credentials, registry)
        self.assertNotIn("delete-object", self.registry_script)
        self.assertNotIn("put-object", self.registry_script)

        source = REGISTRY.read_text(encoding="utf-8")
        self.assertIn("--if-none-match", source)
        self.assertLess(
            source.index("for name in OBJECT_FILES:"),
            source.index('registry.put_if_absent(f"{prefix}/registration.json"'),
        )


if __name__ == "__main__":
    unittest.main()
