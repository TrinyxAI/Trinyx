# Paid staging host bootstrap snapshot

This directory captures the Paid host bootstrap/runtime files that were validated on the staging EC2 before platform automation was introduced.

## Provenance

- Source application commit at capture: `aeb2a447ea7ce0436a60549713636225dfe1a2c1`
- Safe archive SHA-256: `b3bd7de109ae5b5563ea8574d340643764873c819bf7b7e585c281ee0820e2cd`
- Safe text bundle SHA-256: `99851d0b198a6408d68b065e25e3bdb38c6c91f02e8d9783be4fc6d486be94f8`
- All eight captured files were reconstructed from the safe text bundle and checked byte-for-byte against the per-file SHA-256 emitted by the host.
- The Paid archive passed both signature/path and semantic secret-material scans before these files were versioned.

## Scope

This is deliberately a **staging-specific, validated snapshot**, not a production template.

The current materializer embeds `/etc/trinyx/staging/paid`, `us-east-1`, and `/trinyx/staging/paid/`. The overrides also embed staging URLs, the current Paid private address, and a source-release-specific Caddyfile path. The compatibility symlink `paid-runtime-aeb2.override.yml` is transitional and must disappear from the generalized release mechanism.

Do not copy this tree verbatim into production. Production must receive independent SSM/Secrets Manager namespaces, KMS keys, signing keys, passwords, network addresses and a separate AWS Private CA trust domain. No staging private key or certificate is a production bootstrap input.

## Installed ownership and modes

Git does not preserve root ownership or `0600` data-file modes. The installer/reconciler must enforce:

| Target | Owner | Mode |
| --- | --- | --- |
| `/usr/local/lib/trinyx/runtime-materialize-paid.sh` | `root:root` | `0750` |
| systemd unit/drop-in | `root:root` | `0644` |
| `/etc/docker/daemon.json` | `root:root` | `0644` |
| `/etc/trinyx/staging/paid/config/*` | `root:root` | `0600` |

The generated `/run/trinyx/paid-secrets.env` is runtime-only secret material. It is not stored in Git and the decrypted raw SSM JSON is explicitly removed before generation publication.

## Validated runtime contract

The Paid materializer publishes complete immutable generations and atomically switches `/run/trinyx/paid-current`. The runtime override keeps the paid monolith at a 3 GiB memory limit and uses the Spring liveness endpoint instead of aggregate health, which intentionally depends on Stripe configuration.

The staging Paid host has already been exercised through both an OS reboot and a real EC2 Stop/Start without manual repair. The validated topology keeps the private Caddy edge in `network_mode: service:livecontext`.

## Next automation step

The next layer must parameterize environment-specific values, install/reconcile the host state from Git, and remove source-commit-specific compatibility paths. Staging remains the proving environment; production infrastructure stays uncreated until the staging automation and full A-to-Z validation gates are complete.
