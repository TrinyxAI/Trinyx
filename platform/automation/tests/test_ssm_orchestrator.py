from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from helpers import make_release
from invariants import InvariantError
from ssm_orchestrator import NoopSagaLock, Request, StagingSaga, meaningful_runtime_difference


class FakeTransport:
    def __init__(self, fail: tuple[str, str] | None = None):
        self.fail = fail
        self.calls: list[tuple[str, str, str]] = []

    def execute(self, request: Request) -> str:
        self.calls.append((request.mode, request.role, request.release_id))
        if self.fail == (request.mode, request.role):
            self.fail = None
            raise InvariantError("injected transport failure")
        marker = {
            "plan": f"STAGING_DEPLOY_PLAN_OK role={request.role} release_id={request.release_id}",
            "apply": f"STAGING_DEPLOY_APPLY_OK role={request.role} release_id={request.release_id}",
            "rollback": f"STAGING_DEPLOY_ROLLBACK_OK role={request.role} release_id={request.release_id}",
            "health": f"STAGING_DEPLOY_HEALTH_OK role={request.role} release_id={request.release_id}",
            "install": f"RELEASE_INSTALL_APPLY_OK role={request.role}",
        }[request.mode]
        return marker + "\n"


class OrchestratorTests(unittest.TestCase):
    def saga(self, transport: FakeTransport) -> StagingSaga:
        return StagingSaga(transport, "config-1", "1" * 40, NoopSagaLock())

    def test_paid_then_cloud_happy_path(self) -> None:
        transport = FakeTransport()
        self.saga(transport).deploy("rel-v1-" + "2" * 32, "sha256:" + "3" * 64, "rel-v1-" + "0" * 32, "rel-v1-" + "1" * 32)
        mutations = [(mode, role) for mode, role, _ in transport.calls if mode == "apply"]
        self.assertEqual([("apply", "paid"), ("apply", "cloud")], mutations)

    def test_paid_failure_leaves_cloud_unmutated(self) -> None:
        transport = FakeTransport(("apply", "paid"))
        with self.assertRaises(InvariantError):
            self.saga(transport).deploy("rel-v1-" + "2" * 32, "sha256:" + "3" * 64, "rel-v1-" + "0" * 32, "rel-v1-" + "1" * 32)
        self.assertNotIn(("apply", "cloud", "rel-v1-" + "2" * 32), transport.calls)

    def test_cloud_failure_compensates_cloud_then_paid(self) -> None:
        transport = FakeTransport(("apply", "cloud"))
        with self.assertRaises(InvariantError):
            self.saga(transport).deploy("rel-v1-" + "2" * 32, "sha256:" + "3" * 64, "rel-v1-" + "0" * 32, "rel-v1-" + "1" * 32)
        rollback_roles = [role for mode, role, _ in transport.calls if mode == "rollback"]
        self.assertEqual(["cloud", "paid"], rollback_roles)

    def test_cross_stack_health_failure_compensates(self) -> None:
        transport = FakeTransport(("health", "cloud"))
        with self.assertRaises(InvariantError):
            self.saga(transport).deploy("rel-v1-" + "2" * 32, "sha256:" + "3" * 64, "rel-v1-" + "0" * 32, "rel-v1-" + "1" * 32)
        self.assertIn(("rollback", "paid", "rel-v1-" + "1" * 32), transport.calls)

    def test_partial_explicit_rollback_restores_cloud_candidate(self) -> None:
        transport = FakeTransport(("rollback", "paid"))
        baseline = "rel-v1-" + "0" * 32
        candidate = "rel-v1-" + "2" * 32
        with self.assertRaises(InvariantError):
            self.saga(transport).rollback(baseline, "sha256:" + "3" * 64, candidate)
        self.assertEqual(("rollback", "cloud", candidate), transport.calls[-1])

    def test_meaningful_difference_required(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory) / "baseline"
            candidate = Path(directory) / "candidate"
            base.mkdir()
            candidate.mkdir()
            _, release_a, _ = make_release(base, "paid", 1)
            _, release_b, _ = make_release(candidate, "paid", 2)
            changed = meaningful_runtime_difference(release_a / "manifest.json", release_b / "manifest.json")
            self.assertTrue(changed)
            with self.assertRaisesRegex(InvariantError, "no relevant runtime difference"):
                meaningful_runtime_difference(release_a / "manifest.json", release_a / "manifest.json")


if __name__ == "__main__":
    unittest.main()
