# O6-O12 staging automation runbook

Status: implemented and repository/CI validated only. Nothing in this runbook is
evidence of a live AWS bootstrap, staging deployment, rollback, redeploy or PCA.

## Control flow

```text
Build -> Release -> Attestation -> S3 Registry -> SSM -> Install -> Preflight
      -> Activate -> Health -> Rollback
```

## Staging reboot contract

Docker state, including images, volumes and existing containers, persists under
`/srv/trinyx/docker`. The complete runtime generations under `/run/trinyx`
are ephemeral and must be rebuilt after every boot. Staging-only systemd
drop-ins therefore gate `docker.service` with `Requires=` and `After=` on
the role's runtime materializer. Cloud also requires its pre-Docker truststore
materializer:

```text
Cloud: local-fs -> pre-docker -> network/SSM runtime materializer -> Docker
Paid:  local-fs -----------> network/SSM runtime materializer -> Docker
```

The runtime materializers use the network, AWS CLI/SSM and persistent release
and configuration files; they do not use Docker. The graph is therefore
acyclic. A missing persistent input, unavailable SSM value, invalid secret,
failed ownership/mode check or incomplete generation prevents Docker from
starting, so restart-managed application containers cannot become boot-ready
against a partial runtime. Cloud pre-Docker failure has the same fail-closed
effect.

A reboot does not pull or rebuild images, run Compose, recreate the stack,
change the active release pointer or delete persistent Docker state. After the
gate succeeds, Docker may restart only the existing long-running containers
whose stored policy is `unless-stopped`. Migration and init containers retain
`restart: "no"` and remain exited unless an explicitly approved deployment
runs them. The active release selected before reboot remains the source read by
the materializer.

The reconciler installs these drop-ins only for `staging`; production unit
behavior is unchanged. Applying the files to an already running host performs
no Docker restart. The gate takes effect on the next explicit Docker start or
host reboot.

If SSM or the network is temporarily unavailable, the materializer retries
under its existing `Restart=on-failure` policy, while Docker remains failed and
the application stays stopped. Systemd does not automatically requeue a unit
whose start job already failed because a required unit failed. After the
dependency is healthy, the deterministic recovery is therefore:

```text
systemctl reset-failed docker.service
systemctl start docker.service
```

The second start transaction reruns and revalidates the required materializer
before Docker. It does not run Compose, recreate containers, change `active`,
or use a timing delay. Operators must not start containers directly while the
Docker gate is failed.

Application builds remain manual or application-input gated. Registration and
qualification are separate manual workflows bound to the `staging` GitHub
Environment. The publisher role cannot call SSM. The deploy role cannot write
S3. Instance profiles can read only immutable staging release objects.

The registry uses SSE-S3, not SSE-KMS: release bytes contain no secret, TLS and
application hashes protect transport/integrity, and KMS would add policy,
request-cost and outage boundaries without useful additional isolation. The
optional S3 Gateway endpoint must receive reviewed VPC and route-table IDs; the
template deliberately contains none. AWS documents S3 Gateway endpoints as
having no additional endpoint charge; normal S3 request/storage and any other
network charges still apply.

The Gateway endpoint policy uses the AWS-required `Principal: "*"` form and
restricts callers with `ArnEquals` on `aws:PrincipalArn` for exactly the Cloud
and Paid instance roles. Never replace this with role ARNs directly in
`Principal`; gateway endpoint policy validation rejects that form.

Paid Caddy configuration is release-owned. The override mounts
`./docker/paid-monolith-internal/Caddyfile`, resolved by Compose relative to the
first Compose file in the installed immutable bundle. No environment inventory,
captured override or steady-state host path may reference a mutable checkout.

## Required non-secret environment files

Before any qualification, independently render and install on each host:

- `deployment-plan.json` from the reviewed control-plane commit;
- `*-health-endpoints.json`, containing strict `https` URLs, expected status
  codes (including auth/redirect contracts), finite timeouts and a public CA
  file path;
- public staging trust bundles and Paid server certificate;
- the Paid private leaf key, generated and retained on Paid with mode `0600`;
- `rollback-safety.json` when Cloud one-shot migration compatibility has been
  proven by an expand/contract review. It binds previous/candidate release IDs
  and the SHA-256 of the evidence. Absence or mismatch fails before mutation.

Secret values remain in SSM/runtime root-only materializations. Health files,
release objects, deployment records and workflow artifacts contain no secret.
Paid uses the established liveness endpoint. Aggregate `/actuator/health` is
not required unless Stripe TEST is explicitly configured.

