#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

SOURCE=aeb2a447ea7ce0436a60549713636225dfe1a2c1
INVENTORY="$ROOT/platform/release/runtime-inventory.json"
STATIC="$ROOT/platform/release/third-party-images.json"
FIXTURE="$ROOT/platform/tests/fixtures/staging-bootstrap-images.json"
ASSEMBLER="$ROOT/platform/release/assemble-release-images.py"
BUNDLE_BUILDER="$ROOT/platform/release/build-deployment-bundle.py"
BUNDLE_CONTRACT="$ROOT/platform/release/deployment-bundle-files.json"
HISTORICAL_WORKFLOW="$ROOT/.github/workflows/build-historical-staging-baseline-impl.yml"
HISTORICAL_WRAPPER="$ROOT/.github/workflows/build-historical-staging-baseline.yml"

grep -Fq 'uses: ./.github/workflows/build-historical-staging-baseline-impl.yml' "$HISTORICAL_WRAPPER"
grep -Fq 'actions: read' "$HISTORICAL_WRAPPER"

grep -Fq '33444272417' "$HISTORICAL_WORKFLOW"
grep -Fq '33444302902' "$HISTORICAL_WORKFLOW"
grep -Fq '9777989306' "$HISTORICAL_WORKFLOW"
grep -Fq '8cb6a3b52b7deff90bebcceb6435a5c66d6d1a06e45c32b8350427efe4059ac0' "$HISTORICAL_WORKFLOW"
grep -Fq 'sha256sum --check --strict' "$HISTORICAL_WORKFLOW"
grep -Fq 'actions: read' "$HISTORICAL_WORKFLOW"
grep -Fq 'docker/login-action@c94ce9fb468520275223c153574b00df6fe4bcc9' "$HISTORICAL_WORKFLOW"
grep -Fq 'docker buildx imagetools inspect' "$HISTORICAL_WORKFLOW"
grep -Fq 'ref: ${{ job.workflow_sha }}' "$HISTORICAL_WORKFLOW"
grep -Fq -- '--platform-commit "$TRUSTED_BUILDER_COMMIT"' "$HISTORICAL_WORKFLOW"
grep -Fq -- '--manifest historical-input/paid/cloud.json' "$HISTORICAL_WORKFLOW"
grep -Fq -- '--repo historical-source' "$HISTORICAL_WORKFLOW"
grep -Fq 'actions/attest-build-provenance@' "$HISTORICAL_WORKFLOW"
if grep -Eq 'docker build(x build)? |docker/build-push-action|docker pull|docker push|imagetools create|packages: write|--release-id' "$HISTORICAL_WORKFLOW"; then
  echo ERROR_HISTORICAL_BASELINE_IS_NOT_METADATA_ONLY >&2
  exit 1
fi

python3 - "$FIXTURE" "$SOURCE" "$TMP" <<'PY'
import json, pathlib, sys
fixture, source, out = sys.argv[1:]
data = json.load(open(fixture, encoding="utf-8"))
out = pathlib.Path(out)
owned = {"cloud": [], "backend": [], "frontend": []}
static_names = {
    "cloud-postgres", "cloud-redis", "cloud-minio", "cloud-minio-init", "cloud-searxng", "cloud-edge",
    "paid-postgres", "paid-redis", "paid-minio", "paid-minio-init", "paid-bridge", "paid-edge",
}
for item in data["images"]:
    if item["name"] in static_names:
        continue
    entry = {k: item[k] for k in ("name", "service", "package", "environment", "digest", "immutableRef")}
    if item["role"] == "cloud":
        owned["cloud"].append(entry)
    elif item["name"] == "paid-backend":
        owned["backend"].append(entry)
    elif item["name"] == "paid-frontend":
        owned["frontend"].append(entry)
    else:
        raise SystemExit("unexpected owned image " + item["name"])
for name, images in owned.items():
    json.dump({"schemaVersion": 1, "commit": source, "generatedAt": "2026-09-01T00:00:00Z", "images": images}, open(out / f"{name}.json", "w", encoding="utf-8"), indent=2)
PY

python3 "$ASSEMBLER" \
  --source-commit "$SOURCE" \
  --inventory "$INVENTORY" \
  --manifest "$TMP/cloud.json" \
  --manifest "$TMP/backend.json" \
  --manifest "$TMP/frontend.json" \
  --manifest "$STATIC" \
  --out "$TMP/images.json" |
  grep -Fq "RELEASE_IMAGE_ASSEMBLY_OK images=28 source_commit=$SOURCE"

python3 "$ROOT/platform/release/validate-runtime-images.py" --contract "$INVENTORY" --images "$TMP/images.json" >/dev/null

