#!/usr/bin/env python3
"""Repository-only O6-O12 policy checks: no network, image build, or AWS call."""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
PINNED_BUILDER_WORKFLOW_COMMIT = "114a2613e8090f034925a1bcf148f055653c3a06"
PINNED_CONTROL_PLANE_CODE_COMMIT = "e160e3e1c12995ad522a936c95061e03c174f8d8"
PINNED_PRIVILEGED_WORKFLOW_COMMIT = "f25b094611c01f45d3876425a86fb6fdd9b00d91"
ANY_USE = re.compile(r"^\s*uses:\s*([^\s@]+)@([^\s#]+)", re.M)
APP_BUILDS = {
    "build-release-candidate.yml", "build-trinyx-backend.yml",
    "build-trinyx-ce-images.yml", "build-trinyx-cloud-images.yml",
    "build-trinyx-frontend.yml", "cloud-stack.yml",
}
LIVE_WRAPPERS = {"staging-release-register.yml", "staging-qualification.yml", "staging-oidc-probe.yml", "staging-legacy-adopt.yml"}
LIVE_IMPLEMENTATIONS = {name.replace(".yml", "-impl.yml") for name in LIVE_WRAPPERS}
OIDC_IMPLEMENTATIONS = LIVE_IMPLEMENTATIONS | {
    "build-release-candidate-impl.yml",
    "build-trinyx-ce-images-impl.yml",
}


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
        for match in re.finditer(
            r"uses:\s*TrinyxAI/Trinyx/\.github/workflows/([A-Za-z0-9_.-]+-impl\.yml)@([^\s#]+)",
            text,
        ):
            implementation = match.group(1)
            expected_commit = (
                PINNED_PRIVILEGED_WORKFLOW_COMMIT
                if implementation in LIVE_IMPLEMENTATIONS
                else PINNED_BUILDER_WORKFLOW_COMMIT
            )
            require(
                match.group(2) == expected_commit,
                f"reusable workflow is not pinned to its reviewed commit:{path.name}:{implementation}",
            )
        if "id-token: write" in text:
            trigger = head(text)
            if path.name in OIDC_IMPLEMENTATIONS:
                require(
                    "workflow_call:" in trigger
                    and "workflow_dispatch:" not in trigger
                    and not any(x in trigger for x in ("\n  push:", "\n  pull_request:", "\n  schedule:")),
                    f"OIDC implementation is not call-only:{path.name}",
                )
            else:
                job_starts = list(re.finditer(r"^  ([A-Za-z0-9_-]+):\s*$", text, re.M))
                for index, match in enumerate(job_starts):
                    finish = job_starts[index + 1].start() if index + 1 < len(job_starts) else len(text)
                    block = text[match.start():finish]
                    if "id-token: write" not in block:
                        continue
                    require(
                        re.search(r"^    uses:\s*(?:\./\.github/workflows/|TrinyxAI/Trinyx/\.github/workflows/)", block, re.M)
                        is not None
                        and re.search(r"^    steps:\s*$", block, re.M) is None,
                        f"direct id-token job is not delegated to a reusable workflow:{path.name}:{match.group(1)}",
                    )
        if path.name in LIVE_WRAPPERS:
            trigger = head(text)
            require("workflow_dispatch:" in trigger, f"live wrapper not manual:{path.name}")
            require("workflow_call:" not in trigger, f"live wrapper exposes implementation:{path.name}")
            require(not any(x in trigger for x in ("\n  push:", "\n  pull_request:", "\n  schedule:")),
                    f"automatic live trigger:{path.name}")
            require(path.name.replace(".yml", "-impl.yml") in text,
                    f"manual wrapper does not call exact implementation:{path.name}")
            require("configure-aws-credentials" not in text,
                    f"manual wrapper assumes AWS directly:{path.name}")
        if path.name in LIVE_IMPLEMENTATIONS:
            trigger = head(text)
            require("workflow_call:" in trigger and "workflow_dispatch:" not in trigger,
                    f"live implementation is not call-only:{path.name}")
            require(not any(x in trigger for x in ("\n  push:", "\n  pull_request:", "\n  schedule:")),
                    f"automatic live implementation trigger:{path.name}")
            require("environment: staging" in text, f"missing staging environment:{path.name}")
            if path.name == "staging-oidc-probe-impl.yml":
                require(
                    "arn:aws:iam::001634075617:role/TrinyxStagingGitHubOidcBootstrapRole" in text
                    and 'test "$ACCOUNT" = "001634075617"' in text
                    and "arn:aws:sts::001634075617:assumed-role/" in text,
                    "OIDC probe does not prove the exact staging AWS account",
                )
            if path.name != "staging-oidc-probe-impl.yml":
                require(
                    f"ref: {PINNED_CONTROL_PLANE_CODE_COMMIT}" in text
                    and f"CONTROL_PLANE_COMMIT: {PINNED_CONTROL_PLANE_CODE_COMMIT}" in text
                    and 'test "$(git rev-parse HEAD)" = "$CONTROL_PLANE_COMMIT"' in text,
                    f"privileged workflow checkout is not bound to audited control-plane code:{path.name}",
                )
                require('--platform-commit' not in text, f"ambiguous platform commit argument:{path.name}")
                if path.name in {"staging-legacy-adopt-impl.yml", "staging-qualification-impl.yml"}:
                    require(
                        '--control-plane-commit "$CONTROL_PLANE_COMMIT"' in text
                        and '--control-plane-commit "$GITHUB_SHA"' not in text,
                        f"deployment audit identity comes from caller SHA:{path.name}",
                    )
                if path.name == "staging-legacy-adopt-impl.yml":
                    require(
                        "LEGACY_NORMALIZATION_PLAN_READ_ONLY_SUCCESS" in text
                        and "ssm_orchestrator.py normalize-plan" in text
                        and "if [ \"$ACTION\" = normalization-plan ]" in text,
                        "legacy normalization plan is not a separate read-only workflow action",
                    )
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
    bridge = (WORKFLOWS / "build-trinyx-backend.yml").read_text(encoding="utf-8")
    for operation, target in (
        ("staging-oidc-probe", "staging-oidc-probe-impl.yml"),
        ("staging-release-register", "staging-release-register-impl.yml"),
        ("staging-legacy-adopt", "staging-legacy-adopt-impl.yml"),
        ("staging-qualification", "staging-qualification-impl.yml"),
    ):
        require(f"inputs.operation == '{operation}'" in bridge and target in bridge,
                f"missing narrow pre-merge bridge:{operation}")
    require(
        "inputs.operation == 'release-candidate'" in bridge
        and "build-release-candidate-impl.yml" in bridge,
        "manual application build is not separated from reusable implementation",
    )
    for wrapper, implementation in (
        ("build-release-candidate.yml", "build-release-candidate-impl.yml"),
        ("build-trinyx-ce-images.yml", "build-trinyx-ce-images-impl.yml"),
    ):
        wrapper_text = (WORKFLOWS / wrapper).read_text(encoding="utf-8")
        implementation_text = (WORKFLOWS / implementation).read_text(encoding="utf-8")
        require("workflow_call:" not in head(wrapper_text), f"direct builder exposes workflow_call:{wrapper}")
        require("workflow_call:" in head(implementation_text), f"builder implementation is not reusable:{implementation}")
        require(implementation in wrapper_text, f"builder wrapper does not call implementation:{wrapper}")
    qualification = (WORKFLOWS / "staging-qualification-impl.yml").read_text(encoding="utf-8")
    require("timeout-minutes: 360" in qualification, "qualification lacks maximum bounded job budget")


