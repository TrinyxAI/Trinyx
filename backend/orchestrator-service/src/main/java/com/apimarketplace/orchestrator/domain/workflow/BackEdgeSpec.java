package com.apimarketplace.orchestrator.domain.workflow;

/**
 * Resolved description of ONE loop-back, whatever shape it was authored in.
 *
 * <p>Two shapes collapse into this single descriptor so the execution engine keeps one loop path:
 *
 * <table>
 *   <tr><th></th><th>Loop hub ({@code core:loop})</th><th>Declared back-edge (hub-less)</th></tr>
 *   <tr><td>authored as</td><td>edge to {@code core:label:iterate}</td><td>any edge carrying an
 *       {@link Edge.BackEdge} marker</td></tr>
 *   <tr><td>{@link #hubKey()}</td><td>the loop Core key</td><td>{@code null}</td></tr>
 *   <tr><td>{@link #bodyEntryKey()}</td><td>target of the Core's {@code :body} edge</td>
 *       <td>the back-edge's own target</td></tr>
 *   <tr><td>{@link #condition()} / {@link #maxIterationsOverride()}</td><td>from the Core</td>
 *       <td>from the edge marker</td></tr>
 *   <tr><td>{@link #exitTargetKey()}</td><td>target of the Core's {@code :exit} edge</td>
 *       <td>{@code null} (the source's other port already routed)</td></tr>
 * </table>
 *
 * <p>Hub-specific behaviour is guarded on {@code hubKey() != null}; everything else (iteration
 * counting, span reset, re-traversal) is shape-agnostic.
 *
 * @param edge                  the plan edge that closes the loop
 * @param edgeId                stable identity used to key iteration state ({@code from->to})
 * @param sourceKey             port-stripped key of the node whose completion closes the loop
 * @param sourcePort            port on the source side, or null. When set, the loop only fires if
 *                              the source node actually selected that port on this pass.
 * @param bodyEntryKey          node the engine re-traverses from on each iteration
 * @param hubKey                loop Core key, or null for a hub-less back-edge
 * @param condition             expression re-evaluated before each re-entry; blank = always
 * @param maxIterationsOverride explicitly authored cap, or null to resolve from workflow/global
 * @param exitTargetKey         node to activate when the loop terminates, or null
 */
public record BackEdgeSpec(
    Edge edge,
    String edgeId,
    String sourceKey,
    String sourcePort,
    String bodyEntryKey,
    String hubKey,
    String condition,
    Integer maxIterationsOverride,
    String exitTargetKey
) {

    /** True when this loop-back is driven by a {@code core:loop} hub. */
    public boolean hasHub() {
        return hubKey != null;
    }

    /**
     * True when the cap is the ONLY declared way out, i.e. reaching it is what the author asked
     * for rather than a safety net firing.
     *
     * <p>Only a hub can express that: a {@code core:loop} with a blank condition means "repeat N
     * times" ({@code LoopNode} enters the body unconditionally and the cap ends it). A hub-less
     * back-edge with a blank condition means the opposite: it iterates because the source keeps
     * routing to it, so the cap firing means the author's routing never stopped.
     *
     * <p>A condition that is a constant true counts as blank. It can never become false, so the
     * cap is by construction the loop's only exit, which is the same "repeat N times" contract
     * written a different way. Reading it as a live condition would fail every such loop at the
     * very moment it did exactly what it was authored to do.
     */
    public boolean capIsDeclaredTerminator() {
        return hasHub() && isAlwaysTrue(condition);
    }

    /** Blank, or a literal the author cannot have meant as a stopping test. */
    private static boolean isAlwaysTrue(String expression) {
        if (expression == null || expression.isBlank()) {
            return true;
        }
        String normalized = expression.trim();
        // Tolerate the template form the builder emits for a hand-typed literal.
        if (normalized.startsWith("{{") && normalized.endsWith("}}")) {
            normalized = normalized.substring(2, normalized.length() - 2).trim();
        }
        return normalized.equalsIgnoreCase("true");
    }
}
