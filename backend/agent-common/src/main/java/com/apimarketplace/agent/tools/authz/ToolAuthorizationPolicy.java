package com.apimarketplace.agent.tools.authz;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Hand-curated source of truth for which tool actions require a synchronous
 * user authorization before they run in an interactive chat conversation.
 *
 * <p>This is the analogue of {@code BridgeAllowlist} for tool authorization:
 * a single, centralized, in-code list. When the chat agent calls one of these
 * {@code (tool, action)} pairs, the run pauses and an authorization card is
 * shown to the user (mirroring the credential-approval card). See
 * {@code ToolAuthorizationGuard} for the decision logic and the agent-service
 * call-site for enforcement.
 *
 * <p><b>Scope.</b> This list ONLY takes effect for interactive chat
 * conversations. Agents launched via workflow / task / sub-agent are exempt by
 * default - see {@code ToolAuthorizationScopeResolver} in agent-service.
 *
 * <p><b>How to add a rule.</b> Add the action to the {@link Set} of the right
 * tool below (one line). Keys are tool names and actions exactly as the facade
 * tools expose them (lowercase ASCII identifiers - do NOT introduce
 * {@code LabelNormalizer}, which is for workflow node slugs, not tool actions).
 *
 * <p>Criterion for "sensitive": spends credit, acquires/installs a resource,
 * executes something external, or performs a notable state mutation.
 */
public final class ToolAuthorizationPolicy {

    private ToolAuthorizationPolicy() {}

    /**
     * Tool name &rarr; set of actions that require synchronous user authorization.
     *
     * <p>v1 (minimal, easily extensible):
     * <ul>
     *   <li>{@code application:acquire} - acquires a marketplace resource;</li>
     *   <li>{@code application:execute} - runs an application workflow (credit + side effects);</li>
     *   <li>{@code workflow:execute} - runs a saved/built workflow directly (same credit + side
     *       effects as application:execute, just by workflow id instead of publication id);</li>
     *   <li>{@code agent:execute} - launches a sub-agent (credit / LLM spend);</li>
     *   <li>{@code catalog:execute} / {@code catalog:call} - calls an external third-party API.</li>
     * </ul>
     * To extend: add an action to a set, or add a {@code Map.entry(tool, Set.of(...))}.
     */
    public static final Map<String, Set<String>> SENSITIVE_ACTIONS = Map.of(
            "application", Set.of("acquire", "execute"),
            // run a saved workflow by id - was UNGATED (bug); plus advancing a paused run
            // (continue an interface / resolve a user approval) mutates run state + can
            // unblock downstream side-effects, so it is gated the same way in chat.
            // stop_run is deliberately NOT here, and the trade-off is NOT free - read this
            // before extending the reasoning to anything else.
            // Every other entry gates something that STARTS work or lets it continue.
            // stop_run ends work, which is why waiting for a user card would defeat it: an
            // agent that cannot stop a runaway execution until a human clicks is not a
            // safety valve. The user's own Stop button is the same operation, ungated.
            // BUT its default mode does reach beyond the single run: mode='cancel' also
            // suspends the workflow's schedule rows, so an agent can leave a scheduled
            // workflow disarmed until someone reactivates it. That is mitigated by
            // disclosure only (tool help, mode param, and the stop response all say it,
            // and point at mode='graceful' for "end this execution only").
            // If agents are observed disarming workflows users wanted running, the fix is
            // to gate 'cancel' specifically or make 'graceful' the default - not to gate
            // the whole action, which would take the safety valve away.
            // restart_from_node belongs with execute rather than with stop_run: it STARTS work.
            // On an automatic run it re-executes the named node and everything downstream
            // unattended, with the same credit spend and the same external side effects as a
            // fresh fire - only the part upstream of the node is spared.
            "workflow",    Set.of("execute", "continue_interface", "resolve_approval", "restart_from_node", "run_node"),
            "agent",       Set.of("execute"),
            "catalog",     Set.of("execute", "call")   // "call" is an alias of "execute"
    );

    /** True iff this exact {@code (toolName, action)} pair is in the sensitive list. */
    public static boolean requires(String toolName, String action) {
        if (toolName == null || action == null) {
            return false;
        }
        Set<String> actions = SENSITIVE_ACTIONS.get(toolName.toLowerCase(Locale.ROOT));
        return actions != null && actions.contains(action.toLowerCase(Locale.ROOT));
    }

    /** True iff this tool has at least one action requiring authorization. */
    public static boolean isSensitiveTool(String toolName) {
        return toolName != null && SENSITIVE_ACTIONS.containsKey(toolName.toLowerCase(Locale.ROOT));
    }

    /**
     * The one rule whose approval does NOT mean "now run the tool".
     *
     * <p>{@code application:acquire} hands the job to the USER, who installs from the
     * marketplace modal in their own time; the agent must never acquire on their behalf. So
     * this rule raises a card but can never WAIT on one, and anything sizing a budget or a
     * timeout around "this call might be held" must exclude it - otherwise a hung install
     * backend stalls the chat for the length of a wait that was never going to happen.
     */
    public static boolean isUserPerformedRule(String toolName, String action) {
        return "application".equalsIgnoreCase(toolName) && "acquire".equalsIgnoreCase(action);
    }

    /**
     * True for the calls that can ask the user to CONNECT A SERVICE, which is a different
     * question from "may I run this" and gets a different card.
     *
     * <p>It matters because the two questions come apart. A granted rule raises no
     * authorization card and needs no budget to wait for one - but the same call can still
     * hit a service the user never connected, and THAT card comes from the tool result, not
     * from the grant. Without this exception it had nothing holding the call and silently
     * fell back to the two-turn flow. Not only for people who ticked "always allow":
     * approving one card writes a one-shot grant as well, so anyone whose park expired
     * arrived at the next turn already granted and lost the connect hold there too.
     *
     * <p>Deliberately the narrow set rather than "any sensitive call": the credential
     * pre-flight lives in the catalog execute path, and every pair added here buys a longer
     * ceiling on a hung backend for a card it may never raise.
     */
    public static boolean canRaiseConnectCard(String toolName, String action) {
        return "catalog".equalsIgnoreCase(toolName)
                && ("execute".equalsIgnoreCase(action) || "call".equalsIgnoreCase(action));
    }

    /**
     * Canonical rule key {@code "tool:action"} for a matching pair, else {@code null}.
     * This is the stable identifier used for approvals (transient and persisted)
     * and dedup - never use the LLM-generated {@code toolCallId}, which changes
     * across resume turns.
     */
    public static String ruleKey(String toolName, String action) {
        return requires(toolName, action)
                ? toolName.toLowerCase(Locale.ROOT) + ":" + action.toLowerCase(Locale.ROOT)
                : null;
    }
}
