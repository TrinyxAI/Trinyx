#!/usr/bin/env python3
"""Capture a non-secret, explicitly non-releasable observation of legacy runtime."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import subprocess
from pathlib import Path


SERVICES = {
    "cloud": {"agent-service", "auth-service", "catalog-service", "conversation-service", "datasource-service",
              "gateway-service", "interface-service", "keycloak", "migration-service", "orchestrator-service",
              "publication-service", "storage-service", "trigger-service", "websearch-service", "cloud-postgres",
              "cloud-redis", "cloud-minio", "cloud-minio-init", "searxng", "cloud-edge"},
    "paid": {"postgres", "redis", "minio", "minio-init", "bridge", "livecontext", "frontend", "paid-edge"},
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--role", choices=("cloud", "paid"), required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    result = subprocess.run(
        ["docker", "ps", "-a", "--format", "{{json .}}"], check=True, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30,
    )
    observed: dict[str, dict[str, str]] = {}
    for line in result.stdout.splitlines():
        item = json.loads(line)
        labels = {part.split("=", 1)[0]: part.split("=", 1)[1] for part in str(item.get("Labels", "")).split(",") if "=" in part}
        service = labels.get("com.docker.compose.service")
        if service not in SERVICES[args.role]:
            continue
        image = str(item.get("Image", ""))
        image_id = str(item.get("ID", ""))
        if not image or not re.fullmatch(r"[0-9a-f]{12,64}", image_id):
            raise SystemExit("BASELINE_OBSERVATION_FAILED=invalid_container_identity")
        observed[service] = {"configuredImage": image, "containerId": image_id}
    if set(observed) != SERVICES[args.role]:
        raise SystemExit("BASELINE_OBSERVATION_FAILED=runtime_inventory_mismatch")
    record = {
        "schemaVersion": 1,
        "environment": "staging",
        "role": args.role,
        "observedAt": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
        "releaseEligible": False,
        "reason": "observation lacks a complete source manifest and deterministic 13-file bundle",
        "services": observed,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"BASELINE_OBSERVATION_OK role={args.role} release_eligible=false")


if __name__ == "__main__":
    main()