## Legacy baseline honesty

`baseline_observation.py` can record the exact 20/8 running service observation
without exposing environment values. Its output is deliberately marked
`releaseEligible=false`: container observation alone cannot reconstruct the
release ID, deterministic bundle or internal hashes. O12 qualification requires
a genuine existing release artifact containing the complete manifest, exact
bundle and image bindings. Do not invent a release ID and do not rebuild the
frozen candidate.

## SSM execution budget and stale-lock recovery

The fixed SSM document grants each dispatcher invocation 900 seconds. The
orchestrator uses a monotonic 960-second poll budget (900 plus 60 seconds for
status propagation) rather than a fixed poll count. The qualification job uses
GitHub's bounded 360-minute maximum. A command may never be compensated merely
because the old 180-second client loop expired.

The global Parameter Store lock contains only non-secret JSON metadata:
`owner`, `createdAt`, GitHub run ID/attempt and schema version. It has no
automatic TTL. If a runner dies, do not delete the parameter directly and do
not retry an apply. From an independently approved `staging` Environment
session using the deploy role:

1. read the lock and record its exact owner and GitHub run;
2. inspect that run and both staging hosts;
3. execute the proof-gated command below with the exact owner;
4. the command lists the pinned document's SSM commands since `createdAt`,
   refuses while an owner-associated command is active, rereads the unchanged
   lock, and only then deletes it.

```bash
python3 platform/automation/ssm_orchestrator.py break-lock \
  --document-version "$DOCUMENT_VERSION" \
  --registry-bucket "$RELEASE_REGISTRY_BUCKET" \
  --lock-owner 'dep-REPLACE_WITH_EXACT_32_HEX_OWNER' \
  --confirm-break-lock AWS_STAGING_STALE_LOCK_BREAK_APPROVED
```

This is manual recovery, not liveness automation. An unreadable/legacy lock,
AWS inventory error, active command, owner mismatch or concurrent lock change
remains fail-closed and requires direct AWS review.

## Canonical approved live sequence (not executed by this change)

This is the only normative bootstrap order. Do not use an earlier
observation-before-normalization sequence.

1. Protect branch `codex/platform-release-automation` and GitHub Environment
   `staging`; restrict deployment branches, require the available CI/review
   controls, prevent force-push/deletion and prevent self-review when supported.
2. Prepare and independently review AWS trust, registry and control-plane change
   sets in account `001634075617`, region `us-east-1`, pinned to privileged
   workflow `a2d225f2a1345636c2e362e2921e4c0bc2b7b8ae`. Keep `EnableS3GatewayEndpoint=false`.
3. Execute only the reviewed AWS trust migration through the existing
   administrative path.
4. Configure the repository OIDC subject with:
   `{"use_default":false,"use_immutable_subject":true,"include_claim_keys":["repo","context","ref","job_workflow_ref"]}`.
   Immediately GET
   `/repos/TrinyxAI/Trinyx/actions/oidc/customization/sub` and require the
   immutable flag, `use_default=false`, and the exact ordered claim list.
   Then run OIDC probe only and require account `001634075617` and the exact
   bootstrap role. Stop on any mismatch.
5. Execute the separately reviewed registry/control-plane bootstrap, capture the
   bucket/publisher role and exact numeric SSM document version, and verify that
   publisher cannot deploy, deploy cannot publish and EC2 profiles are read-only.
6. Install the reviewed control-plane files pinned to
   `bdbdc0068b08f818881fecc96d6cb0770b972ec4` through the approved reconciler path. Reconcile/materialize
   reviewed non-secret config, health and trust inputs on both hosts without
   activating a release or recreating a container.
7. Stop at `AWS_PCA_LIVE_APPROVAL_REQUIRED`. Use the offline staging PKI
   runbook by default; stage only validated CA/certificate/key material, with the
   Paid private key remaining root-only mode `0600`.
8. Register the frozen release through `staging-release-register`; historical
   signer compatibility remains limited to its exact frozen tuple. Prove and
   register a genuine canonical baseline without rebuilding the candidate.
9. Install the canonical baseline on Cloud and Paid without changing `active`.
10. Run `staging-legacy-normalization-plan` for Paid then Cloud under the global
    lock. The receiver must authenticate exactly 8 and 20 unique service lines,
    recompute the pre-marker SHA-256 and verify every context/count/summary.
    Independently review the bounded normalization protocol. Stop here.
