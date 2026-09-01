#!/usr/bin/env bash
set -euo pipefail
umask 077

. /usr/local/lib/trinyx/runtime-env.sh
trinyx_require_platform_environment

ENVIRONMENT=$(trinyx_env_get TRINYX_ENVIRONMENT)
TRUSTSTORE_SOURCE=$(trinyx_env_get PAID_MONOLITH_TRUSTSTORE_SOURCE_PATH)
TRUSTSTORE_PASSWORD_SOURCE=$(trinyx_env_get PAID_MONOLITH_TRUSTSTORE_PASSWORD_SOURCE_PATH)

case "$TRUSTSTORE_SOURCE" in
    /opt/trinyx/*) ;;
    *) echo ERROR_INVALID_TRUSTSTORE_SOURCE_PATH >&2; exit 1 ;;
esac

case "$TRUSTSTORE_PASSWORD_SOURCE" in
    /etc/trinyx/*) ;;
    *) echo ERROR_INVALID_TRUSTSTORE_PASSWORD_SOURCE_PATH >&2; exit 1 ;;
esac

if test "$ENVIRONMENT" = production; then
    case "$TRUSTSTORE_SOURCE" in
        /opt/trinyx/production/*) ;;
        *) echo ERROR_PRODUCTION_TRUSTSTORE_NOT_PRODUCTION_SCOPED >&2; exit 1 ;;
    esac
    case "$TRUSTSTORE_PASSWORD_SOURCE" in
        /etc/trinyx/production/*) ;;
        *) echo ERROR_PRODUCTION_TRUSTSTORE_PASSWORD_NOT_PRODUCTION_SCOPED >&2; exit 1 ;;
    esac
fi

install -d -o root -g root -m 0700 /run/trinyx
install -d -o root -g root -m 0700 /run/trinyx/auth-runtime

test -s "$TRUSTSTORE_SOURCE"
test -s "$TRUSTSTORE_PASSWORD_SOURCE"

install -o root -g 1001 -m 0640 \
    "$TRUSTSTORE_SOURCE" \
    /run/trinyx/auth-runtime/paid-monolith-truststore.p12

install -o root -g 1001 -m 0640 \
    "$TRUSTSTORE_PASSWORD_SOURCE" \
    /run/trinyx/auth-runtime/paid-monolith-truststore-password

cmp -s \
    "$TRUSTSTORE_SOURCE" \
    /run/trinyx/auth-runtime/paid-monolith-truststore.p12

cmp -s \
    "$TRUSTSTORE_PASSWORD_SOURCE" \
    /run/trinyx/auth-runtime/paid-monolith-truststore-password

for FILE in \
    /run/trinyx/auth-runtime/paid-monolith-truststore.p12 \
    /run/trinyx/auth-runtime/paid-monolith-truststore-password
do
    test "$(stat -c %u "$FILE")" = 0
    test "$(stat -c %g "$FILE")" = 1001
    test "$(stat -c %a "$FILE")" = 640
done
