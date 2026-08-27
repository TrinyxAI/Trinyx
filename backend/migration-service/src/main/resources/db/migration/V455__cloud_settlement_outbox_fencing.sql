-- Strong claim ownership for the Cloud settlement relay.
--
-- A PROCESSING status alone cannot identify which worker owns a reclaimed row.
-- Every claim now receives a new token and all result/retry writes CAS on it.
ALTER TABLE auth.cloud_settlement_outbox
    ADD COLUMN IF NOT EXISTS claim_token UUID;

CREATE INDEX IF NOT EXISTS idx_cloud_settlement_outbox_processing_claim
    ON auth.cloud_settlement_outbox (id, claim_token)
    WHERE status = 'PROCESSING';
