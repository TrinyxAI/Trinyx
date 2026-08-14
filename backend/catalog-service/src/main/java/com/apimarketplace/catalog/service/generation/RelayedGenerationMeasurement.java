package com.apimarketplace.catalog.service.generation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * What a relayed generation is, and how big it is, measured from the request
 * body the cloud already received.
 *
 * <p><b>Why the cloud measures instead of being told.</b> A self-hosted install
 * relaying a call is a customer's own server. If it declared the size of its own
 * generation, it could declare a ten second video as one second and be billed
 * for one, which is the same class of hole the gateway closes by stripping the
 * generation billing headers off every inbound request. The cloud owns the
 * catalog, so it can read both facts out of the provider-shaped parameters it
 * was sent, using the same descriptor that produced them. Nothing extra travels
 * over the wire, and there is nothing for a caller to under-report.
 *
 * <p>The descriptor is read in the direction opposite to
 * {@link GenerationRequestBuilder}: {@code modelParam} names the upstream field
 * that selects the model, and the measuring parameter's binding names the path
 * that carries the size, so both are recovered by reading those paths back.
 * A binding may also carry a {@code scale} (a model sold per minute whose
 * provider takes seconds); the writer multiplies by it, so the reader divides.
 */
public final class RelayedGenerationMeasurement {

    private RelayedGenerationMeasurement() {}

    /**
     * @param modelId  the generation model the call names, null when the
     *                 descriptor has no model selector or the body does not
     *                 carry one
     * @param quantity the PLATFORM measurement of the call (seconds, assets,
     *                 characters), null when the call cannot be measured from
     *                 this body. Null is not zero: it means the price has to be
     *                 resolved the conservative way, never that the call is free.
     */
    public record Measured(String modelId, BigDecimal quantity, String quantityUnit) {
        public static final Measured NOTHING = new Measured(null, null, null);
    }

    /**
     * Read the model and the size out of an upstream request body.
     *
     * <p>Never throws: a body that does not match the descriptor yields
     * {@link Measured#NOTHING}, and the caller then prices it as it did before
     * this existed, which refuses rather than guesses.
     */
    public static Measured measure(GenerationSpec spec, Map<String, Object> upstreamParams) {
        if (spec == null || upstreamParams == null || upstreamParams.isEmpty()) {
            return Measured.NOTHING;
        }
        String modelId = readModelId(spec, upstreamParams);
        GenerationSpec.Model model = resolveModel(spec, modelId);
        if (model == null) {
            return new Measured(modelId, null, null);
        }
        // The unit comes from the model, through the SAME accessor the direct
        // path uses (GenerationRequestBuilder.platformUnitFor), so a number and
        // the name of what it counts cannot be derived from different rules.
        // A quantity without its unit cannot be checked against a published
        // rate: a row priced per image and a call measured in seconds both
        // arrive as a bare 10.
        return new Measured(modelId,
                readQuantity(spec, model, upstreamParams),
                GenerationRequestBuilder.platformUnitFor(model));
    }

    /**
     * What a call on this model is measured in, for a caller that already knows
     * the model and does not have a body to read.
     *
     * <p>Exists so the read-only quote can ask the SAME question the billing
     * path asks. A quote that skipped the unit would answer with an amount the
     * biller then refuses, which is a disagreement no response shows: both look
     * like ordinary successes on their own.
     *
     * @return the platform unit, or null when the model is unknown here, which
     *         means "cannot tell" and never "no unit"
     */
    public static String platformUnitFor(GenerationSpec spec, String modelId) {
        if (spec == null) return null;
        GenerationSpec.Model model = resolveModel(spec, modelId);
        return model == null ? null : GenerationRequestBuilder.platformUnitFor(model);
    }

