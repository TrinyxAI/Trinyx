from __future__ import annotations

import hashlib
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from legacy_normalization_plan import (
    COMPOSE_HASH_MISMATCH_LIMIT,
    SSM_STDOUT_MAX_BYTES,
    build_normalization_plan,
    render_ssm_protocol,
)
from legacy_runtime import SERVICES


def config_hash(service: str) -> str:
    return hashlib.sha256(f"compose:{service}".encode()).hexdigest()


class LegacyNormalizationPlanTests(unittest.TestCase):
    def fixture(self) -> tuple[list[dict], dict, dict[str, str]]:
        containers: list[dict] = []
        models: dict[str, dict] = {}
        hashes: dict[str, str] = {}
        for index, service in enumerate(sorted(SERVICES["paid"]), start=1):
            image = f"ghcr.io/trinyxai/{service}@sha256:{index:064x}"
            source = f"/etc/trinyx/staging/paid/config/{service}.conf"
            destination = f"/etc/trinyx/{service}.conf"
            hashes[service] = config_hash(service)
            containers.append({
                "Id": f"{index + 1000:064x}",
                "Image": "sha256:" + f"{index + 2000:064x}",
                "Config": {
                    "Image": image,
                    "Labels": {
                        "com.docker.compose.project": "trinyx-paid-staging",
                        "com.docker.compose.service": service,
                        "com.docker.compose.config-hash": hashes[service],
                    },
                },
                "Mounts": [{
                    "Type": "bind",
                    "Source": source,
                    "Destination": destination,
                    "RW": False,
                }],
            })
            models[service] = {
                "image": image,
                "volumes": [{
                    "type": "bind",
                    "source": source,
                    "target": destination,
                    "read_only": True,
                }],
            }
        return containers, {"services": models}, hashes

    def build(self, containers: list[dict], model: dict, hashes: dict[str, str]) -> dict:
        return build_normalization_plan(
            "paid",
            "rel-v1-" + "a" * 32,
            containers,
            model,
            hashes,
            "v2.40.3",
            "2026-09-02T00:00:00Z",
            bundle_digest="sha256:" + "b" * 64,
            deployment_id="dep-" + "c" * 32,
            environment_config_revision="config-1",
            environment_config_digest_value="sha256:" + "d" * 64,
            control_plane_commit="e" * 40,
        )

    def test_happy_path_is_review_qualified_and_requires_no_recreate(self) -> None:
        containers, model, hashes = self.fixture()
        result = self.build(containers, model, hashes)
        self.assertEqual("QUALIFIED_REVIEW", result["composeHashCompatibility"])
        self.assertEqual(8, result["composeHashCalibrationMatches"])
        self.assertEqual(0, result["composeHashMismatchCount"])
        self.assertEqual(COMPOSE_HASH_MISMATCH_LIMIT, result["composeHashMismatchLimit"])
        self.assertEqual("MATCHED", result["imageCompatibility"])
        self.assertEqual([], result["recreateServices"])
        self.assertEqual(8, result["serviceCount"])

    def test_mutable_checkout_is_reported_not_hidden(self) -> None:
        containers, model, hashes = self.fixture()
        service = sorted(SERVICES["paid"])[0]
        containers[0]["Mounts"][0]["Source"] = "/srv/trinyx/" + "pr25-aeb2a44/docker/Caddyfile"
        result = self.build(containers, model, hashes)
        item = result["services"][service]
        self.assertTrue(item["mutableCheckoutMounted"])
        self.assertTrue(item["recreateRequired"])
        self.assertIn("MUTABLE_CHECKOUT_MOUNT", item["reasons"])
        self.assertIn(service, result["recreateServices"])

    def test_same_image_but_small_effective_hash_drift_requires_human_review(self) -> None:
        containers, model, hashes = self.fixture()
        service = sorted(SERVICES["paid"])[0]
        containers[0]["Config"]["Labels"]["com.docker.compose.config-hash"] = "f" * 64
        result = self.build(containers, model, hashes)
        self.assertIn("COMPOSE_CONFIG_HASH_MISMATCH", result["services"][service]["reasons"])
        self.assertEqual("QUALIFIED_REVIEW", result["composeHashCompatibility"])
        self.assertEqual(1, result["composeHashMismatchCount"])

    def test_excessive_hash_drift_fails_closed_before_mass_recreation(self) -> None:
        containers, model, hashes = self.fixture()
        for container in containers[: COMPOSE_HASH_MISMATCH_LIMIT + 1]:
            container["Config"]["Labels"]["com.docker.compose.config-hash"] = "f" * 64
        result = self.build(containers, model, hashes)
        self.assertEqual("UNQUALIFIED_EXCESSIVE_DRIFT", result["composeHashCompatibility"])
        self.assertEqual(COMPOSE_HASH_MISMATCH_LIMIT + 1, result["composeHashMismatchCount"])

    def test_image_difference_is_not_normalized_silently(self) -> None:
        containers, model, hashes = self.fixture()
        service = sorted(SERVICES["paid"])[0]
        model["services"][service]["image"] = (
            f"ghcr.io/trinyxai/{service}@sha256:" + "f" * 64
        )
        result = self.build(containers, model, hashes)
        self.assertEqual("MISMATCH", result["imageCompatibility"])
        self.assertIn("IMAGE_DIGEST_MISMATCH", result["services"][service]["reasons"])
        self.assertIn("currentConfiguredImage", result["services"][service])
        self.assertRegex(result["services"][service]["currentImageObjectId"], r"^sha256:[0-9a-f]{64}$")

    def test_report_is_bound_and_bounded_with_marker_last(self) -> None:
        containers, model, hashes = self.fixture()
        # Long mount paths must not turn SSM stdout into an unbounded JSON transport.
        for index, container in enumerate(containers):
            service = sorted(SERVICES["paid"])[index]
            long_source = "/etc/" + ("x" * 3900) + f"/{service}"
            container["Mounts"][0]["Source"] = long_source
            model["services"][service]["volumes"][0]["source"] = long_source
        record = self.build(containers, model, hashes)
        output = render_ssm_protocol(record)
        self.assertLess(len(output.encode("utf-8")), SSM_STDOUT_MAX_BYTES)
        self.assertNotIn('"services":', output)
        self.assertIn("bundle_digest=sha256:" + "b" * 64, output)
        self.assertIn("deployment_id=dep-" + "c" * 32, output)
        self.assertIn("config_revision=config-1", output)
        self.assertIn("config_digest=sha256:" + "d" * 64, output)
        self.assertIn("control_plane_commit=" + "e" * 40, output)
        lines = output.rstrip("\n").splitlines()
        self.assertEqual(10, len(lines))
        self.assertTrue(lines[-1].startswith("LEGACY_NORMALIZATION_PLAN_COMPLETE "))
        self.assertRegex(lines[-1], r"report_sha256=sha256:[0-9a-f]{64}$")
        self.assertEqual(8, sum(line.startswith("NORMALIZATION ") for line in lines))
        self.assertFalse(any("/etc/" + "x" * 100 in line for line in lines))


if __name__ == "__main__":
    unittest.main()
