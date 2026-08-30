# LiveContext CE v0.2.14 → Trinyx Cloud adaptation audit

## Scope and immutable ancestry

This audit covers the integration of the real LiveContext CE v0.2.14 release into PR25 and the Trinyx-only adaptations required at the CE, paid-monolith and distributed-Cloud boundaries.

- LiveContext v0.2.13: `999aec1208a9eb1ddcc3691e466f0087fc819979`
- LiveContext v0.2.14: `cda5dbe4293f099a7b3c4f8bfe02d44073029aa3`
- Trinyx main after PR28: `99673e28981a861d1e51947bcbc3a18a5f4c06d1`
- PR25 pre-merge head: `4282bee7c6021487d334b70cee8daef8e97ed95e`
- PR25 main-merge commit: `54521e7c4b9277dae6e5bce74091a4d5aa060531`
  - parent 1: `4282bee7c6021487d334b70cee8daef8e97ed95e`
  - parent 2: `99673e28981a861d1e51947bcbc3a18a5f4c06d1`

The upstream commit remains in Git ancestry. No squash, rebase, force-push, reset, copied source snapshot or migration-history rewrite was used.

## 453-file classification

The machine-readable inventory is checked in as
`docs/audits/livecontext-v0.2.14-cloud-adaptation-files.tsv`.

| Class | Count | Meaning |
| --- | ---: | --- |
| `UNCHANGED_UPSTREAM` | 436 | exact upstream merge result; no PR25-specific adaptation |
| `TRINYX_ADAPTATION_REQUIRED` | 13 | product/configuration overlay required at a Trinyx boundary |
| `TRINYX_ADAPTATION_ALREADY_COMPATIBLE` | 4 | existing Trinyx extension composes with v0.2.14 without replacing upstream behavior |
| **Total** | **453** | complete upstream v0.2.14 delta |

The required adaptations are limited to Trinyx identity/release documentation, CE distribution configuration, the Trinyx npm package, localized product copy and the release-feed adapter. The four already-compatible surfaces are Remote Marketplace, credentials UI, agent creation UI and the ChatPage conflict resolution that retains both `useChatHandoff` and upstream `togglePanelFromHeader`.

## Flyway

Status: **PASS SOURCE / PASS RUNTIME CI**

- All historical upstream migrations remain byte-identical.
- Trinyx draft migrations remain separately numbered `V453_1`, `V453_2`, `V453_3`.
- Upstream migrations are preserved byte-for-byte:
  - `V454__ce_install_telemetry.sql`: Git blob `f2f8d8d8cb3babe6f68a1088eb5c32f429598a3d`
  - `V455__repair_table_media_cells.sql`: Git blob `e1325db2ec5b3ca588f9d010e83313b04287abb0`
  - `V456__merge_docs_state_the_all_skipped_rule.sql`: Git blob `303c5b1b7f5e179b13facffcbf26d23d109308f4`
- The integrity manifest now covers 441 upstream migration/callback sources.
- Expected current version is 456 with 444 versioned entries.
- Real pgvector/PostgreSQL contracts cover:
  - clean database → 456 → validate → second migrate no-op;
  - target 434 → 456;
  - representative PR25 v0.2.13 state 453.3 → exactly V454/V455/V456 → validate → second migrate no-op.
- `V149`, `V150`, Java `V151` and all other historical upstream bytes are unchanged.

## CE telemetry and release feed

Status: **PASS SOURCE**

Upstream CE classes are not forked. Trinyx adapts privacy at the configuration layer:

- `CE_VERSIONCHECK_ENABLED=true` remains the Trinyx CE default.
- `CE_VERSIONCHECK_SENDINSTALLID=false` is the Trinyx CE default.
- An operator may explicitly opt in to the anonymous persistent install-ID.
- The Trinyx client feed remains `https://app.trinyx.fr/api/ce/releases/latest`.
- Distributed Cloud uses Keycloak auth; embedded-only CE install-ID beans do not activate there.
- `ce.installs.telemetry.enabled` remains false unless explicitly configured.
- Gateway public routing contains the exact release-feed endpoint but does **not** expose `/api/ce/installs/stats`.
- The CE anonymous install-ID is not reused as the tenant-bound CloudLink install identity.

## run_node batch and financial invariants

Status: **PASS SOURCE / REQUIRES FAILURE-INJECTION STAGING**

Upstream batch behavior remains canonical:

