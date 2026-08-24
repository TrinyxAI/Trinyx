# Trinyx Cloud deployment

> **Status:** dependent draft implementation. Do not deploy or merge before the
> staging checklist and capacity review are complete. This stack never modifies
> the existing paid-monolith Compose project.

## Architecture

```text
Browser / linked paid-monolith
        |
        | OIDC JWT (issuer auth.trinyx.fr, audience trinyx-frontend)
        v
cloud.trinyx.fr -> Caddy :8188 -> gateway-service :8086
                                      |
                                      | strips client identity headers
                                      | HMAC-SHA256 v2, exact body + target
                                      v
 auth :8083  catalog :8081  agent :8090  orchestrator :8099
 storage :8082  conversation :8087  datasource :8088
 interface :8089  trigger :8091  publication :8092
        |
        +-- dedicated PostgreSQL/pgvector, Redis and MinIO

auth.trinyx.fr -> Caddy :8280 -> Keycloak :8080

Cloud -- private TLS + short Ed25519 workload JWT --> billing-internal.trinyx.private
       reserve / commit / release                         (paid-monolith wallet authority)

app.trinyx.fr -- private TLS + workload JWT --> Cloud auth-service
               signed projections + identity tombstones
```

The gateway authenticates and routes. It does not calculate plans, interpret
Stripe status, or own a wallet. `app.trinyx.fr` remains authoritative for
Stripe, subscription, plan, subscription/PAYG buckets, delinquency and ledger.

Cloud stores:

- a persistent actor identity binding;
- a signed, monotonic, expiring entitlement projection scoped by
  `issuer + installId + organizationId + billingSubjectId`;
- technical reservation and settlement delivery state, never a spendable
  balance.

## Identity and entitlement contracts

Identity fields remain distinct:

- `keycloakSubject`: Keycloak JWT `sub`;
- `principalId`: stable actor UUID;
- `billingSubjectId`: payer/wallet UUID;
- `organizationId`: selected workspace;
- `installId`: linked installation.

Email is never used to reconcile identities. The numeric `X-User-ID` is a
Cloud-local compatibility value only.

IdentityBinding v2 is a five-minute Ed25519 JWS. It includes issuer, audience,
JTI, time claims, monotonic `bindingRevision`, all five identifiers and the
paid-authority-derived `organizationRole`. A subject change requires explicit
revoke/rebind. The gateway requires the JWT `sub` to equal the signed
`keycloakSubject`; it never substitutes a browser-supplied role. The signed
organization membership is materialized in gateway context so first link does
not depend on the unrelated personal workspace created by Cloud JIT onboarding.

EntitlementProjection v2 deliberately excludes actor identity and spendable
balance. It contains plan/cadence/subscription state, typed features and limits,
`accessState`, sequence, event ID and 15-minute validity. Cloud semantics are:

| Incoming state | Result |
|---|---|
| higher sequence | apply |
| same sequence and same canonical hash | idempotent success |
| same sequence and different hash | `409 EQUIVOCATION_DETECTED` |
| lower sequence | `409 STALE_PROJECTION` |
| invalid/expired assertion | reject/fail closed |
| `REVOKED` | retain tombstone until a higher signed sequence |

Paid-monolith computes `ACTIVE`, `GRACE`, `DENIED` or `REVOKED`.
The initial `past_due` grace is configurable and defaults to 72 hours.
Projection refresh defaults to five minutes; expiration is never treated as
infinite last-known-good authorization. Unlink increments `bindingRevision`, signs
a `REVOKED` identity tombstone, and delivers it through a separate durable outbox.
Cloud retains that row and refuses any stale active assertion.

## Wallet protocol

The sole authority endpoints on paid-monolith are not edge-routed:

```text
POST /internal/v1/credit-reservations
POST /internal/v1/credit-reservations/{operationId}/commit
POST /internal/v1/credit-reservations/{operationId}/release
```

