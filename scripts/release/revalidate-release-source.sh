#!/usr/bin/env bash
set -euo pipefail
tag="${1:?release tag required}"
release_commit="${2:?release commit required}"
git fetch --force --no-tags origin '+refs/heads/main:refs/remotes/origin/main'
git fetch --force --tags origin
tag_ref="refs/tags/${tag}"
git show-ref --verify --quiet "${tag_ref}" || { echo "Release tag ${tag} no longer exists" >&2; exit 1; }
actual="$(git rev-parse "${tag_ref}^{commit}")"
[[ "${actual}" == "${release_commit}" ]] || { echo "Release tag moved: ${actual} != ${release_commit}" >&2; exit 1; }
git merge-base --is-ancestor "${release_commit}" origin/main || { echo "Release commit left origin/main" >&2; exit 1; }
