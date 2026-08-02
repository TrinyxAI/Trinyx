package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run outlives a non-terminal stop: {@code stop_run(mode='graceful')} returns it to
 * WAITING_TRIGGER, and a cancelled run can be reactivated. Both keep the stop cause on
 * the run, so unless it is cleared when the run fires again, EVERY later report of a
 * fresh epoch shows {@code stop_reason} next to a COMPLETED status and an agent reads
 * "this new run was stopped". These tests pin the clear.
 */
@DisplayName("AgentRunStopService.clearStopMetadata - the cause is dropped when a run is revived")
class AgentRunStopClearMetadataTest {

    private WorkflowRunEntity runWithMetadata(Map<String, Object> metadata) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic("run-refire");
        run.setMetadata(metadata == null ? null : new HashMap<>(metadata));
        return run;
    }

    @Test
    @DisplayName("the stop cause of a previous stop is removed before the new epoch runs")
    void stopCauseIsCleared() {
        WorkflowRunEntity run = runWithMetadata(Map.of(
                AgentRunStopService.META_STOP_REASON, "the browser agent was stuck",
                AgentRunStopService.META_STOPPED_BY, "agent",
                AgentRunStopService.META_STOPPED_AT, "2026-07-30T10:15:30Z"));

        AgentRunStopService.clearStopMetadata(run);

        assertThat(run.getMetadata()).doesNotContainKeys(
                AgentRunStopService.META_STOP_REASON,
                AgentRunStopService.META_STOPPED_BY,
                AgentRunStopService.META_STOPPED_AT);
    }

    @Test
    @DisplayName("everything else on the run metadata survives the clear")
    void unrelatedMetadataSurvives() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(AgentRunStopService.META_STOP_REASON, "stopped");
        metadata.put("__editorRun__", true);
        metadata.put("lastCycleResult", "completed");
        WorkflowRunEntity run = runWithMetadata(metadata);

        AgentRunStopService.clearStopMetadata(run);

        assertThat(run.getMetadata())
                .containsEntry("__editorRun__", true)
                .containsEntry("lastCycleResult", "completed")
                .doesNotContainKey(AgentRunStopService.META_STOP_REASON);
    }

    @Test
    @DisplayName("a run nobody ever stopped is left untouched (same map instance, no rewrite)")
    void neverStoppedRunIsUntouched() {
        Map<String, Object> original = new HashMap<>(Map.of("__editorRun__", true));
        WorkflowRunEntity run = runWithMetadata(original);
        Map<String, Object> before = run.getMetadata();

        AgentRunStopService.clearStopMetadata(run);

        assertThat(run.getMetadata()).isSameAs(before);
    }

    @Test
    @DisplayName("a run with no metadata at all does not blow up")
    void nullMetadataIsSafe() {
        WorkflowRunEntity run = runWithMetadata(null);

        AgentRunStopService.clearStopMetadata(run);

        assertThat(run.getMetadata()).isNull();
    }
}
