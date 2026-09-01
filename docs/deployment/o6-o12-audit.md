# O6-O12 staging platform audit

Audit baseline: `codex/platform-release-automation` at
`bff2cda416814188f5f3c5fef3bbc675e9dbf7d5`. This document records the audit
performed before implementation. It is not evidence of a live deployment.

## Threat model and trust boundaries

Protected assets are the immutable release identity, its 28 digest bindings,
deployment bundle, staging configuration and secrets, database compatibility,
the two active pointers, and the production-empty invariant. Relevant actors
are an untrusted pull-request author, a compromised GitHub job/action, a release
publisher, a deploy operator, either EC2 role, and a process able to tamper with
local disk or S3 objects. The principal threats are provenance substitution,
OIDC subject confusion, over-privileged credentials, SSM parameter injection,
mutable artifact/image replacement, concurrent or partial activation, secret
disclosure, unsafe migration rollback, and a health check that reports success
for a broken or old runtime.

The intended boundaries are:

```text
Build -> Release -> Attestation -> S3 Registry -> SSM -> Install -> Preflight
      -> Activate -> Health -> Rollback
```

1. **Build to release:** application builders may publish GHCR images and the
   candidate artifact, but may neither write the staging registry nor deploy.
2. **Release to attestation:** GitHub binds the artifact digest to repository,
   workflow, source ref and commit. Registration verifies this identity before
   accepting bytes.
3. **GitHub to S3:** a manual `staging` Environment job obtains a short-lived
   OIDC session for a publisher-only role. Conditional S3 puts make registration
   immutable and idempotent.
4. **S3 to EC2:** Cloud and Paid instance profiles can list/read only the staging
   release prefix. Application-level SHA-256 validation remains authoritative.
5. **GitHub to SSM:** a different Environment-bound role may invoke only the
   fixed document on the two staging instances and read its command result.
6. **SSM to host:** typed inputs select a fixed dispatcher; no input becomes a
   command. A numeric document version is pinned by the workflow.
7. **Install to activation:** installation is immutable and cannot change
   `active`; preflight completes before a saga may atomically publish a pointer.
8. **Activation to health/rollback:** only changed services are recreated.
   Success requires bounded invariant/health/smoke checks. The recorded previous
   immutable target is the only automatic rollback target.

## Current data flow

The manual release workflow builds 20 Cloud and 8 Paid bindings, creates a
deterministic 13-file bundle, calculates `releaseId` from release content, and
uploads a GitHub artifact. The host installer validates release identity, bundle
SHA-256, per-file hashes, safe tar members and idempotent immutable installation;
it explicitly checks that installation did not change `active`. Runtime
materializers read the active release and fetch secrets through instance-role
SSM access. The fixed dispatcher validates `Mode`, `Role` and `ReleaseId`, but
currently refuses every `apply`.

## Findings and controls

