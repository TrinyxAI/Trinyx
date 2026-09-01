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
