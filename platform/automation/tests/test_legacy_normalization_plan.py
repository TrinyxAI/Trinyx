from __future__ import annotations

import hashlib
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from legacy_normalization_plan import build_normalization_plan
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
            "paid", "rel-v1-" + "a" * 32, containers, model, hashes,
            "v2.40.3", "2026-09-02T00:00:00Z",
        )

    def test_happy_path_is_qualified_and_requires_no_recreate(self) -> None:
        containers, model, hashes = self.fixture()
        result = self.build(containers, model, hashes)
        self.assertEqual("QUALIFIED", result["composeHashCompatibility"])
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

    def test_same_image_but_different_effective_hash_requires_recreate(self) -> None:
        containers, model, hashes = self.fixture()
        service = sorted(SERVICES["paid"])[0]
        containers[0]["Config"]["Labels"]["com.docker.compose.config-hash"] = "f" * 64
        result = self.build(containers, model, hashes)
        self.assertIn("COMPOSE_CONFIG_HASH_MISMATCH", result["services"][service]["reasons"])
        self.assertEqual("QUALIFIED", result["composeHashCompatibility"])

    def test_all_hashes_different_fail_compatibility_gate(self) -> None:
        containers, model, hashes = self.fixture()
        for container in containers:
            container["Config"]["Labels"]["com.docker.compose.config-hash"] = "f" * 64
        result = self.build(containers, model, hashes)
        self.assertEqual("UNQUALIFIED_ALL_SERVICES_DIFFER", result["composeHashCompatibility"])

    def test_image_difference_is_not_normalized_silently(self) -> None:
        containers, model, hashes = self.fixture()
        service = sorted(SERVICES["paid"])[0]
        model["services"][service]["image"] = (
            f"ghcr.io/trinyxai/{service}@sha256:" + "f" * 64
        )
        result = self.build(containers, model, hashes)
        self.assertEqual("MISMATCH", result["imageCompatibility"])
        self.assertIn("IMAGE_DIGEST_MISMATCH", result["services"][service]["reasons"])


if __name__ == "__main__":
    unittest.main()
