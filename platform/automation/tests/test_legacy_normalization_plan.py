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
from invariants import InvariantError


def config_hash(service: str) -> str:
    return hashlib.sha256(f"compose:{service}".encode()).hexdigest()


class LegacyNormalizationPlanTests(unittest.TestCase):
    def fixture(self, role: str = "paid") -> tuple[list[dict], dict, dict[str, str]]:
        containers: list[dict] = []
        models: dict[str, dict] = {}
        hashes: dict[str, str] = {}
        for index, service in enumerate(sorted(SERVICES[role]), start=1):
            image = f"ghcr.io/trinyxai/{service}@sha256:{index:064x}"
            source = f"/etc/trinyx/staging/{role}/config/{service}.conf"
            destination = f"/etc/trinyx/{service}.conf"
            hashes[service] = config_hash(service)
            containers.append({
                "Id": f"{index + 1000:064x}",
                "Image": "sha256:" + f"{index + 2000:064x}",
                "Config": {
                    "Image": image,
                    "Labels": {
                        "com.docker.compose.project": f"trinyx-{role}-staging",
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

    def build(
        self,
        containers: list[dict],
        model: dict,
        hashes: dict[str, str],
        role: str = "paid",
        image_inspections: list[dict] | None = None,
    ) -> dict:
        inspected = image_inspections or [
            {"Id": container["Image"], "RepoDigests": [container["Config"]["Image"]]}
            for container in containers
        ]
        return build_normalization_plan(
            role,
            "rel-v1-" + "a" * 32,
            containers,
            inspected,
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
        self.assertIn("IMAGE_REFERENCE_NON_CANONICAL", result["services"][service]["reasons"])
        self.assertIn("IMAGE_OBJECT_DIGEST_MISMATCH", result["services"][service]["reasons"])
        self.assertIn("currentConfiguredImage", result["services"][service])
        self.assertRegex(result["services"][service]["currentImageObjectId"], r"^sha256:[0-9a-f]{64}$")

    def test_running_image_object_must_expose_expected_repo_digest(self) -> None:
        containers, model, hashes = self.fixture()
        containers[0]["Config"]["Image"] = "redis:7-alpine"
        inspected = [
            {"Id": container["Image"], "RepoDigests": [
                model["services"][sorted(SERVICES["paid"])[index]]["image"]
            ]}
            for index, container in enumerate(containers)
        ]
        service = sorted(SERVICES["paid"])[0]
        inspected[0]["RepoDigests"] = [
            f"ghcr.io/trinyxai/{service}@sha256:" + "f" * 64
        ]
        result = self.build(containers, model, hashes, image_inspections=inspected)
        item = result["services"][service]
        self.assertFalse(item["imageContentMatches"])
        self.assertIn("IMAGE_OBJECT_DIGEST_MISMATCH", item["reasons"])
        self.assertEqual("MISMATCH", result["imageCompatibility"])

    def test_legacy_tag_is_observed_from_exact_object_and_requires_recreate(self) -> None:
        containers, model, hashes = self.fixture()
        service = sorted(SERVICES["paid"])[0]
        expected = model["services"][service]["image"]
        containers[0]["Config"]["Image"] = "redis:7-alpine"
        inspected = [
            {"Id": container["Image"], "RepoDigests": [
                expected if index == 0 else container["Config"]["Image"]
            ]}
            for index, container in enumerate(containers)
        ]
        result = self.build(containers, model, hashes, image_inspections=inspected)
        item = result["services"][service]
        self.assertEqual("redis:7-alpine", item["currentConfiguredImage"])
        self.assertTrue(item["imageContentMatches"])
        self.assertFalse(item["configuredImageCanonical"])
        self.assertEqual(["IMAGE_REFERENCE_NON_CANONICAL"], item["reasons"])
        self.assertTrue(item["recreateRequired"])
        self.assertEqual("MATCHED", result["imageCompatibility"])

    def test_legacy_tag_without_expected_object_digest_is_a_bounded_mismatch(self) -> None:
        containers, model, hashes = self.fixture()
        service = sorted(SERVICES["paid"])[0]
        containers[0]["Config"]["Image"] = "redis:7-alpine"
        inspected = [
            {"Id": container["Image"], "RepoDigests": [
                (f"redis@sha256:{'f' * 64}" if index == 0 else container["Config"]["Image"])
            ]}
            for index, container in enumerate(containers)
        ]
        item = self.build(containers, model, hashes, image_inspections=inspected)["services"][service]
        self.assertFalse(item["imageContentMatches"])
        self.assertEqual(
            ["IMAGE_REFERENCE_NON_CANONICAL", "IMAGE_OBJECT_DIGEST_MISMATCH"],
            item["reasons"],
        )

    def test_missing_immutable_object_evidence_fails_closed(self) -> None:
        containers, model, hashes = self.fixture()
        containers[0]["Config"]["Image"] = "redis:7-alpine"
        inspected = [
            {"Id": container["Image"], "RepoDigests": [] if index == 0 else [container["Config"]["Image"]]}
            for index, container in enumerate(containers)
        ]
        with self.assertRaises(InvariantError):
            self.build(containers, model, hashes, image_inspections=inspected)

    def test_immutable_config_must_be_consistent_with_exact_object(self) -> None:
        containers, model, hashes = self.fixture()
        service = sorted(SERVICES["paid"])[0]
        wrong = f"ghcr.io/trinyxai/{service}@sha256:{'f' * 64}"
        containers[0]["Config"]["Image"] = wrong
        inspected = [
            {"Id": container["Image"], "RepoDigests": [
                model["services"][service]["image"] if index == 0 else container["Config"]["Image"]
            ]}
            for index, container in enumerate(containers)
        ]
        with self.assertRaises(InvariantError):
            self.build(containers, model, hashes, image_inspections=inspected)

    def test_missing_exact_image_object_inspection_fails_closed(self) -> None:
        containers, model, hashes = self.fixture()
        inspected = [
            {"Id": container["Image"], "RepoDigests": [container["Config"]["Image"]]}
            for container in containers[1:]
        ]
        with self.assertRaises(InvariantError):
            self.build(containers, model, hashes, image_inspections=inspected)

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
        self.assertTrue(output.startswith("LEGACY_NORMALIZATION_REPORT_V2 "))
        self.assertIn("configured_image_canonical=yes", output)
        self.assertRegex(output, r"configured_image_sha256=sha256:[0-9a-f]{64}")
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


    def test_all_28_services_remain_bounded_at_realistic_path_limits(self) -> None:
        total = 0
        for role in ("paid", "cloud"):
            containers, model, hashes = self.fixture(role)
            for index, container in enumerate(containers):
                service = sorted(SERVICES[role])[index]
                long_source = "/etc/" + ("y" * 3900) + f"/{service}"
                container["Mounts"][0]["Source"] = long_source
                model["services"][service]["volumes"][0]["source"] = long_source
            output = render_ssm_protocol(self.build(containers, model, hashes, role))
            self.assertLess(len(output.encode("utf-8")), SSM_STDOUT_MAX_BYTES)
            self.assertTrue(
                output.rstrip("\n").splitlines()[-1].startswith(
                    f"LEGACY_NORMALIZATION_PLAN_COMPLETE role={role} "
                )
            )
            total += len(SERVICES[role])
        self.assertEqual(28, total)



if __name__ == "__main__":
    unittest.main()
