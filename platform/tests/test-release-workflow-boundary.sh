#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
FRONTEND="$ROOT/.github/workflows/build-trinyx-frontend.yml"
CANDIDATE="$ROOT/.github/workflows/build-release-candidate.yml"

for file in "$FRONTEND" "$CANDIDATE"; do
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

# Release candidate construction is manual-only: no push/PR/schedule trigger.
python3 - "$CANDIDATE" <<'PY'
from pathlib import Path
import sys
text = Path(sys.argv[1]).read_text(encoding='utf-8')
head = text.split('\npermissions:', 1)[0]
if 'workflow_dispatch:' not in head:
    raise SystemExit('ERROR_RELEASE_CANDIDATE_NOT_MANUAL')
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

echo RELEASE_WORKFLOW_BUILD_DEPLOY_BOUNDARY_OK
