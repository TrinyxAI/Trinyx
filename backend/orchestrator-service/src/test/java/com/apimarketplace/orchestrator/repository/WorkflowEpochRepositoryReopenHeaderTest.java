package com.apimarketplace.orchestrator.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * Pins the SQL of {@code reopenHeader} in BOTH dialects.
 *
 * <p>Reopening is what lets a rerun re-execute inside the epoch that holds its outputs, after a
 * cycle close already marked that epoch finished. The existing upsert cannot serve it: PostgreSQL's
 * {@code ON CONFLICT DO UPDATE} touches only {@code epoch_state} and {@code updated_at}, so
 * {@code is_active} and {@code closed_at} would keep their closed values and the epoch would
 * re-execute while reporting itself ended.
 *
 * <p>These assertions are on the statement text because that is the whole artifact: a wrong column
 * or a missed dialect is a silent no-op ({@code reopenHeader} matches no row), and every
 * higher-level test keeps passing against a mocked service.
 *
 * <p>The {@code started_at} assertions guard the invariant
 * {@link WorkflowEpochRepository#getLatestEpochStartedAtByRunIds} depends on, the same one
 * {@code H2WorkflowEpochRepositoryHeaderTest} was written for: {@code MAX(started_at)} per run is
 * the run's last FIRE time, and a rerun is not a fire. The epoch's execution clock lives in
 * {@code epoch_state.startedAt} instead, which the reopening caller re-stamps.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("reopenHeader SQL - both dialects")
class WorkflowEpochRepositoryReopenHeaderTest {

    @Mock private JdbcTemplate jdbcTemplate;

    private WorkflowEpochRepository postgres;
    private H2WorkflowEpochRepository h2;

    private static final String RUN_ID = "run-1";
    private static final String TRIGGER_ID = "trigger:webhook";
    private static final int EPOCH = 4;
    private static final String STATE_JSON = "{\"completedNodeIds\":[\"mcp:a\"]}";

    @BeforeEach
    void setUp() {
        postgres = new WorkflowEpochRepository(jdbcTemplate);
        h2 = new H2WorkflowEpochRepository(jdbcTemplate);
    }

    private String captureSql(Runnable call) {
        call.run();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(), any(), any(), any());
        return sql.getValue();
    }

    /**
     * The row this statement must hit, and only that row. A typo in any of these predicates is
     * the silent no-op the whole class exists to catch; dropping {@code entry_type} is worse
     * than a no-op, since it would rewrite every per-node COUNT row of the epoch.
     */
    private void assertTargetsTheHeaderRowOnly(String sql) {
        assertThat(sql).contains("UPDATE workflow_epochs");
        assertThat(sql).contains("run_id = ?");
        assertThat(sql).contains("trigger_id = ?");
        assertThat(sql).contains("epoch = ?");
        assertThat(sql)
                .as("without this predicate the UPDATE also hits the epoch's per-node count rows")
                .contains("entry_type = 'EPOCH_HEADER'");
    }

    @Test
    @DisplayName("PostgreSQL: revives the row and leaves started_at alone")
    void postgresReopenRevivesTheRow() {
        String sql = captureSql(() -> postgres.reopenHeader(RUN_ID, TRIGGER_ID, EPOCH, STATE_JSON));

        assertTargetsTheHeaderRowOnly(sql);
        assertThat(sql).contains("is_active = TRUE");
        assertThat(sql).contains("closed_at = NULL");
        assertThat(sql).contains("epoch_state = CAST(? AS JSONB)");
        assertThat(sql)
                .as("re-stamping started_at would make a rerun look like a fire in the run history")
                .doesNotContain("started_at =");
    }

    @Test
    @DisplayName("H2: same contract, dialect-appropriate")
    void h2ReopenRevivesTheRow() {
        String sql = captureSql(() -> h2.reopenHeader(RUN_ID, TRIGGER_ID, EPOCH, STATE_JSON));

        assertTargetsTheHeaderRowOnly(sql);
        assertThat(sql).contains("is_active = TRUE");
        assertThat(sql).contains("closed_at = NULL");
        assertThat(sql)
                .as("H2 has no JSONB cast")
                .contains("epoch_state = ?").doesNotContain("CAST(? AS JSONB)");
        assertThat(sql).contains("CURRENT_TIMESTAMP").doesNotContain("NOW()");
        assertThat(sql).doesNotContain("started_at =");
    }

    @Test
    @DisplayName("Both dialects bind the same parameter order: state, run, trigger, epoch")
    void bothDialectsBindTheSameParameterOrder() {
        postgres.reopenHeader(RUN_ID, TRIGGER_ID, EPOCH, STATE_JSON);
        verify(jdbcTemplate).update(any(String.class), eqState(), eqRun(), eqTrigger(), eqEpoch());

        h2.reopenHeader(RUN_ID, TRIGGER_ID, EPOCH, STATE_JSON);
        verify(jdbcTemplate, org.mockito.Mockito.times(2))
                .update(any(String.class), eqState(), eqRun(), eqTrigger(), eqEpoch());
    }

    @Test
    @DisplayName("A null trigger falls back to the sentinel id, as every other header write does")
    void nullTriggerFallsBackToSentinel() {
        postgres.reopenHeader(RUN_ID, null, EPOCH, STATE_JSON);

        verify(jdbcTemplate).update(any(String.class),
                org.mockito.ArgumentMatchers.eq(STATE_JSON),
                org.mockito.ArgumentMatchers.eq(RUN_ID),
                org.mockito.ArgumentMatchers.eq("trigger:default"),
                org.mockito.ArgumentMatchers.eq(EPOCH));
    }

    private static Object eqState() { return org.mockito.ArgumentMatchers.eq(STATE_JSON); }
    private static Object eqRun() { return org.mockito.ArgumentMatchers.eq(RUN_ID); }
    private static Object eqTrigger() { return org.mockito.ArgumentMatchers.eq(TRIGGER_ID); }
    private static Object eqEpoch() { return org.mockito.ArgumentMatchers.eq(EPOCH); }
}
