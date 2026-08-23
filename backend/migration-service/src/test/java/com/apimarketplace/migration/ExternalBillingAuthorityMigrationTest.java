package com.apimarketplace.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalBillingAuthorityMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V436__external_billing_authority.sql");

    @Test
    void createsSeparateIdentityEntitlementAndWalletStateWithoutReplacingNativeBilling() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("auth.cloud_identity_binding")
                .contains("auth.entitlement_projection")
                .contains("auth.entitlement_outbox")
                .contains("auth.cloud_credit_operation")
                .contains("auth.cloud_settlement_outbox")
                .contains("UNIQUE(operation_id, action, request_hash)");
        assertThat(sql).doesNotContain("DROP TABLE auth.subscription")
                .doesNotContain("DROP TABLE auth.credit_ledger")
                .doesNotContain("DROP COLUMN remaining_credits");
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
                .contains("next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now()")
                .contains("UNIQUE (aggregate_key, sequence)")
                .contains("late_settlement_until TIMESTAMPTZ");
    }
}
