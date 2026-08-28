#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "${tmp}"' EXIT; mkdir -p "${tmp}/digests"
for n in 1 2 3 4 5; do jq -n --arg image "registry/image${n}" --arg digest "sha256:${n}${n}${n}"   --arg staging "registry/image${n}:staging" '{image:$image,digest:$digest,staging:$staging}' >"${tmp}/digests/${n}.json"; done
cat >"${tmp}/inspect" <<'STUB'
#!/usr/bin/env bash
awk -F '	' -v ref="$1" '$1 == ref { print $2; exit }' "${MAP_FILE}"
STUB
chmod +x "${tmp}/inspect"; export INSPECT_DIGEST_COMMAND="${tmp}/inspect" MAP_FILE="${tmp}/map"
for n in 1 2 3 4 5; do printf 'registry/image%s:staging	sha256:%s%s%s
' "${n}" "${n}" "${n}" "${n}" >>"${MAP_FILE}"; done
"${root}/scripts/release/preflight-immutable-images.sh" "${tmp}/digests" v1.2.3 abc
for n in 1 2 3 4 5; do printf 'registry/image%s:v1.2.3	sha256:%s%s%s
' "${n}" "${n}" "${n}" "${n}" >>"${MAP_FILE}"; printf 'registry/image%s:abc	sha256:%s%s%s
' "${n}" "${n}" "${n}" "${n}" >>"${MAP_FILE}"; done
"${root}/scripts/release/preflight-immutable-images.sh" "${tmp}/digests" v1.2.3 abc
for ref in registry/image1:v1.2.3 registry/image5:v1.2.3 registry/image3:abc; do
  cp "${MAP_FILE}" "${tmp}/clean"; awk -F '	' -v ref="${ref}" 'BEGIN{OFS="\t"} $1==ref{$2="sha256:conflict"} {print}' "${tmp}/clean" >"${MAP_FILE}"
  ! "${root}/scripts/release/preflight-immutable-images.sh" "${tmp}/digests" v1.2.3 abc
  mv "${tmp}/clean" "${MAP_FILE}"
done
bare="${tmp}/origin.git"; work="${tmp}/work"; git init --bare "${bare}" >/dev/null; git init -b main "${work}" >/dev/null
git -C "${work}" config user.email test@trinyx.invalid; git -C "${work}" config user.name Test
git -C "${work}" commit --allow-empty -m release >/dev/null; git -C "${work}" remote add origin "${bare}"; git -C "${work}" push -u origin main >/dev/null
commit="$(git -C "${work}" rev-parse HEAD)"; git -C "${work}" tag v1.2.3; git -C "${work}" push origin v1.2.3 >/dev/null
( cd "${work}" && "${root}/scripts/release/revalidate-release-source.sh" v1.2.3 "${commit}" )
git -C "${work}" commit --allow-empty -m moved >/dev/null; git -C "${work}" tag -f v1.2.3; git -C "${work}" push --force origin v1.2.3 >/dev/null
! ( cd "${work}" && "${root}/scripts/release/revalidate-release-source.sh" v1.2.3 "${commit}" )
git -C "${work}" push --delete origin v1.2.3 >/dev/null
! ( cd "${work}" && "${root}/scripts/release/revalidate-release-source.sh" v1.2.3 "${commit}" )
