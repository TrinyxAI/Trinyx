from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]

CLOUD = ROOT / "platform/host/cloud"
PAID = ROOT / "platform/host/paid"
CLOUD_BOOTSTRAP = ROOT / "platform/bootstrap/cloud/staging/rootfs"
PAID_BOOTSTRAP = ROOT / "platform/bootstrap/paid/staging/rootfs"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def directives(text: str) -> dict[str, dict[str, list[str]]]:
    parsed: dict[str, dict[str, list[str]]] = {}
    section = ""
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1]
            parsed.setdefault(section, {})
            continue
        if "=" not in line or not section:
            continue
        key, value = line.split("=", 1)
        parsed[section].setdefault(key, []).append(value)
    return parsed


def words(unit: dict[str, dict[str, list[str]]], key: str) -> set[str]:
    return {
        word
        for value in unit.get("Unit", {}).get(key, [])
        for word in value.split()
    }


def effective_service_value(base: str, drop_in: str, key: str) -> str | None:
    values = directives(base).get("Service", {}).get(key, [])
    values += directives(drop_in).get("Service", {}).get(key, [])
    return values[-1] if values else None


def service_block(compose: str, name: str) -> str:
    lines = compose.splitlines()
    marker = f"  {name}:"
    start = lines.index(marker)
    end = len(lines)
    for index in range(start + 1, len(lines)):
        if re.fullmatch(r"  [A-Za-z0-9_-]+:", lines[index]):
            end = index
            break
        if lines[index] and not lines[index].startswith(" "):
            end = index
            break
    return "\n".join(lines[start:end])


def ordering_edges(units: dict[str, str]) -> dict[str, set[str]]:
    edges: dict[str, set[str]] = {}
    for name, text in units.items():
        unit = directives(text)
        edges.setdefault(name, set())
        for dependency in words(unit, "After"):
            edges.setdefault(dependency, set()).add(name)
        for dependent in words(unit, "Before"):
            edges[name].add(dependent)
    return edges


def require_acyclic(edges: dict[str, set[str]]) -> None:
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(node: str) -> None:
        if node in visiting:
            raise AssertionError(f"systemd ordering cycle at {node}")
        if node in visited:
            return
        visiting.add(node)
        for child in edges.get(node, set()):
            visit(child)
        visiting.remove(node)
        visited.add(node)

    for node in edges:
        visit(node)