python3 - "$FIXTURE" "$TMP/images.json" <<'PY'
import json, sys
expected = json.load(open(sys.argv[1], encoding="utf-8"))["images"]
actual = json.load(open(sys.argv[2], encoding="utf-8"))["images"]
key = lambda x: x["name"]
assert sorted(expected, key=key) == sorted(actual, key=key)
print("RELEASE_IMAGE_ASSEMBLY_EXACT_OK")
PY

python3 - "$TMP/backend.json" "$TMP/backend-wrong.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
data["commit"] = "f" * 40
json.dump(data, open(sys.argv[2], "w", encoding="utf-8"))
PY
if python3 "$ASSEMBLER" \
  --source-commit "$SOURCE" \
  --inventory "$INVENTORY" \
  --manifest "$TMP/cloud.json" \
  --manifest "$TMP/backend-wrong.json" \
  --manifest "$TMP/frontend.json" \
  --manifest "$STATIC" \
  --out "$TMP/invalid.json" >/dev/null 2>&1; then
  echo ERROR_CROSS_COMMIT_IMAGE_MANIFEST_ACCEPTED >&2
  exit 1
fi

python3 "$BUNDLE_BUILDER" --repo "$ROOT" --contract "$BUNDLE_CONTRACT" --out "$TMP/bundle-a.tar" --manifest-out "$TMP/bundle-a.json" |
  grep -Fq 'DEPLOYMENT_BUNDLE_BUILD_OK digest=sha256:'
PYTHONHASHSEED=8675309 python3 "$BUNDLE_BUILDER" --repo "$ROOT" --contract "$BUNDLE_CONTRACT" --out "$TMP/bundle-b.tar" --manifest-out "$TMP/bundle-b.json" >/dev/null
cmp -s "$TMP/bundle-a.tar" "$TMP/bundle-b.tar"
cmp -s "$TMP/bundle-a.json" "$TMP/bundle-b.json"

python3 - "$TMP/bundle-a.tar" "$TMP/bundle-a.json" <<'PY'
import json, pathlib, tarfile, sys
tar_path, manifest_path = sys.argv[1:]
manifest = json.load(open(manifest_path, encoding="utf-8"))
assert manifest["schemaVersion"] == 1
assert manifest["format"] == "tar"
assert manifest["digest"].startswith("sha256:")
assert manifest["sizeBytes"] > 0
paths = [item["path"] for item in manifest["files"]]
assert paths == sorted(paths)
assert len(paths) == len(set(paths))
required = {
    "docker-compose.yml",
    "docker/docker-compose.cloud.yml",
    "docker/docker-compose.cloud.runtime.yml",
    "docker/docker-compose.paid.runtime.yml",
    "docker/cloud/Caddyfile",
    "docker/cloud/postgres/00-keycloak-schema.sql",
    "docker/cloud/keycloak/trinyx-realm.json",
    "docker/cloud/searxng/settings.yml",
    "docker/paid-monolith-internal/Caddyfile",
}
assert required <= set(paths)
assert any(p.startswith("catalog-seeds/") for p in paths)
with tarfile.open(tar_path, "r") as tar:
    members = tar.getmembers()
    assert all(member.isfile() for member in members)
    assert [member.name for member in members] == paths
    assert all(not name.startswith("/") and ".." not in pathlib.PurePosixPath(name).parts for name in paths)
print("DEPLOYMENT_BUNDLE_CONTENT_OK")
PY

python3 - "$ROOT" "$BUNDLE_CONTRACT" "$TMP/changed-root" <<'PY'
import json, pathlib, shutil, sys
root, contract_path, dst = map(pathlib.Path, sys.argv[1:])
doc = json.load(open(contract_path, encoding="utf-8"))
for rel in doc["paths"]:
    src = root / rel
    target = dst / rel
    if src.is_dir():
        shutil.copytree(src, target)
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, target)
PY
printf '\n# bundle identity test\n' >> "$TMP/changed-root/docker/docker-compose.paid.runtime.yml"
python3 "$BUNDLE_BUILDER" --repo "$TMP/changed-root" --contract "$BUNDLE_CONTRACT" --out "$TMP/bundle-changed.tar" --manifest-out "$TMP/bundle-changed.json" >/dev/null
DIGEST_A=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["digest"])' "$TMP/bundle-a.json")
DIGEST_CHANGED=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["digest"])' "$TMP/bundle-changed.json")
test "$DIGEST_A" != "$DIGEST_CHANGED"

echo RELEASE_IMAGE_ASSEMBLY_CONTRACT_OK
echo DEPLOYMENT_BUNDLE_DETERMINISM_OK
echo O5_RELEASE_BUILD_CONTRACT_OK
