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
SOURCE_BRANCH = "codex/trinyx-cloud-gateway-v2"
SOURCE_REF = f"refs/heads/{SOURCE_BRANCH}"
HISTORICAL_EVENT = "workflow_dispatch"
HISTORICAL_RUN_ATTEMPT = 1
BACKEND_RUN_ID = 33444272417
FRONTEND_RUN_ID = 33444302902
BACKEND_PUBLISH_JOB_ID = 99660712771
FRONTEND_PUBLISH_JOB_ID = 99659777935
CLOUD_ARTIFACT_ID = 9777989306
CLOUD_ARTIFACT_NAME = f"trinyx-cloud-image-manifest-{SOURCE_COMMIT}"
CLOUD_ARTIFACT_DIGEST = "sha256:8cb6a3b52b7deff90bebcceb6435a5c66d6d1a06e45c32b8350427efe4059ac0"
BACKEND_WORKFLOW = ".github/workflows/build-trinyx-backend.yml"
CLOUD_WORKFLOW = f"{REPOSITORY}/.github/workflows/build-trinyx-cloud-images.yml@{SOURCE_COMMIT}"
FRONTEND_WORKFLOW = ".github/workflows/build-trinyx-frontend.yml"
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
ANSI_SGR_RE = re.compile(r"\x1b\[[0-?]*[ -/]*m")
UNSAFE_TERMINAL_CONTROL_RE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f]")


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
    require(run.get("head_branch") == SOURCE_BRANCH, "historical run branch mismatch")
    require(run.get("event") == HISTORICAL_EVENT, "historical run event mismatch")
    require(
        type(run.get("run_attempt")) is int
        and run.get("run_attempt") == HISTORICAL_RUN_ATTEMPT,
        "historical run attempt mismatch",
    )
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


def validate_job(job: Any, *, job_id: int, run_id: int, name: str) -> None:
    require(isinstance(job, dict), "historical publication job is not an object")
    require(job.get("id") == job_id, "historical publication job ID mismatch")
    require(job.get("run_id") == run_id, "historical publication job/run mismatch")
    require(job.get("head_sha") == SOURCE_COMMIT,
            "historical publication job source mismatch")
    require(job.get("conclusion") == "success",
            "historical publication job was not successful")
    require(job.get("name") == name, "historical publication job name mismatch")
    require(
        job.get("run_url") == f"https://api.github.com/repos/{REPOSITORY}/actions/runs/{run_id}",
        "historical publication job repository/run URL mismatch",
    )


def normalize_terminal_log(log_text: str) -> str:
    """Remove terminal color formatting without interpreting terminal control semantics."""
    require(isinstance(log_text, str) and log_text, "historical publication log is empty")
    normalized = log_text.replace("\r\n", "\n").replace("\r", "\n")
    normalized = ANSI_SGR_RE.sub("", normalized)
    require("\x1b" not in normalized, "unsupported terminal escape sequence in historical log")
    require(
        UNSAFE_TERMINAL_CONTROL_RE.search(normalized) is None,
        "unsupported terminal control character in historical log",
    )
    return normalized


def historical_paid_digest(log_text: str, *, package: str) -> str:
    normalized_log = normalize_terminal_log(log_text)
    pattern = re.compile(
        rf"pushing manifest for {re.escape(package)}:{SOURCE_COMMIT}@"
        r"(sha256:[0-9a-f]{64})(?:\s|$)"
    )
    digests = set(pattern.findall(normalized_log))
    require(len(digests) == 1, "historical publication digest evidence is ambiguous or missing")
    return next(iter(digests))


