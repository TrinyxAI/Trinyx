# Trinyx immutable release contract

A release is a content-addressed description of exactly what may be deployed. It is independent from a mutable Git branch and from environment deployment state.

## Identity

`releaseId` is `rel-v1-<32 hex>` derived from a SHA-256 of the canonical identity payload:

- `schemaVersion`
- `sourceCommit`
- `platformCommit`
- `configRevision`
- the sorted image inventory

`sourceRef` and `createdAt` are provenance metadata and intentionally do not alter the release identity.

## Image contract

Every image entry has a logical `name`, deployment `role`, Compose `service`, environment-variable binding, package, digest and exact immutable reference. The only accepted runnable reference is:

`<package>@sha256:<64 lowercase hex>`

Mutable tags are not release inputs. A deployment must render `images.env` from this manifest and must not rebuild images.

## State separation

The release manifest is immutable. Staging approval, active deployment, rollback history and production promotion are separate records that reference a `releaseId`; they never rewrite the release itself.

A production promotion must consume the exact image digest set from the staging-approved release. A merge commit may differ from the tested source commit, but the promotion gate must prove that the tested source tree is integrated into `main` before production use.
