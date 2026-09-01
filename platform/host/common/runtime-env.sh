#!/usr/bin/env bash
set -euo pipefail

TRINYX_PLATFORM_ENV_FILE=${TRINYX_PLATFORM_ENV_FILE:-/etc/trinyx/platform/environment.env}

trinyx_env_get() {
    local key="$1"
    local file="${2:-$TRINYX_PLATFORM_ENV_FILE}"
    local count

    [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] || {
        echo "ERROR_INVALID_PLATFORM_ENV_KEY=$key" >&2
        return 1
    }

    test -s "$file" || {
        echo "ERROR_PLATFORM_ENV_FILE_MISSING=$file" >&2
        return 1
    }

    count=$(awk -F= -v key="$key" '$1 == key {n++} END {print n+0}' "$file")
    test "$count" = 1 || {
        echo "ERROR_PLATFORM_ENV_KEY_COUNT_${key}=$count" >&2
        return 1
    }

    awk -F= -v key="$key" '$1 == key {print substr($0, length(key)+2); exit}' "$file"
}

trinyx_require_platform_environment() {
    local environment region

    environment=$(trinyx_env_get TRINYX_ENVIRONMENT)
    region=$(trinyx_env_get AWS_REGION)

    case "$environment" in
        staging|production) ;;
        *)
            echo "ERROR_INVALID_TRINYX_ENVIRONMENT=$environment" >&2
            return 1
            ;;
    esac

    [[ "$region" =~ ^[a-z]{2}-[a-z]+-[0-9]+$ ]] || {
        echo "ERROR_INVALID_AWS_REGION=$region" >&2
        return 1
    }
}
