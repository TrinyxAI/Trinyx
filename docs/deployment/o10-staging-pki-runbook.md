# O10 staging PKI approval and operations runbook

Status: repository design only. No live CA, certificate, key, trust store, DNS,
AWS or production resource was created by this change.

## Mandatory stop

`private-ca-plan.json` defaults to a condition that creates no paid resource.
Before changing `PcaLiveApproval` to `APPROVED`, stop and report:

```text
AWS_PCA_LIVE_APPROVAL_REQUIRED
```

Re-open the current [AWS Private CA pricing page](https://aws.amazon.com/private-ca/pricing/)
at approval time. Confirm the monthly price for **two GENERAL_PURPOSE private
CAs**, per-certificate issuance tiers, managed OCSP charges, CloudTrail/S3
storage, and any short-lived-certificate alternative. Record the date, region,
calculator output and approver in the change ticket. Private CA charges begin
when a CA is created, including while a CA exists but is not actively issuing.

Price checked on 2026-09-01: USD 400 per GENERAL_PURPOSE CA-month, USD 0.75
per certificate for the first 1,000 certificates in a Region/month, USD 0.06
per certificate-month for which an OCSP response is generated, and USD 0.20 per
100,000 OCSP queries per CA. The planned fixed CA operation cost is therefore
approximately **USD 800/month**, prorated for partial months, plus a few dollars
at most for the initial root/subordinate/leaf issuance and low-volume OCSP,
plus ordinary CloudTrail/S3 storage/request charges if a dedicated trail is
enabled. The exact tax, region/account billing and calculator result must still
be revalidated immediately before live approval.

## Planned staging-only resources

- one retained RSA-4096 staging root CA (10-year certificate);
- one retained RSA-3072 path-length-zero internal TLS issuer (5-year
  certificate);
- managed OCSP on both CAs;
- a CA administrator role that cannot issue end-entity certificates;
- an issuer role scoped to the staging subordinate and end-entity template;
- optional dedicated single-region CloudTrail trail and retained SSE-S3 audit
  bucket only if the account/organization trail is not already sufficient.

AWS Private CA manages CA private keys; there is no customer KMS key in this
design because adding an unrelated KMS key would not control those keys. Paid
host leaf keys are generated on the host, mode 0600, and never leave it. The
staging hierarchy, roles, host keys, `/trinyx/staging/pki/*` namespace and trust
bundle must never be reused by production. A separate AWS account is preferred;
account `001634075617` is the minimum accepted isolation boundary for this
staging plan. Production remains empty.

## Bootstrap procedure after approval

1. Verify account `001634075617`, region `us-east-1`, change ticket, current
   pricing, budget alert and adequate CloudTrail management-event coverage.
2. Independently review the CloudFormation change set. Require exactly the
   staging resources above and no production name, namespace or principal.
3. Supply distinct administrator and issuer principals. Neither may be the
   GitHub deploy or release-publisher role.
4. Apply the stack with `PcaLiveApproval=APPROVED`. Capture CA ARNs and CloudTrail
   evidence. Do not issue a leaf yet.
5. On Paid, generate the private key and CSR for the exact internal DNS name
   `billing-internal.trinyx.private`; include that DNS SAN. Issue with the
   subordinate, retrieve the certificate/chain, and verify hostname/SAN,
   validity and chain. Never transmit or log the private key.
6. Distribute only the public root/intermediate chain to Cloud. Bind Caddy to
   the Paid leaf/key with root-only permissions. Validate a normal TLS handshake
   and hostname verification from Cloud. `curl -k`, `--insecure`, disabled
   hostname verification and catch-all trust stores are forbidden.
7. Run revocation proof using a disposable leaf, confirm managed OCSP behavior,
   and preserve CloudTrail evidence before declaring O10 live validated.

## Rotation, recovery and revocation

- Leaf certificates rotate before one-third of remaining lifetime; create and
  validate the new certificate before atomic file-pointer activation, reload
  Caddy, test, then retain the previous pair for bounded rollback.
- Rotate the subordinate with an overlap window and dual public-chain trust.
  Root rotation is exceptional and requires a separate change with dual trust.
- Revoke compromised leaves immediately, capture reason/time/serial, verify
  OCSP, rotate the key, then remove old trust only after all clients converge.
- Back up no CA private key (AWS manages it). Retain CloudFormation, CA
  certificates, issuance inventory, CloudTrail and host leaf-key recovery
  procedures. A lost host leaf key is replaced, never reconstructed.
- CA deletion is never automated. The template retains both CAs and audit data;
  disable and investigate before any separately approved deletion schedule.
