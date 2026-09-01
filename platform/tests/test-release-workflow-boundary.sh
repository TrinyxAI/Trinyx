#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
CANDIDATE="$ROOT/.github/workflows/build-release-candidate.yml"
PLATFORM="$ROOT/.github/workflows/platform-contracts.yml"
REGISTER_WRAPPER="$ROOT/.github/workflows/staging-release-register.yml"
QUALIFY_WRAPPER="$ROOT/.github/workflows/staging-qualification.yml"
ADOPT_WRAPPER="$ROOT/.github/workflows/staging-legacy-adopt.yml"
PROBE_WRAPPER="$ROOT/.github/workflows/staging-oidc-probe.yml"
REGISTER="$ROOT/.github/workflows/staging-release-register-impl.yml"
QUALIFY="$ROOT/.github/workflows/staging-qualification-impl.yml"
ADOPT="$ROOT/.github/workflows/staging-legacy-adopt-impl.yml"
PROBE="$ROOT/.github/workflows/staging-oidc-probe-impl.yml"
BRIDGE="$ROOT/.github/workflows/build-trinyx-backend.yml"

python3 - "$CANDIDATE" "$PLATFORM" "$REGISTER_WRAPPER" "$QUALIFY_WRAPPER" "$ADOPT_WRAPPER" "$PROBE_WRAPPER" "$REGISTER" "$QUALIFY" "$ADOPT" "$PROBE" "$BRIDGE" <<'PY'
from pathlib import Path
import re,sys
(candidate,platform,register_wrapper,qualify_wrapper,adopt_wrapper,probe_wrapper,
 register,qualify,adopt,probe,bridge)=(Path(x).read_text(encoding='utf-8') for x in sys.argv[1:])
candidate_head=candidate.split('\npermissions:',1)[0]
assert 'workflow_dispatch:' in candidate_head and 'workflow_call:' in candidate_head
assert not any(x in candidate_head for x in ('\n  push:','\n  pull_request:','\n  schedule:'))
for forbidden in ('aws ssm','configure-aws-credentials','TrinyxStagingDeployRole'):
    assert forbidden not in candidate
for required in ('attestations: write','id-token: write','actions/attest-build-provenance@'):
    assert required in candidate
for forbidden in ('aws ssm','aws sts','configure-aws-credentials','docker/build-push-action','build-release-candidate.yml'):
    assert forbidden not in platform.lower()

pairs=(
    (register_wrapper,'staging-release-register-impl.yml'),
    (qualify_wrapper,'staging-qualification-impl.yml'),
    (adopt_wrapper,'staging-legacy-adopt-impl.yml'),
    (probe_wrapper,'staging-oidc-probe-impl.yml'),
)
for wrapper,target in pairs:
    head=wrapper.split('\npermissions:',1)[0]
    assert 'workflow_dispatch:' in head and 'workflow_call:' not in head
    assert not any(x in head for x in ('\n  push:','\n  pull_request:','\n  schedule:'))
    assert target in wrapper
    assert 'configure-aws-credentials' not in wrapper
    assert 'environment: staging' not in wrapper

for implementation in (register,qualify,adopt,probe):
    head=implementation.split('\npermissions:',1)[0]
    assert 'workflow_call:' in head and 'workflow_dispatch:' not in head
    assert not any(x in head for x in ('\n  push:','\n  pull_request:','\n  schedule:'))
    assert 'environment: staging' in implementation
    assert 'id-token: write' in implementation
assert 'TrinyxStagingReleasePublisherRole' in register
for required in ('baseline','candidate','O11_DEPLOY_SUCCESS','O12_ROLLBACK_SUCCESS','SAME_CANDIDATE_REDEPLOY_AND_IDEMPOTENCE_SUCCESS'):
    assert required in qualify
assert 'test "$BASELINE_RELEASE_ID" != "$CANDIDATE_RELEASE_ID"' in qualify
assert 'timeout-minutes: 360' in qualify
for required in ('release.json','release-images.json','deployment-bundle.json','deployment-bundle.tar','--signer-workflow','--source-digest','--deny-self-hosted-runners'):
    assert required in register and required in qualify
for operation,target in (
    ('staging-oidc-probe','staging-oidc-probe-impl.yml'),
    ('staging-release-register','staging-release-register-impl.yml'),
    ('staging-legacy-adopt','staging-legacy-adopt-impl.yml'),
    ('staging-qualification','staging-qualification-impl.yml'),
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
