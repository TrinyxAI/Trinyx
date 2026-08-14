package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The claim path's half of the diagnosis: that a refusal is recorded for the node the caller
 * named, dropped when a claim succeeds, and never survives the run it describes.
 *
 * <p>The message itself, and every state it can name, is covered by
 * {@link ClaimRefusalRegistryTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Claim refusal, from the claim path")
class ClaimRefusalDiagnosticsTest {

    private static final String RUN_ID = "run-claim-1";
    private static final String TRIGGER = "trigger:start";

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher eventPublisher;
    @Mock private com.apimarketplace.common.storage.service.StorageBreakdownService breakdownService;
    @Mock private RunningNodeTracker runningNodeTracker;

    private StateSnapshotService service;
    private ClaimRefusalRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        var meterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        registry = new ClaimRefusalRegistry(runningNodeTracker);
        lenient().when(runningNodeTracker.getRunningCountsAcrossEpochs(anyString())).thenReturn(Map.of());
        service = new StateSnapshotService(runRepository, mapper, workflowEpochService, eventPublisher,
                breakdownService, new TxScopedSnapshotCache(runRepository, meterRegistry),
                new com.apimarketplace.orchestrator.metrics.WorkflowMetrics(meterRegistry), registry);
    }

    /** A run row whose snapshot is the given one, shaped as production shapes it (with dags). */
    private void runWith(StateSnapshot snapshot) throws Exception {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        lenient().when(run.getRunIdPublic()).thenReturn(RUN_ID);
        when(run.getStateSnapshot()).thenReturn(mapper.writeValueAsString(snapshot));
        when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(run));
    }

    @Test
    @DisplayName("A refused claim records the reason for the node that was asked about")
    void refusalIsRecordedForTheRequestedNode() throws Exception {
        runWith(StateSnapshot.empty().addReadyNode(TRIGGER, "mcp:b").markNodeCompleted(TRIGGER, "mcp:a"));

        assertThat(service.claimNodeForExecution(RUN_ID, "mcp:a")).isFalse();

        var refusal = service.lastClaimRefusal(RUN_ID, "mcp:a").orElseThrow();
        assertThat(refusal.nodeId()).isEqualTo("mcp:a");
        assertThat(refusal.state()).isEqualTo("completed");
    }

    @Test
    @DisplayName("The reason is not offered for a node other than the one it describes")
    void refusalIsScopedToItsNode() throws Exception {
        runWith(StateSnapshot.empty().addReadyNode(TRIGGER, "mcp:b").markNodeCompleted(TRIGGER, "mcp:a"));

        service.claimNodeForExecution(RUN_ID, "mcp:a");

        assertThat(service.lastClaimRefusal(RUN_ID, "mcp:other")).isEmpty();
    }

    @Test
    @DisplayName("Claiming a node successfully clears that node's own refusal")
    void successfulClaimClearsItsOwnRefusal() throws Exception {
        runWith(StateSnapshot.empty().addReadyNode(TRIGGER, "mcp:b"));
        when(runRepository.updateSnapshotAndSeq(eq(RUN_ID), anyString())).thenReturn(1);
        service.claimNodeForExecution(RUN_ID, "mcp:b");
        assertThat(service.lastClaimRefusal(RUN_ID, "mcp:b")).isEmpty();

        // Now refuse it, then let it succeed again: the stale reason must not survive.
        runWith(StateSnapshot.empty().addReadyNode(TRIGGER, "mcp:other"));
        service.claimNodeForExecution(RUN_ID, "mcp:b");
        assertThat(service.lastClaimRefusal(RUN_ID, "mcp:b")).isPresent();

        runWith(StateSnapshot.empty().addReadyNode(TRIGGER, "mcp:b"));
        assertThat(service.claimNodeForExecution(RUN_ID, "mcp:b")).isTrue();

        assertThat(service.lastClaimRefusal(RUN_ID, "mcp:b"))
                .as("a later 409 must not report the reason of an older one")
                .isEmpty();
    }

    @Test
    @DisplayName("A success on one node leaves another node's refusal intact")
    void successOnOneNodeDoesNotWipeAnother() throws Exception {
        runWith(StateSnapshot.empty().addReadyNode(TRIGGER, "mcp:b"));
        when(runRepository.updateSnapshotAndSeq(eq(RUN_ID), anyString())).thenReturn(1);
        service.claimNodeForExecution(RUN_ID, "mcp:a");
        assertThat(service.lastClaimRefusal(RUN_ID, "mcp:a")).isPresent();

        assertThat(service.claimNodeForExecution(RUN_ID, "mcp:b")).isTrue();

        assertThat(service.lastClaimRefusal(RUN_ID, "mcp:a"))
                .as("b succeeding says nothing about why a is refused; losing it would leave "
                        + "the caller that raced with b holding the generic sentence")
                .isPresent();
    }

    @Test
    @DisplayName("A vanished run drops its reason instead of answering with one computed earlier")
    void runNotFoundClearsTheStaleReason() throws Exception {
        runWith(StateSnapshot.empty().addReadyNode(TRIGGER, "mcp:b"));
        service.claimNodeForExecution(RUN_ID, "mcp:a");
        assertThat(service.lastClaimRefusal(RUN_ID, "mcp:a")).isPresent();

        // The run row is gone (deleted or archived between two clicks).
        when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.empty());

        assertThat(service.claimNodeForExecution(RUN_ID, "mcp:a")).isFalse();
        assertThat(service.lastClaimRefusal(RUN_ID, "mcp:a"))
                .as("the recorded reason describes a snapshot that no longer exists")
                .isEmpty();
    }
}
