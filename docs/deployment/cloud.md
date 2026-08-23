# Trinyx Cloud Docker deployment

> **Status: infrastructure-complete, edge fail-closed.** The repository contains
> the Cloud microservices but does not contain the authenticated gateway they
> expect. Do not expose this stack to production until the gateway contract in
> [Authenticated gateway blocker](#authenticated-gateway-blocker) is implemented
> and tested. The Caddy edge deliberately returns HTTP 503 for authenticated
> routes instead of weakening service authentication.

This stack is separate from the paid-monolith stack. It uses a distinct Compose
project, network, database, Redis, MinIO, Keycloak and volumes. It does not read
or modify the existing paid-monolith environment files.

## Architecture

```text
Internet
   |
   +-- cloud.trinyx.fr -- TLS proxy -- 127.0.0.1:8188 --+
   |                                                     |
   |                                               cloud-edge (Caddy)
   |                                                     |
   |                      +------------------------------+------------------+
   |                      | authenticated API: 503 until gateway exists     |
   |                      | public catalog bundles / health / CDP only      |
   |                      +------------------------------+------------------+
   |                                                     |
   |   catalog  storage  auth  conversation  datasource  interface
   |    :8081    :8082  :8083      :8087        :8088      :8089
   |   agent  trigger  publication  orchestrator  websearch  SearXNG
   |   :8090   :8091      :8092        :8099       :8085     :8080
   |                      |
   |       PostgreSQL/pgvector :5432 (app schemas + keycloak schema)
   |       Redis :6379          MinIO :9000
   |
   +-- auth.trinyx.fr -- TLS proxy -- 127.0.0.1:8280
                                               |
                                        Keycloak :8080
                                   management/health :9000 internal only
```

No application, database, cache, object-storage, SearXNG or Keycloak management
port is published. Only the two loopback edge listeners are published.

## Services and ports

| Compose service | Internal port | Purpose |
|---|---:|---|
| catalog-service | 8081 | API catalog and catalog bundles |
| storage-service | 8082 | files and storage quotas |
| auth-service | 8083 | users, billing, Cloud link and entitlements |
| migration-service | 8084 (non-web runner) | the only Flyway runner |
| websearch-service | 8085 | web search/browser relay |
| conversation-service | 8087 | conversations and realtime APIs |
| datasource-service | 8088 | datasource operations |
| interface-service | 8089 | generated interfaces |
| agent-service | 8090 | agents, model/skill bundles and LLM relay |
| trigger-service | 8091 | public triggers |
| publication-service | 8092 | marketplace/publications |
| orchestrator-service | 8099 | workflows and websearch relay |
| cloud-postgres | 5432 | dedicated Cloud PostgreSQL/pgvector |
| cloud-redis | 6379 | dedicated Cloud Redis |
| cloud-minio | 9000/9001 | dedicated object storage/console (internal) |
| keycloak | 8080/9000 | OIDC and internal management health |
| searxng | 8080 | internal metasearch |
| cloud-edge | 8080/8180 | Cloud fail-closed edge / Keycloak edge |

Every Java service receives the service-DNS URLs requested by the Cloud runtime.
No service URL uses localhost.

## Environment

Copy `docker/.env.cloud.example` to the untracked
`docker/.env.cloud`. Replace every `replace-*` value.

Required secret groups:

- PostgreSQL: `CLOUD_DB_USERNAME`, `CLOUD_DB_PASSWORD`.
- Redis/MinIO: `CLOUD_REDIS_PASSWORD`, `CLOUD_MINIO_ROOT_USER`,
  `CLOUD_MINIO_ROOT_PASSWORD`.
- Keycloak: `KEYCLOAK_ADMIN_CLIENT_SECRET`,
  `KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME`,
  `KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD`.
- request/audit security: `CLOUD_GATEWAY_SECRET_KEY`, `AUDIT_HMAC_KEY`,
  `AUDIT_UA_PEPPER`, `MODEL_AUDIT_HMAC_KEY`,
  `STORAGE_SHOWCASE_HMAC_SECRET`.
- credential encryption: `CREDENTIAL_ENCRYPTION_PASSWORD`,
  `CREDENTIAL_ENCRYPTION_SALT`.
- bundle signing: `CATALOG_BUNDLE_SIGNING_KEY_PEM`,
  `CATALOG_BUNDLE_SIGNING_PUBLIC_KEY`,
  `CATALOG_BUNDLE_SIGNING_KEY_ID`.
- websearch: `WEBSEARCH_GATEWAY_SECRET`, `WEBSEARCH_CDP_JWT_SECRET`,
  `SEARXNG_SECRET`.
- external services: `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`,
  `STRIPE_SUCCESS_URL`, `STRIPE_CANCEL_URL`, and the `SMTP_*` values.

Non-secret deployment values include `TRINYX_CLOUD_IMAGE_TAG`, the two edge
bind/port pairs, `CLOUD_DB_NAME`, pool sizes, `CLOUD_MINIO_BUCKET`,
`KEYCLOAK_REALM_NAME`, the two Keycloak client IDs, and
`CATALOG_BUNDLE_ISSUER`.

Provider keys (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GOOGLE_API_KEY`,
`GEMINI_API_KEY`, `MISTRAL_API_KEY`, `DEEPSEEK_API_KEY`) are optional and
must come from an external secret store. `PISTON_URL` must point to a separately operated compatible code-execution service; no such implementation exists in this repository.

The Compose file pins the required edition flags:

```text
APP_EDITION=cloud
DEPLOYMENT_MODE=microservice
AUTH_MODE=keycloak
MARKETPLACE_MODE=local
BILLING_PROVIDER=stripe
CREDIT_UNLIMITED=false
CREDIT_CONSUMPTION_ENABLED=true
PLAN_LIMITS_ENABLED=true
WORKFLOW_NODE_BILLING_ENABLED=true
```

## Keycloak

The repository had no production Keycloak provisioning script or realm export to
reuse. The new stack therefore builds an optimized Keycloak image and imports
`docker/cloud/keycloak/trinyx-realm.json` on first start.

The imported realm is `trinyx`; application configuration already makes the
realm, issuer and audience configurable, so retaining the legacy realm name is
not required. The import creates:

- public PKCE client `trinyx-frontend`;
- confidential service-account client `trinyx-admin-api`;
- an audience mapper for `trinyx-frontend`;
- the minimum repository-observed realm-management roles for user and identity
  provider administration;
- the known callback `https://app.trinyx.fr/api/cloud-link/callback`.

Keycloak startup import skips a realm that already exists. Subsequent client
secret or redirect changes must be applied through Keycloak administration, not
by expecting a container restart to overwrite the realm.

Self-hosted CE installations can have arbitrary callback origins. They cannot be
safely covered by a universal redirect wildcard. Register each additional CE
callback exactly, or provision a dedicated client for that installation.

The public issuer is
`https://auth.trinyx.fr/realms/trinyx`. Services use the internal Keycloak DNS
name only for JWK retrieval, while validating the public issuer. Never publish
Keycloak port 9000.

## Authenticated gateway blocker

The microservices do not accept a browser bearer token directly. Their common
filter expects an authenticated gateway to:

1. validate the Keycloak JWT and its `trinyx-frontend` audience;
2. call auth-service's user-resolution endpoint to map Keycloak `sub` to the
   local numeric user ID;
3. inject `X-User-ID` and, where applicable, organization/role context;
4. generate the repository's timestamped HMAC headers
   (`X-Gateway-Secret`, `X-Gateway-Timestamp`, `providerId`);
5. route the request to the owning service.

No module or Dockerfile implementing that contract exists in this repository.
A plain Caddy/nginx router cannot perform the user mapping and signing safely.
Setting `GATEWAY_VERIFICATION_ENABLED=false` would permit identity/header
spoofing and is explicitly forbidden.

Consequently the edge exposes only service health, the catalog controller's
explicit public bundle route, and the separately JWT-protected CDP path.
Authenticated paths return `authenticated_gateway_missing` with HTTP 503.

This blocks end-to-end CE/paid-monolith registration, heartbeat, entitlements,
authenticated catalog/skill/model bundles, LLM relay and websearch relay even
though their controllers are present. The relevant implemented API families are:

- auth-service: `/api/ce-link/**`;
- catalog-service: `/api/ce-catalog/**` and
  `/api/catalog/public/bundles/**`;
- agent-service: `/api/ce-llm/**`, `/api/catalog-bundles/**`,
  `/api/skill-bundles/**`;
- orchestrator-service: `/api/ce-websearch/**`.

Do not replace the 503 rule with direct reverse proxies. Supply and test the
missing gateway, then update `docker/cloud/Caddyfile` to proxy to that single
gateway only.

## Reverse proxy and DNS

Create A/AAAA records for `cloud.trinyx.fr` and `auth.trinyx.fr` pointing to
the Cloud host. The existing `app.trinyx.fr` record and deployment are not
changed.

On the host TLS proxy:

```caddyfile
cloud.trinyx.fr {
    reverse_proxy 127.0.0.1:8188
}

auth.trinyx.fr {
    reverse_proxy 127.0.0.1:8280
}
```

Preserve `X-Forwarded-For`, `X-Forwarded-Proto` and `Host`. Do not proxy
Keycloak's management port.

After the missing gateway is delivered, the existing paid-monolith Cloud-link
client would need these external runtime values (do not change production during
this PR):

```text
MARKETPLACE_CLOUD_API_URL=https://cloud.trinyx.fr/api
CLOUD_LINK_KEYCLOAK_URL=https://auth.trinyx.fr/realms/trinyx
CLOUD_LINK_CLIENT_ID=trinyx-frontend
CLOUD_LINK_REDIRECT_URI=https://app.trinyx.fr/api/cloud-link/callback
```

## Database and startup order

The migration service is the sole Flyway runner. It applies all ten application
schemas to the dedicated `trinyx_cloud` database. PostgreSQL initialization
creates a separate `keycloak` schema in the same dedicated Cloud database.
Application services set `SPRING_FLYWAY_ENABLED=false`.

Startup order is:

1. PostgreSQL, Redis and MinIO;
2. MinIO bucket initialization and Keycloak;
3. migration-service (must exit 0);
4. Java services, websearch and SearXNG;
5. cloud-edge after service health checks.

For an existing volume, back it up before migrations. The PostgreSQL
initialization SQL only runs when the data directory is empty; create the
`keycloak` schema manually before first Keycloak start if attaching an existing
database.

## Frontend and Stripe audit

A frontend is not required for the CE/paid-monolith Cloud-link control-plane
calls; those clients already own their UI. A standalone Cloud console would
require a separately built Cloud frontend and the missing authenticated gateway.
Neither is invented by this stack.

Cloud billing code lives in auth-service. This stack requires credentials and
redirect URLs externally but contains no Stripe Price ID and does not reuse the
paid-monolith credentials. Before enabling billing traffic, create Cloud-specific
Stripe TEST products/prices, configure the Cloud plan records, register
`https://cloud.trinyx.fr/webhooks/stripe` through the eventual gateway, and
complete a full TEST-mode webhook/idempotency validation. Do not use LIVE mode.

## Validation and commands

From the repository root:

```bash
cp docker/.env.cloud.example docker/.env.cloud
# Replace every placeholder in docker/.env.cloud.

docker compose --env-file docker/.env.cloud \
  -f docker/docker-compose.cloud.yml config --quiet

docker compose --env-file docker/.env.cloud \
  -f docker/docker-compose.cloud.yml build
```

Local/staging startup sequence (not a production deployment):

```bash
docker compose --env-file docker/.env.cloud \
  -f docker/docker-compose.cloud.yml up -d \
  cloud-postgres cloud-redis cloud-minio cloud-minio-init keycloak searxng

docker compose --env-file docker/.env.cloud \
  -f docker/docker-compose.cloud.yml run --rm migration-service

docker compose --env-file docker/.env.cloud \
  -f docker/docker-compose.cloud.yml up -d \
  auth-service catalog-service storage-service conversation-service \
  datasource-service interface-service agent-service trigger-service \
  publication-service websearch-service orchestrator-service cloud-edge
```

Confirm the fail-closed state:

```bash
curl -fsS http://127.0.0.1:8188/healthz
curl -i http://127.0.0.1:8188/api/ce-link/mine
# Expected: HTTP 503 authenticated_gateway_missing
```

Do not run these commands on the paid-monolith host without capacity planning
and an explicit deployment change. This pull request performs no deployment,
DNS, Stripe, Keycloak, database or production mutation.

## Known missing capabilities and risks

- authenticated gateway: hard blocker for production and Cloud-link;
- no standalone Cloud frontend/control-plane UI;
- no code-node/Piston-compatible service in the repository; `PISTON_URL` is an external dependency;
- Stripe Cloud products/prices and webhook registration remain external;
- Keycloak redirect URIs for arbitrary CE hosts require explicit provisioning;
- websearch/Chromium is resource-heavy and needs host capacity validation;
- container tags should be updated only through reviewed dependency maintenance;
- production backups, observability, alerting and secret-manager integration are
  operator responsibilities outside this Compose-only change.
