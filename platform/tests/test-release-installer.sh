#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TMP=$(mktemp -d)
trap 'chmod -R u+w "$TMP" 2>/dev/null || true; rm -rf "$TMP"' EXIT

SOURCE=aeb2a447ea7ce0436a60549713636225dfe1a2c1
FIXTURE="$ROOT/platform/tests/fixtures/staging-bootstrap-images.json"
MANIFEST="$TMP/manifest.json"
BUNDLE="$TMP/deployment-bundle.tar"
BUNDLE_MANIFEST="$TMP/deployment-bundle.json"
TOOL="$ROOT/platform/release/release.py"
INSTALLER="$ROOT/platform/install/install-release.py"
CONTRACT="$ROOT/platform/release/runtime-inventory.json"
FAKE="$TMP/root"

python3 "$ROOT/platform/release/build-deployment-bundle.py" \
  --repo "$ROOT" \
  --contract "$ROOT/platform/release/deployment-bundle-files.json" \
  --out "$BUNDLE" \
  --manifest-out "$BUNDLE_MANIFEST" >/dev/null

PLATFORM=$(git -C "$ROOT" rev-parse HEAD)
[[ "$PLATFORM" =~ ^[0-9a-f]{40}$ ]]

python3 "$TOOL" create \
  --source-commit "$SOURCE" \
  --source-ref codex/trinyx-cloud-gateway-v2 \
  --platform-commit "$PLATFORM" \
  --created-at 2026-09-01T05:23:32Z \
  --images "$FIXTURE" \
  --bundle-manifest "$BUNDLE_MANIFEST" \
  --out "$MANIFEST" >/dev/null

RID=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["releaseId"])' "$MANIFEST")
[[ "$RID" =~ ^rel-v1-[0-9a-f]{32}$ ]]
EXPECTED_RELEASE="$RID"
BUNDLE_DIGEST=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["digest"])' "$BUNDLE_MANIFEST")
BUNDLE_FILES=$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))["files"]))' "$BUNDLE_MANIFEST")

# Modern bundles must declare the exact normalized mode for every file.
python3 - "$BUNDLE_MANIFEST" "$TMP/missing-mode.json" <<'PY'
import json, sys
source, target = sys.argv[1:]
document = json.load(open(source, encoding="utf-8"))
for item in document["files"]:
    item.pop("mode")
with open(target, "w", encoding="utf-8") as output:
    json.dump(document, output)
PY
if python3 "$INSTALLER" \
  --role paid --environment staging \
  --manifest "$MANIFEST" --bundle-manifest "$TMP/missing-mode.json" --bundle "$BUNDLE" \
  --contract "$CONTRACT" --release-tool "$TOOL" \
  --root "$TMP/missing-mode-root" --apply >/dev/null 2>&1; then
  echo ERROR_MODERN_BUNDLE_WITHOUT_MODES_ACCEPTED >&2
  exit 1
fi

python3 - "$BUNDLE_MANIFEST" "$TMP/wrong-mode.json" <<'PY'
import json, sys
source, target = sys.argv[1:]
document = json.load(open(source, encoding="utf-8"))
document["files"][0]["mode"] = 0o755 if document["files"][0]["mode"] == 0o644 else 0o644
with open(target, "w", encoding="utf-8") as output:
    json.dump(document, output)
PY
if python3 "$INSTALLER" \
  --role paid --environment staging \
  --manifest "$MANIFEST" --bundle-manifest "$TMP/wrong-mode.json" --bundle "$BUNDLE" \
  --contract "$CONTRACT" --release-tool "$TOOL" \
  --root "$TMP/wrong-mode-root" --apply >/dev/null 2>&1; then
  echo ERROR_BUNDLE_MODE_MISMATCH_ACCEPTED >&2
  exit 1
fi

do_install() {
  local role="$1" apply="${2:-no}"
  local args=(
    python3 "$INSTALLER"
    --role "$role" --environment staging
    --manifest "$MANIFEST" --bundle-manifest "$BUNDLE_MANIFEST" --bundle "$BUNDLE"
    --contract "$CONTRACT" --release-tool "$TOOL"
    --root "$FAKE"
  )
  if [ "$apply" = yes ]; then args+=(--apply); fi
  "${args[@]}"
}

