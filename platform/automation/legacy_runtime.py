#!/usr/bin/env python3
"""Shared fail-closed Docker Compose runtime identity and mount normalization."""

from __future__ import annotations

import re
from typing import Any

if __package__:
    from .invariants import InvariantError, require
else:
    from invariants import InvariantError, require  # type: ignore


SERVICES = {
    "cloud": {"agent-service", "auth-service", "catalog-service", "conversation-service", "datasource-service",
              "gateway-service", "interface-service", "keycloak", "migration-service", "orchestrator-service",
              "publication-service", "storage-service", "trigger-service", "websearch-service", "cloud-postgres",
              "cloud-redis", "cloud-minio", "cloud-minio-init", "searxng", "cloud-edge"},
    "paid": {"postgres", "redis", "minio", "minio-init", "bridge", "livecontext", "frontend", "paid-edge"},
}
CONTAINER_ID_RE = re.compile(r"^[0-9a-f]{64}$")
CONFIG_HASH_RE = re.compile(r"^[0-9a-f]{64}$")
PROJECT_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")
MOUNT_TYPE_RE = re.compile(r"^[a-z][a-z0-9_-]{0,31}$")
FORBIDDEN_MUTABLE_CHECKOUT = "/srv/trinyx/" + "pr25-"


def normalize_mounts(container: dict[str, Any], *, reject_mutable_checkout: bool = True) -> list[dict[str, Any]]:
    raw_mounts = container.get("Mounts")
    require(isinstance(raw_mounts, list), "invalid Docker mount inventory")
    normalized: list[dict[str, Any]] = []
    for mount in raw_mounts:
        require(isinstance(mount, dict), "invalid Docker mount")
        mount_type = str(mount.get("Type", ""))
        source = str(mount.get("Source", ""))
        destination = str(mount.get("Destination", ""))
        read_write = mount.get("RW")
        require(MOUNT_TYPE_RE.fullmatch(mount_type) is not None, "invalid Docker mount type")
        require(len(source) <= 4096 and "\x00" not in source and "\n" not in source, "invalid Docker mount source")
        require(destination.startswith("/") and len(destination) <= 4096, "invalid Docker mount destination")
        require(isinstance(read_write, bool), "invalid Docker mount access mode")
        if reject_mutable_checkout:
            require(FORBIDDEN_MUTABLE_CHECKOUT not in source, "mutable checkout is mounted in legacy runtime")
        normalized.append({
            "type": mount_type,
            "source": source,
            "destination": destination,
            "readOnly": not read_write,
        })
    normalized.sort(key=lambda item: (item["destination"], item["type"], item["source"], item["readOnly"]))
    return normalized


def compose_runtime_state(
    role: str,
    containers: list[dict[str, Any]],
    *,
    reject_mutable_checkout: bool = True,
) -> tuple[str, dict[str, dict[str, Any]]]:
    require(role in SERVICES, "invalid observation role")
    observed: dict[str, dict[str, Any]] = {}
    projects: set[str] = set()
    for container in containers:
        require(isinstance(container, dict), "invalid Docker container inspection")
        config = container.get("Config")
        require(isinstance(config, dict), "invalid Docker container config")
        labels = config.get("Labels") or {}
        require(isinstance(labels, dict), "invalid Docker Compose labels")
        service = labels.get("com.docker.compose.service")
        if service not in SERVICES[role]:
            continue
        require(service not in observed, f"duplicate Docker Compose service: {service}")
        compose_project = str(labels.get("com.docker.compose.project", ""))
        compose_service = str(labels.get("com.docker.compose.service", ""))
        compose_config_hash = str(labels.get("com.docker.compose.config-hash", ""))
        container_id = str(container.get("Id", ""))
        require(CONTAINER_ID_RE.fullmatch(container_id) is not None, "invalid container ID")
        require(PROJECT_RE.fullmatch(compose_project) is not None, "missing/invalid Docker Compose project")
        require(compose_service == service, "Docker Compose service label mismatch")
        require(CONFIG_HASH_RE.fullmatch(compose_config_hash) is not None, "missing/invalid Docker Compose config hash")
        projects.add(compose_project)
        observed[service] = {
            "containerId": container_id,
            "composeProject": compose_project,
            "composeService": compose_service,
            "composeConfigHash": compose_config_hash,
            "mounts": normalize_mounts(container, reject_mutable_checkout=reject_mutable_checkout),
        }
    require(set(observed) == SERVICES[role], "runtime inventory mismatch")
    require(len(projects) == 1, "runtime spans multiple Docker Compose projects")
    return next(iter(projects)), {name: observed[name] for name in sorted(observed)}
