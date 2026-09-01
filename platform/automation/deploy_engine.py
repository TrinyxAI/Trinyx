#!/usr/bin/env python3
"""Fail-closed immutable staging host deployment state machine."""

from __future__ import annotations

import argparse
import contextlib
import copy
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

if __package__:
    from .invariants import (
        InvariantError,
        RELEASE_RE,
        SERVICE_RE,
        parse_images_env,
        require,
        validate_active_pointer,
        validate_compose_model,
        validate_deployment_record,
        validate_release_directory,
    )
else:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from invariants import (  # type: ignore
        InvariantError,
        RELEASE_RE,
        SERVICE_RE,
        parse_images_env,
        require,
        validate_active_pointer,
        validate_compose_model,
        validate_deployment_record,
        validate_release_directory,
    )

DEPLOYMENT_RE = re.compile(r"^dep-[0-9a-f]{32}$")
REVISION_RE = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
SHA_RE = re.compile(r"^[0-9a-f]{40}$")
TRANSITIONS = {
    "CREATED": {"PREFLIGHT", "FAILED"},
    "PREFLIGHT": {"READY", "FAILED"},
    "READY": {"MIGRATING", "ACTIVATING", "HEALTH_CHECKING", "FAILED"},
    "MIGRATING": {"ACTIVATING", "MANUAL_RECOVERY_REQUIRED", "FAILED"},
    "ACTIVATING": {"HEALTH_CHECKING", "ROLLING_BACK", "MANUAL_RECOVERY_REQUIRED", "FAILED"},
    "HEALTH_CHECKING": {"SUCCESS", "ROLLED_BACK", "ROLLING_BACK", "MANUAL_RECOVERY_REQUIRED", "FAILED"},
    "ROLLING_BACK": {"ROLLED_BACK", "ROLLBACK_FAILED", "MANUAL_RECOVERY_REQUIRED"},
    "ROLLED_BACK": set(),
    "SUCCESS": set(),
    "FAILED": set(),
    "ROLLBACK_FAILED": set(),
    "MANUAL_RECOVERY_REQUIRED": set(),
}


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def atomic_write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temp = path.with_name(f".{path.name}.{os.getpid()}.{uuid.uuid4().hex}.tmp")
    payload = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n"
    with temp.open("x", encoding="utf-8", newline="\n") as handle:
        os.chmod(temp, 0o600)
        handle.write(payload)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temp, path)
    if os.name != "nt":
        directory_fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)


