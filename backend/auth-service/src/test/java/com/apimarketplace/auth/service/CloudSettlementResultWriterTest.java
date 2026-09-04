package com.apimarketplace.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CloudSettlementResultWriterTest {

    @Test
    void deliveredAndDeadEachUseOneTransactionalBoundaryForBothRows() throws Exception {
        FakeJdbc jdbc = new FakeJdbc();
        CloudSettlementResultWriter writer = new CloudSettlementResultWriter(jdbc);
        UUID outbox = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();
        jdbc.currentClaimToken = claimToken;

        writer.delivered(outbox, operation, "a".repeat(64), claimToken,
                "COMMITTED", "{}");
        assertThat(jdbc.sql).hasSize(3);
        assertThat(jdbc.sql.get(0)).contains("status='DELIVERED'", "claim_token=?");
        assertThat(jdbc.sql.get(1)).contains("action='OUTCOME_UNKNOWN'");
        assertThat(jdbc.sql.get(2)).contains("cloud_credit_operation");

        jdbc.sql.clear();
        jdbc.currentClaimToken = claimToken;
        writer.dead(outbox, operation, claimToken, 2, "permanent");
        assertThat(jdbc.sql).hasSize(2);
        assertThat(jdbc.sql.get(0)).contains("status='DEAD'", "claim_token=?");
        assertThat(jdbc.sql.get(1)).contains("SETTLEMENT_FAILED");

        assertThat(CloudSettlementResultWriter.class
                .getDeclaredMethod("delivered", UUID.class, UUID.class, String.class,
                        UUID.class, String.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(CloudSettlementResultWriter.class
                .getDeclaredMethod("dead", UUID.class, UUID.class, UUID.class,
                        int.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void staleUnknownDeliveryCannotOverwriteCommittedProjection() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.operationState = "COMMITTED";
        UUID token = UUID.randomUUID();
        jdbc.currentClaimToken = token;
        CloudSettlementResultWriter writer = new CloudSettlementResultWriter(jdbc);

        writer.delivered(UUID.randomUUID(), UUID.randomUUID(),
                "b".repeat(64), token, "OUTCOME_UNKNOWN", "{}");

        assertThat(jdbc.operationState).isEqualTo("COMMITTED");
        assertThat(jdbc.sql).hasSize(2);
        assertThat(jdbc.sql.get(1))
                .contains("state NOT IN ('COMMITTED','COMMITTED_DELINQUENT','RELEASED','SETTLEMENT_FAILED')");
    }


    @Test
    void staleUnknownDeliveryCannotOverwriteSettlementFailedProjection() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.operationState = "SETTLEMENT_FAILED";
        UUID token = UUID.randomUUID();
        jdbc.currentClaimToken = token;
        CloudSettlementResultWriter writer = new CloudSettlementResultWriter(jdbc);

        writer.delivered(UUID.randomUUID(), UUID.randomUUID(),
                "f".repeat(64), token, "OUTCOME_UNKNOWN", "{}");

        assertThat(jdbc.operationState).isEqualTo("SETTLEMENT_FAILED");
        assertThat(jdbc.sql.get(1)).contains(
                "state NOT IN ('COMMITTED','COMMITTED_DELINQUENT','RELEASED','SETTLEMENT_FAILED')");
    }


    @Test
    void authoritativeTerminalMayResolveSettlementFailedProjection() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.operationState = "SETTLEMENT_FAILED";
        UUID token = UUID.randomUUID();
        jdbc.currentClaimToken = token;
        CloudSettlementResultWriter writer = new CloudSettlementResultWriter(jdbc);

        writer.delivered(UUID.randomUUID(), UUID.randomUUID(),
                "g".repeat(64), token, "COMMITTED", "{}");

        assertThat(jdbc.operationState).isEqualTo("COMMITTED");
    }

    @Test
    void reclaimedRowFencesOutStaleWorkerAndCurrentOwnerAloneCanComplete() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.operationState = "OUTCOME_UNKNOWN";
        UUID staleToken = UUID.randomUUID();
        UUID currentToken = UUID.randomUUID();
        jdbc.currentClaimToken = currentToken;
        CloudSettlementResultWriter writer = new CloudSettlementResultWriter(jdbc);
        UUID outbox = UUID.randomUUID();
        UUID operation = UUID.randomUUID();

        writer.delivered(outbox, operation, "c".repeat(64), staleToken,
                "COMMITTED", "{}");

        assertThat(jdbc.sql).hasSize(1);
        assertThat(jdbc.operationState).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(jdbc.currentClaimToken).isEqualTo(currentToken);

        jdbc.sql.clear();
        writer.delivered(outbox, operation, "c".repeat(64), currentToken,
                "COMMITTED", "{}");

        assertThat(jdbc.operationState).isEqualTo("COMMITTED");
        assertThat(jdbc.currentClaimToken).isNull();
    }

    @Test
    void terminalCommitMaySupersedeStaleUnknownIntent() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.operationState = "OUTCOME_UNKNOWN";
        UUID token = UUID.randomUUID();
        jdbc.currentClaimToken = token;
        CloudSettlementResultWriter writer = new CloudSettlementResultWriter(jdbc);

        writer.delivered(UUID.randomUUID(), UUID.randomUUID(),
                "d".repeat(64), token, "COMMITTED", "{}");

        assertThat(jdbc.sql).hasSize(3);
        assertThat(jdbc.sql.get(1))
                .contains("action='OUTCOME_UNKNOWN'", "request_hash=?",
                        "status IN ('PENDING','PROCESSING','FAILED')");
        assertThat(jdbc.operationState).isEqualTo("COMMITTED");
    }

    private static final class FakeJdbc extends JdbcTemplate {
        final List<String> sql = new ArrayList<>();
        UUID currentClaimToken;
        String operationState = "DISPATCHING";

        @Override
        public int update(String statement, Object... args) {
            sql.add(statement);
            if (statement.contains("WHERE id=? AND status='PROCESSING'")
                    && statement.contains("claim_token=?")) {
                UUID supplied = (UUID) args[args.length - 1];
                if (currentClaimToken == null || !currentClaimToken.equals(supplied)) {
                    return 0;
                }
                currentClaimToken = null;
                return 1;
            }
            if (statement.contains("UPDATE auth.cloud_credit_operation")
                    && statement.contains("SET state=?")) {
                String incoming = (String) args[0];
                if (statement.contains("state NOT IN")
                        && !operationState.equals(incoming)
                        && (terminal(operationState)
                            || ("SETTLEMENT_FAILED".equals(operationState)
                                && !terminal(incoming)))) {
                    return 0;
                }
                operationState = incoming;
            } else if (statement.contains("SET state='SETTLEMENT_FAILED'")
                    && !terminal(operationState)) {
                operationState = "SETTLEMENT_FAILED";
            }
            return 1;
        }

        private static boolean terminal(String state) {
            return "COMMITTED".equals(state)
                    || "COMMITTED_DELINQUENT".equals(state)
                    || "RELEASED".equals(state);
        }
    }
}
