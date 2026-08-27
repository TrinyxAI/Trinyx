package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.BundleGenerationPriceDto;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Whether THIS installation can run a generation out of its own pocket.
 *
 * <p><b>Why a surface has to say it.</b> A generation runs on one of two keys:
 * the platform's, billed to the caller in credits, or the account's own, billed
 * by the provider directly. Which of the two exists is a property of the
 * INSTALL, not of the model. A cloud deployment holds platform keys for the
 * providers it resells; a self-hosted one normally holds none, and every
 * generation runs on keys its owner connected. The same model id is therefore
 * runnable two different ways depending on where it is read.
 *
 * <p><b>Two conditions, because billing enforces two.</b> A platform key that
 * exists is not enough: {@code CatalogToolBillingService} refuses a resold
 * generation for which no price is published, with
 * {@code PLATFORM_NOT_AVAILABLE}. An earlier version of this class asked only
 * whether the key existed, and so promised the platform would pay for models it
 * then refused - the worst kind of wrong answer, because it is acted on by
 * building a node that fails on its first run. Both halves are checked here, and
 * the price half is per MODEL, which is the granularity the published prices
 * themselves use.
 *
 * <p><b>Three answers, not two.</b> Both underlying calls fail SOFT: an
 * unreachable auth-service answers "key available" and "no prices". Folded into
 * a boolean, that silently becomes a confident lie in one direction or the
 * other. {@link Verdict#UNKNOWN} exists so a surface can say "could not check"
 * instead, which is the only honest thing to tell someone who is about to spend.
 */
@Slf4j
@Service
public class PlatformSalesResolver {

    /**
     * Platform keys and published prices change by an administrator's action,
     * not by traffic. Long enough that a model listing costs no round trip,
     * short enough that publishing a price shows up without a restart.
     */
    private static final Duration TTL = Duration.ofMinutes(5);

    /** What this installation can pay for, for one model. */
    public enum Verdict {
        /** A platform key exists AND a price is published: either payer works. */
        PLATFORM_OR_OWN_KEY,
        /** No platform key, or no published price: only a key the owner connected. */
        OWN_KEY_ONLY,
        /** The check itself did not answer. Neither claim may be made. */
        UNKNOWN;

        /** The wire value a caller reads, in the vocabulary of what to do next. */
        public String wire() {
            return switch (this) {
                case PLATFORM_OR_OWN_KEY -> "platform_or_own_key";
                case OWN_KEY_ONLY -> "own_key_only";
                case UNKNOWN -> "unknown";
            };
        }
    }

    private final CredentialClient credentialClient;

    /** Keyed by {@code integrationName|apiToolId|modelId}. */
    private final Cache<String, Verdict> cache = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .maximumSize(10_000)
            .build();

    public PlatformSalesResolver(CredentialClient credentialClient) {
        this.credentialClient = credentialClient;
    }

    /** One model's identity, as the published prices key it. */
    public record ModelRef(String integrationName, String apiToolId, String modelId) {

        /**
         * Built from the model itself, in ONE place: the two surfaces that ask
         * must key their question identically or they get different answers for
         * the same model.
         */
        public static ModelRef of(GenerationRegistry.GenerationModel m) {
            return new ModelRef(
                    m.platformCredentialName(),
                    m.apiToolId() == null ? null : m.apiToolId().toString(),
                    m.modelId());
        }
    }

    /**
     * Resolve a whole model list in one pass.
     *
     * <p>Bulk on purpose: the price lookup takes every integration and endpoint
     * at once, so a listing of fifty models costs one call rather than fifty.
     *
     * @return verdict per {@link ModelRef}, never null, never missing a key
     */
    public Map<ModelRef, Verdict> resolve(Collection<ModelRef> models) {
        Map<ModelRef, Verdict> out = new java.util.LinkedHashMap<>();
        if (models == null || models.isEmpty()) return out;

        java.util.List<ModelRef> unresolved = new java.util.ArrayList<>();
        for (ModelRef ref : models) {
            if (ref.integrationName() == null || ref.integrationName().isBlank()) {
                // An API that declares no platform credential is never resold.
                out.put(ref, Verdict.OWN_KEY_ONLY);
                continue;
            }
            Verdict cached = cache.getIfPresent(key(ref));
            if (cached != null) out.put(ref, cached);
            else unresolved.add(ref);
        }
        if (unresolved.isEmpty()) return out;

        Set<String> integrations = new LinkedHashSet<>();
        Set<String> toolIds = new LinkedHashSet<>();
        for (ModelRef ref : unresolved) {
            integrations.add(ref.integrationName());
            if (ref.apiToolId() != null) toolIds.add(ref.apiToolId());
        }

        // The KEY question first, and per integration rather than per model.
        // It settles the self-hosted case outright: an install that holds no
        // platform credential resells nothing, whatever the price lookup says,
        // and that is the deployment this field exists to describe. Asking it
        // first also stops an empty price list being read as "could not tell"
        // when the real answer is "there is no platform key here".
        Map<String, Boolean> keyByIntegration = new java.util.LinkedHashMap<>();
        for (ModelRef ref : unresolved) {
            keyByIntegration.computeIfAbsent(ref.integrationName(), name -> {
                try {
                    return credentialClient.platformCredentialAvailable(name);
                } catch (Exception e) {
                    log.warn("[PlatformSales] key check failed for '{}': {}", name, e.getMessage());
                    return Boolean.TRUE; // the client's own fail-open direction
                }
            });
        }

        boolean anyKey = keyByIntegration.values().stream().anyMatch(Boolean.TRUE::equals);
        Set<String> priced = Set.of();
        boolean priceCallAnswered = true;
        if (anyKey) {
            try {
                java.util.List<BundleGenerationPriceDto> prices =
                        credentialClient.fetchPublishedGenerationPrices(integrations, toolIds);
                Set<String> found = new LinkedHashSet<>();
                for (BundleGenerationPriceDto p : prices) {
                    // A price row with NO model is the ENDPOINT-WIDE one, and
                    // billing resolves the per-model row first, else this one.
                    // Matching only the exact model reported "not sold" for a
                    // model that is, and sent the reader off to find their own
                    // key for nothing.
                    if (p.modelId() == null) {
                        found.add(p.integrationName() + "|" + p.apiToolId() + "|*");
                    } else {
                        found.add(p.integrationName() + "|" + p.apiToolId() + "|" + p.modelId());
                    }
                }
                priced = found;
                // Empty is ambiguous ONLY here, where a key exists: the client
                // swallows a transport failure into the same empty list. With
                // no key at all the question never arises.
                priceCallAnswered = !prices.isEmpty();
            } catch (Exception e) {
                log.warn("[PlatformSales] published prices unavailable: {}", e.getMessage());
                priceCallAnswered = false;
            }
        }

        for (ModelRef ref : unresolved) {
            Verdict verdict;
            if (!Boolean.TRUE.equals(keyByIntegration.get(ref.integrationName()))) {
                // No platform key for the provider: nothing else can make the
                // platform pay. This is the ordinary self-hosted answer.
                verdict = Verdict.OWN_KEY_ONLY;
            } else if (!priceCallAnswered) {
                verdict = Verdict.UNKNOWN;
            } else if (priced.contains(key(ref)) || priced.contains(endpointKey(ref))) {
                verdict = Verdict.PLATFORM_OR_OWN_KEY;
            } else {
                verdict = Verdict.OWN_KEY_ONLY;
            }
            // UNKNOWN is never cached: it is a statement about a failed call,
            // not about the installation, and pinning it for five minutes would
            // outlast the outage that caused it.
            if (verdict != Verdict.UNKNOWN) cache.put(key(ref), verdict);
            out.put(ref, verdict);
        }

        return out;
    }

    private static String key(ModelRef ref) {
        return ref.integrationName() + "|" + ref.apiToolId() + "|" + ref.modelId();
    }

    /** The endpoint-wide price row, which covers every model of that endpoint. */
    private static String endpointKey(ModelRef ref) {
        return ref.integrationName() + "|" + ref.apiToolId() + "|*";
    }
}
