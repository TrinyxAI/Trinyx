#!/usr/bin/env python3
"""Repository-only O6-O12 policy checks: no network, image build, or AWS call."""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
ANY_USE = re.compile(r"^\s*uses:\s*([^\s@]+)@([^\s#]+)", re.M)
APP_BUILDS = {
    "build-release-candidate.yml", "build-trinyx-backend.yml",
    "build-trinyx-ce-images.yml", "build-trinyx-cloud-images.yml",
    "build-trinyx-frontend.yml", "cloud-stack.yml",
}
LIVE = {"staging-release-register.yml", "staging-qualification.yml", "staging-oidc-probe.yml"}


def require(ok: bool, message: str) -> None:
    if not ok:
        raise SystemExit("STATIC_POLICY_FAILED=" + message)


def head(text: str) -> str:
    return text.split("\npermissions:", 1)[0]


def check_workflows() -> None:
    files = sorted(WORKFLOWS.glob("*.yml"))
    require(bool(files), "no workflows")
    for path in files:
        text = path.read_text(encoding="utf-8")
        require("\npull_request_target:" not in "\n" + text, f"pull_request_target:{path.name}")
        require("\npermissions:" in "\n" + text, f"implicit permissions:{path.name}")
        for owner, ref in ANY_USE.findall(text):
            if not owner.startswith("./"):
                require(re.fullmatch(r"[0-9a-f]{40}", ref) is not None,
                        f"unpinned action:{path.name}:{owner}@{ref}")
        if path.name in LIVE:
            trigger = head(text)
            require("workflow_dispatch:" in trigger, f"live workflow not manual:{path.name}")
            require(not any(x in trigger for x in ("\n  push:", "\n  pull_request:", "\n  schedule:")),
                    f"automatic live trigger:{path.name}")
            require("environment: staging" in text, f"missing staging environment:{path.name}")
        if path.name == "platform-contracts.yml":
            lower = text.lower()
            for forbidden in ("configure-aws-credentials", "aws ssm", "aws sts",
                              "docker/build-push-action", "docker build", "build-release-candidate.yml"):
                require(forbidden not in lower, f"slow/live platform CI:{forbidden}")
    for name in APP_BUILDS:
        trigger = head((WORKFLOWS / name).read_text(encoding="utf-8"))
        require("'platform/**'" not in trigger and '"platform/**"' not in trigger,
                f"platform triggers app build:{name}")
    cloud = head((WORKFLOWS / "cloud-stack.yml").read_text(encoding="utf-8"))
    require("'docs/**'" not in cloud, "docs trigger cloud stack")
    require("'.github/workflows/**'" not in cloud, "workflow wildcard triggers cloud stack")


def check_iac() -> None:
    registry = json.loads((ROOT / "platform/aws/staging/release-registry.json").read_text(encoding="utf-8"))
    encoded = json.dumps(registry, sort_keys=True)
    for value in ("BucketOwnerEnforced", "PublicAccessBlockConfiguration", "VersioningConfiguration",
                  "aws:SecureTransport", "s3:PutObject", "s3:GetObject", "environment:staging"):
        require(value in encoded, f"registry control missing:{value}")
    require("ssm:SendCommand" not in encoded, "publisher can deploy")
    require("kms:Decrypt" not in encoded and "AWS::KMS::Key" not in encoded, "unjustified KMS")
    deploy = json.loads((ROOT / "platform/aws/staging/deploy-control-plane.json").read_text(encoding="utf-8"))
    encoded = json.dumps(deploy, sort_keys=True)
    require("environment:staging" in encoded, "deploy trust is not environment-bound")
    require("s3:PutObject" not in encoded, "deploy role can publish")
    require("DocumentVersionName" in deploy["Parameters"], "SSM version is not pinned")
    pca = json.loads((ROOT / "platform/aws/staging/private-ca-plan.json").read_text(encoding="utf-8"))
    require(pca["Parameters"]["PcaLiveApproval"]["Default"] == "AWS_PCA_LIVE_APPROVAL_REQUIRED", "PCA stop absent")
    for resource in pca["Resources"].values():
        if str(resource.get("Type", "")).startswith("AWS::ACMPCA::"):
            require(resource.get("Condition") == "PcaApproved", "PCA resource lacks approval condition")


def check_source() -> None:
    files = [path for path in (ROOT / "platform").rglob("*.sh") if "tests" not in path.parts]
    text = "\n".join(path.read_text(encoding="utf-8") for path in files)
    require("docker system prune" not in text, "destructive Docker prune")
    require("/srv/trinyx/pr25-" not in text, "mutable checkout dependency")
    require("curl -k" not in text and "--insecure" not in text, "TLS bypass")
    for line in text.splitlines():
        if "docker" in line and "compose" in line and "up" in line and "-d" in line:
            require("--no-deps" in line, "global compose apply")


if __name__ == "__main__":
    check_workflows()
    check_iac()
    check_source()
    print("O6_O12_STATIC_POLICY_OK")