def check_iac() -> None:
    bootstrap = json.loads(
        (ROOT / "platform/aws/bootstrap/github-oidc-staging-bootstrap.json").read_text(encoding="utf-8")
    )
    registry = json.loads((ROOT / "platform/aws/staging/release-registry.json").read_text(encoding="utf-8"))
    s3_bucket_count = 0
    for template_path in sorted((ROOT / "platform/aws").rglob("*.json")):
        template = json.loads(template_path.read_text(encoding="utf-8"))
        for logical_id, resource in template.get("Resources", {}).items():
            if resource.get("Type") != "AWS::S3::Bucket":
                continue
            s3_bucket_count += 1
            properties = resource.get("Properties", {})
            identity = f"{template_path.relative_to(ROOT).as_posix()}:{logical_id}"
            require(
                "BucketOwnershipControls" not in properties,
                f"unsupported CloudFormation S3 property BucketOwnershipControls:{identity}",
            )
            require(
                properties.get("OwnershipControls", {}).get("Rules", [{}])[0].get("ObjectOwnership")
                == "BucketOwnerEnforced",
                f"S3 Object Ownership is not BucketOwnerEnforced:{identity}",
            )
    require(s3_bucket_count >= 2, "expected staging S3 bucket contracts are missing")
    for name, template in (("bootstrap", bootstrap), ("registry", registry)):
        workflow_ref = template["Parameters"]["PlatformWorkflowRef"]
        require(
            workflow_ref["Default"] == PINNED_PRIVILEGED_WORKFLOW_COMMIT
            and workflow_ref["AllowedPattern"] == "^[0-9a-f]{40}$",
            f"{name} OIDC workflow identity is not immutable",
        )
    canonical_subject_template = "repo,context,ref,job_workflow_ref"
    immutable_repo_subject = "repo:TrinyxAI@319253481/Trinyx@1342032975"
    for name, template in (("bootstrap", bootstrap), ("registry", registry)):
        require(
            template["Outputs"]["RequiredGitHubOidcSubjectTemplate"]["Value"] == canonical_subject_template,
            f"{name} OIDC subject-template output drift",
        )
        encoded_template = json.dumps(template, sort_keys=True)
        require(
            immutable_repo_subject in encoded_template
            and "repository_owner_id:" not in encoded_template
            and "repository_id:" not in encoded_template,
            f"{name} does not use the post-2026 immutable GitHub repo subject",
        )
    encoded = json.dumps(registry, sort_keys=True)
    for value in ("BucketOwnerEnforced", "PublicAccessBlockConfiguration", "VersioningConfiguration",
                  "aws:SecureTransport", "s3:PutObject", "s3:GetObject", "environment:staging"):
        require(value in encoded, f"registry control missing:{value}")
    require("ssm:SendCommand" not in encoded, "publisher can deploy")
    require("kms:Decrypt" not in encoded and "AWS::KMS::Key" not in encoded, "unjustified KMS")
    require("s3:if-none-match" in encoded, "S3 immutability is not server-enforced")
    require("RouteTableIds is required" in encoded and "Fn::Split" in encoded,
            "gateway endpoint route tables are not fail-closed")
    require("job_workflow_ref" in encoded and "repo:TrinyxAI@319253481/Trinyx@1342032975" in encoded
            and "ref:refs/heads/codex/platform-release-automation" in encoded,
            "publisher OIDC principal lacks immutable workflow and caller-ref identity")
    deploy = json.loads((ROOT / "platform/aws/staging/deploy-control-plane.json").read_text(encoding="utf-8"))
    require(
        deploy["Outputs"]["RequiredGitHubOidcSubjectTemplate"]["Value"] == canonical_subject_template,
        "deploy OIDC subject-template output drift",
    )
    encoded = json.dumps(deploy, sort_keys=True)
    require(
        immutable_repo_subject in encoded
        and "repository_owner_id:" not in encoded
        and "repository_id:" not in encoded,
        "deploy does not use the post-2026 immutable GitHub repo subject",
    )
    workflow_ref = deploy["Parameters"]["PlatformWorkflowRef"]
    require(
        workflow_ref["Default"] == PINNED_PRIVILEGED_WORKFLOW_COMMIT
        and workflow_ref["AllowedPattern"] == "^[0-9a-f]{40}$",
        "deploy OIDC workflow identity is not immutable",
    )
    require("environment:staging" in encoded, "deploy trust is not environment-bound")
    require("ref:refs/heads/codex/platform-release-automation" in encoded,
            "deploy trust is not restricted to the reviewed caller branch")
    require("staging-qualification-impl.yml" in encoded and "staging-legacy-adopt-impl.yml" in encoded
            and "staging-release-register-impl.yml" not in encoded,
            "deploy OIDC principal boundary is not workflow-specific")
    require("s3:PutObject" not in encoded, "deploy role can publish")
    require("DocumentVersionName" in deploy["Parameters"], "SSM version is not pinned")
    parameters = deploy["Resources"]["StagingDeployDocument"]["Properties"]["Content"]["parameters"]
    require("ControlPlaneCommit" in parameters and "PlatformCommit" not in parameters,
            "SSM deployment identity is not explicitly control-plane scoped")
    step = deploy["Resources"]["StagingDeployDocument"]["Properties"]["Content"]["mainSteps"][0]
    require(step["inputs"]["timeoutSeconds"] == "900", "SSM execution timeout drift")
    orchestrator = (ROOT / "platform/automation/ssm_orchestrator.py").read_text(encoding="utf-8")
    require("SSM_EXECUTION_TIMEOUT_SECONDS = 900" in orchestrator, "orchestrator execution budget drift")
    require("SSM_POLL_GRACE_SECONDS = 60" in orchestrator, "orchestrator polling grace drift")
    require("range(90)" not in orchestrator, "orchestrator restored magic 180-second polling loop")
    require("STALE_LOCK_LOOKBACK = dt.timedelta(minutes=5)" in orchestrator,
            "stale-lock AWS clock-skew margin missing")
    require(
        "validate_normalization_protocol" in orchestrator
        and "normalization report SHA-256 mismatch" in orchestrator
        and "normalization service inventory is incomplete" in orchestrator,
        "normalization receiver does not authenticate report SHA/cardinality",
    )
    dispatcher = (ROOT / "platform/host/common/staging-deploy.sh").read_text(encoding="utf-8")
    require("--expected-bundle-digest" in dispatcher, "SSM bundle digest is not checked after install")
    require("normalize-plan" in dispatcher and "legacy-normalization-plan" in dispatcher,
            "read-only legacy normalization dispatcher is missing")
    pca = json.loads((ROOT / "platform/aws/staging/private-ca-plan.json").read_text(encoding="utf-8"))
    require(pca["Parameters"]["StagingPkiMode"]["Default"] == "OFFLINE_SELF_MANAGED",
            "offline staging PKI is not the default")
    require(pca["Parameters"]["PcaLiveApproval"]["Default"] == "AWS_PCA_LIVE_APPROVAL_REQUIRED", "PCA stop absent")
    require("AWS_PRIVATE_CA" in json.dumps(pca["Conditions"]["PcaApproved"]),
            "optional paid PCA mode is not separately gated")
    engine = (ROOT / "platform/automation/deploy_engine.py").read_text(encoding="utf-8")
    require("LEGACY_BASELINE_RUNTIME_IMAGE_MISMATCH" in engine and "repoDigests" in engine,
            "legacy adoption is not bound to runtime image digests")
    require("environment_config_digest" in engine,
            "legacy adoption is not bound to materialized non-secret config")
    require("LEGACY_BASELINE_EFFECTIVE_CONFIG_MISMATCH" in engine
            and "compose_config_hashes" in engine and "current_compose_runtime" in engine,
            "legacy adoption is not bound to effective container Compose config")
    normalizer = (ROOT / "platform/automation/legacy_normalization_plan.py").read_text(encoding="utf-8")
    require("composeHashCompatibility" in normalizer and "MUTABLE_CHECKOUT_MOUNT" in normalizer
            and "recreateRequired" in normalizer and "compose_version" in normalizer,
            "legacy normalization plan lacks runtime/config/mount compatibility evidence")
    require("SSM_STDOUT_MAX_BYTES = 20_000" in normalizer
            and "render_ssm_protocol" in normalizer
            and "UNQUALIFIED_EXCESSIVE_DRIFT" in normalizer,
            "legacy normalization report is not bounded/fail-closed")
    require(
        "docker\", \"image\", \"inspect" in normalizer
        and "RepoDigests" in normalizer
        and "IMAGE_OBJECT_DIGEST_MISMATCH" in normalizer,
        "legacy normalization image proof is not bound to the running image object",
    )
    for binding in ("--expected-bundle-digest", "--deployment-id",
                    "--environment-config-revision", "--control-plane-commit"):
        require(binding in normalizer and binding in dispatcher,
                f"legacy normalization audit binding missing:{binding}")
    require("adapter.materialize(" not in normalizer,
            "legacy normalization plan must not materialize or mutate host state")
    runbook = (ROOT / "docs/deployment/o6-o12-staging-automation.md").read_text(encoding="utf-8")
    require(
        '"use_immutable_subject":true' in runbook
        and "/repos/TrinyxAI/Trinyx/actions/oidc/customization/sub" in runbook
        and "bounded normalization protocol" in runbook
        and "Review the JSON output" not in runbook,
        "runbook lacks explicit immutable OIDC GET verification/bounded protocol",
    )
    require(
        runbook.index("Install the canonical baseline on Cloud and Paid")
        < runbook.index("staging-legacy-normalization-plan")
        < runbook.index("Only now capture schema-v3 baseline observations"),
        "runbook does not enforce normalization before baseline observation",
    )
    registry_client = (ROOT / "platform/automation/release_registry.py").read_text(encoding="utf-8")
    require(
        f'APPROVED_BUILDER_WORKFLOW_COMMIT = "{PINNED_BUILDER_WORKFLOW_COMMIT}"' in registry_client
        and "historical builder compatibility is restricted to the frozen candidate" in registry_client,
        "builder provenance compatibility is not exact",
    )
    for resource in pca["Resources"].values():
        if str(resource.get("Type", "")).startswith("AWS::ACMPCA::"):
            require(resource.get("Condition") == "PcaApproved", "PCA resource lacks approval condition")


def check_source() -> None:
    platform = ROOT / "platform"
    checkout_needle = "/srv/trinyx/" + "pr25-"
    checkout_offenders: list[str] = []
    for path in platform.rglob("*"):
        if not path.is_file() or "tests" in path.parts or "__pycache__" in path.parts:
            continue
        try:
            content = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        if checkout_needle in content:
            checkout_offenders.append(path.relative_to(ROOT).as_posix())
    require(not checkout_offenders, "mutable checkout dependency:" + ",".join(checkout_offenders))

    shell_files = [path for path in platform.rglob("*.sh") if "tests" not in path.parts]
    shell_text = "\n".join(path.read_text(encoding="utf-8") for path in shell_files)
    require("docker system prune" not in shell_text, "destructive Docker prune")
    require("curl -k" not in shell_text and "--insecure" not in shell_text, "TLS bypass")
    for line in shell_text.splitlines():
        if "docker" in line and "compose" in line and "up" in line and "-d" in line:
            require("--no-deps" in line, "global compose apply")


if __name__ == "__main__":
    check_workflows()
    check_iac()
    check_source()
    print("O6_O12_STATIC_POLICY_OK")
