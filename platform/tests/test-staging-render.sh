#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

python3 "$ROOT/platform/render/render-environment.py" \
  --inventory "$ROOT/platform/environments/staging.env" \
  --out "$TMP/rendered"

compare() {
  local rendered="$1"
  local captured="$2"
  diff -u "$captured" "$rendered"
}

compare \
  "$TMP/rendered/cloud/runtime-static.env" \
  "$ROOT/platform/bootstrap/cloud/staging/rootfs/etc/trinyx/staging/cloud/config/runtime-static.env"

compare \
  "$TMP/rendered/cloud/cloud-paid.override.yml" \
  "$ROOT/platform/bootstrap/cloud/staging/rootfs/etc/trinyx/staging/cloud/config/cloud-paid.override.yml"

compare \
  "$TMP/rendered/paid/paid.override.yml" \
  "$ROOT/platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/paid.override.yml"

compare \
  "$TMP/rendered/paid/paid-bind.override.yml" \
  "$ROOT/platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/paid-bind.override.yml"

compare \
  "$TMP/rendered/paid/paid-runtime.override.yml" \
  "$ROOT/platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/paid-runtime.override.yml"

EXPECTED_METADATA=$(cat <<'EOF'
TRINYX_ENVIRONMENT=staging
AWS_REGION=us-east-1
CLOUD_BASE=/etc/trinyx/staging/cloud
PAID_BASE=/etc/trinyx/staging/paid
CLOUD_SSM_PATH=/trinyx/staging/cloud/
PAID_SSM_PATH=/trinyx/staging/paid/
EOF
)

ACTUAL_METADATA=$(cat "$TMP/rendered/metadata.env")
[ "$ACTUAL_METADATA" = "$EXPECTED_METADATA" ]

# Strict M3 invariants that must not drift silently.
grep -Fxq '      TRINYX_IDENTITY_SIGNING_KID: authority-identity-1' "$TMP/rendered/paid/paid.override.yml"
grep -Fxq '      TRINYX_ENTITLEMENT_SIGNING_KID: authority-entitlement-1' "$TMP/rendered/paid/paid.override.yml"
grep -Fxq '      TRINYX_S2S_SIGNING_KID: paid-authority-1' "$TMP/rendered/paid/paid.override.yml"
grep -Fxq '    network_mode: service:livecontext' "$TMP/rendered/paid/paid.override.yml"
grep -Fxq '          memory: 3G' "$TMP/rendered/paid/paid-runtime.override.yml"
grep -Fq '/actuator/health/liveness' "$TMP/rendered/paid/paid-runtime.override.yml"

# Environment inventory is explicitly non-secret.
if grep -Eiq -- \
  '(^|_)(PASSWORD|SECRET|TOKEN|PRIVATE_KEY|SIGNING_KEY|ENCRYPTION_KEY|ACCESS_KEY|API_KEY)=' \
  "$ROOT/platform/environments/staging.env"
then
  echo 'ERROR_SECRET_BEARING_STAGING_INVENTORY_KEY' >&2
  exit 1
fi

echo STAGING_RENDER_CONTRACT_OK
