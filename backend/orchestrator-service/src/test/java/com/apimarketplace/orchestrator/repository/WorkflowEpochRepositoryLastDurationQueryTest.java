package com.apimarketplace.orchestrator.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WorkflowEpochRepository#getLatestEpochDurationByRunIds}.
 *
 * <p>This is the figure the run history shows for a run that has not terminated,
 * and the SQL shape is what keeps it honest. Two properties matter more than the
 * happy path:
 * <ul>
 *   <li>it must pick the LATEST epoch, not an arbitrary one - hence
 *       {@code DISTINCT ON (run_id) … ORDER BY run_id, started_at DESC};</li>
 *   <li>it must ignore epochs still executing - {@code duration_ms} is written
 *       when the epoch closes, so a NULL means "no settled figure", not zero.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowEpochRepository.getLatestEpochDurationByRunIds")
class WorkflowEpochRepositoryLastDurationQueryTest {

    @Mock private JdbcTemplate jdbcTemplate;
    private WorkflowEpochRepository repo;

    @BeforeEach
    void setUp() {
        repo = new WorkflowEpochRepository(jdbcTemplate);
    }

    @Test
    @DisplayName("Empty input: returns empty map without hitting the database")
    void emptyInputShortCircuits() {
        assertThat(repo.getLatestEpochDurationByRunIds(List.of())).isEmpty();
        verify(jdbcTemplate, never()).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }

    @Test
    @DisplayName("Null input: returns empty map without NPE")
    void nullInputShortCircuits() {
        assertThat(repo.getLatestEpochDurationByRunIds(null)).isEmpty();
        verify(jdbcTemplate, never()).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }

    @Test
    @DisplayName("SQL takes the LATEST epoch per run, only closed ones, binding runIds in order")
    void sqlShapeIsCorrect() {
        repo.getLatestEpochDurationByRunIds(List.of("run-A", "run-B"));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowCallbackHandler.class), argsCaptor.capture());

        String sql = sqlCaptor.getValue();
        // One row per run, and it is the most recent epoch - without this ordering
        // the column would report whichever epoch the planner happened to return.
        assertThat(sql).contains("DISTINCT ON (run_id)");
        assertThat(sql).contains("ORDER BY run_id, started_at DESC");
        assertThat(sql).contains("entry_type = 'EPOCH_HEADER'");
        // An epoch still executing has no duration yet; showing it as 0 would read
        // as "instant", which is the opposite of the truth.
        assertThat(sql).contains("duration_ms IS NOT NULL");
        assertThat(sql).contains("run_id IN (?, ?)");

        assertThat(argsCaptor.getValue()).containsExactly("run-A", "run-B");
    }

    @Test
    @DisplayName("Populated row: duration lands in the map keyed by run_id")
    void populatedRowMapped() throws Exception {
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        when(rs.getObject("duration_ms")).thenReturn(7000L);
        when(rs.getLong("duration_ms")).thenReturn(7000L);
        when(rs.getString("run_id")).thenReturn("run-A");
        doAnswer(invocation -> {
            RowCallbackHandler rch = invocation.getArgument(1);
            rch.processRow(rs);
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        assertThat(repo.getLatestEpochDurationByRunIds(List.of("run-A"))).containsEntry("run-A", 7000L);
    }

    @Test
    @DisplayName("NULL duration row is skipped rather than mapped to 0")
    void nullDurationSkipped() throws Exception {
        // getLong() returns 0 for SQL NULL, so reading it without the getObject
        // guard would publish a run whose last epoch took "no time at all".
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        when(rs.getObject("duration_ms")).thenReturn(null);
        doAnswer(invocation -> {
            RowCallbackHandler rch = invocation.getArgument(1);
            rch.processRow(rs);
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        assertThat(repo.getLatestEpochDurationByRunIds(List.of("run-A"))).isEmpty();
    }
}
