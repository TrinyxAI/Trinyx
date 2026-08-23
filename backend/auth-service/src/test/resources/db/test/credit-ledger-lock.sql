CREATE SCHEMA IF NOT EXISTS auth;

CREATE TABLE auth.credit_ledger (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    executor_user_id BIGINT,
    organization_id VARCHAR(64),
    amount NUMERIC(15,4) NOT NULL,
    balance_after NUMERIC(15,4) NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_id VARCHAR(512),
    related_source_id VARCHAR(512),
    provider VARCHAR(50),
    model VARCHAR(100),
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    cached_tokens INTEGER,
    description VARCHAR(500),
    pin_id BIGINT,
    expires_at TIMESTAMP,
    payg_portion NUMERIC(15,4) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX uq_credit_ledger_source_id
    ON auth.credit_ledger (source_id)
    WHERE source_id IS NOT NULL;
