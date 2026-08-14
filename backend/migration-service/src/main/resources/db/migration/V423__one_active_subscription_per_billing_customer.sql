-- One active subscription per billing customer.
--
-- WHY
-- `UserResolutionService.ensureFreeSubscription` was a check-then-act with nothing between the
-- "does an active subscription exist?" read and the insert: no lock, no constraint. It runs from
-- `resolveUser`, i.e. on EVERY gateway request, so on a first login (cold gateway cache, several
-- concurrent app requests, 2 auth replicas) more than one request could pass the check and each
-- insert a subscription. Production carries three such pairs, created 7-15 ms apart:
--   user 35 -> subs 18 + 19, user 57 -> subs 32 + 33, user 65 -> subs 38 + 39.
-- Only one of each pair ever received its `_init` credit grant; the twin is a zero-balance ghost.
-- The damage is downstream: credit paths resolve the wallet by USER (most-recent active row)
-- while the renewal iterates ROWS, so a grant keyed to one row lands on the other and the
-- sibling's reset then wipes it.
--
-- The application fix is a per-user lock (FreeSubscriptionProvisioner locks the billing_customer
-- row, which is unique per user, so it serialises across replicas). This migration is the
-- database backstop, and it also repairs the rows that already exist.

-- 1) Repair. Keep the row the application already treats as the wallet - the most recent
--    `created_at`, matching `findActiveByUserIdForUpdate`'s ORDER BY - and fold the losers'
--    balances into it so no credit is destroyed by the cleanup. Mirrors how
--    SubscriptionService carries PAYG credits over when it cancels a sibling.
WITH ranked AS (
    SELECT id,
           billing_customer_id,
           row_number() OVER (
               PARTITION BY billing_customer_id
               ORDER BY created_at DESC, id DESC
           ) AS rn
    FROM auth.subscription
    WHERE status IN ('active', 'trialing')
),
losers AS (
    SELECT id, billing_customer_id FROM ranked WHERE rn > 1
),
carry AS (
    SELECT l.billing_customer_id,
           COALESCE(SUM(s.remaining_credits), 0)      AS sub_credits,
           COALESCE(SUM(s.payg_remaining_credits), 0) AS payg_credits
    FROM losers l
    JOIN auth.subscription s ON s.id = l.id
    GROUP BY l.billing_customer_id
)
UPDATE auth.subscription w
SET remaining_credits      = w.remaining_credits + c.sub_credits,
    payg_remaining_credits = w.payg_remaining_credits + c.payg_credits,
    updated_at             = now()
FROM carry c, ranked r
WHERE r.rn = 1
  AND r.billing_customer_id = c.billing_customer_id
  AND w.id = r.id
  AND (c.sub_credits <> 0 OR c.payg_credits <> 0);

-- 2) Retire the losers. 'canceled' is the vocabulary the rest of the code already uses for a
--    superseded row (SubscriptionService sibling-cancel), so nothing new has to learn a status.
WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY billing_customer_id
               ORDER BY created_at DESC, id DESC
           ) AS rn
    FROM auth.subscription
    WHERE status IN ('active', 'trialing')
)
UPDATE auth.subscription s
SET status                 = 'canceled',
    cancel_at_period_end   = true,
    remaining_credits      = 0,
    payg_remaining_credits = 0,
    updated_at             = now()
FROM ranked r
WHERE s.id = r.id
  AND r.rn > 1;

-- 3) Backstop. Partial unique index so the race can never re-create the shape, whatever future
--    code path forgets the lock. Scoped to the statuses the application treats as "the current
--    subscription" (findActiveByUserId / findExpiredInternalSubscriptions both use this set).
CREATE UNIQUE INDEX IF NOT EXISTS idx_subscription_one_active_per_customer
    ON auth.subscription (billing_customer_id)
    WHERE status IN ('active', 'trialing');