for ROLE in cloud paid; do
  BASE="$FAKE/etc/trinyx/staging/$ROLE"
  mkdir -p "$BASE/deployments/stg-bootstrap-001"
  ln -s deployments/stg-bootstrap-001 "$BASE/active"
  BEFORE=$(readlink "$BASE/active")

  PLAN=$(do_install "$ROLE")
  printf '%s\n' "$PLAN" | grep -Fq "RELEASE_INSTALL_PLAN_OK role=$ROLE environment=staging release_id=$EXPECTED_RELEASE changes=1"
  test ! -e "$BASE/releases/$EXPECTED_RELEASE"

  APPLY=$(do_install "$ROLE" yes)
  printf '%s\n' "$APPLY" | grep -Fq "RELEASE_INSTALL_APPLY_OK role=$ROLE environment=staging release_id=$EXPECTED_RELEASE changes=1"
  printf '%s\n' "$APPLY" | grep -Fq "RELEASE_BUNDLE_INSTALLED_OK digest=$BUNDLE_DIGEST files=$BUNDLE_FILES"
  printf '%s\n' "$APPLY" | grep -Fq 'RELEASE_ACTIVE_UNCHANGED=yes'
  test "$(readlink "$BASE/active")" = "$BEFORE"

  TARGET="$BASE/releases/$EXPECTED_RELEASE"
  test "$(stat -c %a "$TARGET")" = 555
  for name in manifest.json images.env deployment-bundle.json deployment-bundle.tar; do
    test "$(stat -c %a "$TARGET/$name")" = 444
  done
  test "$(stat -c %a "$TARGET/bundle")" = 555
  test -f "$TARGET/bundle/docker-compose.yml"
  test -f "$TARGET/bundle/docker/docker-compose.cloud.yml"
  test -f "$TARGET/bundle/docker/docker-compose.paid.runtime.yml"
  test "sha256:$(sha256sum "$TARGET/deployment-bundle.tar" | awk '{print $1}')" = "$BUNDLE_DIGEST"

  POST=$(do_install "$ROLE")
  printf '%s\n' "$POST" | grep -Fq "RELEASE_INSTALL_PLAN_OK role=$ROLE environment=staging release_id=$EXPECTED_RELEASE changes=0"
done

# Any drift inside the immutable extracted bundle must be rejected.
TARGET="$FAKE/etc/trinyx/staging/cloud/releases/$EXPECTED_RELEASE"
DRIFT="$TARGET/bundle/docker-compose.yml"
chmod 755 "$TARGET"
chmod 755 "$TARGET/bundle"
chmod 644 "$DRIFT"
printf '\n# drift\n' >> "$DRIFT"
chmod 444 "$DRIFT"
chmod 555 "$TARGET/bundle"
chmod 555 "$TARGET"
if do_install cloud >/dev/null 2>&1; then
  echo ERROR_IMMUTABLE_RELEASE_BUNDLE_COLLISION_ACCEPTED >&2
  exit 1
fi

# A mismatched bundle tar must be rejected before filesystem mutation.
cp "$BUNDLE" "$TMP/tampered.tar"
printf 'x' >> "$TMP/tampered.tar"
if python3 "$INSTALLER" \
  --role paid --environment staging \
  --manifest "$MANIFEST" --bundle-manifest "$BUNDLE_MANIFEST" --bundle "$TMP/tampered.tar" \
  --contract "$CONTRACT" --release-tool "$TOOL" \
  --root "$TMP/other-root" --apply >/dev/null 2>&1; then
  echo ERROR_TAMPERED_RELEASE_BUNDLE_ACCEPTED >&2
  exit 1
fi
test ! -e "$TMP/other-root/etc/trinyx/staging/paid/releases/$EXPECTED_RELEASE"

echo RELEASE_INSTALLER_BUNDLE_HASH_GATE_OK
echo RELEASE_INSTALLER_BUNDLE_EXTRACTION_OK
echo RELEASE_INSTALLER_ATOMIC_PUBLISH_OK
echo RELEASE_INSTALLER_ACTIVE_BOUNDARY_OK
echo RELEASE_INSTALLER_CONTRACT_OK