A provider call must not begin before reserve succeeds. `operationId` and
`requestHash` are stable idempotency identities. The authority locks the
native wallet and preserves the subscription/PAYG split. Commit converts the
hold to actual usage and releases the difference. Overrun is accounted even
when it creates delinquency. Release is terminal and idempotent. Reservation
TTL is ten minutes; actual incurred cost may settle for 24 hours after expiry.

Cloud persists failed commit/release delivery in
`auth.cloud_settlement_outbox` with jittered exponential retry. It never falls
back to a local balance when the authority is unavailable.

LLM relays send the provider/model and prompt/output token ceiling before
dispatch; paid-monolith computes the conservative hold from its own pricing and
recomputes settlement from the complete prompt, completion, cache and reasoning
usage. Cloud-provided monetary hints are ignored for this source. Browser-agent
relays additionally cap the external path to 25 steps and 4,096 output tokens
per step before reserving an aggregate amount. Until browser providers expose a
single verifiable cost receipt, that aggregate remains trusted workload input
and is therefore a staging/load-test risk rather than an independently
repriceable LLM settlement. Flat-price web search reserves before SearXNG; its
configured fixed price is read only by the paid-monolith authority, never
accepted from Cloud. Provider-specific output, iteration, timeout and
concurrency limits must remain enabled at each provider adapter; the wallet hold
is the final monetary boundary, not a substitute for those operational limits.

## Gateway HMAC v2

Cloud sets `GATEWAY_FILTER_ACCEPT_V1=false`. A controlled rolling migration
can temporarily enable v1 on downstream services and set
`GATEWAY_SIGNATURE_VERSION=1` on a legacy Java signer. Remove both after all
producers are v2.

Headers:

```text
X-Gateway-Signature-Version: 2
X-Gateway-Timestamp
X-Gateway-Nonce
X-Gateway-Body-SHA256
X-Gateway-Secret
X-Provider-ID
X-User-ID
X-Principal-ID
X-Billing-Subject-ID
X-Organization-ID
X-Organization-Role
X-User-Roles
X-Install-ID
```

Canonical UTF-8 payload:

```text
TRINYX-HMAC-V2
timestamp
nonce
HTTP_METHOD
FINAL_DOWNSTREAM_REQUEST_TARGET
bodySha256
providerId
userId
principalId
billingSubjectId
organizationId
organizationRole
userRoles
installId
```

The request target includes the raw query. Roles are uppercase, de-duplicated
and lexicographically sorted. Empty fields remain empty lines. Timestamp skew
is ±60 seconds. Redis consumes each nonce once for five minutes. The servlet
and reactive gateway wrappers hash the exact bytes later read by controllers
and reject bodies above 10 MiB by default.

All externally supplied identity, role, assertion and HMAC headers are removed
before trusted values are added.

`X-LiveContext-Install-Id` remains accepted at the public edge only as a
backward-compatible, untrusted installation selector. `X-Trinyx-Install-ID` and an
inbound `X-Install-ID` are treated the same way. The gateway resolves the selector
against the Keycloak subject's ACTIVE signed binding, rejects conflicting selectors, removes
all selector headers, and injects only the resolved HMAC-bound `X-Install-ID` downstream.
The browser `Authorization` header and any `lc.jwt.*` WebSocket authentication
subprotocol are also consumed at the edge and never forwarded to microservices; non-secret
application subprotocols are preserved.
When a subject owns several installations, a selector (or a fresh signed binding during
link/rebind) is mandatory; the gateway never chooses an arbitrary installation.

## Routing

Only `cloud-edge` publishes a loopback application port. It forwards all
Cloud application traffic to gateway-service.

