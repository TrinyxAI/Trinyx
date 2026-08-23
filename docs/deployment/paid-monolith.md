# Trinyx paid monolith

This mode keeps the existing monolith, embedded authentication, PostgreSQL,
Redis, MinIO, bridge, service/container names, and backend port 8080. It enables
the billing and finite-credit components already packaged in
`monolith-service`; it does not add an auth container, Keycloak, a gateway, or
another billing service.

## Required runtime configuration

Backend:

```dotenv
BACKEND_IMAGE=ghcr.io/eddinerabii/trinyx-backend:<tested-sha>
FRONTEND_IMAGE=ghcr.io/eddinerabii/trinyx-frontend:<tested-sha>
APP_EDITION=paid-monolith
AUTH_MODE=embedded
DEPLOYMENT_MODE=monolith

BILLING_PROVIDER=stripe
CREDIT_UNLIMITED=false
CREDIT_CONSUMPTION_ENABLED=true
PLAN_LIMITS_ENABLED=true
WORKFLOW_NODE_BILLING_ENABLED=true
BILLING_LLM_CLOUD_MULTIPLIER=1.8
CREDENTIALS_PLATFORM_MARKUP_ENABLED=true

APP_PUBLIC_URL=https://app.trinyx.fr
STRIPE_SECRET_KEY=<aws-secret>
STRIPE_WEBHOOK_SECRET=<aws-secret>
STRIPE_SUCCESS_URL=https://app.trinyx.fr/app/settings/billing?checkout=success
STRIPE_CANCEL_URL=https://app.trinyx.fr/app/settings/pricing?checkout=cancelled
STRIPE_CURRENCY=usd
```

Keep the existing database, Redis, MinIO, JWT/encryption, storage, bridge, LLM
provider, OAuth callback, and optional web-search variables. Do not rotate
`JWT_SECRET`, `CREDENTIAL_ENCRYPTION_PASSWORD`, or
`CREDENTIAL_ENCRYPTION_SALT` during this deployment.

Frontend build-time configuration:

```dotenv
NEXT_PUBLIC_APP_EDITION=ce
NEXT_PUBLIC_AUTH_MODE=embedded
NEXT_PUBLIC_BILLING_ENABLED=true
NEXT_PUBLIC_SPRING_BASE_URL=http://livecontext-app:8080
```

The explicit billing capability is intentional: authentication topology is not
a commercial-edition flag.

## Stripe objects and database wiring

Create LIVE-mode recurring prices for Starter, Pro, and Team in monthly and
yearly cadences, plus the three one-time PAYG prices:

| Database plan/cadence | Amount already expected by the database |
| --- | ---: |
| PAYG / payg_small | 10.00 |
| PAYG / payg_medium | 50.00 |
| PAYG / payg_large | 100.00 |

Before creating products, query the database for the authoritative subscription
amounts and currency:

```bash
docker compose exec -T postgres psql -U "$DB_USERNAME" -d livecontext -c "
SELECT pl.code, pr.cadence, pr.currency, pr.amount_cents
FROM auth.price pr
JOIN auth.plan pl ON pl.id = pr.plan_id
WHERE pl.code IN ('STARTER','PRO','TEAM','PAYG')
ORDER BY pl.code, pr.amount_cents, pr.cadence;"
```

Wire the returned LIVE Price IDs with the manual template. This overwrites the
historical TEST-mode PAYG IDs without deleting any price, subscription, or
ledger row:

```bash
docker compose exec -T postgres psql -U "$DB_USERNAME" -d livecontext   -v starter_monthly="$STRIPE_PRICE_STARTER_MONTHLY"   -v starter_yearly="$STRIPE_PRICE_STARTER_YEARLY"   -v pro_monthly="$STRIPE_PRICE_PRO_MONTHLY"   -v pro_yearly="$STRIPE_PRICE_PRO_YEARLY"   -v team_monthly="$STRIPE_PRICE_TEAM_MONTHLY"   -v team_yearly="$STRIPE_PRICE_TEAM_YEARLY"   -v payg_small="$STRIPE_PRICE_PAYG_SMALL"   -v payg_medium="$STRIPE_PRICE_PAYG_MEDIUM"   -v payg_large="$STRIPE_PRICE_PAYG_LARGE"   -f /path/to/trinyx_stripe_price_ids.template.sql
```

