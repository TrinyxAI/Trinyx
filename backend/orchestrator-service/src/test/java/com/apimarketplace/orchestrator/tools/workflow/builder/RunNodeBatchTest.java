package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.adhoc.AdHocNodeExecutionService;
import com.apimarketplace.orchestrator.execution.v2.adhoc.AdHocNodeRequest;
import com.apimarketplace.orchestrator.execution.v2.adhoc.AdHocNodeResult;
import com.apimarketplace.orchestrator.services.NodeTypeSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code workflow(action='run_node', run_inputs=[...])} - the surface half of batching.
 *
 * <p>Two things are load-bearing and neither is visible by reading the happy path: every gate
 * that protects the single-item path must still refuse BEFORE any item runs (a batch must not be
 * a way to smuggle one execution past a gate), and the batch report must not carry a top-level
 * verdict that contradicts its own items.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunNodeBatchTest {

    @Mock private AdHocNodeExecutionService adHocNodeExecutionService;
    @Mock private NodeTypeSearchService nodeTypeSearchService;
    @Mock private WorkflowBuilderResultEnricher resultEnricher;
    @Mock private WorkflowBuilderLogger buildLogger;

    private WorkflowBuilderProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        java.lang.reflect.Constructor<?> ctor = WorkflowBuilderProvider.class.getDeclaredConstructors()[0];
        provider = (WorkflowBuilderProvider) ctor.newInstance(new Object[ctor.getParameterCount()]);
        setField("adHocNodeExecutionService", adHocNodeExecutionService);
        setField("nodeTypeSearchService", nodeTypeSearchService);
        setField("resultEnricher", resultEnricher);
        setField("buildLogger", buildLogger);
        when(nodeTypeSearchService.isNodeTypeEnabled(any())).thenReturn(true);
        lenient().when(resultEnricher.addSessionSnapshot(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private void setField(String name, Object value) throws Exception {
        Field f = WorkflowBuilderProvider.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(provider, value);
    }

    private static ToolExecutionContext inConversation() {
        return new ToolExecutionContext("tenant-1",
                Map.of("conversationId", "conv-1", "__streamId__", "stream-1"),
                Map.of(), Set.of(), null, null, "org-1", "OWNER");
    }

    private static ToolExecutionContext externalMcp() {
        return new ToolExecutionContext("tenant-1", Map.of(), Map.of(), Set.of(), null, null, "org-1", "OWNER");
    }

    private ToolExecutionResult runWithItems(Object items) {
        return runWithItems(items, inConversation());
    }

    private ToolExecutionResult runWithItems(Object items, ToolExecutionContext ctx) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "run_node");
        params.put("type", "code");
        params.put("params", Map.of("code", "return 1;"));
        if (items != null) params.put("run_inputs", items);
        return provider.execute("workflow", params, ctx);
    }

    private static List<Map<String, Object>> items(int count) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(Map.of("index", i));
        }
        return list;
    }

    private static AdHocNodeResult completed(Map<String, Object> output) {
        return completed(output, 5L);
    }

    /** Durations that differ per entry: a constant cannot tell "each entry's own" from "any". */
    private static AdHocNodeResult completed(Map<String, Object> output, long durationMs) {
        return new AdHocNodeResult("code", AdHocNodeResult.COMPLETED, output, null, durationMs, null);
    }

    private static AdHocNodeResult failed(String error) {
        return failed(error, 5L);
    }

    private static AdHocNodeResult failed(String error, long durationMs) {
        return new AdHocNodeResult("code", AdHocNodeResult.FAILED, Map.of(), error, durationMs, null);
    }

    private static AdHocNodeResult awaitingSignal() {
        return new AdHocNodeResult("code", AdHocNodeResult.AWAITING_SIGNAL, Map.of("partial", true),
                null, 5L, "This node suspends on a signal that only a real run can resolve.");
    }

    private static AdHocNodeResult notStarted() {
        return notStarted("The call ran out of time before this entry started, so it never ran.");
    }

    private static AdHocNodeResult notStarted(String reason) {
        return new AdHocNodeResult("code", AdHocNodeResult.NOT_STARTED, Map.of(), reason, 0L, null);
    }

    private static AdHocNodeResult timedOut() {
        return timedOut(5L);
    }

    private static AdHocNodeResult timedOut(long durationMs) {
        return new AdHocNodeResult("code", AdHocNodeResult.TIMED_OUT, Map.of(), "too slow", durationMs, null);
    }

    private void batchReturns(AdHocNodeResult... results) {
        lenient().when(adHocNodeExecutionService.executeBatch(any(), any(), anyInt()))
                .thenReturn(List.of(results));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ToolExecutionResult result) {
        return (Map<String, Object>) result.data();
    }

    @Nested
    @DisplayName("dispatch")
    class Dispatch {

        @Test
        @DisplayName("Should send items to executeBatch and never to the single-item path")
        void shouldRouteToBatch() {
            batchReturns(completed(Map.of("a", 1)), completed(Map.of("a", 2)));

            ToolExecutionResult result = runWithItems(items(2));

            assertThat(result.success()).isTrue();
            verify(adHocNodeExecutionService).executeBatch(any(), any(), anyInt());
            verify(adHocNodeExecutionService, never()).execute(any());
        }

        @Test
        @DisplayName("Should keep the single-item path untouched when items is absent")
        void shouldNotAffectSingleItemPath() {
            lenient().when(adHocNodeExecutionService.execute(any())).thenReturn(completed(Map.of("v", 1)));

            ToolExecutionResult result = runWithItems(null);

            assertThat(result.success()).isTrue();
            // The pre-existing shape: a top-level output, no batch keys.
            assertThat(body(result)).containsKey("output");
            assertThat(body(result)).doesNotContainKeys("items", "item_count", "completed");
            verify(adHocNodeExecutionService).execute(any());
            verify(adHocNodeExecutionService, never()).executeBatch(any(), any(), anyInt());
        }

        @Test
        @DisplayName("Should pass one run input per entry, in order, sharing one config")
        void shouldPassOneInputPerEntry() {
            batchReturns(completed(Map.of()), completed(Map.of()), completed(Map.of()));

            runWithItems(items(3));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Map<String, Object>>> inputs = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<AdHocNodeRequest> template = ArgumentCaptor.forClass(AdHocNodeRequest.class);
            verify(adHocNodeExecutionService).executeBatch(template.capture(), inputs.capture(), anyInt());

            assertThat(inputs.getValue()).hasSize(3);
            assertThat(inputs.getValue().get(0)).containsEntry("index", 0);
            assertThat(inputs.getValue().get(2)).containsEntry("index", 2);
            assertThat(template.getValue().config()).containsEntry("code", "return 1;");
        }

        @Test
        @DisplayName("Should pass the configured parallelism, which is a rate limit on somebody else's API")
        void shouldPassTheConfiguredParallelism() {
            batchReturns(completed(Map.of()), completed(Map.of()));
            ArgumentCaptor<Integer> parallelism = ArgumentCaptor.forClass(Integer.class);

            runWithItems(items(2));

            verify(adHocNodeExecutionService).executeBatch(any(), any(), parallelism.capture());
            assertThat(parallelism.getValue()).isEqualTo(AdHocNodeExecutionService.MAX_BATCH_PARALLELISM);
        }

        @Test
        @DisplayName("Should name the summed duration for what it is, not as the time the call took")
        void shouldReportTheSummedDurationUnderAnHonestName() {
            // The entries run in parallel, so their sum is not elapsed time. A key called
            // duration_ms would be read as a wall clock and be wrong by the parallelism factor.
            batchReturns(completed(Map.of()), completed(Map.of()));

            Map<String, Object> body = body(runWithItems(items(2)));

            assertThat(body).containsEntry("total_item_duration_ms", 10L);
            assertThat(body).doesNotContainKey("duration_ms");
            assertThat(body).containsEntry("node_type", "code");
        }

        @Test
        @DisplayName("Should sum the time of entries that did NOT complete too, not only the successful ones")
        void theSumCoversEveryEntryWhateverItsOutcome() {
            // A batch of all-completed entries cannot tell "the sum of the entries" from "the sum
            // of the ones that worked", and the second is the more tempting reading. It is also
            // the wrong one to hand an agent: the time a failed or cut-off entry burned is exactly
            // the time it must account for when sizing the next batch, and under-reporting it
            // invites a resend that runs out of budget the same way.
            batchReturns(completed(Map.of(), 100L), failed("boom", 200L), timedOut(400L),
                    notStarted());

            Map<String, Object> body = body(runWithItems(items(4)));

            assertThat(body)
                    .as("100 + 200 + 400 + 0, not the 100 the completed entry spent")
                    .containsEntry("total_item_duration_ms", 700L);
        }
    }

    @Nested
    @DisplayName("refusals - nothing runs")
    class Refusals {

        @Test
        @DisplayName("Should refuse items together with run_input rather than silently ignoring one")
        void shouldRefuseItemsAndRunInputTogether() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("action", "run_node");
            params.put("type", "code");
            params.put("params", Map.of("code", "return 1;"));
            params.put("run_inputs", items(2));
            params.put("run_input", Map.of("x", 1));

            ToolExecutionResult result = provider.execute("workflow", params, inConversation());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("not both");
            verify(adHocNodeExecutionService, never()).executeBatch(any(), any(), anyInt());
            verify(adHocNodeExecutionService, never()).execute(any());
        }

        @Test
        @DisplayName("Should refuse a non-list rather than quietly running zero items (in-process callers only)")
        void shouldRefuseNonList() {
            // Verified live: through the tool layer an agent never reaches this branch. The
            // declared array type refuses a number or an object first (TOOL_012), and a
            // stringified array is coerced into a real list. This pins the guard for a caller
            // that bypasses that layer, which is why the input here is a raw non-list.
            ToolExecutionResult result = runWithItems(42);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("must be a list");
            verify(adHocNodeExecutionService, never()).executeBatch(any(), any(), anyInt());
        }

        @Test
        @DisplayName("Should refuse an empty list, which would otherwise succeed having done nothing")
        void shouldRefuseEmptyList() {
            ToolExecutionResult result = runWithItems(List.of());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("nothing to run");
            verify(adHocNodeExecutionService, never()).executeBatch(any(), any(), anyInt());
        }

        @Test
        @DisplayName("Should refuse more entries than the cap and say what to do instead")
        void shouldRefuseOverTheCap() {
            ToolExecutionResult result = runWithItems(items(AdHocNodeExecutionService.MAX_BATCH_ITEMS + 1));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("21");
            assertThat(result.error()).contains("split");
            verify(adHocNodeExecutionService, never()).executeBatch(any(), any(), anyInt());
        }

        @Test
        @DisplayName("Should accept exactly the cap, so the boundary is not off by one")
        void shouldAcceptExactlyTheCap() {
            AdHocNodeResult[] all = new AdHocNodeResult[AdHocNodeExecutionService.MAX_BATCH_ITEMS];
            java.util.Arrays.fill(all, completed(Map.of()));
            batchReturns(all);

            ToolExecutionResult result = runWithItems(items(AdHocNodeExecutionService.MAX_BATCH_ITEMS));

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Should name the offending index when an entry is not an object")
        void shouldNameTheBadIndex() {
            List<Object> mixed = new ArrayList<>(items(2));
            mixed.add("not a map");

            ToolExecutionResult result = runWithItems(mixed);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("run_inputs[2]");
            verify(adHocNodeExecutionService, never()).executeBatch(any(), any(), anyInt());
        }
    }

    @Nested
    @DisplayName("gates still refuse before any item runs")
    class GatesRunFirst {

        @Test
        @DisplayName("Should refuse a batch from an external API-key session, exactly as a single call")
        void shouldRefuseOutsideConversation() {
            ToolExecutionResult result = runWithItems(items(3), externalMcp());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("no one to ask");
            verify(adHocNodeExecutionService, never()).executeBatch(any(), any(), anyInt());
        }

        @Test
        @DisplayName("Should refuse a disabled node type before running any item")
        void shouldRefuseDisabledType() {
            when(nodeTypeSearchService.isNodeTypeEnabled(any())).thenReturn(false);

            ToolExecutionResult result = runWithItems(items(5));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("disabled");
            verify(adHocNodeExecutionService, never()).executeBatch(any(), any(), anyInt());
        }

        @Test
        @DisplayName("Should refuse a generate batch without the grant, the one gate with money attached")
        void shouldRefuseGenerateWithoutTheGrant() {
            // An ABSENT module list means full access, so the refusal can only be exercised with a
            // list that exists and omits generation.
            Map<String, Object> creds = new LinkedHashMap<>();
            creds.put("conversationId", "conv-1");
            creds.put("__streamId__", "stream-1");
            creds.put(com.apimarketplace.agent.config.AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY,
                    List.of("workflow", "table"));
            ToolExecutionContext ctx = new ToolExecutionContext("tenant-1", creds, Map.of(), Set.of(),
                    null, null, "org-1", "OWNER");

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("action", "run_node");
            params.put("type", "generate");
            params.put("params", Map.of("prompt", "a cat"));
            params.put("run_inputs", items(3));

            ToolExecutionResult result = provider.execute("workflow", params, ctx);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("not set up to run generations");
            verify(adHocNodeExecutionService, never()).executeBatch(any(), any(), anyInt());
        }

        @Test
        @DisplayName("Should refuse a type that cannot run standalone before running any entry")
        void shouldRefuseARefusedTypeFirst() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("action", "run_node");
            params.put("type", "merge");
            params.put("params", Map.of());
            params.put("run_inputs", items(3));

            ToolExecutionResult result = provider.execute("workflow", params, inConversation());

            assertThat(result.success()).isFalse();
            verify(adHocNodeExecutionService, never()).executeBatch(any(), any(), anyInt());
        }

        @Test
        @DisplayName("Should refuse a config that rewrites what the node is, before running any item")
        void shouldRefuseReservedKey() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("action", "run_node");
            params.put("type", "code");
            params.put("params", Map.of("type", "ssh", "code", "return 1;"));
            params.put("run_inputs", items(3));

            ToolExecutionResult result = provider.execute("workflow", params, inConversation());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("identifies the node itself");
            verify(adHocNodeExecutionService, never()).executeBatch(any(), any(), anyInt());
        }
    }

    @Nested
    @DisplayName("the batch report")
    class Report {

        @Test
        @DisplayName("Should report per item and carry NO top-level output or error")
        void shouldNotCarryATopLevelVerdict() {
            batchReturns(completed(Map.of("a", 1)), failed("boom"));

            ToolExecutionResult result = runWithItems(items(2));
            Map<String, Object> body = body(result);

            // An empty top-level output would read as "no output" and an empty error as "no
            // error"; both are false the moment the items disagree.
            assertThat(body).doesNotContainKeys("output", "error");
            assertThat(body).containsEntry("item_count", 2);
            assertThat(body).containsEntry("completed", 1);
            assertThat(body).containsEntry("failed", 1);
        }

        @Test
        @DisplayName("Should call a mixed batch 'partial' and still SUCCEED, so the good results survive")
        void mixedBatchIsPartialAndSucceeds() {
            batchReturns(completed(Map.of("a", 1)), failed("boom"), completed(Map.of("a", 3)));

            ToolExecutionResult result = runWithItems(items(3));

            assertThat(result.success()).isTrue();
            assertThat(body(result)).containsEntry("status", "partial");
        }

        @Test
        @DisplayName("Should call an all-completed batch 'completed'")
        void allCompleted() {
            batchReturns(completed(Map.of("a", 1)), completed(Map.of("a", 2)));

            assertThat(body(runWithItems(items(2)))).containsEntry("status", "completed");
        }

        @Test
        @DisplayName("Should NOT call an all-timed-out batch a failure: those items may still be running")
        void allTimedOutIsNotAFailure() {
            // Calling it failed would invite the agent to resend work that is still in flight and
            // may still have its effect. The per-item statuses carry the uncertainty instead.
            batchReturns(timedOut(), timedOut());

            ToolExecutionResult result = runWithItems(items(2));

            assertThat(result.success()).isTrue();
            assertThat(body(result)).containsEntry("status", "timed_out");
            assertThat(body(result)).containsEntry("timed_out", 2);
        }

        @Test
        @DisplayName("Should NOT call an all-awaiting-signal batch a failure: those items ran correctly")
        void awaitingSignalIsNotAFailure() {
            // They did not fail, there IS a reason (the note), and a failure result carries no body
            // to read it in - so folding them into the failure verdict made the message assert four
            // things that were not true.
            batchReturns(awaitingSignal(), awaitingSignal());

            ToolExecutionResult result = runWithItems(items(2));

            assertThat(result.success()).isTrue();
            assertThat(body(result)).containsEntry("status", "awaiting_signal");
            assertThat(body(result)).containsEntry("awaiting_signal", 2);
            assertThat(body(result)).containsEntry("failed", 0);
        }

        @Test
        @DisplayName("Should put the reason in an all-failed message, since a failure carries no body")
        void allFailedCarriesTheReason() {
            batchReturns(failed("connection refused"));

            ToolExecutionResult result = runWithItems(items(1));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("connection refused");
        }

        @Test
        @DisplayName("Should list each DISTINCT reason when the items failed differently")
        void allFailedListsDistinctReasons() {
            // The entries share a config but not their inputs, so they can fail for different
            // reasons; reporting only the first would hide the rest with no body to look them up in.
            batchReturns(failed("host A unreachable"), failed("401 for this key"));

            ToolExecutionResult result = runWithItems(items(2));

            assertThat(result.error()).contains("host A unreachable").contains("401 for this key");
            assertThat(result.error()).contains("reasons differ");
        }

        @Test
        @DisplayName("Should NOT call a batch 'failed' when some entries merely have an unknown outcome")
        void mixedFailureAndUnknownIsNotFailed() {
            // Saying "failed" would assert something about the unknown entries that nothing proved.
            batchReturns(failed("boom"), timedOut(), timedOut());

            ToolExecutionResult result = runWithItems(items(3));

            assertThat(result.success()).isTrue();
            assertThat(body(result)).containsEntry("status", "unknown");
            assertThat(body(result)).containsEntry("failed", 1);
            assertThat(body(result)).containsEntry("timed_out", 2);
        }

        @Test
        @DisplayName("Should count a never-started entry apart from a timed-out one, with no elapsed time")
        void neverStartedIsItsOwnOutcome() {
            // The two call for opposite actions: a timed-out entry may already have had its effect,
            // a never-started one had none and is safe to resend. A shared counter hides that, and
            // charging it elapsed time would inflate the total the agent sizes the next batch with.
            // One entry never started beside one that completed: there IS something to read, so
            // the call succeeds and the counters tell the agent the unstarted one is safe to resend.
            batchReturns(notStarted(), completed(Map.of()));

            ToolExecutionResult result = runWithItems(items(2));

            assertThat(result.success()).isTrue();
            assertThat(body(result)).containsEntry("status", "partial");
            assertThat(body(result)).containsEntry("not_started", 1);
            assertThat(body(result)).containsEntry("timed_out", 0);
        }

        @Test
        @DisplayName("Should keep the config in the report, redacted, not merely omit the secret")
        void configIsPresentAndRedacted() {
            // Asserting only that the secret is absent passes if the whole config key is dropped.
            batchReturns(completed(Map.of()));
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("action", "run_node");
            params.put("type", "http_request");
            params.put("params", Map.of("url", "https://example.com", "api_key", "super-secret-value"));
            params.put("run_inputs", items(1));

            @SuppressWarnings("unchecked")
            Map<String, Object> config =
                    (Map<String, Object>) body(provider.execute("workflow", params, inConversation())).get("config");

            assertThat(config).containsEntry("url", "https://example.com");
            assertThat(config).containsKey("api_key");
            assertThat(String.valueOf(config.get("api_key"))).isNotEqualTo("super-secret-value");
        }

        @Test
        @DisplayName("Should not carry an unrecognised counter on a healthy batch")
        void noUnrecognisedKeyWhenThereIsNothingToReport() {
            // An always-present "unrecognised": 0 is one more field to interpret on every batch.
            batchReturns(completed(Map.of()), completed(Map.of()));

            assertThat(body(runWithItems(items(2)))).doesNotContainKey("unrecognised");
        }

        @Test
        @DisplayName("Should refuse to report a batch with no entries rather than call it complete and all-failed")
        void anEmptyOutcomeListIsRefused() {
            // The surface refuses an empty run_inputs, but the service can return an empty list and
            // the two methods compose: with no entries the counters would say completed and the
            // verdict all-failed, in one response.
            batchReturns();

            ToolExecutionResult result = runWithItems(items(1));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("nothing to report");
        }

        @Test
        @DisplayName("Should tell the agent how to keep the node, since nothing a probe runs is saved")
        void theBatchStillSaysHowToKeepTheNode() {
            // The hint is the only thing in the report that turns a probe into something durable,
            // and it is the field an agent acts on after a good batch. It sits beside config,
            // which IS asserted, so losing it would be invisible.
            batchReturns(completed(Map.of()), completed(Map.of()));

            Map<String, Object> body = body(runWithItems(items(2)));

            assertThat(String.valueOf(body.get("hint")))
                    .contains("Nothing was saved")
                    .contains("add_node")
                    .contains("code");
        }

        @Test
        @DisplayName("Should always carry the counters both help surfaces promise, even when they are zero")
        void thePromisedCountersAreAlwaysPresent() {
            // Every one of these is documented as a response field, so an agent may read it
            // without checking it exists. Only `unrecognised` is deliberately conditional, and it
            // says so in both surfaces. A counter that silently vanishes on the healthy case is
            // the shape that breaks a reader on its first bad batch instead of its first good one.
            batchReturns(completed(Map.of()), completed(Map.of()));

            Map<String, Object> body = body(runWithItems(items(2)));

            assertThat(body)
                    .containsEntry("item_count", 2)
                    .containsEntry("completed", 2)
                    .containsEntry("failed", 0)
                    .containsEntry("timed_out", 0)
                    .containsEntry("not_started", 0)
                    .containsEntry("awaiting_signal", 0);
        }

        @Test
        @DisplayName("Should say which type an alias resolved to, so the config in the hint is the one that ran")
        void aliasedTypeIsReportedOnABatchToo() {
            // The hint tells the agent to reuse this config with add_node, and add_node takes the
            // canonical type. Without resolved_from the report names a type the agent never sent
            // and never explains where it came from.
            batchReturns(completed(Map.of()), completed(Map.of()));
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("action", "run_node");
            params.put("type", "javascript");
            params.put("params", Map.of("code", "return 1;"));
            params.put("run_inputs", items(2));

            Map<String, Object> body = body(provider.execute("workflow", params, inConversation()));

            assertThat(body).containsEntry("node_type", "code");
            assertThat(body).containsEntry("resolved_from", "javascript");
        }

        @Test
        @DisplayName("Should call a batch of nothing but unrecognised statuses 'unknown', as both help surfaces say")
        void aBatchOfOnlyUnrecognisedStatusesIsUnknown() {
            // The one shape the status ladder can reach only by falling all the way through, and
            // the one the help sentence had to be reworded for: these entries did NOT end
            // differently from each other, so "unknown means they disagreed" was false here.
            batchReturns(new AdHocNodeResult("code", "SOMETHING_NEW", Map.of(), null, 5L, null),
                    new AdHocNodeResult("code", "SOMETHING_NEW", Map.of(), null, 5L, null));

            Map<String, Object> body = body(runWithItems(items(2)));

            assertThat(body).containsEntry("status", "unknown");
            assertThat(body).containsEntry("unrecognised", 2);
            assertThat(body)
                    .as("nothing completed, and nothing failed either")
                    .containsEntry("completed", 0).containsEntry("failed", 0);
        }

        @Test
        @DisplayName("Should still tell the agent what to do when no entry gave a reason for not starting")
        void nothingStartedAndNoReasonStillSaysWhatToDo() {
            // A failure result carries no body, so if the entries have no reason to quote the
            // message is all there is - and a message that only says nothing started leaves the
            // agent with no next move. It is safe to resend precisely because nothing ran.
            batchReturns(notStarted(null), notStarted(null));

            ToolExecutionResult result = runWithItems(items(2));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("None of the 2 entries started");
            assertThat(result.error()).contains("Send them again.");
        }

        @Test
        @DisplayName("Should say the node gave no reason rather than trail off when every entry failed silently")
        void allFailedWithNoReasonSaysSo() {
            // Same reason as above, on the other failing branch: an "All 2 items failed (code): "
            // ending in a colon reads as a truncated message, which sends the agent looking for
            // the rest of it instead of at its own configuration.
            batchReturns(failed(null), failed(null));

            ToolExecutionResult result = runWithItems(items(2));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("All 2 items failed");
            assertThat(result.error()).contains("gave no reason");
        }

        @Test
        @DisplayName("Should carry the batch's shape in metadata, including on the failures that have no body")
        void metadataDescribesTheBatch() {
            // metadata is what survives a failure result, which has no body at all, so it is the
            // only place the shape of an all-failed batch is recorded. That is why the status is
            // computed for the two verdicts an agent can never read in a body.
            // An ALIASED type, so the assertion below can tell the canonical type from the raw
            // one the agent sent. With type='code' the two are equal and the assertion proves
            // nothing about which of them is recorded.
            batchReturns(failed("boom"), failed("boom"));
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("action", "run_node");
            params.put("type", "javascript");
            params.put("params", Map.of("code", "return 1;"));
            params.put("run_inputs", items(2));

            ToolExecutionResult result = provider.execute("workflow", params, inConversation());

            assertThat(result.success()).isFalse();
            assertThat(result.metadata()).containsEntry("adHocStatus", "failed");
            assertThat(result.metadata()).containsEntry("adHocItemCount", 2);
            assertThat(result.metadata())
                    .as("the CANONICAL type, not the alias the agent happened to type")
                    .containsEntry("adHocNodeType", "code");
            assertThat(String.valueOf(result.metadata().get("toolName")))
                    .as("the count is in the label so a batch is not mistaken for a single run")
                    .isEqualTo("Run node: code x2");

            batchReturns(notStarted(), notStarted());
            assertThat(runWithItems(items(2)).metadata()).containsEntry("adHocStatus", "not_started");

            batchReturns(completed(Map.of()), failed("boom"));
            assertThat(runWithItems(items(2)).metadata()).containsEntry("adHocStatus", "partial");
        }

        @Test
        @DisplayName("Should surface a status it does not recognise instead of counting it as a failure")
        void anUnrecognisedStatusIsNotAFailure() {
            // Folding an unknown status into `failed` is how awaiting-signal came to be reported
            // as "all failed"; the next status added would inherit the same bug.
            batchReturns(new AdHocNodeResult("code", "SOMETHING_NEW", Map.of(), null, 5L, null),
                    completed(Map.of()));

            ToolExecutionResult result = runWithItems(items(2));

            assertThat(result.success()).isTrue();
            assertThat(body(result)).containsEntry("failed", 0);
            assertThat(body(result)).containsEntry("unrecognised", 1);
        }

        @Test
        @DisplayName("Should NOT claim every entry failed when some merely never started")
        void aFailedAndNeverStartedMixDoesNotOverclaim() {
            // The all-failed message names a count and blames the configuration. Using it here
            // would assert that entries which never ran had failed, and send the agent looking at
            // inputs for a problem that was the clock.
            batchReturns(failed("boom"), notStarted(), notStarted());

            ToolExecutionResult result = runWithItems(items(3));

            assertThat(result.success()).isTrue();
            assertThat(body(result)).containsEntry("status", "unknown");
            assertThat(body(result)).containsEntry("failed", 1);
            assertThat(body(result)).containsEntry("not_started", 2);
        }

        @Test
        @DisplayName("Should FAIL a batch where nothing ever started, because there is nothing to read")
        void nothingStartedIsAFailure() {
            // Unlike a timed-out entry, a never-started one produced no result and no side effect.
            // Reporting the call as a success would hand the agent an empty report to act on.
            // Entries can go unstarted for different reasons, and a failure result has no body:
            // the message is the only place those reasons survive, so it must carry them rather
            // than assert the clock.
            batchReturns(notStarted("The server could not start this entry"),
                    notStarted("The call was interrupted before this entry started"));

            ToolExecutionResult result = runWithItems(items(2));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("None of the 2 entries started");
            assertThat(result.error()).contains("could not start").contains("interrupted");
            assertThat(result.error()).doesNotContain("failed");
        }

        @Test
        @DisplayName("Should fail the call only when NO item completed, and say the config is the common cause")
        void allFailed() {
            batchReturns(failed("bad host"), failed("bad host"));

            ToolExecutionResult result = runWithItems(items(2));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("bad host");
            assertThat(result.error()).contains("fix it once");
        }

        @Test
        @DisplayName("Should count a timed-out item separately from a failed one")
        void timedOutIsItsOwnCount() {
            batchReturns(completed(Map.of()), timedOut(), failed("boom"));

            Map<String, Object> body = body(runWithItems(items(3)));

            assertThat(body).containsEntry("completed", 1);
            assertThat(body).containsEntry("timed_out", 1);
            assertThat(body).containsEntry("failed", 1);
        }

        @Test
        @DisplayName("Should carry an entry's note, which is the only place its remedy is written")
        void perEntryNoteSurvives() {
            // Both help texts document `note`. Dropping it leaves an awaiting-signal entry with a
            // status and no way to learn that only a real run can resume it.
            batchReturns(awaitingSignal(), completed(Map.of()));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) body(runWithItems(items(2))).get("items");

            assertThat(entries.get(0)).containsKey("note");
            assertThat(String.valueOf(entries.get(0).get("note"))).contains("real run");
            // It ran and produced an output, and the single path always emits one: dropping it
            // would leave the documented {output or error} tuple carrying neither.
            assertThat(entries.get(0)).containsKey("output");
        }

        @Test
        @DisplayName("Should give each entry its index, status and output, in input order")
        void perItemEntries() {
            batchReturns(completed(Map.of("v", "first")), failed("boom"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) body(runWithItems(items(2))).get("items");

            assertThat(entries).hasSize(2);
            assertThat(entries.get(0)).containsEntry("index", 0).containsEntry("status", "COMPLETED");
            assertThat(entries.get(0).get("output")).isEqualTo(Map.of("v", "first"));
            assertThat(entries.get(1)).containsEntry("index", 1).containsEntry("status", "FAILED");
            assertThat(entries.get(1)).containsEntry("error", "boom");
            // A failed entry has no output key to mistake for a result.
            assertThat(entries.get(1)).doesNotContainKey("output");
        }

        @Test
        @DisplayName("Should give each entry the time IT took, which is what both help surfaces promise")
        void eachEntryCarriesItsOwnDuration() {
            // duration_ms is documented per entry in items=[{index, status, duration_ms, ...}], and
            // per-entry timing is the whole reason the batch keeps a clock per entry rather than
            // one for the call. The two values differ so that neither dropping the key nor
            // charging every entry the same number can pass.
            batchReturns(completed(Map.of(), 120L), failed("boom", 340L));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) body(runWithItems(items(2))).get("items");

            assertThat(entries.get(0)).containsEntry("duration_ms", 120L);
            assertThat(entries.get(1))
                    .as("a failed entry is timed too: it spent that time before it failed")
                    .containsEntry("duration_ms", 340L);
        }

    }
}