11. In a separately approved operation, normalize only the proven services with
    scoped `docker compose ... up -d --no-deps --wait <service>`, preserve
    known-good TLS trust where possible, and run Paid liveness, Cloud→Paid strict
    TLS/hostname smoke and public smoke. Rerun the normalization plan until both
    roles report the full exact inventory, `images=matched`,
    `compatibility=review`, and `recreate_count=0`. Never bulk-recreate.
12. Only now capture schema-v3 baseline observations. Require exact container
    image object/RepoDigest bindings, Compose hashes, mounts and config digest;
    create reviewed adoption evidence and perform pointer-only adoption with
    post-adoption health and partial-failure compensation.
13. Install the frozen candidate and run candidate PLAN only. Review disk,
    deltas, health/TLS, restarts/OOM, migration rollback-safety, SSM version and
    active pointers. Stop before `apply`.
14. Only after a separate human approval run qualification: same candidate deploy
    → full health → baseline rollback → full health → same candidate redeploy →
    full health/idempotence. No production and no rebuild.

Never use `$DEFAULT` SSM document version, SSH, a GitHub token on EC2, static
AWS credentials, `curl -k`, global Compose apply, destructive DB
down-migrations, Docker prune, volume deletion, DB drop or Redis flush.

The current repository task performs none of these live operations. Paid
process health continues to use the established liveness contract; aggregate
`/actuator/health` is not required unless Stripe TEST is configured.

### Evidence hash preparation

On each host, after the canonical baseline has been installed and observation captured, compute only non-secret hashes:

```text
sha256sum /etc/trinyx/staging/<role>/releases/<releaseId>/images.env
sha256sum /etc/trinyx/staging/<role>/config/legacy-observation.json
```

Copy the resulting `sha256:<hex>` values into `legacy-adoption.json`. Also copy the observation's `environmentConfigDigest`, bind the manifest deployment-bundle digest, the approved environment-config revision and the exact 40-character platform commit. The adoption engine recomputes the configuration digest, validates the observation hash, re-inspects the same current container IDs/config-hashes/mounts, recomputes baseline Compose hashes, and requires each observed `configuredImage` and `RepoDigests` entry to contain the exact baseline `package@sha256`. Independent hashes or stale observations are rejected.

### Private CA stop

`AWS_PCA_LIVE_APPROVAL_REQUIRED`

No Private CA, certificate, CRL bucket, KMS key or live trust material may be created by these repository workflows. Current Private CA pricing must be rechecked from AWS immediately before the approved live operation.


## Third-review closures (repository-only)

- Legacy observations use schema v3 and bind each Compose service to the full
  container ID, immutable image object/digest, Compose config-hash and normalized
  effective mounts. Adoption re-inspects runtime and recomputes expected hashes;
  20/20 Cloud and 8/8 Paid are mandatory.
- The non-secret materialized configuration is content-hashed from
  `deployment-plan.json` plus every `requiredFiles` entry. Observation,
  approval evidence and current host bytes must all agree.
- Every direct workflow job with `id-token: write` delegates to a reusable
  `workflow_call` implementation. This includes both the release-candidate
  builder and the CE publisher, preventing the repository-wide custom OIDC
  template from encountering a direct job without `job_workflow_ref`.
- The immutable identity chain is explicit: builder workflow `114a2613e8090f034925a1bcf148f055653c3a06`,
  executable control-plane code `bdbdc0068b08f818881fecc96d6cb0770b972ec4`, and privileged reusable workflow
  `a2d225f2a1345636c2e362e2921e4c0bc2b7b8ae`. The latter checks out the former by exact SHA and asserts HEAD before
  credentials. AWS IAM pins the privileged workflow plus exact caller branch ref.
- Deployment/adoption records use `controlPlaneCommit`; release `sourceCommit`
  and builder `platformCommit` retain their distinct meanings.
- Offline encrypted staging root/issuer is the O10 default. The optional
  two-CA AWS PCA hierarchy remains disabled and paid.


## Canonical historical baseline import

