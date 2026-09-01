#!/usr/bin/env bash
set -euo pipefail

MODE=${1:-}
ROLE=${2:-}
RELEASE_ID=${3:-}

case "$MODE" in
  plan|apply) ;;
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

BASE="/etc/trinyx/staging/$ROLE"
RELEASE_DIR="$BASE/releases/$RELEASE_ID"
ACTIVE="$BASE/active"

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
[ -L "$ACTIVE" ] || {
  echo "ERROR_ACTIVE_RELEASE_LINK_MISSING" >&2
  exit 66
}

ACTIVE_TARGET=$(readlink -f "$ACTIVE")
[ -n "$ACTIVE_TARGET" ] || {
  echo "ERROR_ACTIVE_RELEASE_UNRESOLVED" >&2
  exit 66
}

printf 'STAGING_DEPLOY_ROLE=%s\n' "$ROLE"
printf 'STAGING_DEPLOY_RELEASE=%s\n' "$RELEASE_ID"
printf 'STAGING_DEPLOY_ACTIVE_TARGET=%s\n' "$ACTIVE_TARGET"

if [ "$MODE" = plan ]; then
  echo "STAGING_DEPLOY_PLAN_OK role=$ROLE release_id=$RELEASE_ID"
  exit 0
fi

echo "ERROR_STAGING_DEPLOY_APPLY_NOT_IMPLEMENTED_YET" >&2
exit 70
