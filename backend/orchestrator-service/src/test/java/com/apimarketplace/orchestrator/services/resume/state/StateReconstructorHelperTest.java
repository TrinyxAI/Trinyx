package com.apimarketplace.orchestrator.services.resume.state;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.services.WorkflowRunStatusService;
import com.apimarketplace.orchestrator.services.resume.cache.WorkflowCacheManager;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import com.apimarketplace.common.storage.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StateReconstructorHelper")
class StateReconstructorHelperTest {

    @Mock private StorageService storageService;
    @Mock private WorkflowRunStatusService workflowRunStatusService;
    @Mock private RunStateStore runStateStore;
    @Mock private WorkflowCacheManager cacheManager;

    private StateReconstructorHelper helper;

    @BeforeEach
    void setUp() {
        helper = new StateReconstructorHelper(storageService, workflowRunStatusService, runStateStore, cacheManager);
    }

    @Nested
    @DisplayName("determineStepStatus()")
    class DetermineStepStatusTests {

        @Test
        @DisplayName("Should return COMPLETED when in completedStepIds")
        void shouldReturnCompleted() {
            assertEquals(RunStatus.COMPLETED,
                helper.determineStepStatus("mcp:step1", Set.of("mcp:step1"), Set.of(), Set.of(), Set.of()));
        }

        @Test
        @DisplayName("Should return FAILED when in failedStepIds")
        void shouldReturnFailed() {
            assertEquals(RunStatus.FAILED,
                helper.determineStepStatus("mcp:step1", Set.of(), Set.of("mcp:step1"), Set.of(), Set.of()));
        }

        @Test
        @DisplayName("Should return SKIPPED when in skippedStepIds")
        void shouldReturnSkippedForSkipped() {
            assertEquals(RunStatus.SKIPPED,
                helper.determineStepStatus("mcp:step1", Set.of(), Set.of(), Set.of("mcp:step1"), Set.of()));
        }

        @Test
        @DisplayName("Should return PENDING when in readySteps (ready maps to PENDING at run level)")
        void shouldReturnPendingForReady() {
            assertEquals(RunStatus.PENDING,
                helper.determineStepStatus("mcp:step1", Set.of(), Set.of(), Set.of(), Set.of("mcp:step1")));
        }

        @Test
        @DisplayName("Should return PENDING when not in any set")
        void shouldReturnPending() {
            assertEquals(RunStatus.PENDING,
                helper.determineStepStatus("mcp:step1", Set.of(), Set.of(), Set.of(), Set.of()));
        }

        @Test
        @DisplayName("Should prioritize COMPLETED over other statuses")
        void shouldPrioritizeCompleted() {
            assertEquals(RunStatus.COMPLETED,
                helper.determineStepStatus("mcp:step1",
                    Set.of("mcp:step1"), Set.of("mcp:step1"), Set.of("mcp:step1"), Set.of("mcp:step1")));
        }
    }

    @Nested
    @DisplayName("determineEdgeStatus()")
    class DetermineEdgeStatusTests {

        @Test
        @DisplayName("Should return COMPLETED when both source and destination completed")
        void shouldReturnCompletedWhenBothCompleted() {
            assertEquals(RunStatus.COMPLETED,
                helper.determineEdgeStatus("mcp:a", "mcp:b",
                    Set.of("mcp:a", "mcp:b"), Set.of(), Set.of()));
        }

        @Test
        @DisplayName("Should return SKIPPED when source is skipped")
        void shouldReturnSkippedWhenSourceSkipped() {
            assertEquals(RunStatus.COMPLETED,
                helper.determineEdgeStatus("mcp:a", "mcp:b",
                    Set.of(), Set.of(), Set.of("mcp:a")));
        }

        @Test
        @DisplayName("Should return SKIPPED when destination is skipped")
        void shouldReturnSkippedWhenDestSkipped() {
            assertEquals(RunStatus.COMPLETED,
                helper.determineEdgeStatus("mcp:a", "mcp:b",
                    Set.of("mcp:a"), Set.of(), Set.of("mcp:b")));
        }