`build-historical-staging-baseline.yml` reconstructs one genuine canonical
release for source commit `aeb2a447ea7ce0436a60549713636225dfe1a2c1`
without rebuilding or publishing application images. It authenticates the
exact historical backend/Cloud and frontend workflow runs, downloads Cloud
manifest artifact `9777989306` by ID, derives the original Paid manifest
digests from the exact authenticated historical publication job logs, and then
requires the present GHCR full-SHA tags plus OCI revision to match that
historical evidence. The registry tags are consistency checks, not the
historical source of truth. The authenticated job-log evidence is pinned to
backend publish job `99660712771` and frontend build job `99659777935`; it yields
backend `sha256:0485c570d125ca008740860af078f7b6a876048721c0a66d3229bcc85fb94f1e`
and frontend `sha256:92f6c194739d085e88ab460bd09fef821fa96d4caba59d57063494db6f14f04e`.
The builder combines those inputs with the reviewed static
third-party inventory. The trusted current packaging tools build the bundle
from explicitly authenticated source origins and `release.py` computes the
content-derived release ID. Nine contracted paths come byte-for-byte from the
exact aeb2 tree. The exact tree predates
`docker/docker-compose.paid.runtime.yml`, while the current Paid deployment
plan requires that fixed trusted service-and-image overlay to bind all eight
services to the canonical immutable image inventory. The `paid-edge` service
definition remains approved environment configuration; the overlay supplies its
immutable image binding and no other current release content. A closed source contract therefore admits
that one file only from the exact trusted builder checkout, independently
rechecks historical Git HEAD/clean status before and after byte-safe reads,
binds the trusted checkout to the exact `job.workflow_sha` with no tracked
drift, rejects root or descendant symlinks, historical shadowing, unapproved
overlays and incomplete bundle coverage, and never substitutes any other
current application/deployment content. The one environment-specific input used
only for the read-only Paid Compose render is separately allowlisted as
`platform/bootstrap/paid/staging/rootfs/etc/trinyx/staging/paid/config/paid.override.yml`;
it is required to be a regular non-symlinked file in that same trusted checkout,
then copied once to a fresh read-only snapshot. The logged SHA-256 identifies the
exact snapshot consumed by `docker compose config`; the mutable checkout path is
never passed to Compose after verification. The snapshot is not copied into the
baseline bundle and never claims historical aeb2 provenance. Modern bundle entries include
their normalized mode. The four canonical files receive
GitHub build provenance attestations. Do not register or install a baseline
built with this modern bundle schema while the active C3/W7 control plane is
still installed: C3's installer accepts only the old three-field bundle entry
schema. A later reviewed control-plane anchor must authorize this builder and
consume the mode-bearing schema before the separately reviewed W8 activation.
The bundle manifest deliberately keeps `schemaVersion: 1` for this compatibility
release: modern entries are nevertheless closed to exactly `path`, `digest`,
`sizeBytes` and `mode`, while only the exact frozen historical identity may
use the legacy mode-less entry shape. A numeric schema migration would alter
the release/registry identity contract and must be designed and reviewed as a
separate control-plane change; it is not inferred from the presence of `mode`.
Both historical runs must also report branch
`codex/trinyx-cloud-gateway-v2`, event `workflow_dispatch`, and attempt `1`
before the builder may emit `release.sourceRef` as
`refs/heads/codex/trinyx-cloud-gateway-v2`; the branch label is authenticated
metadata and never substitutes for the exact aeb2 source commit.

The exact historical Cloud artifact uses legacy logical names (`agent`,
`auth`, ..., `websearch`). Those names are not prefixed heuristically. A
fail-closed adapter validates every original `name`/`service`/`environment`/
`package` binding against the exact historical source inventory, maps the
binding one-to-one to the current canonical inventory, and preserves the
original digest and immutable reference. The downloaded ZIP is checked against
GitHub artifact digest
`sha256:8cb6a3b52b7deff90bebcceb6435a5c66d6d1a06e45c32b8350427efe4059ac0`
before extraction. Extraction is then performed by an exact-member
safe extractor rather than `unzip`: it permits only the contracted
`cloud-image-manifest.json` regular file in a fresh non-symlinked destination,
and rejects duplicate, traversal, absolute, backslash, encrypted, special-file,
ancestor-collision and unexpected-member forms before writing any bytes.

This is not baseline observation and it never imports identities from EC2.
Live runtime evidence cannot replace missing historical publication provenance.
The resulting baseline means historical aeb2 first-party source/images plus
the currently reviewed immutable third-party inventory; it is not represented
as a byte-for-byte snapshot of every legacy container.
The workflow has read-only package access, performs only manifest inspection,
and contains no image build, push, registration or AWS operation. Do not run it
until its repository change is independently reviewed.

This workflow is **not currently dispatchable**: GitHub requires a manual
`workflow_dispatch` entry point to exist on the default branch, while this file
exists only on the platform branch/PR. Do not claim or attempt a branch-only
manual dispatch. A later, separately reviewed tiny default-branch caller must
delegate to `build-historical-staging-baseline-impl.yml` at the exact immutable
final builder SHA and grant only `actions:read`, `contents:read`,
`packages:read`, `id-token:write`, and `attestations:write`.