The template is
`backend/migration-service/src/main/resources/db/manual/trinyx_stripe_price_ids.template.sql`.
Price IDs are identifiers, not secrets, but production values remain deployment
configuration and are intentionally absent from Git.

Credit tier quantities continue to use the current subscription quantity/tier
logic; no replacement billing model or extra service is required.

## Webhook routing

Configure Stripe to send these existing event families to:

```text
https://app.trinyx.fr/webhooks/stripe
```

The route must reach `livecontext-app:8080/webhooks/stripe` with the original
method, body, and `Stripe-Signature` header. Do not send it through a Next.js
rewrite that changes the path or body. An exact Caddy rule is:

```caddyfile
handle /webhooks/stripe {
    reverse_proxy livecontext-app:8080
}
```

If Caddy runs on the host rather than the Compose network, use
`127.0.0.1:8080` as the upstream. This PR does not change production Caddy.

Enable at least: checkout session completion, customer subscription
created/updated/deleted, invoice paid/payment succeeded/payment failed,
subscription schedule events, charge refunded, dispute created, and customer
deleted.

## Staging deployment

1. Back up the unchanged database and record the current image:

   ```bash
   export ROLLBACK_BACKEND_IMAGE=ghcr.io/livecontext-ai/livecontext-ce:v0.2.12
   docker compose exec -T postgres pg_dump -U "$DB_USERNAME" -d livecontext      --format=custom > "trinyx-pre-paid-monolith-$(date +%Y%m%d%H%M%S).dump"
   ```

2. Pull the immutable candidate and validate the resolved Compose model:

   ```bash
   export BACKEND_IMAGE=ghcr.io/eddinerabii/trinyx-backend:<tested-sha>
   docker pull "$BACKEND_IMAGE"
   docker compose --env-file docker/.env.paid-monolith config --quiet
   ```

3. Start only the existing backend container. Flyway applies additive migrations
   against the current `livecontext` database:

   ```bash
   docker compose --env-file docker/.env.paid-monolith up -d --no-deps livecontext
   docker compose ps livecontext
   curl --fail http://127.0.0.1:8080/actuator/health
   ```

4. Wire LIVE Price IDs with the template, restart the backend to refresh price
   caches/health, then add the exact Caddy route and Stripe endpoint:

   ```bash
   docker compose --env-file docker/.env.paid-monolith restart livecontext
   curl --fail http://127.0.0.1:8080/actuator/health
   ```

5. In Stripe test mode first, exercise Free provisioning, monthly and yearly
   Checkout, portal, upgrade/downgrade, cancel/reactivate, renewal, each PAYG
   tier, partial/full refund, dispute, insufficient credits, LLM/chat/agent,
   workflow node, web tool, and image-generation usage. Compare every case to
   `auth.subscription`, `auth.credit_ledger`, and
   `auth.billing_event.status` before enabling LIVE Checkout.

## Rollback

Application rollback is image/config-only; V435 is additive and backward
compatible, so do not restore or downgrade the database.

```bash
export BACKEND_IMAGE=ghcr.io/livecontext-ai/livecontext-ce:v0.2.12
export APP_EDITION=ce
export BILLING_PROVIDER=none
export CREDIT_UNLIMITED=true
export PLAN_LIMITS_ENABLED=false
export WORKFLOW_NODE_BILLING_ENABLED=false
docker compose --env-file docker/.env.paid-monolith up -d --no-deps livecontext
curl --fail http://127.0.0.1:8080/actuator/health
```

Immediately disable new Checkout entry points during rollback. Stripe retains
failed webhook deliveries and retries them; do not delete events or ledger rows.
Before re-enabling paid mode, redeploy the tested paid-monolith image and let
`FAILED` events replay. Existing PostgreSQL, Redis, MinIO, bridge, volumes, and
network remain untouched.
