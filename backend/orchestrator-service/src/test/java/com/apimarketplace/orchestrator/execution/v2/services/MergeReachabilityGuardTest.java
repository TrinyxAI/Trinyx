package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the prod defect where a merge node ran on an item that no incoming
 * branch had reached (run_<id> epoch 152: agent:draft_reply drafted a reply
 * to a mail routed to the delete branch, on a 70k-token Opus call, because its three
 * reply-category predecessors were all SKIPPED and "all predecessors resolved" was read as
 * "ready").
 */
class MergeReachabilityGuardTest {

    private static final String RUN_ID = "run-1";
    private static final int EPOCH = 152;

    private WorkflowStepDataRepository repository;
    private MergeReachabilityGuard guard;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowStepDataRepository.class);
        guard = new MergeReachabilityGuard(repository);
    }

    private ExecutionNode nodeWithPredecessors(String nodeId, String... predecessors) {
        ExecutionNode node = mock(ExecutionNode.class);
        when(node.getNodeId()).thenReturn(nodeId);
        when(node.getPredecessorIds()).thenReturn(List.of(predecessors));
        return node;
    }

    private ExecutionContext contextForItem(int itemIndex) {
        return ExecutionContext.create(RUN_ID, "wr-1", "tenant-1", String.valueOf(itemIndex),
            itemIndex, "trigger:poll_inbox", EPOCH, 0, Map.of(), null);
    }

    private void statuses(int itemIndex, Map<String, String> byKey) {
        List<Object[]> rows = new ArrayList<>();
        byKey.forEach((key, status) -> rows.add(new Object[]{key, status}));
        when(repository.findTerminalStatusesForItem(eq(RUN_ID), any(), eq(EPOCH), eq(itemIndex)))
            .thenReturn(rows);
    }

    @Test
    @DisplayName("merge whose every predecessor is SKIPPED for this item is unreachable")
    void allPredecessorsSkippedForItemIsUnreachable() {
        ExecutionNode draftReply = nodeWithPredecessors(
            "agent:draft_reply", "core:move_clients", "core:move_prospects", "core:move_partenaires");
        statuses(2, Map.of(
            "core:move_clients", "SKIPPED",
            "core:move_prospects", "SKIPPED",
            "core:move_partenaires", "SKIPPED"));

        assertThat(guard.isUnreachableForItem(draftReply, contextForItem(2))).isTrue();
    }

    @Test
    @DisplayName("one COMPLETED predecessor keeps the merge reachable")
    void oneCompletedPredecessorKeepsMergeReachable() {
        ExecutionNode draftReply = nodeWithPredecessors(
            "agent:draft_reply", "core:move_clients", "core:move_prospects", "core:move_partenaires");
        statuses(2, Map.of(
            "core:move_clients", "COMPLETED",
            "core:move_prospects", "SKIPPED",
            "core:move_partenaires", "SKIPPED"));

        assertThat(guard.isUnreachableForItem(draftReply, contextForItem(2))).isFalse();
    }

    @Test
    @DisplayName("a FAILED predecessor keeps the merge reachable, preserving merge-after-failure")
    void failedPredecessorKeepsMergeReachable() {
        ExecutionNode merge = nodeWithPredecessors("core:join", "core:a", "core:b");
        statuses(0, Map.of("core:a", "FAILED", "core:b", "SKIPPED"));

        assertThat(guard.isUnreachableForItem(merge, contextForItem(0))).isFalse();
    }

    @Test
    @DisplayName("a predecessor with no row yet is silence, not proof - the merge stays reachable")
    void predecessorWithoutRowKeepsMergeReachable() {
        ExecutionNode merge = nodeWithPredecessors("core:join", "core:a", "core:b", "core:c");
        statuses(0, Map.of("core:a", "SKIPPED", "core:b", "SKIPPED"));

        assertThat(guard.isUnreachableForItem(merge, contextForItem(0))).isFalse();
    }

    @Test
    @DisplayName("verdict is per item: skipped for item 2 does not decide item 0")
    void verdictIsScopedToTheItem() {
        ExecutionNode draftReply = nodeWithPredecessors(
            "agent:draft_reply", "core:move_clients", "core:move_prospects");
        when(repository.findTerminalStatusesForItem(eq(RUN_ID), any(), eq(EPOCH), eq(2)))
            .thenReturn(List.of(
                new Object[]{"core:move_clients", "SKIPPED"},
                new Object[]{"core:move_prospects", "SKIPPED"}));
        when(repository.findTerminalStatusesForItem(eq(RUN_ID), any(), eq(EPOCH), eq(0)))
            .thenReturn(List.of(
                new Object[]{"core:move_clients", "COMPLETED"},
                new Object[]{"core:move_prospects", "SKIPPED"}));

        assertThat(guard.isUnreachableForItem(draftReply, contextForItem(2))).isTrue();
        assertThat(guard.isUnreachableForItem(draftReply, contextForItem(0))).isFalse();
    }

    @Test
    @DisplayName("port-qualified predecessors are queried by their bare node key")
    void portQualifiedPredecessorsAreNormalized() {
        ExecutionNode merge = nodeWithPredecessors("core:join", "core:decision:if", "core:decision:else");
        // Both refs collapse to core:decision, so this is a single-predecessor node, not a merge.
        assertThat(guard.isUnreachableForItem(merge, contextForItem(0))).isFalse();
        verify(repository, never()).findTerminalStatusesForItem(anyString(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("a single-predecessor node is never suppressed and is never queried")
    void singlePredecessorNodeIsNeverSuppressed() {
        ExecutionNode linear = nodeWithPredecessors("core:next", "core:previous");

        assertThat(guard.isUnreachableForItem(linear, contextForItem(0))).isFalse();
        verify(repository, never()).findTerminalStatusesForItem(anyString(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("a repository failure leaves the merge reachable rather than mass-skipping the chain")
    void repositoryFailureFailsOpen() {
        ExecutionNode merge = nodeWithPredecessors("core:join", "core:a", "core:b");
        when(repository.findTerminalStatusesForItem(anyString(), any(), anyInt(), anyInt()))
            .thenThrow(new IllegalStateException("connection reset"));

        assertThat(guard.isUnreachableForItem(merge, contextForItem(0))).isFalse();
    }

    @Test
    @DisplayName("null node or null context is reachable")
    void nullInputsAreReachable() {
        assertThat(guard.isUnreachableForItem(null, contextForItem(0))).isFalse();
        assertThat(guard.isUnreachableForItem(nodeWithPredecessors("core:join", "core:a", "core:b"), null)).isFalse();
    }
}
