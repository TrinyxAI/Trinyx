from __future__ import annotations

import datetime as dt
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from helpers import make_release
from invariants import InvariantError
from ssm_orchestrator import (
    STALE_LOCK_CONFIRMATION,
    AwsCliSsmTransport,
    AwsCliStagingLock,
    NoopSagaLock,
    Request,
    StagingSaga,
    meaningful_runtime_difference,
)


class FakeTransport:
    def __init__(self, fail: tuple[str, str] | None = None):
        self.fail = fail
        self.calls: list[tuple[str, str, str]] = []
        self.deployment_ids: list[str] = []

    def execute(self, request: Request) -> str:
        self.calls.append((request.mode, request.role, request.release_id))
        self.deployment_ids.append(request.deployment_id)
        if self.fail == (request.mode, request.role):
            self.fail = None
            raise InvariantError("injected transport failure")
        marker = {
            "plan": f"STAGING_DEPLOY_PLAN_OK role={request.role} release_id={request.release_id}",
            "apply": f"STAGING_DEPLOY_APPLY_OK role={request.role} release_id={request.release_id}",
            "rollback": f"STAGING_DEPLOY_ROLLBACK_OK role={request.role} release_id={request.release_id}",
            "health": f"STAGING_DEPLOY_HEALTH_OK role={request.role} release_id={request.release_id}",
            "install": f"RELEASE_INSTALL_APPLY_OK role={request.role}",
            "adopt": f"STAGING_LEGACY_ADOPTION_OK role={request.role} release_id={request.release_id}",
            "restore-legacy": f"STAGING_LEGACY_RESTORE_OK role={request.role} release_id={request.release_id}",
        }[request.mode]
        return marker + "\n"


