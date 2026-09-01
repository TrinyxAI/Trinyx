#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TEMPLATE="$ROOT/platform/aws/staging/deploy-control-plane.json"
DISPATCHER="$ROOT/platform/host/common/staging-deploy.sh"

python3 - "$TEMPLATE" <<'PY'
import json,sys
doc=json.load(open(sys.argv[1],encoding='utf-8'))
resource=doc['Resources']['StagingDeployDocument']
properties=resource['Properties']
assert properties['Name']=='Trinyx-Staging-Deploy'
assert properties['UpdateMethod']=='NewVersion'
assert properties['VersionName']=={'Ref':'DocumentVersionName'}
parameters=properties['Content']['parameters']
assert parameters['Mode']['allowedValues']==['install','plan','apply','rollback','health']
assert parameters['Role']['allowedValues']==['cloud','paid']
assert all(value['interpolationType']=='ENV_VAR' for value in parameters.values())
steps=properties['Content']['mainSteps']
assert len(steps)==1 and steps[0]['action']=='aws:runShellScript'
command=steps[0]['inputs']['runCommand']
assert len(command)==1 and '/usr/local/lib/trinyx/staging-deploy' in command[0]
role=doc['Resources']['StagingDeployRole']['Properties']
trust=role['AssumeRolePolicyDocument']['Statement'][0]['Condition']['StringEquals']
assert trust['token.actions.githubusercontent.com:sub']=='repo:TrinyxAI@319253481/Trinyx@1342032975:environment:staging'
statements=role['Policies'][0]['PolicyDocument']['Statement']
send=next(item for item in statements if item['Sid']=='SendOnlyDedicatedDocumentToStagingHosts')
assert send['Action']=='ssm:SendCommand' and len(send['Resource'])==3
lock=next(item for item in statements if item['Sid']=='CoordinateOneNonSecretStagingDeployment')
assert set(lock['Action'])=={'ssm:PutParameter','ssm:GetParameter','ssm:DeleteParameter'}
assert 'parameter/trinyx/staging/control-plane/deployment-lock' in json.dumps(lock)
encoded=json.dumps(doc,sort_keys=True)
for forbidden in ('AWS-RunShellScript','s3:PutObject','kms:Decrypt','secretsmanager:','AdministratorAccess','ec2:TerminateInstances','iam:PassRole'):
  assert forbidden not in encoded
print('AWS_STAGING_DEPLOY_CONTROL_PLANE_CONTRACT_OK')
PY

bash -n "$DISPATCHER"
grep -Fq 'install|plan|apply|rollback|health' "$DISPATCHER"
grep -Fq 'exec /usr/bin/env python3 "$ENGINE"' "$DISPATCHER"
echo STAGING_DEPLOY_DISPATCHER_FIXED_PROGRAM_OK
