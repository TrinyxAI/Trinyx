# O10 staging PKI runbook

Status: repository implementation and CI validation only. No CA, certificate, key,
trust store, DNS record, AWS resource or staging runtime was created or modified.

## Decision

The default staging design is an offline, self-managed hierarchy:

```text
encrypted offline staging root
        ↓ signs once
encrypted offline staging issuer
        ↓ signs public CSR
Paid EC2 local leaf private key (0600)
        ↓
billing-internal.trinyx.private leaf certificate
        ↓
Cloud receives only the public staging CA bundle
```

This hierarchy is staging-only. Its keys, serial database, trust bundle, namespace
and recovery media must never be shared with production. The root and issuer
private keys remain encrypted and offline. The Paid leaf private key is generated
on Paid and never leaves that host. Only its CSR leaves the host.

The repository tool is `platform/pki/offline_staging_pki.py`. It fixes the
hostname to `billing-internal.trinyx.private`, creates an RSA-4096 root with
path length 1, an RSA-3072 issuer with path length 0, 90-day server leaves,
30-day CRLs, encrypted CA keys, exact serverAuth/keyUsage extensions and no
CSR extension copying.

AWS Private CA remains an optional enterprise path in
`platform/aws/staging/private-ca-plan.json`. Its default
`StagingPkiMode=OFFLINE_SELF_MANAGED` and
`PcaLiveApproval=AWS_PCA_LIVE_APPROVAL_REQUIRED` create no PCA resources.
Selecting `AWS_PRIVATE_CA` is insufficient by itself: `APPROVED` is also
required.

## Why AWS PCA is not the staging default

As rechecked on 2026-09-02, AWS lists:

- USD 400/month for each general-purpose private CA;
- USD 50/month for each short-lived private CA, whose certificates are limited
  to seven days;
- USD 0.75 for each of the first 1,000 general-purpose certificates issued per
  Region/month;
- USD 0.058 per short-lived certificate;
- additional OCSP response/query charges.

The optional template contains two general-purpose CAs, not two leaf
certificates. Its fixed CA operation charge is therefore approximately
`2 x USD 400 = USD 800/month`, before leaf issuance and OCSP. Two leaf
certificates would add approximately USD 1.50, not USD 800.

Current prices must be rechecked before any approval:
<https://aws.amazon.com/private-ca/pricing/>

For two staging hosts, the recurring managed-CA charge is disproportionate.
An external offline root plus one AWS short-lived subordinate is a supported
future compromise (approximately USD 50/month plus issuance), but seven-day
rotation automation must be proved first.

## Offline bootstrap — human-gated, not executed

Use an encrypted, access-controlled offline workstation or removable encrypted
volume. The passphrase file must be mode 0600, must not be committed, uploaded
as an artifact, stored on EC2 or sent through GitHub/SSM logs.

```bash
umask 077
python3 platform/pki/offline_staging_pki.py init \
  --workspace /approved-offline-media/trinyx-staging-pki \
  --passphrase-file /approved-offline-media/root-issuer.pass
```

Back up the complete encrypted workspace twice to independently controlled
encrypted media. Record custody, creation date, fingerprints and restore test.
Do not copy either CA private key to Cloud, Paid, GitHub, S3 release registry or
Systems Manager.

On Paid, after the reviewed control-plane has installed the tool:

```bash
sudo /usr/local/lib/trinyx/offline-staging-pki leaf-csr \
  --private-key /etc/trinyx/staging/paid/config/tls/billing-internal.pending.key \
  --csr /etc/trinyx/staging/paid/config/tls/billing-internal.csr
```

Transfer only the public CSR to the offline signer through an approved encrypted
administrative channel. Issue the 90-day leaf offline:

```bash
python3 platform/pki/offline_staging_pki.py issue \
  --workspace /approved-offline-media/trinyx-staging-pki \
  --passphrase-file /approved-offline-media/root-issuer.pass \
  --csr billing-internal.csr \
  --certificate billing-internal.crt
```

Create a public bundle containing issuer then root certificates. Stage the
bundle, certificate and the already-local Paid key with
`stage-staging-tls`. Cloud receives only the public bundle. The staging tool
checks the chain, exact hostname, certificate/private-key match, atomic
publication and mode 0600. Temporary transfer files must be removed after their
hashes and installation are independently verified.

No `curl -k`, disabled hostname validation or production trust is permitted.

## Rotation, revocation and recovery

- Rotate the leaf no later than 30 days before its 90-day expiry.
- Generate a new Paid key for each rotation; do not reuse compromised keys.
- Revoke a compromised leaf offline and regenerate the issuer CRL:

```bash
python3 platform/pki/offline_staging_pki.py revoke \
  --workspace /approved-offline-media/trinyx-staging-pki \
  --passphrase-file /approved-offline-media/root-issuer.pass \
  --certificate compromised-billing-internal.crt
```

- Distribute the reviewed CRL/trust update independently; if revocation checking
  cannot be proved end-to-end, rotate the issuer and replace trust fail-closed.
- Restore drills must prove the encrypted CA database, serials, CRL number and
  keys together. A key without its issuance database is not a complete backup.
- Root compromise requires a completely new staging hierarchy and trust
  replacement. Issuer compromise requires a new issuer signed by the offline
  root and new leaves.
- Record fingerprints, serials, validity, issuance, revocation and staging
  installation hashes in the human change record. Never record private material.

## Optional AWS PCA stop

The optional template would create two `GENERAL_PURPOSE` CAs, their
certificates/activations, separate administrator/issuer roles, and optionally a
dedicated CloudTrail bucket/trail. Production remains empty.

Before changing both PCA gates, report the exact current pricing, forecast,
paid operations, resources, principals, audit trail and deletion/recovery plan.

`AWS_PCA_LIVE_APPROVAL_REQUIRED`
