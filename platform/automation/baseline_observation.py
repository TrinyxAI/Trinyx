#!/usr/bin/env python3
"""Capture legacy runtime evidence bound to images, effective Compose config and mounts."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path
from typing import Any

if __package__:
    from .deploy_engine import ShellAdapter, load_host_plan
    from .invariants import DIGEST_RE, InvariantError, RELEASE_RE, environment_config_digest, require, validate_release_directory
    from .legacy_runtime import SERVICES, compose_runtime_state
else:
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from deploy_engine import ShellAdapter, load_host_plan  # type: ignore
    from invariants import DIGEST_RE, InvariantError, RELEASE_RE, environment_config_digest, require, validate_release_directory  # type: ignore
    from legacy_runtime import SERVICES, compose_runtime_state  # type: ignore


IMAGE_ID_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
REVISION_RE = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
REPO_DIGEST_RE = re.compile(r"^[^@\s]+@sha256:[0-9a-f]{64}$")


def docker_json(argv: list[str], timeout: int = 30) -> Any:
    try:
        result = subprocess.run(
            argv, check=True, text=True, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, timeout=timeout,
        )
        return json.loads(result.stdout)
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired, json.JSONDecodeError) as exc:
        raise InvariantError("Docker runtime observation failed") from exc


def build_observation(
    role: str,
    containers: list[dict[str, Any]],
    image_inspections: list[dict[str, Any]],
    expected_config_hashes: dict[str, str],
    environment_config_revision: str,
    config_digest: str,
    observed_at: str,
) -> dict[str, Any]:
    require(role in SERVICES, "invalid observation role")
    require(REVISION_RE.fullmatch(environment_config_revision) is not None, "invalid environment config revision")
    require(DIGEST_RE.fullmatch(config_digest) is not None, "invalid environment config digest")
    compose_project, runtime = compose_runtime_state(role, containers)
    require(set(expected_config_hashes) == SERVICES[role], "expected Compose config-hash inventory mismatch")

    images: dict[str, list[str]] = {}
    for image in image_inspections:
        image_id = str(image.get("Id", ""))
        repo_digests = image.get("RepoDigests")
        require(IMAGE_ID_RE.fullmatch(image_id) is not None, "invalid Docker image object ID")
        require(
            isinstance(repo_digests, list)
            and repo_digests
            and all(isinstance(value, str) and REPO_DIGEST_RE.fullmatch(value) for value in repo_digests),
            "Docker image lacks immutable RepoDigests",
        )
        images[image_id] = sorted(set(repo_digests))

    containers_by_id = {str(item.get("Id", "")): item for item in containers if isinstance(item, dict)}
    observed: dict[str, dict[str, Any]] = {}
    for service, state in runtime.items():
        container = containers_by_id[state["containerId"]]
        config = container["Config"]
        image_id = str(container.get("Image", ""))
        configured_image = str(config.get("Image", ""))
        require(IMAGE_ID_RE.fullmatch(image_id) is not None and image_id in images, "unresolved container image object")
        require(REPO_DIGEST_RE.fullmatch(configured_image) is not None, "configured image is not digest-only")
        require(
            state["composeConfigHash"] == expected_config_hashes.get(service),
            "legacy container Compose config hash differs from baseline",
        )
        observed[service] = {
            **state,
            "containerImageId": image_id,
            "configuredImage": configured_image,
            "repoDigests": images[image_id],
        }

    return {
        "schemaVersion": 3,
        "environment": "staging",
        "role": role,
        "observedAt": observed_at,
        "releaseEligible": False,
        "reason": "observation is evidence only and is not a cryptographic release",
        "environmentConfigRevision": environment_config_revision,
        "environmentConfigDigest": config_digest,
        "composeProject": compose_project,
        "services": {name: observed[name] for name in sorted(observed)},
    }


def atomic_write(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    temporary = Path(temporary_name)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            os.chmod(temporary, 0o600)
            json.dump(value, handle, sort_keys=True, separators=(",", ":"))
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        directory_fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        if temporary.exists():
            temporary.unlink()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--role", choices=("cloud", "paid"), required=True)
    parser.add_argument("--baseline-release", required=True)
    parser.add_argument("--environment-config-revision", required=True)
    parser.add_argument("--base", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    require(RELEASE_RE.fullmatch(args.baseline_release) is not None, "invalid baseline release")
    base = args.base or Path(f"/etc/trinyx/staging/{args.role}")
    release_dir = base / "releases" / args.baseline_release
    validate_release_directory(release_dir, args.role)
    plan = load_host_plan(base / "config" / "deployment-plan.json", args.role)
    adapter = ShellAdapter()
    adapter.materialize(args.role)
    expected_config_hashes = adapter.compose_config_hashes(base, release_dir, plan)

    try:
        ids_result = subprocess.run(
            ["docker", "ps", "-a", "--no-trunc", "--format", "{{.ID}}"],
            check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30,
        )
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        raise InvariantError("Docker runtime inventory failed") from exc
    ids = [line.strip() for line in ids_result.stdout.splitlines() if line.strip()]
    require(ids, "empty Docker container inventory")
    containers = docker_json(["docker", "inspect", *ids])
    require(isinstance(containers, list), "invalid Docker container inspection")
    image_ids = sorted({
        str(item.get("Image", ""))
        for item in containers
        if isinstance(item, dict)
        and isinstance(item.get("Config"), dict)
        and (item["Config"].get("Labels") or {}).get("com.docker.compose.service") in SERVICES[args.role]
    })
    require(image_ids and all(IMAGE_ID_RE.fullmatch(value) for value in image_ids), "invalid Docker image inventory")
    image_inspections = docker_json(["docker", "image", "inspect", *image_ids])
    require(isinstance(image_inspections, list), "invalid Docker image inspection")
    record = build_observation(
        args.role,
        containers,
        image_inspections,
        expected_config_hashes,
        args.environment_config_revision,
        environment_config_digest(base, args.role),
        dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
    )
    atomic_write(args.out, record)
    print(
        f"BASELINE_OBSERVATION_OK role={args.role} release_eligible=false "
        f"services={len(record['services'])} image_binding=verified effective_compose_config=verified"
    )


if __name__ == "__main__":
    try:
        main()
    except InvariantError as exc:
        print(f"BASELINE_OBSERVATION_FAILED={type(exc).__name__}", file=__import__("sys").stderr)
        raise SystemExit(1)