| Severity | Area | Risk and failure mode | Required control and justification | Cost / operations |
| --- | --- | --- | --- | --- |
| SECURITY_CRITICAL | CI/live boundary | `platform-contracts.yml` contacts STS and both staging hosts through SSM on every branch push. A compromised branch job gets a live control-plane path and routine pushes create unnecessary live dependency. | Remove all live jobs from push/PR. Keep fast static contracts there; allow registry/deploy/qualification only from `workflow_dispatch` jobs bound to `staging`. | Reduces latency and AWS calls; manual operation required. |
| SECURITY_CRITICAL | OIDC | Existing deploy and bootstrap roles trust a branch subject. Branch control alone is weaker than an Environment gate. The platform branch is currently unprotected and the repository has no ruleset. | Trust exact immutable owner/repository IDs plus `environment:staging`; guard repository/ref IDs in jobs; configure the Environment to allow only the platform branch and prevent self-review. | One human GitHub settings operation; independent reviewer identity is not encoded in Git. |
| SECURITY_CRITICAL | Role separation | No dedicated release publisher or private registry exists. Adding publish to the deploy role would combine artifact substitution and runtime mutation. | Separate build, publisher, deploy, Cloud and Paid roles. Publisher gets conditional S3 write only; deploy gets SSM only; EC2 gets prefix read only. | One IAM role, two narrow inline policies. |
| SECURITY_CRITICAL | Supply chain | The frozen artifact has a GitHub ZIP digest but registration has no mandatory attestation verification. A valid-looking replacement could be registered. | Build attestations for future candidates; verify the frozen candidate against repository/workflow/source and artifact digest before registration; record provenance separately from release identity. | Uses native GitHub attestation service; no application rebuild for registration. |
| SECURITY_CRITICAL | Action integrity | `actions/checkout@v4` is floating in the main platform workflow; several pinned actions use Node 20 generations. A tag can move and deprecated runtimes increase maintenance and dependency risk. | Full-SHA pin every external action and use official Node-24-compatible majors. Add a static pin/runtime policy test. | Periodic reviewed pin updates. |
| SECURITY_CRITICAL | SSM document | Inputs are safely enumerated/regex constrained, but the workflow does not pin a document version. A changed default version could execute code not reviewed with the workflow. | Require a numeric `document_version` input and pass `--document-version`; compare the expected document hash/version during the approved bootstrap. Keep the dispatcher path fixed. | Operator must copy the reviewed version output. |
| SECURITY_CRITICAL | Secrets/logging | Materializers correctly delete raw SSM JSON and use root-only files, but command output and deployment metadata have no shared secret-name/value guard. Failure diagnostics could accidentally serialize config. | Schema allowlist plus redaction guard; records store only config revision and presence results. Never print secret values or rendered secret env. | Small diagnostic discipline; slightly less verbose errors. |
| RELIABILITY_CRITICAL | Apply engine | `apply` is intentionally unimplemented. There is no transaction, cross-host compensation, deployment record or bounded rollback. | Explicit state machine with durable states, previous pointers, failure/rollback fields, bounded steps and manual-recovery state when migration rollback is unsafe. | Additional state records and operator runbook. |
| RELIABILITY_CRITICAL | Concurrency | Workflow concurrency is not a host lock. Two dispatches or a direct SSM call can race on active pointers. | GitHub Environment concurrency plus non-blocking host `flock`; record owner/deployment ID. | Stale process releases kernel lock automatically. |
| RELIABILITY_CRITICAL | Activation atomicity | Materialized runtime links are atomic, but there is no implemented atomic release activation. Partial Cloud/Paid activation is unhandled. | Create a temporary relative symlink, fsync its directory, then `rename`; verify resolved target is an installed immutable release. Paid-first ordering allows a Paid failure to leave Cloud untouched; a later Cloud failure compensates Cloud then Paid. | Brief mixed-version window remains and is covered by backward-compatibility preflight. |
| RELIABILITY_CRITICAL | Delta-only runtime | A naive Compose apply would restart all services and risks database/Redis disruption. | Compare old/new service image/config fingerprints and call Compose only with the changed service allowlist. Explicitly forbid unscoped `compose up -d`. | Faster deploys; engine must maintain the service inventory. |
| RELIABILITY_CRITICAL | Migrations | Release metadata does not yet prove backward-compatible expand/contract migrations. Automatic rollback after an incompatible migration could corrupt state. | Require a rollback-safety declaration and explicit one-shot migration plan. Refuse activation/automatic rollback when compatibility is not proven; never run down migrations. | Some releases require manual recovery planning. |
| RELIABILITY_CRITICAL | Preflight | Current plan verifies only installed files and active link. It does not prove binaries, disk, config/TLS, GHCR pullability, rendered Compose, topology invariants or both-host installation before mutation. | One reusable invariant/preflight suite, finite command timeouts, digest pre-pull, disk threshold, config/secret presence checks and Compose rendering before pointer mutation. | Pre-pull consumes bandwidth/disk but reduces mutation risk. |
| RELIABILITY_CRITICAL | Health/smoke | No integrated post-activation health/smoke or restart/OOM check exists. `SUCCESS` could mean only that a command returned. | Bounded container, liveness, TLS hostname, auth/redirect, edge and cross-stack checks plus digest/pointer verification. Treat Paid liveness as authoritative when Stripe TEST is absent; do not require aggregate `/actuator/health`. | Adds minutes only to manual live deploy, not push CI. |
| RELIABILITY_CRITICAL | Registry partial writes | A multi-object upload can fail halfway or race. A release ID could appear registered before all bytes are durable. | Upload content-addressed objects with `If-None-Match: *`, verify checksums, and publish `registration.json` last as the commit marker. Matching objects make retries idempotent; mismatches fail closed. | A failed attempt can leave harmless uncommitted objects. |
| RELIABILITY_CRITICAL | S3/network/disk | Hosts currently rely on external package/GHCR/network paths and do not have a registry download path or explicit free-space gate. NAT failure or disk exhaustion could fail mid-deploy. | Parameterized S3 Gateway endpoint, finite AWS CLI timeouts, checksum verification and space calculation before download/pre-pull. Do not guess VPC/route-table IDs. | Gateway endpoint has no hourly/data-processing endpoint charge; route policy must be reviewed. |
| RELIABILITY_CRITICAL | Baseline honesty | A running checkout-derived baseline cannot be promoted into a cryptographic release merely by naming it. False baseline metadata would make O12 meaningless. | Import only if the complete manifest, bundle, internal hashes and exact digests are recoverable. Otherwise record the active observation as non-releasable and stop qualification until a genuine immutable baseline exists. | May require a one-time baseline capture, never an image rebuild if bytes are complete. |
| PERFORMANCE_COST | CI routing | Platform push currently runs live probes and the platform workflow can dispatch a full candidate build manually. Broad app workflows may still build on unrelated changes. | A single path-filtered fast platform job runs syntax, compile, unit/fixture/contract/failure tests, Compose render and IaC policy. Image builds remain manual or app-input gated. | Target is a few minutes; no assurance removed. |
| PERFORMANCE_COST | Runtime churn | Global Compose recreation would increase outage, CPU, pull time and rollback surface. | Fingerprint and mutate changed services only; pre-pull in parallel where bounded. | More precise diagnostics and lower runtime cost. |
| DEFENSE_IN_DEPTH | S3 encryption | KMS would add key policy, decrypt grants, request charges and a new outage boundary although artifacts contain no secrets. | Use SSE-S3 with TLS-only access, versioning, ownership enforcement and public-access block. Keep application hashes. | Lower cost and fewer permissions than KMS. |
| DEFENSE_IN_DEPTH | S3 checksum | Transport/storage corruption is already caught by release hashes. | Also send/check S3 checksum headers. | Negligible. |
| DEFENSE_IN_DEPTH | Private network path | S3 access through NAT/internet is unnecessary if route tables are known. | Optional, explicitly parameterized S3 Gateway endpoint; no invented IDs. GHCR still needs outbound HTTPS. | Route-table policy review required. |
| DEFENSE_IN_DEPTH | Staging PKI | Shared production/staging trust or TLS bypass would expand compromise. No PCA IaC/runbook exists. | Separate staging hierarchy, issuer/admin roles, keys, namespaces, trust, strict hostname validation, audit, revocation, rotation and recovery. No `curl -k`. | PCA is a paid live resource and requires a separate approval stop. |

