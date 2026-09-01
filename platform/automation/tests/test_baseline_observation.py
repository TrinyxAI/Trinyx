from __future__ import annotations

import hashlib
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from baseline_observation import SERVICES, build_observation
from invariants import InvariantError


def config_hash(service: str) -> str:
    return hashlib.sha256(f"compose:{service}".encode()).hexdigest()


class BaselineObservationTests(unittest.TestCase):
    def fixture(self) -> tuple[list[dict], list[dict], dict[str, str]]:
        containers: list[dict] = []
        images: list[dict] = []
        expected_hashes: dict[str, str] = {}
        for index, service in enumerate(sorted(SERVICES["paid"]), start=1):
            digest = "sha256:" + f"{index:064x}"
            immutable = f"ghcr.io/trinyxai/{service}@{digest}"
            image_id = "sha256:" + f"{index + 1000:064x}"
            expected_hashes[service] = config_hash(service)
            containers.append(
                {
                    "Id": f"{index + 2000:064x}",
                    "Image": image_id,
                    "Config": {
                        "Image": immutable,
                        "Labels": {
                            "com.docker.compose.project": "trinyx-paid-staging",
                            "com.docker.compose.service": service,
                            "com.docker.compose.config-hash": expected_hashes[service],
                        },
                    },
                    "Mounts": [{
                        "Type": "bind",
                        "Source": f"/etc/trinyx/staging/paid/config/{service}.conf",
                        "Destination": f"/etc/trinyx/{service}.conf",
                        "RW": False,
                    }],
                }
            )
            images.append({"Id": image_id, "RepoDigests": [immutable]})
        return containers, images, expected_hashes

    def observe(self, containers: list[dict], images: list[dict], expected_hashes: dict[str, str]) -> dict:
        return build_observation(
            "paid", containers, images, expected_hashes, "config-1", "sha256:" + "a" * 64,
            "2026-09-02T00:00:00Z",
        )

    def test_observation_binds_images_effective_config_and_mounts(self) -> None:
        containers, images, expected_hashes = self.fixture()
        record = self.observe(containers, images, expected_hashes)
        self.assertEqual(3, record["schemaVersion"])
        self.assertEqual(8, len(record["services"]))
        self.assertEqual("trinyx-paid-staging", record["composeProject"])
        for service, item in record["services"].items():
            self.assertEqual(service, item["composeService"])
            self.assertEqual(expected_hashes[service], item["composeConfigHash"])
            self.assertEqual(record["composeProject"], item["composeProject"])
            self.assertIn(item["configuredImage"], item["repoDigests"])
            self.assertRegex(item["containerImageId"], r"^sha256:[0-9a-f]{64}$")
            self.assertTrue(item["mounts"][0]["readOnly"])

    def test_observation_rejects_image_without_repo_digest(self) -> None:
        containers, images, expected_hashes = self.fixture()
        images[0]["RepoDigests"] = []
        with self.assertRaisesRegex(InvariantError, "RepoDigests"):
            self.observe(containers, images, expected_hashes)

    def test_observation_rejects_multiple_compose_projects(self) -> None:
        containers, images, expected_hashes = self.fixture()
        containers[0]["Config"]["Labels"]["com.docker.compose.project"] = "unexpected-project"
        with self.assertRaisesRegex(InvariantError, "multiple Docker Compose projects"):
            self.observe(containers, images, expected_hashes)

    def test_observation_rejects_effective_container_config_mismatch(self) -> None:
        containers, images, expected_hashes = self.fixture()
        containers[0]["Config"]["Labels"]["com.docker.compose.config-hash"] = "f" * 64
        with self.assertRaisesRegex(InvariantError, "differs from baseline"):
            self.observe(containers, images, expected_hashes)

    def test_observation_rejects_mutable_checkout_mount(self) -> None:
        containers, images, expected_hashes = self.fixture()
        containers[0]["Mounts"][0]["Source"] = "/srv/trinyx/pr25-aeb2a44/docker/Caddyfile"
        with self.assertRaisesRegex(InvariantError, "mutable checkout"):
            self.observe(containers, images, expected_hashes)


if __name__ == "__main__":
    unittest.main()
