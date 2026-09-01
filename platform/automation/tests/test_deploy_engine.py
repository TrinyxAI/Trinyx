from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from deploy_engine import (
    DeploymentLock,
    HostDeployment,
    HostPlan,
    atomic_active_pointer,
    sha256_path,
)
from invariants import InvariantError, validate_active_pointer
from helpers import make_host


class FakeAdapter:
    def __init__(self, models: dict[str, dict[str, Any]], fail: str | None = None):
        self.models = models
        self.fail = fail
        self.calls: list[str] = []

    def _step(self, name: str) -> None:
        self.calls.append(name)
        if self.fail == name:
            self.fail = None
            raise InvariantError(f"injected {name} failure")

    def preflight(self, base: Path, release_dir: Path, plan: HostPlan) -> dict[str, Any]:
        self._step("preflight")
        for injected in ("missing_config", "missing_secret", "missing_binary", "insufficient_disk", "compose_render", "ghcr_pull"):
            if self.fail == injected:
                self._step(injected)
        return self.models[release_dir.name]

    def render_model(self, base: Path, release_dir: Path, plan: HostPlan) -> dict[str, Any]:
        return self.models[release_dir.name]

    def run_migrations(self, base: Path, release_dir: Path, plan: HostPlan, services: tuple[str, ...]) -> None:
        self._step("migration")

    def materialize(self, role: str) -> None:
        self._step("materialize")

    def apply_services(self, base: Path, release_dir: Path, plan: HostPlan, services: tuple[str, ...]) -> None:
        self._step("apply")

    def health(self, base: Path, release_dir: Path, plan: HostPlan, services: tuple[str, ...]) -> None:
        self._step("health")


class FakePointer:
    def __init__(self, release_id: str):
        self.release_id = release_id
        self.activations: list[str] = []

    def current(self, base: Path, role: str) -> str:
        return self.release_id

    def activate(self, base: Path, release_id: str) -> None:
        if not (base / "releases" / release_id).is_dir():
            raise InvariantError("missing activation target")
        self.release_id = release_id
        self.activations.append(release_id)


class DeploymentEngineTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name) / "etc" / "trinyx" / "staging" / "paid"
        self.base.mkdir(parents=True)
        self.releases, self.models = make_host(self.base, "paid", create_active=False)
        self.previous, self.candidate = self.releases
        self.pointer = FakePointer(self.previous)
        self.args = {
            "deployment_id": "dep-" + "1" * 32,
            "release_id": self.candidate,
            "config_revision": "config-1",
            "platform_commit": "1" * 40,
            "previous_cloud": None,
            "previous_paid": self.previous,
        }

    def tearDown(self) -> None:
        self.temp.cleanup()

    def engine(self, fail: str | None = None) -> tuple[HostDeployment, FakeAdapter]:
        adapter = FakeAdapter(self.models, fail)
        return HostDeployment(self.base, "paid", adapter, self.pointer), adapter

    def record(self) -> dict[str, Any]:
        return json.loads((self.base / "deployments" / f"{self.args['deployment_id']}.json").read_text())

    def test_happy_path_and_state_transitions(self) -> None:
        engine, adapter = self.engine()
        self.assertEqual("APPLIED", engine.apply(**self.args))
        self.assertEqual(self.candidate, self.pointer.release_id)
        self.assertEqual(["preflight", "materialize", "apply", "health"], adapter.calls)
        record = self.record()
        self.assertEqual("SUCCESS", record["state"])
        self.assertEqual(
            ["CREATED", "PREFLIGHT", "READY", "ACTIVATING", "HEALTH_CHECKING", "SUCCESS"],
            [item["state"] for item in record["history"]],
        )

    def test_idempotence(self) -> None:
        self.pointer.activate(self.base, self.candidate)
        (self.base / "active-config-revision").write_text(json.dumps("config-1") + "\n")
        engine, adapter = self.engine()
        args = dict(self.args)
        args["previous_paid"] = self.candidate
        self.assertEqual("IDEMPOTENT", engine.apply(**args))
        self.assertNotIn("apply", adapter.calls)
        self.assertEqual("SUCCESS", self.record()["state"])

    def test_failure_before_mutation(self) -> None:
        for failure in ("preflight", "missing_config", "missing_secret", "missing_binary", "insufficient_disk", "compose_render", "ghcr_pull"):
            with self.subTest(failure=failure):
                # Each subtest needs a unique record because records are immutable audit history.
                self.args["deployment_id"] = "dep-" + format(len(failure), "032x")
                engine, _ = self.engine(failure)
                with self.assertRaises(InvariantError):
                    engine.apply(**self.args)
                self.assertEqual(self.previous, self.pointer.release_id)
                self.assertEqual("FAILED", self.record()["state"])

    def test_unproven_migration_rollback_safety_fails_before_mutation(self) -> None:
        plan_path = self.base / "config" / "deployment-plan.json"
        plan = json.loads(plan_path.read_text())
        plan["oneShot"] = {"services": ["livecontext"], "rollbackSafe": False}
        plan_path.write_text(json.dumps(plan), encoding="utf-8")
        engine, adapter = self.engine()
        with self.assertRaisesRegex(InvariantError, "rollback safety"):
            engine.apply(**self.args)
        self.assertEqual(self.previous, self.pointer.release_id)
        self.assertNotIn("migration", adapter.calls)
        self.assertNotIn("apply", adapter.calls)

    def test_partial_activation_rolls_back(self) -> None:
        engine, adapter = self.engine("health")
        with self.assertRaises(InvariantError):
            engine.apply(**self.args)
        self.assertEqual(self.previous, self.pointer.release_id)
        self.assertEqual("ROLLED_BACK", self.record()["state"])
        self.assertGreaterEqual(adapter.calls.count("materialize"), 2)

    def test_rollback_failure_is_reported(self) -> None:
        class RollbackFails(FakeAdapter):
            def __init__(self, models: dict[str, dict[str, Any]]):
                super().__init__(models)
                self.health_count = 0

            def health(self, base: Path, release_dir: Path, plan: HostPlan, services: tuple[str, ...]) -> None:
                self.health_count += 1
                self.calls.append("health")
                if self.health_count in {1, 2}:
                    raise InvariantError("injected health failure")

        adapter = RollbackFails(self.models)
        engine = HostDeployment(self.base, "paid", adapter, self.pointer)
        with self.assertRaisesRegex(InvariantError, "rollback failed"):
            engine.apply(**self.args)
        self.assertEqual("ROLLBACK_FAILED", self.record()["state"])

    def test_explicit_rollback_success_and_idempotence(self) -> None:
        self.pointer.activate(self.base, self.candidate)
        engine, _ = self.engine()
        args = dict(self.args)
        args["deployment_id"] = "dep-" + "2" * 32
        self.assertEqual(
            "ROLLED_BACK",
            engine.rollback(
                args["deployment_id"],
                self.previous,
                args["config_revision"],
                args["platform_commit"],
                None,
                self.candidate,
            ),
        )
        self.assertEqual(self.previous, self.pointer.release_id)
        args["deployment_id"] = "dep-" + "3" * 32
        self.assertEqual(
            "IDEMPOTENT",
            engine.rollback(args["deployment_id"], self.previous, args["config_revision"], args["platform_commit"], None, self.previous),
        )

    def test_rollback_preflight_failure_preserves_original_error_and_record(self) -> None:
        self.pointer.activate(self.base, self.candidate)
        self.args["deployment_id"] = "dep-" + "4" * 32
        engine, adapter = self.engine("preflight")
        with self.assertRaisesRegex(InvariantError, "injected preflight failure"):
            engine.rollback(
                self.args["deployment_id"],
                self.previous,
                self.args["config_revision"],
                self.args["platform_commit"],
                None,
                self.candidate,
            )
        self.assertEqual(self.candidate, self.pointer.release_id)
        self.assertNotIn("apply", adapter.calls)
        self.assertEqual("FAILED", self.record()["state"])

    def test_installed_bundle_digest_is_bound_to_dispatch_contract(self) -> None:
        engine, _ = self.engine()
        manifest = json.loads((self.base / "releases" / self.candidate / "manifest.json").read_text())
        expected = manifest["deploymentBundle"]["digest"]
        engine.verify_bundle_digest(self.candidate, expected)
        with self.assertRaisesRegex(InvariantError, "differs from SSM contract"):
            engine.verify_bundle_digest(self.candidate, "sha256:" + "0" * 64)

    @unittest.skipIf(os.name == "nt", "Linux CI executes symlink adoption semantics")
    def test_legacy_baseline_adoption_and_explicit_restore_are_pointer_only(self) -> None:
        legacy = self.base / "deployments" / "stg-bootstrap-001"
        legacy.mkdir(parents=True)
        os.symlink("deployments/stg-bootstrap-001", self.base / "active", target_is_directory=True)
        observation = {
            "schemaVersion": 1,
            "environment": "staging",
            "role": "paid",
            "observedAt": "2026-09-01T00:00:00Z",
            "releaseEligible": False,
            "reason": "observation is not a release",
            "services": {
                service: {"configuredImage": "fixture@sha256:" + "1" * 64, "containerId": "1" * 12}
                for service in json.loads(
                    (self.base / "config" / "deployment-plan.json").read_text()
                )["services"]
            },
        }
        observation_path = self.base / "config" / "legacy-observation.json"
        observation_path.write_text(json.dumps(observation, sort_keys=True) + "\n", encoding="utf-8")
        manifest = json.loads((self.base / "releases" / self.previous / "manifest.json").read_text())
        evidence = {
            "schemaVersion": 1,
            "environment": "staging",
            "role": "paid",
            "legacyActiveTarget": "deployments/stg-bootstrap-001",
            "baselineRelease": self.previous,
            "bundleDigest": manifest["deploymentBundle"]["digest"],
            "imagesEnvSha256": sha256_path(self.base / "releases" / self.previous / "images.env"),
            "observationSha256": sha256_path(observation_path),
            "environmentConfigRevision": "config-legacy",
            "platformCommit": "1" * 40,
            "approvalScope": "pointer-only-no-runtime-recreation",
            "approvedForPointerAdoption": True,
        }
        evidence_path = self.base / "config" / "legacy-adoption.json"
        evidence_path.write_text(json.dumps(evidence, sort_keys=True) + "\n", encoding="utf-8")
        adapter = FakeAdapter(self.models)
        engine = HostDeployment(self.base, "paid", adapter)
        self.assertEqual("legacy:deployments/stg-bootstrap-001", engine.active_status())
        adoption_id = "dep-" + "5" * 32
        self.assertEqual(
            "ADOPTED",
            engine.adopt_legacy_baseline(adoption_id, self.previous, "config-legacy", "1" * 40),
        )
        self.assertEqual(self.previous, engine.current_release())
        self.assertNotIn("apply", adapter.calls)
        adoption_record = json.loads(
            (self.base / "deployments" / f"{adoption_id}-adopt.json").read_text()
        )
        self.assertEqual("SUCCESS", adoption_record["state"])
        restore_id = "dep-" + "6" * 32
        self.assertEqual(
            "LEGACY_POINTER_RESTORED",
            engine.restore_legacy_pointer(restore_id, self.previous, "config-legacy", "1" * 40),
        )
        self.assertEqual("legacy:deployments/stg-bootstrap-001", engine.active_status())
        self.assertNotIn("apply", adapter.calls)

    @unittest.skipIf(os.name == "nt", "Linux CI executes symlink adoption semantics")
    def test_unproved_legacy_baseline_fails_closed_before_pointer_mutation(self) -> None:
        legacy = self.base / "deployments" / "stg-bootstrap-001"
        legacy.mkdir(parents=True)
        os.symlink("deployments/stg-bootstrap-001", self.base / "active", target_is_directory=True)
        engine = HostDeployment(self.base, "paid", FakeAdapter(self.models))
        deployment_id = "dep-" + "7" * 32
        with self.assertRaisesRegex(InvariantError, "LEGACY_BASELINE_PROOF_REQUIRED"):
            engine.adopt_legacy_baseline(
                deployment_id, self.previous, "config-legacy", "1" * 40,
            )
        self.assertEqual("legacy:deployments/stg-bootstrap-001", engine.active_status())
        record = json.loads(
            (self.base / "deployments" / f"{deployment_id}-adopt.json").read_text()
        )
        self.assertEqual("FAILED", record["state"])

    def test_concurrent_deployment_refusal(self) -> None:
        with DeploymentLock(self.base / "deploy.lock"):
            with self.assertRaisesRegex(InvariantError, "concurrent"):
                with DeploymentLock(self.base / "deploy.lock"):
                    pass

    @unittest.skipIf(os.name == "nt", "Windows runner lacks unprivileged symlink support; Linux CI executes this")
    def test_atomic_active_pointer_never_points_outside_releases(self) -> None:
        os.symlink(f"releases/{self.previous}", self.base / "active", target_is_directory=True)
        atomic_active_pointer(self.base, self.candidate)
        self.assertEqual(self.candidate, validate_active_pointer(self.base, "paid"))
        self.assertEqual(f"releases/{self.candidate}", os.readlink(self.base / "active"))

    def test_paid_cloud_and_cross_stack_health_failures_are_compensated(self) -> None:
        for failure in ("paid_health", "cloud_health", "cross_stack_smoke"):
            with self.subTest(failure=failure):
                self.args["deployment_id"] = "dep-" + format(sum(map(ord, failure)), "032x")
                engine, adapter = self.engine("health")
                with self.assertRaises(InvariantError):
                    engine.apply(**self.args)
                self.assertEqual(self.previous, self.pointer.release_id)


if __name__ == "__main__":
    unittest.main()
