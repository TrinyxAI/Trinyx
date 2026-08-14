package com.apimarketplace.agent.tools.authz;

import java.util.Map;

/**
 * Whether the caller's context is one where a tool-authorization card is actually raised.
 *
 * <p>This is THE predicate, shared. It used to live only in agent-service, next to the code that
 * raises the card, and any other surface wanting to know "will the user be asked?" had to guess.
 * Guessing is not close enough: a plausible-looking test on
 * {@code conversationId != null || streamId != null} says yes for a sub-agent, for an
 * agent-backed chat, and for an agent node inside an unattended scheduled run, all of which are
 * EXEMPT from the card. An action gated on that guess would run unattended with no one to ask.
 *
 * <p>The rules, in the order they are decided:
 * <ol>
 *   <li>a sub-agent (depth >= 1) inherits its parent's authorization, so no card;</li>
 *   <li>a workflow- or task-driven execution was authorized when the workflow or task was, so
 *       no card;</li>
 *   <li>an interactive conversation (a conversation id AND a live stream) DOES get a card,
 *       unless a specific agent is bound to it, which makes it "an agent" rather than the
 *       general chat;</li>
 *   <li>anything else is headless, so no card.</li>
 * </ol>
 * At every exit an explicit per-agent flag can force the card back on.
 */
public final class ToolAuthorizationScope {

    public static final String KEY_AGENT_DEPTH = "__agent_depth__";
    public static final String KEY_CONVERSATION_ID = "conversationId";
    public static final String KEY_STREAM_ID = "__streamId__";
    public static final String KEY_STREAM_ID_PLAIN = "streamId";
    public static final String KEY_WORKFLOW_RUN_ID = "__workflowRunId__";
    public static final String KEY_WORKFLOW_RUN_ID_PLAIN = "workflowRunId";
    public static final String KEY_TASK_ID = "__taskId__";
    public static final String KEY_AGENT_ID = "__agentId__";
    public static final String KEY_REQUIRE_AUTHORIZATION = "__requireToolAuthorization__";

    private ToolAuthorizationScope() {
    }

    /** True when a sensitive action in this context would raise a card the user can answer. */
    public static boolean isCardRaised(Map<String, Object> credentials) {
        if (credentials == null) {
            return false;
        }
        boolean agentOverride = isTruthy(credentials.get(KEY_REQUIRE_AUTHORIZATION));

        if (agentDepth(credentials) >= 1) {
            return agentOverride;
        }
        if (hasText(credentials.get(KEY_WORKFLOW_RUN_ID))
                || hasText(credentials.get(KEY_WORKFLOW_RUN_ID_PLAIN))
                || hasText(credentials.get(KEY_TASK_ID))) {
            return agentOverride;
        }
        boolean interactiveChat = hasText(credentials.get(KEY_CONVERSATION_ID))
                && (hasText(credentials.get(KEY_STREAM_ID)) || hasText(credentials.get(KEY_STREAM_ID_PLAIN)));
        if (interactiveChat) {
            if (hasText(credentials.get(KEY_AGENT_ID))) {
                return agentOverride;
            }
            return true;
        }
        return agentOverride;
    }

    private static int agentDepth(Map<String, Object> credentials) {
        Object depth = credentials.get(KEY_AGENT_DEPTH);
        if (depth instanceof Number n) {
            return n.intValue();
        }
        if (depth instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private static boolean isTruthy(Object value) {
        if (value instanceof Boolean b) return b;
        return value != null && Boolean.parseBoolean(String.valueOf(value).trim());
    }
}
