from __future__ import annotations

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


class IacContractTests(unittest.TestCase):
    def load(self, relative: str) -> dict:
        return json.loads((ROOT / relative).read_text(encoding="utf-8"))

    def test_post_2026_immutable_repo_oidc_subject_contract(self) -> None:
        template = self.load("platform/aws/bootstrap/github-oidc-staging-bootstrap.json")
        encoded = json.dumps(template["Resources"]["StagingGitHubOidcBootstrapRole"], sort_keys=True)
        self.assertEqual(
            "repo,context,ref,job_workflow_ref",
            template["Outputs"]["RequiredGitHubOidcSubjectTemplate"]["Value"],
        )
        self.assertIn("repo:TrinyxAI@319253481/Trinyx@1342032975", encoded)
        self.assertIn("environment:staging", encoded)
        self.assertIn("ref:refs/heads/codex/platform-release-automation", encoded)
        self.assertNotIn("repository_owner_id:", encoded)
        self.assertNotIn("repository_id:", encoded)

    def test_s3_bucket_ownership_uses_supported_cloudformation_property(self) -> None:
        templates = sorted((ROOT / "platform/aws").rglob("*.json"))
        buckets_checked = 0
        for path in templates:
            template = json.loads(path.read_text(encoding="utf-8"))
            for logical_id, resource in template.get("Resources", {}).items():
                if resource.get("Type") != "AWS::S3::Bucket":
                    continue
                buckets_checked += 1
                properties = resource.get("Properties", {})
                self.assertNotIn(
                    "BucketOwnershipControls",
                    properties,
                    f"{path.relative_to(ROOT)}:{logical_id}",
                )
                self.assertEqual(
                    "BucketOwnerEnforced",
                    properties["OwnershipControls"]["Rules"][0]["ObjectOwnership"],
                    f"{path.relative_to(ROOT)}:{logical_id}",
                )
        self.assertGreaterEqual(buckets_checked, 2)

    def test_registry_security_and_iam_separation(self) -> None:
        template = self.load("platform/aws/staging/release-registry.json")
        bucket = template["Resources"]["ReleaseRegistryBucket"]
        properties = bucket["Properties"]
        self.assertEqual("1340ad64ac358694a3f88848db4665769da82f0c", template["Parameters"]["PlatformWorkflowRef"]["Default"])
        self.assertEqual("^[0-9a-f]{40}$", template["Parameters"]["PlatformWorkflowRef"]["AllowedPattern"])
        self.assertEqual("Retain", bucket["DeletionPolicy"])
        self.assertNotIn("BucketOwnershipControls", properties)
        self.assertEqual("BucketOwnerEnforced", properties["OwnershipControls"]["Rules"][0]["ObjectOwnership"])
        self.assertTrue(all(properties["PublicAccessBlockConfiguration"].values()))
        self.assertEqual("Enabled", properties["VersioningConfiguration"]["Status"])
        self.assertEqual("AES256", properties["BucketEncryption"]["ServerSideEncryptionConfiguration"][0]["ServerSideEncryptionByDefault"]["SSEAlgorithm"])
        publisher = json.dumps(template["Resources"]["ReleasePublisherRole"], sort_keys=True)
        self.assertIn("s3:PutObject", publisher)
        self.assertNotIn("ssm:SendCommand", publisher)
        self.assertNotIn("kms:Decrypt", publisher)
        self.assertIn("environment:staging", publisher)
        self.assertIn("repo:TrinyxAI@319253481/Trinyx@1342032975", publisher)
        self.assertNotIn("repository_owner_id:", publisher)
        self.assertNotIn("repository_id:", publisher)
        self.assertIn("ref:refs/heads/codex/platform-release-automation", publisher)
        self.assertEqual(
            "repo,context,ref,job_workflow_ref",
            template["Outputs"]["RequiredGitHubOidcSubjectTemplate"]["Value"],
        )
        self.assertIn("staging-release-register-impl.yml", publisher)
        self.assertNotIn("staging-oidc-probe-impl.yml", publisher)
        bucket_policy = template["Resources"]["ReleaseRegistryBucketPolicy"]["Properties"]["PolicyDocument"]["Statement"]
        immutable = next(item for item in bucket_policy if item["Sid"] == "DenyUnconditionalImmutableReleaseWrites")
        self.assertEqual("Deny", immutable["Effect"])
        self.assertEqual({"s3:if-none-match": "true"}, immutable["Condition"]["Null"])
        for name in ("CloudReleaseRegistryReadPolicy", "PaidReleaseRegistryReadPolicy"):
            policy = json.dumps(template["Resources"][name], sort_keys=True)
            self.assertIn("s3:GetObject", policy)
            self.assertNotIn("s3:PutObject", policy)

        endpoint = template["Resources"]["S3GatewayEndpoint"]["Properties"]
        self.assertEqual("Gateway", endpoint["VpcEndpointType"])
        endpoint_statement = endpoint["PolicyDocument"]["Statement"][0]
        self.assertEqual("*", endpoint_statement["Principal"])
        principal_arns = endpoint_statement["Condition"]["ArnEquals"]["aws:PrincipalArn"]
        self.assertEqual(2, len(principal_arns))
        self.assertTrue(all("Fn::Sub" in arn for arn in principal_arns))
        self.assertIn("CloudInstanceRoleName", json.dumps(principal_arns))
        self.assertIn("PaidInstanceRoleName", json.dumps(principal_arns))
        self.assertEqual(
            {"Fn::Split": [",", {"Ref": "RouteTableIds"}]},
            endpoint["RouteTableIds"],
        )
        assertions = template["Rules"]["GatewayEndpointInputsRequired"]["Assertions"]
        self.assertTrue(any("RouteTableIds is required" in item["AssertDescription"] for item in assertions))

    def test_deploy_role_and_fixed_document_boundaries(self) -> None:
        template = self.load("platform/aws/staging/deploy-control-plane.json")
        self.assertEqual("1340ad64ac358694a3f88848db4665769da82f0c", template["Parameters"]["PlatformWorkflowRef"]["Default"])
        self.assertEqual("^[0-9a-f]{40}$", template["Parameters"]["PlatformWorkflowRef"]["AllowedPattern"])
        role = json.dumps(template["Resources"]["StagingDeployRole"], sort_keys=True)
        self.assertIn("environment:staging", role)
        self.assertIn("repo:TrinyxAI@319253481/Trinyx@1342032975", role)
        self.assertNotIn("repository_owner_id:", role)
        self.assertNotIn("repository_id:", role)
        self.assertIn("ref:refs/heads/codex/platform-release-automation", role)
        self.assertIn("staging-qualification-impl.yml", role)
        self.assertIn("staging-legacy-adopt-impl.yml", role)
        self.assertNotIn("staging-release-register-impl.yml", role)
        self.assertIn("ssm:SendCommand", role)
        self.assertIn("ssm:ListCommands", role)
        self.assertNotIn("s3:PutObject", role)
        self.assertNotIn("iam:PassRole", role)
        document = template["Resources"]["StagingDeployDocument"]["Properties"]
        self.assertEqual("NewVersion", document["UpdateMethod"])
        self.assertEqual({"Ref": "DocumentVersionName"}, document["VersionName"])
        parameters = document["Content"]["parameters"]
        self.assertIn("ControlPlaneCommit", parameters)
        self.assertNotIn("PlatformCommit", parameters)
        self.assertEqual(
            "repo,context,ref,job_workflow_ref",
            template["Outputs"]["RequiredGitHubOidcSubjectTemplate"]["Value"],
        )
        self.assertTrue(all(value["interpolationType"] == "ENV_VAR" for value in parameters.values()))
        self.assertEqual(
            ["install", "normalize-plan", "plan", "adopt", "restore-legacy", "apply", "rollback", "health"],
            parameters["Mode"]["allowedValues"],
        )
        step = document["Content"]["mainSteps"][0]
        self.assertEqual("900", step["inputs"]["timeoutSeconds"])
        command = step["inputs"]["runCommand"]
        self.assertEqual(1, len(command))
        self.assertIn("/usr/local/lib/trinyx/staging-deploy", command[0])
        self.assertNotIn("AWS-RunShellScript", json.dumps(template))

    def test_exact_runtime_plan_cardinality_and_paid_invariants(self) -> None:
        cloud = self.load("platform/bootstrap/cloud/staging/rootfs/etc/trinyx/staging/cloud/config/deployment-plan.json")
        paid = self.load("platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/deployment-plan.json")
        self.assertEqual(20, len(cloud["services"]))
        self.assertEqual(8, len(paid["services"]))
        self.assertEqual(["migration-service", "cloud-minio-init"], cloud["oneShot"]["services"])
        self.assertFalse(cloud["oneShot"]["rollbackSafe"])
        paid_runtime = (ROOT / "platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/paid-runtime.override.yml").read_text()
        self.assertIn("memory: 3G", paid_runtime)
        self.assertIn("/actuator/health/liveness", paid_runtime)

    def test_health_and_tls_prerequisites_are_materialized_without_private_keys_in_git(self) -> None:
        for role, name in (("cloud", "cloud-health-endpoints.json"), ("paid", "paid-health-endpoints.json")):
            path = ROOT / f"platform/bootstrap/{role}/staging/rootfs/etc/trinyx/staging/{role}/config/{name}"
            document = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(1, document["schemaVersion"])
            self.assertTrue(document["checks"])
            self.assertTrue(all(item["url"].startswith("https://") for item in document["checks"]))
            self.assertNotIn("PRIVATE KEY", path.read_text(encoding="utf-8"))
        paid_override = (
            ROOT / "platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/paid.override.yml"
        ).read_text(encoding="utf-8")
        self.assertIn("/etc/trinyx/staging/paid/config/tls/paid-server.key", paid_override)
        self.assertNotIn("/etc/trinyx/tls/billing-internal.key", paid_override)

    def test_pca_is_approval_gated_and_staging_only(self) -> None:
        template = self.load("platform/aws/staging/private-ca-plan.json")
        self.assertEqual("OFFLINE_SELF_MANAGED", template["Parameters"]["StagingPkiMode"]["Default"])
        self.assertEqual("AWS_PCA_LIVE_APPROVAL_REQUIRED", template["Parameters"]["PcaLiveApproval"]["Default"])
        self.assertIn("AWS_PRIVATE_CA", json.dumps(template["Conditions"]["PcaApproved"]))
        self.assertEqual("OFFLINE_SELF_MANAGED", template["Outputs"]["DefaultStagingPkiMode"]["Value"])
        for resource in template["Resources"].values():
            if resource["Type"].startswith("AWS::ACMPCA::"):
                self.assertEqual("PcaApproved", resource["Condition"])
        encoded = json.dumps(template)
        self.assertNotIn("TrinyxProduction", encoded)
        self.assertNotIn("/trinyx/production", encoded)

    def test_normalization_schema_binds_running_image_object_repo_digests(self) -> None:
        schema = self.load("platform/contracts/legacy-normalization-plan.schema.json")
        service = schema["properties"]["services"]["additionalProperties"]
        self.assertFalse(service["additionalProperties"])
        self.assertTrue(
            {"currentImageObjectId", "currentRepoDigests", "imageObjectVerified"}
            .issubset(set(service["required"]))
        )
        self.assertIn(
            "IMAGE_OBJECT_DIGEST_MISMATCH",
            service["properties"]["reasons"]["items"]["enum"],
        )
        self.assertTrue(
            {"explainedComposeConfigHash", "composeDriftClassification"}
            .issubset(set(service["required"]))
        )
        self.assertIn(
            "UNEXPLAINED_COMPOSE_CONFIG_DRIFT",
            service["properties"]["reasons"]["items"]["enum"],
        )

    def test_o6_o12_contract_schemas_are_committed_and_closed(self) -> None:
        expected = {
            "platform/contracts/deployment-record.schema.json": {
                "deploymentId", "environment", "releaseId", "environmentConfigRevision",
                "controlPlaneCommit", "previousCloudRelease", "previousPaidRelease", "state",
                "createdAt", "startedAt", "completedAt", "failure", "rollbackResult",
            },
            "platform/contracts/health-endpoints.schema.json": {"schemaVersion", "checks"},
            "platform/contracts/rollback-safety.schema.json": {
                "schemaVersion", "previousRelease", "candidateRelease", "strategy",
                "compatible", "evidenceSha256",
            },
            "platform/contracts/legacy-observation.schema.json": {
                "schemaVersion", "environment", "role", "observedAt", "releaseEligible",
                "environmentConfigRevision", "environmentConfigDigest", "composeProject", "services",
            },
            "platform/contracts/legacy-adoption.schema.json": {
                "schemaVersion", "environment", "role", "legacyActiveTarget", "baselineRelease",
                "bundleDigest", "imagesEnvSha256", "observationSha256",
                "environmentConfigRevision", "environmentConfigDigest", "controlPlaneCommit", "approvalScope",
                "approvedForPointerAdoption",
            },
            "platform/contracts/legacy-normalization-plan.schema.json": {
                "schemaVersion", "environment", "role", "baselineReleaseId", "bundleDigest",
                "deploymentId", "environmentConfigRevision", "environmentConfigDigest",
                "controlPlaneCommit", "observedAt", "composeProject", "composeVersion",
                "composeHashCapability", "composeDriftCompatibility",
                "composeCanonicalMatchCount", "composeExplainedDriftCount",
                "composeUnexplainedDriftCount", "imageCompatibility",
                "serviceCount", "recreateServices", "services",
            },
        }
        for path, required in expected.items():
            schema = self.load(path)
            self.assertEqual("https://json-schema.org/draft/2020-12/schema", schema["$schema"])
            self.assertFalse(schema["additionalProperties"])
            self.assertTrue(required.issubset(set(schema["required"])), path)
            for name, definition in schema["properties"].items():
                if "pattern" in definition:
                    field_type = definition.get("type")
                    self.assertTrue(
                        field_type == "string" or isinstance(field_type, list) and "string" in field_type,
                        f"{path}:{name}",
                    )


if __name__ == "__main__":
    unittest.main()
