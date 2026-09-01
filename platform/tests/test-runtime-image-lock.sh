#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
CONTRACT="$ROOT/platform/release/runtime-inventory.json"
IMAGES="$ROOT/platform/tests/fixtures/staging-bootstrap-images.json"
RELEASE="$ROOT/platform/releases/rel-v1-de31904a66fbef13c2042cc0652b94bc/manifest.json"
TOOL="$ROOT/platform/release/release.py"

python3 "$ROOT/platform/release/validate-runtime-images.py" --contract "$CONTRACT" --images "$IMAGES"
python3 "$TOOL" validate --manifest "$RELEASE"
python3 "$ROOT/platform/release/validate-runtime-images.py" --contract "$CONTRACT" --images "$RELEASE"

# The checked-in bootstrap release must be exactly reproducible from its
# source commit, platform revision, config revision and 28 immutable images.
python3 "$TOOL" create \
  --source-commit aeb2a447ea7ce0436a60549713636225dfe1a2c1 \
  --source-ref codex/trinyx-cloud-gateway-v2 \
  --platform-commit b19beff636e7665941c73dd94e03726858c5559d \
  --config-revision c18c450be61b18dce167bb7fd726b224f989eecf \
  --created-at 2026-09-01T05:10:48Z \
  --images "$IMAGES" \
  --out "$TMP/release.json" >/dev/null
cmp -s "$RELEASE" "$TMP/release.json"

python3 "$TOOL" render-env --manifest "$RELEASE" --role cloud --out "$TMP/cloud.env" >/dev/null
python3 "$TOOL" render-env --manifest "$RELEASE" --role paid --out "$TMP/paid.env" >/dev/null

test "$(grep -c '^[A-Z][A-Z0-9_]*=' "$TMP/cloud.env")" = 20
test "$(grep -c '^[A-Z][A-Z0-9_]*=' "$TMP/paid.env")" = 8

cat "$ROOT/docker/.env.cloud.example" "$TMP/cloud.env" > "$TMP/cloud-full.env"
docker compose --env-file "$TMP/cloud-full.env" \
  -f "$ROOT/docker/docker-compose.cloud.yml" \
  -f "$ROOT/docker/docker-compose.cloud.runtime.yml" \
  config --format json > "$TMP/cloud.json"

docker compose --env-file "$TMP/paid.env" \
  -f "$ROOT/docker-compose.yml" \
  -f "$ROOT/platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/paid.override.yml" \
  -f "$ROOT/docker/docker-compose.paid.runtime.yml" \
  config --format json > "$TMP/paid.json"

python3 - "$CONTRACT" "$RELEASE" "$TMP/cloud.json" "$TMP/paid.json" <<'PY'
import json, re, sys
contract_path, images_path, cloud_path, paid_path = sys.argv[1:5]
contract = json.load(open(contract_path, encoding='utf-8'))
images = json.load(open(images_path, encoding='utf-8'))
rendered = {
    'cloud': json.load(open(cloud_path, encoding='utf-8')),
    'paid': json.load(open(paid_path, encoding='utf-8')),
}
refs = {item['name']: item['immutableRef'] for item in images['images']}
pattern = re.compile(r'^\S+@sha256:[0-9a-f]{64}$')
for item in contract['images']:
    role = item['role']
    service = item['service']
    name = item['name']
    actual = rendered[role]['services'][service]['image']
    expected = refs[name]
    if actual != expected:
        raise SystemExit(f'ERROR_RUNTIME_IMAGE_RENDER_MISMATCH={role}:{service}:{actual}:{expected}')
    if not pattern.fullmatch(actual):
        raise SystemExit(f'ERROR_MUTABLE_RUNTIME_IMAGE={role}:{service}:{actual}')
print('RUNTIME_COMPOSE_DIGEST_LOCK_OK images=28')
PY

echo BOOTSTRAP_RELEASE_REPRODUCIBLE_OK
echo RUNTIME_IMAGE_LOCK_CONTRACT_OK
