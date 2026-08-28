package com.apimarketplace.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalBillingAuthorityMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V453_1__external_billing_authority.sql");

    @Test
    void createsSeparateIdentityEntitlementAndWalletStateWithoutReplacingNativeBilling() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("auth.cloud_identity_binding")
                .contains("auth.entitlement_projection")
                .contains("auth.entitlement_outbox")
                .contains("auth.identity_binding_outbox")
                .contains("auth.cloud_credit_operation")
                .contains("authority_payer_user_id BIGINT REFERENCES auth.users(id)")
                .contains("billing_status_changed_at TIMESTAMP WITHOUT TIME ZONE")
                .contains("origin_service_id VARCHAR(64)")
                .contains("ALTER COLUMN billing_status_changed_at SET NOT NULL")
                .contains("auth.cloud_settlement_outbox")
                .contains("UNIQUE(operation_id, action, request_hash)");
        assertThat(sql).contains("gen_random_uuid()")
                .doesNotContain("md5('trinyx-principal:'")
                .doesNotContain("md5('trinyx-billing:'")
                .doesNotContain("DROP TABLE auth.subscription")
                .doesNotContain("DROP TABLE auth.credit_ledger")
                .doesNotContain("DROP COLUMN remaining_credits")
                .doesNotContain("payer_user_id BIGINT NOT NULL");
    }

    @Test
    void projectionAndBindingConstraintsEnforceReplayAndTombstoneState() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("assertion_jti UUID NOT NULL UNIQUE")
                .contains("event_id UUID NOT NULL UNIQUE")
                .contains("CHECK (binding_revision > 0)")
                .contains("CHECK (sequence > 0)")
                .contains("'REVOKED'")
                .contains("uq_cloud_binding_active_scope")
                .contains("uq_entitlement_projection_scope");
    }

    @Test
    void operationIdAndOutboxesAreIdempotentAndRetryable() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("operation_id UUID PRIMARY KEY")
                .contains("request_hash CHAR(64) NOT NULL")
                .contains("settlement_hash CHAR(64)")
                .contains("'DISPATCHING'")
                .contains("next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now()")
                .contains("UNIQUE (aggregate_key, sequence)")
                .contains("UNIQUE (aggregate_key, binding_revision)")
                .contains("late_settlement_until TIMESTAMPTZ")
                .contains("prompt_tokens BIGINT")
                .contains("completion_tokens BIGINT")
                .contains("cache_creation_tokens BIGINT")
                .contains("cache_read_tokens BIGINT")
                .contains("cached_tokens BIGINT")
                .contains("reasoning_tokens BIGINT")
                .contains("'DEAD'")
                .contains("terminal_at TIMESTAMPTZ");
    }
}
