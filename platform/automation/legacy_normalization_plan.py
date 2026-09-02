#!/usr/bin/env python3
"""Read-only plan for removing legacy runtime drift before baseline adoption."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

if __package__:
    from .deploy_engine import ShellAdapter, load_host_plan
    from .invariants import InvariantError, RELEASE_RE, require, validate_release_directory
    from .legacy_runtime import FORBIDDEN_MUTABLE_CHECKOUT, SERVICES, compose_runtime_state
else:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from deploy_engine import ShellAdapter, load_host_plan  # type: ignore
    from invariants import InvariantError, RELEASE_RE, require, validate_release_directory  # type: ignore
    from legacy_runtime import FORBIDDEN_MUTABLE_CHECKOUT, SERVICES, compose_runtime_state  # type: ignore

IMAGE_RE = re.compile(r"^[^@\s]+@sha256:[0-9a-f]{64}$")
COMPOSE_VERSION_RE = re.compile(r"^v?[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$")


def docker_json(argv: list[str], timeout: int = 60) -> Any:
    try:
        result = subprocess.run(
            argv, check=True, text=True, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, timeout=timeout,
        )
        return json.loads(result.stdout)
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired, json.JSONDecodeError) as exc:
        raise InvariantError("legacy normalization Docker inspection failed") from exc


def compose_version() -> str:
    try:
        result = subprocess.run(
            ["docker", "compose", "version", "--short"],
            check=True, text=True, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, timeout=20,
        )
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        raise InvariantError("Docker Compose version capability check failed") from exc
    version = result.stdout.strip()
    require(COMPOSE_VERSION_RE.fullmatch(version) is not None, "unrecognized Docker Compose version")
    return version


def expected_mounts(service_model: dict[str, Any]) -> list[dict[str, Any]]:
    volumes = service_model.get("volumes") or []
    require(isinstance(volumes, list), "invalid rendered Compose mount inventory")
    normalized: list[dict[str, Any]] = []
    for volume in volumes:
        require(isinstance(volume, dict), "rendered Compose mount is not normalized")
        mount_type = str(volume.get("type", ""))
        source = str(volume.get("source", ""))
        destination = str(volume.get("target", ""))
        read_only = volume.get("read_only", False)
        require(mount_type and destination.startswith("/") and isinstance(read_only, bool),
                "invalid rendered Compose mount")
        normalized.append({
            "type": mount_type,
            "source": source,
            "destination": destination,
            "readOnly": read_only,
        })
    normalized.sort(key=lambda item: (item["destination"], item["type"], item["source"], item["readOnly"]))
    return normalized


def bind_mounts(mounts: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [item for item in mounts if item["type"] == "bind"]


def build_normalization_plan(
    role: str,
    baseline_release_id: str,
    containers: list[dict[str, Any]],
    rendered_model: dict[str, Any],
    expected_hashes: dict[str, str],
    version: str,
    observed_at: str,
) -> dict[str, Any]:
    require(role in SERVICES, "invalid normalization role")
    require(RELEASE_RE.fullmatch(baseline_release_id) is not None, "invalid baseline release")
    require(COMPOSE_VERSION_RE.fullmatch(version) is not None, "unrecognized Docker Compose version")
    compose_project, runtime = compose_runtime_state(
        role, containers, reject_mutable_checkout=False,
    )
    require(set(expected_hashes) == SERVICES[role], "expected Compose config-hash inventory mismatch")
    models = rendered_model.get("services")
    require(isinstance(models, dict) and set(SERVICES[role]).issubset(models),
            "rendered Compose service inventory mismatch")
    containers_by_id = {
        str(item.get("Id", "")): item for item in containers if isinstance(item, dict)
    }

    services: dict[str, dict[str, Any]] = {}
    hash_matches = 0
    image_matches = 0
    for service in sorted(SERVICES[role]):
        state = runtime[service]
        container = containers_by_id[state["containerId"]]
        config = container.get("Config")
        require(isinstance(config, dict), "invalid container config")
        current_image = str(config.get("Image", ""))
        service_model = models[service]
        require(isinstance(service_model, dict), "invalid rendered Compose service")
        expected_image = str(service_model.get("image", ""))
        require(IMAGE_RE.fullmatch(current_image) is not None, "legacy image is not digest-only")
        require(IMAGE_RE.fullmatch(expected_image) is not None, "baseline image is not digest-only")
        current_mounts = state["mounts"]
        wanted_mounts = expected_mounts(service_model)
        mutable_checkout = any(FORBIDDEN_MUTABLE_CHECKOUT in item["source"] for item in current_mounts)
        current_hash = state["composeConfigHash"]
        expected_hash = expected_hashes[service]
        reasons: list[str] = []
        if current_image != expected_image:
            reasons.append("IMAGE_DIGEST_MISMATCH")
        else:
            image_matches += 1
        if current_hash != expected_hash:
            reasons.append("COMPOSE_CONFIG_HASH_MISMATCH")
        else:
            hash_matches += 1
        if bind_mounts(current_mounts) != bind_mounts(wanted_mounts):
            reasons.append("BIND_MOUNT_MISMATCH")
        if mutable_checkout:
            reasons.append("MUTABLE_CHECKOUT_MOUNT")
        services[service] = {
            "currentContainerId": state["containerId"],
            "currentImageDigest": current_image,
            "expectedImageDigest": expected_image,
            "currentComposeConfigHash": current_hash,
            "expectedComposeConfigHash": expected_hash,
            "currentMounts": current_mounts,
            "expectedMounts": wanted_mounts,
            "mutableCheckoutMounted": mutable_checkout,
            "recreateRequired": bool(reasons),
            "reasons": reasons,
        }

    service_count = len(SERVICES[role])
    recreate = [name for name, item in services.items() if item["recreateRequired"]]
    return {
        "schemaVersion": 1,
        "environment": "staging",
        "role": role,
        "baselineReleaseId": baseline_release_id,
        "observedAt": observed_at,
        "composeProject": compose_project,
        "composeVersion": version,
        "composeHashCapability": "SUPPORTED",
        "composeHashCompatibility": "QUALIFIED" if hash_matches else "UNQUALIFIED_ALL_SERVICES_DIFFER",
        "imageCompatibility": "MATCHED" if image_matches == service_count else "MISMATCH",
        "serviceCount": service_count,
        "recreateServices": recreate,
        "services": services,
    }


def inspect_containers() -> list[dict[str, Any]]:
    try:
        result = subprocess.run(
            ["docker", "ps", "-a", "--no-trunc", "--format", "{{.ID}}"],
            check=True, text=True, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, timeout=30,
        )
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        raise InvariantError("legacy normalization Docker inventory failed") from exc
    ids = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    require(ids, "empty Docker container inventory")
    containers = docker_json(["docker", "inspect", *ids])
    require(isinstance(containers, list), "invalid Docker container inspection")
    return containers


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--role", choices=("cloud", "paid"), required=True)
    parser.add_argument("--baseline-release", required=True)
    parser.add_argument("--base", type=Path)
    args = parser.parse_args()
    require(RELEASE_RE.fullmatch(args.baseline_release) is not None, "invalid baseline release")
    base = args.base or Path(f"/etc/trinyx/staging/{args.role}")
    release_dir = base / "releases" / args.baseline_release
    validate_release_directory(release_dir, args.role)
    plan = load_host_plan(base / "config" / "deployment-plan.json", args.role)
    adapter = ShellAdapter()
    # Do not call materialize(): this is a read-only observation of the
    # already reconciled/materialized host state.
    rendered_model = adapter.render_model(base, release_dir, plan)
    expected_hashes = adapter.compose_config_hashes(base, release_dir, plan)
    record = build_normalization_plan(
        args.role, args.baseline_release, inspect_containers(),
        rendered_model, expected_hashes, compose_version(),
        dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
    )
    print(json.dumps(record, sort_keys=True, separators=(",", ":"), ensure_ascii=False))
    recreate = ",".join(record["recreateServices"]) or "none"
    compatibility = "qualified" if record["composeHashCompatibility"] == "QUALIFIED" else "unqualified"
    images = "matched" if record["imageCompatibility"] == "MATCHED" else "mismatch"
    print(
        f"LEGACY_NORMALIZATION_PLAN_COMPLETE role={args.role} "
        f"release_id={args.baseline_release} services={record['serviceCount']} "
        f"recreate={recreate} compose_version={record['composeVersion']} "
        f"compatibility={compatibility} images={images}"
    )


if __name__ == "__main__":
    try:
        main()
    except InvariantError as exc:
        print(f"LEGACY_NORMALIZATION_PLAN_FAILED={type(exc).__name__}", file=sys.stderr)
        raise SystemExit(1)
