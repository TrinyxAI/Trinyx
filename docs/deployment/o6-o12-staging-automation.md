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

## Approved live sequence (not executed by this change)

1. Protect branch `codex/platform-release-automation` and GitHub Environment
   `staging`; restrict deployment branches, add an independent reviewer if the
   plan supports it, prevent self-review, and never expose environment secrets to PR.
2. Prepare and review CloudFormation change sets for the *new* exact OIDC subjects,
   registry and deploy-control-plane stacks in account `001634075617`, region
   `us-east-1`. The subjects include immutable repository IDs, Environment,
   caller `ref:refs/heads/codex/platform-release-automation`, and privileged
   reusable-workflow commit `00fb3aef892d84754c8fb8d953171cb46fb05959`.
3. Execute the reviewed AWS trust migration using the existing administrative
   path. Only then configure the GitHub repository custom subject template as
   `repo,context,ref,job_workflow_ref`.
4. Immediately run the OIDC probe only and verify the exact STS identity. Stop on
   any mismatch; remove any reviewed temporary compatibility before continuing.
5. Capture the generated bucket, publisher role ARN and numeric SSM document
   version. Optionally enable the S3 Gateway endpoint only after supplying
   independently verified VPC/route-table IDs.
6. Install the exact reviewed control-plane files using the existing approved
   bootstrap/reconciler path. Verify their SHA-256 and host modes. This is the
   only checkout-dependent bootstrap; steady-state deployment is checkout-free.
7. Stop for `AWS_PCA_LIVE_APPROVAL_REQUIRED`; follow the separate O10 runbook.
8. Prove a genuine baseline artifact. Review migration expand/contract evidence
   and install the non-secret config/trust/health files.
9. Manually run `Register Staging Release` for baseline and candidate. Confirm
   artifact/run/source/release/bundle identities and native GitHub attestation.
10. Manually run the single qualification workflow. It installs both releases,
   deploys candidate Paid then Cloud, smokes, rolls back Cloud then Paid, smokes,
   redeploys the same candidate, smokes, proves idempotence and leaves that same
   candidate active.

Never use `$DEFAULT` SSM document version, SSH, a GitHub token on EC2, static AWS
credentials, `curl -k`, global Compose apply, destructive DB down-migrations,
Docker prune, volume deletion, DB drop or Redis flush.


## Required pre-live sequence added by the final review

Do not execute these steps as part of repository implementation. They are the later human-gated sequence.

1. Review the branch CI and exact identity chain: builder workflow `114a2613e8090f034925a1bcf148f055653c3a06`, audited executable control-plane code `d9080a2068cae049ac1860c27007d09f79241c18`, and AWS-privileged reusable workflow `00fb3aef892d84754c8fb8d953171cb46fb05959`.
2. Protect branch `codex/platform-release-automation` and GitHub Environment `staging` for only that branch.
3. Prepare/review the AWS trust change sets for the new subject containing `repo,context,ref,job_workflow_ref`; keep `EnableS3GatewayEndpoint=false` until real route-table IDs exist.
4. Execute the reviewed AWS trust migration through the existing administrative path.
5. Configure the GitHub custom subject template, immediately run OIDC probe only, verify exact STS identity, and stop on any error. Remove any temporary compatibility.
6. Verify that registration subjects cannot assume deploy and the probe cannot assume publisher/deploy. No PCA resource is included.
7. Register the frozen release through `staging-release-register`. Its historical signer is accepted only for the exact run/artifact/source/release/bundle/ZIP tuple. Every future release requires signer `build-release-candidate-impl.yml` with signer digest `114a2613e8090f034925a1bcf148f055653c3a06`.
8. Reconcile both hosts in plan mode. This installs health inventories and TLS/runtime verification tools, but never certificate private keys from Git.
9. Establish the default offline staging PKI from the O10 runbook (AWS PCA remains optional behind `AWS_PCA_LIVE_APPROVAL_REQUIRED`), then stage trust/certificate material:
   - Cloud: `stage-staging-tls --role cloud --ca <approved-staging-ca.pem>`
   - Paid: `stage-staging-tls --role paid --ca <approved-staging-ca.pem> --certificate <billing-internal.crt> --private-key <billing-internal.key>`
   The tool verifies chain, `billing-internal.trinyx.private`, certificate/key match, atomicity and mode `0600` for the private key.
10. Install the canonical baseline without activation, then run `/usr/local/lib/trinyx/baseline-observation --baseline-release <releaseId>` on each host with the approved environment-config revision. Schema v3 records full container/image IDs, exact `RepoDigests`, Compose project/service/config-hash and normalized effective mounts for exactly 20 Cloud and 8 Paid services. Expected hashes are recomputed with `docker compose config --hash` from the immutable baseline plus materialized environment. Any hash drift or effective mount under `/srv/trinyx/pr25-*` fails before pointer mutation.
11. Use `staging-legacy-adopt` only after that review. It installs without activation, health-checks the existing runtime, atomically adopts the pointer only, health-checks again and compensates on partial failure.
12. Run install/plan. Stop again before candidate `apply`.
13. Only after independent review run the single qualification workflow: candidate deploy → health → baseline rollback → health → same-candidate redeploy → health/idempotence.

The current task performs none of these live operations. Paid process health continues to come from the Compose `/actuator/health/liveness` container healthcheck; the public smoke inventory does not query aggregate `/actuator/health`, so absent Stripe TEST configuration cannot create a false process failure.

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
  executable control-plane code `d9080a2068cae049ac1860c27007d09f79241c18`, and privileged reusable workflow
  `00fb3aef892d84754c8fb8d953171cb46fb05959`. The latter checks out the former by exact SHA and asserts HEAD before
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
reasons. It fails the workflow if every service hash differs (Compose semantics
unqualified) or any runtime image differs from the immutable baseline.

Review the JSON output independently. The expected initial legacy delta is at
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
GitHub configured with `{"use_default":false,"include_claim_keys":["repo","context","ref","job_workflow_ref"]}`,
then the exact-account OIDC probe run immediately.

The read-only legacy normalization command emits a marker-last line protocol,
never the full JSON report. Output is hard-bounded below 20,000 bytes to remain
under the SSM `StandardOutputContent` limit. Every report is bound to the
baseline release and bundle digest, deployment ID, environment config revision
and computed config digest, audited control-plane commit, Compose version and a
SHA-256 of the emitted protocol. More than three Compose config-hash mismatches
returns `compatibility=stop`; no bulk recreation is inferred or authorized.
