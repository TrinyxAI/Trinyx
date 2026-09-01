#!/usr/bin/env python3
"""Cross-host staging deployment and qualification saga over fixed SSM calls."""

from __future__ import annotations

import argparse
import contextlib
import json
import re
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, Protocol

if __package__:
    from .invariants import InvariantError, RELEASE_RE, require, validate_release_manifest
else:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from invariants import InvariantError, RELEASE_RE, require, validate_release_manifest  # type: ignore

INSTANCES = {"cloud": "i-06f414cdb30078a9d", "paid": "i-0b8fd709ff82f6dd2"}


@dataclass(frozen=True)
class Request:
    mode: str
    role: str
    release_id: str
    bundle_digest: str
    deployment_id: str
    config_revision: str
    platform_commit: str
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
    def __init__(self, document: str, document_version: str, registry_bucket: str, region: str = "us-east-1"):
        require(document == "Trinyx-Staging-Deploy", "unexpected SSM document")
        require(re.fullmatch(r"[1-9][0-9]*", document_version) is not None, "SSM document version must be numeric and pinned")
        require(re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", registry_bucket) is not None, "bad registry bucket")
        self.document = document
        self.document_version = document_version
        self.registry_bucket = registry_bucket
        self.region = region

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
            "PlatformCommit": [request.platform_commit],
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
        for _ in range(90):
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
                time.sleep(2)
                continue
            try:
                payload = json.loads(result.stdout)
            except json.JSONDecodeError as exc:
                raise InvariantError("invalid SSM result") from exc
            status = payload.get("Status")
            if status in {"Pending", "InProgress", "Delayed"}:
                time.sleep(2)
                continue
            require(status == "Success", f"SSM command failed with status {status}")
            require(not payload.get("StandardErrorContent"), "SSM command returned stderr")
            output = str(payload.get("StandardOutputContent", ""))
            require("ERROR_" not in output, "SSM output contains a fail-closed marker")
            # Output is reduced to contract markers by the fixed host dispatcher.
            return output
        raise InvariantError("SSM command timed out")


class AwsCliStagingLock:
    """Atomic account/region-wide lock. It contains only a random deployment ID."""

    NAME = "/trinyx/staging/control-plane/deployment-lock"

    def __init__(self, transport: AwsCliSsmTransport):
        self.transport = transport

    @contextlib.contextmanager
    def hold(self, owner: str) -> Iterator[None]:
        require(re.fullmatch(r"dep-[0-9a-f]{32}", owner) is not None, "bad lock owner")
        created = self.transport._aws([
            "ssm", "put-parameter", "--name", self.NAME, "--type", "String", "--value", owner,
            "--description", "Trinyx staging deployment lock; non-secret", "--no-overwrite",
        ])
        require(created.returncode == 0, "concurrent staging deployment refused")
        try:
            yield
        finally:
            current = self.transport._aws([
                "ssm", "get-parameter", "--name", self.NAME, "--query", "Parameter.Value", "--output", "text"
            ])
            if current.returncode == 0 and current.stdout.strip() == owner:
                deleted = self.transport._aws(["ssm", "delete-parameter", "--name", self.NAME])
                require(deleted.returncode == 0, "staging deployment lock release failed")


def new_deployment_id() -> str:
    return "dep-" + uuid.uuid4().hex


class StagingSaga:
    def __init__(self, transport: Transport, config_revision: str, platform_commit: str, lock: SagaLock):
        require(re.fullmatch(r"[A-Za-z0-9._-]{1,128}", config_revision) is not None, "bad config revision")
        require(re.fullmatch(r"[0-9a-f]{40}", platform_commit) is not None, "bad platform commit")
        self.transport = transport
        self.config_revision = config_revision
        self.platform_commit = platform_commit
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
                self.platform_commit,
                previous_cloud,
                previous_paid,
            )
        )

    def install(self, release_id: str, bundle_digest: str, previous_cloud: str, previous_paid: str) -> None:
        for role in ("paid", "cloud"):
            output = self._request("install", role, release_id, bundle_digest, previous_cloud, previous_paid)
            require(f"RELEASE_INSTALL_APPLY_OK role={role}" in output, f"{role} release installation not acknowledged")

    def plan_both(self, release_id: str, bundle_digest: str, previous_cloud: str, previous_paid: str) -> None:
        for role in ("paid", "cloud"):
            output = self._request("plan", role, release_id, bundle_digest, previous_cloud, previous_paid)
            require(f"STAGING_DEPLOY_PLAN_OK role={role} release_id={release_id}" in output, f"{role} preflight failed")

    def full_health(self, release_id: str, bundle_digest: str, previous_cloud: str, previous_paid: str) -> None:
        # Paid liveness first; Cloud health includes Cloud->Paid strict TLS and edge smoke.
        for role in ("paid", "cloud"):
            output = self._request("health", role, release_id, bundle_digest, previous_cloud, previous_paid)
            require(f"STAGING_DEPLOY_HEALTH_OK role={role} release_id={release_id}" in output, f"{role} health failed")

    def deploy(self, release_id: str, bundle_digest: str, previous_cloud: str, previous_paid: str) -> None:
        owner = new_deployment_id()
        with self.lock.hold(owner):
            self.plan_both(release_id, bundle_digest, previous_cloud, previous_paid)
            paid_applied = False
            try:
                paid = self._request("apply", "paid", release_id, bundle_digest, previous_cloud, previous_paid)
                require(f"STAGING_DEPLOY_APPLY_OK role=paid release_id={release_id}" in paid, "Paid apply not acknowledged")
                paid_applied = True
                cloud = self._request("apply", "cloud", release_id, bundle_digest, previous_cloud, previous_paid)
                require(f"STAGING_DEPLOY_APPLY_OK role=cloud release_id={release_id}" in cloud, "Cloud apply not acknowledged")
                self.full_health(release_id, bundle_digest, previous_cloud, previous_paid)
            except Exception:
                # The host engine first compensates its own partial mutation. Cloud is
                # restored before Paid so a failed Cloud edge cannot keep sending new
                # traffic while Paid is being restored.
                with suppress_invariant():
                    self._request("rollback", "cloud", previous_cloud, bundle_digest, release_id, release_id)
                if paid_applied:
                    with suppress_invariant():
                        self._request("rollback", "paid", previous_paid, bundle_digest, previous_cloud, release_id)
                raise

    def rollback(self, baseline_id: str, baseline_digest: str, candidate_id: str) -> None:
        owner = new_deployment_id()
        with self.lock.hold(owner):
            # Cloud first removes the candidate-facing edge, then Paid returns.
            cloud = self._request("rollback", "cloud", baseline_id, baseline_digest, candidate_id, candidate_id)
            require(f"STAGING_DEPLOY_ROLLBACK_OK role=cloud release_id={baseline_id}" in cloud, "Cloud rollback not acknowledged")
            try:
                paid = self._request("rollback", "paid", baseline_id, baseline_digest, baseline_id, candidate_id)
                require(f"STAGING_DEPLOY_ROLLBACK_OK role=paid release_id={baseline_id}" in paid, "Paid rollback not acknowledged")
            except Exception:
                # Paid host rollback compensates itself to candidate. Restore Cloud
                # to candidate as well so a partial O12 does not leave mixed stacks.
                with suppress_invariant():
                    self._request("rollback", "cloud", candidate_id, baseline_digest, baseline_id, candidate_id)
                raise
            self.full_health(baseline_id, baseline_digest, baseline_id, baseline_id)


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
    parser.add_argument("command", choices=("install", "deploy", "rollback", "health", "diff"))
    parser.add_argument("--document", default="Trinyx-Staging-Deploy")
    parser.add_argument("--document-version")
    parser.add_argument("--registry-bucket")
    parser.add_argument("--config-revision")
    parser.add_argument("--platform-commit")
    parser.add_argument("--release-id")
    parser.add_argument("--bundle-digest")
    parser.add_argument("--previous-cloud")
    parser.add_argument("--previous-paid")
    parser.add_argument("--candidate-id")
    parser.add_argument("--baseline-manifest", type=Path)
    parser.add_argument("--candidate-manifest", type=Path)
    args = parser.parse_args()
    if args.command == "diff":
        require(args.baseline_manifest and args.candidate_manifest, "manifest paths required")
        changed = meaningful_runtime_difference(args.baseline_manifest, args.candidate_manifest)
        print("QUALIFICATION_MEANINGFUL_DIFF_OK components=" + ",".join(changed))
        return
    require(all((args.document_version, args.registry_bucket, args.config_revision, args.platform_commit)), "control-plane inputs required")
    transport = AwsCliSsmTransport(args.document, args.document_version, args.registry_bucket)
    saga = StagingSaga(transport, args.config_revision, args.platform_commit, AwsCliStagingLock(transport))
    require(args.release_id and args.bundle_digest and args.previous_cloud and args.previous_paid, "release inputs required")
    if args.command == "install":
        saga.install(args.release_id, args.bundle_digest, args.previous_cloud, args.previous_paid)
        print(f"STAGING_SAGA_INSTALL_OK release_id={args.release_id}")
    elif args.command == "deploy":
        saga.deploy(args.release_id, args.bundle_digest, args.previous_cloud, args.previous_paid)
        print(f"STAGING_SAGA_DEPLOY_OK release_id={args.release_id}")
    elif args.command == "rollback":
        require(args.candidate_id, "candidate ID required")
        saga.rollback(args.release_id, args.bundle_digest, args.candidate_id)
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
