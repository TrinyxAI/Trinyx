package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The delivery side of the stale-split-scope fix.
 *
 * <p>An async agent delivery is handled by whichever replica consumed the Redis message, which
 * may be a pod that ran an EARLIER epoch of this run and still holds that epoch's split scope
 * in its heap. {@code SplitContextManager.restoreContext} can only tell the two apart if this
 * call site forwards the delivery's OWN epoch. Prod run {@code run_<id>}
 * (epochs 80 -> 81 and 96 -> 97) is what happens when it does not.
 *
 * <p>{@link PendingAgent} declares {@code int epoch} and {@code int itemIndex} next to each
 * other, so forwarding the wrong one compiles, ships, and reproduces the incident. The epoch and
 * the item index here are deliberately DIFFERENT values so that swap fails this test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentAsyncCompletionService - the split-scope restore carries the delivery's epoch")
class AgentAsyncCompletionSplitEpochRestoreTest {

    private static final int DELIVERY_EPOCH = 97;
    private static final int SUB_ITEM_INDEX = 3;

    @Mock private PendingAgentRegistry registry;
    @Mock private StepCompletionOrchestrator stepCompletionOrchestrator;
    @Mock private SplitContextManager splitContextManager;
    @Mock private RunningNodeTracker runningNodeTracker;
    @Mock private SplitCoalesceTracker splitCoalesceTracker;
    @Mock private com.apimarketplace.orchestrator.repository.WorkflowRunRepository runRepository;

    private AgentAsyncCompletionService service;

    @BeforeEach
    void setUp() {
        NodeSearchService nodeSearchService = mock(NodeSearchService.class);
        service = new AgentAsyncCompletionService(
            registry,
            stepCompletionOrchestrator,
            splitContextManager,
            runningNodeTracker,
            splitCoalesceTracker,
            nodeSearchService,
            runRepository);
    }

    private static PendingAgent pendingAgent(Map<String, Object> splitItemData) {
        return new PendingAgent(
            "corr-1", "run_<id>", "agent:classify_mail", "Classify Mail",
            "trigger:poll_inbox", DELIVERY_EPOCH, SUB_ITEM_INDEX, String.valueOf(SUB_ITEM_INDEX),
            "classify", "tenant-1", splitItemData, null, null, null, null, null, null, null,
            Instant.now());
    }

    private void invokeRestore(PendingAgent pending) throws Exception {
        Method method = AgentAsyncCompletionService.class
            .getDeclaredMethod("restoreSplitContextIfAny", PendingAgent.class);
        method.setAccessible(true);
        method.invoke(service, pending);
    }

    @Test
    @DisplayName("a split delivery restores with pending.epoch(), never with the sub-item index")
    void splitDeliveryForwardsTheDeliveryEpoch() throws Exception {
        Map<String, Object> splitItemData = new HashMap<>();
        splitItemData.put("splitNodeId", "core:each_mail");
        splitItemData.put("workflowItemIndex", 0);
        splitItemData.put("itemIndex", SUB_ITEM_INDEX);
        splitItemData.put("items", List.of("m1026"));

        invokeRestore(pendingAgent(splitItemData));

        // Exact tuple: swapping epoch for itemIndex, or dropping to the 3-arg overload (which
        // stamps UNKNOWN_EPOCH and is never stale), fails here.
        verify(splitContextManager).restoreContext(
            "run_<id>", "agent:classify_mail", splitItemData, DELIVERY_EPOCH);
    }

    @Test
    @DisplayName("a delivery with no split data restores nothing at all")
    void nonSplitDeliveryRestoresNothing() throws Exception {
        invokeRestore(pendingAgent(null));
        invokeRestore(pendingAgent(new HashMap<>()));

        verify(splitContextManager, never()).restoreContext(any(), any(), any(), anyInt());
        verify(splitContextManager, never()).restoreContext(any(), any(), any());
    }
}