class FakeClock:
    def __init__(self) -> None:
        self.now = 0.0

    def monotonic(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.now += seconds


class SlowSsmTransport(AwsCliSsmTransport):
    def __init__(self, clock: FakeClock, complete_at: float):
        super().__init__(
            "Trinyx-Staging-Deploy",
            "7",
            "trinyx-staging-registry",
            monotonic=clock.monotonic,
            sleep=clock.sleep,
        )
        self.clock = clock
        self.complete_at = complete_at
        self.polls = 0

    def _aws(self, argv: list[str], timeout: int = 45) -> subprocess.CompletedProcess[str]:
        if "send-command" in argv:
            return subprocess.CompletedProcess(
                argv, 0, "12345678-1234-1234-1234-123456789abc\n", ""
            )
        if "get-command-invocation" in argv:
            self.polls += 1
            status = "Success" if self.clock.now >= self.complete_at else "InProgress"
            payload = {
                "Status": status,
                "StandardErrorContent": "",
                "StandardOutputContent": "STAGING_DEPLOY_PLAN_OK",
            }
            return subprocess.CompletedProcess(argv, 0, json.dumps(payload), "")
        raise AssertionError(argv)


class FakeLockTransport:
    document = "Trinyx-Staging-Deploy"

    def __init__(self) -> None:
        self.parameter: str | None = None
        self.commands: list[dict[str, str]] = []
        self.last_list_argv: list[str] | None = None

    def _aws(self, argv: list[str], timeout: int = 45) -> subprocess.CompletedProcess[str]:
        operation = argv[1]
        if operation == "put-parameter":
            if self.parameter is not None:
                return subprocess.CompletedProcess(argv, 1, "", "exists")
            self.parameter = argv[argv.index("--value") + 1]
            return subprocess.CompletedProcess(argv, 0, "{}", "")
        if operation == "get-parameter":
            if self.parameter is None:
                return subprocess.CompletedProcess(argv, 1, "", "missing")
            return subprocess.CompletedProcess(argv, 0, self.parameter + "\n", "")
        if operation == "delete-parameter":
            self.parameter = None
            return subprocess.CompletedProcess(argv, 0, "{}", "")
        if operation == "list-commands":
            self.last_list_argv = argv
            return subprocess.CompletedProcess(
                argv, 0, json.dumps({"Commands": self.commands}), ""
            )
        raise AssertionError(argv)


class OrchestratorTests(unittest.TestCase):
    def saga(self, transport: FakeTransport) -> StagingSaga:
        return StagingSaga(transport, "config-1", "1" * 40, NoopSagaLock())

    def test_legacy_adoption_is_paid_then_cloud_and_uses_one_lock_owner(self) -> None:
        transport = FakeTransport()
        release = "rel-v1-" + "2" * 32
        self.saga(transport).adopt_legacy_baseline(release, "sha256:" + "3" * 64)
        self.assertEqual(
            [("adopt", "paid", release), ("adopt", "cloud", release)],
            transport.calls,
        )
        self.assertEqual(1, len(set(transport.deployment_ids)))

    def test_partial_legacy_adoption_restores_paid_pointer(self) -> None:
        transport = FakeTransport(("adopt", "cloud"))
        release = "rel-v1-" + "2" * 32
        with self.assertRaises(InvariantError):
            self.saga(transport).adopt_legacy_baseline(release, "sha256:" + "3" * 64)
        self.assertEqual(("restore-legacy", "paid", release), transport.calls[-1])
        self.assertEqual(1, len(set(transport.deployment_ids)))

    def test_paid_then_cloud_happy_path(self) -> None:
        transport = FakeTransport()
        self.saga(transport).deploy("rel-v1-" + "2" * 32, "sha256:" + "3" * 64, "rel-v1-" + "0" * 32, "rel-v1-" + "1" * 32, "sha256:" + "4" * 64, "sha256:" + "4" * 64)
        mutations = [(mode, role) for mode, role, _ in transport.calls if mode == "apply"]
        self.assertEqual([("apply", "paid"), ("apply", "cloud")], mutations)
        self.assertEqual(1, len(set(transport.deployment_ids)))

    def test_paid_failure_leaves_cloud_unmutated(self) -> None:
        transport = FakeTransport(("apply", "paid"))
        with self.assertRaises(InvariantError):
            self.saga(transport).deploy("rel-v1-" + "2" * 32, "sha256:" + "3" * 64, "rel-v1-" + "0" * 32, "rel-v1-" + "1" * 32, "sha256:" + "4" * 64, "sha256:" + "4" * 64)
        self.assertNotIn(("apply", "cloud", "rel-v1-" + "2" * 32), transport.calls)

    def test_cloud_failure_compensates_cloud_then_paid(self) -> None:
        transport = FakeTransport(("apply", "cloud"))
        with self.assertRaises(InvariantError):
            self.saga(transport).deploy("rel-v1-" + "2" * 32, "sha256:" + "3" * 64, "rel-v1-" + "0" * 32, "rel-v1-" + "1" * 32, "sha256:" + "4" * 64, "sha256:" + "4" * 64)
        rollback_roles = [role for mode, role, _ in transport.calls if mode == "rollback"]
        self.assertEqual(["cloud", "paid"], rollback_roles)

    def test_cross_stack_health_failure_compensates(self) -> None:
        transport = FakeTransport(("health", "cloud"))
        with self.assertRaises(InvariantError):
            self.saga(transport).deploy("rel-v1-" + "2" * 32, "sha256:" + "3" * 64, "rel-v1-" + "0" * 32, "rel-v1-" + "1" * 32, "sha256:" + "4" * 64, "sha256:" + "4" * 64)
        self.assertIn(("rollback", "paid", "rel-v1-" + "1" * 32), transport.calls)

    def test_partial_explicit_rollback_restores_cloud_candidate(self) -> None:
        transport = FakeTransport(("rollback", "paid"))
        baseline = "rel-v1-" + "0" * 32
        candidate = "rel-v1-" + "2" * 32
        with self.assertRaises(InvariantError):
            self.saga(transport).rollback(baseline, "sha256:" + "3" * 64, candidate, "sha256:" + "5" * 64)
        self.assertEqual(("rollback", "cloud", candidate), transport.calls[-1])

    def test_ssm_polling_accepts_command_longer_than_180_seconds(self) -> None:
        clock = FakeClock()
        transport = SlowSsmTransport(clock, complete_at=182.0)
        output = transport.execute(
            Request(
                "plan", "paid", "rel-v1-" + "2" * 32, "sha256:" + "3" * 64,
                "dep-" + "4" * 32, "config-1", "5" * 40,
                "rel-v1-" + "0" * 32, "rel-v1-" + "1" * 32,
            )
        )
        self.assertEqual("STAGING_DEPLOY_PLAN_OK", output)
        self.assertGreater(transport.polls, 90)
        self.assertGreaterEqual(clock.now, 182.0)
        self.assertLess(clock.now, transport.poll_budget_seconds)

    def test_stale_lock_break_requires_proof_and_explicit_confirmation(self) -> None:
        owner = "dep-" + "6" * 32
        transport = FakeLockTransport()
        lock = AwsCliStagingLock(transport)  # type: ignore[arg-type]
        with lock.hold(owner):
            metadata = json.loads(transport.parameter or "")
            self.assertEqual(owner, metadata["owner"])
            self.assertEqual(1, metadata["schemaVersion"])
        self.assertIsNone(transport.parameter)

        transport.parameter = lock._value(owner)
        stale_metadata = json.loads(transport.parameter)
        transport.commands = [{"Comment": f"Trinyx staging apply {owner} paid", "Status": "InProgress"}]
        with self.assertRaisesRegex(InvariantError, "still active"):
            lock.break_stale(owner, STALE_LOCK_CONFIRMATION)
        self.assertIsNotNone(transport.parameter)

        transport.commands[0]["Status"] = "Success"
        with self.assertRaisesRegex(InvariantError, "explicit approval"):
            lock.break_stale(owner, "NO")
        lock.break_stale(owner, STALE_LOCK_CONFIRMATION)
        self.assertIsNone(transport.parameter)
        assert transport.last_list_argv is not None
        filter_value = transport.last_list_argv[
            transport.last_list_argv.index("--filters") + 2
        ].removeprefix("key=InvokedAfter,value=")
        invoked_after = dt.datetime.fromisoformat(filter_value.replace("Z", "+00:00"))
        created_at = dt.datetime.fromisoformat(stale_metadata["createdAt"].replace("Z", "+00:00"))
        self.assertEqual(dt.timedelta(minutes=5), created_at - invoked_after)


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
