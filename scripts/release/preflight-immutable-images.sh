#!/usr/bin/env bash
set -euo pipefail
digest_dir="${1:?digest directory required}"
patch_tag="${2:?patch tag required}"
release_commit="${3:?release commit required}"
inspect_digest() {
  if [[ -n "${INSPECT_DIGEST_COMMAND:-}" ]]; then "${INSPECT_DIGEST_COMMAND}" "$1"
  else docker buildx imagetools inspect "$1" 2>/dev/null | awk '$1 == "Digest:" { print $2; exit }'; fi
}
mapfile -t files < <(find "${digest_dir}" -maxdepth 1 -name '*.json' -type f | sort)
[[ "${#files[@]}" -eq 5 ]] || { echo "Expected five image digests" >&2; exit 1; }
for file in "${files[@]}"; do
  image="$(jq -er '.image' "${file}")"; expected="$(jq -er '.digest' "${file}")"; staging="$(jq -er '.staging' "${file}")"
  [[ "$(inspect_digest "${staging}")" == "${expected}" ]] || { echo "Staging mismatch: ${staging}" >&2; exit 1; }
  for ref in "${image}:${patch_tag}" "${image}:${release_commit}"; do
    existing="$(inspect_digest "${ref}" || true)"
    [[ -z "${existing}" || "${existing}" == "${expected}" ]] || { echo "Immutable conflict: ${ref}" >&2; exit 1; }
  done
done
