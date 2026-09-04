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
HISTORICAL_SOURCE_CONTRACT="$ROOT/platform/release/historical-deployment-bundle-sources.json"
HISTORICAL_WORKFLOW="$ROOT/.github/workflows/build-historical-staging-baseline-impl.yml"
HISTORICAL_WRAPPER="$ROOT/.github/workflows/build-historical-staging-baseline.yml"

grep -Fq 'uses: ./.github/workflows/build-historical-staging-baseline-impl.yml' "$HISTORICAL_WRAPPER"
grep -Fq 'actions: read' "$HISTORICAL_WRAPPER"

grep -Fq '33444272417' "$HISTORICAL_WORKFLOW"
grep -Fq '33444302902' "$HISTORICAL_WORKFLOW"
grep -Fq '9777989306' "$HISTORICAL_WORKFLOW"
grep -Fq 'actions/jobs/99660712771/logs' "$HISTORICAL_WORKFLOW"
grep -Fq 'actions/jobs/99659777935/logs' "$HISTORICAL_WORKFLOW"
grep -Fq '8cb6a3b52b7deff90bebcceb6435a5c66d6d1a06e45c32b8350427efe4059ac0' "$HISTORICAL_WORKFLOW"
grep -Fq 'sha256sum --check --strict' "$HISTORICAL_WORKFLOW"
grep -Fq 'actions: read' "$HISTORICAL_WORKFLOW"
grep -Fq 'docker/login-action@c94ce9fb468520275223c153574b00df6fe4bcc9' "$HISTORICAL_WORKFLOW"
grep -Fq 'docker buildx imagetools inspect' "$HISTORICAL_WORKFLOW"
grep -Fq 'ref: ${{ job.workflow_sha }}' "$HISTORICAL_WORKFLOW"
grep -Fq -- '--platform-commit "$TRUSTED_BUILDER_COMMIT"' "$HISTORICAL_WORKFLOW"
grep -Fq -- '--manifest historical-input/paid/cloud.json' "$HISTORICAL_WORKFLOW"
grep -Fq 'platform/release/prepare-historical-bundle-source.py' "$HISTORICAL_WORKFLOW"
grep -Fq -- '--historical-repo historical-source' "$HISTORICAL_WORKFLOW"
grep -Fq -- '--repo historical-bundle-source' "$HISTORICAL_WORKFLOW"
grep -Fq 'historical-deployment-bundle-sources.json' "$HISTORICAL_WORKFLOW"
grep -Fq 'actions/attest-build-provenance@' "$HISTORICAL_WORKFLOW"
grep -Fq 'git -C historical-source status --porcelain=v1 --untracked-files=all' "$HISTORICAL_WORKFLOW"
grep -Fq 'postgres redis minio minio-init bridge livecontext frontend paid-edge' "$HISTORICAL_WORKFLOW"
grep -Fq 'set(services) != expected_services' "$HISTORICAL_WORKFLOW"
grep -Fq 'platform/release/validate-historical-artifact.py' "$HISTORICAL_WORKFLOW"
grep -Fq -- '--expected-file cloud-image-manifest.json' "$HISTORICAL_WORKFLOW"
grep -Fq -- '--trusted-commit "$TRUSTED_BUILDER_COMMIT"' "$HISTORICAL_WORKFLOW"
grep -Fq -- '--approved-environment-config platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/paid.override.yml' "$HISTORICAL_WORKFLOW"
grep -Fq 'HISTORICAL_TRUSTED_ENVIRONMENT_CONFIG_OK' "$ROOT/platform/release/prepare-historical-bundle-source.py"
if grep -Fq 'unzip -q' "$HISTORICAL_WORKFLOW"; then
  echo ERROR_UNSAFE_HISTORICAL_ZIP_EXTRACTION >&2
  exit 1
fi
python3 - "$HISTORICAL_WORKFLOW" <<'PY'
import sys
workflow = open(sys.argv[1], encoding="utf-8").read()
prepare = workflow.index("Prepare authenticated historical bundle source")
render = workflow.index("Verify Paid runtime render from authenticated bundle origins")
build = workflow.index("Build deterministic bundle from authenticated source origins")
assert prepare < render < build
print("HISTORICAL_BUNDLE_WORKFLOW_ORDER_OK")
PY

python3 - "$HISTORICAL_SOURCE_CONTRACT" "$BUNDLE_CONTRACT" "$SOURCE" <<'PY'
import json, sys
source_contract, bundle_contract, source = sys.argv[1:]
doc = json.load(open(source_contract, encoding="utf-8"))
bundle = json.load(open(bundle_contract, encoding="utf-8"))
assert set(doc) == {
    "schemaVersion", "historicalSourceCommit", "historicalPaths",
    "trustedBuilderOverlays",
}
assert doc["schemaVersion"] == 1
assert doc["historicalSourceCommit"] == source
assert set(doc["trustedBuilderOverlays"]) == {"docker/docker-compose.paid.runtime.yml"}
assert set(doc["historicalPaths"]) == {
    "docker-compose.yml",
    "catalog-seeds",
    "docker/docker-compose.cloud.yml",
    "docker/docker-compose.cloud.runtime.yml",
    "docker/cloud/Caddyfile",
    "docker/cloud/postgres/00-keycloak-schema.sql",
    "docker/cloud/keycloak/trinyx-realm.json",
    "docker/cloud/searxng/settings.yml",
    "docker/paid-monolith-internal/Caddyfile",
}
assert set(bundle["paths"]) == set(doc["historicalPaths"]) | set(doc["trustedBuilderOverlays"])
print("HISTORICAL_BUNDLE_SOURCE_CONTRACT_OK")
PY
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

# Contract paths must be canonical and schemaVersion must be a real integer.
python3 - "$BUNDLE_CONTRACT" "$TMP/bool-contract.json" "$TMP/dot-contract.json" <<'PY'
import json, sys
source, bool_path, dot_path = sys.argv[1:]
doc = json.load(open(source, encoding="utf-8"))
bool_doc = dict(doc)
bool_doc["schemaVersion"] = True
json.dump(bool_doc, open(bool_path, "w", encoding="utf-8"))
dot_doc = dict(doc)
dot_doc["paths"] = ["."]
json.dump(dot_doc, open(dot_path, "w", encoding="utf-8"))
PY
if python3 "$BUNDLE_BUILDER" --repo "$ROOT" --contract "$TMP/bool-contract.json" --out "$TMP/bool.tar" --manifest-out "$TMP/bool.json" >/dev/null 2>&1; then
  echo ERROR_BOOL_BUNDLE_SCHEMA_ACCEPTED >&2
  exit 1
fi
if python3 "$BUNDLE_BUILDER" --repo "$ROOT" --contract "$TMP/dot-contract.json" --out "$TMP/dot.tar" --manifest-out "$TMP/dot.json" >/dev/null 2>&1; then
  echo ERROR_DOT_BUNDLE_PATH_ACCEPTED >&2
  exit 1
fi
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
assert all(set(entry) == {"path", "digest", "sizeBytes", "mode"} for entry in manifest["files"])
assert all(entry["mode"] in {0o644, 0o755} for entry in manifest["files"])
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
    assert [member.mode for member in members] == [entry["mode"] for entry in manifest["files"]]
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
