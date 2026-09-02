#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TEMPLATE="$ROOT/platform/aws/staging/deploy-control-plane.json"
DISPATCHER="$ROOT/platform/host/common/staging-deploy.sh"

python3 - "$TEMPLATE" <<'PY'
import json,sys
doc=json.load(open(sys.argv[1],encoding='utf-8'))
assert doc['Parameters']['PlatformWorkflowRef']['Default']=='c513bb305baec25e7e70a18c7539af3b99b7bc4f'
assert doc['Parameters']['PlatformWorkflowRef']['AllowedPattern']=='^[0-9a-f]{40}$'
resource=doc['Resources']['StagingDeployDocument']
properties=resource['Properties']
assert properties['Name']=='Trinyx-Staging-Deploy'
assert properties['UpdateMethod']=='NewVersion'
assert properties['VersionName']=={'Ref':'DocumentVersionName'}
parameters=properties['Content']['parameters']
assert parameters['Mode']['allowedValues']==['install','normalize-plan','plan','adopt','restore-legacy','apply','rollback','health']
assert parameters['Role']['allowedValues']==['cloud','paid']
assert all(value['interpolationType']=='ENV_VAR' for value in parameters.values())
assert 'ControlPlaneCommit' in parameters and 'PlatformCommit' not in parameters
assert doc['Outputs']['RequiredGitHubOidcSubjectTemplate']['Value']=='repository_owner_id,repository_id,context,ref,job_workflow_ref'
steps=properties['Content']['mainSteps']
assert len(steps)==1 and steps[0]['action']=='aws:runShellScript'
assert steps[0]['inputs']['timeoutSeconds']=='900'
command=steps[0]['inputs']['runCommand']
assert len(command)==1 and '/usr/local/lib/trinyx/staging-deploy' in command[0]
assert '$SSM_ControlPlaneCommit' in command[0] and '$SSM_PlatformCommit' not in command[0]
role=doc['Resources']['StagingDeployRole']['Properties']
trust=role['AssumeRolePolicyDocument']['Statement'][0]['Condition']['StringEquals']
subjects=trust['token.actions.githubusercontent.com:sub']
assert len(subjects)==2
assert all('repository_owner_id:319253481:repository_id:1342032975:environment:staging:ref:refs/heads/codex/platform-release-automation:job_workflow_ref:' in json.dumps(item) for item in subjects)
assert 'staging-qualification-impl.yml' in json.dumps(subjects)
assert 'staging-legacy-adopt-impl.yml' in json.dumps(subjects)
assert 'staging-release-register-impl.yml' not in json.dumps(subjects)
statements=role['Policies'][0]['PolicyDocument']['Statement']
send=next(item for item in statements if item['Sid']=='SendOnlyDedicatedDocumentToStagingHosts')
assert send['Action']=='ssm:SendCommand' and len(send['Resource'])==3
lock=next(item for item in statements if item['Sid']=='CoordinateOneNonSecretStagingDeployment')
assert set(lock['Action'])=={'ssm:PutParameter','ssm:GetParameter','ssm:DeleteParameter'}
assert 'parameter/trinyx/staging/control-plane/deployment-lock' in json.dumps(lock)
inspect=next(item for item in statements if item['Sid']=='InspectCommandsBeforeManualLockBreak')
assert inspect=={'Sid':'InspectCommandsBeforeManualLockBreak','Effect':'Allow','Action':'ssm:ListCommands','Resource':'*'}
encoded=json.dumps(doc,sort_keys=True)
for forbidden in ('AWS-RunShellScript','s3:PutObject','kms:Decrypt','secretsmanager:','AdministratorAccess','ec2:TerminateInstances','iam:PassRole'):
  assert forbidden not in encoded
print('AWS_STAGING_DEPLOY_CONTROL_PLANE_CONTRACT_OK')
PY

bash -n "$DISPATCHER"
grep -Fq 'install|normalize-plan|plan|adopt|restore-legacy|apply|rollback|health' "$DISPATCHER"
grep -Fq -- '--expected-bundle-digest "$BUNDLE_DIGEST"' "$DISPATCHER"
grep -Fq 'exec /usr/bin/env python3 "$ENGINE"' "$DISPATCHER"
echo STAGING_DEPLOY_DISPATCHER_FIXED_PROGRAM_OK
