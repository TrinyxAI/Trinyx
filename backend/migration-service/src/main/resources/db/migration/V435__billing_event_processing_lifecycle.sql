-- A Stripe event is idempotent only after its dispatch completes successfully.
-- Existing rows predate lifecycle tracking and represent events the old handler
-- acknowledged, so they are conservatively marked PROCESSED.
ALTER TABLE auth.billing_event
    ADD COLUMN IF NOT EXISTS status VARCHAR(24) NOT NULL DEFAULT 'PROCESSED',
    ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS processed_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS last_error VARCHAR(2000);

UPDATE auth.billing_event
SET processed_at = COALESCE(processed_at, received_at)
WHERE status = 'PROCESSED';

ALTER TABLE auth.billing_event
    ALTER COLUMN status SET DEFAULT 'RECEIVED',
    ALTER COLUMN attempt_count SET DEFAULT 0;

ALTER TABLE auth.billing_event
    ADD CONSTRAINT chk_billing_event_status
        CHECK (status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED'));

CREATE INDEX IF NOT EXISTS idx_be_status_received
    ON auth.billing_event (status, received_at);
