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
