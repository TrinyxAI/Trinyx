# Trinyx immutable release contract

A release is a content-addressed, environment-independent description of exactly what may be promoted. It is independent from a mutable Git branch and from staging/production deployment state.

## Identity

`releaseId` is `rel-v1-<32 hex>` derived from a SHA-256 of the canonical identity payload: schema version, source commit, platform commit, deterministic deployment-bundle identity and the sorted image inventory. `sourceRef` and `createdAt` are provenance metadata and do not alter release identity.

Environment-specific configuration is deliberately **not** release identity. Values such as staging/production URLs, private IPs, SSM namespaces, PKI paths, KMS identifiers and other environment inventory belong to a deployment record. This separation is required so an approved staging release can be promoted to production without rebuilding or changing any image digest or deployment asset.

## Complete runtime image contract

`runtime-inventory.json` is the canonical hosted-runtime inventory. A deployable release must contain every listed Cloud and Paid service exactly once. This includes Trinyx-owned images and third-party runtime dependencies such as PostgreSQL/pgvector, Redis, MinIO, SearXNG, LiveContext Bridge and Caddy.

Every runnable reference must be `<package>@sha256:<64 lowercase hex>`. Mutable tags such as `latest`, `7-alpine` or `pg16` may remain in upstream/developer base Compose files, but controlled Cloud and Paid runtime overrides must replace every hosted service with the digest from the release before deployment.

`third-party-images.json` records the pinned third-party runtime dependencies. `assemble-release-images.py` combines those pins with the Cloud, backend and frontend build manifests and requires all 28 canonical runtime bindings exactly once and from the requested source commit.

The separate public staging Caddy container is classified as platform/edge state rather than an application-release service. Its host/platform automation must also remain digest-pinned; it is not part of the 28-service application release inventory.

## Deterministic deployment bundle

Images alone are not sufficient for a reproducible Compose deployment. `deployment-bundle-files.json` lists the environment-independent Compose files and local runtime assets required by the hosted Cloud/Paid stacks. `build-deployment-bundle.py` packages them into a deterministic uncompressed tar archive with normalized ownership, explicit per-file modes and timestamps.

The historical aeb2 baseline uses `historical-deployment-bundle-sources.json` to authenticate each bundle path's origin. Nine contracted paths come byte-for-byte from the exact historical tree. The sole trusted-builder overlay is `docker/docker-compose.paid.runtime.yml`, which postdates aeb2 and is a fixed trusted service-and-image overlay: it binds the exact canonical immutable images for all eight Paid services. The `paid-edge` service definition itself remains approved environment-specific deployment configuration; no other current release content is imported. Preparation independently verifies the historical Git HEAD and clean status, rejects symlinked repository roots or path traversal, and rejects an overlay that shadows historical content, an unapproved overlay, a missing path or incomplete coverage of the normal bundle contract. No other current-tree deployment content may replace historical source data.

The bundle SHA-256 is part of `releaseId`. Changing a Compose file, Keycloak realm, SearXNG settings, catalog seed, Caddyfile or other contracted deployment asset therefore creates a different release even when every container digest is unchanged.

The deployment-bundle manifest intentionally remains `schemaVersion: 1` while modern file entries carry the authenticated `mode` field. This is a compatibility boundary, not an unversioned relaxation: every modern entry must contain exactly `path`, `digest`, `sizeBytes` and `mode`; only the exact frozen historical release is allowed its legacy mode-less shape. A schema-number bump would change the release/registry contract and requires a coordinated, separately reviewed control-plane migration, so no implicit v2 is introduced here.

The bundle contains no environment inventory or runtime secret material. Environment overrides, private addresses, TLS/PKI paths and secret namespaces remain deployment state.

## State separation

The release manifest is immutable. Installation, environment configuration, staging approval, active deployment, rollback history and production promotion are separate records that reference a `releaseId`; they never rewrite the release itself.

A production promotion must consume the exact image digest set and exact deployment-bundle digest from the staging-approved release. A merge commit may differ from the tested source commit, but the promotion gate must prove that the tested source tree is integrated into `main` before production use.

The legacy `stg-bootstrap-001` fixture records the desired staging image set discovered during migration to this contract. The historical Cloud migration container from an older source commit is intentionally not treated as desired state; the release uses the desired migration digest associated with the staging candidate.