## Existing controls retained

The installer already provides strong canonical JSON release-ID validation,
digest-only bindings, bundle SHA-256 and internal file verification, safe tar
extraction, immutable permissions, fsync/rename publication, idempotence, and a
hard assertion that install never changes `active`. SSM already prevents
arbitrary command input by enumerating mode/role and regex-validating release
IDs. Materializers use instance-role SSM reads, root-only temporary files,
finite AWS CLI timeouts and atomic runtime links. Paid rendering already carries
the 3 GiB limit and liveness probe. These controls must not be weakened.

## Topology and safe order

Cloud calls Paid over hostname-validated internal TLS; Paid liveness can operate
without Cloud. Therefore activation is **Paid then Cloud**. A Paid failure is
compensated locally and Cloud stays unchanged. If Cloud fails after Paid,
Cloud is restored first (removing the failed Cloud edge), then Paid is restored,
and the full old-state invariant/smoke suite runs. Database migration safety is
an earlier fail-closed gate; unsafe migration releases enter manual recovery and
are never described as automatically rolled back.

## GitHub Environment capability

The repository is public, so GitHub documents Environment deployment branch
policies and required reviewers as available; however the connector cannot read
the repository Environments endpoint and no reviewer identities are present in
source. The workflow and OIDC trust can be made Environment-compatible in Git,
but a human must create/configure `staging`, restrict it to
`codex/platform-release-automation`, select an independent reviewer if one is
available, enable prevention of self-review, and record the approved document
version before live use. The branch itself is currently unprotected and the
repository ruleset list is empty.

## O10 scope

O10 in this pass is repository-side design/IaC/runbooks only. It must stop with
`AWS_PCA_LIVE_APPROVAL_REQUIRED`; no PCA, certificate, key, trust store, DNS,
load balancer or production resource is created or modified.

## Post-implementation corrective review

A repository-only review after commit `a449ac80903ace4bb60ddb180ddf6cac9daa34f7`
found four pre-live inconsistencies. They are treated as controls to validate in
CI, not evidence of live readiness:

- **LIVE_BLOCKER / SECURITY+RELIABILITY — mutable checkout:** Paid Caddy now
  mounts the Caddyfile contained in each immutable release bundle. The staging
  inventory no longer accepts a checkout path, and static policy scans all
  operational platform text formats rather than shell files alone.
- **LIVE_BLOCKER / SECURITY+RELIABILITY — gateway endpoint semantics:** the
  optional S3 Gateway endpoint now uses `Principal: "*"` with exact
  `aws:PrincipalArn` conditions for Cloud/Paid. A semantic contract test
  prevents regression.
- **RELIABILITY_CRITICAL — SSM polling race:** the client now uses a monotonic
  960-second deadline for a 900-second document execution budget. Failure
  injection proves a command completing after 182 seconds is still accepted.
- **RELIABILITY_CRITICAL — stale global lock:** lock metadata is structured and
  recovery is an explicit approval-gated operation. It refuses deletion unless
  no owner-associated SSM command is active and the lock is unchanged.

The deploy role adds only `ssm:ListCommands` on `Resource: "*"`, because that
read/list API has no resource-level ARN boundary. Parameter mutation remains
limited to the single staging lock ARN. Direct verification of the original
builder attestations remains a non-blocking provenance hardening backlog item.
