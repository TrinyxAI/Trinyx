from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


class StagingBootRecoveryTests(unittest.TestCase):
    def test_transient_materializer_failure_has_explicit_fail_closed_recovery(self) -> None:
        roles = {
            "cloud": "trinyx-cloud-runtime-materialize.service",
            "paid": "trinyx-paid-runtime-materialize.service",
        }
        for role, unit_name in roles.items():
            with self.subTest(role=role):
                base = (
                    ROOT / f"platform/host/{role}/systemd/{unit_name}"
                ).read_text(encoding="utf-8")
                retrigger = (
                    ROOT
                    / f"platform/host/{role}/systemd/staging/{unit_name}.d/20-trinyx-staging-retrigger.conf"
                ).read_text(encoding="utf-8")
                gate = (
                    ROOT
                    / f"platform/host/{role}/systemd/staging/docker.service.d/20-trinyx-staging-runtime-gate.conf"
                ).read_text(encoding="utf-8")
                self.assertIn("Restart=on-failure", base)
                self.assertIn("RemainAfterExit=no", retrigger)
                self.assertIn(f"Requires={unit_name}", gate)
                self.assertIn(f"After={unit_name}", gate)

        runbook = (
            ROOT / "docs/deployment/o6-o12-staging-automation.md"
        ).read_text(encoding="utf-8")
        self.assertIn("systemctl reset-failed docker.service", runbook)
        self.assertIn("systemctl start docker.service", runbook)
        self.assertIn("Systemd does not automatically requeue", runbook)
        self.assertNotIn("sleep 60", runbook)
        recovery = runbook.split("deterministic recovery is therefore:", 1)[1]
        recovery = recovery.split("The second start transaction", 1)[0]
        self.assertNotIn("docker compose", recovery.lower())


if __name__ == "__main__":
    unittest.main()
