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


## Corrective readiness closure (repository-only)

The final pre-live review found and closed these additional gaps:

| Classification | Risk / failure mode | Implemented control | Operational cost |
| --- | --- | --- | --- |
| SECURITY_CRITICAL | A buggy or compromised publisher could omit `If-None-Match` and replace the current S3 object version | Bucket policy denies every release-prefix `PutObject` without `s3:if-none-match`; the client still sends `If-None-Match: *` | No steady-state charge; copy operations to the immutable prefix are intentionally unavailable |
| RELIABILITY_CRITICAL | Rollback preflight could mask the original exception with an uninitialized `mutated` local | Initialize mutation state before rollback preflight and fixture-test failure before mutation | None |
| RELIABILITY_CRITICAL | The legacy `active -> deployments/stg-bootstrap-001` pointer prevented even a candidate plan and made the first safe transition undefined | Plan reports a validated legacy status; a separate pointer-only adoption saga requires a canonical installed release, full observation, exact evidence hashes, pre/post health and atomic compensation | One explicit reviewed adoption operation |
| SECURITY_CRITICAL | Publisher and deploy roles shared a generic Environment OIDC principal | Custom GitHub OIDC subjects bind immutable owner/repository IDs, `staging`, and the exact called workflow; register cannot assume deploy | One GitHub subject-template change during approved bootstrap |
| RELIABILITY_CRITICAL | SSM `BundleDigest` was informational after installation and rollback compensation reused the candidate digest for a previous release | Every non-install host mode verifies the installed manifest against the requested digest; the saga carries candidate and previous digests separately | Extra local hash/manifest validation only |
| RELIABILITY_CRITICAL | Runner/AWS clock skew could hide an active command during stale-lock recovery | Query from `createdAt - 5 minutes`, then filter the exact deployment owner and active statuses | Negligible extra ListCommands results |
| RELIABILITY_CRITICAL | Required health inventory and TLS paths were not materialized by bootstrap | Commit non-secret strict-TLS endpoint inventories; reconcile them; stage CA/certificate/key only through a bounded atomic root tool that validates chain, hostname and key match without logging material | Human certificate issuance/staging remains required |
| PERFORMANCE_COST | New live workflows do not receive `workflow_dispatch` until present on the default branch | The existing default-branch backend workflow is a narrow pre-merge dispatcher to reusable live workflows; every operation is branch- and Environment-gated and application builds run only for `release-candidate` | Temporary bridge removed after normal workflow integration |
| DEFENSE_IN_DEPTH | Registration verified a publisher attestation created immediately before consumption | Also verify the four original builder subjects with expected signer workflow/source and deny self-hosted provenance | Four GitHub attestation lookups per release |

### Honest legacy adoption contract

The observation remains `releaseEligible=false`; it is never renamed or promoted into a release. Pointer adoption is allowed only when all of the following are available on each host:

1. an independently built, canonical and installed baseline `rel-v1-*`;
2. `legacy-observation.json` produced from the complete live service inventory;
3. a reviewed `legacy-adoption.json` matching role, exact legacy target, release ID, bundle digest, `images.env` hash, observation hash, environment-config revision and platform commit;
4. full preflight and health proving the existing containers use the baseline release's exact digest model;
5. post-pointer health after an atomic pointer-only update.

The adoption path never calls Compose `up`, never runs a one-shot, and compensates the pointer to the legacy target on failure. If any proof is absent, the durable record is `FAILED` and the surfaced marker is `LEGACY_BASELINE_PROOF_REQUIRED`. A one-way cutover without a cryptographically honest baseline is deliberately not automated because it cannot satisfy O12 rollback safety.

### OIDC principal boundary

Before STS is used, configure the repository OIDC subject template with these ordered claim keys:

`repo, context, ref, job_workflow_ref`

Because this repository was created after GitHub's 2026 immutable-subject rollout, the IaC trusts `repo:TrinyxAI@319253481/Trinyx@1342032975`, Environment `staging`, the exact caller ref, and exact reusable workflow paths at the reviewed `PlatformWorkflowRef`. The bootstrap probe, release registration, baseline adoption and qualification identities are distinct. Changing the workflow ref after merge is a reviewed CloudFormation/GitHub trust migration, never a compatibility wildcard.

### Pre-merge manual entry point

GitHub only dispatches a workflow file registered on the default branch. Until the dedicated workflows are integrated, manually dispatch `build-trinyx-backend.yml` at ref `codex/platform-release-automation` and select one explicit operation. The bridge has no generic command input and calls only pinned repository reusable workflows. Selecting a platform operation does not run backend/frontend tests, Docker build, image publication or any push-triggered staging contact.


## Third-review findings and closures

