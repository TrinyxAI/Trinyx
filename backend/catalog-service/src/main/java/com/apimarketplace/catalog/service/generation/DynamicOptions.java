package com.apimarketplace.catalog.service.generation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * Where a parameter's admissible values come from, when no list can be written
 * down in advance.
 *
 * <p><b>Why this exists.</b> A catalogue list ({@code allowed_values}) works for
 * a set the PROVIDER defines: eleven voices, seventeen style presets. It cannot
 * work for a set the ACCOUNT defines. An ElevenLabs voice belongs to whoever
 * owns the key: the shared defaults, plus every voice that account has cloned or
 * bought. No list written into a seed is true for two different readers, and a
 * wrong one is worse than none, because the identifier is opaque and the failure
 * arrives from the provider after the call is paid for.
 *
 * <p>So the seed names the ENDPOINT that knows, and the values are fetched with
 * the caller's own credential when they are actually needed.
 *
 * <p><b>Declared on the parameter, not on the generation block</b>, and stored
 * in the parameter's existing {@code extras}. A voice list is a fact about the
 * ElevenLabs {@code voice_id} parameter, not about generating: the workflow
 * inspector asking "what can I put here" deserves the same answer, and a future
 * Slack channel or Drive folder is the same shape of problem.
 *
 * @param tool  slug of the endpoint that lists them, in the SAME api
 * @param items dotted path to the array in that endpoint's answer, or empty
 *              when the answer is the array itself
 * @param value field of each item holding the value the provider expects
 * @param label field holding what a person should read, or null to show the
 *              value. A voice id is unreadable; its name is the whole point.
 */
public record DynamicOptions(String tool, Match match, String items, String value, String label) {

    /**
     * Which ELEMENT of the source's answer holds the values, when the answer is
     * a list and only one of its entries is about the thing being configured.
     *
     * <p>The case this exists for: the languages a speech model accepts are not
     * a property of the provider, they are a property of the MODEL. The endpoint
     * that knows them answers with every model at once, each carrying its own
     * {@code languages} array. Without a way to say "the row whose model_id is
     * the one I am about to call", the descriptor could only offer the union of
     * every model's languages, which would let a caller pick a language its
     * model refuses, and be refused by the provider after the call is paid for.
     *
     * <p>Deliberately a match on a VALUE the caller already has rather than an
     * array index: an index is a fact about today's response ordering, and it
     * silently starts pointing at another model the day the provider inserts
     * one.
     *
     * @param field the field of each element to compare
     * @param from  which value the caller supplies to compare it against.
     *              {@code model} is the only one today: the id actually sent to
     *              the provider, which is what such an endpoint keys its rows on
     */
    public record Match(String field, String from) {}

    /** Key under a parameter's {@code extras} where the descriptor is stored. */
    public static final String EXTRAS_KEY = "valuesFrom";

    /**
     * Read the descriptor off a parameter's extras.
     *
     * <p>Returns empty rather than throwing on anything malformed: a parameter
     * with a broken descriptor keeps the free-text field it had before, which is
     * the same outcome as not declaring one. A surface losing a dropdown is a
     * disappointment; a catalogue that refuses to load because one seed has a
     * typo is an outage.
     */
    public static Optional<DynamicOptions> from(JsonNode extras) {
        if (extras == null || !extras.isObject()) return Optional.empty();
        JsonNode node = extras.get(EXTRAS_KEY);
        if (node == null || !node.isObject()) return Optional.empty();

        String tool = text(node, "tool");
        String value = text(node, "value");
        // Without those two there is nothing to call and nothing to read out of
        // the answer, so the descriptor cannot mean anything.
        if (tool == null || value == null) return Optional.empty();

        Match match = null;
        JsonNode matchNode = node.get("match");
        if (matchNode != null) {
            // Half a selector is worse than none: it would silently read the
            // FIRST element, so a caller would be offered another model's
            // values and find out from the provider, after paying.
            if (!matchNode.isObject()) return Optional.empty();
            String field = text(matchNode, "field");
            String from = text(matchNode, "from");
            if (field == null || from == null) return Optional.empty();
            match = new Match(field, from);
        }

        return Optional.of(new DynamicOptions(
                tool, match, text(node, "items"), value, text(node, "label")));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
