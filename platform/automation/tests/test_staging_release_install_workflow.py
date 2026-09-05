from __future__ import annotations

import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / ".github" / "workflows" / "staging-legacy-adopt-impl.yml"
BRIDGE = ROOT / ".github" / "workflows" / "build-trinyx-backend.yml"
WRAPPER = ROOT / ".github" / "workflows" / "staging-legacy-adopt.yml"
DISPATCHER = ROOT / "platform" / "host" / "common" / "staging-deploy.sh"
INSTALLER = ROOT / "platform" / "install" / "install-release.py"
SSM_TEMPLATE = ROOT / "platform" / "aws" / "staging" / "deploy-control-plane.json"
C4 = "c41b17cdabb48f3405ebd8a612477fde76bc5818"
W11 = "ec9a71e7302a4fd7f5b60475f0951c157465f3d8"


class StagingReleaseInstallWorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.bridge = BRIDGE.read_text(encoding="utf-8")
        cls.wrapper = WRAPPER.read_text(encoding="utf-8")
        cls.dispatcher = DISPATCHER.read_text(encoding="utf-8")
        cls.installer = INSTALLER.read_text(encoding="utf-8")
        cls.template = json.loads(SSM_TEMPLATE.read_text(encoding="utf-8"))

    def install_block(self) -> str:
        self.assertEqual(1, self.workflow.count('if [ "$ACTION" = install ]; then'))
        match = re.search(
            r'if \[ "\$ACTION" = install \]; then\n(?P<body>.*?)\n          fi',
            self.workflow,
            re.S,
        )
        self.assertIsNotNone(match)
        return match.group("body")

    def test_install_is_explicit_call_only_staging_action(self) -> None:
        head = self.workflow.split("\npermissions:", 1)[0]
        self.assertIn("workflow_call:", head)
        self.assertNotIn("workflow_dispatch:", head)
        self.assertIn('[[ "$ACTION" =~ ^(install|normalization-plan|adopt)$ ]]', self.workflow)
        self.assertIn("environment: staging", self.workflow)
        self.assertIn(f"ref: {C4}", self.workflow)
        self.assertIn(f"CONTROL_PLANE_COMMIT: {C4}", self.workflow)
        self.assertNotIn("environment: production", self.workflow)

    def test_install_block_runs_install_and_nothing_afterward(self) -> None:
        block = self.install_block()
        self.assertEqual(1, block.count("ssm_orchestrator.py"))
        self.assertIn("ssm_orchestrator.py install", block)
        self.assertIn("STAGING_RELEASE_INSTALL_ONLY_SUCCESS", block)
        self.assertIn("exit 0", block)
        for forbidden in ("normalize-plan", " adopt", " deploy", " apply", " rollback", " health"):
            self.assertNotIn(forbidden, block)

    def test_install_interface_does_not_claim_unenforced_active_precondition(self) -> None:
        for name in (
            "baseline_release_id",
            "baseline_bundle_digest",
            "environment_config_revision",
            "registry_bucket",
            "deploy_role_arn",
            "document_version",
        ):
            self.assertIn(f"{name}:", self.workflow)
        self.assertNotIn("previous_cloud_release:", self.workflow)
        self.assertNotIn("previous_paid_release:", self.workflow)
        self.assertNotIn("PREVIOUS_CLOUD_RELEASE", self.workflow)
        self.assertNotIn("PREVIOUS_PAID_RELEASE", self.workflow)
        block = self.install_block()
        self.assertIn("active-release preconditions", block)
        self.assertIn('--previous-cloud "$BASELINE_RELEASE_ID"', block)
        self.assertIn('--previous-paid "$BASELINE_RELEASE_ID"', block)
        self.assertIn("arn:aws:iam::001634075617:role/TrinyxStagingDeployRole", self.workflow)

    def test_ssm_document_remains_fixed_program(self) -> None:
        content = self.template["Resources"]["StagingDeployDocument"]["Properties"]["Content"]
        self.assertIn("install", content["parameters"]["Mode"]["allowedValues"])
        self.assertEqual(1, len(content["mainSteps"]))
        command = content["mainSteps"][0]["inputs"]["runCommand"]
        self.assertEqual(1, len(command))
        self.assertIn("/usr/local/lib/trinyx/staging-deploy", command[0])
        self.assertNotIn("commands", content["parameters"])
        self.assertNotIn("shell", content["parameters"])

    def test_install_dispatcher_cannot_change_active(self) -> None:
        install_branch = self.dispatcher.split('if [ "$MODE" = install ]; then', 1)[1].split("\nfi", 1)[0]
        self.assertIn("/usr/local/lib/trinyx/install-release.py", install_branch)
        self.assertNotIn("ln -s", install_branch)
        self.assertNotIn("os.replace", install_branch)
        self.assertIn("active_before", self.installer)
        self.assertIn("active_after", self.installer)
        self.assertIn('fail("release installation changed active deployment")', self.installer)

    def test_install_only_bridge_is_explicit_closed_and_isolated(self) -> None:
        self.assertEqual(1, self.bridge.count("\n  staging_release_install:\n"))
        block = self.bridge.split("\n  staging_release_install:\n", 1)[1].split(
            "\n  staging_legacy_adopt:\n", 1
        )[0]
        self.assertIn("inputs.operation == 'staging-release-install'", block)
        self.assertIn("github.event_name == 'workflow_dispatch'", block)
        self.assertIn("github.ref == 'refs/heads/codex/platform-release-automation'", block)
        self.assertIn(f"staging-legacy-adopt-impl.yml@{W11}", block)
        self.assertIn("action: install", block)
        with_block = block.split("\n    with:\n", 1)[1]
        self.assertEqual(
            {
                "action",
                "baseline_release_id",
                "baseline_bundle_digest",
                "environment_config_revision",
                "registry_bucket",
                "deploy_role_arn",
                "document_version",
            },
            set(re.findall(r"^      ([a-z_]+):", with_block, re.M)),
        )
        self.assertNotIn("previous_cloud_release", block)
        self.assertNotIn("previous_paid_release", block)
        for forbidden in ("normalization-plan", "adopt", "health", "deploy", "apply", "rollback"):
            self.assertNotIn(f"action: {forbidden}", block)
        self.assertNotIn("environment: production", block)

    def test_wrapper_exposes_three_separate_closed_actions(self) -> None:
        self.assertIn("          - install", self.wrapper)
        self.assertIn("          - normalization-plan", self.wrapper)
        self.assertIn("          - adopt", self.wrapper)
        self.assertIn(f"staging-legacy-adopt-impl.yml@{W11}", self.wrapper)
        self.assertNotIn("previous_cloud_release", self.wrapper)
        self.assertNotIn("previous_paid_release", self.wrapper)

    def test_existing_actions_remain_separate_and_no_image_shortcut_exists(self) -> None:
        self.assertIn('if [ "$ACTION" = normalization-plan ]; then', self.workflow)
        self.assertIn("ssm_orchestrator.py adopt", self.workflow)
        self.assertNotIn("docker build", self.workflow.lower())
        self.assertNotIn("docker pull", self.workflow.lower())
        self.assertNotIn("docker push", self.workflow.lower())


if __name__ == "__main__":
    unittest.main()
