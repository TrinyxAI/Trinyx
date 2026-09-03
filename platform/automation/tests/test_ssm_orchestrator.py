from __future__ import annotations

import datetime as dt
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from helpers import make_release
from invariants import InvariantError
from legacy_runtime import SERVICES
from ssm_orchestrator import (
    STALE_LOCK_CONFIRMATION,
    AwsCliSsmTransport,
    AwsCliStagingLock,
    NoopSagaLock,
    Request,
    StagingSaga,
    meaningful_runtime_difference,
    validate_normalization_protocol,
)


def normalization_protocol(
    request: Request,
    *,
    explained_drift: int = 0,
    unexplained_drift: int = 0,
) -> str:
    services = sorted(SERVICES[request.role])
    canonical_matches = len(services) - explained_drift - unexplained_drift
    lines = [
        (
            f"LEGACY_NORMALIZATION_REPORT_V3 role={request.role} "
            f"release_id={request.release_id} bundle_digest={request.bundle_digest} "
            f"deployment_id={request.deployment_id} config_revision={request.config_revision} "
            f"config_digest=sha256:{'7' * 64} "
            f"control_plane_commit={request.control_plane_commit} "
            "observed_at=2026-09-02T00:00:00Z compose_version=v2.40.3 "
            f"service_count={len(services)} canonical_matches={canonical_matches} "
            f"explained_drift={explained_drift} unexplained_drift={unexplained_drift}"
        )
    ]
    for index, service in enumerate(services, start=1):
        explained = index <= explained_drift
        unexplained = explained_drift < index <= explained_drift + unexplained_drift
        mismatch = explained or unexplained
        current_hash = ("e" if explained else "f") * 64 if mismatch else f"{index:064x}"
        expected_hash = f"{index:064x}"
        compose_drift = (
            "explained" if explained
            else "unexplained" if unexplained
            else "matched"
        )
        reasons = (
            "COMPOSE_CONFIG_DRIFT_EXPLAINED" if explained
            else "UNEXPLAINED_COMPOSE_CONFIG_DRIFT" if unexplained
            else "none"
        )
        lines.append(
            f"NORMALIZATION role={request.role} service={service} "
            f"recreate={'yes' if mismatch else 'no'} reasons={reasons} image_match=yes "
            f"container_id={index + 1000:064x} image_object=sha256:{index + 2000:064x} "
            "configured_image_canonical=yes "
            f"configured_image_sha256=sha256:{index + 2500:064x} "
            f"expected_image_digest=sha256:{index:064x} "
            f"repo_digests_sha256=sha256:{index + 3000:064x} "
            f"compose_drift={compose_drift} current_config_hash={current_hash} "
            f"expected_config_hash={expected_hash} "
            f"current_bind_mounts_sha256=sha256:{index + 4000:064x} "
            f"expected_bind_mounts_sha256=sha256:{index + 4000:064x} "
            "mutable_checkout=no"
        )
    payload = "\n".join(lines) + "\n"
    report_sha = "sha256:" + hashlib.sha256(payload.encode("utf-8")).hexdigest()
    compatibility = "review" if unexplained_drift == 0 else "stop"
    lines.append(
        f"LEGACY_NORMALIZATION_PLAN_COMPLETE role={request.role} "
        f"release_id={request.release_id} services={len(services)} "
        f"recreate_count={explained_drift + unexplained_drift} compose_version=v2.40.3 "
        f"compatibility={compatibility} images=matched report_sha256={report_sha}"
    )
    return "\n".join(lines) + "\n"


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
            "normalize-plan": normalization_protocol(request).rstrip("\n"),
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

    def normalization_request(self, role: str = "paid") -> Request:
        return Request(
            "normalize-plan",
            role,
            "rel-v1-" + "2" * 32,
            "sha256:" + "3" * 64,
            "dep-" + "4" * 32,
            "config-1",
            "5" * 40,
            None,
            None,
        )

    def validate_protocol(self, output: str, request: Request) -> dict[str, object]:
        return validate_normalization_protocol(
            output,
            request.role,
            request.release_id,
            request.bundle_digest,
            request.deployment_id,
            request.config_revision,
            request.control_plane_commit,
        )

    def test_normalization_receiver_accepts_exact_paid_and_cloud_inventories(self) -> None:
        for role, count in (("paid", 8), ("cloud", 20)):
            with self.subTest(role=role):
                request = self.normalization_request(role)
                report = self.validate_protocol(normalization_protocol(request), request)
                self.assertEqual(count, report["serviceCount"])
                self.assertEqual(0, report["recreateCount"])
                self.assertRegex(str(report["reportSha256"]), r"^sha256:[0-9a-f]{64}$")

    def test_normalization_receiver_rejects_missing_service(self) -> None:
        request = self.normalization_request()
        lines = normalization_protocol(request).splitlines()
        del lines[1]
        with self.assertRaises(InvariantError):
            self.validate_protocol("\n".join(lines) + "\n", request)

    def test_normalization_receiver_rejects_duplicate_service(self) -> None:
        request = self.normalization_request()
        lines = normalization_protocol(request).splitlines()
        lines[2] = lines[1]
        with self.assertRaises(InvariantError):
            self.validate_protocol("\n".join(lines) + "\n", request)

    def test_normalization_receiver_rejects_unknown_service(self) -> None:
        request = self.normalization_request()
        lines = normalization_protocol(request).splitlines()
        service = sorted(SERVICES["paid"])[0]
        lines[1] = lines[1].replace(f"service={service}", "service=unknown-service")
        with self.assertRaises(InvariantError):
            self.validate_protocol("\n".join(lines) + "\n", request)

    def test_normalization_receiver_rejects_wrong_report_sha(self) -> None:
        request = self.normalization_request()
        output = normalization_protocol(request)
        output = re.sub(
            r"report_sha256=sha256:[0-9a-f]{64}\n$",
            "report_sha256=sha256:" + "f" * 64 + "\n",
            output,
        )
        with self.assertRaisesRegex(InvariantError, "SHA-256 mismatch"):
            self.validate_protocol(output, request)

    def test_normalization_receiver_rejects_wrong_recreate_count(self) -> None:
        request = self.normalization_request()
        output = normalization_protocol(request).replace("recreate_count=0", "recreate_count=1")
        with self.assertRaises(InvariantError):
            self.validate_protocol(output, request)

    def test_normalization_receiver_rejects_marker_not_last(self) -> None:
        request = self.normalization_request()
        with self.assertRaises(InvariantError):
            self.validate_protocol(normalization_protocol(request) + "EXTRA=1\n", request)

    def test_normalization_receiver_rejects_truncated_output(self) -> None:
        request = self.normalization_request()
        with self.assertRaisesRegex(InvariantError, "truncated"):
            self.validate_protocol(normalization_protocol(request)[:-1], request)

    def test_normalization_receiver_accepts_noncanonical_reference_with_proven_content(self) -> None:
        request = self.normalization_request()
        output = normalization_protocol(request)
        lines = output.rstrip("\n").split("\n")
        fields = lines[1].replace("recreate=no reasons=none", (
            "recreate=yes reasons=IMAGE_REFERENCE_NON_CANONICAL"
        )).replace("configured_image_canonical=yes", "configured_image_canonical=no")
        lines[1] = fields
        marker_fields = lines[-1].replace("recreate_count=0", "recreate_count=1")
        payload = "\n".join(lines[:-1]) + "\n"
        lines[-1] = re.sub(
            r"report_sha256=sha256:[0-9a-f]{64}$",
            "report_sha256=sha256:" + hashlib.sha256(payload.encode()).hexdigest(),
            marker_fields,
        )
        report = self.validate_protocol("\n".join(lines) + "\n", request)
        self.assertEqual("matched", report["images"])
        self.assertEqual(1, report["recreateCount"])

    def test_normalization_receiver_rejects_spoofed_image_reason_evidence(self) -> None:
        request = self.normalization_request()
        output = normalization_protocol(request)
        lines = output.rstrip("\n").split("\n")
        lines[1] = lines[1].replace(
            "configured_image_canonical=yes", "configured_image_canonical=no"
        )
        payload = "\n".join(lines[:-1]) + "\n"
        lines[-1] = re.sub(
            r"report_sha256=sha256:[0-9a-f]{64}$",
            "report_sha256=sha256:" + hashlib.sha256(payload.encode()).hexdigest(),
            lines[-1],
        )
        with self.assertRaisesRegex(InvariantError, "image evidence contradicts"):
            self.validate_protocol("\n".join(lines) + "\n", request)

    def test_legacy_normalization_plan_is_paid_then_cloud_and_read_only(self) -> None:
        transport = FakeTransport()
        release = "rel-v1-" + "2" * 32
        self.saga(transport).legacy_normalization_plan(release, "sha256:" + "3" * 64)
        self.assertEqual(
            [("normalize-plan", "paid", release), ("normalize-plan", "cloud", release)],
            transport.calls,
        )
        self.assertEqual(1, len(set(transport.deployment_ids)))
        self.assertFalse(any(mode in {"apply", "adopt", "rollback", "install"} for mode, _, _ in transport.calls))

    def test_legacy_normalization_plan_fails_closed_on_one_unexplained_change(self) -> None:
        class UnqualifiedTransport(FakeTransport):
            def execute(self, request: Request) -> str:
                if request.mode == "normalize-plan":
                    return normalization_protocol(request, unexplained_drift=1)
                return super().execute(request)
        with self.assertRaisesRegex(InvariantError, "unexplained Compose drift"):
            self.saga(UnqualifiedTransport()).legacy_normalization_plan(
                "rel-v1-" + "2" * 32, "sha256:" + "3" * 64
            )

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
