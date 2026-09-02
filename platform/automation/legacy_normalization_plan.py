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
    from .invariants import (
        DIGEST_RE,
        SHA_RE,
        InvariantError,
        RELEASE_RE,
        canonical_json,
        environment_config_digest,
        require,
        sha256_bytes,
        validate_release_directory,
    )
    from .legacy_runtime import FORBIDDEN_MUTABLE_CHECKOUT, SERVICES, compose_runtime_state
else:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from deploy_engine import ShellAdapter, load_host_plan  # type: ignore
    from invariants import (  # type: ignore
        DIGEST_RE,
        SHA_RE,
        InvariantError,
        RELEASE_RE,
        canonical_json,
        environment_config_digest,
        require,
        sha256_bytes,
        validate_release_directory,
    )
    from legacy_runtime import FORBIDDEN_MUTABLE_CHECKOUT, SERVICES, compose_runtime_state  # type: ignore

IMAGE_RE = re.compile(r"^[^@\s]+@sha256:[0-9a-f]{64}$")
IMAGE_OBJECT_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
DEPLOYMENT_RE = re.compile(r"^dep-[0-9a-f]{32}$")
CONFIG_REVISION_RE = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
COMPOSE_VERSION_RE = re.compile(r"^v?[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$")
SSM_STDOUT_MAX_BYTES = 20_000
COMPOSE_HASH_MISMATCH_LIMIT = 3


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
    *,
    bundle_digest: str,
    deployment_id: str,
    environment_config_revision: str,
    environment_config_digest_value: str,
    control_plane_commit: str,
) -> dict[str, Any]:
    require(role in SERVICES, "invalid normalization role")
    require(RELEASE_RE.fullmatch(baseline_release_id) is not None, "invalid baseline release")
    require(DIGEST_RE.fullmatch(bundle_digest) is not None, "invalid normalization bundle digest")
    require(DEPLOYMENT_RE.fullmatch(deployment_id) is not None, "invalid normalization deployment ID")
    require(CONFIG_REVISION_RE.fullmatch(environment_config_revision) is not None,
            "invalid normalization environment config revision")
    require(DIGEST_RE.fullmatch(environment_config_digest_value) is not None,
            "invalid normalization environment config digest")
    require(SHA_RE.fullmatch(control_plane_commit) is not None,
            "invalid normalization control-plane commit")
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
        current_configured_image = str(config.get("Image", ""))
        current_image_object_id = str(container.get("Image", ""))
        service_model = models[service]
        require(isinstance(service_model, dict), "invalid rendered Compose service")
        expected_image = str(service_model.get("image", ""))
        require(IMAGE_RE.fullmatch(current_configured_image) is not None,
                "legacy configured image is not digest-only")
        require(IMAGE_OBJECT_RE.fullmatch(current_image_object_id) is not None,
                "legacy Docker image object ID is invalid")
        require(IMAGE_RE.fullmatch(expected_image) is not None, "baseline image is not digest-only")
        current_mounts = state["mounts"]
        wanted_mounts = expected_mounts(service_model)
        mutable_checkout = any(FORBIDDEN_MUTABLE_CHECKOUT in item["source"] for item in current_mounts)
        current_hash = state["composeConfigHash"]
        expected_hash = expected_hashes[service]
        reasons: list[str] = []
        if current_configured_image != expected_image:
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
            "currentConfiguredImage": current_configured_image,
            "currentImageObjectId": current_image_object_id,
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
    mismatch_count = service_count - hash_matches
    recreate = [name for name, item in services.items() if item["recreateRequired"]]
    return {
        "schemaVersion": 2,
        "environment": "staging",
        "role": role,
        "baselineReleaseId": baseline_release_id,
        "bundleDigest": bundle_digest,
        "deploymentId": deployment_id,
        "environmentConfigRevision": environment_config_revision,
        "environmentConfigDigest": environment_config_digest_value,
        "controlPlaneCommit": control_plane_commit,
        "observedAt": observed_at,
        "composeProject": compose_project,
        "composeVersion": version,
        "composeHashCapability": "SUPPORTED",
        "composeHashCompatibility": (
            "QUALIFIED_REVIEW"
            if mismatch_count <= COMPOSE_HASH_MISMATCH_LIMIT
            else "UNQUALIFIED_EXCESSIVE_DRIFT"
        ),
        "composeHashCalibrationMatches": hash_matches,
        "composeHashCalibrationTotal": service_count,
        "composeHashMismatchCount": mismatch_count,
        "composeHashMismatchLimit": COMPOSE_HASH_MISMATCH_LIMIT,
        "imageCompatibility": "MATCHED" if image_matches == service_count else "MISMATCH",
        "serviceCount": service_count,
        "recreateServices": recreate,
        "services": services,
    }


def _image_digest(image_ref: str) -> str:
    require(IMAGE_RE.fullmatch(image_ref) is not None, "invalid configured image in normalization protocol")
    return "sha256:" + image_ref.rsplit("@sha256:", 1)[1]


