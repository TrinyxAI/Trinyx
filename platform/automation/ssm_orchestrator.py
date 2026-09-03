#!/usr/bin/env python3
"""Cross-host staging deployment and qualification saga over fixed SSM calls."""

from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterator, Protocol

if __package__:
    from .invariants import DIGEST_RE, SHA_RE, InvariantError, RELEASE_RE, require, validate_release_manifest
    from .legacy_runtime import SERVICES
else:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from invariants import DIGEST_RE, SHA_RE, InvariantError, RELEASE_RE, require, validate_release_manifest  # type: ignore
    from legacy_runtime import SERVICES  # type: ignore

INSTANCES = {"cloud": "i-06f414cdb30078a9d", "paid": "i-0b8fd709ff82f6dd2"}
SSM_EXECUTION_TIMEOUT_SECONDS = 900
SSM_POLL_GRACE_SECONDS = 60
STALE_LOCK_LOOKBACK = dt.timedelta(minutes=5)
SSM_POLL_INTERVAL_SECONDS = 2.0
NORMALIZATION_PROTOCOL_MAX_BYTES = 20_000
ACTIVE_COMMAND_STATUSES = {"Pending", "InProgress", "Delayed", "Cancelling"}
STALE_LOCK_CONFIRMATION = "AWS_STAGING_STALE_LOCK_BREAK_APPROVED"


@dataclass(frozen=True)
class Request:
    mode: str
    role: str
    release_id: str
    bundle_digest: str
    deployment_id: str
    config_revision: str
    control_plane_commit: str
    previous_cloud: str | None
    previous_paid: str | None


class Transport(Protocol):
    def execute(self, request: Request) -> str: ...


class SagaLock(Protocol):
    @contextlib.contextmanager
    def hold(self, owner: str) -> Iterator[None]: ...


class NoopSagaLock:
    """Test-only lock; production CLI always injects AwsCliStagingLock."""

    @contextlib.contextmanager
    def hold(self, owner: str) -> Iterator[None]:
        require(re.fullmatch(r"dep-[0-9a-f]{32}", owner) is not None, "bad lock owner")
        yield