class StagingBootOrchestrationTests(unittest.TestCase):
    maxDiff = None

    def setUp(self) -> None:
        self.cloud_materializer = read(
            CLOUD / "systemd/trinyx-cloud-runtime-materialize.service"
        )
        self.cloud_materializer_drop_in = read(
            CLOUD
            / "systemd/staging/trinyx-cloud-runtime-materialize.service.d/20-trinyx-staging-retrigger.conf"
        )
        self.cloud_docker_base = read(
            CLOUD / "systemd/docker.service.d/10-trinyx-runtime.conf"
        )
        self.cloud_docker_gate = read(
            CLOUD
            / "systemd/staging/docker.service.d/20-trinyx-staging-runtime-gate.conf"
        )
        self.pre_docker_unit = read(CLOUD / "systemd/trinyx-pre-docker.service")

        self.paid_materializer = read(
            PAID / "systemd/trinyx-paid-runtime-materialize.service"
        )
        self.paid_materializer_drop_in = read(
            PAID
            / "systemd/staging/trinyx-paid-runtime-materialize.service.d/20-trinyx-staging-retrigger.conf"
        )
        self.paid_docker_base = read(
            PAID / "systemd/docker.service.d/10-trinyx-storage.conf"
        )
        self.paid_docker_gate = read(
            PAID
            / "systemd/staging/docker.service.d/20-trinyx-staging-runtime-gate.conf"
        )

    def test_cloud_docker_waits_for_pre_docker_and_materializer(self) -> None:
        docker = directives(self.cloud_docker_base + self.cloud_docker_gate)
        self.assertEqual(
            words(docker, "Requires"),
            {
                "trinyx-pre-docker.service",
                "trinyx-cloud-runtime-materialize.service",
            },
        )
        self.assertEqual(words(docker, "After"), words(docker, "Requires"))

        materializer = directives(self.cloud_materializer)
        self.assertIn("network-online.target", words(materializer, "After"))
        self.assertIn("trinyx-pre-docker.service", words(materializer, "After"))
        self.assertIn(
            "trinyx-pre-docker.service", words(materializer, "Requires")
        )
        self.assertIn(
            "docker.service", words(directives(self.pre_docker_unit), "Before")
        )
        self.assertEqual(
            effective_service_value(
                self.cloud_materializer,
                self.cloud_materializer_drop_in,
                "RemainAfterExit",
            ),
            "no",
        )

    def test_paid_docker_waits_for_materializer(self) -> None:
        docker = directives(self.paid_docker_base + self.paid_docker_gate)
        self.assertEqual(
            words(docker, "Requires"),
            {"trinyx-paid-runtime-materialize.service"},
        )
        self.assertEqual(words(docker, "After"), words(docker, "Requires"))
        self.assertIn(
            "network-online.target",
            words(directives(self.paid_materializer), "After"),
        )
        self.assertEqual(
            effective_service_value(
                self.paid_materializer,
                self.paid_materializer_drop_in,
                "RemainAfterExit",
            ),
            "no",
        )

    def test_materializer_and_pre_docker_failures_are_fail_closed(self) -> None:
        cloud_docker = directives(self.cloud_docker_base + self.cloud_docker_gate)
        paid_docker = directives(self.paid_docker_base + self.paid_docker_gate)
        self.assertTrue(
            {
                "trinyx-pre-docker.service",
                "trinyx-cloud-runtime-materialize.service",
            }.issubset(words(cloud_docker, "Requires"))
        )
        self.assertIn(
            "trinyx-paid-runtime-materialize.service",
            words(paid_docker, "Requires"),
        )
        for unit in (self.cloud_materializer, self.paid_materializer):
            parsed = directives(unit)
            self.assertEqual(parsed["Service"]["Type"], ["oneshot"])
            self.assertEqual(parsed["Service"]["Restart"], ["on-failure"])

    def test_systemd_ordering_graphs_are_acyclic(self) -> None:
        cloud_units = {
            "trinyx-pre-docker.service": self.pre_docker_unit,
            "trinyx-cloud-runtime-materialize.service": self.cloud_materializer,
            "docker.service": self.cloud_docker_base + self.cloud_docker_gate,
        }
        paid_units = {
            "trinyx-paid-runtime-materialize.service": self.paid_materializer,
            "docker.service": self.paid_docker_base + self.paid_docker_gate,
        }
        require_acyclic(ordering_edges(cloud_units))
        require_acyclic(ordering_edges(paid_units))

    @unittest.skipUnless(shutil.which("systemd-analyze"), "systemd-analyze unavailable")
    def test_systemd_analyze_verifies_both_role_graphs(self) -> None:
        for role, units in {
            "cloud": {
                "trinyx-pre-docker.service": self.pre_docker_unit,
                "trinyx-cloud-runtime-materialize.service": self.cloud_materializer.replace(
                    "RemainAfterExit=yes", "RemainAfterExit=no"
                ),
                "docker.service": self.cloud_docker_base
                + self.cloud_docker_gate
                + "\n[Service]\nType=oneshot\nExecStart=/bin/true\n",
            },
            "paid": {
                "trinyx-paid-runtime-materialize.service": self.paid_materializer.replace(
                    "RemainAfterExit=yes", "RemainAfterExit=no"
                ),
                "docker.service": self.paid_docker_base
                + self.paid_docker_gate
                + "\n[Service]\nType=oneshot\nExecStart=/bin/true\n",
            },
        }.items():
            with self.subTest(role=role), tempfile.TemporaryDirectory() as temp:
                paths: list[str] = []
                for name, text in units.items():
                    sanitized = re.sub(
                        r"^ExecStart=.*$", "ExecStart=/bin/true", text, flags=re.MULTILINE
                    )
                    path = Path(temp) / name
                    path.write_text(sanitized, encoding="utf-8")
                    paths.append(str(path))
                subprocess.run(
                    ["systemd-analyze", "--man=no", "verify", *paths],
                    check=True,
                    text=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                )

    def test_boot_scripts_never_control_docker_or_sleep(self) -> None:
        scripts = {
            "pre-docker": read(CLOUD / "pre-docker-cloud.sh"),
            "cloud-materializer": read(CLOUD / "runtime-materialize-cloud.sh"),
            "paid-materializer": read(PAID / "runtime-materialize-paid.sh"),
        }
        forbidden = (
            r"docker\s+compose\s+.*\bup\b",
            r"docker\s+system\s+prune",
            r"docker\s+volume\s+rm",
            r"docker\s+(?:start|run)\b",
            r"\bsleep\s+[0-9]",
        )
        for name, script in scripts.items():
            for pattern in forbidden:
                with self.subTest(script=name, pattern=pattern):
                    self.assertIsNone(re.search(pattern, script))

    def test_runtime_inputs_are_atomic_and_actual_mounts_are_known(self) -> None:
        cloud_script = read(CLOUD / "runtime-materialize-cloud.sh")
        paid_script = read(PAID / "runtime-materialize-paid.sh")
        for script, prefix in ((cloud_script, "cloud"), (paid_script, "paid")):
            self.assertIn("mktemp -d", script)
            self.assertIn('mv "$TMP" "$FINAL"', script)
            self.assertIn('mv -Tf "$LINKTMP" "$CURRENT"', script)
            self.assertIn('rm -f "$TMP/ssm.json"', script)
            self.assertNotIn("docker ", script)
            self.assertIn(f"{prefix}-materialized", script)

        auth_files = read(CLOUD / "cloud-auth-files.sh")
        self.assertEqual(
            set(auth_files.splitlines()),
            {
                "export PAID_MONOLITH_TRUSTSTORE_PATH=/run/trinyx/auth-runtime/paid-monolith-truststore.p12",
                "export PAID_MONOLITH_TRUSTSTORE_PASSWORD_PATH=/run/trinyx/auth-runtime/paid-monolith-truststore-password",
            },
        )
        cloud_compose = read(ROOT / "docker/docker-compose.cloud.yml")
        self.assertIn(
            "${PAID_MONOLITH_TRUSTSTORE_PATH:?set PAID_MONOLITH_TRUSTSTORE_PATH}:/run/secrets/paid-monolith-truststore.p12:ro",
            cloud_compose,
        )
        self.assertIn(
            "file: ${PAID_MONOLITH_TRUSTSTORE_PASSWORD_PATH:?set PAID_MONOLITH_TRUSTSTORE_PASSWORD_PATH}",
            cloud_compose,
        )
        paid_sources = "\n".join(
            read(path)
            for path in (
                ROOT / "docker-compose.yml",
                ROOT / "platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/paid.override.yml",
                ROOT / "platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/paid-bind.override.yml",
                ROOT / "platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/paid-runtime.override.yml",
            )
        )
        self.assertNotIn("/run/trinyx", paid_sources)

    def test_migration_and_init_services_remain_non_restarting(self) -> None:
        cloud = read(ROOT / "docker/docker-compose.cloud.yml")
        paid = read(ROOT / "docker-compose.yml")
        self.assertIn('restart: "no"', service_block(cloud, "migration-service"))
        self.assertIn('restart: "no"', service_block(cloud, "cloud-minio-init"))
        self.assertIn('restart: "no"', service_block(paid, "minio-init"))
        self.assertIn("restart: unless-stopped", service_block(cloud, "cloud-postgres"))
        self.assertIn("restart: unless-stopped", service_block(paid, "livecontext"))

    def test_boot_never_changes_active_release_pointer(self) -> None:
        for path in (
            CLOUD / "pre-docker-cloud.sh",
            CLOUD / "runtime-materialize-cloud.sh",
            PAID / "runtime-materialize-paid.sh",
        ):
            script = read(path)
            self.assertNotIn("atomic_active_pointer", script)
            self.assertIsNone(re.search(r"(?:mv|ln)\s+[^\n]*\$ACTIVE", script))
        for plan_path in (
            ROOT
            / "platform/bootstrap/cloud/staging/rootfs/etc/trinyx/staging/cloud/config/deployment-plan.json",
            ROOT
            / "platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/deployment-plan.json",
        ):
            plan = json.loads(read(plan_path))
            self.assertEqual(plan["schemaVersion"], 1)

    def test_bootstrap_and_reconciler_drop_ins_are_identical(self) -> None:
        pairs = (
            (
                CLOUD
                / "systemd/staging/docker.service.d/20-trinyx-staging-runtime-gate.conf",
                CLOUD_BOOTSTRAP
                / "etc/systemd/system/docker.service.d/20-trinyx-staging-runtime-gate.conf",
            ),
            (
                CLOUD
                / "systemd/staging/trinyx-cloud-runtime-materialize.service.d/20-trinyx-staging-retrigger.conf",
                CLOUD_BOOTSTRAP
                / "etc/systemd/system/trinyx-cloud-runtime-materialize.service.d/20-trinyx-staging-retrigger.conf",
            ),
            (
                PAID
                / "systemd/staging/docker.service.d/20-trinyx-staging-runtime-gate.conf",
                PAID_BOOTSTRAP
                / "etc/systemd/system/docker.service.d/20-trinyx-staging-runtime-gate.conf",
            ),
            (
                PAID
                / "systemd/staging/trinyx-paid-runtime-materialize.service.d/20-trinyx-staging-retrigger.conf",
                PAID_BOOTSTRAP
                / "etc/systemd/system/trinyx-paid-runtime-materialize.service.d/20-trinyx-staging-retrigger.conf",
            ),
        )
        for managed, bootstrap in pairs:
            with self.subTest(path=managed):
                self.assertEqual(read(managed), read(bootstrap))


if __name__ == "__main__":
    unittest.main()

