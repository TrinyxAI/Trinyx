-- Durable physical object erasure for workspace/account purge.
--
-- Storage metadata may be deleted only after the immutable tenant/key tuple is
-- recorded here. Object-store I/O is retried outside the purge transaction.
CREATE TABLE IF NOT EXISTS auth.workspace_storage_erasure_outbox (
    id UUID PRIMARY KEY,
    organization_id VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    storage_key TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','PROCESSING','FAILED','DELIVERED')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claim_token UUID,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ,
    CONSTRAINT uq_workspace_storage_erasure
        UNIQUE (organization_id, tenant_id, storage_key)
);

CREATE INDEX IF NOT EXISTS idx_workspace_storage_erasure_due
    ON auth.workspace_storage_erasure_outbox (status, next_attempt_at)
    WHERE status IN ('PENDING','FAILED','PROCESSING');
