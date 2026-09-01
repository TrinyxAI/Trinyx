#!/usr/bin/env bash
set -euo pipefail

MODE=${1:-}
ROLE=${2:-}
RELEASE_ID=${3:-}
BUNDLE_DIGEST=${4:-}
REGISTRY_BUCKET=${5:-}
DEPLOYMENT_ID=${6:-}
CONFIG_REVISION=${7:-}
PLATFORM_COMMIT=${8:-}
PREVIOUS_CLOUD_RELEASE=${9:-}
PREVIOUS_PAID_RELEASE=${10:-}

case "$MODE" in
  install|plan|adopt|restore-legacy|apply|rollback|health) ;;
  *) echo "ERROR_INVALID_DEPLOY_MODE" >&2; exit 64 ;;
esac

case "$ROLE" in
  cloud|paid) ;;
  *) echo "ERROR_INVALID_DEPLOY_ROLE" >&2; exit 64 ;;
esac

[[ "$RELEASE_ID" =~ ^rel-v1-[0-9a-f]{32}$ ]] || {
  echo "ERROR_INVALID_RELEASE_ID" >&2
  exit 64
}
[[ "$BUNDLE_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] || {
  echo "ERROR_INVALID_BUNDLE_DIGEST" >&2
  exit 64
}
[[ "$REGISTRY_BUCKET" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || {
  echo "ERROR_INVALID_REGISTRY_BUCKET" >&2
  exit 64
}

[[ "$DEPLOYMENT_ID" =~ ^dep-[0-9a-f]{32}$ ]] || {
  echo "ERROR_INVALID_DEPLOYMENT_ID" >&2
  exit 64
}
[[ "$CONFIG_REVISION" =~ ^[A-Za-z0-9._-]{1,128}$ ]] || {
  echo "ERROR_INVALID_CONFIG_REVISION" >&2
  exit 64
}
[[ "$PLATFORM_COMMIT" =~ ^[0-9a-f]{40}$ ]] || {
  echo "ERROR_INVALID_PLATFORM_COMMIT" >&2
  exit 64
}
for previous in "$PREVIOUS_CLOUD_RELEASE" "$PREVIOUS_PAID_RELEASE"; do
  if [ -n "$previous" ] && [[ ! "$previous" =~ ^rel-v1-[0-9a-f]{32}$ ]]; then
    echo "ERROR_INVALID_PREVIOUS_RELEASE" >&2
    exit 64
  fi
done

BASE="/etc/trinyx/staging/$ROLE"
RELEASE_DIR="$BASE/releases/$RELEASE_ID"
ACTIVE="$BASE/active"

[ -L "$ACTIVE" ] || {
  echo "ERROR_ACTIVE_RELEASE_LINK_MISSING" >&2
  exit 66
}

ACTIVE_TARGET=$(readlink -f "$ACTIVE")
[ -n "$ACTIVE_TARGET" ] || {
  echo "ERROR_ACTIVE_RELEASE_UNRESOLVED" >&2
  exit 66
}

if [ "$MODE" != install ]; then
  [ -d "$RELEASE_DIR" ] || {
    echo "ERROR_RELEASE_NOT_INSTALLED=$RELEASE_ID" >&2
    exit 66
  }
  [ -s "$RELEASE_DIR/manifest.json" ] || {
    echo "ERROR_RELEASE_MANIFEST_MISSING=$RELEASE_ID" >&2
    exit 66
  }
  [ -s "$RELEASE_DIR/images.env" ] || {
    echo "ERROR_RELEASE_IMAGES_MISSING=$RELEASE_ID" >&2
    exit 66
  }
fi

printf 'STAGING_DEPLOY_ROLE=%s\n' "$ROLE"
printf 'STAGING_DEPLOY_RELEASE=%s\n' "$RELEASE_ID"
printf 'STAGING_DEPLOY_ACTIVE_TARGET=%s\n' "$ACTIVE_TARGET"

ENGINE=/usr/local/lib/trinyx/deploy_engine.py
INVARIANTS=/usr/local/lib/trinyx/invariants.py
REGISTRY=/usr/local/lib/trinyx/release_registry.py
test -x "$ENGINE" || { echo ERROR_DEPLOY_ENGINE_MISSING >&2; exit 66; }
test -x "$INVARIANTS" || { echo ERROR_INVARIANTS_MISSING >&2; exit 66; }
test -x "$REGISTRY" || { echo ERROR_RELEASE_REGISTRY_CLIENT_MISSING >&2; exit 66; }

if [ "$MODE" = install ]; then
  CANDIDATE="/var/lib/trinyx/release-candidates/$RELEASE_ID"
  /usr/bin/env python3 "$REGISTRY" fetch \
    --bucket "$REGISTRY_BUCKET" \
    --region "${AWS_REGION:-us-east-1}" \
    --release-id "$RELEASE_ID" \
    --bundle-digest "$BUNDLE_DIGEST" \
    --destination "$CANDIDATE"
  exec /usr/bin/env python3 /usr/local/lib/trinyx/install-release.py \
    --role "$ROLE" \
    --environment staging \
    --manifest "$CANDIDATE/release.json" \
    --bundle-manifest "$CANDIDATE/deployment-bundle.json" \
    --bundle "$CANDIDATE/deployment-bundle.tar" \
    --contract /usr/local/share/trinyx/runtime-inventory.json \
    --release-tool /usr/local/lib/trinyx/release.py \
    --apply
fi

ARGS=(
  "$MODE" "$ROLE" "$RELEASE_ID"
  --deployment-id "$DEPLOYMENT_ID"
  --environment-config-revision "$CONFIG_REVISION"
  --platform-commit "$PLATFORM_COMMIT"
  --expected-bundle-digest "$BUNDLE_DIGEST"
)
if [ -n "$PREVIOUS_CLOUD_RELEASE" ]; then
  ARGS+=(--previous-cloud-release "$PREVIOUS_CLOUD_RELEASE")
fi
if [ -n "$PREVIOUS_PAID_RELEASE" ]; then
  ARGS+=(--previous-paid-release "$PREVIOUS_PAID_RELEASE")
fi

exec /usr/bin/env python3 "$ENGINE" "${ARGS[@]}"
