#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TOOL="$ROOT/platform/release/release.py"
FIXTURE="$ROOT/platform/tests/fixtures/release-images.json"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

SOURCE=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
PLATFORM=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb

cat > "$TMP/bundle.json" <<'JSON'
{"schemaVersion":1,"format":"tar","digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","sizeBytes":4096,"files":[{"path":"docker-compose.yml","digest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","sizeBytes":123}]}
JSON

PYTHONHASHSEED=1 python3 "$TOOL" create \
  --source-commit "$SOURCE" \
  --source-ref codex/trinyx-cloud-gateway-v2 \
  --platform-commit "$PLATFORM" \
  --created-at 2026-09-01T00:00:00Z \
  --images "$FIXTURE" \
  --bundle-manifest "$TMP/bundle.json" \
  --out "$TMP/release-a.json" >/dev/null

python3 "$TOOL" validate --manifest "$TMP/release-a.json" | grep -Fq 'RELEASE_VALIDATE_OK release_id=rel-v1-'

if grep -Fq 'configRevision' "$TMP/release-a.json"; then
  echo ERROR_ENVIRONMENT_CONFIG_LEAKED_INTO_RELEASE >&2
  exit 1
fi
grep -Fq '"deploymentBundle"' "$TMP/release-a.json"

PYTHONHASHSEED=987654 python3 "$TOOL" create \
  --source-commit "$SOURCE" \
  --source-ref codex/trinyx-cloud-gateway-v2 \
  --platform-commit "$PLATFORM" \
  --created-at 2026-09-01T00:00:00Z \
  --images "$FIXTURE" \
  --bundle-manifest "$TMP/bundle.json" \
  --out "$TMP/release-byte-identical.json" >/dev/null
cmp -s "$TMP/release-a.json" "$TMP/release-byte-identical.json"

python3 "$TOOL" create \
  --source-commit "$SOURCE" \
  --source-ref refs/heads/some-other-label \
  --platform-commit "$PLATFORM" \
  --created-at 2026-09-02T12:34:56Z \
  --images "$FIXTURE" \
  --bundle-manifest "$TMP/bundle.json" \
  --out "$TMP/release-b.json" >/dev/null

RID_A=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["releaseId"])' "$TMP/release-a.json")
RID_B=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["releaseId"])' "$TMP/release-b.json")
test "$RID_A" = "$RID_B"

python3 "$TOOL" render-env --manifest "$TMP/release-a.json" --role cloud --out "$TMP/cloud.env" >/dev/null
python3 "$TOOL" render-env --manifest "$TMP/release-a.json" --role paid --out "$TMP/paid.env" >/dev/null

grep -Fxq 'AUTH_SERVICE_IMAGE=ghcr.io/trinyxai/trinyx-cloud-auth@sha256:1111111111111111111111111111111111111111111111111111111111111111' "$TMP/cloud.env"
grep -Fxq 'EDGE_IMAGE=caddy@sha256:3333333333333333333333333333333333333333333333333333333333333333' "$TMP/cloud.env"
grep -Fxq 'BACKEND_IMAGE=ghcr.io/trinyxai/trinyx-backend@sha256:2222222222222222222222222222222222222222222222222222222222222222' "$TMP/paid.env"
grep -Fxq 'EDGE_IMAGE=caddy@sha256:3333333333333333333333333333333333333333333333333333333333333333' "$TMP/paid.env"

python3 - "$FIXTURE" "$TMP/changed-images.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
item = data["images"][0]
item["digest"] = "sha256:" + "4" * 64
item["immutableRef"] = item["package"] + "@" + item["digest"]
json.dump(data, open(sys.argv[2], "w"), indent=2)
PY
python3 "$TOOL" create \
  --source-commit "$SOURCE" --source-ref test \
  --platform-commit "$PLATFORM" \
  --created-at 2026-09-01T00:00:00Z \
  --images "$TMP/changed-images.json" \
  --bundle-manifest "$TMP/bundle.json" \
  --out "$TMP/release-c.json" >/dev/null
RID_C=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["releaseId"])' "$TMP/release-c.json")
test "$RID_A" != "$RID_C"

python3 - "$TMP/bundle.json" "$TMP/bundle-changed.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
data["digest"] = "sha256:" + "c" * 64
json.dump(data, open(sys.argv[2], "w"))
PY
python3 "$TOOL" create \
  --source-commit "$SOURCE" --source-ref test \
  --platform-commit "$PLATFORM" \
  --created-at 2026-09-01T00:00:00Z \
  --images "$FIXTURE" \
  --bundle-manifest "$TMP/bundle-changed.json" \
  --out "$TMP/release-bundle-change.json" >/dev/null
RID_BUNDLE=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["releaseId"])' "$TMP/release-bundle-change.json")
test "$RID_A" != "$RID_BUNDLE"

OTHER_PLATFORM=eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
python3 "$TOOL" create \
  --source-commit "$SOURCE" --source-ref test \
  --platform-commit "$OTHER_PLATFORM" \
  --created-at 2026-09-01T00:00:00Z \
  --images "$FIXTURE" \
  --bundle-manifest "$TMP/bundle.json" \
  --out "$TMP/release-platform-change.json" >/dev/null
RID_PLATFORM=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["releaseId"])' "$TMP/release-platform-change.json")
test "$RID_A" != "$RID_PLATFORM"

python3 - "$TMP/release-a.json" "$TMP/tampered.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
data["sourceCommit"] = "d" * 40
json.dump(data, open(sys.argv[2], "w"), indent=2)
PY
if python3 "$TOOL" validate --manifest "$TMP/tampered.json" >/dev/null 2>&1; then
  echo ERROR_TAMPERED_RELEASE_ACCEPTED >&2
  exit 1
fi

python3 - "$TMP/release-a.json" "$TMP/tampered-bundle.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
data["deploymentBundle"]["digest"] = "sha256:" + "d" * 64
json.dump(data, open(sys.argv[2], "w"), indent=2)
PY
if python3 "$TOOL" validate --manifest "$TMP/tampered-bundle.json" >/dev/null 2>&1; then
  echo ERROR_TAMPERED_BUNDLE_IDENTITY_ACCEPTED >&2
  exit 1
fi

python3 - "$TMP/release-a.json" "$TMP/environment-bound.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
data["configRevision"] = "c" * 40
json.dump(data, open(sys.argv[2], "w"), indent=2)
PY
if python3 "$TOOL" validate --manifest "$TMP/environment-bound.json" >/dev/null 2>&1; then
  echo ERROR_ENVIRONMENT_BOUND_RELEASE_ACCEPTED >&2
  exit 1
fi

python3 - "$FIXTURE" "$TMP/mutable.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
item = data["images"][0]
item["digest"] = "latest"
item["immutableRef"] = item["package"] + ":latest"
json.dump(data, open(sys.argv[2], "w"), indent=2)
PY
if python3 "$TOOL" create \
  --source-commit "$SOURCE" --source-ref test \
  --platform-commit "$PLATFORM" \
  --images "$TMP/mutable.json" \
  --bundle-manifest "$TMP/bundle.json" \
  --out "$TMP/invalid.json" >/dev/null 2>&1; then
  echo ERROR_MUTABLE_RELEASE_ACCEPTED >&2
  exit 1
fi

echo RELEASE_PROMOTION_BOUNDARY_OK
echo RELEASE_BUNDLE_IDENTITY_OK
echo RELEASE_CONTRACT_OK