| External path | Destination | Policy |
|---|---|---|
| `/healthz`, `/actuator/health` | gateway | public |
| `/api/ce/releases/latest` | auth-service | public release metadata only |
| `/webhooks/stripe` | auth-service | Stripe signature, no browser JWT |
| `/api/catalog/public/bundles/**` | catalog-service | explicit public allowlist |
| `/api/ce-link/**` | auth-service | JWT + signed identity; lifecycle/repair is identity-only, entitlement response itself fails closed |
| `/api/users/**`, `/api/billing/**`, `/api/credits/**` | auth-service | JWT + projection |
| `/api/ce-catalog/**` | catalog-service | `catalogBundle` |
| `/api/catalog-bundles/**` | agent-service | `catalogBundle` |
| `/api/skill-bundles/**` | agent-service | `skillBundle` |
| `/api/ce-llm/**` | agent-service | `cloudLlmRelay` + reserve |
| `/api/ce-websearch/**` | orchestrator-service | `cloudWebSearchRelay` + reserve |
| `/ws/**` | conversation-service | authenticated upgrade |
| `/cdp/**` | websearch-service | CDP's dedicated token |
| `/api/internal/**`, `/internal/**` | none | never edge-routed |

The downstream service URLs use Docker DNS names; no service uses localhost.
Keycloak is externally reachable only through `auth.trinyx.fr`.

## Key material and required environment

No private key belongs in Git. Use a secret manager and separate Ed25519 key
pairs for identity assertions, entitlement assertions and workload
authentication. Verification rings use
`kid=base64-X509;kid2=base64-X509`; signers use base64 PKCS#8 plus an active
kid. Keep previous public keys during rotation.

Cloud requires all placeholders in `docker/.env.cloud.example`, notably:

```text
CLOUD_DB_USERNAME
CLOUD_DB_PASSWORD
CLOUD_REDIS_PASSWORD
CLOUD_MINIO_ROOT_USER
CLOUD_MINIO_ROOT_PASSWORD
KEYCLOAK_ADMIN_CLIENT_SECRET
KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME
KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD
CLOUD_GATEWAY_SECRET_KEY
CATALOG_BUNDLE_SIGNING_KEY_PEM
CATALOG_BUNDLE_SIGNING_PUBLIC_KEY
CATALOG_BUNDLE_SIGNING_KEY_ID
TRINYX_IDENTITY_VERIFICATION_KEYS
TRINYX_ENTITLEMENT_VERIFICATION_KEYS
TRINYX_S2S_SIGNING_KID
TRINYX_S2S_SIGNING_KEY
TRINYX_S2S_VERIFICATION_KEYS
PAID_MONOLITH_BILLING_URL=https://billing-internal.trinyx.private
```

Cloud S2S direction is fixed by Compose:

```text
signing issuer=trinyx-cloud
signing audience=trinyx-billing-authority
verification issuer=trinyx-paid-authority
verification audience=trinyx-cloud-internal
```

Before staging, paid-monolith must receive external secrets/configuration for
the opposite direction:

```text
BILLING_AUTHORITY_MODE=paid-monolith-authority
CLOUD_LINK_ENABLED=true
CATALOG_BUNDLE_TRUSTED_KEYS=<Cloud catalog signing kid=base64-X509 public key>
TRINYX_IDENTITY_SIGNING_KID=<active kid>
TRINYX_IDENTITY_SIGNING_KEY=<PKCS8 private key>
TRINYX_ENTITLEMENT_SIGNING_KID=<active kid>
TRINYX_ENTITLEMENT_SIGNING_KEY=<PKCS8 private key>
TRINYX_S2S_SIGNING_ISSUER=trinyx-paid-authority
TRINYX_S2S_SIGNING_AUDIENCE=trinyx-cloud-internal
TRINYX_S2S_SIGNING_KID=<paid workload kid>
TRINYX_S2S_SIGNING_KEY=<paid workload private key>
TRINYX_S2S_VERIFICATION_ISSUER=trinyx-cloud
TRINYX_S2S_VERIFICATION_AUDIENCE=trinyx-billing-authority
TRINYX_S2S_VERIFICATION_KEYS=<Cloud workload public ring>
TRINYX_ENTITLEMENT_CLOUD_INGEST_URL=https://cloud-internal.trinyx.private:8443/internal/v1/entitlement-projections
TRINYX_IDENTITY_CLOUD_REVOCATION_URL=https://cloud-internal.trinyx.private:8443/internal/v1/identity-bindings/revocations
```