        @Test
        @DisplayName("Should return RUNNING when only source completed")
        void shouldReturnRunningWhenOnlySourceCompleted() {
            assertEquals(RunStatus.RUNNING,
                helper.determineEdgeStatus("mcp:a", "mcp:b",
                    Set.of("mcp:a"), Set.of(), Set.of()));
        }

        @Test
        @DisplayName("Should return FAILED when source is failed")
        void shouldReturnFailedWhenSourceFailed() {
            assertEquals(RunStatus.FAILED,
                helper.determineEdgeStatus("mcp:a", "mcp:b",
                    Set.of(), Set.of("mcp:a"), Set.of()));
        }

        @Test
        @DisplayName("Should return PENDING when neither completed nor failed")
        void shouldReturnPending() {
            assertEquals(RunStatus.PENDING,
                helper.determineEdgeStatus("mcp:a", "mcp:b",
                    Set.of(), Set.of(), Set.of()));
        }
    }

    @Nested
    @DisplayName("determineOverallStatus()")
    class DetermineOverallStatusTests {

        @Test
        @DisplayName("Should return PAUSED when DB status is PAUSED")
        void shouldReturnPaused() {
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(entity.getStatus()).thenReturn(RunStatus.PAUSED);

            assertEquals(RunStatus.PAUSED,
                helper.determineOverallStatus(entity, Set.of(), Set.of(), Set.of(), Set.of(), mock(WorkflowPlan.class)));
        }

        @Test
        @DisplayName("Should return WAITING_TRIGGER when DB status is WAITING_TRIGGER")
        void shouldReturnWaitingTrigger() {
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(entity.getStatus()).thenReturn(RunStatus.WAITING_TRIGGER);

            assertEquals(RunStatus.WAITING_TRIGGER,
                helper.determineOverallStatus(entity, Set.of(), Set.of(), Set.of(), Set.of(), mock(WorkflowPlan.class)));
        }

        @Test
        @DisplayName("Should return CANCELLED when DB status is CANCELLED")
        void shouldReturnCancelled() {
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(entity.getStatus()).thenReturn(RunStatus.CANCELLED);

            assertEquals(RunStatus.CANCELLED,
                helper.determineOverallStatus(entity, Set.of(), Set.of(), Set.of(), Set.of(), mock(WorkflowPlan.class)));
        }

        @Test
        @DisplayName("Should return DB status for COMPLETED")
        void shouldReturnDbCompletedStatus() {
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(entity.getStatus()).thenReturn(RunStatus.COMPLETED);

            assertEquals(RunStatus.COMPLETED,
                helper.determineOverallStatus(entity, Set.of(), Set.of(), Set.of(), Set.of(), mock(WorkflowPlan.class)));
        }

        @Test
        @DisplayName("Should return RUNNING when ready steps exist")
        void shouldReturnRunningWhenReadySteps() {
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(entity.getStatus()).thenReturn(RunStatus.RUNNING);

            assertEquals(RunStatus.RUNNING,
                helper.determineOverallStatus(entity, Set.of(), Set.of(), Set.of(), Set.of("mcp:next"), mock(WorkflowPlan.class)));
        }

        // The five cases above only exercise the early-return ladder. The DERIVED branch,
        // which is where the defect lived, had zero coverage, which is why nothing caught it.
        // A bare mock(WorkflowPlan.class) returns an empty set from getAllStepIds() under
        // Mockito's default answer, and containsAll(emptySet) is trivially true, so every test
        // below stubs the plan explicitly. A future test that forgets to would silently pass.

        private WorkflowPlan planWith(String... stepIds) {
            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getAllStepIds()).thenReturn(new java.util.LinkedHashSet<>(java.util.Arrays.asList(stepIds)));
            return plan;
        }

        @Test
        @DisplayName("A core node that has not run keeps the run out of COMPLETED")
        void coreNodeMustSettleBeforeCompleted() {
            // The defect, in its smallest form: plan = one trigger + one core node. The old
            // code counted only mcps + triggers, so allStepIds was {trigger:start}, size 1,
            // and completed={trigger:start} satisfied "completed + failed >= 1". The run was
            // reported COMPLETED while core:stamp had not executed.
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(entity.getStatus()).thenReturn(RunStatus.RUNNING);

            assertEquals(RunStatus.RUNNING,
                helper.determineOverallStatus(entity,
                    Set.of("trigger:start"), Set.of(), Set.of(), Set.of(),
                    planWith("trigger:start", "core:stamp")),
                "a plan node that has not settled must not be absorbed by a cardinality threshold");
        }

