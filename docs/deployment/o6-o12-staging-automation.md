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

1. Create/configure GitHub Environment `staging`; restrict it to
   `codex/platform-release-automation`, add an independent reviewer if the plan
   supports it, prevent self-review, and never expose environment secrets to PR.
2. Review CloudFormation change sets for the registry and deploy-control-plane
   stacks in account `001634075617`, region `us-east-1`. Capture the generated
   bucket, publisher role ARN and numeric SSM document version.
3. Optionally enable the S3 Gateway endpoint only after supplying independently
   verified VPC/route-table IDs.
4. Install the exact reviewed control-plane files using the existing approved
   bootstrap/reconciler path. Verify their SHA-256 and host modes. This is the
   only checkout-dependent bootstrap; steady-state deployment is checkout-free.
5. Stop for `AWS_PCA_LIVE_APPROVAL_REQUIRED`; follow the separate O10 runbook.
6. Prove a genuine baseline artifact. Review migration expand/contract evidence
   and install the non-secret config/trust/health files.
7. Manually run `Register Staging Release` for baseline and candidate. Confirm
   artifact/run/source/release/bundle identities and native GitHub attestation.
8. Manually run the single qualification workflow. It installs both releases,
   deploys candidate Paid then Cloud, smokes, rolls back Cloud then Paid, smokes,
   redeploys the same candidate, smokes, proves idempotence and leaves that same
   candidate active.

Never use `$DEFAULT` SSM document version, SSH, a GitHub token on EC2, static AWS
credentials, `curl -k`, global Compose apply, destructive DB down-migrations,
Docker prune, volume deletion, DB drop or Redis flush.
