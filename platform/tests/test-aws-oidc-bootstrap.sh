#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TEMPLATE="$ROOT/platform/aws/bootstrap/github-oidc-staging-bootstrap.json"

python3 - "$TEMPLATE" <<'PY'
import json
import re
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as fh:
    doc = json.load(fh)

resources = doc["Resources"]
provider = resources["GitHubActionsOidcProvider"]
role = resources["StagingGitHubOidcBootstrapRole"]

assert provider["Type"] == "AWS::IAM::OIDCProvider"
assert provider["Condition"] == "CreateGitHubOidcProvider"
props = provider["Properties"]
assert props["Url"] == "https://token.actions.githubusercontent.com"
assert props["ClientIdList"] == ["sts.amazonaws.com"]
assert "ThumbprintList" not in props

role_props = role["Properties"]
assert role["Type"] == "AWS::IAM::Role"
assert role_props["RoleName"] == "TrinyxStagingGitHubOidcBootstrapRole"
assert role_props["MaxSessionDuration"] == 3600

# Identity-only means exactly that: no deployment/read/write policy is attached yet.
for forbidden in ("Policies", "ManagedPolicyArns", "PermissionsBoundary"):
    assert forbidden not in role_props, f"unexpected bootstrap permission field: {forbidden}"

trust = role_props["AssumeRolePolicyDocument"]
assert trust["Version"] == "2012-10-17"
assert len(trust["Statement"]) == 1
stmt = trust["Statement"][0]
assert stmt["Effect"] == "Allow"
assert stmt["Action"] == "sts:AssumeRoleWithWebIdentity"

principal = stmt["Principal"]["Federated"]
assert principal == {
    "Fn::If": [
        "CreateGitHubOidcProvider",
        {"Ref": "GitHubActionsOidcProvider"},
        {"Ref": "ExistingGitHubOidcProviderArn"},
    ]
}

conditions = stmt["Condition"]
assert set(conditions) == {"StringEquals"}
exact = conditions["StringEquals"]
assert exact["token.actions.githubusercontent.com:aud"] == "sts.amazonaws.com"
expected_sub = "repo:TrinyxAI@319253481/Trinyx@1342032975:ref:refs/heads/codex/platform-release-automation"
assert exact["token.actions.githubusercontent.com:sub"] == expected_sub
assert "*" not in expected_sub and "?" not in expected_sub

param = doc["Parameters"]["ExistingGitHubOidcProviderArn"]
assert param["Default"] == ""
pattern = re.compile(param["AllowedPattern"])
assert pattern.fullmatch("")
assert pattern.fullmatch("arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com")
assert not pattern.fullmatch("arn:aws:iam::123456789012:oidc-provider/evil.example")

serialized = json.dumps(doc, sort_keys=True)
for forbidden in (
    "AWS_ACCESS_KEY_ID",
    "AWS_SECRET_ACCESS_KEY",
    "AdministratorAccess",
    "ssm:SendCommand",
    "ssm:GetParameter",
    "kms:Decrypt",
):
    assert forbidden not in serialized, f"forbidden bootstrap capability present: {forbidden}"

outputs = doc["Outputs"]
assert outputs["TrustedGitHubSubject"]["Value"] == expected_sub
print("AWS_GITHUB_OIDC_BOOTSTRAP_CONTRACT_OK")
PY