Cloud uses:

```text
APP_EDITION=cloud
DEPLOYMENT_MODE=microservice
AUTH_MODE=keycloak
MARKETPLACE_MODE=local
BILLING_PROVIDER=none
BILLING_AUTHORITY_MODE=external-paid-monolith
```

The production catalog trust root is explicit. The public key represented by
`CATALOG_BUNDLE_TRUSTED_KEYS` must match the externally injected Cloud bundle
signer; the repository no longer inherits or trusts the historical LiveContext key.
Apply the same public ring to every linked CE/paid-monolith before enabling bundle
sync, and keep the previous Trinyx public key only for a bounded rotation window.
An empty development installation may use the existing HTTPS signing-key bootstrap,
but verification remains closed and no bundle is applied before a key is pinned.

There is no Cloud Stripe requirement, Price ID, customer, subscription or
wallet for linked users. Native billing code is retained for other modes.

### Cloud-link bootstrap and unlink

Cloud-link lifecycle is gated by `CLOUD_LINK_ENABLED`, independently of
`MARKETPLACE_MODE`; paid-monolith may therefore keep its marketplace local. In
`paid-monolith-authority` mode the existing OAuth completion stores the local
link first, resolves its trusted organization scope, and calls the local auth
service to issue an `AuthorityBundle`. The first Cloud
`POST /api/ce-link/register` carries:

```text
Authorization: Bearer <Keycloak access token>
X-Trinyx-Identity-Binding: <five-minute Ed25519 JWS>
X-Trinyx-Entitlement-Projection: <fifteen-minute Ed25519 JWS>
X-Trinyx-Organization-ID: <signed organization scope>
```

The gateway verifies and consumes the binding JTI, applies the projection
idempotently, strips all bootstrap headers, then injects HMAC v2 context. Later
heartbeats and reads use the persisted binding; the five-minute assertion is not
a five-minute login. Projection refresh/outbox delivery remains five-minute and
15-minute fail-closed.

Unlink deliberately orders operations as Cloud registry revoke, paid-authority
signed identity/entitlement tombstones, then local link deletion. A failure
before the tombstones are committed leaves the local row retryable; no blind
identity deletion occurs. Cloud-link lifecycle routes remain identity-authenticated
even when a projection has expired so heartbeat repair and revocation cannot be
blocked by stale billing data. Paid relay/bundle routes still require a current
feature projection.

## Keycloak

The imported realm is `trinyx`. It provides public PKCE client
`trinyx-frontend` and confidential service-account client
`trinyx-admin-api`. The public issuer is:

```text
https://auth.trinyx.fr/realms/trinyx
```

The gateway requires audience `trinyx-frontend`. Internal JWK retrieval uses
the Keycloak Docker name but issuer validation always uses the public URL.
Register arbitrary self-hosted callback origins explicitly; do not use a broad
wildcard. Never expose Keycloak management port 9000.

## Database and startup

Flyway migration `V436__external_billing_authority.sql` is backward-compatible.
It adds stable UUID identities, binding/projection state, entitlement and identity-tombstone outbox state
and reservation idempotency. Native subscription, wallet, PAYG and ledger
tables are not removed or replaced.

Startup order:

1. dedicated PostgreSQL, Redis and MinIO;
2. MinIO initialization and Keycloak;
3. migration-service (sole Flyway runner, must exit 0);
4. auth-service and remaining microservices;
5. gateway-service;
6. cloud-edge.

Back up an existing Cloud volume before Flyway. Keycloak's schema bootstrap SQL
runs only on a new PostgreSQL volume.

## DNS and host proxy

```caddyfile
cloud.trinyx.fr {
    reverse_proxy 127.0.0.1:8188
}

auth.trinyx.fr {
    reverse_proxy 127.0.0.1:8280
}
```

