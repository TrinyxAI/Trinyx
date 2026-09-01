#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TEMPLATE="$ROOT/platform/aws/staging/deploy-control-plane.json"
DISPATCHER="$ROOT/platform/host/common/staging-deploy.sh"

python3 - "$TEMPLATE" <<'PY'
import json, re, sys
p=sys.argv[1]
d=json.load(open(p,encoding='utf-8'))
params=d['Parameters']
assert re.fullmatch(params['CloudInstanceId']['AllowedPattern'],'i-'+'a'*17)
assert re.fullmatch(params['PaidInstanceId']['AllowedPattern'],'i-'+'b'*17)
assert not re.fullmatch(params['CloudInstanceId']['AllowedPattern'],'i-123')
assert params['GitHubOidcProviderArn']['AllowedPattern'].startswith('^arn:aws:iam::')

res=d['Resources']
doc=res['StagingDeployDocument']
assert doc['Type']=='AWS::SSM::Document'
p=doc['Properties']
assert p['Name']=='Trinyx-Staging-Deploy'
assert p['DocumentType']=='Command'
assert p['TargetType']=='/AWS::EC2::Instance'
assert p['UpdateMethod']=='NewVersion'
content=p['Content']
assert content['schemaVersion']=='2.2'
pars=content['parameters']
assert pars['Mode']['allowedValues']==['plan','apply']
assert pars['Role']['allowedValues']==['cloud','paid']
assert pars['ReleaseId']['allowedPattern']=='^rel-v1-[0-9a-f]{32}$'
assert all(v['interpolationType']=='ENV_VAR' for v in pars.values())
steps=content['mainSteps']
assert len(steps)==1 and steps[0]['action']=='aws:runShellScript'
commands=steps[0]['inputs']['runCommand']
assert commands==[
 'exec /usr/bin/env bash -c \'set -euo pipefail; test -x /usr/local/lib/trinyx/staging-deploy; exec /usr/local/lib/trinyx/staging-deploy "$SSM_Mode" "$SSM_Role" "$SSM_ReleaseId"\'',
]
assert '/usr/bin/env bash -c' in commands[0]
assert '$SSM_Mode' in commands[0] and '$SSM_Role' in commands[0] and '$SSM_ReleaseId' in commands[0]

role=res['StagingDeployRole']['Properties']
trust=role['AssumeRolePolicyDocument']['Statement']
assert len(trust)==1
stmt=trust[0]
assert stmt['Action']=='sts:AssumeRoleWithWebIdentity'
assert stmt['Condition']['StringEquals']=={
 'token.actions.githubusercontent.com:aud':'sts.amazonaws.com',
 'token.actions.githubusercontent.com:sub':'repo:TrinyxAI@319253481/Trinyx@1342032975:ref:refs/heads/codex/platform-release-automation',
}
policy=role['Policies']
assert len(policy)==1
stmts=policy[0]['PolicyDocument']['Statement']
send=[s for s in stmts if s['Action']=='ssm:SendCommand']
assert len(send)==1
resources=send[0]['Resource']
assert len(resources)==3
serialized=json.dumps(d,sort_keys=True)
for required in ('document/Trinyx-Staging-Deploy','instance/${CloudInstanceId}','instance/${PaidInstanceId}'):
    assert required in serialized
for forbidden in ('AWS-RunShellScript','ssm:GetParameter','ssm:GetParameters','kms:Decrypt','secretsmanager:','AdministratorAccess','ec2:TerminateInstances','iam:PassRole'):
    assert forbidden not in serialized
read=[s for s in stmts if s['Action']=='ssm:GetCommandInvocation']
assert len(read)==1 and read[0]['Resource']=='*'
print('AWS_STAGING_DEPLOY_CONTROL_PLANE_CONTRACT_OK')
PY

bash -n "$DISPATCHER"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
BASE="$TMP/etc/trinyx/staging/cloud"
RID=rel-v1-0123456789abcdef0123456789abcdef
mkdir -p "$BASE/releases/$RID"
printf '{}\n' > "$BASE/releases/$RID/manifest.json"
printf 'IMAGE=x@sha256:%064d\n' 0 > "$BASE/releases/$RID/images.env"
mkdir -p "$BASE/deployments/legacy"
ln -s deployments/legacy "$BASE/active"

sed "s#BASE=\"/etc/trinyx/staging/\$ROLE\"#BASE=\"$TMP/etc/trinyx/staging/\$ROLE\"#" "$DISPATCHER" > "$TMP/dispatcher"
chmod +x "$TMP/dispatcher"
"$TMP/dispatcher" plan cloud "$RID" | grep -Fq "STAGING_DEPLOY_PLAN_OK role=cloud release_id=$RID"
if "$TMP/dispatcher" apply cloud "$RID" >/dev/null 2>&1; then
  echo ERROR_APPLY_MUST_REMAIN_FAIL_CLOSED >&2
  exit 1
fi
if "$TMP/dispatcher" plan cloud rel-v1-bad >/dev/null 2>&1; then
  echo ERROR_INVALID_RELEASE_ACCEPTED >&2
  exit 1
fi

echo STAGING_DEPLOY_DISPATCHER_FAIL_CLOSED_OK