def render_ssm_protocol(record: dict[str, Any]) -> str:
    """Render a bounded, marker-last protocol safe for SSM's 24k stdout field."""
    require(record.get("schemaVersion") == 2, "unsupported normalization report")
    role = str(record["role"])
    release_id = str(record["baselineReleaseId"])
    header = (
        "LEGACY_NORMALIZATION_REPORT_V1 "
        f"role={role} release_id={release_id} bundle_digest={record['bundleDigest']} "
        f"deployment_id={record['deploymentId']} config_revision={record['environmentConfigRevision']} "
        f"config_digest={record['environmentConfigDigest']} "
        f"control_plane_commit={record['controlPlaneCommit']} observed_at={record['observedAt']} "
        f"compose_version={record['composeVersion']} service_count={record['serviceCount']} "
        f"hash_matches={record['composeHashCalibrationMatches']} "
        f"hash_mismatches={record['composeHashMismatchCount']} "
        f"hash_limit={record['composeHashMismatchLimit']}"
    )
    lines = [header]
    services = record["services"]
    require(isinstance(services, dict), "normalization services are invalid")
    for service in sorted(services):
        item = services[service]
        reasons = ",".join(item["reasons"]) or "none"
        lines.append(
            "NORMALIZATION "
            f"role={role} service={service} "
            f"recreate={'yes' if item['recreateRequired'] else 'no'} reasons={reasons} "
            f"image_match={'yes' if item['currentConfiguredImage'] == item['expectedImageDigest'] else 'no'} "
            f"container_id={item['currentContainerId']} image_object={item['currentImageObjectId']} "
            f"configured_image_digest={_image_digest(item['currentConfiguredImage'])} "
            f"expected_image_digest={_image_digest(item['expectedImageDigest'])} "
            f"current_config_hash={item['currentComposeConfigHash']} "
            f"expected_config_hash={item['expectedComposeConfigHash']} "
            f"current_bind_mounts_sha256={sha256_bytes(canonical_json(bind_mounts(item['currentMounts'])))} "
            f"expected_bind_mounts_sha256={sha256_bytes(canonical_json(bind_mounts(item['expectedMounts'])))} "
            f"mutable_checkout={'yes' if item['mutableCheckoutMounted'] else 'no'}"
        )
    payload = "\n".join(lines) + "\n"
    report_sha = sha256_bytes(payload.encode("utf-8"))
    compatibility = (
        "review"
        if record["composeHashCompatibility"] == "QUALIFIED_REVIEW"
        else "stop"
    )
    images = "matched" if record["imageCompatibility"] == "MATCHED" else "mismatch"
    marker = (
        f"LEGACY_NORMALIZATION_PLAN_COMPLETE role={role} release_id={release_id} "
        f"services={record['serviceCount']} recreate_count={len(record['recreateServices'])} "
        f"compose_version={record['composeVersion']} compatibility={compatibility} "
        f"images={images} report_sha256={report_sha}"
    )
    output = payload + marker + "\n"
    require(
        len(output.encode("utf-8")) < SSM_STDOUT_MAX_BYTES,
        "normalization protocol exceeds bounded SSM stdout budget",
    )
    return output


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
    parser.add_argument("--expected-bundle-digest", required=True)
    parser.add_argument("--deployment-id", required=True)
    parser.add_argument("--environment-config-revision", required=True)
    parser.add_argument("--control-plane-commit", required=True)
    parser.add_argument("--base", type=Path)
    args = parser.parse_args()
    require(RELEASE_RE.fullmatch(args.baseline_release) is not None, "invalid baseline release")
    require(DIGEST_RE.fullmatch(args.expected_bundle_digest) is not None, "invalid expected bundle digest")
    base = args.base or Path(f"/etc/trinyx/staging/{args.role}")
    release_dir = base / "releases" / args.baseline_release
    manifest = validate_release_directory(release_dir, args.role)
    require(
        manifest["deploymentBundle"]["digest"] == args.expected_bundle_digest,
        "installed normalization baseline bundle digest differs from dispatch contract",
    )
    config_digest = environment_config_digest(base, args.role)
    plan = load_host_plan(base / "config" / "deployment-plan.json", args.role)
    adapter = ShellAdapter()
    # Do not call materialize(): this is a read-only observation of the
    # already reconciled/materialized host state.
    rendered_model = adapter.render_model(base, release_dir, plan)
    expected_hashes = adapter.compose_config_hashes(base, release_dir, plan)
    record = build_normalization_plan(
        args.role,
        args.baseline_release,
        inspect_containers(),
        rendered_model,
        expected_hashes,
        compose_version(),
        dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        bundle_digest=args.expected_bundle_digest,
        deployment_id=args.deployment_id,
        environment_config_revision=args.environment_config_revision,
        environment_config_digest_value=config_digest,
        control_plane_commit=args.control_plane_commit,
    )
    print(render_ssm_protocol(record), end="")


if __name__ == "__main__":
    try:
        main()
    except InvariantError as exc:
        print(f"LEGACY_NORMALIZATION_PLAN_FAILED={type(exc).__name__}", file=sys.stderr)
        raise SystemExit(1)