        @Test
        @DisplayName("A run parked on an approval is not COMPLETED")
        void awaitingSignalIsNotCompleted() {
            // The user-visible half. A node parked on a blocking signal is removed from the
            // ready set (EpochState.markNodeAwaitingSignal) and no code writes AWAITING_SIGNAL
            // to workflow_runs, so ready is empty and dbStatus is RUNNING. Pre-fix this
            // rendered COMPLETED while a human was still being asked to approve.
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(entity.getStatus()).thenReturn(RunStatus.RUNNING);

            assertEquals(RunStatus.RUNNING,
                helper.determineOverallStatus(entity,
                    Set.of("trigger:start"), Set.of(), Set.of(), Set.of(),
                    planWith("trigger:start", "core:approve")));
        }

        @Test
        @DisplayName("A skipped branch still lets the run reach COMPLETED")
        void skippedBranchStillCompletes() {
            // The direction this change could have taken something away. Decision, switch,
            // loop-exit and fork plans settle their untaken branch as SKIPPED, never as
            // completed or failed, so skipped has to count or the derived verdict becomes
            // unreachable for that whole family.
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(entity.getStatus()).thenReturn(RunStatus.RUNNING);

            assertEquals(RunStatus.COMPLETED,
                helper.determineOverallStatus(entity,
                    Set.of("trigger:start", "core:decide", "mcp:taken"), Set.of(), Set.of("mcp:untaken"), Set.of(),
                    planWith("trigger:start", "core:decide", "mcp:taken", "mcp:untaken")));
        }

        @Test
        @DisplayName("Ids the plan does not contain cannot satisfy completion")
        void foreignIdsCannotSatisfyCompletion() {
            // Why containment rather than a corrected count. The settled sets come from the
            // snapshot's flat views and legitimately carry ids absent from the plan, notably
            // interface: nodes. Under a >= comparison those inflate the left-hand side and can
            // satisfy the threshold on their own; under containment they are simply ignored.
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(entity.getStatus()).thenReturn(RunStatus.RUNNING);

            assertEquals(RunStatus.RUNNING,
                helper.determineOverallStatus(entity,
                    Set.of("interface:page_a", "interface:page_b"), Set.of(), Set.of(), Set.of(),
                    planWith("trigger:start", "core:stamp")),
                "two foreign ids must not stand in for two unsettled plan nodes");
        }

        @Test
        @DisplayName("Every plan node settled still yields COMPLETED")
        void allSettledCompletes() {
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(entity.getStatus()).thenReturn(RunStatus.RUNNING);

            assertEquals(RunStatus.COMPLETED,
                helper.determineOverallStatus(entity,
                    Set.of("trigger:start", "core:stamp"), Set.of(), Set.of(), Set.of(),
                    planWith("trigger:start", "core:stamp")));
        }

