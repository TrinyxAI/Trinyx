# O6-O12 staging automation runbook

Status: implemented and repository/CI validated only. Nothing in this runbook is
evidence of a live AWS bootstrap, staging deployment, rollback, redeploy or PCA.

## Control flow

```text
Build -> Release -> Attestation -> S3 Registry -> SSM -> Install -> Preflight
      -> Activate -> Health -> Rollback
```

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
   workflow `578c7610373f96d4cd018253f591750e0cfb8ebf`. Keep `EnableS3GatewayEndpoint=false`.
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
   `e160e3e1c12995ad522a936c95061e03c174f8d8` through the approved reconciler path. Reconcile/materialize
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
  executable control-plane code `e160e3e1c12995ad522a936c95061e03c174f8d8`, and privileged reusable workflow
  `578c7610373f96d4cd018253f591750e0cfb8ebf`. The latter checks out the former by exact SHA and asserts HEAD before
  credentials. AWS IAM pins the privileged workflow plus exact caller branch ref.
- Deployment/adoption records use `controlPlaneCommit`; release `sourceCommit`
  and builder `platformCommit` retain their distinct meanings.
- Offline encrypted staging root/issuer is the O10 default. The optional
  two-CA AWS PCA hierarchy remains disabled and paid.


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

For every exact 20 Cloud / 8 Paid service it reports current and expected image
digests, Compose config hashes, mounts, container ID and `recreateRequired`
reasons. It fails the workflow when more than three service hashes differ, when the exact service inventory or report SHA cannot be authenticated, or when the configured/running image object lacks the immutable baseline RepoDigest.

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
SHA-256 of the emitted protocol. More than three Compose config-hash mismatches
returns `compatibility=stop`; no bulk recreation is inferred or authorized.
