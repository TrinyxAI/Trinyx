#!/usr/bin/env bash
set -euo pipefail

metadata="${1:?npm metadata path is required}"
release_version="${2:?release version is required}"
[[ -f "${metadata}" ]] || {
  echo "Missing npm release metadata: ${metadata}" >&2
  exit 1
}

package_name="$(jq -er '.[0].name' "${metadata}")"
package_version="$(jq -er '.[0].version' "${metadata}")"
local_integrity="$(jq -er '.[0].integrity' "${metadata}")"
if [[ "${package_name}" != "trinyx" || "${package_version}" != "${release_version}" ]]; then
  echo "npm release candidate identity mismatch" >&2
  exit 1
fi

if [[ -n "${NPM_VIEW_COMMAND:-}" ]]; then
  existing_integrity="$("${NPM_VIEW_COMMAND}" "trinyx@${release_version}" dist.integrity 2>/dev/null || true)"
else
  existing_integrity="$(npm view "trinyx@${release_version}" dist.integrity 2>/dev/null || true)"
fi

if [[ -n "${existing_integrity}" && "${existing_integrity}" != "${local_integrity}" ]]; then
  echo "Refusing release: trinyx@${release_version} already has different integrity" >&2
  exit 1
fi

if [[ -n "${existing_integrity}" ]]; then
  echo "trinyx@${release_version} already matches the release candidate"
else
  echo "trinyx@${release_version} is available for immutable publication"
fi