        @Test
        @DisplayName("A failed plan node yields FAILED once everything settled")
        void failedNodeYieldsFailed() {
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(entity.getStatus()).thenReturn(RunStatus.RUNNING);

            assertEquals(RunStatus.FAILED,
                helper.determineOverallStatus(entity,
                    Set.of("trigger:start"), Set.of("core:stamp"), Set.of(), Set.of(),
                    planWith("trigger:start", "core:stamp")));
        }
    }

    @Nested
    @DisplayName("calculateExecutionTime()")
    class CalculateExecutionTimeTests {

        @Test
        @DisplayName("Should calculate execution time from start and end time")
        void shouldCalculateExecutionTime() {
            WorkflowStepDataEntity entity = mock(WorkflowStepDataEntity.class);
            Instant start = Instant.parse("2024-01-15T10:00:00Z");
            Instant end = Instant.parse("2024-01-15T10:00:05Z");
            when(entity.getStartTime()).thenReturn(start);
            when(entity.getEndTime()).thenReturn(end);

            assertEquals(5000, helper.calculateExecutionTime(entity));
        }

        @Test
        @DisplayName("Should return 0 when start time is null")
        void shouldReturnZeroWhenStartNull() {
            WorkflowStepDataEntity entity = mock(WorkflowStepDataEntity.class);
            when(entity.getStartTime()).thenReturn(null);

            assertEquals(0, helper.calculateExecutionTime(entity));
        }

        @Test
        @DisplayName("Should return 0 when end time is null")
        void shouldReturnZeroWhenEndNull() {
            WorkflowStepDataEntity entity = mock(WorkflowStepDataEntity.class);
            when(entity.getStartTime()).thenReturn(Instant.now());
            when(entity.getEndTime()).thenReturn(null);

            assertEquals(0, helper.calculateExecutionTime(entity));
        }
    }

    @Nested
    @DisplayName("extractLabel()")
    class ExtractLabelTests {

        @Test
        @DisplayName("Should extract label from prefixed node ID")
        void shouldExtractLabel() {
            assertEquals("while_loop", helper.extractLabel("core:while_loop"));
        }

        @Test
        @DisplayName("Should return raw ID when no prefix")
        void shouldReturnRawId() {
            assertEquals("my_step", helper.extractLabel("my_step"));
        }

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNull() {
            assertNull(helper.extractLabel(null));
        }

        @Test
        @DisplayName("Should extract label from mcp prefix")
        void shouldExtractLabelFromMcp() {
            assertEquals("api_call", helper.extractLabel("mcp:api_call"));
        }
    }

    @Nested
    @DisplayName("normalizeCoreId()")
    class NormalizeCoreIdTests {

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNull() {
            assertNull(helper.normalizeCoreId(null));
        }
    }

    @Nested
    @DisplayName("isPaused()")
    class IsPausedTests {

        @Test
        @DisplayName("Should return true when cache says paused")
        void shouldReturnTrueWhenCachePaused() {
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(cacheManager.isPaused("run-1")).thenReturn(true);

            assertTrue(helper.isPaused("run-1", entity));
        }

        @Test
        @DisplayName("Should return true when entity status is PAUSED")
        void shouldReturnTrueWhenEntityPaused() {
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(cacheManager.isPaused("run-1")).thenReturn(false);
            when(entity.getStatus()).thenReturn(RunStatus.PAUSED);

            assertTrue(helper.isPaused("run-1", entity));
        }

        @Test
        @DisplayName("Should return false when not paused")
        void shouldReturnFalseWhenNotPaused() {
            WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
            when(cacheManager.isPaused("run-1")).thenReturn(false);
            when(entity.getStatus()).thenReturn(RunStatus.RUNNING);

            assertFalse(helper.isPaused("run-1", entity));
        }
    }

    @Nested
    @DisplayName("isLoopKey()")
    class IsLoopKeyTests {

        @Test
        @DisplayName("Should return false for null stepKey")
        void shouldReturnFalseForNullStepKey() {
            assertFalse(helper.isLoopKey(null, mock(WorkflowPlan.class)));
        }

        @Test
        @DisplayName("Should return false for null plan")
        void shouldReturnFalseForNullPlan() {
            assertFalse(helper.isLoopKey("core:loop1", null));
        }

        @Test
        @DisplayName("Should return false when no cores in plan")
        void shouldReturnFalseWhenNoCores() {
            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getCores()).thenReturn(null);

            assertFalse(helper.isLoopKey("core:loop1", plan));
        }
    }

    @Nested
    @DisplayName("findNormalizedKeyForAlias()")
    class FindNormalizedKeyTests {

        @Test
        @DisplayName("Should return null for null alias")
        void shouldReturnNullForNullAlias() {
            assertNull(helper.findNormalizedKeyForAlias(mock(WorkflowPlan.class), null));
        }
    }

    @Nested
    @DisplayName("normalizeNodeId()")
    class NormalizeNodeIdTests {

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNull() {
            assertNull(helper.normalizeNodeId(null));
        }

        @Test
        @DisplayName("Should return already normalized key as-is")
        void shouldReturnAlreadyNormalizedAsIs() {
            String normalized = helper.normalizeNodeId("mcp:my_step");
            assertEquals("mcp:my_step", normalized);
        }
    }
}
