#!/usr/bin/env python3
"""Fail-closed validation for the one metadata-only historical baseline import."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

REPOSITORY = "TrinyxAI/Trinyx"
REPOSITORY_ID = 1342032975
OWNER_ID = 319253481
SOURCE_COMMIT = "aeb2a447ea7ce0436a60549713636225dfe1a2c1"
BACKEND_RUN_ID = 33444272417
FRONTEND_RUN_ID = 33444302902
CLOUD_ARTIFACT_ID = 9777989306
CLOUD_ARTIFACT_NAME = f"trinyx-cloud-image-manifest-{SOURCE_COMMIT}"
CLOUD_ARTIFACT_DIGEST = "sha256:8cb6a3df3aab35cd54db2d5760c785c7dd862229d1334be75eeff5b41bf52287"
BACKEND_WORKFLOW = ".github/workflows/build-trinyx-backend.yml"
CLOUD_WORKFLOW = f"{REPOSITORY}/.github/workflows/build-trinyx-cloud-images.yml@{SOURCE_COMMIT}"
FRONTEND_WORKFLOW = ".github/workflows/build-trinyx-frontend.yml"
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def load(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"cannot read JSON: {path}") from exc


def validate_run(run: Any, *, run_id: int, workflow: str, cloud_reusable: bool) -> None:
    require(isinstance(run, dict), "historical run is not an object")
    repository = run.get("repository")
    head_repository = run.get("head_repository")
    require(run.get("id") == run_id, "historical run ID mismatch")
    require(run.get("head_sha") == SOURCE_COMMIT, "historical run source mismatch")
    require(run.get("conclusion") == "success", "historical run was not successful")
    require(run.get("path") == workflow, "historical workflow path mismatch")
    require(
        isinstance(repository, dict)
        and repository.get("id") == REPOSITORY_ID
        and repository.get("full_name") == REPOSITORY,
        "historical run repository mismatch",
    )
    require(
        isinstance(repository.get("owner"), dict)
        and repository["owner"].get("id") == OWNER_ID,
        "historical run repository owner mismatch",
    )
    require(
        isinstance(head_repository, dict)
        and head_repository.get("id") == REPOSITORY_ID
        and head_repository.get("full_name") == REPOSITORY,
        "historical run head repository mismatch",
    )
    require(
        isinstance(head_repository.get("owner"), dict)
        and head_repository["owner"].get("id") == OWNER_ID,
        "historical run head repository owner mismatch",
    )
    referenced = run.get("referenced_workflows")
    require(isinstance(referenced, list), "historical referenced workflows missing")
    if cloud_reusable:
        require(len(referenced) == 1, "historical Cloud reusable workflow cardinality mismatch")
        item = referenced[0]
        require(
            isinstance(item, dict)
            and item.get("path") == CLOUD_WORKFLOW
            and item.get("sha") == SOURCE_COMMIT,
            "historical Cloud reusable workflow mismatch",
        )
    else:
        require(referenced == [], "unexpected historical frontend reusable workflow")


def validate_artifact(artifact: Any) -> None:
    require(isinstance(artifact, dict), "historical artifact is not an object")
    workflow_run = artifact.get("workflow_run")
    require(artifact.get("id") == CLOUD_ARTIFACT_ID, "historical artifact ID mismatch")
    require(artifact.get("name") == CLOUD_ARTIFACT_NAME, "historical artifact name mismatch")
    require(artifact.get("expired") is False, "historical artifact is expired")
    require(artifact.get("digest") == CLOUD_ARTIFACT_DIGEST,
            "historical artifact digest mismatch")
    require(
        isinstance(workflow_run, dict)
        and workflow_run.get("id") == BACKEND_RUN_ID
        and workflow_run.get("repository_id") == REPOSITORY_ID
        and workflow_run.get("head_repository_id") == REPOSITORY_ID
        and workflow_run.get("head_sha") == SOURCE_COMMIT,
        "historical artifact/run binding mismatch",
    )


def validate_cloud_manifest(document: Any, inventory: Any) -> None:
    require(isinstance(document, dict), "Cloud manifest is not an object")
    require(document.get("schemaVersion") == 1, "Cloud manifest schema mismatch")
    require(document.get("commit") == SOURCE_COMMIT, "Cloud manifest source mismatch")
    images = document.get("images")
    require(isinstance(images, list) and len(images) == 14, "Cloud manifest cardinality mismatch")
    require(isinstance(inventory, dict) and isinstance(inventory.get("images"), list),
            "runtime inventory is invalid")
    expected = {
        item["name"] for item in inventory["images"]
        if isinstance(item, dict) and item.get("role") == "cloud"
        and str(item.get("name", "")).startswith("cloud-")
        and item.get("name") not in {
            "cloud-postgres", "cloud-redis", "cloud-minio", "cloud-minio-init",
            "cloud-searxng", "cloud-edge",
        }
    }
    names = [item.get("name") for item in images if isinstance(item, dict)]
    require(len(names) == 14 and set(names) == expected, "Cloud manifest service inventory mismatch")


def paid_manifest(document: Any, *, name: str, service: str, environment: str,
                  package: str) -> dict[str, Any]:
    require(isinstance(document, dict) and set(document) == {"package", "tag", "digest", "labels"},
            "Paid image inspection schema mismatch")
    require(document["package"] == package, "Paid image package mismatch")
    require(document["tag"] == f"{package}:{SOURCE_COMMIT}", "Paid immutable tag mismatch")
    digest = str(document["digest"])
    require(DIGEST_RE.fullmatch(digest) is not None, "Paid image digest mismatch")
    labels = document["labels"]
    require(
        isinstance(labels, dict)
        and labels.get("org.opencontainers.image.source") == f"https://github.com/{REPOSITORY}"
        and labels.get("org.opencontainers.image.revision") == SOURCE_COMMIT,
        "Paid image OCI provenance mismatch",
    )
    return {
        "schemaVersion": 1,
        "commit": SOURCE_COMMIT,
        "images": [{
            "name": name,
            "service": service,
            "package": package,
            "environment": environment,
            "digest": digest,
            "immutableRef": f"{package}@{digest}",
        }],
    }


def write_canonical(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--backend-run", type=Path, required=True)
    parser.add_argument("--frontend-run", type=Path, required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--cloud-manifest", type=Path, required=True)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--backend-inspect", type=Path, required=True)
    parser.add_argument("--frontend-inspect", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    validate_run(load(args.backend_run), run_id=BACKEND_RUN_ID,
                 workflow=BACKEND_WORKFLOW, cloud_reusable=True)
    validate_run(load(args.frontend_run), run_id=FRONTEND_RUN_ID,
                 workflow=FRONTEND_WORKFLOW, cloud_reusable=False)
    validate_artifact(load(args.artifact))
    validate_cloud_manifest(load(args.cloud_manifest), load(args.inventory))
    write_canonical(args.out / "backend.json", paid_manifest(
        load(args.backend_inspect), name="paid-backend", service="livecontext",
        environment="BACKEND_IMAGE", package="ghcr.io/trinyxai/trinyx-backend",
    ))
    write_canonical(args.out / "frontend.json", paid_manifest(
        load(args.frontend_inspect), name="paid-frontend", service="frontend",
        environment="FRONTEND_IMAGE", package="ghcr.io/trinyxai/trinyx-frontend",
    ))
    print(f"HISTORICAL_BASELINE_PROVENANCE_OK source_commit={SOURCE_COMMIT}")


if __name__ == "__main__":
    try:
        main()
    except ValueError as exc:
        raise SystemExit(f"ERROR_HISTORICAL_BASELINE={exc}") from exc
