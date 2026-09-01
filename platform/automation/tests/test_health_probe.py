from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "health_probe.py"


class HealthProbeContractTests(unittest.TestCase):
    def run_config(self, value: object) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "health.json"
            path.write_text(json.dumps(value), encoding="utf-8")
            return subprocess.run([sys.executable, str(SCRIPT), "--config", str(path)], text=True, capture_output=True)

    def test_rejects_http_tls_bypass(self) -> None:
        result = self.run_config({"schemaVersion": 1, "checks": [{
            "name": "bad", "url": "http://example.test/health", "expectedStatuses": [200],
            "timeoutSeconds": 1, "caFile": "/missing", "method": "GET"
        }]})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("HEALTH_PROBE_FAILED=bad_url", result.stdout + result.stderr)

    def test_rejects_unsafe_method(self) -> None:
        result = self.run_config({"schemaVersion": 1, "checks": [{
            "name": "bad", "url": "https://example.test/health", "expectedStatuses": [200],
            "timeoutSeconds": 1, "caFile": "/missing", "method": "DELETE"
        }]})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("HEALTH_PROBE_FAILED=bad_method", result.stdout + result.stderr)

    def test_rejects_credentials_in_url(self) -> None:
        result = self.run_config({"schemaVersion": 1, "checks": [{
            "name": "bad", "url": "https://user:password@example.test/health", "expectedStatuses": [401],
            "timeoutSeconds": 1, "caFile": "/missing", "method": "GET"
        }]})
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("password", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
