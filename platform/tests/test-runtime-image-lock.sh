#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
CONTRACT="$ROOT/platform/release/runtime-inventory.json"
IMAGES="$ROOT/platform/tests/fixtures/staging-bootstrap-images.json"

python3 "$ROOT/platform/release/validate-runtime-images.py" --contract "$CONTRACT" --images "$IMAGES"

python3 - "$IMAGES" "$TMP/cloud.env" "$TMP/paid.env" <<'PY'
import json, sys
src, cloud_path, paid_path = sys.argv[1:4]
data = json.load(open(src, encoding='utf-8'))
for role, path in [('cloud', cloud_path), ('paid', paid_path)]:
    rows = sorted((i['environment'], i['immutableRef']) for i in data['images'] if i['role'] == role)
    with open(path, 'w', encoding='utf-8', newline='\n') as fh:
        for key, value in rows:
            fh.write(f'{key}={value}\n')
PY

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

python3 - "$CONTRACT" "$IMAGES" "$TMP/cloud.json" "$TMP/paid.json" <<'PY'
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

echo RUNTIME_IMAGE_LOCK_CONTRACT_OK
