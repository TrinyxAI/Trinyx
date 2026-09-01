# Cloud staging host bootstrap snapshot

This directory captures the Cloud host bootstrap/runtime files that were validated on the staging EC2 before platform automation was introduced.

## Provenance

- Source application commit at capture: `aeb2a447ea7ce0436a60549713636225dfe1a2c1`
- Safe archive SHA-256: `32535fa9c0adb400237302bfb3a5e887630cf96f2699072778031467a62bad1f`
- Safe text bundle SHA-256: `b848f9ec8e2777135e96cfbf87742b696aa448402ca8b0421bd07b7485421efb`
- Every captured file was reconstructed from the safe text bundle and checked against the per-file SHA-256 emitted by the host.
- The bundle passed a secret-material scan before these files were versioned.

## Scope

This is deliberately a **staging-specific, validated snapshot**, not a production template.

The materializer currently embeds the staging filesystem base, AWS region and staging SSM namespace. `runtime-static.env` contains staging public URLs, and `cloud-paid.override.yml` contains the current staging Paid private address. Those values must be rendered from environment inventory before this bootstrap can be generalized.

Do not copy this tree verbatim into production. Production must have an independent SSM namespace, KMS/secrets, signing keys, passwords, private PKI/CA and certificates. The planned production PKI uses a separate AWS Private CA trust domain.

## Installed ownership and modes

Git does not preserve root ownership or `0600` data-file modes. The installer/reconciler must enforce the following target metadata:

| Target | Owner | Mode |
| --- | --- | --- |
| `/usr/local/lib/trinyx/pre-docker-cloud.sh` | `root:root` | `0750` |
| `/usr/local/lib/trinyx/runtime-materialize-cloud.sh` | `root:root` | `0750` |
| systemd units/drop-in | `root:root` | `0644` |
| `/etc/docker/daemon.json` | `root:root` | `0644` |
| `/etc/trinyx/staging/cloud/config/*` | `root:root` | `0600` |

The pre-Docker runtime truststore and password copies are intentionally not stored in Git. They are reconstructed at boot from protected persistent sources and must remain `root:1001 0640` in `/run/trinyx/auth-runtime`.

## Validated recovery contract

The captured design has already been exercised through both an OS reboot and a real EC2 Stop/Start. The validated contract is that the persistent release remains unchanged, `/srv/trinyx` remains the Docker data root, pre-Docker prerequisites are reconstructed, the runtime materializer publishes a fresh atomic generation, and the running Cloud stack returns without a new Git checkout or deployment.

## Next automation step

The next layer must install/reconcile this snapshot through a controlled deployment workflow, then parameterize environment-specific values instead of teaching EC2 instances to follow a Git branch. New commits must produce immutable release candidates; deployment to staging remains a manual promotion action.
