# Release hardening and upstream synchronization

This document defines the reproducibility and compatibility rules for Trinyx
release preparation. It does not authorize a deployment or a stable release.

## Canonical external runtime pins

Production-relevant canonical Compose files use reviewed, version-specific image
tags. The current reviewed set is:

| Component | Pinned image |
| --- | --- |
| PostgreSQL + pgvector | `pgvector/pgvector:0.8.1-pg16` |
| Redis | `redis:7.4.11-alpine` |
| MinIO server | `minio/minio:RELEASE.2025-07-23T15-54-02Z` |
| MinIO client | `minio/mc:RELEASE.2025-07-21T05-28-08Z` |
| SearXNG | `searxng/searxng:2026.8.17-374939b88` |

The root `docker-compose.yml` is a compatibility-only legacy surface and is not
the canonical Trinyx production definition. Do not rewrite its historical
LiveContext identifiers or volumes as part of a routine dependency update.

To update a pin:

1. review upstream release notes and image provenance;
2. update every canonical Compose and CI smoke reference together;
3. render each Compose model and run the CE, paid-monolith and Cloud gates;
4. test persistent-volume upgrade and rollback in staging;
5. record the reviewed versions in this table;
6. deploy only the captured image digest, never a mutable alias.

## GitHub Actions

Release, registry, npm and AWS deployment workflows pin every third-party action
to a full commit SHA. The trailing comment records the human-readable major
release. Updates must review the action's upstream release and compare the old
and new commits before changing the SHA. Job permissions remain local to the
jobs that need package writes, OIDC or GitHub Release writes.

## Stable release manifest

The stable workflow produces `release-manifest.json` with the Trinyx version,
release tag, exact Git commit, five GHCR image digests, immutable patch/SHA
references, OCI SBOM and provenance subjects, and npm package version,
integrity, tarball and provenance references. The workflow retains the Actions
artifact for 365 days and attaches the identical file to the GitHub Release.
The workflow code may be reviewed on a pull request, but publication is gated
to an exact stable tag and is never executed by PR validation. If a registry
mutation succeeded before a later job failed, use **Re-run failed jobs** so the
original staged digests and provenance remain the recovery source. Do not use
**Re-run all jobs** after partial immutable promotion; rebuilding can produce a
new digest that the immutable-tag preflight must correctly refuse.

## CloudLink topology

CloudLink pending OAuth state contains the tenant binding, PKCE verifier,
expiry and single-consumption state in one process-local map. The supported
topology is therefore exactly one CloudLink-enabled publication runtime.
`cloud-link.replica-count=1` and
`cloud-link.pending-state-store=in-memory` are startup configuration invariants.
They are a declared topology contract, not physical singleton discovery: every
runtime must receive the truthful replica count from deployment configuration.

Do not horizontally scale a CloudLink-enabled publication service and do not
work around the guard with a false replica count. Before multi-replica support,
implement a shared TTL-capable store with atomic consume semantics and preserve
tenant binding, PKCE, expiry and replay protection. Distributed Cloud currently
keeps CloudLink disabled.

## LiveContext upstream refs

Bare `vX.Y.Z` tags are reserved exclusively for Trinyx releases. LiveContext
source refs must use `upstream/livecontext/vX.Y.Z`, or the synchronization must
record and use an exact upstream commit SHA. Never import upstream tags into the
bare Trinyx namespace and never rewrite existing Git history to resolve a
collision.

## Portainer compatibility notice

The canonical template is `templates/portainer/trinyx-ce.json`. No tracked
consumer references the retired
`templates/portainer/livecontext-ce.json` raw path. External consumers that
pinned that raw URL must update it during the Trinyx v0.2.13 transition; the old
product-branded path is not reintroduced. Historical database, volume, Java
package, schema, `X-LiveContext-Install-ID` and attribution identifiers remain
unchanged where compatibility requires them.
