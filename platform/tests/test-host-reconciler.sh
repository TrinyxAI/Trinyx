#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
INVENTORY="$ROOT/platform/environments/staging.env"
RECONCILER="$ROOT/platform/install/reconcile-host.py"

run_role() {
  local role="$1"
  local fake="$TMP/$role"
  mkdir -p "$fake"

  local plan1="$TMP/${role}-plan1.txt"
  python3 "$RECONCILER" --role "$role" --inventory "$INVENTORY" --root "$fake" > "$plan1"
  grep -Fq "HOST_RECONCILE_PLAN_OK role=$role environment=staging" "$plan1"
  grep -Eq 'changes=[1-9][0-9]*$' "$plan1"
  test ! -e "$fake/etc/trinyx/platform/environment.env"

  local apply1="$TMP/${role}-apply1.txt"
  python3 "$RECONCILER" --role "$role" --inventory "$INVENTORY" --root "$fake" --apply > "$apply1"
  grep -Fq "HOST_RECONCILE_APPLY_OK role=$role environment=staging" "$apply1"
  grep -Fq 'runtime_refresh_required=yes' "$apply1"

  test "$(stat -c %a "$fake/etc/trinyx/platform/environment.env")" = 600
  test "$(stat -c %a "$fake/usr/local/lib/trinyx/runtime-env.sh")" = 750
  test "$(stat -c %a "$fake/usr/local/lib/trinyx/baseline-observation")" = 750
  test "$(stat -c %a "$fake/usr/local/lib/trinyx/legacy-normalization-plan")" = 750
  test "$(stat -c %a "$fake/usr/local/lib/trinyx/legacy_runtime.py")" = 750
  test "$(stat -c %a "$fake/usr/local/lib/trinyx/offline-staging-pki")" = 750
  test "$(stat -c %a "$fake/etc/docker/daemon.json")" = 644
  grep -Fxq 'TRINYX_ENVIRONMENT=staging' "$fake/etc/trinyx/platform/environment.env"
  grep -Fxq 'CLOUD_SSM_PATH=/trinyx/staging/cloud/' "$fake/etc/trinyx/platform/environment.env"
  grep -Fxq 'PAID_SSM_PATH=/trinyx/staging/paid/' "$fake/etc/trinyx/platform/environment.env"

  if test "$role" = cloud; then
    diff -u \
      "$ROOT/platform/contracts/ssm/cloud-required.txt" \
      "$fake/etc/trinyx/staging/cloud/config/ssm-required.txt"
    diff -u \
      "$ROOT/platform/bootstrap/cloud/staging/rootfs/etc/trinyx/staging/cloud/config/runtime-static.env" \
      "$fake/etc/trinyx/staging/cloud/config/runtime-static.env"
    diff -u \
      "$ROOT/platform/host/cloud/systemd/staging/docker.service.d/20-trinyx-staging-runtime-gate.conf" \
      "$fake/etc/systemd/system/docker.service.d/20-trinyx-staging-runtime-gate.conf"
    diff -u \
      "$ROOT/platform/host/cloud/systemd/staging/trinyx-cloud-runtime-materialize.service.d/20-trinyx-staging-retrigger.conf" \
      "$fake/etc/systemd/system/trinyx-cloud-runtime-materialize.service.d/20-trinyx-staging-retrigger.conf"
    test ! -e "$fake/etc/trinyx/staging/cloud/active"
  else
    diff -u \
      "$ROOT/platform/contracts/ssm/paid-required.txt" \
      "$fake/etc/trinyx/staging/paid/config/ssm-required.txt"
    diff -u \
      "$ROOT/platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/paid-runtime.override.yml" \
      "$fake/etc/trinyx/staging/paid/config/paid-runtime.override.yml"
    diff -u \
      "$ROOT/platform/host/paid/systemd/staging/docker.service.d/20-trinyx-staging-runtime-gate.conf" \
      "$fake/etc/systemd/system/docker.service.d/20-trinyx-staging-runtime-gate.conf"
    diff -u \
      "$ROOT/platform/host/paid/systemd/staging/trinyx-paid-runtime-materialize.service.d/20-trinyx-staging-retrigger.conf" \
      "$fake/etc/systemd/system/trinyx-paid-runtime-materialize.service.d/20-trinyx-staging-retrigger.conf"
    test ! -e "$fake/etc/trinyx/staging/paid/active"
  fi

  echo keep-me > "$fake/unmanaged-sentinel"

  local plan2="$TMP/${role}-plan2.txt"
  python3 "$RECONCILER" --role "$role" --inventory "$INVENTORY" --root "$fake" > "$plan2"
  grep -Fxq "HOST_RECONCILE_PLAN_OK role=$role environment=staging changes=0" "$plan2"

  chmod 0777 "$fake/etc/docker/daemon.json"
  printf '\n# drift\n' >> "$fake/etc/docker/daemon.json"

  local drift="$TMP/${role}-drift.txt"
  python3 "$RECONCILER" --role "$role" --inventory "$INVENTORY" --root "$fake" > "$drift"
  grep -Fq 'target=/etc/docker/daemon.json' "$drift"
  grep -Fq 'CONTENT' "$drift"

  python3 "$RECONCILER" --role "$role" --inventory "$INVENTORY" --root "$fake" --apply >/dev/null
  test "$(stat -c %a "$fake/etc/docker/daemon.json")" = 644
  diff -u \
    "$ROOT/platform/bootstrap/cloud/staging/rootfs/etc/docker/daemon.json" \
    "$fake/etc/docker/daemon.json"
  grep -Fxq 'keep-me' "$fake/unmanaged-sentinel"

  local plan3="$TMP/${role}-plan3.txt"
  python3 "$RECONCILER" --role "$role" --inventory "$INVENTORY" --root "$fake" > "$plan3"
  grep -Fxq "HOST_RECONCILE_PLAN_OK role=$role environment=staging changes=0" "$plan3"
}

run_role cloud
run_role paid

diff -u \
  "$ROOT/platform/contracts/ssm/cloud-required.txt" \
  "$ROOT/platform/bootstrap/cloud/staging/rootfs/etc/trinyx/staging/cloud/config/ssm-required.txt"
diff -u \
  "$ROOT/platform/contracts/ssm/paid-required.txt" \
  "$ROOT/platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/ssm-required.txt"

echo HOST_RECONCILER_CONTRACT_OK
