from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from baseline_observation import SERVICES, build_observation
from invariants import InvariantError


class BaselineObservationTests(unittest.TestCase):
    def fixture(self) -> tuple[list[dict], list[dict]]:
        containers: list[dict] = []
        images: list[dict] = []
        for index, service in enumerate(sorted(SERVICES["paid"]), start=1):
            digest = "sha256:" + f"{index:064x}"
            immutable = f"ghcr.io/trinyxai/{service}@{digest}"
            image_id = "sha256:" + f"{index + 1000:064x}"
            containers.append(
                {
                    "Id": f"{index + 2000:064x}",
                    "Image": image_id,
                    "Config": {
                        "Image": immutable,
                        "Labels": {
                            "com.docker.compose.project": "trinyx-paid-staging",
                            "com.docker.compose.service": service,
                        },
                    },
                }
            )
            images.append({"Id": image_id, "RepoDigests": [immutable]})
        return containers, images

    def test_observation_binds_container_image_object_repo_digest_and_compose_labels(self) -> None:
        containers, images = self.fixture()
        record = build_observation(
            "paid", containers, images, "config-1", "sha256:" + "a" * 64,
            "2026-09-02T00:00:00Z",
        )
        self.assertEqual(2, record["schemaVersion"])
        self.assertEqual(8, len(record["services"]))
        self.assertEqual("trinyx-paid-staging", record["composeProject"])
        for service, item in record["services"].items():
            self.assertEqual(service, item["composeService"])
            self.assertEqual(record["composeProject"], item["composeProject"])
            self.assertIn(item["configuredImage"], item["repoDigests"])
            self.assertRegex(item["containerImageId"], r"^sha256:[0-9a-f]{64}$")

    def test_observation_rejects_image_without_repo_digest(self) -> None:
        containers, images = self.fixture()
        images[0]["RepoDigests"] = []
        with self.assertRaisesRegex(InvariantError, "RepoDigests"):
            build_observation(
                "paid", containers, images, "config-1", "sha256:" + "a" * 64,
                "2026-09-02T00:00:00Z",
            )

    def test_observation_rejects_multiple_compose_projects(self) -> None:
        containers, images = self.fixture()
        containers[0]["Config"]["Labels"]["com.docker.compose.project"] = "unexpected-project"
        with self.assertRaisesRegex(InvariantError, "multiple Docker Compose projects"):
            build_observation(
                "paid", containers, images, "config-1", "sha256:" + "a" * 64,
                "2026-09-02T00:00:00Z",
            )


if __name__ == "__main__":
    unittest.main()