def canonical_cloud_manifest(
    document: Any,
    inventory: Any,
    historical_inventory: Any,
) -> dict[str, Any]:
    require(isinstance(document, dict), "Cloud manifest is not an object")
    require(
        set(document) == {"schemaVersion", "commit", "generatedAt", "images"},
        "Cloud manifest schema mismatch",
    )
    require(document.get("schemaVersion") == 1, "Cloud manifest schema mismatch")
    require(document.get("commit") == SOURCE_COMMIT, "Cloud manifest source mismatch")
    images = document.get("images")
    require(isinstance(images, list) and len(images) == 14, "Cloud manifest cardinality mismatch")
    require(isinstance(inventory, dict) and isinstance(inventory.get("images"), list),
            "runtime inventory is invalid")
    require(
        isinstance(historical_inventory, dict)
        and historical_inventory.get("schemaVersion") == 1
        and isinstance(historical_inventory.get("images"), list)
        and len(historical_inventory["images"]) == 14,
        "historical Cloud inventory is invalid",
    )

    current = [
        item for item in inventory["images"]
        if isinstance(item, dict) and item.get("role") == "cloud"
        and item.get("name") not in {
            "cloud-postgres", "cloud-redis", "cloud-minio", "cloud-minio-init",
            "cloud-searxng", "cloud-edge",
        }
    ]
    require(len(current) == 14, "canonical Cloud inventory cardinality mismatch")
    canonical_by_binding: dict[tuple[str, str], dict[str, Any]] = {}
    for item in current:
        require(
            set(item) == {"name", "role", "service", "environment"},
            "canonical Cloud inventory entry is invalid",
        )
        binding = (item["service"], item["environment"])
        require(binding not in canonical_by_binding, "ambiguous canonical Cloud binding")
        canonical_by_binding[binding] = item

    historical_by_name: dict[str, dict[str, str]] = {}
    for raw in historical_inventory["images"]:
        required = {"name", "service", "package", "environment"}
        require(
            isinstance(raw, dict) and required.issubset(raw),
            "historical Cloud inventory entry is invalid",
        )
        item = {key: str(raw[key]) for key in required}
        require(item["name"] not in historical_by_name, "duplicate historical Cloud name")
        require(
            item["package"].startswith("ghcr.io/trinyxai/")
            and "@" not in item["package"]
            and not any(ch.isspace() for ch in item["package"]),
            "historical Cloud package is invalid",
        )
        historical_by_name[item["name"]] = item
    require(len(historical_by_name) == 14, "historical Cloud name cardinality mismatch")

    canonical_images: list[dict[str, str]] = []
    used_canonical: set[str] = set()
    seen_historical: set[str] = set()
    for raw in images:
        required = {"name", "service", "package", "environment", "digest", "immutableRef"}
        require(isinstance(raw, dict) and set(raw) == required,
                "historical Cloud manifest entry is invalid")
        legacy_name = str(raw["name"])
        require(legacy_name not in seen_historical, "duplicate historical Cloud manifest name")
        seen_historical.add(legacy_name)
        source_binding = historical_by_name.get(legacy_name)
        require(source_binding is not None, "unexpected historical Cloud manifest name")
        for key in ("service", "environment", "package"):
            require(str(raw[key]) == source_binding[key],
                    f"historical Cloud manifest binding mismatch:{legacy_name}:{key}")
        digest = str(raw["digest"])
        immutable_ref = str(raw["immutableRef"])
        require(DIGEST_RE.fullmatch(digest) is not None,
                f"historical Cloud digest mismatch:{legacy_name}")
        require(immutable_ref == source_binding["package"] + "@" + digest,
                f"historical Cloud immutable ref mismatch:{legacy_name}")
        canonical = canonical_by_binding.get(
            (source_binding["service"], source_binding["environment"])
        )
        require(canonical is not None, f"missing canonical Cloud binding:{legacy_name}")
        canonical_name = str(canonical["name"])
        require(canonical_name not in used_canonical, "duplicate canonical Cloud mapping")
        used_canonical.add(canonical_name)
        canonical_images.append({
            "name": canonical_name,
            "service": source_binding["service"],
            "package": source_binding["package"],
            "environment": source_binding["environment"],
            "digest": digest,
            "immutableRef": immutable_ref,
        })

    require(set(seen_historical) == set(historical_by_name),
            "missing historical Cloud manifest image")
    require(set(used_canonical) == {str(item["name"]) for item in current},
            "missing canonical Cloud mapping")
    return {
        "schemaVersion": 1,
        "commit": SOURCE_COMMIT,
        "generatedAt": document["generatedAt"],
        "images": sorted(canonical_images, key=lambda item: item["name"]),
    }


