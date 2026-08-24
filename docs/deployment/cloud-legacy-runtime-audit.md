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

The Cloud deployment itself already uses only `app.trinyx.fr`,
`cloud.trinyx.fr` and `auth.trinyx.fr`. CI inventories the wider runtime tree
on every change and separately rejects a legacy domain in the Cloud stack.

## Intentionally preserved compatibility material

The following are not runtime network dependencies and are not blindly renamed:

- database schema names and migration history;
- historical test fixtures/snapshots;
- compatibility Java/package names inherited by the fork;
- the `X-LiveContext-Install-Id` HTTP header at the public gateway while existing
  linked installations still emit it. It is only an untrusted selector there, is
  validated against an ACTIVE signed binding, stripped, and translated to the
  downstream HMAC-bound `X-Install-ID`.

Changing those identifiers requires a separately versioned protocol/data migration.
Their preservation does not make a request to a LiveContext host.

## Deployment note

All URL defaults remain externally configurable. Production must still inject and
verify the documented Trinyx values; this audit does not change DNS or any running
service.
