package com.apimarketplace.orchestrator.tools.workflow.builder;

/**
 * How an interface action-mapping REF is read, in one place.
 *
 * <p>A ref is {@code <prefix>:<label>:<event>}. The event is what decides the meaning:
 * {@code navigate} switches the displayed page (frontend only), everything else fires a
 * trigger.
 *
 * <p><b>The prefix does not decide it.</b> The builder's Action Mappings dropdown emitted
 * {@code trigger:<label>:navigate} for a page switch for a long time, so that shape is
 * persisted in published and cloned plans; the current producer emits
 * {@code interface:<label>:navigate}. Both mean the same thing and both must resolve
 * against INTERFACES.
 *
 * <p>This class exists because that rule was written three times - the add_node
 * validator, the modify validator, and the cross-DAG check - and the first two had
 * already drifted apart once: create accepted a legacy ref while modify reported
 * "trigger '&lt;page&gt;' not found" for the same input, on the very path the agent is told
 * to use to FIX a mapping. Adding a fourth copy is what a caller must not do.
 */
public final class ActionMappingRefs {

    private static final String NAVIGATE_EVENT = "navigate";

    private ActionMappingRefs() {}

    /**
     * Whether the ref's parts describe a page switch. Expects the already-split ref, the
     * form every caller holds at the point of the check, so no caller re-splits.
     */
    public static boolean isNavigate(String[] parts) {
        return parts != null && parts.length >= 3 && NAVIGATE_EVENT.equals(parts[parts.length - 1]);
    }

    /** The interface key a navigate ref points at, whatever prefix carries it. */
    public static String targetInterfaceKey(String[] parts) {
        return "interface:" + parts[1];
    }
}
