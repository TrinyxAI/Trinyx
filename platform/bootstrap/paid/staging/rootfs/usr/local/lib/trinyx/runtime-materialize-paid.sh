#!/usr/bin/env bash
set -euo pipefail
umask 077

export AWS_PAGER=""
export AWS_RETRY_MODE=standard
export AWS_MAX_ATTEMPTS=3

BASE=/etc/trinyx/staging/paid
ACTIVE="$BASE/active"
CONFIG="$BASE/config"
SCHEMA="$CONFIG/ssm-required.txt"

RUNDIR=/run/trinyx
GENERATIONS="$RUNDIR/paid-materialized"
CURRENT="$RUNDIR/paid-current"

REGION=us-east-1
SSM_PATH=/trinyx/staging/paid/

test -s "$ACTIVE/manifest.env"
test -s "$ACTIVE/images.env"
test -s "$CONFIG/paid.override.yml"
test -s "$CONFIG/paid-bind.override.yml"
test -s "$CONFIG/paid-runtime.override.yml"
test -s "$SCHEMA"

install -d -o root -g root -m 0700 \
    "$RUNDIR" \
    "$GENERATIONS"

TMP=$(mktemp -d "$GENERATIONS/.staging.XXXXXX")

cleanup() {
    if test -n "${TMP:-}" && test -e "$TMP"; then
        rm -rf "$TMP"
    fi
}

trap cleanup EXIT

install -o root -g root -m 0600 \
    "$ACTIVE/images.env" \
    "$TMP/paid-images.env"

install -o root -g root -m 0600 \
    "$CONFIG/paid.override.yml" \
    "$TMP/paid.override.yml"

install -o root -g root -m 0600 \
    "$CONFIG/paid-bind.override.yml" \
    "$TMP/paid-bind.override.yml"

install -o root -g root -m 0600 \
    "$CONFIG/paid-runtime.override.yml" \
    "$TMP/paid-runtime.override.yml"

aws ssm get-parameters-by-path \
    --path "$SSM_PATH" \
    --recursive \
    --with-decryption \
    --region "$REGION" \
    --cli-connect-timeout 5 \
    --cli-read-timeout 20 \
    --output json \
    > "$TMP/ssm.json"

python3 - "$SCHEMA" "$TMP/ssm.json" "$TMP/paid-secrets.env" <<'PY'
import json
import os
import re
import shlex
import sys

schema_path, json_path, output_path = sys.argv[1:4]

with open(schema_path, "r", encoding="utf-8") as fh:
    required = [
        line.strip()
        for line in fh
        if line.strip() and not line.lstrip().startswith("#")
    ]

if not required:
    raise SystemExit("ERROR_EMPTY_SSM_SCHEMA")

if len(required) != len(set(required)):
    raise SystemExit("ERROR_DUPLICATE_SSM_SCHEMA_NAME")

for name in required:
    if not re.fullmatch(r"[A-Z][A-Z0-9_]*", name):
        raise SystemExit(f"ERROR_INVALID_SSM_SCHEMA_NAME={name}")

expected = set(required)

with open(json_path, "r", encoding="utf-8") as fh:
    data = json.load(fh)

values = {}

for parameter in data.get("Parameters", []):
    name = str(parameter["Name"]).rsplit("/", 1)[-1]

    if name == "probe":
        continue

    if not re.fullmatch(r"[A-Z][A-Z0-9_]*", name):
        raise SystemExit(f"ERROR_INVALID_SSM_NAME={name}")

    if name in values:
        raise SystemExit(f"ERROR_DUPLICATE_SSM_NAME={name}")

    values[name] = str(parameter["Value"])

actual = set(values)

missing = sorted(expected - actual)
extra = sorted(actual - expected)

if missing:
    print(
        "ERROR_MISSING_SSM_NAMES=" + ",".join(missing),
        file=sys.stderr,
    )

if extra:
    print(
        "ERROR_EXTRA_SSM_NAMES=" + ",".join(extra),
        file=sys.stderr,
    )

if missing or extra:
    raise SystemExit(1)

with open(output_path, "x", encoding="utf-8", newline="\n") as fh:
    for name in sorted(required):
        fh.write(f"export {name}={shlex.quote(values[name])}\n")

os.chmod(output_path, 0o600)
PY

# Do not retain the decrypted raw SSM response.
rm -f "$TMP/ssm.json"

for F in \
    "$TMP/paid-images.env" \
    "$TMP/paid.override.yml" \
    "$TMP/paid-bind.override.yml" \
    "$TMP/paid-runtime.override.yml" \
    "$TMP/paid-secrets.env"
do
    test -s "$F"
    test "$(stat -c %u "$F")" = 0
    test "$(stat -c %g "$F")" = 0
    test "$(stat -c %a "$F")" = 600
done

GEN="gen-$(date -u +%Y%m%dT%H%M%SZ)-$$-$RANDOM"
FINAL="$GENERATIONS/$GEN"

test ! -e "$FINAL"

# Complete immutable generation first.
mv "$TMP" "$FINAL"
TMP=""

# Atomic activation point.
LINKTMP="$RUNDIR/.paid-current.$$.$RANDOM"
ln -s "paid-materialized/$GEN" "$LINKTMP"
mv -Tf "$LINKTMP" "$CURRENT"

publish_link() {
    local name="$1"
    local target="$2"
    local tmp="$RUNDIR/.${name}.link.$$.$RANDOM"

    ln -s "$target" "$tmp"
    mv -Tf "$tmp" "$RUNDIR/$name"
}

publish_link \
    paid-images.env \
    paid-current/paid-images.env

publish_link \
    paid.override.yml \
    paid-current/paid.override.yml

publish_link \
    paid-bind.override.yml \
    paid-current/paid-bind.override.yml

publish_link \
    paid-runtime.override.yml \
    paid-current/paid-runtime.override.yml

publish_link \
    paid-secrets.env \
    paid-current/paid-secrets.env

# Temporary backwards compatibility with the current deployment commands.
publish_link \
    paid-runtime-aeb2.override.yml \
    paid-current/paid-runtime.override.yml

test -L "$CURRENT"
test -d "$(readlink -f "$CURRENT")"
test ! -e "$(readlink -f "$CURRENT")/ssm.json"