| Severity | Finding | Control | Residual operation |
| --- | --- | --- | --- |
| RELIABILITY_CRITICAL / SUPPLY_CHAIN | Observation and baseline `images.env` were individually hashed but not related; a false pointer adoption could corrupt future delta calculation | Observation schema v2 records full container/image identities and Compose labels; adoption requires exact manifest service digest in both configured image and RepoDigests for 20 Cloud/8 Paid | Human must capture evidence from the unchanged legacy runtime |
| SECURITY_CRITICAL | Repository-wide custom OIDC `sub` requiring `job_workflow_ref` was incompatible with direct release/CE jobs requesting ID tokens | Direct workflows are credential-free callers; all ID-token work runs in call-only implementations; static policy scans every workflow/job | GitHub subject-template mutation remains human-gated |
| DEFENSE_IN_DEPTH | Exact workflow path was combined with a mutable branch ref | AWS trust parameter accepts a reviewed 40-character commit SHA and callers support SHA-pinned reusable workflows | Each reviewed control-plane revision requires a change-set update |
| PERFORMANCE_COST | Two AWS general-purpose CAs cost about USD 800/month before leaves | Offline encrypted staging root/issuer is default and integration-tested; AWS PCA remains an explicit optional paid mode | Offline custody, rotation, backup and revocation drills are human operations |

The USD 800 estimate is the operation fee for two managed CA resources, not for
two TLS leaf certificates. The default offline path creates no AWS PCA resource
and incurs no AWS managed-CA monthly fee.


## Fourth independent review closures

- **SECURITY_CRITICAL — privileged code identity:** the reusable workflow YAML and
  its executable checkout are now separate immutable identities. AWS-privileged
  workflows at `b5000a7167f12a555ca351c78af1079cabe4486c` checkout `5a68200d476295f086f871a38c8b5fea7ad8ec0a`, assert `git rev-parse HEAD` before
  credentials, and write that code SHA as `controlPlaneCommit`. IAM also requires
  the exact caller branch ref. A mutable caller checkout cannot supply privileged
  Python.
- **RELIABILITY_CRITICAL — effective legacy configuration:** schema v3 captures
  `com.docker.compose.config-hash` and normalized mounts for all 28 containers.
  Adoption recomputes `docker compose config --hash`, re-inspects current
  container IDs/hashes/mounts and rejects any mutable PR checkout mount before
  pointer mutation.
- **SUPPLY_CHAIN — signer transition:** the legacy builder signer is permitted
  only for frozen run 33485509832 / artifact 9791964215 and its exact source,
  release, bundle and ZIP digests. Future candidates require reusable builder
  `build-release-candidate-impl.yml@114a2613e8090f034925a1bcf148f055653c3a06` with `--signer-digest`.
- **OIDC migration availability:** AWS trust is reviewed/executed before GitHub
  custom-sub activation; the OIDC probe follows immediately and all mismatch
  paths stop fail-closed.


## Fifth independent review closures

- **RELIABILITY_CRITICAL — canonical OIDC template:** all three CloudFormation
  outputs now require exactly
  `repo,context,ref,job_workflow_ref`; static and
  fixture policies reject any drift.
- **DEFENSE_IN_DEPTH — exact STS account:** the bootstrap probe accepts only IAM
  account `001634075617`, verifies the returned `Account`, and matches the
  exact STS assumed-role ARN.
- **LIVE_BOOTSTRAP_BLOCKER — legacy normalization:** a new read-only
  `normalize-plan` SSM path reports image/config-hash/mount deltas without
  materialization or mutation. Mutable checkout mounts are reported as required
  recreations, not permitted as baseline evidence. All-image mismatch and
  all-service config-hash mismatch remain fail-closed compatibility gates.
- **OPERATIONAL STOP:** normalization output is CI-implemented only. No container
  recreation, TLS transition, baseline adoption, AWS change set or staging plan
  has been live validated.


### GitHub 2026 immutable OIDC subject and bounded normalization evidence

Trinyx uses the repository-level custom OIDC template
`repo,context,ref,job_workflow_ref`. The immutable repository segment is
`repo:TrinyxAI@319253481/Trinyx@1342032975`; owner/repository IDs must not be
repeated as legacy top-level claim keys. AWS trust must be migrated first, then
GitHub configured with `{"use_default":false,"use_immutable_subject":true,"include_claim_keys":["repo","context","ref","job_workflow_ref"]}`, GET-verified, then the exact-account OIDC probe run immediately.

The read-only legacy normalization command emits a marker-last line protocol,
never the full JSON report. Output is hard-bounded below 20,000 bytes to remain
under the SSM `StandardOutputContent` limit. Every report is bound to the
baseline release and bundle digest, deployment ID, environment config revision
and computed config digest, audited control-plane commit, Compose version and a
SHA-256 of the emitted protocol. More than three Compose config-hash mismatches
returns `compatibility=stop`; no bulk recreation is inferred or authorized.


### Normalization receiver integrity closure

The host proves both the configured digest and the running Docker image object:
the expected immutable reference must appear in that object's `RepoDigests`.
The orchestrator accepts a normalization report only when it contains one exact
header, exactly 8 unique Paid or 20 unique Cloud service records with the
canonical names, and one final marker. It recomputes SHA-256 over every
pre-marker byte and verifies service, image, hash, recreate and compatibility
summaries. Missing, duplicate, unknown, truncated, reordered-after-marker or
digest-mismatched output fails before human review or mutation.

The only approved live order is reconcile/materialize → install baseline
inactive → authenticated Paid/Cloud normalization plan → separately approved
minimal normalization → zero-recreate normalization proof → schema-v3 baseline
observation → pointer-only adoption → candidate plan. Observation before
normalization is intentionally not a valid bootstrap path.
