-- Manual production template; NOT a Flyway migration.
-- Run with psql -v ... after creating the matching Trinyx LIVE prices.
-- No real Price ID belongs in source control.
\set ON_ERROR_STOP on

BEGIN;

UPDATE auth.price pr
SET provider_price_id = :'starter_monthly', provider = 'stripe'
FROM auth.plan pl
WHERE pr.plan_id = pl.id AND pl.code = 'STARTER' AND pr.cadence = 'monthly';

UPDATE auth.price pr
SET provider_price_id = :'starter_yearly', provider = 'stripe'
FROM auth.plan pl
WHERE pr.plan_id = pl.id AND pl.code = 'STARTER' AND pr.cadence = 'yearly';

UPDATE auth.price pr
SET provider_price_id = :'pro_monthly', provider = 'stripe'
FROM auth.plan pl
WHERE pr.plan_id = pl.id AND pl.code = 'PRO' AND pr.cadence = 'monthly';

UPDATE auth.price pr
SET provider_price_id = :'pro_yearly', provider = 'stripe'
FROM auth.plan pl
WHERE pr.plan_id = pl.id AND pl.code = 'PRO' AND pr.cadence = 'yearly';

UPDATE auth.price pr
SET provider_price_id = :'team_monthly', provider = 'stripe'
FROM auth.plan pl
WHERE pr.plan_id = pl.id AND pl.code = 'TEAM' AND pr.cadence = 'monthly';

UPDATE auth.price pr
SET provider_price_id = :'team_yearly', provider = 'stripe'
FROM auth.plan pl
WHERE pr.plan_id = pl.id AND pl.code = 'TEAM' AND pr.cadence = 'yearly';

UPDATE auth.price pr
SET provider_price_id = :'credit_pack_monthly', provider = 'stripe'
FROM auth.plan pl
WHERE pr.plan_id = pl.id AND pl.code = 'CREDIT_PACK' AND pr.cadence = 'monthly';

UPDATE auth.price pr
SET provider_price_id = :'credit_pack_yearly', provider = 'stripe'
FROM auth.plan pl
WHERE pr.plan_id = pl.id AND pl.code = 'CREDIT_PACK' AND pr.cadence = 'yearly';

UPDATE auth.price pr
SET provider_price_id = :'payg_small', provider = 'stripe'
FROM auth.plan pl
WHERE pr.plan_id = pl.id AND pl.code = 'PAYG' AND pr.cadence = 'payg_small';

UPDATE auth.price pr
SET provider_price_id = :'payg_medium', provider = 'stripe'
FROM auth.plan pl
WHERE pr.plan_id = pl.id AND pl.code = 'PAYG' AND pr.cadence = 'payg_medium';

UPDATE auth.price pr
SET provider_price_id = :'payg_large', provider = 'stripe'
FROM auth.plan pl
WHERE pr.plan_id = pl.id AND pl.code = 'PAYG' AND pr.cadence = 'payg_large';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM auth.price pr
        JOIN auth.plan pl ON pl.id = pr.plan_id
        WHERE (
            (pl.code IN ('STARTER', 'PRO', 'TEAM', 'CREDIT_PACK') AND pr.cadence IN ('monthly', 'yearly'))
            OR (pl.code = 'PAYG' AND pr.cadence IN ('payg_small', 'payg_medium', 'payg_large'))
        )
        AND (pr.provider_price_id IS NULL OR pr.provider_price_id NOT LIKE 'price\_%')
    ) THEN
        RAISE EXCEPTION 'One or more Trinyx Stripe Price IDs are missing or invalid';
    END IF;
END $$;

COMMIT;

SELECT pl.code, pr.cadence, pr.currency, pr.amount_cents, pr.provider_price_id
FROM auth.price pr
JOIN auth.plan pl ON pl.id = pr.plan_id
WHERE pl.code IN ('STARTER', 'PRO', 'TEAM', 'CREDIT_PACK', 'PAYG')
ORDER BY pl.code, pr.amount_cents, pr.cadence;
