#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 2 ]]; then
  echo "usage: $0 <app-image@digest> <landing-image@digest>" >&2
  exit 2
fi

app_ref="$1"
landing_ref="$2"
image_pattern='^ghcr[.]io/trinyxai/[a-z0-9-]+@sha256:[0-9a-f]{64}$'
[[ "${app_ref}" =~ ${image_pattern} ]] || {
  echo "invalid app image reference" >&2
  exit 1
}
[[ "${landing_ref}" =~ ${image_pattern} ]] || {
  echo "invalid landing image reference" >&2
  exit 1
}

printf 'app_ref=%q\n' "${app_ref}"
printf 'landing_ref=%q\n' "${landing_ref}"
cat <<'REMOTE'
set -euo pipefail
cd /home/ubuntu/Trinyx
docker pull "${app_ref}"
docker pull "${landing_ref}"
override=$(mktemp)
trap 'rm -f "${override}"' EXIT
cat >"${override}" <<YAML
services:
  frontend:
    image: ${app_ref}
  landing:
    image: ${landing_ref}
YAML
docker compose -f docker-compose.yml -f "${override}" --env-file docker/.env.ce up -d --no-deps frontend landing
test "$(docker inspect "$(docker compose -f docker-compose.yml -f "${override}" --env-file docker/.env.ce ps -q frontend)" --format '{{.Config.Image}}')" = "${app_ref}"
test "$(docker inspect "$(docker compose -f docker-compose.yml -f "${override}" --env-file docker/.env.ce ps -q landing)" --format '{{.Config.Image}}')" = "${landing_ref}"
REMOTE