Do not change `app.trinyx.fr`. Preserve Host and forwarded-protocol/client-IP
headers. Never proxy database, Redis, MinIO, service or Keycloak-management
ports.

The Compose stack also publishes port 8443 only on `CLOUD_INTERNAL_BIND`.
Set that value to the Cloud EC2 private VPC address, create private DNS
`cloud-internal.trinyx.private`, and allow the port only from the paid-monolith
security group. Caddy uses a persisted internal CA; export its root certificate
into the paid-monolith JVM trust store before enabling dispatch. The public
host never routes `/internal/**`. Workload JWT remains mandatory in addition
to TLS and the network allowlist.

## Validation and deployment commands

These commands are documentation only; this PR does not run them:

```bash
cp docker/.env.cloud.example docker/.env.cloud
# Replace every placeholder using the secret manager.

docker compose --env-file docker/.env.cloud \
  -f docker/docker-compose.cloud.yml config --quiet

docker compose --env-file docker/.env.cloud \
  -f docker/docker-compose.cloud.yml build

docker compose --env-file docker/.env.cloud \
  -f docker/docker-compose.cloud.yml up -d \
  cloud-postgres cloud-redis cloud-minio cloud-minio-init keycloak searxng

docker compose --env-file docker/.env.cloud \
  -f docker/docker-compose.cloud.yml run --rm migration-service

docker compose --env-file docker/.env.cloud \
  -f docker/docker-compose.cloud.yml up -d
```

Before production: generate/rotate keys, configure the paid authority, run
migration backups/restores, complete the provider-stub E2E, test revoke and
late settlement, validate WebSocket/CDP behavior, install metrics/alerts and
capacity-test Chromium.

## Capacity and remaining external dependencies

Do not run this stack on the current 2-vCPU/7.6-GiB/27-GiB-free host.

- minimal Cloud without Chromium: 4 vCPU, 16 GiB RAM, 100 GiB disk;
- full Cloud with websearch/Chromium: 8 vCPU, 32 GiB RAM, 150 GiB disk after a
  real load test.

A compatible Piston/code-execution service is not present in this repository
and remains externally operated. Provider API keys, SMTP, TLS/DNS, backups,
observability and production secret injection are operator responsibilities.
No standalone Cloud frontend is required for linked paid-monolith control-plane
flows.


## Private Cloud-to-paid wallet edge

Wallet reservations must never traverse the public `app.trinyx.fr` virtual host. Deploy
`docker/paid-monolith-internal/Caddyfile` on the paid-monolith host with these invariants:

- bind the listener only to its private VPC address;
- allow ingress only from the Cloud security group;
- use a certificate trusted by the Cloud JVM;
- route only the three POST reservation/commit/release shapes;
- remove forwarding headers before the loopback hop to the monolith;
- keep the public proxy forwarding `Forwarded` or `X-Forwarded-For`.

`MonolithSecurityFilter` returns 404 for the wallet surface unless the request is a genuine
non-forwarded loopback hop. It does not parse the Ed25519 bearer as an embedded user JWT;
`WorkloadAuthenticationService` remains the sole token verifier in the controller.

Set `PAID_MONOLITH_BILLING_URL=https://billing-internal.trinyx.private` in the Cloud secret
environment. Do not publish that hostname in public DNS and do not add `/internal/v1/**`
to the public app proxy.


### TLS trust for the private wallet hop

`auth-service` never disables TLS verification. Build a PKCS12 truststore containing
the CA (or exact issuing chain) for `billing-internal.trinyx.private`, keep it outside Git,
and set:

```dotenv
PAID_MONOLITH_TRUSTSTORE_PATH=/absolute/host/path/to/paid-monolith-truststore.p12
PAID_MONOLITH_TRUSTSTORE_PASSWORD=<external-secret>
```

Compose mounts it read-only only into Cloud `auth-service`. Certificate rotation must
publish the new CA alongside the old one, restart the Cloud auth container, rotate the server
certificate, then remove the retired CA. Never use an insecure trust-all client.
