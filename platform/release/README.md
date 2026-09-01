# Trinyx immutable release contract

A release is a content-addressed description of exactly what may be deployed. It is independent from a mutable Git branch and from environment deployment state.

## Identity

`releaseId` is `rel-v1-<32 hex>` derived from a SHA-256 of the canonical identity payload: schema version, source commit, platform/config revision and the sorted image inventory. `sourceRef` and `createdAt` are provenance metadata and do not alter release identity.

## Complete runtime image contract

`runtime-inventory.json` is the canonical hosted-runtime inventory. A deployable release must contain every listed Cloud and Paid service exactly once. This includes Trinyx-owned images and third-party runtime dependencies such as PostgreSQL/pgvector, Redis, MinIO, SearXNG, LiveContext Bridge and Caddy.

Every runnable reference must be `<package>@sha256:<64 lowercase hex>`. Mutable tags such as `latest`, `7-alpine` or `pg16` may remain in the upstream/developer base Compose files, but the controlled Cloud and Paid runtime overrides must replace every hosted service with the digest from the release before deployment.

The separate public staging Caddy container is classified as platform/edge state rather than an application-release service. Its host/platform automation must also remain digest-pinned; it is not part of the 28-service application release inventory.

## State separation

The release manifest is immutable. Staging approval, active deployment, rollback history and production promotion are separate records that reference a `releaseId`; they never rewrite the release itself.

A production promotion must consume the exact image digest set from the staging-approved release. A merge commit may differ from the tested source commit, but the promotion gate must prove that the tested source tree is integrated into `main` before production use.

The legacy `stg-bootstrap-001` fixture records the desired staging image set discovered during migration to this contract. The historical Cloud migration container from an older source commit is intentionally not treated as desired state; the release uses the active migration digest associated with the staging candidate.
