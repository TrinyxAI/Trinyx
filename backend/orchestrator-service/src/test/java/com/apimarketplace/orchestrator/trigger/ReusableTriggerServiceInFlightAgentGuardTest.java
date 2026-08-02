package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry;
import com.apimarketplace.orchestrator.execution.v2.async.RedisInFlightStore;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the epoch-reset race that drops the node downstream of an
 * async agent.
 *
 * <p><b>Root cause.</b> "Is an async agent still in flight for this epoch?" is answered by TWO
 * stores, because an agent lives in the registry from dispatch until {@code consume()} and in the
 * in-flight store from {@code consume()} until the end of delivery.
 * {@code AgentAsyncCompletionService.onAgentResult} calls {@code registry.consume(...)} on its
 * FIRST line and only reaches {@code executeReadyNodesLoop} hundreds of milliseconds later, so the
 * whole delivery happens with the registry already empty. The reset guard consulted the registry
 * only: inside that window it read "drained", closed and pruned the epoch, and the successor
 * traversal that followed found no epoch state to walk. The downstream node was never dispatched,
 * never marked SKIPPED, and got no step row - with nothing logged above INFO.
 *
 * <p><b>Fix under test.</b> The guard consults the in-flight store as well.
 * {@code AgentAsyncCompletionService.triggerDeferredResetIfDrained} already did this for the other
 * reader of the same question; these tests pin it for the reader it was missing from. They fail on
 * the pre-fix code, which returned false as soon as the registry was empty.
 *
 * <p>The guard is private because it is an invariant of the reset path rather than an API; it is
 * invoked reflectively so the test pins the decision itself rather than a caller's side effects.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReusableTriggerService - async agent in-flight guard")
class ReusableTriggerServiceInFlightAgentGuardTest {

    private static final String RUN_ID = "run-1";
    private static final String TRIGGER_ID = "trigger:start";
    private static final int EPOCH = 1;

    @Mock private PendingAgentRegistry pendingAgentRegistry;
    @Mock private RedisInFlightStore agentInFlightStore;
    @Mock private UnifiedSignalService unifiedSignalService;

    private ReusableTriggerService service;

