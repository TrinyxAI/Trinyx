from __future__ import annotations

import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / ".github" / "workflows" / "staging-legacy-adopt-impl.yml"
BRIDGE = ROOT / ".github" / "workflows" / "build-trinyx-backend.yml"
DISPATCHER = ROOT / "platform" / "host" / "common" / "staging-deploy.sh"
INSTALLER = ROOT / "platform" / "install" / "install-release.py"
SSM_TEMPLATE = ROOT / "platform" / "aws" / "staging" / "deploy-control-plane.json"
C4 = "bdbdc0068b08f818881fecc96d6cb0770b972ec4"


class StagingReleaseInstallWorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.bridge = BRIDGE.read_text(encoding="utf-8")
        cls.dispatcher = DISPATCHER.read_text(encoding="utf-8")
        cls.installer = INSTALLER.read_text(encoding="utf-8")
        cls.template = json.loads(SSM_TEMPLATE.read_text(encoding="utf-8"))

    def install_block(self) -> str:
        matches = re.findall(
            r'if \[ "\$ACTION" = install \]; then\n(?P<body>.*?)\n          fi',
            self.workflow,
            re.S,
        )
        self.assertEqual(2, len(matches))
        return matches[-1]

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

    def test_install_inputs_are_closed_and_previous_releases_are_required(self) -> None:
        for name in (
            "baseline_release_id",
            "baseline_bundle_digest",
            "environment_config_revision",
            "registry_bucket",
            "deploy_role_arn",
            "document_version",
            "previous_cloud_release",
            "previous_paid_release",
        ):
            self.assertIn(f"{name}:", self.workflow)
        self.assertIn('[[ "$PREVIOUS_CLOUD_RELEASE" =~ ^rel-v1-[0-9a-f]{32}$ ]]', self.workflow)
        self.assertIn('[[ "$PREVIOUS_PAID_RELEASE" =~ ^rel-v1-[0-9a-f]{32}$ ]]', self.workflow)
        self.assertIn('--previous-cloud "$PREVIOUS_CLOUD_RELEASE"', self.install_block())
        self.assertIn('--previous-paid "$PREVIOUS_PAID_RELEASE"', self.install_block())
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

    def test_existing_actions_remain_separate_and_no_image_or_live_shortcut_exists(self) -> None:
        self.assertIn('if [ "$ACTION" = normalization-plan ]; then', self.workflow)
        self.assertIn("ssm_orchestrator.py adopt", self.workflow)
        self.assertNotIn("docker build", self.workflow.lower())
        self.assertNotIn("docker pull", self.workflow.lower())
        self.assertNotIn("docker push", self.workflow.lower())
        self.assertNotIn("staging-release-install", self.bridge)


if __name__ == "__main__":
    unittest.main()