- maximum 20 items;
- maximum parallelism 5;
- timeout 120 seconds;
- every item re-enters the existing single-item `execute()` path;
- every item receives a distinct run/plan identity;
- the credit gate runs per item;
- an item not started before the deadline is `NOT_STARTED` and never reaches provider/billing;
- item failures are isolated.

Trinyx per-call billing uses a fresh server-side call reference and distinct reservation identity for each provider call. The catalog tests prove independent reserve/commit operations and fail closed before provider dispatch when a resold generation has no published applicable price.

The v0.2.14 audit found and closed one Trinyx-only ambiguity defect in the generic Catalog provider path:

| Boundary | Required result |
| --- | --- |
| reserve or dispatch journal fails before provider | provider not called; release is safe |
| external `DISPATCHING` acknowledged, provider returns failure | `OUTCOME_UNKNOWN`; never release |
| provider response is lost/throws after dispatch | `OUTCOME_UNKNOWN`; never release |
| commit response is uncertain after dispatch | `OUTCOME_UNKNOWN`; never release |
| successful call proves the caller's own credential answered | BYOK release remains valid |
| successful platform call | commit once |

Browser accounting already followed this invariant. Catalog now uses the same durable reconciliation boundary without changing native CE accounting.

A real multi-process A/B/C batch with process kills, response loss and reconciliation remains a staging gate; CI is not represented as proof of that external runtime behavior.

## Generation catalog and pricing

Status: **PASS SOURCE / PASS RUNTIME CI**

- The v0.2.14 generation seed contains 59 declared/actual models.
- No seeded model is missing its price.
- Seedance 2.0, 2.5, fast, mini, 480p, 1080p and 4k variants retain their upstream model IDs and unit-specific prices.
- Catalog bundle price tests cover model-specific and bundle price propagation.
- Resolution/unit mismatches fail closed.
- A resold generation without a positive published price fails before reservation/provider dispatch.
- Historical model behavior is not silently remapped.

Real provider invoices/token reconciliation remain staging gates.

## Publication, Marketplace and storage ownership

Status: **PASS SOURCE / PASS RUNTIME CI**

The upstream owner-scope logic remains authoritative:

- `StorageKeys` rejects absolute paths, empty segments, `.`, `..` and duplicate separators.
- publication file resolution accepts only authentic signed URLs or safe relative keys;
- the key must belong to the publisher/source tenant and publication namespace;
- showcase rewriting only signs well-formed publisher-owned keys;
- datasource acquisition rewrites files into the acquiring tenant;
- public snapshots do not expose the publisher tenant ID;
- repair/admin paths are not anonymous-public.

Trinyx layers—Gateway HMAC, tenant delegation, storage operation capabilities, Remote Marketplace and CloudLink—do not bypass these checks. Remote Marketplace continues to use the upstream controller/service semantics, including on-demand editable workflow twins.

The already documented exact-secret consumption boundary remains unchanged: authorized compute services can consume individual credentials. Bulk discovery is tenant-delegated and secret-free; a future job-scoped secret broker is not falsely claimed as part of this PR.

## Datasource file/image normalization

Status: **PASS SOURCE / PASS RUNTIME CI**

- V455 is byte-identical upstream.
- file/image normalization and repair tests run against the current source.
- public file URLs are built from the configured public/Gateway origin.
- private Docker DNS, storage-service ports and MinIO-internal URLs are not valid public outputs.
- signed proxy paths retain tenant scope and expiry checks.

## Workflow engine and Gateway reachability

Status: **PASS SOURCE / PASS RUNTIME CI**

Preserved upstream behavior:

- unreachable merge / all-skipped semantics;
- workflow caller/callee relations;
- `run_node` batch;
- expression fields;
- file step outputs.

Workflow relation endpoints are under the existing authenticated `/api/workflows/**` Gateway route and require user/organization context. They are not added to public routes.

Piston remains a Trinyx deployment adapter:

- Cloud default `PISTON_ENABLED=false`;
- Cloud forces `PISTON_EMBEDDED=false`;
- disabled means no network call and explicit code-node unavailability;
- enabled without URL fails startup/configuration;
- no localhost or public fallback;
- CE embedded-code behavior remains upstream-compatible.

## Tool schema

Status: **PASS SOURCE / PASS RUNTIME CI**

`ToolParameter.itemType` is preserved through agent-common, shared agent schema generation, conversation tool mapping and the network representation. Tests prove that `array<object>` remains an object array and does not collapse to `array<string>`; legacy arrays without item type retain the upstream string default.

## Frontend

Status: **PASS SOURCE / PASS RUNTIME CI**

