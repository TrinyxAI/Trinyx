#!/usr/bin/env bash
set -euo pipefail
umask 077

. /usr/local/lib/trinyx/runtime-env.sh
trinyx_require_platform_environment

export AWS_PAGER=""
export AWS_RETRY_MODE=standard
export AWS_MAX_ATTEMPTS=3

ENVIRONMENT=$(trinyx_env_get TRINYX_ENVIRONMENT)
REGION=$(trinyx_env_get AWS_REGION)
BASE=$(trinyx_env_get CLOUD_BASE)
SSM_PATH=$(trinyx_env_get CLOUD_SSM_PATH)
CLOUD_IP=$(trinyx_env_get CLOUD_PRIVATE_IP)

EXPECTED_BASE="/etc/trinyx/${ENVIRONMENT}/cloud"
EXPECTED_SSM_PATH="/trinyx/${ENVIRONMENT}/cloud/"

test "$BASE" = "$EXPECTED_BASE" || {
    echo ERROR_CLOUD_BASE_ENVIRONMENT_MISMATCH >&2
    exit 1
}
test "$SSM_PATH" = "$EXPECTED_SSM_PATH" || {
    echo ERROR_CLOUD_SSM_PATH_ENVIRONMENT_MISMATCH >&2
    exit 1
}

ACTIVE="$BASE/active"
CONFIG="$BASE/config"
SCHEMA="$CONFIG/ssm-required.txt"
STATIC="$CONFIG/runtime-static.env"
AUTH_CONFIG="$CONFIG/cloud-auth-files.sh"

RUNDIR=/run/trinyx
GENERATIONS="$RUNDIR/cloud-materialized"
CURRENT="$RUNDIR/cloud-current"

test -s "$ACTIVE/manifest.env"
test -s "$ACTIVE/images.env"
test -s "$CONFIG/cloud-paid.override.yml"
test -s "$SCHEMA"
test -s "$STATIC"
test -s "$AUTH_CONFIG"
test -s "$RUNDIR/auth-runtime/paid-monolith-truststore.p12"
test -s "$RUNDIR/auth-runtime/paid-monolith-truststore-password"

install -d -o root -g root -m 0700 "$RUNDIR" "$GENERATIONS"
TMP=$(mktemp -d "$GENERATIONS/.staging.XXXXXX")

cleanup() {
    if test -n "${TMP:-}" && test -e "$TMP"; then
        rm -rf "$TMP"
    fi
}
trap cleanup EXIT

install -o root -g root -m 0600 "$ACTIVE/images.env" "$TMP/cloud-images.env"
install -o root -g root -m 0600 "$CONFIG/cloud-paid.override.yml" "$TMP/cloud-paid.override.yml"
install -o root -g root -m 0600 "$AUTH_CONFIG" "$TMP/cloud-auth-files.sh"

aws ssm get-parameters-by-path \
    --path "$SSM_PATH" \
    --recursive \
    --with-decryption \
    --region "$REGION" \
    --cli-connect-timeout 5 \
    --cli-read-timeout 20 \
    --output json \
    > "$TMP/ssm.json"

python3 - \
    "$SCHEMA" \
    "$STATIC" \
    "$TMP/ssm.json" \
    "$TMP/cloud.runtime.active.sh" \
    "$CLOUD_IP" <<'PY'
import ipaddress
import json
import os
import re
import shlex
import sys

schema_path, static_path, json_path, output_path, internal_bind = sys.argv[1:6]

with open(schema_path, "r", encoding="utf-8") as fh:
    required = [line.strip() for line in fh if line.strip() and not line.lstrip().startswith("#")]

if not required:
    raise SystemExit("ERROR_EMPTY_CLOUD_SSM_SCHEMA")
if len(required) != len(set(required)):
    raise SystemExit("ERROR_DUPLICATE_CLOUD_SSM_SCHEMA_NAME")
for name in required:
    if not re.fullmatch(r"[A-Z][A-Z0-9_]*", name):
        raise SystemExit(f"ERROR_INVALID_CLOUD_SSM_SCHEMA_NAME={name}")

expected_ssm = set(required)
static = {}
with open(static_path, "r", encoding="utf-8") as fh:
    for raw in fh:
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise SystemExit("ERROR_INVALID_CLOUD_STATIC_LINE")
        name, value = line.split("=", 1)
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*", name):
            raise SystemExit(f"ERROR_INVALID_CLOUD_STATIC_NAME={name}")
        if name in static:
            raise SystemExit(f"ERROR_DUPLICATE_CLOUD_STATIC_NAME={name}")
        static[name] = value

