package com.apimarketplace.catalog.service.generation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a surface is told about the values a generation parameter accepts.
 *
 * <p><b>Why this is one class and not two loops.</b> The agent's
 * {@code generation(action='models')} and the app's {@code GET
 * /api/generation/models} both describe the same model, and they described it
 * with two hand-kept copies of the same shaping. Two surfaces disagreeing about
 * a limit is how a builder shows a restriction nothing enforces, or hides one
 * that does.
 *
 * <p>What is shared is the CONTENT of {@code limits}. Whether an empty result
 * is published at all still belongs to each caller, and they differ on purpose:
 * the agent omits the key (a payload it pays for in context), the app publishes
 * {@code {}} (a field its type declares as always present).
 *
 * <p><b>Two sources, one shape.</b> A limit is either DECLARED by the
 * generation descriptor, or INHERITED from the catalog parameter the descriptor
 * writes to. The declared one wins whenever it exists: a descriptor narrows a
 * provider's list on purpose (a model that accepts nine of its provider's
 * eleven voices), so treating the catalog as an override would widen it back
 * and offer values that model refuses.
 *
 * <p><b>Inherited values are shown, never enforced.</b> They are not merged
 * into {@link GenerationSpec.Constraint}, which
 * {@link GenerationRequestBuilder} checks before a call is billed, and which
 * {@link GenerationSpec.Model#defaultMeasurement()} reads to pick the size a
 * run is charged for. A catalogue list can be stale or partial; refusing a
 * value the provider accepts, or moving the billed default, is a real cost, and
 * neither is worth paying for a dropdown. So the inheritance fills a blank on
 * screen and changes nothing about what runs. It is published with
 * {@code allowedEnforced:false} so a surface can tell the two apart: the
 * workflow builder keeps its expression field for an advisory list, because a
 * parameter there is often bound to runtime data rather than typed.
 */
public final class GenerationLimits {

    /**
     * How many inherited values are inlined before the list is summarised.
     *
     * <p>The agent's copy of this payload is TOKENS, and it carries every model
     * at once. One provider's voice catalogue runs to a hundred entries, which
     * would cost more context than the rest of the answer put together and
     * would push the models it is meant to describe out of view. Past the cap
     * the count is reported instead, which is the part an agent can act on: it
     * says "ask the person" rather than inventing an identifier.
     *
     * <p>Set above the largest closed set the catalogue actually documents (a
     * provider's 17 style presets) and well below the largest open-ended one (a
     * hundred-odd voices). A cap that truncates a complete set is worse than no
     * cap: the agent is told the list is a sample and stops trusting it, for
     * the price of a few dozen tokens it could have had in full.
     *
     * <p>The app's copy is a browser fetch where the cost is bytes, so it takes
     * the whole list. That difference is deliberate and is the only one.
     */
    public static final int AGENT_INLINE_CAP = 25;

    private GenerationLimits() {
    }

    /**
     * The wire shape for one model's limits.
     *
     * @param model     the resolved model, carrying its declared constraints
     * @param inherited catalogue values per UNIFIED parameter name, for the
     *                  parameters the descriptor left unrestricted; empty when
     *                  nothing was resolved
     * @param cap       maximum inherited values to inline, or a non-positive
     *                  number for all of them
     */
    public static Map<String, Object> describe(GenerationSpec.Model model,
                                               Map<String, List<String>> inherited,
                                               int cap) {
        return describe(model, inherited, cap, Set.of());
    }

    /**
     * The same shape, plus the parameters whose values only the provider can
     * name.
     *
     * <p>A third kind of list, and the reason it is a flag rather than values:
     * an ElevenLabs voice belongs to the account holding the key, so there is
     * nothing to inline here that would be true for two different readers. The
     * flag says "there IS a list, ask for it with the caller's own credential",
     * which is a different sentence from both "here are the values" and
     * silence, and it is the one that stops a caller inventing an identifier.
     *
     * @param dynamic UNIFIED parameter names that can be fetched, from
     *                {@link DynamicOptionsResolver#unifiedDynamicParameters}
     */
    public static Map<String, Object> describe(GenerationSpec.Model model,
                                               Map<String, List<String>> inherited,
                                               int cap,
                                               Set<String> dynamic) {
        Map<String, Object> limits = describeStatic(model, inherited, cap);
        if (dynamic == null) return limits;
        for (String param : dynamic) {
            // Merged into whatever the declared or inherited pass left, rather
            // than replacing it: a parameter can carry both a documented list
            // and an account-specific one, and dropping the first would hide a
            // limit that IS enforced.
            Map<String, Object> lim = asMap(limits.get(param));
            if (lim == null) lim = new LinkedHashMap<>();
            lim.put("optionsAvailable", true);
            limits.put(param, lim);
        }
        return limits;
    }

    private static Map<String, Object> describeStatic(GenerationSpec.Model model,
                                                      Map<String, List<String>> inherited,
                                                      int cap) {
        Map<String, Object> limits = new LinkedHashMap<>();

        model.constraints().forEach((param, c) -> {
            Map<String, Object> lim = new LinkedHashMap<>();
            if (!c.allowed().isEmpty()) lim.put("allowed", c.allowed());
            if (c.min() != null) lim.put("min", c.min());
            if (c.max() != null) lim.put("max", c.max());
            // A text cap is a limit like any other, and the one a caller trips
            // by accident rather than by guessing: a prompt assembled over
            // several turns grows past it with nothing to signal it.
            if (c.maxLength() != null) lim.put("maxLength", c.maxLength());
            // An empty entry would say "this parameter is restricted" and name
            // no restriction, which is worse than saying nothing.
            if (!lim.isEmpty()) limits.put(param, lim);
        });

        if (inherited == null) return limits;

        inherited.forEach((param, values) -> {
            if (values == null || values.isEmpty()) return;
            // Declared wins. Reaching a parameter that already has an `allowed`
            // would widen a list the descriptor narrowed on purpose.
            Map<String, Object> existing = asMap(limits.get(param));
            if (existing != null && existing.containsKey("allowed")) return;

            Map<String, Object> lim = existing == null ? new LinkedHashMap<>() : existing;
            // Named, because the two kinds of list fail in opposite directions.
            // A DECLARED list is refused before the call is billed, so a value
            // outside it cannot be spent. An inherited one is not checked
            // anywhere: it describes what the provider documents, which can be
            // stale or partial, so a surface that turned it into a closed
            // choice would forbid values the platform accepts. Marked false
            // rather than marking the declared ones true: absent means the
            // ordinary, enforced case.
            lim.put("allowedEnforced", false);
            if (cap > 0 && values.size() > cap) {
                lim.put("allowed", new ArrayList<>(values.subList(0, cap)));
                // Named so the reader knows the list is a sample rather than
                // the whole set: a truncated list read as exhaustive is how a
                // caller concludes a value does not exist.
                lim.put("allowedTruncated", true);
                lim.put("allowedCount", values.size());
            } else {
                lim.put("allowed", new ArrayList<>(values));
            }
            limits.put(param, lim);
        });

        return limits;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }
}
