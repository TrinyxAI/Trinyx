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

compare "$TMP/rendered/cloud/runtime-static.env" "$ROOT/platform/bootstrap/cloud/staging/rootfs/etc/trinyx/staging/cloud/config/runtime-static.env"
compare "$TMP/rendered/cloud/cloud-paid.override.yml" "$ROOT/platform/bootstrap/cloud/staging/rootfs/etc/trinyx/staging/cloud/config/cloud-paid.override.yml"
compare "$TMP/rendered/paid/paid.override.yml" "$ROOT/platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/paid.override.yml"
compare "$TMP/rendered/paid/paid-bind.override.yml" "$ROOT/platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/paid-bind.override.yml"
compare "$TMP/rendered/paid/paid-runtime.override.yml" "$ROOT/platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/paid-runtime.override.yml"

EXPECTED_METADATA=$(cat <<'EOF'
TRINYX_ENVIRONMENT=staging
AWS_REGION=us-east-1
CLOUD_BASE=/etc/trinyx/staging/cloud
PAID_BASE=/etc/trinyx/staging/paid
CLOUD_SSM_PATH=/trinyx/staging/cloud/
PAID_SSM_PATH=/trinyx/staging/paid/
CLOUD_PRIVATE_IP=10.30.1.147
PAID_PRIVATE_IP=10.30.1.217
PAID_MONOLITH_TRUSTSTORE_SOURCE_PATH=/opt/trinyx/private-ca/paid-monolith-truststore.p12
PAID_MONOLITH_TRUSTSTORE_PASSWORD_SOURCE_PATH=/etc/trinyx/secrets/paid-monolith-truststore-password
EOF
)
ACTUAL_METADATA=$(cat "$TMP/rendered/metadata.env")
[ "$ACTUAL_METADATA" = "$EXPECTED_METADATA" ]

grep -Fxq '      TRINYX_IDENTITY_SIGNING_KID: authority-identity-1' "$TMP/rendered/paid/paid.override.yml"
grep -Fxq '      TRINYX_ENTITLEMENT_SIGNING_KID: authority-entitlement-1' "$TMP/rendered/paid/paid.override.yml"
grep -Fxq '      TRINYX_S2S_SIGNING_KID: paid-authority-1' "$TMP/rendered/paid/paid.override.yml"
grep -Fxq '    network_mode: service:livecontext' "$TMP/rendered/paid/paid.override.yml"
grep -Fxq '      - ./docker/paid-monolith-internal/Caddyfile:/etc/caddy/Caddyfile:ro' "$TMP/rendered/paid/paid.override.yml"
grep -Fxq '      - /etc/trinyx/staging/paid/config/tls/paid-server.crt:/run/tls/billing-internal.crt:ro' "$TMP/rendered/paid/paid.override.yml"
grep -Fxq '      - /etc/trinyx/staging/paid/config/tls/paid-server.key:/run/tls/billing-internal.key:ro' "$TMP/rendered/paid/paid.override.yml"
if grep -Fq '    image: caddy:2.11.4-alpine' "$TMP/rendered/paid/paid.override.yml"; then
  echo 'ERROR_PAID_EDGE_LATE_MUTABLE_IMAGE_OVERRIDE' >&2
  exit 1
fi
if grep -R -Fq --exclude='static_policy.py' --exclude='invariants.py' --exclude-dir=tests --exclude-dir=__pycache__ -- '/srv/trinyx/'"pr25-" "$ROOT/platform"; then
  echo 'ERROR_MUTABLE_CHECKOUT_DEPENDENCY' >&2
  exit 1
fi
grep -Fxq '          memory: 3G' "$TMP/rendered/paid/paid-runtime.override.yml"
grep -Fq '/actuator/health/liveness' "$TMP/rendered/paid/paid-runtime.override.yml"

if grep -Eiq -- '(^|_)(PASSWORD|SECRET|TOKEN|PRIVATE_KEY|SIGNING_KEY|ENCRYPTION_KEY|ACCESS_KEY|API_KEY)=' "$ROOT/platform/environments/staging.env"; then
  echo 'ERROR_SECRET_BEARING_STAGING_INVENTORY_KEY' >&2
  exit 1
fi

echo STAGING_RENDER_CONTRACT_OK
