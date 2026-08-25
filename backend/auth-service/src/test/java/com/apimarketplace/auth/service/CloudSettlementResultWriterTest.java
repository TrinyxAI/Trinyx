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

        writer.delivered(outbox, operation, "COMMITTED", "{}");
        assertThat(jdbc.sql).hasSize(3);
        assertThat(jdbc.sql.get(0)).contains("status='DELIVERED'");
        assertThat(jdbc.sql.get(1)).contains("action='OUTCOME_UNKNOWN'");
        assertThat(jdbc.sql.get(2)).contains("cloud_credit_operation");

        jdbc.sql.clear();
        writer.dead(outbox, operation, 2, "permanent");
        assertThat(jdbc.sql).hasSize(2);
        assertThat(jdbc.sql.get(0)).contains("status='DEAD'");
        assertThat(jdbc.sql.get(1)).contains("SETTLEMENT_FAILED");

        assertThat(CloudSettlementResultWriter.class
                .getDeclaredMethod("delivered", UUID.class, UUID.class, String.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(CloudSettlementResultWriter.class
                .getDeclaredMethod("dead", UUID.class, UUID.class, int.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void staleUnknownDeliveryCannotOverwriteCommittedProjection() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.operationState = "COMMITTED";
        CloudSettlementResultWriter writer = new CloudSettlementResultWriter(jdbc);

        writer.delivered(UUID.randomUUID(), UUID.randomUUID(), "OUTCOME_UNKNOWN", "{}");

        assertThat(jdbc.operationState).isEqualTo("COMMITTED");
        assertThat(jdbc.sql).hasSize(2);
        assertThat(jdbc.sql.get(1))
                .contains("state NOT IN ('COMMITTED','COMMITTED_DELINQUENT','RELEASED')");
    }

    @Test
    void cancelledProcessingRowCannotRewriteOperationState() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.operationState = "OUTCOME_UNKNOWN";
        jdbc.claimResult = 0;
        CloudSettlementResultWriter writer = new CloudSettlementResultWriter(jdbc);

        writer.delivered(UUID.randomUUID(), UUID.randomUUID(), "COMMITTED", "{}");

        assertThat(jdbc.sql).hasSize(1);
        assertThat(jdbc.operationState).isEqualTo("OUTCOME_UNKNOWN");
    }

    @Test
    void terminalCommitMaySupersedeStaleUnknownIntent() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.operationState = "OUTCOME_UNKNOWN";
        CloudSettlementResultWriter writer = new CloudSettlementResultWriter(jdbc);

        writer.delivered(UUID.randomUUID(), UUID.randomUUID(), "COMMITTED", "{}");

        assertThat(jdbc.sql).hasSize(3);
        assertThat(jdbc.sql.get(1))
                .contains("action='OUTCOME_UNKNOWN'", "status IN ('PENDING','PROCESSING','FAILED')");
        assertThat(jdbc.operationState).isEqualTo("COMMITTED");
    }

    private static final class FakeJdbc extends JdbcTemplate {
        final List<String> sql = new ArrayList<>();
        int claimResult = 1;
        String operationState = "DISPATCHING";

        @Override
        public int update(String statement, Object... args) {
            sql.add(statement);
            if (statement.contains("WHERE id=? AND status='PROCESSING'")) {
                return claimResult;
            }
            if (statement.contains("UPDATE auth.cloud_credit_operation")
                    && statement.contains("SET state=?")) {
                String incoming = (String) args[0];
                if (statement.contains("state NOT IN")
                        && terminal(operationState)
                        && !operationState.equals(incoming)) {
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