expected_static = {
    "CLOUD_DB_USERNAME",
    "CLOUD_PUBLIC_URL",
    "KEYCLOAK_PUBLIC_URL",
    "PAID_PUBLIC_URL",
}
if set(static) != expected_static:
    raise SystemExit("ERROR_CLOUD_STATIC_SCHEMA_MISMATCH")

with open(json_path, "r", encoding="utf-8") as fh:
    data = json.load(fh)

values = {}
for parameter in data.get("Parameters", []):
    name = str(parameter["Name"]).rsplit("/", 1)[-1]
    if name == "probe":
        continue
    if not re.fullmatch(r"[A-Z][A-Z0-9_]*", name):
        raise SystemExit(f"ERROR_INVALID_CLOUD_SSM_NAME={name}")
    if name in values:
        raise SystemExit(f"ERROR_DUPLICATE_CLOUD_SSM_NAME={name}")
    values[name] = str(parameter["Value"])

actual_ssm = set(values)
missing = sorted(expected_ssm - actual_ssm)
extra = sorted(actual_ssm - expected_ssm)
if missing:
    print("ERROR_MISSING_CLOUD_SSM_NAMES=" + ",".join(missing), file=sys.stderr)
if extra:
    print("ERROR_EXTRA_CLOUD_SSM_NAMES=" + ",".join(extra), file=sys.stderr)
if missing or extra:
    raise SystemExit(1)

try:
    address = ipaddress.ip_address(internal_bind)
except ValueError:
    raise SystemExit("ERROR_INVALID_CLOUD_INTERNAL_BIND")
private_ranges = (
    ipaddress.ip_network("10.0.0.0/8"),
    ipaddress.ip_network("172.16.0.0/12"),
    ipaddress.ip_network("192.168.0.0/16"),
)
if address.version != 4 or not any(address in network for network in private_ranges):
    raise SystemExit("ERROR_INVALID_CLOUD_INTERNAL_BIND")

with open(output_path, "x", encoding="utf-8", newline="\n") as fh:
    fh.write("#!/usr/bin/env bash\n")
    fh.write("set -a\n")
    for name in sorted(required):
        fh.write(f"export {name}={shlex.quote(values[name])}\n")
    for name in sorted(static):
        fh.write(f"export {name}={shlex.quote(static[name])}\n")
    fh.write("export CLOUD_INTERNAL_BIND=" + shlex.quote(internal_bind) + "\n")
    fh.write(". /run/trinyx/cloud-auth-files.sh\n")
    fh.write("set +a\n")
os.chmod(output_path, 0o600)
PY

rm -f "$TMP/ssm.json"

for FILE in \
    "$TMP/cloud-images.env" \
    "$TMP/cloud-paid.override.yml" \
    "$TMP/cloud-auth-files.sh" \
    "$TMP/cloud.runtime.active.sh"
do
    test -s "$FILE"
    test "$(stat -c %u "$FILE")" = 0
    test "$(stat -c %g "$FILE")" = 0
    test "$(stat -c %a "$FILE")" = 600
done

bash -n "$TMP/cloud-auth-files.sh"
bash -n "$TMP/cloud.runtime.active.sh"

GEN="gen-$(date -u +%Y%m%dT%H%M%SZ)-$$-$RANDOM"
FINAL="$GENERATIONS/$GEN"
test ! -e "$FINAL"
mv "$TMP" "$FINAL"
TMP=""

LINKTMP="$RUNDIR/.cloud-current.$$.$RANDOM"
ln -s "cloud-materialized/$GEN" "$LINKTMP"
mv -Tf "$LINKTMP" "$CURRENT"

publish_link() {
    local name="$1"
    local target="$2"
    local tmp="$RUNDIR/.${name}.link.$$.$RANDOM"
    ln -s "$target" "$tmp"
    mv -Tf "$tmp" "$RUNDIR/$name"
}

publish_link cloud-images.env cloud-current/cloud-images.env
publish_link cloud-paid.override.yml cloud-current/cloud-paid.override.yml
publish_link cloud-auth-files.sh cloud-current/cloud-auth-files.sh
publish_link cloud.runtime.active.sh cloud-current/cloud.runtime.active.sh

test -L "$CURRENT"
test -d "$(readlink -f "$CURRENT")"
REAL=$(readlink -f "$CURRENT")
case "$REAL" in
    "$GENERATIONS"/gen-*) ;;
    *) echo ERROR_INVALID_CLOUD_GENERATION >&2; exit 1 ;;
esac
test ! -e "$REAL/ssm.json"