Upstream v0.2.14 functionality remains present:

- detachable/floating side panel;
- Save/Run/Share actions;
- multi-canvas isolation;
- workflow relations;
- file picker/folders;
- run tab and expression controls.

Trinyx edition gates, Cloud OIDC, paid embedded auth, CloudLink, billing, Marketplace and credentials remain active. The detach test proves the same React subtree is moved rather than duplicated, and Run wiring tests protect against duplicate UI dispatch. Real provider billing from two simultaneously open browser canvases remains an E2E staging check.

## Multi-architecture policy

Status: **AUDITED LIMITATION / AMD64 ONLY**

LiveContext v0.2.14 upstream added amd64+arm64 images. Trinyx does not currently claim that parity:

- the five-image Trinyx CE release set remains `linux/amd64`;
- the attempted arm64 validation correctly failed because `websearch-service` installs Google Chrome from an `arch=amd64` repository and its amd64 dependencies are unavailable in an arm64 image;
- partial multi-arch publication is rejected: one tag must not advertise arm64 when the opt-in browser image cannot build for it;
- distributed Trinyx Cloud remains `linux/amd64` for the current AWS topology;
- native arm64 requires a separate Chromium/browser packaging change plus all five CE runtime gates.

No LiveContext engine behavior was changed to work around this packaging limitation.

## Caddy, TLS, Gateway and origins

Status: **PASS RUNTIME CI / REQUIRES STAGING**

Private Caddy remains exactly:

- listener `cloud-internal.trinyx.private:8443`;
- `admin off`;
- `auto_https disable_redirects`;
- `tls internal`;
- exact internal allowlist;
- default 404;
- no new public port.

CI performs a real TLS handshake, not only `caddy validate`.

Gateway HMAC v2, Ed25519 workload auth, replay stores, body-buffer semaphore and atomic Redis rate limit remain covered. No new v0.2.14 endpoint was made public by wildcard.

`CLOUD_PUBLIC_URL`, `KEYCLOAK_PUBLIC_URL` and `PAID_PUBLIC_URL` remain the only public-origin inputs. The staging render uses `.example.invalid` and rejects production-origin leakage. No staging secret or real hostname is committed.

## Immutable Cloud image inventory

Status: **PASS SOURCE / NO IMAGE PUBLISHED**

The owner-built Cloud release set remains exactly 14 images:

1. agent
2. auth
3. catalog
4. conversation
5. datasource
6. gateway
7. interface
8. keycloak
9. migration
10. orchestrator
11. publication
12. storage
13. trigger
14. websearch

The immutable runtime model requires full `package@sha256:digest` references, clears local build definitions and supports `pull` followed by `up --no-build`. No `latest`, mutable staging/prod alias or local owner image is accepted. PR mode builds without pushing; no image was published during this audit.

## Profile matrix

| Profile | Source | Runtime CI | Still requires staging |
| --- | --- | --- | --- |
| CE | PASS | amd64 compose/build/tests | arm64 browser packaging; real registry pull and upgrade |
| paid-monolith | PASS | boot/liveness/contracts | Stripe, private TLS, CloudLink, process kills |
| distributed Cloud | PASS | compose, TLS, security, PostgreSQL/Flyway contracts | full topology, Keycloak, Redis HA, providers, reconciliation |

## Final gates not simulated

- repository private visibility and governance/rulesets;
- protected `main`, independent review and stable tag protection;
- Actions artifact retention ≥365 days;
- GHCR and npm permissions/tokens;
- immutable image publication and captured digests;
- durable release manifest/GitHub Release;
- staging deploy by digest;
- representative real database backup upgrade;
- Keycloak login/refresh/organization switch;
- Stripe test-mode lifecycle;
- CloudLink/PKCE through real DNS/TLS;
- Redis AOF restart, restore and HA failover;
- process kills around DISPATCHING/UNKNOWN/ACK;
- MinIO/S3 deletion retry and absent-object behavior;
- provider cost/token reconciliation;
- browser cancellation and ambiguity;
- cross-tenant negative E2E;
- DNS, TLS, Caddy and security-group verification.

## Verdict

- **LiveContext parity:** PASS. Upstream v0.2.14 remains canonical and its true Git ancestry is preserved.
- **Source merge verdict:** GO only when the required checks on the commit containing this report are green; the PR check suite is the live authority.
- **Production verdict:** NO-GO until the external staging and governance gates above are evidenced.
- **Safety:** PR25 remains Draft; no merge, deploy, AWS mutation, package publication, tag, release or secret was performed.
