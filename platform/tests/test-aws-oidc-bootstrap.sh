#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
python3 - "$ROOT/platform/aws/bootstrap/github-oidc-staging-bootstrap.json" <<'PY'
import json,re,sys
doc=json.load(open(sys.argv[1],encoding='utf-8'))
resources=doc['Resources']
provider=resources['GitHubActionsOidcProvider']
assert provider['Type']=='AWS::IAM::OIDCProvider'
assert provider['Condition']=='CreateGitHubOidcProvider'
assert provider['Properties']['Url']=='https://token.actions.githubusercontent.com'
assert provider['Properties']['ClientIdList']==['sts.amazonaws.com']
assert 'ThumbprintList' not in provider['Properties']
role=resources['StagingGitHubOidcBootstrapRole']['Properties']
assert role['RoleName']=='TrinyxStagingGitHubOidcBootstrapRole'
assert all(key not in role for key in ('Policies','ManagedPolicyArns','PermissionsBoundary'))
statement=role['AssumeRolePolicyDocument']['Statement'][0]
expected={'Fn::Sub':'repository_owner_id:319253481:repository_id:1342032975:environment:staging:job_workflow_ref:TrinyxAI/Trinyx/.github/workflows/staging-oidc-probe-impl.yml@${PlatformWorkflowRef}'}
assert statement['Action']=='sts:AssumeRoleWithWebIdentity'
assert statement['Condition']['StringEquals']=={
  'token.actions.githubusercontent.com:aud':'sts.amazonaws.com',
  'token.actions.githubusercontent.com:sub':expected,
}
assert doc['Outputs']['TrustedGitHubSubject']['Value']==expected
encoded=json.dumps(doc,sort_keys=True)
for forbidden in ('AWS_ACCESS_KEY_ID','AWS_SECRET_ACCESS_KEY','AdministratorAccess','ssm:SendCommand','s3:PutObject','kms:Decrypt'):
  assert forbidden not in encoded
print('AWS_GITHUB_OIDC_BOOTSTRAP_ENVIRONMENT_CONTRACT_OK')
PY
