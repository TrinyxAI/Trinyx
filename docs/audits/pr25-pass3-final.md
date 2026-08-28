# PR25 Pass 3 final hardening report

Audit basis: GitHub-side source, workflow and repository metadata inspection.
No merge, deployment, tag, registry publication, npm publication, database
mutation or GitHub Release was performed.

## Verdict

**MERGE VERDICT: CONDITIONAL GO**

The source is ready for merge only after the external repository-governance and
real-staging conditions below are satisfied. PR25 must remain Draft until the
owner verifies those conditions.

**PRODUCTION VERDICT: NO-GO**

Production requires real registry publication, digest-pinned staging and the
failure-mode exercises below. Source tests do not simulate those results.

**No source correction is currently required before merging PR25 into main.**

At the time of this audit the repository is public, no repository ruleset is
visible, and branch-protection evidence is unavailable. Those are external
NO-GO conditions, not source defects.

## Final matrices

### LiveContext parity

| Invariant | Classification | Evidence |
| --- | --- | --- |
| Engine behavior remains upstream, not reimplemented | PASS SOURCE | Trinyx changes are adapters in branding, Gateway, CloudLink, billing, security, deployment and release paths. |
| Upstream Flyway history | PASS SOURCE / PASS RUNTIME CI | Exact Git-blob manifest plus PostgreSQL lifecycle and target-434 upgrade contracts. |
| V149, V150 and Java V151 | PASS SOURCE | Current blobs equal LiveContext v0.2.13 blobs. |
| EditableWorkflowTwin on-demand semantics | PASS SOURCE / PASS RUNTIME CI | Existing controller/service contracts remain selected in the publication test graph. |
| Folders, generation, credentials and tool approvals | PASS SOURCE | No disabling or duplicate replacement controller found; Gateway routes the existing controllers. |
| CE local workflow/account semantics | PASS SOURCE / PASS RUNTIME CI | CE defaults, key persistence and edition contracts boot from the candidate image. |
| Real user/provider parity | REQUIRES STAGING | Requires actual OAuth, providers, browsers and persistent data. |

### Security, finance, tenancy and connectivity

| Area | Classification | Remaining boundary |
| --- | --- | --- |
| Gateway HMAC v2, anti-spoofing, path canonicalization | PASS SOURCE / PASS RUNTIME CI | Adversarial real-proxy tests remain staging. |
| Internal service-specific HMAC | PASS SOURCE / PASS RUNTIME CI | Network-policy enforcement remains staging. |
| Ed25519 workload JWT and replay protection | PASS SOURCE / PASS RUNTIME CI | Real key rotation and Redis failover remain staging. |
| Exact organization payer and financial state machine | PASS SOURCE / PASS RUNTIME CI | Stripe/provider receipts and process-kill reconciliation remain staging. |
| Browser fail-closed accounting | PASS SOURCE / PASS RUNTIME CI | Cancellation and ambiguous provider outcomes remain staging. |
| Redis and PostgreSQL settlement fencing | PASS SOURCE / PASS RUNTIME CI | AOF restart, HA and lost-ACK tests remain staging. |
| Workspace purge and S3 erasure outbox | PASS SOURCE / PASS RUNTIME CI | Real MinIO/S3 retries and absent-object behavior remain staging. |
| Cross-tenant isolation | PASS SOURCE / PASS RUNTIME CI | Full negative matrix across real services remains staging. |
| Gateway route inventory | PASS SOURCE / PASS RUNTIME CI | DNS, TLS, Caddy and security groups remain staging. |

### Deployment profiles

| Profile | PASS SOURCE | PASS RUNTIME CI | REQUIRES STAGING |
| --- | --- | --- | --- |
| CE | Upstream capabilities and compatibility identifiers preserved | Image builds, default boot, key-volume persistence | OAuth/provider/browser flows and persistent-volume upgrade |
| paid-monolith | Native semantics preserved; Trinyx billing and CloudLink are conditional | Image build, frontend build and liveness boot | Stripe test mode, CloudLink PKCE over TLS, rollback |
| distributed Cloud | Gateway, S2S, external billing, tenancy and Flyway wiring present | Compose/contracts, PostgreSQL/Testcontainers and targeted security tests | Full multi-process topology, Redis HA, private edge and provider failures |

### Branding, Flyway and release supply chain

| Area | Classification | Result |
| --- | --- | --- |
| User-facing Trinyx branding | PASS SOURCE / PASS RUNTIME CI | Guard keeps compatibility/history identifiers legal. |
| Compatibility identifiers | PASS SOURCE | Database, volumes, Java packages, schemas, attribution and `X-LiveContext-Install-ID` are not blindly renamed. |
| Portainer path | PASS SOURCE | Canonical `trinyx-ce.json`; old raw-path users receive a compatibility notice. |
| Canonical external images | PASS SOURCE | Version-specific pgvector, Redis, MinIO, mc and SearXNG tags. |
| Privileged GitHub Actions | PASS SOURCE | Full commit SHA pins and job-local permissions. |
| Frontend production deployment | PASS SOURCE | Manual main-only dispatch, production environment, serialization and exact digests. |
| Backend main publication | PASS SOURCE | SHA tag only; no automatic mutable `latest`. |
| Stable release manifest | PASS SOURCE | Commit, version, five digests, npm integrity and SBOM/provenance references; 365-day artifact plus GitHub Release attachment. |
| Real GHCR/npm/GitHub Release | REQUIRES EXTERNAL GATE | The PR path performs none of these mutations. |
| LiveContext tag namespace | PASS SOURCE | Bare `vX.Y.Z` reserved for Trinyx; upstream refs use `upstream/livecontext/vX.Y.Z` or an exact SHA. |

## CloudLink replica policy

Pending OAuth state remains process-local by design. A CloudLink-enabled runtime
now fails startup unless it declares the implemented
`pending-state-store=in-memory` and `replica-count=1` topology. Multi-replica
CloudLink is unsupported until a shared TTL store provides tenant binding, PKCE,
expiry, atomic single-consumption and replay protection. Distributed Cloud keeps
CloudLink disabled.

## Exact external production gates

Do not mark an item successful without observing it in the real target system.

- [ ] repository becomes private
- [ ] Actions retention is configured and verified at 365 days or more
- [ ] bare Trinyx stable tags are protected by a repository ruleset
- [ ] GHCR organization/package permissions are verified
- [ ] `NPM_TOKEN` is configured with minimum required scope
- [ ] stable tag is created only after merge and staging gates
- [ ] five images are published by the stable workflow
- [ ] five immutable image digests are captured and verified
- [ ] `trinyx` npm package is published and integrity is verified
- [ ] GitHub Release exists and contains the identical `release-manifest.json`
- [ ] staging is deployed by digest, not a mutable tag
- [ ] Stripe real test-mode checkout/webhook/delinquency flows pass
- [ ] Keycloak login, refresh and organization switching pass
- [ ] CloudLink/PKCE completes through real DNS and TLS
- [ ] Redis AOF restart, restore and HA/failover pass
- [ ] process kills around DISPATCHING, UNKNOWN and ACK loss reconcile correctly
- [ ] MinIO/S3 deletion retries and absent-object idempotency pass
- [ ] provider calls and token/cost reconciliation match authoritative receipts
- [ ] browser cancellation and ambiguous outcomes retain the correct hold
- [ ] cross-tenant negative tests pass through every real service boundary
- [ ] DNS, TLS, Caddy allowlists and security groups are independently verified

Until every applicable item is evidenced, production remains NO-GO.