def atomic_active_pointer(base: Path, release_id: str) -> None:
    require(RELEASE_RE.fullmatch(release_id) is not None, "invalid active release ID")
    target = base / "releases" / release_id
    require(target.is_dir() and not target.is_symlink(), "activation target is not an immutable installed release")
    temp = base / f".active.{os.getpid()}.{uuid.uuid4().hex}"
    try:
        os.symlink(f"releases/{release_id}", temp, target_is_directory=True)
        os.replace(temp, base / "active")
        if os.name != "nt":
            directory_fd = os.open(base, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
    finally:
        with contextlib.suppress(FileNotFoundError):
            temp.unlink()


class DeploymentLock:
    """Kernel lock on Linux; exclusive-file fallback is only for local tests."""

    def __init__(self, path: Path):
        self.path = path
        self.handle: Any = None
        self.fallback = False

    def __enter__(self) -> "DeploymentLock":
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        try:
            import fcntl  # type: ignore

            self.handle = self.path.open("a+", encoding="utf-8")
            try:
                fcntl.flock(self.handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as exc:
                self.handle.close()
                raise InvariantError("concurrent staging deployment refused") from exc
            self.handle.seek(0)
            self.handle.truncate()
            self.handle.write(f"pid={os.getpid()}\n")
            self.handle.flush()
        except ImportError:
            self.fallback = True
            try:
                fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            except FileExistsError as exc:
                raise InvariantError("concurrent staging deployment refused") from exc
            os.write(fd, f"pid={os.getpid()}\n".encode())
            self.handle = fd
        return self

    def __exit__(self, *_: Any) -> None:
        if self.fallback:
            if isinstance(self.handle, int):
                os.close(self.handle)
            with contextlib.suppress(FileNotFoundError):
                self.path.unlink()
        elif self.handle is not None:
            import fcntl  # type: ignore

            fcntl.flock(self.handle.fileno(), fcntl.LOCK_UN)
            self.handle.close()


@dataclass(frozen=True)
class OneShot:
    services: tuple[str, ...]
    rollback_safe: bool


@dataclass(frozen=True)
class HostPlan:
    role: str
    compose_files: tuple[str, ...]
    services: tuple[str, ...]
    config_sensitive_services: tuple[str, ...]
    required_files: tuple[str, ...]
    tls_files: tuple[str, ...]
    required_secret_names: tuple[str, ...]
    health: tuple[dict[str, Any], ...]
    min_free_bytes: int
    max_restart_count: int
    one_shot: OneShot


def load_host_plan(path: Path, role: str) -> HostPlan:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise InvariantError(f"missing/invalid environment deployment plan: {exc}") from exc
    keys = {
        "schemaVersion",
        "role",
        "composeFiles",
        "services",
        "configSensitiveServices",
        "requiredFiles",
        "tlsFiles",
        "requiredSecretNames",
        "health",
        "minFreeBytes",
        "maxRestartCount",
        "oneShot",
    }
    require(isinstance(raw, dict) and set(raw) == keys, "environment deployment plan schema mismatch")
    require(raw["schemaVersion"] == 1 and raw["role"] == role, "environment deployment plan role/schema mismatch")
    for key in ("composeFiles", "services", "configSensitiveServices", "requiredFiles", "tlsFiles", "requiredSecretNames", "health"):
        require(isinstance(raw[key], list), f"deployment plan {key} must be a list")
    services = tuple(str(item) for item in raw["services"])
    require(services and len(services) == len(set(services)), "deployment service inventory missing/duplicate")
    require(all(SERVICE_RE.fullmatch(item) for item in services), "unsafe deployment service name")
    config_sensitive = tuple(str(item) for item in raw["configSensitiveServices"])
    require(len(config_sensitive) == len(set(config_sensitive)) and all(item in services for item in config_sensitive),
            "invalid config-sensitive service inventory")
    compose_files = tuple(str(item) for item in raw["composeFiles"])
    require(compose_files and len(compose_files) == len(set(compose_files)), "Compose file inventory missing/duplicate")
    for item in compose_files:
        require((item.startswith(("release/", "config/")) or item.startswith("/run/trinyx/"))
                and ".." not in Path(item).parts, f"unsafe Compose path: {item}")
    for item in (*raw["requiredFiles"], *raw["tlsFiles"]):
        require((item.startswith(("release/", "config/")) or item.startswith("/run/trinyx/"))
                and ".." not in Path(item).parts, f"unsafe plan path: {item}")
    secret_names = tuple(str(item) for item in raw["requiredSecretNames"])
    require(all(re.fullmatch(r"[A-Z][A-Z0-9_]*", item) for item in secret_names), "unsafe required secret name")
    health: list[dict[str, Any]] = []
    for check in raw["health"]:
        require(isinstance(check, dict) and set(check) == {"name", "argv", "timeoutSeconds"}, "health check schema mismatch")
        require(SERVICE_RE.fullmatch(str(check["name"])) is not None, "unsafe health check name")
        require(isinstance(check["argv"], list) and check["argv"] and all(isinstance(x, str) and x for x in check["argv"]), "health check argv missing")
        require(not any(x in {"-k", "--insecure"} for x in check["argv"]), "TLS bypass is forbidden")
        require(isinstance(check["timeoutSeconds"], int) and 1 <= check["timeoutSeconds"] <= 120, "health timeout out of bounds")
        health.append(copy.deepcopy(check))
    one = raw["oneShot"]
    require(isinstance(one, dict) and set(one) == {"services", "rollbackSafe"}, "one-shot schema mismatch")
    require(isinstance(one["services"], list) and all(x in services for x in one["services"]), "unknown one-shot service")
    require(isinstance(one["rollbackSafe"], bool), "rollback safety must be explicit")
    require(isinstance(raw["minFreeBytes"], int) and raw["minFreeBytes"] >= 0, "bad disk threshold")
    require(isinstance(raw["maxRestartCount"], int) and 0 <= raw["maxRestartCount"] <= 20, "bad restart threshold")
    return HostPlan(
        role=role,
        compose_files=compose_files,
        services=services,
        config_sensitive_services=config_sensitive,
        required_files=tuple(str(x) for x in raw["requiredFiles"]),
        tls_files=tuple(str(x) for x in raw["tlsFiles"]),
        required_secret_names=secret_names,
        health=tuple(health),
        min_free_bytes=raw["minFreeBytes"],
        max_restart_count=raw["maxRestartCount"],
        one_shot=OneShot(tuple(str(x) for x in one["services"]), one["rollbackSafe"]),
    )


class Adapter(Protocol):
    def preflight(self, base: Path, release_dir: Path, plan: HostPlan) -> dict[str, Any]: ...

    def render_model(self, base: Path, release_dir: Path, plan: HostPlan) -> dict[str, Any]: ...

    def run_migrations(self, base: Path, release_dir: Path, plan: HostPlan, services: tuple[str, ...]) -> None: ...

    def materialize(self, role: str) -> None: ...

    def apply_services(self, base: Path, release_dir: Path, plan: HostPlan, services: tuple[str, ...]) -> None: ...

    def health(self, base: Path, release_dir: Path, plan: HostPlan, services: tuple[str, ...]) -> None: ...


class Pointer(Protocol):
    def current(self, base: Path, role: str) -> str: ...

    def activate(self, base: Path, release_id: str) -> None: ...


class SymlinkPointer:
    def current(self, base: Path, role: str) -> str:
        return validate_active_pointer(base, role)

    def activate(self, base: Path, release_id: str) -> None:
        atomic_active_pointer(base, release_id)


class ShellAdapter:
    """Production adapter. Every subprocess uses argv arrays and finite timeouts."""

    def __init__(self, timeout_seconds: int = 300):
        self.timeout_seconds = timeout_seconds

    def _run(self, argv: list[str], timeout: int | None = None, capture: bool = False) -> subprocess.CompletedProcess[str]:
        try:
            return subprocess.run(
                argv,
                check=True,
                text=True,
                stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                stderr=subprocess.PIPE if capture else subprocess.DEVNULL,
                timeout=timeout or self.timeout_seconds,
            )
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            # Command output can contain secrets; never include it in the exception.
            raise InvariantError(f"bounded command failed: {argv[0]}") from exc

    @staticmethod
    def _resolve(base: Path, release_dir: Path, item: str) -> Path:
        if item.startswith("/run/trinyx/"):
            resolved = Path(item)
            try:
                real = resolved.resolve(strict=True)
            except OSError as exc:
                raise InvariantError(f"required runtime generation file missing: {item}") from exc
            generation_roots = (Path("/run/trinyx/cloud-materialized"), Path("/run/trinyx/paid-materialized"))
            require(real.is_file() and any(str(real).startswith(str(root) + os.sep) for root in generation_roots),
                    f"runtime link escapes atomic generation: {item}")
            return resolved
        elif item.startswith("release/"):
            resolved = release_dir / "bundle" / item.removeprefix("release/")
        else:
            resolved = base / "config" / item.removeprefix("config/")
        require(resolved.is_file() and not resolved.is_symlink(), f"required file missing: {item}")
        return resolved

    def _compose_argv(self, base: Path, release_dir: Path, plan: HostPlan) -> list[str]:
        runtime = "/run/trinyx/cloud.runtime.active.sh" if plan.role == "cloud" else "/run/trinyx/paid-secrets.env"
        # The program is fixed and every variable argument is positional; no
        # release/config/user value is interpolated into shell source text.
        argv = ["bash", "-c", f'set -euo pipefail; . {runtime}; exec "$@"', "trinyx",
                "docker", "compose", "--env-file", str(release_dir / "images.env")]
        for item in plan.compose_files:
            argv.extend(["-f", str(self._resolve(base, release_dir, item))])
        return argv

    def render_model(self, base: Path, release_dir: Path, plan: HostPlan) -> dict[str, Any]:
        rendered = self._run([*self._compose_argv(base, release_dir, plan), "config", "--format", "json"], timeout=60, capture=True)
        try:
            return json.loads(rendered.stdout)
        except json.JSONDecodeError as exc:
            raise InvariantError("Compose render returned invalid JSON") from exc

    def preflight(self, base: Path, release_dir: Path, plan: HostPlan) -> dict[str, Any]:
        for binary in ("bash", "docker", "aws", "amazon-ssm-agent"):
            require(shutil.which(binary) is not None, f"required binary missing: {binary}")
        self._run(["docker", "compose", "version"], timeout=20)
        # Existing materializers prove all required SSM values are present without
        # printing their values and publish a complete /run generation atomically.
        self.materialize(plan.role)
        for item in (*plan.required_files, *plan.tls_files):
            self._resolve(base, release_dir, item)
        secret_inventory = base / "config" / "secret-names.present"
        if not secret_inventory.is_file():
            secret_inventory = base / "config" / "ssm-required.txt"
        require(secret_inventory.is_file() and not secret_inventory.is_symlink(), "required secret inventory missing")
        present = {line.strip() for line in secret_inventory.read_text(encoding="utf-8").splitlines() if line.strip()}
        require(set(plan.required_secret_names).issubset(present), "required secret missing")
        required_space = max(plan.min_free_bytes, int(release_dir.joinpath("deployment-bundle.tar").stat().st_size * 3 + 5 * 1024**3))
        require(shutil.disk_usage(base).free >= required_space, "insufficient disk space")
        argv = self._compose_argv(base, release_dir, plan)
        model = self.render_model(base, release_dir, plan)
        expected = parse_images_env(release_dir / "images.env")
        validate_compose_model(model, plan.role, expected)
        for image in sorted(set(expected.values())):
            self._run(["docker", "pull", image], timeout=180)
        return model

    def run_migrations(self, base: Path, release_dir: Path, plan: HostPlan, services: tuple[str, ...]) -> None:
        argv = self._compose_argv(base, release_dir, plan)
        require(all(service in plan.one_shot.services for service in services), "unknown one-shot service")
        for service in services:
            self._run([*argv, "run", "--rm", "--no-deps", service], timeout=300)

    def materialize(self, role: str) -> None:
        self._run(["systemctl", "start", f"trinyx-{role}-runtime-materialize.service"], timeout=120)

    def apply_services(self, base: Path, release_dir: Path, plan: HostPlan, services: tuple[str, ...]) -> None:
        if not services:
            return
        require(all(service in plan.services for service in services), "delta contains unknown service")
        argv = self._compose_argv(base, release_dir, plan)
        self._run([*argv, "up", "-d", "--no-deps", "--wait", "--wait-timeout", "180", *services], timeout=240)

    def health(self, base: Path, release_dir: Path, plan: HostPlan, services: tuple[str, ...]) -> None:
        rendered_services = self.render_model(base, release_dir, plan).get("services", {})
        for service in services or plan.services:
            compose = self._compose_argv(base, release_dir, plan)
            container = self._run([*compose, "ps", "-q", service], timeout=20, capture=True).stdout.strip()
            require(bool(container), f"container missing: {service}")
            inspect = self._run(
                ["docker", "inspect", "--format", "{{json .}}", container],
                timeout=20,
                capture=True,
            )
            try:
                state = json.loads(inspect.stdout)
            except json.JSONDecodeError as exc:
                raise InvariantError(f"invalid container state for {service}") from exc
            container_state = state.get("State", {})
            expected_image = rendered_services.get(service, {}).get("image")
            require(expected_image and state.get("Config", {}).get("Image") == expected_image,
                    f"active container digest mismatch: {service}")
            require(container_state.get("Running") is True, f"container not running: {service}")
            require(container_state.get("OOMKilled") is False, f"container OOM: {service}")
            require(int(state.get("RestartCount", 0)) <= plan.max_restart_count, f"restart loop: {service}")
            health = container_state.get("Health")
            if health is not None:
                require(health.get("Status") == "healthy", f"container unhealthy: {service}")
        for check in plan.health:
            self._run(list(check["argv"]), timeout=check["timeoutSeconds"])


class DeploymentRecord:
    def __init__(
        self,
        path: Path,
        deployment_id: str,
        release_id: str,
        config_revision: str,
        platform_commit: str,
        previous_cloud: str | None,
        previous_paid: str | None,
    ):
        now = utc_now()
        self.path = path
        self.value: dict[str, Any] = {
            "schemaVersion": 1,
            "deploymentId": deployment_id,
            "environment": "staging",
            "releaseId": release_id,
            "environmentConfigRevision": config_revision,
            "platformCommit": platform_commit,
            "previousCloudRelease": previous_cloud,
            "previousPaidRelease": previous_paid,
            "state": "CREATED",
            "createdAt": now,
            "startedAt": None,
            "completedAt": None,
            "failure": None,
            "rollbackResult": None,
            "history": [{"state": "CREATED", "at": now}],
        }
        self.save()

    def save(self) -> None:
        validate_deployment_record(self.value)
        atomic_write_json(self.path, self.value)

    def transition(self, state: str, failure: str | None = None, rollback_result: str | None = None) -> None:
        current = self.value["state"]
        require(state in TRANSITIONS[current], f"invalid deployment transition {current}->{state}")
        now = utc_now()
        self.value["state"] = state
        self.value["history"].append({"state": state, "at": now})
        if self.value["startedAt"] is None and state == "PREFLIGHT":
            self.value["startedAt"] = now
        if failure is not None:
            self.value["failure"] = failure[:512]
        if rollback_result is not None:
            self.value["rollbackResult"] = rollback_result[:128]
        if state in {"SUCCESS", "ROLLED_BACK", "FAILED", "ROLLBACK_FAILED", "MANUAL_RECOVERY_REQUIRED"}:
            self.value["completedAt"] = now
        self.save()


class HostDeployment:
    def __init__(self, base: Path, role: str, adapter: Adapter, pointer: Pointer | None = None):
        require(role in {"cloud", "paid"}, "invalid role")
        self.base = base
        self.role = role
        self.adapter = adapter
        self.pointer = pointer or SymlinkPointer()

    def current_release(self) -> str:
        return self.pointer.current(self.base, self.role)

    def _record(
        self,
        deployment_id: str,
        release_id: str,
        config_revision: str,
        platform_commit: str,
        previous_cloud: str | None,
        previous_paid: str | None,
    ) -> DeploymentRecord:
        return DeploymentRecord(
            self.base / "deployments" / f"{deployment_id}.json",
            deployment_id,
            release_id,
            config_revision,
            platform_commit,
            previous_cloud,
            previous_paid,
        )

    def _validate_inputs(self, deployment_id: str, release_id: str, config_revision: str, platform_commit: str) -> None:
        require(DEPLOYMENT_RE.fullmatch(deployment_id) is not None, "invalid deployment ID")
        require(RELEASE_RE.fullmatch(release_id) is not None, "invalid release ID")
        require(REVISION_RE.fullmatch(config_revision) is not None, "invalid environment config revision")
        require(SHA_RE.fullmatch(platform_commit) is not None, "invalid platform commit")

    def _migration_rollback_safe(self, plan: HostPlan, previous: str, candidate: str) -> bool:
        if plan.one_shot.rollback_safe:
            return True
        path = self.base / "config" / "rollback-safety.json"
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return False
        required = {"schemaVersion", "previousRelease", "candidateRelease", "strategy", "compatible", "evidenceSha256"}
        return (
            isinstance(value, dict) and set(value) == required and value["schemaVersion"] == 1
            and value["previousRelease"] == previous and value["candidateRelease"] == candidate
            and value["strategy"] == "expand-contract" and value["compatible"] is True
            and re.fullmatch(r"sha256:[0-9a-f]{64}", str(value["evidenceSha256"])) is not None
        )

    def plan(self, release_id: str) -> tuple[HostPlan, dict[str, Any]]:
        release_dir = self.base / "releases" / release_id
        validate_release_directory(release_dir, self.role)
        plan = load_host_plan(self.base / "config" / "deployment-plan.json", self.role)
        model = self.adapter.preflight(self.base, release_dir, plan)
        return plan, model

    def _delta(self, previous: str, candidate: str, plan: HostPlan, candidate_model: dict[str, Any]) -> tuple[str, ...]:
        previous_model = self.adapter.render_model(self.base, self.base / "releases" / previous, plan)
        old_services = previous_model.get("services", {})
        new_services = candidate_model.get("services", {})
        return tuple(service for service in plan.services if old_services.get(service) != new_services.get(service))

    @staticmethod
    def _runtime_services(plan: HostPlan) -> tuple[str, ...]:
        return tuple(service for service in plan.services if service not in plan.one_shot.services)

    def _current_config_revision(self) -> str | None:
        path = self.base / "active-config-revision"
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return None
        return value if isinstance(value, str) and REVISION_RE.fullmatch(value) else None

    def _publish_config_revision(self, revision: str | None) -> None:
        path = self.base / "active-config-revision"
        if revision is None:
            with contextlib.suppress(FileNotFoundError):
                path.unlink()
            return
        atomic_write_json(path, revision)

    def apply(
        self,
        deployment_id: str,
        release_id: str,
        config_revision: str,
        platform_commit: str,
        previous_cloud: str | None,
        previous_paid: str | None,
    ) -> str:
        self._validate_inputs(deployment_id, release_id, config_revision, platform_commit)
        with DeploymentLock(self.base / "deploy.lock"):
            previous = self.current_release()
            previous_config_revision = self._current_config_revision()
            expected_previous = previous_cloud if self.role == "cloud" else previous_paid
            require(expected_previous == previous, f"recorded previous {self.role} release is stale")
            record = self._record(
                deployment_id,
                release_id,
                config_revision,
                platform_commit,
                previous_cloud,
                previous_paid,
            )
            mutated = False
            migration_ran = False
            migration_safe = False
            plan: HostPlan | None = None
            try:
                mutated = False
                record.transition("PREFLIGHT")
                plan, candidate_model = self.plan(release_id)
                record.transition("READY")
                if previous == release_id and previous_config_revision == config_revision:
                    record.transition("HEALTH_CHECKING")
                    self.adapter.health(self.base, self.base / "releases" / release_id, plan, self._runtime_services(plan))
                    record.transition("SUCCESS")
                    return "IDEMPOTENT"
                changed = set(self._delta(previous, release_id, plan, candidate_model))
                if previous_config_revision != config_revision:
                    changed.update(plan.config_sensitive_services)
                delta = tuple(service for service in plan.services if service in changed)
                one_shots = tuple(service for service in plan.one_shot.services if service in delta)
                runtime_delta = tuple(service for service in delta if service not in plan.one_shot.services)
                if one_shots:
                    migration_safe = self._migration_rollback_safe(plan, previous, release_id)
                    require(migration_safe, "rollback safety cannot be established for one-shot migration")
                    record.transition("MIGRATING")
                    migration_ran = True
                    self.adapter.run_migrations(self.base, self.base / "releases" / release_id, plan, one_shots)
                record.transition("ACTIVATING")
                self.pointer.activate(self.base, release_id)
                mutated = True
                self.adapter.materialize(self.role)
                self.adapter.apply_services(self.base, self.base / "releases" / release_id, plan, runtime_delta)
                record.transition("HEALTH_CHECKING")
                self.adapter.health(self.base, self.base / "releases" / release_id, plan, self._runtime_services(plan))
                require(self.current_release() == release_id, "active pointer changed during health validation")
                self._publish_config_revision(config_revision)
                record.transition("SUCCESS")
                return "APPLIED"
            except Exception as exc:
                safe_message = type(exc).__name__ + ": deployment step failed"
                if not mutated:
                    if record.value["state"] not in {"FAILED", "MANUAL_RECOVERY_REQUIRED"}:
                        if migration_ran:
                            record.transition("MANUAL_RECOVERY_REQUIRED", failure=safe_message)
                        else:
                            record.transition("FAILED", failure=safe_message)
                    raise
                if plan is None or migration_ran and not migration_safe:
                    record.transition("MANUAL_RECOVERY_REQUIRED", failure=safe_message)
                    raise
                try:
                    record.transition("ROLLING_BACK", failure=safe_message)
                    self.pointer.activate(self.base, previous)
                    self.adapter.materialize(self.role)
                    self.adapter.apply_services(self.base, self.base / "releases" / previous, plan, runtime_delta)
                    self.adapter.health(self.base, self.base / "releases" / previous, plan, self._runtime_services(plan))
                    require(self.current_release() == previous, "rollback active pointer mismatch")
                    self._publish_config_revision(previous_config_revision)
                    record.transition("ROLLED_BACK", rollback_result="SUCCESS")
                except Exception as rollback_exc:
                    if record.value["state"] == "ROLLING_BACK":
                        record.transition("ROLLBACK_FAILED", rollback_result=type(rollback_exc).__name__)
                    raise InvariantError("deployment failed and rollback failed") from rollback_exc
                raise

    def rollback(
        self,
        deployment_id: str,
        target_release: str,
        config_revision: str,
        platform_commit: str,
        previous_cloud: str | None,
        previous_paid: str | None,
    ) -> str:
        self._validate_inputs(deployment_id, target_release, config_revision, platform_commit)
        with DeploymentLock(self.base / "deploy.lock"):
            current = self.current_release()
            previous_config_revision = self._current_config_revision()
            record = self._record(
                deployment_id,
                target_release,
                config_revision,
                platform_commit,
                previous_cloud,
                previous_paid,
            )
            try:
                record.transition("PREFLIGHT")
                plan, target_model = self.plan(target_release)
                record.transition("READY")
                if current == target_release and previous_config_revision == config_revision:
                    record.transition("HEALTH_CHECKING")
                    self.adapter.health(self.base, self.base / "releases" / target_release, plan, self._runtime_services(plan))
                    record.transition("SUCCESS")
                    return "IDEMPOTENT"
                changed = set(self._delta(current, target_release, plan, target_model))
                if previous_config_revision != config_revision:
                    changed.update(plan.config_sensitive_services)
                delta = tuple(service for service in plan.services if service in changed)
                runtime_delta = tuple(service for service in delta if service not in plan.one_shot.services)
                record.transition("ACTIVATING")
                self.pointer.activate(self.base, target_release)
                mutated = True
                self.adapter.materialize(self.role)
                self.adapter.apply_services(self.base, self.base / "releases" / target_release, plan, runtime_delta)
                record.transition("HEALTH_CHECKING")
                self.adapter.health(self.base, self.base / "releases" / target_release, plan, self._runtime_services(plan))
                require(self.current_release() == target_release, "rollback target is not active")
                self._publish_config_revision(config_revision)
                record.transition("ROLLED_BACK", rollback_result="SUCCESS")
                return "ROLLED_BACK"
            except Exception as exc:
                safe = type(exc).__name__ + ": rollback step failed"
                if not mutated:
                    if record.value["state"] not in {"FAILED", "ROLLBACK_FAILED", "MANUAL_RECOVERY_REQUIRED"}:
                        record.transition("FAILED", failure=safe, rollback_result="FAILED")
                    raise
                try:
                    record.transition("ROLLING_BACK", failure=safe)
                    self.pointer.activate(self.base, current)
                    self.adapter.materialize(self.role)
                    self.adapter.apply_services(self.base, self.base / "releases" / current, plan, runtime_delta)
                    self.adapter.health(self.base, self.base / "releases" / current, plan, self._runtime_services(plan))
                    self._publish_config_revision(previous_config_revision)
                finally:
                    if record.value["state"] == "ROLLING_BACK":
                        record.transition("ROLLBACK_FAILED", rollback_result="FAILED")
                raise


def rooted(root: Path, absolute: str) -> Path:
    return root / absolute.lstrip("/")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("plan", "apply", "rollback", "health"))
    parser.add_argument("role", choices=("cloud", "paid"))
    parser.add_argument("release_id")
    parser.add_argument("--deployment-id", default="dep-" + uuid.uuid4().hex)
    parser.add_argument("--environment-config-revision", default="unknown")
    parser.add_argument("--platform-commit", default="0" * 40)
    parser.add_argument("--previous-cloud-release")
    parser.add_argument("--previous-paid-release")
    parser.add_argument("--root", type=Path, default=Path("/"))
    args = parser.parse_args()
    base = rooted(args.root, f"/etc/trinyx/staging/{args.role}")
    engine = HostDeployment(base, args.role, ShellAdapter())
    if args.mode == "plan":
        engine.plan(args.release_id)
        print(f"STAGING_DEPLOY_PLAN_OK role={args.role} release_id={args.release_id} active={engine.current_release()}")
    elif args.mode == "health":
        plan, _ = engine.plan(args.release_id)
        engine.adapter.health(base, base / "releases" / args.release_id, plan, engine._runtime_services(plan))
        require(engine.current_release() == args.release_id, "health target is not active")
        print(f"STAGING_DEPLOY_HEALTH_OK role={args.role} release_id={args.release_id}")
    elif args.mode == "apply":
        result = engine.apply(
            args.deployment_id,
            args.release_id,
            args.environment_config_revision,
            args.platform_commit,
            args.previous_cloud_release,
            args.previous_paid_release,
        )
        print(f"STAGING_DEPLOY_APPLY_OK role={args.role} release_id={args.release_id} result={result}")
    else:
        result = engine.rollback(
            args.deployment_id,
            args.release_id,
            args.environment_config_revision,
            args.platform_commit,
            args.previous_cloud_release,
            args.previous_paid_release,
        )
        print(f"STAGING_DEPLOY_ROLLBACK_OK role={args.role} release_id={args.release_id} result={result}")


if __name__ == "__main__":
    try:
        main()
    except (InvariantError, OSError) as exc:
        print(f"ERROR_DEPLOYMENT_FAILED={type(exc).__name__}", file=sys.stderr)
        raise SystemExit(1)
