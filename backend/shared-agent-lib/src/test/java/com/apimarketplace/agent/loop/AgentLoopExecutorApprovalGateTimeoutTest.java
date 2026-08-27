package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.logging.AgentLogger;
import com.apimarketplace.agent.tool.ToolExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A call that can park on a user approval card needs a budget bigger than the ordinary 30 s
 * tool timeout, or it would be discarded as timed out while its card is still on screen -
 * and the user's click would then release a call whose result nobody collects.
 *
 * <p>The budget is a licence to hang for five extra minutes, so who receives it matters as
 * much as the number. Two conditions, both narrow: the exact {@code (tool, action)} pair
 * must be gateable, and the context must be one where a card is raised at all. Widen either
 * and a hung backend stops being a 30 s blip on the busiest tools in the product.
 */
@DisplayName("AgentLoopExecutor - timeout budget for calls that can park on an approval")
class AgentLoopExecutorApprovalGateTimeoutTest {

    private static final long DEFAULT_TIMEOUT_MS = 30_000;
    /** Kept in sync with AgentLoopExecutor.APPROVAL_GATE_BUDGET_MS. */
    private static final long GATE_BUDGET_MS = 300_000;

    private AgentLoopExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new AgentLoopExecutor(
                mock(ToolExecutionService.class), mock(AgentLogger.class),
                Executors.newCachedThreadPool(), DEFAULT_TIMEOUT_MS, false);
    }

    private static ToolDefinition tool(String name, Long timeoutMs) {
        return ToolDefinition.builder().name(name).timeoutMs(timeoutMs).build();
    }

    private static ToolCall call(String toolName, String action) {
        return new ToolCall("call-1", toolName, Map.of("action", action), null);
    }

    /** The one shape that raises a card: the general interactive chat, no bound agent. */
    private static Map<String, Object> interactiveChat() {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("conversationId", "conv-1");
        credentials.put("__streamId__", "stream-1");
        return credentials;
    }

    @Test
    @DisplayName("A gateable action in an interactive chat gets the approval budget")
    void gateableActionGetsTheApprovalBudget() {
        assertThat(executor.effectiveTimeoutMs(call("workflow", "execute"), tool("workflow", null), interactiveChat()))
                .isEqualTo(DEFAULT_TIMEOUT_MS + GATE_BUDGET_MS);
        assertThat(executor.effectiveTimeoutMs(call("catalog", "execute"), tool("catalog", null), interactiveChat()))
                .isEqualTo(DEFAULT_TIMEOUT_MS + GATE_BUDGET_MS);
        assertThat(executor.effectiveTimeoutMs(call("application", "execute"), tool("application", null), interactiveChat()))
                .isEqualTo(DEFAULT_TIMEOUT_MS + GATE_BUDGET_MS);
        assertThat(executor.effectiveTimeoutMs(call("agent", "execute"), tool("agent", null), interactiveChat()))
                .isEqualTo(DEFAULT_TIMEOUT_MS + GATE_BUDGET_MS);
    }

    @Test
    @DisplayName("application:acquire raises a card but never waits on one, so it gets no budget")
    void userPerformedRuleGetsNoBudget() {
        // Approving it means "the USER installs it from the marketplace", not "run the tool",
        // so the call is never held. Granting the wait budget anyway turns a hung install
        // backend into a 5.5-minute stall for a wait that cannot happen.
        assertThat(executor.effectiveTimeoutMs(call("application", "acquire"), tool("application", null), interactiveChat()))
                .isEqualTo(DEFAULT_TIMEOUT_MS);
    }

    @Test
    @DisplayName("A gateable tool with its own long timeout keeps it, plus the budget")
    void gateableToolKeepsItsOwnTimeoutPlusTheBudget() {
        assertThat(executor.effectiveTimeoutMs(call("catalog", "execute"), tool("catalog", 120_000L), interactiveChat()))
                .isEqualTo(120_000L + GATE_BUDGET_MS);
    }

    @Test
    @DisplayName("A NON-gateable action on a gateable tool keeps the ordinary timeout")
    void readActionsOnGateableToolsAreUntouched() {
        // These four are the busiest facade tools in the product and most of their traffic is
        // reads. Keying on the tool NAME would hand every one of them the park budget, so a
        // hung backend would stall each call for 5 min instead of failing in 30 s.
        assertThat(executor.effectiveTimeoutMs(call("catalog", "search"), tool("catalog", null), interactiveChat()))
                .isEqualTo(DEFAULT_TIMEOUT_MS);
        assertThat(executor.effectiveTimeoutMs(call("workflow", "get"), tool("workflow", null), interactiveChat()))
                .isEqualTo(DEFAULT_TIMEOUT_MS);
        assertThat(executor.effectiveTimeoutMs(call("agent", "list"), tool("agent", null), interactiveChat()))
                .isEqualTo(DEFAULT_TIMEOUT_MS);
        assertThat(executor.effectiveTimeoutMs(call("application", "list"), tool("application", null), interactiveChat()))
                .isEqualTo(DEFAULT_TIMEOUT_MS);
    }

    @Test
    @DisplayName("A context that never raises a card gets no budget - there is nobody to wait for")
    void exemptContextsGetNoBudget() {
        // Each of these is exempt from the authorization card by design, so a park would
        // wait out its full deadline for a click nobody is there to make.
        Map<String, Object> scheduledRun = interactiveChat();
        scheduledRun.put("__workflowRunId__", "run-1");
        Map<String, Object> task = interactiveChat();
        task.put("__taskId__", "task-1");
        Map<String, Object> subAgent = interactiveChat();
        subAgent.put("__agent_depth__", 1);
        Map<String, Object> agentBackedChat = interactiveChat();
        agentBackedChat.put("__agentId__", "agent-1");

        for (Map<String, Object> exempt : java.util.List.of(scheduledRun, task, subAgent, agentBackedChat)) {
            assertThat(executor.effectiveTimeoutMs(call("workflow", "execute"), tool("workflow", null), exempt))
                    .isEqualTo(DEFAULT_TIMEOUT_MS);
        }
        // Headless: no conversation, no stream.
        assertThat(executor.effectiveTimeoutMs(call("workflow", "execute"), tool("workflow", null), Map.of()))
                .isEqualTo(DEFAULT_TIMEOUT_MS);
        assertThat(executor.effectiveTimeoutMs(call("workflow", "execute"), tool("workflow", null), null))
                .isEqualTo(DEFAULT_TIMEOUT_MS);
    }

    @Test
    @DisplayName("An agent that explicitly asks to be gated gets the budget back")
    void agentOptingIntoAuthorizationGetsTheBudget() {
        // The card and the budget must follow the same rule, including its opt-in seam:
        // an agent that raises cards but times out in 30 s would never survive its own card.
        Map<String, Object> optedIn = interactiveChat();
        optedIn.put("__agentId__", "agent-1");
        optedIn.put("__requireToolAuthorization__", true);

        assertThat(executor.effectiveTimeoutMs(call("workflow", "execute"), tool("workflow", null), optedIn))
                .isEqualTo(DEFAULT_TIMEOUT_MS + GATE_BUDGET_MS);
    }

    @Test
    @DisplayName("An ordinary tool is untouched - a hung call is still caught as fast as before")
    void ordinaryToolIsUntouched() {
        assertThat(executor.effectiveTimeoutMs(call("web_search", "query"), tool("web_search", 640_000L), interactiveChat()))
                .isEqualTo(640_000L);
        assertThat(executor.effectiveTimeoutMs(call("files", "list"), tool("files", null), interactiveChat()))
                .isEqualTo(DEFAULT_TIMEOUT_MS);
    }

    @Test
    @DisplayName("A sensitive tool called with no resolvable action gets the budget (it is gated too)")
    void unresolvableActionIsTreatedAsGateable() {
        // ToolAuthorizationGuard fails CLOSED here and raises a card, so the budget must
        // follow, or the very call that shows a card would be killed under it.
        assertThat(executor.effectiveTimeoutMs(
                new ToolCall("call-1", "workflow", Map.of(), null), tool("workflow", null), interactiveChat()))
                .isEqualTo(DEFAULT_TIMEOUT_MS + GATE_BUDGET_MS);
    }

    @Test
    @DisplayName("A missing tool definition still resolves, and still respects gateability")
    void missingDefinitionStillResolves() {
        assertThat(executor.effectiveTimeoutMs(call("files", "list"), null, interactiveChat()))
                .isEqualTo(DEFAULT_TIMEOUT_MS);
        assertThat(executor.effectiveTimeoutMs(call("workflow", "execute"), null, interactiveChat()))
                .isEqualTo(DEFAULT_TIMEOUT_MS + GATE_BUDGET_MS);
    }
}
