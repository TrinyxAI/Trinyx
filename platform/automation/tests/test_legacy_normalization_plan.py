from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from legacy_normalization_plan import (
    SSM_STDOUT_MAX_BYTES,
    build_normalization_plan,
    explained_compose_model,
    expected_mounts,
    legacy_bind_content_evidence,
    render_ssm_protocol,
)
from legacy_runtime import SERVICES
from deploy_engine import ShellAdapter, load_host_plan
from invariants import InvariantError
from ssm_orchestrator import validate_normalization_protocol


def config_hash(service: str) -> str:
    return hashlib.sha256(f"compose:{service}".encode()).hexdigest()


def explained_hash(service: str) -> str:
    return hashlib.sha256(f"compose-legacy-explained:{service}".encode()).hexdigest()


def empty_bind_evidence(role: str) -> dict[str, dict]:
    empty = "sha256:" + hashlib.sha256(b"[]").hexdigest()
    aggregate = "sha256:" + hashlib.sha256(
        b'{"current":[],"expected":[]}'
    ).hexdigest()
    return {
        service: {
            "required": False,
            "verified": True,
            "verifiedTargets": [],
            "currentDigest": empty,
            "expectedDigest": empty,
            "evidenceDigest": aggregate,
        }
        for service in SERVICES[role]
    }


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

    def bind_fixture(
        self, root: Path, *, directory: bool = False,
    ) -> tuple[list[dict], dict, dict[str, str], dict[str, dict], str, Path, Path, Path]:
        containers, model, hashes = self.fixture()
        service = sorted(SERVICES["paid"])[0]
        legacy_root = root / "srv" / "trinyx"
        checkout_root = legacy_root / "pr25-aeb2a44"
        bundle_root = root / "release" / "bundle"
        relative = Path("catalog-seeds" if directory else "docker/legacy.conf")
        current = checkout_root / relative
        expected = bundle_root / relative
        if directory:
            (current / "nested").mkdir(parents=True)
            (expected / "nested").mkdir(parents=True)
            (current / "seed.json").write_text('{"seed":1}\n')
            (expected / "seed.json").write_text('{"seed":1}\n')
            (current / "nested/item.txt").write_text("approved\n")
            (expected / "nested/item.txt").write_text("approved\n")
        else:
            current.parent.mkdir(parents=True)
            expected.parent.mkdir(parents=True)
            current.write_text("approved legacy content\n")
            expected.write_text("approved legacy content\n")
        containers[0]["Mounts"][0]["Source"] = str(current)
        model["services"][service]["volumes"][0]["source"] = str(expected)
        evidence = legacy_bind_content_evidence(
            "paid", containers, model, bundle_root, legacy_root=legacy_root,
        )
        return (
            containers, model, hashes, evidence, service, legacy_root,
            current, expected,
        )

    def build(
        self,
        containers: list[dict],
        model: dict,
        hashes: dict[str, str],
        role: str = "paid",
        image_inspections: list[dict] | None = None,
        explained_hashes: dict[str, str] | None = None,
        bind_evidence: dict[str, dict] | None = None,
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
            bind_evidence or empty_bind_evidence(role),
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
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            containers, model, hashes, evidence, service, legacy_root, _, _ = self.bind_fixture(root)
            allowed_hashes = dict(hashes)
            allowed_hashes[service] = explained_hash(service)
            containers[0]["Config"]["Labels"]["com.docker.compose.config-hash"] = allowed_hashes[service]
            result = self.build(
                containers, model, hashes, explained_hashes=allowed_hashes,
                bind_evidence=evidence,
            )
            item = result["services"][service]
            self.assertTrue(item["mutableCheckoutMounted"])
            self.assertTrue(item["legacyBindContentVerified"])
            self.assertTrue(item["recreateRequired"])
            self.assertIn("MUTABLE_CHECKOUT_MOUNT", item["reasons"])
            self.assertIn(service, result["recreateServices"])
            protocol = render_ssm_protocol(result)
            self.assertIn(
                f"bind_proof=match:{evidence[service]['evidenceDigest']}",
                protocol,
            )
            self.assertNotIn(str(root), protocol)
            self.assertNotIn("approved legacy content", protocol)

    def assert_bind_content_mismatch(
        self, root: Path, containers: list[dict], model: dict,
        hashes: dict[str, str], service: str, legacy_root: Path,
    ) -> None:
        evidence = legacy_bind_content_evidence(
            "paid", containers, model, root / "release" / "bundle",
            legacy_root=legacy_root,
        )
        self.assertTrue(evidence[service]["required"])
        self.assertFalse(evidence[service]["verified"])
        result = self.build(containers, model, hashes, bind_evidence=evidence)
        item = result["services"][service]
        self.assertEqual("UNEXPLAINED", item["composeDriftClassification"])
        self.assertIn("LEGACY_BIND_CONTENT_MISMATCH", item["reasons"])
        self.assertEqual("UNQUALIFIED_UNEXPLAINED_DRIFT", result["composeDriftCompatibility"])

    def test_same_legacy_file_path_with_one_changed_byte_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            containers, model, hashes, _, service, legacy_root, current, _ = self.bind_fixture(root)
            current.write_text("approved legacy content?\n")
            self.assert_bind_content_mismatch(
                root, containers, model, hashes, service, legacy_root,
            )

    def test_dirty_checkout_head_does_not_substitute_for_content_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            containers, model, hashes, _, service, legacy_root, current, _ = self.bind_fixture(root)
            git = current.parents[1] / ".git"
            git.mkdir()
            (git / "HEAD").write_text("aeb2a447ea7ce0436a60549713636225dfe1a2c1\n")
            current.write_text("locally modified despite matching HEAD\n")
            self.assert_bind_content_mismatch(
                root, containers, model, hashes, service, legacy_root,
            )

    def test_legacy_bind_symlink_escape_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            containers, model, _, _, _, legacy_root, current, _ = self.bind_fixture(root)
            outside = root / "outside.conf"
            outside.write_text("approved legacy content\n")
            current.unlink()
            try:
                current.symlink_to(outside)
            except OSError as exc:
                self.skipTest(f"symlink creation unavailable: {exc}")
            with self.assertRaises(InvariantError):
                legacy_bind_content_evidence(
                    "paid", containers, model, root / "release" / "bundle",
                    legacy_root=legacy_root,
                )

    def test_legacy_directory_add_remove_and_nested_change_fail_closed(self) -> None:
        mutations = {
            "added": lambda path: (path / "extra.txt").write_text("extra\n"),
            "removed": lambda path: (path / "seed.json").unlink(),
            "nested": lambda path: (path / "nested/item.txt").write_text("changed\n"),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                values = self.bind_fixture(root, directory=True)
                containers, model, hashes, _, service, legacy_root, current, _ = values
                mutate(current)
                self.assert_bind_content_mismatch(
                    root, containers, model, hashes, service, legacy_root,
                )

    def test_legacy_bind_target_or_access_mode_mismatch_fails_closed(self) -> None:
        for field in ("Destination", "RW"):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                containers, model, _, _, _, legacy_root, _, _ = self.bind_fixture(root)
                if field == "Destination":
                    containers[0]["Mounts"][0][field] = "/unexpected"
                else:
                    containers[0]["Mounts"][0][field] = True
                with self.assertRaises(InvariantError):
                    legacy_bind_content_evidence(
                        "paid", containers, model, root / "release" / "bundle",
                        legacy_root=legacy_root,
                    )

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
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            containers, canonical, _, evidence, service, legacy_root, _, _ = self.bind_fixture(root)
            canonical_service = canonical["services"][service]
            canonical_service.update({
                "command": ["serve", "--strict"],
                "environment": {"MODE": "staging"},
                "ports": [{"target": 8080, "published": "18080"}],
                "restart": "unless-stopped",
                "healthcheck": {"test": ["CMD", "true"]},
                "networks": {"default": {}},
            })
            containers[0]["Config"]["Image"] = "redis:7-alpine"
            containers[0]["Config"]["Cmd"] = ["serve", "--unapproved"]
            containers[0]["Config"]["Env"] = ["MODE=unexpected"]

            explained = explained_compose_model(
                "paid", containers, canonical, evidence, legacy_root=legacy_root,
            )
            explained_service = explained["services"][service]
            self.assertEqual("redis:7-alpine", explained_service["image"])
            self.assertEqual(
                containers[0]["Mounts"][0]["Source"],
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

    def test_cloud_protocol_with_four_verified_legacy_binds_remains_ssm_bounded(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            legacy_root = root / "srv" / "trinyx"
            checkout = legacy_root / "pr25-aeb2a44"
            bundle_root = root / "release" / "bundle"
            containers, model, hashes = self.fixture("cloud")
            explained = dict(hashes)
            legacy_services = sorted(SERVICES["cloud"])[:4]
            for service in legacy_services:
                position = sorted(SERVICES["cloud"]).index(service)
                relative = Path("docker/cloud") / f"{service}.conf"
                current = checkout / relative
                expected = bundle_root / relative
                current.parent.mkdir(parents=True, exist_ok=True)
                expected.parent.mkdir(parents=True, exist_ok=True)
                current.write_text(f"approved:{service}\n")
                expected.write_text(f"approved:{service}\n")
                containers[position]["Mounts"][0]["Source"] = str(current)
                model["services"][service]["volumes"][0]["source"] = str(expected)
                explained[service] = explained_hash(service)
                containers[position]["Config"]["Labels"][
                    "com.docker.compose.config-hash"
                ] = explained[service]
            evidence = legacy_bind_content_evidence(
                "cloud", containers, model, bundle_root, legacy_root=legacy_root,
            )
            record = self.build(
                containers, model, hashes, "cloud",
                explained_hashes=explained, bind_evidence=evidence,
            )
            output = render_ssm_protocol(record)
            print(f"CLOUD_LEGACY_BIND_PROTOCOL_BYTES={len(output.encode('utf-8'))}")
            self.assertLess(len(output.encode("utf-8")), SSM_STDOUT_MAX_BYTES)
            self.assertEqual(4, output.count("bind_proof=match:sha256:"))
            self.assertNotIn(str(root), output)



ROOT = Path(__file__).resolve().parents[3]
DIGEST_ONLY_IMAGE_RE = re.compile(r"^[^@\s]+@sha256:[0-9a-f]{64}$")
HISTORICAL_CLOUD_RUNTIME_BLOB = "21bf5663c814b3184765d1164430db08b69a2e93"
HISTORICAL_PAID_COMPOSE_BLOB = "3f13740c100913433d0af2b1eba7dec398ca8714"
HISTORICAL_CLOUD_COMPOSE_BLOB = "21ffc835177db4f3dcaaca2d827bd1f24a7003b3"
TRUSTED_PAID_RUNTIME_BLOB = "7a46cfa9ff0cc8d6aa87d3d4f8faf1352f2d3142"


def git_blob_sha(path: Path) -> str:
    content = path.read_bytes()
    return hashlib.sha1(f"blob {len(content)}\0".encode() + content).hexdigest()


class RepositoryComposeAdapter(ShellAdapter):
    def __init__(self, compose_files: tuple[Path, ...]):
        super().__init__(timeout_seconds=60)
        self.compose_files = compose_files

    def _compose_argv(self, base: Path, release_dir: Path, plan: object) -> list[str]:
        argv = ["docker", "compose"]
        for path in self.compose_files:
            argv.extend(["-f", str(path)])
        return argv


class RepositoryNormalizationPreflightTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        docker = shutil.which("docker")
        if docker is None:
            if os.environ.get("CI"):
                raise AssertionError("Docker CLI is required for repository normalization preflight")
            raise unittest.SkipTest("Docker CLI is unavailable")
        result = subprocess.run(
            [docker, "compose", "version", "--short"],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=20,
        )
        if result.returncode != 0:
            if os.environ.get("CI"):
                raise AssertionError("Docker Compose is required for repository normalization preflight")
            raise unittest.SkipTest("Docker Compose is unavailable")
        cls.compose_version = result.stdout.strip()
        print(f"NORMALIZATION_REAL_COMPOSE_VERSION={cls.compose_version}")

    @staticmethod
    def _config_root(role: str) -> Path:
        return (
            ROOT / "platform" / "bootstrap" / role / "staging" / "rootfs"
            / "etc" / "trinyx" / "staging" / role / "config"
        )

    def _plan_and_files(self, role: str) -> tuple[object, tuple[Path, ...]]:
        config_root = self._config_root(role)
        plan = load_host_plan(config_root / "deployment-plan.json", role)
        paths: list[Path] = []
        for item in plan.compose_files:
            if item.startswith("release/"):
                relative = item.removeprefix("release/")
                if role == "cloud" and relative == "docker/docker-compose.cloud.runtime.yml":
                    path = (
                        ROOT / "platform" / "automation" / "tests" / "fixtures"
                        / "docker-compose.cloud.runtime-aeb2.yml"
                    )
                else:
                    path = ROOT / relative
            elif item.startswith("/run/trinyx/"):
                path = config_root / Path(item).name
            else:
                path = config_root / item.removeprefix("config/")
            self.assertTrue(path.is_file(), f"missing repository Compose input for {item}")
            paths.append(path)
        self.assertEqual(len(plan.compose_files), len(paths))
        return plan, tuple(paths)

    @staticmethod
    def _required_value(name: str) -> str:
        if name.endswith("_PATH"):
            return "/tmp/trinyx-normalization-contract"
        if name.endswith(("_USER", "_USERNAME")):
            return "contract_user"
        if name.endswith("_URL"):
            return "https://example.invalid"
        return "contract-value"

    def _compose_environment(
        self, role: str, plan: object, paths: tuple[Path, ...],
    ) -> tuple[dict[str, str], dict[str, str]]:
        inventory = json.loads(
            (ROOT / "platform" / "release" / "runtime-inventory.json").read_text(encoding="utf-8")
        )
        role_images = [item for item in inventory["images"] if item["role"] == role]
        self.assertEqual(set(plan.services), {item["service"] for item in role_images})
        expected: dict[str, str] = {}
        environment: dict[str, str] = {}
        for index, item in enumerate(sorted(role_images, key=lambda value: value["service"]), 1):
            image = (
                f"example.invalid/trinyx/{role}-{item['service']}@sha256:"
                f"{index:064x}"
            )
            expected[item["service"]] = image
            environment[item["environment"]] = image

        for path in paths:
            text = path.read_text(encoding="utf-8")
            for match in re.finditer(r"\$\{([A-Z][A-Z0-9_]*)([^}]*)\}", text):
                name, suffix = match.groups()
                if suffix == "" or suffix.startswith(":?"):
                    environment.setdefault(name, self._required_value(name))
        return environment, expected

    @staticmethod
    def _synthetic_runtime(
        role: str, model: dict, hashes: dict[str, str],
    ) -> tuple[list[dict], list[dict]]:
        containers: list[dict] = []
        images: list[dict] = []
        for index, service in enumerate(sorted(SERVICES[role]), 1):
            image = model["services"][service]["image"]
            image_id = "sha256:" + f"{index + 1000:064x}"
            mounts = [
                {
                    "Type": mount["type"],
                    "Source": mount["source"],
                    "Destination": mount["destination"],
                    "RW": not mount["readOnly"],
                }
                for mount in expected_mounts(model["services"][service])
                if mount["type"] == "bind"
            ]
            containers.append({
                "Id": f"{index + 2000:064x}",
                "Image": image_id,
                "Config": {
                    "Image": image,
                    "Labels": {
                        "com.docker.compose.project": f"trinyx-{role}-staging",
                        "com.docker.compose.service": service,
                        "com.docker.compose.config-hash": hashes[service],
                    },
                },
                "Mounts": mounts,
            })
            images.append({"Id": image_id, "RepoDigests": [image]})
        return containers, images

    def test_historical_bundle_sources_are_the_exact_authenticated_blobs(self) -> None:
        fixture = (
            ROOT / "platform" / "automation" / "tests" / "fixtures"
            / "docker-compose.cloud.runtime-aeb2.yml"
        )
        self.assertEqual(HISTORICAL_CLOUD_RUNTIME_BLOB, git_blob_sha(fixture))
        self.assertEqual(HISTORICAL_PAID_COMPOSE_BLOB, git_blob_sha(ROOT / "docker-compose.yml"))
        self.assertEqual(
            HISTORICAL_CLOUD_COMPOSE_BLOB,
            git_blob_sha(ROOT / "docker" / "docker-compose.cloud.yml"),
        )
        self.assertEqual(
            TRUSTED_PAID_RUNTIME_BLOB,
            git_blob_sha(ROOT / "docker" / "docker-compose.paid.runtime.yml"),
        )

    def test_real_paid_and_cloud_normalization_preflight_contracts(self) -> None:
        for role, expected_count in (("paid", 8), ("cloud", 20)):
            with self.subTest(role=role):
                plan, paths = self._plan_and_files(role)
                environment, expected_images = self._compose_environment(role, plan, paths)
                adapter = RepositoryComposeAdapter(paths)
                with mock.patch.dict(os.environ, environment, clear=False):
                    model = adapter.render_model(ROOT, ROOT, plan)
                    source_hashes = adapter.compose_config_hashes(ROOT, ROOT, plan)
                roundtrip_hashes = adapter.compose_model_hashes(model, plan.services)

                self.assertEqual(expected_count, len(plan.services))
                self.assertEqual(set(SERVICES[role]), set(plan.services))
                self.assertEqual(set(plan.services), set(model["services"]))
                self.assertEqual(set(plan.services), set(source_hashes))
                self.assertEqual(source_hashes, roundtrip_hashes)
                for service in plan.services:
                    image = model["services"][service].get("image")
                    self.assertEqual(expected_images[service], image)
                    self.assertRegex(image, DIGEST_ONLY_IMAGE_RE)

                if role == "paid":
                    self.assertEqual(
                        environment["TRINYX_PAID_EDGE_IMAGE"],
                        model["services"]["paid-edge"]["image"],
                    )
                    self.assertNotEqual(
                        "caddy:2.11.4-alpine",
                        model["services"]["paid-edge"]["image"],
                    )

                containers, image_inspections = self._synthetic_runtime(
                    role, model, source_hashes
                )
                record = build_normalization_plan(
                    role,
                    "rel-v1-" + "a" * 32,
                    containers,
                    image_inspections,
                    model,
                    source_hashes,
                    source_hashes,
                    empty_bind_evidence(role),
                    self.compose_version,
                    "2026-09-05T00:00:00Z",
                    bundle_digest="sha256:" + "b" * 64,
                    deployment_id="dep-" + "c" * 32,
                    environment_config_revision="config-contract",
                    environment_config_digest_value="sha256:" + "d" * 64,
                    control_plane_commit="e" * 40,
                )
                protocol = render_ssm_protocol(record)
                result = validate_normalization_protocol(
                    protocol,
                    role,
                    "rel-v1-" + "a" * 32,
                    "sha256:" + "b" * 64,
                    "dep-" + "c" * 32,
                    "config-contract",
                    "e" * 40,
                )
                self.assertEqual(expected_count, result["serviceCount"])
                self.assertEqual(0, result["recreateCount"])
                self.assertEqual("review", result["compatibility"])
                self.assertEqual("matched", result["images"])
                lines = protocol.rstrip("\n").split("\n")
                self.assertEqual(
                    expected_count,
                    sum(line.startswith("NORMALIZATION ") for line in lines),
                )
                self.assertTrue(lines[-1].startswith("LEGACY_NORMALIZATION_PLAN_COMPLETE "))
                print(
                    f"NORMALIZATION_REPOSITORY_PREFLIGHT_OK role={role} "
                    f"services={expected_count} hashes={len(source_hashes)} "
                    f"roundtrip=match protocol_bytes={len(protocol.encode('utf-8'))}"
                )



if __name__ == "__main__":
    unittest.main()