    @BeforeEach
    void setUp() {
        // The constructor takes a dozen collaborators the guard never touches; nulls keep the
        // fixture about the one decision under test.
        service = new ReusableTriggerService(
                null, null, null, null, null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "pendingAgentRegistry", pendingAgentRegistry);
        ReflectionTestUtils.setField(service, "agentInFlightStore", agentInFlightStore);
        ReflectionTestUtils.setField(service, "unifiedSignalService", unifiedSignalService);

        // No blocking signals: isolate the async-agent half of the guard.
        org.mockito.Mockito.lenient().when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(false);
        org.mockito.Mockito.lenient().when(unifiedSignalService.hasPendingSignalResumesForDagAndEpoch(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(false);
        org.mockito.Mockito.lenient().when(unifiedSignalService.hasBlockingSignals(anyString())).thenReturn(false);
    }

    private boolean guard(String triggerId, int epoch) {
        return guardExcluding(triggerId, epoch, null);
    }

    private boolean guardExcluding(String triggerId, int epoch, String excludeCorrelationId) {
        Boolean result = ReflectionTestUtils.invokeMethod(
                service, "hasActiveSignalsForTrigger", RUN_ID, triggerId, epoch, excludeCorrelationId);
        assertThat(result).as("guard must return a decision").isNotNull();
        return result;
    }

    @Nested
    @DisplayName("epoch-scoped path (triggerId + epoch known)")
    class EpochScoped {

        @Test
        @DisplayName("blocks the reset while an agent is consumed but not yet delivered")
        void blocksWhileConsumedNotDelivered() {
            // THE bug: the registry is already empty because consume() ran, but the delivery
            // that will dispatch the successor has not finished. Resetting here destroys the
            // epoch state that delivery is about to walk.
            when(pendingAgentRegistry.hasPendingFor(RUN_ID, TRIGGER_ID, EPOCH)).thenReturn(false);
            when(agentInFlightStore.hasOtherInFlightForEpoch(RUN_ID, TRIGGER_ID, EPOCH, null)).thenReturn(true);

            assertThat(guard(TRIGGER_ID, EPOCH))
                    .as("an agent mid-delivery must defer the epoch reset")
                    .isTrue();
        }

        @Test
        @DisplayName("blocks while the agent is still in the registry (pre-consume, unchanged)")
        void blocksWhileStillPending() {
            when(pendingAgentRegistry.hasPendingFor(RUN_ID, TRIGGER_ID, EPOCH)).thenReturn(true);

            assertThat(guard(TRIGGER_ID, EPOCH)).isTrue();
            // The registry answered; there is no reason to pay for a Redis SCAN.
            verify(agentInFlightStore, never())
                    .hasOtherInFlightForEpoch(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(), isNull());
        }

        @Test
        @DisplayName("allows the reset once BOTH stores are empty")
        void allowsWhenBothDrained() {
            when(pendingAgentRegistry.hasPendingFor(RUN_ID, TRIGGER_ID, EPOCH)).thenReturn(false);
            when(agentInFlightStore.hasOtherInFlightForEpoch(RUN_ID, TRIGGER_ID, EPOCH, null)).thenReturn(false);

            assertThat(guard(TRIGGER_ID, EPOCH))
                    .as("nothing in flight: the cycle must be free to reset, or a reusable "
                            + "trigger would never re-arm")
                    .isFalse();
        }

        @Test
        @DisplayName("excludes no correlationId - an ordinary reset owns no delivery to exempt")
        void passesNullExclude() {
            when(pendingAgentRegistry.hasPendingFor(RUN_ID, TRIGGER_ID, EPOCH)).thenReturn(false);
            when(agentInFlightStore.hasOtherInFlightForEpoch(RUN_ID, TRIGGER_ID, EPOCH, null)).thenReturn(false);

            guard(TRIGGER_ID, EPOCH);

            // Unlike the drain check inside a delivery, this caller is not itself an agent
            // completion: exempting any correlationId here would re-open the window by one agent.
            verify(agentInFlightStore).hasOtherInFlightForEpoch(RUN_ID, TRIGGER_ID, EPOCH, null);
        }
    }

    @Nested
    @DisplayName("self-exemption for the delivery that requested the reset")
    class SelfExemption {

        private static final String SELF = "cid-self";

        @Test
        @DisplayName("a delivery is not blocked by its OWN stale entry when its clear failed")
        void ownStaleEntryDoesNotBlock() {
            // triggerDeferredResetIfDrained clears its entry then asks for the reset, but
            // RedisInFlightStore.clear swallows a Redis error. Without the exemption that
            // swallowed error turns into a run stuck RUNNING until the zombie scanner fails it,
            // because nothing retries the reset this delivery just requested.
            when(pendingAgentRegistry.hasPendingFor(RUN_ID, TRIGGER_ID, EPOCH)).thenReturn(false);
            when(agentInFlightStore.hasOtherInFlightForEpoch(RUN_ID, TRIGGER_ID, EPOCH, SELF)).thenReturn(false);

            assertThat(guardExcluding(TRIGGER_ID, EPOCH, SELF))
                    .as("the caller's own entry must not block the reset it asked for")
                    .isFalse();
            verify(agentInFlightStore).hasOtherInFlightForEpoch(RUN_ID, TRIGGER_ID, EPOCH, SELF);
        }

        @Test
        @DisplayName("a SIBLING still blocks, even with self exempted")
        void siblingStillBlocks() {
            // The exemption is exactly one correlationId wide. A sibling mid-delivery is still
            // in flight, and closing the epoch would destroy the state it is about to walk.
            when(pendingAgentRegistry.hasPendingFor(RUN_ID, TRIGGER_ID, EPOCH)).thenReturn(false);
            when(agentInFlightStore.hasOtherInFlightForEpoch(RUN_ID, TRIGGER_ID, EPOCH, SELF)).thenReturn(true);

            assertThat(guardExcluding(TRIGGER_ID, EPOCH, SELF)).isTrue();
        }
    }

    @Nested
    @DisplayName("run-wide fallback (legacy 4-arg resetForNextCycle, triggerId null)")
    class RunWide {

        @Test
        @DisplayName("blocks while an agent is consumed but not yet delivered")
        void blocksWhileConsumedNotDelivered() {
            when(pendingAgentRegistry.hasAnyPendingForRun(RUN_ID)).thenReturn(false);
            when(agentInFlightStore.hasAnyInFlightForRun(RUN_ID)).thenReturn(true);

            assertThat(guard(null, -1)).isTrue();
        }

        @Test
        @DisplayName("allows the reset once BOTH stores are empty")
        void allowsWhenBothDrained() {
            when(pendingAgentRegistry.hasAnyPendingForRun(RUN_ID)).thenReturn(false);
            when(agentInFlightStore.hasAnyInFlightForRun(RUN_ID)).thenReturn(false);

            assertThat(guard(null, -1)).isFalse();
        }
    }

    @Nested
    @DisplayName("optional beans")
    class OptionalBeans {

        @Test
        @DisplayName("falls back to the registry when the in-flight store is absent")
        void noInFlightStore() {
            // In-memory scaling mode and unit tests have no RedisInFlightStore bean. The guard
            // must still work off the registry rather than NPE.
            ReflectionTestUtils.setField(service, "agentInFlightStore", null);
            when(pendingAgentRegistry.hasPendingFor(RUN_ID, TRIGGER_ID, EPOCH)).thenReturn(true);

            assertThat(guard(TRIGGER_ID, EPOCH)).isTrue();
        }

        @Test
        @DisplayName("still guards off the in-flight store when the registry is absent")
        void noRegistry() {
            ReflectionTestUtils.setField(service, "pendingAgentRegistry", null);
            when(agentInFlightStore.hasOtherInFlightForEpoch(RUN_ID, TRIGGER_ID, EPOCH, null)).thenReturn(true);

            assertThat(guard(TRIGGER_ID, EPOCH))
                    .as("the in-flight store alone must be able to defer a reset")
                    .isTrue();
        }

        @Test
        @DisplayName("allows the reset when neither bean is wired")
        void neitherBean() {
            ReflectionTestUtils.setField(service, "pendingAgentRegistry", null);
            ReflectionTestUtils.setField(service, "agentInFlightStore", null);

            assertThat(guard(TRIGGER_ID, EPOCH))
                    .as("no async subsystem at all means nothing can be in flight")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("blocking signals still win")
    class BlockingSignals {

        @Test
        @DisplayName("a blocking signal short-circuits before either agent store is read")
        void signalShortCircuits() {
            when(unifiedSignalService.hasBlockingSignalsForDagAndEpoch(RUN_ID, TRIGGER_ID, EPOCH)).thenReturn(true);

            assertThat(guard(TRIGGER_ID, EPOCH)).isTrue();
            verify(pendingAgentRegistry, never()).hasPendingFor(anyString(), anyString(), eq(EPOCH));
            verify(agentInFlightStore, never())
                    .hasOtherInFlightForEpoch(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(), isNull());
        }
    }
}