The current release registrar also does not yet authorize this new builder.
After the builder SHA exists and its output is reviewed, a separate registrar
change must recognize only the exact aeb2 source, exact historical run and
artifact identities, exact baseline artifact name, exact default-branch caller
commit, and exact reusable builder path/SHA. All four internal attestations are
mandatory. Their signer workflow/digest must identify the immutable baseline
implementation; the attestation source digest must identify the exact caller
run commit. `release.sourceCommit` remains aeb2 and
`release.platformCommit` remains the reusable builder's `job.workflow_sha`.
This is a new modern policy path and must not reuse or widen the frozen f3a4
compatibility exception.

The operational order remains: prove and build the canonical baseline →
register it → install without changing `active` → Paid normalization PLAN →
Cloud normalization PLAN → STOP and review → separately approved bounded
normalization.


## Legacy runtime normalization gate

The legacy runtime is not eligible for pointer-only adoption while any effective
container mount still references a mutable checkout. Do not weaken this gate and
do not edit observation evidence.

After the immutable baseline is installed without activation and host
configuration is reconciled/materialized, dispatch
`staging-legacy-normalization-plan`. This action invokes SSM mode
`normalize-plan` under the global staging lock. It is read-only: it does not
run a materializer, write a plan file, recreate a container, change `active`, or
run health mutations.

For every exact 20 Cloud / 8 Paid service it reports the observed
`Config.Image`, the exact Docker image object and its immutable `RepoDigests`,
the expected digest, Compose config hashes, mounts, container ID and
`recreateRequired` reasons. A legacy tag is observation metadata only: it is
never resolved or trusted. Content matches only when the expected canonical
`repository@sha256` occurs in the exact object's `RepoDigests`; a matching
object behind a tag is reported separately as a non-canonical configured
reference requiring bounded recreation. Missing object evidence fails closed.
Compose hash drift is not qualified by a count. The planner hashes the canonical
rendered model and a second structured model that reintroduces only the observed
legacy image reference and approved `/srv/trinyx/pr25-*` bind sources while
leaving command, environment, ports, networks, restart policy, healthcheck,
mount targets/options and every other field canonical. A current label must
match one of those exact hashes. Any single unexplained effective change returns
`compatibility=stop`. This keeps Compose hashes as authenticated supplementary
evidence without treating a fixed mismatch threshold as a trust boundary.

A legacy bind path is not content identity. Before such a source can enter the
structured explained model, the planner compares the bytes actually reachable
through it with the corresponding path in the installed immutable release
bundle. Files are bound by type, mode, size and SHA-256. Directories use a
bounded canonical tree identity over sorted relative paths, entry types, modes,
sizes and file SHA-256 values. Symlinks, traversal outside either approved root,
special files, duplicate targets, missing/extra entries and any byte difference
fail closed as `LEGACY_BIND_CONTENT_MISMATCH`; a matching Git HEAD alone is not
evidence because the checkout may be dirty. Only aggregate digests—not raw bind
contents—are emitted in the bounded SSM protocol.

Before approving normalization, require every one of the 12 third-party service
records to prove `image_match=yes`: the exact running Docker object's
`RepoDigests` must contain the corresponding immutable reference from
`platform/release/third-party-images.json`. This read-only preflight does not
turn observed tags into baseline identity.

Review the authenticated bounded normalization protocol independently; verify its final recomputed `report_sha256` and exact 8/20 service inventory. The expected initial legacy delta is at
least Paid `paid-edge` because its effective container may still mount the
historical mutable Caddyfile. If unexpected services require recreation, stop.

A later, separately approved normalization mutation must be delta-only:

```text
preserve and validate the known-good TLS material at canonical paths
→ docker compose ... up -d --no-deps --wait paid-edge
→ Paid liveness
→ Cloud→Paid strict hostname/CA TLS smoke
→ public edge smoke
→ rerun normalization plan
```

Never recreate all Paid/Cloud services globally. Do not combine Caddy source,
filesystem paths and CA/leaf rotation unless the old certificate cannot be
validated. If old trust cannot be preserved, stop and review an explicit
old+new trust transition before any recreate.


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
SHA-256 of the emitted protocol. Explained canonicalization drift is counted
separately from unexplained effective configuration drift; one unexplained
change returns `compatibility=stop`. No bulk recreation is inferred or authorized.
PLAN performs no pull, build, push, start or recreate. A proven content
mismatch remains visible for separate human review; APPLY is never implied and
must remain release/bundle/config/service bounded under the global lock.
