#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
CANDIDATE="$ROOT/.github/workflows/build-release-candidate.yml"
PLATFORM="$ROOT/.github/workflows/platform-contracts.yml"
REGISTER="$ROOT/.github/workflows/staging-release-register.yml"
QUALIFY="$ROOT/.github/workflows/staging-qualification.yml"
ADOPT="$ROOT/.github/workflows/staging-legacy-adopt.yml"
BRIDGE="$ROOT/.github/workflows/build-trinyx-backend.yml"

python3 - "$CANDIDATE" "$PLATFORM" "$REGISTER" "$QUALIFY" "$ADOPT" "$BRIDGE" <<'PY'
from pathlib import Path
import re,sys
candidate,platform,register,qualify,adopt,bridge=(Path(x).read_text(encoding='utf-8') for x in sys.argv[1:])
candidate_head=candidate.split('\npermissions:',1)[0]
assert 'workflow_dispatch:' in candidate_head and 'workflow_call:' in candidate_head
assert not any(x in candidate_head for x in ('\n  push:','\n  pull_request:','\n  schedule:'))
for forbidden in ('aws ssm','configure-aws-credentials','TrinyxStagingDeployRole'):
    assert forbidden not in candidate
for required in ('attestations: write','id-token: write','actions/attest-build-provenance@'):
    assert required in candidate
for forbidden in ('aws ssm','aws sts','configure-aws-credentials','docker/build-push-action','build-release-candidate.yml'):
    assert forbidden not in platform.lower()
for text in (register,qualify,adopt):
    head=text.split('\npermissions:',1)[0]
    assert 'workflow_dispatch:' in head
    assert not any(x in head for x in ('\n  push:','\n  pull_request:','\n  schedule:'))
    assert 'environment: staging' in text
    assert 'id-token: write' in text
assert 'TrinyxStagingReleasePublisherRole' in register
for required in ('baseline','candidate','O11_DEPLOY_SUCCESS','O12_ROLLBACK_SUCCESS','SAME_CANDIDATE_REDEPLOY_AND_IDEMPOTENCE_SUCCESS'):
    assert required in qualify
assert 'test "$BASELINE_RELEASE_ID" != "$CANDIDATE_RELEASE_ID"' in qualify
assert 'timeout-minutes: 360' in qualify
for required in ('release.json','release-images.json','deployment-bundle.json','deployment-bundle.tar','--signer-workflow','--source-digest','--deny-self-hosted-runners'):
    assert required in register and required in qualify
for operation,target in (
    ('staging-oidc-probe','staging-oidc-probe.yml'),
    ('staging-release-register','staging-release-register.yml'),
    ('staging-legacy-adopt','staging-legacy-adopt.yml'),
    ('staging-qualification','staging-qualification.yml'),
):
    assert f"inputs.operation == '{operation}'" in bridge
    assert target in bridge
assert "inputs.operation == 'release-candidate'" in bridge
for path in Path(sys.argv[1]).parents[2].joinpath('.github/workflows').glob('*.yml'):
    text=path.read_text(encoding='utf-8')
    for match in re.finditer(r'^\s*uses:\s*([^\s@]+)@([^\s#]+)',text,re.M):
        if not match.group(1).startswith('./'):
            assert re.fullmatch(r'[0-9a-f]{40}',match.group(2)), (path,match.group(0))
print('RELEASE_WORKFLOW_BUILD_DEPLOY_BOUNDARY_OK')
PY