class AwsCliSsmTransport:
    def __init__(
        self,
        document: str,
        document_version: str,
        registry_bucket: str,
        region: str = "us-east-1",
        execution_timeout_seconds: int = SSM_EXECUTION_TIMEOUT_SECONDS,
        poll_grace_seconds: int = SSM_POLL_GRACE_SECONDS,
        monotonic: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], None] = time.sleep,
    ):
        require(document == "Trinyx-Staging-Deploy", "unexpected SSM document")
        require(re.fullmatch(r"[1-9][0-9]*", document_version) is not None, "SSM document version must be numeric and pinned")
        require(re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", registry_bucket) is not None, "bad registry bucket")
        require(1 <= execution_timeout_seconds <= 3600, "SSM execution timeout out of bounds")
        require(1 <= poll_grace_seconds <= 300, "SSM polling grace out of bounds")
        self.document = document
        self.document_version = document_version
        self.registry_bucket = registry_bucket
        self.region = region
        self.poll_budget_seconds = execution_timeout_seconds + poll_grace_seconds
        self.monotonic = monotonic
        self.sleep = sleep

    def _aws(self, argv: list[str], timeout: int = 45) -> subprocess.CompletedProcess[str]:
        try:
            return subprocess.run(
                ["aws", *argv, "--region", self.region],
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=timeout,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise InvariantError("bounded AWS CLI call failed") from exc

    def execute(self, request: Request) -> str:
        require(request.role in INSTANCES, "unexpected staging role")
        parameters = {
            "Mode": [request.mode],
            "Role": [request.role],
            "ReleaseId": [request.release_id],
            "BundleDigest": [request.bundle_digest],
            "RegistryBucket": [self.registry_bucket],
            "DeploymentId": [request.deployment_id],
            "EnvironmentConfigRevision": [request.config_revision],
            "ControlPlaneCommit": [request.control_plane_commit],
            "PreviousCloudRelease": [request.previous_cloud or ""],
            "PreviousPaidRelease": [request.previous_paid or ""],
        }
        sent = self._aws(
            [
                "ssm",
                "send-command",
                "--document-name",
                self.document,
                "--document-version",
                self.document_version,
                "--instance-ids",
                INSTANCES[request.role],
                "--parameters",
                json.dumps(parameters, separators=(",", ":")),
                "--comment",
                f"Trinyx staging {request.mode} {request.deployment_id} {request.role}",
                "--query",
                "Command.CommandId",
                "--output",
                "text",
            ]
        )
        require(sent.returncode == 0 and re.fullmatch(r"[0-9a-f-]{36}\n?", sent.stdout) is not None, "SSM SendCommand failed")
        command_id = sent.stdout.strip()
        deadline = self.monotonic() + self.poll_budget_seconds
        while self.monotonic() < deadline:
            result = self._aws(
                [
                    "ssm",
                    "get-command-invocation",
                    "--command-id",
                    command_id,
                    "--instance-id",
                    INSTANCES[request.role],
                    "--output",
                    "json",
                ]
            )
            if result.returncode != 0:
                remaining = deadline - self.monotonic()
                if remaining > 0:
                    self.sleep(min(SSM_POLL_INTERVAL_SECONDS, remaining))
                continue
            try:
                payload = json.loads(result.stdout)
            except json.JSONDecodeError as exc:
                raise InvariantError("invalid SSM result") from exc
            status = payload.get("Status")
            if status in ACTIVE_COMMAND_STATUSES:
                remaining = deadline - self.monotonic()
                if remaining > 0:
                    self.sleep(min(SSM_POLL_INTERVAL_SECONDS, remaining))
                continue
            require(status == "Success", f"SSM command failed with status {status}")
            require(not payload.get("StandardErrorContent"), "SSM command returned stderr")
            output = str(payload.get("StandardOutputContent", ""))
            require("ERROR_" not in output, "SSM output contains a fail-closed marker")
            # Output is reduced to contract markers by the fixed host dispatcher.
            return output
        raise InvariantError(
            f"SSM command exceeded orchestrator budget of {self.poll_budget_seconds} seconds"
        )


class AwsCliStagingLock:
    """Atomic account/region lock with explicit, proof-gated stale recovery."""

    NAME = "/trinyx/staging/control-plane/deployment-lock"

    def __init__(self, transport: AwsCliSsmTransport):
        self.transport = transport

    @staticmethod
    def _value(owner: str) -> str:
        run_id = os.environ.get("GITHUB_RUN_ID", "manual")
        run_attempt = os.environ.get("GITHUB_RUN_ATTEMPT", "manual")
        require(run_id == "manual" or re.fullmatch(r"[0-9]{1,20}", run_id) is not None, "bad GitHub run ID")
        require(run_attempt == "manual" or re.fullmatch(r"[0-9]{1,10}", run_attempt) is not None, "bad GitHub run attempt")
        return json.dumps(
            {
                "schemaVersion": 1,
                "owner": owner,
                "createdAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
                "githubRunId": run_id,
                "githubRunAttempt": run_attempt,
            },
            sort_keys=True,
            separators=(",", ":"),
        )

    def _read(self) -> str:
        current = self.transport._aws([
            "ssm", "get-parameter", "--name", self.NAME,
            "--query", "Parameter.Value", "--output", "text",
        ])
        require(current.returncode == 0, "staging deployment lock is missing or unreadable")
        return current.stdout.strip()

    @contextlib.contextmanager
    def hold(self, owner: str) -> Iterator[None]:
        require(re.fullmatch(r"dep-[0-9a-f]{32}", owner) is not None, "bad lock owner")
        value = self._value(owner)
        created = self.transport._aws([
            "ssm", "put-parameter", "--name", self.NAME, "--type", "String", "--value", value,
            "--description", "Trinyx staging deployment lock; non-secret metadata", "--no-overwrite",
        ])
        require(created.returncode == 0, "concurrent or stale staging deployment lock refused")
        try:
            yield
        finally:
            if self._read() == value:
                deleted = self.transport._aws(["ssm", "delete-parameter", "--name", self.NAME])
                require(deleted.returncode == 0, "staging deployment lock release failed")

    def break_stale(self, owner: str, confirmation: str) -> None:
        require(re.fullmatch(r"dep-[0-9a-f]{32}", owner) is not None, "bad stale lock owner")
        require(confirmation == STALE_LOCK_CONFIRMATION, "stale lock break lacks explicit approval")
        raw = self._read()
        try:
            lock = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise InvariantError("stale lock metadata is not valid JSON; manual AWS review required") from exc
        require(
            isinstance(lock, dict)
            and set(lock) == {"schemaVersion", "owner", "createdAt", "githubRunId", "githubRunAttempt"}
            and lock.get("schemaVersion") == 1
            and lock.get("owner") == owner,
            "stale lock owner/schema mismatch",
        )
        try:
            created_at = dt.datetime.fromisoformat(str(lock["createdAt"]).replace("Z", "+00:00"))
        except ValueError as exc:
            raise InvariantError("stale lock timestamp is invalid; manual AWS review required") from exc
        require(created_at.tzinfo is not None, "stale lock timestamp lacks timezone")
        invoked_after = (created_at - STALE_LOCK_LOOKBACK).astimezone(dt.timezone.utc)
        invoked_after_text = invoked_after.isoformat(timespec="seconds").replace("+00:00", "Z")
        commands = self.transport._aws([
            "ssm", "list-commands",
            "--filters",
            f"key=DocumentName,value={self.transport.document}",
            f"key=InvokedAfter,value={invoked_after_text}",
            "--output", "json",
        ])
        require(commands.returncode == 0, "cannot prove absence of active SSM commands")
        try:
            command_list = json.loads(commands.stdout).get("Commands", [])
        except json.JSONDecodeError as exc:
            raise InvariantError("invalid SSM command inventory") from exc
        require(isinstance(command_list, list), "invalid SSM command inventory")
        active = [
            command for command in command_list
            if isinstance(command, dict)
            and owner in str(command.get("Comment", ""))
            and command.get("Status") in ACTIVE_COMMAND_STATUSES
        ]
        require(not active, "stale lock break refused: associated SSM command is still active")
        require(self._read() == raw, "staging deployment lock changed during recovery proof")
        deleted = self.transport._aws(["ssm", "delete-parameter", "--name", self.NAME])
        require(deleted.returncode == 0, "stale staging deployment lock deletion failed")


def new_deployment_id() -> str:
    return "dep-" + uuid.uuid4().hex


def _normalization_fields(line: str, prefix: str, expected: set[str]) -> dict[str, str]:
    parts = line.split(" ")
    require(parts and parts[0] == prefix and all(parts), f"invalid {prefix} protocol line")
    fields: dict[str, str] = {}
    for token in parts[1:]:
        require(token.count("=") == 1, f"invalid {prefix} protocol token")
        key, value = token.split("=", 1)
        require(key in expected and key not in fields and value, f"invalid/duplicate {prefix} field")
        fields[key] = value
    require(set(fields) == expected, f"incomplete {prefix} protocol fields")
    return fields


def _normalization_int(value: str, label: str) -> int:
    require(re.fullmatch(r"[0-9]{1,3}", value) is not None, f"invalid {label}")
    return int(value)


def validate_normalization_protocol(
    output: str,
    role: str,
    release_id: str,
    bundle_digest: str,
    deployment_id: str,
    config_revision: str,
    control_plane_commit: str,
) -> dict[str, object]:
    """Authenticate the complete marker-last normalization protocol received from SSM."""
    require(role in SERVICES, "invalid normalization protocol role")
    require(RELEASE_RE.fullmatch(release_id) is not None, "invalid normalization release")
    require(DIGEST_RE.fullmatch(bundle_digest) is not None, "invalid normalization bundle digest")
    require(re.fullmatch(r"dep-[0-9a-f]{32}", deployment_id) is not None,
            "invalid normalization deployment ID")
    require(re.fullmatch(r"[A-Za-z0-9._-]{1,128}", config_revision) is not None,
            "invalid normalization config revision")
    require(SHA_RE.fullmatch(control_plane_commit) is not None,
            "invalid normalization control-plane commit")
    require(0 < len(output.encode("utf-8")) < NORMALIZATION_PROTOCOL_MAX_BYTES,
            "legacy normalization protocol exceeds the SSM-safe budget")
    require("\r" not in output and output.endswith("\n"),
            "normalization protocol is truncated or has non-canonical newlines")
    lines = output[:-1].split("\n")
    expected_services = SERVICES[role]
    require(len(lines) == len(expected_services) + 2,
            "normalization protocol service cardinality mismatch")
    require(all(lines), "normalization protocol contains an empty line")

    header_keys = {
        "role", "release_id", "bundle_digest", "deployment_id", "config_revision",
        "config_digest", "control_plane_commit", "observed_at", "compose_version",
        "service_count", "canonical_matches", "explained_drift", "unexplained_drift",
    }
    header = _normalization_fields(lines[0], "LEGACY_NORMALIZATION_REPORT_V3", header_keys)
    require(
        header["role"] == role
        and header["release_id"] == release_id
        and header["bundle_digest"] == bundle_digest
        and header["deployment_id"] == deployment_id
        and header["config_revision"] == config_revision
        and header["control_plane_commit"] == control_plane_commit,
        "normalization header context binding mismatch",
    )
    require(DIGEST_RE.fullmatch(header["config_digest"]) is not None,
            "invalid normalization config digest")
    require(re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", header["observed_at"]) is not None,
            "invalid normalization observation timestamp")
    require(re.fullmatch(r"v?[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?",
                         header["compose_version"]) is not None,
            "invalid normalization Compose version")
    service_count = _normalization_int(header["service_count"], "normalization service count")
    canonical_matches = _normalization_int(
        header["canonical_matches"], "normalization canonical match count"
    )
    explained_drift = _normalization_int(
        header["explained_drift"], "normalization explained drift count"
    )
    unexplained_drift = _normalization_int(
        header["unexplained_drift"], "normalization unexplained drift count"
    )
    require(
        service_count == len(expected_services)
        and canonical_matches + explained_drift + unexplained_drift == service_count,
        "normalization header count invariant failed",
    )

    service_keys = {
        "role", "service", "recreate", "reasons", "image_match", "container_id",
        "image_object", "configured_image_canonical", "configured_image_sha256",
        "expected_image_digest",
        "repo_digests_sha256", "compose_drift", "current_config_hash",
        "expected_config_hash",
        "current_bind_mounts_sha256", "expected_bind_mounts_sha256",
        "mutable_checkout",
    }
    allowed_reasons = {
        "IMAGE_REFERENCE_NON_CANONICAL", "IMAGE_OBJECT_DIGEST_MISMATCH",
        "COMPOSE_CONFIG_DRIFT_EXPLAINED", "UNEXPLAINED_COMPOSE_CONFIG_DRIFT",
        "BIND_MOUNT_MISMATCH",
        "MUTABLE_CHECKOUT_MOUNT",
    }
    seen: set[str] = set()
    recreate_count = 0
    calculated_canonical_matches = 0
    calculated_explained_drift = 0
    calculated_unexplained_drift = 0
    all_images_match = True
    for line in lines[1:-1]:
        fields = _normalization_fields(line, "NORMALIZATION", service_keys)
        service = fields["service"]
        require(fields["role"] == role and service in expected_services and service not in seen,
                "normalization service is unknown, duplicated or role-mismatched")
        seen.add(service)
        require(fields["recreate"] in {"yes", "no"}
            and fields["image_match"] in {"yes", "no"}
            and fields["configured_image_canonical"] in {"yes", "no"}
            and fields["mutable_checkout"] in {"yes", "no"}
            and fields["compose_drift"] in {"matched", "explained", "unexplained"},
                "invalid normalization boolean field")
        reasons = [] if fields["reasons"] == "none" else fields["reasons"].split(",")
        require(
            len(reasons) == len(set(reasons))
            and all(reason in allowed_reasons for reason in reasons)
            and (fields["recreate"] == "yes") == bool(reasons)
            and (fields["mutable_checkout"] == "yes") == ("MUTABLE_CHECKOUT_MOUNT" in reasons),
            "normalization reasons/recreate invariant failed",
        )
        for key in (
            "container_id", "current_config_hash", "expected_config_hash",
        ):
            require(re.fullmatch(r"[0-9a-f]{64}", fields[key]) is not None,
                    f"invalid normalization {key}")
        require(re.fullmatch(r"sha256:[0-9a-f]{64}", fields["image_object"]) is not None,
                "invalid normalization image object ID")
        for key in (
            "configured_image_sha256", "expected_image_digest", "repo_digests_sha256",
            "current_bind_mounts_sha256", "expected_bind_mounts_sha256",
        ):
            require(DIGEST_RE.fullmatch(fields[key]) is not None,
                    f"invalid normalization {key}")
        image_matches = fields["image_match"] == "yes"
        configured_canonical = fields["configured_image_canonical"] == "yes"
        require(
            configured_canonical == ("IMAGE_REFERENCE_NON_CANONICAL" not in reasons)
            and image_matches == ("IMAGE_OBJECT_DIGEST_MISMATCH" not in reasons),
            "normalization image evidence contradicts reasons",
        )
        if not image_matches:
            all_images_match = False
        compose_drift = fields["compose_drift"]
        current_hash = fields["current_config_hash"]
        expected_hash = fields["expected_config_hash"]
        if compose_drift == "matched":
            require(
                current_hash == expected_hash
                and "COMPOSE_CONFIG_DRIFT_EXPLAINED" not in reasons
                and "UNEXPLAINED_COMPOSE_CONFIG_DRIFT" not in reasons,
                "normalization matched Compose evidence is inconsistent",
            )
            calculated_canonical_matches += 1
        elif compose_drift == "explained":
            require(
                current_hash != expected_hash
                and "COMPOSE_CONFIG_DRIFT_EXPLAINED" in reasons
                and "UNEXPLAINED_COMPOSE_CONFIG_DRIFT" not in reasons,
                "normalization explained Compose evidence is inconsistent",
            )
            calculated_explained_drift += 1
        else:
            require(
                current_hash != expected_hash
                and "UNEXPLAINED_COMPOSE_CONFIG_DRIFT" in reasons
                and "COMPOSE_CONFIG_DRIFT_EXPLAINED" not in reasons,
                "normalization unexplained Compose evidence is inconsistent",
            )
            calculated_unexplained_drift += 1
        if fields["recreate"] == "yes":
            recreate_count += 1
    require(seen == expected_services, "normalization service inventory is incomplete")
    require(
        calculated_canonical_matches == canonical_matches
        and calculated_explained_drift == explained_drift
        and calculated_unexplained_drift == unexplained_drift,
        "normalization Compose drift summary differs from service lines",
    )

    marker_keys = {
        "role", "release_id", "services", "recreate_count", "compose_version",
        "compatibility", "images", "report_sha256",
    }
    marker = _normalization_fields(
        lines[-1], "LEGACY_NORMALIZATION_PLAN_COMPLETE", marker_keys
    )
    compatibility = "review" if unexplained_drift == 0 else "stop"
    images = "matched" if all_images_match else "mismatch"
    require(
        marker["role"] == role
        and marker["release_id"] == release_id
        and _normalization_int(marker["services"], "marker service count") == service_count
        and _normalization_int(marker["recreate_count"], "marker recreate count") == recreate_count
        and marker["compose_version"] == header["compose_version"]
        and marker["compatibility"] == compatibility
        and marker["images"] == images,
        "normalization final marker summary mismatch",
    )
    payload = "\n".join(lines[:-1]) + "\n"
    expected_report_sha = "sha256:" + hashlib.sha256(payload.encode("utf-8")).hexdigest()
    require(marker["report_sha256"] == expected_report_sha,
            "normalization report SHA-256 mismatch")
    return {
        "compatibility": compatibility,
        "images": images,
        "serviceCount": service_count,
        "recreateCount": recreate_count,
        "reportSha256": expected_report_sha,
    }


class StagingSaga:
    def __init__(self, transport: Transport, config_revision: str, control_plane_commit: str, lock: SagaLock):
        require(re.fullmatch(r"[A-Za-z0-9._-]{1,128}", config_revision) is not None, "bad config revision")
        require(re.fullmatch(r"[0-9a-f]{40}", control_plane_commit) is not None, "bad control-plane commit")
        self.transport = transport
        self.config_revision = config_revision
        self.control_plane_commit = control_plane_commit
        self.lock = lock

    def _request(
        self,
        mode: str,
        role: str,
        release_id: str,
        bundle_digest: str,
        previous_cloud: str | None,
        previous_paid: str | None,
        deployment_id: str | None = None,
    ) -> str:
        return self.transport.execute(
            Request(
                mode,
                role,
                release_id,
                bundle_digest,
                deployment_id or new_deployment_id(),
                self.config_revision,
                self.control_plane_commit,
                previous_cloud,
                previous_paid,
            )
        )

    def install(self, release_id: str, bundle_digest: str, previous_cloud: str, previous_paid: str) -> None:
        for role in ("paid", "cloud"):
            output = self._request("install", role, release_id, bundle_digest, previous_cloud, previous_paid)
            require(f"RELEASE_INSTALL_APPLY_OK role={role}" in output, f"{role} release installation not acknowledged")

    def plan_both(
        self, release_id: str, bundle_digest: str, previous_cloud: str, previous_paid: str,
        deployment_id: str | None = None,
    ) -> None:
        for role in ("paid", "cloud"):
            output = self._request(
                "plan", role, release_id, bundle_digest, previous_cloud, previous_paid, deployment_id
            )
            require(f"STAGING_DEPLOY_PLAN_OK role={role} release_id={release_id}" in output, f"{role} preflight failed")

    def full_health(
        self, release_id: str, bundle_digest: str, previous_cloud: str, previous_paid: str,
        deployment_id: str | None = None,
    ) -> None:
        # Paid liveness first; Cloud health includes Cloud->Paid strict TLS and edge smoke.
        for role in ("paid", "cloud"):
            output = self._request(
                "health", role, release_id, bundle_digest, previous_cloud, previous_paid, deployment_id
            )
            require(f"STAGING_DEPLOY_HEALTH_OK role={role} release_id={release_id}" in output, f"{role} health failed")

    def legacy_normalization_plan(self, release_id: str, bundle_digest: str) -> None:
        owner = new_deployment_id()
        with self.lock.hold(owner):
            for role in ("paid", "cloud"):
                output = self._request(
                    "normalize-plan", role, release_id, bundle_digest, None, None, owner
                )
                # Strictly authenticate cardinality, service inventory, marker-last
                # framing and SHA-256 before exposing the report for human review.
                report = validate_normalization_protocol(
                    output,
                    role,
                    release_id,
                    bundle_digest,
                    owner,
                    self.config_revision,
                    self.control_plane_commit,
                )
                print(output.rstrip())
                require(
                    report["compatibility"] == "review",
                    f"{role} normalization contains unexplained Compose drift",
                )
                # A proven object mismatch is reviewable PLAN output and yields a
                # bounded recreate reason.  Unprovable identity already fails in
                # the host observer or authenticated protocol validator.

    def adopt_legacy_baseline(self, release_id: str, bundle_digest: str) -> None:
        owner = new_deployment_id()
        with self.lock.hold(owner):
            paid_adopted = False
            try:
                paid = self._request("adopt", "paid", release_id, bundle_digest, None, None, owner)
                require(
                    f"STAGING_LEGACY_ADOPTION_OK role=paid release_id={release_id}" in paid,
                    "Paid legacy adoption not acknowledged",
                )
                paid_adopted = True
                cloud = self._request("adopt", "cloud", release_id, bundle_digest, None, None, owner)
                require(
                    f"STAGING_LEGACY_ADOPTION_OK role=cloud release_id={release_id}" in cloud,
                    "Cloud legacy adoption not acknowledged",
                )
            except Exception:
                if paid_adopted:
                    with suppress_invariant():
                        self._request("restore-legacy", "paid", release_id, bundle_digest, None, None, owner)
                raise

    def deploy(
        self,
        release_id: str,
        bundle_digest: str,
        previous_cloud: str,
        previous_paid: str,
        previous_cloud_bundle_digest: str,
        previous_paid_bundle_digest: str,
    ) -> None:
        owner = new_deployment_id()
        with self.lock.hold(owner):
            self.plan_both(release_id, bundle_digest, previous_cloud, previous_paid, owner)
            paid_applied = False
            try:
                paid = self._request("apply", "paid", release_id, bundle_digest, previous_cloud, previous_paid, owner)
                require(f"STAGING_DEPLOY_APPLY_OK role=paid release_id={release_id}" in paid, "Paid apply not acknowledged")
                paid_applied = True
                cloud = self._request("apply", "cloud", release_id, bundle_digest, previous_cloud, previous_paid, owner)
                require(f"STAGING_DEPLOY_APPLY_OK role=cloud release_id={release_id}" in cloud, "Cloud apply not acknowledged")
                self.full_health(release_id, bundle_digest, previous_cloud, previous_paid, owner)
            except Exception:
                # The host engine first compensates its own partial mutation. Cloud is
                # restored before Paid so a failed Cloud edge cannot keep sending new
                # traffic while Paid is being restored.
                with suppress_invariant():
                    self._request(
                        "rollback", "cloud", previous_cloud, previous_cloud_bundle_digest,
                        release_id, release_id, owner,
                    )
                if paid_applied:
                    with suppress_invariant():
                        self._request(
                            "rollback", "paid", previous_paid, previous_paid_bundle_digest,
                            previous_cloud, release_id, owner,
                        )
                raise

    def rollback(
        self, baseline_id: str, baseline_digest: str, candidate_id: str, candidate_digest: str,
    ) -> None:
        owner = new_deployment_id()
        with self.lock.hold(owner):
            # Cloud first removes the candidate-facing edge, then Paid returns.
            cloud = self._request("rollback", "cloud", baseline_id, baseline_digest, candidate_id, candidate_id, owner)
            require(f"STAGING_DEPLOY_ROLLBACK_OK role=cloud release_id={baseline_id}" in cloud, "Cloud rollback not acknowledged")
            try:
                paid = self._request("rollback", "paid", baseline_id, baseline_digest, baseline_id, candidate_id, owner)
                require(f"STAGING_DEPLOY_ROLLBACK_OK role=paid release_id={baseline_id}" in paid, "Paid rollback not acknowledged")
            except Exception:
                # Paid host rollback compensates itself to candidate. Restore Cloud
                # to candidate as well so a partial O12 does not leave mixed stacks.
                with suppress_invariant():
                    self._request("rollback", "cloud", candidate_id, candidate_digest, baseline_id, candidate_id, owner)
                raise
            self.full_health(baseline_id, baseline_digest, baseline_id, baseline_id, owner)


class suppress_invariant:
    def __enter__(self) -> None:
        return None

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> bool:
        return exc_type is not None and issubclass(exc_type, Exception)  # type: ignore[arg-type]


def meaningful_runtime_difference(baseline_manifest: Path, candidate_manifest: Path) -> list[str]:
    baseline = validate_release_manifest(json.loads(baseline_manifest.read_text(encoding="utf-8")))
    candidate = validate_release_manifest(json.loads(candidate_manifest.read_text(encoding="utf-8")))
    changed: list[str] = []
    if baseline["deploymentBundle"] != candidate["deploymentBundle"]:
        changed.append("deploymentBundle")
    old = {image["name"]: image["immutableRef"] for image in baseline["images"]}
    new = {image["name"]: image["immutableRef"] for image in candidate["images"]}
    changed.extend(f"image:{name}" for name in sorted(set(old) | set(new)) if old.get(name) != new.get(name))
    require(changed, "candidate and baseline have no relevant runtime difference; refusing false rollback qualification")
    return changed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("install", "normalize-plan", "adopt", "deploy", "rollback", "health", "diff", "break-lock"))
    parser.add_argument("--document", default="Trinyx-Staging-Deploy")
    parser.add_argument("--document-version")
    parser.add_argument("--registry-bucket")
    parser.add_argument("--config-revision")
    parser.add_argument("--control-plane-commit")
    parser.add_argument("--release-id")
    parser.add_argument("--bundle-digest")
    parser.add_argument("--previous-cloud")
    parser.add_argument("--previous-paid")
    parser.add_argument("--candidate-id")
    parser.add_argument("--candidate-bundle-digest")
    parser.add_argument("--previous-cloud-bundle-digest")
    parser.add_argument("--previous-paid-bundle-digest")
    parser.add_argument("--baseline-manifest", type=Path)
    parser.add_argument("--candidate-manifest", type=Path)
    parser.add_argument("--lock-owner")
    parser.add_argument("--confirm-break-lock")
    args = parser.parse_args()
    if args.command == "diff":
        require(args.baseline_manifest and args.candidate_manifest, "manifest paths required")
        changed = meaningful_runtime_difference(args.baseline_manifest, args.candidate_manifest)
        print("QUALIFICATION_MEANINGFUL_DIFF_OK components=" + ",".join(changed))
        return
    if args.command == "break-lock":
        require(args.document_version and args.registry_bucket, "lock control inputs required")
        require(args.lock_owner and args.confirm_break_lock, "stale lock owner and confirmation required")
        transport = AwsCliSsmTransport(args.document, args.document_version, args.registry_bucket)
        AwsCliStagingLock(transport).break_stale(args.lock_owner, args.confirm_break_lock)
        print(f"STAGING_STALE_LOCK_BREAK_OK owner={args.lock_owner}")
        return
    require(all((args.document_version, args.registry_bucket, args.config_revision, args.control_plane_commit)), "control-plane inputs required")
    transport = AwsCliSsmTransport(args.document, args.document_version, args.registry_bucket)
    saga = StagingSaga(transport, args.config_revision, args.control_plane_commit, AwsCliStagingLock(transport))
    require(args.release_id and args.bundle_digest, "release identity inputs required")
    if args.command not in ("adopt", "normalize-plan"):
        require(args.previous_cloud and args.previous_paid, "previous release inputs required")
    if args.command == "install":
        saga.install(args.release_id, args.bundle_digest, args.previous_cloud, args.previous_paid)
        print(f"STAGING_SAGA_INSTALL_OK release_id={args.release_id}")
    elif args.command == "normalize-plan":
        saga.legacy_normalization_plan(args.release_id, args.bundle_digest)
        print(f"STAGING_SAGA_LEGACY_NORMALIZATION_PLAN_OK release_id={args.release_id}")
    elif args.command == "adopt":
        saga.adopt_legacy_baseline(args.release_id, args.bundle_digest)
        print(f"STAGING_SAGA_LEGACY_ADOPTION_OK release_id={args.release_id}")
    elif args.command == "deploy":
        require(
            args.previous_cloud_bundle_digest and args.previous_paid_bundle_digest,
            "previous bundle digest inputs required",
        )
        saga.deploy(
            args.release_id, args.bundle_digest, args.previous_cloud, args.previous_paid,
            args.previous_cloud_bundle_digest, args.previous_paid_bundle_digest,
        )
        print(f"STAGING_SAGA_DEPLOY_OK release_id={args.release_id}")
    elif args.command == "rollback":
        require(args.candidate_id and args.candidate_bundle_digest, "candidate identity required")
        saga.rollback(
            args.release_id, args.bundle_digest, args.candidate_id, args.candidate_bundle_digest,
        )
        print(f"STAGING_SAGA_ROLLBACK_OK release_id={args.release_id}")
    else:
        saga.full_health(args.release_id, args.bundle_digest, args.previous_cloud, args.previous_paid)
        print(f"STAGING_SAGA_HEALTH_OK release_id={args.release_id}")


if __name__ == "__main__":
    try:
        main()
    except (InvariantError, OSError, json.JSONDecodeError) as exc:
        print(f"ERROR_STAGING_SAGA={type(exc).__name__}", file=sys.stderr)
        raise SystemExit(1)
