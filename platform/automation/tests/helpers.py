from __future__ import annotations

import io
import json
import os
import sys
import tarfile
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from invariants import calculated_release_id, canonical_json, sha256_bytes


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(canonical_json(value) + b"\n")


def image_inventory(version: int) -> list[dict[str, str]]:
    images: list[dict[str, str]] = []
    for role, count in (("cloud", 20), ("paid", 8)):
        for index in range(count):
            if index == count - 1:
                name = f"{role}-edge"
                package = "docker.io/library/caddy"
            else:
                name = "livecontext" if role == "paid" and index == 0 else f"{role}-{index:02d}"
                package = f"ghcr.io/trinyxai/{name}"
            digest = "sha256:" + f"{version + index + (0 if role == 'cloud' else 100):064x}"
            images.append(
                {
                    "name": name,
                    "role": role,
                    "service": name,
                    "package": package,
                    "environment": f"{role.upper()}_IMAGE_{index:02d}",
                    "digest": digest,
                    "immutableRef": package + "@" + digest,
                }
            )
    return sorted(images, key=lambda item: item["name"])


def make_release(base: Path, role: str, version: int) -> tuple[str, Path, dict[str, Any]]:
    bundle_files = {
        "compose.yml": (
            "services:\n"
            "  placeholder:\n"
            "    image: ghcr.io/trinyxai/placeholder@sha256:" + f"{version:064x}" + "\n"
        ).encode(),
        "metadata.txt": f"fixture={version}\n".encode(),
    }
    tar_buffer = io.BytesIO()
    entries: list[dict[str, Any]] = []
    with tarfile.open(fileobj=tar_buffer, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for name in sorted(bundle_files):
            content = bundle_files[name]
            info = tarfile.TarInfo(name)
            info.size = len(content)
            info.mode = 0o444
            info.mtime = 0
            archive.addfile(info, io.BytesIO(content))
            entries.append({"path": name, "digest": sha256_bytes(content), "sizeBytes": len(content), "mode": 0o444})
    tar_bytes = tar_buffer.getvalue()
    bundle_manifest = {
        "schemaVersion": 1,
        "format": "tar",
        "digest": sha256_bytes(tar_bytes),
        "sizeBytes": len(tar_bytes),
        "files": entries,
    }
    manifest: dict[str, Any] = {
        "schemaVersion": 1,
        "releaseId": "",
        "sourceCommit": f"{version:040x}",
        "sourceRef": "refs/heads/codex/platform-release-automation",
        "platformCommit": f"{version + 1:040x}",
        "createdAt": "2026-09-01T00:00:00Z",
        "deploymentBundle": {
            "format": "tar",
            "digest": bundle_manifest["digest"],
            "sizeBytes": bundle_manifest["sizeBytes"],
            "fileCount": len(entries),
        },
        "images": image_inventory(version),
    }
    manifest["releaseId"] = calculated_release_id(manifest)
    release_dir = base / "releases" / manifest["releaseId"]
    release_dir.mkdir(parents=True)
    write_json(release_dir / "manifest.json", manifest)
    write_json(release_dir / "deployment-bundle.json", bundle_manifest)
    (release_dir / "deployment-bundle.tar").write_bytes(tar_bytes)
    for name, content in bundle_files.items():
        target = release_dir / "bundle" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    selected = [image for image in manifest["images"] if image["role"] == role]
    (release_dir / "images.env").write_text(
        "".join(f"{item['environment']}={item['immutableRef']}\n" for item in sorted(selected, key=lambda x: x["environment"])),
        encoding="utf-8",
    )
    return manifest["releaseId"], release_dir, manifest


def make_compose_model(manifest: dict[str, Any], role: str) -> dict[str, Any]:
    services: dict[str, Any] = {}
    for image in manifest["images"]:
        if image["role"] == role:
            services[image["service"]] = {"image": image["immutableRef"]}
    if role == "paid":
        services["livecontext"].update(
            {
                "mem_limit": 3 * 1024**3,
                "healthcheck": {"test": ["CMD", "wget", "http://localhost:8080/actuator/health/liveness"]},
            }
        )
    return {"services": services}


def make_host(
    base: Path,
    role: str,
    versions: tuple[int, ...] = (1, 2),
    create_active: bool = True,
) -> tuple[list[str], dict[str, dict[str, Any]]]:
    release_ids: list[str] = []
    models: dict[str, dict[str, Any]] = {}
    for version in versions:
        release_id, _, manifest = make_release(base, role, version)
        release_ids.append(release_id)
        models[release_id] = make_compose_model(manifest, role)
    if create_active:
        os.symlink(f"releases/{release_ids[0]}", base / "active", target_is_directory=True)
    config = base / "config"
    config.mkdir(parents=True)
    (config / "compose.env").write_text("ENVIRONMENT=staging\n", encoding="utf-8")
    (config / "ca.pem").write_text("fixture-ca\n", encoding="utf-8")
    (config / "secret-names.present").write_text("REQUIRED_SECRET\n", encoding="utf-8")
    plan = {
        "schemaVersion": 1,
        "role": role,
        "composeFiles": ["release/compose.yml"],
        "services": sorted(models[release_ids[0]]["services"]),
        "configSensitiveServices": sorted(models[release_ids[0]]["services"]),
        "requiredFiles": ["config/compose.env"],
        "tlsFiles": ["config/ca.pem"],
        "requiredSecretNames": ["REQUIRED_SECRET"],
        "health": [
            {
                "name": "liveness",
                "argv": ["curl", "--fail", "--cacert", "/etc/trinyx/tls/ca.pem", "https://service/liveness"],
                "timeoutSeconds": 10,
            }
        ],
        "minFreeBytes": 0,
        "maxRestartCount": 3,
        "oneShot": {"services": [], "rollbackSafe": True},
    }
    write_json(config / "deployment-plan.json", plan)
    return release_ids, models
