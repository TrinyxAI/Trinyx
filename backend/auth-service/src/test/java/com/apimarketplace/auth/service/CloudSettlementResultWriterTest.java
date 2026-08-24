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
        assertThat(jdbc.sql).hasSize(2);
        assertThat(jdbc.sql.get(0)).contains("status='DELIVERED'");
        assertThat(jdbc.sql.get(1)).contains("cloud_credit_operation");

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

    private static final class FakeJdbc extends JdbcTemplate {
        final List<String> sql = new ArrayList<>();
        @Override public int update(String statement, Object... args) {
            sql.add(statement);
            return 1;
        }
    }
}