def paid_manifest(document: Any, *, historical_digest: str, name: str, service: str,
                  environment: str, package: str) -> dict[str, Any]:
    require(isinstance(document, dict) and set(document) == {"package", "tag", "digest", "labels"},
            "Paid image inspection schema mismatch")
    require(document["package"] == package, "Paid image package mismatch")
    require(document["tag"] == f"{package}:{SOURCE_COMMIT}", "Paid immutable tag mismatch")
    digest = str(document["digest"])
    require(DIGEST_RE.fullmatch(digest) is not None, "Paid image digest mismatch")
    require(DIGEST_RE.fullmatch(historical_digest) is not None,
            "historical Paid image digest is invalid")
    require(digest == historical_digest,
            "current Paid tag moved from authenticated historical digest")
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
    parser.add_argument("--backend-job", type=Path, required=True)
    parser.add_argument("--frontend-job", type=Path, required=True)
    parser.add_argument("--backend-log", type=Path, required=True)
    parser.add_argument("--frontend-log", type=Path, required=True)
    parser.add_argument("--cloud-manifest", type=Path, required=True)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--historical-inventory", type=Path, required=True)
    parser.add_argument("--backend-inspect", type=Path, required=True)
    parser.add_argument("--frontend-inspect", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    validate_run(load(args.backend_run), run_id=BACKEND_RUN_ID,
                 workflow=BACKEND_WORKFLOW, cloud_reusable=True)
    validate_run(load(args.frontend_run), run_id=FRONTEND_RUN_ID,
                 workflow=FRONTEND_WORKFLOW, cloud_reusable=False)
    validate_artifact(load(args.artifact))
    validate_job(
        load(args.backend_job), job_id=BACKEND_PUBLISH_JOB_ID,
        run_id=BACKEND_RUN_ID, name="publish",
    )
    validate_job(
        load(args.frontend_job), job_id=FRONTEND_PUBLISH_JOB_ID,
        run_id=FRONTEND_RUN_ID, name="build-and-push",
    )
    try:
        backend_log = args.backend_log.read_text(encoding="utf-8")
        frontend_log = args.frontend_log.read_text(encoding="utf-8")
    except OSError as exc:
        raise ValueError("cannot read historical publication job log") from exc
    backend_digest = historical_paid_digest(
        backend_log, package="ghcr.io/trinyxai/trinyx-backend"
    )
    frontend_digest = historical_paid_digest(
        frontend_log, package="ghcr.io/trinyxai/trinyx-frontend"
    )
    write_canonical(
        args.out / "cloud.json",
        canonical_cloud_manifest(
            load(args.cloud_manifest),
            load(args.inventory),
            load(args.historical_inventory),
        ),
    )
    write_canonical(args.out / "backend.json", paid_manifest(
        load(args.backend_inspect), historical_digest=backend_digest,
        name="paid-backend", service="livecontext",
        environment="BACKEND_IMAGE", package="ghcr.io/trinyxai/trinyx-backend",
    ))
    write_canonical(args.out / "frontend.json", paid_manifest(
        load(args.frontend_inspect), historical_digest=frontend_digest,
        name="paid-frontend", service="frontend",
        environment="FRONTEND_IMAGE", package="ghcr.io/trinyxai/trinyx-frontend",
    ))
    print(f"HISTORICAL_BASELINE_PROVENANCE_OK source_commit={SOURCE_COMMIT}")


if __name__ == "__main__":
    try:
        main()
    except ValueError as exc:
        raise SystemExit(f"ERROR_HISTORICAL_BASELINE={exc}") from exc
