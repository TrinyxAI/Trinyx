#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TMP=$(mktemp -d)
trap 'chmod -R u+w "$TMP" 2>/dev/null || true; rm -rf "$TMP"' EXIT

SOURCE=aeb2a447ea7ce0436a60549713636225dfe1a2c1
PLATFORM=ae045447fce099f6bffd43b399b6964f29820a0a
EXPECTED_RELEASE=rel-v1-082bd961a3ef556fc849e3555d804a5a
FIXTURE="$ROOT/platform/tests/fixtures/staging-bootstrap-images.json"
MANIFEST="$TMP/manifest.json"
TOOL="$ROOT/platform/release/release.py"
INSTALLER="$ROOT/platform/install/install-release.py"
CONTRACT="$ROOT/platform/release/runtime-inventory.json"
FAKE="$TMP/root"

python3 "$TOOL" create \
  --source-commit "$SOURCE" \
  --source-ref codex/trinyx-cloud-gateway-v2 \
  --platform-commit "$PLATFORM" \
  --created-at 2026-09-01T05:23:32Z \
  --images "$FIXTURE" \
  --out "$MANIFEST" >/dev/null

RID=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["releaseId"])' "$MANIFEST")
test "$RID" = "$EXPECTED_RELEASE"

for ROLE in cloud paid; do
  BASE="$FAKE/etc/trinyx/staging/$ROLE"
  mkdir -p "$BASE/deployments/stg-bootstrap-001"
  ln -s deployments/stg-bootstrap-001 "$BASE/active"
  BEFORE=$(readlink "$BASE/active")

  PLAN=$(python3 "$INSTALLER" \
    --role "$ROLE" --environment staging \
    --manifest "$MANIFEST" --contract "$CONTRACT" --release-tool "$TOOL" \
    --root "$FAKE")
  printf '%s\n' "$PLAN" | grep -Fq "RELEASE_INSTALL_PLAN_OK role=$ROLE environment=staging release_id=$EXPECTED_RELEASE changes=1"
  test ! -e "$BASE/releases/$EXPECTED_RELEASE"

  APPLY=$(python3 "$INSTALLER" \
    --role "$ROLE" --environment staging \
    --manifest "$MANIFEST" --contract "$CONTRACT" --release-tool "$TOOL" \
    --root "$FAKE" --apply)
  printf '%s\n' "$APPLY" | grep -Fq "RELEASE_INSTALL_APPLY_OK role=$ROLE environment=staging release_id=$EXPECTED_RELEASE changes=1"
  printf '%s\n' "$APPLY" | grep -Fq 'RELEASE_ACTIVE_UNCHANGED=yes'
  test "$(readlink "$BASE/active")" = "$BEFORE"
  test "$(stat -c %a "$BASE/releases/$EXPECTED_RELEASE")" = 555
  test "$(stat -c %a "$BASE/releases/$EXPECTED_RELEASE/manifest.json")" = 444
  test "$(stat -c %a "$BASE/releases/$EXPECTED_RELEASE/images.env")" = 444

  POST=$(python3 "$INSTALLER" \
    --role "$ROLE" --environment staging \
    --manifest "$MANIFEST" --contract "$CONTRACT" --release-tool "$TOOL" \
    --root "$FAKE")
  printf '%s\n' "$POST" | grep -Fq "RELEASE_INSTALL_PLAN_OK role=$ROLE environment=staging release_id=$EXPECTED_RELEASE changes=0"

done

# Collision with same release id but changed installed bytes must fail closed.
TARGET="$FAKE/etc/trinyx/staging/cloud/releases/$EXPECTED_RELEASE"
chmod 755 "$TARGET"
chmod 644 "$TARGET/images.env"
printf '# drift\n' >> "$TARGET/images.env"
chmod 444 "$TARGET/images.env"
chmod 555 "$TARGET"
if python3 "$INSTALLER" \
  --role cloud --environment staging \
  --manifest "$MANIFEST" --contract "$CONTRACT" --release-tool "$TOOL" \
  --root "$FAKE" >/dev/null 2>&1; then
  echo ERROR_IMMUTABLE_RELEASE_COLLISION_ACCEPTED >&2
  exit 1
fi

echo RELEASE_INSTALLER_ATOMIC_PUBLISH_OK
echo RELEASE_INSTALLER_ACTIVE_BOUNDARY_OK
echo RELEASE_INSTALLER_CONTRACT_OK
