# Legacy LiveContext runtime dependency audit

Scope: runtime source/config under `backend`, `frontend`, `mcp`,
`websearch-service`, `docker` and `cli`. Test fixtures, generated output, documentation,
schema identifiers and compatibility names are excluded from the runtime gate.

## Replaced runtime dependencies

| Area | Previous default | Trinyx default |
|---|---|---|
| CE marketplace/control plane | `https://livecontext.ai/api` | `https://cloud.trinyx.fr/api` |
| Cloud-link OIDC | `https://auth.livecontext.ai/realms/livecontext` | `https://auth.trinyx.fr/realms/trinyx` |
| Cloud-link client | `livecontext-frontend` | `trinyx-frontend` |
| Catalog/API/skill bundles | `https://livecontext.ai` | `https://cloud.trinyx.fr` |
| User-facing Cloud web links | `https://livecontext.ai` | `https://app.trinyx.fr` |
| CE release feed default | LiveContext release feed | `https://app.trinyx.fr/api/ce/releases/latest` |
| OAuth callback fallback | LiveContext application | `https://app.trinyx.fr/api/credentials/oauth2/callback` |
| Mail/contact defaults | LiveContext mail/domain | `@trinyx.fr` / `trinyx.fr` |
| OAuth user agent | LiveContext | Trinyx |
| Catalog bundle trust root | inherited upstream key | explicit Trinyx public ring / bounded HTTPS bootstrap |

The Cloud deployment itself uses only `app.trinyx.fr`,
`cloud.trinyx.fr` and `auth.trinyx.fr`. The blocking CI gate covers every
runtime surface listed above, rejects legacy network domains, upstream GHCR image
ownership and the historical upstream bundle signer. A current-HEAD CI success is
still required before this source audit can be treated as executable proof.

## Intentionally preserved compatibility material

The following are not runtime network dependencies and are not blindly renamed:

- database schema names and migration history;
- historical test fixtures/snapshots;
- compatibility Java/package names inherited by the fork;
- internal Compose service, database, log and volume identifiers such as
  `livecontext`, `livecontext-ce` and `livecontext_data`, which preserve upgrades;
- legacy embedded JWT realm/audience/issuer defaults and `LIVECONTEXT_*` CLI
  environment aliases, which preserve existing tokens and installations;
- internal CSS selectors and the historical API-key prefix;
- the `X-LiveContext-Install-Id` HTTP header at the public gateway while existing
  linked installations still emit it. It is only an untrusted selector there, is
  validated against an ACTIVE signed binding, stripped, and translated to the
  downstream HMAC-bound `X-Install-ID`.

Changing those identifiers requires a separately versioned protocol/data migration.
Their preservation does not make a request to a LiveContext host.

## User-visible and legal occurrences

CLI commands/package metadata, mail sender defaults and mail bodies, frontend product
copy, OAuth callbacks, release links and MCP snippets use Trinyx. The remaining
`LIVECONTEXT SAS` postal address in localized contact/legal copy and the upstream
license URL in Maven metadata are legal attribution and are intentionally retained.
LICENSE, NOTICE and historical migration/test text are never rewritten.

## Trinyx-owned runtime artifacts

Package metadata, clone instructions and crawler-facing source links use the canonical
`TrinyxAI/Trinyx` repository. The historical GHCR owner is retained until replacement
packages are actually published and verified; changing it in source first would make CE
installs pull nonexistent images.

The npm CLI Compose asset references only Trinyx-owned image names for the CE
backend, frontend, MCP bridge, screenshot renderer and websearch service, pinned
to the CLI release tag (`v0.2.12`). The `Build Trinyx CE runtime images`
workflow builds every image on pull requests without publishing. It publishes
the immutable commit tag, audited `v*` release tag and `latest` only for an
explicit Git release-tag push; a main push or manual dispatch cannot publish.

This removes the configured `ghcr.io/livecontext-ai/*` supply-chain dependency, but
the new manifests do not become deployable merely because their names are present in
source. Before any CLI release or production deployment, all five images must be
successfully built from the final release commit, published under `ghcr.io/trinyxai/*`, pulled by digest in a
staging installation, and checked for database/volume compatibility. Until that
external publication and verification succeeds, supply-chain independence remains a
deployment blocker rather than a completed operational fact.

## Deployment note

All URL defaults remain externally configurable. Production must still inject and
verify the documented Trinyx values; this audit does not change DNS or any running
service.
