#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
digest="sha256:$(printf 'a%.0s' {1..64})"
rendered="$(bash "${root}/scripts/deploy/render-frontend-ssm-command.sh" \
  "ghcr.io/trinyxai/trinyx-frontend@${digest}" \
  "ghcr.io/trinyxai/trinyx-landing@${digest}")"

grep -Fq 'override=$(mktemp)' <<<"${rendered}"
grep -Fq 'trap '\''rm -f "${override}"'\'' EXIT' <<<"${rendered}"
grep -Fq '$(docker inspect "$(docker compose' <<<"${rendered}"
grep -Fq 'image: ${app_ref}' <<<"${rendered}"
grep -Fq "app_ref=ghcr.io/trinyxai/trinyx-frontend@${digest}" <<<"${rendered}"
! bash "${root}/scripts/deploy/render-frontend-ssm-command.sh" \
  'ghcr.io/trinyxai/trinyx-frontend:latest' \
  "ghcr.io/trinyxai/trinyx-landing@${digest}"
