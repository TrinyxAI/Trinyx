#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
FRONTEND="$ROOT/.github/workflows/build-trinyx-frontend.yml"
CANDIDATE="$ROOT/.github/workflows/build-release-candidate.yml"
PLATFORM="$ROOT/.github/workflows/platform-contracts.yml"

for file in "$FRONTEND" "$CANDIDATE" "$PLATFORM"; do
  test -s "$file"
done

# Image build workflows must never deploy infrastructure/runtime directly.
forbidden_frontend=(
  'deploy-production:'
  'AWS-RunShellScript'
  'aws ssm send-command'
  'configure-aws-credentials'
  'arn:aws:iam::373468206405:'
  'i-025faee9cb435ad89'
  'EC2_INSTANCE_ID:'
  'AWS_REGION: eu-north-1'
)
for pattern in "${forbidden_frontend[@]}"; do
  if grep -Fq "$pattern" "$FRONTEND"; then
    echo "ERROR_LEGACY_FRONTEND_DEPLOYMENT_BOUNDARY=$pattern" >&2
    exit 1
  fi
done

# Release candidate construction can be started manually or by an explicitly
# guarded reusable-workflow caller. It must never have push/PR/schedule triggers.
python3 - "$CANDIDATE" <<'PY'
from pathlib import Path
import sys
text = Path(sys.argv[1]).read_text(encoding='utf-8')
head = text.split('\npermissions:', 1)[0]
for required in ('workflow_dispatch:', 'workflow_call:'):
    if required not in head:
        raise SystemExit('ERROR_RELEASE_CANDIDATE_TRIGGER_MISSING=' + required)
for forbidden in ('\n  push:', '\n  pull_request:', '\n  schedule:'):
    if forbidden in head:
        raise SystemExit('ERROR_RELEASE_CANDIDATE_AUTOMATIC_TRIGGER=' + forbidden.strip())
PY

# Candidate workflow may publish immutable artifacts, but may never contact AWS/SSM or deploy.
for pattern in \
  'aws ssm' \
  'AWS-RunShellScript' \
  'configure-aws-credentials' \
  'id-token: write' \
  'deploy-production:' \
  'TrinyxStagingDeployRole'
do
  if grep -Fq "$pattern" "$CANDIDATE"; then
    echo "ERROR_RELEASE_CANDIDATE_DEPLOYMENT_CAPABILITY=$pattern" >&2
    exit 1
  fi
done

grep -Fq 'O5_RELEASE_CANDIDATE_BUILD_OK' "$CANDIDATE"
grep -Fq 'assemble-release-images.py' "$CANDIDATE"
grep -Fq 'build-deployment-bundle.py' "$CANDIDATE"
grep -Fq 'release.py create' "$CANDIDATE"
grep -Fq 'trinyx-release-candidate-${{ github.sha }}' "$CANDIDATE"

# The existing Platform Contracts workflow is the manual UI gateway while the
# candidate workflow is not yet present on main. It may call the builder only
# for workflow_dispatch on the exact platform branch.
grep -Fq 'manual_release_candidate:' "$PLATFORM"
grep -Fq "if: github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/codex/platform-release-automation'" "$PLATFORM"
grep -Fq 'uses: ./.github/workflows/build-release-candidate.yml' "$PLATFORM"

python3 - "$PLATFORM" <<'PY'
from pathlib import Path
import sys
text = Path(sys.argv[1]).read_text(encoding='utf-8')
start = text.index('  manual_release_candidate:')
end = text.index('\n  oidc_sts_probe:', start)
block = text[start:end]
for required in ('contents: read', 'packages: write'):
    if required not in block:
        raise SystemExit('ERROR_MANUAL_RELEASE_GATEWAY_PERMISSION_MISSING=' + required)
for forbidden in ('id-token: write', 'aws ssm', 'TrinyxStagingDeployRole', 'AWS-RunShellScript'):
    if forbidden in block:
        raise SystemExit('ERROR_MANUAL_RELEASE_GATEWAY_DEPLOY_CAPABILITY=' + forbidden)
PY

echo RELEASE_WORKFLOW_BUILD_DEPLOY_BOUNDARY_OK
echo MANUAL_RELEASE_BUILD_GATEWAY_CONTRACT_OK