    /** The model the body selects, by the upstream field the descriptor declares. */
    private static String readModelId(GenerationSpec spec, Map<String, Object> params) {
        String modelParam = spec.modelParam();
        if (modelParam == null || modelParam.isBlank()) {
            // A single-model endpoint has no selector; the one model IS the answer.
            return spec.models().size() == 1 ? spec.models().get(0).id() : null;
        }
        Object raw = GenerationRequestBuilder.getByPath(params, modelParam);
        if (raw == null) return null;
        String upstream = String.valueOf(raw).trim();
        if (upstream.isEmpty()) return null;
        // The body carries the UPSTREAM model name; the price is published
        // against the public id, so translate rather than compare the two.
        for (GenerationSpec.Model m : spec.models()) {
            if (upstream.equals(m.upstream()) || upstream.equals(m.id())) {
                return m.id();
            }
        }
        return null;
    }

    private static GenerationSpec.Model resolveModel(GenerationSpec spec, String modelId) {
        if (modelId == null) return null;
        return spec.models().stream()
                .filter(m -> modelId.equals(m.id()))
                .findFirst()
                .orElse(null);
    }

    /**
     * The size of this call in PLATFORM units, or null when the body does not
     * carry it.
     *
     * <p>A model sold per call needs no measurement and reports one, which is
     * exactly what its price expects. Anything else is read from the path its
     * binding declares, with the writer's scale undone.
     */
    private static BigDecimal readQuantity(GenerationSpec spec, GenerationSpec.Model model,
                                            Map<String, Object> params) {
        String measuring = model.measuringParam();
        if (measuring == null) {
            // Sold per call: the quantity is one call, and it is knowable
            // without reading anything.
            return BigDecimal.ONE;
        }
        if ("prompt".equals(measuring)) {
            // Measured by its own text: the length of what was actually sent,
            // which is the same thing the direct path counts. An empty string
            // is not a measurement, for the reason given at the signum check
            // below.
            Object text = readBound(spec, measuring, params);
            if (text == null) return null;
            int length = String.valueOf(text).length();
            return length <= 0 ? null : BigDecimal.valueOf(length);
        }
        Object raw = readBound(spec, measuring, params);
        if (raw == null) return null;
        BigDecimal value;
        try {
            value = new BigDecimal(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        // NON-POSITIVE IS NOT A MEASUREMENT, and this must match the direct
        // path exactly: CatalogToolBillingService.sizeMissing treats null and
        // <= 0 as the same statement, "nobody said how big this is", because a
        // generation of zero seconds is not a call anyone meant to make.
        //
        // Reading a 0 as a real size is worse here than it looks. The resolver
        // reports unitCredits as ZERO whenever ANY quantity was supplied (a
        // measured per-unit amount is no longer the price of one unit), so
        // FrozenMarkupDto.isPricedPerUnitWithoutQuantity stays false and the
        // relay's only per-unit refusal cannot fire. The amount is then
        // base + rate x 0, clamped UP to minCredits, which is positive, so the
        // install is billed a floor for a size it never stated while the
        // provider charges the platform owner for whatever it auto-selected.
        // Returning null instead lets that refusal fire, which is the outcome
        // the direct path already produces for the same input.
        if (value.signum() <= 0) return null;

        GenerationSpec.ParamBinding binding = spec.paramMap().get(measuring);
        BigDecimal scale = binding == null ? null : binding.scale();
        if (scale == null || scale.signum() == 0) {
            return value;
        }
        // The writer multiplied by the scale on the way out, so the reader
        // divides on the way back. Kept exact where it can be, and rounded UP
        // where it cannot: a rounding that favoured the caller would be a
        // discount nobody published.
        return value.divide(scale, 6, RoundingMode.CEILING).stripTrailingZeros();
    }

    /** Value at the upstream path a unified parameter is bound to. */
    private static Object readBound(GenerationSpec spec, String unifiedParam,
                                     Map<String, Object> params) {
        GenerationSpec.ParamBinding binding = spec.paramMap().get(unifiedParam);
        if (binding == null || binding.path() == null) return null;
        return GenerationRequestBuilder.getByPath(params, binding.path());
    }
}
