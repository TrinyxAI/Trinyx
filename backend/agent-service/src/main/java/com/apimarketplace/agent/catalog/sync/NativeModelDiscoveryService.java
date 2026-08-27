package com.apimarketplace.agent.catalog.sync;

import com.apimarketplace.agent.cloud.CloudRelaySupport;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.provider.OpenAICompatibleProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Third catalog source: asks each configured provider WHICH MODELS IT SERVES,
 * through its own OpenAI-compatible {@code GET <base>/models} endpoint, and
 * emits rows for the ids no feed knows about.
 *
 * <p><b>Why this exists.</b> Both feeds are third-party mirrors, and they lag
 * per vendor. Measured against the live feeds on 2026-08-19: LiteLLM's
 * {@code zai} block ended at glm-5.1 while Z.AI was already serving glm-5.2,
 * glm-5.3 (released 5 days earlier) and glm-5v-turbo; its {@code moonshot}
 * block ended at kimi-k2.6 while Kimi K3 had shipped a month before; MiniMax
 * M2.7 and DeepSeek V4 Pro were missing the same way. No amount of syncing
 * produces a model the source does not publish, so a mirror-only catalog is
 * permanently behind for exactly the vendors that ship fastest.
 *
 * <p><b>Authority split - the rule that keeps this safe.</b>
 * <ul>
 *   <li>The vendor endpoint is authoritative for EXISTENCE. If Z.AI's own API
 *       lists {@code glm-5.3}, the model is real and callable on the endpoint
 *       we have configured for that provider. This is strictly better than
 *       inferring existence from an aggregator's catalogue, which also lists
 *       third-party re-hosts the vendor's own API would 404 on.</li>
 *   <li>OpenRouter is the PRICE source. The vendor {@code /models} endpoint
 *       publishes no rates, so the row is priced from OpenRouter's entry for
 *       the same vendor id, exactly like a feed row: one pricing pathway, and
 *       no field the rest of the catalog does not already use.
 *       <p>Known and accepted consequence: OpenRouter quotes a RESALE rate,
 *       which is not always the vendor's direct one. Measured 2026-08-19,
 *       glm-5.3 (1.40/4.40), glm-5v-turbo (1.20/4.00) and minimax-m2.7
 *       (0.30/1.20) match the vendor list price exactly, while glm-5.1 and
 *       glm-5.2 carry a uniform 31% discount (0.966/3.036 against 1.40/4.40
 *       direct). A direct call on such a row is billed under the vendor rate
 *       until an admin edits it, which the review gate keeps a deliberate
 *       step since a feed insert lands disabled.</li>
 *   <li>Capabilities come from that same donor row (tools / vision /
 *       reasoning / context window).</li>
 *   <li>A model OpenRouter does NOT carry lands with no price at all, and the
 *       unpriced-enable guard in {@code ModelCatalogService} keeps it out of
 *       the picker until an admin prices it. Unpriced is not free:
 *       {@code ModelPricingService} would otherwise bill it at the platform
 *       default rate.</li>
 * </ul>
 *
 * <p><b>Auditing where a price came from.</b> Every discovered row stamps
 * {@code priceFrom} and the exact {@code openRouterId} it was taken from into
 * {@code feed_metadata}, so a rate can be traced back to a specific aggregator
 * entry long after the sync ran. The id match is exact and namespace-mapped
 * ({@code zai} to {@code z-ai}); no fuzzy matching can attach one model's
 * price to another.
 *
 * <p><b>Gap-fill only.</b> A row already carried by LiteLLM (or already in the
 * catalog) is never emitted: the feeds carry real prices and richer metadata,
 * so they stay in charge of everything they cover. Discovery only speaks for
 * what they miss.
 *
 * <p><b>Fails soft, per provider.</b> An unconfigured provider is skipped
 * (no key, nothing to ask). A provider whose endpoint errors is skipped with a
 * WARN. Neither can fail the enclosing sync - a catalog refresh must not break
 * because one vendor's status page is having a bad day.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NativeModelDiscoveryService {

    /**
     * OpenRouter namespaces the same vendor's models under its own slug, which
     * does not always match our provider name. Used ONLY to find the
     * donor row (price AND capabilities) for an id discovered from the vendor -
     * never to decide that a model exists.
     */
    static final Map<String, String> OPENROUTER_VENDOR_NAMESPACE = Map.of(
            "zai",      "z-ai",
            "moonshot", "moonshotai",
            "qwen",     "qwen",
            "minimax",  "minimax",
            "deepseek", "deepseek",
            "xai",      "x-ai",
            "mistral",  "mistralai"
    );

    private final LLMProviderFactory providerFactory;

    /** What one discovery pass found, for the sync log + admin UI. */
    public record DiscoveryResult(List<Map<String, Object>> models,
                                  Map<String, Integer> discoveredByProvider,
                                  List<String> skippedProviders) {
        public static DiscoveryResult empty() {
            return new DiscoveryResult(List.of(), Map.of(), List.of());
        }
        public int total() {
            return models.size();
        }
    }

    /**
     * Run one discovery pass.
     *
     * @param feedModels      every row the two feeds accepted this run, in the
     *                        parsers' canonical shape. Used to suppress ids a
     *                        feed already covers.
     * @param existingKeys    {@code provider + '\0' + modelId} for every row
     *                        already in {@code model_config_overrides}, so a
     *                        model an admin has already curated is left alone.
     * @param openRouterRows  the parsed OpenRouter rows, used as the price and
     *                        capability donors for the ids they carry.
     */
    public DiscoveryResult discover(List<Map<String, Object>> feedModels,
                                    Set<String> existingKeys,
                                    List<Map<String, Object>> openRouterRows) {
        Set<String> covered = new HashSet<>(existingKeys == null ? Set.of() : existingKeys);
        if (feedModels != null) {
            for (Map<String, Object> m : feedModels) {
                String p = strOf(m.get("provider"));
                String id = strOf(m.get("modelId"));
                if (p != null && id != null) covered.add(key(p, id));
            }
        }

        Map<String, Map<String, Object>> donorsByVendorId = indexOpenRouterDonors(openRouterRows);

        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Integer> perProvider = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();

        for (String providerName : sortedRelayProviders()) {
            OpenAICompatibleProvider provider = openAiCompatible(providerName);
            if (provider == null) {
                // Native-SDK providers (anthropic/openai/google/...) and the
                // CLI bridges have no OpenAI-compatible /models endpoint here.
                // They are also the ones the feeds cover best, so there is
                // nothing to gap-fill.
                continue;
            }
            Optional<List<String>> listed = provider.listRemoteModelIds();
            if (listed.isEmpty()) {
                skipped.add(providerName);
                continue;
            }

            int added = 0;
            for (String modelId : listed.get()) {
                if (modelId == null || modelId.isBlank()) continue;
                String id = modelId.trim();
                if (covered.contains(key(providerName, id))) continue;
                covered.add(key(providerName, id));  // a vendor listing the same id twice
                out.add(buildRow(providerName, id, donorsByVendorId));
                added++;
            }
            if (added > 0) perProvider.put(providerName, added);
        }

        log.info("Native discovery: {} new model(s) across {} provider(s); skipped (no key or endpoint error): {}",
                out.size(), perProvider.size(), skipped);

        return new DiscoveryResult(out, perProvider, skipped);
    }

    /**
     * Index OpenRouter rows by {@code <ourProvider>\0<bareId>} so a discovered
     * id can find the donor that supplies its price. OpenRouter ids are
     * {@code <vendor>/<id>}, sometimes with a {@code :variant} suffix; the
     * suffixed variants are dropped by the parser already, so a plain split is
     * enough.
     */
    private static Map<String, Map<String, Object>> indexOpenRouterDonors(
            List<Map<String, Object>> openRouterRows) {
        if (openRouterRows == null || openRouterRows.isEmpty()) return Map.of();

        Map<String, String> namespaceToProvider = new HashMap<>();
        OPENROUTER_VENDOR_NAMESPACE.forEach((ours, ns) -> namespaceToProvider.put(ns, ours));

        Map<String, Map<String, Object>> donors = new HashMap<>();
        for (Map<String, Object> row : openRouterRows) {
            String fullId = strOf(row.get("modelId"));
            if (fullId == null) continue;
            int slash = fullId.indexOf('/');
            if (slash <= 0 || slash == fullId.length() - 1) continue;
            String ourProvider = namespaceToProvider.get(fullId.substring(0, slash));
            if (ourProvider == null) continue;
            donors.putIfAbsent(key(ourProvider, fullId.substring(slash + 1)), row);
        }
        return donors;
    }

    /**
     * Build the canonical merge row for a discovered model. Deliberately
     * price-free: {@code priceInput} / {@code priceOutput} stay absent so the
     * row lands unpriced and un-enableable rather than carrying a number
     * nobody verified.
     */
    private static Map<String, Object> buildRow(String provider, String modelId,
                                                Map<String, Map<String, Object>> donors) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider", provider);
        row.put("modelId", modelId);
        row.put("displayName", modelId);
        row.put("source", "discovery");
        row.put("mode", "chat");

        Map<String, Object> donor = donors.get(key(provider, modelId));
        if (donor != null) {
            // Pricing, taken verbatim from the donor - same fields the
            // OpenRouter parser fills on its own rows, so a discovered row is
            // indistinguishable from a feed row downstream.
            copyIfPresent(donor, row, "priceInput");
            copyIfPresent(donor, row, "priceOutput");
            copyIfPresent(donor, row, "priceFloorInput");
            copyIfPresent(donor, row, "priceFloorOutput");
            copyIfPresent(donor, row, "priceCacheRead");
            copyIfPresent(donor, row, "priceCacheWrite");
            copyIfPresent(donor, row, "supportsPromptCaching");
            // tier is a deterministic function of the output price, so it must
            // travel with it or the row lands in the wrong bucket.
            copyIfPresent(donor, row, "tier");

            copyIfPresent(donor, row, "contextWindow");
            copyIfPresent(donor, row, "maxOutputTokens");
            copyIfPresent(donor, row, "supportsTools");
            copyIfPresent(donor, row, "supportsVision");
            copyIfPresent(donor, row, "supportsReasoning");
            copyIfPresent(donor, row, "supportsResponseSchema");
            copyIfPresent(donor, row, "supportedModalities");
            copyIfPresent(donor, row, "supportedOutputModalities");
        }

        // Provenance. This is what makes "is that really OpenRouter's price?"
        // an answerable question months later, per row, without re-deriving
        // anything: the donor's exact aggregator id is recorded next to the
        // rate it supplied.
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", "provider-models-endpoint");
        meta.put("provider", provider);
        if (donor != null) {
            meta.put("priceFrom", "openrouter");
            meta.put("openRouterId", donor.get("modelId"));
            meta.put("capabilitiesFrom", "openrouter");
        } else {
            meta.put("priceFrom", "none");
            meta.put("capabilitiesFrom", "none");
            meta.put("pricing", "unpriced - OpenRouter carries no entry for this vendor id");
        }
        row.put("feedMetadata", meta);

        return row;
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String field) {
        Object v = from.get(field);
        if (v != null) to.put(field, v);
    }

    /** Relay-executable providers, in a stable order so logs and tests are deterministic. */
    private static List<String> sortedRelayProviders() {
        List<String> names = new ArrayList<>(CloudRelaySupport.supportedProviders());
        Collections.sort(names);
        return names;
    }

    private OpenAICompatibleProvider openAiCompatible(String providerName) {
        LLMProvider provider = providerFactory.findProvider(providerName).orElse(null);
        return provider instanceof OpenAICompatibleProvider oa ? oa : null;
    }

    /**
     * The {@code existingKeys} key format, shared with
     * {@link ModelCatalogSyncService} so caller and callee cannot drift on the
     * separator. Package-private rather than private so tests build the key
     * through it instead of hard-coding the delimiter.
     */
    static String key(String provider, String modelId) {
        return provider + '\0' + modelId;
    }

    private static String strOf(Object v) {
        return v == null ? null : v.toString();
    }
}
