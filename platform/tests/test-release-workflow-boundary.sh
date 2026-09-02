#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
CANDIDATE_WRAPPER="$ROOT/.github/workflows/build-release-candidate.yml"
CANDIDATE="$ROOT/.github/workflows/build-release-candidate-impl.yml"
CE_WRAPPER="$ROOT/.github/workflows/build-trinyx-ce-images.yml"
CE="$ROOT/.github/workflows/build-trinyx-ce-images-impl.yml"
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

python3 - "$CANDIDATE_WRAPPER" "$CANDIDATE" "$CE_WRAPPER" "$CE" "$PLATFORM" "$REGISTER_WRAPPER" "$QUALIFY_WRAPPER" "$ADOPT_WRAPPER" "$PROBE_WRAPPER" "$REGISTER" "$QUALIFY" "$ADOPT" "$PROBE" "$BRIDGE" <<'PY'
from pathlib import Path
import re,sys
BUILDER="114a2613e8090f034925a1bcf148f055653c3a06"
CONTROL_PLANE_CODE="d00143d7bbd5619e98f447ce0935fe6ea26ccd37"
PRIVILEGED_WORKFLOW="c513bb305baec25e7e70a18c7539af3b99b7bc4f"
(candidate_wrapper,candidate,ce_wrapper,ce,platform,register_wrapper,qualify_wrapper,
 adopt_wrapper,probe_wrapper,register,qualify,adopt,probe,bridge)=(
    Path(x).read_text(encoding='utf-8') for x in sys.argv[1:]
)

candidate_wrapper_head=candidate_wrapper.split('\npermissions:',1)[0]
candidate_head=candidate.split('\npermissions:',1)[0]
assert 'workflow_dispatch:' in candidate_wrapper_head and 'workflow_call:' not in candidate_wrapper_head
assert 'workflow_call:' in candidate_head and 'workflow_dispatch:' not in candidate_head
assert 'build-release-candidate-impl.yml' in candidate_wrapper
assert 'configure-aws-credentials' not in candidate_wrapper
for forbidden in ('aws ssm','configure-aws-credentials','TrinyxStagingDeployRole'):
    assert forbidden not in candidate
for required in ('attestations: write','id-token: write','actions/attest-build-provenance@'):
    assert required in candidate

ce_wrapper_head=ce_wrapper.split('\npermissions:',1)[0]
ce_head=ce.split('\npermissions:',1)[0]
assert 'workflow_dispatch:' in ce_wrapper_head and 'workflow_call:' not in ce_wrapper_head
assert 'workflow_call:' in ce_head and 'workflow_dispatch:' not in ce_head
assert 'build-trinyx-ce-images-impl.yml' in ce_wrapper

for forbidden in ('aws ssm','aws sts','configure-aws-credentials','docker/build-push-action','docker build','build-release-candidate.yml'):
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
    assert f'@{PRIVILEGED_WORKFLOW}' in wrapper
    assert 'configure-aws-credentials' not in wrapper
    assert 'environment: staging' not in wrapper

for implementation in (register,qualify,adopt,probe):
    head=implementation.split('\npermissions:',1)[0]
    assert 'workflow_call:' in head and 'workflow_dispatch:' not in head
    assert not any(x in head for x in ('\n  push:','\n  pull_request:','\n  schedule:'))
    assert 'environment: staging' in implementation
    assert 'id-token: write' in implementation
for implementation in (register,qualify,adopt):
    assert f'ref: {CONTROL_PLANE_CODE}' in implementation
    assert f'CONTROL_PLANE_COMMIT: {CONTROL_PLANE_CODE}' in implementation
    assert 'test "$(git rev-parse HEAD)" = "$CONTROL_PLANE_COMMIT"' in implementation
    assert '--platform-commit' not in implementation
for implementation in (qualify,adopt):
    assert '--control-plane-commit "$CONTROL_PLANE_COMMIT"' in implementation
    assert '--control-plane-commit "$GITHUB_SHA"' not in implementation
assert 'arn:aws:iam::001634075617:role/TrinyxStagingGitHubOidcBootstrapRole' in probe
assert 'test "$ACCOUNT" = "001634075617"' in probe
assert 'arn:aws:sts::001634075617:assumed-role/' in probe
assert 'ssm_orchestrator.py normalize-plan' in adopt
assert 'LEGACY_NORMALIZATION_PLAN_READ_ONLY_SUCCESS' in adopt
assert '--signer-digest "$SIGNER_DIGEST"' in register
assert '--signer-digest "$signer_digest"' in qualify
for frozen in (
    '33485509832','9791964215','755594078d9da7e19406e01187534132920a31f87804c1b33baa28fa96559152',
    'f3a4c1ddcf6a17bfc837071f9046ac4c38a38b47','rel-v1-b5ba70c23b9f529ac8228a7b00b4faa4',
    'c9df14dcd1dbc24b31b926d3778bef2e208b59824c78f24292608284f3579892',
):
    assert frozen in register and frozen in qualify
assert BUILDER in register and BUILDER in qualify
assert 'TrinyxStagingReleasePublisherRole' in register
for required in ('baseline','candidate','O11_DEPLOY_SUCCESS','O12_ROLLBACK_SUCCESS','SAME_CANDIDATE_REDEPLOY_AND_IDEMPOTENCE_SUCCESS'):
    assert required in qualify
assert 'test "$BASELINE_RELEASE_ID" != "$CANDIDATE_RELEASE_ID"' in qualify
assert 'timeout-minutes: 360' in qualify
for required in ('release.json','release-images.json','deployment-bundle.json','deployment-bundle.tar','--signer-workflow','--source-digest','--deny-self-hosted-runners'):
    assert required in register and required in qualify
for operation,target in (
    ('release-candidate','build-release-candidate-impl.yml'),
    ('staging-oidc-probe','staging-oidc-probe-impl.yml'),
    ('staging-release-register','staging-release-register-impl.yml'),
    ('staging-legacy-normalization-plan','staging-legacy-adopt-impl.yml'),
    ('staging-legacy-adopt','staging-legacy-adopt-impl.yml'),
    ('staging-qualification','staging-qualification-impl.yml'),
):
    assert f"inputs.operation == '{operation}'" in bridge
    assert target in bridge
    expected=BUILDER if operation=='release-candidate' else PRIVILEGED_WORKFLOW
    assert f'{target}@{expected}' in bridge
assert 'action: normalization-plan' in bridge
assert 'action: adopt' in bridge

for path in Path(sys.argv[1]).parents[2].joinpath('.github/workflows').glob('*.yml'):
    text=path.read_text(encoding='utf-8')
    for match in re.finditer(r'^\s*uses:\s*([^\s@]+)@([^\s#]+)',text,re.M):
        if not match.group(1).startswith('./'):
            assert re.fullmatch(r'[0-9a-f]{40}',match.group(2)), (path,match.group(0))
print('RELEASE_WORKFLOW_BUILD_DEPLOY_BOUNDARY_OK')
PY
