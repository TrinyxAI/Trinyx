from __future__ import annotations

import hashlib
import json
import re
import shutil
import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from legacy_normalization_plan import (
    SSM_STDOUT_MAX_BYTES,
    build_normalization_plan,
    explained_compose_model,
    render_ssm_protocol,
)
from legacy_runtime import SERVICES
from invariants import InvariantError


def config_hash(service: str) -> str:
    return hashlib.sha256(f"compose:{service}".encode()).hexdigest()


def explained_hash(service: str) -> str:
    return hashlib.sha256(f"compose-legacy-explained:{service}".encode()).hexdigest()


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
        explained_hashes: dict[str, str] | None = None,
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
            explained_hashes or dict(hashes),
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
        self.assertEqual("QUALIFIED_EXPLAINED_DRIFT", result["composeDriftCompatibility"])
        self.assertEqual(8, result["composeCanonicalMatchCount"])
        self.assertEqual(0, result["composeExplainedDriftCount"])
        self.assertEqual(0, result["composeUnexplainedDriftCount"])
        self.assertEqual("MATCHED", result["imageCompatibility"])
        self.assertEqual([], result["recreateServices"])
        self.assertEqual(8, result["serviceCount"])

    def test_mutable_checkout_is_reported_not_hidden(self) -> None:
        containers, model, hashes = self.fixture()
        service = sorted(SERVICES["paid"])[0]
        containers[0]["Mounts"][0]["Source"] = "/srv/trinyx/" + "pr25-aeb2a44/docker/Caddyfile"
        allowed_hashes = dict(hashes)
        allowed_hashes[service] = explained_hash(service)
        containers[0]["Config"]["Labels"]["com.docker.compose.config-hash"] = allowed_hashes[service]
        result = self.build(containers, model, hashes, explained_hashes=allowed_hashes)
        item = result["services"][service]
        self.assertTrue(item["mutableCheckoutMounted"])
        self.assertTrue(item["recreateRequired"])
        self.assertIn("MUTABLE_CHECKOUT_MOUNT", item["reasons"])
        self.assertIn(service, result["recreateServices"])

    def test_one_unexplained_effective_config_change_fails_closed(self) -> None:
        containers, model, hashes = self.fixture()
        service = sorted(SERVICES["paid"])[0]
        containers[0]["Config"]["Labels"]["com.docker.compose.config-hash"] = "f" * 64
        result = self.build(containers, model, hashes)
        self.assertIn("UNEXPLAINED_COMPOSE_CONFIG_DRIFT", result["services"][service]["reasons"])
        self.assertEqual("UNQUALIFIED_UNEXPLAINED_DRIFT", result["composeDriftCompatibility"])
        self.assertEqual(1, result["composeUnexplainedDriftCount"])

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
        allowed_hashes = dict(hashes)
        allowed_hashes[service] = explained_hash(service)
        containers[0]["Config"]["Labels"]["com.docker.compose.config-hash"] = allowed_hashes[service]
        inspected = [
            {"Id": container["Image"], "RepoDigests": [
                expected if index == 0 else container["Config"]["Image"]
            ]}
            for index, container in enumerate(containers)
        ]
        result = self.build(
            containers, model, hashes,
            image_inspections=inspected,
            explained_hashes=allowed_hashes,
        )
        item = result["services"][service]
        self.assertEqual("redis:7-alpine", item["currentConfiguredImage"])
        self.assertTrue(item["imageContentMatches"])
        self.assertTrue(item["imageObjectVerified"])
        self.assertFalse(item["configuredImageCanonical"])
        self.assertEqual(
            ["IMAGE_REFERENCE_NON_CANONICAL", "COMPOSE_CONFIG_DRIFT_EXPLAINED"],
            item["reasons"],
        )
        self.assertEqual("EXPLAINED_NORMALIZATION", item["composeDriftClassification"])
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

    def test_six_real_third_party_image_canonicalizations_per_role_are_explained(self) -> None:
        inventory = json.loads(
            (Path(__file__).resolve().parents[2] / "release/third-party-images.json").read_text()
        )
        for role in ("paid", "cloud"):
            with self.subTest(role=role):
                services = {
                    item["service"] for item in inventory["images"] if item["role"] == role
                }
                self.assertEqual(6, len(services))
                containers, model, hashes = self.fixture(role)
                explained = dict(hashes)
                inspected: list[dict] = []
                for index, service in enumerate(sorted(SERVICES[role])):
                    container = containers[index]
                    expected = model["services"][service]["image"]
                    if service in services:
                        container["Config"]["Image"] = expected.split("@", 1)[0] + ":legacy"
                        explained[service] = explained_hash(service)
                        container["Config"]["Labels"]["com.docker.compose.config-hash"] = explained[service]
                    inspected.append({"Id": container["Image"], "RepoDigests": [expected]})
                result = self.build(
                    containers, model, hashes, role,
                    image_inspections=inspected,
                    explained_hashes=explained,
                )
                self.assertEqual("QUALIFIED_EXPLAINED_DRIFT", result["composeDriftCompatibility"])
                self.assertEqual(6, result["composeExplainedDriftCount"])
                self.assertEqual(0, result["composeUnexplainedDriftCount"])
                for service in services:
                    self.assertTrue(result["services"][service]["imageContentMatches"])
                    self.assertEqual(
                        "EXPLAINED_NORMALIZATION",
                        result["services"][service]["composeDriftClassification"],
                    )

                unrelated = next(service for service in sorted(SERVICES[role]) if service not in services)
                position = sorted(SERVICES[role]).index(unrelated)
                containers[position]["Config"]["Labels"]["com.docker.compose.config-hash"] = "f" * 64
                blocked = self.build(
                    containers, model, hashes, role,
                    image_inspections=inspected,
                    explained_hashes=explained,
                )
                self.assertEqual(
                    "UNQUALIFIED_UNEXPLAINED_DRIFT",
                    blocked["composeDriftCompatibility"],
                )
                self.assertEqual(1, blocked["composeUnexplainedDriftCount"])

    def test_explained_model_changes_only_approved_legacy_fields(self) -> None:
        containers, model, _ = self.fixture()
        service = sorted(SERVICES["paid"])[0]
        position = sorted(SERVICES["paid"]).index(service)
        canonical = json.loads(json.dumps(model))
        canonical_service = canonical["services"][service]
        canonical_service.update({
            "command": ["serve", "--strict"],
            "environment": {"MODE": "staging"},
            "ports": [{"target": 8080, "published": "18080"}],
            "restart": "unless-stopped",
            "healthcheck": {"test": ["CMD", "true"]},
            "networks": {"default": {}},
        })
        containers[position]["Config"]["Image"] = "redis:7-alpine"
        containers[position]["Config"]["Cmd"] = ["serve", "--unapproved"]
        containers[position]["Config"]["Env"] = ["MODE=unexpected"]
        containers[position]["Mounts"][0]["Source"] = (
            "/srv/trinyx/pr25-aeb2a44/docker/legacy.conf"
        )

        explained = explained_compose_model("paid", containers, canonical)
        explained_service = explained["services"][service]
        self.assertEqual("redis:7-alpine", explained_service["image"])
        self.assertEqual(
            "/srv/trinyx/pr25-aeb2a44/docker/legacy.conf",
            explained_service["volumes"][0]["source"],
        )
        for field in ("command", "environment", "ports", "restart", "healthcheck", "networks"):
            self.assertEqual(canonical_service[field], explained_service[field])

    @unittest.skipUnless(shutil.which("docker"), "Docker Compose CLI is unavailable")
    def test_real_compose_hash_changes_when_only_image_becomes_digest_only(self) -> None:
        def compose_hash(image: str) -> str:
            model = {
                "name": "normalization-hash-contract",
                "services": {
                    "redis": {
                        "image": image,
                        "command": ["redis-server", "--appendonly", "yes"],
                        "environment": {"TZ": "UTC"},
                        "restart": "unless-stopped",
                    }
                },
            }
            result = subprocess.run(
                ["docker", "compose", "-f", "-", "config", "--hash", "redis"],
                input=json.dumps(model), text=True, stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, check=True, timeout=30,
            )
            service, value = result.stdout.strip().split()
            self.assertEqual("redis", service)
            self.assertRegex(value, r"^[0-9a-f]{64}$")
            return value

        legacy = compose_hash("redis:7-alpine")
        canonical = compose_hash("redis@sha256:" + "a" * 64)
        self.assertNotEqual(legacy, canonical)

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
        self.assertTrue(output.startswith("LEGACY_NORMALIZATION_REPORT_V3 "))
        self.assertIn("configured_image_canonical=yes", output)
        self.assertIn("compose_drift=matched", output)
        self.assertNotIn("explained_config_hash=", output)
        self.assertNotIn("configured_image_sha256=", output)
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
