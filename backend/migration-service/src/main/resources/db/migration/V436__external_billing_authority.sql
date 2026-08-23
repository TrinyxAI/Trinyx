-- Trinyx Cloud external billing authority (schema v2).
-- Backward-compatible: native Cloud billing tables remain untouched.
ALTER TABLE auth.users ADD COLUMN IF NOT EXISTS principal_id UUID;
ALTER TABLE auth.users ADD COLUMN IF NOT EXISTS billing_subject_id UUID;

UPDATE auth.users
SET principal_id = COALESCE(principal_id, md5('trinyx-principal:' || id::text)::uuid),
    billing_subject_id = COALESCE(billing_subject_id, md5('trinyx-billing:' || id::text)::uuid)
WHERE principal_id IS NULL OR billing_subject_id IS NULL;

ALTER TABLE auth.users ALTER COLUMN principal_id SET NOT NULL;
ALTER TABLE auth.users ALTER COLUMN billing_subject_id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_principal_id ON auth.users(principal_id);
CREATE INDEX IF NOT EXISTS idx_users_billing_subject_id ON auth.users(billing_subject_id);

CREATE TABLE IF NOT EXISTS auth.cloud_identity_binding (
    id UUID PRIMARY KEY,
    schema_version INTEGER NOT NULL DEFAULT 2 CHECK (schema_version = 2),
    issuer VARCHAR(255) NOT NULL,
    audience VARCHAR(100) NOT NULL,
    install_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    principal_id UUID NOT NULL,
    billing_subject_id UUID NOT NULL,
    keycloak_subject VARCHAR(255) NOT NULL,
    cloud_user_id BIGINT NOT NULL REFERENCES auth.users(id),
    binding_revision BIGINT NOT NULL CHECK (binding_revision > 0),
    assertion_jti UUID NOT NULL UNIQUE,
    assertion_jws TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    issued_at TIMESTAMPTZ NOT NULL,
    not_before TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_cloud_binding_active_scope
    ON auth.cloud_identity_binding(issuer, install_id, organization_id, principal_id)
    WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX IF NOT EXISTS uq_cloud_binding_active_subject
    ON auth.cloud_identity_binding(issuer, install_id, keycloak_subject)
    WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_cloud_binding_lookup
    ON auth.cloud_identity_binding(keycloak_subject, status, expires_at);

CREATE TABLE IF NOT EXISTS auth.entitlement_projection (
    projection_id UUID PRIMARY KEY,
    schema_version INTEGER NOT NULL DEFAULT 2 CHECK (schema_version = 2),
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    issuer VARCHAR(255) NOT NULL,
    audience VARCHAR(100) NOT NULL,
    install_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    billing_subject_id UUID NOT NULL,
    access_state VARCHAR(16) NOT NULL CHECK (access_state IN ('ACTIVE', 'GRACE', 'DENIED', 'REVOKED')),
    state_hash CHAR(64) NOT NULL,
    event_id UUID NOT NULL UNIQUE,
    signed_jws TEXT NOT NULL,
    canonical_payload JSONB NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    not_before TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_entitlement_projection_scope
        UNIQUE (issuer, install_id, organization_id, billing_subject_id)
);
CREATE INDEX IF NOT EXISTS idx_entitlement_projection_expiry
    ON auth.entitlement_projection(access_state, expires_at);

CREATE TABLE IF NOT EXISTS auth.identity_binding_authority_state (
    id UUID PRIMARY KEY,
    install_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    principal_id UUID NOT NULL,
    billing_subject_id UUID NOT NULL,
    keycloak_subject VARCHAR(255) NOT NULL,
    binding_revision BIGINT NOT NULL CHECK (binding_revision > 0),
    assertion_jti UUID NOT NULL UNIQUE,
    assertion_jws TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','REVOKED')),
    expires_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (install_id, organization_id, principal_id)
);

CREATE TABLE IF NOT EXISTS auth.entitlement_authority_state (
    projection_id UUID PRIMARY KEY,
    issuer VARCHAR(255) NOT NULL,
    install_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    billing_subject_id UUID NOT NULL,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    access_state VARCHAR(16) NOT NULL CHECK (access_state IN ('ACTIVE','GRACE','DENIED','REVOKED')),
    canonical_payload JSONB NOT NULL,
    signed_jws TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (issuer, install_id, organization_id, billing_subject_id)
);

CREATE TABLE IF NOT EXISTS auth.entitlement_outbox (
    event_id UUID PRIMARY KEY,
    aggregate_key VARCHAR(512) NOT NULL,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('UPSERT', 'REVOKE', 'REFRESH')),
    signed_jws TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processing_started_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    last_error VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (aggregate_key, sequence)
);
CREATE INDEX IF NOT EXISTS idx_entitlement_outbox_dispatch
    ON auth.entitlement_outbox(status, next_attempt_at);

CREATE TABLE IF NOT EXISTS auth.cloud_credit_operation (
    operation_id UUID PRIMARY KEY,
    reservation_id UUID,
    request_hash CHAR(64) NOT NULL,
    settlement_hash CHAR(64),
    principal_id UUID NOT NULL,
    billing_subject_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    install_id UUID NOT NULL,
    entitlement_sequence BIGINT NOT NULL CHECK (entitlement_sequence > 0),
    source_type VARCHAR(64) NOT NULL,
    estimated_credits NUMERIC(19,6) NOT NULL CHECK (estimated_credits >= 0),
    maximum_credits NUMERIC(19,6) NOT NULL CHECK (maximum_credits > 0),
    actual_credits NUMERIC(19,6),
    provider VARCHAR(64),
    model VARCHAR(255),
    provider_request_id VARCHAR(255),
    state VARCHAR(24) NOT NULL
        CHECK (state IN ('RESERVED', 'COMMITTED', 'COMMITTED_DELINQUENT', 'RELEASED', 'EXPIRED')),
    response_payload JSONB,
    expires_at TIMESTAMPTZ,
    late_settlement_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_cloud_credit_reservation_id
    ON auth.cloud_credit_operation(reservation_id) WHERE reservation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_cloud_credit_operation_expiry
    ON auth.cloud_credit_operation(state, expires_at);

CREATE TABLE IF NOT EXISTS auth.cloud_settlement_outbox (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES auth.cloud_credit_operation(operation_id),
    action VARCHAR(16) NOT NULL CHECK (action IN ('COMMIT', 'RELEASE')),
    request_hash CHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ,
    UNIQUE(operation_id, action, request_hash)
);
CREATE INDEX IF NOT EXISTS idx_cloud_settlement_outbox_dispatch
    ON auth.cloud_settlement_outbox(status, next_attempt_at);
